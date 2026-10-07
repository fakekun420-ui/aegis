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
 * Sirve las rutas de [OpenCodeApi] con las FORMAS reales (envelopes `data`,
 * errores `_tag`/`message` de la captura 2026-10-07). Modos: [NORMAL], [LENTO],
 * [FALLO_500], [SIN_SESION] (404 con cuerpo real) y [SSE_TEXTO] (stream con un
 * segmento texto completo). Cada peticion queda registrada en [peticiones].
 */
class FakeOpenCode {

    enum class Modo { NORMAL, LENTO, FALLO_500, SIN_SESION }

    var modo: Modo = Modo.NORMAL

    data class Peticion(val metodo: String, val ruta: String)

    val peticiones = ConcurrentLinkedQueue<Peticion>()
    var crearSesiones = 0
    var modelosFijados = mutableListOf<String>()
    var agentesFijados = mutableListOf<String>()
    var cuerposCreacion = mutableListOf<String>()
    var eventosPeticiones = 0
    var primerEventoCorta = false

    val servidor = MockWebServer()

    private fun envuelto(cuerpo: String) = """{"data":$cuerpo}"""

    private val sesion = envuelto(
        """{"id":"ses_test","title":"Chat de prueba","agent":"build","model":{"id":"m-srv","providerID":"opencode"}}"""
    )
    private val modelos = envuelto(
        """[{"id":"m-srv","modelID":"m-srv","providerID":"opencode","name":"M Srv","enabled":true}]"""
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
                        crearSesiones++
                        cuerposCreacion.add(request.body.readUtf8())
                        MockResponse().setResponseCode(200)
                            .setBody(envuelto("""{"id":"ses_nueva_$crearSesiones","title":"T"}"""))
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
                    ruta.startsWith("/api/event") -> {
                        eventosPeticiones++
                        if (primerEventoCorta && eventosPeticiones == 1) {
                            // Corte limpio con error: el cliente reconecta igual
                            // que ante un EOF (mismo backoff, mismo camino).
                            MockResponse().setResponseCode(500).setBody("corte")
                        } else {
                            sse()
                        }
                    }
                    else -> MockResponse().setResponseCode(404).setBody("no hay ruta")
                }
            }
        }
        servidor.start()
    }

    private fun sse(): MockResponse {
        val cuerpo = """
            data: {"id":"e1","type":"session.text.started","data":{"sessionID":"ses_test"}}

            data: {"id":"e2","type":"session.text.delta","data":{"sessionID":"ses_test","delta":"Ho"}}

            data: {"id":"e3","type":"session.text.ended","data":{"sessionID":"ses_test","text":"Hola"}}

            data: {"id":"e4","type":"session.execution.succeeded","data":{"sessionID":"ses_test"},"durable":{"aggregateID":"ses_test","seq":7,"version":1}}

        """.trimIndent()
        return MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody(cuerpo)
            .throttleBody(1024, 50, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    fun api(): OpenCodeApi = OpenCodeApi.create(baseUrl = servidor.url("/").toString())

    fun contar(metodo: String, prefijoRuta: String): Int =
        peticiones.count { it.metodo == metodo && it.ruta.startsWith(prefijoRuta) }

    fun cerrar() {
        servidor.shutdown()
    }
}
