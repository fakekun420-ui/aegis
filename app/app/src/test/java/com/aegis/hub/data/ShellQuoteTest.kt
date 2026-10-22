package com.aegis.hub.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Auditoria 2026-10-22: `RutaNativa` construia comandos con `'$x'` a mano y un nombre
 * como `x';id;'` cerraba el literal y se ejecutaba como root via `su -c`
 * (`RootShell.exec`). Estos tests fijan el escapado y el filtro de nombres.
 */
class ShellQuoteTest {

    /**
     * Interpreta un literal de shell de comillas simples y devuelve lo que `sh` veria.
     * Sirve para demostrar el escapado con el mismo algoritmo que usa el shell real:
     * `'` abre/cierra literal, y fuera de comillas solo `\'` produce una comilla.
     */
    private fun descomillarComosh(entrada: String): String {
        require(entrada.startsWith("'")) { "no empieza en comilla: $entrada" }
        val sb = StringBuilder()
        var i = 0
        while (i < entrada.length) {
            when (entrada[i]) {
                '\'' -> {
                    var j = i + 1
                    while (j < entrada.length && entrada[j] != '\'') {
                        sb.append(entrada[j])
                        j++
                    }
                    require(j < entrada.length) { "literal sin cerrar en $i: $entrada" }
                    i = j + 1
                }
                '\\' -> {
                    require(i + 1 < entrada.length && entrada[i + 1] == '\'') {
                        "escape no permitido en $i: $entrada"
                    }
                    sb.append('\'')
                    i += 2
                }
                else -> {
                    // Un caracter fuera de comillas no lo generaria quote(): seria el agujero.
                    throw AssertionError("caracter fuera de comillas en $i: '$entrada'")
                }
            }
        }
        return sb.toString()
    }

    @Test
    fun `quote de un nombre simple`() {
        assertEquals("'simple'", ShellQuote.quote("simple"))
    }

    @Test
    fun `quote escapa la comilla simple`() {
        // 'x' \'' ;id; '\'' '  -> cadena exacta, con toda comilla interna escapada
        val esperado = "'x'\\'';id;'\\'''"
        assertEquals(esperado, ShellQuote.quote("x';id;'"))
        assertTrue(ShellQuote.quote("x';id;'").startsWith("'"))
        assertTrue(ShellQuote.quote("x';id;'").endsWith("'"))
    }

    @Test
    fun `quote devuelve al shell el mismo argumento`() {
        // El roundtrip es la prueba fuerte: si `sh` leyera algo distinto del original,
        // habria inyeccion. Vale para separadores, comillas y saltos de linea.
        for (arg in listOf("simple", "x';id;'", "con espacio", "acentué", "a;b|c&d", "a\nb")) {
            assertEquals(arg, descomillarComosh(ShellQuote.quote(arg)))
        }
    }

    @Test
    fun `slug seguro acepta nombres legitimos`() {
        assertTrue(ShellQuote.esSlugSeguro("skill_buena"))
        assertTrue(ShellQuote.esSlugSeguro("skill-buena-2"))
        // Decision: se PERMITEN espacios y acentos; quote() ya los neutraliza y son
        // nombres de skill legitimamente usables. No se bloquea lo que no es meta-caracter.
        assertTrue("se permiten espacios (documentado en esSlugSeguro)", ShellQuote.esSlugSeguro("con espacio"))
        assertTrue(ShellQuote.esSlugSeguro("diseño con acentos"))
    }

    @Test
    fun `slug seguro rechaza metacaracteres y traversal`() {
        assertFalse(ShellQuote.esSlugSeguro("x';id;'"))
        assertFalse(ShellQuote.esSlugSeguro("../../etc/passwd"))
        assertFalse(ShellQuote.esSlugSeguro("a/b"))
        assertFalse(ShellQuote.esSlugSeguro("a;b"))
        assertFalse(ShellQuote.esSlugSeguro("a|b"))
        assertFalse(ShellQuote.esSlugSeguro("a&b"))
        assertFalse(ShellQuote.esSlugSeguro("a\$b"))
        assertFalse(ShellQuote.esSlugSeguro("a`id`b"))
        assertFalse(ShellQuote.esSlugSeguro("a\"b"))
        assertFalse(ShellQuote.esSlugSeguro(""))
        assertFalse(ShellQuote.esSlugSeguro("   "))
    }

    @Test
    fun `el vector original queda cerrado`() {
        val vector = "x';id;'"
        // 1) el filtro lo rechaza antes de construir ningun comando
        assertFalse(ShellQuote.esSlugSeguro(vector))
        // 2) y aunque llegara, quote() lo deja como un unico literal sin `;` ejecutable
        val citado = ShellQuote.quote(vector)
        assertEquals(vector, descomillarComosh(citado))
        // el `;` solo aparece dentro de comillas: al descomillar no queda nada "suelto"
        assertTrue(citado.contains(";id;"))
    }
}
