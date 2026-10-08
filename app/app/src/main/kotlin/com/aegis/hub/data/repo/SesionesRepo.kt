package com.aegis.hub.data.repo

import android.util.Log
import com.aegis.hub.data.CreateOpenCodeSessionRequest
import com.aegis.hub.data.OpenCodeApi
import com.aegis.hub.data.OpenCodeLocation
import com.aegis.hub.data.OpenCodeModelRef
import com.aegis.hub.data.Project
import com.aegis.hub.data.ProjectsStore
import com.aegis.hub.data.UpdateOpenCodeSessionRequest

/**
 * Un solo camino para crear, renombrar, borrar y vincular sesiones (F2).
 *
 * Reglas (plan T-F2.1):
 * 1. `crear` llama UNA vez a `POST /api/session`. Si lanza o viene sin id, es
 *    [Resultado.Fallo] con motivo. Sin reintentos automaticos (evita la sesion
 *    duplicada del "fallback fantasma", H-03).
 * 2. Con el id ya creado, agente/modelo/vinculo NO convierten el exito en fallo:
 *    cada uno que falle aporta un aviso.
 * 3. El vinculo es UNA sola escritura (`store.linkSessionToProject` +
 *    `setSessionTitle`); la costura delega aqui en vez de escribir dos veces.
 * 4. Idempotencia optativa contra doble pulsacion: misma clave dentro de 3 s no
 *    crea otra sesion.
 */
data class NuevaSesion(
    val titulo: String,
    val proyectoId: String? = null,
    val carpeta: String? = null,
    val agente: String? = null,
    val modelo: OpenCodeModelRef? = null
)

data class SesionCreada(val id: String, val titulo: String, val carpeta: String?)

class SesionesRepo(
    private val oc: OpenCodeApi = OpenCodeApi.default,
    private val store: ProjectsStore = ProjectsStore.default,
    configInyectada: SesionConfigRepo? = null,
    private val reloj: () -> Long = { System.currentTimeMillis() }
) {
    private val config: SesionConfigRepo by lazy { configInyectada ?: SesionConfigRepo(oc) }
    companion object {
        private const val TAG = "SesionesRepo"

        /**
         * Agente con el que nace una sesion si no se pide otro. Mismo valor que el
         * `default_agent` del servidor ("build"); era "orchestrator" hasta que la
         * migracion ECC lo retiro.
         */
        const val AGENTE_POR_DEFECTO = "build"

        /** Ventana anti doble pulsacion para [crear]. */
        const val VENTANA_IDEMPOTENCIA_MS = 3_000L
    }

    private var ultimaClave: String? = null
    private var ultimaCreada: SesionCreada? = null
    private var ultimaMs: Long = 0L

    suspend fun crear(req: NuevaSesion, claveIdempotencia: String? = null): Resultado<SesionCreada> {
        if (claveIdempotencia != null && claveIdempotencia == ultimaClave) {
            val previa = ultimaCreada
            if (previa != null && reloj() - ultimaMs < VENTANA_IDEMPOTENCIA_MS) {
                Log.i(TAG, "crear: doble pulsacion con la misma clave, se reutiliza ${previa.id}")
                return Resultado.Ok(previa)
            }
        }

        val carpeta = req.carpeta?.takeIf { it.isNotBlank() }
            ?: req.proyectoId?.let { pid -> store.getProject(pid)?.folder?.takeIf { it.isNotBlank() } }

        val id = try {
            oc.createSession(
                CreateOpenCodeSessionRequest(
                    title = req.titulo,
                    location = carpeta?.let { OpenCodeLocation(directory = it) }
                )
            ).data?.id?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "crear: POST /api/session fallo: ${e.message}")
            return Resultado.Fallo("No se pudo crear la sesión: ${e.message ?: "error de red"}", e)
        } ?: return Resultado.Fallo("No se pudo crear la sesión: el servidor no devolvió id")

        val avisos = mutableListOf<String>()

        val agente = req.agente?.trim()?.takeIf { it.isNotBlank() } ?: AGENTE_POR_DEFECTO
        when (val r = config.fijarAgente(id, agente)) {
            is Resultado.Ok -> Unit
            is Resultado.Fallo -> avisos.add("No se pudo fijar el agente $agente")
        }

        val modelo = req.modelo ?: config.modeloDeAgente(agente)
        if (modelo != null) {
            when (val r = config.fijarModelo(id, modelo.id, modelo.variant, modelo.providerID)) {
                is Resultado.Ok -> Unit
                is Resultado.Fallo -> avisos.add("No se pudo fijar el modelo ${modelo.id}")
            }
        }

        val pid = req.proyectoId?.trim()?.takeIf { it.isNotBlank() }
        if (pid != null) {
            when (val v = vincular(id, pid, req.titulo)) {
                is Resultado.Ok -> avisos.addAll(v.avisos)
                is Resultado.Fallo -> avisos.add("Sesión creada pero no vinculada al proyecto")
            }
        }

        val creada = SesionCreada(id = id, titulo = req.titulo, carpeta = carpeta)
        if (claveIdempotencia != null) {
            ultimaClave = claveIdempotencia
            ultimaCreada = creada
            ultimaMs = reloj()
        }
        return Resultado.Ok(creada, avisos)
    }

    suspend fun renombrar(id: String, titulo: String): Resultado<Unit> {
        if (titulo.isBlank()) return Resultado.Fallo("No se puede renombrar con el título vacío")
        try {
            val r = oc.updateSession(id, UpdateOpenCodeSessionRequest(title = titulo))
            if (!r.isSuccessful) {
                return Resultado.Fallo("No se pudo renombrar la sesión (HTTP ${r.code()})")
            }
        } catch (e: Exception) {
            Log.w(TAG, "renombrar $id fallo: ${e.message}")
            return Resultado.Fallo("No se pudo renombrar la sesión: ${e.message ?: "error de red"}", e)
        }
        // El titulo local solo se escribe si el servidor confirmo (antes se escribia
        // primero y se perdia el rastro si el servidor rechazaba).
        store.setSessionTitle(id, titulo)
        return Resultado.Ok(Unit)
    }

    suspend fun borrar(id: String): Resultado<Unit> {
        try {
            val r = oc.deleteSession(id)
            if (!r.isSuccessful) {
                return Resultado.Fallo("No se pudo borrar la sesión (HTTP ${r.code()})")
            }
        } catch (e: Exception) {
            Log.w(TAG, "borrar $id fallo: ${e.message}")
            return Resultado.Fallo("No se pudo borrar la sesión: ${e.message ?: "error de red"}", e)
        }
        return Resultado.Ok(Unit)
    }

    suspend fun vincular(id: String, proyectoId: String, titulo: String?): Resultado<Unit> {
        return try {
            store.linkSessionToProject(id, proyectoId)
            titulo?.takeIf { it.isNotBlank() }?.let { store.setSessionTitle(id, it) }
            Resultado.Ok(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "vincular $id a $proyectoId fallo: ${e.message}")
            Resultado.Fallo("No se pudo vincular la sesión al proyecto: ${e.message ?: "error de red"}", e)
        }
    }

    suspend fun desvincular(id: String, proyectoId: String): Resultado<Unit> {
        return try {
            store.unlinkSessionFromProject(id, proyectoId)
            Resultado.Ok(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "desvincular $id de $proyectoId fallo: ${e.message}")
            Resultado.Fallo("No se pudo desvincular la sesión: ${e.message ?: "error de red"}", e)
        }
    }

}

/**
 * Una unica funcion para la carpeta de un proyecto (F2).
 *
 * `folder` no vacia manda; si no, la resuelta. La derivacion
 * `/sdcard/projects/<nombre>` de `ProjectDetailViewModel` desaparece a favor
 * de esta funcion (es identica a `resolvedFolder`, asi que nada cambia).
 */
fun carpetaDeProyecto(p: Project): String? =
    p.folder?.takeIf { it.isNotBlank() } ?: p.resolvedFolder.takeIf { it.isNotBlank() }
