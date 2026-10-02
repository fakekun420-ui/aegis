package com.aegis.hub.data

import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Registro local sesion <-> proyecto con persistencia JSON.
 *
 * OpenCode v2 no persiste la relacion de carpetas con proyectos propios
 * (GET /api/project da 404 para proyectos puntuales o lista benchs temporales).
 * ProjectsStore mantiene la asociacion de:
 * 1. Proyectos conocidos (id, nombre, ruta de carpeta /sdcard/projects/...).
 * 2. Sesiones asociadas a cada proyecto (por directorio de trabajo o vinculo explicito).
 * 3. Titulos de sesion (renombrados o derivados).
 * 4. Estado fijado (pinned) por sesion.
 *
 * Soporta migracion inicial automatica desde backend/projects.json si existe.
 */
class ProjectsStore(
    private val storeFile: File = File(DEFAULT_STORE_PATH),
    private val legacyFile: File = File(DEFAULT_LEGACY_PATH),
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    data class StoreData(
        @SerializedName("projects") val projects: MutableList<ProjectEntry> = mutableListOf(),
        @SerializedName("sessionTitles") val sessionTitles: MutableMap<String, String> = mutableMapOf(),
        @SerializedName("sessionPins") val sessionPins: MutableMap<String, Boolean> = mutableMapOf(),
        @SerializedName("sessionProjects") val sessionProjects: MutableMap<String, String> = mutableMapOf()
    )

    data class ProjectEntry(
        @SerializedName("id") val id: String,
        @SerializedName("name") val name: String,
        @SerializedName("description") val description: String? = null,
        @SerializedName("folder") val folder: String,
        @SerializedName("createdAt") val createdAt: Long = System.currentTimeMillis(),
        @SerializedName("archivedAt") val archivedAt: Long? = null
    )

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val lock = Any()
    private var data: StoreData = StoreData()

    init {
        loadOrMigrate()
    }

    private fun loadOrMigrate() {
        synchronized(lock) {
            if (storeFile.exists() && storeFile.length() > 0L) {
                try {
                    val text = storeFile.readText(StandardCharsets.UTF_8)
                    val parsed = gson.fromJson(text, StoreData::class.java)
                    if (parsed != null) {
                        data = parsed
                        return
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error leyendo storeFile, intentando fallback legacy: ${e.message}")
                }
            }

            if (legacyFile.exists() && legacyFile.length() > 0L) {
                try {
                    val legacyText = legacyFile.readText(StandardCharsets.UTF_8)
                    val json = com.google.gson.JsonParser.parseString(legacyText).asJsonObject
                    val projectsArray = json.getAsJsonArray("projects")
                    val migratedProjects = mutableListOf<ProjectEntry>()
                    val migratedSessionProjects = mutableMapOf<String, String>()

                    if (projectsArray != null) {
                        for (elem in projectsArray) {
                            val obj = elem.asJsonObject
                            val id = obj.get("id")?.asString ?: continue
                            val name = obj.get("name")?.asString ?: id
                            val desc = obj.get("description")?.asString
                            val folder = obj.get("folder")?.asString ?: obj.get("directory")?.asString ?: "/sdcard/projects/$name"
                            migratedProjects.add(
                                ProjectEntry(
                                    id = id,
                                    name = name,
                                    description = desc,
                                    folder = folder
                                )
                            )
                            val sessionsArr = obj.getAsJsonArray("sessions")
                            if (sessionsArr != null) {
                                for (s in sessionsArr) {
                                    val sid = if (s.isJsonObject) s.asJsonObject.get("sessionId")?.asString else s.asString
                                    if (!sid.isNullOrBlank()) {
                                        migratedSessionProjects[sid] = id
                                    }
                                }
                            }
                        }
                    }

                    val titlesMap = mutableMapOf<String, String>()
                    val legacyTitles = json.getAsJsonObject("sessionTitles")
                    if (legacyTitles != null) {
                        for ((k, v) in legacyTitles.entrySet()) {
                            titlesMap[k] = v.asString
                        }
                    }

                    val pinsMap = mutableMapOf<String, Boolean>()
                    val legacyPins = json.getAsJsonObject("sessionPins")
                    if (legacyPins != null) {
                        for ((k, v) in legacyPins.entrySet()) {
                            pinsMap[k] = v.asBoolean
                        }
                    }

                    data = StoreData(
                        projects = migratedProjects,
                        sessionTitles = titlesMap,
                        sessionPins = pinsMap,
                        sessionProjects = migratedSessionProjects
                    )
                    saveInternal()
                    Log.i(TAG, "Migracion exitosa desde legacy: ${migratedProjects.size} proyectos migrados")
                } catch (e: Exception) {
                    Log.e(TAG, "Error en migracion legacy: ${e.message}")
                    data = StoreData()
                }
            } else {
                data = StoreData()
            }
        }
    }

    private fun saveInternal() {
        try {
            val parent = storeFile.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            val tmpFile = File(storeFile.absolutePath + ".tmp")
            tmpFile.writeText(gson.toJson(data), StandardCharsets.UTF_8)
            if (tmpFile.renameTo(storeFile)) {
                // guardado atomico ok
            } else {
                storeFile.writeText(gson.toJson(data), StandardCharsets.UTF_8)
                tmpFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error guardando ProjectsStore: ${e.message}")
        }
    }

    fun getAllProjects(): List<ProjectEntry> = synchronized(lock) {
        data.projects.toList()
    }

    fun getProject(id: String): ProjectEntry? = synchronized(lock) {
        data.projects.find { it.id == id }
    }

    fun saveProject(entry: ProjectEntry) = synchronized(lock) {
        val idx = data.projects.indexOfFirst { it.id == entry.id }
        if (idx >= 0) {
            data.projects[idx] = entry
        } else {
            data.projects.add(entry)
        }
        saveInternal()
    }

    fun deleteProject(id: String) = synchronized(lock) {
        data.projects.removeAll { it.id == id }
        val toRemove = data.sessionProjects.filter { it.value == id }.keys
        toRemove.forEach { data.sessionProjects.remove(it) }
        saveInternal()
    }

    fun linkSessionToProject(sessionId: String, projectId: String) = synchronized(lock) {
        data.sessionProjects[sessionId] = projectId
        saveInternal()
    }

    fun getProjectIdForSession(sessionId: String, directory: String? = null): String? = synchronized(lock) {
        val explicit = data.sessionProjects[sessionId]
        if (!explicit.isNullOrBlank()) return explicit

        if (!directory.isNullOrBlank()) {
            val normalizedDir = directory.trim().trimEnd('/')
            val matched = data.projects.find { it.folder.trim().trimEnd('/') == normalizedDir }
            if (matched != null) return matched.id
        }
        return null
    }

    fun setSessionTitle(sessionId: String, title: String) = synchronized(lock) {
        data.sessionTitles[sessionId] = title
        saveInternal()
    }

    fun getSessionTitle(sessionId: String): String? = synchronized(lock) {
        data.sessionTitles[sessionId]
    }

    fun setSessionPin(sessionId: String, pinned: Boolean) = synchronized(lock) {
        data.sessionPins[sessionId] = pinned
        saveInternal()
    }

    fun isSessionPinned(sessionId: String): Boolean = synchronized(lock) {
        data.sessionPins[sessionId] ?: false
    }

    companion object {
        private const val TAG = "ProjectsStore"
        const val DEFAULT_STORE_PATH = "/sdcard/projects/Aegis/app/state/projects-store.json"
        /**
         * MEDIDO 2026-10-02: apuntaba a `backend/projects.json`, y al mover el Hub se iba el
         * ORIGEN de la migracion — o sea, los 27 proyectos se perdian sin ruido. Ahora el
         * legacy esta junto al store, en `app/state/`, y el fichero se copio ahi.
         *
         * El formato NO cambia: es el mismo que leia antes, y por eso la migracion lo
         * interpreta sin transformacion.
         */
        const val DEFAULT_LEGACY_PATH = "/sdcard/projects/Aegis/app/state/projects.json"

        val default: ProjectsStore by lazy {
            ProjectsStore()
        }
    }
}
