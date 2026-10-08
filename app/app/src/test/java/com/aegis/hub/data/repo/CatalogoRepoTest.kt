package com.aegis.hub.data.repo

import com.aegis.hub.data.OpenCodeApi
import com.aegis.hub.data.OpenCodeFormReplyRequest
import com.aegis.hub.data.OpenCodeNativeModel
import com.aegis.hub.data.OpenCodeNativeModelListResponse
import com.aegis.hub.data.OpenCodePermissionReplyRequest
import com.aegis.hub.data.OpenCodePromptRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests JVM para [CatalogoRepo] (F4, T-F4.1): TTL, vuelo unico e invalidacion.
 */
class CatalogoRepoTest {

    private class FakeOc : OpenCodeApi {
        var modelosLlamadas = 0
        var puerta: CompletableDeferred<Unit>? = null
        var modelos = listOf(
            OpenCodeNativeModel(id = "m-free", providerID = "opencode"),
            OpenCodeNativeModel(id = "m-pago", providerID = "opencode")
        )

        private fun no(): Nothing = throw UnsupportedOperationException()
        override suspend fun getInfo() = no()
        override suspend fun listSessions(cursor: String?, limit: Int?) = no()
        override suspend fun getSession(id: String) = no()
        override suspend fun createSession(body: com.aegis.hub.data.CreateOpenCodeSessionRequest) = no()
        override suspend fun updateSession(id: String, body: com.aegis.hub.data.UpdateOpenCodeSessionRequest) =
            retrofit2.Response.success(Unit)
        override suspend fun deleteSession(id: String) = retrofit2.Response.success(Unit)
        override suspend fun getActiveSessions() = no()
        override suspend fun getMessages(sessionId: String, limit: Int?, order: String?, cursor: String?) = no()
        override suspend fun getMessage(sessionId: String, messageID: String) = no()
        override suspend fun sendPrompt(sessionId: String, body: OpenCodePromptRequest) = no()
        override suspend fun interruptSession(sessionId: String) = retrofit2.Response.success(Unit)
        override suspend fun setSessionModel(sessionId: String, body: com.aegis.hub.data.SetSessionModelRequest) =
            retrofit2.Response.success(Unit)
        override suspend fun setSessionAgent(sessionId: String, body: com.aegis.hub.data.SetSessionAgentRequest) =
            retrofit2.Response.success(Unit)
        override suspend fun listAgents() = com.aegis.hub.data.OpenCodeNativeAgentListResponse(emptyList())
        override suspend fun listModels(): OpenCodeNativeModelListResponse {
            modelosLlamadas++
            puerta?.await()
            return OpenCodeNativeModelListResponse(modelos)
        }
        override suspend fun getSessionForms(sessionId: String) = no()
        override suspend fun replyForm(sessionId: String, formID: String, body: OpenCodeFormReplyRequest) =
            retrofit2.Response.success(Unit)
        override suspend fun getSessionPermissions(sessionId: String) = no()
        override suspend fun replyPermission(sessionId: String, requestID: String, body: OpenCodePermissionReplyRequest) =
            retrofit2.Response.success(Unit)
        override suspend fun openEventStream(): okhttp3.ResponseBody = no()
    }

    private var ahora = 1_700_000_000_000L

    @Test
    fun `segunda lectura dentro del TTL no pide red`() = runBlocking {
        val oc = FakeOc()
        val repo = CatalogoRepo(oc) { ahora }
        repo.modelosNativos()
        ahora += 60_000L
        repo.modelosNativos()

        assertEquals(1, oc.modelosLlamadas)
    }

    @Test
    fun `vencido el TTL vuelve a pedir`() = runBlocking {
        val oc = FakeOc()
        val repo = CatalogoRepo(oc) { ahora }
        repo.modelosNativos()
        ahora += CatalogoRepo.TTL_MS + 1L
        repo.modelosNativos()

        assertEquals(2, oc.modelosLlamadas)
    }

    @Test
    fun `dos lecturas simultaneas comparten una sola peticion`() = runBlocking {
        val oc = FakeOc()
        val puerta = CompletableDeferred<Unit>()
        oc.puerta = puerta
        val repo = CatalogoRepo(oc) { ahora }

        val a = async { repo.modelosNativos() }
        val b = async { repo.modelosNativos() }
        // Las dos tienen que estar esperando (puerta cerrada) antes de abrir.
        kotlinx.coroutines.delay(10)
        puerta.complete(Unit)

        assertEquals(listOf(2, 2), listOf(a.await().size, b.await().size))
        assertEquals(1, oc.modelosLlamadas)
    }

    @Test
    fun `esGratis sale del cacheo sin red extra`() = runBlocking {
        val oc = FakeOc()
        val repo = CatalogoRepo(oc) { ahora }
        repo.modelosNativos()

        assertTrue(repo.esGratis("m-free"))
        assertEquals(1, oc.modelosLlamadas)
    }

    @Test
    fun `invalidar fuerza una peticion nueva`() = runBlocking {
        val oc = FakeOc()
        val repo = CatalogoRepo(oc) { ahora }
        repo.modelosNativos()
        repo.invalidar()
        repo.modelosNativos()

        assertEquals(2, oc.modelosLlamadas)
    }
}
