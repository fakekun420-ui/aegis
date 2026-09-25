# QUICKSTART — de cero a producto (v1.1.0)

Guía completa de la primera vez: **instalar la APK → wizard de 6 pasos → verificar → usar**.
Al final, troubleshooting y enlaces al resto de documentación.

> **Modelo mental:** Aegis es un hub con *root total con token*. Todo vive en el dispositivo;
> el hub sólo escucha en `127.0.0.1:8765` (ver [ADR-001](adr/ADR-001-security-model.md)) y
> el wizard autogestionado monta Ubuntu + Node + OpenCode + Antigravity + skills
> (ver [ADR-002](adr/ADR-002-bootstrap-design.md)). Enlaces al final.

---

## 0. Precondiciones

| Requisito | Detalle |
|---|---|
| Dispositivo | ARM64/aarch64 — en desarrollo: POCO F3 (`alioth`), Android 15, crDroid |
| Root | Magisk/KernelSU/APatch (o Termux + chroot). El wizard exige root en `preflight` |
| Red | WiFi estable (el wizard descarga y verifica por SHA256) |
| Espacio | Libre para el rootfs de Ubuntu base + tarball de Node |
| PC (opcional) | `adb` para instalar el APK y para `adb forward` |
| Compilar (opcional) | JDK 17 + Android SDK. **No hace falta**: el CI publica el artifact |

---

## 1. Instalar la APK

**Opción A — desde el CI (recomendada):**
1. GitHub Actions → workflow *Build Aegis APK* → artifact **`aegis-debug`**.
2. Descárgalo en el dispositivo como `/sdcard/Download/app-debug.apk`.
3. Instala con root (permisos + accesibilidad + doze whitelist + arranque del servicio):
   ```sh
   su -c "sh /sdcard/projects/Aegis/app/install-su.sh"
   ```
   El script hace 6 pasos (`pm install`, `RECORD_AUDIO`/`POST_NOTIFICATIONS`/doze,
   AccessibilityService, whitelist de batería, arranque de `CompanionService` en `:8766`,
   verificación) y tarda **~60 s + 10 s**.

**Opción B — a mano:**
```sh
adb install -r /ruta/al/apk.apk
# tras instalar: abrir "Aegis" → Activar accesibilidad → habilitar el servicio
```

---

## 2. Primer arranque

1. Abre **Aegis**. La app consulta `GET /api/bootstrap/state` (con token leído vía root de
   `backend/.aegis_token`).
2. Si el hub no está corriendo, aparece el overlay **"Sistema desconectado"** →
   botón **"Iniciar Sistema"** (levanta hub + `opencode serve` con root).
3. Si `phase != done`, la app navega sola al **wizard de configuración**. Ese es el único
   camino de primera ejecución: no hay pasos manuales obligatorios.

---

## 3. El wizard: 6 pasos

Botones según estado: **Iniciar instalación** (idle) · **Cancelar** (running) ·
**Reintentar paso** + **Reanudar** (failed) · **Reanudar** (paused) · **Continuar** (done).

| # | Paso | Qué comprueba `check()` (idempotente) | Qué hace `run()` | Qué verifica |
|---|---|---|---|---|
| 1 | **Comprobación previa** | root/su, arquitectura `arm64`, disco libre, red | no muta: es el preflight duro | falla con error honesto si falta algo |
| 2 | **Ubuntu (chroot/proot)** | rootfs presente (`/etc/os-release`) | descarga `ubuntu-base-24.04.5-base-arm64.tar.gz` y extrae | SHA256 oficial (`ubuntu-manifest.json`) + `os-release`; detecta chroot/unshare/proot |
| 3 | **Node.js** | `node -v` usable | reutiliza `backend/node.bin` si responde; si no, descarga `node-v24.21.0-linux-arm64.tar.gz` | SHA256 oficial (`node-manifest.json`) + `node -v`; enlaza en `PATH` |
| 4 | **OpenCode** | `opencode --version` | wrapper 0o755 sobre `backend/opencode.cjs` (fallback `npm install -g opencode-ai`) | `opencode --version` |
| 5 | **Antigravity / Artemis** | binario `agy` + `~/.gemini/antigravity-cli` con auth `>0 B` | crea el dir si falta; **no** escribe ni imprime tokens | `agy --version` y existencia del fichero OAuth (nunca su contenido) |
| 6 | **Skills y plugins** | skills de `skills-manifest.json` instaladas | `SkillManager.install` sólo de la allowlist del catálogo | binario en `~/.local/bin` + `sha256` del tarball |

**Reglas del motor** (ver [ADR-002](adr/ADR-002-bootstrap-design.md)):
- **SHA256 obligatorio:** sin hash de 64 hex en el manifiesto, *no se descarga nada*.
- **Estado persistente atómico** en `backend/bootstrap-state.json`: si el hub muere a mitad,
  `running` se normaliza a `paused` y el wizard es reanudable (nunca queda “corriendo” para siempre).
- **Rollback por paso**, sólo sobre los artefactos creados por **ese** run (LIFO).

**Tiempos típicos:** el criterio de aceptación es **`done` en ≤25 min con WiFi** en un
dispositivo de fábrica. Si `backend/node.bin` ya existe, el paso 3 es casi inmediato.
Durante la ejecución el progreso se actualiza en vivo (polling 1 s) y puedes **Cancelar**
cooperativamente en cualquier checkpoint.

**Si algo falla a mitad:**
- Corte de WiFi → el paso en curso pasa a `failed` con un **error honesto** (nunca un 500 vacío).
  Pulsa **Reintentar paso** (o **Reanudar** para continuar desde ahí); los pasos ya `done/skipped`
  no se repiten.
- Checksum manipulado / descarga corrupta → `EBADCHECKSUM`, **rollback** del paso y la tarjeta
  del paso muestra **"Cambios deshechos — seguro reintentar"**.
- Hooks para probar sin tocar el dispositivo (sólo tests/QA):
  `AEGIS_BOOTSTRAP_DRY=1` (simula sin mutar ni descargar) y
  `AEGIS_BOOTSTRAP_FAIL=<stepId>` (fuerza fallo + rollback + retry).

---

## 4. Verificación final + smoke test

Al terminar, la app muestra la tarjeta **"Verificación final"**:

1. **Ejecutar verificación** → `GET /api/setup/final-check` (4 checks en paralelo, ≤2 s c/u):
   - `OpenCode (proxy4096)` — sonda real a `127.0.0.1:49374`
   - `Antigravity/Artemis (agy + auth)` — binario + fichero OAuth (>0 B, sin leerlo)
   - `Servicio de accesibilidad (:8766)` — sonda a la app
   - `Instalación inicial (wizard)` — `phase == done`
   Todos en `ok` ⇒ `ready: true`. Si alguno queda en `manual`/`fail`, el detalle te dice qué hacer.
2. **Smoke test** → `POST /api/setup/smoke-test` (hasta 60 s): crea/reutiliza la sesión
   `aegis:smoke-test`, manda *"Responde exclusivamente: PONG"* y devuelve el texto **real** del
   modelo. **Resultado esperado: `PONG`** (si es vacío/timeout → `502/504 SMOKE_FAILED` con
   diagnóstico, nunca una respuesta inventada).
3. **Auth de Antigravity (si aplica):** la tarjeta ofrece el comando oficial
   (`curl -fsSL https://antigravity.google/cli/install.sh | bash`) para copiarlo y ejecutarlo a
   mano, y luego **"Reintentar verificación"**.
4. **Continuar** → entras a la app normal.

---

## 5. Usar la app

- **Chats:** selector multiproveedor (OpenCode / Antigravity) en vivo, streaming con cursor,
  sesiones ligadas a proyectos (`projects.json`), voz 🎙️ (STT/TTS nativos).
- **Control Center:** estado `ONLINE`/`OFFLINE` del hub, uptime, memoria, workspace, proyectos,
  agentes, jobs, skills y adapters (`GET /api/health` sin token / `/api/system/health` con token).
- **Skills:** lista instaladas/disponibles desde la allowlist; instalar fuera del catálogo →
  `400 ALLOWLIST`.
- **Projects / Workflows:** workspace, init/indexar, DAGs del workflow engine.
- **Hub web:** `http://127.0.0.1:8765` desde el navegador del propio dispositivo (con token en
  `/api/*`).

---

## 6. Troubleshooting

### El hub no responde (`Sistema desconectado` / Control Center OFFLINE)
```sh
# ¿está vivo el proceso? (patrón real de keepalive)
pgrep -af "Aegis/backend/server.js"

# sonda ligera SIN token (la única ruta exenta)
curl -s http://127.0.0.1:8765/api/health

# levantarlo a mano
sh /sdcard/projects/Aegis/backend/start-hub.sh

# logs
tail -50 /sdcard/projects/Aegis/backend/hub.log          # stdout del hub
tail -50 /sdcard/projects/Aegis/backend/logs/aegis.log   # logger rotado (1 MB ×3)
```
`keepalive.sh` sondea `/api/health` cada 10 s y relanza lo caído; si el hub “parpadea”, revisa
`keepalive.log` y que no haya otro `node server.js` peleando por el puerto.
En la app, el botón **"Iniciar Sistema"** del overlay hace ese trabajo con root.

### `403 FORBIDDEN` en todo
Falta o falla el header `X-Aegis-Token`:
```sh
TOKEN=$(cat /sdcard/projects/Aegis/backend/.aegis_token)
curl -s -H "X-Aegis-Token: $TOKEN" http://127.0.0.1:8765/api/setup/manifest
curl -s http://127.0.0.1:8765/api/health   # ésta SIEMPRE va sin token
```
La app lo lee vía root (`TokenProvider`/`ApiClient`) y reintenta una vez ante 403.

### `429 RATE_LIMITED`
Rate limit por diseño: **120 req/min por IP** con header `Retry-After`.
Exentas: `GET /api/health` y `GET /api/bootstrap/state`. Para depuración:
`AEGIS_RATE_LIMIT=0` (sólo entornos de test).

### Acceder desde otro dispositivo o desde el PC
El hub **no** escucha en la IP LAN (es intencional). Usa ADB port-forward con el dispositivo
conectado:
```sh
adb forward tcp:8765 tcp:8765
# en el PC:
curl -s http://127.0.0.1:8765/api/health
curl -s -H "X-Aegis-Token: <token>" http://127.0.0.1:8765/api/setup/final-check
# y abre http://127.0.0.1:8765 en el navegador del PC
```
El bridge de accesibilidad de la app (`:8766`) es loopback por el mismo motivo.

### El wizard no aparece en el segundo arranque
Es el comportamiento esperado: con `phase == done` la app arranca en la ruta normal. Si quieres
verlo otra vez, borra `backend/bootstrap-state.json` **sólo** si estás dispuesto a re-ejecutar el
bootstrap (los `check()` son idempotentes: lo ya instalado sale `skipped`).

### El wizard quedó `paused`/`failed`
Abre la app → wizard → **Reanudar** (o **Reintentar paso** para el fallido). El estado vive en
`backend/bootstrap-state.json` y sobrevive a reinicios del hub.

### Accesibilidad no conectada (`:8766`)
Abre la app una vez (dispara `onCreate` de `CompanionService`) o reinstala con
`install-su.sh`, que activa el AccessibilityService y la whitelist de batería.

---

## 7. Siguientes lecturas

| Doc | Para qué |
|---|---|
| [`../README.md`](../README.md) | Visión general, arquitectura y setup de desarrollo |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | Estructura del monorepo y componentes |
| [`adr/ADR-001-security-model.md`](adr/ADR-001-security-model.md) | Modelo de seguridad y rotación del keystore |
| [`adr/ADR-002-bootstrap-design.md`](adr/ADR-002-bootstrap-design.md) | Diseño del wizard idempotente/reanudable |
| [`../backend/docs/FRONTEND_CONTRACT.md`](../backend/docs/FRONTEND_CONTRACT.md) | Contrato app ↔ hub (envelope, health, setup, F4) |
| [`../backend/docs/BACKEND_ARCHITECTURE.md`](../backend/docs/BACKEND_ARCHITECTURE.md) | Arquitectura del hub, keepalive, salud |
| [`qa/QA_CHECKLIST.md`](qa/QA_CHECKLIST.md) | Checklist de aceptación reproducible |
| [`audits/`](audits/) | Auditorías históricas (crónicas, no se reescriben) |
| [`../CHANGELOG.md`](../CHANGELOG.md) | Qué cambió en v1.1.0 |
