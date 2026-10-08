#!/system/bin/sh
# aegis-serve.sh — lanzador unico de `opencode serve --service` (F6, T-F6.2).
#
# Lo invocan el hook de boot Y `OpenCodeLauncher` (que despliega este mismo
# fichero desde los assets del APK a /data/local/tmp/). La logica vive aqui
# una sola vez: el Kotlin ya no contiene ni montajes ni `chroot ... serve`.
#
# Salidas (una linea, para que el llamador la interprete sin adivinar):
#   YA_HAY:<n>     ya hay <n> procesos serve vivos; no se lanza otro.
#   LANZADO        se lanzo un servidor nuevo.
#   ERROR:<motivo> no se pudo lanzar (sin binario, sin montajes, ...).
#
# MEDIDO 2026-10-08 en el dispositivo:
# - `pgrep -f 'opencode serve --service'` SIN corchete devuelve 3 PIDs (se
#   cuenta a si mismo via el `sh -c` que lo envuelve); CON corchete devuelve 1
#   (el real). Por eso la guarda usa `[o]pencode` (H-11).
# - `flock` del movil es toybox (`flock [-sxun] fd`, solo descriptores): la
#   forma util-linux `flock ARCHIVO COMANDO` NO existe. El cerrojo anti-carrera
#   entre boot y app es un `mkdir` (atomico), con limpieza por antiguedad
#   (> 60 s = huerfano de un intento muerto).
# - `/sdcard` es FUSE con `noexec`: el script debe vivir en /data/local/tmp,
#   nunca en /sdcard.

U=/data/local/ubuntu
CANDIDATOS_BINARIO="/data/data/com.termux/files/usr/lib/node_modules/@opencode/cli/bin/opencode.exe /usr/local/bin/opencode /data/data/com.termux/files/lib/node_modules/opencode-ai/bin/opencode.exe"
CERROJO=/data/local/tmp/aegis-serve.lock
LOG_ARRANQUE=/data/local/ubuntu/root/.local/share/opencode/app-launch.log

limpiar_cerrojo() {
    rmdir "$CERROJO" 2>/dev/null
}

# Cerrojo atomico con caducidad: si existe y es viejo, era de un intento muerto.
if mkdir "$CERROJO" 2>/dev/null; then
    trap limpiar_cerrojo EXIT HUP INT TERM
else
    edad=$(( $(date +%s) - $(stat -c %Y "$CERROJO" 2>/dev/null || echo 0) ))
    if [ "$edad" -gt 60 ]; then
        rm -rf "$CERROJO" 2>/dev/null
        mkdir "$CERROJO" 2>/dev/null || { echo "ERROR:cerrojo-bloqueado"; exit 0; }
        trap limpiar_cerrojo EXIT HUP INT TERM
    else
        echo "ERROR:cerrojo-bloqueado"
        exit 0
    fi
fi

# Guarda anti-duplicado (H-11): con corchete para no contarse a si mismo.
# MEDIDO 2026-10-08: el proceso real es `opencode.exe serve --service` (con
# `.exe`); el patron sin `(.exe)?` NO lo ve (0 PIDs con el servidor vivo) y la
# guarda dejaria pasar un segundo servidor. Verificado: con `(.exe)?` -> 1 PID.
n=$(pgrep -f '[o]pencode(.exe)? serve --service' 2>/dev/null | wc -l)
if [ "$n" -gt 0 ]; then
    echo "YA_HAY:$n"
    exit 0
fi

# Montajes idempotentes del chroot (los mismos de start-ubuntu.sh): cada uno
# comprueba antes de montar, asi que repetir no falla.
mkdir -p "$U/dev" "$U/dev/pts" "$U/proc" "$U/sys" "$U/sdcard" 2>/dev/null
grep -q " $U/dev " /proc/mounts || mount -o bind /dev "$U/dev" 2>/dev/null
grep -q " $U/dev/pts " /proc/mounts || mount -o bind /dev/pts "$U/dev/pts" 2>/dev/null
grep -q " $U/proc " /proc/mounts || mount -t proc proc "$U/proc" 2>/dev/null
grep -q " $U/sys " /proc/mounts || mount -t sysfs sysfs "$U/sys" 2>/dev/null
if grep -q " $U/sdcard " /proc/mounts; then
    :
elif [ -d /sdcard/projects ]; then
    mount -o bind /sdcard "$U/sdcard" 2>/dev/null
fi

# Binario: primero el del proceso vivo (no depende de donde se instalo),
# luego la lista de rutas probadas dentro del chroot (donde se ejecuta).
BINARIO=""
vivo=$(for p in $(pgrep -f '[@]opencode/cli/bin/opencode' 2>/dev/null); do
    readlink -f /proc/"$p"/exe 2>/dev/null
done | grep "^$U/" | head -1)
if [ -n "$vivo" ]; then
    BINARIO=${vivo#"$U/"}
else
    for ruta in $CANDIDATOS_BINARIO; do
        if chroot "$U" /bin/sh -c "test -x \"$ruta\"" 2>/dev/null; then
            BINARIO=$ruta
            break
        fi
    done
fi
if [ -z "$BINARIO" ]; then
    echo "ERROR:sin-binario"
    exit 0
fi
case "$BINARIO" in
    /*) ;;
    *) BINARIO=/"$BINARIO" ;;
esac

# Que el LMK no lo mate a mitad de turno (heredado del boot; explicito aqui
# para que valga igual cuando el padre es la app).
echo -1000 > /proc/self/oom_score_adj 2>/dev/null

if chroot "$U" /bin/sh -c "HOME=/root $BINARIO serve --service" \
    >>"$LOG_ARRANQUE" 2>&1 & then
    echo "LANZADO"
else
    echo "ERROR:lanzamiento-rc-$?"
fi
