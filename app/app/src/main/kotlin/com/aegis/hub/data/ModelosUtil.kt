package com.aegis.hub.data

/**
 * Pistas puras de modelo/agente, sin red ni estado (F2).
 *
 * Movidas verbatim desde `RutaNativa` (misma logica, mismos tests en
 * `SesionModeloSyncTest`, ahora contra este objeto): la creacion de sesiones
 * (`SesionesRepo`) y el envio las necesitan sin pasar por la costura.
 */
object ModelosUtil {

    /**
     * Guardia de envio (F13): si el catalogo YA cargo y el modelo elegido no esta,
     * enviar seria un turno condenado (el "enviar se buguea" con chip
     * "No disponible"). Devuelve el motivo o null si se puede enviar.
     * Con catalogo vacio (sin cargar / sin red) o sin modelo elegido no bloquea:
     * decide el servidor, como antes.
     */
    fun motivoModeloNoDisponible(elegido: String?, catalogo: List<ModeloElegible>): String? {
        val id = elegido?.trim().orEmpty()
        if (id.isBlank() || catalogo.isEmpty()) return null
        if (catalogo.any { it.id == id }) return null
        return "El modelo «$id» ya no está en el catálogo. Elige otro en el selector de modelo; tu texto se conserva."
    }

    /**
     * Resuelve el providerID de un id de modelo contra el catalogo vivo.
     *
     * MEDIDO 2026-10-03: `POST /api/session/{id}/model` exige la pareja id mas providerID, y
     * la app solo guardaba el id. Si el id existe en varios proveedores, manda la pista
     * (el provider del chat); si no, prefiere `opencode`; si ni eso, el primero.
     */
    fun resolveProviderFor(modelId: String?, hint: String?, catalogo: List<OpenCodeNativeModel>): String {
        val id = normalizarIdModelo(modelId)
        if (id.isEmpty()) return "opencode"
        val candidatos = catalogo.filter { (it.id ?: it.modelID) == id }
        if (candidatos.isEmpty()) return hint?.trim()?.takeIf { it.isNotBlank() } ?: "opencode"
        val pista = hint?.trim()?.takeIf { it.isNotBlank() }
        val porPista = pista?.let { h -> candidatos.firstOrNull { it.providerID == h } }
        if (porPista != null) return porPista.providerID
        return candidatos.firstOrNull { it.providerID == "opencode" }?.providerID
            ?: candidatos.first().providerID
    }

    /**
     * El id tal y como lo entiende el CLI: sin prefijo de proveedor.
     *
     * MEDIDO 2026-10-03 en las prefs del movil: hay sesiones guardadas como
     * `opencode/muse-spark-1.3-contributor-free`. Ese prefijo lo puso una version vieja al
     * guardar, y con el la lista (que trae ids cortos) nunca coincide: el chip dice
     * "No disponible" con la lista cargada. Se corta por la ultima barra, venga de donde venga.
     */
    fun normalizarIdModelo(ref: String?): String =
        ref?.trim()?.substringAfterLast("/")?.trim().orEmpty()

    /**
     * El proveedor que trae un id con prefijo (`opencode/x` -> `opencode`), o null si no hay.
     * Es la pista que el propio valor da para no tener que adivinarlo en el catalogo.
     */
    fun proveedorDeRef(ref: String?): String? {
        val limpio = ref?.trim().orEmpty()
        if (!limpio.contains("/")) return null
        return limpio.substringBeforeLast("/").trim().takeIf { it.isNotBlank() }
    }

    /**
     * El variant con el que fijar un modelo: `max` si el catalogo lo ofrece, null si no.
     *
     * Decision del usuario 2026-10-03 ("5. max"). MEDIDO en el catalogo vivo: tanto
     * `space-bunny-free` como `muse-spark-1.3-contributor-free` ofrecen `max` entre sus
     * variants. Si un modelo no lo ofrece, se manda sin variant y decide el servidor en
     * vez de mandar un variant que no existe.
     */
    fun resolveVariantFor(modelId: String?, catalogo: List<OpenCodeNativeModel>): String? {
        val id = normalizarIdModelo(modelId)
        if (id.isEmpty()) return null
        val entrada = catalogo.firstOrNull { (it.id ?: it.modelID) == id } ?: return null
        val ids = entrada.variants.orEmpty().mapNotNull { it.id }
        return if (ids.contains("max")) "max" else null
    }

    /**
     * El modelo que un agente trae definido (`GET /api/agent` -> `model`), o null si el
     * agente delega en el de la sesion. MEDIDO 2026-10-03: solo orchestrator lo tiene
     * (muse-spark-1.3-contributor-free); Build y Plan lo dejan en null.
     */
    fun modeloDelAgente(nombre: String?, agentes: List<OpenCodeNativeAgent>): OpenCodeModelRef? {
        val n = nombre?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return agentes.firstOrNull { it.id == n || it.name == n }?.model
    }
}
