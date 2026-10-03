package com.aegis.hub.data

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * MEDIDO 2026-10-03 contra el servidor vivo y el OpenAPI: `GET /api/session/{id}`,
 * `POST /api/session` y `GET /api/session/{id}/message/{messageID}` responden con la
 * entidad ENVUELTA en `data`. Declarar el retorno sin envoltura hacia que Gson devolviera
 * todo a null: `getSessionModel` siempre null y la app jamas veia el modelo del CLI.
 *
 * Estos tests fijan la envoltura con el JSON real medido, y la resolucion del providerID
 * que `POST /api/session/{id}/model` exige junto al id.
 */
class SesionModeloSyncTest {

    private val gson = Gson()

    private val ruta: RutaNativa = RutaNativa(
        Proxy.newProxyInstance(
            ApiService::class.java.classLoader,
            arrayOf(ApiService::class.java)
        ) { _, metodo, _ ->
            error("el test no debe preguntar al Hub: ${metodo.name}")
        } as ApiService
    )

    @Test
    fun `la sesion individual se desenvuelve de data con su modelo completo`() {
        // Forma real medida en vivo de GET /api/session/ses_x.
        val crudo = """{"data":{"id":"ses_x","title":"aegis","model":{"id":"muse-spark-1.3-contributor-free","providerID":"opencode","variant":"xhigh"}}}"""
        val r = gson.fromJson(crudo, OpenCodeSessionResponse::class.java)
        assertNotNull(r.data)
        assertEquals("ses_x", r.data?.id)
        assertEquals("muse-spark-1.3-contributor-free", r.data?.model?.id)
        assertEquals("opencode", r.data?.model?.providerID)
        assertEquals("xhigh", r.data?.model?.variant)
    }

    @Test
    fun `el mensaje individual se desenvuelve de data`() {
        val crudo = """{"data":{"id":"msg_x","sessionID":"ses_x","role":"user","text":"hola"}}"""
        val r = gson.fromJson(crudo, OpenCodeMessageResponse::class.java)
        assertNotNull(r.data)
        assertEquals("msg_x", r.data?.id)
        assertEquals("hola", r.data?.text)
    }

    @Test
    fun `el proveedor se resuelve por pista cuando el id existe en varios`() {
        val catalogo = listOf(
            OpenCodeNativeModel(id = "m", providerID = "opencode"),
            OpenCodeNativeModel(id = "m", providerID = "openrouter")
        )
        assertEquals("openrouter", ruta.resolveProviderFor("m", "openrouter", catalogo))
    }

    @Test
    fun `sin pista se prefiere opencode`() {
        val catalogo = listOf(
            OpenCodeNativeModel(id = "m", providerID = "openrouter"),
            OpenCodeNativeModel(id = "m", providerID = "opencode")
        )
        assertEquals("opencode", ruta.resolveProviderFor("m", null, catalogo))
    }

    @Test
    fun `sin candidatos se usa la pista y sin pista opencode`() {
        assertEquals("google", ruta.resolveProviderFor("desconocido", "google", emptyList()))
        assertEquals("opencode", ruta.resolveProviderFor("desconocido", null, emptyList()))
        assertEquals("opencode", ruta.resolveProviderFor(null, null, emptyList()))
    }
}
