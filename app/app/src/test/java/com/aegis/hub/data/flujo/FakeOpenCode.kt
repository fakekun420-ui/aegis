package com.aegis.hub.data.flujo

import com.aegis.hub.data.OpenCodeApi
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Servidor falso de OpenCode (F10, T-F10.1).
 *
 * Sirve las rutas UNARIAS de [OpenCodeApi] con las FORMAS reales (envelopes
 * `data`, errores `_tag`/`message` de la captura 2026-10-07). Modos: [NORMAL],
 * [LENTO], [FALLO_500] y [SIN_SESION] (404 con cuerpo real). Cada peticion
 * queda registrada en [peticiones] (thread-safe).
 *
 * SIN `/api/event`: el streaming HTTP/SSE no entrega en CI y los tests SSE usan
 * `ResponseBody` locales (ver `FlujoReconexionTest`); la interop HTTP/SSE va al
 * movil (V-06/V-08).
 */
class FakeOpenCode {

    enum class Modo { NORMAL, LENTO, FALLO_500, SIN_SESION }

    var modo: Modo = Modo.NORMAL

    data class Peticion(val metodo: String, val ruta: String)

    val peticiones = ConcurrentLinkedQueue<Peticion>()
    var crearSesiones = java.util.concurrent.atomic.AtomicInteger(0)
    var modelosFijados = java.util.Collections.synchronizedList(mutableListOf<String>())
    var agentesFijados = java.util.Collections.synchronizedList(mutableListOf<String>())
    var cuerposCreacion = java.util.Collections.synchronizedList(mutableListOf<String>())

    val servidor = MockWebServer()

    private fun envuelto(cuerpo: String) = """{"data":$cuerpo}"""

    private val sesion = envuelto(
        """{"id":"ses_test","title":"Chat de prueba","agent":"build","model":{"id":"m-srv","providerID":"opencode"}}"""
    )
    private val modelos = envuelto(
        """[{"id":"m-srv","modelID":"m-srv","providerID":"opencode","name":"M Srv","enabled":true},{"id":"m-nuevo","modelID":"m-nuevo","providerID":"opencode","name":"M Nuevo","enabled":true}]"""
    )
    private val agentes = envuelto(
        """[{"id":"build","name":"build","mode":"primary","hidden":false,"model":{"id":"m-srv","providerID":"opencode"}}]"""
    )
    private val mensajes = envuelto(
        """[{"id":"msg_1","role":"user","text":"hola"},{"id":"msg_2","role":"assistant","content":[{"id":"prt_1","type":"text","text":"buenas"}]}]"""
    )

    init {
        servidor.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val ruta = request.path ?: "/"
                peticiones.add(Peticion(request.method ?: "?", ruta.substringBefore("?")))
                if (modo == Modo.LENTO) Thread.sleep(300)
                if (modo == Modo.FALLO_500) {
                    return MockResponse().setResponseCode(500).setBody("fallo")
                }
                if (modo == Modo.SIN_SESION && ruta.startsWith("/api/session/ses_")) {
                    return MockResponse().setResponseCode(404)
                        .setBody("""{"_tag":"SessionNotFoundError","message":"Session not found"}""")
                }
                return when {
                    ruta == "/api/session" && request.method == "POST" -> {
                        val n = crearSesiones.incrementAndGet()
                        cuerposCreacion.add(request.body.readUtf8())
                        MockResponse().setResponseCode(200)
                            .setBody(envuelto("""{"id":"ses_nueva_$n","title":"T"}"""))
                    }
                    ruta.startsWith("/api/session/") && ruta.endsWith("/model") -> {
                        modelosFijados.add(request.body.readUtf8())
                        MockResponse().setResponseCode(200).setBody(Buffer())
                    }
                    ruta.startsWith("/api/session/") && ruta.endsWith("/agent") -> {
                        agentesFijados.add(request.body.readUtf8())
                        MockResponse().setResponseCode(200).setBody(Buffer())
                    }
                    ruta.startsWith("/api/session/") && request.method == "DELETE" ->
                        MockResponse().setResponseCode(200).setBody(Buffer())
                    ruta.startsWith("/api/session/") && ruta.contains("/prompt") ->
                        MockResponse().setResponseCode(200)
                            .setBody(envuelto("""{"id":"msg_9","type":"text","sessionID":"ses_test"}"""))
                    ruta.startsWith("/api/session/") && ruta.contains("/message") && request.method == "POST" ->
                        MockResponse().setResponseCode(200)
                            .setBody(envuelto("""{"info":{"id":"msg_9","role":"assistant"},"parts":[]}"""))
                    ruta.startsWith("/api/session/") && ruta.contains("/message") ->
                        MockResponse().setResponseCode(200).setBody(mensajes)
                    ruta.startsWith("/api/session/") ->
                        MockResponse().setResponseCode(200).setBody(sesion)
                    ruta.startsWith("/api/session") ->
                        MockResponse().setResponseCode(200).setBody(envuelto("[]"))
                    ruta.startsWith("/api/model") ->
                        MockResponse().setResponseCode(200).setBody(modelos)
                    ruta.startsWith("/api/agent") ->
                        MockResponse().setResponseCode(200).setBody(agentes)
                    // NOTA: /api/event NO se sirve aqui. El streaming HTTP/SSE no
                    // entrega en CI (ver FlujoReconexionTest); los tests SSE usan
                    // ResponseBody locales y las formas viven en resources/eventos/.
                    else -> MockResponse().setResponseCode(404).setBody("no hay ruta")
                }
            }
        }
        servidor.start()
    }

    fun api(): OpenCodeApi = OpenCodeApi.create(baseUrl = servidor.url("/").toString())

    fun contar(metodo: String, prefijoRuta: String): Int =
        peticiones.count { it.metodo == metodo && it.ruta.startsWith(prefijoRuta) }

    /** Solo la ruta exacta (sin contar subrutas como /model o /agent). */
    fun contarExacto(metodo: String, ruta: String): Int =
        peticiones.count { it.metodo == metodo && it.ruta == ruta }

    fun cerrar() {
        servidor.shutdown()
    }
}
