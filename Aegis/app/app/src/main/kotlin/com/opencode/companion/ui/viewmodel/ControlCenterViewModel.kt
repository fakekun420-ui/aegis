package com.opencode.companion.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.companion.data.ApiClient
import com.opencode.companion.data.HealthData
import kotlinx.coroutines.delay
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

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val response = ApiClient.service.getSystemHealth()
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
        viewModelScope.launch {
            while (true) {
                refresh()
                delay(10_000)
            }
        }
    }
}
