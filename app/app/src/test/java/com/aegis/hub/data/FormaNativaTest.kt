package com.aegis.hub.data

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    /**
     * MEDIDO contra `GET /api/agent` en vivo. Copiado tal cual, sin arreglar.
     *
     * `permissions` es la version que MEDÍ: una lista de reglas `{action, resource, effect}`.
     * La primera vez que escribi este fixture puse `{"bash": true}` —un mapa— porque es lo que
     * yo suponia, y el test pasaba mientras la app fallaba con
     * `Expected BEGIN_ARRAY but was BEGIN_OBJECT at $.data[0].permissions[0]`. Un fixture
     * inventado verifica el invento.
     */
    private val AGENTE_REAL = """
        {"id":"build","name":"build","mode":"primary",
         "model":{"id":"space-bunny-free","providerID":"opencode"},
         "description":"Construye cosas","hidden":false,
         "permissions":[{"action":"*","resource":"*","effect":"allow"},
                        {"action":"read","resource":"*.env","effect":"ask"},
                        {"action":"execute","resource":"bash","effect":"ask"}],
         "request":{"settings":{},"headers":{},"body":{}}}
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
    fun `los permissions de un agente son una LISTA de reglas, no un mapa de banderas`() {
        // MEDIDO 2026-10-02: SEGUNDO fallo del usuario sobre el MISMO endpoint, y el hecho de que
        // el primero (`model`) estuviera ya arreglado demuestra que corregir campo a campo es
        // un ciclo de CI por cada campo. De ahi el `chk_forma.py`, que los mide todos de una vez.
        //
        //     Expected BEGIN_ARRAY but was BEGIN_OBJECT at line 1 column 438
        //     path $.data[0].permissions[0]
        //
        // Es una lista de objetos {action, resource, effect}. Lo tenía declarado como
        // `Map<String, Any?>`, y Gson no puede deserializar un ARRAY en un mapa.
        val a = gson.fromJson(AGENTE_REAL, OpenCodeNativeAgent::class.java)
        val reglas = a.permissions
        assertNotNull(
            "MEDIDO: 'permissions' es una LISTA. Si esto es null, el tipo ha vuelto a Map, que es" +
            " exactamente el fallo que dio el usuario: BEGIN_ARRAY vs BEGIN_OBJECT.",
            reglas
        )
        assertEquals("3 reglas en el JSON medido, no banderas sueltas", 3, reglas!!.size)
        assertEquals("*", reglas[0].action)
        assertEquals("allow", reglas[0].effect)
        assertEquals("*.env", reglas[1].resource)
        // El CONTRAejemplo: con el tipo anterior (Map) esta aserción no compila siquiera. Y con un
        // `List<String>` daría un ClassCastException aqui. Es la que distingue "lista" de "lo que sea".
        assertTrue("cada elemento es una regla, no un texto", reglas[2] is OpenCodePermissionRule)
    }

    @Test
    fun `un agente SIN permissions lo acepta, la lista es nullable`() {
        // Sin esto, un agente sin reglas de permiso (o un endpoint que las omita) tumba la hoja
        // entera de agentes por un NullPointerException en el mapeo.
        val a = gson.fromJson("""{"id":"x","name":"x","mode":"primary"}""",
            OpenCodeNativeAgent::class.java)
        assertEquals(null, a.permissions)
        assertEquals(null, a.request)
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