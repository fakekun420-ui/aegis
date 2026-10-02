package com.aegis.hub.data

import android.content.Context
import android.util.Log
import java.io.File

/**
 * MEDIDO 2026-10-02: este objeto es lo que hace que el APK sea AUTONOMO.
 *
 * ## El problema que resuelve
 *
 * El estado de la app vivia en rutas ABSOLUTAS del arbol de codigo:
 *
 *     /sdcard/projects/Aegis/app/state/projects-store.json
 *     /sdcard/projects/Aegis/app/state/bootstrap-state.json
 *
 * MEDIDO que es un arbol de COMPILACION, no de instalacion. Eso funcionaba unicamente porque el
 * repo esta en `/sdcard` de este movil, y por dos razones a la vez: los ficheros existian, y el
 * proceso podia leerlos sin permiso. En un movil donde no estuviera el repo —o si alguien lo
 * borra— la app arrancaria con la lista de proyectos y el instalador VACIOS, sin un solo error:
 * `ProjectsStore` no encuentra su fichero y empieza con un registro en blanco.
 *
 * ## Donde va ahora
 *
 * En el directorio privado de la app (`filesDir`), que Android crea y protege, y que viaja con
 * la instalacion. Un APK instalado en un movil nuevo arranca con su propio estado y no depende
 * de ninguna carpeta de fuera.
 *
 * ## Lo que NO cabe dentro del APK, y por que
 *
 * El runtime de OpenCode —el chroot de Ubuntu, `node` y el binario `opencode`— y el
 * `service.json` con la contraseña (que se regenera en cada arranque de `serve`). Son ~150 MB de
 * rootfs, y ademas en este movil ya existen con las sesiones del usuario dentro. El APK los
 * *instala* si faltan; no puede *contenerlos*. Decision del usuario 2026-10-02.
 *
 * ## Por que hay un plan B y no una excepcion
 *
 * `AppContext.require()` lanza si nadie inicializo el contexto, y un `filesDir` sin contexto es
 * imposible. Lanzar aqui tumbaria la app en cualquier camino que llegara antes que `MainActivity`
 * —y con un mensaje ("AppContext sin inicializar") que no señala el fichero de estado, que es lo
 * que de verdad se busca. Asi que sin contexto se cae a la ruta vieja y se avisa por log: se
 * comporta como hasta ahora, y el log dice por que.
 */
object AppPaths {

    private const val TAG = "AppPaths"

    /** Carpeta del arbol de codigo, en la que vivia el estado hasta 2026-10-02. */
    private const val ESTADO_EN_ARBOL = "/sdcard/projects/Aegis/app/state"

    /**
     * El directorio privado de la app, o `null` si `AppContext` no esta inicializado todavia.
     * No se cachea en un `by lazy`: si el contexto llegara mas tarde, un `lazy` congelaria el
     * `null` para siempre y la app se quedaria en la ruta vieja durante toda la sesion.
     */
    private fun privado(): File? = try {
        AppContext.require().filesDir
    } catch (_: IllegalStateException) {
        null
    }

    /**
     * Un fichero de estado de la app, en el sitio que le corresponde.
     *
     * @param nombre nombre del fichero, no una ruta: asi nadie puede pasar una ruta absoluta y
     *   sacarlo del directorio privado por descuido.
     */
    fun estado(nombre: String): File {
        require(!nombre.contains('/')) { "AppPaths.estado() recibe un NOMBRE, no una ruta: $nombre" }
        val dir = privado()
        if (dir != null) {
            val destino = File(dir, nombre)
            // El directorio puede no existir todavia (primer arranque tras instalar).
            if (!dir.exists()) dir.mkdirs()
            return destino
        }
        Log.w(TAG, "estado($nombre): AppContext sin inicializar, se usa la ruta del arbol " +
            "$ESTADO_EN_ARBOL. Si ves esto, MainActivity no ha llamado a AppContext.init().")
        return File("$ESTADO_EN_ARBOL/$nombre")
    }

    /**
     * El directorio de assets EXTRAIDOS del APK.
     *
     * MEDIDO 2026-10-02: `stage-node.sh` se invocaba por su ruta de compilacion,
     * `sh /sdcard/projects/Aegis/app/app/src/main/assets/stage-node.sh`. En el movil los assets
     * estan en `/data/app/~~<hash>/com.aegis.hub-*/assets/`, y esa ruta no existe: el script
     * fallaba con "No such file or directory" sin que nadie lo supiera, porque el paso de node
     * del instalador tenria un plan B y ese plan B tambien fallaba.
     *
     * Un asset NO se puede ejecutar en sitio: hay que sacarlo a disco. De ahi este directorio.
     */
    fun assetsExtraidos(): File {
        val dir = File(privado() ?: ESTADO_EN_ARBOL, "assets")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Copia un asset del APK a disco y devuelve su ruta, o `null` si no existe.
     *
     * @throws IllegalStateException si el asset no esta en el APK. Es deliberado: un `null`
     *   convertiria un asset que se ha olvidado meter en `assets/` en un "el script fallo, sigamos",
     *   que es exactamente como se manifesto el defecto de `stage-node.sh`.
     */
    fun extraer(nombre: String): String {
        val destino = File(assetsExtraidos(), nombre)
        val ctx = try {
            AppContext.require()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "extraer($nombre): AppContext sin inicializar. No se puede sacar el asset " +
                "del APK sin contexto.", e)
            throw e
        }
        ctx.assets.open(nombre).use { entrada ->
            destino.outputStream().use { salida -> entrada.copyTo(salida) }
        }
        // Un asset shell sin permiso de ejecucion no se puede correr con `sh` tampoco en algunos
        // casos del emulador de Android, y el permiso se pierde al copiar.
        destino.setExecutable(true, false)
        Log.i(TAG, "extraer($nombre) -> ${destino.absolutePath} (${destino.length()} b)")
        return destino.absolutePath
    }
}
