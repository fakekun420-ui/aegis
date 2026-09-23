package com.aegis.hub

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

/** VoiceInteractionService — selectable as default Digital Assistant (long-press home). */
class CompanionVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() { super.onReady() }
}
class CompanionVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = CompanionVoiceSession(this)
}
class CompanionVoiceSession(private val service: VoiceInteractionSessionService) : VoiceInteractionSession(service), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(service, this)
    }
    // Correct signature: AssistStructure / AssistContent per framework API 21+
    override fun onHandleAssist(state: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        super.onHandleAssist(state, structure, content)
        val i = Intent(service, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("voice_assist", true)
        }
        service.startActivity(i)
        startAssistListening()
    }
    // No onHandleVoiceAssist in VoiceInteractionSession — remove incorrect override
    override fun onShow(args: Bundle?, flags: Int) {
        super.onShow(args, flags)
        startAssistListening()
    }
    private fun startAssistListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(service)) { speak("No disponible reconocimiento de voz"); finish(); return }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(service).apply {
            setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(e: Int) { speak("No te entendí"); finish() }
                override fun onResults(b: Bundle?) {
                    val text = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                    if (text.isBlank()) { speak("No te entendí"); finish(); return }
                    Thread {
                        try {
                            val payload = org.json.JSONObject().put("text", text).toString()
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
                                val execUrl = java.net.URL("http://127.0.0.1:8765/api/assistant/execute")
                                val ec = execUrl.openConnection() as java.net.HttpURLConnection
                                ec.requestMethod = "POST"; ec.doOutput = true
                                ec.setRequestProperty("Content-Type", "application/json")
                                val execPayload = org.json.JSONObject().put("action", action).put("slots", intentJson.optJSONObject("slots") ?: org.json.JSONObject()).toString()
                                ec.outputStream.write(execPayload.toByteArray())
                                ec.inputStream.bufferedReader().readText()
                                speak("Hecho: $action")
                            } else {
                                service.startActivity(Intent(service, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                                speak("Abriendo hub")
                            }
                            try {
                                val logUrl = java.net.URL("http://127.0.0.1:8765/api/voice/log")
                                val lc = logUrl.openConnection() as java.net.HttpURLConnection
                                lc.requestMethod = "POST"; lc.doOutput = true
                                lc.setRequestProperty("Content-Type", "application/json")
                                lc.outputStream.write(org.json.JSONObject().put("recognizedText", text).put("matchedCommand", action.ifEmpty { "none" }).put("result", "via assist").toString().toByteArray())
                                lc.inputStream.close()
                            } catch (_:Exception) {}
                        } catch (e: Exception) { speak("Error: ${e.message}") }
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
    private fun speak(text: String) { tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "voice_assist") }
    override fun onInit(status: Int) { if (status == TextToSpeech.SUCCESS) tts?.language = Locale("es", "ES") }
    override fun onDestroy() { tts?.shutdown(); recognizer?.destroy(); super.onDestroy() }
}
class CompanionVoiceInteractionSessionActivity : androidx.appcompat.app.AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java).apply { putExtra("voice_assist", true) })
        finish()
    }
}
