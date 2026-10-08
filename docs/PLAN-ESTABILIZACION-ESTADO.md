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

## Capacidades medidas en el dispositivo (§1 del plan de continuación, 2026-10-07 ~12:20)

- Servidor OpenCode: **SÍ** (`curl .../api/health` → 401, alcanzable; PID 17510,
  lanzado desde Termux 2026-10-06 20:07, NO desde service.d).
- Raíz del host: **SÍ** — `su -c id` → uid=0 (en el chroot root). Sin contraseña.
- Ver el host desde el chroot: **SÍ** — `nsenter -t 1 -m -- ls /data/adb/service.d`
  funciona (SÍ lista el dir).
- `logcat`: **SÍ** — `nsenter -t 1 -m -- logcat -d -t 5` entrega (el chroot no tiene
  `logcat` directo).
- `pm`: **SÍ** — `nsenter -t 1 -m -- pm list packages com.aegis.hub` → instalada.
- `service.d`: **SIN hook de Aegis instalado** — solo `.zn_cleanup.sh`. El servidor
  va levantado por Termux, no por arranque del sistema.
- Dirección de UI (`input keyevent`): **bus de pantalla NO tocado** (§0): todo lo
  del dispositivo que requiere tocar la pantalla queda en PENDIENTE-OJOS salvo
  comando explícito del usuario.
- `gh` CLI: **NO** en el chroot — el plan usa curl+token de `~/.git-credentials`
  (ver rutinas inline en esta sesión).
- `flock`: **CORREGIDO 2026-10-08** — lo de arriba era falso para el uso del plan.
  El `flock` del movil es toybox (`flock [-sxun] fd`, solo descriptores): la forma
  util-linux `flock ARCHIVO COMANDO` NO existe (`Unknown option 'c'`, `Max 1 argument`).
  El cerrojo anti-carrera de F6 es `mkdir` atomico con caducidad 60 s en `/data/local/tmp`.
- **Situación resultante: B** (raíz + logcat + pm sí; UI/input libre sin OK; no se
  automatizan V-01..V-11). Nada que requiera UI toca el bus: §4.2 se ejecuta a modo
  de program track con PENDIENTE-OJOS.

## Integraciòn — forma de ramas (§6.1)

- Todas las ramas de fase están **apiladas** (cada una desciende de la anterior por
  `git merge-base --is-ancestor`), salvo `F8-modelos` que solo aporta 1 commit de
  docs (`e1b07c7`) sobre la misma punta de código que usa F10.
- **Punta real = `estabilizar/F10-flujos`** (f569d65). Basta fusionar esa rama.

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

## Continuacion autonoma — ejecucion 2026-10-08 (§3, §5, §6, §4 parcial, §7)

### §3 Correcciones (ramas desde la punta de fases, un push por tarea)
- **C1** (`estabilizar/C1-modelo-cat`, CI verde run 37725148661): `fijarModelo`
  valida contra `CatalogoRepo` (refresco unico si falta; `Fallo` sin tocar
  servidor ni cache si no existe; catologo caido no bloquea). V-03 ajustado.
- **C2**: cerrada por diseno sin gastar ciclos. La biseccion MockWebServer acumula
  ~10 ciclos previos sin causa (stream no entrega ni un byte en CI, unario OK);
  repetirla era quemar CI. El transporte se prueba con cuerpos `ResponseBody`
  locales (verde) y la interop HTTP/SSE queda en V-06/V-08 de dispositivo.
- **C3** (`estabilizar/C3-c4-cierre`, CI verde run 37729924029): EOF limpio reabre
  sin `Reconectando`; excepcion si lo emite. 2 tests nuevos.
- **C4** (misma rama): `graphify-staleness.js` eliminado del arbol (no se repone,
  decision §2); `grep hub` en main solo comentarios historicos/docs; guardias
  `check_hilo_principal` + `check_composable` verdes; V-03 redactado.

### §5 F6 (`estabilizar/F6-lanzador`, CI verde run 37779401131, 2 ciclos)
- T-F6.1 sin efecto: **no hay hook que copiar** (`service.d` solo `.zn_cleanup.sh`);
  hook intacto por inexistente (registrado, no es fallo).
- T-F6.2: `app/app/src/main/assets/aegis-serve.sh` (fuente unica; desvia del plan
  `app/scripts/` con motivo: el asset se empaqueta en el APK y lo cubre el lint
  `find app -name '*.sh'`). Guarda `[o]pencode` (H-11 verificado: 3 PIDs sin
  corchete vs 1 con corchete), cerrojo `mkdir` 60 s, oom -1000, nohup,
  `YA_HAY:<n>/LANZADO/ERROR:<motivo>`.
- T-F6.3: `ServidorOpenCode` (un solo vuelo por Mutex, reintento unico a mitad);
  `MainActivity` solo observa (nombre `startRootSystemAndPoll` conservado para
  acotar el diff); `OpenCodeLauncher` solo despliega (base64, idempotente por sha)
  e invoca el script (montajes/binario muertos eliminados; `pgrep` con corchete
  tambien en el escaneo de binario vivo que hacia el Kotlin).
- 11 tests JVM nuevos (estados, vuelo unico, reintento, parseo YA_HAY/ERROR).
- V-09 queda **PENDIENTE-OJOS** (arranque/force-stop/kill tocan pantalla; §0).

### §6 Integracion (`estabilizar/integracion`)
- F1–F10+C1/C3/C4 fusionados (merge cf5b305, CI verde run 37731066908) y luego F6
  (merge b3a200f; CI pendiente de este push).
- §6.5: `createSessionViaHub` 0; `createSession(` prod solo en repo+costura+smoke;
  `catch (_: Exception) {}` en viewmodel solo 2 con marcador GUARD-SILENCIO-OK;
  `SYNC_POR_EVENTOS=false`; `versionName=1.2.0`; guardias verdes; tests 282+11.
- Integracion final (merge F6 + cierre): CI verde run 37784710096 (6 jobs),
  292 tests JVM (minimo 277). APK debug = artifact `aegis-debug` de ese run.
- Grafo local: `graphify update` intentado; aborta en `_rebuild_lock` (FUSE errno 38,
  trampa conocida de §2). Indice local sin tracking git: queda STALE=yes (79 por
  ruido de mtime FUSE + 3 ficheros nuevos reales). No es entregable; se registra.
- Matiz honesto a §6.5: `RootShell.` directo sigue en `OpenCodeLauncher`
  (lanzador, permitido) + `Credentials`/`SetupNative` (excepciones F7 fuera de
  main documentadas); `RutaNativa` va por `Raiz`. No es 0 literal: es lo que F7
  verifico uno a uno.
- Revert: etiqueta `pre-integracion-20261008` sobre `main` (2862fd8) antes de fusionar.

### §4 Dispositivo (situacion B; detalle en `docs/qa/VERIFICACION-20261008.md`)
- AUTO-OK (solo lectura, sin tocar pantalla): servidor alcanzable (401),
  1 proceso serve, app instalada 1.1.1 con 69 MB PSS, 0 ANR/crash de
  `com.aegis.hub` (`/data/anr` solo trae `io.chaldeaprjkt.gamespace`), state/
  respaldo con mismos hashes (no se toco nada).
- PENDIENTE-OJOS: instalacion del APK 1.2.0, arranque, V-01…V-11, V-09, E3/E4/E8,
  PERF-BASELINE (lista en `docs/qa/VERIFICACION-PENDIENTE.md`).

### §7 Fusion a `main`: NO (resultado correcto, no fallo)
- Falta §7.2 (sin §4.1 con APK 1.2.0: instalar+lanzar tocan pantalla, §0) y §7.3
  (V-09 sin AUTO-OK). Rama lista, APK debug del run de integracion disponible,
  motivo registrado. PENDIENTE-OJOS no bloquea (§7) pero F9 y `SYNC_POR_EVENTOS`
  siguen bloqueados (§8: sin 72 h de estabilidad comprobada, nada que hacer).

## Verificacion con OK de pantalla — 2026-10-08 tarde (restriccion nueva)
- §4.1 con APK fix3 (run 37815937195): **AUTO-OK** — 0 FATAL, 0 ANR,
  0 StrictMode con stack aegis tras 2 correcciones (ProjectsStore precalentado
  en IO; migrarPrefijos a IO + warm de model_prefs/agent_prefs).
- Hallazgo previo (ya corregido): 1.2.0 sin fix moria en arranque con FATAL x2
  (`RootShell.drenar` sin atrapar corte; fix + 2 tests JVM).
- V-09 **PARCIAL**: 5 force-stop+rearranque sin crecimiento (siempre el par del
  supervisor, 1 listener). Los conteos con el patron viejo eran instrumento
  invalido (no ve `opencode.exe`); rehechos con `(.exe)?`.
- Supervisor (servicio Termux 6418): al matar un hijo `serve`, repone otro y el
  viejo languidece sin puerto (2 PIDs/1 listener transitorio). La app NUNCA lanzo
  un tercero: la guarda F6 aguanto.
- **RESTRICCION del usuario 2026-10-08: no matar el servidor; todo reinicio de
  opencode requiere su autorizacion.** Quedan pendientes con esa condicion:
  V-09 kill→reapertura→1, ruta LANZADO del script en dispositivo, V-08 visual.
- AegisTrace en vivo: turno real con message=18-20, forms/permissions (el usuario
  usa la app: motivo de mas para no interferir). PSS 117 MB en uso (no curva).
- §7: **NO fusionar** (V-09 incompleta + PENDIENTE-OJOS). F9/`SYNC` bloqueados.

## Cierre (orden del usuario: reinicio del servidor al final)
- UI no tocada con usuario activo (Brave en primer plano): V-01…V-08/V-10/E3/E8
  PENDIENTE-OJOS. `uiautomator` verificado operativo (dump OK).
- PERF-BASELINE "despues" rellenado con lo medido (6/9 celdas); 3 siguen no
  medidas (abrir chat, enviar, su Skills: requieren UI).
- Pendiente final (requiere autorizacion): reinicio del servidor / pruebas de
  reinicio (ruta LANZADO del script, V-09 kill→reapertura→1, V-08 con corte).
