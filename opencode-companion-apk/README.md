# Opencode Companion APK — POCO F3 crDroid 15 (ROOT)

Hub web + voz + control total del dispositivo para opencode.

## Qué hace
- **Hub web (8765)**: proxy a `opencode serve :4096` + `/api/device/*`. No necesita APK.
- **APK Companion (8766)**: `AccessibilityService` (click por texto/id, tap, back/home, dump) + `SpeechRecognizer`/`TTS` nativo (sin restricción HTTPS) + `su` shell.

Unificados: el hub reenvía `/api/device/a11y` → `127.0.0.1:8766/a11y` si el APK está instalado; si no, hace fallback a `uiautomator dump` + shell.

## Uso inmediato (sin compilar APK) — 1 min
```sh
sh /sdcard/projects/opencode-companion/start-hub.sh
# abre en Chrome del POCO F3:
# http://127.0.0.1:8765
```
En el hub: elige proyecto → nueva sesión → dicta con 🎙️ → auto-voz lee la respuesta. Control dispositivo: shell, abrir app, tap, key.

Limitación web STT: Chrome bloquea micrófono en `http://192.168.x.x`. Usa `https` o el APK (STT nativo).

## Compilar APK
Requiere JDK 17 + Android SDK.

Local (si tienes SDK):
```sh
cd /sdcard/projects/opencode-companion-apk
./gradlew assembleDebug   # o assembleRelease
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

CI (recomendado — este repo ya está listo para GitHub Actions si lo pusheas):
`gradle-wrapper.jar` se genera en CI; no necesitas commit binarios.

Tras instalar: abre **Opencode Companion** → `Activar accesibilidad` → habilita el servicio.

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

# A11y (requiere APK, control fino)
POST /api/device/a11y   {"action":"clickText","text":"Ingresar"}
POST /api/device/a11y   {"action":"dump"}
POST /api/device/a11y   {"action":"setText","viewId":"com.app:id/input","text":"hola"}
```
La IA puede encadenar: `dump` → parse → `clickText` → `input` → `shell`.

## Boot autostart (opcional)
Crea `/data/adb/service.d/99-opencode-hub.sh` (Magisk):
```sh
#!/system/bin/sh
# espera a que sdcard esté montada
sleep 25
sh /sdcard/projects/opencode-companion/start-hub.sh &
```

## Estructura
- `../opencode-companion/` — hub Node sin deps (siempre funciona, base para WebView del APK)
- `app/src/main/kotlin/com/opencode/companion/` — `MainActivity.kt`, `CompanionService.kt`, `OpencodeAccessibilityService.kt`, `RootShell.kt`
