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
     * Union CRONOLOGICA de la cola del servidor (`cola`, ascendente) con lo que ya
     * hay en pantalla (`actual`).
     *
     * Contrato:
     * - NUNCA encoge la lista: todo id de `actual` sigue presente al terminar.
     * - NUNCA coloca mensajes viejos despues de los nuevos: el historial que solo
     *   existe en `actual` (mas antiguo que la ventana de la cola) se queda DELANTE.
     * - La cola deduplicada por id sustituye en su sitio a los mensajes ya presentes.
     * - Los mensajes de `actual` ausentes de la cola que caen dentro de la ventana
     *   (optimistas locales) se conservan al final, nunca se descartan.
     *
     * Regresion que esto cierra: la version anterior anexaba al final todo id de la
     * cola ausente de `actual`, asi que con carga inicial de 100 y poll de 200 los
     * ~100 mas antiguos de la cola se pintaban DESPUES de los recientes y las
     * respuestas nuevas quedaban enterradas al hacer scroll.
     */
    fun mergeTail(actual: List<Message>, cola: List<Message>): List<Message> {
        if (cola.isEmpty()) return actual
        // Dedupe de la cola por id: gana la ULTIMA version de cada id, pero se
        // respeta el orden ascendente original de la cola.
        val porId = HashMap<String, Message>()
        cola.forEach { m -> m.info?.id?.let { porId[it] = m } }
        if (porId.isEmpty()) return actual
        val vistos = HashSet<String>()
        val colaDedup = ArrayList<Message>(porId.size)
        for (m in cola) {
            val id = m.info?.id ?: continue
            if (vistos.add(id)) colaDedup.add(porId.getValue(id))
        }
        // Primer punto donde `actual` solapa con la ventana de la cola.
        val idxPrimerMatch = actual.indexOfFirst { a ->
            val id = a.info?.id
            id != null && porId.containsKey(id)
        }
        // Todo lo anterior al solape es historial mas viejo: se queda DELANTE.
        val antes: List<Message> = if (idxPrimerMatch >= 0) actual.subList(0, idxPrimerMatch) else actual
        // Lo posterior al solape solo sobrevive si no lo trae ya la cola (optimistas).
        val despues: List<Message> = if (idxPrimerMatch >= 0) {
            actual.drop(idxPrimerMatch).filter { a ->
                val id = a.info?.id
                id == null || !porId.containsKey(id)
            }
        } else {
            emptyList()
        }
        return antes + colaDedup + despues
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
