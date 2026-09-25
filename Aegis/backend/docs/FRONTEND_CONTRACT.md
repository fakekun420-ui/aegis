# FRONTEND CONTRACT: Aegis (OpenCode & Antigravity)

**Version:** 1.3.0  
**Target Clients:** Android App (`com.aegis.hub`, Jetpack Compose Material 3), Web Clients  
**Target Backends:** Local Hub (`server.js`, port 8765), OpenCode Daemon (port 4096), Antigravity CLI (`agy`)  
**Status:** Canonical & Strictly Typed  
**Changelog 1.3.0 (A-3):** standard envelope + health/skills contracts + 404 JSON — see §7.  

---

## 1. Overview & Communication Architecture

The Aegis app interfaces with the backend hub at `http://127.0.0.1:8765` (or configured host), which routes requests dynamically to either **OpenCode** (native daemon on port 4096) or **Antigravity CLI** (`/root/.local/bin/agy`).

```
┌────────────────────────────┐
│      Android Frontend      │
│  (ChatViewModel + Compose) │
└─────────────┬──────────────┘
              │ HTTP / JSON (OkHttp Timeout: 90s)
              ▼
┌────────────────────────────┐
│         Hub API            │ (Port 8765)
│       (server.js)          │
└──────┬──────────────┬──────┘
       │              │
       ▼              ▼
┌──────────────┐ ┌───────────────────┐
│   OpenCode   │ │    Antigravity    │
│ (Port 4096)  │ │ (/root/.local/bin/│
│              │ │  agy -p ... )     │
└──────────────┘ └───────────────────┘
```

---

## 2. Global Headers Contract

Every request originating from the frontend MUST support or include the following headers when context is available:

| Header | Type | Values / Example | Description |
|---|---|---|---|
| `Content-Type` | String | `application/json` | Required for all POST/PUT/PATCH bodies |
| `Accept` | String | `application/json` | Expected response encoding |
| `X-Provider` | String | `"opencode"` \| `"antigravity"` | Specifies whether OpenCode or Antigravity executes the query |
| `X-Project-Id` | String | `"prj_12345"` \| UUID | Specifies working directory / project boundary |

---

## 3. Core Endpoints Specification

### 3.1. Send Message
- **Endpoint:** `POST /opencode/session/{sessionId}/message`
- **Headers:** `X-Provider: {provider}`, `X-Project-Id: {projectId}`
- **Timeout Requirement:** Backend MUST respond within **90 seconds**. If long-running LLM generation takes longer, backend should return 202 Accepted or stream chunks.
- **Request Body (`SendMessageRequest`):**
```json
{
  "text": "Escribe un componente Compose para mostrar gráficos",
  "model": "gemini-2.5-flash",
  "provider": "antigravity",
  "files": [
    {
      "name": "diagram.png",
      "mime": "image/png",
      "size": 1048576,
      "base64": "iVBORw0KGgoAAAANSUhEUgAA..."
    }
  ]
}
```
- **Response (200 OK):**
```json
{
  "role": "assistant",
  "text": "Aquí tienes el código del componente...",
  "info": {
    "id": "msg_98765",
    "timestamp": 1726856000000,
    "deliveryStatus": "SENT"
  },
  "parts": [
    {
      "type": "text",
      "text": "Aquí tienes el código del componente..."
    }
  ]
}
```
- **Response (202 Accepted):**
```json
{
  "status": "queued",
  "sessionId": "ses_123"
}
```
- **Response (4xx / 5xx Error):**
```json
{
  "error": "Timeout comunicando con el proveedor Antigravity CLI tras 90s"
}
```

---

### 3.2. Get Messages (Polling & Initial Load)
- **Endpoint:** `GET /api/opencode/sessions/{sessionId}/messages`
- **Response (200 OK):** Array of `Message` objects in chronological order.
```json
[
  {
    "role": "user",
    "text": "Hola, ¿cómo estás?",
    "info": {
      "id": "msg_001",
      "timestamp": 1726855900000,
      "deliveryStatus": "SENT"
    }
  },
  {
    "role": "assistant",
    "text": "¡Hola! Estoy listo para ayudarte a programar.",
    "info": {
      "id": "msg_002",
      "timestamp": 1726855905000,
      "deliveryStatus": "SENT"
    }
  }
]
```

---

### 3.3. Sessions Management
- **List Sessions:** `GET /api/opencode/sessions`
  - Response: `List<OpencodeSession>`
- **Create Session:** `POST /api/opencode/sessions`
  - Request: `{"title": "Nueva sesión", "project_id": "p1", "provider": "antigravity"}`
  - Response: `OpencodeSession`
- **Get Models:** `GET /api/opencode/models`
  - Response: `List<{"id": "...", "name": "...", "description": "..."}>`

---

## 4. Strict Type Definitions (Kotlin & TypeScript)

### 4.1. Kotlin Models (`com.aegis.hub.data.Models.kt`)

```kotlin
enum class MessageDeliveryStatus {
    PENDING,
    SENT,
    ERROR
}

data class MessageInfo(
    val id: String? = null,
    val timestamp: Long? = null,
    val status: MessageDeliveryStatus? = null,
    val delivery_status: String? = null
) {
    val deliveryStatus: MessageDeliveryStatus
        get() = status ?: when (delivery_status?.uppercase()) {
            "PENDING" -> MessageDeliveryStatus.PENDING
            "SENT" -> MessageDeliveryStatus.SENT
            "ERROR" -> MessageDeliveryStatus.ERROR
            else -> MessageDeliveryStatus.SENT
        }
}

data class Message(
    val role: String? = null,
    val text: String = "",
    val info: MessageInfo? = null,
    val parts: List<Part>? = null
) {
    val isPending: Boolean
        get() = info?.deliveryStatus == MessageDeliveryStatus.PENDING

    val isError: Boolean
        get() = info?.deliveryStatus == MessageDeliveryStatus.ERROR

    fun withStatus(status: MessageDeliveryStatus): Message {
        val newInfo = (info ?: MessageInfo()).copy(status = status)
        return copy(info = newInfo)
    }
}

data class AttachedFile(
    val name: String,
    val mime: String,
    val size: Long = 0,
    val base64: String? = null,
    val text: String? = null
)

data class SendMessageRequest(
    val text: String,
    val files: List<AttachedFile> = emptyList(),
    val model: String? = null,
    val provider: String? = null
)
```

---

## 5. UI Lifecycle, Polling & State Machine

```
User Hits Send
      │
      ▼
┌────────────────────────────────────────┐
│ Optimistic Message Created             │
│ - role: "user"                         │
│ - text: input                          │
│ - deliveryStatus: PENDING              │
│ - Loading indicator: TRUE              │
└─────┬──────────────────────────────────┘
      │
      ├─── Concurrent 1: POST /opencode/session/{id}/message
      │     (Timeout: 90 seconds)
      │
      └─── Concurrent 2: Background Poller
            - Polls GET /api/opencode/sessions/{id}/messages every 1.5s
            - Max 50 iterations (~75s)
            - Terminates when new assistant message appears
      │
      ▼
┌────────────────────────────────────────┐
│ Response / Poll Succeeded              │
│ - Optimistic message -> SENT           │
│ - Assistant message rendered           │
│ - Loading indicator: FALSE             │
│ - Auto-scroll triggered                │
└────────────────────────────────────────┘
      │
      └─── (On Timeout or Network Failure)
            │
            ▼
┌────────────────────────────────────────┐
│ Error Handled                          │
│ - Optimistic message -> ERROR          │
│ - Loading indicator: FALSE             │
│ - ErrorBanner visible to user          │
│ - Inline "• Reintentar" button active  │
└────────────────────────────────────────┘
```

---

## 6. Frontend Visual & Design Specifications (Material 3)

1. **Insets:**
   - Bottom Bar uses `.navigationBarsPadding().imePadding()` to ensure smooth keyboard lifting with zero visual overlap.
2. **Auto-Scroll:**
   - Triggers on `(messages.size, loading)` changes with an 80ms layout pass debounce.
3. **Typing Indicator (`AssistantTypingBubble`):**
   - Renders 3 animated pulsing dots with staggered 180ms delay between dots while `loading == true`.
4. **Empty State:**
   - Includes centered branding icon, welcome headline, and horizontal suggestion chips:
     - *"¿Qué puedes hacer?"*
     - *"Explícame la arquitectura del proyecto"*
     - *"Comprueba el estado del sistema"*
5. **Double TopBar Prevention:**
   - `MainNavScreen` uses outer top bar; `ChatScreen` receives `showTopBar = false` when embedded in draft view.
6. **Internal Context Collapsibility:**
   - Any `<memory_context>` block received from backend is rendered as a clean collapsible dropdown so internal prompt injection context does not clutter the user conversation.

---

## 7. Hub API Contract (A-3 — repaired app ↔ backend contracts)

Source of truth for every `/api/*` route: `Models.kt` shapes. Verified with `node --check` + live curl against a test hub on `HUB_PORT=18767`.

### 7.1. Standard envelope (single point of normalization)

All JSON emitted through `json()` in `server.js` (the helper also used by the 4 mounted routers: skills/project/job/agent/workflow/content) is normalized by `normalizeEnvelope()`:

- **Success (2xx):** `{ "ok": true, "data": <payload> }`
- **Error (4xx/5xx):** `{ "ok": false, "error": { "code": "NOT_FOUND" | "FORBIDDEN" | "BAD_REQUEST" | ..., "message": "..." } }`
  - Legacy `fail()` / handlers that send `error` as a **string** are converted automatically (`code` derived from status, or the explicit `code` field if present).
  - `Envelope.error: String?` in `Models.kt` is only parsed on 2xx responses, where `ok:false` never travels → the object form does **not** break the app (checked against Main/Chat/ProjectDetail viewmodels).
- **Auth (A-1):** every `/api/*` except `GET /api/health` requires `X-Aegis-Token`; missing/invalid → `403 {"ok":false,"error":{"code":"FORBIDDEN","message":"missing or invalid token"}}` (JSON, never HTML).
- **Unknown `/api/*` route:** `404 {"ok":false,"error":{"code":"NOT_FOUND","message":"unknown api route","path":"/api/..."}}` — never the SPA fallback.

**Documented exceptions (2xx NOT wrapped):**

| Route | Why |
|---|---|
| `GET /api/system/status` | Raw `SystemStatus{ready,sessionOwnership,...}` — overlay gate in `MainActivity` parses the raw body. |
| `/opencode/*` proxy (SSE streams) | Event/message streams, not JSON envelopes. |
| `GET /api/device/screenshot?raw=1` | Explicit raw mode: `text/plain` base64 body. |
| `POST /api/device/a11y` forward | Passthrough of the Aegis app (:8766) body/status as-is. |

### 7.2. Health contracts

| Route | Token | Shape | Consumer |
|---|---|---|---|
| `GET /api/health` | **exempt** (light probe) | `HealthResponse{ok, data: HealthData}` **field-by-field** = `Models.kt:243` (`server,port,uptime,memory,workspace,projects,agents,jobs,skills,adapters`) — no secrets, no `df`/`su`/child shells (audit API-05) | `keepalive.sh` probe |
| `GET /api/system/health` | required | `{ok, data: HealthData + legacy diagnostics (status,timestamp,runtime,a11ySocket,agy,permissions,opencode,sessionOwnership,activeProject,lastVoice)}` | `ControlCenterViewModel` (`StatusCard` accepts `running\|healthy\|ok` → ONLINE) |

`skills.installed` in health = ids really present on disk (`SkillManager.listInstalled()`); `adapters.opencode` = `healthy|down` from a 2s HTTP probe; `adapters.antigravity` = `ok|missing` (binary presence).

### 7.3. Skills contracts

| Route | Response shape (`Models.kt`) |
|---|---|
| `GET /api/skills` (no query) | `SkillsResponse{ok, data:{installed:[SkillItem], available:[SkillItem]}}` — `installed` from disk; `available` = **real allowlist catalog minus installed** (F3/H-14); each `SkillItem = {id,name,version,description,installed,enabled}` |
| `GET /api/skills?projectId=…` (or `?project=`) | delegated to `server.js` → `Envelope<SkillListResponse{skills,projectId,counts}>` (same path, two shapes by query) |
| `GET /api/skills?scope=…` | delegated → `{ok, data:[…]}` plain array (legacy shape, **no app consumer**) |
| `POST /api/skills/install` | **202** `TaskResponse{ok,data:{taskId,message}}` — asynchronous JSON (was SSE; Retrofit only parses JSON) |
| `DELETE /api/skills/:id` | `{ok, data:{removed}}` |
| `GET/PATCH /api/skills/:id/config` | `SkillConfigResponse` / `{ok,data:{…}}` |
| `POST /api/skills`, `PATCH|DELETE /api/skills/:scope/:name` | `Envelope<Skill>` etc. |

**Honest gaps (documented, not invented):**

- `SkillsData.available` = **`backend/src/skills/catalog.json` (allowlist, F3/H-14) minus what `listInstalled()` finds on disk** — no longer `[]`. Entries are full `SkillItem`s: `installed:false`, `enabled:true` (there is no real on/off state for a non-installed skill), `version` = the catalog pin (`null` when the catalog does not pin one), `description` from the catalog.
- `POST /api/skills/install` rejects any id **outside the catalog** with `400 {ok:false,error:{code:"ALLOWLIST",…}}` (the A-1 regex check still applies *in addition*). If the catalog entry pins a `sha256`, the tarball is downloaded (`npm pack`), hashed and compared **before** installing: mismatch → `400 …code:"EBADCHECKSUM"`; verification itself impossible (registry down, bad hash format) → `400 …code:"EVERIFY"`. Only `opencode-mem@2.26.0` is pinned today (real `npm pack` sha256); `graphify` has **no `sha256`** because the installed artifact comes from a uv tool (`graphifyy`), while npm's `graphify` is an unrelated package — pinning either hash would be misleading (documented in `SkillManager.js`).
- `SkillItem.version/description` for **installed** items are `null` unless the skill's config JSON (`/root/.config/opencode/skills/<id>.json`) provides them (no manifest exists).
- `SkillItem.enabled` = `enabled` flag from that config JSON if boolean, otherwise `true` (there is no real on/off state).
- `DELETE /api/skills/:id` runs `npm uninstall -g`, which does **not** remove entries from the dir `listInstalled()` scans → the item may still appear until the config file is removed manually (known mismatch, pending). Uninstall is deliberately **not** allowlist-gated (the allowlist guards package *ingress*, not local cleanup).

### 7.4. Endpoint matrix (`ApiService.kt` → backend)

| ApiService | Backend route | Response (`Models.kt`) | State |
|---|---|---|---|
| `getProjects/create/patch/delete` | `/api/projects*` | `Envelope<Project>` | ok |
| `getOpencodeSessions/rename/delete` | `/api/opencode/sessions*` | `Envelope<…>` | ok |
| `linkSession/unlink/getProjectSessions` | `/api/projects/{id}/sessions*` | `Envelope<…>` | ok |
| `getSkills(projectId)` | `GET /api/skills?projectId=` | `Envelope<SkillListResponse>` | ok |
| `getSystemSkills` | `GET /api/skills` | `SkillsResponse` | ok (A-3) |
| `installSkill` | `POST /api/skills/install` | `TaskResponse` (202 async) | ok (A-3, was SSE) |
| `uninstallSkill/getSkillConfig/updateSkillConfig` | `/api/skills/:id…` | `BaseResponse`/`SkillConfigResponse` | ok |
| `createSkill/updateSkill/deleteSkill` | `/api/skills…` | `Envelope<Skill>` | ok |
| `getMessages` | `GET /api/opencode/sessions/{id}/messages` | `Envelope<List<Message>>` | ok |
| `sendMessage` | `POST /opencode/session/{id}/message` | raw `Message` | ok — **not under `/api/*`, so A-1 token does not apply** (exception by design, pending A-1 review) |
| `systemStatus` | `GET /api/system/status` | raw `SystemStatus` | ok (documented exception) |
| `getModels` | `GET /api/opencode/models` | `Envelope<List<ModelOption>>` | ok |
| `getSystemHealth` | `GET /api/system/health` | `HealthResponse` | ok (A-3) |
| `getSystemLogs` / `getSystemMemory` | `GET /api/system/logs` / `/memory` | `LogsResponse` / `MemoryResponse` | ok (A-3, routes added) |
| `getWorkspaceProjects` | `GET /api/workspace/projects` | `ProjectsResponse` (`ProjectItem.name` **required** + `lastCommit`) | ok (A-3 — `name` was missing → NPE in `WorkspaceScreen`) |
| `initProject` | `POST …/init` | `BaseResponse` | ok (A-3 wrapped) |
| `getProjectState` | `GET …/state` | `ProjectStateResponse` | ok |
| `indexProject` | `POST …/index` | `TaskResponse` (202 async) | ok (A-3, was SSE) |
| `getAgents/dispatch/getAgentStatus` | `/api/agents*` | `AgentsResponse`/`TaskResponse`/`AgentStatusResponse` | ok |
| *(none)* | `DELETE /api/agents/{projectId}` | `{ok,data:{projectId,message}}` | ok (A-3 wrapped; **no app consumer**) |
| `getWorkflows/runWorkflow/getWorkflowStatus` | `/api/workflows/*` | `WorkflowsResponse`/`TaskResponse`/`WorkflowStatusResponse` | ok |
| `getJobs/runJob` | `/api/jobs*` | `JobsResponse`/`BaseResponse` | ok |
| *(none)* | `GET /api/status`, `/api/device/*`, `/api/assistant/*`, `/api/voice/*`, `/api/content/status` | `{ok,data:…}` (A-3 wrapped) | consumed by scripts/voice UI, not by Retrofit |

**Honest gaps (2xx payloads):**

- `JobsSummary.lastRun` / `JobItem.lastRun` are `null` until the first run.
- `ProjectItem.lastCommit` is `null` when the folder is not a git repo or `git` is unavailable (1.5s timeout, `execFile`, never faked).
- `AgentItem.status` from `GET /api/agents` is always `"registered"` (pool registry); live run state only via `GET /api/agents/status/{projectId}`.
- Assistant disambiguation replies `200 {ok:true,data:{type:"disambiguation",…}}`; failures `4xx {ok:false,error:{…}}`.

### 7.5. keepalive.sh contract

- Probe = `curl -m 2 -s -f http://127.0.0.1:$HUB_PORT/api/health | grep -q '"server":"running"'` (token-exempt, light). The old probe hit `/api/status` **without** token → 403 forever → the loop killed/relaunched the hub every 10s.
- Hub pid pattern = `node.*Aegis/backend/server.js` (el patrón del repo pre-migración ya no matcheaba nada — no lo reintroducir).
- Package guard = `com.aegis.hub` (real `applicationId`).

---

## 8. Setup contract (F3 — wizard closing, `/api/setup/*`)

Router: `backend/src/api/setupRoutes.js` (`createSetupHandler({opencodeAdapter, probeOpencodeHealth})`), mounted in `server.js` next to the bootstrap router. **Auth:** standard — all three routes require `X-Aegis-Token` (the Fase 0 middleware covers every `/api/*` except `GET /api/health`); missing/invalid → `403 FORBIDDEN`. All responses go through `json()` → §7.1 envelope. Tests: `tests/setup.test.js`.

### 8.1. `GET /api/setup/final-check`

```json
{ "ok": true, "data": {
  "ready": false,
  "checks": [
    { "id": "opencode",    "label": "OpenCode (proxy4096)",              "status": "ok|fail|manual", "detail": "…" },
    { "id": "antigravity", "label": "Antigravity/Artemis (agy + auth)",  "status": "…",              "detail": "…" },
    { "id": "a11y",        "label": "Servicio de accesibilidad (:8766)", "status": "…",              "detail": "…" },
    { "id": "bootstrap",   "label": "Instalación inicial (wizard)",      "status": "…",              "detail": "…" }
  ]
} }
```

- `ready` = `checks.every(c => c.status === "ok")`. Check **order and labels are literal** (the UI parses them).
- `status` enum: `ok` · `fail` · `manual` (needs the user). `detail` is always a non-empty, human-readable Spanish string with the exact next action.
- The 4 checks run **in parallel**, each capped at **≤2 s** (a check that exceeds it comes back `fail` with a `timeout` detail — the endpoint never hangs and never throws; unexpected errors become `fail`/`500 envelope`, never a hung socket).
- How each check is resolved:
  | id | Resolution | `ok` | `manual` | `fail` |
  |---|---|---|---|---|
  | `opencode` | `GET http://127.0.0.1:4096/global/health` (same probe as `GET /api/health`, remaining budget of the 2 s); if down → `opencode --version` binary fallback | serve reachable | binary installed but serve down → detail says how to start it | neither serve nor binary |
  | `antigravity` | `fs.existsSync` on the `agy` candidates + `fs.statSync(...).size > 0` on `~/.gemini/antigravity-cli/antigravity-oauth-token` (**token content is never read or logged**) | `agy` + OAuth file >0 B | `agy` present but no session → detail carries the exact interactive login command | `agy` missing → detail carries the official installer `curl -fsSL https://antigravity.google/cli/install.sh \| bash` |
  | `a11y` | TCP `connect 127.0.0.1:8766` (CompanionService bridge; 1.5 s socket timeout) | accepts connection | — | `ECONNREFUSED`/timeout → detail **"Abre la app y concede accesibilidad (…)"** |
  | `bootstrap` | `state.phase` (bootstrap wizard state) | `done` | `running` → "instalación en curso" | any other phase (`idle`/`paused`/`failed`) |

### 8.2. `POST /api/setup/smoke-test`

- **Success (200):** `{ "ok": true, "data": { "ok": true, "reply": "<texto REAL del modelo>" } }` — `reply` is the model's actual first text part (usually `PONG`), **never fabricated**.
- **Failure:** standard error envelope with `error.code = "SMOKE_FAILED"` and an honest message; HTTP status tells the failure class: `502` provider down / no session, `504` timeout (>60 s or empty answer), `500` hub-side surprise.
- Mechanics: reuses the hub's real machinery — `probeOpencodeHealth()` (2 s) then `OpencodeAdapter.createSession`/`listSessions`/`sendMessage` against `127.0.0.1:4096`. It keeps a dedicated session titled **`aegis:smoke-test`** (reused across calls; recreated if OpenCode no longer lists it) and sends `"Responde exclusivamente: PONG"` with a hard **60 s** `AbortController` budget. Concurrent calls share one in-flight promise (single-flight). Async: nothing blocks the event loop, and the response is skipped if the client/hub closed the connection meanwhile.

### 8.3. `POST /api/setup/auth/antigravity`

```json
{ "ok": true, "data": { "mode": "manual", "command": "<comando exacto>", "status": "authenticated|missing_auth|missing_cli" } }
```

- `mode` is **always `"manual"`** — investigated in F3: the current `agy` CLI has **no `auth login` subcommand** (`agy help auth` → *unknown subcommand*), and the official flow (antigravity.google/docs/cli/install) is interactive: local keyring sign-in that opens the default browser, or an authorization-URL + paste-code loop over SSH. Spawning it detached would just hang waiting for a TTY/browser, so the hub is honest instead of pretending (`mode:"spawned"` is never returned by design).
- `command`: `curl -fsSL https://antigravity.google/cli/install.sh | bash` when `status:"missing_cli"`; otherwise the absolute `agy` path to run in a terminal and complete the interactive login.
- `status`: `authenticated` (OAuth file > 0 B) · `missing_auth` (CLI present, no session) · `missing_cli` (binary absent).
- The response **never contains the token** — only whether the credential file exists (> 0 B).

---

## 9. Blindado F4 — rate limit, logs, SBOM e ids (backend)

Sección nueva en FASE F4. Router/ubicación y tests de cada pieza:

| Pieza | Dónde vive | Test |
|---|---|---|
| Validación de ids (anti path-traversal) | `server.js` (`ID_RE`/`isValidId`/`invalidId`) + helpers locales en `src/api/projectRoutes.js` y `src/api/skillsRoutes.js` | `tests/security.test.js` |
| Rate limiting `/api/*` | `server.js`, middleware **antes** del token | `tests/ratelimit.test.js` |
| Logger con sink + rotación | `src/core/logger.js` (formato de línea INALTERADO) | `tests/logs.test.js` |
| `GET /api/system/logs` | `server.js` (lee el sink rotado) | `tests/logs.test.js`, `tests/cross-contract.test.js` |
| `GET /api/setup/manifest` (SBOM) | `src/api/setupRoutes.js` | `tests/manifest.test.js` |
| Contrato cruzado ↔ `Models.kt` | — (lee el fichero Kotlin, SOLO lectura) | `tests/cross-contract.test.js` |

### 9.1. Validación de ids (anti path-traversal)

Todo `:id` de proyecto/sesión/skill —incluidos los de **query** (`?projectId=`, `?scope=`)— se valida **antes** de tocar store, `fs` o cualquier `path.join`:

- Forma: `^[A-Za-z0-9._-]+$`, longitud **1..256**, y sin `..` (se valida tras `decodeURIComponent`, así que `%2F`/`%2e` codificados también caen).
- Rechazo: `400` con el envelope estándar y `error.code` según el tipo de id:

| Tipo | `error.code` |
|---|---|
| proyecto | `PROJECT_INVALID` |
| sesión | `SESSION_INVALID` |
| skill (`skillId`, `?scope=`) | `SKILL_INVALID` |
| `?projectId=` en `GET /api/skills` | `PROJECT_INVALID` |

- `?scope=` acepta además el literal `"global"`.
- El **orden de middleware no cambia**: rate limit → token (403) → routers. Sin token, un id malicioso responde `403 FORBIDDEN`, nunca `400` (no se filtra información de validación sin autenticar).
- Segunda barrera: `pathResolver.isValidProjectId` (`^[a-zA-Z0-9\-_]+$`) sigue operativa en las rutas que ya la usaban.
- Los ids **válidos** no se ven afectados: `?scope=global`, `GET /api/workflows/:projectId` y el `400 ALLOWLIST` de `POST /api/skills/install` (F3/H-14) siguen respondiendo igual.

**Divergencia documentada (NO corregida en F4):** `GET /api/projects/:id/summary` usa un regex **sin grupo de captura** → `m[1]` es `undefined` → el id resuelve `"undefined"` → `404` para *todo* id, válido o traversal. El endpoint nunca llega al filesystem con un id ajeno (seguridad intacta), pero tampoco devuelve nunca un resumen existente. Corrección de comportamiento pendiente fuera del alcance de esta fase; fijada por `tests/security.test.js` ("test 6").

### 9.2. Rate limiting `/api/*`

- Ventana **deslizante de 60 s** en memoria, sin dependencias; techo por defecto **120 peticiones/min por IP**.
- Configuración (entorno del proceso):
  - `AEGIS_RATE_LIMIT_N=<n>` → cambia el techo.
  - `AEGIS_RATE_LIMIT=0` → **desactiva** el límite (tests y depuración). No desactiva la autenticación.
- **Exentas** de consumir cuota (por diseño): `GET /api/health` (sonda de `keepalive.sh`, 1/10 s) y `GET /api/bootstrap/state` (polling del wizard, 1/s). Con ellas el wizard nunca gasta cuota.
- Excepción de techo → `429`:

```json
{ "ok": false, "error": { "code": "RATE_LIMITED", "message": "rate limit excedido: 120 peticiones/min por IP — reintenta en 42s (header Retry-After)" } }
```

  con cabecera **`Retry-After: <segundos>`** (1..60). Envelope estándar §7.1, nunca HTML.
- El middleware corre **antes** que el de token: un flood sin token también consume cuota.

### 9.3. Logger con sink + rotación (`src/core/logger.js`)

- **Formato de línea INALTERADO** (F0-F3): `[ISO] [NIVEL] [módulo] <msg> <ctx-json>`; en stdout sigue yendo por `console.log` (y por ahí `keepalive.sh` lo redirige a `hub.log`).
- **Sink nuevo** `backend/logs/aegis.log` (dir auto-creado, gitignorado en `.gitignore`: `Aegis/backend/logs/`). Escritura síncrona (`fs.writeSync` append) con el mismo `entry` que stdout: no se duplica el formato.
- **Rotación por tamaño**: al superar el máximo → `.1 → .2 → .3` (se elimina `.3`), **3 backups** ⇒ hasta 4 ficheros.
- Variables: `AEGIS_LOG_DIR` (redirige el dir — lo usan los tests) · `AEGIS_LOG_MAX_BYTES` (default 1 MB; sólo lo bajan los tests).
- **Nunca lanza**: cualquier fallo de E/S (dir no creable, disco lleno, fd invalidado) **desactiva el sink** y el logger sigue sólo con stdout. El hub sigue sirviendo con el sink caído.

### 9.4. `GET /api/system/logs`

- **Token:** requerido (`403 FORBIDDEN` sin él).
- **Parámetros:** `?lines=` (default `200`, mínimo `1`, tope `5000`) y el legacy `?limit=` que sigue enviando `ApiService.getSystemLogs(limit = 100)` — los dos se aceptan.
- **Fuente:** el sink rotado (`aegis.log` + `.1` + `.2` + `.3`), leído en orden **cronológico** (` .3 → .2 → .1 → principal`); **ya no** `hub.log` (eso es la redirección de stdout de `keepalive.sh`).

```json
{ "ok": true,
  "data":  ["[2026-09-24T01:04:30.467Z] [INFO] [hub] … {}", "…"],
  "logs":  ["… idéntico a data …"],
  "note":  "sin fichero de log todavía: …/aegis.log no existe (el sink se crea en el primer mensaje del logger)" }
```

- **Contrato con la app:** `data` sigue siendo **`Array<String>`** — es lo único que parsea `LogsResponse(val ok: Boolean, val data: List<String>?)` en `Models.kt` y **no se cambia**. `logs` es un espejo para consumidores nuevos y `note` sólo aparece si todavía no hay fichero (nunca `500` por un `.1` ilegible ni por un dir de log inutilizable). Un fichero roto se salta; la respuesta es `200`.

### 9.5. `GET /api/setup/manifest` (SBOM)

- **Token:** requerido. **Nunca lanza**: los errores se convierten en `500` envelope o en `null + note`.
- **Shape exacto:**

```json
{ "ok": true, "data": {
  "hub":     { "version": "…" },
  "node":    { "version": "v24.21.0", "sha256": "<64 hex>", "fileName": "node-v24.21.0-linux-arm64.tar.gz" },
  "ubuntu":  { "version": "24.04.5",  "sha256": "<64 hex>", "fileName": "ubuntu-base-24.04.5-base-arm64.tar.gz" },
  "opencode":{ "version": "…" | null, "note": "…" },
  "agy":     { "version": "…" | null, "note": "…" },
  "skills":  [ { "id": "graphify", "version": null }, { "id": "opencode-mem", "version": "2.26.0" } ],
  "generatedAt": "2026-09-24T01:04:30.467Z" } }
```

- **Fuentes de verdad:** `HUB_VERSION` de `server.js` (fijada en `1.0.0` por F5; el test la lee del fuente) · `src/bootstrap/node-manifest.json` (la `version` de `node` es `process.version`, la del artefacto es la pineada) · `src/bootstrap/ubuntu-manifest.json` (la versión se **deriva** del `fileName` con `ubuntu-base-([\d.]+)`) · `src/bootstrap/skills-manifest.json` ∪ `src/skills/catalog.json` (versión pineada del catálogo; `graphify → null` porque no la lleva).
- **Honestidad `null + note`:** `opencode` y `agy` son sondas reales con timeout corto (1500 ms y 3000 ms, **en paralelo**; `agy` se cachea por proceso). Si no hay serve ni binario, o el binario no responde, el campo va en `null` con un `note` que explica el porqué — **nunca se inventa una versión**.
- `skills[].version` es `string | null` (`null` = el catálogo no pinea versión).

### 9.6. CI/CD (F4)

Workflow `.github/workflows/build-apk.yml` del proyecto `Aegis`:

`backend-checks` (node --check + 54 tests) → `lint` (node --check · `bash -n` en `backend/**` y `app/**` · `grep` de `console.*` en `server.js` · YAML de los workflows) → `build-debug` (Gradle **8.9** pineado) → `build-release` (**condicional**: sin secreto `KEYSTORE_BASE64` los pasos se omiten con un aviso, sin fallar) → `semgrep` (`p/security-audit`) y `gitleaks`, ambos con **`continue-on-error: true`** hasta el primer ciclo limpio. El job `instrumented` (emulador + install + monkey) **sólo** se dispara con `workflow_dispatch` + input booleano `instrumented`.

