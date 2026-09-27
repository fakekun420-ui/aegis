#!/system/bin/sh
# keepalive loop para hub 8765 + opencode 49374 — resiste simple_lmk / oom y caídas por proxy
# uso: sh keepalive.sh &  (se auto-backgroundiza)  o  su -c "sh /sdcard/projects/Aegis/backend/keepalive.sh"
# IMPORTANTE: este script debe correr en el mismo mount NS que opencode (host mnt 5294/5522), system init 4359 no ve /usr/bin/node
HUB_DIR="/sdcard/projects/Aegis/backend"
HUB_PORT="8765"
OC_PORT="49374"   # unificado con el servicio que usa el CLI (antes 4096: dos servidores distintos, el CLI no veia los turnos en curso)
OC_HOST="127.0.0.1"
INTERVAL=10
LOG="$HUB_DIR/keepalive.log"
# NODE_BIN override: app mount ns (com.aegis.hub) cannot see /usr/bin/node
# (private ns 4026535508, node lives only in termux ns 4026535555 and is NOT
# bind-shared). su shell DOES see node, so MainActivity now passes the resolved
# absolute path it verified (NODE_BIN="$NODE_BIN" ./keepalive.sh keeps working
# when unset via the default below). Env override wins, default stays.
NODE_BIN="${NODE_BIN:-/usr/bin/node}"
OPENCODE_BIN="/data/data/com.termux/files/usr/lib/node_modules/opencode-ai/bin/opencode.exe"
SERVER_JS="$HUB_DIR/server.js"

# NODE_BIN contract: caller MUST export NODE_BIN pointing at a node binary visible
# from THIS mount ns (MainActivity resolves it inside its su shell and passes it
# in env). No nsenter pid-guessing here: /proc/PID/root resolves in the SCANNING
# ns, so scans from a node-less ns (app 4026535508) can only ever find adb
# fork-server and nsenter into another node-less ns -> infinite relaunch loop.
# Fail fast with a clear log line instead of looping forever.
if [ ! -x "$NODE_BIN" ]; then
  echo "[keepalive] FATAL: NODE_BIN=$NODE_BIN not executable in this mount ns ($(readlink /proc/self/ns/mnt 2>/dev/null)) — caller must export NODE_BIN (see MainActivity keepalive nodeBin). Aborting; NOT looping." | tee -a "$LOG"
  exit 3
fi
# OPENCODE_BIN es ELF, no necesita node; si no existe usa symlink /usr/local/bin/opencode
if [ ! -x "$OPENCODE_BIN" ]; then
  if [ -x "/usr/local/bin/opencode" ]; then OPENCODE_BIN="/usr/local/bin/opencode"
  elif [ -x "/proc/self/root$OPENCODE_BIN" ] 2>/dev/null; then : # ok via proc root
  elif command -v opencode >/dev/null 2>&1; then OPENCODE_BIN="$(command -v opencode)"
  fi
fi

# backgroundiza si no está en bg
case "$1" in --no-daemon) shift;; *)
  nohup sh "$0" --no-daemon "$@" >> "$LOG" 2>&1 &
  echo "[keepalive] lanzado pid $! log $LOG — hub http://127.0.0.1:$HUB_PORT proxy $OC_HOST:$OC_PORT"
  exit 0
  ;;
esac

# doble instancia guard (usa /sdcard/projects/Aegis/backend/keepalive.lock compartido host+system)
LOCK="/sdcard/projects/Aegis/backend/keepalive.lock"
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

echo "[keepalive] loop iniciado $(date) pid $$ — intervalo ${INTERVAL}s" | tee -a "$HUB_DIR/keepalive.log"
trap '' HUP
trap 'echo "[keepalive] trap exit" >> "$LOG"; rm -f "$LOCK"; exit 0' TERM INT

# ¿Hay alguien escuchando en este puerto? Ground truth de "¿puedo lanzar otro?".
# /dev/tcp NO existe en dash (el shebang cae a `sh` = dash; comprobado: da NO),
# así que el fallback antiguo era código muerto. Se lee /proc/net/tcp: st=0A es
# LISTEN y el puerto va en HEX en mayusculas (49374 -> C0DE).
port_listening() {
  _plhex=$(printf '%04X' "$1" 2>/dev/null)
  [ -n "$_plhex" ] || return 1
  awk -v w="$_plhex" 'NR>1 && $4=="0A" {split($2,a,":"); if (a[2]==w) f=1} END{exit !f}' /proc/net/tcp 2>/dev/null
}

is_up() {
  # A-8: sonda = GET / con -f y -o /dev/null + %{http_code} == 200.
  # Por que cambia (medido 2026-09-27: 119 eventos "caido" en un dia, 270 bind fallidos):
  #  - `serve` v2 NO expone /api/* sin auth: /api/info y /api/health devuelven 401.
  #    Por eso la sonda va contra la SPA (/) y NO se puede copiar el /api/health
  #    del hub: daria 401 eterno = "caido" perpetuo.
  #  - Antes: `curl -m 2 -s / | grep -qi opencode` -> sin -f y greedy por el cuerpo
  #    entero. Con un SOLO disparo, cualquier pausa >2s reiniciaba un servidor de
  #    ~480MB que abre un SQLite de 2.1GB y levanta 6 venvs python (932MB).
  #  - Ahora: -f falla en 4xx/5xx, -o /dev/null evita traer el cuerpo y -m 5 da
  #    margen. Coste real medido: 6147 b en ~4 ms, 30/30 sondas OK.
  if command -v curl >/dev/null 2>&1; then
    [ "$(curl -m 5 -s -f -o /dev/null -w '%{http_code}' "http://$OC_HOST:$1/" 2>/dev/null)" = "200" ]
    return $?
  fi
  port_listening "$1"
}
hub_up() {
  # A-3: sonda = GET /api/health (EXENTO de X-Aegis-Token y ligero, ver buildHealthData).
  # Antes usaba /api/status SIN token -> el middleware A-1 devolvía 403 -> el loop veía
  # "hub caído" cada 10s y mataba/relanzaba el hub en bucle. -f hace fallar la sonda
  # en 4xx/5xx; el grepeo de "server":"running" confirma el shape del contrato.
  if command -v curl >/dev/null 2>&1; then
    curl -m 2 -s -f "http://127.0.0.1:$HUB_PORT/api/health" 2>/dev/null | grep -q '"server":"running"'
    return $?
  fi
  (echo > "/dev/tcp/127.0.0.1/$HUB_PORT") 2>/dev/null && return 0 || return 1
}

while true; do
  _oc_restart=""
  if ! is_up "$OC_PORT"; then
    # A-8: backoff ANTES de decidir, igual que el hub (A-7). opencode era el UNICO
    # servicio con sonda de un solo disparo: por eso generaba mas reinicios y mas
    # carga que el hub (cada relanzamiento abre el DB de 2.1GB + 6 venvs python).
    echo "[$(date +%H:%M:%S)] opencode $OC_HOST:$OC_PORT sonda KO (1/2) — backoff 6s antes de decidir reinicio" >> "$LOG"
    sleep 6
    if is_up "$OC_PORT"; then
      echo "[$(date +%H:%M:%S)] opencode $OC_HOST:$OC_PORT OK tras backoff — blip descartado, SIN reiniciar" >> "$LOG"
    else
      echo "[$(date '+%Y-%m-%d %H:%M:%S')] opencode $OC_HOST:$OC_PORT sonda KO (2/2 seguidas) — reinicio confirmado" >> "$LOG"
      _oc_restart=1
    fi
  fi
  if [ -n "$_oc_restart" ] && port_listening "$OC_PORT"; then
    # A-9: GUARDIA DE PUERTO. 2 sondas HTTP fallan pero el puerto SIGUE escuchando:
    # hay un serve vivo que no contesta (arrancando, saturado o colgado). Lanzar otro
    # NO lo arregla: choca en el bind, muere, y mientras tanto abre el DB de 2.1GB y
    # levanta 6 venvs python (~932MB, 30-60% CPU cada uno) -> eso sube el load -> la
    # siguiente sonda vuelve a fallar. Medido: 270 "already in use" en un dia.
    # NO se mata el daemon `--service` (es el del CLI, condicion 4): matarlo cortaria
    # la sesion del TUI. Se registra y se deja que lo gestione el CLI.
    echo "  puerto $OC_PORT OCUPADO pese a 2 sondas KO — serve vivo que no responde; NO relanzar" >> "$LOG"
    _oc_restart=""
  fi
  if [ -n "$_oc_restart" ]; then
    echo "[$(date +%H:%M:%S)] opencode $OC_HOST:$OC_PORT caído (puerto libre) — relanzando" >> "$LOG"
    # SAFE: distinguir TUI interactiva (pts/N) de serve (sin tty) — NUNCA matar TUI ni hub
    # Condición 1: isRealServe — argv[0] basename opencode/opencode.exe + argv[1]=="serve" (no substring "server.js")
    # Evita falso positivo del hub: su cmdline "node .../opencode-companion/server.js" contiene "opencode" y "server.js"
    # con pgrep "opencode.*serve" coincidía por substring "serve" dentro de "server.js". Ahora filtramos estricto.
    # Condición 2: /proc/$pid/stat campo 7 (tty_nr): 0='?' (nohup/daemon)=serve; !=0=pts/N=TUI manual → skip
    # Condición 3: hub node detectado por cmdline que contiene "server.js" → nunca matar como serve
    # Condición 4: ignorar daemons internos del TUI/CLI con argumento --service
    for _ocpid in $(timeout 5 pgrep -f "opencode.*serve" 2>/dev/null || true); do
      # Filtro estricto: solo opencode serve real, no node server.js del hub ni TUI service daemon
      _cmd=$(tr '\0' ' ' < "/proc/$_ocpid/cmdline" 2>/dev/null)
      case "$_cmd" in *server.js*) echo "  skip hub pid $_ocpid (server.js) — no es opencode serve" >> "$LOG"; continue;; esac
      case "$_cmd" in *--service*) echo "  skip TUI daemon pid $_ocpid (--service) — no matar" >> "$LOG"; continue;; esac
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
    # opencode es ELF standalone, no necesita node — PERO necesita HOME=/root para
    # leer el DB real (/root/.local/share/opencode/opencode.db, 2.1GB, 18 sesiones).
    # Sin HOME, XDG cae a /.local/share (DB fresca 249KB) y TODAS las sesiones
    # vinculadas devuelven "Session not found" (bug: chats vacíos en la app).
    #
    # --service (OBLIGATORIO, no usar --port/--hostname aquí): el puerto 49374 lo
    # toma el servicio gestionado (default de este build = 49374, ver
    # `opencode service get`). Sin --service NO se escribe la entrada en el registro
    # de servicios, y entonces el CLI (`service.ensure`) cree que no hay servidor,
    # intenta spawnar el suyo y choca: "Managed service port 49374 ... already in use"
    # -> "Timed out waiting for the background service to start".
    # Además serve v2 genera una contraseña Basic ALEATORIA en cada arranque y la
    # loguea como "server password ..."; el CLI solo la conoce si el servidor es
    # gestionado (el Hub la scrapea de opencode.log, server.js F6). Con un serve
    # "ajeno", /api/info responde 401 y la sonda de readiness nunca queda verde.
    # El filtro de muerte de más abajo ya ignora los daemons --service (condición 4).
    HOME=/root nohup "$OPENCODE_BIN" serve --service >> "$HUB_DIR/opencode.log" 2>&1 &
    echo "  opencode pid $! lanzado ($OPENCODE_BIN)" >> "$LOG"
    sleep 4
    if is_up "$OC_PORT"; then
      echo "  opencode OK tras relanzar" >> "$LOG"
    else
      echo "  opencode aun no responde (arrancando) — normal; lo recoge el proximo ciclo" >> "$LOG"
    fi
  fi
  # A-7: reintento/backoff ANTES de reiniciar — si la sonda (curl -f | grep '"server":"running"')
  # falla una vez, se espera 4s y se reintenta; sólo 2 fallos seguidos = reinicio real.
  # Evita matar/relanzar el hub por un blip (GC, carga, latencia de 1 ciclo del loop).
  _hub_restart=""
  if ! hub_up; then
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] hub 127.0.0.1:$HUB_PORT sonda KO (1/2) — backoff 4s antes de decidir reinicio" >> "$LOG"
    sleep 4
    if hub_up; then
      echo "[$(date '+%Y-%m-%d %H:%M:%S')] hub 127.0.0.1:$HUB_PORT OK tras backoff — blip descartado, SIN reiniciar" >> "$LOG"
    else
      echo "[$(date '+%Y-%m-%d %H:%M:%S')] hub 127.0.0.1:$HUB_PORT sonda KO (2/2 seguidas) — reinicio confirmado" >> "$LOG"
      _hub_restart=1
    fi
  fi
  if [ -n "$_hub_restart" ]; then
    echo "[$(date +%H:%M:%S)] hub 127.0.0.1:$HUB_PORT caído — relanzando (log tail abajo)" >> "$LOG"
    # FIX pkill hang: pkill -f escanea /proc/*/cmdline y se cuelga 120s por simple_lmk en Android
    # Reemplazo: kill $(timeout 5 pgrep -f "node .../Aegis/backend/server.js") — pgrep ligero + guard 5s evita bloqueo
    # Condición: timeout 5 asegura que si /proc está lento, no cuelga el loop; pgrep -f matchea cmdline completa
    # A-3: el hub vive en /sdcard/projects/Aegis/backend/server.js (el patrón viejo
    # "node.*opencode-companion/server.js" ya no matchea NADA -> pgrep devolvía vacío
    # y el hub stale jamás se mataba). Fallback genérico por si el path cambia.
    _hubpids=$(timeout 5 pgrep -f "node.*Aegis/backend/server.js" 2>/dev/null || timeout 5 pgrep -f "node.*backend/server.js" 2>/dev/null || true)
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
  # A-3: applicationId real = com.aegis.hub (build.gradle.kts) — el chequeo viejo
  # con el applicationId legado nunca matcheaba. El bloque no hace nada (guard neutro).
  if [ -f "/data/data/com.aegis.hub/files" ] || pm list packages 2>/dev/null | grep -q com.aegis.hub; then
    :
  fi
  sleep "$INTERVAL"
done
