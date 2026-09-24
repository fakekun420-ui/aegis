# Changelog

Todo notable de Aegis se documenta aquí. Formato basado en [Keep a Changelog](https://keepachangelog.com/es/1.1.0/); versionado [SemVer](https://semver.org/lang/es/).

## [1.0.0] — 2026-09-24

Primera release consolidada: del hub legacy con RCE anónima a un producto autogestionado
(seguridad + wizard de bootstrap + verificación + tests + CI). Todo lo listado está verificado
en código y/o tests; lo pendiente de ejecución real en dispositivo vive en
[`docs/qa/QA_CHECKLIST.md`](docs/qa/QA_CHECKLIST.md).

### Added

- **Seguridad (F0 · A-1):** middleware `X-Aegis-Token` con comparación *timing-safe* en
  **todo** `/api/*` y `/opencode/*` (única exención: `GET /api/health`, sonda ligera de
  `keepalive.sh`), bind `127.0.0.1`, CORS con allowlist (`http://localhost:8765`, `app://aegis`),
  `CompanionService` (8766) en loopback con el mismo token, escaping de meta-caracteres en
  `RootShell.kt`, `skillId` validado y traversal cerrado en `DELETE /api/sessions`.
  Ver [`docs/adr/ADR-001-security-model.md`](docs/adr/ADR-001-security-model.md).
- **Routers y motores montados (F0 · A-2):** `jobRoutes`, `agentRoutes`, `workflowRoutes` y
  `contentRoutes` pasan de huérfanos a montados, con `GET /api/workflows/list|status` (los que
  consume la app), `jobScheduler.start()` y `eventBus` suscrito. `dispatchRoute` elimina 21
  `ERR_HTTP_HEADERS_SENT`.
- **Contratos reparados (F0 · A-3):** `HealthData` 10/10 campos, `SkillsResponse {installed[],
  available[]}`, envelope global `{ok, data, error}` y `404` JSON para rutas API (antes caía al
  fallback SPA `200 text/html`).
- **Wizard de bootstrap de 6 pasos (F1/F2):** dominio `backend/src/bootstrap/` — `STEP_DEFS`
  (Comprobación previa · Ubuntu · Node.js · OpenCode · Antigravity/Artemis · Skills), `check()`
  idempotente por paso que lee el estado **real** del dispositivo, motor
  `runOnce / resume / retry / cancel` cooperativo y estado persistente atómico en
  `backend/bootstrap-state.json` (tmp + fsync + rename; `running` se normaliza a `paused` si el
  hub muere). En la app: `SetupWizardScreen` + `BootstrapViewModel` (polling 1 s) y arranque
  directo en el wizard cuando `phase != done`.
- **Motor de instalación real con rollback (F2):** descarga con **SHA256 obligatorio**
  (sin 64 hex no se descarga nada) contra `src/bootstrap/ubuntu-manifest.json`
  (`ubuntu-base-24.04.5-base-arm64`) y `src/bootstrap/node-manifest.json`
  (`node-v24.21.0-linux-arm64`); extracción e instalación de OpenCode (wrapper sobre
  `opencode.cjs` o `npm install -g` como fallback), paso `agy` (binario + auth OAuth verificada
  por tamaño, su contenido jamás se imprime) y skills desde `skills-manifest.json`. Rollback por
  paso (LIFO de `ctx.onRollback`) **sólo sobre lo creado por ese run**, con detección de modo de
  aislamiento chroot/unshare/proot. Ver [`docs/adr/ADR-002-bootstrap-design.md`](docs/adr/ADR-002-bootstrap-design.md).
- **Verificación final y smoke test de la IA (F3):** `GET /api/setup/final-check` (4 checks en
  paralelo, cada uno ≤2 s: OpenCode `:4096`, Antigravity/Artemis, servicio de accesibilidad
  `:8766`, estado del wizard), `POST /api/setup/smoke-test` (ida y vuelta **real** con el modelo,
  60 s, pide `PONG` y devuelve el texto literal), `POST /api/setup/auth/antigravity` (guía del
  comando oficial) y `GET /api/setup/manifest` (SBOM honesto: `null` + `note` cuando algo no es
  verificable, nunca inventa versiones).
- **Allowlist de skills (F3 · H-14):** `SkillManager.install` sólo admite ids de
  `src/skills/catalog.json` (formato validado, tarball vía `npm pack` con `sha256` verificado);
  fuera del catálogo → `400 ALLOWLIST`. Fin del `npm install -g` desde un endpoint anónimo.
- **Blindado F4:** rate limiting `120 req/min/IP` → `429` + `Retry-After`
  (exentas `GET /api/health` y `GET /api/bootstrap/state`; `AEGIS_RATE_LIMIT=0` lo desactiva),
  validación anti-traversal de ids (`400 PROJECT_INVALID|SESSION_INVALID|SKILL_INVALID`, máx.
  256 chars), logger con sink rotado en `backend/logs/aegis.log` (1 MB ×3) y
  `GET /api/system/logs?lines=`, y `GET /api/setup/manifest` (SBOM).
- **Tests:** 47 suites/casos backend (`cd backend && npm test` → `node --test tests/*.test.js`):
  contrato, bootstrap, rollback, setup, seguridad, rate-limit, logs, **cross-contract** (fija el
  shape que espera `Models.kt`) y manifest/SBOM. En la app: 25 tests JVM + 5 instrumentados (F4).
- **CI/CD (F4):** `.github/workflows/build-apk.yml` con `backend-checks` (`node --check`,
  `bash -n`, tests, YAML), `lint`, `build-debug`, `build-release` (sólo si existen los secrets
  `KEYSTORE_BASE64`/`KEYSTORE_PASSWORD`/…), `semgrep` y `gitleaks` **no bloqueantes** (primer
  ciclo) y `instrumented` manual. `server.js` queda sin `console.*` (sólo el logger del hub).
- **Documentación de release (F5):** [`CHANGELOG.md`](CHANGELOG.md),
  [`docs/QUICKSTART.md`](docs/QUICKSTART.md), ADR-001 (modelo de seguridad + rotación de
  keystore), ADR-002 (diseño del bootstrap), [`docs/qa/QA_CHECKLIST.md`](docs/qa/QA_CHECKLIST.md)
  y README actualizado al estado v1.0.0.

### Changed

- **Versionado:** `versionName = "1.0.0"` y `versionCode` fallback local 1 → 2 en
  `app/app/build.gradle.kts` (en CI sigue mandando `BUILD_NUMBER`/`GITHUB_RUN_NUMBER`);
  `HUB_VERSION` de `server.js` pasa de `1.0.0-SNAPSHOT` a `1.0.0` (fuente única del SBOM; el
  test la lee del fichero fuente, no la fija).
- **Naming:** paquete legado `com.opencode.companion` → `com.aegis.hub` (0 hits, A-6.1); resto de
  referencias de feature/docs renombradas a **Aegis** / **Aegis Hub** (F5). No se tocaron
  applicationId, patrones de proceso de `keepalive.sh` ni rutas físicas fuera de `Aegis/`.
- **CI:** el job `build` pasa a llamarse **`build-debug`** (F4).
  > ⚠️ Si el repo en GitHub tiene reglas de protección de rama que exigen el check `build`,
  > hay que actualizarlas a `build-debug` (o a `build-debug, backend-checks`) desde
  > *Settings → Branches/Branch protection rules*. Este proyecto no puede tocar esa configuración.
- **Observabilidad (F0 · A-7):** 73 `console.*` → `logger` con niveles; `providers.js` y
  adapters migrados (30, grep = 0); arranque del hub estructurado.
- **Estado interno:** `fileMutex` + `loadProjectsStore` unificados en `src/core/storage.js`;
  `modelRouter`/`taskClassifier` **eliminados** (0 consumidores en runtime: `route()` cambiaba el
  adapter por defecto por palabras del prompt); `start-hub.sh` dejó de anunciar una URL “de red”
  engañosa y ahora explica el loopback + `adb forward`.
- **Keystore:** `companion-release.keystore` sigue **fuera del repo** (`.gitignore *.keystore`;
  `git ls-files` = 0 entradas). Plan de rotación en ADR-001 (A-6.2).

### Fixed

- `GET /api/projects/:id/summary`: el regex del route **sin grupo de captura** devolvía siempre
  404; ahora captura + `isValidId` + decode → 200 con shape / 404 honesto sin fichero /
  400 `PROJECT_INVALID` en traversal (test 6 reescrito).
- `/opencode/*` quedaba **fuera** del middleware de token (cualquier proceso local podía
  invocarlo) → ahora exige `X-Aegis-Token` (cierre de A-1 en backlog).
- App: fuga de OkHttp (`body.close()` en `finally`), doble envío de chat, `key = {hashCode}` en
  la lista de conversaciones, `rememberSaveable` de 0 → 4 usos, targets táctiles < 40dp → ≥48dp,
  `imePadding()` en el input de chat y contentDescription de accesibilidad en español.
- Backend: escrituras atómicas en `ui-state`/`providers`/summaries/session/skill, E/S fuera del
  lock de `createSession`, `loadProjectsStore` indefinida, `ClaudeCodeAdapter → normalizeMessage`
  real y `500` honesto si falla `providers/default`.
- Higiene: `MainActivity.webview.kt.bak` (639 líneas muertas) borrado y
  `copy_facebook_post.sh` fuera del árbol versionado.

### Security

- **RCE anónima de root cerrada (H-04/SEC-01..04):** el hub escuchaba en `0.0.0.0:8765` sin
  autenticación, con CORS `*` y `POST /api/device/shell` = ejecución de comandos como root desde
  cualquier host de la red. Hoy: loopback + token en todo lo sensible, meta-caracteres de shell
  rechazados (`;`, `|`, `` ` ``, `&&`, salto de línea, `$(`) con `400 BAD_REQUEST`, y allowlist de
  origen CORS. **Modelo: root total *con* token, cero acceso sin él** (ADR-001).
- **F4:** rate limit con `429` + `Retry-After`, ids validados contra traversal y longitud,
  `/api/system/logs` y `/api/setup/manifest` protegidos (test 7), orden de middleware verificado
  (auth antes de routers, test 8).
- **Catálogo de skills con hash verificado** (F3): sin `sha256` de 64 hex no se instala nada.
- **Bootstrap ciego endurecido (F2):** sin SHA256 oficial no se descarga nada; el token OAuth de
  Antigravity sólo se comprueba por tamaño (`>0 B`) y jamás se imprime ni se copia.
- **Secretos:** keystore de release no trackeado y sólo inyectado en CI vía `KEYSTORE_BASE64`;
  el token jamás aparece en respuestas HTTP (ni siquiera en `GET /api/system/health`).
- Backlog: `AndroidManifest` documenta por qué `usesCleartextTraffic=true` (XML no admite
  allowlist de IPs loopback) y sus mitigantes reales (bind `127.0.0.1` + token).

[1.0.0]: https://github.com/fakekun420-ui/aegis/releases/tag/v1.0.0
