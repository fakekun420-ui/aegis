package com.aegis.hub.ui.viewmodel

import com.aegis.hub.data.Message

/**
 * G4/F9: Helper puro para la logica de refresco y fusion de mensajes.
 */
object ChatRefreshHelper {

    const val REFRESH_MIN_WAIT_MS = 2_000L
    const val REFRESH_MAX_WAIT_MS = 30_000L
    const val SERVIDOR_FAIL_STREAK_FOR_DEGRADED = 3
    const val TAIL_POLL = 200

    /**
     * Fusiona la cola de mensajes con los mensajes actuales sin duplicar por ID
     * y preservando el orden ascendente.
     */
    fun mergeTail(actual: List<Message>, cola: List<Message>): List<Message> {
        if (cola.isEmpty()) return actual
        val porId = HashMap<String, Message>()
        cola.forEach { m -> m.info?.id?.let { porId[it] = m } }
        if (porId.isEmpty()) return actual
        val vistos = HashSet<String>()
        val fusion = ArrayList<Message>(actual.size + cola.size)
        for (m in actual) {
            val id = m.info?.id
            if (id != null && porId.containsKey(id)) {
                fusion.add(porId.getValue(id))
                vistos.add(id)
            } else {
                fusion.add(m)
            }
        }
        for (m in cola) {
            val id = m.info?.id ?: continue
            if (id !in vistos) {
                fusion.add(m)
                vistos.add(id)
            }
        }
        return fusion
    }

    /**
     * F3-P8.2: politica de espera del refresco.
     */
    fun esperaRefrescoMs(fallosSeguidos: Int): Long =
        if (fallosSeguidos >= SERVIDOR_FAIL_STREAK_FOR_DEGRADED) REFRESH_MAX_WAIT_MS else REFRESH_MIN_WAIT_MS

    /**
     * F3-P8.2: guarda de propiedad del refresco.
     */
    fun puedeDetenerRefresco(ownerSessionId: String?, viewRefreshSessionId: String?): Boolean =
        ownerSessionId == null || ownerSessionId == viewRefreshSessionId
}
