package com.aegis.hub.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests unitarios JVM para verificar la serialización/deserialización de [NativeModels]
 * y el contrato de datos real medido de OpenCode v2.
 */
class NativeModelsTest {

    private val gson = Gson()

    @Test
    fun `GET session id plano se deserializa sin envoltorio ok`() {
        val json = """
            {
              "id": "ses_test123",
              "title": "aegis direct session",
              "agent": "orchestrator",
              "model": {
                "id": "space-bunny-free",
                "providerID": "opencode",
                "variant": "max"
              },
              "outcome": "succeeded",
              "cost": 0.0,
              "projectID": "681834abcdef",
              "time": {
                "created": 1790649234306,
                "updated": 1790649240000,
                "idle": 1790649250000
              },
              "tokens": {
                "input": 1500,
                "output": 350,
                "reasoning": 50
              },
              "location": {
                "directory": "/sdcard/projects"
              }
            }
        """.trimIndent()

        val session = gson.fromJson(json, OpenCodeSession::class.java)

        assertNotNull(session)
        assertEquals("ses_test123", session.id)
        assertEquals("aegis direct session", session.title)
        assertEquals("orchestrator", session.agent)
        assertNotNull(session.model)
        assertEquals("space-bunny-free", session.model?.id)
        assertEquals("opencode", session.model?.providerID)
        assertEquals("max", session.model?.variant)
        assertEquals(1790649234306L, session.time?.created)
        assertEquals(1500L, session.tokens?.input)
        assertEquals(350L, session.tokens?.output)
        assertEquals("/sdcard/projects", session.location?.directory)
    }

    @Test
    fun `GET session active se deserializa como mapa plano ses_id a status`() {
        val json = """
            {
              "ses_running_1": { "type": "running" },
              "ses_idle_2": { "type": "idle" }
            }
        """.trimIndent()

        val type = object : TypeToken<Map<String, ActiveSessionStatus>>() {}.type
        val activeSessions: Map<String, ActiveSessionStatus> = gson.fromJson(json, type)

        assertEquals(2, activeSessions.size)
        assertEquals("running", activeSessions["ses_running_1"]?.type)
        assertEquals("idle", activeSessions["ses_idle_2"]?.type)
    }

    @Test
    fun `OpenCodeMessage con content de partes se deserializa preservando tipos y herramientas`() {
        val json = """
            {
              "id": "msg_001",
              "sessionID": "ses_test123",
              "type": "assistant",
              "time": { "created": 1790649234500 },
              "content": [
                {
                  "type": "text",
                  "text": "Iniciando revision de archivos..."
                },
                {
                  "type": "tool",
                  "tool": "read",
                  "callID": "call_987",
                  "state": {
                    "status": "completed",
                    "input": { "path": "/sdcard/projects/test.txt" },
                    "content": [
                      { "type": "text", "text": "contenido de prueba" }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        val message = gson.fromJson(json, OpenCodeMessage::class.java)

        assertNotNull(message)
        assertEquals("msg_001", message.id)
        assertEquals("ses_test123", message.sessionID)
        assertEquals("assistant", message.type)
        assertEquals(2, message.content?.size)

        val textPart = message.content?.get(0)
        assertEquals("text", textPart?.type)
        assertEquals("Iniciando revision de archivos...", textPart?.text)

        val toolPart = message.content?.get(1)
        assertEquals("tool", toolPart?.type)
        assertEquals("read", toolPart?.tool)
        assertEquals("completed", toolPart?.state?.status)
        assertEquals("read", toolPart?.state?.toolName)
        assertEquals("/sdcard/projects/test.txt", toolPart?.state?.command)
        assertEquals("contenido de prueba", toolPart?.state?.outputText)
    }

    @Test
    fun `POST prompt ack asincrono con text vacio se deserializa correctamente`() {
        val json = """
            {
              "id": "ack_111",
              "type": "prompt_ack",
              "sessionID": "ses_test123",
              "delivery": "steer",
              "text": "",
              "time": {
                "created": 1790649235000
              }
            }
        """.trimIndent()

        val ack = gson.fromJson(json, OpenCodePromptAck::class.java)

        assertNotNull(ack)
        assertEquals("ack_111", ack.id)
        assertEquals("ses_test123", ack.sessionID)
        assertEquals("steer", ack.delivery)
        assertEquals("", ack.text)
        assertEquals(1790649235000L, ack.time?.created)
    }

    @Test
    fun `Eventos SSE parsean delta y ended correctamente`() {
        val deltaJson = """
            {
              "id": "ev_01",
              "created": 1790649235100,
              "type": "session.text.delta",
              "data": {
                "sessionID": "ses_test123",
                "delta": "fragmento de texto"
              }
            }
        """.trimIndent()

        val event = gson.fromJson(deltaJson, OpenCodeServerEvent::class.java)

        assertNotNull(event)
        assertEquals("session.text.delta", event.type)
        assertEquals("ses_test123", event.sessionID)
        assertEquals("fragmento de texto", event.textDelta)
        assertNull(event.endedText)
    }
}
