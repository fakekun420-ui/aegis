# QA — Checklist de aceptación v1.0.0

Checklist **reproducible por otra persona**, en un dispositivo real. Cada caso tiene pasos,
resultado esperado y casilla. Marca `[x]` sólo cuando lo hayas ejecutado **tú** y anota fecha +
dispositivo en la tabla final.

> Regla de oro: **un PASS sin evidencia no cuenta** (lección de `docs/audits/H-08`).
> Adjunta el comando y su salida (o screenshot) en el informe de QA de la release.

## Precondiciones

- [ ] Dispositivo **arm64** con Android 15 (ref: POCO F3 `alioth`), root Magisk/KernelSU **o**
      Termux + chroot. Verificar: `su -c id` → `uid=0`.
- [ ] Red **WiFi estable** con acceso a `cdimage.ubuntu.com`, `nodejs.org` y `registry.npmjs.org`.
- [ ] Espacio libre para el rootfs de Ubuntu base + tarball de Node.
- [ ] APK v1.0.0 instalado (`adb shell dumpsys package com.aegis.hub | grep -E "versionName|versionCode"`).
- [ ] Hub accesible: `curl -s http://127.0.0.1:8765/api/health` responde **sin token**.
- [ ] Token a mano: `TOKEN=$(cat /sdcard/projects/Aegis/backend/.aegis_token)`.

---

## 1. Instalación limpia → wizard → `done` en ≤25 min

- [ ] **Paso.** Dispositivo sin Aegis (borra `backend/bootstrap-state.json` y el rootfs si
      quieres el caso más duro). Instala el APK y abre la app.
- [ ] **Esperado.** Arranca **en el wizard** (porque `phase != done`). Botón
      **"Iniciar instalación"** → los 6 pasos avanzan con detalle en vivo: *Comprobación previa*,
      *Ubuntu (chroot/proot)*, *Node.js*, *OpenCode*, *Antigravity / Artemis*, *Skills y plugins*.
- [ ] **Esperado.** `phase == done` en **≤25 min** con WiFi (criterio F2) y botón **"Continuar"**.
- [ ] **Evidencia.** `curl -s -H "X-Aegis-Token: $TOKEN" http://127.0.0.1:8765/api/bootstrap/state`
      → `"phase":"done"` y los 6 pasos en `done|skipped`.

## 2. Corte de WiFi a mitad → error honesto → retry → continúa

- [ ] **Paso.** Con el wizard en `running` (idealmente durante la descarga de Ubuntu o Node),
      apaga el WiFi a mitad del paso.
- [ ] **Esperado.** El paso pasa a `failed` con **un error legible y honesto** (timeout/descarga),
      la fase a `failed`, y los pasos posteriores quedan `pending`. **No** hay pantalla en blanco
      ni crash de la app.
- [ ] **Paso.** Restaura el WiFi y pulsa **"Reintentar paso"** (o **"Reanudar"**).
- [ ] **Esperado.** El paso fallido se re-ejecuta; los pasos ya `done`/`skipped` **no** se repiten;
      la ejecución continúa hasta `done`.

## 3. Checksum manipulado → rollback → tarjeta "Cambios deshechos"

- [ ] **Paso.** Simula el fallo de integridad **sin tocar el dispositivo real**:
      `AEGIS_BOOTSTRAP_FAIL=ubuntu` (fuerza `FAIL_INJECTED` antes de mutar) o, para el
      checksum, un MITM/manual: edita `backend/src/bootstrap/ubuntu-manifest.json` con un
      `sha256` distinto al real (o apaga la red justo tras iniciar la descarga) y reinicia el
      wizard.
- [ ] **Esperado.** El paso `ubuntu` falla con `EBADCHECKSUM…` (o `FAIL_INJECTED`), se ejecuta el
      **rollback del paso** y la tarjeta muestra **"Cambios deshechos — seguro reintentar"**
      (`rollback: done`). No queda artefacto a medias de **ese** run.
- [ ] **Paso.** Corrige el manifiesto (deja el hash oficial) y pulsa **"Reintentar paso"**.
- [ ] **Esperado.** Descarga y verificación correctas → el paso pasa a `done`.
- [ ] **Evidencia.** `GET /api/bootstrap/state` → `rollback:"done"` en el paso afectado.

## 4. Segundo arranque → SIN wizard (`phase=done`)

- [ ] **Paso.** Cierra la app por completo y vuelve a abrirla (sin tocar `bootstrap-state.json`).
- [ ] **Esperado.** La app **no** muestra el wizard: entra en la ruta normal. Si ejecutas el
      wizard a mano, todos los pasos salen `skipped` con detalle de “ya satisfecho”.
- [ ] **Evidencia.** `GET /api/bootstrap/state` → `"phase":"done"`.

## 5. Verificación final + smoke test → "PONG"

- [ ] **Paso.** En el wizard, tarjeta **"Verificación final"** → **"Ejecutar verificación"**.
- [ ] **Esperado.** `GET /api/setup/final-check` → `ready:true` con los 4 checks en `ok`:
      `OpenCode (proxy4096)`, `Antigravity/Artemis (agy + auth)`,
      `Servicio de accesibilidad (:8766)`, `Instalación inicial (wizard)`.
- [ ] **Paso.** Pulsa **"Ejecutar smoke test"** (hasta 60 s).
- [ ] **Esperado.** `reply` = **`PONG`** (texto real del modelo). Si no hay credenciales/serve,
      error honesto `502/504 SMOKE_FAILED` con diagnóstico — **nunca** una respuesta inventada.
- [ ] **Evidencia.**
      `curl -s -H "X-Aegis-Token: $TOKEN" -X POST http://127.0.0.1:8765/api/setup/smoke-test`

## 6. Seguridad: token y shell

- [ ] **Paso.** Barrido **sin token**:
      `for p in /api/system/health /api/skills /api/setup/manifest /api/bootstrap/state /opencode/session/x; do curl -s -o /dev/null -w "$p -> %{http_code}\n" http://127.0.0.1:8765$p; done`
- [ ] **Esperado.** **403** en todo **excepto** `GET /api/health` (200 sin token).
      Con token: `curl -s -o /dev/null -w "%{http_code}\n" -H "X-Aegis-Token: $TOKEN" http://127.0.0.1:8765/api/setup/manifest` → **200**.
- [ ] **Paso.** Shell con meta-caracteres:
      `curl -s -o /dev/null -w "%{http_code}\n" -H "X-Aegis-Token: $TOKEN" -X POST -H 'Content-Type: application/json' -d '{"cmd":"ls; id"}' http://127.0.0.1:8765/api/device/shell`
- [ ] **Esperado.** **400** con `code:"BAD_REQUEST"` (rechazo de `;`). Repetir con `|`, `` ` ``,
      `&&` → igual.
- [ ] **Paso.** Rate limit: 121 peticiones rápidas a una ruta contada (`/api/setup/manifest`).
- [ ] **Esperado.** La 121ª → **429** + header `Retry-After`. (`GET /api/health` no consume cuota.)
- [ ] **Paso.** Bind: `ss -lntp | grep 8765` (o `netstat`) desde Termux/red.
- [ ] **Esperado.** Sólo `127.0.0.1:8765` — nada en `0.0.0.0`.

## 7. Pruebas automatizadas

- [ ] **Paso.**
      `cd /sdcard/projects/Aegis/backend && find . -name "*.js" -not -path "*/node_modules/*" -print0 | xargs -0 -n1 node --check`
- [ ] **Esperado.** Sin salida = todos los `.js` OK.
- [ ] **Paso.** `cd /sdcard/projects/Aegis/backend && npm test`
- [ ] **Esperado.** **47/47 en verde** (`node --test tests/*.test.js`); si hay skips/fallos, no
      se aprueba la release.
- [ ] **Paso (si tocas scripts):** `bash -n <script.sh>` por cada `.sh` modificado.
- [ ] **Paso (app):** `gradle test` desde `app/` → 25 tests JVM en verde
      (+ `gradle connectedAndroidTest` con dispositivo → 5 instrumentados).

## 8. CI en verde

- [ ] **Paso.** Push a `main` → workflow *Build Aegis APK*.
- [ ] **Esperado.** En verde: `backend-checks`, `lint`, `build-debug` y (si hay secrets)
      `build-release`. `semgrep` y `gitleaks` son **no bloqueantes** (informativos);
      `instrumented` es manual.
- [ ] **Esperado.** Artifact `aegis-debug` descargable y el APK instala con `install-su.sh`.
- [ ] **Nota operativa:** el job `build` de F0-F3 se renombró a **`build-debug`** en F4. Si el
      repo en GitHub tiene reglas de protección de rama que exigen el check `build`, hay que
      actualizarlas a `build-debug` desde *Settings → Branches* (este entorno no puede tocar
      esa configuración).

---

## Registro de ejecución

| # | Casilla | Fecha | Dispositivo / Android | Evidencia (comando + salida / screenshot) | Tester |
|---|---|---|---|---|---|
| 1 | [ ] | | | | |
| 2 | [ ] | | | | |
| 3 | [ ] | | | | |
| 4 | [ ] | | | | |
| 5 | [ ] | | | | |
| 6 | [ ] | | | | |
| 7 | [ ] | | | | |
| 8 | [ ] | | | | |

**Estado de la release:** `[ ]` QA de dispositivo pendiente · `[ ]` aprobado por segunda persona
