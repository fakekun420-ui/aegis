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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.opencode.companion.ui.AppNavHost

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null

    private var systemReady by mutableStateOf(false)
    private var systemOwnership by mutableStateOf("unknown")
    private var isStartingSystem by mutableStateOf(false)

    private val reqMic = registerForActivityResult(ActivityResultContracts.RequestPermission()){ ok ->
        if(ok) toast("Micrófono concedido") else toast("Micrófono denegado — STT no funcionará")
    }
    private val reqContacts = registerForActivityResult(ActivityResultContracts.RequestPermission()){ ok ->
        if(ok) toast("Contactos concedidos") else toast("Contactos denegados — WhatsApp por nombre no funcionará")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = true
            window.isNavigationBarContrastEnforced = true
        }

        tts = TextToSpeech(this, this)

        setContent {
            MaterialTheme(
                colorScheme = if (true) darkColorScheme(
                    primary = androidx.compose.ui.graphics.Color(0xFF7C5CFF),
                    background = androidx.compose.ui.graphics.Color(0xFF0A0A0F),
                    surface = androidx.compose.ui.graphics.Color(0xFF14141C)
                ) else lightColorScheme()
            ) {
                Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    AppNavHost()
                    if (!systemReady) {
                        NativeOfflineOverlay(
                            ownership = systemOwnership,
                            isStarting = isStartingSystem,
                            onStartSystem = { startRootSystemAndPoll() }
                        )
                    }
                }
            }
        }

        ensurePermissions()
        startCompanionService()
        checkHubOnStart()
    }

    private var lastBootError: String? by mutableStateOf<String?>(null)

    @Composable
    private fun NativeOfflineOverlay(ownership: String, isStarting: Boolean, onStartSystem: () -> Unit) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("◉", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text("Sistema desconectado", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Hub 8765 no responde ($ownership). Pulsa Iniciar Sistema para levantar Ubuntu y OpenCode con ROOT.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                if (isStarting) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text("Iniciando servicios...", style = MaterialTheme.typography.bodySmall)
                } else {
                    Button(onClick = onStartSystem, modifier = Modifier.fillMaxWidth()) { Text("Iniciar Sistema") }
                }
                lastBootError?.let { err ->
                    Spacer(Modifier.height(8.dp))
                    Text("Error: $err", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Ejecuta su -c 'sh /sdcard/projects/opencode-companion/keepalive.sh'",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    private suspend fun checkSystemReady(): Pair<Boolean,String> = withContext(Dispatchers.IO) {
        try {
            val url = java.net.URL("http://127.0.0.1:8765/api/system/status")
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 3000; readTimeout = 3000; requestMethod = "GET"
            }
            val code = conn.responseCode
            if (code != 200) return@withContext Pair(false, "http:$code")
            val body = conn.inputStream.bufferedReader().readText()
            val ready = body.contains("\"ready\":true")
            val ownership = when {
                body.contains("\"sessionOwnership\":\"termux-native\"") -> "termux-native"
                body.contains("\"sessionOwnership\":\"companion-owned\"") -> "companion-owned"
                body.contains("\"sessionOwnership\":\"none\"") -> "none"
                else -> "unknown"
            }
            android.util.Log.i("OpenCodeBoot", "checkSystemReady ready=$ready ownership=$ownership code=200")
            return@withContext Pair(ready, ownership)
        } catch (e: Exception) {
            android.util.Log.w("OpenCodeBoot", "checkSystemReady fail: ${e.message}")
            return@withContext Pair(false, "error:${e.message?.take(60)}")
        }
    }

    private suspend fun isHubReady(): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = java.net.URL("http://127.0.0.1:8765/api/system/status")
            (url.openConnection() as java.net.HttpURLConnection).run {
                connectTimeout = 1500; readTimeout = 1500; requestMethod = "GET"
                responseCode == 200
            }
        } catch (_: Exception) { false }
    }

    private fun startRootSystemAndPoll() {
        if (isStartingSystem) return
        isStartingSystem = true
        lifecycleScope.launch(Dispatchers.IO) {
            var execExit = -1
            try {
                val script = "/sdcard/projects/opencode-companion/keepalive.sh"
                val sysLog = "/sdcard/projects/opencode-companion/hub-startup.log"
                // Resolve node INSIDE the su shell (it sees host mounts; the app ns 4026535508
                // does not — node lives only in termux ns 4026535555). Pass it as NODE_BIN
                // env so keepalive.sh never needs /usr/bin/node itself. No nsenter: the old
                // pid-guess resolved /proc/PID/root in OUR ns, picked adb fork-server, and
                // dropped keepalive where node is invisible -> infinite relaunch loop.
                val resolve = Runtime.getRuntime().exec(arrayOf("su", "-c", "for nb in /usr/bin/node /data/data/com.termux/files/usr/bin/node; do [ -x "\$nb" ] && { echo "\$nb"; break; }; done; command -v node 2>/dev/null; echo done"))
                val nodeBin = try { resolve.inputStream.bufferedReader().readText().trim().lines().firstOrNull { it.isNotBlank() && it != "done" }?.trim() } catch (_: Exception) { null }
                android.util.Log.i("OpenCodeBoot", "keepalive nodeBin=$nodeBin")
                val direct = arrayOf("su", "-c", "export PATH=/data/data/com.termux/files/usr/bin:\$PATH; NODE_BIN=\"${nodeBin ?: "/usr/bin/node"}\" nohup sh \"$script\" >> \"$sysLog\" 2>&1 & echo launched")
                val proc = Runtime.getRuntime().exec(direct)
                execExit = proc.waitFor()
                val outText = try { proc.inputStream.bufferedReader().readText().trim() } catch (_: Exception) { "" }
                val errText = try { proc.errorStream.bufferedReader().readText().trim() } catch (_: Exception) { "" }
                android.util.Log.i("OpenCodeBoot", "keepalive exec exit=$execExit out=${outText.take(120)} err=${errText.take(300)}")
                if (!outText.contains("launched")) execExit = 98
            } catch (e: Exception) {
                android.util.Log.e("OpenCodeBoot", "keepalive exec exception", e)
                withContext(Dispatchers.Main) { isStartingSystem = false }
                return@launch
            }
            var attempts = 0
            var ready = false
            val maxAttempts = 90
            while (attempts < maxAttempts && !ready) {
                delay(500)
                ready = isHubReady()
                attempts++
            }
            withContext(Dispatchers.Main) {
                isStartingSystem = false
                if (ready) {
                    systemReady = true
                    systemOwnership = "ready"
                    lastBootError = null
                    toast("Hub levantado")
                } else {
                    val reason = when {
                        execExit == 99 -> "no se encontró namespace host con /usr/bin/node"
                        execExit != 0 -> "keepalive exit=$execExit"
                        else -> "hub sin 200 tras 45s"
                    }
                    lastBootError = reason
                    android.util.Log.e("OpenCodeBoot", "timeout 45s sin 200 ($reason)")
                    toast("Timeout 45s: $reason")
                }
            }
        }
    }

    private fun checkHubOnStart() {
        lifecycleScope.launch {
            val result = withTimeoutOrNull(3000L) { checkSystemReady() }
            val (ready, info) = result ?: Pair(false, "timeout:3s")
            systemReady = ready
            systemOwnership = info
            if (ready) {
                android.util.Log.i("OpenCodeBoot", "checkHubOnStart ready=true ($info) — Compose ready")
            } else {
                android.util.Log.i("OpenCodeBoot", "checkHubOnStart ready=false ($info) — overlay")
                if (result == null) android.util.Log.w("OpenCodeBoot", "checkHubOnStart timed out after 3s")
            }
        }
    }

    // ---- minimal retained helpers from previous WebView version (wake word gating, TTS, etc.) ----
    companion object {
        const val PREF_WAKE = "voice_prefs"
        const val KEY_WAKE_PHRASES = "wake_phrases_json"
        val DEFAULT_WAKE = arrayOf("viernes escucha", "hola viernes", "viernes atenta")
    }
    fun getWakePhrases(): Array<String> {
        val p = getSharedPreferences(PREF_WAKE, MODE_PRIVATE)
        val raw = p.getString(KEY_WAKE_PHRASES, null)
        return try { if (raw != null) org.json.JSONArray(raw).let { j -> Array(j.length()) { j.getString(it) } } else DEFAULT_WAKE } catch (_:Exception) { DEFAULT_WAKE }
    }
    fun saveWakePhrases(arr: Array<String>) {
        getSharedPreferences(PREF_WAKE, MODE_PRIVATE).edit().putString(KEY_WAKE_PHRASES, org.json.JSONArray(arr.toList()).toString()).apply()
    }

    private var wakeRecognizer: SpeechRecognizer? = null
    private var wakeListening = false
    private var duplexEnabledInSession: Boolean = false
    private fun containsWakeWord(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return getWakePhrases().any { ph -> lower.contains(ph.lowercase(Locale.ROOT)) }
    }
    private fun isNativeOverlayVisible(): Boolean = !systemReady
    private fun shouldWakeListen(): Boolean {
        if (isNativeOverlayVisible()) return false
        if (duplexEnabledInSession) return true
        return false
    }

    @android.webkit.JavascriptInterface
    fun onVoiceModeChanged(duplex: Boolean) {
        duplexEnabledInSession = duplex
        android.util.Log.i("OpenCodeWake", "onVoiceModeChanged duplex=$duplex")
        if (duplex && !isNativeOverlayVisible()) lifecycleScope.launch { startWakeWordListener() } else if (!duplex) stopWakeWordListener()
    }
    fun startWakeWordListener() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        if (isNativeOverlayVisible()) return
        if (!shouldWakeListen()) return
        if (wakeListening) return
        wakeListening = true
        wakeRecognizer?.destroy()
        wakeRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(e: Int) {
                    wakeListening = false
                    if (shouldWakeListen()) lifecycleScope.launch { delay(900); startWakeWordListener() }
                }
                override fun onResults(b: Bundle?) {
                    val text = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                    if (containsWakeWord(text)) speak("Sí? Te escucho")
                    wakeListening = false
                    if (shouldWakeListen()) lifecycleScope.launch { delay(400); startWakeWordListener() }
                }
                override fun onPartialResults(b: Bundle?) {
                    val p = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                    if (containsWakeWord(p)) android.util.Log.i("OpenCodeWake", "wake partial $p")
                }
                override fun onEvent(t: Int, b: Bundle?) {}
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try { wakeRecognizer?.startListening(intent) } catch (e:Exception) { wakeListening = false }
    }
    fun stopWakeWordListener() {
        try { wakeRecognizer?.destroy() } catch (_:Exception) {}
        wakeRecognizer = null
        wakeListening = false
    }

    private fun ensurePermissions(){
        if(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED){
            reqMic.launch(Manifest.permission.RECORD_AUDIO)
        }
        if(ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED){
            reqContacts.launch(Manifest.permission.READ_CONTACTS)
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

    override fun onInit(status: Int) {
        if(status==TextToSpeech.SUCCESS){
            tts?.language = Locale("es","ES")
        }
    }
    fun speak(text:String){ tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "utt1") }
    private fun startListening(){ /* Phase 1: native STT handled via Compose voice FAB later */ }
    private fun toast(m:String)= Toast.makeText(this,m,Toast.LENGTH_SHORT).show()
    override fun onDestroy() { tts?.shutdown(); recognizer?.destroy(); stopWakeWordListener(); super.onDestroy() }
    private fun String.lowercase():String = this.lowercase(Locale.ROOT)
}
