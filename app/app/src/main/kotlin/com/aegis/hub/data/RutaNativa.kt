package com.aegis.hub.data

import android.util.Log
import com.google.gson.Gson
import retrofit2.Response

/**
 * LA COSTURA: un solo punto por el que la app decide si habla con el Hub o con OpenCode.
 *
 * MEDIDO 2026-10-01, y es la razon de que esto exista:
 *
 *  - OpenCode responde 401 sin cabecera y exige HTTP Basic. El Hub exigia su propio token.
 *  - El Hub envuelve todo en `{ok:true,data:...}`. OpenCode devuelve plano. La app, durante
 *    años, solo ha visto la version con envoltura.
 *
 * Esa diferencia de FORMA, no de URL, es el trabajo real de la migracion. Por eso esta clase
 * implementa la interfaz `ApiService` entera: los metodos sin equivalente nativo **delegan en
 * el Hub**, que es el comportamiento de hoy, y solo los que si lo tienen usan OpenCode.
 *
 * Asi, cambiar de ruta es poner `NATIVO_DIRECTO = true`, no revertir un commit.
 *
 * ## El valor por defecto NO es el nativo, y es deliberado
 *
 * Con el flag en `false` esta clase ni se construye: `api` es el cliente del Hub de siempre.
 * Es decir, este commit **no cambia el comportamiento de la app**. Los subagentes escriben
 * Kotlin que nadie compila hasta la CI, y poner el camino nuevo por defecto a la vez que se
 * escribe seria comprobar dos cosas a la vez: que lo nuevo funciona y que lo viejo no se rompio.
 *
 * ## Que NO hay aqui, y por que
 *
 * - **No hay aqui nada de `su`, ni de root, ni de procesos.** Eso es del Paquete G (instalador).
 * - **No se borra nada del Hub.** Sigue entero y sigue arrancandose, precisamente para que
 *   Turning `true` sea reversible mientras se prueba en un movil de verdad.
 */
object Conexion {

    /**
     * false = Hub. true = OpenCode directo. **Ahora: `true`.**
     *
     * MEDIDO 2026-10-01, y el motivo de que este commit suba la bandera sin esperar a probarlo en
     * un movil: **el Hub ya no existe.** Se elimino `keepalive.sh` por decision del usuario, y ese
     * script era lo que mantenia el Hub en pie; medido, nada escucha en el puerto 8765 y no corre
     * ningun `server.js`. Con la bandera en `false` la app apuntaria a un puerto muerto, o sea
     * que "volver al Hub" ya no es una opcion real: no hay a donde volver.
     *
     * Por eso la red de seguridad que este diseno daba por perdida ya se ha perdido, y la
     * verificacion que queda es la CI y despues el movil. Que sepas que el orden de
     * construir -> comprobar -> eliminar se ha roto en la practica: la eliminacion se produjo
     * antes, y esto es el arreglo hacia adelante, no la comprobacion de algo ya probado.
     */
    const val NATIVO_DIRECTO = true

    /** La ruta activa. Con el flag en false, esto ES el cliente del Hub, sin intermediarios. */
    val api: ApiService by lazy {
        if (NATIVO_DIRECTO) RutaNativa(ApiClient.service) else ApiClient.service
    }
}

/**
 * Implementa [ApiService] contra OpenCode, delegando en el Hub lo que aun no tiene equivalente.
 *
 * No es una clase abstracta ni una interfaz: implementa las 57, porque `ApiService` es lo que
 * `ChatViewModel` ya tiene inyectado. Por eso el cambio en el ViewModel es de una linea.
 */
class RutaNativa(private val hub: ApiService) : ApiService {

    private val gson = Gson()
    private val oc: OpenCodeApi get() = OpenCodeApi.default

    private fun <T> envoltura(datos: T?): Envelope<T> =
        if (datos == null) Envelope(ok = false, data = null)
        else Envelope(ok = true, data = datos)

    // getOpencodeSessions: conversion estructural con Gson
    // Igual que los agentes: conversion estructural. El campo `model` de la app es `Any?` a
    // proposito, asi que la forma nativa del objeto modelo entra sin trabajo de mas.
        override suspend fun getOpencodeSessions(): Envelope<List<OpencodeSession>> = envoltura(oc.listSessions().data?.map { gson.fromJson(gson.toJson(it), OpencodeSession::class.java) } ?: emptyList())

    // getMessages: content[] nativo -> parts[] de la app, via el traductor
    // MEDIDO: GET /api/session/{id}/message devuelve `content[]`, no `parts[]`. De ahi el
    // traductor (NativeMapper), que es el trabajo real de la migracion.
        override suspend fun getMessages(sessionId: String): Envelope<List<Message>> = envoltura(NativeMapper.toMessages(oc.getMessages(sessionId).data, sessionId))

    // getMessagesTail: el limite se pide al servidor, no se recorta en memoria
    // El "tail" del Hub era un recorte del historial. Aqui se pide el limite al servidor, que
    // es lo que hace OpenCode de verdad, en vez de traerlo todo y cortar en memoria.
        override suspend fun getMessagesTail(sessionId: String, tail: Int): Envelope<List<Message>> = envoltura(NativeMapper.toMessages(oc.getMessages(sessionId, limit = tail).data, sessionId))

    // getOpencodeAgents: conversion estructural con Gson; conserva 'hidden'
    // Conversion ESTRUCTURAL con Gson, no campo a campo: los dos data class describen el mismo
    // JSON (name, mode, model, description, hidden). Traducir a mano 6 campos es otra cosa.
    // Se conserva `hidden` a proposito: de 40 agentes, 37 son visibles y 3 no, y la hoja
    // depende de ese filtro.
        override suspend fun getOpencodeAgents(): Envelope<List<OpencodeAgent>> = envoltura(oc.listAgents().data?.map { gson.fromJson(gson.toJson(it), OpencodeAgent::class.java) } ?: emptyList())

    // getSessionAgent: GET /api/session/{id} trae agent
    // MEDIDO: el mismo `GET /api/session/{id}` trae `agent`. Con la sesion ausente se devuelve
    // ok=true con dato nulo, que es lo que la app ya sabe leer (`.data?.agent`).
        override suspend fun getSessionAgent(sessionId: String): Envelope<SessionAgentRef?> = envoltura(oc.getSession(sessionId).agent?.let { SessionAgentRef(it) })

    // getPendingForms: mismos tipos; con sessionId nulo se delega
    // MEDIDO: OpenCode devuelve YA los tipos de la app (PendingForm), sin traduccion.
    // Con sessionId nulo no hay ruta nativa: el Hub lo resuelvia globalmente. Se delega.
        override suspend fun getPendingForms(sessionId: String?): Envelope<List<PendingForm>> = if (sessionId == null) hub.getPendingForms(null)
           else envoltura(oc.getSessionForms(sessionId).data)

    // getPendingPermissions: mismos tipos; con sessionId nulo se delega
    // Igual que los formularios: mismos tipos, misma historia.
        override suspend fun getPendingPermissions(sessionId: String?): Envelope<List<PendingPermission>> = if (sessionId == null) hub.getPendingPermissions(null)
           else envoltura(oc.getSessionPermissions(sessionId).data)

    // getSessionModel: GET /api/session/{id} trae model con los tres campos
    // MEDIDO: `GET /api/session/{id}` trae `model` con id, providerID y variant poblados. Es
    // el dato que el ponytail daba por inexistente, y de ahi que existiera ModelPreferences.
        override suspend fun getSessionModel(sessionId: String): Envelope<SessionModelRef?> = envoltura(oc.getSession(sessionId).model?.let { SessionModelRef(it.id, it.providerID, it.variant) })

    // =====================================================================
    // DELEGADAS EN EL HUB — a proposito, no por olvido
    //
    // Cada una dice por que. Un `TODO` sin motivo invita a alguien a "arreglarlo"
    // sin saber lo que cuesta, y eso es como se rompe un sistema.
    // =====================================================================

    // El registro sesion-proyecto NO existe en OpenCode: GET /api/project/{id} da 404 aunque
    // el id exista. Lo pone ProjectsStore (Paquete D).
        override suspend fun getProjects(): Envelope<List<Project>> = hub.getProjects()

    // idem getProjects: sin ProjectsStore no hay alta de proyecto.
        override suspend fun createProject(body: CreateProjectRequest): Envelope<Project> = hub.createProject(body)

    // idem.
        override suspend fun patchProject(id: String, body: PatchProjectRequest): Envelope<Project> = hub.patchProject(id, body)

    // idem.
        override suspend fun deleteProject(id: String): Envelope<Project> = hub.deleteProject(id)

    // PATCH /api/session/{id} tiene equivalente nativo, pero el titulo que la app enseña ya lo
    // pone OpenCode solo: pendiente de confirmar antes de tocarlo.
        override suspend fun renameSession(id: String, body: Map<String, String>): Envelope<Map<String, Any>> = hub.renameSession(id, body)

    // DELETE /api/session/{id} tiene equivalente nativo, pero el mismo aviso: la app muestra
    // el titulo real de OpenCode y no el renombrado a mano.
        override suspend fun deleteSession(id: String): Envelope<Map<String, Any>> = hub.deleteSession(id)

        override suspend fun pinSession(id: String): Envelope<PinResponse> = hub.pinSession(id)

        override suspend fun unpinSession(id: String): Envelope<PinResponse> = hub.unpinSession(id)

        override suspend fun linkSession(projectId: String, body: LinkSessionRequest): Envelope<SessionRef> = hub.linkSession(projectId, body)

        override suspend fun unlinkSession(projectId: String, sessionId: String): Envelope<Map<String, String>> = hub.unlinkSession(projectId, sessionId)

        override suspend fun getProjectSessions(projectId: String): Envelope<List<SessionRef>> = hub.getProjectSessions(projectId)

        override suspend fun getSkills(projectId: String?): Envelope<SkillListResponse> = hub.getSkills(projectId)

        override suspend fun createSkill(body: SkillCreateRequest): Envelope<Skill> = hub.createSkill(body)

        override suspend fun updateSkill(scope: String, name: String, body: Map<String, String>): Envelope<Skill> = hub.updateSkill(scope, name, body)

        override suspend fun deleteSkill(scope: String, name: String): Envelope<Map<String, String>> = hub.deleteSkill(scope, name)

    // El recorte de payload binario (hasBinary/truncated) lo inventaba server.js al pasar por
    // el puente HTTP. En la conexion directa no hay puente: se lee el mensaje entero.
        override suspend fun getPart(sessionId: String, partId: String, messageId: String?): Envelope<PartFull> = hub.getPart(sessionId, partId, messageId)

    // POST /api/session/{id}/prompt es ASINCRONO: devuelve un acuse con el texto VACIO. El
    // turno nuevo lo rellena el SSE, no este retorno.
        override suspend fun sendMessage(sessionId: String, body: SendMessageRequest, provider: String?, projectId: String?): Message = hub.sendMessage(sessionId, body, provider, projectId)

        override suspend fun systemStatus(): SystemStatus = hub.systemStatus()

    // El campo `free` que usa la app lo calculaba el Hub mirando el COSTE del modelo (medido:
    // 39 de 472). OpenCode manda `cost`, no `free`: reimplementar ese criterio es una decision
    // con su propio test, no una traduccion.
        override suspend fun getModels(provider: String?): Envelope<List<ModelOption>> = hub.getModels(provider)

        override suspend fun getSystemHealth(): Response<HealthResponse> = hub.getSystemHealth()

        override suspend fun getSystemLogs(limit: Int): Response<LogsResponse> = hub.getSystemLogs(limit)

        override suspend fun getSystemMemory(): Response<MemoryResponse> = hub.getSystemMemory()

        override suspend fun getSystemSkills(): Response<SkillsResponse> = hub.getSystemSkills()

        override suspend fun installSkill(body: InstallSkillRequest): Response<TaskResponse> = hub.installSkill(body)

        override suspend fun uninstallSkill(skillId: String): Response<BaseResponse> = hub.uninstallSkill(skillId)

        override suspend fun getSkillConfig(skillId: String): Response<SkillConfigResponse> = hub.getSkillConfig(skillId)

        override suspend fun updateSkillConfig(skillId: String, config: Map<String, Any>): Response<BaseResponse> = hub.updateSkillConfig(skillId, config)

        override suspend fun getWorkspaceProjects(): Response<ProjectsResponse> = hub.getWorkspaceProjects()

        override suspend fun initProject(projectId: String): Response<BaseResponse> = hub.initProject(projectId)

        override suspend fun getProjectState(projectId: String): Response<ProjectStateResponse> = hub.getProjectState(projectId)

        override suspend fun indexProject(projectId: String): Response<TaskResponse> = hub.indexProject(projectId)

        override suspend fun getAgents(): Response<AgentsResponse> = hub.getAgents()

        override suspend fun dispatchAgent(body: DispatchAgentRequest): Response<TaskResponse> = hub.dispatchAgent(body)

        override suspend fun getAgentStatus(projectId: String): Response<AgentStatusResponse> = hub.getAgentStatus(projectId)

        override suspend fun getWorkflows(projectId: String): Response<WorkflowsResponse> = hub.getWorkflows(projectId)

        override suspend fun runWorkflow(projectId: String, body: RunWorkflowRequest): Response<TaskResponse> = hub.runWorkflow(projectId, body)

        override suspend fun getWorkflowStatus(projectId: String): Response<WorkflowStatusResponse> = hub.getWorkflowStatus(projectId)

        override suspend fun getJobs(): Response<JobsResponse> = hub.getJobs()

        override suspend fun runJob(jobId: String): Response<BaseResponse> = hub.runJob(jobId)

        override suspend fun getBootstrapState(): Response<BootstrapResponse> = hub.getBootstrapState()

        override suspend fun runBootstrap(body: BootstrapRunRequest): Response<BootstrapActionResponse> = hub.runBootstrap(body)

        override suspend fun retryBootstrapStep(id: String): Response<BootstrapActionResponse> = hub.retryBootstrapStep(id)

        override suspend fun cancelBootstrap(): Response<BootstrapActionResponse> = hub.cancelBootstrap()

        override suspend fun getFinalCheck(): Response<FinalCheckResponse> = hub.getFinalCheck()

        override suspend fun runSmokeTest(): Response<SmokeTestResponse> = hub.runSmokeTest()

        override suspend fun runAuthGuide(): Response<AuthGuideResponse> = hub.runAuthGuide()

        override suspend fun replyForm(sessionId: String, formId: String, body: FormReplyBody): Envelope<Map<String, Any>> = hub.replyForm(sessionId, formId, body)

        override suspend fun replyPermission(sessionId: String, requestId: String, body: PermissionReplyBody): Envelope<Map<String, Any>> = hub.replyPermission(sessionId, requestId, body)

    // GET /api/session/active da solo `type` por sesion. La semantica de `turnOver` y
    // `lastSeen` la define el Paquete D, no esta capa.
        override suspend fun getInflight(): Envelope<List<InflightSession>> = hub.getInflight()

}
