package com.aegis.hub.data

/**
 * Marcas de ciclo de vida para medir sin tocar la pantalla (G1).
 *
 * La app ya cuenta peticiones (`TraceoPeticiones`, solo debug); aqui solo se
 * ponen mojones con nombre para que `tools/extraer_metricas.py` corte ventanas
 * (abrir chat, enviar, skills, reposo) y cuente lo que paso dentro.
 *
 * Reglas:
 * - Todo lo que se testea en JVM es puro (`linea`, deltas sobre fotos).
 * - `emitir` no hace nada si `TraceoPeticiones.activo` es `false` (release):
 *   coste cero y ningun log en produccion.
 * - El formato es estable y versionado con ejemplo en
 *   `tools/ejemplo-logcat-metricas.txt`: si cambia, cambian los dos.
 */
object MarcasCiclo {

    const val ETIQUETA = "AegisMark"

    /**
     * Una linea de marca. Pura y testeada.
     * `extras` en orden alfabetico para que el extractor no dependa del orden.
     */
    fun linea(evento: String, sid: String, extras: Map<String, Long> = emptyMap()): String {
        val cola = extras.toSortedMap().entries.joinToString(" ") { (k, v) -> "$k=$v" }
        return if (cola.isEmpty()) "$ETIQUETA: $evento $sid"
        else "$ETIQUETA: $evento $sid $cola"
    }

    /** Peticiones http + `su` acumulados desde [foto]. Puro. */
    fun totalDesde(foto: FotoVentana): Long =
        (TraceoPeticiones.total() - foto.http) + (TraceoPeticiones.susTotales() - foto.su)

    /** Suma http+su de una foto (para "su=<n>" atado a ventana). Pura. */
    fun susDesde(foto: FotoVentana): Long = TraceoPeticiones.susTotales() - foto.su

    data class FotoVentana(val http: Long, val su: Long)

    fun foto(): FotoVentana = FotoVentana(TraceoPeticiones.total(), TraceoPeticiones.susTotales())

    private fun emitir(sid: String, texto: String) {
        if (sid.isBlank() || !TraceoPeticiones.activo) return
        try {
            android.util.Log.i(ETIQUETA, texto.removePrefix("$ETIQUETA: "))
        } catch (_: Exception) {
            // Marcar nunca puede romper el flujo real.
        }
    }

    /** Devuelve la foto para el `fin` correspondiente. Sin sesion no marca. */
    fun abrirInicio(sid: String): FotoVentana {
        val f = foto()
        if (sid.isNotBlank()) emitir(sid, linea("chat.abrir:inicio", sid))
        return f
    }

    fun abrirFin(sid: String, desde: FotoVentana) {
        if (sid.isBlank()) return
        emitir(sid, linea("chat.abrir:fin", sid, mapOf("peticiones" to totalDesde(desde))))
    }

    fun enviarInicio(sid: String): FotoVentana {
        val f = foto()
        if (sid.isNotBlank()) emitir(sid, linea("chat.enviar:inicio", sid))
        return f
    }

    fun enviarFin(sid: String, desde: FotoVentana) {
        if (sid.isBlank()) return
        emitir(sid, linea("chat.enviar:fin", sid, mapOf("peticiones" to totalDesde(desde))))
    }

    fun skillsInicio(): FotoVentana = foto().also {
        if (TraceoPeticiones.activo) {
            try {
                android.util.Log.i(ETIQUETA, "skills.abrir:inicio")
            } catch (_: Exception) {
            }
        }
    }

    fun skillsFin(desde: FotoVentana) {
        if (!TraceoPeticiones.activo) return
        try {
            android.util.Log.i(
                ETIQUETA,
                "skills.abrir:fin su=${susDesde(desde)} peticiones=${totalDesde(desde)}"
            )
        } catch (_: Exception) {
        }
    }

    /** Linea de reposo (el ticker decide cuando). Pura salvo emision. */
    fun reposo(sid: String, desde: FotoVentana): FotoVentana {
        val f = foto()
        if (sid.isNotBlank()) {
            emitir(sid, linea("reposo:60s", sid, mapOf("peticiones" to (f.http - desde.http))))
        }
        return f
    }
}
