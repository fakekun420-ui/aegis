package com.aegis.hub.data

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MEDIDO 2026-10-02. Estos tests fijan los DOS fallos que el usuario reportó al abrir la app:
 * "no cargan los modelos" y "la barra de 'Trabajando en ello...' aparece cuando la sesion ya
 * termino". Los dos son el mismo tipo de error — **una forma del JSON que nadie medico** — y los
 * dos eran silenciosos.
 */
class FormaMensajesTest {

    private val gson = Gson()

    /**
     * MEDIDO contra `GET /api/model?limit=1`, copiado tal cual y sin arreglar.
     *
     * `variants` es lo importante: yo lo declare `List<String>?` y cada elemento es un OBJETO.
     */
    private val MODELO_REAL = """
        {"id":"anthropic/claude-sonnet-5","modelID":"anthropic/claude-sonnet-5",
         "name":"Claude Sonnet 5","providerID":"anthropic","enabled":true,"family":"claude",
         "cost":[{"input":3,"output":15,"cache":{"read":0.3}}],
         "capabilities":{"tools":true,"input":["text"],"output":["text"]},
         "variants":[{"id":"none","settings":{"reasoning":{"enabled":false}}},
                     {"id":"thinking","settings":{"reasoning":{"enabled":true}}}],
         "status":"active"}
    """.trimIndent()

    /** MEDIDO: el `time` de un mensaje ya cerrado trae `completed`. */
    private val MENSAJE_CERRADO = """
        {"id":"msg_1","role":"assistant",
         "time":{"created":1790973151624,"streamed":1790973158444,"completed":1790973158526},
         "content":[{"type":"text","text":"Listo"}]}
    """.trimIndent()

    /** Y uno que AUN NO ha terminado: mismo shape, sin `completed`. */
    private val MENSAJE_ABIERTO = """
        {"id":"msg_2","role":"assistant",
         "time":{"created":1790973167356,"streamed":1790973182314},
         "content":[{"type":"text","text":"Trabajando"}]}
    """.trimIndent()

    // ---------------------------------------------------------------------------------------
    // FALLO 1 — los modelos no cargaban
    // ---------------------------------------------------------------------------------------

    @Test
    fun `un variant de modelo es un OBJETO, no una cadena`() {
        // Este es el fallo exacto: `variants` estaba declarado `List<String>?`. Gson no puede
        // deserializar un objeto en un texto y LANZA, asi que el fallo tumbaba la lista COMPLETA
        // de 480 modelos, no solo este campo.
        val m = gson.fromJson(MODELO_REAL, OpenCodeNativeModel::class.java)
        val variants = m.variants
        assertNotNull("MEDIDO: variants es una lista de 2 objetos", variants)
        assertEquals(2, variants!!.size)
        assertEquals("none", variants[0].id)
        assertEquals("thinking", variants[1].id)
        assertNotNull("cada variant trae sus settings", variants[1].settings)
    }

    @Test
    fun `una lista de variantes VACIA no rompe nada`() {
        // El contraejemplo: si `variants` fuera no-nullable o required, un modelo sin variantes
        // —los hay, 179 de 480— haria fallar la carga entera.
        val m = gson.fromJson("""{"id":"x","modelID":"x"}""", OpenCodeNativeModel::class.java)
        assertEquals(null, m.variants)
        assertTrue("no debe lanzar", m.id == "x")
    }

    @Test
    fun `el cost de un modelo es una LISTA de objetos, no un numero`() {
        // MEDIDO: `cost` es `[{input, output, cache}]`, y lo declare `Any?` a proposito para que
        // entrara cualquiera. Este test fija que el criterio de "gratis" tiene de donde leer.
        val m = gson.fromJson(MODELO_REAL, OpenCodeNativeModel::class.java)
        assertTrue("el cost llega como lista", m.cost is List<*>)
        val primero = (m.cost as List<*>).first()
        assertTrue("y cada elemento es un mapa con input/output", primero is Map<*, *>)
    }

    // ---------------------------------------------------------------------------------------
    // FALLO 2 — "Trabajando en ello..." con el turno ya cerrado
    // ---------------------------------------------------------------------------------------

    @Test
    fun `el time de un mensaje CERRADO trae completed, y el abierto no`() {
        // La asercion que fija el fallo. `completed` no estaba declarado en `OpenCodeTime`, asi
        // que Gson lo descartaba y el mapa de la app nunca tenia esa clave.
        val cerrado = gson.fromJson(MENSAJE_CERRADO, OpenCodeMessage::class.java)
        assertNotNull("MEDIDO: el cerrado tiene completed", cerrado.time?.completed)

        val abierto = gson.fromJson(MENSAJE_ABIERTO, OpenCodeMessage::class.java)
        assertEquals("el que sigue trabajando NO lo tiene", null, abierto.time?.completed)
    }

    @Test
    fun `el traductor copia completed al mapa de la app`() {
        // SEGUNDA mitad del mismo fallo: aunque Gson lo traiga, `NativeMapper.timeMap` solo copiaba
        // `created`, `updated` e `idle`. O sea que la clave no llegaba a `info.time`, que es lo
        // que mira `turnIsReallyFinished`.
        val cerrado = gson.fromJson(MENSAJE_CERRADO, OpenCodeMessage::class.java)
        val info = NativeMapper.toMessages(listOf(cerrado), "ses_test").first().info

        assertNotNull("info.time debe existir", info?.time)
        assertTrue(
            "MEDIDO: sin esta clave, `turnIsReallyFinished` devuelve false SIEMPRE y el" +
            " indicador de 'Trabajando en ello...' se queda pegado con el turno cerrado.",
            info!!.time!!.containsKey("completed")
        )
        assertTrue("created tambien, que es la marca de tiempo", info.time!!.containsKey("created"))
    }

    @Test
    fun `un mensaje abierto NO trae completed, y por eso el indicador puede apagarse`() {
        // El CONTRAejemplo del anterior. Sin esto, el test anterior pasaria aunque `completed` se
        // metiera siempre: el fallo seeria "la barra nunca aparece", que es el sintoma opuesto y
        // tan raro como el otro.
        val abierto = gson.fromJson(MENSAJE_ABIERTO, OpenCodeMessage::class.java)
        val info = NativeMapper.toMessages(listOf(abierto), "ses_test").first().info

        assertFalse(
            "un turno en marcha no puede decir 'completed', o el indicador se apaga mientras el" +
            " agente trabaja",
            info!!.time!!.containsKey("completed")
        )
        assertTrue("pero sí trae created, que es lo que lo fecha", info.time!!.containsKey("created"))
    }
}