// bootstrapRoutes.js — API del wizard de bootstrap (FASE F1)
//
// Rutas (TODAS bajo /api/bootstrap => cubiertas por el middleware X-Aegis-Token
// del hub, Fase 0; ninguna exenta salvo las que el middleware exima él mismo):
//   GET  /api/bootstrap/state             -> 200 {ok,data:<snapshot contrato>}
//   POST /api/bootstrap/run               -> 202 {ok,data:{phase:"running"}}
//                                            | 409 ALREADY_RUNNING
//   POST /api/bootstrap/step/:id/retry    -> 202 {ok,data:{phase:"running"}}
//                                            | 404 NOT_FOUND (id desconocido)
//                                            | 409 NOT_RETRYABLE (status != failed)
//                                            | 409 ALREADY_RUNNING
//   POST /api/bootstrap/cancel            -> 200 {ok,data:{phase:"paused"}}
//                                            | 409 NOT_RUNNING
//
// Patrón idéntico a skillsRoutes.js/jobRoutes.js: firma
// (req, res, pathname, jsonHelper, readJsonBody), respuestas SIEMPRE vía
// jsonHelper (=> envelope {ok,data} / {ok:false,error:{code,message}} + CORS) y
// `return false` literal = "no es mi ruta" para el contrato de dispatchRoute.

import { snapshot } from "../bootstrap/state.js";
import { bootstrapOrchestrator } from "../bootstrap/orchestrator.js";

function bodyError(jsonHelper, res, status, code, message) {
  // normalizeEnvelope (server.js) convierte error string + code en
  // {ok:false, error:{code, message}} — mismo shape que el resto del hub.
  return jsonHelper(res, status, { ok: false, error: message, code });
}

export function handleBootstrapRoute(req, res, pathname, jsonHelper, readJsonBody) {
  // Fuera del prefijo => "no es mi ruta" (los demás routers y el 404 central siguen)
  if (typeof pathname !== "string" || !pathname.startsWith("/api/bootstrap")) return false;

  // 1) GET /api/bootstrap/state — snapshot con progreso EN VIVO
  if (pathname === "/api/bootstrap/state" && req.method === "GET") {
    return jsonHelper(res, 200, { ok: true, data: snapshot() });
  }

  // 2) POST /api/bootstrap/run — body {"resume":true} (opcional; idempotente: la
  //    re-ejecución re-comprueba cada paso => los satisfechos salen "skipped")
  if (pathname === "/api/bootstrap/run" && req.method === "POST") {
    // readJsonBody sólo rechaza si el body es ilegible/límite excedido => 400;
    // el resto de errores INTERNOS del try llegan a dispatchRoute (=>500 envelope).
    return readJsonBody(req, 64 * 1024).catch(() => null).then(raw => {
      if (raw === null) return bodyError(jsonHelper, res, 400, "BAD_REQUEST", "body ilegible");
      let body = {};
      try { body = JSON.parse(raw || "{}") || {}; } catch (_) { body = {}; }
      try {
        bootstrapOrchestrator.runOnce({ resume: body.resume === true });
      } catch (e) {
        if (e && e.code === "ALREADY_RUNNING") {
          return bodyError(jsonHelper, res, 409, "ALREADY_RUNNING", "bootstrap ya en ejecución");
        }
        if (e && e.code === "NOT_FOUND") {
          return bodyError(jsonHelper, res, 404, "NOT_FOUND", String(e.message || e));
        }
        return bodyError(jsonHelper, res, 500, "INTERNAL_ERROR", String((e && e.message) || e).slice(0, 400));
      }
      return jsonHelper(res, 202, { ok: true, data: { phase: "running" } });
    });
  }

  // 3) POST /api/bootstrap/step/:id/retry — re-ejecuta ESE paso y CONTINÚA con
  //    los pendientes (el runner arranca su índice y sigue el bucle normal)
  const retryMatch = pathname.match(/^\/api\/bootstrap\/step\/([^\/]+)\/retry$/);
  if (retryMatch && req.method === "POST") {
    let id = "";
    try { id = decodeURIComponent(retryMatch[1]); } catch (_) { id = retryMatch[1]; }
    const st = snapshot();
    const step = st.steps.find(s => s.id === id);
    if (!step) return bodyError(jsonHelper, res, 404, "NOT_FOUND", `paso desconocido: ${id}`);
    if (step.status !== "failed") {
      return bodyError(jsonHelper, res, 409, "NOT_RETRYABLE",
        `el paso "${id}" no está en estado failed (status=${step.status}) — sólo un fallo puede reintentarse`);
    }
    if (bootstrapOrchestrator.isRunning() || st.phase === "running") {
      return bodyError(jsonHelper, res, 409, "ALREADY_RUNNING", "bootstrap ya en ejecución");
    }
    try {
      bootstrapOrchestrator.runOnce({ resume: true, retryFrom: id });
    } catch (e) {
      if (e && e.code === "ALREADY_RUNNING") {
        return bodyError(jsonHelper, res, 409, "ALREADY_RUNNING", "bootstrap ya en ejecución");
      }
      return bodyError(jsonHelper, res, 500, "INTERNAL_ERROR", String((e && e.message) || e).slice(0, 400));
    }
    return jsonHelper(res, 202, { ok: true, data: { phase: "running" } });
  }

  // 4) POST /api/bootstrap/cancel — cooperativa:200 {phase:"paused"} sólo si
  //    corría; si no,409 NOT_RUNNING
  if (pathname === "/api/bootstrap/cancel" && req.method === "POST") {
    return Promise.resolve()
      .then(() => bootstrapOrchestrator.cancel())
      .then(r => {
        if (!r || r.wasRunning !== true) {
          return bodyError(jsonHelper, res, 409, "NOT_RUNNING", "no hay ninguna ejecución en curso");
        }
        // wasRunning true => cancel() garantiza la pausa (checkpoint o forzada)
        return jsonHelper(res, 200, { ok: true, data: { phase: "paused" } });
      })
      .catch(e => bodyError(jsonHelper, res, 500, "INTERNAL_ERROR", String((e && e.message) || e).slice(0, 400)));
  }

  return false; // bajo /api/bootstrap pero sin handler propio (=>404 central)
}
