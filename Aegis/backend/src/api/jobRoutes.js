import { jobScheduler } from "../core/jobScheduler.js";

export function handleJobRoutes(req, res, pathname, jsonHelper, readJsonBody) {
  if (pathname === "/api/jobs" && req.method === "GET") {
    const list = Array.from(jobScheduler.jobs.entries()).map(([id, j]) => ({
      id, intervalMs: j.intervalMs, enabled: j.enabled, lastRun: j.lastRun
    }));
    return jsonHelper(res, 200, { ok: true, data: list });
  }
  return false;
}
