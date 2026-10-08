package com.aegis.hub.data.repo

import android.util.Log
import com.aegis.hub.data.AgentPreferences
import com.aegis.hub.data.AppContext
import com.aegis.hub.data.ModelPreferences
import com.aegis.hub.data.ModelosUtil
import com.aegis.hub.data.OpenCodeApi
import com.aegis.hub.data.OpenCodeModelRef
import com.aegis.hub.data.SetSessionAgentRequest
import com.aegis.hub.data.SetSessionModelRequest

/**
 * Modelo y agente con el servidor como unica verdad (F3).
 *
 * - [leer]: UN solo `GET /api/session/{id}` (titulo + modelo + agente). Si el
 *   servidor falla, devuelve la cache local marcada [Origen.CACHE_LOCAL].
 * - [fijarModelo]/[fijarAgente]: servidor primero; la cache SOLO se escribe si el
 *   servidor confirma. Fallo -> [Resultado.Fallo] con motivo (el llamador revierte).
 * - La cache ([ConfigCache]) son las prefs de siempre (mismas claves: no se pierde
 *   nada de usuarios existentes) usadas solo como ultimo-usado y respaldo.
 */
data class ConfigSesion(
    val sessionId: String,
    val titulo: String?,
    val modelo: String?,
    val agente: String?,
    val origen: Origen
) {
    enum class Origen { SERVIDOR, CACHE_LOCAL }
}

interface ConfigCache {
    fun leerModelo(sid: String): String?
    fun guardarModelo(sid: String, modelo: String)
    fun leerAgente(sid: String): String?
    fun guardarAgente(sid: String, agente: String)
    fun ultimoModelo(): String?
    fun guardarUltimoModelo(modelo: String)
    fun ultimoAgente(): String?
    fun guardarUltimoAgente(agente: String)
}

/** Cache real sobre `ModelPreferences`/`AgentPreferences` (mismas claves). */
class PrefsConfigCache : ConfigCache {
    private fun ctx() = runCatching { AppContext.require() }.getOrNull()
    override fun leerModelo(sid: String): String? = ctx()?.let { ModelPreferences.modelFor(it, sid) }
    override fun guardarModelo(sid: String, modelo: String) {
        ctx()?.let { ModelPreferences.setModel(it, sid, modelo) }
    }
    override fun leerAgente(sid: String): String? = ctx()?.let { AgentPreferences.agentFor(it, sid) }
    override fun guardarAgente(sid: String, agente: String) {
        ctx()?.let { AgentPreferences.setAgent(it, sid, agente) }
    }
    override fun ultimoModelo(): String? = ctx()?.let { ModelPreferences.lastModel(it) }
    override fun guardarUltimoModelo(modelo: String) {
        // setModel con sesion en blanco solo toca la ultima eleccion (ver su KDoc).
        ctx()?.let { ModelPreferences.setModel(it, "", modelo) }
    }
    override fun ultimoAgente(): String? = ctx()?.let { AgentPreferences.lastAgent(it) }
    override fun guardarUltimoAgente(agente: String) {
        ctx()?.let { AgentPreferences.setAgent(it, "", agente) }
    }
}

class SesionConfigRepo(
    private val oc: OpenCodeApi = OpenCodeApi.default,
    private val cache: ConfigCache = PrefsConfigCache(),
    catalogoInyectado: CatalogoRepo? = null,
    private val reloj: () -> Long = { System.currentTimeMillis() }
) {
    // El catalogo deriva del mismo `oc` si no se inyecta: en tests basta pasar el
    // fake una vez; en produccion son los singletons de siempre.
    private val catalogo: CatalogoRepo by lazy { catalogoInyectado ?: CatalogoRepo(oc) }
    companion object {
        private const val TAG = "SesionConfigRepo"

        /**
         * Salta el POST redundante de modelo (F3.3): si se fijo el mismo valor hace
         * menos de 60 s (y la lectura es fresca), el servidor ya lo tiene. Si en
         * V-03 aparece un chip desfasado, poner en `false`.
         */
        const val OMITIR_FIJADO_REDUNDANTE = true
        const val FRESCURA_LECTURA_MS = 60_000L
    }

    private val ultimoFijado = mutableMapOf<String, String>()
    private val ultimoLeido = mutableMapOf<String, Long>()

    /** Olvida el fijado cacheado (F5 lo llamara al ver cambios externos). */
    fun olvidarFijado(sessionId: String) {
        ultimoFijado.remove(sessionId)
    }

    suspend fun leer(sid: String): ConfigSesion {
        if (sid.isBlank()) {
            return ConfigSesion(
                sessionId = "",
                titulo = "Nuevo chat",
                modelo = cache.ultimoModelo(),
                agente = cache.ultimoAgente() ?: SesionesRepo.AGENTE_POR_DEFECTO,
                origen = ConfigSesion.Origen.CACHE_LOCAL
            )
        }
        try {
            val s = oc.getSession(sid).data
            if (s != null) {
                val modelo = s.model?.id?.let { ModelosUtil.normalizarIdModelo(it) }?.takeIf { it.isNotBlank() }
                val agente = s.agent?.trim()?.takeIf { it.isNotBlank() }
                if (modelo != null) cache.guardarModelo(sid, modelo)
                if (agente != null) cache.guardarAgente(sid, agente)
                ultimoLeido[sid] = reloj()
                return ConfigSesion(
                    sessionId = sid,
                    titulo = s.title,
                    modelo = modelo,
                    agente = agente,
                    origen = ConfigSesion.Origen.SERVIDOR
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "leer $sid fallo, tira de cache: ${e.message}")
        }
        return ConfigSesion(
            sessionId = sid,
            titulo = null,
            modelo = cache.leerModelo(sid),
            agente = cache.leerAgente(sid),
            origen = ConfigSesion.Origen.CACHE_LOCAL
        )
    }

    suspend fun fijarModelo(
        sid: String,
        ref: String,
        variantExplicita: String? = null,
        pistaProveedor: String? = null
    ): Resultado<Unit> {
        val limpio = ModelosUtil.normalizarIdModelo(ref)
        if (limpio.isBlank()) return Resultado.Fallo("Modelo vacío: nada que fijar")
        if (sid.isBlank()) {
            // Chat nuevo sin id: no hay servidor al que empujar; queda como ultima
            // eleccion para cuando nazca la sesion.
            cache.guardarUltimoModelo(limpio)
            return Resultado.Ok(Unit)
        }
        val clave = "modelo:$limpio#$variantExplicita"
        if (OMITIR_FIJADO_REDUNDANTE && ultimoFijado[sid] == clave &&
            reloj() - (ultimoLeido[sid] ?: 0L) < FRESCURA_LECTURA_MS
        ) {
            return Resultado.Ok(Unit)
        }
        var nativos = try {
            catalogo.modelosNativos()
        } catch (e: Exception) {
            emptyList()
        }
        if (nativos.isNotEmpty() && nativos.none { (it.id ?: it.modelID) == limpio }) {
            // El modelo no está en la caché del catálogo: refrescar una vez (single-flight)
            catalogo.invalidar()
            nativos = try {
                catalogo.modelosNativos()
            } catch (e: Exception) {
                emptyList()
            }
            if (nativos.isNotEmpty() && nativos.none { (it.id ?: it.modelID) == limpio }) {
                return Resultado.Fallo("El modelo «$limpio» no está disponible en OpenCode")
            }
        }
        val nativosFinales = nativos
        try {
            val prov = ModelosUtil.proveedorDeRef(ref)
                ?: ModelosUtil.resolveProviderFor(limpio, pistaProveedor, nativosFinales)
            val variante = variantExplicita?.trim()?.takeIf { it.isNotBlank() }
                ?: ModelosUtil.resolveVariantFor(limpio, nativosFinales)
            val r = oc.setSessionModel(
                sid,
                SetSessionModelRequest(
                    OpenCodeModelRef(id = limpio, providerID = prov, variant = variante)
                )
            )
            if (!r.isSuccessful) {
                // Catalogo rancio (el modelo ya no existe): se invalida para que la
                // proxima lo vuelva a descargar.
                if (r.code() == 404) catalogo.invalidar()
                return Resultado.Fallo("El servidor no aceptó el modelo $limpio (HTTP ${r.code()})")
            }
        } catch (e: Exception) {
            Log.w(TAG, "fijarModelo $limpio en $sid fallo: ${e.message}")
            return Resultado.Fallo("No se pudo fijar el modelo $limpio: ${e.message ?: "error de red"}", e)
        }
        cache.guardarModelo(sid, limpio)
        cache.guardarUltimoModelo(limpio)
        ultimoFijado[sid] = clave
        return Resultado.Ok(Unit)
    }

    suspend fun fijarAgente(sid: String, nombre: String): Resultado<Unit> {
        val limpio = nombre.trim()
        if (limpio.isBlank()) return Resultado.Fallo("Agente vacío: nada que fijar")
        if (sid.isBlank()) {
            cache.guardarUltimoAgente(limpio)
            return Resultado.Ok(Unit)
        }
        try {
            val r = oc.setSessionAgent(sid, SetSessionAgentRequest(limpio))
            if (!r.isSuccessful) {
                return Resultado.Fallo("El servidor no aceptó el agente $limpio (HTTP ${r.code()})")
            }
        } catch (e: Exception) {
            Log.w(TAG, "fijarAgente $limpio en $sid fallo: ${e.message}")
            return Resultado.Fallo("No se pudo fijar el agente $limpio: ${e.message ?: "error de red"}", e)
        }
        if (sid.isNotBlank()) cache.guardarAgente(sid, limpio)
        cache.guardarUltimoAgente(limpio)
        return Resultado.Ok(Unit)
    }

    /** Modelo que un agente trae definido, o null si delega en el de la sesion. */
    suspend fun modeloDeAgente(nombre: String): OpenCodeModelRef? = runCatching {
        ModelosUtil.modeloDelAgente(nombre, catalogo.agentesNativos())
    }.getOrNull()
}
