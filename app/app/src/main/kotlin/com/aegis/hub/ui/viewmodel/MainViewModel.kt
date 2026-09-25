package com.aegis.hub.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aegis.hub.data.*
import okhttp3.MediaType.Companion.toMediaType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {
    private val api = ApiClient.service

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

    // Declarado DESPUÉS de las propiedades que usa: en Kotlin los init blocks y los
    // initializers se ejecutan en orden de declaración, y con Dispatchers.Main.immediate
    // el cuerpo de refreshProjects() puede correr de forma síncrona dentro de refreshAll().
    init { refreshAll() }

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
                val currentSession = _sessions.value.find { it.resolvedId == sessionId || it.id == sessionId || it.ID == sessionId }
                val currentTitle = currentSession?.resolvedTitle
                val currentProvider = currentSession?.provider
                val resp = api.linkSession(
                    projectId,
                    LinkSessionRequest(sessionId = sessionId, title = currentTitle, provider = currentProvider)
                )
                if (resp.ok) refreshAll() else _error.value = resp.error?.message ?: resp.error?.code ?: "move failed"
            } catch (e: Exception) { _error.value = e.message ?: "Error de red" }
        }
    }

    fun renameSession(sessionId: String, newTitle: String) {
        viewModelScope.launch {
            // Optimistic in-memory update
            val current = _sessions.value
            _sessions.value = current.map {
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
            // Optimistic update
            val current = _sessions.value
            _sessions.value = current.map {
                if (it.resolvedId == sessionId || it.id == sessionId || it.ID == sessionId) it.copy(pinned = true) else it
            }
            try {
                val resp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    api.pinSession(sessionId)
                }
                if (resp.ok) {
                    refreshSessions()
                } else {
                    _error.value = resp.error?.message ?: resp.error?.code ?: "Error fijando sesión"
                    refreshSessions()
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Error fijando sesión"
                refreshSessions()
            }
        }
    }

    fun unpinSession(sessionId: String) {
        viewModelScope.launch {
            // Optimistic update
            val current = _sessions.value
            _sessions.value = current.map {
                if (it.resolvedId == sessionId || it.id == sessionId || it.ID == sessionId) it.copy(pinned = false) else it
            }
            try {
                val resp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    api.unpinSession(sessionId)
                }
                if (resp.ok) {
                    refreshSessions()
                } else {
                    _error.value = resp.error?.message ?: resp.error?.code ?: "Error desfijando sesión"
                    refreshSessions()
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Error desfijando sesión"
                refreshSessions()
            }
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
            // Optimistic in-memory removal from sessions list
            val current = _sessions.value
            _sessions.value = current.filter {
                it.resolvedId != sessionId && it.id != sessionId && it.ID != sessionId && it.resolvedId !in _deletedSessionIds
            }
            // Optimistic removal from projects sessions list
            _projects.value = _projects.value.map { proj ->
                if (proj.sessions != null) {
                    proj.copy(sessions = proj.sessions.filter { it.sessionId != sessionId && it.sessionId !in _deletedSessionIds })
                } else proj
            }
            try {
                val resp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    api.deleteSession(sessionId)
                }
                if (resp.ok) {
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

    suspend fun createSessionForProject(projectId: String, title: String, providerOverride: String? = null): String? {
        return try {
            val proj = _projects.value.find { it.id == projectId }
            // providerOverride gana: desde la lista de chats no hay proyecto asociado
            // (projectId = ""), así que sin esto TODOS los chats nuevos nacían como
            // "antigravity" sin importar lo que el usuario hubiera elegido.
            val provider = providerOverride?.takeIf { it.isNotBlank() }
                ?: proj?.provider
                ?: "antigravity"
            val effectiveProjectId = projectId.trim().ifBlank { null }
            val sid = createSessionViaHub(title, effectiveProjectId, provider)
            if (sid != null) {
                if (effectiveProjectId != null) {
                    try { api.linkSession(effectiveProjectId, LinkSessionRequest(sessionId = sid, title = title, provider = provider)) } catch (_: Exception) {}
                }
                refreshSessions(); refreshProjects()
            }
            sid
        } catch (e: Exception) { _error.value = e.message; null }
    }

    private suspend fun createSessionViaHub(title: String, projectId: String? = null, provider: String = "antigravity"): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val pIdStr = if (!projectId.isNullOrBlank()) "\"$projectId\"" else "null"
            val bodyJson = "{\"title\":\"${title.replace("\"","\\\"")}\",\"projectId\":$pIdStr,\"provider\":\"$provider\",\"model\":\"gemini-3.8-flash-high\"}"
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
            // Antes devolvía null en silencio: el botón de nuevo chat no navegaba y el
            // usuario no veía POR QUÉ. Ahora el motivo queda en _error, que ChatsScreen
            // ya pinta.
            _error.value = "No se pudo crear la sesión: ${e.message ?: e::class.simpleName}"
            null
        }
    }
}
