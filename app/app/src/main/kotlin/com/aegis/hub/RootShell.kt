package com.aegis.hub

import android.os.Looper
import java.io.BufferedReader
import java.io.InputStreamReader

object RootShell {
    data class Result(val code:Int, val stdout:String, val stderr:String)

    /**
     * Escapa un argumento para sh con comillas simples: dentro de '...' todo es literal,
     * por lo que neutraliza `;`, `|`, `&`, `<`, `>`, backticks, `$`, `\n` y `\`.
     * El único carácter especial es la comilla simple, que se cierra y reabre con '\''.
     */
    private fun shQuote(arg: String): String = "'" + arg.replace("'", "'\\''") + "'"

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
        // intenta su -c, si falla sh -c
        val shells = listOf(arrayOf("su","-c", cmd), arrayOf("sh","-c", cmd))
        var last: Result? = null
        for (sh in shells) {
            try {
                val p = Runtime.getRuntime().exec(sh)
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
                val code = p.exitValue()
                // si usamos su y code !=0 pero su not found, probamos siguiente
                if (sh[0]=="su" && code!=0 && out.toString().contains("not found", true)) {
                    last = Result(code, out.toString(), err.toString()); continue
                }
                return Result(code, out.toString(), err.toString())
            } catch (e: Exception) {
                last = Result(-1, "", e.message ?: "exec error")
            }
        }
        return last ?: Result(-1, "", "no shell")
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
