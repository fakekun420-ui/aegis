package com.aegis.hub.data.flujo

import com.aegis.hub.data.Message
import com.aegis.hub.data.MessageInfo
import com.aegis.hub.data.MessagePart
import com.aegis.hub.data.sync.ChatSync
import com.aegis.hub.data.sync.EstadoTurno
import com.aegis.hub.data.sync.EventosServidor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Flujos SSE de punta a punta SIN red (F10): el transporte es un `abrir`
 * programable (cuerpos locales o excepciones = mismo camino de reconexion que
 * un 500/EOF). Ver FlujoEventosDirectoTest para el por que (transporte
 * HTTP/SSE mudo en CI).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlujoReconexionTest {

    private val CUERPO = """
        data: {"id":"e1","type":"session.text.started","data":{"sessionID":"ses_test"}}

        data: {"id":"e2","type":"session.text.delta","data":{"sessionID":"ses_test","delta":"Ho"}}

        data: {"id":"e3","type":"session.text.ended","data":{"sessionID":"ses_test","text":"Hola"}}

        data: {"id":"e4","type":"session.execution.succeeded","data":{"sessionID":"ses_test"},"durable":{"aggregateID":"ses_test","seq":7,"version":1}}

    """.trimIndent()

    private fun cuerpo() = CUERPO.toResponseBody("text/event-stream".toMediaType())

    private fun msg(id: String, texto: String, rol: String = "user") = Message(
        info = MessageInfo(id = id, role = rol),
        parts = listOf(MessagePart(type = "text", text = texto))
    )

    private fun syncCon(
        eventos: EventosServidor,
        cola: List<Message> = listOf(msg("msg_1", "hola"), msg("msg_2", "buenas", "assistant"))
    ) = ChatSync(
        CoroutineScope(UnconfinedTestDispatcher()),
        eventos,
        leerCola = { cola }
    )

    private suspend fun esperarHasta(ms: Long, cond: () -> Boolean): Boolean {
        val fin = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < fin) {
            if (cond()) return true
            kotlinx.coroutines.delay(100)
        }
        return cond()
    }

    @Test
    fun `stream bueno mueve el turno hasta el fin sin duplicar`() = runBlocking {
        val eventos = EventosServidor(this, abrir = { cuerpo() })
        val sync = syncCon(eventos)
        eventos.iniciar()
        sync.abrir("ses_test")

        val terminado = esperarHasta(10_000L) {
            sync.turno.value is EstadoTurno.Terminado
        }
        eventos.detener()
        sync.cerrar()

        assertTrue(
            "el turno termino con el stream bueno (turno=${sync.turno.value} estado=${sync.estado.value})",
            terminado
        )
        val ids = sync.mensajes.value.mapNotNull { it.info?.id }
        assertEquals("sin duplicados", ids.size, ids.toSet().size)
    }

    @Test
    fun `cae y vuelve sin duplicar`() = runBlocking {
        var llamadas = 0
        val eventos = EventosServidor(this, abrir = {
            llamadas++
            if (llamadas == 1) throw java.io.IOException("corte")
            cuerpo()
        })
        val sync = syncCon(eventos)
        eventos.iniciar()
        sync.abrir("ses_test")

        // Etapa 1: tras el corte, la conexion se establece.
        val conectado = esperarHasta(10_000L) {
            eventos.conexion.value is EventosServidor.Conexion.Conectado
        }
        assertTrue("la conexion SSE se establecio tras el corte", conectado)
        assertTrue("se reconecto al menos una vez (aperturas=$llamadas)", llamadas >= 2)

        // Etapa 2: los eventos del stream bueno mueven el turno hasta el fin.
        val terminado = esperarHasta(10_000L) {
            sync.turno.value is EstadoTurno.Terminado
        }
        eventos.detener()
        sync.cerrar()

        assertTrue(
            "el turno termino tras reconectar (turno=${sync.turno.value} estado=${sync.estado.value})",
            terminado
        )
        val ids = sync.mensajes.value.mapNotNull { it.info?.id }
        assertEquals("sin duplicados", ids.size, ids.toSet().size)
    }
}
