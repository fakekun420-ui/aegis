# Aegis — Project Context

> Generated from an exhaustive read of the repository (backend, Android app, scripts, tests, docs, audits, and state/handoff files). Items that could not be resolved from the code or docs are explicitly marked **[UNCLEAR]** under the relevant section instead of being guessed.

---

## 1. Project Overview

**Aegis** is a self-contained AI orchestration platform that runs entirely from an Android device (POCO F3 "alioth", Android 15, Magisk root, Ubuntu 24.04.5 chroot), with root access secured by a token and a self-service setup wizard. The pitch (README v1.0.0, 2026-09-24): install the APK, follow the 6-step wizard, and the device provisions itself (Ubuntu + Node.js + OpenCode + Antigravity + skills) with SHA256-verified downloads and rollback.

### What it consists of

| Component | Role |
|---|---|
| **Android app** `com.aegis.hub` | Jetpack Compose UI (Chat, Projects, Setup Wizard, Control Center, Skill Manager, Workspace, Workflows), voice (STT/TTS/wake word), accessibility service, root shell helper. |
| **Hub (Node.js, zero npm deps)** | HTTP API on `127.0.0.1:8765`, token-gated; multiprovider orchestration (OpenCode + Antigravity, claudecode registered but inert), session/message management, bootstrap engine, device bridge endpoints. |
| **opencode serve** | Local AI coding engine on `127.0.0.1:4096`, proxied by the hub. |
| **CompanionService** | Foreground Android service exposing a loopback HTTP bridge on `127.0.0.1:8766` (accessibility actions, shell, launch, status) for the hub to drive the device UI. |
| **keepalive.sh** | 10-second supervisor loop for hub + opencode, resistant to LMK/OOM; boots via Magisk `service.d` or the app. |
| **Bootstrap engine** | 6-step idempotent, resumable, rollback-capable installer (preflight → ubuntu → node → opencode → antigravity → skills) driven by the in-app Setup Wizard. |
| **Agents / Skills / Workflows** | Lightweight artifact-driven agents (Research/Architect/Auditor — template-based, no LLM), allowlisted skills (graphify, opencode-mem), simple sequential workflow runner. |

### Origin / identity notes
- Migrated from two legacy repos (`opencode-companion` + `opencode-companion-apk`); package renamed `com.opencode.companion` → `com.aegis.hub` (0 legacy hits remain; changelog notes branch-protection rules referencing the old `build` job must be updated by hand). Some files still carry legacy branding: the Compose theme entry is still named `OpenCodeCompanionTheme`, and audit docs reference the old app name.
- GitHub repo badge points to `fakekun420-ui/aegis` (workflow `build-apk.yml`).
- Security history: the hub originally shipped as an **anonymous root RCE** (`0.0.0.0:8765`, CORS `*`, unauthenticated `POST /api/device/shell`). All of that was closed in "Phase 0" and is now enshrined in ADR-001 (see §6).

---

## 2. Current State

### Shipped
- **v1.0.0 released 2026-09-24** (single release; Keep a Changelog + SemVer). `versionName 1.0.0`, `HUB_VERSION "1.0.0"`, `versionCode` from CI `BUILD_NUMBER`/`GITHUB_RUN_NUMBER` (local fallback 2).
- **Backend test suite green**: `npm test` = `node --test tests/*.test.js` → **54 tests** across 10 files (antigravity-attach 7, bootstrap-rollback 3, bootstrap 6, contract 8, cross-contract 9, logs 2, manifest 4, ratelimit 3, security 9, setup 3). No skips/todos. Caveat recorded by the suite itself: sequential runs pass; parallel runs may flake under I/O load (backlog T1).
- **App tests**: 25 JVM unit tests (ModelsEnvelopeTest 11, BootstrapViewModelTest 10, FriendlyErrorTest 4) + 5 instrumented `SetupWizardNavigationTest` (deliberately **not** in CI, F4 decision — manual `connectedAndroidTest` only).
- **CI** (`build-apk.yml`): backend-checks (node --check + tests) → lint (fails if `console.` appears in server.js) → build-debug (artifact `aegis-debug`, 14d) → build-release (conditional on `KEYSTORE_BASE64`, artifact `aegis-release`, 30d) → semgrep + gitleaks (both `continue-on-error: true` until first clean cycle) → instrumented (manual dispatch, emulator API 30).
- **On the actual device**: `bootstrap-state.json` shows `phase: "done"` with all 6 steps completed (root, arm64, Ubuntu 24.04.4 LTS rootfs, Node v24.21.0, opencode v2.0.14, agy 1.2.9 + OAuth token 1428 B, graphify 1/1). The production hub runs on :8765 under keepalive.
- **Security hardening** (ADR-001, verified by `security.test.js`): loopback-only bind, timing-safe token on all `/api/*` and `/opencode/*` (sole exemption `GET /api/health`), rate limit 120/min/IP, ID validation + shell-meta rejection, path-traversal guards, CORS allowlist.

### Not yet done (summary; detail in §7)
- **QA_CHECKLIST: 8/8 cases still open** (no device acceptance run with evidence; the golden rule is "un PASS sin evidencia no cuenta").
- **The wizard does not yet answer "PONG" end-to-end**: smoke-test against live OpenCode returns `502 SMOKE_FAILED` because the hub's live OpenCode adapter speaks API **v1** while the installed serve exposes **v2** (backlog T2/B1). The smoke endpoint itself works (unit-tested with a fake repo; the app test shows reply "¡Hola desde Aegis!").
- **`docs/QA_REPORT.md` (v1.0.1) is explicitly disputed** by audit finding H-08: its "all tests PASSED / zero bugs / no pending actions" claims were declared irreproducible and contradicted by code. Treat as invalid until re-executed with evidence.
- **Release criteria (§5 of PLAN_SIGUIENTES_ACCIONES) all unchecked**: device QA 8/8, release keystore, semgrep/gitleaks non-tolerated, smoke PONG vs OpenCode v2, LLM graph labels, `v1.0.0` tag pending acceptance.
- **Phase registry is fragmented**: `AEGIS_MASTER_PROMPT` phases 0–4 done / 5–11 prescribed (with a note that phase 7 model routing was built then deliberately deleted), audits use their own F0–F7 / A-1…A-7 coding, and the CHANGELOG bundles everything into F0–F5. Which numbering is canonical going forward is **[UNCLEAR]**.

### Known-quality caveats
- Test count claims drift across artifacts: CI mirror comment "46 tests", canonical workflow and docs "47", actual suite "54". The tests themselves are the source of truth.
- Several docs describe behavior superseded by code (v1 OpenCode API, old SSE, string-shaped errors). The live truth is in `backend/providers.js` + `backend/docs/FRONTEND_CONTRACT.md` v1.3.0.

---

## 3. Tech Stack

### Backend (`backend/`)
- **Node.js 18+ (device runs v24.21.0)**, ESM (`"type": "module"`), **zero npm dependencies** — only builtins (`node:http`, `node:crypto`, `node:fs`, `node:child_process`, `node:test`).
- HTTP: hand-rolled server (`server.js`, 3156 lines) with a custom router/middleware chain; SSE for streaming; envelope JSON responses `{ok:true,data}` / `{ok:false,error:{code,message}}`.
- Storage: flat JSON files with a `FileMutex` + atomic write (tmp → fsync → rename). Master store: `projects.json`.
- Tests: `node:test` + `assert/strict`, harness spawns the real `server.js` on an ephemeral port.

### Android app (`app/`)
- **Kotlin 2.0.21**, **Jetpack Compose** (BOM `2024.10.00`, material3 1.3.1, material-icons-extended 1.7.3), AGP **8.7.3**, `compileSdk/targetSdk 35`, `minSdk 26`, Java 17.
- Architecture: **MVVM with StateFlow**, no DI framework (Hilt/Koin absent), no Room, no Coil; navigation via `navigation-compose 2.8.4` (single `NavHost`, string routes).
- Networking: **Retrofit 2.11.0 + Gson 2.10.1** (`ApiClient` service) plus raw **OkHttp 4.12.0** for SSE, session creation, and hand-built PATCH; `X-Aegis-Token` added by interceptor, reading the token file via `su`.
- Tests: JUnit 4.13.2, kotlinx-coroutines-test 1.8.1 (fakes build `retrofit2.Response` by hand — explicitly no MockWebServer), Compose UI test (instrumented).

### Runtime / device
- Android 15 (crDroid) on POCO F3 alioth, **Magisk/KernelSU/APatch root**; Ubuntu 24.04.5 aarch64 rootfs in **chroot + unshare** (proot detected as fallback but *not* installed on this device).
- opencode serve v2.0.14 (`/usr/local/bin/opencode` or `opencode.cjs` + `node.bin` wrapper); Antigravity CLI `agy` 1.2.9 (206 MB ELF at `/root/.local/bin/agy`, not from npm); skills in `~/.config/opencode/skills/`.
- Key third-party services/tools: OpenCode, Antigravity/Artemis (mobile agent MCP), graphify (code-graph tool), agency-agents (vendored `agents/` directory).

### Build & CI/CD
- Gradle 8.9 (pinned by CI; **no gradle-wrapper committed** — backlog B6), GitHub Actions, artifacts `aegis-debug`/`aegis-release`, signing via `KEYSTORE_BASE64` secret, semgrep + gitleaks (tolerated failures for now), optional Gemini API key for graph labels.

---

## 4. Project Structure

```
/sdcard/projects/                  ← git repo root (branch main)
├── .github/workflows/build-apk.yml   ← CANONICAL CI (paths prefixed "Aegis/")
├── docs/                             ← top-level docs (QUICKSTART, adr/, qa/, audits/)
│   ├── AEGIS_*.md                    ← phase prompts (MASTER_PROMPT, PHASE11, BUILD_AND_QA, QA)
│   ├── AUDITORIA_*.md                ← 3 immutable audit chronicles (2026-09-23)
│   ├── PLAN_MEJORA_FASE0.md          ← Phase-0 attack plan + actas
│   ├── PLAN_SIGUIENTES_ACCIONES.md   ← closure + prioritized queue (I/T/B items)
│   ├── QA_REPORT.md                  ← disputed v1.0.1 report (see §2)
│   ├── qa/QA_CHECKLIST.md            ← 8 acceptance cases, all open
│   ├── adr/ADR-001, ADR-002         ← security & bootstrap decisions
│   └── PROJECT_CONTEXT.md            ← this file
├── Aegis/
│   ├── backend/                   ← the Node hub (see below)
│   │   ├── server.js              ← monolith HTTP server/router (3156 L)
│   │   ├── providers.js           ← LIVE adapters + ProviderManager (1784 L)
│   │   ├── src/core/              ← storage, pathResolver, projectManager, eventBus,
│   │   │                            agentPool, workflowParser, workflowEngine, jobScheduler, logger
│   │   ├── src/api/               ← skills, projects/workspace, jobs, agents, workflows,
│   │   │                            content, bootstrap, setup routers
│   │   ├── src/adapters/          ← LEGACY MIRRORS (mostly dead; see note)
│   │   ├── src/bootstrap/         ← steps.js (6 steps, 1229 L), orchestrator, state, manifests
│   │   ├── src/skills/            ← catalog.json allowlist, SkillManager, SkillInvoker
│   │   ├── src/agents/            ← BaseAgent + Research/Architect/Auditor (template-based)
│   │   ├── src/plugins/content/   ← dead content plugin (moneyPrinter stub)
│   │   ├── tests/                 ← 10 test files, 54 tests
│   │   ├── keepalive.sh, start-hub.sh, service.d-99-opencode-hub.sh,
│   │   │   find-ubuntu.sh, stage-node.sh, opencode.sh, patch_*.py
│   │   ├── projects.json          ← MASTER DATA STORE (tracked, dirty in worktree)
│   │   ├── docs/                  ← BACKEND_ARCHITECTURE, FRONTEND_CONTRACT v1.3.0 (canonical)
│   │   ├── context/pony-tail-global.md  ← IMMUTABLE global context (needs approval to edit)
│   │   ├── .ponytail.md           ← mutable backend work-state
│   │   └── (runtime, mostly gitignored: .aegis_token, bootstrap-state.json, ui-state.json,
│   │        providers.json, .companion-session.json, logs/, hub.log, opencode.log,
│   │        node.bin, opencode.cjs, public/ [ABSENT in checkout], skills/ [ABSENT])
│   ├── app/                       ← Android project (Gradle root "Aegis", module :app)
│   │   ├── build.gradle.kts, settings.gradle.kts, gradle.properties
│   │   ├── install-su.sh          ← root installer (6 steps) for POCO F3
│   │   └── app/src/main/kotlin/com/aegis/hub/
│   │       ├── MainActivity.kt        ← startup, hub check, root-boot, wake word, TTS
│   │       ├── CompanionService.kt    ← :8766 loopback bridge (a11y/shell/launch)
│   │       ├── CompanionVoiceInteraction*.kt  ← default-assistant voice pipeline
│   │       ├── OpencodeAccessibilityService.kt
│   │       ├── RootShell.kt           ← su/sh exec, quoting, monkey/tap/key/text
│   │       ├── data/                  ← ApiClient, TokenProvider, ApiService (45 endpoints),
│   │       │                            Models.kt (416 L), BootstrapRepository
│   │       ├── ui/                    ← AppNavHost, NavRoutes, theme, 15 screens,
│   │       │                            viewmodel/ (Main, Chat, ProjectDetail, Bootstrap,
│   │       │                            ControlCenter, SkillManager, Workspace, Workflow)
│   │       └── res/                   ← 3 strings, themes, a11y/voice configs
│   │       (tests: 25 JVM + 5 instrumented)
│   ├── agents/                    ← VENDORED third-party agent repo (untracked; described, not audited)
│   ├── .hub/                      ← project metadata (untracked)
│   └── docs/, graphify-out/       ← app docs (FRONTEND_CONTRACT v1.2.0 ⚠, UI_AUDIT_CLAUDE_STYLE),
│                                    graph snapshots
└── (workspace data) /sdcard/projects/*  ← user projects; PROJECTS_ROOT
```

Structure notes:
- **Two adapter layers exist**: the live ones inside `backend/providers.js` (used by `server.js`), and intentional legacy mirrors in `backend/src/adapters/` (`OpenCodeAdapter.js` marked "⚠️ ESPEJO LEGADO — NO USADO POR EL HUB"; only `ClaudeCodeAdapter` is imported from there; `GitAdapter.js` has no importers at all). Unification is backlog **B3**.
- **`backend/public/` and `backend/skills/` do not exist in the checkout** → all non-API requests return 404 JSON (never SPA), and `listSkills` returns empty. The README/web-UI references are therefore partially aspirational. **[UNCLEAR]** whether a web UI is meant to ship.
- Repo root git status: `backend/projects.json` modified (runtime data store), `agents/`, `.hub/`, `docs/screenshots/*` untracked.

---

## 5. Data Flow

### 5.1 Message send (the core pipeline)

```
App (ChatViewModel.sendWithFiles)
  ├─ anti-double-send guard (inFlightSendKey, FASE A-5)
  ├─ optimistic user message (PENDING, id "local_<ts>"); text files inlined ≤30k chars,
  │   binary files as data-URI base64 parts (screen-side >5MB → placeholder)
  ├─ session resolution: existing id, else raw POST http://127.0.0.1:8765/opencode/session
  │   (X-Provider; tolerant id/ID/data.id parse; model "gemini-3.8-flash-high")
  └─ PRIMARY: SSE POST /api/opencode/sessions/{id}/message?stream=true
        headers: Accept: text/event-stream, X-Provider, X-Model, X-Agent, X-Mode
        events: chunk | tool_start | tool_done | done{message} | error
     FALLBACK (classic POST) only if streaming never got an accepted response (A-5 rule)
     BACKGROUND POLL: GET messages every 1.5s × 50 (~75s) until assistant reply appears
     finally: close SSE body (fd-leak fix), delay(400) → canonical GET messages sync
           ↓
Hub (server.js) POST /opencode/session/:id/message  [?stream=true ⇒ SSE]
  ├─ readJsonBody cap 55MB (F7; attachments broke the old 512KB)
  ├─ provider = id-prefix always wins (agy_→antigravity, ses_→opencode)
  ├─ fileMutex: link session↔project (only if X-Project-Id explicit), provider set-once
  ├─ auto-title (technical/placeholder titles → first prompt, 30 chars + "…")
  ├─ resolveProvider precedence: stored session provider → id-prefix convention →
  │   explicit header → brain-dir heuristic → defaultProvider ("antigravity")
  └─ adapter.sendMessage(...)
       ├─ OpenCode (HTTP v2): Basic opencode:<password> (scraped from opencode.log tail,
       │    TTL 3s, 401→1 retry); session instructions PUT (context block, hash-dedup);
       │    POST model/agent; POST prompt (3 retries, 409 backoff, files stripped on 4xx);
       │    then POLL GET message every 1s up to 70s → single full-text emit
       └─ Antigravity (spawn CLI): prompt wrapped in <SYSTEM_INSTRUCTION>/<USER_REQUEST>;
            attachments materialized to disk (/root/.gemini/antigravity-cli/attachments/,
            ≤15MB) + absolute path + view_file; args include --output-format stream-json,
            --dangerously-skip-permissions, --mode plan|accept-edits; NDJSON events
            (init/step_update/result/tool_*/text_delta); hard timeout 90s with
            SIGTERM→SIGKILL group escalation; conversation id recovered from events or brain dir
           ↓
App renders: streaming text (blink ▋), ToolExecutionCards, then canonical Message;
delivery status PENDING → SENT (or ERROR + "Reintentar")
```

Read-back: `GET /api/opencode/sessions/{id}/messages` → `getUnifiedMessages()` merges both adapters by timestamp, normalized to the canonical `Message` shape (`info` + `parts[]`).

### 5.2 Context injection (project-aware prompts)
`proxyWithInjection` (legacy POST paths only): resolves `projectId` (body → header → projects.json lookup; never falls back to `ui-state.json`), builds a ≤24k-char system block from `context/pony-tail-global.md` + `<project>/.ponytail.md` + skills + linked-project summaries (max 8) + fixed Spanish execution directive, deduped via `[SYSTEM CONTEXT` marker, injected into `parsed.system` (never user parts). Skipped if payload >512KB. The in-app chat instead sends explicit `X-Agent`/`X-Mode` headers and strips injection markers display-side (`strippedText()`).

### 5.3 Session lifecycle
- **Create**: `POST /opencode/session` (or `/api/sessions`, or `POST /api/projects/{id}/sessions`). OpenCode → v2 `POST /api/session` → `ses_…`; Antigravity → local id `agy_<base36>_<rand>` (no process spawned at creation).
- **Persist**: `projects.json → projects[].sessions[]` + global `sessionTitles` map (atomic writes under `FileMutex`).
- **Resume**: no resume endpoint; the client reuses the id. Antigravity continuation via `sessionMap`/`agyConversationId` → `--conversation <convId>`; messages read from `brain/<convId>/.system_generated/logs/transcript.jsonl`.
- **Delete**: removes from projects, purges titles, detects provider, calls adapter delete (Antigravity removes brain dir). App keeps tombstone sets so deleted sessions don't reappear on refresh.
- Active session pointer: `ui-state.json` (written atomically; **not** used as a session→project fallback — comments confirm).

### 5.4 Bootstrap / setup flow
```
App launch → GET /api/system/status (3s, token) → ready?
  ├─ no  → "Sistema desconectado" overlay → "Iniciar Sistema"
  │        → RootShell runs find-ubuntu.sh → chroot /proc/<pid>/root nohup keepalive.sh
  │        → poll health 90×500ms (45s) → may flip route to SETUP
  └─ yes → GET /api/bootstrap/state (2.5s) → phase != "done" ? SETUP : DRAFT_CHAT

SetupWizardScreen ⇄ BootstrapViewModel (poll GET state every 1s while running)
  POST /api/bootstrap/run {resume:true} | /step/{id}/retry | /cancel
  Engine (orchestrator): per step check(idempotent) → skip if done, else run;
    failure → rollback best-effort LIFO ("none"|"done"|"failed") → phase failed (STOP);
    crash recovery: persisted "running" with no live runner → "paused" (reanudable)
  phase done → FinalVerificationCard:
    GET /api/setup/final-check (4 checks: opencode/antigravity/a11y/bootstrap)
    POST /api/setup/smoke-test (real "Responde exclusivamente: PONG", 60s budget)
    POST /api/setup/auth/antigravity → manual command only (hub never logs in for the user)
```
Downloads: SHA256 **mandatory** — no 64-hex hash ⇒ no download (`EMISSINGSHA`), hash verified in-stream, mismatch → `EBADCHECKSUM` → rollback.

### 5.5 Auth / security flow
- Hub generates `.aegis_token` (32 random bytes, hex, mode 0600) on first run; never logged, never returned in HTTP responses (tests assert this on health/final-check/auth/manifest).
- App reads it via `su -c cat` (2s cooldown cache), attaches `X-Aegis-Token` to **every** request; on 403 it re-reads once and retries only if the token changed (rotation support).
- Rate limit (120/min/IP, sliding 60s) runs **before** token middleware; exempt: `GET /api/health` and `GET /api/bootstrap/state` (wizard 1s polling). Off-switch: `AEGIS_RATE_LIMIT=0`.
- CompanionService :8766 validates the same token timing-safe (`MessageDigest.isEqual`) before executing anything; CORS allowlist `{http://localhost:8765, app://aegis}` only.

### 5.6 Supervision loop
`keepalive.sh` (10s): probes `GET /api/health` (must contain `"server":"running"`) and `curl :4096`; restarts opencode `serve` only (never the TUI, never `server.js` — 4-way cmdline filters); restarts the hub with **A-7 backoff** (single failed probe → 4s backoff → "blip descartado"; two consecutive → restart). Lock file prevents duplicates; mount-NS contract: if `NODE_BIN` isn't executable in this namespace it exits 3 with "caller must export NODE_BIN" instead of pid-guessing loops. Boot path: Magisk `service.d-99-opencode-hub.sh` (~20–30s after /sdcard mounts, `nsenter -t 1 -m`) or the app's "Iniciar Sistema" button.

---

## 6. Key Decisions & Constraints

### ADR-001 — Security: "root total con token, cero acceso sin él" (accepted 2026-09-23)
Context: anonymous root RCE (finding count: 55 backend + 62 frontend audit findings). Four enforced layers: (1) loopback-only bind on 8765/8766; (2) timing-safe token on all `/api/*` and `/opencode/*` with the single documented exemption `GET /api/health` (a secretless probe the keepalive consumes every 10s — removing the exemption would create a hub death-loop); (3) rate limit before token middleware (exempt health + bootstrap state); (4) CORS allowlist, never `*`. Plus shell-meta regex → 400, id validation ≤256 chars → 400, RootShell escaping, skill allowlist. Consequence: external access only via `adb forward` + token. Open follow-up: keystore rotation plan A-6.2 (keystore is untracked; **"not rotated nor deleted in this phase"**).

### ADR-002 — Bootstrap: idempotent, resumable wizard with rollback (accepted 2026-09-23)
6 steps with a strict hook contract (`check()` must be read-only and idempotent; `run(ctx)` can register rollbacks; rollback is LIFO best-effort). Hard rules: SHA256 required for every download (pinned: ubuntu `a91d5a93…`, node `724282c3…` for node-v24.21.0-arm64), cooperative cancellation (15s), atomic state file, orphaned `running` → `paused` on restart. Acceptance: fresh device + WiFi provisions in ≤25 min. **Honest KPI note**: the engine *replicates* `find-ubuntu.sh`/`stage-node.sh` logic in JS instead of invoking them, so the original "Kotlin → script" KPI is **not literally met** (flow is Kotlin → HTTP → JS); scripts remain operational fallbacks — backlog B5.

### Other binding decisions
- **FRONTEND_CONTRACT.md is the source of truth** for API shapes and *changes the backend*, not the app (A-3 decision). Canonical copy: `backend/docs/FRONTEND_CONTRACT.md` **v1.3.0**. The app copy `app/docs/FRONTEND_CONTRACT.md` is **v1.2.0** and known-stale (raw-array messages, `project_id` snake_case) — which copy the app team should follow is **[UNCLEAR]**, but v1.3.0 is the contract the tests enforce (cross-contract tests literally parse `Models.kt` and assert the doc contains §7.1 verbatim — *the doc is part of the contract test suite*).
- **Provider defaults are intentional, not routed**: `modelRouter`/`taskClassifier` were built in phase 7, had **zero runtime consumers**, and were deliberately deleted. Default `provider=antigravity` + `model=gemini-3.8-flash-high` stands as an intentional hardcode; any future task routing must be config-driven (documented reversal inside `AEGIS_MASTER_PROMPT.md`, which still contains the old promise — flagged as a contradiction).
- **Session provider is immutable after birth** (F6): a session's provider can't be switched in the UI; model lists are cleared rather than reused across providers (prevents showing another provider's v1 models).
- **OpenCode adapter speaks v2 in `providers.js`** (Basic auth scraped from `opencode.log`, `/api/session|prompt|model|agent|message`), while `src/adapters/OpenCodeAdapter.js` is an intentionally obsolete v1 mirror ("do not sync by hand"). The known v1/v2 serve mismatch is documented inside `setupRoutes.js` and is the root cause of the open smoke-test failure (T2/B1).
- **Immutable files**: `backend/context/pony-tail-global.md` is marked "🔒 INMUTABLE Y PERMANENTE" (4 absolute prohibitions: never reboot/shutdown; never touch /system|/vendor|/product|/system_ext|/apex|/data/adb/modules; never kill critical processes (zygote, surfaceflinger, system_server, a11y services) or Termux/SSH or **keepalive.sh**; never edit this file). Editing it (e.g., 3 stale legacy paths it still cites) requires explicit user approval — backlog B7. `backend/.ponytail.md` is the mutable sibling. Audit chronicles are likewise immutable ("crónicas, no se reescriben").
- **Cleartext HTTP is deliberate**: `usesCleartextTraffic="true"` because Android's `networkSecurityConfig` can't reliably allowlist a raw loopback IP; mitigations are backend-side (loopback bind + token). A-6.3 (networkSecurityConfig + autoVerify) remains open.
- **No deep links / no BROWSABLE**: `aegis://` is intentionally not declared (no assetlinks.json).
- **Env-specific constraints**: /sdcard is FUSE → `fsync`/`rename` atomicity is **not guaranteed** (DATA-02, open); symlinks under /sdcard break Gradle builds (build from `/root/`); `simple_lmk` hangs `pkill -f` for 120s (hence `timeout 5 pgrep`); process "wedged" D-state on FUSE logs previously caused keepalive respawn loops (manual quarantine convention, backlog T1 is the real fix).
- **Test isolation rule (not yet enforced)**: tests must spawn with their own `AEGIS_LOG_DIR` (root cause of a past FUSE incident and `logs.test` flakiness).

---

## 7. Pending Features / Roadmap

Source: `PLAN_SIGUIENTES_ACCIONES.md` (2026-09-24), audit backlogs, and TODO-equivalent comments. Note: the codebase contains **no literal `TODO/FIXME/HACK` comments** (backend or app); work is tracked with coded prefixes (`A-n`, `F-n`, `B-n`, `T-n`, `I-n`, `BUG-15`, `UX-04`, `H-n`, `SEC-n`, `ARQ-n`, …).

### §1 Immediate (I-1…I-5)
1. **I-1** Download release APK artifact (`aegis-release`; signed with debug key until a real keystore exists).
2. **I-2** Execute device QA per `docs/qa/QA_CHECKLIST.md` (clean install ≤25min → cut WiFi → retry → tampered checksum → rollback → 2nd boot w/o wizard → final-check PONG → 403 sweep) with screenshots.
3. **I-3** Create/protect release keystore (keytool RSA **4096** + `gh secret set`). ⚠ Conflict: ADR-001 says 2048.
4. **I-4** Review first semgrep/gitleaks cycle → remove `continue-on-error`.
5. **I-5** Branch protection: rename required check `build` → `build-debug`.

### §2 Next week (T1–T5)
1. **T1** (~1h) Isolate test logs (`AEGIS_LOG_DIR` per spawn; keep `--test-concurrency=1` in CI) — fixes the FUSE-wedge root cause and logs.test flakiness.
2. **T2** (~2h) **Real smoke test vs OpenCode v2** — the last link for "wizard answers PONG" (currently 502 `SMOKE_FAILED`).
3. **T3** (~1h) `listModels` for v2 (current WARN: `listModels fetch error: <!doctype` — SPA HTML).
4. **T4** (~1h) Move `/event` and `/global/event` (SSE aliases) **behind the token middleware** — open security item.
5. **T5** (~15min) graphify `--missing-only` labels with `GEMINI_API_KEY`.

### §3 v1.0.1 milestone / F6 (B1–B10)
1. **B1** Adapt `OpencodeAdapter` to OpenCode v2 (unblocks T2/T3).
2. **B2** Resolve `Envelope.error` divergence (`String?` in Kotlin vs `{code,message}` object) — align Kotlin model, drop the §7.1 caveat.
3. **B3** Unify duplicated adapters (`providers.js` vs `src/adapters/`).
4. **B4** Replace remaining ~7 runtime `console.*` calls (eventBus, workflowParser, jobScheduler, BaseAgent, content/index, bootstrap/state) with the logger.
5. **B5** Close the "Kotlin → script" KPI: make the engine invoke `find-ubuntu.sh`/`stage-node.sh`, or formally accept the equivalent KPI.
6. **B6** Generate + commit the Gradle wrapper (`gradle wrapper --gradle-version 8.9`).
7. **B7** Fix 3 stale legacy paths in `pony-tail-global.md` (needs explicit approval — immutable file).
8. **B8** Run the instrumented test suite in CI at least once before trusting it.
9. **B9** Re-extract semantics for 12 docs (needs API key).
10. **B10** Keystore rotation (I-3) + CHANGELOG entry (formal close of A-6.2).

### §4 Minor backlog (≤30min each)
- Decide whether `projects.json` should be tracked (it's modified in the worktree as runtime data).
- Document the honest 404 flow (no `public/` → no web UI in checkout).
- Rename `service.d-99-opencode-hub.sh` only with a migration plan (name is installed on live devices under `/data/adb/service.d/`).
- Cosmetic cleanup of legacy CI job name "Build Companion APK" in history.
- `friendlyError()` has **no "timeout" branch** (documented by a test).

### Open audit findings never confirmed fixed
- Backend: SEC-05, SEC-06, SEC-08, SEC-09, SEC-11…13, BUG-05, BUG-06, BUG-07, BUG-08, BUG-13, BUG-16, ARQ-02 (monolith), ARQ-03 (three sources of truth for "project"), API-03, API-04, DATA-01 (projects.json re-parsed per request), DATA-03, OPS-04/05/06, all 12 LOW findings (incl. `agentPool.cancelAll` returns success but cancels nothing; workflow engine isn't a real DAG and the 2-agent cap breaks >2 concurrent steps).
- Frontend: UX-06 (**three visual identities** — Claude palette vs GitHub-dark hexes vs CLI mono; disputed by `UI_AUDIT_CLAUDE_STYLE.md` "Aprobado al 100%"), UX-07 (dark-only), SEC-04/05/07, BUG-10…17, INT-02/03/04, A11Y-06/07/10, PERF-01…06, DEAD-02…09, BUILD-04/05, DOC-03.
- Product-level: content plugin (phases 9) is a stub; jobs (phase 8) is only `companion-meta-sweep`; agents (phase 5) are template-based with no LLM.

### UI-level dead/partial features found in code (candidates for roadmap)
- "Fijar" (pin) on chats is a **no-op callback**; on projects it's a local `Set` that never persists or reorders.
- Archive dialog promises "la sección de archivados" — **no archived section exists anywhere**.
- `MarkdownText.onLinkClick` is never invoked → links render underlined but are not clickable.
- Voice screen: settings gear, "Nuevo", and model chip buttons are no-ops; its TTS queue speaks only the first chunk; assistant replies can be re-spoken without dedup.
- Home drawer's voice (headphones) button is dead on the draft screen (`onVoice = {}`).
- `NavRoutes.MAIN` declared but unused; three screens accept an unused `navController`.

---

## 8. Environment & Setup

### Device prerequisites (from QUICKSTART)
- ARM64/aarch64 (POCO F3 alioth, Android 15, crDroid), root via Magisk/KernelSU/APatch (the `preflight` step enforces euid 0 or `su`), WiFi reaching `cdimage.ubuntu.com`, `nodejs.org`, `registry.npmjs.org`, ≥0.5 GB free (checked; unreadable free-space does not block), adb optional, JDK17+SDK optional (CI publishes artifacts). **[UNCLEAR]**: `UI_AUDIT_CLAUDE_STYLE.md` claims Android 16 (crDroid) — README/QUICKSTART say Android 15.

### Install (end user)
1. APK: (A) Actions artifact `aegis-debug` → `/sdcard/Download/app-debug.apk` → `su -c "sh /sdcard/projects/Aegis/app/install-su.sh"` (installs via `nsenter`/`pm`, grants RECORD_AUDIO/POST_NOTIFICATIONS, enables the accessibility service, doze-whitelists the app, starts `CompanionService`, verifies `:8766/status` + `:8765/api/status`); or (B) `adb install -r` + enable accessibility manually.
2. First launch: app checks hub → offline ⇒ "Sistema desconectado" overlay with **Iniciar Sistema** (runs `find-ubuntu.sh` → `chroot … keepalive.sh`); bootstrap `phase != done` ⇒ auto-navigates to the 6-step Setup Wizard.
3. Normal operation needs no further interaction; keepalive (10s) sustains hub + opencode.

### Development
```bash
cd Aegis/backend
npm test                  # → 54 tests (docs say 47; CI mirror comment says 46)
node --check server.js    # required after every JS change (master-prompt rule)
sh start-hub.sh           # manual start (kills previous; explains loopback-only banner)

# health / auth
curl http://127.0.0.1:8765/api/health          # only token-free route
TOKEN=$(cat Aegis/backend/.aegis_token)
curl -H "X-Aegis-Token: $TOKEN" http://127.0.0.1:8765/api/setup/final-check

# from another machine
adb forward tcp:8765 tcp:8765                  # never expose a LAN URL

# app (build from /root/, not /sdcard — symlink issues)
cd Aegis/app && gradle assembleDebug           # no gradle wrapper committed yet
```

### Environment variables & flags
| Var | Meaning |
|---|---|
| `HUB_PORT` / `--port` | hub port (default 8765) |
| `OPENCODE_PORT` / `--opencode-port`, `OPENCODE_HOST` | opencode serve (default 4096) |
| `AEGIS_RATE_LIMIT` / `AEGIS_RATE_LIMIT_N` | disable ("0") / cap (default 120 per min) |
| `AEGIS_LOG_DIR`, `AEGIS_LOG_MAX_BYTES` | log sink (default `backend/logs`, 1MB rotation) |
| `AEGIS_BOOTSTRAP_DRY`, `_STEP_DELAY_MS`, `_FAIL`, `_TEST_STEP`, `_TEST_ARTIFACT` | bootstrap test hooks |
| `BOOTSTRAP_STATE_FILE` | bootstrap state path (tests use a temp file — the real one is never touched) |
| `AEGIS_UBUNTU_DIR`, `AEGIS_NODE_DIR` | override rootfs/node install targets |
| `NODE_BIN` | must be exported into keepalive's mount namespace (else exit 3) |
| `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` | CI signing |
| `SEMGREP_APP_TOKEN`, `GITHUB_TOKEN`, `GEMINI_API_KEY` | CI scanning / graph labels |

### Key ports & endpoints
- **8765** hub (loopback only; `/api/*` + `/opencode/*` token-gated except `GET /api/health`; rate-limited; 404 JSON for unknown API paths).
- **4096** opencode serve (proxied by the hub; never exposed directly in the URL space the app uses; upstream 502 hint tells you to start `opencode serve --port 4096 --hostname 0.0.0.0`).
- **8766** CompanionService bridge (loopback, token timing-safe, CORS allowlist; routes `/status`, `/dump`, `/a11y`, `/shell`, `/launch`).

### State files (runtime)
`.aegis_token` (0600, gitignored) · `projects.json` (**master store**, tracked) · `ui-state.json` · `providers.json` · `.companion-session.json` · `bootstrap-state.json` (gitignored) · `summaries/<id>.summary.json` · `skills/<scope>/<name>.skill.md` (dir absent) · `logs/aegis.log` (+ `.1 .2 .3`), `hub.log`, `opencode.log` (contains the v2 Basic password — treat as secret) · `keepalive.lock` · `brain/<convId>/…/transcript.jsonl` · `attachments/<sid>/…` · `<project>/.hub/{project,state}.json` · `<project>/workflows/*.{json,yaml,yml}`.

---

## 9. Open Questions

### Backend
1. **Dead provider branch**: in `server.js` (≈L1296–1302) both branches of the unknown-provider ternary return `"antigravity"` — is the else-branch supposed to be `"opencode"`? (audit BUG-05, never confirmed fixed).
2. **Default model behavior**: `gemini-3.8-flash-high` is hardcoded as body default; audit BUG-06 flagged that this can produce 400s against some daemons — resolved as "intentional" by the phase-7 reversal, but the audit finding was never formally closed.
3. **`backend/public/` missing** → no web UI in the checkout, yet README/app docs describe one ("hub web", "Open en Chrome del POCO F3"). Ship a web UI or update the docs?
4. **`backend/skills/` missing** → `listSkills` always `[]`; skills actually live in `~/.config/opencode/skills/` (scanned by `SkillManager`). Which location is canonical for the API?
5. **Two id regexes**: `ID_RE` (allows dots/spaces-free but dots) vs `isValidProjectId` (`^[a-zA-Z0-9\-_]+$`, no dots/spaces). A project named "Agencia de Marketing" would pass creation but throw in `pathResolver` (audit BUG-07). Intended behavior?
6. **`GET /api/system/status` returns a legacy un-enveloped object** that the app depends on — keep as documented exception, or migrate the app?
7. **Assistant "llm_classify"**: the backend returns `{action:"llm_classify", hint}` and says "the real classifier is on the client", but no client-side classifier was found beyond the voice service's simple flow — where does classification actually happen?
8. **Header comment claims TTS/STT host routes** but no `/api/tts`/`/api/stt` exist (only voice command/log + assistant intent/execute).
9. **claudecode** is registered in `listProviders()` but absent from `providers.json`, and `loadConfig()` ignores the `providers[]` array (`enabled`/`type` inert). Is the provider list config-driven or code-driven?
10. **`dispatchRoute` contract is fragile**: routers return `false`/`null` to mean "not mine"; a `throw` becomes 500. Was a sentinel object ever intended?
11. **`GitAdapter.js` and `SkillInvoker` (outside ResearchAgent)**: any live route that reaches them? None found by import search.
12. **`BaseAgent.validate()`**: abstract, never implemented by subclasses, never called — dead abstraction?
13. **`agentPool.cancelAll()`** returns success without signalling running agents; `workflowEngine` doesn't await steps and its 2-agent/project cap breaks >2-step workflows — fix or document as unsupported?
14. **`ui-state.json` double-read** and `/api/system/start` overlap with keepalive — benign duplication or a race?
15. **"wedged" log files** (`logs/wedged/*`, `hub.log.wedged-*`): manual quarantine (per PLAN §0) — no code performs this rename. Confirm manual convention?
16. **Upstream opencode DB bug** (`UNIQUE(aggregate_id, seq)` corruption in v1.18.31): `backend/NOTES.md` has a verbatim open TODO — "upstream report (GitHub opencode issues) should attach: the reproduction above, the version 1.18.31, and the log excerpt" — **was this issue ever filed?** No evidence in the repo.

### Android app
17. **`/a11y-repair`**: `OpencodeAccessibilityService`'s comment says "CompanionService exposes POST /a11y-repair on :8766 and the HUB performs the `settings put secure`" — but CompanionService routes only `/status /dump /a11y /shell /launch`. Stale comment, or is the hub-side route missing? (The self-heal currently only flags `needsA11yRepair` in `/status`.)
18. **`activity_main.xml` (151 lines, legacy WebView layout)**: no `setContentView`/binding reference anywhere. Intentional legacy, or dead? (Related: `viewBinding = true` and the `androidx.webkit` dependency may also be dead — audit DEAD-02.)
19. **`assets/stage-node.sh`**: never invoked from Kotlin (0 grep hits); byte-identical copy exists in `backend/`. Keep as manual fallback or remove?
20. **Two project screens/models**: `ProjectsScreen` (`Project`, `getProjects()`, projects.json-backed) vs `WorkspaceScreen` (`ProjectItem`, `getWorkspaceProjects()`, fs-scan-backed). Which is canonical for the user? (Backend supports both: `/api/projects` vs `/api/workspace/projects?source=fs`.)
21. **Session id schema drift**: the app matches `resolvedId || id || ID || sessionId` and infers provider from the `agy_` prefix — which field is the canonical session id across all four naming variants?
22. **`SendMessageRequestWithModel`** appears unused — dead model?
23. **Two Retrofit methods on `GET api/skills`** with different return shapes (`Envelope<SkillListResponse>` vs `Response<SkillsResponse>`) — which one does the hub serve (depends on query presence)? Which should the app use?
24. **Triple token caches** (`ApiClient`, `TokenProvider`, CompanionService's private `hubToken()`) each with 2s cooldown — unify?
25. **`ControlCenterViewModel.startAutoRefresh()`** runs `while(true)` with no stored job; a second call would stack loops (currently only guarded by `LaunchedEffect(Unit)` at screen level).
26. **Hardcoded strings**: zero `stringResource` usages; Spanish majority vs English Control Center/Skill Manager/Workflow screens; hardcoded `http://127.0.0.1:8765`, `gemini-3.8-flash-high`, wake phrases, hex colors. Is i18n/string-resources ever planned?
27. **`createProject` default provider inconsistency**: `MainViewModel` defaults `"opencode"` while the create dialog and backend default `"antigravity"` — which wins?

### Process / documentation conflicts
28. **Canonical FRONTEND_CONTRACT**: backend **v1.3.0** (tested) vs app **v1.2.0** (stale, with known-wrong shapes)? Which does the app team follow?
29. **`QA_REPORT.md` (v1.0.1, "0 bugs, nothing pending")** vs audit H-08 ("irreproducible, contradicted by code") — needs re-run or retraction. Also its `date:` frontmatter is an unsubstituted shell literal `$(date -u …)`.
30. **`UI_AUDIT_CLAUDE_STYLE.md` "Aprobado al 100%"** vs audit UX-06/DOC-02 (three visual identities, hardcoded GitHub hexes). Which stands?
31. **Test-count drift**: 46 (CI mirror comment) / 47 (canonical workflow, docs, QA checklist) / 54 (actual). Also: mirror `Aegis/.github/workflows/build-apk.yml` vs canonical `/sdcard/projects/.github/workflows/build-apk.yml` differ (Node 20 vs 24, `--test-concurrency=1`, setup-gradle v3 vs v4, build-release conditional vs always) and are synced **manually** — should the mirror be deleted?
32. **Device OS version**: Android 15 (README/QUICKSTART) vs Android 16 (UI_AUDIT). Also `AEGIS_BUILD_AND_QA.md` targets ADB serial `emulator-5554` while claiming a physical POCO F3 — which device did QA actually run on?
33. **Keystore key size**: ADR-001 procedure says RSA 2048; I-3 says RSA 4096.
34. **keepalive pattern claim**: CHANGELOG says legacy `opencode-companion` patterns were "not touched" while OPS-01/quick-win #11 said they had to be fixed — resolution not recorded.
35. **Phase numbering**: `AEGIS_MASTER_PROMPT` 0–11 (with phase 7 reversed/deleted) vs audit F0–F7 / A-1…A-7 vs CHANGELOG F0–F5 — which registry is authoritative going forward? Note `AUDITORIA_INTEGRAL` still says "APROBADO AL 100%" for an app named "OpenCode Companion".
36. **`backend/NOTES.md` upstream opencode issue** and **`SESSION_HANDOFF.md` pending UI items** (black text in session rows, missing 3-dot menu, drawer scroll refresh, no auto-scroll, raw markdown, visible `memory_context` block): were these superseded by later fixes, or still open? The handoff also leaves 4 unresolved decisions (managed-project vs root sessions, keep/disable injection, orphaned session entry, versionCode 25+ parity).
37. **`agents/` directory** (vendored third-party agent repo, untracked): is it meant to be part of this project, a submodule, or a reference corpus?
38. **`AEGIS_QA.md` is a single 8KB line with no newlines** (and per audits "AEGIS_QA.md no es QA") — superseded by QA_CHECKLIST/QA_REPORT, or still the operational runbook?
39. **Smoke-test automation**: deliberately excluded from automated tests ("would make a real model call, up to 60s") — accepted risk, or should a recorded-fixture test exist?
40. **`projects.json` tracked as runtime data**: the worktree shows it permanently dirty. Track, gitignore, or move to a data dir?
