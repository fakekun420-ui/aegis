package com.aegis.hub.ui.viewmodel

import android.util.Log
import com.aegis.hub.data.OpenCodeApi
import com.aegis.hub.data.repo.NuevaSesion
import com.aegis.hub.data.repo.Resultado
import com.aegis.hub.data.repo.SesionesRepo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Modelo de una pestaña abierta en la barra estilo TUI de OpenCode.
 *
 * [isCurrent] marca la pestaña activa (prefijo "⠆ " en la barra) y [isRunning]
 * marca que el backend (`:49374`, `GET /api/session/active`) tiene un turno en
 * curso para esa sesión.
 */
data class SessionTab(
    val sessionId: String,
    val title: String,
    val isRunning: Boolean = false,
    val isCurrent: Boolean = false,
)

/**
 * Gestor reactivo de pestañas de sesión para la barra superior estilo
 * navegador/TUI (la contraparte móvil de las pestañas del TUI de OpenCode).
 *
 * Contrato con el backend `:49374`:
 * 1. Cada pestaña apunta a una sesión REAL existente en OpenCode: aquí solo se
 *    guarda la referencia visual. Abrir pestaña nueva crea la sesión por
 *    [SesionesRepo.crear] (con su idempotencia anti doble pulsación); conmutar
 *    NO toca el servidor; cerrar pestaña NO borra la sesión (`DELETE
 *    /api/session/{id}` nunca se llama desde aquí): la sesión queda viva en el
 *    backend y en la lista de "Chats", solo se quita su pestaña.
 * 2. [SessionTab.isRunning] se reconcilia con el servidor ([refrescarEjecucion]);
 *    no se adivina en la UI.
 * 3. Estado como [StateFlow] de listas inmutables, mutaciones atomicas con
 *    `update { }`.
 */
class SessionTabManager(
    private val sesionesRepo: SesionesRepo = SesionesRepo(),
    private val api: OpenCodeApi = OpenCodeApi.default,
) {
    companion object {
        private const val TAG = "SessionTabManager"

        /** Titulo con el que nace la pestaña del botón "+" hasta que se renombre. */
        const val TITULO_NUEVO = "Nuevo chat"
    }

    private val _pestanas = MutableStateFlow<List<SessionTab>>(emptyList())

    /** Pestañas en orden de apertura; la activa es la única con isCurrent. */
    val pestanas: StateFlow<List<SessionTab>> = _pestanas.asStateFlow()

    /** Pestaña activa, null si la barra esta vacia. */
    val pestanaActual: SessionTab?
        get() = _pestanas.value.firstOrNull { it.isCurrent }

    /**
     * Conmutación instantánea (un tap): marca [sessionId] como activa y la abre
     * como pestaña si no lo estaba (se ancla al final, no se reordena la fila).
     * No llama al backend: el contenido lo carga su pantalla de chat de siempre.
     */
    fun abrirOConmutar(sessionId: String, titulo: String? = null) {
        if (sessionId.isBlank()) return
        _pestanas.update { lista ->
            val yaEsta = lista.any { it.sessionId == sessionId }
            val listaSinActivas = lista.map { tab ->
                if (tab.isCurrent) tab.copy(isCurrent = false) else tab
            }
            // Si la pestaña YA existía hay que marcarla ACTIVA: devolver solo la
            // lista sin activas dejaba la barra entera sin pestaña resaltada al
            // conmutar a una pestaña ya abierta.
            if (yaEsta) listaSinActivas.map {
                if (it.sessionId == sessionId) it.copy(isCurrent = true) else it
            } else listaSinActivas + SessionTab(
                sessionId = sessionId,
                title = titulo?.takeIf { it.isNotBlank() } ?: sessionId,
                isCurrent = true,
            )
        }
    }

    /** Alias para el tap en pestaña inactiva. */
    fun conmutar(sessionId: String) = abrirOConmutar(sessionId)

    /**
     * Cierra una pestaña SIN destruir la sesión del backend. Si era la activa,
     * queda activa la vecina de la izquierda (como el TUI); sin vecina ni filas,
     * la barra queda sin pestaña activa.
     */
    fun cerrarPestana(sessionId: String) {
        _pestanas.update { lista ->
            val indice = lista.indexOfFirst { it.sessionId == sessionId }
            if (indice < 0) return@update lista
            val restantes = lista.filterIndexed { i, _ -> i != indice }
            if (!lista[indice].isCurrent) return@update restantes
            val vecina = restantes.getOrNull(indice - 1) ?: restantes.getOrNull(indice)
            restantes.mapIndexed { i, t -> t.copy(isCurrent = i == restantes.indexOf(vecina)) }
        }
    }

    /** Titulo en vivo: lo llama la pantalla cuando el titulo real de la sesion cambia. */
    fun reetiquetar(sessionId: String, titulo: String) {
        _pestanas.update { lista ->
            lista.map { t ->
                if (t.sessionId == sessionId)
                    t.copy(title = titulo.ifBlank { sessionId }) else t
            }
        }
    }

    /** Pestañas marcadas actualmente. */
    fun contar(): Int = _pestanas.value.size

    /**
     * Botón "+": crea sesión NUEVA en el backend (una sola llamada a
     * `POST /api/session`, la misma vía que "+ Nuevo chat" y su idempotencia)
     * y abre su pestaña. Devuelve la pestaña creada, o null si fallo (el
     * motivo queda en el log; la UI enuclia el error donde ya lo hace).
     */
    suspend fun abrirNueva(carpeta: String? = SesionesRepo.CARPETA_POR_DEFECTO): SessionTab? {
        return when (val r = sesionesRepo.crear(NuevaSesion(titulo = TITULO_NUEVO, carpeta = carpeta))) {
            is Resultado.Ok -> {
                val tab = SessionTab(
                    sessionId = r.valor.id,
                    title = r.valor.titulo.ifBlank { TITULO_NUEVO },
                    isCurrent = true,
                )
                _pestanas.update { lista -> lista.map { it.copy(isCurrent = false) } + tab }
                tab
            }
            is Resultado.Fallo -> {
                Log.w(TAG, "abrirNueva: fallo al crear sesion: ${r.motivo}", r.causa)
                null
            }
        }
    }

    /**
     * Reconcilia [SessionTab.isRunning] con el estado REAL del servidor
     * (`GET /api/session/active`). Llamar en el mismo poll/SSE de siempre; si
     * el servidor no responde se conservan los estados actuales, no se borran.
     */
    suspend fun refrescarEjecucion() {
        try {
            val activas = api.getActiveSessions().data.orEmpty()
            _pestanas.update { lista ->
                lista.map { t ->
                    val corriendo = activas.containsKey(t.sessionId)
                    if (t.isRunning == corriendo) t else t.copy(isRunning = corriendo)
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "refrescarEjecucion: servidor no alcanzable, se conservan los estados", e)
        }
    }
}
