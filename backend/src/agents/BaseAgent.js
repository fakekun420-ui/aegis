import fs from "node:fs";
import path from "node:path";
import { eventBus, EVENTS } from "../core/eventBus.js";
import { getProjectAbsPath } from "../core/pathResolver.js";
import { atomicReadFileSync, atomicWriteFileSync } from "../core/storage.js";
import { SkillInvoker } from "../skills/SkillInvoker.js";

export class BaseAgent {
  constructor(projectId, name) {
    this.id = `agent_${Date.now()}_${Math.random().toString(36).slice(2,7)}`;
    this.name = name;
    this.projectId = projectId;
    this.status = "idle";
    this.invoker = new SkillInvoker();
  }
  
  async execute(context) { throw new Error("Not implemented"); }
  async validate(artifact) { throw new Error("Not implemented"); }
  
  readArtifact(filename) {
    const p = path.join(getProjectAbsPath(this.projectId), "docs", filename);
    if (!fs.existsSync(p)) return null;
    return fs.readFileSync(p, "utf8");
  }
  
  writeArtifact(filename, content, metadata = {}) {
    const dir = path.join(getProjectAbsPath(this.projectId), "docs");
    if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
    
    const yaml = [
      "---",
      `version: ${metadata.version || 1}`,
      `last_updated: ${new Date().toISOString()}`,
      `owned_by: ${this.name}`,
      `state: ${metadata.state || "DRAFT"}`,
      "---",
      ""
    ].join("\n");
    
    const p = path.join(dir, filename);
    atomicWriteFileSync(p, yaml + content);
    this.emit(EVENTS.ARTIFACT_CREATED, { filename, metadata });
  }
  
  log(msg) {
    // console.log muted
  }
  
  emit(event, data) {
    eventBus.publish(this.projectId, event, { agentId: this.id, agentName: this.name, ...data });
  }
}
