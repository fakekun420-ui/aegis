#!/usr/bin/env node
// opencode-companion hub — proxy opencode + device bridge + TTS/STT host
// Node 18+ only builtins. No npm deps.
// Listens 0.0.0.0:8765 -> serves public/ + /opencode/* proxy + /api/device/*
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { exec, spawn } from "node:child_process";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// resiliencia: no morir por crash del proxy, pero sí permitir reinicio limpio por keepalive (pkill -f) o por kill -15
process.on('uncaughtException', e => console.error('[hub] uncaughtException', e?.stack || e));
process.on('unhandledRejection', e => console.error('[hub] unhandledRejection', e?.stack || e));
process.on('SIGTERM', () => { console.log('[hub] SIGTERM — cierre limpio (keepalive relanza)'); try { server.close(() => process.exit(0)); } catch (_) { process.exit(0); } setTimeout(()=> process.exit(0), 2000); });
process.on('SIGINT',  () => { console.log('[hub] SIGINT — cierre');  try { server.close(() => process.exit(0)); } catch (_) { process.exit(0); } setTimeout(()=> process.exit(0), 2000); });
process.on('SIGPIPE', () => console.log('[hub] SIGPIPE ignorado'));

function argVal(name, fallback){
  const i = process.argv.indexOf(name);
  return i !== -1 && process.argv[i+1] ? process.argv[i+1] : fallback;
}
const HUB_PORT = parseInt(process.env.HUB_PORT || argVal("--port","8765"), 10);
const OPENCODE_PORT = parseInt(process.env.OPENCODE_PORT || argVal("--opencode-port","4096"), 10);
const OPENCODE_HOST = process.env.OPENCODE_HOST || "127.0.0.1";
const PROJECTS_ROOT = "/sdcard/projects";
const UI_STATE_FILE = path.join(__dirname, "ui-state.json");
const SKILLS_ROOT = path.join(__dirname, "skills");
const SUMMARIES_DIR = path.join(__dirname, "summaries");
const UI_STATE = (() => {
  try { if (fs.existsSync(UI_STATE_FILE)) return JSON.parse(fs.readFileSync(UI_STATE_FILE, "utf8")); } catch {}
  // New fields: projectId (companion managed id), sessionId; legacy `project` (folder name) kept for compat
  const base = { project: null, projectId: null, sessionId: null, updatedAt: 0 };
  try {
    const raw = fs.existsSync(UI_STATE_FILE) ? JSON.parse(fs.readFileSync(UI_STATE_FILE, "utf8")) : {};
    return { ...base, ...raw };
  } catch { return base; }
})();
function saveUiState() {
  try { fs.writeFileSync(UI_STATE_FILE, JSON.stringify({ ...UI_STATE, updatedAt: Date.now() }, null, 2)); } catch (e) { console.error("[ui-state] save err", e.message); }
}

// ---- Companion Project Management — persistent store projects.json ----
// Schema per spec (1): { id, name, description, createdAt, archivedAt, sessions:[{sessionId,title,createdAt,lastUsed,summary}], skills:[], linkedProjects:[] }
// Stored at /sdcard/projects/opencode-companion/projects.json ; soft delete via archivedAt timestamp.
// Envelope: all /api/projects routes return {ok:true,data:...} or {ok:false,error:...} (spec 6).
const PROJECTS_STORE_FILE = path.join(__dirname, "projects.json");

// Load projects from disk — returns {projects: []} envelope on disk (or [] legacy).
function loadProjectsStore() {
  try {
    if (!fs.existsSync(PROJECTS_STORE_FILE)) return { projects: [] };
    const raw = JSON.parse(fs.readFileSync(PROJECTS_STORE_FILE, "utf8"));
    // Accept both {projects:[]} and [] for backwards compat
    if (Array.isArray(raw)) return { projects: raw };
    if (raw && Array.isArray(raw.projects)) return raw;
    return { projects: [] };
  } catch (e) { console.error("[projects] load err", e.message); return { projects: [] }; }
}
function saveProjectsStore(store) {
  try { fs.writeFileSync(PROJECTS_STORE_FILE, JSON.stringify(store, null, 2)); } catch (e) { console.error("[projects] save err", e.message); }
}
// Generate stable id: timestamp + random suffix, lowercase alphanumeric + hyphen
function genProjectId() {
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}
function sanitizeProjectId(v) { return String(v || "").trim(); }
function nowIso() { return new Date().toISOString(); }
// Find project by id (including archived) — caller filters visible if needed
function findProject(store, id) { return store.projects.find(p => p.id === id) || null; }
// Validate project payload for create/update
function validateProjectPayload(body, isCreate) {
  if (isCreate && (!body.name || !String(body.name).trim())) return "name required";
  if (body.name !== undefined && !String(body.name).trim()) return "name cannot be empty";
  if (body.description !== undefined && typeof body.description !== "string") return "description must be string";
  if (body.skills !== undefined && !Array.isArray(body.skills)) return "skills must be array";
  if (body.linkedProjects !== undefined && !Array.isArray(body.linkedProjects)) return "linkedProjects must be array";
  return null;
}
// Normalize sessions entry — ensures required fields exist
function normalizeSessionEntry(s) {
  return {
    sessionId: String(s.sessionId || s.id || "").trim(),
    title: String(s.title || s.sessionId || "untitled").trim(),
    createdAt: s.createdAt || nowIso(),
    lastUsed: s.lastUsed || s.createdAt || nowIso(),
    summary: s.summary || ""
  };
}

// ---- Voice Registry + Log — TOP-LEVEL persistent storage (outside request callback) ----
const VOICE_COMMANDS = [
  { id: "new_project", patterns: ["nuevo proyecto {nombre}"], example: "nuevo proyecto mi app", description: "Crear proyecto (POST /api/projects name=nombre)", handler: "POST /api/projects" },
  { id: "open_project", patterns: ["abrir proyecto {nombre}"], example: "abrir proyecto mi app", description: "Cambiar proyecto activo por nombre aproximado (fuzzy match)", handler: "PATCH /api/ui/state projectId" },
  { id: "new_session", patterns: ["nueva sesión", "nueva sesion"], example: "nueva sesión", description: "Crear sesión en proyecto actual (POST /opencode/session)", handler: "POST /opencode/session" },
  { id: "open_app", patterns: ["abrir {app}"], example: "abrir chrome", description: "Abrir app por alias o paquete (POST /api/device/launch)", handler: "POST /api/device/launch" },
  { id: "screenshot", patterns: ["tomar captura", "hacer captura", "captura de pantalla"], example: "tomar captura", description: "Captura pantalla y mostrar inline (GET /api/device/screenshot)", handler: "GET /api/device/screenshot" },
  { id: "system_status", patterns: ["estado del sistema", "como esta el sistema", "estado"], example: "estado del sistema", description: "Leer /api/system/status en voz alta vía TTS", handler: "GET /api/system/status + TTS" },
  { id: "whatsapp_send", patterns: ["enviar a {contacto} por whatsapp {mensaje}", "manda whatsapp a {contacto} {mensaje}"], example: "enviar a juan por whatsapp hola", description: "Enviar WhatsApp (POST /api/assistant/execute action=whatsapp_send)", handler: "POST /api/assistant/execute" },
];
const VOICE_LOG = [];
const VOICE_LOG_MAX = 50;
function pushVoiceLog(entry) {
  const e = { timestamp: nowIso(), ...entry };
  VOICE_LOG.unshift(e);
  if (VOICE_LOG.length > VOICE_LOG_MAX) VOICE_LOG.length = VOICE_LOG_MAX;
  return e;
}

// ---- Skills + Summaries storage helpers ----
// Skills are markdown files at skills/{scope}/{name}.skill.md — scope "global" or projectId.
// Summaries are JSON files at summaries/{projectId}.summary.json with {summary, updatedAt, sessions[]}.

function sanitizeScope(v) { return String(v || "").trim(); }
function sanitizeName(v) { return String(v || "").trim(); }
function isValidScope(scope) {
  if (scope === "global") return true;
  // projectId scopes must correspond to an existing project id (or we allow any alphanumeric hyphen)
  return /^[a-z0-9][a-z0-9-]*$/i.test(scope);
}
function isValidSkillName(name) {
  return /^[a-z0-9][a-z0-9 _-]*$/i.test(name) && name.length <= 80;
}
function skillPath(scope, name) {
  // Prevent path traversal — sanitize
  const s = scope.replace(/[^a-zA-Z0-9_-]/g, "");
  const n = name.replace(/[^a-zA-Z0-9 _-]/g, "").replace(/\.+/g, "");
  return path.join(SKILLS_ROOT, s, `${n}.skill.md`);
}
function listSkills(scope) {
  const dir = path.join(SKILLS_ROOT, scope);
  try {
    if (!fs.existsSync(dir)) return [];
    return fs.readdirSync(dir).filter(f => f.endsWith(".skill.md")).map(f => {
      const name = f.replace(/\.skill\.md$/, "");
      const full = path.join(dir, f);
      const stat = fs.statSync(full);
      const content = fs.readFileSync(full, "utf8");
      return { scope, name, content, updatedAt: stat.mtime.toISOString(), size: content.length };
    });
  } catch { return []; }
}
function listAllSkillsMerged(projectId) {
  // Returns global skills + project-specific skills merged (global first)
  const global = listSkills("global");
  const project = projectId ? listSkills(projectId) : [];
  return [...global, ...project];
}
function writeSkill(scope, name, content) {
  const p = skillPath(scope, name);
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, String(content || ""), "utf8");
  return { scope, name, content: String(content || ""), path: p };
}
function deleteSkill(scope, name) {
  const p = skillPath(scope, name);
  if (!fs.existsSync(p)) return false;
  fs.unlinkSync(p);
  // Remove empty scope dir
  try { if (fs.readdirSync(path.dirname(p)).length === 0) fs.rmdirSync(path.dirname(p)); } catch {}
  return true;
}
function readSummary(projectId) {
  const p = path.join(SUMMARIES_DIR, `${projectId}.summary.json`);
  try { if (fs.existsSync(p)) return JSON.parse(fs.readFileSync(p, "utf8")); } catch {}
  return null;
}
function writeSummary(projectId, summary) {
  fs.mkdirSync(SUMMARIES_DIR, { recursive: true });
  const p = path.join(SUMMARIES_DIR, `${projectId}.summary.json`);
  const obj = { projectId, summary: String(summary || "").trim(), updatedAt: nowIso() };
  fs.writeFileSync(p, JSON.stringify(obj, null, 2));
  return obj;
}
function buildCrossProjectContext(projectId) {
  // Include one-paragraph summary of each linked project's last 3 sessions equivalent — here using summary.json
  if (!projectId) return "";
  const store = loadProjectsStore();
  const proj = findProject(store, projectId);
  if (!proj || !proj.linkedProjects || proj.linkedProjects.length === 0) return "";
  const paragraphs = [];
  for (const linkedId of proj.linkedProjects.slice(0, 8)) {
    const linked = findProject(store, linkedId);
    const title = linked ? linked.name : linkedId;
    const summ = readSummary(linkedId);
    if (summ && summ.summary) {
      paragraphs.push(`Project "${title}" (${linkedId}): ${summ.summary}`);
    } else {
      // Fallback: list last 3 session titles if no summary yet
      const last = linked && linked.sessions ? linked.sessions.slice(-3).map(s => s.title || s.sessionId) : [];
      if (last.length) paragraphs.push(`Project "${title}" (${linkedId}) recent sessions: ${last.join(" | ")} — no summary yet.`);
      else paragraphs.push(`Project "${title}" (${linkedId}): no summary or sessions.`);
    }
  }
  return paragraphs.join("\n\n");
}
function buildSkillsContext(projectId) {
  const skills = listAllSkillsMerged(projectId);
  if (!skills.length) return "";
  return skills.map(s => `### Skill: ${s.name} [${s.scope}]\n${s.content}`).join("\n\n---\n\n");
}
function buildSystemContextBlock(projectId) {
  // Compose skills + cross-project context into a single system block
  const parts = [];
  const skillsBlock = buildSkillsContext(projectId);
  if (skillsBlock) parts.push(`# Active Skills\n${skillsBlock}`);
  const crossBlock = buildCrossProjectContext(projectId);
  if (crossBlock) parts.push(`# Cross-Project Context (linked projects)\n${crossBlock}`);
  return parts.join("\n\n");
}

// ---- Non-destructive session ownership discovery (hub must never kill TUI) ----
// COMPANION_SESSION_FILE persists the PID we launched as companion-owned across restarts.
// If this file exists and its PID is still alive as "opencode serve", ownership is companion-owned.
// If health is true but no such file/pid matches, it's an existing termux-native session we must not touch.
// If health is false, ownership is none and only then may we launch via keepalive.sh.
const COMPANION_SESSION_FILE = path.join(__dirname, ".companion-session.json");

// Load persisted companion meta at startup (if any) — survives hub restarts so we remember our own serve.
let companionMeta = (() => {
  try { if (fs.existsSync(COMPANION_SESSION_FILE)) return JSON.parse(fs.readFileSync(COMPANION_SESSION_FILE, "utf8")); } catch {}
  return null;
})();

// Probe opencode health via HTTP — single source of truth for whether a serve is running (termux or companion).
// Called on hub startup and on every /api/system/status call (spec 1).
function probeOpencodeHealth() {
  return new Promise(resolve => {
    http.get({ hostname: OPENCODE_HOST, port: OPENCODE_PORT, path: "/global/health", timeout: 2000 }, r => {
      let d = ""; r.on("data", c => d += c); r.on("end", () => {
        try { const j = JSON.parse(d); resolve({ up: !!j.healthy || r.statusCode === 200, healthy: !!j.healthy, version: j.version || null, raw: j }); }
        catch { resolve({ up: r.statusCode === 200, healthy: false, version: null, raw: d }); }
      });
    }).on("error", () => resolve({ up: false, healthy: false, version: null })).end();
  });
}

// Scan /proc for opencode serve PIDs — distinguishes TUI (cmdline "opencode" only) from serve.
// Detection: split cmdline on \0 into argv parts. Real serve has argv[0] basename "opencode"/"opencode.exe" and argv[1]==="serve".
// This avoids false positives from shell strings that merely contain both words (e.g., comments, echo tests).
// Also handles "node /path/opencode serve" form where opencode is a JS file + serve is next arg.
// TUI has just "opencode\0" => no second arg "serve" => not counted.
function scanServePids() {
  const out = [];
  try {
    for (const e of fs.readdirSync("/proc")) {
      if (!/^[0-9]+$/.test(e)) continue;
      try {
        const cmd = fs.readFileSync(`/proc/${e}/cmdline`, "utf8");
        // Parse into argv parts split on \0 (null) — this is how kernel stores cmdline
        const parts = cmd.split("\x00").filter(Boolean);
        let isServe = false;
        for (let i = 0; i < parts.length; i++) {
          const base = parts[i].split("/").pop();
          // Condition: argv entry is opencode binary and next entry is literally "serve"
          if ((base === "opencode" || base === "opencode.exe") && parts[i + 1] === "serve") { isServe = true; break; }
        }
        // Fallback for node-wrapped opencode: node .../opencode ... serve
        if (!isServe && parts[0] && parts[0].endsWith("node") && parts.some(p => p.includes("opencode")) && parts.includes("serve")) {
          const opIdx = parts.findIndex(p => p.includes("opencode"));
          const serveIdx = parts.indexOf("serve");
          if (opIdx !== -1 && serveIdx > opIdx) isServe = true;
        }
        if (isServe) out.push(parseInt(e, 10));
      } catch {}
    }
  } catch {}
  return out;
}

// Get process uptime in seconds — reads /proc/<pid>/stat field 22 (starttime) and /proc/uptime.
// Used for /api/system/session-info uptime field.
function getProcessUptime(pid) {
  try {
    const stat = fs.readFileSync(`/proc/${pid}/stat`, "utf8");
    // stat format: pid (comm) state ... starttime is field 22 (1-indexed). comm may contain spaces but is inside parentheses.
    // Extract after last ') ' to safely get fields after comm.
    const after = stat.substring(stat.lastIndexOf(")") + 2);
    const parts = after.split(" ");
    // parts[0] is state, so starttime offset: field 22 => index 19 after split (field offset adjustment)
    // Simpler: original split without handling comm is fine because comm has no spaces for opencode, but we keep robust parsing.
    // Field 22 in full stat is parts[19] after this extraction.
    const starttimeTicks = parseInt(parts[19], 10);
    const uptimeSecTotal = parseFloat(fs.readFileSync("/proc/uptime", "utf8").split(" ")[0]);
    const clkTck = 100; // typical Linux CLK_TCK
    const procUptime = Math.floor(uptimeSecTotal - starttimeTicks / clkTck);
    return procUptime >= 0 ? procUptime : null;
  } catch { return null; }
}

// Check if companion meta pid is still alive and is a serve — validates companion-owned claim.
// Uses precise argv parsing (not substring) to avoid counting stale reused PIDs.
function isCompanionPidAlive(pid) {
  if (!pid) return false;
  try {
    // kill -0 checks existence without signal
    process.kill(pid, 0);
    // Also verify cmdline still is a real serve (argv[1]==="serve"), not just any "opencode" string
    const cmd = fs.readFileSync(`/proc/${pid}/cmdline`, "utf8");
    const parts = cmd.split("\x00").filter(Boolean);
    for (let i = 0; i < parts.length; i++) {
      const base = parts[i].split("/").pop();
      if ((base === "opencode" || base === "opencode.exe") && parts[i + 1] === "serve") return true;
    }
    if (parts[0] && parts[0].endsWith("node") && parts.some(p => p.includes("opencode")) && parts.includes("serve")) {
      const opIdx = parts.findIndex(p => p.includes("opencode"));
      const serveIdx = parts.indexOf("serve");
      if (opIdx !== -1 && serveIdx > opIdx) return true;
    }
    return false;
  } catch { return false; }
}

// Classify ownership: termux-native vs companion-owned vs none.
// Logic: if not healthy => none. If healthy and companionMeta pid alive+matches => companion-owned.
// Else if healthy and any serve pid exists but not companion-owned => termux-native (we did not start it, must not launch).
function classifyOwnership(health, servePids) {
  if (!health || !health.healthy || !health.up) return "none";
  // Condition: companion-owned only if we have a persisted pid that is still the active serve
  if (companionMeta && companionMeta.pid && isCompanionPidAlive(companionMeta.pid)) {
    // If multiple serves, check if our pid is among them
    if (servePids.includes(companionMeta.pid)) return "companion-owned";
  }
  // If health is true and we reach here, it's an existing session we did not start => termux-native (do not launch)
  return "termux-native";
}

// Persist companion-owned session after we launch it — records pid, port, startedAt.
function persistCompanionSession(pid) {
  try {
    companionMeta = { pid, port: OPENCODE_PORT, host: OPENCODE_HOST, startedAt: Date.now() };
    fs.writeFileSync(COMPANION_SESSION_FILE, JSON.stringify(companionMeta, null, 2));
    console.log(`[session] persisted companion-owned pid ${pid} port ${OPENCODE_PORT}`);
  } catch (e) { console.error("[session] persist err", e.message); }
}

// Clear stale companion meta if pid dead — prevents misclassifying termux-native as companion-owned.
function clearStaleCompanionMetaIfNeeded() {
  if (companionMeta && companionMeta.pid && !isCompanionPidAlive(companionMeta.pid)) {
    console.log(`[session] clearing stale companion meta pid ${companionMeta.pid} (dead)`);
    companionMeta = null;
    try { fs.unlinkSync(COMPANION_SESSION_FILE); } catch {}
  }
}

const MIME = {
  ".html":"text/html; charset=utf-8", ".js":"text/javascript; charset=utf-8",
  ".css":"text/css; charset=utf-8", ".json":"application/json; charset=utf-8",
  ".png":"image/png", ".svg":"image/svg+xml", ".ico":"image/x-icon",
  ".wav":"audio/wav", ".mp3":"audio/mpeg",
};

function send(res, code, body, headers={}){
  res.writeHead(code, { "Access-Control-Allow-Origin":"*", "Access-Control-Allow-Headers":"*", "Access-Control-Allow-Methods":"GET,POST,PUT,PATCH,DELETE,OPTIONS", ...headers });
  res.end(body);
}
function json(res, code, obj){ send(res, code, JSON.stringify(obj), {"Content-Type":"application/json; charset=utf-8"}); }

async function proxyWithInjection(req, resRaw, originalBodyBuf) {
  // For opencode message routes (/session/:id/message, /session/:id/prompt etc.), prepend system context block if project has skills.
  // Defensive: sanitize, size-cap, dedup, and never leak debug fields to provider.
  const targetPathCheck = req.url.replace(/^\/opencode/, "") || "/";
  const isMessageRoute = /\/session\/[^\/]+\/(message|prompt|chat)/.test(targetPathCheck);
  if (!isMessageRoute || req.method !== "POST" || !originalBodyBuf || originalBodyBuf.length === 0) return null;
  let parsed;
  try { parsed = JSON.parse(originalBodyBuf.toString("utf8")); } catch { return null; }
  // Dedup: if caller already injected (retry) skip to avoid double prefix blowing up size
  try {
    const firstPartText = Array.isArray(parsed.parts) && parsed.parts[0] && typeof parsed.parts[0].text === "string" ? parsed.parts[0].text : "";
    if (firstPartText.startsWith("[SYSTEM CONTEXT") || firstPartText.includes("---\n\n[SYSTEM CONTEXT")) return null;
    if (typeof parsed.text === "string" && parsed.text.includes("[SYSTEM CONTEXT")) return null;
    if (typeof parsed.prompt === "string" && parsed.prompt.includes("[SYSTEM CONTEXT")) return null;
  } catch {}
  // Resolve projectId for this message: from body.projectId, or from sessionId association via projects.json, or header X-Project-Id
  let projectId = parsed.projectId || parsed.projectID || req.headers["x-project-id"] || null;
  const sessionId = parsed.sessionId || parsed.sessionID || targetPathCheck.match(/\/session\/([^\/]+)/)?.[1] || null;
  if (!projectId && sessionId) {
    try {
      const store = loadProjectsStore();
      for (const p of store.projects) {
        if ((p.sessions || []).some(s => String(s.sessionId) === String(sessionId))) { projectId = p.id; break; }
      }
    } catch {}
  }
  // Also fallback to UI_STATE projectId if header missing (client persisted active project)
  if (!projectId && UI_STATE && UI_STATE.projectId) projectId = UI_STATE.projectId;
  if (!projectId) return null;
  let block = buildSystemContextBlock(projectId);
  if (!block) return null;
  // Defensive sanitization: remove control chars that break JSON/provider validation, keep \n \r \t
  // Also normalize: skills/summaries may contain unescaped quotes/backticks/binary
  block = String(block).replace(/[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]/g, " ");
  // Size cap: prevent oversized injection exceeding provider limit (12KB text ~3k tokens)
  const MAX_BLOCK_CHARS = 12000;
  if (block.length > MAX_BLOCK_CHARS) {
    block = block.slice(0, MAX_BLOCK_CHARS) + "\n\n[truncated: context exceeds 12KB cap]";
  }
  // Final size guard: if full payload would exceed 512KB (shouldTryInject boundary), truncate further
  const approxPayloadLen = originalBodyBuf.length + block.length + 256;
  if (approxPayloadLen > 512 * 1024) {
    // Skip injection rather than risk 400 from oversized body
    console.log(`[proxy] skip injection: payload would exceed 512KB cap (${approxPayloadLen} bytes) for project ${projectId}`);
    return null;
  }
  // Build system-prepend injection — opencode messages expect parts[]. Prepend a system parts entry.
  // JSON.stringify will properly escape all characters in block; no manual escaping needed.
  if (Array.isArray(parsed.parts)) {
    parsed.parts = [{ type: "text", text: `[SYSTEM CONTEXT — skills + linked projects]\n${block}` }, ...parsed.parts];
  } else if (typeof parsed.text === "string") {
    parsed.text = `[SYSTEM CONTEXT — skills + linked projects]\n${block}\n\n---\n\n${parsed.text}`;
  } else if (typeof parsed.prompt === "string") {
    parsed.prompt = `[SYSTEM CONTEXT]\n${block}\n\n---\n\n${parsed.prompt}`;
  } else {
    // Generic fallback: stash in systemContext field for observability; don't break unknown shapes
    parsed.systemContext = `[SYSTEM CONTEXT]\n${block}`;
  }
  // Do NOT add debug fields to payload sent to provider; keep meta for logging only via header/log
  const outBuf = Buffer.from(JSON.stringify(parsed));
  // Attach project hint for log line via a separate variable, not inside payload
  outBuf._injectedProjectId = projectId;
  return outBuf;
}

function proxyToOpencode(req, res){
  const targetPath = req.url.replace(/^\/opencode/, "") || "/";
  const opts = { hostname: OPENCODE_HOST, port: OPENCODE_PORT, path: targetPath, method: req.method, headers: { ...req.headers, host: `${OPENCODE_HOST}:${OPENCODE_PORT}` } };
  delete opts.headers["accept-encoding"];
  const contentLength = req.headers["content-length"] ? parseInt(req.headers["content-length"]) : null;
  if(contentLength && contentLength > 0){
    console.log(`[proxy] ${req.method} ${targetPath} streaming ${Math.round(contentLength/1024)}KB via chunks`);
  } else if(req.method==="POST" || req.method==="PUT" || req.method==="PATCH"){
    console.log(`[proxy] ${req.method} ${targetPath} streaming chunked (sin Content-Length)`);
  }
  // Early intercept for message routes that need skill injection — buffer and create request lazily
  // to avoid creating an initial pr whose 8s guard would race with the injected pr2.
  const len = req.headers["content-length"] ? parseInt(req.headers["content-length"]) : 0;
  const shouldTryInject = len > 0 && len < 512 * 1024 && req.method === "POST" && /\/session\/[^\/]+\/(message|prompt|chat)/.test(targetPath);
  if (shouldTryInject) {
    return (async () => {
      // For LLM-backed message routes, wait up to 60s (injected guard) — 8s was too short and caused premature 502 before LLM replied
      const guard = setTimeout(() => {
        if (!res.headersSent) {
          try { json(res, 502, { error: 'opencode timeout (injected)', hint: `opencode serve no respondió en 60s en ${OPENCODE_HOST}:${OPENCODE_PORT}` }); } catch (_) {}
        }
      }, 60000);
      try {
        const chunks = [];
        for await (const c of req) chunks.push(c);
        const buf = Buffer.concat(chunks);
        const injected = await proxyWithInjection(req, res, buf);
        const outBuf = injected || buf;
        if (injected) console.log(`[proxy] injected system context for project ${_projectHint(injected)} (${injected.length} bytes) sanitized+capped`);
        const injOpts = { ...opts, headers: { ...opts.headers, "content-length": String(outBuf.length), "Content-Length": String(outBuf.length) } };
        delete injOpts.headers["transfer-encoding"];
        delete injOpts.headers["Transfer-Encoding"];
        const pr2 = http.request(injOpts, (prRes2)=>{
          clearTimeout(guard);
          const h = { ...prRes2.headers, "Access-Control-Allow-Origin":"*", "Access-Control-Allow-Headers":"*", "Access-Control-Allow-Methods":"GET,POST,PUT,PATCH,DELETE,OPTIONS" };
          if (!res.headersSent) res.writeHead(prRes2.statusCode, h);
          prRes2.on('error', e => { console.error('[proxy inj] prRes error', e.message); try { res.destroy(); } catch (_) {} });
          prRes2.pipe(res);
        });
        pr2.on("error", e=> {
          clearTimeout(guard);
          console.error('[proxy inj] error', e.message);
          if(!res.headersSent) try { json(res, 502, { error:"opencode unreachable (injected)", detail:String(e) }); } catch(_){}
          else try{ res.end(); }catch(_){}
        });
        pr2.setTimeout(120000, ()=> { clearTimeout(guard); console.error('[proxy inj] timeout 120s'); try{ pr2.destroy(); }catch(_){} });
        res.setTimeout(130000, () => { clearTimeout(guard); try { res.destroy(); } catch (_) {} });
        pr2.write(outBuf);
        pr2.end();
      } catch (e) {
        clearTimeout(guard);
        console.error(`[proxy] injection path error ${e.message}`);
        if(!res.headersSent) try { json(res, 502, { error:"injection error", detail:String(e) }); } catch(_){}
      }
      function _projectHint(buf) {
        if (buf && buf._injectedProjectId) return buf._injectedProjectId;
        try { const j = JSON.parse(buf.toString("utf8")); return j.projectId || j.projectID || "unknown"; } catch { return "unknown"; }
      }
    })();
  }
  // Non-injected path — original streaming with guard
  const guard = setTimeout(() => {
    if (!res.headersSent) {
      try { json(res, 502, { error: 'opencode timeout', hint: `opencode serve no respondió en 8s en ${OPENCODE_HOST}:${OPENCODE_PORT}` }); } catch (_) {}
    }
    try { pr.destroy(); } catch (_) {}
  }, 8000);
  const pr = http.request(opts, (prRes)=>{
    clearTimeout(guard);
    const h = { ...prRes.headers, "Access-Control-Allow-Origin":"*", "Access-Control-Allow-Headers":"*", "Access-Control-Allow-Methods":"GET,POST,PUT,PATCH,DELETE,OPTIONS" };
    if (!res.headersSent) res.writeHead(prRes.statusCode, h);
    prRes.on('error', e => { console.error('[proxy] prRes error', e.message); try { res.destroy(); } catch (_) {} });
    res.on('error', e => { console.error('[proxy] res error', e.message); try { pr.destroy(); } catch (_) {} });
    req.on('aborted', () => { try { pr.destroy(); } catch (_) {} });
    prRes.pipe(res);
  });
  pr.on("error", e=> {
    clearTimeout(guard);
    console.error('[proxy] error', e.message);
    if(!res.headersSent) {
      try { json(res, 502, { error:"opencode unreachable", detail:String(e), hint:`opencode serve debe estar corriendo en ${OPENCODE_HOST}:${OPENCODE_PORT}. Ejecuta: opencode serve --port ${OPENCODE_PORT} --hostname 0.0.0.0  ó  opencode web --port ${OPENCODE_PORT} --hostname 0.0.0.0` }); } catch(_){}
    } else try{ res.end(); }catch(_){}
  });
  req.on('error', e => { clearTimeout(guard); console.error('[proxy] req error', e.message); try { pr.destroy(); } catch (_) {} });
  try { req.pipe(pr); } catch (e) { clearTimeout(guard); console.error('[proxy] pipe err', e.message); }
  pr.setTimeout(120000, ()=> { clearTimeout(guard); console.error('[proxy] timeout 120s (payload grande)'); try{ pr.destroy(); }catch(_){} });
  res.setTimeout(130000, () => { clearTimeout(guard); console.error('[proxy] res timeout 130s'); try { res.destroy(); } catch (_) {} });
}

// device helpers — portable + Android namespace aware
// En este POCO F3, los comandos Android solo funcionan vía nsenter -t 1 -m (monkey también necesita nsenter)
const ANDROID_CMDS = /\b(pm|input|am|getprop|uiautomator|cmd|dumpsys|settings|monkey)\b/;
const MAX_BUFFER = 50 * 1024 * 1024; // 50 MB para screenshots Base64 1080p/4K
const MAX_JSON_BODY = 55 * 1024 * 1024; // 55 MB límite JSON entrante

function shellExecRaw(cmd, timeout=12000, maxBuffer = MAX_BUFFER){
  return new Promise((resolve)=>{
    exec(cmd, { timeout, maxBuffer }, (err, stdout, stderr)=>{
      // si excede maxBuffer, err.message contiene "maxBuffer"
      if(err && String(err.message).includes("maxBuffer")){
        resolve({ code: 1, stdout: String(stdout||"").slice(0, maxBuffer), stderr: String(stderr||"") + "\n[hub] maxBuffer " + (maxBuffer/1024/1024) + "MB excedido — salida truncada. Usa streaming o reduce calidad.", signal: err.signal || null, killed: !!err.killed, error: err.message, truncated: true });
      } else {
        resolve({ code: err?.code ?? 0, stdout: String(stdout||""), stderr: String(stderr||""), signal: err?.signal || null, killed: !!err?.killed, error: err ? String(err.message) : null });
      }
    });
  });
}
function readJsonBody(req, limit = MAX_JSON_BODY){
  return new Promise((resolve, reject)=>{
    const chunks=[];
    let total=0;
    let aborted=false;
    req.on("data", c=>{
      total += c.length;
      if(total > limit && !aborted){
        aborted=true;
        reject(new Error("JSON body excede " + (limit/1024/1024) + "MB — reduce imagen o usa compresión"));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on("end", ()=>{ try{ resolve(Buffer.concat(chunks).toString("utf8")); }catch(e){ reject(e); } });
    req.on("error", reject);
  });
}
async function runShell(cmd, timeout=12000){
  // si es comando Android y existe nsenter, envolver
  const needsNs = ANDROID_CMDS.test(cmd);
  if(needsNs){
    // prueba nsenter primero (más rápido que su)
    const wrapped = `nsenter -t 1 -m -- sh -c ${JSON.stringify(cmd)} 2>&1`;
    const r = await shellExecRaw(wrapped, timeout);
    // si nsenter no existe o falló con "nsenter: not found", fallback a su/sh directo
    if(r.stdout.includes("nsenter:") || r.stderr.includes("nsenter:") || (r.code===127 && r.stdout==="")){
      const fallback = `su -c ${JSON.stringify(cmd)} 2>&1 || sh -c ${JSON.stringify(cmd)} 2>&1`;
      return shellExecRaw(fallback, timeout);
    }
    return r;
  }
  // comando genérico linux: intenta su luego sh
  const wrapped = `su -c ${JSON.stringify(cmd)} 2>&1 || sh -c ${JSON.stringify(cmd)} 2>&1`;
  return shellExecRaw(wrapped, timeout);
}
async function listProjects(){
  try{
    const entries = fs.readdirSync(PROJECTS_ROOT, {withFileTypes:true});
    return entries.filter(d=>d.isDirectory() && !d.name.startsWith(".")).map(d=>d.name);
  }catch{ return []; }
}

const server = http.createServer(async (req, res)=>{
  // timeouts largos para payloads multimodales grandes (video/audio/docs en Base64)
  req.setTimeout(125000, () => { console.error('[hub] req timeout 125s (multimodal)', req.url?.slice(0,140)); try { res.destroy(); } catch (_) {} });
  res.on('close', () => { /* cleanup */ });
  if(req.method==="OPTIONS"){ return send(res, 204, ""); }
  let url;
  try { url = new URL(req.url, `http://${req.headers.host}`); } catch (e) { return json(res, 400, { error: 'bad url', detail: String(e) }); }
  const pathname = url.pathname;

  // 1) opencode proxy
  if(pathname.startsWith("/opencode/") || pathname==="/opencode"){
    return proxyToOpencode(req, res);
  }
  // SSE passthrough convenience: /event and /global/event also proxied? keep alias
  if(pathname==="/event" || pathname==="/global/event"){
    req.url = "/opencode" + pathname;
    return proxyToOpencode(req, res);
  }

  // 2) API hub — non-destructive session discovery
  // Every /api/system/status call probes health and classifies ownership.
  // Ownership values: "companion-owned" (we launched via keepalive), "termux-native" (existing serve we didn't start), "none" (no serve).
  // This is the single source of truth for whether keepalive may launch.
  if(pathname==="/api/system/status" && req.method==="GET"){
    // Probe opencode health — spec (1): detect if serve already running (termux or companion)
    const ocHealth = await probeOpencodeHealth();
    // Scan serve PIDs via /proc — checks cmdline contains "opencode"+"serve" (not TUI)
    clearStaleCompanionMetaIfNeeded();
    const servePids = scanServePids();
    // Classify ownership — spec (2)
    const sessionOwnership = classifyOwnership(ocHealth, servePids);
    const bridgeHealth = await new Promise(resolve=>{
      http.get({ hostname:"127.0.0.1", port:8766, path:"/status", timeout:1500 }, r=>{
        let d=""; r.on("data",c=>d+=c); r.on("end", async ()=>{
          let h;
          try {
            const j=JSON.parse(d);
            h = { up:true, a11y: !!j.a11y };
            if (j.needsA11yRepair) {
              try {
                const cur = (await runShell("settings get secure enabled_accessibility_services")).stdout.trim();
                const me = "com.opencode.companion/com.opencode.companion.OpencodeAccessibilityService";
                const parts = cur.split(":").map(x=>x.trim()).filter(x=>x && x!==me);
                parts.push(me);
                await runShell("settings put secure enabled_accessibility_services " + parts.join(":"));
                await runShell("settings put secure accessibility_enabled 1");
                h.a11yRepaired = true;
              } catch(_e) { h.a11yRepairError = String((_e&&_e.message)||_e).slice(0,120); }
            }
          } catch(_e) { h = { up:true, a11y:false }; }
          resolve(h);
        });
      }).on("error", ()=> resolve({ up:false, a11y:false })).end();
    });
    const ready = ocHealth.healthy && ocHealth.up;
    // Build sessionInfo for inline inclusion — detailed info available via /api/system/session-info
    const primaryPid = servePids[0] || (companionMeta && companionMeta.pid) || null;
    return json(res, 200, {
      ready,
      hub:`http://127.0.0.1:${HUB_PORT}`,
      opencode: ocHealth,
      bridge: bridgeHealth,
      // Session ownership discovery — spec (2): values "termux-native" | "companion-owned" | "none"
      sessionOwnership,
      // Inline session info (light) — full data via /api/system/session-info which includes uptime
      sessionInfo: primaryPid ? { pid: primaryPid, ownership: sessionOwnership, port: OPENCODE_PORT } : null,
      hint: ready ? `ready (${sessionOwnership})` : (sessionOwnership === "none" ? "no serve — POST /api/system/start will launch (only when none)" : `existing ${sessionOwnership} session detected — not launching`)
    });
  }
  // Detailed session info route — spec (4): PID, ownership, uptime, port
  // Does NOT trigger launch — read-only probe, safe for polling.
  if(pathname==="/api/system/session-info" && req.method==="GET"){
    const health = await probeOpencodeHealth();
    clearStaleCompanionMetaIfNeeded();
    const servePids = scanServePids();
    const ownership = classifyOwnership(health, servePids);
    // Primary pid: companion-owned uses persisted pid, termux-native uses first scanned serve pid
    let pid = null;
    if (ownership === "companion-owned" && companionMeta && companionMeta.pid) pid = companionMeta.pid;
    else if (servePids.length) pid = servePids[0];
    const uptime = pid ? getProcessUptime(pid) : null;
    return json(res, 200, {
      // PID of the detected serve process (null if none)
      pid,
      // Ownership classification — see classifyOwnership logic
      ownership,
      // Uptime in seconds since process start (null if unknown/pid missing)
      uptime,
      // Port the serve is listening on (from HUB config)
      port: OPENCODE_PORT,
      host: OPENCODE_HOST,
      // All detected serve pids (for debugging)
      allServePids: servePids,
      // Persisted companion meta (if any)
      companionMeta,
      health,
      hint: ownership === "none" ? "no serve running — safe to launch via POST /api/system/start" : `${ownership} session pid ${pid} uptime ${uptime !== null ? uptime + 's' : 'unknown'}`
    });
  }
  // Non-destructive launch gate — spec (3): only launch when sessionOwnership is "none", never when "termux-native"
  if(pathname==="/api/system/start" && req.method==="POST"){
    await readJsonBody(req, 64*1024).catch(()=> "{}");
    // Check ownership BEFORE deciding to launch — do not destroy existing termux session
    const preHealth = await probeOpencodeHealth();
    clearStaleCompanionMetaIfNeeded();
    const preServePids = scanServePids();
    const preOwnership = classifyOwnership(preHealth, preServePids);
    // Spec (3): if already healthy with any ownership except "none", do not launch via keepalive
    if (preHealth.healthy && preOwnership !== "none") {
      console.log(`[system] POST /api/system/start — skipped launch: existing ${preOwnership} session pid ${preServePids[0] || preHealth.version || ''} already healthy (non-destructive)`);
      return json(res, 200, {
        started: false,
        skipped: true,
        reason: `Existing ${preOwnership} session already running — not launching (non-destructive policy)`,
        healthy: true,
        sessionOwnership: preOwnership,
        opencode:`http://${OPENCODE_HOST}:${OPENCODE_PORT}`,
        steps: [`skip: existing ${preOwnership} healthy, pid ${preServePids[0] || 'unknown'}`],
        alreadyHealthy: true,
        next:"Use GET /api/system/session-info for PID/uptime details"
      });
    }
    console.log('[system] POST /api/system/start — sessionOwnership none, invoking keepalive + opencode ensure (host namespace)');
    const steps = [];
    async function ensureOpencode(){
      const health = await probeOpencodeHealth();
      if(health.healthy) { steps.push("opencode: already healthy (re-check)"); return { already:true }; }
      steps.push("opencode: not healthy — launching via host keepalive.sh (only when none)");
      // keepalive.sh y opencode deben lanzarse en HOST (donde existe /usr/bin/node), no en system (nsenter -t 1 -m no ve /usr/bin/node)
      // This path is only reached when ownership is "none" — safe per spec (3)
      const r1 = await shellExecRaw(`nohup sh /sdcard/projects/opencode-companion/keepalive.sh > /sdcard/projects/opencode-companion/keepalive.log 2>&1 & echo keepalive_pid=$!`, 8000, 1024*1024);
      steps.push(`keepalive.sh: ${r1.stdout.trim().slice(0,300)} ${r1.stderr.trim().slice(0,200)}`);
      // Direct launch fallback — also only when none; persist companion-owned meta so future probes classify correctly
      const r2 = await shellExecRaw(`nohup opencode serve --port ${OPENCODE_PORT} --hostname 0.0.0.0 >> /sdcard/projects/opencode-companion/opencode.log 2>&1 & echo opencode_direct_pid=$!`, 8000, 1024*1024);
      steps.push(`opencode direct: ${r2.stdout.trim().slice(0,300)} ${r2.stderr.trim().slice(0,200)}`);
      // Extract pid from "opencode_direct_pid=12345" and persist as companion-owned
      const m = r2.stdout.match(/opencode_direct_pid=(\d+)/);
      if (m) {
        const newPid = parseInt(m[1], 10);
        // Give process a moment to actually start before probing
        await new Promise(r => setTimeout(r, 500));
        persistCompanionSession(newPid);
        steps.push(`persisted companion-owned pid ${newPid}`);
      }
      await runShell(`dumpsys deviceidle whitelist +com.opencode.companion 2>&1 | head -n 3; cmd deviceidle whitelist +com.opencode.companion 2>&1 | head -n 3; am set-standby-bucket com.opencode.companion active 2>&1 | head -n 3`).then(r=> steps.push(`whitelist: ${r.stdout.trim().slice(0,200)}`)).catch(()=>{});
      return { already:false };
    }
    const ens = await ensureOpencode();
    // poll hasta healthy o timeout 25s
    let healthy=false;
    for(let i=0;i<12;i++){
      await new Promise(r=> setTimeout(r, 2100));
      const h = await probeOpencodeHealth();
      if(h.healthy){ healthy=true; break; }
    }
    steps.push(healthy ? "poll: healthy after retry" : "poll: timeout 25s not healthy");
    // Re-classify after poll so response includes new ownership
    const postHealth = await probeOpencodeHealth();
    const postPids = scanServePids();
    const postOwnership = classifyOwnership(postHealth, postPids);
    return json(res, healthy ? 200 : 202, { started: !ens.already && healthy, healthy, sessionOwnership: postOwnership, opencode:`http://${OPENCODE_HOST}:${OPENCODE_PORT}`, steps, alreadyHealthy: ens.already, next:"Poll GET /api/system/status until {ready:true} or GET /api/system/session-info for details" });
  }
  if(pathname==="/api/ui/state" && req.method==="GET"){
    return json(res, 200, { ...UI_STATE });
  }
  if(pathname==="/api/ui/state" && req.method==="PATCH"){
    try {
      const raw = await readJsonBody(req, 64*1024);
      const body = JSON.parse(raw || "{}");
      // Legacy folder name compat
      if ("project" in body) UI_STATE.project = body.project || null;
      // New: active companion project id (spec 5) + session id
      if ("projectId" in body) UI_STATE.projectId = body.projectId || null;
      if ("sessionId" in body) UI_STATE.sessionId = body.sessionId || null;
      saveUiState();
      return json(res, 200, { ...UI_STATE });
    } catch (e) { return json(res, 400, { error: String(e).slice(0,400) }); }
  }

  // ---- Companion Projects REST — projects.json persistent store (spec 1-2, envelope spec 6) ----
  // Helper: send ok envelope consistently
  function ok(data) { return { ok: true, data }; }
  function fail(error, code) { return { ok: false, error: String(error).slice(0, 800), code }; }

  // GET /api/projects — list all non-archived (includeArchived=1 includes archived)
  if(pathname==="/api/projects" && req.method==="GET"){
    // Serve both legacy folder scan and companion managed store depending on query.
    // Default: companion managed projects (spec: projects.json). Legacy folder scan via ?source=fs
    const source = url.searchParams.get("source");
    const includeArchived = url.searchParams.get("includeArchived") === "1" || url.searchParams.get("all") === "1";
    if (source === "fs") {
      // Legacy: scan filesystem folders under PROJECTS_ROOT
      const dirs = await listProjects();
      const infos = dirs.map(name=>{
        try{
          const p = path.join(PROJECTS_ROOT, name);
          const pkg = fs.existsSync(path.join(p,"package.json")) ? JSON.parse(fs.readFileSync(path.join(p,"package.json"),"utf8")) : null;
          const git = fs.existsSync(path.join(p,".git"));
          return { name, path:p, hasPackage:!!pkg, description: pkg?.description||"", git };
        }catch{ return { name, path: path.join(PROJECTS_ROOT,name) }; }
      });
      return json(res, 200, ok(infos));
    }
    const store = loadProjectsStore();
    let list = store.projects;
    if (!includeArchived) list = list.filter(p => !p.archivedAt);
    // Sort: active first by createdAt desc, then archived
    list = [...list].sort((a,b) => (b.createdAt || "").localeCompare(a.createdAt || ""));
    return json(res, 200, ok(list));
  }

  // POST /api/projects — create {name, description, skills?, linkedProjects?}
  if(pathname==="/api/projects" && req.method==="POST"){
    try {
      const raw = await readJsonBody(req, 64*1024);
      const body = JSON.parse(raw || "{}");
      const err = validateProjectPayload(body, true);
      if (err) return json(res, 400, fail(err));
      const store = loadProjectsStore();
      // Duplicate name guard (case-insensitive among non-archived)
      const normName = String(body.name).trim();
      if (store.projects.some(p => !p.archivedAt && p.name.toLowerCase() === normName.toLowerCase())) {
        return json(res, 409, fail(`project name "${normName}" already exists`));
      }
      const proj = {
        id: genProjectId(),
        name: normName,
        description: String(body.description || "").trim(),
        createdAt: nowIso(),
        archivedAt: null,
        sessions: [],
        skills: Array.isArray(body.skills) ? body.skills : [],
        linkedProjects: Array.isArray(body.linkedProjects) ? body.linkedProjects : []
      };
      store.projects.push(proj);
      saveProjectsStore(store);
      return json(res, 201, ok(proj));
    } catch (e) { return json(res, 500, fail(String(e))); }
  }

  // PATCH /api/projects/:id — update name/description/archive (archivedAt toggle), also skills/linked
  if(pathname.startsWith("/api/projects/") && req.method==="PATCH" && !pathname.includes("/sessions")){
    const m = pathname.match(/^\/api\/projects\/([^\/]+)$/);
    if (!m) return json(res, 404, fail("not found"));
    const id = sanitizeProjectId(decodeURIComponent(m[1]));
    try {
      const raw = await readJsonBody(req, 64*1024);
      const body = JSON.parse(raw || "{}");
      const err = validateProjectPayload(body, false);
      if (err) return json(res, 400, fail(err));
      const store = loadProjectsStore();
      const proj = findProject(store, id);
      if (!proj) return json(res, 404, fail(`project ${id} not found`));
      if (body.name !== undefined) {
        const newName = String(body.name).trim();
        if (!newName) return json(res, 400, fail("name cannot be empty"));
        // Duplicate guard excluding self
        if (store.projects.some(p => p.id !== id && !p.archivedAt && p.name.toLowerCase() === newName.toLowerCase())) {
          return json(res, 409, fail(`project name "${newName}" already exists`));
        }
        proj.name = newName;
      }
      if (body.description !== undefined) proj.description = String(body.description || "").trim();
      if (body.archived !== undefined || body.archivedAt !== undefined) {
        // archived boolean toggles archivedAt timestamp (soft delete spec)
        const shouldArchive = body.archived === true || (body.archivedAt !== undefined && body.archivedAt !== null);
        if (shouldArchive && !proj.archivedAt) proj.archivedAt = nowIso();
        else if (!shouldArchive) proj.archivedAt = null;
        else if (body.archivedAt) proj.archivedAt = body.archivedAt;
      }
      if (body.skills !== undefined) proj.skills = Array.isArray(body.skills) ? body.skills : [];
      if (body.linkedProjects !== undefined) proj.linkedProjects = Array.isArray(body.linkedProjects) ? body.linkedProjects : [];
      if (body.sessions !== undefined) {
        // Allow bulk replace sessions (admin) — normalize each
        if (!Array.isArray(body.sessions)) return json(res, 400, fail("sessions must be array"));
        proj.sessions = body.sessions.map(normalizeSessionEntry).filter(s => s.sessionId);
      }
      saveProjectsStore(store);
      return json(res, 200, ok(proj));
    } catch (e) { return json(res, 500, fail(String(e))); }
  }

  // DELETE /api/projects/:id — soft delete: set archivedAt timestamp (spec 2)
  if(pathname.match(/^\/api\/projects\/[^\/]+$/) && req.method==="DELETE" && !pathname.includes("/sessions")){
    const m = pathname.match(/^\/api\/projects\/([^\/]+)$/);
    if (!m) return json(res, 404, fail("not found"));
    const id = sanitizeProjectId(decodeURIComponent(m[1]));
    const store = loadProjectsStore();
    const proj = findProject(store, id);
    if (!proj) return json(res, 404, fail(`project ${id} not found`));
    if (proj.archivedAt) return json(res, 200, ok(proj)); // already archived idempotent
    proj.archivedAt = nowIso();
    saveProjectsStore(store);
    return json(res, 200, ok(proj));
  }

  // GET /api/projects/:id/sessions — list sessions for a project
  if(pathname.match(/^\/api\/projects\/[^\/]+\/sessions$/) && req.method==="GET"){
    const m = pathname.match(/^\/api\/projects\/([^\/]+)\/sessions$/);
    const id = sanitizeProjectId(decodeURIComponent(m[1]));
    const store = loadProjectsStore();
    const proj = findProject(store, id);
    if (!proj) return json(res, 404, fail(`project ${id} not found`));
    // Return sessions sorted by lastUsed desc
    const sorted = [...(proj.sessions || [])].sort((a,b) => (b.lastUsed || b.createdAt || "").localeCompare(a.lastUsed || a.createdAt || ""));
    return json(res, 200, ok(sorted));
  }

  // POST /api/projects/:id/sessions — associate existing opencode sessionId to project (spec 2)
  // Body: {sessionId, title?, summary?} — sessionId is opencode session id to link
  if(pathname.match(/^\/api\/projects\/[^\/]+\/sessions$/) && req.method==="POST"){
    try {
      const m = pathname.match(/^\/api\/projects\/([^\/]+)\/sessions$/);
      const id = sanitizeProjectId(decodeURIComponent(m[1]));
      const raw = await readJsonBody(req, 64*1024);
      const body = JSON.parse(raw || "{}");
      const sessionId = String(body.sessionId || body.id || "").trim();
      if (!sessionId) return json(res, 400, fail("sessionId required"));
      const store = loadProjectsStore();
      const proj = findProject(store, id);
      if (!proj) return json(res, 404, fail(`project ${id} not found`));
      if (proj.archivedAt) return json(res, 409, fail(`project ${id} is archived`));
      // Avoid duplicate association in same project
      if ((proj.sessions || []).some(s => s.sessionId === sessionId)) {
        return json(res, 409, fail(`session ${sessionId} already associated to project ${id}`));
      }
      // Remove association from other projects if any (ensure session belongs to at most one project)
      for (const p of store.projects) {
        if (p.id !== id) p.sessions = (p.sessions || []).filter(s => s.sessionId !== sessionId);
      }
      const entry = normalizeSessionEntry({ sessionId, title: body.title, summary: body.summary, createdAt: body.createdAt, lastUsed: body.lastUsed });
      proj.sessions = proj.sessions || [];
      proj.sessions.push(entry);
      saveProjectsStore(store);
      return json(res, 201, ok(entry));
    } catch (e) { return json(res, 500, fail(String(e))); }
  }

  // DELETE /api/projects/:id/sessions/:sessionId — disassociate session from project
  if(pathname.match(/^\/api\/projects\/[^\/]+\/sessions\/[^\/]+$/) && req.method==="DELETE"){
    const m = pathname.match(/^\/api\/projects\/([^\/]+)\/sessions\/([^\/]+)$/);
    const id = sanitizeProjectId(decodeURIComponent(m[1]));
    const sessionId = sanitizeProjectId(decodeURIComponent(m[2]));
    const store = loadProjectsStore();
    const proj = findProject(store, id);
    if (!proj) return json(res, 404, fail(`project ${id} not found`));
    const before = (proj.sessions || []).length;
    proj.sessions = (proj.sessions || []).filter(s => s.sessionId !== sessionId);
    if (proj.sessions.length === before) return json(res, 404, fail(`session ${sessionId} not found in project ${id}`));
    saveProjectsStore(store);
    return json(res, 200, ok({ removed: sessionId, projectId: id }));
  }

  // ---- Skills + Cross-Project Context + Session Summaries ----

  // GET /api/skills?projectId=X — merged global + project-specific skills
  if(pathname==="/api/skills" && req.method==="GET"){
    const projectId = url.searchParams.get("projectId") || url.searchParams.get("project") || null;
    const scopeFilter = url.searchParams.get("scope");
    if (scopeFilter) {
      const list = listSkills(scopeFilter);
      return json(res, 200, ok(list));
    }
    const merged = listAllSkillsMerged(projectId);
    // Return {ok:true, data:[{scope,name,content,...}], meta:{projectId, counts}}
    return json(res, 200, ok({ skills: merged, projectId, counts: { global: listSkills("global").length, project: projectId ? listSkills(projectId).length : 0, total: merged.length } }));
  }

  // POST /api/skills — create skill {scope, name, content}
  if(pathname==="/api/skills" && req.method==="POST"){
    try {
      const raw = await readJsonBody(req, 64*1024);
      const body = JSON.parse(raw || "{}");
      const scope = sanitizeScope(body.scope);
      const name = sanitizeName(body.name);
      const content = body.content !== undefined ? String(body.content) : "";
      if (!scope) return json(res, 400, fail("scope required: global or projectId"));
      if (!isValidScope(scope)) return json(res, 400, fail(`invalid scope "${scope}" — use "global" or a valid projectId`));
      if (scope !== "global") {
        const s = loadProjectsStore();
        if (!findProject(s, scope)) return json(res, 404, fail(`scope project ${scope} not found`));
      }
      if (!name) return json(res, 400, fail("name required"));
      if (!isValidSkillName(name)) return json(res, 400, fail(`invalid skill name "${name}" — alphanumeric, spaces, hyphens, max 80`));
      const existing = skillPath(scope, name);
      if (fs.existsSync(existing)) return json(res, 409, fail(`skill "${name}" already exists in scope "${scope}"`));
      const created = writeSkill(scope, name, content);
      return json(res, 201, ok({ scope: created.scope, name: created.name, content: created.content, size: created.content.length }));
    } catch (e) { return json(res, 500, fail(String(e))); }
  }

  // PATCH /api/skills/:scope/:name — update content
  if(pathname.startsWith("/api/skills/") && req.method==="PATCH"){
    const m = pathname.match(/^\/api\/skills\/([^\/]+)\/([^\/]+)$/);
    if (!m) return json(res, 404, fail("not found — use /api/skills/:scope/:name"));
    const scope = sanitizeScope(decodeURIComponent(m[1]));
    const name = sanitizeName(decodeURIComponent(m[2]));
    try {
      const p = skillPath(scope, name);
      if (!fs.existsSync(p)) return json(res, 404, fail(`skill "${name}" not found in scope "${scope}"`));
      const raw = await readJsonBody(req, 64*1024);
      const body = JSON.parse(raw || "{}");
      const newContent = body.content !== undefined ? String(body.content) : fs.readFileSync(p, "utf8");
      const updated = writeSkill(scope, name, newContent);
      return json(res, 200, ok({ scope: updated.scope, name: updated.name, content: updated.content, size: updated.content.length }));
    } catch (e) { return json(res, 500, fail(String(e))); }
  }

  // DELETE /api/skills/:scope/:name
  if(pathname.match(/^\/api\/skills\/[^\/]+\/[^\/]+$/) && req.method==="DELETE"){
    const m = pathname.match(/^\/api\/skills\/([^\/]+)\/([^\/]+)$/);
    const scope = sanitizeScope(decodeURIComponent(m[1]));
    const name = sanitizeName(decodeURIComponent(m[2]));
    const okDel = deleteSkill(scope, name);
    if (!okDel) return json(res, 404, fail(`skill "${name}" not found in scope "${scope}"`));
    return json(res, 200, ok({ removed: name, scope }));
  }

  // POST /api/projects/:id/summarize — generate/update project summary + summaries/<id>.summary.json
  // Body: {summary?} — if summary provided, use it; otherwise auto-build from last 3 sessions + description
  if(pathname.match(/^\/api\/projects\/[^\/]+\/summarize$/) && req.method==="POST"){
    const m = pathname.match(/^\/api\/projects\/([^\/]+)\/summarize$/);
    const id = sanitizeProjectId(decodeURIComponent(m[1]));
    const store = loadProjectsStore();
    const proj = findProject(store, id);
    if (!proj) return json(res, 404, fail(`project ${id} not found`));
    try {
      const raw = await readJsonBody(req, 64*1024).catch(()=> "{}");
      let body = {};
      try { body = JSON.parse(raw || "{}"); } catch {}
      let summary = body.summary ? String(body.summary).trim() : "";
      if (!summary) {
        // Auto-generate one-paragraph summary from project metadata + last 3 sessions
        const last = (proj.sessions || []).slice(-3);
        const sessLines = last.map(s => `${s.title || s.sessionId}${s.summary ? `: ${s.summary}` : ""}`).join(" | ");
        const desc = proj.description ? `${proj.description}. ` : "";
        const sessPart = last.length ? `Recent sessions (${last.length}): ${sessLines}.` : "No sessions yet.";
        summary = `${desc}${sessPart}`.trim();
        if (!summary) summary = `Project ${proj.name} — no sessions or description yet.`;
      }
      // Persist project summary back into projects.json for quick cross-project access
      proj._summaryParagraph = summary;
      proj._summaryUpdatedAt = nowIso();
      saveProjectsStore(store);
      // Also write summaries/<id>.summary.json as specified
      const fileObj = writeSummary(id, summary);
      return json(res, 200, ok({ projectId: id, summary: fileObj.summary, updatedAt: fileObj.updatedAt, sessionsCount: (proj.sessions||[]).length }));
    } catch (e) { return json(res, 500, fail(String(e))); }
  }

  // GET /api/opencode/sessions — proxy live opencode session list (fix 4: surface existing chats)
  if(pathname==="/api/opencode/sessions" && req.method==="GET"){
    try {
      const ocRes = await new Promise((resolve, reject)=>{
        const r = http.get({ hostname: OPENCODE_HOST, port: OPENCODE_PORT, path: "/session", timeout: 4000 }, rs=>{
          let d=""; rs.on("data",c=>d+=c); rs.on("end",()=> resolve({ status: rs.statusCode, body: d }));
        });
        r.on("error", reject);
        r.setTimeout(4000, ()=> { try{ r.destroy(); }catch{} reject(new Error("timeout /session")); });
      });
      const payload = JSON.parse(ocRes.body || "[]");
      // Normalize to array
      const list = Array.isArray(payload) ? payload : (payload.sessions || payload.data || []);
      return json(res, 200, ok(list));
    } catch (e) {
      return json(res, 500, fail(`opencode /session proxy failed: ${String(e).slice(0,400)}`));
    }
  }

  // Phase 2: GET /api/opencode/sessions/:id/messages — proxy GET /session/:id/message
  // Following same proxyToOpencode pattern as /api/opencode/sessions above
  if(pathname.match(/^\/api\/opencode\/sessions\/[^\/]+\/messages$/) && req.method==="GET"){
    const m = pathname.match(/^\/api\/opencode\/sessions\/([^\/]+)\/messages$/);
    const sid = sanitizeProjectId(decodeURIComponent(m[1]));
    try {
      const ocRes = await new Promise((resolve, reject) => {
        const r = http.get({ hostname: OPENCODE_HOST, port: OPENCODE_PORT, path: `/session/${encodeURIComponent(sid)}/message`, timeout: 8000 }, rs => {
          let d = ""; rs.on("data", c => d += c); rs.on("end", () => resolve({ status: rs.statusCode, body: d }));
        });
        r.on("error", reject);
        r.setTimeout(8000, () => { try { r.destroy(); } catch {} reject(new Error("timeout /session/:id/message")); });
      });
      let payload;
      try { payload = JSON.parse(ocRes.body || "[]"); } catch { payload = ocRes.body; }
      const list = Array.isArray(payload) ? payload : (payload.messages || payload.data || []);
      // Pass through raw array — hub envelope not needed, keep compat with direct /opencode proxy
      // Wrap in ok envelope for consistent client parsing (ApiService expects List<Message>)
      // But Retrofit will deserialize envelope.data — so use envelope
      return json(res, ocRes.status, ok(list));
    } catch (e) {
      return json(res, 502, fail(`opencode /session/:id/message proxy failed: ${String(e).slice(0,400)}`));
    }
  }

  // GET /api/opencode/models — list available models for the model selector
  // Returns a curated list since opencode doesn't expose a models API directly
  if(pathname === "/api/opencode/models" && req.method === "GET") {
    const models = [
      { id: "gemini-3.6-flash", name: "Gemini 3.6 Flash", description: "Rápido y eficiente" },
      { id: "gemini-3.6-flash-lite", name: "Gemini 3.6 Flash Lite", description: "Más rápido, menos preciso" },
      { id: "gemini-2.5-pro", name: "Gemini 2.5 Pro", description: "Alta calidad, más lento" },
      { id: "gpt-4o", name: "GPT-4o", description: "OpenAI multihablidad" },
      { id: "gpt-4o-mini", name: "GPT-4o Mini", description: "Rápido y económico" },
      { id: "claude-sonnet-4-20250514", name: "Claude Sonnet 4", description: "Balance calidad/velocidad" },
      { id: "claude-3-5-haiku-20241022", name: "Claude 3.5 Haiku", description: " Ultrarrápido" }
    ];
    return json(res, 200, ok(models));
  }

  // GET /api/projects/:id/summary — read summary (optional fetch helper)
  if(pathname.match(/^\/api\/projects\/[^\/]+\/summary$/) && req.method==="GET"){
    const m = pathname.match(/^\/api\/projects\/[^\/]+\/summary$/);
    const id = sanitizeProjectId(decodeURIComponent(m[1]));
    const data = readSummary(id);
    if (!data) return json(res, 404, fail(`no summary for project ${id} — POST /api/projects/${id}/summarize to generate`));
    return json(res, 200, ok(data));
  }

  // ---- Voice Command Registry + Log (hands-free system) ----
  // NOTE: VOICE_COMMANDS/LOG are defined at top-level scope outside createServer callback for persistence
  // (previously inside callback caused per-request reset — now fixed)
  // GET /api/voice/commands — all registered commands with patterns/descriptions
  if(pathname==="/api/voice/commands" && req.method==="GET"){
    return json(res, 200, ok(VOICE_COMMANDS));
  }
  // POST /api/voice/log — record a voice command execution (called by app.js after handler)
  // Body: {recognizedText, matchedCommand, result, projectId?, sessionId?}
  if(pathname==="/api/voice/log" && req.method==="POST"){
    try {
      const raw = await readJsonBody(req, 16*1024);
      const body = JSON.parse(raw || "{}");
      const entry = pushVoiceLog({
        recognizedText: String(body.recognizedText || "").slice(0, 600),
        matchedCommand: String(body.matchedCommand || body.command || "none").slice(0, 80),
        result: body.result !== undefined ? String(body.result).slice(0, 2000) : null,
        projectId: body.projectId || null,
        sessionId: body.sessionId || null
      });
      return json(res, 201, ok(entry));
    } catch (e) { return json(res, 400, fail(String(e))); }
  }
  // GET /api/voice/log — last 50 commands with timestamp/recognizedText/matchedCommand/result
  if(pathname==="/api/voice/log" && req.method==="GET"){
    return json(res, 200, ok(VOICE_LOG));
  }

  // GET /api/health — full diagnostic (opencode, bridge, disk, uptime, sessionOwnership, active project, last voice)
  if(pathname==="/api/health" && req.method==="GET"){
    const healthProbe = await probeOpencodeHealth();
    clearStaleCompanionMetaIfNeeded();
    const servePids = scanServePids();
    const ownership = classifyOwnership(healthProbe, servePids);
    const uptimePid = servePids[0] || (companionMeta && companionMeta.pid) || null;
    const uptime = uptimePid ? getProcessUptime(uptimePid) : null;

    // Bridge reachable (port 8766)
    const bridgeProbe = await new Promise(resolve=>{
      http.get({ hostname:"127.0.0.1", port:8766, path:"/status", timeout:1500 }, r=>{
        let d=""; r.on("data",c=>d+=c); r.on("end",()=>{ try{ const j=JSON.parse(d); resolve({ reachable:true, a11y: !!j.a11y, raw:j }); }catch{ resolve({ reachable:true, a11y:false, raw:d }); } });
      }).on("error", e=> resolve({ reachable:false, error:String(e.message || e).slice(0,300) })).end();
    });

    // Disk space /sdcard (df)
    let disk = null;
    try {
      const out = await shellExecRaw("df -h /sdcard 2>&1 | tail -n1", 4000, 4096);
      const line = (out.stdout || "").trim().split("\n").pop() || "";
      const parts = line.trim().split(/\s+/);
      // Expected: /dev/... size used avail use% mount
      disk = { raw: line, filesystem: parts[0]||null, size: parts[1]||null, used: parts[2]||null, avail: parts[3]||null, usePct: parts[4]||null, mount: parts[5]||"/sdcard" };
    } catch (e) { disk = { error: String(e).slice(0,300) }; }

    // Hub uptime (process.uptime)
    const hubUptime = Math.floor(process.uptime());

    // Active project from UI_STATE (projectId + project name)
    let activeProject = null;
    try {
      const s = loadProjectsStore();
      if (UI_STATE.projectId) {
        const p = findProject(s, UI_STATE.projectId);
        if (p) activeProject = { id: p.id, name: p.name, description: p.description || "", updatedAt: p.updatedAt || p.createdAt || null, sessionsCount: (p.sessions||[]).length };
        else activeProject = { id: UI_STATE.projectId, name: UI_STATE.project || null, note: "projectId not found in store" };
      } else if (UI_STATE.project) {
        activeProject = { id: null, name: UI_STATE.project, note: "legacy folder name, no projectId" };
      }
    } catch (e) { activeProject = { error: String(e).slice(0,300) }; }

    // Last voice command (most recent VOICE_LOG entry)
    const lastVoice = VOICE_LOG[0] || null;

    const opencodeReachable = !!(healthProbe && healthProbe.up && healthProbe.healthy);
    const bridgeReachable = !!bridgeProbe.reachable;

    return json(res, 200, ok({
      ok: opencodeReachable && bridgeReachable,
      opencode: { reachable: opencodeReachable, healthy: !!healthProbe.healthy, version: healthProbe.version || null, pid: servePids[0] || null, allPids: servePids, ownership },
      bridge: bridgeReachable ? { reachable: true, a11y: !!bridgeProbe.a11y } : { reachable: false, error: bridgeProbe.error || "bridge not reachable" },
      disk,
      uptime: { hub: hubUptime, opencode: uptime },
      sessionOwnership: ownership,
      activeProject,
      lastVoice,
      now: nowIso()
    }));
  }

  if(pathname==="/api/status" && req.method==="GET"){
    const rootCheck = await runShell("id; su -c id 2>&1 | head -1; getprop ro.build.version.release 2>&1; getprop ro.product.model 2>&1");
    // probe opencode
    const health = await new Promise(resolve=>{
      http.get({ hostname: OPENCODE_HOST, port: OPENCODE_PORT, path:"/global/health", timeout:2000 }, r=>{
        let d=""; r.on("data",c=>d+=c); r.on("end",()=>{ try{ resolve(JSON.parse(d)); }catch{ resolve({ raw:d, status:r.statusCode })} });
      }).on("error", e=> resolve({ error:String(e), reachable:false })).end();
    });
    return json(res, 200, { hub:"ok", hub_port: HUB_PORT, opencode: health, projects_root: PROJECTS_ROOT, projects: await listProjects(), root: rootCheck.stdout.slice(0,1200) });
  }
  if(pathname==="/api/device/shell" && req.method==="POST"){
    try{
      const raw = await readJsonBody(req, MAX_JSON_BODY);
      const { cmd, timeout, maxBuffer } = JSON.parse(raw||"{}");
      if(!cmd) return json(res, 400, { error:"cmd requerido" });
      console.log(`[shell] ${cmd.slice(0,400)} (maxBuffer ${((maxBuffer||MAX_BUFFER)/1024/1024).toFixed(1)}MB)`);
      const out = await runShell(cmd, timeout || 20000, maxBuffer || MAX_BUFFER);
      json(res, 200, out);
    }catch(e){ json(res, 500, { error:String(e), truncated: String(e).includes("maxBuffer") }); }
    return;
  }
  if(pathname==="/api/device/launch" && req.method==="POST"){
    try{ const raw=await readJsonBody(req); const { pkg, activity } = JSON.parse(raw||"{}"); if(!pkg) return json(res, 400, { error:"pkg requerido ej: com.bcp.bo.wallet" }); const cmd = activity ? `am start -n ${pkg}/${activity}` : `am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p ${pkg} 2>&1 || monkey -p ${pkg} -c android.intent.category.LAUNCHER 1 2>&1 || cmd package resolve-activity --brief -c android.intent.category.LAUNCHER ${pkg} 2>&1`; const out = await runShell(cmd); json(res, 200, { cmd, ...out }); }catch(e){ json(res,500,{error:String(e)}); }
    return;
  }
  if(pathname==="/api/device/tap" && req.method==="POST"){
    try{ const raw=await readJsonBody(req); const { x, y } = JSON.parse(raw||"{}"); const out = await runShell(`input tap ${parseInt(x)} ${parseInt(y)}`); json(res, 200, out); }catch(e){ json(res,500,{error:String(e)}); }
    return;
  }
  if(pathname==="/api/device/input" && req.method==="POST"){
    try{ const raw=await readJsonBody(req); const { text } = JSON.parse(raw||"{}"); const esc = String(text||"").replace(/ /g,"%s").replace(/&/g,"\\&"); const out = await runShell(`input text ${esc}`); json(res,200,out); }catch(e){ json(res,500,{error:String(e)}); }
    return;
  }
  if(pathname==="/api/device/key" && req.method==="POST"){
    try{ const raw=await readJsonBody(req); const { code } = JSON.parse(raw||"{}"); const out = await runShell(`input keyevent ${parseInt(code)}`); json(res,200,out); }catch(e){ json(res,500,{error:String(e)}); }
    return;
  }
  if(pathname==="/api/device/apps" && req.method==="GET"){
    const q = url.searchParams.get("q") || "";
    const out = await runShell(`pm list packages ${q ? `-3 | grep -i ${JSON.stringify(q)}` : ""} 2>&1 | head -n 200; pm list packages -3 2>&1 | head -n 200`);
    // parse
    const pkgs = out.stdout.split("\n").filter(l=>l.includes("package:")).map(l=>l.replace("package:","").trim()).slice(0,200);
    return json(res, 200, { pkgs, raw: out.stdout.slice(0,4000) });
  }
  // a11y + streaming-friendly (reenvía Base64 grande sin truncar)
  if(pathname==="/api/device/a11y" && req.method==="POST"){
    try{
      const raw = await readJsonBody(req, MAX_JSON_BODY);
      const body = JSON.parse(raw||"{}");
      // forwarding con buffer grande: usa bytes completos
      const bodyBytes = Buffer.from(raw, "utf8");
      const fwd = await new Promise(resolve=>{
        const pr = http.request({ hostname:"127.0.0.1", port:8766, path:"/a11y", method:"POST", headers:{"Content-Type":"application/json", "Content-Length": bodyBytes.length } }, r=>{
          const chunks=[]; r.on("data",c=>chunks.push(c)); r.on("end",()=> resolve({ ok:true, status:r.statusCode, body: Buffer.concat(chunks).toString("utf8") }));
        });
        pr.on("error", e=> resolve({ ok:false, error:String(e) }));
        pr.write(bodyBytes); pr.end();
      });
      if(fwd.ok) return send(res, fwd.status, fwd.body, {"Content-Type":"application/json"});
      if(body.action==="dump"){
        const out = await runShell(`uiautomator dump /sdcard/window_dump.xml && cat /sdcard/window_dump.xml 2>&1 | head -n 800`, 15000, MAX_BUFFER);
        return json(res, 200, { fallback:"uiautomator", ...out, note:"Para clicks por texto/id instala el APK Companion con AccessibilityService" });
      }
      return json(res, 501, { error:"APK Companion no conectado (puerto 8766). Instala el APK para control por AccessibilityService.", requested: body, forwardError: fwd.error });
    }catch(e){ json(res,500,{error:String(e)}); }
    return;
  }
  // nuevo: screenshot Base64 streaming-safe — PNG 1080x2400 ~ 800KB-2MB b64, 50MB buffer sobrado
  if(pathname==="/api/device/screenshot" && req.method==="GET"){
    const quality = Math.max(10, Math.min(95, parseInt(url.searchParams.get("quality")||"35")));
    const scale = Math.max(0.2, Math.min(1.0, parseFloat(url.searchParams.get("scale")||"0.5")));
    // pipe directo sin head -c truncador intermedio — maxBuffer controla el límite real
    const cmd = `nsenter -t 1 -m -- sh -c 'screencap -p 2>/dev/null | base64 -w 0 2>/dev/null || screencap -p 2>/dev/null | base64 2>/dev/null'`;
    const out = await runShell(cmd, 25000, MAX_BUFFER);
    const b64 = out.stdout.trim().replace(/\s/g,"");
    if(!b64 || b64.length < 1000){
      return json(res, 500, { error:"screenshot vacío", stdout: out.stdout.slice(0,800), stderr: out.stderr.slice(0,800), code: out.code });
    }
    if(url.searchParams.get("raw")==="1") return send(res, 200, b64, {"Content-Type":"text/plain", "Content-Length": String(Buffer.byteLength(b64))});
    return json(res, 200, { b64, len: b64.length, approx_bytes: Math.floor(b64.length*0.75), quality, scale, note: "b64 completo sin truncar (MAX_BUFFER 50MB). Para ahorrar tokens pasa ?quality=25&scale=0.4 y recorta cliente-side." });
  }
  if(pathname==="/api/device/screenshot" && req.method==="POST"){
    try{
      const raw = await readJsonBody(req, 2*1024*1024);
      const opts = JSON.parse(raw||"{}");
      const out = await runShell(`nsenter -t 1 -m -- sh -c 'screencap -p 2>/dev/null | base64 -w 0 2>/dev/null || screencap -p 2>/dev/null | base64 2>/dev/null'`, 25000, MAX_BUFFER);
      const b64 = out.stdout.trim().replace(/\s/g,"");
      return json(res, 200, { b64, len: b64.length, opts });
    }catch(e){ json(res,500,{error:String(e)}); }
    return;
  }

  // 2.5) Asistente Root: interpretar intención → acción NSENTER/8766
  // Vocabulario directo sin pasar por LLM si la orden ya es comando del sistema
  const DIRECT_INTENT = [
    { re: /^abre\s+(.+)/i, action: "launch", slot: "app" },
    { re: /^abrir\s+(.+)/i, action: "launch", slot: "app" },
    { re: /^inicia\s+(.+)/i, action: "launch", slot: "app" },
    { re: /^lanza\s+(.+)/i, action: "launch", slot: "app" },
    { re: /^(toma|haz|saca)\s+(una\s+)?captura(\s+de\s+pantalla)?/i, action: "screenshot" },
    { re: /^captura(\s+de\s+pantalla)?/i, action: "screenshot" },
    { re: /^(sube|aumenta)\s+(el\s+)?volumen/i, action: "volume_up" },
    { re: /^(baja|disminuye)\s+(el\s+)?volumen/i, action: "volume_down" },
    { re: /^(silencia|mutea|silenciar)/i, action: "volume_mute" },
    { re: /^(bloquea|bloquear)\s+(la\s+)?pantalla/i, action: "lock_screen" },
    { re: /^(desbloquea|desbloquear)/i, action: "unlock_screen" },
    { re: /^(activa|enciende|prende)\s+(el\s+)?(wifi|wi-?fi)/i, action: "wifi_on" },
    { re: /^(desactiva|apaga)\s+(el\s+)?(wifi|wi-?fi)/i, action: "wifi_off" },
    { re: /^(activa|enciende)\s+(el\s+)?bluetooth/i, action: "bluetooth_on" },
    { re: /^(desactiva|apaga)\s+(el\s+)?bluetooth/i, action: "bluetooth_off" },
    { re: /^pon\s+alarma/i, action: "set_alarm" },
    { re: /^llama\s+a\s+(.+)/i, action: "call", slot: "contact" },
    { re: /^manda\s+(?:un\s+)?mensaje\s+a\s+(.+?)\s*[:\-]\s*(.+)/i, action: "whatsapp_send", slots: ["contact","text"] },
    { re: /^(manda|env[ií]a)\s+(?:un\s+)?(?:whatsapp|mensaje)\s+a\s+(.+?)\s*[:\-]\s*(.+)/i, action: "whatsapp_send", slots: ["contact","text"] },
    { re: /^(manda|env[ií]a)\s+(?:un\s+)?(?:whatsapp|mensaje)\s+a\s+(.+)/i, action: "whatsapp_send_prompt", slot: "contact" },
  ];
  const APP_ALIASES = {
    "whatsapp": "com.whatsapp",
    "wa": "com.whatsapp",
    "telegram": "org.telegram.messenger",
    "youtube": "com.google.android.youtube",
    "chrome": "com.android.chrome",
    "camara": "com.android.camera2",
    "cámara": "com.android.camera2",
    "galeria": "com.google.android.apps.photos",
    "galería": "com.google.android.apps.photos",
    "fotos": "com.google.android.apps.photos",
    "yape": "com.bcp.bo.wallet",
    "bcp": "com.bcp.bo.wallet",
    "proton": "ch.protonmail.android",
    "protonmail": "ch.protonmail.android",
    "gmail": "com.google.android.gm",
    "maps": "com.google.android.apps.maps",
    "spotify": "com.spotify.music",
    "facebook": "com.facebook.katana",
    "instagram": "com.instagram.android",
    "tiktok": "com.zhiliaoapp.musically",
  };
  function directIntentOf(text){
    const t = String(text||"").trim();
    for(const pat of DIRECT_INTENT){
      const m = t.match(pat.re);
      if(m){
        const slots={};
        if(pat.slot) slots[pat.slot] = (m[1]||m[m.length-1]||"").trim();
        if(pat.slots) pat.slots.forEach((k,i)=> slots[k] = (m[i+1]||"").trim());
        return { action: pat.action, slots, raw: t, via: "direct_regex" };
      }
    }
    return null;
  }
  function appPackageFor(name){
    const k = String(name||"").toLowerCase().trim();
    if(!k) return null;
    if(k.includes(".")) return k;
    if(APP_ALIASES[k]) return APP_ALIASES[k];
    for(const [alias,pkg] of Object.entries(APP_ALIASES)){
      if(k.includes(alias)) return pkg;
    }
    return k.replace(/\s+/g, ".");
  }

  // Contactos: query via content provider (READ_CONTACTS) si el permiso existe; fallback vacío
  async function queryContacts(q){
    if(!q || q.trim().length<2) return [];
    // content query contacts — nsenter necesario
    const safe = q.replace(/'/g, "''").slice(0,60);
    const cmd = `content query --uri content://com.android.contacts/contacts --where "display_name LIKE '%${safe}%'" --projection display_name:phone 2>&1 | head -n 20`;
    const out = await runShell(cmd, 8000, 1024*1024);
    // parse líneas tipo "Row: 3 display_name=Juan, phone=..." — heurística
    const lines = out.stdout.split("\n").filter(l=> l.includes("display_name") || l.includes("Row:"));
    const hits=[];
    for(const line of lines){
      const nameMatch = line.match(/display_name=([^,]+)/i);
      const rowMatch = line.match(/Row:\s*\d+\s*([^,=]+)/);
      const name = (nameMatch?.[1] || rowMatch?.[1] || "").trim();
      // intenta obtener teléfono en segunda query si name hallado
      if(name && name.length>1){
        const cmd2 = `content query --uri content://com.android.contacts/data --where "display_name='${name.replace(/'/g,"''")}'" 2>&1 | grep -i -oE "\\+?[0-9][0-9 \\-]{6,}[0-9]" | head -n 3`;
        const out2 = await runShell(cmd2, 6000, 1024*1024);
        const phones = out2.stdout.split("\n").map(s=> s.replace(/[\s\-]/g,"").trim()).filter(s=> s.length>=8);
        hits.push({ name, phones: [...new Set(phones)].slice(0,2), source: "contacts" });
      }
      if(hits.length>=5) break;
    }
    // si no hubo hits por content, al menos devuelve el q como contacto tentativo
    if(hits.length===0){
      const normalized = q.replace(/[^+0-9a-zA-Z ]/g,"").trim();
      if(/^\+?[0-9 ]{8,}$/.test(normalized)) hits.push({ name: normalized, phones:[normalized.replace(/ /g,"")], source:"raw_phone" });
    }
    return hits.slice(0,5);
  }

  async function executeAssistantAction(action, slots={}, opts={}){
    const started = Date.now();
    let result={};
    // para acciones que piden confirmación, no ejecutar directo
    if(action==="whatsapp_send" && slots.contact && !slots.phone && !opts.forcePhone){
      const hits = await queryContacts(slots.contact);
      if(hits.length===0){
        return { ok:false, need:"phone", message:`No encontré "${slots.contact}" en contactos. Indica número con código país (ej: +51999...).`, action, slots };
      }
      if(hits.length>1 && !opts.selectedIndex && hits.some(h=> h.phones.length===0 || h.phones.length>1)){
        return { ok:false, type:"disambiguation", kind:"contacts", action, slots, options: hits.map((h,i)=> ({ idx:i, name:h.name, phones:h.phones, label:`${h.name}${h.phones.length? ` — ${h.phones[0]}`:""}` })), message:`Varios contactos coinciden con "${slots.contact}". Elige uno:` };
      }
      const chosen = opts.selectedIndex!=null ? hits[opts.selectedIndex] : hits[0];
      const phone = chosen.phones[0] || slots.phone;
      if(!phone) return { ok:false, need:"phone", message:`Contacto "${chosen.name}" sin teléfono. Indica número.`, action, slots, chosen };
      slots.phone = phone.replace(/[^+0-9]/g,"");
      slots.contactName = chosen.name;
    }
    // también para launch ambiguo: si el nombre no mapea a paquete conocido, pide desambiguación
    if(action==="launch" && slots.app){
      const pkg = appPackageFor(slots.app);
      const out = await runShell(`pm list packages 2>&1 | grep -i -E "${pkg.replace(/\./g,"\\.")}" 2>&1 | head -n 5; echo "---"; pm list packages 2>&1 | grep -i "${String(slots.app).slice(0,12).replace(/"/g,"")}" 2>&1 | head -n 5`, 6000, 1024*1024);
      const pkgs = out.stdout.split("\n").filter(l=> l.includes("package:")).map(l=> l.replace("package:","").trim());
      if(pkgs.length===0){
        return { ok:false, type:"disambiguation", kind:"app", action, slots, options: [], message:`No encontré app "${slots.app}". ¿Quisiste decir WhatsApp, Yape, Cámara, Chrome…?` };
      }
      if(pkgs.length>1){
        const exact = pkgs.find(p=> p===pkg);
        if(exact) slots.pkg = exact; else {
          return { ok:false, type:"disambiguation", kind:"app", action, slots, options: pkgs.slice(0,4).map((p,i)=> ({ idx:i, pkg:p, label:p })), message:`Varias apps coinciden con "${slots.app}". Elige:` };
        }
      } else {
        slots.pkg = pkgs[0] || pkg;
      }
      const pkgFinal = slots.pkg || pkg;
      const amCmd = `am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p ${pkgFinal} 2>&1 || monkey -p ${pkgFinal} -c android.intent.category.LAUNCHER 1 2>&1 || cmd package resolve-activity --brief -c android.intent.category.LAUNCHER ${pkgFinal} 2>&1`;
      const r = await runShell(amCmd, 8000);
      const ok = (r.stdout.includes("Events injected") || r.stdout.includes("Starting:"));
      result = { ok, pkg: pkgFinal, stdout: r.stdout.slice(0,1200), stderr:r.stderr.slice(0,400) };
      return { ok, action, slots, result, elapsedMs: Date.now()-started };
    }

    switch(action){
      case "launch":
        return { ok:false, type:"disambiguation", kind:"app", action, slots, options:[], message:"App no especificada" };
      case "screenshot": {
        const r = await runShell(`nsenter -t 1 -m -- sh -c 'screencap -p 2>/dev/null | base64 -w 0 2>/dev/null || screencap -p 2>/dev/null | base64 2>/dev/null'`, 25000, MAX_BUFFER);
        const b64 = r.stdout.trim().replace(/\s/g,"");
        if(b64.length<1000) return { ok:false, action, error:"screenshot vacío", stdout:r.stdout.slice(0,400) };
        return { ok:true, action, result:{ b64, len:b64.length, approx_bytes: Math.floor(b64.length*0.75) }, elapsedMs: Date.now()-started };
      }
      case "volume_up": {
        const r = await runShell(`input keyevent 24; input keyevent 24 2>&1; echo vol_up; dumpsys audio 2>&1 | grep -i "STREAM_MUSIC.*level" | head -n 2`, 5000);
        return { ok:true, action, result:{ stdout:r.stdout.slice(0,800) }, elapsedMs: Date.now()-started };
      }
      case "volume_down": {
        const r = await runShell(`input keyevent 25; input keyevent 25 2>&1; echo vol_down`, 5000);
        return { ok:true, action, result:{ stdout:r.stdout.slice(0,600) }, elapsedMs: Date.now()-started };
      }
      case "volume_mute": {
        const r = await runShell(`input keyevent 164 2>&1; echo mute; input keyevent 164 2>&1; echo mute2`, 5000);
        return { ok:true, action, result:{ stdout:r.stdout.slice(0,600) }, elapsedMs: Date.now()-started };
      }
      case "lock_screen": {
        const r = await runShell(`input keyevent 26 2>&1; echo lock`, 4000);
        return { ok:true, action, result:{ stdout:r.stdout.slice(0,400) }, elapsedMs: Date.now()-started };
      }
      case "unlock_screen": {
        const r = await runShell(`input keyevent 82 2>&1; sleep 0.3; input swipe 540 1800 540 600 300 2>&1; echo unlock_try`, 6000);
        return { ok:true, action, result:{ stdout:r.stdout.slice(0,600) }, elapsedMs: Date.now()-started };
      }
      case "wifi_on": { const r = await runShell(`cmd wifi set-wifi-enabled enabled 2>&1; svc wifi enable 2>&1; echo wifi_on`, 6000); return { ok:true, action, result:{ stdout:r.stdout.slice(0,600)} , elapsedMs: Date.now()-started }; }
      case "wifi_off": { const r = await runShell(`cmd wifi set-wifi-enabled disabled 2>&1; svc wifi disable 2>&1; echo wifi_off`, 6000); return { ok:true, action, result:{ stdout:r.stdout.slice(0,600)} , elapsedMs: Date.now()-started }; }
      case "bluetooth_on": { const r = await runShell(`cmd bluetooth_manager enable 2>&1; svc bluetooth enable 2>&1; echo bt_on`, 6000); return { ok:true, action, result:{ stdout:r.stdout.slice(0,600)} , elapsedMs: Date.now()-started }; }
      case "bluetooth_off": { const r = await runShell(`cmd bluetooth_manager disable 2>&1; svc bluetooth disable 2>&1; echo bt_off`, 6000); return { ok:true, action, result:{ stdout:r.stdout.slice(0,600)} , elapsedMs: Date.now()-started }; }
      case "whatsapp_send_prompt": {
        // si el usuario dijo "manda whatsapp a Juan" sin texto, pide texto
        const hits = await queryContacts(slots.contact||"");
        if(hits.length===0) return { ok:false, need:"contact", message:`No encontré "${slots.contact}". Indica nombre o número.`, action, slots };
        if(hits.length>1) return { ok:false, type:"disambiguation", kind:"contacts", action:"whatsapp_send", slots:{...slots, text:""}, options: hits.map((h,i)=> ({ idx:i, name:h.name, phones:h.phones, label:`${h.name}${h.phones[0]? ` — ${h.phones[0]}`:""}` })), message:`Varios contactos coinciden con "${slots.contact}". Elige:` };
        return { ok:false, type:"disambiguation", kind:"whatsapp_text", action:"whatsapp_send", slots:{ contact: hits[0].name, phone: hits[0].phones[0]||"", text:"" }, options:[], message:`¿Qué mensaje le envío a ${hits[0].name}${hits[0].phones[0]? ` (${hits[0].phones[0]})`:""}?` };
      }
      case "whatsapp_send": {
        const phone = String(slots.phone||"").replace(/[^0-9+]/g,"").replace(/^\+0/,"+");
        const text = String(slots.text||"").trim();
        if(!phone) return { ok:false, need:"phone", message:"Falta teléfono", action, slots };
        if(!text) return { ok:false, need:"text", message:"Falta texto del mensaje", action, slots };
        const encoded = encodeURIComponent(text);
        const uri = `https://api.whatsapp.com/send?phone=${phone.replace("+","")}&text=${encoded}`;
        // am start VIEW con uri — abre WhatsApp con chat listo; si a11y está, puede auto-enviar
        let r = await runShell(`am start -a android.intent.action.VIEW -d '${uri}' 2>&1 | head -n 20; echo WA_VIEW`, 8000);
        const ok = r.stdout.includes("Starting:") || r.stdout.includes("WA_VIEW");
        // auto-enviar via a11y polling loop — retry clickText("Enviar") up to 5 times with 600ms intervals (spec 5)
        let autoSend = { tried:false, attempts: 0, success: false };
        if(ok){
          autoSend.tried = true;
          let lastResult = null;
          for (let attempt = 1; attempt <= 5; attempt++) {
            await new Promise(r2=> setTimeout(r2, 600));
            const fwd = await new Promise(resolve=>{
              const pr = http.request({ hostname:"127.0.0.1", port:8766, path:"/a11y", method:"POST", headers:{"Content-Type":"application/json"} }, rr=>{
                const c=[]; rr.on("data",x=> c.push(x)); rr.on("end",()=> resolve({ ok:true, status: rr.statusCode, body: Buffer.concat(c).toString("utf8") }));
              });
              pr.on("error", e=> resolve({ ok:false, error:String(e) }));
              pr.write(JSON.stringify({ action:"clickText", text:"Enviar" }));
              pr.end();
            });
            autoSend.attempts = attempt;
            if (!fwd.ok) {
              lastResult = { ok: false, error: fwd.error, attempt };
            } else {
              try { lastResult = JSON.parse(fwd.body); } catch { lastResult = { body: fwd.body, attempt }; }
              // Check if click succeeded — CompanionService returns {ok:true} when click found
              const clickedOk = lastResult && (lastResult.ok === true || lastResult.clicked === true || String(lastResult).includes('"ok":true'));
              // Also consider status 200 as potential success — but verify ok field
              if (fwd.status === 200 && (lastResult.ok === true || lastResult.status === 200)) {
                autoSend.success = true;
                autoSend.result = { ...lastResult, attempts: attempt, success: true };
                break;
              }
              // Fallback: if a11y returned 200 with any body, treat as success on last attempt only if no error
              if (fwd.status === 200 && !lastResult.error) {
                autoSend.success = lastResult.ok !== false;
                autoSend.result = { ...lastResult, attempts: attempt, success: autoSend.success };
                if (autoSend.success) break;
              }
              autoSend.result = { ...lastResult, attempts: attempt, success: false };
            }
            // If bridge offline (fwd.ok false with ECONNREFUSED), stop retrying and hint manual
            if (!fwd.ok && String(fwd.error || "").includes("ECONNREFUSED")) {
              autoSend.result = { error: fwd.error, hint:"Bridge 8766 offline — pulsa Enviar manualmente", attempts: attempt, success: false };
              break;
            }
          }
          if (!autoSend.success && !autoSend.result) {
            autoSend.result = { error: "max retries reached", hint:"WhatsApp abierto — pulsa Enviar manualmente si no se envió solo", attempts: 5, success: false };
          } else if (!autoSend.success && autoSend.result && !autoSend.result.hint) {
            autoSend.result.hint = "WhatsApp abierto — pulsa Enviar manualmente si no se envió solo";
          }
        }
        return { ok, action, slots, result:{ uri, stdout:r.stdout.slice(0,800), autoSend }, elapsedMs: Date.now()-started };
      }
      case "call": {
        const hits = await queryContacts(slots.contact||"");
        if(hits.length===0) return { ok:false, need:"contact", message:`No encontré "${slots.contact}"`, action, slots };
        if(hits.length>1) return { ok:false, type:"disambiguation", kind:"contacts", action, slots, options: hits.map((h,i)=> ({ idx:i, name:h.name, phones:h.phones, label:`${h.name}${h.phones[0]? ` — ${h.phones[0]}`:""}` })), message:`Varios contactos para "${slots.contact}". Elige:` };
        const phone = hits[0].phones[0] || "";
        if(!phone) return { ok:false, need:"phone", message:`Contacto ${hits[0].name} sin teléfono`, action, slots };
        const r = await runShell(`am start -a android.intent.action.CALL -d tel:${phone.replace(/[^+0-9]/g,"")} 2>&1 | head -n 10; echo CALL`, 6000);
        return { ok:true, action, slots:{...slots, phone, contactName:hits[0].name}, result:{ stdout:r.stdout.slice(0,600)}, elapsedMs: Date.now()-started };
      }
      default: {
        return { ok:false, error:`acción no soportada: ${action}`, action, slots };
      }
    }
  }

  if(pathname==="/api/assistant/intent" && req.method==="POST"){
    try{
      const raw = await readJsonBody(req, 64*1024);
      const { text, lang } = JSON.parse(raw||"{}");
      if(!text || !String(text).trim()) return json(res, 400, { error:"text requerido" });
      const direct = directIntentOf(text);
      if(direct){
        // para acciones que necesitan parámetros faltantes, delegar a execute con disambiguation
        if(direct.action==="whatsapp_send_prompt" || (direct.action==="whatsapp_send" && !direct.slots.text)){
          // responde disambiguation inmediata sin llamar execute
          const execRes = await executeAssistantAction(direct.action, direct.slots);
          if(execRes.type==="disambiguation") return json(res, 200, execRes);
          return json(res, 200, direct);
        }
        // si launch tiene app muy genérica, verificar si necesita disambiguation
        if(direct.action==="launch"){
          const check = await executeAssistantAction(direct.action, direct.slots);
          if(check.type==="disambiguation" && (check.options||[]).length>0) return json(res, 200, check);
          return json(res, 200, { ...direct, resolved: check });
        }
        return json(res, 200, direct);
      }
      // ambiguo: pide al LLM que clasifique (si opencode está sano, no gastamos tokens aquí — devolvemos estructura para que frontend llame a LLM)
      return json(res, 200, { action:"llm_classify", slots:{ text: String(text).slice(0,600) }, hint: "Texto ambiguo — envíalo a opencode como mensaje con contexto assistant_mode. Si el LLM devuelve JSON {action, slots}, reenvía a /api/assistant/execute.", raw: text, lang: lang||"es" });
    }catch(e){ return json(res, 500, { error:String(e).slice(0,600) }); }
  }
  if(pathname==="/api/assistant/execute" && req.method==="POST"){
    try{
      const raw = await readJsonBody(req, 64*1024);
      const { action, slots, options, selectedIndex, forcePhone } = JSON.parse(raw||"{}");
      if(!action) return json(res, 400, { error:"action requerido" });
      // chip en progreso: log + header para que pill lo detecte
      console.log(`[assistant] execute ${action} ${JSON.stringify(slots||{}).slice(0,300)}`);
      res.setHeader("X-Assistant-Action", action);
      const r = await executeAssistantAction(action, slots||{}, { selectedIndex: selectedIndex ?? options?.selectedIndex, forcePhone: forcePhone ?? !!slots?.phone });
      // si es disambiguation, responde 200 con type para que frontend renderice tarjetas
      const code = r.ok===false && r.type==="disambiguation" ? 200 : (r.ok ? 200 : 400);
      return json(res, code, r);
    }catch(e){ return json(res, 500, { error:String(e).slice(0,800) }); }
  }

  // 3) static
  let fp = path.join(__dirname, "public", pathname==="/" ? "index.html" : pathname.slice(1));
  // path traversal guard
  if(!fp.startsWith(path.join(__dirname,"public"))) return send(res,403,"forbidden");
  if(fs.existsSync(fp) && fs.statSync(fp).isDirectory()) fp = path.join(fp, "index.html");
  if(!fs.existsSync(fp)){
    // spa fallback para rutas /app etc: serve index
    const idx = path.join(__dirname,"public","index.html");
    if(fs.existsSync(idx)) fp = idx; else return json(res,404,{error:"not found", path:pathname});
  }
  const ext = path.extname(fp).toLowerCase();
  const mime = MIME[ext] || "application/octet-stream";
  // simple etag/cache disable for dev
  res.writeHead(200, {"Content-Type": mime, "Access-Control-Allow-Origin":"*", "Cache-Control":"no-cache"});
  fs.createReadStream(fp).pipe(res);
});

server.on('error', e => {
  if (String(e.code) === 'EADDRINUSE') {
    console.error(`[hub] puerto ${HUB_PORT} ocupado — deja el existente y salgo con 0`);
    process.exit(0);
  }
  console.error('[hub] server error', e);
});
server.on('clientError', (err, socket) => {
  console.error('[hub] clientError', String(err).slice(0,300));
  try { socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n'); } catch (_) {}
});
server.keepAliveTimeout = 125000;
server.headersTimeout = 130000;
server.requestTimeout = 135000;
server.maxHeadersCount = 100;
server.listen(HUB_PORT, "0.0.0.0", async ()=>{
  console.log(`\n[opencode-companion] hub listening http://0.0.0.0:${HUB_PORT}`);
  console.log(`  local  : http://127.0.0.1:${HUB_PORT}`);
  console.log(`  proxy  : /opencode/* -> http://${OPENCODE_HOST}:${OPENCODE_PORT}`);
  console.log(`  api    : /api/status  /api/device/*`);
  console.log(`  session: /api/system/status (ownership)  /api/system/session-info (pid/uptime)`);
  console.log(`  pid    : ${process.pid}  node ${process.version}  keepAlive 125s (multimodal streaming)`);
  // Non-destructive startup probe — spec (1): detect existing serve on startup without launching
  // If health already true, we adopt it as termux-native (or companion-owned if prior meta exists) and do NOT auto-launch.
  try {
    const h = await probeOpencodeHealth();
    clearStaleCompanionMetaIfNeeded();
    const pids = scanServePids();
    const ownership = classifyOwnership(h, pids);
    if (h.healthy) {
      console.log(`  opencode: existing ${ownership} session detected — health OK ${JSON.stringify(h).slice(0,120)} pids=${pids.join(",")||"unknown"} — not launching (non-destructive)`);
    } else {
      console.log(`  opencode: no session (ownership none) — not auto-launching on startup; use POST /api/system/start when none`);
    }
    console.log(`  sessionOwnership: ${ownership} (hub startup)`);
  } catch (e) {
    console.log(`  opencode: startup probe error ${e.message}`);
  }

  // print LAN IPs via shell (nsenter-aware)
  runShell(`nsenter -t 1 -m -- ip addr 2>&1 | grep -oE '192\\.168\\.[0-9]+\\.[0-9]+' | head -5; nsenter -t 1 -m -- getprop 2>&1 | grep -oE '192\\.168\\.[0-9]+\\.[0-9]+' | head -5; ip addr 2>&1 | grep -oE '192\\.168\\.[0-9]+\\.[0-9]+' | head -5; getprop 2>&1 | grep -oE '192\\.168\\.[0-9]+\\.[0-9]+' | head -5`).then(o=>{
    const ips = [...new Set((o.stdout.match(/192\.168\.\d+\.\d+/g) || []))];
    if(ips.length) console.log(`  LAN    : ${ips.map(ip=>`http://${ip}:${HUB_PORT}`).join("  |  ")}`);
    else console.log(`  LAN    : (no se detectó IP, usa el IP de WiFi en Ajustes > Acerca del teléfono)`);
    console.log("");
  });
});
