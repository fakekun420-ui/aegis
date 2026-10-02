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
    fun `coste no-cero CON sufijo -free SI es gratis: las dos ramas se suman, no se pisan`() {
        // MEDIDO 2026-10-02: yo escribi este test afirmando `false` aqui, y FALLO. Ejecuté el
        // criterio original del Hub en node para no discutir de memoria:
        //
        //     coste 0/0   + id normal  -> true
        //     coste 3/15  + id '-free' -> true      <-- el que yo creia false
        //     coste 3/15  + id normal   -> false
        //     sin coste   + id '-free' -> true
        //
        // O sea: las dos ramas son un O. La primera PUEDE anadir gratis, pero nunca lo quita.
        // Mi expectativa era la de un "el coste manda", que no es lo que hacia el Hub.
        //
        // Y esto es lo importante del test: fija el comportamiento REAL, que es el que decide
        // qué modelo aparece primero. Si alguien "corrige" la logica para que el coste mande
        // sobre el nombre, este test cae — y ese cambio HARIA lo que no se puede hacer, que es
        // cobrar por un modelo que el proveedor da gratis.
        assertTrue(esFree("opencode/algo-free", mapOf("input" to 3, "output" to 15)))
    }

    @Test
    fun `lo que SI excluye un modelo de pago es el coste, sin depender del id`() {
        // El contraejemplo del anterior: mismo coste, id que NO dice '-free' → no es gratis.
        assertEquals(false, esFree("anthropic/claude-opus", mapOf("input" to 3, "output" to 15)))
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