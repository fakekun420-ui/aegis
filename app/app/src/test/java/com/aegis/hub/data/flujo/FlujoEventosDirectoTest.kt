package com.aegis.hub.data.flujo

import com.aegis.hub.data.sync.EventosServidor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Diagnostico de entrega SSE (F10): el stream del falso llega linea a linea y
 * la conexion pasa a Conectado. Separa la capa HTTP de ChatSync.
 */
class FlujoEventosDirectoTest {

    private lateinit var fake: FakeOpenCode

    @Before
    fun arrancar() {
        fake = FakeOpenCode()
    }

    @After
    fun parar() {
        fake.cerrar()
    }

    @Test
    fun `el stream entrega lineas y conecta`() = runBlocking {
        val oc = fake.api()
        val eventos = EventosServidor(this, abrir = { oc.openEventStream() })
        val recibidas = mutableListOf<String>()
        val recolector = launch {
            eventos.lineas.collect { recibidas.add(it) }
        }
        eventos.iniciar()

        val fin = System.currentTimeMillis() + 8_000L
        while (System.currentTimeMillis() < fin && recibidas.size < 4) {
            kotlinx.coroutines.delay(100)
        }
        val conexion = eventos.conexion.value
        eventos.detener()
        recolector.cancel()

        assertTrue(
            "conexion=${conexion} peticiones=${fake.eventosPeticiones}",
            conexion is EventosServidor.Conexion.Conectado
        )
        assertTrue("llegaron ${recibidas.size} lineas", recibidas.size >= 4)
        assertEquals(1, fake.eventosPeticiones)
    }
}
