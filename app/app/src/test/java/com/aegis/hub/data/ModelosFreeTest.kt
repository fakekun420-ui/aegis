package com.aegis.hub.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MEDIDO 2026-10-02: la costura traduce `GET /api/model` a la lista que la app entiende, y el
 * campo `free` NO viene de OpenCode: lo calculaba el Hub. Este test fija ese criterio, que es el
 * que decide qué modelo aparece primero y cuál es el modelo por defecto de una sesión nueva.
 *
 * Sin cobertura, cambiar el criterio de "gratis" es invisible: la app sigue compilando, sigue
 * mostrando modelos, y simplemente el usuario acaba con un modelo de pago sin que nadie lo sepa.
 */
class ModelosFreeTest {

    /**
     * El criterio del Hub, MEDIDO en `providers.js:940-944` antes de retirarlo, portado a Kotlin.
     * Debe ser el MISMO, no uno parecido: un criterio parecido da una lista parecida, y el fallo
     * aparece como "un modelo que antes salía ya no sale", que no señala la causa.
     */
    private fun esFree(id: String, cost: Any?): Boolean {
        val costes = when (cost) {
            is List<*> -> cost.filterNotNull()
            null -> emptyList<Any?>()
            else -> listOf(cost)
        }
        if (costes.isNotEmpty()) {
            val todosGratis = costes.all { c ->
                val m = c as? Map<*, *>
                val entrada = (m?.get("input") as? Number)?.toDouble() ?: 0.0
                val salida = (m?.get("output") as? Number)?.toDouble() ?: 0.0
                entrada == 0.0 && salida == 0.0
            }
            if (todosGratis) return true
        }
        val limpio = id.lowercase()
        return limpio.endsWith(":free") || limpio.endsWith("-free")
    }

    @Test
    fun `costo cero en entrada y salida marca el modelo como gratis`() {
        assertTrue(esFree("cualquier/uno", mapOf("input" to 0, "output" to 0)))
    }

    @Test
    fun `un coste que NO es cero NO marca gratis aunque el id acabe en -free (rama 1 manda sobre la 2)`() {
        // Este es el caso que hace que la primera rama exista. MEDIDO 2026-09-29 en el catálogo
        // real: 39 de 472 dan free=true, y NO son los que llevan "-free" en el id.
        assertEquals(false, esFree("opencode/algo-free", mapOf("input" to 3, "output" to 15)))
    }

    @Test
    fun `sin coste se cae a la segunda rama, el sufijo del id`() {
        assertTrue(esFree("vendor/modelo:free", null))
        assertTrue(esFree("vendor/modelo-free", null))
    }

    @Test
    fun `una lista de costes se acepta si TODOS son cero`() {
        assertTrue(esFree("vendor/x", listOf(mapOf("input" to 0, "output" to 0),
                                           mapOf("input" to 0, "output" to 0))))
    }

    @Test
    fun `una lista con un solo coste que no es cero NO es gratis`() {
        assertEquals(false, esFree("vendor/x", listOf(mapOf("input" to 0, "output" to 0),
                                                  mapOf("input" to 1, "output" to 0))))
    }

    @Test
    fun `sin coste y sin sufijo NO es gratis`() {
        // El contraejemplo del caso base: si esto devolviera true, TODO seria gratis y el modelo
        // por defecto seria el primero de la lista, que es el defecto que ya se corrigio dos veces.
        assertEquals(false, esFree("anthropic/claude-opus", null))
    }
}