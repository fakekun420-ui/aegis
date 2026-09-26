package com.aegis.hub.data

import retrofit2.Response
import retrofit2.http.*

interface ApiService {
    @GET("api/projects")
    suspend fun getProjects(): Envelope<List<Project>>

    @POST("api/projects")
    suspend fun createProject(@Body body: CreateProjectRequest): Envelope<Project>

    @PATCH("api/projects/{id}")
    suspend fun patchProject(@Path("id") id: String, @Body body: PatchProjectRequest): Envelope<Project>

    @DELETE("api/projects/{id}")
    suspend fun deleteProject(@Path("id") id: String): Envelope<Project>

    @GET("api/opencode/sessions")
    suspend fun getOpencodeSessions(): Envelope<List<OpencodeSession>>

    @PATCH("api/opencode/sessions/{id}")
    suspend fun renameSession(@Path("id") id: String, @Body body: Map<String, String>): Envelope<Map<String, Any>>

    @DELETE("api/opencode/sessions/{id}")
    suspend fun deleteSession(@Path("id") id: String): Envelope<Map<String, Any>>

    @POST("api/opencode/sessions/{id}/pin")
    suspend fun pinSession(@Path("id") id: String): Envelope<PinResponse>

    @POST("api/opencode/sessions/{id}/unpin")
    suspend fun unpinSession(@Path("id") id: String): Envelope<PinResponse>

    @POST("api/projects/{id}/sessions")
    suspend fun linkSession(@Path("id") projectId: String, @Body body: LinkSessionRequest): Envelope<SessionRef>

    @DELETE("api/projects/{id}/sessions/{sessionId}")
    suspend fun unlinkSession(@Path("id") projectId: String, @Path("sessionId") sessionId: String): Envelope<Map<String, String>>

    @GET("api/projects/{id}/sessions")
    suspend fun getProjectSessions(@Path("id") projectId: String): Envelope<List<SessionRef>>

    @GET("api/skills")
    suspend fun getSkills(@Query("projectId") projectId: String? = null): Envelope<SkillListResponse>

    @POST("api/skills")
    suspend fun createSkill(@Body body: SkillCreateRequest): Envelope<Skill>

    @PATCH("api/skills/{scope}/{name}")
    suspend fun updateSkill(@Path("scope") scope: String, @Path("name") name: String, @Body body: Map<String, String>): Envelope<Skill>

    @DELETE("api/skills/{scope}/{name}")
    suspend fun deleteSkill(@Path("scope") scope: String, @Path("name") name: String): Envelope<Map<String, String>>

    @GET("api/opencode/sessions/{id}/messages")
    suspend fun getMessages(@Path("id") sessionId: String): Envelope<List<Message>>

    // Send message via hub proxy POST /opencode/session/:id/message (handles injection)
    @POST("opencode/session/{id}/message")
    suspend fun sendMessage(
        @Path("id") sessionId: String,
        @Body body: SendMessageRequest,
        @Header("X-Provider") provider: String? = null,
        @Header("X-Project-Id") projectId: String? = null
    ): Message

    @GET("api/system/status")
    suspend fun systemStatus(): SystemStatus

    @GET("api/opencode/models")
    suspend fun getModels(@Query("provider") provider: String? = null): Envelope<List<ModelOption>>

    // System
    @GET("api/system/health")
    suspend fun getSystemHealth(): Response<HealthResponse>

    @GET("api/system/logs")
    suspend fun getSystemLogs(@Query("limit") limit: Int = 100): Response<LogsResponse>

    @GET("api/system/memory")
    suspend fun getSystemMemory(): Response<MemoryResponse>

    // Skills
    @GET("api/skills")
    suspend fun getSystemSkills(): Response<SkillsResponse>

    @POST("api/skills/install")
    suspend fun installSkill(@Body body: InstallSkillRequest): Response<TaskResponse>

    @DELETE("api/skills/{id}")
    suspend fun uninstallSkill(@Path("id") skillId: String): Response<BaseResponse>

    @GET("api/skills/{id}/config")
    suspend fun getSkillConfig(@Path("id") skillId: String): Response<SkillConfigResponse>

    @PATCH("api/skills/{id}/config")
    suspend fun updateSkillConfig(
        @Path("id") skillId: String,
        @Body config: Map<String, Any>
    ): Response<BaseResponse>

    // Workspace / Projects
    @GET("api/workspace/projects")
    suspend fun getWorkspaceProjects(): Response<ProjectsResponse>

    @POST("api/workspace/projects/{id}/init")
    suspend fun initProject(@Path("id") projectId: String): Response<BaseResponse>

    @GET("api/workspace/projects/{id}/state")
    suspend fun getProjectState(@Path("id") projectId: String): Response<ProjectStateResponse>

    @POST("api/workspace/projects/{id}/index")
    suspend fun indexProject(@Path("id") projectId: String): Response<TaskResponse>

    // Agents
    @GET("api/agents")
    suspend fun getAgents(): Response<AgentsResponse>

    @POST("api/agents/dispatch")
    suspend fun dispatchAgent(@Body body: DispatchAgentRequest): Response<TaskResponse>

    @GET("api/agents/status/{projectId}")
    suspend fun getAgentStatus(@Path("projectId") projectId: String): Response<AgentStatusResponse>

    // Workflows
    @GET("api/workflows/{projectId}")
    suspend fun getWorkflows(@Path("projectId") projectId: String): Response<WorkflowsResponse>

    @POST("api/workflows/{projectId}/run")
    suspend fun runWorkflow(
        @Path("projectId") projectId: String,
        @Body body: RunWorkflowRequest
    ): Response<TaskResponse>

    @GET("api/workflows/{projectId}/status")
    suspend fun getWorkflowStatus(@Path("projectId") projectId: String): Response<WorkflowStatusResponse>

    // Jobs
    @GET("api/jobs")
    suspend fun getJobs(): Response<JobsResponse>

    @POST("api/jobs/{id}/run")
    suspend fun runJob(@Path("id") jobId: String): Response<BaseResponse>

    // F1 — Bootstrap / asistente de configuración inicial (contrato /api/bootstrap/*)
    @GET("api/bootstrap/state")
    suspend fun getBootstrapState(): Response<BootstrapResponse>

    @POST("api/bootstrap/run")
    suspend fun runBootstrap(@Body body: BootstrapRunRequest): Response<BootstrapActionResponse>

    @POST("api/bootstrap/step/{id}/retry")
    suspend fun retryBootstrapStep(@Path("id") id: String): Response<BootstrapActionResponse>

    @POST("api/bootstrap/cancel")
    suspend fun cancelBootstrap(): Response<BootstrapActionResponse>

    // F3 — Verificación final, smoke test de la IA y guía de auth (contrato /api/setup/*)
    // Los POST sin body siguen el patrón de retryBootstrapStep()/cancelBootstrap()
    // (Retrofit no exige @Body cuando el hub no lo recibe).
    @GET("api/setup/final-check")
    suspend fun getFinalCheck(): Response<FinalCheckResponse>

    @POST("api/setup/smoke-test")
    suspend fun runSmokeTest(): Response<SmokeTestResponse>

    @POST("api/setup/auth/antigravity")
    suspend fun runAuthGuide(): Response<AuthGuideResponse>

    // ===== Formularios / preguntas de herramientas =====
    // El proxy generico /opencode/* devuelve 401 (no anade el Basic de OpenCode),
    // asi que el Hub expone rutas propias con auth. Ver server.js /api/forms.
    @GET("api/forms")
    suspend fun getPendingForms(@Query("sessionId") sessionId: String?): Envelope<List<PendingForm>>

    @POST("api/forms/{sessionId}/{formId}/reply")
    suspend fun replyForm(
        @Path("sessionId") sessionId: String,
        @Path("formId") formId: String,
        @Body body: FormReplyBody
    ): Envelope<Map<String, Any>>
}
