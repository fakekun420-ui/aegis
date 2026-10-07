# PLAN DE ESTABILIZACIÓN Y OPTIMIZACIÓN — Aegis (app Android)

> **Para el agente de código.** Este documento es ejecutable: cada fase tiene tareas con
> archivos, pasos, criterios de aceptación, tests y plan de vuelta atrás. Léelo entero una vez,
> luego trabaja **una fase por rama**, en orden.
>
> **Base analizada:** commit `bd515c5` (`fix(app)+docs: sesion en proyecto usa carpeta real y avisa;
> auditoria integral`), 2026-10-06. Los números de línea son de ese commit y se desplazarán al
> editar: **localiza siempre por nombre de símbolo (`grep -n`), no por número**.
>
> **Cómo se produjo este plan:** lectura estática del repo + `docs/AUDITORIA-2026-10-06.md`.
> **No se compiló ni se ejecutó nada.** Todo lo marcado `VERIFICAR` es una hipótesis razonada que
> debes comprobar (grep, test o dispositivo) **antes** de actuar sobre ella. Lo no marcado es
> observable directamente en el código.

---

## 0. Reglas de ejecución (obligatorias)

1. **No hay toolchain Android en el host** (ver docstring de `tools/check_composable.py`): la única
   compilación real es la CI (~9 min). Por tanto: commits pequeños, **un push por tarea o grupo
   pequeño**, esperar CI verde antes de seguir. Nunca acumules una fase entera sin compilar.
2. **Antes de borrar algo:** `grep -rn "<símbolo>" app/app/src/main app/app/src/test app/app/src/androidTest`
   y exige 0 usos fuera de su definición. Aplica el **control negativo**: demuestra que el grep
   encuentra un símbolo que sí existe (ej. `grep -rn "getProjects("` debe dar resultados).
3. **Commits de "mover/renombrar" no cambian comportamiento.** Commits de "arreglar" no mueven
   código. Nunca mezclar ambos.
4. **Respeta las reglas del flujo ya medidas** (auditoría §2): el servidor es la única verdad de
   modelo/agente; el prompt no acepta modelo (fijar ANTES); ningún `su` en el hilo principal
   (todo por `sh()` a IO); ningún `Regex()` en caliente; ningún `stop()/speak()` de TTS en main;
   ningún fallo mudo en botones.
5. **Contratos de red:** si tocas `NativeModels.kt` o `OpenCodeApi.kt`, corre
   `python3 tools/chk_forma.py` contra el CLI vivo (`:49374`) → debe dar 0 tipos incompatibles.
6. **Checks locales antes de cada commit:** `python3 tools/check_composable.py` +
   `python3 tools/chk_forma.py` (si hay servidor) + `bash -n` de los `.sh` tocados.
   En CI: los 7 jobs en verde (209 tests JVM como mínimo; **el número no puede bajar** salvo que
   borres tests de código eliminado, y entonces se documenta en el commit).
7. **Después de cada fase:** actualizar `CHANGELOG.md`, `app/.ponytail.md` (directriz del propio
   archivo), correr `graphify update .` (exigido por `AGENTS.md`) y marcar la fase en
   `docs/PLAN-ESTABILIZACION-ESTADO.md` (créalo en F0).
8. **Estilo de commit del repo:** español, prefijo `fix(app):` / `refactor(app):` / `test(app):` /
   `docs:` / `chore(ci):`, mensaje que explica el *porqué* medido.
9. **Ramas:** `estabilizar/F<n>-<slug>`; fusionar a `master` solo con CI verde **y** checklist
   manual (§6) de la fase pasada en el móvil.
10. **Puntos `DECISIÓN`:** no los resuelvas tú. Anota la pregunta en
    `docs/PLAN-ESTABILIZACION-ESTADO.md`, aplica el **valor por defecto indicado** y sigue.
11. **No tocar** (fuera de alcance, ver §8): firma/keystore, `app/state/*.json`, el script de
    `service.d` del dispositivo salvo en F6, `graphify-out/`, `docs/_archivo`, `docs/audits`.
12. **Instalación de pruebas:** el debug de CI se firma con clave desechable → hay que
    desinstalar antes de instalar cada debug (pérdida de `SharedPreferences`; ver F3 sobre por qué
    eso ya no debe romper nada). Instalar por `pm` copiando antes a `/data/local/tmp`.

---

## 1. Objetivo y criterios de "estable"

**Objetivo:** una app con **un solo camino por operación**, **una sola fuente de verdad por dato**,
**un solo canal de actualización**, errores siempre visibles con motivo, y sin trabajo pesado en
el hilo principal.

**Definición de "estable" (todas medibles):**

| # | Criterio | Cómo se mide |
|---|---|---|
| E1 | 0 ANR en 1 h de uso mixto (chat, proyectos, skills, TTS) | `/data/anr` vacío tras la sesión; logcat sin `ANR in com.aegis.hub` |
| E2 | Exactamente 1 proceso `opencode serve --service` tras: arranque en frío, abrir app, matar y reabrir app, 5 aperturas seguidas | `pgrep -f '[o]pencode serve --service' \| wc -l` = 1 |
| E3 | Abrir un chat existente ≤ 3 peticiones HTTP (sesión, cola de mensajes, catálogo si no está en caché) | Contador de peticiones de F0 |
| E4 | Chat abierto en reposo con SSE conectado: ≤ 1 petición HTTP por 15 s | Contador de peticiones |
| E5 | Crear chat (global y en proyecto vinculado) deja **una** sesión, con agente, modelo y carpeta correctos, o un mensaje con el motivo | Checklist V-04/V-05 + test |
| E6 | Cambiar de sesión rápido 10 veces nunca deja el modelo/agente de otra sesión en el chip | Checklist V-03 + test de carrera |
| E7 | Ningún botón falla en silencio | Revisión de `catch` vacíos = 0 en rutas de usuario (script de F10) |
| E8 | Pantalla de Skills carga con ≤ 1 `su` | Log de `RootShell` en debug |
| E9 | 0 referencias al "Hub" en código ejecutable, textos de UI y docs vigentes | `grep -rni hub` (excepciones listadas en F1) |

---

## 2. Diagnóstico (hallazgos medidos en el repo)

Cada hallazgo tiene ID (`H-xx`) que las tareas referencian.

### 2.1 Duplicación de caminos

**H-01 — Cuatro caminos distintos para crear una sesión**, cada uno con reglas propias:

| Sitio | Carpeta | Agente/modelo | Vínculo a proyecto | Error |
|---|---|---|---|---|
| `ChatViewModel.createNewSession` | ninguna | solo lo que haga `RutaNativa.createSession` | no | `catch → null` mudo |
| `MainViewModel.createSessionForProject` | `proj.folder ?: proj.resolvedFolder` | idem | `ProjectsStore` **y** `api.linkSession` | fallback a `createSessionViaHub` |
| `ProjectDetailViewModel` (crear + primer mensaje) | `folder ?: resolvedFolder ?: /sdcard/projects/<nombre>` | idem | `api.linkSession` | mensajes en `_error` |
| `SetupNative` (smoke test, `openCodeApi.createSession` directo) | — | ninguno | no | — |

Las tres primeras divergieron ya una vez (bug "sesión en blanco": carpeta derivada del nombre).

**H-02 — Doble escritura del vínculo sesión↔proyecto.** `MainViewModel.createSessionForProject`
y `moveSession` llaman `projectsStore.linkSessionToProject(...)` **y después** `api.linkSession(...)`,
que en `RutaNativa.linkSession` vuelve a llamar `store.linkSessionToProject` + `setSessionTitle`.
Misma operación dos veces, la segunda dentro de `try { } catch (_: Exception) {}` mudo.

**H-03 — Posible sesión duplicada por "fallback fantasma".** `RutaNativa.createSession` envuelve
todo en un `try`: si la sesión se crea en el servidor (`oc.createSession`) y algo posterior lanza
(p. ej. fijar modelo), devuelve `Envelope(ok=false, data=null)` aunque la sesión **existe**.
`MainViewModel` interpreta eso como fallo total y llama a `createSessionViaHub`, que vuelve a llamar
`api.createSession` → segunda sesión huérfana. `VERIFICAR` reproduciendo con un fake que lance en
`setSessionModel`.

**H-04 — La costura se salta a sí misma.** `MainViewModel.renameSession` y `deleteSession` usan
`OpenCodeApi.default` directamente y, si falla, llaman `api.renameSession`, que vuelve a pegar al
mismo endpoint (`RutaNativa.renameSession → oc.updateSession`). Dos capas, mismo destino, y la
primera ignora el filtro/normalización de la segunda. `MainViewModel` también llama
`openCodeApi.getActiveSessions()` (2 sitios) saltándose `api.getInflight()`.

**H-05 — Modelo y agente viven en tres sitios:** servidor, `SharedPreferences`
(`ModelPreferences`, `AgentPreferences`) y estado del ViewModel (`_selectedModel`, `_agentMode`).
`selectModel` escribe prefs **antes** de que el servidor confirme y traga el fallo
(`try { api.setSessionModel(...) } catch (_: Exception) { }`): la UI muestra un modelo que el
servidor nunca recibió. Es el origen histórico del chip "No disponible: opencode/x".

**H-06 — Carrera al cambiar de sesión.** `restoreModelFor` y `restoreAgentFor` lanzan una corrutina
en `viewModelScope` sin guardar el `Job` ni comprobar, al terminar, que `currentSessionForModel`
siga siendo la misma sesión. Una respuesta tardía de la sesión A escribe `_selectedModel`/`_agentMode`
cuando ya se muestra la B. (`load()` además contiene un no-op:
`if (_selectedModel.value.isNullOrBlank()) _selectedModel.value = null`.)

**H-07 — `ChatViewModel.load()` dispara 5–6 peticiones solapadas** para abrir un chat:
`getSessionModel` y `getSessionAgent` (ambas acaban en `oc.getSession(id)` → **el mismo GET dos
veces**), `getOpencodeSessions` (lista **completa** solo para sacar el título), `getMessages`
(hasta `LIMITE_MAX_MENSAJES`), `listModels` (~480 modelos) y `listAgents`.
Un solo `getSession` da título + modelo + agente.

**H-08 — El catálogo de modelos se descarga una y otra vez:** `loadModels` (cada `load`),
`RutaNativa.fijarModelo` (**en cada envío**), `getModels`, y `createSession` (`listAgents` +
`listModels`). Sin caché.

**H-09 — Tres mecanismos de sincronización de chat que se excluyen a mano:**
(a) `startViewRefresh`: bucle con **4 peticiones por ciclo** (inflight, formularios, permisos,
cola de mensajes); (b) `pollingJob` dentro de `sendWithFiles`: `getMessages` **completo** cada
1,5 s durante el envío; (c) SSE (`EventStream`) solo durante el envío y solo para el texto en vivo.
La exclusión se hace con flags (`pollingJob?.isActive`, `_streamingText`, `streamingSince`,
`sseSuccess`, `sseRequestAccepted`, `inFlightSendKey`) → terreno fértil para carreras.

**H-10 — Semántica de "turno terminado" degradada (`VERIFICAR`).** El Hub original calculaba
`busy/turnOver` a partir de `session.execution.*` (ver `docs/adr/ADR-003-turn-final-signal.md`).
`RutaNativa.getInflight` ahora lo aproxima desde `GET /api/session/active` con `since = null`,
`lastSeen = now` y `turnOver = (type != "running")`. Cualquier inexactitud aquí se ve como divisor
"✓ respuesta final" o indicador "trabajando" erráticos.

### 2.2 Arranque, hilos y rendimiento

**H-11 — Dos lanzadores del servidor y arranque en tres funciones.** El script de boot
(`/data/adb/service.d/99-aegis-opencode.sh`, **solo en el dispositivo, no versionado**) y
`OpenCodeLauncher.asegurarAbierto` duplican la guarda anti-duplicado (`pgrep`). En `MainActivity`
conviven `checkHubOnStart`, `startRootSystemAndPoll`, `pasoHubRetirado` (no-op) y
`anotarArranqueSinHub`. `VERIFICAR` en el móvil: `pgrep -f 'opencode serve --service'` ejecutado
vía `su -c "…"` puede **autocoincidir** con la línea de comando del propio `sh -c` (el patrón está
en su argv). Si coincide, la guarda dice "ya hay servidor" cuando no lo hay (o al revés según el
shell). Técnica estándar: `pgrep -f '[o]pencode serve --service'`.

**H-12 — Un proceso `su` nuevo por comando** (`RootShell.exec` → `Runtime.exec("su","-c",…)`).
`getSkills` = 1 `ls` + N `head`; `getSystemSkills` = 1 `ls` + N `test -f`; `getSystemHealth` = 4
ejecuciones (`/proc/uptime`, `/proc/meminfo`, `ls` proyectos, `ls` skills). Con Magisk lento cada
`su` cuesta cientos de ms y hasta el timeout de 5 s.

**H-13 — Regex compiladas en rutas calientes** (`VERIFICAR` cuáles están dentro de `remember`):
`Models.kt` getter `Project.resolvedFolder` (`Regex("[^a-z0-9_-]")` **en cada lectura**),
`RutaNativa.getSystemHealth` (`"\\s+".toRegex()`), `ChatViewModel.isTechnicalTitle` y
`ChatScreen` (`Regex("^[0-9a-fA-F-]{8,}$")` por título), `SubagentCard` (2), y en `MarkdownText`
varias dentro de funciones de render (`toolMatch`, listas, `linkRegex`, `tokenRegex`, y `matches(Regex(...))`
por token). `OpenCodeLauncher` (`Regex("\\s+")`, una vez por arranque) es inocuo.

### 2.3 Código muerto, vocabulario y modelos

**H-14 — Código muerto o engañoso:**
- 11 métodos de `ApiService` sin ningún uso fuera de `ApiService`/`RutaNativa` en `main`:
  `dispatchAgent`, `getAgentStatus`, `getAgents`, `getJobs`, `getProjectState`, `getSkillConfig`,
  `getSystemLogs`, `getSystemMemory`, `runJob`, `updateSkill`, `updateSkillConfig`
  (`VERIFICAR` también `test/` y `androidTest/`, y usos internos en `RutaNativa`).
- Anotaciones Retrofit de `ApiService` (`@GET`, `@POST`…) sin ningún `Retrofit.create(ApiService)`:
  `RutaNativa` implementa la interfaz a mano. Solo `OpenCodeApi` usa Retrofit de verdad.
- `RetrofitBootstrapRepository` no usa Retrofit: delega en `Conexion.api`.
- Flujo de "proveedor" (`selectProvider`, `_selectedProvider`, `_sessionProviderBound`,
  `createNewSession(provider)`; ~27 referencias en `ChatViewModel`, 6 en `ChatScreen`) pensado para
  Antigravity, que ya no existe: cambiar "proveedor" **crea una sesión nueva**
  (`selectProvider` → `createNewSession` → `_pendingSessionNav`). El parámetro `provider` de
  `sendMessage` y `createNewSession` "se conserva por firma pero ya no decide".
- `ModelsHub.kt` (tipos del Hub: `HealthResponse`, `AgentItem`, `JobItem`, …) y su envoltura
  `Response<…>` de Retrofit usada solo para disfrazar respuestas nativas.

**H-15 — El vocabulario "Hub" sigue vivo** en identificadores (`hubReachable`, `checkHubOnStart`,
`pasoHubRetirado`, `anotarArranqueSinHub`, `createSessionViaHub`, `delHub`, `scheduleHubRetry`,
`RetrofitBootstrapRepository`, `hasHub`, `onInitHub`) y en **textos que ve el usuario**
("Sin conexión con el Hub…" en `ChatScreen`, "el Hub no responde" en `ChatsScreen`,
"Workspace (Hub)" en `MainNavScreen`, "[INIT HUB]" en `WorkspaceScreen`, "recortado por el Hub"
en `SubagentCard`). `parseDeliveryError` está escrito para el sobre `{ok:false,error:{code,message}}`
del Hub y `providers.js`; **no se sabe si parsea los errores reales de OpenCode** (`VERIFICAR` con
un 4xx/5xx real, p. ej. enviar con modelo inexistente).

**H-16 — Tres familias de tipos para el mismo concepto, con nombres casi idénticos:**
`OpenCodeSession` (wire, `NativeModels`) → `OpencodeSession` (dominio, `Models.kt`, con
`model: Any?`); `OpenCodeNativeAgent` → `OpencodeAgent`; `OpenCodeMessage` → `Message`
(vía `NativeMapper`). `Models.kt` (726 líneas) mezcla dominio de chat, bootstrap/setup, formularios
y permisos.

**H-17 — Fallos silenciosos residuales:** `sendMessage` en `sendWithFiles` descarta la excepción
(`catch (_: Exception) { sseSuccess = false }`, `VERIFICAR` cómo llega el motivo a la UI);
`selectModel`; `RutaNativa.createSession` devuelve `Envelope(ok=false)` **sin `error`**, y falla
parcial (agente/modelo no fijados) solo va a `Log.w`; `linkSession`/`moveSession` con `catch {}`.

**H-18 — Deriva documental y de CI:** `README.md`, `docs/ARCHITECTURE.md`,
`app/docs/FRONTEND_CONTRACT.md` y `app/README.md` describen `backend/`, `agents/`, Hub `:8765`,
`X-Provider`, `X-Aegis-Token`: nada de eso existe ya. `versionName` en `app/app/build.gradle.kts`
es `1.1.1` mientras README/CHANGELOG dicen `1.1.2`. El job `backend-checks` solo imprime un aviso.

**H-19 — Objetos dios** (deuda, se trata al final): `ChatScreen` 2047, `RutaNativa` 1934,
`ChatViewModel` 1378, `MarkdownText` 1014, `SetupWizardScreen` 865, `Models` 726 líneas.

---

## 3. Arquitectura objetivo

```
UI (Compose)            Pantallas: solo pintan estado y emiten intenciones
   │
ViewModels              Estado de pantalla. Sin lógica de red/protocolo.
   │
Casos de uso / repos    ← NUEVO nivel explícito (paquete data/repo/)
   ├─ SesionesRepo      crear (atómico), listar, renombrar, borrar, vincular
   ├─ SesionConfigRepo  leer/fijar modelo+agente (servidor = verdad; prefs = caché)
   ├─ CatalogoRepo      modelos + agentes con caché TTL
   ├─ ChatSync          un solo canal: SSE + reconciliación + respaldo por poll
   ├─ SkillsRepo        lectura por lote, 1 exec
   └─ ServidorOpenCode  estado (Detenido/Arrancando/Listo/Error) + lanzador único
   │
Costura (RutaNativa → fachada delgada, luego se retira)
   │
OpenCodeApi (Retrofit) · EventStream (SSE) · ProjectsStore · RootShell
```

**Principios** (cada tarea debe poder justificarse con uno):
- **P1 Un camino por operación.** Si dos sitios hacen lo mismo, uno es un bug latente.
- **P2 Una fuente de verdad por dato.** Servidor para modelo/agente/título/mensajes;
  `ProjectsStore` para vínculo proyecto↔sesión (OpenCode no lo conoce); prefs solo como caché y
  último-usado.
- **P3 Éxito parcial es un resultado, no un fallo.** Se devuelve `Ok(valor, avisos)`; el fallo
  total lleva siempre `motivo`.
- **P4 Un canal de actualización** con degradación explícita, no flags cruzados.
- **P5 Lo caro se pide una vez** (catálogo, `getSession`, lote de `su`) y se cachea con TTL.
- **P6 Reversibilidad:** cambios de comportamiento tras un flag de `BuildConfig`/constante hasta
  validarse en el móvil (patrón ya usado en el repo con `Conexion`).

**Tipo base nuevo** (F2, usado por todo lo demás):

```kotlin
// app/app/src/main/kotlin/com/aegis/hub/data/repo/Resultado.kt
sealed interface Resultado<out T> {
    /** Éxito, posiblemente parcial: `avisos` lista lo que no se pudo completar. */
    data class Ok<T>(val valor: T, val avisos: List<String> = emptyList()) : Resultado<T>
    /** Fallo total. `motivo` SIEMPRE legible para el usuario; `causa` para el log. */
    data class Fallo(val motivo: String, val causa: Throwable? = null) : Resultado<Nothing>
}
inline fun <T, R> Resultado<T>.mapa(f: (T) -> R): Resultado<R> = when (this) {
    is Resultado.Ok -> Resultado.Ok(f(valor), avisos)
    is Resultado.Fallo -> this
}
```

---

## 4. Plan por fases

**Orden y dependencias:** F0 → F1 → F2 → F3 → F4 → F5 → F6 → F7 → F8 → F9 → F10.
F6 y F7 son independientes de F2–F5 y pueden intercalarse si F5 se bloquea.
F9 (partir objetos dios) **solo después** de que F1–F7 estén estables en el móvil.

| Fase | Resuelve | Riesgo | Valor |
|---|---|---|---|
| F0 Base y medición | — | bajo | habilita medir E1–E9 |
| F1 Limpieza de legado | H-14, H-15, H-18 | bajo | menos superficie de bugs |
| F2 Crear sesión: un solo camino | H-01, H-02, H-03, H-04, H-17 | medio | alto (sesiones duplicadas/en blanco) |
| F3 Modelo/agente: fuente única | H-05, H-06, H-17 | medio | alto (chip erróneo, carreras) |
| F4 Menos peticiones al abrir/enviar | H-07, H-08 | bajo | medio-alto |
| F5 Un canal de sincronización | H-09, H-10 | **alto** | alto (estabilidad del chat) |
| F6 Arranque y lanzador único | H-11 | medio | alto (doble servidor, RAM) |
| F7 `su`, hilos y regex | H-12, H-13 | bajo | alto (ANR) |
| F8 Reorganizar modelos de datos | H-16 | medio (mecánico) | medio |
| F9 Partir objetos dios | H-19 | medio (mecánico) | mantenibilidad |
| F10 Tests de flujo, CI, docs | todo | bajo | evita regresiones |

---

### F0 — Base y medición

**Objetivo:** poder demostrar mejora con números y no romper lo que ya funciona.

**T-F0.1 — Línea base de tests.** En la rama `estabilizar/F0-base` ejecuta la CI y anota en
`docs/PLAN-ESTABILIZACION-ESTADO.md`: nº de tests (esperado 209), duración, estado de cada job.
Si algún test falla en `master`, **párate y repórtalo**: no se construye sobre rojo.

**T-F0.2 — Archivo de estado.** Crea `docs/PLAN-ESTABILIZACION-ESTADO.md` con una tabla
`Fase | Estado | Commit | CI | Checklist manual | Notas | Decisiones pendientes`.

**T-F0.3 — Contador de peticiones (solo debug).**
Archivo nuevo `data/TraceoPeticiones.kt`: un `Interceptor` de OkHttp que cuenta peticiones por
`método + ruta normalizada` (reemplaza ids `ses_…`, `msg_…`, `prt_…` por `:id`) y cada 60 s emite a
logcat una línea `AegisTrace: GET api/session/:id=12 POST api/session/:id/model=1 …`.
Se añade en `OpenCodeApi.createOkHttpClient(...)` **solo si `BuildConfig.DEBUG`**.
Tests: normalización de rutas (3 casos) y que el contador no cuenta nada en release.

**T-F0.4 — StrictMode en debug.** En `MainActivity.onCreate` (o `Application`), si
`BuildConfig.DEBUG`: `StrictMode.setThreadPolicy(ThreadPolicy.Builder().detectAll().penaltyLog().build())`.
No usar `penaltyDeath`. Sirve para ver en logcat cualquier E/S, red o `su` en el hilo principal.
(`RootShell.exec` hace E/S de proceso: añade en `RootShell.exec` un
`check(Looper.myLooper() != Looper.getMainLooper())` **solo en debug** → lanza `IllegalStateException`
con el stack, para cazar llamadas desde main.)

**T-F0.5 — Medir línea base en el móvil** (con el servidor real) y guardar en
`docs/PERF-BASELINE.md`: peticiones al abrir un chat (60 s), peticiones en reposo con chat abierto
(60 s), peticiones al enviar un mensaje y esperar respuesta, `su` por apertura de Skills,
`pgrep … | wc -l` tras arranque en frío. Estas cifras son el "antes" de E3/E4/E8/E2.

**T-F0.6 — Higiene de versión.** Decide `versionName`: `app/app/build.gradle.kts` dice `1.1.1`,
README/CHANGELOG `1.1.2`. **DECISIÓN** (por defecto: alinear build.gradle a `1.1.2`; la
siguiente versión del plan será `1.2.0` al cerrar F7).

**Aceptación F0:** CI verde, debug muestra `AegisTrace` en logcat, `PERF-BASELINE.md` con cifras
reales (no estimadas). **Vuelta atrás:** revertir el commit; no hay cambio funcional en release.

---

### F1 — Limpieza de legado (código muerto y vocabulario)

**Objetivo:** reducir superficie sin cambiar comportamiento. Es la fase más barata y hace el resto
más legible.

**T-F1.1 — Eliminar los 11 métodos muertos de `ApiService`** (H-14).
1. Para cada uno: grep en `main`, `test`, `androidTest` (regla 2 de §0). Si aparece en algún test,
   el test se borra con el método **y se anota en el commit**.
2. Quitar de `ApiService.kt`, de `RutaNativa.kt` (el `override`) y los tipos de `ModelsHub.kt` que
   queden sin uso (`DispatchAgentRequest`, `AgentStatusResponse`, `JobsResponse`, `JobItem`,
   `LogsResponse`, `MemoryResponse`, `SkillConfigResponse`, `ProjectStateResponse`… `VERIFICAR`
   cada uno con grep).
3. Cuidado: `uninstallSkill` llama internamente a `deleteSkill`; `updateSkill` puede ser llamado
   desde otro `override` de `RutaNativa`. Si hay uso interno, **no** se borra: se convierte en
   función privada.
4. Un commit por grupo (agentes/jobs, system, skills) para que un fallo de compilación en CI se
   aísle.

**T-F1.2 — Quitar el flujo de "proveedor" (Antigravity).** `DECISIÓN` confirmar que Antigravity
queda descartado (el código y la auditoría lo dan por inexistente; por defecto: sí).
- `ChatViewModel`: eliminar `selectProvider`, `_selectedProvider`, `_sessionProviderBound`,
  el parámetro `provider` de `load`, `send`, `sendWithFiles`, `retryMessage`, `createNewSession`.
- `ChatScreen`: quitar el selector/acciones que los usen (6 referencias) y el `onProviderChange`
  correspondiente en `AppNavHost`.
- `ApiService.sendMessage`: quitar `provider` y `projectId` si `RutaNativa.sendMessage` no los usa
  (`VERIFICAR`; según el código leído solo usa `body`).
- `Models.kt`: `SessionRef.resolvedProvider`, `Project.provider`, `SendMessageRequest.provider`,
  `MessageInfo` campos de proveedor → eliminar **solo** si grep da 0 usos reales; los que se
  persisten en `ProjectsStore` (JSON ya guardado en dispositivos) se **conservan como campos
  ignorados** para no romper la deserialización de datos existentes.
- Tests afectados (`SesionModeloSyncTest`, `EnvioDesdeLaAppTest`…): adaptar firmas.

**T-F1.3 — Renombrar el vocabulario "Hub"** (H-15). Tabla de renombres (aplicar con IDE o `sed`
acotado; compilar entre grupos):

| Antes | Después |
|---|---|
| `RetrofitBootstrapRepository` | `NativoBootstrapRepository` (y quitar imports de Retrofit si sobran) |
| `checkHubOnStart` | `comprobarServidorAlArrancar` |
| `pasoHubRetirado`, `anotarArranqueSinHub` | **eliminar** (son no-ops documentales; el contexto histórico vive en CHANGELOG) |
| `createSessionViaHub` | **eliminar** (lo sustituye F2) |
| `delHub` (var local en `restoreAgentFor`) | `delServidor` |
| `scheduleHubRetry` (BootstrapViewModel) | `programarReintento` |
| `hubReachable` y derivados | `servidorAlcanzable` |
| `hasHub` / `onInitHub` / "[INIT HUB]" | `tieneWorkspace` / `onInicializar` / "[INICIALIZAR]" |

Textos de usuario: "Sin conexión con el Hub: esto puede no reflejar el estado real." →
"Sin conexión con OpenCode: lo que ves puede no ser el estado real."; "el Hub no responde" →
"OpenCode no responde"; "Workspace (Hub)" → "Workspace"; "recortado por el Hub" →
"recortado por OpenCode". Mantener los `strings` si ya están en `strings.xml`.
**Los tests que comparan textos** (`FriendlyErrorTest`, `BootstrapViewModelTest`, etc.) se
actualizan en el mismo commit.

**T-F1.4 — `parseDeliveryError` real.** Primero captura 3 cuerpos de error **reales** de OpenCode
(modelo inexistente, sesión inexistente, servidor sin credenciales: `curl -i -u opencode:PASS …`)
y guárdalos en `app/app/src/test/resources/errores/*.json`. Reescribe `parseDeliveryError`
(sácalo de `ChatViewModel` a `data/ErroresRed.kt`) para entender esas formas **y** el sobre viejo
como respaldo. Tests con los 3 fixtures + vacío + HTML + no-JSON.

**T-F1.5 — Documentación y CI muertas** (H-18):
- `README.md`, `docs/ARCHITECTURE.md`, `app/README.md`: reescribir a la realidad (app Compose →
  OpenCode `:49374` directo, `ProjectsStore`, chroot, lanzador). Borrar secciones de
  `backend/`, `agents/`, `server.js`, `keepalive.sh`, endpoints `/api/device/*`, "Hub 8765".
- `app/docs/FRONTEND_CONTRACT.md` → sustituir por `docs/CONTRATO-OPENCODE.md` generado **desde
  `OpenCodeApi.kt`**: tabla `método | ruta | request | response | quién lo usa`. (El script
  `tools/chk_forma.py` ya conoce los tipos; reutiliza su lista de endpoints.)
- `.github/workflows/build-apk.yml`: el job `backend-checks` solo imprime un aviso; eliminarlo y
  quitar `needs: backend-checks`. Mantener `lint`, `build-debug`, `build-release`, `semgrep`,
  `gitleaks`, `instrumented`.
- `app/state/*.json` **no se tocan** (fuera de alcance, ver §8).

**Aceptación F1:** CI verde; `grep -rniE "\bhub\b" app/app/src/main` solo devuelve comentarios
históricos justificados o 0; E9 cumplido; el APK abre chat, envía y lista proyectos igual que antes
(V-01, V-02, V-07). **Vuelta atrás:** un `git revert` por commit (los renombres van aparte de las
eliminaciones).

---

### F2 — Crear sesión: un solo camino, vínculo atómico, errores con motivo

**Objetivo:** que crear un chat (global o en proyecto) tenga **una** implementación, deje la sesión
completa (carpeta, agente, modelo, vínculo) y nunca falle en silencio ni duplique.

**T-F2.1 — `Resultado` y `SesionesRepo`.**
Crear `data/repo/Resultado.kt` (ver §3) y `data/repo/SesionesRepo.kt`:

```kotlin
data class NuevaSesion(
    val titulo: String,
    val proyectoId: String? = null,   // si hay proyecto, carpeta y vínculo salen de él
    val carpeta: String? = null,      // solo si no hay proyecto
    val agente: String? = null,       // null → AGENTE_POR_DEFECTO
    val modelo: OpenCodeModelRef? = null // null → modelo del agente
)
data class SesionCreada(val id: String, val titulo: String, val carpeta: String?)

class SesionesRepo(
    private val oc: OpenCodeApi,
    private val store: ProjectsStore,
    private val config: SesionConfigRepo,      // F3; en F2 puede ser un adaptador sobre fijarModelo
) {
    suspend fun crear(req: NuevaSesion): Resultado<SesionCreada>
    suspend fun renombrar(id: String, titulo: String): Resultado<Unit>
    suspend fun borrar(id: String): Resultado<Unit>
    suspend fun vincular(id: String, proyectoId: String, titulo: String?): Resultado<Unit>
    suspend fun desvincular(id: String, proyectoId: String): Resultado<Unit>
}
```

Reglas de `crear` (todas con test):
1. **Carpeta:** una única función `carpetaDeProyecto(p: Project): String?` =
   `p.folder` no vacía → `p.resolvedFolder` (ya sin regex en caliente, F7) → `null`.
   **Se elimina** la derivación `/sdcard/projects/<nombre>` de `ProjectDetailViewModel`.
2. Llamar **una vez** a `oc.createSession(CreateOpenCodeSessionRequest(title, location))`. Si lanza o
   `data == null` → `Fallo("No se pudo crear la sesión: <causa legible>")`. **Sin reintentos
   automáticos** (evita H-03).
3. Con el id ya creado, los pasos siguientes **no pueden convertir el éxito en fallo**: agente,
   modelo y vínculo se intentan cada uno y, si fallan, se añaden a `avisos`
   (`"No se pudo fijar el agente X"`, `"Sesión creada pero no vinculada al proyecto"`).
   Devolver `Ok(sesion, avisos)`.
4. Vínculo: una sola llamada a `store.linkSessionToProject(id, proyectoId)` + `setSessionTitle`.
   `RutaNativa.linkSession` pasa a delegar en el repo (o se elimina el doble paso en los VMs; lo
   importante: **una sola escritura**).
5. Idempotencia: aceptar un `claveIdempotencia` opcional (p. ej. `titulo+proyectoId` en ventana de
   3 s) para ignorar doble pulsación del botón "+". Test: dos `crear` seguidos → 1 petición.

**T-F2.2 — Sustituir los 4 sitios** (H-01):
- `ChatViewModel.createNewSession` → `sesionesRepo.crear(NuevaSesion("Nuevo chat"))`.
- `MainViewModel.createSessionForProject` y `createSessionViaHub` → una función que llama al repo
  y publica `_error` con `Fallo.motivo` y los `avisos` (como snackbar informativo).
- `ProjectDetailViewModel` (crear + primer mensaje) → repo; el primer mensaje solo se envía si
  `Ok`; si falla el envío, `_error = "Sesión creada pero el primer mensaje no se envió: …"`
  (ya existe ese texto: conservarlo).
- `SetupNative` (smoke test) → mantener `openCodeApi.createSession` **pero** borrar la sesión de
  prueba al terminar (`VERIFICAR` si ya lo hace) para no dejar basura en la lista.
- `RutaNativa.createSession` pasa a ser una **fachada** que llama a `SesionesRepo.crear` y mapea a
  `Envelope` **con `error`** (`envolturaFallo(motivo)`), para no romper llamadores que queden.

**T-F2.3 — Cerrar la costura saltada** (H-04). `MainViewModel`: `renameSession`, `deleteSession`,
`getActiveSessions` pasan por `SesionesRepo`/`api.getInflight`. Eliminar la propiedad
`openCodeApi` del VM. Renombrar: actualizar `ProjectsStore.setSessionTitle` **solo** si el servidor
confirmó (hoy se escribe antes y se pierde el rastro si el servidor rechaza).

**T-F2.4 — Limpieza de vínculos duplicados.** `moveSession` y `createSessionForProject`: una sola
llamada a `SesionesRepo.vincular`. Quitar los `try { } catch (_: Exception) {}` mudos: el `Fallo`
va a `_error`.

**Tests nuevos** (JVM, con `fake` de `OpenCodeApi` y `ProjectsStore` en memoria; ya hay patrón en
`SesionModeloSyncTest`/`ProjectsStoreVinculoTest`):
- crear global → 1 `createSession`, agente por defecto fijado, modelo del agente fijado.
- crear en proyecto con `folder` distinto del nombre → `location.directory == folder`.
- `setSessionAgent` lanza → `Ok` con aviso; **no** hay segundo `createSession`.
- `oc.createSession` lanza → `Fallo` con motivo, 0 sesiones, 0 vínculos.
- vínculo falla → `Ok` con aviso "no vinculada".
- doble pulsación → 1 sesión.
- renombrar: servidor rechaza → título local **no** cambia.

**Aceptación F2:** 0 usos de `createSessionViaHub`; un único `createSession(` en main fuera del
repo (más el smoke test); V-04, V-05 del checklist; contador de F0 muestra 1 `POST api/session`
por creación. **Vuelta atrás:** `RutaNativa.createSession` conserva su firma; revertir el commit
de sustitución de VMs restaura el comportamiento anterior sin tocar datos.

---

### F3 — Modelo y agente: servidor como única verdad

**Objetivo:** eliminar el chip erróneo y las carreras. Prefs pasan a ser **caché**.

**T-F3.1 — `SesionConfigRepo`.**

```kotlin
data class ConfigSesion(
    val sessionId: String,
    val titulo: String?,
    val modelo: String?,      // normalizado (sin prefijo "opencode/")
    val agente: String?,
    val origen: Origen        // SERVIDOR | CACHE_LOCAL (servidor inaccesible)
) { enum class Origen { SERVIDOR, CACHE_LOCAL } }

class SesionConfigRepo(oc: OpenCodeApi, cache: CacheLocalConfig, catalogo: CatalogoRepo) {
    /** UN solo GET /api/session/{id}: título + modelo + agente. */
    suspend fun leer(sid: String): ConfigSesion
    /** Servidor primero; cache SOLO si el servidor confirma. Devuelve Fallo con motivo si no. */
    suspend fun fijarModelo(sid: String, ref: String, variantExplicita: String? = null): Resultado<Unit>
    suspend fun fijarAgente(sid: String, nombre: String): Resultado<Unit>
}
```
- `CacheLocalConfig` envuelve `ModelPreferences`/`AgentPreferences` (se conservan sus claves para
  no perder datos de usuarios existentes). Semántica nueva: **escribe tras leer o tras
  confirmación del servidor**; se **lee** solo si el servidor falla (`Origen.CACHE_LOCAL`) o para
  el "último usado" que precarga una sesión nueva.
- `leer` reemplaza `getSessionModel` + `getSessionAgent` + búsqueda de título en
  `getOpencodeSessions` (H-07): una petición.
- `fijarModelo` reutiliza `RutaNativa.resolveProviderFor/resolveVariantFor/normalizarIdModelo`
  (moverlas a `CatalogoRepo`/objeto puro `ModelosUtil`, **sin cambiar su lógica**: tienen tests).

**T-F3.2 — Estado de config en el ViewModel sin carreras** (H-06). En `ChatViewModel`:
- Un único `private var configJob: Job?`. `cargarConfig(sid)` cancela el anterior, y **al volver
  de `leer` comprueba `if (_currentSessionId.value != sid) return`** antes de escribir estado.
- Un único `StateFlow<ConfigUi>` (`Cargando | Lista(config) | Error(motivo)`) en lugar de
  `_selectedModel` + `_agentMode` sueltos. La UI (chips) lee de ahí.
- `selectModel(id)`: **optimista con reversa**: guarda `previo`, pinta el nuevo, llama
  `fijarModelo`; si `Fallo` → restaura `previo` y `_error = motivo`. Nada de `catch {}`.
- `selectAgent(nombre)`: igual, y además **fija el modelo del agente** si el usuario no eligió
  modelo manualmente en esta sesión (`modeloDelAgente`, que ya existe).
- Quitar el no-op de `load()` (`if (isNullOrBlank()) = null`).

**T-F3.3 — Fijar modelo en el envío sin repetirlo.** `sendMessage` llama a `fijarModelo` antes de
cada prompt (necesario porque el prompt no acepta modelo). Optimización segura:
`SesionConfigRepo` mantiene `ultimoFijado[sid] = "provider/modelo#variante"` y **omite** el POST si
el valor coincide **y** la lectura de F3.1 fue hace < 60 s. Se invalida al recibir un evento de
sesión externo (F5) o al cambiar de chat. `DECISIÓN` (por defecto: **implementar**, tras flag
`OMITIR_FIJADO_REDUNDANTE = true`; si en V-03 aparece un chip desfasado, poner `false`).

**Tests nuevos:**
- carrera: `leer(A)` lento, `cargarConfig(B)` rápido → el estado final es el de B (usar
  `StandardTestDispatcher` y `advanceUntilIdle` en orden inverso).
- `fijarModelo` falla → estado vuelve al previo, prefs sin cambios, `_error` con motivo.
- servidor caído → `Origen.CACHE_LOCAL` y se muestra aviso discreto "sin conexión".
- una sola petición a `/api/session/{id}` al abrir (contador).
- cambio de agente sin modelo manual → modelo del agente aplicado; con modelo manual → no se pisa.

**Aceptación F3:** E6 (V-03: 10 cambios rápidos de sesión sin chip erróneo), 0 `catch (_: Exception)`
en `selectModel`/`selectAgent`, contador de F0: `GET api/session/:id` ×1 al abrir.
**Vuelta atrás:** las claves de `SharedPreferences` no cambian → revertir el commit es seguro.

---

### F4 — Menos peticiones al abrir y al enviar

**T-F4.1 — `CatalogoRepo` con caché.** Modelos y agentes en memoria con TTL (5 min) y
`Mutex` para que dos llamadas simultáneas compartan una sola petición ("single-flight").
Invalidación: manual desde la pantalla de modelos ("refrescar") y al fallar un `setSessionModel`
con "modelo no encontrado". Consumidores: `loadModels`, `loadAgents`, `fijarModelo`,
`getModels`, `createSession` (H-08). Calcular `esFree` **una vez** al cachear, no por llamada.

**T-F4.2 — `load()` mínimo.** Secuencia objetivo al abrir un chat existente:
1. `SesionConfigRepo.leer(sid)` (1 petición: título + modelo + agente).
2. `getMessagesTail` con la cola (no el historial completo) → pintar ya.
3. Catálogo desde caché (0 peticiones si está caliente).
4. El historial anterior se carga **bajo demanda** al hacer scroll hacia arriba
   (`getMessages` con `cursor`/`limit`; `OpenCodeApi` ya expone `LIMITE_MAX_MENSAJES` y
   `ORDEN_COLA`; `VERIFICAR` si el endpoint soporta `before`/cursor y capturar la forma real con
   `chk_forma.py`). Si no lo soporta, mantener la carga completa pero **después** de pintar la cola.
Quitar de `load()` la llamada a `getOpencodeSessions` (el título viene de `leer`).

**T-F4.3 — Primer mensaje sin trabajo extra.** En el envío, eliminar la lectura completa
`api.getMessages(...)` del `pollingJob` de `sendWithFiles` (se sustituye por F5; mientras tanto,
cambiarla por `getMessagesTail` + `mergeTail`, que ya existen y fueron medidos 0,27 s vs 15,63 s).

**Aceptación F4:** E3 (≤ 3 peticiones al abrir con catálogo caliente; ≤ 4 en frío). Comparar con
`PERF-BASELINE.md`. **Vuelta atrás:** revertir; no cambia datos.

---

### F5 — Un solo canal de sincronización (SSE + reconciliación + respaldo por poll)

> **Fase de mayor riesgo.** Trabajar tras flag `SYNC_POR_EVENTOS` (por defecto `false` hasta pasar
> V-06/V-08 en el móvil), conservando el bucle antiguo como `SyncPorPoll`. Fusionar el flag a
> `true` solo cuando el checklist pase 3 días seguidos de uso real.

**T-F5.0 — Capturar la verdad antes de diseñar.** Con un turno real (texto, un `bash`, un
formulario, un permiso) captura 90 s de `GET /api/event`:
`curl -N -u opencode:<pass> http://127.0.0.1:49374/api/event | tee eventos.ndjson`
Guardar en `app/app/src/test/resources/eventos/turno-*.ndjson` (**sin credenciales ni contenido
privado**; revisar a mano). Documentar en `docs/CONTRATO-OPENCODE.md` la lista **real** de tipos
de evento. Hoy el código solo conoce con certeza `session.text.delta`, `session.text.ended`
(`EventStream`), y por el CHANGELOG/ADR-003 `session.execution.started`, `session.execution.<fin>`
(`succeeded`) y `session.inbox.delivered`. **No inventes tipos**: lo que no esté en el
ndjson capturado no se usa.

**T-F5.1 — `EventosServidor` (conexión única).** Objeto/servicio con **una** conexión SSE a
`/api/event` mientras la app esté en primer plano (`onStart`/`onStop` de `MainActivity` ya
distinguen `isForeground`), expuesta como `SharedFlow<OpenCodeServerEvent>` (`replay = 0`,
`extraBufferCapacity` acotado, `onBufferOverflow = DROP_OLDEST`). Reconexión con backoff
(1 → 2 → 4 → 8 → máx 15 s) y estado `StateFlow<Conexion>` (`Conectado | Reconectando | Caido`).
Reaprovecha el parser de `EventStream` (no reescribir): extraer su `parse`/acumulador a funciones
puras testeables con los `.ndjson` de T-F5.0.

**T-F5.2 — `ChatSync` (por chat abierto).**

```kotlin
class ChatSync(scope: CoroutineScope, api: ApiService, eventos: EventosServidor) {
    val mensajes: StateFlow<List<Message>>
    val textoEnVivo: StateFlow<String?>
    val turno: StateFlow<EstadoTurno>            // Ocioso | Ocupado | Terminado(seq)
    val formularios: StateFlow<List<PendingForm>>
    val permisos: StateFlow<List<PendingPermission>>
    val estado: StateFlow<EstadoSync>            // PorEventos | Respaldo | SinServidor
    fun abrir(sid: String)    // cancela el anterior; mismo guardia "sid actual" que F3
    fun cerrar()
    suspend fun refrescarAhora()  // para pull-to-refresh y tras enviar
}
```
Comportamiento:
- **Con SSE conectado:** los eventos de la sesión actual actualizan `turno` y `textoEnVivo`.
  Reconciliación: `getMessagesTail` (a) al abrir, (b) al llegar `session.execution.<fin>`,
  (c) cada 15 s de seguridad, (d) tras reconectar SSE. Formularios/permisos: refrescar al abrir,
  al terminar turno y cada 5 s **solo mientras `turno == Ocupado`** (`VERIFICAR` si hay eventos
  propios en el ndjson; si los hay, usarlos y quitar este poll).
- **Con SSE caído:** `Respaldo` = el bucle actual (cola + inflight + forms + permisos) con backoff
  2 s → 10 s. Es el comportamiento de hoy, no una novedad.
- **Un solo escritor** de `_messages`: `ChatSync`. El envío ya no tiene su propio `pollingJob`; el
  mensaje optimista se inserta en `ChatSync` y se reconcilia por id.

**T-F5.3 — Señal de turno fiel (H-10).** `EstadoTurno` se alimenta de `session.execution.*` (los
mismos que usaba el vigilante del Hub, ver ADR-003). `RutaNativa.getInflight` queda solo como
**respaldo** y su semántica degradada se documenta en el KDoc. El divisor "✓ respuesta final" y
`TurnNotifier` leen de `turno`, no de `info.time.completed` (ADR-003 ya lo exige).

**T-F5.4 — Migrar `ChatViewModel`.** Sustituir `startViewRefresh`, `pollingJob`, `sseSuccess`,
`sseRequestAccepted`, `streamingSince`, `inFlightSendKey` por `ChatSync`. `sendWithFiles` queda en:
validar → insertar optimista → `api.sendMessage` → si `Fallo` marcar `ERROR` con motivo →
`chatSync.refrescarAhora()`. **Conservar** la cola de mensajes (`ColaDeMensajes`, tests existentes)
y la guarda anti doble envío.

**Tests nuevos** (JVM puro, sin dispositivo):
- reproducir `turno-*.ndjson` → secuencia exacta de `EstadoTurno`.
- SSE cae a mitad de turno → pasa a `Respaldo`, no pierde mensajes, vuelve a `PorEventos` al reconectar.
- evento de otra sesión no altera el estado.
- cambiar de chat durante una respuesta tardía → no contamina (mismo patrón que F3).
- mensaje optimista + llegada por evento + reconciliación → 1 mensaje, no 2.
- `TurnStateTransitionTest` y `FormaMensajesTest` existentes **siguen en verde sin editarlos**.

**Aceptación F5:** E4 (≤ 1 petición/15 s en reposo), V-06 (turno largo con `bash`), V-08
(matar y levantar el servidor con el chat abierto: se recupera solo sin reabrir). **Vuelta
atrás:** flag `SYNC_POR_EVENTOS = false` restaura el bucle anterior sin revertir commits.

---

### F6 — Arranque del servidor: un solo lanzador, un solo estado

**Objetivo:** E2 (siempre 1 proceso `serve`), y que `MainActivity` deje de orquestar.

**T-F6.1 — Versionar el script de arranque.** Traer del dispositivo
`/data/adb/service.d/99-aegis-opencode.sh` a `app/scripts/service.d/99-aegis-opencode.sh`
(**no inventes su contenido**: cópialo tal cual con `su -c cat`, revisa que no contenga
contraseñas/tokens antes de commitear). Añadir `bash -n` en el job `lint` (ya revisa `app/**/*.sh`).

**T-F6.2 — Un único script `aegis-serve.sh`** (versionado en `app/scripts/`, desplegado por el
instalador `install-su.sh` a una ruta fija del chroot/`/data/local/tmp`), con **toda** la lógica:
montajes idempotentes, guarda anti-duplicado, `oom_score_adj=-1000`, lanzamiento con `nohup`.
Tanto el hook de boot como `OpenCodeLauncher` lo **invocan**; el lanzador Kotlin deja de contener
la cadena de montajes y el `chroot … serve --service` (hoy duplicada con el script).
La guarda usa el patrón con corchete para no autocoincidir (H-11):

```sh
# cuenta procesos serve vivos SIN contarse a sí mismo ni al sh -c que lo ejecuta
n=$(pgrep -f '[o]pencode serve --service' | wc -l)
[ "$n" -gt 0 ] && { echo "YA_HAY:$n"; exit 0; }
```
`VERIFICAR` en el móvil, antes y después: `pgrep -f 'opencode serve --service'` (sin corchete)
dentro de `su -c` vs con corchete; documentar el resultado en el commit.
Para evitar una condición de carrera entre boot y app (ambos lanzan a la vez), usar un `flock`
sobre un fichero en `/data/local/tmp` **si está disponible** (`VERIFICAR`: la auditoría anota que
`flock` falla en FUSE/sdcard; el lock debe vivir en `/data/local/tmp`, no en `/sdcard`).

**T-F6.3 — `ServidorOpenCode`** (reemplaza la orquestación de `MainActivity`):

```kotlin
sealed interface EstadoServidor {
    object Comprobando : EstadoServidor
    object Listo : EstadoServidor
    data class Arrancando(val desde: Long, val intento: Int) : EstadoServidor
    data class Error(val motivo: String) : EstadoServidor
}
object ServidorOpenCode {
    val estado: StateFlow<EstadoServidor>
    fun asegurar()   // idempotente; si ya hay una corrutina en curso, no lanza otra
}
```
- Una sola corrutina `asegurar`: sondeo HTTP → si no responde, `aegis-serve.sh` → espera con
  backoff hasta 3 min (el plazo actual) → `Listo` o `Error(motivo)` con el motivo del script.
- `MainActivity` solo **observa** `estado` y pinta `NativeOfflineOverlay`. Se eliminan
  `checkHubOnStart`, `startRootSystemAndPoll`, `pasoHubRetirado`, `anotarArranqueSinHub`.
- `asegurar()` se llama desde `onStart` **solo si** `estado != Listo` (no en cada `onResume`).
- Tests: con `shell` y `comprobarSiVivo` inyectables (ya lo son en `asegurarAbierto`):
  vivo → no lanza; proceso arrancando → no lanza; ausente → lanza una vez; dos `asegurar()`
  concurrentes → un solo lanzamiento.

**Aceptación F6:** V-09 (arranque en frío ×3, abrir app ×5, matar servidor y reabrir): `pgrep`
siempre 1 y RAM sin segundo proceso de ~660 MB. **Vuelta atrás:** el script antiguo del
dispositivo sigue intacto hasta que el usuario lo reemplace; conservar copia `.bak`.

---

### F7 — `su`, hilos y regex

**T-F7.1 — Skills en un solo `su`** (H-12). `RutaNativa.getSkills`: reemplazar `ls` + N `head`
por una sola ejecución que emita todos los `SKILL.md` con delimitadores:

```sh
cd '<base>' 2>/dev/null || exit 0
for d in */; do
  n="${d%/}"; [ -f "$n/SKILL.md" ] || continue
  printf '@@AEGIS_SKILL@@%s\n' "$n"; head -c 200000 "$n/SKILL.md"; printf '\n'
done
```
Parseo por el delimitador (constante privada). Tope total de salida (p. ej. 2 MB) para no
reventar memoria. Igual para `getSystemSkills` (`ls` + N `test -f` → la misma ejecución) y
`getSystemHealth` (4 ejecuciones → 1 con `cat /proc/uptime; echo @@; cat /proc/meminfo; echo @@; …`).
Tests existentes (`SkillsNativosTest`) deben seguir verdes; añadir test con salida simulada con
delimitadores, con un skill sin `SKILL.md` y con contenido que contenga el delimitador (se debe
tratar con el primero que aparezca al inicio de línea).

**T-F7.2 — Caché corta.** `SkillsRepo`/`Health`: TTL 10 s para Skills y 5 s para Health, con
invalidación al crear/borrar/editar. Evita que volver a una pantalla repita el `su`.

**T-F7.3 — Un solo punto de `su`.** Hoy `sh()` en `RutaNativa` mueve a IO. Mover ese punto a un
objeto `Raiz` (`suspend fun ejecutar(cmd, timeout)`) con un `Mutex` o `Semaphore(2)` para no
lanzar 10 procesos `su` a la vez, y que `OpenCodeLauncher`, `BootstrapNative`, `SetupNative`
lo usen (hoy llaman a `RootShell` por su cuenta). Con el `check` de hilo de F0.4 en debug.
`VERIFICAR` si una sesión `su` persistente (un `Process` que lee/escribe comandos) es viable con
Magisk; **no** implementarla en esta ronda salvo que T-F7.1 no baste (E8).

**T-F7.4 — Regex fuera de lo caliente** (H-13). Mover a `private val RX_… = Regex(…)` de nivel
de archivo o `companion`:
- `Project.resolvedFolder` → `private val RX_NOMBRE_CARPETA` (y cachear el resultado con `by lazy`
  si el `data class` lo permite; si no, función `fun carpetaDe(name)` fuera del getter).
- `RutaNativa.getSystemHealth`: `"\\s+".toRegex()` → `split(' ', '\t')` filtrando vacíos, sin regex.
- `ChatViewModel.isTechnicalTitle` y `ChatScreen` (duplicadas) → **una** función en `util/`
  (`esTituloTecnico`) con `RX_ID` compilado una vez; borrar la copia.
- `SubagentCard` (2) y `ProjectsScreen` (1) → constantes.
- `MarkdownText`: **primero medir**: `VERIFICAR` cuáles están dentro de `remember(texto){…}` o de
  `AnnotatedString` por línea. Los que se evalúan por línea/token (`toolMatch`, regex de listas,
  `token.matches(Regex(…))`) → constantes de archivo. `MarkdownBlockCountTest` y
  `MarkdownTablaTest` deben seguir verdes sin cambios.

**T-F7.5 — Guardia automática.** Script `tools/check_hilo_principal.py`, enganchado a CI (job
`lint`), que falla si:
1. aparece `Regex(`, `.toRegex()` o `Pattern.compile(` dentro de un cuerpo de función en `ui/` o
   `data/` que **no** esté precedido por `private val`/`val` de nivel superior/companion;
2. aparece `RootShell.exec(` fuera de `Raiz`/`RutaNativa.sh`/`OpenCodeLauncher`;
3. aparece `catch (_: Exception) {}` **con cuerpo vacío** en `ui/viewmodel/` (E7).
Con lista de excepciones comentada en el propio script. Documentar como el resto de `tools/`.

**Aceptación F7:** E8 (1 `su` al abrir Skills; verlo en debug con el log de `Raiz`), E1 tras 1 h
de uso con StrictMode sin avisos de main-thread. **Vuelta atrás:** cada tarea es un commit
independiente.

---

### F8 — Reorganizar modelos de datos (mecánico)

**Objetivo:** que cada tipo viva donde corresponde y los nombres dejen de confundirse (H-16).
**Solo mover/renombrar; cero cambio de comportamiento.** Cada paso compila solo.

**T-F8.1 — Trocear `Models.kt`** en `data/modelos/`: `ModelosChat.kt` (Message, MessagePart,
MessageInfo, ToolState…), `ModelosProyecto.kt` (Project, SessionRef, OpencodeSession…),
`ModelosSetup.kt` (Bootstrap*, SetupCheck*, FinalCheck*, SmokeTest*, AuthGuide*),
`ModelosInteraccion.kt` (PendingForm, PendingPermission, FormReplyBody…). Mismo paquete
(`com.aegis.hub.data`) para no tocar ningún `import`.

**T-F8.2 — Nombres.** Renombrar los tipos de dominio para que no choquen visualmente con los
wire: `OpencodeSession` → `Sesion`, `OpencodeAgent` → `Agente`, `ModelOption` → `ModeloElegible`;
los de `NativeModels.kt` conservan el prefijo `OpenCode…` (son el cable). Hacerlo **un tipo por
commit**, con rename del IDE; `FormaNativaTest`/`NativeMapperTest` protegen el mapeo.

**T-F8.3 — Tipar `Sesion.model: Any?`.** Sustituir por `ModeloRef?` (`id`, `providerID`,
`variant`) y mapear en `RutaNativa`/`NativeMapper`. Solo después de que F3 haya eliminado los
sitios que leían `model` como `Any`. `chk_forma.py` debe seguir en 0 incompatibles.

**T-F8.4 — Retirar `ModelsHub.kt` y `Response<…>`.** Con F1 hecho, los pocos tipos que sobreviven
(Bootstrap, SkillItem, ProjectItem, WorkflowItem…) pasan a `ModelosSetup.kt`/`ModelosProyecto.kt`
y las pantallas dejan de recibir `retrofit2.Response`: el repo devuelve `Resultado<T>` (F2).
**DECISIÓN:** ¿se mantienen las pantallas Workspace/Workflow/ControlCenter/SkillManager?
Por defecto **se mantienen** (no se eliminan funcionalidades en una ronda de estabilización).

**Aceptación F8:** 209 tests en verde sin modificar aserciones (solo nombres de tipos), APK
idéntico en comportamiento (V-01…V-07). **Vuelta atrás:** un revert por commit.

---

### F9 — Partir los objetos dios (después de estable)

**Condición de entrada:** F1–F7 fusionadas y 3 días de uso real sin ANR ni regresiones.
**Método:** una extracción por commit; el archivo original sigue exportando la misma API hasta el
último paso (fachada), de modo que ningún llamador cambia hasta entonces.

**T-F9.1 — `RutaNativa` (1934 líneas) → repos por dominio**, en `data/repo/`:
`SesionesRepo` (F2), `SesionConfigRepo` (F3), `CatalogoRepo` (F4), `ProyectosRepo`
(`getProjects/createProject/patchProject/deleteProject`), `SkillsRepo` (F7), `SistemaRepo`
(`getSystemHealth`, bootstrap, final-check, smoke, auth-guide), `InteraccionRepo`
(formularios, permisos), `MensajesRepo` (`getMessages*`, `getPart`, `sendMessage`).
`RutaNativa` queda como fachada delgada que delega; cuando ningún VM la use por su nombre,
`ApiService` se **sustituye por interfaces pequeñas** inyectadas (y se borra la fachada).
Los tests de `RutaNativa*` se mueven con su código (`git mv`, sin editar aserciones).

**T-F9.2 — `ChatViewModel` (1378 líneas)** → `ChatViewModel` (estado de pantalla y navegación),
`ChatSync` (F5), `ConfigSesionUi` (F3), `EnvioMensajes` (cola, reintentos, adjuntos,
`sendWithFiles`), `TurnoTracker` (`turnIsReallyFinished`, `announceFinishedTurnIfAny`,
`TurnNotifier`). Estados expuestos con los mismos nombres hasta el final.

**T-F9.3 — `ChatScreen` (2047 líneas)** → carpeta `ui/chat/`: `ChatTopBar`, `ListaMensajes`
(`LazyColumn`, claves **nunca `hashCode()`**, decisión 3 de `.ponytail.md`), `ItemMensaje`,
`Compositor` (campo + adjuntos + enviar), `HojaModelos`, `HojaAgentes`, `TarjetaFormulario`,
`TarjetaPermiso`, `BotonVolverAlFinal`. Pasar a cada uno solo el estado que usa (evita
recomposiciones masivas). `python3 tools/check_composable.py` tras **cada** extracción
(es justo el fallo que ese script evita).

**T-F9.4 — `MarkdownText` (1014) y `SetupWizardScreen` (865):** misma técnica, solo si el tiempo lo
permite; no son bloqueantes de estabilidad.

**Aceptación F9:** ningún archivo de `ui/` o `data/` > 700 líneas (meta, no bloqueo), tests igual
de verdes, V-01…V-09 sin cambios. **Vuelta atrás:** por commit.

---

### F10 — Tests de flujo, CI y documentación (transversal)

**T-F10.1 — Servidor falso de OpenCode.** Añadir `testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")`
(misma versión que `okhttp`). Un `FakeOpenCode` que sirva las rutas de `OpenCodeApi` con las
**formas reales** (fixtures capturados con `chk_forma.py`), con modos: normal, lento, 401, 500,
cuerpo envuelto `{"data":…}`, y SSE con los `.ndjson` de F5.

**T-F10.2 — Un test de flujo por camino crítico** (corren en JVM, sin emulador):

| Test | Verifica |
|---|---|
| `FlujoAbrirChatTest` | ≤ 3 peticiones; título/modelo/agente del servidor; caché usada si el servidor cae |
| `FlujoEnviarMensajeTest` | fijar modelo → prompt → respuesta por SSE; error del servidor visible con motivo |
| `FlujoCrearSesionProyectoTest` | carpeta real, agente, modelo, vínculo; un solo `POST /session` |
| `FlujoCambiarModeloTest` | éxito persiste; fallo revierte el chip |
| `FlujoReconexionTest` | SSE cae y vuelve; sin mensajes duplicados |
| `FlujoSkillsTest` | 1 ejecución de shell (con `shell` falso contando llamadas) |

**T-F10.3 — Documentación viva.** `CHANGELOG.md` (versión `1.2.0`), `app/.ponytail.md`
(arquitectura nueva + decisiones "no volver a discutir": servidor = verdad, un canal, un
camino de creación), `docs/ARCHITECTURE.md` con el diagrama de §3, y **actualizar el
diagrama Mermaid de `docs/AUDITORIA-2026-10-06.md`** en un nuevo `docs/AUDITORIA-<fecha>.md`
(las auditorías son crónicas: **no se reescriben**, se añade una nueva).

**T-F10.4 — QA.** Actualizar `docs/qa/QA_CHECKLIST.md` con §6 de este plan.

**Aceptación F10:** CI ≥ 209 + nuevos tests en verde; `check_hilo_principal.py` en `lint`.

---

## 5. Métricas de éxito (rellenar en `PERF-BASELINE.md`, columna "después")

| Métrica | Antes (F0) | Meta | Después |
|---|---|---|---|
| Peticiones al abrir un chat existente | _medir_ | ≤ 3 | |
| Peticiones/min con chat abierto en reposo | _medir_ | ≤ 4 | |
| Peticiones al enviar y recibir una respuesta corta | _medir_ | ≤ 8 | |
| `GET /api/model` por sesión de uso de 10 min | _medir_ | ≤ 2 | |
| `su` al abrir Skills | _medir_ | 1 | |
| Procesos `serve` tras 5 aperturas | _medir_ | 1 | |
| Archivos > 700 líneas en `ui/`+`data/` | 6 | ≤ 2 (tras F9) | |
| `catch` vacíos en `ui/viewmodel/` | _medir_ | 0 | |
| ANR en 1 h de uso | _medir_ | 0 | |

---

## 6. Checklist manual en el móvil (por fase, antes de fusionar a `master`)

Marcar en `docs/PLAN-ESTABILIZACION-ESTADO.md`. Se hace con el APK instalado por `pm`
(desinstalar el debug anterior primero) y `adb logcat -s AegisTrace AegisChat OpenCodeLauncher`.

- **V-01 Abrir chat existente** (con y sin historial largo): carga rápida, chip de modelo y agente
  correctos, sin spinner eterno.
- **V-02 Enviar mensaje**: fases Enviando → Generando, respuesta completa, divisor "✓ respuesta
  final" solo al final del turno entero (no tras cada `bash`).
- **V-03 Cambiar de chat 10 veces seguidas** (alternando dos chats con modelos distintos): el chip
  nunca muestra el modelo/agente del otro. Cambiar modelo a uno inexistente/no disponible: el chip
  vuelve al anterior y aparece el motivo.
- **V-04 Crear chat nuevo global** ("+" en Chats): aparece **una** sola sesión nueva con agente y
  modelo; pulsar "+" dos veces seguidas no crea dos.
- **V-05 Crear chat dentro de un proyecto vinculado a una carpeta con nombre distinto**: la sesión
  nace en esa carpeta, aparece en la lista del proyecto y no en "sin proyecto"; si falla algo,
  hay un mensaje con motivo (probar con el servidor detenido).
- **V-06 Turno largo** (pedir algo con varios `bash` y un formulario/permiso): "trabajando…"
  visible hasta el final real; formulario y permiso respondibles; notificación de fin una sola vez.
- **V-07 Proyectos**: lista, abrir, renombrar y borrar sesión, vincular/desvincular; Skills,
  Workspace, Workflow, Control Center cargan sin ANR.
- **V-08 Servidor caído/levantado con el chat abierto:** banner de sin conexión, y al volver el
  servidor el chat se recupera solo (sin reabrir).
- **V-09 Arranque:** reinicio del teléfono → abrir app (1 proceso `serve`); matar `serve` → abrir
  app (se relanza uno); abrir y cerrar la app 5 veces (sigue 1).
- **V-10 TTS y segundo plano:** leer una respuesta en voz alta, salir de la app y volver; sin ANR.
- **V-11 Memoria:** `dumpsys meminfo com.aegis.hub` tras 30 min de uso: sin crecimiento sostenido.

---

## 7. Riesgos y mitigaciones

| Riesgo | Mitigación |
|---|---|
| F5 cambia el corazón del chat y no hay emulador en el host | Flag `SYNC_POR_EVENTOS`, `SyncPorPoll` intacto, tests con `.ndjson` reales, 3 días de uso antes de activar |
| Borrar métodos "muertos" que se usan por reflexión/JSON | Grep en main+test+androidTest y **control negativo**; commits por grupo |
| Retirar campos de `Models.kt` rompe `projects-store.json` ya guardado | Conservar campos persistidos como ignorados (T-F1.2); test de lectura de un JSON antiguo (`ProjectsStoreTest`) |
| Renombrar textos de UI rompe tests de strings | Actualizar tests en el mismo commit; CI lo detecta |
| El script `pgrep` con corchete no se comporta igual en el `sh` del dispositivo | Probar en el móvil antes/después (T-F6.2) y guardar el resultado |
| La CI tarda ~9 min y alienta commits grandes | Regla 1 de §0: un push por tarea |
| Pérdida de prefs al reinstalar debug (clave desechable) | F3 hace del servidor la verdad: prefs perdidas ya no rompen el chip |
| Contratos de OpenCode cambian en una actualización (`v2.0.14` visto en el lanzador) | `chk_forma.py` en cada fase que toque `NativeModels` y fixtures versionados |

---

## 8. Fuera de alcance (no hacer en esta ronda)

- Firma y keystore, `docs/KEYSTORE_SETUP.md`, el job `build-release`.
- `app/state/*.json` (están versionados y los lee `ProjectsStore`/`BootstrapNative`; moverlos o
  ignorarlos en git requiere decisión del usuario y migración en el dispositivo).
- Reimplementar el vigilante de ejecuciones **como servicio en segundo plano** (solo en primer
  plano, F5).
- Paginación de más de 50 sesiones por proyecto (limitación ya documentada en `getProjectSessions`).
- Sesión `su` persistente (solo si T-F7.1 no cumple E8).
- Nuevas funciones. Esta ronda no añade features.
- `graphify-out/`, `docs/_archivo/`, `docs/audits/` (crónicas históricas).
- El plugin `.opencode/plugins/graphify-staleness.js` (la auditoría lo marca como pendiente de
  aclarar con el usuario; no lo repongas sin preguntar).

---

## 9. Anexo — comandos y plantillas

**Búsquedas de control** (úsalas como criterios de aceptación):

```sh
# ningún camino alternativo de creación de sesión
grep -rn "createSession(" app/app/src/main | grep -v "data/repo/SesionesRepo"
# vocabulario Hub fuera
grep -rniE "\bhub\b" app/app/src/main --include=*.kt
# catch vacíos en ViewModels
grep -rnE "catch \(_: Exception\) *\{ *\}" app/app/src/main/kotlin/com/aegis/hub/ui/viewmodel
# su fuera del punto único
grep -rn "RootShell\." app/app/src/main | grep -vE "data/Raiz|OpenCodeLauncher|RootShell.kt"
# regex en función (revisar a mano)
grep -rnE "Regex\(|toRegex\(|Pattern\.compile" app/app/src/main --include=*.kt
# métodos de ApiService sin uso (repetir tras cada borrado)
for m in $(grep -oE 'suspend fun [a-zA-Z]+' app/app/src/main/kotlin/com/aegis/hub/data/ApiService.kt | awk '{print $3}' | sort -u); do
  n=$(grep -rnE "\b$m\(" app/app/src | grep -vE "data/ApiService.kt|data/RutaNativa.kt" | wc -l); echo "$m $n"; done | sort -k2 -n
```

**Plantilla de commit:**

```
refactor(app): <qué> — <por qué medido>

MEDIDO <fecha>: <evidencia: traza, contador AegisTrace, test que falla antes>.
Antes: <comportamiento>. Después: <comportamiento>.
Verificado: CI <run>, V-0x en el móvil.
Hallazgo: H-xx · Tarea: T-Fy.z
```

**Plantilla de test de carrera (F3/F5):**

```kotlin
@Test fun `una respuesta tardia de la sesion A no pisa la sesion B`() = runTest {
    val a = CompletableDeferred<ConfigSesion>()
    val repo = FakeConfigRepo(mapOf("A" to a, "B" to CompletableDeferred(cfg("B", "m-b"))))
    val vm = ChatViewModelParaTest(repo)
    vm.load("A"); vm.load("B"); advanceUntilIdle()
    a.complete(cfg("A", "m-a")); advanceUntilIdle()   // llega tarde
    assertEquals("m-b", vm.config.value.modelo)
}
```

**Orden sugerido de la primera semana de trabajo:** F0 (1 día) → F1.1/F1.3/F1.5 (1 día, sin riesgo)
→ F2 completa (2 días) → F3 (1–2 días) → validar en el móvil → F4. F5–F9 en rondas siguientes,
cada una con su validación en el móvil antes de empezar la siguiente.
