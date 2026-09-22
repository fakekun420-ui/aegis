import { EventEmitter } from "node:events";

class AegisEventBus extends EventEmitter {
  constructor() {
    super();
    this.setMaxListeners(100);
  }
  
  publish(projectId, event, data) {
    const topic = projectId ? `${projectId}:${event}` : event;
    console.log(`[EventBus] ${new Date().toISOString()} | ${topic}`);
    this.emit(topic, data);
  }
  
  subscribe(projectId, event, handler) {
    const topic = projectId ? `${projectId}:${event}` : event;
    this.on(topic, handler);
  }
  
  unsubscribe(projectId, event, handler) {
    const topic = projectId ? `${projectId}:${event}` : event;
    this.removeListener(topic, handler);
  }
}

export const eventBus = new AegisEventBus();
export const EVENTS = {
  AGENT_STARTED: "AGENT_STARTED",
  AGENT_COMPLETED: "AGENT_COMPLETED",
  AGENT_FAILED: "AGENT_FAILED",
  ARTIFACT_CREATED: "ARTIFACT_CREATED",
  WORKFLOW_STARTED: "WORKFLOW_STARTED",
  WORKFLOW_COMPLETED: "WORKFLOW_COMPLETED",
  WORKFLOW_FAILED: "WORKFLOW_FAILED"
};
