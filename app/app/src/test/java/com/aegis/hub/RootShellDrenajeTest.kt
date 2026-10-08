package com.aegis.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InterruptedIOException
import java.io.Reader

/**
 * MEDIDO 2026-10-08 en el movil: `RootShell.exec` moria con FATAL EXCEPTION en
 * el arranque (`InterruptedIOException: read interrupted by close() on another
 * thread` en los hilos lectores) porque el drenaje no atrapaba nada. `drenar`
 * es best-effort: lo leido se conserva y el corte es EOF, nunca FATAL.
 */
class RootShellDrenajeTest {

    /** Lector que entrega [texto] de una vez y luego simula el cierre ajeno. */
    private class LectorQueCorta(private val texto: String) : Reader() {
        private var entregado = false
        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            if (entregado) throw InterruptedIOException("corte simulado")
            val n = minOf(len, texto.length)
            texto.toCharArray(cbuf, off, off + n)
            entregado = true
            return n
        }
        override fun close() {}
    }

    @Test
    fun `corte a mitad conserva lo leido y no lanza`() {
        val destino = StringBuilder()
        RootShell.drenar(BufferedReader(LectorQueCorta("uno\ndos\n")), destino)
        assertEquals("uno\ndos\n", destino.toString())
    }

    @Test
    fun `flujo completo se drena entero`() {
        val destino = StringBuilder()
        RootShell.drenar(BufferedReader("abc\n".reader()), destino)
        assertTrue(destino.toString().contains("abc"))
    }
}
