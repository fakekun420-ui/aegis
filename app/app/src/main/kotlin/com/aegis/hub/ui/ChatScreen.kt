package com.aegis.hub.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.aegis.hub.data.AttachedFile
import com.aegis.hub.data.LiveToolExecution
import com.aegis.hub.data.Message
import com.aegis.hub.data.MessageDeliveryStatus
import com.aegis.hub.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String,
    vm: ChatViewModel,
    onBack: () -> Unit,
    onVoice: () -> Unit = {},
    showTopBar: Boolean = sessionId.isNotBlank(),
    sessionProvider: String? = null
) {
    val context = LocalContext.current
    val messages by vm.messages.collectAsState()
    val sessionTitle by vm.sessionTitle.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val models by vm.models.collectAsState()
    val selectedModel by vm.selectedModel.collectAsState()
    val selectedProvider by vm.selectedProvider.collectAsState()
    val sessionProviderBound by vm.sessionProviderBound.collectAsState()
    val modelsLoading by vm.modelsLoading.collectAsState()
    val streamingText by vm.streamingText.collectAsState()
    val streamingTools by vm.streamingTools.collectAsState()
    val agentMode by vm.agentMode.collectAsState()
    var showModelSheet by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(sessionId, sessionProvider) {
        vm.load(sessionId, sessionProvider)
    }

    // Auto-scroll on new messages / loading / streaming tools or text changes.
    // Se observan también el id y la LONGITUD del último mensaje: antes la clave era
    // solo `messages.size`, de modo que cuando el asistente seguía escribiendo sobre el
    // MISMO mensaje (poll o streaming) el tamaño no cambiaba, el efecto no se relanzaba
    // y la lista se quedaba anclada a un mensaje anterior en vez de seguir la respuesta
    // más reciente.
    val lastMsgId = messages.lastOrNull()?.info?.id
    val lastMsgTextLen = messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(messages.size, loading, streamingText, streamingTools, lastMsgId, lastMsgTextLen) {
        val hasLive = streamingText != null || streamingTools.isNotEmpty() || (loading && messages.isNotEmpty())
        val totalCount = messages.size + (if (hasLive) 1 else 0)
        if (totalCount > 0) {
            delay(50)
            try { listState.animateScrollToItem(totalCount - 1) } catch (_: Exception) {}
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
    // UX-04/A-5: rememberSaveable — el modo de conversación y el borrador del mensaje
    // sobreviven a rotación/muerte del proceso (con remember puro se perdían al girar).
    var duplex by rememberSaveable { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var sttError by remember { mutableStateOf<String?>(null) }
    var composerText by rememberSaveable { mutableStateOf("") }
    var attachedFiles by remember { mutableStateOf<List<AttachedFile>>(emptyList()) }
    var duplexJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

    // BUG-15 (wake word): shouldWakeListen() de MainActivity lee duplexEnabledInSession, que
    // SOLO se actualiza desde onVoiceModeChanged(). En el código nativo nadie la llamaba
    // (solo la referenciaba el WebView histórico) y devolvía siempre false. Aquí se notifica
    // el modo de voz real: al alternar Conversación/Texto y al salir de la pantalla (false
    // detiene el listener de wake word).
    LaunchedEffect(duplex) { resolveMainActivity(context)?.onVoiceModeChanged(duplex) }
    DisposableEffect(Unit) { onDispose { resolveMainActivity(context)?.onVoiceModeChanged(false) } }

    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        val newFiles = uris.take(6 - attachedFiles.size).mapNotNull { uri -> uriToAttachedFile(context, uri) }
        attachedFiles = attachedFiles + newFiles
    }

    var showAttachSheet by remember { mutableStateOf(false) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && cameraUri != null) {
            val f = uriToAttachedFile(context, cameraUri!!)
            if (f != null) attachedFiles = attachedFiles + f
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val f = uriToAttachedFile(context, uri)
            if (f != null) attachedFiles = attachedFiles + f
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", java.io.File(context.cacheDir, "photo_${System.currentTimeMillis()}.jpg"))
            cameraUri = uri
            cameraLauncher.launch(uri)
        }
    }

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
            if (showTopBar) {
                val firstUserMsg = messages.firstOrNull { it.role == "user" && it.text.isNotBlank() }?.text?.trim()
                val displayTitle = when {
                    !sessionTitle.isNullOrBlank() && !isTechnicalSessionId(sessionTitle) -> sessionTitle!!
                    !firstUserMsg.isNullOrBlank() -> {
                        val clean = firstUserMsg.replace("\n", " ").trim()
                        if (clean.length > 30) clean.take(30).trim() + "…" else clean
                    }
                    else -> "Nuevo chat"
                }
                val effectiveProvider = sessionProvider ?: selectedProvider

                TopAppBar(
                    title = {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = displayTitle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (!effectiveProvider.isNullOrBlank()) {
                                    ProviderBadge(effectiveProvider)
                                }
                            }
                            if (sessionId.isNotBlank()) {
                                Text(
                                    text = sessionId.take(16),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    maxLines = 1
                                )
                            }
                        }
                    },
                    navigationIcon = { IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Volver" }) { Icon(Icons.Filled.ArrowBack, contentDescription = "Volver") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    actions = {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { contentDescription = if (duplex) "Conversación" else "Texto" }) {
                            Text(if (duplex) "Conversación" else "Texto", style = MaterialTheme.typography.labelSmall)
                            Switch(checked = duplex, onCheckedChange = { v ->
                                duplex = v
                                if (v) scheduleDuplexRestart(600) else { duplexJob?.cancel(); try { recognizer?.cancel() } catch (_: Exception) {}; listening = false }
                            }, modifier = Modifier.semantics { contentDescription = if (duplex) "Modo Conversación activado" else "Modo Texto activado" })
                        }
                    }
                )
            }
        },
        bottomBar = {
            Column(modifier = Modifier.navigationBarsPadding().imePadding()) {
                val modelDisplayName = models.find { it.id == selectedModel }?.name
                    ?: if (!selectedModel.isNullOrBlank()) selectedModel!! else "Gemini 3.8 Flash (High)"

                UnifiedFloatingComposer(
                    text = composerText,
                    onTextChange = { composerText = it },
                    onSend = {
                        val t = composerText.trim()
                        if (t.isNotBlank() || attachedFiles.isNotEmpty()) {
                            vm.sendWithFiles(sessionId, t, attachedFiles, sessionProvider)
                            composerText = ""
                            attachedFiles = emptyList()
                        }
                    },
                    onAttach = { showAttachSheet = true },
                    onMic = {
                        if (!ensureMicPermission()) return@UnifiedFloatingComposer
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
                    attachedFiles = attachedFiles,
                    onRemoveFile = { idx -> attachedFiles = attachedFiles.filterIndexed { i, _ -> i != idx } },
                    selectedModelName = modelDisplayName,
                    onSelectModelClick = { showModelSheet = true },
                    onVoice = onVoice,
                    agentMode = agentMode,
                    onToggleAgentMode = { vm.toggleAgentMode() }
                )
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                if (error != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = error ?: "Error de comunicación",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { vm.clearError() },
                                // A-5: target táctil mínimo 48dp (antes 24dp, imposible de tocar)
                                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Cerrar",
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
                Box(Modifier.fillMaxSize().weight(1f)) {
                    when {
                        loading && messages.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        messages.isEmpty() -> Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    Icons.Filled.SmartToy,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "Hablemos",
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    "Escribe un mensaje para comenzar",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(8.dp))
                                val suggestionPrompts = listOf(
                                    "¿Qué puedes hacer?",
                                    "Explícame la arquitectura del proyecto",
                                    "Comprueba el estado del sistema"
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.horizontalScroll(rememberScrollState())
                                ) {
                                    suggestionPrompts.forEach { prompt ->
                                        SuggestionChip(
                                            onClick = {
                                                composerText = prompt
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                            label = { Text(prompt, style = MaterialTheme.typography.bodySmall) }
                                        )
                                    }
                                }
                            }
                        }
                        else -> LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // A-5: key basada en id del mensaje, nunca en hashCode() — dos
                            // data classes con igual contenido colisionaban y crasheaban el
                            // LazyColumn con "Key was already used". Fallback por índice
                            // solo para mensajes aún sin id del servidor.
                            itemsIndexed(messages, key = { index, msg -> msg.info?.id ?: "msg_$index" }) { _, msg ->
                                TerminalConsoleTurn(msg, onRetry = {
                                    vm.retryMessage(msg, sessionId)
                                })
                            }
                            if (streamingText != null || streamingTools.isNotEmpty()) {
                                item(key = "streaming_live") {
                                    TerminalStreamingTurn(streamingText ?: "", streamingTools)
                                }
                            } else if (loading) {
                                item(key = "typing_dots") {
                                    TerminalActivityCursor()
                                }
                            }
                        }
                    }
                }
            }
            if (sttError != null) {
                Snackbar(modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)) { Text(sttError ?: "") }
            }
        }
    }

    if (showAttachSheet) {
        ModalBottomSheet(onDismissRequest = { showAttachSheet = false }) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Agregar al chat", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilledTonalIconButton(
                            onClick = {
                                showAttachSheet = false
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", java.io.File(context.cacheDir, "photo_${System.currentTimeMillis()}.jpg"))
                                    cameraUri = uri
                                    cameraLauncher.launch(uri)
                                } else {
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            },
                            modifier = Modifier.size(56.dp).semantics { contentDescription = "Cámara" }
                        ) { Icon(Icons.Filled.CameraAlt, contentDescription = "Cámara", modifier = Modifier.size(28.dp)) }
                        Text("Cámara", style = MaterialTheme.typography.labelSmall)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilledTonalIconButton(
                            onClick = { showAttachSheet = false; photoPickerLauncher.launch("image/*") },
                            modifier = Modifier.size(56.dp).semantics { contentDescription = "Fotos" }
                        ) { Icon(Icons.Filled.Photo, contentDescription = "Fotos", modifier = Modifier.size(28.dp)) }
                        Text("Fotos", style = MaterialTheme.typography.labelSmall)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilledTonalIconButton(
                            onClick = { showAttachSheet = false; filePickerLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier.size(56.dp).semantics { contentDescription = "Archivos" }
                        ) { Icon(Icons.Filled.FolderOpen, contentDescription = "Archivos", modifier = Modifier.size(28.dp)) }
                        Text("Archivos", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showModelSheet) {
        ModalBottomSheet(onDismissRequest = { showModelSheet = false }) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Proveedor y Modelo", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Elige el motor y modelo para esta sesión:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    FilterChip(
                        selected = selectedProvider == "opencode",
                        enabled = !sessionProviderBound,
                        onClick = { vm.selectProvider("opencode") },
                        label = { Text("OpenCode Zen") },
                        shape = RoundedCornerShape(10.dp)
                    )
                    FilterChip(
                        selected = selectedProvider == "antigravity",
                        enabled = !sessionProviderBound,
                        onClick = { vm.selectProvider("antigravity") },
                        label = { Text("Antigravity") },
                        shape = RoundedCornerShape(10.dp)
                    )
                }
                if (sessionProviderBound) {
                    Text(
                        "Sesión ya vinculada: el proveedor de nacimiento se mantiene. " +
                            "Para usar otro motor, crea un chat nuevo.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                models.forEach { model ->
                    ListItem(
                        headlineContent = { Text(model.name, fontWeight = if (model.id == selectedModel) FontWeight.SemiBold else FontWeight.Normal) },
                        supportingContent = { model.description?.let { Text(it) } },
                        leadingContent = {
                            RadioButton(
                                selected = model.id == selectedModel,
                                onClick = { vm.selectModel(model.id); showModelSheet = false }
                            )
                        },
                        modifier = Modifier.fillMaxWidth().clickable { vm.selectModel(model.id); showModelSheet = false }
                    )
                }
                if (models.isEmpty()) {
                    Text(
                        if (modelsLoading) "Cargando modelos…"
                        else "No se pudieron cargar los modelos. Revisa que OpenCode esté activo.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

}

@Composable
private fun decodeBase64Bitmap(b64: String): android.graphics.Bitmap? {
    return try {
        val clean = b64.substringAfter(",", b64)
        val bytes = android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (_: Exception) { null }
}

@Composable
private fun FileRow(name: String, mime: String, tint: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            if (mime.startsWith("image/")) Icons.Filled.Photo else Icons.Filled.FolderOpen,
            contentDescription = null, tint = tint, modifier = Modifier.size(20.dp)
        )
        Column {
            Text(name, style = MaterialTheme.typography.bodySmall, color = tint, maxLines = 1)
            if (mime.isNotBlank()) Text(mime, style = MaterialTheme.typography.labelSmall, color = tint.copy(alpha = 0.7f), maxLines = 1)
        }
    }
}

@Composable
private fun TerminalConsoleTurn(msg: Message, onRetry: (() -> Unit)? = null) {
    val isUser = msg.role == "user"
    val raw = msg.text
    val stripped = msg.strippedText()
    val isMem = msg.isMemoryContext()
    val displayContent = stripped.ifBlank { raw }.trim()
    val deliveryStatus = msg.info?.deliveryStatus ?: if (isUser) MessageDeliveryStatus.SENT else null

    val files = msg.fileParts()
    val images = msg.imageParts()
    val imageFiles = files.filter { it.mime?.startsWith("image/") == true && it.url != null }
    val nonImageFiles = files.filter { !(it.mime?.startsWith("image/") == true && it.url != null) }

    if (isMem && displayContent.isBlank()) {
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (isUser) {
            // User Prompt in terminal wizard format: Prompt prefix ❯, clear light text color
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = "❯ ",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        ),
                        modifier = Modifier.padding(top = 1.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        if (displayContent.isNotBlank()) {
                            Text(
                                text = displayContent,
                                color = Color(0xFFF2F2ED), // Distinct clear light prompt text
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    lineHeight = 22.sp
                                )
                            )
                        } else if (images.isEmpty() && imageFiles.isEmpty() && nonImageFiles.isEmpty()) {
                            Text(
                                "(mensaje vacío)",
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        // Attached images/files
                        if (images.isNotEmpty() || imageFiles.isNotEmpty() || nonImageFiles.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            images.forEach { img ->
                                val bitmap = (img.image ?: img.data ?: img.url)?.let { decodeBase64Bitmap(it) }
                                if (bitmap != null) {
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = img.filename ?: "imagen adjunta",
                                        modifier = Modifier
                                            .fillMaxWidth(0.7f)
                                            .clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.FillWidth
                                    )
                                } else {
                                    FileRow(name = img.filename ?: "imagen", mime = img.mime ?: "image/*", tint = Color(0xFFD4D4D0))
                                }
                            }
                            imageFiles.forEach { img ->
                                val bitmap = img.url?.let { decodeBase64Bitmap(it) }
                                if (bitmap != null) {
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = img.filename ?: "imagen adjunta",
                                        modifier = Modifier
                                            .fillMaxWidth(0.7f)
                                            .heightIn(max = 260.dp)
                                            .clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.FillWidth
                                    )
                                } else {
                                    FileRow(name = img.filename ?: "imagen", mime = img.mime ?: "image/*", tint = Color(0xFFD4D4D0))
                                }
                            }
                            nonImageFiles.forEach { f ->
                                FileRow(name = f.filename ?: "archivo", mime = f.mime ?: "", tint = Color(0xFFD4D4D0))
                            }
                        }

                        // Delivery Status indicator
                        if (deliveryStatus != null && deliveryStatus != MessageDeliveryStatus.SENT) {
                            Row(
                                modifier = Modifier.padding(top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                when (deliveryStatus) {
                                    MessageDeliveryStatus.PENDING -> {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(10.dp),
                                            strokeWidth = 1.5.dp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            "Enviando…",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    MessageDeliveryStatus.ERROR -> {
                                        Icon(
                                            Icons.Filled.ErrorOutline,
                                            contentDescription = "Error",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Text(
                                            "Error al enviar",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                            color = MaterialTheme.colorScheme.error
                                        )
                                        if (onRetry != null) {
                                            Text(
                                                "• Reintentar",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 10.sp
                                                ),
                                                color = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.clickable { onRetry() }
                                            )
                                        }
                                    }
                                    else -> {}
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Assistant Turn: Continuous CLI wizard rendering with tool steps & markdown
            val parts = msg.parts ?: emptyList()
            val hasToolParts = parts.any { it.type == "tool" }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 2.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (hasToolParts) {
                    parts.forEach { part ->
                        if (part.type == "tool") {
                            ToolExecutionCard(
                                tool = part.tool ?: "bash",
                                command = part.state?.command ?: "",
                                output = part.state?.output,
                                status = part.state?.status ?: "completed",
                                exitCode = part.state?.exitCode ?: 0,
                                duration = part.state?.duration
                            )
                        } else if (part.type == "text" && !part.text.isNullOrBlank()) {
                            MarkdownText(text = part.text.trim())
                        }
                    }
                    val hasRenderedText = parts.any { it.type == "text" && !it.text.isNullOrBlank() }
                    if (!hasRenderedText && displayContent.isNotBlank()) {
                        MarkdownText(text = displayContent)
                    }
                } else if (displayContent.isNotBlank()) {
                    MarkdownText(text = displayContent)
                } else {
                    Text(
                        "(sin respuesta)",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun TerminalStreamingTurn(
    streamText: String,
    streamingTools: List<LiveToolExecution>
) {
    var cursorVisible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            cursorVisible = !cursorVisible
        }
    }
    val cursor = if (cursorVisible) " ▋" else ""

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 2.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Live Tool Execution steps
        streamingTools.forEach { exec ->
            ToolExecutionCard(
                tool = exec.tool,
                command = exec.command,
                output = exec.output,
                status = exec.status,
                exitCode = exec.exitCode,
                duration = exec.duration
            )
        }

        // Generative text streaming
        if (streamText.isBlank()) {
            if (streamingTools.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Generando respuesta…",
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.SansSerif),
                        color = Color(0xFF8B949E)
                    )
                    Text(
                        cursor,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = Color(0xFF58A6FF),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        } else {
            MarkdownText(text = streamText, cursor = cursor)
        }
    }
}

@Composable
private fun TerminalActivityCursor() {
    var cursorVisible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            cursorVisible = !cursorVisible
        }
    }
    val cursor = if (cursorVisible) " ▋" else ""
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            "Generando respuesta…",
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.SansSerif),
            color = Color(0xFF8B949E)
        )
        Text(
            cursor,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = Color(0xFF58A6FF),
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun UnifiedFloatingComposer(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onMic: () -> Unit,
    listening: Boolean,
    attachedFiles: List<AttachedFile>,
    onRemoveFile: (Int) -> Unit,
    selectedModelName: String,
    onSelectModelClick: () -> Unit,
    onVoice: () -> Unit,
    agentMode: String = "build",
    onToggleAgentMode: () -> Unit = {}
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            // Attached files chips
            if (attachedFiles.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    attachedFiles.forEachIndexed { idx, f ->
                        AssistChip(
                            onClick = {},
                            label = { Text("${f.name.take(18)} ${humanSize(f.size)}", maxLines = 1, style = MaterialTheme.typography.labelSmall) },
                            shape = RoundedCornerShape(10.dp),
                            trailingIcon = {
                                IconButton(onClick = { onRemoveFile(idx) }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                                    Icon(Icons.Filled.Close, contentDescription = "Quitar", modifier = Modifier.size(12.dp))
                                }
                            }
                        )
                    }
                }
            }

            // Text input
            TextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Escribe un mensaje" },
                placeholder = {
                    Text(
                        "Escribe un mensaje…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                },
                maxLines = 5,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                    disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                ),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface)
            )

            // Bottom toolbar inside the pill
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IconButton(
                        onClick = onAttach,
                        modifier = Modifier
                            // A-5: target táctil mínimo 48dp (antes 36dp)
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .semantics { contentDescription = "Adjuntar archivo" }
                    ) {
                        Icon(
                            Icons.Outlined.Add,
                            contentDescription = "Adjuntar archivo",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Botón de Agente PLAN / BUILD: 'P' amarillo mostaza / 'B' azul
                    val isPlan = agentMode.lowercase() == "plan"
                    val buttonBg = if (isPlan) Color(0xFFD4A017) else Color(0xFF1E88E5)
                    val letterColor = if (isPlan) Color(0xFF141413) else Color.White
                    val letter = if (isPlan) "P" else "B"
                    val modeDesc = if (isPlan) "Modo Plan (solo lectura)" else "Modo Build (ejecución y edición)"
                    Surface(
                        onClick = onToggleAgentMode,
                        shape = CircleShape,
                        color = buttonBg,
                        modifier = Modifier
                            // A-5: target táctil fijo 48dp. OJO: `.sizeIn(min=48)` sólo
                            // acota el MÍNIMO y este Surface tiene hijo `fillMaxSize()`,
                            // así que se tragaba toda la altura de la fila y crecía a
                            // pantalla completa (bug_2026-09-24). `.size()` acota ambos
                            // extremos: target táctil ≥48dp garantizado, chip contenido.
                            .size(48.dp)
                            .semantics { contentDescription = modeDesc }
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Text(
                                text = letter,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                ),
                                color = letterColor
                            )
                        }
                    }

                    Surface(
                        onClick = onSelectModelClick,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.semantics { contentDescription = "Seleccionar modelo" }
                    ) {
                        Row(
                            // A-5: alto mínimo 48dp para que el target táctil cumpla el mínimo
                            // manteniendo el contenido centrado (layout vertical intacto).
                            modifier = Modifier
                                .sizeIn(minHeight = 48.dp)
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Outlined.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                selectedModelName,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface,
                                // A-5: al crecer los targets vecinos, el nombre del modelo se
                                // trunca con elipsis en vez de romper la fila del compositor.
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IconButton(
                        onClick = onVoice,
                        modifier = Modifier
                            // A-5: target táctil mínimo 48dp (antes 36dp)
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .semantics { contentDescription = "Modo voz" }
                    ) {
                        Icon(
                            Icons.Outlined.Headphones,
                            contentDescription = "Modo voz",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    val canSend = text.isNotBlank() || attachedFiles.isNotEmpty()
                    if (canSend) {
                        IconButton(
                            onClick = onSend,
                            modifier = Modifier
                                // A-5: target táctil mínimo 48dp (antes 36dp)
                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                                .semantics { contentDescription = "Enviar mensaje" }
                        ) {
                            Icon(
                                Icons.Filled.ArrowUpward,
                                contentDescription = "Enviar mensaje",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else if (listening) {
                        val infiniteTransition = rememberInfiniteTransition(label = "pulse_mic")
                        val scale by infiniteTransition.animateFloat(
                            initialValue = 1f,
                            targetValue = 1.15f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(500, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "micScale"
                        )
                        IconButton(
                            onClick = onMic,
                            modifier = Modifier
                                // A-5: target táctil mínimo 48dp (antes 36dp)
                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .graphicsLayer { scaleX = scale; scaleY = scale }
                                .background(MaterialTheme.colorScheme.error, CircleShape)
                                .semantics { contentDescription = "Dejar de escuchar" }
                        ) {
                            Icon(
                                Icons.Filled.Mic,
                                contentDescription = "Dejar de escuchar",
                                tint = MaterialTheme.colorScheme.onError,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else {
                        IconButton(
                            onClick = onMic,
                            modifier = Modifier
                                // A-5: target táctil mínimo 48dp (antes 36dp)
                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                                .semantics { contentDescription = "Hablar" }
                        ) {
                            Icon(
                                Icons.Filled.MicNone,
                                contentDescription = "Hablar",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun humanSize(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "${(b / 1024.0).let { String.format("%.1f", it) }} KB"
    else -> "${(b / 1024.0 / 1024.0).let { String.format("%.1f", it) }} MB"
}

private fun uriToAttachedFile(context: android.content.Context, uri: Uri): AttachedFile? {
    return try {
        val cr = context.contentResolver
        val mime = cr.getType(uri) ?: "application/octet-stream"
        var name = "archivo"
        var size = 0L
        cr.query(uri, null, null, null, null)?.use { c ->
            val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                if (nameIdx >= 0) name = c.getString(nameIdx) ?: name
                if (sizeIdx >= 0) size = c.getLong(sizeIdx)
            }
        }
        // Base64 for images/files under ~5MB, else just metadata
        val isText = mime.startsWith("text/") || name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".json")
        cr.openInputStream(uri)?.use { input ->
            val bytes = input.readBytes()
            if (bytes.size > 5 * 1024 * 1024) {
                AttachedFile(name = name, mime = mime, size = bytes.size.toLong(), text = "[archivo demasiado grande, ${humanSize(bytes.size.toLong())}]")
            } else if (isText) {
                val text = String(bytes, Charsets.UTF_8).take(60000)
                AttachedFile(name = name, mime = mime, size = bytes.size.toLong(), text = text)
            } else {
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                AttachedFile(name = name, mime = mime, size = bytes.size.toLong(), base64 = b64)
            }
        }
    } catch (_: Exception) { null }
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

private fun isTechnicalSessionId(t: String?): Boolean {
    if (t == null) return true
    val s = t.trim()
    if (s.isBlank()) return true
    if (s.startsWith("ses_") || s.startsWith("agy_") || s.startsWith("companion:") || s.startsWith("local_")) return true
    if (s.matches(Regex("^[0-9a-fA-F-]{8,}$"))) return true
    return false
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
