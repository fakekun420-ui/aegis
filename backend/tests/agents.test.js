// agents.test.js — el Hub tiene una conexion real con los agentes de OpenCode (backend)
//
// Por que existe: MEDIDO 2026-09-30. El boton de agente de la app era un INTERRUPTOR de
// dos (`if (agentMode == "plan") "build" else "plan"`), y el Hub solo activaba agente
// cuando el nombre estaba en `["plan","build"]`:
//
//     if (agentMode && ["plan", "build"].includes(String(agentMode))) { … }
//
// O sea que de los 40 agentes que OpenCode publica —`orchestrator` y los 32 cargos de
// Kaenor entre ellos— solo se podian alcanzar 2, y el resto no fallaba: se descartaba en
// silencio, sin log y sin error.
//
// Y el detalle que obliga a que la validacion la PONGA EL HUB: MEDIDO, que
// `POST /api/session/:id/agent` responde 204 CON CUALQUIER nombre, exista o no. Con un
// turno real por debajo, de contraste:
//
//     kaenor-ai-engineer     204 -> el turno corrio y contesto
//     kaenor-inventado-xyz   204 -> el turno salio VACIO
//
// Sin validar antes, un nombre mal escrito es un chat que se queda mudo sin decir nada.
//
// Que fija este fichero:
//
//   A1 listAgents() trae la lista REAL del serve, leida de la API y no escrita a mano, y
//      con el `model` aplanado a texto (que es lo que la app necesita pintar).
//   A2 la puerta de plan/build NO ha vuelto y la activacion pasa por `_agentExiste`.
//   A3 un agente de la lista SE acepta y uno que NO esta se rechaza  <- control negativo
//      de A2: sin el, A2 seguiria verde con `_agentExiste` devolviendo siempre true.
//   A4 sin nombre de agente NO se bloquea: esa decision es de OpenCode.
//
// Modelo: se prueba el adapter contra un serve FALSO, como listmodels-auth.test.js, y no
// un Hub por fichero. La ruta HTTP se ancla en A5 por codigo, y su comportamiento real se
// midio en ejecucion contra el Hub de verdad (40 agentes, orchestrator incluido).

import { test, after } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { OpencodeAdapter } from "../providers.js";

const servers = [];

/** Serve falso: exige Basic y sirve /api/agent, que es lo unico que se consulta. */
async function startFakeServe(password = "test-pass") {
  const agentes = [
    { name: "orchestrator", mode: "primary", model: { id: "space-bunny-free", providerID: "opencode" }, description: "Orquestador" },
    { name: "Build", mode: "primary", model: null, description: "The default agent" },
    { name: "Plan", mode: "primary", model: null, description: "Read-only" },
    { name: "kaenor-ai-engineer", mode: "subagent", model: { id: "x", providerID: "opencode" }, description: "Modelos y agentes" },
    // El caso que faltaba: un primary OCULTO. Es como vienen los tres internos de
    // OpenCode (Compaction, Title, Summary), y sin el, un `hidden` que el Hub no pasara
    // no se notaria en ningun test.
    { name: "Compaction", mode: "primary", model: null, description: null, hidden: true }
  ];
  const s = http.createServer((req, res) => {
    const h = String(req.headers.authorization || "");
    if (h !== `Basic ${Buffer.from(`opencode:${password}`).toString("base64")}`) {
      res.writeHead(401, { "WWW-Authenticate": 'Basic realm="Secure Area"' });
      res.end();
      return;
    }
    if (req.url === "/api/agent") {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: true, data: agentes }));
      return;
    }
    res.writeHead(404);
    res.end();
  });
  await new Promise((r) => s.listen(0, "127.0.0.1", r));
  servers.push(s);
  return { port: s.address().port, agentes };
}

const dirs = [];

/**
 * Adapter apuntando al serve falso.
 *
 * La password va en un FICHERO JSON de verdad porque las candidatas de `passwordCandidates`
 * son rutas (`fs.existsSync`), no cadenas. Se aprendo al escribirlo: con `"inline:pass"`
 * la lista salia vacia, `_agentExiste` caia en su rama de fail-open y A3 daba `true` para
 * un nombre INVENTADO — o sea, el control negativo en verde por la razon equivocada. Un
 * test que pasa sin probar lo que dice probar es peor que no tenerlo.
 */
function adapterPara(port, password = "test-pass") {
  const d = fs.mkdtempSync(path.join(os.tmpdir(), "aegis-agents-"));
  dirs.push(d);
  const ruta = path.join(d, "service.json");
  fs.writeFileSync(ruta, JSON.stringify({ pid: 1, url: `http://127.0.0.1:${port}`, password }));
  return new OpencodeAdapter({ host: "127.0.0.1", port, passwordCandidates: [ruta] });
}

after(async () => {
  for (const s of servers) { try { s.close(); } catch { } }
  for (const d of dirs) { try { fs.rmSync(d, { recursive: true, force: true }); } catch { } }
});

test("A1: listAgents() trae la lista REAL del serve, con el model aplanado a texto", async () => {
  const serve = await startFakeServe();
  const ad = adapterPara(serve.port);

  const lista = await ad.listAgents();
  assert.ok(Array.isArray(lista), "listAgents() tiene que devolver una lista");
  assert.equal(lista.length, serve.agentes.length, "tiene que traer los 4 agentes del serve falso");

  const nombres = lista.map((a) => a.name);
  assert.ok(nombres.includes("orchestrator"), "la lista tiene que traer orchestrator");
  assert.ok(nombres.includes("kaenor-ai-engineer"), "la lista tiene que traer los cargos, no solo los internos");

  const orch = lista.find((a) => a.name === "orchestrator");
  assert.equal(orch.mode, "primary", "orchestrator es primary, no subagent");
  assert.equal(
    orch.model, "space-bunny-free",
    "el model llega como TEXTO plano: la app lo pinta y un objeto {id,providerID} ahi se veria como [object Object]"
  );
  assert.equal(
    lista.find((a) => a.name === "Build").model, null,
    "un agente sin model llega null: no undefined (que en Kotlin es un tipo distinto) ni {}"
  );
  assert.equal(orch.description, "Orquestador", "la descripcion se conserva: es lo que explica que hace cada cargo");
});

test("A6: `hidden` se pasa tal cual, y primary+visible son los elegibles", async () => {
  const serve = await startFakeServe();
  const ad = adapterPara(serve.port);
  const lista = await ad.listAgents();

  // `hidden` es la bandera del PROPIO OpenCode para no ensenar un agente en su selector.
  // MEDIDO 2026-09-30 en el registro crudo: orchestrator/Build/Plan hidden=false y
  // Compaction/Title/Summary hidden=true. Es lo unico que separa los 3 reales de los
  // internos: por descripcion NO vale (los internos no la tienen, pero eso es casualidad,
  // no regla) y por nombre habria que hardcodear 3, que se queda viejo.
  const porNombre = (n) => lista.find((a) => a.name === n);
  assert.equal(porNombre("Compaction").hidden, true, "un primary oculto llega con hidden=true");
  assert.equal(porNombre("Build").hidden, false, "un primary visible llega con hidden=false");
  assert.equal(porNombre("orchestrator").hidden, false, "orchestrator es visible");
  assert.equal(
    porNombre("kaenor-ai-engineer").hidden, false,
    "un subagent visible tambien lo es: la bandera no significa 'cargo', significa 'no lo ensenes'"
  );

  // Y la regla que aplica la app, escrita aqui para que no se vuelva a inventar otra.
  // El serve falso trae 4 visibles (orchestrator, Build, Plan, kaenor-ai-engineer) y 1
  // oculto (Compaction). Los elegibles son los 3 primary visibles: ni el oculto, ni el
  // subagent. La comparacion se hace sobre la lista COMPLETA de nombres, para que
  // falte un agente o sobre uno de mas.
  const elegibles = lista
    .filter((a) => a.mode === "primary" && !a.hidden)
    .map((a) => a.name)
    .sort();
  assert.deepEqual(
    elegibles,
    ["Build", "Plan", "orchestrator"],
    "primary+visible tiene que ser exactamente los 3 elegibles: ni el oculto ni el subagent"
  );
});

test("A2: la puerta de plan/build NO ha vuelto, y la activacion pasa por _agentExiste", () => {
  const src = fs.readFileSync(new URL("../providers.js", import.meta.url), "utf8");

  assert.ok(
    !/\[\s*"plan"\s*,\s*"build"\s*\]\s*\.includes/.test(src),
    "providers.js ha vuelto a filtrar el agente por [\"plan\",\"build\"]: cualquier otro se descartaria en silencio"
  );
  assert.ok(
    /await this\._agentExiste\(/.test(src),
    "activar el agente tiene que pasar por _agentExiste(): es el HUB quien valida, porque el serve acepta cualquier nombre"
  );
});

test("A3: un agente de la lista SE acepta y uno que NO esta se rechaza (control negativo de A2)", async () => {
  const serve = await startFakeServe();
  const ad = adapterPara(serve.port);

  assert.equal(await ad._agentExiste("orchestrator"), true, "orchestrator esta en la lista: se acepta");
  assert.equal(
    await ad._agentExiste("kaenor-ai-engineer"), true,
    "un subagent tambien: MEDIDO que puede pilotar la sesion (turno real con agent=kaenor-ai-engineer)"
  );
  assert.equal(
    await ad._agentExiste("kaenor-inventado-xyz"), false,
    "un nombre que no esta en /api/agent tiene que rechazarse: el serve lo GUARDARIA y daria un turno vacio sin error"
  );
  assert.equal(
    await ad._agentExiste("  orchestrator  "), false,
    "sin recortar espacios, el nombre no casaria con la lista: se rechazaria un agente que SI existe (falso negativo)"
  );
});

test("A4: sin nombre de agente no se bloquea — esa decision es de OpenCode", async () => {
  const serve = await startFakeServe();
  const ad = adapterPara(serve.port);
  assert.equal(await ad._agentExiste(""), true, "cadena vacia: no se bloquea");
  assert.equal(await ad._agentExiste(null), true, "null: no se bloquea");
});

test("A5: la ruta /api/opencode/agents existe y llama a listAgents()", () => {
  const src = fs.readFileSync(new URL("../server.js", import.meta.url), "utf8");
  assert.ok(
    src.includes('pathname === "/api/opencode/agents"'),
    "la ruta GET /api/opencode/agents ha desaparecido: la app se queda sin lista de agentes"
  );
  assert.ok(
    /pathname === "\/api\/opencode\/agents"[\s\S]{0,400}listAgents\(\)/.test(src),
    "la ruta tiene que servir listAgents(), no una lista escrita a mano que se queda vieja al anadir un cargo"
  );
});
