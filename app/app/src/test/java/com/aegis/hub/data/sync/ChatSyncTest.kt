package com.aegis.hub.data.sync

import com.aegis.hub.data.Message
import com.aegis.hub.data.MessageInfo
import com.aegis.hub.data.MessagePart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests JVM para [ChatSync] (F5, T-F5.2).
 *
 * Flujo falso (lineas + conexion controlables) y colas en memoria. Con
 * `UnconfinedTestDispatcher` todo corre con ansia hasta suspension: sin relojes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSyncTest {

    private class FakeFlujo : FlujoEventos {
        val lineasFalsas = MutableSharedFlow<String>(extraBufferCapacity = 64)
        val conexionFalsa =
            MutableStateFlow<EventosServidor.Conexion>(EventosServidor.Conexion.Conectado)
        override val lineas = lineasFalsas
        override val conexion = conexionFalsa
    }

    private fun msg(id: String, texto: String, rol: String = "user") = Message(
        info = MessageInfo(id = id, role = rol),
        parts = listOf(MessagePart(type = "text", text = texto))
    )

    private fun lineasFixture(nombre: String): List<String> =
        javaClass.classLoader!!.getResourceAsStream("eventos/$nombre")!!
            .bufferedReader().readLines().filter { it.isNotBlank() }

    private fun otroSid(tipo: String): String =
        """data: {"id":"evt_x","type":"$tipo","data":{"sessionID":"ses_otro","delta":"x"}}"""

    @Test
    fun `evento de otra sesion no altera el estado`() {
        val flujo = FakeFlujo()
        val colas = mutableMapOf("ses_b" to listOf(msg("m1", "hola")))
        val sync = ChatSync(
            CoroutineScope(UnconfinedTestDispatcher()),
            flujo,
            leerCola = { sid -> colas[sid].orEmpty() }
        )
        sync.abrir("ses_b")

        flujo.lineasFalsas.tryEmit(otroSid("session.text.delta"))

        assertNull(sync.textoEnVivo.value)
        assertEquals(EstadoTurno.Ocioso, sync.turno.value)
        assertEquals(listOf("m1"), sync.mensajes.value.map { it.info?.id })
    }

    @Test
    fun `cambiar de chat descarta la respuesta tardia`() {
        val flujo = FakeFlujo()
        val sync = ChatSync(
            CoroutineScope(UnconfinedTestDispatcher()),
            flujo,
            leerCola = { emptyList() }
        )
        sync.abrir("A")
        sync.abrir("B")

        flujo.lineasFalsas.tryEmit(otroSid("session.text.delta").replace("ses_otro", "A"))

        assertNull("la linea tardia de A no pinta nada en B", sync.textoEnVivo.value)
        assertEquals(EstadoTurno.Ocioso, sync.turno.value)
    }

    @Test
    fun `optimista mas eco es un mensaje no dos`() {
        val flujo = FakeFlujo()
        val eco = listOf(msg("msg_1", "hola"))
        val sync = ChatSync(
            CoroutineScope(UnconfinedTestDispatcher()),
            flujo,
            leerCola = { eco }
        )
        sync.abrir("ses_x")
        sync.insertarOptimista(msg("local_1", "hola"))

        kotlinx.coroutines.runBlocking { sync.refrescarAhora() }

        assertEquals(listOf("msg_1"), sync.mensajes.value.map { it.info?.id })
    }

    @Test
    fun `caida pasa a respaldo sin perder y al volver reconcilia`() {
        val flujo = FakeFlujo()
        var lecturas = 0
        val sync = ChatSync(
            CoroutineScope(UnconfinedTestDispatcher()),
            flujo,
            leerCola = { lecturas++; listOf(msg("m1", "hola")) }
        )
        sync.abrir("ses_x")
        assertEquals(ChatSync.EstadoSync.PorEventos, sync.estado.value)

        flujo.conexionFalsa.value = EventosServidor.Conexion.Reconectando
        assertEquals(ChatSync.EstadoSync.Respaldo, sync.estado.value)
        assertEquals(listOf("m1"), sync.mensajes.value.map { it.info?.id })

        flujo.conexionFalsa.value = EventosServidor.Conexion.Conectado
        assertEquals(ChatSync.EstadoSync.PorEventos, sync.estado.value)
        assertTrue("al reconectar reconcilia", lecturas >= 2)
    }

    @Test
    fun `texto en vivo se pinta y al terminar reconcilia la cola`() {
        val flujo = FakeFlujo()
        val llamadas = mutableListOf<Int>()
        val sync = ChatSync(
            CoroutineScope(UnconfinedTestDispatcher()),
            flujo,
            leerCola = {
                llamadas.add(1)
                if (llamadas.size == 1) emptyList() else listOf(msg("msg_9", "TEXTO", "assistant"))
            }
        )
        sync.abrir("ses_test")

        for (linea in lineasFixture("turno-texto.ndjson")) {
            flujo.lineasFalsas.tryEmit(linea)
        }

        assertNull("tras ended no queda texto en vivo", sync.textoEnVivo.value)
        assertEquals(EstadoTurno.Ocupado, sync.turno.value)
        assertEquals(listOf("msg_9"), sync.mensajes.value.map { it.info?.id })
    }
}
