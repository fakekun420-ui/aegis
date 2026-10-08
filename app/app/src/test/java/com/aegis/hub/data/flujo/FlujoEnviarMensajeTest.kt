package com.aegis.hub.data.flujo

import com.aegis.hub.data.ErroresRed
import com.aegis.hub.data.RutaNativa
import com.aegis.hub.data.SendMessageRequest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Flujo enviar mensaje (F10): fijar modelo -> prompt; el error del servidor
 * llega con motivo (cuerpo real parseado por ErroresRed).
 */
class FlujoEnviarMensajeTest {

    private lateinit var fake: FakeOpenCode

    @Before
    fun arrancar() {
        fake = FakeOpenCode()
    }

    @After
    fun parar() {
        fake.cerrar()
    }

    @Test
    fun `enviar fija el modelo y hace el prompt`() = runBlocking {
        val api = RutaNativa(oc = fake.api())
        val r = api.sendMessage(
            "ses_test",
            SendMessageRequest(parts = listOf(mapOf("type" to "text", "text" to "hola")), model = "m-srv")
        )

        assertTrue(fake.modelosFijados.isNotEmpty())
        assertTrue(fake.modelosFijados[0].contains("m-srv"))
        assertEquals(1, fake.contar("POST", "/api/session/ses_test/prompt"))
    }

    @Test
    fun `error del servidor visible con motivo`() = runBlocking {
        val api = RutaNativa(oc = fake.api())
        fake.modo = FakeOpenCode.Modo.SIN_SESION
        try {
            api.sendMessage(
                "ses_fantasma",
                SendMessageRequest(parts = listOf(mapOf("type" to "text", "text" to "hola")), model = "m-srv")
            )
            fail("tenia que lanzar HttpException")
        } catch (e: retrofit2.HttpException) {
            val cuerpo = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
            val motivo = ErroresRed.parsear(cuerpo, e.code())
            assertTrue("motivo real del cuerpo: $motivo", motivo.contains("Session not found"))
        }
    }
}
