import { workflowEngine } from "../core/workflowEngine.js";

export function handleWorkflowRoutes(req, res, pathname, jsonHelper, readJsonBody) {
  const runMatch = pathname.match(/^\/api\/workflows\/([^\/]+)\/run$/);
  if (runMatch && req.method === "POST") {
    return readJsonBody(req).then(raw => {
      const { workflowId } = JSON.parse(raw || "{}");
      return workflowEngine.run(workflowId, runMatch[1], "manual")
        .then(data => jsonHelper(res, 200, data));
    }).catch(e => jsonHelper(res, 500, { ok: false, error: e.message }));
  }
  
  return false;
}
