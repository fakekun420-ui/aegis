package com.aegis.hub.data

import com.aegis.hub.RootShell
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests JVM del lote unico de `getSkills` (F7, T-F7.1): un solo `su` con
 * delimitadores, skill sin SKILL.md ausente y contenido con delimitador.
 */
class SkillsLoteTest {

    private fun rutaCon(salida: String, codigo: Int = 0, contador: IntArray = IntArray(1)): RutaNativa {
        val fake: (String, Long) -> RootShell.Result = { _, _ ->
            contador[0]++
            RootShell.Result(codigo, salida, "")
        }
        return RutaNativa(shell = fake)
    }

    @Test
    fun `un solo su trae todos los skills`() = runBlocking {
        val llamadas = IntArray(1)
        val salida = "@@AEGIS_SKILL@@graphify\n# Graphify\n@@AEGIS_SKILL@@mem\n# Mem\n"
        val r = rutaCon(salida, contador = llamadas).getSkills(null)

        assertTrue(r.ok)
        assertEquals(1, llamadas[0])
        assertEquals(listOf("graphify", "mem"), r.data!!.skills.map { it.name })
    }

    @Test
    fun `el delimitador interno corta y la seccion vacia se descarta`() = runBlocking {
        val salida = "@@AEGIS_SKILL@@a\nuno\n@@AEGIS_SKILL@@linea\n@@AEGIS_SKILL@@b\ndos\n"
        val r = rutaCon(salida).getSkills(null)

        assertTrue(r.ok)
        assertEquals(listOf("a", "b"), r.data!!.skills.map { it.name })
    }

    @Test
    fun `si el su falla hay motivo y no lista`() = runBlocking {
        val r = rutaCon("", codigo = 1).getSkills(null)

        assertFalse(r.ok)
    }
}
