package com.aegis.hub.ui

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Tablas de markdown. MEDIDO 2026-10-01: `parseMarkdown` NO tenia ningun tipo de tabla.
 *
 * Los ocho tipos que sabia reconocer eran Header, CodeBlock, ToolExecution, Quote,
 * BulletList, OrderedList y Paragraph. Una tabla caia en `Paragraph`, que pinta el texto tal
 * cual: los `|` salen en crudo y las columnas se leen superpuestas. Eso es lo que el usuario
 * describe al comparar con el CLI, donde la misma tabla sale alineada.
 *
 * `MdBlock` y el parser son `internal` para que este test llame al codigo REAL. Un test que
 * reimplementa la regla verifica que la reimplementacion es correcta, no que el parser lo sea.
 *
 * Cada afirmacion lleva su contraejemplo: si la regla se invirtiera, el test falla.
 */
class MarkdownTablaTest {

    private fun tablasDe(texto: String): List<MdBlock.Table> =
        parseMarkdown(texto).filterIsInstance<MdBlock.Table>()

    private val EJEMPLO = """
        | Herramienta | Estado | Notas |
        | --- | :---: | ---: |
        | bash | ok | sin cambios |
        | view_file | falló | ruta mala |
    """.trimIndent()

    @Test
    fun `una tabla bien formada es UN bloque con sus cabeceras y sus filas`() {
        val tablas = tablasDe(EJEMPLO)
        assertEquals("la tabla debe reconocerse como un unico bloque", 1, tablas.size)
        val t = tablas[0]
        assertEquals(listOf("Herramienta", "Estado", "Notas"), t.headers)
        assertEquals(2, t.rows.size)
        assertEquals(listOf("bash", "ok", "sin cambios"), t.rows[0])
        assertEquals(listOf("view_file", "falló", "ruta mala"), t.rows[1])
        // CONTRAEJEMPLO: sin el brazo de tabla estos tres campos serian null y habria un
        // Paragraph con los pipes dentro.
        assertTrue("las filas no pueden ir dentro del texto como parrafo", t.rows.isNotEmpty())
    }

    @Test
    fun `la alineacion de cada columna sale de la fila de separacion`() {
        val t = tablasDe(EJEMPLO).single()
        assertEquals(
            listOf(TableAlign.Left, TableAlign.Center, TableAlign.Right),
            t.align
        )
        // CONTRAEJEMPLO: `---` a secas es Left y `---:` es Right. Si la regla se invirtiera,
        // las columnas central y derecha saldrian intercambiadas.
    }

    @Test
    fun `una linea con pipes pero SIN fila de separacion no es una tabla`() {
        // El caso que mas importa para no romper texto normal: un comando de shell, o una
        // frase con una tuberia, mencionan `|` y no son una tabla.
        val texto = "El comando `a | b` no es una tabla."
        assertTrue("una tuberia suelta no puede volverse tabla", tablasDe(texto).isEmpty())
    }

    @Test
    fun `una fila con un solo pipe no es una tabla`() {
        // Un pipe suelto no alcanza para una tabla: hacen falta al menos dos celdas. Sin este
        // filtro, `grep foo | wc -l` como linea suelta se intentaria parsear como tabla.
        assertTrue("una sola pipe no es una tabla", tablasDe("grep foo | wc -l").isEmpty())
    }

    @Test
    fun `la tabla termina en la primera linea que no es suya`() {
        val texto = """
            | a | b |
            | --- | --- |
            | 1 | 2 |
            Fin de la tabla.

            Parrafo normal.
        """.trimIndent()
        val t = tablasDe(texto).single()
        assertEquals("solo las filas de datos de la tabla", 1, t.rows.size)
        assertEquals(listOf("1", "2"), t.rows[0])
        // CONTRAEJEMPLO: si el bucle no cortase en la linea en blanco, "Fin de la tabla." se
        // contaria como fila y las columnas se descuadrarian.
    }

    @Test
    fun `una tabla dentro de un bloque de codigo NO se parsea`() {
        val texto = """
            ```
            | a | b |
            | --- | --- |
            | 1 | 2 |
            ```
        """.trimIndent()
        assertTrue(
            "dentro de un fence de codigo todo es texto",
            tablasDe(texto).isEmpty()
        )
        assertTrue(
            "y debe salir como bloque de codigo",
            parseMarkdown(texto).any { it is MdBlock.CodeBlock }
        )
    }

    @Test
    fun `una fila con menos celdas se rellena a la anchura de la cabecera`() {
        // Una tabla con una celda vacia de mas es comun en salidas de herramientas. Si el
        // renderizador dibujara solo las celdas que hay, las columnas de al lado se moverian
        // de sitio en cada fila.
        val texto = """
            | a | b | c |
            | --- | --- | --- |
            | 1 | 2 |
            | 1 | 2 | 3 | 4 |
        """.trimIndent()
        val t = tablasDe(texto).single()
        assertEquals("la cabecera fija la anchura", 3, t.headers.size)
        assertEquals(listOf("1", "2", ""), t.rows[0])
        // CONTRAEJEMPLO: la fila sobrante se recorta a la cabecera. Si no, la fila tendria una
        // celda de mas y `repeat(headers.size)` la ocultaria sin avisar.
        assertEquals(3, t.rows[1].size)
    }

    @Test
    fun `el markdown que ya funcionaba sigue igual (sin regresion)` {
        val texto = """
            # Titulo

            Un parrafo normal.

            - uno
            - dos

            1. primero
            2. segundo

            > cita

            `codigo en linea`
        """.trimIndent()
        val bloques = parseMarkdown(texto)
        assertTrue(bloques.any { it is MdBlock.Header })
        assertTrue(bloques.any { it is MdBlock.Paragraph })
        assertTrue(bloques.any { it is MdBlock.BulletList })
        assertTrue(bloques.any { it is MdBlock.OrderedList })
        assertTrue(bloques.any { it is MdBlock.Quote })
        assertTrue("y nada se ha vuelto tabla", tablasDe(texto).isEmpty())
    }

    @Test
    fun `el recuento de bloques ve la tabla como un bloque, no como cinco lineas`() {
        // `markdownBlockCount` decide si hace falta un "ver todo". Si una tabla contase como
        // sus N lineas, una tabla de 40 filas desplegaria el texto entero.
        val texto = """
            | a | b |
            | --- | --- |
            | 1 | 2 |
            | 3 | 4 |
            | 5 | 6 |
        """.trimIndent()
        assertEquals("una tabla es UN bloque", 1, markdownBlockCount(texto))
    }
}