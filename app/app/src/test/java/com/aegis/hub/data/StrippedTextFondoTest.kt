package com.aegis.hub.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * F3-P8.4: `strippedText()` fuera del hilo principal.
 *
 * MEDIDO 2026-10-06 (ANR con traza): 200 mensajes x 12 regex compiladas en CADA llamada,
 * cada 2 s de poll, en el hilo principal (>5 s = ANR). Las 12 regex ya estan
 * precompiladas (ModelosChat:166-186); lo que fija este test es el PATRON de uso: el
 * pelado de un ciclo entero corre en `Dispatchers.Default` y da el mismo resultado que
 * en el hilo que sea, sin tocar el main.
 */
class StrippedTextFondoTest {

    private fun mensajeRuido(i: Int): Message = Message(
        info = MessageInfo(id = "m$i", role = "assistant"),
        parts = listOf(
            MessagePart(
                type = "text",
                text = "<SYSTEM_CONTEXT>ruido $i</SYSTEM_CONTEXT>\n<USER_REQUEST>  pedido $i </USER_REQUEST>"
            )
        )
    )

    @Test
    fun `200 mensajes se pelan en Default con resultado correcto`() = runTest {
        val mensajes = List(200) { mensajeRuido(it) }
        var hilo = ""
        val pelados = withContext(Dispatchers.Default) {
            hilo = Thread.currentThread().name
            mensajes.map { it.strippedText() }
        }
        assertFalse("no corre en el main: $hilo", hilo.equals("main", ignoreCase = true))
        assertEquals(200, pelados.size)
        assertEquals("el USER_REQUEST manda y se recorta", "pedido 0", pelados[0])
        assertEquals("pedido 199", pelados[199])
        assertFalse("sin restos de contexto", pelados.any { it.contains("SYSTEM_CONTEXT") })
    }

    @Test
    fun `el pelado es determinista entre hilos`() = runTest {
        val msg = mensajeRuido(7)
        val enDefault = withContext(Dispatchers.Default) { msg.strippedText() }
        assertEquals("pedido 7", enDefault)
        assertEquals(enDefault, msg.strippedText())
    }
}
