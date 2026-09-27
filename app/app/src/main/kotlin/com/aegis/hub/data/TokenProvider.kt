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
 * Centraliza la lectura de `/sdcard/projects/Aegis/backend/.aegis_token`, el cacheo en
 * memoria, la invalidación ante errores 401/403/logout, y provee wrappers tanto para
 * OkHttp/corutinas como para llamadas directas HttpURLConnection.
 *
 * LECTURA DIRECTA, SIN ROOT (2026-09-27). Antes leía el fichero con `su -c cat` y la
 * caché duraba 2000 ms, mientras los polls de la app son de 1500 y 2000 ms: la caché
 * expiraba en cada vuelta, así que se lanzaba un `su` cada 1,5 s y Magisk pedía
 * confirmación cada vez. Con MANAGE_EXTERNAL_STORAGE concedido (ver MainActivity →
 * ensureAllFilesAccess) el fichero se lee con File y no hace falta root para nada.
 *
 * Root queda SOLO como respaldo, para que la app siga funcionando si el usuario aún no
 * ha concedido "Acceso a todos los archivos". En cuanto el permiso está concedido, la
 * rama directa gana y no se vuelve a pedir root.
 */
object TokenProvider {
    private const val TAG = "AegisToken"
    private const val TOKEN_FILE = "/sdcard/projects/Aegis/backend/.aegis_token"
    // 60 s en vez de 2 s. El token no cambia en la práctica: se invalida explícitamente
    // ante 401/403 y al hacer logout, así que una caché corta solo servía para gastar
    //lecturas de fichero.
    private const val CACHE_TTL_MS = 60_000L

    /** ¿Se ha resuelto alguna vez por la vía directa (sin root)? Evita el log cada vez. */
    @Volatile
    private var directReadWorks: Boolean? = null

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
     *
     * Si el token está en caché y dentro del TTL (60 s), se retorna directamente. Si no,
     * delega en [fetchToken], que es el ÚNICO sitio que sabe leer el fichero: directo
     * primero, root solo como respaldo. Antes esta función tenía su propio `su -c cat`
     * en línea, así que había DOS caminos de lectura y basta con arreglar uno para que el
     * otro siga pidiendo root a cada llamada.
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
            fetchToken(checkNow)
        }
    }

    /**
     * Lee el token con la API de ficheros normal, sin root.
     *
     * Devuelve null si el fichero no existe o no se puede leer (típico: el permiso de
     * "todos los archivos" aún no está concedido).
     */
    private fun readDirect(): String? = try {
        val f = java.io.File(TOKEN_FILE)
        if (f.isFile && f.canRead()) f.readText().trim().ifEmpty { null } else null
    } catch (e: Exception) {
        Log.w(TAG, "Lectura directa fallida: ${e.message}")
        null
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

        return fetchToken(now)
    }

    /**
     * ÚNICO punto que lee el fichero del token, y el que decide si hace falta root.
     *
     * Orden: 1) `File` normal, que es la vía normal en cuanto el usuario ha concedido
     * "Acceso a todos los archivos" y por tanto no pide root nunca. 2) `su -c cat`,
     * solo como respaldo para que la app no se quede sin token antes de que se conceda.
     *
     * Actualiza la caché y [lastError] como efecto lateral.
     */
    private fun fetchToken(now: Long): String? {
        // 1) Vía directa: sin root, sin spawn, sin prompt.
        readDirect()?.let { t ->
            cachedToken = t
            lastFetched = now
            lastError = null
            if (directReadWorks != true) {
                directReadWorks = true
                Log.i(TAG, "Token leído directamente de $TOKEN_FILE: ya no hace falta root.")
            }
            return t
        }
        if (directReadWorks != false) {
            directReadWorks = false
            Log.w(
                TAG,
                "No se pudo leer $TOKEN_FILE sin root. Concede 'Acceso a todos los archivos' " +
                    "en Ajustes → Apps → Aegis para eliminar las peticiones de root."
            )
        }

        // 2) Respaldo con root, solo si de verdad hace falta.
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
