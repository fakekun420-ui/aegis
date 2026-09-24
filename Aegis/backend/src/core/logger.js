// logger.js — logger del hub (createLogger) con SINK A FICHERO + ROTACIÓN (FASE F4)
//
// Formato de línea INALTERADO respecto a F0-F3 (compat con el tail de keepalive
// y con todo lo que ya parsea estas líneas):
//   [ISO] [NIVEL] [módulo] <msg> <ctx-json>
// La única diferencia a nivel fichero es el "\n" final (append) — en stdout
// lo aporta console.log como hasta ahora.
//
// Sink (F4):
//   fichero  backend/logs/aegis.log  (dir auto-creado; gitignored — .gitignore)
//   env      AEGIS_LOG_DIR           redirige el dir (TESTS: no ensuciar el repo)
//            AEGIS_LOG_MAX_BYTES     tamaño máximo por fichero (default 1MB)
//   rotación por tamaño: al superar el máximo => aegis.log -> aegis.log.1
//     (.1->.2, .2->.3, se borra .3), 3 backups => hasta 4MB en total.
//   escritura SÍNCRONA simple (fs.writeSync append) — orden garantizado, O(linea).
//   JAMÁS lanza: cualquier fallo de E/S (dir no escribible, disco lleno, fd
//   invalidado) desactiva el sink y el logger sigue SOLO con stdout (console.log),
//   que es además lo que keepalive.sh redirige a hub.log.
//
// Nota: NO se duplica el formato de línea — sink y stdout reciben EL MISMO entry.

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// Raíz del backend (.../backend) => sink por defecto en backend/logs/aegis.log
export const LOG_DIR = (() => {
  const env = typeof process.env.AEGIS_LOG_DIR === "string" ? process.env.AEGIS_LOG_DIR.trim() : "";
  if (env) return path.resolve(env);
  return path.join(__dirname, "..", "..", "logs");
})();
export const LOG_FILE = path.join(LOG_DIR, "aegis.log");

// Tamaño máximo por fichero (default ~1MB). AEGIS_LOG_MAX_BYTES sólo lo usa
// tests (rotación sin escribir 1MB reales).
const MAX_BYTES = (() => {
  const n = parseInt(process.env.AEGIS_LOG_MAX_BYTES || "", 10);
  return Number.isFinite(n) && n > 0 ? n : 1024 * 1024;
})();
const MAX_BACKUPS = 3; // aegis.log.1 .. aegis.log.3

// Estado del sink a nivel de MÓDULO: todos los createLogger() comparten UN fd.
const sink = { fd: null, bytes: 0, disabled: false };

/** Abre (o reabre) el sink. Lanza sólo hacia el caller envuelto en try/catch. */
function sinkOpen() {
  fs.mkdirSync(LOG_DIR, { recursive: true });
  let bytes = 0;
  try { bytes = fs.statSync(LOG_FILE).size; } catch (_) { bytes = 0; }
  sink.fd = fs.openSync(LOG_FILE, "a");
  sink.bytes = bytes;
}

/** Rotación por tamaño: .3 se elimina, .2->.3, .1->.2, principal->.1. */
function sinkRotate() {
  if (sink.fd !== null) { try { fs.closeSync(sink.fd); } catch (_) {} sink.fd = null; }
  const b1 = `${LOG_FILE}.1`, b2 = `${LOG_FILE}.2`, b3 = `${LOG_FILE}.3`;
  try { if (fs.existsSync(b3)) fs.unlinkSync(b3); } catch (_) {}
  try { if (fs.existsSync(b2)) fs.renameSync(b2, b3); } catch (_) {}
  try { if (fs.existsSync(b1)) fs.renameSync(b1, b2); } catch (_) {}
  try { fs.renameSync(LOG_FILE, b1); } catch (_) {}
  sink.bytes = 0;
  // reapertura: si falla, sinkOpen() de sinkWrite() lo reintenta una vez más
  // y, si también falla, el catch general desactiva el sink (sigue con stdout).
  sinkOpen();
}

/**
 * Append síncrono al sink. Nunca lanza: fallo => sink desactivado y el logger
 * continúa sólo con stdout (contrato F4: la E/S del fichero no puede tumbar nada).
 */
function sinkWrite(entry) {
  if (sink.disabled) return;
  try {
    if (sink.fd === null) sinkOpen();
    const buf = Buffer.from(entry, "utf8");
    // Rotar ANTES de escribir si esta línea desbordaría el máximo (un fichero
    // sólo puede exceder MAX por UNA línea — la que provoca la rotación).
    if (sink.bytes > 0 && sink.bytes + buf.length > MAX_BYTES) sinkRotate();
    let written = 0;
    while (written < buf.length) {
      written += fs.writeSync(sink.fd, buf, written, buf.length - written);
    }
    sink.bytes += written;
  } catch (_) {
    sink.disabled = true; // jamás lanza: de aquí en adelante sólo stdout
    try { if (sink.fd !== null) { fs.closeSync(sink.fd); sink.fd = null; } } catch (__) {}
  }
}

/** Para /api/system/logs: ruta real del sink (dir puede venir de AEGIS_LOG_DIR). */
export function getLogFile() { return LOG_FILE; }
export function getLogDir() { return LOG_DIR; }

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
    console.log(entry);   // stdout del hub (keepalive.sh -> hub.log) — sin cambios
    sinkWrite(`${entry}\n`); // sink propio backend/logs/aegis.log (F4) — mismo entry
  }
}

export function createLogger(moduleName) {
  return new Logger(moduleName);
}
