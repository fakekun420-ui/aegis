# opencode-companion-apk — Index

Files: 13

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