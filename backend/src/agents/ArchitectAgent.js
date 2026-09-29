import { BaseAgent } from "./BaseAgent.js";

export class ArchitectAgent extends BaseAgent {
  constructor(projectId) { super(projectId, "ArchitectAgent"); }
  
  async execute(context) {
    this.status = "running";
    this.emit("AGENT_STARTED", { context });
    try {
      const research = this.readArtifact("RESEARCH.md");
      const req = this.readArtifact("REQ.md");
      if (!research || !req) {
        // Antes escribia "# Architecture / REQ size: 0 / RESEARCH size: 0" y lo
        // devolvia como ok:true con estado DRAFT: un archivo que dice "arquitectura"
        // y solo contiene el numero de caracteres de dos archivos que no ha leido.
        // Un agente sin entrada no puede producir salida: se dice.
        const missing = [!req && "REQ.md", !research && "RESEARCH.md"].filter(Boolean).join(" y ");
        this.status = "failed";
        const error = `faltan artefactos de entrada: ${missing}. Este agente no puede`;
        this.emit("AGENT_FAILED", { error });
        return { ok: false, error };
      }

      // Lo que realmente sabe hacer: resumir las entradas. Se llama "resumen de
      // requisitos", no "arquitectura", para que el nombre no prometa mas de lo que hay.
      const summary = `# Resumen de requisitos\n\n- REQ.md: ${req.length} caracteres\n- RESEARCH.md: ${research.length} caracteres\n\nEste agente NO genera arquitectura: solo resume lo que hay escrito.\nLa arquitectura la escribe el agente principal de OpenCode.`;
      this.writeArtifact("ARCHITECTURE.md", summary, { state: "DRAFT" });
      this.status = "completed";
      this.emit("AGENT_COMPLETED", { artifact: "ARCHITECTURE.md" });
      return { ok: true };
    } catch (e) {
      this.status = "failed";
      this.emit("AGENT_FAILED", { error: e.message });
      return { ok: false, error: e.message };
    }
  }
}
