package com.opencode.companion

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * VoiceInteractionService — makes the companion selectable as default Digital Assistant.
 * Settings → Apps → Default Apps → Digital Assistant → Opencode Companion.
 * Long-press home / gesture triggers onReady() → shows session that can STT + forward to hub.
 * Spec (4): respond to long-press home / equivalent gesture.
 */
class CompanionVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        // Service is ready to handle assist intents
    }
}

class CompanionVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return CompanionVoiceSession(this)
    }
}

class CompanionVoiceSession(private val service: VoiceInteractionSessionService) : VoiceInteractionSession(service), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(service, this)
    }

    override fun onHandleAssist(state: Bundle?, data: Intent?, structuredData: Bundle?) {
        super.onHandleAssist(state, data, structuredData)
        // Launch MainActivity with voice flag and also start listening via assist
        val i = Intent(service, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("voice_assist", true)
        }
        service.startActivity(i)
        // Also speak via TTS and listen inline if possible
        startAssistListening()
    }

    override fun onHandleVoiceAssist(state: Bundle?, data: Intent?, structuredData: Bundle?) {
        super.onHandleVoiceAssist(state, data, structuredData)
        onHandleAssist(state, data, structuredData)
    }

    override fun onShow(args: Bundle?, flags: Int) {
        super.onShow(args, flags)
        startAssistListening()
    }

    private fun startAssistListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(service)) {
            speak("No disponible reconocimiento de voz")
            finish()
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(service).apply {
            setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(e: Int) {
                    speak("No te entendí")
                    finish()
                }
                override fun onResults(b: Bundle?) {
                    val text = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                    if (text.isBlank()) { speak("No te entendí"); finish(); return }
                    // Forward to hub via simple HTTP — log and TTS confirm
                    Thread {
                        try {
                            val url = java.net.URL("http://127.0.0.1:8765/api/assistant/execute")
                            val conn = url.openConnection() as java.net.HttpURLConnection
                            conn.requestMethod = "POST"
                            conn.doOutput = true
                            conn.setRequestProperty("Content-Type", "application/json")
                            conn.connectTimeout = 8000
                            conn.readTimeout = 8000
                            // Try direct voice command first — reuse assistant intent
                            val payload = org.json.JSONObject().put("text", text).toString()
                            // First try intent to see if it's a direct action
                            val intentUrl = java.net.URL("http://127.0.0.1:8765/api/assistant/intent")
                            val ic = intentUrl.openConnection() as java.net.HttpURLConnection
                            ic.requestMethod = "POST"; ic.doOutput = true
                            ic.setRequestProperty("Content-Type", "application/json")
                            ic.outputStream.write(payload.toByteArray())
                            val intentResp = ic.inputStream.bufferedReader().readText()
                            val intentJson = org.json.JSONObject(intentResp)
                            val action = intentJson.optString("action", "")
                            if (action.isNotEmpty() && action != "llm_classify") {
                                speak("Ejecutando $action")
                                // Also trigger execute
                                val execUrl = java.net.URL("http://127.0.0.1:8765/api/assistant/execute")
                                val ec = execUrl.openConnection() as java.net.HttpURLConnection
                                ec.requestMethod = "POST"; ec.doOutput = true
                                ec.setRequestProperty("Content-Type", "application/json")
                                val execPayload = org.json.JSONObject().put("action", action).put("slots", intentJson.optJSONObject("slots") ?: org.json.JSONObject()).toString()
                                ec.outputStream.write(execPayload.toByteArray())
                                ec.inputStream.bufferedReader().readText()
                                speak("Hecho: $action")
                            } else {
                                // Fallback: launch hub WebView; let user continue
                                service.startActivity(Intent(service, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                                speak("Abriendo hub")
                            }
                            // Log to voice log
                            try {
                                val logUrl = java.net.URL("http://127.0.0.1:8765/api/voice/log")
                                val lc = logUrl.openConnection() as java.net.HttpURLConnection
                                lc.requestMethod = "POST"; lc.doOutput = true
                                lc.setRequestProperty("Content-Type", "application/json")
                                lc.outputStream.write(org.json.JSONObject().put("recognizedText", text).put("matchedCommand", action.ifEmpty { "none" }).put("result", "via assist").toString().toByteArray())
                                lc.inputStream.close()
                            } catch (_:Exception) {}
                        } catch (e: Exception) {
                            speak("Error: ${e.message}")
                        }
                        finish()
                    }.start()
                }
                override fun onPartialResults(b: Bundle?) {}
                override fun onEvent(t: Int, b: Bundle?) {}
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        recognizer?.startListening(intent)
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "voice_assist")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts?.language = Locale("es", "ES")
    }

    override fun onDestroy() {
        tts?.shutdown()
        recognizer?.destroy()
        super.onDestroy()
    }
}

// Placeholder activity required for VoiceInteraction manifest — immediately delegates to MainActivity
class CompanionVoiceInteractionSessionActivity : androidx.appcompat.app.AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java).apply { putExtra("voice_assist", true) })
        finish()
    }
}
