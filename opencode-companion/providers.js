// providers.js — Universal AI Coding Agent Provider Abstraction
// Supports OpenCode (HTTP serve proxy) and Antigravity (agy CLI non-interactive)

import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { spawn } from "node:child_process";

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

  async getMessages(sessionId) {
    throw new Error(`getMessages() not implemented on ${this.name}`);
  }

  async sendMessage(sessionId, payload = {}) {
    throw new Error(`sendMessage() not implemented on ${this.name}`);
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
  }

  async isHealthy() {
    return new Promise((resolve) => {
      const r = http.get(
        { hostname: this.host, port: this.port, path: "/global/health", timeout: 2500 },
        (res) => {
          let d = "";
          res.on("data", (c) => (d += c));
          res.on("end", () => {
            try {
              const j = JSON.parse(d);
              resolve({ up: true, healthy: !!j.healthy, version: j.version || null });
            } catch {
              resolve({ up: true, healthy: false, version: null });
            }
          });
        }
      );
      r.on("error", (e) => resolve({ up: false, healthy: false, error: e.message }));
      r.setTimeout(2500, () => {
        try { r.destroy(); } catch (_) {}
        resolve({ up: false, healthy: false, error: "timeout" });
      });
    });
  }

  async listSessions() {
    return new Promise((resolve, reject) => {
      const r = http.get(
        { hostname: this.host, port: this.port, path: "/session", timeout: 5000 },
        (res) => {
          let d = "";
          res.on("data", (c) => (d += c));
          res.on("end", () => {
            try {
              const payload = JSON.parse(d || "[]");
              const rawList = Array.isArray(payload) ? payload : (payload.sessions || payload.data || []);
              const normalized = rawList.map((s) => ({
                id: s.id || s.ID || "",
                title: s.title || s.name || s.id || "untitled",
                createdAt: s.time?.created ? new Date(s.time.created).toISOString() : (s.createdAt || s.created_at || new Date().toISOString()),
                updatedAt: s.time?.updated ? new Date(s.time.updated).toISOString() : (s.updatedAt || s.updated_at || s.createdAt || new Date().toISOString()),
                provider: "opencode",
                raw: s
              }));
              resolve(normalized);
            } catch (e) {
              reject(e);
            }
          });
        }
      );
      r.on("error", reject);
      r.setTimeout(5000, () => {
        try { r.destroy(); } catch (_) {}
        reject(new Error("timeout GET /session"));
      });
    });
  }

  async createSession(opts = {}) {
    const title = opts.title || `session:${Date.now().toString(36)}`;
    const postData = JSON.stringify({ title });
    return new Promise((resolve, reject) => {
      const req = http.request(
        {
          hostname: this.host,
          port: this.port,
          path: "/session",
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "Content-Length": Buffer.byteLength(postData)
          },
          timeout: 8000
        },
        (res) => {
          let d = "";
          res.on("data", (c) => (d += c));
          res.on("end", () => {
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
              reject(e);
            }
          });
        }
      );
      req.on("error", reject);
      req.setTimeout(8000, () => {
        try { req.destroy(); } catch (_) {}
        reject(new Error("timeout POST /session"));
      });
      req.write(postData);
      req.end();
    });
  }

  async getMessages(sessionId) {
    return new Promise((resolve, reject) => {
      const r = http.get(
        {
          hostname: this.host,
          port: this.port,
          path: `/session/${encodeURIComponent(sessionId)}/message`,
          timeout: 8000
        },
        (res) => {
          let d = "";
          res.on("data", (c) => (d += c));
          res.on("end", () => {
            try {
              let payload;
              try { payload = JSON.parse(d || "[]"); } catch { payload = d; }
              const list = Array.isArray(payload) ? payload : (payload.messages || payload.data || []);
              resolve(list);
            } catch (e) {
              reject(e);
            }
          });
        }
      );
      r.on("error", reject);
      r.setTimeout(8000, () => {
        try { r.destroy(); } catch (_) {}
        reject(new Error(`timeout GET /session/${sessionId}/message`));
      });
    });
  }

  async sendMessage(sessionId, payload = {}) {
    // Inject system context block if projectId is present
    let finalPayload = { ...payload };
    const projectId = payload.projectId || null;
    if (projectId) {
      const block = this.getSystemContextBlock(projectId);
      if (block) {
        if (Array.isArray(finalPayload.parts)) {
          finalPayload.parts = [
            { type: "text", text: `[SYSTEM CONTEXT — skills + linked projects]\n${block}` },
            ...finalPayload.parts
          ];
        } else if (typeof finalPayload.text === "string") {
          finalPayload.text = `[SYSTEM CONTEXT — skills + linked projects]\n${block}\n\n---\n\n${finalPayload.text}`;
        }
      }
    }

    const postData = JSON.stringify(finalPayload);
    return new Promise((resolve, reject) => {
      const req = http.request(
        {
          hostname: this.host,
          port: this.port,
          path: `/session/${encodeURIComponent(sessionId)}/message`,
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "Content-Length": Buffer.byteLength(postData)
          },
          timeout: 120000
        },
        (res) => {
          let d = "";
          res.on("data", (c) => (d += c));
          res.on("end", () => {
            try {
              const j = JSON.parse(d || "{}");
              resolve(j);
            } catch (e) {
              reject(new Error(`Failed to parse opencode response: ${d}`));
            }
          });
        }
      );
      req.on("error", reject);
      req.setTimeout(120000, () => {
        try { req.destroy(); } catch (_) {}
        reject(new Error(`timeout POST /session/${sessionId}/message`));
      });
      req.write(postData);
      req.end();
    });
  }

  async listModels() {
    return [
      { id: "gemini-3.6-flash", name: "Gemini 3.6 Flash", description: "Rápido y eficiente" },
      { id: "gemini-3.6-flash-lite", name: "Gemini 3.6 Flash Lite", description: "Más rápido, menos preciso" },
      { id: "gemini-2.5-pro", name: "Gemini 2.5 Pro", description: "Alta calidad, más lento" },
      { id: "gpt-4o", name: "GPT-4o", description: "OpenAI multihabilidad" },
      { id: "gpt-4o-mini", name: "GPT-4o Mini", description: "Rápido y económico" },
      { id: "claude-sonnet-4-20250514", name: "Claude Sonnet 4", description: "Balance calidad/velocidad" },
      { id: "claude-3-5-haiku-20241022", name: "Claude 3.5 Haiku", description: "Ultrarrápido" }
    ];
  }
}

// ==========================================
// Antigravity Adapter (CLI Print/JSON Mode)
// ==========================================
export class AntigravityAdapter extends BaseProviderAdapter {
  constructor(options = {}) {
    super("antigravity", "Antigravity", "cli");
    this.binPath = options.binPath || (fs.existsSync("/root/.local/bin/agy") ? "/root/.local/bin/agy" : "agy");
    this.brainDir = options.brainDir || "/root/.gemini/antigravity-cli/brain";
    this.cwd = options.cwd || "/sdcard/projects";
    this.getSystemContextBlock = options.getSystemContextBlock || (() => null);
    this.sessionMap = new Map(); // sessionId -> agyConversationId
  }

  async isHealthy() {
    try {
      if (fs.existsSync(this.binPath)) {
        return { up: true, healthy: true, version: "agy" };
      }
      return { up: false, healthy: false, error: `agy binary not found at ${this.binPath}` };
    } catch (e) {
      return { up: false, healthy: false, error: e.message };
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
        const transcriptPath = path.join(this.brainDir, convId, ".system_generated/logs/transcript.jsonl");
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
    // Generate a unique session ID in standard UUID format
    const id = `agy_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 10)}`;
    const title = opts.title || `Antigravity session`;
    return {
      id,
      title,
      createdAt: new Date().toISOString(),
      provider: "antigravity"
    };
  }

  _cleanPromptContent(raw) {
    if (!raw) return "";
    let str = String(raw);
    const m = str.match(/<USER_REQUEST>([\s\S]*?)<\/USER_REQUEST>/i);
    if (m && m[1]) str = m[1].trim();
    // Strip XML blocks that might be in the prompt
    str = str.replace(/<ADDITIONAL_METADATA>[\s\S]*?<\/ADDITIONAL_METADATA>/gi, "").trim();
    str = str.replace(/<USER_SETTINGS_CHANGE>[\s\S]*?<\/USER_SETTINGS_CHANGE>/gi, "").trim();
    return str;
  }

  async getMessages(sessionId) {
    const convId = this.sessionMap.get(sessionId) || sessionId;
    const transcriptPath = path.join(this.brainDir, convId, ".system_generated/logs/transcript.jsonl");

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

          messages.push({
            info: {
              id: `msg_agy_${sessionId}_${item.step_index ?? messages.length}`,
              role: isUser ? "user" : "assistant",
              time: { created: createdTime }
            },
            parts: [
              {
                id: `prt_${item.step_index ?? messages.length}`,
                type: "text",
                text: text.trim()
              }
            ]
          });
        } catch (_) {}
      }

      return messages;
    } catch (e) {
      console.error(`[antigravity] getMessages err for ${sessionId}:`, e.message);
      return [];
    }
  }

  async sendMessage(sessionId, payload = {}) {
    // 1. Resolve prompt text
    let userPrompt = "";
    if (typeof payload.text === "string" && payload.text.trim()) {
      userPrompt = payload.text.trim();
    } else if (typeof payload.prompt === "string" && payload.prompt.trim()) {
      userPrompt = payload.prompt.trim();
    } else if (Array.isArray(payload.parts)) {
      const textParts = payload.parts
        .filter((p) => p.type === "text" && typeof p.text === "string")
        .map((p) => p.text);
      userPrompt = textParts.join("\n").trim();

      // Handle attached files if any
      const fileParts = payload.parts.filter((p) => p.type === "file");
      for (const fp of fileParts) {
        if (fp.filename) {
          userPrompt += `\n\n[Attached File: ${fp.filename}]`;
          if (fp.url && fp.url.startsWith("data:") && fp.url.includes(";base64,")) {
            try {
              const base64Data = fp.url.split(";base64,")[1];
              const decoded = Buffer.from(base64Data, "base64").toString("utf8");
              // If decoded looks like plain text or markdown, append it
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

    // 2. Inject system context (skills + linked projects) if available
    const projectId = payload.projectId || null;
    if (projectId) {
      const block = this.getSystemContextBlock(projectId);
      if (block) {
        userPrompt = `[SYSTEM CONTEXT — skills + linked projects]\n${block}\n\n---\n\n${userPrompt}`;
      }
    }

    // 3. Check if we have an existing Antigravity conversation ID
    let convId = this.sessionMap.get(sessionId) || null;
    if (!convId) {
      // Check if sessionId itself exists in brainDir
      if (fs.existsSync(path.join(this.brainDir, sessionId))) {
        convId = sessionId;
      }
    }

    // 4. Build agy CLI spawn arguments
    const args = [];
    if (convId && fs.existsSync(path.join(this.brainDir, convId))) {
      args.push("--conversation", convId);
    }
    if (payload.model) {
      args.push("--model", String(payload.model));
    }
    args.push("-p", userPrompt, "--output-format", "json", "--dangerously-skip-permissions");

    console.log(`[antigravity] executing agy for session ${sessionId} (convId: ${convId || "new"})...`);

    // 5. Spawn agy and await output
    const result = await new Promise((resolve, reject) => {
      const p = spawn(this.binPath, args, {
        cwd: this.cwd,
        env: {
          ...process.env,
          HOME: "/root",
          PATH: `/root/.local/bin:${process.env.PATH || ""}`
        }
      });

      let stdout = "";
      let stderr = "";

      p.stdout.on("data", (c) => (stdout += c));
      p.stderr.on("data", (c) => (stderr += c));

      // 90 second timeout guard
      const timer = setTimeout(() => {
        try { p.kill("SIGTERM"); } catch (_) {}
        reject(new Error("Antigravity CLI execution timed out after 90s"));
      }, 90000);

      p.on("close", (code) => {
        clearTimeout(timer);
        if (code !== 0 && !stdout.trim()) {
          return reject(new Error(`Antigravity exited with code ${code}: ${stderr.trim()}`));
        }
        try {
          const trimmed = stdout.trim();
          // Find JSON line in stdout (in case of leading banner/warning text)
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
        clearTimeout(timer);
        reject(new Error(`Failed to spawn agy binary: ${err.message}`));
      });
    });

    // 6. Record returned conversation ID
    if (result.conversation_id) {
      this.sessionMap.set(sessionId, result.conversation_id);
    }

    const responseText = (result.response || "").trim();

    // 7. Return standard Message format matching Companion App expectations
    return {
      info: {
        id: `msg_agy_${sessionId}_${Date.now()}`,
        role: "assistant",
        time: { created: Date.now() }
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
    };
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
      if (fs.existsSync(this.configFilePath)) {
        const raw = JSON.parse(fs.readFileSync(this.configFilePath, "utf8"));
        if (raw.defaultProvider) this.defaultProvider = raw.defaultProvider;
      }
    } catch (e) {
      console.error("[provider-mgr] loadConfig err", e.message);
    }
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

    if (sessionId && (sessionId.startsWith("agy_") || fs.existsSync(path.join("/root/.gemini/antigravity-cli/brain", sessionId)))) {
      return this.adapters.get("antigravity");
    }

    return this.adapters.get(this.defaultProvider);
  }

  // Unified messages: merges history from OpenCode and Antigravity so NO context is lost on provider switch
  async getUnifiedMessages(sessionId) {
    let ocMsgs = [];
    let agyMsgs = [];

    const oc = this.adapters.get("opencode");
    if (oc) {
      try {
        ocMsgs = await oc.getMessages(sessionId);
      } catch (_) {}
    }

    const agy = this.adapters.get("antigravity");
    if (agy) {
      try {
        agyMsgs = await agy.getMessages(sessionId);
      } catch (_) {}
    }

    if (ocMsgs.length > 0 && agyMsgs.length > 0) {
      const merged = [...ocMsgs, ...agyMsgs];
      merged.sort((a, b) => (a.info?.time?.created || 0) - (b.info?.time?.created || 0));
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
