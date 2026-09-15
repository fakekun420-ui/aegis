#!/system/bin/sh
# opencode-companion su installer — POCO F3 crDroid15 (ROOT)
# Uso: su -c "sh /sdcard/projects/opencode-companion-apk/install-su.sh [/ruta/al.apk]"
# Tiempo: ~60s (instalación) + 10s whitelist
set -e
APK="${1:-}"
if [ -z "$APK" ]; then
  for p in \
    "/sdcard/Download/app-debug.apk" \
    "/sdcard/Download/app-release.apk" \
    "/sdcard/Download/opencode-companion.apk" \
    "/sdcard/projects/opencode-companion-apk/app/build/outputs/apk/debug/app-debug.apk" \
    "/sdcard/projects/opencode-companion-apk/app/build/outputs/apk/release/app-release-unsigned.apk"; do
    if [ -f "$p" ]; then APK="$p"; break; fi
  done
fi
if [ -z "$APK" ] || [ ! -f "$APK" ]; then
  echo "APK no encontrado. Descarga el artifact del CI (Actions > Build Companion APK > app-debug.apk) a /sdcard/Download/ y re-ejecuta."
  echo "  Buscado en: /sdcard/Download/app-debug.apk y build/outputs/..."
  exit 1
fi
echo "[1/6] Instalando $APK ..."
nsenter -t 1 -m -- pm install -r "$APK" 2>&1 || pm install -r "$APK" 2>&1
echo "  -> pm list | grep companion:"
nsenter -t 1 -m -- pm list packages 2>&1 | grep -i companion || pm list packages 2>&1 | grep -i companion || echo "  (aún no visible, revisa log arriba)"

echo "[2/6] Concediendo permisos runtime ..."
nsenter -t 1 -m -- pm grant com.opencode.companion android.permission.RECORD_AUDIO 2>&1 || true
nsenter -t 1 -m -- pm grant com.opencode.companion android.permission.POST_NOTIFICATIONS 2>&1 || true
nsenter -t 1 -m -- appops set com.opencode.companion RECORD_AUDIO allow 2>&1 || true
nsenter -t 1 -m -- cmd appops set com.opencode.companion RUN_IN_BACKGROUND allow 2>&1 || true
nsenter -t 1 -m -- cmd appops set com.opencode.companion RUN_ANY_IN_BACKGROUND allow 2>&1 || true

echo "[3/6] Activando AccessibilityService ..."
CUR="$(nsenter -t 1 -m -- settings get secure enabled_accessibility_services 2>&1)"
# settings get devuelve "null" si vacío
if echo "$CUR" | grep -q "null"; then CUR=""; fi
TARGET="com.opencode.companion/com.opencode.companion.OpencodeAccessibilityService"
if echo "$CUR" | grep -q "$TARGET"; then
  echo "  Ya activo: $TARGET"
else
  if [ -z "$CUR" ] || [ "$CUR" = "null" ]; then NEW="$TARGET"
  else NEW="$CUR:$TARGET"; fi
  nsenter -t 1 -m -- settings put secure enabled_accessibility_services "$NEW" 2>&1
  nsenter -t 1 -m -- settings put secure accessibility_enabled 1 2>&1
  echo "  Nuevo valor: $(nsenter -t 1 -m -- settings get secure enabled_accessibility_services 2>&1)"
fi

echo "[4/6] Doze/Battery whitelist (evita que el sistema mate el bridge 8766) ..."
nsenter -t 1 -m -- dumpsys deviceidle whitelist +com.opencode.companion 2>&1 | head -n 5 || true
nsenter -t 1 -m -- cmd deviceidle whitelist +com.opencode.companion 2>&1 | head -n 5 || true
nsenter -t 1 -m -- dumpsys deviceidle sys-whitelist +com.opencode.companion 2>&1 | head -n 5 || true
nsenter -t 1 -m -- am set-standby-bucket com.opencode.companion active 2>&1 | head -n 5 || true
nsenter -t 1 -m -- cmd appops set com.opencode.companion SYSTEM_ALERT_WINDOW allow 2>&1 || true
# quit battery optimization explicitly
nsenter -t 1 -m -- dumpsys deviceidle whitelist 2>&1 | grep -i companion || echo "  (whitelist grep vacío — verifica con: dumpsys deviceidle whitelist | grep companion)"

echo "[5/6] Iniciando CompanionService (foreground 8766) ..."
nsenter -t 1 -m -- am start-foreground-service -n com.opencode.companion/.CompanionService 2>&1 | head -n 10 || \
nsenter -t 1 -m -- am startservice -n com.opencode.companion/.CompanionService 2>&1 | head -n 10 || true
sleep 1
nsenter -t 1 -m -- dumpsys activity services 2>&1 | grep -i "opencode.companion" | head -n 5 || true

echo "[6/6] Verificación ..."
echo "  pm list:"; nsenter -t 1 -m -- pm list packages 2>&1 | grep companion || echo "    (no)"
echo "  a11y :"; nsenter -t 1 -m -- settings get secure enabled_accessibility_services 2>&1 | tr ':' '\n' | grep companion || echo "    (no)"
echo "  deviceidle whitelist:"; nsenter -t 1 -m -- dumpsys deviceidle whitelist 2>&1 | grep -i companion | head -n 5 || echo "    (no)"
echo "  bridge 8766:"; curl -m 3 -s http://127.0.0.1:8766/status 2>&1 | head -c 400; echo
echo "  hub 8765:"; curl -m 3 -s http://127.0.0.1:8765/api/status 2>&1 | head -c 300; echo
echo ""
echo "Listo. Abre la app 'Opencode Companion' para ver estado, o http://127.0.0.1:8765 para el hub voz."
echo "Si el bridge 8766 no responde, abre la app manualmente una vez (dispara onCreate del CompanionService)."
