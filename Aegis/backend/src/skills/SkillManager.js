// SkillManager.js — Skill Installation and Management
// Phase 3

import fs from "node:fs";
import path from "node:path";
import { spawn } from "node:child_process";
import { atomicWriteFileSync, atomicReadFileSync } from "../core/storage.js";

// Valida el skillId ANTES de interpolarlo en sh (npm install|uninstall -g <id>)
// Evita metacaracteres de shell, espacios, path traversal ("..") y longitudes abusivas.
const SKILL_ID_RE = /^[a-z0-9@][a-z0-9@/._-]*$/;
function assertValidSkillId(skillId) {
  const id = String(skillId ?? "");
  if (!id || id.length > 128 || id.includes("..") || !SKILL_ID_RE.test(id)) {
    throw new Error(
      `invalid skillId "${id.slice(0, 80)}" — must match ^[a-z0-9@][a-z0-9@/._-]*$, max 128 chars, no ".."`
    );
  }
  return id;
}

export class SkillManager {
  listInstalled() {
    const skills = [];
    const configDir = "/root/.config/opencode/skills";
    const binDir = "/root/.local/bin";
    
    if (fs.existsSync(configDir)) {
      const entries = fs.readdirSync(configDir);
      for (const e of entries) {
        if (e.endsWith(".json")) skills.push(e.replace(".json", ""));
      }
    }
    
    // Check known binaries
    const known = ["graphify", "opencode-mem"];
    for (const k of known) {
      if (fs.existsSync(path.join(binDir, k)) && !skills.includes(k)) {
        skills.push(k);
      }
    }
    return skills;
  }

  // A-3: lectura honesta de disco para el contrato SkillItem (Models.kt).
  // id/name desde lo REALMENTE instalado; version/description/enabled desde el
  // config JSON del skill si existe. `enabled` default true cuando el config no
  // trae el flag (documentado en FRONTEND_CONTRACT.md — no hay estado real de "on/off").
  listInstalledDetailed() {
    return this.listInstalled().map(id => {
      let cfg = {};
      try { cfg = this.getConfig(id) || {}; } catch (_) {}
      return {
        id,
        name: typeof cfg.name === "string" && cfg.name ? cfg.name : id,
        version: typeof cfg.version === "string" ? cfg.version : null,
        description: typeof cfg.description === "string" && cfg.description ? cfg.description : null,
        installed: true,
        enabled: typeof cfg.enabled === "boolean" ? cfg.enabled : true
      };
    });
  }

  install(skillId) {
    const id = assertValidSkillId(skillId);
    const cmd = `npm install -g ${id}`;
    const p = spawn("sh", ["-c", cmd], { env: { ...process.env, HOME: "/root" } });
    
    return {
      stdout: p.stdout,
      stderr: p.stderr,
      promise: new Promise((resolve, reject) => {
        p.on("close", code => {
          if (code === 0) resolve();
          else reject(new Error(`Install failed with code ${code}`));
        });
      })
    };
  }

  uninstall(skillId) {
    // Devuelve (no lanza) la promesa rechazada: skillsRoutes.js sólo captura errores dentro de .catch()
    let id;
    try { id = assertValidSkillId(skillId); } catch (e) { return Promise.reject(e); }
    const cmd = `npm uninstall -g ${id}`;
    return new Promise((resolve, reject) => {
      const p = spawn("sh", ["-c", cmd], { env: { ...process.env, HOME: "/root" } });
      p.on("close", code => {
        if (code === 0) resolve();
        else reject(new Error(`Uninstall failed with code ${code}`));
      });
    });
  }

  getConfig(skillId) {
    const configPath = `/root/.config/opencode/skills/${skillId}.json`;
    return atomicReadFileSync(configPath, {});
  }

  updateConfig(skillId, config) {
    const configPath = `/root/.config/opencode/skills/${skillId}.json`;
    atomicWriteFileSync(configPath, config);
    return config;
  }
}
