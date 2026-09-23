package com.aegis.hub.data

import android.util.Log
import com.aegis.hub.RootShell
import com.google.gson.GsonBuilder
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    private const val TAG = "AegisApi"
    private const val BASE_URL = "http://127.0.0.1:8765/"
    // Misma resolución de ruta del hub que ya usa la app (MainActivity.checkHubOnStart,
    // assets/stage-node.sh, RootShell y start-hub.sh -> /sdcard/projects/Aegis/backend)
    private const val TOKEN_FILE = "/sdcard/projects/Aegis/backend/.aegis_token"
    private const val TOKEN_RETRY_COOLDOWN_MS = 2_000L

    /** Estado de error visible para la UI (null = token leído OK). Nunca crashea: si el archivo
     *  no existe o está vacío se marca el error y se reintenta en la próxima petición (ciclo). */
    @Volatile
    var tokenError: String? = null
        private set

    @Volatile private var tokenCache: String? = null
    @Volatile private var tokenLastReadAt = 0L

    /** Lee el token del hub vía root (la app tiene root). Cachea en memoria; las lecturas fallidas
     *  NO se cachean (se reintenta) y van limitadas a 1 intento / 2s para no saturar `su`. */
    private fun readToken(): String? {
        tokenCache?.let { if (it.isNotEmpty()) return it }
        val now = System.currentTimeMillis()
        if (now - tokenLastReadAt < TOKEN_RETRY_COOLDOWN_MS) return null
        tokenLastReadAt = now
        return try {
            val r = RootShell.exec("cat $TOKEN_FILE 2>/dev/null")
            val t = r.stdout.trim()
            if (t.isNotEmpty()) {
                tokenCache = t
                tokenError = null
                t
            } else {
                tokenError = "Token no disponible: $TOKEN_FILE no existe o está vacío"
                Log.e(TAG, tokenError!!)
                null
            }
        } catch (e: Exception) {
            tokenError = "Error leyendo el token del hub: ${e.message}"
            Log.e(TAG, tokenError!!)
            null
        }
    }

    /** Adjunta X-Aegis-Token a TODAS las peticiones. ante un 403 relée el archivo una vez y
     *  reintenta la petición (rotación futura del token); si no cambió, devuelve el 403 tal cual. */
    private val authInterceptor = Interceptor { chain ->
        val base = chain.request()
        val token = readToken()
        val withToken = if (token != null) base.newBuilder().header("X-Aegis-Token", token).build() else base
        val response = chain.proceed(withToken)
        if (response.code == 403) {
            tokenCache = null              // releer una vez por si el hub rotó el token
            val fresh = readToken()
            if (fresh != null && fresh != token) {
                response.close()
                chain.proceed(withToken.newBuilder().header("X-Aegis-Token", fresh).build())
            } else {
                response
            }
        } else {
            response
        }
    }

    private val gson = GsonBuilder()
        .setLenient()
        .create()

    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .addInterceptor(authInterceptor)
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        .build()

    val rawOkHttp: OkHttpClient get() = okHttp

    val service: ApiService = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttp)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
        .create(ApiService::class.java)
}
