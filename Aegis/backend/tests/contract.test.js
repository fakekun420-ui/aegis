// contract.test.js — suite de contrato del hub (FASE A-6.5)
//
// Objetivo: que CI falle si cambia el shape que la app (Models.kt) parsea.
// No toca lógica del servidor: arranca server.js REAL en un puerto efímero y
// solo asserta el contrato externo (status + envelope + claves exactas).
//
// Contratos cubiertos:
//   GET  /api/health            -> 200 {ok,data:{10 claves exactas}} SIN token (A-1)
//   GET  /api/skills            -> 200 {ok,data:{installed,available}} con token
//                                  (F3: available = catálogo − instalados, H-14)
//   POST /api/skills/install    -> 400 ALLOWLIST si el id no está en el catálogo (F3)
//   GET  /api/workflows/:id     -> 200 {ok,data:[]} con token
//   GET  /api/jobs              -> 200 {ok,data:[...]} con token
//   GET  /api/noexiste          -> 404 {ok:false,error:{code:"NOT_FOUND"}}
//   barrido sin token           -> TODO 403 FORBIDDEN salvo GET /api/health

import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import fs from "node:fs";
import net from "node:net";

const BACKEND_DIR = dirname(dirname(fileURLToPath(import.meta.url))); // .../backend
const TOKEN_FILE = join(BACKEND_DIR, ".aegis_token");

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

/** GET/POST con/sin token (y body JSON opcional) contra el hub de prueba. */
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

before(async () => {
  port = await freePort();
  child = spawn(process.execPath, ["server.js"], {
    cwd: BACKEND_DIR,
    env: { ...process.env, HUB_PORT: String(port) },
    stdio: ["ignore", "pipe", "pipe"],
  });
  await waitForHub(child);
  // server.js crea/lee backend/.aegis_token al arrancar (mode 0600)
  token = fs.readFileSync(TOKEN_FILE, "utf8").trim();
  assert.ok(token.length >= 32, "token ausente o demasiado corto");
});

after(async () => {
  if (!child || child.exitCode !== null) return;
  child.kill("SIGTERM"); // server.js: SIGTERM -> server.close + exit
  await new Promise((r) => setTimeout(r, 1500));
  if (child.exitCode === null) child.kill("SIGKILL"); // fallback: nunca dejar huérfanos
});

test("GET /api/health sin token -> 200 con las 10 claves exactas de HealthData", async () => {
  const { status, body } = await api("/api/health", { withToken: false });
  assert.equal(status, 200);
  assert.equal(body.ok, true);
  const keys = Object.keys(body.data).sort();
  assert.deepEqual(
    keys,
    ["adapters", "agents", "jobs", "memory", "port", "projects", "server", "skills", "uptime", "workspace"],
    "shape de HealthData cambiado (Models.kt / app / keepalive.sh lo consumen)"
  );
  assert.equal(body.data.server, "running");
  assert.equal(body.data.port, port);
  assert.equal(typeof body.data.uptime, "number");
  assert.equal(typeof body.data.memory.heapUsed, "string");
  assert.equal(typeof body.data.memory.heapTotal, "string");
  assert.equal(body.data.workspace, "/sdcard/projects");
  assert.equal(typeof body.data.projects, "number");
  assert.deepEqual(Object.keys(body.data.agents).sort(), ["active", "registered"]);
  assert.deepEqual(Object.keys(body.data.jobs).sort(), ["active", "lastRun"]);
  assert.ok(Array.isArray(body.data.skills.installed), "skills.installed debe ser array");
  assert.equal(typeof body.data.adapters, "object");
  // Nunca secretos en el health público
  assert.ok(!JSON.stringify(body).includes(token), "el token no puede filtrarse en /api/health");
});

test("GET /api/skills sin query -> SkillsResponse {installed[], available[]}", async () => {
  const { status, body } = await api("/api/skills");
  assert.equal(status, 200);
  assert.equal(body.ok, true);
  assert.deepEqual(Object.keys(body.data).sort(), ["available", "installed"]);
  assert.ok(Array.isArray(body.data.installed));
  for (const skill of body.data.installed) {
    assert.deepEqual(
      Object.keys(skill).sort(),
      ["description", "enabled", "id", "installed", "name", "version"],
      "shape de SkillItem cambiado"
    );
    assert.equal(skill.installed, true);
    assert.equal(typeof skill.enabled, "boolean");
  }

  // F3 (H-14): available = catálogo (src/skills/catalog.json) − instalados,
  // calculado AQUÍ desde el catálogo real del repo (no se acepta [] a ciegas).
  const catalog = JSON.parse(fs.readFileSync(join(BACKEND_DIR, "src", "skills", "catalog.json"), "utf8"));
  assert.ok(Array.isArray(catalog) && catalog.length >= 1, "catálogo debe existir y no estar vacío");
  const installedIds = new Set(body.data.installed.map(s => s.id));
  const expectedAvailable = catalog.map(e => e.id).filter(id => !installedIds.has(id)).sort();
  assert.deepEqual(
    body.data.available.map(s => s.id).sort(),
    expectedAvailable,
    "available debe ser catálogo − instalados"
  );
  for (const skill of body.data.available) {
    assert.deepEqual(
      Object.keys(skill).sort(),
      ["description", "enabled", "id", "installed", "name", "version"],
      "shape de SkillItem cambiado en available"
    );
    assert.equal(skill.installed, false);
    assert.equal(typeof skill.enabled, "boolean");
    assert.ok(!installedIds.has(skill.id), `${skill.id} está instalado y no puede estar en available`);
  }
});

test("POST /api/skills/install con id fuera del catálogo -> 400 ALLOWLIST (H-14)", async () => {
  // Id que PASA la regex A-1 pero NO está en src/skills/catalog.json: el
  // rechazo debe ser del allowlist (nunca llega a spawn de npm).
  const { status, body } = await api("/api/skills/install", {
    method: "POST",
    body: { skillId: "no-esta-en-el-catalogo-f3" }
  });
  assert.equal(status, 400);
  assert.equal(body.ok, false);
  assert.equal(body.error.code, "ALLOWLIST", `code inesperado: ${body.error && body.error.code}`);
  assert.equal(typeof body.error.message, "string");
  assert.match(body.error.message, /catálogo|catalogo|allowlist/i);
});

test("GET /api/workflows/:projectId -> 200 lista ( [] si no hay workflows )", async () => {
  const { status, body } = await api("/api/workflows/contrato-test");
  assert.equal(status, 200);
  assert.equal(body.ok, true);
  assert.ok(Array.isArray(body.data), "data debe ser array de {id,name,steps}");
  for (const wf of body.data) {
    assert.deepEqual(Object.keys(wf).sort(), ["file", "format", "id", "name", "steps"]);
    assert.equal(typeof wf.steps, "number");
  }
});

test("GET /api/jobs -> 200 lista de JobItem", async () => {
  const { status, body } = await api("/api/jobs");
  assert.equal(status, 200);
  assert.equal(body.ok, true);
  assert.ok(Array.isArray(body.data));
  assert.ok(body.data.length >= 1, "el motor registra al menos companion-meta-sweep");
  for (const job of body.data) {
    assert.deepEqual(
      Object.keys(job).sort(),
      ["enabled", "id", "interval", "intervalMs", "lastRun"],
      "shape de JobItem cambiado"
    );
    assert.equal(typeof job.interval, "string", "JobItem.interval es String no nulo (Models.kt)");
    assert.equal(typeof job.intervalMs, "number");
    assert.equal(typeof job.enabled, "boolean");
  }
});

test("ruta API inexistente -> 404 JSON con error.code NOT_FOUND (nunca el fallback SPA)", async () => {
  const { status, body } = await api("/api/noexiste");
  assert.equal(status, 404);
  assert.equal(body.ok, false);
  assert.equal(body.error.code, "NOT_FOUND");
  assert.equal(body.error.path, "/api/noexiste");
  assert.equal(typeof body.error.message, "string");
});

test("barrido sin token: solo GET /api/health responde 200; el resto 403 FORBIDDEN", async () => {
  const rutas = [
    "/api/health",
    "/api/status",
    "/api/skills",
    "/api/jobs",
    "/api/workflows/contrato-test",
    "/api/system/health",
    "/api/noexiste",
  ];
  for (const ruta of rutas) {
    const { status, body } = await api(ruta, { withToken: false });
    if (ruta === "/api/health") {
      assert.equal(status, 200, "GET /api/health debe seguir exento de token (A-1)");
      assert.equal(body.ok, true);
    } else {
      assert.equal(status, 403, `${ruta} debe exigir X-Aegis-Token`);
      assert.equal(body.ok, false);
      assert.equal(body.error.code, "FORBIDDEN");
    }
  }
});

test("token inválido -> 403 FORBIDDEN (comparación timing-safe del hub)", async () => {
  const res = await fetch(`http://127.0.0.1:${port}/api/jobs`, {
    headers: { "X-Aegis-Token": "no-es-el-token" },
  });
  assert.equal(res.status, 403);
  const body = await res.json();
  assert.equal(body.ok, false);
  assert.equal(body.error.code, "FORBIDDEN");
});
