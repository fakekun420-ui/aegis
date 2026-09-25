package com.aegis.hub

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.ViewModel
import com.aegis.hub.data.AuthGuideData
import com.aegis.hub.data.AuthGuideResponse
import com.aegis.hub.data.BootstrapActionData
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
import com.aegis.hub.ui.screens.SetupTestTags
import com.aegis.hub.ui.screens.SetupWizardScreen
import com.aegis.hub.ui.theme.OpenCodeCompanionTheme
import com.aegis.hub.ui.viewmodel.BootstrapViewModel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import retrofit2.Response
import java.io.IOException

// ==== F4 — suite instrumentada de Compose: SetupWizardScreen ====
//
// Cubre la navegación/flujo del wizard SIN red: BootstrapViewModel recibe un
// BootstrapRepository falso (mismo puente inyectable que en los tests JVM).
// Localización por SetupTestTags (testTags estables) + textos en español; los
// títulos de paso se buscan con substring=true porque la card agrupa varios
// Text en un mismo nodo padre.
// Asíncrono: nada con sleep() fijo — flags del fake con
// rule.waitUntil(condición, 5 s) y nodos con waitUntilNodeCount(matcher, 1, 5 s).
// NO se ejecuta en CI (decisión F4): sólo `connectedAndroidTest` manual.
class SetupWizardNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    // onCleared es protected: los VMs con jobs vivos (retry de red, polling)
    // se limpian por reflejo para no dejar corutinas huérfanas tras el test.
    private val trackedViewModels = mutableListOf<BootstrapViewModel>()

    @After
    fun tearDown() {
        trackedViewModels.forEach { vm ->
            val method = ViewModel::class.java.getDeclaredMethod("onCleared")
            method.isAccessible = true
            method.invoke(vm)
        }
        trackedViewModels.clear()
    }

    // ---- Falso repo: respuestas fijas construidas a mano (sin OkHttp) ----
    private class FakeRepo(
        private val stateCall: () -> Response<BootstrapResponse>
    ) : BootstrapRepository {
        var runCalled = false
        var finalCheckCalled = false
        var smokeCalled = false

        override suspend fun getBootstrapState(): Response<BootstrapResponse> = stateCall()

        override suspend fun runBootstrap(body: BootstrapRunRequest): Response<BootstrapActionResponse> {
            runCalled = true
            return Response.success(
                BootstrapActionResponse(ok = true, data = BootstrapActionData(BootstrapPhase.running))
            )
        }

        override suspend fun retryBootstrapStep(id: String): Response<BootstrapActionResponse> =
            Response.success(
                BootstrapActionResponse(ok = true, data = BootstrapActionData(BootstrapPhase.running))
            )

        override suspend fun cancelBootstrap(): Response<BootstrapActionResponse> =
            Response.success(
                BootstrapActionResponse(ok = true, data = BootstrapActionData(BootstrapPhase.paused))
            )

        override suspend fun getFinalCheck(): Response<FinalCheckResponse> {
            finalCheckCalled = true
            return Response.success(
                FinalCheckResponse(
                    ok = true,
                    data = FinalCheckData(
                        ready = true,
                        checks = listOf(
                            SetupCheck("opencode", "OpenCode (proxy4096)", SetupCheckStatus.ok, "ok"),
                            SetupCheck(
                                "antigravity", "Antigravity/Artemis (agy + auth)",
                                SetupCheckStatus.ok, "ok"
                            ),
                            SetupCheck("a11y", "Servicio de accesibilidad (:8766)", SetupCheckStatus.ok, "ok"),
                            SetupCheck("bootstrap", "Instalación inicial (wizard)", SetupCheckStatus.ok, "ok")
                        )
                    )
                )
            )
        }

        override suspend fun runSmokeTest(): Response<SmokeTestResponse> {
            smokeCalled = true
            return Response.success(
                SmokeTestResponse(ok = true, data = SmokeTestData(ok = true, reply = "¡Hola desde Aegis!"))
            )
        }

        override suspend fun runAuthGuide(): Response<AuthGuideResponse> = Response.success(
            AuthGuideResponse(
                ok = true,
                data = AuthGuideData(mode = "command", command = "agy login", status = "unauthenticated")
            )
        )
    }

    // ---- Fixtures: 6 pasos con los títulos EXACTOS de STEP_DEFS (state.js) ----
    private val stepTitles = listOf(
        "Comprobación previa",
        "Ubuntu (chroot/proot)",
        "Node.js",
        "OpenCode",
        "Antigravity / Artemis",
        "Skills y plugins"
    )

    private fun stepsFixture(status: BootstrapStepStatus): List<BootstrapStep> {
        val progress = if (status == BootstrapStepStatus.done) 100 else 0
        return listOf(
            BootstrapStep("preflight", stepTitles[0], status, BootstrapRollback.none, progress, "detalle", null),
            BootstrapStep("ubuntu", stepTitles[1], status, BootstrapRollback.none, progress, "detalle", null),
            BootstrapStep("node", stepTitles[2], status, BootstrapRollback.none, progress, "detalle", null),
            BootstrapStep("opencode", stepTitles[3], status, BootstrapRollback.none, progress, "detalle", null),
            BootstrapStep("antigravity", stepTitles[4], status, BootstrapRollback.none, progress, "detalle", null),
            BootstrapStep("skills", stepTitles[5], status, BootstrapRollback.none, progress, "detalle", null)
        )
    }

    private fun stateResponse(phase: BootstrapPhase, status: BootstrapStepStatus): Response<BootstrapResponse> =
        Response.success(
            BootstrapResponse(
                ok = true,
                data = BootstrapState(
                    phase = phase,
                    currentStepId = null,
                    updatedAt = "2026-09-24T10:00:00.000Z",
                    lastError = null,
                    steps = stepsFixture(status)
                )
            )
        )

    /** Compone la pantalla con un VM inyectado y lo registra para la limpieza. */
    private fun setScreen(repo: BootstrapRepository) {
        val vm = BootstrapViewModel(repo)
        trackedViewModels += vm
        composeRule.setContent {
            OpenCodeCompanionTheme {
                SetupWizardScreen(onFinished = {}, viewModel = vm)
            }
        }
    }

    /** Espera (máx. 5 s) a que un nodo con el testTag exista en el árbol. */
    private fun waitTag(tag: String) {
        composeRule.waitUntilNodeCount(hasTestTag(tag), 1, 5_000)
    }

    /** Espera (máx. 5 s) a que un texto (substring) exista en el árbol. */
    private fun waitText(text: String) {
        composeRule.waitUntilNodeCount(hasText(text, substring = true), 1, 5_000)
    }

    // ---- 1) Estado idle: cabecera, subtítulo exacto y botón Iniciar ----
    @Test
    fun idleMuestraCabeceraSubtituloYBotonIniciar() {
        val repo = FakeRepo { stateResponse(BootstrapPhase.idle, BootstrapStepStatus.pending) }
        setScreen(repo)

        // Espera a que el estado llegue (paso visible = fetch completado y
        // loading=false; si no, el botón START estaría disabled y el click
        // no dispararía onClick)
        waitText("Comprobación previa")
        waitTag(SetupTestTags.START)
        // Cabecera (TopAppBar) en español
        composeRule.onNodeWithText("Configuración inicial de Aegis").assertExists()
        // Subtítulo exacto del contrato para phase idle
        composeRule.onNodeWithText(
            "Instala todo lo necesario: Ubuntu, Node.js, OpenCode, Antigravity y skills"
        ).assertExists()
        // Botón "Iniciar instalación" por testTag estable (target >= 48dp)
        composeRule.onNodeWithTag(SetupTestTags.START).assertExists()

        // El click dispara runBootstrap del repo (POST /api/bootstrap/run)
        composeRule.onNodeWithTag(SetupTestTags.START).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { repo.runCalled }
    }

    // ---- 2) Estado done: subtítulo de éxito + los 6 pasos por orden ----
    @Test
    fun doneMuestraSubtituloYLosSeisPasosPorOrden() {
        setScreen(FakeRepo { stateResponse(BootstrapPhase.done, BootstrapStepStatus.done) })

        // El subtítulo "¡Todo listo!" sólo aparece cuando el fetch resolvió
        // phase=done (y la tarjeta queda virtualizada hasta hacer scroll)
        waitText("¡Todo listo!")

        // Los 6 títulos visibles recorriendo la LazyColumn por índice (0..5)
        stepTitles.forEachIndexed { index, title ->
            composeRule.onNode(hasScrollAction()).performScrollToIndex(index)
            composeRule.onNodeWithText(title, substring = true).assertExists()
        }
    }

    // ---- 3) Tarjeta de verificación (sólo done) + botones por testTag ----
    @Test
    fun tarjetaVerificacionVisibleEnDoneConSusBotones() {
        val repo = FakeRepo { stateResponse(BootstrapPhase.done, BootstrapStepStatus.done) }
        setScreen(repo)

        waitText("¡Todo listo!")

        // Título de la tarjeta: índice 6 = 6 pasos + card (después hay un Spacer)
        composeRule.onNode(hasScrollAction()).performScrollToIndex(6)
        waitText("Verificación final")

        // Botón de verificación final (label varía: "Ejecutar…"/"Reintentar…",
        // por eso se localiza por testTag y no por texto)
        composeRule.onNodeWithTag(SetupTestTags.FINAL_CHECK).performScrollTo()
        composeRule.onNodeWithTag(SetupTestTags.FINAL_CHECK).performClick()
        waitText("Verificación superada: todo listo")

        // El resultado crece la card: se lleva el final de la lista al
        // viewport (índice 7 = Spacer final) para que el botón de smoke
        // quede visible antes de pulsarlo
        composeRule.onNode(hasScrollAction()).performScrollToIndex(7)
        waitTag(SetupTestTags.SMOKE_TEST)
        composeRule.onNodeWithTag(SetupTestTags.SMOKE_TEST).performScrollTo()
        composeRule.onNodeWithTag(SetupTestTags.SMOKE_TEST).performClick()
        waitText("¡Hola desde Aegis!")
        composeRule.onNodeWithTag(SetupTestTags.SMOKE_TEST).assertExists()
    }

    // ---- 4) Fuera de done la tarjeta NO aparece ----
    @Test
    fun fueraDeDoneNoSeMuestraLaTarjetaDeVerificacion() {
        setScreen(FakeRepo { stateResponse(BootstrapPhase.idle, BootstrapStepStatus.pending) })

        // Espera a que exista la LazyColumn (state renderizado) antes del scroll
        waitText("Comprobación previa")
        waitTag(SetupTestTags.START)
        // LazyColumn con 6 pasos: índice 6 sólo contiene el Spacer final
        composeRule.onNode(hasScrollAction()).performScrollToIndex(6)
        composeRule.onNodeWithText("Verificación final", substring = true).assertDoesNotExist()
        composeRule.onNodeWithTag(SetupTestTags.FINAL_CHECK).assertDoesNotExist()
        composeRule.onNodeWithTag(SetupTestTags.SMOKE_TEST).assertDoesNotExist()
    }

    // ---- 5) Hub inaccesible: bloqueo de espera en español ----
    @Test
    fun hubInaccesibleMuestraBloqueoDeEspera() {
        setScreen(FakeRepo { throw IOException("connection refused") })

        // Texto exacto del bloqueo F3 (state null + hubReachable=false)
        waitText("Esperando el hub (127.0.0.1:8765)…")
        composeRule.onNodeWithText("Esperando el hub (127.0.0.1:8765)…").assertExists()
        // Sin lista de pasos ni tarjeta tras el fallo de red
        composeRule.onNodeWithText("Verificación final", substring = true).assertDoesNotExist()
    }
}
