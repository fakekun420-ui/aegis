import { agentPool } from "../core/agentPool.js";

// Rutas de agentes — contratos de Models.kt (app):
//   GET    /api/agents               -> AgentsResponse {ok, data:[AgentItem{id,name,status}]}
//   POST   /api/agents/dispatch      -> TaskResponse {ok, data:{taskId,message}}
//   GET    /api/agents/status/:proj  -> AgentStatusResponse {ok, data:[AgentItem{id,name,status}]}
//   DELETE /api/agents/:projectId    -> cancela agentes activos del proyecto
export function handleAgentRoutes(req, res, pathname, jsonHelper, readJsonBody) {
  if (pathname === "/api/agents" && req.method === "GET") {
    // Tipos registrados en el pool (antes devolvía solo los keys como strings;
    // la app espera objetos AgentItem)
    const agents = Object.keys(agentPool.registry).map(name => ({ id: name, name, status: "registered" }));
    return jsonHelper(res, 200, { ok: true, data: agents });
  }

  if (pathname === "/api/agents/dispatch" && req.method === "POST") {
    return readJsonBody(req).then(raw => {
      let body = {};
      try { body = JSON.parse(raw || "{}"); } catch (_) { body = null; }
      if (!body) return jsonHelper(res, 400, { ok: false, error: "invalid JSON body" });
      const { agentType, projectId, context } = body;
      if (!agentType || !projectId) return jsonHelper(res, 400, { ok: false, error: "agentType and projectId required" });
      if (!agentPool.registry[agentType]) return jsonHelper(res, 404, { ok: false, error: `unknown agent type: ${agentType}` });
      return agentPool.dispatch(agentType, projectId, context || {})
        .then(r => jsonHelper(res, 200, {
          ok: true,
          data: { taskId: r.agentId, message: `dispatched ${r.agentType} for project ${projectId}` },
          agentId: r.agentId,
          agentType: r.agentType
        }));
    }).catch(e => jsonHelper(res, 500, { ok: false, error: String((e && e.message) || e).slice(0, 400) }));
  }

  const statusMatch = pathname.match(/^\/api\/agents\/status\/([^\/]+)$/);
  if (statusMatch && req.method === "GET") {
    return jsonHelper(res, 200, { ok: true, data: agentPool.getStatus(statusMatch[1]) });
  }

  const delMatch = pathname.match(/^\/api\/agents\/([^\/]+)$/);
  if (delMatch && req.method === "DELETE") {
    // A-3: envuelto — antes devolvía {ok,message} pelado (sin data) fuera del envelope
    const r = agentPool.cancelAll(delMatch[1]);
    return jsonHelper(res, 200, { ok: true, data: { projectId: delMatch[1], message: r.message } });
  }

  return false;
}
