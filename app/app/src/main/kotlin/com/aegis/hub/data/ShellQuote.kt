package com.aegis.hub.data

/**
 * Escapado de argumentos para `sh -c`. Fuente unica: no duplicar comillas a mano.
 *
 * Sin dependencias de Android a proposito, para poder probarlo en JVM
 * (`ShellQuoteTest`). Todo comando que se pase a `RootShell.exec` (que acaba en
 * `su -c`) debe interpolar sus argumentos con [quote]: las comillas simples no
 * se escapan solas y un `'` dentro del valor cierra el literal y ejecuta lo que
 * venga detras como root.
 */
object ShellQuote {

    /**
     * Devuelve el argumento entrecomillado con comillas simples, seguro para `sh -c`.
     *
     * Dentro de `'...'` el shell no interpreta nada (`;`, `|`, `&`, `$`, backticks,
     * `\n`, `\`): todo es literal. El unico caracter especial es la propia comilla
     * simple, que se cierra, se escapa fuera de comillas y se reabre: `'` -> `'\''`.
     */
    fun quote(arg: String): String = "'" + arg.replace("'", "'\\''") + "'"

    /**
     * true si el nombre es un slug seguro (sin separadores de ruta ni metacaracteres de shell).
     *
     * Decision: se PERMITEN espacios y acentos (`con espacio` es valido) porque son
     * nombres de skill legitimos y [quote] ya los neutraliza; lo que se bloquea son
     * los caracteres que cambian el significado del comando o que sacan del directorio
     * de skills (`'`, `"`, `;`, `$`, backtick, `\`, saltos de linea, `/`, `|`, `&`, NUL).
     * Se exige ademas que no contenga `..` para no dejar subir por el arbol.
     */
    fun esSlugSeguro(nombre: String): Boolean =
        nombre.isNotBlank() && nombre.length <= 64 &&
            nombre.none {
                it == '\'' || it == '"' || it == ';' || it == '$' || it == '`' ||
                    it == '\\' || it == '\n' || it == '\r' || it == '/' || it == '|' ||
                    it == '&' || it == '\u0000'
            } &&
            !nombre.contains("..")
}
