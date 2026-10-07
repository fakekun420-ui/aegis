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

        // Etapa 1: la conexion se establece (tras el corte + backoff de 1 s).
        val conectado = esperarHasta(10_000L) {
            eventos.conexion.value is EventosServidor.Conexion.Conectado
        }
        assertTrue("la conexion SSE se establecio tras el corte", conectado)
        assertTrue("se reconecto al menos una vez", fake.eventosPeticiones >= 2)

        // Etapa 2: los eventos del stream bueno mueven el turno hasta el fin.
        val terminado = esperarHasta(10_000L) {
            sync.turno.value is EstadoTurno.Terminado
        }

        eventos.detener()
        sync.cerrar()

        assertTrue("el turno termino tras reconectar", terminado)
        val ids = sync.mensajes.value.mapNotNull { it.info?.id }
        assertEquals("sin duplicados", ids.size, ids.toSet().size)
    }

    private suspend fun esperarHasta(ms: Long, cond: () -> Boolean): Boolean {
        val fin = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < fin) {
            if (cond()) return true
            kotlinx.coroutines.delay(100)
        }
        return cond()
    }
}
