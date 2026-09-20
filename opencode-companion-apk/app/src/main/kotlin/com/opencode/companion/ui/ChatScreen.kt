package com.opencode.companion.ui

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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.opencode.companion.data.AttachedFile
import com.opencode.companion.data.Message
import com.opencode.companion.data.MessageDeliveryStatus
import com.opencode.companion.ui.viewmodel.ChatViewModel
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
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val models by vm.models.collectAsState()
    val selectedModel by vm.selectedModel.collectAsState()
    var showModelSheet by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(sessionId) { vm.load(sessionId) }

    // Auto-scroll on new messages / loading state changes
    LaunchedEffect(messages.size, loading) {
        val totalCount = messages.size + (if (loading && messages.isNotEmpty()) 1 else 0)
        if (totalCount > 0) {
            delay(80)
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
    var duplex by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var sttError by remember { mutableStateOf<String?>(null) }
    var composerText by remember { mutableStateOf("") }
    var attachedFiles by remember { mutableStateOf<List<AttachedFile>>(emptyList()) }
    var duplexJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

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
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(sessionId.take(8).ifBlank { "Chat" }, maxLines = 1)
                            if (!sessionProvider.isNullOrBlank()) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(
                                        text = sessionProvider.replaceFirstChar { it.uppercase() },
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                    },
                    navigationIcon = { IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Volver" }) { Icon(Icons.Filled.ArrowBack, contentDescription = "Volver") } },
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
                if (attachedFiles.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        attachedFiles.forEachIndexed { idx, f ->
                            AssistChip(
                                onClick = {},
                                label = { Text("${f.name.take(18)} ${humanSize(f.size)}", maxLines = 1) },
                                trailingIcon = {
                                    IconButton(onClick = { attachedFiles = attachedFiles.filterIndexed { i, _ -> i != idx } }, modifier = Modifier.size(18.dp)) {
                                        Icon(Icons.Filled.Close, contentDescription = "Quitar", modifier = Modifier.size(12.dp))
                                    }
                                }
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AssistChip(
                        onClick = { showModelSheet = true },
                        label = {
                            val modelName = models.find { it.id == selectedModel }?.name ?: selectedModel ?: "Modelo"
                            Text(modelName, style = MaterialTheme.typography.labelSmall)
                        },
                        leadingIcon = { Icon(Icons.Filled.SmartToy, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.semantics { contentDescription = "Seleccionar modelo" }
                    )
                    Spacer(Modifier.weight(1f))
                    FilledTonalIconButton(
                        onClick = onVoice,
                        modifier = Modifier.size(40.dp).semantics { contentDescription = "Modo voz" }
                    ) {
                        Icon(Icons.Filled.Headset, contentDescription = "Modo voz", modifier = Modifier.size(20.dp))
                    }
                }
                ComposerBar(
                    text = composerText,
                    onTextChange = { composerText = it },
                    onSend = {
                        val t = composerText.trim()
                        if (t.isNotBlank() || attachedFiles.isNotEmpty()) {
                            vm.sendWithFiles(sessionId, t, attachedFiles)
                            composerText = ""
                            attachedFiles = emptyList()
                        }
                    },
                    onAttach = { showAttachSheet = true },
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
                                modifier = Modifier.size(24.dp)
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
                                            label = { Text(prompt, style = MaterialTheme.typography.bodySmall) }
                                        )
                                    }
                                }
                            }
                        }
                        else -> LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(messages, key = { it.info?.id ?: it.hashCode().toString() }) { msg ->
                                MessageBubble(msg, onRetry = {
                                    if (sessionId.isNotBlank()) vm.retryMessage(msg, sessionId)
                                })
                            }
                            if (loading) {
                                item {
                                    AssistantTypingBubble()
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
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Seleccionar modelo", style = MaterialTheme.typography.titleMedium)
                models.forEach { model ->
                    ListItem(
                        headlineContent = { Text(model.name) },
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
                    Text("Cargando modelos…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun MessageBubble(msg: Message, onRetry: (() -> Unit)? = null) {
    val isUser = msg.role == "user"
    val raw = msg.text
    val isMem = msg.isMemoryContext()
    val stripped = if (isMem) msg.strippedText() else raw
    val deliveryStatus = msg.info?.deliveryStatus ?: if (isUser) MessageDeliveryStatus.SENT else null

    val files = msg.fileParts()
    val images = msg.imageParts()
    val imageFiles = files.filter { it.mime?.startsWith("image/") == true && it.url != null }
    val nonImageFiles = files.filter { !(it.mime?.startsWith("image/") == true && it.url != null) }

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
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Image previews (legacy type:"image" parts)
                images.forEach { img ->
                    val bitmap = (img.image ?: img.data ?: img.url)?.let { decodeBase64Bitmap(it) }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = img.filename ?: "imagen adjunta",
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.FillWidth
                        )
                    } else {
                        FileRow(name = img.filename ?: "imagen", mime = img.mime ?: "image/*", tint = contentColor)
                    }
                }
                // Image-type file parts (opencode stores images as type:file with url data URI)
                imageFiles.forEach { img ->
                    val bitmap = img.url?.let { decodeBase64Bitmap(it) }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = img.filename ?: "imagen adjunta",
                            modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.FillWidth
                        )
                    } else {
                        FileRow(name = img.filename ?: "imagen", mime = img.mime ?: "image/*", tint = contentColor)
                    }
                }
                // Non-image file attachments as icon rows
                nonImageFiles.forEach { f ->
                    FileRow(name = f.filename ?: "archivo", mime = f.mime ?: "", tint = contentColor)
                }
                if (stripped.isNotBlank() || raw.isNotBlank()) {
                    if (isUser) {
                        Text(stripped.ifBlank { raw }, color = contentColor, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        MarkdownText(stripped.ifBlank { raw })
                    }
                } else if (images.isEmpty() && imageFiles.isEmpty() && nonImageFiles.isEmpty()) {
                    Text("(vacío)", color = contentColor.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
                }

                // Delivery Status for User Messages
                if (isUser && deliveryStatus != null) {
                    Row(
                        modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        when (deliveryStatus) {
                            MessageDeliveryStatus.PENDING -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(10.dp),
                                    strokeWidth = 1.5.dp,
                                    color = contentColor.copy(alpha = 0.7f)
                                )
                                Text(
                                    "Enviando…",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = contentColor.copy(alpha = 0.7f)
                                )
                            }
                            MessageDeliveryStatus.ERROR -> {
                                Icon(
                                    Icons.Filled.ErrorOutline,
                                    contentDescription = "Error",
                                    tint = MaterialTheme.colorScheme.errorContainer,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    "Error al enviar",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.errorContainer
                                )
                                if (onRetry != null) {
                                    Text(
                                        "• Reintentar",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 10.sp
                                        ),
                                        color = MaterialTheme.colorScheme.errorContainer,
                                        modifier = Modifier.clickable { onRetry() }
                                    )
                                }
                            }
                            MessageDeliveryStatus.SENT -> {
                                Icon(
                                    Icons.Filled.Done,
                                    contentDescription = "Enviado",
                                    tint = contentColor.copy(alpha = 0.7f),
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantTypingBubble() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing_dots")
    val dot1 by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 0, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot1"
    )
    val dot2 by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 180, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot2"
    )
    val dot3 by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 360, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot3"
    )

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 4.dp),
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dot1),
                            shape = CircleShape
                        )
                )
                Box(
                    Modifier
                        .size(8.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dot2),
                            shape = CircleShape
                        )
                )
                Box(
                    Modifier
                        .size(8.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dot3),
                            shape = CircleShape
                        )
                )
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            IconButton(onClick = onAttach, modifier = Modifier.semantics { contentDescription = "Adjuntar archivo" }) {
                Icon(Icons.Filled.AttachFile, contentDescription = "Adjuntar archivo")
            }
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "Escribe un mensaje" },
                placeholder = { Text("Escribe un mensaje…") },
                maxLines = 5,
                shape = RoundedCornerShape(20.dp)
            )
            IconButton(
                onClick = onSend,
                enabled = text.isNotBlank(),
                modifier = Modifier.semantics { contentDescription = "Enviar mensaje" }
            ) {
                Icon(
                    Icons.Filled.Send,
                    contentDescription = "Enviar mensaje",
                    tint = if (text.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            }
            IconButton(onClick = onMic, modifier = Modifier.semantics { contentDescription = if (listening) "Dejar de escuchar" else "Hablar" }) {
                Icon(
                    if (listening) Icons.Filled.Mic else Icons.Filled.MicNone,
                    contentDescription = if (listening) "Dejar de escuchar" else "Hablar",
                    tint = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
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
