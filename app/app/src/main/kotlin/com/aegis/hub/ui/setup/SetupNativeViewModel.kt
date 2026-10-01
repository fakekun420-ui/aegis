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
    private val setupNative: SetupNative = SetupNative()
) : ViewModel() {

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
        // Escuchar cambios de estado reactivos desde SetupNative
        viewModelScope.launch {
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
        pollJob = viewModelScope.launch {
            while (isActive && _ui.value.state?.phaseOrIdle == BootstrapPhase.running) {
                delay(1000)
                refresh(silent = true)
            }
        }
    }

    fun start() {
        actionJob?.cancel()
        actionJob = viewModelScope.launch {
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
        actionJob = viewModelScope.launch {
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
        finalCheckJob = viewModelScope.launch {
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
        smokeJob = viewModelScope.launch {
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
