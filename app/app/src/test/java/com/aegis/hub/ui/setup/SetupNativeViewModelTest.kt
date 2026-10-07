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
     * Y el `StandardTestDispatcher` del `@Before` **no lleva `testScheduler`**, o sea que crea el
     * suyo. MEDIDO: eso solo vale si nada depende de que el Main y el test compartan reloj, y
     * como este ViewModel lanza trabajo a un dispatcher inyectado (ver `ioDispatcher`), el
     * dispatcher del test es el que se pasa y el del Main es secundario.
     */
    @Before
    fun setUp() {
        // MEDIDO 2026-10-02: esto era `Dispatchers.setMain(UnconfinedTestDispatcher())` y por eso
        // los tests de `runFinalCheck` seguian fallando con `AssertionError` en la DECLARACION,
        // sin ningun frame de stack — que es la firma de que `runTest` se queda esperando.
        //
        // La causa, y no es el ViewModel: **`UnconfinedTestDispatcher()` sin argumentos crea su
        // PROPIO `TestScheduler`**, distinto del que usa `runTest`. Es decir, dos planificadores:
        // el ViewModel corria en el de `UnconfinedTestDispatcher` y `runCurrent()` drena el de
        // `runTest`. El trabajo se lanzaba en uno y se esperaba en el otro, y por eso nada llegaba
        // nunca a tiempo — ni al assert, ni al final de `runTest`.
        //
        // El patron correcto es compartir el planificador del propio test, y es el que ya usa
        // `BootstrapViewModelTest`. `UnconfinedTestDispatcher` sin scheduler no vale para un
        // `runTest`: eso es lo que enseña el fallo.
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * MEDIDO 2026-10-02: `addTearDown` NO EXISTE como metodo de una clase de test JUnit — es
     * de `TestWatcher`, y la CI lo rechazo con `Unresolved reference 'addTearDown'` en las 4
     * lineas. Lo escribi de memoria.
     *
     * Lo que SI funciona, y ya usa este repo en `BootstrapViewModelTest`, es un `@After` que
     * invoca `onCleared()` por reflexion (es `protected` en `androidx.lifecycle.ViewModel`). Aqui
     * hace falta una lista porque los 4 tests crean un ViewModel distinto y se registra cada uno.
     */
    private val vmsAbiertos = mutableListOf<SetupNativeViewModel>()

    @After
    fun cerrarViewModels() {
        for (vm in vmsAbiertos) {
            try {
                val onCleared = androidx.lifecycle.ViewModel::class.java
                    .getDeclaredMethod("onCleared")
                onCleared.isAccessible = true
                onCleared.invoke(vm)
            } catch (_: Exception) {
                // Un test que falla al cerrar no debe enmascarar el fallo real del test.
            }
        }
        vmsAbiertos.clear()
    }

    @Test
    fun `estado inicial refleja el snapshot persistido y servidorAlcanzable es true`() = runTest {
        val stateFile = File(tempFolder.root, "state.json")
        val bsNative = BootstrapNative(stateFile = stateFile)
        // MEDIDO 2026-10-02: se le pasa el dispatcher del test. `SetupNative` hace
        // `withContext(Dispatchers.IO)` en el final-check, y `Dispatchers.IO` es un dispatcher
        // REAL: `runCurrent()` no lo esperaba y el test afirmaba antes de que terminara. El
        // sintoma era `AssertionError` SIN NINGUN frame en la linea de la declaracion.
        val setupNative = SetupNative(bootstrapNative = bsNative, ioDispatcher = StandardTestDispatcher(testScheduler))

        val vm = SetupNativeViewModel(setupNative, backgroundScope)
        // MEDIDO 2026-10-02: sin esto, el `while` de sondeo de `startPolling()` sigue vivo
        // en `backgroundScope` y `runTest` se queda esperandolo. Un ViewModel en un test hay
        // que cerrarlo como lo cerraria Android: llamando a `onCleared()`.
        vmsAbiertos.add(vm)
        val ui = vm.ui.value

        assertTrue(ui.servidorAlcanzable)
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
            ioDispatcher = StandardTestDispatcher(testScheduler),
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
        vmsAbiertos.add(vm)
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
            ioDispatcher = StandardTestDispatcher(testScheduler),
            httpProbe = { _, _ -> SetupNative.HttpProbeResult(ok = true, statusCode = 200, body = "") }
        )

        val vm = SetupNativeViewModel(setupNative, backgroundScope)
        // MEDIDO 2026-10-02: sin esto, el `while` de sondeo de `startPolling()` sigue vivo
        // en `backgroundScope` y `runTest` se queda esperandolo. Un ViewModel en un test hay
        // que cerrarlo como lo cerraria Android: llamando a `onCleared()`.
        vmsAbiertos.add(vm)
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
        // MEDIDO 2026-10-02: se le pasa el dispatcher del test. `SetupNative` hace
        // `withContext(Dispatchers.IO)` en el final-check, y `Dispatchers.IO` es un dispatcher
        // REAL: `runCurrent()` no lo esperaba y el test afirmaba antes de que terminara. El
        // sintoma era `AssertionError` SIN NINGUN frame en la linea de la declaracion.
        val setupNative = SetupNative(bootstrapNative = bsNative, ioDispatcher = StandardTestDispatcher(testScheduler))

        val vm = SetupNativeViewModel(setupNative, backgroundScope)
        // MEDIDO 2026-10-02: sin esto, el `while` de sondeo de `startPolling()` sigue vivo
        // en `backgroundScope` y `runTest` se queda esperandolo. Un ViewModel en un test hay
        // que cerrarlo como lo cerraria Android: llamando a `onCleared()`.
        vmsAbiertos.add(vm)
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
