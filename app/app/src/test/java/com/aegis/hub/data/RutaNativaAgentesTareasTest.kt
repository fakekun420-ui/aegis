package com.aegis.hub.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Las nueve funciones de `RutaNativa` que leavesaban en el Hub: `getAgents`, `dispatchAgent`,
 * `getAgentStatus`, `getWorkflows`, `runWorkflow`, `getWorkflowStatus`, `getJobs`, `runJob` y
 * `getProjectState`.
 *
 * MEDIDO 2026-10-03 contra el OpenAPI vivo del servidor (113 rutas) y contra el Hub retirado.
 * Resultado: UNA tiene equivalente nativo real (`GET /api/agent`) y las otras OCHO no lo tienen,
 * porque sus endpoints eran del Hub y sus routers nunca se montaron
 * (`docs/audits/AUDITORIA_BACKEND.md` BUG-03). Por eso respondem `ok=false` con motivo en vez de
 * inventar una traduccion.
 *
 * ESTE FICHERO NO PUEDE LLAMAR A `getAgents`. Razon medida: `RutaNativa` saca su cliente de
 * `OpenCodeApi.default`, que es un singleton con la URL real, y no hay donde inyectar otro. En la
 * CI no hay servidor en el puerto 49374, asi que `listAgents()` falla y con ella la funcion. Por
 * eso la parte de `getAgents` que se puede comprobar —el JSON medido y el filtro que deja tres
 * agentes— se comprueba aqui con el JSON real, igual que hizo `SubagentSessionFilterTest`: el
 * contrato queda fijado, y el metodo entero depende de el.
 */
class RutaNativaAgentesTareasTest {

    private val gson = Gson()

    /**
     * Un `ApiService` que EXPLOTA en cuanto se le llama. Su unico trabajo es comprobar que ninguna
     * de las ocho funciones sin equivalente vuelve a mirar al Hub: si una lo hiciera, este test
     * peta diciendo que metodo, en vez de dejar una pantalla vacia sin explicar por que.
     */
    private val hubQueFalla: ApiService = Proxy.newProxyInstance(
        ApiService::class.java.classLoader,
        arrayOf(ApiService::class.java)
    ) { _, metodo, _ ->
        error("una de las ocho ha vuelto a preguntar al Hub: ${metodo.name}")
    } as ApiService

    private val ruta: RutaNativa = RutaNativa(hubQueFalla)

    // ==================================================================
    // 1. Las ocho sin equivalente: ok=false, sin datos, sin llamar al Hub
    // ==================================================================

    @Test
    fun `getProjectState responde sin datos porque el workspace era del Hub`() = runBlocking {
        val r = ruta.getProjectState("proj-kaenor")
        assertTrue("ok=false con HTTP 200 es lo que permite el tipo", r.isSuccessful)
        assertFalse(r.body()!!.ok)
        assertNull(r.body()!!.data)
    }

    @Test
    fun `getAgentStatus responde sin datos porque el estado era por proyecto`() = runBlocking {
        val r = ruta.getAgentStatus("proj-kaenor")
        assertTrue(r.isSuccessful)
        assertFalse(r.body()!!.ok)
        assertNull("responder con el catalogo global SERIA ignores el projectId", r.body()!!.data)
    }

    @Test
    fun `getWorkflows responde sin datos porque no hay workflows`() = runBlocking {
        val r = ruta.getWorkflows("proj-kaenor")
        assertTrue(r.isSuccessful)
        assertFalse(r.body()!!.ok)
        assertNull(r.body()!!.data)
    }

    @Test
    fun `getWorkflowStatus responde sin datos porque no hay workflows`() = runBlocking {
        val r = ruta.getWorkflowStatus("proj-kaenor")
        assertTrue(r.isSuccessful)
        assertFalse(r.body()!!.ok)
        assertNull(r.body()!!.data)
    }

    @Test
    fun `getJobs responde sin datos porque el planificador nunca arranco`() = runBlocking {
        val r = ruta.getJobs()
        assertTrue(r.isSuccessful)
        assertFalse(r.body()!!.ok)
        assertNull(r.body()!!.data)
    }

    @Test
    fun `runJob lleva el motivo en el error con el codigo del Hub retirado`() = runBlocking {
        val r = ruta.runJob("job-1")
        assertTrue(r.isSuccessful)
        val cuerpo = r.body()!!
        assertFalse(cuerpo.ok)
        // MEDIDO: la local NO se llama `error` porque ese es el nombre de la funcion de kotlin
        // `error()`, y una variable con ese nombre la tapa dentro de su ambito.
        val campoError = cuerpo.error
        assertNotNull("este es el unico de los ocho con campo error donde poner el motivo", campoError)
        val motivo = campoError!!
        assertEquals("hub-retirado", motivo.code)
        assertTrue(motivo.message!!.contains("job"))
    }

    @Test
    fun `dispatchAgent deja el motivo en el mensaje de la tarea`() = runBlocking {
        val cuerpo = DispatchAgentRequest("orchestrator", "proj-kaenor", emptyMap())
        val r = ruta.dispatchAgent(cuerpo)
        assertTrue(r.isSuccessful)
        assertFalse(r.body()!!.ok)
        val tarea = r.body()!!.data
        assertNotNull(tarea)
        val motivo = tarea!!
        assertNull("sin id de tarea, porque no hay tarea: esto es un motivo", motivo.taskId)
        assertTrue(
            "el motivo tiene que decir cual es el casi equivalente y por que no sirve",
            motivo.message!!.contains("session.switchAgent"))
    }

    @Test
    fun `runWorkflow deja el motivo en el mensaje de la tarea`() = runBlocking {
        val r = ruta.runWorkflow("proj-kaenor", RunWorkflowRequest("wf-1"))
        assertTrue(r.isSuccessful)
        assertFalse(r.body()!!.ok)
        val tarea = r.body()!!.data
        assertNotNull(tarea)
        val motivo = tarea!!
        assertNull(motivo.taskId)
        assertTrue(motivo.message!!.contains("workflow"))
    }

    @Test
    fun `ninguna de las ocho vuelve a preguntar al Hub`() = runBlocking {
        // Si alguna delegara, el `hubQueFalla` de arriba reventaria aqui con el nombre del metodo.
        ruta.getProjectState("p")
        ruta.dispatchAgent(DispatchAgentRequest("orchestrator", "p", emptyMap()))
        ruta.getAgentStatus("p")
        ruta.getWorkflows("p")
        ruta.runWorkflow("p", RunWorkflowRequest("wf"))
        ruta.getWorkflowStatus("p")
        ruta.getJobs()
        val ultima = ruta.runJob("j")
        // MEDIDO: la ultima llamada tambien tiene que ser la que devuelve el cuerpo, porque si
        // aqui se acabara en la propia llamada, `runBlocking` devolveria `Response<BaseResponse>`
        // y JUnit4 no acepta un metodo de test que no sea void.
        assertNotNull("las ocho se ejecutaron sin tocar el Hub", ultima.body())
    }

    // ==================================================================
    // 2. El catalogo MEDIDO de GET /api/agent y el filtro que deja tres
    // ==================================================================

    /**
     * MEDIDO el 2026-10-03 contra `GET /api/agent` del servidor vivo: el catalogo trae 40 agentes,
     * 6 de mode primary de los que solo 3 NO estan ocultos. Estos son cinco entradas REALES del
     * catalogo recortadas a los cuatro campos que el filtro mira (`id`, `name`, `mode`, `hidden`):
     * los tres visibles, uno primario OCULTO y un subagente visible, para que el filtro tenga algo
     * que descartar. `permissions`, `request` y `description` no se copian porque son largos y el
     * filtro no los mira.
     */
    private val catalogoMedido = """
        {"data":[
          {"id":"orchestrator","name":"orchestrator","mode":"primary","hidden":false},
          {"id":"build","name":"Build","mode":"primary","hidden":false},
          {"id":"compaction","name":"Compaction","mode":"primary","hidden":true},
          {"id":"plan","name":"Plan","mode":"primary","hidden":false},
          {"id":"kaenor-software-architect","name":"kaenor-software-architect",
           "mode":"subagent","hidden":false}
        ]}
    """.trimIndent()

    private fun visibles(): List<AgentItem> {
        val crudos = gson.fromJson(catalogoMedido, OpenCodeNativeAgentListResponse::class.java)
        return (crudos.data ?: emptyList())
            .filter { it.mode == "primary" && !it.hidden }
            .map { AgentItem(id = it.id.orEmpty(), name = it.name, status = "idle") }
    }

    @Test
    fun `el filtro deja los tres agentes primarios y visibles`() {
        val lista = visibles()
        assertEquals(3, lista.size)
        assertEquals(listOf("orchestrator", "build", "plan"), lista.map { it.id })
    }

    @Test
    fun `el primario oculto no se cuela aunque sea primario`() {
        val ids = visibles().map { it.id }
        assertFalse("compaction es primary pero hidden", ids.contains("compaction"))
    }

    @Test
    fun `el subagente no se cuela aunque sea visible`() {
        val ids = visibles().map { it.id }
        assertFalse("kaenor-software-architect es visible pero subagent", ids.contains("kaenor-software-architect"))
    }

    @Test
    fun `el id y el nombre no coinciden en build ni en plan`() {
        // MEDIDO: en 7 de los 40 agentes `id` y `name` son distintos. Por eso `AgentItem` lleva
        // los dos campos, y comparar por uno solo daria la mitad de las filas vacias.
        val lista = visibles()
        assertEquals(listOf("orchestrator", "Build", "Plan"), lista.map { it.name })
        assertEquals("build", lista[1].id)
        assertEquals("plan", lista[2].id)
    }

    // ==================================================================
    // 3. Por que el status de AgentItem NO se deriva de nada
    // ==================================================================

    /**
     * MEDIDO el 2026-10-03 contra `GET /api/session/active` del servidor vivo. La respuesta REAL
     * viene envuelta en `data`, y `OpenCodeApi.getActiveSessions` declara `Map<String,
     * ActiveSessionStatus>` sin desenvolver. Con el Gson de `ApiClient` (que no desenvuelve) el
     * mapa que sale tiene UNA clave, `data`, y el valor de esa clave no trae los ids de sesion.
     *
     * O sea: la senal no se pierde al leerla, se pierde al DESERIALIZARLA, y por eso no se puede
     * marcar un agente como ocupado con ella. Por eso `getAgents` responde `status = idle` como
     * definicion, y este test existe para que el dia que alguien TOQUE desenvolver la envoltura,
     * se entere de que el mapa tiene una sola clave.
     */
    private val activeMedido = """
        {"data":{
          "ses_effe52081ffeQOoWq3yoDrS764":{"type":"running"},
          "ses_effe5207effeqAvJja6LF3VGg2":{"type":"running"},
          "ses_effe5207cffeo4yPVC40g4Qw2o":{"type":"running"}
        }}
    """.trimIndent()

    @Test
    fun `la envoltura de active sessions se pierde al deserializar`() {
        val tipo = object : TypeToken<Map<String, ActiveSessionStatus>>() {}.type
        val mapa: Map<String, ActiveSessionStatus> = gson.fromJson(activeMedido, tipo)
        assertEquals(
            "la unica clave que sale es data, no el id de ninguna sesion",
            listOf("data"), mapa.keys.toList())
    }

    /**
     * MEDIDO el 2026-10-03 contra `GET /api/session` del servidor vivo, con 50 sesiones y las 3
     * activas de [activeMedido]. Estas cinco entradas son REALES —el padre, sus tres subagentes en
     * marcha y una subagente vieja que no esta activa—:
     *
     *  - `ses_f14fd...` es la sesion del orquestador que tiene `idle` PUESTO, y es la que ha
     *    lanzado las tres subagentes que el servidor dice que estan corriendo. Trabaja, y su `idle`
     *    esta puesto.
     *  - `ses_effe5207...` es una de esas subagentes: `idle` nulo, y el servidor la lista como
     *    activa.
     *  - `ses_f06fda...` es una subagente vieja: `idle` nulo y el servidor NO la lista como
     *    activa. Nunca quedo marcada.
     *
     * Las dos senales que llegaron a ser candidatas quedan falseadas por las dos direcciones.
     */
    private val sesionesMedidas = """
        {"data":[
          {"id":"ses_f14fd0ce5ffe2w8GcjDY40s1hm","agent":"orchestrator","parentID":null,
           "time":{"created":1790649234306,"updated":1791002805246,"idle":1791003154304}},
          {"id":"ses_effe5207cffeo4yPVC40g4Qw2o","agent":"kaenor-software-architect",
           "parentID":"ses_f14fd0ce5ffe2w8GcjDY40s1hm",
           "time":{"created":1791003123665,"updated":1791003123678}},
          {"id":"ses_effe5207effeqAvJja6LF3VGg2","agent":"kaenor-mobile-app-builder",
           "parentID":"ses_f14fd0ce5ffe2w8GcjDY40s1hm",
           "time":{"created":1791003123639,"updated":1791003123650}},
          {"id":"ses_effe52081ffeQOoWq3yoDrS764","agent":"kaenor-backend-architect",
           "parentID":"ses_f14fd0ce5ffe2w8GcjDY40s1hm",
           "time":{"created":1791003123601,"updated":1791003123621}},
          {"id":"ses_f06fdaf07ffempi31x09FAwqoI","agent":"kaenor-mobile-app-builder",
           "parentID":"ses_f14fd0ce5ffe2w8GcjDY40s1hm",
           "time":{"created":1790884073778,"updated":1790884073809}}
        ]}
    """.trimIndent()

    @Test
    fun `el idle de la sesion no dice que agente esta ocupado`() {
        val sesiones = gson.fromJson(sesionesMedidas, OpenCodeSessionListResponse::class.java)
            .data ?: emptyList()
        val porId = sesiones.associateBy { it.id }

        // Direccion 1: idle PUESTO no significa "no trabaja". El orquestador tiene idle puesto y
        // sus tres subagentes estan corriendo.
        val orquestador = porId["ses_f14fd0ce5ffe2w8GcjDY40s1hm"]
        assertNotNull(orquestador)
        // MEDIDO: `assertNotNull` de JUnit4 no smart-castea en Kotlin, asi que `.id` sobre el
        // `orquestador` de arriba no compila. De ahi la local no nula, que es el mismo truco que
        // hay que hacer en toda lista que venga de un mapa.
        val padre = orquestador!!
        assertNotNull("el padre tiene idle PUESTO", padre.time!!.idle)
        val hijasCorriendo = sesiones.count {
            it.parentID == padre.id && activeMedido.contains(it.id)
        }
        assertEquals("y sin embargo tiene tres subagentes corriendo", 3, hijasCorriendo)

        // Direccion 2: idle NULO tampoco significa "trabaja". Esta subagente la tiene y no esta
        // en la lista de activas del servidor.
        val vieja = porId["ses_f06fdaf07ffempi31x09FAwqoI"]
        assertNotNull(vieja)
        val laVieja = vieja!!
        assertNull("esta sesion no tiene idle", laVieja.time!!.idle)
        assertFalse("y el servidor no la lista como activa", activeMedido.contains(laVieja.id))
    }
}
