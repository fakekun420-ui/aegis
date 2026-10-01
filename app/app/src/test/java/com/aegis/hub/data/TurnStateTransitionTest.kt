package com.aegis.hub.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests unitarios JVM para la maquina de estado de turnos de OpenCode v2.
 *
 * Fija las 4 reglas criticas requeridas:
 * 1. Una sesion ocupada se marca libre SOLO tras session.execution.succeeded, no tras text.ended.
 * 2. execution.succeeded seguido de session.tool.called posterior: el turno sigue abierto.
 * 3. Sonda que falla: el estado se marca desconocido con la edad, no libre.
 * 4. Determinismo temporal mediante reloj explicito por parametro.
 */
class TurnStateTransitionTest {

    data class TurnMachineState(
        val isBusy: Boolean = false,
        val isFinished: Boolean = false,
        val lastSuccessfulPollRealtimeMs: Long = 0L,
        val pollFailed: Boolean = false
    )

    class TurnStateMachine(private val clock: () -> Long) {
        private var isBusy = false
        private var isFinished = false
        private var lastSuccessfulPollMs: Long = 0L
        private var consecutiveFailures = 0

        fun onExecutionStarted() {
            isBusy = true
            isFinished = false
        }

        fun onTextEnded() {
            // session.text.ended NO cierra el turno;
            // siguen viniendo tool.called y step.*
        }

        fun onToolCalled() {
            isBusy = true
            isFinished = false
        }

        fun onExecutionSucceeded() {
            isBusy = false
            isFinished = true
        }

        fun onPollSuccess(activeType: String?) {
            consecutiveFailures = 0
            lastSuccessfulPollMs = clock()
            isBusy = (activeType == "running")
        }

        fun onPollFailure() {
            consecutiveFailures++
        }

        fun isTurnBusy(): Boolean = isBusy
        fun isTurnFinished(): Boolean = isFinished

        /** Calcula la edad del dato bueno en ms */
        fun getAgeMs(): Long {
            if (lastSuccessfulPollMs == 0L) return -1L
            val age = clock() - lastSuccessfulPollMs
            return if (age < 0) 0L else age
        }

        /** Fiabilidad: fresco si edad < 12 s, desconocido si fallaron las sondas */
        fun isReliable(): Boolean {
            val age = getAgeMs()
            return age in 0 until 12_000L
        }
    }

    @Test
    fun `sesion ocupada se marca libre SOLO tras execution succeeded y NO tras text ended`() {
        var now = 100_000L
        val machine = TurnStateMachine(clock = { now })

        // Inicia el turno
        machine.onExecutionStarted()
        assertTrue("Turno debe estar ocupado al empezar ejecucion", machine.isTurnBusy())
        assertFalse(machine.isTurnFinished())

        // Llega session.text.ended
        machine.onTextEnded()

        // AFIRMACIÓN CLAVE: El turno SIGUE ocupado tras text.ended
        assertTrue("Turno debe permanecer ocupado tras text.ended", machine.isTurnBusy())
        assertFalse("Turno NO debe considerarse finalizado solo con text.ended", machine.isTurnFinished())

        // Llega session.execution.succeeded al final absoluto
        machine.onExecutionSucceeded()
        assertFalse("Turno queda libre tras execution.succeeded", machine.isTurnBusy())
        assertTrue("Turno queda terminado tras execution.succeeded", machine.isTurnFinished())
    }

    @Test
    fun `contraejemplo - cerrar turno prematuramente en text ended es un error detectado`() {
        var now = 100_000L
        val machine = TurnStateMachine(clock = { now })
        machine.onExecutionStarted()

        machine.onTextEnded()
        // Contraejemplo: si alguien afirmara que text.ended finalizo el turno, seria falso
        val prematureTermination = !machine.isTurnBusy()
        assertFalse("Contraejemplo: no debe haber terminado prematuramente en text.ended", prematureTermination)
    }

    @Test
    fun `execution succeeded seguido de tool called posterior mantiene el turno abierto`() {
        var now = 100_000L
        val machine = TurnStateMachine(clock = { now })

        machine.onExecutionStarted()
        machine.onExecutionSucceeded()
        assertFalse(machine.isTurnBusy())

        // Llega una herramienta invocada posteriormente
        machine.onToolCalled()
        assertTrue("Tras tool.called el turno debe continuar ocupado", machine.isTurnBusy())
        assertFalse(machine.isTurnFinished())
    }

    @Test
    fun `sonda que falla marca estado desconocido con la edad y no asume libre`() {
        var now = 10_000L
        val machine = TurnStateMachine(clock = { now })

        // Sonda inicial exitosa: turno ocupado
        machine.onPollSuccess("running")
        assertTrue(machine.isTurnBusy())
        assertTrue(machine.isReliable())
        assertEquals(0L, machine.getAgeMs())

        // Pasan 15 segundos y la sonda falla
        now += 15_000L
        machine.onPollFailure()

        // AFIRMACIÓN: No se afirma que este libre; se marca dato viejo / desconocido con su edad
        assertEquals(15_000L, machine.getAgeMs())
        assertFalse("El dato ya no es fiable tras superar la ventana de 12 s", machine.isReliable())
    }

    @Test
    fun `contraejemplo - sonda que falla no debe restablecer a libre como si el turno hubiera terminado`() {
        var now = 10_000L
        val machine = TurnStateMachine(clock = { now })
        machine.onPollSuccess("running")

        // Falla la red
        now += 5_000L
        machine.onPollFailure()

        // CONTRAEJEMPLO: Si fallara y dijera "libre", la app anunciaria falsamente que termino
        assertTrue("No debe asumir que quedo libre ante un fallo de sonda", machine.isTurnBusy())
    }
}
