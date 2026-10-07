package com.aegis.hub.data

import com.google.gson.annotations.SerializedName

/**
 * Bootstrap, setup, verificacion y auth (F8: trozo de Models.kt, mismo paquete).
 */

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


// F8.4: supervivientes de ModelsHub.kt (mismo contenido).

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
