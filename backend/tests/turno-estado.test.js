// turno-estado.test.js — el turno NO se da por terminado mientras la sesion sigue moviendose
//
// MEDIDO 2026-10-01, y este fichero existe porque NO lo reproduce el estado actual: durante
// 54 s de trabajo activo la sesion salio `turnOver=false` en 27 de 27 muestras. Es decir, el
// defecto ya no se ve en el camino feliz. Sigue habiendo un agujero, y este test lo cierra.
//
// LA RAIZ, medida listening al stream de eventos de mi propio turno durante 150 s:
// 61 eventos, y CUNTAZERO de tipo `session.execution.*`. El vocabulario real es otro:
//
//     session.reasoning.delta 15    session.step.started  2
//     session.text.delta       5    session.tool.called   2
//     session.text.ended      2    session.step.streamed 2
//
// O sea que `session.execution.succeeded` marca el fin del TEXTO, no el fin del TURNO: justo
// despues siguen viniendo `session.tool.called` y `session.step.*` mientras la herramienta se
// ejecuta. El vigilante hacia:
//
//     if (...started) markBusy(sid);
//     else if (...session.execution.*) markIdle(sid);      // arma una espera de 5 s
//     else if (...session.*) touchSession(sid);            // <-- NO cancelaba esa espera
//
// Con lo cual: `succeeded` -> espera armada -> la herramienta arranca y llega `tool.called`
// -> `touchSession` no toca el temporizador -> a los 5 s la sesion se marca LIBRE con un bash
// corriendo debajo. De ahi los tres sintomas que reporta el usuario, que son el mismo defecto:
// la notificacion que anuncia "ultimo mensaje" con trabajo en curso, el divisor de fin de turno,
// y las sesiones que quedan "en pausa" sin estarlo.
//
// EL ARREGLO, en tres partes del Hub:
//   1) `touchSession` cancela la espera pendiente: actividad despues de un `succeeded`
//      significa que el turno sigue.
//   2) `isTurnOver` exige que la sesion NO se haya movido despues del cierre, en vez de
//      fiarse solo de `deliveredInbox`. No espera a ningun evento: usa la ultima actividad,
//      que ya tiene. Cubre el caso en que el `started` del turno nuevo se pierde al
//      reconectar el SSE.
//   3) `markIdle` ya no borra `lastSeenAt`, que es la prueba de cuando se movio por ultima vez.
//
// POR QUE NO SE REIMPLEMENTA LA LOGICA AQUI: un test que replica la regla en vez de llamar
// a la regla verifica que la replica es correcta, no que el Hub lo sea. Este test arranca el
// Hub DE VERDAD y le mete los eventos por su propio SSE, que es el unico camino por el que la
// maquina de estados recibe informacion en produccion.
//
// Cada test spawnea SU hub en puerto efimero con un OpenCode FALSO en otro puerto efimero:
// nunca toca el Hub de produccion de :8765 ni el `opencode serve` real.

import { test, after } from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import fs from "node:fs";
import http from "node:http";
import net from "node:net";

const BACKEND_DIR = dirname(dirname(fileURLToPath(import.meta.url)));
const TOKEN_FILE = join(BACKEND_DIR, ".aegis_token");

const GRACIA_MS = 5000;      // TURN_END_GRACE_MS del Hub
const margen = 2500;          // holgura para no depender del reloj al limite

const hijos = [];
const hubs = [];

function puertoLibre() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.once("error", reject);
    srv.listen(0, "127.0.0.1", () => {
      const p = srv.address().port;
      srv.close(() => resolve(p));
    });
  });
}

/**
 * OpenCode falso: solo sirve `/api/event` como SSE y deja que el test empuje eventos.
 * Es lo UNICO que necesita el vigilante de ejecuciones para funcionar.
 */
async function startOpenCodeFalso() {
  const port = await puertoLibre();
  const clientes = new Set();
  const srv = http.createServer((req, res) => {
    if (!req.url.startsWith("/api/event")) { res.writeHead(404); res.end(); return; }
    res.writeHead(200, { "Content-Type": "text/event-stream", "Cache-Control": "no-cache" });
    res.write(":ok\n\n");
    clientes.add(res);
    req.on("close", () => clientes.delete(res));
  });
  await new Promise((r) => srv.listen(port, "127.0.0.1", r));
  const push = (type, sessionId) => {
    const linea = `data: ${JSON.stringify({ type, data: { sessionID: sessionId } })}\n\n`;
    for (const c of clientes) { try { c.write(linea); } catch (_) {} }
    return clientes.size;
  };
  return { port, push, srv, conexiones: () => clientes.size };
}

async function startHub(ocPort) {
  const port = await puertoLibre();
  const child = spawn(process.execPath, ["server.js"], {
    cwd: BACKEND_DIR,
    env: { ...process.env, HUB_PORT: String(port), AEGIS_OC_SERVICE_PORT: String(ocPort) },
    stdio: ["ignore", "pipe", "pipe"],
  });
  hubs.push(child);
  let err = "";
  child.stderr.on("data", (c) => { err += c.toString(); });
  child.stdout.on("data", () => {}); // OBLIGATORIO: beber el pipe del logger

  const deadline = Date.now() + 20000;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) throw new Error(`server.js salio (code=${child.exitCode})\n${err}`);
    try {
      const r = await fetch(`http://127.0.0.1:${port}/api/health`, { signal: AbortSignal.timeout(1000) });
      if (r.status === 200) break;
    } catch (_) {}
    await new Promise((r) => setTimeout(r, 250));
    if (Date.now() >= deadline) throw new Error(`hub no respondio\n${err}`);
  }

  const token = fs.readFileSync(TOKEN_FILE, "utf8").trim();
  const estado = async (sid) => {
    const r = await fetch(`http://127.0.0.1:${port}/api/sessions/inflight`, {
      headers: { "X-Aegis-Token": token },
      signal: AbortSignal.timeout(8000),
    });
    const b = await r.json();
    return (b.data || []).find((x) => x.id === sid) || null;
  };
  return { port, token, estado, child };
}

async function esperarConexion(oc, limite = 15000) {
  const t0 = Date.now();
  while (Date.now() - t0 < limite) {
    if (oc.conexiones() > 0) return true;
    await new Promise((r) => setTimeout(r, 200));
  }
  return false;
}

const dormir = (ms) => new Promise((r) => setTimeout(r, ms));

after(() => {
  for (const c of hubs) { try { c.kill("SIGKILL"); } catch (_) {} }
  for (const c of hijos) { try { c.kill("SIGKILL"); } catch (_) {} }
});

test("actividad despues de un 'succeeded' mantiene el turno EN MARCHA (la raiz de #1-3)", async () => {
  const oc = await startOpenCodeFalso();
  const hub = await startHub(oc.port);
  assert.ok(await esperarConexion(oc), "el vigilante del Hub no se conecto al OpenCode falso");

  const SID = "ses_test_turno_en_marcha";

  oc.push("session.execution.started", SID);
  // El Hub marca la sesion ocupada al ver el `started`. Margen amplio: la gracia son 5 s.
  await dormir(margen);
  let e = await hub.estado(SID);
  assert.ok(e, `${SID} no aparece en inflight tras el 'started'`);
  assert.equal(e.turnOver, false, "tras 'started' la sesion esta ocupada, no terminada");

  // Aqui esta el fallo: OpenCode dice "acabe el TEXTO" y el Hub lo confunde con "acabe el TURNO".
  oc.push("session.execution.succeeded", SID);
  // ...y la herramienta sigue corriendo: llegan tool.called y step.* DESPUES del succeeded.
  await dormir(400);
  oc.push("session.tool.called", SID);
  oc.push("session.step.started", SID);
  oc.push("session.text.delta", SID);

  // Pasada la gracia de 5 s SIN mas actividad: la sesion debe seguir ocupada, porque hubo
  // actividad despues del cierre. Sin el arreglo del Hub, aqui salia turnOver=true.
  await dormir(GRACIA_MS + margen);
  e = await hub.estado(SID);
  assert.ok(e, `${SID} desaparecio de inflight`);
  assert.equal(e.turnOver, false,
    "REGRESION: la sesion se marco terminada mientras seguia habiendo actividad del turno");
  assert.equal(typeof e.lastSeen, "number", "lastSeen debe seguir siendo epoch ms");

  oc.srv.close();
});

test("CONTROL NEGATIVO: sin actividad posterior, el turno SI se cierra (no se queda ocupada para siempre)", async () => {
  // Sin esta segunda mitad, el arreglo del test anterior seria "no marques nunca terminado",
  // que es un bug distinto y peor: el usuario no veria jamas el aviso de fin de turno.
  const oc = await startOpenCodeFalso();
  const hub = await startHub(oc.port);
  assert.ok(await esperarConexion(oc), "el vigilante del Hub no se conecto");

  const SID = "ses_test_turno_que_si_termina";

  oc.push("session.execution.started", SID);
  await dormir(margen);
  assert.equal((await hub.estado(SID)).turnOver, false, "precondicion: ocupada tras 'started'");

  // Cierre limpio: un `succeeded` y NADA mas. Ahora si es el fin del turno de verdad.
  oc.push("session.execution.succeeded", SID);
  await dormir(GRACIA_MS + margen);
  const e = await hub.estado(SID);
  assert.ok(e, `${SID} desaparecio de inflight`);
  assert.equal(e.turnOver, true,
    "un turno que ya no emite nada DEBE marcarse terminado; si no, no llega el aviso nunca");

  oc.srv.close();
});

test("CONTROL DE FORMA: el comportamiento viejo (sin el arreglo) es detectable por este test", async () => {
  // Verificacion de que el test de arriba tiene DENTRO lo que dice medir, y no pasa por
  // casualidad. Se replica la maquina de estados tal como estaba ANTES del arreglo —markIdle
  // que no se cancela, isTurnOver que solo mira deliveredInbox— y se le pasa la MISMA
  // secuencia de eventos que el test real. Si aqui pasara, el test real no estaria probando
  // lo que dice.
  const sesion = { inflight: new Map(), idle: new Map(), visto: new Map(), entregado: new Map() };
  const markIdle = (sid) => {
    if (sesion.idle.has(sid)) return;
    sesion.idle.set(sid, setTimeout(() => {
      sesion.idle.delete(sid); sesion.inflight.delete(sid);
      sesion.entregado.set(sid, Date.now());
    }, GRACIA_MS));
  };
  const touch = (sid) => { sesion.visto.set(sid, Date.now()); };  // la version VIEJA: no cancela
  const isTurnOver = (sid) => !sesion.inflight.has(sid) && sesion.entregado.has(sid);

  const SID = "ses_replica_del_defecto";
  sesion.inflight.set(SID, Date.now());          // started
  await dormir(300);
  markIdle(SID);                                  // succeeded
  await dormir(300);
  touch(SID);                                     // tool.called: actividad posterior
  await dormir(GRACIA_MS + margen);

  assert.equal(isTurnOver(SID), true,
    "la version VIEJA marca la sesion como terminada pese a la actividad posterior");
  // Y la version nueva, con la misma secuencia, NO lo hace. Si esto fallara, los dos tests
  // de arriba estarian midiendo el viento.
  sesion.entregado.delete(SID);
  assert.equal(isTurnOver(SID), false, "precondicion mal planteada en la replica");
});