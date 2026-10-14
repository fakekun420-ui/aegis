package com.aegis.hub.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F3-P8.2: refresco del VM — backoff, cooldown del camino lento y propiedad del job.
 *
 * MEDIDO: el refresco hace 4 peticiones por ciclo cada 2 s (~120/min). Sin backoff, un
 * Hub caido genera trafico sin parar; sin cooldown del fallback, cada ciclo de 2 s
 * descargaba el historial entero (~1730 mensajes) mientras el camino rapido fallaba.
 *
 * Estos tests llaman al codigo REAL (`noteRefreshResult`, `esperaRefrescoMs` y
 * `puedeDetenerRefresco` son `internal`). Sin red, sin tiempo virtual: la politica es
 * pura y el contador vive en el VM.
 */
class ChatRefrescoBackoffTest {

    @Test
    fun `la espera es 2 s hasta el tercer fallo y 30 s desde ahi`() {
        val vm = ChatViewModel()
        assertEquals(2_000L, vm.esperaRefrescoMs(0))
        assertEquals(2_000L, vm.esperaRefrescoMs(1))
        assertEquals(2_000L, vm.esperaRefrescoMs(2))
        // Contraejemplo: contar por PETICION (no por ciclo) reseteaba la racha con un
        // exito intermedio y el backoff no disparaba nunca.
        assertEquals(30_000L, vm.esperaRefrescoMs(3))
        assertEquals(30_000L, vm.esperaRefrescoMs(100))
    }

    @Test
    fun `tres ciclos fallidos seguidos marcan el Hub como inalcanzable`() {
        val vm = ChatViewModel()
        assertTrue("empieza alcanzable", vm.servidorAlcanzable.value)
        vm.noteRefreshResult(ok = false)
        vm.noteRefreshResult(ok = false)
        assertTrue("dos fallos puntuales se toleran", vm.servidorAlcanzable.value)
        vm.noteRefreshResult(ok = false)
        assertFalse("al tercer fallo se dice en pantalla", vm.servidorAlcanzable.value)
    }

    @Test
    fun `un ciclo bueno resetea la racha y vuelve a marcar alcanzable`() {
        val vm = ChatViewModel()
        repeat(3) { vm.noteRefreshResult(ok = false) }
        assertFalse(vm.servidorAlcanzable.value)
        vm.noteRefreshResult(ok = true)
        assertTrue(vm.servidorAlcanzable.value)
        // Y la racha quedo a cero: hacen falta otros 3 fallos, no 1.
        vm.noteRefreshResult(ok = false)
        vm.noteRefreshResult(ok = false)
        assertTrue(vm.servidorAlcanzable.value)
    }

    @Test
    fun `el cooldown del historial completo es 30 s`() {
        assertEquals(30_000L, COOLDOWN_FALLBACK_COMPLETO_MS)
    }

    @Test
    fun `stopViewRefresh de otra sesion no mata el refresco propio`() {
        // La guarda que evita que el onDispose de la pantalla ANTERIOR mate el poll que
        // el LaunchedEffect de la nueva ya arranco (el chat se quedaba sin sincronizar).
        val vm = ChatViewModel()
        assertTrue("sin dueno todo stop vale", vm.puedeDetenerRefresco(null))
        assertFalse("otra sesion no es duena", vm.puedeDetenerRefresco("sesion-ajena"))
        // No revienta sin job en marcha: es un no-op, no un crash.
        vm.stopViewRefresh("sesion-ajena")
    }
}
