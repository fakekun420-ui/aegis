package com.opencode.companion

import java.io.BufferedReader
import java.io.InputStreamReader

object RootShell {
    data class Result(val code:Int, val stdout:String, val stderr:String)

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

    fun launchPackage(pkg: String): Result = exec("monkey -p $pkg -c android.intent.category.LAUNCHER 1 2>&1 | head -n 20")
    fun tap(x:Int, y:Int): Result = exec("input tap $x $y")
    fun keyEvent(code:Int): Result = exec("input keyevent $code")
    fun inputText(text:String): Result {
        val esc = text.replace(" ", "%s").replace("&","\\&").replace("\"","\\\"")
        return exec("input text \"$esc\"")
    }
}
