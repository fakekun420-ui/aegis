import { BaseAgent } from "./BaseAgent.js";

export class ResearchAgent extends BaseAgent {
  constructor(projectId) { super(projectId, "ResearchAgent"); }
  
  async execute(context) {
    this.status = "running";
    this.emit("AGENT_STARTED", { context });
    try {
      const res = await this.invoker.invoke("graphify", ["--markdown"], this.projectId);
      let content = "";
      if (res.ok) content = res.data;
      else content = "# Research Fallback\nGraphify failed or not installed. " + res.error;
      
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
