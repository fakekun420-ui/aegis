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

    /** Estado de error visible para la UI (null = token leído OK). */
    val tokenError: String?
        get() = TokenProvider.lastError

    /** Adjunta X-Aegis-Token a TODAS las peticiones vía TokenProvider. Ante un 401 o 403
     *  invalida y relée el archivo una vez y reintenta la petición; si no cambió, devuelve el error tal cual. */
    private val authInterceptor = Interceptor { chain ->
        val base = chain.request()
        val token = TokenProvider.getTokenBlocking()
        val withToken = if (token != null) base.newBuilder().header("X-Aegis-Token", token).build() else base
        val response = chain.proceed(withToken)
        if (response.code == 401 || response.code == 403) {
            TokenProvider.invalidate()              // releer una vez por si el hub rotó el token o expiró
            val fresh = TokenProvider.getTokenBlocking()
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

    /**
     * Tope de inactividad del cliente para esperar la respuesta del Hub.
     *
     * El Hub mantiene la conexión abierta durante TODO el turno agéntico (el modelo
     * llama a herramientas, lee ficheros y ejecuta comandos), y no emite ni un byte
     * mientras eso ocurre. Con 90s el readTimeout de OkHttp —que es de INACTIVIDAD,
     * se reinicia con cada byte— abortaba la petición a mitad de turno y la píldora
     * "Enviando..." se quedaba cargando para siempre.
     *
     * El Hub responde con un 502 limpio a los 600s (AEGIS_TURN_TIMEOUT_MS), así que
     * el cliente debe aguantar MÁS que eso para llegar a leer ese error en vez de
     * cortar antes por su cuenta. 660s deja 60s de margen.
     */
    private val TURN_TIMEOUT_SECONDS = 660L

    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(TURN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TURN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
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
