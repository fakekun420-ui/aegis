package com.aegis.hub.ui.viewmodel

import com.aegis.hub.data.OpenCodeApi
import com.aegis.hub.data.OpenCodeFormReplyRequest
import com.aegis.hub.data.OpenCodeModelRef
import com.aegis.hub.data.OpenCodeNativeAgent
import com.aegis.hub.data.OpenCodeNativeAgentListResponse
import com.aegis.hub.data.OpenCodeNativeModelListResponse
import com.aegis.hub.data.OpenCodePermissionReplyRequest
import com.aegis.hub.data.OpenCodePromptRequest
import com.aegis.hub.data.OpenCodeSession
import com.aegis.hub.data.OpenCodeSessionResponse
import com.aegis.hub.data.repo.ConfigCache
import com.aegis.hub.data.repo.SesionConfigRepo
import com.aegis.hub.data.repo.SesionesRepo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests JVM del estado de config de `ChatViewModel` (F3, T-F3.2).
 *
 * Con `UnconfinedTestDispatcher` como Main, cada `cargarConfig` corre con ansia
 * hasta su primer punto de suspension: la carrera A-lento/B-rapido es determinista
 * sin relojes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigSesionVmTest {

    private class FakeOc : OpenCodeApi {
        var puertas = mapOf<String, CompletableDeferred<OpenCodeSessionResponse>>()
        var setModeloTira: Exception? = null
        var setModeloLlamadas = 0
        var agentes = listOf(
            OpenCodeNativeAgent(id = "build", name = "Build", model = OpenCodeModelRef(id = "m-b", providerID = "opencode"))
        )

        private fun ok(): retrofit2.Response<Unit> = retrofit2.Response.success(Unit)
        private fun sesion(sid: String, modelo: String, agente: String) =
            OpenCodeSessionResponse(OpenCodeSession(id = sid, title = "T", agent = agente, model = OpenCodeModelRef(id = modelo)))

        override suspend fun getInfo() = throw UnsupportedOperationException()
        override suspend fun listSessions(cursor: String?, limit: Int?) = throw UnsupportedOperationException()
        override suspend fun getSession(id: String): OpenCodeSessionResponse {
            val letra = id.takeLast(1)
            return puertas[letra]?.await()
                ?: sesion(id, "m-$letra", "ag-$letra")
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
            return ok()
        }
        override suspend fun setSessionAgent(sessionId: String, body: com.aegis.hub.data.SetSessionAgentRequest) = ok()
        override suspend fun listAgents() = OpenCodeNativeAgentListResponse(agentes)
        override suspend fun listModels() = OpenCodeNativeModelListResponse(
            listOf(
                com.aegis.hub.data.OpenCodeNativeModel(id = "m-nuevo", providerID = "opencode"),
                com.aegis.hub.data.OpenCodeNativeModel(id = "m-b", providerID = "opencode"),
                com.aegis.hub.data.OpenCodeNativeModel(id = "mx", providerID = "opencode")
            )
        )
        override suspend fun getSessionForms(sessionId: String) = throw UnsupportedOperationException()
        override suspend fun replyForm(sessionId: String, formID: String, body: OpenCodeFormReplyRequest) = ok()
        override suspend fun getSessionPermissions(sessionId: String) = throw UnsupportedOperationException()
        override suspend fun replyPermission(sessionId: String, requestID: String, body: OpenCodePermissionReplyRequest) = ok()
        override suspend fun openEventStream(): okhttp3.ResponseBody =
            throw UnsupportedOperationException()
    }

    private class FakeCache : ConfigCache {
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

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun preparar() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun limpiar() {
        Dispatchers.resetMain()
    }

    private fun vm(oc: FakeOc, cache: FakeCache = FakeCache()): ChatViewModel {
        val repo = SesionConfigRepo(oc, cache) { 0L }
        return ChatViewModel(repo, SesionesRepo())
    }

    private fun fijarSesion(vm: ChatViewModel, sid: String) {
        (vm.currentSessionId as MutableStateFlow<String?>).value = sid
    }

    @Test
    fun `una respuesta tardia de la sesion A no pisa la sesion B`() {
        val puertaA = CompletableDeferred<OpenCodeSessionResponse>()
        val oc = FakeOc().apply { puertas = mapOf("A" to puertaA) }
        val vm = vm(oc)

        fijarSesion(vm, "A")
        vm.cargarConfig("A")
        fijarSesion(vm, "B")
        vm.cargarConfig("B")
        puertaA.complete(
            OpenCodeSessionResponse(OpenCodeSession(id = "A", model = OpenCodeModelRef(id = "m-A"), agent = "ag-A"))
        )

        assertEquals("m-B", vm.selectedModel.value)
        assertEquals("ag-B", vm.agentMode.value)
    }

    @Test
    fun `cargarConfig sin sesion actual no escribe nada`() {
        val oc = FakeOc()
        val vm = vm(oc)
        vm.cargarConfig("A")

        assertNull(vm.selectedModel.value)
        assertEquals("build", vm.agentMode.value)
    }

    @Test
    fun `fijarModelo falla y el chip vuelve al previo con motivo`() {
        val oc = FakeOc().apply { setModeloTira = RuntimeException("caido") }
        val vm = vm(oc)
        fijarSesion(vm, "ses_x")

        vm.selectModel("m-nuevo")

        assertNull("el chip vuelve al previo", vm.selectedModel.value)
        assertTrue(vm.error.value!!.contains("m-nuevo"))
    }

    @Test
    fun `cambiar de agente sin modelo manual aplica el modelo del agente`() {
        val oc = FakeOc()
        val vm = vm(oc)
        fijarSesion(vm, "ses_x")

        vm.selectAgent("build")

        assertEquals("build", vm.agentMode.value)
        assertEquals("m-b", vm.selectedModel.value)
    }

    @Test
    fun `cambiar de agente con modelo manual no pisa el modelo`() {
        val oc = FakeOc()
        val vm = vm(oc)
        fijarSesion(vm, "ses_x")

        vm.selectModel("mx")
        vm.selectAgent("build")

        assertEquals("build", vm.agentMode.value)
        assertEquals("mx", vm.selectedModel.value)
    }

    @Test
    fun `elegir modelo no disponible en catalogo revierte el chip y pone error`() {
        val oc = FakeOc()
        val vm = vm(oc)
        fijarSesion(vm, "ses_x")

        vm.selectModel("m-inexistente")

        assertNull(vm.selectedModel.value)
        assertTrue(vm.error.value!!.contains("m-inexistente"))
    }
}
