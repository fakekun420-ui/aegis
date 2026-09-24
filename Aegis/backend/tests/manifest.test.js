// manifest.test.js — FASE F4 · SBOM de GET /api/setup/manifest (backend)
//
// Objetivo: fijar el shape del manifiesto de dependencias (SBOM) que el wizard
// y los audits usan para saber QUÉ trae este despliegue. Reglas del contrato:
//   * shape EXACTO: {hub, node, ubuntu, opencode, agy, skills, generatedAt}
//   * honestidad: donde no hay dato verificable el campo va `null` + `note`
//     (NUNCA se inventa una versión)
//   * hub.version sale de HUB_VERSION de server.js (se lee del FICHERO FUENTE:
//     F4 no sube la versión — eso lo hace F5 — y el test la sigue, no la pisa)
//   * versiones de node/ubuntu = manifests con los que el bootstrap INSTALA de
//     verdad (src/bootstrap/*-manifest.json), sha256 = 64 hex
//   * skills = ids de skills-manifest.json ∪ catálogo allowlist, con la versión
//     pineada del catálogo (graphify -> null, opencode-mem -> "2.26.0")
//   * sondeos opencode/agy con timeout corto => el endpoint resuelve en <8s
//
// NO se testea el valor EXACTO de opencode/agy: dependen de si el serve está
// arriba o si el binario existe en la máquina que ejecuta los tests. Se fija la
// forma (version string|null + note no vacío), que es lo que exige el contrato.

import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import fs from "node:fs";
import net from "node:net";

const BACKEND_DIR = dirname(dirname(fileURLToPath(import.meta.url))); // .../backend
const TOKEN_FILE = join(BACKEND_DIR, ".aegis_token");
const SERVER_FILE = join(BACKEND_DIR, "server.js");
const NODE_MANIFEST = join(BACKEND_DIR, "src", "bootstrap", "node-manifest.json");
const UBUNTU_MANIFEST = join(BACKEND_DIR, "src", "bootstrap", "ubuntu-manifest.json");
const SKILLS_MANIFEST = join(BACKEND_DIR, "src", "bootstrap", "skills-manifest.json");
const CATALOG = join(BACKEND_DIR, "src", "skills", "catalog.json");

let child = null;
let port = 0;
let token = "";

const readJson = (f) => JSON.parse(fs.readFileSync(f, "utf8"));
const isSha256 = (v) => typeof v === "string" && /^[0-9a-f]{64}$/.test(v);

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

async function api(pathname, { withToken = true } = {}) {
  const headers = {};
  if (withToken) headers["X-Aegis-Token"] = token;
  const res = await fetch(`http://127.0.0.1:${port}${pathname}`, { headers });
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
  assert.ok(token.length >= 32, "token ausente o demasiado corto");
});

after(async () => {
  if (!child || child.exitCode !== null) return;
  child.kill("SIGTERM");
  await new Promise((r) => setTimeout(r, 1500));
  if (child.exitCode === null) child.kill("SIGKILL");
});

test("1. GET /api/setup/manifest sin token -> 403 FORBIDDEN", async () => {
  const { status, body } = await api("/api/setup/manifest", { withToken: false });
  assert.equal(status, 403);
  assert.equal(body.ok, false);
  assert.equal(body.error.code, "FORBIDDEN");
});

test("2. shape EXACTO del SBOM + versiones derivadas de los manifests reales", async () => {
  const t0 = Date.now();
  const { status, body } = await api("/api/setup/manifest");
  const elapsed = Date.now() - t0;
  assert.equal(status, 200, `esperaba 200, vino ${status} (${JSON.stringify(body).slice(0, 300)})`);
  assert.equal(body.ok, true);
  assert.ok(elapsed < 8000, `manifest tardó ${elapsed}ms (sondas opencode 1.5s + agy 3s en paralelo => <8s)`);

  const m = body.data;
  assert.deepEqual(
    Object.keys(m).sort(),
    ["agy", "generatedAt", "hub", "node", "opencode", "skills", "ubuntu"],
    "shape del SBOM cambiado"
  );

  // hub.version = HUB_VERSION de server.js (leído del fuente: F4 no sube la versión, F5 lo hará)
  const src = fs.readFileSync(SERVER_FILE, "utf8");
  const hv = src.match(/const\s+HUB_VERSION\s*=\s*"([^"]+)"/);
  assert.ok(hv, "HUB_VERSION no encontrada en server.js");
  assert.deepEqual(Object.keys(m.hub), ["version"]);
  assert.equal(m.hub.version, hv[1], "hub.version debe ser literalmente HUB_VERSION de server.js");

  // node: runtime real + artefacto pineado
  const nodeMan = readJson(NODE_MANIFEST);
  assert.deepEqual(Object.keys(m.node).sort(), ["fileName", "sha256", "version"]);
  assert.equal(m.node.version, process.version, "node.version = process.version del hub");
  assert.equal(m.node.fileName, nodeMan.fileName, "node.fileName = node-manifest.json");
  assert.equal(m.node.sha256, nodeMan.sha256, "node.sha256 = node-manifest.json");
  assert.ok(isSha256(m.node.sha256), "node.sha256 debe ser 64 hex");

  // ubuntu: versión DERIVADA del fileName (ubuntu-base-24.04.5-... -> 24.04.5)
  const ubMan = readJson(UBUNTU_MANIFEST);
  assert.deepEqual(Object.keys(m.ubuntu).sort(), ["fileName", "sha256", "version"]);
  assert.equal(m.ubuntu.fileName, ubMan.fileName, "ubuntu.fileName = ubuntu-manifest.json");
  assert.ok(isSha256(m.ubuntu.sha256), "ubuntu.sha256 debe ser 64 hex");
  const derived = (ubMan.fileName || "").match(/ubuntu-base-([\d.]+)/);
  assert.ok(derived, "el fileName del manifest debe permitir derivar la versión");
  assert.equal(m.ubuntu.version, derived[1], "ubuntu.version debe derivarse del fileName");
  assert.equal(m.ubuntu.version, "24.04.5");

  // generatedAt: ISO real y reciente (nunca inventado/fijo)
  assert.equal(typeof m.generatedAt, "string");
  const gen = new Date(m.generatedAt);
  assert.ok(!Number.isNaN(gen.getTime()), `generatedAt no es fecha ISO: ${m.generatedAt}`);
  assert.ok(Math.abs(Date.now() - gen.getTime()) < 60000, "generatedAt debe ser ahora mismo");

  // Nunca secretos en el SBOM
  assert.ok(!JSON.stringify(body).includes(token), "el token del hub no puede filtrarse en el manifest");
});

test("3. honestidad null+note en las sondas opencode/agy (nunca versiones inventadas)", async () => {
  const { body } = await api("/api/setup/manifest");
  for (const key of ["opencode", "agy"]) {
    const entry = body.data[key];
    assert.deepEqual(Object.keys(entry).sort(), ["note", "version"], `${key} debe ser {version, note}`);
    assert.equal(typeof entry.note, "string", `${key}.note debe existir siempre`);
    assert.ok(entry.note.length > 0, `${key}.note no puede ser vacío`);
    if (entry.version === null) {
      assert.ok(entry.note.length > 0, `${key}.version null DEBE llevar note que explique el porqué`);
    } else {
      assert.equal(typeof entry.version, "string");
      assert.ok(String(entry.version).length > 0, `${key}.version no puede ser cadena vacía`);
    }
  }
});

test("4. skills = manifest ∪ catálogo, con la versión pineada (graphify null / opencode-mem 2.26.0)", async () => {
  const { body } = await api("/api/setup/manifest");
  const skills = body.data.skills;
  assert.ok(Array.isArray(skills), "skills debe ser array");
  assert.ok(skills.length >= 1, "skills no puede estar vacío (hay manifest + catálogo)");

  const bootIds = readJson(SKILLS_MANIFEST).map(e => e.id);
  const catalog = readJson(CATALOG);
  const expectedIds = [...new Set([...bootIds, ...catalog.map(e => e.id)])].sort();
  assert.deepEqual(skills.map(s => s.id).sort(), expectedIds, "skills = ids(manifest) ∪ ids(catálogo)");

  for (const s of skills) {
    assert.deepEqual(Object.keys(s).sort(), ["id", "version"], "cada skill = {id, version}");
    assert.equal(typeof s.id, "string");
    assert.ok(s.version === null || typeof s.version === "string", `${s.id}.version: string|null`);
  }

  const byId = new Map(skills.map(s => [s.id, s]));
  assert.equal(byId.get("graphify").version, null, "graphify no lleva versión pineada -> null honesto");
  assert.equal(byId.get("opencode-mem").version, "2.26.0", "opencode-mem sí está pineado en el catálogo");
});
