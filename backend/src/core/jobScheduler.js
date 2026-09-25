export class JobScheduler {
  constructor() {
    this.jobs = new Map();
    this.intervals = new Map();
  }
  
  registerJob(id, intervalMs, handler) {
    this.jobs.set(id, { intervalMs, handler, enabled: true, lastRun: null });
  }
  
  start() {
    for (const [id, job] of this.jobs) {
      if (!job.enabled) continue;
      const t = setInterval(async () => {
        try {
          await job.handler();
          job.lastRun = new Date().toISOString();
        } catch (e) {
          console.error(`[Job ${id}] failed: ${e.message}`);
        }
      }, job.intervalMs);
      this.intervals.set(id, t);
    }
  }
  
  stop() {
    for (const t of this.intervals.values()) clearInterval(t);
    this.intervals.clear();
  }
}
export const jobScheduler = new JobScheduler();
