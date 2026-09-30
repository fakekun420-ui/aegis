#!/usr/bin/env node
// Aegis hub — proxy opencode + device bridge + TTS/STT host
// Node 18+ only builtins. No npm deps.
// Listens 127.0.0.1:8765 (loopback only, exige X-Aegis-Token en /api/*) -> serves public/ + /opencode/* proxy + /api/device/*
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { exec, spawn } from "node:child_process";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";
import {
  OpencodeAdapter,
  ProviderManager,
  fileMutex,
  atomicReadFileSync,
  atomicWriteFileSync,
  normalizeMessage
} from "./providers.js";
// BACKLOG (F0-F2, unificación): loadProjectsStore con define SOLO en
// src/core/storage.js (antes también aquí y en providers.js — firmas idénticas).
import { loadProjectsStore } from "./src/core/storage.js";

import { handleSkillsRoute } from "./src/api/skillsRoutes.js";
import { handleProjectRoutes } from "./src/api/projectRoutes.js";
// A-2: routers antes huérfanos (jobs/agents/workflows/content) + subsistemas del motor
import { handleJobRoutes } from "./src/api/jobRoutes.js";
import { handleAgentRoutes } from "./src/api/agentRoutes.js";
import { handleWorkflowRoutes } from "./src/api/workflowRoutes.js";
import { handleContentRoutes } from "./src/api/contentRoutes.js";
// F1: dominio bootstrap del wizard (estado idempotente + orquestador reanudable)
import { handleBootstrapRoute } from "./src/api/bootstrapRoutes.js";
// F3: checks finales + smoke-test + auth guide del cierre del wizard (/api/setup/*)
import { createSetupHandler } from "./src/api/setupRoutes.js";
import { jobScheduler } from "./src/core/jobScheduler.js";
import { agentPool } from "./src/core/agentPool.js";
import { eventBus, EVENTS } from "./src/core/eventBus.js";
// F4: createLogger + getLogFile (sink rotado backend/logs/aegis.log que lee
// GET /api/system/logs — la fuente deja de ser hub.log, que sigue siendo sólo
// la redirección de stdout que hace keepalive.sh).
import { createLogger, getLogFile } from "./src/core/logger.js";
// A-3: fuente honesta de skills para el contrato HealthData/SkillsResponse (disco real)
import { SkillManager } from "./src/skills/SkillManager.js";

// A-7: logger del hub (createLogger ya se usaba en A-2 para el eventBus). Niveles
// info/warn/error/debug con prefijo [ISO][NIVEL][módulo]. Sin escritura a fichero propia
// (logger.js NO la tiene y no se inventa): el stdout del hub lo redirige keepalive.sh a
// hub.log, así que el log persistente sale igual, ahora con nivel y módulo.
const log = createLogger("hub");

// Versión del hub para GET /api/setup/manifest (SBOM). Fuente ÚNICA: el valor
// vive aquí y lo consumen tests/manifest.test.js y el contrato §9.5; subirlo es
// tarea de release (F5 subió "1.0.0-SNAPSHOT" -> "1.0.0").
const HUB_VERSION = "1.0.0"; // F5: release v1.0.0 (fuente única del SBOM; la sigue tests/manifest.test.js)

// Helper: send ok envelope consistently
function ok(data) { return { ok: true, data }; }
function fail(error, code) { return { ok: false, error: String(error).slice(0, 800), code }; }

// ---- F4: validación de ids de ruta (projectId/sessionId/skillId) -----------
// Regex DEL CONTRATO F4: los ids que el hub GENERA (genProjectId, sesiones de
// los adapters, ids del catálogo de skills) y los nombres de carpeta de
// workspace saneados caben todos en [A-Za-z0-9._-]. Cualquier "/" "\" null,
// metacaracter de shell (";" "|" "$"…) o ".." queda FUERA => 400 con code
// PROJECT_INVALID/SESSION_INVALID (envelope estándar), ANTES de tocar el store,
// fs o cualquier path.join. Se valida SIEMPRE tras decodeURIComponent.
// Regex canónica (sin puntos para evitar ambigüedades en segmentos de ruta de filesystem)
const ID_RE = /^[A-Za-z0-9_-]+$/;
function isValidId(v) {
  return typeof v === "string" && v.length > 0 && v.length <= 256 && ID_RE.test(v) && !v.includes("..");
}
// 400 envelope {ok:false,error:{code,message}} — normalizeEnvelope lo deja tal cual
function invalidId(res, kind, value) {
  const code = kind === "project" ? "PROJECT_INVALID" : kind === "session" ? "SESSION_INVALID" : "SKILL_INVALID";
  const label = kind === "project" ? "projectId" : kind === "session" ? "sessionId" : "skillId";
  return json(res, 400, {
    ok: false,
    error: { code, message: `invalid ${label}: "${String(value ?? "").slice(0, 80)}" (must match ^[A-Za-z0-9_-]+$, max 256, sin "..")` }
  });
}

// ---- F4: rate limiting básico /api/* (sliding window en memoria, sin deps) --
// Techo por defecto: 120 req/min por IP (AEGIS_RATE_LIMIT_N cambia el techo;
// AEGIS_RATE_LIMIT=0 lo desactiva — lo usan tests y depuración). El hub sólo
// escucha en 127.0.0.1 => TODOS los clientes comparten un único bucket por IP
// de loopback. Exentos de contar:
//   - GET /api/health        (sonda pública de keepalive.sh, 1 cada 10s)
//   - GET /api/bootstrap/state (polling de 1s del wizard = 60 req/min solo:
//     contarlo comería la mitad de la cuota junto al chat-polling de 1.5s)
// Con ambas exenciones el wizard nunca consume cuota; el techo de 120/min
// reserva el resto para chat/UI (chat-polling ~40/min + UI ≈ holgado).
// 429 => envelope {ok:false,error:{code:"RATE_LIMITED"}} + header Retry-After.
const RATE_WINDOW_MS = 60 * 1000;
const RATE_LIMIT_N = (() => {
  const n = parseInt(process.env.AEGIS_RATE_LIMIT_N || "", 10);
  return Number.isFinite(n) && n > 0 ? n : 120;
})();
const RATE_LIMIT_ENABLED = process.env.AEGIS_RATE_LIMIT !== "0" && RATE_LIMIT_N > 0;
const rateHits = new Map(); // ip -> [timestamps dentro de la ventana]
// ---- segundo cubo: el POLLING de la app (2026-09-27) ------------------------
// El techo de 120/min se diseno con la cuenta de "chat-polling ~40/min + UI
// holgada". Esa cuenta era FALSA: medido, la app hace ~180 req/min y se limitaba a
// si misma. Reparto real del ciclo, con la app en un turno activo:
//   startViewRefresh, cada 2s: inflight + forms + permissions + messages  = 4
//   pollingJob,       cada 1.5s: messages                                = 1
//   lista de chats,   cada 2s: sessions                                  = 1
//   selector de modelo, cada 2s: models                                  = 1
//   => ~3,7 req/s = ~220/min, casi el doble del techo.
// Medido en hub.log: turno cerrado 05:45:32 y primer 429 a las 05:46:12, 40 s para
// agotar el presupuesto entero. Efecto: al caer en 429 el poll se traga el error y se
// queda con el estado viejo, asi que "Trabajando en ello" y el divisor vuelven a
// mentir, y el envio de una encuesta se come el 429.
//
// No se desactiva la proteccion: se le da al polling de solo lectura su propio cubo
// mas alto. Un bucle runaway se detiene en ese cubo; lo que deja de pasar es la app
// legitima estrangulandose con su propio presupuesto.
const RATE_LIMIT_POLL_N = (() => {
  const n = parseInt(process.env.AEGIS_RATE_LIMIT_POLL_N || "", 10);
  return Number.isFinite(n) && n > 0 ? n : 600;
})();
// Solo GET y solo estas rutas: las que consulta el bucle de refresco de la app. Una
// escritura (el reply de un formulario o de un permiso) cuenta para el cubo estricto,
// porque no debe poder esconderse detras del trafico de lectura.
function isReadOnlyPoll(method, pathname) {
  if (method !== "GET") return false;
  if (pathname === "/api/forms" || pathname === "/api/permissions") return true;
  if (pathname === "/api/sessions" || pathname === "/api/sessions/inflight") return true;
  if (pathname === "/api/opencode/sessions/inflight") return true;
  // /api/sessions/:id/models y /api/opencode/sessions/:id/messages, y tambien
    // :id/part: descargar el binario recortado es un GET, no una escritura, y no
    // debe gastar el cubo estricto de 120/min que existe para las escrituras
  return /^\/api\/(opencode\/)?sessions\/[^/]+\/(models|messages|part)$/.test(pathname);
}
const rateHitsPoll = new Map(); // ip -> [timestamps del polling, cubo propio]

// Log obligatorio en los 502: sin esto, un fallo de OpenCode al responder un
// formulario o un permiso no dejaba NI UNA LINEA en el log, y el diagnostico se hizo
// a ciegas. Se registra el estado y un trozo del cuerpo, que es donde viene el motivo.
function log502(que, code, r) {
  log.warn(`[${que}] OpenCode respondio ${r.status} (${code})`, {
    status: r.status,
    body: String(r.text ?? "").slice(0, 300),
  });
  return r;
}
function rateLimitCheck(ip, bucket) {
  if (!RATE_LIMIT_ENABLED) return { allowed: true };
  // bucket = "poll" usa su propio contador y su propio techo. NO comparten ventana: si
  // la compartieran, el poll se comeria el presupuesto del estricto y seguiria
  // estrangulando al resto, que es justo lo que se quiere evitar.
  const poll = bucket === "poll";
  const ceiling = poll ? RATE_LIMIT_POLL_N : RATE_LIMIT_N;
  const store = poll ? rateHitsPoll : rateHits;
  const now = Date.now();
  const cutoff = now - RATE_WINDOW_MS;
  let arr = store.get(ip);
  if (!arr) { arr = []; store.set(ip, arr); }
  // poda sliding-window de los timestamps fuera de la ventana
  let i = 0;
  while (i < arr.length && arr[i] <= cutoff) i++;
  if (i > 0) arr = arr.slice(i);
  if (arr.length >= ceiling) {
    store.set(ip, arr);
    const retryAfterMs = (arr[0] + RATE_WINDOW_MS) - now;
    return { allowed: false, retryAfterSec: Math.max(1, Math.ceil(retryAfterMs / 1000)), ceiling };
  }
  arr.push(now);
  store.set(ip, arr);
  return { allowed: true };
}

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// ---- Auth: token de acceso del hub (raíz total CON token; cero acceso SIN token) ----
const TOKEN_FILE = path.join(__dirname, ".aegis_token");
function loadOrCreateToken() {
  try {
    if (fs.existsSync(TOKEN_FILE)) {
      const existing = fs.readFileSync(TOKEN_FILE, "utf8").trim();
      if (existing) return existing;
    }
    fs.writeFileSync(TOKEN_FILE, crypto.randomBytes(32).toString("hex"), { mode: 0o600 });
    log.info("[hub] token file created"); // nunca imprimir el token
    return fs.readFileSync(TOKEN_FILE, "utf8").trim();
  } catch (e) {
    log.error("[hub] token file error", { err: e.message });
    return "";
  }
}
const AEGIS_TOKEN = loadOrCreateToken();

// Comparación timing-safe (timingSafeEqual exige buffers de igual longitud)
function tokenMatches(provided) {
  if (!AEGIS_TOKEN) return false;
  const a = Buffer.from(typeof provided === "string" ? provided : "", "utf8");
  const b = Buffer.from(AEGIS_TOKEN, "utf8");
  if (a.length === 0 || a.length !== b.length) return false;
  return crypto.timingSafeEqual(a, b);
}

// CORS: allowlist — solo se refleja el origin si está en la lista (nunca "*")
const CORS_ALLOWLIST = ["http://localhost:8765", "app://aegis"];
const CORS_ALLOW_HEADERS = "X-Aegis-Token, Content-Type";
function withCors(res, headers = {}) {
  const h = { ...headers };
  // elimina cualquier ACAO heredado (upstream lo manda en minúsculas como header de Node)
  delete h["Access-Control-Allow-Origin"];
  delete h["access-control-allow-origin"];
  const origin = res && res._corsOrigin;
  if (origin && CORS_ALLOWLIST.includes(origin)) {
    h["Access-Control-Allow-Origin"] = origin;
    h["Vary"] = h["Vary"] ? `${h["Vary"]}, Origin` : "Origin";
  }
  return h;
}

// Anti path-traversal: el id debe resolver DENTRO de basePath (previo a cualquier fs.rmSync/rmdir)
function resolvesInside(basePath, id) {
  const base = path.resolve(basePath);
  const target = path.resolve(base, String(id ?? ""));
  return target !== base && target.startsWith(base + path.sep);
}

// Metacaracteres de shell prohibidos en parámetros interpolados en comandos
// Denylist COMPLETA ahora. Antes faltaban `>`, `>>`, `<`, `&` suelto y `${...}` sin
// parentesis, y la de `am start` interpola `pkg` SIN comillas: un `pkg` con `>`
// ejecutaba una redireccion como root. Sigue siendo una denylist (un filtro se puede
// saltar), asi que para lo que tiene GRAMATICA se usa la allowlist PACKAGE_RE de abajo.
// Nota de honestidad: en /api/device/shell esta regex no protege nada, porque `cmd`
// es el comando entero — ese endpoint es root por diseno para quien tenga el token.
const SHELL_META_RE = /[;|`]|&&|\n|\$\(|\$\{|[<>]|&(?!&)/;
// Gramatica de paquete Android. /api/device/tap y /key ya usaban parseInt como
// patron; esto es el mismo criterio aplicado a lo que de verdad tiene estructura.
const PACKAGE_RE = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$/;
function isValidPackageName(v) {
  return typeof v === "string" && v.length <= 255 && PACKAGE_RE.test(v);
}
function hasShellMeta(v) { return typeof v === "string" && SHELL_META_RE.test(v); }
function badParam(res, name) {
  return json(res, 400, { ok: false, error: `invalid characters in "${name}" (; | \` && <> & newline $() \${})`, code: "BAD_REQUEST" });
}

// resiliencia: no morir por crash del proxy, pero sí permitir reinicio limpio por keepalive (pkill -f) o por kill -15
// Frecuencia de las excepciones SIN CAPTURAR. MEDIDO 2026-09-29: una sola peticion
// abandonada produjo 578 lineas de EPIPE. Es el mismo problema que los 500 de
// respondUnhandledRequestError y se resuelve igual: la primera se dice entera, luego
// una de cada 25, y el total al parar. NO se silencia — el diagnostico se conserva.
const EXC_LOG_EVERY = 25;
const excVistos = new Map();
function logExcepcion(etiqueta, e) {
  const clave = `${etiqueta} ${(e && (e.code || e.message)) || String(e)}`.slice(0, 160);
  const ahora = Date.now();
  let rec = excVistos.get(clave);
  if (!rec) { rec = { n: 0, desde: ahora }; excVistos.set(clave, rec); }
  rec.n++;
  // La ventana de un minuto caduca: si el fallo vuelve mas tarde, se vuelve a decir
  // como PRIMERA vez y no como continuacion de un minuto viejo.
  if (ahora - rec.desde > 60000) {
    if (rec.n > 1) log.warn(`[hub] ${etiqueta} dejo de ocurrir: ${rec.n} en 60 s`);
    rec.n = 0; rec.desde = ahora; return;
  }
  if (rec.n === 1 || rec.n % EXC_LOG_EVERY === 0) {
    log.error(etiqueta, { err: (e && e.stack) || String(e) });
  }
}
process.on('uncaughtException', e => logExcepcion('[hub] uncaughtException', e));
process.on('unhandledRejection', e => logExcepcion('[hub] unhandledRejection', e));

// =====================================================================
// GUARDIA DE SUPERVIVENCIA — un hub de tests no puede sobrevivir a su padre
// =====================================================================
// MEDIDO 2026-09-29: matar al proceso dueño con SIGKILL dejó SEIS hubs huérfanos
// con PPID=1 reteniendo 504 MB, de UN solo fichero de tests. Con 12 ficheros y
// varias vueltas, eso es exactamente los 106 huérfanos / 5.757 MB del informe.
// Un hub vivo pesa 80-90 MB: 6 ya son media gigabyte, y el movil tiene 11,3.
//
// El `after()` que hay en los 12 ficheros de test NO es la defensa. Cuando el
// dueño muere con SIGKILL —que es lo que hace un timeout de CI— no se ejecuta
// NINGÚN hook, y los hijos que el fichero ya habia acumulado se quedan vivos
// para siempre (el `after()` mata a `hubs`, pero nadie mata a `hubs`). Que el
// fichero contenga un after() no dice nada sobre ese camino: el unico proceso
// que sigue en pie cuando todo lo demas ha muerto es el hijo, asi que la
// defensa tiene que vivir aqui.
//
// DOS disparadores, y por que los dos:
//   1) INTRINSECO (el que se usa solo): mi padre es un runner de tests. Se lee
//      UNA vez al arrancar, mientras el padre todavia existe. En produccion el
//      padre es un shell (`nohup node server.js`, desde start-hub.sh o
//      keepalive.sh) y NUNCA lleva `--test`, asi que alli la guardia no se arma y
//      no cambia absolutamente nada.
//   2) EXPLICITO: AEGIS_SELF_LIMIT_MS en milisegundos. Hace falta porque CI
//      ejecuta `node --test` DIRECTO, no `npm test`: una variable puesta en
//      package.json no llegaria a los hijos.
//
// Se mira el padre por SU /proc, no por si el hijo es "de tests" de alguna
// manera adivinada: si /proc no se puede leer, la guardia NO se arma. Fallar
// hacia "no auto-matarme" es lo que corresponde: en produccion un falso positivo
// seria un Hub que se suicide al cerrarse quien lo lanzo.
let GUARDIA_TOPE_MS = 0;
function armarGuardiaSupervivencia() {
  const explicito = parseInt(process.env.AEGIS_SELF_LIMIT_MS || "", 10);
  const padre = process.ppid;
  let armado = Number.isFinite(explicito) && explicito > 0;
  if (!armado && padre > 1) {
    try {
      // MEDIDO: el padre real de un hub de test es
      //   node --test-coverage-functions=0 --test-concurrency=0 --test-isolation=process ...
      // Node pone esos flags al forkear el hijo por fichero, asi que `--test` va
      // seguido de GUION, no de espacio. Un matcher que exija `--test(\s|$)` no
      // casa con NADA y la guardia no se arma nunca: fue lo que paso en la primera
      // version de esto, y el unico sintoma fue que el defecto seguia ahi.
      // Por eso se hace PREFIJO, y por eso se comprueba con control NEGATIVO: los
      // lanzadores de produccion (start-hub.sh, keepalive.sh, service.d) no llevan
      // `--test` y siguen dando False.
      const cmd = fs.readFileSync(`/proc/${padre}/cmdline`, "utf8").replace(/\0/g, " ");
      armado = /(^|\s)--test/.test(cmd);
    } catch (_) { /* sin /proc: no se arma. Ver el comentario de arriba. */ }
  }
  if (!armado) return false;
  GUARDIA_TOPE_MS = (Number.isFinite(explicito) && explicito > 0) ? explicito : 15 * 60 * 1000;
  const arranque = Date.now();
  const iv = setInterval(() => {
    if (!fs.existsSync(`/proc/${padre}`)) {
      log.warn(`[hub] guardia de supervivencia: mi padre (pid ${padre}) ya no existe — el run de tests murio sin cerrar sus hooks. Me voy, para no quedarme huerfano ocupando ~80 MB.`);
      process.exit(0);
    }
    if (Date.now() - arranque > GUARDIA_TOPE_MS) {
      log.warn(`[hub] guardia de supervivencia: superados los ${GUARDIA_TOPE_MS} ms de vida maxima de un hub de test. Me voy.`);
      process.exit(0);
    }
  }, 2000);
  // unref: el temporizador no es motivo para mantener vivo al proceso.
  if (iv.unref) iv.unref();
  log.info(`[hub] guardia de supervivencia armada (padre ${padre}, tope ${GUARDIA_TOPE_MS} ms) — este hub es de tests`);
  return true;
}
if (armarGuardiaSupervivencia()) { /* solo informativo */ }
// Quien envia el SIGTERM, de verdad. El mensaje anterior decia SIEMPRE
// "keepalive relanza", estea o no fuera keepalive, y eso hizo perder tiempo a dos
// sesiones distintas persiguiendo al culpable equivocado (la otra sesion lo apunta
// explicitamente: "Me hizo perder tiempo CREO apuntando al keepalive equivocado dos
// veces"). Un diagnostico no puede afirmar una causa que no ha comprobado.
//
// De donde sale el dato: en Linux, /proc/<pid>/stat[15] es el campo "signal" del
// proceso. Ojo con los indices: los dos primeros campos del stat son "pid" y "comm", y
// "comm" va entre parentesis CON ESPACIOS, asi que partiendo por la cadena el campo
// signal es el numero 12 contando desde 1, NO el 15. Se recorre el fichero a mano en vez
// de usar split() por lo mismo, y se toma el ultimo parentesis que cierra.
function senalRecibida(pid) {
  try {
    const st = fs.readFileSync(`/proc/${pid}/stat`, "utf8");
    const fin = st.lastIndexOf(")");
    const campos = st.slice(fin + 2).trim().split(/\s+/);
    // campos[0] = state (campo 3 del stat), asi que signal = campo 15 -> indice 12.
    return parseInt(campos[12], 10);
  } catch (_) { return null; }
}

// El padre es quien casi siempre manda la senal. Se registra porque "me ha matado mi
// padre keepalive" y "me ha matado un reinicio manual" son diagnosticos distintos.
function padre(pid) {
  try {
    const st = fs.readFileSync(`/proc/${pid}/stat`, "utf8");
    const fin = st.lastIndexOf(")");
    const campos = st.slice(fin + 2).trim().split(/\s+/);
    return { ppid: parseInt(campos[1], 10), starttime: campos[19] };
  } catch (_) { return { ppid: null, starttime: null }; }
}

function quienMato(que) {
  const mio = process.pid;
  const p = padre(mio);
  const senal = senalRecibida(mio);
  let padreCmd = "desconocido";
  try {
    if (p.ppid && p.ppid > 1) padreCmd = fs.readFileSync(`/proc/${p.ppid}/cmdline`, "utf8").replace(/\0/g, " ").trim().slice(0, 120);
  } catch (_) {}
  // OJO con el nombre: el campo `signal` de /proc/<pid>/stat es la ultima senal que
  // DETUVO el proceso (job control), NO la que lo mato. Al recibir un SIGTERM normal
  // suele valer 0, o un valor viejo sin relacion (aqui salia 8 = SIGFPE, de un
  // parpadeo de otro momento). Etiquetarlo como "la senal que me mato" seria el mismo
  // error que el del mensaje hardcodeado que se acaba de quitar: afirmar una causa sin
  // comprobarla. El diagnostico de quien lo mato va aparte, en ppid + padreCmd, que si
  // es fiable.
  const NAMES = { 1: "SIGHUP", 2: "SIGINT", 3: "SIGQUIT", 9: "SIGKILL", 15: "SIGTERM" };
  const nombre = senal != null ? (NAMES[senal] || `senal ${senal}`) : "desconocida";
  // Se registra el DATO, no la conclusion. Quien lo lea decide quien fue; el log no
  // accuse a nadie por su cuenta.
  log.info(`[hub] ${que} recibido`, {
    // senalQueDetuvo NO es la que mato, ver el comentario de arriba. Quien lo mato se
    // deduce de ppid + padreCmd.
    senalQueDetuvo: nombre,
    senalQueDetuvoNum: senal,
    pid: mio,
    ppid: p.ppid,
    padreCmd,
  });
}

// Cierre ordenado: el vigilante SSE se cierra ANTES de salir. Antes no se cerraba
// nunca, asi que cada reinicio del Hub (y keepalive reinicia bastante) dejaba su
// suscripcion colgada dentro de `opencode serve`. Con reinicios seguidos, ahi se
// acumulaba la memoria.
function stopBackgroundWatchers(reason) {
  try { if (execWatcher) execWatcher.stop(); } catch (e) { log.error(`[hub] ${reason}: execWatcher.stop err`, { err: e.message }); }
  try { if (typeof jobScheduler !== "undefined" && jobScheduler && jobScheduler.stop) jobScheduler.stop(); } catch (_) {}
}
process.on('SIGTERM', () => { quienMato("SIGTERM"); stopBackgroundWatchers("SIGTERM"); try { server.close(() => process.exit(0)); } catch (_) { process.exit(0); } setTimeout(()=> process.exit(0), 2000); });
process.on('SIGINT',  () => { log.info('[hub] SIGINT — cierre'); stopBackgroundWatchers("SIGINT"); try { server.close(() => process.exit(0)); } catch (_) { process.exit(0); } setTimeout(()=> process.exit(0), 2000); });
process.on('SIGPIPE', () => log.info('[hub] SIGPIPE ignorado'));

function argVal(name, fallback){
  const i = process.argv.indexOf(name);
  return i !== -1 && process.argv[i+1] ? process.argv[i+1] : fallback;
}
const HUB_PORT = parseInt(process.env.HUB_PORT || argVal("--port","8765"), 10);

// Un SOLO servidor de OpenCode: el que ya usa el TUI del CLI.
//
// Había DOS. El Hub apuntaba a :4096 (el que lanzaba keepalive) y el TUI se
// conectaba al servicio registrado en :49374. Comparten la base de datos SQLite,
// así que los mensajes se ven en ambos, pero el estado "este turno está corriendo"
// vive en la MEMORIA de cada proceso: el CLI no veía los turnos que Aegis lanzaba
// y el chat se leía como si no estuviera ejecutándose.
//
// Si hay un servicio registrado (service.json), ese manda. Se ignora el argumento
// --opencode-port a propósito: keepalive puede estar trayendo un 4096 obsoleto
// desde antes de esta unificación, y respetar ese valor reintroduciría el bug.
// OPENCODE_PORT del entorno sigue teniendo prioridad máxima (para depurar).
const OC_SERVICE_PORT = parseInt(process.env.AEGIS_OC_SERVICE_PORT || "49374", 10);
const OC_HAS_SERVICE = fs.existsSync("/root/.config/opencode/service.json");
const OC_ARG_PORT = parseInt(argVal("--opencode-port", "0"), 10) || 0;
const OPENCODE_PORT = parseInt(
  process.env.OPENCODE_PORT ||
    (OC_HAS_SERVICE ? OC_SERVICE_PORT : (OC_ARG_PORT || 4096)),
  10
);
const OPENCODE_HOST = process.env.OPENCODE_HOST || "127.0.0.1";
const PROJECTS_ROOT = process.env.PROJECTS_ROOT || "/sdcard/projects";
const UI_STATE_FILE = path.join(__dirname, "ui-state.json");
const SKILLS_ROOT = path.join(__dirname, "skills");
const SUMMARIES_DIR = path.join(__dirname, "summaries");
const UI_STATE = (() => {
  try { if (fs.existsSync(UI_STATE_FILE)) return JSON.parse(fs.readFileSync(UI_STATE_FILE, "utf8")); } catch {}
  // New fields: projectId (companion managed id), sessionId; legacy `project` (folder name) kept for compat
  const base = { project: null, projectId: null, sessionId: null, updatedAt: 0 };
  try {
    const raw = fs.existsSync(UI_STATE_FILE) ? JSON.parse(fs.readFileSync(UI_STATE_FILE, "utf8")) : {};
    return { ...base, ...raw };
  } catch { return base; }
})();
function saveUiState() {
  // A-4 (BACKEND-BUG-02/04): escritura atómica (tmp+fsync+rename) — un crash a
  // medio escribir ui-state.json dejaba JSON corrupto y el hub perdía proyecto/sesión activos.
  try { atomicWriteFileSync(UI_STATE_FILE, { ...UI_STATE, updatedAt: Date.now() }); } catch (e) { log.error("[ui-state] save err", { err: e.message }); }
}

// ---- Companion Project Management — persistent store projects.json ----
// Schema per spec (1): { id, name, description, createdAt, archivedAt, sessions:[{sessionId,title,createdAt,lastUsed,summary}], skills:[], linkedProjects:[] }
// Stored at /sdcard/projects/Aegis/backend/projects.json ; soft delete via archivedAt timestamp.
// Envelope: all /api/projects routes return {ok:true,data:...} or {ok:false,error:...} (spec 6).
// El store es configurable para que los tests NUNCA escriban en el projects.json real:
// antes estaba hardcodeado y `node --test` en local insertaba proyectos de prueba
// ("Test Auto Folder ...", "1") en el registro del usuario. Los tests ya aíslan
// PROJECTS_ROOT, pero no el store, que es lo que realmente se ensuciaba.
const PROJECTS_STORE_FILE = process.env.AEGIS_PROJECTS_STORE
  ? path.resolve(process.env.AEGIS_PROJECTS_STORE)
  : path.join(__dirname, "projects.json");

// BACKLOG (F0-F2, unificación): loadProjectsStore se MOVIO a src/core/storage.js
// (fuente única — antes existía también en providers.js). Misma firma, misma
// semántica (versión canónica con seeds de sessionTitles); se importa arriba.

function resolveExistingSessionTitle(store, sessionId, fallbackTitle = null) {
  if (!sessionId) return fallbackTitle || "";
  if (store && store.sessionTitles && store.sessionTitles[sessionId]) {
    return store.sessionTitles[sessionId];
  }
  if (store && Array.isArray(store.projects)) {
    for (const p of store.projects) {
      const s = (p.sessions || []).find(x => x.sessionId === sessionId);
      if (s && s.title && s.title !== sessionId) {
        return s.title;
      }
    }
  }
  if (fallbackTitle && fallbackTitle !== sessionId) {
    return fallbackTitle;
  }
  return sessionId;
}
function resolveExistingSessionPinned(store, sessionId) {
  if (!sessionId || !store) return false;
  if (store.sessionPins && store.sessionPins[sessionId] !== undefined) {
    return Boolean(store.sessionPins[sessionId]);
  }
  if (Array.isArray(store.projects)) {
    for (const p of store.projects) {
      const s = (p.sessions || []).find(x => x.sessionId === sessionId);
      if (s && s.pinned !== undefined) {
        return Boolean(s.pinned);
      }
    }
  }
  return false;
}
function saveProjectsStore(store) {
  try {
    atomicWriteFileSync(PROJECTS_STORE_FILE, store);
  } catch (e) { log.error("[projects] save err", { err: e.message }); }
}
// Generate stable id: timestamp + random suffix, lowercase alphanumeric + hyphen
function genProjectId() {
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}
function sanitizeProjectId(v) { return String(v || "").trim(); }
function nowIso() { return new Date().toISOString(); }
// Find project by id (including archived) — caller filters visible if needed
function findProject(store, id) { return store.projects.find(p => p.id === id) || null; }
// F6: carpetas candidatas de un proyecto para el merge de sesiones de OpenCode.
// directory explícito (PATCH) > rutas absolutas en linkedProjects >
// PROJECTS_ROOT/<nombre> (POST /api/projects crea exactamente esa carpeta).
function projectCandidateDirs(proj) {
  const out = [];
  const push = (v) => {
    if (typeof v === "string" && v.trim() && v.trim().startsWith("/")) {
      const clean = v.trim().replace(/\/+$/, "");
      if (clean && !out.includes(clean)) out.push(clean);
    }
  };
  push(proj.directory);
  if (Array.isArray(proj.linkedProjects)) for (const lp of proj.linkedProjects) push(lp);
  if (typeof proj.name === "string" && proj.name.trim()) push(path.join(PROJECTS_ROOT, proj.name.trim()));
  return out;
}
// Validate project payload for create/update
function validateProjectPayload(body, isCreate) {
  if (isCreate && (!body.name || !String(body.name).trim())) return "name required";
  if (body.name !== undefined && !String(body.name).trim()) return "name cannot be empty";
  if (body.description !== undefined && typeof body.description !== "string") return "description must be string";
  if (body.instructions !== undefined && typeof body.instructions !== "string") return "instructions must be string";
  if (body.skills !== undefined && !Array.isArray(body.skills)) return "skills must be array";
  if (body.linkedProjects !== undefined && !Array.isArray(body.linkedProjects)) return "linkedProjects must be array";
  if (body.directory !== undefined && body.directory !== null && typeof body.directory !== "string") return "directory must be string";
  if (body.folder !== undefined && body.folder !== null && typeof body.folder !== "string") return "folder must be string";
  if (body.provider !== undefined && String(body.provider).toLowerCase().trim() !== "opencode") {
    return "invalid provider — must be 'opencode'";
  }
  return null;
}
// Normalize sessions entry — ensures required fields exist
function normalizeSessionEntry(s) {
  return {
    sessionId: String(s.sessionId || s.id || "").trim(),
    title: String(s.title || s.sessionId || "untitled").trim(),
    createdAt: s.createdAt || nowIso(),
    lastUsed: s.lastUsed || s.createdAt || nowIso(),
    summary: s.summary || "",
    pinned: Boolean(s.pinned || false),
    provider: s.provider ? String(s.provider).toLowerCase().trim() : undefined
  };
}

// ---- Voice Registry + Log — TOP-LEVEL persistent storage (outside request callback) ----
const VOICE_COMMANDS = [
  { id: "new_project", patterns: ["nuevo proyecto {nombre}"], example: "nuevo proyecto mi app", description: "Crear proyecto (POST /api/projects name=nombre)", handler: "POST /api/projects" },
  { id: "open_project", patterns: ["abrir proyecto {nombre}"], example: "abrir proyecto mi app", description: "Cambiar proyecto activo por nombre aproximado (fuzzy match)", handler: "PATCH /api/ui/state projectId" },
  { id: "new_session", patterns: ["nueva sesión", "nueva sesion"], example: "nueva sesión", description: "Crear sesión en proyecto actual (POST /opencode/session)", handler: "POST /opencode/session" },
  { id: "open_app", patterns: ["abrir {app}"], example: "abrir chrome", description: "Abrir app por alias o paquete (POST /api/device/launch)", handler: "POST /api/device/launch" },
  { id: "screenshot", patterns: ["tomar captura", "hacer captura", "captura de pantalla"], example: "tomar captura", description: "Captura pantalla y mostrar inline (GET /api/device/screenshot)", handler: "GET /api/device/screenshot" },
  { id: "system_status", patterns: ["estado del sistema", "como esta el sistema", "estado"], example: "estado del sistema", description: "Leer /api/system/status en voz alta vía TTS", handler: "GET /api/system/status + TTS" },
  { id: "whatsapp_send", patterns: ["enviar a {contacto} por whatsapp {mensaje}", "manda whatsapp a {contacto} {mensaje}"], example: "enviar a juan por whatsapp hola", description: "Enviar WhatsApp (POST /api/assistant/execute action=whatsapp_send)", handler: "POST /api/assistant/execute" },
];
const VOICE_LOG = [];
const VOICE_LOG_MAX = 50;
function pushVoiceLog(entry) {
  const e = { timestamp: nowIso(), ...entry };
  VOICE_LOG.unshift(e);
  if (VOICE_LOG.length > VOICE_LOG_MAX) VOICE_LOG.length = VOICE_LOG_MAX;
  return e;
}

// ---- Skills + Summaries storage helpers ----
// Skills are markdown files at skills/{scope}/{name}.skill.md — scope "global" or projectId.
// Summaries are JSON files at summaries/{projectId}.summary.json with {summary, updatedAt, sessions[]}.

function sanitizeScope(v) { return String(v || "").trim(); }
function sanitizeName(v) { return String(v || "").trim(); }
function isValidScope(scope) {
  if (scope === "global") return true;
  // projectId scopes must correspond to an existing project id (or we allow any alphanumeric hyphen)
  return /^[a-z0-9][a-z0-9-]*$/i.test(scope);
}
function isValidSkillName(name) {
  return /^[a-z0-9][a-z0-9 _-]*$/i.test(name) && name.length <= 80;
}
function skillPath(scope, name) {
  // Prevent path traversal — sanitize
  const s = scope.replace(/[^a-zA-Z0-9_-]/g, "");
  const n = name.replace(/[^a-zA-Z0-9 _-]/g, "").replace(/\.+/g, "");
  return path.join(SKILLS_ROOT, s, `${n}.skill.md`);
}
function listSkills(scope) {
  const dir = path.join(SKILLS_ROOT, scope);
  try {
    if (!fs.existsSync(dir)) return [];
    return fs.readdirSync(dir).filter(f => f.endsWith(".skill.md")).map(f => {
      const name = f.replace(/\.skill\.md$/, "");
      const full = path.join(dir, f);
      const stat = fs.statSync(full);
      const content = fs.readFileSync(full, "utf8");
      return { scope, name, content, updatedAt: stat.mtime.toISOString(), size: content.length };
    });
  } catch { return []; }
}
function listAllSkillsMerged(projectId) {
  // Returns global skills + project-specific skills merged (global first)
  const global = listSkills("global");
  const project = projectId ? listSkills(projectId) : [];
  return [...global, ...project];
}
function writeSkill(scope, name, content) {
  const p = skillPath(scope, name);
  fs.mkdirSync(path.dirname(p), { recursive: true });
  // A-4: atómico — un crash a medio escribir deja el skill anterior intacto, nunca un .md truncado.
  atomicWriteFileSync(p, String(content || ""));
  return { scope, name, content: String(content || ""), path: p };
}
function deleteSkill(scope, name) {
  const p = skillPath(scope, name);
  if (!fs.existsSync(p)) return false;
  fs.unlinkSync(p);
  // Remove empty scope dir
  try { if (fs.readdirSync(path.dirname(p)).length === 0) fs.rmdirSync(path.dirname(p)); } catch {}
  return true;
}
function readSummary(projectId) {
  const p = path.join(SUMMARIES_DIR, `${projectId}.summary.json`);
  try { if (fs.existsSync(p)) return JSON.parse(fs.readFileSync(p, "utf8")); } catch {}
  return null;
}
function writeSummary(projectId, summary) {
  fs.mkdirSync(SUMMARIES_DIR, { recursive: true });
  const p = path.join(SUMMARIES_DIR, `${projectId}.summary.json`);
  const obj = { projectId, summary: String(summary || "").trim(), updatedAt: nowIso() };
  // A-4 (BACKEND-BUG-02/04): atómico — summaries/*.summary.json es estado compartido leído por buildCrossProjectContext.
  atomicWriteFileSync(p, obj);
  return obj;
}
function buildCrossProjectContext(projectId) {
  // Include one-paragraph summary of each linked project's last 3 sessions equivalent — here using summary.json
  if (!projectId) return "";
  const store = loadProjectsStore();
  const proj = findProject(store, projectId);
  if (!proj || !proj.linkedProjects || proj.linkedProjects.length === 0) return "";
  const paragraphs = [];
  for (const linkedId of proj.linkedProjects.slice(0, 8)) {
    const linked = findProject(store, linkedId);
    const title = linked ? linked.name : linkedId;
    const summ = readSummary(linkedId);
    if (summ && summ.summary) {
      paragraphs.push(`Project "${title}" (${linkedId}): ${summ.summary}`);
    } else {
      // Fallback: list last 3 session titles if no summary yet
      const last = linked && linked.sessions ? linked.sessions.slice(-3).map(s => s.title || s.sessionId) : [];
      if (last.length) paragraphs.push(`Project "${title}" (${linkedId}) recent sessions: ${last.join(" | ")} — no summary yet.`);
      else paragraphs.push(`Project "${title}" (${linkedId}): no summary or sessions.`);
    }
  }
  return paragraphs.join("\n\n");
}
function buildSkillsContext(projectId) {
  const skills = listAllSkillsMerged(projectId);
  if (!skills.length) return "";
  return skills.map(s => `### Skill: ${s.name} [${s.scope}]\n${s.content}`).join("\n\n---\n\n");
}
// El ponytail GLOBAL ya no se lee aquí: lo entrega OpenCode a TODAS las sesiones
// por su propio mecanismo, `~/.config/opencode/AGENTS.md` -> este mismo archivo.
// (V2 acepta el campo `instructions` en config pero NO lo resuelve, así que
// AGENTS.md es el único mecanismo que cubre el 100% de las sesiones.)
// Reinyectarlo duplicaría ~12KB por sesión del hub y consumiría el presupuesto de
// 24KB de buildSystemContextBlock, desplazando el contexto específico de proyecto
// que solo el hub puede ensamblar. Aquí queda únicamente la capa por proyecto.
const PONYTAIL_GLOBAL_FILE = "/sdcard/projects/ponytail-global.md";

function loadPonyTailContext(projectId) {
  const blocks = [];

  if (projectId) {
    try {
      const store = loadProjectsStore();
      const proj = findProject(store, projectId);
      if (proj && proj.name) {
        const projDir = path.join(PROJECTS_ROOT, proj.name);
        const projPonyTail = path.join(projDir, ".ponytail.md");
        if (fs.existsSync(projPonyTail)) {
          const content = fs.readFileSync(projPonyTail, "utf8");
          blocks.push(content.trim());
        }
      }
    } catch (e) {
      log.warn("[ponytail] failed reading project .ponytail.md", { err: e.message });
    }
  }

  if (blocks.length > 0) {
    blocks.push(
      `<!-- PONY-TAIL DIRECTIVE -->\n*Instrucción de Sistema:* Cuando alcances un hito, refactorización o funcionalidad relevante 100% verificada en este proyecto, debes actualizar automáticamente el archivo \`.ponytail.md\` en la raíz del proyecto para registrar los avances. Tienes estrictamente prohibido modificar, borrar o sobreescribir el archivo global \`${PONYTAIL_GLOBAL_FILE}\`: se entrega automáticamente a todas las sesiones vía \`~/.config/opencode/AGENTS.md\`.`
    );
  }

  return blocks.join("\n\n---\n\n");
}

function buildSystemContextBlock(projectId) {
  // Compose pony-tail context + project instructions + skills + cross-project context
  const parts = [];
  const ponyTailBlock = loadPonyTailContext(projectId);
  if (ponyTailBlock) parts.push(ponyTailBlock);

  if (projectId) {
    const store = loadProjectsStore();
    const proj = findProject(store, projectId);
    if (proj && proj.instructions && proj.instructions.trim()) {
      parts.push(`# Project Instructions\n${proj.instructions.trim()}`);
    }
  }
  const skillsBlock = buildSkillsContext(projectId);
  if (skillsBlock) parts.push(`# Active Skills\n${skillsBlock}`);
  const crossBlock = buildCrossProjectContext(projectId);
  if (crossBlock) parts.push(`# Cross-Project Context (linked projects)\n${crossBlock}`);

  // Execution Engine & Tool-Calling Parity Directive
  parts.push(
    `# MOTOR DE EJECUCIÓN Y TOOL-CALLING ACTIVO\n` +
    `Tienes permisos automáticos de ejecución de herramientas activados (--dangerously-skip-permissions / auto-approve).\n` +
    `NO actúes como un chatbot pasivo de solo texto. Cuando el usuario solicite ejecutar un comando, inspeccionar el entorno, montar rutas, crear o modificar archivos, correr diagnósticos, o resolver un problema técnico, NO te limites a responder únicamente con texto estático o sugerencias teóricas: EJECUTA de inmediato las herramientas y comandos bash correspondientes (usando run_command o bash, y si requieres inspeccionar el sistema host Android o comandos de root, puedes usar nsenter -t 1 -m o herramientas directas de sistema) para inspeccionar, aplicar cambios y reportar los resultados reales obtenidos de la ejecución.`
  );

  return parts.join("\n\n");
}

// ---- Multi-agent Provider Abstraction ----
const providerManager = new ProviderManager(path.join(__dirname, "providers.json"));
const opencodeAdapter = new OpencodeAdapter({
  host: OPENCODE_HOST,
  port: OPENCODE_PORT,
  // F6: opencode.log es la fuente de la contraseña Basic de la API v2
  // ("server password ...", rota en cada arranque del serve).
  logPath: path.join(__dirname, "opencode.log"),
  getSystemContextBlock: (pid) => buildSystemContextBlock(pid)
});
providerManager.register(opencodeAdapter);
// AntigravityAdapter (938 lineas) y ClaudeCodeAdapter (un stub con metodos vacios)
// se han retirado: OpenCode es el unico motor. El plugin opencode-antigravity sigue
// dando los modelos Antigravity DENTRO de OpenCode, que es donde deben estar.

// F3: handler de /api/setup/* — recibe la maquinaria REAL del hub (adapter de
// sesiones/mensajes de OpenCode + la misma sonda /global/health del health) para
// que el smoke-test use el mismo cable que la app, no un atajo paralelo.
const handleSetupRoute = createSetupHandler({
  opencodeAdapter,
  probeOpencodeHealth,
  // F4: HUB_VERSION (server.js) para el SBOM de GET /api/setup/manifest
  hubVersion: HUB_VERSION
});

// ---- Non-destructive session ownership discovery (hub must never kill TUI) ----
// COMPANION_SESSION_FILE persists the PID we launched as companion-owned across restarts.
// If this file exists and its PID is still alive as "opencode serve", ownership is companion-owned.
// If health is true but no such file/pid matches, it's an existing termux-native session we must not touch.
// If health is false, ownership is none and only then may we launch via keepalive.sh.
const COMPANION_SESSION_FILE = path.join(__dirname, ".companion-session.json");

// Load persisted companion meta at startup (if any) — survives hub restarts so we remember our own serve.
let companionMeta = (() => {
  try { if (fs.existsSync(COMPANION_SESSION_FILE)) return JSON.parse(fs.readFileSync(COMPANION_SESSION_FILE, "utf8")); } catch {}
  return null;
})();

// Probe opencode health via HTTP — single source of truth for whether a serve is running (termux or companion).
// Called on hub startup and on every /api/system/status call (spec 1).
// F6: la sonda habla la API v2 autenticada (GET /api/info) y sólo cae a la SPA
// como "vivo sin auth" si no hay contraseña todavía. Misma forma de respuesta.
function probeOpencodeHealth() {
  return opencodeAdapter.isHealthy().then(h => ({
    up: !!h.up,
    healthy: !!h.healthy,
    version: h.version || null,
    raw: h.error ? { error: h.error } : { source: "/api/info" }
  })).catch(() => ({ up: false, healthy: false, version: null }));
}

// Scan /proc for opencode serve PIDs — distinguishes TUI (cmdline "opencode" only) from serve.
// Detection: split cmdline on \0 into argv parts. Real serve has argv[0] basename "opencode"/"opencode.exe" and argv[1]==="serve".
// This avoids false positives from shell strings that merely contain both words (e.g., comments, echo tests).
// Also handles "node /path/opencode serve" form where opencode is a JS file + serve is next arg.
// TUI has just "opencode\0" => no second arg "serve" => not counted.
function scanServePids() {
  const out = [];
  try {
    for (const e of fs.readdirSync("/proc")) {
      if (!/^[0-9]+$/.test(e)) continue;
      try {
        const cmd = fs.readFileSync(`/proc/${e}/cmdline`, "utf8");
        // Parse into argv parts split on \0 (null) — this is how kernel stores cmdline
        const parts = cmd.split("\x00").filter(Boolean);
        if (parts.includes("--service")) continue; // ignore internal TUI service daemon
        let isServe = false;
        for (let i = 0; i < parts.length; i++) {
          const base = parts[i].split("/").pop();
          // Condition: argv entry is opencode binary and next entry is literally "serve"
          if ((base === "opencode" || base === "opencode.exe") && parts[i + 1] === "serve") { isServe = true; break; }
        }
        // Fallback for node-wrapped opencode: node .../opencode ... serve
        if (!isServe && parts[0] && parts[0].endsWith("node") && parts.some(p => p.includes("opencode")) && parts.includes("serve")) {
          const opIdx = parts.findIndex(p => p.includes("opencode"));
          const serveIdx = parts.indexOf("serve");
          if (opIdx !== -1 && serveIdx > opIdx) isServe = true;
        }
        if (isServe) out.push(parseInt(e, 10));
      } catch {}
    }
  } catch {}
  return out;
}

// Get process uptime in seconds — reads /proc/<pid>/stat field 22 (starttime) and /proc/uptime.
// Used for /api/system/session-info uptime field.
function getProcessUptime(pid) {
  try {
    const stat = fs.readFileSync(`/proc/${pid}/stat`, "utf8");
    // stat format: pid (comm) state ... starttime is field 22 (1-indexed). comm may contain spaces but is inside parentheses.
    // Extract after last ') ' to safely get fields after comm.
    const after = stat.substring(stat.lastIndexOf(")") + 2);
    const parts = after.split(" ");
    // parts[0] is state, so starttime offset: field 22 => index 19 after split (field offset adjustment)
    // Simpler: original split without handling comm is fine because comm has no spaces for opencode, but we keep robust parsing.
    // Field 22 in full stat is parts[19] after this extraction.
    const starttimeTicks = parseInt(parts[19], 10);
    const uptimeSecTotal = parseFloat(fs.readFileSync("/proc/uptime", "utf8").split(" ")[0]);
    const clkTck = 100; // typical Linux CLK_TCK
    const procUptime = Math.floor(uptimeSecTotal - starttimeTicks / clkTck);
    return procUptime >= 0 ? procUptime : null;
  } catch { return null; }
}

// Check if companion meta pid is still alive and is a serve — validates companion-owned claim.
// Uses precise argv parsing (not substring) to avoid counting stale reused PIDs.
function isCompanionPidAlive(pid) {
  if (!pid) return false;
  try {
    // kill -0 checks existence without signal
    process.kill(pid, 0);
    // Also verify cmdline still is a real serve (argv[1]==="serve"), not just any "opencode" string
    const cmd = fs.readFileSync(`/proc/${pid}/cmdline`, "utf8");
    const parts = cmd.split("\x00").filter(Boolean);
    for (let i = 0; i < parts.length; i++) {
      const base = parts[i].split("/").pop();
      if ((base === "opencode" || base === "opencode.exe") && parts[i + 1] === "serve") return true;
    }
    if (parts[0] && parts[0].endsWith("node") && parts.some(p => p.includes("opencode")) && parts.includes("serve")) {
      const opIdx = parts.findIndex(p => p.includes("opencode"));
      const serveIdx = parts.indexOf("serve");
      if (opIdx !== -1 && serveIdx > opIdx) return true;
    }
    return false;
  } catch { return false; }
}

// Classify ownership: termux-native vs companion-owned vs none.
// Logic: if not healthy => none. If healthy and companionMeta pid alive+matches => companion-owned.
// Else if healthy and any serve pid exists but not companion-owned => termux-native (we did not start it, must not launch).
function classifyOwnership(health, servePids) {
  if (!health || !health.healthy || !health.up) return "none";
  // Condition: companion-owned only if we have a persisted pid that is still the active serve
  if (companionMeta && companionMeta.pid && isCompanionPidAlive(companionMeta.pid)) {
    // If multiple serves, check if our pid is among them
    if (servePids.includes(companionMeta.pid)) return "companion-owned";
  }
  // If health is true and we reach here, it's an existing session we did not start => termux-native (do not launch)
  return "termux-native";
}

// Persist companion-owned session after we launch it — records pid, port, startedAt.
function persistCompanionSession(pid) {
  try {
    companionMeta = { pid, port: OPENCODE_PORT, host: OPENCODE_HOST, startedAt: Date.now() };
    // A-4 (BACKEND-BUG-02/04): atómico — .companion-session.json decide la propiedad
    // de la sesión (classifyOwnership); JSON corrupto => reclasificación errónea al arrancar.
    atomicWriteFileSync(COMPANION_SESSION_FILE, companionMeta);
    log.info(`[session] persisted companion-owned pid ${pid} port ${OPENCODE_PORT}`);
  } catch (e) { log.error("[session] persist err", { err: e.message }); }
}

// Clear stale companion meta if pid dead — prevents misclassifying termux-native as companion-owned.
function clearStaleCompanionMetaIfNeeded() {
  if (companionMeta && companionMeta.pid && !isCompanionPidAlive(companionMeta.pid)) {
    log.info(`[session] clearing stale companion meta pid ${companionMeta.pid} (dead)`);
    companionMeta = null;
    try { fs.unlinkSync(COMPANION_SESSION_FILE); } catch {}
  }
}

const MIME = {
  ".html":"text/html; charset=utf-8", ".js":"text/javascript; charset=utf-8",
  ".css":"text/css; charset=utf-8", ".json":"application/json; charset=utf-8",
  ".png":"image/png", ".svg":"image/svg+xml", ".ico":"image/x-icon",
  ".wav":"audio/wav", ".mp3":"audio/mpeg",
};

function send(res, code, body, headers={}){
  res.writeHead(code, withCors(res, { "Access-Control-Allow-Headers": CORS_ALLOW_HEADERS, "Access-Control-Allow-Methods":"GET,POST,PUT,PATCH,DELETE,OPTIONS", ...headers }));
  res.end(body);
  // A-2: contrato del dispatcher — truthy = "respuesta ya enviada". Sin este return,
  // `if (handleXRoute(...)) return;` nunca cortaba y el flujo caía en un handler
  // central que re-enviaba headers (ERR_HTTP_HEADERS_SENT: doble manejo de respuesta).
  return true;
}
// ---- Envelope estándar de TODAS las respuestas JSON /api/* (routers incluidos: pasan por json()) ----
// éxito: { ok:true, data:... }   error: { ok:false, error:{ code, message } }
// Se normaliza AQUÍ (punto único por el que pasan server.js y los 4 routers montados en A-2)
// para garantizar el contrato sin reescribir cada handler. Models.kt (Envelope.error: String?)
// sólo se parsea en 2xx, donde ok:false nunca viaja => convertir error a objeto NO rompe la app.
function errorCodeForStatus(code) {
  const MAP = {
    400: "BAD_REQUEST", 401: "UNAUTHORIZED", 403: "FORBIDDEN", 404: "NOT_FOUND",
    405: "METHOD_NOT_ALLOWED", 409: "CONFLICT", 422: "UNPROCESSABLE_ENTITY",
    500: "INTERNAL_ERROR", 501: "NOT_IMPLEMENTED", 502: "BAD_GATEWAY",
    503: "SERVICE_UNAVAILABLE", 504: "GATEWAY_TIMEOUT"
  };
  return MAP[code] || (code >= 500 ? "SERVER_ERROR" : code >= 400 ? "HTTP_ERROR" : "ERROR");
}
function normalizeEnvelope(code, obj) {
  if (!obj || typeof obj !== "object" || Array.isArray(obj)) return obj;
  // error en string (fail(), handlers legacy, routers) -> error:{code,message}
  if (obj.error != null && typeof obj.error !== "object" && (code >= 400 || obj.ok === false)) {
    const clone = { ...obj };
    // Hay dos convenciones de llamada a fail() conviviendo y NO se pueden arreglar
    // cambiando la firma, porque ambas existen de verdad:
    //   - 11 llamadas del tipo fail("INVALID_ID", "sessionId o formId inválido")
    //   -  6 llamadas del tipo fail("not found")
    // ...y la firma es fail(error, code), o sea MENSAJE primero. Las 11 primeras sacan
    // el mensaje por `code` y el código por `error`, y acababan la respuesta con
    // {code:"sessionId o formId inválido", message:"INVALID_ID"}: al revés.
    // Se nota en la app, que muestra resp.error.message y recibía "INVALID_ID" en vez
    // del motivo, y en cualquier cliente que lea el contrato.
    // Se detecta el cruce por FORMA (un token tipo CODIGO contra una frase con
    // espacios), no por una lista de códigos, para que también cubra los nuevos.
    let errText = String(obj.error);
    let errCode = typeof obj.code === "string" ? obj.code : errorCodeForStatus(code);
    const looksLikeCode = (v) => typeof v === "string" && /^[A-Z][A-Z0-9_]*$/.test(v.trim());
    const looksLikeProse = (v) => typeof v === "string" && /\s/.test(v.trim());
    if (looksLikeCode(errText) && looksLikeProse(errCode)) {
      const swapped = errCode;
      errCode = errText;
      errText = swapped;
    }
    delete clone.code;
    clone.ok = false;
    clone.error = { code: errCode, message: errText.slice(0, 800) };
    return clone;
  }
  // 4xx/5xx sin error explícito -> error genérico con el motivo HTTP (nunca HTML ni cuerpo liso)
  if (code >= 400 && (obj.ok === undefined || obj.ok === false) && obj.error == null) {
    return { ...obj, ok: false, error: { code: errorCodeForStatus(code), message: http.STATUS_CODES[code] || "request failed" } };
  }
  return obj;
}
function json(res, code, obj){ return send(res, code, JSON.stringify(normalizeEnvelope(code, obj)), {"Content-Type":"application/json; charset=utf-8"}); }

// ---- B: recorte del payload de mensajes (2026-09-27) --------------------------
// MEDIDO, no supuesto. El reparto real de los 40,5 MB de /messages:
//   .parts[].url                    24.618 KB (61%)  data URI en base64 (type:"file")
//   .parts[].state.content[].uri   10.720 KB (27%)  data URI en el estado
//   .parts[].text                    1.439 KB
//   .parts[].state.content[].text    1.063 KB
// El 88% son data URI (capturas y adjuntos). Un recorte SOLO de texto largo no hace
// nada: por eso el primero dio 0 partes recortadas y el mismo tamano.
  // ---- A-3: cache de /messages, invalidada por evento (2026-09-27) -------------
  // MEDIDO: 13,9 s / 13,1 s / 14,0 s por llamada, y la app pregunta cada 2 s. El
  // recorte de payload (que bajo 40.530 -> 5.575 KB) no toco el tiempo, porque el coste
  // no es serializar: es releer 1400 mensajes de OpenCode en cada poll.
  //
  // Se cachea el STRING FINAL (ya recortado y normalizado), no la lista. El recorte
  // muta en sitio, asi que cachear la entrada y recortar al servir daria un recorte
  // sobre otro y la segunda vez no habria nada que retirar.
  // Clave de cache. El ?tail=N produce un cuerpo DISTINTO, asi que si compartiera clave
  // con la respuesta completa, un tail envenenaria la completa y al reves. La clave
  // lleva el tail dentro por eso, no por gusto.
  function msgsCacheKey(sid, tail) {
    return tail > 0 ? `${sid}#tail${tail}` : sid;
  }
  const MSGS_CACHE_TTL_MS = 4000;
  const MSGS_CACHE_MAX = 3;
  const msgsCache = new Map();      // sid -> { body, at }
  const msgsInflight = new Map();   // sid -> Promise<{recortado}>, para no duplicar trabajo
  const MSGS_TAIL_MAX = 200;   // tope duro: el limite de pagina de OpenCode
  const msgsTailInflight = new Map();  // clave -> Promise<string>, para el tail tambien
  // Eviccion por antiguedad, reutilizable. Se separo del bloque original porque ahora hay
  // dos-productores (completo y tail) y duplicar la cuenta seria una forma segura de
  // equivocarse al vaciar.
  // _nueva es la clave que se va a insertar: no se borra a si misma al evacuar, que es el
  // caso limite cuando el cache esta lleno y solo queda ella.
  function evictCache(cache, _nueva, max) {
    while (cache.size >= max) {
      const masVieja = cache.keys().next().value;
      if (masVieja === undefined || masVieja === _nueva) break;
      cache.delete(masVieja);
    }
  }
  function invalidarCacheMensajes(sid) {
    if (sid) msgsCache.delete(sid);
  }

const PART_MAX_CHARS = (() => {
  const n = parseInt(process.env.AEGIS_PART_MAX_CHARS || "", 10);
  return Number.isFinite(n) && n > 0 ? n : 8000;
})();

function trimPartText(p, maxChars, stats) {
  if (!p || typeof p !== "object") return p;

  // (1) data URI suelta: 61% del payload, en `url` (type "file") o `image`/`data`.
  for (const campo of ["url", "image", "data"]) {
    const v = p[campo];
    if (typeof v !== "string" || v.slice(0, 5) !== "data:") continue;
    stats.binarios++;
    stats.saved += v.length;
    delete p[campo];
    p.hasBinary = true;
    p.binaryChars = (p.binaryChars || 0) + v.length;
    const mime = (v.slice(5).split(";")[0] || "").trim();
    if (mime && !p.mime) p.mime = mime;
  }

  // (2) data URI dentro de state.content[]: otros 27%.
  const st = p.state;
  if (st && Array.isArray(st.content)) {
    let tocados = 0;
    for (const c of st.content) {
      if (!c || typeof c !== "object") continue;
      if (typeof c.uri === "string" && c.uri.slice(0, 5) === "data:") {
        stats.binarios++;
        stats.saved += c.uri.length;
        const mime = (c.uri.slice(5).split(";")[0] || "").trim();
        delete c.uri;
        tocados++;
        if (mime && !c.mime) c.mime = mime;
      }
    }
    if (tocados > 0) {
      p.hasBinary = true;
      p.binaryInState = (p.binaryInState || 0) + tocados;
    }
  }

  // (3) texto largo: son los bytes que QUEDAN, no los que pesan (6% del total).
  if (typeof p.text === "string" && p.text.length > maxChars) {
    const real = p.text.length;
    stats.textos++;
    stats.saved += real - maxChars;
    p.text = p.text.slice(0, maxChars);
    p.truncated = true;
    p.fullChars = (p.fullChars || 0) + real;
  }
  if (st && typeof st.output === "string" && st.output.length > maxChars) {
    const real = st.output.length;
    stats.textos++;
    stats.saved += real - maxChars;
    st.output = st.output.slice(0, maxChars);
    p.truncated = true;
    p.fullChars = (p.fullChars || 0) + real;
  }
  return p;
}

function trimListForWire(list, maxChars) {
  const stats = { textos: 0, binarios: 0, saved: 0 };
  if (!Array.isArray(list)) return { list, ...stats };
  for (const m of list) {
    if (!m || !Array.isArray(m.parts)) continue;
    for (const p of m.parts) trimPartText(p, maxChars, stats);
  }
  return { list, ...stats };
}

async function proxyWithInjection(req, resRaw, originalBodyBuf) {
  // For opencode message routes (/session/:id/message, /session/:id/prompt etc.), prepend system context block if project has skills.
  // Defensive: sanitize, size-cap, dedup, and never leak debug fields to provider.
  const targetPathCheck = req.url.replace(/^\/opencode/, "") || "/";
  const isMessageRoute = /\/session\/[^\/]+\/(message|prompt|chat)/.test(targetPathCheck);
  if (!isMessageRoute || req.method !== "POST" || !originalBodyBuf || originalBodyBuf.length === 0) return null;
  let parsed;
  try { parsed = JSON.parse(originalBodyBuf.toString("utf8")); } catch { return null; }

  // Normalize OpenCode payload: OpenCode strictly validates schema.
  let modified = false;
  if ("provider" in parsed) {
    delete parsed.provider;
    modified = true;
  }
  if (typeof parsed.model === "string") {
    const m = parsed.model;
    if (m.includes("gemini")) {
      parsed.model = { modelID: m, providerID: "google" };
    } else if (m.includes("gpt")) {
      parsed.model = { modelID: m, providerID: "openai" };
    } else if (m.includes("claude")) {
      parsed.model = { modelID: m, providerID: "anthropic" };
    } else {
      delete parsed.model;
    }
    modified = true;
  }

  // Dedup: if caller already injected (retry) skip to avoid double prefix blowing up size
  try {
    const firstPartText = Array.isArray(parsed.parts) && parsed.parts[0] && typeof parsed.parts[0].text === "string" ? parsed.parts[0].text : "";
    if (firstPartText.startsWith("[SYSTEM CONTEXT") || firstPartText.includes("---\n\n[SYSTEM CONTEXT")) return modified ? Buffer.from(JSON.stringify(parsed)) : null;
    if (typeof parsed.text === "string" && parsed.text.includes("[SYSTEM CONTEXT")) return modified ? Buffer.from(JSON.stringify(parsed)) : null;
    if (typeof parsed.prompt === "string" && parsed.prompt.includes("[SYSTEM CONTEXT")) return modified ? Buffer.from(JSON.stringify(parsed)) : null;
  } catch {}
  // Resolve projectId for this message: from body.projectId, or from sessionId association via projects.json, or header X-Project-Id
  let projectId = parsed.projectId || parsed.projectID || req.headers["x-project-id"] || null;
  const sessionId = parsed.sessionId || parsed.sessionID || targetPathCheck.match(/\/session\/([^\/]+)/)?.[1] || null;
  if (!projectId && sessionId) {
    try {
      const store = loadProjectsStore();
      for (const p of store.projects) {
        if ((p.sessions || []).some(s => String(s.sessionId) === String(sessionId))) { projectId = p.id; break; }
      }
    } catch {}
  }
  // Do NOT fallback to UI_STATE.projectId — sessions created outside a project must remain strictly loose.
  // No pre-guard on the global ponytail file: that layer is delivered by AGENTS.md, not from
  // here. The `!block` check below is now the single authoritative "nothing to inject" condition,
  // which also lets project-less sessions still receive skills / project instructions.
  let block = buildSystemContextBlock(projectId);
  if (!block) return modified ? Buffer.from(JSON.stringify(parsed)) : null;
  // Defensive sanitization: remove control chars that break JSON/provider validation, keep \n \r \t
  // Also normalize: skills/summaries may contain unescaped quotes/backticks/binary
  block = String(block).replace(/[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]/g, " ");
  // Size cap: prevent oversized injection exceeding provider limit (24KB text ~6k tokens)
  const MAX_BLOCK_CHARS = 24000;
  if (block.length > MAX_BLOCK_CHARS) {
    block = block.slice(0, MAX_BLOCK_CHARS) + "\n\n[truncated: context exceeds 24KB cap]";
  }
  // Final size guard: if full payload would exceed 512KB (shouldTryInject boundary), truncate further
  const approxPayloadLen = originalBodyBuf.length + block.length + 256;
  if (approxPayloadLen > 512 * 1024) {
    // Skip injection rather than risk 400 from oversized body
    log.warn(`[proxy] skip injection: payload would exceed 512KB cap (${approxPayloadLen} bytes) for project ${projectId}`);
    return null;
  }
  // Pass system context silently in background via dedicated 'system' field — NEVER into user parts
  if (typeof parsed.system === "string" && parsed.system.trim()) {
    parsed.system = `${block.trim()}\n\n${parsed.system.trim()}`;
  } else {
    parsed.system = block.trim();
  }
  // Do NOT add debug fields to payload sent to provider; keep meta for logging only via header/log
  const outBuf = Buffer.from(JSON.stringify(parsed));
  // Attach project hint for log line via a separate variable, not inside payload
  outBuf._injectedProjectId = projectId;
  return outBuf;
}

function proxyToOpencode(req, res){
  const targetPath = req.url.replace(/^\/opencode/, "") || "/";
  const opts = { hostname: OPENCODE_HOST, port: OPENCODE_PORT, path: targetPath, method: req.method, headers: { ...req.headers, host: `${OPENCODE_HOST}:${OPENCODE_PORT}` } };
  delete opts.headers["accept-encoding"];
  const contentLength = req.headers["content-length"] ? parseInt(req.headers["content-length"]) : null;
  if(contentLength && contentLength > 0){
    log.debug(`[proxy] ${req.method} ${targetPath} streaming ${Math.round(contentLength/1024)}KB via chunks`);
  } else if(req.method==="POST" || req.method==="PUT" || req.method==="PATCH"){
    log.debug(`[proxy] ${req.method} ${targetPath} streaming chunked (sin Content-Length)`);
  }
  // Early intercept for message routes that need skill injection — buffer and create request lazily
  // to avoid creating an initial pr whose 8s guard would race with the injected pr2.
  const len = req.headers["content-length"] ? parseInt(req.headers["content-length"]) : 0;
  const shouldTryInject = len > 0 && len < 512 * 1024 && req.method === "POST" && /\/session\/[^\/]+\/(message|prompt|chat)/.test(targetPath);
  if (shouldTryInject) {
    return (async () => {
      // For LLM-backed message routes, wait for OpenCode. 8s was too short and caused
      // premature 502 before LLM replied; 60s was still too short for AGENTIC turns
      // (tool calls, file reads, commands) which routinely run for minutes — it produced
      // "opencode timeout (injected)" 502s on the Aegis app. Now shares the
      // AEGIS_TURN_TIMEOUT_MS knob with providers.js so both ends of the proxy agree.
      const injectGuardMs = Number(process.env.AEGIS_TURN_TIMEOUT_MS) > 0
        ? Number(process.env.AEGIS_TURN_TIMEOUT_MS)
        : 600000;
      const guard = setTimeout(() => {
        if (!res.headersSent) {
          try { json(res, 502, { error: 'opencode timeout (injected)', hint: `opencode serve no respondió en ${Math.round(injectGuardMs / 1000)}s en ${OPENCODE_HOST}:${OPENCODE_PORT} (ajustable con AEGIS_TURN_TIMEOUT_MS)` }); } catch (_) {}
        }
      }, injectGuardMs);
      try {
        const chunks = [];
        for await (const c of req) chunks.push(c);
        const buf = Buffer.concat(chunks);
        const injected = await proxyWithInjection(req, res, buf);
        const outBuf = injected || buf;
        if (injected) log.info(`[proxy] injected system context for project ${_projectHint(injected)} (${injected.length} bytes) sanitized+capped`);
        const injOpts = { ...opts, headers: { ...opts.headers, "content-length": String(outBuf.length), "Content-Length": String(outBuf.length) } };
        delete injOpts.headers["transfer-encoding"];
        delete injOpts.headers["Transfer-Encoding"];
        const pr2 = http.request(injOpts, (prRes2)=>{
          clearTimeout(guard);
          const h = withCors(res, { ...prRes2.headers, "Access-Control-Allow-Headers": CORS_ALLOW_HEADERS, "Access-Control-Allow-Methods":"GET,POST,PUT,PATCH,DELETE,OPTIONS" });
          if (!res.headersSent) res.writeHead(prRes2.statusCode, h);
          prRes2.on('error', e => { log.error('[proxy inj] prRes error', { err: e.message }); try { res.destroy(); } catch (_) {} });
          prRes2.pipe(res);
        });
        pr2.on("error", e=> {
          clearTimeout(guard);
          log.error('[proxy inj] error', { err: e.message });
          if(!res.headersSent) try { json(res, 502, { error:"opencode unreachable (injected)", detail:String(e) }); } catch(_){}
          else try{ res.end(); }catch(_){}
        });
        // Los timeouts de socket de Node son de INACTIVIDAD: se reinician con cada byte.
        // Durante un turno agéntico (tool calls, comandos) no circula ni un byte, así que
        // los valores hardcodeados de 120s/130s destruían la conexión a mitad de turno y el
        // cliente se quedaba sin respuesta (curl: HTTP 000 a los 125s, 0 bytes). Eran
        // ademas INCONSISTENTES con el guard explícito de arriba, que nunca llegaba a
        // dispararse porque estos lo precedian.
        // Ahora ambos son solo una red de seguridad posterior al guard explicito, para que
        // el guard sea quien responde con un 502 descriptivo en vez de un corte mudo.
        pr2.setTimeout(injectGuardMs + 15000, ()=> { clearTimeout(guard); log.error(`[proxy inj] socket timeout ${Math.round((injectGuardMs+15000)/1000)}s`); try{ pr2.destroy(); }catch(_){} });
        res.setTimeout(injectGuardMs + 20000, () => { clearTimeout(guard); try { res.destroy(); } catch (_) {} });
        pr2.write(outBuf);
        pr2.end();
      } catch (e) {
        clearTimeout(guard);
        log.error(`[proxy] injection path error ${e.message}`);
        if(!res.headersSent) try { json(res, 502, { error:"injection error", detail:String(e) }); } catch(_){}
      }
      function _projectHint(buf) {
        if (buf && buf._injectedProjectId) return buf._injectedProjectId;
        try { const j = JSON.parse(buf.toString("utf8")); return j.projectId || j.projectID || "unknown"; } catch { return "unknown"; }
      }
    })();
  }
  // Non-injected path — original streaming with guard
  const guard = setTimeout(() => {
    if (!res.headersSent) {
      try { json(res, 502, { error: 'opencode timeout', hint: `opencode serve no respondió en 8s en ${OPENCODE_HOST}:${OPENCODE_PORT}` }); } catch (_) {}
    }
    try { pr.destroy(); } catch (_) {}
  }, 8000);
  const pr = http.request(opts, (prRes)=>{
    clearTimeout(guard);
    const h = withCors(res, { ...prRes.headers, "Access-Control-Allow-Headers": CORS_ALLOW_HEADERS, "Access-Control-Allow-Methods":"GET,POST,PUT,PATCH,DELETE,OPTIONS" });
    if (!res.headersSent) res.writeHead(prRes.statusCode, h);
    prRes.on('error', e => { log.error('[proxy] prRes error', { err: e.message }); try { res.destroy(); } catch (_) {} });
    res.on('error', e => { log.error('[proxy] res error', { err: e.message }); try { pr.destroy(); } catch (_) {} });
    req.on('aborted', () => { try { pr.destroy(); } catch (_) {} });
    prRes.pipe(res);
  });
  pr.on("error", e=> {
    clearTimeout(guard);
    log.error('[proxy] error', { err: e.message });
    if(!res.headersSent) {
      try { json(res, 502, { error:"opencode unreachable", detail:String(e), hint:`opencode serve debe estar corriendo en ${OPENCODE_HOST}:${OPENCODE_PORT}. Ejecuta: opencode serve --service  (ó "opencode service start" / "opencode service restart"). Usa --service y NO --port: un serve sin --service no se registra, el CLI choca con EADDRINUSE y /api/info le responde 401.` }); } catch(_){}
    } else try{ res.end(); }catch(_){}
  });
  req.on('error', e => { clearTimeout(guard); log.error('[proxy] req error', { err: e.message }); try { pr.destroy(); } catch (_) {} });
  try { req.pipe(pr); } catch (e) { clearTimeout(guard); log.error('[proxy] pipe err', { err: e.message }); }
  // Mismo problema que en el path inyectado: timeouts de socket por INACTIVIDAD que
  // cortaban la conexión a los 120s/130s durante turnos agénticos. Se derivan del mismo
  // knob para que el guard explicito de 8s sea el que responda con un 502 legible.
  const plainSocketMs = Number(process.env.AEGIS_TURN_TIMEOUT_MS) > 0
    ? Number(process.env.AEGIS_TURN_TIMEOUT_MS)
    : 600000;
  pr.setTimeout(plainSocketMs + 15000, ()=> { clearTimeout(guard); log.error(`[proxy] socket timeout ${Math.round((plainSocketMs+15000)/1000)}s (payload grande)`); try{ pr.destroy(); }catch(_){} });
  res.setTimeout(plainSocketMs + 20000, () => { clearTimeout(guard); log.error(`[proxy] res socket timeout ${Math.round((plainSocketMs+20000)/1000)}s`); try { res.destroy(); } catch (_) {} });
}

// device helpers — portable + Android namespace aware
// En este POCO F3, los comandos Android solo funcionan vía nsenter -t 1 -m (monkey también necesita nsenter)
const ANDROID_CMDS = /\b(pm|input|am|getprop|uiautomator|cmd|dumpsys|settings|monkey)\b/;
const MAX_BUFFER = 50 * 1024 * 1024; // 50 MB para screenshots Base64 1080p/4K
const MAX_JSON_BODY = 55 * 1024 * 1024; // 55 MB límite JSON entrante

function shellExecRaw(cmd, timeout=12000, maxBuffer = MAX_BUFFER){
  return new Promise((resolve)=>{
    exec(cmd, { timeout, maxBuffer }, (err, stdout, stderr)=>{
      // si excede maxBuffer, err.message contiene "maxBuffer"
      if(err && String(err.message).includes("maxBuffer")){
        resolve({ code: 1, stdout: String(stdout||"").slice(0, maxBuffer), stderr: String(stderr||"") + "\n[hub] maxBuffer " + (maxBuffer/1024/1024) + "MB excedido — salida truncada. Usa streaming o reduce calidad.", signal: err.signal || null, killed: !!err.killed, error: err.message, truncated: true });
      } else {
        resolve({ code: err?.code ?? 0, stdout: String(stdout||""), stderr: String(stderr||""), signal: err?.signal || null, killed: !!err?.killed, error: err ? String(err.message) : null });
      }
    });
  });
}
function readJsonBody(req, limit = MAX_JSON_BODY){
  return new Promise((resolve, reject)=>{
    const chunks=[];
    let total=0;
    let aborted=false;
    req.on("data", c=>{
      total += c.length;
      if(total > limit && !aborted){
        aborted=true;
        reject(new Error("JSON body excede " + (limit/1024/1024) + "MB — reduce imagen o usa compresión"));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on("end", ()=>{ try{ resolve(Buffer.concat(chunks).toString("utf8")); }catch(e){ reject(e); } });
    req.on("error", reject);
  });
}
async function runShell(cmd, timeout=12000){
  // si es comando Android y existe nsenter, envolver
  const needsNs = ANDROID_CMDS.test(cmd);
  if(needsNs){
    // prueba nsenter primero (más rápido que su)
    const wrapped = `nsenter -t 1 -m -- sh -c ${JSON.stringify(cmd)} 2>&1`;
    const r = await shellExecRaw(wrapped, timeout);
    // si nsenter no existe o falló con "nsenter: not found", fallback a su/sh directo
    if(r.stdout.includes("nsenter:") || r.stderr.includes("nsenter:") || (r.code===127 && r.stdout==="")){
      const fallback = `su -c ${JSON.stringify(cmd)} 2>&1 || sh -c ${JSON.stringify(cmd)} 2>&1`;
      return shellExecRaw(fallback, timeout);
    }
    return r;
  }
  // comando genérico linux: intenta su luego sh
  const wrapped = `su -c ${JSON.stringify(cmd)} 2>&1 || sh -c ${JSON.stringify(cmd)} 2>&1`;
  return shellExecRaw(wrapped, timeout);
}
async function listProjects(){
  try{
    const entries = fs.readdirSync(PROJECTS_ROOT, {withFileTypes:true});
    return entries.filter(d=>d.isDirectory() && !d.name.startsWith(".")).map(d=>d.name);
  }catch{ return []; }
}

// ---- Contrato HealthData (app: Models.kt HealthData L243-254) — fuente única ----
// Alimenta GET /api/health (EXENTO de token: sonda ligera de keepalive.sh) y
// GET /api/system/health (Control Center, con token). Ligero a propósito: memoria,
// readdir y un solo probe HTTP corto a opencode — SIN su/nsenter/df ni procesos hijos
// (auditoría API-05: antes el healthcheck spawneaba root shells cada 10s).
function formatMb(bytes) { return `${(bytes / (1024 * 1024)).toFixed(1)}MB`; }
// Disponibilidad honesta de un binario (ruta absoluta o nombre en PATH estándar)
function binAvailable(binPath) {
  if (!binPath) return false;
  if (binPath.includes("/")) return fs.existsSync(binPath);
  for (const dir of ["/root/.local/bin", "/usr/local/bin", "/usr/bin", "/bin"]) {
    try { if (fs.existsSync(path.join(dir, binPath))) return true; } catch {}
  }
  return false;
}
async function buildHealthData(opts = {}) {
  const mem = process.memoryUsage();
  // workspace: nº de carpetas de proyecto reales en PROJECTS_ROOT (frente a `workspace`: la ruta)
  let workspaceProjects = 0;
  try { workspaceProjects = (await listProjects()).length; } catch (_) {}
  // skills.installed: lo que SkillManager realmente tiene en disco (nada inventado)
  let skillsInstalled = [];
  try { skillsInstalled = new SkillManager().listInstalled() || []; } catch (_) {}
  // agents: registrados en el pool vs ejecutando ahora mismo
  let agentsRegistered = 0;
  let agentsActive = 0;
  try {
    agentsRegistered = Object.keys(agentPool.registry || {}).length;
    for (const list of (agentPool.active || new Map()).values()) agentsActive += (list || []).length;
  } catch (_) {}
  // jobs: habilitados y con intervalo realmente lanzado + último run (ISO)
  let jobsActive = 0;
  let jobsLastRun = null;
  try {
    for (const [id, job] of jobScheduler.jobs) {
      if (!job || job.enabled === false) continue;
      if (jobScheduler.intervals.has(id)) jobsActive++;
      if (job.lastRun && (!jobsLastRun || job.lastRun > jobsLastRun)) jobsLastRun = job.lastRun;
    }
  } catch (_) {}
  // adapters: opencode sano via probe HTTP
  const adapters = {};
  try {
    const oc = opts.ocHealth || await probeOpencodeHealth();
    for (const p of providerManager.listProviders()) {
      if (p.id === "opencode") adapters[p.id] = oc && oc.healthy ? "healthy" : "down";
    }
  } catch (_) {}
  return {
    server: "running",           // Control Center: ONLINE si server ∈ {running, healthy, ok}
    port: HUB_PORT,
    // SIN pid aqui a proposito: el contrato de HealthData son 10 claves exactas y hay
    // un test que lo fija (tests/regression-correcciones.test.js). El pid va en la
    // CABECERA X-Aegis-Pid, que no altera el JSON. Ver mas abajo por que hace falta.
    uptime: Math.floor(process.uptime()),   // segundos del hub (no del serve opencode)
    memory: { heapUsed: formatMb(mem.heapUsed), heapTotal: formatMb(mem.heapTotal) },
    workspace: PROJECTS_ROOT,
    projects: workspaceProjects,
    agents: { active: agentsActive, registered: agentsRegistered },
    jobs: { active: jobsActive, lastRun: jobsLastRun },
    skills: { installed: skillsInstalled },
    adapters
    // Sin secretos: jamás AEGIS_TOKEN ni rutas/token file en esta respuesta (queda en claro).
  };
}

// A-4 (BACKEND-BUG-01/09-12): invocación segura de los routers montados.
// - await: los routers devuelven PROMESAS en rutas asíncronas; sin await una
//   rechazada era unhandledRejection con la petición colgada (res sin end).
// - try/catch: excepción síncrona dentro de un router (p.ej. getProjectAbsPath()
//   lanzando por projectId inválido) también cierra la respuesta.
// Contrato: literal `false` (o null/undefined sin headers) = "no es mi ruta";
// cualquier otra cosa = manejado. res.headersSent manda: si el handler ya
// escribió headers NUNCA se devuelve "no manejado" (evita doble writeHead).
async function dispatchRoute(handler, req, res, pathname) {
  try {
    const handled = await handler(req, res, pathname, json, readJsonBody);
    if (handled === false || handled == null) return !!res.headersSent;
    return true;
  } catch (e) {
    log.error(`[hub] handler error ${req.method} ${pathname}`, { err: (e && e.stack) || String(e) });
    const msg = String((e && e.message) || e).slice(0, 800);
    if (!res.headersSent) {
      try { json(res, 500, { ok: false, error: { code: "INTERNAL_ERROR", message: msg } }); } catch (_) {}
    } else if (!res.writableEnded) {
      try { res.end(); } catch (_) {}
    }
    return true; // quedó manejada (cerrada): no seguir hacia el fallback central
  }
}

// A-4 (BACKEND-BUG-01/09-12): el dispatcher es ahora una función nombrada envuelta
// abajo por http.createServer(...).catch(...). Antes, cualquier excepción no
// capturada dentro de este callback async (síncrona o promesa RECHAZADA, p.ej.
// scanWorkspace()/getProjectAbsPath() lanzando en las rutas de projectRoutes o
// listModels() rechazando en GET /api/opencode/models) caía en
// process.on('unhandledRejection') y la petición quedaba COLGADA: sin headers y
// sin res.end() — el cliente sufría timeout con respuesta vacía.
/**
 * El modelo por defecto de una sesion nueva: el PRIMERO FREE de la lista de OpenCode.
 *
 * No se escribe el id a mano. La lista la devuelve `opencode serve` y cambia sola (el
 * 2026-09-29 tenia 472 modelos, 39 free), asi que un id fijo envejece: y aqui ya habia
 * uno caducado. MEDIDO: el default de antes era "gemini-3.8-flash-high", que NO esta
 * entre los 472 (existen "gemini-3.8-flash", "google/gemini-3.8-flash" y
 * "antigravity-gemini-3.8-flash", pero no el "-high"). O sea que cada turno de cada
 * sesion nueva viajaba con un modelo inexistente.
 *
 * `listModels()` ya viene ORDENADO (free de OpenCode primero) y ya marca `free`
 * comprobando coste cero, no solo el sufijo "-free" del id. Se reusa esa regla en vez
 * de duplicarla aqui, que es como se desincroniza un default.
 *
 * Con cache caliente (10 min) esto es instantaneo. Con cache frio se acota el tiempo
 * y, si no llega, se devuelve null: es preferible que elija OpenCode a inventar un id.
 */
const PRIMER_FREE_TIMEOUT_MS = 2500;
async function primerModeloFree(timeoutMs = PRIMER_FREE_TIMEOUT_MS) {
  try {
    const lista = await Promise.race([
      opencodeAdapter.listModels().catch(() => []),
      new Promise((r) => setTimeout(() => r([]), timeoutMs))
    ]);
    const libre = Array.isArray(lista) ? lista.find((m) => m && m.free && m.id) : null;
    if (libre) {
      // Se DICE que modelo se eligio. Un default que se aplica en silencio no se
      // puede depurar: si manana un turno sale raro, la primera pregunta es "con que
      // modelo salio", y si el Hub no lo dijo, no hay forma de saberlo sin mirar la
      // sesion uno por uno.
      log.info(`[hub] modelo por defecto de sesion nueva: ${libre.id} (primer free de la lista de OpenCode)`);
      return String(libre.id);
    }
    log.warn("[hub] sin modelo free en la lista de OpenCode: la sesion nueva nace sin modelo y decide OpenCode");
    return null;
  } catch (e) {
    log.warn("[hub] no se pudo resolver el modelo por defecto", { err: e.message });
    return null;
  }
}

async function handleRequest(req, res){
  // timeouts largos para payloads multimodales grandes (video/audio/docs en Base64).
  // Este req.setTimeout es de INACTIVIDAD y se aplica a TODAS las peticiones que entran,
  // incluida la ruta de envío de la app Aegis: durante un turno agéntico no llegan bytes
  // de entrada, así que a los 125s destruía la petición. Mismo bug que los de 120s/130s
  // del proxy. Se deriva del mismo knob para que no vuelva a cutting por debajo del guard.
  const inboundIdleMs = Number(process.env.AEGIS_TURN_TIMEOUT_MS) > 0
    ? Number(process.env.AEGIS_TURN_TIMEOUT_MS)
    : 600000;
  req.setTimeout(inboundIdleMs + 25000, () => { log.error(`[hub] req idle timeout ${Math.round((inboundIdleMs+25000)/1000)}s ${req.url?.slice(0,140)}`); try { res.destroy(); } catch (_) {} });
  res.on('close', () => { /* cleanup */ });
  // CORS: el origin solo se refleja si está en la allowlist (withCors lo lee de res._corsOrigin)
  res._corsOrigin = req.headers.origin || null;
  // Preflight: 204 con Allow-Headers/Methods (el preflight no lleva headers custom, no exige token)
  if(req.method==="OPTIONS"){ return send(res, 204, ""); }
  let url;
  try { url = new URL(req.url, `http://${req.headers.host}`); } catch (e) { return json(res, 400, { error: 'bad url', detail: String(e) }); }
  const pathname = url.pathname;

  // ---- F4: rate limiting /api/* (antes incluso del token: un flood sin token
  // también consume cuota). Exentos: GET /api/health y GET /api/bootstrap/state
  // (justificación en la cabecera de rateLimitCheck). OPTIONS ya salió arriba.
  const isApi = pathname === "/api" || pathname.startsWith("/api/");
  const isPublicHealth = pathname === "/api/health" && req.method === "GET";
  const isBootstrapStatePoll = pathname === "/api/bootstrap/state" && req.method === "GET";
  if (isApi && !isPublicHealth && !isBootstrapStatePoll) {
    const ip = req.socket && req.socket.remoteAddress ? req.socket.remoteAddress : "unknown";
    const pollBucket = isReadOnlyPoll(req.method, pathname);
    const rl = rateLimitCheck(ip, pollBucket ? "poll" : "strict");
    if (!rl.allowed) {
      res.setHeader("Retry-After", String(rl.retryAfterSec));
      const techo = rl.ceiling || RATE_LIMIT_N;
      log.warn(`[ratelimit] 429 ${req.method} ${pathname.slice(0, 120)} desde ${ip} (techo ${techo}/min, cubo ${pollBucket ? "poll" : "estricto"})`);
      return json(res, 429, {
        ok: false,
        error: { code: "RATE_LIMITED", message: `rate limit excedido: ${techo} peticiones/min por IP — reintenta en ${rl.retryAfterSec}s (header Retry-After)` }
      });
    }
  }

  // ---- Auth de TODAS las rutas /api/* Y /opencode/*: exige X-Aegis-Token (única excepción sin token: GET /api/health) ----
  // BACKLOG (F0-F2, cierre A-1): el prefijo /opencode/* (rutas de sesión/mensaje sin
  // /api y el proxy de respaldo) quedaba FUERA del middleware — cualquier proceso
  // local podía invocarlo. Consumidores verificados con grep: la app Android añade
  // X-Aegis-Token a TODAS sus peticiones (ApiClient.authInterceptor + TokenProvider
  // para HttpURLConnection) y los tests/envíos de prueba ya llevan token; scripts de
  // diagnóstico sólo usan /api/health (exento). Nota: el rate limit /api/* NO se
  // toca (exenciones y techo F4 intactos).
  const needsToken = isApi || pathname === "/opencode" || pathname.startsWith("/opencode/");
  if (needsToken && !isPublicHealth && !tokenMatches(req.headers["x-aegis-token"])) {
    return json(res, 403, { ok: false, error: { code: "FORBIDDEN", message: "missing or invalid token" } });
  }

  // 0) Provider API routes
  
  // Phase 3.5 & 4: Route Handlers for skills and projects
  // A-4 (BACKEND-BUG-01/09-12): invocación BLINDADA vía dispatchRoute — await +
  // try/catch + garantía de cierre de respuesta. Contrato único y robusto:
  //   false  = "no es mi ruta" (sigue la siguiente regla)
  //   true / promesa resuelta = "ya respondí" (return del dispatcher)
  //   throw / promesa rechazada = 500 envelope (o res.end() si ya había headers)
  // Si un handler escribió headers pero devolvió undefined/false, dispatchRoute lo
  // trata como manejado => nunca una segunda regla re-envía la respuesta.
  if (await dispatchRoute(handleSkillsRoute, req, res, pathname)) return;
  if (await dispatchRoute(handleProjectRoutes, req, res, pathname)) return;
  // A-2: motores antes huérfanos montados con el mismo contrato (truthy = respuesta ya
  // enviada). Todas sus respuestas pasan por json()/send() => withCors + envelope {ok,error},
  // y van ANTES del fallback 404 JSON central (nunca llegan al SPA text/html).
  if (await dispatchRoute(handleJobRoutes, req, res, pathname)) return;
  if (await dispatchRoute(handleAgentRoutes, req, res, pathname)) return;
  if (await dispatchRoute(handleWorkflowRoutes, req, res, pathname)) return;
  if (await dispatchRoute(handleContentRoutes, req, res, pathname)) return;
  // F1: wizard de bootstrap — mismas reglas que los demás routers (truthy = ya
  // respondí) y ANTES del fallback 404 central (nunca llega al SPA text/html).
  if (await dispatchRoute(handleBootstrapRoute, req, res, pathname)) return;
  // F3: cierre del wizard — misma regla que los demás routers (truthy = ya respondí)
  if (await dispatchRoute(handleSetupRoute, req, res, pathname)) return;

  if(pathname==="/api/providers" && req.method==="GET"){
    return json(res, 200, { ok: true, data: { defaultProvider: providerManager.defaultProvider, providers: providerManager.listProviders() } });
  }
  if(pathname==="/api/providers/default" && req.method==="POST"){
    try {
      const raw = await readJsonBody(req, 4*1024);
      const body = JSON.parse(raw || "{}");
      const id = String(body.provider || body.id || "").toLowerCase().trim();
      if (!id || !providerManager.adapters.has(id)) {
        return json(res, 400, { ok: false, error: `invalid provider: ${id}` });
      }
      const prevDefault = providerManager.defaultProvider;
      providerManager.defaultProvider = id;
      try {
        // A-4 (BACKEND-BUG-02/04): antes fs.writeFileSync directo — a medio escribir,
        // providers.json quedaba corrupto y el hub re-arrancaba con config inválida.
        // Misma salida byte a byte (JSON.stringify(obj,null,2)) vía el helper existente.
        atomicWriteFileSync(path.join(__dirname, "providers.json"), {
          defaultProvider: id,
          providers: providerManager.listProviders()
        });
      } catch (e2) {
        // A-7 (A-4 backlog #4): antes catch silencioso => 200 con la escritura FALLIDA.
        // Se revierte el estado en memoria y se responde 500 envelope (nunca 200 mentiroso).
        providerManager.defaultProvider = prevDefault;
        return json(res, 500, { ok: false, error: `persist providers.json failed: ${e2.message}` });
      }
      return json(res, 200, { ok: true, data: { defaultProvider: id } });
    } catch (e) {
      return json(res, 500, { ok: false, error: String(e) });
    }
  }

  // 0b) Provider-aware Session Creation: POST /opencode/session or POST /api/sessions
  if ((pathname === "/opencode/session" || pathname === "/api/sessions") && req.method === "POST") {
    try {
      const raw = await readJsonBody(req, 64 * 1024);
      const body = JSON.parse(raw || "{}");

      const headerProv = req.headers["x-provider"]
        ? String(req.headers["x-provider"]).toLowerCase().trim()
        : null;
      const headerProj = req.headers["x-project-id"]
        ? sanitizeProjectId(decodeURIComponent(req.headers["x-project-id"]))
        : null;

      let provId = body.provider || headerProv || null;
      // Strict project association: ONLY if body.projectId or headerProj is explicitly provided.
      // NEVER fallback to UI_STATE.projectId or infer from title (prevents auto-linking to 'Agencia de Marketing').
      let projectId = (body.projectId && String(body.projectId).trim()) || (headerProj && String(headerProj).trim()) || null;

      const store = loadProjectsStore();

      if (!provId && projectId) {
        const p = findProject(store, projectId);
        if (p && p.provider) provId = p.provider;
      }
      // NACIMIENTO de la sesion. Antes caia en "antigravity" sin motivo, y eso ya
      // no es un detalle: el id de una sesion nace con prefijo `agy_`, y la ruta de
      // envio (linea ~1616) ata de por vida al proveedor de nacimiento. Es decir: el
      // default de aqui decidia, sin que nadie lo eligiera, que TODOS los chats
      // nuevos iban por AntigravityAdapter — la rama sin aegis-context, sin
      // execWatcher y sin senal de fin de turno. New -> "opencode"; los `agy_` que ya
      // existen se siguen detectando en el envio y no se tocan.
      if (!provId) provId = "opencode";
      // Un proveedor desconocido se rechaza, no se ignora en silencio: antes de quitar
      // Antigravity, mandar provider:"antigravity" devolvia 200 con un id opencode,
      // y un cliente que creyera estar en otro motor no tendria forma de enterarse.
      if (!providerManager.adapters.has(String(provId).toLowerCase())) {
        return json(res, 400, { ok: false, error: { code: "PROVIDER_UNKNOWN",
          message: `unknown provider: ${String(provId).slice(0, 40)} (disponible: opencode)` } });
      }

      const adapter = providerManager.resolveProvider(null, provId, store);
      log.info(`[hub] creating session via ${adapter.id} (title: ${body.title || "untitled"}, project: ${projectId || "none"})`);

      // Modelo de la sesion nueva: el que pida el cliente o, si no, el PRIMERO FREE
      // de la lista de OpenCode. Antes NO se pasaba modelo ninguno —esta llamada solo
      // mandaba {title, projectId}—, asi que la sesion nacia con el default propio de
      // OpenCode, que no es el mismo criterio que ve el usuario en el selector.
      const modeloInicial = (body.model && String(body.model).trim()) || await primerModeloFree();
      const created = await adapter.createSession({
        title: body.title,
        projectId,
        model: modeloInicial || undefined
      });

      const entry = normalizeSessionEntry({
        sessionId: created.id,
        title: created.title,
        createdAt: created.createdAt,
        lastUsed: created.createdAt,
        provider: adapter.id
      });

      if (projectId) {
        await fileMutex.runExclusive(PROJECTS_STORE_FILE, async () => {
          const s = loadProjectsStore();
          const proj = findProject(s, projectId);
          if (proj && !proj.archivedAt) {
            proj.sessions = proj.sessions || [];
            proj.sessions.push(entry);
            saveProjectsStore(s);
          }
        });
      }

      return json(res, 201, {
        ok: true,
        data: entry,
        id: created.id,
        ID: created.id,
        title: created.title,
        createdAt: created.createdAt,
        provider: adapter.id,
        ...(created.raw || {})
      });
    } catch (e) {
      log.error("[hub] createSession error", { err: e.message });
      return json(res, 500, { ok: false, error: `createSession failed: ${e.message}` });
    }
  }

  // 0b2) Provider-aware Session Deletion: DELETE /api/opencode/sessions/:id, DELETE /api/sessions/:id, DELETE /opencode/session/:id, DELETE /opencode/sessions/:id
  const deleteSessionIntercept = pathname.match(/^\/(?:api\/opencode|opencode|api)\/session(?:s)?\/([^\/]+)$/);
  if (deleteSessionIntercept && req.method === "DELETE") {
    const sid = sanitizeProjectId(decodeURIComponent(deleteSessionIntercept[1]));
    // F4: primera línea de validación (regex del contrato) ANTES de tocar store/fs
    if (!isValidId(sid)) return invalidId(res, "session", sid);
    try {
      // 1. Remove atomically from all projects and purge from sessionTitles in projects.json
      await fileMutex.runExclusive(PROJECTS_STORE_FILE, async () => {
        const store = loadProjectsStore();
        let changed = false;
        for (const p of store.projects) {
          const before = (p.sessions || []).length;
          p.sessions = (p.sessions || []).filter(s => s.sessionId !== sid);
          if (p.sessions.length !== before) changed = true;
        }
        if (store.sessionTitles && store.sessionTitles[sid]) {
          delete store.sessionTitles[sid];
          changed = true;
        }
        if (store.sessionPins && store.sessionPins[sid] !== undefined) {
          delete store.sessionPins[sid];
          changed = true;
        }
        if (changed) saveProjectsStore(store);
      });

      // 2) Sesion de OpenCode: el unico caso que queda.
      try {
        if (typeof opencodeAdapter.deleteSession === "function") {
          await opencodeAdapter.deleteSession(sid);
        }
      } catch (e) {
        log.warn(`[hub] opencodeAdapter.deleteSession warning: ${e.message}`);
      }
      return json(res, 200, { ok: true, data: { removed: sid, storagePurged: true }, removed: sid });
    } catch (e) {
      log.error(`[hub] DELETE session error for ${sid}`, { err: e.message });
      return json(res, 500, fail(`delete session failed: ${e.message}`));
    }
  }

  // 0c) Provider-aware Message Send: POST /opencode/session/:id/message or POST /api/sessions/:id/message or POST /api/opencode/sessions/:id/message
  const sendMsgMatch = pathname.match(/^\/(?:opencode|api)\/session(?:s)?\/([^\/]+)\/message$/) ||
                       pathname.match(/^\/api\/opencode\/sessions\/([^\/]+)\/message$/);
  if (sendMsgMatch && req.method === "POST") {
    const sid = sanitizeProjectId(decodeURIComponent(sendMsgMatch[1]));
    // F4: id de sesión validado ANTES de leer body, tocar el store o el adapter
    if (!isValidId(sid)) return invalidId(res, "session", sid);
    const isStream = url.searchParams.get("stream") === "true" ||
                     (req.headers["accept"] && req.headers["accept"].includes("text/event-stream"));
    try {
      // F7: este límite era 512KB y rechazaba CUALQUIER envío con adjuntos
      // (6 archivos × 5MB base64 ≈ 40MB) con "JSON body excede 0.5MB". Se usa
      // el límite global MAX_JSON_BODY (55MB), coherente con MAX_BUFFER.
      const raw = await readJsonBody(req, MAX_JSON_BODY);
      const body = JSON.parse(raw || "{}");

      const headerProvider = req.headers["x-provider"]
        ? String(req.headers["x-provider"]).toLowerCase().trim()
        : null;
      const headerProjectId = req.headers["x-project-id"]
        ? sanitizeProjectId(decodeURIComponent(req.headers["x-project-id"]))
        : null;

      // Strict project association: ONLY use explicit project ID; never fallback to UI_STATE.projectId!
      let pId = (headerProjectId && String(headerProjectId).trim()) ||
                (body.projectId && String(body.projectId).trim()) ||
                null;
      let provId = headerProvider || body.provider || null;
      if (!provId) provId = "opencode";
      // F6: la convención del id de sesión (ses_) prevalece sobre cualquier
      // header X-Provider — el vínculo de proveedor de nacimiento es inamovible.
      const sidConv = sid.startsWith("ses_") ? "opencode" : null;
      if (sidConv) provId = sidConv;

      // Immediate atomic persistence of session association and provider
      await fileMutex.runExclusive(PROJECTS_STORE_FILE, async () => {
        const store = loadProjectsStore();
        let sessionEntry = null;
        let parentProject = null;

        for (const p of store.projects) {
          const found = (p.sessions || []).find((s) => s.sessionId === sid);
          if (found) {
            sessionEntry = found;
            parentProject = p;
            break;
          }
        }

        // Only link to a project if pId was EXPLICITLY given and session wasn't already in a project
        if (pId && !sessionEntry) {
          parentProject = findProject(store, pId);
          if (parentProject) {
            const existingTitle = resolveExistingSessionTitle(store, sid);
            sessionEntry = normalizeSessionEntry({
              sessionId: sid,
              title: existingTitle,
              createdAt: nowIso(),
              lastUsed: nowIso(),
              provider: provId || parentProject.provider || "opencode"
            });
            parentProject.sessions = parentProject.sessions || [];
            parentProject.sessions.push(sessionEntry);
          }
        }

        if (sessionEntry) {
          // F6: vínculo de proveedor INAMOVIBLE. La convención del id repara
          // registros corrompidos; sin convención, sólo se llena el hueco
          // (set-once). El header nunca pisa un vínculo ya existente.
          const sidConv2 = sid.startsWith("ses_") ? "opencode" : null;
          if (sidConv2) sessionEntry.provider = sidConv2;
          else if (!sessionEntry.provider && provId) sessionEntry.provider = provId;
          sessionEntry.lastUsed = nowIso();
          if (!pId && parentProject) pId = parentProject.id;
        }

        saveProjectsStore(store);
      });

      // Auto-update session title from first prompt if title is technical or placeholder
      const promptText = typeof body.text === "string" ? body.text : (body.prompt || (Array.isArray(body.parts) && body.parts[0]?.text) || "");
      if (promptText && promptText.trim()) {
        await fileMutex.runExclusive(PROJECTS_STORE_FILE, async () => {
          const store = loadProjectsStore();
          store.sessionTitles = store.sessionTitles || {};
          const curTitle = store.sessionTitles[sid] || resolveExistingSessionTitle(store, sid);
          if (!curTitle || curTitle.startsWith("companion:") || curTitle.startsWith("session:") || curTitle.startsWith("ses_") || curTitle === "Nuevo chat" || /^[0-9a-fA-F-]{8,}$/.test(curTitle)) {
            const cleanPrompt = promptText.replace(/[\r\n]+/g, " ").trim();
            const autoTitle = cleanPrompt.length > 30 ? cleanPrompt.slice(0, 30).trim() + "…" : cleanPrompt;
            store.sessionTitles[sid] = autoTitle;
            for (const p of store.projects) {
              const found = (p.sessions || []).find(s => s.sessionId === sid);
              if (found) {
                found.title = autoTitle;
                break;
              }
            }
            saveProjectsStore(store);
          }
        });
      }

      body.projectId = pId;
      if (!body.model) {
        // La razon de F0-F2 sigue siendo valida —el enrutado por prompt se elimino a
        // proposito— pero su conclusion ("default de modelo INTENCIONAL y unico")
        // dejo de serlo: el id que se eligio entonces ya no existe en el catalogo.
        // Lo que se mantiene es que no se enruta por prompt. Lo que se cambia es de
        // donde sale el id: de una constante caducada al primer free REAL de OpenCode.
        const libre = await primerModeloFree();
        if (libre) body.model = libre;
        // Si no se pudo resolver (OpenCode caido, o cache frio y lento), NO se
        // inventa nada: se deja el campo tal cual y que OpenCode elija su default.
      }
      const agentMode = body.agent || body.mode || req.headers["x-agent"] || req.headers["x-mode"] || "build";
      body.agent = agentMode;
      body.mode = agentMode;

      const currentStore = loadProjectsStore();
      const adapter = providerManager.resolveProvider(sid, provId, currentStore);

      // Abort controller to terminate backend execution if client closes connection
      const abortCtrl = new AbortController();
      req.on("close", () => {
        if (!res.writableEnded) {
          abortCtrl.abort();
        }
      });

      if (isStream) {
        res.writeHead(200, withCors(res, {
          "Content-Type": "text/event-stream; charset=utf-8",
          "Cache-Control": "no-cache, no-transform",
          "Connection": "keep-alive",
          "Access-Control-Allow-Headers": CORS_ALLOW_HEADERS,
          "Access-Control-Allow-Methods": "GET,POST,PUT,PATCH,DELETE,OPTIONS"
        }));
        // SIN ESTO el cliente no se enteraba de nada hasta el final del turno.
        //
        // En Node, `writeHead()` NO vuelca los bytes al socket: la respuesta queda
        // "corked" hasta el primer `write()` o el `end()`. Y el primer `write()` de
        // esta ruta ocurre dentro de onChunk, que el adaptador de OpenCode solo invoca
        // UNA VEZ, cuando el turno ya ha terminado (providers.js, la llamada a onChunk
        // esta dentro de `if (complete)`). O sea que los headers llegaban al cliente en
        // el mismo instante que el final del turno, no al aceptarse el prompt.
        //
        // Medido en hub.log: 3 min 15 s entre "prompt accepted" y el primer byte.
        // Por eso la píldora "Enviando…" se quedaba minutos con el mensaje ya
        // recibido, y el usuario lo perceives como "el envío tarda mucho".
        res.flushHeaders();
        // Y se manda un evento explícito, para que el cliente marque el ack por
        // CONTRATO y no por sincronismo: el turno arrancó, aunque todavía no haya
        // texto que enseñar.
        if (!res.writableEnded) {
          res.write(`data: ${JSON.stringify({ type: "accepted" })}\n\n`);
        }
      }

      // La sesion queda OCUPADA desde aqui, y no porque lo diga un evento remoto: el Hub
      // acaba de reenviar el prompt, o sea que sabe con certeza que el turno empezo.
      // Antes dependia de que el vigilante viera `session.execution.started` por
      // /api/event, que llega tarde, se pierde al reconectar y no llega si el Hub se
      // reinicio a mitad de turno. Esa dependencia es la causa comun de que el divisor
      // apareciese con un bash corriendo, de que la notificacion disparase antes de
      // tiempo y de que el circulo de carga se apagase con la sesion ocupada.
      markBusy(sid);
      log.info(`[hub] routing message for session ${sid} to provider ${adapter.id} (project: ${pId || "none"}, mode: ${agentMode}, streaming: ${isStream})`);
      const msgResult = await adapter.sendMessage(sid, body, {
        signal: abortCtrl.signal,
        projectId: pId,
        agent: agentMode,
        mode: agentMode,
        onStreamEvent: isStream ? (evt) => {
          if (!res.writableEnded) {
            res.write(`data: ${JSON.stringify(evt)}\n\n`);
          }
        } : null,
        onChunk: isStream ? (chunk) => {
          if (!res.writableEnded) {
            res.write(`data: ${JSON.stringify({ type: "chunk", text: chunk })}\n\n`);
          }
        } : null
      });

      const normalized = normalizeMessage(msgResult, sid);

      if (isStream) {
        if (!res.writableEnded) {
          res.write(`data: ${JSON.stringify({ type: "done", message: normalized })}\n\n`);
          res.end();
        }
        return;
      }

      return json(res, 200, {
        ok: true,
        data: normalized,
        ...normalized
      });
    } catch (e) {
      log.error(`[hub] sendMessage error for ${sid}`, { err: e.message });
      if (isStream && res.headersSent) {
        try {
          res.write(`data: ${JSON.stringify({ type: "error", error: e.message || "Message send failed" })}\n\n`);
          res.end();
        } catch (_) {}
        return;
      }
      const isTimeout = e.message.includes("timed out") || e.message.includes("timeout");
      return json(res, isTimeout ? 504 : 502, {
        ok: false,
        error: e.message || "Message send failed"
      });
    }
  }

  // 1) opencode proxy
  if(pathname.startsWith("/opencode/") || pathname==="/opencode"){
    return proxyToOpencode(req, res);
  }
  // SSE passthrough convenience: /event and /global/event also proxied? keep alias
  if(pathname==="/event" || pathname==="/global/event"){
    req.url = "/opencode" + pathname;
    return proxyToOpencode(req, res);
  }

  // 2a) System Health Diagnostic: Node.js runtime, A11y socket :8766, agy binary connectivity, Termux/Ubuntu permissions
  if (pathname === "/api/system/health" && req.method === "GET") {
    try {
      const runtime = {
        nodeVersion: process.version,
        execPath: process.execPath,
        pid: process.pid,
        uptimeSeconds: Math.round(process.uptime()),
        memoryUsage: process.memoryUsage(),
        arch: process.arch,
        platform: process.platform,
        status: "ok"
      };

      // 1. Accessibility socket probe (localhost:8766)
      const a11ySocket = await new Promise((resolve) => {
        const r = http.get(
          { hostname: "127.0.0.1", port: 8766, path: "/status", timeout: 2000, headers: { "X-Aegis-Token": AEGIS_TOKEN } },
          (resp) => {
            let d = "";
            resp.on("data", (c) => (d += c));
            resp.on("end", () => {
              try {
                resolve({ reachable: true, statusCode: resp.statusCode, details: JSON.parse(d) });
              } catch {
                resolve({ reachable: true, statusCode: resp.statusCode, raw: d });
              }
            });
            resp.on("error", (e) => resolve({ reachable: false, error: e.message }));
          }
        );
        r.on("error", (e) => resolve({ reachable: false, error: e.message }));
        r.setTimeout(2000, () => {
          try { r.destroy(); } catch (_) {}
          resolve({ reachable: false, error: "timeout" });
        });
      });

      // 2. Connectivity with agy binary
      const agyCheck = await new Promise((resolve) => {
        const agyBin = fs.existsSync("/root/.local/bin/agy") ? "/root/.local/bin/agy" : "agy";
        exec(`${agyBin} --version`, { timeout: 3500 }, (err, stdout, stderr) => {
          if (err) {
            resolve({ installed: false, path: agyBin, error: err.message, stderr: stderr.trim() });
          } else {
            resolve({ installed: true, path: agyBin, version: stdout.trim(), canExecute: true });
          }
        });
      });

      // 3. Execution permissions in Termux/Ubuntu
      let canWriteTmp = false;
      let tmpError = null;
      try {
        const tmpTest = `/tmp/.health_test_${Date.now()}`;
        fs.writeFileSync(tmpTest, "ok");
        fs.unlinkSync(tmpTest);
        canWriteTmp = true;
      } catch (e) {
        tmpError = e.message;
      }

      let canWriteProjects = false;
      let projectsError = null;
      try {
        const prjTest = `/sdcard/projects/Aegis/backend/.health_test_${Date.now()}`;
        fs.writeFileSync(prjTest, "ok");
        fs.unlinkSync(prjTest);
        canWriteProjects = true;
      } catch (e) {
        projectsError = e.message;
      }

      const permissions = {
        canWriteTmp,
        tmpError,
        canWriteProjects,
        projectsError,
        uid: process.getuid ? process.getuid() : null,
        gid: process.getgid ? process.getgid() : null,
        isRoot: process.getuid ? process.getuid() === 0 : true,
        cwd: process.cwd()
      };

      // 4. OpenCode daemon status
      const ocHealth = await probeOpencodeHealth();

      const isHealthy = runtime.status === "ok" && canWriteProjects && agyCheck.installed;

      // A-3: data = HealthData (Models.kt) + diagnóstico legacy. La app (Control Center)
      // lee server/port/uptime/memory/workspace/projects/agents/jobs/skills/adapters;
      // sin `server:"running"` la UI marcaba siempre OFFLINE. El resto (runtime, a11ySocket,
      // agy, permissions, opencode) se conserva como superconjunto para QA/docs (curl).
      // Diagnóstico barato heredado de GET /api/health (sin `df`/su: audits API-05;
      // disk ya no se calcula en ningún health — sólo hooks de QA lo miraban).
      clearStaleCompanionMetaIfNeeded();
      const healthOwnership = classifyOwnership(ocHealth, scanServePids());
      let activeProject = null;
      try {
        const st = loadProjectsStore();
        if (UI_STATE.projectId) {
          const pr = findProject(st, UI_STATE.projectId);
          if (pr) activeProject = { id: pr.id, name: pr.name, sessionsCount: (pr.sessions || []).length };
          else activeProject = { id: UI_STATE.projectId, name: UI_STATE.project || null, note: "projectId not found in store" };
        } else if (UI_STATE.project) {
          activeProject = { id: null, name: UI_STATE.project, note: "legacy folder name, no projectId" };
        }
      } catch (_) {}
      const healthData = await buildHealthData({ ocHealth: ocHealth });

      return json(res, 200, {
        ok: true,
        data: {
          ...healthData,
          status: isHealthy ? "healthy" : "degraded",
          timestamp: nowIso(),
          runtime,
          a11ySocket,
          agy: agyCheck,
          permissions,
          opencode: ocHealth,
          sessionOwnership: healthOwnership,
          activeProject,
          lastVoice: VOICE_LOG[0] || null
        }
      });
    } catch (e) {
      return json(res, 500, { ok: false, error: `Health check failed: ${e.message}` });
    }
  }

  // 2b) Memory (Models.kt MemoryData — heapUsed/heapTotal en texto legible)
  if (pathname === "/api/system/memory" && req.method === "GET") {
    const m = process.memoryUsage();
    return json(res, 200, { ok: true, data: { heapUsed: formatMb(m.heapUsed), heapTotal: formatMb(m.heapTotal) } });
  }

  // 2c) Logs (Models.kt LogsResponse — últimas líneas del log REAL del hub, nunca inventadas)
  // F4: fuente = sink rotado del logger (backend/logs/aegis.log + backups .1..3,
  // dir redirigible vía AEGIS_LOG_DIR), NO el hub.log (que es la redirección de
  // stdout de keepalive.sh). Params: ?lines= (default 200, tope 5000) y el
  // legacy ?limit= que sigue enviando ApiService.getSystemLogs(limit=100).
  // Contrato: data = Array<String> (LogsResponse lo exige — NO romper la app) +
  // espejo `logs` (consumidor nuevo) + `note` honesto cuando no hay fichero aún.
  if (pathname === "/api/system/logs" && req.method === "GET") {
    const rawLimit = url.searchParams.get("lines") || url.searchParams.get("limit") || "200";
    const limit = Math.max(1, Math.min(5000, parseInt(rawLimit, 10) || 200));
    try {
      // Orden cronológico: .3 (más viejo) .2 .1 principal (más reciente)
      const logFile = getLogFile();
      const files = [`${logFile}.3`, `${logFile}.2`, `${logFile}.1`, logFile];
      let lines = [];
      let anyFile = false;
      for (const f of files) {
        try {
          if (!fs.existsSync(f)) continue;
          anyFile = true;
          lines = lines.concat(fs.readFileSync(f, "utf8").split("\n").filter(l => l.trim()));
        } catch (_) { /* backup ilegible: se salta, nunca 500 por un .1 roto */ }
      }
      const tail = lines.slice(-limit);
      return json(res, 200, {
        ok: true,
        data: tail,                 // LogsResponse.data: List<String>? (app, SOLO esto parsea Gson)
        logs: tail,                 // espejo documentado para consumidores nuevos (F4)
        ...(anyFile
          ? {}
          : { note: `sin fichero de log todavía: ${logFile} no existe (el sink se crea en el primer mensaje del logger)` })
      });
    } catch (e) {
      return json(res, 500, { ok: false, error: `log read failed: ${e.message}` });
    }
  }

  // 2) API hub — non-destructive session discovery
  // Every /api/system/status call probes health and classifies ownership.
  // Ownership values: "companion-owned" (we launched via keepalive), "termux-native" (existing serve we didn't start), "none" (no serve).
  // This is the single source of truth for whether keepalive may launch.
  if(pathname==="/api/system/status" && req.method==="GET"){
    // Probe opencode health — spec (1): detect if serve already running (termux or companion)
    const ocHealth = await probeOpencodeHealth();
    // Scan serve PIDs via /proc — checks cmdline contains "opencode"+"serve" (not TUI)
    clearStaleCompanionMetaIfNeeded();
    const servePids = scanServePids();
    // Classify ownership — spec (2)
    const sessionOwnership = classifyOwnership(ocHealth, servePids);
    const bridgeHealth = await new Promise(resolve=>{
      http.get({ hostname:"127.0.0.1", port:8766, path:"/status", timeout:1500, headers:{ "X-Aegis-Token": AEGIS_TOKEN } }, r=>{
        let d=""; r.on("data",c=>d+=c); r.on("end", async ()=>{
          let h;
          try {
            const j=JSON.parse(d);
            h = { up:true, a11y: !!j.a11y };
            // Proactive repair: always ensure companion a11y service is in the
            // enabled list. After force-stop Android strips it; the service
            // can't start to flag needsA11yRepair because it's not enabled.
            // Reactive repair (needsA11yRepair flag) covers in-flight clearing.
            try {
              const cur = (await runShell("settings get secure enabled_accessibility_services")).stdout.trim();
              const me = "com.aegis.hub/com.aegis.hub.OpencodeAccessibilityService";
              const parts = cur.split(":").map(x=>x.trim()).filter(x=>x && x!==me);
              if (!cur.split(":").map(x=>x.trim()).includes(me)) {
                parts.push(me);
                await runShell("settings put secure enabled_accessibility_services " + parts.join(":"));
                await runShell("settings put secure accessibility_enabled 1");
                h.a11yRepaired = true;
              }
              // Also handle the bridge-reported flag (covers edge cases)
              if (j.needsA11yRepair && !h.a11yRepaired) {
                h.a11yRepaired = true;
              }
            } catch(_e) { h.a11yRepairError = String((_e&&_e.message)||_e).slice(0,120); }
          } catch(_e) { h = { up:true, a11y:false }; }
          resolve(h);
        });
      }).on("error", ()=> resolve({ up:false, a11y:false })).end();
    });
    const ready = ocHealth.healthy && ocHealth.up;
    // Build sessionInfo for inline inclusion — detailed info available via /api/system/session-info
    const primaryPid = servePids[0] || (companionMeta && companionMeta.pid) || null;
    return json(res, 200, {
      ready,
      hub:`http://127.0.0.1:${HUB_PORT}`,
      opencode: ocHealth,
      bridge: bridgeHealth,
      // Session ownership discovery — spec (2): values "termux-native" | "companion-owned" | "none"
      sessionOwnership,
      // Inline session info (light) — full data via /api/system/session-info which includes uptime
      sessionInfo: primaryPid ? { pid: primaryPid, ownership: sessionOwnership, port: OPENCODE_PORT } : null,
      hint: ready ? `ready (${sessionOwnership})` : (sessionOwnership === "none" ? "no serve — POST /api/system/start will launch (only when none)" : `existing ${sessionOwnership} session detected — not launching`)
    });
  }
  // Detailed session info route — spec (4): PID, ownership, uptime, port
  // Does NOT trigger launch — read-only probe, safe for polling.
  if(pathname==="/api/system/session-info" && req.method==="GET"){
    const health = await probeOpencodeHealth();
    clearStaleCompanionMetaIfNeeded();
    const servePids = scanServePids();
    const ownership = classifyOwnership(health, servePids);
    // Primary pid: companion-owned uses persisted pid, termux-native uses first scanned serve pid
    let pid = null;
    if (ownership === "companion-owned" && companionMeta && companionMeta.pid) pid = companionMeta.pid;
    else if (servePids.length) pid = servePids[0];
    const uptime = pid ? getProcessUptime(pid) : null;
    return json(res, 200, { ok: true, data: {
      // PID of the detected serve process (null if none)
      pid,
      // Ownership classification — see classifyOwnership logic
      ownership,
      // Uptime in seconds since process start (null if unknown/pid missing)
      uptime,
      // Port the serve is listening on (from HUB config)
      port: OPENCODE_PORT,
      host: OPENCODE_HOST,
      // All detected serve pids (for debugging)
      allServePids: servePids,
      // Persisted companion meta (if any)
      companionMeta,
      health,
      hint: ownership === "none" ? "no serve running — safe to launch via POST /api/system/start" : `${ownership} session pid ${pid} uptime ${uptime !== null ? uptime + 's' : 'unknown'}`
    }});
  }
  // Non-destructive launch gate — spec (3): only launch when sessionOwnership is "none", never when "termux-native"
  if(pathname==="/api/system/start" && req.method==="POST"){
    await readJsonBody(req, 64*1024).catch(()=> "{}");
    // Check ownership BEFORE deciding to launch — do not destroy existing termux session
    const preHealth = await probeOpencodeHealth();
    clearStaleCompanionMetaIfNeeded();
    const preServePids = scanServePids();
    const preOwnership = classifyOwnership(preHealth, preServePids);
    // Spec (3): if already healthy with any ownership except "none", do not launch via keepalive
    if (preHealth.healthy && preOwnership !== "none") {
      log.info(`[system] POST /api/system/start — skipped launch: existing ${preOwnership} session pid ${preServePids[0] || preHealth.version || ''} already healthy (non-destructive)`);
      return json(res, 200, { ok: true, data: {
        started: false,
        skipped: true,
        reason: `Existing ${preOwnership} session already running — not launching (non-destructive policy)`,
        healthy: true,
        sessionOwnership: preOwnership,
        opencode:`http://${OPENCODE_HOST}:${OPENCODE_PORT}`,
        steps: [`skip: existing ${preOwnership} healthy, pid ${preServePids[0] || 'unknown'}`],
        alreadyHealthy: true,
        next:"Use GET /api/system/session-info for PID/uptime details"
      }});
    }
    log.info('[system] POST /api/system/start — sessionOwnership none, invoking keepalive + opencode ensure (host namespace)');
    const steps = [];
    async function ensureOpencode(){
      const health = await probeOpencodeHealth();
      if(health.healthy) { steps.push("opencode: already healthy (re-check)"); return { already:true }; }
      steps.push("opencode: not healthy — launching via host keepalive.sh (only when none)");
      // keepalive.sh y opencode deben lanzarse en HOST (donde existe /usr/bin/node), no en system (nsenter -t 1 -m no ve /usr/bin/node)
      // This path is only reached when ownership is "none" — safe per spec (3)
      const r1 = await shellExecRaw(`nohup sh /sdcard/projects/Aegis/backend/keepalive.sh > /sdcard/projects/Aegis/backend/keepalive.log 2>&1 & echo keepalive_pid=$!`, 8000, 1024*1024);
      steps.push(`keepalive.sh: ${r1.stdout.trim().slice(0,300)} ${r1.stderr.trim().slice(0,200)}`);
      // Direct launch fallback — also only when none; persist companion-owned meta so future probes classify correctly
      // --service (no --port/--hostname): debe ser el servicio GESTIONADO. Un serve
      // "ajeno" no se registra, el CLI no lo ve, choca con EADDRINUSE y además el
      // CLI no conoce su contraseña Basic rotada por arranque -> /api/info 401 ->
      // "Timed out waiting for the background service to start". Ver keepalive.sh.
      const r2 = await shellExecRaw(`HOME=/root nohup opencode serve --service >> /sdcard/projects/Aegis/backend/opencode.log 2>&1 & echo opencode_direct_pid=$!`, 8000, 1024*1024);
      steps.push(`opencode direct: ${r2.stdout.trim().slice(0,300)} ${r2.stderr.trim().slice(0,200)}`);
      // Extract pid from "opencode_direct_pid=12345" and persist as companion-owned
      const m = r2.stdout.match(/opencode_direct_pid=(\d+)/);
      if (m) {
        const newPid = parseInt(m[1], 10);
        // Give process a moment to actually start before probing
        await new Promise(r => setTimeout(r, 500));
        persistCompanionSession(newPid);
        steps.push(`persisted companion-owned pid ${newPid}`);
      }
      await runShell(`dumpsys deviceidle whitelist +com.aegis.hub 2>&1 | head -n 3; cmd deviceidle whitelist +com.aegis.hub 2>&1 | head -n 3; am set-standby-bucket com.aegis.hub active 2>&1 | head -n 3`).then(r=> steps.push(`whitelist: ${r.stdout.trim().slice(0,200)}`)).catch(()=>{});
      return { already:false };
    }
    const ens = await ensureOpencode();
    // poll hasta healthy o timeout 25s
    let healthy=false;
    for(let i=0;i<12;i++){
      await new Promise(r=> setTimeout(r, 2100));
      const h = await probeOpencodeHealth();
      if(h.healthy){ healthy=true; break; }
    }
    steps.push(healthy ? "poll: healthy after retry" : "poll: timeout 25s not healthy");
    // Re-classify after poll so response includes new ownership
    const postHealth = await probeOpencodeHealth();
    const postPids = scanServePids();
    const postOwnership = classifyOwnership(postHealth, postPids);
    return json(res, healthy ? 200 : 202, { ok: true, data: { started: !ens.already && healthy, healthy, sessionOwnership: postOwnership, opencode:`http://${OPENCODE_HOST}:${OPENCODE_PORT}`, steps, alreadyHealthy: ens.already, next:"Poll GET /api/system/status until {ready:true} or GET /api/system/session-info for details" } });
  }
  if(pathname==="/api/ui/state" && req.method==="GET"){
    return json(res, 200, { ok: true, data: { ...UI_STATE } });
  }
  if(pathname==="/api/ui/state" && req.method==="PATCH"){
    try {
      const raw = await readJsonBody(req, 64*1024);
      const body = JSON.parse(raw || "{}");
      // Legacy folder name compat
      if ("project" in body) UI_STATE.project = body.project || null;
      // New: active companion project id (spec 5) + session id
      if ("projectId" in body) UI_STATE.projectId = body.projectId || null;
      if ("sessionId" in body) UI_STATE.sessionId = body.sessionId || null;
      saveUiState();
      return json(res, 200, { ok: true, data: { ...UI_STATE } });
    } catch (e) { return json(res, 400, { error: String(e).slice(0,400) }); }
  }

  // ---- Companion Projects REST — projects.json persistent store (spec 1-2, envelope spec 6) ----
  // Helper: send ok envelope consistently
  function ok(data) { return { ok: true, data }; }
  function fail(error, code) { return { ok: false, error: String(error).slice(0, 800), code }; }

  // GET /api/projects — list all non-archived (includeArchived=1 includes archived)
  if(pathname==="/api/projects" && req.method==="GET"){
    // Serve both legacy folder scan and companion managed store depending on query.
    // Default: companion managed projects (spec: projects.json). Legacy folder scan via ?source=fs
    const source = url.searchParams.get("source");
    const includeArchived = url.searchParams.get("includeArchived") === "1" || url.searchParams.get("all") === "1";
    if (source === "fs") {
      // Legacy: scan filesystem folders under PROJECTS_ROOT
      const dirs = await listProjects();
      const infos = dirs.map(name=>{
        try{
          const p = path.join(PROJECTS_ROOT, name);
          const pkg = fs.existsSync(path.join(p,"package.json")) ? JSON.parse(fs.readFileSync(path.join(p,"package.json"),"utf8")) : null;
          const git = fs.existsSync(path.join(p,".git"));
          return { name, path:p, hasPackage:!!pkg, description: pkg?.description||"", git };
        }catch{ return { name, path: path.join(PROJECTS_ROOT,name) }; }
      });
      return json(res, 200, ok(infos));
    }
    const store = loadProjectsStore();
    let list = store.projects;
    if (!includeArchived) list = list.filter(p => !p.archivedAt);
    list = list.map(p => {
      const safeFolder = p.folder || (p.name ? path.join(PROJECTS_ROOT, String(p.name).toLowerCase().replace(/\s+/g, "-").replace(/[^a-z0-9_-]/g, "")) : null);
      return {
        ...p,
        folder: safeFolder,
        ponytail: p.ponytail || (safeFolder ? path.join(safeFolder, ".ponytail.md") : null)
      };
    });
    // Sort: active first by createdAt desc, then archived
    list = [...list].sort((a,b) => (b.createdAt || "").localeCompare(a.createdAt || ""));
    return json(res, 200, ok(list));
  }

  // POST /api/projects — create {name, description, instructions?, provider?, skills?, linkedProjects?}
  if(pathname==="/api/projects" && req.method==="POST"){
    try {
      const raw = await readJsonBody(req, 64*1024);
      const body = JSON.parse(raw || "{}");
      const err = validateProjectPayload(body, true);
      if (err) return json(res, 400, fail(err));

      const headerProv = req.headers["x-provider"] ? String(req.headers["x-provider"]).toLowerCase().trim() : null;
      const initialProv = (body.provider && String(body.provider).toLowerCase().trim() === "opencode")
        ? "opencode"
        : (headerProv && headerProv === "opencode" ? headerProv : "opencode");

      let createdProj = null;
      await fileMutex.runExclusive(PROJECTS_STORE_FILE, async () => {
        const store = loadProjectsStore();
        const normName = String(body.name).trim();
        if (store.projects.some(p => !p.archivedAt && p.name.toLowerCase() === normName.toLowerCase())) {
          throw new Error(`DUPLICATE_NAME: project name "${normName}" already exists`);
        }
        // Carpeta a adoptar. "Vincular carpeta" permite registrar un proyecto sobre un
        // directorio YA existente en /sdcard/projects en vez de crear uno nuevo con el
        // nombre saneado. Sin esto, "Petite Raw" creaba la carpeta "petite-raw" y
        // duplicaba el proyecto en vez de adopts la original.
        //
        // SEGURIDAD: la ruta debe resolver DENTRO de PROJECTS_ROOT. Sin esta
        // contención, un body con ../../etc o /data lo convertiría en path traversal
        // arbitrario (lectura y escritura de .hub/project.json y .ponytail.md).
        let folderPath;
        const requested = (body.folder ?? body.directory);
        if (requested !== undefined && requested !== null && String(requested).trim() !== "") {
          const raw = String(requested).trim();
          const resolved = path.resolve(raw);
          const rootResolved = path.resolve(PROJECTS_ROOT);
          if (resolved !== rootResolved && !resolved.startsWith(rootResolved + path.sep)) {
            throw new Error(`FOLDER_OUTSIDE_ROOT: "${raw}" está fuera de ${PROJECTS_ROOT}`);
          }
          if (fs.existsSync(resolved) && !fs.statSync(resolved).isDirectory()) {
            throw new Error(`FOLDER_NOT_A_DIRECTORY: "${raw}"`);
          }
          // Una carpeta no puede pertenecer a dos proyectos: el Hub mostraría el mismo
          // contenido dos veces y los .ponytail.md de ambos colisionarían.
          const claimed = store.projects.find(p => !p.archivedAt && p.folder && path.resolve(p.folder) === resolved);
          if (claimed) throw new Error(`DUPLICATE_FOLDER: la carpeta ya pertenece al proyecto "${claimed.name}"`);
          folderPath = resolved;
        } else {
          const safeFolderName = normName.toLowerCase().replace(/\s+/g, "-").replace(/[^a-z0-9_-]/g, "");
          folderPath = path.join(PROJECTS_ROOT, safeFolderName);
        }
        const ponyTailPath = path.join(folderPath, ".ponytail.md");
        createdProj = {
          id: genProjectId(),
          name: normName,
          description: String(body.description || "").trim(),
          instructions: String(body.instructions || "").trim(),
          createdAt: nowIso(),
          archivedAt: null,
          provider: initialProv,
          folder: folderPath,
          directory: folderPath,
          ponytail: ponyTailPath,
          sessions: [],
          skills: Array.isArray(body.skills) ? body.skills : [],
          linkedProjects: Array.isArray(body.linkedProjects) ? body.linkedProjects : []
        };
        store.projects.push(createdProj);
        saveProjectsStore(store);
      });

      // Initialize project directory on disk (PROJECTS_ROOT), .hub/project.json and .ponytail.md
      try {
        const projDir = createdProj.folder;
        if (!fs.existsSync(projDir)) {
          fs.mkdirSync(projDir, { recursive: true });
        }
        
        // .hub/project.json
        const hubDir = path.join(projDir, ".hub");
        if (!fs.existsSync(hubDir)) {
          fs.mkdirSync(hubDir, { recursive: true });
        }
        const hubProjectJson = path.join(hubDir, "project.json");
        if (!fs.existsSync(hubProjectJson)) {
          fs.writeFileSync(
            hubProjectJson,
            JSON.stringify({
              id: createdProj.id,
              name: createdProj.name,
              provider: createdProj.provider,
              created_at: createdProj.createdAt,
              aegis_version: "1.0.0"
            }, null, 2)
          );
        }

        // Infer project type & stack
        const descLower = (createdProj.description || "").toLowerCase();
        const nameLower = createdProj.name.toLowerCase();
        let inferredType = "other";
        let inferredStack = "To be defined";
        if (nameLower.includes("bot") || descLower.includes("trading") || descLower.includes("bot")) {
          inferredType = "trading system";
          inferredStack = "Python / Node.js";
        } else if (nameLower.includes("app") || descLower.includes("android") || descLower.includes("mobile")) {
          inferredType = "mobile app";
          inferredStack = "Kotlin / Jetpack Compose";
        } else if (nameLower.includes("api") || descLower.includes("backend") || descLower.includes("api")) {
          inferredType = "API / backend service";
          inferredStack = "Node.js / Express";
        } else if (nameLower.includes("web") || descLower.includes("frontend") || descLower.includes("web")) {
          inferredType = "web app";
          inferredStack = "HTML / TypeScript / React";
        }

        const ponyTailFile = path.join(projDir, ".ponytail.md");
        if (!fs.existsSync(ponyTailFile)) {
          fs.writeFileSync(
            ponyTailFile,
            `# PONYTAIL — ${createdProj.name}
**Location:** \`${ponyTailFile}\`
**Inherits:** \`/sdcard/projects/ponytail-global.md\`
**Created:** ${createdProj.createdAt}
**Provider:** ${createdProj.provider}
**Last Updated by AI:** ${createdProj.createdAt}

---

## 1. Project Identity
- **Type:** ${inferredType}
- **Stack:** ${inferredStack}
- **Purpose:** ${createdProj.description || "To be defined"}
- **Active since:** ${createdProj.createdAt.split("T")[0]}

---

## 2. Workspace
- **Project root:** \`${projDir}/\`
- **Hub metadata:** \`${path.join(projDir, ".hub", "project.json")}\`
- **Aegis project ID:** ${createdProj.id}
- **Provider:** ${createdProj.provider}

---

## 3. Verified Milestones
*(AI must update this section after completing verified work in this project)*

- [ ] Project initialized

---

## 4. Key Files & Structure
*(AI must populate this section after first exploration of the project)*

- To be discovered on first session.

---

## 5. Notes & Constraints
*(AI must add relevant constraints, tech debt, or important decisions here)*

- None yet.

---

## Update Instructions
After completing any verified milestone in this project:
1. Add it to §3 with a checkmark and date.
2. Update §4 if new key files were created or discovered.
3. Add any important decisions or constraints to §5.
4. Update \`Last Updated by AI\` in the header.
Do NOT modify \`/sdcard/projects/ponytail-global.md\`.
`
          );
        }

        // Log operation to /sdcard/projects/_system/fs-operations.log
        const fsLog = "/sdcard/projects/_system/fs-operations.log";
        const ts = new Date().toISOString();
        const logLines = `[${ts}] [AEGIS-HUB] [CREATE] ${projDir}/ created for project ${createdProj.id}\n[${ts}] [AEGIS-HUB] [CREATE] ${ponyTailFile} generated\n`;
        try {
          fs.appendFileSync(fsLog, logLines);
        } catch (_) {}
      } catch (err) {
        log.warn(`[hub] failed to initialize project directory on disk: ${err.message}`);
      }

      return json(res, 201, { ok: true, data: createdProj });
    } catch (e) {
      if (String(e.message).includes("DUPLICATE_NAME")) {
        return json(res, 409, fail(e.message));
      }
      return json(res, 500, fail(String(e)));
    }
  }

  // PATCH /api/projects/:id — update name/description/instructions/archive (archivedAt toggle), also skills/linked
  if(pathname.startsWith("/api/projects/") && req.method==="PATCH" && !pathname.includes("/sessions")){
    const m = pathname.match(/^\/api\/projects\/([^\/]+)$/);
    if (!m) return json(res, 404, fail("not found"));
    const id = sanitizeProjectId(decodeURIComponent(m[1]));
    if (!isValidId(id)) return invalidId(res, "project", id); // F4: antes del store
    try {
      const raw = await readJsonBody(req, 64*1024);
      const body = JSON.parse(raw || "{}");
      const err = validateProjectPayload(body, false);
      if (err) return json(res, 400, fail(err));

      const headerProv = req.headers["x-provider"] ? String(req.headers["x-provider"]).toLowerCase().trim() : null;

      let updatedProj = null;
      await fileMutex.runExclusive(PROJECTS_STORE_FILE, async () => {
        const store = loadProjectsStore();
        const proj = findProject(store, id);
        if (!proj) throw new Error(`NOT_FOUND: project ${id} not found`);

        if (body.name !== undefined) {
          const newName = String(body.name).trim();
          if (!newName) throw new Error("VALIDATION: name cannot be empty");
          if (store.projects.some(p => p.id !== id && !p.archivedAt && p.name.toLowerCase() === newName.toLowerCase())) {
            throw new Error(`DUPLICATE: project name "${newName}" already exists`);
          }
          proj.name = newName;
        }
        if (body.description !== undefined) proj.description = String(body.description || "").trim();
        if (body.instructions !== undefined) proj.instructions = String(body.instructions || "").trim();
        if (body.provider !== undefined) {
          const prov = String(body.provider).toLowerCase().trim();
          if (prov !== "opencode") return json(res, 400, { ok: false, error: { code: "PROVIDER_UNKNOWN", message: `unknown provider: ${prov} (disponible: opencode)` } });
          proj.provider = prov;
        } else if (headerProv) {
          if (headerProv !== "opencode") return json(res, 400, { ok: false, error: { code: "PROVIDER_UNKNOWN", message: `unknown provider: ${headerProv} (disponible: opencode)` } });
          proj.provider = headerProv;
        }
        if (body.archived !== undefined || body.archivedAt !== undefined) {
          const shouldArchive = body.archived === true || (body.archivedAt !== undefined && body.archivedAt !== null);
          if (shouldArchive && !proj.archivedAt) proj.archivedAt = nowIso();
          else if (!shouldArchive) proj.archivedAt = null;
          else if (body.archivedAt) proj.archivedAt = body.archivedAt;
        }
        if (body.skills !== undefined) proj.skills = Array.isArray(body.skills) ? body.skills : [];
        if (body.linkedProjects !== undefined) proj.linkedProjects = Array.isArray(body.linkedProjects) ? body.linkedProjects : [];
        if (body.directory !== undefined) proj.directory = body.directory === null ? null : (String(body.directory).trim() || null);
        if (body.sessions !== undefined) {
          if (!Array.isArray(body.sessions)) throw new Error("VALIDATION: sessions must be array");
          proj.sessions = body.sessions.map(normalizeSessionEntry).filter(s => s.sessionId);
        }
        saveProjectsStore(store);
        updatedProj = proj;
      });

      return json(res, 200, { ok: true, data: updatedProj });
    } catch (e) {
      if (e.message.startsWith("NOT_FOUND")) return json(res, 404, fail(e.message));
      if (e.message.startsWith("DUPLICATE")) return json(res, 409, fail(e.message));
      if (e.message.startsWith("VALIDATION")) return json(res, 400, fail(e.message));
      return json(res, 500, fail(String(e)));
    }
  }

  // DELETE /api/projects/:id — soft delete: set archivedAt timestamp (spec 2)
  if(pathname.match(/^\/api\/projects\/[^\/]+$/) && req.method==="DELETE" && !pathname.includes("/sessions")){
    const m = pathname.match(/^\/api\/projects\/([^\/]+)$/);
    if (!m) return json(res, 404, fail("not found"));
    const id = sanitizeProjectId(decodeURIComponent(m[1]));
    if (!isValidId(id)) return invalidId(res, "project", id); // F4: antes del store
    try {
      let resultProj = null;
      await fileMutex.runExclusive(PROJECTS_STORE_FILE, async () => {
        const store = loadProjectsStore();
        const proj = findProject(store, id);
        if (!proj) throw new Error(`NOT_FOUND: project ${id} not found`);
        if (!proj.archivedAt) {
          proj.archivedAt = nowIso();
          saveProjectsStore(store);
        }
        resultProj = proj;
      });
      return json(res, 200, { ok: true, data: resultProj });
    } catch (e) {
      if (e.message.startsWith("NOT_FOUND")) return json(res, 404, fail(e.message));
      return json(res, 500, fail(String(e)));
    }
  }

  // GET /api/projects/:id/sessions — list sessions for a project
  // F6: además del registro, mergea en vivo las sesiones de OpenCode nacidas en
  // la CARPETA del proyecto (directory explícito > linkedProjects con ruta >
  // PROJECTS_ROOT/<nombre>, que POST /api/projects crea en disco). Single-parent:
  // una sesión ya vinculada a otro proyecto no se duplica.
  if(pathname.match(/^\/api\/projects\/[^\/]+\/sessions$/) && req.method==="GET"){
    const m = pathname.match(/^\/api\/projects\/([^\/]+)\/sessions$/);
    const id = sanitizeProjectId(decodeURIComponent(m[1]));
    if (!isValidId(id)) return invalidId(res, "project", id); // F4: antes del store
    const store0 = loadProjectsStore();
    const proj0 = findProject(store0, id);
    if (!proj0) return json(res, 404, fail(`project ${id} not found`));

    if (!proj0.archivedAt) {
      try {
        const dirs = projectCandidateDirs(proj0);
        if (dirs.length > 0) {
          const live = await opencodeAdapter.listSessions();
          const elsewhere = new Set();
          for (const p of store0.projects) {
            if (p.id === id) continue;
            for (const se of (p.sessions || [])) elsewhere.add(se.sessionId);
          }
          const mine = new Set((proj0.sessions || []).map(se => se.sessionId));
          const toAdd = live.filter(s => s.directory && !elsewhere.has(s.id) && !mine.has(s.id) &&
            dirs.some(d => s.directory === d || s.directory.startsWith(d + "/")));
          if (toAdd.length > 0) {
            await fileMutex.runExclusive(PROJECTS_STORE_FILE, async () => {
              const st = loadProjectsStore();
              const p = findProject(st, id);
              if (!p || p.archivedAt) return;
              p.sessions = p.sessions || [];
              for (const s of toAdd) {
                if (p.sessions.some(se => se.sessionId === s.id)) continue;
                const custom = resolveExistingSessionTitle(st, s.id, null);
                const title = (custom && custom !== s.id) ? custom : s.title;
                p.sessions.push(normalizeSessionEntry({
                  sessionId: s.id,
                  title,
                  createdAt: s.createdAt,
                  lastUsed: s.updatedAt || s.createdAt,
                  provider: "opencode"
                }));
                log.info(`[hub] F6: sesion ${s.id} ("${title}") vinculada al proyecto ${id} por carpeta (${s.directory})`);
              }
              saveProjectsStore(st);
            });
          }
        }
      } catch (e) {
        log.warn("[hub] project sessions live-merge failed", { err: e.message });
      }
    }
    const store = loadProjectsStore();
    const proj = findProject(store, id) || proj0;
    const sorted = [...(proj.sessions || [])].map(s => ({
      ...s,
      pinned: s.pinned !== undefined ? Boolean(s.pinned) : resolveExistingSessionPinned(store, s.sessionId)
    })).sort((a,b) => (b.lastUsed || b.createdAt || "").localeCompare(a.lastUsed || a.createdAt || ""));
    return json(res, 200, { ok: true, data: sorted });
  }

  // POST /api/projects/:id/sessions — associate existing opencode sessionId to project (or create new if empty)
  // Body: {sessionId?, title?, summary?, provider?}
  if(pathname.match(/^\/api\/projects\/[^\/]+\/sessions$/) && req.method==="POST"){
    try {
      const m = pathname.match(/^\/api\/projects\/([^\/]+)\/sessions$/);
      const id = sanitizeProjectId(decodeURIComponent(m[1]));
      if (!isValidId(id)) return invalidId(res, "project", id); // F4: antes de crear/asociar
      const raw = await readJsonBody(req, 64*1024);
      const body = JSON.parse(raw || "{}");
      let sessionId = String(body.sessionId || body.id || "").trim();
      const headerProv = req.headers["x-provider"] ? String(req.headers["x-provider"]).toLowerCase().trim() : null;

      let createdEntry = null;

      // A-4 (BACKEND-BUG-11): la E/S del proveedor (adapter.createSession — HTTP a
      // opencode con timeout de 8s, o I/O de fs) SALE de la sección crítica del lock.
      // Antes ocurría DENTRO de fileMutex.runExclusive(PROJECTS_STORE_FILE) y, como
      // projects.json es la tabla maestra de sesiones, UNA creación lenta retenía el
      // lock y serializaba detrás deletes/renames/messages/creates de todo el hub.
      // Fase 1 (FUERA del lock): validar + crear la sesión en el proveedor.
      if (!sessionId) {
        const preStore = loadProjectsStore();
        const preProj = findProject(preStore, id);
        if (!preProj) throw new Error(`NOT_FOUND: project ${id} not found`);
        if (preProj.archivedAt) throw new Error(`ARCHIVED: project ${id} is archived`);
        const provId = body.provider || headerProv || preProj.provider || "opencode";
        if (String(provId).toLowerCase() !== "opencode") {
          return json(res, 400, { ok: false, error: { code: "PROVIDER_UNKNOWN", message: `unknown provider: ${String(provId).slice(0, 40)} (disponible: opencode)` } });
        }
        const adapter = providerManager.resolveProvider(null, provId, preStore);
        const autoTitle = body.title || "Nuevo chat";
        const created = await adapter.createSession({ title: autoTitle, projectId: id }); // I/O: fuera del lock
        sessionId = created.id;
        body.title = created.title || autoTitle;
        body.createdAt = created.createdAt || nowIso();
      }

      // Fase 2 (DENTRO del lock): sólo mutación de la estructura en memoria + save.
      await fileMutex.runExclusive(PROJECTS_STORE_FILE, async () => {
        const store = loadProjectsStore();
        const proj = findProject(store, id);
        // Re-validación tras re-adquirir el lock: el proyecto pudo archivarse o
        // borrarse mientras creábamos la sesión fuera (mismos códigos de error que antes).
        if (!proj) throw new Error(`NOT_FOUND: project ${id} not found`);
        if (proj.archivedAt) throw new Error(`ARCHIVED: project ${id} is archived`);

        let existingTitle = null;
        for (const p of store.projects) {
          const found = (p.sessions || []).find(s => s.sessionId === sessionId);
          if (found && found.title && found.title !== sessionId) {
            existingTitle = found.title;
          }
          if (p.id !== id) p.sessions = (p.sessions || []).filter(s => s.sessionId !== sessionId);
        }

        // Cleanly replace any previous entry in this project
        proj.sessions = (proj.sessions || []).filter(s => s.sessionId !== sessionId);

        const candidateTitle = (body.title && String(body.title).trim() && String(body.title).trim() !== sessionId)
          ? String(body.title).trim()
          : null;
        const resolvedTitle = candidateTitle || existingTitle || resolveExistingSessionTitle(store, sessionId, null);

        store.sessionTitles = store.sessionTitles || {};
        if (resolvedTitle && resolvedTitle !== sessionId) {
          store.sessionTitles[sessionId] = resolvedTitle;
        }

        createdEntry = normalizeSessionEntry({
          sessionId,
          title: resolvedTitle,
          summary: body.summary,
          createdAt: body.createdAt || nowIso(),
          lastUsed: body.lastUsed || nowIso(),
          provider: body.provider || headerProv || proj.provider || "opencode",
        });
        proj.sessions = proj.sessions || [];
        proj.sessions.push(createdEntry);
        saveProjectsStore(store);
      });

      return json(res, 201, { ok: true, data: createdEntry, sessionId: createdEntry.sessionId, id: createdEntry.sessionId });
    } catch (e) {
      if (e.message.startsWith("NOT_FOUND")) return json(res, 404, fail(e.message));
      if (e.message.startsWith("ARCHIVED") || e.message.startsWith("DUPLICATE")) return json(res, 409, fail(e.message));
      return json(res, 500, fail(String(e)));
    }
  }

  // PATCH /api/projects/:id/sessions/:sessionId — update session title, summary, or provider
  if(pathname.match(/^\/api\/projects\/[^\/]+\/sessions\/[^\/]+$/) && req.method==="PATCH"){
    const m = pathname.match(/^\/api\/projects\/([^\/]+)\/sessions\/([^\/]+)$/);
    const id = sanitizeProjectId(decodeURIComponent(m[1]));
    const sessionId = sanitizeProjectId(decodeURIComponent(m[2]));
    if (!isValidId(id)) return invalidId(res, "project", id);   // F4: antes del store
    if (!isValidId(sessionId)) return invalidId(res, "session", sessionId);
    try {
      const raw = await readJsonBody(req, 64*1024);
      const body = JSON.parse(raw || "{}");
      let updatedSess = null;

      await fileMutex.runExclusive(PROJECTS_STORE_FILE, async () => {
        const store = loadProjectsStore();
        const proj = findProject(store, id);
        if (!proj) throw new Error(`NOT_FOUND: project ${id} not found`);
        const sess = (proj.sessions || []).find(s => s.sessionId === sessionId);
        if (!sess) throw new Error(`NOT_FOUND_SESSION: session ${sessionId} not found in project ${id}`);

        if (body.title !== undefined) sess.title = String(body.title).trim();
        if (body.summary !== undefined) sess.summary = String(body.summary).trim();
        if (body.pinned !== undefined) sess.pinned = Boolean(body.pinned);
        if (body.provider !== undefined) {
          const prov = String(body.provider).toLowerCase().trim();
          if (prov !== "opencode") {
            throw new Error("VALIDATION: invalid provider: must be 'opencode'");
          }
          sess.provider = prov;
        }
        saveProjectsStore(store);
        updatedSess = sess;
      });

      return json(res, 200, { ok: true, data: updatedSess });
    } catch (e) {
      if (e.message.startsWith("NOT_FOUND")) return json(res, 404, fail(e.message));
      if (e.message.startsWith("VALIDATION")) return json(res, 400, fail(e.message));
      return json(res, 500, fail(String(e)));
    }
  }

  // DELETE /api/projects/:id/sessions/:sessionId — disassociate session from project
  if(pathname.match(/^\/api\/projects\/[^\/]+\/sessions\/[^\/]+$/) && req.method==="DELETE"){
    const m = pathname.match(/^\/api\/projects\/([^\/]+)\/sessions\/([^\/]+)$/);
    const id = sanitizeProjectId(decodeURIComponent(m[1]));
    const sessionId = sanitizeProjectId(decodeURIComponent(m[2]));
    if (!isValidId(id)) return invalidId(res, "project", id);   // F4: antes del store
    if (!isValidId(sessionId)) return invalidId(res, "session", sessionId);
    try {
      await fileMutex.runExclusive(PROJECTS_STORE_FILE, async () => {
        const store = loadProjectsStore();
        const proj = findProject(store, id);
        if (!proj) throw new Error(`NOT_FOUND: project ${id} not found`);
        const before = (proj.sessions || []).length;
        proj.sessions = (proj.sessions || []).filter(s => s.sessionId !== sessionId);
        if (proj.sessions.length === before) throw new Error(`NOT_FOUND_SESSION: session ${sessionId} not found in project ${id}`);
        saveProjectsStore(store);
      });
      return json(res, 200, { ok: true, data: { removed: sessionId, projectId: id } });
    } catch (e) {
      if (e.message.startsWith("NOT_FOUND")) return json(res, 404, fail(e.message));
      return json(res, 500, fail(String(e)));
    }
  }

  // ---- Skills + Cross-Project Context + Session Summaries ----

  // GET /api/skills?projectId=X — merged global + project-specific skills
  if(pathname==="/api/skills" && req.method==="GET"){
    const projectId = url.searchParams.get("projectId") || url.searchParams.get("project") || null;
    const scopeFilter = url.searchParams.get("scope");
    // F4: los ids de QUERY también se validan (llegan a path.join(SKILLS_ROOT, scope)
    // y a listAllSkillsMerged(projectId)) — scope acepta "global" o un id válido.
    if (projectId && !isValidId(projectId)) return invalidId(res, "project", projectId);
    if (scopeFilter && scopeFilter !== "global" && !isValidId(scopeFilter)) {
      return invalidId(res, "skill", scopeFilter);
    }
    if (scopeFilter) {
      const list = listSkills(scopeFilter);
      return json(res, 200, ok(list));
    }
    const merged = listAllSkillsMerged(projectId);
    // Return {ok:true, data:[{scope,name,content,...}], meta:{projectId, counts}}
    return json(res, 200, ok({ skills: merged, projectId, counts: { global: listSkills("global").length, project: projectId ? listSkills(projectId).length : 0, total: merged.length } }));
  }

  // POST /api/skills — create skill {scope, name, content}
  if(pathname==="/api/skills" && req.method==="POST"){
    try {
      const raw = await readJsonBody(req, 64*1024);
      const body = JSON.parse(raw || "{}");
      const scope = sanitizeScope(body.scope);
      const name = sanitizeName(body.name);
      const content = body.content !== undefined ? String(body.content) : "";
      if (!scope) return json(res, 400, fail("scope required: global or projectId"));
      if (!isValidScope(scope)) return json(res, 400, fail(`invalid scope "${scope}" — use "global" or a valid projectId`));
      if (scope !== "global") {
        const s = loadProjectsStore();
        if (!findProject(s, scope)) return json(res, 404, fail(`scope project ${scope} not found`));
      }
      if (!name) return json(res, 400, fail("name required"));
      if (!isValidSkillName(name)) return json(res, 400, fail(`invalid skill name "${name}" — alphanumeric, spaces, hyphens, max 80`));
      const existing = skillPath(scope, name);
      if (fs.existsSync(existing)) return json(res, 409, fail(`skill "${name}" already exists in scope "${scope}"`));
      const created = writeSkill(scope, name, content);
      return json(res, 201, ok({ scope: created.scope, name: created.name, content: created.content, size: created.content.length }));
    } catch (e) { return json(res, 500, fail(String(e))); }
  }

  // PATCH /api/skills/:scope/:name — update content
  if(pathname.startsWith("/api/skills/") && req.method==="PATCH"){
    const m = pathname.match(/^\/api\/skills\/([^\/]+)\/([^\/]+)$/);
    if (!m) return json(res, 404, fail("not found — use /api/skills/:scope/:name"));
    const scope = sanitizeScope(decodeURIComponent(m[1]));
    const name = sanitizeName(decodeURIComponent(m[2]));
    try {
      const p = skillPath(scope, name);
      if (!fs.existsSync(p)) return json(res, 404, fail(`skill "${name}" not found in scope "${scope}"`));
      const raw = await readJsonBody(req, 64*1024);
      const body = JSON.parse(raw || "{}");
      const newContent = body.content !== undefined ? String(body.content) : fs.readFileSync(p, "utf8");
      const updated = writeSkill(scope, name, newContent);
      return json(res, 200, ok({ scope: updated.scope, name: updated.name, content: updated.content, size: updated.content.length }));
    } catch (e) { return json(res, 500, fail(String(e))); }
  }

  // DELETE /api/skills/:scope/:name
  if(pathname.match(/^\/api\/skills\/[^\/]+\/[^\/]+$/) && req.method==="DELETE"){
    const m = pathname.match(/^\/api\/skills\/([^\/]+)\/([^\/]+)$/);
    const scope = sanitizeScope(decodeURIComponent(m[1]));
    const name = sanitizeName(decodeURIComponent(m[2]));
    const okDel = deleteSkill(scope, name);
    if (!okDel) return json(res, 404, fail(`skill "${name}" not found in scope "${scope}"`));
    return json(res, 200, ok({ removed: name, scope }));
  }

  // POST /api/projects/:id/summarize — generate/update project summary + summaries/<id>.summary.json
  // Body: {summary?} — if summary provided, use it; otherwise auto-build from last 3 sessions + description
  if(pathname.match(/^\/api\/projects\/[^\/]+\/summarize$/) && req.method==="POST"){
    const m = pathname.match(/^\/api\/projects\/([^\/]+)\/summarize$/);
    const id = sanitizeProjectId(decodeURIComponent(m[1]));
    if (!isValidId(id)) return invalidId(res, "project", id); // F4: antes de leer/escribir resumen
    const store = loadProjectsStore();
    const proj = findProject(store, id);
    if (!proj) return json(res, 404, fail(`project ${id} not found`));
    try {
      const raw = await readJsonBody(req, 64*1024).catch(()=> "{}");
      let body = {};
      try { body = JSON.parse(raw || "{}"); } catch {}
      let summary = body.summary ? String(body.summary).trim() : "";
      if (!summary) {
        // Auto-generate one-paragraph summary from project metadata + last 3 sessions
        const last = (proj.sessions || []).slice(-3);
        const sessLines = last.map(s => `${s.title || s.sessionId}${s.summary ? `: ${s.summary}` : ""}`).join(" | ");
        const desc = proj.description ? `${proj.description}. ` : "";
        const sessPart = last.length ? `Recent sessions (${last.length}): ${sessLines}.` : "No sessions yet.";
        summary = `${desc}${sessPart}`.trim();
        if (!summary) summary = `Project ${proj.name} — no sessions or description yet.`;
      }
      // Persist project summary back into projects.json for quick cross-project access
      proj._summaryParagraph = summary;
      proj._summaryUpdatedAt = nowIso();
      saveProjectsStore(store);
      // Also write summaries/<id>.summary.json as specified
      const fileObj = writeSummary(id, summary);
      return json(res, 200, ok({ projectId: id, summary: fileObj.summary, updatedAt: fileObj.updatedAt, sessionsCount: (proj.sessions||[]).length }));
    } catch (e) { return json(res, 500, fail(String(e))); }
  }

  // GET /api/opencode/sessions (and GET /api/sessions) — proxy live sessions across all active providers
  if((pathname==="/api/opencode/sessions" || pathname==="/api/sessions") && req.method==="GET"){
    try {
      const list = await providerManager.listAllSessions();
      // Overlay custom titles and pinned state from projects.json
      try {
        const store = loadProjectsStore();
        for (const item of list) {
          // Registro único (F6): el item conserva el id, el nombre REAL que le
          // puso OpenCode (providerTitle) y el nombre puesto desde la app (title).
          item.providerTitle = (item.raw && item.raw.title) || item.title || null;
          const custom = resolveExistingSessionTitle(store, item.id);
          if (custom && custom !== item.id) {
            item.title = custom;
          }
          item.pinned = resolveExistingSessionPinned(store, item.id);
        }
      } catch (_) {}
      return json(res, 200, ok(list));
    } catch (e) {
      return json(res, 500, fail(`list sessions failed: ${String(e).slice(0,400)}`));
    }
  }

  // PATCH /api/opencode/sessions/:id (and PATCH /api/sessions/:id) — rename session atomically
  const patchSessionMatch = pathname.match(/^\/api\/(?:opencode\/sessions|sessions)\/([^\/]+)$/);
  if (patchSessionMatch && req.method === "PATCH") {
    const sid = sanitizeProjectId(decodeURIComponent(patchSessionMatch[1]));
    if (!isValidId(sid)) return invalidId(res, "session", sid); // F4: antes del store/adapter
    try {
      const raw = await readJsonBody(req, 64 * 1024);
      const body = JSON.parse(raw || "{}");
      const newTitle = String(body.title || body.name || "").trim();
      if (!newTitle) return json(res, 400, fail("title required"));

      await fileMutex.runExclusive(PROJECTS_STORE_FILE, async () => {
        const store = loadProjectsStore();
        store.sessionTitles = store.sessionTitles || {};
        store.sessionTitles[sid] = newTitle;

        for (const p of store.projects) {
          for (const s of (p.sessions || [])) {
            if (s.sessionId === sid) {
              s.title = newTitle;
              s.lastUsed = nowIso();
            }
          }
        }
        saveProjectsStore(store);
      });

      // Also forward rename to provider adapter (opencode)
      const adapter = providerManager.resolveProvider(sid);
      try {
        if (adapter && typeof adapter.renameSession === "function") {
          await adapter.renameSession(sid, newTitle);
        }
      } catch (adapterErr) {
        log.warn(`[hub] adapter.renameSession failed for ${sid}: ${adapterErr.message}`);
      }

      return json(res, 200, ok({ id: sid, sessionId: sid, title: newTitle }));
    } catch (e) {
      return json(res, 500, fail(`rename session failed: ${String(e)}`));
    }
  }

  // DELETE /api/opencode/sessions/:id (and DELETE /api/sessions/:id) — delete session atomically
  const deleteSessionMatch = pathname.match(/^\/api\/(?:opencode\/sessions|sessions)\/([^\/]+)$/);
  if (deleteSessionMatch && req.method === "DELETE") {
    const sid = sanitizeProjectId(decodeURIComponent(deleteSessionMatch[1]));
    if (!isValidId(sid)) return invalidId(res, "session", sid); // F4: primera línea (regex del contrato)
    try {
      // Remove from any project in projects.json and clean sessionTitles
      await fileMutex.runExclusive(PROJECTS_STORE_FILE, async () => {
        const store = loadProjectsStore();
        let changed = false;
        for (const p of store.projects) {
          const before = (p.sessions || []).length;
          p.sessions = (p.sessions || []).filter(s => s.sessionId !== sid);
          if (p.sessions.length !== before) changed = true;
        }
        if (store.sessionTitles && store.sessionTitles[sid]) {
          delete store.sessionTitles[sid];
          changed = true;
        }
        if (store.sessionPins && store.sessionPins[sid] !== undefined) {
          delete store.sessionPins[sid];
          changed = true;
        }
        if (changed) saveProjectsStore(store);
      });

      // Call provider adapter deleteSession
      const adapter = providerManager.resolveProvider(sid);
      try {
        if (adapter && typeof adapter.deleteSession === "function") {
          await adapter.deleteSession(sid);
        }
      } catch (adapterErr) {
        log.warn(`[hub] adapter.deleteSession failed for ${sid}: ${adapterErr.message}`);
      }

      return json(res, 200, ok({ removed: sid }));
    } catch (e) {
      return json(res, 500, fail(`delete session failed: ${String(e)}`));
    }
  }

  // POST /api/opencode/sessions/:id/pin (and /api/sessions/:id/pin) — pin session
  const pinSessionMatch = pathname.match(/^\/api\/(?:opencode\/sessions|sessions)\/([^\/]+)\/pin$/);
  if (pinSessionMatch && req.method === "POST") {
    const sid = sanitizeProjectId(decodeURIComponent(pinSessionMatch[1]));
    if (!isValidId(sid)) return invalidId(res, "session", sid);
    try {
      await fileMutex.runExclusive(PROJECTS_STORE_FILE, async () => {
        const store = loadProjectsStore();
        store.sessionPins = store.sessionPins || {};
        store.sessionPins[sid] = true;

        for (const p of store.projects) {
          for (const s of (p.sessions || [])) {
            if (s.sessionId === sid) {
              s.pinned = true;
            }
          }
        }
        saveProjectsStore(store);
      });
      return json(res, 200, ok({ id: sid, pinned: true }));
    } catch (e) {
      return json(res, 500, fail(`pin session failed: ${String(e)}`));
    }
  }

  // POST /api/opencode/sessions/:id/unpin (and /api/sessions/:id/unpin) — unpin session
  const unpinSessionMatch = pathname.match(/^\/api\/(?:opencode\/sessions|sessions)\/([^\/]+)\/unpin$/);
  if (unpinSessionMatch && req.method === "POST") {
    const sid = sanitizeProjectId(decodeURIComponent(unpinSessionMatch[1]));
    if (!isValidId(sid)) return invalidId(res, "session", sid);
    try {
      await fileMutex.runExclusive(PROJECTS_STORE_FILE, async () => {
        const store = loadProjectsStore();
        store.sessionPins = store.sessionPins || {};
        store.sessionPins[sid] = false;

        for (const p of store.projects) {
          for (const s of (p.sessions || [])) {
            if (s.sessionId === sid) {
              s.pinned = false;
            }
          }
        }
        saveProjectsStore(store);
      });
      return json(res, 200, ok({ id: sid, pinned: false }));
    } catch (e) {
      return json(res, 500, fail(`unpin session failed: ${String(e)}`));
    }
  }

  // Phase 2: GET /api/opencode/sessions/:id/messages (and GET /api/sessions/:id/messages) — unified messages
  if((pathname.match(/^\/api\/opencode\/sessions\/[^\/]+\/messages$/) || pathname.match(/^\/api\/sessions\/[^\/]+\/messages$/)) && req.method==="GET"){
    const m = pathname.match(/^\/api\/(?:opencode\/sessions|sessions)\/([^\/]+)\/messages$/);
    const sid = sanitizeProjectId(decodeURIComponent(m[1]));
      // ?tail=N: >=1 pide solo la cola. 0 o ausente = comportamiento de antes.
      const _tailRaw = parseInt(url.searchParams.get("tail") || "", 10);
      const tailN = Number.isFinite(_tailRaw) && _tailRaw > 0 ? _tailRaw : 0;
    if (!isValidId(sid)) return invalidId(res, "session", sid); // F4: antes del store/adapter
    try {
      const headerProvider = req.headers["x-provider"] ? String(req.headers["x-provider"]).toLowerCase().trim() : null;
      const headerProjectId = req.headers["x-project-id"] ? sanitizeProjectId(decodeURIComponent(req.headers["x-project-id"])) : null;

      if (headerProvider || headerProjectId) {
        await fileMutex.runExclusive(PROJECTS_STORE_FILE, async () => {
          const s = loadProjectsStore();
          let parentProj = null;
          let sessionEntry = null;

          for (const p of s.projects) {
            const found = (p.sessions || []).find(se => se.sessionId === sid);
            if (found) {
              sessionEntry = found;
              parentProj = p;
              break;
            }
          }

          if (headerProjectId && !sessionEntry) {
            parentProj = findProject(s, headerProjectId);
            if (parentProj && !parentProj.archivedAt) {
              const existingTitle = resolveExistingSessionTitle(s, sid);
              sessionEntry = normalizeSessionEntry({
                sessionId: sid,
                title: existingTitle,
                createdAt: nowIso(),
                lastUsed: nowIso(),
                provider: headerProvider || parentProj.provider || "opencode"
              });
              parentProj.sessions = parentProj.sessions || [];
              parentProj.sessions.push(sessionEntry);
            }
          }

          if (sessionEntry) {
            // F6: convención del id manda; sin convención, set-once.
            const convMsg = sid.startsWith("ses_") ? "opencode" : null;
            const boundMsg = convMsg || sessionEntry.provider || headerProvider || null;
            if (boundMsg) sessionEntry.provider = boundMsg;
            sessionEntry.lastUsed = nowIso();
          }

          saveProjectsStore(s);
        });
      }

        // ---- ?tail=N: la cola, en una sola pagina -------------------------------
        // MEDIDO: order=desc&limit=200 -> 0,12 s y 934 KB, frente a ~15 s y 6,5 MB del
        // historial completo. Es lo que necesita el usuario durante un turno: lo que esta
        // pasando ahora, no las 1.800 partes que ya no se tocan.
        //
        // El limite de pagina de OpenCode es 200 fijo, asi que un tail mayor se recorta a
        // 200. Se avisa en el log una sola vez por combinacion para no llenarlo.
        if (tailN > 0) {
          const lim = Math.min(tailN, MSGS_TAIL_MAX);
          if (lim !== tailN) {
            log.warn(`[msgs] ${sid}: ?tail=${tailN} recortado a ${lim}, el limite de pagina de OpenCode es 200`);
          }
          const cKey = msgsCacheKey(sid, lim);
          const cHit = msgsCache.get(cKey);
          if (cHit && Date.now() - cHit.at < MSGS_CACHE_TTL_MS) {
            return send(res, 200, cHit.body, { "Content-Type": "application/json; charset=utf-8" });
          }
          let tFly = msgsTailInflight.get(cKey);
          if (!tFly) {
            tFly = (async () => {
              const ctrl = new AbortController();
              const t = setTimeout(() => ctrl.abort(), 30000);
              let lista;
              try {
                const oc = providerManager.adapters && providerManager.adapters.get("opencode");
                if (oc && typeof oc.fetchMessageTail === "function") {
                  lista = await oc.fetchMessageTail(sid, lim, { signal: ctrl.signal });
                } else {
                  lista = [];
                }
              } catch (e) {
                log.warn(`[msgs] tail fallo ${sid}: ${e.message}`);
                lista = [];
              } finally {
                clearTimeout(t);
              }
              // desc devuelve las mas nuevas PRIMERO: se invierte para que los indices de
              // normalizeMessage sigan siendo ascendentes y el chat no salga del reves.
              if (Array.isArray(lista)) lista = lista.slice().reverse();
              trimListForWire(Array.isArray(lista) ? lista : [], PART_MAX_CHARS);
              const norm = (Array.isArray(lista) ? lista : [])
                .map((msg, idx) => normalizeMessage(msg, sid, idx));
              const body = JSON.stringify(normalizeEnvelope(200, { ok: true, data: norm, tail: lim, total: null }));
              evictCache(msgsCache, cKey, MSGS_CACHE_MAX);
              msgsCache.set(cKey, { body, at: Date.now() });
              return body;
            })();
            msgsTailInflight.set(cKey, tFly);
            const limpiar = () => { if (msgsTailInflight.get(cKey) === tFly) msgsTailInflight.delete(cKey); };
            tFly.then(limpiar, limpiar);
          }
          const tBody = await tFly;
          return send(res, 200, tBody, { "Content-Type": "application/json; charset=utf-8" });
        }

        // A-3: servir de cache si sigue viva. El TTL (4 s) es la red de seguridad para
        // un evento perdido; el mecanismo real es la invalidacion, mas abajo.
        const hit = msgsCache.get(sid);
        if (hit && Date.now() - hit.at < MSGS_CACHE_TTL_MS) {
          return send(res, 200, hit.body, { "Content-Type": "application/json; charset=utf-8" });
        }

        // Single-flight: si ya hay una lectura en curso para esta sesion, se espera a
        // esa en vez de lanzar otra. Sin esto, con poll de 2 s y lectura de 14 s, se
        // acumulan varias lecturas identicas en paralelo haciendo el mismo trabajo.
        //
        // La lectura ya NO lleva el abortCtrl de ESTA peticion: al compartirla entre
        // varias, que un solo cliente se vaya no puede cancelar la lectura de los
        // demas. Se cancela por tiempo, que es lo que evita dejar un fetch colgado.
        let flying = msgsInflight.get(sid);
        if (!flying) {
          flying = (async () => {
            const ctrl = new AbortController();
            const t = setTimeout(() => ctrl.abort(), 30000);
            let lista;
            try {
              lista = await providerManager.getUnifiedMessages(sid, { signal: ctrl.signal });
            } finally {
              clearTimeout(t);
            }
            const recortado = trimListForWire(Array.isArray(lista) ? lista : [], PART_MAX_CHARS);
            const normalizedList = (Array.isArray(lista) ? lista : [])
              .map((msg, idx) => normalizeMessage(msg, sid, idx));
            const body = JSON.stringify(normalizeEnvelope(200, { ok: true, data: normalizedList }));
            // Eviccion por antiguedad: 6 MB por sesion y esto corre en un movil. Usa el
            // helper y no una cuenta propia: ahora hay dos productores (completo y tail)
            // y duplicar la cuenta seria una forma segura de equivocarse al vaciar.
            evictCache(msgsCache, sid, MSGS_CACHE_MAX);
            msgsCache.set(sid, { body, at: Date.now() });
            return { recortado };
          })();
          msgsInflight.set(sid, flying);
          const limpiar = () => { if (msgsInflight.get(sid) === flying) msgsInflight.delete(sid); };
          flying.then(limpiar, limpiar);
        }
        const { recortado } = await flying;
        if (recortado.binarios || recortado.textos) {
          log.info(`[msgs] ${sid}: ${recortado.binarios} data URI retirada(s), ${recortado.textos} texto(s) recortado(s), ${Math.round(recortado.saved / 1024)} KB ahorrados`);
        }
        const servido = msgsCache.get(sid);
        if (servido) return send(res, 200, servido.body, { "Content-Type": "application/json; charset=utf-8" });
        return json(res, 200, { ok: true, data: [] });
    } catch (e) {
      log.error(`[hub] getMessages error for ${sid}`, { err: e.message });
      return json(res, 502, { ok: false, error: `get messages failed: ${String(e.message || e).slice(0,400)}` });
    }
  }

  // GET /api/opencode/models (and GET /api/models) — list available models for the model selector
  if((pathname === "/api/opencode/models" || pathname === "/api/models") && req.method === "GET") {
    // Antes, sin parametro `provider`, esta ruta devolvia la lista de
    // AntigravityAdapter (los modelos del CLI agy) en vez de la de OpenCode. La app
    // la llama sin parametro, asi que el selector de modelos ofrecia el motor
    // equivocado. Ahora solo hay un motor y una lista.
    const models = await opencodeAdapter.listModels();
    return json(res, 200, ok(models));
  }

  // GET /api/forms (and /api/opencode/forms) — formularios/preguntas PENDIENTES.
  //
  // Cuando una herramienta lanza una pregunta al usuario, el TUI del CLI la pinta y
  // se responde con flechas + Enter. Desde Aegis no había forma de verla NI de
  // contestarla: el proxy generico /opencode/* devuelve 401 porque no anade el Basic
  // de OpenCode (esa auth solo vive en providers.js), asi que hacen falta rutas
  // propias que usen opencodeAdapter._v2().
  //
  // ?sessionId filtra por sesion. Sin filtro devuelve todos los pendientes.
  if ((pathname === "/api/forms" || pathname === "/api/opencode/forms") && req.method === "GET") {
    try {
      const r = await opencodeAdapter._v2("/api/form", { timeoutMs: 12000 });
      if (!r.ok) return json(res, 502, log502("forms", "FORMS_UNAVAILABLE", r), fail("FORMS_UNAVAILABLE", `OpenCode respondió ${r.status}`));
      const j = r.json || {};
      const all = Array.isArray(j.data) ? j.data : [];
      const want = url.searchParams.get("sessionId");
      const forms = want ? all.filter((f) => f && f.sessionID === want) : all;
      return json(res, 200, ok(forms));
    } catch (e) {
      log.warn("[forms] list error", { err: e.message });
      return json(res, 200, ok([]));
    }
  }

  // POST /api/forms/:sessionId/:formId/reply — responder un formulario.
  // Cuerpo: { "answer": { "<fieldKey>": "<valor de la opcion>" } }
  if (pathname.match(/^\/api\/forms\/([^\/]+)\/([^\/]+)\/reply$/) && req.method === "POST") {
    const m = pathname.match(/^\/api\/forms\/([^\/]+)\/([^\/]+)\/reply$/);
    const sid = m[1];
    const fid = m[2];
    if (!isValidId(sid) || !isValidId(fid)) {
      return json(res, 400, fail("INVALID_ID", "sessionId o formId inválido"));
    }
    try {
      const raw = await readJsonBody(req, 256 * 1024);
      const body = JSON.parse(raw || "{}");
      if (!body.answer || typeof body.answer !== "object") {
        return json(res, 400, fail("INVALID_ANSWER", "body.answer debe ser un objeto {campo: valor}"));
      }
      const r = await opencodeAdapter._v2(`/api/session/${sid}/form/${fid}/reply`, {
        method: "POST",
        body: { answer: body.answer },
        timeoutMs: 20000,
      });
      if (!r.ok) {
        return json(res, 502, fail("FORM_REPLY_FAILED",
          `OpenCode respondió ${r.status}${r.text ? `: ${String(r.text).slice(0, 160)}` : ""}`));
      }
      // Se garantiza un objeto de primer nivel: la app deserializa esto como
      // Map<String, Any> y una lista o un escalar harían fallar a Gson.
      const payload = (r.json && typeof r.json === "object" && !Array.isArray(r.json))
        ? r.json
        : { replied: true, result: r.json ?? null };
      return json(res, 200, ok(payload));
    } catch (e) {
      log.warn("[forms] reply error", { err: e.message });
      return json(res, 500, fail("FORM_REPLY_ERROR", e.message));
    }
  }

  // ===== Permisos de herramientas =====
  //
  // Cuando una herramienta pide permiso, el TUI del CLI abre un diálogo "Permission
  // required / Allow once / Always allow / Reject". Desde Aegis el prompt era
  // COMPLETAMENTE invisible, y el turno se quedaba parado sin dar ninguna señal: se ve
  // "trabajando en ello" indefinidamente. Peor: la sesión seguía marcada como ocupada
  // para siempre, porque el vigilante del Hub (startExecutionWatcher) solo atiende
  // eventos que empiezan por "session." y `permission.asked` NO empieza por "session.".
  //
  // Rutas de OpenCode 2.0.14 (confirmadas contra su openapi.json; NO son /api/permission,
  // que da 404 — son en singular y con el id de sesion en la ruta):
  //   GET  /api/session/{id}/permission               -> {"data":[Permission.Request]}
  //   POST /api/session/{id}/permission/{rid}/reply    -> {decision} -> 204 sin cuerpo
  //
  // Se usan las rutas por sesión y no las globales a propósito: las globales aceptan
  // ?location[directory] y, si no se les pasa, OpenCode la resuelve a SU cwd (/root) y
  // devuelve siempre lista vacía. Aquí no cabe ese error de alcance.

  // GET /api/permissions (y /api/opencode/permissions) — permisos PENDIENTES.
  // ?sessionId filtra por sesión. Sin filtro, todos los pendientes.
  if ((pathname === "/api/permissions" || pathname === "/api/opencode/permissions") && req.method === "GET") {
    try {
      const want = url.searchParams.get("sessionId");
      // Sin filtro, /api/permission/request necesita location[directory] para no mirar
      // en /root. Se piden todas las localizaciones pendientes y se filtra aquí, que es
      // más simple y no depende de adivinar el directorio de cada proyecto.
      const path = want
        ? `/api/session/${want}/permission`
        : "/api/permission/request";
      const r = await opencodeAdapter._v2(path, { timeoutMs: 12000 });
      if (!r.ok) return json(res, 502, log502("permissions", "PERMISSIONS_UNAVAILABLE", r), fail("PERMISSIONS_UNAVAILABLE", `OpenCode respondió ${r.status}`));
      const j = r.json || {};
      let all = Array.isArray(j.data) ? j.data : [];
      // La variante global viene envuelta en {location, data}; la de sesión, no.
      if (j.location && Array.isArray(j.data)) all = j.data;
      return json(res, 200, ok(all));
    } catch (e) {
      log.warn("[permissions] list error", { err: e.message });
      // Como en formularios: 200 con lista vacía. Un 500 aquí lo que provoca es que el
      // poll de la app trague el error y deje el chat sin actualizar.
      return json(res, 200, ok([]));
    }
  }

  // POST /api/permissions/:sessionId/:requestId/reply — responder un permiso.
  // Cuerpo: { "decision": "once" | "always" | "reject" }
  //
  // OJO con la semántica de "always" y "reject" en OpenCode 2.0.14, porque no es la
  // intuitiva y conviene dejarla escrita cerca de donde se usa:
  //  - "once": resuelve solo esta peticion y la herramienta suspendida continua.
  //  - "always": resuelve esta, y SOLO si request.save no viene vacio persiste
  //    {projectID, action, resources:save} como regla permanente. Ademas auto-resuelve
  //    las demas pendientes de la misma sesion que ya quedarian en "allow".
  //  - "reject": la herramienta FALLA, y ademas rechaza todas las demas pendientes de
  //    la misma sesion. Un toque puede cancelar varios avisos a la vez.
  if (pathname.match(/^\/api\/permissions\/([^\/]+)\/([^\/]+)\/reply$/) && req.method === "POST") {
    const m = pathname.match(/^\/api\/permissions\/([^\/]+)\/([^\/]+)\/reply$/);
    const sid = m[1];
    const rid = m[2];
    if (!isValidId(sid) || !isValidId(rid)) {
      return json(res, 400, fail("INVALID_ID", "sessionId o requestId inválido"));
    }
    try {
      const raw = await readJsonBody(req, 64 * 1024);
      const body = JSON.parse(raw || "{}");
      const decision = body.decision;
      if (decision !== "once" && decision !== "always" && decision !== "reject") {
        return json(res, 400, fail("INVALID_DECISION",
          'body.decision debe ser "once", "always" o "reject"'));
      }
      const r = await opencodeAdapter._v2(`/api/session/${sid}/permission/${rid}/reply`, {
        method: "POST",
        body: { decision, message: typeof body.message === "string" ? body.message : undefined },
        timeoutMs: 20000,
      });
      if (!r.ok) {
        return json(res, 502, fail("PERMISSION_REPLY_FAILED",
          `OpenCode respondió ${r.status}${r.text ? `: ${String(r.text).slice(0, 160)}` : ""}`));
      }
      // OpenCode responde 204 SIN cuerpo. Se devuelve siempre un objeto de primer
      // nivel porque la app deserializa esto y un null haría fallar a Gson.
      return json(res, 200, ok({ replied: true, decision }));
    } catch (e) {
      log.warn("[permissions] reply error", { err: e.message });
      return json(res, 500, fail("PERMISSION_REPLY_ERROR", e.message));
    }
  }

  // GET /api/sessions/:sid/part?messageId=..&partId=.. (y /api/opencode/sessions/:sid/part,
  // que es la que llama la app) — la parte ENTERA, sin recortar.
  // Es lo que hace que recortar no sea perder: la tarjeta plegada pide esto al
  // desplegarse y recibe el texto y el binario originales.
  //
  // No se sirve desde la cache de A a proposito: la cache guarda la version recortada y
  // aqui hace falta la larga. Se relee de OpenCode, que son ~4 s en una sesion de 1400
  // mensajes, y es una accion explicita del usuario, no el poll de fondo.
  if (pathname.match(/^\/api\/(?:opencode\/)?sessions\/([^\/]+)\/part$/) && req.method === "GET") {
    const m = pathname.match(/^\/api\/(?:opencode\/)?sessions\/([^\/]+)\/part$/);
    const sid = m[1];
    const messageId = url.searchParams.get("messageId") || "";
    const partId = url.searchParams.get("partId") || "";
    if (!isValidId(sid)) return json(res, 400, fail("INVALID_ID", "sessionId invalido"));
    if (!partId) return json(res, 400, fail("MISSING_PART", "hace falta ?partId="));
    try {
      const ctrl = new AbortController();
      const t = setTimeout(() => ctrl.abort(), 30000);
      let lista = [];
      try {
        lista = await providerManager.getUnifiedMessages(sid, { signal: ctrl.signal });
      } finally {
        clearTimeout(t);
      }
      if (!Array.isArray(lista)) lista = [];
      for (const msg of lista) {
        if (messageId && msg && msg.info && msg.info.id && msg.info.id !== messageId) continue;
        for (const p of (msg && msg.parts) || []) {
          if (!p || String(p.id) !== String(partId)) continue;
          return json(res, 200, ok({
            id: p.id,
            type: p.type,
            mime: p.mime || null,
            filename: p.filename || null,
            text: typeof p.text === "string" ? p.text : null,
            output: p.state && typeof p.state.output === "string" ? p.state.output : null,
            // El binario tambien vuelve aqui: es lo que pide la app cuando la parte
            // venia marcada hasBinary por el recorte.
            url: typeof p.url === "string" ? p.url : null,
            image: typeof p.image === "string" ? p.image : null,
            data: typeof p.data === "string" ? p.data : null,
            stateContent: p.state && Array.isArray(p.state.content) ? p.state.content : null,
          }));
        }
      }
      return json(res, 404, fail("PART_NOT_FOUND",
        `no encuentro la parte ${partId} en ${sid}${messageId ? ` (mensaje ${messageId})` : ""}`));
    } catch (e) {
      log.warn("[part] fetch error", { err: e.message });
      return json(res, 500, fail("PART_FETCH_ERROR", e.message));
    }
  }

  // GET /api/sessions/inflight — estado de ejecucion de las sesiones.
  //   ?ids=1 -> solo los ids ocupados (O(1), sin consultar OpenCode)
  //   sin    -> [{id, since, turnOver}] para las que el vigilante conoce
  // Lo consume la app para: el circulo de "ejecutando" en la lista de chats, el
  // divisor "respuesta final" (solo cuando turnOver), y confirmar la entrega.
  if ((pathname === "/api/sessions/inflight" || pathname === "/api/opencode/sessions/inflight") && req.method === "GET") {
    const onlyIds = url.searchParams.get("ids") === "1";
    const now = Date.now();
    const live = [...inflightSessions.entries()].filter(([, t]) => now - t < 15 * 60 * 1000);
    if (onlyIds) return json(res, 200, ok(live.map(([id]) => id)));
    // `lastSeenAt` = ultimo instante en el que se vio CUALQUIER evento de la sesion. Es
    // lo que permite al cliente distinguir "turno en marcha" de "registro que se quedo
    // pegado" porque el vigilante perdio el `succeeded` al reconectar.
    //
    //
    // ESTO ESTABA ROTO DE DOS FORMAS, y las dos importan:
    //
    // 1) `turnOver: false` hardcodeado. El diseño del ADR-003 (el divisor lo decide
    //    session.execution.succeeded, nunca un time.completed adivinado) NO ESTABA
    //    IMPLEMENTADO: el campo nunca valía true, así que la app caía siempre en su
    //    modo degradado, que devuelve "terminado" en cuanto el último mensaje tiene
    //    time.completed. Y eso ocurre tras CADA bash. De ahí el divisor que salía
    //    después de cada bash con exit 0. La función isTurnOver() ya existía y hacía
    //    justo falta: simplemente no la llamaba nadie.
    //
    // 2) `seen.has(sid)`, donde `seen` NO ESTÁ DECLARADO EN NINGÚN SITIO (un solo uso
    //    en todo el fichero). ReferenceError en cada petición -> 500. El poll de la app
    //    se lo tragaba y se quedaba con el estado viejo, o sea que tampoco recibía el
    //    punto 1. El comentario que hay arriba afirma que esto ya estaba arreglado, y no
    //    lo estaba: se cambío .get() por .has() sobre un Set que no existe, y eso
    //    cambia el error de TypeError a ReferenceError sin arreglar nada.
    //
    // El `seen` que hace falta es un Set LOCAL de ids ya emitidos, para no repetir una
    // sesion que sigue en inflightSessions y además acaba de aparecer en deliveredInbox
    // (pasa en cuanto empieza un turno nuevo). No hace falta que persista entre
    // peticiones: solo deduplica dentro de esta respuesta.
    const emitted = new Set(live.map(([id]) => id));
    const out = live.map(([id, since]) => ({
      id, since, turnOver: isTurnOver(id), lastSeen: lastSeenAt.get(id) ?? since,
    }));
    for (const [sid, since] of deliveredInbox) {
      if (emitted.has(sid)) continue;
      if (now - since < 15 * 60 * 1000) {
        emitted.add(sid);
        out.push({ id: sid, since, turnOver: true, lastSeen: since });
      }
    }
    return json(res, 200, ok(out));
  }

  // GET /api/sessions/:id/model — modelo REAL con el que esta trabajando la sesion.
  //
  // Por que existe: el usuario elegia un modelo en Aegis (p. ej. space-bunny-free) y al
  // salir y volver a entrar le volvia el default. La eleccion NO se perdia: OpenCode la
  // tiene en el objeto de sesion,
  //
  //   model = { id: "space-bunny-free", providerID: "opencode", variant: "default" }
  //
  // y la app sencillamente no la pedia. Esto es ademas la fuente que cubre el cambio de
  // modelo hecho DESDE EL CLI, porque el CLI escribe en el mismo sitio.
  //
  // Devuelve {id, providerID, variant} o null si la sesion no tiene modelo fijado.
  if (pathname.match(/^\/api\/sessions\/([^\/]+)\/model$/) && req.method === "GET") {
    const sid = sanitizeProjectId(decodeURIComponent(pathname.match(/^\/api\/sessions\/([^\/]+)\/model$/)[1]));
    if (!isValidId(sid)) return invalidId(res, "session", sid);
    try {
      const r = await opencodeAdapter._v2(`/api/session/${encodeURIComponent(sid)}`, { timeoutMs: 8000 });
      if (!r.ok) return json(res, 502, fail("SESSION_MODEL_UNAVAILABLE", `OpenCode respondió ${r.status}`));
      const ses = (r.json && (r.json.data || r.json)) || {};
      const m = ses.model;
      return json(res, 200, ok(m && typeof m === "object"
        ? { id: m.id ?? null, providerID: m.providerID ?? null, variant: m.variant ?? null }
        : null));
    } catch (e) {
      log.warn("[session-model] error", { err: e.message });
      return json(res, 200, ok(null));
    }
  }

  // GET /api/projects/:id/summary — read summary (optional fetch helper)
  // BACKLOG F0-F2 (bug preexistente, hallazgo F4): el regex original NO tenía grupo
  // de captura => m[1] === undefined => id literal "undefined" => 404 SIEMPRE, hasta
  // con el fichero en disco. Ahora: ([^/]+) captura el id, isValidId rechaza
  // traversal con 400 ANTES de tocar summaries/, y un id válido sin fichero devuelve
  // 404 honesto (NOT_FOUND vía normalizeEnvelope).
  if(pathname.match(/^\/api\/projects\/([^\/]+)\/summary$/) && req.method==="GET"){
    const m = pathname.match(/^\/api\/projects\/([^\/]+)\/summary$/);
    let id = "";
    try { id = sanitizeProjectId(decodeURIComponent(m[1])); } catch (_) { id = String(m[1] || ""); } // % inválido => id con "%" => 400
    if (!isValidId(id)) return invalidId(res, "project", id); // F4: antes de leer summaries/<id>.json
    const data = readSummary(id);
    if (!data) return json(res, 404, fail(`no summary for project ${id} — POST /api/projects/${id}/summarize to generate`));
    return json(res, 200, ok(data));
  }

  // ---- Voice Command Registry + Log (hands-free system) ----
  // NOTE: VOICE_COMMANDS/LOG are defined at top-level scope outside createServer callback for persistence
  // (previously inside callback caused per-request reset — now fixed)
  // GET /api/voice/commands — all registered commands with patterns/descriptions
  if(pathname==="/api/voice/commands" && req.method==="GET"){
    return json(res, 200, ok(VOICE_COMMANDS));
  }
  // POST /api/voice/log — record a voice command execution (called by app.js after handler)
  // Body: {recognizedText, matchedCommand, result, projectId?, sessionId?}
  if(pathname==="/api/voice/log" && req.method==="POST"){
    try {
      const raw = await readJsonBody(req, 16*1024);
      const body = JSON.parse(raw || "{}");
      const entry = pushVoiceLog({
        recognizedText: String(body.recognizedText || "").slice(0, 600),
        matchedCommand: String(body.matchedCommand || body.command || "none").slice(0, 80),
        result: body.result !== undefined ? String(body.result).slice(0, 2000) : null,
        projectId: body.projectId || null,
        sessionId: body.sessionId || null
      });
      return json(res, 201, ok(entry));
    } catch (e) { return json(res, 400, fail(String(e))); }
  }
  // GET /api/voice/log — last 50 commands with timestamp/recognizedText/matchedCommand/result
  if(pathname==="/api/voice/log" && req.method==="GET"){
    return json(res, 200, ok(VOICE_LOG));
  }

  // GET /api/health — EXENTO de token (A-1) y con shape EXACTO de Models.kt HealthData:
  //   { ok:true, data:{ server, port, uptime, memory, workspace, projects, agents, jobs, skills, adapters } }
  // Es la sonda ligera de keepalive.sh (sin shells root ni df) y la fuente de verdad del
  // contrato app <-> backend. El diagnóstico rico (disk/bridge/ownership/activeProject) vive
  // en GET /api/system/health (con token), que incluye este mismo HealthData más el legacy.
  if(pathname==="/api/health" && req.method==="GET"){
    // MEDIDO 2026-09-30: el pid va en una CABECERA y no en el cuerpo a proposito. El
    // cuerpo es el contrato `HealthData`, con 10 claves fijadas por un test, y meter
    // una clave mas lo rompe. En cabecera no se altera el JSON y aun asi quien hable
    // con este Hub puede COMPROBAR que es el suyo: los tests eligen un puerto efimero,
    // lo sueltan y arrancan su Hub encima; si algo se cuela en ese hueco, el Hub nuevo
    // no puede tomar el puerto y el test acabaria hablando con el ajeno —que ya tiene
    // su cubo de rate-limit lleno— sin enterarse de nada. Esto no es un secreto: /proc
    // lo enseña cualquiera del mismo uid.
    res.setHeader("X-Aegis-Pid", String(process.pid));
    return json(res, 200, { ok: true, data: await buildHealthData() });
  }

  if(pathname==="/api/status" && req.method==="GET"){
    const rootCheck = await runShell("id; su -c id 2>&1 | head -1; getprop ro.build.version.release 2>&1; getprop ro.product.model 2>&1");
    // probe opencode
    const health = await probeOpencodeHealth();
    // A-3: envuelto. La sonda de keepalive.sh ya NO usa esta ruta (pesada + exige token):
    // usa GET /api/health (exento). install-su.sh sólo hace `head -c 300` de diagnóstico.
    return json(res, 200, { ok: true, data: { hub:"ok", hub_port: HUB_PORT, opencode: health, projects_root: PROJECTS_ROOT, projects: await listProjects(), root: rootCheck.stdout.slice(0,1200) } });
  }
  if(pathname==="/api/device/shell" && req.method==="POST"){
    try{
      const raw = await readJsonBody(req, MAX_JSON_BODY);
      const { cmd, timeout, maxBuffer } = JSON.parse(raw||"{}");
      if(!cmd) return json(res, 400, { error:"cmd requerido" });
      if (hasShellMeta(cmd)) return badParam(res, "cmd");
      log.info(`[shell] ${cmd.slice(0,400)} (maxBuffer ${((maxBuffer||MAX_BUFFER)/1024/1024).toFixed(1)}MB)`);
      const out = await runShell(cmd, timeout || 20000, maxBuffer || MAX_BUFFER);
      json(res, 200, { ok: true, data: out });
    }catch(e){ json(res, 500, { error:String(e), truncated: String(e).includes("maxBuffer") }); }
    return;
  }
  if(pathname==="/api/device/launch" && req.method==="POST"){
    try{ const raw=await readJsonBody(req); const { pkg, activity } = JSON.parse(raw||"{}"); if(!pkg) return json(res, 400, { error:"pkg requerido ej: com.bcp.bo.wallet" }); if (!isValidPackageName(String(pkg))) return badParam(res, "pkg (debe ser com.ejemplo.app)");
    if (activity && !isValidPackageName(String(activity))) return badParam(res, "activity (debe ser com.ejemplo.app)"); const cmd = activity ? `am start -n ${pkg}/${activity}` : `am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p ${pkg} 2>&1 || monkey -p ${pkg} -c android.intent.category.LAUNCHER 1 2>&1 || cmd package resolve-activity --brief -c android.intent.category.LAUNCHER ${pkg} 2>&1`; const out = await runShell(cmd); json(res, 200, { ok: true, data: { cmd, ...out } }); }catch(e){ json(res,500,{error:String(e)}); }
    return;
  }
  if(pathname==="/api/device/tap" && req.method==="POST"){
    try{ const raw=await readJsonBody(req); const { x, y } = JSON.parse(raw||"{}"); const out = await runShell(`input tap ${parseInt(x)} ${parseInt(y)}`); json(res, 200, { ok: true, data: out }); }catch(e){ json(res,500,{error:String(e)}); }
    return;
  }
  if(pathname==="/api/device/input" && req.method==="POST"){
    try{ const raw=await readJsonBody(req); const { text } = JSON.parse(raw||"{}"); if (text != null && hasShellMeta(String(text))) return badParam(res, "text"); const esc = String(text||"").replace(/ /g,"%s").replace(/&/g,"\\&"); const out = await runShell(`input text ${esc}`); json(res,200,{ ok: true, data: out }); }catch(e){ json(res,500,{error:String(e)}); }
    return;
  }
  if(pathname==="/api/device/key" && req.method==="POST"){
    try{ const raw=await readJsonBody(req); const { code } = JSON.parse(raw||"{}"); const out = await runShell(`input keyevent ${parseInt(code)}`); json(res,200,{ ok: true, data: out }); }catch(e){ json(res,500,{error:String(e)}); }
    return;
  }
  if(pathname==="/api/device/apps" && req.method==="GET"){
    const q = url.searchParams.get("q") || "";
    if (hasShellMeta(q)) return badParam(res, "q");
    // El valor va dentro de comillas dobles (JSON.stringify), donde $ y ` siguen
    // expandiendose. Solo se acepta si parece un nombre, y si no lo es NO se
    // interpola: se devuelve la lista sin filtrar.
    const qSafe = /^[A-Za-z0-9._]{1,60}$/.test(q);
    // qSafe=false significa que `q` NO tiene forma de paquete: no se interpola nada
    // y se devuelve la lista sin filtrar. Antes se metia dentro de comillas dobles
    // con JSON.stringify, donde $ y ` siguen expandiendose.
    const out = await runShell(`pm list packages ${qSafe ? `-3 | grep -i -F ${JSON.stringify(q)}` : ""} 2>&1 | head -n 200; pm list packages -3 2>&1 | head -n 200`);
    // parse
    const pkgs = out.stdout.split("\n").filter(l=>l.includes("package:")).map(l=>l.replace("package:","").trim()).slice(0,200);
    return json(res, 200, { ok: true, data: { pkgs, raw: out.stdout.slice(0,4000) } });
  }
  // a11y + streaming-friendly (reenvía Base64 grande sin truncar)
  if(pathname==="/api/device/a11y" && req.method==="POST"){
    try{
      const raw = await readJsonBody(req, MAX_JSON_BODY);
      const body = JSON.parse(raw||"{}");
      // forwarding con buffer grande: usa bytes completos
      const bodyBytes = Buffer.from(raw, "utf8");
      const fwd = await new Promise(resolve=>{
        const pr = http.request({ hostname:"127.0.0.1", port:8766, path:"/a11y", method:"POST", headers:{"Content-Type":"application/json", "Content-Length": bodyBytes.length, "X-Aegis-Token": AEGIS_TOKEN } }, r=>{
          const chunks=[]; r.on("data",c=>chunks.push(c)); r.on("end",()=> resolve({ ok:true, status:r.statusCode, body: Buffer.concat(chunks).toString("utf8") }));
        });
        pr.on("error", e=> resolve({ ok:false, error:String(e) }));
        pr.write(bodyBytes); pr.end();
      });
      // Excepción documentada: reenvío passthrough del cuerpo JSON del Companion :8766
      // (status y body originales, sin envolver) — sólo existe el APK que lo genera.
      if(fwd.ok) return send(res, fwd.status, fwd.body, {"Content-Type":"application/json"});
      if(body.action==="dump"){
        const out = await runShell(`uiautomator dump /sdcard/window_dump.xml && cat /sdcard/window_dump.xml 2>&1 | head -n 800`, 15000, MAX_BUFFER);
        return json(res, 200, { ok: true, data: { fallback:"uiautomator", ...out, note:"Para clicks por texto/id instala el APK Companion con AccessibilityService" } });
      }
      return json(res, 501, { error:"APK Companion no conectado (puerto 8766). Instala el APK para control por AccessibilityService.", requested: body, forwardError: fwd.error });
    }catch(e){ json(res,500,{error:String(e)}); }
    return;
  }
  // nuevo: screenshot Base64 streaming-safe — PNG 1080x2400 ~ 800KB-2MB b64, 50MB buffer sobrado
  if(pathname==="/api/device/screenshot" && req.method==="GET"){
    const quality = Math.max(10, Math.min(95, parseInt(url.searchParams.get("quality")||"35")));
    const scale = Math.max(0.2, Math.min(1.0, parseFloat(url.searchParams.get("scale")||"0.5")));
    // pipe directo sin head -c truncador intermedio — maxBuffer controla el límite real
    const cmd = `nsenter -t 1 -m -- sh -c 'screencap -p 2>/dev/null | base64 -w 0 2>/dev/null || screencap -p 2>/dev/null | base64 2>/dev/null'`;
    const out = await runShell(cmd, 25000, MAX_BUFFER);
    const b64 = out.stdout.trim().replace(/\s/g,"");
    if(!b64 || b64.length < 1000){
      return json(res, 500, { error:"screenshot vacío", stdout: out.stdout.slice(0,800), stderr: out.stderr.slice(0,800), code: out.code });
    }
    if(url.searchParams.get("raw")==="1") return send(res, 200, b64, {"Content-Type":"text/plain", "Content-Length": String(Buffer.byteLength(b64))});
    return json(res, 200, { ok: true, data: { b64, len: b64.length, approx_bytes: Math.floor(b64.length*0.75), quality, scale, note: "b64 completo sin truncar (MAX_BUFFER 50MB). Para ahorrar tokens pasa ?quality=25&scale=0.4 y recorta cliente-side." } });
  }
  if(pathname==="/api/device/screenshot" && req.method==="POST"){
    try{
      const raw = await readJsonBody(req, 2*1024*1024);
      const opts = JSON.parse(raw||"{}");
      const out = await runShell(`nsenter -t 1 -m -- sh -c 'screencap -p 2>/dev/null | base64 -w 0 2>/dev/null || screencap -p 2>/dev/null | base64 2>/dev/null'`, 25000, MAX_BUFFER);
      const b64 = out.stdout.trim().replace(/\s/g,"");
      return json(res, 200, { ok: true, data: { b64, len: b64.length, opts } });
    }catch(e){ json(res,500,{error:String(e)}); }
    return;
  }

  // 2.5) Asistente Root: interpretar intención → acción NSENTER/8766
  // Vocabulario directo sin pasar por LLM si la orden ya es comando del sistema
  const DIRECT_INTENT = [
    { re: /^abre\s+(.+)/i, action: "launch", slot: "app" },
    { re: /^abrir\s+(.+)/i, action: "launch", slot: "app" },
    { re: /^inicia\s+(.+)/i, action: "launch", slot: "app" },
    { re: /^lanza\s+(.+)/i, action: "launch", slot: "app" },
    { re: /^(toma|haz|saca)\s+(una\s+)?captura(\s+de\s+pantalla)?/i, action: "screenshot" },
    { re: /^captura(\s+de\s+pantalla)?/i, action: "screenshot" },
    { re: /^(sube|aumenta)\s+(el\s+)?volumen/i, action: "volume_up" },
    { re: /^(baja|disminuye)\s+(el\s+)?volumen/i, action: "volume_down" },
    { re: /^(silencia|mutea|silenciar)/i, action: "volume_mute" },
    { re: /^(bloquea|bloquear)\s+(la\s+)?pantalla/i, action: "lock_screen" },
    { re: /^(desbloquea|desbloquear)/i, action: "unlock_screen" },
    { re: /^(activa|enciende|prende)\s+(el\s+)?(wifi|wi-?fi)/i, action: "wifi_on" },
    { re: /^(desactiva|apaga)\s+(el\s+)?(wifi|wi-?fi)/i, action: "wifi_off" },
    { re: /^(activa|enciende)\s+(el\s+)?bluetooth/i, action: "bluetooth_on" },
    { re: /^(desactiva|apaga)\s+(el\s+)?bluetooth/i, action: "bluetooth_off" },
    { re: /^pon\s+alarma/i, action: "set_alarm" },
    { re: /^llama\s+a\s+(.+)/i, action: "call", slot: "contact" },
    { re: /^manda\s+(?:un\s+)?mensaje\s+a\s+(.+?)\s*[:\-]\s*(.+)/i, action: "whatsapp_send", slots: ["contact","text"] },
    { re: /^(manda|env[ií]a)\s+(?:un\s+)?(?:whatsapp|mensaje)\s+a\s+(.+?)\s*[:\-]\s*(.+)/i, action: "whatsapp_send", slots: ["contact","text"] },
    { re: /^(manda|env[ií]a)\s+(?:un\s+)?(?:whatsapp|mensaje)\s+a\s+(.+)/i, action: "whatsapp_send_prompt", slot: "contact" },
  ];
  const APP_ALIASES = {
    "whatsapp": "com.whatsapp",
    "wa": "com.whatsapp",
    "telegram": "org.telegram.messenger",
    "youtube": "com.google.android.youtube",
    "chrome": "com.android.chrome",
    "camara": "com.android.camera2",
    "cámara": "com.android.camera2",
    "galeria": "com.google.android.apps.photos",
    "galería": "com.google.android.apps.photos",
    "fotos": "com.google.android.apps.photos",
    "yape": "com.bcp.bo.wallet",
    "bcp": "com.bcp.bo.wallet",
    "proton": "ch.protonmail.android",
    "protonmail": "ch.protonmail.android",
    "gmail": "com.google.android.gm",
    "maps": "com.google.android.apps.maps",
    "spotify": "com.spotify.music",
    "facebook": "com.facebook.katana",
    "instagram": "com.instagram.android",
    "tiktok": "com.zhiliaoapp.musically",
  };
  function directIntentOf(text){
    const t = String(text||"").trim();
    for(const pat of DIRECT_INTENT){
      const m = t.match(pat.re);
      if(m){
        const slots={};
        if(pat.slot) slots[pat.slot] = (m[1]||m[m.length-1]||"").trim();
        if(pat.slots) pat.slots.forEach((k,i)=> slots[k] = (m[i+1]||"").trim());
        return { action: pat.action, slots, raw: t, via: "direct_regex" };
      }
    }
    return null;
  }
  function appPackageFor(name){
    const k = String(name||"").toLowerCase().trim();
    if(!k) return null;
    if(k.includes(".")) return k;
    if(APP_ALIASES[k]) return APP_ALIASES[k];
    for(const [alias,pkg] of Object.entries(APP_ALIASES)){
      if(k.includes(alias)) return pkg;
    }
    return k.replace(/\s+/g, ".");
  }

  // Contactos: query via content provider (READ_CONTACTS) si el permiso existe; fallback vacío
  // NEUTRALIZADO 2026-09-29. Esto era INYECCION DE COMANDOS como root, confirmada en
  // ejecucion: `safe` escapaba comillas SIMPLES pero el valor se interpolaba dentro de
  // COMILLAS DOBLES de un comando ejecutado via `su -c`, y ni siquiera pasaba por
  // hasShellMeta. Reproduje el codigo real con un payload que solo hacia `echo` y el
  // comando inyectado se ejecuto.
  //
  // No se "arregla" escapando mejor: cualquier sanizador de texto libre para un shell
  // es un parser de shell, y no se va a escribir uno. Leer contactos por nombre es una
  // capacidad que el sistema operativo ya trae; construirla aqui era ademas una
  // pantalla de texto libre dentro de un shell con privilegios de root.
  //
  // Se conserva: enviar WhatsApp con un numero EXPLICITO. Se pierde: resolver un
  // nombre a su numero. Lo da el usuario, o el asistente del sistema.
  async function queryContacts(q){
    if(!q || q.trim().length<2) return [];
    const out = { stdout: "", stderr: "", code: 0 };
    // El segundo `content query` (nombre -> telefono) se ha eliminado con el primero.
    // Interpolarba `name` —que venia de parsear la salida de un shell— dentro de
    // comillas dobles de otro comando `content query`: la MISMA clase de inyeccion,
    // en un segundo sitio. Con la salida vacia era inalcanzable, y el codigo muerto
    // que parece vivo es peor que el codigo muerto que no existe.
    const hits = [];
    // si no hubo hits por content, al menos devuelve el q como contacto tentativo
    if(hits.length===0){
      const normalized = q.replace(/[^+0-9a-zA-Z ]/g,"").trim();
      if(/^\+?[0-9 ]{8,}$/.test(normalized)) hits.push({ name: normalized, phones:[normalized.replace(/ /g,"")], source:"raw_phone" });
    }
    return hits.slice(0,5);
  }

  async function executeAssistantAction(action, slots={}, opts={}){
    const started = Date.now();
    let result={};
    // para acciones que piden confirmación, no ejecutar directo
    if(action==="whatsapp_send" && slots.contact && !slots.phone && !opts.forcePhone){
      const hits = await queryContacts(slots.contact);
      if(hits.length===0){
        return { ok:false, need:"phone", message:`No encontré "${slots.contact}" en contactos. Indica número con código país (ej: +51999...).`, action, slots };
      }
      if(hits.length>1 && !opts.selectedIndex && hits.some(h=> h.phones.length===0 || h.phones.length>1)){
        return { ok:false, type:"disambiguation", kind:"contacts", action, slots, options: hits.map((h,i)=> ({ idx:i, name:h.name, phones:h.phones, label:`${h.name}${h.phones.length? ` — ${h.phones[0]}`:""}` })), message:`Varios contactos coinciden con "${slots.contact}". Elige uno:` };
      }
      const chosen = opts.selectedIndex!=null ? hits[opts.selectedIndex] : hits[0];
      const phone = chosen.phones[0] || slots.phone;
      if(!phone) return { ok:false, need:"phone", message:`Contacto "${chosen.name}" sin teléfono. Indica número.`, action, slots, chosen };
      slots.phone = phone.replace(/[^+0-9]/g,"");
      slots.contactName = chosen.name;
    }
    // también para launch ambiguo: si el nombre no mapea a paquete conocido, pide desambiguación
    if(action==="launch" && slots.app){
      const pkg = appPackageFor(slots.app);
      // Solo se interpola si el valor tiene FORMA de nombre. Antes el segundo grep
      // solo eliminaba las comillas dobles, asi que un $ o un backtick en el nombre
      // pedido seguian expandiendose dentro del comando.
      const cand = String(slots.app).slice(0, 24);
      const candSafe = /^[A-Za-z0-9 ._-]{1,24}$/.test(cand) ? cand : null;
      const out = await runShell(`pm list packages 2>&1 | grep -i -E "${pkg.replace(/\./g,"\\.")}" 2>&1 | head -n 5; echo "---"; pm list packages 2>&1 | grep -i ${candSafe ? JSON.stringify(candSafe) : '""'} 2>&1 | head -n 5`, 6000, 1024*1024);
      const pkgs = out.stdout.split("\n").filter(l=> l.includes("package:")).map(l=> l.replace("package:","").trim());
      if(pkgs.length===0){
        return { ok:false, type:"disambiguation", kind:"app", action, slots, options: [], message:`No encontré app "${slots.app}". ¿Quisiste decir WhatsApp, Yape, Cámara, Chrome…?` };
      }
      if(pkgs.length>1){
        const exact = pkgs.find(p=> p===pkg);
        if(exact) slots.pkg = exact; else {
          return { ok:false, type:"disambiguation", kind:"app", action, slots, options: pkgs.slice(0,4).map((p,i)=> ({ idx:i, pkg:p, label:p })), message:`Varias apps coinciden con "${slots.app}". Elige:` };
        }
      } else {
        slots.pkg = pkgs[0] || pkg;
      }
      const pkgFinal = slots.pkg || pkg;
      const amCmd = `am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p ${pkgFinal} 2>&1 || monkey -p ${pkgFinal} -c android.intent.category.LAUNCHER 1 2>&1 || cmd package resolve-activity --brief -c android.intent.category.LAUNCHER ${pkgFinal} 2>&1`;
      const r = await runShell(amCmd, 8000);
      const ok = (r.stdout.includes("Events injected") || r.stdout.includes("Starting:"));
      result = { ok, pkg: pkgFinal, stdout: r.stdout.slice(0,1200), stderr:r.stderr.slice(0,400) };
      return { ok, action, slots, result, elapsedMs: Date.now()-started };
    }

    switch(action){
      case "launch":
        return { ok:false, type:"disambiguation", kind:"app", action, slots, options:[], message:"App no especificada" };
      case "screenshot": {
        const r = await runShell(`nsenter -t 1 -m -- sh -c 'screencap -p 2>/dev/null | base64 -w 0 2>/dev/null || screencap -p 2>/dev/null | base64 2>/dev/null'`, 25000, MAX_BUFFER);
        const b64 = r.stdout.trim().replace(/\s/g,"");
        if(b64.length<1000) return { ok:false, action, error:"screenshot vacío", stdout:r.stdout.slice(0,400) };
        return { ok:true, action, result:{ b64, len:b64.length, approx_bytes: Math.floor(b64.length*0.75) }, elapsedMs: Date.now()-started };
      }
      case "volume_up": {
        const r = await runShell(`input keyevent 24; input keyevent 24 2>&1; echo vol_up; dumpsys audio 2>&1 | grep -i "STREAM_MUSIC.*level" | head -n 2`, 5000);
        return { ok:true, action, result:{ stdout:r.stdout.slice(0,800) }, elapsedMs: Date.now()-started };
      }
      case "volume_down": {
        const r = await runShell(`input keyevent 25; input keyevent 25 2>&1; echo vol_down`, 5000);
        return { ok:true, action, result:{ stdout:r.stdout.slice(0,600) }, elapsedMs: Date.now()-started };
      }
      case "volume_mute": {
        const r = await runShell(`input keyevent 164 2>&1; echo mute; input keyevent 164 2>&1; echo mute2`, 5000);
        return { ok:true, action, result:{ stdout:r.stdout.slice(0,600) }, elapsedMs: Date.now()-started };
      }
      case "lock_screen": {
        const r = await runShell(`input keyevent 26 2>&1; echo lock`, 4000);
        return { ok:true, action, result:{ stdout:r.stdout.slice(0,400) }, elapsedMs: Date.now()-started };
      }
      case "unlock_screen": {
        const r = await runShell(`input keyevent 82 2>&1; sleep 0.3; input swipe 540 1800 540 600 300 2>&1; echo unlock_try`, 6000);
        return { ok:true, action, result:{ stdout:r.stdout.slice(0,600) }, elapsedMs: Date.now()-started };
      }
      case "wifi_on": { const r = await runShell(`cmd wifi set-wifi-enabled enabled 2>&1; svc wifi enable 2>&1; echo wifi_on`, 6000); return { ok:true, action, result:{ stdout:r.stdout.slice(0,600)} , elapsedMs: Date.now()-started }; }
      case "wifi_off": { const r = await runShell(`cmd wifi set-wifi-enabled disabled 2>&1; svc wifi disable 2>&1; echo wifi_off`, 6000); return { ok:true, action, result:{ stdout:r.stdout.slice(0,600)} , elapsedMs: Date.now()-started }; }
      case "bluetooth_on": { const r = await runShell(`cmd bluetooth_manager enable 2>&1; svc bluetooth enable 2>&1; echo bt_on`, 6000); return { ok:true, action, result:{ stdout:r.stdout.slice(0,600)} , elapsedMs: Date.now()-started }; }
      case "bluetooth_off": { const r = await runShell(`cmd bluetooth_manager disable 2>&1; svc bluetooth disable 2>&1; echo bt_off`, 6000); return { ok:true, action, result:{ stdout:r.stdout.slice(0,600)} , elapsedMs: Date.now()-started }; }
      case "whatsapp_send_prompt": {
        // si el usuario dijo "manda whatsapp a Juan" sin texto, pide texto
        const hits = await queryContacts(slots.contact||"");
        if(hits.length===0) return { ok:false, need:"contact", message:`No encontré "${slots.contact}". Indica nombre o número.`, action, slots };
        if(hits.length>1) return { ok:false, type:"disambiguation", kind:"contacts", action:"whatsapp_send", slots:{...slots, text:""}, options: hits.map((h,i)=> ({ idx:i, name:h.name, phones:h.phones, label:`${h.name}${h.phones[0]? ` — ${h.phones[0]}`:""}` })), message:`Varios contactos coinciden con "${slots.contact}". Elige:` };
        return { ok:false, type:"disambiguation", kind:"whatsapp_text", action:"whatsapp_send", slots:{ contact: hits[0].name, phone: hits[0].phones[0]||"", text:"" }, options:[], message:`¿Qué mensaje le envío a ${hits[0].name}${hits[0].phones[0]? ` (${hits[0].phones[0]})`:""}?` };
      }
      case "whatsapp_send": {
        const phone = String(slots.phone||"").replace(/[^0-9+]/g,"").replace(/^\+0/,"+");
        const text = String(slots.text||"").trim();
        if(!phone) return { ok:false, need:"phone", message:"Falta teléfono", action, slots };
        if(!text) return { ok:false, need:"text", message:"Falta texto del mensaje", action, slots };
        // encodeURIComponent() NO escapa la comilla simple: `'` esta en su lista de
        // caracteres seguros. Como la uri va entre comillas SIMPLES en el comando,
        // un texto con `'` cerraba la cadena y ejecutaba lo quepusiera detras. Ultima
        // via de inyeccion de este archivo, y era real.
        const encoded = encodeURIComponent(text).replace(/'/g, "%27").replace(/\(/g, "%28").replace(/\)/g, "%29");
        const uri = `https://api.whatsapp.com/send?phone=${phone.replace("+","")}&text=${encoded}`;
        // Comprobacion en vez de fe: la uri solo puede ser esquema+host+query.
        if (!/^https:\/\/api\.whatsapp\.com\/send\?phone=[0-9+]+&text=[A-Za-z0-9%._~*-]*$/.test(uri)) {
          return { ok:false, need:"text", message:"El texto del mensaje tiene caracteres no admitidos en la uri", action, slots };
        }
        let r = await runShell(`am start -a android.intent.action.VIEW -d ${JSON.stringify(uri)} 2>&1 | head -n 20; echo WA_VIEW`, 8000);
        const ok = r.stdout.includes("Starting:") || r.stdout.includes("WA_VIEW");
        // auto-enviar via a11y polling loop — retry clickText("Enviar") up to 5 times with 600ms intervals (spec 5)
        let autoSend = { tried:false, attempts: 0, success: false };
        if(ok){
          autoSend.tried = true;
          let lastResult = null;
          for (let attempt = 1; attempt <= 5; attempt++) {
            await new Promise(r2=> setTimeout(r2, 600));
            const fwd = await new Promise(resolve=>{
              const pr = http.request({ hostname:"127.0.0.1", port:8766, path:"/a11y", method:"POST", headers:{"Content-Type":"application/json", "X-Aegis-Token": AEGIS_TOKEN} }, rr=>{
                const c=[]; rr.on("data",x=> c.push(x)); rr.on("end",()=> resolve({ ok:true, status: rr.statusCode, body: Buffer.concat(c).toString("utf8") }));
              });
              pr.on("error", e=> resolve({ ok:false, error:String(e) }));
              pr.write(JSON.stringify({ action:"clickText", text:"Enviar" }));
              pr.end();
            });
            autoSend.attempts = attempt;
            if (!fwd.ok) {
              lastResult = { ok: false, error: fwd.error, attempt };
            } else {
              try { lastResult = JSON.parse(fwd.body); } catch { lastResult = { body: fwd.body, attempt }; }
              // Check if click succeeded — CompanionService returns {ok:true} when click found
              const clickedOk = lastResult && (lastResult.ok === true || lastResult.clicked === true || String(lastResult).includes('"ok":true'));
              // Also consider status 200 as potential success — but verify ok field
              if (fwd.status === 200 && (lastResult.ok === true || lastResult.status === 200)) {
                autoSend.success = true;
                autoSend.result = { ...lastResult, attempts: attempt, success: true };
                break;
              }
              // Fallback: if a11y returned 200 with any body, treat as success on last attempt only if no error
              if (fwd.status === 200 && !lastResult.error) {
                autoSend.success = lastResult.ok !== false;
                autoSend.result = { ...lastResult, attempts: attempt, success: autoSend.success };
                if (autoSend.success) break;
              }
              autoSend.result = { ...lastResult, attempts: attempt, success: false };
            }
            // If bridge offline (fwd.ok false with ECONNREFUSED), stop retrying and hint manual
            if (!fwd.ok && String(fwd.error || "").includes("ECONNREFUSED")) {
              autoSend.result = { error: fwd.error, hint:"Bridge 8766 offline — pulsa Enviar manualmente", attempts: attempt, success: false };
              break;
            }
          }
          if (!autoSend.success && !autoSend.result) {
            autoSend.result = { error: "max retries reached", hint:"WhatsApp abierto — pulsa Enviar manualmente si no se envió solo", attempts: 5, success: false };
          } else if (!autoSend.success && autoSend.result && !autoSend.result.hint) {
            autoSend.result.hint = "WhatsApp abierto — pulsa Enviar manualmente si no se envió solo";
          }
        }
        return { ok, action, slots, result:{ uri, stdout:r.stdout.slice(0,800), autoSend }, elapsedMs: Date.now()-started };
      }
      case "call": {
        const hits = await queryContacts(slots.contact||"");
        if(hits.length===0) return { ok:false, need:"contact", message:`No encontré "${slots.contact}"`, action, slots };
        if(hits.length>1) return { ok:false, type:"disambiguation", kind:"contacts", action, slots, options: hits.map((h,i)=> ({ idx:i, name:h.name, phones:h.phones, label:`${h.name}${h.phones[0]? ` — ${h.phones[0]}`:""}` })), message:`Varios contactos para "${slots.contact}". Elige:` };
        const phone = hits[0].phones[0] || "";
        if(!phone) return { ok:false, need:"phone", message:`Contacto ${hits[0].name} sin teléfono`, action, slots };
        const r = await runShell(`am start -a android.intent.action.CALL -d tel:${phone.replace(/[^+0-9]/g,"")} 2>&1 | head -n 10; echo CALL`, 6000);
        return { ok:true, action, slots:{...slots, phone, contactName:hits[0].name}, result:{ stdout:r.stdout.slice(0,600)}, elapsedMs: Date.now()-started };
      }
      default: {
        return { ok:false, error:`acción no soportada: ${action}`, action, slots };
      }
    }
  }

  if(pathname==="/api/assistant/intent" && req.method==="POST"){
    try{
      const raw = await readJsonBody(req, 64*1024);
      const { text, lang } = JSON.parse(raw||"{}");
      if(!text || !String(text).trim()) return json(res, 400, { error:"text requerido" });
      const direct = directIntentOf(text);
      if(direct){
        // para acciones que necesitan parámetros faltantes, delegar a execute con disambiguation
        if(direct.action==="whatsapp_send_prompt" || (direct.action==="whatsapp_send" && !direct.slots.text)){
          // responde disambiguation inmediata sin llamar execute
          const execRes = await executeAssistantAction(direct.action, direct.slots);
          if(execRes.type==="disambiguation") return json(res, 200, { ok: true, data: execRes });
          return json(res, 200, { ok: true, data: direct });
        }
        // si launch tiene app muy genérica, verificar si necesita disambiguation
        if(direct.action==="launch"){
          const check = await executeAssistantAction(direct.action, direct.slots);
          if(check.type==="disambiguation" && (check.options||[]).length>0) return json(res, 200, { ok: true, data: check });
          return json(res, 200, { ok: true, data: { ...direct, resolved: check } });
        }
        return json(res, 200, { ok: true, data: direct });
      }
      // ambiguo: pide al LLM que clasifique (si opencode está sano, no gastamos tokens aquí — devolvemos estructura para que frontend llame a LLM)
      return json(res, 200, { ok: true, data: { action:"llm_classify", slots:{ text: String(text).slice(0,600) }, hint: "Texto ambiguo — envíalo a opencode como mensaje con contexto assistant_mode. Si el LLM devuelve JSON {action, slots}, reenvía a /api/assistant/execute.", raw: text, lang: lang||"es" } });
    }catch(e){ return json(res, 500, { error:String(e).slice(0,600) }); }
  }
  if(pathname==="/api/assistant/execute" && req.method==="POST"){
    try{
      const raw = await readJsonBody(req, 64*1024);
      const { action, slots, options, selectedIndex, forcePhone } = JSON.parse(raw||"{}");
      if(!action) return json(res, 400, { error:"action requerido" });
      // chip en progreso: log + header para que pill lo detecte
      log.info(`[assistant] execute ${action} ${JSON.stringify(slots||{}).slice(0,300)}`);
      res.setHeader("X-Assistant-Action", action);
      const r = await executeAssistantAction(action, slots||{}, { selectedIndex: selectedIndex ?? options?.selectedIndex, forcePhone: forcePhone ?? !!slots?.phone });
      // si es disambiguation, responde 200 con type para que frontend renderice tarjetas
      const code = r.ok===false && r.type==="disambiguation" ? 200 : (r.ok ? 200 : 400);
      // A-3: 2xx SIEMPRE {ok:true,data:r} (aunque r traiga ok:false en disambiguation);
      // 4xx deja pasar r con ok:false y normalizeEnvelope convierte error -> {code,message}.
      if (code >= 400) return json(res, code, r);
      return json(res, 200, { ok: true, data: r });
    }catch(e){ return json(res, 500, { error:String(e).slice(0,800) }); }
  }

  // /api/* sin ruta conocida -> 404 JSON (nunca el fallback SPA 200 text/html)
  if (isApi) {
    return json(res, 404, { ok: false, error: { code: "NOT_FOUND", message: "unknown api route", path: pathname } });
  }

  // 3) static
  let fp = path.join(__dirname, "public", pathname==="/" ? "index.html" : pathname.slice(1));
  // path traversal guard
  if(!fp.startsWith(path.join(__dirname,"public"))) return send(res,403,"forbidden");
  if(fs.existsSync(fp) && fs.statSync(fp).isDirectory()) fp = path.join(fp, "index.html");
  if(!fs.existsSync(fp)){
    // spa fallback para rutas /app etc: serve index
    const idx = path.join(__dirname,"public","index.html");
    if(fs.existsSync(idx)) fp = idx; else return json(res,404,{error:"not found", path:pathname});
  }
  const ext = path.extname(fp).toLowerCase();
  const mime = MIME[ext] || "application/octet-stream";
  // simple etag/cache disable for dev
  res.writeHead(200, withCors(res, {"Content-Type": mime, "Cache-Control":"no-cache"}));
  // A-4: read stream sin manejador de 'error' => uncaughtException + petición
  // colgada si el fichero desaparece/falla entre statSync y la lectura.
  const staticStream = fs.createReadStream(fp);
  staticStream.on("error", (e) => {
    log.error(`[hub] static read err ${fp}`, { err: e.message });
    if (!res.writableEnded) { try { res.end(); } catch (_) {} }
  });
  // El cliente puede colgarse: un `curl | head`, el usuario cerrando la pestana, la
  // app cerrando el stream al rotar. Eso es NORMAL. Pero al caer el socket, el write
  // lanza EPIPE, y `res` no tiene manejador global de 'error': el error subia como
  // evento del stream y de ahi a `uncaughtException`, que logueaba COMO ERROR.
  // MEDIDO 2026-09-29: 578 lineas de EPIPE de golpe —la mitad del log— por un solo
  // cliente que cerro antes de tiempo. Cada linea es una escritura en /sdcard, que es
  // FUSE (y un proceso esperando a FUSE se queda en estado D): es la misma tormenta de
  // escritura que cuelga el movil, y ademas tapa el diagnostico de verdad.
  res.on("error", (e) => {
    try { staticStream.destroy(); } catch (_) {}
    const code = e && e.code;
    if (code === "EPIPE" || code === "ERR_STREAM_DESTROYED" || code === "ERR_STREAM_PREMATURE_CLOSE") return;
    log.warn(`[hub] static write err ${fp}`, { err: e.message });
  });
  staticStream.pipe(res);
}

// A-4 (BACKEND-BUG-09/10): red de seguridad FINAL del dispatcher. Cualquier rama
// que escape a su try/catch interno termina aquí y SIEMPRE cierra la respuesta:
// 500 envelope si aún no se enviaron headers; si ya se enviaron, fin de stream
// (nunca doble writeHead -> ERR_HTTP_HEADERS_SENT, nunca petición colgada).
// Frecuencia de cada 500, para NO volcar una linea por peticion cuando un fallo se
// repite. MEDIDO 2026-09-27: un ReferenceError en /api/sessions/inflight produjo
// 4367 lineas de error identicas en los logs rotados, porque la app consulta esa
// ruta cada 2 s con espera fija y sin backoff ante un 5xx. Escribir (y rotar) esas
// lineas en /sdcard, que es FUSE, es lo que genera la cola de procesos en estado D:
// un proceso esperando a FUSE no se puede matar con una senal.
//
// NO se silencia el error: se dice la primera vez, luego cada N, y el total al final
// del minuto. El diagnostico se conserva entero y el volumen de escritura deja de
// ser el problema.
const UNHANDLED_LOG_EVERY = 25;
const unhandledSeen = new Map(); // "METHOD url" -> {count, firstAt}
function logUnhandledRate(key) {
  const now = Date.now();
  let rec = unhandledSeen.get(key);
  if (!rec) { rec = { count: 0, firstAt: now }; unhandledSeen.set(key, rec); }
  rec.count++;
  // La ventana de un minuto caduca: si el fallo vuelve a aparecer mas tarde, se
  // vuelve a avisar como PRIMERA vez, no como continuacion de un一分钟 viejo.
  if (now - rec.firstAt > 60_000) {
    if (rec.count > 1) {
      log.warn(`[hub] ${key} dejo de fallar: ${rec.count} errores en 60 s (ya no en cadena)`);
    }
    rec.count = 0; rec.firstAt = now;
    return true;
  }
  if (rec.count === 1 || rec.count % UNHANDLED_LOG_EVERY === 0) return true;
  return false;
}

function respondUnhandledRequestError(req, res, e) {
  const msg = String((e && (e.message || e.stack)) || e).slice(0, 800);
  const key = `${req && req.method} ${((req && req.url) || "").slice(0, 140)}`;
  if (logUnhandledRate(key)) {
    log.error(`[hub] unhandled request error ${key}`, {
      err: (e && e.stack) || String(e),
      suprimidos: unhandledSeen.get(key)?.count > 1
        ? `1 de cada ${UNHANDLED_LOG_EVERY} hasta que pare (contador en linea siguiente)`
        : undefined
    });
  }
  if (res.headersSent) {
    if (!res.writableEnded) { try { res.end(); } catch (_) {} }
    return;
  }
  try {
    json(res, 500, { ok: false, error: { code: "INTERNAL_ERROR", message: msg } });
  } catch (_) {
    try {
      res.writeHead(500, { "Content-Type": "application/json; charset=utf-8" });
      res.end(`{"ok":false,"error":{"code":"INTERNAL_ERROR","message":${JSON.stringify(msg)}}}`);
    } catch (__) {}
  }
}

const server = http.createServer((req, res) => {
  handleRequest(req, res).catch((err) => respondUnhandledRequestError(req, res, err));
});

server.on('error', e => {
  if (String(e.code) === 'EADDRINUSE') {
    log.error(`[hub] puerto ${HUB_PORT} ocupado — deja el existente y salgo con 0`);
    process.exit(0);
  }
  log.error('[hub] server error', { err: (e && e.stack) || String(e) });
});
server.on('clientError', (err, socket) => {
  log.warn('[hub] clientError', { err: String(err).slice(0,300) });
  try { socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n'); } catch (_) {}
});
server.keepAliveTimeout = 125000;
server.headersTimeout = 130000;
server.requestTimeout = 135000;
server.maxHeadersCount = 100;
// F6: repara en el arranque vínculos de proveedor corrompidos por el header
// X-Provider (sesiones ses_ marcadas como antigravity o viceversa). El prefijo
// del id es la fuente de verdad del proveedor de nacimiento.
function sanitizeSessionProviders() {
  try {
    const store = loadProjectsStore();
    let fixed = 0;
    for (const p of store.projects || []) {
      for (const s of p.sessions || []) {
        const conv = typeof s.sessionId === "string" && s.sessionId.startsWith("ses_") ? "opencode" : null;
        if (conv && s.provider && s.provider !== conv) { s.provider = conv; fixed++; }
      }
    }
    if (fixed) { saveProjectsStore(store); log.warn(`[hub] F6: ${fixed} vinculos de proveedor reparados por prefijo de sesion`); }
  } catch (e) { log.warn("[hub] sanitizeSessionProviders failed", { err: e.message }); }
}
sanitizeSessionProviders();

// =====================================================================
// EJECUCIONES EN VUELO + FIN REAL DE TURNO
// =====================================================================
// Un vigilante se suscribe al stream de eventos de OpenCode (`GET /api/event`) y
// sigue `session.execution.*` e `session.inbox.delivered`.
//
// POR QUE NO INFERIRLO DE `time.completed`: ese campo cierra el MENSAJE, no el turno.
// Un mensaje del asistente lleva `completed` en cuanto su segmento termina, o sea
// justo despues de cada `bash` con exit 0, aunque el agente vaya a seguir trabajando.
// De ahi que el divisor "respuesta final" saltaba a mitad. El evento
// `session.execution.succeeded` es el unico que significa de verdad "no va a hacer
// nada mas hasta que le hables".
//
// Y no se puede llevar la cuenta desde el Hub al aceptar el prompt, porque los turnos
// del CLI entran a OpenCode SIN pasar por aqui. El stream ve los dos.
//
// Payloads reales (build 36228701460, 2026-09-26):
//   session.execution.started   -> {"sessionID":"ses_..."}
//   session.execution.succeeded -> {"sessionID":"ses_..."}
//   session.inbox.enqueued      -> {"inboxID","sessionID","item"}
//   session.inbox.delivered     -> {"sessionID","inboxID"}
const inflightSessions = new Map();   // sessionId -> epoch ms de inicio del turno
const lastSeenAt = new Map();        // sessionId -> epoch ms del ULTIMO evento visto
const deliveredInbox = new Map();    // sessionId -> epoch ms del ultimo cierre
let execWatcher = null;

// Ventana de gracia al terminar un turno.
//
// El reporte del usuario: "el circulo de carga a veces desaparece pero el chat sigue
// ejecutando". Midiendo el stream de eventos NO hay eventos de ejecucion intermedios
// (solo started y succeeded), asi que la causa no es un evento mal clasificado: es el
// HUECO entre una ejecucion que termina y la siguiente que arranca. Durante ese hueco
// la sesion no esta ocupada en el mapa y el circulo se apaga aunque el turno siga.
//
// Asi que el fin de turno NO es inmediato: se agenda y se cancela si llega un `started`
// antes. Cubre a la vez las dos cosas que el usuario quiere unidas — el circulo y el
// divisor "respuesta final" — porque ambos leen de este mismo registro.
const TURN_END_GRACE_MS = 5000;
const pendingIdle = new Map();

function markBusy(sid) {
  if (!sid) return;
  inflightSessions.set(sid, Date.now());
  // Un turno nuevo borra la marca de "este turno se cerro" del turno anterior. Sin esto
  // hay una ventana entre que se envia el prompt y que el vigilante ve el
  // `session.execution.started`: durante ella deliveredInbox sigue diciendo que el turno
  // acabo, isTurnOver devuelve true y la app pinta el divisor, dispara la notificacion y
  // apaga el circulo de carga con un bash todavia corriendo debajo.
  deliveredInbox.delete(sid);
  lastSeenAt.set(sid, Date.now());
  const t = pendingIdle.get(sid);
  if (t) { clearTimeout(t); pendingIdle.delete(sid); }
}

// Cualquier evento de la sesion prueba que sigue VIVA, aunque no sea de ejecucion
// (los hay a destajo: session.step.*, session.text.delta, session.usage.updated...).
// Se usa para distinguir un turno en marcha de un registro que se quedo pegado.
function touchSession(sid) {
  if (!sid) return;
  lastSeenAt.set(sid, Date.now());
  // A-3: touchSession significa "algo toco esta sesion", que es justo la condicion
  // para que sus mensajes puedan haber cambiado. Se invalida aqui y no solo en el
  // vigilante SSE porque touchSession tambien se llama desde el resto del Hub.
  invalidarCacheMensajes(sid);
}
function markIdle(sid) {
  if (!sid) return;
  if (pendingIdle.has(sid)) return;
  const t = setTimeout(() => {
    pendingIdle.delete(sid);
    inflightSessions.delete(sid);
    lastSeenAt.delete(sid);
    deliveredInbox.set(sid, Date.now());
  }, TURN_END_GRACE_MS);
  if (t.unref) t.unref();
  pendingIdle.set(sid, t);
}
function isTurnOver(sid) {
  if (!sid) return false;
  if (inflightSessions.has(sid)) return false;
  const since = deliveredInbox.get(sid);
  return typeof since === "number";
}

function startExecutionWatcher(adapter) {
  if (execWatcher) return;
  let stopped = false;
  // El socket SSE vivo. `stop()` solo ponia un flag, asi que la conexion se quedaba
  // abierta hasta que el proceso muriera: cada reinicio del Hub dejaba su suscripcion
  // colgada del lado de `opencode serve`, que es donde se acumula la memoria.
  let liveReq = null, liveRes = null;
  const connect = () => {
    if (stopped) return;
    let auth = {};
    try { auth = adapter._authHeader ? adapter._authHeader() : {}; } catch (_) {}
    liveReq = null;
    const req = http.request(`http://${OPENCODE_HOST}:${OPENCODE_PORT}/api/event`,
      { headers: { Accept: "text/event-stream", ...auth } }, (res) => {
      liveRes = res;
      if (res.statusCode !== 200) { res.resume(); setTimeout(connect, 5000); return; }
      log.info("[exec] vigilante conectado a /api/event");
      let buf = "";
      res.setEncoding("utf8");
      res.on("data", (chunk) => {
        buf += chunk;
        if (buf.length > 1048576) buf = buf.slice(-4096);
        let nl;
        while ((nl = buf.indexOf("\n")) !== -1) {
          const line = buf.slice(0, nl).trim();
          buf = buf.slice(nl + 1);
          if (!line.startsWith("data:")) continue;
          let ev;
          try { ev = JSON.parse(line.slice(5).trim()); } catch (_) { continue; }
          const sid = ev && ev.data && ev.data.sessionID;
          if (!sid) continue;
          // A-3: cualquier evento de esta sesion puede haber cambiado sus mensajes.
          invalidarCacheMensajes(sid);
          if (ev.type === "session.execution.started") markBusy(sid);
          else if (typeof ev.type === "string" && ev.type.startsWith("session.execution.")) markIdle(sid);
          // `permission.asked` NO empieza por "session.", asi que antes caia por las tres
          // ramas y se descartaba en silencio. Consecuencia muy visible: un turno
          // bloqueado esperando un permiso es INDISTINGUIBLE de un turno trabajando
          // (OpenCode no tiene evento de estado "bloqueado": la maquina de estados solo
          // tiene started/succeeded/failed/interrupted), asi que el prompt se quedaba
          // colgado sin que nada lo indicara. Se trata como sesion viva: la herramienta
          // esta suspendida, no terminada.
          else if (ev.type === "permission.asked") { markBusy(sid); touchSession(sid); }
          else if (ev.type === "permission.replied") touchSession(sid);
          else if (typeof ev.type === "string" && ev.type.startsWith("session.")) touchSession(sid);
        }
      });
      const again = () => { if (!stopped) setTimeout(connect, 3000); };
      res.on("end", again);
      res.on("error", again);
    });
    req.on("error", () => { if (!stopped) setTimeout(connect, 5000); });
    liveReq = req;
    req.end();
  };
  connect();
  // Red de seguridad: si se pierde un 'ended', la sesion no puede quedar ocupada para siempre.
  const sweep = setInterval(() => {
    const cut = Date.now() - 15 * 60 * 1000;
    for (const [sid, since] of inflightSessions) {
      if (since < cut && !pendingIdle.has(sid)) inflightSessions.delete(sid);
    }
  }, 30000);
  if (sweep.unref) sweep.unref();
  execWatcher = {
    stop() {
      stopped = true;
      clearInterval(sweep);
      // Cerrar de verdad: sin esto el servidor de opencode sigue viendo la
      // suscripcion hasta que muere el proceso, y los reinicios del Hub la acumulan.
      for (const h of [liveRes, liveReq]) { try { if (h) h.destroy(); } catch (_) {} }
      liveRes = null; liveReq = null;
    }
  };
}

server.listen(HUB_PORT, "127.0.0.1", async ()=>{
  log.info(`[hub] listening http://127.0.0.1:${HUB_PORT} (loopback only)`);
  log.info(`  local  : http://127.0.0.1:${HUB_PORT}`);
  if (OC_HAS_SERVICE && OC_ARG_PORT && OC_ARG_PORT !== OPENCODE_PORT) {
    log.warn(`  puerto  : se IGNORA --opencode-port ${OC_ARG_PORT} y se usa el servicio registrado en ${OPENCODE_PORT} (un solo servidor, el que usa el CLI del opencode)`);
  }
  log.info(`  proxy  : /opencode/* -> http://${OPENCODE_HOST}:${OPENCODE_PORT}`);
  // A-2: vigilante de ejecuciones. Da el "final del final" del turno, el estado de
  // ejecucion para la lista de chats y la confirmacion de entrega del mensaje. Un
  // fallo aqui NUNCA debe impedir que el hub levante.
  try { startExecutionWatcher(opencodeAdapter); }
  catch (e) { log.warn("[exec] vigilante no arranca", { err: e.message }); }
  log.info(`  api    : /api/status  /api/device/*`);
  log.info(`  session: /api/system/status (ownership)  /api/system/session-info (pid/uptime)`);
  log.info(`  pid    : ${process.pid}  node ${process.version}  keepAlive 125s (multimodal streaming)`);
  // A-2: arranque del MOTOR (jobScheduler + eventBus) — con try/catch para que
  // un fallo del motor NUNCA impida el arranque del hub.
  try {
    // eventBus: logger real como primer suscriptor (publish difunde también el
    // nombre base del evento => recibe eventos de todos los proyectos).
    const motorLog = createLogger("motor");
    for (const evt of Object.values(EVENTS)) {
      eventBus.on(evt, (data) => {
        try { motorLog.info(`event ${evt}`, (data && typeof data === "object") ? data : { data }); } catch (_) {}
      });
    }
    // jobScheduler: job de mantenimiento real (barre meta obsoleto de companion)
    // y arranque del scheduler. Sin esto /api/jobs y start() no tendrían efecto.
    if (typeof jobScheduler.registerJob === "function" && !jobScheduler.jobs.has("companion-meta-sweep")) {
      jobScheduler.registerJob("companion-meta-sweep", 60000, () => { clearStaleCompanionMetaIfNeeded(); });
    }
    if (typeof jobScheduler.start === "function") jobScheduler.start();
    log.info(`  motor  : jobScheduler started (${jobScheduler.jobs.size} jobs) + eventBus logger suscrito a ${Object.values(EVENTS).length} eventos`);
  } catch (e) {
    log.error(`[hub] motor startup error: ${e.message}`);
  }
  // A-7: arranque estructurado en UNA sola línea (puerto, bind, routers montados, jobs activos)
  log.info("hub startup", {
    port: HUB_PORT,
    bind: "127.0.0.1",
    routers: ["skills", "projects", "jobs", "agents", "workflows", "content"],
    jobs: [...jobScheduler.jobs.keys()],
    proxy: `${OPENCODE_HOST}:${OPENCODE_PORT}`,
    pid: process.pid,
    node: process.version
  });
  // Non-destructive startup probe — spec (1): detect existing serve on startup without launching
  // If health already true, we adopt it as termux-native (or companion-owned if prior meta exists) and do NOT auto-launch.
  try {
    const h = await probeOpencodeHealth();
    clearStaleCompanionMetaIfNeeded();
    const pids = scanServePids();
    const ownership = classifyOwnership(h, pids);
    if (h.healthy) {
      log.info(`  opencode: existing ${ownership} session detected — health OK ${JSON.stringify(h).slice(0,120)} pids=${pids.join(",")||"unknown"} — not launching (non-destructive)`);
    } else {
      log.info(`  opencode: no session (ownership none) — not auto-launching on startup; use POST /api/system/start when none`);
    }
    log.info(`  sessionOwnership: ${ownership} (hub startup)`);
  } catch (e) {
    log.error(`  opencode: startup probe error ${e.message}`);
  }

  // print LAN IPs via shell (nsenter-aware)
  runShell(`nsenter -t 1 -m -- ip addr 2>&1 | grep -oE '192\\.168\\.[0-9]+\\.[0-9]+' | head -5; nsenter -t 1 -m -- getprop 2>&1 | grep -oE '192\\.168\\.[0-9]+\\.[0-9]+' | head -5; ip addr 2>&1 | grep -oE '192\\.168\\.[0-9]+\\.[0-9]+' | head -5; getprop 2>&1 | grep -oE '192\\.168\\.[0-9]+\\.[0-9]+' | head -5`).then(o=>{
    const ips = [...new Set((o.stdout.match(/192\.168\.\d+\.\d+/g) || []))];
    if(ips.length) log.info(`  LAN    : inaccesible — el hub solo escucha en 127.0.0.1 (IPs detectadas: ${ips.join(", ")})`);
    else log.info(`  LAN    : inaccesible — el hub solo escucha en 127.0.0.1`);
  });
});
