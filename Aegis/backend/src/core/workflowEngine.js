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
      error: null
    });
    try {
      const wf = this.parser.parse(projectId, workflowId);
      const total = wf.steps.length;
      let done = 0;

      // Simplistic sequential execution for now instead of DAG full parallel
      for (const step of wf.steps) {
        // Progreso real por paso: la app hace polling de status cada 3s
        this._setRun(projectId, {
          currentStep: step.id || step.agent || null,
          progress: Math.round((done / (total || 1)) * 100)
        });
        await agentPool.dispatch(step.agent, projectId, { stepId: step.id });
        // Polling agentPool to wait until it finishes...
        // In a real DAG, we use promises and eventBus listeners.
        done++;
        this._setRun(projectId, { progress: Math.round((done / (total || 1)) * 100) });
      }

      this._setRun(projectId, {
        status: "completed",
        currentStep: null,
        progress: 100,
        finishedAt: new Date().toISOString()
      });
      eventBus.publish(projectId, EVENTS.WORKFLOW_COMPLETED, { workflowId });
      return { ok: true, workflowId };
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
