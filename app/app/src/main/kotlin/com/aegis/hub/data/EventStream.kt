package com.aegis.hub.data

import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.StringReader
import kotlin.coroutines.coroutineContext

/**
 * Cliente SSE y procesador de eventos para la conexión directa con OpenCode (`GET /api/event`).
 *
 * Cumple los 6 requisitos arquitectónicos:
 * 1. Emisión de eventos fuertemente tipados ([OpenCodeEvent]).
 * 2. Filtrado estricto por [targetSessionId] (los eventos de otras sesiones se descartan).
 * 3. Conexión resiliente con reconexión y exponential backoff (evita tormentas de peticiones contra un servidor caído).
 * 4. Detección de huecos de secuencia (seq gap) en hitos durables ([DurableMetadata.seq]).
 * 5. Estrategia contra duplicación de texto:
 *    - Los eventos `session.text.delta` transmiten incrementos parciales de texto.
 *    - El evento `session.text.ended` transmite el acumulado consolidado completo.
 *    - El procesador utiliza un acumulador interno que se alimenta de los deltas y, al recibir `ended`,
 *      consolida y verifica el contenido acumulado sin duplicar.
 * 6. Testabilidad pura sin red: lógica de parseo, filtrado y detección de huecos expuesta de forma desacoplada
 *    ([EventStreamParser]).
 */
object EventStream {

    private const val TAG = "EventStream"
    private const val INITIAL_BACKOFF_MS = 1000L
    private const val MAX_BACKOFF_MS = 30000L
    private const val BACKOFF_FACTOR = 2.0

    /**
     * Abre un [Flow] infinito y resiliente conectado a `GET /api/event`, filtrando por [targetSessionId].
     * Aplica reconexión automática con exponential backoff en caso de corte o error de red.
     */
    fun createEventStream(
        targetSessionId: String,
        api: OpenCodeApi = OpenCodeApi.default,
        parser: EventStreamParser = EventStreamParser()
    ): Flow<OpenCodeStreamItem> = flow {
        var currentBackoff = INITIAL_BACKOFF_MS

        while (coroutineContext.isActive) {
            try {
                val responseBody = api.openEventStream()
                // Si la conexión fue exitosa, reiniciamos el backoff
                currentBackoff = INITIAL_BACKOFF_MS

                val reader = BufferedReader(InputStreamReader(responseBody.byteStream(), Charsets.UTF_8))
                try {
                    var line: String?
                    while (coroutineContext.isActive && reader.readLine().also { line = it } != null) {
                        val items = parser.processLine(line ?: "", targetSessionId)
                        for (item in items) {
                            emit(item)
                        }
                    }
                } finally {
                    try { reader.close() } catch (_: Exception) {}
                    try { responseBody.close() } catch (_: Exception) {}
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.w(TAG, "Conexión SSE interrumpida: ${e.message}. Reintentando en ${currentBackoff}ms...")
            }

            if (coroutineContext.isActive) {
                delay(currentBackoff)
                currentBackoff = (currentBackoff * BACKOFF_FACTOR).toLong().coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }.flowOn(Dispatchers.IO)
}

/**
 * Representa los elementos emitidos por el stream ya procesados y tipados.
 */
sealed class OpenCodeStreamItem {
    data class Event(val event: OpenCodeEvent) : OpenCodeStreamItem()
    data class TextUpdate(
        val sessionID: String,
        val textAccumulated: String,
        val isEnded: Boolean
    ) : OpenCodeStreamItem()
    data class SequenceGapDetected(
        val sessionID: String,
        val expectedSeq: Long,
        val actualSeq: Long
    ) : OpenCodeStreamItem()
}

/**
 * Modelo de evento genérico tipado de OpenCode v2.
 */
data class OpenCodeEvent(
    val id: String? = null,
    val created: Long? = null,
    val type: String? = null,
    val durable: DurableMetadata? = null,
    val data: Map<String, Any?>? = null
) {
    val sessionID: String?
        get() = data?.get("sessionID") as? String

    val textDelta: String?
        get() = data?.get("delta") as? String

    val endedText: String?
        get() = data?.get("text") as? String

    val callID: String?
        get() = data?.get("callID") as? String

    val tool: String?
        get() = data?.get("tool") as? String

    val input: Map<String, Any?>?
        @Suppress("UNCHECKED_CAST")
        get() = data?.get("input") as? Map<String, Any?>

    val output: String?
        get() = data?.get("output") as? String

    val exitCode: Int?
        get() = (data?.get("exitCode") as? Number)?.toInt()

    val duration: Double?
        get() = (data?.get("duration") as? Number)?.toDouble()
}

/**
 * Metadatos de persistencia y ordenación secuencial de hitos durables.
 */
data class DurableMetadata(
    val aggregateID: String? = null,
    val seq: Long? = null,
    val version: Long? = null
)

/**
 * Motor puro de parseo, filtrado y detección de estado para SSE de OpenCode.
 * Desacoplado de OkHttp/red para permitir tests unitarios exhaustivos deterministas.
 */
class EventStreamParser(
    private val gson: Gson = GsonBuilder().setLenient().create()
) {
    private var lastDurableSeq: Long? = null
    private val textAccumulator = StringBuilder()

    fun getLastDurableSeq(): Long? = lastDurableSeq

    fun getAccumulatedText(): String = textAccumulator.toString()

    fun reset() {
        lastDurableSeq = null
        textAccumulator.setLength(0)
    }

    /**
     * Procesa una única línea cruda del protocolo SSE.
     * Ignora comentarios (ej: `: keepalive`), líneas vacías y data malformada sin abortar el stream.
     * Filtra los eventos que no pertenezcan a [targetSessionId].
     */
    fun processLine(rawLine: String, targetSessionId: String): List<OpenCodeStreamItem> {
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(":")) {
            // Línea vacía o comentario keepalive SSE
            return emptyList()
        }

        if (!trimmed.startsWith("data:")) {
            return emptyList()
        }

        val jsonPayload = trimmed.removePrefix("data:").trim()
        if (jsonPayload.isEmpty()) {
            return emptyList()
        }

        val event = try {
            gson.fromJson(jsonPayload, OpenCodeEvent::class.java)
        } catch (_: Exception) {
            return emptyList()
        } ?: return emptyList()

        return processEvent(event, targetSessionId)
    }

    /**
     * Procesa un texto continuo simulando un stream crudo completo de SSE.
     */
    fun processRawStream(rawSseStream: String, targetSessionId: String): List<OpenCodeStreamItem> {
        val reader = BufferedReader(StringReader(rawSseStream))
        val result = mutableListOf<OpenCodeStreamItem>()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            result.addAll(processLine(line ?: "", targetSessionId))
        }
        return result
    }

    /**
     * Lógica de procesamiento de un evento tipado:
     * - Filtro por sessionID.
     * - Detección de huecos de secuencia (seq).
     * - Normalización de texto acumulado vs deltas.
     */
    fun processEvent(event: OpenCodeEvent, targetSessionId: String): List<OpenCodeStreamItem> {
        val eventSessionId = event.sessionID
        if (eventSessionId != targetSessionId) {
            // Filtrado: evento descartado por pertenecer a otra sesión
            return emptyList()
        }

        val items = mutableListOf<OpenCodeStreamItem>()

        // 1. Detección de huecos en hitos durables (seq)
        val currentSeq = event.durable?.seq
        if (currentSeq != null) {
            val previousSeq = lastDurableSeq
            if (previousSeq != null && currentSeq > previousSeq + 1) {
                items.add(
                    OpenCodeStreamItem.SequenceGapDetected(
                        sessionID = targetSessionId,
                        expectedSeq = previousSeq + 1,
                        actualSeq = currentSeq
                    )
                )
            }
            lastDurableSeq = currentSeq
        }

        // Emitimos el evento tipado original
        items.add(OpenCodeStreamItem.Event(event))

        // 2. Gestión de texto acumulado vs delta
        when (event.type) {
            "session.text.delta" -> {
                val delta = event.textDelta
                if (!delta.isNullOrEmpty()) {
                    textAccumulator.append(delta)
                    items.add(
                        OpenCodeStreamItem.TextUpdate(
                            sessionID = targetSessionId,
                            textAccumulated = textAccumulator.toString(),
                            isEnded = false
                        )
                    )
                }
            }
            "session.text.ended" -> {
                val consolidated = event.endedText
                if (consolidated != null) {
                    // Estrategia no-duplicación:
                    // `ended` provee la versión canónica acumulada por el motor.
                    // En lugar de añadir `consolidated` al acumulador (lo que duplicaría el texto),
                    // sincronizamos el acumulador directamente con el consolidado canónico.
                    textAccumulator.setLength(0)
                    textAccumulator.append(consolidated)
                    items.add(
                        OpenCodeStreamItem.TextUpdate(
                            sessionID = targetSessionId,
                            textAccumulated = consolidated,
                            isEnded = true
                        )
                    )
                }
            }
        }

        return items
    }
}
