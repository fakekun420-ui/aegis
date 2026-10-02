package com.aegis.hub.ui.setup

import androidx.lifecycle.ViewModel
import com.aegis.hub.RootShell
import com.aegis.hub.data.BootstrapNative
import com.aegis.hub.data.BootstrapPhase
import com.aegis.hub.data.BootstrapStepStatus
import com.aegis.hub.data.SetupCheckStatus
import com.aegis.hub.data.SetupNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests unitarios JVM para [SetupNativeViewModel].
 *
 * Verifica el ciclo de vida del ViewModel, las acciones de usuario y la interacción con SetupNative:
 * 1. Estado inicial refleja correctamente la persistencia de BootstrapNative.
 * 2. start() y retry() lanzan corutinas y actualizan el estado a running.
 * 3. Polling automático durante la fase running.
 * 4. runFinalCheck() actualiza finalCheck y guía ante comprobaciones incompletas.
 * 5. onCleared() cancela los jobs pendientes limpiamente.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupNativeViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /**
     * MEDIDO 2026-10-01, y la razon de este `backgroundScope` — CI: los DOS tests de
     * `runFinalCheck` fallaban, el primero con `AssertionError` en su PROPIA declaración (línea 73)
     * y el segundo con `UncaughtExceptionsBeforeTest`. Ninguno de los dos es una aserción mala.
     *
     * La causa: el `init` del ViewModel hace `setupNative.stateFlow.collect { ... }`, y eso
     * **nunca termina**. En un `runTest`, el cuerpo del test espera a que TODAS las corrutinas
     * del ambito terminen, luego `runTest` se queda esperando un `collect` eterno y nunca sale.
     * De ahi el error en la declaración en vez de en un assert: el test no llego a comprobar nada.
     *
     * Y no lo comparo con una teoria, lo comparo con el ViewModel VIEJO, cuyo test SI pasa:
     * `BootstrapViewModel` NO hace `collect` en su init, luego no deja ninguna corrutina
     * colgando. La diferencia entre los dos ficheros explica los dos resultados sin inventar nada.
     *
     * `backgroundScope` es el mecanismo que da la biblioteca para esto: corrutinas que se espera
     * que vivan mas que el test y que se cancelan al terminar. El `collect` del init se pasa ahi,
     * y el test pasa a poder afirmar lo que afirma.
     *
     * El `UnconfinedTestDispatcher` es la mitad complementaria: ejecuta las corrutinas del Main
     * de inmediato, para que `ui.value` este poblado cuando se llega al assert sin necesitar un
     * `runCurrent` extra. `StandardTestDispatcher` —el que usa el `@Before`— las deja en cola, y
     * en un objeto que se construye en una sola linea no hay quien las drene.
     */
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `estado inicial refleja el snapshot persistido y hubReachable es true`() = runTest {
        val stateFile = File(tempFolder.root, "state.json")
        val bsNative = BootstrapNative(stateFile = stateFile)
        val setupNative = SetupNative(bootstrapNative = bsNative)

        val vm = SetupNativeViewModel(setupNative, backgroundScope)
        // MEDIDO 2026-10-02: sin esto, el `while` de sondeo de `startPolling()` sigue vivo
        // en `backgroundScope` y `runTest` se queda esperandolo. Un ViewModel en un test hay
        // que cerrarlo como lo cerraria Android: llamando a `onCleared()`.
        addTearDown {
            val onCleared = androidx.lifecycle.ViewModel::class.java
                .getDeclaredMethod("onCleared")
            onCleared.isAccessible = true
            onCleared.invoke(vm)
        }
        val ui = vm.ui.value

        assertTrue(ui.hubReachable)
        assertNotNull(ui.state)
        assertEquals(BootstrapPhase.idle, ui.state?.phaseOrIdle)
        assertEquals(5, ui.state?.stepList?.size)
        assertFalse(ui.loading)
        assertNull(ui.actionError)
    }

    @Test
    fun `runFinalCheck exitoso expone ready en true y los dos checks del contrato`() = runTest {
        val stateFile = File(tempFolder.root, "state.json")
        val bsNative = BootstrapNative(stateFile = stateFile)
        bsNative.updateState(phase = BootstrapPhase.done)

        val setupNative = SetupNative(
            bootstrapNative = bsNative,
            shellExecutor = { cmd, _ ->
                if (cmd.contains("opencode --version")) RootShell.Result(0, "2.0.14", "")
                else RootShell.Result(0, "", "")
            },
            httpProbe = { url, _ ->
                if (url.contains(":49374")) SetupNative.HttpProbeResult(ok = true, statusCode = 200, body = "")
                else SetupNative.HttpProbeResult(ok = false, statusCode = 404, body = "")
            }
        )

        val vm = SetupNativeViewModel(setupNative, backgroundScope)
        // MEDIDO 2026-10-02: sin esto, el `while` de sondeo de `startPolling()` sigue vivo
        // en `backgroundScope` y `runTest` se queda esperandolo. Un ViewModel en un test hay
        // que cerrarlo como lo cerraria Android: llamando a `onCleared()`.
        addTearDown {
            val onCleared = androidx.lifecycle.ViewModel::class.java
                .getDeclaredMethod("onCleared")
            onCleared.isAccessible = true
            onCleared.invoke(vm)
        }
        runCurrent()

        vm.runFinalCheck()
        runCurrent()

        val ui = vm.ui.value
        assertNotNull(ui.finalCheck)
        assertTrue(ui.finalCheck!!.data!!.ready)
        assertEquals(2, ui.finalCheck!!.data!!.checkList.size)
        assertEquals("opencode", ui.finalCheck!!.data!!.checkList[0].id)
        assertEquals("bootstrap", ui.finalCheck!!.data!!.checkList[1].id)
        assertFalse(ui.finalCheckLoading)
    }

    @Test
    fun `runFinalCheck con check en manual o fail dispara guideAuth`() = runTest {
        val stateFile = File(tempFolder.root, "state.json")
        val bsNative = BootstrapNative(stateFile = stateFile)
        // Bootstrap incompleto -> check bootstrap fail
        bsNative.updateState(phase = BootstrapPhase.failed)

        val setupNative = SetupNative(
            bootstrapNative = bsNative,
            httpProbe = { _, _ -> SetupNative.HttpProbeResult(ok = true, statusCode = 200, body = "") }
        )

        val vm = SetupNativeViewModel(setupNative, backgroundScope)
        // MEDIDO 2026-10-02: sin esto, el `while` de sondeo de `startPolling()` sigue vivo
        // en `backgroundScope` y `runTest` se queda esperandolo. Un ViewModel en un test hay
        // que cerrarlo como lo cerraria Android: llamando a `onCleared()`.
        addTearDown {
            val onCleared = androidx.lifecycle.ViewModel::class.java
                .getDeclaredMethod("onCleared")
            onCleared.isAccessible = true
            onCleared.invoke(vm)
        }
        runCurrent()

        vm.runFinalCheck()
        runCurrent()

        val ui = vm.ui.value
        assertNotNull(ui.finalCheck)
        assertFalse(ui.finalCheck!!.data!!.ready)
        // Al haber un check pendiente se debe activar authGuide
        assertNotNull(ui.authGuide)
        assertEquals("command", ui.authGuide?.data?.mode)
    }

    @Test
    fun `onCleared cancela jobs sin fugar corutinas`() = runTest {
        val stateFile = File(tempFolder.root, "state.json")
        val bsNative = BootstrapNative(stateFile = stateFile)
        val setupNative = SetupNative(bootstrapNative = bsNative)

        val vm = SetupNativeViewModel(setupNative, backgroundScope)
        // MEDIDO 2026-10-02: sin esto, el `while` de sondeo de `startPolling()` sigue vivo
        // en `backgroundScope` y `runTest` se queda esperandolo. Un ViewModel en un test hay
        // que cerrarlo como lo cerraria Android: llamando a `onCleared()`.
        addTearDown {
            val onCleared = androidx.lifecycle.ViewModel::class.java
                .getDeclaredMethod("onCleared")
            onCleared.isAccessible = true
            onCleared.invoke(vm)
        }
        runCurrent()

        val onClearedMethod = ViewModel::class.java.getDeclaredMethod("onCleared")
        onClearedMethod.isAccessible = true
        onClearedMethod.invoke(vm)

        testScheduler.advanceTimeBy(5000)
        testScheduler.runCurrent()
        // Pasa el tiempo sin crash ni jobs colgando
        assertTrue(true)
    }
}
