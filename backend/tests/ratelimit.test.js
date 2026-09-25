// ratelimit.test.js — FASE F4 · rate limiting del hub (backend)
//
// Objetivo: fijar el contrato del middleware de rate limiting añadido en F4
// (server.js, ANTES del middleware de token). Un hub de producción en loopback
// sólo ve un bucket por IP, así que el techo protege al propio dispositivo.
//
// Contrato verificado aquí:
//   * ventana deslizante de 60s en memoria, sin dependencias
//   * techo por defecto 120 req/min por IP (AEGIS_RATE_LIMIT_N lo cambia)
//   * AEGIS_RATE_LIMIT=0 desactiva el límite por completo
//   * EXENTOS de consumir cuota: GET /api/health y GET /api/bootstrap/state
//     (sonda de keepalive.sh y polling del wizard)
//   * 429 => envelope {ok:false,error:{code:"RATE_LIMITED"}} + header Retry-After
//
// Cada test spawnea SU hub (env independiente = contrato independiente) en puerto
// efímero: jamás toca el hub de producción de :8765.

import { test, after } from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import fs from "node:fs";
import net from "node:net";

const BACKEND_DIR = dirname(dirname(fileURLToPath(import.meta.url))); // .../backend
const TOKEN_FILE = join(BACKEND_DIR, ".aegis_token");

const hubs = []; // todos los hijos creados => after() los mata sin dejar huérfanos

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

/** Arranca server.js REAL con el env dado y devuelve {port, token, api, child}. */
async function startHub(env = {}) {
  const port = await freePort();
  const child = spawn(process.execPath, ["server.js"], {
    cwd: BACKEND_DIR,
    // stdout consumido (el logger escribe mucho en el pipe y se llenaría)
    env: { ...process.env, HUB_PORT: String(port), ...env },
    stdio: ["ignore", "pipe", "pipe"],
  });
  hubs.push(child);

  let stderr = "";
  child.stderr.on("data", (c) => { stderr += c.toString(); });
  child.stdout.on("data", () => {}); // OBLIGatorio: beber el pipe del logger

  const deadline = Date.now() + 20000;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) {
      throw new Error(`server.js salió antes de estar listo (code=${child.exitCode})\n${stderr}`);
    }
    try {
      const r = await fetch(`http://127.0.0.1:${port}/api/health`, { signal: AbortSignal.timeout(1000) });
      if (r.status === 200) break;
    } catch (_) { /* aún no escucha */ }
    await new Promise((r) => setTimeout(r, 250));
    if (Date.now() >= deadline) throw new Error(`hub no respondió en 20000ms\n${stderr}`);
  }

  const token = fs.readFileSync(TOKEN_FILE, "utf8").trim();
  const api = async (pathname, { withToken = true, method = "GET", body } = {}) => {
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
    return { status: res.status, body: parsed, headers: res.headers };
  };
  return { port, token, api, child };
}

after(async () => {
  for (const h of hubs) {
    if (!h || h.exitCode !== null) continue;
    h.kill("SIGTERM");
  }
  await new Promise((r) => setTimeout(r, 1500));
  for (const h of hubs) {
    if (h && h.exitCode === null) h.kill("SIGKILL");
  }
});

test("A. techo N=5: exentas no consumen cuota, la 6ª contada -> 429 + Retry-After", async () => {
  const { api } = await startHub({ AEGIS_RATE_LIMIT_N: "5" });

  // 1) exentas: 30 health + 30 bootstrap/state NO deben gastar ni una unidad
  for (let i = 0; i < 30; i++) {
    const h = await api("/api/health", { withToken: false });
    assert.equal(h.status, 200, `health exenta #${i} debe ser 200`);
    const b = await api("/api/bootstrap/state");
    assert.equal(b.status, 200, `bootstrap/state exenta #${i} debe ser 200`);
  }

  // 2) exactamente N peticiones contadas pasan
  for (let i = 0; i < 5; i++) {
    const r = await api("/api/jobs");
    assert.equal(r.status, 200, `la petición contada #${i + 1}/5 debe pasar (techo 5)`);
  }

  // 3) la siguiente EXCEDE -> 429 con envelope + Retry-After
  const over = await api("/api/jobs");
  assert.equal(over.status, 429, `la petición #6 debe ser 429, vino ${over.status}`);
  assert.equal(over.body.ok, false);
  assert.equal(over.body.error.code, "RATE_LIMITED");
  assert.match(over.body.error.message, /5/);
  const retryAfter = over.headers.get("retry-after");
  assert.ok(retryAfter, "el 429 debe llevar header Retry-After");
  const secs = parseInt(retryAfter, 10);
  assert.ok(Number.isFinite(secs) && secs >= 1 && secs <= 60, `Retry-After fuera de rango: ${retryAfter}`);

  // 4) estando limitado, las exentas SIGUEN respondiendo (no se bloquea el wizard ni keepalive)
  const h = await api("/api/health", { withToken: false });
  assert.equal(h.status, 200, "GET /api/health debe seguir 200 con la cuota agotada");
  const b = await api("/api/bootstrap/state");
  assert.equal(b.status, 200, "GET /api/bootstrap/state debe seguir 200 con la cuota agotada");

  // ...y otras rutas siguen bloqueadas
  const still = await api("/api/jobs");
  assert.equal(still.status, 429, "sigue limitado dentro de la ventana de 60s");
});

test("B. AEGIS_RATE_LIMIT=0 desactiva el límite (40 peticiones seguidas sin 429)", async () => {
  const { api } = await startHub({ AEGIS_RATE_LIMIT: "0" });
  for (let i = 0; i < 40; i++) {
    const r = await api("/api/jobs");
    assert.equal(r.status, 200, `petición #${i + 1} no debe ser 429 con el límite desactivado`);
  }
  // las exentas siguen exentas (y el token sigue obligatorio)
  const sin = await api("/api/jobs", { withToken: false });
  assert.equal(sin.status, 403, "desactivar el rate limit NO desactiva la autenticación");
});

test("C. techo por defecto = 120/min (las 121 primeras contadas pasan, la 121ª no)", async () => {
  const { api } = await startHub({}); // sin AEGIS_RATE_LIMIT_N -> default 120
  let primeras429 = -1;
  for (let i = 1; i <= 121; i++) {
    const r = await api("/api/jobs");
    if (r.status === 429) { primeras429 = i; break; }
    assert.equal(r.status, 200, `petición #${i} debe pasar con el techo por defecto`);
  }
  assert.equal(primeras429, 121, `el primer 429 debe llegar en la petición 121, llegó en ${primeras429}`);
});
