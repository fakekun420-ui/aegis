# PLAN DE SIGUIENTES ACCIONES — Aegis v1.0.0

> Generado el 2026-09-24 tras ejecutar el plan completo de acciones siguientes
> (operativo + F3 + F4 + F5 + backlog) con push a `origin/main` y mapa graphify
> actualizado. Este documento es la foto del cierre + la cola de trabajo priorizada.

---

## 0. ACTA DE CIERRE CONSOLIDADA (F3 · F4 · F5 · backlog)

### F3 — Integración IA end-to-end ✅
- **Backend** (`9f89d48`): `GET /api/setup/final-check` (4 checks reales con timeouts), `POST /api/setup/smoke-test` (60s, `OpencodeAdapter`, `reply` real o `SMOKE_FAILED` honesto), `POST /api/setup/auth/antigravity` (siempre `mode:"manual"` — `agy` no tiene `auth login`; comando oficial en `command`), `SkillManager` con allowlist `src/skills/catalog.json` (+`sha256` cuando es verificable con npm), `available` real (catálogo − instalados).
- **App** (`3420b3b`): tarjeta **"Verificación final"** en el wizard al completar: checks ✓/✗/⚠, "Enviar mensaje de prueba" con respuesta de la IA en monospace, comando de auth manual con reintento.

### F4 — Hardening + CI/CD ✅
- **Backend** (`268550c`): ids anti-traversal (`PROJECT/SESSION/SKILL_INVALID`), rate-limit 120 req/min/IP (`429`+`Retry-After`, exentos health y `bootstrap/state`), logger con sink rotado (`logs/aegis.log` 1MB×3) + `GET /api/system/logs?lines=`, **`GET /api/setup/manifest`** (SBOM/versiones reales), **tests cruzados backend↔`Models.kt`** (fijan health10/10, `SkillItem`, enums bootstrap,4 `id` de final-check).
- **App** (`446c83f`): refactor `BootstrapRepository` (inyección con default → compatible con `viewModel()`), **25 tests JVM** (VM/friendlyError/envelopes Gson) + **5 instrumentados** con `testTag`s.

### F5 — Release v1.0.0 ✅
- `f582f6c`: `versionCode 2` / `versionName 1.0.0` / `HUB_VERSION 1.0.0`; `CHANGELOG.md`, `docs/QUICKSTART.md`, `ADR-001` (modelo de seguridad + plan de rotación de keystore), `ADR-002` (wizard idempotente/reanunable), `docs/qa/QA_CHECKLIST.md` (8 casos con casillas); README raíz+app; barrido `opencode-companion` **47→25 hits** (0 nuevos; quedan crónicas de auditoría, comentarios de `keepalive.sh` que explican el patrón vivo, `pony-tail-global.md` **INMUTABLE — requiere tu aprobación** y la nota de migración de `docs/ARCHITECTURE.md`).

### Backlog técnico ✅ (8/10)
- `d730e6e`:30 `console.*`→logger (providers/adapters =0), `fileMutex`/`loadProjectsStore` unificados (1 def), **`modelRouter`/`taskClassifier` eliminados** (0 consumidores reales; `route()` habría cambiado el adapter por defecto por palabras del prompt), fix regex `summary` (siempre 404→200/400), **`/opencode/*` exige token** (cierre A-1), doc `AEGIS_MASTER_PROMPT.md` corregida, `start-hub.sh` sin mensaje "red" falso, comment de `usesCleartextTraffic` justificado, `copy_facebook_post.sh` fuera del índice.
- No resueltos: `gradle-wrapper.jar` no existe en el entorno (no se fabrica un binario) · `brain/` sin nada trackeado.

### CI/CD — pipeline canónica en la raíz ✅ (corrección post-push)
- `5014ab1`+`b1d409e`: **GitHub sólo lee `/.github/workflows/`** — el F4 vivía en `Aegis/.github/` (huérfano para GitHub). Workflow canónico reescrito con prefijos `Aegis/`: `backend-checks (Node 24, tests secuenciales)` → `lint` → `build-debug` → `build-release` (siempre; keystore real si hay `KEYSTORE_BASE64`, si no fallback debug-signing) + `semgrep`/`gitleaks` (`continue-on-error` hasta primer ciclo limpio) + `instrumented` manual-only. Artefactos: **`aegis-debug` (14d)** y **`aegis-release` (30d)**.
- Default branch del repo: **`master` → `main`**.

### Verificación final de cierre
| Check | Estado |
|---|---|
| `node --check` (46+ .js) | ✅ |
| `npm test` secuencial | ✅ **47/47** (paralelo puede flakear bajo carga E/S — ver backlog) |
| Producción :8765 con TODO el código nuevo | ✅ (`final-check`, manifest, `/opencode` 403 sin token, summary fix) |
| `keepalive.sh` vigilando | ✅ |
| Push `origin/main` | ✅ `8853ee0..b1d409e` |
| graphify | ✅ **3081 nodos / 5594 aristas / 220 comunidades** (alcance completo, backup en `graphify-out/2026-09-24/`) |
| CI en GitHub | ⏳ corrida2 en curso tras fix de `setup-android` (run1 falló: `sdkmanager` sin `packages:` rechazaba licencias de preview) |

### Incidentes operativos de esta sesión (lecciones)
1. **Cola FUSE**: la carga (tests + emulador + apps) colgó19 procesos en estado D sobre `hub.log`/`logs/aegis.log` y keepalive entró en bucle de respawn. **Recuperación**: aislar los inodes (`mv hub.log hub.log.wedged-*`), relanzar hub y keepalive. **Prevención** (→ backlog T1): tests deben spawnear con `AEGIS_LOG_DIR` propio.
2. **Tests comparten sink de log con producción**: los12 SIGTERM en `aegis.log` eran servidores de test (raíz del flake de `logs.test.js`).
3. **`[skip ci]`** en un commit de fix silenció la corrida que lo probaba → disparada manual con `workflow_dispatch`.
4. `android-actions/setup-android@v3` **necesita `packages:` explícitos** en este runner (regresión de licencias de preview sin ellos).

---

## 1. INMEDIATO (hoy, con la corrida2 de GitHub)

| # | Acción | Cómo |
|---|---|---|
| **I-1** | **Descargar el APK de GitHub** | `gh run download 35949619707 -n aegis-release -R fakekun420-ui/aegis` (o pestaña Actions → run → Artifacts). `aegis-release` = no debuggable (firmado con debug-key hasta que haya keystore); `aegis-debug` para desarrollo. |
| **I-2** | **QA de dispositivo (F5 aceptación)** | Seguir `docs/qa/QA_CHECKLIST.md`: instalación limpia ≤25 min → corte WiFi → retry → checksum→rollback → 2º arranque sin wizard → verificación final "PONG" → 403 sin token. Casillas `[ ]` → `[x]` con capturas. |
| **I-3** | **Protege el keystore de release** (opcional pero recomendado) | Genera fuera del árbol: `keytool -genkeypair -v -keystore aegis-release.keystore -alias aegis -keyalg RSA -keysize 4096 -validity 10000`; sube secretos: `gh secret set KEYSTORE_BASE64 -R fakekun420-ui/aegis < <(base64 -w0 aegis-release.keystore)` + `KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD`. Guarda la contraseña en tu gestor (nunca en el repo). El próximo run firmará `aegis-release` de verdad (rotación A-6.2/ADR-001). |
| **I-4** | Revisar el **primer ciclo de semgrep/gitleaks** | Si sale limpio → quitar `continue-on-error: true` de ambos jobs (F4 pendiente). |
| **I-5** | Branch protection (si la usas) | Si tenías regla exigiendo el check `build` → actualízala a `build-debug` (F4 lo renombró). Sin regla, no hace falta nada. |

## 2. SIGUIENTE SEMANA — estabilización (T1–T5)

| # | Tarea | Esfuerzo | Detalle |
|---|---|---|---|
| **T1** | Aislar logs de tests | ~1h | Todos los `startHub` de `tests/*.test.js` con `AEGIS_LOG_DIR` temporal (raíz del incidente FUSE y del flake de `logs.test.js`). Dejar `--test-concurrency=1` en CI y documentarlo. |
| **T2** | Smoke test real desde la app | ~2h | Ejecutar `POST /api/setup/smoke-test` contra OpenCode **v2** (hoy el `OpencodeAdapter` habla API v1 → `502 SMOKE_FAILED` honesto). Decidir: adaptar adapter a v2 (`/api/*` + Basic auth) o invocar el bundle local1.18.x. **Es el último eslabón para que "el wizard responda PONG" sea literal.** |
| **T3** | `listModels` v2 | ~1h | WARN `[providers] [opencode] listModels fetch error: <!doctype` — mismo mismatch v1/v2 que T2. |
| **T4** | `/event` y `/global/event` sin token | ~1h | Aliases SSE del proxy fuera del middleware (backlog de seguridad del agente de backlog). Exigir token como en `/opencode/*`; verificar que la app los consume con el interceptor. |
| **T5** | Etiquetas LLM del grafo | ~15min | Sin API key, los nombres de comunidad quedaron por "hub". Con `GEMINI_API_KEY`: `graphify label /sdcard/projects/Aegis --missing-only`. |

## 3. PRÓXIMO MILESTONE — v1.0.1 / F6 (cola priorizada)

| # | Tarea | Por qué |
|---|---|---|
| **B1** | **Adaptar `OpencodeAdapter` a OpenCode v2** (T2/T3 en uno) | Desbloquea smoke test + `listModels` + cualquier futuro uso del proxy. Contrato v2: rutas `/api/*` + auth Basic del serve. |
| **B2** | Divergencia `Envelope.error: String?` (Models.kt) vs `{code,message}` | Fijada por `cross-contract.test.js`; alinear el modelo Kotlin (parseo robusto) y quitar la salvedad §7.1 del contrato. |
| **B3** | Duplicidad de adapters | `src/adapters/{OpenCode,Antigravity}Adapter.js` coexisten con implementaciones dentro de `providers.js` (sólo `ClaudeCodeAdapter` se importa de adapters). Unificar en una sola fuente. |
| **B4** | `console.*` restantes (7 runtime: `eventBus`, `workflowParser`, `jobScheduler`, `BaseAgent`, `content/index`, `bootstrap/state`) | Completar A-7. |
| **B5** | KPI literal Kotlin→script | Hacer que el motor de bootstrap **invoque** `find-ubuntu.sh`/`stage-node.sh` en vez de replicarlos (decisión de diseño ADR-002), o aceptar el KPI equivalente y cerrarlo. |
| **B6** | `gradle-wrapper` | Generar en una máquina con Gradle (`gradle wrapper --gradle-version 8.9`) y commitear `gradlew` + jar (CI no lo necesita: usa `setup-gradle`; es para devs locales). |
| **B7** | `pony-tail-global.md` (3 paths legacy) | El fichero se declara INMUTABLE — **requiere aprobación explícita** para actualizar sus paths. |
| **B8** | Suite instrumentada en CI | `workflow_dispatch` con input `instrumented` ya listo (emulador + install + monkey). Probarlo una vez antes de fiarlo. |
| **B9** |12 docs sin re-extracción semántica | `graphify --update` de docs requiere API key (lado código ✓). |
| **B10** | Rotación de keystore (I-3) + registro en CHANGELOG | Cierre formal de A-6.2. |

## 4. BACKLOG MENOR (≤30 min, corte cuando haga falta)
- `projects.json` es estado runtime: decidir si se trackea o se ignora del todo (hoy aparece como modificado siempre).
- Scaffold de proyecto: `loadProjectsStore`/summaries →404 honesto sin fixture (ya correcto; sólo documentar flujo `POST /summarize`).
- `service.d-99-opencode-hub.sh`: nombre conservado por instalación viva; renombrar sólo con plan de migración en dispositivos ya instalados.
- Workflow legacy de nombre "Build Companion APK" en el historial de Actions → limpieza cosmética (ya reemplazado).
- `friendlyError()` sin caso "timeout" (test lo fija como passthrough) — ampliar si aparece en QA.

## 5. Criterios de "release hecho"
- [ ] QA_CHECKLIST completo (8/8 casillas) en el POCO F3 real.
- [ ] `aegis-release` firmado con keystore propio (secretos en GH, contraseña fuera del repo).
- [ ] semgrep + gitleaks en verde y **sin** `continue-on-error`.
- [ ] Smoke test `PONG` desde la wizard contra OpenCode v2 (B1).
- [ ] Etiquetas LLM del grafo regeneradas.
- [ ] Tag `v1.0.0` en el commit correspondiente una vez pasada la aceptación.

---
*Comandos de verificación rápidos:*
```bash
cd Aegis/backend && npm test                 # 47/47
TOKEN=$(cat Aegis/backend/.aegis_token)
curl -H "X-Aegis-Token: $TOKEN" localhost:8765/api/setup/final-check
gh run list -R fakekun420-ui/aegis           # estado CI
graphify god-nodes --graph Aegis/graphify-out/graph.json
```
