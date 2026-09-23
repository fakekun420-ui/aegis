package com.aegis.hub.ui

import android.Manifest
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.aegis.hub.data.AttachedFile
import com.aegis.hub.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun VoiceConversationScreen(
    sessionId: String,
    vm: ChatViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val messages by vm.messages.collectAsState()
    val models by vm.models.collectAsState()
    val selectedModel by vm.selectedModel.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(sessionId) { vm.load(sessionId) }

    // BUG-15 (wake word): esta pantalla ES modo conversación (duplex). Se informa a la
    // MainActivity al entrar para que shouldWakeListen() tenga un origen real (antes
    // nadie llamaba a onVoiceModeChanged() en código nativo y devolvía siempre false),
    // y al salir se notifica false para detener el listener de wake word.
    LaunchedEffect(Unit) { resolveMainActivity(context)?.onVoiceModeChanged(true) }
    DisposableEffect(Unit) { onDispose { resolveMainActivity(context)?.onVoiceModeChanged(false) } }

    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var ttsQueue by remember { mutableStateOf<List<String>>(emptyList()) }
    var speaking by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var sttError by remember { mutableStateOf<String?>(null) }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    var duplexJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var lastTranscript by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) engine?.language = Locale("es", "ES")
        }
        tts = engine
        onDispose { try { engine?.shutdown() } catch (_: Exception) {} }
    }

    fun queueTts(text: String) {
        if (text.isBlank()) return
        val chunks = text.split(Regex("(?<=[.!?¡¿\\n])\\s+")).filter { it.isNotBlank() }
        ttsQueue = ttsQueue + chunks
        if (!speaking) {
            val t = tts ?: return
            speaking = true
            val chunk = ttsQueue.firstOrNull() ?: return
            ttsQueue = ttsQueue.drop(1)
            try { t.speak(chunk, TextToSpeech.QUEUE_FLUSH, null, "utt") } catch (_: Exception) {}
            speaking = false
        }
    }

    fun scheduleDuplexRestart(delayMs: Long = 500) {
        if (speaking || listening) return
        duplexJob?.cancel()
        duplexJob = scope.launch {
            delay(delayMs)
            if (!listening && !speaking) {
                if (!SpeechRecognizer.isRecognitionAvailable(context)) { sttError = "STT no disponible"; return@launch }
                try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) {}
                val sr = SpeechRecognizer.createSpeechRecognizer(context)
                recognizer = sr
                sr.setRecognitionListener(object : android.speech.RecognitionListener {
                    override fun onReadyForSpeech(p: android.os.Bundle?) { listening = true; sttError = null }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(v: Float) {}
                    override fun onBufferReceived(b: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(e: Int) {
                        listening = false
                        sttError = if (e == SpeechRecognizer.ERROR_NO_MATCH) "no-speech" else "STT error $e"
                        scheduleDuplexRestart(if (e == SpeechRecognizer.ERROR_NO_MATCH) 1200 else 1500)
                    }
                    override fun onResults(b: android.os.Bundle?) {
                        listening = false
                        val text = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim() ?: ""
                        if (text.isNotBlank()) {
                            lastTranscript = text
                            vm.sendWithFiles(sessionId, text, emptyList())
                        }
                        scheduleDuplexRestart(700)
                    }
                    override fun onPartialResults(b: android.os.Bundle?) {}
                    override fun onEvent(t: Int, b: android.os.Bundle?) {}
                })
                val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
                try { sr.startListening(intent) } catch (e: Exception) { sttError = e.message }
            }
        }
    }

    LaunchedEffect(messages) {
        val last = messages.lastOrNull()
        if (last != null && last.role == "assistant") {
            val stripped = last.strippedText().ifBlank { last.text }
            if (stripped.isNotBlank()) {
                lastTranscript = stripped.take(200)
                queueTts(stripped)
            }
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scheduleDuplexRestart(100) else sttError = "Micrófono denegado"
    }

    Scaffold { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Spacer(Modifier.weight(1f))

                // Top-right settings gear
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
                    IconButton(onClick = { }, modifier = Modifier.padding(16.dp).semantics { contentDescription = "Configuración" }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Configuración", modifier = Modifier.size(28.dp))
                    }
                }

                Spacer(Modifier.weight(1f))

                // App icon
                Icon(
                    Icons.Filled.SmartToy,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    if (listening) "Escuchando…" else if (speaking) "Hablando…" else "Toca para hablar",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (lastTranscript.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        lastTranscript,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                }

                Spacer(Modifier.weight(1f))

                // Large mic button
                Button(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            if (listening) {
                                try { recognizer?.stopListening() } catch (_: Exception) {}
                            } else {
                                scheduleDuplexRestart(100)
                            }
                        }
                    },
                    modifier = Modifier.size(80.dp).semantics { contentDescription = if (listening) "Dejar de escuchar" else "Hablar" },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        if (listening) Icons.Filled.Close else Icons.Filled.SmartToy,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }

                Spacer(Modifier.weight(0.5f))

                // Bottom controls row
                Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { }, modifier = Modifier.semantics { contentDescription = "Nuevo chat" }) {
                        Icon(Icons.Filled.Add, contentDescription = "Nuevo", modifier = Modifier.size(28.dp))
                    }
                    AssistChip(
                        onClick = { },
                        label = {
                            val modelName = models.find { it.id == selectedModel }?.name ?: selectedModel ?: "Modelo"
                            Text(modelName, style = MaterialTheme.typography.labelSmall)
                        },
                        modifier = Modifier.semantics { contentDescription = "Modelo" }
                    )
                    IconButton(onClick = { onBack() }, modifier = Modifier.semantics { contentDescription = "Cerrar" }) {
                        Icon(Icons.Filled.Close, contentDescription = "Cerrar", modifier = Modifier.size(28.dp))
                    }
                }
            }

            sttError?.let {
                Snackbar(modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)) { Text(it) }
            }
        }
    }
}

/**
 * A-5 (BUG-15): resuelve la MainActivity anfitriona atravesando los ContextWrapper,
 * para notificarle el cambio de modo de voz (onVoiceModeChanged) desde Compose.
 */
private fun resolveMainActivity(context: android.content.Context): com.aegis.hub.MainActivity? {
    var ctx: android.content.Context? = context
    while (ctx is android.content.ContextWrapper) {
        if (ctx is com.aegis.hub.MainActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
