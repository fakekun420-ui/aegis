package com.aegis.hub.data

import com.google.gson.annotations.SerializedName

/**
 * Modelos de datos NATIVOS para la API v2 de OpenCode (`http://127.0.0.1:49374/api/*`).
 *
 * A diferencia del Hub de Aegis (que envuelve todo en `{ "ok": true, "data": ... }`),
 * OpenCode v2 devuelve las entidades de forma directa:
 * - Listas: `{ "data": [ ... ], "cursor": ... }` o listas JSON planas según endpoint.
 * - Una entidad: JSON plano `{ "id": "ses_...", "title": ... }` o `{ "data": ... }`.
 * - Mensajes: array de bloques en `content: [ { "type": "...", ... } ]`.
 * - Prompt: acuse de recibo inmediato `{ "id": ..., "sessionID": ..., "time": ... }`.
 * - Modelos y agentes: estructuras directas publicadas por el daemon.
 */

// ==========================================
// 1. Info / Estado del servidor
// ==========================================

data class OpenCodeServerInfo(
    @SerializedName("version") val version: String? = null,
    @SerializedName("platform") val platform: String? = null,
    @SerializedName("status") val status: String? = null
)

// ==========================================
// 2. Sesiones (OpenCode Session v2)
// ==========================================

data class OpenCodeTime(
    @SerializedName("created") val created: Long? = null,
    @SerializedName("updated") val updated: Long? = null,
    @SerializedName("idle") val idle: Long? = null
)

data class OpenCodeTokens(
    @SerializedName("input") val input: Long? = null,
    @SerializedName("output") val output: Long? = null,
    @SerializedName("reasoning") val reasoning: Long? = null
)

data class OpenCodeLocation(
    @SerializedName("directory") val directory: String? = null
)

data class OpenCodeModelRef(
    @SerializedName("id") val id: String,
    @SerializedName("providerID") val providerID: String = "opencode",
    @SerializedName("variant") val variant: String? = null
)

data class OpenCodeSession(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String? = null,
    @SerializedName("agent") val agent: String? = null,
    @SerializedName("model") val model: OpenCodeModelRef? = null,
    @SerializedName("outcome") val outcome: String? = null,
    @SerializedName("cost") val cost: Double? = null,
    @SerializedName("projectID") val projectID: String? = null,
    @SerializedName("time") val time: OpenCodeTime? = null,
    @SerializedName("tokens") val tokens: OpenCodeTokens? = null,
    @SerializedName("location") val location: OpenCodeLocation? = null
)

data class OpenCodeSessionListResponse(
    @SerializedName("data") val data: List<OpenCodeSession>? = null,
    @SerializedName("cursor") val cursor: OpenCodeCursor? = null
)

data class OpenCodeCursor(
    @SerializedName("next") val next: String? = null,
    @SerializedName("prev") val prev: String? = null
)

data class CreateOpenCodeSessionRequest(
    @SerializedName("title") val title: String? = null,
    @SerializedName("location") val location: OpenCodeLocation? = null
)

data class UpdateOpenCodeSessionRequest(
    @SerializedName("title") val title: String? = null
)

// ==========================================
// 3. Estado de Turno / Activas (Killer endpoint)
// ==========================================

data class ActiveSessionStatus(
    @SerializedName("type") val type: String? = null // e.g. "running", "idle"
)

// ==========================================
// 4. Mensajes y Partes Nativas
// ==========================================

data class OpenCodePartSource(
    @SerializedName("type") val type: String? = null,
    @SerializedName("uri") val uri: String? = null
)

data class OpenCodeFileAttachment(
    @SerializedName("id") val id: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("mime") val mime: String? = null,
    @SerializedName("data") val data: String? = null,
    @SerializedName("source") val source: OpenCodePartSource? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("mention") val mention: String? = null
)

data class OpenCodeMessagePart(
    @SerializedName("id") val id: String? = null,
    @SerializedName("type") val type: String? = null, // "text", "reasoning", "tool", "file", "image"
    @SerializedName("text") val text: String? = null,
    @SerializedName("mime") val mime: String? = null,
    @SerializedName("tool") val tool: String? = null,
    @SerializedName("callID") val callID: String? = null,
    @SerializedName("state") val state: ToolState? = null,
    @SerializedName("files") val files: List<OpenCodeFileAttachment>? = null
)

data class OpenCodeMessage(
    @SerializedName("id") val id: String,
    @SerializedName("sessionID") val sessionID: String? = null,
    @SerializedName("type") val type: String? = null, // "user", "assistant", etc.
    @SerializedName("role") val role: String? = null,
    @SerializedName("agent") val agent: String? = null,
    @SerializedName("model") val model: OpenCodeModelRef? = null,
    @SerializedName("content") val content: List<OpenCodeMessagePart>? = null,
    @SerializedName("time") val time: OpenCodeTime? = null,
    @SerializedName("error") val error: Map<String, Any?>? = null,
    // Adjuntos directos a nivel mensaje (v2 m.files)
    @SerializedName("files") val files: List<OpenCodeFileAttachment>? = null
)

data class OpenCodeMessageListResponse(
    @SerializedName("data") val data: List<OpenCodeMessage>? = null,
    @SerializedName("cursor") val cursor: OpenCodeCursor? = null
)

// ==========================================
// 5. Envío de Prompt (Asíncrono v2)
// ==========================================

data class OpenCodePromptFileRef(
    @SerializedName("uri") val uri: String,
    @SerializedName("name") val name: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("mention") val mention: String? = null
)

data class OpenCodePromptAgentRef(
    @SerializedName("name") val name: String,
    @SerializedName("mention") val mention: String? = null
)

data class OpenCodePromptRequest(
    @SerializedName("text") val text: String,
    @SerializedName("files") val files: List<OpenCodePromptFileRef>? = null,
    @SerializedName("agents") val agents: List<OpenCodePromptAgentRef>? = null,
    @SerializedName("delivery") val delivery: String? = null, // enum: "steer" | "queue"
    @SerializedName("resume") val resume: Boolean? = null
)

data class OpenCodePromptAck(
    @SerializedName("id") val id: String? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("sessionID") val sessionID: String? = null,
    @SerializedName("payload") val payload: Map<String, Any?>? = null,
    @SerializedName("delivery") val delivery: String? = null,
    @SerializedName("time") val time: OpenCodeTime? = null,
    @SerializedName("text") val text: String? = null
)

// ==========================================
// 6. Selección de Modelo y Agente
// ==========================================

data class SetSessionModelRequest(
    @SerializedName("model") val model: OpenCodeModelRef
)

data class SetSessionAgentRequest(
    @SerializedName("agent") val agent: String
)

// ==========================================
// 7. Catálogo de Agentes y Modelos
// ==========================================

data class OpenCodeNativeAgent(
    @SerializedName("id") val id: String? = null,
    @SerializedName("name") val name: String,
    @SerializedName("mode") val mode: String = "primary", // "primary" | "subagent"
    @SerializedName("model") val model: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("hidden") val hidden: Boolean = false,
    @SerializedName("permissions") val permissions: Map<String, Any?>? = null
)

data class OpenCodeNativeAgentListResponse(
    @SerializedName("data") val data: List<OpenCodeNativeAgent>? = null
)

data class OpenCodeNativeModel(
    @SerializedName("id") val id: String,
    @SerializedName("modelID") val modelID: String? = null,
    @SerializedName("providerID") val providerID: String = "opencode",
    @SerializedName("name") val name: String? = null,
    @SerializedName("family") val family: String? = null,
    @SerializedName("enabled") val enabled: Boolean = true,
    @SerializedName("capabilities") val capabilities: Map<String, Any?>? = null,
    @SerializedName("cost") val cost: Any? = null, // puede ser número, objeto o lista
    @SerializedName("variants") val variants: List<String>? = null,
    @SerializedName("status") val status: String? = null
)

data class OpenCodeNativeModelListResponse(
    @SerializedName("data") val data: List<OpenCodeNativeModel>? = null
)

// ==========================================
// 8. Formularios y Permisos
// ==========================================

data class OpenCodeFormReplyRequest(
    @SerializedName("answer") val answer: Map<String, Any?>
)

data class OpenCodePermissionReplyRequest(
    @SerializedName("decision") val decision: String, // "once" | "always" | "reject"
    @SerializedName("message") val message: String? = null
)

data class OpenCodeFormsResponse(
    @SerializedName("data") val data: List<PendingForm>? = null
)

data class OpenCodePermissionsResponse(
    @SerializedName("data") val data: List<PendingPermission>? = null,
    @SerializedName("location") val location: OpenCodeLocation? = null
)

// ==========================================
// 9. Contrato base de eventos SSE (/api/event)
// ==========================================

data class OpenCodeServerEvent(
    @SerializedName("id") val id: String? = null,
    @SerializedName("created") val created: Long? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("location") val location: OpenCodeLocation? = null,
    @SerializedName("data") val data: Map<String, Any?>? = null
) {
    val sessionID: String?
        get() = data?.get("sessionID") as? String

    val textDelta: String?
        get() = data?.get("delta") as? String

    val endedText: String?
        get() = data?.get("text") as? String
}
