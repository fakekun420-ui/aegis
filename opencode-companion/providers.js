// providers.js — Universal AI Coding Agent Provider Abstraction
// Robust adapter layer for OpenCode (HTTP serve mode) and Antigravity (agy CLI print/json mode)
// Hard 90s timeouts, connection leak prevention, zombie process cleanup, atomic concurrency.

import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { spawn } from "node:child_process";

// ==========================================
// Atomic Storage & Concurrency Utilities
// ==========================================

export class FileMutex {
  constructor() {
    this.queues = new Map();
  }

  async runExclusive(filePath, fn) {
    const key = path.resolve(filePath);
    let queue = this.queues.get(key) || Promise.resolve();
    const next = queue.then(async () => {
      try {
        return await fn();
      } finally {
        if (this.queues.get(key) === next) {
          this.queues.delete(key);
        }
      }
    });
    this.queues.set(key, next.catch(() => {}));
    return next;
  }
}

export const fileMutex = new FileMutex();

export function atomicReadFileSync(filePath, fallback = null) {
  try {
    if (!fs.existsSync(filePath)) return fallback;
    const content = fs.readFileSync(filePath, "utf8");
    return JSON.parse(content);
  } catch (e) {
    console.error(`[storage] atomicReadFileSync error reading ${filePath}:`, e.message);
    return fallback;
  }
}

export function atomicWriteFileSync(filePath, data) {
  const dir = path.dirname(filePath);
  if (!fs.existsSync(dir)) {
    fs.mkdirSync(dir, { recursive: true });
  }
  const tmpFile = path.join(
    dir,
    `.${path.basename(filePath)}.${Date.now()}.${Math.random().toString(36).slice(2)}.tmp`
  );
  const content = typeof data === "string" ? data : JSON.stringify(data, null, 2);
  const fd = fs.openSync(tmpFile, "w");
  try {
    fs.writeFileSync(fd, content, "utf8");
    fs.fsyncSync(fd);
  } finally {
    fs.closeSync(fd);
  }
  fs.renameSync(tmpFile, filePath);
}

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
      ...(p.url ? { url: p.url } : {})
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
    // Pre-warm models cache in background
    setTimeout(() => { this.listModels().catch(() => {}); }, 1500);
  }

  async isHealthy() {
    return new Promise((resolve) => {
      let resolved = false;
      const r = http.get(
        {
          hostname: this.host,
          port: this.port,
          path: "/global/health",
          timeout: 2500,
          headers: { Connection: "close" }
        },
        (res) => {
          let d = "";
          res.on("data", (c) => (d += c));
          res.on("end", () => {
            if (resolved) return;
            resolved = true;
            try {
              const j = JSON.parse(d);
              resolve({ up: true, healthy: !!j.healthy, version: j.version || null });
            } catch {
              resolve({ up: res.statusCode === 200, healthy: false, version: null });
            }
          });
          res.on("error", (e) => {
            if (resolved) return;
            resolved = true;
            resolve({ up: false, healthy: false, error: e.message });
          });
        }
      );
      r.on("error", (e) => {
        if (resolved) return;
        resolved = true;
        resolve({ up: false, healthy: false, error: e.message });
      });
      r.setTimeout(2500, () => {
        if (resolved) return;
        resolved = true;
        try { r.destroy(); } catch (_) {}
        resolve({ up: false, healthy: false, error: "timeout" });
      });
    });
  }

  async listSessions() {
    return new Promise((resolve, reject) => {
      let resolved = false;
      const r = http.get(
        {
          hostname: this.host,
          port: this.port,
          path: "/session",
          timeout: 8000,
          headers: { Connection: "close" }
        },
        (res) => {
          let d = "";
          res.on("data", (c) => (d += c));
          res.on("end", () => {
            if (resolved) return;
            resolved = true;
            try {
              const payload = JSON.parse(d || "[]");
              const rawList = Array.isArray(payload)
                ? payload
                : (payload.sessions || payload.data || []);
              const normalized = rawList.map((s) => ({
                id: s.id || s.ID || "",
                title: s.title || s.name || s.id || "untitled",
                createdAt: s.time?.created
                  ? new Date(s.time.created).toISOString()
                  : (s.createdAt || s.created_at || new Date().toISOString()),
                updatedAt: s.time?.updated
                  ? new Date(s.time.updated).toISOString()
                  : (s.updatedAt || s.updated_at || s.createdAt || new Date().toISOString()),
                provider: "opencode",
                raw: s
              }));
              resolve(normalized);
            } catch (e) {
              reject(new Error(`Failed to parse OpenCode sessions: ${e.message}`));
            }
          });
          res.on("error", (e) => {
            if (resolved) return;
            resolved = true;
            reject(e);
          });
        }
      );
      r.on("error", (e) => {
        if (resolved) return;
        resolved = true;
        reject(e);
      });
      r.setTimeout(8000, () => {
        if (resolved) return;
        resolved = true;
        try { r.destroy(); } catch (_) {}
        reject(new Error("timeout GET /session"));
      });
    });
  }

  async createSession(opts = {}) {
    const title = opts.title || `session:${Date.now().toString(36)}`;
    const postData = JSON.stringify({ title });
    return new Promise((resolve, reject) => {
      let resolved = false;
      const req = http.request(
        {
          hostname: this.host,
          port: this.port,
          path: "/session",
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "Content-Length": Buffer.byteLength(postData),
            Connection: "close"
          },
          timeout: 8000
        },
        (res) => {
          let d = "";
          res.on("data", (c) => (d += c));
          res.on("end", () => {
            if (resolved) return;
            resolved = true;
            try {
              const j = JSON.parse(d || "{}");
              const id = j.id || j.ID || (j.data && (j.data.id || j.data.ID)) || null;
              if (!id) return reject(new Error(`Failed to create opencode session: ${d}`));
              resolve({
                id,
                title,
                createdAt: new Date().toISOString(),
                provider: "opencode",
                raw: j
              });
            } catch (e) {
              reject(new Error(`Failed to parse createSession response: ${e.message}`));
            }
          });
          res.on("error", (e) => {
            if (resolved) return;
            resolved = true;
            reject(e);
          });
        }
      );
      req.on("error", (e) => {
        if (resolved) return;
        resolved = true;
        reject(e);
      });
      req.setTimeout(8000, () => {
        if (resolved) return;
        resolved = true;
        try { req.destroy(); } catch (_) {}
        reject(new Error("timeout POST /session"));
      });
      req.write(postData);
      req.end();
    });
  }

  async renameSession(sessionId, title) {
    return new Promise((resolve, reject) => {
      const postData = JSON.stringify({ title });
      const req = http.request(
        {
          hostname: this.host,
          port: this.port,
          path: `/session/${encodeURIComponent(sessionId)}`,
          method: "PATCH",
          headers: {
            "Content-Type": "application/json",
            "Content-Length": Buffer.byteLength(postData),
            Connection: "close"
          }
        },
        (res) => {
          let d = "";
          res.on("data", (c) => (d += c));
          res.on("end", () => {
            try {
              const j = JSON.parse(d || "{}");
              resolve(j);
            } catch {
              resolve({ id: sessionId, title });
            }
          });
        }
      );
      req.on("error", (e) => reject(e));
      req.setTimeout(8000, () => {
        try { req.destroy(); } catch (_) {}
        reject(new Error("timeout PATCH /session"));
      });
      req.write(postData);
      req.end();
    });
  }

  async deleteSession(sessionId) {
    return new Promise((resolve, reject) => {
      const req = http.request(
        {
          hostname: this.host,
          port: this.port,
          path: `/session/${encodeURIComponent(sessionId)}`,
          method: "DELETE",
          headers: { Connection: "close" }
        },
        (res) => {
          let d = "";
          res.on("data", (c) => (d += c));
          res.on("end", () => {
            resolve({ id: sessionId, deleted: true });
          });
        }
      );
      req.on("error", (e) => reject(e));
      req.setTimeout(8000, () => {
        try { req.destroy(); } catch (_) {}
        reject(new Error("timeout DELETE /session"));
      });
      req.end();
    });
  }

  async getMessages(sessionId, opts = {}) {
    return new Promise((resolve, reject) => {
      let resolved = false;
      const reqOpts = {
        hostname: this.host,
        port: this.port,
        path: `/session/${encodeURIComponent(sessionId)}/message`,
        timeout: 15000,
        headers: { Connection: "close" }
      };

      const r = http.get(reqOpts, (res) => {
        let d = "";
        res.on("data", (c) => (d += c));
        res.on("end", () => {
          if (resolved) return;
          resolved = true;
          try {
            let payload;
            try {
              payload = JSON.parse(d || "[]");
            } catch {
              payload = d;
            }
            const rawList = Array.isArray(payload)
              ? payload
              : (payload.messages || payload.data || []);
            const normalized = rawList.map((m, idx) => normalizeMessage(m, sessionId, idx));
            resolve(normalized);
          } catch (e) {
            reject(new Error(`Failed to parse messages from OpenCode: ${e.message}`));
          }
        });
        res.on("error", (e) => {
          if (resolved) return;
          resolved = true;
          reject(e);
        });
      });

      if (opts.signal) {
        opts.signal.addEventListener("abort", () => {
          if (resolved) return;
          resolved = true;
          try { r.destroy(); } catch (_) {}
          reject(new Error("OpenCode getMessages aborted by client"));
        }, { once: true });
      }

      r.on("error", (e) => {
        if (resolved) return;
        resolved = true;
        reject(e);
      });
      r.setTimeout(15000, () => {
        if (resolved) return;
        resolved = true;
        try { r.destroy(); } catch (_) {}
        reject(new Error(`timeout GET /session/${sessionId}/message`));
      });
    });
  }

  async sendMessage(sessionId, payload = {}, opts = {}) {
    // 1. Prepare OpenCode payload structure: ensure parts array
    let parts = [];
    if (Array.isArray(payload.parts) && payload.parts.length > 0) {
      parts = [...payload.parts];
    } else if (typeof payload.text === "string" && payload.text.trim()) {
      parts = [{ type: "text", text: payload.text.trim() }];
    } else if (typeof payload.prompt === "string" && payload.prompt.trim()) {
      parts = [{ type: "text", text: payload.prompt.trim() }];
    } else {
      parts = [{ type: "text", text: "" }];
    }

    // 2. Handle attached files if present
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
          parts.push({ type: "text", text: fileText });
        }
      }
    }

    // 3. Inject system context block silently as a system prompt (NEVER in visible parts)
    const projectId = payload.projectId || opts.projectId || null;
    const block = this.getSystemContextBlock(projectId);
    const finalPayload = { parts };
    if (payload.system && typeof payload.system === "string") {
      finalPayload.system = payload.system;
    } else if (block && block.trim()) {
      finalPayload.system = block.trim();
    }
    const agentMode = payload.agent || payload.mode || opts.agent || opts.mode || "build";
    finalPayload.agent = agentMode;
    if (payload.model) {
      if (typeof payload.model === "object" && payload.model.modelID) {
        finalPayload.model = payload.model;
      } else if (typeof payload.model === "string" && payload.model.trim()) {
        const mStr = payload.model.trim();
        let providerID = "opencode";
        let modelID = mStr;
        if (mStr.includes("/")) {
          const parts = mStr.split("/");
          providerID = parts[0];
          modelID = parts.slice(1).join("/");
        }
        finalPayload.model = { modelID, providerID };
      }
    }

    const postData = JSON.stringify(finalPayload);

    return new Promise((resolve, reject) => {
      let resolved = false;

      const req = http.request(
        {
          hostname: this.host,
          port: this.port,
          path: `/session/${encodeURIComponent(sessionId)}/message`,
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "Content-Length": Buffer.byteLength(postData),
            Connection: "close"
          },
          timeout: 90000 // Hard 90s timeout per specification
        },
        (res) => {
          let d = "";
          res.on("data", (c) => (d += c));
          res.on("end", () => {
            if (resolved) return;
            resolved = true;
            try {
              const j = JSON.parse(d || "{}");
              if (j.name === "BadRequest" || j.error) {
                return reject(new Error(j.data?.message || j.message || j.error || "OpenCode error"));
              }
              const normalized = normalizeMessage(j, sessionId);
              if (typeof opts.onChunk === "function" && normalized.text) {
                try { opts.onChunk(normalized.text); } catch (_) {}
              }
              resolve(normalized);
            } catch (e) {
              reject(new Error(`Failed to parse opencode response: ${d} (${e.message})`));
            }
          });
          res.on("error", (e) => {
            if (resolved) return;
            resolved = true;
            reject(e);
          });
        }
      );

      // Manage abort signal from client connection drop
      if (opts.signal) {
        opts.signal.addEventListener("abort", () => {
          if (resolved) return;
          resolved = true;
          try { req.destroy(); } catch (_) {}
          reject(new Error("OpenCode sendMessage aborted by client"));
        }, { once: true });
      }

      req.on("error", (e) => {
        if (resolved) return;
        resolved = true;
        reject(e);
      });

      // Hard 90s timeout guard
      req.setTimeout(90000, () => {
        if (resolved) return;
        resolved = true;
        try { req.destroy(); } catch (_) {}
        reject(new Error("OpenCode execution timed out after 90s"));
      });

      req.write(postData);
      req.end();
    });
  }

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
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), 12000);
      const res = await fetch(`http://${this.host}:${this.port}/provider`, { signal: controller.signal });
      clearTimeout(timer);

      if (res.ok) {
        const data = await res.json();
        const connected = Array.isArray(data.connected) ? data.connected : ["opencode"];
        const allProviders = Array.isArray(data.all) ? data.all : [];
        const models = [];

        // Include connected providers prioritizing opencode and google
        const priority = ["opencode", "google", "xiaomi", "openrouter"];
        const targetProviders = allProviders.filter(p => connected.includes(p.id))
          .sort((a, b) => {
            const idxA = priority.indexOf(a.id);
            const idxB = priority.indexOf(b.id);
            return (idxA === -1 ? 99 : idxA) - (idxB === -1 ? 99 : idxB);
          });

        for (const prov of targetProviders) {
          for (const [key, m] of Object.entries(prov.models || {})) {
            if (m.status && m.status !== "active") continue;
            models.push({
              id: m.id || key,
              name: m.name || key,
              description: `${prov.name || prov.id} · ${m.family || "AI"}`
            });
          }
        }

        if (models.length > 0) {
          this._modelsCache = models;
          this._modelsCacheTime = now;
          return models;
        }
      }
    } catch (e) {
      console.warn("[opencode] listModels fetch error:", e.message);
    } finally {
      this._fetchingModels = false;
    }

    return this._fallbackModels();
  }

  _fallbackModels() {
    return [
      { id: "claude-sonnet-4-6", name: "Claude Sonnet 4.6", description: "OpenCode Zen · claude-sonnet" },
      { id: "gemini-3.1-pro", name: "Gemini 3.1 Pro Preview", description: "OpenCode Zen · gemini-pro" },
      { id: "gemini-3.6-flash", name: "Gemini 3.6 Flash", description: "OpenCode Zen · gemini-flash" },
      { id: "deepseek-v4-flash", name: "DeepSeek V4 Flash", description: "OpenCode Zen · deepseek" },
      { id: "gpt-5-codex", name: "GPT-5 Codex", description: "OpenCode Zen · gpt-codex" },
      { id: "mimo-v2.5-free", name: "Mimo v2.5 Free", description: "OpenCode Zen · mimo-free" },
      { id: "nemotron-3-ultra-free", name: "Nemotron 3 Ultra Free", description: "OpenCode Zen · nemotron-free" }
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
            console.log(`[antigravity] Reaping orphaned/zombie agy process: pid=${pid}, state=${state}, ppid=${ppid}`);
            try {
              process.kill(pid, "SIGKILL");
              reapedCount++;
            } catch (_) {}
          }
        } catch (_) {}
      }
      return reapedCount;
    } catch (e) {
      console.error("[antigravity] cleanupZombieProcesses err:", e.message);
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
      console.error("[antigravity] listSessions err", e.message);
    }
    return sessions;
  }

  async createSession(opts = {}) {
    const id = `agy_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 10)}`;
    const title = opts.title || `Antigravity session`;
    return {
      id,
      title,
      createdAt: new Date().toISOString(),
      provider: "antigravity"
    };
  }

  async deleteSession(sessionId) {
    const convId = this.sessionMap.get(sessionId) || sessionId;
    this.sessionMap.delete(sessionId);
    const targetDir = path.join(this.brainDir, convId);
    if (fs.existsSync(targetDir)) {
      try {
        fs.rmSync(targetDir, { recursive: true, force: true });
        console.log(`[antigravity] purged brain directory: ${targetDir}`);
      } catch (err) {
        console.warn(`[antigravity] failed to remove brain directory ${targetDir}:`, err.message);
      }
    }
    return { ok: true, removed: sessionId };
  }

  _cleanPromptContent(raw) {
    if (!raw) return "";
    let str = String(raw);
    const m = str.match(/<USER_REQUEST>([\s\S]*?)<\/USER_REQUEST>/i);
    if (m && m[1]) return m[1].trim();
    str = str.replace(/<SYSTEM_INSTRUCTION>[\s\S]*?<\/SYSTEM_INSTRUCTION>/gi, "").trim();
    str = str.replace(/<SYSTEM_CONTEXT[\s\S]*?<\/SYSTEM_CONTEXT>/gi, "").trim();
    str = str.replace(/\[SYSTEM CONTEXT[\s\S]*?\][\s\S]*?(?:---\n\n|$)/gi, "").trim();
    str = str.replace(/# PONY-TAIL[\s\S]*?(?:---\n\n|$)/gi, "").trim();
    str = str.replace(/<memory_context>[\s\S]*?<\/memory_context>/gi, "").trim();
    str = str.replace(/<ADDITIONAL_METADATA>[\s\S]*?<\/ADDITIONAL_METADATA>/gi, "").trim();
    str = str.replace(/<USER_SETTINGS_CHANGE>[\s\S]*?<\/USER_SETTINGS_CHANGE>/gi, "").trim();
    return str;
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

      for (const lineStr of lines) {
        try {
          const item = JSON.parse(lineStr);
          const isUser = item.source === "USER_EXPLICIT" || item.type === "USER_INPUT";
          const isAssistant = item.source === "MODEL" || item.type === "PLANNER_RESPONSE";

          if (!isUser && !isAssistant) continue;

          let text = item.content || "";
          if (isUser) {
            text = this._cleanPromptContent(text);
          }

          if (!text.trim()) continue;

          const createdTime = item.created_at ? new Date(item.created_at).getTime() : Date.now();

          messages.push(
            normalizeMessage(
              {
                role: isUser ? "user" : "assistant",
                text: text.trim(),
                info: {
                  id: `msg_agy_${sessionId}_${item.step_index ?? messages.length}`,
                  role: isUser ? "user" : "assistant",
                  time: { created: createdTime },
                  timestamp: createdTime,
                  status: "SENT",
                  deliveryStatus: "SENT"
                },
                parts: [
                  {
                    id: `prt_${item.step_index ?? messages.length}`,
                    type: "text",
                    text: text.trim()
                  }
                ]
              },
              sessionId,
              messages.length
            )
          );
        } catch (_) {}
      }

      return messages;
    } catch (e) {
      console.error(`[antigravity] getMessages err for ${sessionId}:`, e.message);
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
    if (payload.model) {
      args.push("--model", String(payload.model));
    }
    const agentMode = payload.agent || payload.mode || opts.agent || opts.mode || "build";
    if (agentMode === "plan") {
      args.push("--mode", "plan");
    } else {
      args.push("--mode", "accept-edits");
    }
    args.push("-p", promptForAgy);
    if (isStreaming) {
      args.push("--output-format", "text");
    } else {
      args.push("--output-format", "json");
    }
    args.push(
      "--dangerously-skip-permissions",
      "--print-timeout",
      "85s"
    );

    console.log(`[antigravity] executing agy for session ${sessionId} (convId: ${convId || "new"}, streaming: ${isStreaming})...`);

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

      p.stdout.on("data", (c) => {
        const text = c.toString("utf8");
        stdout += text;
        if (isStreaming && typeof opts.onChunk === "function") {
          try { opts.onChunk(text); } catch (_) {}
        }
      });
      p.stderr.on("data", (c) => (stderr += c));

      // 90 second hard timeout guard with escalation
      const timer = setTimeout(() => {
        if (isDone) return;
        isDone = true;
        console.warn(`[antigravity] Process ${p.pid} exceeded hard 90s timeout. Killing group with SIGTERM...`);
        killGroup(p, "SIGTERM");
        const killTimer = setTimeout(() => {
          try {
            console.warn(`[antigravity] Escalating to SIGKILL for process group ${p.pid}...`);
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
            console.log(`[antigravity] Client connection aborted. Killing process group ${p.pid}...`);
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

        if (code !== 0 && !stdout.trim()) {
          return reject(new Error(`Antigravity exited with code ${code}: ${stderr.trim()}`));
        }

        if (isStreaming) {
          let afterConvId = convId;
          if (!afterConvId && fs.existsSync(this.brainDir)) {
            const curDirs = fs.readdirSync(this.brainDir);
            const newDirs = curDirs.filter((d) => !beforeDirs.has(d));
            if (newDirs.length > 0) afterConvId = newDirs[0];
          }
          if (afterConvId) this.sessionMap.set(sessionId, afterConvId);

          return resolve({
            response: stdout.trim(),
            role: "assistant",
            conversation_id: afterConvId || sessionId
          });
        }

        try {
          const trimmed = stdout.trim();
          const jsonMatch = trimmed.match(/\{[\s\S]*\}/);
          if (!jsonMatch) {
            return reject(new Error(`No JSON found in agy output: ${trimmed}`));
          }
          const parsed = JSON.parse(jsonMatch[0]);
          resolve(parsed);
        } catch (e) {
          reject(new Error(`Failed to parse agy JSON: ${stdout} (error: ${e.message})`));
        }
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

    const responseText = (result.response || "").trim();

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
        parts: [
          {
            id: `prt_${Date.now()}`,
            type: "text",
            text: responseText
          }
        ],
        _agyMeta: {
          conversationId: result.conversation_id,
          durationSeconds: result.duration_seconds,
          usage: result.usage
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
  constructor(configFilePath = "/sdcard/projects/opencode-companion/providers.json") {
    this.configFilePath = configFilePath;
    this.adapters = new Map();
    this.defaultProvider = "opencode";
    this.loadConfig();
  }

  loadConfig() {
    try {
      const raw = atomicReadFileSync(this.configFilePath, null);
      if (raw && raw.defaultProvider) {
        this.defaultProvider = raw.defaultProvider;
      }
    } catch (e) {
      console.error("[provider-mgr] loadConfig err", e.message);
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

  // Resolve which provider should handle a session or request
  resolveProvider(sessionId, explicitProvider = null, projectsStore = null) {
    if (explicitProvider && this.adapters.has(explicitProvider.toLowerCase())) {
      return this.adapters.get(explicitProvider.toLowerCase());
    }

    if (sessionId && projectsStore && Array.isArray(projectsStore.projects)) {
      for (const p of projectsStore.projects) {
        const sess = (p.sessions || []).find((s) => s.sessionId === sessionId);
        if (sess) {
          const provId = (sess.provider || p.provider || this.defaultProvider).toLowerCase();
          if (this.adapters.has(provId)) return this.adapters.get(provId);
        }
      }
    }

    if (
      sessionId &&
      (sessionId.startsWith("agy_") ||
        fs.existsSync(path.join("/root/.gemini/antigravity-cli/brain", sessionId)))
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
        console.warn(`[provider-mgr] listSessions error for ${adapter.id}:`, e.message);
      }
    }

    all.sort((a, b) => (b.updatedAt || b.createdAt || "").localeCompare(a.updatedAt || a.createdAt || ""));
    return all;
  }
}
