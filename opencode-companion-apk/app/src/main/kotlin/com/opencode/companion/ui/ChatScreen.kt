package com.opencode.companion.ui

import android.Manifest
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.opencode.companion.data.Message
import com.opencode.companion.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String,
    vm: ChatViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val messages by vm.messages.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(sessionId) { vm.load(sessionId) }

    // Auto-scroll on new messages / initial load (double-frame + tts queue drain analog)
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            // Wait for layout pass
            delay(60)
            try { listState.animateScrollToItem(messages.size - 1) } catch (_: Exception) {}
        }
    }

    // Voice: TTS
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var ttsQueue by remember { mutableStateOf<List<String>>(emptyList()) }
    var speaking by remember { mutableStateOf(false) }

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
        if (!speaking) drainTts(tts, ttsQueue, { speaking = it }, { ttsQueue = it })
    }

    // Voice: STT push-to-talk + duplex toggle
    var duplex by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var sttError by remember { mutableStateOf<String?>(null) }
    var composerText by remember { mutableStateOf("") }
    var attachStub by remember { mutableStateOf(false) }
    var duplexJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) sttError = "Micrófono denegado"
    }

    fun ensureMicPermission(): Boolean {
        return if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO); false
        } else true
    }

    // Duplex debounced restart: mirrors app.js scheduleDuplexRestart
    fun scheduleDuplexRestart(delayMs: Long = 500) {
        if (!duplex) return
        if (speaking) return
        if (listening) return
        duplexJob?.cancel()
        duplexJob = scope.launch {
            delay(delayMs)
            if (duplex && !listening && !speaking) startListeningInternal(
                context, recognizer, { recognizer = it }, { listening = it }, { sttError = it },
                duplex, scope, ::scheduleDuplexRestart,
                onResult = { t ->
                    composerText = if (composerText.isBlank()) t else "$composerText $t"
                },
                onQueueTts = {}
            )
        }
    }

    // When assistant message arrives and duplex on, speak it (with debounce)
    LaunchedEffect(messages) {
        val last = messages.lastOrNull()
        if (duplex && last != null && last.role == "assistant") {
            val stripped = last.strippedText().ifBlank { last.text }
            if (stripped.isNotBlank()) queueTts(stripped)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(sessionId.take(8), maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null) } },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (duplex) "Conversación" else "Texto", style = MaterialTheme.typography.labelSmall)
                        Switch(checked = duplex, onCheckedChange = { v ->
                            duplex = v
                            if (v) scheduleDuplexRestart(600) else { duplexJob?.cancel(); try { recognizer?.cancel() } catch (_: Exception) {}; listening = false }
                        })
                    }
                }
            )
        },
        bottomBar = {
            ComposerBar(
                text = composerText,
                onTextChange = { composerText = it },
                onSend = {
                    val t = composerText.trim()
                    if (t.isNotBlank()) { vm.send(sessionId, t); composerText = "" }
                },
                onAttach = { attachStub = true },
                onMic = {
                    if (!ensureMicPermission()) return@ComposerBar
                    if (listening) {
                        try { recognizer?.stopListening() } catch (_: Exception) {}
                    } else {
                        startListeningInternal(
                            context, recognizer, { recognizer = it }, { listening = it }, { sttError = it },
                            duplex, scope, ::scheduleDuplexRestart,
                            onResult = { t -> composerText = if (composerText.isBlank()) t else "$composerText $t" },
                            onQueueTts = {}
                        )
                    }
                },
                listening = listening,
                duplex = duplex
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading && messages.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                error != null && messages.isEmpty() -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Error: $error", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { vm.load(sessionId) }) { Text("Reintentar") }
                }
                messages.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Sesión vacía — escribe abajo.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(messages, key = { it.info?.id ?: it.hashCode().toString() }) { msg ->
                        MessageBubble(msg)
                    }
                    if (loading) { item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) } } }
                }
            }
            if (sttError != null) {
                Snackbar(modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)) { Text(sttError ?: "") }
            }
        }
    }

    if (attachStub) {
        AlertDialog(onDismissRequest = { attachStub = false }, title = { Text("Adjuntar") }, text = { Text("Adjuntos de archivos — próxima fase (stub).") }, confirmButton = { TextButton(onClick = { attachStub = false }) { Text("OK") } })
    }
}

@Composable
private fun MessageBubble(msg: Message) {
    val isUser = msg.role == "user"
    val raw = msg.text
    val isMem = msg.isMemoryContext()
    val stripped = if (isMem) msg.strippedText() else raw

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (isMem && stripped.isNotBlank() && stripped != raw) {
            // Collapsible contexto interno row
            var expanded by remember { mutableStateOf(false) }
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(0.9f)
            ) {
                Column(Modifier.padding(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("contexto interno", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Ocultar" else "Ver") }
                    }
                    if (expanded) {
                        Text(raw.take(4000), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        } else if (isMem && stripped.isBlank()) {
            var expanded by remember { mutableStateOf(false) }
            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth(0.9f)) {
                Column(Modifier.padding(8.dp)) {
                    Row { Text("contexto interno (oculto)", style = MaterialTheme.typography.labelSmall); Spacer(Modifier.weight(1f)); TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Ocultar" else "Ver") } }
                    if (expanded) Text(raw.take(4000), style = MaterialTheme.typography.bodySmall)
                }
            }
            return@Column
        }

        val bubbleColor = when {
            isUser -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceVariant
        }
        val contentColor = when {
            isUser -> MaterialTheme.colorScheme.onPrimary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        val shape = RoundedCornerShape(
            topStart = 16.dp, topEnd = 16.dp,
            bottomStart = if (isUser) 16.dp else 4.dp,
            bottomEnd = if (isUser) 4.dp else 16.dp
        )
        Surface(color = bubbleColor, shape = shape, modifier = Modifier.fillMaxWidth(0.86f)) {
            Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                if (isUser) {
                    Text(stripped.ifBlank { raw }, color = contentColor, style = MaterialTheme.typography.bodyMedium)
                } else {
                    MarkdownText(stripped.ifBlank { raw })
                }
            }
        }
    }
}

@Composable
private fun ComposerBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onMic: () -> Unit,
    listening: Boolean,
    duplex: Boolean
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = onAttach) { Icon(Icons.Filled.AttachFile, contentDescription = "Adjuntar") }
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Escribe un mensaje…") },
                maxLines = 5
            )
            IconButton(onClick = onSend, enabled = text.isNotBlank()) { Icon(Icons.Filled.Send, contentDescription = "Enviar") }
            IconButton(onClick = onMic) {
                Icon(
                    if (listening) Icons.Filled.Mic else Icons.Filled.MicNone,
                    contentDescription = "Hablar",
                    tint = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

// ---- helpers ----

private fun drainTts(tts: TextToSpeech?, queue: List<String>, setSpeaking: (Boolean) -> Unit, setQueue: (List<String>) -> Unit) {
    if (queue.isEmpty()) { setSpeaking(false); return }
    val t = tts ?: return
    setSpeaking(true)
    val chunk = queue.first()
    setQueue(queue.drop(1))
    try {
        t.speak(chunk, TextToSpeech.QUEUE_FLUSH, null, "utt")
        // Naive: mark done after estimate; real onUtteranceProgressListener is more precise but stub here
        // For Phase 2 we don't block; next chunk will be queued when duplex restart fires
        setSpeaking(false)
    } catch (_: Exception) { setSpeaking(false) }
}

private fun startListeningInternal(
    context: android.content.Context,
    current: SpeechRecognizer?,
    setRecognizer: (SpeechRecognizer?) -> Unit,
    setListening: (Boolean) -> Unit,
    setError: (String?) -> Unit,
    duplex: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
    scheduleRestart: (Long) -> Unit,
    onResult: (String) -> Unit,
    onQueueTts: (String) -> Unit
) {
    if (!SpeechRecognizer.isRecognitionAvailable(context)) { setError("STT no disponible"); return }
    try { current?.cancel() } catch (_: Exception) {}
    try { current?.destroy() } catch (_: Exception) {}
    val sr = SpeechRecognizer.createSpeechRecognizer(context)
    setRecognizer(sr)
    var finalsBuf = ""
    sr.setRecognitionListener(object : android.speech.RecognitionListener {
        override fun onReadyForSpeech(p: android.os.Bundle?) { setListening(true); setError(null) }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(e: Int) {
            setListening(false)
            val msg = when (e) { SpeechRecognizer.ERROR_NO_MATCH -> "no-speech"; else -> "STT error $e" }
            setError(msg)
            if (duplex) scheduleRestart(if (e == SpeechRecognizer.ERROR_NO_MATCH) 1200 else 1500)
        }
        override fun onResults(b: android.os.Bundle?) {
            setListening(false)
            val list = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = list?.firstOrNull()?.trim() ?: finalsBuf.trim()
            finalsBuf = ""
            if (text.isNotBlank()) onResult(text)
            if (duplex) scheduleRestart(700)
        }
        override fun onPartialResults(b: android.os.Bundle?) {
            val p = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
            if (p.isNotBlank()) { /* interim: could interrupt TTS here */ }
        }
        override fun onEvent(t: Int, b: android.os.Bundle?) {}
    })
    val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }
    try { sr.startListening(intent); setListening(true) } catch (e: Exception) { setError(e.message); setListening(false) }
}
