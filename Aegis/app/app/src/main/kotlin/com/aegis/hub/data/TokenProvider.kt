package com.aegis.hub.data

import android.util.Log
import com.aegis.hub.RootShell

/**
 * Token del hub para los consumidores HTTP directos que NO pasan por ApiClient
 * (MainActivity.checkSystemReady/isHubReady y CompanionVoiceInteractionService).
 *
 * A-3: misma fuente y semántica que ApiClient — lectura vía root de .aegis_token,
 * cache en memoria, cooldown de 2s ante lecturas fallidas (sin saturar `su`) y
 * reintento único ante 403 (rotación futura del token). ApiClient NO se toca:
 * conserva su copia privada dentro del interceptor de OkHttp; esta object sólo
 * atiende a quien abre HttpURLConnection a mano.
 */
object TokenProvider {
    private const val TAG = "AegisToken"
    // Misma resolución de ruta que ApiClient/TOKEN_FILE del hub
    private const val TOKEN_FILE = "/sdcard/projects/Aegis/backend/.aegis_token"
    private const val TOKEN_RETRY_COOLDOWN_MS = 2_000L

    @Volatile private var tokenCache: String? = null
    @Volatile private var tokenLastReadAt = 0L

    /** Lee el token del hub vía root. Las lecturas fallidas NO se cachean (se reintenta),
     *  limitadas a 1 intento / 2s para no saturar `su`. */
    fun token(): String? {
        tokenCache?.let { if (it.isNotEmpty()) return it }
        val now = System.currentTimeMillis()
        if (now - tokenLastReadAt < TOKEN_RETRY_COOLDOWN_MS) return null
        tokenLastReadAt = now
        return try {
            val r = RootShell.exec("cat $TOKEN_FILE 2>/dev/null")
            val t = r.stdout.trim()
            if (t.isNotEmpty()) {
                tokenCache = t
                t
            } else {
                Log.e(TAG, "Token no disponible: $TOKEN_FILE no existe o está vacío")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error leyendo el token del hub: ${e.message}")
            null
        }
    }

    /** Invalida la cache (ante un 403: el hub pudo rotar el token). */
    fun invalidate() { tokenCache = null }

    /** Respuesta HTTP ya leída: código + cuerpo (legible aunque sea un 4xx). */
    data class Response(val code: Int, val body: String)

    /**
     * Ejecuta una petición completa con X-Aegis-Token y reintenta UNA vez ante 403
     * (relée el token; sólo repite si el valor cambió). El body va como ByteArray
     * para que el reintento pueda reproducirlo. Las IOException de red se propagan:
     * cada llamador ya las captura (comportamiento previo idéntico).
     */
    @Throws(java.io.IOException::class)
    fun request(
        url: String,
        method: String,
        body: ByteArray? = null,
        connectTimeoutMs: Int = 3000,
        readTimeoutMs: Int = 15_000
    ): Response {
        var used = token()
        var resp = execute(url, method, body, used, connectTimeoutMs, readTimeoutMs)
        if (resp.code == 403) {
            invalidate()
            val fresh = token()
            if (fresh != null && fresh != used) {
                used = fresh
                resp = execute(url, method, body, fresh, connectTimeoutMs, readTimeoutMs)
            }
        }
        return resp
    }

    private fun execute(
        url: String,
        method: String,
        body: ByteArray?,
        token: String?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): Response {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.requestMethod = method
        if (token != null) conn.setRequestProperty("X-Aegis-Token", token)
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = try {
            stream?.bufferedReader()?.use { it.readText() } ?: ""
        } catch (_: Exception) { "" }
        try { conn.disconnect() } catch (_: Exception) {}
        return Response(code, text)
    }
}
