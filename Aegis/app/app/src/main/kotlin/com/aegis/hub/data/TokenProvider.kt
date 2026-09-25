package com.aegis.hub.data

import android.util.Log
import com.aegis.hub.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Proveedor canónico y singleton del token de autenticación del hub (`X-Aegis-Token`).
 *
 * Centraliza la lectura de `/sdcard/projects/Aegis/backend/.aegis_token` vía root,
 * el cacheo en memoria con TTL de 2000 ms, la invalidación ante errores 401/403/logout,
 * y provee wrappers tanto para OkHttp/corutinas como para llamadas directas HttpURLConnection.
 */
object TokenProvider {
    private const val TAG = "AegisToken"
    private const val TOKEN_FILE = "/sdcard/projects/Aegis/backend/.aegis_token"
    private const val CACHE_TTL_MS = 2_000L

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var lastFetched = 0L

    @Volatile
    var lastError: String? = null
        private set

    private val mutex = Mutex()

    /**
     * Obtiene el token de forma asíncrona y segura entre hilos/corutinas.
     * Si el token está en caché y dentro del TTL (2000 ms), se retorna directamente.
     * De lo contrario, lee `.aegis_token` usando `RootShell` en `Dispatchers.IO`.
     */
    suspend fun getToken(): String? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cached = cachedToken
        if (!cached.isNullOrEmpty() && (now - lastFetched) < CACHE_TTL_MS) {
            return@withContext cached
        }

        mutex.withLock {
            val checkNow = System.currentTimeMillis()
            val currentCached = cachedToken
            if (!currentCached.isNullOrEmpty() && (checkNow - lastFetched) < CACHE_TTL_MS) {
                return@withLock currentCached
            }

            try {
                val r = RootShell.exec("cat $TOKEN_FILE 2>/dev/null")
                val t = r.stdout.trim()
                if (t.isNotEmpty()) {
                    cachedToken = t
                    lastFetched = checkNow
                    lastError = null
                    t
                } else {
                    lastError = "Token no disponible: $TOKEN_FILE no existe o está vacío"
                    Log.e(TAG, lastError!!)
                    null
                }
            } catch (e: Exception) {
                lastError = "Error leyendo el token del hub: ${e.message}"
                Log.e(TAG, lastError!!)
                null
            }
        }
    }

    /**
     * Obtiene el token de manera síncrona / bloqueante (útil para interceptores de OkHttp
     * o llamadas síncronas existentes).
     */
    @Synchronized
    fun getTokenBlocking(): String? {
        val now = System.currentTimeMillis()
        val cached = cachedToken
        if (!cached.isNullOrEmpty() && (now - lastFetched) < CACHE_TTL_MS) {
            return cached
        }

        return try {
            val r = RootShell.exec("cat $TOKEN_FILE 2>/dev/null")
            val t = r.stdout.trim()
            if (t.isNotEmpty()) {
                cachedToken = t
                lastFetched = now
                lastError = null
                t
            } else {
                lastError = "Token no disponible: $TOKEN_FILE no existe o está vacío"
                Log.e(TAG, lastError!!)
                null
            }
        } catch (e: Exception) {
            lastError = "Error leyendo el token del hub: ${e.message}"
            Log.e(TAG, lastError!!)
            null
        }
    }

    /**
     * Alias para retrocompatibilidad con código existente que llame a `TokenProvider.token()`.
     */
    fun token(): String? = getTokenBlocking()

    /**
     * Invalida el token en caché ante un 401, 403, rotación o logout.
     */
    fun invalidate() {
        cachedToken = null
        lastFetched = 0L
    }

    /** Respuesta HTTP ya leída: código + cuerpo (legible aunque sea un 4xx). */
    data class Response(val code: Int, val body: String)

    /**
     * Ejecuta una petición completa con X-Aegis-Token y reintenta UNA vez ante 401 o 403
     * (invalida el token, relée y sólo repite si obtuvo un token nuevo).
     */
    @Throws(java.io.IOException::class)
    fun request(
        url: String,
        method: String,
        body: ByteArray? = null,
        connectTimeoutMs: Int = 3000,
        readTimeoutMs: Int = 15_000
    ): Response {
        var used = getTokenBlocking()
        var resp = execute(url, method, body, used, connectTimeoutMs, readTimeoutMs)
        if (resp.code == 401 || resp.code == 403) {
            invalidate()
            val fresh = getTokenBlocking()
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
