package com.aegis.hub.data

import com.google.gson.annotations.SerializedName

/**
 * Chat, mensajes, agentes y modelos elegibles (F8: trozo de Models.kt, mismo paquete).
 */

data class ErrorBody(
    val code: String? = null,
    val message: String? = null
)

data class Envelope<T>(
    val ok: Boolean,
    val data: T? = null,
    val error: ErrorBody? = null
)

// ---- Projects ----

enum class MessageDeliveryStatus { PENDING, SENT, ERROR }

data class MessageInfo(
    val id: String? = null,
    val role: String? = null,
    val time: Map<String, Any>? = null,
    val status: MessageDeliveryStatus? = MessageDeliveryStatus.SENT
) {
    val deliveryStatus: MessageDeliveryStatus get() = status ?: MessageDeliveryStatus.SENT
}

// Bloque de contenido de una tool en el formato nativo de OpenCode v2. El Hub lo
// reenvía verbatim dentro de `state` (providers.js), pero el cliente no lo
// declaraba, así que Gson lo descartaba y la salida de TODA herramienta se perdía.

data class ToolContentBlock(
    val type: String? = null,
    val text: String? = null
)

data class ToolState(
    val status: String? = null,
    val input: Map<String, Any?>? = null,
    // Ruta LEGACY: el normalizador antiguo (providers.js:1541) escribía `output`.
    val output: String? = null,
    // Formato NATIVO v2: el resultado llega como `content` = lista de bloques.
    val content: List<ToolContentBlock>? = null,
    // `metadata` trae sessionID/status (subagentes), truncated, exit, name (skills).
    val metadata: Map<String, Any?>? = null,
    val exitCode: Int? = null,
    val duration: Double? = null
) {
    /**
     * La salida que se debe pintar, venga del formato que venga.
     *
     * MEDIDO 2026-09-29 sobre una sesión real: las 69 tool parts de una sesión con
     * subagentes llegaron con `state.content` (array de bloques) y `state.output`
     * siempre a null. Leer solo `output` es leer siempre null — por eso la tarjeta
     * de una herramienta salía sin resultado. Se prefiere `output` si existe para no
     * cambiar el comportamiento de la ruta legacy, y se aplana `content` si no.
     */
    val outputText: String?
        get() {
            val legacy = output?.takeIf { it.isNotBlank() }
            if (legacy != null) return legacy
            val joined = content
                ?.mapNotNull { it.text }
                ?.filter { it.isNotBlank() }
                ?.joinToString("\n")
                ?.takeIf { it.isNotBlank() }
            return joined
        }

    /**
     * Una invocación de subagente se reconoce por la FORMA de su `input`, no por su
     * nombre: toda herramienta que reciba `agent` es una delegación. No se menciona
     * ningún cargo ni ningún proyecto con nombre propio, así que esto funciona con
     * cualquier subagente que se añada mañana, no solo con los de hoy.
     */
    val isSubagent: Boolean get() = input?.containsKey("agent") == true

    val agentName: String? get() = input?.get("agent") as? String
    val agentDescription: String? get() = input?.get("description") as? String

    /** Sesión hija del subagente. Permite abrir su conversación desde la tarjeta. */
    val subagentSessionId: String? get() = metadata?.get("sessionID") as? String
    val isTruncated: Boolean get() = metadata?.get("truncated") == true

    /**
     * El nombre de la herramienta NO viaja en el payload: el normalizador lo
     * conserva si existe (providers.js:87) y la ruta nativa v2 no lo trae — MEDIDO,
     * las 69 tool parts de una sesión real llegaron sin `tool` ni `callID`. Antes de
     * arreglarlo en la UI, el nombre se infiere de las claves de `input`. Es una
     * etiqueta para la persona, no un valor de lógica: si la inferencia falla se
     * dice "herramienta" en vez de mentir con un "bash" fijo.
     */
    val toolName: String get() {
        val i = input ?: return "herramienta"
        fun has(k: String) = i.containsKey(k)
        return when {
            has("agent") -> "subagente"
            has("command") || has("CommandLine") || has("cmd") -> "bash"
            has("oldString") || has("newString") -> "edit"
            has("path") && has("content") -> "write"
            has("path") && (has("pattern") || has("glob")) -> "search"
            has("path") || has("AbsolutePath") || has("TargetFile") -> "read"
            has("query") || has("url") || has("Url") -> "web"
            has("id") -> "skill"
            else -> "herramienta"
        }
    }

    val command: String get() {
        val direct = input?.get("command") as? String
            ?: input?.get("CommandLine") as? String
            ?: input?.get("cmd") as? String
        if (!direct.isNullOrBlank()) return direct.trim().removeSurrounding("\"")
        val path = input?.get("path") as? String
            ?: input?.get("AbsolutePath") as? String
            ?: input?.get("TargetFile") as? String
        if (!path.isNullOrBlank()) return path.trim().removeSurrounding("\"")
        val query = input?.get("query") as? String
        if (!query.isNullOrBlank()) return query.trim().removeSurrounding("\"")
        val url = input?.get("Url") as? String ?: input?.get("url") as? String
        if (!url.isNullOrBlank()) return url.trim().removeSurrounding("\"")
        return ""
    }
}

data class LiveToolExecution(
    val id: String,
    val tool: String,
    val command: String,
    val status: String = "running",
    val output: String? = null,
    val exitCode: Int? = null,
    val duration: Double? = null
)

data class MessagePart(
    val id: String? = null,
    val type: String? = null,
    val text: String? = null,
    val mime: String? = null,
    val filename: String? = null,
    val data: String? = null,
    val image: String? = null,
    val url: String? = null,
    val tool: String? = null,
    val callID: String? = null,
    val state: ToolState? = null,
    // Marcador del recorte de payload (server.js -> trimPartText). Con
    // hasBinary=true el Hub retiro una data URI para no mandar decenas de MB de
    // base64; el binario real se pide bajo demanda a /api/sessions/:sid/part.
    val hasBinary: Boolean? = null,
    val binaryChars: Int? = null
)

/**
 * MEDIDO 2026-10-06 (ANR con traza): estos 12 patrones se compilaban en CADA llamada
 * a `strippedText()` —200 mensajes x 12 compilaciones ICU cada 2 s de poll, en el hilo
 * principal— y el main no atendia input (>5 s = ANR). Compilados UNA vez aqui.
 */

private val RX_USER_REQUEST = Regex("<USER_REQUEST>([\\s\\S]*?)(?:</USER_REQUEST>|$)", RegexOption.IGNORE_CASE)

private val RX_PLAN_BUILD = Regex("^\\s*//?/?(?:PLAN|plan|BUILD|build)\\s*", RegexOption.IGNORE_CASE)

private val RX_SYSTEM_INSTRUCTION = Regex("<SYSTEM_INSTRUCTION>[\\s\\S]*?(?:</SYSTEM_INSTRUCTION>|$)", RegexOption.IGNORE_CASE)

private val RX_SYSTEM_CONTEXT = Regex("<SYSTEM_CONTEXT>[\\s\\S]*?(?:</SYSTEM_CONTEXT>|$)", RegexOption.IGNORE_CASE)

private val RX_SYSTEM_CONTEXT_MD = Regex("\\[SYSTEM CONTEXT[\\s\\S]*?\\][\\s\\S]*?(?:---\\n\\n|$)", RegexOption.IGNORE_CASE)

private val RX_PONYTAIL = Regex("# PONY-TAIL[\\s\\S]*?(?:---\\n\\n|$)", RegexOption.IGNORE_CASE)

private val RX_ENTORNO = Regex("## 1\\. Entorno[\\s\\S]*?(?:---\\n\\n|$)", RegexOption.IGNORE_CASE)

private val RX_MEMORY_CONTEXT = Regex("<memory_context[\\s\\S]*?</memory_context>", RegexOption.IGNORE_CASE)

private val RX_PROJECT_KNOWLEDGE = Regex("<project_knowledge[\\s\\S]*?</project_knowledge>", RegexOption.IGNORE_CASE)

private val RX_ADDITIONAL_METADATA = Regex("<ADDITIONAL_METADATA>[\\s\\S]*?</ADDITIONAL_METADATA>", RegexOption.IGNORE_CASE)

private val RX_USER_SETTINGS = Regex("<USER_SETTINGS_CHANGE>[\\s\\S]*?</USER_SETTINGS_CHANGE>", RegexOption.IGNORE_CASE)

data class Message(
    val info: MessageInfo? = null,
    val parts: List<MessagePart>? = null
) {
    // Normalized: role from info, text from type=text parts only
    val role: String get() = info?.role ?: "assistant"
    val text: String get() = parts
        ?.filter { it.type == "text" && !it.text.isNullOrBlank() }
        ?.joinToString("\n") { it.text!! } ?: ""
    fun isMemoryContext(): Boolean {
        val t = text.trim()
        return t.startsWith("<memory_context") || t.contains("<project_knowledge") || t.contains("<memory relevance=")
    }
    fun strippedText(): String {
        var t = text
        val reqMatch = RX_USER_REQUEST.find(t)
        if (reqMatch != null && reqMatch.groupValues[1].isNotBlank()) {
            t = reqMatch.groupValues[1].trim()
        }
        t = RX_PLAN_BUILD.replace(t, "").trim()
        t = RX_SYSTEM_INSTRUCTION.replace(t, "").trim()
        t = RX_SYSTEM_CONTEXT.replace(t, "").trim()
        t = RX_SYSTEM_CONTEXT_MD.replace(t, "").trim()
        t = RX_PONYTAIL.replace(t, "").trim()
        t = RX_ENTORNO.replace(t, "").trim()
        t = RX_MEMORY_CONTEXT.replace(t, "").trim()
        t = RX_PROJECT_KNOWLEDGE.replace(t, "").trim()
        t = RX_ADDITIONAL_METADATA.replace(t, "").trim()
        t = RX_USER_SETTINGS.replace(t, "").trim()
        return t.trim()
    }
    val isEmpty: Boolean get() = text.isBlank() && strippedText().isBlank() && fileParts().isEmpty() && imageParts().isEmpty() && toolParts().isEmpty()
    fun fileParts(): List<MessagePart> = parts?.filter { it.type == "file" } ?: emptyList()
    fun imageParts(): List<MessagePart> = parts?.filter { it.type == "image" } ?: emptyList()
    fun toolParts(): List<MessagePart> = parts?.filter { it.type == "tool" } ?: emptyList()

    val isPending: Boolean get() = info?.deliveryStatus == MessageDeliveryStatus.PENDING
    val isError: Boolean get() = info?.deliveryStatus == MessageDeliveryStatus.ERROR

    fun withStatus(newStatus: MessageDeliveryStatus): Message =
        copy(info = (info ?: MessageInfo()).copy(status = newStatus))
}

data class SendMessageRequest(
    val parts: List<Map<String, String>>,
    val model: String? = null,
    val agent: String? = null,
    val mode: String? = null
)

data class AttachedFile(
    val name: String,
    val mime: String,
    val size: Long,
    val base64: String? = null,
    val text: String? = null
)

// ---- Models ----
/**
 * Un agente REAL de OpenCode, tal cual lo publica `GET /api/agent`.
 *
 * MEDIDO 2026-09-30: son 40 — 6 `primary` (orchestrator, Build, Plan y los tres internos
 * de OpenCode) y 34 `subagent` (los 32 cargos de Kaenor + General + Explore). Antes de
 * esto la app no tenia NINGUN modelo de agente y el boton azul era un interruptor de
 * dos: `if (agentMode == "plan") "build" else "plan"`. O sea que de 40 agentes
 * reachables solo se podian elegir dos, y el resto no era que no existieran.
 *
 * `model` es el modelo FIJADO del agente, no el de la sesion: MEDIDO, de los 6 primary
 * solo `orchestrator` lo tiene (`space-bunny-free`); Build y Plan lo dejan en null y
 * usan el de la sesion. Por eso la hoja lo enseña tal cual, sin inventar un valor.
 */

data class Agente(
    val name: String,
    val mode: String = "primary",
    val model: String? = null,
    val description: String? = null,
    // Lo que OpenCode marca para NO ensenar en su propio selector. MEDIDO 2026-09-30:
    // de los 40, 37 son visibles y 3 ocultos (Compaction, Title, Summary). Con
    // `mode == "primary" && !hidden` salen exactamente Build, Plan y orchestrator.
    //
    // El valor por defecto es `false` a proposito: si el Hub dejara de mandar el campo,
    // se verian los 6 en vez de 0, que es degradar hacia lo visible y no hacia lo
    // invisible. Un default true esconderia agentes que si valen sin avisar.
    val hidden: Boolean = false
)

/**
 * Los agentes que OpenCode ofrece para cambiar a mano, y SOLO esos.
 *
 * MEDIDO 2026-09-30 sobre el registro crudo: 40 agentes, 37 visibles, 3 ocultos
 * (Compaction, Title, Summary, que son internos de la tuberia de OpenCode). Con
 * `mode == "primary" && !hidden` la lista queda en tres: Build, Plan y orchestrator.
 *
 * Se descartan los 34 subagentes (los cargos de Kaenor y General/Explore) a proposito:
 * son agentes que un agente invoca con la herramienta `task`, no destinations que se
 * elijan para tu sesion. Es lo mismo que hace el propio OpenCode en su selector.
 *
 * No es que no sirvan: MEDIDO, un turno con `agent=kaenor-ai-engineer` corre y contesta.
 * Es que no son lo que el boton esta preguntando. Si algun dia se quieren, el Hub los
 * sigue exponiendo todos en `/api/opencode/agents` y esta funcion es el unico sitio que
 * habria que tocar.
 */

fun List<Agente>.seleccionables(): List<Agente> =
    filter { it.mode == "primary" && !it.hidden }
        .sortedBy { it.name.lowercase() }

data class ModelOption(
    val id: String,
    val name: String,
    val description: String? = null,
    // El Hub ya lo expone en `free` (/api/models), y lo calcula mirando el COSTE del
    // modelo, no solo si el id acaba en "-free". MEDIDO 2026-09-29: 39 de 472 modelos
    // lo traen a true. Sin este campo la app no puede distinguir un free de uno de
    // pago, y por eso el default acababa hardcodeado a un id que no existe.
    val free: Boolean = false,
    // MEDIDO 2026-10-03: sin esto la app solo maneja el id y no puede fijar el modelo en
    // el servidor, que distingue por pareja id mas providerID. Con default null para no
    // romper los tests que construyen ModelOption a mano.
    val providerID: String? = null
)

/**
 * El modelo por defecto de una sesion nueva: **Space Bunny Free**.
 *
 * Decision del usuario 2026-10-01. MEDIDO sobre `/api/models` el mismo dia: de 475 modelos,
 * `space-bunny-free` existe y trae `free=true`, y el PRIMERO free de la lista —que es lo que
 * devolvia esta regla— es `longcat-2.5-preview-free`. De ahi "al abrir nuevas sesiones se
 * cambia el modelo".
 *
 * Sigue siendo una REGLA y no un id fijo a pelo, por el motivo que ya esta escrito aqui y
 * que costo caro: el id que se hardcodeo antes ("gemini-3.8-flash-high") no existia entre los
 * 472 modelos, y el Hub avisaba "modelo no encontrado en el indice v2". Si OpenCode deja de
 * ofrecer Space Bunny Free, esta regla cae al primer free en vez de mandar un id que no
 * existe — que es un fallo que se ve, y no uno que se cuela.
 *
 * Si la lista no trae ninguno free, se devuelve null y deja que OpenCode elija: es preferible
 * a inventar un id.
 */
val List<ModelOption>.modeloPorDefecto: String?
    get() = firstOrNull { it.id == ID_MODELO_POR_DEFECTO && it.free }?.id
        ?: firstOrNull { it.free }?.id

/**
 * Default de modelo para una sesion que trabaja con un agente.
 *
 * MEDIDO 2026-10-07: `modeloPorDefecto` (global, primer free) se usaba tambien para
 * sesiones de orchestrator, cuyo agente trae modelo propio (muse-spark). El chip
 * mostraba Space Bunny y al enviar se fijaba ese en el servidor, contra el agente.
 * Si el agente tiene modelo definido y esta en la lista, manda el; si no, el global.
 * Puro para probarlo sin servidor.
 */

internal fun modeloPorDefectoPara(
    agente: String?,
    agentes: List<Agente>,
    modelos: List<ModelOption>
): String? {
    val nombre = agente?.trim()?.takeIf { it.isNotBlank() }
    val delAgente = nombre?.let { n -> agentes.firstOrNull { it.name == n }?.model?.trim() }
        ?.takeIf { it.isNotBlank() }
    if (delAgente != null && modelos.any { it.id == delAgente }) return delAgente
    return modelos.modeloPorDefecto
}

/**
 * El id del modelo que abre una sesion nueva. Verificado contra el catalogo vivo, no supuesto.
 */

const val ID_MODELO_POR_DEFECTO = "space-bunny-free"

// ---- F1: Bootstrap / asistente de configuración inicial (/api/bootstrap/*) ----
// Parser: Gson (ApiClient) — case-sensitive. Los nombres de las constantes coinciden
// EXACTO con los strings del contrato ("idle", "pending", "none", ...); NO se usa
// @Json (Moshi/kotlinx) porque el proyecto es Gson + @SerializedName.
// Campos que el contrato envía como null (error, currentStepId, startedAt) van
// nullable para que el parseo no rompa; los helpers cubren valores inesperados
// sin NPE (mismo precedente que MessageInfo.deliveryStatus).

object TurnState {
    /** Silencio máximo tolerado antes de dudar del registro. */
    /**
     * Techo de seguridad por si el vigilante del Hub muere y `turnOver` no llega nunca.
     *
     * ANTES eran 45 s, y ese numero era el defecto: una herramienta de mas de 45 s no
     * emite NADA durante ese rato, asi que el circulo se apagaba mientras el turno seguia
     * vivo. MEDIDO 2026-09-30 en la captura del usuario: `bash(sleep 270; ...)` con
     * "Trabajando en ello..." dentro del chat y SIN circulo en la lista — el mismo turno,
     * dos indicadores, uno cierto y otro apagado. Lo que no puede distinguir "no hay
     * eventos" de "se acabo" es un reloj corto, y esa distincion es justo la que se
     * perdia.
     *
     * `turnOver` es la senal fiable y no necesita reloj. El silencio queda solo como red
     * de seguridad, y 6 h da margen de sobra a un turno: pasado ese punto, o el turno esta
     * colgado o el vigilante murio, y en los dos casos un circulo de mas importa mucho
     * menos que apagarlo antes de tiempo.
     */
    const val MAX_SILENCE_MS = 6L * 60L * 60L * 1000L

    /**
     * ¿Este registro dice "ocupado" y además es de fiar?
     *
     * Se pasa la sesión como parámetro en vez de declarar una extensión de miembro
     * (`fun InflightSession?.isReliableBusy()`): las extensiones de miembro sobre un
     * objeto importado daba "Unresolved reference" en ambos ViewModel, y una llamada
     * explícita no deja lugar a ambigüedad. Sigue siendo el ÚNICO sitio donde se decide.
     */
    fun isBusy(s: InflightSession?, now: Long = System.currentTimeMillis()): Boolean {
        if (s == null || s.turnOver) return false
        val last = s.lastSeen ?: s.since ?: return false
        return now - last < MAX_SILENCE_MS
    }

    /** ¿Este registro dice que el turno terminó? */
    fun isOver(s: InflightSession?): Boolean = s != null && s.turnOver
}
