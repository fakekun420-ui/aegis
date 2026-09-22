export class Logger {
  constructor(moduleName) {
    this.moduleName = moduleName;
    this.logs = [];
  }
  
  info(msg, ctx = {}) { this.log("INFO", msg, ctx); }
  error(msg, ctx = {}) { this.log("ERROR", msg, ctx); }
  warn(msg, ctx = {}) { this.log("WARN", msg, ctx); }
  debug(msg, ctx = {}) { this.log("DEBUG", msg, ctx); }
  
  log(level, msg, ctx) {
    const entry = `[${new Date().toISOString()}] [${level}] [${this.moduleName}] ${msg} ${JSON.stringify(ctx)}`;
    this.logs.push(entry);
    if (this.logs.length > 1000) this.logs.shift();
    console.log(entry);
  }
}

export function createLogger(moduleName) {
  return new Logger(moduleName);
}
