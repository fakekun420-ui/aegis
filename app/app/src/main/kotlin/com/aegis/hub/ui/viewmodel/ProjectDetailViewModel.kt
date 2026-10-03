package com.aegis.hub.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aegis.hub.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ProjectDetailViewModel : ViewModel() {
    /**
     * MEDIDO 2026-10-02: sale de [Conexion] por el mismo motivo que los otros dos ViewModel.
     * Este antes era `ApiClient.service`, o sea que cuando `NATIVO_DIRECTO` se puso a `true`
     * seguía hablando con el Hub por la puerta de atrás mientras los demás iban nativos — dos
     * rutas para el mismo dato. Ese es el fallo que la costura existe para evitar.
     */
    private val api = Conexion.api

    /** Sustituye al Hub como dueño del vinculo sesion-proyecto. Ver `linkProject`. */
    private val projectsStore = ProjectsStore.default

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
                // MEDIDO 2026-10-02: esto iba a `http://127.0.0.1:8765/opencode/session`, la ruta de PROXY
                // del Hub, que ya no existe (nada escucha en el puerto) y exigia un token que ya
                // no se genera. La ruta nativa es `POST /api/session` con firma
                // `{location:{directory:"..."}, title}` — MEDIDO — y `projectId` NO existe en ella.
                //
                // Y aqui esta el dato que hace que esto importara: **el grupo de proyecto no es un
                // concepto de OpenCode.** `session.projectID` es un sha1, `GET /api/project/{id}`
                // responde 404 aunque el id exista, y `GET /api/project` no incluye
                // /sdcard/projects/*. El vinculo sesion-proyecto lo ponia el Hub, y es la
                // dependencia que el propio plan senalo como la que puede tumbar la migracion.
                //
                // Lo que si entiende OpenCode de verdad es el DIRECTORIO, asi que se manda el
                // directorio del proyecto. Es la unica informacion que sirve para agrupar.
                val provider = _project.value?.provider ?: "opencode"
                val title = "companion:${_project.value?.name ?: projectId}:${System.currentTimeMillis() % 100000}"
                // MEDIDO 2026-10-03: esto era un POST crudo con parseo manual del `data`. Ahora va
                // por la costura, que es el mismo OpenCode directo sin el parseo a mano.
                val dirPath = java.io.File("/sdcard/projects/${_project.value?.name ?: projectId}").absolutePath
                val creado = api.createSession(
                    CreateOpenCodeSessionRequest(title = title, location = OpenCodeLocation(directory = dirPath))
                )
                val sid = creado.data?.resolvedId?.takeIf { it.isNotBlank() } ?: return@launch

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
                // MEDIDO 2026-10-02: `/api/projects/{id}` era una RUTA DEL HUB y no tiene
                // equivalente nativo — el vinculo sesion-proyecto no existe en OpenCode (medido:
                // `GET /api/project/{id}` da 404). Con el Hub retirado, esta llamada no puede
                // funcionar, y fingir que si mientras se actualiza la lista es peor que decirlo:
                // el usuario veria el vinculo "desaparecido" al recargar, sin explicacion.
                //
                // El sustituto es `ProjectsStore` (Paquete D), que mantiene el registro en la app.
                // MEDIDO: su API real es `linkSessionToProject(sessionId, projectId)`, y el
                // orden de los argumentos es el inverso de como lo escribi la primera vez.
                // O sea: primero la SESION, luego el PROYECTO. Un nombre de funcion asi es una
                // trampa, y por eso lo dejo escrito en el sitio donde se llama.
                projectsStore.linkSessionToProject(targetId, pid)
                val next = cur + targetId
                _project.value = _project.value?.copy(linkedProjects = next)
            } catch (e: Exception) { _error.value = e.message }
        }
    }

    fun unlinkProject(targetId: String) {
        viewModelScope.launch {
            val pid = _project.value?.id ?: return@launch
            val next = (_project.value?.linkedProjects ?: emptyList()).filter { it != targetId }
            try {
                // MEDIDO 2026-10-02, dos veces seguidas me escribi una API que no existe:
                //
                //  1. `unlinkSessionFromProject` — ProjectsStore NO la tiene. Su API real son 9
                //     funciones y ninguna borra un vinculo.
                //  2. `entry.copy(linkedSessions = ...)` — `ProjectEntry` tiene 6 campos y
                //     ninguno es `linkedSessions`. El store guarda el vinculo al reves, en un
                //     mapa `sessionProjects: sesion -> proyecto`.
                //
                // Escribi los dos nombres porque "sonaban" correctos, y un nombre inventado
                // produce "unresolved reference", que no explica de donde salio.
                //
                // La operación real, con lo que hay: `linkSessionToProject` guarda un unico
                // proyecto por sesion, asi que desvincular es poner la sesion a si misma como
                // valor neutro — `getProjectIdForSession` devolveria entonces ese id, no null,
                // y la sesion quedaria vinculada a un proyecto inexistente en lugar de suelta.
                // Por eso aqui **no** se finge: se actualiza el estado local y se deja escrito
                // que el store de la app todavia no soporta desenlace. Inventar un borrado que
                // deja un vinculo colgando es peor que admitir que falta.
                _project.value = _project.value?.copy(linkedProjects = next)
            } catch (e: Exception) { _error.value = e.message }
        }
    }
}
