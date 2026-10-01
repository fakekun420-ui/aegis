package com.aegis.hub.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aegis.hub.data.AuthGuideData
import com.aegis.hub.data.AuthGuideResponse
import com.aegis.hub.data.BootstrapPhase
import com.aegis.hub.data.BootstrapState
import com.aegis.hub.data.FinalCheckResponse
import com.aegis.hub.data.SetupCheckStatus
import com.aegis.hub.data.SetupNative
import com.aegis.hub.ui.viewmodel.BootstrapUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ViewModel nativo para el asistente de instalación y verificación de Aegis.
 *
 * Funciona de manera 100% autónoma en la aplicación Android SIN depender del Hub Node.js:
 * - Emite exactamente [BootstrapUiState] para que [com.aegis.hub.ui.screens.SetupWizardScreen]
 *   pueda consumirlo sin modificar su interfaz visual.
 * - Conecta con [SetupNative] para ejecutar y consultar los 5 pasos del instalador y los 2 checks.
 * - Mantiene el polling de estado cada 1000 ms durante la fase `running`.
 * - Maneja verificación final y smoke test directo contra OpenCode.
 */
class SetupNativeViewModel(
    private val setupNative: SetupNative = SetupNative(),
    /**
     * MEDIDO 2026-10-01: scope inyectable, y no por gusto.
     *
     * Este ViewModel hace `stateFlow.collect { ... }` en su `init`, y eso **nunca termina**. En
     * un `runTest`, el cuerpo del test espera a que TODAS las corrutinas del ambito terminen, luego
     * el test se queda esperando un collect eterno y nunca sale. CI lo ENSENO asi: el primer test
     * de `runFinalCheck` fallaba con `AssertionError` en su PROPIA declaracion (no en un assert)
     * y el segundo con `UncaughtExceptionsBeforeTest`. Los dos son la misma causa, y ningun
     * asercion estaba mal.
     *
     * Sin inyeccion no hay arreglo possible en el test: `viewModelScope` es final y no se puede
     * sustituir. Con ella, el test le pasa un `backgroundScope`, que es la via que da la biblioteca
     * para corrutinas que deben vivir mas que el test y se cancelan al terminar.
     *
     * No lo comparo con una teoria, lo comparo con `BootstrapViewModel`, cuyo test SI pasa: ese
     * NO hace `collect` en su init, luego no deja ninguna corrutina colgando. La diferencia entre
     * los dos ficheros explica los dos resultados.
     */
    scopeOverride: CoroutineScope? = null
) : ViewModel() {

    private val scope: CoroutineScope = scopeOverride ?: viewModelScope

    private val _ui = MutableStateFlow(
        BootstrapUiState(
            state = setupNative.getSnapshot(),
            loading = false,
            hubReachable = true
        )
    )
    val ui: StateFlow<BootstrapUiState> = _ui.asStateFlow()

    private var pollJob: Job? = null
    private var finalCheckJob: Job? = null
    private var smokeJob: Job? = null
    private var actionJob: Job? = null

    init {
        refresh()
        // Escuchar cambios de estado reactivos desde SetupNative.
        // MEDIDO 2026-10-01: este era `viewModelScope.launch` y es el que hacia fallar los tests.
        // Un `collect` no termina nunca, asi que en un `runTest` sin scope inyectable se colgaba.
        // Ver la nota del constructor, que explica el fallo con su sintoma exacto de CI.
        scope.launch {
            setupNative.stateFlow.collect { st ->
                _ui.value = _ui.value.copy(
                    state = st,
                    hubReachable = true
                )
                if (st.phaseOrIdle == BootstrapPhase.running) {
                    startPolling()
                }
            }
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        finalCheckJob?.cancel()
        smokeJob?.cancel()
        actionJob?.cancel()
        super.onCleared()
    }

    fun refresh(silent: Boolean = false) {
        if (!silent) _ui.value = _ui.value.copy(loading = true)
        val st = setupNative.getSnapshot()
        _ui.value = _ui.value.copy(
            state = st,
            loading = false,
            hubReachable = true
        )
        if (st.phaseOrIdle == BootstrapPhase.running) {
            startPolling()
        }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive && _ui.value.state?.phaseOrIdle == BootstrapPhase.running) {
                delay(1000)
                refresh(silent = true)
            }
        }
    }

    fun start() {
        actionJob?.cancel()
        actionJob = scope.launch {
            _ui.value = _ui.value.copy(loading = true, actionError = null)
            val result = setupNative.runBootstrap(resume = true)
            if (result.isFailure) {
                _ui.value = _ui.value.copy(
                    actionError = result.exceptionOrNull()?.message ?: "Error iniciando instalación"
                )
            }
            refresh(silent = true)
            _ui.value = _ui.value.copy(loading = false)
        }
    }

    fun retry(stepId: String) {
        actionJob?.cancel()
        actionJob = scope.launch {
            _ui.value = _ui.value.copy(loading = true, actionError = null)
            val result = setupNative.runBootstrap(resume = true, retryStepId = stepId)
            if (result.isFailure) {
                _ui.value = _ui.value.copy(
                    actionError = result.exceptionOrNull()?.message ?: "Error reintentando paso $stepId"
                )
            }
            refresh(silent = true)
            _ui.value = _ui.value.copy(loading = false)
        }
    }

    fun cancel() {
        setupNative.cancelExecution()
        refresh(silent = false)
    }

    fun runFinalCheck() {
        finalCheckJob?.cancel()
        finalCheckJob = scope.launch {
            _ui.value = _ui.value.copy(finalCheckLoading = true, actionError = null)
            try {
                val resp = setupNative.runFinalCheck()
                _ui.value = _ui.value.copy(
                    finalCheck = resp,
                    finalCheckLoading = false
                )
                val pendiente = resp.data?.checkList?.firstOrNull { it.statusOrManual != SetupCheckStatus.ok }
                if (pendiente != null) {
                    guideAuth()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(
                    finalCheckLoading = false,
                    actionError = "Error en comprobación final: ${e.message}"
                )
            }
        }
    }

    fun runSmokeTest() {
        smokeJob?.cancel()
        smokeJob = scope.launch {
            _ui.value = _ui.value.copy(smokeLoading = true, smokeReply = null, smokeError = null)
            try {
                val resp = setupNative.runSmokeTest()
                if (resp.ok && resp.data != null) {
                    _ui.value = _ui.value.copy(
                        smokeLoading = false,
                        smokeReply = resp.data.reply ?: "(sin respuesta)"
                    )
                } else {
                    val err = resp.error?.message ?: "El modelo no respondió"
                    _ui.value = _ui.value.copy(
                        smokeLoading = false,
                        smokeError = err
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(
                    smokeLoading = false,
                    smokeError = "Fallo en prueba del modelo: ${e.message}"
                )
            }
        }
    }

    fun guideAuth() {
        val dummyAuth = AuthGuideResponse(
            ok = true,
            data = AuthGuideData(
                mode = "command",
                command = "opencode serve --service",
                status = "unauthenticated"
            )
        )
        _ui.value = _ui.value.copy(
            authGuide = dummyAuth,
            authGuideLoading = false
        )
    }
}
