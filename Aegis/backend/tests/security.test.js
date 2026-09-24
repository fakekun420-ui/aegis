// security.test.js — FASE F4 · validación anti-traversal de ids + auth (backend)
//
// Objetivo: que CI falle si alguien quita o debilita la validación de ids que F4
// puso ANTES de tocar store/fs/path.join (server.js y los routers de projects y
// skills). No toca lógica del servidor: arranca server.js REAL en puerto efímero
// y sólo asserta respuestas externas (status + envelope + error.code exacto).
//
// Método (importante):
//   fetch()/new URL() NORMALIZAN los ".." literales del path (un segmento
//   "%2e%2e" solo colapsa a ".." y la URL se acorta), así que los ataques viajan
//   codificados y con barra codificada (%2F): el segmento así NO es un dot-segment
//   y llega íntegro al hub, que hace decodeURIComponent y ahí cae la validación.
//   Todas las peticiones llevan token: el middleware de auth va ANTES de los
//   routers, sin token la respuesta sería 403 (se testea aparte).
//
// Hallazgos documentados en el informe F4 (NO "arreglados" aquí, fuera de alcance):
//   * GET /api/projects/:id/summary usa un regex SIN grupo de captura (server.js
//     ~L2470) => m[1] es undefined => id "undefined" => 404 SIEMPRE, también para
//     ids válidos. El test lo fija como 404: el traversal NO llega al filesystem
//     (queda el test 6 abajo).

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
  try {
    if (!fs.existsSync(TOKEN_FILE)) {
      fs.writeFileSync(TOKEN_FILE, (await import("node:crypto")).randomBytes(32).toString("hex"), { mode: 0o600 });
    }
  } catch { /* si falla, server.js lo crea como hasta ahora */ }
  port = await freePort();
  child = spawn(process.execPath, ["server.js"], {
    cwd: BACKEND_DIR,
    env: { ...process.env, HUB_PORT: String(port) },
    stdio: ["ignore", "pipe", "pipe"],
  });
  await waitForHub(child);
  token = fs.readFileSync(TOKEN_FILE, "utf8").trim();
  assert.ok(token.length >= 32, "token ausente o demasiado corto");
});

after(async () => {
  if (!child || child.exitCode !== null) return;
  child.kill("SIGTERM");
  await new Promise((r) => setTimeout(r, 1500));
  if (child.exitCode === null) child.kill("SIGKILL");
});

/** Asserta el envelope de error F4: 400 + code exacto + message legible. */
function assertInvalid(status, body, code, label) {
  assert.equal(status, 400, `${label} -> esperaba 400, vino ${status} (${JSON.stringify(body).slice(0, 200)})`);
  assert.equal(body.ok, false, `${label}: ok debe ser false`);
  assert.equal(body.error && body.error.code, code, `${label}: error.code debería ser ${code}`);
  assert.equal(typeof body.error.message, "string", `${label}: error.message debe ser string (§7.1)`);
  assert.ok(body.error.message.length > 0, `${label}: error.message vacío`);
}

test("1. traversal de projectId -> 400 PROJECT_INVALID (antes de tocar store/fs)", async () => {
  // %2F mantiene el segmento entero en la URL y, tras decodeURIComponent, el id
  // contiene "/" y ".." -> regex del contrato lo rechaza.
  const casos = [
    ["/api/projects/..%2F..%2Fetc%2Fpasswd", "DELETE", undefined],
    ["/api/projects/..%2F..%2Fetc%2Fpasswd", "PATCH", {}],
    ["/api/projects/..%2F..%2Fetc%2Fpasswd/path", "GET", undefined],
    ["/api/workspace/projects/..%2Fx/init", "POST", undefined],
    ["/api/workspace/projects/..%2Fx/state", "GET", undefined],
    // GET /api/projects/:id no existe (sólo PATCH/DELETE/…/path/…): se usan las
    // rutas SÍ registradas para que el 400 venga de la validación, no del 404.
    ["/api/projects/foo%00bar/path", "GET", undefined], // null byte
    ["/api/projects/a%3Bb/path", "GET", undefined],     // metacaracter de shell
    ["/api/projects/a%20b/path", "GET", undefined],     // espacio codificado
  ];
  for (const [ruta, method, body] of casos) {
    const { status, body: b } = await api(ruta, { method, ...(body !== undefined ? { body } : {}) });
    assertInvalid(status, b, "PROJECT_INVALID", `${method} ${ruta}`);
    // Nunca 2xx: el id malicioso no puede resolver a un recurso real
    assert.notEqual(status, 200, `${method} ${ruta}: traversal no puede devolver 200`);
  }
});

test("2. traversal de sessionId -> 400 SESSION_INVALID (en todas las rutas de sesión)", async () => {
  const casos = [
    ["/api/sessions/..%2F..%2Fetc%2Fpasswd", "DELETE", undefined],
    ["/api/opencode/sessions/..%2F..%2Fetc%2Fpasswd", "DELETE", undefined],
    ["/api/sessions/..%2F..%2Fetc%2Fpasswd/message", "POST", {}],
    ["/opencode/session/..%2F..%2Fetc%2Fpasswd/message", "POST", {}],
  ];
  for (const [ruta, method, body] of casos) {
    const { status, body: b } = await api(ruta, { method, ...(body !== undefined ? { body } : {}) });
    assertInvalid(status, b, "SESSION_INVALID", `${method} ${ruta}`);
  }

  // Doble id (proyecto + sesión): si el PROYECTO es el malo manda PROJECT_INVALID
  const p = await api("/api/projects/..%2Fx/sessions/f4-sesion", { method: "PATCH", body: {} });
  assertInvalid(p.status, p.body, "PROJECT_INVALID", "PATCH proyecto malo + sesión buena");

  // ...y si el proyecto es bueno, el malo es la sesión
  const s = await api("/api/projects/f4-proyecto/sessions/..%2Fx", { method: "PATCH", body: {} });
  assertInvalid(s.status, s.body, "SESSION_INVALID", "PATCH proyecto bueno + sesión mala");
});

test("3. traversal de skillId / query -> 400 SKILL_INVALID (o PROJECT_INVALID en projectId)", async () => {
  const del = await api("/api/skills/..%2Fgraphify", { method: "DELETE" });
  assertInvalid(del.status, del.body, "SKILL_INVALID", "DELETE /api/skills/..%2Fgraphify");

  const conf = await api("/api/skills/..%2Fgraphify/config");
  assertInvalid(conf.status, conf.body, "SKILL_INVALID", "GET /api/skills/..%2Fgraphify/config");

  // Forma del id ANTES del allowlist: un id malicioso NO puede llegar a npm
  const inst = await api("/api/skills/install", { method: "POST", body: { skillId: "../../etc/passwd" } });
  assertInvalid(inst.status, inst.body, "SKILL_INVALID", "POST /api/skills/install skillId traversal");
  assert.notEqual(inst.body.error.code, "ALLOWLIST", "un id con traversal debe fallar por FORMA, no por catálogo");

  const scope = await api("/api/skills?scope=..%2F..%2Fx");
  assertInvalid(scope.status, scope.body, "SKILL_INVALID", "GET /api/skills?scope=traversal");

  const proj = await api("/api/skills?projectId=..%2F..%2Fx");
  assertInvalid(proj.status, proj.body, "PROJECT_INVALID", "GET /api/skills?projectId=traversal");
});

test("4. límite de longitud: id de 300 chars -> 400 (max 256 del contrato F4)", async () => {
  const { status, body } = await api(`/api/projects/${"a".repeat(300)}/path`);
  assertInvalid(status, body, "PROJECT_INVALID", "projectId de 300 caracteres");
  assert.match(body.error.message, /256/, "el message debe citar el límite violado");
});

test("5. regresión: los ids VÁLIDOS no se rechazan (la validación no rompe lo normal)", async () => {
  const scope = await api("/api/skills?scope=global");
  assert.equal(scope.status, 200, "scope=global debe seguir funcionando");
  assert.equal(scope.body.ok, true);

  const wf = await api("/api/workflows/f4-proyecto-valido");
  assert.equal(wf.status, 200, "id válido en workflows debe seguir en 200");
  assert.equal(wf.body.ok, true);

  const jobs = await api("/api/jobs");
  assert.equal(jobs.status, 200);

  // Id con forma correcta pero fuera del catálogo -> sigue siendo ALLOWLIST (F3/H-14)
  const inst = await api("/api/skills/install", { method: "POST", body: { skillId: "id-bien-formado-fuera-catalogo" } });
  assert.equal(inst.status, 400);
  assert.equal(inst.body.error.code, "ALLOWLIST", "el allowlist F3 no puede perderse tras la validación F4");
});

test("6. GET /api/projects/:id/summary -> 404 SIEMPRE (bug PREEXISTENTE, documentado y NO tocado)", async () => {
  // server.js ~L2470: pathname.match(/^\/api\/projects\/[^\/]+\/summary$/) no tiene
  // grupo de captura => m[1] === undefined => id "undefined" => readSummary nunca
  // encuentra fichero => 404 para TODO id, válido o traversal. F4 NO lo arregla
  // (alcance: hardening, no cambiar comportamiento ya verificado); este test fija
  // que el traversal tampoco llega al filesystem.
  const malo = await api("/api/projects/..%2F..%2Fetc%2Fpasswd/summary");
  assert.equal(malo.status, 404, `summary con traversal debe quedar en 404, vino ${malo.status}`);
  assert.equal(malo.body.ok, false);
  assert.equal(malo.body.error.code, "NOT_FOUND");
  assert.ok(!JSON.stringify(malo.body).includes("root:"), "nunca debe volcar contenido externo");
});

test("7. los endpoints nuevos exigen token: /api/system/logs y /api/setup/manifest -> 403", async () => {
  for (const ruta of ["/api/system/logs", "/api/setup/manifest"]) {
    const { status, body } = await api(ruta, { withToken: false });
    assert.equal(status, 403, `${ruta} debe exigir X-Aegis-Token`);
    assert.equal(body.ok, false);
    assert.equal(body.error.code, "FORBIDDEN");

    const mala = await fetch(`http://127.0.0.1:${port}${ruta}`, { headers: { "X-Aegis-Token": "token-falso" } });
    assert.equal(mala.status, 403, `${ruta} con token inválido debe dar 403`);
  }
});

test("8. orden de middleware: sin token, el traversal responde 403 (auth ANTES que routers)", async () => {
  // Si alguien moviera la validación de ids antes del middleware de token, este
  // 403 se convertiría en 400 y se filtraría información de validación sin auth.
  const { status, body } = await api("/api/projects/..%2F..%2Fetc%2Fpasswd", { withToken: false });
  assert.equal(status, 403, "el rate-limit/token middleware va antes que cualquier router");
  assert.equal(body.error.code, "FORBIDDEN");
});
