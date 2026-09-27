// graphify-staleness — OpenCode V2 plugin
//
// AVISO, no bloqueo. Empuja el veredicto al contexto del sistema para que el
// agente sepa si el mapa por el que se va a orientar está al día. No puede
// impedir una escritura: eso lo hace .githooks/pre-commit, en el commit.
//
// ── Una sola fuente de verdad ────────────────────────────────────────────────
// El veredicto NO se calcula aquí. Se llama a .githooks/stale-check.py, el
// mismo script que ejecuta el hook pre-commit. Antes esta cosa llevaba su
// propio test por mtime y su propia lista de extensiones, y divergía del hook:
// con el mtime congelado por FUSE decía "al día (STALE=no)" mientras el hook
// bloqueaba el commit, y no veía ficheros nuevos de extensiones que su lista
// no knew. Dos veredictos que pueden discrepar es exactamente el problema que
// esto evita: el agente leía "al día", consultaba un mapa viejo, y el commit
// no salía. Ahora pregunta al mismo código que bloquea.
//
// El check usa md5 de contenido, no mtime: /sdcard es FUSE y su mtime miente
// (ponytail-global §2), y tanto el test de §4.3.1 como el detect_incremental
// de graphify se lo creen. El inventario es detect() de graphify, el mismo que
// usa `graphify update`.
//
// ── Ancestor search ──────────────────────────────────────────────────────────
// Antes exigía ctx.location.directory === raíz del proyecto, y como las
// sesiones abren en / o /root, el plugin no se cargaba ni se registraba para
// ninguna: por eso no había ni una línea en graphify-out/.staleness-hook.log.
// Ahora sube desde el directorio de la sesión buscando graphify-out/, igual que
// git busca .git. Si la sesión está en un subdirectorio del proyecto, dentro
// del proyecto, o en un sitio sin mapa, se comporta bien en los tres casos.
//
// V2 shape (verificado contra opencode 2.0.14):
//   default export { id, setup(ctx) }  +  ctx.session.hook("context", e => e.system.push(...))
//   fallback: ctx.tool.hook("execute.before", e => { e.tool === "bash", e.input.command })
// El V1 `export const GraphifyPlugin = async ({directory}) => ({...})` que
// escribe `graphify opencode install` lo ignora v2 en silencio — ponytail §4.3.
//
// ── Entorno ──────────────────────────────────────────────────────────────────
//   GRAPHIFY_STALENESS=off            apaga el plugin
//   GRAPHIFY_STALENESS_TTL_MS=5000    cachea el veredicto N ms (0 = sin cache)
//   GRAPHIFY_STALENESS_AUTOUPDATE=1   corre `graphify update` si está viejo
//   GRAPHIFY_HOOK_VERBOSE=1            log detail en .staleness-hook.log
//
// La TTL viene a 0 a propósito: con 60 s, un agente que edita y vuelve a
// escribir dentro de esa ventana leería un veredicto viejo. Si el coste molesta,
// se sube a mano; el hook del commit no se cachea nunca.

import { existsSync, readFileSync, readdirSync, statSync, appendFileSync } from "fs"
import { spawnSync } from "child_process"
import { join, dirname, resolve } from "path"

const MARKER = join("graphify-out", "manifest.json")
const CHECKER = join(".githooks", "stale-check.py")
const DEFAULT_TTL_MS = 0
const PROJECTS_ROOT = process.env.GRAPHIFY_PROJECTS_ROOT || "/sdcard/projects"

const off = () => ["off", "0", "false", "no"].includes(String(process.env.GRAPHIFY_STALENESS ?? "").toLowerCase())
const autoUpdate = () => ["1", "true", "yes", "on"].includes(String(process.env.GRAPHIFY_STALENESS_AUTOUPDATE ?? "").toLowerCase())
const ttlMs = () => {
  const raw = process.env.GRAPHIFY_STALENESS_TTL_MS
  const n = raw == null ? NaN : Number(raw)
  return Number.isFinite(n) && n >= 0 ? n : DEFAULT_TTL_MS
}
const mb = (b) => (b >= 1048576 ? `${(b / 1048576).toFixed(1)} MB` : `${Math.max(1, Math.round(b / 1024))} KB`)

/** Sube desde `start` buscando graphify-out/manifest.json, como git con .git. */
function findProjectRoot(start) {
  let dir
  try {
    dir = resolve(start)
  } catch {
    return null
  }
  for (;;) {
    try {
      if (existsSync(join(dir, MARKER))) return dir
    } catch { /* ilegible: sigue subiendo */ }
    const parent = dirname(dir)
    if (parent === dir) return null
    dir = parent
  }
}

/** Proyectos con mapa bajo un directorio de proyectos. */
function projectsWithMap(rootDir) {
  const out = []
  let entries = []
  try { entries = readdirSync(rootDir, { withFileTypes: true }) } catch { return out }
  for (const e of entries) {
    if (!e.isDirectory() || e.name.startsWith(".")) continue
    const d = join(rootDir, e.name)
    if (existsSync(join(d, MARKER))) out.push(d)
  }
  return out
}

/**
 * Qué proyecto está tocando la sesión, según lo que dice en sus propios
 * mensajes. Es una señal de datos, no una suposición: cuenta cuántas veces
 * aparecen las rutas absolutas de cada proyecto con mapa. Sin esto, una sesión
 * abierta en / o /root no tiene forma de saber de qué proyecto habla, y el
 * ancestor search se queda sin nada que encontrar.
 */
function projectFromMessages(messages, candidates) {
  if (!Array.isArray(messages) || !candidates.length) return null
  const tally = new Map()
  for (const m of messages.slice(-12)) {
    let text = ""
    try { text = JSON.stringify(m).slice(0, 20000) } catch { continue }
    for (const c of candidates) {
      const hits = text.split(c + "/").length - 1
      if (hits > 0) tally.set(c, (tally.get(c) || 0) + hits)
    }
  }
  if (!tally.size) return null
  return [...tally.entries()].sort((a, b) => b[1] - a[1])[0][0]
}

let toolchain = null
/** Python con graphify importable. Mismo criterio que el hook. */
function findPython(root) {
  if (toolchain !== null) return toolchain
  const sh = (cmd) => {
    try {
      const r = spawnSync("sh", ["-c", cmd], { encoding: "utf8", timeout: 20000 })
      return r.status === 0 ? (r.stdout || "").trim() : ""
    } catch { return "" }
  }
  // `command -v "$1"` con el valor en $1 en vez de interpolado en el texto del script.
  // Antes era sh(`command -v ${p}`), que meteía `p` DENTRO de la linea de comandos, y
  // `p` no es de fiar: sale del shebang del binario graphify o del fichero
  // <root>/graphify-out/.graphify_python, ambos dentro de rutas que escribe el agente.
  // Un "python3; lo que sea" ahi se ejecutaba como shell.
  const shWithArg = (script, arg) => {
    try {
      const r = spawnSync("sh", ["-c", script, "sh", arg], { encoding: "utf8", timeout: 20000 })
      return r.status === 0 ? (r.stdout || "").trim() : ""
    } catch { return "" }
  }
  const usable = (p) => {
    if (!p) return false
    try {
      if (statSync(p).mode & 0o111) return true
    } catch { /* no existe */ }
    return shWithArg('command -v "$1"', p) === p
  }
  let py = sh("command -v graphify")
  if (py) {
    const bin = sh("command -v graphify")
    try {
      const shebang = readFileSync(bin, "utf8").split("\n", 1)[0]
      if (shebang.startsWith("#!")) py = shebang.slice(2).trim()
    } catch { /* si no se puede leer, nos quedamos con el path */ }
  }
  if (!usable(py)) py = ""
  if (!py) {
    const fromOut = join(root, "graphify-out", ".graphify_python")
    if (existsSync(fromOut)) {
      const c = readFileSync(fromOut, "utf8").trim()
      if (usable(c)) py = c
    }
  }
  // Aqui `cand` viene de un array LITERAL, asi que interpolarlo no era un agujero: son
  // "python3" y "python". Aun asi se pasa por shWithArg para no dejar dos estilos
  # distintos en el mismo bloque, y para que anadir un candidto de verdad (una ruta leida
  // de algun sitio) no introduzca una inyeccion sin que se note en la revision.
  for (const cand of ["python3", "python"]) {
    if (py) break
    if (shWithArg('command -v "$1"', cand) !== cand) continue
    if (shWithArg('"$1" -c "import graphify.detect" && echo ok', cand) === "ok") py = cand
  }
  toolchain = py
  return py
}

let graphBytes = 0
function graphSize(root) {
  try {
    graphBytes = statSync(join(root, "graphify-out", "graph.json")).size
  } catch { graphBytes = 0 }
  return graphBytes
}

/** El veredicto, de la fuente de verdad. Nunca de aquí. */
function check(root) {
  const checker = join(root, CHECKER)
  if (!existsSync(checker)) {
    return { state: "error", why: `no encuentro ${CHECKER} en ${root}` }
  }
  const py = findPython(root)
  if (!py) {
    return { state: "error", why: "no encuentro un python con graphify importable" }
  }
  const t0 = Date.now()
  let r
  try {
    // nosemgrep: javascript.lang.security.detect-child-process.detect-child-process
    // Falso positivo. Esto es execve con un ARRAY de argumentos y sin shell: `root` va
    // como un argumento literal, asi que un valor con espacios, comillas o punto y coma
    // se transmite tal cual y no se interpreta. La regla marca cualquier valor que
    // llegue a child_process desde un argumento de funcion, sin distinguir si hay shell
    // detras. El caso con shell de verdad estaba en findPython(), y ese si se ha
    // arreglado de verdad.
    r = spawnSync(py, [checker, "--root", root, "--json"], { encoding: "utf8", timeout: 300000 })
  } catch (e) {
    return { state: "error", why: String(e) }
  }
  const ms = Date.now() - t0
  if (r.error) return { state: "error", why: String(r.error) }
  if (r.status === 2) {
    return { state: "error", why: (r.stderr || "").trim().split("\n").slice(-1)[0] || `el chequeo salió con ${r.status}` }
  }
  if (r.status !== 0 && r.status !== 1) {
    return { state: "error", why: `el chequeo salió con ${r.status}: ${(r.stderr || "").trim().slice(0, 200)}` }
  }
  let v
  try {
    v = JSON.parse(r.stdout)
  } catch (e) {
    return { state: "error", why: `no pude leer el JSON del chequeo: ${e}` }
  }
  return {
    state: v.stale ? "stale" : "fresh",
    root: v.root,
    indexed: v.indexed,
    changed: v.changed.length,
    deleted: v.deleted.length,
    added: v.new.length,
    blank: v.blank.length,
    unread: v.unread.length,
    samples: [...v.changed, ...v.new].slice(0, 3),
    fix: v.fix,
    checkSeconds: v.seconds,
    overheadMs: ms,
  }
}

function message(v) {
  if (v.state === "stale") {
    const extra = v.blank ? ` (+${v.blank} sin ast_hash, +${v.unread} ilegibles)` : ""
    return `[graphify] El mapa de ${v.root} esta DESACTUALIZADO (STALE=yes): ` +
      `modificados=${v.changed} borrados=${v.deleted} nuevos=${v.added}${extra}. ` +
      `Si esta tarea va a MODIFICAR codigo, ejecuta \`${v.fix}\` ANTES de editar ` +
      `(solo AST, ~20s, sin coste) y vuelve a comprobar al terminar. ` +
      `Si es solo lectura o QA, no actualices, pero orientate antes con ` +
      `\`graphify query "<pregunta>" --budget 2000\`. ` +
      `Ojo: el commit de esto lo va a bloquear el hook pre-commit con este mismo veredicto. ` +
      `NO leas graph.json entero (${mb(graphSize(v.root))}).`
  }
  if (v.state === "fresh") {
    return `[graphify] El mapa de ${v.root} esta al dia (STALE=no, ${v.indexed} ficheros ` +
      `verificados por md5). Antes de leer codigo, orientate con ` +
      `\`graphify query "<pregunta>" --budget 2000\`, \`explain\`, \`path\` o \`affected\`; ` +
      `despues lee el fichero concreto. NO leas graph.json entero (${mb(graphSize(v.root))}).`
  }
  if (v.state === "error") {
    return `[graphify] NO HE PODIDO COMPROBAR el mapa: ${v.why}. ` +
      `Trátalo como no fiable: no te orientations por el grafo hasta saber por qué.`
  }
  return null                                            // sin mapa: la regla prohibe crear uno
}

let running = false
function runUpdate(v) {
  if (running || !autoUpdate() || !v.fix) return null
  running = true
  try {
    const args = ["update", v.root]
    if (v.deleted) args.push("--force")
    const r = spawnSync("graphify", args, { encoding: "utf8", timeout: 300000 })
    return r.status === 0
      ? "Mapa actualizado automaticamente (graphify update)."
      : `graphify update fallo (rc=${r.status}); hazlo a mano con \`${v.fix}\`.`
  } catch (e) {
    return `graphify update no se pudo lanzar: ${e}`
  } finally { running = false }
}

export default {
  id: "graphify-staleness",
  async setup(ctx) {
    if (off()) return
    // Dónde está la sesión no basta: una sesión abierta en / o /root no tiene
    // ningún ancestro con graphify-out. Se resuelve en este orden, y si nada
    // cuadra no se inyecta nada: adivinar el proyecto sería peor que callarse,
    // porque el modelo se orientaría por el mapa equivocado.
    const candidates = projectsWithMap(PROJECTS_ROOT)
    const resolveRoot = (event) => {
      const env = process.env.GRAPHIFY_PROJECT
      if (env) {
        const r = findProjectRoot(env)
        if (r) return r
      }
      for (const s of [ctx.location?.directory, process.cwd()]) {
        if (!s) continue
        const r = findProjectRoot(s)
        if (r) return r
      }
      return projectFromMessages(event?.messages, candidates)
    }
    let root = resolveRoot(null)
    let logDir = root ? join(root, "graphify-out") : null
    const log = (line) => {
      if (!logDir) return
      try {
        appendFileSync(join(logDir, ".staleness-hook.log"), `${new Date().toISOString()} ${line}\n`)
      } catch { /* el log es evidencia, no función: si falla, seguimos */ }
    }
    if (!root) {
      log("RESOLVE setup sin proyecto: la sesión no está en un árbol con mapa. " +
          "Se resuelve con el primer evento (messages) o con GRAPHIFY_PROJECT.")
    }
    const ttl = ttlMs()
    let cache = null
    const verdict = (event) => {
      if (!root) {                       // el primer evento puede traer la pista
        const r = resolveRoot(event)
        if (!r) return null
        root = r
        logDir = join(root, "graphify-out")
        log(`RESOLVE desde messages: ${root}`)
      }
      const t = Date.now()
      if (cache && ttl > 0 && t - cache.t < ttl) return cache.v
      try {
        cache = { t, v: check(root) }
      } catch (e) {
        cache = { t, v: { state: "error", why: String(e) } }
      }
      return cache.v
    }

    const told = new Map()   // sessionID -> ultimo veredicto anunciado
    const once = (sessionID) => (v) => {
      if (told.get(sessionID) === v.state) return
      told.set(sessionID, v.state)
      log(`INJECT session=${sessionID} verdict=${v.state}` +
        (v.state === "stale" ? ` changed=${v.changed} new=${v.added} deleted=${v.deleted} check=${v.checkSeconds}s total=${v.overheadMs}ms` : ""))
      const extra = v.state === "stale" ? runUpdate(v) : null
      const text = [message(v), extra].filter(Boolean).join(" ")
      return text
    }

    try {
      // OJO: el await es imprescindible. Sin el, un rechazo de session.hook se
      // convierte en unhandled rejection y el fallback de abajo nunca se ejecuta.
      const reg = await ctx.session.hook("context", (event) => {
        try {
          const v = verdict(event)
          if (!v) return
          const text = once(event.sessionID)(v)
          if (text) event.system.push({ type: "text", text })
        } catch (e) { log(`CTX error ${e}`) }
      })
      return () => { try { reg?.dispose?.() } catch {} }
    } catch (e) {
      log(`session.hook no disponible (${e}); uso tool.hook`)
      try {
        await ctx.tool.hook("execute.before", (event) => {
          try {
            if (event.tool !== "bash") return
            const v = verdict(null)
            if (!v) return
            const text = once("bash")(v)
            if (text) event.input.command = `echo ${JSON.stringify(text)} ; ${event.input.command}`
          } catch (e) { log(`TOOL error ${e}`) }
        })
      } catch (e2) { log(`tool.hook tampoco disponible: ${e2}`) }
    }
  },
}
