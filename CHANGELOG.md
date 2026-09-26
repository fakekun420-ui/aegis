# Changelog

Todo notable de Aegis se documenta aquí. Formato basado en [Keep a Changelog](https://keepachangelog.com/es/1.1.0/); versionado [SemVer](https://semver.org/lang/es/).

## [1.1.1] - 2026-09-26

### Arreglado
- **Formularios respondibles desde la app.** Las preguntas de herramientas solo se
  podían responder desde el TUI del CLI. Ahora Aegis las muestra como tarjeta con
  opciones táctiles y las contesta. Rutas nuevas en el Hub (el proxy generico
  `/opencode/*` devuelve 401: no anade el Basic de OpenCode, que solo vive en
  `providers.js`):
  - `GET  /api/forms[?sessionId=]`
  - `POST /api/forms/:sessionId/:formId/reply`  body `{"answer":{"<campo>":"<valor>"}}`
- **El cuerpo del reply no llevaba la clave `answer`.** El `@Body` estaba tipado como
  `Map<String, Map<String, String>>` y la llamada mandaba `{...}` a pelo; el Hub exige
  `body.answer` y devolvia 400 `INVALID_ANSWER` en cada toque.
- **Retrofit no admite el `Map` anidado.** Kotlin lo aceptaba pero en ejecucion
  reventaba con `Parameter type must not include a type variable or wildcard`. La
  tarjeta se pintaba perfecta y el toque fallaba: el peor tipo de fallo, porque en la
  lista de mensajes todo parecia correcto y el build estaba VERDE. Sustituido por
  `data class FormReplyBody(val answer: Map<String, String>)`.
- **El divisor de "respuesta final" se dibujaba en el sitio equivocado.** Flotaba al
  final de la lista en cuanto existia un turno cerrado en el historial
  (`if (finishedTurnId != null)`), y `finishedTurnId` se fijaba una vez y nunca se
  limpiaba. De ahi los dos sintomas: el divisor aparecia encima de "Trabajando en
  ello…" de un turno nuevo, y caia despues de un mensaje que no era el final.
- **`time.completed` no significa "turno terminado", significa "mensaje cerrado".**
  Un mensaje puede llevarlo con un `bash` todavia en `running` (el divisor salia con
  la herramienta en "ejecutando…") o con un formulario pendiente.
- **El poll consultaba los mensajes antes que los formularios**, juzgando el cierre
  del turno con el estado de preguntas de dos ciclos atras.

### Cambiado
- El divisor se emite **anclado a su mensaje** (`isFinalResponseOf`): asistente +
  `completed` + ninguna herramienta `running` + lo siguiente es un mensaje del
  usuario o no hay nada. Sobrevive a recargar la sesion y no depende de estado global.
- `turnIsReallyFinished()` aplica la misma regla de tres condiciones al indicador
  "Trabajando en ello…" y a la notificacion, anadiendo que un formulario pendiente
  significa que el agente sigue trabajando.
- `buildChatRows()` construye una lista heterogenea `ChatRow` (Mensaje | Cierre) para
  poder intercalar el divisor: `item()` no se puede llamar desde dentro de
  `itemsIndexed`, y `remember` tampoco desde el `content` del `LazyColumn`.
- El poll consulta los formularios **antes** que los mensajes.

### Verificado en el dispositivo (versionCode 150)
- Encuesta real "Motor favorito" (4 opciones) con la tarjeta pintada, sin error.
- Un toque responde de verdad: la encuesta paso de 1 pendiente a 0 y el agente
  contesto "¡Gracias! OpenCode es tu motor favorito."
- Con un `bash` en "ejecutando…": aparece "Trabajando en ello…" y NO el divisor.
- Con el turno cerrado: el divisor aparece en su sitio, y no hay indicador a la vez.

## [1.1.0] — 2026-09-25

Cierre de la sesión de estabilización: el Hub y el CLI hablaban con **dos servidores de OpenCode distintos**, lo que rompía la sincronización y hacía que los turnos se cortaran. Todo lo de esta versión sale de ese diagnóstico y de lo que se destapó al arreglarlo.

### Fixed
- **Un solo servidor de OpenCode (raíz de la falta de sincronización).** El Hub proxaba a `:4096` (lanzado por `keepalive`) mientras el TUI del CLI usaba el servicio registrado en `:49374`. Comparten la base de datos SQLite, pero el estado *"este turno está en curso"* vive en la **memoria de cada proceso**, así que el CLI no veía los turnos lanzados desde Aegis y el chat se leía "como si no estuviera corriendo". `server.js` prioriza el servicio registrado e **ignora `--opencode-port`** con un `WARN` explícito; `keepalive.sh` pasa a `OC_PORT=49374`. Verificado en uso real por el usuario. **No reintroducir un segundo `opencode serve` en otro puerto.**
- **Cinco timeouts de socket por inactividad eliminados.** Estaban hardcodeados a 120/130/125 s en el proxy (ambos paths) y en `handleRequest`. Durante un turno agéntico no circula ni un byte, así que destruían la conexión a mitad de turno y la píldora "Enviando…" se quedaba cargando para siempre, con reintentos en bucle. Todos se derivan ahora de `AEGIS_TURN_TIMEOUT_MS` (600 s) y el guard explícito queda como red de seguridad posterior, para que el error que ve el cliente sea un 502 legible y no un corte mudo. Medido: de **HTTP 000 / 125 s / 0 bytes** a **HTTP 200 / 11,4 s / 10.888 bytes**.
- **El chat no se actualizaba solo.** `load()` llamaba a `getMessages` una vez y no volvía a preguntar: el único poll vivía dentro de `sendMessage` y se cancelaba al acabar el turno. Mientras el usuario solo miraba un chat no había actualización, y tenía que salir y reentrar, o enviar otro mensaje, para ver los cambios. Nuevo `startViewRefresh()` con refresh cada 2 s, con **propiedad de sesión** en el `DisposableEffect`: sin ella, el `onDispose` de la pantalla anterior mataba el poll de la nueva (Compose no garantiza el orden).
- **Al abrir un chat se veía el primer mensaje.** El auto-scroll calculaba `messages.size + 1` para sumar un ítem "live" que todavía no existía, `animateScrollToItem` lanzaba `IndexOutOfBounds` y el `catch (_: Exception) {}` lo tragaba en silencio, dejando la lista en el índice 0. Ahora usa el conteo real de `listState.layoutInfo`, reintenta y hace salto instantáneo al final.
- **El teclado tapaba la última parte del mensaje.** `imePadding()` faltaba en la lista de mensajes. (Primer intento de arreglo lo duplicó —el `bottomBar` ya lo aplicaba— y collapsedó la lista a altura 0, haciendo desaparecer el chat; revertido dejando solo el inset como clave del scroll.)
- **El chat nuevo no se creaba, en silencio.** `if (!sid.isNullOrBlank())` sin `else`, más un `catch (_: Exception) { null }`: si fallaba no pasaba nada y no había forma de saber por qué. Ahora el motivo llega a `vm.error` y se muestra.
- **El proveedor siempre era `antigravity`.** `createSessionForProject("")` no encontraba proyecto y caía siempre en el fallback, ignorando la elección del usuario. Además `selectProvider` hacía un `return` mudo al estar la sesión vinculada: ahora el proveedor es cambiable mientras el chat esté vacío y, cuando ya no puede, explica por qué.
- **El registro de proyectos estaba lleno de basura.** `PROJECTS_STORE_FILE` estaba hardcodeado, así que `node --test` en local escribía proyectos de prueba en el `projects.json` real. Ahora es configurable con `AEGIS_PROJECTS_STORE`; verificado con md5 idéntico antes y después de la suite.
- **Ponytail global duplicado y corrupto.** El Hub inyectaba su propia copia de `pony-tail-global.md` (54 líneas, con una ruta inexistente y datos obsoletos) en vez del canónico. Eso consumía la mitad de su presupuesto de 24 KB por sesión. Retirada a cuarentena; la capa global la entrega `~/.config/opencode/AGENTS.md`, symlink al canónico.

### Added
- **Aviso de respuesta final.** Se usa `info.time.streamed`, la marca con la que OpenCode cierra el turno de verdad, para distinguir "llegó el último trozo de texto" de "la IA ya no está trabajando". En primer plano se dibuja un divisor "✓ respuesta final" en el chat.
- **Notificación en la barra de notificaciones** al llegar la respuesta final, **solo si la app está en segundo plano** (`MainActivity.isForeground`). Sin sonido, con `PendingIntent` inmutable que abre la sesión correcta, y el `SecurityException` se come a propósito si el usuario no concedió `POST_NOTIFICATIONS`: la notificación es un extra y no debe tumbar el refresco.
- **Vincular carpeta existente como proyecto.** Al pulsar "+ Nuevo proyecto" se pregunta entre **crear** y **vincular**. Al vincular se abre el gestor de archivos del sistema y la carpeta se registra como proyecto tomando **el nombre de la carpeta** como nombre. `POST /api/projects` acepta `folder`, con contención estricta en `PROJECTS_ROOT` (`FOLDER_OUTSIDE_ROOT`) y rechazo de carpeta ya reclamada (`DUPLICATE_FOLDER`) — sin esa validación, un body con `/data` sería path traversal arbitrario.
- **Sincronización de proyectos preexistentes.** Los 6 proyectos que ya estaban en `/sdcard/projects` se registrean adoptando su carpeta real (incluido `Petite Raw`, sin crear la duplicada `petite-raw`), con `.hub/project.json` y `.ponytail.md` generados.

### Changed
- **Versión a 1.1.0** (`versionName`). `versionCode` sigue auto-incrementándose por `GITHUB_RUN_NUMBER`, así que cada build de CI es único.
- **Grafo de Graphify actualizado** a 1743 nodos / 3623 aristas, health check sin aristas colgantes, incluyendo el código de esta versión.

## [Unreleased]

### Added
- **Auto Project Folder Creation & Ponytail:** Creación automática de directorio en `/sdcard/projects/<nombre-sanitizado>/`, subdirectorio `.hub/project.json` y archivo `.ponytail.md` con inferencia contextual de tipo y stack al crear proyectos desde la app.
- **Model Discovery y Sub-agent Policy:** Registro clasificado de 477 modelos en `_system/model-registry.json` y política de selección multinivel en `_system/subagent-policy.md`.
- **Ruta de proyecto en UI Android:** Indicador de ruta interactivo en `ProjectDetailScreen` con copiado al portapapeles y notificación de creación en `ProjectsScreen`.
- **OpenCode v2 Adapter:** Soporte completo de API REST v2 de OpenCode con scraping dinámico de autenticación Basic en `service.json` y `opencode.log`, polling cursor-based de mensajes y fallback de modelo activo.
- **Persistencia atómica de fijado de chats:** Endpoints `POST /api/opencode/sessions/:id/pin` y `unpin`, guardado seguro en `projects.json` mediante `fileMutex`, e interfaz con pin en Android (`ChatsScreen.kt`).
- **TokenProvider unificado:** Singleton canónico en Kotlin (`TokenProvider.kt`) con cooldown de 2s, sincronización con Mutex y compatibilidad OkHttp sincrónica. Eliminadas duplicaciones en `ApiClient` y `CompanionService`.
- **Motor DAG en Workflows:** Resolución de dependencias (`depends_on`), concurrencia configurable (`max_concurrent`), y ciclo de vida de pasos (`pending`/`running`/`completed`/`failed`/`skipped`) con test unitario dedicado.
- **Pipeline de Voz Completo:** Chips funcionales en `VoiceConversationScreen`, selección de modelo, creación de sesión, diálogo de configuración de voz (velocidad TTS, selector de idioma, wake word configurable persistido en `SharedPreferences`).
- **Dashboard Web UI:** Interfaz ligera vanilla en `backend/public/index.html` sirviendo estado del hub, lista de sesiones activas, visor de logs y almacenamiento de token.
- **Plantilla `projects.json.example`:** Repositorio limpio con `projects.json` ignorado en `.gitignore`.
- **Guía de Keystore RSA-4096:** `docs/KEYSTORE_SETUP.md` documentando generación, encoding en base64 y configuración de secretos CI.
- **Scripts de prueba de QA física:** `docs/qa/QA_TEST_SCRIPTS.md` con 8 casos reproducibles para POCO F3.

### Changed
- **CI / Seguridad:** Promoción de `semgrep` y `gitleaks` a bloqueantes en `.github/workflows/build-apk.yml`. Concurrencia fijada a 1 en pruebas backend.
- **Ruteo de Proveedores:** Corrección de rama muerta en `server.js` que forzaba Antigravity incluso cuando OpenCode era el destino.
- **Validación de Identificadores:** Unificación canónica de `ID_RE` (`^[A-Za-z0-9_-]+$`) rechazando puntos para mayor seguridad en rutas de archivos.
- **Envelope de Errores Tipado:** Creación de `ErrorBody` en `Models.kt` para `Envelope` y `BaseResponse`, actualizando ViewModels de Android.
- **Sincronización de Conteo de Tests:** Unificación documental a 54/55 tests reales.

### Removed
- Árbol de adapters legado `backend/src/adapters/` (ClaudeCode inlined en `providers.js`).
- Layout muerto `activity_main.xml`.

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
