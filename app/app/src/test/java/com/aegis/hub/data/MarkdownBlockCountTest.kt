/* ==================================================================================
 * F4 — tests JVM de markdownBlockCount (MarkdownText.kt): conteo de bloques.
 *
 * El funcion parseMarkdown agrupa el texto markdown en bloques (Header, CodeBlock,
 * ToolExecution, Quote, BulletList, OrderedList, Paragraph). markdownBlockCount
 * devuelve el numero total de bloques. La UI usa esto para decidir si muestra "ver todo".
 * ================================================================================== */

package com.aegis.hub.data

import com.aegis.hub.ui.markdownBlockCount
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownBlockCountTest {

    /** Texto vacío → 0 bloques. */
    @Test
    fun `cadena vacía devuelve 0 bloques`() {
        assertEquals(0, markdownBlockCount(""))
    }

    /** Texto plano (sin markdown) → 1 bloque Paragraph. */
    @Test
    fun `texto plano sin markdown devuelve 1 bloque`() {
        assertEquals(1, markdownBlockCount("Hola, este es texto plano"))
    }

    /** Markdown con una lista → 1 bloque BulletList. */
    @Test
    fun `markdown con lista simple devuelve 1 bloque`() {
        val text = "- item 1\n- item 2"
        assertEquals(1, markdownBlockCount(text))
    }

    /** Markdown con varias listas → 2 bloques (2 BulletList). */
    @Test
    fun `markdown con dos listas separadas devuelve 2 bloques`() {
        val text = "- item 1\n- item 2\n\n- item 3\n- item 4"
        assertEquals(2, markdownBlockCount(text))
    }

    /** Markdown con encabezado → 1 bloque Header. */
    @Test
    fun `markdown con encabezado devuelve 1 bloque Header`() {
        val text = "# Título"
        assertEquals(1, markdownBlockCount(text))
    }

    /** Markdown con código ``` → 1 bloque CodeBlock. */
    @Test
    fun `markdown con bloque de código devuelve 1 bloque CodeBlock`() {
        val text = "```\nconsole.log('hola')\n```"
        assertEquals(1, markdownBlockCount(text))
    }

    /** El caso crítico: UN bloque pero LARGO. Antes ponía "ver todo" sin nada más que ver.
     *  Con un bloque longo de texto normal, el count es 1, así que maxBlocks=1 no truncaría.
     *  El "ver todo" se muestra cuando blocks.size > maxBlocks, es decir, cuando hay más bloques
     *  de los que caben. Un bloque largo pero solo uno no oculta contenido.
     */
    @Test
    fun `un bloque largo devuelve 1 bloque (no oculta contenido)`() {
        val largo = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ".repeat(50)
        assertEquals(1, markdownBlockCount(largo))
    }

    /** Markdown con combinación: encabezado + párrafo + lista → 3 bloques. */
    @Test
    fun `combinación de header + párrafo + lista devuelve 3 bloques`() {
        val text = "# Título\n\nTexto de párrafo.\n\n- item 1\n- item 2"
        assertEquals(3, markdownBlockCount(text))
    }

    /** Texto con solo saltos de línea y vacío → 0 bloques después del flush. */
    @Test
    fun `solo saltos de línea devuelve 0 bloques`() {
        assertEquals(0, markdownBlockCount("\n\n\n"))
    }
}