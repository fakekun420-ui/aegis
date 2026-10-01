package com.aegis.hub.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests unitarios JVM para [ProjectsStore].
 *
 * Afirmacion con contraejemplo explícito:
 * 1. Resolucion de proyecto con ruta explicita vs directorio coincidente.
 * 2. Prevalencia de vinculacion de sesion sobre coincidencia de carpeta.
 * 3. Migracion desde fichero legacy backend/projects.json vs fichero vacio.
 * 4. Persistencia de titulos y pines de sesion.
 */
class ProjectsStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storeFile: File
    private lateinit var legacyFile: File
    private var currentTime = 1_000_000L

    @Before
    fun setUp() {
        storeFile = tempFolder.newFile("test-projects-store.json")
        legacyFile = tempFolder.newFile("test-projects-legacy.json")
        storeFile.delete() // Para que arranque sin fichero de store inicial
    }

    @Test
    fun `sin ruta explicita vinculada el directorio del proyecto manda`() {
        val store = ProjectsStore(
            storeFile = storeFile,
            legacyFile = legacyFile,
            clock = { currentTime }
        )
        store.saveProject(
            ProjectsStore.ProjectEntry(
                id = "proj_compraseguro",
                name = "compraseguro-bo",
                folder = "/sdcard/projects/compraseguro-bo"
            )
        )

        // AFIRMACIÓN: Si no hay link explicito, el directorio hace match
        val resolved = store.getProjectIdForSession("ses_123", "/sdcard/projects/compraseguro-bo")
        assertEquals("proj_compraseguro", resolved)
    }

    @Test
    fun `contraejemplo - con directorio no coincidente no se asocia proyecto`() {
        val store = ProjectsStore(
            storeFile = storeFile,
            legacyFile = legacyFile,
            clock = { currentTime }
        )
        store.saveProject(
            ProjectsStore.ProjectEntry(
                id = "proj_compraseguro",
                name = "compraseguro-bo",
                folder = "/sdcard/projects/compraseguro-bo"
            )
        )

        // CONTRAEJEMPLO: Un directorio ajeno o temporario no devuelve el id de compraseguro
        val resolved = store.getProjectIdForSession("ses_123", "/tmp/bench-orch")
        assertNull(resolved)
    }

    @Test
    fun `con vinculo explicito la asociacion explicita manda sobre el directorio`() {
        val store = ProjectsStore(
            storeFile = storeFile,
            legacyFile = legacyFile,
            clock = { currentTime }
        )
        store.saveProject(
            ProjectsStore.ProjectEntry(
                id = "proj_a",
                name = "Proyecto A",
                folder = "/sdcard/projects/proj-a"
            )
        )
        store.saveProject(
            ProjectsStore.ProjectEntry(
                id = "proj_b",
                name = "Proyecto B",
                folder = "/sdcard/projects/proj-b"
            )
        )

        // Vinculamos explicitamente ses_x al proyecto B
        store.linkSessionToProject("ses_x", "proj_b")

        // AFIRMACIÓN: Aunque el directorio sea proj-a, el vinculo explicito devuelve proj_b
        val resolved = store.getProjectIdForSession("ses_x", "/sdcard/projects/proj-a")
        assertEquals("proj_b", resolved)
    }

    @Test
    fun `migracion exitosa si legacy existe con proyectos y pines`() {
        legacyFile.writeText(
            """
            {
              "projects": [
                {
                  "id": "legacy_p1",
                  "name": "Legacy Project",
                  "folder": "/sdcard/projects/legacy",
                  "sessions": ["ses_legacy_1"]
                }
              ],
              "sessionTitles": {
                "ses_legacy_1": "Titulo Legacy"
              },
              "sessionPins": {
                "ses_legacy_1": true
              }
            }
            """
        )

        val store = ProjectsStore(
            storeFile = storeFile,
            legacyFile = legacyFile,
            clock = { currentTime }
        )

        val p = store.getProject("legacy_p1")
        assertNotNull(p)
        assertEquals("Legacy Project", p?.name)
        assertEquals("/sdcard/projects/legacy", p?.folder)
        assertEquals("Titulo Legacy", store.getSessionTitle("ses_legacy_1"))
        assertTrue(store.isSessionPinned("ses_legacy_1"))
        assertEquals("legacy_p1", store.getProjectIdForSession("ses_legacy_1"))
    }

    @Test
    fun `contraejemplo - sesion no pineada devuelve false`() {
        val store = ProjectsStore(
            storeFile = storeFile,
            legacyFile = legacyFile,
            clock = { currentTime }
        )
        assertFalse(store.isSessionPinned("ses_desconocida"))
    }
}
