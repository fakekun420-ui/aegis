import { agentPool } from "../core/agentPool.js";

export function handleAgentRoutes(req, res, pathname, jsonHelper, readJsonBody) {
  if (pathname === "/api/agents" && req.method === "GET") {
    return jsonHelper(res, 200, { ok: true, data: Object.keys(agentPool.registry) });
  }
  
  if (pathname === "/api/agents/dispatch" && req.method === "POST") {
    return readJsonBody(req).then(raw => {
      const { agentType, projectId, context } = JSON.parse(raw || "{}");
      return agentPool.dispatch(agentType, projectId, context)
        .then(data => jsonHelper(res, 200, data));
    }).catch(e => jsonHelper(res, 500, { ok: false, error: e.message }));
  }
  
  const statusMatch = pathname.match(/^\/api\/agents\/status\/([^\/]+)$/);
  if (statusMatch && req.method === "GET") {
    return jsonHelper(res, 200, { ok: true, data: agentPool.getStatus(statusMatch[1]) });
  }
  
  const delMatch = pathname.match(/^\/api\/agents\/([^\/]+)$/);
  if (delMatch && req.method === "DELETE") {
    return jsonHelper(res, 200, agentPool.cancelAll(delMatch[1]));
  }
  
  return false;
}
