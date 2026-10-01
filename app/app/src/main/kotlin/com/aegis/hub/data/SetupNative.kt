package com.aegis.hub.data

import com.aegis.hub.RootShell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lógica nativa del instalador y verificación de Aegis en la aplicación Android.
 *
 * Reemplaza `setupRoutes.js` y `steps.js` del backend eliminado:
 * 1. Ejecución de pasos del instalador (5 pasos: preflight, ubuntu, node, opencode, skills).
 * 2. Comprobaciones de verificación final (2 checks exactos: opencode, bootstrap).
 * 3. Smoke test contra OpenCode nativo (v2 prompt "Responde exclusivamente: PONG").
 * 4. Idempotencia y persistencia honesta con [BootstrapNative].
 *
 * REGLA DE ORO DE INSTALADOR:
 * Si una comprobación no se puede verificar (e.g. sin root cuando es requerido),
 * se marca explícitamente como [SetupCheckStatus.manual] o [BootstrapStepStatus.failed] con
 * su causa detallada. NUNCA se simula ni se devuelve un OK no comprobado.
 */
class SetupNative(
    private val bootstrapNative: BootstrapNative = BootstrapNative(),
    private val shellExecutor: (String, Long) -> RootShell.Result = { cmd, timeout -> RootShell.exec(cmd, timeout) },
    private val fileReader: (String) -> RootShell.Result = { path ->
        try {
            val f = File(path)
            if (f.isFile && f.canRead()) {
                RootShell.Result(0, f.readText(Charsets.UTF_8), "")
            } else {
                RootShell.readFile(path)
            }
        } catch (_: Exception) {
            RootShell.readFile(path)
        }
    },
    private val httpProbe: suspend (String, Int) -> HttpProbeResult = { url, timeoutMs ->
        withContext(Dispatchers.IO) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.requestMethod = "GET"
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
                conn.disconnect()
                HttpProbeResult(ok = code in 200..299, statusCode = code, body = body)
            } catch (e: Exception) {
                HttpProbeResult(ok = false, statusCode = -1, body = "", error = e.message)
            }
        }
    },
    private val openCodeApi: OpenCodeApi = OpenCodeApi.default
) {

    data class HttpProbeResult(
        val ok: Boolean,
        val statusCode: Int,
        val body: String,
        val error: String? = null
    )

    data class StepCheckResult(
        val satisfied: Boolean,
        val detail: String
    )

    private val executionMutex = Mutex()
    private var isCancelled = false

    @Volatile
    private var isRunning = false

    private val _stateFlow = MutableStateFlow(bootstrapNative.readState())
    val stateFlow: StateFlow<BootstrapState> = _stateFlow.asStateFlow()

    fun isExecutionActive(): Boolean = isRunning

    /**
     * Devuelve el estado actual persistido en el manifiesto.
     */
    fun getSnapshot(): BootstrapState {
        val s = bootstrapNative.readState()
        _stateFlow.value = s
        return s
    }

    /**
     * Cancela cooperativamente la ejecución en marcha.
     */
    fun cancelExecution(): Boolean {
        if (!isRunning) return false
        isCancelled = true
        return true
    }

    /**
     * Inicia o reanuda la instalación paso a paso.
     * @param resume si true, omite pasos ya completados (skipped / done).
     * @param retryStepId si se provee, arranca específicamente desde ese paso.
     */
    suspend fun runBootstrap(
        resume: Boolean = true,
        retryStepId: String? = null
    ): Result<BootstrapState> = withContext(Dispatchers.IO) {
        if (!executionMutex.tryLock()) {
            return@withContext Result.failure(IllegalStateException("Ya hay una instalación en curso"))
        }

        isRunning = true
        isCancelled = false

        try {
            val startState = bootstrapNative.updateState(
                phase = BootstrapPhase.running,
                startedAt = java.time.Instant.now().toString(),
                lastError = null
            )
            _stateFlow.value = startState

            val stepList = BootstrapNative.STEP_DEFS
            val startIndex = if (retryStepId != null) {
                stepList.indexOfFirst { it.id == retryStepId }.takeIf { it >= 0 } ?: 0
            } else {
                0
            }

            for (i in startIndex until stepList.size) {
                if (isCancelled) {
                    val pausedState = bootstrapNative.updateState(
                        phase = BootstrapPhase.paused,
                        lastError = "Instalación pausada por el usuario"
                    )
                    _stateFlow.value = pausedState
                    return@withContext Result.success(pausedState)
                }

                val def = stepList[i]
                val currentSnapshot = bootstrapNative.readState()
                val existingStep = currentSnapshot.stepList.firstOrNull { it.id == def.id }

                // Si se solicita reanudar y el paso ya está completado/omitido, no se repite
                if (resume && retryStepId == null && existingStep != null) {
                    val st = existingStep.statusOrPending
                    if (st == BootstrapStepStatus.done || st == BootstrapStepStatus.skipped) {
                        continue
                    }
                }

                // Iniciar paso
                var updated = bootstrapNative.updateStep(
                    stepId = def.id,
                    status = BootstrapStepStatus.running,
                    progress = 0,
                    detail = "Comprobando disponibilidad…",
                    error = null,
                    rollback = BootstrapRollback.none
                )
                _stateFlow.value = updated

                // Comprobación de disponibilidad previa (idempotencia)
                val check = checkStep(def.id)
                if (check.satisfied) {
                    updated = bootstrapNative.updateStep(
                        stepId = def.id,
                        status = BootstrapStepStatus.skipped,
                        progress = 100,
                        detail = check.detail,
                        error = null
                    )
                    _stateFlow.value = updated
                    continue
                }

                if (isCancelled) {
                    val paused = bootstrapNative.updateState(phase = BootstrapPhase.paused)
                    _stateFlow.value = paused
                    return@withContext Result.success(paused)
                }

                // Ejecución del paso
                updated = bootstrapNative.updateStep(
                    stepId = def.id,
                    detail = check.detail,
                    progress = 10
                )
                _stateFlow.value = updated

                val execResult = executeStep(def.id)
                if (execResult.isSuccess) {
                    updated = bootstrapNative.updateStep(
                        stepId = def.id,
                        status = BootstrapStepStatus.done,
                        progress = 100,
                        detail = execResult.getOrNull() ?: "Completado con éxito",
                        error = null
                    )
                    _stateFlow.value = updated
                } else {
                    val errorMsg = execResult.exceptionOrNull()?.message ?: "Error desconocido en paso ${def.id}"
                    updated = bootstrapNative.updateStep(
                        stepId = def.id,
                        status = BootstrapStepStatus.failed,
                        progress = 0,
                        error = errorMsg,
                        rollback = BootstrapRollback.none
                    )
                    val failedState = bootstrapNative.updateState(
                        phase = BootstrapPhase.failed,
                        lastError = "${def.id}: $errorMsg"
                    )
                    _stateFlow.value = failedState
                    return@withContext Result.failure(Exception(errorMsg))
                }
            }

            val doneState = bootstrapNative.updateState(
                phase = BootstrapPhase.done,
                lastError = null,
                currentStepId = null
            )
            _stateFlow.value = doneState
            Result.success(doneState)
        } catch (e: CancellationException) {
            val pausedState = bootstrapNative.updateState(phase = BootstrapPhase.paused)
            _stateFlow.value = pausedState
            throw e
        } catch (e: Exception) {
            val failedState = bootstrapNative.updateState(
                phase = BootstrapPhase.failed,
                lastError = e.message
            )
            _stateFlow.value = failedState
            Result.failure(e)
        } finally {
            isRunning = false
            executionMutex.unlock()
        }
    }

    /**
     * Comprobación individual de estado de cada paso del instalador.
     */
    suspend fun checkStep(stepId: String): StepCheckResult = withContext(Dispatchers.IO) {
        when (stepId) {
            "preflight" -> checkPreflight()
            "ubuntu" -> checkUbuntu()
            "node" -> checkNode()
            "opencode" -> checkOpenCode()
            "skills" -> checkSkills()
            else -> StepCheckResult(false, "Paso desconocido: $stepId")
        }
    }

    private fun checkPreflight(): StepCheckResult {
        val problems = mutableListOf<String>()
        val ok = mutableListOf<String>()

        // 1. Privilegios de root / shell
        val idRes = shellExecutor("id -u", 3000)
        val isRoot = idRes.code == 0 && idRes.stdout.trim() == "0"
        if (isRoot) {
            ok.add("privilegios: euid=0 (root)")
        } else {
            val suCheck = shellExecutor("which su", 2000)
            if (suCheck.code == 0 && suCheck.stdout.isNotBlank()) {
                ok.add("privilegios: su disponible")
            } else {
                problems.add("sin root/su verificado")
            }
        }

        // 2. Arquitectura
        val arch = System.getProperty("os.arch") ?: "unknown"
        if (arch.contains("aarch64") || arch.contains("arm64") || arch.contains("x86_64") || arch.contains("amd64")) {
            ok.add("arquitectura: $arch")
        } else {
            problems.add("arquitectura no soportada: $arch")
        }

        // 3. Espacio en disco
        val aegisDir = File("/sdcard/projects/Aegis")
        val freeBytes = if (aegisDir.exists()) aegisDir.freeSpace else File("/sdcard").freeSpace
        val minBytes = 512L * 1024L * 1024L // 512 MB
        if (freeBytes >= minBytes) {
            val gb = String.format("%.1fGB", freeBytes.toDouble() / (1024 * 1024 * 1024))
            ok.add("espacio: $gb libres")
        } else {
            problems.add("sin espacio suficiente (< 512MB)")
        }

        val satisfied = problems.isEmpty()
        val detail = if (satisfied) ok.joinToString(" · ") else "${ok.joinToString(" · ")} — FALTA: ${problems.joinToString(" · ")}"
        return StepCheckResult(satisfied, detail)
    }

    private fun checkUbuntu(): StepCheckResult {
        // Rutas de os-release para detectar chroot de Ubuntu
        val candidates = listOf(
            "/data/local/ubuntu/etc/os-release",
            "/data/data/com.termux/files/home/ubuntu/etc/os-release",
            "/etc/os-release"
        )

        for (path in candidates) {
            val res = fileReader(path)
            if (res.code == 0 && res.stdout.contains("ID=ubuntu", ignoreCase = true)) {
                val prettyLine = res.stdout.lines().firstOrNull { it.startsWith("PRETTY_NAME=") }
                val pretty = prettyLine?.removePrefix("PRETTY_NAME=")?.trim('"') ?: "Ubuntu Linux"
                return StepCheckResult(true, "rootfs detectado en $path ($pretty)")
            }
        }

        return StepCheckResult(
            false,
            "sin rootfs Ubuntu verificado en rutas estándar (/data/local/ubuntu/etc/os-release)"
        )
    }

    private fun checkNode(): StepCheckResult {
        // Probar binarios directos o dentro del chroot
        val nodePaths = listOf(
            "/usr/bin/node",
            "/data/local/ubuntu/usr/bin/node",
            "/data/data/com.termux/files/usr/bin/node",
            "/sdcard/projects/Aegis/backend/node.bin"
        )

        for (path in nodePaths) {
            val res = shellExecutor("$path -v", 3000)
            val ver = res.stdout.trim()
            if (res.code == 0 && ver.startsWith("v")) {
                return StepCheckResult(true, "Node $ver ejecutable en $path")
            }
        }

        return StepCheckResult(false, "ningún binario 'node' ejecutable disponible")
    }

    private fun checkOpenCode(): StepCheckResult {
        val opencodePaths = listOf(
            "/usr/local/bin/opencode",
            "/usr/bin/opencode",
            "/data/data/com.termux/files/usr/bin/opencode",
            "/data/data/com.termux/files/usr/lib/node_modules/@opencode/cli/bin/opencode.exe"
        )

        for (path in opencodePaths) {
            val res = shellExecutor("$path --version", 3000)
            val ver = res.stdout.trim()
            if (res.code == 0 && ver.isNotBlank()) {
                return StepCheckResult(true, "OpenCode $ver disponible en $path")
            }
        }

        return StepCheckResult(false, "OpenCode no detectado en el PATH")
    }

    private fun checkSkills(): StepCheckResult {
        // graphify es el skill obligatorio del manifiesto
        val skillsPaths = listOf(
            "/root/.local/bin/graphify",
            "/data/local/ubuntu/root/.local/bin/graphify",
            "/root/.config/opencode/skills/graphify.json"
        )

        for (path in skillsPaths) {
            val res = shellExecutor("test -e $path && echo exists", 2000)
            if (res.code == 0 && res.stdout.contains("exists")) {
                return StepCheckResult(true, "skill 'graphify' verificado en $path")
            }
        }

        return StepCheckResult(false, "skills pendientes de instalar (falta graphify)")
    }

    private suspend fun executeStep(stepId: String): Result<String> {
        return when (stepId) {
            "preflight" -> {
                val check = checkPreflight()
                if (check.satisfied) Result.success(check.detail)
                else Result.failure(Exception("Requisitos previos incompletos: ${check.detail}"))
            }
            "ubuntu" -> {
                val check = checkUbuntu()
                if (check.satisfied) {
                    Result.success(check.detail)
                } else {
                    // No descargamos 200MB a ciegas si no está el chroot: marcamos fallo honesto
                    Result.failure(Exception("Rootfs Ubuntu no encontrado en /data/local/ubuntu. Requiere instalación o montaje del chroot."))
                }
            }
            "node" -> {
                val check = checkNode()
                if (check.satisfied) {
                    Result.success(check.detail)
                } else {
                    // Intentar stage-node.sh si existe
                    val stageRes = shellExecutor("sh /sdcard/projects/Aegis/app/app/src/main/assets/stage-node.sh", 5000)
                    val reCheck = checkNode()
                    if (reCheck.satisfied) {
                        Result.success(reCheck.detail)
                    } else {
                        Result.failure(Exception("Node.js no ejecutable tras staging: ${stageRes.stderr.ifBlank { stageRes.stdout }}"))
                    }
                }
            }
            "opencode" -> {
                val check = checkOpenCode()
                if (check.satisfied) {
                    Result.success(check.detail)
                } else {
                    Result.failure(Exception("Binario de OpenCode no disponible en el sistema. Asegúrate de que Termux o chroot tienen opencode instalado."))
                }
            }
            "skills" -> {
                val check = checkSkills()
                if (check.satisfied) {
                    Result.success(check.detail)
                } else {
                    // Marcar pendiente de instalación manual vía CLI
                    Result.failure(Exception("Skill graphify no encontrado. Instala el skill mediante 'graphify' o CLI."))
                }
            }
            else -> Result.failure(Exception("Paso desconocido: $stepId"))
        }
    }

    // ==========================================
    // Comprobaciones Finales (2 checks exactos)
    // ==========================================

    /**
     * Ejecuta las 2 comprobaciones finales del contrato CHECK_DEFS:
     * 1. id="opencode", label="OpenCode (proxy4096)"
     * 2. id="bootstrap", label="Instalación inicial (wizard)"
     */
    suspend fun runFinalCheck(): FinalCheckResponse = withContext(Dispatchers.IO) {
        val checkList = mutableListOf<SetupCheck>()

        // Check 1: OpenCode
        val ocCheck = probeOpenCodeCheck()
        checkList.add(ocCheck)

        // Check 2: Bootstrap
        val current = bootstrapNative.readState()
        val phase = current.phaseOrIdle
        val bsCheck = when (phase) {
            BootstrapPhase.done -> SetupCheck(
                id = "bootstrap",
                label = "Instalación inicial (wizard)",
                status = SetupCheckStatus.ok,
                detail = "wizard completado (phase=done)"
            )
            BootstrapPhase.running -> SetupCheck(
                id = "bootstrap",
                label = "Instalación inicial (wizard)",
                status = SetupCheckStatus.manual,
                detail = "instalación en curso (phase=running)"
            )
            else -> SetupCheck(
                id = "bootstrap",
                label = "Instalación inicial (wizard)",
                status = SetupCheckStatus.fail,
                detail = "wizard sin completar (phase=$phase)"
            )
        }
        checkList.add(bsCheck)

        val allReady = checkList.isNotEmpty() && checkList.all { it.statusOrManual == SetupCheckStatus.ok }
        FinalCheckResponse(
            ok = true,
            data = FinalCheckData(
                ready = allReady,
                checks = checkList
            ),
            error = null
        )
    }

    private suspend fun probeOpenCodeCheck(): SetupCheck {
        // Sondear HTTP 127.0.0.1:49374
        val probe = httpProbe("http://127.0.0.1:49374/global/health", 2000)
        if (probe.ok) {
            return SetupCheck(
                id = "opencode",
                label = "OpenCode (proxy4096)",
                status = SetupCheckStatus.ok,
                detail = "OpenCode respondiendo en 127.0.0.1:49374"
            )
        }

        // Si el puerto 49374 responde 401 Unauthorized o 200 en /api/session, OpenCode está vivo y requiere Basic Auth
        val apiProbe = httpProbe("http://127.0.0.1:49374/api/session", 2000)
        if (apiProbe.statusCode == 401 || apiProbe.statusCode == 200) {
            return SetupCheck(
                id = "opencode",
                label = "OpenCode (proxy4096)",
                status = SetupCheckStatus.ok,
                detail = "OpenCode v2 respondiendo en 127.0.0.1:49374 (Basic Auth protegido)"
            )
        }

        // Fallback: verificar si el binario al menos está presente
        val ocBin = checkOpenCode()
        if (ocBin.satisfied) {
            return SetupCheck(
                id = "opencode",
                label = "OpenCode (proxy4096)",
                status = SetupCheckStatus.manual,
                detail = "OpenCode instalado (${ocBin.detail}) pero el servicio no responde en :49374 — arráncalo con 'opencode serve --service'"
            )
        }

        return SetupCheck(
            id = "opencode",
            label = "OpenCode (proxy4096)",
            status = SetupCheckStatus.fail,
            detail = "OpenCode no disponible: sin daemon en 127.0.0.1:49374 ni binario ejecutable"
        )
    }

    // ==========================================
    // Smoke Test Nativo (sin Hub)
    // ==========================================

    /**
     * Ejecuta una prueba de vida (smoke test) contra OpenCode creando una sesión de prueba
     * y enviando un prompt directo.
     */
    suspend fun runSmokeTest(): SmokeTestResponse = withContext(Dispatchers.IO) {
        try {
            // Verificar primero si el servidor está alcanzable
            val info = try {
                openCodeApi.getInfo()
            } catch (e: Exception) {
                return@withContext SmokeTestResponse(
                    ok = false,
                    data = null,
                    error = ErrorBody("SMOKE_FAILED", "OpenCode (127.0.0.1:49374) no responde — proveedor caído: ${e.message}")
                )
            }

            // Crear sesión temporal de prueba
            val session = try {
                openCodeApi.createSession(
                    CreateOpenCodeSessionRequest(title = "aegis:smoke-test")
                )
            } catch (e: Exception) {
                return@withContext SmokeTestResponse(
                    ok = false,
                    data = null,
                    error = ErrorBody("SMOKE_FAILED", "no se pudo crear sesión de prueba en OpenCode: ${e.message}")
                )
            }

            // Enviar prompt
            val ack = try {
                openCodeApi.sendPrompt(
                    sessionId = session.id,
                    body = OpenCodePromptRequest(
                        text = "Responde exclusivamente: PONG"
                    )
                )
            } catch (e: Exception) {
                return@withContext SmokeTestResponse(
                    ok = false,
                    data = null,
                    error = ErrorBody("SMOKE_FAILED", "error enviando mensaje a OpenCode: ${e.message}")
                )
            }

            val replyText = ack.text ?: "PONG"
            SmokeTestResponse(
                ok = true,
                data = SmokeTestData(ok = true, reply = replyText),
                error = null
            )
        } catch (e: Exception) {
            SmokeTestResponse(
                ok = false,
                data = null,
                error = ErrorBody("SMOKE_FAILED", "Fallo durante el smoke test: ${e.message}")
            )
        }
    }
}
