/* ==================================================================================
 * F4 — tests JVM de ToolState (Models.kt): outputText, toolName, isSubagent.
 *
 * Estos tests validan las propiedades de ToolState que la UI usa para pintar
 * la tarjeta de una herramienta. El runner es JUnit (no instrumentado), así que
 * corren en la CI sin emulador.
 * ================================================================================== */

package com.aegis.hub.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ToolStateTest {

    // ---------- outputText ----------
    /** Devuelve el legacy `output` si tiene contenido; si no, aplana `content`. */
    @Test
    fun `outputText devuelve output legacy cuando existe`() {
        val ts = ToolState(output = "legacy output", content = null)
        assertEquals("legacy output", ts.outputText)
    }

    @Test
    fun `outputText devuelve contenido aplastado cuando output está vacío`() {
        val ts = ToolState(output = null, content = listOf(ToolContentBlock(text = "from content")))
        assertEquals("from content", ts.outputText)
    }

    @Test
    fun `outputText devuelve vacío cuando no hay ni output ni content`() {
        val ts = ToolState(output = null, content = null)
        // `outputText` esta declarado `String?`, asi que aqui lo correcto es null y no "".
        // El test esperaba "" desde antes de que existiera la propiedad nullable: nunca se
        // ejecuto, y nadie vio que la expectativa no cuadraba con el tipo declarado. El
        // consumidor (SubagentCard) trata null con `isNullOrBlank()`, que cubre ambos.
        assertNull(ts.outputText)
    }

    @Test
    fun `outputText ignora bloques en blanco`() {
        val ts = ToolState(
            output = null,
            content = listOf(
                ToolContentBlock(text = ""),
                ToolContentBlock(text = "  "),
                ToolContentBlock(text = "real text")
            )
        )
        assertEquals("real text", ts.outputText)
    }

    // ---------- isSubagent ----------
    /** Una tool que trae `agent` en su input es una delegación a subagente. */
    @Test
    fun `isSubagent devuelve verdadero cuando input tiene "agent"`() {
        val ts = ToolState(input = mapOf("agent" to "kaenor-ai-engineer"), content = null)
        assertTrue(ts.isSubagent)
    }

    @Test
    fun `isSubagent devuelve falso cuando input no tiene "agent"`() {
        val ts = ToolState(input = mapOf("command" to "ls -la"), content = null)
        assertFalse(ts.isSubagent)
    }

    @Test
    fun `isSubagent devuelve falso cuando input es nulo`() {
        val ts = ToolState(output = "some output", content = null)
        assertFalse(ts.isSubagent)
    }

    // ---------- toolName ----------
    /** Si input tiene "command"/"CommandLine"/"cmd", se infiere "bash". */
    @Test
    fun `toolName devuelve "bash" cuando hay command`() {
        val ts = ToolState(input = mapOf("command" to "ls -la"), content = null)
        assertEquals("bash", ts.toolName)
    }

    @Test
    fun `toolName devuelve "bash" cuando hay CommandLine`() {
        val ts = ToolState(input = mapOf("CommandLine" to "/bin/bash"), content = null)
        assertEquals("bash", ts.toolName)
    }

    @Test
    fun `toolName devuelve "bash" cuando hay cmd`() {
        val ts = ToolState(input = mapOf("cmd" to "  pwd  "), content = null)
        assertEquals("bash", ts.toolName)
    }

    /** Si input tiene "agent", dice "subagente" (sobrepasa a command). */
    @Test
    fun `toolName devuelve "subagente" cuando hay agent`() {
        val ts = ToolState(input = mapOf("agent" to "any", "command" to "ls"), content = null)
        assertEquals("subagente", ts.toolName)
    }

    /** Si input tiene "oldString"/"newString", dice "edit". */
    @Test
    fun `toolName devuelve "edit" cuando hay oldString`() {
        val ts = ToolState(input = mapOf("oldString" to "old", "newString" to "new"), content = null)
        assertEquals("edit", ts.toolName)
    }

    /** Si input tiene "path" y "content", dice "write". */
    @Test
    fun `toolName devuelve "write" cuando hay path y content`() {
        val ts = ToolState(input = mapOf("path" to "/tmp/x", "content" to "hola"), content = null)
        assertEquals("write", ts.toolName)
    }

    /** Si input tiene "path" y "pattern"/"glob", dice "search". */
    @Test
    fun `toolName devuelve "search" cuando hay path y pattern`() {
        val ts = ToolState(input = mapOf("path" to "/tmp", "pattern" to "hola"), content = null)
        assertEquals("search", ts.toolName)
    }

    /** Si input tiene "path", dice "read". */
    @Test
    fun `toolName devuelve "read" cuando hay path sin otras claves`() {
        val ts = ToolState(input = mapOf("path" to "/tmp/x"), content = null)
        assertEquals("read", ts.toolName)
    }

    /** Si input tiene "query" o "url", dice "web". */
    @Test
    fun `toolName devuelve "web" cuando hay query`() {
        val ts = ToolState(input = mapOf("query" to "test"), content = null)
        assertEquals("web", ts.toolName)
    }

    @Test
    fun `toolName devuelve "web" cuando hay url`() {
        val ts = ToolState(input = mapOf("url" to "https://example.com"), content = null)
        assertEquals("web", ts.toolName)
    }

    /** Si input tiene "id", dice "skill". */
    @Test
    fun `toolName devuelve "skill" cuando hay id`() {
        val ts = ToolState(input = mapOf("id" to "some-skill"), content = null)
        assertEquals("skill", ts.toolName)
    }

    /** Si no coincide ninguna clave, dice "herramienta". */
    @Test
    fun `toolName devuelve "herramienta" cuando no hay claves conocidas`() {
        val ts = ToolState(input = mapOf("foo" to "bar"), content = null)
        assertEquals("herramienta", ts.toolName)
    }
}