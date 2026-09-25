// skillsRoutes.js — API Routes for Skills
// Phase 3 / A-3: contratos de Models.kt (app):
//   GET    /api/skills            -> SkillsResponse {ok, data:{installed:[SkillItem], available:[SkillItem]}}
//                                    (F3: available = catálogo allowlist − instalados, H-14)
//   GET    ?projectId=|scope=     -> DELEGA en server.js (SkillListResponse {ok,data:{skills,projectId,counts}})
//   POST   /api/skills/install    -> TaskResponse {ok, data:{taskId,message}} (202, ASÍNCRONO: el consumidor
//                                    es Retrofit, que sólo parsea JSON — antes devolvía SSE y llegaba siempre null)
//   DELETE /api/skills/:id        -> {ok, data:{removed}} (npm uninstall -g)
//   GET    /api/skills/:id/config -> SkillConfigResponse {ok, data:{...}}
//   PATCH  /api/skills/:id/config -> {ok, data:{...}}

import { SkillManager } from "../skills/SkillManager.js";
// A-7: logger con nivel — antes console.log dispersos en la instalación async de npm
import { createLogger } from "../core/logger.js";

const manager = new SkillManager();
const log = createLogger("skills");

// ---- F4: validación de skillId de ruta/body (SKILL_INVALID) ----------------
// Mismo contrato que isValidId/invalidId de server.js (sin importar de él: éste
// módulo no debe conocer server.js). OJO: scope/names con "@" de los skills
// scoped (@scope/name) van por server.js (dos segmentos), no por aquí — las
// rutas de este fichero sólo manejan UN segmento, que por contrato F4 es
// [A-Za-z0-9._-] (ids del catálogo: graphify, opencode-mem, ...).
const ID_RE = /^[A-Za-z0-9._-]+$/;
function isValidSkillRouteId(v) {
  return typeof v === "string" && v.length > 0 && v.length <= 256 && ID_RE.test(v) && !v.includes("..");
}
function invalidSkillId(res, jsonHelper, value) {
  return jsonHelper(res, 400, {
    ok: false,
    error: { code: "SKILL_INVALID", message: `invalid skillId: "${String(value ?? "").slice(0, 80)}" (must match ^[A-Za-z0-9._-]+$, max 256, sin "..")` }
  });
}

export function handleSkillsRoute(req, res, pathname, jsonHelper, readJsonBody) {
  // GET /api/skills — SIN query = inventario de la app (SkillManagerScreen).
  // Con query (projectId/project/scope) NO tocamos: devolvemos false para que el
  // handler inline de server.js responda con SkillListResponse (ProjectDetailScreen).
  if (pathname === "/api/skills" && req.method === "GET") {
    if (req.url && req.url.includes("?")) return false;
    // F3 (H-14): installed = disco real (SkillManager); available = catálogo
    // REAL menos instalados (src/skills/catalog.json) — ya no es [].
    return jsonHelper(res, 200, {
      ok: true,
      data: { installed: manager.listInstalledDetailed(), available: manager.listAvailable() }
    });
  }

  // POST /api/skills/install — JSON asíncrono (antes SSE; ver cabecera)
  if (pathname === "/api/skills/install" && req.method === "POST") {
    return readJsonBody(req).then(async raw => {
      let skillId = null;
      try { skillId = JSON.parse(raw || "{}").skillId; } catch (_) {}
      if (!skillId) return jsonHelper(res, 400, { ok: false, error: "skillId required" });
      // F4: forma del id ANTES de tocar SkillManager (regex + ".." + max 256)
      if (!isValidSkillRouteId(String(skillId))) return invalidSkillId(res, jsonHelper, skillId);

      let started;
      try {
        // F3: install() es ASÍNCRONA — valida A-1 (regex) + allowlist del catálogo
        // y, si el id trae sha256, verifica el tarball ANTES de responder. Los
        // rechazos salen aquí con su code (ALLOWLIST / EBADCHECKSUM / EVERIFY).
        started = await manager.install(skillId);
      } catch (e) {
        return jsonHelper(res, 400, {
          ok: false,
          error: e.message,
          code: typeof e.code === "string" ? e.code : undefined // normalizeEnvelope lo consume
        });
      }
      // Drenamos los streams (antes iban al SSE): si nadie lee, el pipe se llena y
      // npm se bloquea. El resultado real se comprueba con GET /api/skills tras instalar.
      started.stdout.on("data", c => { const s = c.toString().trim(); if (s) log.debug(`[skills] install ${skillId}: ${s.slice(0, 300)}`); });
      started.stderr.on("data", c => { const s = c.toString().trim(); if (s) log.warn(`[skills] install ${skillId} err: ${s.slice(0, 300)}`); });
      started.promise
        .then(() => log.info(`[skills] install OK ${skillId}`))
        .catch(err => log.error(`[skills] install FAIL ${skillId}: ${err.message}`));

      const taskId = `install_${Date.now().toString(36)}`;
      return jsonHelper(res, 202, {
        ok: true,
        data: { taskId, message: `install started: ${skillId} (async — verifica con GET /api/skills)` }
      });
    }).catch(e => jsonHelper(res, 500, { ok: false, error: e.message }));
  }

  // DELETE /api/skills/:id  (un segmento = id npm; dos segmentos = scope/name -> server.js)
  const matchDel = pathname.match(/^\/api\/skills\/([^\/]+)$/);
  if (matchDel && req.method === "DELETE") {
    const id = matchDel[1];
    if (!isValidSkillRouteId(id)) return invalidSkillId(res, jsonHelper, id); // F4: antes de uninstall
    return manager.uninstall(id)
      .then(() => jsonHelper(res, 200, { ok: true, data: { removed: id } }))
      .catch(e => jsonHelper(res, 500, { ok: false, error: e.message }));
  }

  // GET /api/skills/:id/config
  const matchGetConf = pathname.match(/^\/api\/skills\/([^\/]+)\/config$/);
  if (matchGetConf && req.method === "GET") {
    const id = matchGetConf[1];
    if (!isValidSkillRouteId(id)) return invalidSkillId(res, jsonHelper, id); // F4: antes de getConfig
    return jsonHelper(res, 200, { ok: true, data: manager.getConfig(id) });
  }

  // PATCH /api/skills/:id/config
  const matchPatchConf = pathname.match(/^\/api\/skills\/([^\/]+)\/config$/);
  if (matchPatchConf && req.method === "PATCH") {
    const id = matchPatchConf[1];
    if (!isValidSkillRouteId(id)) return invalidSkillId(res, jsonHelper, id); // F4: antes de updateConfig
    return readJsonBody(req).then(raw => {
      const config = JSON.parse(raw || "{}");
      return jsonHelper(res, 200, { ok: true, data: manager.updateConfig(id, config) });
    }).catch(e => jsonHelper(res, 500, { ok: false, error: e.message }));
  }

  return false; // Not handled
}
