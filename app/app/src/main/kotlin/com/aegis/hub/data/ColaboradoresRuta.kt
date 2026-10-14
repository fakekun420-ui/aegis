package com.aegis.hub.data

import android.util.Log
import com.aegis.hub.data.repo.CatalogoRepo
import com.aegis.hub.data.repo.SesionConfigRepo
import com.aegis.hub.data.repo.Resultado
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import retrofit2.Response

/**
 * G4/F9: Colaborador para la gestion y entrega de mensajes y partes.
 */
class MensajesRutaNativa(
    private val oc: OpenCodeApi,
    private val config: SesionConfigRepo
) {
    companion object {
        private const val TAG = "AegisMensajesRuta"
    }

    private fun colaDe(tail: Int): Int {
        if (tail > OpenCodeApi.LIMITE_MAX_MENSAJES) {
            Log.w(TAG, "colaDe(): se piden $tail mensajes y el tope del servidor es " +
                "${OpenCodeApi.LIMITE_MAX_MENSAJES}. Se recorta a ${OpenCodeApi.LIMITE_MAX_MENSAJES}.")
        }
        return tail.coerceIn(1, OpenCodeApi.LIMITE_MAX_MENSAJES)
    }

    suspend fun getMessagesTail(sessionId: String, tail: Int): Envelope<List<Message>> {
        val limite = colaDe(tail)
        val r = oc.getMessages(sessionId, limit = limite, order = OpenCodeApi.ORDEN_COLA)
        return Envelope(ok = true, data = NativeMapper.toMessages(r.data, sessionId).reversed())
    }

    suspend fun getMessages(sessionId: String): Envelope<List<Message>> {
        val r = oc.getMessages(sessionId, limit = OpenCodeApi.LIMITE_MAX_MENSAJES, order = OpenCodeApi.ORDEN_COLA)
        return Envelope(ok = true, data = NativeMapper.toMessages(r.data, sessionId).reversed())
    }

    suspend fun getPart(sessionId: String, partId: String, messageId: String?): Envelope<PartFull> {
        val mensajes = oc.getMessages(sessionId).data.orEmpty()
        val encontradas = NativeMapper.toMessages(mensajes, sessionId)
        val parte = encontradas.asSequence()
            .flatMap { it.parts.orEmpty().asSequence() }
            .firstOrNull { it.id == partId || it.id == messageId }
            ?: return Envelope(ok = false, data = null)
        return Envelope(ok = true, data = PartFull(
            id = parte.id,
            type = parte.type,
            mime = parte.mime,
            filename = parte.filename,
            text = parte.text,
            url = parte.url,
            image = parte.image,
            data = parte.data
        ))
    }

    suspend fun sendMessage(
        sessionId: String,
        body: SendMessageRequest
    ): Message {
        val texto = body.parts
            .filter { it["type"] == "text" }
            .mapNotNull { it["text"] }
            .joinToString("\n")
            .trim()

        val ficheros = body.parts
            .filter { it["type"] == "file" }
            .mapNotNull { p ->
                val nombre = p["filename"] ?: p["name"] ?: return@mapNotNull null
                val mime = p["mime"]
                val datos = p["data"] ?: p["base64"] ?: p["url"] ?: p["uri"]
                    ?: return@mapNotNull null
                val uri = if (datos.startsWith("data:")) datos
                         else "data:${mime ?: "application/octet-stream"};base64,$datos"
                OpenCodePromptFileRef(
                    uri = uri,
                    name = nombre,
                    description = mime?.let { "mime: $it" }
                )
            }
            .takeIf { it.isNotEmpty() }

        val agentes = body.agent?.takeIf { it.isNotBlank() }?.let { listOf(OpenCodePromptAgentRef(name = it)) }

        val modeloId = ModelosUtil.normalizarIdModelo(body.model)
        if (modeloId.isNotEmpty()) {
            when (val r = config.fijarModelo(sessionId, body.model ?: modeloId, pistaProveedor = body.providerID)) {
                is Resultado.Fallo -> Log.w(TAG, "sendMessage: sigue sin fijar modelo: ${r.motivo}")
                else -> Unit
            }
        }

        oc.sendPrompt(
            sessionId,
            OpenCodePromptRequest(
                text = texto,
                files = ficheros,
                agents = agentes
            )
        )
        return Message()
    }
}

/**
 * G4/F9: Colaborador para la resolucion de formularios y permisos de sesion.
 */
class InteraccionesRutaNativa(
    private val oc: OpenCodeApi
) {
    suspend fun getPendingForms(sessionId: String?): Envelope<List<PendingForm>> =
        if (sessionId == null) Envelope(ok = true, data = emptyList())
        else Envelope(ok = true, data = oc.getSessionForms(sessionId).data ?: emptyList())

    suspend fun getPendingPermissions(sessionId: String?): Envelope<List<PendingPermission>> =
        if (sessionId == null) Envelope(ok = true, data = emptyList())
        else Envelope(ok = true, data = oc.getSessionPermissions(sessionId).data ?: emptyList())

    suspend fun replyForm(
        sessionId: String,
        formId: String,
        body: FormReplyBody
    ): Envelope<Map<String, Any>> {
        val r = oc.replyForm(
            sessionId,
            formId,
            OpenCodeFormReplyRequest(answer = body.answer.mapValues { it.value })
        )
        return if (r.isSuccessful) {
            Envelope(ok = true, data = mapOf("formId" to formId, "enviado" to true))
        } else {
            Envelope(ok = false, data = null, error = ErrorBody(code = "ERROR", message = "El formulario $formId no se pudo contestar (${r.code()})"))
        }
    }

    suspend fun replyPermission(
        sessionId: String,
        requestId: String,
        body: PermissionReplyBody
    ): Envelope<Map<String, Any>> {
        val validas = setOf("once", "always", "reject")
        if (body.decision !in validas) {
            return Envelope(ok = false, data = null, error = ErrorBody(code = "ERROR", message = "decision '${body.decision}' no vale. MEDIDO: solo ${validas.joinToString(", ")}."))
        }
        val r = oc.replyPermission(
            sessionId,
            requestId,
            OpenCodePermissionReplyRequest(decision = body.decision, message = body.message)
        )
        return if (r.isSuccessful) {
            Envelope(ok = true, data = mapOf("requestId" to requestId, "decision" to body.decision))
        } else {
            Envelope(ok = false, data = null, error = ErrorBody(code = "ERROR", message = "El permiso $requestId no se pudo contestar (${r.code()})"))
        }
    }
}

/**
 * G4/F9: Colaborador para la obtencion de catalogos de modelos y agentes.
 */
class CatalogoRutaNativa(
    private val catalogo: CatalogoRepo
) {
    suspend fun getOpencodeAgents(): Envelope<List<Agente>> {
        val lista = catalogo.agentesNativos().mapNotNull { a ->
            Agente(
                name = a.name,
                mode = a.mode ?: "primary",
                model = a.model?.id,
                description = a.description,
                hidden = a.hidden ?: false
            )
        }
        return Envelope(ok = true, data = lista.filter { it.mode == "primary" && !it.hidden })
    }

    suspend fun refrescarCatalogo(): Envelope<Boolean> {
        catalogo.invalidar()
        return Envelope(ok = true, data = true)
    }

    suspend fun getModels(provider: String?): Envelope<List<ModeloElegible>> {
        val todos = catalogo.modelosNativos()
            .filter { it.enabled }
            .filter { provider == null || it.providerID == provider || it.id == ID_MODELO_POR_DEFECTO }
            .sortedWith(
                compareBy<OpenCodeNativeModel> { if (catalogo.esGratis(it.id ?: it.modelID)) 0 else 2 }
                    .thenBy { if (it.providerID == "opencode") 0 else 1 }
            )
        val etiquetas = mapOf(
            "opencode" to "OpenCode Zen", "google" to "Google AI (API key)",
            "openrouter" to "OpenRouter"
        )
        val lista = todos.map { m ->
            ModeloElegible(
                id = m.id ?: m.modelID.orEmpty(),
                name = m.name ?: m.modelID ?: m.id,
                description = buildString {
                    append(etiquetas[m.providerID] ?: m.providerID)
                    append(" · ")
                    append(m.family ?: "AI")
                    if (catalogo.esGratis(m.id ?: m.modelID)) append(" · gratis")
                },
                free = catalogo.esGratis(m.id ?: m.modelID),
                providerID = m.providerID
            )
        }
        return Envelope(ok = true, data = lista)
    }
}

/**
 * G4/F9: Colaborador para bootstrap, salud y comandos de sistema.
 */
class SistemaRutaNativa(
    private val sh: suspend (String, Long) -> com.aegis.hub.RootShell.Result,
    private val rutaDeSkills: () -> String,
    private val raizProyectos: String,
    private val rutaChrootRoot: String
) {
    companion object {
        private const val TAG = "AegisSistemaRuta"
    }

    private val setupNative: SetupNative by lazy { SetupNative() }
    private var cacheHealth: Pair<Long, Response<HealthResponse>>? = null
    private var cacheSystemSkills: Pair<Long, Response<SkillsResponse>>? = null

    private fun cacheVigente(marcaMs: Long, ttlMs: Long): Boolean =
        System.currentTimeMillis() - marcaMs < ttlMs

    fun invalidarCacheSkills() {
        cacheSystemSkills = null
    }

    suspend fun getSystemHealth(): Response<HealthResponse> {
        cacheHealth?.takeIf { cacheVigente(it.first, 5_000L) }?.let { return it.second }
        return try {
            val baseSkills = rutaDeSkills()
            val lote = sh(
                "cat /proc/uptime 2>/dev/null; echo '@@AEGIS_SALUD@@mem'\n" +
                    "cat /proc/meminfo 2>/dev/null; echo '@@AEGIS_SALUD@@ls'\n" +
                    "ls -1d $raizProyectos/*/ 2>/dev/null | wc -l; echo '@@AEGIS_SALUD@@skills'\n" +
                    "ls -1d '$baseSkills'/*/ 2>/dev/null",
                5000
            )
            val secciones = mutableMapOf<String, StringBuilder>()
            var actual = StringBuilder()
            var nombre = "uptime"
            for (linea in lote.stdout.lines()) {
                if (linea.startsWith("@@AEGIS_SALUD@@")) {
                    secciones[nombre] = actual
                    nombre = linea.removePrefix("@@AEGIS_SALUD@@")
                    actual = StringBuilder()
                } else {
                    actual.appendLine(linea)
                }
            }
            secciones[nombre] = actual

            val uptimeSec = secciones["uptime"]?.toString()
                ?.split(' ', '\t', '\n')?.firstOrNull { it.isNotBlank() }
                ?.toDoubleOrNull()?.toLong() ?: 0L

            var totalKb = 0L
            var freeKb = 0L
            var availableKb = 0L
            for (line in (secciones["mem"]?.toString() ?: "").lines()) {
                val valor = line.substringAfter(":").trim().split(' ', '\t')
                    .firstOrNull { it.isNotBlank() }?.toLongOrNull() ?: continue
                when {
                    line.startsWith("MemTotal:") -> totalKb = valor
                    line.startsWith("MemAvailable:") -> availableKb = valor
                    line.startsWith("MemFree:") -> freeKb = valor
                }
            }
            val usedKb = if (availableKb > 0) (totalKb - availableKb) else (totalKb - freeKb)
            val memData = MemoryData(
                heapUsed = "${usedKb / 1024}MB",
                heapTotal = "${totalKb / 1024}MB"
            )

            val projectCount = secciones["ls"]?.toString()?.trim()?.toIntOrNull() ?: 0

            val skillNames = (secciones["skills"]?.toString() ?: "").lines()
                .map { it.trim().trimEnd('/') }
                .filter { it.isNotEmpty() }
                .map { it.substringAfterLast('/') }
                .filter { !it.startsWith(".") }

            val adaptersMap = mapOf("rootshell" to "healthy", "opencode" to "healthy")
            val healthData = HealthData(
                server = "running",
                port = 49374,
                uptime = uptimeSec,
                memory = memData,
                workspace = raizProyectos,
                projects = projectCount,
                agents = AgentsSummary(active = 0, registered = 0),
                jobs = JobsSummary(active = 0, lastRun = null),
                skills = SkillsSummary(installed = skillNames),
                adapters = adaptersMap
            )
            Response.success(HealthResponse(ok = true, data = healthData))
                .also { cacheHealth = System.currentTimeMillis() to it }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            Log.w(TAG, "getSystemHealth fallo: ${e.message}")
            Response.success(HealthResponse(ok = false, data = null))
        }
    }

    suspend fun getBootstrapState(): Response<BootstrapResponse> {
        return try {
            val snapshot = setupNative.getSnapshot()
            Response.success(BootstrapResponse(ok = true, data = snapshot))
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            Log.w(TAG, "getBootstrapState fallo: ${e.message}")
            Response.success(
                BootstrapResponse(
                    ok = false,
                    data = null,
                    error = ErrorBody(code = "BOOTSTRAP_STATE_ERROR", message = e.message ?: "error leyendo estado")
                )
            )
        }
    }

    suspend fun runBootstrap(body: BootstrapRunRequest): Response<BootstrapActionResponse> {
        return try {
            val result = setupNative.runBootstrap(resume = body.resume)
            if (result.isSuccess) {
                val state = result.getOrNull()
                Response.success(
                    BootstrapActionResponse(
                        ok = true,
                        data = BootstrapActionData(phase = state?.phaseOrIdle ?: BootstrapPhase.running)
                    )
                )
            } else {
                val err = result.exceptionOrNull()
                Response.success(
                    BootstrapActionResponse(
                        ok = false,
                        data = null,
                        error = ErrorBody(code = "BOOTSTRAP_RUN_FAILED", message = err?.message ?: "error ejecutando bootstrap")
                    )
                )
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            Response.success(
                BootstrapActionResponse(
                    ok = false,
                    data = null,
                    error = ErrorBody(code = "BOOTSTRAP_RUN_ERROR", message = e.message ?: "error")
                )
            )
        }
    }

    suspend fun retryBootstrapStep(id: String): Response<BootstrapActionResponse> {
        return try {
            val result = setupNative.runBootstrap(resume = true, retryStepId = id)
            if (result.isSuccess) {
                val state = result.getOrNull()
                Response.success(
                    BootstrapActionResponse(
                        ok = true,
                        data = BootstrapActionData(phase = state?.phaseOrIdle ?: BootstrapPhase.running)
                    )
                )
            } else {
                val err = result.exceptionOrNull()
                Response.success(
                    BootstrapActionResponse(
                        ok = false,
                        data = null,
                        error = ErrorBody(code = "BOOTSTRAP_RETRY_FAILED", message = err?.message ?: "error reintentando paso $id")
                    )
                )
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            Response.success(
                BootstrapActionResponse(
                    ok = false,
                    data = null,
                    error = ErrorBody(code = "BOOTSTRAP_RETRY_ERROR", message = e.message ?: "error")
                )
            )
        }
    }

    suspend fun cancelBootstrap(): Response<BootstrapActionResponse> {
        val cancelled = setupNative.cancelExecution()
        val snapshot = setupNative.getSnapshot()
        return Response.success(
            BootstrapActionResponse(
                ok = cancelled,
                data = BootstrapActionData(phase = snapshot.phaseOrIdle)
            )
        )
    }

    suspend fun getFinalCheck(): Response<FinalCheckResponse> {
        return try {
            val resp = setupNative.runFinalCheck()
            Response.success(resp)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            Response.success(
                FinalCheckResponse(
                    ok = false,
                    data = null,
                    error = ErrorBody(code = "FINAL_CHECK_ERROR", message = e.message ?: "error")
                )
            )
        }
    }

    suspend fun runSmokeTest(): Response<SmokeTestResponse> {
        return try {
            val resp = setupNative.runSmokeTest()
            Response.success(resp)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            Response.success(
                SmokeTestResponse(
                    ok = false,
                    data = null,
                    error = ErrorBody(code = "SMOKE_FAILED", message = e.message ?: "error")
                )
            )
        }
    }

    suspend fun runAuthGuide(): Response<AuthGuideResponse> {
        return Response.success(
            AuthGuideResponse(
                ok = true,
                data = AuthGuideData(
                    mode = "command",
                    command = "opencode serve --service",
                    status = "unauthenticated"
                )
            )
        )
    }

    suspend fun getSystemSkills(): Response<SkillsResponse> {
        cacheSystemSkills?.takeIf { cacheVigente(it.first, 10_000L) }?.let { return it.second }
        val base = rutaDeSkills()
        val listado = sh(
            "cd '$base' 2>/dev/null || exit 0\n" +
                "for d in */; do n=\"\${d%/}\"; [ -f \"\$n/SKILL.md\" ] && printf '%s\\n' \"\$n\"; done",
            5000
        )
        val carpetas = if (listado.code == 0) {
            listado.stdout.split("\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != ".papelera" }
        } else {
            emptyList()
        }

        val installedSet = mutableSetOf<String>()
        val installedList = mutableListOf<SkillItem>()
        for (id in carpetas) {
            installedSet.add(id)
            installedList.add(
                SkillItem(
                    id = id,
                    name = id,
                    version = null,
                    description = "Skill local ($id)",
                    installed = true,
                    enabled = true
                )
            )
        }

        val availableCatalog = listOf(
            Triple("graphify", "graphify", "Convierte archivos en grafo de conocimiento (GraphRAG)"),
            Triple("opencode-mem", "opencode-mem", "Memoria persistente para OpenCode")
        )

        val availableList = mutableListOf<SkillItem>()
        for ((catId, catName, catDesc) in availableCatalog) {
            if (!installedSet.contains(catId)) {
                availableList.add(
                    SkillItem(
                        id = catId,
                        name = catName,
                        version = if (catId == "opencode-mem") "2.26.0" else null,
                        description = catDesc,
                        installed = false,
                        enabled = true
                    )
                )
            }
        }

        return Response.success(
            SkillsResponse(
                ok = true,
                data = SkillsData(
                    installed = installedList,
                    available = availableList
                )
            )
        ).also { cacheSystemSkills = System.currentTimeMillis() to it }
    }

    suspend fun installSkill(body: InstallSkillRequest): Response<TaskResponse> =
        Response.success(
            TaskResponse(
                ok = false,
                data = TaskData(
                    taskId = null,
                    message = "La instalacion asincrona de paquetes npm/uv era del Hub retirado. Para anadir skills copie el directorio con SKILL.md en $rutaChrootRoot/.config/opencode/skills/"
                )
            )
        )

    suspend fun uninstallSkill(skillId: String, deleteSkillDelegate: suspend (String, String) -> Envelope<Map<String, String>>): Response<BaseResponse> {
        val id = skillId.trim()
        if (id.isEmpty() || id.contains("..") || id.contains("/")) {
            return Response.success(
                BaseResponse(
                    ok = false,
                    error = ErrorBody(code = "SKILL_INVALID", message = "id de skill invalido: $id")
                )
            )
        }
        val delResult = deleteSkillDelegate("global", id)
        return if (delResult.ok) {
            Response.success(BaseResponse(ok = true, error = null))
        } else {
            Response.success(
                BaseResponse(
                    ok = false,
                    error = ErrorBody(code = "UNINSTALL_FAILED", message = delResult.error?.message ?: "error apartando skill")
                )
            )
        }
    }
}
