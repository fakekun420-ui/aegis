package com.aegis.hub.data

import com.google.gson.annotations.SerializedName

/**
 * Formularios, permisos e inflight (F8: trozo de Models.kt, mismo paquete).
 */

data class FormOption(
    val value: String? = null,
    val label: String? = null,
    val description: String? = null
)

data class FormField(
    val key: String? = null,
    val title: String? = null,
    val description: String? = null,
    val type: String? = null,
    val options: List<FormOption>? = null,
    val custom: Boolean? = null
)

/**
 * Parte completa tal y como la devuelve GET /api/sessions/:sid/part.
 *
 * Es lo que pide la tarjeta al desplegarse cuando la parte venia recortada: la lista de
 * mensajes viaja ligera y el binario se recupera solo si el usuario lo quiere ver.
 */

data class PartFull(
    val id: String? = null,
    val type: String? = null,
    val mime: String? = null,
    val filename: String? = null,
    val text: String? = null,
    val output: String? = null,
    val url: String? = null,
    val image: String? = null,
    val data: String? = null,
    val stateContent: List<Map<String, Any?>>? = null
) {
    /** El binario puede venir en cualquiera de los tres huecos; se toma el primero. */
    fun base64(): String? = image ?: data ?: url
}

data class PendingForm(
    val id: String? = null,
    val sessionID: String? = null,
    val title: String? = null,
    val fields: List<FormField>? = null
) {
    val firstField: FormField? get() = fields?.firstOrNull()

    /** Valor exacto a enviar en el cuerpo del reply. */
    fun optionValue(opt: FormOption): String = opt.value ?: opt.label.orEmpty()

    val allFields: List<FormField> get() = fields.orEmpty().filter { !it.key.isNullOrBlank() }

    /** Campos que se pueden contestar con un toque, es decir, los que traen opciones. */
    val optionFields: List<FormField> get() = allFields.filter { !it.options.isNullOrEmpty() }

    /**
     * Campos SIN opciones: no hay nada que tocar, solo se pueden rellenar escribiendo.
     *
     * Importa porque `POST .../reply` RESUELVE el formulario entero con lo que le
     * mandes: un POST con un solo campo descarta el resto en silencio (medido con un
     * formulario real de 3 campos — se respondió q0 y q1/q2 se perdieron). Por eso la
     * UI tiene que juntarlo todo antes de enviar, y avisar de lo que se va a dejar fuera.
     */
    val freeFields: List<FormField> get() = allFields.filter { it.options.isNullOrEmpty() }

    /** ¿Un solo toque basta? Solo cuando hay una única pregunta contestable. */
    val isOneTap: Boolean get() = optionFields.size == 1 && freeFields.isEmpty()
}

/**
 * Cuerpo de POST /api/forms/:sessionId/:formId/reply.
 *
 * OJO: esto NO puede ser `Map<String, Map<String, String>>`. Kotlin lo acepta, pero
 * Retrofit falla EN EJECUCIÓN con "Parameter type must not include a type variable or
 * wildcard" al no poder construir el converter para un genérico anidado. La tarjeta se
 * pintaba bien y el toque reventaba, que es el peor fallo posible: parecía funcionar.
 * Con una clase concreta, Gson la serializa sin problemas.
 */

data class FormReplyBody(val answer: Map<String, String>)

/**
 * Permiso pendiente de una herramienta, tal y como lo devuelve OpenCode 2.0.14
 * (`Permission.Request`). Es lo que el TUI del CLI pinta como "Permission required".
 *
 * Todos los campos vienen como opcionales a proposito: segun la version, la accion
 * trae o no mensaje, y `save` solo viene relleno cuando la peticion admite
 * "permitir siempre". Un campo obligatorio aqui seria un crash en produccion.
 */

data class PendingPermission(
    val id: String? = null,
    val sessionID: String? = null,
    val action: String? = null,
    val resources: List<String>? = null,
    val save: List<String>? = null,
    val message: String? = null
) {
    /** Etiqueta legible de la herramienta ("bash", "edit", ...). */
    val toolName: String get() = action?.takeIf { it.isNotBlank() } ?: "herramienta"

    /** Texto para el cuerpo de la tarjeta: el mensaje del servidor si existe. */
    val explain: String get() = message?.takeIf { it.isNotBlank() }
        ?: resources?.firstOrNull()?.takeIf { it.isNotBlank() }
        ?: "$toolName necesita permiso para continuar"

    /**
     * Si OpenCode ha Proposed reglas permanentes para esta peticion. Si no, "siempre
     * permitir" se comportaria EXACTAMENTE igual que "permitir una vez" (OpenCode solo
     * persiste cuando request.save no viene vacio), asi que la UI lo dice en vez de
     * prometer algo que no ocurre.
     */
    val canPersist: Boolean get() = !save.isNullOrEmpty()
}

/**
 * Cuerpo de la respuesta a un permiso. `decision` es obligatoria: el Hub rechaza
 * con 400 cualquier otro valor.
 */

data class PermissionReplyBody(
    val decision: String,
    val message: String? = null
)

/**
 * Estado de ejecucion de una sesion (GET /api/sessions/inflight).
 *
 * Lo lleva el vigilante del Hub, que se suscribe al stream de eventos de OpenCode.
 * `turnOver` es la unica senal fiable de "el agente termino TODO y esta esperando":
 * `info.time.completed` solo dice que el mensaje se cerro, y eso pasa despues de
 * cada `bash` con exit 0 aunque el agente siga trabajando.
 */

data class InflightSession(
    val id: String? = null,
    val since: Long? = null,
    val turnOver: Boolean = false,
    /**
     * Ultimo instante en el que se vio CUALQUIER evento de la sesion.
     *
     * Es lo que distingue un turno EN MARCHA de un registro que se quedo pegado. El
     * vigilante se desconecta y reconecta, y en el hueco pierde eventos: si se pierde el
     * `succeeded`, la sesion se queda "ocupada" hasta 15 minutos. Con `lastSeen` se
     * detecta: un turno vivo genera eventos a destajo (session.step.*, session.text.*,
     * session.usage.updated...), asi que el silencio prolongado significa registro
     * obsoleto, no turno lento.
     */
    val lastSeen: Long? = null
)

/**
 * Decide si el estado de turno del vigilante es de fiar, en UN solo sitio.
 *
 * Lo consumen DOS vistas (el indicador del chat y el círculo de la lista de chats) y
 * ambas sufrian el mismo bug por caminos separados, asi que la decisión va aquí y no
 * duplicada.
 */
