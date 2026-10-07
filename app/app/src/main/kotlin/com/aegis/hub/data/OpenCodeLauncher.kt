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
        "grep -q \" \$U/sys \" /proc/mounts || mount -t sysfs sysfs \$U/sys",
        // MEDIDO 2026-10-03: sin este bind el chroot ve su propia carpeta aislada en vez del
        // almacenamiento real, y OpenCode escribe en el arbol equivocado. Solo se monta si
        // /sdcard esta listo (en el boot temprano aun no existe FUSE); si no esta, se registra
        // y el script de arranque lo reintenta con espera.
        "grep -q \" \$U/sdcard \" /proc/mounts || { [ -d /sdcard/projects ] && mount -o bind /sdcard \$U/sdcard || echo SIN_SDCARD; }"
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
        val r = shell("for p in \$(pgrep -f '@opencode/cli/bin/opencode'); do " +
            "readlink -f /proc/\${p}/exe 2>/dev/null; done | head -1", 3000)
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
            val r = shell("chroot $CHROOT /bin/sh -c 'test -x \"$ruta\" && echo OK'", 3000)
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
        val r = shell("grep -c \"$CHROOT/\" /proc/mounts", 3000)
        return r
    }

    /**
     * La ruta del binario tal y como la necesita `chroot`: con una sola barra inicial.
     *
     * MEDIDO: concatenar `/$ruta` con una ruta ya absoluta daba `//usr/local/bin/opencode`.
     * Funciona —MEDIDO: `//usr/local/bin/opencode --version` y `/usr/local/bin/opencode
     * --version` dan los dos `opencode v2.0.14`, porque Linux resuelve `//` como `/`— pero es una
     * construccion que depende de una regla POSIX marcada como *implementation-defined*, y no
     * hace falta depender de ella.
     */
    private fun rutaEnChroot(ruta: String): String =
        if (ruta.startsWith("/")) ruta else "/$ruta"

    /**
     * Arranca `opencode serve --service` si no esta ya responding.
     *
     * @param comprobarSiVivo decision que se ya ha tomado por fuera (una sonda HTTP). Se recibe en
     *   vez de hacerla aqui porque un `curl` con Basic necesita la contrasena, que es cosa de
     *   [Credentials] y no de este objeto.
     */
    suspend fun asegurarAbierto(
        comprobarSiVivo: suspend () -> Boolean,
        shell: (String, Long) -> RootShell.Result = RootShell::exec
    ): Resultado {
        if (comprobarSiVivo()) {
            return Resultado(true, "OpenCode ya responde; no se toca nada.")
        }

        // MEDIDO 2026-10-03: habia DOS servidores `opencode serve` vivos a la vez (~660 MB cada
        // uno) y el movil se quedaba sin RAM. La sonda HTTP dice "no responde" tambien cuando el
        // servidor esta ARRANCANDO (puerto aun sin ligar), asi que lanzar ahi crea el duplicado.
        // Por eso antes de lanzar se mira si ya hay un PROCESO `serve --service`: si lo hay, no
        // se lanza otro — se informa y quien llama espera a que abra el puerto.
        val yaProceso = shell("pgrep -f 'opencode serve --service' 2>/dev/null", 3000)
        // F7: sin regex (una vez por arranque, pero gratis hacerlo bien).
        val pids = yaProceso.stdout.split(' ', '\t', '\n', '\r').mapNotNull { it.trim().toIntOrNull() }
        if (pids.isNotEmpty()) {
            Log.i(TAG, "asegurarAbierto: hay proceso serve vivo (pids=${pids.joinToString()}), " +
                "pero aun no responde. No se lanza otro.")
            return Resultado(
                ok = false,
                detalle = "Hay un servidor arrancando (pids=${pids.joinToString()}); " +
                    "espera a que abra el puerto en vez de lanzar otro.",
                arrancoAhora = false
            )
        }

        montarChroot(shell)

        val binario = primerBinarioQueExista(shell)
            ?: return Resultado(false, "No encuentro el binario de OpenCode dentro de $CHROOT. " +
                "Rutas probadas: ${RUTAS_BINARIO.joinToString()}. Sin el, la app no puede " +
                "arrancarlo sola.")

        // MEDIDO 2026-10-02: `HOME=/root` va DENTRO del `sh -c`, no con `env`. Escribi
        // `chroot $CHROOT env HOME=/root ...` y falla:
        //
        //     chroot: exec env: No such file or directory
        //
        // MEDIDO tambien que dentro del chroot no existe ni `env` ni `ls`: el `/bin` es minimo.
        // O sea que ahi no se puede lanzar nada que no sea `/bin/sh` o el binario de opencode
        // (que es un ELF estatico). Con `/bin/sh -c` la misma orden da `opencode v2.0.14`, y el
        // control negativo —el mismo comando con un binario inexistente— falla como debe.
        //
        // MEDIDO: `serve --help` lista `--service`, y `service status` responde
        // `http://127.0.0.1:49374` a traves de toda esta cadena.
        // MEDIDO: `serve --service` y no `serve --port`, por el motivo que ya esta escrito en el
        // ponytail §4.1: sin `--service` no hay entrada de registro, la contrasena se genera al
        // azar y no se anuncia, y el CLI da "Timed out waiting for the background service".
        //
        // MEDIDO 2026-10-03: el servidor vivo corre con oom_score_adj=-1000 (heredado del
        // boot), o sea que el LMK de Android no lo mata aunque se abra una app pesada. Se fija
        // explicito en el lanzamiento para que valga igual cuando el padre es la app (con otro
        // adj): un servidor matado a mitad de turno corta todas las sesiones de todos los
        // clientes, incluido el TUI de Termux.
        //
        // `nohup ... &` porque el proceso debe sobrevivir al shell que lo lanzo: la app abre un
        // `su` por orden, y si OpenCode cuelga de ese shell se muere con el.
        val cmd = "chroot $CHROOT /bin/sh -c \"echo -1000 > /proc/self/oom_score_adj; " +
            "HOME=/root ${rutaEnChroot(binario)} serve --service\" " +
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