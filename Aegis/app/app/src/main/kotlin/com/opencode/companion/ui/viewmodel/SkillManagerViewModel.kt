package com.opencode.companion.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.companion.data.ApiClient
import com.opencode.companion.data.InstallSkillRequest
import com.opencode.companion.data.SkillsData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SkillManagerViewModel : ViewModel() {
    private val _skills = MutableStateFlow<SkillsData?>(null)
    val skills: StateFlow<SkillsData?> = _skills.asStateFlow()
    
    private val _installingId = MutableStateFlow<String?>(null)
    val installingId: StateFlow<String?> = _installingId.asStateFlow()
    
    private val _installLog = MutableStateFlow<List<String>>(emptyList())
    val installLog: StateFlow<List<String>> = _installLog.asStateFlow()

    fun loadSkills() {
        viewModelScope.launch {
            try {
                val response = ApiClient.service.getSystemSkills()
                if (response.isSuccessful) {
                    _skills.value = response.body()?.data
                }
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    fun installSkill(skillId: String) {
        viewModelScope.launch {
            _installingId.value = skillId
            _installLog.value = listOf("Starting installation of $skillId...")
            try {
                val req = InstallSkillRequest(skillId)
                val response = ApiClient.service.installSkill(req)
                if (response.isSuccessful) {
                    _installLog.value = _installLog.value + "Installation complete."
                } else {
                    _installLog.value = _installLog.value + "Error: ${response.code()}"
                }
            } catch (e: Exception) {
                _installLog.value = _installLog.value + "Exception: ${e.message}"
            } finally {
                loadSkills()
                // Keep overlay open a bit to see result, or close immediately.
                kotlinx.coroutines.delay(2000)
                _installingId.value = null
            }
        }
    }

    fun uninstallSkill(skillId: String) {
        viewModelScope.launch {
            try {
                ApiClient.service.uninstallSkill(skillId)
                loadSkills()
            } catch (e: Exception) {
            }
        }
    }
}
