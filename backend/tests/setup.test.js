// setup.test.js — suite de contrato del cierre del wizard (FASE F3 · backend)
//
// Objetivo: que CI falle si cambia el shape de /api/setup/* que la UI del
// wizard (app/, construida en PARALELO contra este contrato) parsea. No toca la
// lógica del servidor: arranca server.js REAL en un puerto efímero y sólo
// asserta el contrato externo (status + envelope + claves/labels exactos).
//
// Entorno del hijo:
//   BOOTSTRAP_STATE_FILE=<temp>   estado TEMPORAL bajo /tmp/opencode: el check
//                                 "bootstrap" es determinista (phase=idle =>
//                                 status "fail") y el backend/bootstrap-state.json
//                                 REAL jamás se toca.
//
// Cobertura:
//   1. las 3 rutas SIN token -> 403 FORBIDDEN (el middleware Fase 0 cubre /api/setup/*)
//   2. GET  /api/setup/final-check       -> shape exacto: {ready, checks[4]} con
//                                           ids/labels literales, statuses del
//                                           enum y `ready` coherente (+ <5s)
//   3. POST /api/setup/auth/antigravity  -> {mode:"manual", command, status}
//                                           y NUNCA el token en la respuesta
//
// NO se testea aquí el smoke-test: haría una llamada REAL al modelo (hasta 60s)
// contra el OpenCode del entorno — se verifica manualmente (ver informe F3).

import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import fs from "node:fs";
import net from "node:net";

const BACKEND_DIR = dirname(dirname(fileURLToPath(import.meta.url))); // .../backend
const TOKEN_FILE = join(BACKEND_DIR, ".aegis_token");
const STATE_FILE = `/tmp/opencode/aegis-f3-setup-state-${process.pid}.json`;

function seedTokenIfMissing() {
  try {
    if (!fs.existsSync(TOKEN_FILE)) {
      fs.writeFileSync(TOKEN_FILE, crypto.randomBytes(32).toString("hex"), { mode: 0o600 });
    }
  } catch { /* si falla, server.js lo crea como hasta ahora */ }
}
seedTokenIfMissing();

// Labels literales del contrato F3 — deben casar AL PIE DE LA LETRA
const CHECK_CONTRACT = [
  { id: "opencode",    label: "OpenCode (proxy4096)" },
  { id: "antigravity", label: "Antigravity/Artemis (agy + auth)" },
  { id: "a11y",        label: "Servicio de accesibilidad (:8766)" },
  { id: "bootstrap",   label: "Instalación inicial (wizard)" }
];
const STATUSES = ["ok", "fail", "manual"];

let child = null;
let port = 0;
let token = "";

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.once("error", reject);
    srv.listen(0, "127.0.0.1", () => {
      const p = srv.address().port;
      srv.close(() => resolve(p));
    });
  });
}

async function api(pathname, { withToken = true, method = "GET", body } = {}) {
  const headers = {};
  if (withToken) headers["X-Aegis-Token"] = token;
  const init = { method, headers };
  if (body !== undefined) {
    headers["Content-Type"] = "application/json";
    init.body = JSON.stringify(body);
  }
  const res = await fetch(`http://127.0.0.1:${port}${pathname}`, init);
  let parsed = null;
  try { parsed = await res.json(); } catch (_) { parsed = null; }
  return { status: res.status, body: parsed };
}

async function waitForHub(proc, timeoutMs = 20000) {
  const deadline = Date.now() + timeoutMs;
  let stderr = "";
  proc.stderr.on("data", (c) => { stderr += c.toString(); });
  while (Date.now() < deadline) {
    if (proc.exitCode !== null) {
      throw new Error(`server.js salió antes de estar listo (code=${proc.exitCode})\n${stderr}`);
    }
    try {
      const r = await fetch(`http://127.0.0.1:${port}/api/health`, { signal: AbortSignal.timeout(1000) });
      if (r.status === 200) return;
    } catch (_) { /* aún no escucha */ }
    await new Promise((r) => setTimeout(r, 250));
  }
  throw new Error(`hub no respondió en ${timeoutMs}ms\n${stderr}`);
}

before(async () => {
  try { fs.mkdirSync("/tmp/opencode", { recursive: true }); } catch { /* ya existe */ }
  try { fs.rmSync(STATE_FILE, { force: true }); } catch (_) { /* inexistente */ }
  port = await freePort();
  child = spawn(process.execPath, ["server.js"], {
    cwd: BACKEND_DIR,
    env: { ...process.env, HUB_PORT: String(port), BOOTSTRAP_STATE_FILE: STATE_FILE },
    stdio: ["ignore", "pipe", "pipe"],
  });
  await waitForHub(child);
  token = fs.readFileSync(TOKEN_FILE, "utf8").trim();
  assert.ok(token.length >= 32, "token ausente o demasiado corto");
});

after(async () => {
  if (child && child.exitCode === null) {
    child.kill("SIGTERM"); // server.js: SIGTERM -> server.close + exit
    await new Promise((r) => setTimeout(r, 1500));
    if (child.exitCode === null) child.kill("SIGKILL"); // fallback: nunca dejar huérfanos
  }
  try { fs.rmSync(STATE_FILE, { force: true }); } catch (_) { /* limpieza final */ }
});

test("1. /api/setup/* sin token -> 403 FORBIDDEN (middleware Fase 0 cubre el prefijo)", async () => {
  const rutas = [
    ["/api/setup/final-check", "GET"],
    ["/api/setup/smoke-test", "POST"],
    ["/api/setup/auth/antigravity", "POST"],
  ];
  for (const [ruta, method] of rutas) {
    const { status, body } = await api(ruta, { withToken: false, method });
    assert.equal(status, 403, `${ruta} debe exigir X-Aegis-Token`);
    assert.equal(body.ok, false);
    assert.equal(body.error.code, "FORBIDDEN");
  }
});

test("2. GET /api/setup/final-check -> {ready, checks[4]} con labels exactos y ready coherente", async () => {
  const t0 = Date.now();
  const { status, body } = await api("/api/setup/final-check");
  const elapsed = Date.now() - t0;
  assert.equal(status, 200);
  assert.equal(body.ok, true);
  assert.deepEqual(Object.keys(body.data).sort(), ["checks", "ready"], "shape de data cambiado");
  assert.equal(typeof body.data.ready, "boolean");

  const checks = body.data.checks;
  assert.ok(Array.isArray(checks));
  assert.equal(checks.length, 4, "el contrato F3 define exactamente 4 checks");
  assert.deepEqual(
    checks.map(c => ({ id: c.id, label: c.label })),
    CHECK_CONTRACT,
    "ids/labels/orden de los checks cambiados (los parsea la UI del wizard)"
  );
  for (const c of checks) {
    assert.deepEqual(Object.keys(c).sort(), ["detail", "id", "label", "status"], `shape de check "${c.id}" cambiado`);
    assert.ok(STATUSES.includes(c.status), `status inválido en "${c.id}": ${c.status}`);
    assert.equal(typeof c.detail, "string");
    assert.ok(c.detail.length > 0, `detail vacío en "${c.id}" — debe explicar el porqué`);
  }

  // ready = todos ok (coherencia literal del contrato)
  assert.equal(body.data.ready, checks.every(c => c.status === "ok"), "ready debe ser checks.every(ok)");

  // con estado TEMPORAL recién creado (phase=idle) el check "bootstrap" es fail
  const bootstrap = checks.find(c => c.id === "bootstrap");
  assert.equal(bootstrap.status, "fail");
  assert.match(bootstrap.detail, /phase=idle/, "el check bootstrap debe reflejar la phase real");

  // cada check <=2s y en paralelo: el endpoint entero tiene que ser rápido
  assert.ok(elapsed < 5000, `final-check tardó ${elapsed}ms (esperado <5000ms: 4 checks en paralelo de <=2s)`);
});

test("3. POST /api/setup/auth/antigravity -> mode manual + command + status (sin token)", async () => {
  const { status, body } = await api("/api/setup/auth/antigravity", { method: "POST" });
  assert.equal(status, 200);
  assert.equal(body.ok, true);
  assert.deepEqual(Object.keys(body.data).sort(), ["command", "mode", "status"]);
  assert.equal(body.data.mode, "manual", "el login de agy es interactivo (navegador/TTY) => nunca spawned");
  assert.equal(typeof body.data.command, "string");
  assert.ok(body.data.command.length > 0, "command debe traer el comando exacto a ejecutar");
  assert.ok(
    ["authenticated", "missing_auth", "missing_cli"].includes(body.data.status),
    `status inválido: ${body.data.status}`
  );
  // Nunca secretos: ni el token del hub ni contenido de credenciales
  assert.ok(!JSON.stringify(body).includes(token), "el token del hub no puede aparecer en la respuesta");
});
