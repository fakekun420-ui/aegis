package com.aegis.hub

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import com.aegis.hub.data.VoicePreferences
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
    private val ttsLock = Any()
    private val ttsBuffer = StringBuilder()
    private var utteranceCount = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var retryCount = 0
    private var isProcessingResult = false

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
        retryCount = 0
        startAssistListening()
    }
    private fun startAssistListening() {
        stopSpeech()
        if (!SpeechRecognizer.isRecognitionAvailable(service)) {
            speakText("No disponible reconocimiento de voz")
            finish()
            return
        }
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {}

        isProcessingResult = false
        recognizer = SpeechRecognizer.createSpeechRecognizer(service).apply {
            setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(e: Int) {
                    if (isProcessingResult) return
                    // SpeechRecognizer error resilience: if idle timeout or no match, retry up to 2 times before giving up
                    if ((e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) && retryCount < 2) {
                        retryCount++
                        mainHandler.postDelayed({
                            startAssistListening()
                        }, 500)
                    } else {
                        speakText("No te entendí")
                        finish()
                    }
                }
                override fun onResults(b: Bundle?) {
                    val results = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
                    val text = results.firstOrNull()?.trim() ?: ""
                    if (text.isBlank()) {
                        if (retryCount < 2) {
                            retryCount++
                            mainHandler.postDelayed({ startAssistListening() }, 500)
                            return
                        }
                        speakText("No te entendí")
                        finish()
                        return
                    }

                    // Check wake word gating if wake phrase is configured/required
                    val wakePhrases = VoicePreferences.getWakePhrases(service)
                    val wakeEnabled = VoicePreferences.isWakeWordEnabled(service)
                    val matchedWake = !wakeEnabled || wakePhrases.any { ph -> text.lowercase(Locale.ROOT).contains(ph.lowercase(Locale.ROOT)) }
                    // If wake word is present, strip it from command text if desired or process directly
                    var commandText = text
                    if (wakeEnabled && matchedWake) {
                        for (ph in wakePhrases) {
                            val idx = commandText.indexOf(ph, ignoreCase = true)
                            if (idx >= 0) {
                                commandText = (commandText.substring(0, idx) + " " + commandText.substring(idx + ph.length)).trim()
                            }
                        }
                    }

                    isProcessingResult = true
                    Thread {
                        try {
                            val reqText = if (commandText.isNotBlank()) commandText else text
                            val payload = org.json.JSONObject().put("text", reqText).toString()
                            // A-3: /api/* exige X-Aegis-Token (A-1) — TokenProvider reintenta 1 vez.
                            val tokenProvider = com.aegis.hub.data.TokenProvider
                            val intentResp = tokenProvider.request(
                                "http://127.0.0.1:8765/api/assistant/intent", "POST",
                                payload.toByteArray(), connectTimeoutMs = 3000, readTimeoutMs = 60_000
                            )
                            if (intentResp.code !in 200..299) throw java.io.IOException("intent http:${intentResp.code}")
                            val intentJson = org.json.JSONObject(intentResp.body)
                            // Envelope estándar: la acción viaja en data (fallback al shape plano legado)
                            val intentData = intentJson.optJSONObject("data") ?: intentJson
                            val action = intentData.optString("action", "")
                            if (action.isNotEmpty() && action != "llm_classify") {
                                appendChunkAndSynthesize("Ejecutando $action. ")
                                val execPayload = org.json.JSONObject().put("action", action).put("slots", intentData.optJSONObject("slots") ?: org.json.JSONObject()).toString()
                                tokenProvider.request(
                                    "http://127.0.0.1:8765/api/assistant/execute", "POST",
                                    execPayload.toByteArray(), connectTimeoutMs = 3000, readTimeoutMs = 60_000
                                )
                                appendChunkAndSynthesize("Hecho: $action. ")
                                finishStreamingTts()
                            } else {
                                service.startActivity(Intent(service, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                                appendChunkAndSynthesize("Abriendo hub. ")
                                finishStreamingTts()
                            }
                            try {
                                val logPayload = org.json.JSONObject().put("recognizedText", text).put("matchedCommand", action.ifEmpty { "none" }).put("result", "via assist").toString()
                                tokenProvider.request(
                                    "http://127.0.0.1:8765/api/voice/log", "POST",
                                    logPayload.toByteArray(), connectTimeoutMs = 3000, readTimeoutMs = 15_000
                                )
                            } catch (_: Exception) {}
                        } catch (e: Exception) {
                            appendChunkAndSynthesize("Error: ${e.message}")
                            finishStreamingTts()
                        }
                        finish()
                    }.start()
                }
                override fun onPartialResults(b: Bundle?) {}
                override fun onEvent(t: Int, b: Bundle?) {}
            })
        }
        val lang = VoicePreferences.getLanguage(service)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try {
            recognizer?.startListening(intent)
        } catch (_: Exception) {
            speakText("No te entendí")
            finish()
        }
    }

    /** Interrumpe cualquier locución previa y limpia el buffer ante nueva interacción/cancelación. */
    fun stopSpeech() {
        synchronized(ttsLock) {
            ttsBuffer.setLength(0)
            try {
                tts?.stop()
            } catch (_: Exception) {}
        }
    }

    /** Síntesis continua para chunks de texto/SSE: acumula en buffer, detecta fronteras de oración y sintetiza con QUEUE_ADD. */
    fun appendChunkAndSynthesize(chunk: String) {
        if (chunk.isEmpty()) return
        val sentencesToSpeak = mutableListOf<String>()
        synchronized(ttsLock) {
            ttsBuffer.append(chunk)
            // Escanea fronteras de oración: . ! ? seguidos de espacio/puntuación o \n\n (salto de párrafo)
            val regex = Regex("(?<=[.!?\\n])(\\s+|(?<=\\n\\n))")
            while (true) {
                val currentText = ttsBuffer.toString()
                val match = regex.find(currentText) ?: break
                val sentence = currentText.substring(0, match.range.first).trim()
                val remainder = currentText.substring(match.range.last + 1)
                ttsBuffer.setLength(0)
                ttsBuffer.append(remainder)
                if (sentence.isNotEmpty()) {
                    sentencesToSpeak.add(sentence)
                }
            }
        }
        for (sentence in sentencesToSpeak) {
            speakSentence(sentence)
        }
    }

    /** Se llama cuando finaliza el stream (SSE done) para vaciar cualquier resto del buffer con QUEUE_ADD. */
    fun finishStreamingTts() {
        val remaining: String
        synchronized(ttsLock) {
            remaining = ttsBuffer.toString().trim()
            ttsBuffer.setLength(0)
        }
        if (remaining.isNotEmpty()) {
            speakSentence(remaining)
        }
    }

    /** Compatibilidad hacia atrás: interrumpe si es necesario o encola mensaje directo mediante streaming. */
    fun speak(text: String) {
        speakText(text)
    }

    private fun speakText(text: String) {
        appendChunkAndSynthesize(text)
        finishStreamingTts()
    }

    private fun speakSentence(sentence: String) {
        val uttId = "voice_assist_${System.currentTimeMillis()}_${++utteranceCount}"
        tts?.speak(sentence, TextToSpeech.QUEUE_ADD, null, uttId)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val langTag = VoicePreferences.getLanguage(service)
            val locale = Locale.forLanguageTag(langTag)
            tts?.language = if (locale.language.isNotEmpty()) locale else Locale("es", "ES")
            val rate = VoicePreferences.getSpeechRate(service)
            tts?.setSpeechRate(rate)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        stopSpeech()
        tts?.shutdown()
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }
}
class CompanionVoiceInteractionSessionActivity : androidx.appcompat.app.AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java).apply { putExtra("voice_assist", true) })
        finish()
    }
}
