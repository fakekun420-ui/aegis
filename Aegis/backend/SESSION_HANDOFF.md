# Aegis — Session Handoff (histórico: escrito antes de la migración a Aegis)

**Date:** 2026-09-18
**From:** `ses_f59ee1524ffe6C75XZej2OGr92` — `Opencode-companion` (stuck, 1392 msgs / 21353 events / 21 MB, archived read-only at `/tmp/opencode/ses_f59ee1524ffe6C75XZej2OGr92.json`)
**To:** `ses_f4c94c66dffenoqFOCFaytnre7` — `Opencode Companion (continued)` (new, clean)
**Rule:** Do NOT replay the old session's 1392 messages. This file is the condensed continuation. Abandon the old session; continue here.

## Current project status

- **Repo:** `/sdcard/projects/Aegis` (monorepo unificado: hub `backend/server.js` en `:8765`, web UI en `backend/public/`, app Android en `app/`)
- **Hub:** `1.18.31`, `ready:true healthy:true` on `127.0.0.1:4096`, ownership `termux-native` (PID 27592) — do not touch live DB; hub serves `public/app.js` with `Cache-Control:no-cache`.
- **Progress:** 6 phases complete. Latest web commits on `master`: `229fbcd` / `1aef62c` / `6f58cff` / `0089193` (graphify 24 nodes) / `346b271` etc. APK `versionCode=24` (run 24, `0089193`/`aa91263`) previously signed-installed as `app-release.apk` 4.5 MB with keystore `db4b6ee` (no reinstall needed); current device may show no package after recent state — verify with `dumpsys package com.aegis.hub | grep -E "versionCode|lastUpdate"` before next build. CI trigger remains `git push` via path filter; manual trigger `curl -X POST .../actions/workflows/.../dispatches` if needed.
- **DB:** `opencode.db` 2.0 GB — `PRAGMA integrity_check=ok`, 19 sessions, `FOREIGN KEY` clean. Prior corruption was from live `UPDATE`/`DELETE` under `opencode serve` (WAL 4.1 MB → 4.1 KB at 2026-09-18T05:37), not size. Root cause is `UNIQUE(event.aggregate_id,event.seq)` races — see `NOTES.md`. No further manual SQL while serve is running.

## Pending UI fixes (do these first, one by one, with live checks)

1. **Black session-row text** — `.session-row`/`.session-title` renders black on dark drawer, invisible.
2. **Missing 3-dot menu** on session rows — kebab/menu to expose per-session actions.
3. **Drawer scroll-refresh bug** — scrolling the drawer triggers a full project/session refresh instead of scrolling.
4. **Chat not auto-scrolling to last message** — new assistant/user bubbles should scroll `msgsEl` to `scrollHeight` after render and after SSE chunk; currently sticks at top.
5. **Markdown rendering rough** — code blocks/inline code/lists in assistant messages not styled; raw markdown shown.
6. **`memory_context` block rendering visibly** — internal `<memory_context><project_knowledge><memory relevance="100%">` XML that should be invisible background context (injected via `server.js:proxyWithInjection` / `opencode-mem` plugin) leaks as a visible chat bubble. Already partially audited; injection now sanitizes and dedupes by `[SYSTEM CONTEXT` prefix and caps at 12 KB.

## Unresolved technical decisions

- Should `projects.json` have a dedicated **"Opencode Companion"** managed project (separate from `mu6e90j5-fvgrx7` "Agencia de Marketing") to own this continued session, or keep sessions at root `/sdcard/projects`? If creating, use `POST /api/projects` then `POST /api/projects/:id/sessions`.
- Whether to keep hub injection (`buildSystemContextBlock` skills + cross-project linked summaries) vs. disabling it until UI stabilizes — current code is hardened (control-char strip, 12 KB cap, 512 KB payload skip) but still adds payload.
- How to handle the abandoned session's `projects.json` entries (`spec 2` sessions association) — leave `ses_f59ee…` unassociated; do not delete it in DB.
- APK versioning for next fix: bump `versionCode` to 25+ and verify `WEBVIEW versionCode` parity (`app.js:1736` vs `MainActivity.kt:LOAD_NO_CACHE`) after install.

## Continuation protocol

1. **First action in the new session:** read this file (`/sdcard/projects/Aegis/backend/SESSION_HANDOFF.md`) to load context.
2. Confirm readiness with a short response restating project status from this handoff (proof the new session is unblocked and context preserved).
3. Pick the next pending UI fix from the list above and implement it as a surgical patch with a live verification loop (`curl /api/system/status`, WebView check, screenshot if needed).

## References (read-only)

- Old session export: `/tmp/opencode/ses_f59ee1524ffe6C75XZej2OGr92.json` (21 MB) — read-only, never `DELETE`/`UPDATE` live.
- Companion repo: `git log --oneline -5` en el monorepo unificado `Aegis` (antes eran dos repos: backend y app) — el head debe mostrar `229fbcd` o un commit posterior.
- Bug reproduction: documented in `NOTES.md` (and below).
