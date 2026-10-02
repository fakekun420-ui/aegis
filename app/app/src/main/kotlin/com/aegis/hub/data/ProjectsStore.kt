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
    // MEDIDO 2026-10-02: eran rutas ABSOLUTAS del arbol de compilacion. El store vivia en el
    // repo, asi que sin el repo la lista de proyectos salia VACIA y sin error: `loadOrMigrate`
    // no encontraba su fichero y empezaba con un registro en blanco. Ahora se resuelven en el
    // directorio privado de la app, y el arbol queda como ORIGEN de la migracion.
    private val storeFile: File = AppPaths.estado(NOMBRE_STORE),
    private val legacyFile: File = AppPaths.estado(NOMBRE_LEGACY),
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

    /**
     * MEDIDO 2026-10-02: copia el store desde el arbol de compilacion al directorio privado, una
     * sola vez.
     *
     * Las TRES condiciones se necesitan juntas, y por que:
     *  - si el destino ya tiene algo, no se toca: esta migracion no puede pisar datos mas nuevos.
     *  - si el origen no existe, no hay nada que migrar y no se dice nada.
     *  - si el origen esta DAÑADO (existe pero vacio o ilegible), se avisa. Un origen que existe y
     *    esta vacio es justo el estado que deja una copia fallida, y saltarselo en silencio
     *    convertiria ese estado en "no habia nada", que es el que hace perder los proyectos sin
     *    que nadie se entere.
     */
    private fun copiarDesdeArbolSiFalta() {
        if (storeFile.exists() && storeFile.length() > 0L) return
        for (nombre in listOf(NOMBRE_STORE, NOMBRE_LEGACY)) {
            val origen = File(RUTA_ARBOL_ANTES, nombre)
            val destino = File(storeFile.parentFile, nombre)
            when {
                !origen.exists() -> {
                    Log.i(TAG, "migracion: '$nombre' no esta en $RUTA_ARBOL_ANTES. Sin datos previos.")
                }
                origen.length() == 0L -> {
                    Log.w(TAG, "migracion: '$nombre' esta VACIO en el arbol. No se copia un " +
                        "fichero de 0 b: seria indistinguishable de 'no hay datos'.")
                }
                else -> try {
                    origen.copyTo(destino, overwrite = false)
                    Log.i(TAG, "migracion: '$nombre' copiado de $RUTA_ARBOL_ANTES a " +
                        "${storeFile.parentFile} (${origen.length()} b). El original NO se borra.")
                } catch (e: Exception) {
                    Log.e(TAG, "migracion: no se pudo copiar '$nombre' del arbol: ${e.message}", e)
                }
            }
        }
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

            // MEDIDO 2026-10-02: la migracion desde el ARBOL DE COMPILACION. Sin esto, instalar
            // el APK en un movil donde el repo no este deja los 27 proyectos en el arbol y la
            // app arranca con un registro VACIO, sin error. Con esto se copian una vez y se
            // trabaja sobre la copia: el arbol no se borra, porque es la unica copia.
            copiarDesdeArbolSiFalta()

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

    /**
     * MEDIDO 2026-10-02: esta funcion NO existia, y su ausencia la anote como "desvincular sigue
     * sin estar soportado por el store" en un commit anterior. Era cierto entonces, y por eso
     * `unlinkSession` delegaba en el Hub. Con el Hub retirado, esa delegacion no tiene a donde ir
     * y `unlinkSession` era un `= hub.` mas: un boton que al pulsarse no hacia nada.
     *
     * El vinculo se guarda al reves (`sessionProjects: sesion -> proyecto`), asi que desvincular
     * es quitar la entrada de la sesion. Se devuelve si estaba o no, para que el llamador pueda
     * decir "no estaba vinculada" en vez de fingir un borrado.
     *
     * NO borra el titulo ni el pin: son de la SESION, no del vinculo. Si se borraran aqui,
     * volver a vincular la misma sesion la devolveria sin nombre, que es peor que dejar un dato
     * de mas.
     */
    fun unlinkSessionFromProject(sessionId: String, projectId: String): Boolean = synchronized(lock) {
        val actual = data.sessionProjects[sessionId]
        // Solo se quita si apunta a ESE proyecto. Si la sesion se movio a otro, quitar el vinculo
        // desde el proyecto viejo la dejaria huerfana en el nuevo.
        if (actual != projectId) return@synchronized false
        data.sessionProjects.remove(sessionId)
        saveInternal()
        true
    }

    /**
     * Las sesiones que un proyecto tiene vinculadas. El store guarda el vinculo al reves, asi que
     * esto es la INVERSION del mapa: sin esto, `getProjectSessions` no tendria de donde sacar la
     * lista y solo podria devolver un `projects.json` entero, que no es lo que la pantalla
     * espera.
     *
     * El filtro es por proyecto, no por carpeta: `getProjectIdForSession` resuelve las dos cosas
     * (vinculo explicito y carpeta que coincide), y aqui se invierte esa resolucion para no
     * tener dos criterios distintos que puedan discrepar.
     */
    fun getSessionIdsForProject(projectId: String): List<String> = synchronized(lock) {
        data.sessionProjects.filterValues { it == projectId }.keys.toList()
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
        /**
         * MEDIDO 2026-10-02: apuntaba a `backend/projects.json`, y al mover el Hub se iba el
         * ORIGEN de la migracion — o sea, los 27 proyectos se perdian sin ruido. El legacy esta
         * ahora junto al store, en el directorio privado, y el fichero se copio ahi.
         *
         * El formato NO cambia: es el mismo que leia antes, y por eso la migracion lo
         * interpreta sin transformacion.
         */
        const val NOMBRE_STORE = "projects-store.json"
        const val NOMBRE_LEGACY = "projects.json"

        /**
         * MEDIDO 2026-10-02: el estado vivia en el ARBOL DE COMPILACION (`app/state/`), y ahi
         * sigue estando el primer despliegue. Esta es la ruta de donde se COPIA la primera vez.
         *
         * Se conserva porque es la unica copia de los 27 proyectos: si el arbol desaparece, no hay
         * de donde recuperarlos, y una app autonoma que borra los datos de su usuario al
         * instalarse no es autonoma, es destructiva.
         *
         * Se lee UNA vez por instalacion (ver `loadOrMigrate`), no en cada arranque.
         */
        const val RUTA_ARBOL_ANTES = "/sdcard/projects/Aegis/app/state"

        val default: ProjectsStore by lazy {
            ProjectsStore()
        }
    }
}
