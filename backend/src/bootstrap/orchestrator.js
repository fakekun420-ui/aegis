// orchestrator.js — ejecutor singleton del bootstrap (FASE F1 · backend)
//
// API: bootstrapOrchestrator.runOnce({resume, retryFrom}) · cancel() · isRunning()
//
// Flujo por paso (persistiendo CADA transición => progreso en vivo del polling):
//   1) status="running" + persist (+ rollback="none": cada intento arranca limpio)
//   2) check()  -> done:true  => status="skipped" + detail (ya estaba satisfecho
//                               ANTES de esta ejecución; idempotencia)
//   3) si cancelRequested  => paso en curso vuelve a "pending", phase="paused" y PARA
//   4) ctx.resetRunArtifacts()  (F2: el ctx de artefactos/rollbackFns se vacía ANTES
//      de cada run => el rollback de un paso jamás ve artefactos de otro paso)
//   5) await run()         => status="done", progress=100
//   6) si run() lanza      => status="failed" + error y llama a rollback(ctx) (F2):
//      el hook devuelve "none" | "done" | "failed" y el orquestador lo persiste en el
//      campo `rollback` del paso (si LANZA => "failed" + detalle). Luego phase="failed"
//      + lastError y PARA (reanudable con run {resume:true} o retry del paso).
//
// Semántica de rollback (F2): best-effort y NO destructivo fuera de lo creado por
// ESTE run — los hooks registran sus acciones en ctx.onRollback() durante el run
// fallido y drainRollbacks() las ejecuta en LIFO. Un rollback fallido deja el paso
// en status="failed" reanudable igualmente (rollback="failed" sólo informa).
//
// Cancelación COOPERATIVA: la bandera se consulta entre pasos, tras la
// comprobación, tras run() y al final del bucle; dentro de un paso largo, el
// hook puede llamar a ctx.cancelRequested() para abortar por su cuenta.
// Nunca bloquea el event loop: todo es await (execFile/fetch/setTimeout) y la
// única I/O sincrónica es la escritura atómica de un JSON de pocos KB (mismo
// patrón que ui-state.json del hub).
//
// AEGIS_BOOTSTRAP_STEP_DELAY_MS (default 0): pausa opcional antes de CADA paso.
// Sirve para demostrar progreso en vivo y para que los tests asserten el409
// ALREADY_RUNNING de forma determinista; en producción se queda a 0.

import { createLogger } from "../core/logger.js";
import { snapshot, update, updateStep, STATE_FILE, BACKEND_DIR } from "./state.js";
import { STEPS, makeExecFile } from "./steps.js";

const log = createLogger("bootstrap");

const CANCEL_WAIT_MS = 15000; // tope de espera de cancel() antes de forzar la pausa
const parsedDelay = parseInt(process.env.AEGIS_BOOTSTRAP_STEP_DELAY_MS || "0", 10);
const STEP_DELAY_MS = Number.isFinite(parsedDelay) && parsedDelay > 0 ? Math.min(parsedDelay, 60000) : 0;

const execFile = makeExecFile(process.env);

// --- flags de proceso (singleton en memoria) ---
let running = false;
let cancelRequested = false;
let runPromise = null;

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

function isRunning() { return running; }

function alreadyRunningError() {
  const err = new Error("bootstrap ya en ejecución (ALREADY_RUNNING)");
  err.code = "ALREADY_RUNNING";
  return err;
}

// ctx compartido por check/run/rollback (firma documentada en steps.js)
function makeCtx() {
  return {
    execFile,
    log,
    dry: process.env.AEGIS_BOOTSTRAP_DRY === "1",
    progress(pct, detail) {
      const cur = snapshot().currentStepId;
      if (!cur) return;
      const patch = { progress: pct };
      if (typeof detail === "string") patch.detail = detail;
      updateStep(cur, patch);
    },
    cancelRequested: () => cancelRequested,
    paths: { backendDir: BACKEND_DIR, stateFile: STATE_FILE },
    env: process.env,

    // --- F2: artefactos DEL PASO EN CURSO (se vacían antes de CADA run) ---
    // rollbackFns: cierres de reversa registrados por run() con ctx.onRollback(fn)
    //              — ejecutados en LIFO por steps.drainRollbacks() si run() lanza.
    // artifacts:   inventario legible (tipo/path) de lo que run() creó; alimenta
    //              los logs y el detalle de diagnóstico, jamás se auto-destruye.
    rollbackFns: [],
    artifacts: [],
    onRollback(fn) { if (typeof fn === "function") this.rollbackFns.push(fn); },
    recordArtifact(a) { this.artifacts.push(a); },
    resetRunArtifacts() { this.rollbackFns = []; this.artifacts = []; }
  };
}

// Pausa: el paso en curso (si lo hay) vuelve a "pending" y phase="paused".
// Idempotente: cancel() y el runner pueden llamarlo más de una vez.
function pauseBecause(reason) {
  const st = snapshot();
  const cur = st.steps.find(s => s.status === "running");
  if (cur) updateStep(cur.id, { status: "pending", progress: 0, detail: `cancelado: ${reason} — pendiente de reanudar` });
  update({ phase: "paused" });
  log.info(`[bootstrap] ejecución PAUSADA (${reason}) — reanudable con POST /api/bootstrap/run {"resume":true}`);
}

async function execute(startIdx) {
  const ctx = makeCtx();

  for (let i = startIdx; i < STEPS.length; i++) {
    const def = STEPS[i];

    // (3) cancelación entre pasos
    if (cancelRequested) { pauseBecause("solicitada antes de iniciar el paso"); return; }
    if (STEP_DELAY_MS > 0) {
      await sleep(STEP_DELAY_MS);
      if (cancelRequested) { pauseBecause("solicitada durante la espera entre pasos"); return; }
    }

    // (1) transición a running + persist (progreso en vivo). rollback:"none":
    // cada intento arranca sin reversa heredada de un fallo anterior.
    updateStep(def.id, { status: "running", progress: 0, rollback: "none", detail: "comprobando…", error: null });
    log.info(`[bootstrap] paso "${def.id}" (${def.title}): comprobando`);

    // (2) check idempotente — nunca debe tumbar el runner
    let chk = null;
    try {
      chk = await def.check(ctx);
    } catch (e) {
      chk = { done: false, detail: `error en la comprobación: ${String((e && e.message) || e).slice(0, 200)}` };
    }

    // (3) cancelación durante la comprobación => el paso vuelve a pending
    if (cancelRequested) { pauseBecause("solicitada durante la comprobación"); return; }

    if (chk && chk.done) {
      const detail = String((chk.detail && String(chk.detail)) || "ya satisfecho");
      updateStep(def.id, { status: "skipped", progress: 100, detail, error: null });
      log.info(`[bootstrap] paso "${def.id}": skipped — ${detail.slice(0, 200)}`);
      continue;
    }

    const why = chk && chk.detail ? String(chk.detail) : "comprobación no satisfecha";
    updateStep(def.id, { progress: 0, detail: why });
    log.info(`[bootstrap] paso "${def.id}": ejecutando — ${why.slice(0, 200)}`);

    // (4)+(5) run / fallo. El ctx de artefactos se vacía ANTES del run: el
    // rollback de ESTE paso sólo verá lo que ESTE run registre.
    try {
      ctx.resetRunArtifacts();
      await def.run(ctx);
      // (3) cancelación durante run(): el paso en curso pasa a pending aunque
      // run() haya vuelto — los pasos son idempotentes, resume lo re-ejecuta.
      if (cancelRequested) { pauseBecause("solicitada durante la ejecución del paso"); return; }
      updateStep(def.id, { status: "done", progress: 100, error: null });
      log.info(`[bootstrap] paso "${def.id}": done`);
    } catch (e) {
      const msg = String((e && e.message) || e).slice(0, 800);
      if (cancelRequested) { pauseBecause("solicitada durante la ejecución del paso"); return; }
      updateStep(def.id, { status: "failed", error: msg });
      log.error(`[bootstrap] paso "${def.id}": FAILED — ${msg}`);
      // Rollback F2: el hook devuelve "none" (nada que revertir) | "done"
      // (todo lo registrado revertido) | "failed" (alguna acción falló); si
      // LANZA, queda "failed". En cualquier caso el paso sigue failed reanudable.
      try {
        const res = await def.rollback(ctx);
        if (res === "done" || res === "none") {
          updateStep(def.id, { rollback: res });
          log.info(`[bootstrap] paso "${def.id}": rollback => ${res}`);
        } else if (res === "failed") {
          updateStep(def.id, { rollback: "failed", detail: "rollback fallido (best-effort, sólo lo creado por este run) — paso failed reanudable" });
          log.error(`[bootstrap] paso "${def.id}": rollback FAILED (best-effort)`);
        } else {
          log.info(`[bootstrap] paso "${def.id}": rollback sin declaración (=> none)`);
        }
      } catch (re) {
        updateStep(def.id, { rollback: "failed", detail: `rollback fallido: ${String((re && re.message) || re).slice(0, 160)} — paso failed reanudable` });
        log.error(`[bootstrap] paso "${def.id}": rollback FAILED — ${String((re && re.message) || re)}`);
      }
      // phase failed + lastError y PARA: estado honesto y reanudable.
      update({ phase: "failed", lastError: `${def.id}: ${msg}` });
      return;
    }
  }

  if (cancelRequested) { pauseBecause("solicitada al finalizar la ejecución"); return; }
  update({ phase: "done", lastError: null });
  log.info(`[bootstrap] ejecución COMPLETADA — ${STEPS.length} pasos done/skipped`);
}

// Lanza una ejecución. LANZA sincrónicamente con code="ALREADY_RUNNING" si ya
// hay una en curso (el router la traduce a409); si no, pone phase="running"
// ANTES de responder y devuelve la promesa del runner (fire-and-forget para el
// router: el202 no espera a que acabe). La promesa NUNCA se rechaza.
function runOnce({ resume = false, retryFrom = null } = {}) {
  if (running || snapshot().phase === "running") throw alreadyRunningError();

  let startIdx = 0;
  if (retryFrom != null) {
    startIdx = STEPS.findIndex(s => s.id === retryFrom);
    if (startIdx < 0) {
      const err = new Error(`paso desconocido: ${retryFrom}`);
      err.code = "NOT_FOUND";
      throw err;
    }
  }

  running = true;
  cancelRequested = false;
  update({ phase: "running", startedAt: new Date().toISOString(), lastError: null });
  log.info(
    `[bootstrap] ejecución ${retryFrom ? `RETRY desde "${retryFrom}"` : resume ? "reanudada" : "iniciada"} ` +
    `— dry=${process.env.AEGIS_BOOTSTRAP_DRY === "1"} delayMs=${STEP_DELAY_MS} state=${STATE_FILE}`
  );

  runPromise = execute(startIdx)
    .catch(e => {
      // Red de seguridad: execute() ya captura paso a paso; esto cubriría un
      // fallo del propio runner => phase failed, nunca unhandledRejection.
      const msg = String((e && e.message) || e).slice(0, 800);
      log.error(`[bootstrap] runner inesperado: ${msg}`);
      try { update({ phase: "failed", lastError: msg }); } catch { /* estado ya cerrado */ }
    })
    .finally(() => { running = false; runPromise = null; });

  return runPromise;
}

// Cancelación cooperativa. Responde {wasRunning, phase}:
//   - no corría        => {wasRunning:false} (el router responde409 NOT_RUNNING)
//   - corría           => espera al siguiente checkpoint del runner (tope 15s),
//                         y si aún no hubo pausa la fuerza => phase="paused"
//                         SIEMPRE garantizado antes del200 del contrato.
async function cancel() {
  if (!running || !runPromise) return { wasRunning: false, phase: snapshot().phase };
  cancelRequested = true;
  log.info("[bootstrap] cancel solicitada — esperando al siguiente checkpoint del runner");
  try { await Promise.race([runPromise, sleep(CANCEL_WAIT_MS)]); } catch { /* runner ya cerrado */ }
  if (snapshot().phase === "running") {
    pauseBecause("solicitada (checkpoint forzado: el paso no respondió a tiempo)");
  }
  return { wasRunning: true, phase: snapshot().phase };
}

// Singleton en memoria (un hub = un runner)
export const bootstrapOrchestrator = { runOnce, cancel, isRunning };
