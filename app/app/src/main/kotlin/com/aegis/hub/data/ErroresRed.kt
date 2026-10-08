package com.aegis.hub.data

import com.google.gson.JsonParser

/**
 * Motivos de error de red legibles, desde cuerpos REALES (F1, T-F1.4).
 *
 * Formas capturadas el 2026-10-07 contra `:49374` vivo (ver
 * `app/src/test/resources/errores/`):
 * - Sesion inexistente: HTTP 404 + `{"_tag":"SessionNotFoundError", "message": ...}`.
 * - Sin credenciales: HTTP 401 + `{"_tag":"UnauthorizedError", "message": ...}`.
 * - POST a sesion inexistente: HTTP 404 + cuerpo VACIO.
 * - Modelo inexistente: HTTP 204 vacio (el servidor NO valida: no es un error aqui).
 *
 * Se entiende ademas el sobre viejo `{ok:false, error:{code, message}}` como respaldo.
 * Puramente funcional (Gson real) para poder probarse en JVM sin `android.jar`.
 */
object ErroresRed {

    fun parsear(crudo: String?, codigoHttp: Int? = null): String {
        if (crudo.isNullOrBlank()) return porCodigo(codigoHttp)
        val txt = crudo.trim()
        if (txt.startsWith("<")) {
            return "El servidor devolvió HTML en vez de JSON (¿proxy o puerto equivocado?)"
        }
        try {
            val elemento = JsonParser.parseString(txt)
            if (!elemento.isJsonObject) return txt.take(300)
            val obj = elemento.asJsonObject
            obj.get("message")?.takeIf { it.isJsonPrimitive }?.asString
                ?.takeIf { it.isNotBlank() }?.let { msg ->
                    val tag = obj.get("_tag")?.takeIf { it.isJsonPrimitive }?.asString
                        ?.takeIf { it.isNotBlank() }
                    return if (tag != null) "$tag: $msg" else msg
                }
            obj.get("error")?.takeIf { it.isJsonObject }?.asJsonObject?.let { err ->
                val msg = err.get("message")?.takeIf { it.isJsonPrimitive }?.asString
                    ?.takeIf { it.isNotBlank() }
                if (msg != null) {
                    val code = err.get("code")?.takeIf { it.isJsonPrimitive }?.asString
                        ?.takeIf { it.isNotBlank() }
                    return if (code != null) "$code: $msg" else msg
                }
            }
        } catch (_: Exception) {
            // No-JSON: cae al crudo recortado de abajo.
        }
        return txt.take(300)
    }

    private fun porCodigo(codigoHttp: Int?): String = when (codigoHttp) {
        401 -> "Sin credenciales: el servidor pide autenticación (401)"
        404 -> "No existe en el servidor (404): la sesión pudo borrarse desde el CLI"
        else -> "El servidor no aceptó el mensaje"
    }
}
