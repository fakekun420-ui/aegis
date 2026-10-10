# VERIFICACION-20261009 — batería UI con Artemis (Antigravity 3.8-flash-low)

Artemis funcional vía proxy `:8767` (OAuth `leitogat`). Modelo: `openai/gemini-3.8-flash-low`
(fallback 2.5-flash). 2.5-flash fallaba grounding (taps errados + confirmaciones falsas);
con 3.8-low 6/6 tareas PASS a la primera o segunda. Pantalla con OK del usuario.
Daemon parado + latido apagado al cierre. Servidor opencode NO tocado (PID 19890, env
Ubuntu verificado tras reinicio único autorizado del 09-10).

| V | Resultado | Evidencia |
|---|---|---|
| V-01 abrir chat | PASS | `nagent` carga inmediato, sin spinner; chips `Muse Spark 1.3 Free` + `build` |
| V-02 enviar | PASS c/nota | Solo fase `Generando respuesta...` visible; divisor `✓ respuesta final` 1× al final; turno real en servidor (falló `date` con `Executable not found: sh` → causa del fix F11) |
| V-03 10 cambios | PASS | 10/10 aperturas con chips propios, 0 cruces (`nagent`=Spark/build, `Chat 3496`=Bunny/build). Modelo inexistente: no aplicable (picker cerrado, sin tipeo; cubierto por tests C1) |
| V-04 crear global | PASS | Sesión `Chat 3496` (`ses_ee21b654…`) creada con +, mensaje + respuesta verificados en `/api/session` |
| V-05 chat proyecto | PASS | `companion:Carpeta de Prueba` nació en `/sdcard/projects/Carpeta de Prueba`, visible en proyecto, NO en global |
| V-06 turno largo | PASS c/nota | `ls /sdcard/projects` → 23 entradas correctas; `trabajando…` hasta fin real; sin form de permiso (ejecutó directo); 0 notificaciones en foreground |
| V-07 proyectos | PASS | Rename+delete del chat desechable OK (borrado confirmado en servidor); Skills/Workspace/Control Center sin ANR (Workflow no existe como entrada) |
| V-10 TTS+2ºplano | PASS | TTS arrancó (`Dejar de leer en voz alta`); HOME 5 s + recents sin ANR |
| V-08 caído/recupera | BLOQUEADO | Requiere matar/levantar serve: solo con OK explícito |
| V-09 arranque | BLOQUEADO | Requiere reinicio teléfono + kills: solo con OK explícito |
| V-11 memoria | PENDIENTE | PSS 265772 (en turno) → 201188 (idle, ~10 min después). Falta par válido a 30 min |

Hallazgo lateral: 3 `.md` temporales en raíz de `/sdcard/projects`
(`_tmp_D2_base.md`, `_tmp_D2_nuevo.md`, `_tmp_filas.md`) violan §5.1/§6.
Trazas: `/root/artemis-google/traces/` sesiones `139d56a7` (V-04), `5f38dce0`
(V-01/02), `fa1552f6` (V-03), `4a2b1e4f` (V-05/07), `1862f23a` (V-06), `3c5a295c` (V-10).
V-11: PASS (PSS 225094@00:41Z → 170638@01:12Z, 30 min, baja; Java Heap 42460 → 43964 estable).

## F13 (2026-10-09 ~03:00Z): catálogo completo + envío honesto — INSTALADO, pendiente ver en pantalla
- Causa: `getModels("opencode")` escondía 37/79 modelos (medido: 42 opencode + 37
  google, todos enabled). `antigravity-gemini-3.8-flash` (google, enabled) existe
  en servidor pero la app decía "No disponible" y el enviar disparaba un turno
  condenado (el bug reportado con capturas).
- Fix: `loadModels` pide `null` (sin recorte); guardia pura
  `motivoModeloNoDisponible` + tests (frena con motivo, conserva texto en composer
  y reintento; sin catálogo decide el servidor). CI verde (run 37876382923),
  APK reinstalado (respaldo `app/state` en `_tmp/f13-state-bkp-20261009`).
- Falta (requiere OK pantalla): abrir picker y confirmar 79 modelos + chip del
  `antigravity-gemini-3.8-flash` resuelto + enviar frenado con motivo honesto.

## V-08 (2026-10-09 ~04:17Z): PASS con corte REAL
- Durante la prueba hubo un corte genuino del servidor (ventana de reinicio
  04:18, duplicado 32495/19366 saneado a uno solo). Artemis testificó: banner
  "Sin conexión con OpenCode: lo que ves puede no ser el estado real." y
  recuperación sola sin reabrir (banner fuera, chat interactivo).
## F12/F14 (2026-10-09): scroll+buscador PASS
- LazyColumn verificado en dispositivo: scroll llega abajo (Claude, Gemini, GPT,
  Gemma, Nemotron), "bunny" filtra a Space Bunny Free, limpiar restaura todo.
## V-09 (2026-10-09 ~04:21Z): PASS parcial (5× abrir/cerrar)
- 5 ciclos abrir→HOME→reabrir: lista de chats normal siempre, 0 ANR/freeze/crash/spinner.
- Kill→reapertura cubierto por el incidente 04:18 (corte real, servidor único
  restaurado, app recuperada sola). Falta: reinicio físico del teléfono.

## V-09 reinicio (2026-10-09 ~04:52Z): PASS
- Boot 04:51Z → serve arriba 04:52:50 por el hook de arranque (padre init),
  con el env fijado (PATH Ubuntu + SHELL bash en el cmdline del wrapper).
  1 servidor (wrapper+child), API 401 OK, app funcional (usuario escribe desde ella).

## A-1 + E8 + E2E (2026-10-10, pantalla autorizada)
- A-1 PASS: 3 chats abiertos, Skills cargó, 90 s reposo, enviado `medicion A-1` con
  respuesta. Marcas G1 en logcat: E3=3/3/3, enviar=6, reposo 136/38/133 por 60 s,
  api/model 0–1/10 min (logs `METRICAS-20261010-a1.log`).
- E8 PASS: `skills.abrir:fin su=1 peticiones=2..3` (2 muestras).
- E2E chat nuevo PASS (UN toque): `Chat 9802` creado con + (21:26:09Z),
  `dir=/sdcard/projects`, mensaje + respuesta verificados en servidor.

## V-04 "+ Nuevo chat" (2026-10-10, revisión de código — SIN pantalla)
- **La prueba anterior era incompleta y dio un PASS que no era cierto.** Cubría UN toque;
  el enunciado de `QA_CHECKLIST.md` pide además que "+ dos veces seguidas no crea dos", y esa
  parte fallaba. Corregido el PASS anterior: **V-04 PASS solo en la mitad de un toque**.
- Causa: `AppNavHost` pasaba el título `"Chat ${System.currentTimeMillis() % 10000}"`, y ese
  título es la mitad de la clave de idempotencia (`"$title|$proyectoId"`). Cada toque generaba
  una clave distinta → la ventana de 3 s de `SesionesRepo` nunca se cumplía.
- **Reproducido antes de arreglar**, no supuesto: se ejecutó la lógica exacta del repo con tres
  toques a 0/200/400 ms → **3 sesiones** (V-04 exige 1). Con el título constante → 1.
- El título con reloj tampoco compraba nada: el servidor renombra la sesión con el primer
  mensaje, así que "Chat 9802" solo livedía unos segundos en la lista.
- Arreglo: `MainViewModel.nuevoChat()` = camino único con `TITULO_NUEVO_CHAT` constante.
  `MainViewModel` recibe la costura por parámetro (mismos defaults) para poder testearlo.
- Fijado con `FlujoNuevoChatTest` (5 tests) contra el ViewModel real + MockWebServer: un toque
  → 1 POST en `/sdcard/projects` con agente y modelo; tres toques → **1** sesión; tras la ventana
  → 2 sesiones (el arreglo no es "no crear nunca"); títulos iguales al servidor; fallo → null
  con motivo.
- **CI (rama `verificar/E2E-nuevo-chat`, run 38090676087): VERDE, 306 tests, 0 fallos.** Los 5
  tests nuevos aparecen ejecutados en el log. Dos iteraciones previas Fallaron y ambas están
  registradas porque el fallo era real:
  - run 38088984890: 4 de 5 con `UncompletedCoroutinesError` a los 60 s y **sin una sola petición
    okhttp** → el cuelgue era `viewModelScope.launch` (refresh) en el scheduler **virtual** de
    `runTest`, que no corresponde al tiempo real del MockWebServer. Arreglado con `runBlocking` +
    `UnconfinedTestDispatcher` (el patrón que ya pasaban en este repo).
  - run 38089853020: 1 fallo `expected:<2> but was:<1>`, **aserción mía equivocada**: el segundo
    toque estaba a 1.500 ms, DENTRO de la ventana de 3 s, así que se deduplicaba bien y no había
    segundo POST que comparar. El código era correcto; el test no.
- Lección de método: `gh run watch` devolvió exit 0 en el run que estaba FALLIDO. El veredicto se
  lee en `--json conclusion`, nunca en el exit code de `watch`.
- **PENDIENTE-OJOS:** confirmar en el móvil que tras instalar este APK, dos toques seguidos dan
  1 sesión. Requiere OK de pantalla (§0) y el APK de CI (`aegis-debug`, 19.5 MB, run 38090676087).
  Hasta entonces es un PASS de test, no de dispositivo.
- Grafo: `graphify update` falla con `OSError 38` (FUSE), conocido y registrado; el índice queda
  `STALE=yes` con 1 fichero nuevo (`FlujoNuevoChatTest.kt`). No entregable desde este host.
