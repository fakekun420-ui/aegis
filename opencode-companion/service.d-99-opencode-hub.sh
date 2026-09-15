#!/system/bin/sh
# Magisk service.d — arranca hub voice + opencode al boot, con keepalive
# Instalación: nsenter -t 1 -m -- cp /sdcard/projects/opencode-companion/service.d-99-opencode-hub.sh /data/adb/service.d/99-opencode-hub.sh && chmod 755 /data/adb/service.d/99-opencode-hub.sh
# Tiempo boot: ~20-30s tras montar /sdcard

# Espera a que /sdcard esté montada y node/opencode estén disponibles
for i in 1 2 3 4 5 6; do
  if [ -f "/sdcard/projects/opencode-companion/server.js" ] && [ -f "/sdcard/projects/opencode-companion/keepalive.sh" ]; then break; fi
  sleep 5
done

# Doze whitelist temprano (evita que system mate companion al boot)
dumpsys deviceidle whitelist +com.opencode.companion 2>/dev/null || true
cmd deviceidle whitelist +com.opencode.companion 2>/dev/null || true

# Lanza keepalive loop (este script es el padre del keepalive, no bloquea boot)
nohup sh /sdcard/projects/opencode-companion/keepalive.sh > /sdcard/projects/opencode-companion/keepalive.log 2>&1 &

# También deja un marker para saber que boot hook corrió
echo "[99-opencode-hub] launched keepalive pid $! at $(date)" >> /sdcard/projects/opencode-companion/keepalive.log
