package com.aegis.hub.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aegis.hub.data.Conexion
import com.aegis.hub.data.HealthData
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ControlCenterViewModel : ViewModel() {
    private val _health = MutableStateFlow<HealthData?>(null)
    val health: StateFlow<HealthData?> = _health.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var autoRefreshJob: Job? = null

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val response = Conexion.api.getSystemHealth()
                if (response.isSuccessful) {
                    _health.value = response.body()?.data
                } else {
                    _error.value = "Error: ${response.code()}"
                }
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun startAutoRefresh() {
        if (autoRefreshJob?.isActive == true) return
        autoRefreshJob = viewModelScope.launch {
            while (true) {
                ensureActive()
                refresh()
                delay(10_000)
            }
        }
    }

    fun stopAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    override fun onCleared() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
        super.onCleared()
    }
}
