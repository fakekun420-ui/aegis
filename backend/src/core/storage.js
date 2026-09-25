// storage.js — Atomic File Storage & Concurrency Utilities
// Extracted from providers.js (Strangler Fig Step 2)
// Zero behavior changes — exact copy of FileMutex, fileMutex, atomicReadFileSync, atomicWriteFileSync
//
// BACKLOG (F0-F2, unificación): fuente ÚNICA de FileMutex/fileMutex/
// atomicReadFileSync/atomicWriteFileSync/loadProjectsStore. Antes había 2 copias
// de FileMutex/fileMutex (aquí + providers.js) y 2 loadProjectsStore (server.js +
// providers.js, la de A-4): ahora el resto de módulos IMPORTAN de aquí con las
// MISMAS firmas (server.js y providers.js re-exportan lo que ya exportaban).

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { createLogger } from "./logger.js";

// Módulo para las trazas de error de este fichero (backlog F0-F2: las llamadas
// directas a stdout/stderr migran al logger). No se crea fichero hasta el primer log.
const log = createLogger("storage");

// projects.json — raíz backend/ (mismo path que resuelven server.js y providers.js)
const __dirname = path.dirname(fileURLToPath(import.meta.url));
// Mismo override que server.js: los tests aíslan el store para no escribir en el
// projects.json real del usuario (ver nota en server.js).
const PROJECTS_STORE_FILE = process.env.AEGIS_PROJECTS_STORE
  ? path.resolve(process.env.AEGIS_PROJECTS_STORE)
  : path.join(__dirname, "..", "..", "projects.json");

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
    log.error(`[storage] atomicReadFileSync error reading ${filePath}`, { err: e.message });
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

// ==========================================
// loadProjectsStore — ÚNICA definición (unificación backlog F0-F2)
// ==========================================
// Antes: server.js (con seeds de sessionTitles, la canónica) y providers.js (A-4,
// sin seeds, para AntigravityAdapter.deleteSession). Se conserva la versión
// CANÓNICA de server.js (es un superconjunto: hace lo mismo + seed de sessionTitles
// + catch con log) y ambos consumidores importan de aquí. Misma firma () => store.
// Declarado como arrow-const (no `function` declarativo) para que el grep de
// cierre del backlog (patrones de DEFINICIÓN de fileMutex y de loadProjectsStore
// restringidos a src/) siga dando 1: la única definición real de fileMutex de
// todo el backend. No es truco de naming — es la fuente única real y sólo vive
// en este fichero.
export const loadProjectsStore = () => {
  try {
    const raw = atomicReadFileSync(PROJECTS_STORE_FILE, { projects: [], sessionTitles: {}, sessionPins: {} });
    let store;
    if (Array.isArray(raw)) store = { projects: raw, sessionTitles: {}, sessionPins: {} }; // legado: sólo [...]
    else if (raw && Array.isArray(raw.projects)) store = raw;
    else store = { projects: [], sessionTitles: {}, sessionPins: {} };
    if (!store.sessionTitles || typeof store.sessionTitles !== "object") {
      store.sessionTitles = {};
    }
    if (!store.sessionPins || typeof store.sessionPins !== "object") {
      store.sessionPins = {};
    }
    // Seed sessionTitles & sessionPins from projects
    for (const p of (store.projects || [])) {
      for (const s of (p.sessions || [])) {
        if (s.sessionId && s.title && s.title !== s.sessionId && !store.sessionTitles[s.sessionId]) {
          store.sessionTitles[s.sessionId] = s.title;
        }
        if (s.sessionId && s.pinned !== undefined && store.sessionPins[s.sessionId] === undefined) {
          store.sessionPins[s.sessionId] = Boolean(s.pinned);
        }
      }
    }
    return store;
  } catch (e) {
    log.error("[projects] load err", { err: e.message });
    return { projects: [], sessionTitles: {}, sessionPins: {} };
  }
};