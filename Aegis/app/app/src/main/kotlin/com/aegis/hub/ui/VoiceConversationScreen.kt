package com.aegis.hub.ui

import android.Manifest
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.aegis.hub.data.VoicePreferences
import com.aegis.hub.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceConversationScreen(
    sessionId: String,
    vm: ChatViewModel,
    onBack: () -> Unit,
    onNewSession: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val messages by vm.messages.collectAsState()
    val models by vm.models.collectAsState()
    val selectedModel by vm.selectedModel.collectAsState()
    val selectedProvider by vm.selectedProvider.collectAsState()
    val modelsLoading by vm.modelsLoading.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(sessionId) { vm.load(sessionId) }

    // BUG-15 (wake word): esta pantalla ES modo conversación (duplex). Se informa a la
    // MainActivity al entrar para que shouldWakeListen() tenga un origen real,
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

    // Dialog & sheet states
    var showModelSheet by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var isCreatingSession by remember { mutableStateOf(false) }

    // Settings local state initialized from VoicePreferences
    var prefWakeEnabled by remember { mutableStateOf(VoicePreferences.isWakeWordEnabled(context)) }
    var prefSpeechRate by remember { mutableStateOf(VoicePreferences.getSpeechRate(context)) }
    var prefLanguage by remember { mutableStateOf(VoicePreferences.getLanguage(context)) }
    var prefWakePhrasesText by remember {
        mutableStateOf(VoicePreferences.getWakePhrases(context).joinToString(", "))
    }

    DisposableEffect(Unit) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val locale = Locale.forLanguageTag(VoicePreferences.getLanguage(context))
                engine?.language = if (locale.language.isNotEmpty()) locale else Locale("es", "ES")
                engine?.setSpeechRate(VoicePreferences.getSpeechRate(context))
            }
        }
        tts = engine
        onDispose { try { engine?.shutdown() } catch (_: Exception) {} }
    }

    fun applyTtsSettings(rate: Float, langTag: String) {
        tts?.setSpeechRate(rate)
        val locale = Locale.forLanguageTag(langTag)
        tts?.language = if (locale.language.isNotEmpty()) locale else Locale("es", "ES")
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
                if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                    sttError = "STT no disponible"
                    return@launch
                }
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
                val lang = VoicePreferences.getLanguage(context)
                val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
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
                    IconButton(
                        onClick = {
                            prefWakeEnabled = VoicePreferences.isWakeWordEnabled(context)
                            prefSpeechRate = VoicePreferences.getSpeechRate(context)
                            prefLanguage = VoicePreferences.getLanguage(context)
                            prefWakePhrasesText = VoicePreferences.getWakePhrases(context).joinToString(", ")
                            showSettingsDialog = true
                        },
                        modifier = Modifier.padding(16.dp).semantics { contentDescription = "Configuración" }
                    ) {
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

                // Bottom controls row: [Nuevo sesión] [Modelo Chip] [Cerrar]
                Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            if (!isCreatingSession) {
                                isCreatingSession = true
                                scope.launch {
                                    try {
                                        val newSid = vm.createVoiceSession(selectedProvider)
                                        if (newSid != null) {
                                            onNewSession?.invoke(newSid) ?: run {
                                                vm.load(newSid)
                                            }
                                        }
                                    } finally {
                                        isCreatingSession = false
                                    }
                                }
                            }
                        },
                        enabled = !isCreatingSession,
                        modifier = Modifier.semantics { contentDescription = "Nuevo chat" }
                    ) {
                        if (isCreatingSession) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Add, contentDescription = "Nuevo", modifier = Modifier.size(28.dp))
                        }
                    }

                    AssistChip(
                        onClick = { showModelSheet = true },
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

    // Model selection BottomSheet
    if (showModelSheet) {
        ModalBottomSheet(onDismissRequest = { showModelSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Proveedor y Modelo",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Elige el motor y modelo para la interacción por voz:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FilterChip(
                        selected = selectedProvider == "opencode",
                        onClick = { vm.selectProvider("opencode") },
                        label = { Text("OpenCode Zen") },
                        shape = RoundedCornerShape(10.dp)
                    )
                    FilterChip(
                        selected = selectedProvider == "antigravity",
                        onClick = { vm.selectProvider("antigravity") },
                        label = { Text("Antigravity") },
                        shape = RoundedCornerShape(10.dp)
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 350.dp)) {
                    items(models) { model ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    model.name,
                                    fontWeight = if (model.id == selectedModel) FontWeight.SemiBold else FontWeight.Normal
                                )
                            },
                            supportingContent = { model.description?.let { Text(it) } },
                            leadingContent = {
                                RadioButton(
                                    selected = model.id == selectedModel,
                                    onClick = {
                                        vm.selectModel(model.id)
                                        showModelSheet = false
                                    }
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.selectModel(model.id)
                                    showModelSheet = false
                                }
                        )
                    }
                    if (models.isEmpty()) {
                        item {
                            Text(
                                if (modelsLoading) "Cargando modelos…"
                                else "No se encontraron modelos disponibles.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // Voice Settings Dialog
    if (showSettingsDialog) {
        val scrollState = rememberScrollState()
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("Ajustes de Voz", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Speech rate slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Velocidad de voz (TTS)", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                String.format(Locale.ROOT, "%.2fx", prefSpeechRate),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Slider(
                            value = prefSpeechRate,
                            onValueChange = { prefSpeechRate = it },
                            valueRange = 0.5f..2.0f,
                            steps = 5,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    HorizontalDivider()

                    // Wake word toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Wake word activo", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Activar reconocimiento continuo por frase clave",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = prefWakeEnabled,
                            onCheckedChange = { prefWakeEnabled = it }
                        )
                    }

                    // Wake phrases input
                    OutlinedTextField(
                        value = prefWakePhrasesText,
                        onValueChange = { prefWakePhrasesText = it },
                        label = { Text("Frases de activación (separadas por comas)") },
                        placeholder = { Text("viernes escucha, hola viernes") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = prefWakeEnabled,
                        singleLine = false,
                        maxLines = 3
                    )

                    HorizontalDivider()

                    // Language selection
                    Column {
                        Text("Idioma (STT & TTS)", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = prefLanguage == "es-ES",
                                onClick = { prefLanguage = "es-ES" },
                                label = { Text("Español (es-ES)") }
                            )
                            FilterChip(
                                selected = prefLanguage == "en-US",
                                onClick = { prefLanguage = "en-US" },
                                label = { Text("English (en-US)") }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Persist preferences
                        VoicePreferences.setWakeWordEnabled(context, prefWakeEnabled)
                        VoicePreferences.setSpeechRate(context, prefSpeechRate)
                        VoicePreferences.setLanguage(context, prefLanguage)
                        val splitPhrases = prefWakePhrasesText
                            .split(",")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .toTypedArray()
                        val finalPhrases = if (splitPhrases.isNotEmpty()) splitPhrases else VoicePreferences.DEFAULT_WAKE_PHRASES
                        VoicePreferences.saveWakePhrases(context, finalPhrases)

                        // Update current TTS instance
                        applyTtsSettings(prefSpeechRate, prefLanguage)

                        showSettingsDialog = false
                    }
                ) {
                    Text("Guardar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

/**
 * Resuelve la MainActivity anfitriona atravesando los ContextWrapper,
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
