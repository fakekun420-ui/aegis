package com.aegis.hub.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F4 — tests JVM unitarios de [friendlyError] (F2): motivos crudos del motor de
 * instalación → mensaje guía en español y passthrough del raw.
 *
 * NOTA (documentada en la entrega F4): friendlyError NO tiene caso "timeout"
 * (a fecha de escritura no existe tal rama); el test de timeout verifica por
 * tanto el passthrough del raw, igual que un motivo desconocido.
 */
class FriendlyErrorTest {

    /** Cada motivo crudo conocido → su guía en español exacta. */
    @Test
    fun `motivos conocidos mapean a su guía en español`() {
        assertEquals(
            "La descarga no coincide con el SHA256 esperado: reintenta el paso",
            friendlyError("sha256 mismatch EBADCHECKSUM en paquete x")
        )
        assertEquals(
            "Fallo inyectado (modo de prueba del motor): nada quedó instalado; puedes reintentar el paso",
            friendlyError("FAIL_INJECTED para probar el manejo de errores")
        )
        assertEquals(
            "Este paso aún no está implementado en el motor de instalación: no puede completarse",
            friendlyError("paso x: ERR_NOT_IMPLEMENTED")
        )
        assertEquals(
            "Autenticación requerida: completa el login indicado (Antigravity/Artemis) y reintenta el paso",
            friendlyError("Autenticación fallida con el proveedor")
        )
        assertEquals(
            "Sin SHA256 del manifiesto no se descarga nada: falta la verificación del paquete",
            friendlyError("Falta SHA256 en el manifest del paquete")
        )
    }

    /** Sin coincidencia → el raw se devuelve intacto (nunca se sustituye). */
    @Test
    fun `raw desconocido se devuelve intacto`() {
        val raw = "Error inesperado del motor: algo no previsto"
        assertEquals(raw, friendlyError(raw))
    }

    /**
     * Passthrough de "timeout" (ver nota de clase: no hay caso timeout → raw).
     */
    @Test
    fun `timeout se devuelve intacto (sin caso específico en friendlyError)`() {
        val raw = "timeout esperando la respuesta"
        assertEquals(raw, friendlyError(raw))
    }

    /** Precedencia documentada: EBADCHECKSUM va antes que el resto. */
    @Test
    fun `EBADCHECKSUM tiene precedencia sobre otros códigos`() {
        assertEquals(
            "La descarga no coincide con el SHA256 esperado: reintenta el paso",
            friendlyError("FAIL_INJECTED EBADCHECKSUM Autenticación")
        )
    }
}
