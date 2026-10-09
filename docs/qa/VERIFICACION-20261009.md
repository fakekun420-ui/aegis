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
