package com.opencode.companion.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.companion.data.*
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

    init { refreshAll() }

    fun refreshAll() {
        refreshProjects()
        refreshSessions()
    }

    fun refreshProjects() {
        viewModelScope.launch {
            try {
                val resp = api.getProjects()
                if (resp.ok && resp.data != null) _projects.value = resp.data
                else _error.value = resp.error ?: "getProjects failed"
            } catch (e: Exception) { _error.value = e.message }
        }
    }

    fun refreshSessions() {
        viewModelScope.launch {
            try {
                val resp = api.getOpencodeSessions()
                if (resp.ok && resp.data != null) _sessions.value = resp.data
                else _error.value = resp.error ?: "getSessions failed"
            } catch (e: Exception) { _error.value = e.message }
        }
    }

    fun createProject(name: String, description: String) {
        viewModelScope.launch {
            try {
                val resp = api.createProject(CreateProjectRequest(name, description.ifBlank { null }))
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
                val resp = api.linkSession(projectId, LinkSessionRequest(sessionId = sessionId))
                if (resp.ok) refreshProjects() else _error.value = resp.error
            } catch (e: Exception) { _error.value = e.message }
        }
    }
}
