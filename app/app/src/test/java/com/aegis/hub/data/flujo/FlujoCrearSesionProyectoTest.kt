package com.aegis.hub.data.flujo

import com.aegis.hub.data.ProjectsStore
import com.aegis.hub.data.repo.ConfigCache
import com.aegis.hub.data.repo.NuevaSesion
import com.aegis.hub.data.repo.Resultado
import com.aegis.hub.data.repo.SesionConfigRepo
import com.aegis.hub.data.repo.SesionesRepo
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Flujo crear sesion en proyecto (F10): carpeta real del proyecto, agente y
 * modelo fijados, vinculo escrito; un solo POST /session.
 */
class FlujoCrearSesionProyectoTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var fake: FakeOpenCode

    @Before
    fun arrancar() {
        fake = FakeOpenCode()
    }

    @After
    fun parar() {
        fake.cerrar()
    }

    private class Cache : ConfigCache {
        override fun leerModelo(sid: String): String? = null
        override fun guardarModelo(sid: String, modelo: String) = Unit
        override fun leerAgente(sid: String): String? = null
        override fun guardarAgente(sid: String, agente: String) = Unit
        override fun ultimoModelo(): String? = null
        override fun guardarUltimoModelo(modelo: String) = Unit
        override fun ultimoAgente(): String? = null
        override fun guardarUltimoAgente(agente: String) = Unit
    }

    @Test
    fun `crear en proyecto deja una sesion completa con un solo POST`() = runBlocking {
        val oc = fake.api()
        val store = ProjectsStore(
            storeFile = tempFolder.newFile("s.json"),
            legacyFile = tempFolder.newFile("s.legacy"),
            clock = { 0L }
        )
        store.saveProject(ProjectsStore.ProjectEntry(id = "p1", name = "Otro Nombre", folder = "/sdcard/projects/carpeta-real"))
        val repo = SesionesRepo(oc, store, SesionConfigRepo(oc, Cache()))

        val r = repo.crear(NuevaSesion("Nueva", proyectoId = "p1"))
        assertTrue(r is Resultado.Ok)
        val creada = (r as Resultado.Ok).valor

        assertEquals(1, fake.contar("POST", "/api/session"))
        assertTrue("la carpeta real viaja en location: ${fake.cuerposCreacion}",
            fake.cuerposCreacion[0].contains("/sdcard/projects/carpeta-real"))
        assertTrue(fake.agentesFijados.isNotEmpty())
        assertTrue(fake.modelosFijados.isNotEmpty())
        assertEquals("p1", store.getProjectIdForSession(creada.id, null))
        assertTrue(r.avisos.isEmpty())
    }
}
