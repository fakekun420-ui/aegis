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
      
      // Sin limite, un skill que escupe mucho accumulates en memoria hasta tumbar el
      // Hub (el acumulador de string no tiene techo). Se corta por TAMANO y se DICE
      // en el resultado, en vez de truncar en silencio.
      const MAX_OUT = 2 * 1024 * 1024;
      let stdout = "";
      let stderr = "";
      let truncated = false;
      const cap = (prev, chunk) => {
        if (prev.length >= MAX_OUT) { truncated = true; return prev; }
        const next = prev + chunk;
        if (next.length > MAX_OUT) { truncated = true; return next.slice(0, MAX_OUT); }
        return next;
      };
      
      p.stdout.on("data", c => { stdout = cap(stdout, c); });
      p.stderr.on("data", c => { stderr = cap(stderr, c); });
      
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
          resolve({ ok: true, data, error: null, truncated });
        } else {
          resolve({ ok: false, data: outTrim, error: `Skill exited with code ${code}: ${stderr.trim()}`, truncated });
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
