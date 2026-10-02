package com.aegis.hub.data

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Por que la lista de "Chats" se llenaba de sesiones que nadie abrio.
 *
 * MEDIDO el 2026-10-02: `GET /api/session` devuelve las sesiones de primer nivel
 * Y las que crean los subagentes, mezcladas. El campo que las separa es
 * `parentID`: null en una sesion normal, y el id de la madre en una creada por
 * un subagente.
 *
 * Antes, `OpenCodeSession` NO tenia `parentID`. O sea que el problema no era que
 * faltara un filtro: era que la INFORMACION para filtrar no llegaba a la app.
 * Este test fija las dos cosas por separado, porque si alguien quita el campo
 * otra vez el filtro dejara de compilar, y si quita el filtro el sintoma vuelve
 * sin que se note por aqui.
 */
class SubagentSessionFilterTest {

    private val gson = Gson()

    // ------------------------------------------------------------------
    // 1. El campo tiene que EXISTIR y deserializarse
    // ------------------------------------------------------------------

    @Test
    fun `una sesion normal llega con parentID nulo`() {
        val json = """
            {"id":"ses_padre","title":"quant-math","time":{"created":1700000000000}}
        """.trimIndent()
        val s = gson.fromJson(json, OpenCodeSession::class.java)
        assertEquals("ses_padre", s.id)
        assertTrue(
            "una sesion de primer nivel tiene que llegar con parentID nulo, " +
                "que es lo que la hace VISIBLE en la lista",
            s.parentID.isNullOrBlank())
    }

    @Test
    fun `una sesion de subagente llega con el id de su madre`() {
        // El JSON real de una sesion hija. Si `parentID` no estuviera en el
        // modelo, esto deserializaria sin error y con `parentID = null`, que
        // es EXACTAMENTE el bug: la sesion de un subagente se hacia pasar por
        // una sesion normal y aparecia en la lista.
        val json = """
            {"id":"ses_hija","title":"Verificacion de directorio actual",
             "parentID":"ses_padre","time":{"created":1700000000000}}
        """.trimIndent()
        val s = gson.fromJson(json, OpenCodeSession::class.java)
        assertEquals("ses_hija", s.id)
        assertNotNull(
            "una sesion de subagente tiene que traer el id de su madre; si " +
                "esto sale nulo, el campo se ha vuelto a quitar del modelo",
            s.parentID)
        assertEquals("ses_padre", s.parentID)
    }

    // ------------------------------------------------------------------
    // 2. El filtro tiene que dejar pasar solo las de primer nivel
    // ------------------------------------------------------------------

    private fun sesion(id: String, titulo: String, madre: String?): OpenCodeSession =
        gson.fromJson(
            """{"id":"$id","title":"$titulo","parentID":${madre?.let { "\"$it\"" } ?: "null"}}""",
            OpenCodeSession::class.java)

    @Test
    fun `el filtro deja pasar la sesion normal y descarta la del subagente`() {
        val todas = listOf(
            sesion("ses_padre", "quant-math", null),
            sesion("ses_hija1", "Nombres exactos de herramientas", "ses_padre"),
            sesion("ses_hija2", "orquestador:master", "ses_padre"),
            sesion("ses_otro", "aegis", null),
        )
        val visibles = todas.filter { it.parentID.isNullOrBlank() }
        assertEquals(
            "solo las dos sesiones de primer nivel tienen que verse",
            listOf("ses_padre", "ses_otro"), visibles.map { it.id })
    }

    @Test
    fun `un parentID vacio se CONSERVA, y es lo correcto`() {
        // MEDIDO el 2026-10-02: la primera version de este test afirmaba que un
        // `parentID` vacio se descarta. Es FALSO, y habria fallado en CI, porque
        // `"".isNullOrBlank()` es `true` y el filtro CONSERVA lo que pasa ese
        // filtro. La implementacion era la correcta y el test la contradecía.
        //
        // Que se conserve es lo que se quiere: una sesion sin madre
        // identificada es, para todos los efectos, una sesion de primer nivel.
        // Descartarla seria esconder un chat real de la lista, que es un fallo
        // mucho peor que mostrar uno de mas.
        val conVacio = sesion("ses_x", "algo", "")
        assertTrue(conVacio.parentID.isNullOrBlank())
        assertTrue(
            "una sesion sin madre identificada tiene que SEGUIR VIENDOSE: " +
                "es preferible mostrar una de mas que esconder un chat real",
            listOf(conVacio).filter { it.parentID.isNullOrBlank() }
                .map { it.id } == listOf("ses_x"))
    }

    @Test
    fun `una sesion sin campo parentID se queda visible`() {
        // Si el servidor dejara de enviar el campo en una version futura, un
        // filtro estricto POR EL VALOR se comeria todas las sesiones y la
        // lista saldria vacia sin avisar. Por eso el criterio es
        // `isNullOrBlank()`: ausente y presente-nulo se tratan igual.
        val sinCampo = gson.fromJson(
            """{"id":"ses_sin","title":"aegis"}""", OpenCodeSession::class.java)
        assertNull(sinCampo.parentID)
        assertTrue(sinCampo.parentID.isNullOrBlank())
    }

    // ------------------------------------------------------------------
    // 3. Un caso real de lo que se veia en pantalla
    // ------------------------------------------------------------------

    @Test
    fun `una lista mezclada deja solo los chats reales`() {
        // Los cuatro titulos de la captura, tal cual aparecian.
        val cruda = listOf(
            sesion("ses_a", "quant-math", null),
            sesion("ses_b", "aegis", null),
            sesion("ses_c", "Verificacion de directorio actual", "ses_a"),
            sesion("ses_d", "Verificacion de alias y usuario con Bash", "ses_a"),
            sesion("ses_e", "Nombres exactos de herramientas", "ses_a"),
            sesion("ses_f", "orquestador:master", "ses_a"),
            sesion("ses_g", "Eliminar codigo muerto", "ses_a"),
        )
        val visibles = cruda.filter { it.parentID.isNullOrBlank() }
        assertEquals(listOf("quant-math", "aegis"), visibles.map { it.title })
        assertTrue(
            "ninguno de los titulos de subagente puede quedar visible",
            visibles.none { it.title in setOf(
                "Verificacion de directorio actual",
                "Verificacion de alias y usuario con Bash",
                "Nombres exactos de herramientas",
                "orquestador:master",
                "Eliminar codigo muerto") })
    }
}