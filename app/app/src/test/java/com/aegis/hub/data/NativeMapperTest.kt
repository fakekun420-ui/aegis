package com.aegis.hub.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests unitarios JVM exhaustivos para [NativeMapper].
 * Cada afirmación cuenta con su respectivo contraejemplo.
 */
class NativeMapperTest {

    private val fixedNow = 1727800000000L

    @Test
    fun `mensaje con content de un solo type text produce exactamente una parte con su texto`() {
        val native = OpenCodeMessage(
            id = "msg_001",
            role = "user",
            content = listOf(
                OpenCodeMessagePart(
                    id = "prt_custom_1",
                    type = "text",
                    text = "Hola mundo"
                )
            )
        )

        val mapped = NativeMapper.toMessage(native, sessionId = "ses_test", index = 0, nowSupplier = { fixedNow })

        assertEquals("msg_001", mapped.info?.id)
        assertEquals("user", mapped.role)
        assertEquals("Hola mundo", mapped.text)
        assertNotNull(mapped.parts)
        assertEquals(1, mapped.parts!!.size)
        val p = mapped.parts!![0]
        assertEquals("prt_custom_1", p.id)
        assertEquals("text", p.type)
        assertEquals("Hola mundo", p.text)
    }

    @Test
    fun `mensaje con content de tres tipos distintos text, reasoning y tool produce tres partes en orden`() {
        val dummyToolState = ToolState(
            status = "completed",
            input = mapOf("command" to "ls -la"),
            output = "file1.txt"
        )

        val native = OpenCodeMessage(
            id = "msg_002",
            role = "assistant",
            content = listOf(
                OpenCodeMessagePart(
                    id = "prt_1",
                    type = "reasoning",
                    text = "Pensando la respuesta..."
                ),
                OpenCodeMessagePart(
                    id = "prt_2",
                    type = "tool",
                    tool = "bash",
                    callID = "call_abc",
                    state = dummyToolState
                ),
                OpenCodeMessagePart(
                    id = "prt_3",
                    type = "text",
                    text = "Resultado final"
                )
            )
        )

        val mapped = NativeMapper.toMessage(native, sessionId = "ses_test", index = 1, nowSupplier = { fixedNow })

        assertEquals("assistant", mapped.role)
        assertNotNull(mapped.parts)
        assertEquals(3, mapped.parts!!.size)

        // Parte 0: reasoning
        assertEquals("prt_1", mapped.parts!![0].id)
        assertEquals("reasoning", mapped.parts!![0].type)
        assertEquals("Pensando la respuesta...", mapped.parts!![0].text)

        // Parte 1: tool
        assertEquals("prt_2", mapped.parts!![1].id)
        assertEquals("tool", mapped.parts!![1].type)
        assertEquals("bash", mapped.parts!![1].tool)
        assertEquals("call_abc", mapped.parts!![1].callID)
        assertEquals("completed", mapped.parts!![1].state?.status)
        assertEquals("file1.txt", mapped.parts!![1].state?.output)

        // Parte 2: text
        assertEquals("prt_3", mapped.parts!![2].id)
        assertEquals("text", mapped.parts!![2].type)
        assertEquals("Resultado final", mapped.parts!![2].text)

        // El helper text de Message sólo concatena type=text
        assertEquals("Resultado final", mapped.text)
    }

    @Test
    fun `mensaje con content vacio o nulo genera una sola parte de texto vacia y sintetica`() {
        val native = OpenCodeMessage(
            id = "msg_003",
            role = "assistant",
            content = emptyList()
        )

        val mapped = NativeMapper.toMessage(native, sessionId = "ses_test", index = 2, nowSupplier = { fixedNow })

        assertNotNull(mapped.parts)
        assertEquals(1, mapped.parts!!.size)
        assertEquals("prt_${fixedNow}_0", mapped.parts!![0].id)
        assertEquals("text", mapped.parts!![0].type)
        assertEquals("", mapped.parts!![0].text)
    }

    @Test
    fun `mensaje con files y source uri genera parte type file con url y mime`() {
        val native = OpenCodeMessage(
            id = "msg_004",
            type = "user",
            files = listOf(
                OpenCodeFileAttachment(
                    id = "file_123",
                    name = "foto.png",
                    mime = "image/png",
                    source = OpenCodePartSource(type = "uri", uri = "file:///sdcard/dcim/foto.png")
                )
            )
        )

        val mapped = NativeMapper.toMessage(native, sessionId = "ses_test", index = 3, nowSupplier = { fixedNow })

        assertEquals("user", mapped.role)
        assertNotNull(mapped.parts)
        // Al no haber parte de texto, debe anteponer una vacía: total 2 partes
        assertEquals(2, mapped.parts!!.size)

        // 1ª parte: texto antepuesto vacío
        assertEquals("prt_msg_004_t", mapped.parts!![0].id)
        assertEquals("text", mapped.parts!![0].type)
        assertEquals("", mapped.parts!![0].text)

        // 2ª parte: archivo
        val filePart = mapped.parts!![1]
        assertEquals("file_123", filePart.id)
        assertEquals("file", filePart.type)
        assertEquals("foto.png", filePart.filename)
        assertEquals("image/png", filePart.mime)
        assertEquals("file:///sdcard/dcim/foto.png", filePart.url)
    }

    @Test
    fun `mensaje con files en base64 genera data uri y usa fallback application octet-stream si mime es nulo`() {
        val native = OpenCodeMessage(
            id = "msg_005",
            type = "user",
            files = listOf(
                OpenCodeFileAttachment(
                    name = "binario.dat",
                    data = "AQIDBA=="
                    // mime ausente a propósito
                )
            )
        )

        val mapped = NativeMapper.toMessage(native, sessionId = "ses_test", index = 4, nowSupplier = { fixedNow })

        assertNotNull(mapped.parts)
        assertEquals(2, mapped.parts!!.size) // texto antepuesto + archivo
        val filePart = mapped.parts!![1]
        assertEquals("prt_msg_005_f0", filePart.id)
        assertEquals("file", filePart.type)
        assertEquals("application/octet-stream", filePart.mime)
        assertEquals("data:application/octet-stream;base64,AQIDBA==", filePart.url)
    }

    @Test
    fun `contraejemplo critico de bug de adjuntos - mensaje con files y con texto NO antepone una segunda parte de texto vacia`() {
        val native = OpenCodeMessage(
            id = "msg_006",
            type = "user",
            content = listOf(
                OpenCodeMessagePart(
                    id = "prt_texto_real",
                    type = "text",
                    text = "Mira este archivo adjunto"
                )
            ),
            files = listOf(
                OpenCodeFileAttachment(
                    id = "file_doc",
                    name = "doc.pdf",
                    mime = "application/pdf",
                    source = OpenCodePartSource(type = "uri", uri = "file:///doc.pdf")
                )
            )
        )

        val mapped = NativeMapper.toMessage(native, sessionId = "ses_test", index = 5, nowSupplier = { fixedNow })

        assertNotNull(mapped.parts)
        // Debe tener exactamente 2 partes: el texto original y el archivo. NO 3 partes.
        assertEquals(2, mapped.parts!!.size)
        assertEquals("prt_texto_real", mapped.parts!![0].id)
        assertEquals("text", mapped.parts!![0].type)
        assertEquals("Mira este archivo adjunto", mapped.parts!![0].text)

        assertEquals("file_doc", mapped.parts!![1].id)
        assertEquals("file", mapped.parts!![1].type)
        assertEquals("doc.pdf", mapped.parts!![1].filename)
        assertEquals("file:///doc.pdf", mapped.parts!![1].url)
    }

    @Test
    fun `rol ausente y type user resuelve a user`() {
        val native = OpenCodeMessage(
            id = "msg_user",
            type = "user",
            content = listOf(OpenCodeMessagePart(text = "pregunta"))
        )

        val mapped = NativeMapper.toMessage(native, sessionId = "ses_test", index = 0, nowSupplier = { fixedNow })
        assertEquals("user", mapped.role)
    }

    @Test
    fun `rol ausente y type distinto de user resuelve a assistant`() {
        val nativeBot = OpenCodeMessage(
            id = "msg_bot",
            type = "bot",
            content = listOf(OpenCodeMessagePart(text = "respuesta"))
        )

        val mapped = NativeMapper.toMessage(nativeBot, sessionId = "ses_test", index = 0, nowSupplier = { fixedNow })
        assertEquals("assistant", mapped.role)
    }

    @Test
    fun `rol invalido se normaliza forzosamente a assistant`() {
        val nativeInvalid = OpenCodeMessage(
            id = "msg_invalid",
            role = "system_custom_unknown",
            content = listOf(OpenCodeMessagePart(text = "test"))
        )

        val mapped = NativeMapper.toMessage(nativeInvalid, sessionId = "ses_test", index = 0, nowSupplier = { fixedNow })
        assertEquals("assistant", mapped.role)
    }

    @Test
    fun `timestamp ausente en time no revienta y utiliza nowSupplier determinista`() {
        val native = OpenCodeMessage(
            id = "msg_no_time",
            role = "user",
            time = null,
            content = listOf(OpenCodeMessagePart(text = "sin fecha"))
        )

        val mapped = NativeMapper.toMessage(native, sessionId = "ses_test", index = 0, nowSupplier = { fixedNow })

        assertEquals(fixedNow, mapped.info?.time?.get("created"))
    }

    @Test
    fun `timestamp presente en time created se preserva y conserva updated e idle`() {
        val native = OpenCodeMessage(
            id = "msg_with_time",
            role = "assistant",
            time = OpenCodeTime(created = 1000L, updated = 2000L, idle = 3000L),
            content = listOf(OpenCodeMessagePart(text = "con fecha"))
        )

        val mapped = NativeMapper.toMessage(native, sessionId = "ses_test", index = 0, nowSupplier = { fixedNow })

        assertEquals(1000L, mapped.info?.time?.get("created"))
        assertEquals(2000L, mapped.info?.time?.get("updated"))
        assertEquals(3000L, mapped.info?.time?.get("idle"))
    }

    @Test
    fun `toMessages mapea lista completa preservando orden e indices`() {
        val list = listOf(
            OpenCodeMessage(id = "m1", role = "user", content = listOf(OpenCodeMessagePart(text = "p1"))),
            OpenCodeMessage(id = "m2", role = "assistant", content = listOf(OpenCodeMessagePart(text = "r1")))
        )

        val mappedList = NativeMapper.toMessages(list, sessionId = "ses_1", nowSupplier = { fixedNow })

        assertEquals(2, mappedList.size)
        assertEquals("m1", mappedList[0].info?.id)
        assertEquals("user", mappedList[0].role)
        assertEquals("m2", mappedList[1].info?.id)
        assertEquals("assistant", mappedList[1].role)
    }

    @Test
    fun `streamDeltaToPart convierte texto de evento SSE a MessagePart correctamente`() {
        val deltaPart = NativeMapper.streamDeltaToPart(
            text = "token incremental",
            partId = "prt_stream_1",
            timestamp = fixedNow
        )

        assertEquals("prt_stream_1", deltaPart.id)
        assertEquals("text", deltaPart.type)
        assertEquals("token incremental", deltaPart.text)
    }
}
