package com.aegis.hub.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * G1: marcas de ciclo de vida y conteo por ventana, sin Android.
 *
 * Todo lo testeado es puro (`linea`, fotos/deltas). `emitir` no se verifica
 * (pide `Log` real); solo se comprueba que inicio/fin no rompen con el gate
 * apagado (el caso release).
 */
class MarcasCicloTest {

    @Before
    fun limpio() {
        TraceoPeticiones.limpiar()
        TraceoPeticiones.activo = false
    }

    @After
    fun devuelvo() {
        TraceoPeticiones.limpiar()
        TraceoPeticiones.activo = false
    }

    @Test
    fun `linea sin extras ni orden depende`() {
        assertEquals(
            "AegisMark: chat.abrir:inicio ses_1",
            MarcasCiclo.linea("chat.abrir:inicio", "ses_1")
        )
        assertEquals(
            "AegisMark: chat.abrir:fin ses_1 peticiones=7",
            MarcasCiclo.linea("chat.abrir:fin", "ses_1", mapOf("peticiones" to 7))
        )
        assertEquals(
            "AegisMark: x s a=1 b=2",
            MarcasCiclo.linea("x", "s", mapOf("b" to 2, "a" to 1))
        )
    }

    @Test
    fun `foto y delta cuentan http mas su`() {
        TraceoPeticiones.activo = true
        TraceoPeticiones.registrar("GET", "api/session/ses_9")
        TraceoPeticiones.contarSu()
        val f = MarcasCiclo.foto()
        TraceoPeticiones.registrar("GET", "api/session/ses_9")
        TraceoPeticiones.registrar("POST", "api/model")
        TraceoPeticiones.contarSu()
        TraceoPeticiones.contarSu()
        // 2 http + 2 su desde la foto.
        assertEquals(4, MarcasCiclo.totalDesde(f))
        assertEquals(2, MarcasCiclo.susDesde(f))
        assertEquals(3, TraceoPeticiones.total())
        assertEquals(3, TraceoPeticiones.susTotales())
    }

    @Test
    fun `inicio-fin no rompen con el gate apagado`() {
        val f = MarcasCiclo.abrirInicio("ses_x")
        MarcasCiclo.abrirFin("ses_x", f)
        val g = MarcasCiclo.enviarInicio("ses_x")
        MarcasCiclo.enviarFin("ses_x", g)
        MarcasCiclo.skillsFin(MarcasCiclo.skillsInicio())
        MarcasCiclo.reposo("ses_x", MarcasCiclo.foto())
        assertTrue(true)
    }

    @Test
    fun `normalizar deja los ids como id para E3`() {
        assertEquals("api/session/:id/message", TraceoPeticiones.normalizarRuta("api/session/ses_abc/message"))
        TraceoPeticiones.activo = true
        TraceoPeticiones.registrar("GET", "api/session/ses_a")
        TraceoPeticiones.registrar("GET", "api/session/ses_b")
        // Los dos caen en la misma clave normalizada.
        assertEquals("GET api/session/:id=2", TraceoPeticiones.resumen())
    }
}
