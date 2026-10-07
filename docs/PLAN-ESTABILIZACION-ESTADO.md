# ESTADO DEL PLAN DE ESTABILIZACIÓN — Aegis

Plan: `docs/PLAN-ESTABILIZACION-AEGIS.md`. Este archivo es el tablero: una fila por
fase, con el commit, la CI y el checklist manual. Se actualiza al cerrar cada fase.

> Base del plan: commit `bd515c5` (2026-10-06). Rama de trabajo actual creada sobre
> `e89a274` (2 commits por delante de la base; solo cambios de modelo por defecto,
> sin tocar las zonas del plan).

| Fase | Estado | Commit | CI | Checklist manual | Notas | Decisiones pendientes |
|---|---|---|---|---|---|---|
| F0 Base y medición | 🟡 fusionada a main, CI corriendo | `4d21768` (merge de `estabilizar/F0-base`, 4 commits) | pendiente (~9 min, ver Actions) | no aplica (F0 no cambia release) | Tests JVM: 220 anotaciones `@Test` en 28 ficheros (el plan esperaba 209; +11 por commits posteriores a la base — confirmar número exacto en CI). T-F0.5 (cifras en el móvil) pendiente de medición manual. AVISO: el merge arrastró el borrado ya staged de `.opencode/plugins/graphify-staleness.js` (preexistente, no es del plan §8 — decidir si se restaura) | T-F0.6: alinear a `1.1.2` (aplicado, valor por defecto del plan) |
| F1 Limpieza de legado | ⬜ no empezada | — | — | V-01, V-02, V-07 | — | Antigravity descartado (por defecto: sí) |
| F2 Crear sesión | ⬜ no empezada | — | — | V-04, V-05 | — | — |
| F3 Modelo/agente | ⬜ no empezada | — | — | V-03 | — | `OMITIR_FIJADO_REDUNDANTE = true` (por defecto: implementar) |
| F4 Menos peticiones | ⬜ no empezada | — | — | E3 vs PERF-BASELINE | — | — |
| F5 Un canal sync | ⬜ no empezada | — | — | V-06, V-08 | Flag `SYNC_POR_EVENTOS=false` hasta 3 días en verde | — |
| F6 Arranque único | ⬜ no empezada | — | — | V-09 | — | — |
| F7 su/hilos/regex | ⬜ no empezada | — | — | E8, E1 | — | — |
| F8 Modelos datos | ⬜ no empezada | — | — | V-01…V-07 | — | Pantallas Workspace/Workflow/ControlCenter/SkillManager: se mantienen (por defecto) |
| F9 Partir dioses | ⬜ no empezada | — | — | V-01…V-09 | Entrada: F1–F7 estables 3 días | — |
| F10 Tests/CI/docs | ⬜ no empezada | — | — | §6 del plan | — | — |

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
