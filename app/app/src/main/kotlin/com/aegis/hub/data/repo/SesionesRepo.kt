package com.aegis.hub.data.repo

import android.util.Log
import com.aegis.hub.data.CreateOpenCodeSessionRequest
import com.aegis.hub.data.OpenCodeApi
import com.aegis.hub.data.OpenCodeLocation
import com.aegis.hub.data.OpenCodeModelRef
import com.aegis.hub.data.Project
import com.aegis.hub.data.ProjectsStore
import com.aegis.hub.data.UpdateOpenCodeSessionRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 *    crea otra sesion. La clave se reserva ATOMICAMENTE antes de la red
 *    (V-04-RESERVA-ATOMICA, 2026-10-13): la segunda llamada concurrente espera
 *    a la que esta en vuelo y devuelve la misma sesion (1 POST, no 2).
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

        /**
         * Carpeta con la que nace una sesion global (sin carpeta ni proyecto).
         * MEDIDO 2026-10-09: sin `directory` el servidor usa su cwd (`/root`) y
         * el agente arranca fuera del workspace (sin proyecto, guard en `ask`,
         * skills de proyecto sin resolver). Verificado en Chat 3496.
         */
        const val CARPETA_POR_DEFECTO = "/sdcard/projects"
    }

    private var ultimaClave: String? = null
    private var ultimaCreada: SesionCreada? = null
    private var ultimaMs: Long = 0L

    /**
     * V-04-RESERVA-ATOMICA (2026-10-13): peticiones en vuelo por clave de
     * idempotencia, bajo [guardiaCreacion].
     *
     * MEDIDO en dispositivo (APK 1.2.1/401): un doble toque en "+ Nuevo chat"
     * creaba 2 sesiones a 122 ms porque la guarda anterior comprobaba
     * (`ultimaClave`, L70-76) y registraba (L119-123) DESPUES del await de la
     * red: carrera check-then-act — los dos toques pasaban la comprobacion
     * antes de que ninguno registrara. La ventana de 3 s solo contenia toques
     * SECUENCIALES, nunca concurrentes.
     */
    private val guardiaCreacion = Mutex()
    private val enVuelo = mutableMapOf<String, CompletableDeferred<Resultado<SesionCreada>>>()

    suspend fun crear(req: NuevaSesion, claveIdempotencia: String? = null): Resultado<SesionCreada> {
        val clave = claveIdempotencia ?: return ejecutarCreacion(req, null)

        // Reserva ATOMICA antes de la red: o hay una peticion en vuelo con esta
        // clave (se espera y se devuelve la misma sesion), o esta llamada se
        // convierte en la dueña. La segunda llamada concurrente NUNCA llega al POST.
        var ajena: CompletableDeferred<Resultado<SesionCreada>>? = null
        var mia: CompletableDeferred<Resultado<SesionCreada>>? = null
        guardiaCreacion.withLock {
            val vuelo = enVuelo[clave]
            if (vuelo != null) {
                ajena = vuelo
            } else {
                val previa = ultimaCreada
                if (clave == ultimaClave && previa != null && reloj() - ultimaMs < VENTANA_IDEMPOTENCIA_MS) {
                    Log.i(TAG, "crear: doble pulsacion con la misma clave, se reutiliza ${previa.id}")
                    ajena = CompletableDeferred<Resultado<SesionCreada>>(Resultado.Ok(previa))
                } else {
                    mia = CompletableDeferred<Resultado<SesionCreada>>().also { enVuelo[clave] = it }
                }
            }
        }
        ajena?.let { return it.await() }
        val duena = mia ?: error("SesionesRepo.crear: sin reserva ni espera para $clave")

        val resultado: Resultado<SesionCreada> = try {
            ejecutarCreacion(req, clave)
        } catch (e: Exception) {
            // El dueño cayo (red rota a mitad, cancelacion): soltar a los que
            // esperan con un Fallo en vez de dejar el Deferred colgado, y relanzar
            // (la cancelacion se propaga, no se traga).
            guardiaCreacion.withLock {
                enVuelo.remove(clave)
                if (!duena.isCompleted) {
                    duena.complete(Resultado.Fallo("No se pudo crear la sesión: ${e.message ?: "error de red"}", e))
                }
            }
            throw e
        }
        guardiaCreacion.withLock {
            duena.complete(resultado)
            if (resultado is Resultado.Ok) {
                ultimaClave = clave
                ultimaCreada = resultado.valor
                ultimaMs = reloj()
            }
            enVuelo.remove(clave)
        }
        return resultado
    }

    /**
     * El trabajo real de crear (red + agente/modelo/vinculo). Sin guarda propia:
     * la idempotencia vive en [crear], que reserva la clave ATOMICAMENTE antes
     * de llamar aqui (V-04-RESERVA-ATOMICA).
     */
    private suspend fun ejecutarCreacion(req: NuevaSesion, claveIdempotencia: String?): Resultado<SesionCreada> {
        val carpeta = req.carpeta?.takeIf { it.isNotBlank() }
            ?: req.proyectoId?.let { pid -> store.getProject(pid)?.folder?.takeIf { it.isNotBlank() } }
            ?: CARPETA_POR_DEFECTO

        val id = try {
            oc.createSession(
                CreateOpenCodeSessionRequest(
                    title = req.titulo,
                    location = OpenCodeLocation(directory = carpeta)
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
            Log.i(TAG, "crear: sesion $id con clave (reserva V-04-RESERVA-ATOMICA)")
        }
        return Resultado.Ok(creada, avisos)
    }

    /**
     * Corta el turno en curso de [id] (el `Esc`/`ctrl+d` del TUI: `POST api/session/{id}/interrupt`).
     *
     * Va por el repo y no por `Conexion.api` porque `interruptSession` solo existe en
     * [OpenCodeApi], no en `ApiService`; este repo ya tiene el cliente nativo inyectado.
     * No borra nada del servidor: el turno deja de estar vivo y el siguiente refresco
     * reconcilia el estado real.
     */
    suspend fun interrumpir(id: String): Resultado<Unit> {
        if (id.isBlank()) return Resultado.Fallo("No hay sesión que interrumpir")
        try {
            val r = oc.interruptSession(id)
            if (!r.isSuccessful) {
                return Resultado.Fallo("No se pudo interrumpir el turno (HTTP ${r.code()})")
            }
        } catch (e: Exception) {
            Log.w(TAG, "interrumpir $id fallo: ${e.message}")
            return Resultado.Fallo("No se pudo interrumpir el turno: ${e.message ?: "error de red"}", e)
        }
        return Resultado.Ok(Unit)
    }

    // ==========================================
    // Compactar / Revertir / Fork / Exportar
    //
    // Estas cinco operaciones existen en [OpenCodeApi] (cliente nativo), no en `ApiService`,
    // igual que [interrumpir]. Regla comun: exito SOLO con `isSuccessful` (2xx, 204 incluido);
    // si el servidor dice que no, [Resultado.Fallo] con el codigo HTTP y su cuerpo REAL.
    // ==========================================

    /**
     * Compacta el contexto de [id]: `POST api/session/{id}/compact` con body `{}`.
     *
     * No borra el historial. El servidor inserta un mensaje `type:"compaction"` con `summary`
     * y `reason:"manual"`; para verlo en pantalla hay que refrescar la cola (lo hace el VM).
     */
    suspend fun compactar(id: String): Resultado<Unit> =
        peticion("compactar el contexto", id) { oc.compactSession(id) }

    /**
     * Etapa 1 del undo: marca el punto al que se retrocede (`POST .../revert/stage`).
     * El servidor NO aplica nada hasta [revertirConfirmar]; [revertirCancelar] lo deshace.
     */
    suspend fun revertirEtapa(id: String, messageId: String): Resultado<Unit> {
        if (messageId.isBlank()) return Resultado.Fallo("No hay mensaje que deshacer")
        return peticion("preparar el undo", id) {
            oc.stageRevert(id, mapOf("messageID" to messageId, "files" to true))
        }
    }

    /** Etapa 2 del undo: aplica el retroceso preparado por [revertirEtapa]. */
    suspend fun revertirConfirmar(id: String): Resultado<Unit> =
        peticion("confirmar el undo", id) { oc.commitRevert(id) }

    /** Cancela un undo preparado y no confirmado (`DELETE .../revert` -> 204). */
    suspend fun revertirCancelar(id: String): Resultado<Unit> =
        peticion("cancelar el undo", id) { oc.clearRevert(id) }

    /**
     * Bifurca la sesión (`POST .../fork`, body `{before:null}`) y devuelve el id de la NUEVA.
     *
     * Si el servidor responde 2xx pero sin id legible, se devuelve [id] (la original): es lo
     * unico verificable. No se inventa un id que luego no exista en el servidor.
     */
    suspend fun forkear(id: String): Resultado<String> {
        if (id.isBlank()) return Resultado.Fallo("No hay sesión que bifurcar")
        return try {
            val resp = oc.forkSession(id, mapOf("before" to null))
            val nueva = resp.data?.id?.takeIf { it.isNotBlank() }
            Resultado.Ok(nueva ?: id)
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "forkear $id fallo: ${e.message}")
            Resultado.Fallo("No se pudo bifurcar la sesión: ${e.message ?: "error de red"}", e)
        }
    }

    /**
     * Exporta la transcripción como TEXTO legible (para copiar/compartir).
     *
     * `GET api/experimental/session/{id}/export` devuelve el historial entero: se recorta a
     * ~200 KB antes de parsear (un chat de miles de mensajes no cabe en un Intent sin
     * reventar el binder). Si el JSON no se puede interpretar, se devuelve el volcado crudo
     * recortado, nunca un "listo" falso.
     */
    suspend fun exportar(id: String): Resultado<String> {
        if (id.isBlank()) return Resultado.Fallo("No hay sesión que exportar")
        return try {
            val r = oc.exportSession(id)
            if (!r.isSuccessful) {
                return Resultado.Fallo("No se pudo exportar (HTTP ${r.code()}): ${cuerpoRecortado(r)}")
            }
            // El cuerpo se lee en IO: `ResponseBody.string()` es I/O de red y esto se llama
            // desde `viewModelScope` (Main). Sin esto, un export grande bloqueaba la UI.
            val cuerpo = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                r.body()?.string().orEmpty()
            }
            if (cuerpo.isBlank()) return Resultado.Fallo("El servidor devolvió un export vacío")
            Resultado.Ok(textoLegible(cuerpo))
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "exportar $id fallo: ${e.message}")
            Resultado.Fallo("No se pudo exportar la sesión: ${e.message ?: "error de red"}", e)
        }
    }

    /**
     * Envoltorio comun: exito solo con 2xx y, si no, HTTP + cuerpo real recortado.
     * El cuerpo importa: el servidor explica el rechazo ahi y perderlo dejaba
     * "no se pudo" sin causa, que es la mitad del problema.
     */
    private suspend fun peticion(
        etiqueta: String,
        id: String,
        bloque: suspend () -> retrofit2.Response<Unit>
    ): Resultado<Unit> {
        if (id.isBlank()) return Resultado.Fallo("Falta el id de sesión para $etiqueta")
        return try {
            val r = bloque()
            if (!r.isSuccessful) {
                return Resultado.Fallo("No se pudo $etiqueta (HTTP ${r.code()}): ${cuerpoRecortado(r)}")
            }
            Resultado.Ok(Unit)
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$etiqueta en $id fallo: ${e.message}")
            Resultado.Fallo("No se pudo $etiqueta: ${e.message ?: "error de red"}", e)
        }
    }

    private suspend fun cuerpoRecortado(r: retrofit2.Response<*>): String {
        val texto = try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                r.errorBody()?.string().orEmpty()
            }
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (_: Exception) {
            ""
        }
        val limpio = texto.replace(RX_ESPACIOS, " ").trim()
        return if (limpio.isBlank()) "(sin cuerpo)" else limpio.take(300)
    }

    /** JSON del export -> texto con `[role]` y el texto de cada mensaje. */
    private fun textoLegible(json: String): String {
        val limite = 200_000
        val base = if (json.length > limite) {
            json.substring(0, limite) +
                "\n\n[Export RECORTADO a ${limite / 1024} KB de ${json.length} caracteres]"
        } else {
            json
        }
        val raiz = try {
            com.google.gson.JsonParser.parseString(base).asJsonObject
        } catch (_: Exception) {
            null
        } ?: return base
        val info = raiz.getAsJsonObject("info")
            ?: raiz.getAsJsonObject("data")?.getAsJsonObject("info")
        val titulo = info?.get("title")?.let { if (it.isJsonPrimitive) it.asString else null }
        val lista = (raiz.get("messages") ?: raiz.getAsJsonObject("data")?.get("messages"))
            ?.let { if (it.isJsonArray) it.asJsonArray else null }
            ?: return base
        val sb = StringBuilder()
        sb.append("Transcripción")
        if (!titulo.isNullOrBlank()) sb.append(" — ").append(titulo)
        sb.append("\n\n")
        lista.forEach { el ->
            val m = if (el.isJsonObject) el.asJsonObject else return@forEach
            val meta = m.getAsJsonObject("info")
            val rol = (meta?.get("role") ?: m.get("role"))
                ?.let { if (it.isJsonPrimitive) it.asString else null }
                ?: "?"
            val texto = textoDeMensaje(m, meta)
            if (texto.isNotBlank()) sb.append("[").append(rol).append("]\n").append(texto).append("\n\n")
        }
        return if (sb.length <= 32) base else sb.toString()
    }

    private fun textoDeMensaje(
        m: com.google.gson.JsonObject,
        meta: com.google.gson.JsonObject?
    ): String {
        (meta?.get("text") ?: m.get("text"))
            ?.let { if (it.isJsonPrimitive) it.asString else null }
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        val partes = (m.get("parts") ?: meta?.get("parts"))
            ?.let { if (it.isJsonArray) it.asJsonArray else null }
            ?: return ""
        return partes.mapNotNull { p ->
            val o = if (p.isJsonObject) p.asJsonObject else return@mapNotNull null
            val tipo = o.get("type")?.let { if (it.isJsonPrimitive) it.asString else null }
            if (tipo != null && tipo != "text") return@mapNotNull null
            o.get("text")?.let { if (it.isJsonPrimitive) it.asString else null }
        }.joinToString("\n")
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
/**
 * Colapsa saltos de línea y espacios del cuerpo de error para que el motivo quepa en una
 * línea. Compilado UNA vez a nivel de fichero (crear `Regex` por llamada costaba en el hilo
 * principal y el check de hilo principal lo marca).
 */
private val RX_ESPACIOS = Regex("\\s+")

fun carpetaDeProyecto(p: Project): String? =
    p.folder?.takeIf { it.isNotBlank() } ?: p.resolvedFolder.takeIf { it.isNotBlank() }
