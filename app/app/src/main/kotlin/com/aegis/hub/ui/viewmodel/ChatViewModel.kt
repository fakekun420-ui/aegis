package com.aegis.hub.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.aegis.hub.data.FormReplyBody
import com.aegis.hub.data.InflightSession
import com.aegis.hub.data.PendingForm
import com.aegis.hub.data.FormOption
import com.aegis.hub.data.FormField
import com.aegis.hub.ui.TurnNotifier
import androidx.lifecycle.viewModelScope
import com.aegis.hub.data.ApiClient
import com.aegis.hub.data.AttachedFile
import com.aegis.hub.data.LiveToolExecution
import com.aegis.hub.data.Message
import com.aegis.hub.data.MessageDeliveryStatus
import com.aegis.hub.data.MessageInfo
import com.aegis.hub.data.MessagePart
import com.aegis.hub.data.ModelOption
import com.aegis.hub.data.SendMessageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType

class ChatViewModel : ViewModel() {
    private val api = ApiClient.service

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _models = MutableStateFlow<List<ModelOption>>(emptyList())
    val models: StateFlow<List<ModelOption>> = _models

    private val _modelsLoading = MutableStateFlow(false)
    val modelsLoading: StateFlow<Boolean> = _modelsLoading

    private val _selectedModel = MutableStateFlow<String?>("gemini-3.8-flash-high")
    val selectedModel: StateFlow<String?> = _selectedModel

    private val _selectedProvider = MutableStateFlow<String>("antigravity")
    val selectedProvider: StateFlow<String> = _selectedProvider

    // F6: sesión ya vinculada a un proveedor — el pill queda fijo para que
    // cambiar de motor en un chat abierto NO re-bindea ni "borra" la sesión.
    private val _sessionProviderBound = MutableStateFlow(false)
    val sessionProviderBound: StateFlow<Boolean> = _sessionProviderBound

    private val _sessionTitle = MutableStateFlow<String?>("Nuevo chat")
    val sessionTitle: StateFlow<String?> = _sessionTitle

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId

    private val _streamingText = MutableStateFlow<String?>(null)
    val streamingText: StateFlow<String?> = _streamingText

    private val _streamingTools = MutableStateFlow<List<LiveToolExecution>>(emptyList())
    val streamingTools: StateFlow<List<LiveToolExecution>> = _streamingTools

    private val _agentMode = MutableStateFlow<String>("build") // "plan" | "build"
    val agentMode: StateFlow<String> = _agentMode

    private var pollingJob: Job? = null

    // Refresco CONTINUO mientras el chat está abierto. Antes solo existía el poll de
    // envío (pollingJob), que se cancela al terminar el turno: mientras el usuario
    // merely miraba un chat no había ninguna actualización, y tenía que salir y
    // volver a entrar o enviar otro mensaje para ver cambios. Es un job aparte a
    // propósito, para no chocar con el poll de envío.
    private var viewRefreshJob: Job? = null
    private var viewRefreshSessionId: String? = null

    // Formularios / preguntas de herramientas pendientes de esta sesión. El TUI del
    // CLI los pintaba y solo se podían responder con flechas + Enter; desde Aegis no
    // había forma de verlos ni contestarlos. Se consultan en el mismo refresco y se
    // responden con un toque.
    // El turno del asistente sigue en curso: el último mensaje del asistente NO trae
    // `time.completed`. Permite pintar "trabajando en ello" mientras ocurre, y que el
    // divisor de "respuesta final" aparezca solo cuando de verdad termina.
    private val _turnInProgress = MutableStateFlow(false)
    val turnInProgress: StateFlow<Boolean> = _turnInProgress

    // Estado REAL de ejecucion, segun el vigilante del Hub (session.execution.*).
    // `_turnBusy` = hay un turno en marcha. `_turnOver` = ese turno acaba de terminar
    // de verdad, o sea que el agente no va a hacer nada mas hasta que le hables.
    //
    // Antes se deducía de `time.completed`, que cierra el MENSAJE: por eso el divisor
    // saltaba tras cada `bash` con exit 0. Ahora la señal es el evento de ejecución.
    // true SOLO mientras el POST sigue en vuelo. Distingue "se esta enviando" de
    // "el servidor ya lo acepto y el modelo esta trabajando": antes se encendia
    // _loading de golpe, asi que "Enviando..." y "Generando respuesta..." salian
    // juntos y no se podia saber en que fase estabas.
    private val _sendingInFlight = MutableStateFlow(false)
    val sendingInFlight: StateFlow<Boolean> = _sendingInFlight

    private val _turnBusy = MutableStateFlow(false)
    val turnBusy: StateFlow<Boolean> = _turnBusy
    private val _turnOver = MutableStateFlow(false)
    val turnOver: StateFlow<Boolean> = _turnOver

    private val _pendingForms = MutableStateFlow<List<PendingForm>>(emptyList())
    val pendingForms: StateFlow<List<PendingForm>> = _pendingForms
    private val _replyingForm = MutableStateFlow(false)
    val replyingForm: StateFlow<Boolean> = _replyingForm

    // Id del último mensaje del asistente cuyo turno ya se CERRÓ (info.time.completed).
    // El chat lo usa para dibujar el divisor de "respuesta final", de modo que se sabe
    // cuándo terminó de verdad y no solo cuando llegó el último trozo de texto.
    private val _finishedTurnId = MutableStateFlow<String?>(null)
    val finishedTurnId: StateFlow<String?> = _finishedTurnId

    // Cuando se cambia de motor hay que crear una sesión nueva (el proveedor vive en
    // el prefijo del id). Este estado lleva la id recién creada para que ChatScreen
    // navegue a ella; se consume una sola vez.
    private val _pendingSessionNav = MutableStateFlow<String?>(null)
    val pendingSessionNav: StateFlow<String?> = _pendingSessionNav

    fun consumePendingNav() { _pendingSessionNav.value = null }

    // FASE A-5 (anti doble envío): clave del envío actualmente en vuelo
    // ("proveedor|sesión|texto|nº archivos"). Si llega un segundo click o una
    // reentrada con el MISMO contenido antes de que termine el envío actual,
    // se ignora para no reenviar el mensaje final dos veces. Se limpia en el
    // finally del envío y en el retorno temprano de error.
    private var inFlightSendKey: String? = null

    fun setSessionTitle(title: String?) {
        if (!title.isNullOrBlank() && !isTechnicalTitle(title)) {
            _sessionTitle.value = title
        }
    }

    private fun isTechnicalTitle(t: String?): Boolean {
        if (t == null) return true
        val s = t.trim()
        if (s.isBlank()) return true
        if (s.startsWith("ses_") || s.startsWith("agy_") || s.startsWith("companion:") || s.startsWith("local_")) return true
        if (s.matches(Regex("^[0-9a-fA-F-]{8,}$"))) return true
        return false
    }

    private fun updateTitleFromFirstMessage(msgList: List<Message>) {
        if (_sessionTitle.value.isNullOrBlank() || isTechnicalTitle(_sessionTitle.value)) {
            val firstPrompt = msgList.firstOrNull { it.role == "user" && it.text.isNotBlank() }?.text?.trim()
            if (!firstPrompt.isNullOrBlank()) {
                val clean = firstPrompt.replace("\n", " ").trim()
                _sessionTitle.value = if (clean.length > 30) clean.take(30).trim() + "…" else clean
            }
        }
    }

    fun selectModel(modelId: String?) {
        _selectedModel.value = modelId
    }

    fun selectProvider(provider: String) {
        val p = provider.lowercase().trim()
        if (p == _selectedProvider.value) return

        // El proveedor NO es una etiqueta: el Hub lo deriva del prefijo del id de
        // sesión (agy_ -> antigravity, ses_ -> opencode, ver _conventionProvider en
        // providers.js). Por eso cambiar el chip en un chat existente NO podía
        // funcionar: un id agy_ se enruta SIEMPRE a antigravity, diga lo que diga la
        // app. La Sesión vive en el espacio de conversación de su motor.
        //
        // Así que cambiar de motor implica una sesión NUEVA en el motor destino. Si
        // el usuario elige otro, se crea y se navega a ella; el chat viejo queda
        // intacto. Esto era lo que faltaba cuando solo se desbloqueaba el chip: la
        // app dejaba cambiar y luego no llegaba ninguna respuesta.
        val sid = _currentSessionId.value.orEmpty()
        val sessionProvider = when {
            sid.startsWith("agy_") -> "antigravity"
            sid.startsWith("ses_") -> "opencode"
            else -> null
        }
        if (sessionProvider != null && sessionProvider != p) {
            viewModelScope.launch {
                _error.value = null
                val ns = createNewSession(p)
                if (ns.isNullOrBlank()) {
                    _error.value = "No se pudo crear un chat de $p."
                } else {
                    _pendingSessionNav.value = ns
                }
            }
            return
        }

        // Mismo motor (o sesión aún sin prefijo): aquí sí se puede recolocar, y solo
        // mientras no haya respuesta real — en cuanto el asistente contesta, el
        // historial pertenece a ese motor y se queda fijo.
        val tieneRespuestaReal = _messages.value.any {
            it.role == "assistant" && it.text.isNotBlank() && !it.text.trimStart().startsWith("⚠️")
        }
        if (_sessionProviderBound.value && tieneRespuestaReal) {
            _error.value = "El asistente ya respondió en este chat, así que el motor queda fijo. Para usar otro, crea un chat nuevo."
            return
        }
        _selectedProvider.value = p
        _sessionProviderBound.value = false
        loadModels(p)
    }

    fun toggleAgentMode() {
        _agentMode.value = if (_agentMode.value == "plan") "build" else "plan"
    }

    fun setAgentMode(mode: String) {
        _agentMode.value = if (mode.lowercase().trim() == "plan") "plan" else "build"
    }

    fun clearError() {
        _error.value = null
    }

    fun loadModels(provider: String? = null) {
        val prov = (provider ?: _selectedProvider.value).lowercase().trim().ifBlank { "antigravity" }
        viewModelScope.launch {
            _modelsLoading.value = true
            try {
                val resp = api.getModels(prov)
                if (resp.ok && resp.data != null && resp.data.isNotEmpty()) {
                    _models.value = resp.data
                    if (_selectedModel.value == null || _models.value.none { it.id == _selectedModel.value }) {
                        val defaultHigh = resp.data.find { it.id == "gemini-3.8-flash-high" }
                        _selectedModel.value = defaultHigh?.id ?: resp.data.first().id
                    }
                } else {
                    // F6: sin lista fiable NO se conserva la del proveedor anterior
                    // (antes se veían modelos ajenos/v1 bajo la pestaña de OpenCode).
                    _models.value = emptyList()
                }
            } catch (_: Exception) {
                _models.value = emptyList()
            } finally {
                _modelsLoading.value = false
            }
        }
    }

    /**
     * Refresco periódico mientras el chat está visible.
     *
     * Antes de este cambio la pantalla solo cargaba los mensajes UNA vez al abrir
     * (en [load]) y no volvía a preguntar nunca: cualquier respuesta que llegara
     * después —por el Hub, por el CLI o por otro cliente— no se veía hasta que el
     * usuario salía y reentraba, o enviaba otro mensaje.
     *
     * No pisa [_streamingText]: mientras hay texto en vivo del asistente la lista la
     * manda el propio stream, y un refresh de medio segundo antes podría hacer parpadear
     * la pantalla. Tampoco toca [_loading] ni [_error] para no tumbar la UI.
     */
    private fun startViewRefresh(sessionId: String) {
        viewRefreshJob?.cancel()
        viewRefreshSessionId = sessionId
        viewRefreshJob = viewModelScope.launch {
            var streamingSince = 0
            while (isActive) {
                delay(2000)
                if (_currentSessionId.value != sessionId) break
                // Durante un envío hay otro poll corriendo; no competimos con él.
                if (pollingJob?.isActive == true) continue
                // El stream manda mientras hay texto en vivo, pero con un tope: si el
                // stream se queda a medias y _streamingText no vuelve a vaciarse, este
                // `continue` convertiría el refresco en un no-op PERMANENTE. Pasados
                // 6s de texto vivo dejamos de refugiarnos en el stream.
                if (!_streamingText.value.isNullOrBlank() && streamingSince < 6) {
                    streamingSince++
                    continue
                }
                if (streamingSince > 0) streamingSince = 0
                // Estado de ejecucion (session.execution.*). Es lo que decide si el
                // turno ha terminado de verdad, asi que va PRIMERO. O(1): el Hub lo
                // tiene en memoria, no consulta a OpenCode.
                try {
                    val ex = api.getInflight()
                    if (ex.ok && ex.data != null) {
                        val sid = sessionId
                        // El vigilante manda UNA fila por sesion: busy si no ha
                        // terminado, turnOver si acaba de terminar.
                        val mine = ex.data.firstOrNull { it.id == sid }
                        _turnBusy.value = mine != null && !mine.turnOver
                        _turnOver.value = mine != null && mine.turnOver
                    }
                } catch (_: Exception) {
                }

                // Formularios pendientes. Va PRIMERO y en su propio try: un fallo al
                // listarlos no debe impedir refrescar la conversación, y el estado de
                // las preguntas tiene que estar fresco ANTES de juzgar si el turno
                // terminó, porque un agente esperando respuesta sigue trabajando.
                try {
                    val fr = api.getPendingForms(sessionId)
                    if (fr.ok && fr.data != null) _pendingForms.value = fr.data
                } catch (_: Exception) {
                    // Un fallo puntual de red no debe tumbar el refresco: el siguiente
                    // ciclo reintenta solo.
                }

                try {
                    val r = api.getMessages(sessionId)
                    if (r.ok && r.data != null) {
                        val fresh = r.data.filterNot { it.isEmpty }
                        if (fresh != _messages.value) {
                            _messages.value = fresh
                        }
                        // Se comprueba el cierre SIEMPRE, no solo cuando la lista cambia.
                        // El propio flujo de envío ya dejó _messages al día, así que en
                        // el primer poll tras el turno la lista es idéntica y el aviso
                        // se habría quedado sin disparar. announceFinishedTurnIfAny es
                        // idempotente: ignora el turno ya anunciado.
                        announceFinishedTurnIfAny(fresh)
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Detiene el refresco. [ownerSessionId] da propiedad del job: sin él, el
     * `onDispose` de la pantalla ANTERIOR puede ejecutarse DESPUÉS de que el
     * `LaunchedEffect` de la nueva ya arrancó el suyo, y lo mataba. Ese era el
     * motivo de que el chat no se sincronizara en tiempo real: el poll moría nada
     * más abrirse y nunca completaba un ciclo.
     */
    /**
     * Detecta que la IA ha CERRADO su turno y lo avisa.
     *
     * "Cerrado" = el último mensaje del asistente trae `time.completed`, que es la
     * marca que pone OpenCode cuando el TURNO termina. Ojo: `time.streamed` NO
     * sirve, esa solo indica que un segmento de texto dejó de crecer, y en un
     * turno agéntico ocurre varias veces antes de que el turno acabe.
     *
     * Si el turno terminó justo ahora:
     *  - se marca para que el chat dibuje el divisor de "respuesta final"
     *  - y se lanza notificación, pero solo si la app está en segundo plano
     *    (TurnNotifier lo comprueba con MainActivity.isForeground).
     */
    /**
     * ¿Ha terminado DE VERDAD el turno del asistente?
     *
     * `time.completed` por sí solo NO basta, y esa era la causa de los dos síntoma que
     * reportó el usuario (divisor saltando a mitad y "trabajando en ello" pegado al
     * divisor). Un mensaje del asistente puede llevar `completed` mientras el turno
     * sigue vivo, porque lo que se cierra es ESE mensaje, no el turno:
     *
     *  1. Hay una herramienta en estado `running` (p. ej. un `bash`): el agente aún
     *     espera su salida.
     *  2. Hay un formulario pendiente: el agente está esperando a la persona.
     */
    private fun turnIsReallyFinished(messages: List<Message>): Boolean {
        // El vigilante manda. Un formulario pendiente significa que el agente esta
        // esperando a la persona, asi que el turno NO ha terminado.
        if (_pendingForms.value.isNotEmpty()) return false
        if (_turnBusy.value) return false
        if (_turnOver.value) return true
        // Sin datos del vigilante todavia (arranque, o sesion de otro proveedor):
        // se cae a la heuristica anterior para no dejar la UI muda.
        val last = messages.lastOrNull { it.role == "assistant" } ?: return false
        if (last.info?.time?.containsKey("completed") != true) return false
        if (last.parts.orEmpty().any { it.state?.status == "running" }) return false
        return true
    }

    private fun announceFinishedTurnIfAny(fresh: List<Message>) {
        val lastAssistant = fresh.lastOrNull { it.role == "assistant" } ?: return
        val id = lastAssistant.info?.id ?: return
        val finished = turnIsReallyFinished(fresh)
        _turnInProgress.value = !finished
        if (!finished) return
        if (id == _finishedTurnId.value) return   // ya anunciado, no repetir cada 2 s
        _finishedTurnId.value = id

        val title = _sessionTitle.value?.takeIf { it.isNotBlank() } ?: "Aegis"
        TurnNotifier.notifyTurnFinished(
            title = "$title · respuesta final",
            preview = lastAssistant.text.take(160),
            sessionId = _currentSessionId.value
        )
    }

    /**
     * Responde un formulario pendiente.
     *
     * IMPORTANTE — por qué recibe el mapa COMPLETO y no una sola opción:
     * `POST /api/session/:id/form/:fid/reply` resuelve el formulario entero con lo que
     * le llegue. Comprobado contra un formulario real de 3 campos: enviando solo `q0`
     * respondió `{"replied":true}` y el formulario desapareció de la lista, con `q1` y
     * `q2` descartados en silencio. No hay accumulates ni "enviar parcial" que luego
     * se pueda completar: o mandas todas las preguntas o pierdes el resto.
     *
     * El cuerpo es `{ "answer": { "<clave del campo>": "<valor de la opción>" } }`. La
     * clave "answer" es obligatoria; el Hub la exige y devuelve 400 sin ella.
     */
    fun answerForm(form: PendingForm, answers: Map<String, String>) {
        val sid = _currentSessionId.value.orEmpty()
        val fid = form.id.orEmpty()
        if (sid.isBlank() || fid.isBlank()) {
            _error.value = "No se puede responder: formulario incompleto"
            return
        }
        if (answers.isEmpty()) {
            _error.value = "No hay respuestas que enviar"
            return
        }
        viewModelScope.launch {
            _replyingForm.value = true
            try {
                val resp = api.replyForm(sid, fid, FormReplyBody(answer = answers))
                if (resp.ok) {
                    _error.value = null
                    // Se quita de inmediato para que la UI no repita el botón; el
                    // siguiente refresco confirma que OpenCode ya no lo lista.
                    _pendingForms.value = _pendingForms.value.filterNot { it.id == fid }
                } else {
                    _error.value = resp.error?.message ?: resp.error?.code ?: "No se pudo enviar la respuesta"
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Error al enviar la respuesta"
            } finally {
                _replyingForm.value = false
            }
        }
    }

    fun stopViewRefresh(ownerSessionId: String? = null) {
        if (ownerSessionId != null && viewRefreshSessionId != ownerSessionId) return
        viewRefreshJob?.cancel()
        viewRefreshJob = null
        viewRefreshSessionId = null
    }

    override fun onCleared() {
        super.onCleared()
        stopViewRefresh()
    }

    fun load(sessionId: String, provider: String? = null) {
        pollingJob?.cancel()
        if (sessionId.isBlank()) {
            stopViewRefresh()
        } else if (viewRefreshSessionId != sessionId) {
            // startViewRefresh cancela el job anterior por su cuenta, así que recargar
            // la misma sesión tampoco deja el refresco muerto.
            startViewRefresh(sessionId)
        }
        val prov = (provider ?: if (sessionId.isBlank() || sessionId.startsWith("agy_")) "antigravity" else "opencode").lowercase().trim()
        _selectedProvider.value = prov
        // F6: sólo una sesión existente queda vinculada al proveedor de nacimiento;
        // los chats nuevos pueden cambiar libremente de motor.
        _sessionProviderBound.value = sessionId.isNotBlank()
        if (_selectedModel.value.isNullOrBlank()) {
            _selectedModel.value = "gemini-3.8-flash-high"
        }
        if (sessionId.isBlank()) {
            _sessionTitle.value = "Nuevo chat"
            loadModels(prov)
            return
        }
        _currentSessionId.value = sessionId
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            loadModels(prov)

            // Try to resolve human-readable title from sessions list
            try {
                val sessResp = api.getOpencodeSessions()
                if (sessResp.ok && sessResp.data != null) {
                    val found = sessResp.data.find { it.resolvedId == sessionId || it.id == sessionId || it.ID == sessionId }
                    if (found != null && !found.title.isNullOrBlank() && !isTechnicalTitle(found.title)) {
                        _sessionTitle.value = found.title
                    }
                }
            } catch (_: Exception) {}

            try {
                val resp = api.getMessages(sessionId)
                if (resp.ok && resp.data != null) {
                    val nonEmpties = resp.data.filterNot { it.isEmpty }
                    _messages.value = nonEmpties
                    updateTitleFromFirstMessage(nonEmpties)
                } else if (!resp.ok) {
                    _error.value = resp.error?.message ?: resp.error?.code ?: "Error al obtener mensajes"
                }
            } catch (e: Exception) {
                _error.value = e.localizedMessage ?: e.message ?: "Error de conexión con el servidor"
            } finally {
                _loading.value = false
            }
        }
    }

    fun send(sessionId: String, text: String, provider: String? = null) {
        sendWithFiles(sessionId, text, emptyList(), provider)
    }

    fun retryMessage(failedMsg: Message, sessionId: String) {
        val text = failedMsg.text
        val files = failedMsg.fileParts().mapNotNull { fp ->
            if (fp.filename != null) {
                AttachedFile(
                    name = fp.filename,
                    size = fp.data?.length?.toLong() ?: 0L,
                    mime = fp.mime ?: "text/plain",
                    text = fp.data,
                    base64 = if (fp.url?.startsWith("data:") == true) fp.url.substringAfter("base64,") else null
                )
            } else null
        }
        _messages.value = _messages.value.filterNot { it.info?.id == failedMsg.info?.id }
        sendWithFiles(sessionId, text, files)
    }

    fun sendWithFiles(
        sessionId: String,
        text: String,
        files: List<AttachedFile>,
        explicitProvider: String? = null
    ) {
        if (text.isBlank() && files.isEmpty()) return

        val provider = (explicitProvider ?: _selectedProvider.value).lowercase().trim().ifBlank { "antigravity" }
        val tempMsgId = "local_${System.currentTimeMillis()}"

        // FASE A-5: guarda anti doble envío — un segundo click/reentrada con el mismo
        // mensaje mientras sigue en vuelo no debe reenviarlo (ver inFlightSendKey).
        val sendKey = "$provider|${sessionId.trim()}|${text.trim()}|${files.size}"
        if (sendKey == inFlightSendKey) return
        inFlightSendKey = sendKey

        if (text.isNotBlank() && (_sessionTitle.value.isNullOrBlank() || isTechnicalTitle(_sessionTitle.value))) {
            val clean = text.replace("\n", " ").trim()
            _sessionTitle.value = if (clean.length > 30) clean.take(30).trim() + "…" else clean
        }

        viewModelScope.launch {
            _loading.value = true
            _error.value = null

            // 1. Optimistic user message with PENDING status
            val optimisticParts = mutableListOf<MessagePart>()
            if (text.isNotBlank()) {
                optimisticParts += MessagePart(type = "text", text = text)
            }
            for (f in files) {
                when {
                    f.text != null -> optimisticParts += MessagePart(type = "text", text = "Archivo ${f.name} (${f.mime}):\n```\n${f.text.take(30000)}\n```", filename = f.name, mime = f.mime)
                    f.base64 != null -> optimisticParts += MessagePart(type = "file", filename = f.name, mime = f.mime, url = "data:${f.mime};base64,${f.base64}")
                }
            }
            val optimistic = Message(
                info = MessageInfo(id = tempMsgId, role = "user", status = MessageDeliveryStatus.PENDING),
                parts = optimisticParts
            )
            _messages.value = _messages.value + optimistic

            // 2. Resolve Target Session ID
            val activeSessionId = sessionId.ifBlank { _currentSessionId.value ?: "" }
            val targetSessionId = if (activeSessionId.isBlank()) createNewSession(provider) else activeSessionId
            if (targetSessionId == null) {
                _error.value = "No se pudo crear o resolver la sesión en el servidor"
                _messages.value = _messages.value.map {
                    if (it.info?.id == tempMsgId) it.withStatus(MessageDeliveryStatus.ERROR) else it
                }
                _loading.value = false
                inFlightSendKey = null // A-5: libera la guarda anti doble envío en el retorno temprano
                return@launch
            }
            _currentSessionId.value = targetSessionId

            // 3. Prepare payload parts
            val reqParts = mutableListOf<Map<String, String>>()
            if (text.isNotBlank()) reqParts += mapOf("type" to "text", "text" to text)
            for (f in files) {
                when {
                    f.text != null -> reqParts += mapOf("type" to "text", "text" to "Archivo ${f.name} (${f.mime}):\n```\n${f.text.take(30000)}\n```")
                    f.base64 != null -> {
                        val dataUri = "data:${f.mime};base64,${f.base64}"
                        reqParts += mapOf("type" to "file", "mime" to f.mime, "filename" to f.name, "url" to dataUri)
                    }
                }
            }

            val countBefore = _messages.value.size
            var messageDelivered = false

            // Baseline para detectar un mensaje del asistente REALMENTE NUEVO.
            // Antes se comprobaba `nonEmpties.any { it.role == "assistant" }`, que en un
            // chat con historial es SIEMPRE cierto: el poll rompía en el primer intento
            // (1.5s), marcaba messageDelivered=true y la UI dejaba de actualizarse
            // mientras el modelo seguía trabajando (respuesta congelada / no se
            // mantenía en la última). Ahora se compara el id del último asistente.
            val lastAssistantIdBefore =
                _messages.value.lastOrNull { it.role == "assistant" && !it.isEmpty }?.info?.id

            // 4. Start active background polling in parallel to catch assistant output or SSE stream completions
            pollingJob?.cancel()
            pollingJob = launch {
                // Poll cada 1.5s hasta ~600s, alineado con AEGIS_TURN_TIMEOUT_MS del Hub
                // (antes 50 intentos = 75s, muy corto para un turno agéntico con tool calls).
                for (attempt in 1..400) {
                    delay(1500)
                    if (!isActive || messageDelivered) break
                    try {
                        val pollResp = api.getMessages(targetSessionId)
                        if (pollResp.ok && pollResp.data != null) {
                            val nonEmpties = pollResp.data.filterNot { it.isEmpty }
                            // Un asistente NUEVO: último assistant con id distinto al baseline.
                            val lastAssistant = nonEmpties.lastOrNull { it.role == "assistant" && !it.isEmpty }
                            val newAssistantArrived = lastAssistant != null &&
                                lastAssistant.info?.id != null &&
                                lastAssistant.info.id != lastAssistantIdBefore
                            if (newAssistantArrived && nonEmpties.size >= countBefore) {
                                _messages.value = nonEmpties
                                _loading.value = false
                                messageDelivered = true
                                break
                            }
                            // Aunque el turno siga en curso, refresca la lista para que el
                            // texto parcial del asistente se vea en vivo.
                            if (nonEmpties.size >= countBefore) {
                                _messages.value = nonEmpties
                            }
                        }
                    } catch (_: Exception) { }
                }
            }

            // 5. Send message with SSE real-time token streaming
            try {
                val currentAgentMode = _agentMode.value
                val currentModel = _selectedModel.value ?: "gemini-3.8-flash-high"
                val sendReq = SendMessageRequest(
                    parts = reqParts,
                    model = currentModel,
                    provider = provider,
                    agent = currentAgentMode,
                    mode = currentAgentMode
                )
                val bodyJson = com.google.gson.Gson().toJson(sendReq)

                var sseSuccess = false
                // FASE A-5: true cuando el POST del streaming ya obtuvo respuesta HTTP del
                // servidor. En ese caso la vía clásica de respaldo NO debe reenviar el
                // mensaje final (antes: una excepción en L402 reenviaba en L410 = doble envío).
                var sseRequestAccepted = false
                var sseResp: okhttp3.Response? = null
                _streamingText.value = ""
                _streamingTools.value = emptyList()

                _sendingInFlight.value = true
                try {
                    val streamReq = okhttp3.Request.Builder()
                        .url("http://127.0.0.1:8765/api/opencode/sessions/$targetSessionId/message?stream=true")
                        .header("Accept", "text/event-stream")
                        .header("X-Provider", provider)
                        .header("X-Model", currentModel)
                        .header("X-Agent", currentAgentMode)
                        .header("X-Mode", currentAgentMode)
                        .post(okhttp3.RequestBody.create("application/json".toMediaType(), bodyJson))
                        .build()

                    sseResp = withContext(Dispatchers.IO) { ApiClient.rawOkHttp.newCall(streamReq).execute() }
                    val resp = sseResp
                    sseRequestAccepted = resp?.isSuccessful == true

                    // ---- CONFIRMACION DE RECEPCION ----
                    // El POST ha vuelto: el servidor tiene el mensaje. A partir de aqui
                    // "Enviando..." desaparece y empieza "Generando respuesta...". Antes
                    // el mensaje seguia en PENDING hasta que terminaba TODO el stream, que
                    // puede tardar minutos, y no habia forma de saber si lo habia
                    // recibido o no.
                    _sendingInFlight.value = false
                    if (sseRequestAccepted) {
                        _messages.value = _messages.value.map {
                            if (it.info?.id == tempMsgId) it.withStatus(MessageDeliveryStatus.SENT) else it
                        }
                    } else {
                        // El servidor RECHAZO el mensaje. Se pinta el motivo real (cuota
                        // agotada, quota, error de proveedor...) en vez de un generico.
                        val crudo = try { resp?.errorBody()?.string() } catch (_: Exception) { null }
                        val motivo = parseDeliveryError(crudo)
                        _error.value = motivo
                        _messages.value = _messages.value.map {
                            if (it.info?.id == tempMsgId) it.withStatus(MessageDeliveryStatus.ERROR) else it
                        }
                    }
                    if (resp != null && resp.isSuccessful && resp.body != null) {
                        val reader = resp.body!!.charStream().buffered()
                        val sb = java.lang.StringBuilder()
                        var line: String? = null
                        while (withContext(Dispatchers.IO) { reader.readLine() }.also { line = it } != null) {
                            val cur = line ?: break
                            if (cur.startsWith("data: ")) {
                                val dataStr = cur.removePrefix("data: ").trim()
                                try {
                                    val jsonObj = com.google.gson.JsonParser.parseString(dataStr).asJsonObject
                                    val type = if (jsonObj.has("type")) jsonObj.get("type").asString else ""
                                    when (type) {
                                        "chunk" -> {
                                            if (jsonObj.has("text")) {
                                                val chunk = jsonObj.get("text").asString
                                                sb.append(chunk)
                                                _streamingText.value = sb.toString()
                                            }
                                        }
                                        "tool_start" -> {
                                            val rawTool = if (jsonObj.has("tool")) jsonObj.get("tool").asString else "bash"
                                            val tool = if (rawTool == "run_command") "bash" else rawTool
                                            val callId = if (jsonObj.has("callID")) jsonObj.get("callID").asString else "tool_${System.currentTimeMillis()}"
                                            val inputObj = if (jsonObj.has("input") && jsonObj.get("input").isJsonObject) jsonObj.getAsJsonObject("input") else null
                                            val cmd = inputObj?.let {
                                                (if (it.has("CommandLine")) it.get("CommandLine").asString else null)
                                                    ?: (if (it.has("command")) it.get("command").asString else null)
                                                    ?: (if (it.has("cmd")) it.get("cmd").asString else null)
                                                    ?: (if (it.has("path")) it.get("path").asString else null)
                                                    ?: (if (it.has("AbsolutePath")) it.get("AbsolutePath").asString else null)
                                                    ?: (if (it.has("TargetFile")) it.get("TargetFile").asString else null)
                                                    ?: (if (it.has("query")) it.get("query").asString else null)
                                                    ?: (if (it.has("Url")) it.get("Url").asString else null)
                                            } ?: ""
                                            val cleanCmd = cmd.trim().removeSurrounding("\"")
                                            val newExec = LiveToolExecution(
                                                id = callId,
                                                tool = tool,
                                                command = cleanCmd,
                                                status = "running"
                                            )
                                            _streamingTools.value = _streamingTools.value.filterNot { it.id == callId } + newExec
                                        }
                                        "tool_done" -> {
                                            val rawTool = if (jsonObj.has("tool")) jsonObj.get("tool").asString else "bash"
                                            val tool = if (rawTool == "run_command") "bash" else rawTool
                                            val callId = if (jsonObj.has("callID")) jsonObj.get("callID").asString else ""
                                            val output = if (jsonObj.has("output")) jsonObj.get("output").asString else ""
                                            val exitCode = if (jsonObj.has("exitCode")) jsonObj.get("exitCode").asInt else 0
                                            val duration = if (jsonObj.has("duration")) jsonObj.get("duration").asDouble else null
                                            val inputObj = if (jsonObj.has("input") && jsonObj.get("input").isJsonObject) jsonObj.getAsJsonObject("input") else null
                                            val cmd = inputObj?.let {
                                                (if (it.has("CommandLine")) it.get("CommandLine").asString else null)
                                                    ?: (if (it.has("command")) it.get("command").asString else null)
                                            } ?: ""
                                            val cleanCmd = cmd.trim().removeSurrounding("\"")
                                            _streamingTools.value = _streamingTools.value.map {
                                                if (it.id == callId || (it.tool == tool && it.status == "running")) {
                                                    it.copy(
                                                        status = if (exitCode == 0) "completed" else "error",
                                                        output = output,
                                                        exitCode = exitCode,
                                                        duration = duration,
                                                        command = if (cleanCmd.isNotBlank()) cleanCmd else it.command
                                                    )
                                                } else it
                                            }
                                        }
                                        "done" -> {
                                            val msgObj = jsonObj.getAsJsonObject("message")
                                            val finalMsg = com.google.gson.Gson().fromJson(msgObj, Message::class.java)
                                            if (finalMsg != null && !finalMsg.isEmpty) {
                                                messageDelivered = true
                                                pollingJob?.cancel()
                                                val updated = _messages.value.map {
                                                    if (it.info?.id == tempMsgId) it.withStatus(MessageDeliveryStatus.SENT) else it
                                                }
                                                val exists = updated.any { it.info?.id == finalMsg.info?.id }
                                                _messages.value = if (exists) updated else updated + finalMsg
                                                sseSuccess = true
                                            }
                                            break
                                        }
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    }
                } catch (_: Exception) {
                    sseSuccess = false
                } finally {
                    // FASE A-5: cerrar SIEMPRE la respuesta SSE. Sin esto, el ResponseBody
                    // (y su socket/file descriptor de OkHttp) queda abierto y se fuga en
                    // cada envío con streaming.
                    try { sseResp?.close() } catch (_: Exception) {}
                    _streamingText.value = null
                    _streamingTools.value = emptyList()
                }

                // A-5: solo se reenvía por la vía clásica si el streaming NUNCA llegó a
                // entregar la petición al servidor (2xx) y además no hay mensaje final.
                // Si el POST del streaming ya fue aceptado, reenviar duplicaba el mensaje.
                if (!sseSuccess && !messageDelivered && !sseRequestAccepted) {
                    val responseMsg = api.sendMessage(
                        sessionId = targetSessionId,
                        body = sendReq,
                        provider = provider
                    )

                    // If responseMsg arrived directly with assistant parts
                    if (!responseMsg.isEmpty) {
                        messageDelivered = true
                        pollingJob?.cancel()

                        // Mark user message as SENT and append response
                        val updated = _messages.value.map {
                            if (it.info?.id == tempMsgId) it.withStatus(MessageDeliveryStatus.SENT) else it
                        }
                        val exists = updated.any { it.info?.id == responseMsg.info?.id }
                        _messages.value = if (exists) updated else updated + responseMsg
                        _loading.value = false
                    }
                }

                // Follow-up sync to get canonical messages from DB
                delay(400)
                val syncResp = api.getMessages(targetSessionId)
                if (syncResp.ok && syncResp.data != null) {
                    val synced = syncResp.data.filterNot { it.isEmpty }
                    if (synced.isNotEmpty()) {
                        _messages.value = synced
                        // El aviso de "respuesta final" SOLO se llamaba desde el poll de
                        // refresco, y ese poll se salta mientras hay un envío activo
                        // (`pollingJob?.isActive == true` -> continue). O sea que el
                        // aviso no se disparaba NUNCA al enviar desde la app, que es
                        // justo cuando tiene que pasar. Se llama también aquí, al
                        // cerrar el turno del propio envío.
                        announceFinishedTurnIfAny(synced)
                    }
                }
            } catch (e: Exception) {
                // Check if background polling already retrieved the response
                // ¿El poll trajo ya la respuesta? Debe ser un asistente NUEVO (id distinto
                // al baseline). Antes `any { ...id != tempMsgId }` daba true en cuanto el
                // chat tenía historial, así que nunca se marcaba ERROR y la píldora
                // "Enviando..." se quedaba cargando indefinidamente.
                val lastAssistantNow = _messages.value.lastOrNull { it.role == "assistant" && !it.isEmpty }
                val hasAssistantNow = lastAssistantNow != null &&
                    lastAssistantNow.info?.id != null &&
                    lastAssistantNow.info.id != lastAssistantIdBefore
                if (!hasAssistantNow && !messageDelivered) {
                    // Try one last fast getMessages attempt before declaring failure
                    try {
                        delay(600)
                        val lastTry = api.getMessages(targetSessionId)
                        if (lastTry.ok && lastTry.data != null) {
                            val list = lastTry.data.filterNot { it.isEmpty }
                            // Mismo criterio que el poll: asistente NUEVO, no "existe alguno"
                            val lastA = list.lastOrNull { it.role == "assistant" }
                            if (lastA != null && lastA.info?.id != null && lastA.info.id != lastAssistantIdBefore) {
                                _messages.value = list
                                _loading.value = false
                                return@launch
                            }
                        }
                    } catch (_: Exception) { }

                    _messages.value = _messages.value.map {
                        if (it.info?.id == tempMsgId) it.withStatus(MessageDeliveryStatus.ERROR) else it
                    }
                    _error.value = "Error al enviar mensaje: ${e.localizedMessage ?: e.message ?: "Tiempo de espera agotado"}"
                    _messages.value = _messages.value.map {
                        if (it.info?.id == tempMsgId) it.withStatus(MessageDeliveryStatus.ERROR) else it
                    }
                }
            } finally {
                _streamingText.value = null
                _loading.value = false
                // Red de seguridad: si el POST semurio por una excepcion antes de
                // tocar el flag, "Enviando..." se quedaria colgado para siempre.
                _sendingInFlight.value = false
                pollingJob?.cancel()
                inFlightSendKey = null // A-5: libera la guarda anti doble envío al terminar
            }
        }
    }

    suspend fun createVoiceSession(provider: String = "antigravity"): String? = createNewSession(provider)

    private suspend fun createNewSession(provider: String = "antigravity"): String? = withContext(Dispatchers.IO) {
        try {
            val title = "Nuevo chat"
            val bodyJson = "{\"title\":\"${title.replace("\"", "\\\"")}\",\"provider\":\"$provider\",\"model\":\"gemini-3.8-flash-high\"}"
            val req = okhttp3.Request.Builder()
                .url("http://127.0.0.1:8765/opencode/session")
                .header("X-Provider", provider)
                .post(okhttp3.RequestBody.create("application/json".toMediaType(), bodyJson))
                .build()
            val resp = ApiClient.rawOkHttp.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext null
            val j = com.google.gson.JsonParser.parseString(body).asJsonObject
            when {
                j.has("id") -> j.get("id").asString
                j.has("ID") -> j.get("ID").asString
                j.has("data") -> {
                    val d = j.getAsJsonObject("data")
                    when {
                        d.has("id") -> d.get("id").asString
                        d.has("ID") -> d.get("ID").asString
                        else -> null
                    }
                }
                else -> null
            }
        } catch (_: Exception) { null }
    }
}

    /**
     * Saca el motivo real de un cuerpo de error del Hub.
     *
     * El Hub responde `{ok:false, error:{code, message}}` y providers.js YA mete ahi
     * el motivo accionable ("Antigravity no tiene cuota disponible (cuota agotada)",
     * o el final de stderr de agy). Antes la app se tragaba el cuerpo y pintaba un
     * error generico, losing justo la informacion que dice si fue cuota, 429 o token.
     */
    private fun parseDeliveryError(crudo: String?): String {
        if (crudo.isNullOrBlank()) return "El servidor no acepto el mensaje"
        val txt = crudo.trim()
        return try {
            val env = org.json.JSONObject(txt)
            val err = env.optJSONObject("error")
            val msg = err?.optString("message")?.takeIf { it.isNotBlank() }
                ?: env.optString("message").takeIf { it.isNotBlank() }
            val code = err?.optString("code")?.takeIf { it.isNotBlank() }
            when {
                msg != null && code != null -> "$code: $msg"
                msg != null -> msg
                else -> txt.take(300)
            }
        } catch (_: Exception) {
            txt.take(300)
        }
    }
