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
// Hallazgos del informe F4 vinculados a este fichero (estado tras el BACKLOG F0-F2):
//   * GET /api/projects/:id/summary usaba un regex SIN grupo de captura (server.js
//     ~L2470) => m[1] es undefined => id "undefined" => 404 SIEMPRE, también para
//     ids válidos. ARREGLADO en el backlog (regex con captura + isValidId): el
//     test 6 ahora exige el comportamiento CORRECTO (200 con fichero en disco,
//     404 honesto sin él, 400 PROJECT_INVALID para traversal) — ver comentario
//     del test 6.
//   * POST /opencode/session/:id/message (sin prefijo /api) quedaba FUERA del
//     middleware de token (revisión A-1). CERRADO en el backlog: el middleware
//     cubre /opencode/* => test 9 (sin token 403; con token, igual que antes).

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

test("6. GET /api/projects/:id/summary -> 200 con shape para id válido; 404 honesto sin fichero; 400 para traversal (bug del regex SIN captura, corregido en backlog)", async () => {
  // ANTES (bug preexistente documentado en F4): el regex no tenía grupo de captura
  // => m[1] === undefined => id "undefined" => 404 para TODO id, con o sin fichero.
  // DESPUÉS del backlog F0-F2:
  //   * id válido + summaries/<id>.summary.json en disco -> 200 {ok,data:{projectId,summary,updatedAt}}
  //   * id válido SIN fichero -> 404 honesto (error.code NOT_FOUND, envelope F4)
  //   * id con traversal -> 400 PROJECT_INVALID ANTES de tocar el filesystem
  const validId = `f4-summary-${process.pid}`; // ^[A-Za-z0-9._-]+$ => id válido
  const summaryFile = join(BACKEND_DIR, "summaries", `${validId}.summary.json`);
  fs.mkdirSync(join(BACKEND_DIR, "summaries"), { recursive: true });
  fs.writeFileSync(summaryFile, JSON.stringify({
    projectId: validId,
    summary: "resumen de prueba del backlog F0-F2",
    updatedAt: "2026-09-24T00:00:00.000Z"
  }));
  try {
    const okRes = await api(`/api/projects/${validId}/summary`);
    assert.equal(okRes.status, 200, `id válido con fichero debe dar 200, vino ${okRes.status} (${JSON.stringify(okRes.body).slice(0, 200)})`);
    assert.equal(okRes.body.ok, true);
    assert.equal(okRes.body.data.projectId, validId, "data.projectId debe ser el id REAL (no 'undefined')");
    assert.equal(okRes.body.data.summary, "resumen de prueba del backlog F0-F2");
    assert.equal(typeof okRes.body.data.updatedAt, "string");

    // Id válido pero SIN summary en disco -> 404 honesto (no un 200 falso ni el
    // viejo 404 por id "undefined": aquí el id del message SÍ es el correcto)
    const sinFichero = await api("/api/projects/backlog-f4-sin-summary/summary");
    assert.equal(sinFichero.status, 404);
    assert.equal(sinFichero.body.ok, false);
    assert.equal(sinFichero.body.error.code, "NOT_FOUND");
    assert.match(sinFichero.body.error.message, /backlog-f4-sin-summary/, "el 404 debe citar el id pedido");
  } finally {
    try { fs.unlinkSync(summaryFile); } catch (_) {}
  }

  // Traversal: 400 PROJECT_INVALID ANTES de leer summaries/ (antes "404 por id undefined")
  const malo = await api("/api/projects/..%2F..%2Fetc%2Fpasswd/summary");
  assert.equal(malo.status, 400, `summary con traversal debe quedar en 400, vino ${malo.status}`);
  assert.equal(malo.body.ok, false);
  assert.equal(malo.body.error.code, "PROJECT_INVALID");
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

test("9. /opencode/* exige token (cierre A-1 del backlog): sin token 403; con token, igual que antes", async () => {
  // Rutas sin prefijo /api que ANTES del cierre quedaban fuera del middleware de
  // token (cualquier proceso local las usaba gratis). Con token, la validación F4
  // de ids es la que manda — MISMO resultado que antes del cierre (400), lo que
  // demuestra que autenticar no cambia el flujo autenticado.
  const sin = await api("/opencode/session/backlog-sin-token/message", { withToken: false, method: "POST", body: {} });
  assert.equal(sin.status, 403, "POST /opencode/session/:id/message sin token debe dar 403");
  assert.equal(sin.body.ok, false);
  assert.equal(sin.body.error.code, "FORBIDDEN");

  const falso = await fetch(`http://127.0.0.1:${port}/opencode/session/backlog-token-falso/message`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-Aegis-Token": "token-falso" },
    body: "{}"
  });
  assert.equal(falso.status, 403, "con token inválido también 403");

  // El proxy de respaldo /opencode/* (antes puerta abierta al opencode serve)
  const proxy = await api("/opencode/global/health", { withToken: false });
  assert.equal(proxy.status, 403, "GET /opencode/* sin token debe dar 403");

  // Con token: se comporta como ANTES del cierre (la validación de sessionId F4
  // responde 400 SESSION_INVALID antes de tocar store/adapter — id con traversal)
  const con = await api("/opencode/session/..%2F..%2Fetc%2Fpasswd/message", { method: "POST", body: {} });
  assert.equal(con.status, 400, "con token el flujo previo no cambia (400 SESSION_INVALID)");
  assert.equal(con.body.ok, false);
  assert.equal(con.body.error.code, "SESSION_INVALID");
});
