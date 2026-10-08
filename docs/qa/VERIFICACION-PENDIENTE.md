# VERIFICACION-PENDIENTE (ojos humanos, con OK explícito — §0)

APK: artifact `aegis-debug` del run de CI verde de `estabilizar/integracion`
(`versionName=1.2.0`). Instalar desde `/data/local/tmp` tras `pm uninstall`
(previo respaldo de `app/state/`, hashes en `docs/qa/VERIFICACION-20261008.md`).

## Arranque (§4.1)
- `logcat -c`, `am start -n com.aegis.hub/.MainActivity`, esperar 20 s.
- Correcto: sin `FATAL EXCEPTION`, sin `ANR in com.aegis.hub`, sin
  `StrictMode policy violation` con stack en `com.aegis.hub`.

## V-09 — un solo servidor (bloquea fusión a main y F9)
- Tras arranque, tras `am force-stop` + relanzar ×5, tras `kill` del serve +
  reabrir app: `pgrep -f '[o]pencode(.exe)? serve --service' | wc -l` siempre **1**.
- Si alguna da 2 → AUTO-FALLÓ: revertir F6 y registrar.

## V-01…V-08, V-10, V-11 — ver `docs/qa/QA_CHECKLIST.md`
- Lo visual (chips, banners, overlay, TTS) solo se verifica mirando.
- V-06/V-08 con `SYNC_POR_EVENTOS=false` y (vía extra debug `sync_eventos`) con
  `true`; E4 en reposo ≤ 1 petición/15 s en ambos modos. El valor de release
  (`false`) no se cambia pase lo que pase.
- V-04: "+" ×2 → **1** sesión (`GET /api/session`). V-05: `directory` == carpeta.
- E3/E4/E8 con `logcat -s AegisTrace` → rellenar `docs/PERF-BASELINE.md`.

## F9 y `SYNC_POR_EVENTOS=true` (§8)
- Bloqueados hasta: fusión en `main` con ≥ 72 h + evidencia de estabilidad
  (`/data/anr`, tombstones, `meminfo` sin crecimiento, sin FALLÓ) + §4.3 con
  flag en debug sin AUTO-FALLÓ en V-06/V-08. Sin eso: no avanzar, no es error.
