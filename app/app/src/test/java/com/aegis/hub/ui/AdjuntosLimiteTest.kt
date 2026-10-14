package com.aegis.hub.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F3-P8.3: limite de 5 MB en adjuntos + truncado de texto a 60 000 caracteres.
 *
 * MEDIDO: una captura moderna (1080x2400x4 B ~= 10 MB) por imagen visible, retenida en
 * RAM por `remember` + el base64 original en `MessagePart`. `uriToAttachedFile` ya
 * limita a 5 MB; estos tests fijan la politica pura (sin Context, sin Android) para que
 * un cambio de numero no cuele un OOM.
 */
class AdjuntosLimiteTest {

    @Test
    fun `el limite es 5 MB exactos`() {
        assertEquals(5 * 1024 * 1024, LIMITE_ADJUNTO_BYTES)
        assertFalse("5 MB justos aun pasan", adjuntoExcedeLimite(ByteArray(5 * 1024 * 1024)))
        assertTrue("5 MB + 1 ya es solo metadatos", adjuntoExcedeLimite(ByteArray(5 * 1024 * 1024 + 1)))
        assertFalse("vacio no excede", adjuntoExcedeLimite(ByteArray(0)))
    }

    @Test
    fun `el texto se recorta a 60 000 caracteres`() {
        assertEquals(60_000, MAX_TEXTO_ADJUNTO_CHARS)
        val largo = "a".repeat(70_000).toByteArray(Charsets.UTF_8)
        val recorte = recortarTextoAdjunto(largo)
        assertEquals(60_000, recorte.length)
        assertEquals("a".repeat(60_000), recorte)
        val corto = "hola".toByteArray(Charsets.UTF_8)
        assertEquals("un texto corto pasa intacto", "hola", recortarTextoAdjunto(corto))
    }

    @Test
    fun `la clasificacion texto-binario no depende de la extension sola`() {
        assertTrue("text/plain manda", esTextoAdjunto("text/plain", "binario.dat"))
        assertTrue(".md es texto", esTextoAdjunto("application/octet-stream", "nota.md"))
        assertTrue(".txt es texto", esTextoAdjunto("application/octet-stream", "nota.txt"))
        assertTrue(".json es texto", esTextoAdjunto("application/octet-stream", "datos.json"))
        // Contraejemplo: un .png con mime de texto pasaria por el camino de texto.
        assertFalse("png es binario", esTextoAdjunto("image/png", "foto.png"))
        assertFalse("pdf es binario", esTextoAdjunto("application/pdf", "doc.pdf"))
    }

    @Test
    fun `el adjunto grande deja metadatos legibles, no el binario`() {
        val marca = describirAdjuntoGrande(6L * 1024 * 1024)
        assertTrue("dice que es demasiado grande: $marca", marca.contains("demasiado grande"))
        assertTrue("dice cuanto: $marca", marca.contains("MB"))
    }
}
