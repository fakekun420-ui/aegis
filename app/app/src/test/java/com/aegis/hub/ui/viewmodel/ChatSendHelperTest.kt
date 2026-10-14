package com.aegis.hub.ui.viewmodel

import com.aegis.hub.data.AttachedFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * G4/F9: Pruebas unitarias puras para ChatSendHelper.
 */
class ChatSendHelperTest {

    @Test
    fun `buildSendKey concatena campos clave`() {
        val key = ChatSendHelper.buildSendKey(
            selectedProvider = "opencode",
            sessionId = "ses_123",
            text = "  hola mundo  ",
            filesCount = 2
        )
        assertEquals("opencode|ses_123|hola mundo|2", key)
    }

    @Test
    fun `computeProvisionalTitle solo actua si sesion es nueva`() {
        val t1 = ChatSendHelper.computeProvisionalTitle("Un mensaje largo para titular la sesion", "", null)
        assertEquals("Un mensaje largo para titular…", t1)

        val t2 = ChatSendHelper.computeProvisionalTitle("Mensaje", "ses_existente", null)
        assertNull("no actua con id de sesion", t2)
    }

    @Test
    fun `buildOptimisticMessage genera partes correctas`() {
        val files = listOf(
            AttachedFile(name = "test.txt", mime = "text/plain", size = 10, text = "contenido"),
            AttachedFile(name = "test.png", mime = "image/png", size = 20, base64 = "ABCD")
        )
        val msg = ChatSendHelper.buildOptimisticMessage("local_1", "hola", files)
        assertEquals("local_1", msg.info?.id)
        assertEquals(3, msg.parts?.size)
        assertEquals("text", msg.parts?.get(0)?.type)
        assertEquals("text", msg.parts?.get(1)?.type)
        assertEquals("file", msg.parts?.get(2)?.type)
    }

    @Test
    fun `buildRequestParts genera mapa de partes`() {
        val files = listOf(
            AttachedFile(name = "test.txt", mime = "text/plain", size = 10, text = "contenido")
        )
        val parts = ChatSendHelper.buildRequestParts("hola", files)
        assertEquals(2, parts.size)
        assertEquals("hola", parts[0]["text"])
    }
}
