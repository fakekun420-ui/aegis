// AntigravityAdapter.js — Antigravity CLI Print/JSON Mode Adapter
// Extracted from providers.js (Strangler Fig Step 5)
// Zero behavior changes — exact copy of AntigravityAdapter class

import fs from "node:fs";
import path from "node:path";
import { spawn } from "node:child_process";
import { BaseProviderAdapter } from "./BaseProviderAdapter.js";
import { normalizeMessage } from "../../providers.js"; // Temporary: still imports from providers.js until normalizeMessage is extracted

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
      // Note: loadProjectsStore is in server.js, not available here
      // This will be handled by the caller (server.js) via ProviderManager
    } catch (_) {}

    const targetDir = path.join(this.brainDir, convId);
    if (fs.existsSync(targetDir)) {
      try {
        fs.rmSync(targetDir, { recursive: true, force: true });
        console.log(`[antigravity] purged brain directory: ${targetDir}`);
      } catch (err) {
        console.warn(`[antigravity] failed to remove brain directory ${targetDir}:`, err.message);
      }
    }

    // Also check direct sessionId directory if distinct
    const directDir = path.join(this.brainDir, sessionId);
    if (directDir !== targetDir && fs.existsSync(directDir)) {
      try {
        fs.rmSync(directDir, { recursive: true, force: true });
        console.log(`[antigravity] purged brain directory: ${directDir}`);
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

    console.log(`[antigravity] executing agy for session ${sessionId} (convId: ${convId || "new"}, streaming: ${isStreaming})...`);

    // Helper to safely kill process group
    const killGroup = (proc, signal = "SIGTERM") => {
      if (!proc || !proc.pid) return;
      try {
        process.kill(-proc.pid, signal);
      } catch (_) {
        try {
          proc.kill(signal);
        } catch (___) {}
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