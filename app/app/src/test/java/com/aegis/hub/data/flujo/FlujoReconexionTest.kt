package com.aegis.hub.data.flujo

import com.aegis.hub.data.RutaNativa
import com.aegis.hub.data.sync.ChatSync
import com.aegis.hub.data.sync.EstadoTurno
import com.aegis.hub.data.sync.EventosServidor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Flujo reconexion SSE (F10): el primer stream se corta, el segundo entrega el
 * turno completo; al volver no hay mensajes duplicados.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlujoReconexionTest {

    private lateinit var fake: FakeOpenCode

    @Before
    fun arrancar() {
        fake = FakeOpenCode().apply { primerEventoCorta = true }
    }

    @After
    fun parar() {
        fake.cerrar()
    }

    @Test
    fun `cae y vuelve sin duplicar`() = runBlocking {
        val oc = fake.api()
        val api = RutaNativa(oc = oc)
        val eventos = EventosServidor(this, abrir = { oc.openEventStream() })
        val sync = ChatSync(
            CoroutineScope(UnconfinedTestDispatcher()),
            eventos,
            leerCola = { sid -> api.getMessagesTail(sid, 200).data.orEmpty() }
        )
        eventos.iniciar()
        sync.abrir("ses_test")

        // Espera real: el corte + backoff (1 s) + stream bueno + eventos.
        var turnos = 0
        repeat(60) {
            kotlinx.coroutines.delay(100)
            if (sync.turno.value is EstadoTurno.Terminado) {
                turnos++
                return@runBlocking
            }
        }

        eventos.detener()
        sync.cerrar()

        assertTrue("el turno termino tras reconectar", turnos > 0)
        assertTrue("se reconecto al menos una vez", fake.eventosPeticiones >= 2)
        val ids = sync.mensajes.value.mapNotNull { it.info?.id }
        assertEquals("sin duplicados", ids.size, ids.toSet().size)
    }
}
