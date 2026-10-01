package com.aegis.hub.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aegis.hub.data.*
import okhttp3.MediaType.Companion.toMediaType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.aegis.hub.data.TurnState

class MainViewModel : ViewModel() {
    private val api = ApiClient.service
    private val openCodeApi: OpenCodeApi = OpenCodeApi.default
    private val projectsStore: ProjectsStore = ProjectsStore.default

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

    fun applyActiveSessions(activeMap: Map<String, ActiveSessionStatus>) {
        // Sesiones ocupadas segun OpenCode: { type: "running" }
        val busyNow = activeMap.filter { it.value.type == "running" }.keys
        val previousBusy = _inflightIds.value
        _inflightIds.value = busyNow

        val now = System.currentTimeMillis()
        // Sesiones que estaban ocupadas y ya no lo estan pasan a terminadas temporalmente
        for (sid in previousBusy) {
            if (sid !in busyNow) {
                finishedAt[sid] = now
            }
        }
        finishedAt.keys.retainAll { id ->
            now - (finishedAt[id] ?: 0L) < FINISHED_TTL_MS
        }
        _finishedIds.value = finishedAt.keys.toSet()
    }

    private var inflightJob: Job? = null

    init {
        refreshAll()
        startInflightPolling()
    }

    /**
     * Sondeo con backoff contra /api/session/active de OpenCode.
     * Si falla, conmuta al fallback del Hub (/api/sessions/inflight) si estuviera disponible,
     * y si ambos fallan marca desconocido con la edad real.
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
                    val activeMap = openCodeApi.getActiveSessions()
                    applyActiveSessions(activeMap)
                    ok = true
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
                    try {
                        val r = api.getInflight()
                        if (r.ok && r.data != null) {
                            val rows = r.data
                            _inflightIds.value = rows.filter { TurnState.isBusy(it) }.mapNotNull { it.id }.toSet()
                            val now = System.currentTimeMillis()
                            for (row in rows) {
                                val id = row.id ?: continue
                                if (TurnState.isOver(row)) finishedAt[id] = now
                            }
                            finishedAt.keys.retainAll { id -> now - (finishedAt[id] ?: 0L) < FINISHED_TTL_MS }
                            _finishedIds.value = finishedAt.keys.toSet()
                            ok = true
                            ultimoAciertoMs = android.os.SystemClock.elapsedRealtime()
                        }
                    } catch (_: Exception) {}

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
                val activeMap = openCodeApi.getActiveSessions()
                applyActiveSessions(activeMap)
                ultimoAciertoMs = android.os.SystemClock.elapsedRealtime()
                _inflightFiable.value = calcularEsFiable()
            } catch (e: Exception) {
                try {
                    val r = api.getInflight()
                    if (r.ok && r.data != null) {
                        val rows = r.data
                        _inflightIds.value = rows.filter { TurnState.isBusy(it) }.mapNotNull { it.id }.toSet()
                        ultimoAciertoMs = android.os.SystemClock.elapsedRealtime()
                        _inflightFiable.value = calcularEsFiable()
                    }
                } catch (_: Exception) {
                    android.util.Log.w(
                        "AegisChats",
                        "refreshInflightNow fallo: " + e.javaClass.simpleName + ": " + e.message
                    )
                }
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
            try {
                projectsStore.linkSessionToProject(sessionId, projectId)
                val currentSession = _sessions.value.find { it.resolvedId == sessionId || it.id == sessionId || it.ID == sessionId }
                val currentTitle = currentSession?.resolvedTitle
                val currentProvider = currentSession?.provider
                try {
                    api.linkSession(
                        projectId,
                        LinkSessionRequest(sessionId = sessionId, title = currentTitle, provider = currentProvider)
                    )
                } catch (_: Exception) {}
                refreshAll()
            } catch (e: Exception) {
                _error.value = e.message ?: "Error de red"
            }
        }
    }

    fun renameSession(sessionId: String, newTitle: String) {
        viewModelScope.launch {
            projectsStore.setSessionTitle(sessionId, newTitle)
            val current = _sessions.value
            _sessions.value = current.map {
                if (it.resolvedId == sessionId) it.copy(title = newTitle, name = newTitle) else it
            }
            try {
                val resp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    openCodeApi.updateSession(sessionId, UpdateOpenCodeSessionRequest(title = newTitle))
                }
                if (resp.isSuccessful) {
                    refreshAll()
                } else {
                    _error.value = "Error renombrando sesion (${resp.code()})"
                    refreshSessions()
                }
            } catch (e: Exception) {
                try {
                    val respHub = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        api.renameSession(sessionId, mapOf("title" to newTitle))
                    }
                    if (respHub.ok) refreshAll() else refreshSessions()
                } catch (_: Exception) {
                    _error.value = e.message ?: "Error renombrando sesion"
                    refreshSessions()
                }
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
            _deletedSessionIds.add(sessionId)
            val current = _sessions.value
            _sessions.value = current.filter {
                it.resolvedId != sessionId && it.id != sessionId && it.ID != sessionId && it.resolvedId !in _deletedSessionIds
            }
            _projects.value = _projects.value.map { proj ->
                if (proj.sessions != null) {
                    proj.copy(sessions = proj.sessions.filter { it.sessionId != sessionId && it.sessionId !in _deletedSessionIds })
                } else proj
            }
            try {
                val resp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    openCodeApi.deleteSession(sessionId)
                }
                if (resp.isSuccessful) {
                    refreshAll()
                } else {
                    try { api.deleteSession(sessionId) } catch (_: Exception) {}
                    refreshAll()
                }
            } catch (e: Exception) {
                try { api.deleteSession(sessionId) } catch (_: Exception) {}
                refreshSessions()
            }
        }
    }

    suspend fun createSessionForProject(projectId: String, title: String, providerOverride: String? = null): String? {
        return try {
            val proj = _projects.value.find { it.id == projectId }
            val effectiveProjectId = projectId.trim().ifBlank { null }
            val effectiveFolder = proj?.folder ?: proj?.resolvedFolder
            val location = if (!effectiveFolder.isNullOrBlank()) OpenCodeLocation(directory = effectiveFolder) else null

            val createdSession = try {
                openCodeApi.createSession(
                    CreateOpenCodeSessionRequest(
                        title = title,
                        location = location
                    )
                )
            } catch (_: Exception) {
                null
            }

            val sid = createdSession?.id ?: createSessionViaHub(title, effectiveProjectId, providerOverride ?: proj?.provider ?: "opencode")

            if (sid != null) {
                if (effectiveProjectId != null) {
                    projectsStore.linkSessionToProject(sid, effectiveProjectId)
                    try { api.linkSession(effectiveProjectId, LinkSessionRequest(sessionId = sid, title = title, provider = "opencode")) } catch (_: Exception) {}
                }
                projectsStore.setSessionTitle(sid, title)
                refreshSessions()
                refreshProjects()
            }
            sid
        } catch (e: Exception) {
            _error.value = e.message
            null
        }
    }

    private suspend fun createSessionViaHub(title: String, projectId: String? = null, provider: String = "opencode"): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val pIdStr = if (!projectId.isNullOrBlank()) f'"{projectId}"' else "null"
            val bodyJson = "{"title":"" + title.replace(""", "\"") + "","projectId":" + pIdStr + ","provider":"" + provider + ""}"
            val req = okhttp3.Request.Builder()
                .url("http://127.0.0.1:8765/opencode/session")
                .header("X-Provider", provider)
                .apply { if (!projectId.isNullOrBlank()) header("X-Project-Id", projectId) }
                .post(okhttp3.RequestBody.create("application/json".toMediaType(), bodyJson))
                .build()
            val resp = ApiClient.rawOkHttp.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext null
            val json = com.google.gson.JsonParser.parseString(body).asJsonObject
            when {
                json.has("id") -> json.get("id").asString
                json.has("ID") -> json.get("ID").asString
                json.has("data") -> {
                    val d = json.getAsJsonObject("data")
                    when { d.has("id") -> d.get("id").asString; d.has("ID") -> d.get("ID").asString; else -> null }
                }
                else -> null
            }
        } catch (e: Exception) {
            _error.value = "No se pudo crear la sesion: " + (e.message ?: e::class.simpleName)
            null
        }
    }
}
