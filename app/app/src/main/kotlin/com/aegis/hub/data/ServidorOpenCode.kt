package com.aegis.hub.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Estado unico del servidor OpenCode (F6, T-F6.3).
 *
 * Sustituye la orquestacion que vivia en `MainActivity` (`startRootSystemAndPoll` +
 * reintentos sueltos): un solo camino con un solo estado observable. `MainActivity`
 * solo observa [estado] y pinta el overlay; no decide nada.
 *
 * MEDIDO 2026-10-08: `/data/adb/service.d/` NO trae hook de Aegis (solo
 * `.zn_cleanup.sh`); el servidor vivo viene de Termux, no del boot. Y el `flock`
 * del movil es toybox (solo descriptores), asi que la guarda anti-duplicado es el
 * `pgrep` con corchete de `aegis-serve.sh` + su cerrojo `mkdir` — no `flock`.
 */
sealed interface EstadoServidor {
    data object Comprobando : EstadoServidor
    data object Listo : EstadoServidor
    data class Arrancando(val intento: Int) : EstadoServidor
    data class Error(val motivo: String) : EstadoServidor
}

/**
 * Lo que devuelve un intento de lanzamiento (el `echo` de `aegis-serve.sh`
 * ya parseado por [OpenCodeLauncher]).
 */
sealed interface Lanzamiento {
    /** Ya habia un proceso vivo: no se lanzo otro, solo hay que esperar al puerto. */
    data object YaHay : Lanzamiento
    /** Se lanzo un servidor nuevo. */
    data object Lanzado : Lanzamiento
    /** No se pudo lanzar; [motivo] es el `ERROR:<motivo>` del script. */
    data class Error(val motivo: String) : Lanzamiento
}

object ServidorOpenCode {

    private val _estado = MutableStateFlow<EstadoServidor>(EstadoServidor.Comprobando)
    val estado: StateFlow<EstadoServidor> = _estado.asStateFlow()

    /**
     * Vuelta atras idempotente con un solo vuelo: dos `asegurar()` concurrentes
     * hacen UN lanzamiento (el segundo espera al Mutex y luego ve `Listo`).
     *
     * @param comprobarSiVivo sonda HTTP ya decidida fuera (necesita Basic de
     *   [Credentials]; 401/403 tambien es "vivo").
     * @param lanzar invoca `aegis-serve.sh` (ver [OpenCodeLauncher]).
     * @param demora punto de inyeccion para tests (por defecto, espera real).
     */
    suspend fun asegurar(
        comprobarSiVivo: suspend () -> Boolean,
        lanzar: suspend () -> Lanzamiento,
        sondeoMs: Long = 500L,
        maxSondeos: Int = 360,
        demora: suspend (Long) -> Unit = { delay(it) }
    ): EstadoServidor = mutex.withLock {
        _estado.value = EstadoServidor.Comprobando
        if (comprobarSiVivo()) {
            _estado.value = EstadoServidor.Listo
            return _estado.value
        }
        _estado.value = EstadoServidor.Arrancando(1)
        var lanzoAhora = false
        when (val l = lanzar()) {
            is Lanzamiento.Error -> {
                _estado.value = EstadoServidor.Error(l.motivo)
                return _estado.value
            }
            Lanzamiento.Lanzado -> lanzoAhora = true
            Lanzamiento.YaHay -> Unit // proceso vivo pero sin puerto: solo esperar.
        }
        var sondeos = 0
        while (sondeos < maxSondeos) {
            demora(sondeoMs)
            sondeos++
            if (comprobarSiVivo()) {
                _estado.value = EstadoServidor.Listo
                return _estado.value
            }
            // MEDIDO 2026-10-02 (heredado del codigo anterior): si el primer
            // lanzamiento no prendio, UN reintento mas a mitad de camino; mas
            // seria ruido contra un puerto ocupado.
            if (sondeos == 10 && !lanzoAhora) {
                when (val l2 = lanzar()) {
                    is Lanzamiento.Error -> {
                        _estado.value = EstadoServidor.Error(l2.motivo)
                        return _estado.value
                    }
                    Lanzamiento.Lanzado -> {
                        lanzoAhora = true
                        _estado.value = EstadoServidor.Arrancando(2)
                    }
                    Lanzamiento.YaHay -> Unit
                }
            }
        }
        _estado.value = EstadoServidor.Error(
            "OpenCode no responde en 127.0.0.1:49374 tras $maxSondeos sondeos. " +
                "Arranca 'opencode serve --service' (Termux) o reinicia el servicio registrado."
        )
        _estado.value
    }

    private val mutex = Mutex()
}
