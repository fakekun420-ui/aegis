// SkillManager.js — Skill Installation and Management
// Phase 3 / F3: allowlist (H-14) — SÓLO los ids de src/skills/catalog.json
// pueden instalarse; la validación A-1 (regex) se mantiene ADICIONALMENTE.

import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import crypto from "node:crypto";
import { spawn, execFile } from "node:child_process";
import { fileURLToPath } from "node:url";
import { atomicWriteFileSync, atomicReadFileSync } from "../core/storage.js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const CATALOG_FILE = path.join(__dirname, "catalog.json");

// Valida el skillId ANTES de interpolarlo en sh (npm install|uninstall -g <id>)
// Evita metacaracteres de shell, espacios, path traversal ("..") y longitudes abusivas.
const SKILL_ID_RE = /^[a-z0-9@][a-z0-9@/._-]*$/;
function assertValidSkillId(skillId) {
  const id = String(skillId ?? "");
  if (!id || id.length > 128 || id.includes("..") || !SKILL_ID_RE.test(id)) {
    throw new Error(
      `invalid skillId "${id.slice(0, 80)}" — must match ^[a-z0-9@][a-z0-9@/._-]*$, max 128 chars, no ".."`
    );
  }
  return id;
}

function errWithCode(message, code) {
  const e = new Error(message);
  e.code = code;
  return e;
}

// ---- Catálogo / allowlist (H-14) ------------------------------------------
// Fuente única de verdad de lo INSTALABLE. `version` y `sha256` son opcionales:
// si `sha256` está, install() descarga el tarball con `npm pack`, calcula su
// SHA-256 real y lo compara ANTES de instalar (mismatch => EBADCHECKSUM).
//
// POR QUÉ SÓLO opencode-mem trae sha256 (y graphify NO) — verificado, no inventado:
//  - opencode-mem@2.26.0: existe como paquete npm canónico con ese nombre y
//    descripción. El sha256 se calculó con `npm pack opencode-mem@2.26.0` +
//    `sha256sum` sobre el tarball DESCARGADO DE VERDAD (ver catálogo). Va junto
//    a `version` pinneada: sin versión fija, el "latest" cambia y el hash quedaría
//    obsoleto (la instalación fallaría con EBADCHECKSUM — actualiza catálogo+hilo).
//  - graphify: el artefacto REALMENTE instalado en este equipo es la uv tool
//    `graphifyy` (/root/.local/share/uv/tools/graphifyy, bin /root/.local/bin/graphify),
//    NO un paquete npm. El paquete npm `graphify` existe pero es otro proyecto
//    ("RGG (Random Graph Generator)", emeraldarrow/Graphify) — fijar su hash
//    certificaría un paquete DISTINTO del descrito en el catálogo => hash engañoso.
//    Por eso graphify queda SIN sha256: no hay un artefacto de registro canónico
//    cuyo hash podamos verificar de verdad. (Hallazgo reportado en F3.)
//
// Nota: `npm view <id> dist.integrity` devuelve sha512 del tarball (algoritmo
// distinto del campo `sha256` exigido por el contrato); por eso la verificación
// descarga el propio tarball y hashea LOCALMENTE con sha256 en vez de confiar
// en un string de integridad que además no podemos llamar "sha256".
function loadCatalog() {
  let raw;
  try {
    raw = JSON.parse(fs.readFileSync(CATALOG_FILE, "utf8"));
  } catch (_) {
    return []; // catálogo ausente/ilegible => allowlist VACÍA (nadie instalable)
  }
  if (!Array.isArray(raw)) return [];
  return raw
    .filter(e => e && typeof e === "object" && typeof e.id === "string" && e.id)
    .map(e => ({
      id: e.id,
      name: typeof e.name === "string" && e.name ? e.name : e.id,
      description: typeof e.description === "string" ? e.description : null,
      // se conservan CRUDOS: install() valida el formato y falla con EVERIFY si
      // están presentes pero mal formados (nunca se degrada en silencio).
      version: typeof e.version === "string" && e.version ? e.version : null,
      sha256: typeof e.sha256 === "string" && e.sha256 ? e.sha256 : null
    }));
}

// Exportado para que el manifiesto del bootstrap (steps.js) valide sus ids
// contra ESTA misma allowlist (un solo catálogo, dos consumidores).
export function loadSkillCatalog() {
  return loadCatalog();
}

// npm pack promisificado SIN shell (argv en lista), no-lanzador.
function npmPack(spec, destDir) {
  return new Promise(resolve => {
    execFile(
      "npm",
      ["pack", spec, "--pack-destination", destDir],
      { timeout: 120000, maxBuffer: 4 * 1024 * 1024, env: { ...process.env, HOME: "/root" } },
      (err, stdout, stderr) => resolve({
        code: err ? (typeof err.code === "number" ? err.code : 1) : 0,
        stdout: String(stdout || ""),
        stderr: String(stderr || ""),
        error: err ? String(err.message || err) : null
      })
    );
  });
}

export class SkillManager {
  listInstalled() {
    const skills = [];
    const configDir = "/root/.config/opencode/skills";
    const binDir = "/root/.local/bin";
    
    if (fs.existsSync(configDir)) {
      const entries = fs.readdirSync(configDir);
      for (const e of entries) {
        if (e.endsWith(".json")) skills.push(e.replace(".json", ""));
      }
    }
    
    // Check known binaries
    for (const k of loadCatalog().map(e => e.id)) {
      if (fs.existsSync(path.join(binDir, k)) && !skills.includes(k)) {
        skills.push(k);
      }
    }
    return skills;
  }

  // A-3: lectura honesta de disco para el contrato SkillItem (Models.kt).
  // id/name desde lo REALMENTE instalado; version/description/enabled desde el
  // config JSON del skill si existe. `enabled` default true cuando el config no
  // trae el flag (documentado en FRONTEND_CONTRACT.md — no hay estado real de "on/off").
  listInstalledDetailed() {
    return this.listInstalled().map(id => {
      let cfg = {};
      try { cfg = this.getConfig(id) || {}; } catch (_) {}
      return {
        id,
        name: typeof cfg.name === "string" && cfg.name ? cfg.name : id,
        version: typeof cfg.version === "string" ? cfg.version : null,
        description: typeof cfg.description === "string" && cfg.description ? cfg.description : null,
        installed: true,
        enabled: typeof cfg.enabled === "boolean" ? cfg.enabled : true
      };
    });
  }

  // F3 (H-14): catálogo REAL menos lo instalado — shape SkillItem de Models.kt
  // (id, name, version?, description?, installed, enabled). Fuente: catalog.json.
  listAvailable() {
    const installed = new Set(this.listInstalled());
    return loadCatalog()
      .filter(e => !installed.has(e.id))
      .map(e => ({
        id: e.id,
        name: e.name,
        version: e.version,          // versión pineada del catálogo (null si no la hay)
        description: e.description,
        installed: false,
        enabled: true                // no existe estado real on/off de un no-instalado
      }));
  }

  getCatalogEntry(skillId) {
    return loadCatalog().find(e => e.id === skillId) || null;
  }

  // F3: verificación de contenido — descarga el tarball (`npm pack`) y compara
  // su sha256 REAL con el fijado en el catálogo. Devuelve la ruta del tarball
  // verificado (que es lo que se instala: el bytes que hasheamos).
  async _verifyPackage(entry, spec) {
    if (!/^[0-9a-f]{64}$/.test(String(entry.sha256))) {
      throw errWithCode(
        `sha256 inválido en src/skills/catalog.json para "${entry.id}" (se esperan64 hex) — no se instala nada sin verificación`,
        "EVERIFY"
      );
    }
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "aegis-skill-"));
    const packed = await npmPack(spec, dir);
    if (packed.code !== 0) {
      try { fs.rmSync(dir, { recursive: true, force: true }); } catch (_) {}
      throw errWithCode(
        `no se pudo descargar el tarball de ${spec} para verificarlo (npm pack code=${packed.code}): ${String(packed.stderr || packed.error || "").trim().slice(-240)}`,
        "EVERIFY"
      );
    }
    let tgz = null;
    try {
      const files = fs.readdirSync(dir).filter(f => f.endsWith(".tgz"));
      if (files.length !== 1) {
        throw errWithCode(`npm pack devolvió ${files.length} tarballs para ${spec} (esperado 1)`, "EVERIFY");
      }
      tgz = path.join(dir, files[0]);
      if (!/^[A-Za-z0-9._/-]+$/.test(tgz)) {
        throw errWithCode(`ruta de tarball no segura: ${tgz.slice(0, 120)}`, "EVERIFY");
      }
      const sum = crypto.createHash("sha256").update(fs.readFileSync(tgz)).digest("hex");
      if (sum !== entry.sha256) {
        const msg =
          `EBADCHECKSUM: sha256 real de ${spec} = ${sum}, pero el catálogo fija ${entry.sha256} ` +
          `(el paquete publicado cambió respecto a la versión verificada — si es una actualización legítima, ` +
          `revisa el diff del paquete y actualiza src/skills/catalog.json; si no, NO lo instales)`;
        try { fs.rmSync(dir, { recursive: true, force: true }); } catch (_) {}
        throw errWithCode(msg, "EBADCHECKSUM");
      }
      return { tgz, dir };
    } catch (e) {
      try { fs.rmSync(dir, { recursive: true, force: true }); } catch (_) {}
      if (e && (e.code === "EBADCHECKSUM" || e.code === "EVERIFY")) throw e;
      throw errWithCode(`falló la verificación de ${spec}: ${String((e && e.message) || e).slice(0, 200)}`, "EVERIFY");
    }
  }

  // F3: ASÍNCRONA — valida allowlist (sync, se rechaza antes de tocar red) y,
  // si el catálogo trae sha256, verifica el tarball ANTES de instalar.
  // Devuelve {stdout, stderr, promise} igual que antes (los consumidores hacen await).
  async install(skillId) {
    const id = assertValidSkillId(skillId);           // A-1 (regex) — sync
    const entry = this.getCatalogEntry(id);           // allowlist (H-14) — sync
    if (!entry) {
      const allowed = loadCatalog().map(e => e.id).join(", ") || "(catálogo vacío)";
      throw errWithCode(
        `skill "${id}" fuera del catálogo allowlist (src/skills/catalog.json) — sólo se pueden instalar: ${allowed}`,
        "ALLOWLIST"
      );
    }

    let spec = entry.version ? `${id}@${entry.version}` : id;
    if (!/^[a-z0-9@][a-z0-9@/._-]*(@[A-Za-z0-9._+-]+)?$/.test(spec)) {
      throw errWithCode(`spec de npm no seguro en catálogo para "${id}"`, "EVERIFY");
    }

    let verifyDir = null;
    if (entry.sha256 != null) {
      const v = await this._verifyPackage(entry, spec);
      verifyDir = v.dir;
      spec = v.tgz;                                     // instalamos LOS BYTES que hasheamos
    }

    const cleanup = () => { if (verifyDir) { try { fs.rmSync(verifyDir, { recursive: true, force: true }); } catch (_) {} } };

    const p = spawn("sh", ["-c", `npm install -g ${spec}`], { env: { ...process.env, HOME: "/root" } });

    return {
      stdout: p.stdout,
      stderr: p.stderr,
      promise: new Promise((resolve, reject) => {
        p.on("error", e => { cleanup(); reject(e); });
        p.on("close", code => {
          cleanup();
          if (code === 0) resolve();
          else reject(new Error(`Install failed with code ${code}`));
        });
      })
    };
  }

  uninstall(skillId) {
    // Devuelve (no lanza) la promesa rechazada: skillsRoutes.js sólo captura errores dentro de .catch()
    let id;
    try { id = assertValidSkillId(skillId); } catch (e) { return Promise.reject(e); }
    // Uninstall NO está restringido al catálogo a propósito: sirve también para
    // retirar skills instalados por versiones anteriores (la allowlist protege la
    // INSTALACIÓN —entrada de paquetes—, no la limpieza local).
    const cmd = `npm uninstall -g ${id}`;
    return new Promise((resolve, reject) => {
      const p = spawn("sh", ["-c", cmd], { env: { ...process.env, HOME: "/root" } });
      p.on("close", code => {
        if (code === 0) resolve();
        else reject(new Error(`Uninstall failed with code ${code}`));
      });
    });
  }

  getConfig(skillId) {
    const configPath = `/root/.config/opencode/skills/${skillId}.json`;
    return atomicReadFileSync(configPath, {});
  }

  updateConfig(skillId, config) {
    const configPath = `/root/.config/opencode/skills/${skillId}.json`;
    atomicWriteFileSync(configPath, config);
    return config;
  }
}
