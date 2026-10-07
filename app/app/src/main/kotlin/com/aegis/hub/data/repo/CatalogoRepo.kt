package com.aegis.hub.data.repo

import android.util.Log
import com.aegis.hub.data.OpenCodeApi
import com.aegis.hub.data.OpenCodeNativeAgent
import com.aegis.hub.data.OpenCodeNativeModel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Catalogos de modelos y agentes con cache corta (F4).
 *
 * Sin esto cada `load()`, cada envio y cada creacion descargaba el catalogo entero
 * (~480 modelos). TTL 5 min + `Mutex`: dos llamadas simultaneas comparten UNA sola
 * peticion (la segunda reutiliza lo que lleno la primera dentro del lock).
 * `esGratis` se calcula UNA vez al cachear, no por llamada.
 */
class CatalogoRepo(
    private val oc: OpenCodeApi = OpenCodeApi.default,
    private val reloj: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        private const val TAG = "CatalogoRepo"
        const val TTL_MS = 5 * 60_000L
    }

    private val mutex = Mutex()
    private var cacheModelos: List<OpenCodeNativeModel>? = null
    private var cacheModelosMs = 0L
    private var gratisIds: Set<String> = emptySet()
    private var cacheAgentes: List<OpenCodeNativeAgent>? = null
    private var cacheAgentesMs = 0L

    suspend fun modelosNativos(): List<OpenCodeNativeModel> = mutex.withLock {
        val ahora = reloj()
        cacheModelos?.takeIf { ahora - cacheModelosMs < TTL_MS }?.let { return it }
        val frescos = oc.listModels().data.orEmpty()
        cacheModelos = frescos
        cacheModelosMs = ahora
        gratisIds = frescos
            .filter { esFree(it) }
            .flatMap { listOfNotNull(it.id, it.modelID) }
            .toSet()
        frescos
    }

    suspend fun agentesNativos(): List<OpenCodeNativeAgent> = mutex.withLock {
        val ahora = reloj()
        cacheAgentes?.takeIf { ahora - cacheAgentesMs < TTL_MS }?.let { return it }
        val frescos = oc.listAgents().data.orEmpty()
        cacheAgentes = frescos
        cacheAgentesMs = ahora
        frescos
    }

    /** Calculado al cachear: O(1) por llamada. */
    fun esGratis(idModelo: String?): Boolean = idModelo != null && gratisIds.contains(idModelo)

    /** Invalidacion manual (pantalla de modelos) o tras "modelo no encontrado". */
    fun invalidar() {
        cacheModelos = null
        cacheAgentes = null
        gratisIds = emptySet()
    }

    /**
     * Criterio del Hub (`providers.js:940-944`) portado literal: coste 0/0 O id
     * terminado en ":free"/"-free" (OR, no prioridad). MEDIDO 2026-10-02 en node:
     * coste 3/15 + id "-free" -> gratis igual. 39 de 472 daban `free=true` con esto,
     * y no son los del sufijo: hacen falta las dos ramas.
     */
    private fun esFree(m: OpenCodeNativeModel): Boolean {
        val costes = when (val c = m.cost) {
            is List<*> -> c.filterNotNull()
            null -> emptyList<Any?>()
            else -> listOf(c)
        }
        if (costes.isNotEmpty()) {
            val todosGratis = costes.all { c ->
                val mapa = c as? Map<*, *>
                val in0 = (mapa?.get("input") as? Number)?.toDouble() ?: 0.0
                val out0 = (mapa?.get("output") as? Number)?.toDouble() ?: 0.0
                in0 == 0.0 && out0 == 0.0
            }
            if (todosGratis) return true
        }
        val id = (m.id ?: m.modelID ?: "").lowercase()
        return id.endsWith(":free") || id.endsWith("-free")
    }
}
