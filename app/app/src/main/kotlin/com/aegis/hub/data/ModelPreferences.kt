package com.aegis.hub.data

import android.content.Context
import android.content.Context.MODE_PRIVATE

/**
 * Modelo elegido por sesión, persistido de verdad.
 *
 * Por qué existe esto y por qué NO vale un mapa en memoria: el `ChatViewModel` de un
 * chat está scopeado a la entrada de navegación (`viewModel(key = "chat_$sid")` dentro
 * de `composable(...)` en `AppNavHost.kt`). Al hacer pop, el `NavBackStackEntry` se
 * destruye, se limpia su `ViewModelStore` y se dispara `onCleared()`. Un
 * `mutableMapOf` dentro del ViewModel muere con él, así que no recuerda nada: es
 * precisamente el bug ("el modelo se me cambia al salir y volver a entrar").
 *
 * Se replica el patrón de preferencias por SharedPreferences que usaba `VoicePreferences`
 * (retirado con el modo de voz el 2026-10-01),Preferences) en vez de inventar
 * otro mecanismo, porque es lo único que ya usa la app para preferencias.
 *
 * Modelo de datos:
 *  - `KEY_MODEL_<sessionId>` -> modelo de ESA sesión.
 *  - `KEY_LAST_MODEL` -> última elección, usada para los chats NUEVOS (sessionId en
 *    blanco), que es el caso donde no hay registro por sesión todavía.
 */
object ModelPreferences {
    const val PREFS_NAME = "model_prefs"
    private const val KEY_PREFIX_MODEL = "model_"
    const val KEY_LAST_MODEL = "last_model"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    /** Modelo guardada para una sesión concreta, o null si nunca se eligió. */
    fun modelFor(ctx: Context, sessionId: String): String? =
        if (sessionId.isBlank()) null
        else prefs(ctx).getString(KEY_PREFIX_MODEL + sessionId, null)

    /**
     * Guarda el modelo de una sesión y actualiza la "última elección".
     *
     * Con `sessionId` en blanco solo se actualiza la última elección, que es lo que
     * usan los chats nuevos.
     */
    fun setModel(ctx: Context, sessionId: String, modelId: String) {
        if (modelId.isBlank()) return
        val p = prefs(ctx).edit()
        if (sessionId.isNotBlank()) p.putString(KEY_PREFIX_MODEL + sessionId, modelId)
        p.putString(KEY_LAST_MODEL, modelId)
        p.apply()
    }

    /** Última elección global, para cuando no hay sesión todavía. */
    fun lastModel(ctx: Context): String? = prefs(ctx).getString(KEY_LAST_MODEL, null)

    /**
     * Corta el prefijo `proveedor/` de un id guardado (`opencode/x` -> `x`).
     *
     * MEDIDO 2026-10-03 en el movil: hay prefs guardadas CON prefijo por una version vieja,
     * y con el la lista (ids cortos) nunca coincide. Se normaliza al leer y al migrar, nunca
     * se escribe un id con prefijo.
     */
    fun normalizar(ref: String?): String =
        ref?.trim()?.substringAfterLast("/")?.trim().orEmpty()

    /**
     * Migracion de una sola pasada: reescribe sin prefijo todo lo guardado con prefijo.
     * Idempotente por construccion (sin prefijo no hay nada que cortar). Se llama al abrir
     * un chat; si ya corrio, no toca nada.
     */
    fun migrarPrefijos(ctx: Context) {
        val p = prefs(ctx)
        val ed = p.edit()
        var toco = false
        for ((k, v) in p.all) {
            if ((k == KEY_LAST_MODEL || k.startsWith(KEY_PREFIX_MODEL)) && v is String) {
                val limpio = normalizar(v)
                if (limpio.isNotEmpty() && limpio != v) {
                    ed.putString(k, limpio)
                    toco = true
                }
            }
        }
        if (toco) ed.apply()
    }

    /** ¿Tiene ya registro esta sesión? Evita preguntar al servidor si no hace falta. */
    fun hasModelFor(ctx: Context, sessionId: String): Boolean =
        sessionId.isNotBlank() && prefs(ctx).contains(KEY_PREFIX_MODEL + sessionId)

    /**
     * Olvida el registro de una sesión. Se usa al borrarla, para que el registro no
     * crezca sin límite y para no resucitar un modelo de un chat que ya no existe.
     */
    fun forget(ctx: Context, sessionId: String) {
        if (sessionId.isBlank()) return
        prefs(ctx).edit().remove(KEY_PREFIX_MODEL + sessionId).apply()
    }
}
