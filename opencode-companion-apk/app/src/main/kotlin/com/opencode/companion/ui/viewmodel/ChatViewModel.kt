package com.opencode.companion.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.companion.data.ApiClient
import com.opencode.companion.data.Message
import com.opencode.companion.data.SendMessageRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ChatViewModel : ViewModel() {
    private val api = ApiClient.service

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun load(sessionId: String) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val resp = api.getMessages(sessionId)
                // Envelope wrapping: ok check; if raw array compat, handle
                if (resp.ok && resp.data != null) {
                    _messages.value = resp.data.filterNot { it.isEmpty }
                } else if (!resp.ok) {
                    _error.value = resp.error
                }
            } catch (e: Exception) {
                _error.value = e.message
            } finally { _loading.value = false }
        }
    }

    fun send(sessionId: String, text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            // Optimistic bubble
            val optimistic = Message(
                info = com.opencode.companion.data.MessageInfo(role = "user"),
                parts = listOf(com.opencode.companion.data.MessagePart(type = "text", text = text))
            )
            _messages.value = _messages.value + optimistic
            try {
                api.sendMessage(sessionId, SendMessageRequest(parts = listOf(mapOf("type" to "text", "text" to text))))
                // Reload to get assistant response (LLM round-trip ~seconds; proxy guard 60s)
                // Poll once after short delay; opencode may take seconds to produce assistant message
                kotlinx.coroutines.delay(600)
                val resp = api.getMessages(sessionId)
                if (resp.ok && resp.data != null) {
                    _messages.value = resp.data.filterNot { it.isEmpty }
                }
            } catch (e: Exception) {
                _error.value = e.message
            } finally { _loading.value = false }
        }
    }
}
