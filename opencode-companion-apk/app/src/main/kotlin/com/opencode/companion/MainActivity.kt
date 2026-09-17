package com.opencode.companion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import android.widget.TextView
import java.util.Locale
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private lateinit var webView: WebView
    private lateinit var txtStatus: TextView
    private lateinit var txtSttResult: TextView
    private lateinit var txtVoice: TextView

    private val reqMic = registerForActivityResult(ActivityResultContracts.RequestPermission()){ ok ->
        if(ok) toast("Micrófono concedido")
        else toast("Micrófono denegado — STT no funcionará")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // crDroid 15 edge-to-edge: NO usar FLAG_LAYOUT_NO_LIMITS sin insets — respeta status bar vía WindowCompat + WindowInsetsCompat
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // status bar transparente pero con contraste (no invade contenido)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = true
            window.isNavigationBarContrastEnforced = true
        }
        setContentView(R.layout.activity_main)
        // aplica WindowInsetsCompat al contenedor raíz del WebView para desplazar todo bajo el notch y que la barra quede limpia en crDroid 15
        val root = findViewById<androidx.coordinatorlayout.widget.CoordinatorLayout>(R.id.root)
        // AppBarLayout necesita su top inset separado para no quedar bajo la status bar
        val appBar = findViewById<com.google.android.material.appbar.AppBarLayout>(R.id.appbar)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            // root: solo statusBar top + nav+ime bottom; appBar recibe su propio paddingTop para no duplicar
            v.updatePadding(top = statusBars.top, bottom = maxOf(navBars.bottom, ime.bottom))
            appBar?.updatePadding(top = statusBars.top)
            // WebView contenedor: desplaza contenido bajo el notch (statusBars top ya aplicado en root)
            val swipe = findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.swipe)
            swipe?.updatePadding(top = 0)
            findViewById<WebView>(R.id.webview)?.let { wv ->
                ViewCompat.setOnApplyWindowInsetsListener(wv) { vw, ins ->
                    val b = ins.getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.ime())
                    vw.updatePadding(bottom = b.bottom)
                    ins
                }
            }
            insets
        }

        txtStatus = findViewById(R.id.txtStatus)
        txtVoice = findViewById(R.id.txtVoice)
        txtSttResult = findViewById(R.id.txtSttResult)
        webView = findViewById(R.id.webview)

        tts = TextToSpeech(this, this)

        setupWebView()
        setupButtons()
        // botón nativo Iniciar Sistema — dispara keepalive.sh con ROOT + polling 1s hasta 200
        findViewById<MaterialButton>(R.id.btnNativeStart)?.setOnClickListener {
            it.isEnabled = false
            startRootSystemAndPoll()
        }
        ensurePermissions()
        startCompanionService()
        refreshStatus()

        // Flujo autónomo: si hub ya está 200, carga chat directo; si no, muestra overlay nativo Iniciar Sistema
        checkHubOnStart()

        // auto-refresh
        webView.postDelayed({ refreshStatus() }, 1500)
    }

    private fun setupWebView(){
        WebView.setWebContentsDebuggingEnabled(true)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        webView.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        webView.clearCache(true)
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) {
                    val desc = error.description?.toString() ?: ""
                    val code = error.errorCode
                    if (code == ERROR_CONNECT || code == ERROR_HOST_LOOKUP || code == ERROR_TIMEOUT
                        || desc.contains("ERR_CONNECTION_REFUSED") || desc.contains("ERR_CONNECTION_TIMED_OUT")
                        || desc.contains("ERR_NAME_NOT_RESOLVED")) {
                        view.post { showNativeOfflineOverlay() }
                    }
                }
                super.onReceivedError(view, request, error)
            }
            @Suppress("DEPRECATION")
            override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
                if (description?.contains("ERR_CONNECTION_REFUSED") == true
                    || errorCode == ERROR_CONNECT || errorCode == ERROR_HOST_LOOKUP) {
                    view.post { showNativeOfflineOverlay() }
                }
                super.onReceivedError(view, errorCode, description, failingUrl)
            }
            override fun onPageFinished(view: WebView?, url: String?) { super.onPageFinished(view, url) }
        }
        val hubUrl = "http://127.0.0.1:8765"
        // Carga diferida: onCreate decide si cargar directo o mostrar overlay nativo; no cargar aquí incondicionalmente
        // webView.loadUrl(hubUrl) se llama tras checkHubReady()
        val swipe = findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.swipe)
        swipe?.setOnRefreshListener {
            // pull-to-refresh también respeta estado: si hub caído, reintenta check en vez de reload error
            lifecycleScope.launch {
                if (isHubReady()) {
                    hideNativeOverlay()
                    webView.loadUrl(hubUrl)
                } else {
                    webView.reload()
                }
                swipe.isRefreshing = false
            }
        }
    }

    // ---- Arranque autónomo desde APK (ROOT) ----
    private var isStartingSystem = false
    private fun showNativeOfflineOverlay() {
        val overlay = findViewById<android.view.View>(R.id.nativeOverlay) ?: return
        val progress = findViewById<android.widget.ProgressBar>(R.id.nativeProgress)
        val txt = findViewById<TextView>(R.id.nativeStatus)
        val btn = findViewById<MaterialButton>(R.id.btnNativeStart)
        overlay.visibility = android.view.View.VISIBLE
        progress?.visibility = android.view.View.GONE
        txt?.text = "Sistema desconectado — hub 8765 no responde. Pulsa Iniciar Sistema para levantar Ubuntu y OpenCode con ROOT."
        btn?.visibility = android.view.View.VISIBLE
        btn?.isEnabled = true
    }
    private fun hideNativeOverlay() {
        findViewById<android.view.View>(R.id.nativeOverlay)?.visibility = android.view.View.GONE
    }
    private fun showNativeLoading(msg: String = "Iniciando servicios y levantando Hub...") {
        val overlay = findViewById<android.view.View>(R.id.nativeOverlay) ?: return
        val progress = findViewById<android.widget.ProgressBar>(R.id.nativeProgress)
        val txt = findViewById<TextView>(R.id.nativeStatus)
        val btn = findViewById<MaterialButton>(R.id.btnNativeStart)
        overlay.visibility = android.view.View.VISIBLE
        progress?.visibility = android.view.View.VISIBLE
        txt?.text = msg
        btn?.visibility = android.view.View.GONE
    }
    private suspend fun isHubReady(): Boolean = withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val url = java.net.URL("http://127.0.0.1:8765/api/system/status")
            (url.openConnection() as java.net.HttpURLConnection).run {
                connectTimeout = 1500; readTimeout = 1500; requestMethod = "GET"
                val code = responseCode
                if (code == 200) {
                    val body = inputStream.bufferedReader().readText()
                    // listo = ready:true y healthy:true
                    body.contains("\"ready\":true")
                } else false
            }
        } catch (_: Exception) { false }
    }
    private fun startRootSystemAndPoll() {
        if (isStartingSystem) return
        isStartingSystem = true
        showNativeLoading("Iniciando servicios y levantando Hub...")
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // 1) Ejecuta keepalive.sh con ROOT — levanta hub 8765 + opencode 4096 + Ubuntu
                try {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "sh /sdcard/projects/opencode-companion/keepalive.sh")).waitFor()
                } catch (e: Exception) {
                    // fallback sin su si no hay root (no debería pasar en POCO F3)
                    try { Runtime.getRuntime().exec(arrayOf("sh", "/sdcard/projects/opencode-companion/keepalive.sh")).waitFor() } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    findViewById<TextView>(R.id.nativeStatus)?.text = "Error ejecutando keepalive.sh: ${e.message}"
                    findViewById<android.widget.ProgressBar>(R.id.nativeProgress)?.visibility = android.view.View.GONE
                    findViewById<MaterialButton>(R.id.btnNativeStart)?.visibility = android.view.View.VISIBLE
                    findViewById<MaterialButton>(R.id.btnNativeStart)?.isEnabled = true
                    isStartingSystem = false
                }
                return@launch
            }
            // 2) Polling cada 1s hasta HTTP 200 en /api/system/status
            var attempts = 0
            var ready = false
            while (attempts < 30 && !ready) {
                kotlinx.coroutines.delay(1000)
                ready = isHubReady()
                attempts++
                if (!ready) {
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        findViewById<TextView>(R.id.nativeStatus)?.text = "Iniciando servicios y levantando Hub... (${attempts}s)"
                    }
                }
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                isStartingSystem = false
                if (ready) {
                    findViewById<android.widget.ProgressBar>(R.id.nativeProgress)?.visibility = android.view.View.GONE
                    findViewById<TextView>(R.id.nativeStatus)?.text = "Hub listo — cargando chat..."
                    hideNativeOverlay()
                    webView.loadUrl("http://127.0.0.1:8765")
                    // limpia WebView caché congelada que mostraba ERR_CONNECTION_REFUSED
                    webView.clearCache(true)
                    toast("Hub levantado — chat cargado")
                } else {
                    findViewById<TextView>(R.id.nativeStatus)?.text = "Timeout: hub no respondió 200 en 30s. Reintenta Iniciar Sistema."
                    findViewById<android.widget.ProgressBar>(R.id.nativeProgress)?.visibility = android.view.View.GONE
                    findViewById<MaterialButton>(R.id.btnNativeStart)?.visibility = android.view.View.VISIBLE
                    findViewById<MaterialButton>(R.id.btnNativeStart)?.isEnabled = true
                }
            }
        }
    }
    private fun checkHubOnStart() {
        lifecycleScope.launch {
            val ready = isHubReady()
            if (ready) {
                hideNativeOverlay()
                webView.loadUrl("http://127.0.0.1:8765")
            } else {
                // sistema desconectado — muestra overlay con botón Iniciar Sistema
                showNativeOfflineOverlay()
                // no cargues webView hasta que usuario pulse o hub esté listo (evita ERR_CONNECTION_REFUSED feo)
            }
        }
    }

    private fun setupButtons(){
        findViewById<MaterialButton>(R.id.btnStartHub).setOnClickListener {
            RootShell.exec("sh /sdcard/projects/opencode-companion/start-hub.sh 2>&1 | head -n 80")
            toast("Hub iniciado — revisa estado")
            webView.postDelayed({ webView.loadUrl("http://127.0.0.1:8765"); refreshStatus() }, 1800)
        }
        findViewById<MaterialButton>(R.id.btnEnableA11y).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("Activa: Opencode Companion")
        }
        findViewById<MaterialButton>(R.id.btnOpenHub).setOnClickListener {
            webView.loadUrl("http://127.0.0.1:8765")
        }
        findViewById<MaterialButton>(R.id.btnCopyUrl).setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("hub", "http://127.0.0.1:8765"))
            toast("URL copiada")
        }
        findViewById<MaterialButton>(R.id.btnMic).setOnClickListener { startListening() }
        findViewById<MaterialButton>(R.id.btnStopTts).setOnClickListener { tts?.stop() }
    }

    private fun ensurePermissions(){
        if(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED){
            reqMic.launch(Manifest.permission.RECORD_AUDIO)
        }
        if(Build.VERSION.SDK_INT >= 33){
            if(ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED){
                registerForActivityResult(ActivityResultContracts.RequestPermission()){}.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun startCompanionService(){
        val i = Intent(this, CompanionService::class.java)
        if(Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    private fun refreshStatus(){
        val a11yOn = OpencodeAccessibilityService.instance != null
        val hook = if(a11yOn) "✓ accesibilidad ON" else "✗ accesibilidad OFF (toca Activar)"
        // quick shell probe for root
        Thread{
            val r = RootShell.exec("id; su -c id 2>&1 | head -1")
            val rootLine = r.stdout.lines().firstOrNull{ it.contains("uid=0")} ?: "no root"
            runOnUiThread{
                txtStatus.text = "a11y: $hook\nroot: $rootLine\nbridge: http://127.0.0.1:8766/status\n hub: http://127.0.0.1:8765  (WebView abajo)\n\nSi el hub no carga: pulsa 'Iniciar hub'."
                findViewById<TextView>(R.id.txtHubUrl)?.text = "http://127.0.0.1:8765  ·  bridge 8766 " + (if(a11yOn) "✓" else "✗ a11y")
            }
        }.start()
    }

    // ---- TTS ----
    override fun onInit(status: Int) {
        if(status==TextToSpeech.SUCCESS){
            tts?.language = Locale("es","ES")
            txtVoice.text = "TTS: listo (${tts?.language}) · STT: toca 🎙"
        }
    }
    fun speak(text:String){
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "utt1")
        txtSttResult.text = "TTS: $text".take(800)
    }

    // ---- STT nativo (sin restricción HTTPS, a diferencia de Web Speech API) ----
    private fun startListening(){
        if(!SpeechRecognizer.isRecognitionAvailable(this)){
            toast("STT no disponible en este dispositivo"); return
        }
        if(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            reqMic.launch(Manifest.permission.RECORD_AUDIO); return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply{
            setRecognitionListener(object: android.speech.RecognitionListener{
                override fun onReadyForSpeech(p: Bundle?) { txtVoice.text="STT: escuchando… habla"; }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() { txtVoice.text="STT: procesando…" }
                override fun onError(e:Int) { txtVoice.text="STT error $e"; toast("STT error $e") }
                override fun onResults(b: Bundle?) {
                    val list = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = list?.firstOrNull() ?: ""
                    txtSttResult.text = "Tú: $text"
                    txtVoice.text = "STT: \"$text\" → enviando al hub…"
                    // Enviar al hub web vía WebView JS injection: pone texto en #prompt y envía
                    // Si el hub no está cargado en webview, al menos mostrar TTS y ofrecer ejecutar shell
                    handleVoiceCommand(text)
                }
                override fun onPartialResults(b: Bundle?) {
                    val p = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if(!p.isNullOrEmpty()) txtSttResult.text = "…$p"
                }
                override fun onEvent(t:Int, b:Bundle?){}
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        recognizer?.startListening(intent)
        txtVoice.text="STT: escuchando…"
    }

    private fun handleVoiceCommand(text:String){
        if(text.isBlank()) return
        // 1) intenta inyectar en el hub WebView (si está cargado)
        val esc = text.replace("\\","\\\\").replace("'","\\'").replace("\n"," ")
        webView.evaluateJavascript("""
            (function(){
              var p=document.getElementById('prompt');
              if(p){ p.value='${esc}'; p.focus(); p.dispatchEvent(new Event('input',{bubbles:true})); return 'injected'; }
              return 'no-prompt';
            })()
        """.trimIndent()){ res ->
            if(res?.contains("injected")==true){
                // auto-enviar si parece comando natural: always inject, let user press send or auto?
                // For hands-free: if text ends with "enviar", auto-click send
                if(esc.lowercase().endsWith("enviar") || esc.lowercase().endsWith("envía")){
                    webView.evaluateJavascript("document.getElementById('btn-send')?.click()", null)
                    speak("Enviando: ${esc.substringBeforeLast(" ")}")
                } else {
                    // Para voz 100% manos libres, enviamos igual después de 500ms y avisamos
                    // Pero dejamos que el usuario confirme tocando enviar — duplicado? opt: auto tras 1s si no hay interacción
                    // Aquí auto-enviamos para fluidez — el hub permite abortar
                    webView.postDelayed({
                        webView.evaluateJavascript("document.getElementById('btn-send')?.click()", null)
                    }, 700)
                    speak("Enviado al agente")
                }
            } else {
                // fallback: habla y ofrece acciones rápidas locales
                val lower = text.lowercase()
                when{
                    lower.contains("abre yape") || lower.contains("abrir yape") -> {
                        RootShell.launchPackage("com.bcp.bo.wallet")
                        speak("Abriendo Yape")
                    }
                    lower.contains("abre proton") -> {
                        RootShell.launchPackage("ch.protonmail.android")
                        speak("Abriendo Proton Mail")
                    }
                    lower.contains("inicio") || lower.contains("home") -> {
                        OpencodeAccessibilityService.instance?.pressHome() ?: RootShell.keyEvent(3)
                        speak("Inicio")
                    }
                    lower.contains("atrás") || lower.contains("volver") -> {
                        OpencodeAccessibilityService.instance?.pressBack() ?: RootShell.keyEvent(4)
                        speak("Atrás")
                    }
                    else -> speak("No tengo el hub abierto. Di el comando de nuevo con el hub visible, o abre el hub y dicta allí.")
                }
            }
        }
    }

    private fun toast(m:String)= Toast.makeText(this,m,Toast.LENGTH_SHORT).show()
    override fun onDestroy() { tts?.shutdown(); recognizer?.destroy(); super.onDestroy() }
    private fun String.lowercase():String = this.lowercase(Locale.ROOT)
}
