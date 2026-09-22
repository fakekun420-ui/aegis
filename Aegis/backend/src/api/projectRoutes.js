// projectRoutes.js — API Routes for Project Management
// Phase 4

import { ProjectManager } from "../core/projectManager.js";
import { getProjectAbsPath, WORKSPACE_ROOT } from "../core/pathResolver.js";

const manager = new ProjectManager();

export function handleProjectRoutes(req, res, pathname, jsonHelper, readJsonBody) {
  // GET /api/workspace/projects
  if (pathname === "/api/workspace/projects" && req.method === "GET") {
    return jsonHelper(res, 200, { ok: true, data: manager.scanWorkspace() });
  }
  
  // POST /api/workspace/projects/:id/init
  const matchInit = pathname.match(/^\/api\/workspace\/projects\/([^\/]+)\/init$/);
  if (matchInit && req.method === "POST") {
    const id = matchInit[1];
    try {
      return jsonHelper(res, 200, manager.initProject(id));
    } catch (e) {
      return jsonHelper(res, 400, { ok: false, error: e.message });
    }
  }
  
  // GET /api/workspace/projects/:id/state
  const matchGetState = pathname.match(/^\/api\/workspace\/projects\/([^\/]+)\/state$/);
  if (matchGetState && req.method === "GET") {
    const id = matchGetState[1];
    const state = manager.getProjectState(id);
    if (!state) return jsonHelper(res, 404, { ok: false, error: "Not found or not initialized" });
    return jsonHelper(res, 200, { ok: true, data: state });
  }
  
  // PATCH /api/workspace/projects/:id/state
  const matchPatchState = pathname.match(/^\/api\/workspace\/projects\/([^\/]+)\/state$/);
  if (matchPatchState && req.method === "PATCH") {
    const id = matchPatchState[1];
    return readJsonBody(req).then(raw => {
      const patch = JSON.parse(raw || "{}");
      return jsonHelper(res, 200, { ok: true, data: manager.updateProjectState(id, patch) });
    }).catch(e => jsonHelper(res, 500, { ok: false, error: e.message }));
  }
  
  // POST /api/workspace/projects/:id/index
  const matchIndex = pathname.match(/^\/api\/workspace\/projects\/([^\/]+)\/index$/);
  if (matchIndex && req.method === "POST") {
    const id = matchIndex[1];
    try {
      manager.indexProject(id, res); // Handles SSE
      return true; // Sent headers already
    } catch (e) {
      return jsonHelper(res, 500, { ok: false, error: e.message });
    }
  }

  // GET /api/projects/:id/path (Phase 3.5 addition)
  const matchPath = pathname.match(/^\/api\/projects\/([^\/]+)\/path$/);
  if (matchPath && req.method === "GET") {
    const id = matchPath[1];
    try {
      return jsonHelper(res, 200, { 
        ok: true, 
        data: { projectId: id, absolutePath: getProjectAbsPath(id), workspaceRoot: WORKSPACE_ROOT } 
      });
    } catch (e) {
      return jsonHelper(res, 400, { ok: false, error: e.message });
    }
  }
  
  return false;
}
