package com.aegis.hub.data

import com.google.gson.annotations.SerializedName

/**
 * Modelos de datos NATIVOS para la API v2 de OpenCode (`http://127.0.0.1:49374/`, rutas bajo
 * `/api/`). MEDIDO 2026-10-01: esta frase decía la ruta con los dos comodines de glob ("api"
 * seguido de asterisco) y rompía la compilación. Kotlin ANIDA los comentarios de bloque, así que
 * esa secuencia abría un comentario anidado dentro de este KDoc y el cierre lo cerraba a él,
 * dejando el KDoc abierto hasta el fin del fichero.
 *
 * A diferencia del Hub de Aegis (que envuelve todo en `{ "ok": true, "data": ... }`),
 * OpenCode v2 devuelve las entidades de forma directa:
 * - Listas: `{ "data": [ ... ], "cursor": ... }` o listas JSON planas según endpoint.
 * - Una entidad: JSON plano `{ "id": "ses_...", "title": ... }` o `{ "data": ... }`.
 * - Mensajes: array de bloques en `content: [ { "type": "...", ... } ]`.
 * - Prompt: acuse de recibo inmediato `{ "id": ..., "sessionID": ..., "time": ... }`.
 * - Modelos y agentes: estructuras directas publicadas por el daemon.
 */

// ==========================================
// 1. Info / Estado del servidor
// ==========================================

data class OpenCodeServerInfo(
    @SerializedName("version") val version: String? = null,
    @SerializedName("platform") val platform: String? = null,
    @SerializedName("status") val status: String? = null
)

// ==========================================
// 2. Sesiones (OpenCode Session v2)
// ==========================================

/**
 * MEDIDO 2026-10-02 contra `GET /api/session/{id}/message`: el `time` de un mensaje trae
 * `created`, `streamed` y `completed`. Y el ultimo es el que decide si el turno TERMINO.
 *
 * Reportado por el usuario: "la barra de 'Trabajando en ello...' aparece cuando la sesion ya
 * termino". Causa medida: `completed` no estaba declarado aqui, asi que Gson lo descartaba, y el
 * mapa que recibe la app nunca tenia esa clave.
 *
 * `updated` e `idle` tambien constan, aunque no salen en los mensajes: vienen en las sesiones.
 */
data class OpenCodeTime(
    @SerializedName("created") val created: Long? = null,
    @SerializedName("updated") val updated: Long? = null,
    @SerializedName("idle") val idle: Long? = null,
    @SerializedName("streamed") val streamed: Long? = null,
    @SerializedName("completed") val completed: Long? = null
)

data class OpenCodeTokens(
    @SerializedName("input") val input: Long? = null,
    @SerializedName("output") val output: Long? = null,
    @SerializedName("reasoning") val reasoning: Long? = null
)

data class OpenCodeLocation(
    @SerializedName("directory") val directory: String? = null
)

data class OpenCodeModelRef(
    @SerializedName("id") val id: String,
    @SerializedName("providerID") val providerID: String = "opencode",
    @SerializedName("variant") val variant: String? = null
)

data class OpenCodeSession(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String? = null,
    // MEDIDO el 2026-10-02: falta aqui el campo que distingue una sesion de
    // una sesion HIJA (la que crea un subagente). `GET /api/session` devuelve
    // las dos mezcladas y `parentID` es null en las de primer nivel y trae el
    // id de la madre en las de subagente.
    //
    // Sin este campo el problema no se puede arreglar en ninguna parte de la
    // app: no es que el filtro falte, es que la INFORMACION para filtrar no
    // llegaba. El sintoma era la lista de "Chats" llena de sesiones que
    // nadie abrio ("Verificacion de directorio actual", "Nombres exactos de
    // herramientas", "orquestador:master"...), que son los subagentes del
    // orquestador.
    @SerializedName("parentID") val parentID: String? = null,
    @SerializedName("agent") val agent: String? = null,
    @SerializedName("model") val model: OpenCodeModelRef? = null,
    @SerializedName("outcome") val outcome: String? = null,
    @SerializedName("cost") val cost: Double? = null,
    @SerializedName("projectID") val projectID: String? = null,
    @SerializedName("time") val time: OpenCodeTime? = null,
    @SerializedName("tokens") val tokens: OpenCodeTokens? = null,
    @SerializedName("location") val location: OpenCodeLocation? = null
)

data class OpenCodeSessionListResponse(
    @SerializedName("data") val data: List<OpenCodeSession>? = null,
    @SerializedName("cursor") val cursor: OpenCodeCursor? = null
)

/**
 * MEDIDO 2026-10-03 contra el OpenAPI y contra el servidor vivo: `GET /api/session/{id}` y
 * `POST /api/session` responden con la sesion ENVUELTA en `data`. Declarar el retorno sin
 * envoltura hacia que Gson devolviera una sesion con todo a null: `getSessionModel` siempre
 * null y la app creia que el servidor no tenia modelo. Esa era la desincronia con el CLI.
 */
data class OpenCodeSessionResponse(
    @SerializedName("data") val data: OpenCodeSession? = null
)

data class OpenCodeCursor(
    @SerializedName("next") val next: String? = null,
    @SerializedName("prev") val prev: String? = null
)

data class CreateOpenCodeSessionRequest(
    @SerializedName("title") val title: String? = null,
    @SerializedName("location") val location: OpenCodeLocation? = null
)

data class UpdateOpenCodeSessionRequest(
    @SerializedName("title") val title: String? = null
)

// ==========================================
// 3. Estado de Turno / Activas (Killer endpoint)
// ==========================================

data class ActiveSessionStatus(
    @SerializedName("type") val type: String? = null // e.g. "running", "idle"
)

/**
 * MEDIDO 2026-10-03: `GET /api/session/active` responde `{"data":{"<id>":{"type":"running"}}}`,
 * ENVUELTO en `data`. Declarar el endpoint como `Map<String, ActiveSessionStatus>` hacia que Gson
 * devolviera un mapa con una sola clave, "data", y los ids de sesion se perdian al deserializar.
 * (Reportado por el agente C con 5 sesiones reales que lo falsifican; verificado en el OpenAPI.)
 */
data class OpenCodeActiveSessionsResponse(
    @SerializedName("data") val data: Map<String, ActiveSessionStatus>? = null
)

// ==========================================
// 4. Mensajes y Partes Nativas
// ==========================================

data class OpenCodePartSource(
    @SerializedName("type") val type: String? = null,
    @SerializedName("uri") val uri: String? = null
)

data class OpenCodeFileAttachment(
    @SerializedName("id") val id: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("mime") val mime: String? = null,
    @SerializedName("data") val data: String? = null,
    @SerializedName("source") val source: OpenCodePartSource? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("mention") val mention: String? = null
)

data class OpenCodeMessagePart(
    @SerializedName("id") val id: String? = null,
    @SerializedName("type") val type: String? = null, // "text", "reasoning", "tool", "file", "image"
    @SerializedName("text") val text: String? = null,
    @SerializedName("mime") val mime: String? = null,
    @SerializedName("tool") val tool: String? = null,
    @SerializedName("callID") val callID: String? = null,
    @SerializedName("state") val state: ToolState? = null,
    @SerializedName("files") val files: List<OpenCodeFileAttachment>? = null
)

data class OpenCodeMessage(
    @SerializedName("id") val id: String,
    @SerializedName("sessionID") val sessionID: String? = null,
    @SerializedName("type") val type: String? = null, // "user", "assistant", etc.
    @SerializedName("role") val role: String? = null,
    @SerializedName("agent") val agent: String? = null,
    @SerializedName("model") val model: OpenCodeModelRef? = null,
    @SerializedName("content") val content: List<OpenCodeMessagePart>? = null,
    // MEDIDO 2026-10-02: los mensajes de USUARIO no traen `content[]`. Traen el texto aqui, en un
    // campo de primer nivel. MEDIDO sobre los 17 mensajes de usuario de la sesion —TODOS,
    // antiguos incluidos—: `content` en 0 de 17, `text` en 17 de 17. Los de asistente si usan
    // `content[]`.
    //
    // O sea que no es una excepcion de la app ni un mensaje raro: es la forma normal de un
    // mensaje de usuario. No declararlo hacia que Gson lo descartara, el mensaje llegaba sin
    // partes, `Message.isEmpty` daba true y el filtro lo borraba de la lista. Ese es el sintoma
    // que reporto el usuario: "este mensaje no se visualiza en el chat".
    @SerializedName("text") val text: String? = null,
    @SerializedName("time") val time: OpenCodeTime? = null,
    @SerializedName("error") val error: Map<String, Any?>? = null,
    // Adjuntos directos a nivel mensaje (v2 m.files)
    @SerializedName("files") val files: List<OpenCodeFileAttachment>? = null
)

data class OpenCodeMessageListResponse(
    @SerializedName("data") val data: List<OpenCodeMessage>? = null,
    @SerializedName("cursor") val cursor: OpenCodeCursor? = null
)

/**
 * MEDIDO 2026-10-03 en el OpenAPI: `GET /api/session/{id}/message/{messageID}` responde con
 * el mensaje ENVUELTO en `data`, igual que la sesion individual. Misma causa, mismo arreglo.
 */
data class OpenCodeMessageResponse(
    @SerializedName("data") val data: OpenCodeMessage? = null
)

/**
 * MEDIDO 2026-10-02 contra `GET /api/agent`: una regla de permiso es un OBJETO con tres campos,
 * no una bandera suelta. `effect` es "allow" | "ask" | "deny".
 */
data class OpenCodePermissionRule(
    @SerializedName("action") val action: String? = null,
    @SerializedName("resource") val resource: String? = null,
    @SerializedName("effect") val effect: String? = null
)

/** MEDIDO 2026-10-02: `request` trae `settings`, `headers` y `body`, todos vacios en la practica. */
data class OpenCodeAgentRequest(
    @SerializedName("settings") val settings: Map<String, Any?>? = null,
    @SerializedName("headers") val headers: Map<String, Any?>? = null,
    @SerializedName("body") val body: Map<String, Any?>? = null
)

// ==========================================
// 5. Envío de Prompt (Asíncrono v2)
// ==========================================

data class OpenCodePromptFileRef(
    @SerializedName("uri") val uri: String,
    @SerializedName("name") val name: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("mention") val mention: String? = null
)

data class OpenCodePromptAgentRef(
    @SerializedName("name") val name: String,
    @SerializedName("mention") val mention: String? = null
)

data class OpenCodePromptRequest(
    @SerializedName("text") val text: String,
    @SerializedName("files") val files: List<OpenCodePromptFileRef>? = null,
    @SerializedName("agents") val agents: List<OpenCodePromptAgentRef>? = null,
    @SerializedName("delivery") val delivery: String? = null, // enum: "steer" | "queue"
    @SerializedName("resume") val resume: Boolean? = null
)

data class OpenCodePromptAck(
    @SerializedName("id") val id: String? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("sessionID") val sessionID: String? = null,
    @SerializedName("payload") val payload: Map<String, Any?>? = null,
    @SerializedName("delivery") val delivery: String? = null,
    @SerializedName("time") val time: OpenCodeTime? = null,
    @SerializedName("text") val text: String? = null
)

// ==========================================
// 6. Selección de Modelo y Agente
// ==========================================

data class SetSessionModelRequest(
    @SerializedName("model") val model: OpenCodeModelRef
)

data class SetSessionAgentRequest(
    @SerializedName("agent") val agent: String
)

// ==========================================
// 7. Catálogo de Agentes y Modelos
// ==========================================

data class OpenCodeNativeAgent(
    @SerializedName("id") val id: String? = null,
    @SerializedName("name") val name: String,
    @SerializedName("mode") val mode: String = "primary", // "primary" | "subagent"
    // MEDIDO 2026-10-02: esto estaba declarado como `String?` y es un OBJETO. El usuario lo
    // vio al abrir la app: "Expected a string but was BEGIN_OBJECT at $.data[0].model".
    //
    // MEDIDO contra `GET /api/agent`: `"model": {"id": "space-bunny-free",
    // "providerID": "opencode"}`. Es el MISMO tipo que el `model` de la sesion
    // ([OpenCodeModelRef], linea 51), y de ahi el error: son dos data class con el mismo
    // nombre de campo y tipos distintos, y Gson aplica el del que toque.
    //
    // Se corrigio aqui, y no en el traductor, porque el error ocurre AL DESERIALIZAR: un
    // traductor nunca llega a verse. Arreglarlo solo donde peta deja el mismo fallo latente
    // en cualquier otro consumidor del endpoint.
    @SerializedName("model") val model: OpenCodeModelRef? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("hidden") val hidden: Boolean = false,
    // MEDIDO 2026-10-02: esto estaba declarado como `Map<String, Any?>` y es una LISTA de
    // objetos. El usuario lo vio al abrir la app:
    //
    //     Expected BEGIN_ARRAY but was BEGIN_OBJECT at $.data[0].permissions[0]
    //
    // MEDIDO contra el catalogo real (40 agentes, TODOS con la misma forma):
    //     "permissions": [ {"action":"*","resource":"*","effect":"allow"},
    //                      {"action":"read","resource":"*.env","effect":"ask"}, ... ]
    //
    // O sea: es la lista de REGLAS de permiso, no un diccionario de banderas. Un mapa no puede
    // deserializar una lista, y Gson falla al hacerlo.
    @SerializedName("permissions") val permissions: List<OpenCodePermissionRule>? = null,
    // MEDIDO: `request` viene con `settings`, `headers` y `body`; no lo declaraba. Gson lo ignora
    // sin problema, pero se declara para que la forma real este documentada en el codigo.
    @SerializedName("request") val request: OpenCodeAgentRequest? = null,
    // MEDIDO: `color` existe y es un string (o null), y `system` es el prompt de sistema del
    // agente. Ninguno lo declara la app, pero dejarlo escrito evita el mismo despiste luego.
    @SerializedName("color") val color: String? = null,
    @SerializedName("system") val system: String? = null
)

data class OpenCodeNativeAgentListResponse(
    @SerializedName("data") val data: List<OpenCodeNativeAgent>? = null
)

data class OpenCodeNativeModel(
    @SerializedName("id") val id: String,
    @SerializedName("modelID") val modelID: String? = null,
    @SerializedName("providerID") val providerID: String = "opencode",
    @SerializedName("name") val name: String? = null,
    @SerializedName("family") val family: String? = null,
    @SerializedName("enabled") val enabled: Boolean = true,
    @SerializedName("capabilities") val capabilities: Map<String, Any?>? = null,
    @SerializedName("cost") val cost: Any? = null, // puede ser número, objeto o lista
    // MEDIDO 2026-10-02: esto estaba declarado como `List<String>?` y cada elemento es un
    // OBJETO. MEDIDO sobre el catalogo entero: 301 de 480 modelos traen `variants`, y el tipo de
    // TODOS sus elementos es `dict`. Gson no puede deserializar un objeto en un texto y lanza,
    // asi que el fallo tumbaba la lista COMPLETA de modelos, no solo este campo.
    //
    // Reportado por el usuario al abrir la app: los modelos no cargaban.
    @SerializedName("variants") val variants: List<OpenCodeModelVariant>? = null,
    @SerializedName("status") val status: String? = null
)

/**
 * MEDIDO 2026-10-02 contra `GET /api/model`: un "variant" es un objeto con `id` y `settings`, no
 * un nombre. Ejemplo real: `{"id": "thinking", "settings": {"reasoning": {"enabled": true}}}`.
 */
data class OpenCodeModelVariant(
    @SerializedName("id") val id: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("settings") val settings: Map<String, Any?>? = null
)

data class OpenCodeNativeModelListResponse(
    @SerializedName("data") val data: List<OpenCodeNativeModel>? = null
)

// ==========================================
// 8. Formularios y Permisos
// ==========================================

data class OpenCodeFormReplyRequest(
    @SerializedName("answer") val answer: Map<String, Any?>
)

data class OpenCodePermissionReplyRequest(
    @SerializedName("decision") val decision: String, // "once" | "always" | "reject"
    @SerializedName("message") val message: String? = null
)

data class OpenCodeFormsResponse(
    @SerializedName("data") val data: List<PendingForm>? = null
)

data class OpenCodePermissionsResponse(
    @SerializedName("data") val data: List<PendingPermission>? = null,
    @SerializedName("location") val location: OpenCodeLocation? = null
)

// ==========================================
// 9. Contrato base de eventos SSE (/api/event)
// ==========================================

data class OpenCodeServerEvent(
    @SerializedName("id") val id: String? = null,
    @SerializedName("created") val created: Long? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("location") val location: OpenCodeLocation? = null,
    @SerializedName("data") val data: Map<String, Any?>? = null
) {
    val sessionID: String?
        get() = data?.get("sessionID") as? String

    val textDelta: String?
        get() = data?.get("delta") as? String

    val endedText: String?
        get() = data?.get("text") as? String
}
