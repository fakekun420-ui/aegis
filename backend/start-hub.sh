#!/system/bin/sh
# inicia opencode serve + hub companion — corre en boot o manual
# uso: sh start-hub.sh  |  sh start-hub.sh --port 8765 --opencode-port 4096
set -e
HUB_DIR="/sdcard/projects/Aegis/backend"
HUB_PORT="8765"
OC_PORT="49374"   # puerto gestionado autoritativo (ponytail-global.md §4.5)
for i in 1 2 3 4 5 6; do case "$1" in --port) HUB_PORT="$2"; shift 2;; --opencode-port) OC_PORT="$2"; shift 2;; *) break;; esac; done

# mata previos si existen
# SOLO mata serves "pelados" (--port/--hostname). Tocar uno con --service mataria el
# servicio gestionado entero. ponytail-global.md §4.5 lo llama regresion obligatoria.
pkill -f "opencode serve --port" 2>/dev/null || true
pkill -f "Aegis/backend/server.js" 2>/dev/null || true
sleep 1

echo "[hub] iniciando opencode serve --service (puerto gestionado :$OC_PORT) ..."
# `--service`, NUNCA `--port/--hostname`: sin registro el CLI no ve el servidor y da
# "Timed out waiting for the background service"; ademas genera un password aleatorio
# cada boot y contesta 401 en /api/info. ponytail-global.md §4.5.
nohup opencode serve --service > "$HUB_DIR/opencode.log" 2>&1 &
sleep 3
if ! curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:$OC_PORT/" | grep -qE "200|401"; then
  echo "[hub] WARN opencode no responde aún, continuo de todos modos"
  cat "$HUB_DIR/opencode.log" | tail -n 20 || true
else
  echo "[hub] opencode OK"
fi

echo "[hub] iniciando hub :$HUB_PORT -> opencode :$OC_PORT ..."
nohup node "$HUB_DIR/server.js" --port "$HUB_PORT" --opencode-port "$OC_PORT" > "$HUB_DIR/hub.log" 2>&1 &
sleep 2
cat "$HUB_DIR/hub.log" | tail -n 40 || true
echo ""
echo "[hub] listo:"
echo "  local: http://127.0.0.1:$HUB_PORT  (SOLO accesible desde este dispositivo)"
echo "         El hub hace bind a 127.0.0.1 (loopback, F0 A-1.2): NO escucha en la"
echo "         IP LAN, así que NO hay url de 'red' desde otros equipos. Si necesitas"
echo "         acceso externo, usa ADB port-forward desde el PC con el dispositivo"
echo "         conectado:"
echo "           adb forward tcp:$HUB_PORT tcp:$HUB_PORT"
echo "           y abre http://127.0.0.1:$HUB_PORT en el PC"
echo "         (las rutas /api/* y /opencode/* exigen el header X-Aegis-Token:"
echo "          backend/.aegis_token)"
echo "  log hub:      $HUB_DIR/hub.log"
echo "  log opencode: $HUB_DIR/opencode.log"
echo ""
echo "Abre en Chrome del POCO F3: http://127.0.0.1:$HUB_PORT"
