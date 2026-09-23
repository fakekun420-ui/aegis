import { workflowEngine } from "../core/workflowEngine.js";

// Rutas de workflows — contratos de Models.kt (app):
//   GET  /api/workflows/:projectId        -> WorkflowsResponse    {ok, data:[{id,name,steps}]}
//   GET  /api/workflows/:projectId/status -> WorkflowStatusResponse {ok, data:{id,status,currentStep,progress}}
//   POST /api/workflows/:projectId/run    -> TaskResponse          {ok, data:{taskId,message}}  body {workflowId}
// Cualquier otro método/ruta conocida bajo /api/workflows -> 405/404 JSON (nunca el fallback SPA).
export function handleWorkflowRoutes(req, res, pathname, jsonHelper, readJsonBody) {
  // GET /api/workflows/:projectId/status — estado REAL de la última ejecución
  const statusMatch = pathname.match(/^\/api\/workflows\/([^\/]+)\/status$/);
  if (statusMatch && req.method === "GET") {
    try {
      const st = workflowEngine.getWorkflowStatus(statusMatch[1]);
      if (!st) return jsonHelper(res, 404, { ok: false, error: `no workflow run recorded for project ${statusMatch[1]}` });
      return jsonHelper(res, 200, { ok: true, data: st });
    } catch (e) {
      return jsonHelper(res, 500, { ok: false, error: String((e && e.message) || e).slice(0, 400) });
    }
  }

  // POST /api/workflows/:projectId/run — body {workflowId}
  const runMatch = pathname.match(/^\/api\/workflows\/([^\/]+)\/run$/);
  if (runMatch && req.method === "POST") {
    return readJsonBody(req).then(raw => {
      let workflowId = null;
      try { workflowId = JSON.parse(raw || "{}").workflowId || null; } catch (_) {}
      if (!workflowId) return jsonHelper(res, 400, { ok: false, error: "workflowId required" });
      const projectId = runMatch[1];
      return workflowEngine.run(workflowId, projectId, "manual").then(data => {
        if (data.ok) {
          return jsonHelper(res, 200, {
            ok: true,
            data: { taskId: `${projectId}:${workflowId}`, message: `workflow ${workflowId} executed` },
            workflowId
          });
        }
        const notFound = /not found|invalid workflow/i.test(String(data.error || ""));
        return jsonHelper(res, notFound ? 404 : 500, data);
      });
    }).catch(e => jsonHelper(res, 500, { ok: false, error: String((e && e.message) || e).slice(0, 400) }));
  }

  // GET /api/workflows/:projectId — listado real desde disco ([] si no hay workflows)
  const listMatch = pathname.match(/^\/api\/workflows\/([^\/]+)$/);
  if (listMatch && req.method === "GET") {
    try {
      return jsonHelper(res, 200, { ok: true, data: workflowEngine.listWorkflows(listMatch[1]) });
    } catch (e) {
      // projectId inválido (caracteres fuera de /^[a-zA-Z0-9\-_]+$/) u error de resolución
      return jsonHelper(res, 400, { ok: false, error: String((e && e.message) || e).slice(0, 400) });
    }
  }

  // Rutas conocidas con método no soportado -> 405 JSON coherente
  if ((statusMatch && req.method !== "GET") ||
      (runMatch && req.method !== "POST") ||
      (listMatch && req.method !== "GET")) {
    return jsonHelper(res, 405, { ok: false, error: `method ${req.method} not allowed for ${pathname}`, code: "METHOD_NOT_ALLOWED" });
  }

  return false; // ruta desconocida bajo /api/workflows => 404 JSON central
}
