package com.aegis.hub.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests unitarios JVM para [EventStream] y [EventStreamParser].
 * Cada afirmación cuenta con su contraejemplo explícito:
 * 1. Filtrado por sesión: evento de sesión A aceptado vs evento de sesión B descartado.
 * 2. Robustez de stream: líneas de comentario o malformadas se ignoran sin romper el flujo.
 * 3. Detección de huecos: salto de seq > 1 reporta SequenceGapDetected vs salto secuencial normal seq == prev + 1.
 * 4. Estrategia anti-duplicación: text.delta incremental y text.ended consolidado NO duplican el texto acumulado.
 */
class EventStreamTest {

    private lateinit var parser: EventStreamParser

    @Before
    fun setUp() {
        parser = EventStreamParser()
    }

    // =========================================================================
    // 1. Filtrado de sesión (Afirmación y Contraejemplo)
    // =========================================================================

    @Test
    fun `evento text delta de la sesion A se acepta`() {
        val rawLine = """data: {"type":"session.text.delta","data":{"sessionID":"ses_A","delta":"Hola"}}"""
        val items = parser.processLine(rawLine, targetSessionId = "ses_A")

        assertEquals(2, items.size) // 1 Event + 1 TextUpdate
        val textUpdate = items.filterIsInstance<OpenCodeStreamItem.TextUpdate>().firstOrNull()
        assertNotNull(textUpdate)
        assertEquals("ses_A", textUpdate?.sessionID)
        assertEquals("Hola", textUpdate?.textAccumulated)
        assertFalse(textUpdate?.isEnded ?: true)
    }

    @Test
    fun `contraejemplo - evento text delta de la sesion B se descarta y no cruza texto a sesion A`() {
        val rawLine = """data: {"type":"session.text.delta","data":{"sessionID":"ses_B","delta":"Texto secreto de B"}}"""
        val items = parser.processLine(rawLine, targetSessionId = "ses_A")

        assertTrue(items.isEmpty())
        assertEquals("", parser.getAccumulatedText())
    }

    // =========================================================================
    // 2. Líneas malformadas y comentarios SSE (Afirmación y Contraejemplo)
    // =========================================================================

    @Test
    fun `comentario keepalive o linea vacia se ignoran y no rompen el stream`() {
        val stream = """
            : keepalive
            
            : ping 1234
            data: {"type":"session.text.delta","data":{"sessionID":"ses_A","delta":"A"}}
        """.trimIndent()

        val items = parser.processRawStream(stream, targetSessionId = "ses_A")

        assertEquals(2, items.size)
        val textUpdate = items.filterIsInstance<OpenCodeStreamItem.TextUpdate>().firstOrNull()
        assertEquals("A", textUpdate?.textAccumulated)
    }

    @Test
    fun `linea data malformada se ignora de forma segura y el stream continua procesando`() {
        val stream = """
            data: { ESTO NO ES JSON VALIDO }
            data: {"type":"session.text.delta","data":{"sessionID":"ses_A","delta":"OK"}}
        """.trimIndent()

        val items = parser.processRawStream(stream, targetSessionId = "ses_A")

        assertEquals(2, items.size)
        val textUpdate = items.filterIsInstance<OpenCodeStreamItem.TextUpdate>().firstOrNull()
        assertEquals("OK", textUpdate?.textAccumulated)
    }

    // =========================================================================
    // 3. Detección de huecos de secuencia seq (Afirmación y Contraejemplo)
    // =========================================================================

    @Test
    fun `salto de seq mayor que 1 en hito durable se detecta y reporta SequenceGapDetected`() {
        // Hito inicial seq = 10
        val line1 = """data: {"type":"session.step.started","durable":{"aggregateID":"agg_1","seq":10},"data":{"sessionID":"ses_A"}}"""
        val items1 = parser.processLine(line1, targetSessionId = "ses_A")
        assertEquals(1, items1.size)
        assertEquals(10L, parser.getLastDurableSeq())

        // Salto a seq = 15 (se perdieron deltas e hitos 11..14)
        val line2 = """data: {"type":"session.step.ended","durable":{"aggregateID":"agg_1","seq":15},"data":{"sessionID":"ses_A"}}"""
        val items2 = parser.processLine(line2, targetSessionId = "ses_A")

        assertEquals(2, items2.size) // SequenceGapDetected + Event
        val gap = items2.filterIsInstance<OpenCodeStreamItem.SequenceGapDetected>().firstOrNull()
        assertNotNull("Debe detectarse el hueco de secuencia", gap)
        assertEquals("ses_A", gap?.sessionID)
        assertEquals(11L, gap?.expectedSeq)
        assertEquals(15L, gap?.actualSeq)
        assertEquals(15L, parser.getLastDurableSeq())
    }

    @Test
    fun `contraejemplo - avance secuencial normal de seq no genera SequenceGapDetected`() {
        val line1 = """data: {"type":"session.step.started","durable":{"aggregateID":"agg_1","seq":1},"data":{"sessionID":"ses_A"}}"""
        val line2 = """data: {"type":"session.step.ended","durable":{"aggregateID":"agg_1","seq":2},"data":{"sessionID":"ses_A"}}"""

        val items1 = parser.processLine(line1, targetSessionId = "ses_A")
        val items2 = parser.processLine(line2, targetSessionId = "ses_A")

        assertTrue(items1.none { it is OpenCodeStreamItem.SequenceGapDetected })
        assertTrue(items2.none { it is OpenCodeStreamItem.SequenceGapDetected })
        assertEquals(2L, parser.getLastDurableSeq())
    }

    // =========================================================================
    // 4. Estrategia anti-duplicación delta vs ended (Afirmación y Contraejemplo)
    // =========================================================================

    @Test
    fun `estrategia de texto acumulado NO duplica cuando llega delta y luego ended con el mismo contenido`() {
        // Delta 1: "Hola "
        val l1 = """data: {"type":"session.text.delta","data":{"sessionID":"ses_A","delta":"Hola "}}"""
        // Delta 2: "mundo"
        val l2 = """data: {"type":"session.text.delta","data":{"sessionID":"ses_A","delta":"mundo"}}"""
        // Ended: texto consolidado completo "Hola mundo"
        val l3 = """data: {"type":"session.text.ended","data":{"sessionID":"ses_A","text":"Hola mundo"}}"""

        parser.processLine(l1, targetSessionId = "ses_A")
        parser.processLine(l2, targetSessionId = "ses_A")
        val items3 = parser.processLine(l3, targetSessionId = "ses_A")

        val endUpdate = items3.filterIsInstance<OpenCodeStreamItem.TextUpdate>().firstOrNull()
        assertNotNull(endUpdate)
        assertTrue(endUpdate?.isEnded ?: false)
        // VERIFICACIÓN CLAVE: El texto final es exactamente "Hola mundo", NO "Hola mundoHola mundo"
        assertEquals("Hola mundo", endUpdate?.textAccumulated)
        assertEquals("Hola mundo", parser.getAccumulatedText())
    }

    @Test
    fun `contraejemplo - si ended trae correccion canonica el acumulador se sincroniza fielmente sin duplicar`() {
        val l1 = """data: {"type":"session.text.delta","data":{"sessionID":"ses_A","delta":"Respuesta parcial"}}"""
        val l2 = """data: {"type":"session.text.ended","data":{"sessionID":"ses_A","text":"Respuesta parcial consolidada"}}"""

        parser.processLine(l1, targetSessionId = "ses_A")
        val items2 = parser.processLine(l2, targetSessionId = "ses_A")

        val endUpdate = items2.filterIsInstance<OpenCodeStreamItem.TextUpdate>().firstOrNull()
        assertEquals("Respuesta parcial consolidada", endUpdate?.textAccumulated)
        assertEquals("Respuesta parcial consolidada", parser.getAccumulatedText())
    }
}
