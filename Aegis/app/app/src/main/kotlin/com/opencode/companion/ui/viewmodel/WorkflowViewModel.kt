package com.opencode.companion.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.companion.data.ApiClient
import com.opencode.companion.data.RunWorkflowRequest
import com.opencode.companion.data.WorkflowItem
import com.opencode.companion.data.WorkflowStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class WorkflowViewModel : ViewModel() {
    private val _workflows = MutableStateFlow<List<WorkflowItem>>(emptyList())
    val workflows: StateFlow<List<WorkflowItem>> = _workflows.asStateFlow()
    
    private val _status = MutableStateFlow<WorkflowStatus?>(null)
    val status: StateFlow<WorkflowStatus?> = _status.asStateFlow()
    
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    fun loadWorkflows(projectId: String) {
        viewModelScope.launch {
            try {
                val res = ApiClient.service.getWorkflows(projectId)
                if (res.isSuccessful) {
                    _workflows.value = res.body()?.data ?: emptyList()
                }
            } catch (e: Exception) {
            }
        }
    }

    fun runWorkflow(projectId: String, workflowId: String) {
        viewModelScope.launch {
            try {
                _isRunning.value = true
                val req = RunWorkflowRequest(workflowId)
                ApiClient.service.runWorkflow(projectId, req)
                pollStatus(projectId)
            } catch (e: Exception) {
                _isRunning.value = false
            }
        }
    }

    fun pollStatus(projectId: String) {
        viewModelScope.launch {
            while (_isRunning.value) {
                try {
                    val res = ApiClient.service.getWorkflowStatus(projectId)
                    if (res.isSuccessful) {
                        val st = res.body()?.data
                        _status.value = st
                        if (st?.status == "completed" || st?.status == "failed") {
                            _isRunning.value = false
                        }
                    }
                } catch (e: Exception) {
                }
                delay(3000)
            }
        }
    }
}
