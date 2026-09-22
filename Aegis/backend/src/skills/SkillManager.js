// SkillManager.js — Skill Installation and Management
// Phase 3

import fs from "node:fs";
import path from "node:path";
import { spawn } from "node:child_process";
import { atomicWriteFileSync, atomicReadFileSync } from "../core/storage.js";

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

  install(skillId) {
    const cmd = `npm install -g ${skillId}`;
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
    const cmd = `npm uninstall -g ${skillId}`;
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
