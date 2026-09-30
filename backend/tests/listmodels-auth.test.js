// listmodels-auth.test.js — la password de OpenCode se RESUELVE y se VALIDA (backend)
//
// Por que existe: MEDIDO 2026-09-30. `_readPassword()` se quedaba con "el primer
// candidate que EXISTE", y el orden era el equivocado. Peor: cuando el servidor
// rebotaba con 401, `_v2` invalidaba la cache y reintentaba UNA vez —pero el
// reintento volvia a elegir la MISMA primera candidata, que era justo la caducada.
// Con la primera candidata mala no habia ninguna recuperacion posible, por muchas
// veces que se reintentara.
//
// Que fija este fichero, por orden de fiabilidad:
//
//   L1 la fuente de verdad es la que escribe el propio serve al arrancar
//      (/root/.local/state/opencode/service.json, lleva su `pid` y su `url`), y va
//      PRIMERA en la lista de candidatas.
//   L2 una candidata CADUCADA como primera NO ROMPE nada: el 401 la descarta y el
//      Hub sigue bajando por la lista. Este es el CONTROL NEGATIVO que hace falta:
//      sin el, un test que sigue verde con la clave rota no esta probando la
//      autenticacion.
//   L3 y para que L2 no sea verde por accidente: si TODAS las candidatas estan
//      caducadas, la lista NO se puede autenticar y el resultado lo dice.
//   L4 la ventana de rechazo CADUCA: si no caducara, en cuanto el serve rota su
//      clave la lista se quedaria rota para siempre. Un candidate rechazado hoy es
//      el bueno mañana, y el codigo tiene que volver a mirarlo.
//
// No toca el serve de verdad: cada test levanta el suyo en un puerto efimero.

import { test, after } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { OpencodeAdapter } from "../providers.js";

const servers = [];
const dirs = [];

/**
 * Serve falso: exige Basic opencode:<password> y sirve /api/model con 2 modelos.
 * Devuelve un objeto con la password MODIFICABLE, para poder simular la rotacion
 * que hace el serve de verdad en cada arranque.
 */
function serveFalso(passwordInicial) {
  const estado = { password: passwordInicial };
  return new Promise((resolve) => {
    const srv = http.createServer((req, res) => {
      const ok = req.headers.authorization === "Basic " + Buffer.from(`opencode:${estado.password}`).toString("base64");
      if (!ok) {
        res.writeHead(401, { "www-authenticate": 'Basic realm="Secure Area"' });
        res.end("no");
        return;
      }
      res.writeHead(200, { "Content-Type": "application/json" });
      if (req.url.startsWith("/api/model")) {
        res.end(JSON.stringify({
          data: [
            { id: "m-free", modelID: "m-free", providerID: "opencode", name: "M Free", cost: [{ input: 0, output: 0 }] },
            { id: "m-pago", modelID: "m-pago", providerID: "opencode", name: "M Pago", cost: [{ input: 5, output: 5 }] }
          ]
        }));
        return;
      }
      res.end(JSON.stringify({ data: { version: "2.0.14" } }));
    });
    srv.listen(0, "127.0.0.1", () => {
      servers.push(srv);
      resolve({ port: srv.address().port, estado });
    });
  });
}

/** Escribe un candidate .json con una password y devuelve su ruta. */
function candidate(password) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aegis-pw-"));
  dirs.push(dir);
  const f = path.join(dir, "service.json");
  fs.writeFileSync(f, JSON.stringify({ port: 49374, password }));
  return f;
}

/** Log con la forma que el Hub raspa: "server password <pw>". */
function candidateLog(password) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aegis-pw-"));
  dirs.push(dir);
  const f = path.join(dir, "serve.log");
  fs.writeFileSync(f, `server password ${password}\n`);
  return f;
}

function adaptador(port, candidatas) {
  return new OpencodeAdapter({ host: "127.0.0.1", port, passwordCandidates: candidatas, authProbePath: "/api/info" });
}

/** Vacia caches y simula que ha pasado la ventana de rechazo. */
function reinicia(a) {
  a._modelsCache = null;
  a._modelsCacheTime = 0;
  a._pw = null;
  a._pwTime = 0;
  a._pwFrom = null;
  a._pwRechazadasTime = Date.now() - 61000;
}

after(() => {
  for (const s of servers) { try { s.close(); } catch (_) {} }
  for (const d of dirs) { try { fs.rmSync(d, { recursive: true, force: true }); } catch (_) {} }
});

// ---------------------------------------------------------------- L1
test("L1: la lista viene del serve, con el candidate bueno primero", async () => {
  const BUENA = "pw-buena-esta";
  const { port } = await serveFalso(BUENA);
  const a = adaptador(port, [candidate(BUENA)]);

  const ids = (await a.listModels()).map((m) => m.id);
  assert.ok(ids.includes("m-free"), `la lista debe traer el modelo del serve. Vino: ${JSON.stringify(ids)}`);

  // Y el ORDEN por fiabilidad de la lista de candidatas, verificado sobre el
  // constructor de verdad (sin inyectar candidatas).
  const porDefecto = new OpencodeAdapter({ host: "127.0.0.1", port }).passwordCandidates;
  assert.equal(
    porDefecto[0],
    "/root/.local/state/opencode/service.json",
    "la fuente de verdad (la que escribe el serve al arrancar) debe ir la primera"
  );
  assert.ok(
    porDefecto.indexOf("/root/.config/opencode/service.json") === porDefecto.length - 1,
    "el espejo antiguo (/root/.config/...) no puede ser la primera: su mtime no se actualiza al arrancar el serve"
  );
});

// ---------------------------------------------------------------- L2 (control negativo)
test("L2: candidate CADUCADO primero — el Hub lo descarta y lista igual", async () => {
  const BUENA = "pw-buena-tras-la-caducada";
  const CADUCADA = "pw-caducada-del-26-sep";
  const { port } = await serveFalso(BUENA);

  const a = adaptador(port, [
    candidate(CADUCADA),              // 1ª: caducada a proposito. ESTO es el control.
    candidateLog(CADUCADA),           // 2ª: la misma caducada, en formato log
    candidate(BUENA)                  // 3ª: la buena
  ]);

  const ids = (await a.listModels()).map((m) => m.id);
  assert.ok(
    ids.includes("m-free"),
    `con una caducada la primera, el Hub deberia descartarla y autenticarse con la buena. Vino: ${JSON.stringify(ids)}`
  );
  assert.ok(a._pwRechazadas.size >= 1, "la caducada deberia constar como rechazada, no olvidarse");
  assert.equal(a._pwFrom, a.passwordCandidates[2], "la que esta en uso debe ser la buena, la tercera");
});

// ---------------------------------------------------------------- L3
test("L3: si TODAS estan caducadas, la lista NO se puede autenticar (L2 no es verde por accidente)", async () => {
  const BUENA = "pw-que-esta-en-el-serve-y-no-en-ningun-candidate";
  const { port } = await serveFalso(BUENA);

  const a = adaptador(port, [candidate("caducada-1"), candidate("caducada-2"), candidateLog("caducada-3")]);
  const ids = (await a.listModels()).map((m) => m.id);

  assert.equal(
    ids.includes("m-free"), false,
    "sin ninguna clave buena NO debe poder traer los modelos del serve; si los trae, el test de L2 no probaba nada"
  );
  assert.equal(a._pw, null, "la password en uso debe quedar a null si ninguna candidata autentico");
  assert.equal(a._pwRechazadas.size, 3, "las tres candidatas deben constar como rechazadas: se probaron todas");
});

// ---------------------------------------------------------------- L4
test("L4: la ventana de rechazo caduca — tras rotar la clave del serve, la lista vuelve", async () => {
  const VIEJA = "pw-caducada-que-ayer-era-la-buena";
  const NUEVA = "pw-que-tras-rotar";
  const { port, estado } = await serveFalso(VIEJA);

  // La UNICA candidata tiene la clave NUEVA, que el serve todavia no tiene: se rechaza.
  const a = adaptador(port, [candidate(NUEVA)]);

  // 1) El serve tiene la clave VIEJA y el candidate ofrece la NUEVA: 401, la candidata
  //    queda rechazada y la lista no sale. (La primera version de este test ponia en el
  //    candidate justo la clave que el serve SI tenia, o sea que autenticaba a la
  //    primera y la asercion de abajo era falsa.)
  const ids1 = (await a.listModels()).map((m) => m.id);
  assert.equal(ids1.includes("m-free"), false, "con la clave cambiada de sitio la lista no deberia salir");

  // 2) El serve rota, como hace en cada arranque. Ahora la clave NUEVA es la buena.
  estado.password = NUEVA;
  reinicia(a);

  const ids2 = (await a.listModels()).map((m) => m.id);
  assert.ok(
    ids2.includes("m-free"),
    `tras rotar la clave del serve y pasar la ventana, tiene que volver a listar. Vino: ${JSON.stringify(ids2)}`
  );
});