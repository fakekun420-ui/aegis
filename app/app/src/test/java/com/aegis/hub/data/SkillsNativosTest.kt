package com.aegis.hub.data

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests unitarios para las estructuras y respuestas del subsistema nativo de Skills.
 * MEDIDO 2026-10-03: se valida la forma exacta de los contratos contra el JSON real
 * y modelos definidos en Models.kt.
 */
class SkillsNativosTest {

    private val gson = Gson()

    /**
     * JSON real medido de catalog.json del subsistema de skills.
     */
    private val CATALOG_JSON = """
        [
          {
            "id": "graphify",
            "name": "graphify",
            "description": "Convierte archivos en grafo de conocimiento (GraphRAG)"
          },
          {
            "id": "opencode-mem",
            "name": "opencode-mem",
            "description": "Memoria persistente para OpenCode",
            "version": "2.26.0",
            "sha256": "0182f1afce5a5769c3a790febc2fbf3f89bd6ca6e938717135173c3029076558"
          }
        ]
    """.trimIndent()

    /**
     * JSON real medido correspondiente a SkillsResponse emitido para la UI.
     */
    private val SKILLS_RESPONSE_JSON = """
        {
          "ok": true,
          "data": {
            "installed": [
              {
                "id": "graphify",
                "name": "graphify",
                "version": null,
                "description": "Skill local (graphify)",
                "installed": true,
                "enabled": true
              }
            ],
            "available": [
              {
                "id": "opencode-mem",
                "name": "opencode-mem",
                "version": "2.26.0",
                "description": "Memoria persistente para OpenCode",
                "installed": false,
                "enabled": true
              }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `SkillsResponse deserializa correctamente lista de instalados y disponibles`() {
        val resp = gson.fromJson(SKILLS_RESPONSE_JSON, SkillsResponse::class.java)
        assertTrue(resp.ok)
        val data = resp.data
        assertNotNull(data)
        assertEquals(1, data?.installed?.size)
        assertEquals(1, data?.available?.size)

        val inst = data?.installed?.first()
        assertEquals("graphify", inst?.id)
        assertTrue(inst?.installed == true)
        assertTrue(inst?.enabled == true)

        val avail = data?.available?.first()
        assertEquals("opencode-mem", avail?.id)
        assertEquals("2.26.0", avail?.version)
        assertFalse(avail?.installed == true)
    }

    @Test
    fun `TaskResponse modela fallo sin taskId en installSkill`() {
        val json = """{"ok":false,"data":{"taskId":null,"message":"Hub retirado"}}"""
        val resp = gson.fromJson(json, TaskResponse::class.java)
        assertFalse(resp.ok)
        assertNull(resp.data?.taskId)
        assertEquals("Hub retirado", resp.data?.message)
    }

    @Test
    fun `SkillCreateRequest y Skill tienen los campos esperados`() {
        val req = SkillCreateRequest(scope = "global", name = "mi-skill", content = "# Test")
        assertEquals("global", req.scope)
        assertEquals("mi-skill", req.name)
        assertEquals("# Test", req.content)

        val skill = Skill(scope = req.scope, name = req.name, content = req.content)
        assertEquals("global", skill.scope)
        assertEquals("mi-skill", skill.name)
        assertEquals("# Test", skill.content)
    }

    @Test
    fun `BaseResponse maneja respuesta de exito y error con ErrorBody`() {
        val okJson = """{"ok":true}"""
        val okResp = gson.fromJson(okJson, BaseResponse::class.java)
        assertTrue(okResp.ok)
        assertNull(okResp.error)

        val errJson = """{"ok":false,"error":{"code":"SKILL_INVALID","message":"id invalido"}}"""
        val errResp = gson.fromJson(errJson, BaseResponse::class.java)
        assertFalse(errResp.ok)
        assertEquals("SKILL_INVALID", errResp.error?.code)
        assertEquals("id invalido", errResp.error?.message)
    }
}
