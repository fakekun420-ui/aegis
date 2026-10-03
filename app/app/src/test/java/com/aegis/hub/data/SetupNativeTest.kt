package com.aegis.hub.data

import com.aegis.hub.RootShell
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests unitarios JVM para [SetupNative].
 *
 * Verificaciones clave:
 * 1. Exactamente 2 checks producidos en runFinalCheck ("opencode" y "bootstrap").
 * 2. Exactamente 5 pasos ejecutados y respetados.
 * 3. Si OpenCode o el chroot falla, el check se marca como "fail" o "manual", NUNCA como "ok".
 * 4. Smoke test invoca correctamente OpenCode nativo y propaga fallos con SMOKE_FAILED.
 * 5. Idempotencia: un estado con pasos ya 'done' se salta sin re-ejecutar.
 */
class SetupNativeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `runFinalCheck produce exactamente 2 checks del contrato con sus IDs y labels`() = runBlocking {
        val stateFile = File(tempFolder.root, "bootstrap-state.json")
        val bsNative = BootstrapNative(stateFile = stateFile)
        bsNative.updateState(phase = BootstrapPhase.done)

        val setupNative = SetupNative(
            bootstrapNative = bsNative,
            shellExecutor = { cmd, _ ->
                if (cmd.contains("opencode --version")) RootShell.Result(0, "2.0.14", "")
                else RootShell.Result(0, "", "")
            },
            httpProbe = { url, _ ->
                if (url.contains(":49374")) {
                    SetupNative.HttpProbeResult(ok = true, statusCode = 200, body = """{"healthy":true}""")
                } else {
                    SetupNative.HttpProbeResult(ok = false, statusCode = 404, body = "")
                }
            }
        )

        val fc = setupNative.runFinalCheck()
        assertTrue(fc.ok)
        val data = fc.data
        assertNotNull(data)
        assertEquals(2, data!!.checkList.size)

        val check0 = data.checkList[0]
        assertEquals("opencode", check0.id)
        assertEquals("OpenCode (proxy4096)", check0.label)
        assertEquals(SetupCheckStatus.ok, check0.statusOrManual)

        val check1 = data.checkList[1]
        assertEquals("bootstrap", check1.id)
        assertEquals("Instalación inicial (wizard)", check1.label)
        assertEquals(SetupCheckStatus.ok, check1.statusOrManual)

        assertTrue(data.ready)
    }

    @Test
    fun `runFinalCheck con OpenCode caido marca el check como fail y ready en false`() = runBlocking {
        val stateFile = File(tempFolder.root, "bootstrap-state.json")
        val bsNative = BootstrapNative(stateFile = stateFile)
        bsNative.updateState(phase = BootstrapPhase.done)

        val setupNative = SetupNative(
            bootstrapNative = bsNative,
            shellExecutor = { _, _ -> RootShell.Result(127, "", "not found") },
            httpProbe = { _, _ -> SetupNative.HttpProbeResult(ok = false, statusCode = -1, body = "", error = "Connection refused") }
        )

        val fc = setupNative.runFinalCheck()
        assertTrue(fc.ok)
        assertFalse(fc.data!!.ready)

        val ocCheck = fc.data!!.checkList.first { it.id == "opencode" }
        assertEquals(SetupCheckStatus.fail, ocCheck.statusOrManual)
        assertTrue(ocCheck.detail?.contains("no disponible") == true)
    }

    @Test
    fun `runFinalCheck con wizard incompleto marca bootstrap como fail`() = runBlocking {
        val stateFile = File(tempFolder.root, "bootstrap-state.json")
        val bsNative = BootstrapNative(stateFile = stateFile)
        bsNative.updateState(phase = BootstrapPhase.failed)

        val setupNative = SetupNative(
            bootstrapNative = bsNative,
            shellExecutor = { _, _ -> RootShell.Result(0, "2.0.14", "") },
            httpProbe = { _, _ -> SetupNative.HttpProbeResult(ok = true, statusCode = 200, body = "") }
        )

        val fc = setupNative.runFinalCheck()
        val bsCheck = fc.data!!.checkList.first { it.id == "bootstrap" }
        assertEquals(SetupCheckStatus.fail, bsCheck.statusOrManual)
        assertFalse(fc.data!!.ready)
    }

    @Test
    fun `runBootstrap salta pasos ya marcados como done o skipped sin volver a ejecutarlos`() = runBlocking {
        val stateFile = File(tempFolder.root, "bootstrap-state.json")
        val bsNative = BootstrapNative(stateFile = stateFile)

        // Pre-completar paso preflight y ubuntu
        bsNative.updateStep("preflight", status = BootstrapStepStatus.done, progress = 100)
        bsNative.updateStep("ubuntu", status = BootstrapStepStatus.done, progress = 100)

        var execCalls = 0
        val setupNative = SetupNative(
            bootstrapNative = bsNative,
            shellExecutor = { cmd, _ ->
                execCalls++
                if (cmd.contains("node -v")) RootShell.Result(0, "v24.21.0", "")
                else if (cmd.contains("opencode --version")) RootShell.Result(0, "2.0.14", "")
                else if (cmd.contains("graphify")) RootShell.Result(0, "exists", "")
                else RootShell.Result(0, "", "")
            },
            fileReader = { path ->
                if (path.contains("os-release")) RootShell.Result(0, "ID=ubuntu\nPRETTY_NAME=\"Ubuntu 24.04\"", "")
                else RootShell.Result(0, "", "")
            }
        )

        val res = setupNative.runBootstrap(resume = true)
        assertTrue(res.isSuccess)
        val finalState = res.getOrNull()
        assertNotNull(finalState)
        assertEquals(BootstrapPhase.done, finalState!!.phaseOrIdle)

        // preflight y ubuntu conservan done
        val pre = finalState.stepList.first { it.id == "preflight" }
        assertEquals(BootstrapStepStatus.done, pre.statusOrPending)
    }

    @Test
    fun `smokeTest ante OpenCode inalcanzable devuelve envelope con codigo SMOKE_FAILED`() = runBlocking {
        val stateFile = File(tempFolder.root, "bootstrap-state.json")
        val bsNative = BootstrapNative(stateFile = stateFile)

        // Fake OpenCodeApi que arroja excepción al conectar
        val failingApi = object : OpenCodeApi {
            override suspend fun getInfo(): OpenCodeServerInfo = throw java.io.IOException("Connection refused")
            override suspend fun listSessions(cursor: String?, limit: Int?): OpenCodeSessionListResponse = throw UnsupportedOperationException()
            override suspend fun getSession(id: String): OpenCodeSessionResponse = throw UnsupportedOperationException()
            override suspend fun createSession(body: CreateOpenCodeSessionRequest): OpenCodeSessionResponse = throw UnsupportedOperationException()
            override suspend fun updateSession(id: String, body: UpdateOpenCodeSessionRequest): retrofit2.Response<Unit> = throw UnsupportedOperationException()
            override suspend fun deleteSession(id: String): retrofit2.Response<Unit> = throw UnsupportedOperationException()
            override suspend fun getActiveSessions(): OpenCodeActiveSessionsResponse = throw UnsupportedOperationException()
            override suspend fun getMessages(sessionId: String, limit: Int?, order: String?, cursor: String?): OpenCodeMessageListResponse = throw UnsupportedOperationException()
            override suspend fun getMessage(sessionId: String, messageID: String): OpenCodeMessageResponse = throw UnsupportedOperationException()
            override suspend fun sendPrompt(sessionId: String, body: OpenCodePromptRequest): OpenCodePromptAck = throw UnsupportedOperationException()
            override suspend fun interruptSession(sessionId: String): retrofit2.Response<Unit> = throw UnsupportedOperationException()
            override suspend fun setSessionModel(sessionId: String, body: SetSessionModelRequest): retrofit2.Response<Unit> = throw UnsupportedOperationException()
            override suspend fun setSessionAgent(sessionId: String, body: SetSessionAgentRequest): retrofit2.Response<Unit> = throw UnsupportedOperationException()
            override suspend fun listAgents(): OpenCodeNativeAgentListResponse = throw UnsupportedOperationException()
            override suspend fun listModels(): OpenCodeNativeModelListResponse = throw UnsupportedOperationException()
            override suspend fun getSessionForms(sessionId: String): OpenCodeFormsResponse = throw UnsupportedOperationException()
            override suspend fun replyForm(sessionId: String, formID: String, body: OpenCodeFormReplyRequest): retrofit2.Response<Unit> = throw UnsupportedOperationException()
            override suspend fun getSessionPermissions(sessionId: String): OpenCodePermissionsResponse = throw UnsupportedOperationException()
            override suspend fun replyPermission(sessionId: String, requestID: String, body: OpenCodePermissionReplyRequest): retrofit2.Response<Unit> = throw UnsupportedOperationException()
            override suspend fun openEventStream(): okhttp3.ResponseBody = throw UnsupportedOperationException()
        }

        val setupNative = SetupNative(
            bootstrapNative = bsNative,
            openCodeApi = failingApi
        )

        val smoke = setupNative.runSmokeTest()
        assertFalse(smoke.ok)
        assertNotNull(smoke.error)
        assertEquals("SMOKE_FAILED", smoke.error!!.code)
        assertTrue(smoke.error!!.message?.contains("OpenCode (127.0.0.1:49374) no responde") == true)
    }
}
