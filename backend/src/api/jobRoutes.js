import { jobScheduler } from "../core/jobScheduler.js";

// Formato legible de intervalo para JobItem.interval (Models.kt lo exige como String no nulo)
function formatInterval(ms) {
  if (ms >= 60000 && ms % 60000 === 0) return `${ms / 60000}m`;
  if (ms >= 1000 && ms % 1000 === 0) return `${ms / 1000}s`;
  return `${ms}ms`;
}

export function handleJobRoutes(req, res, pathname, jsonHelper, readJsonBody) {
  // GET /api/jobs — JobsResponse {ok, data:[{id, enabled, lastRun, interval, intervalMs}]}
  if (pathname === "/api/jobs" && req.method === "GET") {
    const list = Array.from(jobScheduler.jobs.entries()).map(([id, j]) => ({
      id,
      interval: formatInterval(j.intervalMs),
      intervalMs: j.intervalMs,
      enabled: j.enabled,
      lastRun: j.lastRun
    }));
    return jsonHelper(res, 200, { ok: true, data: list });
  }

  // POST /api/jobs/:id/run — ejecuta el handler del job bajo demanda (app: runJob -> BaseResponse)
  const runMatch = pathname.match(/^\/api\/jobs\/([^\/]+)\/run$/);
  if (runMatch && req.method === "POST") {
    const id = runMatch[1];
    const job = jobScheduler.jobs.get(id);
    if (!job) return jsonHelper(res, 404, { ok: false, error: `job not found: ${id}` });
    return Promise.resolve()
      .then(() => job.handler())
      .then(() => {
        job.lastRun = new Date().toISOString();
        return jsonHelper(res, 200, { ok: true, data: { taskId: id, message: `job ${id} executed` } });
      })
      .catch(e => jsonHelper(res, 500, { ok: false, error: String((e && e.message) || e).slice(0, 400) }));
  }

  return false;
}
