package com.aegis.hub.data

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MEDIDO 2026-10-02: este fichero fija la FORMA REAL de lo que devuelve OpenCode, y existe por
 * un fallo que dio el usuario al abrir la app:
 *
 *     No se pudieron cargar los agentes: Expected a string but was BEGIN_OBJECT
 *     at line 1 column 95 path $.data[0].model
 *
 * Es decir: el campo `model` de `/api/agent` es un OBJETO y el modelo de la app lo declaraba como
 * `String`. Gson no puede convertir un objeto en un texto y falla al deserializar.
 *
 * ## Por qué estos tests usan JSON en crudo y no data class
 *
 * Porque el fallo estaba en la CAPA DE DESERIALIZACIÓN, no en el mapeo: el traductor nunca llegó
 * a verse. Un test construido con `OpenCodeNativeAgent(...)` habría pasado mientras el endpoint
 * real fallaba, porque construye saltandose exactamente el punto donde se rompía. Con el JSON real, el
 * fallo no puede esconderse.
 */
class FormaNativaTest {

    private val gson = Gson()

    /** MEDIDO contra `GET /api/agent` en vivo. Copiado tal cual, sin arreglar. */
    private val AGENTE_REAL = """
        {"id":"build","name":"build","mode":"primary",
         "model":{"id":"space-bunny-free","providerID":"opencode"},
         "description":"Construye cosas","hidden":false,
         "permissions":{"bash":true}}
    """.trimIndent()

    @Test
    fun `el model de un agente es un OBJETO, no un String`() {
        // Esta es la aserción que habría evitado el fallo: el campo que da nombre al error.
        val a = gson.fromJson(AGENTE_REAL, OpenCodeNativeAgent::class.java)
        assertTrue(
            "MEDIDO 2026-10-02: 'model' es un objeto {id, providerID}. Si esto falla, el tipo de" +
            " OpenCodeNativeAgent.model ha vuelto a declararse como String, que es el fallo que dio" +
            " el usuario: 'Expected a string but was BEGIN_OBJECT'.",
            a.model is OpenCodeModelRef
        )
        assertEquals("space-bunny-free", a.model?.id)
    }

    @Test
    fun `un agente SIN model lo acepta, model es nullable`() {
        // El contraejemplo: si `model` fuera obligatorio, un agente sin modelo —los hay— haría
        // fallar la hoja entera de agentes.
        val a = gson.fromJson("""{"id":"x","name":"x","mode":"primary"}""",
            OpenCodeNativeAgent::class.java)
        assertEquals(null, a.model)
        assertEquals(false, a.hidden)
    }

    @Test
    fun `el model de una SESION tambien es un objeto, y ahi la app lo acepta como Any`() {
        // MEDIDO contra `GET /api/session`: `model` es un objeto igual que en el agente. Aqui el
        // data class NATIVO ya lo declara como `OpenCodeModelRef?`, asi que deserializa bien.
        //
        // MEDIDO 2026-10-02, y este test fallo al escribirse: yo afirme que salia un `Map`, porque
        // en el caso de los agentes Gson deja un objeto crudo. Aqui no: el tipo es `OpenCodeModelRef`
        // porque el data class lo declara asi. Los dos casos son distintos y mi comentario los
        // mezclaba — que es justo lo que hace dano por medido algo que no se ha medido.
        val s = gson.fromJson(
            """{"id":"ses_x","title":"aegis","model":{"id":"space-bunny-free","providerID":"opencode"}}""",
            OpenCodeSession::class.java)
        assertEquals("aegis", s.title)
        assertTrue("el model de la sesion debe seguir siendo un objeto",
            s.model is OpenCodeModelRef)
        assertEquals("space-bunny-free", s.model?.id)
    }

    @Test
    fun `la fecha de una sesion viene en un objeto time, no como texto`() {
        // MEDIDO: `time` es `{"created": 1790649234306, ...}` en milisegundos. La app espera un
        // String, y por eso el mapeo lo convierte a ISO-8601. Sin ese dato, la lista de chats se
        // ordena por nada.
        val s = gson.fromJson(
            """{"id":"ses_x","time":{"created":1790649234306,"updated":1790917918000}}""",
            OpenCodeSession::class.java)
        assertEquals(1790649234306L, s.time?.created)
        val iso = java.time.Instant.ofEpochMilli(s.time!!.created!!).toString()
        assertTrue("debe ser ISO-8601, que es lo que usaba el Hub: $iso", iso.startsWith("2026-"))
    }
}