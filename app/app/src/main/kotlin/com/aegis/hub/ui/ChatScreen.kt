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
import androidx.compose.foundation.verticalScroll
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
import com.aegis.hub.data.MessagePart
import com.aegis.hub.data.MessageDeliveryStatus
import com.aegis.hub.ui.chat.SubagentCard
import com.aegis.hub.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import com.aegis.hub.data.FormField
import com.aegis.hub.data.PendingForm
import com.aegis.hub.data.PendingPermission
import com.aegis.hub.data.PartFull
import com.aegis.hub.data.filtrarPorTexto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String,
    vm: ChatViewModel,
    onBack: () -> Unit,
    showTopBar: Boolean = sessionId.isNotBlank()
) {
    val context = LocalContext.current
    val messages by vm.messages.collectAsState()
    val sessionTitle by vm.sessionTitle.collectAsState()
    val loading by vm.loading.collectAsState()
    val sendingInFlight by vm.sendingInFlight.collectAsState()
    val error by vm.error.collectAsState()
    val servidorAlcanzable by vm.servidorAlcanzable.collectAsState()
    val models by vm.models.collectAsState()
    val selectedModel by vm.selectedModel.collectAsState()
    val pendingForms by vm.pendingForms.collectAsState()
    val pendingPermissions by vm.pendingPermissions.collectAsState()
    val replyingPermission by vm.replyingPermission.collectAsState()
    val turnInProgress by vm.turnInProgress.collectAsState()
    // El divisor usa la MISMA decisión que la notificación (vm.turnFinished), no el
    // turnOver crudo del vigilante: leer dos señales distintas es lo que hacía que el
    // aviso saliera tras cada bash mientras el divisor no aparecía nunca.
    val turnFinished by vm.turnFinished.collectAsState()
    val replyingForm by vm.replyingForm.collectAsState()

    // Las filas se calculan AQUÍ y no dentro del `content` del LazyColumn: ese lambda
    // no es un contexto @Composable, y llamar a `remember` dentro de él no compila
    // ("@Composable invocations can only happen from the context of a @Composable
    // function"). El divisor de fin de turno se intercala entre los mensajes aquí.
    val filas = remember(messages, turnFinished) { buildChatRows(messages, turnFinished) }

    val modelsLoading by vm.modelsLoading.collectAsState()
    val streamingText by vm.streamingText.collectAsState()
    val streamingTools by vm.streamingTools.collectAsState()
    val agentMode by vm.agentMode.collectAsState()
    val agents by vm.agents.collectAsState()
    // MEDIDO 2026-10-01: la hoja se llenaba con una sola llamada hecha al crear el ViewModel.
    // Si esa llamada fallaba, la hoja quedaba vacia PARA SIEMPRE: el boton no volvia a
    // pedirla y no decia por que. Aqui se pide al abrir, que es cuando hace falta.
    val agentsLoading by vm.agentsLoading.collectAsState()
    val agentsError by vm.error.collectAsState()
    var showModelSheet by remember { mutableStateOf(false) }
    var showAgentSheet by remember { mutableStateOf(false) }
    // Al abrir la hoja se pide la lista. Es idempotente: el ViewModel no hace nada si ya
    // esta cargando, y la respuesta se cachea en el StateFlow, asi que abrirla dos veces
    // seguidas no son dos peticiones.
    LaunchedEffect(showAgentSheet) { if (showAgentSheet) vm.loadAgents() }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(sessionId) {
        vm.load(sessionId)
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
    var speaking by remember { mutableStateOf(false) }
    // MEDIDO 2026-10-01: una sola cola para las dos pantallas. Antes cada una tenia la suya
    // y las dos hablaban SOLO la primera frase, con QUEUE_FLUSH. El porque del fallo esta
    // en TtsQueue.kt, que es donde queda documentado y no aqui otra vez.
    val colaTts = remember { TtsQueue { speaking = it } }

    DisposableEffect(Unit) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) engine?.language = Locale("es", "ES")
        }
        // El listener de fin de locucion se instala aqui, UNA vez y sobre el motor: es lo que
        // hace que `speaking` lo baje el sintetizador y no una estimacion de un instante.
        colaTts.attach(engine)
        tts = engine
        onDispose { try { engine?.shutdown() } catch (_: Exception) {} }
    }

    fun queueTts(text: String) { colaTts.enqueue(text) }

    // Voice: STT push-to-talk. El modo de duplex salio el 2026-10-01: el microfono
    // transcribe una vez y ya no se reabre solo.
    // UX-04/A-5: rememberSaveable — el modo de conversación y el borrador del mensaje
    // sobreviven a rotación/muerte del proceso (con remember puro se perdían al girar).
    var leerEnVoz by rememberSaveable { mutableStateOf(false) }

    // MEDIDO 2026-10-01: sin esto el interruptor solo dejaba de ENCOLAR. Lo ya encolado
    // seguia sonando hasta el final de la respuesta, que es justo lo que reporto el usuario.
    // Al apagar el interruptor se corta, y al salir de la pantalla tambien: si no, el motor
    // sigue hablando con la pantalla cerrada.
    //
    // Va DESPUES de la declaracion de `leerEnVoz` y no antes: en Kotlin un `val` local tiene
    // que existir antes de su primer uso. Estaba 7 lineas por encima y la CI lo cazó con
    // "Unresolved reference 'leerEnVoz'" — el error no decia nada del orden.
    LaunchedEffect(leerEnVoz) { if (!leerEnVoz) colaTts.stop() }
    DisposableEffect(Unit) { onDispose { colaTts.stop() } }
    var listening by remember { mutableStateOf(false) }
    var sttError by remember { mutableStateOf<String?>(null) }
    var composerText by rememberSaveable { mutableStateOf("") }
    var attachedFiles by remember { mutableStateOf<List<AttachedFile>>(emptyList()) }
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

    // MEDIDO 2026-10-01: era `GetContent()`, que devuelve UN solo Uri. El selector de
    // "Archivos" de al lado ya era multi (`OpenMultipleDocuments`), asi que depende de que
    // boton pulses para poder adjuntar varias imagenes. `GetMultipleContents` devuelve la
    // lista y no exige ninguna version de API. El tope de 6 es el mismo que usa el otro.
    val photoPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        val nuevas = uris.take(6 - attachedFiles.size).mapNotNull { uri -> uriToAttachedFile(context, uri) }
        attachedFiles = attachedFiles + nuevas
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


    // Cuando llega un mensaje del asistente, leerlo en voz si el interruptor esta activo.
    // MEDIDO 2026-10-01: esto era `if (duplex && ...)`. Al quitar el modo duplex el TTS
    // se quedaba sin disparador, asi que ahora depende del interruptor del compositor.
    LaunchedEffect(messages, leerEnVoz) {
        if (!leerEnVoz) return@LaunchedEffect
        val last = messages.lastOrNull()
        if (last != null && last.role == "assistant") {
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
                )
            }
        },
        bottomBar = {
            Column(modifier = Modifier.navigationBarsPadding().imePadding()) {
                // El chip NUNCA cae al id crudo: si el modelo elegido no esta en la
                // lista se muestra como no disponible en vez de imprimir un id como si
                // fuera un nombre de modelo. Ese fallback hacia que el chip pareciera
                // correcto mientras ningun radio podia marcarse — que es exactamente
                // lo que pasaba con el id caducado que se usaba por defecto.
                // MEDIDO 2026-10-03: el valor puede venir con prefijo `proveedor/id` de
                // prefs viejas; se compara sin el para que la lista (ids cortos) coincida.
                val modeloCorto = selectedModel?.trim()?.substringAfterLast("/")?.trim().orEmpty()
                val modelDisplayName = models.find { it.id == modeloCorto }?.name
                    ?: when {
                        modeloCorto.isBlank() && !modelsLoading -> "Elige un modelo"
                        modeloCorto.isBlank() -> "Cargando modelos…"
                        else -> "No disponible: $modeloCorto"
                    }

                UnifiedFloatingComposer(
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
                        if (!ensureMicPermission()) return@UnifiedFloatingComposer
                        if (listening) {
                            try { recognizer?.stopListening() } catch (_: Exception) {}
                        } else {
                            startListeningInternal(
                                context, recognizer, { recognizer = it }, { listening = it }, { sttError = it },
                                onResult = { t -> composerText = if (composerText.isBlank()) t else "$composerText $t" },
                            )
                        }
                    },
                    listening = listening,
                    attachedFiles = attachedFiles,
                    onRemoveFile = { idx -> attachedFiles = attachedFiles.filterIndexed { i, _ -> i != idx } },
                    selectedModelName = modelDisplayName,
                    onSelectModelClick = { showModelSheet = true },
                    leerEnVoz = leerEnVoz,
                    onToggleLeerEnVoz = { leerEnVoz = !leerEnVoz },
                    agentMode = agentMode,
                    agentIsPrimary = agents.firstOrNull { it.name == agentMode }?.mode != "subagent",
                    onSelectAgentClick = { showAgentSheet = true }
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
                // El Hub no responde. NO lleva boton de cerrar a proposito: no es un
                // error que el usuario pueda descartar, es un estado. Se va solo en
                // cuanto un ciclo vuelve a salir bien (noteRefreshResult). Sin esto, un
                // 429/502 durante el refresco dejaba la pantalla con el estado viejo y
                // sin decir nada: "Trabajando en ello" para un turno ya acabado.
                if (!servidorAlcanzable) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
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
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Sin conexión con OpenCode: lo que ves puede no ser el estado real.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
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
                                        msg = fila.message,
                                        sendingInFlight = sendingInFlight,
                                        onRetry = { vm.retryMessage(fila.message, sessionId) },
                                        onLoadPart = { partId, done -> vm.loadPartFull(partId, null, done) }
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
                            // Fases del ciclo, excluyentes por construccion.
                            //
                            // El `when` tiene que cubrir las TRES ramas, no solo la
                            // segunda: al empezar a enviar, `_streamingText` vale "" (no
                            // null), asi que `streamingText != null` es CIERTO y la fila
                            // de streaming se pintaba igual. Y como por dentro escribe
                            // "Generando respuesta..." cuando el texto va vacio, salia
                            // junto a la pildora "Enviando...". Por eso el arreglo
                            // anterior, que solo condicionaba la rama de `loading`,
                            // parecio funcionar y seguia mostrando los dos a la vez.
                            when {
                                sendingInFlight -> Unit
                                streamingText != null || streamingTools.isNotEmpty() ->
                                    item(key = "streaming_live") {
                                        TerminalStreamingTurn(streamingText ?: "", streamingTools)
                                    }
                                loading -> item(key = "typing_dots") { TerminalActivityCursor() }
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

                            // Los permisos van antes que los formularios: si hay una
                            // herramienta esperando permiso, ESA es la razón por la que
                            // el turno no avanza, y una encuesta posterior es ruido.
                            pendingPermissions.forEach { perm ->
                                item(key = "perm_${perm.id}") {
                                    PendingPermissionCard(
                                        permission = perm,
                                        busy = replyingPermission,
                                        onDecision = { d -> vm.answerPermission(perm, d) }
                                    )
                                }
                            }

                        }
                    }
                }
            }
            // Boton "ir al mas reciente", al estilo del chat que se le da de ejemplo: solo
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

    // Hoja de agentes. Estructura igual que la de modelos a proposito: las dos son
    // "elegir una cosa de una lista que da el Hub", y hacerlas distintas solo para que
    // se parezcan es trabajo sin resultado.
    if (showAgentSheet) {
        ModalBottomSheet(onDismissRequest = { showAgentSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // El scroll va ANTES que el alto maximo, y no es cosmetico: sin el,
                    // `heightIn` recortaba en silencio. MEDIDO: con la lista de 40, la
                    // hoja mostraba 4 de 6 primarios y el resto no existia para el
                    // usuario — ni se podia tocar, ni se arrastraba, ni habia aviso de que
                    // faltara algo. Un limite de altura sin scroll no acota nada: solo
                    // hace desaparecer lo que no cabe.
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 520.dp)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Agente", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Los ${agents.size} que OpenCode deja elegir. Los cargos y los demas " +
                        "subagentes no salen porque un agente los invoca, no porque los elijas tu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                if (agents.isEmpty()) {
                    // Decir POR QUE. Antes era un texto fijo, y un fallo de red es
                    // indistinguible de "no hay agentes": los dos se veian igual, y no habia
                    // forma de reintentar.
                    Text(
                        when {
                            agentsLoading -> "Cargando agentes\u2026"
                            agentsError != null -> "No se pudieron cargar: ${agentsError!!}"
                            else -> "No se pudieron cargar los agentes. Revisa que OpenCode este activo."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!agentsLoading) {
                        TextButton(onClick = { vm.loadAgents() }) { Text("Reintentar") }
                    }
                } else {
                    agents.forEach { ag ->
                                ListItem(
                                    headlineContent = {
                                        Text(
                                            ag.name,
                                            fontWeight = if (ag.name == agentMode) FontWeight.SemiBold else FontWeight.Normal
                                        )
                                    },
                                    supportingContent = {
                                        Text(
                                            listOfNotNull(
                                                ag.description?.takeIf { it.isNotBlank() },
                                                if (ag.model != null) "modelo: ${ag.model}" else null
                                            ).joinToString(" \u00b7 ").ifBlank { ag.mode }
                                        )
                                    },
                                    leadingContent = {
                                        RadioButton(
                                            selected = ag.name == agentMode,
                                            onClick = { vm.selectAgent(ag.name); showAgentSheet = false }
                                        )
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { vm.selectAgent(ag.name); showAgentSheet = false }
                                )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showModelSheet) {
        ModalBottomSheet(onDismissRequest = { showModelSheet = false }) {
            // La busqueda se reinicia cada vez que se abre la hoja (entra en
            // composicion): conservar el filtro anterior no tiene sentido.
            var busqueda by remember { mutableStateOf("") }
            val visibles = remember(busqueda, models) { models.filtrarPorTexto(busqueda) }
            Column(
                modifier = Modifier.fillMaxWidth()
                    // MEDIDO 2026-10-09 (V-03): el catalogo es amplio y la hoja
                    // no scrolleaba: los modelos fuera de pantalla no existian
                    // para el usuario. Mismo patron que la hoja de agentes.
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 520.dp)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Modelo", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Elige el modelo para esta sesión:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // F4: la lista sale del catalogo con cache (5 min); esto la invalida
                    // y la vuelve a pedir (p. ej. tras instalar un proveedor nuevo).
                    TextButton(onClick = { vm.refrescarCatalogo() }) { Text("Actualizar") }
                }
                OutlinedTextField(
                    value = busqueda,
                    onValueChange = { busqueda = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Buscar modelo…") },
                    singleLine = true
                )

                // El selector de motor se fue con Antigravity: queda uno solo, asi
                // que una fila de chips donde una opcion esta siempre activa es ruido
                // que hace creer que se puede cambiar algo que no se puede.
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                visibles.forEach { model ->
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
                if (visibles.isEmpty()) {
                    Text(
                        when {
                            modelsLoading -> "Cargando modelos…"
                            models.isEmpty() -> "No se pudieron cargar los modelos. Revisa que OpenCode esté activo."
                            else -> "Sin resultados para «$busqueda»."
                        },
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
private fun FileRow(
    name: String,
    mime: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: (() -> Unit)? = null,
    cargando: Boolean = false
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = if (onClick != null) {
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = !cargando) { onClick?.invoke() }
        } else Modifier
    ) {
        if (cargando) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = tint
            )
        } else {
            Icon(
                if (mime.startsWith("image/")) Icons.Filled.Photo else Icons.Filled.FolderOpen,
                contentDescription = null, tint = tint, modifier = Modifier.size(20.dp)
            )
        }
        Column {
            Text(name, style = MaterialTheme.typography.bodySmall, color = tint, maxLines = 1)
            if (mime.isNotBlank()) Text(mime, style = MaterialTheme.typography.labelSmall, color = tint.copy(alpha = 0.7f), maxLines = 1)
        }
    }
}


/**
 * Una fila de parte adjunta, con el toque que recupera el binario cuando la venia
 * recortada.
 *
 * Esta en un solo lugar a proposito. La version anterior tenia la logica metida dentro
 * del bucle de `images`, y MEDIDO sobre una sesion real resulto ser codigo muerto: las
 * partes recortadas son type="file" sin `url` (el recorte se lo borra), asi que caen en
 * `nonImageFiles` y no en `images`. Con el toque en la rama, no lo tocaba nadie.
 * Ahora las tres ramas pasan por aqui, asi que el toque va con la parte y no con la
 * rama: si el reparto cambia manana, sigue funcionando.
 *
 * @param alto 0 = sin limite de alto; >0 = tope en dp, como necesitan los adjuntos
 *   pequenos. Las imagenes grandes se muestran a ancho completo sin tope.
 */
@Composable
private fun PartRow(
    part: MessagePart,
    nombre: String,
    mime: String,
    tint: androidx.compose.ui.graphics.Color,
    onLoadPart: ((partId: String?, done: (PartFull?) -> Unit) -> Unit)?,
    alto: Int = 0
) {
    // La clave del remember es el id de la PARTE y no el del mensaje: con la del mensaje
    // dos imagenes del mismo turno comparten estado y pulsar una moveria la otra.
    var descargada by remember(part.id) { mutableStateOf<String?>(null) }
    var pidiendo by remember(part.id) { mutableStateOf(false) }

    val bitmap = (descargada ?: part.image ?: part.data ?: part.url)
        ?.let { decodeBase64Bitmap(it) }

    if (bitmap != null) {
        val forma = if (alto > 0) {
            Modifier.fillMaxWidth(0.7f).heightIn(max = alto.dp).clip(RoundedCornerShape(8.dp))
        } else {
            Modifier.fillMaxWidth(0.7f).clip(RoundedCornerShape(8.dp))
        }
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = nombre,
            modifier = forma,
            contentScale = ContentScale.FillWidth
        )
        return
    }

    // El toque solo si la parte venia recortada Y el binario es una imagen. En
    // nonImageFiles tambien caen adjuntos que no lo son, y bajarse 5 MB de un PDF para
    // no poder pintar nada es peor que no tener toque: se descarga solo lo que se ve.
    val esImagen = mime.isBlank() || mime.startsWith("image/")
    val pulsable = part.hasBinary == true && esImagen && !pidiendo && onLoadPart != null
    FileRow(
        name = nombre,
        mime = mime,
        tint = tint,
        cargando = pidiendo,
        onClick = if (pulsable) {
            {
                pidiendo = true
                onLoadPart?.invoke(part.id) { parte ->
                    // Siempre se invoca, tambien en error (lo garantiza loadPartFull): si
                    // no, un fallo deja el spinner girando para siempre. Por eso el false
                    // va ANTES de asignar el resultado.
                    pidiendo = false
                    descargada = parte?.base64()
                }
            }
        } else null
    )
}

@Composable
private fun TerminalConsoleTurn(
    msg: Message,
    sendingInFlight: Boolean = false,
    onRetry: (() -> Unit)? = null,
    // Cargar una parte que venia recortada. Se pasa como lambda de comportamiento
    // (el mismo patron que onRetry) y no el ViewModel entero: la sesion ya la sabe
    // el ViewModel, y pasar `vm` por el arbol de composables por un parametro es
    // ruido que se propaga a todas las firmas intermedias.
    onLoadPart: ((partId: String?, done: (PartFull?) -> Unit) -> Unit)? = null
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
                                PartRow(
                                    part = img,
                                    nombre = img.filename ?: "imagen",
                                    mime = img.mime ?: "image/*",
                                    tint = Color(0xFFD4D4D0),
                                    onLoadPart = onLoadPart
                                )
                            }
                            imageFiles.forEach { img ->
                                PartRow(
                                    part = img,
                                    nombre = img.filename ?: "imagen",
                                    mime = img.mime ?: "image/*",
                                    tint = Color(0xFFD4D4D0),
                                    onLoadPart = onLoadPart,
                                    alto = 260
                                )
                            }
                            nonImageFiles.forEach { f ->
                                PartRow(
                                    part = f,
                                    nombre = f.filename ?: "archivo",
                                    mime = f.mime ?: "",
                                    tint = Color(0xFFD4D4D0),
                                    onLoadPart = onLoadPart
                                )
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
                            val st = part.state
                            // Una delegacion se pinta como subagente, no como bash.
                            // Antes TODO tool caia en ToolExecutionCard con `part.tool
                            // ?: "bash"`, y como `tool` llega siempre a null MEDIDO,
                            // una delegacion salia rotulada "bash", sin comando y sin
                            // resultado. `toolName`/`outputText` leen la forma real del
                            // input y del state nativo (v2 usa `content`, no `output`).
                            if (st != null && st.isSubagent) {
                                SubagentCard(state = st)
                            } else {
                                ToolExecutionCard(
                                    // El nombre REAL gana si viaja (lo preserva el
                                    // normalizador, providers.js:87); la inferencia es
                                    // solo el respaldo para la ruta nativa v2, que no lo
                                    // manda. Al reves se perderia el nombre bueno.
                                    tool = part.tool ?: st?.toolName ?: "herramienta",
                                    command = st?.command ?: "",
                                    output = st?.outputText,
                                    status = st?.status ?: "completed",
                                    exitCode = st?.exitCode ?: 0,
                                    duration = st?.duration
                                )
                            }
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

/**
 * MEDIDO 2026-10-03: habia DOS bucles `while(true){delay(500)}` identicos, uno en cada
 * componente de abajo, y el estado `cursorVisible` se leia en el cuerpo del padre: 2
 * recomposiciones por segundo de las tarjetas de herramienta enteras. El parpadeo vive
 * ahora en estas hojas: solo el glifo se recompone, nunca las tarjetas ni la columna.
 */
@Composable
private fun CursorParpadeanteCadena(): String {
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            visible = !visible
        }
    }
    return if (visible) " ▋" else ""
}

@Composable
private fun GlifoCursor() {
    Text(
        CursorParpadeanteCadena(),
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        color = Color(0xFF58A6FF),
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun TextoConCursorParpadeante(text: String) {
    MarkdownText(text = text, cursor = CursorParpadeanteCadena())
}

@Composable
private fun TerminalStreamingTurn(
    streamText: String,
    streamingTools: List<LiveToolExecution>
) {
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
                    GlifoCursor()
                }
            }
        } else {
            TextoConCursorParpadeante(streamText)
        }
    }
}

@Composable
private fun TerminalActivityCursor() {
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
        GlifoCursor()
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
    leerEnVoz: Boolean,
    onToggleLeerEnVoz: () -> Unit,
    agentMode: String = "build",
    // Antes `onToggleAgentMode`, un interruptor de dos. Ahora el boton ABRE una hoja con
    // los agentes que OpenCode publica de verdad (MEDIDO 2026-09-30: 40, no 2).
    onSelectAgentClick: () -> Unit = {},
    // Si el agente elegido NO es un subagent. Con la lista sin cargar no se sabe, y se
    // supone primary porque es el valor por defecto del propio OpenCode: ante la duda se
    // muestra el estado de partida, no un color inventado.
    agentIsPrimary: Boolean = true,
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

                    // Boton de agente: la letra es la inicial del nombre REAL
                    // (orchestrator -> O, build -> B, plan -> P, kaenor-ai-engineer -> K).
                    //
                    // El color pasa a codificar el `mode` que da OpenCode y no dos casos
                    // escritos a mano: azul = primary, verde azulado = subagent. Antes
                    // "plan" era mostaza; ahora es primary, o sea azul, y la letra "P" y la
                    // hoja siguen diciendo cual es. El color queda como pista y no como
                    // fuente: deducir el modo de un color obliga a recordar la regla.
                    val agentName = agentMode.trim()
                    val letter = agentName.take(1).uppercase().ifBlank { "?" }
                    val buttonBg = if (agentIsPrimary) Color(0xFF1E88E5) else Color(0xFF00897B)
                    val modeDesc = "Agente: $agentName. Toca para cambiar de agente"
                    Surface(
                        onClick = onSelectAgentClick,
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
                                color = Color.White
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
                        // MEDIDO 2026-10-01: aqui estaba el boton de auriculares, que abria
                        // la pantalla de voz. Se sustituye por el interruptor de LEER EN VOZ:
                        // el TTS antes solo se disparaba dentro del modo duplex, asi que
                        // quitar ese modo lo dejaba muerto. Conectado aqui: pulsado lee la
                        // respuesta; sin pulsar, callado y sin coste.
                        onClick = onToggleLeerEnVoz,
                        modifier = Modifier
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .semantics {
                                contentDescription = if (leerEnVoz) "Dejar de leer en voz alta"
                                else "Leer la respuesta en voz alta"
                            }
                    ) {
                        Icon(
                            if (leerEnVoz) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                            contentDescription = null,
                            tint = if (leerEnVoz) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // MEDIDO 2026-10-01: el microfono vivia en un `else if (listening)` /
                    // `else` de `if (canSend)`, asi que con un adjunto puesto NUNCA se pintaba
                    // —`canSend` era true y el microfono era la rama descartada. El boton que
                    // responde a "hay algo que enviar" no puede decidir si se ve el microfono.
                    // Aqui el microfono se pinta siempre y el enviar se anade a su lado.
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
                    }
                    if (listening) {
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
                    // El microfono ya no esta en un `else` de nada: termina aqui su rama, y el
                    // boton de voz (leer en voz alta) se pinta al lado, siempre.
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

private fun startListeningInternal(
    context: android.content.Context,
    current: SpeechRecognizer?,
    setRecognizer: (SpeechRecognizer?) -> Unit,
    setListening: (Boolean) -> Unit,
    setError: (String?) -> Unit,
    onResult: (String) -> Unit
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
        }
        override fun onResults(b: android.os.Bundle?) {
            setListening(false)
            val list = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = list?.firstOrNull()?.trim() ?: finalsBuf.trim()
            finalsBuf = ""
            if (text.isNotBlank()) onResult(text)
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

private fun isTechnicalSessionId(t: String?): Boolean = com.aegis.hub.util.esTituloTecnico(t)

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
/**
 * Tarjeta de permiso pendiente: el equivalente móvil del diálogo "Permission required"
 * del TUI del CLI. Antes no existía y por eso Aegis se quedaba "trabajando" para
 * siempre cuando una herramienta pedía permiso.
 *
 * Las tres decisiones son las de OpenCode 2.0.14 y cada una tiene un efecto secundario
 * que el usuario no adivina, así que se escribe en el propio botón en vez de dejarlo
 * implícito: "rechazar" también cancela el resto de permisos de la sesión, y "permitir
 * siempre" solo persiste si el servidor propuso reglas (si no, es idéntico a "una vez").
 */
@Composable
private fun PendingPermissionCard(
    permission: PendingPermission,
    busy: Boolean,
    onDecision: (String) -> Unit
) {
    val warn = Color(0xFFFFB74D)
    val danger = MaterialTheme.colorScheme.error
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = warn.copy(alpha = 0.12f)
            ),
            border = BorderStroke(1.dp, warn.copy(alpha = 0.5f))
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.Lock, contentDescription = "Permiso pendiente", tint = warn)
                    Text(
                        "Permiso requerido",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = warn
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    permission.explain,
                    style = MaterialTheme.typography.bodySmall
                )
                // El recurso concreto importa al decidir: "bash" con un comando legible
                // no es lo mismo que "edit" sobre un fichero que no se ve.
                permission.resources?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { res ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        res,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (busy) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Enviando decisión…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onDecision("once") },
                            colors = ButtonDefaults.buttonColors(containerColor = danger)
                        ) { Text("Rechazar") }
                        OutlinedButton(onClick = { onDecision("always") }) {
                            Text(if (permission.canPersist) "Siempre" else "Una vez")
                        }
                        Button(onClick = { onDecision("once") }) { Text("Permitir") }
                    }
                    if (!permission.canPersist) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Este permiso no admite recordarse: «Siempre» equivale a «Una vez».",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Rechazar también cancela los demás permisos de esta sesión.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

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
private fun isFinalResponseOf(messages: List<Message>, index: Int, turnFinished: Boolean): Boolean {
    val msg = messages[index]
    if (msg.role != "assistant") return false
    val next = messages.getOrNull(index + 1)
    // Si despues viene un mensaje del usuario, ese turno esta cerrado por historia:
    // el divisor se queda, es el cierre real de aquello.
    if (next != null) return next.role == "user"
    // Es el ULTIMO mensaje: aqui solo se marca si el agente ha parado de verdad
    // (session.execution.*). Antes se exigia `time.completed`, que se cumple tras cada
    // `bash` con exit 0 aunque siga trabajando, y por eso saltaba a mitad de turno.
    return turnFinished
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

private fun buildChatRows(messages: List<Message>, turnFinished: Boolean): List<ChatRow> = buildList {
    messages.forEachIndexed { index, msg ->
        add(ChatRow.Mensaje(index, msg))
        if (isFinalResponseOf(messages, index, turnFinished)) {
            add(ChatRow.Cierre(msg.info?.id ?: "idx_$index"))
        }
    }
}
