package com.aegis.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * MEDIDO 2026-10-10: el `"su"` pelado nunca se resolvia en el PATH de la app
 * (el unico `su` vive en `/debug_ramdisk/su`) y el fallback mudo a `sh`
 * escondia el fallo: Magisk jamas veia a la app. `resolverSu` prueba rutas
 * absolutas primero y `command -v su` despues, sin pedir root al probar.
 */
class RootShellSuTest {

    @Test
    fun `prefiere la primera ruta absoluta que existe`() {
        val r = RootShell.resolverSu(
            existeArchivo = { it == "/sbin/su" },
            cualEnPath = { "/system/bin/su" }
        )
        assertEquals("/sbin/su", r)
    }

    @Test
    fun `sin absolutas usa command -v`() {
        val r = RootShell.resolverSu(
            existeArchivo = { false },
            cualEnPath = { "/system/xbin/su" }
        )
        assertEquals("/system/xbin/su", r)
    }

    @Test
    fun `sin nada devuelve null para error honesto`() {
        val r = RootShell.resolverSu(
            existeArchivo = { false },
            cualEnPath = { "  " }
        )
        assertNull(r)
    }
}
