package com.aegis.hub.data

import android.content.Context

/**
 * Contexto de aplicación accesible desde los ViewModel.
 *
 * Existe para no meter `Application` en el constructor de `ChatViewModel`: sus factories
 * usan `viewModel(key = ...)` en tres sitios distintos (`AppNavHost` para el draft y
 * para cada `chat_$sid`, más la ruta de voz) y cambiar la firma obligaría a tocar todos
 * y a que cada uno pase el contexto. Se inicializa una vez en `MainActivity.onCreate`
 * con el contexto de APLICACIÓN, que no puede filtrar activities.
 */
object AppContext {
    @Volatile
    private var app: Context? = null

    fun init(ctx: Context) {
        if (app == null) app = ctx.applicationContext
    }

    /** @throws IllegalStateException solo si se usa antes de que arranque MainActivity. */
    fun require(): Context =
        app ?: error("AppContext sin inicializar: MainActivity debe llamar a AppContext.init() en onCreate")
}
