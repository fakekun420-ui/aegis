package com.aegis.hub.data

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MEDIDO 2026-10-02. Fijan dos fallos que el usuario reporto al enviar desde la app:
 * "este mensaje no se visualiza en el chat" y "la subida de archivos tampoco funciona".
 *
 * ## La forma real de un mensaje de USUARIO
 *
 * MEDIDO paginando 800 mensajes de la sesion: los 17 mensajes de usuario traen el texto en un
 * campo `text` de primer nivel, y **ninguno** trae `content[]`. Los de asistente si usan
 * `content[]`.
 *
 * O sea que no es una excepcion ni un mensaje raro: es LA forma de un mensaje de usuario, y mi
 * traductor construia las partes solo desde `content[]`. Resultado: el mensaje salia con cero
 * partes, `Message.isEmpty` daba true, y el filtro `filterNot { it.isEmpty }` lo borraba de la
 * lista. El mensaje del usuario no se veia nunca.
 */
class EnvioDesdeLaAppTest {

    private val gson = Gson()

    /** MEDIDO: tal cual lo devuelve `GET /api/session/{id}/message` para un mensaje de usuario. */
    private val USUARIO_REAL = """
        {"id":"msg_0fe5c73690013YJweL4Ux2QER1",
         "time":{"created":1790973866963},
         "text":"un gran detalle es que cuando envia un mensaje no se visualiza",
         "agents":[{"name":"orchestrator"}],
         "type":"user"}
    """.trimIndent()

    /** MEDIDO: el de asistente, que si usa `content[]`. */
    private val ASISTENTE_REAL = """
        {"id":"msg_0fe5c740a001dlo3Z1","type":"assistant",
         "time":{"created":1790973867546,"streamed":1790973917289},
         "content":[{"type":"text","text":"Un fallo real y grave."}]}
    """.trimIndent()

    // ---------------------------------------------------------------------------------------

    @Test
    fun `un mensaje de USUARIO trae el texto en 'text', no en content`() {
        // La asercion que fija el fallo. Si esto vuelve a fallar, el mensaje no se ve.
        val m = gson.fromJson(USUARIO_REAL, OpenCodeMessage::class.java)
        assertEquals("MEDIDO: el texto va en 'text'", "user", m.type)
        assertEquals(null, m.content)
        assertTrue("y el texto NO esta vacio", !m.text.isNullOrBlank())
    }

    @Test
    fun `el mensaje del usuario se TRADUCE a una parte de texto, y no sale vacio`() {
        // Este es el fallo entero. Con `content` vacio y `text` sin declarar, el mensaje llegaba
        // con cero partes y `isEmpty` daba true.
        val m = gson.fromJson(USUARIO_REAL, OpenCodeMessage::class.java)
        val msg = NativeMapper.toMessages(listOf(m), "ses_test").first()

        assertFalse(
            "MEDIDO: un mensaje de usuario sin partes es DESAPARECIDO. `isEmpty` borra el" +
            " mensaje de la lista y el usuario no ve lo que escribe.",
            msg.isEmpty
        )
        assertEquals("el texto tiene que llegar integro", m.text, msg.parts!!.first().text)
        assertEquals("y ser una parte de texto", "text", msg.parts!!.first().type)
    }

    @Test
    fun `el mensaje del ASISTENTE sigue traduciendose por content, sin cambios`() {
        // El contraejemplo del anterior: si el fallback se aplicara siempre, el texto de un
        // asistente con `content` se duplicaria o se perderia el de `content`.
        val m = gson.fromJson(ASISTENTE_REAL, OpenCodeMessage::class.java)
        val msg = NativeMapper.toMessages(listOf(m), "ses_test").first()

        assertFalse("un asistente con contenido no esta vacio", msg.isEmpty)
        assertEquals("usa el de content[]", "Un fallo real y grave.", msg.parts!!.first().text)
    }

    @Test
    fun `el id de la parte de texto no colisiona con el mensaje`() {
        // `mergeTail` indexa por id. Si la parte�inventada del usuario tuviera el mismo id que el
        // mensaje, dos filas distintas se pisarian al fusionar.
        val m = gson.fromJson(USUARIO_REAL, OpenCodeMessage::class.java)
        val msg = NativeMapper.toMessages(listOf(m), "ses_test").first()

        assertTrue("la parte tiene id propio", !msg.parts!!.first().id.isNullOrBlank())
        assertFalse(
            "y NO es el mismo que el del mensaje: colisionarian al fusionar",
            msg.parts!!.first().id == msg.info!!.id
        )
    }

    @Test
    fun `un mensaje sin texto ni content NO inventa una parte`() {
        // Los mensajes de herramienta y los `idle` vienen asi. No se les debe fabricar texto.
        val m = gson.fromJson("""{"id":"msg_x","type":"idle","time":{"created":1}}""",
            OpenCodeMessage::class.java)
        val msg = NativeMapper.toMessages(listOf(m), "ses_test").first()

        assertTrue("sin texto plano no hay parte de texto que pintar",
            msg.parts.isNullOrEmpty() || msg.parts!!.none { !it.text.isNullOrBlank() })
    }

    /** MEDIDO: mensaje con texto E imagenes: trae `text` y `files`, y NO `content[]`. */
    private val USUARIO_CON_IMAGEN = """
        {"id":"msg_c1","time":{"created":1},
         "text":"mira esta captura",
         "files":[{"data":"iVBORw0KGgo=","mime":"image/png","name":"c.png",
                   "source":{"type":"inline"}}],
         "type":"user"}
    """.trimIndent()

    @Test
    fun `un mensaje con TEXTO e imagenes muestra LOS DOS`() {
        // Este es el fallo que reporto el usuario: "aparece la imagen pero no el mensaje".
        // MEDIDO: los adjuntos ya crean partes, asi que `parts` NO estaba vacio y mi fallback
        // —`if (!parts.isNullOrEmpty()) parts else textoPlano(m)`— descartaba el texto en cuanto
        // habia una imagen. El texto se ANADE, no se elige en vez de.
        val m = gson.fromJson(USUARIO_CON_IMAGEN, OpenCodeMessage::class.java)
        val msg = NativeMapper.toMessages(listOf(m), "ses_test").first()

        val tipos = msg.parts!!.map { it.type }
        assertTrue(
            "tiene que haber una parte de TEXTO y una de archivo: " + tipos,
            tipos.contains("text") && tipos.any { it == "file" || it == "image" }
        )
        assertEquals(
            "el texto del usuario llega integro",
            "mira esta captura",
            msg.parts!!.first { it.type == "text" }.text
        )
    }

    @Test
    fun `un mensaje de ASISTENTE con content no duplica el texto`() {
        // El contraejemplo del anterior. Si el texto se anadiera SIEMPRE, un asistente que trae
        // las dos cosas tendria dos partes de texto con el mismo contenido, y la UI lo pintaria
        // dos veces.
        val m = gson.fromJson(
            """{"id":"msg_a1","type":"assistant","text":"hola","time":{"created":1},"content":[{"type":"text","text":"hola"}]}""",
            OpenCodeMessage::class.java
        )
        val msg = NativeMapper.toMessages(listOf(m), "ses_test").first()

        assertEquals("una sola parte de texto", 1, msg.parts!!.count { it.type == "text" })
    }

    @Test
    fun `sin marca no se afirma que este ocupada, y con marca vieja tampoco`() {
        // MEDIDO: por esto fallaba el indicador de "Trabajando en ello". `getInflight` ponia
        // `lastSeen = null`, y `isBusy` devuelve false cuando no hay marca: o sea que
        // `_turnBusy` era SIEMPRE false y el veto de `turnIsReallyFinished` no se aplicaba nunca.
        assertFalse(
            "sin marca no hay senal, y sin senal no se afirma que este ocupada",
            TurnState.isBusy(
                InflightSession(id = "ses_x", turnOver = false, lastSeen = null, since = null)
            )
        )
        assertTrue(
            "con marca reciente SI debe decir que esta ocupada",
            TurnState.isBusy(
                InflightSession(
                    id = "ses_x", turnOver = false,
                    lastSeen = System.currentTimeMillis(), since = null
                )
            )
        )
        assertFalse(
            "pero si el registro es VIEJO, no: un turno que empezo hace una hora no esta trabajando",
            TurnState.isBusy(
                InflightSession(
                    id = "ses_x", turnOver = false,
                    lastSeen = System.currentTimeMillis() - 3_600_000L, since = null
                )
            )
        )
    }
}
