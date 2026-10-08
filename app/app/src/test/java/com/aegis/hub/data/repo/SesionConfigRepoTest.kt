package com.aegis.hub.data.repo

import com.aegis.hub.data.OpenCodeApi
import com.aegis.hub.data.OpenCodeFormReplyRequest
import com.aegis.hub.data.OpenCodeModelRef
import com.aegis.hub.data.OpenCodeNativeAgent
import com.aegis.hub.data.OpenCodeNativeAgentListResponse
import com.aegis.hub.data.OpenCodeNativeModel
import com.aegis.hub.data.OpenCodeNativeModelListResponse
import com.aegis.hub.data.OpenCodePermissionReplyRequest
import com.aegis.hub.data.OpenCodePromptRequest
import com.aegis.hub.data.OpenCodeSession
import com.aegis.hub.data.OpenCodeSessionResponse
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests JVM para [SesionConfigRepo] (F3, T-F3.1).
 *
 * Fake de `OpenCodeApi` + cache en memoria (sin `Context`: las prefs reales no
 * funcionan en JVM). El reloj es controlable para la regla de 60 s.
 */
class SesionConfigRepoTest {

    private class FakeOc : OpenCodeApi {
        var getSessionLlamadas = 0
        var getSessionTira: Exception? = null
        var sesion = OpenCodeSession(
            id = "ses_x",
            title = "Chat X",
            agent = "build",
            model = OpenCodeModelRef(id = "opencode/m-srv", providerID = "opencode")
        )
        var setModeloLlamadas = 0
        var setModeloTira: Exception? = null
        var ultimoModeloFijado: OpenCodeModelRef? = null
        var setAgenteLlamadas = 0
        var agenteFijado: String? = null

        private fun ok(): retrofit2.Response<Unit> = retrofit2.Response.success(Unit)

        override suspend fun getInfo() = throw UnsupportedOperationException()
        override suspend fun listSessions(cursor: String?, limit: Int?) = throw UnsupportedOperationException()
        override suspend fun getSession(id: String): OpenCodeSessionResponse {
            getSessionLlamadas++
            getSessionTira?.let { throw it }
            return OpenCodeSessionResponse(sesion.copy(id = id))
        }
        override suspend fun createSession(body: com.aegis.hub.data.CreateOpenCodeSessionRequest) =
            throw UnsupportedOperationException()
        override suspend fun updateSession(id: String, body: com.aegis.hub.data.UpdateOpenCodeSessionRequest) = ok()
        override suspend fun deleteSession(id: String) = ok()
        override suspend fun getActiveSessions() = throw UnsupportedOperationException()
        override suspend fun getMessages(sessionId: String, limit: Int?, order: String?, cursor: String?) =
            throw UnsupportedOperationException()
        override suspend fun getMessage(sessionId: String, messageID: String) = throw UnsupportedOperationException()
        override suspend fun sendPrompt(sessionId: String, body: OpenCodePromptRequest) =
            throw UnsupportedOperationException()
        override suspend fun interruptSession(sessionId: String) = ok()
        override suspend fun setSessionModel(sessionId: String, body: com.aegis.hub.data.SetSessionModelRequest): retrofit2.Response<Unit> {
            setModeloLlamadas++
            setModeloTira?.let { throw it }
            ultimoModeloFijado = body.model
            return ok()
        }
        override suspend fun setSessionAgent(sessionId: String, body: com.aegis.hub.data.SetSessionAgentRequest): retrofit2.Response<Unit> {
            setAgenteLlamadas++
            agenteFijado = body.agent
            return ok()
        }
        override suspend fun listAgents() = OpenCodeNativeAgentListResponse(
            listOf(OpenCodeNativeAgent(id = "build", name = "Build"))
        )
        var modelosDisponibles = listOf(
            OpenCodeNativeModel(id = "m-nuevo", providerID = "opencode")
        )

        override suspend fun listModels() = OpenCodeNativeModelListResponse(modelosDisponibles)
        override suspend fun getSessionForms(sessionId: String) = throw UnsupportedOperationException()
        override suspend fun replyForm(sessionId: String, formID: String, body: OpenCodeFormReplyRequest) = ok()
        override suspend fun getSessionPermissions(sessionId: String) = throw UnsupportedOperationException()
        override suspend fun replyPermission(sessionId: String, requestID: String, body: OpenCodePermissionReplyRequest) = ok()
        override suspend fun openEventStream(): okhttp3.ResponseBody = "".toResponseBody()
    }

    private class FakeCache : ConfigCache {
        val modelos = mutableMapOf<String, String>()
        val agentes = mutableMapOf<String, String>()
        var ultimoM: String? = null
        var ultimoA: String? = null
        override fun leerModelo(sid: String) = modelos[sid]
        override fun guardarModelo(sid: String, modelo: String) { modelos[sid] = modelo }
        override fun leerAgente(sid: String) = agentes[sid]
        override fun guardarAgente(sid: String, agente: String) { agentes[sid] = agente }
        override fun ultimoModelo() = ultimoM
        override fun guardarUltimoModelo(modelo: String) { ultimoM = modelo }
        override fun ultimoAgente() = ultimoA
        override fun guardarUltimoAgente(agente: String) { ultimoA = agente }
    }

    private var ahora = 1_700_000_000_000L

    @Test
    fun `leer trae titulo modelo y agente del servidor en una peticion`() = runBlocking {
        val oc = FakeOc()
        val repo = SesionConfigRepo(oc, FakeCache()) { ahora }
        val cfg = repo.leer("ses_x")

        assertEquals(1, oc.getSessionLlamadas)
        assertEquals("Chat X", cfg.titulo)
        assertEquals("m-srv", cfg.modelo)
        assertEquals("build", cfg.agente)
        assertEquals(ConfigSesion.Origen.SERVIDOR, cfg.origen)
    }

    @Test
    fun `fijarModelo falla y la cache no cambia`() = runBlocking {
        val oc = FakeOc().apply { setModeloTira = RuntimeException("caido") }
        val cache = FakeCache()
        val repo = SesionConfigRepo(oc, cache) { ahora }
        val r = repo.fijarModelo("ses_x", "m-nuevo")

        assertTrue(r is Resultado.Fallo)
        assertTrue((r as Resultado.Fallo).motivo.contains("m-nuevo"))
        assertNull(cache.leerModelo("ses_x"))
    }

    @Test
    fun `servidor caido devuelve cache local`() = runBlocking {
        val oc = FakeOc().apply { getSessionTira = RuntimeException("red caida") }
        val cache = FakeCache().apply {
            modelos["ses_x"] = "m-cache"
            agentes["ses_x"] = "plan"
        }
        val repo = SesionConfigRepo(oc, cache) { ahora }
        val cfg = repo.leer("ses_x")

        assertEquals(ConfigSesion.Origen.CACHE_LOCAL, cfg.origen)
        assertEquals("m-cache", cfg.modelo)
        assertEquals("plan", cfg.agente)
    }

    @Test
    fun `fijar el mismo modelo dos veces seguidas es un solo POST`() = runBlocking {
        val oc = FakeOc()
        val repo = SesionConfigRepo(oc, FakeCache()) { ahora }
        repo.leer("ses_x")
        repo.fijarModelo("ses_x", "m-nuevo")
        ahora += 10_000L
        val segunda = repo.fijarModelo("ses_x", "m-nuevo")

        assertTrue(segunda is Resultado.Ok)
        assertEquals(1, oc.setModeloLlamadas)
    }

    @Test
    fun `fijarModelo escribe la cache solo si el servidor confirma`() = runBlocking {
        val oc = FakeOc()
        val cache = FakeCache()
        val repo = SesionConfigRepo(oc, cache) { ahora }
        repo.fijarModelo("ses_x", "m-nuevo")

        assertEquals("m-nuevo", cache.leerModelo("ses_x"))
        assertEquals("m-nuevo", cache.ultimoModelo())
        assertEquals("opencode", oc.ultimoModeloFijado?.providerID)
    }

    @Test
    fun `modelo inexistente en catalogo da Fallo sin llamar al servidor ni tocar cache`() = runBlocking {
        val oc = FakeOc()
        val cache = FakeCache()
        val repo = SesionConfigRepo(oc, cache) { ahora }

        val r = repo.fijarModelo("ses_x", "modelo-inexistente-total")

        assertTrue(r is Resultado.Fallo)
        assertTrue((r as Resultado.Fallo).motivo.contains("no está disponible en OpenCode"))
        assertEquals(0, oc.setModeloLlamadas)
        assertNull(cache.leerModelo("ses_x"))
    }

    @Test
    fun `catalogo caido deja pasar con aviso o llamada sin bloquear`() = runBlocking {
        val oc = object : OpenCodeApi by FakeOc() {
            var setModeloLlamadas = 0
            override suspend fun listModels(): OpenCodeNativeModelListResponse {
                throw RuntimeException("catalogo caido")
            }
            override suspend fun setSessionModel(sessionId: String, body: com.aegis.hub.data.SetSessionModelRequest): retrofit2.Response<Unit> {
                setModeloLlamadas++
                return retrofit2.Response.success(Unit)
            }
        }
        val cache = FakeCache()
        val repo = SesionConfigRepo(oc, cache) { ahora }

        val r = repo.fijarModelo("ses_x", "cualquier-modelo")

        assertTrue("debe pasar aunque catalogo este caido", r is Resultado.Ok)
        assertEquals(1, oc.setModeloLlamadas)
    }
}
