package com.aegis.hub.data

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Persistencia y gestión del manifiesto de estado de instalación (bootstrap) en la aplicación Aegis.
 *
 * Contrato exacto:
 * STEP_DEFS (5 pasos, id y título literal):
 *   1. "preflight" -> "Comprobación previa"
 *   2. "ubuntu"    -> "Ubuntu (chroot/proot)"
 *   3. "node"      -> "Node.js"
 *   4. "opencode"  -> "OpenCode"
 *   5. "skills"    -> "Skills y plugins"
 *
 * Fichero persistente:
 *   Por defecto en `/sdcard/projects/Aegis/backend/bootstrap-state.json`.
 *   Permite inyección de ruta o File para tests JVM independientes sin tocar disco real.
 */
class BootstrapNative(
    private val stateFile: File = File(DEFAULT_STATE_FILE_PATH),
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    data class StepDef(val id: String, val title: String)

    companion object {
        const val DEFAULT_STATE_FILE_PATH = "/sdcard/projects/Aegis/backend/bootstrap-state.json"

        val STEP_DEFS: List<StepDef> = listOf(
            StepDef("preflight", "Comprobación previa"),
            StepDef("ubuntu", "Ubuntu (chroot/proot)"),
            StepDef("node", "Node.js"),
            StepDef("opencode", "OpenCode"),
            StepDef("skills", "Skills y plugins")
        )

        private val VALID_PHASES = setOf(
            BootstrapPhase.idle,
            BootstrapPhase.running,
            BootstrapPhase.paused,
            BootstrapPhase.failed,
            BootstrapPhase.done
        )

        fun createInitialSteps(): List<BootstrapStep> {
            return STEP_DEFS.map { def ->
                BootstrapStep(
                    id = def.id,
                    title = def.title,
                    status = BootstrapStepStatus.pending,
                    rollback = BootstrapRollback.none,
                    progress = 0,
                    detail = "pendiente",
                    error = null
                )
            }
        }

        fun createInitialState(isoDate: String): BootstrapState {
            return BootstrapState(
                phase = BootstrapPhase.idle,
                currentStepId = null,
                startedAt = null,
                updatedAt = isoDate,
                lastError = null,
                steps = createInitialSteps()
            )
        }
    }

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    private fun nowIso(): String {
        val instant = java.time.Instant.ofEpochMilli(clock())
        return instant.toString()
    }

    @Synchronized
    fun readState(): BootstrapState {
        if (!stateFile.exists()) {
            return createInitialState(nowIso())
        }

        return try {
            val content = stateFile.readText(StandardCharsets.UTF_8).trim()
            if (content.isEmpty()) {
                createInitialState(nowIso())
            } else {
                val parsed = gson.fromJson(content, BootstrapState::class.java)
                normalize(parsed)
            }
        } catch (_: Exception) {
            createInitialState(nowIso())
        }
    }

    @Synchronized
    fun writeState(state: BootstrapState): Boolean {
        return try {
            val parent = stateFile.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            // Patrón de escritura atómica con archivo temporal en el mismo directorio
            val tmpFile = File(parent ?: stateFile.absoluteFile.parentFile, "${stateFile.name}.tmp.${clock()}")
            val json = gson.toJson(state)
            tmpFile.writeText(json, StandardCharsets.UTF_8)
            val success = tmpFile.renameTo(stateFile)
            if (!success) {
                // Fallback si renameTo falla (e.g. en algunos montajes)
                tmpFile.copyTo(stateFile, overwrite = true)
                tmpFile.delete()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun updateState(
        phase: BootstrapPhase? = null,
        startedAt: String? = null,
        lastError: String? = null,
        currentStepId: String? = null
    ): BootstrapState {
        val current = readState()
        val updated = current.copy(
            phase = phase ?: current.phase,
            startedAt = if (startedAt != null) startedAt else current.startedAt,
            lastError = if (lastError != null) lastError else current.lastError,
            currentStepId = if (currentStepId != null) currentStepId else current.currentStepId,
            updatedAt = nowIso()
        )
        writeState(updated)
        return updated
    }

    @Synchronized
    fun updateStep(
        stepId: String,
        status: BootstrapStepStatus? = null,
        progress: Int? = null,
        detail: String? = null,
        error: String? = null,
        rollback: BootstrapRollback? = null
    ): BootstrapState {
        val current = readState()
        val currentSteps = current.stepList
        val newSteps = currentSteps.map { step ->
            if (step.id == stepId) {
                step.copy(
                    status = status ?: step.status,
                    progress = progress ?: step.progress,
                    detail = detail ?: step.detail,
                    error = if (error != null) error else step.error,
                    rollback = rollback ?: step.rollback
                )
            } else {
                step
            }
        }

        val runningStep = newSteps.firstOrNull { it.statusOrPending == BootstrapStepStatus.running }
        val newCurrentId = runningStep?.id

        val updated = current.copy(
            steps = newSteps,
            currentStepId = newCurrentId,
            updatedAt = nowIso()
        )
        writeState(updated)
        return updated
    }

    private fun normalize(raw: BootstrapState?): BootstrapState {
        val defaultState = createInitialState(nowIso())
        if (raw == null) return defaultState

        val normPhase = if (raw.phase != null && VALID_PHASES.contains(raw.phase)) raw.phase else BootstrapPhase.idle
        val normStartedAt = raw.startedAt
        val normUpdatedAt = raw.updatedAt ?: nowIso()
        val normLastError = raw.lastError

        val rawStepsById = (raw.steps ?: emptyList()).associateBy { it.id }

        val normSteps = STEP_DEFS.map { def ->
            val existing = rawStepsById[def.id]
            if (existing != null) {
                BootstrapStep(
                    id = def.id,
                    title = def.title,
                    status = existing.status ?: BootstrapStepStatus.pending,
                    rollback = existing.rollback ?: BootstrapRollback.none,
                    progress = existing.progress ?: 0,
                    detail = existing.detail ?: "",
                    error = existing.error
                )
            } else {
                BootstrapStep(
                    id = def.id,
                    title = def.title,
                    status = BootstrapStepStatus.pending,
                    rollback = BootstrapRollback.none,
                    progress = 0,
                    detail = "pendiente",
                    error = null
                )
            }
        }

        // Si el estado en disco quedó como "running" pero nadie está corriendo (reinicio de app),
        // se normaliza a "paused" y el paso running vuelve a "pending" para ser reanudable
        val finalPhase = if (normPhase == BootstrapPhase.running) BootstrapPhase.paused else normPhase
        val sanitizedSteps = normSteps.map { s ->
            if (normPhase == BootstrapPhase.running && s.statusOrPending == BootstrapStepStatus.running) {
                s.copy(status = BootstrapStepStatus.pending, detail = "interrumpido por reinicio — pendiente de reanudar")
            } else {
                s
            }
        }

        val running = sanitizedSteps.firstOrNull { it.statusOrPending == BootstrapStepStatus.running }

        return BootstrapState(
            phase = finalPhase,
            currentStepId = running?.id,
            startedAt = normStartedAt,
            updatedAt = normUpdatedAt,
            lastError = normLastError,
            steps = sanitizedSteps
        )
    }
}
