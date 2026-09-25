// bootstrap-rollback.test.js — FASE F2 · motor de instalación + rollback por paso
//
// Complementa a bootstrap.test.js (contrato de F1) probando el CICLO DE FALLO:
// inyección de error → rollback → retry/resume, SIN consumir red de verdad ni
// instalar paquetes (todo en DRY=1 + hooks de test documentados en steps.js).
//
// Usa hubs PROPIOS (env y estado temporal independientes por suite):
//   A) AEGIS_BOOTSTRAP_DRY=1 + AEGIS_BOOTSTRAP_FAIL=ubuntu
//      → run: phase "failed", ubuntu failed con error FAIL_INJECTED y rollback
//        en {none,done,failed}; preflight ya satisfecho (skipped); los pasos
//        posteriores (node/opencode/antigravity/skills) quedan "pending".
//   B) mismo fichero de estado SIN la inyección
//      → POST /step/ubuntu/retry → 202 y la ejecución CONTINÚA hasta phase
//        "done"; node (siempre satisfacible: process.execPath) sale "skipped"
//        => el resume re-evaluó check() (idempotencia); un 2º run {resume:true}
//        vuelve a "done".
//   C) AEGIS_BOOTSTRAP_TEST_STEP=faketest (paso fake AL FINAL, state.js)
//      → su run crea AEGIS_BOOTSTRAP_TEST_ARTIFACT (fichero real en disco) y
//        lanza FAIL_INJECTED; el rollback REAL debe borrar ese fichero
//        (rollback === "done") y phase queda "failed".
//
// Los hubs de A/B setean AEGIS_BOOTSTRAP_TEST_STEP="" para neutralizar la env
// por si acaso; la suite C es la única que registra el paso fake (los hubs de
// los tests de contrato de 6 pasos NUNCA la ven).

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
const TMP_DIR = "/tmp/opencode";

// Estados TEMPORALES de este test (fuera del repo) — jamás tocan
// backend/bootstrap-state.json real.
const STATE_A = `${TMP_DIR}/aegis-f2-state-a-${process.pid}.json`; // A y B lo comparten
const STATE_C = `${TMP_DIR}/aegis-f2-state-c-${process.pid}.json`;
const FAKE_ARTIFACT = `${TMP_DIR}/aegis-f2-artifact-${process.pid}`;

const REAL_STEP_IDS = ["preflight", "ubuntu", "node", "opencode", "antigravity", "skills"];

function seedTokenIfMissing() {
  try {
    if (!fs.existsSync(TOKEN_FILE)) {
      fs.writeFileSync(TOKEN_FILE, crypto.randomBytes(32).toString("hex"), { mode: 0o600 });
    }
  } catch { /* si falla, server.js lo crea como hasta ahora */ }
}
seedTokenIfMissing();

const children = [];

/** Puerto TCP libre en 127.0.0.1 (evita pisar hubs ya corriendos). */
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

/** Arranca server.js REAL con el env de la suite. */
async function startHub(env) {
  const port = await freePort();
  const child = spawn(process.execPath, ["server.js"], {
    cwd: BACKEND_DIR,
    env: {
      ...process.env,
      HUB_PORT: String(port),
      AEGIS_BOOTSTRAP_STEP_DELAY_MS: "80",
      ...env
    },
    stdio: ["ignore", "pipe", "pipe"],
  });
  children.push({ child, port });
  // Espera de arranque (misma técnica que bootstrap.test.js)
  const deadline = Date.now() + 20000;
  let stderr = "";
  child.stderr.on("data", c => { stderr += c.toString(); });
  while (Date.now() < deadline) {
    if (child.exitCode !== null) {
      throw new Error(`server.js salió antes de estar listo (code=${child.exitCode})\n${stderr}`);
    }
    try {
      const r = await fetch(`http://127.0.0.1:${port}/api/health`, { signal: AbortSignal.timeout(1000) });
      if (r.status === 200) return { child, port };
    } catch { /* aún no escucha */ }
    await new Promise(r => setTimeout(r, 250));
  }
  throw new Error(`hub no respondió en 20000ms (puerto ${port})\n${stderr}`);
}

async function stopHub(entry) {
  if (!entry) return;
  const { child } = entry;
  if (child.exitCode !== null) return;
  child.kill("SIGTERM");
  await new Promise(r => setTimeout(r, 1200));
  if (child.exitCode === null) child.kill("SIGKILL");
  await new Promise(r => setTimeout(r, 250));
}

/** Petición con token contra un hub concreto. */
async function api(port, pathname, { method = "GET", body } = {}) {
  const token = fs.readFileSync(TOKEN_FILE, "utf8").trim();
  const headers = { "X-Aegis-Token": token };
  const init = { method, headers };
  if (body !== undefined) {
    headers["Content-Type"] = "application/json";
    init.body = JSON.stringify(body);
  }
  const res = await fetch(`http://127.0.0.1:${port}${pathname}`, init);
  let parsed = null;
  try { parsed = await res.json(); } catch { parsed = null; }
  return { status: res.status, body: parsed };
}

/** Poll hasta que phase != "running". */
async function waitSettled(port, timeoutMs = 20000) {
  const deadline = Date.now() + timeoutMs;
  let last = null;
  while (Date.now() < deadline) {
    const { status, body } = await api(port, "/api/bootstrap/state");
    if (status === 200 && body && body.ok && body.data) {
      last = body.data;
      if (body.data.phase !== "running") return body.data;
    }
    await new Promise(r => setTimeout(r, 100));
  }
  throw new Error(
    `no salió de "running" en ${timeoutMs}ms — último: ` +
    JSON.stringify(last ? { phase: last.phase, steps: last.steps.map(s => `${s.id}:${s.status}`) } : null)
  );
}

before(() => {
  try { fs.mkdirSync(TMP_DIR, { recursive: true }); } catch { /* ya existe */ }
  for (const f of [STATE_A, STATE_C, FAKE_ARTIFACT]) {
    try { fs.rmSync(f, { force: true }); } catch { /* inexistente */ }
  }
});

after(async () => {
  for (const entry of children) {
    try { await stopHub(entry); } catch { /* ya muerto */ }
  }
  for (const f of [STATE_A, STATE_C, FAKE_ARTIFACT]) {
    try { fs.rmSync(f, { force: true }); } catch { /* limpieza final */ }
  }
});

// ---------------------------------------------------------------------------
// Suite A — fallo inyectado en ubuntu (DRY): failed reanudable + rollback válido
// ---------------------------------------------------------------------------
let hubA = null;

test("A. DRY + AEGIS_BOOTSTRAP_FAIL=ubuntu → phase failed, ubuntu failed (FAIL_INJECTED), posteriores pending", async () => {
  hubA = await startHub({
    AEGIS_BOOTSTRAP_DRY: "1",
    AEGIS_BOOTSTRAP_FAIL: "ubuntu",
    AEGIS_BOOTSTRAP_TEST_STEP: "",        // neutraliza la env por si acaso
    AEGIS_BOOTSTRAP_TEST_ARTIFACT: "",
    BOOTSTRAP_STATE_FILE: STATE_A
  });

  const r = await api(hubA.port, "/api/bootstrap/run", { method: "POST", body: { resume: true } });
  assert.equal(r.status, 202, "el run inicial debe responder 202");
  assert.deepEqual(r.body, { ok: true, data: { phase: "running" } });

  const data = await waitSettled(hubA.port);
  assert.equal(data.phase, "failed", `phase final: ${data.phase} (lastError=${data.lastError})`);
  assert.match(String(data.lastError), /^ubuntu: FAIL_INJECTED/);

  const byId = Object.fromEntries(data.steps.map(s => [s.id, s]));

  // paso ubuntu: failed + error + rollback en un estado válido del contrato
  assert.equal(byId.ubuntu.status, "failed");
  assert.match(String(byId.ubuntu.error), /FAIL_INJECTED/);
  assert.ok(
    ["none", "done", "failed"].includes(byId.ubuntu.rollback),
    `rollback de ubuntu inválido: ${byId.ubuntu.rollback}`
  );

  // preflight se satisfizo antes (check real) → skipped con detail
  assert.ok(
    ["skipped", "done"].includes(byId.preflight.status),
    `preflight debió cerrar (status=${byId.preflight.status})`
  );

  // pasos posteriores NUNCA alcanzados → pending
  for (const id of ["node", "opencode", "antigravity", "skills"]) {
    assert.equal(byId[id].status, "pending", `el paso "${id}" debió quedar pending`);
    assert.equal(byId[id].rollback, "none");
  }
  assert.equal(data.currentStepId, null, "sin paso en curso tras el fallo");
});

// ---------------------------------------------------------------------------
// Suite B — retry SIN la inyección: continúa hasta done + resume re-evalúa check
// ---------------------------------------------------------------------------
let hubB = null;

test("B. retry del paso fallido sin inyección → 202 y la ejecución CONTINÚA hasta done", async () => {
  await stopHub(hubA); // el nuevo hub relee el MISMO estado (phase failed)
  hubB = await startHub({
    AEGIS_BOOTSTRAP_DRY: "1",
    AEGIS_BOOTSTRAP_FAIL: "",             // SIN inyección
    AEGIS_BOOTSTRAP_TEST_STEP: "",
    AEGIS_BOOTSTRAP_TEST_ARTIFACT: "",
    BOOTSTRAP_STATE_FILE: STATE_A
  });

  // el estado persistido llega como "failed" y ubuntu sigue reintentable
  const pre = await api(hubB.port, "/api/bootstrap/state");
  assert.equal(pre.body.data.phase, "failed");
  assert.equal(pre.body.data.steps.find(s => s.id === "ubuntu").status, "failed");

  const r = await api(hubB.port, "/api/bootstrap/step/ubuntu/retry", { method: "POST" });
  assert.equal(r.status, 202, "retry de un paso failed debe responder 202");
  assert.deepEqual(r.body, { ok: true, data: { phase: "running" } });

  const data = await waitSettled(hubB.port);
  assert.equal(data.phase, "done", `phase final: ${data.phase} (lastError=${data.lastError})`);
  assert.equal(data.lastError, null);
  assert.equal(data.currentStepId, null);

  const byId = Object.fromEntries(data.steps.map(s => [s.id, s]));
  for (const id of REAL_STEP_IDS) {
    assert.ok(
      ["done", "skipped"].includes(byId[id].status),
      `el paso "${id}" terminó en "${byId[id].status}" (error=${byId[id].error}) — la ejecución debe CONTINUAR hasta done`
    );
    assert.equal(byId[id].error, null, `el paso "${id}" no debe conservar error tras el retry`);
  }
  // ya no está failed y su rollback se reinició en el nuevo intento
  assert.notEqual(byId.ubuntu.status, "failed");
  assert.equal(byId.ubuntu.rollback, "none");

  // test 3 (resume re-evaluó check()): node SIEMPRE está satisfecho
  // (process.execPath = el node que ejecuta el hub) → skipped con detail real
  assert.equal(byId.node.status, "skipped", "resume debe re-evaluar check() y skipear lo ya satisfecho");
  assert.match(byId.node.detail, /Node\s+v\d+/, `detail de node inesperado: ${byId.node.detail}`);

  // un run {resume:true} posterior es idempotente → done de nuevo
  const r2 = await api(hubB.port, "/api/bootstrap/run", { method: "POST", body: { resume: true } });
  assert.equal(r2.status, 202);
  const data2 = await waitSettled(hubB.port);
  assert.equal(data2.phase, "done");
  assert.ok(data2.steps.every(s => ["done", "skipped"].includes(s.status)));
  assert.equal(data2.steps.find(s => s.id === "node").status, "skipped");
});

// ---------------------------------------------------------------------------
// Suite C — rollback REAL: paso fake crea fichero y lanza → el fichero desaparece
// ---------------------------------------------------------------------------
test("C. rollback real: el paso fake crea un fichero y lanza → tras el fallo YA NO existe y rollback=done", async () => {
  await stopHub(hubB);
  assert.ok(!fs.existsSync(FAKE_ARTIFACT), "el artifact no debe existir antes de la prueba");

  const hubC = await startHub({
    AEGIS_BOOTSTRAP_DRY: "1",
    AEGIS_BOOTSTRAP_FAIL: "",
    AEGIS_BOOTSTRAP_TEST_STEP: "faketest",                    // registra el paso fake (state.js)
    AEGIS_BOOTSTRAP_TEST_ARTIFACT: FAKE_ARTIFACT,             // fichero que su run creará
    BOOTSTRAP_STATE_FILE: STATE_C
  });

  // el contrato del hub de prueba incluye el paso fake AL FINAL
  const pre = await api(hubC.port, "/api/bootstrap/state");
  assert.deepEqual(
    pre.body.data.steps.map(s => s.id),
    [...REAL_STEP_IDS, "faketest"],
    "el paso fake debe añadirse al FINAL de STEP_DEFS"
  );

  const r = await api(hubC.port, "/api/bootstrap/run", { method: "POST", body: { resume: true } });
  assert.equal(r.status, 202);

  const data = await waitSettled(hubC.port);
  assert.equal(data.phase, "failed", `phase final: ${data.phase} (lastError=${data.lastError})`);

  const byId = Object.fromEntries(data.steps.map(s => [s.id, s]));

  // los 6 pasos reales cerraron (dry) antes de llegar al fake
  for (const id of REAL_STEP_IDS) {
    assert.ok(
      ["done", "skipped"].includes(byId[id].status),
      `paso real "${id}" en ${byId[id].status} — deben cerrar en dry antes del paso fake`
    );
  }

  // el paso fake falló y su rollback CORRIÓ: el fichero ya no existe
  assert.equal(byId.faketest.status, "failed");
  assert.match(String(byId.faketest.error), /FAIL_INJECTED/);
  assert.equal(byId.faketest.rollback, "done", "el rollback debe declararse done (acción ejecutada sin errores)");
  assert.ok(
    !fs.existsSync(FAKE_ARTIFACT),
    "el rollback debió borrar el fichero creado por el run del paso fake"
  );
  assert.match(String(data.lastError), /^faketest: FAIL_INJECTED/);
});
