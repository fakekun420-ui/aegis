package com.aegis.hub

import android.os.Looper
import com.aegis.hub.data.ShellQuote
import java.io.BufferedReader
import java.io.InputStreamReader

object RootShell {
    data class Result(val code:Int, val stdout:String, val stderr:String)

    /**
     * Escapa un argumento para sh con comillas simples: dentro de '...' todo es literal,
     * por lo que neutraliza `;`, `|`, `&`, `<`, `>`, backticks, `$`, `\n` y `\`.
     * El único carácter especial es la comilla simple, que se cierra y reabre con '\''.
     * La implementación NO se duplica aquí: delega en [ShellQuote.quote] (fuente única).
     */
    internal fun shQuote(arg: String): String = ShellQuote.quote(arg)

    /**
     * Donde vive `su` segun ROM/Magisk. MEDIDO 2026-10-10 en el POCO F3: el unico
     * `su` esta en `/debug_ramdisk/su`, fuera del PATH de la app (`/system/bin`,
     * `/system/xbin`, `/sbin` no existen). El `"su"` pelado nunca se resolvia y el
     * fallback mudo a `sh` escondia el fallo: Magisk jamas veia a la app (0 filas
     * en `policies`) y nada con root funcionaba (montajes, chroot).
     */
    internal val RUTAS_SU_ABSOLUTAS = listOf(
        "/debug_ramdisk/su",
        "/sbin/su",
        "/system/xbin/su",
        "/system/bin/su",
        "/su/bin/su"
    )

    /**
     * Resuelve el binario `su` sin pedir root (pura y testeable): primero rutas
     * absolutas que existan, luego `command -v su` con shell normal (sin root,
     * sin prompt de Magisk). `null` = no hay `su` alcanzable.
     */
    internal fun resolverSu(
        existeArchivo: (String) -> Boolean,
        cualEnPath: () -> String?
    ): String? {
        RUTAS_SU_ABSOLUTAS.firstOrNull { existeArchivo(it) }?.let { return it }
        return cualEnPath()?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun buscarSuEnPath(): String? = try {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "command -v su"))
        val salida = p.inputStream.bufferedReader().readText().trim()
        p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
        salida.ifBlank { null }
    } catch (_: Exception) {
        null
    }

    fun exec(cmd: String, timeoutMs: Long = 15000): Result {
        // F0 (T-F0.4): cazar llamadas desde el hilo principal en debug. El `main != null`
        // es intencionado: en tests JVM los stubs de android.jar devuelven null y el
        // chequeo debe quedar inerte ahí, no romper la suite.
        if (BuildConfig.DEBUG) {
            val principal = Looper.getMainLooper()
            if (principal != null && Looper.myLooper() === principal) {
                throw IllegalStateException(
                    "RootShell.exec en el hilo principal (cmd=${cmd.take(80)}). " +
                        "Todo `su` va a IO (regla 4 del plan de estabilización)."
                )
            }
        }
        // Sin `su` alcanzable no se finge root: antes se caia en mudo a `sh`
        // y todo lo privilegiado fallaba en silencio (montajes, chroot). Ahora
        // el motivo es honesto y Magisk pide permiso en el primer uso real.
        val binarioSu = resolverSu(
            existeArchivo = { java.io.File(it).canExecute() },
            cualEnPath = ::buscarSuEnPath
        ) ?: return Result(
            -1, "",
            "su no disponible: la app no tiene acceso root (concede Superusuario a com.aegis.hub en Magisk)"
        )
        return try {
            val p = Runtime.getRuntime().exec(arrayOf(binarioSu, "-c", cmd))
                val out = StringBuilder()
                val err = StringBuilder()
                // MEDIDO 2026-10-08 en el movil (FATAL x2 en arranque 1.2.0): si el
                // proceso muere o sus flujos se cierran mientras se drenan, el hilo
                // lector recibe InterruptedIOException ("read interrupted by close()
                // on another thread"). Sin atraparla, el hilo muere sin capturador
                // -> FATAL EXCEPTION y la app cae en el arranque. Drenar es
                // best-effort: lo ya leido se conserva, el cierre es EOF.
                val tOut = Thread {
                    drenar(BufferedReader(InputStreamReader(p.inputStream)), out)
                }
                val tErr = Thread {
                    drenar(BufferedReader(InputStreamReader(p.errorStream)), err)
                }
                tOut.start(); tErr.start()
                val finished = if (timeoutMs>0) p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) else { p.waitFor(); true }
                if (!finished) { p.destroyForcibly(); return Result(124, out.toString(), "timeout ${timeoutMs}ms") }
                tOut.join(800); tErr.join(800)
                return Result(p.exitValue(), out.toString(), err.toString())
            } catch (e: Exception) {
                return Result(-1, "", e.message ?: "fallo al lanzar su")
            }
    }

    /**
     * Drena un flujo al acumulador sin morir nunca con excepcion.
     *
     * Interna para poder probarla en JVM ([RootShellDrenajeTest]): el hilo lector
     * real la llama; aqui se simula el corte.
     */
    internal fun drenar(lector: BufferedReader, destino: StringBuilder) {
        try {
            lector.forEachLine { destino.appendLine(it) }
        } catch (_: Exception) { }
        // GUARD-SILENCIO-OK: drenaje best-effort (ver KDoc de `exec`).
    }

    /**
     * Lee el contenido completo de un fichero usando root/shell.
     * Escapa la ruta de forma segura para prevenir inyecciones.
     *
     * Devuelve [Result] con el código de salida, contenido stdout y stderr.
     */
    fun readFile(path: String, timeoutMs: Long = 5000): Result {
        return exec("cat " + shQuote(path), timeoutMs)
    }
}
