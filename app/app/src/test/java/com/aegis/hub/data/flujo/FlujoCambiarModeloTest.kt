package com.aegis.hub.data.flujo

import com.aegis.hub.data.repo.ConfigCache
import com.aegis.hub.data.repo.Resultado
import com.aegis.hub.data.repo.SesionConfigRepo
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Flujo cambiar modelo (F10): el exito persiste en cache; el fallo no escribe
 * nada (la UI revierte con ese motivo: ver ConfigSesionVmTest).
 */
class FlujoCambiarModeloTest {

    private lateinit var fake: FakeOpenCode

    @Before
    fun arrancar() {
        fake = FakeOpenCode()
    }

    @After
    fun parar() {
        fake.cerrar()
    }

    private class Cache : ConfigCache {
        val modelos = mutableMapOf<String, String>()
        override fun leerModelo(sid: String) = modelos[sid]
        override fun guardarModelo(sid: String, modelo: String) { modelos[sid] = modelo }
        override fun leerAgente(sid: String): String? = null
        override fun guardarAgente(sid: String, agente: String) = Unit
        override fun ultimoModelo(): String? = null
        override fun guardarUltimoModelo(modelo: String) = Unit
        override fun ultimoAgente(): String? = null
        override fun guardarUltimoAgente(agente: String) = Unit
    }

    @Test
    fun `exito persiste el modelo`() = runBlocking {
        val cache = Cache()
        val repo = SesionConfigRepo(fake.api(), cache)
        val r = repo.fijarModelo("ses_test", "m-nuevo")

        assertTrue(r is Resultado.Ok)
        assertEquals("m-nuevo", cache.leerModelo("ses_test"))
    }

    @Test
    fun `fallo no escribe la cache`() = runBlocking {
        fake.modo = FakeOpenCode.Modo.FALLO_500
        val cache = Cache()
        val repo = SesionConfigRepo(fake.api(), cache)
        val r = repo.fijarModelo("ses_test", "m-nuevo")

        assertTrue(r is Resultado.Fallo)
        assertNull(cache.leerModelo("ses_test"))
    }
}
