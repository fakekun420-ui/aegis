package com.aegis.hub.ui.viewmodel

import android.util.Log
import com.aegis.hub.data.Message
import com.aegis.hub.data.PendingForm
import com.aegis.hub.data.PendingPermission
import com.aegis.hub.ui.TurnNotifier

/**
 * G4/F9: Helper puro para la deduccion y aviso de fin de turno del asistente.
 * Extraido de ChatViewModel para reducir complejidad y permitir cobertura de tests JVM pura.
 */
object ChatTurnHelper {

    /**
     * ¿Ha terminado DE VERDAD el turno del asistente?
     *
     * Reglas fijadas:
     * 1. Veto inmediato si alguna parte de un mensaje esta en ejecucion (running).
     * 2. Veto si hay formularios pendientes (el agente espera a la persona).
     * 3. Veto si hay permisos pendientes (la herramienta esta suspendida).
     * 4. Veto si el vigilante marca turnBusy.
     * 5. Si el vigilante marca turnOver -> true.
     * 6. Modo degradado (sin vigilante): mira el ultimo mensaje del asistente y comprueba
     *    si tiene marca "completed" en sus metadatos de tiempo.
     */
    fun turnIsReallyFinished(
        messages: List<Message>,
        pendingForms: List<PendingForm>,
        pendingPermissions: List<PendingPermission>,
        turnBusy: Boolean,
        turnOver: Boolean,
        onWarnNoWatcher: (() -> Unit)? = null
    ): Boolean {
        // Veto inmediato si alguna parte esta running
        if (messages.any { m -> m.parts.orEmpty().any { it.state?.status == "running" } }) {
            return false
        }
        if (pendingForms.isNotEmpty()) return false
        if (pendingPermissions.isNotEmpty()) return false
        if (turnBusy) return false
        if (turnOver) return true

        val last = messages.lastOrNull { it.role == "assistant" } ?: return false
        onWarnNoWatcher?.invoke()
        return last.info?.time?.containsKey("completed") == true
    }

    /**
     * Evalua si se debe emitir aviso de fin de turno y actualiza la deduplicacion.
     * Devuelve el nuevo finishedTurnId si se anuncio, o el actual si no hubo cambios.
     */
    fun announceFinishedTurnIfAny(
        fresh: List<Message>,
        lastFinishedTurnId: String?,
        isFinished: Boolean,
        sessionTitle: String?,
        sessionId: String?,
        notify: (title: String, preview: String, sessionId: String?) -> Unit = { title, preview, sid ->
            TurnNotifier.notifyTurnFinished(title, preview, sid)
        }
    ): String? {
        val lastAssistant = fresh.lastOrNull { it.role == "assistant" } ?: return lastFinishedTurnId
        val id = lastAssistant.info?.id ?: return lastFinishedTurnId
        if (!isFinished) return lastFinishedTurnId
        if (id == lastFinishedTurnId) return lastFinishedTurnId // ya anunciado

        val title = sessionTitle?.takeIf { it.isNotBlank() } ?: "Aegis"
        notify(
            "$title · respuesta final",
            lastAssistant.text.take(160),
            sessionId
        )
        return id
    }
}
