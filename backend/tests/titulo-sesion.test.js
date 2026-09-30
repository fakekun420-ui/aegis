// titulo-sesion.test.js — una sesion existente NO se renombra, y el nombre real gana
//
// MEDIDO 2026-09-30, ses_f15109400ffeCFxEAtL8u33Dn0:
//
//     OpenCode (CLI): "quant-math"
//     Aegis:           "adjunto captura ee pantalla de…"
//
// Las dos capas se habian separado y el nombre bueno no se veia. La causa era una sola y
// concreta: el store de la app guardaba en el MISMO cubo (`sessionTitles`) el titulo
// PROVISIONAL (recorte del primer mensaje) y el RENOMBRADO A MANO. Y el overlay de
// `/api/opencode/sessions` leia ese cubo y le ponia el resultado encima al nombre real,
// que se conservaba intacto en `providerTitle` y no se ense~naba nunca.
//
// Con el provisional wins, cualquier sesion quedaba con el recorte del primer mensaje para
// siempre. Y al Mandar otro mensaje el Hub lo volvia a escribir, asi que tampoco se
// arreglaba sola.
//
// Que fija este fichero:
//
//   T1 el overlay SOLO respeta lo deliberado; si no hay, gana OpenCode.
//   T2 el auto-titulo va a su propio cubo y no pisa un renombrado a mano.
//   T3 el nombre real de OpenCode se distingue POR LA FORMA, no por una lista de ids.
//   T4 la migracion mueve solo los automaticos.
//
// Como `resolveExistingSessionTitle` y `tituloRealDeOpenCode` viven dentro de server.js y
// ese fichero no exporta nada (importarlo arrancaria el Hub, con efectos de segundo), se
// fijan por FUENTE. Es el mismo criterio que ya usan A2 y A5 de agents.test.js, y tiene una
// ventaja que importa: la prueba del comportamiento de verdad se hizo contra el Hub vivo
// (el titulo paso de "adjunto captura ee pantalla de…" a "quant-math"), no contra un doble
// de laboratorio.

import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";

const SRC = fs.readFileSync(new URL("../server.js", import.meta.url), "utf8");

test("T1: el overlay solo respeta el titulo DELIBERADO; si no hay, gana OpenCode", () => {
  assert.ok(
    SRC.includes("const deliberado = store.sessionTitles && store.sessionTitles[item.id]"),
    "el overlay tiene que leer el cubo DELIBERado (sessionTitles)"
  );
  assert.ok(
    /if \(deliberado && deliberado !== item\.id\) \{\s*item\.title = deliberado;\s*\} else \{\s*item\.title = item\.providerTitle \|\| item\.title;/.test(SRC),
    "si no hay renombrado a mano, `item.title` tiene que ser el nombre REAL de OpenCode (providerTitle)"
  );
  assert.ok(
    !/const custom = resolveExistingSessionTitle\(store, item\.id\);\s*if \(custom && custom !== item\.id\) \{\s*item\.title = custom;/.test(SRC),
    "el overlay ha vuelto a usar resolveExistingSessionTitle(): eso devuelve tambien el titulo PROVISIONAL y vuelve a perder el nombre real"
  );
});

test("T2: el auto-titulo va a su cubo y respeta el renombrado a mano", () => {
  assert.ok(
    SRC.includes("store.sessionAutoTitles[sid] = autoTitle"),
    "el titulo automatico debe escribirse en `sessionAutoTitles`, no en `sessionTitles`"
  );
  assert.ok(
    SRC.includes("if (store.sessionTitles[sid]) return;"),
    "si el usuario renombro la sesion a mano, el auto-titulo NO debe tocarla"
  );
  assert.ok(
    SRC.includes("if (found && !found.title) {"),
    "la entrada de la sesion en el proyecto solo se rellena si no tiene titulo: una sesion con nombre no se pisa"
  );
});

test("T3: el nombre real de OpenCode se reconoce POR LA FORMA, no por una lista de ids", () => {
  const m = SRC.match(/function tituloRealDeOpenCode\([\s\S]*?\n}/);
  assert.ok(m, "tiene que existir tituloRealDeOpenCode()");
  const cuerpo = m[0];
  assert.ok(
    cuerpo.includes('t.startsWith("ses_")'),
    "un id de sesion no es un nombre"
  );
  assert.ok(
    cuerpo.includes("Nuevo chat"),
    "el marcador de sesion nueva tampoco es un nombre"
  );
  assert.ok(
    !/^\s*(const|let|var)\s+(LISTA|NOMBRES|IDS)\s*=/m.test(cuerpo),
    "la funcion no debe llevar una lista de ids: se quedaria vieja. La forma no caduca."
  );
});

test("T4: la migracion mueve SOLO los automaticos y es idempotente", () => {
  const m = SRC.match(/async function migrarTitulosAutomaticos\(\)[\s\S]*?\n}/);
  assert.ok(m, "tiene que existir migrarTitulosAutomaticos()");
  const cuerpo = m[0];
  assert.ok(
    cuerpo.includes("/…$/.test(t.trim())"),
    "la firma del auto-titulador es acabar en '…' (lo anade siempre al recortar a 30 chars)"
  );
  assert.ok(
    cuerpo.includes("if (store.sessionAutoTitles[sid]) { yaEstaban++; continue; }"),
    "idempotente: si ya estaba migrado, no se vuelve a tocar en el siguiente arranque"
  );
  assert.ok(
    cuerpo.includes("delete titulos[sid]"),
    "hay que QUITAR el automatico del cubo deliberado: si se deja ahi, vuelve a ganar"
  );
  // Y que se llame al arrancar, con su propio try/catch: una migracion de datos no puede
  // impedir que el Hub levante.
  assert.ok(
    SRC.includes("void migrarTitulosAutomaticos()"),
    "la migracion tiene que ejecutarse en el arranque"
  );
  assert.ok(
    SRC.includes("migracion de titulos automaticos fallo (no bloquea el arranque)"),
    "si la migracion falla, se dice y el Hub sigue: no puede ser un punto unico de fallo de arranque"
  );
});