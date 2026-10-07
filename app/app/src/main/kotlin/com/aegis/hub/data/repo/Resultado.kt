package com.aegis.hub.data.repo

/**
 * Resultado total de una operacion del repo (F2).
 *
 * El exito parcial es un resultado, no un fallo: la sesion creada sin vinculo,
 * por ejemplo, existe y sirve; lo que falto va en [Ok.avisos]. El fallo total
 * lleva siempre un [Fallo.motivo] legible para la UI.
 */
sealed interface Resultado<out T> {
    /** Exito, posiblemente parcial: `avisos` lista lo que no se pudo completar. */
    data class Ok<T>(val valor: T, val avisos: List<String> = emptyList()) : Resultado<T>
    /** Fallo total. `motivo` SIEMPRE legible para el usuario; `causa` para el log. */
    data class Fallo(val motivo: String, val causa: Throwable? = null) : Resultado<Nothing>
}

inline fun <T, R> Resultado<T>.mapa(f: (T) -> R): Resultado<R> = when (this) {
    is Resultado.Ok -> Resultado.Ok(f(valor), avisos)
    is Resultado.Fallo -> this
}
