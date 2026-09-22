import fs from "node:fs";
import path from "node:path";
import { getProjectAbsPath } from "./pathResolver.js";

export class WorkflowParser {
  parse(projectId, workflowId) {
    const wfPath = path.join(getProjectAbsPath(projectId), "workflows", `${workflowId}.json`);
    if (!fs.existsSync(wfPath)) {
      throw new Error(`Workflow not found: ${workflowId}`);
    }
    const data = JSON.parse(fs.readFileSync(wfPath, "utf8"));
    
    // Basic validation
    if (!data.id || !Array.isArray(data.steps)) throw new Error("Invalid workflow format");
    
    // Cycle detection logic can be added here
    
    return data;
  }
}
