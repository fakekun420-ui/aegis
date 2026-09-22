// projectManager.js — Project Management
// Phase 4

import fs from "node:fs";
import path from "node:path";
import { getProjectAbsPath, WORKSPACE_ROOT } from "./pathResolver.js";
import { atomicReadFileSync, atomicWriteFileSync } from "./storage.js";
import { SkillInvoker } from "../skills/SkillInvoker.js";

export class ProjectManager {
  scanWorkspace() {
    const projects = [];
    if (!fs.existsSync(WORKSPACE_ROOT)) return projects;
    
    const entries = fs.readdirSync(WORKSPACE_ROOT, { withFileTypes: true });
    for (const ent of entries) {
      if (ent.isDirectory() && !ent.name.startsWith(".")) {
        const pPath = path.join(WORKSPACE_ROOT, ent.name);
        let hasHub = false;
        let hubState = null;
        if (fs.existsSync(path.join(pPath, ".hub"))) {
          hasHub = true;
          hubState = atomicReadFileSync(path.join(pPath, ".hub", "state.json"), null);
        }
        projects.push({
          id: ent.name,
          path: pPath,
          hasHub,
          state: hubState,
          lastModified: fs.statSync(pPath).mtimeMs
        });
      }
    }
    return projects;
  }

  initProject(projectId) {
    const pPath = getProjectAbsPath(projectId);
    if (!fs.existsSync(pPath)) {
      fs.mkdirSync(pPath, { recursive: true });
    }
    const hubDir = path.join(pPath, ".hub");
    if (!fs.existsSync(hubDir)) {
      fs.mkdirSync(hubDir, { recursive: true });
    }
    
    const projJsonPath = path.join(hubDir, "project.json");
    if (!fs.existsSync(projJsonPath)) {
      atomicWriteFileSync(projJsonPath, {
        id: projectId,
        createdAt: new Date().toISOString()
      });
    }
    
    const stateJsonPath = path.join(hubDir, "state.json");
    if (!fs.existsSync(stateJsonPath)) {
      atomicWriteFileSync(stateJsonPath, {
        status: "active",
        lastIndexed: null
      });
    }
    
    return { ok: true, projectId };
  }

  getProjectState(projectId) {
    const statePath = path.join(getProjectAbsPath(projectId), ".hub", "state.json");
    return atomicReadFileSync(statePath, null);
  }

  updateProjectState(projectId, patch) {
    const statePath = path.join(getProjectAbsPath(projectId), ".hub", "state.json");
    const current = atomicReadFileSync(statePath, {});
    const updated = { ...current, ...patch, updatedAt: new Date().toISOString() };
    atomicWriteFileSync(statePath, updated);
    return updated;
  }

  async indexProject(projectId, res) {
    const invoker = new SkillInvoker();
    
    if (res) {
      res.writeHead(200, {
        "Content-Type": "text/event-stream; charset=utf-8",
        "Cache-Control": "no-cache",
        "Connection": "keep-alive"
      });
      res.write(`data: ${JSON.stringify({ type: "info", text: "Starting indexing via graphify..." })}\n\n`);
    }
    
    const result = await invoker.invoke("graphify", ["--markdown"], projectId);
    
    if (result.ok) {
      this.updateProjectState(projectId, { lastIndexed: new Date().toISOString() });
      if (res) {
        res.write(`data: ${JSON.stringify({ type: "done", success: true, result: result.data })}\n\n`);
        res.end();
      }
      return { ok: true, data: result.data };
    } else {
      if (res) {
        res.write(`data: ${JSON.stringify({ type: "done", success: false, error: result.error })}\n\n`);
        res.end();
      }
      return { ok: false, error: result.error };
    }
  }
}
