package com.aegis.hub.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aegis.hub.data.ApiClient
import com.aegis.hub.data.ProjectItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class WorkspaceViewModel : ViewModel() {
    private val _projects = MutableStateFlow<List<ProjectItem>>(emptyList())
    val projects: StateFlow<List<ProjectItem>> = _projects.asStateFlow()
    
    private val _selectedProject = MutableStateFlow<ProjectItem?>(null)
    val selectedProject: StateFlow<ProjectItem?> = _selectedProject.asStateFlow()

    fun loadProjects() {
        viewModelScope.launch {
            try {
                val res = ApiClient.service.getWorkspaceProjects()
                if (res.isSuccessful) {
                    _projects.value = res.body()?.data ?: emptyList()
                }
            } catch (e: Exception) {
            }
        }
    }

    fun initProject(projectId: String) {
        viewModelScope.launch {
            try {
                ApiClient.service.initProject(projectId)
                loadProjects()
            } catch (e: Exception) {
            }
        }
    }

    fun indexProject(projectId: String) {
        viewModelScope.launch {
            try {
                ApiClient.service.indexProject(projectId)
                loadProjects()
            } catch (e: Exception) {
            }
        }
    }

    fun selectProject(project: ProjectItem) {
        _selectedProject.value = project
    }
}
