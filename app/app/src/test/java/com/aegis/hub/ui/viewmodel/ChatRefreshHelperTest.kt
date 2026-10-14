package com.aegis.hub.ui.viewmodel

import com.aegis.hub.data.Message
import com.aegis.hub.data.MessageInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G4/F9: Tests JVM para ChatRefreshHelper.
 */
class ChatRefreshHelperTest {

    private fun msg(id: String): Message =
        Message(info = MessageInfo(id = id, role = "user"))

    @Test
    fun `mergeTail fusiona listas preservando orden y sin duplicar`() {
        val actual = listOf(msg("m1"), msg("m2"))
        val cola = listOf(msg("m2"), msg("m3"))
        val fusion = ChatRefreshHelper.mergeTail(actual, cola)
        assertEquals(3, fusion.size)
        assertEquals(listOf("m1", "m2", "m3"), fusion.mapNotNull { it.info?.id })
    }

    @Test
    fun `mergeTail con cola vacia devuelve actual`() {
        val actual = listOf(msg("m1"))
        val fusion = ChatRefreshHelper.mergeTail(actual, emptyList())
        assertEquals(actual, fusion)
    }

    @Test
    fun `esperaRefrescoMs escala a 30s al tercer fallo`() {
        assertEquals(2000L, ChatRefreshHelper.esperaRefrescoMs(0))
        assertEquals(2000L, ChatRefreshHelper.esperaRefrescoMs(1))
        assertEquals(2000L, ChatRefreshHelper.esperaRefrescoMs(2))
        assertEquals(30000L, ChatRefreshHelper.esperaRefrescoMs(3))
        assertEquals(30000L, ChatRefreshHelper.esperaRefrescoMs(10))
    }

    @Test
    fun `puedeDetenerRefresco respeta propietario`() {
        assertTrue("sin dueno vale", ChatRefreshHelper.puedeDetenerRefresco(null, "s1"))
        assertTrue("mismo dueno vale", ChatRefreshHelper.puedeDetenerRefresco("s1", "s1"))
        assertFalse("otro dueno no vale", ChatRefreshHelper.puedeDetenerRefresco("s2", "s1"))
    }
}
