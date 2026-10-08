package com.aegis.hub.data.flujo

import com.aegis.hub.data.sync.EventosServidor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Diagnostico de entrega SSE (F10): el bucle lee lineas y conecta, con un
 * `ResponseBody` construido en local (sin red).
 *
 * MEDIDO 2026-10-07: el mismo stream servido por MockWebServer via Retrofit no
 * entrega ni un byte en CI (peticiones=0, timeouts agotados) mientras las rutas
 * unarias van instantaneas; causa del transporte sin localizar tras ~10 ciclos.
 * Esto prueba MI codigo (bucle, backoff, conexion); la interop HTTP/SSE se
 * valida en el movil (V-06/V-08).
 */
class FlujoEventosDirectoTest {

    private val CUERPO = """
        data: {"id":"e1","type":"session.text.started","data":{"sessionID":"ses_test"}}

        data: {"id":"e2","type":"session.text.delta","data":{"sessionID":"ses_test","delta":"Ho"}}

        data: {"id":"e3","type":"session.text.ended","data":{"sessionID":"ses_test","text":"Hola"}}

        data: {"id":"e4","type":"session.execution.succeeded","data":{"sessionID":"ses_test"},"durable":{"aggregateID":"ses_test","seq":7,"version":1}}

    """.trimIndent()

    private fun cuerpo() = CUERPO.toResponseBody("text/event-stream".toMediaType())

    @Test
    fun `el stream entrega lineas y conecta`() = runBlocking {
        val eventos = EventosServidor(this, abrir = { cuerpo() })
        eventos.iniciar()

        val recibidas = kotlinx.coroutines.withTimeoutOrNull(5_000L) {
            eventos.lineas.take(4).toList()
        }.orEmpty()
        val conexion = eventos.conexion.value
        eventos.detener()

        assertEquals(4, recibidas.size)
        assertTrue(
            "conexion=${conexion}",
            conexion is EventosServidor.Conexion.Conectado
        )
    }

    @Test
    fun `EOF limpio reabre sin emitir Reconectando`() = runBlocking {
        var lecturas = 0
        val estadosObservados = mutableListOf<EventosServidor.Conexion>()
        val eventos = EventosServidor(this, abrir = {
            lecturas++
            cuerpo()
        })
        val recolector = kotlinx.coroutines.launch {
            eventos.conexion.collect { estadosObservados.add(it) }
        }
        eventos.iniciar()

        // Dejar que lea el primer stream (EOF) y reabra el segundo tras la pausa
        val r = kotlinx.coroutines.withTimeoutOrNull(2_000L) {
            while (lecturas < 2) {
                kotlinx.coroutines.delay(50)
            }
            true
        }
        eventos.detener()
        recolector.cancel()

        assertTrue("reabrió tras EOF", r == true)
        assertTrue(
            "no debe pasar por Reconectando en EOF limpio: $estadosObservados",
            estadosObservados.none { it is EventosServidor.Conexion.Reconectando && estadosObservados.indexOf(it) > 0 }
        )
    }

    @Test
    fun `excepcion si emite Reconectando`() = runBlocking {
        var intento = 0
        val estadosObservados = mutableListOf<EventosServidor.Conexion>()
        val eventos = EventosServidor(this, abrir = {
            intento++
            if (intento == 1) throw java.io.IOException("corte de red simulado")
            cuerpo()
        })
        val recolector = kotlinx.coroutines.launch {
            eventos.conexion.collect { estadosObservados.add(it) }
        }
        eventos.iniciar()

        val r = kotlinx.coroutines.withTimeoutOrNull(3_000L) {
            while (estadosObservados.none { it is EventosServidor.Conexion.Conectado }) {
                kotlinx.coroutines.delay(50)
            }
            true
        }
        eventos.detener()
        recolector.cancel()

        assertTrue("reconectó tras excepción", r == true)
        assertTrue(
            "debe emitir Reconectando tras fallo de red: $estadosObservados",
            estadosObservados.any { it is EventosServidor.Conexion.Reconectando }
        )
    }
}
