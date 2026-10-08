package com.aegis.hub.util

private val RX_ID_TECNICO = Regex("^[0-9a-fA-F-]{8,}$")

/**
 * Un titulo (o id) es "tecnico" si no lo puso un humano: nulo, vacio, prefijo de
 * sesion (`ses_`, `companion:`, `local_`) o pinta de UUID. F7: una sola funcion
 * con el regex compilado una vez (antes duplicado en ViewModel y pantalla).
 */
fun esTituloTecnico(t: String?): Boolean {
    if (t == null) return true
    val s = t.trim()
    if (s.isBlank()) return true
    if (s.startsWith("ses_") || s.startsWith("companion:") || s.startsWith("local_")) return true
    if (s.matches(RX_ID_TECNICO)) return true
    return false
}
