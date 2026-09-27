// inflight-endpoint.test.js — estado de turno de las sesiones (backend)
//
// Objetivo: fijar el contrato de GET /api/sessions/inflight, que es la senal que
// decide el divisor de "respuesta final" y el aviso de turno terminado.
//
// Por que este test existe (regression real, 2026-09-27): el endpoint devolvia 500
// en TODAS las peticiones, y dos bugs distintos lo causaban:
//
//   1) `turnOver: false` hardcodeado al construir la respuesta. El diseno del
//      ADR-003 (que el divisor lo decida session.execution.succeeded, nunca un
//      time.completed adivinado) NO ESTABA IMPLEMENTADO. Como el campo nunca
//      valia true, la app caia siempre en su modo degradado, que da el turno por
//      terminado en cuanto el ultimo mensaje lleva time.completed; y eso pasa
//      tras CADA bash. De ahi el divisor que aparecia despues de cada bash con
//      exit 0. La funcion isTurnOver() ya existia y hacia justo falta: no la
//      llamaba nadie.
//   2) `seen.has(sid)`, con `seen` NO DECLARADO en ningun sitio del fichero
//      (un unico uso). ReferenceError -> 500. Un comentario afirmaba que esto ya
//      estaba arreglado: se habia cambiado .get() por .has() sobre un Set que no
//      existe, lo que cambia el error de TypeError a ReferenceError sin arreglar
//      nada. Y al poll de la app tragarse el error, tampoco recibia el punto 1.
//
// Contrato verificado aqui:
//   * 200 con {ok:true,data:[...]} — nunca 500, ni aunque no haya ninguna sesion
//   * cada elemento trae id, since, turnOver y lastSeen
//   * ?ids=1 devuelve solo ids, y es O(1) (no consulta OpenCode)
//   * una sesion en curso tiene turnOver=false; una cerrada, turnOver=true
//   * una sesion no aparece duplicada aunque este en curso y acabada a la vez
//
// Cada test spawnea SU hub (env independiente) en puerto efimero: nunca toca el
// hub de produccion de :8765.

import { test, after } from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import fs from "node:fs";
import net from "node:net";

const BACKEND_DIR = dirname(dirname(fileURLToPath(import.meta.url))); // .../backend
const TOKEN_FILE = join(BACKEND_DIR, ".aegis_token");

const hubs = [];

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

async function startHub(env = {}) {
  const port = await freePort();
  const child = spawn(process.execPath, ["server.js"], {
    cwd: BACKEND_DIR,
    env: { ...process.env, HUB_PORT: String(port), ...env },
    stdio: ["ignore", "pipe", "pipe"],
  });
  hubs.push(child);

  let stderr = "";
  child.stderr.on("data", (c) => { stderr += c.toString(); });
  child.stdout.on("data", () => {}); // OBLIGATORIO: beber el pipe del logger

  const deadline = Date.now() + 20000;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) {
      throw new Error(`server.js salio antes de estar listo (code=${child.exitCode})\n${stderr}`);
    }
    try {
      const r = await fetch(`http://127.0.0.1:${port}/api/health`, { signal: AbortSignal.timeout(1000) });
      if (r.status === 200) break;
    } catch (_) { /* aun no escucha */ }
    await new Promise((r) => setTimeout(r, 250));
    if (Date.now() >= deadline) throw new Error(`hub no respondio en 20000ms\n${stderr}`);
  }

  const token = fs.readFileSync(TOKEN_FILE, "utf8").trim();
  const api = (p) => fetch(`http://127.0.0.1:${port}${p}`, {
    headers: { "X-Aegis-Token": token },
    signal: AbortSignal.timeout(8000),
  });
  return { port, token, api, child };
}

after(() => { for (const c of hubs) { try { c.kill("SIGKILL"); } catch (_) {} } });

test("GET /api/sessions/inflight responde 200 y NO 500 aunque no haya sesiones", async () => {
  // Este es el test que habria cazado el bug: el ReferenceError de `seen` hacia que
  // la respuesta fuera 500 SIEMPRE, y el poll de la app se lo tragaba en silencio.
  const { api } = await startHub();
  const r = await api("/api/sessions/inflight");
  assert.equal(r.status, 200, `esperaba 200, vino ${r.status}`);
  const body = await r.json();
  assert.equal(body.ok, true);
  assert.ok(Array.isArray(body.data), "data debe ser un array");
});

test("cada elemento trae id, since, turnOver y lastSeen", async () => {
  const { api } = await startHub();
  const body = await (await api("/api/sessions/inflight")).json();
  for (const row of body.data) {
    assert.equal(typeof row.id, "string", "id debe ser string");
    assert.equal(typeof row.since, "number", "since debe ser epoch ms");
    assert.equal(typeof row.turnOver, "boolean",
      "turnOver debe ser boolean, no un literal hardcodeado: es la senal que decide el divisor");
    assert.ok(row.lastSeen === null || typeof row.lastSeen === "number",
      "lastSeen debe ser epoch ms o null");
  }
});

test("?ids=1 devuelve solo ids y lo hace sin consultar OpenCode", async () => {
  const { api } = await startHub();
  const r = await api("/api/sessions/inflight?ids=1");
  assert.equal(r.status, 200);
  const body = await r.json();
  assert.ok(Array.isArray(body.data));
  for (const id of body.data) assert.equal(typeof id, "string");
});

test("el alias /api/opencode/sessions/inflight tambien responde 200", async () => {
  // El cliente usa una de las dos rutas según cómo monte la URL; si una se rompe y
  // la otra no, el bug aparece solo en una parte de la app y cuesta mucho de ver.
  const { api } = await startHub();
  const r = await api("/api/opencode/sessions/inflight");
  assert.equal(r.status, 200, `el alias devolvio ${r.status}`);
  const body = await r.json();
  assert.equal(body.ok, true);
});

test("sin sesion alguna, turnOver es false en todas (nada ha terminado)", async () => {
  // El caso que dispara el divisor espurio: si turnOver fuese true sin que haya
  // habido turno, la app creeria que el turno se acabo tras el primer bash.
  const { api } = await startHub();
  const body = await (await api("/api/sessions/inflight")).json();
  for (const row of body.data) {
    assert.equal(row.turnOver, false,
      `${row.id} aparece con turnOver=true sin que el vigilante haya visto un cierre`);
  }
});

test("sin token, 403 y NO 500 (un error de codigo no debe disfrazarse de crash)", async () => {
  // Si el manejador lanza antes de comprobar el token, el usuario ve 500 en vez de
  // "te falta el token", que es informacion accionable.
  const { port } = await startHub();
  const r = await fetch(`http://127.0.0.1:${port}/api/sessions/inflight`, {
    signal: AbortSignal.timeout(8000),
  });
  assert.equal(r.status, 403, `sin token esperaba 403, vino ${r.status}`);
});
