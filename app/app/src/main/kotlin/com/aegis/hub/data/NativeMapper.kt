package com.aegis.hub.data

/**
 * Traductor canónico de entidades de la API nativa de OpenCode v2 a los modelos
 * consumidos históricamente por la UI de Aegis (Models.kt).
 *
 * Reemplaza la lógica distribuida en JavaScript del backend (providers.js: normalizeMessage
 * y _mapV2Message) permitiendo la conexión directa sin el Hub intermediario.
 *
 * Contrato de diseño:
 * 1. Funciones puras sin efectos secundarios ni dependencias de Android framework.
 * 2. Soporte para inyección explícita de reloj (timestamp) para reproducibilidad y tests deterministas.
 * 3. Mapeo fiel de roles, adjuntos m.files en top-level, bloques tool y reasoning, y eventos SSE de streaming.
 */
object NativeMapper {

    /**
     * Mapea un mensaje individual nativo de OpenCode ([OpenCodeMessage]) a la entidad [Message]
     * consumida por la UI de Aegis.
     *
     * @param m Mensaje plano en el formato v2 de OpenCode.
     * @param sessionId Identificador de la sesión (usado para generar IDs sintéticos si faltan).
     * @param index Índice posicional del mensaje en la lista de la conversación.
     * @param nowSupplier Función proveedora del timestamp actual en milisegundos (por defecto System.currentTimeMillis).
     */
    fun toMessage(
        m: OpenCodeMessage,
        sessionId: String = "",
        index: Int = 0,
        nowSupplier: () -> Long = { System.currentTimeMillis() }
    ): Message {
        // 1. Extracción de rol conforme a providers.js:
        // role = m.role || (m.type === "user" ? "user" : "assistant")
        // Si no es "user" ni "assistant", se fuerza "assistant".
        val rawRole = m.role ?: if (m.type == "user") "user" else "assistant"
        val role = if (rawRole == "user" || rawRole == "assistant") rawRole else "assistant"

        // 2. Extracción de timestamp base:
        // raw.info?.timestamp || raw.info?.time?.created || raw.timestamp || raw.time?.created || Date.now()
        val timestamp = m.time?.created ?: nowSupplier()

        // 3. Normalización y mapeo de partes (content[] y files[] top-level)
        var parts: MutableList<MessagePart>? = null

        // Mapeo inicial desde content[]
        val contentList = m.content
        if (!contentList.isNullOrEmpty()) {
            parts = contentList.mapIndexed { i, c ->
                val fallbackId = if (m.id.isNotEmpty()) m.id else index.toString()
                val partId = c.id ?: "prt_${fallbackId}_$i"
                val partType = c.type ?: "text"
                val partText = c.text ?: ""
                MessagePart(
                    id = partId,
                    type = partType,
                    text = partText,
                    tool = c.tool,
                    callID = c.callID,
                    state = c.state,
                    mime = c.mime
                )
            }.toMutableList()
        }

        // F7 (providers.js:629): Los adjuntos del usuario viven en m.files (top-level),
        // no en content[]. Sin este mapeo los adjuntos/imágenes desaparecen al recargar historial.
        val filesList = m.files
        if (!filesList.isNullOrEmpty()) {
            val fileParts = filesList.mapIndexed { i, f ->
                val mime = f.mime ?: "application/octet-stream"
                val url = if (f.source?.type == "uri" && !f.source.uri.isNullOrEmpty()) {
                    f.source.uri
                } else if (!f.data.isNullOrEmpty()) {
                    "data:$mime;base64,${f.data}"
                } else {
                    null
                }
                val fallbackId = if (m.id.isNotEmpty()) m.id else index.toString()
                MessagePart(
                    id = f.id ?: "prt_${fallbackId}_f$i",
                    type = "file",
                    text = "",
                    filename = f.name,
                    mime = mime,
                    url = url
                )
            }

            if (parts == null) {
                parts = fileParts.toMutableList()
            } else {
                parts.addAll(fileParts)
            }

            // providers.js:646: Si tras añadir adjuntos NO hay ninguna parte de texto,
            // se antepone una vacía para mantener el contrato de la UI.
            if (parts.none { it.type == "text" }) {
                val fallbackId = if (m.id.isNotEmpty()) m.id else index.toString()
                parts.add(0, MessagePart(
                    id = "prt_${fallbackId}_t",
                    type = "text",
                    text = ""
                ))
            }
        }

        // Si no hubo content[] ni files[], se genera una parte por defecto de texto vacío
        val finalParts: List<MessagePart> = if (!parts.isNullOrEmpty()) {
            parts
        } else {
            listOf(
                MessagePart(
                    id = "prt_${timestamp}_0",
                    type = "text",
                    text = ""
                )
            )
        }

        // 4. Construcción del objeto time:
        // Conserva el mapa de tiempo original (created, updated, idle) para que la UI
        // pueda detectar el cierre y estados del turno.
        val timeMap = mutableMapOf<String, Any>()
        if (m.time?.created != null) {
            timeMap["created"] = m.time.created
        } else {
            timeMap["created"] = timestamp
        }
        if (m.time?.updated != null) {
            timeMap["updated"] = m.time.updated
        }
        if (m.time?.idle != null) {
            timeMap["idle"] = m.time.idle
        }

        val msgId = if (m.id.isNotEmpty()) {
            m.id
        } else {
            val sid = if (sessionId.isNotEmpty()) sessionId else "hub"
            "msg_${sid}_${timestamp}_$index"
        }

        val info = MessageInfo(
            id = msgId,
            role = role,
            time = timeMap,
            status = MessageDeliveryStatus.SENT
        )

        return Message(
            info = info,
            parts = finalParts
        )
    }

    /**
     * Mapea una lista de mensajes nativos preservando el orden y los índices.
     */
    fun toMessages(
        messages: List<OpenCodeMessage>?,
        sessionId: String = "",
        nowSupplier: () -> Long = { System.currentTimeMillis() }
    ): List<Message> {
        if (messages.isNullOrEmpty()) return emptyList()
        return messages.mapIndexed { idx, m ->
            toMessage(m, sessionId = sessionId, index = idx, nowSupplier = nowSupplier)
        }
    }

    /**
     * Convierte un fragmento o delta de texto recibido vía streaming SSE
     * (e.g. session.text.delta o session.text.ended) en un [MessagePart] de tipo "text".
     *
     * @param text Contenido textual del delta o acumulado.
     * @param partId ID opcional para la parte. Si no se suministra, se genera un ID sintético.
     * @param timestamp Timestamp opcional en milisegundos.
     */
    fun streamDeltaToPart(
        text: String,
        partId: String? = null,
        timestamp: Long = System.currentTimeMillis()
    ): MessagePart {
        return MessagePart(
            id = partId ?: "prt_stream_${timestamp}",
            type = "text",
            text = text
        )
    }
}
