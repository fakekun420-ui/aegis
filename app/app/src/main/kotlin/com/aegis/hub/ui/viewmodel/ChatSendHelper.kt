package com.aegis.hub.ui.viewmodel

import com.aegis.hub.data.AttachedFile
import com.aegis.hub.data.Message
import com.aegis.hub.data.MessageDeliveryStatus
import com.aegis.hub.data.MessageInfo
import com.aegis.hub.data.MessagePart

/**
 * G4/F9: Despiece de logica pura para el flujo de envio de mensajes (sendWithFiles).
 */
object ChatSendHelper {

    /**
     * Construye la clave para la guarda anti doble envio (FASE A-5).
     */
    fun buildSendKey(
        selectedProvider: String?,
        sessionId: String,
        text: String,
        filesCount: Int
    ): String = "${selectedProvider ?: ""}|${sessionId.trim()}|${text.trim()}|$filesCount"

    /**
     * Calcula el titulo provisional de la sesion si es nueva y el texto es no blanco.
     */
    fun computeProvisionalTitle(
        text: String,
        sessionId: String,
        currentSessionId: String?
    ): String? {
        val sesionEsNueva = sessionId.isBlank() && currentSessionId.isNullOrBlank()
        if (text.isNotBlank() && sesionEsNueva) {
            val clean = text.replace("\n", " ").trim()
            return if (clean.length > 30) clean.take(30).trim() + "…" else clean
        }
        return null
    }

    /**
     * Prepara el mensaje optimista del usuario con estado PENDING.
     */
    fun buildOptimisticMessage(
        tempMsgId: String,
        text: String,
        files: List<AttachedFile>
    ): Message {
        val optimisticParts = mutableListOf<MessagePart>()
        if (text.isNotBlank()) {
            optimisticParts += MessagePart(type = "text", text = text)
        }
        for (f in files) {
            when {
                f.text != null -> optimisticParts += MessagePart(
                    type = "text",
                    text = "Archivo ${f.name} (${f.mime}):\n```\n${f.text.take(30000)}\n```",
                    filename = f.name,
                    mime = f.mime
                )
                f.base64 != null -> optimisticParts += MessagePart(
                    type = "file",
                    filename = f.name,
                    mime = f.mime,
                    url = "data:${f.mime};base64,${f.base64}"
                )
            }
        }
        return Message(
            info = MessageInfo(id = tempMsgId, role = "user", status = MessageDeliveryStatus.PENDING),
            parts = optimisticParts
        )
    }

    /**
     * Prepara las partes de la peticion (payload parts) que se enviaran al servidor.
     */
    fun buildRequestParts(
        text: String,
        files: List<AttachedFile>
    ): List<Map<String, String>> {
        val reqParts = mutableListOf<Map<String, String>>()
        if (text.isNotBlank()) reqParts += mapOf("type" to "text", "text" to text)
        for (f in files) {
            when {
                f.text != null -> reqParts += mapOf(
                    "type" to "text",
                    "text" to "Archivo ${f.name} (${f.mime}):\n```\n${f.text.take(30000)}\n```"
                )
                f.base64 != null -> {
                    val dataUri = "data:${f.mime};base64,${f.base64}"
                    reqParts += mapOf("type" to "file", "mime" to f.mime, "filename" to f.name, "url" to dataUri)
                }
            }
        }
        return reqParts
    }
}
