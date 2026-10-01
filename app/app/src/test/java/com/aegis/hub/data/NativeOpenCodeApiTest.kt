package com.aegis.hub.data

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests unitarios JVM para verificar la lógica de interceptor y auth retry de [OpenCodeApi].
 */
class NativeOpenCodeApiTest {

    @Test
    fun `authInterceptor inyecta cabecera Authorization Basic en cada peticion`() {
        val fakeCredentials = Credentials(
            candidatePaths = listOf("/fake/path"),
            fileReader = {
                val json = """{"password":"test_pass_123","pid":123,"url":"http://127.0.0.1:49374"}"""
                com.aegis.hub.RootShell.Result(0, json, "")
            }
        )

        var capturedAuthHeader: String? = null

        val mockInterceptor = Interceptor { chain ->
            val req = chain.request()
            capturedAuthHeader = req.header("Authorization")
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody("application/json".toMediaTypeOrNull()))
                .build()
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(OpenCodeApi.createAuthInterceptor(fakeCredentials))
            .addInterceptor(mockInterceptor)
            .build()

        val request = Request.Builder()
            .url("http://127.0.0.1:49374/api/info")
            .build()

        val response = client.newCall(request).execute()
        assertEquals(200, response.code)
        assertNotNull(capturedAuthHeader)
        assertTrue(capturedAuthHeader!!.startsWith("Basic "))
    }

    @Test
    fun `authInterceptor ante HTTP 401 invalida y reintenta exactamente una vez`() {
        var currentPassword = "old_stale_password"
        val readCounter = AtomicInteger(0)

        val fakeCredentials = Credentials(
            candidatePaths = listOf("/fake/path"),
            fileReader = {
                readCounter.incrementAndGet()
                val json = """{"password":"$currentPassword","pid":123,"url":"http://127.0.0.1:49374"}"""
                com.aegis.hub.RootShell.Result(0, json, "")
            }
        )

        val serverCallCount = AtomicInteger(0)

        val mockInterceptor = Interceptor { chain ->
            val req = chain.request()
            val count = serverCallCount.incrementAndGet()
            val auth = req.header("Authorization")

            if (auth?.contains("old_stale_password") == true || count == 1) {
                // Simular que el daemon cambió de password: primer intento responde 401
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(401)
                    .message("Unauthorized")
                    .body("{}".toResponseBody("application/json".toMediaTypeOrNull()))
                    .build()
            } else {
                // Segundo intento con nueva password: 200 OK
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"status":"ok"}""".toResponseBody("application/json".toMediaTypeOrNull()))
                    .build()
            }
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(OpenCodeApi.createAuthInterceptor(fakeCredentials))
            .addInterceptor(mockInterceptor)
            .build()

        // Sembrar caché con la clave vieja
        fakeCredentials.getPasswordBlocking()

        // Cambiar password del servicio en caliente (como tras un reinicio de serve)
        currentPassword = "new_fresh_password"

        val request = Request.Builder()
            .url("http://127.0.0.1:49374/api/info")
            .build()

        val response = client.newCall(request).execute()

        // Debe haber recibido 200 tras el reintento exitoso
        assertEquals(200, response.code)
        // Debe haber contactado al mock 2 veces (401 -> retry -> 200)
        assertEquals(2, serverCallCount.get())
        // Debe haber leído el archivo de credenciales de nuevo (1 inicial + 1 tras invalidate)
        assertEquals(2, readCounter.get())
    }
}
