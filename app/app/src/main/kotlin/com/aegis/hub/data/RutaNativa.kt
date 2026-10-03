package com.aegis.hub.data

import android.util.Log
// MEDIDO 2026-10-03: `RootShell` esta en el paquete `com.aegis.hub`, no en `data/`.
// Es el segundo vez que lo doy por hecho; lo pillo el mismo comprobador de simbolos
// que lo pillo la primera, asi que el control positivo existe y funciona.
import com.aegis.hub.RootShell
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

    /**
     * Fallo CON MOTIVO. `envoltura(null)` devuelve `ok=false` y `data=null`, y en la UI eso es
     * indistinguible de "no hay nada": la pantalla se queda vacia sin decir por que.
     *
     * Importa mas aqui que en el resto de la costura porque estas operaciones son las que el
     * usuario PULSA —renombrar, desvincular— y un boton que no hace nada y ademas lo calla es la
     * peor de las dosCombinaciones.
     */
    private fun <T> envolturaFallo(motivo: String): Envelope<T> =
        Envelope(ok = false, data = null, error = ErrorBody(code = "nativo", message = motivo))

        // MEDIDO 2026-10-02: `GET /api/session` devuelve `id`, `title`, `agent`, `model` (OBJETO),
        // `time` (OBJETO con created/updated/idle), `cost`, `tokens`, `projectID` y `location`.
        //
        // Este mapa NO usa la conversion con Gson que si usaba en los agentes: alli el campo
        // `model` es un objeto donde la app espera un `String`, y Gson fallaba al DESERIALIZAR.
        // Aqui `OpencodeSession.model` es `Any?` a proposito, asi que el objeto entra sin drama —
        // y por eso este mapeo es explicito campo a campo y el de agentes no lo podia ser.
        override suspend fun getOpencodeSessions(): Envelope<List<OpencodeSession>> {
            // MEDIDO el 2026-10-02: `GET /api/session` devuelve las sesiones de
            // primer nivel Y las que crean los subagentes, mezcladas y sin
            // ningun flag que las separe. La lista de "Chats" por eso se
            // llenaba de sesiones que nadie abrio: "Verificacion de
            // directorio actual", "Nombres exactos de herramientas",
            // "orquestador:master" y demas, que son los subagentes que lanza
            // el orquestador.
            //
            // El campo que las distingue es `parentID`: null en una sesion
            // normal, y el id de la madre en una creada por un subagente. No
            // estaba en el modelo (`OpenCodeSession`), asi que el filtro no se
            // podia escribir; ahora si.
            //
            // Se filtran en la app y no se pide al server filtrado, porque el
            // filtro tiene que ser el mismo en las DOS listas (global y por
            // proyecto): si se filtrara en un solo sitio, un chat apareceria en
            // una lista y no en la otra, que es el mismo criterio que ya
            // sustenta `getProjectSessions`.
            val lista = oc.listSessions().data.orEmpty()
                .filter { it.parentID.isNullOrBlank() }
                .map { s ->
                OpencodeSession(
                    id = s.id,
                    title = s.title,
                    // MEDIDO: `OpencodeSession` (el de la app) NO tiene campo `agent` — son
                    // id, ID, title, name, providerTitle, model, createdAt... Escribi `agent`
                    // aqui de memoria y no compila. El agente de la sesion se lee por otra
                    // via, `GET /api/session/{id}`, en `getSessionAgent`.
                    model = s.model,
                    // MEDIDO: `projectID` tampoco esta en `OpencodeSession` (la app). Es el
                    // segundo campo de esta función que escribi de memoria. El vinculo
                    // sesion-proyecto lo lleva `ProjectsStore`, no el modelo de la sesion.
                    // MEDIDO: `time.created` es un numero en milisegundos, y la app espera un
                    // String. Se pasa como ISO-8601 porque es lo que usaba el Hub, y lo que
                    // ordena la lista de chats. Sin esto, `createdAt` sale null y el orden de la
                    // lista deja de ser real.
                    createdAt = s.time?.created?.let { java.time.Instant.ofEpochMilli(it).toString() },
                    updatedAt = s.time?.updated?.let { java.time.Instant.ofEpochMilli(it).toString() },
                    pinned = false,
                    provider = s.model?.providerID
                )
            }
            return envoltura(lista)
        }

    // getMessages: content[] nativo -> parts[] de la app, via el traductor
    // MEDIDO: GET /api/session/{id}/message devuelve `content[]`, no `parts[]`. De ahi el
    // traductor (NativeMapper), que es el trabajo real de la migracion.
        /**
     * MEDIDO 2026-10-02, y es la causa de que el chat de la app estuviese CONGELADO:
     *
     * "Tail" significa "los ultimos N mensajes". Antes esta funcion pedia `order = "asc"`, y
     * MEDIDO contra el endpoint vivo: `asc` devuelve los MAS ANTIGUOS. O sea que la app pedia la
     * cola y recibia la cabeza, siempre los mismos 200 primeros mensajes, con lo que el poll no
     * podia traer NUNCA un mensaje nuevo. La lista se quedaba donde estaba, para siempre.
     *
     * Concreto, en esta sesion: 2520 mensajes. `asc&limit=200` devuelve los indices 0..199, y el
     * mensaje mas reciente esta en el 2519. El chat se quedaba en el 0.08% del historial.
     *
     * Y el `order=desc` se INVIERTE antes de devolverlo: `desc` llega del reves, y `mergeTail`
     * (ChatViewModel:552) anade al final lo que no ha visto, asi que con la lista invertida los
     * mensajes nuevos se apendirian en orden contrario. La app espera cronologico.
     */
    override suspend fun getMessagesTail(sessionId: String, tail: Int): Envelope<List<Message>> {
        val limite = colaDe(tail)
        val r = oc.getMessages(sessionId, limit = limite, order = OpenCodeApi.ORDEN_COLA)
        return envoltura(NativeMapper.toMessages(r.data, sessionId).reversed())
    }

    /**
     * MEDIDO: los cinco llamadores de esta funcion usan el resultado para lo MISMO — reemplazar
     * la lista que se ve (ChatViewModel:685, 1014, 1153, 1431) — y ninguno quiere el principio
     * del historial. Con `asc` los cuatro se quedaban con los primeros 200 de 2520.
     *
     * Por eso esta tambien devuelve la COLA. Y es un cambio de semantica que se dice aqui, no en
     * silencio: `updateTitleFromFirstMessage` (ChatViewModel:264) saca el titulo del primer prompt
     * de la lista, y con la cola ese primer prompt es uno reciente, no el original. Solo afecta
     * cuando el titulo de la sesion esta vacio o es tecnico, porque el titulo REAL de OpenCode se
     * lee antes (ChatViewModel:1015) y manda.
     *
     * LIMITACION REAL y no resuelta: con mas de 200 mensajes no se puede ir hacia atras. Se puede
     * paginar por `cursor`, pero solo SIN `order` (MEDIDO: `400 InvalidCursorError`), o sea que
     * haria falta un recorrido distinto para "cargar anteriores". Queda escrito para que no se lea
     * como resuelto: hoy un chat largo muestra sus ultimos 200 mensajes y nada mas.
     */
    override suspend fun getMessages(sessionId: String): Envelope<List<Message>> {
        val r = oc.getMessages(sessionId, limit = OpenCodeApi.LIMITE_MAX_MENSAJES, order = OpenCodeApi.ORDEN_COLA)
        return envoltura(NativeMapper.toMessages(r.data, sessionId).reversed())
    }

    /**
     * El limite pedido por el llamador, recortado al tope real del servidor.
     *
     * MEDIDO: por encima de 200 la respuesta viene VACIA, no con un error. Asi que recortar aqui
     * no es defensa, es la diferencia entre un chat con 200 mensajes y un chat con CERO. Y el
     * recorte se avisa por log, porque un `coerceIn` callado seria el mismo fallo reproduciendose
     * en otra forma.
     */
    private fun colaDe(tail: Int): Int {
        if (tail > OpenCodeApi.LIMITE_MAX_MENSAJES) {
            Log.w(TAG, "colaDe(): se piden $tail mensajes y el tope del servidor es " +
                "${OpenCodeApi.LIMITE_MAX_MENSAJES}. MEDIDO: por encima del tope la respuesta viene " +
                "VACIA, sin error. Se recorta a ${OpenCodeApi.LIMITE_MAX_MENSAJES}.")
        }
        return tail.coerceIn(1, OpenCodeApi.LIMITE_MAX_MENSAJES)
    }

    // getOpencodeAgents: conversion estructural con Gson; conserva 'hidden'
    // Conversion ESTRUCTURAL con Gson, no campo a campo: los dos data class describen el mismo
    // JSON (name, mode, model, description, hidden). Traducir a mano 6 campos es otra cosa.
    // Se conserva `hidden` a proposito: de 40 agentes, 37 son visibles y 3 no, y la hoja
    // depende de ese filtro.
        // MEDIDO 2026-10-02: aquí usaba `gson.fromJson(gson.toJson(nativo), OpencodeAgent::class.java)`
        // con el comentario de que "los dos data class describen el mismo JSON". Es FALSO, y lo
        // reportó el usuario al abrir la app:
        //
        //     No se pudieron cargar los agentes: Expected a string but was BEGIN_OBJECT
        //     at line 1 column 95 path $.data[0].model
        //
        // MEDIDO contra `/api/agent`: `model` es un OBJETO `{id, providerID}` y la app lo espera
        // como `String`. Gson encuentra el tipo y falla. El mapeo estructural solo es valido cuando
        // las dos formas coinciden, y aquí no: es justo el campo que seTbien podría suponer igual.
        //
        // Y hay un segundo defecto en la misma linea: el Hub devolvia la lista YA FILTRADA
        // (`primary && !hidden`), y `GET /api/agent` devuelve el catalogo entero. Con el mapeo
        // anterior la hoja habria recibido los 40 y `seleccionables()` los habria dejado en 3, pero
        // el filtro se queda aqui, que es donde estaba.
        override suspend fun getOpencodeAgents(): Envelope<List<OpencodeAgent>> {
            val lista = oc.listAgents().data.orEmpty().mapNotNull { a ->
                OpencodeAgent(
                    name = a.name,
                    mode = a.mode ?: "primary",
                    // MEDIDO: aquí viene el OBJETO {id, providerID}; lo que la app quiere es el
                    // identificador. Se toma el `id`.
                    model = a.model?.id,
                    description = a.description,
                    hidden = a.hidden ?: false
                )
            }
            // El Hub filtraba antes de responder: `primary && !hidden` deja exactamente los 3
            // que la hoja muestra (Build, Plan, orchestrator) de los 40 del catálogo.
            return envoltura(lista.filter { it.mode == "primary" && !it.hidden })
        }

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

    // ==========================================================================================
    // SESIONES DE UN PROYECTO, renombrar y borrar.
    //
    // Estas CINCO delegaban en el Hub con un comentario mio que decia "tiene equivalente nativo,
    // pendiente de confirmar antes de tocarlo". Confirmado MEDIDO contra el OpenAPI de OpenCode
    // (113 rutas): `/api/session/{sessionID}` tiene `GET, DELETE, PATCH`, y el PATCH acepta
    // `{"title": string|null}`. Confirmado tambien que NO existe `/api/project/{id}/sessions`:
    // el vinculo sesion-proyecto no lo tiene OpenCode, lo tiene el store de la app.
    //
    // POR QUE IMPORTA mas de lo que parece: estas cinco son las que usa `ProjectDetailViewModel`,
    // que es la pantalla de proyecto con SU PROPIA lista de chats. O sea que "los chats no cargan"
    // no era un fallo de la lista global —esa va bien, y lo verifique en el logcat del movil:
    // `GET /api/session?limit=50 -> 200 OK (39ms)`— sino que esta pantalla pedia a un puerto que
    // ya no escucha. Mi comentario anterior decia que las 40 que delegan eran "subsistemas que ya
    // no existen: skills, jobs, workflows". Eso era cierto para 36 y FALSO para estas cinco.
    // ==========================================================================================

    /**
     * MEDIDO: el PATCH acepta `title`. Sin esto, renombrar un chat no tiene destino: el Hub se
     * llevo `/api/sessions/{id}/rename` y con el Hub fuera solo queda esto.
     *
     * El titulo se guarda ALSO en el store, y no solo en OpenCode, porque `getProjectSessions`
     * lee el store: si solo se guardara en OpenCode, el renombrado no se veria hasta que la
     * pantalla volviera a pedir la lista completa, y el store no tendria de donde sacarlo.
     */
    override suspend fun renameSession(id: String, body: Map<String, String>): Envelope<Map<String, Any>> {
        val titulo = body["title"]?.takeIf { it.isNotBlank() }
            ?: return envolturaFallo("renameSession sin title")
        val r = oc.updateSession(id, UpdateOpenCodeSessionRequest(title = titulo))
        if (!r.isSuccessful) return envolturaFallo("PATCH session ${r.code()}")
        store.setSessionTitle(id, titulo)
        return envoltura(mapOf("id" to id, "title" to titulo))
    }

    /**
     * MEDIDO: `DELETE /api/session/{sessionID}` existe (OpenAPI, ruta 1 de 113).
     *
     * Se consulta `isSuccessful` y no se asume excepcion: un 404 significa que la sesion ya no
     * esta, que para el usuario es el resultado que queria, no un fallo.
     */
    override suspend fun deleteSession(id: String): Envelope<Map<String, Any>> {
        val r = oc.deleteSession(id)
        return if (r.isSuccessful) envoltura(mapOf("id" to id, "deleted" to true))
        else envolturaFallo("DELETE session ${r.code()}")
    }

    override suspend fun pinSession(id: String): Envelope<PinResponse> {
        // MEDIDO: OpenCode no tiene "pin" en el modelo de sesion (`GET /api/session` no trae ese
        // campo), asi que el pin es de la APP y vive en el store, que es donde ya se guardaba.
        store.setSessionPin(id, true)
        return envoltura(PinResponse(id = id, pinned = true))
    }

    override suspend fun unpinSession(id: String): Envelope<PinResponse> {
        store.setSessionPin(id, false)
        return envoltura(PinResponse(id = id, pinned = false))
    }

    override suspend fun linkSession(projectId: String, body: LinkSessionRequest): Envelope<SessionRef> {
        store.linkSessionToProject(body.sessionId, projectId)
        body.title?.takeIf { it.isNotBlank() }?.let { store.setSessionTitle(body.sessionId, it) }
        return envoltura(
            SessionRef(
                sessionId = body.sessionId,
                title = body.title ?: store.getSessionTitle(body.sessionId),
                pinned = store.isSessionPinned(body.sessionId),
                provider = body.provider
            )
        )
    }

    override suspend fun unlinkSession(projectId: String, sessionId: String): Envelope<Map<String, String>> {
        // MEDIDO 2026-10-02: `unlinkSessionFromProject` no existia en el store, y su ausencia la
        // anote como "desvincular sigue sin estar soportado" en un commit anterior. Era verdad
        // cuando lo escribi; con el Hub fuera era la razon de que este boton no hiciera nada.
        val estaba = store.unlinkSessionFromProject(sessionId, projectId)
        // No se borra la sesion: esto quita el VINCULO con el proyecto. Son dos cosas distintas y
        // confundirlas aqui seria borrar el trabajo del usuario por desordenar una lista.
        return envoltura(mapOf("sessionId" to sessionId, "unlinked" to estaba.toString()))
    }

    /**
     * MEDIDO: NO existe `/api/project/{id}/sessions` en OpenCode. El vinculo sesion-proyecto lo
     * lleva `ProjectsStore`, y el titulo/pin tambien. O sea que la lista sale de CRUZAR las
     * sesiones que devuelve OpenCode con los vinculos del store.
     *
     * Y por que no sale solo del store: ahi esta el vinculo, pero no el titulo real de OpenCode ni
     * la fecha. Sin `/api/session` la lista saldria con titulos vacios.
     *
     * LIMITACION, escrita porque es real: se piden las 50 primeras sesiones, que es el limite que
     * usa el resto de la app. Un proyecto con mas de 50 sesiones no las muestra todas, y con
     * paginacion por `cursor` se podria resolver. No lo hago aqui porque es la unica pantalla que
     * paginaria, y anadir un bucle de paginas a una pantalla es un cambio de alcance que no es
     * de este arreglo; queda dicho para que no se lea como si estuviera resuelto.
     */
    override suspend fun getProjectSessions(projectId: String): Envelope<List<SessionRef>> {
        val ids = store.getSessionIdsForProject(projectId).toSet()
        val todas = oc.listSessions().data.orEmpty()
            // MEDIDO el 2026-10-02: mismo filtro que `getOpencodeSessions`, y
            // por el mismo motivo. Las dos listas tienen que usar el MISMO
            // criterio: si aqui no se filtrara, un subagente apareceria en la
            // lista del proyecto pero no en la global.
            .filter { it.parentID.isNullOrBlank() }
        val lista = todas.mapNotNull { s ->
            // `getProjectIdForSession` resuelve el vinculo explicito Y la carpeta que coincide.
            // Se usa el MISMO criterio que en el resto de la app: si aqui se filtrara por otra
            // cosa, un chat apareceria en la lista global y no en la del proyecto.
            val proyecto = store.getProjectIdForSession(s.id, s.location?.directory)
            if (proyecto != projectId && s.id !in ids) return@mapNotNull null
            SessionRef(
                sessionId = s.id,
                // El titulo guardado en el store tiene prioridad: es lo que el usuario escribio.
                // El de OpenCode es el que el agente haya puesto.
                title = store.getSessionTitle(s.id) ?: s.title,
                createdAt = s.time?.created?.let { java.time.Instant.ofEpochMilli(it).toString() },
                lastUsed = s.time?.updated?.let { java.time.Instant.ofEpochMilli(it).toString() },
                pinned = store.isSessionPinned(s.id),
                provider = s.model?.providerID
            )
        }.sortedByDescending { it.lastUsed ?: "" }
        return envoltura(lista)
    }

    // ==========================================================================================
    // SKILLS
    //
    // MEDIDO 2026-10-03, y lo primero es donde estan, porque hay TRES vistas del mismo arbol y no
    // son la misma:
    //
    //   desde el shell de este agente:  /root/.config/opencode/skills            EXISTE
    //   desde el host (donde corre la app): /root/...                          NO EXISTE
    //   desde el host:                    /data/local/ubuntu/root/.config/...  EXISTE
    //
    // La app usa `su`, y `su` da root en el namespace de quien llama, que es el del proceso
    // Android: alli `/root` no existe. Por eso la ruta es la del host, y esta medida.
    // ==========================================================================================

    /** MEDIDO: `skills/<nombre>/SKILL.md`, con frontmatter `name:` y `description:`. */
    private fun rutaDeSkills(): String = "$RUTA_CHROOT_ROOT/.config/opencode/skills"

    /**
     * MEDIDO: un subdirectorio por skill. Se listan solo los que tienen `SKILL.md`: un directorio
     * suelto no es un skill, y sin ese filtro la UI listaria filas vacias.
     *
     * El separador es un salto de linea y los nombres de skill no lo pueden contener (es un
     * nombre de carpeta), asi que no hace falta un delimitador exotico.
     */
    override suspend fun getSkills(projectId: String?): Envelope<SkillListResponse> {
        val base = rutaDeSkills()
        val shell: (String, Long) -> RootShell.Result = { c, t -> RootShell.exec(c, t) }
        val listado = shell("ls -1 '$base' 2>/dev/null", 5000)
        if (listado.code != 0) {
            return envolturaFallo("No se pudo leer $base (exit ${listado.code})")
        }
        val nombres = listado.stdout.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != ".papelera" }
        val skills = mutableListOf<Skill>()
        var leidos = 0
        for (nombre in nombres) {
            val md = shell("head -c 200000 '$base/$nombre/SKILL.md' 2>/dev/null", 5000)
            if (md.code != 0 || md.stdout.isBlank()) continue
            // `head -c` evita el fallo de `cat` cuando el fichero es grande: un skill de 40 KB
            // entra, pero uno de varios MB no cabe en el buffer del shell.
            skills += Skill(
                scope = scopeDe(nombre, base),
                name = nombre,
                content = md.stdout
            )
            leidos++
        }
        Log.i(TAG, "getSkills: $leidos de ${nombres.size} entradas bajo $base")
        return envoltura(
            SkillListResponse(
                skills = skills,
                projectId = projectId,
                counts = mapOf(scopeDe("", base) to skills.size)
            )
        )
    }

    /**
     * MEDIDO: solo hay un ambito, el global, que es `$RUTA_CHROOT_ROOT/.config/opencode/skills`.
     * Un proyecto con sus propios skills usaria el subdirectorio del proyecto; MEDIDO: no existe
     * ninguno en este movil, asi que `scope` devuelve "global" siempre en lugar de inventar un
     * ambito por proyecto que luego no se puede seleccionar.
     */
    private fun scopeDe(nombre: String, base: String): String {
        val deProyecto = base + "/../project"
        return if (nombre.isNotEmpty() && deProyecto.isNotEmpty()) "global" else "global"
    }

    /**
     * NO borra: mueve el directorio a `.papelera/`. MEDIDO: el Hub lo hacia asi con los proyectos
     * y el motivo no ha cambiado — un boton de "borrar" que tira el trabajo del usuario sin
     * vuelta atras no es un boton de borrar, es una perdida de datos con interfaz de accion.
     *
     * El nombre lleva fecha para que dos borrados del mismo skill no se pisen.
     */
    override suspend fun deleteSkill(scope: String, name: String): Envelope<Map<String, String>> {
        require(name.isNotBlank()) { "deleteSkill sin nombre" }
        // MEDIDO: el nombre viene de la URL, y `..` en un nombre de skill es un path traversal
        // que dejaria borrar `/`. Se filtra ANTES de tocar el sistema de ficheros.
        require(!name.contains("..") && !name.contains("/")) {
            "Nombre de skill invalido: $name"
        }
        val shell: (String, Long) -> RootShell.Result = { c, t -> RootShell.exec(c, t) }
        val base = rutaDeSkills()
        val origen = "$base/$name"
        val comprobacion = shell("test -d '$origen' && echo SI", 3000)
        if (comprobacion.stdout.trim() != "SI") {
            return envolturaFallo("No existe el skill '$name'")
        }
        val destino = "$base/.papelera/$name-$(System.currentTimeMillis())"
        val r = shell("mkdir -p '$base/.papelera' && mv '$origen' '$destino'", 8000)
        if (r.code != 0) {
            return envolturaFallo("No se pudo apartar '$name': ${r.stderr.take(140)}")
        }
        Log.i(TAG, "deleteSkill: '$name' -> $destino (NO se borro)")
        return envoltura(mapOf("name" to name, "movido" to destino))
    }


        override suspend fun createSkill(body: SkillCreateRequest): Envelope<Skill> = hub.createSkill(body)

        // MEDIDO 2026-10-03: sigue delegando. Esta funcion reescribe el SKILL.md entero, y el
        // Hub lo hacia fusionando con el contenido que ya venia en el cuerpo. Hacerlo aqui sin
        // medir esa fusion es escribir a ciegas, que es como han salido los tres fallos de hoy.
        override suspend fun updateSkill(scope: String, name: String, body: Map<String, String>): Envelope<Skill> = hub.updateSkill(scope, name, body)

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
                // MEDIDO 2026-10-02: la app manda el adjunto con la clave "url", y las otras tres
                // no existen nunca en sus peticiones. MEDIDO en el historial: los mensajes
                // enviados desde la app llegan con `files: []` — el adjunto se perdia en silencio.
                val datos = p["data"] ?: p["base64"] ?: p["url"] ?: p["uri"]
                    ?: return@mapNotNull null
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
     * MEDIDO 2026-10-02, y esto es un OR, no una prioridad: **las dos ramas se suman.** La
     * primera PUEDE añadir gratis, pero nunca lo quita. Ejecuté el criterio original del Hub
     * en node para no discutir de memoria:
     *
     *     coste 0/0  + id normal  -> true
     *     coste 3/15 + id "-free" -> true      (yo creia false, y el test que escribi lo fijo
     *                                                como false: por eso el test cayo)
     *     coste 3/15 + id normal  -> false
     *     sin coste  + id "-free" -> true
     *
     * Lo que EXCLUYE un modelo de pago es el coste, y el sufijo solo puede añadir. Cambiar eso
     * para que "el coste mande sobre el nombre" haría que un modelo que el proveedor da gratis
     * apareciera como de pago. MEDIDO 2026-09-29 en el catálogo real: 39 de 472 dan `free=true`
     * con este criterio, y no son los que llevan "-free" en el id — de ahí que hagan falta las
     * dos ramas y no una.
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
            // MEDIDO 2026-10-02: el filtro por `providerID` es lo que hacia que los modelos
            // aparecieran "a veces". `loadModels` usa `_selectedProvider`, que viene de la
            // sesion; si ese proveedor no es `opencode`, `space-bunny-free` (que es
            // providerID=opencode, MEDIDO en el catalogo) se queda fuera de la lista, y el chip
            // pasa a decir "No disponible: space-bunny-free" con la lista cargada.
            //
            // Es el mismo patron que el del sintoma que ya se corrigio dos veces: un filtro que
            // descarta en silencio y deja una pantalla vacia sin explicar por que. Por eso el
            // filtro se DECLARA y la lista no se recorta: el selector tiene que poder mostrar un
            // modelo de otro motor, que es justo lo que hace el resto de la app.
            .filter { provider == null || it.providerID == provider || it.id == ID_MODELO_POR_DEFECTO }
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

    // ==========================================================================================
    // WORKSPACE
    //
    // MEDIDO 2026-10-03: la pantalla se describe a si misma (WorkspaceScreen.kt:30) como
    // "explorador fisico de directorios en /sdcard/projects/". O sea que la fuente real es el
    // disco y se puede hacer nativo sin inventar nada.
    //
    // Y un hallazgo que explica un GRUPO entero: `WorkspaceViewModel`, `WorkflowViewModel` y
    // `SkillManagerViewModel` usan `ApiClient.service` DIRECTO, no `Conexion.api` (3 usos cada uno).
    // Se saltan la costura y hablan con el cliente del Hub aunque la costura este en modo nativo.
    // Por eso esas tres pantallas no funcionan con independencia de lo que se implemente aqui.
    // ==========================================================================================

    private const val RAIZ_PROYECTOS = "/sdcard/projects"

    override suspend fun getWorkspaceProjects(): Response<ProjectsResponse> {
        val shell: (String, Long) -> RootShell.Result = { c, t -> RootShell.exec(c, t) }
        // `-d` para quedarse solo con directorios: la pantalla lista carpetas, y un fichero suelto
        // en la raiz no es un proyecto.
        val r = shell("ls -1d $RAIZ_PROYECTOS/*/ 2>/dev/null", 8000)
        if (r.code != 0 && r.stdout.isBlank()) {
            Log.w(TAG, "getWorkspaceProjects: no se pudo listar $RAIZ_PROYECTOS (exit ${r.code})")
            return Response.success(ProjectsResponse(ok = false, data = emptyList()))
        }
        val items = r.stdout.split("\n")
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotEmpty() }
            .map { ruta ->
                val nombre = ruta.substringAfterLast('/')
                ProjectItem(
                    id = nombre,
                    name = nombre,
                    path = ruta,
                    // MEDIDO: esta columna viene de un concepto que ya no existe. El Hub se retiro
                    // el 2026-10-01. Decir `true` porque hay una carpeta seria mentir, y poner un
                    // sustituto obligaria al usuario a aprender algo que no significa nada. Se deja
                    // en false y queda dicho aqui.
                    hasHub = false,
                    lastCommit = null
                )
            }
        Log.i(TAG, "getWorkspaceProjects: ${items.size} carpetas bajo $RAIZ_PROYECTOS")
        return Response.success(ProjectsResponse(ok = true, data = items))
    }

    /**
     * MEDIDO: no hay equivalente nativo, y no lo hay por una razon concreta — este endpoint era
     * del Hub, que inicializaba su propio workspace. OpenCode no tiene el concepto.
     *
     * Se responde `ok=false` CON MOTIVO en vez de dejar que la peticion se vaya a un puerto muerto.
     * La diferencia es visible: un boton que dice "esto ya no existe en la app" es informacion; uno
     * que no hace nada es una pista falsa, y el usuario deducira que la app esta rota.
     */
    override suspend fun initProject(projectId: String): Response<BaseResponse> =
        Response.success(
            BaseResponse(
                ok = false,
                // MEDIDO: `error` es `ErrorBody?`, no un texto. Escribirlo como String no
                // compila, y el control positivo de simbolos lo ve antes que la CI.
                error = ErrorBody(
                    code = "hub-retirado",
                    message = "Inicializar el workspace era del Hub, que se retiro. OpenCode no" +
                        " tiene ese concepto: el proyecto ya esta en el registro de la app."
                )
            )
        )

    /** MEDIDO: sin equivalente nativo, por la misma razon que [initProject]. */
    override suspend fun indexProject(projectId: String): Response<TaskResponse> =
        Response.success(
            TaskResponse(
                ok = false,
                // MEDIDO: `TaskResponse` es `(ok, data: TaskData?)`, y `TaskData` es
                // `(taskId, message)`. No hay campo `error` ni `taskId` sueltos: lo he mirado.
                data = TaskData(
                    taskId = null,
                    message = "Indexar el workspace era del Hub. OpenCode indexa sus propias" +
                        " sesiones; el grafo de un proyecto se construye desde el propio proyecto."
                )
            )
        )



        override suspend fun getProjectState(projectId: String): Response<ProjectStateResponse> = hub.getProjectState(projectId)



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

    // ==========================================================================================
    // FORMULARIOS Y PERMISOS
    //
    // MEDIDO 2026-10-03: estas dos delegaban en el Hub con un endpoint equivalente YA DECLARADO
    // en `OpenCodeApi` desde el principio. El nativo estaba escrito y desconectado, que es la
    // QUINTA vez hoy que algo ya existia: un import de otro paquete, una constante que no existe,
    // dos sobrecargas duplicadas, `updateSkill` sin implementar, y esto.
    //
    // MEDIDO contra el OpenAPI (113 rutas):
    //   POST /api/session/{id}/form/{formID}/reply          body {answer},      required answer
    //   POST /api/session/{id}/permission/{id}/reply        body {decision, message}, required decision
    //
    // Lo que cambia al cablear, y por que:
    //  - La app manda `answer: Map<String,String>` y el nativo `Map<String,Any?>`. Se convierte en
    //    vez de castear: `Form.Answer` admite mas que texto, y taparlo aqui seria cerrarle la
    //    puerta al cliente.
    //  - El nativo devuelve `Response<Unit>` y la app `Envelope`. Se comprueba `isSuccessful` y no
    //    se asume excepcion, igual que en `deleteSession`: un 404 significa que la peticion ya no
    //    esta, que para el usuario es respuesta y no fallo.
    // ==========================================================================================

    override suspend fun replyForm(
        sessionId: String,
        formId: String,
        body: FormReplyBody
    ): Envelope<Map<String, Any>> {
        val r = oc.replyForm(
            sessionId,
            formId,
            OpenCodeFormReplyRequest(answer = body.answer.mapValues { it.value })
        )
        return if (r.isSuccessful) {
            envoltura(mapOf("formId" to formId, "enviado" to true))
        } else {
            envolturaFallo("El formulario $formId no se pudo contestar (${r.code()})")
        }
    }

    override suspend fun replyPermission(
        sessionId: String,
        requestId: String,
        body: PermissionReplyBody
    ): Envelope<Map<String, Any>> {
        // MEDIDO: `decision` solo admite "once" | "always" | "reject". Se valida aqui y no en el
        // nativo para que el error llegue al usuario como texto y no como un 400 sin explicar.
        val validas = setOf("once", "always", "reject")
        if (body.decision !in validas) {
            return envolturaFallo(
                "decision '${body.decision}' no vale. MEDIDO: solo ${validas.joinToString(", ")}."
            )
        }
        val r = oc.replyPermission(
            sessionId,
            requestId,
            OpenCodePermissionReplyRequest(decision = body.decision, message = body.message)
        )
        return if (r.isSuccessful) {
            envoltura(mapOf("requestId" to requestId, "decision" to body.decision))
        } else {
            envolturaFallo("El permiso $requestId no se pudo contestar (${r.code()})")
        }
    }



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
                // MEDIDO 2026-10-02: aqui puse `lastSeen = null` para no inventar una marca de
                // tiempo que el endpoint NO trae. Error: `TurnState.isBusy` es
                //     val last = s.lastSeen ?: s.since ?: return false
                // o sea que sin marca `isBusy` es SIEMPRE false, y `_turnBusy` nunca se ponia a
                // true. Eso hacia que el veto `if (_turnBusy.value) return false` de
                // `turnIsReallyFinished` NO se aplicara nunca, y por tanto:
                //   - "Trabajando en ello" se apagaba entre mensaje y mensaje del agente
                //   - el divisor "respuesta final" se pintaba a mitad de turno
                //
                // Poner la hora de AHORA no es inventar el pasado: es decir "esta sesion estaba
                // en la lista hace un instante", que es lo unico que el endpoint afirma. Y el
                // `MAX_SILENCE_MS` de `isBusy` sigue haciendo su trabajo: si el poll falla, el
                // registro envejece y el busy se apaga solo. Es la marca de FANTASMA, no la real.
                lastSeen = System.currentTimeMillis()
            )
        }
        return envoltura(lista)
    }


    companion object {
        /** MEDIDO 2026-10-02: este fichero no logueaba NADA, y por eso un recorte de limite que
         *  devolvia una lista VACIA pasaba sin dejar rastro. Un fallo mudo en la capa de datos es el
         *  mas caro de diagnosticar en un movil: obliga a volver a las cinco minutos de
         *  certificacion, que es exactamente lo que paso con el congelamiento del chat.
         *
         *  Va en el companion y no en el cuerpo porque Kotlin solo admite `const val` en el
         *  nivel superior, en objetos con nombre y en companions: en el cuerpo de una clase da
         *  error de compilacion. */
        private const val TAG = "AegisRutaNativa"

        /**
         * MEDIDO 2026-10-03: el `/root` del chroot, expresado como lo ve el HOST.
         *
         * Hay TRES vistas del mismo arbol y no son la misma: desde el shell de este agente
         * `/root/.config/opencode/skills` existe; desde el namespace del host -donde corre la
         * app, porque `su` da root ahi- `/root` **no existe** y el camino bueno es este.
         *
         * Es la tercera vez que me lo como en un dia. Por eso la ruta esta en UN sitio y el
         * porque esta escrito al lado, en vez de repetirse en cada llamada.
         */
        const val RUTA_CHROOT_ROOT = "/data/local/ubuntu/root"
    }
}
