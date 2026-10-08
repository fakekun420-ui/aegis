package com.aegis.hub.data.sync

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests JVM de [avanzarTurno] reproduciendo los `.ndjson` capturados (F5, T-F5.0).
 *
 * `turno-texto.ndjson` y `turno-tools.ndjson`: captura real redactada (ventana
 * 2026-10-07, tipos `session.text.*`, `session.step.*`, `session.tool.*`).
 * `turno-ejecucion.ndjson`: forma ADR-003 (el servidor no la emitio en la ventana).
 */
class EstadoTurnoTest {

    private fun tipos(nombre: String): List<Pair<String, Long?>> {
        val txt = javaClass.classLoader!!.getResourceAsStream("eventos/$nombre")!!
            .bufferedReader().readText()
        return txt.lines().filter { it.isNotBlank() }.map { linea ->
            val e = JsonParser.parseString(linea.removePrefix("data:").trim()).asJsonObject
            val tipo = e.get("type").asString
            val seq = e.getAsJsonObject("durable")?.get("seq")?.asLong
            tipo to seq
        }
    }

    private fun secuencia(nombre: String): List<EstadoTurno> {
        var turno: EstadoTurno = EstadoTurno.Ocioso
        return tipos(nombre).map { (tipo, seq) ->
            turno = avanzarTurno(turno, tipo, seq)
            turno
        }
    }

    @Test
    fun `texto reproducido ocupa al empezar y NO termina al cerrar el segmento`() {
        val seq = secuencia("turno-texto.ndjson")
        assertTrue(seq.isNotEmpty())
        assertEquals(EstadoTurno.Ocupado, seq.first())
        // H-10: el fin del segmento NO es fin de turno (tras el texto puede venir bash).
        assertTrue(seq.last() is EstadoTurno.Ocupado)
    }

    @Test
    fun `herramientas reproducidas ocupan al empezar y nunca terminan solas`() {
        var turno: EstadoTurno = EstadoTurno.Ocioso
        var huboOcupado = false
        var huboTerminado = false
        for ((tipo, seq) in tipos("turno-tools.ndjson")) {
            turno = avanzarTurno(turno, tipo, seq)
            if (turno is EstadoTurno.Ocupado) huboOcupado = true
            if (turno is EstadoTurno.Terminado) huboTerminado = true
        }
        // Los inicios (step/tool) ocupan; los fines (success/ended) no terminan:
        // el fin de un paso no es fin de turno (H-10).
        assertTrue(huboOcupado)
        assertTrue(!huboTerminado)
    }

    @Test
    fun `ejecucion con started ocupa y con succeeded termina con su seq`() {
        val seq = secuencia("turno-ejecucion.ndjson")
        assertEquals(
            listOf(EstadoTurno.Ocupado, EstadoTurno.Ocupado, EstadoTurno.Terminado(101L)),
            seq
        )
    }

    @Test
    fun `tipos desconocidos no mueven el turno`() {
        assertEquals(EstadoTurno.Ocioso, avanzarTurno(EstadoTurno.Ocioso, "server.connected", null))
        assertEquals(
            EstadoTurno.Ocupado,
            avanzarTurno(EstadoTurno.Ocupado, "session.usage.updated", null)
        )
    }
}
