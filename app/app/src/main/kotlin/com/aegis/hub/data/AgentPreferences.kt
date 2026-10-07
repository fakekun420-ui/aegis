package com.aegis.hub.data

import android.content.Context
import android.content.Context.MODE_PRIVATE

/**
 * Agente elegido por sesion, persistido de verdad.
 *
 * Copia el patron de [ModelPreferences] —y por el MISMO motivo—: el `ChatViewModel` de un
 * chat esta scopeado a la entrada de navegacion, asi que al hacer pop se destruye su
 * `ViewModelStore` y cualquier estado en memoria se pierde con el. Ese era el defecto que
 * reportaba el usuario: "si yo salgo de la sesion y vuelvo a entrar no se vuelva a
 * cambiar a B".
 *
 * La verdad, sin embargo, NO esta aqui: MEDIDO 2026-09-30, `GET /api/session/:id` de
 * OpenCode trae el campo `agent`, y el Hub lo expone en `/api/sessions/:id/agent`. O sea
 * que el agente ya se guardaba en OpenCode y la app sencillamente no lo leia — igual que
 * pasaba con el modelo antes de que existiera su ruta. Este fichero es la copia local,
 * para cuando la sesion todavia no existe (chat nuevo) o el Hub no responde.
 *
 *  - `KEY_AGENT_<sessionId>` -> agente de ESA sesion.
 *  - `KEY_LAST_AGENT` -> ultima eleccion, para los chats NUEVOS.
 */
object AgentPreferences {
    const val PREFS_NAME = "agent_prefs"
    private const val KEY_PREFIX_AGENT = "agent_"
    const val KEY_LAST_AGENT = "last_agent"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    /** Agente guardado para una sesion concreta, o null si nunca se eligio. */
    fun agentFor(ctx: Context, sessionId: String): String? =
        if (sessionId.isBlank()) null
        else prefs(ctx).getString(KEY_PREFIX_AGENT + sessionId, null)

    fun setAgent(ctx: Context, sessionId: String, agent: String) {
        if (agent.isBlank()) return
        val p = prefs(ctx).edit()
        if (sessionId.isNotBlank()) p.putString(KEY_PREFIX_AGENT + sessionId, agent)
        p.putString(KEY_LAST_AGENT, agent)
        p.apply()
    }

    fun lastAgent(ctx: Context): String? = prefs(ctx).getString(KEY_LAST_AGENT, null)

    fun forget(ctx: Context, sessionId: String) {
        if (sessionId.isBlank()) return
        prefs(ctx).edit().remove(KEY_PREFIX_AGENT + sessionId).apply()
    }
}
