import { BaseAgent } from "./BaseAgent.js";

export class ResearchAgent extends BaseAgent {
  constructor(projectId) { super(projectId, "ResearchAgent"); }
  
  async execute(context) {
    this.status = "running";
    this.emit("AGENT_STARTED", { context });
    try {
      // El flag era "--markdown", que NO existe en graphify (verificado contra la CLI:
      // "unknown command '--markdown'"), asi que este agente fallaba SIEMPRE y aun asi
      // escribia un RESEARCH.md de decepcion marcado como FINAL y devolvia ok:true.
      // Se usa una orden que existe de verdad.
      const res = await this.invoker.invoke("graphify", ["query", "que hace este proyecto y como se organiza", "--budget", "1200"], this.projectId);
      if (!res.ok) {
        // Si la skill falla, NO se escribe un artefacto que parezca una investigacion
        // creada. Se devuelve el fallo y no se finge.
        this.status = "failed";
        this.emit("AGENT_FAILED", { error: res.error });
        return { ok: false, error: res.error };
      }
      const content = typeof res.data === "string" ? res.data : JSON.stringify(res.data);
      this.writeArtifact("RESEARCH.md", content, { state: "FINAL" });
      this.status = "completed";
      this.emit("AGENT_COMPLETED", { artifact: "RESEARCH.md" });
      return { ok: true };
    } catch (e) {
      this.status = "failed";
      this.emit("AGENT_FAILED", { error: e.message });
      return { ok: false, error: e.message };
    }
  }
}
