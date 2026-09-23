package com.aegis.hub.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aegis.hub.data.ApiClient
import com.aegis.hub.data.BootstrapActionResponse
import com.aegis.hub.data.BootstrapError
import com.aegis.hub.data.BootstrapPhase
import com.aegis.hub.data.BootstrapRunRequest
import com.aegis.hub.data.BootstrapState
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import retrofit2.Response

/** Estado de la pantalla de configuración inicial (wizard de bootstrap). */
data class BootstrapUiState(
    val state: BootstrapState? = null,
    val loading: Boolean = false,
    val actionError: String? = null,
    val hubReachable: Boolean = true
)

/**
 * F1 — dominio de bootstrap contra /api/bootstrap/* (ApiClient + token del hub).
 *
 * - Polling en vivo cada 1000 ms mientras phase == running; carga única en el resto de fases.
 * - Red/403 → hubReachable=false ("Esperando el hub…") + reintento suave cada 5 s.
 * - error.code → mensajes en español; 409 ALREADY_RUNNING en run() = sólo recarga (sin error).
 * - Nunca lanza: todo fallo queda reflejado en el estado local.
 * F2: friendlyError() (top-level, al final de este archivo) traduce los códigos
 * crudos del motor de instalación a mensajes guía; el raw sigue visible en
 * SetupWizardScreen (cabecera y card del paso).
 */
class BootstrapViewModel : ViewModel() {

    private val _ui = MutableStateFlow(BootstrapUiState())
    val ui: StateFlow<BootstrapUiState> = _ui.asStateFlow()

    private val gson = Gson()
    private var pollJob: Job? = null
    private var hubRetryJob: Job? = null

    /** true → actionError procede de una acción del usuario: una carga exitosa no lo limpia. */
    private var pendingActionError = false

    init {
        refresh()
    }

    // Se detiene el polling y el reintento suave al destruir el ViewModel
    override fun onCleared() {
        pollJob?.cancel()
        hubRetryJob?.cancel()
        super.onCleared()
    }

    // ---- Carga de estado ----

    /** Carga única. silent=true la usa el polling sin alternar el indicador de carga. */
    fun refresh(silent: Boolean = false) {
        viewModelScope.launch { fetchState(silent) }
    }

    private suspend fun fetchState(silent: Boolean) {
        if (!silent) _ui.value = _ui.value.copy(loading = true)
        try {
            val resp = ApiClient.service.getBootstrapState()
            val st = resp.body()?.data
            when {
                resp.isSuccessful && st != null -> {
                    _ui.value = _ui.value.copy(state = st, loading = false, hubReachable = true)
                    hubRetryJob?.cancel()
                    if (!pendingActionError) _ui.value = _ui.value.copy(actionError = null)
                    if (st.phase == BootstrapPhase.running) startPolling()
                }
                // Sin token/permiso → tratado como hub inaccesible (mensaje de espera)
                resp.code() == 403 -> markUnreachable()
                resp.isSuccessful -> _ui.value = _ui.value.copy(loading = false, hubReachable = true)
                else -> {
                    // 5xx u otro error HTTP: conserva el estado local y lo muestra, nunca crashea
                    pendingActionError = false
                    _ui.value = _ui.value.copy(
                        loading = false,
                        actionError = "El hub respondió con error (HTTP ${resp.code()})"
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Fallo de red → estado local intacto + espera con reintento suave
            markUnreachable()
        }
    }

    // ---- Polling en vivo: 1000 ms mientras phase == running ----

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive && _ui.value.state?.phase == BootstrapPhase.running) {
                delay(1000)
                fetchState(silent = true)
            }
        }
    }

    private fun markUnreachable() {
        _ui.value = _ui.value.copy(loading = false, hubReachable = false)
        pollJob?.cancel()
        pollJob = null
        scheduleHubRetry()
    }

    /** Reintento suave cada 5000 ms; si sigue caído se reprograma (una ventana cada vez). */
    private fun scheduleHubRetry() {
        hubRetryJob?.cancel()
        hubRetryJob = viewModelScope.launch {
            delay(5000)
            fetchState(silent = true)
        }
    }

    // ---- Acciones del wizard ----

    /** idle/paused/failed → POST /api/bootstrap/run {resume:true}; recarga inmediata al terminar. */
    fun start() {
        runAction { ApiClient.service.runBootstrap(BootstrapRunRequest(resume = true)) }
    }

    /** failed → POST /api/bootstrap/step/{id}/retry; recarga inmediata al terminar. */
    fun retry(stepId: String) {
        runAction { ApiClient.service.retryBootstrapStep(stepId) }
    }

    /** running → POST /api/bootstrap/cancel; recarga inmediata al terminar. */
    fun cancel() {
        runAction { ApiClient.service.cancelBootstrap() }
    }

    private fun runAction(call: suspend () -> Response<BootstrapActionResponse>) {
        viewModelScope.launch {
            pendingActionError = false
            _ui.value = _ui.value.copy(loading = true, actionError = null)
            try {
                val resp = call()
                if (resp.isSuccessful) {
                    _ui.value = _ui.value.copy(actionError = null)
                } else {
                    val err = parseErrorBody(resp)
                    if (err?.code == "ALREADY_RUNNING") {
                        // 409 previsto de run(): sólo recarga, sin error visible
                    } else {
                        pendingActionError = true
                        _ui.value = _ui.value.copy(actionError = actionErrorMessage(err, resp.code()))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Red/403 → bloqueo "Esperando el hub…" + reintento a los 5 s
                markUnreachable()
            } finally {
                // Tras cada acción → recarga inmediata del estado
                fetchState(silent = true)
                _ui.value = _ui.value.copy(loading = false)
            }
        }
    }

    /**
     * Retrofit NO convierte los errorBody (4xx/5xx): el envelope {ok,error:{code,message}}
     * se parsea a mano. Cualquier cuerpo inesperado → null (sin excepción).
     */
    private fun parseErrorBody(resp: Response<BootstrapActionResponse>): BootstrapError? = try {
        val text = resp.errorBody()?.string()
        if (text.isNullOrBlank()) null
        else gson.fromJson(text, BootstrapActionResponse::class.java)?.error
    } catch (e: Exception) {
        null
    }

    /** error.code → mensaje en español (contrato); fallback: message del hub o HTTP n.
     *  F2: si el message del hub arrastra un código conocido del motor (EBADCHECKSUM…),
     *  se prepende la guía friendlyError() sin perder el texto raw. */
    private fun actionErrorMessage(err: BootstrapError?, httpCode: Int): String = when (err?.code) {
        "ALREADY_RUNNING" -> "Ya hay una instalación en curso"
        "NOT_RETRYABLE" -> "Este paso no está en estado de error"
        "NOT_RUNNING" -> "No hay nada en curso"
        "NOT_FOUND" -> "Paso no encontrado"
        else -> err?.message?.let { m ->
            val guide = friendlyError(m)
            if (guide != m) "$guide\n$m" else m
        } ?: "Error del hub (HTTP $httpCode)"
    }
}

/**
 * F2 — motivo crudo (step.error / state.lastError / error.message) → mensaje guía
 * en español. El texto raw COMPLETO sigue visible en el wizard (cabecera bajo
 * "Se detuvo en…" y card del paso): aquí SÓLO se añade orientación, nunca se
 * sustituye. Detección por contención de los prefijos/códigos conocidos del motor
 * de instalación (sin regex); sin coincidencia → se devuelve el raw intacto.
 */
fun friendlyError(raw: String): String = when {
    raw.contains("EBADCHECKSUM") ->
        "La descarga no coincide con el SHA256 esperado: reintenta el paso"
    raw.contains("FAIL_INJECTED") ->
        "Fallo inyectado (modo de prueba del motor): nada quedó instalado; puedes reintentar el paso"
    raw.contains("ERR_NOT_IMPLEMENTED") ->
        "Este paso aún no está implementado en el motor de instalación: no puede completarse"
    raw.contains("Autenticación") ->
        "Autenticación requerida: completa el login indicado (Antigravity/Artemis) y reintenta el paso"
    raw.contains("Falta SHA256") ->
        "Sin SHA256 del manifiesto no se descarga nada: falta la verificación del paquete"
    else -> raw
}
