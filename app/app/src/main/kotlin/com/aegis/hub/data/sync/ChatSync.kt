package com.aegis.hub.data.sync

import com.aegis.hub.data.EventStreamParser
import com.aegis.hub.data.Message
import com.aegis.hub.data.OpenCodeStreamItem
import com.aegis.hub.data.PendingForm
import com.aegis.hub.data.PendingPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Sincronizacion de UN chat abierto (F5).
 *
 * - **Un solo escritor** de `_mensajes`: todo el que quiera pintar pasa por aqui.
 * - Con SSE conectado (`PorEventos`): los eventos de la sesion actual mueven `turno`
 *   y `textoEnVivo`; reconciliacion por cola al abrir, al terminar turno, cada 15 s
 *   de seguridad y tras reconectar. Formularios/permisos al abrir, al terminar turno
 *   y cada 5 s mientras `turno == Ocupado` (el stream no trae eventos propios:
 *   medido en la captura 2026-10-07).
 * - Con SSE caido (`Respaldo`): el bucle actual (cola + ocupados + forms + permisos)
 *   con backoff 2 s -> 10 s. Es el comportamiento de hoy, no una novedad.
 * - `SinServidor`: ni eventos ni respaldo responden (la UI ya muestra su banner).
 *
 * Dependencias estrechas (lambdas) para probar sin servidor ni Android.
 */
class ChatSync(
    private val scope: CoroutineScope,
    private val eventos: FlujoEventos,
    private val leerCola: suspend (sid: String) -> List<Message>,
    private val leerFormularios: suspend (sid: String) -> List<PendingForm> = { emptyList() },
    private val leerPermisos: suspend (sid: String) -> List<PendingPermission> = { emptyList() },
    private val leerOcupados: suspend () -> Set<String> = { emptySet() }
) {
    sealed interface EstadoSync {
        data object PorEventos : EstadoSync
        data object Respaldo : EstadoSync
        data object SinServidor : EstadoSync
    }

    companion object {
        const val RECONCILIAR_CADA_MS = 15_000L
        const val FORMS_CADA_MS = 5_000L
        const val RESPALDO_MIN_MS = 2_000L
        const val RESPALDO_MAX_MS = 10_000L
    }

    private val _mensajes = MutableStateFlow<List<Message>>(emptyList())
    val mensajes: StateFlow<List<Message>> = _mensajes

    private val _textoEnVivo = MutableStateFlow<String?>(null)
    val textoEnVivo: StateFlow<String?> = _textoEnVivo

    private val _turno = MutableStateFlow<EstadoTurno>(EstadoTurno.Ocioso)
    val turno: StateFlow<EstadoTurno> = _turno

    private val _formularios = MutableStateFlow<List<PendingForm>>(emptyList())
    val formularios: StateFlow<List<PendingForm>> = _formularios

    private val _permisos = MutableStateFlow<List<PendingPermission>>(emptyList())
    val permisos: StateFlow<List<PendingPermission>> = _permisos

    private val _estado = MutableStateFlow<EstadoSync>(EstadoSync.Respaldo)
    val estado: StateFlow<EstadoSync> = _estado

    private var sidActual: String? = null
    private var trabajo: Job? = null
    private val parser = EventStreamParser()

    /** Abre una sesion: cancela la anterior (misma guardia anti-carrera que F3). */
    fun abrir(sid: String) {
        cerrar()
        sidActual = sid
        trabajo = scope.launch {
            parser.reset()
            _textoEnVivo.value = null
            _turno.value = EstadoTurno.Ocioso
            _estado.value = if (eventos.conexion.value is EventosServidor.Conexion.Conectado) {
                EstadoSync.PorEventos
            } else {
                EstadoSync.Respaldo
            }
            refrescarTodo(sid)
            launch { vigilarConexion(sid) }
            launch { escucharEventos(sid) }
            launch { bucleSeguridad(sid) }
            launch { bucleFormularios(sid) }
            launch { bucleRespaldo(sid) }
        }
    }

    fun cerrar() {
        trabajo?.cancel()
        trabajo = null
        sidActual = null
    }

    /** Para pull-to-refresh y tras enviar. */
    suspend fun refrescarAhora() {
        val sid = sidActual ?: return
        _mensajes.value = fusionar(_mensajes.value, leerColaSegura(sid))
    }

    /** El mensaje optimista del envio; la reconciliacion lo confirma por id. */
    fun insertarOptimista(mensaje: Message) {
        _mensajes.value = _mensajes.value + mensaje
    }

    /** Transicion de estado de un mensaje local (p. ej. PENDING -> ERROR). */
    fun actualizarMensaje(tempId: String, f: (Message) -> Message) {
        _mensajes.value = _mensajes.value.map { if (it.info?.id == tempId) f(it) else it }
    }

    private suspend fun vigilarConexion(sid: String) {
        var era: EventosServidor.Conexion? = null
        eventos.conexion.collect { c ->
            if (sidActual != sid) return@collect
            _estado.value = if (c is EventosServidor.Conexion.Conectado) {
                EstadoSync.PorEventos
            } else {
                EstadoSync.Respaldo
            }
            // Al reconectar se reconcilia: lo emitido durante el corte se perdio.
            // Solo en transicion (no en la emision inicial, que abrir() ya cargo).
            if (era != null && era !is EventosServidor.Conexion.Conectado &&
                c is EventosServidor.Conexion.Conectado
            ) {
                refrescarTodo(sid)
            }
            era = c
        }
    }

    private suspend fun escucharEventos(sid: String) {
        eventos.lineas.collect { linea ->
            if (sidActual != sid) return@collect
            if (_estado.value != EstadoSync.PorEventos) return@collect
            for (item in parser.processLine(linea, sid)) {
                if (sidActual != sid) break
                aplicar(item, sid)
            }
        }
    }

    private fun aplicar(item: OpenCodeStreamItem, sid: String) {
        when (item) {
            is OpenCodeStreamItem.TextUpdate -> {
                if (item.isEnded) {
                    _textoEnVivo.value = null
                    scope.launch { reconciliar(sid) }
                } else {
                    _textoEnVivo.value = item.textAccumulated
                    _turno.value = EstadoTurno.Ocupado
                }
            }
            is OpenCodeStreamItem.Event -> {
                val e = item.event
                _turno.value = avanzarTurno(_turno.value, e.type, e.durable?.seq)
                if (e.type == "session.execution.succeeded") {
                    scope.launch {
                        reconciliar(sid)
                        refrescarInteraccion(sid)
                    }
                }
            }
            is OpenCodeStreamItem.SequenceGapDetected -> Unit
        }
    }

    /** Cada 15 s de seguridad: la reconciliacion que nunca se salta. */
    private suspend fun bucleSeguridad(sid: String) {
        while (scope.isActive && sidActual == sid) {
            delay(RECONCILIAR_CADA_MS)
            if (sidActual != sid) break
            if (_estado.value == EstadoSync.PorEventos) reconciliar(sid)
        }
    }

    /**
     * Formularios/permisos cada 5 s SOLO mientras el turno esta ocupado. Sin eventos
     * propios en el stream para ellos (medido), el poll corto mientras trabaja es el
     * precio de responderlos a tiempo; en reposo no cuesta nada.
     */
    private suspend fun bucleFormularios(sid: String) {
        while (scope.isActive && sidActual == sid) {
            delay(FORMS_CADA_MS)
            if (sidActual != sid) break
            if (_estado.value == EstadoSync.PorEventos && _turno.value is EstadoTurno.Ocupado) {
                refrescarInteraccion(sid)
            }
        }
    }

    /** Respaldo con SSE caido: lo que hace hoy el bucle viejo, con backoff 2 s -> 10 s. */
    private suspend fun bucleRespaldo(sid: String) {
        var espera = RESPALDO_MIN_MS
        var fallosSeguidos = 0
        while (scope.isActive && sidActual == sid) {
            if (_estado.value == EstadoSync.Respaldo) {
                val ok = runCatching {
                    _mensajes.value = fusionar(_mensajes.value, leerColaSegura(sid))
                    refrescarInteraccion(sid)
                    if (leerOcupados().contains(sid)) _turno.value = EstadoTurno.Ocupado
                    true
                }.getOrDefault(false)
                if (ok) {
                    espera = RESPALDO_MIN_MS
                    fallosSeguidos = 0
                } else {
                    espera = (espera * 2).coerceAtMost(RESPALDO_MAX_MS)
                    fallosSeguidos++
                    // Ni eventos ni respaldo responden: la UI ya muestra su banner.
                    if (fallosSeguidos >= 5) _estado.value = EstadoSync.SinServidor
                }
            }
            delay(espera)
        }
    }

    private suspend fun refrescarTodo(sid: String) {
        _mensajes.value = fusionar(_mensajes.value, leerColaSegura(sid))
        refrescarInteraccion(sid)
    }

    private suspend fun reconciliar(sid: String) {
        _mensajes.value = fusionar(_mensajes.value, leerColaSegura(sid))
    }

    private suspend fun refrescarInteraccion(sid: String) {
        _formularios.value = runCatching { leerFormularios(sid) }.getOrDefault(emptyList())
        _permisos.value = runCatching { leerPermisos(sid) }.getOrDefault(emptyList())
    }

    private suspend fun leerColaSegura(sid: String): List<Message> =
        runCatching { leerCola(sid) }.getOrDefault(emptyList())

    /**
     * Fusion por id: lo fresco manda donde coincide; lo local pendiente (p. ej. el
     * optimista `local_*`, que el servidor aun no trae) se conserva... salvo que ya
     * llego su eco: un `local_*` con mismo rol+texto que un mensaje con id de
     * servidor se empareja y sale (cada eco consume un solo optimista, asi que dos
     * "hola" seguidos siguen siendo dos). Sin esto el optimista duplicaria.
     */
    internal fun fusionar(actual: List<Message>, frescos: List<Message>): List<Message> {
        val ecosUsados = HashSet<String>()
        val sinEcos = actual.filterNot { m ->
            val id = m.info?.id
            id != null && id.startsWith("local_") && frescos.any { s ->
                val sid = s.info?.id
                sid != null && sid !in ecosUsados && sid != id &&
                    s.role == m.role && s.text == m.text && ecosUsados.add(sid)
            }
        }
        if (frescos.isEmpty()) return sinEcos
        val porId = frescos.mapNotNull { m -> m.info?.id?.let { it to m } }.toMap()
        if (porId.isEmpty()) return sinEcos
        val vistos = HashSet<String>()
        val fusion = ArrayList<Message>(sinEcos.size + frescos.size)
        for (m in sinEcos) {
            val id = m.info?.id
            if (id != null && porId.containsKey(id)) {
                fusion.add(porId.getValue(id))
                vistos.add(id)
            } else {
                fusion.add(m)
            }
        }
        for (m in frescos) {
            val id = m.info?.id ?: continue
            if (id !in vistos) {
                fusion.add(m)
                vistos.add(id)
            }
        }
        return fusion
    }
}
