#!/system/bin/sh
# keepalive loop para hub 8765 + opencode 4096 — resiste simple_lmk / oom y caídas por proxy
# uso: sh keepalive.sh &  (se auto-backgroundiza)  o  su -c "sh /sdcard/projects/opencode-companion/keepalive.sh"
HUB_DIR="/sdcard/projects/opencode-companion"
HUB_PORT="8765"
OC_PORT="4096"
OC_HOST="127.0.0.1"
INTERVAL=10
LOG="$HUB_DIR/keepalive.log"

# doble instancia guard (usa /data/adb/tmp que sí existe en crDroid, fallback a /sdcard)
LOCK="/data/adb/tmp/opencode-keepalive.lock"
if [ ! -d "$(dirname "$LOCK")" ]; then LOCK="/sdcard/projects/opencode-companion/keepalive.lock"; fi
if [ -f "$LOCK" ]; then
  OLDPID="$(cat "$LOCK" 2>/dev/null)"
  if [ -n "$OLDPID" ] && kill -0 "$OLDPID" 2>/dev/null; then
    # si el keepalive ya corre pero no es este proceso, salir (no duplicar)
    MYPID="$$"
    if [ "$OLDPID" != "$MYPID" ]; then
      echo "[keepalive] ya corre pid $OLDPID — no lanzo duplicado (yo $MYPID)" | tee -a "$LOG"
      exit 0
    fi
  fi
fi
echo $$ > "$LOCK"

# backgroundiza si no está en bg
case "$1" in --no-daemon) shift;; *)
  nohup sh "$0" --no-daemon "$@" > "$LOG" 2>&1 &
  echo "[keepalive] lanzado pid $! log $LOG — hub http://127.0.0.1:$HUB_PORT proxy $OC_HOST:$OC_PORT"
  exit 0
  ;;
esac

echo "[keepalive] loop iniciado $(date) pid $$ — intervalo ${INTERVAL}s" | tee -a "$HUB_DIR/keepalive.log"
# trap para limpiar lock al salir (pero el loop no debe salir)
trap 'echo "[keepalive] trap exit" >> "$LOG"; rm -f "$LOCK"; exit 0' TERM INT

is_up() {
  # usa busybox curl si existe, si no wget, si no /dev/tcp
  if command -v curl >/dev/null 2>&1; then
    curl -m 2 -s "http://$OC_HOST:$1/global/health" 2>/dev/null | grep -q healthy
    return $?
  fi
  # fallback: intenta conectar tcp
  (echo > "/dev/tcp/$OC_HOST/$1") 2>/dev/null && return 0 || return 1
}
hub_up() {
  if command -v curl >/dev/null 2>&1; then
    curl -m 2 -s "http://127.0.0.1:$HUB_PORT/api/status" 2>/dev/null | grep -q '"hub":"ok"'
    return $?
  fi
  (echo > "/dev/tcp/127.0.0.1/$HUB_PORT") 2>/dev/null && return 0 || return 1
}

while true; do
  # 1) opencode
  if ! is_up "$OC_PORT"; then
    echo "[$(date +%H:%M:%S)] opencode $OC_HOST:$OC_PORT caído — relanzando" >> "$LOG"
    pkill -f "opencode serve.*$OC_PORT" 2>/dev/null || true
    sleep 1
    nohup opencode serve --port "$OC_PORT" --hostname 0.0.0.0 >> "$HUB_DIR/opencode.log" 2>&1 &
    echo "  opencode pid $! lanzado" >> "$LOG"
    sleep 4
  fi
  # 2) hub
  if ! hub_up; then
    echo "[$(date +%H:%M:%S)] hub 127.0.0.1:$HUB_PORT caído — relanzando (log tail abajo)" >> "$LOG"
    pkill -f "opencode-companion/server.js" 2>/dev/null || true
    # también mata node huérfano que pudo quedar con puerto ocupado
    sleep 1
    nohup node "$HUB_DIR/server.js" --port "$HUB_PORT" --opencode-port "$OC_PORT" >> "$HUB_DIR/hub.log" 2>&1 &
    echo "  hub pid $! lanzado" >> "$LOG"
    sleep 3
    if hub_up; then
      echo "  hub OK tras relanzar" >> "$LOG"
    else
      echo "  hub aún no responde — tail hub.log:" >> "$LOG"
      tail -n 30 "$HUB_DIR/hub.log" 2>/dev/null >> "$LOG" || true
    fi
  fi
  # 3) companion bridge (no mata batería — solo verifica, no relanza agresivo porque lo maneja system)
  if [ -f "/data/data/com.opencode.companion/files" ] || pm list packages 2>/dev/null | grep -q com.opencode.companion; then
    :
  fi
  sleep "$INTERVAL"
done
