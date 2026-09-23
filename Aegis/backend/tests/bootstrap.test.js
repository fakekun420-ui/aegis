// bootstrap.test.js — suite de contrato del wizard de bootstrap (FASE F1)
//
// Objetivo: que CI falle si cambia el shape de /api/bootstrap/* que la UI del
// wizard (app/, en construcción en PARALELO) parsea. No toca la lógica del
// servidor: arranca server.js REAL en un puerto efímero y sólo asserta el
// contrato externo (status + envelope + claves/orden exactos).
//
// Entorno del hijo:
//   AEGIS_BOOTSTRAP_DRY=1             run() NO muta ni spawnean => los pasos no
//                                     satisfechos terminan "done" con detail "[DRY]"
//   AEGIS_BOOTSTRAP_STEP_DELAY_MS=150 ventana determinista para assertar el409
//                                     ALREADY_RUNNING y el progreso en vivo
//   BOOTSTRAP_STATE_FILE=<temp>       estado TEMPORAL bajo /tmp/opencode: el
//                                     backend/bootstrap-state.json REAL jamás se
//                                     borra ni se toca (independiente de la env
//                                     del resto de procesos)
//
// Cobertura:
//   1. GET  /api/bootstrap/state     -> 403 sin token; shape exacto con token
//                                       (6 pasos, orden y títulos literales)
//   2. POST /api/bootstrap/run       -> 202 {phase:"running"}; 2º run -> 409 ALREADY_RUNNING
//   3. poll hasta salir de running   -> en DRY: phase "done" y todos done|skipped
//   4. POST /api/bootstrap/step/:id/retry -> 409 NOT_RETRYABLE (done) + 404 NOT_FOUND
//   5. POST /api/bootstrap/cancel    -> 409 NOT_RUNNING (no corría)
//   6. idempotencia                  -> 2ª ejecución: satisfechos = skipped con detail

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
// Estado TEMPORAL de este test (fuera del repo) — se borra al empezar y al terminar
const STATE_FILE = `/tmp/opencode/aegis-bootstrap-state-test-${process.pid}.json`;

// Semilla del token ANTES de spawn (ver comentario en before)
function seedTokenIfMissing() {
  try {
    if (!fs.existsSync(TOKEN_FILE)) {
      fs.writeFileSync(TOKEN_FILE, crypto.randomBytes(32).toString("hex"), { mode: 0o600 });
    }
  } catch { /* si falla, server.js lo crea como hasta ahora */ }
}
seedTokenIfMissing();

// Contrato literal de steps (orden + títulos) — debe casar AL PIE DE LA LETRA
const STEP_CONTRACT = [
  { id: "preflight",   title: "Comprobación previa" },
  { id: "ubuntu",      title: "Ubuntu (chroot/proot)" },
  { id: "node",        title: "Node.js" },
  { id: "opencode",    title: "OpenCode" },
  { id: "antigravity", title: "Antigravity / Artemis" },
  { id: "skills",      title: "Skills y plugins" }
];
const STEP_IDS = STEP_CONTRACT.map(s => s.id);

let child = null;
let port = 0;
let token = "";

/** Puerto TCP libre en 127.0.0.1 (evita pisar un hub ya corriendo en 8765). */
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

/** Petición con/sin token y body JSON opcional contra el hub de prueba. */
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

/** Espera a que el hub acepte conexiones (o falla con el stderr del hijo). */
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

/** Poll de GET /api/bootstrap/state hasta que phase != "running" (o timeout). */
async function waitSettled(timeoutMs = 15000) {
  const deadline = Date.now() + timeoutMs;
  let last = null;
  while (Date.now() < deadline) {
    const { status, body } = await api("/api/bootstrap/state");
    if (status === 200 && body && body.ok && body.data) {
      last = body.data;
      if (body.data.phase !== "running") return body.data;
    }
    await new Promise((r) => setTimeout(r, 120));
  }
  throw new Error(
    `la ejecución no salió de "running" en ${timeoutMs}ms — último estado: ` +
    JSON.stringify(last ? { phase: last.phase, steps: last.steps.map(s => `${s.id}:${s.status}`) } : null)
  );
}

before(async () => {
  // Estado temporal LIMPIO: el seeder "idle" del contrato, sin pisar el real
  try { fs.rmSync(STATE_FILE, { force: true }); } catch (_) { /* inexistente */ }
  port = await freePort();
  child = spawn(process.execPath, ["server.js"], {
    cwd: BACKEND_DIR,
    env: {
      ...process.env,
      HUB_PORT: String(port),
      AEGIS_BOOTSTRAP_DRY: "1",
      AEGIS_BOOTSTRAP_STEP_DELAY_MS: "150",
      BOOTSTRAP_STATE_FILE: STATE_FILE
    },
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
    if (child.exitCode === null) child.kill("SIGKILL"); // fallback: nunca huérfanos
  }
  try { fs.rmSync(STATE_FILE, { force: true }); } catch (_) { /* limpieza final */ }
});

test("1. GET /api/bootstrap/state: 403 sin token y shape EXACTO con token", async () => {
  // sin token => el middleware de Fase 0 la bloquea (ruta no exenta)
  const noToken = await api("/api/bootstrap/state", { withToken: false });
  assert.equal(noToken.status, 403);
  assert.equal(noToken.body.ok, false);
  assert.equal(noToken.body.error.code, "FORBIDDEN");

  // con token =>200 y cabecera exacta del contrato
  const { status, body } = await api("/api/bootstrap/state");
  assert.equal(status, 200);
  assert.equal(body.ok, true);
  assert.deepEqual(
    Object.keys(body.data).sort(),
    ["currentStepId", "lastError", "phase", "startedAt", "steps", "updatedAt"],
    "shape de cabecera cambiado (contrato /api/bootstrap/state)"
  );
  assert.equal(body.data.phase, "idle");
  assert.equal(body.data.currentStepId, null);
  assert.equal(body.data.startedAt, null);
  assert.equal(body.data.lastError, null);
  assert.equal(typeof body.data.updatedAt, "string");
  assert.ok(!Number.isNaN(Date.parse(body.data.updatedAt)), "updatedAt debe ser ISO parseable");

  // 6 pasos, EN ESTE ORDEN, con estos títulos LITERALES
  assert.ok(Array.isArray(body.data.steps));
  assert.equal(body.data.steps.length, 6);
  assert.deepEqual(
    body.data.steps.map(s => ({ id: s.id, title: s.title })),
    STEP_CONTRACT,
    "orden/títulos de steps cambiados (los parsea la UI del wizard)"
  );
  for (const s of body.data.steps) {
    assert.deepEqual(
      Object.keys(s).sort(),
      ["detail", "error", "id", "progress", "rollback", "status", "title"],
      `shape de step "${s.id}" cambiado`
    );
    assert.equal(s.status, "pending");
    assert.equal(s.rollback, "none");
    assert.equal(s.progress, 0);
    assert.equal(typeof s.detail, "string");
    assert.equal(s.error, null);
  }
});

test("2. POST /api/bootstrap/run -> 202 running; segundo run -> 409 ALREADY_RUNNING", async () => {
  const r1 = await api("/api/bootstrap/run", { method: "POST", body: { resume: true } });
  assert.equal(r1.status, 202);
  assert.deepEqual(r1.body, { ok: true, data: { phase: "running" } });

  // progreso EN VIVO: el state ya refleja la ejecución (currentStepId = paso
  // "running" o null entre pasos)
  const live = await api("/api/bootstrap/state");
  assert.equal(live.status, 200);
  assert.equal(live.body.data.phase, "running");
  assert.ok(
    live.body.data.currentStepId === null || STEP_IDS.includes(live.body.data.currentStepId),
    `currentStepId inesperado: ${live.body.data.currentStepId}`
  );

  // mientras corre => 409 con code ALREADY_RUNNING (env {ok:false,error:{code,message}})
  const r2 = await api("/api/bootstrap/run", { method: "POST", body: { resume: true } });
  assert.equal(r2.status, 409);
  assert.equal(r2.body.ok, false);
  assert.equal(r2.body.error.code, "ALREADY_RUNNING");
  assert.equal(typeof r2.body.error.message, "string");
});

test("3. la ejecución termina en DRY: phase done y todos los steps done|skipped", async () => {
  const data = await waitSettled(15000);
  assert.equal(data.phase, "done", `phase final inesperada: ${data.phase} (lastError=${data.lastError})`);
  assert.equal(data.lastError, null);
  assert.equal(data.currentStepId, null);
  assert.equal(typeof data.startedAt, "string");
  assert.deepEqual(data.steps.map(s => s.id), STEP_IDS, "el orden de steps debe permanecer");
  for (const s of data.steps) {
    assert.ok(
      ["done", "skipped"].includes(s.status),
      `el paso "${s.id}" terminó en "${s.status}" (error=${s.error}) — en DRY todos deben cerrar`
    );
    assert.equal(typeof s.detail, "string");
    assert.equal(s.error, null);
    if (s.status === "done") assert.equal(s.progress, 100);
  }
});

test("4. retry: paso no failed -> 409 NOT_RETRYABLE; id desconocido -> 404 NOT_FOUND", async () => {
  // preflight quedó done/skipped en la ejecución anterior => no reintentable
  const r = await api("/api/bootstrap/step/preflight/retry", { method: "POST" });
  assert.equal(r.status, 409);
  assert.equal(r.body.ok, false);
  assert.equal(r.body.error.code, "NOT_RETRYABLE");
  assert.equal(typeof r.body.error.message, "string");

  const r404 = await api("/api/bootstrap/step/loquesea/retry", { method: "POST" });
  assert.equal(r404.status, 404);
  assert.equal(r404.body.ok, false);
  assert.equal(r404.body.error.code, "NOT_FOUND");
});

test("5. POST /api/bootstrap/cancel sin ejecución en curso -> 409 NOT_RUNNING", async () => {
  const r = await api("/api/bootstrap/cancel", { method: "POST" });
  assert.equal(r.status, 409);
  assert.equal(r.body.ok, false);
  assert.equal(r.body.error.code, "NOT_RUNNING");
  // el estado NO se corrompe: sigue cerrado y sin "running"
  const st = await api("/api/bootstrap/state");
  assert.ok(["done", "failed", "paused", "idle"].includes(st.body.data.phase));
  assert.equal(st.body.data.currentStepId, null);
});

test("6. idempotencia: la segunda ejecución marca los satisfechos como skipped con detail", async () => {
  const r1 = await api("/api/bootstrap/run", { method: "POST", body: { resume: true } });
  assert.equal(r1.status, 202, "una ejecución ya cerrada debe poder relanzarse");

  const data = await waitSettled(15000);
  assert.equal(data.phase, "done", `phase final inesperada: ${data.phase} (lastError=${data.lastError})`);
  assert.equal(data.lastError, null);
  assert.equal(data.currentStepId, null);
  assert.deepEqual(data.steps.map(s => s.id), STEP_IDS);

  let skipped = 0;
  for (const s of data.steps) {
    assert.ok(
      ["done", "skipped"].includes(s.status),
      `la re-ejecución dejó "${s.id}" en "${s.status}" (error=${s.error})`
    );
    if (s.status === "skipped") {
      skipped++;
      assert.equal(typeof s.detail, "string");
      assert.ok(s.detail.length > 0, `el paso skipped "${s.id}" debe explicar POR QUÉ (detail vacío)`);
    }
  }
  assert.ok(skipped >= 1, "la re-ejecución debe marcar como skipped al menos un paso ya satisfecho");
});
