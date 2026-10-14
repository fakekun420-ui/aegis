package com.aegis.hub.data

import com.aegis.hub.data.repo.CatalogoRepo
import com.aegis.hub.data.repo.SesionConfigRepo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * G4/F9: Tests JVM para colaboradores extraidos de RutaNativa.
 */
class ColaboradoresRutaTest {

    @Test
    fun `InteraccionesRutaNativa valida decision de permisos`() = runTest {
        val interacciones = InteraccionesRutaNativa(OpenCodeApi.default)
        val respInvalida = interacciones.replyPermission("s1", "r1", PermissionReplyBody(decision = "invalida"))
        assertFalse("decision invalida es rechazada", respInvalida.ok)
        assertTrue("explica motivo", respInvalida.error?.message?.contains("decision 'invalida' no vale") == true)
    }

    @Test
    fun `InteraccionesRutaNativa maneja sesion nula`() = runTest {
        val interacciones = InteraccionesRutaNativa(OpenCodeApi.default)
        val forms = interacciones.getPendingForms(null)
        assertTrue(forms.ok)
        assertTrue(forms.data?.isEmpty() == true)

        val perms = interacciones.getPendingPermissions(null)
        assertTrue(perms.ok)
        assertTrue(perms.data?.isEmpty() == true)
    }
}
