package com.aegis.hub.data

import com.aegis.hub.RootShell
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests JVM del lote unico de `getSystemHealth` (F7, T-F7.1): 1 `su` con
 * secciones, sin regex, y `getSystemSkills` con 1 `su`.
 */
class SaludLoteTest {

    private val SALIDA_SALUD = """
        123.45 678.90
        @@AEGIS_SALUD@@mem
        MemTotal:        8000000 kB
        MemFree:         1000000 kB
        MemAvailable:    6000000 kB
        @@AEGIS_SALUD@@ls
        7
        @@AEGIS_SALUD@@skills
        /x/skills/graphify/
        /x/skills/.oculto/
    """.trimIndent()

    private fun rutaCon(salida: String, llamadas: IntArray = IntArray(1)): RutaNativa {
        val fake: (String, Long) -> RootShell.Result = { _, _ ->
            llamadas[0]++
            RootShell.Result(0, salida, "")
        }
        return RutaNativa(shell = fake)
    }

    @Test
    fun `salud sale de un solo su y sin regex`() = runBlocking {
        val llamadas = IntArray(1)
        val r = rutaCon(SALIDA_SALUD, llamadas).getSystemHealth()

        assertTrue(r.isSuccessful)
        assertEquals(1, llamadas[0])
        val datos = r.body()!!.data!!
        assertEquals(123L, datos.uptime)
        assertEquals("1953MB", datos.memory?.heapUsed)
        assertEquals("7812MB", datos.memory?.heapTotal)
        assertEquals(7, datos.projects)
        assertEquals(listOf("graphify"), datos.skills.installed)
    }

    @Test
    fun `system skills sale de un solo su`() = runBlocking {
        val llamadas = IntArray(1)
        val r = rutaCon("graphify\nmem\n", llamadas).getSystemSkills()

        assertTrue(r.isSuccessful)
        assertEquals(1, llamadas[0])
        assertEquals(2, r.body()!!.data!!.installed.size)
    }
}
