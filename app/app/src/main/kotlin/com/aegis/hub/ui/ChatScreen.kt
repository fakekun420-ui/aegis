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
import androidx.compose.material.icons.filled.HelpOutline
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
import androidx.compose.ui.platform.LocalDensity
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
import com.aegis.hub.data.FormField
import com.aegis.hub.data.PendingForm

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String,
    vm: ChatViewModel,
    onBack: () -> Unit,
    onVoice: () -> Unit = {},
    showTopBar: Boolean = sessionId.isNotBlank(),
    sessionProvider: String? = null,
    // Al cambiar de motor se crea una sesión nueva (el proveedor es el prefijo del
    // id) y hay que navegar a ella. La app lo inyecta; si es null no navega.
    onNavigateToSession: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val messages by vm.messages.collectAsState()
    val sessionTitle by vm.sessionTitle.collectAsState()
    val loading by vm.loading.collectAsState()
    val sendingInFlight by vm.sendingInFlight.collectAsState()
    val error by vm.error.collectAsState()
    val models by vm.models.collectAsState()
    val selectedModel by vm.selectedModel.collectAsState()
    val selectedProvider by vm.selectedProvider.collectAsState()
    val sessionProviderBound by vm.sessionProviderBound.collectAsState()
    val pendingSessionNav by vm.pendingSessionNav.collectAsState()
    val pendingForms by vm.pendingForms.collectAsState()
    val turnInProgress by vm.turnInProgress.collectAsState()
    val turnOver by vm.turnOver.collectAsState()
    val replyingForm by vm.replyingForm.collectAsState()

    // Las filas se calculan AQUÍ y no dentro del `content` del LazyColumn: ese lambda
    // no es un contexto @Composable, y llamar a `remember` dentro de él no compila
    // ("@Composable invocations can only happen from the context of a @Composable
    // function"). El divisor de fin de turno se intercala entre los mensajes aquí.
    val filas = remember(messages, turnOver) { buildChatRows(messages, turnOver) }

    // Cambiar de motor crea una sesión nueva en el destino (el proveedor vive en el
    // prefijo del id) y aquí se navega a ella.
    LaunchedEffect(pendingSessionNav) {
        val target = pendingSessionNav
        if (!target.isNullOrBlank()) {
            vm.consumePendingNav()
            onNavigateToSession?.invoke(target)
        }
    }
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

    // El ViewModel suele estar scoped a la Activity, así que onCleared NO se dispara
    // al navegar a otro chat: el refresco seguiría preguntando al Hub en segundo plano.
    DisposableEffect(sessionId) {
        // Se pasa sessionId para que este cleanup solo pare SU propio refresco: el
        // onDispose de la pantalla anterior puede correr después de que la nueva ya
        // arrancó el suyo, y sin esta propiedad lo mataba (no sincronizaba en vivo).
        onDispose { vm.stopViewRefresh(sessionId) }
    }

    // Auto-scroll on new messages / loading / streaming tools or text changes.
    // Se observan también el id y la LONGITUD del último mensaje: antes la clave era
    // solo `messages.size`, de modo que cuando el asistente seguía escribiendo sobre el
    // MISMO mensaje (poll o streaming) el tamaño no cambiaba, el efecto no se relanzaba
    // y la lista se quedaba anclada a un mensaje anterior en vez de seguir la respuesta
    // más reciente.
    // Altura del teclado. Se usa como clave del auto-scroll para que, al abrirse o
    // cerrarse el teclado, la lista vuelva a dejar visible el final: sin esto el
    // teclado tapaba la última parte del mensaje.
    val density = LocalDensity.current
    val imeInset = WindowInsets.ime.getBottom(density)

    // ---- SCROLL QUE NO ROBA LA NAVEGACION ----
    // El efecto de mas abajo saltaba SIEMPRE al ultimo item, este donde estuvieras.
    // Leyendo historia hacia arriba, en cuanto llegaba algo nuevo (el poll cada 2 s, un
    // token del stream) la lista te arrastraba al final y perdias el sitio.
    //
    // `followOutput` = "el usuario esta abajo, seguile". En cuanto sube a releer historia
    // se pone a false y la lista deja de saltarle al final; el boton con la flecha es la
    // salida manual. Se reinicia por sesion porque al ABRIR un chat siempre se quiere
    // el mensaje mas reciente.
    var followOutput by remember(sessionId) { mutableStateOf(true) }
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.totalItemsCount - 1
            last <= 0 || (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= last
        }
    }

    // Observa donde esta el scroll: si el ultimo item visible deja de ser el ultimo, el
    // usuario subio a historia y hay que dejar de arrastrarlo.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { lastVisible ->
                val total = listState.layoutInfo.totalItemsCount
                if (total > 0) followOutput = lastVisible >= total - 1
            }
    }

    val lastMsgId = messages.lastOrNull()?.info?.id
    val lastMsgTextLen = messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(messages.size, loading, streamingText, streamingTools, lastMsgId, lastMsgTextLen, imeInset, followOutput) {
        // El usuario esta leyendo historia: no se le mueve la lista.
        if (!followOutput) return@LaunchedEffect
        // El conteo REAL de items ya compuestos (listState.layoutInfo), no un cálculo
        // a ciegas. Antes se usaba `messages.size + 1` para sumar el item "live", que
        // Todavía puede no existir en la primera pasada: animateScrollToItem lanzaba
        // IndexOutOfBounds y el `catch (_: Exception) {}` lo tragaba en silencio,
        // dejando la lista clavada en el índice 0 — al abrir un chat se veía el
        // PRIMER mensaje en lugar del más reciente.
        //
        // Se reintenta unas pocas veces porque el efecto corre ANTES de que el
        // LazyColumn recomponga con los mensajes nuevos.
        var intentos = 0
        while (intentos < 8) {
            val total = listState.layoutInfo.totalItemsCount
            if (total > 0) {
                val last = total - 1
                if (intentos == 0) {
                    // Salto instantáneo al final absoluto; animateScrollToItem
                    // interpolaba desde arriba y a menudo no llegaba.
                    listState.scrollToItem(last, Int.MAX_VALUE)
                } else {
                    listState.animateScrollToItem(last)
                }
                return@LaunchedEffect
            }
            intentos++
            delay(80)
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
                                // SIN imePadding() aquí a propósito: el bottomBar del Scaffold
                                // ya lo aplica, y el Box de contenido ya recibe ese padding.
                                // Ponerlo también en la lista cuenta el teclado DOS veces y
                                // colapsaba la altura de la LazyColumn a 0: el chat desaparecía
                                // mientras el teclado estaba abierto. El inset del teclado sí
                                // se usa como clave del auto-scroll (más abajo), que es lo
                                // que hace falta para que la lista vuelva al final.
                                .background(MaterialTheme.colorScheme.background),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // A-5: key basada en id del mensaje, nunca en hashCode() — dos
                            // data classes con igual contenido colisionaban y crasheaban el
                            // LazyColumn con "Key was already used". Fallback por índice
                            // solo para mensajes aún sin id del servidor.
                            // El divisor va ANCLADO a su mensaje, no flotando al final
                            // de la lista. Antes se dibujaba al final en cuanto había un
                            // turno cerrado en el historial, así que aparecía DESPUÉS de
                            // un turno nuevo que aún trabajaba: de ahí "respuesta final"
                            // pegado a "trabajando en ello".
                            //
                            // Para poder intercalar el divisor hay que construir una lista
                            // de filas de dos tipos: `item()` no se puede llamar desde
                            // dentro de `itemsIndexed` (Kotlin lo rechaza por el receptor
                            // implícito), pero `items()` sobre una lista heterogénea sí.
                            items(filas, key = { it.key }) { fila ->
                                when (fila) {
                                    is ChatRow.Mensaje -> TerminalConsoleTurn(
                                        message = fila.message,
                                        sendingInFlight = sendingInFlight,
                                        onRetry = { vm.retryMessage(fila.message, sessionId) }
                                    )
                                    is ChatRow.Cierre -> TurnFinishedDivider()
                                }
                            }
                            // Fase de GENERACION. La de ENVIO no necesita fila propia: la
                            // pildora "Enviando..." que ya vive bajo el mensaje del usuario
                            // (MessageDeliveryStatus.PENDING) la cubre, y como ahora el
                            // mensaje pasa a SENT en cuanto llega el ack, esa pildora
                            // desaparece sola y el relevo lo toma esta fila. Se habia
                            // anadido una SendingRow() aqui y el usuario reporto que se
                            // veian las DOS a la vez: duplicaba un indicador que ya
                            // existia.
                            if (streamingText != null || streamingTools.isNotEmpty()) {
                                item(key = "streaming_live") {
                                    TerminalStreamingTurn(streamingText ?: "", streamingTools)
                                }
                            } else if (loading && !sendingInFlight) {
                                // Nunca junto a la pildora de envio: son las dos fases
                                // del mismo ciclo, excluyentes por construccion.
                                item(key = "typing_dots") { TerminalActivityCursor() }
                            }

                            // Formulario / pregunta pendiente. El TUI del CLI la
                            // pintaba y solo se podia contestar con flechas + Enter;
                            // aqui se responde con un toque.
                            // "Trabajando en ello": solo mientras el turno NO ha
                            // cerrado. Cierra el ciclo con el divisor "respuesta final",
                            // que aparece al terminar.
                            if (turnInProgress) {
                                item(key = "turn_in_progress") {
                                    TurnInProgressRow()
                                }
                            }

                            pendingForms.forEach { form ->
                                item(key = "form_${form.id}") {
                                    PendingFormCard(
                                        form = form,
                                        busy = replyingForm,
                                        onAnswer = { respuestas -> vm.answerForm(form, respuestas) }
                                    )
                                }
                            }

                        }
                    }
                }
            }
            // Boton "ir al mas reciente", al estilo del chat que se le打 de ejemplo: solo
            // aparece cuando NO estas abajo, y tocarlo devuelve el control sin haber tenido
            // que saltarte el historial mientras leias.
            if (!atBottom) {
                SmallFloatingActionButton(
                    onClick = {
                        followOutput = true
                        scope.launch {
                            val last = listState.layoutInfo.totalItemsCount - 1
                            if (last >= 0) listState.animateScrollToItem(last)
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 96.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Ir al mensaje más reciente")
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
                if (sessionProviderBound) {
                    // El proveedor ya NO está bloqueado: los chips están siempre
                    // activos y, al elegir otro motor, se crea una sesión nueva y se
                    // navega a ella (el proveedor vive en el prefijo del id, así que
                    // esta sesión no se puede re-etiquetar). El aviso solo explica que
                    // el cambio no reescribe este chat.
                    Text(
                        "El motor está ligado a esta sesión. Al elegir otro se abrirá un " +
                            "chat nuevo con ese motor; este se conserva tal cual.",
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
private fun TerminalConsoleTurn(
    msg: Message,
    sendingInFlight: Boolean = false,
    onRetry: (() -> Unit)? = null
) {
    val isUser = msg.role == "user"
    val raw = msg.text
    val stripped = msg.strippedText()
    val isMem = msg.isMemoryContext()
    val displayContent = stripped.ifBlank { raw }.trim()

    // La fase de ENVIO se lee de `sendingInFlight`, no de `info.status`.
    //
    // Motivo: `info.status` es un campo del MENSAJE, y hay tres sitios que
    // reemplazan la lista entera por la del servidor (los dos polls y la
    // sincronizacion final). Cada uno de ellos pisa el estado local, asi que
    // cualquier carrera entre el poll y el ack dejaba la pildora en PENDING otra
    // vez, con "Generando respuesta..." debajo: los dos indicadores a la vez, que
    // es justo lo que reporto el usuario. `sendingInFlight` es estado dedicado,
    // global y que ningun poll toca, asi que las dos fases quedan excluyentes por
    // construccion y no por suerte.
    //
    // ERROR si sigue mandando el mensaje: eso si es estado real del servidor y hay
    // que conservarlo.
    val deliveryStatus = when {
        isUser && sendingInFlight -> MessageDeliveryStatus.PENDING
        else -> msg.info?.deliveryStatus ?: if (isUser) MessageDeliveryStatus.SENT else null
    }

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

/**
 * Divisor que confirma que la IA terminó su turno.
 *
 * Ya NO se dibuja "cuando el último mensaje del asistente trae `time.completed`": se
 * dibuja ANCLADO a un mensaje concreto, el último de su turno, y solo si además no le
 * queda ninguna herramienta corriendo detrás (ver `isFinalResponseOf`).
 *
 * `time.completed` por sí solo no marca el cierre del TURNO, sino el del MENSAJE: con
 * esa suposición el divisor saltaba a mitad de turno, o se quedaba pegado al final de
 * la lista mientras el agente ya estaba en el turno siguiente.
 */
@Composable
private fun TurnFinishedDivider() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
        Text(
            "  ✓ respuesta final  ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
    }
}

/**
 * Tarjeta de un formulario / pregunta pendiente de una herramienta.
 *
 * El TUI del CLI la pintaba como un menú de flechas + Enter. Aquí se responde con un
 * toque, que es justo lo que faltaba para no depender del CLI.
 *
 * MULTIPREGUNTA — dos casos, a propósito:
 *
 *  - **Una sola pregunta:** un toque envía y listo. Es el caso frecuente y no debe
 *    costar un segundo toque de confirmación.
 *  - **Varias preguntas:** cada toque solo ELIGE, y se accumulates hasta que esté todo
 *    marcado; luego un botón envía el mapa entero. No se puede enviar al vuelo porque
 *    `POST .../reply` resuelve el formulario entero con lo que llegue: mandar una sola
 *    respuesta descarta las demás en silencio (medido con un formulario real de 3
 *    campos — se mandó q0 y q1/q2 se perdieron sin aviso).
 */
@Composable
private fun PendingFormCard(
    form: PendingForm,
    busy: Boolean,
    onAnswer: (Map<String, String>) -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    val contestables = form.optionFields
    if (contestables.isEmpty()) {
        // Sin una sola opción no hay nada que tocar. Se dice en vez de pintar botones
        // que no podrían hacer nada.
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Filled.HelpOutline, contentDescription = "Pregunta pendiente", tint = accent)
                Text(
                    form.title ?: "Confirmación necesaria",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                "Esta pregunta no trae opciones: respóndela escribiéndola en el chat.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    // Key por id de formulario: sobrevive a los refrescos de 2 s, que reconstruyen la
    // lista de pendientes, y se reinicia solo si el formulario cambia.
    var elegidas by remember(form.id) { mutableStateOf<Map<String, String>>(emptyMap()) }

    val completas = contestables.count { elegidas.containsKey(it.key) }
    val todoMarcado = completas == contestables.size

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Filled.HelpOutline, contentDescription = "Pregunta pendiente", tint = accent)
            Text(
                form.title ?: "Confirmación necesaria",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        }

        contestables.forEachIndexed { indice, field ->
            val key = field.key.orEmpty()
            val elegida = elegidas[key]
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    buildString {
                        if (contestables.size > 1) append("${indice + 1}. ")
                        append(field.title ?: "Pregunta")
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
                val detail = field.description
                if (!detail.isNullOrBlank() && detail != field.title) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                field.options.orEmpty().forEach { opt ->
                    val valor = form.optionValue(opt)
                    val marcada = elegida == valor
                    Card(
                        onClick = {
                            if (!busy) {
                                if (form.isOneTap) {
                                    onAnswer(mapOf(key to valor))
                                } else {
                                    elegidas = elegidas + (key to valor)
                                }
                            }
                        },
                        enabled = !busy,
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.outlinedCardColors(
                            containerColor = if (marcada) {
                                accent.copy(alpha = 0.16f)
                            } else {
                                MaterialTheme.colorScheme.surface
                            }
                        )
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(opt.label ?: valor, style = MaterialTheme.typography.bodyMedium)
                            val d = opt.description
                            if (!d.isNullOrBlank()) {
                                Text(
                                    d,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        if (form.freeFields.isNotEmpty()) {
            Text(
                "⚠ ${form.freeFields.size} pregunta(s) no traen opciones y se descartarán al enviar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        if (!form.isOneTap) {
            Button(
                onClick = { onAnswer(elegidas) },
                enabled = !busy && todoMarcado,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (form.freeFields.isEmpty())
                        "Enviar respuestas ($completas/${contestables.size})"
                    else
                        "Enviar solo $completas de ${form.allFields.size} respuestas"
                )
            }
            if (!todoMarcado) {
                Text(
                    "Marca las ${contestables.size} preguntas para poder enviar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Indicador de "trabajando en ello".
 *
 * Es el complementario del divisor "✓ respuesta final": mientras el turno sigue
 * abierto (el último mensaje del asistente no trae `time.completed`) se ve esto; al
 * cerrarse, aparece el divisor. Así siempre se sabe en qué fase está la ejecución.
 */
@Composable
private fun TurnInProgressRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.height(14.dp).width(14.dp),
            strokeWidth = 2.dp
        )
        Text(
            "Trabajando en ello…",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * ¿Este mensaje del asistente es el cierre real de un turno?
 *
 * Dos casos, y el segundo es el que se estaba fallando:
 *
 *  - **No es el último mensaje** y detrás viene uno del usuario: ese turno está
 *    cerrado por historia, el divisor se queda ahí.
 *  - **Es el último mensaje**: solo se marca si el agente ha parado de verdad
 *    (`turnOver`, que viene de `session.execution.*`). Antes se exigía
 *    `info.time.completed`, pero eso cierra el MENSAJE y se cumple tras cada `bash`
 *    con exit 0 aunque el agente siga trabajando — de ahí que el divisor saltaba a
 *    mitad de turno, que es justo lo que se quiso evitar.
 */
private fun isFinalResponseOf(messages: List<Message>, index: Int, turnOver: Boolean): Boolean {
    val msg = messages[index]
    if (msg.role != "assistant") return false
    val next = messages.getOrNull(index + 1)
    // Si despues viene un mensaje del usuario, ese turno esta cerrado por historia:
    // el divisor se queda, es el cierre real de aquello.
    if (next != null) return next.role == "user"
    // Es el ULTIMO mensaje: aqui solo se marca si el agente ha parado de verdad
    // (session.execution.*). Antes se exigia `time.completed`, que se cumple tras cada
    // `bash` con exit 0 aunque siga trabajando, y por eso saltaba a mitad de turno.
    return turnOver
}

/** Una fila de la lista del chat: un mensaje, o el divisor que cierra un turno. */
private sealed interface ChatRow {
    val key: String

    data class Mensaje(val index: Int, val message: Message) : ChatRow {
        // Fallback por ÍNDICE, nunca por hashCode(): dos data classes con igual
        // contenido colisionaban y reventaban el LazyColumn con "Key was already used".
        override val key: String get() = message.info?.id ?: "msg_$index"
    }

    data class Cierre(val msgId: String) : ChatRow {
        override val key: String get() = "turn_finished_$msgId"
    }
}

private fun buildChatRows(messages: List<Message>, turnOver: Boolean): List<ChatRow> = buildList {
    messages.forEachIndexed { index, msg ->
        add(ChatRow.Mensaje(index, msg))
        if (isFinalResponseOf(messages, index, turnOver)) {
            add(ChatRow.Cierre(msg.info?.id ?: "idx_$index"))
        }
    }
}
