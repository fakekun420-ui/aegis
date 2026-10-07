package com.aegis.hub.data

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

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
        assertEquals("openrouter", ModelosUtil.resolveProviderFor("m", "openrouter", catalogo))
    }

    @Test
    fun `sin pista se prefiere opencode`() {
        val catalogo = listOf(
            OpenCodeNativeModel(id = "m", providerID = "openrouter"),
            OpenCodeNativeModel(id = "m", providerID = "opencode")
        )
        assertEquals("opencode", ModelosUtil.resolveProviderFor("m", null, catalogo))
    }

    @Test
    fun `sin candidatos se usa la pista y sin pista opencode`() {
        assertEquals("google", ModelosUtil.resolveProviderFor("desconocido", "google", emptyList()))
        assertEquals("opencode", ModelosUtil.resolveProviderFor("desconocido", null, emptyList()))
        assertEquals("opencode", ModelosUtil.resolveProviderFor(null, null, emptyList()))
    }

    @Test
    fun `el id con prefijo se corta y el proveedor queda como pista`() {
        // Forma real guardada en el movil por una version vieja.
        assertEquals("muse-spark-1.3-contributor-free", ModelosUtil.normalizarIdModelo("opencode/muse-spark-1.3-contributor-free"))
        assertEquals("opencode", ModelosUtil.proveedorDeRef("opencode/muse-spark-1.3-contributor-free"))
        assertEquals("space-bunny-free", ModelosUtil.normalizarIdModelo("space-bunny-free"))
        assertEquals(null, ModelosUtil.proveedorDeRef("space-bunny-free"))
        assertEquals("", ModelosUtil.normalizarIdModelo(null))
    }

    @Test
    fun `max se elige cuando el catalogo lo ofrece y si no es null`() {        val conMax = listOf(
            OpenCodeNativeModel(
                id = "muse-spark-1.3-contributor-free",
                variants = listOf(OpenCodeModelVariant(id = "low"), OpenCodeModelVariant(id = "max"))
            )
        )
        assertEquals("max", ModelosUtil.resolveVariantFor("muse-spark-1.3-contributor-free", conMax))
        // Con prefijo tambien resuelve, porque normaliza antes de buscar.
        assertEquals("max", ModelosUtil.resolveVariantFor("opencode/muse-spark-1.3-contributor-free", conMax))
        val sinMax = listOf(
            OpenCodeNativeModel(id = "otro", variants = listOf(OpenCodeModelVariant(id = "low")))
        )
        assertEquals(null, ModelosUtil.resolveVariantFor("otro", sinMax))
        assertEquals(null, ModelosUtil.resolveVariantFor("ausente", conMax))
    }

    @Test
    fun `el modelo del agente sale de su definicion y si no tiene es null`() {        // Forma real medida en GET /api/agent para orchestrator.
        val agentes = listOf(
            OpenCodeNativeAgent(
                id = "orchestrator",
                name = "orchestrator",
                model = OpenCodeModelRef(id = "muse-spark-1.3-contributor-free", providerID = "opencode")
            ),
            OpenCodeNativeAgent(id = "build", name = "Build", model = null)
        )
        val mod = ModelosUtil.modeloDelAgente("orchestrator", agentes)
        assertEquals("muse-spark-1.3-contributor-free", mod?.id)
        assertEquals("opencode", mod?.providerID)
        assertEquals(null, ModelosUtil.modeloDelAgente("build", agentes))
        assertEquals(null, ModelosUtil.modeloDelAgente("inexistente", agentes))
        assertEquals(null, ModelosUtil.modeloDelAgente(null, agentes))
    }

    @Test
    fun `el default de sesion con agente prefiere el modelo del agente`() {
        val agentes = listOf(
            Agente(name = "orchestrator", model = "muse-spark-1.3-contributor-free"),
            Agente(name = "Build", model = null)
        )
        val modelos = listOf(
            ModelOption(id = "space-bunny-free", name = "Space Bunny", free = true),
            ModelOption(id = "muse-spark-1.3-contributor-free", name = "Muse Spark", free = true)
        )
        assertEquals(
            "muse-spark-1.3-contributor-free",
            modeloPorDefectoPara("orchestrator", agentes, modelos)
        )
        assertEquals("space-bunny-free", modeloPorDefectoPara("Build", agentes, modelos))
        assertEquals("space-bunny-free", modeloPorDefectoPara("inexistente", agentes, modelos))
        assertEquals("space-bunny-free", modeloPorDefectoPara(null, agentes, modelos))
        assertEquals(
            "space-bunny-free",
            modeloPorDefectoPara("orchestrator", agentes, listOf(modelos[0]))
        )
    }
}
