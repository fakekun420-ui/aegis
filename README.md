# Aegis — Mobile Development Hub

App Android (`com.aegis.hub`, Jetpack Compose) que habla **directo con OpenCode**
(`127.0.0.1:49374`, servicio registrado, el mismo que usa el TUI del CLI) desde un
POCO F3 con root + chroot Ubuntu. El Hub intermedio (`:8765`) se retiró entero el
2026-10-02 por decisión del usuario: no hay `backend/`, no hay `server.js`, no hay
`keepalive.sh`.

> ⚠️ **Un solo `opencode serve --service`.** Si un turno parece vacío o atascado,
> busca un segundo servidor antes de depurar nada. Nunca lances otro `serve`.

**Estado: `v1.1.2`** — ver [CHANGELOG](CHANGELOG.md).

- Chat con OpenCode (modelos, agentes, formularios y permisos respondibles).
- Proyectos vinculados a carpetas reales (`ProjectsStore` local; OpenCode no conoce
  el vínculo).
- Skills, Workspace, Workflow, Control Center, TTS/STT, Setup Wizard de 6 pasos.
- Plan de estabilización en curso: [plan](docs/PLAN-ESTABILIZACION-AEGIS.md) ·
  [tablero](docs/PLAN-ESTABILIZACION-ESTADO.md) · [contrato OpenCode](docs/CONTRATO-OPENCODE.md).

```
app (Kotlin, :49374 directo) ──►  opencode serve --service 127.0.0.1:49374
       │                                     ▲
       └──── CompanionService :8766 ─────────┘  (a11y/TTS/STT, loopback)
```

→ [Quickstart](docs/QUICKSTART.md) · [QA checklist](docs/qa/QA_CHECKLIST.md) ·
[Arquitectura](docs/ARCHITECTURE.md)

![Build](https://github.com/fakekun420-ui/aegis/actions/workflows/build-apk.yml/badge.svg)

## Estructura

```
Aegis/
├── app/         # App Android (Compose + Gradle) + install-su.sh + scripts/
├── docs/        # QUICKSTART, ADRs, QA, arquitectura, contratos, auditorías, plan
│   ├── adr/     # ADR-001 seguridad · ADR-002 bootstrap · ADR-003 fin de turno
│   ├── qa/      # QA_CHECKLIST de aceptación
│   └── audits/  # auditorías históricas (crónicas, no se reescriben)
├── tools/       # check_composable.py, chk_forma.py (contratos vs :49374 vivo)
├── .hub/        # Metadatos del proyecto
└── graphify-out/ # Índice del código (ignorado por git; ver AGENTS.md)
```

## Compilar

Sin toolchain Android en el host: la única compilación real es la CI
(`.github/workflows/build-apk.yml`: `lint` · `build-debug` (+ tests JVM) ·
`build-release` · `semgrep` · `gitleaks` · `instrumented` manual). Commits
pequeños, un push por tarea, esperar verde antes de seguir.

## Seguridad

Ver [ADR-001](docs/adr/ADR-001-security-model.md). Keystore de release fuera del
repo. La app usa `su` para leer el chroot y lanzar `opencode serve --service`
(servicio registrado, se levanta solo).

## Documentación

| Doc | Contenido |
|---|---|
| [docs/QUICKSTART.md](docs/QUICKSTART.md) | De cero a producto: APK → wizard → verificación → uso |
| [CHANGELOG.md](CHANGELOG.md) | Historial por versión |
| [docs/CONTRATO-OPENCODE.md](docs/CONTRATO-OPENCODE.md) | Contrato app ↔ OpenCode (generado desde `OpenCodeApi.kt`) |
| [docs/PLAN-ESTABILIZACION-AEGIS.md](docs/PLAN-ESTABILIZACION-AEGIS.md) | Plan de estabilización F0–F10 |
| [docs/audits/](docs/audits/) | Auditorías históricas |

## Stack

POCO F3 (crDroid, Magisk root) · Ubuntu chroot · Jetpack Compose · Kotlin 2.0.21 ·
OpenCode `serve --service` · RootShell (`su`) · Artemis (automatización UI)
