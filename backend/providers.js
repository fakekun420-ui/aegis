// providers.js — Universal AI Coding Agent Provider Abstraction
// Robust adapter layer for OpenCode (HTTP serve mode).
// Hard 90s timeouts, connection leak prevention, zombie process cleanup, atomic concurrency.

import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { spawn } from "node:child_process";
// BACKLOG (F0-F2): llamadas directas a stdout/stderr -> logger del hub (mismo
// formato/sink que el resto). Nivel coherente: log->info, warn->warn, error->error.
import { createLogger } from "./src/core/logger.js";
// BACKLOG (F0-F2, unificación): FileMutex/fileMutex/atomic*/loadProjectsStore viven
// SOLO en src/core/storage.js. Se importan y se re-exportan con las MISMAS firmas
// para no romper a los consumidores históricos (server.js importa fileMutex/
// atomic*/normalizeMessage de ESTE fichero; aquí sólo se usan internamente).
import {
  FileMutex,
  fileMutex,
  atomicReadFileSync,
  atomicWriteFileSync,
  loadProjectsStore
} from "./src/core/storage.js";

export { FileMutex, fileMutex, atomicReadFileSync, atomicWriteFileSync };

const log = createLogger("providers");

// ==========================================
// Message Normalizer (Strict Typing)
// Conforms to docs/FRONTEND_CONTRACT.md and Android Models.kt
// ==========================================

export function normalizeMessage(raw, sessionId = "", index = 0) {
  if (!raw || typeof raw !== "object") {
    const text = String(raw || "");
    const now = Date.now();
    return {
      role: "assistant",
      text,
      info: {
        id: `msg_${sessionId || "hub"}_${now}_${index}`,
        role: "assistant",
        timestamp: now,
        time: { created: now },
        status: "SENT",
        deliveryStatus: "SENT"
      },
      parts: [{ id: `prt_${now}_${index}`, type: "text", text }]
    };
  }

  // Extract role
  let role = raw.role || raw.info?.role || (raw.type === "user" ? "user" : "assistant");
  if (role !== "user" && role !== "assistant") role = "assistant";

  // Extract timestamp
  const timestamp =
    raw.info?.timestamp ||
    raw.info?.time?.created ||
    raw.timestamp ||
    raw.time?.created ||
    Date.now();

  // Extract text
  let text = "";
  if (typeof raw.text === "string") {
    text = raw.text;
  } else if (typeof raw.response === "string") {
    text = raw.response;
  } else if (Array.isArray(raw.parts)) {
    text = raw.parts
      .filter((p) => p && (p.type === "text" || !p.type) && typeof p.text === "string")
      .map((p) => p.text)
      .join("\n");
  }

  // Extract / normalize parts
  let parts = [];
  if (Array.isArray(raw.parts) && raw.parts.length > 0) {
    parts = raw.parts.map((p, pIdx) => ({
      id: p.id || `prt_${timestamp}_${pIdx}`,
      type: p.type || "text",
      text: p.text || (typeof p === "string" ? p : ""),
      ...(p.mime ? { mime: p.mime } : {}),
      ...(p.filename ? { filename: p.filename } : {}),
      ...(p.url ? { url: p.url } : {}),
      ...(p.tool ? { tool: p.tool } : {}),
      ...(p.callID ? { callID: p.callID } : {}),
      ...(p.state ? { state: p.state } : {}),
        // Marcadores del recorte de payload (server.js -> trimPartText). Sin esto la
        // app no puede distinguir "esta parte venia recortada" de "es pequena": una
        // imagen de un chat largo se queda en FileRow sin forma de pedir su binario.
        // Opcionales: si no hubo recorte no aparecen.
        ...(p.hasBinary ? { hasBinary: true, binaryChars: p.binaryChars || 0, binaryInState: p.binaryInState || 0 } : {}),
        ...(p.truncated ? { truncated: true, fullChars: p.fullChars || 0 } : {}),
    }));
  } else {
    parts = [{ id: `prt_${timestamp}_0`, type: "text", text }];
  }

  const id = raw.info?.id || raw.id || `msg_${sessionId || "hub"}_${timestamp}_${index}`;
  const status = raw.info?.status || raw.info?.deliveryStatus || "SENT";

  const normalized = {
    role,
    text,
    info: {
      id,
      role,
      timestamp,
      // Se conserva el objeto `time` ORIGINAL en lugar de reconstruirlo con solo
      // `created`. OpenCode marca el cierre real de un turno con `time.streamed`
      // (y `completed`), y la app lo usa para dibujar el separador "respuesta
      // final" y lanzar la notificación en segundo plano. Al tirar esas claves,
      // la app veía SIEMPRE time={created} y el aviso no se disparaba nunca, por
      // muy correcto que fuera el resto del flujo.
      time: (() => {
        const src = (raw.info && raw.info.time) || raw.time;
        const t = src && typeof src === "object" ? { ...src } : {};
        if (t.created == null) t.created = timestamp;
        return t;
      })(),
      status,
      deliveryStatus: status
    },
    parts
  };

  if (raw._agyMeta) {
    // Passthrough de metadatos de antigravity que ya no se generan. Se conserva el
    // bloque por si un payload antiguo los trae: leerlos no cuesta nada y henceforth
    // ignorarlos seria una comprobacion por mensaje sin ningun beneficio.
    normalized._agyMeta = raw._agyMeta;
  }

  return normalized;
}

// ==========================================
// Base Provider Adapter Interface
// ==========================================

export class BaseProviderAdapter {
  constructor(id, name, type = "cli") {
    this.id = id;
    this.name = name;
    this.type = type; // "serve" | "cli"
  }

  async isHealthy() {
    throw new Error(`isHealthy() not implemented on ${this.name}`);
  }

  async listSessions() {
    throw new Error(`listSessions() not implemented on ${this.name}`);
  }

  async createSession(opts = {}) {
    throw new Error(`createSession() not implemented on ${this.name}`);
  }

  async getMessages(sessionId, opts = {}) {
    throw new Error(`getMessages() not implemented on ${this.name}`);
  }

  async sendMessage(sessionId, payload = {}, opts = {}) {
    throw new Error(`sendMessage() not implemented on ${this.name}`);
  }

  async renameSession(sessionId, title) {
    return { id: sessionId, title };
  }

  async deleteSession(sessionId) {
    return { id: sessionId, deleted: true };
  }

  async listModels() {
    return [];
  }
}

// ==========================================
// OpenCode Adapter (HTTP Serve Mode)
// ==========================================

/**
 * Describe la FORMA de una respuesta que llego con 200 pero no es la que se esperaba.
 * Existe para que el log diga que paso de verdad. MEDIDO 2026-09-30: el mensaje era
 * "GET /api/model -> 200:" y durante dos dias no se pudo diagnosticar, porque no decia
 * nada de lo recibido y los WARNING antiguos ya habian rotado.
 */
export function describeRespuesta(r) {
  if (r && r.json && typeof r.json === "object") {
    const claves = Object.keys(r.json);
    const d = r.json.data;
    return `claves=${JSON.stringify(claves)} tipo(data)=${Array.isArray(d) ? "array" : typeof d}`;
  }
  if (r && typeof r.text === "string" && r.text) {
    return `sin JSON (${r.text.slice(0, 60).replace(/\s+/g, " ")})`;
  }
  return "cuerpo vacio";
}

export class OpencodeAdapter extends BaseProviderAdapter {
  constructor(options = {}) {
    super("opencode", "OpenCode", "serve");
    this.host = options.host || "127.0.0.1";
    this.port = options.port || 4096;
    this.getSystemContextBlock = options.getSystemContextBlock || (() => null);
    this._modelsCache = null;
    this._modelsCacheTime = 0;
    this._fetchingModels = false;
    // ---- F6 (transición OpenCode v1 → v2) -----------------------------------
    // La API v2 vive bajo /api/* y exige HTTP Basic opencode:<password>. La
    // contraseña rota en cada arranque del serve y se anota en opencode.log
    // ("server password ..."), así que se lee el final del log con TTL corto y
    // se invalida ante un401 para recapturar rotaciones sin reiniciar el hub.
    this.logPath = options.logPath || null;
    this._pw = null;
    this._pwTime = 0;
    this._pwFrom = null;            // de que fichero salio la password que se esta usando
    this._pwRechazadas = new Map(); // ruta -> instante en que el servidor la reboto con 401
    this._pwRechazadasTime = 0;
    // Fuentes de la password, POR FIABILIDAD y sobre todo por ORDEN. Cada una se
    // VALIDA contra el servidor antes de darla por buena: un candidate que se sabe
    // caducado se descarta solo, no el primero que se lee.
    //
    // MEDIDO 2026-09-30, contra el serve de verdad:
    //   1) /root/.local/state/opencode/service.json -> la escribe el PROPIO serve en
    //      cada arranque (lleva su `pid` y su `url`). Esta es la fuente de verdad.
    //   2) this.logPath -> el log del serve que lanzo este Hub. Bueno si el Hub lo
    //      lanzo; viejo si el serve lo relanzo otro.
    //   3) /root/.local/share/opencode/log/opencode.log -> log global: MEZCLA las
    //      lineas de `spawning process` con la de arranque del servicio (78
    //      coincidencias de "server password", casi todas falsas). Solo si no hay
    //      nada mejor.
    //   4) /root/.config/opencode/service.json -> espejo antiguo: NADIE actualiza su
    //      mtime cuando el serve arranca, asi que no dice nada de si la clave que
    //      contiene es la vigente. ULTIMO recurso, nunca la primera.
    this.passwordCandidates = options.passwordCandidates || [
      "/root/.local/state/opencode/service.json",
      ...(this.logPath ? [this.logPath] : []),
      "/root/.local/share/opencode/log/opencode.log",
      "/root/.config/opencode/service.json"
    ];
    this._lastModel = new Map();   // sessionId -> "providerID/id" ya activo
    this._instrHash = new Map();   // sessionId -> hash del contexto inyectado
    // Pre-warm models cache in background
    setTimeout(() => { this.listModels().catch(() => {}); }, 1500);
  }

  /**
   * Devuelve la password que el Hub va a usar, o null.
   *
   * NO es "el primero que existe": es "el primero que el servidor NO ha rechazado".
   * Un candidate se descarta solo en cuanto devuelve 401, y la lista entera se
   * reexplora pasado un minuto, porque una rotacion del serve vuelve a valer la
   * clave que un momento antes estaba caducada.
   */
  _readPassword() {
    const now = Date.now();
    if (this._pw && now - (this._pwTime || 0) < 3000) return this._pw;

    // La ventana de rechazo caduca: si el serve roto la clave, lo que hace un rato
    // era incorrecto puede ser justo lo que vale ahora.
    if (this._pwRechazadas.size && now - (this._pwRechazadasTime || 0) > 60000) {
      this._pwRechazadas.clear();
    }

    for (const ruta of this.passwordCandidates) {
      if (!ruta || this._pwRechazadas.has(ruta)) continue;
      if (!fs.existsSync(ruta)) continue;
      const pw = this._passwordFromFile(ruta);
      if (pw) { this._pw = pw; this._pwFrom = ruta; break; }
    }
    this._pwTime = now;
    return this._pw || null;
  }

  /** Extrae la password de un fichero, sea .json o log. null si no la hay. */
  _passwordFromFile(ruta) {
    try {
      if (ruta.endsWith(".json")) {
        const raw = JSON.parse(fs.readFileSync(ruta, "utf8"));
        if (raw && raw.password) return String(raw.password);
        return null;
      }
      const fd = fs.openSync(ruta, "r");
      try {
        const size = fs.fstatSync(fd).size;
        const len = Math.min(size, 8192);
        const buf = Buffer.alloc(len);
        fs.readSync(fd, buf, 0, len, Math.max(0, size - len));
        const matches = [...buf.toString("utf8").matchAll(/server password (\S+)/g)];
        return matches.length ? matches[matches.length - 1][1] : null;
      } finally { fs.closeSync(fd); }
    } catch (_) {
      return null;
    }
  }

  /** Cuantas candidatas siguen sin rechazo: eso define cuantos reintentos merece un 401. */
  get intentosAuthRestantes() {
    return Math.max(1, this.passwordCandidates.filter((r) => r && !this._pwRechazadas.has(r)).length);
  }

  _invalidatePassword(rutaRechazada) {
    if (rutaRechazada) {
      if (!this._pwRechazadas.size) this._pwRechazadasTime = Date.now();
      this._pwRechazadas.set(rutaRechazada, Date.now());
    }
    this._pw = null;
    this._pwTime = 0;
    this._pwFrom = null;
  }

  _authHeader() {
    const pw = this._readPassword();
    return pw ? { Authorization: `Basic ${Buffer.from(`opencode:${pw}`).toString("base64")}` } : {};
  }

  // Cliente HTTP genérico de la API v2: auth, timeout, abort del cliente y
  // reintentos ante rotación de contraseña (401 una sola vez).
  async _v2(pathname, { method = "GET", body = null, timeoutMs = 10000, signal = null, reintentos = 0 } = {}) {
    const headers = { ...(body ? { "Content-Type": "application/json" } : {}), ...this._authHeader() };
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), timeoutMs);
    const onAbort = () => ctrl.abort();
    if (signal) {
      if (signal.aborted) {
        clearTimeout(timer);
        throw new Error("OpenCode request aborted by client");
      }
      signal.addEventListener("abort", onAbort, { once: true });
    }
    let res;
    try {
      res = await fetch(`http://${this.host}:${this.port}${pathname}`, {
        method,
        headers,
        body: body ? JSON.stringify(body) : undefined,
        signal: ctrl.signal
      });
    } catch (e) {
      clearTimeout(timer);
      if (signal) signal.removeEventListener("abort", onAbort);
      if (signal && signal.aborted) throw new Error("OpenCode request aborted by client");
      // MEDIDO 2026-09-30: "This operation was aborted" salia mezclado con los fallos
      // de autenticacion en el mismo WARN, y son COSAS DISTINTAS. Este es un timeout
      // (o un serve caido), no un 401. Se dice cual de los dos para que el log no
      // haga pensar que la clave esta mal cuando lo que pasa es que no hay servidor.
      const porTimeout = e.name === "AbortError" || /abort/i.test(e.message || "");
      const porConexion = /ECONNREFUSED|ECONNRESET|fetch failed/i.test(e.message || "");
      const porque = porTimeout
        ? `timeout de ${timeoutMs} ms${porConexion ? " (el serve no esta escuchando: no es un problema de clave)" : ""}`
        : e.message;
      throw new Error(`OpenCode v2 ${method} ${pathname} failed: ${porque}`);
    }
    clearTimeout(timer);
    if (signal) signal.removeEventListener("abort", onAbort);
    if (res.status === 401) {
      // Se rechaza la CANDIDATA CONCRETA que se acaba de usar, no "la password".
      // Antes solo se invalidaba la cache y el reintento volvia a elegir la misma
      // primera de la lista, que era justo la caducada: con la primera candidata mala
      // no habia ninguna recuperacion posible, por muchas veces que se reintentara.
      //
      // Se invalida SIEMPRE, tambien en el ultimo intento: si no, la password que
      // acaba de fallar se queda cacheada 3 s y se vuelve a usar. MEDIDO: con tres
      // candidatas todas caducadas, `_pw` acababa valiendo la ultima. Precisamente
      // lo que este arreglo viene a evitar.
      const quedan = this.intentosAuthRestantes;   // se cuenta ANTES de invalidar
      const usada = this._pwFrom;
      this._invalidatePassword(usada);
      if (reintentos < quedan) {
        log.info(`[opencode] ${usada || "candidata"} rechazada por el servidor (401); pruebo la siguiente`);
        return this._v2(pathname, { method, body, timeoutMs, signal, reintentos: reintentos + 1 });
      }
      log.warn(`[opencode] ninguna de las ${quedan + reintentos} candidatas autentica en ${this.host}:${this.port}`);
    }
    const text = await res.text();
    let json = null;
    if (text) {
      try { json = JSON.parse(text); } catch (_) {}
    }
    if (!json && text && text.trimStart().startsWith("<")) {
      throw new Error("OpenCode devolvió HTML en API v2 (¿serve sin /api? versión no soportada)");
    }
    return { status: res.status, ok: res.ok, json, text };
  }

  async isHealthy() {
    try {
      const r = await this._v2("/api/info", { timeoutMs: 2500 });
      if (r.ok && r.json && r.json.version) return { up: true, healthy: true, version: String(r.json.version) };
      if (r.status === 401) return { up: true, healthy: false, version: null, error: "auth" };
      return { up: r.status < 500, healthy: false, version: null, error: `status ${r.status}` };
    } catch (_) {
      // Sin auth o caído: si la raíz sirve la SPA de OpenCode, el serve está vivo.
      try {
        const ctrl = new AbortController();
        const t = setTimeout(() => ctrl.abort(), 2000);
        const res = await fetch(`http://${this.host}:${this.port}/`, { signal: ctrl.signal });
        clearTimeout(t);
        const html = await res.text();
        const up = res.ok && html.includes("<title>OpenCode</title>");
        return { up, healthy: false, version: up ? "v2" : null, error: up ? "auth" : "down" };
      } catch (e) {
        return { up: false, healthy: false, error: e.message };
      }
    }
  }

  async listSessions() {
    // F6: GET /api/session (session.list) — devuelve {data:[Session.Info]} con
    // title real de OpenCode, projectID y location.directory (para el merge por carpeta).
    // F7: la API PAGINA (limit por defecto = las50 más recientes + cursor.next).
    // Con una sola llamada sólo llegaban las50 primeras →23 sesiones manuales
    // antiguas (Sep07–Sep23) no aparecían en el panel hasta que se creaba un chat
    // nuevo que "empujaba" el tope. Recorremos todas las páginas (200/pág., tope
    // de10 =2000 sesiones) con dedupe por id.
    const rawList = [];
    const seenIds = new Set();
    let cursor = null;
    for (let page = 0; page < 10; page++) {
      const qs = `?order=desc&limit=200${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`;
      const r = await this._v2(`/api/session${qs}`, { timeoutMs: 10000 });
      if (!r.ok || !r.json) throw new Error(`OpenCode v2 GET /api/session -> ${r.status}${r.text ? `: ${String(r.text).slice(0, 120)}` : ""}`);
      const payload = r.json;
      const batch = Array.isArray(payload) ? payload : (payload.sessions || payload.data || []);
      for (const s of batch) {
        const id = s && (s.id || s.ID);
        if (id && !seenIds.has(id)) { seenIds.add(id); rawList.push(s); }
      }
      cursor = (!Array.isArray(payload) && payload.cursor && payload.cursor.next) || null;
      if (!batch.length || !cursor) break;
    }
    // F6: el panel de Chats sólo muestra sesiones MANUALES. Las sub-sesiones de
    // OpenCode (parentID != null y sin fork explícito) las crean los sub-agentes
    // del harness (agent general/explore, p. ej. "Recon…", "F5 release…") y no
    // deben visualizarse; un fork explícito (campo fork) sí cuenta como manual.
    // El filtro vive en la FUENTE, así que cubre listAllSessions (panel Chats),
    // el overlay de títulos y el merge por carpeta de proyectos.
    return rawList
      .filter((s) => !(s.parentID && !s.fork))
      .map((s) => ({
      id: s.id || s.ID || "",
      title: s.title || s.name || s.id || "untitled",
      createdAt: s.time?.created
        ? new Date(s.time.created).toISOString()
        : (s.createdAt || s.created_at || new Date().toISOString()),
      updatedAt: s.time?.updated
        ? new Date(s.time.updated).toISOString()
        : (s.updatedAt || s.updated_at || s.createdAt || new Date().toISOString()),
      provider: "opencode",
      projectId: s.projectID || null,
      directory: s.location?.directory || null,
      raw: s
    }));
  }

  /**
   * Los agentes que OpenCode EXPONE de verdad. MEDIDO 2026-09-30: son 40 — los 7
   * internos (Build, General, Explore, Compaction, Title, Summary, Plan) mas
   * `orchestrator` y los 32 cargos de Kaenor. Se leen de la API, no de una lista
   * escrita a mano: una lista fija se queda vieja en cuanto se anade un cargo, que es
   * justo lo que pasaba con el modelo por defecto.
   *
   * Se devuelve TAL CUAL, sin filtrar ni reordenar en el Hub: el Hub es un espejo
   * fiel y decidir que agentes "interesan" esdecision de la app, no del proxy. Los
   * tres internos (Compaction, Title, Summary) tambien salen, con su `mode`, para que
   * quien lo vea pueda decir que sobran en vez de encontrarlos fantasma.
   */
  async listAgents() {
    const ahora = Date.now();
    if (this._agentsCache && (ahora - (this._agentsCacheTime || 0)) < 60000) {
      return this._agentsCache;
    }
    try {
      const r = await this._v2("/api/agent", { timeoutMs: 10000 });
      const raw = (r.ok && r.json && (Array.isArray(r.json.data) ? r.json.data : null)) || null;
      if (!raw) throw new Error(`/api/agent -> ${r.status}`);
      const lista = raw
        .filter((a) => a && a.name)
        .map((a) => ({
          name: String(a.name),
          mode: String(a.mode || "primary"),
          model: a.model ? String(a.model.id || a.model) : null,
          description: a.description ? String(a.description) : null,
          // `hidden` es la bandera del PROPIO OpenCode para no enseyar un agente en su
          // selector. MEDIDO 2026-09-30 en el registro crudo de los 6 primary:
          //
          //     orchestrator hidden=false   Build hidden=false   Plan hidden=false
          //     Compaction  hidden=true    Title   hidden=true   Summary hidden=true
          //
          // O sea que hidden=false y mode=primary da EXACTAMENTE los 3 que se pueden
          // cambiar a mano. Sin esta campo habia dos salidas y las dos malas: hardcodear
          // 3 nombres (se queda viejo en cuanto OpenCode anada uno) o deducirlo de que
          // tenga descripcion (los internos no la tienen, pero eso es casualidad, no
          // regla). El Hub lo devuelve tal cual y decide la app.
          hidden: a.hidden === true
        }));
      this._agentsCache = lista;
      this._agentsCacheTime = ahora;
      return lista;
    } catch (e) {
      log.warn("[opencode] listAgents error", { err: e.message });
      return this._agentsCache || [];
    }
  }

  /** El nombre de un agente existe de verdad? (para no mandar basura al serve) */
  async _agentExiste(nombre) {
    if (!nombre) return true;   // sin agente: que decida OpenCode
    const lista = await this.listAgents();
    if (!lista.length) return true;   // no se puede comprobar: no bloquear
    return lista.some((a) => a.name === nombre);
  }

  /**
   * Activa un modelo en una sesion. UNICO sitio que sabe hacerlo: lo usan el envio de
   * mensajes y la creacion de sesion. Estar en dos sitios es exactamente como los dos
   * acaban discrepando.
   *
   * OpenCode NO acepta modelo en el POST de creacion (solo {title}), asi que una
   * sesion nueva se modela llamando a este mismo metodo con el id recien creado.
   */
  async _switchSessionModel(sessionId, model, opts = {}, { fallbackToFirst = false } = {}) {
    if (!model || !sessionId) return null;
    let ref = await this._resolveModelRef(model);
    if (!ref && fallbackToFirst && this._modelRefs && this._modelRefs.size) {
      ref = this._modelRefs.values().next().value;
    }
    if (!ref) {
      log.warn("[opencode] modelo no resoluble, se deja el de la sesion", { model: String(model).slice(0, 120) });
      return null;
    }
    const key = `${ref.providerID}/${ref.id}`;
    if (this._lastModel.get(sessionId) === key) return key;
    const activar = (m) => this._v2(`/api/session/${encodeURIComponent(sessionId)}/model`, {
      method: "POST", body: { model: { id: m.id, providerID: m.providerID } }, timeoutMs: 8000, signal: opts.signal
    });
    let r = await activar(ref);
    if (!r.ok && ref.alt && ref.alt.id && ref.alt.id !== ref.id) r = await activar(ref.alt);
    if (!r.ok) throw new Error(`OpenCode no pudo activar el modelo ${key}: ${r.status}${r.text ? ` ${String(r.text).slice(0, 160)}` : ""}`);
    this._lastModel.set(sessionId, key);
    return key;
  }

  async createSession(opts = {}) {
    // F6: POST /api/session (session.create) — acepta {title} y responde {data:Session.Info}.
    const title = opts.title || `session:${Date.now().toString(36)}`;
    const r = await this._v2("/api/session", { method: "POST", body: { title }, timeoutMs: 8000 });
    const data = r.json && (r.json.data || r.json);
    if (!r.ok || !data || !data.id) {
      throw new Error(`Failed to create opencode session: ${r.status} ${r.text ? String(r.text).slice(0, 200) : ""}`.trim());
    }
    // El modelo inicial se fija DESPUES de crearla, porque el POST de creacion de
    // OpenCode solo acepta {title}. Es "mejor esfuerzo" a proposito: si el modelo no
    // se puede activar, la sesion sigue existiendo y el primer turno la resolvera. Que
    // la creacion fallara por un modelo seria peor que dejar que OpenCode elija.
    if (opts.model) {
      try {
        await this._switchSessionModel(data.id, opts.model, opts);
      } catch (e) {
        log.warn("[opencode] no se pudo fijar el modelo inicial de la sesion", { model: String(opts.model).slice(0, 120), err: e.message });
      }
    }
    return {
      id: data.id,
      title: data.title || title,
      createdAt: data.time?.created ? new Date(data.time.created).toISOString() : new Date().toISOString(),
      provider: "opencode",
      // Sin envoltorio {data}: el hub hace ...created.raw y un raw.envuelto
      // pisaría el envelope {ok,data} de la respuesta.
      raw: data
    };
  }

  async renameSession(sessionId, title) {
    // F6: PATCH /api/session/:id (session.update) — {title} y responde 204 sin body.
    const r = await this._v2(`/api/session/${encodeURIComponent(sessionId)}`, {
      method: "PATCH",
      body: { title },
      timeoutMs: 8000
    });
    if (!r.ok) throw new Error(`OpenCode rename failed: ${r.status}${r.text ? ` ${String(r.text).slice(0, 160)}` : ""}`);
    return { id: sessionId, title };
  }

  async deleteSession(sessionId) {
    const r = await this._v2(`/api/session/${encodeURIComponent(sessionId)}`, {
      method: "DELETE",
      timeoutMs: 8000
    });
    if (!r.ok && r.status !== 404) throw new Error(`OpenCode delete failed: ${r.status}${r.text ? ` ${String(r.text).slice(0, 160)}` : ""}`);
    return { id: sessionId, deleted: true };
  }

  // GET /api/session/:id (session.get) — metadatos puros (projectID, location,
  // agent, model). Usado por el hub para sellar el origen de una sesión.
  async getSessionMeta(sessionId) {
    const r = await this._v2(`/api/session/${encodeURIComponent(sessionId)}`, { timeoutMs: 6000 });
    if (!r.ok || !r.json) return null;
    return r.json.data || r.json || null;
  }

  // Convierte un mensaje v2 (type + content[]) al shape que entiende
  // normalizeMessage (role + parts[]) sin perder reasoning/tools.
  _mapV2Message(m, sessionId, idx) {
    const role = m.role || (m.type === "user" ? "user" : "assistant");
    let parts;
    if (Array.isArray(m.content) && m.content.length > 0) {
      parts = m.content.map((c, i) => ({
        id: c.id || `prt_${m.id || idx}_${i}`,
        type: c.type || "text",
        text: typeof c.text === "string" ? c.text : "",
        ...(c.tool ? { tool: c.tool } : {}),
        ...(c.state ? { state: c.state } : {}),
        ...(c.mime ? { mime: c.mime } : {})
      }));
    } else if (Array.isArray(m.parts) && m.parts.length > 0) {
      parts = m.parts;
    } else if (typeof m.text === "string") {
      parts = [{ id: `prt_${m.id || idx}_0`, type: "text", text: m.text }];
    }
    // F7: los adjuntos del usuario viven en m.files (top-level), no en
    // content[]/parts; sin este mapeo las imágenes/docs desaparecían del
    // historial al recargar la conversación (solo se veían en el optimista).
    if (Array.isArray(m.files) && m.files.length > 0) {
      const fileParts = m.files.map((f, i) => {
        const mime = f.mime || "application/octet-stream";
        const url = f.source && f.source.type === "uri" && f.source.uri
          ? f.source.uri
          : (f.data ? `data:${mime};base64,${f.data}` : null);
        return {
          id: `prt_${m.id || idx}_f${i}`,
          type: "file",
          text: "",
          ...(f.name ? { filename: f.name } : {}),
          mime,
          ...(url ? { url } : {})
        };
      });
      parts = parts ? [...parts, ...fileParts] : fileParts;
      if (!parts.some((p) => p.type === "text")) parts.unshift({ id: `prt_${m.id || idx}_t`, type: "text", text: typeof m.text === "string" ? m.text : "" });
    }
    return normalizeMessage(
      { ...m, role, ...(parts ? { parts } : {}), ...(typeof m.text === "string" ? { text: m.text } : {}) },
      sessionId,
      idx
    );
  }

  async _fetchMessagePage(sessionId, query, signal, timeoutMs = 15000) {
    const r = await this._v2(`/api/session/${encodeURIComponent(sessionId)}/message${query}`, { timeoutMs, signal });
    if (r.status === 404) return { data: [], cursor: null, notFound: true };
    if (!r.ok || !r.json) throw new Error(`OpenCode GET /message -> ${r.status}${r.text ? `: ${String(r.text).slice(0, 120)}` : ""}`);
    return { data: r.json.data || [], cursor: r.json.cursor || null };
  }

  async getMessages(sessionId, opts = {}) {
    // F6: session.message.list con paginación por cursor (order=asc sólo en la
    // primera página).404 = sesión inexistente en opencode (p.ej. id agy_) → []
    // para que getUnifiedMessages la ignore en lugar de propagar ruido.
    const all = [];
    let query = "?order=asc&limit=200";
    for (let page = 0; page < 15; page++) {
      const { data, cursor, notFound } = await this._fetchMessagePage(sessionId, query, opts.signal);
      if (notFound) break;
      all.push(...data);
      if (!cursor || !cursor.next) break;
      query = `?cursor=${encodeURIComponent(cursor.next)}&limit=200`;
    }
    // F6: v2 mezcla mensajes-evento en el historial (model-switched,
    // agent-switched, idle, ...). Sin este filtro se convertían en asistentes
    // vacíos = burbujas fantasma en la app. Turnos reales: user/assistant.
    return all
      .filter((m) => !m || !m.type || m.type === "user" || m.type === "assistant")
      .map((m, idx) => this._mapV2Message(m, sessionId, idx));
  }

  /**
   * La COLA del chat: los `limit` mensajes mas nuevos, en UNA pagina.
   *
   * Es la contraparte de getMessages, que recorre el historial entero con paginacion por
   * cursor. Aqui no hay cursor que seguir: order=desc con limit=200 devuelve directamente
   * el final. MEDIDO: 0,12 s y 934 KB, frente a ~15 s y 6,5 MB del recorrido completo.
   *
   * @returns mensajes en orden DESC (mas nuevo primero). El llamante los invierte.
   */
  async fetchMessageTail(sessionId, limit = 200, opts = {}) {
    const lim = Math.max(1, Math.min(200, Number(limit) || 200));
    const { data, notFound } = await this._fetchMessagePage(sessionId, `?order=desc&limit=${lim}`, opts.signal);
    if (notFound) return [];
    return (Array.isArray(data) ? data : [])
      .filter((m) => !m || !m.type || m.type === "user" || m.type === "assistant")
      .map((m, idx) => this._mapV2Message(m, sessionId, idx));
  }

  async sendMessage(sessionId, payload = {}, opts = {}) {
    // ==== F6: OpenCode v2 — POST /api/session/:id/prompt (session.prompt) ====
    // Contrato v2 estricto: el body SOLO admite {text} (additionalProperties:
    // false). Los archivos se inlinean en el texto, el contexto del hub va a las
    // instructions de la sesión, y modelo/agente se cambian por endpoints
    // separados (session.switchModel / session.switchAgent). El turno
    // asistente se espera por polling newest-first (la app además re-sincroniza).

    // 1) Texto final desde parts/text/prompt + archivos adjuntos
    const chunks = [];
    if (Array.isArray(payload.parts) && payload.parts.length > 0) {
      for (const p of payload.parts) if (p && typeof p.text === "string" && p.text) chunks.push(p.text);
    } else if (typeof payload.text === "string" && payload.text.trim()) {
      chunks.push(payload.text.trim());
    } else if (typeof payload.prompt === "string" && payload.prompt.trim()) {
      chunks.push(payload.prompt.trim());
    }
    if (Array.isArray(payload.files)) {
      for (const f of payload.files) {
        if (f.name) {
          let fileText = `\n[Attached File: ${f.name} (${f.mime || "application/octet-stream"})]`;
          if (f.base64) {
            try {
              const decoded = Buffer.from(f.base64, "base64").toString("utf8");
              if (/^[\x20-\x7E\s\n\r\t]+$/.test(decoded.slice(0, 500))) {
                fileText += `\n\`\`\`\n${decoded.slice(0, 8000)}\n\`\`\``;
              }
            } catch (_) {}
          }
          chunks.push(fileText);
        }
      }
    }
    // F7: adjuntos nativos v2 (PromptInput.FileAttachment {uri,name}). Antes los
    // parts tipo "file" (imágenes/videos/docs en data:...;base64) se DESCARTABAN
    // porque sólo se leía p.text → el modelo nunca recibía los adjuntos. Ahora se
    // reenvían tal cual en files[] del prompt (v2 los acepta como source inline);
    // los ficheros de texto siguen inlineados arriba para máxima compatibilidad.
    const v2Files = [];
    const pushV2File = (uri, name) => {
      if (typeof uri === "string" && /^(data|https?):/i.test(uri)) v2Files.push({ uri, name: name || "adjunto" });
    };
    if (Array.isArray(payload.parts)) {
      for (const p of payload.parts) if (p && p.type === "file") pushV2File(p.url, p.filename || p.name);
    }
    if (Array.isArray(payload.files)) {
      for (const f of payload.files) {
        if (!f) continue;
        if (f.url) pushV2File(f.url, f.name);
        else if (f.base64) pushV2File(`data:${f.mime || "application/octet-stream"};base64,${f.base64}`, f.name);
      }
    }

    let text = chunks.join("\n").trim();
    if (!text && v2Files.length === 0) throw new Error("OpenCode prompt rechazado: mensaje vacío");
    if (!text) text = "[Mensaje solo con adjuntos]";

    // 2) Contexto del hub -> instructions persistentes de la sesión (best-effort).
    //    v2 ya no acepta {system} en el prompt.
    const projectId = payload.projectId || opts.projectId || null;
    const block = (typeof payload.system === "string" && payload.system.trim()) || this.getSystemContextBlock(projectId);
    if (block && block.trim()) await this._setSessionInstruction(sessionId, "aegis-context", block.trim());

    // 1b) Modelo por defecto si no viene especificado
    // Sin modelo forzado. Antes, si la peticion no traia modelo, se ponia
    // "google/antigravity-gemini-3.8-flash" a mano: con eso, TODOS los turnos de
    // OpenCode acababan en un modelo de Antigravity en concreto, y si ese no estaba
    // en el indice se caia a "antigravity-gemini-3-flash". Ahora, si no hay modelo, lo
    // elige el Hub/OpenCode; los ids antigravity-* siguen siendo modelos validos de
    // OpenCode (los aporta el plugin) y el usuario puede elegir el que quiera.
    const effectiveModel = payload.model || null;
    if (effectiveModel) {
      // `fallbackToFirst` solo cuando el cliente NO pidio modelo: si lo pidio y no se
      // resuelve, se avisa del id raro en vez de colarse en otro a espaldas del usuario.
      await this._switchSessionModel(sessionId, effectiveModel, opts, { fallbackToFirst: !payload.model });
    }

    // 4) Agente de la sesion — POST /session/:id/agent (mejor esfuerzo)
    //
    // MEDIDO 2026-09-30: esto estaba limitado a `["plan", "build"]`, asi que CUALQUIER
    // otro agente —empezando por `orchestrator` y los 32 cargos— se descartaba EN
    // SILENCIO. No habia conexion directa con los agentes de OpenCode: habia una
    // puerta que solo dejaba pasar a dos.
    //
    // MEDIDO que el serve los acepta de verdad: `POST /api/session/:id/agent` con
    // `{"agent":"orchestrator"}` responde 204. Y `GET /api/agent` lo lista con
    // mode=primary y un modelo asignado. La restriccion era nuestra, no del serve.
    const agentMode = payload.agent || payload.mode || opts.agent || opts.mode || null;
    if (agentMode && await this._agentExiste(String(agentMode))) {
      try {
        const r = await this._v2(`/api/session/${encodeURIComponent(sessionId)}/agent`, {
          method: "POST", body: { agent: String(agentMode) }, timeoutMs: 6000, signal: opts.signal
        });
        if (!r.ok && r.status !== 404) log.warn("[opencode] switchAgent failed", { agent: String(agentMode), status: r.status });
      } catch (e) {
        log.warn("[opencode] switchAgent error", { agent: String(agentMode), err: e.message });
      }
    }

    // 5) Prompt — encola en el inbox de la sesión (200 -> {data:{id,time}}).
    //    F7: si hay adjuntos nativos se envían en files[] (v2 los normaliza a
    //    source inline). Si v2 los rechaza (4xx), se reintenta SIN adjuntos para
    //    que el texto del mensaje no se pierda (fallback degradado + warn).
    let sentFiles = v2Files.length > 0;
    let ack = null;
    let droppedFiles = false;
    for (let attempt = 0; attempt < 3; attempt++) {
      const body = sentFiles ? { text, files: v2Files } : { text };
      const r = await this._v2(`/api/session/${encodeURIComponent(sessionId)}/prompt`, {
        method: "POST", body, timeoutMs: 30000, signal: opts.signal
      });
      if (r.ok && r.json && r.json.data) { ack = r.json.data; break; }
      if (r.status === 409 && attempt < 2) { await new Promise((res) => setTimeout(res, 1500)); continue; }
      if (sentFiles && r.status >= 400 && r.status < 500) {
        log.warn(`[opencode] v2 rechazó adjuntos (${r.status}); reenvío solo texto`, { files: v2Files.length });
        sentFiles = false;
        droppedFiles = true;
        attempt = -1; // reinicia el ciclo: reintento con texto solo + reintento 409
        continue;
      }
      const msg = (r.json && (r.json.message || r.json.error)) || `HTTP ${r.status}`;
      throw new Error(`OpenCode prompt rechazado: ${msg}`);
    }
    if (!ack) throw new Error("OpenCode prompt rechazado: sin confirmación (409 persistente)");
    if (droppedFiles) log.warn(`[opencode] adjuntos descartados para ${sessionId}; el modelo NO los recibió`);
    const cutoff = ack.time?.created || Date.now();
    log.info(`[opencode] prompt accepted for ${sessionId} (msg ${ack.id || "?"}), esperando turno asistente...`);

    // 6) Espera del asistente: página newest-first; completo cuando trae
    //    time.streamed (turno cerrado) o cuando el texto lleva varios polls estable.
    //
    //    AEGIS_TURN_TIMEOUT_MS: un turno agéntico real (el modelo invoca
    //    herramientas, lee ficheros, ejecuta comandos) tarda con frecuencia
    //    minutos. Con el deadline fijo de 70s el Hub abortaba con "no respondió
    //    en 70s" y la app Aegis mostraba error aunque la respuesta llegara
    //    segundos después — el usuario perceived como "no me puede enviar".
    //    Default 10 min; ajustable por env para tests o dispositivos lentos.
    const _turnTimeoutRaw = Number(process.env.AEGIS_TURN_TIMEOUT_MS);
    const TURN_TIMEOUT_MS = _turnTimeoutRaw > 0 ? _turnTimeoutRaw : 600000;
    // Estabilidad: durante las tool calls el texto del asistente puede quedarse
    // quieto más de 1s, así que "estable en 2 polls" devolvía una respuesta
    // PARCIAL antes de tiempo. Exigimos 3 polls consecutivos y damos prioridad
    // a time.streamed, que es el cierre real del turno según OpenCode.
    const STABLE_POLLS_REQUIRED = 3;
    const deadline = Date.now() + TURN_TIMEOUT_MS;
    let stableKey = null;
    let stableCount = 0;
    // MEDIDO 2026-10-01: el texto se emitia UNA vez, al final, y por eso Aegis lo pintaba
    // de golpe. Este es el ultimo texto ya emitido, para no repetirlo en cada poll.
    let ultimoTexto = "";
    const emitir = (txt) => {
      if (typeof opts.onChunk !== "function" || !txt || txt === ultimoTexto) return;
      ultimoTexto = txt;
      try { opts.onChunk(txt); } catch (_) {}
    };
    while (Date.now() < deadline) {
      if (opts.signal?.aborted) throw new Error("OpenCode sendMessage aborted by client");
      await new Promise((res) => setTimeout(res, 1000));
      if (opts.signal?.aborted) throw new Error("OpenCode sendMessage aborted by client");
      try {
        const page = await this._fetchMessagePage(sessionId, "?order=desc&limit=20", opts.signal, 10000);
        let hit = null;
        let hitText = "";
        for (const m of page.data || []) {
          const ts = m.time?.created || 0;
          const isAssistant = !m.type || m.type === "assistant";
          if (!isAssistant || ts < cutoff) continue;
          if (m.error) {
            const errMsg = m.error.message || JSON.stringify(m.error);
            throw new Error(`OpenCode assistant error: ${errMsg}`);
          }
          const txt = Array.isArray(m.content)
            ? m.content.filter((c) => c && c.type === "text" && c.text).map((c) => c.text).join("")
            : (typeof m.text === "string" ? m.text : "");
          if (!txt.trim()) continue;
          hit = m; hitText = txt; break;
        }
        if (hit) {
          // El texto CRECIO desde el ultimo poll: se emite el acumulado. Es lo que convierte
          // la respuesta en un bloque en una progresion, que es lo que pedia el usuario.
          if (hitText.length > ultimoTexto.length) emitir(hitText);
          const key = `${hit.id}:${hitText.length}`;
          const streamed = !!(hit.time && hit.time.streamed);
          if (key === stableKey) stableCount += 1;
          else { stableKey = key; stableCount = 1; }
          const complete = streamed || stableCount >= STABLE_POLLS_REQUIRED;
          if (complete) {
            const normalized = this._mapV2Message(hit, sessionId, 0);
            emitir(normalized.text);
            log.info(`[opencode] assistant turn completed for ${sessionId} (${normalized.text.length} chars)`);
            return normalized;
          }
        } else {
          stableKey = null;
          stableCount = 0;
        }
      } catch (e) {
        if (opts.signal?.aborted) throw new Error("OpenCode sendMessage aborted by client");
        // fallo transitorio del poll — se reintenta en la siguiente vuelta
      }
    }
    throw new Error(`OpenCode no respondió en ${Math.round(TURN_TIMEOUT_MS / 1000)}s (timeout del turno; ajustable con AEGIS_TURN_TIMEOUT_MS)`);
  }

  // F6: GET /api/model (model.list) — lista REAL de v2 (opencode, google,
  // openrouter) con cost por modelo. Orden pedido por el usuario:
  //   rango 0: gratis OpenCode Zen      rango 1: gratis resto (openrouter/otros)
  //   rango 2: pago  OpenCode Zen       rango 3: pago / API-key / privados
  // sort estable => dentro de cada rango se conserva el orden de la API.
  async listModels() {
    const now = Date.now();
    if (this._modelsCache && (now - (this._modelsCacheTime || 0) < 600000) && this._modelsCache.length > 0) {
      return this._modelsCache;
    }
    if (this._fetchingModels) {
      if (this._modelsCache && this._modelsCache.length > 0) return this._modelsCache;
      return this._fallbackModels();
    }
    this._fetchingModels = true;
    try {
      const r = await this._v2("/api/model", { timeoutMs: 12000 });
      if (r.ok && r.json && Array.isArray(r.json.data)) {
        const raw = r.json.data.filter((m) => m && m.enabled !== false && (m.id || m.modelID));
        // Índice de alias -> Model.Ref para session.switchModel
        const refIdx = new Map();
        for (const m of raw) {
          const primary = String(m.modelID || m.id);
          const secondary = String(m.id || m.modelID);
          const ref = {
            id: primary,
            providerID: String(m.providerID || "opencode"),
            ...(secondary !== primary ? { alt: { id: secondary } } : {})
          };
          for (const alias of new Set([m.id, m.modelID, `${m.providerID}/${m.modelID}`, `${m.providerID}/${m.id}`].filter(Boolean))) {
            refIdx.set(String(alias), ref);
          }
        }
        this._modelRefs = refIdx;

        const isFree = (m) => {
          const costs = Array.isArray(m.cost) ? m.cost : (m.cost ? [m.cost] : []);
          if (costs.length > 0 && costs.every((c) => c && c.input === 0 && c.output === 0)) return true;
          const id = String(m.id || "").toLowerCase();
          return id.endsWith(":free") || id.endsWith("-free");
        };
        const rank = (m) => {
          const free = isFree(m);
          if (free) return m.providerID === "opencode" ? 0 : 1;
          return m.providerID === "opencode" ? 2 : 3;
        };
        const label = { opencode: "OpenCode Zen", google: "Google AI (API key)", openrouter: "OpenRouter" };
        const pairs = raw.map((m) => ({
          m,
          disp: {
            id: String(m.id || m.modelID),
            name: String(m.name || m.modelID || m.id),
            description: `${label[m.providerID] || m.providerID} · ${m.family || "AI"}${isFree(m) ? " · Gratis" : ""}`,
            provider: String(m.providerID || "opencode"),
            free: isFree(m)
          }
        }));
        pairs.sort((a, b) => rank(a.m) - rank(b.m));
        if (pairs.length > 0) {
          this._modelsCache = pairs.map((p) => p.disp);
          this._modelsCacheTime = now;
          return this._modelsCache;
        }
      }
      // MEDIDO 2026-09-30: este mensaje salia como "GET /api/model -> 200:" y no
      // decia NADA de que habia llegado. Un 200 con el cuerpo que no toca NO es un
      // fallo de autenticacion — la autenticacion ya paso, porque si no habria 401— y
      // durante dos dias no se pudo decir de que era porque el mensaje no lo decia y
      // los WARNING viejos ya habian rotado. Ahora dice la FORMA de lo recibido, que
      // es lo unico que hace falta para diagnosticarlo.
      throw new Error(`GET /api/model -> ${r.status} con cuerpo inesperado: ${describeRespuesta(r)}${r.text ? ` :: ${String(r.text).slice(0, 100)}` : ""}`);
    } catch (e) {
      log.warn("[opencode] listModels fetch error", { err: e.message });
    } finally {
      this._fetchingModels = false;
    }
    return (this._modelsCache && this._modelsCache.length > 0) ? this._modelsCache : this._fallbackModels();
  }

  async _resolveModelRef(model) {
    if (model && typeof model === "object") {
      const id = model.modelID || model.id;
      if (!id) return null;
      return { id: String(id), providerID: String(model.providerID || "opencode") };
    }
    const key = String(model || "").trim();
    if (!key) return null;
    if (!this._modelRefs || this._modelRefs.size === 0) {
      try { await this.listModels(); } catch (_) {}
    }
    const ref = this._modelRefs ? this._modelRefs.get(key) : null;
    if (!ref) log.warn("[opencode] modelo no encontrado en el índice v2 (se mantiene el de la sesión)", { model: key });
    return ref || null;
  }

  async _setSessionInstruction(sessionId, key, value) {
    try {
      const h = `${value.length}:${Buffer.from(value).toString("base64").slice(0, 48)}`;
      if (this._instrHash.get(sessionId) === h) return;
      const r = await this._v2(
        `/api/experimental/session/${encodeURIComponent(sessionId)}/instructions/entries/${encodeURIComponent(key)}`,
        { method: "PUT", body: { value }, timeoutMs: 6000 }
      );
      if (r.ok) this._instrHash.set(sessionId, h);
      else log.warn("[opencode] instruction PUT failed", { status: r.status });
    } catch (e) {
      log.warn("[opencode] instruction PUT error", { err: e.message });
    }
  }

  _fallbackModels() {
    // Fallback honesto: models free reales de Zen, sólo con opencode caído.
    return [
      { id: "space-bunny-free", name: "Space Bunny Free", description: "OpenCode Zen · AI · Gratis (fallback)" },
      { id: "mimo-v2.6-flash-free", name: "Mimo v2.6 Flash Free", description: "OpenCode Zen · AI · Gratis (fallback)" },
      { id: "muse-spark-1.3-contributor-free", name: "Muse Spark 1.3 Contributor Free", description: "OpenCode Zen · AI · Gratis (fallback)" },
      { id: "ling-3.0-flash-fin-free", name: "Ling 3.0 Flash Fin Free", description: "OpenCode Zen · AI · Gratis (fallback)" },
      { id: "nemotron-3.5-lightning-free", name: "Nemotron 3.5 Lightning Free", description: "OpenCode Zen · AI · Gratis (fallback)" },
      { id: "nemotron-3-ultra-free", name: "Nemotron 3 Ultra Free", description: "OpenCode Zen · AI · Gratis (fallback)" }
    ];
  }
}

// ==========================================
// Provider Manager
// ==========================================

export class ProviderManager {
  constructor(configFilePath = "/sdcard/projects/Aegis/backend/providers.json") {
    this.configFilePath = configFilePath;
    this.adapters = new Map();
    this.defaultProvider = "opencode";
    this.loadConfig();
  }

  loadConfig() {
    try {
      const raw = atomicReadFileSync(this.configFilePath, null);
      if (raw && raw.defaultProvider) {
        this.defaultProvider = raw.defaultProvider;
      }
    } catch (e) {
      log.error("[provider-mgr] loadConfig err", { err: e.message });
    }
  }

  async saveConfig(mutatorFn) {
    return fileMutex.runExclusive(this.configFilePath, async () => {
      const current = atomicReadFileSync(this.configFilePath, {
        defaultProvider: this.defaultProvider,
        providers: []
      });
      const updated = mutatorFn ? mutatorFn(current) : current;
      if (updated.defaultProvider) {
        this.defaultProvider = updated.defaultProvider;
      }
      atomicWriteFileSync(this.configFilePath, updated);
      return updated;
    });
  }

  register(adapter) {
    this.adapters.set(adapter.id, adapter);
  }

  get(id) {
    return this.adapters.get(id) || this.adapters.get(this.defaultProvider);
  }

  listProviders() {
    const list = [];
    for (const [id, a] of this.adapters.entries()) {
      list.push({
        id: a.id,
        name: a.name,
        type: a.type,
        isDefault: a.id === this.defaultProvider
      });
    }
    return list;
  }

  // F6: el proveedor de nacimiento de una sesión es INAMOVIBLE. Orden:
  // 1) vínculo persistente en projects.json (reparado en caliente por prefijo),
  // 2) convención del id (ses_ -> opencode),
  // 3) header explícito (sólo para ids sin convención conocida),
  // 4) heurística brain-dir y default.
  // Así, cambiar el pill de modelo en la app NUNCA re-bindea ("borra") la sesión.
  static _conventionProvider(sessionId) {
    if (typeof sessionId !== "string") return null;
    if (sessionId.startsWith("ses_")) return "opencode";
    return null;
  }

  resolveProvider(sessionId, explicitProvider = null, projectsStore = null) {
    const convention = ProviderManager._conventionProvider(sessionId);

    if (sessionId && projectsStore && Array.isArray(projectsStore.projects)) {
      for (const p of projectsStore.projects) {
        const sess = (p.sessions || []).find((s) => s.sessionId === sessionId);
        if (sess) {
          let provId = String(sess.provider || p.provider || this.defaultProvider || "").toLowerCase();
          // Reparación en caliente: si el registro quedó corrompido por un
          // header X-Provider, el prefijo del id es la autoridad.
          if (convention && provId !== convention) provId = convention;
          if (this.adapters.has(provId)) return this.adapters.get(provId);
          if (convention && this.adapters.has(convention)) return this.adapters.get(convention);
        }
      }
    }

    if (convention && this.adapters.has(convention)) return this.adapters.get(convention);

    if (explicitProvider && this.adapters.has(explicitProvider.toLowerCase())) {
      return this.adapters.get(explicitProvider.toLowerCase());
    }

    return this.adapters.get(this.defaultProvider);
  }

  // Historial unificado. Antes fusionaba OpenCode + Antigravity para no perder
  // contexto al cambiar de motor; con un solo motor la fusion es solo OpenCode, pero
  // se conserva el nombre y el contrato porque el endpoint de mensajes lo llama.
  async getUnifiedMessages(sessionId, opts = {}) {
    let ocMsgs = [];

    const oc = this.adapters.get("opencode");
    if (oc) {
      try {
        ocMsgs = await oc.getMessages(sessionId, opts);
      } catch (e) {
        // Un fallo aqui se traducía en "0 mensajes" sin dejar rastro, que en la app es
        // un historial vacio sin ninguna explicacion. Se loguea; NO se propaga, porque
        // un 404 (sesion inexistente) es normal y esta funcion existe justo para no
        // propagar ese ruido. Lo que no puede es desaparecer.
        log.warn(`[unified] opencode fallo leyendo ${sessionId}: ${e?.message || e}`);
      }
    }

    return ocMsgs;
  }

  // Unified session listing across all active providers
  async listAllSessions() {
    const all = [];
    const seen = new Set();

    for (const [_, adapter] of this.adapters.entries()) {
      try {
        const list = await adapter.listSessions();
        for (const s of list) {
          if (!seen.has(s.id)) {
            seen.add(s.id);
            all.push(s);
          }
        }
      } catch (e) {
        log.warn(`[provider-mgr] listSessions error for ${adapter.id}`, { err: e.message });
      }
    }

    all.sort((a, b) => (b.updatedAt || b.createdAt || "").localeCompare(a.updatedAt || a.createdAt || ""));
    return all;
  }
}
