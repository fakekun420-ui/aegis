package com.aegis.hub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Servicio foreground + HTTP bridge en 127.0.0.1:8766
 * Endpoints que el hub (8765) consume:
 *  POST /a11y {action, text, viewId, x, y}  -> usa AccessibilityService
 *  POST /shell {cmd}
 *  POST /launch {pkg}
 *  GET  /status
 *  GET  /dump   -> window dump
 */
class CompanionService : Service() {

    private var server: SimpleHttpServer? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        startForegroundNotif()
        server = SimpleHttpServer(8766).also { it.start() }
    }
    override fun onDestroy() {
        try { server?.stopServer() } catch(_:Exception){}
        scope.cancel()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun startForegroundNotif(){
        val chId = "opencode-companion"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(chId, "Aegis", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
        val n: Notification = NotificationCompat.Builder(this, chId)
            .setContentTitle("Aegis activo")
            .setContentText("Bridge 8766 · Accesibilidad · Voz nativa")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        startForeground(1, n)
    }

    // --- Minimal HTTP server sin dependencias externas (evita añadir NanoHTTPD) ---
    inner class SimpleHttpServer(val port:Int): Thread("companion-http") {
        @Volatile var running = true
        var socket: ServerSocket? = null
        override fun run(){
            try{
                socket = ServerSocket(port)
                socket!!.soTimeout = 0
                while(running){
                    try{
                        val c = socket!!.accept()
                        // handle in pool
                        Executors.newCachedThreadPool().submit { handleClient(c) }
                    }catch(e: Exception){ if(running) Thread.sleep(200) }
                }
            }catch(e: Exception){ e.printStackTrace() }
        }
        fun stopServer(){ running=false; try{ socket?.close() }catch(_:Exception){}; interrupt() }
    }

    private fun handleClient(sock: Socket){
        try{
            sock.soTimeout = 12000
            val inp = BufferedReader(InputStreamReader(sock.getInputStream()))
            val out = sock.getOutputStream()
            val reqLine = inp.readLine() ?: return
            val parts = reqLine.split(" ")
            if(parts.size < 2) return
            val method = parts[0]
            val path = parts[1].substringBefore("?")
            // headers
            var contentLen = 0
            var line: String?
            while(inp.readLine().also { line = it } != null){
                if(line!!.isEmpty()) break
                if(line!!.startsWith("Content-Length:", true)) contentLen = line!!.substringAfter(":").trim().toIntOrNull() ?: 0
            }
            val bodyChars = CharArray(contentLen.coerceAtLeast(0))
            if(contentLen>0){
                var read=0
                while(read < contentLen){
                    val r = inp.read(bodyChars, read, contentLen-read)
                    if(r<=0) break
                    read+=r
                }
            }
            val body = String(bodyChars).trim()

            val resp: Pair<Int,String> = when{
                path=="/status" && method=="GET" -> 200 to JSONObject().apply{
                    put("ok", true)
                    put("a11y", OpencodeAccessibilityService.instance != null)
                    put("needsA11yRepair", OpencodeAccessibilityService.needsA11yRepair)
                    put("pkg", packageName)
                    put("port", 8766)
                    put("lastPkg", OpencodeAccessibilityService.lastEventPkg)
                }.toString()

                path=="/dump" && method=="GET" -> {
                    val svc = OpencodeAccessibilityService.instance
                    if(svc==null) 503 to JSONObject().put("error","a11y not enabled").toString()
                    else 200 to JSONObject().put("dump", svc.dumpWindow()).toString()
                }

                path=="/a11y" && method=="POST" -> handleA11y(body)
                path=="/shell" && method=="POST" -> handleShell(body)
                path=="/launch" && method=="POST" -> handleLaunch(body)
                else -> 404 to JSONObject().put("error","not found $path").toString()
            }
            val json = resp.second
            val code = resp.first
            val headers = "HTTP/1.1 $code ${if(code==200)"OK" else "ERR"}\r\nContent-Type: application/json; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: *\r\nAccess-Control-Allow-Methods: GET,POST,OPTIONS\r\nContent-Length: ${json.toByteArray().size}\r\nConnection: close\r\n\r\n"
            out.write(headers.toByteArray())
            out.write(json.toByteArray())
            out.flush()
        }catch(e: Exception){
            try{
                val err = JSONObject().put("error", e.message).toString()
                val h = "HTTP/1.1 500 ERR\r\nContent-Type: application/json\r\nContent-Length: ${err.length}\r\nConnection: close\r\n\r\n"
                sock.getOutputStream().write((h+err).toByteArray())
            }catch(_:Exception){}
        } finally { try{ sock.close() }catch(_:Exception){} }
    }

    private fun handleA11y(body:String): Pair<Int,String>{
        val svc = OpencodeAccessibilityService.instance
            ?: return 503 to JSONObject().put("error","AccessibilityService no activo. Actívalo en Ajustes > Accesibilidad").put("hint","Abre la app Companion y pulsa 'Activar accesibilidad'").toString()
        return try{
            val j = JSONObject(if(body.isEmpty()) "{}" else body)
            val action = j.optString("action","")
            val res = JSONObject()
            when(action){
                "clickText" -> {
                    val t = j.optString("text","")
                    res.put("ok", svc.clickByText(t, j.optBoolean("exact", false)))
                    res.put("action", action); res.put("text", t)
                }
                "clickId" -> {
                    val id = j.optString("viewId","")
                    res.put("ok", svc.clickById(id))
                    res.put("action", action); res.put("viewId", id)
                }
                "setText" -> {
                    val id = j.optString("viewId","")
                    val txt = j.optString("text","")
                    res.put("ok", svc.setTextById(id, txt))
                }
                "tap" -> {
                    val x=j.optInt("x",-1); val y=j.optInt("y",-1)
                    res.put("ok", svc.tapAt(x,y))
                    res.put("x",x); res.put("y",y)
                }
                "back" -> res.put("ok", svc.pressBack())
                "home" -> res.put("ok", svc.pressHome())
                "recents" -> res.put("ok", svc.pressRecents())
                "dump" -> res.put("dump", svc.dumpWindow(j.optInt("max",12000)))
                else -> return 400 to JSONObject().put("error","unknown action $action. actions: clickText, clickId, setText, tap, back, home, recents, dump").toString()
            }
            200 to res.toString()
        }catch(e:Exception){ 500 to JSONObject().put("error", e.message).toString() }
    }
    private fun handleShell(body:String): Pair<Int,String>{
        return try{
            val j = JSONObject(if(body.isEmpty()) "{}" else body)
            val cmd = j.optString("cmd","")
            if(cmd.isEmpty()) return 400 to JSONObject().put("error","cmd required").toString()
            val r = RootShell.exec(cmd, j.optLong("timeout",15000))
            200 to JSONObject().put("code", r.code).put("stdout", r.stdout).put("stderr", r.stderr).toString()
        }catch(e:Exception){ 500 to JSONObject().put("error", e.message).toString() }
    }
    private fun handleLaunch(body:String): Pair<Int,String>{
        return try{
            val j = JSONObject(if(body.isEmpty()) "{}" else body)
            val pkg = j.optString("pkg","")
            if(pkg.isEmpty()) return 400 to JSONObject().put("error","pkg required").toString()
            val r = RootShell.launchPackage(pkg)
            200 to JSONObject().put("code", r.code).put("stdout", r.stdout).toString()
        }catch(e:Exception){ 500 to JSONObject().put("error", e.message).toString() }
    }
}
