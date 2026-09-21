package com.opencode.companion.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.companion.data.*
import okhttp3.MediaType.Companion.toMediaType
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

    init { refreshAll() }

    fun refreshAll() {
        refreshProjects()
        refreshSessions()
    }

    fun clearError() { _error.value = null }

    private val _deletedSessionIds = mutableSetOf<String>()

    fun refreshProjects() {
        viewModelScope.launch {
            _loadingProjects.value = true
            try {
                val resp = api.getProjects()
                if (resp.ok && resp.data != null) {
                    _projects.value = resp.data.map { proj ->
                        if (proj.sessions != null) {
                            proj.copy(sessions = proj.sessions.filter { it.sessionId !in _deletedSessionIds })
                        } else proj
                    }
                } else _error.value = resp.error ?: "getProjects failed"
            } catch (e: Exception) { _error.value = e.message ?: "Error de red" }
            finally { _loadingProjects.value = false }
        }
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
                } else _error.value = resp.error ?: "getSessions failed"
            } catch (e: Exception) { _error.value = e.message ?: "Error de red" }
            finally { _loadingSessions.value = false }
        }
    }

    fun createProject(name: String, description: String, provider: String = "opencode") {
        viewModelScope.launch {
            try {
                val resp = api.createProject(CreateProjectRequest(name, description.ifBlank { null }, provider = provider))
                if (resp.ok) refreshProjects() else _error.value = resp.error
            } catch (e: Exception) { _error.value = e.message }
        }
    }

    fun renameProject(id: String, newName: String) {
        viewModelScope.launch {
            try {
                val resp = api.patchProject(id, PatchProjectRequest(name = newName))
                if (resp.ok) refreshProjects() else _error.value = resp.error
            } catch (e: Exception) { _error.value = e.message }
        }
    }

    fun patchProject(id: String, name: String?, description: String?) {
        viewModelScope.launch {
            try {
                val resp = api.patchProject(id, PatchProjectRequest(name = name, description = description))
                if (resp.ok) refreshProjects() else _error.value = resp.error
            } catch (e: Exception) { _error.value = e.message }
        }
    }

    fun archiveProject(id: String) {
        viewModelScope.launch {
            try {
                val resp = api.patchProject(id, PatchProjectRequest(archived = true))
                if (resp.ok) refreshProjects() else _error.value = resp.error
            } catch (e: Exception) { _error.value = e.message }
        }
    }

    fun deleteProject(id: String) {
        viewModelScope.launch {
            try {
                val resp = api.deleteProject(id)
                if (resp.ok) refreshProjects() else _error.value = resp.error
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
                if (resp.ok) refreshAll() else _error.value = resp.error ?: "move failed"
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
                    _error.value = resp.error ?: "Error renombrando sesión"
                    refreshSessions()
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Error renombrando sesión"
                refreshSessions()
            }
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
                    _error.value = resp.error ?: "Error eliminando sesión"
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
            val provider = proj?.provider ?: "opencode"
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

    private suspend fun createSessionViaHub(title: String, projectId: String? = null, provider: String = "opencode"): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val pIdStr = if (!projectId.isNullOrBlank()) "\"$projectId\"" else "null"
            val bodyJson = "{\"title\":\"${title.replace("\"","\\\"")}\",\"projectId\":$pIdStr,\"provider\":\"$provider\"}"
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
        } catch (_: Exception) { null }
    }
}
