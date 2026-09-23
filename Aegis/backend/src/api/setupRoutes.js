// setupRoutes.js — API de cierre del wizard de Aegis (FASE F3 · backend)
//
// Rutas (TODAS bajo /api/setup => cubiertas por el middleware X-Aegis-Token
// del hub, Fase 0: pathname.startsWith("/api/") y sólo GET /api/health exento):
//   GET  /api/setup/final-check          -> 200 {ok,data:{ready:boolean, checks:[...]}}
//   POST /api/setup/smoke-test           -> 200 {ok,data:{ok:true, reply:"<texto REAL del modelo>"}}
//                                            | 502/504 envelope code:"SMOKE_FAILED"
//   POST /api/setup/auth/antigravity     -> 200 {ok,data:{mode:"manual", command, status}}
//
// Patrón idéntico a bootstrapRoutes.js/skillsRoutes.js: firma
// (req, res, pathname, jsonHelper, readJsonBody), respuestas SIEMPRE vía
// jsonHelper (=> envelope {ok,data}/{ok:false,error:{code,message}} + CORS) y
// `return false` literal = "no es mi ruta" para el contrato de dispatchRoute.
//
// Reglas F3:
//  - Cada check tiene timeout corto (<=2s) y NUNCA lanza: toda excepción se
//    convierte en status "fail" con detail honesto.
//  - El jamás imprime ni devuelve el token OAuth de Antigravity: sólo se usa
//    fs.statSync para saber si el fichero tiene >0 B.
//  - El smoke-test NO inventa respuestas: `reply` es el texto que el modelo
//    devolvió a través de la maquinaria real (OpencodeAdapter del hub).

import fs from "node:fs";
import net from "node:net";
import path from "node:path";
import http from "node:http";
import { execFile } from "node:child_process";
import { snapshot as bootstrapSnapshot } from "../bootstrap/state.js";
import { createLogger } from "../core/logger.js";

const log = createLogger("setup");

const CHECK_TIMEOUT_MS = 2000;   // contrato F3: <=2s por check
const A11Y_TIMEOUT_MS = 1500;    // margen por debajo del budget del check
const SMOKE_TIMEOUT_MS = 60000;  // contrato F3: 60s para el smoke-test

const HOME = process.env.HOME || "/root";
const OPENCODE_HOST = process.env.OPENCODE_HOST || "127.0.0.1";
const OPENCODE_PORT = parseInt(process.env.OPENCODE_PORT || "4096", 10);

// Instalador oficial del CLI agy (documentado en F2/F3; los paquetes npm
// "agy"/"antigravity-cli" son placeholders de terceros: NO usarlos).
const AGY_INSTALL_CMD = "curl -fsSL https://antigravity.google/cli/install.sh | bash";
const AGY_DOCS = "https://antigravity.google/docs/cli/install";

const AGY_BIN_CANDIDATES = [...new Set([
  "/root/.local/bin/agy",                    // binPath por defecto de AntigravityAdapter
  path.join(HOME, ".local/bin/agy"),
  "/usr/local/bin/agy",
  "/usr/bin/agy"
])];
// Mismas rutas que AGY_AUTH_FILES de steps.js — sólo se STATea, nunca se lee.
const AGY_AUTH_FILES = [...new Set([
  "/root/.gemini/antigravity-cli/antigravity-oauth-token",
  path.join(HOME, ".gemini", "antigravity-cli", "antigravity-oauth-token")
])];

function uniq(list) { return [...new Set(list.filter(Boolean))]; }

function findAgyBin() {
  for (const c of AGY_BIN_CANDIDATES) {
    try { if (fs.existsSync(c)) return c; } catch (_) {}
  }
  return null;
}

function findAgyAuth() {
  for (const f of AGY_AUTH_FILES) {
    try {
      const st = fs.statSync(f);
      if (st.isFile() && st.size > 0) return { path: f, size: st.size };
    } catch (_) { /* no existe */ }
  }
  return null;
}

// ---------------------------------------------------------------------------
// Sonda OpenCode — MISMO approach que probeOpencodeHealth() de server.js
// (GET /global/health con timeout corto). Se inyecta desde server.js para que
// no haya dos definiciones de verdad; ésta es sólo el fallback por defecto.
// ---------------------------------------------------------------------------
function defaultProbeOpenCode() {
  return new Promise(resolve => {
    let resolved = false;
    const done = v => { if (!resolved) { resolved = true; resolve(v); } };
    const r = http.get(
      { hostname: OPENCODE_HOST, port: OPENCODE_PORT, path: "/global/health", timeout: 2000, headers: { Connection: "close" } },
      res => {
        let d = "";
        res.on("data", c => (d += c));
        res.on("end", () => {
          try {
            const j = JSON.parse(d);
            done({ up: !!j.healthy || res.statusCode === 200, healthy: !!j.healthy, version: j.version || null });
          } catch {
            const isV2 = res.statusCode === 200 && (d.includes("<title>OpenCode</title>") || d.toLowerCase().includes("opencode"));
            done({ up: res.statusCode === 200, healthy: isV2, version: isV2 ? "v2" : null });
          }
        });
        res.on("error", () => done({ up: false, healthy: false, version: null }));
      }
    );
    r.on("error", () => done({ up: false, healthy: false, version: null }));
    r.setTimeout(2000, () => { try { r.destroy(); } catch (_) {} done({ up: false, healthy: false, version: "timeout" }); });
  });
}

// `opencode --version` (fallback del check "opencode"), sin shell y con timeout.
function opencodeVersion(timeoutMs) {
  return new Promise(resolve => {
    execFile("opencode", ["--version"], { timeout: timeoutMs, env: process.env }, (err, stdout) => {
      resolve(err ? null : String(stdout || "").trim() || "desconocida");
    });
  });
}

// Diagnóstico barato cuando la maquinaria de sesiones NO puede crear sesión:
// mira si el serve expone la API v2 (/api/*) — en cuyo caso el adapter del hub
// (API v1: /session) no es compatible con el serve en marcha. Devuelve UNA frase
// para el message de SMOKE_FAILED (nunca credenciales ni cuerpos con secretos).
function diagnoseOpenCodeApi() {
  return new Promise(resolve => {
    let settled = false;
    const done = text => { if (!settled) { settled = true; resolve(text); } };
    const r = http.get(
      { hostname: OPENCODE_HOST, port: OPENCODE_PORT, path: "/api/session", timeout: 1500, headers: { Connection: "close" } },
      res => {
        res.resume();
        res.on("end", () => {
          if (res.statusCode === 401) {
            done(" (diagnóstico: el serve expone la API de OpenCode v2 en /api/* con Basic auth, mientras la maquinaria del hub — OpencodeAdapter — habla la API v1 /session, que este serve responde con SPA/405: el smoke no puede crear sesión hasta alinear el adapter con el v2)");
          } else if (res.statusCode === 200) {
            done(" (diagnóstico: el serve expone la API v2 en /api/* mientras el adapter del hub usa la API v1 /session)");
          } else {
            done(` (diagnóstico: GET /api/session devolvió HTTP ${res.statusCode})`);
          }
        });
        res.on("error", () => done(""));
      }
    );
    r.on("error", e => done(` (diagnóstico: ${e.code || e.message})`));
    r.setTimeout(1500, () => { try { r.destroy(); } catch (_) {} done(" (diagnóstico: timeout consultando /api/session)"); });
  });
}

// ---------------------------------------------------------------------------
// Los 4 checks del contrato — en ESTE orden y con ESTOS labels literales.
// Cada run() devuelve {status:"ok"|"fail"|"manual", detail} y no lanza nunca
// (el envoltorio runCheck() igual captura cualquier excepción).
// ---------------------------------------------------------------------------
const CHECK_DEFS = [
  {
    id: "opencode",
    label: "OpenCode (proxy4096)",
    async run({ probeOpenCode }) {
      const t0 = Date.now();
      const h = await probeOpenCode();
      if (h && (h.healthy || h.up)) {
        return {
          status: "ok",
          detail: `OpenCode respondiendo en ${OPENCODE_HOST}:${OPENCODE_PORT}${h.version ? ` (${h.version})` : ""}`
        };
      }
      // Sin serve: ¿al menos está instalado el binario? (presupuesto restante del check)
      const remaining = CHECK_TIMEOUT_MS - (Date.now() - t0);
      if (remaining >= 400) {
        const v = await opencodeVersion(Math.min(remaining, 1500));
        if (v) {
          return {
            status: "manual",
            detail: `OpenCode instalado (opencode ${v}) pero el serve en ${OPENCODE_HOST}:${OPENCODE_PORT} no responde — arráncalo (opencode serve / keepalive.sh) y reintenta`
          };
        }
      }
      return {
        status: "fail",
        detail: `OpenCode no disponible: sin serve en ${OPENCODE_HOST}:${OPENCODE_PORT} ni binario "opencode" verificable`
      };
    }
  },
  {
    id: "antigravity",
    label: "Antigravity/Artemis (agy + auth)",
    run() {
      const bin = findAgyBin();
      if (!bin) {
        return {
          status: "fail",
          detail: `binario agy ausente (${AGY_BIN_CANDIDATES.join(", ")}) — instálalo con: ${AGY_INSTALL_CMD} (docs: ${AGY_DOCS})`
        };
      }
      const auth = findAgyAuth();
      if (!auth) {
        // Sin sesión: login MANUAL (verificado en F3: el CLI actual NO tiene
        // subcomando "auth login" — el flujo es interactivo en el propio `agy`
        // y necesita navegador/TTY, así que el hub no lo spawnea).
        return {
          status: "manual",
          detail: `agy en ${bin} pero SIN sesión OAuth en ~/.gemini/antigravity-cli/antigravity-oauth-token — ejecuta \`${bin}\` en una terminal y completa el login (abre el navegador; docs: ${AGY_DOCS})`
        };
      }
      // El contenido del token NUNCA se lee ni se registra: sólo tamaño.
      return {
        status: "ok",
        detail: `agy en ${bin} + sesión OAuth verificada (${auth.path}, ${auth.size} B > 0 — el contenido del token nunca se lee)`
      };
    }
  },
  {
    id: "a11y",
    label: "Servicio de accesibilidad (:8766)",
    run() {
      // Bridge del CompanionService: basta con que ACEPTE la conexión TCP
      // (mismo dato que reportaba el health antiguo: connect ECONNREFUSED 127.0.0.1:8766).
      return new Promise(resolve => {
        let settled = false;
        const finish = (status, detail) => { if (settled) return; settled = true; try { sock.destroy(); } catch (_) {} resolve({ status, detail }); };
        const sock = net.connect({ host: "127.0.0.1", port: 8766 });
        sock.setTimeout(A11Y_TIMEOUT_MS, () => finish("fail", `timeout (${A11Y_TIMEOUT_MS}ms) al conectar con 127.0.0.1:8766 — Abre la app y concede accesibilidad`));
        sock.once("connect", () => finish("ok", "bridge de accesibilidad escuchando en 127.0.0.1:8766"));
        sock.once("error", e => finish("fail", `Abre la app y concede accesibilidad (127.0.0.1:8766 no responde: ${e.code || e.message})`));
      });
    }
  },
  {
    id: "bootstrap",
    label: "Instalación inicial (wizard)",
    run() {
      // state.js normaliza "running" sin runner => "paused" al recargar, así que
      // las fases aquí son siempre coherentes con un hub vivo.
      const st = bootstrapSnapshot();
      const phase = st && st.phase ? st.phase : "desconocida";
      if (phase === "done") return { status: "ok", detail: "wizard completado (phase=done)" };
      if (phase === "running") return { status: "manual", detail: "instalación en curso (phase=running)" };
      return { status: "fail", detail: `wizard sin completar (phase=${phase})` };
    }
  }
];

// Carrera con timeout que RESUELVE (no rechaza) con un marcador: evita tanto el
// cuelgue de un check como promesas colgantes con unhandledRejection.
function raceTimeout(promise, ms) {
  return new Promise(resolve => {
    let settled = false;
    const timer = setTimeout(() => { if (!settled) { settled = true; resolve({ __timeout: true }); } }, ms);
    promise.then(
      v => { if (!settled) { settled = true; clearTimeout(timer); resolve(v); } },
      e => { if (!settled) { settled = true; clearTimeout(timer); resolve({ __error: e }); } }
    );
  });
}

async function runCheck(def, deps) {
  const base = { id: def.id, label: def.label };
  let r;
  try {
    r = await raceTimeout(Promise.resolve().then(() => def.run(deps)), CHECK_TIMEOUT_MS);
  } catch (e) {
    r = { __error: e };
  }
  if (r && r.__timeout) {
    return { ...base, status: "fail", detail: `timeout: el check no respondió en ${CHECK_TIMEOUT_MS}ms` };
  }
  if (r && r.__error) {
    return { ...base, status: "fail", detail: `error interno del check: ${String((r.__error && r.__error.message) || r.__error).slice(0, 200)}` };
  }
  return { ...base, status: r.status, detail: String(r.detail ?? "") };
}

// ---------------------------------------------------------------------------
// Smoke test — maquinaria REAL de sesiones/mensajes del hub (OpencodeAdapter).
// Sesión dedicada "aegis:smoke-test" reutilizada entre llamadas (single-flight:
// dos smokes simultáneos comparten la misma promesa en lugar de duplicarla).
// ---------------------------------------------------------------------------
function smokeError(message, status) {
  const e = new Error(message);
  e.code = "SMOKE_FAILED";
  e.status = status;
  return e;
}

function makeSmokeRunner({ opencodeAdapter, probeOpenCode }) {
  let sessionId = null;
  let inflight = null;

  // Errores de CONEXIÓN transitorios: el serve v1 (1.18.x) resetea conexiones
  // inmediatas tras crear sesión (carrera observado y reproducido en F3:
  // ECONNRESET/"socket hang up" sin espera; con 1,2-2s de margen responde bien).
  // Se reintenta con backoff DENTRO del presupuesto de 60s. Los errores de
  // proveedor/credenciales NO se reintentan (un retry ahí sólo enmascara el motivo).
  const isTransientConnError = msg => /socket hang up|ECONNRESET|EPIPE|connection reset|socket closed/i.test(String(msg));
  const sleep = ms => new Promise(r => setTimeout(r, ms));

  async function run() {
    if (!opencodeAdapter || typeof opencodeAdapter.sendMessage !== "function") {
      throw smokeError("hub sin adapter opencode — no hay máquina de sesiones disponible", 500);
    }
    const deadline = Date.now() + SMOKE_TIMEOUT_MS;
    const h = await probeOpenCode();
    if (!h || !(h.up || h.healthy)) {
      throw smokeError(`OpenCode (${OPENCODE_HOST}:${OPENCODE_PORT}) no responde — proveedor caído`, 502);
    }

    // 1) reutiliza la sesión de smoke si sigue existiendo; si no, la crea (con
    //    reintentos sólo ante errores de conexión transitorios).
    let sid = sessionId;
    if (sid) {
      try {
        const list = await opencodeAdapter.listSessions();
        if (!Array.isArray(list) || !list.some(s => s && s.id === sid)) sid = null;
      } catch (_) { sid = null; }
    }
    let createErr = null;
    if (!sid) {
      for (let i = 0; i < 3 && !sid; i++) {
        if (i) await sleep(1500);
        if (Date.now() > deadline - 4000) break;
        try {
          const created = await opencodeAdapter.createSession({ title: "aegis:smoke-test" });
          sid = created && created.id ? created.id : null;
        } catch (e) {
          createErr = e;
          if (!isTransientConnError(String((e && e.message) || e))) break;
        }
      }
      if (!sid) {
        const diag = await diagnoseOpenCodeApi();
        const why = String((createErr && createErr.message) || createErr || "OpenCode devolvió la sesión sin id").slice(0, 200);
        throw smokeError(`no hay sesión disponible y no se pudo crear: ${why}${diag}`, 502);
      }
      sessionId = sid;
      // Margen de seguridad tras crear: el serve v1 resetea la conexión que llega
      // inmediatamente después de createSession (carrera medido en F3).
      await sleep(1200);
    }

    // 2) mensaje con presupuesto duro de 60s vía AbortController (el adapter
    //    respeta opts.signal => el hub nunca queda colgado en este endpoint) y
    //    hasta 3 intentos si el fallo fue de conexión.
    const budgetMs = Math.max(1000, deadline - Date.now());
    const ac = new AbortController();
    const timer = setTimeout(() => ac.abort(), budgetMs);
    let lastErr = null;
    try {
      for (let attempt = 0; attempt < 3; attempt++) {
        if (attempt) {
          if (Date.now() > deadline - 2000) break;
          await sleep(1500);
        }
        try {
          const msg = await opencodeAdapter.sendMessage(
            sid,
            { text: "Responde exclusivamente: PONG" },
            { signal: ac.signal, agent: "build", mode: "build" }
          );
          const reply = msg && typeof msg.text === "string" ? msg.text.trim() : "";
          if (!reply) {
            throw smokeError("el modelo no devolvió texto (sin credenciales del proveedor, respuesta vacía o timeout interno)", 504);
          }
          return reply; // texto REAL del modelo — jamás inventado
        } catch (e) {
          if (e && e.code === "SMOKE_FAILED") throw e;
          if (ac.signal.aborted) {
            throw smokeError(`timeout de ${SMOKE_TIMEOUT_MS / 1000}s esperando la respuesta del modelo en ${sid}`, 504);
          }
          const raw = String((e && e.message) || e);
          lastErr = { message: raw, status: /timed out|timeout/i.test(raw) ? 504 : 502 };
          if (!isTransientConnError(raw)) break; // error de proveedor: sin retry
        }
      }
      if (ac.signal.aborted) {
        throw smokeError(`timeout de ${SMOKE_TIMEOUT_MS / 1000}s esperando la respuesta del modelo en ${sid}`, 504);
      }
      throw smokeError(
        `el proveedor no respondió: ${String(lastErr ? lastErr.message : "sin respuesta").slice(0, 300)}`,
        lastErr ? lastErr.status : 502
      );
    } finally {
      clearTimeout(timer);
    }
  }

  return function smoke() {
    if (!inflight) {
      inflight = run().finally(() => { inflight = null; });
    }
    return inflight;
  };
}

// ---------------------------------------------------------------------------
// Handler (contrato dispatchRoute de server.js)
// ---------------------------------------------------------------------------
export function createSetupHandler(deps = {}) {
  const probeOpenCode = typeof deps.probeOpenCode === "function"
    ? deps.probeOpenCode
    : (typeof deps.probeOpencodeHealth === "function" ? deps.probeOpencodeHealth : defaultProbeOpenCode);
  const smoke = makeSmokeRunner({ opencodeAdapter: deps.opencodeAdapter, probeOpenCode });

  return function handleSetupRoute(req, res, pathname, jsonHelper, readJsonBody) {
    if (typeof pathname !== "string" || !pathname.startsWith("/api/setup")) return false;

    // 1) GET /api/setup/final-check — los 4 checks en paralelo, cada uno <=2s
    if (pathname === "/api/setup/final-check" && req.method === "GET") {
      const checkDeps = { probeOpenCode };
      return Promise.all(CHECK_DEFS.map(def => runCheck(def, checkDeps)))
        .then(checks => {
          if (res.writableEnded) return true;
          const ready = checks.length > 0 && checks.every(c => c.status === "ok");
          return jsonHelper(res, 200, { ok: true, data: { ready, checks } });
        })
        .catch(e => {
          // NUNCA lanza: cualquier sorpresa sale como 500 envelope, nunca colgado.
          log.error("[setup] final-check error", { err: String((e && e.message) || e) });
          if (res.writableEnded) return true;
          return jsonHelper(res, 500, { ok: false, error: String((e && e.message) || e).slice(0, 300), code: "INTERNAL_ERROR" });
        });
    }

    // 2) POST /api/setup/smoke-test — ida y vuelta REAL con el modelo (60s)
    if (pathname === "/api/setup/smoke-test" && req.method === "POST") {
      return smoke().then(
        reply => {
          if (res.writableEnded) return true;
          return jsonHelper(res, 200, { ok: true, data: { ok: true, reply } });
        },
        err => {
          if (res.writableEnded) return true;
          const status = err && Number.isInteger(err.status) ? err.status : 502;
          log.warn("[setup] smoke-test fallido", { status, err: String((err && err.message) || err) });
          return jsonHelper(res, status, { ok: false, error: String((err && err.message) || err).slice(0, 700), code: "SMOKE_FAILED" });
        }
      );
    }

    // 3) POST /api/setup/auth/antigravity — SIEMPRE mode:"manual" (investigado en F3):
    //    `agy` actual NO expone subcomando "auth login" (verificado: `agy help auth`
    //    => unknown subcommand) y el flujo oficial es interactivo (keyring local con
    //    navegador, o URL de autorización manual vía SSH — antigravity.google/docs/cli/install).
    //    Spawnearlo desatendido colgaría esperando TTY/navegador => honestidad > aparentar.
    if (pathname === "/api/setup/auth/antigravity" && req.method === "POST") {
      const bin = findAgyBin();
      const auth = findAgyAuth();
      let status;
      let command;
      if (!bin) {
        status = "missing_cli";
        command = AGY_INSTALL_CMD;             // primero instalar el CLI
      } else if (!auth) {
        status = "missing_auth";
        command = bin;                          // y luego ejecutarlo para loguearse
      } else {
        status = "authenticated";
        command = bin;
      }
      // NUNCA el token: ni contenido, ni bytes, ni hash — sólo si existe (>0 B).
      return jsonHelper(res, 200, { ok: true, data: { mode: "manual", command, status } });
    }

    return false; // bajo /api/setup pero sin handler propio (=>404 central)
  };
}
