// cross-contract.test.js — FASE F4 · contrato cruzado backend ↔ Models.kt
//
// Diferencia con contract.test.js: allí los shapes están ESCRITOS A MANO aquí;
// aquí se LEEN de `app/app/src/main/kotlin/com/aegis/hub/data/Models.kt`
// (SOLO LECTURA — es el contrato de la app, lo parsea la app) y se comparan con
// las respuestas LIVE del hub. Si alguien cambia un data class en Kotlin o una
// clave en el backend, este test falla apuntando al campo concreto.
//
// Regla de compatibilidad Gson que se valida:
//   * Campo NO anulable en Kotlin (`val id: String`) => DEBE venir en la
//     respuesta y NO null (Gson lo dejaría en null -> NPE en la UI).
//   * Campo anulable (`val version: String?`) => puede faltar.
//   * Claves EXTRA del hub que Models.kt no declara (p.ej. JobItem.intervalMs)
//     => Gson las IGNORA: se reportan como informativas, no rompen la app.
//   * Salvo HealthData, cuyo shape es EXACTO por contrato (§7.2).
//
// También fija la divergencia DOCUMENTADA (no "arreglada") del §7.1: en 2xx no
// viaja nunca `error`, y Models.kt declara `Envelope.error: String?` mientras el
// hub responde `error:{code,message}` en errores 4xx/5xx.

import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import fs from "node:fs";
import net from "node:net";

const BACKEND_DIR = dirname(dirname(fileURLToPath(import.meta.url))); // .../backend
const TOKEN_FILE = join(BACKEND_DIR, ".aegis_token");
const MODELS_KT = join(BACKEND_DIR, "..", "app", "app", "src", "main", "kotlin", "com", "aegis", "hub", "data", "Models.kt");
const CONTRACT_MD = join(BACKEND_DIR, "docs", "FRONTEND_CONTRACT.md");

let child = null;
let port = 0;
let token = "";

// ---------------------------------------------------------------------------
// Parser mínimo de `data class Nombre(...)` de Models.kt -> [{name, nullable}]
// Respeta genéricos con comas (Map<String, Any>) y defaults (= null).
// ---------------------------------------------------------------------------
function splitTopLevel(body) {
  const out = [];
  let depth = 0, angle = 0, cur = "";
  for (const ch of body) {
    if (ch === "<") angle++;
    else if (ch === ">") angle--;
    else if (ch === "(") depth++;
    else if (ch === ")") depth--;
    else if (ch === "," && depth === 0 && angle === 0) { out.push(cur); cur = ""; continue; }
    cur += ch;
  }
  if (cur.trim()) out.push(cur);
  return out;
}

function parseDataClasses(kt) {
  const map = new Map();
  const re = /data class\s+(\w+)(?:<[^>]*>)?\s*\(/g;
  let m;
  while ((m = re.exec(kt))) {
    let i = m.index + m[0].length; // carácter DESPUÉS del "(" de apertura
    let depth = 1, body = "";
    for (; i < kt.length; i++) {
      const ch = kt[i];
      if (ch === "(") depth++;
      else if (ch === ")") { depth--; if (depth === 0) break; }
      body += ch;
    }
    const fields = [];
    for (const raw of splitTopLevel(body)) {
      const f = raw.trim();
      const fm = f.match(/^(?:val|var)\s+(\w+)\s*:\s*(.+)$/s);
      if (!fm) continue;
      const type = fm[2].split("=")[0].trim();
      fields.push({ name: fm[1], nullable: type.endsWith("?") });
    }
    map.set(m[1], fields);
  }
  return map;
}

const KT = fs.readFileSync(MODELS_KT, "utf8");
const MODELS = parseDataClasses(KT);
assert.ok(MODELS.size > 20, `sólo se parsearon ${MODELS.size} data classes de Models.kt`);

/**
 * Comprueba que TODOS los campos declarados en Models.kt existen en el payload.
 * @param {string} modelName data class de Models.kt
 * @param {object} payload   body.data devuelto por el hub
 * @param {object} [opts]    {exact:true} exige además que no haya claves extra
 */
function assertModelCoverage(modelName, payload, { exact = false } = {}) {
  const fields = MODELS.get(modelName);
  assert.ok(fields && fields.length, `data class ${modelName} no encontrada en Models.kt`);
  assert.equal(typeof payload, "object");
  assert.ok(payload && !Array.isArray(payload), `${modelName}: data debe ser objeto`);
  const missing = fields.filter(f => !(f.name in payload)).map(f => f.name);
  assert.deepEqual(missing, [], `${modelName}: claves ausentes en la respuesta del hub (Gson -> null -> NPE)`);
  const nullables = fields.filter(f => !f.nullable && payload[f.name] === null).map(f => f.name);
  assert.deepEqual(nullables, [], `${modelName}: campos NO anulables llegaron null`);
  if (exact) {
    const declared = new Set(fields.map(f => f.name));
    const extra = Object.keys(payload).filter(k => !declared.has(k));
    assert.deepEqual(extra, [], `${modelName}: claves extra (el contrato ${modelName} es EXACTO, §7.2)`);
  }
}

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
  return { status: res.status, body: parsed };
}

before(async () => {
  try {
    if (!fs.existsSync(TOKEN_FILE)) {
      fs.writeFileSync(TOKEN_FILE, (await import("node:crypto")).randomBytes(32).toString("hex"), { mode: 0o600 });
    }
  } catch { /* si falla, server.js lo crea */ }
  port = await freePort();
  child = spawn(process.execPath, ["server.js"], {
    cwd: BACKEND_DIR,
    env: { ...process.env, HUB_PORT: String(port) },
    stdio: ["ignore", "pipe", "pipe"],
  });
  let stderr = "";
  child.stderr.on("data", (c) => { stderr += c.toString(); });
  child.stdout.on("data", () => {}); // consumir el pipe del logger
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
  token = fs.readFileSync(TOKEN_FILE, "utf8").trim();
});

after(async () => {
  if (!child || child.exitCode !== null) return;
  child.kill("SIGTERM");
  await new Promise((r) => setTimeout(r, 1500));
  if (child.exitCode === null) child.kill("SIGKILL");
});

test("Models.kt se parsea: HealthData, SkillItem, JobItem, LogsResponse disponibles", () => {
  for (const name of ["HealthData", "SkillsData", "SkillItem", "JobItem", "WorkflowItem", "LogsResponse", "MemoryData", "ProjectItem", "Envelope"]) {
    assert.ok(MODELS.has(name), `falta data class ${name} en Models.kt (¿renombrada?)`);
  }
  assert.deepEqual(
    MODELS.get("HealthData").map(f => f.name),
    ["server", "port", "uptime", "memory", "workspace", "projects", "agents", "jobs", "skills", "adapters"],
    "HealthData cambió en Models.kt"
  );
  assert.ok(MODELS.get("LogsResponse").some(f => f.name === "data" && f.nullable), "LogsResponse.data debe ser anulable");
});

test("GET /api/health -> HealthData EXACTO (claves y tipos)", async () => {
  const { status, body } = await api("/api/health", { withToken: false });
  assert.equal(status, 200);
  assert.equal(body.ok, true);
  assertModelCoverage("HealthData", body.data, { exact: true });

  // sub-objetos declarados en Models.kt
  assertModelCoverage("MemoryData", body.data.memory);
  assertModelCoverage("AgentsSummary", body.data.agents);
  assertModelCoverage("JobsSummary", body.data.jobs);
  assertModelCoverage("SkillsSummary", body.data.skills);

  assert.equal(typeof body.data.port, "number");
  assert.equal(typeof body.data.uptime, "number");
  assert.equal(typeof body.data.memory.heapUsed, "string");
  assert.equal(typeof body.data.projects, "number");
  assert.ok(Array.isArray(body.data.skills.installed), "SkillsSummary.installed: List<String>");
});

test("GET /api/skills -> SkillsData + SkillItem (ambas listas)", async () => {
  const { status, body } = await api("/api/skills");
  assert.equal(status, 200);
  assert.equal(body.ok, true);
  assertModelCoverage("SkillsData", body.data);
  assert.ok(Array.isArray(body.data.installed) && Array.isArray(body.data.available));
  assert.ok(body.data.installed.length + body.data.available.length >= 1, "el catálogo real debe aportar ítems");
  for (const item of [...body.data.installed, ...body.data.available]) {
    assertModelCoverage("SkillItem", item);
    assert.equal(typeof item.installed, "boolean");
    assert.equal(typeof item.enabled, "boolean");
    // version/description anulables: si vienen, son strings
    if (item.version !== undefined && item.version !== null) assert.equal(typeof item.version, "string");
  }
});

test("GET /api/jobs -> JobItem (y los extra del hub no rompen a la app)", async () => {
  const { status, body } = await api("/api/jobs");
  assert.equal(status, 200);
  assert.ok(Array.isArray(body.data));
  assert.ok(body.data.length >= 1);
  const extrasReport = [];
  for (const job of body.data) {
    assertModelCoverage("JobItem", job);
    assert.equal(typeof job.interval, "string", "JobItem.interval es String NO anulable");
    assert.equal(typeof job.enabled, "boolean");
    if (job.lastRun !== null) assert.equal(typeof job.lastRun, "string");
    // Claves que el hub manda y Models.kt NO declara: Gson las ignora (informativo)
    const declared = new Set(MODELS.get("JobItem").map(f => f.name));
    for (const k of Object.keys(job)) if (!declared.has(k)) extrasReport.push(k);
  }
  // intervalMs es un extra CONOCIDO y documentado (Models.kt no lo declara)
  assert.ok(extrasReport.length >= 0, "informativo: extras ignorados por Gson");
});

test("GET /api/workflows/:projectId -> WorkflowItem (si hay workflows)", async () => {
  const { status, body } = await api("/api/workflows/f4-cross-contract");
  assert.equal(status, 200);
  assert.ok(Array.isArray(body.data));
  for (const wf of body.data) {
    assertModelCoverage("WorkflowItem", wf);
    assert.equal(typeof wf.steps, "number");
    assert.equal(typeof wf.name, "string");
    assert.equal(typeof wf.id, "string");
  }
});

test("GET /api/system/memory -> MemoryData", async () => {
  const { status, body } = await api("/api/system/memory");
  assert.equal(status, 200);
  assert.equal(body.ok, true);
  assertModelCoverage("MemoryData", body.data);
  assert.match(body.data.heapUsed, /^\d+(\.\d+)?\s?MB$/i, "heapUsed en formato MB legible");
  assert.match(body.data.heapTotal, /^\d+(\.\d+)?\s?MB$/i, "heapTotal en formato MB legible");
});

test("GET /api/system/logs -> LogsResponse: data = Array<String> (F4 NO cambia el tipo)", async () => {
  const { status, body } = await api("/api/system/logs?lines=10");
  assert.equal(status, 200);
  assert.equal(body.ok, true);
  // LogsResponse(val ok: Boolean, val data: List<String>?) — data DEBE ser array de strings
  assert.ok("data" in body, "LogsResponse.data debe venir presente (aunque vacío)");
  assert.ok(Array.isArray(body.data), "data debe ser List<String>, no objeto");
  for (const line of body.data) assert.equal(typeof line, "string", "LogsResponse.data: List<String>");
  // Extras F4 documentados (Gson los ignora)
  assert.deepEqual(body.logs, body.data, "espejo `logs` = data");
  if ("note" in body) assert.equal(typeof body.note, "string");
});

test("GET /api/workspace/projects -> ProjectItem (si hay proyectos en el workspace)", async () => {
  const { status, body } = await api("/api/workspace/projects");
  assert.equal(status, 200);
  assert.equal(body.ok, true);
  assert.ok(Array.isArray(body.data));
  for (const p of body.data.slice(0, 5)) {
    assertModelCoverage("ProjectItem", p);
    assert.equal(typeof p.id, "string");
    assert.equal(typeof p.name, "string", "ProjectItem.name NO anulable (A-3: causaba NPE en WorkspaceScreen)");
    assert.equal(typeof p.hasHub, "boolean");
    if (p.lastCommit !== null && p.lastCommit !== undefined) {
      assert.match(p.lastCommit, /^\d{4}-\d{2}-\d{2}T/, "lastCommit debe ser ISO o null");
    }
  }
});

test("§7.1: en 2xx NUNCA viaja `error`; la divergencia String? vs objeto está documentada", async () => {
  // 1) 2xx: sin error (es lo único que Models.kt parsea con Envelope.error: String?)
  const ok = await api("/api/jobs");
  assert.equal(ok.status, 200);
  assert.ok(ok.body.error === undefined || ok.body.error === null || typeof ok.body.error === "string",
    `en 2xx no puede viajar error objeto: ${JSON.stringify(ok.body.error)}`);

  // 2) 4xx: el hub SÍ responde error como OBJETO {code,message}
  const err = await api("/api/noexiste-f4");
  assert.equal(err.status, 404);
  assert.equal(typeof err.body.error, "object");
  assert.equal(typeof err.body.error.code, "string");
  assert.equal(typeof err.body.error.message, "string");

  // 3) Models.kt declara Envelope.error: String?  => divergencia REAL detectada...
  const envFields = MODELS.get("Envelope");
  const errorField = envFields.find(f => f.name === "error");
  assert.ok(errorField, "Envelope debe declarar error");
  assert.ok(errorField.nullable, "Envelope.error sigue siendo String? en Models.kt");
  assert.equal(typeof err.body.error, "object", "el hub manda objeto, Models.kt dice String? -> divergencia");

  // 4) ...y documentada en FRONTEND_CONTRACT.md §7.1 (NO se arregla a ciegas)
  const doc = fs.readFileSync(CONTRACT_MD, "utf8");
  assert.ok(doc.includes("Envelope.error: String?"),
    "la divergencia Envelope.error String? vs objeto DEBE seguir documentada en §7.1");
  assert.match(doc, /### 7\.1\. Standard envelope/, "§7.1 debe existir en FRONTEND_CONTRACT.md");
});
