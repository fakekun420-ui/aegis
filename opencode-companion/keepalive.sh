#!/system/bin/sh
# keepalive loop para hub 8765 + opencode 4096 — resiste simple_lmk / oom y caídas por proxy
# uso: sh keepalive.sh &  (se auto-backgroundiza)  o  su -c "sh /sdcard/projects/opencode-companion/keepalive.sh"
# IMPORTANTE: este script debe correr en el mismo mount NS que opencode (host mnt 5294/5522), system init 4359 no ve /usr/bin/node
HUB_DIR="/sdcard/projects/opencode-companion"
HUB_PORT="8765"
OC_PORT="4096"
OC_HOST="127.0.0.1"
INTERVAL=10
LOG="$HUB_DIR/keepalive.log"
# NODE_BIN override: app mount ns (com.opencode.companion) cannot see /usr/bin/node
# (private ns 4026535508, node lives only in termux ns 4026535555 and is NOT
# bind-shared). su shell DOES see node, so MainActivity now passes the resolved
# absolute path it verified (NODE_BIN="$NODE_BIN" ./keepalive.sh keeps working
# when unset via the default below). Env override wins, default stays.
NODE_BIN="${NODE_BIN:-/usr/bin/node}"
OPENCODE_BIN="/data/data/com.termux/files/usr/lib/node_modules/opencode-ai/bin/opencode.exe"
SERVER_JS="$HUB_DIR/server.js"

# auto-detect host mount donde vive /usr/bin/node (host 5294/5522, system 4359 no lo tiene)
if [ ! -x "$NODE_BIN" ]; then
  HOST_PID=""
  for _pid in $(ls /proc 2>/dev/null | grep -E '^[0-9]+$' | head -n 400); do
    if [ -x "/proc/$_pid/root$NODE_BIN" ] 2>/dev/null && grep -q "opencode" "/proc/$_pid/cmdline" 2>/dev/null; then HOST_PID="$_pid"; break; fi
  done
  if [ -z "$HOST_PID" ]; then
    for _pid in $(ls /proc 2>/dev/null | grep -E '^[0-9]+$' | head -n 400); do
      if [ -x "/proc/$_pid/root$NODE_BIN" ] 2>/dev/null; then HOST_PID="$_pid"; break; fi
    done
  fi
  if [ -n "$HOST_PID" ]; then
    echo "[keepalive] re-ejecutando en host mount via nsenter $HOST_PID -m (node no visible aquí)" >> "$LOG" 2>&1
    nsenter -t "$HOST_PID" -m -- sh "$0" "$@" >> "$LOG" 2>&1 &
    echo "[keepalive] relanzado en host pid $! via nsenter $HOST_PID -m" | tee -a "$LOG"
    exit 0
  fi
  echo "[keepalive] no hay host con $NODE_BIN aún — esperando host mount (reintenta 10x 3s)" >> "$LOG" 2>&1
  for _w in 1 2 3 4 5 6 7 8 9 10; do
    sleep 3
    if [ -x "$NODE_BIN" ]; then break; fi
    # re-scan por si apareció un host pid con node
    for _pid in $(ls /proc 2>/dev/null | grep -E '^[0-9]+$' | head -n 400); do
      if [ -x "/proc/$_pid/root$NODE_BIN" ] 2>/dev/null; then
        echo "[keepalive] host con $NODE_BIN apareció pid $_pid — re-ejecutando" >> "$LOG" 2>&1
        nsenter -t "$_pid" -m -- sh "$0" "$@" >> "$LOG" 2>&1 &
        echo "[keepalive] relanzado en host pid $! via nsenter $_pid -m" | tee -a "$LOG"
        exit 0
      fi
    done
  done
  if [ ! -x "$NODE_BIN" ]; then echo "[keepalive] aún sin node tras 30s, abortando hasta próxima invocación" >> "$LOG"; exit 0; fi
fi
# OPENCODE_BIN es ELF, no necesita node; si no existe usa symlink /usr/local/bin/opencode
if [ ! -x "$OPENCODE_BIN" ]; then
  if [ -x "/usr/local/bin/opencode" ]; then OPENCODE_BIN="/usr/local/bin/opencode"
  elif [ -x "/proc/self/root$OPENCODE_BIN" ] 2>/dev/null; then : # ok via proc root
  elif command -v opencode >/dev/null 2>&1; then OPENCODE_BIN="$(command -v opencode)"
  fi
fi

# doble instancia guard (usa /sdcard/projects/opencode-companion/keepalive.lock compartido host+system
LOCK="/sdcard/projects/opencode-companion/keepalive.lock"
if [ -f "$LOCK" ]; then
  OLDPID="$(cat "$LOCK" 2>/dev/null)"
  if [ -n "$OLDPID" ] && kill -0 "$OLDPID" 2>/dev/null; then
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
trap 'echo "[keepalive] trap exit" >> "$LOG"; rm -f "$LOCK"; exit 0' TERM INT

is_up() {
  if command -v curl >/dev/null 2>&1; then
    curl -m 2 -s "http://$OC_HOST:$1/global/health" 2>/dev/null | grep -q healthy
    return $?
  fi
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
  if ! is_up "$OC_PORT"; then
    echo "[$(date +%H:%M:%S)] opencode $OC_HOST:$OC_PORT caído — relanzando" >> "$LOG"
    # SAFE: distinguir TUI interactiva (pts/N) de serve (sin tty) — NUNCA matar TUI ni hub
    # Condición 1: isRealServe — argv[0] basename opencode/opencode.exe + argv[1]=="serve" (no substring "server.js")
    # Evita falso positivo del hub: su cmdline "node .../opencode-companion/server.js" contiene "opencode" y "server.js"
    # con pgrep "opencode.*serve" coincidía por substring "serve" dentro de "server.js". Ahora filtramos estricto.
    # Condición 2: /proc/$pid/stat campo 7 (tty_nr): 0='?' (nohup/daemon)=serve; !=0=pts/N=TUI manual → skip
    # Condición 3: hub node detectado por cmdline que contiene "server.js" → nunca matar como serve
    for _ocpid in $(timeout 5 pgrep -f "opencode.*serve" 2>/dev/null || true); do
      # Filtro estricto: solo opencode serve real, no node server.js del hub
      _cmd=$(tr '\0' ' ' < "/proc/$_ocpid/cmdline" 2>/dev/null)
      case "$_cmd" in *server.js*) echo "  skip hub pid $_ocpid (server.js) — no es opencode serve" >> "$LOG"; continue;; esac
      # Parse argv: debe tener argv[1]=="serve" literal, no solo contener "serve" en path
      if ! tr '\0' '\n' < "/proc/$_ocpid/cmdline" 2>/dev/null | head -n 2 | tail -n 1 | grep -qx "serve"; then
        # fallback para casos node-wrapped opencode: verificar " serve " con espacios
        if ! echo "$_cmd" | grep -q " opencode.* serve "; then
          echo "  skip pid $_ocpid sin argv serve literal (cmd: $(echo "$_cmd" | cut -c1-80))" >> "$LOG"
          continue
        fi
      fi
      _tty=$(awk '{print $7}' "/proc/$_ocpid/stat" 2>/dev/null || echo 1)
      if [ "$_tty" != "0" ]; then
        echo "  skip TUI pid $_ocpid tty=$_tty (pts, interactiva) — no matar" >> "$LOG"
        continue
      fi
      echo "  matando serve stale pid $_ocpid (tty=0, argv serve)" >> "$LOG"
      kill "$_ocpid" 2>/dev/null || true
      for _k in 1 2 3 4 5; do kill -0 "$_ocpid" 2>/dev/null || break; sleep 1; done
      kill -9 "$_ocpid" 2>/dev/null || true
    done
    sleep 1
    # opencode es ELF standalone, no necesita node
    nohup "$OPENCODE_BIN" serve --port "$OC_PORT" --hostname 0.0.0.0 >> "$HUB_DIR/opencode.log" 2>&1 &
    echo "  opencode pid $! lanzado ($OPENCODE_BIN)" >> "$LOG"
    sleep 4
  fi
  if ! hub_up; then
    echo "[$(date +%H:%M:%S)] hub 127.0.0.1:$HUB_PORT caído — relanzando (log tail abajo)" >> "$LOG"
    # FIX pkill hang: pkill -f escanea /proc/*/cmdline y se cuelga 120s por simple_lmk en Android
    # Reemplazo: kill $(timeout 5 pgrep -f "node server.js") — pgrep ligero + guard 5s evita bloqueo
    # Condición: timeout 5 asegura que si /proc está lento, no cuelga el loop; pgrep -f matchea cmdline completa
    _hubpids=$(timeout 5 pgrep -f "node.*opencode-companion/server.js" 2>/dev/null || timeout 5 pgrep -f "node server.js" 2>/dev/null || true)
    if [ -n "$_hubpids" ]; then
      echo "  matando hub stale pids: $_hubpids (timeout 5 pgrep guard)" >> "$LOG"
      kill $_hubpids 2>/dev/null || true
      for _k in 1 2 3 4 5; do _alive=""; for _p in $_hubpids; do kill -0 "$_p" 2>/dev/null && _alive="$_alive $_p"; done; [ -z "$_alive" ] && break; sleep 1; done
      for _p in $_hubpids; do kill -0 "$_p" 2>/dev/null && kill -9 "$_p" 2>/dev/null || true; done
    else
      echo "  no hub pids encontrados (ya caído)" >> "$LOG"
    fi
    sleep 1
    nohup "$NODE_BIN" "$SERVER_JS" --port "$HUB_PORT" --opencode-port "$OC_PORT" >> "$HUB_DIR/hub.log" 2>&1 &
    echo "  hub pid $! lanzado" >> "$LOG"
    sleep 3
    if hub_up; then
      echo "  hub OK tras relanzar" >> "$LOG"
    else
      echo "  hub aún no responde — tail hub.log:" >> "$LOG"
      tail -n 30 "$HUB_DIR/hub.log" 2>/dev/null >> "$LOG" || true
    fi
  fi
  if [ -f "/data/data/com.opencode.companion/files" ] || pm list packages 2>/dev/null | grep -q com.opencode.companion; then
    :
  fi
  sleep "$INTERVAL"
done
