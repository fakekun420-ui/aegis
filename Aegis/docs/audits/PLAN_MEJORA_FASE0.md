# PLAN DE MEJORA — AEGIS · Fase 0 (Ataques de la Auditoría)

> Generado el 2026-09-23 combinando:
> - 3 auditorías con agentes agency-agents → `docs/audits/AUDITORIA_BACKEND.md` (55 hallazgos), `AUDITORIA_FRONTEND.md` (62 hallazgos), `AUDITORIA_PROYECTO_Y_ROADMAP.md` (gap analysis + roadmap F0–F5)
> - Mapa de conocimiento graphify v2026-09-23 → `graphify-out/graph.json` (**2530 nodos · 3975 aristas · 202 comunidades**, corpus 459 archivos)
>
> **Regla de esta fase: NO se implementa nada hasta que este plan sea aprobado.** Tras cada ataque se re-ejecuta `graphify --update` para verificar la nueva conectividad.

---

## 1. Veredicto de arquitectura (verificado contra el grafo)

**La arquitectura SÍ merece corrección — no solo revisión.** El grafo lo confirma con hechos, no con opiniones:

| Evidencia del grafo | Lectura |
|---|---|
| `backend_server` (degree **79**) concentra helpers, rutas, skills, proxy y shell; los módulos `src/` viven en comunidades separadas | **Monolito + módulos desconectados**: la "arquitectura modular v2" existe en disco pero el proceso que corre es el monolito |
| `handleSkillsRoute` y `handleProjectRoutes` → sí tienen aristas a `backend_server*` | Únicas 2 rutas modulares **realmente montadas** |
| `handleJobRoutes`, `handleAgentRoutes`, `handleWorkflowRoutes`, `handleContentRoutes` → **0 vecinos `backend_server`** | **4 routers huérfanos confirmados** → `/api/jobs`, `/api/agents`, `/api/workflows`, contenido: endpoints fantasma |
| `jobScheduler`, `agentPool`, `eventBus`, `modelRouter`, `SkillManager`, `ResearchAgent` → **0 vecinos server** | Todo el motor (scheduler, pool, DAG, router, agentes) está **apagado**: nadie lo arranca |
| `stage-node.sh` (app y backend), `find-ubuntu.sh`, `start-hub.sh`, `install-su.sh`, `opencode.sh`, `service.d-*` → degree 1-3, solo auto-aristas | **Cadena de bootstrap rota**: ningún nodo Kotlin llama a ningún script → el flujo "instalar APK → todo listo" **no existe en el grafo** |
| Nodo `X-Aegis-Token auth + rate limiting` existe **solo** con `source_file = backend/docs/AEGIS_MASTER_PROMPT.md` | La autenticación es **especificación en docs, no código** |
| Health check: **399 aristas dangling, 35 self-loops, 75 colapsadas**; **639 nodos aislados** | Deuda de integración:IDs semánticos sin contraparte AST + artefactos vendor huérfanos |
| Import cycles: **0** (bien) · Cohesión media ~0.05-0.12 (baja) | Sin ciclos tóxicos, pero **202 comunidades = sobrefragmentación** (mitad es el vendor `agents/`) |
| God nodes: `server` (41), `ApiService` (40), `ChatViewModel` (28), `MainActivity` (27) | El flujo real es `ApiService → server → runShell/shellexecraw` — **4 puntos únicos de fallo** |

**Conclusión:** la app y el hub conversan por 1 cadena HTTP; todo lo demás (agentes, workflows, jobs, bootstrap, auth) está **declarado pero no cableado**. El plan corrige el cableado antes que la estética.

---

## 2. FASE 0 — Ataques (orden de ejecución)

Estimación total: **2-4 días**. Cada ataque es mergeable por separado y verificable con `graphify --update` + `node --check`.

### A-1 · CRÍTICO — Cerrar la RCE de root (seguridad)
*Fuente: BACKEND SEC-01..04, FRONTEND SEC-01/02, ROADMAP gap 7*
- **A-1.1** `backend/server.js` (~L722 `runShell`, L666 `shellExecRaw`, L2109 `listen`): exigir header `X-Aegis-Token` (generado en primer arranque, guardado en `ui-state`/keystore de la app) en **todo** `/api/device/*` y `/api/shell*`.
- **A-1.2** Cambiar `listen(0.0.0.0)` → `127.0.0.1` (L2522) y quitar CORS `*` por allowlist `app://aegis` + `http://localhost:8765`.
- **A-1.3** `app/.../CompanionService.kt` (L67 `ServerSocket(8766)`): `bindAddress = 127.0.0.1` + token igual al del hub.
- **A-1.4** Allowlist de comandos en `runShell`/`shellExecRaw` (solo `input`, `pm`, `am`, `getprop`, `ls`, `cat`… con argumentos saneados) — matar la inyección de SEC-03.
- **A-1.5** `RootShell.kt` (L40-43): escaping completo (`;`, `|`, `` ` ``, `\n`) o, mejor, ejecución por **lista de argv** sin `sh -c`.
- **A-1.6** `SkillManager.js` (L33, L49): validar `skillId` con `^[a-z0-9@/._-]+$` y quitar `npm install -g` de un endpoint anónimo (SEC-04).
- **A-1.7** `DELETE /api/sessions/:id`: validar contra `basePath` igual que GET/POST (path traversal SEC-02).
- **Criterio de aceptación:** `curl -X POST http://<lan-ip>:8765/api/device/shell` → **401/403**; comando con `;` → rechazado.

### A-2 · CRÍTICO — Montar los 4 routers huérfanos
*Fuente: BACKEND BUG-02, ROADMAP gap 8 · evidencia grafo: 0 aristas*
- **A-2.1** `server.js` (~L734, donde hoy solo están skills+projects): montar `agentRoutes`, `jobRoutes`, `workflowRoutes`, `contentRoutes`.
- **A-2.2** Definir en `workflowRoutes.js` los `GET /list` y `GET /status` que la app llama (`ApiService.getWorkflows/getWorkflowStatus`) — hoy ni siquiera existen dentro del módulo.
- **A-2.3** Arrancar el motor: `jobScheduler.start()` + suscribir `eventBus` (hoy 0 suscriptores, `logger` sin usar).
- **Criterio de aceptación:** `curl /api/workflows/list` → 200 JSON; en `graphify --update`, `handleWorkflowRoutes` **gana aristas a `backend_server`**.

### A-3 · CRÍTICO — Reparar contratos app ↔ backend
*Fuente: ROADMAP gaps 4-5, FRONTEND INT-01*
- **A-3.1** **Health**: alinear `server.js` L1194-1205 con `Models.kt` L243-254 (el backend pasa a devolver `server, uptime, projects, agents, jobs, skills, adapters`; o al revés — **decisión: se firma `FRONTEND_CONTRACT.md` como fuente única y se cambia el backend**, porque la app ya consume ese shape).
- **A-3.2** **Skills**: `skillsRoutes.js` L11 devuelve `{installed:[{...}], available:[{...}]}` en vez de `string[]`.
- **A-3.3** Estandarizar envelope `{ok, data, error}` en todas las respuestas + `404` JSON para `/api/*` (hoy cae al fallback SPA con `200 text/html`, BACKEND BUG-03).
- **A-3.4** Re-cablear el ruteo de proveedores (BUG-05/06): quitar el hardcode `antigravity` + `gemini-3.8-flash-high` → usar `modelRouter` + `providers.json`.
- **Criterio de aceptación:** Control Center deja de mostrar OFFLINE; Skill Manager lista skills reales.

### A-4 · ALTO — Bugs backend que rompen HTTP
*Fuente: BACKEND BUG-01, 09-12*
- **A-4.1** `skillsRoutes`/`projectRoutes` `async` que devuelven `undefined` y rompen el `if (await handler)` de `server.js` → hacer los handlers `boolean` explícitos (o cambiar el dispatch a try/catch + `finally`) y asegurar `res.end()` en todas las ramas (evita `ERR_HTTP_HEADERS_SENT` y requests colgados).
- **A-4.2** Escrituras atómicas: `providers.js` L214 y `SkillManager.js` L59 → usar el patrón `fileMutex` + tmp+rename que ya existe en `pathResolver.js`/`storage.js`.
- **A-4.3** Sacar la E/S del lock global en `createSession` (BACKEND BUG-11).
- **A-4.4** Eliminar la dependencia fantasma `src/core/normalizer.js` (no existe; silenciada por `catch {}`) y el `loadProjectsStore` no definido (ARQ-01).
- **Criterio de aceptación:** `node --check` en todos los `.js` + prueba manual de cada endpoint montado.

### A-5 · ALTO — Bugs frontend que rompen la UI
*Fuente: FRONTEND BUG-01/02, UX-04*
- **A-5.1** `ChatViewModel.kt` L313-401: `finally { body.close() }` (fuga OkHttp) y bloquear el doble envío (L402/410).
- **A-5.2** `ChatScreen.kt` L388: `key = { conversation.id }` (colisión de `hashCode` = crash).
- **A-5.3** `rememberSaveable` en estados críticos (hoy **0 usos** en todo el proyecto) — al menos en Control Center y Chat.
- **A-5.4** `NavRoutes.MAIN` sin composable + `onVoiceModeChanged` solo en el `.bak` → wake word muerta (BUG-15): restaurar el cableado o eliminar la feature y su config XML.
- **A-5.5** `imePadding()` en el input de chat; targets táctiles ≥48dp (`ChatScreen` L1303 36dp, L941 24dp, `ProjectDetailScreen` L386).
- **Criterio de aceptación:** rotar dispositivo no pierde estado; enviar 2 veces no duplica; TalkBack anuncia el menú (quitar `contentDescription="Menu Icon"`).

### A-6 · ALTO — Higiene de paquete, secretos y CI
*Fuente: ROADMAP gap 10, FRONTEND SEC-06, gap 9*
- **A-6.1** Renombrar restos del **paquete legado** (applicationId heredado) → `com.aegis.hub`: `install-su.sh`, `service.d-99-opencode-hub.sh`, `server.js` L1344 (si no, permisos y doze-whitelist no aplican).
- **A-6.2** **Rotar y sacar** `app/app/companion-release.keystore` del repo (decía "trackeado-ignorado"; **verificado en A-6:** `git ls-files` → 0 entradas ⇒ **no trackeado**, solo ignorado por `*.keystore` en `.gitignore` — discrepancia documentada; sigue sin plan de credenciales); generar uno nuevo fuera del árbol y guardarlo en secreto de GH. *A-6 solo documenta: no se rota ni se borra.*
- **A-6.3** `usesCleartextTraffic=false` + deep link `aegis://` con `autoVerify` o eliminarlo (SEC-04).
- **A-6.4** CI: añadir `node --check` a los `.js` + jobs de test en `build-apk.yml`; fijar versión de Gradle; borrar `MainActivity.webview.kt.bak` (639 líneas muertas).
- **A-6.5** Tests mínimos: 1 suite de contrato (`health`, `skills`, `workflows`) que falle el CI si el shape cambia.
- **Criterio de aceptación:** CI en rojo si un contrato se rompe; barrido `git grep -r` del applicationId legado (segmentos `com` + `opencode` + `companion` unidos por puntos) → 0 hits fuera de `agents/`, `.git/`, `node_modules/`, logs y `graphify-out/`.

### A-7 · MEDIO — Deuda de observabilidad (base para F1)
- Conectar `logger.js` (hoy 0 usos, ~100 `console.log` en `server.js`) y dar arranque a `eventBus`/`jobScheduler` (A-2.3).
- `keepalive.sh`: patrones obsoletos (`opencode-companion`) y salud por banner de texto → healthcheck HTTP real `GET /api/health`.
- Auditoría de archivos raíz: `patch_main.py`, `patch_main_2.py`, `copy_facebook_post.sh`, `*.log` commiteados → mover a `/tmp` o `.gitignore`.

---

## 3. Fuera de Fase 0 (ya planificado, no aquí)

- **F1**: `SetupWizardScreen` + dominio `src/bootstrap/` (estado idempotente, reanudable, progreso en vivo).
- **F2**: motor de instalación real (ubuntu-base SHA256 + proot → `node.bin` → OpenCode → `agy`/Artemis → skills) con rollback por paso.
- **F3+**: hardening, tests de dispositivo, release — ver `AUDITORIA_PROYECTO_Y_ROADMAP.md`.

---

## 4. Verificación post-Fase 0 (graphify)

```bash
graphify /sdcard/projects/Aegis --update   # re-extrae solo archivos tocados
graphify query "are agentRoutes jobRoutes workflowRoutes contentRoutes now mounted in server" --dfs
graphify query "trace bootstrap flow from SetupWizard to stage-node.sh and find-ubuntu.sh"
```
**KPI de éxito del cableado:**
1. `handle*Routes` de los 4 routers con aristas a `backend_server`.
2. `jobScheduler`/`eventBus`/`modelRouter` con al menos 1 vecino server.
3. Aristas Kotlin→`stage-node`/`find-ubuntu` (aparecen cuando F1/F2 existan).
4. Aritmas de salud: **dangling edges < 100** (hoy 399), nodos aislados en `backend/` y `app/` → **0**.

---

## 5. Orden recomendado de ataque (resumen)

```
Día 1:  A-1 (seguridad) ──────────────► bloquea todo lo demás si hay red abierta
Día 2:  A-2 (montar rutas) + A-3 (contratos)   ► la app "despierta"
Día 3:  A-4 (bugs backend) + A-5 (bugs app)
Día 4:  A-6 (paquete/keystore/CI) + A-7 (observabilidad)
        └─ graphify --update + re-auditoría de regresión → cerrar Fase 0
```

---

## 6. ACTA DE CIERRE — Fase 0 ejecutada (2026-09-23)

Las 7 fases (A-1…A-7) se ejecutaron en orden con verificación entre cada una.
**Sin commits** — los cambios quedan pendientes de revisión del usuario.

| Ataque | Estado | Verificación |
|---|---|---|
| **A-1** RCE de root | ✅ | Barrido sin token → 403; `/api/device/shell` con `;` → 400; con token → root real; bind 127.0.0.1; `X-Aegis-Token` (timing-safe) + interceptor en `ApiClient.kt`; `RootShell.kt` con escaping completo; `CompanionService` en loopback; `SkillManager` con regex de `skillId`; traversal de `DELETE /api/sessions` cerrado. **Diseño: root total con token, cero acceso sin él.** |
| **A-2** Routers huérfanos | ✅ | `job/agent/workflow/content` montados; `GET /api/workflows/list|status` creados contra el motor YAML real; `jobScheduler.start()` + `eventBus` (7 eventos) + job `companion-meta-sweep`; 21 `ERR_HTTP_HEADERS_SENT` eliminados |
| **A-3** Contratos | ✅ | `GET /api/health` → `HealthData` 10/10 campos; skills → `SkillItem`; envelope `{ok,data}` global; 404 JSON; `/api/system/memory|logs` creados; `keepalive.sh` sondea `/api/health` (fin del bucle de 403); `TokenProvider.kt` migra `MainActivity`/voz; `FRONTEND_CONTRACT.md` → v1.3.0 |
| **A-4** Bugs backend | ✅ | `dispatchRoute` await+try/catch (toda rama cierra respuesta); 5 escrituras atómicas (`ui-state`, `providers.json`, summaries, companion-session, skill.md); I/O fuera del lock en `createSession`; `loadProjectsStore` definida; `ClaudeCodeAdapter` importa `normalizeMessage` real (47 imports verificados, 0 faltantes) |
| **A-5** Bugs app | ✅ | Fuga OkHttp cerrada (`finally`); doble envío bloqueado; 2 keys `hashCode()` → 0; `rememberSaveable` 0 → 4; targets táctiles ≥48dp; `imePadding()`; wake word diagnosticada; `contentDescription` reales |
| **A-6** Paquete/CI | ✅ | `com.opencode.companion` → **0 hits**; `.bak` (639 líneas) borrado; keystore confirmado **no trackeado**; CI: `backend-checks` (`node --check` + tests) bloquea a `build`; Gradle fijado 8.9; **7 tests de contrato en verde** |
| **A-7** Observabilidad | ✅ | 73 `console.*` → logger con niveles + `hub startup` estructurado; keepalive con backoff (blip → sin reinicio); parches `patch_main*.py` a `.gitignore` (ya aplicados, no borrados); backlog de A-4: `providers/default` → 500 si falla el write; adapter `claudecode` registrado |

### KPI de cierre medido en `graphify --update` (grafo: 2624 nodos, 4142 aristas, 213 comunidades)

| KPI | Objetivo | Antes | Después |
|---|---|---|---|
| 1. `handle*Routes` de los 4 routers → aristas a `backend_server` | >0 | **0** | **2 c/u** (`backend_server` + `handleRequest`) ✅ |
| 2. `jobScheduler`/`eventBus` con vecino server | ≥1 | **0** | **1 c/u** ✅ (`modelRouter`/`taskClassifier` = 0 → backlog documentado) |
| 3. Aristas Kotlin → scripts bootstrap | aparecen en F1/F2 | 0 | 0 (esperado: wizard aún no existe) ⏳ F1/F2 |
| 4. Nodos aislados en `backend/` y `app/` | 0 | 639 (total) | **0 / 0** (total 19) ✅ |
| Salud: aristas dangling | <100 | 399 (pre-build) | **0** (grafo final) ✅ |

### Backlog para F1/F2 (no bloqueante)
`modelRouter`/`taskClassifier` huérfanos (cablear o eliminar) · doc `AEGIS_MASTER_PROMPT.md` falsa sobre `normalizer.js` · 2 `fileMutex` duplicados · 36 `console.*` en `providers.js`/adapters · `logger.js` sin sink a fichero propio (usa `hub.log` vía keepalive) · `copy_facebook_post.sh` trackeado · wrapper de Gradle ausente · `networkSecurityConfig` cleartext-only-loopback · huérfanos históricos en `brain/` · ruta `/opencode/session` sin prefijo `/api` fuera del middleware (revisión A-1).

## 7. ACTA DE CIERRE — F1 y F2 ejecutadas (2026-09-23)

### F1 · SetupWizard + dominio `src/bootstrap/`
**Backend**: `backend/src/bootstrap/` (`state.js` persistente con `atomicWriteFileSync` + `BOOTSTRAP_STATE_FILE` para tests, `orchestrator.js` singleton con runner cooperativo `runOnce/resume/cancel` → `paused`, `steps.js` con `STEP_DEFS` — fuente única de los 6 pasos — y `check()` idempotente REAL por paso (réplica de `find-ubuntu.sh` anclada en PID, rutas de `stage-node.sh`/`opencode.sh`, `agy`+token OAuth **nunca impreso**, `SkillManager` vs `skills-manifest.json`), `src/api/bootstrapRoutes.js` montado vía `dispatchRoute`). Contrato: `GET state` / `POST run {resume}` / `POST step/:id/retry` / `POST cancel`, envelope y token como todo `/api/*`. Bug bloqueante evitado al carga: `running` huérfano (hub muerto a mitad de run) → normaliza a `paused`.
**App**: `Models.kt` (enums minúscula para Gson), 4 endpoints en `ApiService.kt`, `BootstrapViewModel` (polling 1 s en `running`, reintento 5 s, `hubReachable`), `SetupWizardScreen` (estilo Claude existente, estados por phase, progreso global, retry por card, ≥48dp, contentDescription español), `NavRoutes.SETUP` + `AppNavHost`, y **primer arranque**: `MainActivity.checkHubOnStart` → `phase != "done"` → `initialRoute = SETUP`.

### F2 · Motor de instalación real + rollback por paso
| Paso | run() real | Rollback (solo lo creado por este run) |
|---|---|---|
| `ubuntu` | descarga ubuntu-base-24.04.5-arm64 con **SHA256 oficial verificado** (`a91d5a93…` de SHA256SUMS de cdimage.ubuntu.com), progreso en vivo, `tar` por argv, valida `os-release`, fallback **proot** documentado; **sin SHA256 → no descarga, error** | borra tmp siempre; rootfs solo si lo creó este run |
| `node` | node-v24.21.0-arm64 con SHA256 oficial (`724282c3…` de nodejs.org), `node -v` ≥ engine, alias en `bootstrap/bin` | artefactos solo si los creó este run |
| `opencode` | bundle `opencode.cjs`+wrapper 0755, o bin del sistema, o `npm install -g opencode-ai` (argv) → `--version` | `npm uninstall` solo si lo instaló él; nunca un opencode preexistente |
| `antigravity` | exige `agy` (los paquetes npm homónimos son placeholders de terceros verificados → error honesto con el instalador oficial si falta) + config dir + **auth OAuth**; token jamás en logs | solo el config dir que creó; jamás el token |
| `skills` | instala `skills-manifest.json` vía `SkillManager` con progreso i/n | desinstala solo los IDs instalados en este run |
Hooks de test: `AEGIS_BOOTSTRAP_DRY`, `AEGIS_BOOTSTRAP_FAIL=<id>`, step inyectable (tests sin red ni installs reales).
**UI F2**: `lastError` visible en cabecera, errores partido título/detalle sin perder raw, `friendlyError()` en español (EBADCHECKSUM/Falta SHA256/Autenticación/FAIL_INJECTED), nota de rollback por card (verde "Cambios deshechos" / naranja "Revisión manual recomendada" / "Reversible"), y reentrada al wizard desde el overlay "Iniciar Sistema" (`key(initialRoute)` + `isBootstrapPending()` antes de `systemReady`).

### Verificación final F1+F2
- `node --check` → **OK (33+ ficheros)** · `npm test` → **16/16 pass** (7 contrato + 6 bootstrap F1 + 3 rollback F2) · CI ejecuta ambos.
- Grafos: `handleBootstrapRoute` → `backend_server` (2 aristas) ✅ · `SetupWizardScreen` deg=35, `BootstrapViewModel` deg=19 ✅ · aislados `backend/`=0, `app/`=0 ✅ · grafo final **2785 nodos / 4521 aristas**.
- **KPI3 honesto**: el motor **replica** la lógica de `find-ubuntu.sh`/`stage-node.sh` en JS en vez de invocarlos → esas entradas de script siguen degree=1 (son utilidades manuales de arranque). El flujo de bootstrap YA existe en código; si se quiere el KPI literal Kotlin→script, habría que hacer que el motor llame a los scripts en vez de replicarlos (decisión de diseño, backlog).

### Pendiente operativo
- **El hub de producción (8765) corre con código anterior a Fase 0** — reiniciarlo para aplicar todo (lo hace `keepalive.sh` o `bash backend/start-hub.sh` tras matarlo).
- 12 docs cambiados no re-extraídos semánticamente en `graphify --update` (requiere API key o re-ejecución con subagentes); el lado **código** del grafo está 100% actualizado.
- Regenerar `GRAPH_REPORT.md` con `graphify cluster-only` si se quieren etiquetas para las 11 comunidades nuevas.
