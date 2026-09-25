# Aegis App (Android) — POCO F3 crDroid 15 (ROOT)

App Android (`com.aegis.hub`) del **Aegis Mobile Development Hub**: hub web + voz + control total del dispositivo para opencode.

> Documentación de lanzamiento: [`../docs/QUICKSTART.md`](../docs/QUICKSTART.md) · Contrato app ↔ hub: [`docs/FRONTEND_CONTRACT.md`](docs/FRONTEND_CONTRACT.md)

## Qué hace
- **Hub web (8765)**: proxy a `opencode serve :49374` + `/api/device/*`. No necesita APK.
  Arranque: `sh /sdcard/projects/Aegis/backend/start-hub.sh` (el hub hace bind a `127.0.0.1` — ver ADR-001).
- **App Aegis (8766)**: `AccessibilityService` (click por texto/id, tap, back/home, dump) + `SpeechRecognizer`/`TTS` nativos (sin restricción HTTPS) + `su` shell.
- **Wizard de bootstrap**: 6 pasos idempotentes/reanudables con verificación SHA256 y rollback (ver ADR-002).

Unificados: el hub reenvía `/api/device/a11y` → `127.0.0.1:8766/a11y` si la app está instalada; si no, hace fallback a `uiautomator dump` + shell.

## Uso inmediato (sin compilar APK) — 1 min
```sh
sh /sdcard/projects/Aegis/backend/start-hub.sh
# abre en Chrome del POCO F3:
# http://127.0.0.1:8765
```
En el hub: elige proyecto → nueva sesión → dicta con 🎙️ → auto-voz lee la respuesta. Control dispositivo: shell, abrir app, tap, key.

**Token:** `/api/*` y `/opencode/*` exigen `X-Aegis-Token` (fichero `backend/.aegis_token`); sólo `GET /api/health` va sin él.

Limitación web STT: Chrome bloquea micrófono en `http://192.168.x.x`. Usa `https` o la app (STT nativo).

## Instalar la app en el dispositivo
```sh
# con root (recomendado: instala + permisos + a11y + doze whitelist + arranque del servicio)
su -c "sh /sdcard/projects/Aegis/app/install-su.sh"     # busca APK en Download/ y build/outputs/

# o a mano
adb install -r app-release.apk
```
Tras instalar: abre **Aegis** → `Activar accesibilidad` → habilita el servicio.

## Compilar APK
Requiere JDK 17 + Android SDK. `gradle-wrapper.jar` se genera en CI; no necesitas commit binarios.

- **Local** (si tienes SDK): desde `app/` → `gradle assembleDebug` / `assembleRelease`
  (`versionName 1.1.0`, `versionCode` = `BUILD_NUMBER`/`GITHUB_RUN_NUMBER`, fallback local `2`).
- **CI (recomendado)**: GitHub Actions `Build Aegis APK` → artifacts `aegis-debug` y `aegis-release`
  (release firmado con `KEYSTORE_BASE64` — rotación del keystore en `../docs/adr/ADR-001-security-model.md`).

## Endpoints para la IA
```
# Shell ROOT (la IA puede ejecutar cualquier comando)
POST /api/device/shell  {"cmd":"pm list packages -3; getprop ro.product.model"}

# Abrir apps
POST /api/device/launch {"pkg":"com.bcp.bo.wallet"}

# Input/tap/key (ROOT fallback)
POST /api/device/tap    {"x":540,"y":1200}
POST /api/device/input  {"text":"hola mundo"}
POST /api/device/key    {"code":4}  # 3=home 4=back 187=recents

# A11y (requiere app, control fino)
POST /api/device/a11y   {"action":"clickText","text":"Ingresar"}
POST /api/device/a11y   {"action":"dump"}
POST /api/device/a11y   {"action":"setText","viewId":"com.app:id/input","text":"hola"}
```
La IA puede encadenar: `dump` → parse → `clickText` → `input` → `shell`. Todos esos endpoints exigen `X-Aegis-Token`.

## Boot autostart (opcional)
Instala el hook de Magisk (nombre de destino ya instalado en el dispositivo — no renombrar):
```sh
nsenter -t 1 -m -- cp /sdcard/projects/Aegis/backend/service.d-99-opencode-hub.sh /data/adb/service.d/99-opencode-hub.sh
nsenter -t 1 -m -- chmod 755 /data/adb/service.d/99-opencode-hub.sh
```
(el script espera ~20-30 s a que `/sdcard` esté montado y lanza `keepalive.sh`).

## Estructura
- `../backend/` — hub Node sin deps (siempre funciona, base para WebView de la app)
- `app/src/main/kotlin/com/aegis/hub/` — `MainActivity.kt`, `CompanionService.kt`, `OpencodeAccessibilityService.kt`, `RootShell.kt`
- `install-su.sh` — instalador root (6 pasos)
