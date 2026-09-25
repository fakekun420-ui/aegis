# ADR-002: Diseño del bootstrap — wizard idempotente y reanudable con rollback

> 📄 **Documento histórico (snapshot).** Describe el estado del proyecto en el momento
> en que se escribió y **no se mantiene al día**. Para el estado actual ver
> `CHANGELOG.md`, `docs/ARCHITECTURE.md` y `backend/.ponytail.md`.

**Estado:** Aceptada
**Fecha:** 2026-09-23 (F1 checks + F2 motor) · verificada en F3/F4 · documentada en F5
**Decisores:** Fase 0 (roadmap F1/F2) y equipos de backend/app

---

## Contexto

La auditoría de proyecto (`docs/audits/AUDITORIA_PROYECTO_Y_ROADMAP.md`) cerró con **4/10** y un
gap demoledor: *“la promesa del contenedor completo tiene 0 % de automatización”*. No existía
wizard de primera ejecución, ni instalador de Ubuntu/Node/OpenCode/Antigravity, ni relación
Kotlin → scripts (`find-ubuntu.sh` / `stage-node.sh` sólo existían en disco, degree 1-3 en el
grafo). El flujo objetivo era **instalar APK → abrir → seguir el menú → todo listo**.

Restricciones heredadas del entorno: el dispositivo es un POCO F3 con root, la red puede caerse
a mitad de una descarga, y un estado a medias no podía dejar el rootfs corrupto.

## Decisión

Un **wizard de 6 pasos** con *checks* que leen el estado real y un motor con contrato estricto.

### 6 pasos (`STEP_DEFS`, fuente única en `src/bootstrap/state.js`)

| id | título | `check()` (idempotente, sin mutar) | `run()` real (F2) |
|---|---|---|---|
| `preflight` | Comprobación previa | root/su, arquitectura, disco, red | no muta: preflight duro (`run()` lanza si falta algo) |
| `ubuntu` | Ubuntu (chroot/proot) | rootfs presente (`/etc/os-release`) | descarga `ubuntu-base-24.04.5-base-arm64` (SHA256) → `tar -xzf` → valida `os-release` |
| `node` | Node.js | `node -v` usable | reutiliza `backend/node.bin` si responde; si no, `node-v24.21.0-linux-arm64` (SHA256) → `tar --strip-components=1` → enlaza en `PATH` |
| `opencode` | OpenCode | `opencode --version` | wrapper 0o755 sobre `backend/opencode.cjs` (nunca copia/borra el bundle) o `npm install -g opencode-ai` |
| `antigravity` | Antigravity / Artemis | binario `agy` + auth OAuth `>0 B` | crea `~/.gemini/antigravity-cli` si falta y **después** exige auth; el contenido del token **nunca** se imprime ni se copia |
| `skills` | Skills y plugins | skills de `skills-manifest.json` | `SkillManager.install` de lo que falte (allowlist), con `ctx.progress(i/n)` |

### Motor (`src/bootstrap/orchestrator.js`)

- **`runOnce({resume, retryFrom})` · `cancel()` · `isRunning()`** — un único job a la vez
  (`409 ALREADY_RUNNING` si ya corre; `409 NOT_RUNNING` al cancelar sin ejecución).
- **Idempotencia:** cada paso empieza por `check()`; si el dispositivo ya está satisfecho el paso
  pasa a `skipped` con detalle — un segundo arranque no vuelve a instalar nada.
- **Reanudable:** `resume` continúa desde donde quedó; `retryFrom` reinicia **un** paso
  (`409 NOT_RETRYABLE` si ese paso no está `failed`).
- **Cancelación cooperativa:** entre pasos, durante el check y dentro de `run()` vía
  `ctx.cancelRequested()`; el paso vuelve a `pending` y la fase queda `paused`
  (tope de espera de `cancel()` = 15 s antes de forzar la pausa).
- **Estado persistente atómico:** `backend/bootstrap-state.json` escrito con
  `atomicWriteFileSync` (tmp + `fsync` + `rename`) — jamás queda JSON corrupto aunque el hub
  caiga a mitad de una transición. Al recargar, una fase `running` huérfana se normaliza a
  `paused` y el paso `running` vuelve a `pending` (sin esto, `POST /api/bootstrap/run`
  respondería `409` para siempre).
- **Contrato de respuesta:** `GET /api/bootstrap/state` (seed exacto de 6 pasos), `POST
  /api/bootstrap/run` (202), `/cancel`, `/retry/:id` — todos bajo token y exentos de rate limit
  sólo `GET .../state` (polling 1/s del wizard).

### Reglas de descarga y rollback

- **SHA256 obligatorio:** `sha256` sin 64 hex en el manifiesto ⇒ *“Falta SHA256 del manifiesto:
  no se descarga nada sin verificación”* — el paso falla **sin descargar**. Descarga en streaming
  con `crypto.createHash("sha256")` sobre el propio stream; mismatch ⇒ tmp borrado +
  `EBADCHECKSUM`; progreso con `content-length`, cancelación cooperativa y timeout duro de 10 min.
- **Hashes oficiales pineados** (fuente: `SHASUMS256.txt`/`SHA256SUMS` oficiales, 2026-09-23):
  - `ubuntu-base-24.04.5-base-arm64.tar.gz` → `a91d5a93010193712…` (`ubuntu-manifest.json`)
  - `node-v24.21.0-linux-arm64.tar.gz` → `724282c3b43aec99…` (`node-manifest.json`)
- **Rollback por paso, sólo de lo creado por ese run:** `run()` registra acciones con
  `ctx.onRollback(fn)` y las ejecuta en **LIFO** (`drainRollbacks`). Inventario con
  `ctx.recordArtifact({type, path|id})`. Si alguna reversa falla ⇒ `rollback: "failed"` y el
  paso queda `failed` reanudable. Nunca se borra un directorio/binario/skill ajeno ni un token.
- **Hooks de test:** `AEGIS_BOOTSTRAP_FAIL=<stepId>` (el `check()` devuelve `done:false` y
  `run()` lanza `FAIL_INJECTED` **antes** de mutar) y `AEGIS_BOOTSTRAP_TEST_STEP=<id>` (paso fake
  al final que crea un artefacto y lanza → rollback real sin red). `AEGIS_BOOTSTRAP_DRY=1`
  simula el progreso con detalle `"[DRY]"` sin mutar ni descargar.

### UI (`SetupWizardScreen` + `BootstrapViewModel`)

Polling 1 s del estado; acciones por fase: **Iniciar instalación / Cancelar / Reintentar paso /
Reanudar / Continuar**. Errores “amigables” (`friendlyError`) con el detalle crudo visible;
cuando hay rollback aparece la tarjeta **“Cambios deshechos — seguro reintentar”**. El arranque
de la app navega al wizard si `phase != done`.

## Consecuencias

- **Criterio de aceptación cumplible:** en dispositivo de fábrica con WiFi, todos los pasos en
  `done` en **≤25 min**; corte de red ⇒ fallo honesto + retry; checksum manipulado ⇒ rollback;
  segundo arranque ⇒ **sin wizard** (`phase=done`).
- **Decisión de diseño (no literal):** el motor **replica en JavaScript** la lógica de
  `find-ubuntu.sh` y `stage-node.sh` en `steps.js` en vez de invocar esos scripts. Consecuencia
  declarada: el **KPI de grafo Kotlin→script** del plan F0 (aristas de la app hacia
  `stage-node`/`find-ubuntu`) **no se cumple de forma literal** — el flujo va Kotlin → API HTTP →
  motor JS. Queda en el **backlog** decidir si se cablean los scripts como fuente de verdad o si
  se retira la expectativa del KPI (los scripts siguen siendo la referencia operativa y de
  diagnóstico; `stage-node.sh` se conserva además como fallback del paso `node`).
- **Dos fuentes de verdad posibles** (scripts shell ↔ motor JS) que deben mantenerse sincronizadas
  cuando cambie una ruta de instalación; mitigación: los manifests JSON son la fuente de hashes
  para ambos y los `check()` leen el estado real, no la historia.
- El fallback **proot** queda detectado y documentado en el `detail` del paso `ubuntu` (hosts sin
  `chroot`/`unshare`), sin probar aquí: en este dispositivo hay chroot y unshare.
- **Coste de mantenimiento:** cada paso nuevo necesita `check()` + `run()` + `rollback` + test.

## Referencias

- `backend/src/bootstrap/{state,steps,orchestrator}.js` y `src/bootstrap/*-manifest.json`
- `backend/src/api/bootstrapRoutes.js` · `app/.../SetupWizardScreen.kt`, `BootstrapViewModel.kt`
- `backend/tests/bootstrap.test.js`, `bootstrap-rollback.test.js`
- `docs/audits/PLAN_MEJORA_FASE0.md` (F1/F2, KPI de grafo) ·
  `docs/audits/AUDITORIA_PROYECTO_Y_ROADMAP.md` (gap 4.1)
