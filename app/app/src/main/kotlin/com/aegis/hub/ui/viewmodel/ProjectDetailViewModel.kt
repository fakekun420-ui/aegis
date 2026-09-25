package com.aegis.hub.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aegis.hub.data.*
import okhttp3.MediaType.Companion.toMediaType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ProjectDetailViewModel : ViewModel() {
    private val api = ApiClient.service

    private val _project = MutableStateFlow<Project?>(null)
    val project: StateFlow<Project?> = _project

    private val _sessions = MutableStateFlow<List<SessionRef>>(emptyList())
    val sessions: StateFlow<List<SessionRef>> = _sessions

    private val _skills = MutableStateFlow<List<Skill>>(emptyList())
    val skills: StateFlow<List<Skill>> = _skills

    private val _linkedProjects = MutableStateFlow<List<Project>>(emptyList())
    val linkedProjects: StateFlow<List<Project>> = _linkedProjects

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun clearError() { _error.value = null }

    fun patchInstructions(instructions: String) {
        viewModelScope.launch {
            val pid = _project.value?.id ?: return@launch
            try {
                val resp = api.patchProject(pid, PatchProjectRequest(description = instructions))
                if (resp.ok && resp.data != null) {
                    _project.value = resp.data
                } else {
                    load(pid)
                }
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    private val _deletedSessionIds = mutableSetOf<String>()

    fun load(projectId: String) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                // Fetch projects list to find this project, plus its sessions/skills
                val projResp = api.getProjects()
                val proj = projResp.data?.find { it.id == projectId }
                _project.value = proj
                // Sessions for project
                try {
                    val sResp = api.getProjectSessions(projectId)
                    if (sResp.ok && sResp.data != null) {
                        _sessions.value = sResp.data.filter { it.sessionId !in _deletedSessionIds }
                    }
                } catch (_: Exception) {}
                // Skills
                try {
                    val skResp = api.getSkills(projectId)
                    if (skResp.ok && skResp.data != null) _skills.value = skResp.data.skills
                } catch (_: Exception) {}
                // Linked projects full objects
                val allProjects = projResp.data ?: emptyList()
                val linkedIds = proj?.linkedProjects ?: emptyList()
                _linkedProjects.value = allProjects.filter { it.id in linkedIds }
            } catch (e: Exception) { _error.value = e.message ?: "Error de red" }
            finally { _loading.value = false }
        }
    }

    fun sendNewSession(projectId: String, text: String, onCreated: (String) -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // Create session via hub
                val provider = _project.value?.provider ?: "antigravity"
                val title = "companion:${_project.value?.name ?: projectId}:${System.currentTimeMillis() % 100000}"
                val bodyJson = "{\"title\":\"${title.replace("\"","\\\"")}\",\"projectId\":\"$projectId\",\"provider\":\"$provider\",\"model\":\"gemini-3.8-flash-high\"}"
                val req = okhttp3.Request.Builder()
                    .url("http://127.0.0.1:8765/opencode/session")
                    .header("X-Provider", provider)
                    .header("X-Project-Id", projectId)
                    .post(okhttp3.RequestBody.create("application/json".toMediaType(), bodyJson))
                    .build()
                val resp = ApiClient.rawOkHttp.newCall(req).execute()
                val body = resp.body?.string() ?: return@launch
                val sid = try {
                    val j = com.google.gson.JsonParser.parseString(body).asJsonObject
                    when {
                        j.has("id") -> j.get("id").asString
                        j.has("ID") -> j.get("ID").asString
                        j.has("sessionId") -> j.get("sessionId").asString
                        j.has("data") -> {
                            val d = j.getAsJsonObject("data")
                            when {
                                d.has("id") -> d.get("id").asString
                                d.has("ID") -> d.get("ID").asString
                                d.has("sessionId") -> d.get("sessionId").asString
                                else -> null
                            }
                        }
                        else -> null
                    }
                } catch (_: Exception) { null } ?: return@launch

                // Link to project (if not automatically linked)
                try {
                    api.linkSession(projectId, LinkSessionRequest(sessionId = sid, title = title, provider = provider))
                } catch (_: Exception) {}

                // Optimistically add to local sessions list
                val newRef = SessionRef(sessionId = sid, title = title, createdAt = java.time.Instant.now().toString(), provider = provider)
                _sessions.value = listOf(newRef) + _sessions.value.filter { it.sessionId != sid }

                // Navigate immediately on Main dispatcher
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onCreated(sid)
                }

                // Send first message asynchronously if present without blocking navigation
                if (text.isNotBlank()) {
                    try {
                        api.sendMessage(
                            sessionId = sid,
                            body = SendMessageRequest(parts = listOf(mapOf("type" to "text", "text" to text))),
                            provider = provider,
                            projectId = projectId
                        )
                    } catch (msgErr: Exception) {
                        // ignore or log
                    }
                }
                load(projectId)
            } catch (e: Exception) {
                _error.value = e.message ?: "Error creando sesión"
            }
        }
    }

    fun renameSession(sessionId: String, newTitle: String) {
        viewModelScope.launch {
            val pid = _project.value?.id ?: return@launch
            // Optimistic update
            val cur = _sessions.value
            _sessions.value = cur.map { if (it.sessionId == sessionId) it.copy(title = newTitle) else it }
            try {
                val resp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    api.renameSession(sessionId, mapOf("title" to newTitle))
                }
                if (resp.ok) {
                    load(pid)
                } else {
                    _error.value = resp.error?.message ?: resp.error?.code ?: "Error renombrando sesión"
                    load(pid)
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Error renombrando sesión"
                load(pid)
            }
        }
    }

    fun unlinkSession(sessionId: String) {
        viewModelScope.launch {
            val pid = _project.value?.id ?: return@launch
            // Optimistic removal
            val cur = _sessions.value
            _sessions.value = cur.filter { it.sessionId != sessionId }
            try {
                val resp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    api.unlinkSession(pid, sessionId)
                }
                if (resp.ok) {
                    load(pid)
                } else {
                    _error.value = resp.error?.message ?: resp.error?.code ?: "Error desvinculando sesión"
                    load(pid)
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Error desvinculando sesión"
                load(pid)
            }
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            val pid = _project.value?.id ?: return@launch
            _deletedSessionIds.add(sessionId)
            // Optimistic removal
            val cur = _sessions.value
            _sessions.value = cur.filter { it.sessionId != sessionId && it.sessionId !in _deletedSessionIds }
            try {
                val resp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    api.deleteSession(sessionId)
                }
                if (resp.ok) {
                    load(pid)
                } else {
                    _error.value = resp.error?.message ?: resp.error?.code ?: "Error eliminando sesión"
                    load(pid)
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Error eliminando sesión"
                load(pid)
            }
        }
    }

    fun createSkill(scope: String, name: String, content: String) {
        viewModelScope.launch {
            try {
                val realScope = if (scope == "project") _project.value?.id ?: "global" else "global"
                val resp = api.createSkill(SkillCreateRequest(scope = realScope, name = name, content = content))
                if (resp.ok) load(_project.value?.id ?: return@launch) else _error.value = resp.error?.message ?: resp.error?.code
            } catch (e: Exception) { _error.value = e.message }
        }
    }

    fun deleteSkill(scope: String, name: String) {
        viewModelScope.launch {
            try {
                val resp = api.deleteSkill(scope, name)
                if (resp.ok) load(_project.value?.id ?: return@launch) else _error.value = resp.error?.message ?: resp.error?.code
            } catch (e: Exception) { _error.value = e.message }
        }
    }

    fun linkProject(targetId: String) {
        viewModelScope.launch {
            val pid = _project.value?.id ?: return@launch
            val cur = _project.value?.linkedProjects ?: emptyList()
            if (targetId in cur) return@launch
            try {
                // PATCH via direct OkHttp (no typed PATCH for linkedProjects array alone)
                val next = cur + targetId
                val json = com.google.gson.Gson().toJson(mapOf("linkedProjects" to next))
                val req = okhttp3.Request.Builder()
                    .url("http://127.0.0.1:8765/api/projects/${pid}")
                    .patch(okhttp3.RequestBody.create("application/json".toMediaType(), json))
                    .build()
                ApiClient.rawOkHttp.newCall(req).execute().use { }
                load(pid)
            } catch (e: Exception) { _error.value = e.message }
        }
    }

    fun unlinkProject(targetId: String) {
        viewModelScope.launch {
            val pid = _project.value?.id ?: return@launch
            val next = (_project.value?.linkedProjects ?: emptyList()).filter { it != targetId }
            try {
                val json = com.google.gson.Gson().toJson(mapOf("linkedProjects" to next))
                val req = okhttp3.Request.Builder()
                    .url("http://127.0.0.1:8765/api/projects/${pid}")
                    .patch(okhttp3.RequestBody.create("application/json".toMediaType(), json))
                    .build()
                ApiClient.rawOkHttp.newCall(req).execute().use { }
                load(pid)
            } catch (e: Exception) { _error.value = e.message }
        }
    }
}
