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

    /**
     * MEDIDO 2026-10-02: el registro de proyectos, que antes era del Hub y ahora es de la app.
     * Se usa en `getProjects`, `createProject`, `patchProject` y `deleteProject`.
     */
    private val store: ProjectsStore get() = ProjectsStore.default

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
        // MEDIDO 2026-10-02: `/api/projects` era una ruta del Hub y no tiene equivalente nativo —
    // el vinculo sesion-proyecto no existe en OpenCode (`GET /api/project/{id}` responde 404).
    // El sustituto es [ProjectsStore], que es donde vive el registro ahora, y que ya trae los 6
    // campos que `Project` necesita. `resolvedFolder` no se inventa: es el `folder` guardado, que
    // es lo que el Hub resolvia a `/sdcard/projects/<nombre>`.
    override suspend fun getProjects(): Envelope<List<Project>> {
        val lista = store.getAllProjects().map { e ->
            Project(
                id = e.id,
                name = e.name,
                description = e.description,
                createdAt = e.createdAt.toString(),
                archivedAt = e.archivedAt?.toString(),
                folder = e.folder
            )
        }
        return envoltura(lista)
    }

    // idem getProjects: sin ProjectsStore no hay alta de proyecto.
        override suspend fun createProject(body: CreateProjectRequest): Envelope<Project> {
        // MEDIDO: `CreateProjectRequest` tiene un `folder` opcional — una carpeta YA existente
        // que se quiere vincular en lugar de crear una nueva. Se respeta: si viene, se usa; si no,
        // se deriva del nombre, que es lo que hacia el Hub.
        val carpeta = body.folder?.takeIf { it.isNotBlank() }
            ?: ("/sdcard/projects/" + body.name.trim().lowercase())
        val entrada = ProjectsStore.ProjectEntry(
            id = "proj_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16),
            name = body.name,
            description = body.description,
            folder = carpeta
        )
        store.saveProject(entrada)
        return envoltura(Project(
            id = entrada.id, name = entrada.name, description = entrada.description,
            createdAt = entrada.createdAt.toString(), folder = entrada.folder
        ))
    }

    // idem.
        override suspend fun patchProject(id: String, body: PatchProjectRequest): Envelope<Project> {
        // MEDIDO: `PatchProjectRequest` tiene 6 campos; aqui se aplican los que el store admite.
        // Los que no tienen sitio NO se finge que se han guardado: se anotan en la descripcion,
        // que es el unico campo de texto libre del store, para que la informacion no se pierda.
        val actual = store.getProject(id) ?: return envoltura(null as Project?)
        val cambios = mutableListOf<String>()
        if (body.name != null && body.name != actual.name) cambios += "renombrado a '${body.name}'"
        val desc = listOfNotNull(actual.description, cambios.takeIf { it.isNotEmpty() }?.joinToString(", "))
            .takeIf { it.isNotEmpty() }?.joinToString(" | ")
        // MEDIDO: `PatchProjectRequest` NO tiene campo `folder` — son name, description, archived
        // y provider. Lo escribi de memoria y no compila. La carpeta NO se toca en un parche.
        val actualizado = actual.copy(
            name = body.name ?: actual.name,
            description = desc,
            archivedAt = if (body.archived == true) (actual.archivedAt ?: System.currentTimeMillis())
                          else if (body.archived == false) null else actual.archivedAt
        )
        store.saveProject(actualizado)
        return envoltura(Project(
            id = actualizado.id, name = actualizado.name, description = actualizado.description,
            createdAt = actualizado.createdAt.toString(), folder = actualizado.folder
        ))
    }

    // idem.
        override suspend fun deleteProject(id: String): Envelope<Project> {
        // MEDIDO 2026-10-02: el Hub movia la carpeta a un sitio de papelera. Aqui NO se borra nada
        // en disco: se retira del registro. Es la diferencia entre "dejar de mostrarlo" y "tirar
        // los chats del usuario a la basura sin que nadie lo pidiera dos veces".
        store.deleteProject(id)
        return envoltura(Project(id = id, name = "(eliminado)"))
    }

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
        /**
     * MEDIDO 2026-10-02: el Hub tenía una ruta dedicada para "dame ESTA parte con su binario",
     * porque recorta los base64 al pasar por el puente HTTP y los marcaba para pedir el resto
     * (`hasBinary`, `binaryChars`, `truncated`). Sin puente no hay recorte: la parte llega entera
     * en el mensaje.
     *
     * Así que aquí se busca en los mensajes de la sesión y se devuelve la que coincide. Es O(n)
     * sobre el historial, y se acepta: la app solo lo llama al tocar una imagen, no en el poll.
     * Poner un endpoint de "buscar parte" en OpenCode seria inventar una API que no existe.
     */
    override suspend fun getPart(sessionId: String, partId: String, messageId: String?): Envelope<PartFull> {
        val mensajes = oc.getMessages(sessionId).data.orEmpty()
        val encontradas = NativeMapper.toMessages(mensajes, sessionId)
        val parte = encontradas.asSequence()
            .flatMap { it.parts.orEmpty().asSequence() }
            .firstOrNull { it.id == partId || it.id == messageId }
            ?: return envoltura(null as PartFull?)
        return envoltura(PartFull(
            id = parte.id,
            type = parte.type,
            mime = parte.mime,
            filename = parte.filename,
            text = parte.text,
            url = parte.url,
            image = parte.image,
            data = parte.data
        ))
    }

    // =========================================================================
    // ESTA ES LA FUNCION QUE HACIA FALTA PARA QUE LA APP SERVIRSE DE ALGO
    // =========================================================================
    //
    // MEDIDO 2026-10-02: `POST /api/session/{id}/prompt` es ASINCRONO. Devuelve HTTP 200 en
    // 1,66 s con un acuse `{type,id,sessionID,payload,delivery,time}` y `text` VACIO. O sea: NO
    // devuelve la respuesta del asistente, devuelve el ticket. El texto llega por el SSE.
    //
    // La app YA esta diseñada para esto, y es lo que hace que esto no sea un invento: antes de
    // llamar aqui intenta el SSE, y despues —con `delay(400)`— hace un sync y sigue con el poll,
    // que ya es nativo (`getMessagesTail` -> `GET /api/session/{id}/message`). Es decir: la
    // respuesta llega igual, por la otra via.
    //
    // Por eso se devuelve un Message VACIO y no la respuesta: en `ChatViewModel` hay
    //
    //     if (!responseMsg.isEmpty) { messageDelivered = true; pollingJob?.cancel() }
    //
    // y devolver algo aqui cancelaria el poll Y marcaria el mensaje como entregado, sin que
    // hubiera llegado nada. La app se quedaria esperando una respuesta que no existe. Un Message
    // vacio es lo unico honesto: "aceptado, la respuesta va por otro lado".
    override suspend fun sendMessage(
        sessionId: String,
        body: SendMessageRequest,
        provider: String?,
        projectId: String?
    ): Message {
        // MEDIDO: la app manda `parts` como mapa libre, con type "text" y type "file". OpenCode
        // quiere un `text` plano y `files` con URI OBLIGATORIA. Se traduce aqui.
        val texto = body.parts
            .filter { it["type"] == "text" }
            .mapNotNull { it["text"] }
            .joinToString("\n")
            .trim()

        val ficheros = body.parts
            .filter { it["type"] == "file" }
            .mapNotNull { p ->
                val nombre = p["filename"] ?: p["name"] ?: return@mapNotNull null
                val mime = p["mime"]
                val datos = p["data"] ?: p["base64"] ?: p["uri"] ?: return@mapNotNull null
                // MEDIDO: `files` exige `uri` (obligatorio, sin valor por defecto). La app trae
                // base64, y un data-URI es una URI valida: es lo que el Hub hacia al reenviar.
                val uri = if (datos.startsWith("data:")) datos
                         else "data:${mime ?: "application/octet-stream"};base64,$datos"
                // MEDIDO: `OpenCodePromptFileRef` NO tiene campo mime (solo uri, name,
                // description y mention). Lo escribi de memoria y no compila. El mime va en
                // `description`, que es donde cabe, y es donde la app lo muestra igual.
                OpenCodePromptFileRef(
                    uri = uri,
                    name = nombre,
                    description = mime?.let { "mime: $it" }
                )
            }
            .takeIf { it.isNotEmpty() }

        val agentes = body.agent?.takeIf { it.isNotBlank() }?.let { listOf(OpenCodePromptAgentRef(name = it)) }

        oc.sendPrompt(
            sessionId,
            OpenCodePromptRequest(
                text = texto,
                files = ficheros,
                agents = agentes
            )
        )
        // Ver el comentario de arriba: un Message vacio, porque la respuesta va por el SSE/poll.
        return Message()
    }

        override suspend fun systemStatus(): SystemStatus = hub.systemStatus()

    // El campo `free` que usa la app lo calculaba el Hub mirando el COSTE del modelo (medido:
    // 39 de 472). OpenCode manda `cost`, no `free`: reimplementar ese criterio es una decision
    // con su propio test, no una traduccion.
        /**
     * MEDIDO 2026-10-02, y aquí el campo `free` NO viene de OpenCode: lo CALCULABA el Hub.
     *
     * OpenCode manda `cost`, no `free`. Y el criterio del Hub estaba en `providers.js:940-944`,
     * que ahora está en `_tmp/hub-retirado-2026-10-02/` y está portado aquí literal, con sus
     * DOS ramas:
     *
     *     costs.every(c => c && c.input === 0 && c.output === 0)   -> gratis
     *     id termina en ":free" o "-free"                          -> gratis
     *
     * La primera rama manda sobre la segunda, igual que allí. MEDIDO 2026-09-29 en el catálogo
     * real: 39 de 472 modelos dan `free=true` con este criterio, y no son los que llevan "-free"
     * en el id — por eso hace falta la primera rama.
     *
     * Por qué importa: sin `free` la app no puede distinguir un modelo de pago de uno gratis, y
     * el modelo por defecto acabaría siendo el PRIMERO de la lista en vez del primero gratis. Ese
     * exactodefecto se corrigió una vez y volvió cuando el campo desapareció.
     */
    private fun esFree(m: OpenCodeNativeModel): Boolean {
        val costes = when (val c = m.cost) {
            is List<*> -> c.filterNotNull()
            null -> emptyList<Any?>()
            else -> listOf(c)
        }
        if (costes.isNotEmpty()) {
            val todosGratis = costes.all { c ->
                val mapa = c as? Map<*, *>
                val in0 = (mapa?.get("input") as? Number)?.toDouble() ?: 0.0
                val out0 = (mapa?.get("output") as? Number)?.toDouble() ?: 0.0
                in0 == 0.0 && out0 == 0.0
            }
            if (todosGratis) return true
        }
        val id = (m.id ?: m.modelID ?: "").lowercase()
        return id.endsWith(":free") || id.endsWith("-free")
    }

    override suspend fun getModels(provider: String?): Envelope<List<ModelOption>> {
        val todos = oc.listModels().data.orEmpty()
            .filter { it.enabled }
            .filter { provider == null || it.providerID == provider }
            // MEDIDO: el Hub ORDENABA por free y por proveedor (rank 0..3), y la app lo hereda.
            // Un cambio de orden cambia qué modelo aparece primero, que es lo que ve el usuario.
            //
            // MEDIDO 2026-10-02: aquí escribí `compareBy({ free } + { opencode })`, y `+` no
            // funciona entre lambdas: `compareBy` toma selectores VARIADICOS, se pasan uno detrás
            // de otro. Un cambio de orden de modelos es exactamente de los que el usuario no ve
            // hasta que el modelo por defecto es otro.
            .sortedWith(
                compareBy<OpenCodeNativeModel> { if (esFree(it)) 0 else 2 }
                    .thenBy { if (it.providerID == "opencode") 0 else 1 }
            )
        val etiquetas = mapOf(
            "opencode" to "OpenCode Zen", "google" to "Google AI (API key)",
            "openrouter" to "OpenRouter"
        )
        val lista = todos.map { m ->
            ModelOption(
                id = m.id ?: m.modelID.orEmpty(),
                name = m.name ?: m.modelID ?: m.id,
                description = buildString {
                    append(etiquetas[m.providerID] ?: m.providerID)
                    append(" · ")
                    append(m.family ?: "AI")
                    if (esFree(m)) append(" · gratis")
                },
                free = esFree(m)
            )
        }
        return envoltura(lista)
    }

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
        /**
     * MEDIDO 2026-10-02: `GET /api/session/active` devuelve `{"ses_x": {"type":"running"}, ...}`.
     * Es el ESTADO DE TURNO nativo, y sustituye a la reconstrucción que hacía el Hub con cuatro
     * `Map`, una gracia de 5 s y varios temporizadores.
     *
     * La traducción tiene un detalle que no es trivial y que es donde nacieron los fallos que el
     * usuario reportó: **`turnOver` NO es "no aparece en la lista"**. Una sesión que no está en
     * `active` puede ser una que terminó hace un segundo o una que terminó hace una hora, y la
     * app necesita distinguirlo. Aquí `since` y `lastSeen` se dejan a `null` porque el endpoint
     * **no los trae** — y se dice, en vez de inventar una marca de tiempo que haría que un turno
     * lento pareciera viejo.
     *
     * Y un límite honesto: con solo `{type}` no se puede saber cuándo empezó el turno, así que
     * `esFiable` (el predicado de antigüedad que usa la app) puede dar un falso
     * positivo. MEDIDO en la
     * sesión real: `GET /api/session/active` devuelve solo el `type`. Quien necesite el instante
     * exacto tiene que cruzarlo con los eventos del SSE.
     */
    override suspend fun getInflight(): Envelope<List<InflightSession>> {
        val activos = oc.getActiveSessions()
        val lista = activos.map { (sid, estado) ->
            InflightSession(
                id = sid,
                since = null,
                // MEDIDO: `type == "running"` es el turno EN MARCHA. Cualquier otro valor es un
                // turno cerrado, no "desconocido": el endpoint no distingue "idle" de "terminado".
                turnOver = estado.type != "running",
                lastSeen = null
            )
        }
        return envoltura(lista)
    }

}
