// regression-correcciones.test.js — los errores que la auditoría de 2026-09-29 confirmó
// en ejecución o por lectura, fijados para que no vuelvan.
//
// Cada test existe porque hubo un fallo REAL, y el nombre lo dice. Sin estos, una
// suite en verde convive tranquilamente con cuatro mentiras: eso es exactamente lo
// que pasó con `workflow-dag.test.js`, que pasaba porque su agente mock NUNCA fallaba
// y por eso no podía cazar la ruta de fallo del motor.
//
// Nada de esto necesita un Hub vivo ni root: o se comprueba la función, o se
// comprueba que la ruta RECHAZA (que es lo que importa de una validación).

import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const BACKEND = path.join(import.meta.dirname, "..");
const SERVER = path.join(BACKEND, "server.js");

// --- helpers: leer el fuente, para fijar el COMPORTAMIENTO observable de una ruta ---
const src = fs.readFileSync(SERVER, "utf8");
const providersSrc = fs.readFileSync(path.join(BACKEND, "providers.js"), "utf8");

// Un test que busca en el fuente NO debe pillar los COMENTARIOS: varios de estos
// cambios llevan un comentario que explica precisamente lo que se retiró, y sin
// esto el test se dispara contra su propia documentación.
const code = (s) => s
  .split("\n")
  .map(l => l.replace(/^\s*(\/\/|\*|\/\*).*$/, ""))
  .join("\n");

// ═══════════════════════════════════════════════════════════════════════════
// H1 · Inyección de comandos como root en /api/assistant/*
// Confirmada EN EJECUCIÓN: `safe` escapaba comillas simples pero el valor se
// interpolaba dentro de comillas dobles de un comando que corre vía `su -c`, y
// ni siquiera pasaba por hasShellMeta. Se reprodujo el código real con un
// payload que solo hacía `echo` y el comando inyectado se ejecutó.
// ═══════════════════════════════════════════════════════════════════════════

test("H1: queryContacts NO construye un comando de shell (era la vía de RCE)", () => {
  // La función debe seguir existiendo (la voz la usa) pero sin `content query`:
  // esa era la única ruta de texto libre al shell con privilegios de root.
  const at = src.indexOf("async function queryContacts");
  const body = code(src.slice(at, src.indexOf("\n  }", at)));
  assert.ok(body.length > 0, "queryContacts debe seguir existiendo para no romper la voz");
  assert.ok(!/content query/.test(body),
    "queryContacts no debe construir un comando `content query` con el nombre interpolado");
  assert.ok(!/LIKE '%\$\{/.test(body),
    "no debe interpolar texto libre dentro de las comillas dobles del comando");
  assert.ok(!/\.replace\(\/'\/g/.test(body),
    "escapar comillas simples no era el problema: no debe quedar el parche como si lo fuera");
});

test("H1: la denylist de shell cubre redirecciones, & suelto y ${}", () => {
  const m = src.match(/const SHELL_META_RE\s*=\s*(\/.*\/[a-z]*;)/);
  assert.ok(m, "debe seguir declarándose SHELL_META_RE");
  const re = new RegExp(m[1].replace(/^\//, "").replace(/\/[a-z]*;$/, ""));
  for (const ch of [";", "|", "`", "&&", "\n", "$(", "${", ">", "<", "&"]) {
    assert.ok(re.test(`x${ch}y`), `SHELL_META_RE debe rechazar ${JSON.stringify(ch)}`);
  }
  assert.ok(!re.test("com.ejemplo.app"), "una cadena limpia no debe rechazarse");
});

test("H1: la allowlist de paquete Android está y se usa en /api/device/launch", () => {
  assert.ok(/const PACKAGE_RE = \/\^\[A-Za-z\]/.test(src), "debe existir PACKAGE_RE");
  const launch = src.slice(src.indexOf('pathname==="/api/device/launch"'));
  assert.ok(/isValidPackageName\(String\(pkg\)\)/.test(launch.slice(0, 1200)),
    "/api/device/launch debe validar el paquete con la allowlist, no solo con la denylist");
  const re = new RegExp(src.match(/const PACKAGE_RE = (\/\^\[A-Za-z\][^;]*;)/)[1].replace(/^\//, "").replace(/\/;$/, ""));
  assert.ok(re.test("com.aegis.hub"), "com.aegis.app es válido");
  assert.ok(!re.test("com.aegis.hub > /sdcard/pwned"), "una redirección no puede ser un paquete");
  assert.ok(!re.test("com.aegis.hub;id"), "un `;` no puede ser un paquete");
});

test("H1: la uri de WhatsApp no puede romper las comillas simples del comando", () => {
  // encodeURIComponent NO escapa `'` (está en su lista de seguros) y la uri iba
  // entre comillas simples, así que un texto con `'` inyectaba un comando.
  const ws = src.slice(src.indexOf('case "whatsapp_send":'), src.indexOf('case "whatsapp_send":') + 1400);
  assert.ok(/\.replace\(\/'\/g, "%27"\)/.test(ws), "la comilla simple debe ir a %27");
  assert.ok(!/runShell\(`am start[^`]*-d '\$\{uri\}'/.test(ws),
    "la uri no debe ir entre comillas simples en un comando de shell");
});

// ═══════════════════════════════════════════════════════════════════════════
// H2 · workflowEngine no propagaba el fallo del agente (verificado con sonda:
// un agente que devuelve {ok:false} daba status "completed" y progress 100)
// ═══════════════════════════════════════════════════════════════════════════

test("H2: el motor de workflows trata {ok:false} como fallo, no como éxito", async () => {
  const { WorkflowEngine } = await import("../src/core/workflowEngine.js");
  const { agentPool } = await import("../src/core/agentPool.js");

  // Un agente que FALLA devolviendo {ok:false}, que es como fallan los reales.
  class FailingAgent {
    constructor(projectId) { this.projectId = projectId; this.id = "probe_failing"; }
    async execute() { return { ok: false, error: "fallo simulado" }; }
  }
  agentPool.register("FailingAgent", FailingAgent);

  const engine = new WorkflowEngine();
  engine.parser = { parse: () => ({ steps: [{ id: "s1", agent: "FailingAgent" }] }) };

  const res = await engine.run("test-fail", "prj-fail", "probe");
  const st = engine.getWorkflowStatus("prj-fail");
  assert.equal(res.ok, false, "run() no debe decir ok:true con un agente que falla");
  assert.equal(st.status, "failed", `el estado debe ser "failed", no "${st.status}"`);
  assert.equal(st.stepStatuses.s1, "failed", "el paso debe quedar como fallido");
});

test("H2: un agente que TIENE éxito sigue marcando completado (no se rompió)", async () => {
  const { WorkflowEngine } = await import("../src/core/workflowEngine.js");
  const { agentPool } = await import("../src/core/agentPool.js");
  class OkAgent {
    constructor(projectId) { this.projectId = projectId; this.id = "probe_ok"; }
    async execute() { return { ok: true }; }
  }
  agentPool.register("OkAgent", OkAgent);
  const engine = new WorkflowEngine();
  engine.parser = { parse: () => ({ steps: [{ id: "s1", agent: "OkAgent" }] }) };
  const res = await engine.run("test-ok", "prj-ok", "probe");
  assert.equal(res.ok, true);
  assert.equal(engine.getWorkflowStatus("prj-ok").status, "completed");
});

// ═══════════════════════════════════════════════════════════════════════════
// H6 · funciones que decían haber hecho algo que no hicieron
// ═══════════════════════════════════════════════════════════════════════════

test("H6: cancelAll NO devuelve 'All agents cancelled' (no cancela nada)", async () => {
  const { AgentPool } = await import("../src/core/agentPool.js");
  const pool = new AgentPool();
  const r = pool.cancelAll("prj-x");
  const msg = String(r.message || "").toLowerCase();
  assert.ok(!msg.includes("all agents cancelled"),
    "la respuesta no debe prometer una cancelación que el código no puede hacer");
  assert.ok("stillRunning" in r, "debe informar de cuántos siguen en marcha");
});

test("H6: ResearchAgent usa una orden de graphify que EXISTE", async () => {
  const { ResearchAgent } = await import("../src/agents/ResearchAgent.js");
  const inst = Object.create(ResearchAgent.prototype);
  let invocado = null;
  inst.invoker = { invoke: async (skill, args) => { invocado = { skill, args }; return { ok: false, error: "x" }; } };
  inst.projectId = "prj-y"; inst.name = "ResearchAgent";
  inst.emit = () => {}; inst.status = "idle";
  const r = await inst.execute({});
  assert.ok(invocado, "debe invocar una skill");
  assert.ok(!invocado.args.includes("--markdown"),
    'graphify NO tiene el flag "--markdown": verificado con la CLI, da "unknown command"');
  assert.equal(r.ok, false, "si la skill falla, el agente no debe devolver ok:true");
});

test("H6: ArchitectAgent no escribe una 'arquitectura' que solo cuenta caracteres", async () => {
  const { ArchitectAgent } = await import("../src/agents/ArchitectAgent.js");
  const inst = Object.create(ArchitectAgent.prototype);
  inst.projectId = "prj-z"; inst.name = "ArchitectAgent";
  inst.readArtifact = () => null;               // sin entradas
  inst.writeArtifact = () => { throw new Error("no debería escribir"); };
  inst.emit = () => {}; inst.status = "idle";
  const r = await inst.execute({});
  assert.equal(r.ok, false, "sin REQ.md ni RESEARCH.md no puede producir nada: debe fallar");
  assert.match(String(r.error), /faltan artefactos/);
});

// ═══════════════════════════════════════════════════════════════════════════
// H10 · SkillInvoker acumulaba stdout sin techo
// ═══════════════════════════════════════════════════════════════════════════

test("H10: SkillInvoker no acumula salida sin límite y lo declara", () => {
  const s = fs.readFileSync(path.join(BACKEND, "src/skills/SkillInvoker.js"), "utf8");
  assert.ok(/MAX_OUT/.test(s), "debe existir un tope de salida acumulada");
  assert.ok(!/p\.stdout\.on\("data", c => stdout \+= c\)/.test(s),
    "el acumulador sin techo es exactamente el bug: stdout += c debe desaparecer");
  assert.ok(/truncated/.test(s), "el corte debe REPORTARSE, no truncar en silencio");
});

// ═══════════════════════════════════════════════════════════════════════════
// H3 · /api/models devolvía los modelos de otro motor cuando no se pedía provider
// ═══════════════════════════════════════════════════════════════════════════

test("H3: /api/models sin `provider` devuelve los de OpenCode, no los de otro motor", () => {
  const i = src.indexOf('pathname === "/api/opencode/models"');
  const blk = code(src.slice(i, i + 900));
  assert.ok(blk.length > 0, "debe existir la ruta de modelos");
  assert.ok(!/antigravity/i.test(blk),
    "sin parámetro provider la ruta debe devolver SIEMPRE la lista de OpenCode");
  assert.ok(/opencodeAdapter\.listModels\(\)/.test(blk));
});

// ═══════════════════════════════════════════════════════════════════════════
// A3 · dos fuentes de verdad para PROJECTS_ROOT
// ═══════════════════════════════════════════════════════════════════════════

test("A3: pathResolver lee el mismo PROJECTS_ROOT que server.js", async () => {
  const saved = process.env.PROJECTS_ROOT;
  process.env.PROJECTS_ROOT = "/tmp/aegis-test-root-xyz";
  try {
    // Reimportar con cache busted: el módulo lee el env al cargarse.
    const mod = await import(`../src/core/pathResolver.js?v=${Date.now()}`);
    assert.equal(mod.WORKSPACE_ROOT, "/tmp/aegis-test-root-xyz",
      "WORKSPACE_ROOT debe seguir a PROJECTS_ROOT, no a un literal");
    assert.equal(mod.getProjectAbsPath("demo"), "/tmp/aegis-test-root-xyz/demo");
  } finally {
    if (saved === undefined) delete process.env.PROJECTS_ROOT; else process.env.PROJECTS_ROOT = saved;
  }
});

test("A3: no queda un literal /sdcard/projects en pathResolver", () => {
  const s = fs.readFileSync(path.join(BACKEND, "src/core/pathResolver.js"), "utf8");
  assert.ok(!/WORKSPACE_ROOT\s*=\s*"\/sdcard\/projects"/.test(s),
    "el literal es lo que hizo que la mitad del sistema ignorase el env");
});

// ═══════════════════════════════════════════════════════════════════════════
// Retirada de Antigravity: que no vuelva por la puerta de atrás
// ═══════════════════════════════════════════════════════════════════════════

test("Antigravity: el Hub ya no registra ningún adapter suyo", () => {
  assert.ok(!/new AntigravityAdapter/.test(src), "AntigravityAdapter no debe instanciarse");
  assert.ok(!/new ClaudeCodeAdapter/.test(src), "ClaudeCodeAdapter (stub vacío) no debe instanciarse");
  const mgr = code(providersSrc.slice(providersSrc.indexOf("class ProviderManager")));
  assert.ok(!/antigravity/i.test(mgr), "ProviderManager no debe mentionar antigravity");
  assert.ok(/this\.defaultProvider = "opencode"/.test(mgr), "el default duro debe ser opencode");
});

test("Antigravity: un provider desconocido se rechaza con 400, no se ignora en silencio", () => {
  const i = src.indexOf("PROVIDER_UNKNOWN");
  assert.ok(i > 0, "debe existir PROVIDER_UNKNOWN");
  const blk = src.slice(Math.max(0, i - 400), i + 200);
  assert.ok(/res, 400/.test(blk), "la respuesta debe ser 400");
});

test("Antigravity: no queda routing por prefijo agy_ que devuelva al motor retirado", () => {
  // El id agy_ ya no significa nada; si volviera a mapear a un proveedor inexistente,
  // la petición reventaría con ReferenceError en vez de degradar.
  assert.ok(!/startsWith\("agy_"\)\s*\?\s*"antigravity"/.test(code(src)), "no debe haber ternario agy_ -> antigravity");
  assert.ok(!/agyConversationId/.test(code(src)), "agyConversationId pertenecía al brain de agy");
  assert.ok(!/content query/.test(code(src)), "ningún comando de texto libre al shell queda vivo");
});
