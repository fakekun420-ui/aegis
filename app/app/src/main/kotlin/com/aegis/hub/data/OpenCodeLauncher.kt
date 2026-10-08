package com.aegis.hub.data

import android.util.Log
import com.aegis.hub.RootShell

/**
 * MEDIDO 2026-10-02: esto es lo que hace que el APK se sostenga solo DESPUES de un reinicio.
 *
 * ## El problema que resuelve
 *
 * Con el Hub eliminado se fue tambien su watchdog, y `keepalive.sh` era lo unico que arrancaba
 * `opencode serve`. Sin el: **tras un reinicio del movil no hay nada que arranque OpenCode**, y la
 * app abre contra un puerto donde no escucha nadie. Eso estaba escrito como nota en `MainActivity`
 * ("no es un descuido, es lo que significa quedarse sin Hub") y aqui se convierte en codigo.
 *
 * ## Lo que NO es el problema (y me costo una medicion equivocada)
 *
 * Mi primera conclusion fue "la app no puede arrancar OpenCode: el binario esta en los datos
 * privados de Termux y no lo ve". **Era falsa**, y el motivo del error es instructivo: medi con
 * `adb shell su`, que da root en el namespace de la SHELL, no en el de la app. `su` da root en el
 * namespace de quien llama, asi que cada namespace tiene su vista.
 *
 * MEDIDO del proceso real:
 *
 *     /proc/8444/exe -> /data/local/ubuntu/data/data/com.termux/files/usr/lib/node_modules/
 *                       @opencode/cli/bin/opencode.exe
 *
 * El prefijo `/data/local/ubuntu/` es la prueba: dentro del chroot el camino es
 * `/data/data/com.termux/...`, y desde el host ese mismo fichero es alcanzable como root. El
 * chroot no es un namespace: es un arbol de ficheros mas una lista de montajes.
 *
 * ## Las tres cosas que hay que hacer, y por que en ese orden
 *
 *  1. **MONTAR el chroot.** MEDIDO: `/proc/mounts` no tenia NI UNA entrada de
 *     `/data/local/ubuntu`, y sin `dev`, `proc` y `sys` montados el binario de bun ABORTA con un
 *     informe de crash. Son los mismos cuatro `mount` idempotentes que hace `start-ubuntu.sh`, y
 *     por eso son idempotentes tambien aqui: `grep -q ... || mount` no falla si ya estan montados.
 *
 *  2. **CHROOT + ruta real.** MEDIDO: ejecutar el binario directamente desde el host da
 *     "No such file or directory" — que NO es que falte el fichero, es que el ELF pide su
 *     interprete dinamico y ese vive dentro del chroot. Con `chroot` y la ruta real:
 *     **`opencode v2.0.14`**.
 *
 *  3. **`serve --service`.** Es el unico paso que NO esta verificado aqui, porque arrancarlo corta
 *     las sesiones con agentes trabajando y §5.2 exige permiso explicito. Lo que si esta medido es
 *     todo lo que hay alrededor: el binario, el interprete, los montajes y el comando.
 *
 * F6 (2026-10-08): el lanzador Kotlin YA NO contiene la cadena de montajes ni el
 * `chroot ... serve --service` — esa logica vive UNA sola vez en
 * `app/app/src/main/assets/aegis-serve.sh`, que esto despliega a
 * `/data/local/tmp/` (con `chmod 755`; `/sdcard` es `noexec`) y ejecuta.
 *
 * MEDIDO 2026-10-08: `/data/adb/service.d/` NO trae hook de Aegis (solo
 * `.zn_cleanup.sh`); el `flock` del movil es toybox (solo descriptores) — el
 * cerrojo del script es `mkdir`. Y el `pgrep` sin corchete se cuenta a si mismo
 * (3 PIDs vs 1 real): la guarda usa `[o]pencode` (H-11).
 */
object OpenCodeLauncher {

    private const val TAG = "OpenCodeLauncher"

    /** MEDIDO: la raiz del chroot. */
    const val CHROOT = "/data/local/ubuntu"

    /** Donde vive el script desplegado (ejecutable; NO en /sdcard por `noexec`). */
    const val RUTA_SCRIPT_DESPLEGADO = "/data/local/tmp/aegis-serve.sh"

    /**
     * MEDIDO, por orden de probabilidad, de donde sale el binario de verdad:
     *
     *  - `/usr/local/bin/opencode`, que es un symlink DENTRO del chroot a
     *    `/data/data/com.termux/files/usr/bin/opencode`. MEDIDO DENTRO del chroot: **funciona**
     *    (control negativo: `test -x /no/existe/opencode` no devuelve OK, o sea que la sonda no
     *    da OK por defecto).
     *  - La ruta RELATIVA al chroot, que es la que devuelve `/proc/<pid>/exe` del opencode que
     *    esta corriendo, recortada del prefijo `/data/local/ubuntu/`.
     *  - La misma ruta en `node_modules/opencode-ai`, que es donde estuvo antes.
     *
     * ## MEDIDO Y CORREGIDO: el symlink NO esta roto, y yo dije que si
     *
     * Escribi que `/usr/local/bin/opencode` era un symlink ROTO porque desde el HOST,
     * `ls /data/data/com.termux/files/usr/bin/opencode` dice "No such file or directory". Es
     * verdad desde ahi, y da igual: **el symlink se resuelve DENTRO del chroot**, donde ese camino
     * si existe. Dentro, `test -x /usr/local/bin/opencode` devuelve OK.
     *
     * Es el mismo error que el del namespace, por segunda vez: **medir una ruta desde donde no se
     * usa**. Un symlink es relativo a quien lo resuelve, y solo lo resuelve quien va a ejecutarlo.
     */
    data class Resultado(
        val ok: Boolean,
        val detalle: String,
        val arrancoAhora: Boolean = false
    )

    /**
     * Lee el script desde los assets del APK. Nulo si no hay contexto (tests JVM);
     * ahi se inyecta `leerScript`.
     */
    private fun leerScriptDeAssets(): String? = try {
        AppContext.require().assets.open("aegis-serve.sh").bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        Log.w(TAG, "leerScriptDeAssets: ${e.message}")
        null
    }

    /**
     * Despliega `aegis-serve.sh` a [RUTA_SCRIPT_DESPLEGADO] y lo ejecuta.
     *
     * El despliegue viaja en base64 en una sola orden (sin heredoc: las comillas
     * del script romperian el `su -c`). Idempotente: solo escribe si el contenido
     * cambio (evita escrituras FUSE en cada arranque).
     */
    suspend fun lanzarViaScript(
        shell: (String, Long) -> RootShell.Result = RootShell::exec,
        leerScript: () -> String? = { leerScriptDeAssets() }
    ): Lanzamiento {
        val texto = leerScript()
            ?: return Lanzamiento.Error("sin-script: no se pudo leer aegis-serve.sh de los assets")
        if (texto.isBlank()) return Lanzamiento.Error("sin-script: aegis-serve.sh vacio")
        val b64 = java.util.Base64.getEncoder().encodeToString(texto.toByteArray(Charsets.UTF_8))
        val despliegue = "B=\$(echo '$b64' | base64 -d | sha256sum | cut -d' ' -f1); " +
            "A=\$(sha256sum $RUTA_SCRIPT_DESPLEGADO 2>/dev/null | cut -d' ' -f1); " +
            "if [ \"\$B\" != \"\$A\" ]; then echo '$b64' | base64 -d > $RUTA_SCRIPT_DESPLEGADO && " +
            "chmod 755 $RUTA_SCRIPT_DESPLEGADO || exit 11; fi; " +
            "/system/bin/sh $RUTA_SCRIPT_DESPLEGADO"
        val r = try {
            shell(despliegue, 30000)
        } catch (e: Exception) {
            return Lanzamiento.Error("sin-shell: ${e.message?.take(120)}")
        }
        val linea = r.stdout.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        return when {
            linea == null -> Lanzamiento.Error("sin-salida (rc=${r.code})")
            linea.startsWith("YA_HAY:") -> Lanzamiento.YaHay
            linea == "LANZADO" -> Lanzamiento.Lanzado
            linea.startsWith("ERROR:") -> Lanzamiento.Error(linea.removePrefix("ERROR:"))
            else -> Lanzamiento.Error("salida-inesperada: ${linea.take(120)}")
        }
    }
}
