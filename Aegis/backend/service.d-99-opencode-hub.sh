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

HOST_PID=""
for _try in 1 2 3 4 5; do
  for pid in $(ls /proc 2>/dev/null | grep -E '^[0-9]+$'); do
    if [ -x "/proc/$pid/root/usr/bin/node" ] 2>/dev/null && grep -q "opencode" "/proc/$pid/cmdline" 2>/dev/null; then HOST_PID="$pid"; break; fi
  done
  [ -n "$HOST_PID" ] && break
  for pid in $(ls /proc 2>/dev/null | grep -E '^[0-9]+$'); do
    if [ -x "/proc/$pid/root/usr/bin/node" ] 2>/dev/null; then HOST_PID="$pid"; break; fi
  done
  [ -n "$HOST_PID" ] && break
  sleep 2
done

if [ -n "$HOST_PID" ]; then
  nsenter -t "$HOST_PID" -m -- sh /sdcard/projects/Aegis/backend/keepalive.sh > /sdcard/projects/Aegis/backend/keepalive.log 2>&1 &
else
  # fallback: lanza directo (keepalive se auto-re-ejecuta en host si detecta que node no existe)
  nohup sh /sdcard/projects/Aegis/backend/keepalive.sh > /sdcard/projects/Aegis/backend/keepalive.log 2>&1 &
fi

# También deja un marker para saber que boot hook corrió
echo "[99-opencode-hub] launched keepalive pid $! at $(date)" >> /sdcard/projects/Aegis/backend/keepalive.log
