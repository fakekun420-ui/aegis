# PLAN SIGUIENTE — Aegis (tras F0–F14 en `main`)

> **Para el agente de código (opencode).** Continúa `PLAN-ESTABILIZACION-AEGIS.md` y
> `PLAN-CONTINUACION-AEGIS.md` (siguen vigentes: §0 de reglas, H-xx, V-xx).
> Este archivo se basa en una **lectura estática de `main` en `d966ab1` (2026-10-09)**: no se
> compiló ni se ejecutó nada, y el clon era superficial (sin historial). Todo `VERIFICAR` es una
> hipótesis a comprobar antes de actuar.
>
> **No preguntes al usuario cosas sueltas.** Si necesitas una autorización (pantalla, reiniciar
> servidor), sigue §2: **una sola petición agrupada**, y mientras tanto avanza con lo que no la
> necesite.

---

## 0. Estado de partida (lo que consta en el repo)

- `main` contiene F0–F8, F10, C1/C3/C4, F6 y F11–F14 (merge de integración `dac4956`, 2026-10-08;
  luego F11 chats globales en `/sdcard/projects`, F12 buscador de modelos, F13 catálogo completo +
  envío honesto, F14 scroll). `versionName = 1.2.0`, 297 `@Test`, 0 `@Ignore`.
- Verificado en el móvil (`docs/qa/VERIFICACION-20261009.md`): V-01…V-07, V-08 (corte real), V-09
  (5 ciclos + reinicio físico), V-10, V-11 (225→170 MB en 30 min) → **PASS**.
- Cumple: E2, E7 (2 `catch` vacíos, ambos con marcador `GUARD-SILENCIO-OK`), E9 (el grep de `hub`
  solo devuelve el nombre de paquete `com.aegis.hub`), `createSession(` de producción solo en
  `SesionesRepo` + fachada + smoke.
- **No medido todavía:** E3 (peticiones al abrir chat), E4 (reposo), E8 (1 `su` en Skills),
  `GET api/model` por 10 min. Las celdas de `docs/PERF-BASELINE.md` siguen en "no medido".
- **Sin decidir:** `SYNC_POR_EVENTOS` (`const val` en `ChatViewModel.kt`, hoy `false`), y por tanto
  el bucle antiguo de poll **sigue vivo junto a `ChatSync`** (dos caminos: viola P1/P4).
- **Sin hacer:** F9 (`ChatScreen` 2058, `RutaNativa` 1546, `ChatViewModel` 1445, `MarkdownText`
  1024, `SetupWizardScreen` 865 líneas).

### Discrepancias detectadas (corregir en G0)

1. **CHANGELOG desordenado:** las entradas F11/F12/F13/F14 quedaron pegadas dentro de una sección
   antigua ("### Added" cerca de la línea 237, junto a notas del Hub/backend) y no bajo `1.2.0`
   ni una versión nueva.
2. **Hook de arranque no versionado:** `VERIFICACION-20261009` dice que tras el reinicio el servidor
   subió "por el hook de arranque" con el entorno fijado (PATH de Ubuntu + `SHELL=bash`), pero
   `git ls-files` no contiene ningún `service.d`/`99-aegis*`. El 2026-10-07 se registró que **no
   había hook**; ahora sí lo hay y nadie sabe en el repo qué contiene.
3. **Archivos temporales ajenos al producto** en la raíz de `/sdcard/projects`
   (`_tmp_D2_base.md`, `_tmp_D2_nuevo.md`, `_tmp_filas.md`), reportados y sin limpiar.
4. **`ChatScreen` creció** (2029 → 2058) con F12: la meta de F9 se aleja si no se parte pronto.
5. **PERF-BASELINE** dice "≤ 2 archivos > 700 líneas" como meta y hoy hay 5 (ver arriba).

---

## 1. Orden de trabajo

`G0 higiene` → `G1 instrumentación pasiva` → `G2 hook` → `G3 decisión SYNC` → `G4 F9` → `G5 cierre`.
G0–G2 no necesitan pantalla ni reinicios. G3 y G4 dependen de evidencia (§2 y §6).

---

## 2. Autorizaciones: una sola petición agrupada

Muchas verificaciones necesitan pantalla o tocar el servidor, y esas autorizaciones son del usuario.
Para no interrumpirlo con preguntas sueltas:

1. Al terminar G0–G2, crea **`docs/qa/AUTORIZACIONES-SOLICITADAS.md`** con una tabla:
   `Id | Qué necesito | Para qué métrica | Duración estimada | Riesgo | Qué NO tocaré`.
   Ítems posibles (solo los que de verdad hagan falta):
   - **A-1 Pantalla ~15 min:** abrir 3 chats distintos, abrir Skills, dejar un chat abierto 60 s en
     reposo, enviar 1 mensaje corto → cierra E3, E4, E8 y `GET api/model`.
   - **A-2 Pantalla ~20 min con el flag de pruebas en `true`** (extra de intent solo en debug):
     turno largo con `bash` y corte/vuelta del servidor → decide G3.
   - **A-3 Reiniciar el servidor:** solo si A-2 lo exige para V-08 con SSE; si no, no se pide.
2. En el informe final deja **una frase** por ítem. El usuario responde una vez ("A-1 sí, A-2 sí")
   y tú continúas. Sin esa respuesta, **no** toques pantalla ni servidor y sigue con lo demás.
3. Todo lo que se haga con esa autorización queda trazado (comandos, hora, resultado) en
   `docs/qa/VERIFICACION-<fecha>.md`. Al terminar: dejar el servidor en el estado en que estaba
   (1 listener en `:49374`), el daemon/latido de pruebas apagado, y `app/state` con los mismos hashes.

---

## 3. Tareas

### G0 — Higiene documental y de repo (sin dispositivo)

**T-G0.1 — Reubicar el CHANGELOG.** Mover las entradas F11–F14 a una sección nueva
`## [1.2.1] - <fecha>` (tipos `Añadido/Cambiado/Arreglado` correctos), y dejar `1.2.0` solo con lo
suyo. Subir `versionName` a `1.2.1` en `app/app/build.gradle.kts` (los cambios F11–F14 son
posteriores a 1.2.0). Comprobar que ningún test compara la versión.

**T-G0.2 — Archivos temporales.** Los 3 `_tmp_*.md` de `/sdcard/projects`: confirmar por las trazas
citadas (`/root/artemis-google/traces/`) que los creó el arnés de pruebas; si es así, **moverlos a
`/sdcard/projects/_to_delete/`** (no borrar) y anotarlo. Si no hay certeza de origen, no tocarlos y
registrarlo. Verificar que `_to_delete/` queda fuera de la lista de proyectos de la app.

**T-G0.3 — PERF-BASELINE honesto.** Ajustar las metas a la realidad medida (5 archivos > 700
líneas hoy) y conservar la meta F9 como objetivo, no como hecho. No rellenar celdas sin medir.

**T-G0.4 — `.ponytail.md` y `AGENTS.md`.** Anotar las decisiones tomadas desde F6: guarda con
corchete, cerrojo `mkdir`, `aegis-serve.sh` como asset, directorio de chats globales
`/sdcard/projects`, catálogo sin recorte de proveedor, y el criterio contar **listeners**, no PIDs.

**Aceptación:** CI verde, mismos tests (297), `python3 tools/check_hilo_principal.py` y
`check_composable.py` en verde.

### G1 — Instrumentación pasiva para medir sin tocar la pantalla

**Problema:** E3/E4/E8 siguen sin medirse porque exigen interacción. Si la app deja marcas en
logcat, bastará con **leer el log mientras el usuario usa la app con normalidad**.

**T-G1.1 — Marcas de ciclo de vida (solo `BuildConfig.DEBUG`).** En `AegisTrace` (o junto a
`TraceoPeticiones`) añadir líneas `AegisMark`:
- `chat.abrir:inicio <sid>` al empezar `ChatViewModel.load` y `chat.abrir:fin <sid> peticiones=<n>`
  cuando termina de pintar la cola (n = contador de `TraceoPeticiones` entre ambas marcas).
- `skills.abrir:inicio/fin su=<n>` contando ejecuciones de `Raiz` en el intervalo.
- `reposo:60s peticiones=<n>` cada 60 s mientras un chat esté abierto y no haya turno ocupado.
- `chat.enviar:inicio/fin peticiones=<n>`.
Tests JVM de la normalización y del conteo por ventana (sin Android).

**T-G1.2 — Extractor.** `tools/extraer_metricas.py` que lee un `logcat -d` guardado y emite una
tabla Markdown lista para pegar en `PERF-BASELINE.md`. Con test sobre un log de ejemplo versionado.

**T-G1.3 — Recolección pasiva.** Con el APK debug nuevo instalado (el usuario ya usa la app), leer
`logcat -s AegisMark AegisTrace` **sin interactuar** y volcar lo capturado a
`docs/qa/METRICAS-<fecha>.log`. Rellenar solo las celdas con evidencia. Lo que no aparezca pasa a
la petición **A-1**.

**Aceptación:** al menos E3 y `GET api/model` medidos pasivamente, o A-1 solicitada con motivo.

### G2 — Versionar el hook de arranque (solo lectura)

1. Leer el hook real del dispositivo (`nsenter -t 1 -m -- cat /data/adb/service.d/<archivo>`).
   **No modificarlo ni sustituirlo.**
2. Antes de commitear, `grep -niE "pass|token|secret|key|auth|basic|bearer"`; reemplazar valores
   por `<REDACTADO>` y anotarlo.
3. Guardarlo como `app/scripts/service.d/99-aegis-opencode.sh.referencia` y documentar en
   `docs/ARCHITECTURE.md`: quién arranca el servidor (hook en boot, app vía `aegis-serve.sh`,
   supervisor de Termux), el entorno que fija cada uno (PATH Ubuntu, `SHELL=bash`, `CHROOT_PATH`) y
   cómo se evita el doble servidor (guarda con corchete + cerrojo `mkdir` 60 s).
4. **VERIFICAR** que el hook y `aegis-serve.sh` fijan **el mismo entorno** (F11 corrigió un caso en
   que el asset instalado carecía de `CHROOT_PATH`). Si difieren, registrar la diferencia como
   hallazgo; **no** cambiar el hook: proponer en el estado el diff exacto.
5. Añadir un test o chequeo de CI que compare el `sha256` de `aegis-serve.sh` con la constante que
   usa `OpenCodeLauncher` para detectar despliegues desfasados (ya se desplegó por base64: que un
   cambio del script sin actualizar el despliegue falle en CI).

**Aceptación:** hook de referencia en el repo, hallazgos de entorno anotados, CI verde.

### G3 — Decidir `SYNC_POR_EVENTOS` con evidencia (un solo camino)

**Objetivo:** que quede **un** mecanismo de sincronización (principio P1/P4), no dos.

**Estado:** C2 se cerró "por diseño" (MockWebServer no entrega SSE en CI); la interop real SSE solo
puede verse en el dispositivo. Hipótesis sin confirmar: `runTest` usa tiempo virtual y dispara
timeouts antes de la E/S real. **Antes de gastar CI**, probar localmente (JVM, 1 test) la variante
con `runBlocking` y `withTimeout` real; si resuelve, activar los tests de transporte; si no, dejar
las pruebas de transporte documentadas como limitación (máximo 2 ciclos de CI).

**Experimento en dispositivo (requiere A-2):**
1. Con el extra de intent debug `sync_eventos=true`, repetir V-06 (turno largo con `bash`) y V-08
   (corte/vuelta del servidor), y medir E4 con las marcas de G1.
2. Registrar lado a lado `false` vs `true`: peticiones/min en reposo, latencia hasta ver el texto,
   mensajes duplicados (debe ser 0), divisor "✓ respuesta final" (1 sola vez), notificación de fin
   (1 sola vez), recuperación tras corte sin reabrir.

**Regla de decisión (aplícala tú, sin preguntar):**
- **Activar `true`** solo si, con `true`: 0 duplicados, divisor y notificación correctos, recuperación
  automática, **y** E4 ≤ 1 petición/15 s, **y** V-06/V-08 sin fallo, **y** hay ≥ 72 h de `main`
  estable. Entonces: rama aparte, cambiar el valor, etiqueta de reversión, y **en un commit
  posterior** retirar el bucle antiguo (`startViewRefresh`, `pollingJob`, flags cruzados) dejando
  `SyncPorPoll` solo como respaldo cuando el SSE esté caído (ya previsto en T-F5.2).
- **Mantener `false`** si cualquier criterio falla o no hay evidencia. En ese caso **no dejes código
  muerto indefinidamente**: abrir una tarea explícita en el estado para decidir, con fecha, entre
  retirar `ChatSync`/`EventosServidor` o reintentar tras corregir la causa.
- Nunca cambiar el valor de release sin la evidencia completa de arriba.

### G4 — F9: partir los objetos dios (solo tras G3 y 72 h de estabilidad)

**Condición de entrada (comprueba y registra):**
1. `git log` muestra que `dac4956` (o la fusión más reciente de integración) tiene ≥ 72 h.
   El merge fue el 2026-10-08, así que la fecha mínima es **2026-10-11** (verifica la hora exacta).
2. Evidencia de estabilidad desde esa fusión: sin ANR/tombstones de `com.aegis.hub`, `meminfo` sin
   crecimiento sostenido (ya hay V-11 PASS), sin FALLÓ en `VERIFICACION-*`.
3. G3 resuelto (para no extraer `ChatViewModel` con dos caminos dentro).
Si falta algo: **no avances**, deja "F9 aplazada: <motivo>" y termina.

**Reglas:** una extracción por commit; el archivo original conserva la misma API hasta el último
paso (fachada); CI verde entre extracciones; `check_composable.py` tras cada extracción de UI;
los tests existentes **no se editan** salvo nombres de tipo/paquete (`git mv`).

**Orden recomendado (de menos a más riesgo y de mayor a menor valor):**
1. **`RutaNativa` (1546)** → repos por dominio en `data/repo/`: `ProyectosRepo`, `SkillsRepo`,
   `SistemaRepo` (health/bootstrap/final-check/smoke/auth-guide), `InteraccionRepo` (formularios y
   permisos), `MensajesRepo` (`getMessages*`, `getPart`, `sendMessage`). Los de F2/F3/F4
   (`SesionesRepo`, `SesionConfigRepo`, `CatalogoRepo`) ya existen. `RutaNativa` queda como fachada
   delgada; retirarla solo cuando ningún ViewModel la nombre.
2. **`ChatViewModel` (1445)** → `EnvioMensajes` (cola, reintentos, adjuntos, `sendWithFiles`),
   `TurnoTracker` (`turnIsReallyFinished`, `announceFinishedTurnIfAny`, `TurnNotifier`),
   `ConfigSesionUi`. Mantener los nombres de estado expuestos hasta el último paso.
3. **`ChatScreen` (2058)** → `ui/chat/`: `ChatTopBar`, `ListaMensajes` (claves **nunca**
   `hashCode()`), `ItemMensaje`, `Compositor`, `HojaModelos` (con el buscador F12), `HojaAgentes`,
   `TarjetaFormulario`, `TarjetaPermiso`, `BotonVolverAlFinal`. Pasar a cada uno solo el estado que
   usa (menos recomposiciones).
4. `MarkdownText` y `SetupWizardScreen`: opcionales.

**Aceptación:** ningún archivo de `ui/`+`data/` > 700 líneas (meta), tests iguales o más, V-01…V-07
sin regresión **medida** (si requiere pantalla, entra en la petición A-1/A-2, no se saltea).

### G5 — Cierre de cada ejecución

1. `docs/PLAN-ESTABILIZACION-ESTADO.md`: añade el bloque "Ejecución <fecha>" con G0…G4 (estado,
   commit, run de CI, resultado) y las decisiones aplicadas por regla.
2. `docs/PERF-BASELINE.md`: solo cifras medidas; el resto "no medido: <motivo>".
3. `CHANGELOG.md`, `.ponytail.md`, `graphify update .` si funciona (si falla por FUSE, registrarlo).
4. **Informe final de ≤ 30 líneas:**

```
RESUMEN
Bloques: G0 ✅/⚠️/⛔ · G1 … · G2 … · G3 … · G4 …
CI: <run> · Tests: N (antes 297) · versionName: x.y.z
Métricas nuevas: E3=… E4=… E8=… (o "no medido: motivo")
Decisión SYNC: true | false (regla aplicada: …)
Autorizaciones solicitadas (docs/qa/AUTORIZACIONES-SOLICITADAS.md): A-1 …, A-2 …
Hallazgos nuevos: …
Próxima ejecución: condición esperada (p. ej. "≥ 2026-10-11 + respuesta a A-1/A-2")
```
