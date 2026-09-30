// ratelimit-poll-bucket.test.js — cubo de polling separado (backend)
//
// Por que existe: el 2026-09-27 la app se limitaba A SI MISMA. El techo de 120/min se
// se dimensiono con la cuenta de "chat-polling ~40/min + UI holgada", que era
// FALSA: medido en hub.log, el turno termino a las 05:45:32 y el primer 429 llego a las
// 05:46:12, o sea 40 s para agotar el presupuesto entero. El reparto real del ciclo con
// la app en un turno activo es ~3,7 req/s (~220/min):
//
//   startViewRefresh, cada 2s: inflight + forms + permissions + messages = 4
//   pollingJob,       cada 1.5s: messages                               = 1
//   lista de chats,   cada 2s: sessions                                 = 1
//   selector de modelo, cada 2s: models                                 = 1
//
// El efecto era peor que un error visible: el poll de la app se tragaba el 429 y se
// quedaba con el estado viejo, asi que "Trabajando en ello" y el divisor de respuesta
// final volvian a mentir. Y al enviar una encuesta se comia el 429.
//
// El arreglo NO desactiva la proteccion: el polling de solo lectura recibe su propio
// cubo (600/min por defecto, AEGIS_RATE_LIMIT_POLL_N). Esto fija ese contrato.
//
// Cada test spawnea SU hub en un puerto efimero: nunca toca el de produccion (:8765).

import { test, after } from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import fs from "node:fs";
import net from "node:net";

const BACKEND_DIR = dirname(dirname(fileURLToPath(import.meta.url))); // .../backend
const TOKEN_FILE = join(BACKEND_DIR, ".aegis_token");
const hubs = [];

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.once("error", reject);
    srv.listen(0, "127.0.0.1", () => {
      const p = srv.address().port;
      srv.close(() => resolve(p));
    });
  });
}

async function startHub(env = {}) {
  const port = await freePort();
  const child = spawn(process.execPath, ["server.js"], {
    cwd: BACKEND_DIR,
    env: { ...process.env, HUB_PORT: String(port), ...env },
    stdio: ["ignore", "pipe", "pipe"],
  });
  hubs.push(child);

  let stderr = "";
  child.stderr.on("data", (c) => { stderr += c.toString(); });
  child.stdout.on("data", () => {}); // OBLIGATORIO: beber el pipe del logger

  const deadline = Date.now() + 20000;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) {
      throw new Error(`server.js salio antes de estar listo (code=${child.exitCode})\n${stderr}`);
    }
    try {
      const r = await fetch(`http://127.0.0.1:${port}/api/health`, { signal: AbortSignal.timeout(1000) });
      if (r.status === 200) {
        // Un 200 NO basta: hay que comprobar que el Hub que contesta es NUESTRO hijo.
        // MEDIDO 2026-09-30: este test fallo una vez de cuatro, solo cuando la maquina
        // iba cargada, y la causa mas probable es que un Hub de una vuelta anterior
        // siguiera vivo en un puerto efimero reutilizado: el nuevo no puede tomar el
        // puerto, el test habla con el viejo —que ya tiene el cubo de rate-limit
        // lleno— y recibe 429 sin que nada diga por que.
        // Con el pid publicado, eso se ve en vez de ser un fallo fantasma.
        const dicho = r.headers.get("X-Aegis-Pid");
        if (dicho && Number(dicho) !== child.pid) {
          throw new Error(
            `el puerto ${port} lo responde un Hub que NO es nuestro hijo: pid ${dicho} ` +
            `contra hijo ${child.pid}. Alguien mas esta ocupando el puerto efimero.`
          );
        }
        break;
      }
    } catch (e) {
      if (String(e.message || "").startsWith("el puerto")) throw e;   // identidad: fallo real
      /* aun no escucha */
    }
    await new Promise((r) => setTimeout(r, 250));
    if (Date.now() >= deadline) throw new Error(`hub no respondio en 20000ms\n${stderr}`);
  }

  const token = fs.readFileSync(TOKEN_FILE, "utf8").trim();
  const api = (p, opts = {}) => fetch(`http://127.0.0.1:${port}${p}`, {
    method: opts.method || "GET",
    headers: {
      "X-Aegis-Token": token,
      ...(opts.body ? { "Content-Type": "application/json" } : {}),
    },
    body: opts.body,
    signal: AbortSignal.timeout(8000),
  });
  return { port, token, api, child };
}

after(() => { for (const c of hubs) { try { c.kill("SIGKILL"); } catch (_) {} } });

// Techos bajos para que el test sea rapido: poll 20, estricto 5.
const ENV = { AEGIS_RATE_LIMIT_POLL_N: "20", AEGIS_RATE_LIMIT_N: "5" };

test("el polling de solo lectura NO se come su propio limite (20 peticiones seguidas)", async () => {
  // Esta es la regresion concreta: con el techo unico, la 6a peticion de /api/forms
  // ya era un 429 y la app se quedaba sin estado.
  const { api } = await startHub(ENV);
  for (const ruta of ["/api/forms", "/api/permissions", "/api/sessions/inflight", "/api/sessions"]) {
    let status = 0;
    for (let i = 0; i < 5; i++) {
      const r = await api(ruta);
      status = r.status;
      assert.notEqual(r.status, 429, `${ruta} dio 429 en la peticion ${i + 1} de 5 (techo de poll = 20)`);
    }
    assert.equal(status, 200, `${ruta} deberia responder 200, dio ${status}`);
  }
});

test("el polling aguanta MAS que el techo estricto: 20 van, la 21 es 429", async () => {
  const { api } = await startHub(ENV);
  for (let i = 1; i <= 20; i++) {
    const r = await api("/api/forms");
    assert.notEqual(r.status, 429, `la peticion ${i} de 20 no deberia ser 429`);
  }
  const r21 = await api("/api/forms");
  assert.equal(r21.status, 429, "la 21 debe superar el techo de poll (20)");
  assert.ok(r21.headers.get("Retry-After"), "un 429 debe traer Retry-After");
});

test("una ruta que NO es de polling sigue con el techo estricto", async () => {
  // /api/jobs no esta en la lista de polling: es trabajo real, no el refresco de la UI.
  const { api } = await startHub(ENV);
  for (let i = 1; i <= 5; i++) {
    const r = await api("/api/jobs");
    assert.notEqual(r.status, 429, `la peticion ${i} de 5 no deberia ser 429`);
  }
  assert.equal((await api("/api/jobs")).status, 429, "la 6 debe dar 429 con techo estricto 5");
});

test("los dos cubos son independientes: agotar el de poll no deja al estricto sin cuota", async () => {
  // Es el motivo de darle ventana propia y no compartirla. Si compartieran, el poll se
  // comeria el presupuesto del estricto y el envio de una encuesta volveria a fallar
  // justo cuando mas hacen falta.
  const { api } = await startHub(ENV);
  for (let i = 1; i <= 20; i++) await api("/api/forms");
  assert.equal((await api("/api/forms")).status, 429, "el poll debe estar agotado");
  // El estricto, aparte, sigue entero.
  const t = await api("/api/jobs");
  assert.notEqual(t.status, 429, "agotar el poll no puede agotar el cubo estricto");
});

test("un POST cuenta para el cubo estricto, no para el de poll", async () => {
  // Una escritura no puede esconderse detras del trafico de lectura: aunque /api/forms
  // en GET sea de poll, su reply es un POST y va al cubo estricto.
  const { api } = await startHub(ENV);
  const ruta = "/api/forms/ses_x/fm_y/reply";
  for (let i = 1; i <= 5; i++) {
    const r = await api(ruta, { method: "POST", body: JSON.stringify({ nope: 1 }) });
    assert.notEqual(r.status, 429, `el POST ${i} no deberia ser 429`);
  }
  assert.equal(
    (await api(ruta, { method: "POST", body: JSON.stringify({ nope: 1 }) })).status,
    429,
    "el 6º POST debe dar 429 con techo estricto 5"
  );
});

test("GET a /api/sessions/:id/models y .../messages tambien cuentan como poll", async () => {
  // Son las dos rutas que mas golpea la app, con id de sesion en medio. Un fallo aqui
  // devuelve al comportamiento de 2026-09-27 sin que se note en ningun otro sitio.
  const { api } = await startHub(ENV);
  for (const ruta of ["/api/sessions/ses_x/models", "/api/opencode/sessions/ses_x/messages"]) {
    for (let i = 0; i < 5; i++) {
      const r = await api(ruta);
      assert.notEqual(r.status, 429, `${ruta} dio 429 en la peticion ${i + 1} de 5`);
    }
  }
});

test("sin token, 403 y no 429: el cubo de poll no esquiva la autenticacion", async () => {
  const { port } = await startHub(ENV);
  const r = await fetch(`http://127.0.0.1:${port}/api/forms`, { signal: AbortSignal.timeout(8000) });
  assert.equal(r.status, 403, "sin token esperaba 403, vino " + r.status);
});
