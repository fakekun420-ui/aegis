// OpenCodeAdapter.js — OpenCode HTTP Serve Mode Adapter
// Extracted from providers.js (Strangler Fig Step 4)
// Zero behavior changes — exact copy of OpencodeAdapter class

import http from "node:http";
import { BaseProviderAdapter } from "./BaseProviderAdapter.js";
import { normalizeMessage } from "../../providers.js"; // Temporary: still imports from providers.js until normalizeMessage is extracted

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
              const isUserMsg = j.role === "user" || j.type === "user" || (j.info && j.info.role === "user");
              if (isUserMsg) {
                console.log(`[opencode] user prompt acknowledged for ${sessionId}, polling for assistant response...`);
                const startTime = Date.now();
                const pollTimer = setInterval(async () => {
                  if (Date.now() - startTime > 45000) {
                    clearInterval(pollTimer);
                    return resolve(normalizeMessage({ role: "assistant", text: "" }, sessionId));
                  }
                  try {
                    const msgs = await this.getMessages(sessionId);
                    const lastAssistant = msgs.filter((m) => m.role === "assistant" && m.text).pop();
                    if (lastAssistant) {
                      clearInterval(pollTimer);
                      if (typeof opts.onChunk === "function" && lastAssistant.text) {
                        try { opts.onChunk(lastAssistant.text); } catch (_) {}
                      }
                      return resolve(lastAssistant);
                    }
                  } catch (_) {}
                }, 1000);
                return;
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