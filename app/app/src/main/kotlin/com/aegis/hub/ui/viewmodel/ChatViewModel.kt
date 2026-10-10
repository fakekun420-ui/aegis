package com.aegis.hub.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.aegis.hub.data.FormReplyBody
import com.aegis.hub.data.ErroresRed
import com.aegis.hub.data.repo.NuevaSesion
import com.aegis.hub.data.repo.Resultado
import com.aegis.hub.data.repo.ConfigSesion
import com.aegis.hub.data.repo.SesionConfigRepo
import com.aegis.hub.data.repo.SesionesRepo
import com.aegis.hub.data.sync.ChatSync
import com.aegis.hub.data.sync.EstadoTurno
import com.aegis.hub.data.sync.EventosServidor
import com.aegis.hub.data.AppContext
import com.aegis.hub.data.InflightSession
import com.aegis.hub.data.ModelPreferences
import com.aegis.hub.data.PendingForm
import com.aegis.hub.data.PartFull
import com.aegis.hub.data.PendingPermission
import com.aegis.hub.data.PermissionReplyBody
import com.aegis.hub.data.FormOption
import com.aegis.hub.data.FormField
import com.aegis.hub.ui.TurnNotifier
import androidx.lifecycle.viewModelScope
import com.aegis.hub.data.Conexion
import com.aegis.hub.data.AttachedFile
import com.aegis.hub.data.LiveToolExecution
import com.aegis.hub.data.Message
import com.aegis.hub.data.MessageDeliveryStatus
import com.aegis.hub.data.MessageInfo
import com.aegis.hub.data.MessagePart
import android.util.Log
import com.aegis.hub.data.ModeloElegible
import com.aegis.hub.data.Agente
import com.aegis.hub.data.seleccionables
import com.aegis.hub.data.modeloPorDefecto
import com.aegis.hub.data.ModelosUtil
import com.aegis.hub.data.MarcasCiclo
import com.aegis.hub.data.modeloPorDefectoPara
import com.aegis.hub.data.SendMessageRequest
import com.aegis.hub.data.EventStream
import com.aegis.hub.data.OpenCodeStreamItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.aegis.hub.data.TurnState

/**
 * Agente con el que arranca un chat que no sabe cual usar. "build" desde 2026-10-07:
 * "orchestrator" era el valor medido el 2026-09-30 (único primario con modelo propio),
 * pero desapareció en la migración ECC del 2026-10-06 (F2 recortó `agent` a solo `build`)
 * y los chats nuevos fallaban con `AgentNotFoundError`. "build" es además el
 * `default_agent` del servidor, así que el chat usa el mismo agente que el resto del sistema.
 */
private const val AGENTE_POR_DEFECTO = "build"

class ChatViewModel(
    private val configRepo: SesionConfigRepo = SesionConfigRepo(),
    private val sesionesRepo: SesionesRepo = SesionesRepo()
) : ViewModel() {
    companion object {
        /**
         * F5: canal unico de sincronizacion por eventos. `false` = bucle actual
         * (`SyncPorPoll` intacto). Solo se pone `true` tras V-06/V-08 tres dias.
         */
        const val SYNC_POR_EVENTOS = false
    }
    /**
     * MEDIDO 2026-10-01: esta era `ApiClient.service`, el cliente del Hub, en las 18 llamadas
     * de este fichero. Ahora sale de [Conexion], que decide entre Hub y OpenCode nativo.
     *
     * **Esta es la costura, y por eso el cambio es de UNA LINEA.** `RutaNativa` implementa la
     * misma interfaz `ApiService` que ya tenía inyectada, así que las 18 llamadas siguen
     * compilando igual: no hay que tocar ninguna. Lo que cambia no es la lista de llamadas,
     * es a quién se las hacen.
     *
     * Con `Conexion.NATIVO_DIRECTO = false` (el valor actual) esto sigue siendo exactamente el
     * cliente del Hub de siempre. Ver la nota de por qué el valor por defecto NO es el nativo.
     */
    private val api = Conexion.api
    /** F3: config (modelo/agente) por el repo; `sesionesRepo` para crear. */
    private val sesiones = sesionesRepo

    /** F5: un solo canal (tras flag; apagado = bucle actual). */
    private val chatSync = ChatSync(
        viewModelScope,
        EventosServidor.Compartida.servidor,
        leerCola = { sid -> api.getMessagesTail(sid, 200).data.orEmpty() },
        leerFormularios = { sid -> api.getPendingForms(sid).data.orEmpty() },
        leerPermisos = { sid -> api.getPendingPermissions(sid).data.orEmpty() },
        leerOcupados = {
            api.getInflight().data.orEmpty()
                .filter { TurnState.isBusy(it) }.mapNotNull { it.id }.toSet()
        }
    )
    private var espejoJob: Job? = null

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _models = MutableStateFlow<List<ModeloElegible>>(emptyList())
    val models: StateFlow<List<ModeloElegible>> = _models

    private val _modelsLoading = MutableStateFlow(false)
    val modelsLoading: StateFlow<Boolean> = _modelsLoading

    // SIN id fijo. Antes arrancaba en "gemini-3.8-flash-high", que no existe en el
    // catalogo de OpenCode, asi que la ventana de chat lo ensegnaba y lo mandaba en
    // cada turno. Empieza en null y lo rellena [loadModels] con el primer free REAL
    // de la lista; si la lista no ha llegado todavia, se envia sin modelo y decide
    // OpenCode.
    private val _selectedModel = MutableStateFlow<String?>(null)
    val selectedModel: StateFlow<String?> = _selectedModel

    // F1: motor unico (OpenCode). El estado de "proveedor elegido" y de "sesion
    // vinculada a proveedor" (Antigravity) se elimino: solo ses_ existe.

    private val _sessionTitle = MutableStateFlow<String?>("Nuevo chat")
    val sessionTitle: StateFlow<String?> = _sessionTitle

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId

    // Si el Hub se ha ido. El refresco sigue tragandose los fallos a proposito (un
    // fallo puntual de red no debe tumbar el refresco: el siguiente ciclo reintenta
    // solo), pero tragar SIN CONTAR hacia que la pantalla mneta: se quedaba con el
    // estado viejo y un 429/502 no dejaba ni una pista, de modo que "Trabajando en
    // ello" podia quedarse pegado para un turno que ya habia acabado. Con el
    // contador, la app puede distinguir "un fallo" de "el Hub no esta" y decirlo.
    private val _servidorAlcanzable = MutableStateFlow(true)
    val servidorAlcanzable: StateFlow<Boolean> = _servidorAlcanzable
    private var refreshFailStreak = 0

    /**
     * Un ciclo del refresco salio bien -> el Hub responde.
     * Uno fallo -> se cuenta; a partir de [SERVIDOR_FAIL_STREAK_FOR_DEGRADED] se dice.
     *
     * El umbral son 3 ciclos (6 s) y no 1 a proposito: el criterio del codigo es que
     * un fallo puntual se ignora, y eso se respeta. Lo que se cambia no es tragarse
     * el fallo, es que tragarselo tenga un final.
     */
    private fun noteRefreshResult(ok: Boolean) {
        if (ok) {
            if (refreshFailStreak != 0) {
                refreshFailStreak = 0
                _servidorAlcanzable.value = true
            }
            return
        }
        refreshFailStreak++
        if (refreshFailStreak >= SERVIDOR_FAIL_STREAK_FOR_DEGRADED) _servidorAlcanzable.value = false
    }

    private val _streamingText = MutableStateFlow<String?>(null)
    val streamingText: StateFlow<String?> = _streamingText

    private val _streamingTools = MutableStateFlow<List<LiveToolExecution>>(emptyList())
    val streamingTools: StateFlow<List<LiveToolExecution>> = _streamingTools

    // El nombre del agente activo. Antes era "plan" | "build" y nada mas: un interruptor
    // de dos, mientras OpenCode publica 40 agentes (MEDIDO 2026-09-30). Se mantiene como
    // String y no como enum a proposito, porque el valor NO lo elegimos nosotros: es el
    // nombre exacto que OpenCode tiene, y una lista cerrada aqui se quedaria vieja en
    // cuanto se anadiera un cargo.
    // El default es ORCHESTRATOR, no build. MEDIDO 2026-09-30: de los 3 agentes que
    // OpenCode deja elegir (Build, Plan, orchestrator), `orchestrator` es el unico con
    // modelo propio y el unico que delega en los cargos. Con "build" por defecto, escribir
    // desde la app mandaba un agente distinto al que se esta trabajando en el resto del
    // sistema, que es justo el efecto que el usuario quiere evitar: cambiar parametros y
    // configuracion de Kaenor Inc. por escribir un mensaje.
    //
    // Esto solo aplica a lo que NO se sabe. Una sesion que ya tiene agente conserva el
    // suyo —OpenCode primero, luego la copia local—, porque el requisito es que cada
    // sesion guarde el agente que uso hasta que se cambie a mano.
    private val _agentMode = MutableStateFlow<String>(AGENTE_POR_DEFECTO)
    val agentMode: StateFlow<String> = _agentMode

    // Los agentes reales, tal cual. Sin lista no hay selector: se muestran los 6 primary
    // y se dice, en vez de fingir que la lista esta completa.
    private val _agents = MutableStateFlow<List<Agente>>(emptyList())
    val agents: StateFlow<List<Agente>> = _agents

    private val _agentsLoading = MutableStateFlow(false)
    val agentsLoading: StateFlow<Boolean> = _agentsLoading

    private var pollingJob: Job? = null
    // G1: ticker de reposo (una marca cada 60 s con chat abierto y sin turno).
    // Se recrea en cada `load` y muere con el cambio de sesion.
    private var repoJob: Job? = null

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
    // La MISMA decisión que alimenta la notificación, expuesta para el divisor.
    //
    // Antes el divisor leía el `turnOver` CRUDO del vigilante, y la notificación leía
    // `turnIsReallyFinished()`, que además tiene respaldo por los mensajes. Eran dos
    // señales distintas y divergían: la notificación saltaba tras cada `bash` (el
    // respaldo daba el turno por terminado) mientras el divisor no aparecía (turnOver
    // en false). Ahora hay UNA sola decisión y los dos la consumen.
    private val _turnFinished = MutableStateFlow(false)
    val turnFinished: StateFlow<Boolean> = _turnFinished

    private val _pendingForms = MutableStateFlow<List<PendingForm>>(emptyList())
    val pendingForms: StateFlow<List<PendingForm>> = _pendingForms
    private val _pendingPermissions = MutableStateFlow<List<PendingPermission>>(emptyList())
    val pendingPermissions: StateFlow<List<PendingPermission>> = _pendingPermissions
    private val _replyingForm = MutableStateFlow(false)
    val replyingForm: StateFlow<Boolean> = _replyingForm
    private val _replyingPermission = MutableStateFlow(false)
    val replyingPermission: StateFlow<Boolean> = _replyingPermission

    // Id del último mensaje del asistente cuyo turno ya se CERRÓ (info.time.completed).
    // El chat lo usa para dibujar el divisor de "respuesta final", de modo que se sabe
    // cuándo terminó de verdad y no solo cuando llegó el último trozo de texto.
    private val _finishedTurnId = MutableStateFlow<String?>(null)
    val finishedTurnId: StateFlow<String?> = _finishedTurnId

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

    private fun isTechnicalTitle(t: String?): Boolean = com.aegis.hub.util.esTituloTecnico(t)

    private fun updateTitleFromFirstMessage(msgList: List<Message>) {
        if (_sessionTitle.value.isNullOrBlank() || isTechnicalTitle(_sessionTitle.value)) {
            val firstPrompt = msgList.firstOrNull { it.role == "user" && it.text.isNotBlank() }?.text?.trim()
            if (!firstPrompt.isNullOrBlank()) {
                val clean = firstPrompt.replace("\n", " ").trim()
                _sessionTitle.value = if (clean.length > 30) clean.take(30).trim() + "…" else clean
            }
        }
    }

    /**
     * Recupera el modelo de una sesión, en orden de autoridad decreciente.
     *
     * 1. **El servidor.** OpenCode guarda el modelo de cada sesión en su objeto de sesión
     *    (`model = {id, providerID, variant}`) y ahí escribe TANTO la app como el CLI.
     *    Es la única fuente que cubre el requisito completo: "cada sesión recuerda su
     *    modelo hasta que se cambie a mano, desde el CLI o desde Aegis". La app nunca lo
     *    preguntaba, por eso el modelo se perdía al reentrar.
     *  2. **SharedPreferences**, por si el servidor no tiene modelo fijado (sesión recién
     *    creada) o no responde.
     *  3. La última elección global, para un chat nuevo (sessionId en blanco).
     *  4. Default SOLO en Antigravity, que es el único proveedor donde existe.
     *
     * Nada de esto sustituye al usuario por un `first()` de la lista: un modelo
     * desconocido se deja como está, para que la elección sea siempre explícita.
     */
    // F3: una sola carga de config por sesion, con guardia anti-carreras (H-06).
    // Cancela la anterior y, al volver, comprueba que se siga mostrando la misma
    // sesion antes de escribir estado. Una respuesta tardia de A nunca pisa a B.
    private var configJob: Job? = null
    private var modeloManualEnSesion = false

    fun cargarConfig(sid: String) {
        configJob?.cancel()
        modeloManualEnSesion = false
        configJob = viewModelScope.launch {
            val ctx = runCatching { AppContext.require() }.getOrNull()
            // MEDIDO 2026-10-08 (StrictMode): `getSharedPreferences` resuelve rutas
            // en disco; en main es DiskReadViolation. La migracion va a IO.
            if (ctx != null) withContext(Dispatchers.IO) {
                runCatching { ModelPreferences.migrarPrefijos(ctx) }
            }
            val cfg = configRepo.leer(sid)
            if (_currentSessionId.value != sid) return@launch
            _servidorAlcanzable.value = cfg.origen == ConfigSesion.Origen.SERVIDOR || sid.isBlank()
            if (cfg.modelo != null) _selectedModel.value = cfg.modelo
            _agentMode.value = cfg.agente ?: AGENTE_POR_DEFECTO
            // F4: el titulo viene del mismo GET (antes: lista completa solo para esto).
            if (!cfg.titulo.isNullOrBlank() && !isTechnicalTitle(cfg.titulo)) {
                _sessionTitle.value = cfg.titulo
            }
        }
    }

    fun selectModel(modelId: String?) {
        // Nunca se guarda ni se envia con prefijo: la lista trae ids cortos y el servidor
        // distingue por pareja id mas providerID, no por un id compuesto.
        val limpio = ModelPreferences.normalizar(modelId)
        if (limpio.isBlank()) return
        // F3: optimista con reversa. Se pinta ya; si el servidor rechaza, se restaura el
        // previo y el motivo va a _error (antes: prefs primero y catch mudo, H-05).
        val previo = _selectedModel.value
        _selectedModel.value = limpio
        modeloManualEnSesion = true
        val sid = (_currentSessionId.value ?: "").trim()
        if (sid.isBlank()) {
            // Chat nuevo sin id: no hay servidor al que empujar; el repo guardara la
            // ultima eleccion cuando se fije de verdad.
            viewModelScope.launch { configRepo.fijarModelo("", limpio) }
            return
        }
        viewModelScope.launch {
            when (val r = configRepo.fijarModelo(sid, limpio)) {
                is Resultado.Ok -> Unit
                is Resultado.Fallo -> {
                    if (_currentSessionId.value == sid) _selectedModel.value = previo
                    _error.value = r.motivo
                }
            }
        }
    }

    /**
     * Elige el agente de la sesion. Sustituye a `toggleAgentMode()`, que alternaba entre
     * dos valores fijos: MEDIDO, de los 40 agentes de OpenCode solo se podian alcanzar 2.
     */
    fun selectAgent(name: String) {
        val limpio = name.trim()
        if (limpio.isBlank()) return
        val lista = _agents.value
        // Sin lista no se bloquea: el servidor valida contra /api/agent y es la
        // validacion que de verdad importa (MEDIDO: OpenCode guarda CUALQUIER nombre, y
        // uno inexistente produce un turno vacio sin decir nada). Este filtro es solo
        // para que un toque en la hoja no instale un nombre imposible.
        if (lista.isNotEmpty() && lista.none { it.name == limpio }) {
            _error.value = "«$limpio» no es un agente de OpenCode."
            return
        }
        // F3: optimista con reversa (igual que el modelo) + el modelo del agente si el
        // usuario no eligio modelo a mano en esta sesion.
        val previo = _agentMode.value
        val modeloPrevio = _selectedModel.value
        _agentMode.value = limpio
        val sid = (_currentSessionId.value ?: "").trim()
        viewModelScope.launch {
            when (val r = configRepo.fijarAgente(sid, limpio)) {
                is Resultado.Fallo -> {
                    if ((_currentSessionId.value ?: "").trim() == sid) _agentMode.value = previo
                    _error.value = r.motivo
                    return@launch
                }
                is Resultado.Ok -> Unit
            }
            if (!modeloManualEnSesion && sid.isNotBlank()) {
                val mod = configRepo.modeloDeAgente(limpio)?.id
                if (!mod.isNullOrBlank()) {
                    when (val r2 = configRepo.fijarModelo(sid, mod)) {
                        is Resultado.Ok -> {
                            if ((_currentSessionId.value ?: "").trim() == sid) {
                                _selectedModel.value = ModelosUtil.normalizarIdModelo(mod)
                            }
                        }
                        is Resultado.Fallo -> {
                            if ((_currentSessionId.value ?: "").trim() == sid) {
                                _selectedModel.value = modeloPrevio
                            }
                            _error.value = r2.motivo
                        }
                    }
                }
            }
        }
    }

    /**
     * Recupera el agente de una sesion al abrirla. Primero OpenCode, que es quien lo
     * guardo de verdad; luego la copia local; y si no se sabe de ningun lado, el de por
     * defecto.
     *
     * El orden importa: si se leyera primero la copia local, un agente cambiado DESDE EL
     * CLI no se veria en la app, que es el mismo bug que se corrigio para el modelo.
     */
    /** F4: invalida el catalogo con cache y lo vuelve a pedir. */
    fun refrescarCatalogo() {
        viewModelScope.launch {
            runCatching { api.refrescarCatalogo() }
            loadModels()
            loadAgents()
        }
    }

    fun loadAgents() {
        if (_agentsLoading.value) return
        viewModelScope.launch {
            _agentsLoading.value = true
            try {
                val resp = api.getOpencodeAgents()
                if (resp.ok && resp.data != null) {
                    // Solo los que OpenCode ofrece para elegir. La lista COMPLETA sigue
                    // en el Hub por si algun dia se quieren los cargos.
                    _agents.value = resp.data.seleccionables()
                    // Si el agente elegido ya no esta (se borro un cargo), se vuelve a
                    // Build. Antes no habia lista con la que comprobar nada, y el valor
                    // se quedaba pegado a un nombre que el Hub ya no reconoceria.
                    val elegido = _agentMode.value
                    if (elegido.isNotBlank() && resp.data.none { it.name == elegido }) {
                        _agentMode.value = "build"
                    }
                }
            } catch (e: Exception) {
                _error.value = "No se pudieron cargar los agentes: ${e.message}"
            } finally {
                _agentsLoading.value = false
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun loadModels() {
        viewModelScope.launch {
            _modelsLoading.value = true
            try {
                // F13: catalogo COMPLETO (antes: filtro "opencode"). MEDIDO 2026-10-09:
                // el servidor provee 79 modelos (42 opencode + 37 google, todos
                // enabled) y el filtro escondia 37, incluido el modelo fijado en
                // sesiones reales ("No disponible" + enviar condenado). Los de
                // otros proveedores llevan su etiqueta en la descripcion y la
                // guardia de envio frena el turno condenado con motivo honesto.
                val resp = api.getModels(null)
                if (resp.ok && resp.data != null && resp.data.isNotEmpty()) {
                    _models.value = resp.data
                    // Antes, si el modelo elegido no venia en la lista, se sustituia en
                    // SILENCIO por `resp.data.first()`: el usuario tenia "Space Bunny
                    // Free" y le aparecia "LongCat" sin explicación. Ahora solo se rellena
                    // cuando no hay nada elegido; si hay eleccion, se respeta aunque la
                    // lista no la traiga (puede ser un modelo filtrado o de otro motor).
                    if (_selectedModel.value.isNullOrBlank()) {
                        // MEDIDO 2026-10-07 (chip con Space Bunny en sesion de orchestrator):
                        // el default global (primer free) pisaba al modelo DEFINIDO del agente
                        // de la sesion, y al enviar se fijaba ese en el servidor. Si la sesion
                        // trabaja con un agente que trae modelo propio y esta en la lista, ese
                        // manda; si no, el global de siempre.
                        // Los agentes se leen aqui y no de `_agents`: `loadAgents()` vuelve al
                        // instante (lanza su propia corrutina) y el estado aun estaria vacio.
                        // Carrera real, no teorica: con `_agents` el default caia al global a
                        // veces si y a veces no segun quien llegara antes.
                        val ags = _agents.value.takeIf { it.isNotEmpty() }
                            ?: runCatching { api.getOpencodeAgents() }.getOrNull()?.data.orEmpty()
                        _selectedModel.value = modeloPorDefectoPara(
                            _agentMode.value,
                            ags,
                            resp.data
                        )
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
    /**
     * Fusiona una cola de mensajes con la lista que ya se esta viendo.
     *
     * Hace falta porque las llamadas existentes hacen `_messages.value = fresh`, un
     * REPLACE, y con un tail un replace se comeria el historial: el usuario veria 200
     * mensajes de golpe. Aqui se clava por id de mensaje:
     *
     * - los ids que ya estaban se sustituyen EN SU SITIO, no al final. Asi el texto
     *   parcial del asistente se actualiza donde lo esta leyendo el usuario y la lista
     *   no salta, que es lo que hacia que la pantalla parpadeara al leer.
     * - los ids nuevos se anaden al final, en el orden del tail (que es ascendente).
     * - los mensajes sin id no se pueden clavar, asi que se descartan del tail. Antes se
     *   perdia todo el historial; ahora se pierde un caso degenerado.
     */
    // Tope de pagina de OpenCode: pedir mas de 200 no daria mas, y el Hub avisaria
    // por log. 200 es lo que hace falta para cubrir un turno largo.
    private val TAIL_POLL = 200

    // Espera del refresco: normal y maxima cuando el Hub no responde (ver el
    // comentario del backoff dentro del bucle).
    private val REFRESH_MIN_WAIT_MS = 2_000L
    private val REFRESH_MAX_WAIT_MS = 30_000L

    // Ciclos de refresco seguidos fallidos antes de decir que el Hub no esta.
    // El refresco va cada 2 s, asi que 3 son ~6 s: suficiente para no Destapar un
    // fallo puntual, suficiente para no dejar la pantalla mintiendo un minuto.
    private val SERVIDOR_FAIL_STREAK_FOR_DEGRADED = 3

    private fun mergeTail(actual: List<Message>, cola: List<Message>): List<Message> {
        if (cola.isEmpty()) return actual
        val porId = HashMap<String, Message>()
        cola.forEach { m -> m.info?.id?.let { porId[it] = m } }
        if (porId.isEmpty()) return actual
        val vistos = HashSet<String>()
        val fusion = ArrayList<Message>(actual.size + cola.size)
        for (m in actual) {
            val id = m.info?.id
            if (id != null && porId.containsKey(id)) {
                fusion.add(porId.getValue(id))
                vistos.add(id)
            } else {
                fusion.add(m)
            }
        }
        for (m in cola) {
            val id = m.info?.id ?: continue
            if (id !in vistos) {
                fusion.add(m)
                vistos.add(id)
            }
        }
        return fusion
    }

    private fun startViewRefresh(sessionId: String) {
        viewRefreshJob?.cancel()
        viewRefreshSessionId = sessionId
        viewRefreshJob = viewModelScope.launch {
            var streamingSince = 0
            // Mismo motivo que en MainViewModel: espera fija ante un Hub que falla
            // dispara peticiones sin parar. Este bucle hace CUATRO por ciclo
            // (inflight, formularios, permisos, mensajes), asi que a 2 s son
            // ~120/min solo aqui. Con backoff, un Hub caido deja de generar trafico.
            var waitMs = REFRESH_MIN_WAIT_MS
            var refreshFailures = 0
            while (isActive) {
                delay(waitMs)
                if (_currentSessionId.value != sessionId) break
                // Durante un envío hay otro poll corriendo; no competimos con él.
                // OJO: estos dos `continue` se saltan el cierre de ciclo, asi que un
                // ciclo descartado NO cuenta como fallido NI resetea la racha. Es
                // deliberado: no se esta midiendo nada, y pedir una sonda extra solo
                // para esto costaria peticiones en el turno que mas las necesita. El
                // efecto honesto es una ventana: con texto en vivo, el refresco hace
                // como mucho 6 ciclos de espera (~12 s) y despues uno completo, asi
                // que servidorAlcanzable puede tardar hasta ~14 s en corregirse. No queda
                // pegado: en cuanto hay un ciclo completo, si responde, se resetea.
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
                // Un ciclo cuenta como fallido si CUALQUIERA de las llamadas de abajo
                // revienta. Se decide al final del ciclo, no en cada catch: si se
                // contara por peticion, un exito intermedio resetea la racha y la de
                // "Hub caido" no llegaria nunca a dispararse.
                var cycleFailed = false
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
                        // Un registro "ocupado" solo vale si se ve actividad reciente.
                        // Si el vigilante perdio el succeeded al reconectar, la sesion se
                        // queda "ocupada" hasta 15 min y el indicador de "trabajando" se
                        // quedaba pegado aunque el turno hubiera acabado.
                        _turnBusy.value = TurnState.isBusy(mine)
                        _turnOver.value = TurnState.isOver(mine)
                    }
                } catch (_: Exception) {
                    // Antes era un catch mudo SIN NI UN COMENTARIO: un 429/502 aqui no
                    // dejaba ni una linea, y como de este bloque depende el "trabajando"
                    // y el cierre de turno, la pantalla se quedaba con el estado viejo
                    // sin avisar. Ahora el ciclo cuenta como fallido.
                    cycleFailed = true
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
                    // ciclo reintenta solo. Lo que se anade es que CUENTE, para que
                    // varios ciclos seguidos acaben diciendolo en pantalla.
                    cycleFailed = true
                }

                // Permisos pendientes, mismo criterio y mismo motivo que los formularios:
                // van primero y en su propio try, y deben estar frescos ANTES de juzgar
                // si el turno termino. Un turno bloqueado esperando un permiso sigue
                // trabajando: la herramienta esta suspendida, no terminada.
                try {
                    val pr = api.getPendingPermissions(sessionId)
                    if (pr.ok && pr.data != null) _pendingPermissions.value = pr.data
                } catch (_: Exception) {
                    // Idem: el siguiente ciclo reintenta, y ademas cuenta.
                    cycleFailed = true
                }

                try {
                    // Solo la COLA: 0,27 s contra los 15,63 s del historial entero, medidos
                    // en el Hub. Y se fusiona con lo que ya se ve, porque un REPLACE aqui
                    // se comeria el historial (200 mensajes de golpe).
                    //
                    // Si la peticion falla se cae al historial completo, para que un fallo
                    // puntual del camino rapido no deje la pantalla congelada. Es un GET de
                    // 15 s, pero solo cuando el rapido ya ha fallado.
                    var fresh: List<Message>? = null
                    try {
                        val cola = api.getMessagesTail(sessionId, TAIL_POLL)
                        if (cola.ok && cola.data != null) {
                            fresh = mergeTail(_messages.value, cola.data.filterNot { it.isEmpty })
                        }
                    } catch (_: Exception) {
                        // Idem: el siguiente ciclo reintenta por el camino rapido.
                    }
                    if (fresh == null) {
                        val r = api.getMessages(sessionId)
                        if (r.ok && r.data != null) fresh = r.data.filterNot { it.isEmpty }
                    }
                    if (fresh != null) {
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
                    // Aqui ya fallo el camino rapido Y el lento (si el rapido se
                    // recupera con el historial completo, se sale por el if de arriba
                    // y no se llega aqui). O sea: los mensajes no llegan.
                    cycleFailed = true
                }

                // Cierre del ciclo. Ahi se decide si el Hub esta disponible: ni antes
                // (seria contar por peticion y la racha nunca llegaria a 3) ni nunca
                // (seria tragarselo sin final, que es justo el bug que se arregla).
                noteRefreshResult(ok = !cycleFailed)
                // El backoff se decide por CICLO (no por peticion) por lo mismo que
                // noteRefreshResult: un exito intermedio no resetea nada.
                if (cycleFailed) {
                    refreshFailures++
                    if (refreshFailures >= 3) waitMs = REFRESH_MAX_WAIT_MS
                } else {
                    refreshFailures = 0
                    if (waitMs != REFRESH_MIN_WAIT_MS) waitMs = REFRESH_MIN_WAIT_MS
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
     * "Cerrado" NO se deduce de `info.time.completed`: ese campo cierra el MENSAJE y se
     * cumple tras cada `bash` con exit 0, aunque el agente siga trabajando. La condición
     * vive en `turnIsReallyFinished()` y sale del vigilante
     * (`session.execution.succeeded`). Ver `docs/adr/ADR-003-turn-final-signal.md`.
     *
     * Si el turno terminó justo ahora:
     *  - se marca para que el chat dibuje el divisor de "respuesta final"
     *  - y se lanza notificación, pero solo si la app está en segundo plano
     *    (TurnNotifier lo comprueba con MainActivity.isForeground).
     *
     * Divisor y notificación comparten esta función y la misma guarda, así que no
     * pueden desincronizarse: es exactamente el mismo disparo.
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

    /**
     * Actualiza el indicador de "trabajando" y lanza el aviso de fin de turno UNA vez.
     *
     * La condición NO la decide esta función sino `turnIsReallyFinished()`, que se apoya
     * solo en los eventos `session.execution.*` del vigilante. Aquí solo se aplica a la
     * dedup: un mismo turno no debe de avisar cada 2 s.
     */
    private fun turnIsReallyFinished(messages: List<Message>): Boolean {
        // VETO INMEDIATO, antes de mirar NADA mas. Una herramienta en ejecucion
        // significa que el turno NO ha terminado, diga lo que diga el resto.
        //
        // Va el primero A PROPOSITO. Antes estaba mas abajo del todo, detras de
        // "if (_turnOver.value) return true", asi que cuando el vigilante decia
        // "terminado" nunca se llegaba a mirar y el divisor se pintaba con un bash
        // "ejecutando..." debajo. El mismo estado erroneo hacia fallar los TRES
        // sintomas a la vez (divisor, notificacion y circulo de carga), porque los
        // tres beben la misma decision de turno.
        //
        // Se mira la cola de los ultimos TAIL_POLL (200) mensajes, no solo el ultimo: en un
        // turno largo (asi, no literalmente "todo" el historial)
        // OpenCode genera varios mensajes del asistente, y el ultimo puede ser solo
        // texto mientras el bash sigue en marcha en el anterior.
if (messages.any { m -> m.parts.orEmpty().any { it.state?.status == "running" } }) {
            return false
        }
        // Un formulario pendiente significa que el agente esta esperando a la persona,
        // asi que el turno NO ha terminado.
        if (_pendingForms.value.isNotEmpty()) return false

        // Misma regla para los permisos. Sin esto, un turno con un prompt de permiso en
        // pantalla marcaba "respuesta final" y disparaba la notificacion mientras la
        // herramienta seguia bloqueada esperando una respuesta que la app no ensenaba.
        if (_pendingPermissions.value.isNotEmpty()) return false
        if (_turnBusy.value) return false
        if (_turnOver.value) return true
        // VIGILANTE CIEGO: no hay registro, o es tan viejo que ya no se cree. Aqui no se
        // puede devolver "no ha terminado" a ciegas: eso dejaba el indicador de
        // "trabajando" pegado indefinidamente. Se usa la senal de los mensajes, que en
        // este caso si es fiable:
        //  - hay una herramienta running -> hay trabajo vivo, NO ha terminado. Cubre el
        //    bash sleep 60, donde no llega ningun evento y un registro por antiguedad
        //    mentiria.
        //  - no hay herramienta y el ultimo mensaje esta cerrado -> lo mas probable es que
        //    el turno haya terminado. Es el caso degradado: el vigilante no pudo saberlo.
        val last = messages.lastOrNull { it.role == "assistant" } ?: return false
        if (!warnedNoWatcher) {
            warnedNoWatcher = true
            android.util.Log.w(
                "AegisChat",
                "Vigilante sin datos para ${_currentSessionId.value}: se decide con los mensajes."
            )
        }
        return last.info?.time?.containsKey("completed") == true
    }

    private var warnedNoWatcher = false

    private fun announceFinishedTurnIfAny(fresh: List<Message>) {
        val lastAssistant = fresh.lastOrNull { it.role == "assistant" } ?: return
        val id = lastAssistant.info?.id ?: return
        val finished = turnIsReallyFinished(fresh)
        _turnInProgress.value = !finished
        _turnFinished.value = finished
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
    /**
     * Responde a un permiso pendiente.
     *
     * @param decision "once" | "always" | "reject". El Hub valida el valor y devuelve 400
     *   si no es uno de esos tres, así que aqui solo se filtra el vacío para poder dar un
     *   mensaje en castellano en vez de un error opaco.
     *
     * Aviso sobre la semántica, que no es la intuitiva: en OpenCode 2.0.14 "reject"
     * NO rechaza solo este permiso, rechaza también todos los demás pendientes de la
     * misma sesión. Un toque puede cancelar varios avisos a la vez. Por eso la UI lo
     * escribe en el botón en vez de dejarlo implícito.
     */
    fun answerPermission(permission: PendingPermission, decision: String) {
        val sid = _currentSessionId.value.orEmpty()
        val pid = permission.id.orEmpty()
        if (sid.isBlank() || pid.isBlank()) {
            _error.value = "No se puede responder: permiso incompleto"
            return
        }
        if (decision !in setOf("once", "always", "reject")) {
            _error.value = "Decisión no válida: $decision"
            return
        }
        viewModelScope.launch {
            _replyingPermission.value = true
            try {
                val resp = api.replyPermission(sid, pid, PermissionReplyBody(decision = decision))
                if (resp.ok) {
                    _error.value = null
                    // Se quita al instante para que la UI no repita los botones; el
                    // siguiente refresco confirma que OpenCode ya no lo lista.
                    _pendingPermissions.value = _pendingPermissions.value.filterNot { it.id == pid }
                } else {
                    _error.value = resp.error?.message ?: resp.error?.code
                        ?: "No se pudo enviar la decisión"
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Error al enviar la decisión"
            } finally {
                _replyingPermission.value = false
            }
        }
    }

    /**
     * Pide una parte completa al Hub. Se usa cuando la venia recortada: la lista de
     * mensajes no lleva el binario (serian decenas de MB de base64) y la app lo pide
     * unicamente cuando el usuario despliega esa fila.
     *
     * Va por el ViewModel y no desde el composable a proposito: el id de sesion solo
     * existe aqui, y pasarlo por el arbol de composables seria tocar la firma de medio
     * chat para nada.
     *
     * @param onDone recibe la parte ya descargada, o null si fallo. Se invoca SIEMPRE,
     *   tambien en error, para que la UI pueda quitar el estado de "cargando" y no se
     *   quede un spinner eterno.
     */
    fun loadPartFull(partId: String?, messageId: String?, onDone: (PartFull?) -> Unit) {
        val sid = _currentSessionId.value.orEmpty()
        if (sid.isBlank() || partId.isNullOrBlank()) {
            onDone(null)
            return
        }
        viewModelScope.launch {
            var resultado: PartFull? = null
            try {
                val r = api.getPart(sid, partId, messageId)
                if (r.ok) resultado = r.data
            } catch (_: Exception) {
                // Un fallo al recuperar la parte no puede tumbar el chat: la fila se
                // queda como estaba, que ya es un repliegue legible.
            }
            onDone(resultado)
        }
    }

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
        // F5: con el flag, el espejo y el canal se cierran aqui tambien.
        espejoJob?.cancel()
        espejoJob = null
        if (SYNC_POR_EVENTOS) chatSync.cerrar()
    }

    /**
     * F5: espejo del canal unico a los estados de pantalla. Unico escritor de
     * `_messages` con el flag (el resto de escritores se saltan tras el flag).
     */
    private fun arrancarEspejo(sid: String) {
        espejoJob?.cancel()
        chatSync.abrir(sid)
        espejoJob = viewModelScope.launch {
            launch { chatSync.mensajes.collect { _messages.value = it } }
            launch { chatSync.textoEnVivo.collect { _streamingText.value = it } }
            launch { chatSync.formularios.collect { _pendingForms.value = it } }
            launch { chatSync.permisos.collect { _pendingPermissions.value = it } }
            launch {
                chatSync.turno.collect { t ->
                    when (t) {
                        is EstadoTurno.Ocupado -> {
                            _turnBusy.value = true
                            _turnFinished.value = false
                        }
                        is EstadoTurno.Terminado -> {
                            _turnBusy.value = false
                            announceFinishedTurnIfAny(_messages.value)
                        }
                        is EstadoTurno.Ocioso -> {
                            _turnBusy.value = false
                        }
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopViewRefresh()
    }

    fun load(sessionId: String) {
        // G1: mojon de apertura (la app ya cuenta peticiones en debug).
        val fotoApertura = MarcasCiclo.abrirInicio(sessionId)
        repoJob?.cancel()
        repoJob = if (sessionId.isBlank()) null else viewModelScope.launch {
            var base = MarcasCiclo.foto()
            while (isActive) {
                delay(60_000L)
                if (_currentSessionId.value != sessionId) return@launch
                base = if (!_loading.value && _sendingInFlight.value != true) {
                    MarcasCiclo.reposo(sessionId, base)
                } else {
                    // En turno la ventana no cuenta reposo: se reancla sin emitir.
                    MarcasCiclo.foto()
                }
            }
        }
        pollingJob?.cancel()
        if (SYNC_POR_EVENTOS) {
            // F5: sin bucle viejo; el canal unico alimenta el espejo.
            if (sessionId.isBlank()) {
                chatSync.cerrar()
            } else {
                arrancarEspejo(sessionId)
            }
        } else if (sessionId.isBlank()) {
            stopViewRefresh()
        } else if (viewRefreshSessionId != sessionId) {
            // startViewRefresh cancela el job anterior por su cuenta, así que recargar
            // la misma sesión tampoco deja el refresco muerto.
            startViewRefresh(sessionId)
        }
        // F3: la sesion actual se fija ANTES de cargar config (la guardia anti-carreras
        // compara contra este valor). Al cambiar de sesion el chip se limpia: nunca se
        // muestra el modelo/agente de otra sesion mientras llega la lectura.
        _currentSessionId.value = sessionId
        _selectedModel.value = null
        _agentMode.value = AGENTE_POR_DEFECTO
        cargarConfig(sessionId)
        if (sessionId.isBlank()) {
            _sessionTitle.value = "Nuevo chat"
            loadModels()
            loadAgents()
            return
        }
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            loadModels()
            loadAgents()

            // Try to resolve human-readable title from sessions list
            // F4: fuera (el titulo viene de cargarConfig/leer con el mismo GET de
            // modelo+agente). Esta era 1 peticion de lista completa solo por el titulo.

            // F4: la cola primero (rapida) para pintar ya; el historial completo
            // despues reconcilia por id (F5 lo hara bajo demanda por scroll).
            // F5: con el flag el espejo es el unico escritor; estos dos bloques se saltan.
            if (!SYNC_POR_EVENTOS) {
            try {
                val colaResp = api.getMessagesTail(sessionId, 200)
                if (colaResp.ok && colaResp.data != null) {
                    val nonEmpties = colaResp.data.filterNot { it.isEmpty }
                    _messages.value = nonEmpties
                    updateTitleFromFirstMessage(nonEmpties)
                    _loading.value = false
                }
            } catch (e: Exception) {
                    // La cola es optimizacion (rapida); si falla va el historial
                    // completo debajo, que si avisa. Un aviso aqui duplicaria.
                    android.util.Log.d("AegisChat", "cola rapida fallo, va historial: ${e.message}")
                }

            try {
                val resp = api.getMessages(sessionId)
                if (resp.ok && resp.data != null) {
                    val nonEmpties = resp.data.filterNot { it.isEmpty }
                    _messages.value = mergeTail(_messages.value, nonEmpties)
                    updateTitleFromFirstMessage(_messages.value)
                } else if (!resp.ok) {
                    _error.value = resp.error?.message ?: resp.error?.code ?: "Error al obtener mensajes"
                }
            } catch (e: Exception) {
                _error.value = e.localizedMessage ?: e.message ?: "Error de conexión con el servidor"
            } finally {
                _loading.value = false
                // G1: fin de apertura (cubre cola rapida e historial; con el flag
                // de eventos no hay bloques que medir y el inicio queda sin fin).
                MarcasCiclo.abrirFin(sessionId, fotoApertura)
            }
            } // if (!SYNC_POR_EVENTOS)
        }
    }

    /**
     * F5: envio por el canal unico. Sin pollingJob ni stream propio: el espejo ya trae
     * el texto en vivo y la reconciliacion confirma el eco (fusion por id). El motivo
     * de fallo sale del cuerpo HTTP real, igual que el camino viejo.
     */
    private suspend fun envioPorEventos(
        targetSessionId: String,
        sendReq: SendMessageRequest,
        tempMsgId: String
    ) {
        _sendingInFlight.value = true
        try {
            api.sendMessage(sessionId = targetSessionId, body = sendReq)
            _sendingInFlight.value = false
            chatSync.refrescarAhora()
            _loading.value = false
        } catch (e: Exception) {
            _sendingInFlight.value = false
            chatSync.actualizarMensaje(tempMsgId) { it.withStatus(MessageDeliveryStatus.ERROR) }
            val http = e as? retrofit2.HttpException
            _error.value = if (http != null) {
                val cuerpo = runCatching { http.response()?.errorBody()?.string() }.getOrNull()
                ErroresRed.parsear(cuerpo, http.code())
            } else {
                "Error al enviar mensaje: ${e.localizedMessage ?: e.message ?: "Tiempo de espera agotado"}"
            }
        } finally {
            _loading.value = false
            _sendingInFlight.value = false
            inFlightSendKey = null
        }
    }

    fun send(sessionId: String, text: String): Boolean {
        return sendWithFiles(sessionId, text, emptyList())
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
        // F13: si la guardia frena el reintento, se devuelve el mensaje tal cual
        // estaba (si no, el texto se pierde en silencio).
        if (!sendWithFiles(sessionId, text, files)) {
            _messages.value = _messages.value + failedMsg
        }
    }

    fun sendWithFiles(
        sessionId: String,
        text: String,
        files: List<AttachedFile>
    ): Boolean {
        if (text.isBlank() && files.isEmpty()) return false

        // F13: modelo ausente del catalogo = turno condenado (chip
        // "No disponible" + enviar que se buguea). Se frena aqui con motivo
        // honesto y SIN mensaje optimista; la UI conserva el texto.
        ModelosUtil.motivoModeloNoDisponible(_selectedModel.value, _models.value)?.let {
            _error.value = it
            return false
        }

        val tempMsgId = "local_${System.currentTimeMillis()}"

        // FASE A-5: guarda anti doble envío — un segundo click/reentrada con el mismo
        // mensaje mientras sigue en vuelo no debe reenviarlo (ver inFlightSendKey).
        val sendKey = "${sessionId.trim()}|${text.trim()}|${files.size}"
        if (sendKey == inFlightSendKey) return false
        inFlightSendKey = sendKey
        // G1: mojon de envio (solo envios reales: el blank y la guardia F13 ya
        // devolvieron arriba).
        val fotoEnvio = MarcasCiclo.enviarInicio(sessionId)

        // Titulo provisional, y SOLO si esta sesion no existe todavia. Es la MISMA
        // condicion que decide mas abajo si hay que crearla (`activeSessionId.isBlank()`),
        // y no la de "el titulo esta vacio": con esa, cualquier sesion cuyo titulo
        // llegara tarde o fuera tecnico se renombraba aqui Y en el Hub, y el recorte del
        // primer mensaje se quedaba puesto para siempre.
        //
        // MEDIDO 2026-09-30: ses_f15109400ffe se llama "quant-math" en OpenCode y salia en
        // la app como "adjunto captura ee pantalla de…". Ademas el Hub guardaba ese texto
        // en el mismo cubo que el renombrado a mano, asi que ganaba al nombre real; las dos
        // mitades estan arregladas (esta aqui, y en server.js del lado del Hub).
        val sesionEsNueva = sessionId.isBlank() && _currentSessionId.value.isNullOrBlank()
        if (text.isNotBlank() && sesionEsNueva) {
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
            // F5: con el flag el optimista entra por el canal (el espejo es el unico
            // escritor de _messages); si no, directo como siempre.
            if (SYNC_POR_EVENTOS) chatSync.insertarOptimista(optimistic)
            else _messages.value = _messages.value + optimistic

            // 2. Resolve Target Session ID
            val activeSessionId = sessionId.ifBlank { _currentSessionId.value ?: "" }
            val targetSessionId = if (activeSessionId.isBlank()) createNewSession() else activeSessionId
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
                        // F4: la cola (rapida) en vez del historial entero; se fusiona
                        // por id con lo ya pintado (F5 lo sustituye por eventos).
                        val pollResp = api.getMessagesTail(targetSessionId, 50)
                        if (pollResp.ok && pollResp.data != null) {
                            val nonEmpties = mergeTail(
                                _messages.value,
                                pollResp.data.filterNot { it.isEmpty }
                            )
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
                    // GUARD-SILENCIO-OK: poll de 1,5 s por diseno (reintenta solo;
                    // el estado final lo marca el bloque sync de abajo con motivo).
                    } catch (_: Exception) { }
                }
            }

            // 5. Envio del mensaje por la via nativa (la costura fija el modelo y el poll trae
            // la respuesta). Sin fallback a un id: si no hay modelo elegido se envia null y
            // decide OpenCode; mandar un id caducado es peor que no mandar nada.
            try {
                val currentAgentMode = _agentMode.value
                val currentModel = _selectedModel.value
                val sendReq = SendMessageRequest(
                    parts = reqParts,
                    model = currentModel,
                    agent = currentAgentMode,
                    mode = currentAgentMode
                )

                // F5: con el flag el envio va por el canal unico (sin pollingJob ni
                // stream propio: el espejo ya los cubre). Se cancela el poll que se
                // acaba de crear arriba. El camino viejo sigue intacto debajo.
                if (SYNC_POR_EVENTOS) {
                    pollingJob?.cancel()
                    envioPorEventos(targetSessionId, sendReq, tempMsgId)
                    return@launch
                }

                // MEDIDO 2026-10-03: esto era un POST SSE al Hub en :8765, que ya no escucha.
                // El Hub adaptaba el stream del CLI a eventos accepted/chunk/tool_start/done; sin
                // el, el POST siempre fallaba y ademas pintaba un error del Hub aunque el mensaje
                // pudiera entregarse por la via nativa. Ahora el envio va directo por la costura
                // (`api.sendMessage`: fija el modelo en el servidor y hace el prompt al CLI), y la
                // respuesta llega por el poll de arriba mas el sync de abajo, que ya refrescan el
                // texto parcial del asistente cada 1,5 s. El streaming token a token por
                // `/api/event` queda pendiente (hay `EventStream.kt` para ello); el poll es la via
                // honesta que funciona hoy, no la rapida que no existe.
                var sseSuccess = false
                var sseRequestAccepted = false
                _streamingText.value = ""
                _streamingTools.value = emptyList()

                // Streaming token a token por `/api/event` del CLI (emite `session.text.delta`
                // y `session.text.ended` ya filtrados por sesion). Vive solo lo que dura el envio
                // y se cancela en el finally. Si el stream falla, el poll de arriba sigue trayendo
                // el parcial cada 1,5 s: el streaming adelanta, no sustituye.
                val streamJob = launch {
                    try {
                        EventStream.createEventStream(targetSessionId).collect { item ->
                            if (item is OpenCodeStreamItem.TextUpdate) {
                                _streamingText.value = item.textAccumulated
                            }
                        }
                    // GUARD-SILENCIO-OK: stream best-effort (el poll trae lo mismo;
                    // si el stream muere, el parcial sigue llegando por el poll).
                    } catch (_: Exception) { }
                }

                _sendingInFlight.value = true
                try {
                    val responseMsg = api.sendMessage(
                        sessionId = targetSessionId,
                        body = sendReq
                    )
                    _sendingInFlight.value = false
                    if (!responseMsg.isEmpty) {
                        messageDelivered = true
                        pollingJob?.cancel()
                        val updated = _messages.value.map {
                            if (it.info?.id == tempMsgId) it.withStatus(MessageDeliveryStatus.SENT) else it
                        }
                        val exists = updated.any { it.info?.id == responseMsg.info?.id }
                        _messages.value = if (exists) updated else updated + responseMsg
                        _loading.value = false
                    } else {
                        // La via nativa devuelve Message vacio a proposito: la respuesta va por
                        // el poll. Se marca el envio como aceptado para no reenviarlo.
                        _messages.value = _messages.value.map {
                            if (it.info?.id == tempMsgId) it.withStatus(MessageDeliveryStatus.SENT) else it
                        }
                    }
                    sseRequestAccepted = true
                    sseSuccess = true
                } catch (_: Exception) {
                    _sendingInFlight.value = false
                    sseSuccess = false
                } finally {
                    streamJob.cancel()
                    _streamingText.value = null
                    _streamingTools.value = emptyList()
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
                    } catch (e: Exception) {
                        // Ultimo intento antes del ERROR visible de abajo: se deja
                        // rastro en log para distinguir "fallo el reintento" de
                        // "ni se intento".
                        android.util.Log.w("AegisChat", "reintento final fallo: ${e.message}")
                    }

                    _messages.value = _messages.value.map {
                        if (it.info?.id == tempMsgId) it.withStatus(MessageDeliveryStatus.ERROR) else it
                    }
                    // F1: si el fallo es HTTP, el motivo sale del cuerpo real (ver
                    // ErroresRed y los fixtures de `test/resources/errores/`); si no,
                    // el mensaje de la excepcion como antes.
                    val http = e as? retrofit2.HttpException
                    _error.value = if (http != null) {
                        val cuerpo = runCatching { http.response()?.errorBody()?.string() }.getOrNull()
                        ErroresRed.parsear(cuerpo, http.code())
                    } else {
                        "Error al enviar mensaje: ${e.localizedMessage ?: e.message ?: "Tiempo de espera agotado"}"
                    }
                    _messages.value = _messages.value.map {
                        if (it.info?.id == tempMsgId) it.withStatus(MessageDeliveryStatus.ERROR) else it
                    }
                }
            } finally {
                // G1: fin de envio (sesion efectiva: la actual si ya se fijo).
                MarcasCiclo.enviarFin(
                    _currentSessionId.value?.takeIf { it.isNotBlank() } ?: sessionId,
                    fotoEnvio
                )
                _streamingText.value = null
                _loading.value = false
                // Red de seguridad: si el POST semurio por una excepcion antes de
                // tocar el flag, "Enviando..." se quedaria colgado para siempre.
                _sendingInFlight.value = false
                pollingJob?.cancel()
                inFlightSendKey = null // A-5: libera la guarda anti doble envío al terminar
            }
        }
        return true
    }

    private suspend fun createNewSession(): String? = withContext(Dispatchers.IO) {
        // F2: por el repo (antes: catch -> null mudo, H-01). El motivo va a _error.
        when (val r = sesiones.crear(NuevaSesion("Nuevo chat"))) {
            is Resultado.Ok -> {
                r.avisos.forEach { android.util.Log.w("AegisChat", "createNewSession: $it") }
                r.valor.id
            }
            is Resultado.Fallo -> {
                _error.value = r.motivo
                null
            }
        }
    }
}
