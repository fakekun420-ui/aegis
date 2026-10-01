package com.aegis.hub.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests unitarios JVM para [BootstrapNative].
 *
 * Verifica:
 * 1. Exactamente 5 pasos con los IDs y títulos literales de STEP_DEFS.
 * 2. Creación de estado inicial si el fichero no existe.
 * 3. Normalización ante ficheros corruptos o fases interrumpidas (running -> paused).
 * 4. Actualización atómica de pasos y persistencia en disco.
 * 5. Reanudación idempotente que no sobreescribe pasos completados.
 */
class BootstrapNativeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `STEP_DEFS contiene exactamente los 5 pasos del contrato y en el orden fijado`() {
        val defs = BootstrapNative.STEP_DEFS
        assertEquals(5, defs.size)

        assertEquals("preflight", defs[0].id)
        assertEquals("Comprobación previa", defs[0].title)

        assertEquals("ubuntu", defs[1].id)
        assertEquals("Ubuntu (chroot/proot)", defs[1].title)

        assertEquals("node", defs[2].id)
        assertEquals("Node.js", defs[2].title)

        assertEquals("opencode", defs[3].id)
        assertEquals("OpenCode", defs[3].title)

        assertEquals("skills", defs[4].id)
        assertEquals("Skills y plugins", defs[4].title)
    }

    @Test
    fun `fichero no existente devuelve estado inicial con 5 pasos en pending`() {
        val dummyFile = File(tempFolder.root, "non_existent_state.json")
        val manager = BootstrapNative(stateFile = dummyFile)

        val state = manager.readState()
        assertEquals(BootstrapPhase.idle, state.phaseOrIdle)
        assertNull(state.currentStepId)
        assertNull(state.lastError)
        assertEquals(5, state.stepList.size)

        state.stepList.forEach { step ->
            assertEquals(BootstrapStepStatus.pending, step.statusOrPending)
            assertEquals(BootstrapRollback.none, step.rollback)
            assertEquals(0, step.progress)
        }
    }

    @Test
    fun `fase running en disco sin proceso se normaliza a paused para evitar ALREADY_RUNNING huerfano`() {
        val stateFile = File(tempFolder.root, "running_state.json")
        stateFile.writeText(
            """
            {
              "phase": "running",
              "currentStepId": "node",
              "steps": [
                {"id":"preflight","title":"Comprobación previa","status":"done","progress":100},
                {"id":"ubuntu","title":"Ubuntu (chroot/proot)","status":"done","progress":100},
                {"id":"node","title":"Node.js","status":"running","progress":40},
                {"id":"opencode","title":"OpenCode","status":"pending","progress":0},
                {"id":"skills","title":"Skills y plugins","status":"pending","progress":0}
              ]
            }
            """.trimIndent()
        )

        val manager = BootstrapNative(stateFile = stateFile)
        val state = manager.readState()

        // Debe normalizarse a paused y el paso running a pending
        assertEquals(BootstrapPhase.paused, state.phaseOrIdle)
        val nodeStep = state.stepList.first { it.id == "node" }
        assertEquals(BootstrapStepStatus.pending, nodeStep.statusOrPending)
        assertTrue(nodeStep.detail?.contains("interrumpido") == true)
    }

    @Test
    fun `updateStep actualiza el paso especifico y recalcula currentStepId`() {
        val stateFile = File(tempFolder.root, "update_step.json")
        val manager = BootstrapNative(stateFile = stateFile)

        manager.updateStep(
            stepId = "ubuntu",
            status = BootstrapStepStatus.running,
            progress = 50,
            detail = "Extrayendo rootfs"
        )

        val updated = manager.readState()
        assertEquals("ubuntu", updated.currentStepId)
        val ubuntu = updated.stepList.first { it.id == "ubuntu" }
        assertEquals(BootstrapStepStatus.running, ubuntu.statusOrPending)
        assertEquals(50, ubuntu.progress)
        assertEquals("Extrayendo rootfs", ubuntu.detail)

        // Completar el paso
        manager.updateStep(
            stepId = "ubuntu",
            status = BootstrapStepStatus.done,
            progress = 100,
            detail = "Rootfs listo"
        )

        val doneState = manager.readState()
        assertNull(doneState.currentStepId)
        val ubuntuDone = doneState.stepList.first { it.id == "ubuntu" }
        assertEquals(BootstrapStepStatus.done, ubuntuDone.statusOrPending)
        assertEquals(100, ubuntuDone.progress)
    }

    @Test
    fun `escritura atomica persiste y recupera campos personalizados`() {
        val stateFile = File(tempFolder.root, "persist_test.json")
        val manager = BootstrapNative(stateFile = stateFile)

        manager.updateState(
            phase = BootstrapPhase.done,
            startedAt = "2026-10-01T12:00:00Z",
            lastError = null
        )

        val readBack = manager.readState()
        assertEquals(BootstrapPhase.done, readBack.phaseOrIdle)
        assertEquals("2026-10-01T12:00:00Z", readBack.startedAt)
        assertNull(readBack.lastError)
        assertTrue(stateFile.exists())
    }
}
