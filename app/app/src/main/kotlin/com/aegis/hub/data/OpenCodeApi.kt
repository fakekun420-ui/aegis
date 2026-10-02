package com.aegis.hub.data

import android.util.Log
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Cliente e interfaz Retrofit para la conexión DIRECTA con OpenCode (`http://127.0.0.1:49374/`).
 *
 * Características:
 * 1. Base URL `http://127.0.0.1:49374/`.
 * 2. Inyección de `Authorization: Basic ...` en todas las peticiones vía OkHttp interceptor
 *    invocando [Credentials.default.getBasicAuthHeaderBlocking()].
 * 3. Ante HTTP 401: llama a [Credentials.default.invalidate()] y reintenta la petición UNA sola vez
 *    (la contraseña cambia en cada reinicio del daemon `serve`).
 * 4. Modelos de datos nativos sin envoltorios `{ ok, data }` del Hub.
 * 5. Soporte base para el stream SSE de eventos (`GET /api/event`).
 */
interface OpenCodeApi {

    // ==========================================
    // Servidor / Info
    // ==========================================

    @GET("api/info")
    suspend fun getInfo(): OpenCodeServerInfo

    // ==========================================
    // Sesiones
    // ==========================================

    @GET("api/session")
    suspend fun listSessions(
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int? = 50
    ): OpenCodeSessionListResponse

    @GET("api/session/{id}")
    suspend fun getSession(
        @Path("id") id: String
    ): OpenCodeSession


    @POST("api/session")
    suspend fun createSession(
        @Body body: CreateOpenCodeSessionRequest
    ): OpenCodeSession

    @PATCH("api/session/{id}")
    suspend fun updateSession(
        @Path("id") id: String,
        @Body body: UpdateOpenCodeSessionRequest
    ): retrofit2.Response<Unit>

    @DELETE("api/session/{id}")
    suspend fun deleteSession(
        @Path("id") id: String
    ): retrofit2.Response<Unit>

    // ==========================================
    // Estado de turno (Activas en tiempo real)
    // ==========================================

    @GET("api/session/active")
    suspend fun getActiveSessions(): Map<String, ActiveSessionStatus>

    // ==========================================
    // Mensajes
    // ==========================================

    /**
     * MEDIDO 2026-10-02 contra el endpoint vivo, con 2520 mensajes en la sesion:
     *
     *  - `order=asc`  -> devuelve los MAS ANTIGUOS (la CABEZA). Con limit=200 salen los indices
     *                    0..199, y el resto de la conversacion no existe para quien pregunta.
     *  - `order=desc` -> devuelve los MAS NUEVOS. Eso es lo que un chat necesita para avanzar.
     *  - `limit > 200` -> devuelve **CERO mensajes**, en silencio y sin error. El tope es duro.
     *
     * Y un cuarto, el que hace el paginado inutilizable: **`cursor` no se puede combinar con
     * `order`** (`400 InvalidCursorError: Cursor cannot be combined with order`). O sea que
     * paginar solo es posible SIN `order`, y con `order` solo hay cabeza o cola.
     *
     * Por eso `order` NO tiene valor por defecto aqui. Antes era "asc", y fue la causa de que
     * `getMessagesTail` devolviera la cabeza: la app llamaba a "la cola" y recibia el principio
     * del chat, siempre los mismos 200 mensajes, con lo que el poll no podia aportar nunca nada
     * nuevo. Un valor por defecto aqui no es una comodidad: es una decision sobre que parte del
     * historial ve el usuario, y no debe tomarla sin querer.
     */
    @GET("api/session/{id}/message")
    suspend fun getMessages(
        @Path("id") sessionId: String,
        @Query("limit") limit: Int? = LIMITE_MAX_MENSAJES,
        @Query("order") order: String? = null,
        @Query("cursor") cursor: String? = null
    ): OpenCodeMessageListResponse

    @GET("api/session/{id}/message/{messageID}")
    suspend fun getMessage(
        @Path("id") sessionId: String,
        @Path("messageID") messageID: String
    ): OpenCodeMessage

    // ==========================================
    // Prompt y Control de Turno
    // ==========================================

    @POST("api/session/{id}/prompt")
    suspend fun sendPrompt(
        @Path("id") sessionId: String,
        @Body body: OpenCodePromptRequest
    ): OpenCodePromptAck

    @POST("api/session/{id}/interrupt")
    suspend fun interruptSession(
        @Path("id") sessionId: String
    ): retrofit2.Response<Unit>

    // ==========================================
    // Configuración de Modelo y Agente por Sesión
    // ==========================================

    @POST("api/session/{id}/model")
    suspend fun setSessionModel(
        @Path("id") sessionId: String,
        @Body body: SetSessionModelRequest
    ): retrofit2.Response<Unit>

    @POST("api/session/{id}/agent")
    suspend fun setSessionAgent(
        @Path("id") sessionId: String,
        @Body body: SetSessionAgentRequest
    ): retrofit2.Response<Unit>

    // ==========================================
    // Catálogos
    // ==========================================

    @GET("api/agent")
    suspend fun listAgents(): OpenCodeNativeAgentListResponse

    @GET("api/model")
    suspend fun listModels(): OpenCodeNativeModelListResponse

    // ==========================================
    // Formularios y Permisos
    // ==========================================

    @GET("api/session/{id}/form")
    suspend fun getSessionForms(
        @Path("id") sessionId: String
    ): OpenCodeFormsResponse

    @POST("api/session/{id}/form/{formID}/reply")
    suspend fun replyForm(
        @Path("id") sessionId: String,
        @Path("formID") formID: String,
        @Body body: OpenCodeFormReplyRequest
    ): retrofit2.Response<Unit>

    @GET("api/session/{id}/permission")
    suspend fun getSessionPermissions(
        @Path("id") sessionId: String
    ): OpenCodePermissionsResponse

    @POST("api/session/{id}/permission/{requestID}/reply")
    suspend fun replyPermission(
        @Path("id") sessionId: String,
        @Path("requestID") requestID: String,
        @Body body: OpenCodePermissionReplyRequest
    ): retrofit2.Response<Unit>

    // ==========================================
    // Eventos SSE (/api/event) - Streaming crudo
    // ==========================================

    @Streaming
    @GET("api/event")
    suspend fun openEventStream(): ResponseBody

    companion object {
        const val BASE_URL = "http://127.0.0.1:49374/"
        private const val TAG = "OpenCodeApi"

        /**
         * MEDIDO 2026-10-02: el tope de `limit` en `/api/session/{id}/message` es **200**, y es
         * duro — lo que se pase devuelve **cero mensajes**, en silencio, sin error ni 400.
         *
         * MEDIDO con 2520 mensajes en la sesion: limit=50 -> 50, 100 -> 100, 200 -> 200,
         * **201 -> 0**, 250 -> 0, 500 -> 0, 1000 -> 0.
         *
         * Esto convertia cualquier "subamos el limite para ver mas historial" en un chat VACIO, y
         * sin una sola pista de por que. `TAIL_POLL = 200` en `ChatViewModel` estaba justo en el
         * limite: funcionar, por casualidad.
         */
        const val LIMITE_MAX_MENSAJES = 200

        /** MEDIDO: `order=desc` son los MAS NUEVOS. Es lo que necesita un chat para avanzar. */
        const val ORDEN_COLA = "desc"

        /** MEDIDO: `order=asc` son los MAS ANTIGUOS. Es la cabeza, y por lo tanto el congelamiento. */
        const val ORDEN_CABECERA = "asc"

        /**
         * Crea el interceptor de autenticación HTTP Basic.
         *
         * 1. Consulta la cabecera mediante [Credentials.getBasicAuthHeaderBlocking()].
         * 2. Si recibe HTTP 401: invalida las credenciales en caché mediante [Credentials.invalidate()]
         *    y reintenta la petición UNA sola vez.
         */
        fun createAuthInterceptor(credentials: Credentials = Credentials.default): Interceptor {
            return Interceptor { chain ->
                val originalRequest = chain.request()

                val authHeader = try {
                    credentials.getBasicAuthHeaderBlocking()
                } catch (e: Exception) {
                    Log.e(TAG, "Error obteniendo credenciales para OpenCode: ${e.message}")
                    null
                }

                val requestWithAuth = if (authHeader != null) {
                    originalRequest.newBuilder()
                        .header("Authorization", authHeader)
                        .build()
                } else {
                    originalRequest
                }

                val response = chain.proceed(requestWithAuth)

                // Si recibimos 401 Unauthorized, invalidamos y reintentamos exactamente una vez
                if (response.code == 401) {
                    Log.w(TAG, "HTTP 401 en ${originalRequest.url}. Invalidando credenciales y reintentando...")
                    credentials.invalidate()

                    val freshHeader = try {
                        credentials.getBasicAuthHeaderBlocking()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error refrescando credenciales tras 401: ${e.message}")
                        null
                    }

                    if (freshHeader != null && freshHeader != authHeader) {
                        response.close()
                        val retryRequest = originalRequest.newBuilder()
                            .header("Authorization", freshHeader)
                            .build()
                        return@Interceptor chain.proceed(retryRequest)
                    }
                }

                response
            }
        }

        /**
         * Construye el cliente OkHttpClient configurado para OpenCode.
         */
        fun createOkHttpClient(
            credentials: Credentials = Credentials.default,
            timeoutSeconds: Long = 660L
        ): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .addInterceptor(createAuthInterceptor(credentials))
                .addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                })
                .build()
        }

        /**
         * Construye la instancia Retrofit para [OpenCodeApi].
         */
        fun create(
            baseUrl: String = BASE_URL,
            credentials: Credentials = Credentials.default,
            okHttpClient: OkHttpClient = createOkHttpClient(credentials)
        ): OpenCodeApi {
            val gson = GsonBuilder()
                .setLenient()
                .create()

            return Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
                .create(OpenCodeApi::class.java)
        }

        /**
         * Cliente singleton por defecto.
         */
        val default: OpenCodeApi by lazy {
            create()
        }

        /**
         * Lectura base de eventos SSE desde `GET /api/event` como [Flow] de [OpenCodeServerEvent].
         * Consume líneas con prefijo "data:" y parsea el JSON subyacente.
         */
        fun streamServerEvents(
            api: OpenCodeApi = default
        ): Flow<OpenCodeServerEvent> = flow {
            val responseBody = api.openEventStream()
            val gson = GsonBuilder().setLenient().create()
            val reader = BufferedReader(InputStreamReader(responseBody.byteStream(), Charsets.UTF_8))

            try {
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val currentLine = line?.trim() ?: continue
                    if (currentLine.startsWith("data:")) {
                        val jsonPayload = currentLine.removePrefix("data:").trim()
                        if (jsonPayload.isNotEmpty()) {
                            try {
                                val event = gson.fromJson(jsonPayload, OpenCodeServerEvent::class.java)
                                if (event != null) {
                                    emit(event)
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Error parseando evento SSE: ${e.message}")
                            }
                        }
                    }
                }
            } finally {
                try {
                    reader.close()
                } catch (_: Exception) {}
                try {
                    responseBody.close()
                } catch (_: Exception) {}
            }
        }.flowOn(Dispatchers.IO)
    }
}
