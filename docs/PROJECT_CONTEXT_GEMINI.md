# Aegis — Project Context

> Generated from an exhaustive analysis of the codebase, documentation, commit history, and test suites across the backend (`Aegis/backend`), Android app (`Aegis/app`), operational scripts, audits, and architectural decision records. Items that could not be verified directly from the code or configuration are explicitly marked **[UNCLEAR]**.

---

## 1. Project Overview

### Core Purpose
**Aegis** is an autonomous, self-contained AI engineering and orchestration platform running directly on Android mobile hardware. It transforms an Android smartphone into a private, on-device AI development hub capable of managing local coding engines, executing agent workflows, interacting with filesystem workspaces, and controlling the host Android OS via an accessibility and shell bridge.

The core promise of Aegis (v1.0.0, released September 2026):
1. Complete self-service provisioning via an in-app 6-step Setup Wizard (Ubuntu rootfs, Node.js, OpenCode serve, Antigravity CLI, curated skills) with SHA256 verification and atomic LIFO rollback.
2. Unified multiprovider AI routing (local OpenCode engine + cloud-connected Google Antigravity CLI) under a single normalized API.
3. Fully isolated, token-authenticated loopback bridge with zero external attack surface (resolving the legacy anonymous root RCE vulnerability).
4. Direct device interaction (screen reading, node clicking, shell execution, voice pipeline) through Android accessibility and foreground services.

### Target Platform & Hardware Environment
- **Device Hardware**: Xiaomi POCO F3 ("alioth"), Qualcomm Snapdragon 870 (SM8250-AC octa-core Kryo 585 up to 3.2 GHz, Adreno 650 GPU), 6 GB / 8 GB LPDDR5 RAM, UFS 3.1 storage.
- **Host Operating System**: Android 15 (crDroid 11.x / AOSP custom ROM; note: one audit document references Android 16, see §9).
- **Privilege & Root**: Magisk, KernelSU, or APatch (`su` binary available in `/system/bin/su` or `/data/adb/ksu/bin/su`).
- **Linux Subsystem**: Ubuntu 24.04.5 LTS aarch64 rootfs residing at `/data/local/ubuntu` (or `/data/ubuntu`), executed via Linux `chroot` with mount namespace isolation (`unshare -m` / `nsenter -t 1 -m`). PRoot detection exists in the backend as an unprivileged fallback, but physical deployment relies strictly on native root `chroot`.
- **Filesystem**: User projects and workspace repositories reside on `/sdcard/projects/` (emulated FUSE storage with specific I/O constraints; see §6).

### Architecture Summary
Aegis is composed of four cooperating runtime layers:

```
┌────────────────────────────────────────────────────────────────────────┐
│ Android Host Layer (com.aegis.hub)                                     │
│  - Jetpack Compose UI (Chat, Projects, Setup Wizard, Control Center,  │
│    Workspace, Workflows, Skill Manager, Voice Interaction)             │
│  - CompanionService (Loopback HTTP server on 127.0.0.1:8766)           │
│  - OpencodeAccessibilityService (UI tree inspection & node clicks)    │
│  - RootShell helper (su execution, chroot bootstrapping, input events) │
└───────────────────▲────────────────────────────────┬───────────────────┘
                    │ Retrofit / OkHttp (SSE)        │ Loopback Bridge
                    │ X-Aegis-Token                  │ X-Aegis-Token
┌───────────────────▼────────────────────────────────▼───────────────────┐
│ Hub Layer (Aegis Backend — Node.js 24 ESM, zero npm dependencies)      │
│  - Loopback HTTP server on 127.0.0.1:8765                              │
│  - Middleware: Timing-safe token auth, rate limiting (120 req/min/IP), │
│    CORS allowlist, JSON body parser (up to 55 MB for attachments)      │
│  - Core Services: ProjectManager, FileMutex, ProviderManager,          │
│    EventBus, AgentPool, JobScheduler, SkillManager                     │
│  - Bootstrap Engine: 6-step idempotent provisioning orchestrator       │
│  - Adapters: OpencodeAdapter (HTTP v2), AntigravityAdapter (CLI child) │
└───────────────────▲────────────────────────────────┬───────────────────┘
                    │ HTTP REST / Basic Auth         │ CLI spawn / stdio
┌───────────────────▼───────────────┐  ┌─────────────▼───────────────────┐
│ OpenCode Serve Engine             │  │ Google Antigravity CLI (agy)    │
│  - Local daemon on 127.0.0.1:4096 │  │  - Standalone binary v1.2.9     │
│  - Port 4096 REST API v2          │  │  - Stdio stream-json protocol   │
│  - Workspace indexing & edits     │  │  - OAuth session authentication │
└───────────────────────────────────┘  └─────────────────────────────────┘
                    ▲
                    │ Monitored & restarted (10-second loop)
┌───────────────────┴────────────────────────────────────────────────────┐
│ Supervisor Layer: keepalive.sh                                         │
│  - Magisk service.d-99-opencode-hub.sh / nohup daemon                  │
│  - Probes :8765 /api/health & :4096; manages backoff & process recovery│
└────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Current State

### Fully Working Right Now
1. **Core Hub HTTP Server (`backend/server.js`)**:
   - Loopback-only binding (`127.0.0.1:8765`), timing-safe token authentication on all `/api/*` and `/opencode/*` routes (with sole documented exemption for `GET /api/health`).
   - Atomic file persistence (`FileMutex` + temp file -> `fsync` -> `rename`) preventing data corruption on `projects.json` and session state.
   - 54 unit and integration tests passing (`node --test tests/*.test.js`) across 10 test suites covering security, bootstrap rollback, contract fidelity, rate limiting, and log rotation.
2. **Provider Orchestration (`backend/providers.js`)**:
   - **Google Antigravity CLI Integration**: Fully operational. Executes `/root/.local/bin/agy` child processes with `--output-format stream-json`, `--dangerously-skip-permissions`. In-flight attachments are materialized to `/root/.gemini/antigravity-cli/attachments/` with absolute paths so the model can invoke `view_file` (F7-2).
   - **OpenCode Adapter v2 Transition (F6)**: Fully transitioned to OpenCode v2 REST API (v2.0.14). Extracts ephemeral Basic auth password from `opencode.log`, queries health via `GET /api/info`, manages sessions, maps free-first models, paginates messages via cursor, and strips internal sub-agent sub-sessions from UI display.
   - **Session Provider Pinning**: Once created (`ses_...` for OpenCode, `agy_...` for Antigravity), provider affinity is immutable and cannot be cross-pollinated or corrupted by header overrides.
3. **Bootstrap Engine (`backend/src/bootstrap/`)**:
   - 6 provisioning steps: `preflight` (root/arch/storage), `ubuntu` (SHA256 verified rootfs download and tar unpack), `node` (Node.js v24.21.0 aarch64 binary extraction), `opencode` (serve binary staging), `antigravity` (CLI discovery and OAuth token validation), `skills` (catalog installation).
   - LIFO rollback on step failure, atomic `bootstrap-state.json` persistence, idempotent check-before-run semantics.
4. **Android Client Application (`Aegis/app`)**:
   - Jetpack Compose interface with 15 functional views (Chat, Projects, ProjectDetail, SetupWizard, ControlCenter, SkillManager, Workspace, Workflows, VoiceInteraction).
   - Server-Sent Events (SSE) streaming with live cursor blink, optimistic user message dispatch, and tool execution cards.
   - Robust cold-start recovery: F6 fixed session listing token races; F8 fixed empty project list on cold start via `LaunchedEffect` refresh on screen entry and bounded 1s/2s/4s backoff in `MainViewModel`.
   - Complete 45-endpoint Retrofit/OkHttp API client with automatic `X-Aegis-Token` injection via root `su -c cat`.
5. **Supervisor & Daemon (`keepalive.sh`)**:
   - 10-second watchdog loop with A-7 backoff (single failed probe discarded; restart only on two consecutive failures).
   - Mount namespace validation (`NODE_BIN` checks) and strict process command-line filters avoiding false kills.

### Partially Working (With Known Issues)
1. **OpenCode Smoke-Test (`POST /api/setup/smoke-test`)**:
   - While unit-tested with mock fixtures, live smoke execution against `opencode serve` requires the local daemon to be running and authenticated. If OpenCode is starting up or under heavy disk I/O, the 60-second budget can time out or return transient connection drops (ECONNRESET).
2. **Device UI Voice Pipeline (`CompanionVoiceInteractionService.kt`)**:
   - Voice trigger and wake phrases work via Android SpeechRecognizer and TextToSpeech; however, the UI screen has non-functional setting chips (model chip, "Nuevo", and gear icons are no-ops), and the TTS queue only speaks the first received chunk rather than continuous stream synthesis.
3. **Markdown Rendering in Chat (`MarkdownText.kt`)**:
   - Markdown formatting (bold, italic, code blocks, lists) renders properly, but `onLinkClick` is not connected to an Android Intent launcher; hyperlinks render underlined but are not clickable.
4. **Chat Pinned Sessions & Archiving**:
   - The UI includes "Fijar" (pin) actions and an "Archivados" dialog option; however, chat pinning operates as an in-memory no-op callback, and no archived projects/chats view exists in the app.
5. **Test Execution Concurrency**:
   - Backend tests run cleanly sequentially (`--test-concurrency=1`), but parallel execution flakes under Android FUSE storage due to file lock contention and log file descriptor contention (backlog T1).

### Not Implemented Yet
1. **Web UI Dashboard**:
   - `backend/public/` does not exist in the repository checkout. All non-API requests to port 8765 return 404 JSON. Web access from a browser is currently unsupported despite mentions in early documentation.
2. **Deep Linking (`aegis://`)**:
   - No `android.intent.action.VIEW` filter with an `aegis://` scheme or `assetlinks.json` verification exists in `AndroidManifest.xml`.
3. **Automated LLM Task Routing**:
   - Dynamic intent routing (`modelRouter`/`taskClassifier`) was originally designed in Phase 7, found to have zero consumers, and was deliberately deleted in commit `d730e6e`. Provider selection is explicit or defaults statically to Antigravity (`gemini-3.8-flash-high`).
4. **Workflow DAG Engine**:
   - `src/core/workflowEngine.js` executes steps sequentially. It lacks true DAG dependency resolution and enforces a hard limit of 2 concurrent agents per project.

---

## 3. Tech Stack

### Android Application (`Aegis/app`)
- **Language**: Kotlin 2.0.21.
- **UI Toolkit**: Jetpack Compose (Compose BOM `2024.10.00`, Material 3 `1.3.1`, Material Icons Extended `1.7.3`).
- **Core Architecture**: MVVM (Model-View-ViewModel) with Kotlin Coroutines (`1.8.1`) and `StateFlow`. No DI framework (no Hilt/Koin; instances are instantiated directly or managed as singletons).
- **Navigation**: `androidx.navigation:navigation-compose:2.8.4` (Single `NavHost` with string routes).
- **Networking**:
  - `com.squareup.retrofit2:retrofit:2.11.0` + `converter-gson:2.10.1` for standard REST endpoints.
  - `com.squareup.okhttp3:okhttp:4.12.0` (with `logging-interceptor:4.12.0`) for Server-Sent Events (SSE) streaming, raw JSON uploads, and dynamic token interceptors.
- **Android Target**: `compileSdk = 35`, `targetSdk = 35`, `minSdk = 26` (Android 8.0 Oreo), Java 17 toolchain.
- **System Integration**: Android Accessibility API (`AccessibilityService`), Voice Interaction API (`VoiceInteractionService`), Android Doze whitelist APIs, Root Shell bridge (`su`).

### Backend Hub (`Aegis/backend`)
- **Runtime**: Node.js v18+ ESM (`"type": "module"`). Production target on device is Node.js v24.21.0 aarch64.
- **Dependencies**: **Zero third-party npm dependencies**. Strictly native Node.js built-in modules:
  - `node:http`: HTTP server and client dispatch.
  - `node:crypto`: `randomBytes`, `timingSafeEqual`, SHA256 hashing.
  - `node:fs` & `node:fs/promises`: Atomic file I/O, file locks, stream piping.
  - `node:child_process`: Process spawning (`agy` CLI, shell probes).
  - `node:test` & `node:assert/strict`: Test suite runner and assertions.
- **Data Persistence**: Flat JSON files (`projects.json`, `ui-state.json`, `bootstrap-state.json`, `.companion-session.json`) managed via in-memory locks and atomic replace patterns (`tmp` -> `fsync` -> `rename`).

### AI Engine Integration
| Engine | Execution Method | Protocol | Models Supported |
|---|---|---|---|
| **Google Antigravity** | Spawn standalone CLI ELF (`/root/.local/bin/agy`) | Stdio streaming JSON (`--output-format stream-json`) | `gemini-3.8-flash-high`, `gemini-3.8-pro`, `claude-3-7-sonnet` |
| **OpenCode Engine** | Local HTTP daemon (`127.0.0.1:4096`) | REST API v2 (Basic auth with scraped password, cursor pagination, SSE/polling) | Free models (`zen-free`, `free`), paid models, custom API keys |
| **Claude Code** | Inactive adapter (`ClaudeCodeAdapter.js`) | Stub registered in provider catalogue; inert | N/A |

### Build & CI/CD Pipeline
- **Build System**: Android Gradle Plugin (AGP) 8.7.3, Gradle 8.9 (pinned in CI).
- **CI Workflows (`.github/workflows/build-apk.yml`)**:
  1. `backend-checks`: Executes syntax checks (`node --check server.js`) and runs the full test suite (`npm test`).
  2. `lint`: Enforces zero unmanaged `console.*` logging in `server.js`.
  3. `build-debug`: Builds debug APK (`app-debug.apk`), uploaded as a GitHub Actions artifact (retained for 14 days).
  4. `build-release`: Builds signed release APK if `KEYSTORE_BASE64` secret is present (retained for 30 days).
  5. `security`: Semgrep SAST scanning and Gitleaks secret detection (configured with `continue-on-error: true`).
  6. `instrumented`: Manual workflow dispatch for Android emulator UI tests.

---

## 4. Project Structure

```
/sdcard/projects/                                  # Git repository root (branch: main)
├── .github/
│   └── workflows/
│       └── build-apk.yml                          # Canonical CI/CD workflow for backend & Android build
├── docs/                                          # Top-level legacy documentation
│   ├── AUDITORIA_INTEGRAL_QA.md                   # Legacy audit chronicle
│   ├── FRONTEND_CONTRACT.md                       # Historical v1.2.0 API contract
│   ├── PROJECT_CONTEXT.md                         # This comprehensive project context documentation
│   └── UI_AUDIT_CLAUDE_STYLE.md                   # UI styling review and palette definition
├── Aegis/                                         # Main application root
│   ├── .github/workflows/build-apk.yml            # Local mirror of CI workflow
│   ├── CHANGELOG.md                               # Version history and release notes
│   ├── README.md                                  # Aegis project overview and installation guide
│   ├── .hub/                                      # Hub metadata and local project configurations
│   ├── agents/                                    # Vendored specialized agents catalogue
│   │   ├── divisions.json                         # Agent division metadata (engineering, security, etc.)
│   │   ├── tools.json                             # Agent tool definitions
│   │   └── [domain]/                              # Markdown prompts for specialized domain agents
│   ├── backend/                                   # Node.js Hub service
│   │   ├── server.js                              # Main HTTP server monolith, router, middleware (3,156 lines)
│   │   ├── providers.js                           # Active AI provider implementations (OpenCode v2, Antigravity)
│   │   ├── package.json                           # Node.js package manifest (zero dependencies)
│   │   ├── projects.json                          # Master runtime database for projects and sessions
│   │   ├── keepalive.sh                           # 10s watchdog script supervising hub and opencode
│   │   ├── start-hub.sh                           # Manual launcher script for local development
│   │   ├── service.d-99-opencode-hub.sh           # Magisk service.d auto-start boot script
│   │   ├── find-ubuntu.sh                         # Helper locating Ubuntu chroot mountpoints
│   │   ├── stage-node.sh                          # Script staging Node.js binaries into target directories
│   │   ├── opencode.sh                            # OpenCode serve process launcher
│   │   ├── patch_main.py / patch_main_2.py        # Utility patching scripts for runtime fixes
│   │   ├── .ponytail.md                           # Mutable backend work log and developer notes
│   │   ├── context/
│   │   │   └── pony-tail-global.md                # Immutable system instruction rules for all agent runs
│   │   ├── docs/
│   │   │   ├── AEGIS_MASTER_PROMPT.md             # Master specification and architectural requirements
│   │   │   ├── AEGIS_BUILD_AND_QA.md              # Build instructions and QA validation procedures
│   │   │   ├── AEGIS_PHASE11_ANDROID.md           # Android phase execution instructions
│   │   │   ├── AEGIS_QA.md                        # QA runbook notes
│   │   │   ├── AUDITORIA_INTEGRAL_QA.md           # Backend audit findings and resolution log
│   │   │   ├── BACKEND_ARCHITECTURE.md            # Detailed backend architecture documentation
│   │   │   └── FRONTEND_CONTRACT.md               # Canonical v1.3.0 REST/SSE contract enforced by tests
│   │   ├── src/
│   │   │   ├── adapters/                          # Inactive/mirror adapters (OpenCode v1, ClaudeCode, Git)
│   │   │   ├── agents/                            # BaseAgent abstraction and template agents (Research, Auditor)
│   │   │   ├── api/                               # Modular sub-routers (bootstrap, setup, skills, workspace)
│   │   │   │   ├── bootstrapRoutes.js             # Routes for 6-step setup wizard execution
│   │   │   │   ├── setupRoutes.js                 # Final verification, smoke test, and auth check routes
│   │   │   │   ├── skillsRoutes.js                # Skill listing, installation, and invocation routes
│   │   │   │   └── workspaceRoutes.js             # Filesystem workspace scanning routes
│   │   │   ├── bootstrap/                         # Bootstrap engine orchestrator, state machine, manifests
│   │   │   │   ├── steps.js                       # 6 idempotent provisioning step implementations
│   │   │   │   ├── orchestrator.js                # Step execution engine with LIFO rollback
│   │   │   │   └── state.js                       # Atomic state file manager
│   │   │   ├── core/                              # Hub core primitives (Mutex, EventBus, Logger, PathResolver)
│   │   │   │   ├── fileMutex.js                   # Lock-file implementation for atomic JSON storage
│   │   │   │   ├── logger.js                      # Multi-level structured file and console logger
│   │   │   │   ├── pathResolver.js                # Path traversal sanitization and validation
│   │   │   │   └── projectManager.js              # Projects and session store operations
│   │   │   └── skills/                            # Skill catalogue allowlist, SkillManager, SkillInvoker
│   │   └── tests/                                 # 10 test suites (54 tests) executed by node:test
│   ├── app/                                       # Android Jetpack Compose client application
│   │   ├── build.gradle.kts                       # Root Gradle build script for Android project
│   │   ├── settings.gradle.kts                    # Gradle settings declaring :app module
│   │   ├── gradle.properties                      # JVM arguments and AndroidX configuration
│   │   ├── install-su.sh                          # Rooted installation script for POCO F3 via ADB/Terminal
│   │   ├── .ponytail.md                           # Mutable frontend work log and state
│   │   ├── docs/
│   │   │   ├── FRONTEND_CONTRACT.md               # App-level copy of contract (v1.2.0)
│   │   │   └── UI_AUDIT_CLAUDE_STYLE.md           # Visual design audit
│   │   └── app/
│   │       ├── build.gradle.kts                   # Application module dependencies, SDK versions, signing
│   │       └── src/
│   │           ├── main/
│   │           │   ├── AndroidManifest.xml        # Permissions, Services, Activities, Doze declaration
│   │           │   ├── assets/stage-node.sh       # Packaged copy of Node staging script
│   │           │   ├── kotlin/com/aegis/hub/
│   │           │   │   ├── MainActivity.kt        # Application entry point, hub health check, root launcher
│   │           │   │   ├── CompanionService.kt    # Foreground service exposing loopback HTTP on 127.0.0.1:8766
│   │           │   │   ├── CompanionVoiceInteraction*.kt # Voice trigger and interaction service
│   │           │   │   ├── OpencodeAccessibilityService.kt # Accessibility node inspector and input injector
│   │           │   │   ├── RootShell.kt           # su command execution wrapper with argument escaping
│   │           │   │   ├── data/                  # Network client, models, and repositories
│   │           │   │   │   ├── ApiClient.kt       # Retrofit singleton with dynamic token and logging
│   │           │   │   │   ├── ApiService.kt      # Retrofit interface defining 45 HTTP endpoints
│   │           │   │   │   ├── Models.kt          # Data classes (Envelope, Message, Project, BootstrapState)
│   │           │   │   │   └── TokenProvider.kt   # Reads and caches .aegis_token via RootShell
│   │           │   │   └── ui/                    # Compose UI layer
│   │           │   │       ├── AppNavHost.kt      # Main navigation graph and screen transitions
│   │           │   │       ├── NavRoutes.kt       # Navigation destination route constants
│   │           │   │       ├── ChatScreen.kt      # Active AI chat conversation view with SSE streaming
│   │           │   │       ├── ChatsScreen.kt     # Session drawer and session history list
│   │           │   │       ├── ProjectsScreen.kt  # Project management grid/list view
│   │           │   │       ├── SetupWizardScreen.kt# 6-step bootstrap installation wizard
│   │           │   │       ├── ControlCenterScreen.kt # Daemon and system health management dashboard
│   │           │   │       ├── WorkspaceScreen.kt # Physical filesystem directory browser
│   │           │   │       ├── theme/             # Color palettes, typography, theme composables
│   │           │   │       └── viewmodel/         # ViewModels (Main, Chat, Bootstrap, ControlCenter, etc.)
│   │           │   └── res/                       # Drawables, layouts, strings, and accessibility configs
│   │           └── test/                          # 25 JVM unit tests (ModelsEnvelopeTest, BootstrapViewModelTest)
│   └── docs/                                      # Aegis documentation directory
│       ├── ARCHITECTURE.md                        # High-level architecture summary
│       ├── QUICKSTART.md                          # Comprehensive setup and user guide
│       ├── QA_REPORT.md                           # Verification report
│       ├── adr/                                   # Architectural Decision Records
│       │   ├── ADR-001-security-model.md          # Security architecture: token auth & loopback isolation
│       │   └── ADR-002-bootstrap-design.md        # Bootstrap engine design, idempotency & rollback
│       ├── audits/                                # Security and technical audit chronicles
│       │   ├── AUDITORIA_BACKEND.md               # 55 backend audit findings
│       │   ├── AUDITORIA_FRONTEND.md              # 62 frontend audit findings
│       │   ├── AUDITORIA_PROYECTO_Y_ROADMAP.md    # Product and roadmap evaluation
│       │   ├── PLAN_MEJORA_FASE0.md               # Phase 0 remediation plan
│       │   └── PLAN_SIGUIENTES_ACCIONES.md        # Immediate and short-term remediation roadmap
│       ├── qa/
│       │   └── QA_CHECKLIST.md                    # 8-step manual acceptance testing checklist
│       └── screenshots/                           # QA screenshots and UI bug verification evidence
```

### Entry Points
- **Android UI Entry**: [`MainActivity.kt`](file:///sdcard/projects/Aegis/app/app/src/main/kotlin/com/aegis/hub/MainActivity.kt) in `com.aegis.hub`. Initializes RootShell, verifies hub status on `127.0.0.1:8765`, starts the foreground `CompanionService`, registers voice shortcuts, and renders `AppNavHost`.
- **Backend Hub Entry**: [`server.js`](file:///sdcard/projects/Aegis/backend/server.js). Bootstraps environment variables, loads or creates `.aegis_token`, initializes `ProviderManager`, registers HTTP routes, and binds `http.createServer` to `127.0.0.1:8765`.
- **Supervisor Entry**: [`keepalive.sh`](file:///sdcard/projects/Aegis/backend/keepalive.sh) via `/data/adb/service.d/service.d-99-opencode-hub.sh` or through the Android app's "Iniciar Sistema" root command.

---

## 5. Data Flow

### 5.1 End-to-End Chat Message Lifecycle

```
[User taps Send in ChatScreen]
               │
               ▼
[ChatViewModel.sendWithFiles]
  ├─ 1. Anti-Double-Send Guard: Checks inFlightSendKey.
  ├─ 2. Optimistic UI Update: Injects local pending message (id: "local_<timestamp>").
  ├─ 3. Attachment Preprocessing: Text files (<30k chars) inlined into markdown.
  │     Binary files (<5MB) encoded to base64 data URIs.
  ├─ 4. Session Resolution: If no active session, calls POST /opencode/session.
  ├─ 5. Primary Transmission: Opens SSE connection via OkHttp:
  │     POST /api/opencode/sessions/{id}/message?stream=true
  │     Headers: Accept: text/event-stream, X-Aegis-Token, X-Provider, X-Model
               │
               ▼ HTTP SSE Request (127.0.0.1:8765)
[Hub server.js / handleOpencodeSessionMessage]
  ├─ 1. Auth & Rate Limit: Timing-safe token comparison, rate counter increment.
  ├─ 2. Body Parser: Ingests JSON body up to 55 MB cap (F7 attachment support).
  ├─ 3. Provider Resolution: Evaluates session ID prefix (`ses_` vs `agy_`), stored provider,
  │     or explicit X-Provider header.
  ├─ 4. Project Linkage: Associates session with project in projects.json (FileMutex).
  ├─ 5. Auto-Titling: Updates placeholder titles from first user prompt (30 chars + "…").
               │
               ▼
[Provider Execution in providers.js]
  ├─► If Provider == "antigravity":
  │     - Writes binary attachments to /root/.gemini/antigravity-cli/attachments/<sid>/
  │     - Injects absolute file paths and `view_file` instructions into prompt.
  │     - Spawns `/root/.local/bin/agy` with args:
  │       [--output-format, stream-json, --dangerously-skip-permissions, --mode, accept-edits]
  │     - Parses stdout NDJSON lines: `step_update`, `tool_call`, `text_delta`, `result`.
  │     - Pipes events into SSE response: `chunk`, `tool_start`, `tool_done`, `done`.
  │
  └─► If Provider == "opencode":
        - Scrapes dynamic Basic auth password from opencode.log tail.
        - Sends prompt to OpenCode serve v2 API: POST /api/prompt.
        - Polls GET /api/message with cursor pagination until completion.
        - Emits full text response and final `done` event.
               │
               ▼ SSE Stream Events
[ChatViewModel / ChatScreen UI]
  ├─ Receives `chunk` -> Appends to live streaming text buffer (rendered with blinking ▋).
  ├─ Receives `tool_start` / `tool_done` -> Renders expandable ToolExecutionCard.
  ├─ Receives `done` -> Replaces optimistic message with final message, status = SENT.
  └─ Error / Disconnect -> Falls back to GET /api/opencode/sessions/{id}/messages sync.
```

### 5.2 Context Injection Pipeline
When enabled via legacy proxy paths or project configuration:
1. Hub resolves active `projectId` from request header or `projects.json` session linkage.
2. System context block (capped at 24,000 characters) is constructed from:
   - `Aegis/backend/context/pony-tail-global.md` (mandatory safety rules).
   - Target project's `.ponytail.md` (project-specific instructions).
   - Installed skill summaries and up to 8 linked project summary cards.
3. Injected into the model's system prompt using the `[SYSTEM CONTEXT]` boundary marker.
4. The Android app's `MarkdownText` automatically strips internal context markers on display via `strippedText()`.

### 5.3 Session & Project Linkage
- **Creation**: `POST /opencode/session` creates an ID with a provider prefix (`ses_` for OpenCode, `agy_` for Antigravity).
- **Storage**: `projects.json` maintains the list of projects and their associated session ID arrays under `projects[].sessions[]`. A top-level `sessionTitles` map stores friendly names.
- **Provider Affinity**: The provider assigned at session creation is permanent. The Android app binds the model selector to the session's active provider, preventing cross-provider model pollution (F6).
- **Deletion**: Calling `DELETE /opencode/session/:id` purges the session from `projects.json`, clears stored titles, deletes disk transcripts in `brain/<id>`, and adds the ID to the client tombstone set to prevent UI resurrection.

---

## 6. Key Decisions & Constraints

### Architectural Decision Records (ADRs)
- **ADR-001: Security Architecture ("Total root with token, zero access without it")**:
  - *Context*: Audits revealed the legacy daemon bound to `0.0.0.0` with no authentication, exposing root shell execution to any device on the local Wi-Fi.
  - *Decision*: Restrict all bindings to `127.0.0.1`. Generate a cryptographically secure 32-byte hex token (`.aegis_token`) with 0600 permissions. Require `X-Aegis-Token` on all endpoints except `GET /api/health` (required for keepalive monitoring). Enforce IP rate limiting (120 req/min). Sanitize IDs against path traversal (`..`, `/`, `\`). Disallow shell meta-characters.
- **ADR-002: Bootstrap Engine Design**:
  - *Context*: Provisioning an Ubuntu rootfs, Node.js, and AI CLIs manually was error-prone and caused user drop-off.
  - *Decision*: Build a 6-step idempotent wizard with atomic state tracking (`bootstrap-state.json`). Require SHA256 checksums for all remote downloads. Implement LIFO rollback on failure so aborted steps leave no corrupted disk state.

### Hard Constraints & Mobile Hardware Limits
1. **FUSE Filesystem Limitations (`/sdcard/projects`)**:
   - `/sdcard` is backed by Android's `sdcardfs` / FUSE emulation layer. POSIX atomic file rename and `fsync` guarantees are relaxed. Heavy concurrent write operations can cause filesystem stalls (D-state sleep). To prevent data loss, the hub implements an explicit `FileMutex` and serialized file operations.
2. **Low Memory Killer (LMK) & Battery Management**:
   - Android aggressive process management can kill background daemons without warning. `CompanionService` runs as a foreground service with a persistent notification. `keepalive.sh` runs under a Magisk root mount namespace to survive app lifecycle events. Battery optimization is explicitly disabled during setup (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).
3. **Mount Namespace Isolation (`nsenter`)**:
   - Android apps and system daemons operate in separate mount namespaces. To inspect or execute commands inside the Ubuntu chroot from Android root, scripts must switch namespaces using `nsenter -t 1 -m` or explicitly pass `NODE_BIN` paths.

### Technical Debt & Codebase Oddities
- **Duplicate Adapter Trees**: Two adapter trees exist. `backend/providers.js` is the live, active implementation used by `server.js`. `backend/src/adapters/` contains legacy mirrors (`OpenCodeAdapter.js`, `GitAdapter.js`) that are dead or retained only for reference (backlog B3).
- **Format Divergence in Error Envelopes**: The backend returns error envelopes shaped as `{ ok: false, error: { code: string, message: string } }`, whereas some legacy Kotlin models in `Models.kt` expect `error: String?`. Handled via lenient parsing, but requires unification (backlog B2).
- **Missing Web UI Bundle**: `backend/public/` is absent from version control. All non-API routes return 404 JSON. Mentions of browser dashboards in documentation are aspirational.

---

## 7. Pending Features & Roadmap

The Aegis roadmap is prioritized according to `PLAN_SIGUIENTES_ACCIONES.md` and audit chronicles:

### Immediate Next Actions (Phase I)
1. **I-1: Release APK Distribution**: Download and verify `aegis-release` build artifact from GitHub Actions; ensure debug key fallback is documented.
2. **I-2: Device QA Acceptance Execution**: Complete the 8 verification steps in `docs/qa/QA_CHECKLIST.md` on a physical POCO F3 device with timestamped screenshot evidence.
3. **I-3: Release Keystore Creation**: Generate a production 4096-bit RSA keystore, base64-encode, and configure `KEYSTORE_BASE64` in GitHub repository secrets.
4. **I-4: Security Tooling Promotion**: Triage Semgrep and Gitleaks findings in CI; promote scans from `continue-on-error: true` to blocking checks.
5. **I-5: Branch Protection Rules**: Update repository branch protection rules to match renamed CI job `build-debug`.

### Short-Term Engineering Tasks (Phase T)
1. **T1: Test Log Isolation**: Inject unique `AEGIS_LOG_DIR` per test runner spawn to eliminate I/O lock contention and enable parallel test execution.
2. **T2: OpenCode v2 Live Smoke Verification**: Complete end-to-end smoke verification against live `opencode serve` daemon to ensure `setupRoutes.js` reliably returns `PONG`.
3. **T3: Model List Discovery for v2**: Refine OpenCode v2 model scraper to eliminate HTML fallback warnings when endpoints return SPA markup.
4. **T4: Token Protection for SSE Event Endpoints**: Ensure `/event` and `/global/event` endpoints are gated behind the timing-safe token middleware.
5. **T5: Code Graph Semantic Labelling**: Run `graphify` with `--missing-only` using a Gemini API key to update semantic architectural maps.

### Milestone v1.0.1 Backlog (Phase B)
- **B1**: Harmonize `OpencodeAdapter` across active and mirror files.
- **B2**: Align Kotlin `Envelope.error` model with backend `{ code, message }` object structure.
- **B3**: Deprecate and remove dead adapters in `backend/src/adapters/`.
- **B4**: Migrate remaining ~7 instances of raw `console.*` logging to `core/logger.js`.
- **B5**: Formally validate the script-equivalent KPI for the JS bootstrap engine.
- **B6**: Generate and commit the official Gradle wrapper (`gradle wrapper --gradle-version 8.9`).
- **B7**: Clean up legacy file path references in `pony-tail-global.md` (pending user approval for immutable file).
- **B8**: Execute instrumented test suite in CI.
- **B9**: Update markdown link handling in `MarkdownText.kt` to launch external browser intents.

---

## 8. Environment & Setup

### Device Prerequisites
- Xiaomi POCO F3 ("alioth") running Android 15 (crDroid custom ROM).
- Root access configured via Magisk, KernelSU, or APatch.
- Working internet connectivity reaching Ubuntu mirrors and NPM registry.
- At least 2.5 GB free storage on `/data` for the Ubuntu chroot rootfs.

### Installation Procedure
1. **Via Root Shell on Device**:
   ```bash
   su -c "sh /sdcard/projects/Aegis/app/install-su.sh"
   ```
   *Grants necessary permissions (`POST_NOTIFICATIONS`, `RECORD_AUDIO`), enables accessibility services, excludes app from battery optimization, and launches the application.*
2. **First Run & Bootstrap**:
   - Open the **Aegis** app.
   - If the hub is offline, tap **Iniciar Sistema** on the disconnect overlay to spawn `keepalive.sh`.
   - The app navigates to the 6-step **Setup Wizard**.
   - Tap **Iniciar Instalación** to sequentially download Ubuntu, stage Node.js, install OpenCode, verify Antigravity, and configure default skills.

### Local Development & Testing
```bash
# Navigate to backend directory
cd /sdcard/projects/Aegis/backend

# Run complete test suite (54 tests)
npm test

# Verify syntax integrity of server monolith
node --check server.js

# Launch backend manually on port 8765
sh start-hub.sh

# Query health probe (only route accessible without token)
curl http://127.0.0.1:8765/api/health

# Query authenticated endpoint using local token
TOKEN=$(cat .aegis_token)
curl -H "X-Aegis-Token: $TOKEN" http://127.0.0.1:8765/api/setup/final-check
```

### Environment Variables
| Variable | Default Value | Description |
|---|---|---|
| `HUB_PORT` | `8765` | Port for the Aegis Node.js hub HTTP service |
| `OPENCODE_PORT` | `4096` | Port where the local OpenCode serve daemon listens |
| `OPENCODE_HOST` | `127.0.0.1` | Host address for OpenCode serve daemon |
| `AEGIS_RATE_LIMIT` | `120` | Max requests per minute per IP (`0` disables rate limiter) |
| `AEGIS_LOG_DIR` | `backend/logs` | Destination directory for rotated system logs |
| `BOOTSTRAP_STATE_FILE` | `bootstrap-state.json` | Path to persistent bootstrap state store |
| `AEGIS_UBUNTU_DIR` | `/data/local/ubuntu` | Target directory for Ubuntu rootfs installation |
| `AEGIS_NODE_DIR` | `/data/local/node` | Target directory for Node.js binary staging |
| `NODE_BIN` | Evaluated dynamically | Absolute path to Node.js executable inside chroot |

---

## 9. Open Questions & Ambiguities

### Codebase & Architectural Queries
1. **Dead Provider Branch in `server.js`**:
   - In `server.js` (lines ~1296–1302), both branches of the unknown provider ternary resolve to `"antigravity"`. Should the fallback branch default to `"opencode"` when Antigravity is unconfigured?
2. **Absence of Web Dashboard**:
   - Documentation and README frequently reference a local browser interface ("Open en Chrome del POCO F3"), but `backend/public/` does not exist. Is a web dashboard planned, or should documentation be updated to state that Aegis is strictly an Android-native UI?
3. **Dual Project Listing Screens**:
   - The app features both `ProjectsScreen` (backed by `projects.json`) and `WorkspaceScreen` (backed by direct filesystem scanning of `/sdcard/projects/`). What is the intended distinction in user workflow between these two views?
4. **Skills Location Discrepancy**:
   - `SkillManager` scans `~/.config/opencode/skills/`, but early documentation references `backend/skills/`. Which directory should serve as the canonical storage for custom user skills?
5. **Project ID Regex Inconsistency**:
   - `server.js` uses an ID regex allowing dots, whereas `isValidProjectId` in `pathResolver.js` rejects dots (`^[a-zA-Z0-9\-_]+$`). Should dots and periods be permitted in project identifiers?
6. **Voice Synthesis Queue Streaming**:
   - In `CompanionVoiceInteractionService.kt`, text-to-speech synthesis plays only the initial response chunk. Is streaming continuous sentence synthesis planned for conversational mode?

### Documentation & Operational Conflicts
7. **Operating System Version Ambiguity**:
   - The majority of documents specify Android 15 (crDroid 11.x), but `UI_AUDIT_CLAUDE_STYLE.md` claims Android 16 (crDroid 12.x). Which version is the official test target?
8. **Disputed QA Report (`QA_REPORT.md`)**:
   - Audit finding H-08 disputed `QA_REPORT.md` (v1.0.1) for claiming 100% passed tests with zero bugs without attached evidence. A formal clean-slate run of `QA_CHECKLIST.md` with physical screenshots remains open.
9. **Keystore RSA Key Size**:
   - ADR-001 specifies an RSA 2048-bit key for release APK signing, while roadmap item I-3 specifies RSA 4096-bit. Which key size is required for production builds?
10. **Tracking of `projects.json`**:
    - `Aegis/backend/projects.json` is tracked by git but frequently modified at runtime as user projects and sessions change. Should this file be moved to `.gitignore` and initialized from a template (`projects.json.example`)?
