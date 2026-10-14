package com.aegis.hub.ui.viewmodel

import com.aegis.hub.data.Message
import com.aegis.hub.data.MessageInfo
import com.aegis.hub.data.MessagePart
import com.aegis.hub.data.ToolState
import com.aegis.hub.data.PendingForm
import com.aegis.hub.data.PendingPermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G4/F9: Pruebas unitarias puras para ChatTurnHelper.
 */
class ChatTurnHelperTest {

    private fun mensaje(id: String?, role: String, running: Boolean = false, completed: Boolean = false): Message {
        val timeMap = if (completed) mapOf("completed" to 123456L) else emptyMap()
        val parts = if (running) {
            listOf(MessagePart(type = "tool", state = ToolState(status = "running")))
        } else {
            listOf(MessagePart(type = "text", text = "hola"))
        }
        return Message(
            info = MessageInfo(id = id, role = role, time = timeMap),
            parts = parts
        )
    }

    @Test
    fun `herramienta running veta el fin de turno`() {
        val msgs = listOf(mensaje("1", "assistant", running = true, completed = true))
        val fin = ChatTurnHelper.turnIsReallyFinished(
            messages = msgs,
            pendingForms = emptyList(),
            pendingPermissions = emptyList(),
            turnBusy = false,
            turnOver = true
        )
        assertFalse("running veta incluso con turnOver=true", fin)
    }

    @Test
    fun `formulario pendiente veta el fin de turno`() {
        val msgs = listOf(mensaje("1", "assistant", running = false, completed = true))
        val form = PendingForm(id = "f1", sessionID = "s1")
        val fin = ChatTurnHelper.turnIsReallyFinished(
            messages = msgs,
            pendingForms = listOf(form),
            pendingPermissions = emptyList(),
            turnBusy = false,
            turnOver = true
        )
        assertFalse("formulario pendiente veta", fin)
    }

    @Test
    fun `permiso pendiente veta el fin de turno`() {
        val msgs = listOf(mensaje("1", "assistant", running = false, completed = true))
        val perm = PendingPermission(id = "p1", sessionID = "s1")
        val fin = ChatTurnHelper.turnIsReallyFinished(
            messages = msgs,
            pendingForms = emptyList(),
            pendingPermissions = listOf(perm),
            turnBusy = false,
            turnOver = true
        )
        assertFalse("permiso pendiente veta", fin)
    }

    @Test
    fun `turnBusy veta el fin de turno`() {
        val msgs = listOf(mensaje("1", "assistant", running = false, completed = true))
        val fin = ChatTurnHelper.turnIsReallyFinished(
            messages = msgs,
            pendingForms = emptyList(),
            pendingPermissions = emptyList(),
            turnBusy = true,
            turnOver = false
        )
        assertFalse("turnBusy veta", fin)
    }

    @Test
    fun `turnOver confirma el fin de turno si no hay vetos`() {
        val msgs = listOf(mensaje("1", "assistant", running = false, completed = false))
        val fin = ChatTurnHelper.turnIsReallyFinished(
            messages = msgs,
            pendingForms = emptyList(),
            pendingPermissions = emptyList(),
            turnBusy = false,
            turnOver = true
        )
        assertTrue("turnOver da true sin vetos", fin)
    }

    @Test
    fun `modo degradado se apoya en completed del asistente`() {
        var advertido = false
        val msgs = listOf(mensaje("1", "assistant", running = false, completed = true))
        val fin = ChatTurnHelper.turnIsReallyFinished(
            messages = msgs,
            pendingForms = emptyList(),
            pendingPermissions = emptyList(),
            turnBusy = false,
            turnOver = false,
            onWarnNoWatcher = { advertido = true }
        )
        assertTrue("asistente completado da true en degradado", fin)
        assertTrue("se emitio advertencia", advertido)
    }

    @Test
    fun `announceFinishedTurnIfAny no repite aviso con el mismo id`() {
        var notificado = 0
        val msgs = listOf(mensaje("a1", "assistant", completed = true))
        val res1 = ChatTurnHelper.announceFinishedTurnIfAny(
            fresh = msgs,
            lastFinishedTurnId = null,
            isFinished = true,
            sessionTitle = "Mi Chat",
            sessionId = "s1",
            notify = { _, _, _ -> notificado++ }
        )
        assertEquals("a1", res1)
        assertEquals(1, notificado)

        val res2 = ChatTurnHelper.announceFinishedTurnIfAny(
            fresh = msgs,
            lastFinishedTurnId = "a1",
            isFinished = true,
            sessionTitle = "Mi Chat",
            sessionId = "s1",
            notify = { _, _, _ -> notificado++ }
        )
        assertEquals("a1", res2)
        assertEquals("no vuelve a notificar", 1, notificado)
    }
}
