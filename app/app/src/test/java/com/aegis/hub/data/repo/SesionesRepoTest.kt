package com.aegis.hub.data.repo

import com.aegis.hub.data.CreateOpenCodeSessionRequest
import com.aegis.hub.data.OpenCodeApi
import com.aegis.hub.data.OpenCodeModelRef
import com.aegis.hub.data.OpenCodeNativeAgent
import com.aegis.hub.data.OpenCodeNativeAgentListResponse
import com.aegis.hub.data.OpenCodeNativeModelListResponse
import com.aegis.hub.data.OpenCodePromptRequest
import com.aegis.hub.data.OpenCodeFormReplyRequest
import com.aegis.hub.data.OpenCodeSession
import com.aegis.hub.data.OpenCodeSessionResponse
import com.aegis.hub.data.ProjectsStore
import com.aegis.hub.data.SetSessionAgentRequest
import com.aegis.hub.data.SetSessionModelRequest
import com.aegis.hub.data.UpdateOpenCodeSessionRequest
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests JVM para [SesionesRepo] (F2, T-F2.1).
 *
 * Fake de `OpenCodeApi` contando llamadas + `ProjectsStore` real sobre ficheros
 * temporales (patron de `ProjectsStoreVinculoTest`).
 *
 * NOTA sobre "vinculo falla -> Ok con aviso" (tabla del plan): con el store real no
 * es disparable — `ProjectsStore.saveInternal` traga sus excepciones con `Log.e`, asi
 * que `linkSessionToProject` nunca lanza. El `catch` de `vincular` queda como red
 * defensiva y aqui se fija el contrato real: el vinculo se escribe UNA vez
 * (proyecto + titulo juntos).
 */
class SesionesRepoTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private var ahora = 1_700_000_000_000L

    private class FakeOc : OpenCodeApi {
        var crearLlamadas = 0
        var crearTira: Exception? = null
        var ultimaCarpeta: String? = null
        var agenteFijado: String? = null
        var agenteTira: Exception? = null
        var modeloFijado: OpenCodeModelRef? = null
        var modeloHttpFalla = false
        var agentes = listOf(
            OpenCodeNativeAgent(id = "build", name = "Build", model = OpenCodeModelRef(id = "m-b", providerID = "opencode"))
        )
        var actualizarFalla = false
        var actualizarLlamadas = 0
        var borrarLlamadas = 0

        private fun ok(): retrofit2.Response<Unit> = retrofit2.Response.success(Unit)
        private fun fallo(): retrofit2.Response<Unit> =
            retrofit2.Response.error(500, "".toResponseBody("text/plain".toMediaType()))

        override suspend fun getInfo() = throw UnsupportedOperationException()
        override suspend fun listSessions(cursor: String?, limit: Int?) = throw UnsupportedOperationException()
        override suspend fun getSession(id: String) = throw UnsupportedOperationException()
        override suspend fun createSession(body: CreateOpenCodeSessionRequest): OpenCodeSessionResponse {
            crearLlamadas++
            crearTira?.let { throw it }
            ultimaCarpeta = body.location?.directory
            return OpenCodeSessionResponse(OpenCodeSession(id = "ses_nueva_$crearLlamadas", title = body.title))
        }
        override suspend fun updateSession(id: String, body: UpdateOpenCodeSessionRequest): retrofit2.Response<Unit> {
            actualizarLlamadas++
            return if (actualizarFalla) fallo() else ok()
        }
        override suspend fun deleteSession(id: String): retrofit2.Response<Unit> {
            borrarLlamadas++
            return ok()
        }
        override suspend fun getActiveSessions() = throw UnsupportedOperationException()
        override suspend fun getMessages(sessionId: String, limit: Int?, order: String?, cursor: String?) =
            throw UnsupportedOperationException()
        override suspend fun getMessage(sessionId: String, messageID: String) = throw UnsupportedOperationException()
        override suspend fun sendPrompt(sessionId: String, body: OpenCodePromptRequest) =
            throw UnsupportedOperationException()
        override suspend fun interruptSession(sessionId: String) = ok()
        override suspend fun setSessionModel(sessionId: String, body: SetSessionModelRequest): retrofit2.Response<Unit> {
            modeloFijado = body.model
            return if (modeloHttpFalla) fallo() else ok()
        }
        override suspend fun setSessionAgent(sessionId: String, body: SetSessionAgentRequest): retrofit2.Response<Unit> {
            agenteTira?.let { throw it }
            agenteFijado = body.agent
            return ok()
        }
        override suspend fun listAgents() = OpenCodeNativeAgentListResponse(agentes)
        override suspend fun listModels() = OpenCodeNativeModelListResponse(emptyList())
        override suspend fun getSessionForms(sessionId: String) = throw UnsupportedOperationException()
        override suspend fun replyForm(sessionId: String, formID: String, body: OpenCodeFormReplyRequest) = ok()
        override suspend fun getSessionPermissions(sessionId: String) = throw UnsupportedOperationException()
        override suspend fun replyPermission(sessionId: String, requestID: String, body: com.aegis.hub.data.OpenCodePermissionReplyRequest) = ok()
        override suspend fun openEventStream(): okhttp3.ResponseBody = "".toResponseBody()
    }

    private fun store(): ProjectsStore =
        ProjectsStore(
            storeFile = tempFolder.newFile("store.json"),
            legacyFile = tempFolder.newFile("store.legacy"),
            clock = { ahora }
        )

    private class FakeCache : com.aegis.hub.data.repo.ConfigCache {
        val modelos = mutableMapOf<String, String>()
        val agentes = mutableMapOf<String, String>()
        override fun leerModelo(sid: String) = modelos[sid]
        override fun guardarModelo(sid: String, modelo: String) { modelos[sid] = modelo }
        override fun leerAgente(sid: String) = agentes[sid]
        override fun guardarAgente(sid: String, agente: String) { agentes[sid] = agente }
        override fun ultimoModelo(): String? = null
        override fun guardarUltimoModelo(modelo: String) = Unit
        override fun ultimoAgente(): String? = null
        override fun guardarUltimoAgente(agente: String) = Unit
    }

    private fun repo(oc: FakeOc, st: ProjectsStore) =
        SesionesRepo(oc, st, com.aegis.hub.data.repo.SesionConfigRepo(oc, FakeCache()) { ahora }) { ahora }

    @Test
    fun `crear global deja una sesion con agente y modelo fijados`() = runBlocking {
        val oc = FakeOc()
        val r = repo(oc, store()).crear(NuevaSesion("Hola"))

        assertTrue(r is Resultado.Ok)
        val creada = (r as Resultado.Ok).valor
        assertEquals("ses_nueva_1", creada.id)
        assertEquals(1, oc.crearLlamadas)
        assertEquals("build", oc.agenteFijado)
        assertEquals("m-b", oc.modeloFijado?.id)
        assertEquals("/sdcard/projects", oc.ultimaCarpeta)
        assertTrue(r.avisos.isEmpty())
    }

    @Test
    fun `crear en proyecto usa su carpeta aunque el nombre sea distinto`() = runBlocking {
        val oc = FakeOc()
        val st = store()
        st.saveProject(ProjectsStore.ProjectEntry(id = "p1", name = "Nombre Distinto", folder = "/sdcard/projects/carpeta-real"))
        val r = repo(oc, st).crear(NuevaSesion("H", proyectoId = "p1"), claveIdempotencia = "H+p1")

        assertTrue(r is Resultado.Ok)
        assertEquals("/sdcard/projects/carpeta-real", oc.ultimaCarpeta)
        assertEquals("p1", st.getProjectIdForSession("ses_nueva_1", null))
    }

    @Test
    fun `si fijar agente lanza hay aviso y no segundo create`() = runBlocking {
        val oc = FakeOc().apply { agenteTira = RuntimeException("caido") }
        val r = repo(oc, store()).crear(NuevaSesion("H"))

        assertTrue(r is Resultado.Ok)
        assertEquals(1, oc.crearLlamadas)
        assertEquals(1, (r as Resultado.Ok).avisos.size)
        assertTrue(r.avisos[0].contains("agente"))
    }

    @Test
    fun `si crear lanza hay Fallo con motivo y nada vinculado`() = runBlocking {
        val oc = FakeOc().apply { crearTira = RuntimeException("red caida") }
        val st = store()
        val r = repo(oc, st).crear(NuevaSesion("H", proyectoId = "p1"))

        assertTrue(r is Resultado.Fallo)
        assertTrue((r as Resultado.Fallo).motivo.contains("red caida"))
        assertNull(st.getProjectIdForSession("ses_nueva_1", null))
    }

    @Test
    fun `doble pulsacion con la misma clave crea una sola sesion`() = runBlocking {
        val oc = FakeOc()
        val repo = repo(oc, store())
        repo.crear(NuevaSesion("H"), claveIdempotencia = "k")
        ahora += 1_000L
        val segunda = repo.crear(NuevaSesion("H"), claveIdempotencia = "k")

        assertEquals(1, oc.crearLlamadas)
        assertTrue(segunda is Resultado.Ok)
        assertEquals("ses_nueva_1", (segunda as Resultado.Ok).valor.id)
    }

    @Test
    fun `renombrar escribe el titulo local solo si el servidor confirma`() = runBlocking {
        val oc = FakeOc().apply { actualizarFalla = true }
        val st = store()
        val r = repo(oc, st).renombrar("ses_x", "Nuevo")

        assertTrue(r is Resultado.Fallo)
        assertNull("el titulo local no cambia si el servidor rechaza", st.getSessionTitle("ses_x"))
    }

    @Test
    fun `renombrar confirma y escribe`() = runBlocking {
        val oc = FakeOc()
        val st = store()
        val r = repo(oc, st).renombrar("ses_x", "Nuevo")

        assertTrue(r is Resultado.Ok)
        assertEquals("Nuevo", st.getSessionTitle("ses_x"))
    }

    @Test
    fun `vincular escribe proyecto y titulo de una vez`() = runBlocking {
        val st = store()
        val r = repo(FakeOc(), st).vincular("ses_x", "p1", "Titulo")

        assertTrue(r is Resultado.Ok)
        assertEquals("p1", st.getProjectIdForSession("ses_x", null))
        assertEquals("Titulo", st.getSessionTitle("ses_x"))
    }
}
