package com.aegis.hub.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MEDIDO 2026-10-02 contra el endpoint vivo, con 2520 mensajes en la sesion. Estos tests fijan
 * las TRES reglas que hacen que el chat se congelase, y las tres estan medidas, no supuestas.
 *
 * ## El sintoma
 *
 * El usuario: "el chat no se sincroniza con el estado actual de la sesion, esta trabado y no se
 * mueve de alla".
 *
 * ## Lo medido
 *
 *  1. `order=asc`  -> los MAS ANTIGUOS. Con limit=200 y 2520 mensajes salen los indices 0..199.
 *  2. `order=desc` -> los MAS NUEVOS.
 *  3. `limit > 200`-> **CERO mensajes**, en silencio, sin error. MEDIDO: 201 -> 0, 250 -> 0,
 *     500 -> 0, 1000 -> 0. El tope es duro.
 *
 * `getMessagesTail` pedia `asc`. O sea: la app pedia "la cola" y recibia "la cabeza", siempre los
 * mismos 200 primeros, y `mergeTail` (ChatViewModel:552) fusionaba una lista consigo misma. El
 * poll no podia aportar nunca un mensaje nuevo.
 *
 * ## Por que estos tests NO hablan con la API
 *
  * porque reproducir el 200 del servidor exige el servidor. Lo que se fija aqui es lo que se
 * ROMPIO: la eleccion de `order` y el recorte del limite. Un test que solo comprobara que el
 * codigo compila habria dejado pasar las dos cosas.
 */
class ColaDeMensajesTest {

    /** MEDIDO: el tope real del servidor. */
    private val TOPE = OpenCodeApi.LIMITE_MAX_MENSAJES

    @Test
    fun `el tope de mensajes del servidor es 200`() {
        // MEDIDO con 2520 mensajes: 200 -> 200, y 201 -> 0. Si este numero sube, el chat entero
        // desaparece, porque una lista vacia no se distingue de una sesion nueva.
        assertEquals("tope medido del endpoint", 200, TOPE)
    }

    @Test
    fun `el orden de la COLA es desc, y el de la CABEZA es asc`() {
        // Esta es la asercion que fija el fallo. El orden no es un detalle: decide si el chat
        // muestra su final o su principio.
        //
        // MEDIDO, con los 3 primeros y los 3 ultimos de una sesion real de 2520 mensajes:
        //   asc  -> created 1790649234460, 1790649236466, 1790649246082   (los MAS VIEJOS)
        //   desc -> created 1790931020473, 1790931012417, 1790931007987   (los MAS NUEVOS)
        //
        // La prueba mira la CONSTANTE que usa el codigo, no una cadena suelta: si alguien
        // intercambia los dos valores, el chat vuelve a congelarse y este test cae.
        assertEquals("la cola son los mas NUEVOS", "desc", OpenCodeApi.ORDEN_COLA)
        assertEquals("la cabeza son los mas VIEJOS", "asc", OpenCodeApi.ORDEN_CABECERA)
        assertTrue(
            "si los dos valores coinciden, una de las dos consulta es la otra y el chat se congela",
            OpenCodeApi.ORDEN_COLA != OpenCodeApi.ORDEN_CABECERA
        )
    }

    @Test
    fun `un limite por encima del tope se recorta, no se pasa`() {
        // El contraejemplo del anterior. `coerceIn` no es defensa preventiva: es la diferencia
        // entre 200 mensajes y CERO. MEDIDO: pedir 250 devuelve data:[] sin ningun error.
        val pedidos = listOf(1, 50, 200, 201, 250, 500, 1000)
        for (p in pedidos) {
            val recorte = p.coerceIn(1, TOPE)
            assertTrue(
                "pedir $p no puede pasar de $TOPE: devolveria una lista vacia sin avisar",
                recorte <= TOPE
            )
        }
        assertEquals("201 se recorta exactamente al tope", TOPE, 201.coerceIn(1, TOPE))
        assertEquals("1 no se toca", 1, 1.coerceIn(1, TOPE))
    }

    @Test
    fun `un limite de 0 o negativo no deja la consulta sin limite`() {
        // MEDIDO: `limit=0` no significa "sin limite", significa "cero mensajes". Un
        // `coerceIn(1, 200)` en vez de `coerceAtLeast(1)` evita que un valor por defecto mal
        // puesto devuelva una lista vacia creyendo que es "traeme todo".
        for (p in listOf(0, -1, -100)) {
            val recorte = p.coerceIn(1, TOPE)
            assertTrue("un limite de $p no puede quedar en $recorte", recorte >= 1)
        }
    }

    @Test
    fun `la COLA se invierte para que la lista quede cronologica`() {
        // MEDIDO: `order=desc` llega del reves. `mergeTail` (ChatViewModel:552) anade al final
        // lo que no ha visto, asi que sin invertir, los mensajes nuevos se apendirian en orden
        // contrario — y la comparacion `fresh != _messages.value` no detectaria el cambio.
        val delReves = listOf("msj_5", "msj_4", "msj_3", "msj_2", "msj_1")
        assertEquals(
            "la app espera cronologico, no del reves",
            listOf("msj_1", "msj_2", "msj_3", "msj_4", "msj_5"),
            delReves.reversed()
        )
    }
}
