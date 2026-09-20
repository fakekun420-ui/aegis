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
    val summary: String? = null
)

data class Project(
    val id: String,
    val name: String,
    val description: String? = null,
    val createdAt: String? = null,
    val archivedAt: String? = null,
    val sessions: List<SessionRef>? = null,
    val skills: List<Any>? = null,
    val linkedProjects: List<String>? = null
)

data class CreateProjectRequest(
    val name: String,
    val description: String? = null
)

data class PatchProjectRequest(
    val name: String? = null,
    val description: String? = null,
    val archived: Boolean? = null
)

data class LinkSessionRequest(
    val sessionId: String,
    val title: String? = null
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
    @SerializedName("updated_at") val updatedAtAlt: String? = null
) {
    val resolvedId: String get() = id ?: ID ?: ""
    val resolvedTitle: String get() = title ?: name ?: resolvedId.take(8)
    val lastActivityIso: String? get() = updatedAt ?: updatedAtAlt ?: createdAt ?: createdAtAlt
}

// ---- Messages (GET /session/:id/message proxied via hub) ----
data class MessageInfo(
    val id: String? = null,
    val role: String? = null,
    val time: Map<String, Any>? = null
)
data class MessagePart(
    val id: String? = null,
    val type: String? = null,
    val text: String? = null,
    val mime: String? = null,
    val filename: String? = null,
    val data: String? = null,
    val image: String? = null
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
        t = Regex("<memory_context[\\s\\S]*?</memory_context>", RegexOption.IGNORE_CASE).replace(t, "").trim()
        t = Regex("<project_knowledge[\\s\\S]*?</project_knowledge>", RegexOption.IGNORE_CASE).replace(t, "").trim()
        return t
    }
    val isEmpty: Boolean get() = text.isBlank() && strippedText().isBlank() && fileParts().isEmpty() && imageParts().isEmpty()
    fun fileParts(): List<MessagePart> = parts?.filter { it.type == "file" } ?: emptyList()
    fun imageParts(): List<MessagePart> = parts?.filter { it.type == "image" } ?: emptyList()
}

data class SendMessageRequest(
    val parts: List<Map<String, String>>
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
