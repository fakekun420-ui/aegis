// SkillInvoker.js — Execute Skills Safely
// Phase 3

import { spawn } from "node:child_process";
import { getProjectAbsPath } from "../core/pathResolver.js";

export class SkillInvoker {
  async invoke(skillName, args = [], projectId) {
    const cwd = getProjectAbsPath(projectId);
    
    return new Promise((resolve) => {
      let isDone = false;
      const p = spawn(skillName, args, { 
        cwd, 
        env: { ...process.env, HOME: "/root", PATH: `/root/.local/bin:${process.env.PATH || ""}` } 
      });
      
      let stdout = "";
      let stderr = "";
      
      p.stdout.on("data", c => stdout += c);
      p.stderr.on("data", c => stderr += c);
      
      const timer = setTimeout(() => {
        if (isDone) return;
        isDone = true;
        try { process.kill(-p.pid, "SIGTERM"); } catch (_) { p.kill("SIGTERM"); }
        resolve({ ok: false, data: null, error: "Skill execution timed out after 60s" });
      }, 60000);
      
      p.on("close", code => {
        if (isDone) return;
        isDone = true;
        clearTimeout(timer);
        
        const outTrim = stdout.trim();
        let data = outTrim;
        try { data = JSON.parse(outTrim); } catch (_) {}
        
        if (code === 0) {
          resolve({ ok: true, data, error: null });
        } else {
          resolve({ ok: false, data: outTrim, error: `Skill exited with code ${code}: ${stderr.trim()}` });
        }
      });
      
      p.on("error", err => {
        if (isDone) return;
        isDone = true;
        clearTimeout(timer);
        resolve({ ok: false, data: null, error: `Failed to invoke skill: ${err.message}` });
      });
    });
  }
}
