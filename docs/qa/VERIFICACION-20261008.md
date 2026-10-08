# Verificación en dispositivo — 2026-10-08 (integración 1.2.0, sin tocar pantalla)

Situación §1: **B** (raíz + logcat + pm SÍ; UI solo con OK explícito → no se toca).
APK 1.2.0 **no instalado** (instalar + `am start` toman la pantalla; §0).

## AUTO-OK (todo por lectura: `nsenter`, `curl`, `pm`, `logcat -d`)
- Servidor OpenCode alcanzable: `curl :49374/api/health` → **401** (vivo, pide auth).
- **1** proceso `opencode ... serve --service` (PID 6481; el `pgrep` sin corchete
  da 3 PIDs por autocontarse, con `[o]` da 1 — H-11 confirmado en vivo).
- App instalada `com.aegis.hub` **1.1.1** (versionCode 314): PSS total 68.914 KB,
  sin crecimiento medible (una sola muestra; no es curva).
- 0 `FATAL EXCEPTION`/`ANR in com.aegis.hub` en logcat; `/data/anr` solo contiene
  un ANR de `io.chaldeaprjkt.gamespace` (2026-10-07 23:48, juego, no Aegis).
- `service.d`: **sin hook de Aegis** (solo `.zn_cleanup.sh`). Nada instalado por boot.
- `app/state/*.json`: hashes idénticos antes/después (solo se copiaron a
  `/data/local/tmp/respaldo-aegis/` como respaldo; no se modificó nada).
- `AegisTrace` vacío en logcat (esperado: la instalada es 1.1.1, anterior a F0).

## PENDIENTE-OJOS → `docs/qa/VERIFICACION-PENDIENTE.md`
Instalación 1.2.0, arranque 20 s, V-01…V-11, V-09 (5 aperturas + kill),
E3/E4/E8 con `AegisTrace`, PERF-BASELINE, V-10 TTS.

## Intento UI 13:50 (sin tomar la pantalla)
- `uiautomator dump` OK al 3er intento (los 2 primeros: `could not get idle
  state`, UI ocupada). Foreground: `com.brave.browser` (el usuario navega).
- Decision: NO se lanzan taps ni `am start` a Aegis con el usuario activo en
  otra app — tomaria el primer plano en mitad de su tarea (§0). V-01…V-08,
  V-10 y E3/E8 quedan PENDIENTE-OJOS (ya listados en VERIFICACION-PENDIENTE).
- V-11 parcial (solo lectura): PSS 117 → 127 → 131 MB en ~20 min con la app en
  segundo plano y sesion con turno vivo (no es curva de reposo ni de uso
  simulado; es uso real).

## Prueba de reinicio 14:07 (autorizada)
- Kill + `am start`: servidor sano en segundos, 1 listener, health 401.
- Deploy del script F6 verificado en dispositivo (sha256 identico al repo).
- La app no lanzo duplicado (YA_HAY aguanto). LANZADO limpio: pendiente
  controlado (supervisor detenido). V-09 lado app: AUTO-OK.
