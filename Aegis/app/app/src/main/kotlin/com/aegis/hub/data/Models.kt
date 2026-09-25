package com.aegis.hub.data

import com.google.gson.annotations.SerializedName

// Generic envelope server.js returns: { ok:true, data: ... } or { ok:false, error:{code,message} }
data class ErrorBody(
    val code: String? = null,
    val message: String? = null
)

data class Envelope<T>(
    val ok: Boolean,
    val data: T? = null,
    val error: ErrorBody? = null
)

// ---- Projects ----
data class SessionRef(
    @SerializedName("sessionId") val sessionId: String,
    val title: String? = null,
    val createdAt: String? = null,
    val lastUsed: String? = null,
    val summary: String? = null,
    val pinned: Boolean = false,
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
    val folder: String? = null,
    val ponytail: String? = null,
    val sessions: List<SessionRef>? = null,
    val skills: List<Any>? = null,
    val linkedProjects: List<String>? = null
) {
    val resolvedProvider: String get() = if (provider?.lowercase() == "antigravity") "Antigravity" else "OpenCode"
    val resolvedFolder: String get() = folder ?: "/sdcard/projects/${name.lowercase().replace(" ", "-").replace(Regex("[^a-z0-9_-]"), "")}/"
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
    // F6: registro único — nombre REAL que le puso OpenCode (title = nombre puesto desde la app)
    val providerTitle: String? = null,
    val model: Any? = null,
    val createdAt: String? = null,
    @SerializedName("created_at") val createdAtAlt: String? = null,
    val updatedAt: String? = null,
    @SerializedName("updated_at") val updatedAtAlt: String? = null,
    val pinned: Boolean = false,
    val provider: String? = null
) {
    val resolvedId: String get() = id ?: ID ?: ""
    val resolvedTitle: String get() = title ?: name ?: if (resolvedId.startsWith("agy_")) "Nuevo chat" else resolvedId.take(8)
    val lastActivityIso: String? get() = updatedAt ?: updatedAtAlt ?: createdAt ?: createdAtAlt
    val resolvedProvider: String get() = if (provider?.lowercase() == "antigravity" || resolvedId.startsWith("agy_")) "Antigravity" else "OpenCode"
}

data class PinResponse(
    val id: String,
    val pinned: Boolean = false
)

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

// ---- Aegis Phase 11 Models ----

data class BaseResponse(val ok: Boolean, val error: ErrorBody? = null)

data class HealthResponse(
    val ok: Boolean,
    val data: HealthData?
)

data class HealthData(
    val server: String?,
    val port: Int?,
    val uptime: Long?,
    val memory: MemoryData?,
    val workspace: String?,
    val projects: Int?,
    val agents: AgentsSummary?,
    val jobs: JobsSummary?,
    val skills: SkillsSummary?,
    val adapters: Map<String, String>?
)

data class MemoryData(val heapUsed: String?, val heapTotal: String?)
data class AgentsSummary(val active: Int?, val registered: Int?)
data class JobsSummary(val active: Int?, val lastRun: String?)
data class SkillsSummary(val installed: List<String>?)

data class SkillItem(
    val id: String,
    val name: String,
    val version: String?,
    val description: String?,
    val installed: Boolean,
    val enabled: Boolean
)

data class SkillsResponse(val ok: Boolean, val data: SkillsData?)
data class SkillsData(val installed: List<SkillItem>?, val available: List<SkillItem>?)

data class InstallSkillRequest(val skillId: String)
data class TaskResponse(val ok: Boolean, val data: TaskData?)
data class TaskData(val taskId: String?, val message: String?)

data class ProjectItem(
    val id: String,
    val name: String,
    val path: String?,
    val hasHub: Boolean,
    val lastCommit: String?
)

data class ProjectsResponse(val ok: Boolean, val data: List<ProjectItem>?)
data class ProjectStateResponse(val ok: Boolean, val data: Map<String, Any>?)

data class AgentItem(val id: String, val name: String, val status: String)
data class AgentsResponse(val ok: Boolean, val data: List<AgentItem>?)
data class DispatchAgentRequest(val agentType: String, val projectId: String, val context: Map<String, String>)
data class AgentStatusResponse(val ok: Boolean, val data: List<AgentItem>?)

data class WorkflowItem(val id: String, val name: String, val steps: Int)
data class WorkflowsResponse(val ok: Boolean, val data: List<WorkflowItem>?)
data class RunWorkflowRequest(val workflowId: String)
data class WorkflowStatusResponse(val ok: Boolean, val data: WorkflowStatus?)
data class WorkflowStatus(val id: String, val status: String, val currentStep: String?, val progress: Int)

data class JobItem(val id: String, val enabled: Boolean, val lastRun: String?, val interval: String)
data class JobsResponse(val ok: Boolean, val data: List<JobItem>?)

data class LogsResponse(val ok: Boolean, val data: List<String>?)
data class MemoryResponse(val ok: Boolean, val data: MemoryData?)
data class SkillConfigResponse(val ok: Boolean, val data: Map<String, Any>?)

// ---- F1: Bootstrap / asistente de configuración inicial (/api/bootstrap/*) ----
// Parser: Gson (ApiClient) — case-sensitive. Los nombres de las constantes coinciden
// EXACTO con los strings del contrato ("idle", "pending", "none", ...); NO se usa
// @Json (Moshi/kotlinx) porque el proyecto es Gson + @SerializedName.
// Campos que el contrato envía como null (error, currentStepId, startedAt) van
// nullable para que el parseo no rompa; los helpers cubren valores inesperados
// sin NPE (mismo precedente que MessageInfo.deliveryStatus).
enum class BootstrapPhase { idle, running, paused, failed, done }
enum class BootstrapStepStatus { pending, running, done, failed, skipped }
enum class BootstrapRollback { none, pending, done, failed }

data class BootstrapStep(
    val id: String,
    val title: String,
    val status: BootstrapStepStatus? = null,
    val rollback: BootstrapRollback? = null,
    val progress: Int? = null,
    val detail: String? = null,
    val error: String? = null
) {
    // Status desconocido/null del backend → asumido pendiente (nunca crashea)
    val statusOrPending: BootstrapStepStatus get() = status ?: BootstrapStepStatus.pending
}

data class BootstrapState(
    val phase: BootstrapPhase? = null,
    val currentStepId: String? = null,
    val startedAt: String? = null,
    val updatedAt: String? = null,
    val lastError: String? = null,
    val steps: List<BootstrapStep>? = null
) {
    val phaseOrIdle: BootstrapPhase get() = phase ?: BootstrapPhase.idle
    val stepList: List<BootstrapStep> get() = steps ?: emptyList()
}

// Envelope de error del hub: {ok:false, error:{code,message}} (server.js)
typealias BootstrapError = ErrorBody

// GET /api/bootstrap/state → {ok, data:{phase,currentStepId,...,steps:[...]}}
data class BootstrapResponse(val ok: Boolean, val data: BootstrapState?, val error: BootstrapError? = null)

// POST /api/bootstrap/run | /step/{id}/retry | /cancel → 200/202 {ok, data:{phase}}
data class BootstrapActionData(val phase: BootstrapPhase? = null)
data class BootstrapActionResponse(val ok: Boolean, val data: BootstrapActionData?, val error: BootstrapError? = null)

data class BootstrapRunRequest(val resume: Boolean)

// ---- F3: verificación final + smoke test + guía de auth (contrato /api/setup/*) ----
// Mismo patrón que F1: envelope {ok, data, error} (BootstrapResponse-style) con
// data nullable; enums minúscula case-sensitive para Gson (como BootstrapPhase);
// error = {code,message} reutilizando BootstrapError (mismo shape que server.js).

// status del contrato: "ok" | "fail" | "manual"
enum class SetupCheckStatus { ok, fail, manual }

data class SetupCheck(
    val id: String,
    val label: String,
    val status: SetupCheckStatus? = null,
    val detail: String? = null
) {
    // Status desconocido/null → "manual" (revisión del usuario; nunca crashea,
    // mismo precedente que BootstrapStep.statusOrPending)
    val statusOrManual: SetupCheckStatus get() = status ?: SetupCheckStatus.manual
}

// data del GET /api/setup/final-check → {ready, checks:[{id,label,status,detail}]}
data class FinalCheckData(
    val ready: Boolean = false,
    val checks: List<SetupCheck>? = null
) {
    val checkList: List<SetupCheck> get() = checks ?: emptyList()
}

// GET /api/setup/final-check → {ok, data:{ready, checks}}
data class FinalCheckResponse(
    val ok: Boolean,
    val data: FinalCheckData?,
    val error: BootstrapError? = null
)

// data del POST /api/setup/smoke-test → {ok, reply}
data class SmokeTestData(
    val ok: Boolean? = null,
    val reply: String? = null
)

// POST /api/setup/smoke-test → {ok, data:{ok, reply}} ·
// error → {ok:false, error:{code:"SMOKE_FAILED", message}}
data class SmokeTestResponse(
    val ok: Boolean,
    val data: SmokeTestData?,
    val error: BootstrapError? = null
)

// data del POST /api/setup/auth/antigravity → {mode, command, status}
data class AuthGuideData(
    val mode: String? = null,
    val command: String? = null,
    val status: String? = null
)

// POST /api/setup/auth/antigravity → {ok, data:{mode, command, status}}
data class AuthGuideResponse(
    val ok: Boolean,
    val data: AuthGuideData?,
    val error: BootstrapError? = null
)
