package com.aegis.hub.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.aegis.hub.data.AuthGuideData
import com.aegis.hub.data.AuthGuideResponse
import com.aegis.hub.data.BootstrapActionResponse
import com.aegis.hub.data.BootstrapPhase
import com.aegis.hub.data.BootstrapRepository
import com.aegis.hub.data.BootstrapResponse
import com.aegis.hub.data.BootstrapRollback
import com.aegis.hub.data.BootstrapRunRequest
import com.aegis.hub.data.BootstrapState
import com.aegis.hub.data.BootstrapStep
import com.aegis.hub.data.BootstrapStepStatus
import com.aegis.hub.data.FinalCheckData
import com.aegis.hub.data.FinalCheckResponse
import com.aegis.hub.data.SetupCheck
import com.aegis.hub.data.SetupCheckStatus
import com.aegis.hub.data.SmokeTestData
import com.aegis.hub.data.SmokeTestResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException

// ==== F4 — tests JVM de BootstrapViewModel (sin red, sin Android) ====
//
// Estrategia:
//  - Refactor de F4: BootstrapViewModel recibe un BootstrapRepository por
//    PARÁMETRO CON DEFAULT (RetrofitBootstrapRepository). Los tests pasan un
//    fake y ApiClient NUNCA se carga (el default sólo se evalúa si se omite).
//  - Dispatchers.setMain(StandardTestDispatcher()) en @Before: viewModelScope
//    usa Main.immediate. runTest hereda automáticamente el scheduler de Main
//    (documentado en kotlinx-coroutines-test), así que el polling de 1 s y el
//    reintento suave de 5 s se controlan con tiempo VIRTUAL
//    (testScheduler.advanceTimeBy + runCurrent): los tests terminan en
//    milisegundos reales y con el scheduler en reposo (sin jobs colgando).
//  - Los Tests terminan SIEMPRE con el scheduler ocioso: los fakes dejan de
//    reprogramarse (phase done/idle no hacen polling; el fallo de red se
//    recupera en el primer reintento), para no colgar runTest.
@OptIn(ExperimentalCoroutinesApi::class)
class BootstrapViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- 1) Estado inicial (antes de que corra la carga del init) ----
    @Test
    fun estadoInicial_hubAlcanzable_sinPhaseYNilSinActionError() = runTest {
        val fake = FakeRepo().apply {
            onState = { Response.success(BootstrapResponse(ok = true, data = doneState())) }
        }
        val vm = BootstrapViewModel(fake) // init encola refresh() en Main (aún no corre)

        val inicial = vm.ui.value
        assertTrue(inicial.hubReachable)
        assertNull(inicial.state)            // phase == null → la UI deriva phaseOrIdle = idle
        assertNull(inicial.actionError)
        assertFalse(inicial.loading)

        runCurrent()                         // ejecuta la carga inicial encolada
        val trasCarga = vm.ui.value
        assertEquals(BootstrapPhase.done, trasCarga.state?.phase)
        assertEquals(6, trasCarga.state?.stepList?.size)
        assertTrue(trasCarga.hubReachable)
        assertNull(trasCarga.actionError)
        assertFalse(trasCarga.loading)
        assertEquals(1, fake.stateCalls)
    }

    // ---- 2) load()/refresh() con phase "done": estado reflejado, sin polling ----
    @Test
    fun refresh_conPhaseDone_reflejaEstado_yNoArrancaPollingNiReintentos() = runTest {
        val fake = FakeRepo().apply {
            onState = { Response.success(BootstrapResponse(ok = true, data = doneState())) }
        }
        val vm = BootstrapViewModel(fake)
        runCurrent()

        val ui = vm.ui.value
        assertEquals(BootstrapPhase.done, ui.state?.phase)
        assertEquals(BootstrapPhase.done, ui.state?.phaseOrIdle)
        assertEquals(6, ui.state?.stepList?.size)
        assertTrue(ui.hubReachable)
        assertNull(ui.actionError)

        // phase != running → NO hay polling; hubReachable → NO hay reintento suave:
        // pase el tiempo virtual que pase, sólo existió la carga inicial.
        testScheduler.advanceTimeBy(10_000)
        testScheduler.runCurrent()
        assertEquals(1, fake.stateCalls)
    }

    // ---- 3) load()/refresh() con IOException → hubReachable=false + auto-recuperación ----
    @Test
    fun refresh_conIOException_marcaHubInaccesible_yElReintentoSeRecupera() = runTest {
        val fake = FakeRepo()
        var firstCall = true
        fake.onState = { _ ->
            if (firstCall) {
                firstCall = false
                throw IOException("Connection refused")
            }
            Response.success(BootstrapResponse(ok = true, data = doneState()))
        }
        val vm = BootstrapViewModel(fake)
        runCurrent()

        // Fallo de red → bloqueo "Esperando el hub…" (la screen muestra el texto;
        // friendlyError() se testea aparte: este fallo NO genera actionError)
        val caido = vm.ui.value
        assertFalse(caido.hubReachable)
        assertNull(caido.state)
        assertNull(caido.actionError)
        assertFalse(caido.loading)
        assertEquals(1, fake.stateCalls)

        // Reintento suave programado a +5000 ms → esta vez responde y se recupera
        testScheduler.advanceTimeBy(5_000)
        testScheduler.runCurrent()
        assertEquals(2, fake.stateCalls)
        assertTrue(vm.ui.value.hubReachable)
        assertEquals(BootstrapPhase.done, vm.ui.value.state?.phase)
    }

    // ---- 4) runFinalCheck() éxito → finalCheck.ready + los 4 checks ----
    @Test
    fun runFinalCheck_exito_reflejaReadyYLosCuatroChecks() = runTest {
        val fake = FakeRepo().apply {
            onState = { Response.success(BootstrapResponse(ok = true, data = doneState())) }
            onFinalCheck = { Response.success(finalCheckAllOk()) }
        }
        val vm = BootstrapViewModel(fake)
        runCurrent()

        vm.runFinalCheck()
        runCurrent()

        val ui = vm.ui.value
        val fc = ui.finalCheck
        assertNotNull(fc)
        assertTrue(fc!!.data!!.ready)
        assertFalse(ui.finalCheckLoading)
        assertEquals(
            listOf("opencode", "antigravity", "a11y", "bootstrap"),
            fc.data!!.checkList.map { it.id }
        )
        assertEquals(
            listOf(
                "OpenCode (proxy4096)",
                "Antigravity/Artemis (agy + auth)",
                "Servicio de accesibilidad (:8766)",
                "Instalación inicial (wizard)"
            ),
            fc.data!!.checkList.map { it.label }
        )
        assertTrue(fc.data!!.checkList.all { it.statusOrManual == SetupCheckStatus.ok })
        assertNull(ui.actionError)
        assertNull(ui.authGuide) // antigravity ok → NO se dispara guideAuth()
        assertNull(ui.smokeReply)
        assertTrue(ui.hubReachable) // F3: un fallo/success de setup no alterna el bloqueo
    }

    // ---- 5) runFinalCheck() fallo (envelope de error) → actionError legible ----
    @Test
    fun runFinalCheck_falloEnvelop_guiaEnEspanolSinPerderElRaw() = runTest {
        val raw = "EBADCHECKSUM: SHA256 de ubuntu (ubuntu-base.tar.gz) no coincide (esperado aaa111, bbb222)"
        val fake = FakeRepo().apply {
            onState = { Response.success(BootstrapResponse(ok = true, data = doneState())) }
            onFinalCheck = {
                errorResponse(
                    500,
                    """{"ok":false,"error":{"code":"INTERNAL_ERROR","message":"$raw"}}"""
                )
            }
        }
        val vm = BootstrapViewModel(fake)
        runCurrent()

        vm.runFinalCheck()
        runCurrent()

        val ui = vm.ui.value
        // errorBody parseado a mano → guía friendlyError() PREPUENDA + raw intacto
        assertEquals("La descarga no coincide con el SHA256 esperado: reintenta el paso\n$raw", ui.actionError)
        assertNull(ui.finalCheck)
        assertFalse(ui.finalCheckLoading)
        assertTrue(ui.hubReachable) // el error queda LOCAL: la tarjeta sigue visible
    }

    // ---- 6) runFinalCheck() con antigravity en manual → carga la guía de auth ----
    @Test
    fun runFinalCheck_antigravityManual_disparaGuideAuth() = runTest {
        val fake = FakeRepo().apply {
            onState = { Response.success(BootstrapResponse(ok = true, data = doneState())) }
            onFinalCheck = { Response.success(finalCheckAntigravityManual()) }
            onAuthGuide = {
                Response.success(
                    AuthGuideResponse(
                        ok = true,
                        data = AuthGuideData(
                            mode = "manual",
                            command = "/root/.local/bin/agy",
                            status = "missing_auth"
                        )
                    )
                )
            }
        }
        val vm = BootstrapViewModel(fake)
        runCurrent()

        vm.runFinalCheck()
        runCurrent() // runFinalCheck lanza guideAuth() en su propio coroutine → mismo instante virtual

        val ui = vm.ui.value
        assertNotNull(ui.authGuide)
        assertEquals("manual", ui.authGuide?.data?.mode)
        assertEquals("/root/.local/bin/agy", ui.authGuide?.data?.command)
        assertEquals("missing_auth", ui.authGuide?.data?.status)
        assertFalse(ui.authGuideLoading)
        assertNull(ui.actionError)
    }

    // ---- 7) runSmokeTest() éxito → smokeReply = reply ----
    @Test
    fun runSmokeTest_exito_guardaElReplyDeLaIA() = runTest {
        val fake = FakeRepo().apply {
            onState = { Response.success(BootstrapResponse(ok = true, data = doneState())) }
            onSmoke = {
                Response.success(
                    SmokeTestResponse(ok = true, data = SmokeTestData(ok = true, reply = "PONG"))
                )
            }
        }
        val vm = BootstrapViewModel(fake)
        runCurrent()

        vm.runSmokeTest()
        runCurrent()

        val ui = vm.ui.value
        assertEquals("PONG", ui.smokeReply)
        assertNull(ui.smokeError)
        assertFalse(ui.smokeLoading)
        assertNull(ui.actionError)
    }

    // ---- 8) runSmokeTest() fallo SMOKE_FAILED → guía en español SIN perder el raw ----
    @Test
    fun runSmokeTest_falloSmokeFailed_guiaMasRaw() = runTest {
        val raw = "OpenCode (127.0.0.1:4096) no responde — proveedor caído"
        val fake = FakeRepo().apply {
            onState = { Response.success(BootstrapResponse(ok = true, data = doneState())) }
            onSmoke = {
                errorResponse(
                    502,
                    """{"ok":false,"error":{"code":"SMOKE_FAILED","message":"$raw"}}"""
                )
            }
        }
        val vm = BootstrapViewModel(fake)
        runCurrent()

        vm.runSmokeTest()
        runCurrent()

        val ui = vm.ui.value
        assertEquals(
            "La IA no respondió al mensaje de prueba: comprueba la autenticación " +
                "de OpenCode/Antigravity y reintenta\n$raw",
            ui.smokeError
        )
        assertNull(ui.smokeReply) // cada ejecución limpia la respuesta anterior
        assertNull(ui.actionError) // el error de smoke es LOCAL (tarjeta), no de cabecera
        assertTrue(ui.hubReachable)
    }

    // ---- 9) retry() con envelope NOT_FOUND (errorBody parseado a mano) ----
    @Test
    fun retry_pasoDesconocido_muestraPasoNoEncontrado() = runTest {
        val fake = FakeRepo().apply {
            onState = { Response.success(BootstrapResponse(ok = true, data = doneState())) }
            onRetry = {
                errorResponse(
                    404,
                    """{"ok":false,"error":{"code":"NOT_FOUND","message":"paso desconocido: skills"}}"""
                )
            }
        }
        val vm = BootstrapViewModel(fake)
        runCurrent()

        vm.retry("skills")
        runCurrent()

        assertEquals("Paso no encontrado", vm.ui.value.actionError)
        assertEquals(1, fake.retryCalls)
    }

    // ---- 10) Polling de 1 s en phase running + onCleared() cancela los jobs ----
    @Test
    fun pollingEnRunning_cadaUnSegundo_yOnClearedCancelaElJob() = runTest {
        val fake = FakeRepo().apply {
            onState = { Response.success(BootstrapResponse(ok = true, data = runningState())) }
        }
        val vm = BootstrapViewModel(fake)
        runCurrent() // carga inicial: phase=running → startPolling()
        assertEquals(1, fake.stateCalls)
        assertEquals(BootstrapPhase.running, vm.ui.value.state?.phase)

        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent() // 1er tick: delay(1000) virtual → fetchState silencioso
        assertEquals(2, fake.stateCalls)

        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()
        assertEquals(3, fake.stateCalls)

        // onCleared() es protected en androidx.lifecycle.ViewModel: se invoca por
        // reflejo, exactamente como lo haría ViewModelStore.clear() en producción.
        val onCleared = ViewModel::class.java.getDeclaredMethod("onCleared")
        onCleared.isAccessible = true
        onCleared.invoke(vm)

        // Job de polling cancelado → el tiempo pasa y NO se vuelve a consultar
        testScheduler.advanceTimeBy(10_000)
        testScheduler.runCurrent()
        assertEquals(3, fake.stateCalls)
    }
}

// ==== Fakes y fixtures (compilados junto al test; no hay red real) ====

/** Fake de BootstrapRepository: respuestas configurables por test. */
private class FakeRepo : BootstrapRepository {

    /** Recibe el índice de llamada (0-based) y PUELE lanzar (p.ej. IOException). */
    var onState: (Int) -> Response<BootstrapResponse> = {
        Response.success(BootstrapResponse(ok = true, data = doneState()))
    }
    var onFinalCheck: () -> Response<FinalCheckResponse> = {
        Response.success(FinalCheckResponse(ok = false, data = null))
    }
    var onSmoke: () -> Response<SmokeTestResponse> = {
        Response.success(SmokeTestResponse(ok = false, data = null))
    }
    var onAuthGuide: () -> Response<AuthGuideResponse> = {
        Response.success(AuthGuideResponse(ok = false, data = null))
    }
    var onRetry: () -> Response<BootstrapActionResponse> = {
        Response.success(BootstrapActionResponse(ok = true))
    }

    var stateCalls = 0
        private set
    var retryCalls = 0
        private set

    override suspend fun getBootstrapState(): Response<BootstrapResponse> {
        val index = stateCalls
        stateCalls++
        return onState(index)
    }

    override suspend fun runBootstrap(body: BootstrapRunRequest): Response<BootstrapActionResponse> =
        Response.success(BootstrapActionResponse(ok = true))

    override suspend fun retryBootstrapStep(id: String): Response<BootstrapActionResponse> {
        retryCalls++
        return onRetry()
    }

    override suspend fun cancelBootstrap(): Response<BootstrapActionResponse> =
        Response.success(BootstrapActionResponse(ok = true))

    override suspend fun getFinalCheck(): Response<FinalCheckResponse> = onFinalCheck()

    override suspend fun runSmokeTest(): Response<SmokeTestResponse> = onSmoke()

    override suspend fun runAuthGuide(): Response<AuthGuideResponse> = onAuthGuide()
}

/** Ids/títulos EXACTOS de STEP_DEFS (backend/src/bootstrap/state.js). */
private val STEP_DEFS = listOf(
    "preflight" to "Comprobación previa",
    "ubuntu" to "Ubuntu (chroot/proot)",
    "node" to "Node.js",
    "opencode" to "OpenCode",
    "antigravity" to "Antigravity / Artemis",
    "skills" to "Skills y plugins"
)

private fun stepsWith(status: BootstrapStepStatus): List<BootstrapStep> =
    STEP_DEFS.map { (id, title) ->
        BootstrapStep(
            id = id,
            title = title,
            status = status,
            rollback = BootstrapRollback.none,
            progress = if (status == BootstrapStepStatus.done) 100 else 0,
            detail = "detalle del paso",
            error = null
        )
    }

private fun stateWith(phase: BootstrapPhase, status: BootstrapStepStatus): BootstrapState =
    BootstrapState(
        phase = phase,
        currentStepId = null,
        startedAt = "2026-09-24T08:00:00.000Z",
        updatedAt = "2026-09-24T08:00:00.000Z",
        lastError = null,
        steps = stepsWith(status)
    )

private fun doneState(): BootstrapState =
    stateWith(BootstrapPhase.done, BootstrapStepStatus.done)

private fun runningState(): BootstrapState =
    stateWith(BootstrapPhase.running, BootstrapStepStatus.running)

/** 4 checks del contrato (setupRoutes.js CHECK_DEFS), todos ok → ready=true. */
private fun finalCheckAllOk(): FinalCheckResponse =
    FinalCheckResponse(
        ok = true,
        data = FinalCheckData(
            ready = true,
            checks = listOf(
                SetupCheck("opencode", "OpenCode (proxy4096)", SetupCheckStatus.ok, "OpenCode respondiendo en 127.0.0.1:4096"),
                SetupCheck("antigravity", "Antigravity/Artemis (agy + auth)", SetupCheckStatus.ok, "agy + sesión OAuth verificada"),
                SetupCheck("a11y", "Servicio de accesibilidad (:8766)", SetupCheckStatus.ok, "bridge de accesibilidad escuchando"),
                SetupCheck("bootstrap", "Instalación inicial (wizard)", SetupCheckStatus.ok, "wizard completado (phase=done)")
            )
        )
    )

/** antigravity en "manual" → el VM debe encadenar guideAuth(). */
private fun finalCheckAntigravityManual(): FinalCheckResponse =
    FinalCheckResponse(
        ok = true,
        data = FinalCheckData(
            ready = false,
            checks = listOf(
                SetupCheck("opencode", "OpenCode (proxy4096)", SetupCheckStatus.ok, "OpenCode respondiendo en 127.0.0.1:4096"),
                SetupCheck("antigravity", "Antigravity/Artemis (agy + auth)", SetupCheckStatus.manual, "agy SIN sesión OAuth — ejecuta el login"),
                SetupCheck("a11y", "Servicio de accesibilidad (:8766)", SetupCheckStatus.ok, "bridge de accesibilidad escuchando"),
                SetupCheck("bootstrap", "Instalación inicial (wizard)", SetupCheckStatus.ok, "wizard completado (phase=done)")
            )
        )
    )

/** Envelope de error del hub tal y como lo NORMALIZA server.js (json()). */
private fun <T> errorResponse(code: Int, json: String): Response<T> =
    Response.error(code, json.toResponseBody("application/json".toMediaType()))
