package com.aegis.hub.data

/**
 * DTO de la epoca del Hub ("Aegis Phase 11"): salud del sistema, skills, agentes,
 * workflows, jobs y logs. MEDIDO 2026-10-03: movidos aqui desde Models.kt sin tocar ni
 * una coma, mismo paquete, cero imports que cambiar en los 7 ficheros que los usan.
 */
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

data class AgentItem(val id: String, val name: String, val status: String)

data class WorkflowItem(val id: String, val name: String, val steps: Int)
data class WorkflowsResponse(val ok: Boolean, val data: List<WorkflowItem>?)
data class RunWorkflowRequest(val workflowId: String)
data class WorkflowStatusResponse(val ok: Boolean, val data: WorkflowStatus?)
data class WorkflowStatus(val id: String, val status: String, val currentStep: String?, val progress: Int)

data class SkillConfigResponse(val ok: Boolean, val data: Map<String, Any>?)

