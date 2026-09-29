import { BaseAgent } from "./BaseAgent.js";
import fs from "node:fs";
import path from "node:path";
import { getProjectAbsPath } from "../core/pathResolver.js";

export class AuditorAgent extends BaseAgent {
  constructor(projectId) { super(projectId, "AuditorAgent"); }
  
  async execute(context) {
    this.status = "running";
    this.emit("AGENT_STARTED", { context });
    try {
      const dir = path.join(getProjectAbsPath(this.projectId), "docs");
      let files = [];
      if (fs.existsSync(dir)) files = fs.readdirSync(dir);
      
      // Alcance real de este agente: comprueba que los .md tengan frontmatter. Nada
      // mas. El titulo lo decia, el nombre ("Auditor") no; se aclara en el informe
      // para que nadie lo lea como una revision de codigo.
      let report = "# Informe de frontmatter\n\nEste agente comprueba UNA cosa: que cada .md tenga su bloque de frontmatter (`---`). No audita codigo ni seguridad.\n\n";
      for (const f of files) {
        if (f.endsWith(".md")) {
          const content = fs.readFileSync(path.join(dir, f), "utf8");
          const valid = content.startsWith("---");
          report += `- ${f}: ${valid ? "Valid Frontmatter" : "Invalid/Missing Frontmatter"}\n`;
        }
      }
      
      this.writeArtifact("AUDIT.md", report, { state: "FINAL" });
      this.status = "completed";
      this.emit("AGENT_COMPLETED", { artifact: "AUDIT.md" });
      return { ok: true };
    } catch (e) {
      this.status = "failed";
      this.emit("AGENT_FAILED", { error: e.message });
      return { ok: false, error: e.message };
    }
  }
}
