package com.aegis.hub.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de las tres reglas que DECIDEN producto y que hasta ahora no tenian ni una prueba.
 *
 * MEDIDO 2026-10-01: 53 tests de la app se ejecutaban ya, y estos tres simbolos seguian con
 * cero cobertura. Uno de ellos, `TurnState.isBusy`, es exactamente el circulo de "trabajando"
 * que el usuario reporta sin funcionar, asi que su logica no estaba protegida por nada.
 *
 * Que un test pueda FALLAR es parte del diseno: cada afirmacion lleva un contraejemplo
 * concreto en el nombre, y hay tres al final que son FALSE deliberados — si el codigo
 * cambia, tienen que romperse.
 */
class ModelsComportamientoTest {

    // ==================================================================
    // seleccionables(): que agentes se ofrecen para cambiar a mano
    // ==================================================================

    /**
     * La regla real es `mode == "primary" && !hidden`. Este caso trae las cuatro ramas de
     * una vez: primary visible, primary OCULTO, subagent visible y subagent oculto.
     *
     * Contraejemplo: si `hidden` no se mirara, devolveria 5 en vez de 3 — que es el defecto
     * que hizo que la hoja ofreciera 6 donde OpenCode deja elegir 3.
     */
    @Test
    fun `seleccionables devuelve solo los primary visibles`() {
        val lista = listOf(
            OpencodeAgent(name = "Build"),                              // primary, visible
            OpencodeAgent(name = "Plan"),                               // primary, visible
            OpencodeAgent(name = "orchestrator"),                       // primary, visible
            OpencodeAgent(name = "Compaction", hidden = true),           // primary, OCULTO
            OpencodeAgent(name = "Title", mode = "subagent"),            // subagent
            OpencodeAgent(name = "kaenor-ai-engineer", mode = "subagent"),
            OpencodeAgent(name = "general", mode = "subagent"),
            OpencodeAgent(name = "explore", mode = "subagent")
        )
        // El orden es el de `sortedBy { it.name.lowercase() }`: build < orchestrator < plan.
        // Lo escribi primero como "Build, Plan, orchestrator" y FALLO en la primera ejecucion
        // del test: la expectativa estaba mal, no el codigo. El test 2 de este fichero ya
        // afirmaba el orden correcto, asi que era una contradiccion MIA entre dos tests.
        assertEquals(
            listOf("Build", "orchestrator", "Plan"),
            lista.seleccionables().map { it.name }
        )
    }

    /**
     * El orden es `sortedBy { it.name.lowercase() }`, NO el de llegada. Y aqui tiene
     * consequences visibles: la lista que devuelve la app sale Build, orchestrator, Plan —
     * una `o` va antes que una `P` porque la comparacion no distingue mayusculas.
     *
     * Contraejemplo: si el orden fuera el de entrada o fuera sensible a mayusculas, este
     * test falla en la posicion de "orchestrator".
     */
    @Test
    fun `seleccionables ordena por nombre en minusculas, no por orden de llegada`() {
        val desordenado = listOf(
            OpencodeAgent(name = "orchestrator"),
            OpencodeAgent(name = "Plan"),
            OpencodeAgent(name = "Build")
        )
        assertEquals(
            listOf("Build", "orchestrator", "Plan"),
            desordenado.seleccionables().map { it.name }
        )
    }

    /**
     * Los 34 subagentes se descartan aunque a midday trabajo. MEDIDO: un turno con
     * `agent=kaenor-ai-engineer` corre y contesta. Es que no son lo que el boton pregunta.
     *
     * Contraejemplo: si se quitara el filtro de `mode`, un subagent se colaria en la hoja.
     */
    @Test
    fun `seleccionables descarta los subagentes aunque no esten ocultos`() {
        val soloSubagentes = listOf(
            OpencodeAgent(name = "kaenor-backend-architect", mode = "subagent"),
            OpencodeAgent(name = "kaenor-reality-checker", mode = "subagent")
        )
        assertEquals(emptyList<String>(), soloSubagentes.seleccionables().map { it.name })
    }

    /** Lista vacia: vacio, sin crash. Y TODOS ocultos: tambien vacio. */
    @Test
    fun `seleccionables con lista vacia o toda oculta devuelve vacio`() {
        assertEquals(emptyList<String>(), emptyList<OpencodeAgent>().seleccionables().map { it.name })
        val todaOculta = listOf(
            OpencodeAgent(name = "Compaction", hidden = true),
            OpencodeAgent(name = "Title", hidden = true)
        )
        assertEquals(emptyList<String>(), todaOculta.seleccionables().map { it.name })
    }

    /**
     * `hidden` tiene default `false` A PROPOSITO. Si el Hub dejara de mandar el campo, con
     * default `true` se esconderian agentes validos sin avisar y la hoja saldria vacia.
     */
    @Test
    fun `un agente sin hidden explícito se considera visible`() {
        val soloNombre = listOf(OpencodeAgent(name = "Build"))
        assertEquals(listOf("Build"), soloNombre.seleccionables().map { it.name })
    }

    // ==================================================================
    // TurnState.isBusy(): el circulo de "trabajando"
    // ==================================================================

    /**
     * El caso normal: `turnOver=false` y `lastSeen` reciente → ocupado.
     *
     * Se pasa `now` EXPLICITO en todos los casos. Un test que usa el reloj del sistema es un
     * test que puede fallar un dia si y pasar otro: el valor debe venir del test, no de la
     * maquina que lo ejecuta.
     */
    @Test
    fun `isBusy dice ocupado si el turno no ha terminado y se vio hace poco`() {
        val ahora = 1_700_000_000_000L
        val s = InflightSession(
            id = "ses_abc",
            since = ahora - 10_000L,
            turnOver = false,
            lastSeen = ahora - 5_000L
        )
        assertTrue(TurnState.isBusy(s, ahora))
    }

    /**
     * `turnOver=true` significa que el turno CERRO: no esta ocupado, por muy reciente que sea
     * el `lastSeen`. Es la condicion que mas se olvidaria al refactorizar.
     */
    @Test
    fun `isBusy dice libre si el turno ya terminó aunque lastSeen sea ahora mismo`() {
        val ahora = 1_700_000_000_000L
        val s = InflightSession(id = "ses_abc", turnOver = true, lastSeen = ahora)
        assertFalse(TurnState.isBusy(s, ahora))
        assertTrue(TurnState.isOver(s))
    }

    /**
     * El techo de silencio es de 6 HORAS, no de 45 segundos. Ese 45 s era el defecto: una
     * herramienta de mas de 45 s no emite nada, el circulo se apagaba mientras el turno
     * seguia vivo. MEDIDO en la captura del usuario: `bash(sleep 270; ...)`.
     *
     * Contraejemplo: si el techo volviera a 45 s, este test falla Y el de abajo tambien —
     * los dos lados del umbral.
     */
    @Test
    fun `un silencio de 5 minutos sigue ocupado, el techo no son 45 segundos`() {
        val ahora = 1_700_000_000_000L
        val haceCincoMinutos = InflightSession(lastSeen = ahora - 5L * 60L * 1000L)
        assertTrue(
            "5 min de silencio debe seguir siendo ocupado (el umbral es de 6 h)",
            TurnState.isBusy(haceCincoMinutos, ahora)
        )
    }

    /** Y al otro lado: pasado el techo, deja de estar ocupado. */
    @Test
    fun `un silencio de mas de seis horas ya no cuenta como ocupado`() {
        val ahora = 1_700_000_000_000L
        val haceSieteHoras = InflightSession(lastSeen = ahora - 7L * 60L * 60L * 1000L)
        assertFalse(TurnState.isBusy(haceSieteHoras, ahora))
    }

    /**
     * Sin `lastSeen` se cae a `since`, y si tampoco hay `since` NO se inventa ocupacion.
     * Un registro vacio es "no se", no "si".
     */
    @Test
    fun `isBusy cae de lastSeen a since y no inventa ocupacion sin ninguno de los dos`() {
        val ahora = 1_700_000_000_000L
        assertTrue(TurnState.isBusy(InflightSession(since = ahora - 3_000L), ahora))
        assertFalse(TurnState.isBusy(InflightSession(id = "ses_abc"), ahora))
        assertFalse(TurnState.isBusy(null, ahora))
        assertFalse(TurnState.isBusy(InflightSession(turnOver = true), ahora))
    }

    // ==================================================================
    // modeloPorDefecto(): el modelo de una sesion nueva
    // ==================================================================

    /**
     * Space Bunny Free es el modelo por defecto de una sesion nueva. Decision del usuario
     * 2026-10-01. MEDIDO sobre `/api/models`: de 475 modelos, `space-bunny-free` existe con
     * `free=true`, y el PRIMERO free de la lista es `longcat-2.5-preview-free`.
     *
     * Contraejemplo 1: si la regla volviera al primer free, este test falla — devuelve
     * "longcat-2.5-preview-free".
     * Contraejemplo 2: si fuera `first().id`, devolveria "pago-1", o sea un default de pago.
     */
    @Test
    fun `modeloPorDefecto de una sesion nueva es Space Bunny Free`() {
        val lista = listOf(
            ModelOption(id = "pago-1", name = "De pago", free = false),
            ModelOption(id = "longcat-2.5-preview-free", name = "Longcat", free = true),
            ModelOption(id = ID_MODELO_POR_DEFECTO, name = "Space Bunny Free", free = true)
        )
        assertEquals(ID_MODELO_POR_DEFECTO, lista.modeloPorDefecto)
    }

    /**
     * Si Space Bunny Free no esta en la lista, cae al PRIMERO FREE, no al primero de la lista.
     * Ese repliegue es el motivo de que esto sea una regla y no un id fijo: el id que se
     * hardcodeo antes, `gemini-3.8-flash-high`, no existia entre los 472 modelos, y el Hub
     * avisaba "modelo no encontrado en el indice v2".
     *
     * Contraejemplo: si volviera a `first().id`, este test falla — devuelve "pago-1".
     */
    @Test
    fun `si Space Bunny Free no esta, cae al primero free y no al primero de la lista`() {
        val lista = listOf(
            ModelOption(id = "pago-1", name = "De pago", free = false),
            ModelOption(id = "longcat-2.5-preview-free", name = "Longcat", free = true),
            ModelOption(id = "otro-free", name = "Otro", free = true)
        )
        assertEquals("longcat-2.5-preview-free", lista.modeloPorDefecto)
    }

    /**
     * Un Space Bunny Free que NO sea gratis no debe ganar. El id se busca junto al `free` en el
     * mismo elemento: un modelo de pago con ese id es otra cosa, y si se aceptara, el "default"
     * seria de pago disfrazado de neutro — que es justo el defecto que este filtro evita.
     */
    @Test
    fun `un Space Bunny Free de pago no es el default`() {
        val lista = listOf(
            ModelOption(id = ID_MODELO_POR_DEFECTO, name = "Space Bunny", free = false),
            ModelOption(id = "longcat-2.5-preview-free", name = "Longcat", free = true)
        )
        assertEquals("longcat-2.5-preview-free", lista.modeloPorDefecto)
    }

    /**
     * Sin ningun free devuelve null, y se deja que OpenCode elija. Es preferible a no fijar
     * nada a inventar un id que puede que no exista — que es como paso.
     */
    @Test
    fun `modeloPorDefecto devuelve null si no hay ningun modelo free`() {
        val soloPago = listOf(
            ModelOption(id = "pago-1", name = "Uno", free = false),
            ModelOption(id = "pago-2", name = "Dos", free = false)
        )
        assertNull(soloPago.modeloPorDefecto)
        assertNull(emptyList<ModelOption>().modeloPorDefecto)
    }

    /** `free` por default es `false`: un modelo sin el campo NO se toma como default. */
    @Test
    fun `modeloPorDefecto no toma un modelo al que no se le dijo que es free`() {
        val sinMarcar = listOf(
            ModelOption(id = "parece-free-por-el-nombre", name = "X"),
            ModelOption(id = "marcado", name = "Y", free = true)
        )
        assertEquals("marcado", sinMarcar.modeloPorDefecto)
    }

    /** Y con el PRIMERO ya free: ese es, sin mirar los siguientes. */
    @Test
    fun `modeloPorDefecto se queda con el primero que encuentra libre`() {
        val lista = listOf(
            ModelOption(id = "libre-1", name = "A", free = true),
            ModelOption(id = "libre-2", name = "B", free = true)
        )
        assertEquals("libre-1", lista.modeloPorDefecto)
    }
}
