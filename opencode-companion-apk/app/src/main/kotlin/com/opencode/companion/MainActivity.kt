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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    private val reqContacts = registerForActivityResult(ActivityResultContracts.RequestPermission()){ ok ->
        if(ok) toast("Contactos concedidos")
        else toast("Contactos denegados — WhatsApp por nombre no funcionará")
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
        webView.addJavascriptInterface(this, "NativeBridge")
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
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // FIX (2): only start wake listening after WebView chat loaded and Conversación enabled
                view?.postDelayed({
                    // Also sync duplex flag from WebView localStorage (app.js occ.voiceMode)
                    view.evaluateJavascript("(function(){try{return localStorage.getItem('occ.voiceMode')}catch(e){return null}})()") { v ->
                        val duplex = v?.contains("duplex") == true
                        if (duplex) duplexEnabledInSession = true
                        if (!isNativeOverlayVisible() && shouldWakeListen()) {
                            android.util.Log.i("OpenCodeWake", "onPageFinished — starting wake listener (Conversación mode, duplex=$duplex)")
                            startWakeWordListener()
                        } else {
                            android.util.Log.d("OpenCodeWake", "onPageFinished — wake not started (duplex=$duplex, overlay=${isNativeOverlayVisible()})")
                        }
                    }
                }, 800)
            }
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
        // FIX (2): ensure wake word stays off in Texto mode after overlay hides — only Conversación enables it
        // (onPageFinished will handle starting it if duplex was persisted)
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
    // Criterio termux-native: si /api/system/status devuelve ready:true (termux-native o companion-owned), ocultar overlay y cargar WebView directo
    private suspend fun checkSystemReady(): Pair<Boolean,String> = withContext(kotlinx.coroutines.Dispatchers.IO) {
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
    // Legacy 200-only check for polling after user pressed Iniciar Sistema — still HTTP 200 gate
    private suspend fun isHubReady(): Boolean = withContext(kotlinx.coroutines.Dispatchers.IO) {
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
        showNativeLoading("Iniciando servicios y levantando Hub...")
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var execExit = -1
            var execError: String? = null
            try {
                // No ejecutes keepalive.sh en foreground — desacopla con nohup & y exporta PATH de Termux para que nohup/sh se resuelvan
                val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "export PATH=/data/data/com.termux/files/usr/bin:\$PATH; nohup sh /sdcard/projects/opencode-companion/keepalive.sh > /sdcard/projects/opencode-companion/hub-startup.log 2>&1 &"))
                execExit = proc.waitFor()
                val errText = try { proc.errorStream.bufferedReader().readText().trim() } catch (_: Exception) { "" }
                if (errText.isNotEmpty()) execError = errText
                android.util.Log.i("OpenCodeBoot", "keepalive exec exit=$execExit err=${errText.take(300)}")
                if (execExit != 0) {
                    android.util.Log.e("OpenCodeBoot", "keepalive.sh exec failed exit=$execExit err=$errText")
                }
            } catch (e: Exception) {
                execError = e.message
                android.util.Log.e("OpenCodeBoot", "keepalive exec exception", e)
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    findViewById<TextView>(R.id.nativeStatus)?.text = "Error ejecutando keepalive.sh: ${e.message} (exit=$execExit)"
                    findViewById<android.widget.ProgressBar>(R.id.nativeProgress)?.visibility = android.view.View.GONE
                    findViewById<MaterialButton>(R.id.btnNativeStart)?.visibility = android.view.View.VISIBLE
                    findViewById<MaterialButton>(R.id.btnNativeStart)?.isEnabled = true
                    isStartingSystem = false
                }
                return@launch
            }
            if (execExit != 0 && execError != null) {
                android.util.Log.e("OpenCodeBoot", "keepalive exit=$execExit error=$execError")
            }
            // Polling cada 500ms hasta 45s (90 intentos) — no bloquees el hilo UI
            var attempts = 0
            var ready = false
            val maxAttempts = 90 // 45s / 0.5s
            var startupLogShown = false
            while (attempts < maxAttempts && !ready) {
                delay(500)
                ready = isHubReady()
                attempts++
                if (!ready) {
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        val secs = attempts * 0.5
                        findViewById<TextView>(R.id.nativeStatus)?.text = "Iniciando servicios y levantando Hub... (${String.format("%.1f", secs)}s)"
                    }
                    // Tras 5s (10 intentos) sin 200, muestra últimas 15 líneas de hub-startup.log para diagnóstico exacto
                    if (attempts == 10 && !startupLogShown) {
                        startupLogShown = true
                        try {
                            val logFile = java.io.File("/sdcard/projects/opencode-companion/hub-startup.log")
                            val tail = if (logFile.exists()) {
                                val lines = logFile.readLines()
                                lines.takeLast(15).joinToString("\n").trim().ifEmpty { "(hub-startup.log vacío)" }
                            } else "(hub-startup.log no existe aún)"
                            android.util.Log.e("OpenCodeBoot", "5s sin 200, hub-startup.log tail:\n$tail")
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                val prev = findViewById<TextView>(R.id.nativeStatus)?.text?.toString() ?: ""
                                findViewById<TextView>(R.id.nativeStatus)?.text = prev + "\n\n[hub-startup.log tail]\n$tail"
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("OpenCodeBoot", "no se pudo leer hub-startup.log", e)
                        }
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
                    webView.clearCache(true)
                    toast("Hub levantado — chat cargado")
                } else {
                    // Lee tail final para mostrar error exacto si falló
                    var tailHint = ""
                    try {
                        val logFile = java.io.File("/sdcard/projects/opencode-companion/hub-startup.log")
                        if (logFile.exists()) tailHint = "\n\n[hub-startup.log]\n" + logFile.readLines().takeLast(15).joinToString("\n")
                    } catch (_: Exception) {}
                    findViewById<TextView>(R.id.nativeStatus)?.text = "Timeout 45s: hub no respondió 200. Reintenta Iniciar Sistema.$tailHint"
                    findViewById<android.widget.ProgressBar>(R.id.nativeProgress)?.visibility = android.view.View.GONE
                    findViewById<MaterialButton>(R.id.btnNativeStart)?.visibility = android.view.View.VISIBLE
                    findViewById<MaterialButton>(R.id.btnNativeStart)?.isEnabled = true
                    android.util.Log.e("OpenCodeBoot", "timeout 45s sin 200, tail=$tailHint")
                }
            }
        }
    }
    private fun checkHubOnStart() {
        lifecycleScope.launch {
            // FIX (1): 3s max timeout — si /api/system/status tarda, no dejar overlay colgado
            val result = withTimeoutOrNull(3000L) { checkSystemReady() }
            val (ready, info) = result ?: Pair(false, "timeout:3s")
            if (ready) {
                android.util.Log.i("OpenCodeBoot", "checkHubOnStart ready=true ($info) — carga directa WebView sin overlay")
                hideNativeOverlay()
                // carga directa sin pintar overlay
                webView.post { webView.loadUrl("http://127.0.0.1:8765") }
            } else {
                android.util.Log.i("OpenCodeBoot", "checkHubOnStart ready=false ($info) — muestra Iniciar Sistema")
                if (result == null) android.util.Log.w("OpenCodeBoot", "checkHubOnStart timed out after 3s — treating as offline")
                showNativeOfflineOverlay()
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

    // ---- Wake word listener (hands-free) — SharedPreferences configurable array ----
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
    private fun containsWakeWord(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return getWakePhrases().any { ph -> lower.contains(ph.lowercase(Locale.ROOT)) }
    }
    private fun isNativeOverlayVisible(): Boolean {
        val v = findViewById<android.view.View>(R.id.nativeOverlay) ?: return false
        return v.visibility == android.view.View.VISIBLE
    }
    private fun shouldWakeListen(): Boolean {
        // FIX (2): wake word ONLY when WebView chat is loaded AND Conversación mode persisted
        // During native overlay or Texto mode, keep mic fully OFF to avoid loop sound
        if (isNativeOverlayVisible()) return false
        if (webView.url == null) return false
        // Web stores occ.voiceMode (push/duplex) in localStorage, mirrored to native via shouldWakeListen
        // We check both the WebView's localStorage mirror (occ_web_prefs) and the Activity's own flag
        // For now, require that duplex was persisted; Texto mode (null/push) = no wake listening
        // Correct key is occ.voiceMode as used in public/app.js: localStorage.getItem("occ.voiceMode")
        val mode = try {
            // Try reading WebView localStorage via native bridge is async, so we check native-cached copy
            // MainActivity caches the last known mode via shouldWakeListen — fallback to SharedPreferences
            // Primary source: app.js writes to localStorage "occ.voiceMode"; native mirrors via JS injection could be added,
            // but we approximate by checking if any duplex was ever persisted in native prefs
            getSharedPreferences("voice_prefs", MODE_PRIVATE).getString("wake_duplex", null)
        } catch (_:Exception) { null }
        // Actually app.js persists to localStorage "occ.voiceMode" which is not SharedPreferences.
        // So we expose a helper: window.localStorage can be read via evaluateJavascript async, not sync.
        // As a sync approximation, we check if the user ever toggled duplex in this session via a memory flag
        if (duplexEnabledInSession) return true
        // Otherwise remain silent — wake will be enabled when user toggles Conversación and we set the flag
        return false
    }
    // Tracks whether user explicitly enabled Conversación in this session (or hub loaded with duplex)
    // Flipped to true when WebView reports localStorage occ.voiceMode=="duplex" in onPageFinished
    private var duplexEnabledInSession: Boolean = false
    // Called from JS bridge when user toggles Conversación in WebView (app.js setVoiceMode)
    @android.webkit.JavascriptInterface
    fun onVoiceModeChanged(duplex: Boolean) {
        duplexEnabledInSession = duplex
        android.util.Log.i("OpenCodeWake", "onVoiceModeChanged duplex=$duplex")
        if (duplex && !isNativeOverlayVisible()) {
            webView.post { startWakeWordListener() }
        } else if (!duplex) {
            stopWakeWordListener()
        }
    }
    fun startWakeWordListener() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        // FIX (2): do NOT start while native overlay is visible or Texto mode is active
        if (isNativeOverlayVisible()) {
            android.util.Log.d("OpenCodeWake", "wake skip: overlay visible")
            return
        }
        if (!shouldWakeListen()) {
            android.util.Log.d("OpenCodeWake", "wake skip: shouldWakeListen=false webUrl=${webView.url} overlay=${isNativeOverlayVisible()}")
            return
        }
        if (wakeListening) return
        wakeListening = true
        wakeRecognizer?.destroy()
        android.util.Log.d("OpenCodeWake", "wake startListening")
        wakeRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) { android.util.Log.d("OpenCodeWake", "wake onReadyForSpeech") }
                override fun onBeginningOfSpeech() { android.util.Log.d("OpenCodeWake", "wake onBeginningOfSpeech") }
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() { android.util.Log.d("OpenCodeWake", "wake onEndOfSpeech") }
                override fun onError(e: Int) {
                    android.util.Log.d("OpenCodeWake", "wake onError=$e")
                    // Auto-restart only if still should listen
                    wakeListening = false
                    if (shouldWakeListen()) webView.postDelayed({ startWakeWordListener() }, 900)
                }
                override fun onResults(b: Bundle?) {
                    val list = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = list?.firstOrNull() ?: ""
                    android.util.Log.d("OpenCodeWake", "wake onResults: $text")
                    if (containsWakeWord(text)) {
                        android.util.Log.i("OpenCodeWake", "wake word detected: $text")
                        // Activate full voice session via WebView JS injection
                        val esc = text.replace("\\","\\\\").replace("'","\\'").replace("\n"," ")
                        webView.evaluateJavascript("try{ window.startVoiceSession && window.startVoiceSession(); 'ok' }catch(e){'err:'+e}", null)
                        speak("Sí? Te escucho")
                        txtSttResult.text = "Wake: $text → sesión voz"
                    }
                    wakeListening = false
                    if (shouldWakeListen()) webView.postDelayed({ startWakeWordListener() }, 400)
                }
                override fun onPartialResults(b: Bundle?) {
                    val p = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                    if (containsWakeWord(p)) {
                        android.util.Log.i("OpenCodeWake", "wake partial word: $p")
                        // Early trigger on partial to reduce latency
                        webView.evaluateJavascript("try{ window.startVoiceSession && window.startVoiceSession(); 'ok' }catch(e){'err'}", null)
                    }
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
        try { wakeRecognizer?.startListening(intent) } catch (e:Exception) { android.util.Log.w("OpenCodeWake", "wake startListening fail: ${e.message}"); wakeListening = false }
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
        // Wake word no longer auto-started here — gated in onPageFinished + shouldWakeListen (fix 2)
        // Previously this caused mic loop while overlay was visible even in Texto mode
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
    override fun onDestroy() { tts?.shutdown(); recognizer?.destroy(); stopWakeWordListener(); super.onDestroy() }
    private fun String.lowercase():String = this.lowercase(Locale.ROOT)
}
