# ESTADO DEL PLAN DE ESTABILIZACIÓN — Aegis

Plan: `docs/PLAN-ESTABILIZACION-AEGIS.md`. Este archivo es el tablero: una fila por
fase, con el commit, la CI y el checklist manual. Se actualiza al cerrar cada fase.

> Base del plan: commit `bd515c5` (2026-10-06). Rama de trabajo actual creada sobre
> `e89a274` (2 commits por delante de la base; solo cambios de modelo por defecto,
> sin tocar las zonas del plan).

| Fase | Estado | Commit | CI | Checklist manual | Notas | Decisiones pendientes |
|---|---|---|---|---|---|---|
| F0 Base y medición | 🟢 CI verde; pendiente móvil | `4d21768` + `ea0181e` en `main` | ✅ 7 jobs (6 success + instrumented skipped, es manual) en runs `37555482229` y `37555520373` | pendiente: AegisTrace en logcat + cifras PERF-BASELINE (lo hace Leonardo) | T-F0.5 (cifras en el móvil) pendiente de medición manual. AVISO: el merge arrastró el borrado ya staged de `.opencode/plugins/graphify-staleness.js` (preexistente, no es del plan §8 — decidir si se restaura) | T-F0.6: alinear a `1.1.2` (aplicado) |
| F1 Limpieza de legado | 🟢 CI verde en rama; pendiente móvil + merge | `estabilizar/F1-limpieza` (9 commits: T-F1.1×3, gitleaks, T-F1.2×2, T-F1.3×2, T-F1.4, T-F1.5) | ✅ run `37560627846` (6 jobs: 5 success + instrumented skipped) | pendiente V-01, V-02, V-07 (APK debug en `_tmp/f1-apk/app-debug.apk`) | 11/11 métodos muertos fuera; provider fuera; vocabulario Hub fuera; ErroresRed con fixtures reales; docs a la realidad; CI sin backend-checks. AVISO: cambio ajeno sin commitear en `ChatViewModel.kt` (AGENTE_POR_DEFECTO orchestrator→build, 01:08) — no es mío, no lo toco | Antigravity descartado (aplicado) |
| F2 Crear sesión | 🟢 CI verde en rama; pendiente móvil + merge | `estabilizar/F2-sesiones` (5 commits: T-F2.1, T-F2.2 costura, T-F2.2/2.3/2.4 VMs, T-F2.2 smoke, fix import) | ✅ run `37567570740` (5 success + instrumented skipped; antes 1 rojo por import mal puesto, ya fixed) | pendiente V-04, V-05 | SesionesRepo único camino; faceta con error; smoke borra su sesión | — |
| F3 Modelo/agente | 🟢 CI verde en rama; pendiente móvil + merge | `estabilizar/F3-config` (2 commits: T-F3.1 repo, T-F3.2/3.3 VM) | ✅ run `37572264935` (5 success + instrumented skipped) | pendiente V-03 (10 cambios rápidos) | Servidor = verdad; guardia anti-carrera + rollback; skip 60 s | OMITIR_FIJADO_REDUNDANTE=true (aplicado) |
| F4 Menos peticiones | 🟢 CI verde en rama; pendiente móvil + merge | `estabilizar/F4-peticiones` (3 commits: T-F4.1 catálogo, fixup oc, import) | ✅ run `37575719710` (5 success + instrumented skipped; antes 1 rojo por import, fixed) | pendiente E3 vs PERF-BASELINE | Catálogo TTL 5 min; abrir = leer+cola+historial; poll por tail | — |
| F5 Un canal sync | ⬜ no empezada | — | — | V-06, V-08 | Flag `SYNC_POR_EVENTOS=false` hasta 3 días en verde | — |
| F6 Arranque único | ⬜ no empezada | — | — | V-09 | — | — |
| F7 su/hilos/regex | 🟢 CI verde en rama; pendiente móvil + merge | `estabilizar/F7-suhilos` (7 commits: Raiz, skills×2, TTL, regex, guardia, fix) | ✅ run `37581380511` (5 success + instrumented skipped; 2 rojos intermedios por imports/nulabilidad, fixed) | pendiente E8 (1 su en Skills) + E1 (1 h sin avisos StrictMode) | Lotes 1-su, TTL, regex a consts, guard en CI | — |
| F8 Modelos datos | 🟢 CI verde en rama; pendiente móvil + merge | `estabilizar/F8-modelos` (6 commits: split, 3 renombres, ModeloRef, ModelsHub) | ✅ run `37583145506` (5 success + instrumented skipped; split rehecho por declaraciones) | pendiente V-01…V-07 | Mismo comportamiento | pantallas se mantienen (aplicado) |
| F9 Partir dioses | ⬜ bloqueada (entrada: F1–F7 estables 3 días en móvil) | — | — | V-01…V-09 | — | — |
| F10 Tests/CI/docs | 🟢 CI verde en rama; pendiente móvil + merge | `estabilizar/F10-flujos` (docs 1.2.0 + QA + board) | ✅ run `37605294187` (5 success + instrumented skipped; 277 tests; SSE va con cuerpos locales tras ~10 ciclos) | — | FakeOpenCode + 6 flujos + costura inyectable + CHANGELOG/AUDITORIA/QA | — |

## Bitácora F0 (2026-10-07)

- T-F0.1: `python3 tools/check_composable.py` → verde local. CI pendiente del push
  (sin toolchain Android en el host; `gh` no disponible en el chroot para leer runs).
- T-F0.2: este archivo.
- T-F0.3: `data/TraceoPeticiones.kt` + wiring en `OpenCodeApi.createOkHttpClient`
  (solo `BuildConfig.DEBUG`) + `TraceoPeticionesTest` (7 tests JVM puros).
- T-F0.4: StrictMode `penaltyLog` en `MainActivity.onCreate` (debug) + chequeo de
  hilo principal en `RootShell.exec` (debug; inerte en tests JVM por guarda `null`).
- T-F0.5: `docs/PERF-BASELINE.md` creado como plantilla; cifras "antes" pendientes
  de medición en el móvil (requiere APK debug + interacción: lo hace Leonardo).
- T-F0.6: `versionName` `1.1.1` → `1.1.2` (decisión por defecto del plan).
- `buildFeatures.buildConfig = true` explícito (la instrumentación depende de
  `BuildConfig.DEBUG`; no se confía en el defecto de AGP 8.7.3).
