package com.aegis.hub.data

import android.util.Log
import com.aegis.hub.RootShell
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Proveedor thread-safe y canónico de credenciales HTTP Basic para OpenCode (rutas bajo /api/).
 *
 * MEDIDO 2026-10-01: esta frase decía la ruta con los dos comodines de glob ("api" seguido de
 * asterisco) y rompía la compilación. Kotlin ANIDA los comentarios de bloque, a diferencia de
 * Java: esa secuencia abría un comentario anidado DENTRO de este KDoc, y el cierre de abajo lo
 * cerraba a él, dejando este KDoc abierto hasta el fin del fichero. De ahi el "Unclosed comment"
 * que la CI reportaba en la ÚLTIMA línea, y los "Unresolved reference" de `OpenCodeApi.kt`, que
 * no eran de ese fichero sino consecuencia: sus tipos habían quedado dentro de un comentario.
 *
 * OpenCode exige autenticación HTTP Basic con realm "Secure Area". La contraseña
 * se genera de forma aleatoria en cada arranque de `opencode serve` y vive en
 * `/root/.local/state/opencode/service.json` (o la ruta correspondiente en el chroot
 * de Ubuntu según el namespace de montaje).
 *
 * Esta clase:
 * 1. Mantiene una caché en memoria para no invocar `su` o I/O en cada petición.
 * 2. Permite invalidación explícita mediante [invalidate] (e.g. tras recibir HTTP 401).
 * 3. Aplica expiración por TTL (por defecto 60 segundos) como red de seguridad ante
 *    reinicios silenciosos de OpenCode.
 * 4. NUNCA cachea fallos: si una lectura falla, el siguiente intento vuelve a probar.
 * 5. Falla ruidosamente con diagnóstico detallado (rutas probadas y motivos de error),
 *    nunca devolviendo contraseñas vacías o silenciosas.
 * 6. Es completamente thread-safe (soporta corutinas concurrentes y llamadas síncronas).
 */
class Credentials(
    private val candidatePaths: List<String> = DEFAULT_CANDIDATE_PATHS,
    private val fileReader: (String) -> RootShell.Result = { path ->
        // Intento 1: lectura directa por File si el sistema de archivos lo permite
        try {
            val f = File(path)
            if (f.isFile && f.canRead()) {
                val text = f.readText(StandardCharsets.UTF_8)
                RootShell.Result(0, text, "")
            } else {
                RootShell.readFile(path)
            }
        } catch (_: Exception) {
            RootShell.readFile(path)
        }
    },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS
) {

    data class ServiceState(
        @SerializedName("id") val id: String? = null,
        @SerializedName("password") val password: String? = null,
        @SerializedName("pid") val pid: Long? = null,
        @SerializedName("url") val url: String? = null,
        @SerializedName("version") val version: String? = null
    )

    private val mutex = Mutex()
    private val gson = Gson()

    @Volatile
    private var cachedPassword: String? = null

    @Volatile
    private var lastFetchedTime: Long = 0L

    @Volatile
    var lastResolvedPath: String? = null
        private set

    @Volatile
    var lastDiagnostics: String? = null
        private set

    /**
     * Invalida la contraseña en caché (por ejemplo ante HTTP 401).
     */
    fun invalidate() {
        cachedPassword = null
        lastFetchedTime = 0L
    }

    /**
     * Indica si hay una contraseña válida y no expirada en la caché en memoria.
     */
    fun hasValidCache(): Boolean {
        val now = clock()
        val pwd = cachedPassword
        return !pwd.isNullOrEmpty() && (now - lastFetchedTime) < cacheTtlMs
    }

    /**
     * Obtiene la contraseña de forma asíncrona y segura ante concurrencia.
     * Si la caché expiró o está vacía, lee y parsea `service.json`.
     *
     * @throws IllegalStateException Si no se pudo obtener una contraseña válida.
     */
    suspend fun getPassword(): String = withContext(Dispatchers.IO) {
        val now = clock()
        val current = cachedPassword
        if (!current.isNullOrEmpty() && (now - lastFetchedTime) < cacheTtlMs) {
            return@withContext current
        }

        mutex.withLock {
            val checkNow = clock()
            val lockedCurrent = cachedPassword
            if (!lockedCurrent.isNullOrEmpty() && (checkNow - lastFetchedTime) < cacheTtlMs) {
                return@withLock lockedCurrent
            }
            fetchAndCache(checkNow)
        }
    }

    /**
     * Obtiene la contraseña de forma síncrona / bloqueante (apta para OkHttp Interceptor).
     *
     * @throws IllegalStateException Si no se pudo obtener una contraseña válida.
     */
    @Synchronized
    fun getPasswordBlocking(): String {
        val now = clock()
        val current = cachedPassword
        if (!current.isNullOrEmpty() && (now - lastFetchedTime) < cacheTtlMs) {
            return current
        }

        return fetchAndCache(now)
    }

    /**
     * Genera la cabecera HTTP Basic para OkHttp o HttpURLConnection.
     * Formato: "Basic " + base64("opencode:" + password).
     */
    suspend fun getBasicAuthHeader(): String {
        val pwd = getPassword()
        return buildBasicHeader(pwd)
    }

    /**
     * Genera la cabecera HTTP Basic de manera síncrona.
     */
    @Synchronized
    fun getBasicAuthHeaderBlocking(): String {
        val pwd = getPasswordBlocking()
        return buildBasicHeader(pwd)
    }

    private fun buildBasicHeader(password: String): String {
        val credentials = "opencode:$password"
        // MEDIDO 2026-10-01: aqui habia un `try { android.util.Base64 } catch { java.util.Base64 }`.
        // El catch nunca se activaba, y el motivo es que con `returnDefaultValues = true` el stub
        // de android.jar NO LANZA: devuelve null. Es decir, la cabecera salia "Basic null" y
        // OpenCode contestaba 401 en bucle, y el test de reintento fallaba sin que hubiera
        // ningun stack trace — un fallo mudo en la autenticacion, que es lo peor que hay aqui.
        //
        // `java.util.Base64` es de la JVM, no de android.jar, asi que funciona igual en un test
        // que en el movil. Y `minSdk = 26` (medido en build.gradle.kts), donde existe. No hace
        // falta `NO_WRAP`: `getEncoder()` no inserta saltos de linea, que es justo lo que
        // Hacia falta evitar.
        val encoded = java.util.Base64.getEncoder()
            .encodeToString(credentials.toByteArray(StandardCharsets.UTF_8))
        return "Basic $encoded"
    }

    /**
     * Lee y resuelve `service.json`. Si tiene éxito, actualiza la caché.
     * Si falla, NO cachea el fallo (la caché se mantiene limpia para reintentar).
     */
    private fun fetchAndCache(now: Long): String {
        val diagnosticLog = mutableListOf<String>()

        for (path in candidatePaths) {
            try {
                val result = fileReader(path)
                if (result.code != 0) {
                    val errMsg = "code=${result.code}, stderr=${result.stderr.trim()}"
                    diagnosticLog.add("Ruta '$path' falló: $errMsg")
                    continue
                }

                val stdout = result.stdout.trim()
                if (stdout.isEmpty()) {
                    diagnosticLog.add("Ruta '$path' devolvió contenido vacío")
                    continue
                }

                val parsed = parseServiceJson(stdout)
                val pwd = parsed.password?.trim()
                if (pwd.isNullOrEmpty()) {
                    diagnosticLog.add("Ruta '$path' parseó JSON válido pero sin clave 'password' no vacía")
                    continue
                }

                // Éxito: cachear y registrar
                cachedPassword = pwd
                lastFetchedTime = now
                lastResolvedPath = path
                lastDiagnostics = null
                Log.d(TAG, "Contraseña de OpenCode resuelta con éxito desde: $path (pid=${parsed.pid}, version=${parsed.version})")
                return pwd
            } catch (e: Exception) {
                diagnosticLog.add("Ruta '$path' excepción: ${e.javaClass.simpleName}(${e.message})")
            }
        }

        // Si llegamos aquí, todas las rutas candidatas fallaron
        val combinedDiag = diagnosticLog.joinToString(" | ")
        lastDiagnostics = combinedDiag
        // Aseguramos que el fallo NO queda cacheado
        cachedPassword = null
        lastFetchedTime = 0L

        val errorDetails = "No se pudo leer la contraseña de OpenCode desde ninguna ruta candidata. Diagnóstico: $combinedDiag"
        Log.e(TAG, errorDetails)
        throw IllegalStateException(errorDetails)
    }

    /**
     * Parsea la cadena JSON de service.json. Función interna para permitir pruebas unitarias.
     */
    internal fun parseServiceJson(json: String): ServiceState {
        val state = gson.fromJson(json, ServiceState::class.java)
            ?: throw IllegalArgumentException("JSON parseado resultó en objeto nulo")
        return state
    }

    companion object {
        private const val TAG = "OpenCodeCredentials"

        /** TTL de caché en memoria por defecto: 60 segundos */
        const val DEFAULT_CACHE_TTL_MS = 60_000L

        /**
         * Rutas candidatas para service.json:
         * 1) /data/local/ubuntu/root/.local/state/opencode/service.json (namespace del host Android al chroot)
         * 2) /root/.local/state/opencode/service.json (namespace dentro de chroot/root)
         *
         * NOTA: NO se incluye ninguna ruta de log (e.g. opencode.log) porque su contraseña
         * es histórica/caducada y genera HTTP 401.
         */
        val DEFAULT_CANDIDATE_PATHS = listOf(
            "/data/local/ubuntu/root/.local/state/opencode/service.json",
            "/root/.local/state/opencode/service.json"
        )

        /**
         * Instancia singleton estándar para la aplicación.
         */
        val default: Credentials by lazy {
            Credentials()
        }
    }
}
