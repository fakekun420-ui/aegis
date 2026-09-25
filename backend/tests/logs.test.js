// logs.test.js — FASE F4 · logger con sink+rotación y GET /api/system/logs
//
// Objetivo: fijar el contrato del logger nuevo (src/core/logger.js) y del
// endpoint de logs que lee ese sink. Cubre:
//   1. sink en AEGIS_LOG_DIR temporal + rotación por tamaño (AEGIS_LOG_MAX_BYTES
//      pequeño) => aparece aegis.log.1 y el endpoint devuelve ambas partes en
//      orden CRONOLÓGICO (.3 .2 .1 principal)
//   2. shape de Models.kt LogsResponse: data = Array<String> (NUNCA cambiado) +
//      espejo `logs` para consumidores nuevos + params ?lines= y legacy ?limit=
//   3. dir de log NO creable => el sink se desactiva sin lanzar y el endpoint
//      responde 200 con `note` honesto (data: []) en vez de 500
//
// El hijo recibe AEGIS_LOG_DIR temporal => el repo real backend/logs/ jamás se
// ensucia durante los tests. Los 429 del techo bajo son a propósito: es la forma
// determinista de generar líneas de log sin tocar estado real.

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
const LOG_DIR = `/tmp/opencode/aegis-f4-logs-${process.pid}`;
const LOG_FILE = join(LOG_DIR, "aegis.log");

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

async function api(pathname, { withToken = true, method = "GET" } = {}) {
  const headers = {};
  if (withToken) headers["X-Aegis-Token"] = token;
  const res = await fetch(`http://127.0.0.1:${port}${pathname}`, { method, headers });
  let parsed = null;
  try { parsed = await res.json(); } catch (_) { parsed = null; }
  return { status: res.status, body: parsed, headers: res.headers };
}

async function startHub(env = {}) {
  port = await freePort();
  child = spawn(process.execPath, ["server.js"], {
    cwd: BACKEND_DIR,
    env: { ...process.env, HUB_PORT: String(port), ...env },
    stdio: ["ignore", "pipe", "pipe"], // stdout SIEMPRE consumido (el logger llena el pipe)
  });
  let stderr = "";
  child.stderr.on("data", (c) => { stderr += c.toString(); });
  child.stdout.on("data", () => {});
  const deadline = Date.now() + 20000;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) {
      throw new Error(`server.js salió antes de estar listo (code=${child.exitCode})\n${stderr}`);
    }
    try {
      const r = await fetch(`http://127.0.0.1:${port}/api/health`, { signal: AbortSignal.timeout(1000) });
      if (r.status === 200) { token = fs.readFileSync(TOKEN_FILE, "utf8").trim(); return; }
    } catch (_) { /* aún no escucha */ }
    await new Promise((r) => setTimeout(r, 250));
  }
  throw new Error(`hub no respondió en 20000ms\n${stderr}`);
}

async function stopHub() {
  if (!child || child.exitCode !== null) return;
  child.kill("SIGTERM");
  await new Promise((r) => setTimeout(r, 1500));
  if (child.exitCode === null) child.kill("SIGKILL");
  child = null;
}

before(() => {
  try { fs.mkdirSync("/tmp/opencode", { recursive: true }); } catch { /* ya existe */ }
  try { fs.rmSync(LOG_DIR, { recursive: true, force: true }); } catch { /* inexistente */ }
});

after(async () => {
  await stopHub();
  try { fs.rmSync(LOG_DIR, { recursive: true, force: true }); } catch { /* limpieza final */ }
});

test("1. sink en AEGIS_LOG_DIR + rotación -> aegis.log.1 y endpoint en orden cronológico", async () => {
  // MAX_BYTES=300 (muy por debajo de las ~760B que escribe el arranque real del
  // hub) fuerza al menos UNA rotación sin escribir megas. Sin AEGIS_RATE_LIMIT_*
  // => techo por defecto, para que el propipetición al endpoint cuente y pase.
  await startHub({ AEGIS_LOG_DIR: LOG_DIR, AEGIS_LOG_MAX_BYTES: "300" });

  assert.ok(fs.existsSync(LOG_FILE), `el sink debe crear ${LOG_FILE}`);
  assert.ok(fs.existsSync(`${LOG_FILE}.1`), "la rotación debe haber creado aegis.log.1 durante el arranque");
  assert.ok(fs.statSync(`${LOG_FILE}.1`).size > 0, "aegis.log.1 no puede quedar vacío");

  const { status, body } = await api("/api/system/logs");
  assert.equal(status, 200, `esperaba 200, vino ${status}`);
  assert.equal(body.ok, true);

  // Shapes: LogsResponse (app, SOLO esto parsea Gson) + espejo documentado (F4)
  assert.ok(Array.isArray(body.data), "LogsResponse.data debe ser List<String>");
  assert.ok(body.data.length >= 3, `esperaba varias líneas, vinieron ${body.data.length}`);
  for (const line of body.data) assert.equal(typeof line, "string", "cada línea debe ser string");
  assert.deepEqual(body.logs, body.data, "el espejo `logs` debe ser idéntico a `data`");
  assert.ok(!("note" in body), "con fichero presente NO puede haber note");

  // Verificación INDEPENDIENTE del orden: re-leemos los backups nosotros mismos
  // (.3 .2 .1 principal) y la cola debe coincidir EXACTO con lo servido.
  const readTail = (n) => {
    let all = [];
    for (const f of [`${LOG_FILE}.3`, `${LOG_FILE}.2`, `${LOG_FILE}.1`, LOG_FILE]) {
      if (!fs.existsSync(f)) continue;
      all = all.concat(fs.readFileSync(f, "utf8").split("\n").filter(l => l.trim()));
    }
    return all.slice(-n);
  };
  const expected = readTail(Math.min(200, 5000));
  assert.deepEqual(body.data, expected, "el endpoint debe servir .3 .2 .1 principal en orden cronológico");

  // Orden CRONOLÓGICO real (no depende de cómo re-leamos los ficheros): los
  // timestamps ISO-8601 Z son ordenables como strings y no pueden ir hacia atrás.
  const stamps = body.data.map(l => (l.match(/^\[([^\]]+)\]/) || [])[1]).filter(Boolean);
  assert.ok(stamps.length >= 3, "las líneas deben llevar timestamp ISO");
  for (let i = 1; i < stamps.length; i++) {
    assert.ok(stamps[i] >= stamps[i - 1],
      `timestamps fuera de orden: ${stamps[i - 1]} > ${stamps[i]} (los backups se leyeron mal)`);
  }

  // Alguna línea tiene que vivir FUERA del fichero principal (prueba de rotación)
  const mainLines = fs.readFileSync(LOG_FILE, "utf8").split("\n").filter(l => l.trim());
  assert.ok(body.data.length > mainLines.length,
    "el endpoint debe devolver también las líneas que ya rotaron a backups");

  // Formato de línea F0-F3 INALTERADO: [ISO] [NIVEL] [módulo] mensaje {ctx}
  for (const line of body.data) {
    assert.match(line, /^\[\d{4}-\d{2}-\d{2}T[\d:.]+Z\] \[(INFO|WARN|ERROR|DEBUG)\] \[[^\]]+\] /,
      `formato de línea del logger cambiado: ${line.slice(0, 120)}`);
  }

  // Params: ?lines= (nuevo) y ?limit= legacy (ApiService.getSystemLogs envía limit=100)
  const full = await api("/api/system/logs");
  const total = full.body.data.length;
  const one = await api("/api/system/logs?lines=1");
  assert.equal(one.body.data.length, 1, "?lines=1 debe devolver exactamente 1 línea");
  assert.equal(one.body.data[0], full.body.data[total - 1], "?lines=1 = la más reciente");

  const legacy = await api("/api/system/logs?limit=1");
  assert.equal(legacy.body.data.length, 1, "el param legacy ?limit=1 debe seguir funcionando");
  assert.equal(legacy.body.data[0], full.body.data[total - 1]);

  const clamp = await api("/api/system/logs?lines=-5");
  assert.equal(clamp.body.data.length, 1, "lines negativo se clampcea a 1 (min 1 / max 5000)");

  const sinToken = await api("/api/system/logs", { withToken: false });
  assert.equal(sinToken.status, 403, "el endpoint de logs exige token");
});

test("2. AEGIS_LOG_DIR no creable -> sink desactivado sin lanzar y endpoint 200 con `note`", async () => {
  await stopHub();
  // /dev/null/x no es un dir creable => mkdir falla => el logger desactiva el
  // SINK y sigue sólo con stdout. El contrato F4: la E/S nunca tumba el hub.
  await startHub({ AEGIS_LOG_DIR: "/dev/null/x" });

  const { status, body } = await api("/api/system/logs");
  assert.equal(status, 200, "un sink inutilizable no puede convertir el endpoint en 500");
  assert.equal(body.ok, true);
  assert.deepEqual(body.data, [], "sin fichero, data es una lista vacía (nunca null: LogsResponse lo tolera pero la app prefiere lista)");
  assert.deepEqual(body.logs, body.data, "el espejo `logs` también vacío");
  assert.ok("note" in body, "si no hay fichero DEBE venir `note` explicando el porqué");
  assert.equal(typeof body.note, "string");
  assert.ok(body.note.length > 0, "note no puede ser vacío");
  assert.match(body.note, /aegis\.log/, "note debe citar la ruta esperada del sink");

  // El hub sigue vivo y sirviendo (el logger no ha tirado nada)
  const h = await api("/api/health", { withToken: false });
  assert.equal(h.status, 200, "el hub debe seguir respondiendo con el sink desactivado");
});
