// providers.js — Universal AI Coding Agent Provider Abstraction
// Robust adapter layer for OpenCode (HTTP serve mode) and Antigravity (agy CLI print/json mode)
// Hard 90s timeouts, connection leak prevention, zombie process cleanup, atomic concurrency.

import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { spawn } from "node:child_process";
// BACKLOG (F0-F2): llamadas directas a stdout/stderr -> logger del hub (mismo
// formato/sink que el resto). Nivel coherente: log->info, warn->warn, error->error.
import { createLogger } from "./src/core/logger.js";
// BACKLOG (F0-F2, unificación): FileMutex/fileMutex/atomic*/loadProjectsStore viven
// SOLO en src/core/storage.js. Se importan y se re-exportan con las MISMAS firmas
// para no romper a los consumidores históricos (server.js importa fileMutex/
// atomic*/normalizeMessage de ESTE fichero; aquí sólo se usan internamente).
import {
  FileMutex,
  fileMutex,
  atomicReadFileSync,
  atomicWriteFileSync,
  loadProjectsStore
} from "./src/core/storage.js";

export { FileMutex, fileMutex, atomicReadFileSync, atomicWriteFileSync };

const log = createLogger("providers");

// ==========================================
// Message Normalizer (Strict Typing)
// Conforms to docs/FRONTEND_CONTRACT.md and Android Models.kt
// ==========================================

export function normalizeMessage(raw, sessionId = "", index = 0) {
  if (!raw || typeof raw !== "object") {
    const text = String(raw || "");
    const now = Date.now();
    return {
      role: "assistant",
      text,
      info: {
        id: `msg_${sessionId || "hub"}_${now}_${index}`,
        role: "assistant",
        timestamp: now,
        time: { created: now },
        status: "SENT",
        deliveryStatus: "SENT"
      },
      parts: [{ id: `prt_${now}_${index}`, type: "text", text }]
    };
  }

  // Extract role
  let role = raw.role || raw.info?.role || (raw.type === "user" ? "user" : "assistant");
  if (role !== "user" && role !== "assistant") role = "assistant";

  // Extract timestamp
  const timestamp =
    raw.info?.timestamp ||
    raw.info?.time?.created ||
    raw.timestamp ||
    raw.time?.created ||
    Date.now();

  // Extract text
  let text = "";
  if (typeof raw.text === "string") {
    text = raw.text;
  } else if (typeof raw.response === "string") {
    text = raw.response;
  } else if (Array.isArray(raw.parts)) {
    text = raw.parts
      .filter((p) => p && (p.type === "text" || !p.type) && typeof p.text === "string")
      .map((p) => p.text)
      .join("\n");
  }

  // Extract / normalize parts
  let parts = [];
  if (Array.isArray(raw.parts) && raw.parts.length > 0) {
    parts = raw.parts.map((p, pIdx) => ({
      id: p.id || `prt_${timestamp}_${pIdx}`,
      type: p.type || "text",
      text: p.text || (typeof p === "string" ? p : ""),
      ...(p.mime ? { mime: p.mime } : {}),
      ...(p.filename ? { filename: p.filename } : {}),
      ...(p.url ? { url: p.url } : {}),
      ...(p.tool ? { tool: p.tool } : {}),
      ...(p.callID ? { callID: p.callID } : {}),
      ...(p.state ? { state: p.state } : {})
    }));
  } else {
    parts = [{ id: `prt_${timestamp}_0`, type: "text", text }];
  }

  const id = raw.info?.id || raw.id || `msg_${sessionId || "hub"}_${timestamp}_${index}`;
  const status = raw.info?.status || raw.info?.deliveryStatus || "SENT";

  const normalized = {
    role,
    text,
    info: {
      id,
      role,
      timestamp,
      time: { created: timestamp },
      status,
      deliveryStatus: status
    },
    parts
  };

  if (raw._agyMeta) {
    normalized._agyMeta = raw._agyMeta;
  }

  return normalized;
}

// ==========================================
// Base Provider Adapter Interface
// ==========================================

export class BaseProviderAdapter {
  constructor(id, name, type = "cli") {
    this.id = id;
    this.name = name;
    this.type = type; // "serve" | "cli"
  }

  async isHealthy() {
    throw new Error(`isHealthy() not implemented on ${this.name}`);
  }

  async listSessions() {
    throw new Error(`listSessions() not implemented on ${this.name}`);
  }

  async createSession(opts = {}) {
    throw new Error(`createSession() not implemented on ${this.name}`);
  }

  async getMessages(sessionId, opts = {}) {
    throw new Error(`getMessages() not implemented on ${this.name}`);
  }

  async sendMessage(sessionId, payload = {}, opts = {}) {
    throw new Error(`sendMessage() not implemented on ${this.name}`);
  }

  async renameSession(sessionId, title) {
    return { id: sessionId, title };
  }

  async deleteSession(sessionId) {
    return { id: sessionId, deleted: true };
  }

  async listModels() {
    return [];
  }
}

// ==========================================
// OpenCode Adapter (HTTP Serve Mode)
// ==========================================

export class OpencodeAdapter extends BaseProviderAdapter {
  constructor(options = {}) {
    super("opencode", "OpenCode", "serve");
    this.host = options.host || "127.0.0.1";
    this.port = options.port || 4096;
    this.getSystemContextBlock = options.getSystemContextBlock || (() => null);
    this._modelsCache = null;
    this._modelsCacheTime = 0;
    this._fetchingModels = false;
    // ---- F6 (transición OpenCode v1 → v2) -----------------------------------
    // La API v2 vive bajo /api/* y exige HTTP Basic opencode:<password>. La
    // contraseña rota en cada arranque del serve y se anota en opencode.log
    // ("server password ..."), así que se lee el final del log con TTL corto y
    // se invalida ante un401 para recapturar rotaciones sin reiniciar el hub.
    this.logPath = options.logPath || null;
    this._pw = null;
    this._pwTime = 0;
    this._modelRefs = new Map();   // alias(id/modelID/providerID/id) -> Model.Ref
    this._lastModel = new Map();   // sessionId -> "providerID/id" ya activo
    this._instrHash = new Map();   // sessionId -> hash del contexto inyectado
    // Pre-warm models cache in background
    setTimeout(() => { this.listModels().catch(() => {}); }, 1500);
  }

  _readPassword() {
    const now = Date.now();
    if (this._pw && now - (this._pwTime || 0) < 3000) return this._pw;
    try {
      if (this.logPath && fs.existsSync(this.logPath)) {
        const fd = fs.openSync(this.logPath, "r");
        try {
          const size = fs.fstatSync(fd).size;
          const len = Math.min(size, 8192);
          const buf = Buffer.alloc(len);
          fs.readSync(fd, buf, 0, len, Math.max(0, size - len));
          const matches = [...buf.toString("utf8").matchAll(/server password (\S+)/g)];
          if (matches.length) this._pw = matches[matches.length - 1][1];
        } finally {
          fs.closeSync(fd);
        }
      }
    } catch (e) {
      log.warn("[opencode] readPassword error", { err: e.message });
    }
    this._pwTime = now;
    return this._pw || null;
  }

  _invalidatePassword() {
    this._pw = null;
    this._pwTime = 0;
  }

  _authHeader() {
    const pw = this._readPassword();
    return pw ? { Authorization: `Basic ${Buffer.from(`opencode:${pw}`).toString("base64")}` } : {};
  }

  // Cliente HTTP genérico de la API v2: auth, timeout, abort del cliente y
  // reintentos ante rotación de contraseña (401 una sola vez).
  async _v2(pathname, { method = "GET", body = null, timeoutMs = 10000, signal = null, retried = false } = {}) {
    const headers = { ...(body ? { "Content-Type": "application/json" } : {}), ...this._authHeader() };
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), timeoutMs);
    const onAbort = () => ctrl.abort();
    if (signal) {
      if (signal.aborted) {
        clearTimeout(timer);
        throw new Error("OpenCode request aborted by client");
      }
      signal.addEventListener("abort", onAbort, { once: true });
    }
    let res;
    try {
      res = await fetch(`http://${this.host}:${this.port}${pathname}`, {
        method,
        headers,
        body: body ? JSON.stringify(body) : undefined,
        signal: ctrl.signal
      });
    } catch (e) {
      clearTimeout(timer);
      if (signal) signal.removeEventListener("abort", onAbort);
      if (signal && signal.aborted) throw new Error("OpenCode request aborted by client");
      throw new Error(`OpenCode v2 ${method} ${pathname} failed: ${e.message}`);
    }
    clearTimeout(timer);
    if (signal) signal.removeEventListener("abort", onAbort);
    if (res.status === 401 && !retried) {
      this._invalidatePassword();
      return this._v2(pathname, { method, body, timeoutMs, signal, retried: true });
    }
    const text = await res.text();
    let json = null;
    if (text) {
      try { json = JSON.parse(text); } catch (_) {}
    }
    if (!json && text && text.trimStart().startsWith("<")) {
      throw new Error("OpenCode devolvió HTML en API v2 (¿serve sin /api? versión no soportada)");
    }
    return { status: res.status, ok: res.ok, json, text };
  }

  async isHealthy() {
    try {
      const r = await this._v2("/api/info", { timeoutMs: 2500 });
      if (r.ok && r.json && r.json.version) return { up: true, healthy: true, version: String(r.json.version) };
      if (r.status === 401) return { up: true, healthy: false, version: null, error: "auth" };
      return { up: r.status < 500, healthy: false, version: null, error: `status ${r.status}` };
    } catch (_) {
      // Sin auth o caído: si la raíz sirve la SPA de OpenCode, el serve está vivo.
      try {
        const ctrl = new AbortController();
        const t = setTimeout(() => ctrl.abort(), 2000);
        const res = await fetch(`http://${this.host}:${this.port}/`, { signal: ctrl.signal });
        clearTimeout(t);
        const html = await res.text();
        const up = res.ok && html.includes("<title>OpenCode</title>");
        return { up, healthy: false, version: up ? "v2" : null, error: up ? "auth" : "down" };
      } catch (e) {
        return { up: false, healthy: false, error: e.message };
      }
    }
  }

  async listSessions() {
    // F6: GET /api/session (session.list) — devuelve {data:[Session.Info]} con
    // title real de OpenCode, projectID y location.directory (para el merge por carpeta).
    const r = await this._v2("/api/session", { timeoutMs: 8000 });
    if (!r.ok || !r.json) throw new Error(`OpenCode v2 GET /api/session -> ${r.status}${r.text ? `: ${String(r.text).slice(0, 120)}` : ""}`);
    const payload = r.json;
    const rawList = Array.isArray(payload) ? payload : (payload.sessions || payload.data || []);
    // F6: el panel de Chats sólo muestra sesiones MANUALES. Las sub-sesiones de
    // OpenCode (parentID != null y sin fork explícito) las crean los sub-agentes
    // del harness (agent general/explore, p. ej. "Recon…", "F5 release…") y no
    // deben visualizarse; un fork explícito (campo fork) sí cuenta como manual.
    // El filtro vive en la FUENTE, así que cubre listAllSessions (panel Chats),
    // el overlay de títulos y el merge por carpeta de proyectos.
    return rawList
      .filter((s) => !(s.parentID && !s.fork))
      .map((s) => ({
      id: s.id || s.ID || "",
      title: s.title || s.name || s.id || "untitled",
      createdAt: s.time?.created
        ? new Date(s.time.created).toISOString()
        : (s.createdAt || s.created_at || new Date().toISOString()),
      updatedAt: s.time?.updated
        ? new Date(s.time.updated).toISOString()
        : (s.updatedAt || s.updated_at || s.createdAt || new Date().toISOString()),
      provider: "opencode",
      projectId: s.projectID || null,
      directory: s.location?.directory || null,
      raw: s
    }));
  }

  async createSession(opts = {}) {
    // F6: POST /api/session (session.create) — acepta {title} y responde {data:Session.Info}.
    const title = opts.title || `session:${Date.now().toString(36)}`;
    const r = await this._v2("/api/session", { method: "POST", body: { title }, timeoutMs: 8000 });
    const data = r.json && (r.json.data || r.json);
    if (!r.ok || !data || !data.id) {
      throw new Error(`Failed to create opencode session: ${r.status} ${r.text ? String(r.text).slice(0, 200) : ""}`.trim());
    }
    return {
      id: data.id,
      title: data.title || title,
      createdAt: data.time?.created ? new Date(data.time.created).toISOString() : new Date().toISOString(),
      provider: "opencode",
      // Sin envoltorio {data}: el hub hace ...created.raw y un raw.envuelto
      // pisaría el envelope {ok,data} de la respuesta.
      raw: data
    };
  }

  async renameSession(sessionId, title) {
    // F6: PATCH /api/session/:id (session.update) — {title} y responde 204 sin body.
    const r = await this._v2(`/api/session/${encodeURIComponent(sessionId)}`, {
      method: "PATCH",
      body: { title },
      timeoutMs: 8000
    });
    if (!r.ok) throw new Error(`OpenCode rename failed: ${r.status}${r.text ? ` ${String(r.text).slice(0, 160)}` : ""}`);
    return { id: sessionId, title };
  }

  async deleteSession(sessionId) {
    const r = await this._v2(`/api/session/${encodeURIComponent(sessionId)}`, {
      method: "DELETE",
      timeoutMs: 8000
    });
    if (!r.ok && r.status !== 404) throw new Error(`OpenCode delete failed: ${r.status}${r.text ? ` ${String(r.text).slice(0, 160)}` : ""}`);
    return { id: sessionId, deleted: true };
  }

  // GET /api/session/:id (session.get) — metadatos puros (projectID, location,
  // agent, model). Usado por el hub para sellar el origen de una sesión.
  async getSessionMeta(sessionId) {
    const r = await this._v2(`/api/session/${encodeURIComponent(sessionId)}`, { timeoutMs: 6000 });
    if (!r.ok || !r.json) return null;
    return r.json.data || r.json || null;
  }

  // Convierte un mensaje v2 (type + content[]) al shape que entiende
  // normalizeMessage (role + parts[]) sin perder reasoning/tools.
  _mapV2Message(m, sessionId, idx) {
    const role = m.role || (m.type === "user" ? "user" : "assistant");
    let parts;
    if (Array.isArray(m.content) && m.content.length > 0) {
      parts = m.content.map((c, i) => ({
        id: c.id || `prt_${m.id || idx}_${i}`,
        type: c.type || "text",
        text: typeof c.text === "string" ? c.text : "",
        ...(c.tool ? { tool: c.tool } : {}),
        ...(c.state ? { state: c.state } : {}),
        ...(c.mime ? { mime: c.mime } : {})
      }));
    } else if (Array.isArray(m.parts) && m.parts.length > 0) {
      parts = m.parts;
    } else if (typeof m.text === "string") {
      parts = [{ id: `prt_${m.id || idx}_0`, type: "text", text: m.text }];
    }
    return normalizeMessage(
      { ...m, role, ...(parts ? { parts } : {}), ...(typeof m.text === "string" ? { text: m.text } : {}) },
      sessionId,
      idx
    );
  }

  async _fetchMessagePage(sessionId, query, signal, timeoutMs = 15000) {
    const r = await this._v2(`/api/session/${encodeURIComponent(sessionId)}/message${query}`, { timeoutMs, signal });
    if (r.status === 404) return { data: [], cursor: null, notFound: true };
    if (!r.ok || !r.json) throw new Error(`OpenCode GET /message -> ${r.status}${r.text ? `: ${String(r.text).slice(0, 120)}` : ""}`);
    return { data: r.json.data || [], cursor: r.json.cursor || null };
  }

  async getMessages(sessionId, opts = {}) {
    // F6: session.message.list con paginación por cursor (order=asc sólo en la
    // primera página).404 = sesión inexistente en opencode (p.ej. id agy_) → []
    // para que getUnifiedMessages la ignore en lugar de propagar ruido.
    const all = [];
    let query = "?order=asc&limit=200";
    for (let page = 0; page < 15; page++) {
      const { data, cursor, notFound } = await this._fetchMessagePage(sessionId, query, opts.signal);
      if (notFound) break;
      all.push(...data);
      if (!cursor || !cursor.next) break;
      query = `?cursor=${encodeURIComponent(cursor.next)}&limit=200`;
    }
    // F6: v2 mezcla mensajes-evento en el historial (model-switched,
    // agent-switched, idle, ...). Sin este filtro se convertían en asistentes
    // vacíos = burbujas fantasma en la app. Turnos reales: user/assistant.
    return all
      .filter((m) => !m || !m.type || m.type === "user" || m.type === "assistant")
      .map((m, idx) => this._mapV2Message(m, sessionId, idx));
  }

  async sendMessage(sessionId, payload = {}, opts = {}) {
    // ==== F6: OpenCode v2 — POST /api/session/:id/prompt (session.prompt) ====
    // Contrato v2 estricto: el body SOLO admite {text} (additionalProperties:
    // false). Los archivos se inlinean en el texto, el contexto del hub va a las
    // instructions de la sesión, y modelo/agente se cambian por endpoints
    // separados (session.switchModel / session.switchAgent). El turno
    // asistente se espera por polling newest-first (la app además re-sincroniza).

    // 1) Texto final desde parts/text/prompt + archivos adjuntos
    const chunks = [];
    if (Array.isArray(payload.parts) && payload.parts.length > 0) {
      for (const p of payload.parts) if (p && typeof p.text === "string" && p.text) chunks.push(p.text);
    } else if (typeof payload.text === "string" && payload.text.trim()) {
      chunks.push(payload.text.trim());
    } else if (typeof payload.prompt === "string" && payload.prompt.trim()) {
      chunks.push(payload.prompt.trim());
    }
    if (Array.isArray(payload.files)) {
      for (const f of payload.files) {
        if (f.name) {
          let fileText = `\n[Attached File: ${f.name} (${f.mime || "application/octet-stream"})]`;
          if (f.base64) {
            try {
              const decoded = Buffer.from(f.base64, "base64").toString("utf8");
              if (/^[\x20-\x7E\s\n\r\t]+$/.test(decoded.slice(0, 500))) {
                fileText += `\n\`\`\`\n${decoded.slice(0, 8000)}\n\`\`\``;
              }
            } catch (_) {}
          }
          chunks.push(fileText);
        }
      }
    }
    const text = chunks.join("\n").trim();
    if (!text) throw new Error("OpenCode prompt rechazado: mensaje vacío");

    // 2) Contexto del hub -> instructions persistentes de la sesión (best-effort).
    //    v2 ya no acepta {system} en el prompt.
    const projectId = payload.projectId || opts.projectId || null;
    const block = (typeof payload.system === "string" && payload.system.trim()) || this.getSystemContextBlock(projectId);
    if (block && block.trim()) await this._setSessionInstruction(sessionId, "aegis-context", block.trim());

    // 3) Modelo — endpoint aparte POST /session/:id/model {model:{id,providerID}}
    if (payload.model) {
      const ref = await this._resolveModelRef(payload.model);
      if (ref) {
        const key = `${ref.providerID}/${ref.id}`;
        if (this._lastModel.get(sessionId) !== key) {
          let r = await this._v2(`/api/session/${encodeURIComponent(sessionId)}/model`, {
            method: "POST", body: { model: { id: ref.id, providerID: ref.providerID } }, timeoutMs: 8000, signal: opts.signal
          });
          if (!r.ok && ref.alt && ref.alt.id && ref.alt.id !== ref.id) {
            r = await this._v2(`/api/session/${encodeURIComponent(sessionId)}/model`, {
              method: "POST", body: { model: { id: ref.alt.id, providerID: ref.providerID } }, timeoutMs: 8000, signal: opts.signal
            });
          }
          if (!r.ok) throw new Error(`OpenCode no pudo activar el modelo ${key}: ${r.status}${r.text ? ` ${String(r.text).slice(0, 160)}` : ""}`);
          this._lastModel.set(sessionId, key);
        }
      }
    }

    // 4) Agente PLAN/BUILD — POST /session/:id/agent (mejor esfuerzo)
    const agentMode = payload.agent || payload.mode || opts.agent || opts.mode || null;
    if (agentMode && ["plan", "build"].includes(String(agentMode))) {
      try {
        const r = await this._v2(`/api/session/${encodeURIComponent(sessionId)}/agent`, {
          method: "POST", body: { agent: String(agentMode) }, timeoutMs: 6000, signal: opts.signal
        });
        if (!r.ok && r.status !== 404) log.warn("[opencode] switchAgent failed", { status: r.status });
      } catch (e) {
        log.warn("[opencode] switchAgent error", { err: e.message });
      }
    }

    // 5) Prompt — encola en el inbox de la sesión (200 -> {data:{id,time}})
    let ack = null;
    for (let attempt = 0; attempt < 2; attempt++) {
      const r = await this._v2(`/api/session/${encodeURIComponent(sessionId)}/prompt`, {
        method: "POST", body: { text }, timeoutMs: 20000, signal: opts.signal
      });
      if (r.ok && r.json && r.json.data) { ack = r.json.data; break; }
      if (r.status === 409 && attempt === 0) { await new Promise((res) => setTimeout(res, 1500)); continue; }
      const msg = (r.json && (r.json.message || r.json.error)) || `HTTP ${r.status}`;
      throw new Error(`OpenCode prompt rechazado: ${msg}`);
    }
    if (!ack) throw new Error("OpenCode prompt rechazado: sin confirmación (409 persistente)");
    const cutoff = ack.time?.created || Date.now();
    log.info(`[opencode] prompt accepted for ${sessionId} (msg ${ack.id || "?"}), esperando turno asistente...`);

    // 6) Espera del asistente: página newest-first; completo cuando trae
    //    time.streamed (turno cerrado) o cuando el texto es estable en 2 polls.
    const deadline = Date.now() + 70000;
    let stableKey = null;
    while (Date.now() < deadline) {
      if (opts.signal?.aborted) throw new Error("OpenCode sendMessage aborted by client");
      await new Promise((res) => setTimeout(res, 1000));
      if (opts.signal?.aborted) throw new Error("OpenCode sendMessage aborted by client");
      try {
        const page = await this._fetchMessagePage(sessionId, "?order=desc&limit=20", opts.signal, 10000);
        let hit = null;
        let hitText = "";
        for (const m of page.data || []) {
          const ts = m.time?.created || 0;
          const isAssistant = !m.type || m.type === "assistant";
          if (!isAssistant || ts < cutoff) continue;
          const txt = Array.isArray(m.content)
            ? m.content.filter((c) => c && c.type === "text" && c.text).map((c) => c.text).join("")
            : (typeof m.text === "string" ? m.text : "");
          if (!txt.trim()) continue;
          hit = m; hitText = txt; break;
        }
        if (hit) {
          const key = `${hit.id}:${hitText.length}`;
          const complete = !!(hit.time && hit.time.streamed) || key === stableKey;
          stableKey = key;
          if (complete) {
            const normalized = this._mapV2Message(hit, sessionId, 0);
            if (typeof opts.onChunk === "function" && normalized.text) {
              try { opts.onChunk(normalized.text); } catch (_) {}
            }
            log.info(`[opencode] assistant turn completed for ${sessionId} (${normalized.text.length} chars)`);
            return normalized;
          }
        } else {
          stableKey = null;
        }
      } catch (e) {
        if (opts.signal?.aborted) throw new Error("OpenCode sendMessage aborted by client");
        // fallo transitorio del poll — se reintenta en la siguiente vuelta
      }
    }
    throw new Error("OpenCode no respondió en 70s (timeout del turno)");
  }

  // F6: GET /api/model (model.list) — lista REAL de v2 (opencode, google,
  // openrouter) con cost por modelo. Orden pedido por el usuario:
  //   rango 0: gratis OpenCode Zen      rango 1: gratis resto (openrouter/otros)
  //   rango 2: pago  OpenCode Zen       rango 3: pago / API-key / privados
  // sort estable => dentro de cada rango se conserva el orden de la API.
  async listModels() {
    const now = Date.now();
    if (this._modelsCache && (now - (this._modelsCacheTime || 0) < 600000) && this._modelsCache.length > 0) {
      return this._modelsCache;
    }
    if (this._fetchingModels) {
      if (this._modelsCache && this._modelsCache.length > 0) return this._modelsCache;
      return this._fallbackModels();
    }
    this._fetchingModels = true;
    try {
      const r = await this._v2("/api/model", { timeoutMs: 12000 });
      if (r.ok && r.json && Array.isArray(r.json.data)) {
        const raw = r.json.data.filter((m) => m && m.enabled !== false && (m.id || m.modelID));
        // Índice de alias -> Model.Ref para session.switchModel
        const refIdx = new Map();
        for (const m of raw) {
          const primary = String(m.modelID || m.id);
          const secondary = String(m.id || m.modelID);
          const ref = {
            id: primary,
            providerID: String(m.providerID || "opencode"),
            ...(secondary !== primary ? { alt: { id: secondary } } : {})
          };
          for (const alias of new Set([m.id, m.modelID, `${m.providerID}/${m.modelID}`, `${m.providerID}/${m.id}`].filter(Boolean))) {
            refIdx.set(String(alias), ref);
          }
        }
        this._modelRefs = refIdx;

        const isFree = (m) => {
          const costs = Array.isArray(m.cost) ? m.cost : (m.cost ? [m.cost] : []);
          if (costs.length > 0 && costs.every((c) => c && c.input === 0 && c.output === 0)) return true;
          const id = String(m.id || "").toLowerCase();
          return id.endsWith(":free") || id.endsWith("-free");
        };
        const rank = (m) => {
          const free = isFree(m);
          if (free) return m.providerID === "opencode" ? 0 : 1;
          return m.providerID === "opencode" ? 2 : 3;
        };
        const label = { opencode: "OpenCode Zen", google: "Google AI (API key)", openrouter: "OpenRouter" };
        const pairs = raw.map((m) => ({
          m,
          disp: {
            id: String(m.id || m.modelID),
            name: String(m.name || m.modelID || m.id),
            description: `${label[m.providerID] || m.providerID} · ${m.family || "AI"}${isFree(m) ? " · Gratis" : ""}`,
            provider: String(m.providerID || "opencode"),
            free: isFree(m)
          }
        }));
        pairs.sort((a, b) => rank(a.m) - rank(b.m));
        if (pairs.length > 0) {
          this._modelsCache = pairs.map((p) => p.disp);
          this._modelsCacheTime = now;
          return this._modelsCache;
        }
      }
      throw new Error(`GET /api/model -> ${r.status}${r.text ? `: ${String(r.text).slice(0, 100)}` : ""}`);
    } catch (e) {
      log.warn("[opencode] listModels fetch error", { err: e.message });
    } finally {
      this._fetchingModels = false;
    }
    return (this._modelsCache && this._modelsCache.length > 0) ? this._modelsCache : this._fallbackModels();
  }

  async _resolveModelRef(model) {
    if (model && typeof model === "object") {
      const id = model.modelID || model.id;
      if (!id) return null;
      return { id: String(id), providerID: String(model.providerID || "opencode") };
    }
    const key = String(model || "").trim();
    if (!key) return null;
    if (!this._modelRefs || this._modelRefs.size === 0) {
      try { await this.listModels(); } catch (_) {}
    }
    const ref = this._modelRefs ? this._modelRefs.get(key) : null;
    if (!ref) log.warn("[opencode] modelo no encontrado en el índice v2 (se mantiene el de la sesión)", { model: key });
    return ref || null;
  }

  async _setSessionInstruction(sessionId, key, value) {
    try {
      const h = `${value.length}:${Buffer.from(value).toString("base64").slice(0, 48)}`;
      if (this._instrHash.get(sessionId) === h) return;
      const r = await this._v2(
        `/api/experimental/session/${encodeURIComponent(sessionId)}/instructions/entries/${encodeURIComponent(key)}`,
        { method: "PUT", body: { value }, timeoutMs: 6000 }
      );
      if (r.ok) this._instrHash.set(sessionId, h);
      else log.warn("[opencode] instruction PUT failed", { status: r.status });
    } catch (e) {
      log.warn("[opencode] instruction PUT error", { err: e.message });
    }
  }

  _fallbackModels() {
    // Fallback honesto: models free reales de Zen, sólo con opencode caído.
    return [
      { id: "space-bunny-free", name: "Space Bunny Free", description: "OpenCode Zen · AI · Gratis (fallback)" },
      { id: "mimo-v2.6-flash-free", name: "Mimo v2.6 Flash Free", description: "OpenCode Zen · AI · Gratis (fallback)" },
      { id: "muse-spark-1.3-contributor-free", name: "Muse Spark 1.3 Contributor Free", description: "OpenCode Zen · AI · Gratis (fallback)" },
      { id: "ling-3.0-flash-fin-free", name: "Ling 3.0 Flash Fin Free", description: "OpenCode Zen · AI · Gratis (fallback)" },
      { id: "nemotron-3.5-lightning-free", name: "Nemotron 3.5 Lightning Free", description: "OpenCode Zen · AI · Gratis (fallback)" },
      { id: "nemotron-3-ultra-free", name: "Nemotron 3 Ultra Free", description: "OpenCode Zen · AI · Gratis (fallback)" }
    ];
  }
}

// ==========================================
// Antigravity Adapter (CLI Print/JSON Mode)
// Hard 90s timeout, process tree killing, zombie cleanup
// ==========================================

export class AntigravityAdapter extends BaseProviderAdapter {
  constructor(options = {}) {
    super("antigravity", "Antigravity", "cli");
    this.binPath =
      options.binPath ||
      (fs.existsSync("/root/.local/bin/agy") ? "/root/.local/bin/agy" : "agy");
    this.brainDir = options.brainDir || "/root/.gemini/antigravity-cli/brain";
    this.cwd = options.cwd || "/sdcard/projects";
    this.getSystemContextBlock = options.getSystemContextBlock || (() => null);
    this.sessionMap = new Map(); // sessionId -> agyConversationId
    this.activeProcesses = new Map(); // pid -> { proc, timer, kill }

    // Run initial zombie cleanup on initialization
    this.cleanupZombieProcesses();
  }

  async isHealthy() {
    try {
      this.cleanupZombieProcesses();
      if (fs.existsSync(this.binPath)) {
        return { up: true, healthy: true, version: "agy" };
      }
      return { up: false, healthy: false, error: `agy binary not found at ${this.binPath}` };
    } catch (e) {
      return { up: false, healthy: false, error: e.message };
    }
  }

  // Scan /proc to reap orphaned/zombie agy non-interactive processes
  cleanupZombieProcesses() {
    try {
      if (!fs.existsSync("/proc")) return 0;
      const entries = fs.readdirSync("/proc");
      const myPid = process.pid;
      const myPpid = process.ppid;
      let reapedCount = 0;

      for (const ent of entries) {
        if (!/^\d+$/.test(ent)) continue;
        const pid = parseInt(ent, 10);
        if (pid === myPid || pid === myPpid) continue;

        try {
          const statPath = `/proc/${pid}/stat`;
          const cmdlinePath = `/proc/${pid}/cmdline`;
          if (!fs.existsSync(statPath) || !fs.existsSync(cmdlinePath)) continue;

          const cmdline = fs.readFileSync(cmdlinePath, "utf8").replace(/\0/g, " ");
          // Target only agy processes in print mode (-p / --print)
          if (!cmdline.includes("agy") || (!cmdline.includes("-p") && !cmdline.includes("--print"))) {
            continue;
          }

          const stat = fs.readFileSync(statPath, "utf8").split(" ");
          const state = stat[2]; // 'Z' = zombie
          const ppid = parseInt(stat[3], 10);
          const tty = parseInt(stat[6], 10);

          // SAFEGUARD: Never touch processes attached to an interactive terminal (pts/N)
          if (tty !== 0) continue;

          // Orphaned (ppid 1) or Zombie (state Z)
          if (ppid === 1 || state === "Z") {
            log.info(`[antigravity] Reaping orphaned/zombie agy process: pid=${pid}, state=${state}, ppid=${ppid}`);
            try {
              process.kill(pid, "SIGKILL");
              reapedCount++;
            } catch (_) {}
          }
        } catch (_) {}
      }
      return reapedCount;
    } catch (e) {
      log.error("[antigravity] cleanupZombieProcesses err", { err: e.message });
      return 0;
    }
  }

  // Scan Antigravity brain directories and extract conversation metadata
  async listSessions() {
    const sessions = [];
    try {
      if (!fs.existsSync(this.brainDir)) return [];
      const entries = fs.readdirSync(this.brainDir, { withFileTypes: true });
      for (const ent of entries) {
        if (!ent.isDirectory()) continue;
        const convId = ent.name;
        const transcriptPath = path.join(
          this.brainDir,
          convId,
          ".system_generated/logs/transcript.jsonl"
        );
        if (!fs.existsSync(transcriptPath)) continue;

        let title = `Antigravity: ${convId.slice(0, 8)}`;
        let createdAt = null;
        let updatedAt = null;

        try {
          const content = fs.readFileSync(transcriptPath, "utf8");
          const lines = content.trim().split("\n").filter(Boolean);
          if (lines.length === 0) continue;
          const first = JSON.parse(lines[0]);
          createdAt = first.created_at || null;
          if (first.content) {
            const cleaned = this._cleanPromptContent(first.content);
            if (cleaned) title = cleaned.slice(0, 60);
          }
          const last = JSON.parse(lines[lines.length - 1]);
          updatedAt = last.created_at || createdAt;
        } catch (_) {
          continue;
        }

        if (!createdAt) {
          try {
            const stat = fs.statSync(transcriptPath);
            createdAt = stat.birthtime.toISOString();
            updatedAt = stat.mtime.toISOString();
          } catch {
            createdAt = new Date().toISOString();
            updatedAt = createdAt;
          }
        }

        sessions.push({
          id: convId,
          title,
          createdAt,
          updatedAt: updatedAt || createdAt,
          provider: "antigravity"
        });
      }
    } catch (e) {
      log.error("[antigravity] listSessions err", { err: e.message });
    }
    return sessions;
  }

  async createSession(opts = {}) {
    const id = `agy_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 10)}`;
    const title = opts.title && !opts.title.startsWith("companion:") && !opts.title.startsWith("session:")
      ? opts.title
      : "Nuevo chat";
    return {
      id,
      title,
      createdAt: new Date().toISOString(),
      provider: "antigravity"
    };
  }

  async deleteSession(sessionId) {
    let convId = this.sessionMap.get(sessionId) || sessionId;
    this.sessionMap.delete(sessionId);

    // Also check if any project in projects.json has this session's agyConversationId
    try {
      const store = loadProjectsStore();
      for (const p of store.projects) {
        const f = (p.sessions || []).find(s => s.sessionId === sessionId || s.agyConversationId === sessionId);
        if (f && f.agyConversationId) {
          const cDir = path.join(this.brainDir, f.agyConversationId);
          if (fs.existsSync(cDir)) {
            try { fs.rmSync(cDir, { recursive: true, force: true }); } catch (_) {}
          }
        }
      }
    } catch (_) {}

    const targetDir = path.join(this.brainDir, convId);
    if (fs.existsSync(targetDir)) {
      try {
        fs.rmSync(targetDir, { recursive: true, force: true });
        log.info(`[antigravity] purged brain directory: ${targetDir}`);
      } catch (err) {
        log.warn(`[antigravity] failed to remove brain directory ${targetDir}`, { err: err.message });
      }
    }

    // Also check direct sessionId directory if distinct
    const directDir = path.join(this.brainDir, sessionId);
    if (directDir !== targetDir && fs.existsSync(directDir)) {
      try {
        fs.rmSync(directDir, { recursive: true, force: true });
        log.info(`[antigravity] purged brain directory: ${directDir}`);
      } catch (_) {}
    }

    return { ok: true, removed: sessionId };
  }

  _cleanPromptContent(raw) {
    if (!raw) return "";
    let str = String(raw);
    const m = str.match(/<USER_REQUEST>([\s\S]*?)(?:<\/USER_REQUEST>|$)/i);
    if (m && m[1]) str = m[1];
    str = str.replace(/\/\/?(?:PLAN|plan|BUILD|build)\s*/g, "");
    str = str.replace(/<SYSTEM_INSTRUCTION>[\s\S]*?(?:<\/SYSTEM_INSTRUCTION>|$)/gi, "").trim();
    str = str.replace(/<SYSTEM_CONTEXT[\s\S]*?(?:<\/SYSTEM_CONTEXT>|$)/gi, "").trim();
    str = str.replace(/\[SYSTEM CONTEXT[\s\S]*?\][\s\S]*?(?:---\n\n|$)/gi, "").trim();
    str = str.replace(/# PONY-TAIL[\s\S]*?(?:---\n\n|$)/gi, "").trim();
    str = str.replace(/## 1\. Entorno[\s\S]*?(?:---\n\n|$)/gi, "").trim();
    str = str.replace(/<memory_context>[\s\S]*?<\/memory_context>/gi, "").trim();
    str = str.replace(/<ADDITIONAL_METADATA>[\s\S]*?<\/ADDITIONAL_METADATA>/gi, "").trim();
    str = str.replace(/<USER_SETTINGS_CHANGE>[\s\S]*?<\/USER_SETTINGS_CHANGE>/gi, "").trim();
    return str.trim();
  }

  async getMessages(sessionId, opts = {}) {
    const convId = this.sessionMap.get(sessionId) || sessionId;
    const transcriptPath = path.join(
      this.brainDir,
      convId,
      ".system_generated/logs/transcript.jsonl"
    );

    if (!fs.existsSync(transcriptPath)) {
      return [];
    }

    try {
      const content = fs.readFileSync(transcriptPath, "utf8");
      const lines = content.trim().split("\n").filter(Boolean);
      const messages = [];

      let currentTurnParts = [];
      let currentTurnText = [];
      let lastToolCall = null;
      let currentAssistantTime = null;

      const flushAssistantTurn = () => {
        if (currentTurnParts.length === 0 && currentTurnText.length === 0) return;
        const respText = currentTurnText.join("\n").trim();
        const t = currentAssistantTime || Date.now();
        messages.push(
          normalizeMessage(
            {
              role: "assistant",
              text: respText,
              info: {
                id: `msg_agy_${sessionId}_asst_${messages.length}`,
                role: "assistant",
                time: { created: t },
                timestamp: t,
                status: "SENT",
                deliveryStatus: "SENT"
              },
              parts: [...currentTurnParts]
            },
            sessionId,
            messages.length
          )
        );
        currentTurnParts = [];
        currentTurnText = [];
        currentAssistantTime = null;
      };

      for (const lineStr of lines) {
        try {
          const item = JSON.parse(lineStr);
          const isUser = item.source === "USER_EXPLICIT" || item.type === "USER_INPUT";

          if (isUser) {
            flushAssistantTurn();
            let text = item.content || "";
            text = this._cleanPromptContent(text);
            if (!text.trim()) continue;
            const createdTime = item.created_at ? new Date(item.created_at).getTime() : Date.now();
            messages.push(
              normalizeMessage(
                {
                  role: "user",
                  text: text.trim(),
                  info: {
                    id: `msg_agy_${sessionId}_usr_${messages.length}`,
                    role: "user",
                    time: { created: createdTime },
                    timestamp: createdTime,
                    status: "SENT",
                    deliveryStatus: "SENT"
                  },
                  parts: [
                    {
                      id: `prt_usr_${messages.length}`,
                      type: "text",
                      text: text.trim()
                    }
                  ]
                },
                sessionId,
                messages.length
              )
            );
            continue;
          }

          // Assistant steps: tool calls, outputs, or text
          if (item.tool_calls && Array.isArray(item.tool_calls) && item.tool_calls.length > 0) {
            for (const tc of item.tool_calls) {
              const rawToolName = tc.name || "bash";
              lastToolCall = {
                tool: rawToolName === "run_command" ? "bash" : rawToolName,
                input: tc.args || {},
                step_index: item.step_index ?? currentTurnParts.length
              };
            }
            if (item.created_at) currentAssistantTime = new Date(item.created_at).getTime();
          } else if (lastToolCall && (item.type === "GENERIC" || item.content)) {
            const out = item.content || "";
            const exitMatch = out.match(/exited with code (\d+)/i);
            const exitCode = exitMatch ? parseInt(exitMatch[1], 10) : 0;
            currentTurnParts.push({
              id: `prt_tool_${sessionId}_${lastToolCall.step_index}`,
              type: "tool",
              tool: lastToolCall.tool,
              callID: `call_${lastToolCall.step_index}`,
              state: {
                status: exitCode === 0 ? "completed" : "error",
                input: lastToolCall.input,
                output: out,
                exitCode
              }
            });
            lastToolCall = null;
            if (item.created_at) currentAssistantTime = new Date(item.created_at).getTime();
          } else if (item.content && (item.source === "MODEL" || item.type === "PLANNER_RESPONSE")) {
            const trimmed = item.content.trim();
            if (trimmed) {
              currentTurnParts.push({
                id: `prt_text_${sessionId}_${item.step_index ?? currentTurnParts.length}`,
                type: "text",
                text: trimmed
              });
              currentTurnText.push(trimmed);
            }
            if (item.created_at) currentAssistantTime = new Date(item.created_at).getTime();
          }
        } catch (_) {}
      }

      flushAssistantTurn();
      return messages;
    } catch (e) {
      log.error(`[antigravity] getMessages err for ${sessionId}`, { err: e.message });
      return [];
    }
  }

  async sendMessage(sessionId, payload = {}, opts = {}) {
    // 1. Resolve prompt text
    let userPrompt = "";
    if (typeof payload.text === "string" && payload.text.trim()) {
      userPrompt = payload.text.trim();
    } else if (typeof payload.prompt === "string" && payload.prompt.trim()) {
      userPrompt = payload.prompt.trim();
    } else if (Array.isArray(payload.parts)) {
      const textParts = payload.parts
        .filter((p) => p && (p.type === "text" || !p.type) && typeof p.text === "string")
        .map((p) => p.text);
      userPrompt = textParts.join("\n").trim();

      // Handle attached files if any
      const fileParts = payload.parts.filter((p) => p && p.type === "file");
      for (const fp of fileParts) {
        if (fp.filename) {
          userPrompt += `\n\n[Attached File: ${fp.filename}]`;
          if (fp.url && fp.url.startsWith("data:") && fp.url.includes(";base64,")) {
            try {
              const base64Data = fp.url.split(";base64,")[1];
              const decoded = Buffer.from(base64Data, "base64").toString("utf8");
              if (/^[\x20-\x7E\s\n\r\t]+$/.test(decoded.slice(0, 500))) {
                userPrompt += `\n\`\`\`\n${decoded.slice(0, 8000)}\n\`\`\``;
              }
            } catch (_) {}
          }
        }
      }
    }

    if (Array.isArray(payload.files)) {
      for (const f of payload.files) {
        if (f.name) {
          userPrompt += `\n\n[Attached File: ${f.name} (${f.mime || "application/octet-stream"})]`;
          if (f.base64) {
            try {
              const decoded = Buffer.from(f.base64, "base64").toString("utf8");
              if (/^[\x20-\x7E\s\n\r\t]+$/.test(decoded.slice(0, 500))) {
                userPrompt += `\n\`\`\`\n${decoded.slice(0, 8000)}\n\`\`\``;
              }
            } catch (_) {}
          }
        }
      }
    }

    if (!userPrompt) {
      throw new Error("No user text or prompt provided in message payload");
    }

    // 2. Inject system context silently as background instructions (never in visible user prompt)
    const projectId = payload.projectId || opts.projectId || null;
    const block = this.getSystemContextBlock(projectId);
    let promptForAgy = "";
    if (block && block.trim()) {
      promptForAgy += `<SYSTEM_INSTRUCTION>\n${block.trim()}\n</SYSTEM_INSTRUCTION>\n\n`;
    }
    promptForAgy += `<USER_REQUEST>\n${userPrompt}\n</USER_REQUEST>`;

    // 3. Resolve Antigravity conversation ID
    let convId = this.sessionMap.get(sessionId) || null;
    if (!convId) {
      if (fs.existsSync(path.join(this.brainDir, sessionId))) {
        convId = sessionId;
      }
    }

    // 4. Build agy CLI spawn arguments
    const isStreaming = typeof opts.onChunk === "function";
    const beforeDirs = new Set(
      fs.existsSync(this.brainDir) ? fs.readdirSync(this.brainDir) : []
    );

    const args = [];
    if (convId && fs.existsSync(path.join(this.brainDir, convId))) {
      args.push("--conversation", convId);
    }
    const modelToUse = payload.model || "gemini-3.8-flash-high";
    args.push("--model", String(modelToUse));
    const agentMode = payload.agent || payload.mode || opts.agent || opts.mode || "build";
    if (agentMode === "plan") {
      args.push("--mode", "plan");
    } else {
      args.push("--mode", "accept-edits");
    }
    args.push("-p", promptForAgy);
    args.push("--output-format", "stream-json");
    args.push(
      "--dangerously-skip-permissions",
      "--print-timeout",
      "85s"
    );

    log.info(`[antigravity] executing agy for session ${sessionId} (convId: ${convId || "new"}, streaming: ${isStreaming})...`);

    // Helper to safely kill process group
    const killGroup = (proc, signal = "SIGTERM") => {
      if (!proc || !proc.pid) return;
      try {
        process.kill(-proc.pid, signal);
      } catch (_) {
        try {
          proc.kill(signal);
        } catch (__) {}
      }
    };

    // 5. Spawn agy and await output with strict 90s hard timeout and signal handling
    const result = await new Promise((resolve, reject) => {
      let isDone = false;

      // Spawn detached so we can terminate the entire process group
      const p = spawn(this.binPath, args, {
        cwd: this.cwd,
        detached: true,
        env: {
          ...process.env,
          HOME: "/root",
          PATH: `/root/.local/bin:${process.env.PATH || ""}`
        }
      });

      if (p.pid) {
        this.activeProcesses.set(p.pid, {
          proc: p,
          startTime: Date.now(),
          kill: (sig) => killGroup(p, sig)
        });
      }

      let stdout = "";
      let stderr = "";
      let ndjsonBuffer = "";
      const activeTools = new Map();
      const completedToolParts = [];
      let accumulatedText = "";
      let finalResultResponse = "";
      let detectedConvId = convId;

      const handleAgyJsonEvent = (evt) => {
        if (!evt || typeof evt !== "object") return;
        if (evt.event === "init" && evt.conversation_id) {
          detectedConvId = evt.conversation_id;
        } else if (evt.event === "step_update" && evt.step_update) {
          const step = evt.step_update;
          if (step.conversation_id) detectedConvId = step.conversation_id;
          if (step.step_type === "tool") {
            const rawToolName = step.tool_name || step.tool_info?.name || "bash";
            const toolName = rawToolName === "run_command" ? "bash" : rawToolName;
            const params = step.tool_info?.parameters || {};
            const callId = `call_${sessionId}_${step.step_index}`;

            if (step.state === "ACTIVE") {
              activeTools.set(step.step_index, {
                id: callId,
                tool: toolName,
                input: params,
                status: "running"
              });
              if (typeof opts.onStreamEvent === "function") {
                try {
                  opts.onStreamEvent({
                    type: "tool_start",
                    tool: toolName,
                    callID: callId,
                    input: params,
                    stepIndex: step.step_index
                  });
                } catch (_) {}
              }
            } else if (step.state === "DONE") {
              const output = step.tool_info?.output || "";
              const duration = step.duration_seconds || 0;
              let exitCode = 0;
              const exitMatch = output.match(/exited with code (\d+)/i);
              if (exitMatch) exitCode = parseInt(exitMatch[1], 10);

              const toolItem = activeTools.get(step.step_index) || { id: callId, tool: toolName, input: params };
              toolItem.status = exitCode === 0 ? "completed" : "error";
              toolItem.output = output;
              toolItem.exitCode = exitCode;
              toolItem.duration = duration;

              completedToolParts.push({
                id: `prt_${callId}`,
                type: "tool",
                tool: toolName,
                callID: callId,
                state: {
                  status: toolItem.status,
                  input: params,
                  output,
                  exitCode,
                  duration
                }
              });

              if (typeof opts.onStreamEvent === "function") {
                try {
                  opts.onStreamEvent({
                    type: "tool_done",
                    tool: toolName,
                    callID: callId,
                    input: params,
                    output,
                    exitCode,
                    duration,
                    stepIndex: step.step_index
                  });
                } catch (_) {}
              }
            }
          } else if (step.step_type === "agent_response") {
            if (step.text_delta) {
              accumulatedText += step.text_delta;
              if (typeof opts.onStreamEvent === "function") {
                try {
                  opts.onStreamEvent({
                    type: "chunk",
                    text: step.text_delta
                  });
                } catch (_) {}
              } else if (typeof opts.onChunk === "function") {
                try { opts.onChunk(step.text_delta); } catch (_) {}
              }
            }
          }
        } else if (evt.event === "result" && evt.result) {
          if (evt.result.conversation_id) detectedConvId = evt.result.conversation_id;
          if (evt.result.response) finalResultResponse = evt.result.response;
        }
      };

      p.stdout.on("data", (c) => {
        const text = c.toString("utf8");
        stdout += text;
        ndjsonBuffer += text;
        const lines = ndjsonBuffer.split("\n");
        ndjsonBuffer = lines.pop();
        for (const line of lines) {
          const trimmed = line.trim();
          if (!trimmed) continue;
          try {
            const evt = JSON.parse(trimmed);
            handleAgyJsonEvent(evt);
          } catch (_) {}
        }
      });
      p.stderr.on("data", (c) => (stderr += c));

      // 90 second hard timeout guard with escalation
      const timer = setTimeout(() => {
        if (isDone) return;
        isDone = true;
        log.warn(`[antigravity] Process ${p.pid} exceeded hard 90s timeout. Killing group with SIGTERM...`);
        killGroup(p, "SIGTERM");
        const killTimer = setTimeout(() => {
          try {
            log.warn(`[antigravity] Escalating to SIGKILL for process group ${p.pid}...`);
            killGroup(p, "SIGKILL");
          } catch (_) {}
        }, 2000);
        killTimer.unref();

        if (p.pid) this.activeProcesses.delete(p.pid);
        reject(new Error("Antigravity CLI execution timed out after 90s"));
      }, 90000);

      // Manage abort signal from client connection drop
      if (opts.signal) {
        opts.signal.addEventListener(
          "abort",
          () => {
            if (isDone) return;
            isDone = true;
            clearTimeout(timer);
            log.info(`[antigravity] Client connection aborted. Killing process group ${p.pid}...`);
            killGroup(p, "SIGTERM");
            setTimeout(() => killGroup(p, "SIGKILL"), 1500).unref();
            if (p.pid) this.activeProcesses.delete(p.pid);
            reject(new Error("Antigravity request aborted by client"));
          },
          { once: true }
        );
      }

      p.on("close", (code) => {
        if (isDone) return;
        isDone = true;
        clearTimeout(timer);
        if (p.pid) this.activeProcesses.delete(p.pid);

        if (ndjsonBuffer.trim()) {
          try {
            const evt = JSON.parse(ndjsonBuffer.trim());
            handleAgyJsonEvent(evt);
          } catch (_) {}
        }

        const responseText = (finalResultResponse || accumulatedText || "").trim();

        if (code !== 0 && !stdout.trim() && !responseText) {
          return reject(new Error(`Antigravity exited with code ${code}: ${stderr.trim()}`));
        }

        let afterConvId = detectedConvId || convId;
        if (!afterConvId && fs.existsSync(this.brainDir)) {
          const curDirs = fs.readdirSync(this.brainDir);
          const newDirs = curDirs.filter((d) => !beforeDirs.has(d));
          if (newDirs.length > 0) {
            newDirs.sort((a, b) => {
              try {
                return fs.statSync(path.join(this.brainDir, b)).mtimeMs - fs.statSync(path.join(this.brainDir, a)).mtimeMs;
              } catch (_) { return 0; }
            });
            afterConvId = newDirs[0];
          } else {
            const allDirs = curDirs.filter((d) => !d.startsWith("."));
            allDirs.sort((a, b) => {
              try {
                return fs.statSync(path.join(this.brainDir, b)).mtimeMs - fs.statSync(path.join(this.brainDir, a)).mtimeMs;
              } catch (_) { return 0; }
            });
            if (allDirs.length > 0 && Date.now() - fs.statSync(path.join(this.brainDir, allDirs[0])).mtimeMs < 60000) {
              afterConvId = allDirs[0];
            }
          }
        }
        if (afterConvId) this.sessionMap.set(sessionId, afterConvId);

        const parts = [
          ...completedToolParts
        ];
        if (responseText) {
          parts.push({
            id: `prt_text_${Date.now()}`,
            type: "text",
            text: responseText
          });
        }

        return resolve({
          response: responseText,
          text: responseText,
          role: "assistant",
          conversation_id: afterConvId || sessionId,
          parts
        });
      });

      p.on("error", (err) => {
        if (isDone) return;
        isDone = true;
        clearTimeout(timer);
        if (p.pid) this.activeProcesses.delete(p.pid);
        reject(new Error(`Failed to spawn agy binary: ${err.message}`));
      });
    });

    // 6. Record returned conversation ID
    if (result.conversation_id) {
      this.sessionMap.set(sessionId, result.conversation_id);
    }

    const responseText = (result.response || result.text || "").trim();

    // 7. Return standard Message format matching Frontend Contract
    return normalizeMessage(
      {
        role: "assistant",
        text: responseText,
        info: {
          id: `msg_agy_${sessionId}_${Date.now()}`,
          role: "assistant",
          timestamp: Date.now(),
          time: { created: Date.now() },
          status: "SENT",
          deliveryStatus: "SENT"
        },
        parts: result.parts && result.parts.length > 0 ? result.parts : [
          {
            id: `prt_${Date.now()}`,
            type: "text",
            text: responseText
          }
        ],
        _agyMeta: {
          conversationId: result.conversation_id
        }
      },
      sessionId
    );
  }

  async listModels() {
    return [
      { id: "gemini-3.8-flash-high", name: "Gemini 3.8 Flash (High)", description: "Rápido con razonamiento alto (predeterminado)" },
      { id: "gemini-3.8-flash-medium", name: "Gemini 3.8 Flash (Medium)", description: "Balance velocidad/razonamiento" },
      { id: "gemini-3.8-flash-low", name: "Gemini 3.8 Flash (Low)", description: "Velocidad máxima" },
      { id: "gemini-3.1-pro-high", name: "Gemini 3.1 Pro (High)", description: "Máxima calidad para tareas complejas" },
      { id: "claude-sonnet-4-6", name: "Claude Sonnet 4.6 (Thinking)", description: "Anthropic Claude con Thinking" },
      { id: "claude-opus-4-6-thinking", name: "Claude Opus 4.6 (Thinking)", description: "Anthropic Claude Opus con Thinking" }
    ];
  }
}

// ==========================================
// Provider Manager
// ==========================================

export class ProviderManager {
  constructor(configFilePath = "/sdcard/projects/Aegis/backend/providers.json") {
    this.configFilePath = configFilePath;
    this.adapters = new Map();
    this.defaultProvider = "antigravity";
    this.loadConfig();
  }

  loadConfig() {
    try {
      const raw = atomicReadFileSync(this.configFilePath, null);
      if (raw && raw.defaultProvider) {
        this.defaultProvider = raw.defaultProvider;
      }
    } catch (e) {
      log.error("[provider-mgr] loadConfig err", { err: e.message });
    }
  }

  async saveConfig(mutatorFn) {
    return fileMutex.runExclusive(this.configFilePath, async () => {
      const current = atomicReadFileSync(this.configFilePath, {
        defaultProvider: this.defaultProvider,
        providers: []
      });
      const updated = mutatorFn ? mutatorFn(current) : current;
      if (updated.defaultProvider) {
        this.defaultProvider = updated.defaultProvider;
      }
      atomicWriteFileSync(this.configFilePath, updated);
      return updated;
    });
  }

  register(adapter) {
    this.adapters.set(adapter.id, adapter);
  }

  get(id) {
    return this.adapters.get(id) || this.adapters.get(this.defaultProvider);
  }

  listProviders() {
    const list = [];
    for (const [id, a] of this.adapters.entries()) {
      list.push({
        id: a.id,
        name: a.name,
        type: a.type,
        isDefault: a.id === this.defaultProvider
      });
    }
    return list;
  }

  // F6: el proveedor de nacimiento de una sesión es INAMOVIBLE. Orden:
  // 1) vínculo persistente en projects.json (reparado en caliente por prefijo),
  // 2) convención del id (ses_ -> opencode, agy_ -> antigravity),
  // 3) header explícito (sólo para ids sin convención conocida),
  // 4) heurística brain-dir y default.
  // Así, cambiar el pill de modelo en la app NUNCA re-bindea ("borra") la sesión.
  static _conventionProvider(sessionId) {
    if (typeof sessionId !== "string") return null;
    if (sessionId.startsWith("agy_")) return "antigravity";
    if (sessionId.startsWith("ses_")) return "opencode";
    return null;
  }

  resolveProvider(sessionId, explicitProvider = null, projectsStore = null) {
    const convention = ProviderManager._conventionProvider(sessionId);

    if (sessionId && projectsStore && Array.isArray(projectsStore.projects)) {
      for (const p of projectsStore.projects) {
        const sess = (p.sessions || []).find((s) => s.sessionId === sessionId);
        if (sess) {
          let provId = String(sess.provider || p.provider || this.defaultProvider || "").toLowerCase();
          // Reparación en caliente: si el registro quedó corrompido por un
          // header X-Provider, el prefijo del id es la autoridad.
          if (convention && provId !== convention) provId = convention;
          if (this.adapters.has(provId)) return this.adapters.get(provId);
          if (convention && this.adapters.has(convention)) return this.adapters.get(convention);
        }
      }
    }

    if (convention && this.adapters.has(convention)) return this.adapters.get(convention);

    if (explicitProvider && this.adapters.has(explicitProvider.toLowerCase())) {
      return this.adapters.get(explicitProvider.toLowerCase());
    }

    if (
      sessionId &&
      fs.existsSync(path.join("/root/.gemini/antigravity-cli/brain", sessionId))
    ) {
      return this.adapters.get("antigravity");
    }

    return this.adapters.get(this.defaultProvider);
  }

  // Unified messages: merges history from OpenCode and Antigravity so NO context is lost on provider switch
  async getUnifiedMessages(sessionId, opts = {}) {
    let ocMsgs = [];
    let agyMsgs = [];

    const oc = this.adapters.get("opencode");
    if (oc) {
      try {
        ocMsgs = await oc.getMessages(sessionId, opts);
      } catch (_) {}
    }

    const agy = this.adapters.get("antigravity");
    if (agy) {
      try {
        agyMsgs = await agy.getMessages(sessionId, opts);
      } catch (_) {}
    }

    if (ocMsgs.length > 0 && agyMsgs.length > 0) {
      const merged = [...ocMsgs, ...agyMsgs];
      merged.sort((a, b) => {
        const tA = a.info?.timestamp || a.info?.time?.created || 0;
        const tB = b.info?.timestamp || b.info?.time?.created || 0;
        return tA - tB;
      });
      return merged;
    }

    if (agyMsgs.length > 0) return agyMsgs;
    return ocMsgs;
  }

  // Unified session listing across all active providers
  async listAllSessions() {
    const all = [];
    const seen = new Set();

    for (const [_, adapter] of this.adapters.entries()) {
      try {
        const list = await adapter.listSessions();
        for (const s of list) {
          if (!seen.has(s.id)) {
            seen.add(s.id);
            all.push(s);
          }
        }
      } catch (e) {
        log.warn(`[provider-mgr] listSessions error for ${adapter.id}`, { err: e.message });
      }
    }

    all.sort((a, b) => (b.updatedAt || b.createdAt || "").localeCompare(a.updatedAt || a.createdAt || ""));
    return all;
  }
}
