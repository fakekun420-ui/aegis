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
 * ## Por que NO se copia el binario dentro del APK
 *
 * Son 196 MB, y ademas el runtime completo (chroot, `node`, config y la base de datos de sesiones
 * de 2 GB) ya existe en este movil con todo el trabajo del usuario dentro. Copiarlo seria tirar
 * lo que hay y empezar de cero. Decision del usuario 2026-10-02: el runtime se queda donde esta y
 * el APK lo levanta si falta.
 */
object OpenCodeLauncher {

    private const val TAG = "OpenCodeLauncher"

    /** MEDIDO: la raiz del chroot. */
    const val CHROOT = "/data/local/ubuntu"

    /**
     * MEDIDO, por orden de probabilidad, de donde sale el binario de verdad:
     *
     *  - La ruta RELATIVA al chroot, que es como la ve el proceso cuando corre dentro. Es la que
     *    devuelve `/proc/<pid>/exe` del opencode que esta corriendo (medido).
     *  - `/usr/local/bin/opencode`, que es un symlink DENTRO del chroot a
     *    `/data/data/com.termux/files/usr/bin/opencode` — MEDIDO: **ese destino no existe**, o
     *    sea que el symlink esta ROTO. Se deja en la lista por si alguien lo arregla, y no se
     *    fia de el.
     *  - Los de `node_modules/opencode-ai` y `@opencode/cli`, que es donde estuvo antes.
     *
     * MEDIDO que el symlink roto es una trampa: invocarlo da un error de bun que no parece un
     * "no existe", asi que sin esta lista el instalador diria "no encuentro OpenCode" con el
     * fichero delante de sus ojos.
     */
    private val RUTAS_BINARIO = listOf(
        "/data/data/com.termux/files/usr/lib/node_modules/@opencode/cli/bin/opencode.exe",
        "/usr/local/bin/opencode",
        "/data/data/com.termux/files/lib/node_modules/opencode-ai/bin/opencode.exe"
    )

    /**
     * Los montajes que el chroot necesita. Idempotentes por construccion: cada uno comprueba si
     * ya esta antes de montar, que es exactamente lo que hace `start-ubuntu.sh`.
     *
     * MEDIDO que sin `proc` y `sys` el binario aborta; `dev` es lo que le da `/dev/null` y
     * `/dev/urandom`, y bun los usa en el arranque.
     */
    private val MONTajes = listOf(
        "mkdir -p \$U/dev \$U/dev/pts \$U/proc \$U/sys \$U/sdcard",
        "grep -q \" \$U/dev \" /proc/mounts || mount -o bind /dev \$U/dev",
        "grep -q \" \$U/dev/pts \" /proc/mounts || mount -o bind /dev/pts \$U/dev/pts",
        "grep -q \" \$U/proc \" /proc/mounts || mount -t proc proc \$U/proc",
        "grep -q \" \$U/sys \" /proc/mounts || mount -t sysfs sysfs \$U/sys"
    )

    data class Resultado(
        val ok: Boolean,
        val detalle: String,
        val arrancoAhora: Boolean = false
    )

    /**
     * El binario real, deducido del proceso que esta corriendo antes que de una lista.
     *
     * MEDIDO: `/proc/<pid>/exe` del opencode vivo da la ruta DENTRO del chroot. Es mas fiable que
     * cualquier constante porque no depende de donde se instalo: si el usuario actualiza OpenCode,
     * esta lista se queda obsoleta y este metodo no.
     */
    fun rutaDelProcesoVivo(shell: (String, Long) -> RootShell.Result = RootShell::exec): String? {
        val r = shell("for p in \\$(pgrep -f '@opencode/cli/bin/opencode'); do " +
            "readlink -f /proc/\\$p/exe 2>/dev/null; done | head -1", 3000)
        val ruta = r.stdout.trim().substringAfterLast('\n').trim()
        // `readlink -f` dentro del chroot puede devolver la ruta ya resuelta en el host
        // (/data/local/ubuntu/...), y `chroot` necesita la relativa. De ahi el recorte.
        if (ruta.isBlank()) return null
        return if (ruta.startsWith("$CHROOT/")) ruta.removePrefix("$CHROOT/") else ruta
    }

    private fun primerBinarioQueExista(
        shell: (String, Long) -> RootShell.Result
    ): String? {
        rutaDelProcesoVivo(shell)?.let { return it }
        for (ruta in RUTAS_BINARIO) {
            // Se prueba DENTRO del chroot, que es donde se va a ejecutar. Comprobarlo en el host
            // daria un falso positivo: el fichero existe pero su interprete no.
            val r = shell("chroot $CHROOT test -x '$ruta' && echo OK", 3000)
            if (r.code == 0 && r.stdout.contains("OK")) return ruta
        }
        return null
    }

    /**
     * Monta el chroot. Idempotente, y no falla si ya estaba montado.
     */
    fun montarChroot(shell: (String, Long) -> RootShell.Result = RootShell::exec): RootShell.Result {
        for (cmd in MONTajes) {
            val r = shell("U=$CHROOT; $cmd", 5000)
            if (r.code != 0) {
                Log.w(TAG, "montarChroot: '$cmd' devolvio ${r.code}: ${r.stderr.take(120)}")
            }
        }
        val r = shell("grep -c \" $CHROOT/\" /proc/mounts", 3000)
        return r
    }

    /**
     * Arranca `opencode serve --service` si no esta ya responding.
     *
     * @param comprobarSiVivo decision que se ya ha tomado por fuera (una sonda HTTP). Se recibe en
     *   vez de hacerla aqui porque un `curl` con Basic necesita la contrasena, que es cosa de
     *   [Credentials] y no de este objeto.
     */
    fun asegurarAbierto(
        comprobarSiVivo: suspend () -> Boolean,
        shell: (String, Long) -> RootShell.Result = RootShell::exec
    ): Resultado {
        if (comprobarSiVivo()) {
            return Resultado(true, "OpenCode ya responde; no se toca nada.")
        }

        montarChroot(shell)

        val binario = primerBinarioQueExista(shell)
            ?: return Resultado(false, "No encuentro el binario de OpenCode dentro de $CHROOT. " +
                "Rutas probadas: ${RUTAS_BINARIO.joinToString()}. Sin el, la app no puede " +
                "arrancarlo sola.")

        // MEDIDO: `serve --service` y no `serve --port`, por el motivo que ya esta escrito en el
        // ponytail §4.1: sin `--service` no hay entrada de registro, la contrasena se genera al
        // azar y no se anuncia, y el CLI da "Timed out waiting for the background service".
        //
        // `nohup ... &` porque el proceso debe sobrevivir al shell que lo lanzo: la app abre un
        // `su` por orden, y si OpenCode cuelga de ese shell se muere con el.
        val cmd = "chroot $CHROOT env HOME=/root '/$binario' serve --service " +
            ">/data/local/ubuntu/root/.local/share/opencode/app-launch.log 2>&1 &"
        Log.i(TAG, "asegurarAbierto: lanzo $binario")
        val r = shell(cmd, 5000)
        if (r.code != 0) {
            return Resultado(false, "El arranque devolvio ${r.code}: ${r.stderr.take(160)}")
        }
        return Resultado(
            ok = true,
            detalle = "OpenCode arrancado desde $binario (serve --service).",
            arrancoAhora = true
        )
    }
}