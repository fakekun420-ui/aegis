// projectRoutes.js — API Routes for Project Management
// Phase 4 / A-3: contratos de Models.kt (app):
//   GET  /api/workspace/projects        -> ProjectsResponse {ok, data:[ProjectItem{id,name,path,hasHub,lastCommit}]}
//   POST /api/workspace/projects/:id/init -> {ok, data:{ok,projectId}} (BaseResponse de la app)
//   GET  /api/workspace/projects/:id/state -> ProjectStateResponse {ok, data:{...}}
//   POST /api/workspace/projects/:id/index -> TaskResponse {ok, data:{taskId,message}} (202, ASÍNCRONO:
//        antes SSE y Response<TaskResponse> de Retrofit parseaba siempre null)

import { execFile } from "node:child_process";
import { ProjectManager } from "../core/projectManager.js";
import { getProjectAbsPath, WORKSPACE_ROOT } from "../core/pathResolver.js";
// A-7: logger con nivel — antes console.log/error dispersos en el index asíncrono
import { createLogger } from "../core/logger.js";

const manager = new ProjectManager();
const log = createLogger("workspace");

// lastCommit: ISO del último commit del proyecto (Models.kt lo declara nullable).
// execFile sin shell (sin metacaracteres), timeout corto y null como fallback
// honesto: sin git o sin commits, el campo va vacío — nunca inventado.
function lastCommitOf(dir) {
  return new Promise(resolve => {
    execFile("git", ["-C", dir, "log", "-1", "--format=%cI"], { timeout: 1500 }, (err, stdout) => {
      resolve(!err && stdout && stdout.trim() ? stdout.trim() : null);
    });
  });
}

export function handleProjectRoutes(req, res, pathname, jsonHelper, readJsonBody) {
  // GET /api/workspace/projects
  if (pathname === "/api/workspace/projects" && req.method === "GET") {
    const items = manager.scanWorkspace();
    // ProjectItem.name es OBLIGATORIO en Models.kt (antes no existía -> NPE en
    // WorkspaceScreen `Text(project.name)`); id de carpeta también es su nombre.
    return Promise.all(items.map(async p => ({
      id: p.id,
      name: p.id,
      path: p.path,
      hasHub: !!p.hasHub,
      lastCommit: await lastCommitOf(p.path)
    }))).then(data => jsonHelper(res, 200, { ok: true, data }));
  }
  
  // POST /api/workspace/projects/:id/init
  const matchInit = pathname.match(/^\/api\/workspace\/projects\/([^\/]+)\/init$/);
  if (matchInit && req.method === "POST") {
    const id = matchInit[1];
    try {
      return jsonHelper(res, 200, { ok: true, data: manager.initProject(id) });
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
    const taskId = `index_${Date.now().toString(36)}`;
    // JSON asíncrono (antes SSE con res — el consumidor Retrofit esperaba JSON y
    // recibía text/event-stream). res=null: indexProject ya maneja el modo sin stream.
    Promise.resolve()
      .then(() => manager.indexProject(id, null))
      .then(r => log.info(`[workspace] index ${id}: ${r && r.ok ? "ok" : "fail " + (r && r.error)}`))
      .catch(e => log.error(`[workspace] index ${id}: ${e.message}`));
    return jsonHelper(res, 202, {
      ok: true,
      data: { taskId, message: `index started: ${id} (async — estado en GET /api/workspace/projects/${id}/state)` }
    });
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
