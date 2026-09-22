// storage.js — Atomic File Storage & Concurrency Utilities
// Extracted from providers.js (Strangler Fig Step 2)
// Zero behavior changes — exact copy of FileMutex, fileMutex, atomicReadFileSync, atomicWriteFileSync

import fs from "node:fs";
import path from "node:path";

// ==========================================
// FileMutex — Per-file exclusive async execution queue
// Ensures atomic read-modify-write cycles without races
// ==========================================

export class FileMutex {
  constructor() {
    this.queues = new Map();
  }

  async runExclusive(filePath, fn) {
    const key = path.resolve(filePath);
    let queue = this.queues.get(key) || Promise.resolve();
    const next = queue.then(async () => {
      try {
        return await fn();
      } finally {
        if (this.queues.get(key) === next) {
          this.queues.delete(key);
        }
      }
    });
    this.queues.set(key, next.catch(() => {}));
    return next;
  }
}

export const fileMutex = new FileMutex();

// ==========================================
// Atomic Read — Safe JSON read with fallback
// ==========================================

export function atomicReadFileSync(filePath, fallback = null) {
  try {
    if (!fs.existsSync(filePath)) return fallback;
    const content = fs.readFileSync(filePath, "utf8");
    return JSON.parse(content);
  } catch (e) {
    console.error(`[storage] atomicReadFileSync error reading ${filePath}:`, e.message);
    return fallback;
  }
}

// ==========================================
// Atomic Write — Write to temp + fsync + rename (POSIX atomic replace)
// Guarantees: no partial writes, no corruption on crash/power loss
// ==========================================

export function atomicWriteFileSync(filePath, data) {
  const dir = path.dirname(filePath);
  if (!fs.existsSync(dir)) {
    fs.mkdirSync(dir, { recursive: true });
  }
  const tmpFile = path.join(
    dir,
    `.${path.basename(filePath)}.${Date.now()}.${Math.random().toString(36).slice(2)}.tmp`
  );
  const content = typeof data === "string" ? data : JSON.stringify(data, null, 2);
  const fd = fs.openSync(tmpFile, "w");
  try {
    fs.writeFileSync(fd, content, "utf8");
    fs.fsyncSync(fd);
  } finally {
    fs.closeSync(fd);
  }
  fs.renameSync(tmpFile, filePath);
}