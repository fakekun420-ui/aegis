package com.aegis.hub

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
        // intenta su -c, si falla sh -c
        val shells = listOf(arrayOf("su","-c", cmd), arrayOf("sh","-c", cmd))
        var last: Result? = null
        for (sh in shells) {
            try {
                val p = Runtime.getRuntime().exec(sh)
                val out = StringBuilder()
                val err = StringBuilder()
                val tOut = Thread { BufferedReader(InputStreamReader(p.inputStream)).forEachLine { out.appendLine(it) } }
                val tErr = Thread { BufferedReader(InputStreamReader(p.errorStream)).forEachLine { err.appendLine(it) } }
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
     * Lee el contenido completo de un fichero usando root/shell.
     * Escapa la ruta de forma segura para prevenir inyecciones.
     *
     * Devuelve [Result] con el código de salida, contenido stdout y stderr.
     */
    fun readFile(path: String, timeoutMs: Long = 5000): Result {
        return exec("cat " + shQuote(path), timeoutMs)
    }
}
