package com.opencode.companion.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.companion.data.ApiClient
import com.opencode.companion.data.AttachedFile
import com.opencode.companion.data.Message
import com.opencode.companion.data.MessageDeliveryStatus
import com.opencode.companion.data.MessageInfo
import com.opencode.companion.data.MessagePart
import com.opencode.companion.data.ModelOption
import com.opencode.companion.data.SendMessageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private val _selectedProvider = MutableStateFlow<String>("opencode")
    val selectedProvider: StateFlow<String> = _selectedProvider

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId

    private var pollingJob: Job? = null

    fun selectModel(modelId: String?) {
        _selectedModel.value = modelId
    }

    fun selectProvider(provider: String) {
        val p = provider.lowercase().trim()
        _selectedProvider.value = p
        loadModels(p)
    }

    fun clearError() {
        _error.value = null
    }

    fun loadModels(provider: String? = null) {
        val prov = (provider ?: _selectedProvider.value).lowercase().trim()
        viewModelScope.launch {
            try {
                val resp = api.getModels(prov)
                if (resp.ok && resp.data != null && resp.data.isNotEmpty()) {
                    _models.value = resp.data
                    if (_models.value.none { it.id == _selectedModel.value }) {
                        _selectedModel.value = resp.data.first().id
                    }
                }
            } catch (_: Exception) { }
        }
    }

    fun load(sessionId: String, provider: String? = null) {
        pollingJob?.cancel()
        val prov = (provider ?: if (sessionId.startsWith("agy_")) "antigravity" else "opencode").lowercase().trim()
        _selectedProvider.value = prov
        if (sessionId.isBlank()) {
            loadModels(prov)
            return
        }
        _currentSessionId.value = sessionId
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            loadModels(prov)
            try {
                val resp = api.getMessages(sessionId)
                if (resp.ok && resp.data != null) {
                    _messages.value = resp.data.filterNot { it.isEmpty }
                } else if (!resp.ok) {
                    _error.value = resp.error ?: "Error al obtener mensajes"
                }
            } catch (e: Exception) {
                _error.value = e.localizedMessage ?: e.message ?: "Error de conexión con el servidor"
            } finally {
                _loading.value = false
            }
        }
    }

    fun send(sessionId: String, text: String, provider: String? = null) {
        sendWithFiles(sessionId, text, emptyList(), provider)
    }

    fun retryMessage(failedMsg: Message, sessionId: String) {
        val text = failedMsg.text
        val files = failedMsg.fileParts().mapNotNull { fp ->
            if (fp.filename != null) {
                AttachedFile(
                    name = fp.filename,
                    size = fp.data?.length?.toLong() ?: 0L,
                    mime = fp.mime ?: "text/plain",
                    text = fp.data,
                    base64 = if (fp.url?.startsWith("data:") == true) fp.url.substringAfter("base64,") else null
                )
            } else null
        }
        _messages.value = _messages.value.filterNot { it.info?.id == failedMsg.info?.id }
        sendWithFiles(sessionId, text, files)
    }

    fun sendWithFiles(
        sessionId: String,
        text: String,
        files: List<AttachedFile>,
        explicitProvider: String? = null
    ) {
        if (text.isBlank() && files.isEmpty()) return

        val provider = (explicitProvider ?: _selectedProvider.value).lowercase().trim()
        val tempMsgId = "local_${System.currentTimeMillis()}"

        viewModelScope.launch {
            _loading.value = true
            _error.value = null

            // 1. Optimistic user message with PENDING status
            val optimisticParts = mutableListOf<MessagePart>()
            if (text.isNotBlank()) {
                optimisticParts += MessagePart(type = "text", text = text)
            }
            for (f in files) {
                when {
                    f.text != null -> optimisticParts += MessagePart(type = "text", text = "Archivo ${f.name} (${f.mime}):\n```\n${f.text.take(30000)}\n```", filename = f.name, mime = f.mime)
                    f.base64 != null -> optimisticParts += MessagePart(type = "file", filename = f.name, mime = f.mime, url = "data:${f.mime};base64,${f.base64}")
                }
            }
            val optimistic = Message(
                info = MessageInfo(id = tempMsgId, role = "user", status = MessageDeliveryStatus.PENDING),
                parts = optimisticParts
            )
            _messages.value = _messages.value + optimistic

            // 2. Resolve Target Session ID
            val activeSessionId = sessionId.ifBlank { _currentSessionId.value ?: "" }
            val targetSessionId = if (activeSessionId.isBlank()) createNewSession(provider) else activeSessionId
            if (targetSessionId == null) {
                _error.value = "No se pudo crear o resolver la sesión en el servidor"
                _messages.value = _messages.value.map {
                    if (it.info?.id == tempMsgId) it.withStatus(MessageDeliveryStatus.ERROR) else it
                }
                _loading.value = false
                return@launch
            }
            _currentSessionId.value = targetSessionId

            // 3. Prepare payload parts
            val reqParts = mutableListOf<Map<String, String>>()
            if (text.isNotBlank()) reqParts += mapOf("type" to "text", "text" to text)
            for (f in files) {
                when {
                    f.text != null -> reqParts += mapOf("type" to "text", "text" to "Archivo ${f.name} (${f.mime}):\n```\n${f.text.take(30000)}\n```")
                    f.base64 != null -> {
                        val dataUri = "data:${f.mime};base64,${f.base64}"
                        reqParts += mapOf("type" to "file", "mime" to f.mime, "filename" to f.name, "url" to dataUri)
                    }
                }
            }

            val countBefore = _messages.value.size
            var messageDelivered = false

            // 4. Start active background polling in parallel to catch assistant output or SSE stream completions
            pollingJob?.cancel()
            pollingJob = launch {
                // Poll every 1.5s for up to 50 attempts (~75s)
                for (attempt in 1..50) {
                    delay(1500)
                    if (!isActive || messageDelivered) break
                    try {
                        val pollResp = api.getMessages(targetSessionId)
                        if (pollResp.ok && pollResp.data != null) {
                            val nonEmpties = pollResp.data.filterNot { it.isEmpty }
                            // Check if a new assistant message arrived
                            val hasAssistant = nonEmpties.any { it.role == "assistant" && !it.isEmpty }
                            if (hasAssistant && nonEmpties.size >= countBefore) {
                                _messages.value = nonEmpties
                                _loading.value = false
                                messageDelivered = true
                                break
                            }
                        }
                    } catch (_: Exception) { }
                }
            }

            // 5. Send message via Retrofit with explicit provider and model
            try {
                val sendReq = SendMessageRequest(
                    parts = reqParts,
                    model = _selectedModel.value,
                    provider = provider
                )
                val responseMsg = api.sendMessage(
                    sessionId = targetSessionId,
                    body = sendReq,
                    provider = provider
                )

                // If responseMsg arrived directly with assistant parts
                if (!responseMsg.isEmpty) {
                    messageDelivered = true
                    pollingJob?.cancel()

                    // Mark user message as SENT and append response
                    val updated = _messages.value.map {
                        if (it.info?.id == tempMsgId) it.withStatus(MessageDeliveryStatus.SENT) else it
                    }
                    val exists = updated.any { it.info?.id == responseMsg.info?.id }
                    _messages.value = if (exists) updated else updated + responseMsg
                    _loading.value = false
                }

                // Follow-up sync to get canonical messages from DB
                delay(400)
                val syncResp = api.getMessages(targetSessionId)
                if (syncResp.ok && syncResp.data != null) {
                    val synced = syncResp.data.filterNot { it.isEmpty }
                    if (synced.isNotEmpty()) {
                        _messages.value = synced
                    }
                }
            } catch (e: Exception) {
                // Check if background polling already retrieved the response
                val hasAssistantNow = _messages.value.any { it.role == "assistant" && it.info?.id != tempMsgId }
                if (!hasAssistantNow && !messageDelivered) {
                    // Try one last fast getMessages attempt before declaring failure
                    try {
                        delay(600)
                        val lastTry = api.getMessages(targetSessionId)
                        if (lastTry.ok && lastTry.data != null) {
                            val list = lastTry.data.filterNot { it.isEmpty }
                            if (list.any { it.role == "assistant" }) {
                                _messages.value = list
                                _loading.value = false
                                return@launch
                            }
                        }
                    } catch (_: Exception) { }

                    _messages.value = _messages.value.map {
                        if (it.info?.id == tempMsgId) it.withStatus(MessageDeliveryStatus.ERROR) else it
                    }
                    _error.value = "Error al enviar mensaje: ${e.localizedMessage ?: e.message ?: "Tiempo de espera agotado"}"
                }
            } finally {
                _loading.value = false
                pollingJob?.cancel()
            }
        }
    }

    private suspend fun createNewSession(provider: String = "opencode"): String? = withContext(Dispatchers.IO) {
        try {
            val title = "companion:${System.currentTimeMillis() % 100000}"
            val bodyJson = "{\"title\":\"${title.replace("\"", "\\\"")}\",\"provider\":\"$provider\"}"
            val req = okhttp3.Request.Builder()
                .url("http://127.0.0.1:8765/opencode/session")
                .header("X-Provider", provider)
                .post(okhttp3.RequestBody.create("application/json".toMediaType(), bodyJson))
                .build()
            val resp = ApiClient.rawOkHttp.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext null
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
