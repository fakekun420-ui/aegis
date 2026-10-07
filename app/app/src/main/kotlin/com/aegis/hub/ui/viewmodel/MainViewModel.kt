package com.aegis.hub.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aegis.hub.data.*
import com.aegis.hub.data.repo.NuevaSesion
import com.aegis.hub.data.repo.Resultado
import com.aegis.hub.data.repo.SesionesRepo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.aegis.hub.data.TurnState

class MainViewModel : ViewModel() {
    /**
     * MEDIDO 2026-10-01: sale de [Conexion] por el mismo motivo que `ChatViewModel`, y aquí el
     * motivo es más fuerte: este es el fichero que pinta la LISTA de sesiones, que es la que el
     * usuario se quejaba de que a veces no reconocía.
     *
     * La costura no es solo el chat. Si solo se cambiara `ChatViewModel`, la lista seguiría
     * viniendo del Hub y habría dos rutas para el mismo dato: el estado de una sesión en la lista
     * y en su chat podrían discrepar. Un solo sitio decide de dónde vienen los datos.
     */
    private val api = Conexion.api
    private val projectsStore: ProjectsStore = ProjectsStore.default
    /** F2: creacion/vinculo por el repo; la costura queda para lectura. */
    private val sesiones = SesionesRepo()

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects

    private val _sessions = MutableStateFlow<List<OpencodeSession>>(emptyList())
    val sessions: StateFlow<List<OpencodeSession>> = _sessions

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _loadingProjects = MutableStateFlow(false)
    val loadingProjects: StateFlow<Boolean> = _loadingProjects
    private val _loadingSessions = MutableStateFlow(false)
    val loadingSessions: StateFlow<Boolean> = _loadingSessions

    fun refreshAll() {
        refreshProjects()
        refreshSessions()
    }

    fun clearError() { _error.value = null }
    fun setError(msg: String?) { _error.value = msg }

    private val _deletedSessionIds = mutableSetOf<String>()

    private val PROJECTS_RETRY_DELAYS_MS = listOf(1_000L, 2_000L, 4_000L)

    // F8: backoff de reintentos para la lista de proyectos. La carga original ocurre UNA vez
    // (init{refreshAll}); con un hub que se reinicia solo (keepalive) o con el token todavía
    // no legible en frío, esa única carga fallaba y la lista quedaba vacía hasta que alguien
    // creaba/borraba un proyecto (las únicas rutas que llamaban refreshProjects). Máximo 3
    // reintentos: 1s + 2s + 4s; un refresco explícito (entrada a la ventana, pull-to-refresh,
    // mutación) reinicia el contador y cancela el reintento pendiente.
    private var projectsRetryJob: Job? = null
    private var projectsRetries = 0

    // Sondeo de estado de turno desde /api/session/active (OpenCode nativo).
    //
    // Intervalo de sondeo con backoff adaptable: 3 s en regimen normal;
    // 8 s cuando la sonda falla reiteradamente. Previene tormentas de peticiones.
    private val POLL_MIN_WAIT_MS = 3_000L
    private val POLL_MAX_WAIT_MS = 8_000L
    private val FINISHED_TTL_MS = 12_000L

    // Momento del ultimo sondeo de active sessions que respondio con exito.
    // Usa SystemClock.elapsedRealtime() para ser inmune a cambios horarios NTP.
    @Volatile
    private var ultimoAciertoMs = 0L

    /** Cuanto de viejo es el ultimo dato de turno, en ms. -1 si no ha habido sondeo exitoso */
    fun inflightAntiguoMs(): Long {
        val t = ultimoAciertoMs
        if (t == 0L) return -1L
        val d = android.os.SystemClock.elapsedRealtime() - t
        return if (d < 0) 0L else d
    }

    /** Distingue 3 estados: sin datos (-1), dato viejo (>= 12 s), o dato fresco (< 12 s) */
    fun calcularEsFiable(): Boolean {
        val edad = inflightAntiguoMs()
        return edad in 0 until 12_000L
    }

    private val _inflightFiable = MutableStateFlow(false)
    val inflightFiable: StateFlow<Boolean> = _inflightFiable

    private val _inflightIds = MutableStateFlow<Set<String>>(emptySet())
    val inflightIds: StateFlow<Set<String>> = _inflightIds

    private val _finishedIds = MutableStateFlow<Set<String>>(emptySet())
    val finishedIds: StateFlow<Set<String>> = _finishedIds
    private val finishedAt = mutableMapOf<String, Long>()

    // F2: un solo camino (antes se saltaba la costura con `openCodeApi` directo y
    // solo caia a `api.getInflight` si fallaba: dos capas, mismo destino).
    private fun aplicarInflight(filas: List<InflightSession>) {
        _inflightIds.value = filas.filter { TurnState.isBusy(it) }.mapNotNull { it.id }.toSet()
        val now = System.currentTimeMillis()
        for (row in filas) {
            val id = row.id ?: continue
            if (TurnState.isOver(row)) finishedAt[id] = now
        }
        finishedAt.keys.retainAll { id -> now - (finishedAt[id] ?: 0L) < FINISHED_TTL_MS }
        _finishedIds.value = finishedAt.keys.toSet()
    }

    private var inflightJob: Job? = null

    init {
        refreshAll()
        startInflightPolling()
    }

    /**
     * Sondeo con backoff de `api.getInflight` (el unico camino; F2 elimino el doble
     * intento directo + costura). Si falla, marca desconocido con la edad real.
     */
    private fun startInflightPolling() {
        inflightJob?.cancel()
        inflightJob = viewModelScope.launch {
            var waitMs = POLL_MIN_WAIT_MS
            var consecutiveFailures = 0
            var fallosDelPoll = 0
            while (isActive) {
                var ok = false
                try {
                    val r = api.getInflight()
                    if (r.ok && r.data != null) {
                        aplicarInflight(r.data)
                        ok = true
                    }
                    ultimoAciertoMs = android.os.SystemClock.elapsedRealtime()
                    if (fallosDelPoll > 0) {
                        android.util.Log.i(
                            "AegisChats",
                            "poll de active sessions recuperado tras " + fallosDelPoll + " fallos; "
                                + "trabajando ahora: "
                                + _inflightIds.value.joinToString { it.takeLast(6) }.ifBlank { "(ninguna)" }
                        )
                        fallosDelPoll = 0
                    }
                } catch (e: Exception) {
                    if (!ok) {
                        fallosDelPoll++
                        android.util.Log.w(
                            "AegisChats",
                            "poll de active sessions fallo (" + fallosDelPoll + " seguidos, dato viejo "
                                + inflightAntiguoMs() + " ms): " + e.javaClass.simpleName + ": " + e.message
                        )
                    }
                }

                if (ok) {
                    consecutiveFailures = 0
                    if (waitMs != POLL_MIN_WAIT_MS) waitMs = POLL_MIN_WAIT_MS
                } else {
                    consecutiveFailures++
                    if (consecutiveFailures >= 3) waitMs = POLL_MAX_WAIT_MS
                }
                _inflightFiable.value = calcularEsFiable()
                delay(waitMs)
            }
        }
    }

    fun refreshInflightNow() {
        viewModelScope.launch {
            try {
                val r = api.getInflight()
                if (r.ok && r.data != null) {
                    aplicarInflight(r.data)
                    ultimoAciertoMs = android.os.SystemClock.elapsedRealtime()
                    _inflightFiable.value = calcularEsFiable()
                }
            } catch (e: Exception) {
                android.util.Log.w(
                    "AegisChats",
                    "refreshInflightNow fallo: " + e.javaClass.simpleName + ": " + e.message
                )
            }
        }
    }

        fun refreshProjects() {
        projectsRetries = 0
        projectsRetryJob?.cancel()
        projectsRetryJob = null
        loadProjects()
    }

    private fun loadProjects() {
        viewModelScope.launch {
            _loadingProjects.value = true
            var retrying = false
            try {
                val resp = api.getProjects()
                if (resp.ok && resp.data != null) {
                    projectsRetries = 0
                    _projects.value = resp.data.map { proj ->
                        if (proj.sessions != null) {
                            proj.copy(sessions = proj.sessions.filter { it.sessionId !in _deletedSessionIds })
                        } else proj
                    }
                } else {
                    _error.value = resp.error?.message ?: resp.error?.code ?: "getProjects failed"
                    retrying = scheduleProjectsRetry()
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Error de red"
                retrying = scheduleProjectsRetry()
            } finally {
                // Si queda un reintento programado se mantiene el spinner: el estado vacío
                // ("No tienes proyectos aún — crea el primero") sería mentira mientras el
                // hub no responde.
                if (!retrying) _loadingProjects.value = false
            }
        }
    }

    /** Programa el siguiente intento (backoff 1s/2s/4s). true = queda algún intento pendiente. */
    private fun scheduleProjectsRetry(): Boolean {
        if (projectsRetries >= PROJECTS_RETRY_DELAYS_MS.size) return false
        val waitMs = PROJECTS_RETRY_DELAYS_MS[projectsRetries]
        projectsRetries++
        projectsRetryJob?.cancel()
        projectsRetryJob = viewModelScope.launch {
            delay(waitMs)
            loadProjects()
        }
        return true
    }

    fun refreshSessions() {
        viewModelScope.launch {
            _loadingSessions.value = true
            try {
                val resp = api.getOpencodeSessions()
                if (resp.ok && resp.data != null) {
                    _sessions.value = resp.data.filter {
                        val rid = it.resolvedId
                        rid !in _deletedSessionIds && (it.id ?: "") !in _deletedSessionIds && (it.ID ?: "") !in _deletedSessionIds
                    }
                } else _error.value = resp.error?.message ?: resp.error?.code ?: "getSessions failed"
            } catch (e: Exception) { _error.value = e.message ?: "Error de red" }
            finally { _loadingSessions.value = false }
        }
    }

    fun createProject(name: String, description: String, provider: String = "opencode") {
        viewModelScope.launch {
            try {
                val resp = api.createProject(CreateProjectRequest(name, description.ifBlank { null }, provider = provider))
                if (resp.ok) refreshProjects() else _error.value = resp.error?.message ?: resp.error?.code
            } catch (e: Exception) { _error.value = e.message }
        }
    }

    /**
     * Vincula una carpeta YA existente como proyecto.
     *
     * El nombre del proyecto es el de la carpeta, que es lo que el usuario elige en el
     * gestor de archivos. Se delega la validación de la ruta al Hub, que exige que
     * resuelva dentro de PROJECTS_ROOT y que la carpeta no esté ya reclamada.
     */
    fun linkFolderAsProject(folderPath: String, provider: String = "opencode") {
        val folder = folderPath.trim().trimEnd('/')
        if (folder.isBlank()) { _error.value = "Ruta de carpeta vacía"; return }
        val name = folder.substringAfterLast('/').ifBlank { "Proyecto" }
        viewModelScope.launch {
            try {
                val resp = api.createProject(
                    CreateProjectRequest(
                        name = name,
                        description = "Carpeta vinculada: $name",
                        provider = provider,
                        folder = folder
                    )
                )
                if (resp.ok) {
                    refreshProjects()
                    _error.value = null
                } else {
                    _error.value = resp.error?.message ?: resp.error?.code ?: "No se pudo vincular la carpeta"
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Error vincular carpeta"
            }
        }
    }

    fun renameProject(id: String, newName: String) {
        viewModelScope.launch {
            try {
                val resp = api.patchProject(id, PatchProjectRequest(name = newName))
                if (resp.ok) refreshProjects() else _error.value = resp.error?.message ?: resp.error?.code
            } catch (e: Exception) { _error.value = e.message }
        }
    }

    fun patchProject(id: String, name: String?, description: String?) {
        viewModelScope.launch {
            try {
                val resp = api.patchProject(id, PatchProjectRequest(name = name, description = description))
                if (resp.ok) refreshProjects() else _error.value = resp.error?.message ?: resp.error?.code
            } catch (e: Exception) { _error.value = e.message }
        }
    }

    fun archiveProject(id: String) {
        viewModelScope.launch {
            try {
                val resp = api.patchProject(id, PatchProjectRequest(archived = true))
                if (resp.ok) refreshProjects() else _error.value = resp.error?.message ?: resp.error?.code
            } catch (e: Exception) { _error.value = e.message }
        }
    }

    fun deleteProject(id: String) {
        viewModelScope.launch {
            try {
                val resp = api.deleteProject(id)
                if (resp.ok) refreshProjects() else _error.value = resp.error?.message ?: resp.error?.code
            } catch (e: Exception) { _error.value = e.message }
        }
    }

fun moveSession(sessionId: String, projectId: String) {
        viewModelScope.launch {
            // F2: una sola escritura via repo (antes: store + api.linkSession, dos veces).
            when (val r = sesiones.vincular(sessionId, projectId, null)) {
                is Resultado.Ok -> refreshAll()
                is Resultado.Fallo -> {
                    _error.value = r.motivo
                    refreshAll()
                }
            }
        }
    }

    fun renameSession(sessionId: String, newTitle: String) {
        viewModelScope.launch {
            // F2: servidor primero via costura (el repo escribe el titulo local solo si
            // confirma). La lista optimista se reconcilia con refreshAll/refreshSessions.
            _sessions.value = _sessions.value.map {
                if (it.resolvedId == sessionId) it.copy(title = newTitle, name = newTitle) else it
            }
            try {
                val resp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    api.renameSession(sessionId, mapOf("title" to newTitle))
                }
                if (resp.ok) {
                    refreshAll()
                } else {
                    _error.value = resp.error?.message ?: resp.error?.code ?: "Error renombrando sesión"
                    refreshSessions()
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Error renombrando sesión"
                refreshSessions()
            }
        }
    }

    fun pinSession(sessionId: String) {
        viewModelScope.launch {
            projectsStore.setSessionPin(sessionId, true)
            val current = _sessions.value
            _sessions.value = current.map {
                if (it.resolvedId == sessionId || it.id == sessionId || it.ID == sessionId) it.copy(pinned = true) else it
            }
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    api.pinSession(sessionId)
                }
            } catch (_: Exception) {}
            refreshSessions()
        }
    }

    fun unpinSession(sessionId: String) {
        viewModelScope.launch {
            projectsStore.setSessionPin(sessionId, false)
            val current = _sessions.value
            _sessions.value = current.map {
                if (it.resolvedId == sessionId || it.id == sessionId || it.ID == sessionId) it.copy(pinned = false) else it
            }
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    api.unpinSession(sessionId)
                }
            } catch (_: Exception) {}
            refreshSessions()
        }
    }

    fun togglePinSession(sessionId: String) {
        val target = _sessions.value.find { it.resolvedId == sessionId || it.id == sessionId || it.ID == sessionId }
        if (target?.pinned == true) {
            unpinSession(sessionId)
        } else {
            pinSession(sessionId)
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            // F2: servidor primero; el filtrado local solo tras confirmar (antes se
            // filtraba antes de saber si el servidor borro).
            try {
                val resp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    api.deleteSession(sessionId)
                }
                if (resp.ok) {
                    _deletedSessionIds.add(sessionId)
                    _sessions.value = _sessions.value.filter {
                        it.resolvedId != sessionId && it.id != sessionId && it.ID != sessionId && it.resolvedId !in _deletedSessionIds
                    }
                    _projects.value = _projects.value.map { proj ->
                        if (proj.sessions != null) {
                            proj.copy(sessions = proj.sessions.filter { it.sessionId != sessionId && it.sessionId !in _deletedSessionIds })
                        } else proj
                    }
                    refreshAll()
                } else {
                    _error.value = resp.error?.message ?: resp.error?.code ?: "Error eliminando sesión"
                    refreshSessions()
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Error eliminando sesión"
                refreshSessions()
            }
        }
    }

    suspend fun createSessionForProject(projectId: String, title: String): String? {
        return try {
            val proj = _projects.value.find { it.id == projectId }
            val effectiveProjectId = projectId.trim().ifBlank { null }
            val effectiveFolder = proj?.folder ?: proj?.resolvedFolder
            // F2: el repo es el unico camino (1 POST, aviso si algo parcial falla).
            when (
                val r = sesiones.crear(
                    NuevaSesion(
                        titulo = title,
                        proyectoId = effectiveProjectId,
                        carpeta = effectiveFolder?.takeIf { it.isNotBlank() }
                    ),
                    claveIdempotencia = "$title|$effectiveProjectId"
                )
            ) {
                is Resultado.Ok -> {
                    if (r.avisos.isNotEmpty()) _error.value = r.avisos.joinToString("\n")
                    refreshSessions()
                    refreshProjects()
                    r.valor.id
                }
                is Resultado.Fallo -> {
                    _error.value = r.motivo
                    null
                }
            }
        } catch (e: Exception) {
            _error.value = e.message
            null
        }
    }
}
