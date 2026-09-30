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
/**
 * ¿Mi proceso padre es un runner de tests? Se lee UNA vez al cargar el modulo.
 * Misma tecnica que la guardia de supervivencia de server.js, y por el mismo motivo:
 * en produccion el padre es un shell y NO lleva `--test`, asi que aqui no cambia nada.
 */
const PADRE_ES_TEST_RUNNER = (() => {
  const padre = process.ppid;
  if (!padre || padre <= 1) return false;
  try {
    // Prefijo, no palabra: el padre real de un test es
    // `node --test-coverage-functions=0 --test-concurrency=0 --test-isolation=process`.
    const cmd = fs.readFileSync(`/proc/${padre}/cmdline`, "utf8").replace(/\0/g, " ");
    return /(^|\s)--test/.test(cmd);
  } catch (_) {
    return false;   // sin /proc: no se aisla. Fallar hacia "no cambiar nada".
  }
})();

export const LOG_DIR = (() => {
  const env = typeof process.env.AEGIS_LOG_DIR === "string" ? process.env.AEGIS_LOG_DIR.trim() : "";
  if (env) return path.resolve(env);
  // MEDIDO 2026-09-30: la suite escribia en el log de PRODUCCION. 342 lineas de ruido
  // de tests en el diagnostico real, y no solo en CI: `node --test` directo (que es
  // como se ejecuta la mayoria de las veces) no lleva AEGIS_LOG_DIR, asi que aislar
  // solo en package.json y en el workflow no arregla nada.
  //
  // Por eso el aislamiento es INTRINSECO: si mi padre es un runner de tests, el log
  // va a otro sitio. No hay ningun camino de invocacion que pueda contaminar.
  if (PADRE_ES_TEST_RUNNER) return path.join(__dirname, "..", "..", "logs-test");
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

// ---- stdout NO puede romper el logger (MEDIDO 2026-09-29) --------------------
// keepalive.sh abre la stdout del hub con `>` hacia hub.log. Si ese destino
// desaparece (rotacion, borrado, un reinicio de keepalive) el descriptor queda
// roto y `console.log` LANZA EPIPE. Y aqui estaba el bucle:
//     EPIPE -> uncaughtException -> el handler LOGUEA -> console.log -> EPIPE -> ...
// sin fin. Medido: 760 lineas de EPIPE en UN segundo, todas identicas, y el Hub
// con la CPU al 100%. Encima cada linea es una escritura en /sdcard, que es FUSE:
// un proceso bloqueado ahi queda en estado D y no se puede matar con una señal.
// Eso no es una teoria del cuelgue del movil: es el mecanismo.
//
// El contrato del sink de fichero (arriba) dice "JAMÁS lanza". Este lo cumple
// stdout tambien: el error se traga en el stream —no en cada write— y tras el
// PRIMER fallo se deja de intentar, que es lo que corta el bucle de verdad. El
// log REAL sigue yendo al sink de fichero, que es el que se lee para diagnosticar.
//
// MEDIDO 2026-09-30, y esto MEJORA la version anterior: abandonar stdout para
// siempre era un punto ciego que YO mismo me habia creado. Con stdout muerto ya no
// se puede observar el stdout desde fuera, asi que si el problema volvia a aparecer
// no habia forma de verlo. Ahora el abandono es:
//   - VISIBLE: se escribe una linea diciendo que se abandono (silencio aqui es
//     indistinguible de "no ha pasado nada")
//   - RECUPERABLE: se reintenta cada STDOUT_REINTENTO_MS. Un fd que se sana vuelve
//     a usarse solo, y si sigue roto no pasa nada: el error se traga y se espera otro
//     rato. Un reintento por intervalo NO puede reproducir el bucle.
let stdoutVivo = true;
let stdoutReintentoEn = 0;
let stdoutAvisado = false;
const STDOUT_REINTENTO_MS = 30000;
function marcarStdoutMuerto() {
  if (stdoutVivo) {
    // Una sola vez, y al sink de fichero: si se escribiera por stdout no llegaria nunca.
    try { sinkWrite(`[${new Date().toISOString()}] [WARN] [logger] stdout del hub no disponible (EPIPE o fd roto). El log sigue enteringo en ${LOG_FILE}; se reintenta cada ${STDOUT_REINTENTO_MS / 1000} s.\n`); } catch (_) {}
  }
  stdoutVivo = false;
  stdoutAvisado = true;
  stdoutReintentoEn = Date.now() + STDOUT_REINTENTO_MS;
}
try {
  if (process.stdout && typeof process.stdout.on === "function") {
    process.stdout.on("error", marcarStdoutMuerto);
  }
  if (process.stderr && typeof process.stderr.on === "function") {
    process.stderr.on("error", () => { /* idem: escribir nunca puede romper */ });
  }
} catch (_) { stdoutVivo = false; }

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
    // stdout con red de seguridad: `console.log` a un stream roto lanza EPIPE, y un
    // logger que lanza es un bucle (ver el bloque de arriba). Tras el primer fallo se
    // abandona stdout; el sink de fichero sigue recibiendo TODO.
    if (stdoutVivo) {
      try {
        console.log(entry);
      } catch (_) {
        marcarStdoutMuerto();
      }
    } else if (Date.now() >= stdoutReintentoEn) {
      // Reintento esporadico. Si el descriptor se ha sanado, stdout vuelve; si no, el
      // throw se traga aqui y no hay bucle (uno cada 30 s, no 760 por segundo).
      stdoutReintentoEn = Date.now() + STDOUT_REINTENTO_MS;
      try {
        console.log(entry);
        stdoutVivo = true;
        if (stdoutAvisado) {
          sinkWrite(`[${new Date().toISOString()}] [INFO] [logger] stdout del hub vuelve a funcionar.\n`);
          stdoutAvisado = false;
        }
      } catch (_) { /* sigue roto: se espera otro intervalo */ }
    }
    sinkWrite(`${entry}\n`); // sink propio backend/logs/aegis.log (F4) — mismo entry
  }
}

export function createLogger(moduleName) {
  return new Logger(moduleName);
}
