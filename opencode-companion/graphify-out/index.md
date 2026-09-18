# OpenCode Companion — Graphify Index

Generated: 2026-09-18T02:27:10.284251Z
Root: /sdcard/projects

## Files → Role → Deps

| Path | Role | Deps | Size |
|---|---|---|---|
| `opencode-companion/server.js` | hub: Node zero-deps HTTP 8765 -> proxy 4096, device bridge, multimodal, assistant, skills, summaries, voice registry | public/index.html, public/app.js, public/style.css, keepalive.sh | 92594 |
| `opencode-companion/public/index.html` | SPA shell: drawer + composer + skills/linked UI | public/app.js, public/style.css | 8965 |
| `opencode-companion/public/app.js` | SPA logic: drawer/projects/sessions, multimodal, voice duplex, skills/linked, SSE | server.js:api/projects, server.js:api/skills, server.js:proxy | 86178 |
| `opencode-companion/public/style.css` | Theme + layout + status pills | — | 14759 |
| `opencode-companion/keepalive.sh` | Keepalive loop host-mount nsenter, guards TUI vs serve, hub pkill fix | server.js | 8136 |
| `opencode-companion/start-hub.sh` | Manual starter opencode serve + node server.js | server.js | 1519 |
| `opencode-companion/service.d-99-opencode-hub.sh` | Magisk boot hook | keepalive.sh | 2059 |
| `opencode-companion/package.json` | Hub manifest | — | 323 |
| `opencode-companion/ui-state.json` | Runtime UI state (gitignored) | — | 82 |
| `opencode-companion-apk/app/src/main/AndroidManifest.xml` | Manifest: INTERNET, RECORD_AUDIO, READ_CONTACTS, BIND_VOICE_INTERACTION | — | 3957 |
| `opencode-companion-apk/app/src/main/kotlin/com/opencode/companion/MainActivity.kt` | MainActivity: WebView + wake word (SharedPrefs) + STT/TTS + keepalive poll | CompanionService.kt, CompanionVoiceInteractionService.kt | 36830 |
| `opencode-companion-apk/app/src/main/kotlin/com/opencode/companion/CompanionService.kt` | Foreground bridge :8766 (a11y/shell/launch) | OpencodeAccessibilityService.kt | 9105 |
| `opencode-companion-apk/app/src/main/kotlin/com/opencode/companion/CompanionVoiceInteractionService.kt` | VoiceInteractionService assistant (long-press home) | MainActivity.kt | 6924 |
| `opencode-companion-apk/app/src/main/kotlin/com/opencode/companion/OpencodeAccessibilityService.kt` | A11y: clickByText, gestures | — | 5099 |
| `opencode-companion-apk/app/src/main/kotlin/com/opencode/companion/RootShell.kt` | RootShell: su -c fallbacks | — | 2168 |
| `opencode-companion-apk/app/src/main/res/layout/activity_main.xml` | Layout: coordinator + WebView + overlay | — | 11658 |
| `opencode-companion-apk/app/src/main/res/xml/voice_interaction_service_config.xml` | Voice config: supportsAssist | — | 400 |
| `opencode-companion-apk/app/build.gradle.kts` | Gradle: versionCode BUILD_NUMBER, signingConfigs release, deps | — | 2429 |
| `opencode-companion-apk/build.gradle.kts` | Top-level AGP | — | 155 |
| `opencode-companion-apk/settings.gradle.kts` | Settings include :app | — | 336 |
| `opencode-companion-apk/README.md` | APK docs: hub standalone vs a11y | — | 2629 |
| `opencode-companion-apk/install-su.sh` | Su installer: pm grant + a11y + whitelist | — | 4787 |
| `.github/workflows/build-apk.yml` | CI: decode keystore, BUILD_NUMBER, smoke 3–15MB, cert SHA | — | 4384 |
| `.gitignore` | Ignores: *.keystore, node_modules, logs, ui-state.json | — | 652 |

## Hub routes (server.js)

- `GET/POST /opencode/*` → proxy `127.0.0.1:4096` with skills injection (`/session/*/message` buffers <512KB + X-Project-Id fallback)
- `GET /api/system/status` + `/session-info` + `POST /system/start` (non-destructive ownership termux-native/companion-owned/none, .companion-session.json)
- `GET/PATCH /api/ui/state` (project/projectId/sessionId)
- `GET/POST/PATCH/DELETE /api/projects`, `GET/POST /api/projects/:id/sessions`, `DELETE /:id/sessions/:sessionId`, `POST /:id/summarize`, `GET /:id/summary`, `GET /api/opencode/sessions` (live)
- `GET/POST/PATCH/DELETE /api/skills` (skills/{scope}/{name}.skill.md, global + project merged, summaries/{id}.summary.json)
- `GET /api/voice/commands`, `POST/GET /api/voice/log` (50), `GET /api/health` (opencode/bridge/disk/uptime/ownership/activeProject/lastVoice)
- `GET /api/device/*` (shell, launch, tap, input, key, apps, a11y, screenshot), `GET /api/status` (legacy)

## APK ↔ Hub link

- `MainActivity.WebView` → `http://127.0.0.1:8765` (native overlay Iniciar Sistema, isHubReady 200, su keepalive poll)
- `CompanionService :8766` ← `hub /api/device/a11y` forwards; `OpencodeAccessibilityService` clickByText
- `MainActivity` wake word `viernes escucha` (continuous SpeechRecognizer) → `window.startVoiceSession()` → `app.js voiceHandleText` → `VOICE_COMMANDS` (nuevo proyecto, abrir proyecto, nueva sesión, abrir app, captura, estado, whatsapp)
- `CompanionVoiceInteractionService` → Android Default Assistant (long-press home)

## Build

- `versionCode = BUILD_NUMBER|GITHUB_RUN_NUMBER ?: 1` (unique per CI run)
- `signingConfigs release` from `KEYSTORE_BASE64` → `companion-release.keystore` (PKCS12 compat PBE-SHA1-3DES, SHA256 C3:2D:67:...) fallback debug locally
- `smoke-test` needs:build, artifact `app-release.apk` 3–15MB + cert SHA
