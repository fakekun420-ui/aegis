import fs from "node:fs";
import path from "node:path";
import { getProjectAbsPath } from "./pathResolver.js";

const WF_EXTS = [".json", ".yaml", ".yml"];

export class WorkflowParser {
  parse(projectId, workflowId) {
    const base = path.join(getProjectAbsPath(projectId), "workflows");
    const jsonPath = path.join(base, `${workflowId}.json`);
    let data;
    if (fs.existsSync(jsonPath)) {
      data = JSON.parse(fs.readFileSync(jsonPath, "utf8"));
    } else {
      // Compatibilidad YAML: si no existe el .json histórico, se intenta .yaml/.yml
      const yamlPath = [".yaml", ".yml"].map(ext => path.join(base, `${workflowId}${ext}`)).find(p => fs.existsSync(p));
      if (!yamlPath) throw new Error(`Workflow not found: ${workflowId}`);
      data = this.parseYamlWorkflow(fs.readFileSync(yamlPath, "utf8"), workflowId);
    }

    // Basic validation
    if (!data || !data.id || !Array.isArray(data.steps)) throw new Error("Invalid workflow format");

    // Cycle detection logic can be added here

    return data;
  }

  // listWorkflows — lee <proyecto>/workflows/*.{json,yaml,yml} y devuelve
  // [{id, name, steps, file, format}]. Directorio ausente o sin workflows
  // válidos => [] (nunca datos inventados; los archivos ilegibles se omiten con warning).
  list(projectId) {
    const dir = path.join(getProjectAbsPath(projectId), "workflows");
    if (!fs.existsSync(dir)) return [];
    const out = [];
    for (const file of fs.readdirSync(dir).sort()) {
      const ext = path.extname(file).toLowerCase();
      if (!WF_EXTS.includes(ext)) continue;
      const stem = path.basename(file, ext);
      try {
        const raw = fs.readFileSync(path.join(dir, file), "utf8");
        const data = ext === ".json" ? JSON.parse(raw) : this.parseYamlWorkflow(raw, stem);
        if (!data || !Array.isArray(data.steps)) {
          console.warn(`[workflowParser] skip ${file}: missing steps[]`);
          continue;
        }
        out.push({
          id: String(data.id || stem),
          name: String(data.name || data.id || stem),
          steps: data.steps.length,
          file,
          format: ext === ".json" ? "json" : "yaml"
        });
      } catch (e) {
        console.warn(`[workflowParser] skip ${file}: ${e.message}`);
      }
    }
    return out;
  }

  // Parser YAML mínimo SIN dependencias (hub sin npm deps). Cubre solo el
  // subconjunto del esquema de workflow: escalares de nivel superior
  // (id:, name:) y la lista steps: con "- key: value" plano. Si el archivo
  // no encaja en ese subconjunto lanza error y el archivo se omite (no se
  // inventa contenido). YAML que ya es JSON también se acepta.
  parseYamlWorkflow(text, fallbackId = "") {
    const trimmed = String(text || "").trim();
    if (!trimmed) throw new Error("empty workflow file");
    if (trimmed.startsWith("{")) {
      const j = JSON.parse(trimmed);
      return { ...j, id: j.id || fallbackId || null };
    }

    const data = { id: null, name: null, steps: null };
    const steps = [];
    let current = null;
    let inSteps = false;
    let sawSteps = false;
    let keyIndent = null;

    const stripQuotes = v => {
      const m = String(v).match(/^(["'])(.*)\1$/);
      return m ? m[2] : v;
    };
    const assignKv = (obj, expr, indent) => {
      const m = expr.match(/^([A-Za-z_][\w-]*):\s*(.*)$/);
      if (!m) throw new Error(`unsupported YAML line: "${expr}"`);
      if (keyIndent === null) keyIndent = indent;
      else if (indent !== keyIndent) throw new Error(`unsupported nested YAML (indent ${indent} != ${keyIndent})`);
      obj[m[1]] = stripQuotes(m[2].trim());
    };

    for (const rawLine of String(text).split(/\r?\n/)) {
      if (!rawLine.trim() || rawLine.trim().startsWith("#")) continue;
      const indent = rawLine.length - rawLine.trimStart().length;
      const line = rawLine.trim();

      if (indent === 0) {
        inSteps = false;
        current = null;
        keyIndent = null;
        const m = line.match(/^([A-Za-z_][\w-]*):\s*(.*)$/);
        if (!m) continue;
        const key = m[1];
        const value = stripQuotes(m[2].trim());
        if (key === "id") data.id = value || null;
        else if (key === "name") data.name = value || null;
        else if (key === "steps") {
          sawSteps = true;
          if (value === "[]") { data.steps = []; inSteps = false; }
          else if (value === "") { inSteps = true; keyIndent = null; }
          else throw new Error(`unsupported steps value: "${value}"`);
        }
        continue;
      }

      if (inSteps && indent > 0) {
        if (line === "-" || line.startsWith("- ")) {
          current = {};
          steps.push(current);
          keyIndent = null;
          const rest = line.replace(/^-\s*/, "");
          if (rest) assignKv(current, rest, indent + 2);
        } else if (current) {
          assignKv(current, line, indent);
        } else {
          throw new Error(`unexpected YAML line outside step: "${line}"`);
        }
      }
      // Otros bloques anidados de nivel superior se ignoran (fuera del esquema)
    }

    if (!sawSteps) throw new Error("missing steps: key in YAML workflow");
    if (data.steps === null) data.steps = steps;
    data.id = data.id || fallbackId || null;
    return data;
  }
}
