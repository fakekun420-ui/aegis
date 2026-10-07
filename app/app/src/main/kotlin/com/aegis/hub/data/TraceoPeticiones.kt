package com.aegis.hub.data

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Contador de peticiones HTTP hacia OpenCode, solo para builds debug (F0, T-F0.3).
 *
 * Cada 60 s emite a logcat una línea `AegisTrace: METODO ruta=conteo …` que sirve
 * de "antes" para los criterios E3/E4 del plan de estabilización.
 *
 * Diseño para que sea testeable en JVM puro:
 * - [normalizarRuta] y [resumen] son funciones puras sobre el estado del contador.
 * - El contador solo cuenta cuando [activo] es `true`. En release nunca se instala
 *   el interceptor, así que [activo] se queda en `false` y el coste es un `if`.
 */
object TraceoPeticiones {

    const val ETIQUETA = "AegisTrace"

    /** Puesto a `true` solo desde builds debug al instalar el interceptor. */
    @Volatile
    var activo: Boolean = false

    private val conteos = ConcurrentHashMap<String, AtomicLong>()

    /** `ses_…`, `msg_…`, `prt_…` → `:id`. Todo lo demás pasa tal cual. */
    private val RX_ID_SESION = Regex("^(ses|msg|prt)_.+$")

    fun normalizarRuta(ruta: String): String {
        if (!ruta.contains('/')) {
            return if (RX_ID_SESION.matches(ruta)) ":id" else ruta
        }
        return ruta.split('/').joinToString("/") { tramo ->
            if (RX_ID_SESION.matches(tramo)) ":id" else tramo
        }
    }

    fun registrar(metodo: String, ruta: String) {
        if (!activo) return
        val clave = "$metodo ${normalizarRuta(ruta)}"
        conteos.getOrPut(clave) { AtomicLong(0) }.incrementAndGet()
    }

    /** Resumen de una línea para logcat, o cadena vacía si no hubo peticiones. */
    fun resumen(): String {
        if (conteos.isEmpty()) return ""
        return conteos.entries
            .sortedBy { it.key }
            .joinToString(" ") { (clave, n) -> "$clave=${n.get()}" }
    }

    fun crearInterceptor(): Interceptor {
        return Interceptor { cadena ->
            val peticion = cadena.request()
            try {
                registrar(peticion.method, peticion.url.encodedPath)
            } catch (_: Exception) {
                // El traceo nunca puede romper una petición real.
            }
            cadena.proceed(peticion)
        }
    }

    private var informeArrancado = false

    /**
     * Arranca el informe periódico a logcat (una sola vez por proceso).
     * Llamar solo desde debug, después de poner [activo] a `true`.
     */
    @Synchronized
    fun iniciarInformePeriodico(cadaMs: Long = 60_000L) {
        if (informeArrancado) return
        informeArrancado = true
        thread(isDaemon = true, name = "aegis-trace") {
            while (true) {
                try {
                    Thread.sleep(cadaMs)
                } catch (_: InterruptedException) {
                    return@thread
                }
                val texto = resumen()
                if (texto.isNotEmpty()) {
                    Log.i(ETIQUETA, texto)
                }
            }
        }
    }

    /** Solo para tests: vacía el contador (no toca [activo]). */
    fun limpiar() {
        conteos.clear()
    }
}
