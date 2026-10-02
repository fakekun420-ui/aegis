package com.aegis.hub.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * MEDIDO 2026-10-02: estos tests fijan el vinculo sesion <-> proyecto del `ProjectsStore`, que es
 * lo que sostiene la lista de chats DE UN PROYECTO.
 *
 * ## Por que el store y no OpenCode
 *
 * MEDIDO contra el OpenAPI de OpenCode (113 rutas): **no existe** `/api/project/{id}/sessions`.
 * El vinculo entre una sesion y un proyecto no lo tiene OpenCode — lo tenia el Hub, y con el Hub
 * retirado no lo tiene nadie salvo este store. Por eso `getProjectSessions` cruza las sesiones que
 * da OpenCode con los vinculos de aqui.
 *
 * ## Que se esta fijando
 *
 * `unlinkSessionFromProject` se escribio HOY porque no existia, y su ausencia la yo mismo anote
 * como "desvincular sigue sin estar soportado por el store". Era verdad cuando lo escribi, y era
 * la razon de que el boton de desvincular no hiciera nada. Un boton que no hace nada es peor que
 * uno que avisa, y aqui no avisaba de nada.
 */
class ProjectsStoreVinculoTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private var currentTime = 1_700_000_000_000L

    private fun storeNuevo(nombre: String): ProjectsStore =
        ProjectsStore(
            storeFile = tempFolder.newFile(nombre),
            legacyFile = tempFolder.newFile(nombre + ".legacy"),
            clock = { currentTime }
        )

    private fun proyecto(id: String, carpeta: String) =
        ProjectsStore.ProjectEntry(id = id, name = id, folder = carpeta)

    @Test
    fun `desvincular quita el vinculo de ESE proyecto`() {
        val s = storeNuevo("p1.json")
        s.saveProject(proyecto("p1", "/a"))
        s.saveProject(proyecto("p2", "/b"))
        s.linkSessionToProject("ses_x", "p1")

        assertTrue("estaba vinculada a p1", s.unlinkSessionFromProject("ses_x", "p1"))

        assertNull("el vinculo desaparece", s.getProjectIdForSession("ses_x", null))
    }

    @Test
    fun `desvincular desde el proyecto EQUIVOCO no toca el vinculo`() {
        // El contraejemplo del anterior. Sin esto, `unlinkSessionFromProject` podria ser un
        // `remove(sessionId)` a secas: desvincular desde el proyecto viejo dejaria la sesion
        // huerfana si ya se habia movido al nuevo, y el usuario perderia el enlace sin enterarse.
        val s = storeNuevo("p2.json")
        s.saveProject(proyecto("p1", "/a"))
        s.saveProject(proyecto("p2", "/b"))
        s.linkSessionToProject("ses_x", "p2")

        assertFalse(
            "desvincular desde p1 no puede quitar un vinculo que apunta a p2",
            s.unlinkSessionFromProject("ses_x", "p1")
        )
        assertEquals("el vinculo a p2 sigue entero", "p2", s.getProjectIdForSession("ses_x", null))
    }

    @Test
    fun `getSessionIdsForProject es la INVERSION del mapa`() {
        // El store guarda sesion -> proyecto. Sin invertirlo, `getProjectSessions` no tendria de
        // donde sacar la lista de un proyecto y solo podria leer el registro entero.
        val s = storeNuevo("inv.json")
        s.saveProject(proyecto("p1", "/a"))
        s.saveProject(proyecto("p2", "/b"))
        s.linkSessionToProject("ses_1", "p1")
        s.linkSessionToProject("ses_2", "p1")
        s.linkSessionToProject("ses_3", "p2")

        assertEquals(
            "p1 tiene 2 sesiones",
            setOf("ses_1", "ses_2"),
            s.getSessionIdsForProject("p1").toSet()
        )
        assertEquals("p2 tiene 1", listOf("ses_3"), s.getSessionIdsForProject("p2"))
        assertTrue(
            "un proyecto sin sesiones da vacio, no error",
            s.getSessionIdsForProject("no_existe").isEmpty()
        )
    }

    @Test
    fun `desvincular NO borra el titulo, que es de la sesion y no del vinculo`() {
        // Si el titulo se borrara aqui, volver a vincular la misma sesion la devolveria sin
        // nombre. Es peor dejar un dato de mas que perder el nombre de un chat.
        val s = storeNuevo("tit.json")
        s.saveProject(proyecto("p1", "/a"))
        s.linkSessionToProject("ses_x", "p1")
        s.setSessionTitle("ses_x", "El chat del movil")

        s.unlinkSessionFromProject("ses_x", "p1")

        assertEquals("el titulo sobrevive", "El chat del movil", s.getSessionTitle("ses_x"))
    }

    @Test
    fun `el vinculo y el titulo sobreviven a reabrir el store desde disco`() {
        // Un store que solo vive en RAM pierde el trabajo en cuanto la app muere: la lista de
        // chats se vaciaria al reabrir. Este es el fallo que haria "los chats no cargan" un dia
        // mas tarde, cuando el sintoma ya no tiene nada que ver con su causa.
        val fichero = tempFolder.newFile("persist.json")
        val s1 = ProjectsStore(
            storeFile = fichero,
            legacyFile = tempFolder.newFile("persist.legacy"),
            clock = { currentTime }
        )
        s1.saveProject(proyecto("p1", "/a"))
        s1.linkSessionToProject("ses_x", "p1")
        s1.setSessionTitle("ses_x", "Persistido")

        val s2 = ProjectsStore(
            storeFile = fichero,
            legacyFile = tempFolder.newFile("persist2.legacy"),
            clock = { currentTime }
        )
        assertEquals("el vinculo se lee del disco", "p1", s2.getProjectIdForSession("ses_x", null))
        assertEquals("el titulo tambien", "Persistido", s2.getSessionTitle("ses_x"))
        assertEquals(listOf("ses_x"), s2.getSessionIdsForProject("p1"))
    }
}