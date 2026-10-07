package com.aegis.hub.data

import com.google.gson.annotations.SerializedName

/**
 * Proyectos, sesiones, vinculos y skills (F8: trozo de Models.kt, mismo paquete).
 */

private val RX_NOMBRE_CARPETA = Regex("[^a-z0-9_-]")

// Generic envelope server.js returns: { ok:true, data: ... } or { ok:false, error:{code,message} }

data class SessionRef(
    @SerializedName("sessionId") val sessionId: String,
    val title: String? = null,
    val createdAt: String? = null,
    val lastUsed: String? = null,
    val summary: String? = null,
    val pinned: Boolean = false,
    val provider: String? = null
) {
    fun resolvedProvider(projectProvider: String? = null): String {
        // Solo hay un motor, asi que esto ya no decide nada. Se conserva la firma
        // porque hay 5 llamadas y el campo `provider` sigue viajando en el store.
        return "OpenCode"
    }
}

data class Project(
    val id: String,
    val name: String,
    val description: String? = null,
    val createdAt: String? = null,
    val archivedAt: String? = null,
    val provider: String? = "opencode",
    val folder: String? = null,
    val ponytail: String? = null,
    val sessions: List<SessionRef>? = null,
    val skills: List<Any>? = null,
    val linkedProjects: List<String>? = null
) {
    val resolvedProvider: String get() = "OpenCode"
    val resolvedFolder: String get() = folder ?: "/sdcard/projects/${name.lowercase().replace(" ", "-").replace(RX_NOMBRE_CARPETA, "")}/"
}

data class CreateProjectRequest(
    val name: String,
    val description: String? = null,
    val provider: String? = "opencode",
    // Ruta de una carpeta YA existente bajo /sdcard/projects para vincularla como
    // proyecto en vez de crear una carpeta nueva a partir del nombre. El backend la
    // valida: debe resolver dentro de PROJECTS_ROOT (FOLDER_OUTSIDE_ROOT) y no puede
    // pertenecer a otro proyecto activo (DUPLICATE_FOLDER).
    val folder: String? = null
)

data class PatchProjectRequest(
    val name: String? = null,
    val description: String? = null,
    val archived: Boolean? = null,
    val provider: String? = null
)

data class LinkSessionRequest(
    val sessionId: String,
    val title: String? = null,
    val provider: String? = "opencode"
)

// ---- Sessions (proxy /api/opencode/sessions -> opencode /session) ----

data class Sesion(
    val id: String? = null,
    @SerializedName("ID") val ID: String? = null,
    val title: String? = null,
    val name: String? = null,
    // F6: registro único — nombre REAL que le puso OpenCode (title = nombre puesto desde la app)
    val providerTitle: String? = null,
    // F8: tipado (antes `Any?`; nadie lo leia como Any: solo se mapeaba y se pedia su providerID).
    val model: ModeloRef? = null,
    val createdAt: String? = null,
    @SerializedName("created_at") val createdAtAlt: String? = null,
    val updatedAt: String? = null,
    @SerializedName("updated_at") val updatedAtAlt: String? = null,
    val pinned: Boolean = false,
    val provider: String? = null
) {
    val resolvedId: String get() = id ?: ID ?: ""
    val resolvedTitle: String get() = title ?: name ?: resolvedId.take(8)
    val lastActivityIso: String? get() = updatedAt ?: updatedAtAlt ?: createdAt ?: createdAtAlt
    val resolvedProvider: String get() = "OpenCode"
}

data class PinResponse(
    val id: String,
    val pinned: Boolean = false
)

/** Referencia de modelo de una sesion (id + proveedor + variante opcional). */
data class ModeloRef(
    val id: String? = null,
    val providerID: String? = null,
    val variant: String? = null
)

// ---- Messages (GET /session/:id/message proxied via hub) ----

data class Skill(
    val scope: String,
    val name: String,
    val content: String
)

data class SkillListResponse(
    val skills: List<Skill>,
    val projectId: String? = null,
    val counts: Map<String, Int>? = null
)

data class SkillCreateRequest(
    val scope: String,
    val name: String,
    val content: String
)
