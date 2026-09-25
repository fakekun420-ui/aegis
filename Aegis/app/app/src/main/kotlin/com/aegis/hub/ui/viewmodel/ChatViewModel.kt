package com.aegis.hub.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aegis.hub.data.ApiClient
import com.aegis.hub.data.AttachedFile
import com.aegis.hub.data.LiveToolExecution
import com.aegis.hub.data.Message
import com.aegis.hub.data.MessageDeliveryStatus
import com.aegis.hub.data.MessageInfo
import com.aegis.hub.data.MessagePart
import com.aegis.hub.data.ModelOption
import com.aegis.hub.data.SendMessageRequest
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

    private val _modelsLoading = MutableStateFlow(false)
    val modelsLoading: StateFlow<Boolean> = _modelsLoading

    private val _selectedModel = MutableStateFlow<String?>("gemini-3.8-flash-high")
    val selectedModel: StateFlow<String?> = _selectedModel

    private val _selectedProvider = MutableStateFlow<String>("antigravity")
    val selectedProvider: StateFlow<String> = _selectedProvider

    // F6: sesión ya vinculada a un proveedor — el pill queda fijo para que
    // cambiar de motor en un chat abierto NO re-bindea ni "borra" la sesión.
    private val _sessionProviderBound = MutableStateFlow(false)
    val sessionProviderBound: StateFlow<Boolean> = _sessionProviderBound

    private val _sessionTitle = MutableStateFlow<String?>("Nuevo chat")
    val sessionTitle: StateFlow<String?> = _sessionTitle

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId

    private val _streamingText = MutableStateFlow<String?>(null)
    val streamingText: StateFlow<String?> = _streamingText

    private val _streamingTools = MutableStateFlow<List<LiveToolExecution>>(emptyList())
    val streamingTools: StateFlow<List<LiveToolExecution>> = _streamingTools

    private val _agentMode = MutableStateFlow<String>("build") // "plan" | "build"
    val agentMode: StateFlow<String> = _agentMode

    private var pollingJob: Job? = null

    // FASE A-5 (anti doble envío): clave del envío actualmente en vuelo
    // ("proveedor|sesión|texto|nº archivos"). Si llega un segundo click o una
    // reentrada con el MISMO contenido antes de que termine el envío actual,
    // se ignora para no reenviar el mensaje final dos veces. Se limpia en el
    // finally del envío y en el retorno temprano de error.
    private var inFlightSendKey: String? = null

    fun setSessionTitle(title: String?) {
        if (!title.isNullOrBlank() && !isTechnicalTitle(title)) {
            _sessionTitle.value = title
        }
    }

    private fun isTechnicalTitle(t: String?): Boolean {
        if (t == null) return true
        val s = t.trim()
        if (s.isBlank()) return true
        if (s.startsWith("ses_") || s.startsWith("agy_") || s.startsWith("companion:") || s.startsWith("local_")) return true
        if (s.matches(Regex("^[0-9a-fA-F-]{8,}$"))) return true
        return false
    }

    private fun updateTitleFromFirstMessage(msgList: List<Message>) {
        if (_sessionTitle.value.isNullOrBlank() || isTechnicalTitle(_sessionTitle.value)) {
            val firstPrompt = msgList.firstOrNull { it.role == "user" && it.text.isNotBlank() }?.text?.trim()
            if (!firstPrompt.isNullOrBlank()) {
                val clean = firstPrompt.replace("\n", " ").trim()
                _sessionTitle.value = if (clean.length > 30) clean.take(30).trim() + "…" else clean
            }
        }
    }

    fun selectModel(modelId: String?) {
        _selectedModel.value = modelId
    }

    fun selectProvider(provider: String) {
        val p = provider.lowercase().trim()
        // F6: en una sesión ya vinculada el proveedor de nacimiento es inamovible
        if (_sessionProviderBound.value && p != _selectedProvider.value) return
        _selectedProvider.value = p
        loadModels(p)
    }

    fun toggleAgentMode() {
        _agentMode.value = if (_agentMode.value == "plan") "build" else "plan"
    }

    fun setAgentMode(mode: String) {
        _agentMode.value = if (mode.lowercase().trim() == "plan") "plan" else "build"
    }

    fun clearError() {
        _error.value = null
    }

    fun loadModels(provider: String? = null) {
        val prov = (provider ?: _selectedProvider.value).lowercase().trim().ifBlank { "antigravity" }
        viewModelScope.launch {
            _modelsLoading.value = true
            try {
                val resp = api.getModels(prov)
                if (resp.ok && resp.data != null && resp.data.isNotEmpty()) {
                    _models.value = resp.data
                    if (_selectedModel.value == null || _models.value.none { it.id == _selectedModel.value }) {
                        val defaultHigh = resp.data.find { it.id == "gemini-3.8-flash-high" }
                        _selectedModel.value = defaultHigh?.id ?: resp.data.first().id
                    }
                } else {
                    // F6: sin lista fiable NO se conserva la del proveedor anterior
                    // (antes se veían modelos ajenos/v1 bajo la pestaña de OpenCode).
                    _models.value = emptyList()
                }
            } catch (_: Exception) {
                _models.value = emptyList()
            } finally {
                _modelsLoading.value = false
            }
        }
    }

    fun load(sessionId: String, provider: String? = null) {
        pollingJob?.cancel()
        val prov = (provider ?: if (sessionId.isBlank() || sessionId.startsWith("agy_")) "antigravity" else "opencode").lowercase().trim()
        _selectedProvider.value = prov
        // F6: sólo una sesión existente queda vinculada al proveedor de nacimiento;
        // los chats nuevos pueden cambiar libremente de motor.
        _sessionProviderBound.value = sessionId.isNotBlank()
        if (_selectedModel.value.isNullOrBlank()) {
            _selectedModel.value = "gemini-3.8-flash-high"
        }
        if (sessionId.isBlank()) {
            _sessionTitle.value = "Nuevo chat"
            loadModels(prov)
            return
        }
        _currentSessionId.value = sessionId
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            loadModels(prov)

            // Try to resolve human-readable title from sessions list
            try {
                val sessResp = api.getOpencodeSessions()
                if (sessResp.ok && sessResp.data != null) {
                    val found = sessResp.data.find { it.resolvedId == sessionId || it.id == sessionId || it.ID == sessionId }
                    if (found != null && !found.title.isNullOrBlank() && !isTechnicalTitle(found.title)) {
                        _sessionTitle.value = found.title
                    }
                }
            } catch (_: Exception) {}

            try {
                val resp = api.getMessages(sessionId)
                if (resp.ok && resp.data != null) {
                    val nonEmpties = resp.data.filterNot { it.isEmpty }
                    _messages.value = nonEmpties
                    updateTitleFromFirstMessage(nonEmpties)
                } else if (!resp.ok) {
                    _error.value = resp.error?.message ?: resp.error?.code ?: "Error al obtener mensajes"
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

        val provider = (explicitProvider ?: _selectedProvider.value).lowercase().trim().ifBlank { "antigravity" }
        val tempMsgId = "local_${System.currentTimeMillis()}"

        // FASE A-5: guarda anti doble envío — un segundo click/reentrada con el mismo
        // mensaje mientras sigue en vuelo no debe reenviarlo (ver inFlightSendKey).
        val sendKey = "$provider|${sessionId.trim()}|${text.trim()}|${files.size}"
        if (sendKey == inFlightSendKey) return
        inFlightSendKey = sendKey

        if (text.isNotBlank() && (_sessionTitle.value.isNullOrBlank() || isTechnicalTitle(_sessionTitle.value))) {
            val clean = text.replace("\n", " ").trim()
            _sessionTitle.value = if (clean.length > 30) clean.take(30).trim() + "…" else clean
        }

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
                inFlightSendKey = null // A-5: libera la guarda anti doble envío en el retorno temprano
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

            // 5. Send message with SSE real-time token streaming
            try {
                val currentAgentMode = _agentMode.value
                val currentModel = _selectedModel.value ?: "gemini-3.8-flash-high"
                val sendReq = SendMessageRequest(
                    parts = reqParts,
                    model = currentModel,
                    provider = provider,
                    agent = currentAgentMode,
                    mode = currentAgentMode
                )
                val bodyJson = com.google.gson.Gson().toJson(sendReq)

                var sseSuccess = false
                // FASE A-5: true cuando el POST del streaming ya obtuvo respuesta HTTP del
                // servidor. En ese caso la vía clásica de respaldo NO debe reenviar el
                // mensaje final (antes: una excepción en L402 reenviaba en L410 = doble envío).
                var sseRequestAccepted = false
                var sseResp: okhttp3.Response? = null
                _streamingText.value = ""
                _streamingTools.value = emptyList()

                try {
                    val streamReq = okhttp3.Request.Builder()
                        .url("http://127.0.0.1:8765/api/opencode/sessions/$targetSessionId/message?stream=true")
                        .header("Accept", "text/event-stream")
                        .header("X-Provider", provider)
                        .header("X-Model", currentModel)
                        .header("X-Agent", currentAgentMode)
                        .header("X-Mode", currentAgentMode)
                        .post(okhttp3.RequestBody.create("application/json".toMediaType(), bodyJson))
                        .build()

                    sseResp = withContext(Dispatchers.IO) { ApiClient.rawOkHttp.newCall(streamReq).execute() }
                    val resp = sseResp
                    sseRequestAccepted = resp?.isSuccessful == true
                    if (resp != null && resp.isSuccessful && resp.body != null) {
                        val reader = resp.body!!.charStream().buffered()
                        val sb = java.lang.StringBuilder()
                        var line: String? = null
                        while (withContext(Dispatchers.IO) { reader.readLine() }.also { line = it } != null) {
                            val cur = line ?: break
                            if (cur.startsWith("data: ")) {
                                val dataStr = cur.removePrefix("data: ").trim()
                                try {
                                    val jsonObj = com.google.gson.JsonParser.parseString(dataStr).asJsonObject
                                    val type = if (jsonObj.has("type")) jsonObj.get("type").asString else ""
                                    when (type) {
                                        "chunk" -> {
                                            if (jsonObj.has("text")) {
                                                val chunk = jsonObj.get("text").asString
                                                sb.append(chunk)
                                                _streamingText.value = sb.toString()
                                            }
                                        }
                                        "tool_start" -> {
                                            val rawTool = if (jsonObj.has("tool")) jsonObj.get("tool").asString else "bash"
                                            val tool = if (rawTool == "run_command") "bash" else rawTool
                                            val callId = if (jsonObj.has("callID")) jsonObj.get("callID").asString else "tool_${System.currentTimeMillis()}"
                                            val inputObj = if (jsonObj.has("input") && jsonObj.get("input").isJsonObject) jsonObj.getAsJsonObject("input") else null
                                            val cmd = inputObj?.let {
                                                (if (it.has("CommandLine")) it.get("CommandLine").asString else null)
                                                    ?: (if (it.has("command")) it.get("command").asString else null)
                                                    ?: (if (it.has("cmd")) it.get("cmd").asString else null)
                                                    ?: (if (it.has("path")) it.get("path").asString else null)
                                                    ?: (if (it.has("AbsolutePath")) it.get("AbsolutePath").asString else null)
                                                    ?: (if (it.has("TargetFile")) it.get("TargetFile").asString else null)
                                                    ?: (if (it.has("query")) it.get("query").asString else null)
                                                    ?: (if (it.has("Url")) it.get("Url").asString else null)
                                            } ?: ""
                                            val cleanCmd = cmd.trim().removeSurrounding("\"")
                                            val newExec = LiveToolExecution(
                                                id = callId,
                                                tool = tool,
                                                command = cleanCmd,
                                                status = "running"
                                            )
                                            _streamingTools.value = _streamingTools.value.filterNot { it.id == callId } + newExec
                                        }
                                        "tool_done" -> {
                                            val rawTool = if (jsonObj.has("tool")) jsonObj.get("tool").asString else "bash"
                                            val tool = if (rawTool == "run_command") "bash" else rawTool
                                            val callId = if (jsonObj.has("callID")) jsonObj.get("callID").asString else ""
                                            val output = if (jsonObj.has("output")) jsonObj.get("output").asString else ""
                                            val exitCode = if (jsonObj.has("exitCode")) jsonObj.get("exitCode").asInt else 0
                                            val duration = if (jsonObj.has("duration")) jsonObj.get("duration").asDouble else null
                                            val inputObj = if (jsonObj.has("input") && jsonObj.get("input").isJsonObject) jsonObj.getAsJsonObject("input") else null
                                            val cmd = inputObj?.let {
                                                (if (it.has("CommandLine")) it.get("CommandLine").asString else null)
                                                    ?: (if (it.has("command")) it.get("command").asString else null)
                                            } ?: ""
                                            val cleanCmd = cmd.trim().removeSurrounding("\"")
                                            _streamingTools.value = _streamingTools.value.map {
                                                if (it.id == callId || (it.tool == tool && it.status == "running")) {
                                                    it.copy(
                                                        status = if (exitCode == 0) "completed" else "error",
                                                        output = output,
                                                        exitCode = exitCode,
                                                        duration = duration,
                                                        command = if (cleanCmd.isNotBlank()) cleanCmd else it.command
                                                    )
                                                } else it
                                            }
                                        }
                                        "done" -> {
                                            val msgObj = jsonObj.getAsJsonObject("message")
                                            val finalMsg = com.google.gson.Gson().fromJson(msgObj, Message::class.java)
                                            if (finalMsg != null && !finalMsg.isEmpty) {
                                                messageDelivered = true
                                                pollingJob?.cancel()
                                                val updated = _messages.value.map {
                                                    if (it.info?.id == tempMsgId) it.withStatus(MessageDeliveryStatus.SENT) else it
                                                }
                                                val exists = updated.any { it.info?.id == finalMsg.info?.id }
                                                _messages.value = if (exists) updated else updated + finalMsg
                                                sseSuccess = true
                                            }
                                            break
                                        }
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    }
                } catch (_: Exception) {
                    sseSuccess = false
                } finally {
                    // FASE A-5: cerrar SIEMPRE la respuesta SSE. Sin esto, el ResponseBody
                    // (y su socket/file descriptor de OkHttp) queda abierto y se fuga en
                    // cada envío con streaming.
                    try { sseResp?.close() } catch (_: Exception) {}
                    _streamingText.value = null
                    _streamingTools.value = emptyList()
                }

                // A-5: solo se reenvía por la vía clásica si el streaming NUNCA llegó a
                // entregar la petición al servidor (2xx) y además no hay mensaje final.
                // Si el POST del streaming ya fue aceptado, reenviar duplicaba el mensaje.
                if (!sseSuccess && !messageDelivered && !sseRequestAccepted) {
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
                _streamingText.value = null
                _loading.value = false
                pollingJob?.cancel()
                inFlightSendKey = null // A-5: libera la guarda anti doble envío al terminar
            }
        }
    }

    suspend fun createVoiceSession(provider: String = "antigravity"): String? = createNewSession(provider)

    private suspend fun createNewSession(provider: String = "antigravity"): String? = withContext(Dispatchers.IO) {
        try {
            val title = "Nuevo chat"
            val bodyJson = "{\"title\":\"${title.replace("\"", "\\\"")}\",\"provider\":\"$provider\",\"model\":\"gemini-3.8-flash-high\"}"
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
