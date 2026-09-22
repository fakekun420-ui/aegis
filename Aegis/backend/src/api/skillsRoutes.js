// skillsRoutes.js — API Routes for Skills
// Phase 3

import { SkillManager } from "../skills/SkillManager.js";

const manager = new SkillManager();

export function handleSkillsRoute(req, res, pathname, jsonHelper, readJsonBody) {
  // GET /api/skills
  if (pathname === "/api/skills" && req.method === "GET") {
    return jsonHelper(res, 200, { ok: true, data: manager.listInstalled() });
  }
  
  // POST /api/skills/install
  if (pathname === "/api/skills/install" && req.method === "POST") {
    return readJsonBody(req).then(raw => {
      const { skillId } = JSON.parse(raw || "{}");
      if (!skillId) return jsonHelper(res, 400, { ok: false, error: "skillId required" });
      
      const { stdout, stderr, promise } = manager.install(skillId);
      
      res.writeHead(200, {
        "Content-Type": "text/event-stream; charset=utf-8",
        "Cache-Control": "no-cache",
        "Connection": "keep-alive"
      });
      
      stdout.on("data", c => res.write(`data: ${JSON.stringify({ type: "out", text: c.toString() })}\n\n`));
      stderr.on("data", c => res.write(`data: ${JSON.stringify({ type: "err", text: c.toString() })}\n\n`));
      
      promise.then(() => {
        res.write(`data: ${JSON.stringify({ type: "done", success: true })}\n\n`);
        res.end();
      }).catch(err => {
        res.write(`data: ${JSON.stringify({ type: "done", success: false, error: err.message })}\n\n`);
        res.end();
      });
    }).catch(e => jsonHelper(res, 500, { ok: false, error: e.message }));
  }
  
  // DELETE /api/skills/:id
  const matchDel = pathname.match(/^\/api\/skills\/([^\/]+)$/);
  if (matchDel && req.method === "DELETE") {
    const id = matchDel[1];
    return manager.uninstall(id)
      .then(() => jsonHelper(res, 200, { ok: true, data: { removed: id } }))
      .catch(e => jsonHelper(res, 500, { ok: false, error: e.message }));
  }
  
  // GET /api/skills/:id/config
  const matchGetConf = pathname.match(/^\/api\/skills\/([^\/]+)\/config$/);
  if (matchGetConf && req.method === "GET") {
    const id = matchGetConf[1];
    return jsonHelper(res, 200, { ok: true, data: manager.getConfig(id) });
  }
  
  // PATCH /api/skills/:id/config
  const matchPatchConf = pathname.match(/^\/api\/skills\/([^\/]+)\/config$/);
  if (matchPatchConf && req.method === "PATCH") {
    const id = matchPatchConf[1];
    return readJsonBody(req).then(raw => {
      const config = JSON.parse(raw || "{}");
      return jsonHelper(res, 200, { ok: true, data: manager.updateConfig(id, config) });
    }).catch(e => jsonHelper(res, 500, { ok: false, error: e.message }));
  }
  
  return false; // Not handled
}
