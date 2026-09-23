// steps.js — registro de pasos del bootstrap (FASE F1 checks · FASE F2 motor de instalación)
//
// STEPS exporta los 6 pasos EN EL ORDEN del contrato (STEP_DEFS importado de
// state.js, fuente única de id/título) con la firma de hooks:
//
//   check(ctx)   -> {done, detail}   comprobación IDEMPOTENTE (sin mutate):
//                                    ¿el paso ya está satisfecho en el dispositivo?
//   run(ctx)                      F2: motor de instalación REAL por paso (tabla
//                                    abajo), cada uno con su rollback. En dry
//                                    (AEGIS_BOOTSTRAP_DRY=1) simula progreso
//                                    rápido con detail "[DRY]" sin mutar ni
//                                    descargar. preflight sigue siendo el check
//                                    duro de F1 (root/su · arch · disco · red).
//   rollback(ctx) -> "none"|"done"|"failed"
//                                    F2: best-effort y NO destructivo fuera de
//                                    lo creado por ESTE run. Las acciones de
//                                    reversa se registran en run() vía
//                                    ctx.onRollback(fn) y drainRollbacks() las
//                                    ejecuta en LIFO; si alguna falla =>
//                                    "failed" (el paso queda failed reanudable).
//
// HOOK DE TEST (solo tests, documentado):
//   AEGIS_BOOTSTRAP_FAIL=<stepId>  => check() de ese paso devuelve done:false
//                                    (para forzar run) y run() lanza
//                                    Error("FAIL_INJECTED") ANTES de mutar.
//                                    Permite probar fallo→rollback→retry/resume
//                                    sin tocar el dispositivo.
//   AEGIS_BOOTSTRAP_TEST_STEP=<id> => paso fake extra al FINAL (state.js): su run
//                                    crea AEGIS_BOOTSTRAP_TEST_ARTIFACT y lanza
//                                    FAIL_INJECTED → prueba de rollback REAL sin
//                                    red (bootstrap-rollback.test.js).
//
// ctx (lo construye orchestrator.js):
//   execFile(file, argv, {timeout})  promisificado SIN shell (lista de argv).
//     Resuelve SIEMPRE {code, stdout, stderr, error} y NUNCA rechaza: un comando
//     inexistente o una salida != 0 es información para el detail, no una
//     excepción (los check son no-lanzadores por diseño; preflight.run sí lanza
//     cuando falta un requisito duro).
//   log             createLogger("bootstrap")
//   dry             bool (AEGIS_BOOTSTRAP_DRY=1) => run() no muta ni spawnea
//   progress(pct, detail)  actualiza el paso actual en el state (persistido)
//   cancelRequested() -> bool  (cancelación cooperativa dentro de pasos largos)
//   paths           {backendDir, stateFile}
//   env             process.env
//   onRollback(fn)  F2: registra una acción de reversa (LIFO) del run EN CURSO
//   recordArtifact(a) F2: inventario {type,path|id} de lo creado (diagnóstico)
//   rollbackFns/artifacts  arrays del ctx (se vacían antes de CADA run)
//
// Descargas (downloadVerified): fetch en streaming + SHA256 con
// crypto.createHash("sha256") en el propio stream → mismatch = tmp borrado +
// Error("EBADCHECKSUM…"); progreso con content-length si viene (si no, MB
// descargados); cancelación cooperativa periódica; timeout duro 10 min;
// REGLA OBLIGATORIA del roadmap: sha256 sin 64 hex => NO se descarga NADA
// ("Falta SHA256 del manifiesto: no se descarga nada sin verificación").
//
// Motor F2 por paso (run real → rollback):
//   ubuntu     descarga ubuntu-base (manifiesto, SHA256) → tar -xzf -C target →
//              valida target/etc/os-release. Rollback: tmp + target SOLO si lo
//              creó este run (jamás un directorio parcial ajeno).
//   node       reutiliza backend/node.bin (stage-node.sh) si responde, si no
//              descarga node-v24 (manifiesto, SHA256) → tar --strip-components=1
//              en $AEGIS_NODE_DIR o <rootfs>/usr/local → enlaza en PATH → node -v.
//              Rollback: tmp + dir creado + symlink creado (jamás un node ajeno).
//   opencode   wrapper 0o755 en /usr/local/bin|~/.local/bin apuntando al bundle
//              backend/opencode.cjs + node (NUNCA copia/borra el bundle original);
//              fallback npm install -g opencode-ai (id fijo, argv). Valida
//              `opencode --version`. Rollback: borra SOLO el wrapper, o npm
//              uninstall -g opencode-ai si lo instaló npm.
//   agy        asegura binario agy (sin paquete npm oficial → error honesto con el
//              instalador oficial documentado), crea ~/.gemini/antigravity-cli si
//              falta y DESPUÉS exige auth (token OAuth >0 B — su contenido NUNCA
//              se imprime ni se copia). Rollback: solo el dir creado aquí; ni el
//              binario preexistente ni NINGÚN token.
//   skills     SkillManager.install de lo que falte según skills-manifest.json con
//              ctx.progress(i/n). Rollback: uninstall SOLO los instalados en ESTE
//              run.
//
// Rutas REALES descubiertas en el dispositivo (find-ubuntu.sh, stage-node.sh,
// opencode.sh, keepalive.sh, AntigravityAdapter y sondeo directo):
//   - El hub ya corre DENTRO del rootfs Ubuntu 24.04 aarch64 (/etc/os-release
//     ID=ubuntu) y existe el ancla de chroot que localiza find-ubuntu.sh
//     (PID con "bash --login" + /proc/PID/root/usr/bin/node + server.js).
//   - node: /usr/bin/node (hub) + bundle staged backend/node.bin (stage-node.sh).
//   - opencode: bin en PATH (keepalive usa el ELF de opencode-ai; aquí hay
//     symlink /usr/local/bin/opencode) o bundle backend/node.bin + opencode.cjs
//     (exactamente lo que ejecuta opencode.sh).
//   - Antigravity/Artemis: binario agy en /root/.local/bin/agy (ELF, v1.2.9,
//     206MB — NO proviene de npm: los paquetes npm "agy"/"antigravity-cli" son
//     placeholders de terceros; la distribución oficial es el instalador
//     https://antigravity.google/cli/install.sh — docs: antigravity.google/docs/cli/install),
//     config/auth en ~/.gemini/antigravity-cli (antigravity-oauth-token + brain/)
//     y config Artemis en ~/.artemis/.artemis_env. NO hay ningún proxy
//     antigravity en 127.0.0.1:4096 — en este proyecto 4096 es el puerto de
//     OpenCode (OC_PORT en keepalive.sh).
//   - skills: SkillManager lee ~/.config/opencode/skills/*.json y los binarios
//     conocidos de ~/.local/bin (graphify está como symlink uv en disco).
//   - proot NO está instalado en este dispositivo; chroot (/usr/sbin/chroot) y
//     unshare (/usr/bin/unshare) SÍ → el fallback proot queda detectado y
//     documentado en el detail, listo para hosts sin privilegios.

import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import crypto from "node:crypto";
import { execFile as execFileCb } from "node:child_process";
import { SkillManager } from "../skills/SkillManager.js";
import { STEP_DEFS, BACKEND_DIR, TEST_STEP_ID } from "./state.js";

const EXEC_TIMEOUT_MS = 10000;
const NET_TIMEOUT_MS = 5000;
const NET_PROBE_URL = "https://registry.npmjs.org/";

// F2 — timeouts razonables: descargas ≤10 min; comandos ≤60 s (npm install es
// también descarga => DOWNLOAD_TIMEOUT_MS).
const DOWNLOAD_TIMEOUT_MS = 10 * 60 * 1000;
const CMD_TIMEOUT_MS = 60000;

export const SKILLS_MANIFEST_FILE = path.join(BACKEND_DIR, "src", "bootstrap", "skills-manifest.json");
export const UBUNTU_MANIFEST_FILE = path.join(BACKEND_DIR, "src", "bootstrap", "ubuntu-manifest.json");
export const NODE_MANIFEST_FILE = path.join(BACKEND_DIR, "src", "bootstrap", "node-manifest.json");

// Solo tests (F2): hook de fallo inyectado — documentado en la cabecera.
const FAIL_ENV = "AEGIS_BOOTSTRAP_FAIL";

// ---------------------------------------------------------------------------
// ctx.execFile — execFile promisificado SIN shell (argv en lista), no-lanzador
// ---------------------------------------------------------------------------
export function makeExecFile(env = process.env) {
  return (file, args = [], opts = {}) => new Promise(resolve => {
    if (typeof file !== "string" || !file) {
      resolve({ code: 127, stdout: "", stderr: "", error: "execFile: fichero no válido" });
      return;
    }
    const argv = (Array.isArray(args) ? args : []).map(a => String(a));
    const timeout = Number.isFinite(Number(opts.timeout)) ? Number(opts.timeout) : EXEC_TIMEOUT_MS;
    execFileCb(file, argv, {
      timeout,
      maxBuffer: 1024 * 1024,
      env: opts.env || env || process.env,
      cwd: opts.cwd
    }, (err, stdout, stderr) => {
      let code = 0;
      if (err) {
        // err.code es número (exit code) o string (ENOENT/EACCES…); los signals
        // (timeout => SIGTERM) quedan como 124/127 para no confundir con éxito.
        code = typeof err.code === "number" ? err.code : (err.killed ? 124 : 127);
      }
      resolve({
        code,
        stdout: String(stdout ?? ""),
        stderr: String(stderr ?? ""),
        error: err ? String(err.message || err).slice(0, 400) : null
      });
    });
  });
}

// Búsqueda en PATH sin shell (fs.accessSync X_OK) — evita depender de que
// execFile resuelva el PATH del hijo.
function whichSync(bin, env) {
  if (typeof bin !== "string" || !bin) return null;
  if (bin.includes("/")) {
    try { fs.accessSync(bin, fs.constants.X_OK); return bin; } catch { return null; }
  }
  const dirs = String((env && env.PATH) || process.env.PATH || "").split(path.delimiter);
  for (const d of dirs) {
    if (!d) continue;
    const p = path.join(d, bin);
    try { fs.accessSync(p, fs.constants.X_OK); return p; } catch { /* sigue */ }
  }
  return null;
}

function uniq(list) { return [...new Set(list.filter(Boolean))]; }

function gb(bytes) { return `${(bytes / (1024 * 1024 * 1024)).toFixed(1)}GB`; }

// ---------------------------------------------------------------------------
// preflight — euid/su · arquitectura · espacio en disco · red (sin mutate)
// ---------------------------------------------------------------------------
// Acepta arm64/aarch64 (dispositivo) y x64/x86_64 (CI linux) — es "lo que
// soportamos": el hub y los installers de F2 son multiarch puros (Node).
const SUPPORTED_ARCH = new Set(["arm64", "aarch64", "x64", "x86_64"]);
const MIN_FREE_BYTES = 512 * 1024 * 1024; // 0.5GB: rootfs + bundles pesan cientos de MB

async function freeBytes(ctx, dir) {
  // 1) fs.statfsSync (Node >= 18.15): sin spawn — vía preferente.
  try {
    const s = fs.statfsSync(dir);
    if (s && Number.isFinite(s.bsize) && Number.isFinite(s.bavail)) return s.bsize * s.bavail;
  } catch { /* fallback */ }
  // 2) `df -k <dir>` con parseo defensivo (2ª línea, 4ª columna en KB)
  try {
    const r = await ctx.execFile("df", ["-k", dir], { timeout: 4000 });
    if (r.code === 0) {
      const line = (r.stdout.split("\n")[1] || "").trim();
      const cols = line.split(/\s+/);
      const kb = parseInt(cols[3], 10);
      if (Number.isFinite(kb) && kb >= 0) return kb * 1024;
    }
  } catch { /* sin df */ }
  return null;
}

// Sonda de red: CUALQUIER respuesta HTTP prueba conectividad (un 403/405 a un
// HEAD sigue siendo "hay red"). Si falla, NUNCA lanza: devuelve el error.
async function probeNet(url = NET_PROBE_URL, timeoutMs = NET_TIMEOUT_MS) {
  try {
    const res = await fetch(url, { method: "HEAD", redirect: "follow", signal: AbortSignal.timeout(timeoutMs) });
    return { ok: true, status: res.status };
  } catch (e) {
    const cause = e && e.cause && (e.cause.code || e.cause.message);
    return { ok: false, error: `${cause ? cause + ": " : ""}${String((e && e.message) || e)}`.slice(0, 200) };
  }
}

async function preflightProblems(ctx) {
  const ok = [];
  const problems = [];

  // 1) privilegios: euid 0, o `su` disponible como alternativa
  let uid = null;
  try {
    const r = await ctx.execFile("id", ["-u"], { timeout: 4000 });
    if (r.code === 0) { const n = parseInt(r.stdout.trim(), 10); if (Number.isFinite(n)) uid = n; }
  } catch { /* fallback abajo */ }
  if (!Number.isFinite(uid) && typeof process.getuid === "function") uid = process.getuid();
  const su = uniq(["/usr/bin/su", "/bin/su", "/system/bin/su"]).find(p => fs.existsSync(p)) || null;
  if (uid === 0) ok.push("privilegios: euid=0 (root)");
  else if (su) ok.push(`privilegios: euid=${uid ?? "?"}, su disponible en ${su}`);
  else problems.push(`sin root/su (euid=${uid ?? "?"})`);

  // 2) arquitectura
  const arch = os.arch() || process.arch;
  if (SUPPORTED_ARCH.has(arch)) ok.push(`arquitectura ${arch}`);
  else problems.push(`arquitectura no soportada: ${arch} (soportadas: ${[...SUPPORTED_ARCH].join("/")})`);

  // 3) espacio en disco (donde se instalará: la raíz del backend)
  const free = await freeBytes(ctx, ctx.paths.backendDir);
  if (free === null) ok.push("espacio: no medible (sin statfs ni df) — no se bloquea");
  else if (free < MIN_FREE_BYTES) problems.push(`sin espacio: ${gb(free)} libres en ${ctx.paths.backendDir} (< 0.5GB)`);
  else ok.push(`espacio: ${gb(free)} libres en ${ctx.paths.backendDir}`);

  // 4) red (si falla: done:false con detalle honesto, SIN lanzar aquí)
  const net = await probeNet();
  if (net.ok) ok.push(`red: HEAD ${NET_PROBE_URL} -> ${net.status}`);
  else problems.push(`sin red: ${net.error}`);

  return { ok, problems };
}

async function preflightCheck(ctx) {
  const { ok, problems } = await preflightProblems(ctx);
  const detail = problems.length
    ? `${ok.join(" · ")} — FALTA: ${problems.join(" · ")}`
    : ok.join(" · ");
  return { done: problems.length === 0, detail };
}

async function preflightRun(ctx) {
  if (ctx.dry) {
    ctx.progress(100, "[DRY] preflight: comprobaciones ejecutadas sin mutar");
    return;
  }
  // Implementación REAL de F1: re-ejecuta las comprobaciones y LANZA si falta
  // un requisito duro (sin root/su, sin espacio, sin red o arquitectura ajena).
  const { ok, problems } = await preflightProblems(ctx);
  ctx.progress(60, ok.join(" · ") || "comprobando requisitos duros");
  if (problems.length) throw new Error(`preflight: requisito duro ausente — ${problems.join(" · ")}`);
  ctx.progress(100, ok.join(" · "));
}

// ---------------------------------------------------------------------------
// ubuntu — ¿existe ya un rootfs Ubuntu/Debian? (lógica de find-ubuntu.sh)
// ---------------------------------------------------------------------------
const HOME = os.homedir() || "/root";
// Rutas habituales de rootfs en Termux/Android + variantes documentadas
const UBUNTU_DIRS = uniq([
  path.join(HOME, ".aegis", "ubuntu"),
  "/root/.aegis/ubuntu",
  "/data/data/com.termux/files/home/ubuntu",
  "/data/data/com.termux/files/usr/ubuntu",
  "/sdcard/ubuntu",
  "/opt/ubuntu",
  "/data/local/tmp/ubuntu"
]);
const UBUNTU_IDS = new Set(["ubuntu", "debian"]);

function readOsRelease(file) {
  try {
    if (!fs.existsSync(file)) return null;
    const txt = fs.readFileSync(file, "utf8");
    const get = k => {
      const m = txt.match(new RegExp(`^${k}=(.*)$`, "m"));
      if (!m) return null;
      return m[1].trim().replace(/^"(.*)"$/, "$1").replace(/^'(.*)'$/, "$1");
    };
    return { id: (get("ID") || "").toLowerCase(), pretty: get("PRETTY_NAME") || get("NAME") || "" };
  } catch { return null; }
}

function isUbuntuFamily(rel) { return !!rel && UBUNTU_IDS.has(rel.id); }

// Réplica EN JS de backend/find-ubuntu.sh (sin spawn, mismos guards): cmdline
// con "bash --login" (init de ubuntu) y root view que muestra /usr/bin/node +
// sdcard/projects/Aegis/backend/server.js. Nunca matchea a sí mismo ni a su padre.
function findUbuntuAnchor() {
  const self = process.pid;
  const ppid = process.ppid;
  let entries = [];
  try { entries = fs.readdirSync("/proc"); } catch { return null; }
  for (const e of entries) {
    if (!/^[0-9]+$/.test(e)) continue;
    const pid = parseInt(e, 10);
    if (pid === self || pid === ppid) continue;
    try {
      if (!fs.existsSync(`/proc/${e}/root/usr/bin/node`)) continue;
      if (!fs.existsSync(`/proc/${e}/root/sdcard/projects/Aegis/backend/server.js`)) continue;
      const cmd = fs.readFileSync(`/proc/${e}/cmdline`, "utf8");
      if (!cmd.includes("bash --login")) continue;
      return pid;
    } catch { /* proc ilegible — sigue */ }
  }
  return null;
}

async function ubuntuCheck() {
  const probed = [];
  // 1) el propio hub ya corre dentro del rootfs (dispositivo real: Ubuntu 24.04)
  const local = readOsRelease("/etc/os-release") || readOsRelease("/usr/lib/os-release");
  if (local) probed.push(`/etc/os-release ID=${local.id || "?"}`);
  if (isUbuntuFamily(local)) {
    return { done: true, detail: `rootfs local: ${local.pretty || local.id} (ID=${local.id}) — el hub corre dentro de Ubuntu/Debian` };
  }
  // 2) ancla find-ubuntu.sh (chroot/proot vivo)
  const anchor = findUbuntuAnchor();
  if (anchor) {
    const rel = readOsRelease(`/proc/${anchor}/root/etc/os-release`);
    probed.push(`ancla pid ${anchor} ID=${rel ? rel.id : "?"}`);
    if (isUbuntuFamily(rel)) {
      return { done: true, detail: `rootfs ${rel.pretty || rel.id} (ID=${rel.id}) vía ancla pid ${anchor} — find-ubuntu.sh` };
    }
  }
  // 3) rutas habituales de rootfs
  for (const d of UBUNTU_DIRS) {
    if (!fs.existsSync(d)) continue;
    const rel = readOsRelease(path.join(d, "etc/os-release"));
    probed.push(`${d} ID=${rel ? rel.id : "?"}`);
    if (isUbuntuFamily(rel)) {
      return { done: true, detail: `rootfs ${rel.pretty || rel.id} (ID=${rel.id}) en ${d}` };
    }
  }
  return {
    done: false,
    detail: `sin rootfs Ubuntu/Debian detectado — sondeadas: ${probed.join(", ") || "ninguna existente"} (candidatas: ${UBUNTU_DIRS.join(", ")})`
  };
}

// ---------------------------------------------------------------------------
// node — ¿binario node ejecutable en el hub o en el rootfs?
// ---------------------------------------------------------------------------
async function nodeCheck(ctx) {
  const anchor = findUbuntuAnchor();
  const cands = uniq([
    process.execPath,                                  // el node que ejecuta el hub
    whichSync("node", ctx.env),
    "/usr/bin/node", "/usr/local/bin/node", "/system/bin/node",
    path.join(ctx.paths.backendDir, "node.bin"),       // stage-node.sh (bundle a /sdcard)
    anchor ? `/proc/${anchor}/root/usr/bin/node` : null // node del chroot (marcador find-ubuntu.sh)
  ]);
  for (const c of cands) {
    if (!fs.existsSync(c)) continue;
    const r = await ctx.execFile(c, ["-v"], { timeout: 6000 });
    const v = r.stdout.trim();
    if (r.code === 0 && /^v\d+/.test(v)) return { done: true, detail: `Node ${v} ejecutable en ${c}` };
  }
  return { done: false, detail: `ningún binario "node" ejecutable (probados: ${cands.join(", ")})` };
}

// ---------------------------------------------------------------------------
// opencode — binario en PATH (--version) o bundle backend/node.bin + opencode.cjs
// ---------------------------------------------------------------------------
async function opencodeCheck(ctx) {
  const cands = uniq([
    whichSync("opencode", ctx.env),
    "/usr/local/bin/opencode",
    "/usr/bin/opencode",
    "/data/data/com.termux/files/usr/bin/opencode",
    "/data/data/com.termux/files/usr/lib/node_modules/opencode-ai/bin/opencode.exe" // OPENCODE_BIN de keepalive.sh
  ]);
  for (const c of cands) {
    if (!c || !fs.existsSync(c)) continue;
    const r = await ctx.execFile(c, ["--version"], { timeout: 8000 });
    const v = r.stdout.trim();
    if (r.code === 0 && v) return { done: true, detail: `OpenCode ${v} en ${c}` };
  }
  // Fallback: el bundle exacto que ejecuta opencode.sh (node.bin + opencode.cjs)
  const cjs = path.join(ctx.paths.backendDir, "opencode.cjs");
  const nodeBin = path.join(ctx.paths.backendDir, "node.bin");
  if (fs.existsSync(cjs)) {
    if (fs.existsSync(nodeBin)) {
      return { done: true, detail: `bundle local: opencode.cjs + node.bin en ${ctx.paths.backendDir} (vía opencode.sh; --version no verificado)` };
    }
    return { done: false, detail: `opencode.cjs presente pero falta node.bin en ${ctx.paths.backendDir} (lo exige opencode.sh)` };
  }
  return { done: false, detail: `OpenCode no disponible: sin binario "opencode" en PATH (probados: ${cands.filter(Boolean).join(", ")}) ni bundle opencode.cjs+node.bin en ${ctx.paths.backendDir}` };
}

// ---------------------------------------------------------------------------
// antigravity — binario agy + auth (OAuth) + config Artemis
// ---------------------------------------------------------------------------
const AGY_AUTH_FILES = uniq([
  "/root/.gemini/antigravity-cli/antigravity-oauth-token",
  path.join(HOME, ".gemini", "antigravity-cli", "antigravity-oauth-token")
]);
const AGY_BRAIN_DIR = "/root/.gemini/antigravity-cli/brain";
const ARTEMIS_ENV_FILE = path.join(HOME, ".artemis", ".artemis_env");

async function antigravityCheck(ctx) {
  const cands = uniq([
    "/root/.local/bin/agy",          // binPath por defecto de AntigravityAdapter
    whichSync("agy", ctx.env),
    "/usr/local/bin/agy",
    "/usr/bin/agy"
  ]);
  let bin = null;
  let version = null;
  for (const c of cands) {
    if (!c || !fs.existsSync(c)) continue;
    const r = await ctx.execFile(c, ["--version"], { timeout: 8000 });
    const v = r.stdout.trim();
    if (r.code === 0 && v) { bin = c; version = v; break; }
  }

  // Auth: token OAuth de ~/.gemini/antigravity-cli con contenido real (>0 B)
  let authPath = null;
  let authSize = 0;
  for (const f of AGY_AUTH_FILES) {
    try {
      const st = fs.statSync(f);
      if (st.isFile() && st.size > 0) { authPath = f; authSize = st.size; break; }
    } catch { /* no existe */ }
  }

  const extras = [];
  if (fs.existsSync(ARTEMIS_ENV_FILE)) extras.push(`config Artemis en ${ARTEMIS_ENV_FILE}`);
  if (fs.existsSync(AGY_BRAIN_DIR)) extras.push(`brain en ${AGY_BRAIN_DIR}`);
  const tail = extras.length ? ` · ${extras.join(" · ")}` : "";

  if (bin && authPath) {
    return { done: true, detail: `agy ${version} en ${bin} + token OAuth (${authSize} B) en ${authPath}${tail}` };
  }
  if (!bin && authPath) {
    return { done: false, detail: `auth PARCIAL: token OAuth en ${authPath} pero falta/falla el binario agy (probados: ${cands.filter(Boolean).join(", ")})${tail}` };
  }
  if (bin && !authPath) {
    return { done: false, detail: `auth PARCIAL: agy ${version} en ${bin} pero SIN token OAuth en ~/.gemini/antigravity-cli/antigravity-oauth-token (login pendiente)` };
  }
  return { done: false, detail: `Antigravity/Artemis ausente: sin binario agy (${cands.filter(Boolean).join(", ")}) ni token OAuth en ~/.gemini/antigravity-cli` };
}

// ---------------------------------------------------------------------------
// skills — manifiesto (QUÉ debe instalar F2) contra SkillManager (disco real)
// ---------------------------------------------------------------------------
export function readSkillsManifest() {
  try {
    const raw = JSON.parse(fs.readFileSync(SKILLS_MANIFEST_FILE, "utf8"));
    if (Array.isArray(raw)) {
      return raw
        .map(s => (typeof s === "string" ? s : s && s.id))
        .filter(id => typeof id === "string" && id.length > 0);
    }
  } catch { /* manifiesto ausente/ilegible => fallback "no vacío" */ }
  return [];
}

async function skillsCheck() {
  let installed = [];
  let err = null;
  try { installed = new SkillManager().listInstalled() || []; }
  catch (e) { err = String((e && e.message) || e).slice(0, 160); }
  if (err) return { done: false, detail: `SkillManager ilegible: ${err}` };

  const manifest = readSkillsManifest();
  if (!manifest.length) {
    return installed.length > 0
      ? { done: true, detail: `skills instalados (sin manifiesto): ${installed.join(", ")}` }
      : { done: false, detail: "ningún skill instalado y sin manifiesto que comprobar" };
  }
  const missing = manifest.filter(id => !installed.includes(id));
  const detail = missing.length
    ? `faltan ${missing.length}/${manifest.length} skills del manifiesto: ${missing.join(", ")} (instalados: ${installed.join(", ") || "ninguno"})`
    : `skills del manifiesto instalados ${manifest.length}/${manifest.length}: ${manifest.join(", ")} (disco: ${installed.join(", ")})`;
  return { done: missing.length === 0, detail };
}

// ---------------------------------------------------------------------------
// Helpers F2 — manifiestos, progreso, cancelación, descarga verificada, LIFO
// ---------------------------------------------------------------------------
function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

function mb(bytes) { return `${(Number(bytes) / (1024 * 1024)).toFixed(1)}MB`; }

function readJsonManifest(file, label) {
  try { return JSON.parse(fs.readFileSync(file, "utf8")); }
  catch (e) { throw new Error(`manifiesto ${label} ilegible (${file}): ${String((e && e.message) || e).slice(0, 200)}`); }
}

// REGLA OBLIGATORIA del roadmap: sin 64 hex de sha256 NO se descarga nada.
function requireSha256(m, label) {
  const sha = m && typeof m.sha256 === "string" ? m.sha256.trim().toLowerCase() : "";
  if (!/^[0-9a-f]{64}$/.test(sha)) {
    throw new Error(`Falta SHA256 del manifiesto: no se descarga nada sin verificación (${label})`);
  }
  return sha;
}

function hostArch() {
  const a = os.arch();
  return a === "aarch64" ? "arm64" : a === "x86_64" ? "x64" : a;
}

function throwIfCanceled(ctx, where) {
  if (ctx.cancelRequested && ctx.cancelRequested()) {
    throw Object.assign(new Error(`cancelado por el usuario en ${where} — el paso vuelve a pending`), { code: "ECANCELED" });
  }
}

// dry: progreso RÁPIDO con "[DRY]", sin mutar, sin descargar, sin spawnear.
async function dryRun(ctx, stepId, ticks) {
  const list = Array.isArray(ticks) && ticks.length
    ? ticks
    : [`[DRY] ${stepId}: sin mutaciones ni descargas (motor de instalación F2)`];
  for (let i = 0; i < list.length; i++) {
    throwIfCanceled(ctx, `${stepId} (dry)`);
    ctx.progress(Math.round(((i + 1) / list.length) * 100), list[i]);
    await sleep(12);
  }
}

// onProgress(bytesRecibidos, totalBytes) → ctx.progress. Con content-length se
// da % real (techo 92% para dejar margen a extracción/validación); sin él,
// progreso indeterminado con los MB descargados.
function dlProgress(ctx, label) {
  return (bytes, total) => {
    if (Number.isFinite(total) && total > 0) {
      const pct = Math.max(1, Math.min(92, Math.round((bytes / total) * 90)));
      ctx.progress(pct, `descargando ${label}: ${mb(bytes)} / ${mb(total)}`);
    } else {
      ctx.progress(0, `descargando ${label}: ${mb(bytes)} descargados (total desconocido)`);
    }
  };
}

// downloadVerified — descarga en streaming con SHA256 calculado EN el stream.
//  - fetch + redirect follow; timeout duro 10 min (abort con motivo "timeout").
//  - SHA256 con crypto.createHash("sha256") por chunk → si no coincide con el
//    manifiesto: borra el tmp y lanza EBADCHECKSUM (nunca se usa el fichero).
//  - ctx.cancelRequested() comprobado por chunk → abort + tmp borrado +
//    ECANCELED (orchestrator lo traduce a pause y paso "pending").
//  - escribe a `dest.tmp.<pid>` y sólo tras verificar hace rename → `dest`.
async function downloadVerified(ctx, { url, sha256, dest, label, onProgress }) {
  const want = typeof sha256 === "string" ? sha256.trim().toLowerCase() : "";
  if (!/^[0-9a-f]{64}$/.test(want)) {
    throw Object.assign(
      new Error(`Falta SHA256 del manifiesto: no se descarga nada sin verificación (${label})`),
      { code: "EMISSINGSHA" }
    );
  }
  const tmp = `${dest}.tmp.${process.pid}`;
  const cleanup = () => { try { fs.rmSync(tmp, { force: true }); } catch { /* ya borrado */ } };
  const ac = new AbortController();
  let timedOut = false;
  const timer = setTimeout(() => { timedOut = true; ac.abort(); }, DOWNLOAD_TIMEOUT_MS);
  const hash = crypto.createHash("sha256");
  let received = 0;
  let lastAt = 0;
  let lastBytes = 0;
  let fd = null;
  try {
    fs.mkdirSync(path.dirname(dest), { recursive: true });
    const res = await fetch(url, { redirect: "follow", signal: ac.signal });
    if (!res.ok) throw Object.assign(new Error(`HTTP ${res.status} descargando ${label}`), { code: "EHTTP" });
    const cl = Number(res.headers.get("content-length"));
    const total = Number.isFinite(cl) && cl > 0 ? cl : null;
    if (!res.body) throw Object.assign(new Error(`respuesta sin cuerpo: ${url}`), { code: "EHTTPBODY" });
    fd = fs.openSync(tmp, "w");
    for await (const chunk of res.body) {
      if (ctx.cancelRequested && ctx.cancelRequested()) {
        throw Object.assign(new Error(`descarga cancelada por el usuario: ${label}`), { code: "ECANCELED" });
      }
      hash.update(chunk);
      fs.writeSync(fd, chunk);
      received += chunk.length;
      const now = Date.now();
      if (now - lastAt >= 300 || received - lastBytes >= 512 * 1024) {
        lastAt = now;
        lastBytes = received;
        if (typeof onProgress === "function") onProgress(received, total);
      }
    }
    if (fd !== null) { fs.closeSync(fd); fd = null; }
    const got = hash.digest("hex");
    if (got !== want) {
      cleanup();
      throw Object.assign(
        new Error(`EBADCHECKSUM: SHA256 de ${label} no coincide (esperado ${want.slice(0, 12)}…, obtenido ${got.slice(0, 12)}…)`),
        { code: "EBADCHECKSUM" }
      );
    }
    fs.renameSync(tmp, dest);
    if (typeof onProgress === "function") onProgress(received, total ?? received);
    return { path: dest, bytes: received, sha256: got };
  } catch (e) {
    if (fd !== null) { try { fs.closeSync(fd); } catch { /* fd ya cerrado */ } }
    cleanup();
    const aborted = e && (e.name === "AbortError" || e.code === "ABORT_ERR" || /abort/i.test(String(e.message || "")));
    if (timedOut && aborted) {
      throw Object.assign(new Error(`descarga expirada (>=10 min): ${label}`), { code: "ETIMEDOUT" });
    }
    throw e;
  } finally {
    clearTimeout(timer);
  }
}

// dir escribible: lo crea si falta (registrando SU reversa — sólo si lo creó
// este run). Devuelve false si existe y no es escribible (sin pisar nada).
function ensureWritableDir(ctx, dir) {
  if (fs.existsSync(dir)) {
    try { fs.accessSync(dir, fs.constants.W_OK); return true; } catch { return false; }
  }
  try {
    fs.mkdirSync(dir, { recursive: true });
    ctx.recordArtifact({ type: "dir", path: dir });
    ctx.onRollback(() => { try { fs.rmSync(dir, { recursive: true, force: true }); } catch { /* ajeno ya */ } });
    return true;
  } catch { return false; }
}

// Rootfs Ubuntu/Debian usable (AEGIS_UBUNTU_DIR o candidatas habituales)
function detectRootfs(ctx) {
  const cands = uniq([
    ctx && ctx.env && ctx.env.AEGIS_UBUNTU_DIR ? path.resolve(ctx.env.AEGIS_UBUNTU_DIR) : null,
    path.resolve(HOME, ".aegis", "ubuntu"),
    ...UBUNTU_DIRS
  ]);
  for (const d of cands) {
    if (!d || !fs.existsSync(d)) continue;
    const rel = readOsRelease(path.join(d, "etc", "os-release"));
    if (isUbuntuFamily(rel)) return d;
  }
  return null;
}

// Ejecuta en LIFO las reversas registradas por EL run fallido. Best-effort:
// una acción que lanza NO corta las demás; si alguna falla => "failed".
// Devuelve "none" (nada que revertir) | "done" | "failed".
export async function drainRollbacks(ctx) {
  const fns = ctx && Array.isArray(ctx.rollbackFns) ? ctx.rollbackFns.splice(0, ctx.rollbackFns.length) : [];
  if (ctx && Array.isArray(ctx.artifacts)) ctx.artifacts.splice(0, ctx.artifacts.length);
  if (!fns.length) return "none";
  let failures = 0;
  for (let i = fns.length - 1; i >= 0; i--) {
    try {
      await fns[i](ctx);
    } catch (e) {
      failures++;
      const m = String((e && e.message) || e).slice(0, 160);
      try { if (ctx && ctx.log) ctx.log.error(`[bootstrap] acción de reversa falló: ${m}`); } catch { /* logger opcional */ }
    }
  }
  return failures ? "failed" : "done";
}

// ---------------------------------------------------------------------------
// ubuntu — run REAL: descarga ubuntu-base (SHA256 obligatorio) + extracción
// ---------------------------------------------------------------------------
async function ubuntuRun(ctx) {
  if (ctx.dry) {
    await dryRun(ctx, "ubuntu", [
      "[DRY] ubuntu: sin descargas — se simularía downloadVerified(ubuntu-base…tar.gz) con SHA256 verificado",
      "[DRY] ubuntu: sin extracción — tar -xzf <tmp> -C <target>",
      "[DRY] ubuntu: sin validación real de <target>/etc/os-release"
    ]);
    return;
  }

  // (0) ¿ya hay un rootfs usable? (defensa ante check forzado — reutiliza, nunca re-descarga)
  const anchor = findUbuntuAnchor();
  if (anchor) {
    const rel = readOsRelease(`/proc/${anchor}/root/etc/os-release`);
    if (isUbuntuFamily(rel)) {
      ctx.progress(100, `rootfs existente reutilizado vía ancla pid ${anchor}: ${rel.pretty || rel.id} (find-ubuntu.sh) — sin descargas`);
      return;
    }
  }

  // (1) manifiesto + SHA256 OBLIGATORIO (regla del roadmap) + arquitectura
  const m = readJsonManifest(UBUNTU_MANIFEST_FILE, "ubuntu");
  const sha = requireSha256(m, "ubuntu-manifest.json");
  if (!m.url || typeof m.url !== "string") throw new Error("manifiesto ubuntu sin `url` — no se descarga nada");
  const arch = hostArch();
  if (m.arch && m.arch !== arch) {
    throw new Error(`manifiesto ubuntu apunta a ${m.arch} pero el host es ${arch} — ajusta ubuntu-manifest.json`);
  }
  if (!whichSync("tar", ctx.env)) {
    throw new Error("sin `tar` disponible: no se puede extraer el rootfs (instala tar o provee el rootfs ya extraído)");
  }
  throwIfCanceled(ctx, "ubuntu: manifiesto");

  // (2) modo de aislamiento — chroot/unshare preferidos; proot como fallback
  const chrootBin = whichSync("chroot", ctx.env);
  const unshareBin = whichSync("unshare", ctx.env);
  const prootBin = whichSync("proot", ctx.env);
  const mode = chrootBin ? "chroot" : unshareBin ? "unshare+chroot" : prootBin ? "proot" : null;

  // (3) target: AEGIS_UBUNTU_DIR o <manifest.target> bajo $HOME. Sólo se puebla
  // un directorio inexistente o VACÍO; un parcial ajeno jamás se toca.
  const relTarget = typeof m.target === "string" && m.target ? m.target : ".aegis/ubuntu";
  const target = ctx.env && ctx.env.AEGIS_UBUNTU_DIR
    ? path.resolve(ctx.env.AEGIS_UBUNTU_DIR)
    : (path.isAbsolute(relTarget) ? relTarget : path.resolve(HOME, relTarget));
  const osRelPath = path.join(target, "etc", "os-release");
  if (fs.existsSync(target)) {
    const entries = fs.readdirSync(target);
    if (fs.existsSync(osRelPath)) {
      const rel = readOsRelease(osRelPath);
      if (isUbuntuFamily(rel)) {
        ctx.progress(100, `rootfs ya instalado en ${target}: ${rel.pretty || rel.id} (ID=${rel.id}) — sin descargas`);
        return;
      }
      throw new Error(`rootfs existente INVÁLIDO en ${target} (ID=${rel ? rel.id : "?"}) — no se toca nada ajeno: elige otro AEGIS_UBUNTU_DIR o elimínalo a mano`);
    }
    if (entries.length > 0) {
      throw new Error(`directorio parcial preexistente no vacío en ${target} (sin etc/os-release) — este run no lo creó, no se limpia: vacíalo a mano o define AEGIS_UBUNTU_DIR`);
    }
    // Existía VACÍO: este run lo puebla ⇒ rollback vaciará sólo su contenido.
    ctx.recordArtifact({ type: "dir-content", path: target });
    ctx.onRollback(() => {
      try { for (const e of fs.readdirSync(target)) fs.rmSync(path.join(target, e), { recursive: true, force: true }); } catch { /* ilegible */ }
    });
  } else {
    fs.mkdirSync(target, { recursive: true });
    ctx.recordArtifact({ type: "dir", path: target });
    ctx.onRollback(() => { try { fs.rmSync(target, { recursive: true, force: true }); } catch { /* ya borrado */ } });
  }
  throwIfCanceled(ctx, "ubuntu: directorio target");

  // (4) descarga con SHA256 verificado en streaming (tmp → rename tras verificar)
  const fileName = typeof m.fileName === "string" && m.fileName
    ? m.fileName
    : path.basename(String(m.url).split("?")[0]);
  const dl = path.join(os.tmpdir(), "aegis-bootstrap", fileName);
  ctx.onRollback(() => {
    try { fs.rmSync(dl, { force: true }); fs.rmSync(`${dl}.tmp.${process.pid}`, { force: true }); } catch { /* ya borrado */ }
  });
  ctx.progress(3, `descargando ${fileName} de ${m.url} (SHA256 verificado obligatorio)…`);
  await downloadVerified(ctx, { url: m.url, sha256: sha, dest: dl, label: `ubuntu (${fileName})`, onProgress: dlProgress(ctx, fileName) });
  throwIfCanceled(ctx, "ubuntu: post-descarga");

  // (5) extracción con argv (sin shell) + timeout razonable
  ctx.progress(93, `extrayendo ${fileName} en ${target}…`);
  const untar = await ctx.execFile("tar", ["-xzf", dl, "-C", target], { timeout: CMD_TIMEOUT_MS });
  if (untar.code !== 0) {
    throw new Error(`tar falló extrayendo el rootfs (code ${untar.code}): ${(untar.stderr || untar.error || "").slice(0, 300)}`);
  }

  // (6) validar etc/os-release del rootfs extraído
  const after = readOsRelease(osRelPath);
  if (!isUbuntuFamily(after)) {
    throw new Error(`rootfs inválido tras extraer: falta ${osRelPath} con ID ubuntu/debian (ID=${after ? after.id : "ninguno"})`);
  }

  // (7) detail: versión + modo de aislamiento (fallback proot documentado)
  const notes = [];
  if (!mode) {
    notes.push(`SIN chroot/unshare/proot: instala proot (p.ej. \`pkg install proot\`) y opera el rootfs con \`proot -r ${target} -0 -b /dev -b /proc /bin/bash\``);
  } else if (mode === "proot") {
    notes.push(`chroot/unshare ausentes → vía proot: \`${prootBin} -r ${target} -0 -b /dev -b /proc /bin/bash\` (pasos posteriores anteponer proot)`);
  } else {
    notes.push(`modo de aislamiento disponible: ${mode} (${chrootBin || unshareBin}) — proot no hace falta (proot: ${prootBin || "no instalado"})`);
  }
  ctx.progress(100, `rootfs ${after.pretty || after.id} instalado en ${target} · ${notes.join(" · ")}`);
}

// ---------------------------------------------------------------------------
// node — run REAL: reutiliza backend/node.bin (stage-node.sh) o instala el
// tarball del manifiesto (SHA256) y lo enlaza en PATH
// ---------------------------------------------------------------------------
async function nodeRun(ctx) {
  if (ctx.dry) {
    await dryRun(ctx, "node", [
      "[DRY] node: sin descargas — se simularía downloadVerified(node-v24…tar.gz) con SHA256 verificado",
      "[DRY] node: sin extracción — tar -xzf <tmp> -C <dest> --strip-components=1",
      "[DRY] node: sin symlink ni validación real de `node -v`"
    ]);
    return;
  }
  throwIfCanceled(ctx, "node: inicio");

  // (a) bundle staged por stage-node.sh — si responde -v, SIN red
  const bundle = path.join(ctx.paths.backendDir, "node.bin");
  let srcNode = null;
  if (fs.existsSync(bundle)) {
    const r = await ctx.execFile(bundle, ["-v"], { timeout: 6000 });
    if (r.code === 0 && /^v\d+/.test(r.stdout.trim())) srcNode = bundle;
  }

  let extractBase = null;
  if (!srcNode) {
    const m = readJsonManifest(NODE_MANIFEST_FILE, "node");
    const sha = requireSha256(m, "node-manifest.json");
    if (!m.url || typeof m.url !== "string") throw new Error("manifiesto node sin `url` — no se descarga nada");
    const arch = hostArch();
    if (m.arch && m.arch !== arch) {
      throw new Error(`manifiesto node apunta a ${m.arch} pero el host es ${arch} — ajusta node-manifest.json`);
    }
    if (!whichSync("tar", ctx.env)) throw new Error("sin `tar` disponible: no se puede extraer el tarball de node");

    const rootfs = detectRootfs(ctx);
    const relTarget = typeof m.target === "string" && m.target ? m.target : ".aegis/node";
    const dest = ctx.env && ctx.env.AEGIS_NODE_DIR
      ? path.resolve(ctx.env.AEGIS_NODE_DIR)
      : path.resolve(HOME, relTarget);
    // Con rootfs: extraer en <rootfs>/usr/local (queda en <rootfs>/usr/local/bin/node,
    // FHS correcto y no pisa /bin ni /usr/bin del rootfs).
    extractBase = rootfs ? path.join(rootfs, "usr", "local") : dest;

    // ¿Ya instalado en una pasada anterior? (reanudación idempotente)
    const pre = path.join(extractBase, "bin", "node");
    if (fs.existsSync(pre)) {
      const r = await ctx.execFile(pre, ["-v"], { timeout: 6000 });
      if (r.code === 0 && /^v\d+/.test(r.stdout.trim())) srcNode = pre;
    }

    if (!srcNode) {
      if (!fs.existsSync(extractBase)) {
        fs.mkdirSync(extractBase, { recursive: true });
        ctx.recordArtifact({ type: "dir", path: extractBase });
        ctx.onRollback(() => { try { fs.rmSync(extractBase, { recursive: true, force: true }); } catch { /* ya borrado */ } });
      } else {
        let entries = [];
        try { entries = fs.readdirSync(extractBase); } catch { /* ilegible */ }
        if (entries.length === 0) {
          ctx.onRollback(() => {
            try { for (const e of fs.readdirSync(extractBase)) fs.rmSync(path.join(extractBase, e), { recursive: true, force: true }); } catch { /* ilegible */ }
          });
        } else {
          throw new Error(`directorio de instalación no vacío sin binario usable en ${extractBase} — este run no lo creó, no se pisa: define AEGIS_NODE_DIR`);
        }
      }
      throwIfCanceled(ctx, "node: directorio de instalación");

      const fileName = typeof m.fileName === "string" && m.fileName
        ? m.fileName
        : path.basename(String(m.url).split("?")[0]);
      const dl = path.join(os.tmpdir(), "aegis-bootstrap", fileName);
      ctx.onRollback(() => {
        try { fs.rmSync(dl, { force: true }); fs.rmSync(`${dl}.tmp.${process.pid}`, { force: true }); } catch { /* ya borrado */ }
      });
      ctx.progress(3, `descargando ${fileName} de ${m.url} (SHA256 verificado obligatorio)…`);
      await downloadVerified(ctx, { url: m.url, sha256: sha, dest: dl, label: `node (${fileName})`, onProgress: dlProgress(ctx, fileName) });
      throwIfCanceled(ctx, "node: post-descarga");

      ctx.progress(93, `extrayendo ${fileName} en ${extractBase}…`);
      const untar = await ctx.execFile("tar", ["-xzf", dl, "-C", extractBase, "--strip-components=1"], { timeout: CMD_TIMEOUT_MS });
      if (untar.code !== 0) {
        throw new Error(`tar falló extrayendo node (code ${untar.code}): ${(untar.stderr || untar.error || "").slice(0, 300)}`);
      }
      srcNode = path.join(extractBase, "bin", "node");
    }
  }

  // (b) validar `node -v`
  const ver = await ctx.execFile(srcNode, ["-v"], { timeout: 8000 });
  if (ver.code !== 0 || !/^v\d+/.test(ver.stdout.trim())) {
    throw new Error(`node instalado no ejecutable en ${srcNode} (code ${ver.code}): ${(ver.stderr || ver.error || "").slice(0, 200)}`);
  }
  throwIfCanceled(ctx, "node: validación");

  // (c) enlazar en un dir PATH-visible — un node ajeno operativo JAMÁS se toca
  let linked = null;
  for (const dir of uniq(["/usr/local/bin", path.join(HOME, ".local", "bin")])) {
    const lp = path.join(dir, "node");
    if (lp === srcNode) { linked = lp; break; }
    if (fs.existsSync(lp)) {
      const r = await ctx.execFile(lp, ["-v"], { timeout: 6000 });
      if (r.code === 0 && /^v\d+/.test(r.stdout.trim())) { linked = lp; break; } // operativo: no se pisa
      continue; // existente y roto: no se sobrescribe (se prueba el siguiente dir)
    }
    if (!ensureWritableDir(ctx, dir)) continue;
    try {
      fs.symlinkSync(srcNode, lp);
    } catch {
      try { fs.copyFileSync(srcNode, lp); fs.chmodSync(lp, 0o755); } catch { continue; }
    }
    ctx.recordArtifact({ type: "link", path: lp });
    ctx.onRollback(() => { try { fs.rmSync(lp, { force: true }); } catch { /* ya borrado */ } });
    linked = lp;
    break;
  }

  ctx.progress(100,
    `Node ${ver.stdout.trim()} operativo en ${srcNode}` +
    (linked && linked !== srcNode ? ` (enlace en ${linked})` : "") +
    (extractBase ? ` · instalado en ${extractBase}` : " · bundle stage-node.sh reutilizado (node.bin)")
  );
}

// ---------------------------------------------------------------------------
// opencode — run REAL: wrapper sobre el bundle (opencode.cjs + node) o
// npm install -g opencode-ai; valida `opencode --version`
// ---------------------------------------------------------------------------
async function opencodeRun(ctx) {
  if (ctx.dry) {
    await dryRun(ctx, "opencode", [
      "[DRY] opencode: sin escrituras — se crearía wrapper 0755 sobre backend/opencode.cjs + node.bin",
      "[DRY] opencode: alternativa npm install -g opencode-ai NO ejecutada",
      "[DRY] opencode: sin validación real de `opencode --version`"
    ]);
    return;
  }
  throwIfCanceled(ctx, "opencode: inicio");
  const cjs = path.join(ctx.paths.backendDir, "opencode.cjs");
  const nodeBundle = path.join(ctx.paths.backendDir, "node.bin");

  // Ruta A — wrapper sobre el bundle existente (el bundle original JAMÁS se
  // copia, muta ni borra; se replica exactamente el recipe de opencode.sh).
  if (fs.existsSync(cjs)) {
    const nodeSrc = (fs.existsSync(nodeBundle) ? nodeBundle : null) || whichSync("node", ctx.env) || process.execPath;
    const nv = await ctx.execFile(nodeSrc, ["-v"], { timeout: 6000 });
    if (nv.code === 0) {
      for (const dir of uniq(["/usr/local/bin", path.join(HOME, ".local", "bin")])) {
        const wrapper = path.join(dir, "opencode");
        if (fs.existsSync(wrapper)) {
          const vr = await ctx.execFile(wrapper, ["--version"], { timeout: 20000 });
          if (vr.code === 0 && vr.stdout.trim()) {
            ctx.progress(100, `OpenCode ${vr.stdout.trim()} ya operativo en ${wrapper}`);
            return;
          }
          continue; // existente y roto: NO se pisa (se prueba el siguiente dir)
        }
        if (!ensureWritableDir(ctx, dir)) continue;
        fs.writeFileSync(wrapper, `#!/bin/sh\nexec "${nodeSrc}" "${cjs}" "$@"\n`, { mode: 0o755 });
        fs.chmodSync(wrapper, 0o755);
        ctx.recordArtifact({ type: "wrapper", path: wrapper });
        // Rollback: SOLO el wrapper creado — jamás backend/opencode.cjs ni node.bin.
        ctx.onRollback(() => { try { fs.rmSync(wrapper, { force: true }); } catch { /* ya borrado */ } });
        const vr = await ctx.execFile(wrapper, ["--version"], { timeout: 20000 });
        if (vr.code === 0 && vr.stdout.trim()) {
          ctx.progress(100, `OpenCode ${vr.stdout.trim()} vía wrapper ${wrapper} → ${cjs}`);
          return;
        }
        throw new Error(`wrapper creado en ${wrapper} pero \`opencode --version\` falló (code ${vr.code}): ${(vr.stderr || vr.error || "").slice(0, 200)}`);
      }
    }
  }

  // Ruta B — paquete npm oficial con id FIJO (argv, sin shell)
  const npm = whichSync("npm", ctx.env);
  if (!npm) {
    throw new Error(`sin bundle opencode.cjs usable ni npm en PATH: no hay cómo instalar OpenCode — instala el bundle (opencode.cjs + node.bin) o npm`);
  }
  ctx.progress(25, "npm install -g opencode-ai (paquete npm oficial de OpenCode)…");
  const inst = await ctx.execFile(npm, ["install", "-g", "opencode-ai"], { timeout: DOWNLOAD_TIMEOUT_MS });
  if (inst.code !== 0) {
    throw new Error(`npm install -g opencode-ai falló (code ${inst.code}): ${(inst.stderr || inst.stdout || inst.error || "").slice(0, 300)}`);
  }
  ctx.recordArtifact({ type: "npm-g", name: "opencode-ai" });
  // Rollback: desinstalar SÓLO si lo instaló ESTE run por npm.
  ctx.onRollback(async () => {
    const un = await ctx.execFile(npm, ["uninstall", "-g", "opencode-ai"], { timeout: CMD_TIMEOUT_MS });
    if (un.code !== 0) throw new Error(`npm uninstall -g opencode-ai code ${un.code}: ${(un.stderr || un.error || "").slice(0, 160)}`);
  });
  throwIfCanceled(ctx, "opencode: post-npm");

  const bin = whichSync("opencode", ctx.env) || "/usr/local/bin/opencode";
  const vr = await ctx.execFile(bin, ["--version"], { timeout: 20000 });
  if (vr.code !== 0 || !vr.stdout.trim()) {
    throw new Error(`\`opencode --version\` falló tras npm install (code ${vr.code}): ${(vr.stderr || vr.error || "").slice(0, 200)}`);
  }
  ctx.progress(100, `OpenCode ${vr.stdout.trim()} instalado globalmente (opencode-ai vía ${bin})`);
}

// ---------------------------------------------------------------------------
// antigravity — run REAL: asegura binario agy + config dir, DESPUÉS exige auth
// ---------------------------------------------------------------------------
async function antigravityRun(ctx) {
  if (ctx.dry) {
    await dryRun(ctx, "antigravity", [
      "[DRY] antigravity: sin creación de ~/.gemini/antigravity-cli",
      "[DRY] antigravity: sin instalación de agy ni verificación de token (el contenido del token NUNCA se lee)",
      "[DRY] antigravity: auth OAuth no requerida en modo simulado"
    ]);
    return;
  }
  throwIfCanceled(ctx, "antigravity: inicio");

  // (1) binario agy — candidatas reales (AntigravityAdapter + antigravityCheck)
  const cands = uniq([
    "/root/.local/bin/agy",
    whichSync("agy", ctx.env),
    "/usr/local/bin/agy",
    "/usr/bin/agy"
  ]);
  let bin = null;
  let ver = null;
  for (const c of cands) {
    if (!c || !fs.existsSync(c)) continue;
    const r = await ctx.execFile(c, ["--version"], { timeout: 8000 });
    if (r.code === 0 && r.stdout.trim()) { bin = c; ver = r.stdout.trim(); break; }
  }
  if (!bin) {
    // INVESTIGADO (F2): NO existe paquete npm oficial que provea `agy`
    // (npm view de "agy"/"antigravity-cli"/"antigravity" => placeholders de
    // terceros, NO el CLI de Google). Distribución oficial: install.sh sin
    // SHA256 publicado verificable ⇒ este motor NO descarga nada: error honesto.
    throw new Error(
      `binario agy ausente (${cands.filter(Boolean).join(", ")}): no hay paquete npm oficial que lo provea ` +
      `(los paquetes npm "agy"/"antigravity-cli" son placeholders de terceros — NO instalarlos). ` +
      `Instálalo con el instalador oficial: curl -fsSL https://antigravity.google/cli/install.sh | bash ` +
      `(docs: https://antigravity.google/docs/cli/install) y reintenta el paso`
    );
  }

  // (2) dir de config con estructura mínima — SOLO si falta (si existe, intocado)
  const cfgDir = path.join(HOME, ".gemini", "antigravity-cli");
  if (!fs.existsSync(cfgDir)) {
    fs.mkdirSync(path.join(cfgDir, "brain"), { recursive: true });
    ctx.recordArtifact({ type: "dir", path: cfgDir });
    // Seguro: si el dir no existía, TAMPOCO existe ningún token dentro ⇒
    // borrarlo no puede tocar credenciales preexistentes.
    ctx.onRollback(() => { try { fs.rmSync(cfgDir, { recursive: true, force: true }); } catch { /* ya borrado */ } });
  }
  throwIfCanceled(ctx, "antigravity: config dir");

  // (3) auth: token OAuth con contenido >0 B. Su contenido NUNCA se imprime,
  // copia ni registra — sólo ruta y tamaño.
  let authPath = null;
  let authSize = 0;
  for (const f of AGY_AUTH_FILES) {
    try {
      const st = fs.statSync(f);
      if (st.isFile() && st.size > 0) { authPath = f; authSize = st.size; break; }
    } catch { /* no existe */ }
  }
  if (!authPath) {
    throw new Error(
      "Autenticación de Antigravity/Artemis requerida: completa el login y reintenta el paso " +
      "(token OAuth ausente o vacío en ~/.gemini/antigravity-cli/antigravity-oauth-token — " +
      "ejecuta `agy` y completa el login, o `python -m artemis auth login`)"
    );
  }

  const extras = [];
  if (fs.existsSync(ARTEMIS_ENV_FILE)) extras.push("config Artemis presente");
  if (fs.existsSync(AGY_BRAIN_DIR)) extras.push("brain/ presente");
  ctx.progress(100, `agy ${ver} en ${bin} + token OAuth (${authSize} B) en ${authPath}${extras.length ? " · " + extras.join(" · ") : ""}`);
}

// ---------------------------------------------------------------------------
// skills — run REAL: SkillManager.install de lo que falte (progreso i/n)
// ---------------------------------------------------------------------------
async function skillsRun(ctx) {
  const manifest = readSkillsManifest();
  if (ctx.dry) {
    let installed = [];
    try { installed = new SkillManager().listInstalled() || []; } catch { /* detalle no crítico en dry */ }
    const missing = manifest.filter(id => !installed.includes(id));
    await dryRun(ctx, "skills", [missing.length
      ? `[DRY] skills: instalaría ${missing.length}/${manifest.length} del manifiesto (sin npm): ${missing.join(", ")}`
      : `[DRY] skills: nada que instalar (${manifest.length}/${manifest.length} ya presentes)`]);
    return;
  }
  throwIfCanceled(ctx, "skills: inicio");

  const sm = new SkillManager();
  let installed = [];
  try { installed = sm.listInstalled() || []; }
  catch (e) { throw new Error(`SkillManager ilegible: ${String((e && e.message) || e).slice(0, 160)}`); }
  const missing = manifest.filter(id => !installed.includes(id));
  if (!missing.length) {
    ctx.progress(100, `skills del manifiesto ya instalados (${manifest.join(", ") || "manifiesto vacío"})`);
    return;
  }

  // Rollback: desinstalar SOLO los que ESTE run instale (se rellena a medida
  // que cada install valida — LIFO dentro de drainRollbacks).
  const installedThisRun = [];
  ctx.onRollback(async () => {
    const errs = [];
    for (let i = installedThisRun.length - 1; i >= 0; i--) {
      try { await sm.uninstall(installedThisRun[i]); }
      catch (e) { errs.push(`${installedThisRun[i]}: ${String((e && e.message) || e).slice(0, 80)}`); }
    }
    if (errs.length) throw new Error(`no se pudieron desinstalar (${errs.length}): ${errs.join(" · ")}`);
  });

  for (let i = 0; i < missing.length; i++) {
    throwIfCanceled(ctx, `skills (${i}/${missing.length})`);
    const id = missing[i];
    ctx.progress(Math.round((i / missing.length) * 100), `instalando skill "${id}" (${i + 1}/${missing.length})…`);
    let tail = "";
    try {
      const job = sm.install(id); // {stdout, stderr, promise} — id validado en A-1
      if (job.stdout) job.stdout.on("data", d => { tail = (tail + String(d)).slice(-400); });
      if (job.stderr) job.stderr.on("data", d => { tail = (tail + String(d)).slice(-400); });
      await job.promise;
    } catch (e) {
      const why = String((e && e.message) || e).slice(0, 160);
      const out = tail.trim().slice(-200);
      throw new Error(`skill "${id}" no instaló: ${why}${out ? ` — ${out}` : ""}`);
    }
    installedThisRun.push(id);
    ctx.recordArtifact({ type: "skill", id });
  }
  ctx.progress(100, `${missing.length}/${manifest.length} skill(s) instalado(s): ${missing.join(", ")}`);
}

// ---------------------------------------------------------------------------
// Hooks: manifiesto + fail-injection de test + registro por paso
// ---------------------------------------------------------------------------
const CHECKS = {
  preflight: preflightCheck,
  ubuntu: ubuntuCheck,
  node: nodeCheck,
  opencode: opencodeCheck,
  antigravity: antigravityCheck,
  skills: skillsCheck
};

const RUNS = {
  preflight: preflightRun, // F1 (check duro real) + dry de F2
  ubuntu: ubuntuRun,
  node: nodeRun,
  opencode: opencodeRun,
  antigravity: antigravityRun,
  skills: skillsRun
};

// Rollback por paso (F2): drenan en LIFO las acciones que EL run fallido
// registró con ctx.onRollback(). preflight no muta ⇒ "none" puro.
//  - ubuntu    → tmp descargado + target sólo si lo creó este run
//  - node      → tmp + dir de instalación + enlace creado (nunca un node ajeno)
//  - opencode  → wrapper creado, o `npm uninstall -g opencode-ai` si lo instaló npm
//  - antigravity → el dir ~/.gemini/antigravity-cli sólo si LO CREÓ este run;
//                   JAMÁS el binario preexistente ni NINGÚN token
//  - skills    → `SkillManager.uninstall` SOLO de los skills instalados en este run
const ROLLBACKS = {
  preflight: async () => "none",
  ubuntu: drainRollbacks,
  node: drainRollbacks,
  opencode: drainRollbacks,
  antigravity: drainRollbacks,
  skills: drainRollbacks
};

// Paso fake SOLO para tests (AEGIS_BOOTSTRAP_TEST_STEP, añadido al FINAL de
// STEP_DEFS en state.js): su run crea AEGIS_BOOTSTRAP_TEST_ARTIFACT y lanza
// FAIL_INJECTED ⇒ permite assertar rollback REAL sin red ni instalaciones.
if (TEST_STEP_ID) {
  CHECKS[TEST_STEP_ID] = async () => ({ done: false, detail: "paso fake de test: siempre ejecuta run()" });
  RUNS[TEST_STEP_ID] = async (ctx) => {
    const file = (ctx.env && ctx.env.AEGIS_BOOTSTRAP_TEST_ARTIFACT) || path.join(os.tmpdir(), "aegis-bootstrap-test-artifact");
    fs.writeFileSync(file, `artifact de test creado ${new Date().toISOString()} pid=${process.pid}\n`);
    ctx.recordArtifact({ type: "file", path: file });
    ctx.onRollback(() => { try { fs.rmSync(file, { force: true }); } catch { /* ya borrado */ } });
    ctx.progress(50, `artifact creado en ${file} — lanzando fallo`);
    throw new Error("FAIL_INJECTED");
  };
  ROLLBACKS[TEST_STEP_ID] = drainRollbacks;
}

function failInjected(ctx, stepId) {
  return !!(ctx && ctx.env && ctx.env[FAIL_ENV] === stepId);
}

// HOOK DE TEST documentado (cabecera): AEGIS_BOOTSTRAP_FAIL=<stepId> fuerza
// check→done:false (para que el orquestador llame a run) y run→FAIL_INJECTED.
function wrapCheck(stepId, fn) {
  return async (ctx) => {
    if (failInjected(ctx, stepId)) {
      return { done: false, detail: `[TEST] AEGIS_BOOTSTRAP_FAIL=${stepId}: comprobación saltada para inyectar FAIL_INJECTED en run()` };
    }
    return fn(ctx);
  };
}

function wrapRun(stepId, fn) {
  return async (ctx) => {
    if (failInjected(ctx, stepId)) {
      ctx.progress(5, `[TEST] inyectando FAIL_INJECTED en "${stepId}" (${FAIL_ENV} — hook de test)`);
      throw new Error("FAIL_INJECTED");
    }
    return fn(ctx);
  };
}

// Orden y títulos EXACTOS del contrato, heredados de STEP_DEFS (state.js)
export const STEPS = STEP_DEFS.map(def => ({
  id: def.id,
  title: def.title,
  check: wrapCheck(def.id, CHECKS[def.id] || (async () => ({ done: false, detail: `sin comprobación registrada para "${def.id}"` }))),
  run: wrapRun(def.id, RUNS[def.id] || (async () => { throw new Error(`ERR_NOT_IMPLEMENTED: sin run registrado para "${def.id}"`); })),
  rollback: ROLLBACKS[def.id] || drainRollbacks
}));
