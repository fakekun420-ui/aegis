# Aegis — Mobile Development Hub

Aegis is a self-contained AI orchestration platform running entirely from an Android device (POCO F3, Android 15, root Magisk, Ubuntu chroot), **with root secured by token and a self-service setup wizard**: install the APK, follow the 6-step wizard, and the device provisions itself (Ubuntu + Node + OpenCode + Antigravity + skills) with SHA256-verified downloads and rollback.

**Estado: `v1.0.0` (2026-09-24)** — ver [CHANGELOG.md](CHANGELOG.md) · [Quickstart](docs/QUICKSTART.md) · [QA checklist](docs/qa/QA_CHECKLIST.md)

![Build](https://github.com/fakekun420-ui/aegis/actions/workflows/build-apk.yml/badge.svg)

## Architecture

- **App**: Jetpack Compose Android app (`com.aegis.hub`) — Control Center, Project Workspace, Skill Manager, Workflow Runner, Chat and the **Setup Wizard** (bootstrap de 6 pasos + verificación final + smoke test).
- **Hub**: Node.js orchestrator on **`127.0.0.1:8765`** (loopback only, `X-Aegis-Token` en todo `/api/*` y `/opencode/*`, rate-limit 429) with modular routers, workflow engine, agent system and skill manager.
- **opencode**: daemon on **`127.0.0.1:4096`**, proxyado por el hub; la app sólo habla con el hub.
- **Agents**: Specialized AI agents (Research, Architect, Auditor) operating via artifact-driven communication.
- **Skills**: Graphify, opencode-mem, y un motor de skills con **allowlist** verificada por hash.
- **Workflows**: YAML DAG-based workflow engine for autonomous multi-agent pipelines.
- **Supervisión**: `keepalive.sh` sondea `GET /api/health` cada 10 s y relanza lo caído (hook Magisk `service.d`).

```
app (Kotlin)  ──X-Aegis-Token──►  hub Node 127.0.0.1:8765  ──►  opencode 127.0.0.1:4096
      │                                   │
      └──── CompanionService :8766 ◄──────┘  (a11y/TTS/STT, loopback)
```

## Project Structure

```
Aegis/
├── backend/     # Node.js Hub orchestrator (server.js, src/, tests/, keepalive.sh)
├── app/         # Android Jetpack Compose app + install-su.sh
├── agents/      # Agency agents (vendor)
├── docs/        # QUICKSTART, ADRs, QA, architecture, contracts, audits
│   ├── adr/     # ADR-001 seguridad · ADR-002 bootstrap
│   ├── qa/      # QA_CHECKLIST de aceptación
│   └── audits/  # auditorías históricas (crónicas, no se reescriben)
└── .hub/        # Project metadata
```

## Setup de desarrollo

```sh
cd backend
npm test                 # node --test tests/*.test.js → 54 tests
node --check server.js   # (o find backend -name "*.js" -not -path "*/node_modules/*" -print0 | xargs -0 -n1 node --check)

# projects.json: runtime file (ignorado por git), copiar plantilla si es una instalación limpia:
# cp projects.json.example projects.json

# hub manual (NO hacerlo si ya corre el de producción en :8765)
sh start-hub.sh          # opencode serve :4096 + hub :8765
```

CI (`.github/workflows/build-apk.yml`): `backend-checks` · `lint` · `build-debug` · `build-release` (condicional a secrets) · `semgrep`/`gitleaks` no bloqueantes · `instrumented` manual.

## Seguridad

- **Modelo: root total *con* token, cero acceso sin él** — ver [ADR-001](docs/adr/ADR-001-security-model.md).
- `X-Aegis-Token` (comparación *timing-safe*) exigido en **todo** `/api/*` y `/opencode/*`; única exención `GET /api/health` (sonda de keepalive).
- Bind `127.0.0.1`, CORS con allowlist, rate limit 120 req/min/IP → `429` + `Retry-After`, ids validados contra traversal, meta-caracteres de shell rechazados (`400`).
- Acceso desde otro equipo: **`adb forward tcp:8765 tcp:8765` + token** (no hay URL de red).
- Token en `backend/.aegis_token` (`0600`, 32 bytes aleatorios); keystore de release fuera del repo (plan de rotación en ADR-001).

## Documentación

| Doc | Contenido |
|---|---|
| [docs/QUICKSTART.md](docs/QUICKSTART.md) | De cero a producto: APK → wizard → verificación → uso → troubleshooting |
| [CHANGELOG.md](CHANGELOG.md) | v1.0.0: Added / Changed / Fixed / Security |
| [docs/adr/ADR-001-security-model.md](docs/adr/ADR-001-security-model.md) | Modelo de seguridad y rotación del keystore |
| [docs/adr/ADR-002-bootstrap-design.md](docs/adr/ADR-002-bootstrap-design.md) | Wizard idempotente/reanudable, SHA256 y rollback |
| [docs/qa/QA_CHECKLIST.md](docs/qa/QA_CHECKLIST.md) | Aceptación reproducible por otra persona |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Estructura del monorepo |
| [backend/docs/FRONTEND_CONTRACT.md](backend/docs/FRONTEND_CONTRACT.md) | Contrato app ↔ hub (envelope, health, setup, F4) |
| [backend/docs/BACKEND_ARCHITECTURE.md](backend/docs/BACKEND_ARCHITECTURE.md) | Arquitectura del hub y keepalive |
| [docs/audits/](docs/audits/) | 3 auditorías + plan de mejora Fase 0 (históricas) |

## Web UI Dashboard

Aegis includes a vanilla JavaScript dashboard served from `backend/public/index.html` at `http://127.0.0.1:8765/`:
- **Real-time health**: Memory heap, server uptime, active providers (`opencode` + `antigravity`).
- **Session management**: Active and pinned sessions viewer.
- **System logs**: Live inspection of `/api/system/logs`.
- **Token authentication**: Prompts and stores `X-Aegis-Token` in local browser storage.

## Stack

Android 15 · Jetpack Compose · Node.js v24 · Ubuntu 24.04.5 chroot · Magisk root · OpenCode · Antigravity · Artemis · agency-agents
