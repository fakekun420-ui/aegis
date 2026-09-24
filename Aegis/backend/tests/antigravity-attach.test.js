// antigravity-attach.test.js — F7-2 · vertiente ANTIGRAVITY de los adjuntos
//
// Objetivo: que CI falle si una imagen deja de materializarse en disco con
// ruta ABSOLUTA para el agente de agy.
//
// Contexto (bug reportado con `bug_sendi_Aegis.png`): al enviar una imagen a
// una sesión Antigravity el modelo recibía sólo el placeholder de texto
// `[Attached File: foto.jpg]`, no los píxeles. El agente, sin ver la imagen y
// con herramientas (bash/view_file/manage_task), se ponía a buscar el archivo
// a ciegas — "hacer un montón de cosas" sin sentido.
//
// El contrato fijado aquí:
//   * agy sólo admite bloques `text` en el prompt
//     ("stream input content block type %q is not supported (only %q)"),
//     luego lo único que funciona es: disco + ruta absoluta + view_file
//     (verificado empíricamente con agy 1.2.10: el modelo devuelve el
//     contenido exacto de la imagen leída con view_file).
//   * binario        -> se escribe en attachmentsDir/<sid>/ y el prompt
//                       contiene la ruta absoluta + petición de view_file
//   * texto          -> sigue inlineándose en bloque de código (sin escritura)
//   * ruta absoluta  -> se referencia tal cual (sin reescribirla)
//   * sobre el tope  -> se omite con aviso, nunca se llena el almacén
//   * sólo adjuntos  -> el prompt no queda vacío (no lanza "No user text")
//   * los adjuntos viven FUERA de brainDir (el adapter escanea brainDir para
//     detectar conversaciones nuevas: allí no debe haber archivos sueltos)
//
// Todo se ejecuta en un tmpdir propio: jamás toca /root/.gemini ni el hub.

import { test, after } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { AntigravityAdapter } from "../providers.js";

const BACKEND_DIR = dirname(dirname(fileURLToPath(import.meta.url))); // .../backend

// 1x1 PNG (bytes no imprimibles => fuerza la rama binaria)
const PNG_1PX_B64 =
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";
const PNG_1PX = Buffer.from(PNG_1PX_B64, "base64");
const PNG_DATA_URI = `data:image/png;base64,${PNG_1PX_B64}`;

const tmpRoot = fs.mkdtempSync(join(os.tmpdir(), "aegis-agy-attach-"));
const brainDir = join(tmpRoot, "brain");
fs.mkdirSync(brainDir, { recursive: true });

/** Adapter con dirs propios y binario inexistente (aquí NO se spawncea agy). */
function makeAdapter() {
  return new AntigravityAdapter({
    binPath: join(tmpRoot, "agy-falso"),
    brainDir,
    attachmentsDir: join(tmpRoot, "attachments"),
    cwd: tmpRoot
  });
}

after(() => {
  try {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  } catch (_) {}
});

test("imagen binaria (parts/data URI) -> en disco + ruta absoluta + view_file", () => {
  const adapter = makeAdapter();
  const prompt = adapter._buildUserPrompt("sid-1", {
    parts: [
      { type: "text", text: "que es esto?" },
      { type: "file", filename: "foto.png", url: PNG_DATA_URI }
    ]
  });

  assert.match(prompt, /que es esto\?/, "el texto del usuario se conserva");
  assert.match(prompt, /\[Attached File: foto\.png \(image\/png\)\]/, "mime derivado del data URI");

  const absMatch = prompt.match(/→ (\/[^\s]+)/);
  assert.ok(absMatch, "el prompt debe llevar una ruta ABSOLUTA");
  const abs = absMatch[1];
  assert.ok(path.isAbsolute(abs), "la ruta debe ser absoluta (agy exige 'start with /')");
  assert.ok(fs.existsSync(abs), "el archivo materializado debe existir en disco");
  assert.deepEqual(fs.readFileSync(abs), PNG_1PX, "los bytes guardados son los de la imagen");
  assert.ok(abs.startsWith(join(tmpRoot, "attachments")), "vive en attachmentsDir");
  assert.ok(!abs.startsWith(brainDir + path.sep), "NUNCA dentro de brainDir");
  assert.match(prompt, /view_file/, "hay que pedirle al agente que la abra con view_file");
});

test("texto (payload.files) -> inlineado en bloque de código y SIN escritura en disco", () => {
  const adapter = makeAdapter();
  const prompt = adapter._buildUserPrompt("sid-2", {
    text: "resume",
    files: [{ name: "notas.txt", mime: "text/plain", base64: Buffer.from("hola mundo").toString("base64") }]
  });

  assert.match(prompt, /```[\s\S]*hola mundo[\s\S]*```/, "el texto va en un bloque de código");
  assert.ok(!fs.existsSync(join(tmpRoot, "attachments", "sid-2")), "un texto NO se materializa en disco");
});

test("ruta absoluta existente en parts -> se referencia tal cual", () => {
  const adapter = makeAdapter();
  const hostFile = join(tmpRoot, "adjunto_existente.txt");
  fs.writeFileSync(hostFile, "contenido");

  const prompt = adapter._buildUserPrompt("sid-3", {
    parts: [{ type: "file", filename: "adjunto_existente.txt", url: hostFile }]
  });

  assert.ok(prompt.includes(hostFile), "la ruta absoluta original aparece en el prompt");
  assert.match(prompt, /view_file/, "y se pide abrirla con view_file");
  const dir = join(tmpRoot, "attachments", "sid-3");
  assert.ok(!fs.existsSync(dir) || fs.readdirSync(dir).length === 0, "no reescribe el archivo");
});

test("mensaje SOLO con adjuntos -> prompt no vacío (no lanza 'No user text')", () => {
  const adapter = makeAdapter();
  const prompt = adapter._buildUserPrompt("sid-4", {
    parts: [{ type: "file", filename: "foto.png", url: PNG_DATA_URI }]
  });

  assert.ok(prompt.trim().length > 0, "el prompt no puede quedar vacío");
  assert.match(prompt, /Este mensaje sólo contiene archivos adjuntos\./);
  assert.match(prompt, /Attached File: foto\.png/);
});

test("adjunto sobre el tope (15MB) -> se omite con aviso y no escribe nada", () => {
  const adapter = makeAdapter();
  const huge = Buffer.alloc(15 * 1024 * 1024 + 1, 0x80); // no imprimible => binario
  const prompt = adapter._buildUserPrompt("sid-5", {
    parts: [
      { type: "text", text: "mira" },
      { type: "file", filename: "gigante.bin", url: `data:application/octet-stream;base64,${huge.toString("base64")}` }
    ]
  });

  assert.match(prompt, /OMITIDO/, "avisa del archivo omitido");
  assert.ok(!fs.existsSync(join(tmpRoot, "attachments", "sid-5")), "no escribe nada");
});

test("nombre de archivo hostil -> sanitizado (sin path traversal)", () => {
  const adapter = makeAdapter();
  const prompt = adapter._buildUserPrompt("sid-6", {
    parts: [{ type: "file", filename: "../../etc/passwd.png", url: PNG_DATA_URI }]
  });

  const absMatch = prompt.match(/→ (\/[^\s]+)/);
  assert.ok(absMatch, "debe producir una ruta absoluta");
  const abs = absMatch[1];
  assert.ok(abs.startsWith(join(tmpRoot, "attachments", "sid-6")), "queda dentro de attachmentsDir/sid-6");
  assert.ok(!abs.includes(".."), "sin path traversal");
  assert.ok(fs.existsSync(abs), "y el archivo existe");
});

test("el binario real de los tests vive fuera del repo (regresión del E2E)", () => {
  // Este fichero NO debe tocar /root/.gemini: si el adapter dejara de respetar
  // attachmentsDir, el test anterior ya habría fallado; aquí sólo fijamos que
  // el backend esté importable con el código nuevo.
  const pkgPath = join(BACKEND_DIR, "package.json");
  assert.ok(fs.existsSync(pkgPath), "backend importable desde tests/");
  assert.equal(typeof AntigravityAdapter, "function");
});
