// ClaudeCodeAdapter.js — Claude Code CLI Adapter
// Extracted for Phase 3

import fs from "node:fs";
import { spawn } from "node:child_process";
import { BaseProviderAdapter } from "./BaseProviderAdapter.js";
// A-4 (BACKEND-BUG-06/07): dependencia fantasma eliminada — "../core/normalizer.js"
// NO existe (el módulo nunca se extrajo; ver docs/AEGIS_MASTER_PROMPT.md Fase 2).
// Como este adapter no tiene fallback, se corrige la referencia a la función que SÍ
// existe: normalizeMessage (export de providers.js), idéntico a lo que ya hacen sus
// hermanos OpenCodeAdapter/AntigravityAdapter — cero lógica duplicada.
import { normalizeMessage } from "../../providers.js";

export class ClaudeCodeAdapter extends BaseProviderAdapter {
  constructor(options = {}) {
    super("claudecode", "Claude Code", "cli");
    this.binPath = options.binPath || (fs.existsSync("/root/.local/bin/claude") ? "/root/.local/bin/claude" : "claude");
    this.cwd = options.cwd || "/sdcard/projects";
    this.activeProcesses = new Map();
  }

  async isHealthy() {
    try {
      if (fs.existsSync(this.binPath)) {
        return { up: true, healthy: true, version: "claude" };
      }
      return { up: false, healthy: false, error: `claude binary not found at ${this.binPath}` };
    } catch (e) {
      return { up: false, healthy: false, error: e.message };
    }
  }

  async listSessions() {
    return [];
  }

  async createSession(opts = {}) {
    const id = `claude_${Date.now().toString(36)}`;
    return {
      id,
      title: opts.title || "Nuevo chat Claude",
      createdAt: new Date().toISOString(),
      provider: "claudecode"
    };
  }

  async getMessages(sessionId, opts = {}) {
    return [];
  }

  async sendMessage(sessionId, payload = {}, opts = {}) {
    let userPrompt = payload.text || payload.prompt || "";
    if (Array.isArray(payload.parts)) {
      userPrompt = payload.parts.filter(p => p.type === "text").map(p => p.text).join("\n");
    }

    const args = ["-p", userPrompt];
    
    const killGroup = (proc, signal = "SIGTERM") => {
      if (!proc || !proc.pid) return;
      try { process.kill(-proc.pid, signal); } catch (_) {
        try { proc.kill(signal); } catch (__) {}
      }
    };

    const result = await new Promise((resolve, reject) => {
      let isDone = false;
      const p = spawn(this.binPath, args, {
        cwd: this.cwd,
        detached: true,
        env: { ...process.env, HOME: "/root", PATH: `/root/.local/bin:${process.env.PATH || ""}` }
      });

      if (p.pid) {
        this.activeProcesses.set(p.pid, { proc: p, startTime: Date.now(), kill: (sig) => killGroup(p, sig) });
      }

      let stdout = "";
      let stderr = "";

      p.stdout.on("data", c => stdout += c);
      p.stderr.on("data", c => stderr += c);

      const timer = setTimeout(() => {
        if (isDone) return;
        isDone = true;
        killGroup(p, "SIGTERM");
        setTimeout(() => killGroup(p, "SIGKILL"), 2000).unref();
        reject(new Error("Claude Code execution timed out after 90s"));
      }, 90000);

      p.on("close", (code) => {
        if (isDone) return;
        isDone = true;
        clearTimeout(timer);
        if (p.pid) this.activeProcesses.delete(p.pid);
        resolve({ text: stdout.trim(), code });
      });
      
      p.on("error", err => {
        if (isDone) return;
        isDone = true;
        clearTimeout(timer);
        reject(new Error(`Failed to spawn claude: ${err.message}`));
      });
    });

    return normalizeMessage({
      role: "assistant",
      text: result.text || "No response",
      info: { id: `msg_${sessionId}_${Date.now()}`, role: "assistant", timestamp: Date.now(), status: "SENT", deliveryStatus: "SENT" },
      parts: [{ id: `prt_${Date.now()}`, type: "text", text: result.text }]
    }, sessionId);
  }

  async listModels() {
    return [
      { id: "claude-3-5-sonnet", name: "Claude 3.5 Sonnet", description: "Default Claude Code Model" }
    ];
  }
}
