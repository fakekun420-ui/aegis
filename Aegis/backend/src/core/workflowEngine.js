import { agentPool } from "./agentPool.js";
import { eventBus, EVENTS } from "./eventBus.js";
import { WorkflowParser } from "./workflowParser.js";

export class WorkflowEngine {
  constructor() {
    this.parser = new WorkflowParser();
  }
  
  async run(workflowId, projectId, trigger) {
    eventBus.publish(projectId, EVENTS.WORKFLOW_STARTED, { workflowId, trigger });
    try {
      const wf = this.parser.parse(projectId, workflowId);
      
      // Simplistic sequential execution for now instead of DAG full parallel
      for (const step of wf.steps) {
        await agentPool.dispatch(step.agent, projectId, { stepId: step.id });
        // Polling agentPool to wait until it finishes...
        // In a real DAG, we use promises and eventBus listeners.
      }
      
      eventBus.publish(projectId, EVENTS.WORKFLOW_COMPLETED, { workflowId });
      return { ok: true, workflowId };
    } catch (e) {
      eventBus.publish(projectId, EVENTS.WORKFLOW_FAILED, { workflowId, error: e.message });
      return { ok: false, error: e.message };
    }
  }
}
export const workflowEngine = new WorkflowEngine();
