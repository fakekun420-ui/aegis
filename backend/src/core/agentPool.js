import { ResearchAgent } from "../agents/ResearchAgent.js";
import { ArchitectAgent } from "../agents/ArchitectAgent.js";
import { AuditorAgent } from "../agents/AuditorAgent.js";

export class AgentPool {
  constructor() {
    this.registry = {
      ResearchAgent,
      ArchitectAgent,
      AuditorAgent
    };
    this.active = new Map(); // projectId -> Array<Agent>
    this.maxConcurrent = 4; // configurable concurrency limit
  }
  
  register(name, agentClass) {
    this.registry[name] = agentClass;
  }
  
  async dispatch(agentType, projectId, context, maxConcurrent = null) {
    const Class = this.registry[agentType];
    if (!Class) throw new Error(`Unknown agent type: ${agentType}`);
    
    if (!this.active.has(projectId)) this.active.set(projectId, []);
    const projAgents = this.active.get(projectId);
    
    const limit = maxConcurrent || this.maxConcurrent || 4;
    if (projAgents.length >= limit) {
      throw new Error(`Concurrency limit reached: max ${limit} agents per project`);
    }
    
    const agent = new Class(projectId);
    projAgents.push(agent);
    
    // Track execution promise
    const execPromise = agent.execute(context).finally(() => {
      const idx = projAgents.findIndex(a => a.id === agent.id);
      if (idx !== -1) projAgents.splice(idx, 1);
    });
    
    return { ok: true, agentId: agent.id, agentType, promise: execPromise };
  }
  
  getStatus(projectId) {
    const list = this.active.get(projectId) || [];
    return list.map(a => ({ id: a.id, name: a.name, status: a.status }));
  }
  
  cancelAll(projectId) {
    this.active.set(projectId, []);
    return { ok: true, message: "All agents cancelled" };
  }
}
export const agentPool = new AgentPool();
