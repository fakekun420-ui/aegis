package com.aegis.hub.data.flujo

import com.aegis.hub.RootShell
import com.aegis.hub.data.RutaNativa
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Flujo skills (F10): la pantalla carga con pocos `su` (lote + cache).
 */
class FlujoSkillsTest {

    @Test
    fun `pantalla de skills con 2 su la primera vez y 0 despues`() = runBlocking {
        val llamadas = IntArray(1)
        val fake: (String, Long) -> RootShell.Result = { cmd, _ ->
            llamadas[0]++
            if (cmd.contains("@@AEGIS_SKILL@@")) {
                RootShell.Result(0, "@@AEGIS_SKILL@@a\n# A\n", "")
            } else {
                RootShell.Result(0, "a\n", "")
            }
        }
        val api = RutaNativa(shell = fake)

        api.getSkills(null)
        api.getSystemSkills()
        assertEquals(2, llamadas[0])
        api.getSkills(null)
        api.getSystemSkills()
        assertEquals("la segunda vuelta sale de cache", 2, llamadas[0])
    }
}
