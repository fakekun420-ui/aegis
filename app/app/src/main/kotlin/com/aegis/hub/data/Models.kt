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
        // Solo hay un motor, asi que esto ya no decide nada. Se conserva la firma
        // porque hay 5 llamadas y el campo `provider` sigue viajando en el store.
        return "OpenCode"
    }
}

data class Project(
    val id: String,
    val name: String,
    val description: String? = null,
    val createdAt: String? = null,
    val archivedAt: String? = null,
    val provider: String? = "opencode",
    val folder: String? = null,
    val ponytail: String? = null,
    val sessions: List<SessionRef>? = null,
    val skills: List<Any>? = null,
    val linkedProjects: List<String>? = null
) {
    val resolvedProvider: String get() = "OpenCode"
    val resolvedFolder: String get() = folder ?: "/sdcard/projects/${name.lowercase().replace(" ", "-").replace(Regex("[^a-z0-9_-]"), "")}/"
}

data class CreateProjectRequest(
    val name: String,
    val description: String? = null,
    val provider: String? = "opencode",
    // Ruta de una carpeta YA existente bajo /sdcard/projects para vincularla como
    // proyecto en vez de crear una carpeta nueva a partir del nombre. El backend la
    // valida: debe resolver dentro de PROJECTS_ROOT (FOLDER_OUTSIDE_ROOT) y no puede
    // pertenecer a otro proyecto activo (DUPLICATE_FOLDER).
    val folder: String? = null
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
    val provider: String? = "opencode"
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
    val resolvedTitle: String get() = title ?: name ?: resolvedId.take(8)
    val lastActivityIso: String? get() = updatedAt ?: updatedAtAlt ?: createdAt ?: createdAtAlt
    val resolvedProvider: String get() = "OpenCode"
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

// Bloque de contenido de una tool en el formato nativo de OpenCode v2. El Hub lo
// reenvía verbatim dentro de `state` (providers.js), pero el cliente no lo
// declaraba, así que Gson lo descartaba y la salida de TODA herramienta se perdía.
data class ToolContentBlock(
    val type: String? = null,
    val text: String? = null
)

data class ToolState(
    val status: String? = null,
    val input: Map<String, Any?>? = null,
    // Ruta LEGACY: el normalizador antiguo (providers.js:1541) escribía `output`.
    val output: String? = null,
    // Formato NATIVO v2: el resultado llega como `content` = lista de bloques.
    val content: List<ToolContentBlock>? = null,
    // `metadata` trae sessionID/status (subagentes), truncated, exit, name (skills).
    val metadata: Map<String, Any?>? = null,
    val exitCode: Int? = null,
    val duration: Double? = null
) {
    /**
     * La salida que se debe pintar, venga del formato que venga.
     *
     * MEDIDO 2026-09-29 sobre una sesión real: las 69 tool parts de una sesión con
     * subagentes llegaron con `state.content` (array de bloques) y `state.output`
     * siempre a null. Leer solo `output` es leer siempre null — por eso la tarjeta
     * de una herramienta salía sin resultado. Se prefiere `output` si existe para no
     * cambiar el comportamiento de la ruta legacy, y se aplana `content` si no.
     */
    val outputText: String?
        get() {
            val legacy = output?.takeIf { it.isNotBlank() }
            if (legacy != null) return legacy
            val joined = content
                ?.mapNotNull { it.text }
                ?.filter { it.isNotBlank() }
                ?.joinToString("\n")
                ?.takeIf { it.isNotBlank() }
            return joined
        }

    /**
     * Una invocación de subagente se reconoce por la FORMA de su `input`, no por su
     * nombre: toda herramienta que reciba `agent` es una delegación. No se menciona
     * ningún cargo ni ningún proyecto con nombre propio, así que esto funciona con
     * cualquier subagente que se añada mañana, no solo con los de hoy.
     */
    val isSubagent: Boolean get() = input?.containsKey("agent") == true

    val agentName: String? get() = input?.get("agent") as? String
    val agentDescription: String? get() = input?.get("description") as? String

    /** Sesión hija del subagente. Permite abrir su conversación desde la tarjeta. */
    val subagentSessionId: String? get() = metadata?.get("sessionID") as? String
    val isTruncated: Boolean get() = metadata?.get("truncated") == true

    /**
     * El nombre de la herramienta NO viaja en el payload: el normalizador lo
     * conserva si existe (providers.js:87) y la ruta nativa v2 no lo trae — MEDIDO,
     * las 69 tool parts de una sesión real llegaron sin `tool` ni `callID`. Antes de
     * arreglarlo en la UI, el nombre se infiere de las claves de `input`. Es una
     * etiqueta para la persona, no un valor de lógica: si la inferencia falla se
     * dice "herramienta" en vez de mentir con un "bash" fijo.
     */
    val toolName: String get() {
        val i = input ?: return "herramienta"
        fun has(k: String) = i.containsKey(k)
        return when {
            has("agent") -> "subagente"
            has("command") || has("CommandLine") || has("cmd") -> "bash"
            has("oldString") || has("newString") -> "edit"
            has("path") && has("content") -> "write"
            has("path") && (has("pattern") || has("glob")) -> "search"
            has("path") || has("AbsolutePath") || has("TargetFile") -> "read"
            has("query") || has("url") || has("Url") -> "web"
            has("id") -> "skill"
            else -> "herramienta"
        }
    }

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
    val state: ToolState? = null,
    // Marcador del recorte de payload (server.js -> trimPartText). Con
    // hasBinary=true el Hub retiro una data URI para no mandar decenas de MB de
    // base64; el binario real se pide bajo demanda a /api/sessions/:sid/part.
    val hasBinary: Boolean? = null,
    val binaryChars: Int? = null
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
/**
 * Un agente REAL de OpenCode, tal cual lo publica `GET /api/agent`.
 *
 * MEDIDO 2026-09-30: son 40 — 6 `primary` (orchestrator, Build, Plan y los tres internos
 * de OpenCode) y 34 `subagent` (los 32 cargos de Kaenor + General + Explore). Antes de
 * esto la app no tenia NINGUN modelo de agente y el boton azul era un interruptor de
 * dos: `if (agentMode == "plan") "build" else "plan"`. O sea que de 40 agentes
 * reachables solo se podian elegir dos, y el resto no era que no existieran.
 *
 * `model` es el modelo FIJADO del agente, no el de la sesion: MEDIDO, de los 6 primary
 * solo `orchestrator` lo tiene (`space-bunny-free`); Build y Plan lo dejan en null y
 * usan el de la sesion. Por eso la hoja lo enseña tal cual, sin inventar un valor.
 */
data class OpencodeAgent(
    val name: String,
    val mode: String = "primary",
    val model: String? = null,
    val description: String? = null,
    // Lo que OpenCode marca para NO ensenar en su propio selector. MEDIDO 2026-09-30:
    // de los 40, 37 son visibles y 3 ocultos (Compaction, Title, Summary). Con
    // `mode == "primary" && !hidden` salen exactamente Build, Plan y orchestrator.
    //
    // El valor por defecto es `false` a proposito: si el Hub dejara de mandar el campo,
    // se verian los 6 en vez de 0, que es degradar hacia lo visible y no hacia lo
    // invisible. Un default true esconderia agentes que si valen sin avisar.
    val hidden: Boolean = false
)

/**
 * Los agentes que OpenCode ofrece para cambiar a mano, y SOLO esos.
 *
 * MEDIDO 2026-09-30 sobre el registro crudo: 40 agentes, 37 visibles, 3 ocultos
 * (Compaction, Title, Summary, que son internos de la tuberia de OpenCode). Con
 * `mode == "primary" && !hidden` la lista queda en tres: Build, Plan y orchestrator.
 *
 * Se descartan los 34 subagentes (los cargos de Kaenor y General/Explore) a proposito:
 * son agentes que un agente invoca con la herramienta `task`, no destinations que se
 * elijan para tu sesion. Es lo mismo que hace el propio OpenCode en su selector.
 *
 * No es que no sirvan: MEDIDO, un turno con `agent=kaenor-ai-engineer` corre y contesta.
 * Es que no son lo que el boton esta preguntando. Si algun dia se quieren, el Hub los
 * sigue exponiendo todos en `/api/opencode/agents` y esta funcion es el unico sitio que
 * habria que tocar.
 */
fun List<OpencodeAgent>.seleccionables(): List<OpencodeAgent> =
    filter { it.mode == "primary" && !it.hidden }
        .sortedBy { it.name.lowercase() }

data class ModelOption(
    val id: String,
    val name: String,
    val description: String? = null,
    // El Hub ya lo expone en `free` (/api/models), y lo calcula mirando el COSTE del
    // modelo, no solo si el id acaba en "-free". MEDIDO 2026-09-29: 39 de 472 modelos
    // lo traen a true. Sin este campo la app no puede distinguir un free de uno de
    // pago, y por eso el default acababa hardcodeado a un id que no existe.
    val free: Boolean = false
)

/**
 * El modelo por defecto de una sesion nueva: **Space Bunny Free**.
 *
 * Decision del usuario 2026-10-01. MEDIDO sobre `/api/models` el mismo dia: de 475 modelos,
 * `space-bunny-free` existe y trae `free=true`, y el PRIMERO free de la lista —que es lo que
 * devolvia esta regla— es `longcat-2.5-preview-free`. De ahi "al abrir nuevas sesiones se
 * cambia el modelo".
 *
 * Sigue siendo una REGLA y no un id fijo a pelo, por el motivo que ya esta escrito aqui y
 * que costo caro: el id que se hardcodeo antes ("gemini-3.8-flash-high") no existia entre los
 * 472 modelos, y el Hub avisaba "modelo no encontrado en el indice v2". Si OpenCode deja de
 * ofrecer Space Bunny Free, esta regla cae al primer free en vez de mandar un id que no
 * existe — que es un fallo que se ve, y no uno que se cuela.
 *
 * Si la lista no trae ninguno free, se devuelve null y deja que OpenCode elija: es preferible
 * a inventar un id.
 */
val List<ModelOption>.modeloPorDefecto: String?
    get() = firstOrNull { it.id == ID_MODELO_POR_DEFECTO && it.free }?.id
        ?: firstOrNull { it.free }?.id

/**
 * El id del modelo que abre una sesion nueva. Verificado contra el catalogo vivo, no supuesto.
 */
const val ID_MODELO_POR_DEFECTO = "space-bunny-free"

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

// ===== Formularios / preguntas de herramientas (GET /api/forms) =====
// Cuando una herramienta lanza una pregunta, el TUI del CLI la pinta y se responde
// con flechas + Enter. Estos modelos permiten que Aegis la muestre y la conteste
// con un toque, sin depender del CLI.
data class FormOption(
    val value: String? = null,
    val label: String? = null,
    val description: String? = null
)

data class FormField(
    val key: String? = null,
    val title: String? = null,
    val description: String? = null,
    val type: String? = null,
    val options: List<FormOption>? = null,
    val custom: Boolean? = null
)

/**
 * Parte completa tal y como la devuelve GET /api/sessions/:sid/part.
 *
 * Es lo que pide la tarjeta al desplegarse cuando la parte venia recortada: la lista de
 * mensajes viaja ligera y el binario se recupera solo si el usuario lo quiere ver.
 */
data class PartFull(
    val id: String? = null,
    val type: String? = null,
    val mime: String? = null,
    val filename: String? = null,
    val text: String? = null,
    val output: String? = null,
    val url: String? = null,
    val image: String? = null,
    val data: String? = null,
    val stateContent: List<Map<String, Any?>>? = null
) {
    /** El binario puede venir en cualquiera de los tres huecos; se toma el primero. */
    fun base64(): String? = image ?: data ?: url
}
data class PendingForm(
    val id: String? = null,
    val sessionID: String? = null,
    val title: String? = null,
    val fields: List<FormField>? = null
) {
    val firstField: FormField? get() = fields?.firstOrNull()

    /** Valor exacto a enviar en el cuerpo del reply. */
    fun optionValue(opt: FormOption): String = opt.value ?: opt.label.orEmpty()

    val allFields: List<FormField> get() = fields.orEmpty().filter { !it.key.isNullOrBlank() }

    /** Campos que se pueden contestar con un toque, es decir, los que traen opciones. */
    val optionFields: List<FormField> get() = allFields.filter { !it.options.isNullOrEmpty() }

    /**
     * Campos SIN opciones: no hay nada que tocar, solo se pueden rellenar escribiendo.
     *
     * Importa porque `POST .../reply` RESUELVE el formulario entero con lo que le
     * mandes: un POST con un solo campo descarta el resto en silencio (medido con un
     * formulario real de 3 campos — se respondió q0 y q1/q2 se perdieron). Por eso la
     * UI tiene que juntarlo todo antes de enviar, y avisar de lo que se va a dejar fuera.
     */
    val freeFields: List<FormField> get() = allFields.filter { it.options.isNullOrEmpty() }

    /** ¿Un solo toque basta? Solo cuando hay una única pregunta contestable. */
    val isOneTap: Boolean get() = optionFields.size == 1 && freeFields.isEmpty()
}

/**
 * Cuerpo de POST /api/forms/:sessionId/:formId/reply.
 *
 * OJO: esto NO puede ser `Map<String, Map<String, String>>`. Kotlin lo acepta, pero
 * Retrofit falla EN EJECUCIÓN con "Parameter type must not include a type variable or
 * wildcard" al no poder construir el converter para un genérico anidado. La tarjeta se
 * pintaba bien y el toque reventaba, que es el peor fallo posible: parecía funcionar.
 * Con una clase concreta, Gson la serializa sin problemas.
 */
data class FormReplyBody(val answer: Map<String, String>)

/**
 * Permiso pendiente de una herramienta, tal y como lo devuelve OpenCode 2.0.14
 * (`Permission.Request`). Es lo que el TUI del CLI pinta como "Permission required".
 *
 * Todos los campos vienen como opcionales a proposito: segun la version, la accion
 * trae o no mensaje, y `save` solo viene relleno cuando la peticion admite
 * "permitir siempre". Un campo obligatorio aqui seria un crash en produccion.
 */
data class PendingPermission(
    val id: String? = null,
    val sessionID: String? = null,
    val action: String? = null,
    val resources: List<String>? = null,
    val save: List<String>? = null,
    val message: String? = null
) {
    /** Etiqueta legible de la herramienta ("bash", "edit", ...). */
    val toolName: String get() = action?.takeIf { it.isNotBlank() } ?: "herramienta"

    /** Texto para el cuerpo de la tarjeta: el mensaje del servidor si existe. */
    val explain: String get() = message?.takeIf { it.isNotBlank() }
        ?: resources?.firstOrNull()?.takeIf { it.isNotBlank() }
        ?: "$toolName necesita permiso para continuar"

    /**
     * Si OpenCode ha Proposed reglas permanentes para esta peticion. Si no, "siempre
     * permitir" se comportaria EXACTAMENTE igual que "permitir una vez" (OpenCode solo
     * persiste cuando request.save no viene vacio), asi que la UI lo dice en vez de
     * prometer algo que no ocurre.
     */
    val canPersist: Boolean get() = !save.isNullOrEmpty()
}

/**
 * Cuerpo de la respuesta a un permiso. `decision` es obligatoria: el Hub rechaza
 * con 400 cualquier otro valor.
 */
data class PermissionReplyBody(
    val decision: String,
    val message: String? = null
)

/**
 * Estado de ejecucion de una sesion (GET /api/sessions/inflight).
 *
 * Lo lleva el vigilante del Hub, que se suscribe al stream de eventos de OpenCode.
 * `turnOver` es la unica senal fiable de "el agente termino TODO y esta esperando":
 * `info.time.completed` solo dice que el mensaje se cerro, y eso pasa despues de
 * cada `bash` con exit 0 aunque el agente siga trabajando.
 */
data class InflightSession(
    val id: String? = null,
    val since: Long? = null,
    val turnOver: Boolean = false,
    /**
     * Ultimo instante en el que se vio CUALQUIER evento de la sesion.
     *
     * Es lo que distingue un turno EN MARCHA de un registro que se quedo pegado. El
     * vigilante se desconecta y reconecta, y en el hueco pierde eventos: si se pierde el
     * `succeeded`, la sesion se queda "ocupada" hasta 15 minutos. Con `lastSeen` se
     * detecta: un turno vivo genera eventos a destajo (session.step.*, session.text.*,
     * session.usage.updated...), asi que el silencio prolongado significa registro
     * obsoleto, no turno lento.
     */
    val lastSeen: Long? = null
)

/**
 * Decide si el estado de turno del vigilante es de fiar, en UN solo sitio.
 *
 * Lo consumen DOS vistas (el indicador del chat y el círculo de la lista de chats) y
 * ambas sufrian el mismo bug por caminos separados, asi que la decisión va aquí y no
 * duplicada.
 */
object TurnState {
    /** Silencio máximo tolerado antes de dudar del registro. */
    /**
     * Techo de seguridad por si el vigilante del Hub muere y `turnOver` no llega nunca.
     *
     * ANTES eran 45 s, y ese numero era el defecto: una herramienta de mas de 45 s no
     * emite NADA durante ese rato, asi que el circulo se apagaba mientras el turno seguia
     * vivo. MEDIDO 2026-09-30 en la captura del usuario: `bash(sleep 270; ...)` con
     * "Trabajando en ello..." dentro del chat y SIN circulo en la lista — el mismo turno,
     * dos indicadores, uno cierto y otro apagado. Lo que no puede distinguir "no hay
     * eventos" de "se acabo" es un reloj corto, y esa distincion es justo la que se
     * perdia.
     *
     * `turnOver` es la senal fiable y no necesita reloj. El silencio queda solo como red
     * de seguridad, y 6 h da margen de sobra a un turno: pasado ese punto, o el turno esta
     * colgado o el vigilante murio, y en los dos casos un circulo de mas importa mucho
     * menos que apagarlo antes de tiempo.
     */
    const val MAX_SILENCE_MS = 6L * 60L * 60L * 1000L

    /**
     * ¿Este registro dice "ocupado" y además es de fiar?
     *
     * Se pasa la sesión como parámetro en vez de declarar una extensión de miembro
     * (`fun InflightSession?.isReliableBusy()`): las extensiones de miembro sobre un
     * objeto importado daba "Unresolved reference" en ambos ViewModel, y una llamada
     * explícita no deja lugar a ambigüedad. Sigue siendo el ÚNICO sitio donde se decide.
     */
    fun isBusy(s: InflightSession?, now: Long = System.currentTimeMillis()): Boolean {
        if (s == null || s.turnOver) return false
        val last = s.lastSeen ?: s.since ?: return false
        return now - last < MAX_SILENCE_MS
    }

    /** ¿Este registro dice que el turno terminó? */
    fun isOver(s: InflightSession?): Boolean = s != null && s.turnOver
}
