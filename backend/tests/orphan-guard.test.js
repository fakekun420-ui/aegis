// orphan-guard.test.js — un hub de tests no puede sobrevivir a su padre (backend)
//
// Por que existe: MEDIDO 2026-09-29. La suite arranca un hub POR TEST y los mata
// todos juntos en un unico `after()` de fin de fichero (`hubs.push(child)`), asi
// que un fichero de 7 tests tiene 7 hubs vivos a la vez, 80-90 MB cada uno. Ese
// `after()` NO es una defensa: si el proceso dueno muere con SIGKILL —que es lo
// que hace un timeout de CI, con exit 124/137— no se ejecuta NINGUN hook y los
// hubs acumulados se quedan con PPID=1 para siempre.
//
// REPRODUCIDO, no supuesto. Con el guardia puesto, la MISMA operacion:
//   antes : 6 huerfanos, 504 MB retenidos, de un solo fichero
//   ahora : 0 huerfanos
//
// La defensa vive en el hijo (server.js, guardia de supervivencia), no en el padre:
// cuando el dueno muere, el unico proceso que sigue en pie es el hub, y es el que
// tiene que notar que su padre ya no esta. Este fichero NO puede probar esa parte
// matando a su propio padre —matar el padre de un test mata el runner de tests—,
// asi que prueba lo que si es determinista:
//   H1 el guardia se arma y el hub se va SOLO, sin que nadie lo mate
//   H2 un hub con la topologia de PRODUCCION no lo arma (si falseara, mataria el Hub)
//   H3 el detector de "mi padre es un runner de tests" es de PREFIJO (regresion)
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

function bebas(child) {
  hubs.push(child);
  // OBLIGATORIO beber los dos pipes: si no, el logger llena el buffer y el hijo se
  // bloquea escribiendo. Sin esto el runner se queda esperando a un pipe abierto.
  child.stdout.on("data", () => {});
  child.stderr.on("data", () => {});
}

async function esperarListo(child, port, salida, ms = 25000) {
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) throw new Error(`server.js salio antes de estar listo (code=${child.exitCode})\n${salida()}`);
    try {
      const r = await fetch(`http://127.0.0.1:${port}/api/health`, { signal: AbortSignal.timeout(1000) });
      if (r.status === 200) return;
    } catch (_) { /* aun no escucha */ }
    await new Promise((r) => setTimeout(r, 250));
  }
  throw new Error(`hub no respondio en ${ms}ms\n${salida()}`);
}

/** Arranca un hub directamente: su padre es el runner de tests. */
async function startHub(extra = {}) {
  const port = await freePort();
  const child = spawn(process.execPath, ["server.js"], {
    cwd: BACKEND_DIR,
    env: { ...process.env, HUB_PORT: String(port), ...extra },
    stdio: ["ignore", "pipe", "pipe"]
  });
  let salida = "";
  bebas(child);
  child.stdout.on("data", (c) => { salida += c.toString(); });
  child.stderr.on("data", (c) => { salida += c.toString(); });
  await esperarListo(child, port, () => salida);
  return { child, port, salida: () => salida };
}

/**
 * Arranca un hub con la TOPOLOGIA DE PRODUCCION: a traves de un shell, SIN `exec`.
 * El shell hace fork y el padre del hub es `sh`, no el runner de tests: exactamente
 * como lo lanza start-hub.sh (`nohup node server.js ...`). Con `exec` el shell seria
 * el hub y su padre volveria a ser el runner, que es justo lo que este test tiene
 * que evitar medir.
 */
async function startHubComoProduccion() {
  const port = await freePort();
  const child = spawn("sh", ["-c", `node server.js --port ${port} --opencode-port 49374`], {
    cwd: BACKEND_DIR,
    env: { ...process.env, HUB_PORT: String(port) },
    stdio: ["ignore", "pipe", "pipe"],
    // detached: el shell y el node que forkea se van en un GRUPO de procesos propio.
    // Sin esto, matar al `sh` NO mata al `node` que tiene debajo, y este test —que
    // existe precisamente para no dejar hubs huerfanos— se LOS DEJA. Medido: asi
    // el fichero de test no terminaba nunca y el runner se quedaba esperando a un
    // pipe abierto. Y el node superviviente tampoco se auto-limita, porque su padre
    // es un shell sin `--test`: el guardia no se arma en el caso que este test
    // comprueba. El fallo era del test, no del guardia.
    detached: true
  });
  let salida = "";
  bebas(child);
  child.stdout.on("data", (c) => { salida += c.toString(); });
  child.stderr.on("data", (c) => { salida += c.toString(); });
  await esperarListo(child, port, () => salida);
  return { child, port, salida: () => salida };
}

/**
 * Espera a que `salida` contenga el patron. Hace falta porque la entrega del pipe
 * es ASINCRONA: /api/health puede responder 200 antes de que el evento 'data' del
 * stdout se haya entregado. Comprobar el texto en el acto es una carrera, y esa
 * carrera fallo aqui una vez —el test dio por hecho que la guardia no se armaba
 * cuando si se armaba, porque aun no le habia llegado la linea.
 */
async function esperarTexto(salida, patron, ms = 8000) {
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) {
    if (patron.test(salida())) return true;
    await new Promise((r) => setTimeout(r, 200));
  }
  return patron.test(salida());
}

function esperarSalida(child, ms) {
  return new Promise((resolve) => {
    if (child.exitCode !== null || child.signalCode !== null) return resolve(true);
    const t = setTimeout(() => resolve(false), ms);
    child.once("exit", () => { clearTimeout(t); resolve(true); });
  });
}

after(() => { for (const c of hubs) { try { c.kill("SIGKILL"); } catch (_) {} } });

// ---------------------------------------------------------------- H1
test("H1: un hub de test se auto-limita — nadie lo mata y se va solo", async () => {
  const { child, salida } = await startHub({ AEGIS_SELF_LIMIT_MS: "3000" });
  try {
    assert.ok(
      await esperarTexto(salida, /guardia de supervivencia armada/),
      `la guardia deberia haberse armado al arrancar. Salida:\n${salida()}`
    );
    assert.ok(
      await esperarSalida(child, 20000),
      `el hub sigue vivo 20 s despues: la guardia no lo esta auto-limitando. Salida:\n${salida()}`
    );
    assert.ok(
      await esperarTexto(salida, /guardia de supervivencia:.*Me voy/),
      "deberia decir POR QUE se va: un silencio aqui no se puede depurar"
    );
  } finally {
    try { child.kill("SIGKILL"); } catch (_) {}
  }
});

// ---------------------------------------------------------------- H2
test("H2: un hub con la topologia de PRODUCCION no arma la guardia", async () => {
  // Si el detector falseara, el Hub de produccion se mataria al cerrarse quien lo
  // lanzo. Este es el test que protege de MI propio error, no del defecto original.
  const { child, salida } = await startHubComoProduccion();
  try {
    await new Promise((r) => setTimeout(r, 8000));
    assert.equal(child.exitCode, null, "el hub deberia seguir vivo: nadie lo ha matado");
    assert.doesNotMatch(
      salida(),
      /guardia de supervivencia armada/,
      "un hub lanzado por un shell (como en produccion) no debe armar la guardia"
    );
  } finally {
    // al GRUPO: shell + node. Ver el comentario de `detached` arriba.
    try { process.kill(-child.pid, "SIGKILL"); } catch (_) {}
    try { child.kill("SIGKILL"); } catch (_) {}
  }
});

// ---------------------------------------------------------------- H3
test("H3: el detector de 'mi padre es un runner de tests' es de PREFIJO", () => {
  // REGRESION sobre un fallo real de la primera version: el padre real es
  //   node --test-coverage-functions=0 --test-concurrency=0 --test-isolation=process ...
  // Node pone esos flags al forkear el hijo por fichero, asi que `--test` va seguido
  // de GUION. Un matcher que exija `--test(\s|$)` no casa con NADA y la guardia no se
  // arma nunca: el defecto sigue ahi y no se nota.
  //
  // Se leen solo las lineas de CODIGO. Buscar en el fichero entero miente: el
  // comentario que explica este bug CONTIENE el matcher viejo, y un test que lo
  // busca asi se falla a si mismo.
  const codigo = fs.readFileSync(join(BACKEND_DIR, "server.js"), "utf8")
    .split("\n")
    .filter((l) => !l.trim().startsWith("//"))
    .join("\n");

  assert.doesNotMatch(
    codigo,
    /--test\(\\s\|\$\)/,
    "el matcher NO puede exigir espacio o fin tras --test: en el padre real hay un guion"
  );
  assert.match(codigo, /\(\^\|\\s\)--test/, "debe haber un matcher de PREFIJO para --test");

  // El control negativo, aqui y no solo en el comentario.
  const re = /(^|\s)--test/;
  for (const l of [
    "/system/bin/sh /sdcard/projects/Aegis/backend/start-hub.sh",
    "/system/bin/sh /sdcard/projects/Aegis/backend/keepalive.sh",
    "/system/bin/sh /data/adb/service.d/99-opencode-hub.sh"
  ]) {
    assert.equal(re.test(l), false, `el lanzador de produccion ${l} no debe activar la guardia`);
  }
  assert.equal(
    re.test("node --test-coverage-functions=0 --test-concurrency=0 --test-isolation=process"),
    true,
    "el padre real de un hub de test SI debe activar la guardia"
  );
});
