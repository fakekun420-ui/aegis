package com.aegis.hub.ui

import com.aegis.hub.data.Message
import com.aegis.hub.data.MessageInfo
import com.aegis.hub.data.MessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F3-P8.1: keys estables de `buildChatRows`.
 *
 * MEDIDO: el crash "Key was already used" ya mordio 2 veces (ChatScreen:581,1954).
 * La regla que lo evita es el fallback por INDICE (`msg_$index`), nunca por `hashCode()`:
 * dos data classes con igual contenido colisionaban y reventaban el LazyColumn.
 *
 * Estos tests llaman al codigo REAL (`ChatRow`/`buildChatRows` son `internal`, no
 * `private`, justo para esto). Cada afirmacion lleva su contraejemplo.
 */
class ChatRowsKeysTest {

    private fun texto(id: String?, role: String = "user", cuerpo: String = "hola"): Message =
        Message(info = MessageInfo(id = id, role = role), parts = listOf(MessagePart(type = "text", text = cuerpo)))

    private fun clavesDe(messages: List<Message>, turnFinished: Boolean): List<String> =
        buildChatRows(messages, turnFinished).map { it.key }

    @Test
    fun `los ids distintos dan keys distintas`() {
        // Los tres de usuario: ningun turno de asistente que cerrar (asistente + usuario
        // posterior SI anade Cierre por historia; ver el test dedicado mas abajo).
        val claves = clavesDe(listOf(texto("m1"), texto("m2"), texto("m3")), turnFinished = false)
        assertEquals("3 mensajes sin cierre -> 3 filas", 3, claves.size)
        assertEquals("sin duplicados", claves.size, claves.distinct().size)
        assertTrue("el id manda cuando existe", claves[0] == "m1" && claves[1] == "m2")
    }

    @Test
    fun `los mensajes sin id usan el indice y no colisionan`() {
        // Contraejemplo: con `hashCode()` dos "hola" identicos colisionaban. Con el
        // indice, dos mensajes sin id e igual contenido tienen keys distintas.
        val claves = clavesDe(listOf(texto(null), texto(null)), turnFinished = false)
        assertEquals(listOf("msg_0", "msg_1"), claves)
    }

    @Test
    fun `turnFinished apagado no anade divisor al ultimo asistente`() {
        val msgs = listOf(texto("u1"), texto("a1", "assistant"))
        val filas = buildChatRows(msgs, turnFinished = false)
        assertEquals("sin divisor", 2, filas.size)
        assertTrue("ninguna fila es Cierre", filas.none { it is ChatRow.Cierre })
    }

    @Test
    fun `turnFinished encendido cierra el ultimo asistente con key estable`() {
        val msgs = listOf(texto("u1"), texto("a1", "assistant"))
        val filas = buildChatRows(msgs, turnFinished = true)
        assertEquals("mensaje + divisor", 3, filas.size)
        val cierre = filas.last()
        assertTrue("la ultima fila es Cierre", cierre is ChatRow.Cierre)
        assertEquals("turn_finished_a1", cierre.key)
        assertEquals("keys unicas con divisor", 3, filas.map { it.key }.distinct().size)
    }

    @Test
    fun `un turno cerrado por historia lleva divisor aunque turnFinished sea falso`() {
        // Asistente seguido de usuario: ese turno esta cerrado, el divisor se queda.
        val msgs = listOf(texto("a1", "assistant"), texto("u2"))
        val filas = buildChatRows(msgs, turnFinished = false)
        assertEquals(3, filas.size)
        assertTrue(filas[1] is ChatRow.Cierre)
        assertEquals("turn_finished_a1", filas[1].key)
    }

    @Test
    fun `ids duplicados colisionan a proposito`() {
        // Contrato fijado, no bug nuevo: `mergeTail` clava por id en un HashMap, asi que
        // al refresco nunca llegan dos ids iguales. Si esta asercion cae, el duplicado
        // viene de aguas arriba y el LazyColumn crasheara con "Key was already used".
        val claves = clavesDe(listOf(texto("dup"), texto("dup")), turnFinished = false)
        assertEquals("el contrato exige ids unicos: dos iguales dan la misma key", claves[0], claves[1])
    }
}
