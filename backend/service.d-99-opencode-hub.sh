#!/system/bin/sh
# Magisk service.d — arranca hub voice + opencode al boot, con keepalive
# Instalación: nsenter -t 1 -m -- cp /sdcard/projects/Aegis/backend/service.d-99-opencode-hub.sh /data/adb/service.d/99-opencode-hub.sh && chmod 755 /data/adb/service.d/99-opencode-hub.sh
# Tiempo boot: ~20-30s tras montar /sdcard

# Espera a que /sdcard esté montada y node/opencode estén disponibles
for i in 1 2 3 4 5 6; do
  if [ -f "/sdcard/projects/Aegis/backend/server.js" ] && [ -f "/sdcard/projects/Aegis/backend/keepalive.sh" ]; then break; fi
  sleep 5
done

# Doze whitelist temprano (evita que system mate companion al boot)
dumpsys deviceidle whitelist +com.aegis.hub 2>/dev/null || true
cmd deviceidle whitelist +com.aegis.hub 2>/dev/null || true

# Lanza keepalive (system init 4359 no ve /usr/bin/node — host 5294/5522 sí).
# service.d corre en init 4359, así que busca host pid con node visible y nsenter ahí.
# Fix: usa -m (sin -r, host mount 5294).

# Espera a que aparezca un mount ns con /usr/bin/node visible.
# ANTES: 5 intentos x 2s = 10s. Insuficiente en boot frío: si Termux no está abierto
# y la app Aegis tarda en arrancar, la ventana se agotaba y el Hub NUNCA levantaba.
# AHORA: 40 intentos x 5s = ~200s. Convierte "nunca" en "en cuanto el ns exista".
# Bucle con aritmética POSIX (sin `seq`, para no depender de toybox en /system/bin/sh).
HOST_PID=""
_try=0
while [ "$_try" -lt 40 ]; do
  _try=$((_try + 1))
  for pid in $(ls /proc 2>/dev/null | grep -E '^[0-9]+$'); do
    if [ -x "/proc/$pid/root/usr/bin/node" ] 2>/dev/null && grep -q "opencode" "/proc/$pid/cmdline" 2>/dev/null; then HOST_PID="$pid"; break; fi
  done
  [ -n "$HOST_PID" ] && break
  for pid in $(ls /proc 2>/dev/null | grep -E '^[0-9]+$'); do
    if [ -x "/proc/$pid/root/usr/bin/node" ] 2>/dev/null; then HOST_PID="$pid"; break; fi
  done
  [ -n "$HOST_PID" ] && break
  sleep 5
done

if [ -n "$HOST_PID" ]; then
  nsenter -t "$HOST_PID" -m -- sh /sdcard/projects/Aegis/backend/keepalive.sh > /sdcard/projects/Aegis/backend/keepalive.log 2>&1 &
  # Marker: confirma que el boot hook corrió y por qué vía arrancó.
  echo "[99-opencode-hub] launched keepalive pid $! at $(date) via nsenter -t $HOST_PID -m (intento $_try)" >> /sdcard/projects/Aegis/backend/keepalive.log
else
  # Sin ns con node NO hay arranque posible: keepalive.sh aborta con su guarda FATAL
  # ("NODE_BIN not executable in this mount ns") y NO reintenta — es un diseño
  # anti-bucle deliberado. Antes este fallback era inútil y además fallaba en
  # silencio; ahora deja constancia explícita para poder diagnosticar el boot.
  nohup sh /sdcard/projects/Aegis/backend/keepalive.sh > /sdcard/projects/Aegis/backend/keepalive.log 2>&1 &
  echo "[99-opencode-hub] WARNING at $(date): tras ~200s ningun mount ns expone /usr/bin/node." >> /sdcard/projects/Aegis/backend/keepalive.log
  echo "[99-opencode-hub] WARNING: keepalive NO pudo arrancar -> el Hub NO voltou solo. Abrir la app Aegis o Termux para que lo lance. Sin node no hay fallback posible." >> /sdcard/projects/Aegis/backend/keepalive.log
fi
