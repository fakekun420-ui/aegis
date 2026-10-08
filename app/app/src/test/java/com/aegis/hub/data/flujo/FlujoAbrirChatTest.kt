package com.aegis.hub.data.flujo

import com.aegis.hub.data.RutaNativa
import com.aegis.hub.data.repo.ConfigCache
import com.aegis.hub.data.repo.ConfigSesion
import com.aegis.hub.data.repo.SesionConfigRepo
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Flujo abrir chat (F10): leer (1 GET con todo) + cola + catalogo; titulo,
 * modelo y agente del servidor; en caliente el catalogo no repite.
 */
class FlujoAbrirChatTest {

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
        val agentes = mutableMapOf<String, String>()
        override fun leerModelo(sid: String) = modelos[sid]
        override fun guardarModelo(sid: String, modelo: String) { modelos[sid] = modelo }
        override fun leerAgente(sid: String) = agentes[sid]
        override fun guardarAgente(sid: String, agente: String) { agentes[sid] = agente }
        override fun ultimoModelo(): String? = null
        override fun guardarUltimoModelo(modelo: String) = Unit
        override fun ultimoAgente(): String? = null
        override fun guardarUltimoAgente(agente: String) = Unit
    }

    @Test
    fun `abrir trae todo del servidor con pocas peticiones`() = runBlocking {
        val oc = fake.api()
        val api = RutaNativa(oc = oc)
        val config = SesionConfigRepo(oc, Cache())

        val cfg = config.leer("ses_test")
        assertEquals(ConfigSesion.Origen.SERVIDOR, cfg.origen)
        assertEquals("m-srv", cfg.modelo)
        assertEquals("build", cfg.agente)

        val cola = api.getMessagesTail("ses_test", 200)
        assertTrue(cola.ok && cola.data!!.size == 2)

        api.getModels("opencode")
        api.getOpencodeAgents()
        val frio = fake.peticiones.size

        // Segunda vuelta en caliente: el catalogo no repite peticiones.
        config.leer("ses_test")
        api.getMessagesTail("ses_test", 200)
        api.getModels("opencode")
        api.getOpencodeAgents()

        assertEquals(2, fake.peticiones.size - frio)
        assertTrue("abrir en frio: leer+cola+catalogo(2)", frio <= 4)
    }

    @Test
    fun `servidor caido usa la cache marcada`() = runBlocking {
        val oc = fake.api()
        val cache = Cache().apply {
            modelos["ses_x"] = "m-cache"
            agentes["ses_x"] = "plan"
        }
        val config = SesionConfigRepo(oc, cache)
        fake.modo = FakeOpenCode.Modo.SIN_SESION

        val cfg = config.leer("ses_x")
        assertEquals(ConfigSesion.Origen.CACHE_LOCAL, cfg.origen)
        assertEquals("m-cache", cfg.modelo)
    }
}
