import { agentPool } from "./agentPool.js";
import { eventBus, EVENTS } from "./eventBus.js";
import { WorkflowParser } from "./workflowParser.js";

export class WorkflowEngine {
  constructor() {
    this.parser = new WorkflowParser();
    // Estado REAL de la última ejecución por proyecto (in-memory) —
    // alimenta GET /api/workflows/:projectId/status (Models.kt WorkflowStatus)
    this.lastRun = new Map(); // projectId -> {id,status,currentStep,progress,startedAt,finishedAt,...}
  }

  // listWorkflows — lee los YAML/JSON del directorio de workflows del proyecto.
  // Sin workflows en disco => [] (lista vacía, nunca datos inventados).
  listWorkflows(projectId) {
    return this.parser.list(projectId);
  }

  // getWorkflowStatus — última ejecución registrada del proyecto (null si nunca corrió)
  getWorkflowStatus(projectId) {
    return this.lastRun.get(projectId) || null;
  }

  _setRun(projectId, patch) {
    const next = { ...(this.lastRun.get(projectId) || {}), ...patch };
    this.lastRun.set(projectId, next);
    return next;
  }

  async run(workflowId, projectId, trigger) {
    eventBus.publish(projectId, EVENTS.WORKFLOW_STARTED, { workflowId, trigger });
    this._setRun(projectId, {
      id: workflowId,
      status: "running",
      currentStep: null,
      progress: 0,
      trigger,
      startedAt: new Date().toISOString(),
      finishedAt: null,
      error: null,
      stepStatuses: {}
    });

    try {
      const wf = this.parser.parse(projectId, workflowId);
      const steps = wf.steps || [];
      const maxConcurrent = wf.max_concurrent || 4;
      const total = steps.length;
      let completedCount = 0;

      // Status tracking map for all steps: pending | running | completed | failed | skipped
      const stepStatuses = {};
      const stepMap = new Map();
      for (const step of steps) {
        const id = step.id || step.name || `step_${Math.random().toString(36).slice(2, 7)}`;
        step.id = id;
        stepStatuses[id] = "pending";
        stepMap.set(id, step);
      }

      this._setRun(projectId, { stepStatuses: { ...stepStatuses } });

      const inFlight = new Map(); // stepId -> Promise

      // DAG loop until all steps settled or aborted
      while (completedCount < total) {
        // Find runnable steps (dependencies satisfied and currently pending)
        const runnable = [];
        for (const step of steps) {
          if (stepStatuses[step.id] !== "pending") continue;

          const deps = Array.isArray(step.depends_on)
            ? step.depends_on
            : (step.depends_on ? [step.depends_on] : []);

          const depsSatisfied = deps.every(depId => stepStatuses[depId] === "completed");
          const depsFailed = deps.some(depId => stepStatuses[depId] === "failed" || stepStatuses[depId] === "skipped");

          if (depsFailed) {
            // Check if step is configured for on_failure
            if (step.on_failure) {
              runnable.push(step);
            } else {
              stepStatuses[step.id] = "skipped";
              completedCount++;
              this._setRun(projectId, {
                stepStatuses: { ...stepStatuses },
                progress: Math.round((completedCount / (total || 1)) * 100)
              });
            }
          } else if (depsSatisfied) {
            runnable.push(step);
          }
        }

        // Check if no steps are running and no runnable steps remain (deadlock / finished)
        if (runnable.length === 0 && inFlight.size === 0) {
          break;
        }

        // Launch available steps up to maxConcurrent
        for (const step of runnable) {
          if (inFlight.size >= maxConcurrent) break;

          const sid = step.id;
          stepStatuses[sid] = "running";
          this._setRun(projectId, {
            currentStep: sid,
            stepStatuses: { ...stepStatuses }
          });

          const p = (async () => {
            try {
              const res = await agentPool.dispatch(step.agent, projectId, { stepId: sid, params: step.params }, maxConcurrent);
              if (res && res.promise) {
                await res.promise;
              }
              stepStatuses[sid] = "completed";
            } catch (err) {
              stepStatuses[sid] = "failed";
              throw err;
            } finally {
              completedCount++;
              inFlight.delete(sid);
              this._setRun(projectId, {
                stepStatuses: { ...stepStatuses },
                progress: Math.round((completedCount / (total || 1)) * 100)
              });
            }
          })();

          inFlight.set(sid, p);
        }

        // Wait for at least one in-flight step to complete
        if (inFlight.size > 0) {
          try {
            await Promise.race(Array.from(inFlight.values()));
          } catch (stepErr) {
            // Handled in individual step catch, loop continues to re-evaluate dependencies or on_failure handlers
          }
        }
      }

      // Final status determination
      const hasFailures = Object.values(stepStatuses).some(st => st === "failed");
      const finalStatus = hasFailures ? "failed" : "completed";

      this._setRun(projectId, {
        status: finalStatus,
        currentStep: null,
        progress: 100,
        finishedAt: new Date().toISOString(),
        stepStatuses: { ...stepStatuses }
      });

      if (finalStatus === "completed") {
        eventBus.publish(projectId, EVENTS.WORKFLOW_COMPLETED, { workflowId });
        return { ok: true, workflowId, stepStatuses };
      } else {
        eventBus.publish(projectId, EVENTS.WORKFLOW_FAILED, { workflowId, error: "One or more steps failed" });
        return { ok: false, workflowId, error: "One or more steps failed", stepStatuses };
      }
    } catch (e) {
      this._setRun(projectId, {
        status: "failed",
        currentStep: null,
        finishedAt: new Date().toISOString(),
        error: e.message
      });
      eventBus.publish(projectId, EVENTS.WORKFLOW_FAILED, { workflowId, error: e.message });
      return { ok: false, error: e.message };
    }
  }
}

export const workflowEngine = new WorkflowEngine();
