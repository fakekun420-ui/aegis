# AUDITORÍA GLOBAL DEL PROYECTO AEGIS Y ROADMAP HACIA "CONTENEDOR COMPLETO"

**Fecha:** 2026-09-23
**Equipo:** Software Architect · Product Manager · DevOps Automator · Senior Project Manager · Testing Reality Checker · Security Architect
**Alcance leído:** README, .hub/project.json, .github/workflows/build-apk.yml, docs/ARCHITECTURE.md, docs/QA_REPORT.md, backend/docs/* (7 documentos), app/README.md, app/docs/*, scripts de arranque (start-hub, keepalive, stage-node, service.d, find-ubuntu, opencode, install-su, assets/stage-node), backend/server.js, backend/src/**, app/app/src/main/kotlin/com/aegis/hub/**
**Ignorado:** graphify-out/, *.log, node_modules/, agents/ (vendor), .git/, *.apk
**Modo:** solo lectura. No se modificó ningún código.

**Convención:** `[E]` = EVIDENCIADO (verificado en código/archivo:línea). `[NE]` = NO EVIDENCIADO (afirmación de docs sin soporte, o contradicha por código).

---

## 1. RESUMEN EJECUTIVO

**Puntuación: 4 / 10**
**Veredicto: NEEDS WORK — no apto como "herramienta real para revivir el root" ni como contenedor autoinstalable.** `[E]`

Aegis tiene una **base operativa real pero estrecha**: el hub Node (chat/proxy a OpenCode y Antigravity), el watchdog `keepalive.sh`, el hook de boot Magisk, el overlay de recuperación con `RootShell` y el motor de skills/proyectos montados funcionan y están razonablemente construidos `[E]`. Eso justifica los 4 puntos.

Los 6 puntos restantes se pierden por tres razones demostrables:

1. **La promesa del "contenedor completo" tiene 0% de automatización.** No existe wizard de primera ejecución, no existe instalador de Ubuntu chroot, no existe descarga/instalación de Node/OpenCode/Antigravity/Artemis. Todo presupone el entorno ya montado a mano `[E]` (§4).
2. **Las 4 pantallas nuevas de la Fase 11 están conectadas a contratos que el backend no cumple** → en runtime muestran OFFLINE/cero o fallan al parsear `[E]` (§2, H-01/H-02/H-03).
3. **La documentación de QA se aut-certifica con puntuaciones perfectas que el código contradice** → el QA_REPORT.md y AUDITORIA_INTEGRAL_QA.md no son utilizables como evidencia `[NE]` (H-08).

**Reality Checker:** los informes previos dicen "APROBADO AL 100% / 0 errores / listo para producción". Perfect score sin evidencia reproducible = señal roja, no luz verde. Estado por defecto: **NEEDS WORK**.

---

## 2. ESTADO REAL POR FASE (verificado / no verificado)

| Fase | Afirma el doc | Veredicto | Evidencia |
|---|---|---|---|
| 0 Auditoría | ✅ completa (`.hub/project.json:7`) | **VERIFICADO** (commit existe) | `git log`: commits de fases 0–11 presentes |
| 1 Modularización | ✅ Strangler Fig | **VERIFICADO** | `backend/src/adapters/*`, `src/core/*` existen y totalizan 2483 líneas |
| 2 Arquitectura | ✅ | **VERIFICADO** | `pathResolver.js`, `storage.js`, `normalizer.js`, `providerManager.js` presentes |
| 3 Adaptadores + Skills | ✅ | **VERIFICADO** | `src/api/skillsRoutes.js:8-67` montado en `server.js:734` |
| 3.5 Rutas skills/projects | ✅ | **VERIFICADO** | `server.js:734-735` monta `handleSkillsRoute` y `handleProjectRoutes` |
| 4 Project management | ✅ | **VERIFICADO** | `src/api/projectRoutes.js:11-60` (`/api/workspace/projects`, init, state, index, path) |
| 5 Sistema de agentes | ✅ "API de agentes" | **NO VERIFICADO (muerto en runtime)** | `agentRoutes.js` existe pero **no se importa** en `server.js` (solo imports en líneas 20–21) → `GET /api/agents` = 404 |
| 6 Workflow engine | ✅ "API de workflows" | **NO VERIFICADO (muerto + incompleto)** | `workflowRoutes.js` no importado en `server.js`; además solo define `POST /run` (líneas 4–5): **no existen** `GET list`, `GET status`, `DELETE cancel`, `GET events` que la app llama (`ApiService.kt:120-130`) |
| 7 Model routing | ✅ | **NO VERIFICADO** | `taskClassifier.js`/`modelRouter.js` existen pero no hay `X-Provider: auto` ni invocación desde `server.js` (grep `modelRouter` → 0 matches en server.js) |
| 8 Job scheduler | ✅ | **NO VERIFICADO (muerto)** | `jobRoutes.js` no importado; `jobScheduler` nunca hace `start()` en `server.js` |
| 9 Content plugin | ✅ | **NO VERIFICADO (muerto)** | `contentRoutes.js` no importado en `server.js` |
| 10 Seguridad/observabilidad | ✅ token + rate limit + logger + health completo | **NO VERIFICADO** | `X-Aegis-Token` y rate limit solo existen en `AEGIS_MASTER_PROMPT.md:366-367`; `src/core/logger.js:20` define `createLogger` pero **nadie lo importa**; `/api/system/logs` y `/api/system/memory` **no existen** en `server.js` |
| 11 App Android (4 pantallas) | ✅ "Phase 11 complete" + QA PASSED | **VERIFICADO en compile / NO VERIFICADO en runtime** | Pantallas y rutas existen (`AppNavHost.kt:163-175`), pero contratos rotos: ver H-01, H-02, H-03 |
| README "Backend phases 1-10 complete" | `README.md:32` | **PARCIALMENTE FALSO** | Solo 0–4 están cableados; `.hub/project.json:6-13` solo declara fases 0–4 (el propio JSON contradice al README) |
| QA 9/9 PASSED, 0 bugs | `docs/QA_REPORT.md:11-45` | **NO EVIDENCIADO / CONTRADICHO** | TEST-02 exige indicador ONLINE (`AEGIS_BUILD_AND_QA.md:254`), pero `HealthData.server` no existe en la respuesta real → `ControlCenterScreen.kt:98` siempre OFFLINE |

---

## 3. HALLAZGOS GLOBALES (severidad · archivo:línea)

| ID | Severidad | Hallazgo | Evidencia |
|---|---|---|---|
| **H-01** | **CRITICAL** | Contrato de health roto: backend devuelve `data{status,runtime,a11ySocket,agy,permissions,opencode}`; la app espera `server,port,uptime,memory,workspace,projects,agents,jobs,skills,adapters` → Control Center **siempre OFFLINE con métricas en 0** | `server.js:1194-1205` vs `Models.kt:243-254`, `ControlCenterScreen.kt:98,144,152,157` |
| **H-02** | **CRITICAL** | Contrato de skills roto: `GET /api/skills` devuelve un **array de strings**, la app espera objeto `{installed:[{id,name,installed,enabled}],available:[…]}` → Gson falla, Skill Manager inutilizable | `skillsRoutes.js:11` + `SkillManager.js:10-30` vs `Models.kt:261-271`, `SkillManagerViewModel.kt:26` |
| **H-03** | **HIGH** | Pantallas conectadas a endpoints inexistentes: `/api/system/logs`, `/api/system/memory`, `/api/agents*`, `/api/workflows*`, `/api/jobs*` → 404 silenciosos | `ApiService.kt:71-75,110-137` vs grep de rutas en `server.js` (sin matches) |
| **H-04** | **CRITICAL** (seguridad) | Hub escucha en `0.0.0.0:8765` **sin autenticación**, con CORS `*`, exponiendo `POST /api/device/shell` = **ejecución de comandos arbitrarios con root** desde cualquier host de la red | `server.js:2522`, `server.js:722-735` (sin middleware de auth), `server.js:485`, `server.js:2109-2117` |
| **H-05** | **HIGH** | Fases 5/6/8/9 = código huérfano: los route handlers no se montan nunca; schedulers/pools no se arrancan | `server.js:20-21,734-735` (solo skills+projects); grep `handleAgentRoutes|handleWorkflowRoutes|handleJobRoutes|handleContentRoutes` en server.js → 0 |
| **H-06** | **HIGH** | **Cero tests**: sin `*.test.js`, sin `src/test`, sin `*Test.kt`, sin script `test` en package.json; CI solo compila APK | `package.json:6-9`, `build-apk.yml:19-26`, glob `backend/**/*test*` y `app/**/*Test*.kt` → 0 archivos |
| **H-07** | **HIGH** | Renombre de paquete incompleto: instalador y boot scripts apuntan al **paquete legado** (applicationId heredado) → `pm grant`, doze-whitelist y a11y **no aplican a `com.aegis.hub`** | `install-su.sh:34-66`, `service.d-99-opencode-hub.sh:13-14`, `server.js:1344`, `app/docs/FRONTEND_CONTRACT.md:4,146` |
| **H-08** | **HIGH** (proceso) | QA/autoauditorías con scores perfectos no reproducibles y contradichos por código; adoptarlos como "producción lista" es peligroso | `docs/QA_REPORT.md:11,29-39,45` ("Ninguno detectado", "listo para producción"), `AUDITORIA_INTEGRAL_QA.md:17,173` |
| **H-09** | **MEDIUM** | Deriva documental: paths y packages viejos (`opencode-companion`, `app/src/main/java/com/aegis/ui/...`, applicationId legado) vs reales (`app/app/src/main/kotlin/com/aegis/hub/...`) | `AEGIS_PHASE11_ANDROID.md:40-47,432`, `app/README.md:1,13`, `BACKEND_ARCHITECTURE.md:1,12` |
| **H-10** | **MEDIUM** | Contrato dual de `POST /opencode/session/:id/message` documentado como `Envelope<Message>` pero la firma Retrofit devuelve `Message` plano; duplicado `@GET("api/skills")` con dos tipos de respuesta distintos | `ApiService.kt:37-38` (`Envelope<SkillListResponse>`) vs `:78-79` (`Response<SkillsResponse>`); `BACKEND_ARCHITECTURE.md:139-181` |
| **H-11** | **MEDIUM** | CI sin quality gates: no corre tests, no lint, no análisis de seguridad, no build release firmado (aunque `build.gradle.kts:18-35` ya soporta keystore en CI) | `build-apk.yml` completo (27 líneas) |
| **H-12** | **MEDIUM** | Observabilidad inexistente en la práctica: `logger.js` no se usa; logs solo en `console.log` + ficheros planos sin rotación | `src/core/logger.js:20` (0 imports), `server.js:2114` (`console.log`) |
| **H-13** | **LOW** | `stage-node.sh` duplicado byte a byte en backend y assets; `MainActivity.webview.kt.bak` (package viejo) sigue en el árbol fuente | `backend/stage-node.sh` = `app/app/src/main/assets/stage-node.sh`; `MainActivity.webview.kt.bak:1` |
| **H-14** | **LOW** | `SkillManager.install()` ejecuta `npm install -g <skillId>` sin allowlist ni verificación de integridad → inyección de paquete vía UI de skills | `SkillManager.js:33` |

---

## 4. GAP ANALYSIS HACIA EL "CONTENEDOR COMPLETO"

Flujo objetivo: **instalar APK → abrir → seguir menú de configuración → todo listo**.

| # | Requisito del objetivo | Estado hoy | Evidencia |
|---|---|---|---|
| 4.1 | **Wizard/first-run en la app** | **NO EXISTE** `[E]`. No hay pantalla de setup/onboarding ni flag `first_run`/`onboarding` en toda la app. Lo más parecido es `NativeOfflineOverlay` (`MainActivity.kt:84-121`): overlay de *recuperación* que dice "Sistema desconectado" y botón "Iniciar Sistema" — asume todo ya instalado | grep `wizard|onboarding|setup|first_run|onboarding` en app → solo `.bak` y comentarios "terminal wizard" en `ChatScreen.kt:556,686` |
| 4.2 | **Termux interno / motor de shell propio** | **PARCIAL** `[E]`: no hay Termux embebido ni shell engine; hay `RootShell.exec()` (su → fallback sh, timeout 15s) que es la primitiva correcta para construirlo. `stage-node.sh` existe como asset pero **no se extrae/ejecuta desde Kotlin** (grep en app → 0 usos) | `RootShell.kt:9-35`, `assets/stage-node.sh:1-12`, grep `stage-node` en app → 0 |
| 4.3 | **Descarga/instalación de Ubuntu chroot** | **NO EXISTE** `[E]`: cero matches de `proot|rootfs|debootstrap|ubuntu-base|tar.xz` en todo el repo (salvo doc de backup vendor). `find-ubuntu.sh` **solo localiza** un chroot ya montado (exige `/usr/bin/node` + `server.js` + `bash --login`) | grep global `proot|rootfs|debootstrap` → 0; `find-ubuntu.sh:1-14` |
| 4.4 | **Node.js (node.bin)** | **PARCIAL** `[E]`: `stage-node.sh` **copia** un node ya existente en el chroot hacia `/sdcard`. No lo descarga ni lo instala. `opencode.sh` solo hace wrapper a `node.bin` | `stage-node.sh:4-8`, `opencode.sh:1-2` |
| 4.5 | **OpenCode** | **PARCIAL** `[E]`: `keepalive.sh` lanza `OPENCODE_BIN` de una ruta Termux fija con fallbacks, y `/api/system/start` lo relanza; pero **no lo instala ni verifica versión** | `keepalive.sh:17,31-36,117`, `server.js:1324-1346` |
| 4.6 | **Antigravity/Artemis auth** | **NO EXISTE en bootstrap** `[E]`: el health solo hace `agy --version` (`server.js:1144-1153`). No hay flujo de login, ni instalación de Artemis, ni verificación de credenciales. Los docs de QA asumen `python -m artemis auth login` manual | `AEGIS_BUILD_AND_QA.md:106-109` (paso manual) |
| 4.7 | **Skills y plugins** | **PARCIAL/ROTO** `[E]`: endpoint de install existe y hace streaming SSE (`skillsRoutes.js:15-39`), pero el listado rompe el contrato (H-02) y `install` es `npm install -g` sin allowlist (H-14) | `SkillManager.js:32-46` |
| 4.8 | **Verificación de root/Magisk** | **INDIRECTA** `[E]`: el health reporta `uid/isRoot` (`server.js:1178-1187`); `RootShell` prueba `su` y cae a `sh`. **No hay pantalla que muestre "root OK / root denegado" ni guía para concederlo** | `server.js:1183-1185`, `RootShell.kt:11` |
| 4.9 | **Permisos, fallos, reintentos, progreso, reanudación** | **INSUFICIENTE** `[E]`: el overlay tiene spinner + timeout de 45s + código de error (`MainActivity.kt:188-212`) — buen patrón local — pero **no hay estado de instalación persistido, ni reintentos con backoff, ni idempotencia por paso, ni progreso por etapas, ni logs visibles de cada paso** | `MainActivity.kt:188-212`; grep `first_run|hasCompletedSetup` → 0 |
| 4.10 | **Rol del backend en el bootstrap** | **SEMILLA EXISTENTE** `[E]`: `GET /api/system/status` (ownership/ready), `POST /api/system/start` (relanza keepalive+opencode de forma non-destructive con `steps[]`), `GET /api/system/health` (subsistemas). **Faltan** `GET /api/setup/state`, `POST /api/setup/step/:id`, `GET /api/setup/events` (SSE) | `server.js:1215,1300-1361,1106-1205`; grep `/api/setup|bootstrap|install` en backend → 0 |

**Conclusión del gap:** hoy el sistema es un **"hub que arranca un entorno ya existente"**, no un contenedor que **cree** ese entorno. Del flujo objetivo, la parte "instalar APK" está (partially, con scripts rotos por paquete), la parte "seguir menú de configuración" **no existe**, y la parte "todo listo" depende de trabajo manual previo irreproducible.

---

## 5. ARQUITECTURA PROPUESTA DEL SISTEMA DE BOOTSTRAP

### 5.1 Componentes

```
┌─────────────────────────── APP ANDROID (com.aegis.hub) ───────────────────────────┐
│ SetupWizardScreen (first-run, una sola Activity/Composable, N pasos)             │
│   ├─ SetupViewModel  → máquina de estados persistida (DataStore: setup_state.json)│
│   ├─ ProgressPane    → paso actual, % global, log en vivo (SSE o polling 1s)      │
│   └─ ResumePane      → "Reanudar instalación" si quedó a medias                  │
│ BootstrapClient (Retrofit) → /api/setup/*                                        │
│ RootShell (ya existe) → extracción de assets, su, chmod, chroot                  │
└────────────────────────────────────┬──────────────────────────────────────────────┘
                                     │ HTTP 127.0.0.1:8765 + X-Aegis-Token
┌────────────────────────────────────▼──────────────────────────────────────────────┐
│ BACKEND: src/bootstrap/ (nuevo dominio, aislado como plugins/content)             │
│   ├─ setupState.js     → idempotente: {stepId: pending|running|done|failed, try}  │
│   ├─ setupSteps.js     → registro de pasos con validate() y apply()               │
│   ├─ installer/                                                                 │
│   │   ├─ rootCheck.js      → probe su/uid, guía de concesión (Magisk/KernelSU)    │
│   │   ├─ chrootInstaller.js→ descarga ubuntu-base-*.tar.gz + SHA256 + proot/chroot│
│   │   ├─ nodeInstaller.js  → descarga node aarch64 + checksum → node.bin          │
│   │   ├─ opencodeInstaller.js → npm/binary + health :4096                         │
│   │   └─ agyInstaller.js   → agy + artemis auth login --check                     │
│   ├─ download.js       → streaming con % (bytes recibidos/totales), reanudable     │
│   └─ setupRoutes.js    → GET state, POST run/:step, GET events (SSE), POST retry   │
│ MONTADO en server.js junto a handleSkillsRoute/handleProjectRoutes                │
└───────────────────────────────────────────────────────────────────────────────────┘
```

**Decisiones y trade-offs (ADR-001 resumido):**
- **Opción A (elegida): backend-orquestado.** El wizard solo pinta; el backend ejecuta pasos. *Contra:* el backend necesita estar vivo (pero ya hay `keepalive.sh` + overlay de recuperación que lo garantizan). *A favor:* reanudación survive a rotación de la app, SSE reutilizable, testable con node puro.
- **Opción B (descartada): todo en Kotlin.** Más simple a corto progreso, pero duplica la lógica ya escrita en shell/JS y hace imposible el QA headless.
- **chroot real vs proot:** empezar con **proot como fallback y chroot real como preferido** (root disponible). proot permite degradar en dispositivos sin root, pero tiene penalización de rendimiento y límites en montajes — documentar como ADR-002.

### 5.2 Flujo first-run

1. **Primer arranque** → `SetupViewModel` lee `setup_state.json`; si no existe → `SetupWizardScreen` (nunca se muestra dos veces salvo "Re-ejecutar setup").
2. **Paso 0 · Diagnóstico**: `GET /api/setup/state` + `RootShell("id -u")` → checklist verde/rojo: root, chroot, node, opencode:4096, agy, artemis, skills.
3. **Paso 1 · Root**: si `uid != 0` → instrucciones específicas por gestor (Magisk/KernelSU) + botón "Reintentar" (poll cada 2s, máx. 60s).
4. **Paso 2 · Ubuntu**: descarga `ubuntu-base` con **SHA256 verificado**, extracción a `/data/local/chroot/ubuntu` (o `/sdcard/projects/.aegis-chroot`), montajes (`/sdcard/projects`, `/proc`, `/dev`), registro de ancla tipo `find-ubuntu.sh`.
5. **Paso 3 · Node**: descarga node aarch64 → `node.bin` + `chmod 755` (reutiliza `stage-node.sh`).
6. **Paso 4 · Hub + OpenCode**: instala/actualiza opencode, lanza `keepalive.sh`, espera `GET /api/system/status {ready:true}`.
7. **Paso 5 · Antigravity/Artemis**: instala `agy`, `artemis auth login` guiado (deep-link/QR), `agy --version` + health.
8. **Paso 6 · Skills/plugins**: instala `graphify`, `opencode-mem` desde allowlist con checksum (cierra H-14).
9. **Paso 7 · Validación final**: `GET /api/setup/state` todo `done` → marca `setup_completed=true` → entra al hub normal.
10. **Cualquier fallo**: estado `failed` con `retryable:true`, botón "Reintentar paso" → el motor **solo re-ejecuta pasos no `done`** (idempotencia por paso + validación previa: si `validate()` ya pasa, no re-ejecuta `apply()`).

---

## 6. ROADMAP PRIORIZADO

### F0 — Quick wins: sanear la verdad (esfuerzo **S** · 2–4 días)
- **Objetivo:** eliminar mentiras de contrato yhuecos de seguridad baratos antes de construir encima.
- **Entregables:**
  - `server.js`: montar `handleAgentRoutes`, `handleWorkflowRoutes`, `handleJobRoutes`, `handleContentRoutes` (4 líneas, patrón `server.js:734-735`).
  - `server.js`: `X-Aegis-Token` en memoria + rate limit 100 req/min/IP (spec ya escrita en `AEGIS_MASTER_PROMPT.md:366-367`); bind por defecto `127.0.0.1` con flag `--lan`.
  - Contratos: `GET /api/system/health` incluye bloque `aegis{server,port,uptime,memory,workspace,projects,agents,jobs,skills,adapters}` (manteniendo el payload legacy), y `GET /api/skills` devuelve `{installed,available}` compatibles con `Models.kt:243-271`.
  - Añadir `GET /api/workflows/:projectId`, `GET /api/workflows/:projectId/status`, `GET /api/system/logs`, `GET /api/system/memory`.
  - Renombre completo a `com.aegis.hub` en `install-su.sh`, `service.d-99-opencode-hub.sh`, `server.js:1344`, `app/docs/FRONTEND_CONTRACT.md`.
  - CI: job `test` en `build-apk.yml` (`node --check` sobre todos los JS + tests unitarios mínimos) y `./gradlew test`.
- **Criterios de aceptación:** `curl 127.0.0.1:8765/api/agents` → 200; Control Center muestra ONLINE real; Skill Manager lista skills; petición desde la LAN sin token → 401; workflow del panel de QA reproduce resultado esperado.
- **Dependencias:** ninguna. **Riesgo:** romper payload legacy → mantener dual-key por una release.

### F1 — Bootstrap wizard en la app (esfuerzo **M** · 1–2 semanas)
- **Objetivo:** "instalar APK → abrir → seguir menú de configuración".
- **Entregables:**
  - `ui/setup/SetupWizardScreen.kt`, `ui/setup/SetupSteps.kt`, `ui/viewmodel/SetupViewModel.kt`; flag `setup_completed` en DataStore; ruta `setup` como `startDestination` condicional en `AppNavHost.kt:32`.
  - Backend `src/bootstrap/setupState.js` + `src/bootstrap/setupRoutes.js` montado en `server.js`: `GET /api/setup/state`, `POST /api/setup/step/:id/run`, `GET /api/setup/events` (SSE), `POST /api/setup/reset`.
  - Pasos "diagnóstico", "root", "validación entorno" reutilizando `RootShell.kt` y `/api/system/status`.
  - Overlay `NativeOfflineOverlay` → botón "Abrir asistente de configuración".
- **Criterios de aceptación:** APK limpio en dispositivo limpio → wizard aparece en <3s; mata el proceso a mitad y reabre → reanuda en el paso exacto; sin root → instrucciones + retry funcional; QA con Artemis: 5 tests de wizard PASSED con screenshots.
- **Dependencias:** F0 (contratos + token). **Riesgo:** variance de Magisk/KernelSU → cubrir con matriz de 2 gestores.

### F2 — Motor de instalación (esfuerzo **L** · 2–3 semanas)
- **Objetivo:** que el wizard pueda *crear* el entorno, no solo detectarlo.
- **Entregables:**
  - `src/bootstrap/installer/{rootCheck,chrootInstaller,nodeInstaller,opencodeInstaller,agyInstaller}.js` con `validate()`/`apply()`.
  - `src/bootstrap/download.js`: streaming con progreso por bytes, reanudación `Range`, verificación SHA256 contra manifiesto `bootstrap-manifest.json` (versiones + hashes fijados).
  - Scripts embebidos: `assets/install-ubuntu.sh` (ubuntu-base + proot fallback), versión firmada de `stage-node.sh`.
  - Wizard: pantalla de progreso con log en vivo, % por paso, botón Reintentar, "Reanudar instalación".
  - `GET /api/setup/manifest` para versiones/updates.
- **Criterios de aceptación:** dispositivo de fábrica + Magisk + APK → **sin comandos manuales**, todos los pasos `done` en ≤25 min (WiFi); desconectar WiFi a mitad → reanuda sin corromper; checksum inválido → rollback del paso; segundo arranque → wizard no aparece.
- **Dependencias:** F1. **Riesgos:** tamaño de descarga/cuota de almacenamiento (L); `chroot` varía por kernel → fallback proot; tiempo de QA alto → automatizar con Artemis.

### F3 — Integración OpenCode / Antigravity / skills (esfuerzo **M** · 1–2 semanas)
- **Objetivo:** "todo listo" = IA funcional end-to-end tras el wizard.
- **Entregables:**
  - Pasos de setup para `agy auth`, `artemis auth login` guiado, smoke test `POST /opencode/session/:id/message` con respuesta real.
  - SkillManager con allowlist + checksum (cierra H-14), `available` real desde catálogo, streaming de install ya existente conectado al wizard.
  - `/api/setup/step/final-check`: health de 4096 + agy + a11y :8766 + 1 mensaje de prueba.
- **Criterios de aceptación:** fresh install → primer mensaje de chat responde desde el wizard sin tocar nada más; skills `graphify` y `opencode-mem` instaladas y visibles en Skill Manager.
- **Dependencias:** F2. **Riesgo:** credenciales de terceros no automatizables → deep-link + estado "acción manual requerida" explícito en el wizard.

### F4 — Hardening + CI/CD (esfuerzo **M** · 1 semana)
- **Objetivo:** que nada se rompa en silencio y que el sistema no sea una RCE de red.
- **Entregables:**
  - Tests backend (node:test): envelope, validación `projectId` regex, rutas setup idempotentes, token/rate-limit.
  - Tests app: unitarios de ViewModels + 1 suite instrumentada de navegación.
  - CI: `test` → `lint` → `build-debug` → `build-release` (keystore secreto) + Semgrep/Gitleaks (`security-architect` pipeline).
  - `logger.js` conectado (cierra H-12) + `GET /api/system/logs` con rotación; health expuesto en Control Center.
  - Manifest de SBOM/versions para el bootstrap.
- **Criterios de aceptación:** pipeline falla si un test falla; 0 secretos con Gitleaks; endpoint de shell exige token; cobertura de rutas críticas ≥ 70%.
- **Dependencias:** F0–F2. **Riesgo:** poca disciplina de tests → regla "sin test no se mergea".

### F5 — Release (esfuerzo **S** · 3–5 días)
- **Objetivo:** distribuir el APK real con flujo de actualización.
- **Entregables:** `v1.0.0` firmado (release), changelog, `docs/QUICKSTART.md` de una página (3 pasos del usuario), badge CI real, política de rollback, ADR-001/002 publicados, QA reproducible (checklist + screenshots en `docs/qa/`).
- **Criterios de aceptación:** instalación en dispositivo limpio reproducida por otra persona siguiendo solo la doc; QA re-ejecutado con evidencia adjunta; sin referencias al applicationId legado (paquete heredado previo a `com.aegis.hub`) en el repo.
- **Dependencias:** F4. **Riesgo:** ninguno técnico relevante.

---

## 7. ESTRATEGIA DE QA / TESTING (cómo evitar regresiones)

1. **Nivel 1 — Unitarios backend (nuevo):** `node --test` sobre `src/bootstrap`, contratos (`/api/system/health` y `/api/skills` deben coincidir con los data classes de `Models.kt` — **test de contrato cruzado** que habría detectado H-01/H-02), `pathResolver` (traversal), token/rate-limit. CI gate obligatorio.
2. **Nivel 2 — Contrato app↔backend:** generar `openapi.json` desde las rutas y validarlo contra `ApiService.kt` en CI (evita H-03: endpoints fantasma).
3. **Nivel 3 — Instrumentados mínimos:** `androidTest` de navegación (drawer → 4 pantallas) y del wizard (estado persistente, reanudación).
4. **Nivel 4 — E2E en dispositivo con Artemis (el que ya usáis):** mantener la matriz TEST-01…TEST-09 de `AEGIS_BUILD_AND_QA.md`, **pero exigiendo evidencia**: screenshot por test + `test-results.json` + traza de árbol de accesibilidad. Sin evidencia → el test cuenta como `NOT RUN`, no `PASSED` (regla Reality Checker).
5. **Regla de oro:** ningún informe puede contener "0 bugs" sin adjuntar artefactos; los scores perfectos de `QA_REPORT.md` y `AUDITORIA_INTEGRAL_QA.md` se marcan como **no válidos** hasta reproducción.
6. **Smoke de arranque:** test automatizado post-build que instala el APK, ejecuta el wizard en modo dry-run y verifica `GET /api/setup/state {all:"done"}`.

---

## 8. QUICK WINS INMEDIATOS (esta semana)

1. **Montar las 4 rutas huérfanas** en `server.js` (`agentRoutes`, `workflowRoutes`, `jobRoutes`, `contentRoutes`) — 4 líneas, desbloquea 3 pantallas `[E: server.js:734-735]`.
2. **Arreglar el contrato de health** añadiendo claves legacy (`server`, `uptime`, `projects`, `skills`, `adapters`, `agents`, `jobs`) — desbloquea Control Center `[E: server.js:1194]`.
3. **Arreglar `GET /api/skills`** para devolver `{installed,available}` — desbloquea Skill Manager `[E: skillsRoutes.js:11]`.
4. **Token + bind 127.0.0.1 por defecto** — cierra la RCE root en LAN en una tarde `[E: server.js:2522,2109]`.
5. **Actualizar el applicationId legado → `com.aegis.hub`** en `install-su.sh`, `service.d-…`, `server.js:1344` — hace que permisos y doze-whitelist funcionen de verdad `[E: install-su.sh:34-66]`.
6. **Añadir `node --check` + `./gradlew test` al workflow** — primer quality gate `[E: build-apk.yml]`.
7. **Rebajar el tono de `docs/QA_REPORT.md`** a "needs work" y enlazar con esta auditoría — restaura fiabilidad de la documentación `[NE→E]`.

---

*Auditoría generada por el equipo multi-agente de agency-agents. Solo lectura. Toda afirmación citada con archivo:línea; lo no verificado está marcado [NE].*
