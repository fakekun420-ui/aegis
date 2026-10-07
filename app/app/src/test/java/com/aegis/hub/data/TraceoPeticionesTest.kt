package com.aegis.hub.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests JVM puros para [TraceoPeticiones] (F0, T-F0.3).
 *
 * No tocan Android ni red: [TraceoPeticiones.iniciarInformePeriodico] no se llama
 * aquí, y `android.util.Log` solo se usa en ese camino.
 */
class TraceoPeticionesTest {

    @Before
    fun preparar() {
        TraceoPeticiones.limpiar()
        TraceoPeticiones.activo = true
    }

    @After
    fun restaurar() {
        TraceoPeticiones.limpiar()
        TraceoPeticiones.activo = false
    }

    @Test
    fun `normaliza id de sesion`() {
        assertEquals(
            "api/session/:id",
            TraceoPeticiones.normalizarRuta("api/session/ses_abc123XYZ")
        )
    }

    @Test
    fun `normaliza id de mensaje`() {
        assertEquals(
            "api/session/:id/message/:id",
            TraceoPeticiones.normalizarRuta("api/session/ses_abc/message/msg_def456")
        )
    }

    @Test
    fun `normaliza id de parte y deja el resto intacto`() {
        assertEquals(
            "api/session/:id/part/:id",
            TraceoPeticiones.normalizarRuta("api/session/ses_abc/part/prt_789")
        )
    }

    @Test
    fun `rutas sin ids pasan tal cual`() {
        assertEquals("api/model", TraceoPeticiones.normalizarRuta("api/model"))
        assertEquals("api/event", TraceoPeticiones.normalizarRuta("api/event"))
    }

    @Test
    fun `resumen agrega por metodo y ruta normalizada`() {
        TraceoPeticiones.registrar("GET", "api/session/ses_aaa")
        TraceoPeticiones.registrar("GET", "api/session/ses_bbb")
        TraceoPeticiones.registrar("POST", "api/session/ses_aaa/model")

        val texto = TraceoPeticiones.resumen()
        assertTrue(texto.contains("GET api/session/:id=2"))
        assertTrue(texto.contains("POST api/session/:id/model=1"))
    }

    @Test
    fun `con activo en false no cuenta nada (comportamiento de release)`() {
        TraceoPeticiones.activo = false
        TraceoPeticiones.registrar("GET", "api/session/ses_aaa")
        TraceoPeticiones.registrar("GET", "api/model")

        assertEquals("", TraceoPeticiones.resumen())
    }

    @Test
    fun `resumen vacio cuando no hubo peticiones`() {
        assertEquals("", TraceoPeticiones.resumen())
    }
}
