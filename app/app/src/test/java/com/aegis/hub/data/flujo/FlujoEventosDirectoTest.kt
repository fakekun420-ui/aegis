package com.aegis.hub.data.flujo

import com.aegis.hub.data.sync.EventosServidor
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
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
        // Paso -1 (control): la ruta unaria sobre el mismo servidor/cliente va.
        val eco = fake.api().getMessages("ses_test", 10, null, null)
        println("DIAG unaria ok=${eco.data?.size} peticiones=${fake.peticiones.size}")

        // Paso 0 (bypass): el GET crudo trae bytes sin bucle de por medio.
        // Con timeout propio: si el streaming HTTP se cuelga, esto lo dice en 5 s
        // en vez de colgar el worker hasta el readTimeout (660 s).
        val crudo = kotlinx.coroutines.withTimeoutOrNull(5_000L) {
            fake.api().openEventStream().string()
        }.orEmpty()
        assertTrue("el GET crudo trae lineas: '${crudo.take(80)}'", crudo.lines().any { it.startsWith("data:") })

        val oc = fake.api()
        val eventos = EventosServidor(this, abrir = { oc.openEventStream() })
        eventos.iniciar()

        repeat(8) { i ->
            kotlinx.coroutines.delay(1_000)
            println("DIAG t=${i + 1}s conexion=${eventos.conexion.value} peticiones=${fake.eventosPeticiones}")
        }
        val recibidas = kotlinx.coroutines.withTimeoutOrNull(8_000L) {
            eventos.lineas.take(4).toList()
        }.orEmpty()
        val conexion = eventos.conexion.value
        eventos.detener()

        assertTrue(
            "conexion=${conexion} peticiones=${fake.eventosPeticiones}",
            conexion is EventosServidor.Conexion.Conectado
        )
        assertTrue("llegaron ${recibidas.size} lineas", recibidas.size >= 4)
        assertEquals(1, fake.eventosPeticiones)
    }
}
