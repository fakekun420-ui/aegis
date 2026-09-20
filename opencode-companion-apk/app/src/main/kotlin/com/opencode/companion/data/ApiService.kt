package com.opencode.companion.data

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
    suspend fun getModels(): Envelope<List<ModelOption>>
}
