# Plan: conexión directa App → OpenCode (sin Hub)

**Autor:** `kaenor-software-architect` · **Fecha:** 2026-10-01 · **Alcance:** plan, no código
**Estado del repo en el momento de escribir:** `HEAD=03dcb78`, `backend/server.js` con **1 línea sin commitear del usuario** (`aegis-context`). No se ha tocado nada.

> Todo lo que sigue está **medido hoy** contra `opencode serve --service` (PID 17879, `:49374`) y el OpenAPI real (113 rutas). Donde no supe algo está marcado **POR VERIFICAR**. Las afirmaciones del brief que **resultaron falsas** están señaladas, porque cambiar el plan.

---

## DECISION DEL USUARIO (2026-10-01) — lee esto ANTES que la seccion 5

> "el instalador tambien deberia estar en la aplicacion no en el hub. quiero que el hub se
> elimine por completo. es completamente innecesario tener un hub"

**El Hub se elimina ENTERO, y el asistente de instalacion se porta a la app.** La seccion 5 de
este plan recomendaba justo lo contrario (dejar el Hub reducido a instalador): **esa
recomendacion esta ANULADA**, y se conserva solo como explicacion de por que era mala.

Tres consecuencias que cambian el plan, no solo su texto:

1. **Se pierde la red de seguridad.** Con el Hub escuchando, si la app directa falla se vuelve
   al flag. Sin el Hub **no hay adonde volver**. El orden construir -> comprobar -> eliminar ya
   no es solo prudente: es la unica proteccion que queda.
2. **El arranque de `opencode serve` en boot pasa a ser OBLIGATORIO** (seccion 7). Es lo unico
   que pondria a OpenCode en marcha tras un reinicio, y `keepalive.sh` se borra al final.
3. **Aparecen dos trabajos nuevos** que antes se delegaban en el Hub: el instalador
   (`/api/setup/*`, `/api/bootstrap/*`) y el registro sesion<->proyecto (`/api/projects`).

---

---

## 1. Veredicto de viabilidad

**Veredicto: viable, y el Hub sobra para el 80 % de lo que la app hace — pero no es un cambio de URL: es un cambio de forma de los datos, porque el Hub traduce el shape nativo al shape que la app entiende, y esa traducción hay que escribirla en Kotlin.**

Tres correcciones al brief, medidas, que cambian el plan:

| Afirmación del brief | Medido | Efecto |
|---|---|---|
| "`/api/event` lleva `durable:{aggregateID,seq,version}`" | **FALSO hoy.** Los eventos reales son `{id,created,type,location,data}`. `durable` **no aparece**. | Se pierde la detección de huecos. Plan §8 R2. |
| "`GET /api/session/{id}` devuelve `model`/`agent` no-null" | **Cierto.** Confirmado: `agent:"orchestrator"`, `model:{id:"space-bunny-free",providerID:"opencode",variant:"default"}`. | Se elimina `ModelPreferences`/`AgentPreferences`. |
| "La password está en `/root/.local/state/opencode/service.json`" | **Cierto, pero la app no puede leer `/root`.** Ver R1. | Hay solución, medida. |

### Riesgos incómodos (los que más me preocupan, en orden)

- **R1 — La contraseña es el único obstáculo real, y el log está obsoleto.** El Hub la saca de `opencode.log`, pero **esa contraseña es vieja y da 401**; solo la de `service.json` da 200. Y `service.json` vive en un `/root` de chroot que la app no ve. **Resuelto, medido:** el mismo fichero es alcanzable desde el namespace de Android en `/data/local/ubuntu/root/.local/state/opencode/service.json` (verificado: contenido idéntico, y autentica → `200`). La app ya usa `su`, así que no es privilegio nuevo. **Riesgo residual:** si el usuario ejecuta `opencode` desde otro sitio y la ruta del chroot cambia, hay que re-resolverla.
- **R2 — No hay detector de huecos en el streaming.** El brief prometía `durable.seq`; no existe. Si la conexión SSE se corta, **no se puede saber qué deltas se perdieron** salvo re-preguntar el historial. Con `/api/session/{id}/message` (fuente de verdad) el texto se recupera siempre, pero puede haber un salto visible.
- **R3 — El shape de mensajes es incompatible, y es el trabajo real.** Nativo: `{id,time,type,agent,model,content[]}` (plano). App: `{info:{id,role,time},parts[]}`. El Hub lo traduce en `providers.js:609 _mapV2Message` + `normalizeMessage` (~60 líneas de JS con plenty of reglas: `files` en top-level, `state.content` vs `state.output` legacy, bloques `tool`). **Ese normalizador hay que portarlo a Kotlin, y los tests de `Models.kt` existen precisamente porque ya ha roto tres veces.**
- **R4 — `/api/projects` no tiene equivalente nativo, y no es trivial.** Medido: OpenCode tiene `projectID` = **hash sha1 de 40 hex**, y `GET /api/project` devuelve la lista con `canonical` (la ruta). Se puede re-construir el vínculo sesión↔ruta **uniendo `session.projectID` con `project.id` y leyendo `project.canonical`** — pero `/sdcard/projects/*` **no está en la lista de proyectos de OpenCode** (hoy hay 27 y son `/tmp/bench-*`, `/root`, etc.). Ver §6, es la dependencia que puede tumbar el plan.
- **R5 — `aegis-context` se inyecta en el Hub, no en OpenCode.** Es el cambio sin commitear del usuario. Si se quita el Hub, **esa inyección desaparece** salvo que se porte. Y es exactamente lo que `ponytail-global.md` §7 dice que Aegis contributes. **No lo he tocado ni lo tocaré**; queda señalado como dependencia de la Fase 5.

---

## 2. Mapa endpoint a endpoint

`→` nativo en `:49374`. "Decisión" = no hay equivalente y hay que elegir.

| # | Función que la app usa hoy (vía Hub) | Endpoint hoy | Equivalente NATIVO medido | Veredicto |
|---|---|---|---|---|
| 1 | **Autenticación** | `X-Aegis-Token` (fichero `.aegis_token`) | HTTP Basic `opencode:<password>`, password de `/data/local/ubuntu/root/.local/state/opencode/service.json` | ✅ **Resuelto** (R1) |
| 2 | **Agentes** | `GET api/opencode/agents` | `GET /api/agent` → `{data:[40 agentes]}`, cada uno `{id,name,mode,model,permissions,hidden}` | ✅ Directo |
| 3 | **Modelos (catálogo)** | `GET api/opencode/models` | `GET /api/model` → `{data:[476]}` plano, cada uno `{id:"provider/model",providerID,name,capabilities,cost,variants,status}` | ✅ Directo. **Ojo:** `/api/provider` **no** trae modelos (medido: `models=0`) — no usar |
| 4 | **Modelo por defecto** | (Hub lo elige) | `GET /api/model/default` → `{location,data:{id,providerID,...}}` | ✅ Directo |
| 5 | **Sesiones (lista)** | `GET api/opencode/sessions` | `GET /api/session` → `{data:[…50],cursor}` con `{id,projectID,agent,model,cost,tokens,outcome,time,title,location}` | ✅ Directo. **Envolvente `data` + paginación por `cursor`** (el Hub la oculta) |
| 6 | **Una sesión** | — | `GET /api/session/{id}` → **incluye `model` y `agent` reales** | ✅ Directo → mata `ModelPreferences`/`AgentPreferences` |
| 7 | **Mensajes (historial)** | `GET api/opencode/sessions/{id}/messages` | `GET /api/session/{id}/message` → `{data:[…],cursor}`, paginado | ⚠️ **Requiere traductor** (R3) |
| 8 | **Parte completo de un mensaje** (imágenes/binario) | `GET api/sessions/{id}/part` | `GET /api/session/{id}/message/{messageID}` | ⚠️ **Decisión:** el Hub *recorta* data URIs de la lista y las sirve bajo demanda (6,6 MB → 774 KB medido). **Sin Hub, la lista trae todo**: o se pagina, o se acepta el peso. **No medido cuál** |
| 9 | **Envío de prompt** | `POST opencode/session/{id}/message` (SSE del Hub) | `POST /api/session/{id}/prompt` body `{text, files?, agents?, delivery?, resume?}` → **200 con acuse, `text` vacío** | ✅ Directo, pero **el `delivery` es `steer|queue`** (decisión de UX) |
| 10 | **Streaming de tokens** | SSE propio del Hub (`accepted`/`chunk`/`tool_start`/`tool_done`/`error`/`done`) | **`GET /api/event`** (SSE) con `session.text.delta{delta}` / `session.text.ended{text}` / `session.step.*` / `session.execution.succeeded` | ⚠️ **El vocabulario de eventos es OTRO.** Reescribir el parser. `delta` es delta de verdad (el Hub mandaba acumulado) → el `StringBuilder` que ya se borró **vuelve a hacer falta** |
| 11 | **Estado de turno** | `GET api/sessions/inflight` (4 Mapas + gracia 5 s) | **`GET /api/session/active`** → `{data:{ses_x:{type:"running"}}}` | ✅ **El killers.** Elimina `inflightSessions`/`deliveredInbox`/`pendingIdle`/`lastSeenAt` y los 3 bugs |
| 12 | **Formularios** | `GET api/forms` + `POST api/forms/{s}/{f}/reply` | `GET /api/session/{id}/form` → `{data:[]}` · `POST /api/session/{id}/form/{formID}/reply` → **204** | ✅ Directo |
| 13 | **Permisos** | `GET api/permissions` + reply | `GET /api/session/{id}/permission` · `POST .../permission/{requestID}/reply` body `{decision:"once"\|"always"\|"reject", message?}` → **204** | ✅ Directo. **Ojo:** es **por sesión**, no global: obliga a iterar sesiones |
| 14 | **Modelo por sesión** (escribir) | `X-Model` header | **`POST /api/session/{id}/model`** body **`{model:{id,providerID,variant}}`** → **204 sin cuerpo** | ⚠️ **El body es un OBJETO `Model.Ref`, no el string que la app guarda hoy.** Reelaborar el selector de modelo |
| 15 | **Agente por sesión** (escribir) | `X-Agent` header | **`POST /api/session/{id}/agent`** body `{agent:"orchestrator"}` → **204** | ✅ Directo |
| 16 | **Adjuntos** | `SendMessageRequest(parts/files)` | `files:[{uri, name?, description?, mention?}]` — **`uri` obligatoria**, `agents:[{name,mention?}]` | ⚠️ **Por URI, no por subida.** La app debe poder dar una `file://` legible por OpenCode. **POR VERIFICAR:** si acepta `content://` de FileProvider |
| 17 | **Interrumpir turno** | (no expuesto) | `POST /api/session/{id}/interrupt` | ✅ **Ganancia**: la app gana un botón de parada que hoy no tiene |
| 18 | **Renombrar / borrar sesión** | `PATCH`/`DELETE api/opencode/sessions/{id}` | `PATCH /api/session/{id}`, `DELETE /api/session/{id}` | ✅ Directo |
| 19 | **Pin de sesión** | `POST .../pin` | **no existe** → **Decisión:** store local de la app (DataStore) |
| 20 | **Registro sesión↔proyecto** | `api/projects*`, `projects.json` | `projectID` (hash) + `GET /api/project` → **insuficiente**, ver §6 | 🔴 **Decisión mayor** |
| 21 | **Asistente de instalación** | `api/setup/*`, `api/bootstrap/*` (9) | **no existe** | 🔴 **Decisión**, ver §5 |
| 22 | **Skills** | `api/skills*` | **no hay `/api/skill` de listado** (solo `POST /api/experimental/session/{id}/skill`) | 🔴 Decisión |
| 23 | **Estado del sistema** | `api/system/{health,logs,memory,status,start,session-info}` | `GET /api/…` parcial; logs/memory **no** | 🔴 Decisión: leer ficheros por `su`, o se cae la pantalla |
| 24 | **Control de pantalla** `/api/device/*` (8) | rutas del Hub vía `su` | **no existe** | ✅ **Eliminar** (decisión del usuario) |
| 25 | **Asistente de voz** `/api/assistant/*` | rutas del Hub | **no existe** | ✅ **Eliminar** (consumidor ya borrado) |

**Traducción del shape de mensaje (R3), medida:**
- Nativo: `{id, time:{created}, type:"assistant", agent, model:{…}, content:[{type:"text"|"tool"|…, text?, state?{status,input,output|content}, files?}]}`
- App: `Message(info: MessageInfo(id,role,time,status), parts: List<MessagePart>)` con `role` derivado de `type` y `text` = concatenación de `parts` con `type=="text"`.
- El Hub añade además: `files` (top-level) → partes `type:"file"`, y `m.files` → `source.uri` o `data:` base64 (`providers.js:629-650`).

---

## 3. Fases ordenadas

Cada fase termina con un **resultado verificable**. Ninguna borra nada.

### Fase 0 — Prueba de auth + streaming AISLADO (sin tocar Aegis)
**Ficheros:** solo bajo `/sdcard/projects/_tmp/aegis-direct-probe/` (**fuera del repo**, §5.1).
**Trabajo:** script que (a) lee la password por la ruta del chroot, (b) abre `GET /api/event`, (c) imprime la secuencia de eventos de **una sesión propia de prueba bajo `_tmp/`** — nunca de un proyecto del usuario.
**Terminada cuando:** imprime `401`→`200` con la password leída de `/data/local/ubuntu/…`, y una cronología real `inbox.enqueued → …→ text.delta{…} → execution.succeeded` con `sessionID` y `ordinal`. **Ya he medido las tres cosas hoy** (ver R1, §2 fila 10): el riesgo aquí es bajo y el coste es de minutos, pero fija el contrato antes de escribir Kotlin.

### Fase 1 — Capa de datos nativa en la app (elnormalizador)
**Ficheros poseídos:** `data/OpenCodeApi.kt` (nuevo), `data/NativeMapper.kt` (nuevo), `data/NativeModels.kt` (nuevo), `data/Credentials.kt` (nuevo).
**Trabajo:** interfaz Retrofit contra `:49374` con Basic; `NativeMapper` = puerto de `providers.js:609` + `normalizeMessage` a Kotlin.
**Terminada cuando:** `NativeMapper` convierte un volcado real de `/api/session/{id}/message` en `List<Message>` y `ModelsComportamientoTest` (existe) pasa **sin tocar el fichero de test**. Auth: 0 peticiones `su` por arranque (caché, como `TokenProvider`).
**⚠ Riesgo de CI:** esto **no se puede verificar sin compilar**, y aquí no hay compilador de Kotlin → **1 ciclo de CI**. Antes de gastarlo, contraste: `kotlinc` no existe → *POR VERIFICAR* si la CI compila tests unitarios o solo `assembleRelease` (si no los compila, el test es decorativo y hay que decirlo).

### Fase 2 — Lecturas: sesiones, mensajes, modelos, agentes
**Ficheros poseídos:** `data/ApiService.kt` (**añadir** métodos nativos, **no borrar** los del Hub), `ui/viewmodel/ChatsScreen`'s VM (`MainViewModel.kt`), `ui/viewmodel/ProjectDetailViewModel.kt`.
**Terminada cuando:** la lista de chats, el historial y el selector de modelo/agente muestran **los mismos datos** viniendo de `:49374`, con el Hub todavía en pie. **Interruptor de origen por `BuildConfig`/flag**, no por edición de URL: sin eso no hay red de seguridad.

### Fase 3 — Envío + streaming SSE (el heartbeat de la app)
**Ficheros poseídos:** `ui/viewmodel/ChatViewModel.kt` (el bloque `sendMessage`/SSE, ~L1120-1420), `data/EventStream.kt` (nuevo).
**Trabajo:** `POST /prompt` (acuse) + `GET /api/event` en un `callbackFlow` con reconexión; `text.delta` acumula; `execution.succeeded` cierra.
**Terminada cuando:** un turno largo con tool calls se ve **token a token** en la app, el botón "parar" (`/interrupt`) funciona, y **cerrar y abrir la app a mitad de un turno muestra el texto completo** (esa es la prueba de que `/api/event` + `/message` reconstruyen bien). **1 ciclo de CI.**

### Fase 4 — Estado de turno: borrar la reconstrucción
**Ficheros poseídos:** `MainViewModel.kt` (poll de `getInflight`), `ChatViewModel.kt:607`, `ui/TurnNotifier.kt`.
**Trabajo:** `/api/session/active` sustituye a `getInflight()`. **Se pueden eliminar** los 3 bugs (notificación de "último mensaje" con el turno en curso, divisor de fin de turno, "en pausa" sin estarlo) **no parcheteándolos**.
**Terminada cuando:** los tres bugs **no se pueden reproducir** porque el código que los causaba ya no existe; y con la app cerrada, un turno lanzado **desde el CLI** aparece como ocupado en la lista (hoy es justo lo que `/api/session/active` arregla y el Hub no veía).

### Fase 5 — Decisiones de datos: proyectos, pin, skills, sistema
**Ficheros poseídos:** `MainViewModel.kt` (`linkSession`), `ProjectDetailViewModel.kt`, `data/ProjectsStore.kt` (nuevo).
**Terminada cuando:** el registro sesión↔proyecto funciona **sin el Hub** (ver §6), y `aegis-context` sigue inyectándose **o** se ha decidido explícitamente que no (R5).

### Fase 6 — `CompanionService` fuera (decisión del usuario, independiente)
**Ficheros poseídos:** `CompanionService.kt` (a `_tmp/…-retirado-2026-10-01/`), `AndroidManifest.xml` (quitar el `<service>`), y quitar `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` **si quedan sin uso** (medido: `POST_NOTIFICATIONS` los usa `TurnNotifier` → **se queda**).
**Terminada cuando:** la app **no** muestra la notificación permanente, y **no** hayAviso con la app cerrada — que es lo que el usuario aceptó. **Ojo:** el KDoc de `CompanionService` afirma que sin él se pierden la notificación de fin de turno y el círculo de "trabajando". Con `/api/session/active` el círculo sigue mientras la app está abierta; **con la app cerrada no hay proceso y no hay aviso.** Es el precio, y es el que el usuario pagan. **1 ciclo de CI.**

### Fase 7 — Retirada del Hub (solo tras Fase 5 verde)
Mover a `_tmp/hub-retirado-<fecha>/`, **nada de `rm`**. **Y solo si Fase 5 lo deja todo en la app** (§5 y §7 dependent).

**Ciclos de CI estimados: 4** (F1, F3, F6, y uno de integración). Es el recurso escaso; por eso las Fases 0/1 seaposicionan con medición en host en vez de a ciegas.

---

## 4. Paquetes de trabajo con ficheros DISJUNTOS

Regla dura: **ningún paquete toca un fichero de otro.** Si hace falta, se avisa y se serializa. Los paquetes A–D son paralelizables entre sí; E y F dependen.

| Paquete | Ficheros que POSEE (y nadie más toca) | Depende de |
|---|---|---|
| **A — Auth/credenciales** | `data/Credentials.kt` **(nuevo)**, `RootShell.kt` (**solo** el método de leer un fichero), tests nuevos en `app/src/test/…/Credentials*` | — |
| **B — Contrato de red** | `data/OpenCodeApi.kt` **(nuevo)**, `data/NativeModels.kt` **(nuevo)** | — |
| **C — Traductor** | `data/NativeMapper.kt` **(nuevo)**, y **solo tests nuevos** | B (interfaz), no su código |
| **D — Estado de turno** | `ui/viewmodel/MainViewModel.kt`, `ui/viewmodel/ChatViewModel.kt`, `ui/TurnNotifier.kt`, `data/ProjectsStore.kt` **(nuevo)** | B |
| **E — Streaming** | `data/EventStream.kt` **(nuevo)**, `ui/viewmodel/ChatViewModel.kt` ⚠️ **CHOCA con D** | B, C |
| **F — Retirada del servicio** | `CompanionService.kt`, `AndroidManifest.xml` | — (independiente) |

⚠️ **Conflicto declarado: D y E se pelean `ChatViewModel.kt`.** Es *el* fichero de 1536 líneas donde vive el bug. **No se reparten.** Opciones: (a) E y D los hace el mismo agente en orden E→D; (b) E extrae antes el streaming a `EventStream.kt` y **D solo empieza cuando E haya terminado**. Recomiendo **(b)**: separar es lo que hace falta igualmente, y así dos agentes sí pueden trabajar en paralelo sobre ficheros distintos.

**Los paquetes A–D más F se pueden repartir hoy entre 5 agentes** sin que dos toquen el mismo fichero. Dado lo que ya costó el incidente de dos agentes en un mismo fichero, la regla es: **si dos paquetes quieren un fichero, uno espera.**

---

## 5. Qué se hace con el asistente de instalación (`/api/setup/*`, `/api/bootstrap/*`)

**DECISION DEL USUARIO (2026-10-01), que ANULA la recomendacion de esta seccion.**

> "el instalador tambien deberia estar en la aplicacion no en el hub. quiero que el hub se elimine
> por completo. es completamente innecesario tener un hub"

**El asistente de instalacion se PORTA A LA APP, y el Hub desaparece entero.** No hay Hub reducido a
instalador. La recomendacion original se conserva abajo porque su razonamiento es la unica explicacion
de por que era mala, y hay tres cosas suyas que **siguen siendo ciertas** aunque la decision las anule.

**Que cambia respecto a lo que este plan decia:**

| | Recomendacion original | Decision del usuario |
|---|---|---|
| `/api/setup/*`, `/api/bootstrap/*` | quedan en el Hub | **se portan a la app** |
| `/api/projects` | lo pone el Hub | **lo pone la app** (seccion 6) |
| `aegis-context` | lo inyecta el Hub | **lo inyecta la app** |
| `opencode serve` en boot | `service.d` nuevo | **igual, y pasa a ser OBLIGATORIO** (seccion 7) |
| El Hub | se queda como instalador | **se elimina entero** |

**Consecuencia que hay que aceptar por escrito: la reversibilidad.** Con el Hub escuchando, si la app
directa falla se vuelve al flag y todo sigue como antes. **Sin el Hub eso deja de existir**: si la app
directa falla, no hay adonde volver. Por eso el orden sigue siendo construir -> comprobar -> y solo
entonces eliminar, y por eso **el arranque de `opencode serve` en boot (seccion 7) tiene que funcionar
ANTES de borrar `keepalive.sh`**, no despues. El orden contrario deja el movil sin OpenCode tras un
reinicio, y eso no lo detecta nadie hasta que hace falta.

**Lo que el razonamiento original acerto, y sigue siendo verdad:**

1. **Es un instalador de un stack de 4 piezas** (Node en chroot, `opencode serve`, proveedor Antigravity
   con su OAuth, `/sdcard/projects`). `setupRoutes.js` (422+ lineas) hace *smoke tests*, lanza procesos,
   escribe manifiestos y comprueba versiones. **Portarlo a Kotlin NO es mover un fichero:** la app tiene
   que hacer ella lo que el Hub hacia con `su` y procesos. El trabajo es el mismo; lo que cambia es
   **donde vive**, no **cuanto cuesta**.
2. **Es de un solo uso**, asi que su codigo probablemente se simplifique al portarlo: la app ya tiene
   `SetupWizardScreen.kt` (865 lineas) + `BootstrapViewModel` + tests, y ya sabe pintar los pasos.
   **Ojo al tentarse de "simplificar":** lo que se puede quitar es el transporte HTTP, no el instalador.
3. **El Hub no es lo que va a arrancar OpenCode.** `server.js` solo lo lanza como *fallback* si no hay
   servicio registrado. Quitar el Hub **no** deja a nadie sin servidor (la seccion 7 ya lo tiene resuelto).

**Lo que NO cambia, y es un riesgo NUEVO:** el `aegis-context` del usuario tiene que seguir inyectandose
en cada mensaje, porque son sus instrucciones de sesion. La diferencia es que lo inyecta la app en lugar
de dejarselo al Hub. **POR VERIFICAR:** cual es el endpoint nativo que hace lo mismo. Hoy es
`_setSessionInstruction(sessionId, "aegis-context", ...)` en `providers.js`, en cada mensaje proxied; el
evento `session.instructions.updated` que emiti una captura sugiere que existe un mecanismo, pero sin
medir el endpoint no se puede escribir el codigo.

---

## 6. Qué se hace con `/api/projects` (registro sesión↔proyecto)

**🔴 Es la dependencia no trivial, y puede ser la que tumba el plan. Señalada aquí, no escondida.**

**Medido:**
- `session.projectID` = hash **sha1 de 40 hex** (`cd31f2124f23…`). No es una ruta.
- `GET /api/project` → 27 proyectos, cada uno `{id, canonical:"/ruta", time, sandboxes}`. **El vínculo es `session.projectID == project.id`**, y `canonical` es la ruta.
- **PERO: `/sdcard/projects/*` NO está en esa lista.** Hoy hay `/tmp/bench-orch`, `/tmp/bench-sandbox/slot0-2`, `/tmp/kaenor-orch`, `/root`, etc. Los proyectos reales de Aegis **no aparecen** → porque las sesiones se crean con `projectID` de otro sitio, o los proyectos nunca se registran en OpenCode.
- `GET /api/project/{id}` **da 404** aunque el id exista en la lista (solo hay `PATCH`). Por eso no se puede resolver uno a uno.
- El Hub guarda su propia tabla en `backend/projects.json`: `{projects:[{id,name,folder,directory,ponytail,sessions,…}], sessionTitles, sessionPins, sessionAutoTitles}`. **`sessions: []` está vacío** — o sea, **el registro que Aegis pretende mantener ya está vacío o casi**.

**Consecuencia:** `GET /api/project` sirve para *listar* proyectos de OpenCode, **pero no para saber qué sesiones de Aegis pertenecen a qué carpeta de `/sdcard/projects`**. Ese vínculo —el que la app necesita para "mis chats de Aegis agrupados por proyecto"— **no existe en OpenCode**. Se lo pone el Hub.

**Opciones (decisión de Leonardo, no mía):**

| Opción | Qué cuesta | Riesgo |
|---|---|---|
| **A — La app lee `projects.json`** (el fichero que ya existe) | Es un fichero en `/sdcard`, la app ya lo lee sin root (`MANAGE_EXTERNAL_STORAGE`). **Barato.** | Escritura compartida: si el Hub escribe y la app escribe, hay carrera. Se resuelve con "solo la app escribe, el Hub solo lee". |
| **B — La app mantiene su propio store y se queda con OpenCode como secondary** | Store local (DataStore/Room) con `sessionID ↔ carpeta` | Se duplica el estado, pero **el Hub ya no es dueño de nada** y se puede matar limpio. |
| **C — Registrar cada proyecto de Aegis en OpenCode** (`POST /api/session` con `directory`) | Que `/sdcard/projects/X` aparezca con su `projectID` | **POR VERIFICAR**: no está medido si `POST /api/session` acepta un `directory` arbitrario y registra el proyecto. **Sin medir no se promete.** |

**Recomendación: B** (store propio de la app), con **A como puente de migración** de una sola vez (leer `projects.json`, sembrar el store, y a partir de ahí la app es dueña). Es lo único que hace la retirada del Hub **realmente** возможной. **La decisión es de Leonardo; no se ejecuta nada de esto sin que la tome.**

---

## 7. Orden de eliminación, y quién arranca `opencode serve` en el boot

**DECISION DEL USUARIO: esto ya no es una fase opcional.** Con el Hub eliminado, este apartado es
**la unica cosa** que pone a OpenCode en marcha tras un reinicio. Si se borra `keepalive.sh` sin
que el `service.d` nuevo funcione, el movil arranca sin OpenCode y no hay nadie que lo avise.

**Primero, el hallazgo que condiciona todo (§7 responde a "quién arranca"):**
- **`keepalive.sh:184` es lo ÚNICO que arranca `opencode serve --service` en el boot** (medido: `HOME=/root nohup "$OPENCODE_BIN" serve --service >> opencode.log`).
- El arranque en el boot es **`service.d/99-opencode-hub.sh`** → `nsenter` a un mount ns con `/usr/bin/node` → `keepalive.sh`. Si no hay ns con node, **aborta y avisa** (ventana ~200 s).
- **El Hub NO arranca el serve**; `server.js` solo lo lanza como *fallback* si no hay servicio registrado.

**🔴 El problema:** si se borra `keepalive.sh`, **nadie arranca `opencode serve` en el boot**. Es exactamente la pregunta que se hacía, y la respuesta es: **hoy no hay nadie más**. La app no lo arranca (y con `CompanionService` eliminado, tampoco hay proceso vivo que lo pueda hacer).

**El orden correcto (nada se borra antes de estar verde):**

1. **Fase 6 primero:** quitar `CompanionService`. Es lo que **crea** la necesidad de que algo arranque el serve sin la app, y no depende de nada más. (Además es lo que el usuario pidió.)
2. **Crear el arranque nuevo, en paralelo, sin borrar el viejo:** un `service.d/9X-opencode-direct.sh` **nuevo** que arranque `opencode serve --service` directamente (el `serve` es independiente del Hub: es el mismo binario). Se deja `keepalive.sh` corriendo **a la vez**, y `keepalive.sh` **ya ignora** los daemons `--service` (su "condición 4", línea 148) → **no se pelean**.
3. **Verificar en frío:** reiniciar y comprobar que `serve` vuelve **sin** Hub y **sin** abrir la app. **Esto es un reinicio: requiere OK de Leonardo** (§5.2).
4. **Solo entonces** reducir `keepalive.sh` a arrancar-serve (o sustituirlo por el `service.d` nuevo) y, cuando la app directa esté verde en Fase 5, **mover a `_tmp/`**: `keepalive.sh`, `start-hub.sh`, `server.js`, `providers.js`, `find-ubuntu.sh`, y el `service.d` viejo. **Nunca `rm`.**
5. `find-ubuntu.sh` (localiza el PID del chroot) y `/api/device/*` (control de pantalla): **eliminables** según decisión del usuario, pero `find-ubuntu.sh` se usa para el `nsenter` del propio arranque → **no borrarlo hasta que el `service.d` nuevo no dependa de él**.

⚠️ **El `service.d` instalado vive fuera del proyecto** (`/data/adb/service.d/99-opencode-hub.sh`). Instalarlo/reemplazarlo es §5 (`/data/adb` es zona sensible) → **necesita OK explícito**. Y se instala desde `nsenter`, que el guard bloquea para mí.

---

## 8. Los 3 riesgos que más probablemente fallen, y cómo se comprueban ANTES de comprometer un cambio

### R1 — La contraseña (401) · **el que más Probably falla**
**Falla así:** la app lee la password, el serve **reinicia** (o el usuario lo reinicia), la password **cambia**, la app queda con la vieja → **401 en todo**, y no hay dónde mirar porque el 401 de OpenCode no lleva cuerpo útil.
**Se comprueba ANTES:**
- Fase 0 lo lee de la ruta real del chroot y **verifica 200** (ya hecho hoy).
- **Prueba de reinicio, antes de tocar la app:** anotar la password → `curl` → **matar/arrancar `serve`** (**§5.2, pide OK**) → ¿`service.json` cambia? ¿la app lo detecta?
- **El fallback que hay que escribir ANTES:** si el 401 llega, **re-leer el fichero y reintentar una vez** — es el mismo patrón que ya tiene `authInterceptor` para `X-Aegis-Token` (líneas 28-34), o sea que **la machinery ya existe**.
- **Y la trampa de medir:** el `opencode.log` **tiene una contraseña VIEJA que da 401** (medido). **No usar el log como fuente.** Solo `service.json`.

### R2 — Streaming: huecos y reanudaciones
**Falla así:** la SSE se cae (Android mata el proceso, cambia de red) y **como `durable.seq` no existe**, **no hay forma de saber qué deltas se perdieron**. El texto sale incompleto o duplicado.
**Se comprueba ANTES:**
- **Medir el peso de `durable`:** confirmado ausente hoy → **no se puede usar**. **Plan:** al reconectar, **re-preguntar `/api/session/{id}/message`** y **re-renderizar desde la fuente de verdad**, en vez de seguir acumulando deltas. Es lo que hace la app hoy con `getMessagesTail` al abrir.
- **Prueba en Fase 3, sin CI:** abrir un turno largo, **matar la app a mitad** (`adb shell am force-stop` → §5.2/§0, **con OK**), reabrir → el texto **tiene que estar completo**. Si sale completo, R2 está contenido, porque el texto no depende del stream, sino de `/message`.

### R3 — El normalizador de mensajes (el trabajo real, y el que rompe test)
**Falla así:** un `type` de `content[]` o una forma de `state` que el Hub **ya normalizaba** y el puerto no. La app **deja de mostrar texto o tool calls** — y como es un `try/catch` mudo en varios sitios (medido: `catch (_: Exception) {}` en el parseo del SSE, línea 1376), **falla en silencio**.
**Se comprueba ANTES:**
- **Congelar un volcado real** de `/api/session/{id}/message` **en `_tmp/`** (con `tool`, con `text`, con adjuntos) y hacer que el test corra **contra ese volcado**, no contra un modelo inventado.
- **Los tests que ya existen** (`ModelsComportamientoTest`, `ModelsEnvelopeTest`, `ToolStateTest`, `MarkdownBlockCountTest`) son el activo más valioso del repo aquí: **pasarlos sin tocarlos** es el criterio de "terminada" de la Fase 1.
- **Y no confiar en el silencio:** un `catch` mudo que se traga un fallo de parseo es exactamente el patrón que ya ha producido "no se ve nada y no hay error" (medido en el propio repo, comentario de `MainViewModel`).

---

## Requisitos de verificación, en una línea

- Host: Fase 0 (auth+SSE) y Fase 4 (`/api/session/active`) **sin CI**.
- CI: **4 ciclos**, uno por fase de Kotlin. Antes de gastar uno: *POR VERIFICAR* si la CI compila los tests unitarios o solo `assembleRelease`.
- Tras **cada** edición de código: `graphify update /sdcard/projects/Aegis` (**hook lo bloquea si no**), y el test objetivo de §4.3.1 al terminar.
- Ficheros nuevos: siempre en `/sdcard/projects/_tmp/`, y `kwrite.py` con `--sha` para confirmar cada escritura (FUSE trunca, §2).
- `backend/server.js`: **la línea sin commitear del usuario no se toca ni se commitea.** Si alguien hace `git checkout` de ese fichero, se pierde su trabajo — **es el riesgo operativo más fácil de materializar de este plan.**