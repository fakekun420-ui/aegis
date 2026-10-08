package com.aegis.hub.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests JVM para [ErroresRed] (F1, T-F1.4).
 *
 * Los dos primeros casos leen los fixtures capturados contra `:49374` vivo; el resto
 * son formas borde (vacio, HTML, no-JSON, sobre viejo) con el cuerpo inline.
 */
class ErroresRedTest {

    private fun fixture(nombre: String): String =
        javaClass.classLoader!!.getResourceAsStream("errores/$nombre")!!
            .bufferedReader().readText()

    @Test
    fun `sesion inexistente real da tag y mensaje`() {
        val motivo = ErroresRed.parsear(fixture("sesion-inexistente-404.json"), 404)
        assertEquals("SessionNotFoundError: Session not found: ses_noexiste123", motivo)
    }

    @Test
    fun `sin credenciales real da tag y mensaje`() {
        val motivo = ErroresRed.parsear(fixture("sin-credenciales-401.json"), 401)
        assertEquals("UnauthorizedError: Authentication required", motivo)
    }

    @Test
    fun `404 vacio real explica que pudo borrarse`() {
        assertEquals(
            "No existe en el servidor (404): la sesión pudo borrarse desde el CLI",
            ErroresRed.parsear("", 404)
        )
    }

    @Test
    fun `vacio sin codigo da el generico`() {
        assertEquals("El servidor no aceptó el mensaje", ErroresRed.parsear(null, null))
    }

    @Test
    fun `HTML no se muestra crudo`() {
        val motivo = ErroresRed.parsear("<html><body>Not Found</body></html>", 404)
        assertTrue(motivo.contains("HTML"))
    }

    @Test
    fun `no-JSON pasa recortado`() {
        assertEquals("timeout tras 10s", ErroresRed.parsear("timeout tras 10s", null))
    }

    @Test
    fun `sobre viejo con code y message`() {
        val motivo = ErroresRed.parsear(
            """{"ok":false,"error":{"code":"CUOTA","message":"sin cuota"}}""",
            429
        )
        assertEquals("CUOTA: sin cuota", motivo)
    }
}
