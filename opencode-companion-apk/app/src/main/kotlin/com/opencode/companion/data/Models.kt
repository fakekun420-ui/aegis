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

// System status for overlay gate
data class SystemStatus(
    val ready: Boolean = false,
    val sessionOwnership: String? = null
)
