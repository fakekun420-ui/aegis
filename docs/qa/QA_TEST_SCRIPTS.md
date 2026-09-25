# QA Test Scripts — Aegis Execution Guide (POCO F3)

> 📄 **Documento histórico (snapshot).** Describe el estado del proyecto en el momento
> en que se escribió y **no se mantiene al día**. Para el estado actual ver
> `CHANGELOG.md`, `docs/ARCHITECTURE.md` y `backend/.ponytail.md`.

This document contains automated and reproducible physical validation scripts for each of the 8 acceptance cases defined in `docs/qa/QA_CHECKLIST.md`.

---

## Environment Setup
Run on host / Termux / ADB:
```bash
export TOKEN=$(cat /sdcard/projects/Aegis/backend/.aegis_token 2>/dev/null)
export HUB_URL="http://127.0.0.1:8765"
```

---

## Case 1: Clean Install → Wizard Execution (≤25 min)
### Automated / API Verification:
```bash
# Check bootstrap state and ensure phase is done or tracked
curl -s -H "X-Aegis-Token: $TOKEN" "$HUB_URL/api/bootstrap/state" | jq '{phase: .data.phase, steps: [.data.steps[] | {id: .id, status: .status}]}'
```
### Physical Device Steps:
1. Clear existing bootstrap state if doing a fresh test:
   `rm -f /sdcard/projects/Aegis/backend/bootstrap-state.json`
2. Launch Aegis application (`com.aegis.hub`).
3. Tap **"Iniciar instalación"**.
4. Observe the 6 progress cards: Pre-checks, Ubuntu chroot, Node.js, OpenCode, Antigravity/Artemis, Skills/plugins.
5. Verify completion within ≤ 25 min and appearance of **"Continuar"** button.

---

## Case 2: WiFi Disconnection Recovery
### Physical Device Steps:
1. While installation/download is active, toggle Airplane Mode or disable WiFi on the POCO F3.
2. Observe error state: the failing step card turns red with a clear diagnostic message (no app freeze or crash).
3. Re-enable WiFi.
4. Tap **"Reintentar paso"** (or **"Reanudar"**).
5. Verify already completed steps remain intact and execution resumes to completion.

---

## Case 3: Checksum Rollback
### Automated Simulation:
```bash
cd /sdcard/projects/Aegis/backend
# Simulate integrity failure injection without harming real device binaries
AEGIS_BOOTSTRAP_FAIL=ubuntu node --test tests/bootstrap-rollback.test.js
```
### Physical Device Steps:
1. If failure occurs during extraction or hash mismatch, verify card states: "Cambios deshechos — seguro reintentar" (`rollback: done`).
2. Verify no orphan half-extracted rootfs files remain in disk.

---

## Case 4: Subsequent Cold Starts (Bypassing Wizard)
### Automated / API Verification:
```bash
curl -s -H "X-Aegis-Token: $TOKEN" "$HUB_URL/api/bootstrap/state" | jq '.data.phase'
# Expected: "done"
```
### Physical Device Steps:
1. Swipe Aegis away from the Android Recent Apps / multitask list.
2. Tap the Aegis app icon to cold start.
3. Verify it lands immediately on the main Hub interface (Control Center / Chats / Projects), completely bypassing the initial setup wizard.

---

## Case 5: Final Verification & Smoke Test ("PONG")
### Automated Validation:
```bash
# 1. Final check validation
curl -s -H "X-Aegis-Token: $TOKEN" "$HUB_URL/api/setup/final-check" | jq .

# 2. Real smoke test round-trip
curl -s -X POST -H "X-Aegis-Token: $TOKEN" "$HUB_URL/api/setup/smoke-test" | jq .
# Expected output:
# {
#   "ok": true,
#   "data": {
#     "ok": true,
#     "reply": "PONG"
#   }
# }
```

---

## Case 6: Security, Token Authorization, Traversal & Rate Limiting
### Automated Validation:
```bash
# 1. Auth check: unauthenticated requests must yield 403 except health (200)
for p in /api/system/health /api/skills /api/setup/manifest /api/bootstrap/state /opencode/session/x; do
  code=$(curl -s -o /dev/null -w "%{http_code}" "$HUB_URL$p")
  echo "$p -> $code (expected 403 except health)"
done

# 2. Path Traversal & Dot ID rejection:
curl -s -H "X-Aegis-Token: $TOKEN" "$HUB_URL/api/projects/project.with.dots/path" | jq .
# Expected: {"ok": false, "error": {"code": "PROJECT_INVALID", ...}}

# 3. Security test suite execution:
cd /sdcard/projects/Aegis/backend && node --test tests/security.test.js
```

---

## Case 7: Automated Backend Test Suite
### Automated Validation:
```bash
cd /sdcard/projects/Aegis/backend
# Check syntax across all JS files
find . -name "*.js" -not -path "*/node_modules/*" -print0 | xargs -0 -n1 node --check

# Execute entire suite
npm test
# Expected: 54 pass, 0 fail
```

---

## Case 8: CI & Build Pipeline Verification
### Verification:
```bash
# Check workflow YAML syntax and blocking security steps
cd /sdcard/projects/Aegis
git log -1 --stat
```
Check GitHub Actions web console for passing `backend-checks`, `lint`, `build-debug`.
