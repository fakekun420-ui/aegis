package com.opencode.companion.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.companion.data.ApiClient
import com.opencode.companion.data.AttachedFile
import com.opencode.companion.data.Message
import com.opencode.companion.data.ModelOption
import com.opencode.companion.data.SendMessageRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType

class ChatViewModel : ViewModel() {
    private val api = ApiClient.service

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _models = MutableStateFlow<List<ModelOption>>(emptyList())
    val models: StateFlow<List<ModelOption>> = _models

    private val _selectedModel = MutableStateFlow<String?>(null)
    val selectedModel: StateFlow<String?> = _selectedModel

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId

    fun selectModel(modelId: String?) {
        _selectedModel.value = modelId
    }

    fun loadModels() {
        viewModelScope.launch {
            try {
                val resp = api.getModels()
                if (resp.ok && resp.data != null) {
                    _models.value = resp.data
                    if (_selectedModel.value == null && resp.data.isNotEmpty()) {
                        _selectedModel.value = resp.data.first().id
                    }
                }
            } catch (_: Exception) { }
        }
    }

    fun load(sessionId: String) {
        if (sessionId.isBlank()) {
            loadModels()
            return
        }
        _currentSessionId.value = sessionId
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            loadModels()
            try {
                val resp = api.getMessages(sessionId)
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
        sendWithFiles(sessionId, text, emptyList())
    }

    fun sendWithFiles(sessionId: String, text: String, files: List<AttachedFile>) {
        if (text.isBlank() && files.isEmpty()) return
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            val optimisticText = if (text.isNotBlank()) text else files.joinToString(", ") { it.name }
            val optimistic = Message(
                info = com.opencode.companion.data.MessageInfo(role = "user"),
                parts = listOf(com.opencode.companion.data.MessagePart(type = "text", text = optimisticText))
            )
            _messages.value = _messages.value + optimistic
            try {
                val parts = mutableListOf<Map<String, String>>()
                if (text.isNotBlank()) parts += mapOf("type" to "text", "text" to text)
                for (f in files) {
                    when {
                        f.text != null -> parts += mapOf("type" to "text", "text" to "Archivo ${f.name} (${f.mime}):\n```\n${f.text.take(30000)}\n```")
                        f.base64 != null -> {
                            if (f.mime.startsWith("image/")) {
                                parts += mapOf("type" to "image", "mime" to f.mime, "image" to f.base64, "filename" to f.name)
                            }
                            parts += mapOf("type" to "file", "mime" to f.mime, "filename" to f.name, "data" to f.base64)
                        }
                    }
                }
                val targetSessionId = if (sessionId.isBlank()) createNewSession() else sessionId
                if (targetSessionId == null) {
                    _error.value = "No se pudo crear la sesión"
                    _loading.value = false
                    return@launch
                }
                _currentSessionId.value = targetSessionId
                api.sendMessage(targetSessionId, SendMessageRequest(parts = parts))
                kotlinx.coroutines.delay(600)
                val resp = api.getMessages(targetSessionId)
                if (resp.ok && resp.data != null) {
                    _messages.value = resp.data.filterNot { it.isEmpty }
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Error de red"
            } finally { _loading.value = false }
        }
    }

    private suspend fun createNewSession(): String? {
        return try {
            val title = "companion:${System.currentTimeMillis() % 100000}"
            val req = okhttp3.Request.Builder()
                .url("http://127.0.0.1:8765/opencode/session")
                .post(okhttp3.RequestBody.create("application/json".toMediaType(), "{\"title\":\"${title.replace("\"", "\\\"")}\"}"))
                .build()
            val resp = ApiClient.rawOkHttp.newCall(req).execute()
            val body = resp.body?.string() ?: return null
            val j = com.google.gson.JsonParser.parseString(body).asJsonObject
            when {
                j.has("id") -> j.get("id").asString
                j.has("ID") -> j.get("ID").asString
                j.has("data") -> {
                    val d = j.getAsJsonObject("data")
                    when {
                        d.has("id") -> d.get("id").asString
                        d.has("ID") -> d.get("ID").asString
                        else -> null
                    }
                }
                else -> null
            }
        } catch (_: Exception) { null }
    }
}
