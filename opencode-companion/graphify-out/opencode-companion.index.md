# opencode-companion — Index

Files: 22

- `opencode-companion/server.js` — hub: Node zero-deps HTTP 8765 -> proxy 4096, device bridge, multimodal, assistant, skills, summaries, voice registry
- `opencode-companion/public/index.html` — SPA shell: drawer + composer + skills/linked UI
- `opencode-companion/public/app.js` — SPA logic: drawer/projects/sessions, multimodal, voice duplex, skills/linked, SSE
- `opencode-companion/public/style.css` — Theme + layout + status pills
- `opencode-companion/keepalive.sh` — Keepalive loop host-mount nsenter, guards TUI vs serve, hub pkill fix
- `opencode-companion/start-hub.sh` — Manual starter opencode serve + node server.js
- `opencode-companion/service.d-99-opencode-hub.sh` — Magisk boot hook
- `opencode-companion/package.json` — Hub manifest
- `opencode-companion/ui-state.json` — Runtime UI state (gitignored)
- `opencode-companion-apk/app/src/main/AndroidManifest.xml` — Manifest: INTERNET, RECORD_AUDIO, READ_CONTACTS, BIND_VOICE_INTERACTION
- `opencode-companion-apk/app/src/main/kotlin/com/opencode/companion/MainActivity.kt` — MainActivity: WebView + wake word (SharedPrefs) + STT/TTS + keepalive poll
- `opencode-companion-apk/app/src/main/kotlin/com/opencode/companion/CompanionService.kt` — Foreground bridge :8766 (a11y/shell/launch)
- `opencode-companion-apk/app/src/main/kotlin/com/opencode/companion/CompanionVoiceInteractionService.kt` — VoiceInteractionService assistant (long-press home)
- `opencode-companion-apk/app/src/main/kotlin/com/opencode/companion/OpencodeAccessibilityService.kt` — A11y: clickByText, gestures
- `opencode-companion-apk/app/src/main/kotlin/com/opencode/companion/RootShell.kt` — RootShell: su -c fallbacks
- `opencode-companion-apk/app/src/main/res/layout/activity_main.xml` — Layout: coordinator + WebView + overlay
- `opencode-companion-apk/app/src/main/res/xml/voice_interaction_service_config.xml` — Voice config: supportsAssist
- `opencode-companion-apk/app/build.gradle.kts` — Gradle: versionCode BUILD_NUMBER, signingConfigs release, deps
- `opencode-companion-apk/build.gradle.kts` — Top-level AGP
- `opencode-companion-apk/settings.gradle.kts` — Settings include :app
- `opencode-companion-apk/README.md` — APK docs: hub standalone vs a11y
- `opencode-companion-apk/install-su.sh` — Su installer: pm grant + a11y + whitelist