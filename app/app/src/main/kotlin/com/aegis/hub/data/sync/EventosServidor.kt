package com.aegis.hub.data.sync

import com.aegis.hub.data.OpenCodeApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.ResponseBody
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * UNA sola conexion SSE a `GET /api/event` mientras la app esta en primer plano (F5).
 *
 * Emite lineas crudas; cada `ChatSync` las parsea con su propio `EventStreamParser`
 * filtrando por su sesion (el parseador compartido no sirve: filtra por sid).
 * Reconexion con backoff 1 -> 2 -> 4 -> 8 -> max 15 s.
 */
interface FlujoEventos {
    /** Lineas crudas del stream (`data: ...`, `: heartbeat`, ...). */
    val lineas: SharedFlow<String>
    val conexion: StateFlow<EventosServidor.Conexion>
}

class EventosServidor(
    private val scope: CoroutineScope,
    private val abrir: suspend () -> ResponseBody = { OpenCodeApi.default.openEventStream() }
) : FlujoEventos {

    sealed interface Conexion {
        data object Conectado : Conexion
        data object Reconectando : Conexion
        data class Caido(val motivo: String?) : Conexion
    }

    companion object {
        const val ESPERA_INICIAL_MS = 1_000L
        const val ESPERA_MAX_MS = 15_000L
    }

    private val _conexion = MutableStateFlow<Conexion>(Conexion.Reconectando)
    override val conexion: StateFlow<Conexion> = _conexion

    private val _lineas = MutableSharedFlow<String>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val lineas: SharedFlow<String> = _lineas.asSharedFlow()

    private var trabajo: Job? = null

    /** Conexion compartida por todos los chats (una sola, ver T-F5.1). */
    object Compartida {
        private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val servidor = EventosServidor(ambito)
    }

    /** Idempotente: si ya hay conexion en curso no lanza otra. */
    fun iniciar() {
        if (trabajo?.isActive == true) return
        trabajo = scope.launch { bucle() }
    }

    fun detener() {
        trabajo?.cancel()
        trabajo = null
    }

    private suspend fun bucle() {
        var espera = ESPERA_INICIAL_MS
        while (coroutineContext.isActive) {
            try {
                abrir().use { cuerpo ->
                    espera = ESPERA_INICIAL_MS
                    _conexion.value = Conexion.Conectado
                    BufferedReader(InputStreamReader(cuerpo.byteStream(), Charsets.UTF_8))
                        .forEachLine { linea ->
                            if (linea.isNotBlank()) _lineas.emit(linea)
                        }
                    // Fin limpio del stream sin error: reconectar igual (el servidor
                    // lo corta; no es un estado estable).
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
            if (!coroutineContext.isActive) break
            _conexion.value = Conexion.Reconectando
            delay(espera)
            espera = (espera * 2).coerceAtMost(ESPERA_MAX_MS)
        }
    }
}
