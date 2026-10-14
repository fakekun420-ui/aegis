package com.aegis.hub.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G4/F9: Tests JVM para UiStates de ChatScreen.
 */
class ChatUiStatesTest {

    @Test
    fun `defaults de ChatConversationUiState son coherentes`() {
        val s = ChatConversationUiState()
        assertTrue(s.messages.isEmpty())
        assertEquals("Nuevo chat", s.sessionTitle)
        assertFalse(s.turnInProgress)
        assertFalse(s.turnFinished)
    }

    @Test
    fun `defaults de ChatDeliveryUiState son coherentes`() {
        val s = ChatDeliveryUiState()
        assertFalse(s.loading)
        assertFalse(s.sendingInFlight)
        assertTrue(s.servidorAlcanzable)
        assertTrue(s.streamingTools.isEmpty())
    }

    @Test
    fun `defaults de ChatConfigUiState son coherentes`() {
        val s = ChatConfigUiState()
        assertTrue(s.models.isEmpty())
        assertEquals("build", s.agentMode)
        assertTrue(s.agents.isEmpty())
        assertFalse(s.modelsLoading)
    }

    @Test
    fun `defaults de ChatInteractionUiState son coherentes`() {
        val s = ChatInteractionUiState()
        assertTrue(s.pendingForms.isEmpty())
        assertTrue(s.pendingPermissions.isEmpty())
        assertFalse(s.replyingForm)
        assertFalse(s.replyingPermission)
    }
}
