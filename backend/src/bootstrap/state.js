// state.js — estado persistente del wizard de bootstrap (FASE F1 · backend)
//
// Responsabilidades (contrato GET /api/bootstrap/state):
//  - Cargar/guardar `backend/bootstrap-state.json` con atomicWriteFileSync
//    (helper existente de src/core/storage.js: tmp + fsync + rename) — nunca
//    queda JSON corrupto aunque el hub caiga a mitad de una transición.
//  - Seeder EXACTO del contrato: 6 pasos (STEP_DEFS, orden y títulos literales)
//    en `pending`, phase "idle".
//  - update(patch)   => fusiona campos de cabecera + updatedAt ISO y persiste.
//  - updateStep(id…) => fusiona campos de un paso, recalcula currentStepId y
//    persiste (progreso EN VIVO para el polling del wizard).
//  - snapshot()      => copia estructural del `data` del contrato (el caller
//    nunca puede corromper el estado en memoria).
//
// Env BOOTSTRAP_STATE_FILE redirige el fichero: los tests usan un estado
// temporal y JAMÁS borran ni tocan el bootstrap-state.json real.
//
// Fichero persistente: fase "running" es estado DE PROCESO. Si el hub muere a
// mitad de una ejecución, al recargar se normaliza a "paused" (reanudable) y el
// paso "running" vuelve a "pending" — misma semántica que una cancelación
// cooperativa. Sin esto, un hub reiniciado dejaría phase="running" huérfano y
// POST /api/bootstrap/run respondería 409 ALREADY_RUNNING para siempre.

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { atomicWriteFileSync, atomicReadFileSync } from "../core/storage.js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// Raíz del backend (…/backend) — compartida con steps.js/orchestrator.
export const BACKEND_DIR = path.resolve(__dirname, "..", "..");

// Path del estado: env (tests) o backend/bootstrap-state.json
export const STATE_FILE = process.env.BOOTSTRAP_STATE_FILE
  ? path.resolve(process.env.BOOTSTRAP_STATE_FILE)
  : path.join(BACKEND_DIR, "bootstrap-state.json");

// Orden y títulos EXACTOS del contrato — fuente única de verdad (steps.js
// importa STEP_DEFS para montar STEPS en el mismo orden).
export const STEP_DEFS = [
  { id: "preflight",   title: "Comprobación previa" },
  { id: "ubuntu",      title: "Ubuntu (chroot/proot)" },
  { id: "node",        title: "Node.js" },
  { id: "opencode",    title: "OpenCode" },
  { id: "antigravity", title: "Antigravity / Artemis" },
  { id: "skills",      title: "Skills y plugins" }
];

// SOLO TESTS (F2): AEGIS_BOOTSTRAP_TEST_STEP=<id> añade UN paso fake al FINAL
// de STEP_DEFS (id saneado: minúsculas/dígitos/_/-, máx 32 chars). Sirve para
// probar rollback REAL sin red ni instalaciones (su run crea un fichero y
// lanza). Los suites de contrato (bootstrap/rollback) NO setean esta env en los
// hubs de 6 pasos => el contrato de 6 pasos permanece intacto. Si la env trae un
// id inválido se ignora (seed de 6 pasos por defecto).
const TEST_STEP_RAW = typeof process.env.AEGIS_BOOTSTRAP_TEST_STEP === "string"
  ? process.env.AEGIS_BOOTSTRAP_TEST_STEP
  : "";
export const TEST_STEP_ID = /^[a-z0-9][a-z0-9_-]{0,31}$/.test(TEST_STEP_RAW) ? TEST_STEP_RAW : null;
if (TEST_STEP_ID) STEP_DEFS.push({ id: TEST_STEP_ID, title: "Paso fake (solo tests)" });

const PHASES = new Set(["idle", "running", "paused", "failed", "done"]);
const STATUSES = new Set(["pending", "running", "done", "failed", "skipped"]);
const ROLLBACKS = new Set(["none", "pending", "done", "failed"]);

function nowIso() { return new Date().toISOString(); }

function seedStep(def) {
  return { id: def.id, title: def.title, status: "pending", rollback: "none", progress: 0, detail: "pendiente", error: null };
}

function seeder() {
  return {
    phase: "idle",
    currentStepId: null,
    startedAt: null,
    updatedAt: nowIso(),
    lastError: null,
    steps: STEP_DEFS.map(seedStep)
  };
}

// currentStepId SIEMPRE = id del paso con status "running", o null
function computeCurrent(st) {
  const cur = (st.steps || []).find(s => s.status === "running");
  return cur ? cur.id : null;
}

function clampPct(v) {
  const n = Number(v);
  if (!Number.isFinite(n)) return 0;
  return Math.max(0, Math.min(100, Math.round(n)));
}

// Normaliza cualquier fichero (de otra versión, a mano o corrupto) al contrato:
// ids/títulos SIEMPRE de STEP_DEFS, enums validados, tipos saneados.
function normalize(raw) {
  const st = seeder();
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return st;

  if (PHASES.has(raw.phase)) st.phase = raw.phase;
  st.startedAt = typeof raw.startedAt === "string" ? raw.startedAt : null;
  st.updatedAt = typeof raw.updatedAt === "string" ? raw.updatedAt : nowIso();
  st.lastError = typeof raw.lastError === "string" ? raw.lastError : null;

  const byId = new Map();
  if (Array.isArray(raw.steps)) {
    for (const s of raw.steps) if (s && typeof s.id === "string") byId.set(s.id, s);
  }
  st.steps = STEP_DEFS.map(def => {
    const s = byId.get(def.id);
    if (!s) return seedStep(def);
    return {
      id: def.id,
      title: def.title,
      status: STATUSES.has(s.status) ? s.status : "pending",
      rollback: ROLLBACKS.has(s.rollback) ? s.rollback : "none",
      progress: clampPct(s.progress),
      detail: typeof s.detail === "string" ? s.detail : "",
      error: typeof s.error === "string" ? s.error : null
    };
  });

  // "running" persistido SIN runner en memoria = ejecución interrumpida por un
  // reinicio del hub => paused (reanudable) + paso running => pending.
  if (st.phase === "running") {
    st.phase = "paused";
    st.lastError = st.lastError || "ejecución interrumpida (reinicio del hub) — reanudable";
    for (const s of st.steps) {
      if (s.status === "running") { s.status = "pending"; s.progress = 0; s.detail = "interrumpido por reinicio — pendiente de reanudar"; }
    }
  }
  st.currentStepId = computeCurrent(st);
  return st;
}

let state = null;

// Carga perezosa + persistencia única (tmp+fsync+rename vía storage.js)
function persist() {
  state.updatedAt = nowIso();
  state.currentStepId = computeCurrent(state);
  try {
    atomicWriteFileSync(STATE_FILE, state);
  } catch (e) {
    // Nunca tumbar el runner por un fallo de escritura: el estado en memoria
    // sigue siendo la fuente para el snapshot de esta misma ejecución.
    console.error(`[bootstrap] no se pudo persistir ${STATE_FILE}: ${e.message}`);
  }
}

export function getState() {
  if (state) return state;
  const raw = atomicReadFileSync(STATE_FILE, null);
  state = normalize(raw);
  // Reescribe sólo si el disco divergió (fichero de otra versión, o la
  // normalización de arriba corrigió una ejecución interrumpida).
  if (raw) {
    try {
      if (JSON.stringify(raw) !== JSON.stringify(state)) persist();
    } catch { /* raw ilegible (ya devolvió fallback) — lo deja para el próximo update */ }
  }
  return state;
}

// Fusiona campos de cabecera + updatedAt ISO y persiste.
export function update(patch = {}) {
  const st = getState();
  if ("phase" in patch) st.phase = PHASES.has(patch.phase) ? patch.phase : st.phase;
  if ("startedAt" in patch) st.startedAt = typeof patch.startedAt === "string" ? patch.startedAt : null;
  if ("lastError" in patch) st.lastError = typeof patch.lastError === "string" ? patch.lastError : null;
  persist();
  return st;
}

// Fusiona campos de un paso, recalcula currentStepId y persiste (progreso vivo).
export function updateStep(id, patch = {}) {
  const st = getState();
  const s = st.steps.find(x => x.id === id);
  if (!s) return null;
  if ("status" in patch) s.status = STATUSES.has(patch.status) ? patch.status : s.status;
  if ("rollback" in patch) s.rollback = ROLLBACKS.has(patch.rollback) ? patch.rollback : s.rollback;
  if ("progress" in patch) s.progress = clampPct(patch.progress);
  if ("detail" in patch) s.detail = typeof patch.detail === "string" ? patch.detail : s.detail;
  if ("error" in patch) s.error = typeof patch.error === "string" ? patch.error : null;
  persist();
  return s;
}

// `data` del contrato: copia estructural (mutar el resultado NO afecta al estado).
export function snapshot() {
  const st = getState();
  return {
    phase: st.phase,
    currentStepId: st.currentStepId,
    startedAt: st.startedAt,
    updatedAt: st.updatedAt,
    lastError: st.lastError,
    steps: st.steps.map(s => ({ ...s }))
  };
}
