# PLAN DE CONTINUACIÓN AUTÓNOMA — Aegis

> **Para el agente de código (opencode).** Continúa `PLAN-ESTABILIZACION-AEGIS.md`, que **sigue
> vigente** (sus reglas de §0, sus hallazgos H-xx y sus checklists V-01…V-11 se heredan tal cual).
> Este archivo no repite ese plan: cierra lo que quedó pendiente y define cómo avanzar **sin
> preguntar al usuario**.
>
> **Estado de partida (reportado por ti):** F0 fusionada en `main`; F1, F2, F3, F4, F5, F7, F8 y F10
> en ramas con CI verde; 277 tests JVM; F6 y F9 bloqueadas; `SYNC_POR_EVENTOS=false`.

---

## 0. Modo de trabajo

1. **No preguntes al usuario.** Cada decisión de abajo trae su valor por defecto: aplícalo y
   regístralo. Si algo exige a una persona (ver "ojos" en §4), **no lo pidas**: anótalo en
   `docs/qa/VERIFICACION-PENDIENTE.md` y sigue con el resto.
2. **Idempotente y reanudable.** Al empezar, lee `docs/PLAN-ESTABILIZACION-ESTADO.md` y salta lo ya
   hecho. Al terminar cada bloque, actualiza ese archivo (bloque, commit, run de CI, resultado).
3. **Hereda las reglas de `PLAN-ESTABILIZACION-AEGIS.md` §0**, y añade:
   - Si una **misma tarea** falla en CI **3 veces seguidas**: detente en esa tarea, deja la causa
     probable por escrito en el estado, y continúa con la siguiente tarea independiente.
   - **Nunca** `git push --force`, nunca borrar ramas, nunca reescribir historia.
   - **Nunca** tocar firma/keystore, `app/state/*.json` ni `/data/adb/service.d/*` del dispositivo
     (ver §5 para la única forma permitida de trabajar sobre el script de arranque).
   - No añadir funciones nuevas. Lo único "nuevo" permitido es infraestructura de prueba marcada
     como tal (§4.3).
4. **Orden:** §1 sondeo → §2 decisiones → §3 correcciones de código → §6 integración → §4 verificación
   en el dispositivo → §5 F6 → §7 criterios de fusión → §8 F9 → §9 cierre.
   (§4 y §5 dependen de lo que revele §1.)

---

## 1. Sondeo de capacidades (T-0)

Averigua **qué puedes hacer desde donde corres** y guárdalo en
`docs/PLAN-ESTABILIZACION-ESTADO.md` → sección "Capacidades". Prueba cada una con un comando
inocuo y registra `SÍ`/`NO` + el comando que funcionó:

| Capacidad | Prueba sugerida |
|---|---|
| Servidor OpenCode alcanzable | `curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:49374/api/health` (cualquier respuesta HTTP = alcanzable; 401 también vale) |
| Raíz del host | `su -c id` · si falla: `nsenter -t 1 -m -- id` |
| Ver el host desde el chroot | `nsenter -t 1 -m -- ls /data/adb/service.d` |
| `logcat` | `logcat -d -t 20` (o vía `nsenter -t 1 -m --`) |
| Instalar APK | `pm list packages com.aegis.hub` (¿instalada?) y `pm path com.aegis.hub` |
| Dirigir la UI | `input keyevent 3` · `uiautomator dump /sdcard/ui.xml` |
| GitHub (CI y artifacts) | `gh run list -L 3` · `gh run download --help` |
| Red hacia GitHub | `git ls-remote origin HEAD` |
| `flock` en `/data/local/tmp` | `flock -n /data/local/tmp/t.lock -c 'echo ok'` (por `su`/`nsenter` si hace falta) |

**Resultado esperado:** una de tres situaciones, que decide el resto del plan:

- **A — Acceso completo** (raíz + logcat + pm + UI): ejecuta §4 entero.
- **B — Acceso parcial** (raíz o logcat, sin UI): ejecuta lo automatizable de §4 y deja lo demás en
  `VERIFICACION-PENDIENTE.md`.
- **C — Sin acceso al dispositivo:** salta §4 y §5; haz §3, §6, §7 (sin fusionar) y §9.

---

## 2. Decisiones resueltas (no volver a preguntar)

| Tema | Decisión |
|---|---|
| `graphify-staleness.js` borrado (preexistente) | **No se repone.** Quitar sus referencias muertas en `AGENTS.md`/docs y registrar el motivo en el estado. |
| `AGENTE_POR_DEFECTO` → build (migración ECC) | **Se acepta**, ya está en commit propio; verificar que `getOpencodeAgents` lo contiene (si no, caer al primero seleccionable y avisar con `Log.w`). |
| Antigravity | **Descartado definitivamente.** |
| Pantallas Workspace/Workflow/Control Center/Skills | **Se mantienen.** |
| Versión | La integración (§6) sube a `1.2.0`. |
| `SYNC_POR_EVENTOS` | **Sigue en `false`** en release. Solo se prueba con el interruptor de §4.3. |
| Hook de boot del dispositivo | **No se modifica** (§5). |

---

## 3. Correcciones de código pendientes (no requieren dispositivo)

Rama por tarea (`estabilizar/C<n>-<slug>`), partiendo de la **punta de la cadena de fases**
(§6.1 define cuál es). Un push por tarea.

### C1 — Validar el modelo contra el catálogo antes de fijarlo

**Por qué (medido por ti):** el servidor devuelve `204` para un modelo inexistente; el error
aparece en el turno, no al fijar. Hoy el rollback de F3 solo salta con errores de red, así que un
modelo inválido deja el chip "bien" y falla después.

- En `SesionConfigRepo.fijarModelo` (y en el envío): consultar `CatalogoRepo` (caché TTL).
  Si el id normalizado **no está** en el catálogo → `Fallo("El modelo «x» no está disponible en OpenCode")`
  **sin** llamar al servidor y **sin** tocar la caché local.
- Si el catálogo no se pudo cargar (red) → **no bloquear**: dejar pasar con aviso en `avisos`
  (el servidor decide), para no impedir el envío por un fallo de catálogo.
- Si el catálogo está caducado y el modelo no aparece: **refrescar una vez** (single-flight) antes
  de declararlo inexistente.
- En el ViewModel: el `Fallo` revierte el chip y llena `_error` (ya existe el rollback de F3).
- Tests: modelo inexistente → 0 llamadas a `setSessionModel`, chip vuelve al previo; modelo
  existente → 1 llamada; catálogo caído → pasa con aviso; catálogo viejo que no lo tiene → 1
  refresco y luego decide.
- Ajuste de checklist: V-03 pasa a "elegir un modelo que ya no está en el catálogo".

### C2 — Diagnosticar el SSE que "no entrega ni un byte" bajo MockWebServer

Tienes ~10 ciclos gastados sin causa. **Límite de esta tarea: 3 ciclos de CI.** Método de bisección
(cada paso es un test mínimo; el primero que falle señala la capa culpable):

1. **OkHttp pelado → MockWebServer** con cuerpo `text/event-stream` y 2 eventos, leyendo con
   `response.body.source().readUtf8Line()` dentro de `runBlocking` (no `runTest`).
2. Mismo test **dentro de `runTest`**. **Hipótesis principal (a confirmar, no asumir):** en `runTest`
   el tiempo es virtual; un `withTimeout`/`delay` se dispara de inmediato mientras la E/S real aún no
   ha ocurrido → "timeouts" y `peticiones=0`. Lo unario "va al instante" porque no depende del
   timeout virtual. Solución si se confirma: ejecutar la E/S con `withContext(Dispatchers.Default)`
   y un `withTimeout` real (o usar `runBlocking`) en los tests de transporte.
3. Añadir capas **una a una**: `createAuthInterceptor` → `HttpLoggingInterceptor(BASIC)` →
   `TraceoPeticiones` (el de F0; comprobar que **no** consume/`peekBody` el cuerpo) → Retrofit con
   `@Streaming openEventStream()` → el parser de `EventStream`.
4. Revisar la respuesta simulada: `Content-Type: text/event-stream`, que el cuerpo termine en línea
   en blanco, y probar `MockResponse.setBody` frente a `setChunkedBody` frente a `throttleBody`.
5. Si tras 3 ciclos no hay causa: **para**, deja los tests de transporte como `@Ignore("causa en
   estado: C2")` **con el hallazgo escrito**, y traslada la prueba de interop a §4.2 (V-06/V-08 con
   el servidor real). No sigas quemando CI.

Entrega: causa confirmada (o descartada), test de transporte verde si se resuelve, nota en el estado.

### C3 — Cubrir el arreglo de "EOF limpio sin parpadeo"

Ya lo corregiste (EOF limpio reabre sin pasar por `Reconectando`). Añade el test que lo fija:
secuencia `Conectado → EOF → reabre` ⇒ el flujo de estado **no emite** `Reconectando`; y
`Conectado → excepción → Reconectando → Conectado` ⇒ **sí** lo emite.

### C4 — Higiene

- Quitar referencias muertas a `graphify-staleness.js` (decisión §2).
- Comprobar `grep -rniE "\bhub\b"` en `app/app/src/main` (E9) y registrar el resultado.
- `python3 tools/check_hilo_principal.py` y `python3 tools/check_composable.py` en verde.
- `docs/qa/QA_CHECKLIST.md` actualizado con el ajuste de V-03.

---

## 4. Verificación en el dispositivo (según §1)

> Solo se ejecuta lo que §1 haya marcado como posible. Cada ítem termina con uno de tres
> resultados en `docs/qa/VERIFICACION-<fecha>.md`: **AUTO-OK**, **AUTO-FALLÓ** (con log) o
> **PENDIENTE-OJOS** (no automatizable). **No pidas nada al usuario**; los PENDIENTE-OJOS van a
> `docs/qa/VERIFICACION-PENDIENTE.md` con: qué mirar, dónde, y qué resultado es el correcto.

### 4.1 Preparar el APK

1. Baja el artifact debug de la **rama de integración** (§6) con `gh run download` (o el
   mecanismo que §1 haya validado). Verifica que `versionName` sea `1.2.0` con
   `aapt dump badging` si existe, o `dumpsys package com.aegis.hub | grep versionName` tras instalar.
2. La clave de firma debug es desechable: `pm uninstall com.aegis.hub` antes de `pm install -r`.
   Antes de desinstalar, **copia** a `/data/local/tmp/respaldo-aegis/` los datos que vivan en
   `/sdcard/projects/Aegis/app/state/` (no se pierden al desinstalar, pero confírmalo y compara hash
   antes/después). Copia el APK a `/data/local/tmp` y instala desde ahí.
3. Concede permisos ya usados por la app (notificaciones, accesibilidad si el flujo lo exige) por
   `pm grant`/`settings` **solo** si ya eran necesarios antes; no amplíes permisos.
4. `logcat -c` y lanza `am start -n com.aegis.hub/.MainActivity`. Espera 20 s. Falla si el log
   contiene `FATAL EXCEPTION`, `ANR in com.aegis.hub` o `StrictMode policy violation` con stack en
   `com.aegis.hub`.

### 4.2 Qué se automatiza (mapa del checklist V-xx)

| V | Automatizable | Cómo |
|---|---|---|
| V-09 arranque | **Sí** | Contar `pgrep -f '[o]pencode serve --service' \| wc -l` (debe ser **1**) tras: arranque de la app, `am force-stop` + relanzar ×5, `kill` del servidor + relanzar la app. Registrar cada conteo. |
| V-08 caída/levantada del servidor | **Parcial** | Con la app abierta en un chat: matar `serve`, esperar, comprobar en logcat el cambio a estado de reconexión y la recuperación sin reabrir (`AegisChat`/`EventosServidor`). El banner visual es PENDIENTE-OJOS. |
| E3/E4 peticiones | **Sí, si hay UI** | Con `AegisTrace` en logcat: abrir un chat (por `uiautomator dump` + `input tap` sobre el nodo por texto) y contar `GET api/session/:id`, `GET …/message`, `GET api/model`. Reposo: 60 s con el chat abierto, contar peticiones. Comparar con `PERF-BASELINE.md`. |
| E8 skills 1 `su` | **Sí** | Abrir la pantalla de Skills y contar ejecuciones de `Raiz` en el log (si el log de `Raiz` existe en debug; si no, añadirlo como línea `Log.d` — es instrumentación, no función). |
| V-01/V-02/V-04/V-05/V-06/V-07 | **Best effort** | Intentar con `uiautomator`+`input`, **con límite de 3 intentos por escenario** (la UI cambia; no escribas un framework). Lo que no se logre → PENDIENTE-OJOS. Para V-04: comprobar por la API (`GET /api/session`) que "+" ×2 crea **1** sesión. Para V-05: comprobar por la API que `directory` de la sesión nueva == carpeta del proyecto. |
| V-03 cambios rápidos | **Parcial** | Alternar `am start` entre dos sesiones 10 veces con intents/UI y leer el modelo con `GET /api/session/{id}` frente al log del chip (`AegisChat`). Lo visual → PENDIENTE-OJOS. |
| V-10 TTS, V-11 memoria | **V-11 sí** | `dumpsys meminfo com.aegis.hub` cada 5 min durante 30 min con uso simulado; anotar la curva. V-10 → PENDIENTE-OJOS. |

### 4.3 Interruptor de prueba para `SYNC_POR_EVENTOS` (infraestructura de prueba)

Para probar F5 sin recompilar ni cambiar el valor de release:

- `SYNC_POR_EVENTOS` se lee de `BuildConfig` **y** se puede sobreescribir con un valor de
  `SharedPreferences("aegis_flags")`, **solo si `BuildConfig.DEBUG`**. En release esa rama de
  código no existe (verifícalo con un test que lea la fuente del flag en modo release).
- Activación para pruebas: un `BroadcastReceiver` **solo en debug** (`exported=false` o protegido
  por permiso de firma) o, más simple, un extra de intent a `MainActivity` solo en debug:
  `am start -n com.aegis.hub/.MainActivity --ez sync_eventos true`.
- Marca claramente en el código: `// INFRAESTRUCTURA DE PRUEBA — solo debug`.

Con él, repite con el flag **true** las pruebas de V-06 y V-08 (turno largo; matar/levantar
servidor) y mide E4 (≤ 1 petición/15 s en reposo). Registra ambos modos (false/true) lado a lado.
**No cambies el valor por defecto de release**, pase lo que pase.

---

## 5. F6 sin tocar el hook de arranque

**Regla:** el script `/data/adb/service.d/99-aegis-opencode.sh` **no se modifica ni se reemplaza.**
Todo lo demás de F6 sí se hace.

1. **Copiar (solo lectura) al repo.** Si §1 permite leerlo (`nsenter -t 1 -m -- cat …` o `su -c cat …`),
   cópialo a `app/scripts/service.d/99-aegis-opencode.sh.referencia`. **Antes de commitear**,
   búscale secretos (`grep -niE "pass|token|secret|key|auth|basic|bearer"`); si hay valores, reemplázalos
   por `<REDACTADO>` en la copia del repo y anótalo. Si no puedes leerlo → registra "no accesible"
   y continúa con lo siguiente (el nuevo script no depende de él).
2. **Probar el `pgrep`** (si hay raíz): ejecutar por el mismo camino que usa la app
   (`su -c "pgrep -f 'opencode serve --service'"` y `… '[o]pencode serve --service'"`). Guardar ambas
   salidas en el estado. Si difieren (la forma sin corchete devuelve el PID del propio `sh -c`),
   queda confirmado el autoemparejamiento.
3. **`flock`:** probar `flock` en `/data/local/tmp` (no en `/sdcard`). Si no está disponible, usar
   `mkdir /data/local/tmp/aegis-serve.lock` como cerrojo atómico con limpieza por antigüedad
   (> 60 s = huérfano).
4. **Crear `app/scripts/aegis-serve.sh`** (T-F6.2 del plan base): montajes idempotentes, guarda con
   `pgrep` **con corchete**, `oom_score_adj=-1000`, lanzamiento con `nohup`, salida
   `YA_HAY:<n>` / `LANZADO` / `ERROR:<motivo>`. `bash -n` en CI (job `lint`).
5. **`ServidorOpenCode`** (T-F6.3): `asegurar()` idempotente, estados
   `Comprobando/Arrancando/Listo/Error`, `MainActivity` solo observa; eliminar
   `startRootSystemAndPoll` y compañía. `OpenCodeLauncher` pasa a invocar `aegis-serve.sh`
   (desplegado por el APK a una ruta fija, p. ej. extraído a `/data/local/tmp/aegis-serve.sh` con
   `chmod 755` en el primer uso; `/sdcard` es `noexec`).
6. Tests JVM con `shell` y sondeo inyectables (casos del plan base) **más**: dos `asegurar()`
   concurrentes → un lanzamiento; script devuelve `ERROR:x` → `Error("x")`.
7. Con dispositivo (§4.2 V-09): la app debe dejar **1** proceso `serve` en todas las pruebas. Si
   alguna da 2 → AUTO-FALLÓ, **revertir el commit de F6** y registrar.

**Importante:** como el hook de boot queda intacto, la protección contra duplicados en el arranque
del teléfono sigue siendo la suya (ya tiene guarda). Lo que F6 garantiza es que **la app** no
lance un segundo servidor. Reemplazar el hook por el script unificado queda **fuera de esta ronda**
y se anota como opción futura en el estado.

---

## 6. Integración

### 6.1 Determinar la forma de las ramas

`git merge-base --is-ancestor` entre `F1-limpieza … F10-flujos` para saber si están **apiladas**
(cada una contiene a la anterior). Registra el resultado y la punta real.

### 6.2 Rama `estabilizar/integracion`

1. Parte de `main`. Fusiona en este orden: `F1`, `F2`, `F3`, `F4`, `F5`, `F7`, `F8`, `F10`,
   y luego `C1…C4` (§3) y F6 (§5) cuando existan. Si están apiladas, basta fusionar la punta.
2. Resuelve conflictos conservando **ambos** lados cuando sean ortogonales; si un conflicto es
   semántico (misma función cambiada de forma distinta), prevalece la versión de la fase **más
   tardía** y se deja nota en el estado.
3. Tras cada fusión parcial relevante: CI verde (7 jobs) y tests ≥ el número anterior.
4. `versionName` → `1.2.0`; `CHANGELOG.md` consolidado; `app/.ponytail.md` actualizado;
   `graphify update .` (STALE=no).
5. **Comprobaciones finales de integración** (todas por `grep`/tests, sin dispositivo):
   un solo `createSession(` de producción fuera del repo (más el smoke), 0 `createSessionViaHub`,
   0 `catch (_: Exception) {}` vacíos en `ui/viewmodel/`, 0 `RootShell.` fuera de `Raiz`/lanzador,
   `SYNC_POR_EVENTOS=false` en release, E9.
6. El artifact debug de esta rama es el APK de §4.1.

---

## 7. Criterios de fusión a `main` (sustituyen al "checklist manual obligatorio")

Como el usuario delega la validación, el criterio se vuelve **automático y conservador**:

- **Fusionar** `estabilizar/integracion` → `main` **solo si se cumplen TODOS**:
  1. CI verde (7 jobs) y tests ≥ 277.
  2. Situación **A o B** de §1 y §4.1 sin `FATAL EXCEPTION`/ANR.
  3. V-09 **AUTO-OK** (siempre 1 proceso) — si F6 está incluido.
  4. Ningún ítem de §4.2 en **AUTO-FALLÓ**.
  5. `SYNC_POR_EVENTOS=false` en release.
  6. Un revert limpio disponible (etiqueta `pre-integracion-<fecha>` en `main` antes de fusionar).
- **No fusionar** si falta cualquiera (incluida la **situación C**): deja la rama lista, el APK
  disponible y el motivo en el estado. No es un fallo: es el resultado correcto sin evidencia.
- La fusión usa `--no-ff` para poder revertirla de una vez.
- Los ítems **PENDIENTE-OJOS** **no bloquean** la fusión (la app arranca estable y el flag está
  apagado), pero **sí bloquean** F9 y la activación de `SYNC_POR_EVENTOS` (§8).

---

## 8. F9 y activación de `SYNC_POR_EVENTOS`

Estas dos cosas **requieren tiempo de uso real**, que no puedes simular. Por eso se definen como
**condiciones comprobables en una ejecución posterior** de este mismo archivo:

**En cada ejecución, antes de nada**, comprueba:

1. ¿Existe en `main` una fusión de integración con fecha ≥ 72 h atrás? (`git log`)
2. En ese intervalo, ¿hay evidencia de estabilidad? Fuentes: `logcat` guardado si existe;
   `/data/anr` y `/data/tombstones` sin entradas de `com.aegis.hub` posteriores a la fusión;
   `dumpsys meminfo` sin crecimiento sostenido; `docs/qa/VERIFICACION-PENDIENTE.md` sin ítems
   marcados como FALLÓ.
3. Si **no hay forma de comprobar el punto 2** (sin dispositivo) → **no avances**: registra
   "F9 aplazada: sin evidencia de estabilidad".

Si 1 y 2 se cumplen:
- **F9**: ejecútala como en el plan base (T-F9.1…T-F9.3), **una extracción por commit**, CI verde
  entre cada una, fachada hasta el final, `check_composable.py` tras cada extracción de UI.
  `T-F9.4` queda opcional.
- **`SYNC_POR_EVENTOS=true`**: solo si además existen resultados de §4.3 con el flag activado y
  **sin** AUTO-FALLÓ en V-06/V-08, y las métricas de E4 cumplen la meta. Cámbialo en una rama y
  deja la etiqueta de reversión. Si no hay esos resultados, el flag **sigue en false**.

Si no se cumplen, termina la ejecución con el estado actualizado: esto **no** es un error.

---

## 9. Cierre de cada ejecución

1. `docs/PLAN-ESTABILIZACION-ESTADO.md` al día: tabla por bloque (§3, §4, §5, §6, §7, §8),
   capacidades (§1), decisiones aplicadas (§2), commits y runs de CI.
2. `docs/PERF-BASELINE.md`: rellenar la columna "después" **solo con cifras medidas** (si no se
   pudo medir, "no medido: <motivo>", nunca una estimación).
3. `docs/qa/VERIFICACION-<fecha>.md` y `VERIFICACION-PENDIENTE.md` (si hay ítems).
4. `CHANGELOG.md`, `app/.ponytail.md`, `graphify update .`.
5. **Informe final** (máximo ~40 líneas), con este formato:

```
RESUMEN
Situación de acceso al dispositivo: A | B | C
Bloques: C1 ✅/⚠️/⛔ · C2 … · C3 … · C4 … · Integración … · F6 … · Verificación … · F9 …
Fusionado a main: sí/no (motivo) · Etiqueta de reversión: <nombre>
Tests: N (antes 277) · CI: <run>
Hallazgos nuevos: …
Ítems PENDIENTE-OJOS: N (lista en docs/qa/VERIFICACION-PENDIENTE.md)
Próxima ejecución de este archivo: qué condición se espera (p. ej. "72 h desde <fecha>")
```

No incluyas peticiones al usuario en el informe: describe el estado y qué hará el plan en la
siguiente ejecución.

---

## 10. Límites (resumen)

- No tocar: firma, `app/state/*.json`, hook `service.d`, `docs/_archivo`, `docs/audits`.
- No reponer `graphify-staleness.js`.
- No activar `SYNC_POR_EVENTOS` en release sin evidencia (§8).
- No hacer F9 sin 72 h de estabilidad comprobada.
- No gastar más de 3 ciclos de CI por tarea atascada (C2 incluida).
- No inventar mediciones ni resultados de pruebas: si no se midió, se dice.
