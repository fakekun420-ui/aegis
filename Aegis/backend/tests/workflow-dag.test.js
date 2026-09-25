import test from "node:test";
import assert from "node:assert/strict";
import { WorkflowEngine } from "../src/core/workflowEngine.js";
import { agentPool } from "../src/core/agentPool.js";

test("WorkflowEngine: executes DAG workflow respecting dependencies and concurrency", async () => {
  const engine = new WorkflowEngine();
  const executionOrder = [];

  class MockAgent {
    constructor(projectId) {
      this.projectId = projectId;
      this.id = `mock_${Math.random()}`;
    }
    async execute(context) {
      executionOrder.push(`start_${context.stepId}`);
      await new Promise(r => setTimeout(r, 20));
      executionOrder.push(`end_${context.stepId}`);
    }
  }

  agentPool.register("MockAgent", MockAgent);

  // Mock parser
  engine.parser = {
    parse: () => ({
      max_concurrent: 3,
      steps: [
        { id: "stepA", agent: "MockAgent", depends_on: [] },
        { id: "stepB", agent: "MockAgent", depends_on: ["stepA"] },
        { id: "stepC", agent: "MockAgent", depends_on: ["stepA"] },
        { id: "stepD", agent: "MockAgent", depends_on: ["stepB", "stepC"] }
      ]
    })
  };

  const res = await engine.run("test-dag", "prj-test", "manual");
  assert.equal(res.ok, true);
  assert.equal(res.stepStatuses.stepA, "completed");
  assert.equal(res.stepStatuses.stepB, "completed");
  assert.equal(res.stepStatuses.stepC, "completed");
  assert.equal(res.stepStatuses.stepD, "completed");

  const startA = executionOrder.indexOf("start_stepA");
  const endA = executionOrder.indexOf("end_stepA");
  const startB = executionOrder.indexOf("start_stepB");
  const startC = executionOrder.indexOf("start_stepC");
  const startD = executionOrder.indexOf("start_stepD");

  assert.ok(startA < endA, "stepA finishes after starting");
  assert.ok(endA <= startB, "stepB starts after stepA finishes");
  assert.ok(endA <= startC, "stepC starts after stepA finishes");
  assert.ok(startB < startD && startC < startD, "stepD starts after both B and C finish");
});
