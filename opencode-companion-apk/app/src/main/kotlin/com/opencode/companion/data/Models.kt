package com.opencode.companion.data

import com.google.gson.annotations.SerializedName

// Generic envelope server.js returns: { ok:true, data: ... } or { ok:false, error }
data class Envelope<T>(
    val ok: Boolean,
    val data: T? = null,
    val error: String? = null
)

// ---- Projects ----
data class SessionRef(
    @SerializedName("sessionId") val sessionId: String,
    val title: String? = null,
    val createdAt: String? = null,
    val lastUsed: String? = null,
    val summary: String? = null,
    val provider: String? = null
) {
    fun resolvedProvider(projectProvider: String? = null): String {
        val p = provider ?: projectProvider
        return if (p?.lowercase() == "antigravity" || sessionId.startsWith("agy_")) "Antigravity" else "OpenCode"
    }
}

data class Project(
    val id: String,
    val name: String,
    val description: String? = null,
    val createdAt: String? = null,
    val archivedAt: String? = null,
    val provider: String? = "antigravity",
    val sessions: List<SessionRef>? = null,
    val skills: List<Any>? = null,
    val linkedProjects: List<String>? = null
) {
    val resolvedProvider: String get() = if (provider?.lowercase() == "antigravity") "Antigravity" else "OpenCode"
}

data class CreateProjectRequest(
    val name: String,
    val description: String? = null,
    val provider: String? = "antigravity"
)

data class PatchProjectRequest(
    val name: String? = null,
    val description: String? = null,
    val archived: Boolean? = null,
    val provider: String? = null
)

data class LinkSessionRequest(
    val sessionId: String,
    val title: String? = null,
    val provider: String? = "antigravity"
)

// ---- Sessions (proxy /api/opencode/sessions -> opencode /session) ----
data class OpencodeSession(
    val id: String? = null,
    @SerializedName("ID") val ID: String? = null,
    val title: String? = null,
    val name: String? = null,
    val model: Any? = null,
    val createdAt: String? = null,
    @SerializedName("created_at") val createdAtAlt: String? = null,
    val updatedAt: String? = null,
    @SerializedName("updated_at") val updatedAtAlt: String? = null,
    val provider: String? = null
) {
    val resolvedId: String get() = id ?: ID ?: ""
    val resolvedTitle: String get() = title ?: name ?: if (resolvedId.startsWith("agy_")) "Nuevo chat" else resolvedId.take(8)
    val lastActivityIso: String? get() = updatedAt ?: updatedAtAlt ?: createdAt ?: createdAtAlt
    val resolvedProvider: String get() = if (provider?.lowercase() == "antigravity" || resolvedId.startsWith("agy_")) "Antigravity" else "OpenCode"
}

// ---- Messages (GET /session/:id/message proxied via hub) ----
enum class MessageDeliveryStatus { PENDING, SENT, ERROR }

data class MessageInfo(
    val id: String? = null,
    val role: String? = null,
    val time: Map<String, Any>? = null,
    val status: MessageDeliveryStatus? = MessageDeliveryStatus.SENT
) {
    val deliveryStatus: MessageDeliveryStatus get() = status ?: MessageDeliveryStatus.SENT
}

data class ToolState(
    val status: String? = null,
    val input: Map<String, Any?>? = null,
    val output: String? = null,
    val exitCode: Int? = null,
    val duration: Double? = null
) {
    val command: String get() {
        val direct = input?.get("command") as? String
            ?: input?.get("CommandLine") as? String
            ?: input?.get("cmd") as? String
        if (!direct.isNullOrBlank()) return direct.trim().removeSurrounding("\"")
        val path = input?.get("path") as? String
            ?: input?.get("AbsolutePath") as? String
            ?: input?.get("TargetFile") as? String
        if (!path.isNullOrBlank()) return path.trim().removeSurrounding("\"")
        val query = input?.get("query") as? String
        if (!query.isNullOrBlank()) return query.trim().removeSurrounding("\"")
        val url = input?.get("Url") as? String ?: input?.get("url") as? String
        if (!url.isNullOrBlank()) return url.trim().removeSurrounding("\"")
        return ""
    }
}

data class LiveToolExecution(
    val id: String,
    val tool: String,
    val command: String,
    val status: String = "running",
    val output: String? = null,
    val exitCode: Int? = null,
    val duration: Double? = null
)

data class MessagePart(
    val id: String? = null,
    val type: String? = null,
    val text: String? = null,
    val mime: String? = null,
    val filename: String? = null,
    val data: String? = null,
    val image: String? = null,
    val url: String? = null,
    val tool: String? = null,
    val callID: String? = null,
    val state: ToolState? = null
)

data class Message(
    val info: MessageInfo? = null,
    val parts: List<MessagePart>? = null
) {
    // Normalized: role from info, text from type=text parts only
    val role: String get() = info?.role ?: "assistant"
    val text: String get() = parts
        ?.filter { it.type == "text" && !it.text.isNullOrBlank() }
        ?.joinToString("\n") { it.text!! } ?: ""
    fun isMemoryContext(): Boolean {
        val t = text.trim()
        return t.startsWith("<memory_context") || t.contains("<project_knowledge") || t.contains("<memory relevance=")
    }
    fun strippedText(): String {
        var t = text
        val reqMatch = Regex("<USER_REQUEST>([\\s\\S]*?)(?:</USER_REQUEST>|$)", RegexOption.IGNORE_CASE).find(t)
        if (reqMatch != null && reqMatch.groupValues[1].isNotBlank()) {
            t = reqMatch.groupValues[1].trim()
        }
        t = Regex("^\\s*//?/?(?:PLAN|plan|BUILD|build)\\s*", RegexOption.IGNORE_CASE).replace(t, "").trim()
        t = Regex("<SYSTEM_INSTRUCTION>[\\s\\S]*?(?:</SYSTEM_INSTRUCTION>|$)", RegexOption.IGNORE_CASE).replace(t, "").trim()
        t = Regex("<SYSTEM_CONTEXT>[\\s\\S]*?(?:</SYSTEM_CONTEXT>|$)", RegexOption.IGNORE_CASE).replace(t, "").trim()
        t = Regex("\\[SYSTEM CONTEXT[\\s\\S]*?\\][\\s\\S]*?(?:---\\n\\n|$)", RegexOption.IGNORE_CASE).replace(t, "").trim()
        t = Regex("# PONY-TAIL[\\s\\S]*?(?:---\\n\\n|$)", RegexOption.IGNORE_CASE).replace(t, "").trim()
        t = Regex("## 1\\. Entorno[\\s\\S]*?(?:---\\n\\n|$)", RegexOption.IGNORE_CASE).replace(t, "").trim()
        t = Regex("<memory_context[\\s\\S]*?</memory_context>", RegexOption.IGNORE_CASE).replace(t, "").trim()
        t = Regex("<project_knowledge[\\s\\S]*?</project_knowledge>", RegexOption.IGNORE_CASE).replace(t, "").trim()
        t = Regex("<ADDITIONAL_METADATA>[\\s\\S]*?</ADDITIONAL_METADATA>", RegexOption.IGNORE_CASE).replace(t, "").trim()
        t = Regex("<USER_SETTINGS_CHANGE>[\\s\\S]*?</USER_SETTINGS_CHANGE>", RegexOption.IGNORE_CASE).replace(t, "").trim()
        return t.trim()
    }
    val isEmpty: Boolean get() = text.isBlank() && strippedText().isBlank() && fileParts().isEmpty() && imageParts().isEmpty() && toolParts().isEmpty()
    fun fileParts(): List<MessagePart> = parts?.filter { it.type == "file" } ?: emptyList()
    fun imageParts(): List<MessagePart> = parts?.filter { it.type == "image" } ?: emptyList()
    fun toolParts(): List<MessagePart> = parts?.filter { it.type == "tool" } ?: emptyList()

    val isPending: Boolean get() = info?.deliveryStatus == MessageDeliveryStatus.PENDING
    val isError: Boolean get() = info?.deliveryStatus == MessageDeliveryStatus.ERROR

    fun withStatus(newStatus: MessageDeliveryStatus): Message =
        copy(info = (info ?: MessageInfo()).copy(status = newStatus))
}

data class SendMessageRequest(
    val parts: List<Map<String, String>>,
    val model: String? = null,
    val provider: String? = null,
    val agent: String? = null,
    val mode: String? = null
)

data class Skill(
    val scope: String,
    val name: String,
    val content: String
)

data class SkillListResponse(
    val skills: List<Skill>,
    val projectId: String? = null,
    val counts: Map<String, Int>? = null
)

data class SkillCreateRequest(
    val scope: String,
    val name: String,
    val content: String
)

data class AttachedFile(
    val name: String,
    val mime: String,
    val size: Long,
    val base64: String? = null,
    val text: String? = null
)

// ---- Models ----
data class ModelOption(
    val id: String,
    val name: String,
    val description: String? = null
)

data class SendMessageRequestWithModel(
    val parts: List<Map<String, String>>,
    val model: String? = null
)

// System status for overlay gate
data class SystemStatus(
    val ready: Boolean = false,
    val sessionOwnership: String? = null
)
