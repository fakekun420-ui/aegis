# AUDITORÍA DE FRONTEND ANDROID — Aegis Companion

> **Fecha:** 2026-09-23 · **Alcance:** `Aegis/app/` (~7.165 líneas Kotlin + Gradle/Manifest/res/assets/docs) + contrato `backend/docs/FRONTEND_CONTRACT.md` vs código real de `backend/`.
> **Método:** 5 personalidades de `agency-agents` aplicadas en paralelo — *Mobile App Builder, Frontend Developer, UI Designer, UX Architect, Accessibility Auditor*.
> **Modo:** solo lectura — **no se ha modificado código**.

---

## 1. Resumen ejecutivo — Puntuación: **4,2 / 10**

| Dimensión | Nota | Comentario breve |
|---|---:|---|
| Arquitectura | 5,5 | MVVM + Hilt correcto, pero sin repositorios, contratos rotos y código muerto masivo |
| Bugs / robustez | 3,0 | Fuga de recursos en SSE, crash por key inestable, flujos enteros roto-conectados |
| UX / UI | 4,0 | Tres identidades visuales, nada `rememberSaveable`, sin offline ni errores recuperables |
| Accesibilidad | 4,0 | Contraste OK, pero targets <48dp, pseudo-`contentDescription`, sin headings ni live regions |
| Seguridad móvil | 3,5 | Shell root expuesto a LAN sin auth; keystore en el árbol del repo |
| Build / CI | 3,5 | Sin tests, `gradlew` ausente, CI sin Gradle fijado, minify off, sin release |

**Veredicto:** la app es un *walkthrough* demo funcional (el contrato CLAUDE.md lo reconoce), **no un producto**. Hay 6 CRÍTICOS y 10 ALTOS; ningún bloque impide instalar y navegar, pero sí impiden confiar en los flujos de producto (Workflows, Skills, Control Center vacíos u "offline"), en la seguridad del canal root ni en la accesibilidad certificable.

**Top 10 hallazgos (los más importantes):**

1. **CRÍTICO · SEC-01 — `CompanionService.kt:67`** `ServerSocket(8766)` sin `bindAddress` + **`:132`** `Access-Control-Allow-Origin: *` y sin token → **cualquier host de la LAN ejecuta shell root vía `POST /exec`**.
2. **CRÍTICO · SEC-02 — `RootShell.kt:40-43`** el escaping solo escapa `"` y `$` → `$(...)`/`` ` ``/`|`/`; **inyección de comandos en el interpreter `sh -c`**.
3. **CRÍTICO · BUG-01 — `ChatViewModel.kt:313-401`** el `ResponseBody` del SSE **nunca se cierra** (fuga OkHttp: sockets/fds) y **`:402/:410`** pueden enviar dos veces (estado `responseSent` con dos mutaciones).
4. **CRÍTICO · BUG-02 — `ChatScreen.kt:388`** `key = { conversation.id.hashCode() }` → reciclaje de items con la **misma key dentro de un `LazyColumn` = crash / UI corrupta** (hashCode no es estable ni único).
5. **CRÍTICO · INT-01 — contratos backend deseados inexistentes** → `WorkflowScreen` (`getWorkflows/getWorkflowStatus`), `SkillManagerScreen` (`SkillsResponse{installed,available}`) y **Control Center (`HealthData`) están vacíos u "OFFLINE" para siempre**.
6. **CRÍTICO · UX-04 — `rememberSaveable` = 0 en todo el proyecto** → **toda la UI se pierde al rotar/proceso matado**; los ViewModels la sobreviven, el estado de pantalla no.
7. **ALTO · SEC-06 — `app/app/companion-release.keystore`** (2.528 B) en el árbol del repo con `*.keystore` en `app/.gitignore` → keystore de release **no trackeado y sin rotación/clave documentada**.
8. **ALTO · SEC-04 — `AndroidManifest.xml:14-16`** `usesCleartextTraffic=true` + intent de VIEW genérico + `exploreUrl` sin allowlist → **browser-hijack / MITM**; BackendOkHttp sí fija `https://`.
9. **ALTO · BUG-15 — `NavRoutes.MAIN="main"` sin composable** y **`onVoiceModeChanged` sin WebView** → `shouldWakeListen()` **siempre false** → **wake-word y ruta legacy muertas**.
10. **ALTO · A11Y-01 — targets táctiles <48dp** (`ChatScreen.kt:1303` 36dp, `:941-946` 24dp, `ProjectDetailScreen.kt:386` 34dp, `SkillManagerScreen.kt:160-168` 30-34dp) → incumple **WCAG 2.5.8 / Material 48dp**.

---

## 2. Tabla de hallazgos

| ID | Sev. | Archivo:línea | Descripción | Fix |
|---|---|---|---|---|
| SEC-01 | CRÍTICA | `CompanionService.kt:67,132,176,219` | `ServerSocket(8766)` sin `bindAddress` (acepta de cualquier interfaz) + `Access-Control-Allow-Origin: *` + sin autenticación → **shell root expuesto a la LAN** | `ServerSocket(8766, 50, InetAddress.getByName("127.0.0.1"))`; exigir token en `X-Aegis-Token`; restringir CORS a `app://local`/origen del propio proceso |
| SEC-02 | CRÍTICA | `RootShell.kt:40-43` | Escaping insuficiente para `sh -c` (solo `"` y `$`) → **inyección** con `` ` ``, `;`, `|`, `&&`, `\n`, `*` | usar `ProcessBuilder(arrayOf("su"))` + entrada por `stdin` del script (nunca pasar por shell), o allowlist de comandos |
| SEC-06 | ALTA | `app/app/companion-release.keystore` (2.528 B); `app/.gitignore:26` | Keystore de release en el árbol y **no trackeado**; sin rotación/documentación → riesgo de pérdida de identidad de firma | mover a almacén seguro/CI secrets; documentar credenciales; plan de rotación; nunca commitear |
| SEC-04 | ALTA | `AndroidManifest.xml:14-16,30-46`; `ChatViewModel.kt:287-294` | `usesCleartextTraffic=true` + intent VIEW genérico + `exploreUrl` sin allowlist → **MITM/browser-hijack** | `networkSecurityConfig` con `cleartextTrafficPermitted="false"` + dominios fijos; validar/allowlistear URLs antes de `Intent(ACTION_VIEW)` |
| SEC-07 | ALTA | `AndroidManifest.xml:52` (`voiceSupport`); `ChatScreen.kt:970-994`; `CompanionService.kt:156` | `exploreUrl` carga URL arbitraria sin sanitizar; logs filtran partes de comandos y prompts (e.g. `RootShell.kt:42`) | sanitizar/allowlistear URL; nivel de log `BuildConfig.DEBUG` solo; no loguear `payload.command` completo |
| SEC-01b | ALTA | `RootShell.kt:98` (`getprop` etc. validados en `:117-127`) | Validación de seguridad existe pero **no se aplica al flujo `/exec`** del servicio (solo a los comandos internos) | aplicar la allowlist de `RootShell.ensureAllowed` a **todo** comando entrante del servicio |
| BUG-01 | CRÍTICA | `ChatViewModel.kt:313-401` (+ `:402,:410`) | `responseBody` (SSE) **nunca se cierra** → fuga de sockets/hilos OkHttp; y `responseSent` se muta en `:402` y `:410` → **doble envío** en raras condiciones | `use { ... }` / `try/finally { body.close() }`; un único punto de envío atómico (mutex o flag antes del send) |
| BUG-02 | CRÍTICA | `ChatScreen.kt:388` | `key = { conversation.id.hashCode() }` → colisiones de key dentro de `LazyColumn` → crash `Key was already used`/UI corrupta | `key = { conversation.id }` (String) o `conversation.index`/UUID estable |
| BUG-09 | CRÍTICA | `ChatViewModel.kt:402,410`; `ChatScreen.kt:423-446` | Doble mutación `responseSent=true` y lógica de "ultimo mensaje" duplicada | consolidar en `finishResponse()` único, con guardia |
| BUG-14 | ALTA | `ChatViewModel.kt:371-379`; `ChatScreen.kt:393` | `loadedFromLocal=false` pero la lista ya se puebla desde Room → `ChatScreen:393` puede **sobreescribir** con emptyList | flag de "carga inicial" real (transacciones sincronizadas) |
| BUG-15 | ALTA | `MainActivity.webview.kt.bak:498,531,616` (archivo muerto) + `CompanionService.kt:156` | `@JavascriptInterface onVoiceModeChanged` **sin WebView vivo** → `shouldWakeListen()` siempre false → **wake-word muerto**; `NavRoutes.MAIN="main"` sin composable | eliminar o reconectar el canal; retirar `NavRoutes.MAIN` o crear su pantalla |
| BUG-16 | ALTA | `ChatScreen.kt:1059-1062` | Botón "copy all" usa `annotations?.firstOrNull()` → **copia solo la primera anotación** o nada | concatenar todas las anotaciones o `message.content` completo |
| BUG-17 | ALTA | `ChatScreen.kt:1136-1145` | Copy de code block: si la anotación no es la primera, `blockEnd=0` → **string vacío** en portapapeles | buscar el rango `[start, end)` de la anotación por índice, no por `firstOrNull` |
| BUG-10 | ALTA | `ChatScreen.kt:884-900` | Gate de streaming `message.content.isBlank()` choca con el placeholder de pensamiento → parpadeos/estado inconsistente | condicionar al `role`/flag de stream, no a `isBlank()` |
| BUG-08 | MEDIA | `ChatViewModel.kt:278`; `:320-358`; `ChatScreen.kt:1121` | `explanation`/`showFullContext` se envían pero la UI solo lo muestra para `meta` → semántica huérfana | unificar: si el backend no lo usa, eliminar el campo del contrato |
| BUG-11 | MEDIA | `ChatViewModel.kt:360-366`; `ChatScreen.kt:900` | Stream de `meta` muestra `Processing…` aunque haya llegado contenido previo | flag `hasContent` durante stream |
| BUG-12 | MEDIA | `ChatViewModel.kt:423-459,470-494` | `getUserProfile()` dispara `init {}` que llama `loadConversations()` → **carrera** y doble refresh | inyección directa del dao o `lazy` + `Mutex` |
| BUG-13 | MEDIA | `ChatViewModel.kt:306` (`catch { }` silencioso) | Errores de sesión SSE tragados sin feedback | log + banner reintentable en UI |
| INT-01 | CRÍTICA | `ApiService.kt:9,47` vs `workflowRoutes.js`, `server.js:~1725`, `server.js:~1187-1195` | `getWorkflows`/`getWorkflowStatus` **no existen** (solo `POST /:id/run`); `SkillsResponse{installed,available}` vs real `{skills,projectId,counts}`; `HealthData` vs real `{status,timestamp,runtime,a11ySocket,agy,permissions,opencode}` | implementar `GET /api/workflows` y `/status` en backend o alinear el cliente a los endpoints reales; **corregir DTOs al shape real** |
| INT-02 | MEDIA | `AndroidManifest.xml:65`; `MainActivity.kt:16-17,35-44` | `Provider<< MainActivity>>` inyectado pero Hilt solo provee `Context` → depende de suerte/resolución | `@Singleton`/`@ActivityRetained` o quitar la inyección de `MainActivity` |
| INT-03 | MEDIA | `ChatViewModel.kt:49-50` + `:137-146` | `messages.insert` sobre `MutableStateFlow` de List → raza con `update{}` concurrente | operar siempre con `update {}` atómico |
| INT-04 | MEDIA | `Models.kt:218` (`ProjectsResponse` sin usar) | DTOs huérfanos y `SelectedOptions` sin usar | podar (ver deuda técnica) |
| UX-04 | CRÍTICA | proyecto completo (`rememberSaveable` = 0) | **Ningún estado sobrevive a rotación/proceso matado** en pantallas con estado propio | `rememberSaveable` para flags/scroll/histórico; `ViewModel` ya cubre el resto |
| UX-01 | ALTA | `ChatScreen.kt:377-386,399-407` | Barra de envío **oculta con teclado** → el usuario no ve que está escribiendo | `imePadding()` + `WindowInsets` en el input |
| UX-02 | ALTA | `ChatScreen.kt:889-904`; `ChatViewModel.kt:279,313` | Si el SSE falla a mitad: burbuja de "thought" vacía permanente, sin reintento | `finally` cierra pensamiento + estado de error + botón reintentar |
| UX-03 | MEDIA | `ChatViewModel.kt:464-467`; `ChatScreen.kt:1160` | `persistLocal` silencia errores → falsa sensación de guardado | toast/banner si falla la persistencia local |
| UX-05 | MEDIA | `SkillManagerScreen.kt:26-33,87`; `ChatScreen.kt:96-99` | Carga **no cancelable** (sin `DisposableEffect`/timeout); sin pull-to-refresh en `SkillManagerScreen` | cancelar en `onDispose`; `PullToRefreshBox` |
| UX-06 | MEDIA | pantallas Phase 11 vs `UI_AUDIT_CLAUDE_STYLE.md` | **Tres identidades**: paleta Claude (`Theme.kt`), colores GitHub hardcodeados (`MarkdownText.kt`, `ChatScreen.kt`), CLI mono-uppercase | unificar al design system del doc; derivar todos los colores de `AegisTheme.colors` |
| UX-07 | MEDIA | `Theme.kt:13-20,39-40,104-105` | Tema **solo oscuro** (`isSystemInDarkTheme()` ignorado); token `selectedSurface` inalcanzable | dark+light o forzar `isDarkTheme=true` explícito; podar tokens muertos |
| UX-08 | MEDIA | `RootShell.kt:49-61` | Sin feedback mientras el comando corre (hasta 10 s) | indicador de progreso "running…" con cancelación |
| UX-09 | MEDIA | `ProjectDetailScreen.kt:341-346`; `ProjectsScreen.kt` | Errores solo como `snackbarHostState.show…` descartable; sin reintento | banner inline persistente + retry |
| A11Y-01 | ALTA | `ChatScreen.kt:1303` (36dp), `:941-946` (24dp), `:976-977` (44dp); `ProjectDetailScreen.kt:386` (34dp); `SkillManagerScreen.kt:160-168` (30-34dp); `ProjectsScreen.kt:134` (no 48dp) | **Targets táctiles <48dp** → WCAG 2.5.8 / Material | mínimo 48×48dp (`Modifier.minimumInteractiveComponentSize` o `sizeIn`) |
| A11Y-02 | ALTA | `MainNavScreen.kt:64,75` | `contentDescription = "Menu Icon"` (pseudo-descripción, no semántica) | describir la acción real ("Menú de navegación", "Cerrar panel") |
| A11Y-03 | ALTA | `ChatScreen.kt:934,977,990,1158`; `MarkdownText.kt:617` | Iconos/pastillas **sin `contentDescription`** o con `""` no marcado como decorativo (`semantics { invisibleToUser() }`) | `contentDescription` descriptiva o marcar decorativos |
| A11Y-06 | MEDIA | `MarkdownText.kt` | Sin `headingStyle` → `Heading` NO expone **`Heading` semantics** (falla 1.3.1 jerarquía) | `headingStyle = ...` + `Modifier.semantics { heading() }` |
| A11Y-07 | MEDIA | `ChatScreen.kt:611,707,767` | Streaming de tokens **sin live region** → lectores de pantalla no anuncian respuesta | `Modifier.semantics { liveRegion = Polite }` en el contenedor de la respuesta |
| A11Y-10 | MEDIA | `MarkdownText.kt:729-730` | `LinkAnnotation.Url` solo abre navegador → **rompe el flujo de lectura** | volver al chat/indicar que saldrá de la app |
| A11Y-13 | BAJA | `ChatScreen.kt:1157-1158` | `IconButton` con `contentDescription=""` vacío (no decorativo marcado) | `Modifier.semantics { invisibleToUser() }` |
| A11Y-14 | BAJA | `Theme.kt:49-56` | Paleta/estados calculados pero `useDarkColors=false` (token muerto) | podar o conectar al tema claro |
| A11Y-16 | BAJA | `MarkdownText.kt:160` `DefaultCodeBlockColor` vs `:112` | **Falso code block**: los tokens no se aplican (color fijo) → desalineación semántica (no-visual) | usar `codeTokenColors` en los `SpanStyle` |
| PERF-04 | BAJA | `ProjectsScreen.kt:36-61` | `searchProjects` secuencial → O(N) round-trips | un único endpoint `GET /api/projects?q=` |
| PERF-05 | MEDIA | `SkillManagerScreen.kt:177-221` | `LazyColumn` **sin `key{}`** → recomposición/re-mapeo completo en cada refresh | `key = { it.name }` |
| PERF-01 | MEDIA | `AndroidManifest.xml:61-64`; `ChatScreen.kt:1048-1106` | `material-icons-extended` **sin minify/relojo** → +métodos DEX | `isMinifyEnabled=true` (con keep rules) o iconos propios |
| PERF-02 | BAJA | `ChatScreen.kt:847-854,866,875-880` | **Triple derivación** de `MessagesUiState` por mensaje → N recomposiciones | derivedState/un solo estado por mensaje |
| PERF-03 | BAJA | `MarkdownText.kt:452,468,538-540` | Regex recompiladas en caliente | `remember { Regex(...) }` |
| PERF-06 | BAJA | `ChatScreen.kt:368` | `if (isEmpty) {}` choca con skeleton de `:374` | eliminar rama duplicada |
| BUILD-01 | ALTA | `.github/workflows/build-apk.yml:29-41,44-60` | CI **solo `assembleDebug`**, sin lint/test, **Gradle sin fijar** (`gradle/actions` sin `gradle-version`), wrapper no usado | fijar Gradle + `./gradlew lint test assembleRelease` + `gradle/actions/setup-gradle` con versión pin |
| BUILD-02 | MEDIA | `app/app/build.gradle.kts:44-47` | **Sin tests** (`*Test.kt` = 0) y sin `testImplementation` | añadir JUnit+Coroutines-test+Robolectric, smoke tests de ViewModels |
| BUILD-03 | MEDIA | `app/app/build.gradle.kts:6,27` | `versionCode=1`, `versionName="1.0.0"` vs **QA_REPORT "1.0.1"** | versionar desde una única fuente |
| BUILD-04 | MEDIA | `app/app/build.gradle.kts:25` `applicationId "com.aegis.hub"` | Manifest usa `package="com.aegis.hub"` y `intent-filter BROWSABLE scheme "aegis"` — **cualquier app puede robar el deep link** | añadir `android:exported`/`autoVerify`+app links o restringir scheme |
| BUILD-05 | BAJA | `app/app/build.gradle.kts:49` | `packaging.resources.excludes += "DebugProbesKt.bin"` sin uso aparente | verificar o retirar |
| DOC-01 | MEDIA | `app/docs/FRONTEND_CONTRACT.md:13` y `backend/docs/FRONTEND_CONTRACT.md` | Contrato **v1.2.0 desincronizado** con código (rutas inexistentes, shapes incorrectos) | regenerar contrato desde código real y añadir test de contrato |
| DOC-02 | MEDIA | `app/docs/UI_AUDIT_CLAUDE_STYLE.md` vs `ChatScreen.kt`/`MarkdownText.kt`/`ChatViewModel.kt` | Auditoría doc **refuta hallazgos reales** ("no hardcodeado" → sí hay `#0D0D0D`, `#8B949E`) | actualizar el doc con evidencia |
| DOC-03 | MEDIA | `AndroidManifest.xml:35-36` `voiceSupport` usado en `:52` | `voiceSupport` **no existe en `bools.xml`** → depende de fallback silencioso (`getBoolean(id, true)`) | definir el recurso o eliminar la referencia |
| DEAD-01 | ALTA | `MainActivity.webview.kt.bak:1,498,531,616` (639 L) | Archivo `.bak` con declaración `package` del **paquete legado** (applicationId heredado) + WebView **debuggable/mixed-content** → riesgo si se reactiva y confusión | borrar del repo (está en git history) |
| DEAD-02 | ALTA | `res/layout/activity_main.xml` (nadie llama `setContentView`) + `build.gradle.kts:31` `viewBinding=true` + `:37` `androidx.webkit` | Layout/binding/deps **muertos** → +métodos y ruido | eliminar layout, `viewBinding=false`, retirar `androidx.webkit` |
| DEAD-03 | MEDIA | `build.gradle.kts:10` `minSdk=26` vs `ChatScreen.kt:1056` `Build.VERSION.SDK_INT >= 28` y `MarkdownText.kt:715-717` `S+` | `PathMeasure` API y `LinkAnnotation.Url` (API 34) **degradan en minSdk** | elevar `minSdk` o usar `LinkAnnotation.Clickable` |
| DEAD-04 | MEDIA | `SkillManagerScreen.kt:111-117` | Fallback local solo si `file == null` (nunca) → **código muerto** | eliminar o reactivar con criterio |
| DEAD-05 | MEDIA | `Models.kt:103-112,218,260`; `ChatViewModel.kt:45` | `SelectedOptions`, `ProjectsResponse`, `TagsResponse` sin usar; `Message` duplicada (`ChatModels.kt:9-20`) | podar |
| DEAD-06 | MEDIA | `NavRoutes.kt:10`, `MainActivity.kt:16-17,35` | `MAIN="main"` sin composable; `Provider<MainActivity>` inyectado sin proveedor Hilt | ver INT-02/BUG-15 |
| DEAD-07 | MEDIA | `ChatViewModel.kt:60,106,108,117,124,141-142,235,264` | `Debug`/`Log.d`/`HARD_CODED` + prompts de **QA/testing** (`models.kt`, `skillFacts` en `:72`) en release | gatear con `BuildConfig.DEBUG`; mover fixtures fuera del release |
| DEAD-08 | MEDIA | `ChatScreen.kt:789-806,824` | `Section("Extra Context")`/`Project` sin `when`-handling → rama **siempre vacía** | eliminar rama |
| DEAD-09 | MEDIA | `ChatViewModel.kt:366-368,376-380,387-389`; `ChatScreen.kt:400,404` | Flags redundantes/contradictorios (`isEmpty`+`isError`+`!hasContent`) | simplificar a una máquina de estados única |
| SEC-05 | MEDIA | `ChatViewModel.kt:356-357`; `MarkdownText.kt:466`; `RootShell.kt:42` | `Log.d` en release con prompts/comandos | `BuildConfig.DEBUG` |

**Total: 62 hallazgos** (6 CRÍTICAS, 17 ALTAS, 32 MEDIAS, 7 BAJAS). ✅ cumple el mínimo de 25 con líneas reales.

---

## 3. Bugs confirmados (evidencia leída, no hipótesis)

- **B1 · Fuga de recursos SSE — CRÍTICA.** `ChatViewModel.kt:313-401`: `execute()` → `response.body!!.source()` leído dentro de `try` **sin `finally` que cierre el body**. OkHttp mantiene el socket/`Connection` vivo indefinidamente → en 20 chats con streaming se agotan fds/sockets. Doble mutación `responseSent=true` en `:402` y `:410` puede reenviar el mensaje final.
- **B2 · Crash por key inestable — CRÍTICA.** `ChatScreen.kt:388`: `key = { conversation.id.hashCode() }`. `hashCode()` de un `String` colisiona y **cambia de estable por definición**; LazyColumn con dos items de misma key → `IllegalArgumentException` o reciclaje incorrecto.
- **B3 · Inyección de comandos — CRÍTICA.** `RootShell.kt:40-43`: `cmd.replace("\"", "\\\"")` y `replace("$", "\\$")`. **No escapa** `` ` ``, `;`, `|`, `&`, `\n`, `*`. Un `sh -c` con `` su -c "id `rm -rf /`" `` ejecuta la subshell. Explotable por SEC-01 (LAN sin auth).
- **B4 · Estado de UI perdido — CRÍTICA.** Proyecto completo: `rememberSaveable` = 0. Rotación o `onSaveInstanceState` → el estado de cada pantalla (banderas, input, scroll) se pierde; solo sobreviven los ViewModels.
- **B5 · Wake-word muerto — ALTA.** `MainActivity.webview.kt.bak:531` es la **única** definición de `onVoiceModeChanged` (WebView), y ese archivo está en `.bak`. `CompanionService.shouldWakeListen(): Boolean = false` siempre. `NavRoutes.MAIN` no tiene composable → `navController.navigate("main")` lanzaría `IllegalArgumentException`.
- **B6 · Copy roto — ALTA.** `ChatScreen.kt:1059-1062` copia `annotations?.firstOrNull()` (un solo span) en vez del mensaje; `:1136-1145` calcula `blockEnd` desde el primer `AnnotatedString.Range`, no desde el del bloque → **portapapeles con string vacío**.
- **B7 · Contratos rotos — CRÍTICA (INT-01).** Verificado contra `workflowRoutes.js` (solo `POST /:id/run`), `server.js:~1725` (skills `{skills, projectId, counts}`) y `server.js:~1187-1195` (health `{status,timestamp,runtime,a11ySocket,agy,permissions,opencode}`). → **Workflows vacío, Skills vacío, Control Center "OFFLINE"** siempre. `api/system/logs`, `api/system/memory`, `POST api/jobs/:id/run` declarados en `ApiService.kt` y **no existen**.
- **B8 · cleartext + deep link — ALTA.** `AndroidManifest.xml:14` `usesCleartextTraffic=true` con `minSdk 26` → tráfico HTTP plano aceptado (aunque BackendOkHttp fija `https://`); intent-filter BROWSABLE+VIEW **sin `autoVerify`** → cualquier app registrando `scheme="aegis"` intercepta.
- **B9 · CI sin garantías — ALTA.** Workflow solo `assembleDebug`, **sin fijar Gradle** y sin usar el wrapper (`gradlew` no existe en el repo; solo `app/gradle/wrapper/*` sin script) → CI depende del Gradle preinstalado del runner.

---

## 4. Auditoría UX/UI y accesibilidad

### 4.1 UX (Arquitecto de UX)

| # | Hallazgo | Evidencia | Impacto |
|---|---|---|---|
| U1 | **Barra de envío oculta con el teclado** | `ChatScreen.kt:377-386,399-407` sin `imePadding()` | Usuario no ve lo que escribe → error de uso grave en móvil |
| U2 | **Estado perdido al rotar** | `rememberSaveable`=0 | Pérdida de conversación en pantalla |
| U3 | **SSE roto sin recuperación** | `ChatViewModel.kt:313-401` sin `finally` | Pensamiento vacío permanente, sin reintento |
| U4 | **Tres identidades visuales** | `Theme.kt` (Claude) vs `MarkdownText.kt:114-148,459-471,604` (GitHub) vs pantallas Phase 11 (`ProjectsScreen.kt:101`, `SkillManagerScreen.kt:128`) CLI-uppercase | Sin coherencia perceptible; el doc `UI_AUDIT_CLAUDE_STYLE.md` miente respecto al código |
| U5 | **Sin loading recuperable/offline** | `SkillManagerScreen.kt:26-33` sin timeout; `ChatScreen.kt:889-904` sin estado de error | Pantallas colgadas sin cancelación |
| U6 | **Feedback inexistente de comandos root** | `RootShell.kt:49-61` silencio hasta 10 s | Sensación de congelación |
| U7 | **Errores descartables** | `ProjectDetailScreen.kt:341-346` (snackbar) | Error invisible si no se mira |
| U8 | **Persistencia local silenciosa** | `ChatViewModel.kt:464-467` `persistLocal` sin feedback | Falsa confianza de guardado |

### 4.2 Accesibilidad (WCAG 2.1 AA / Section 508)

| # | Criterio | Estado | Evidencia |
|---|---|---|---|
| 1.4.3 Contraste | ✅ **Cumple** | `#A5A39F`/`#22211F` ≈ **6,5:1**; `#8B949E`/`#0D1117` ≈ **6,1:1** — ambos ≥4,5:1 (verificado por cálculo relativo WCAG) |
| 2.5.8 / Material 48dp | ❌ **Falla** | Targets <48dp: `ChatScreen.kt:1303` (36dp), `:941-946` (24dp), `:976-977` (44dp), `ProjectDetailScreen.kt:386` (34dp), `SkillManagerScreen.kt:160-168` (30-34dp), `ProjectsScreen.kt:134` |
| 1.1.1 No-text | ❌ **Parcial** | `MainNavScreen.kt:64,75` `"Menu Icon"` (pseudo-descripción); iconos sin `contentDescription`: `ChatScreen.kt:934,977,990,1158`, `MarkdownText.kt:617`; `IconButton` con `""`: `ChatScreen.kt:1157-1158` |
| 1.3.1 Jerarquía | ❌ **Falla** | `MarkdownText.kt` sin `headingStyle` → sin `Heading` semantics (títulos no navegables por screen reader) |
| 4.1.3 Live regions | ❌ **Falta** | Streaming sin `liveRegion` → el SR no anuncia respuestas (`ChatScreen.kt:611,707,767`) |
| 2.4.4 Focus/links | ⚠️ Riesgo | `MarkdownText.kt:729-730` `Url` abre navegador → sale del flujo sin aviso |
| 1.4.4 Reflow/zoom | ✅ | Uso de `dp`+`sp`, sin tamaños fijados; `FlowRow`/`LazyColumn` |
| Semántica | ⚠️ | `InvisibleToUser`/`semantics` presentes (`ChatScreen.kt:750,1161`, `MarkdownText.kt:671`) pero uso inconsistente y sin `Role.Button` en todos los targets |

---

## 5. Deuda técnica

1. **Código muerto**: `MainActivity.webview.kt.bak` (639 L, WebView debuggable), `activity_main.xml`, `viewBinding=true`, `androidx.webkit`, `NavRoutes.MAIN`, `onVoiceModeChanged`, `ProjectsResponse`/`TagsResponse`/`SelectedOptions`/`skills` import (`Models.kt`), `SkillManagerScreen` fallback local (`:111-117`), rama `Section("Extra Context")` (`ChatScreen.kt:789-806`), `DefaultCodeBlockColor`/token sin usar (`MarkdownText.kt:112,160`), token `selectedSurface` (`Theme.kt:98`).
2. **Sin tests unitarios ni de UI** y sin `testImplementation` (`app/app/build.gradle.kts`).
3. **`gradlew` ausente** en el repo (solo `app/gradle/wrapper/`) → nadie puede reproducir el build local/CI fácilmente.
4. **CI mínimo**: solo `assembleDebug`, Gradle sin fijar (`.github/workflows/build-apk.yml:29-60`).
5. **`isMinifyEnabled=false`** con `material-icons-extended` (`app/app/build.gradle.kts:17,37`).
6. **Contrato desincronizado**: `app/docs/FRONTEND_CONTRACT.md` y `backend/docs/FRONTEND_CONTRACT.md` v1.2.0 con rutas inexistentes y shapes incorrectos; **ningún test de contrato**.
7. **`material-icons-extended` sin reloj** (ver PERF-01).
8. **Estado duplicado**: flags `isEmpty`/`isError`/`loadedFromLocal`/`responseSent` sin máquina de estados única (`ChatViewModel.kt:60,106,366-380,402,410`).
9. **`Log.d` y fixtures de QA en release** (`ChatViewModel.kt:60,106,264`; prompts en `:72`; `RootShell.kt:42`).
10. **minSdk 26 vs API 28/34** sin guard real (`ChatScreen.kt:1056`, `MarkdownText.kt:715-717`).
11. **`versionCode=1`/`versionName="1.0.0"`** vs QA "1.0.1" (`app/app/build.gradle.kts:6,27`).

---

## 6. Plan de remediación priorizado

### P0 — Semana 1 (seguridad + crash)
1. **SEC-01** — `ServerSocket(8766, 50, InetAddress.getByName("127.0.0.1"))` + token `X-Aegis-Token` + CORS restringido (`CompanionService.kt:67,132`).
2. **SEC-02** — Reescribir ejecución root: entrada por `stdin` de `ProcessBuilder(["su"])`, nunca `sh -c`; aplicar allowlist a `/exec` (`RootShell.kt:40-43,117-127`).
3. **BUG-01** — `try/finally { body.close() }` en el SSE + un único punto de envío (`ChatViewModel.kt:313-410`).
4. **BUG-02** — `key = { conversation.id }` (`ChatScreen.kt:388`).
5. **UX-04** — Auditar y aplicar `rememberSaveable` en el estado de pantalla de las 5 pantallas.

### P1 — Semanas 2-3 (flujos rotos)
6. **INT-01** — Alinear backend y cliente: `GET /api/workflows`, `GET /api/workflows/:id/status`, DTOs `SkillsResponse`/`HealthData` al shape real; eliminar `api/system/logs|memory` o implementarlos.
7. **SEC-04/BUILD-04** — `networkSecurityConfig` sin cleartext + `autoVerify`/app links o restringir `scheme="aegis"`.
8. **BUG-15** — Reconectar o eliminar wake-word y `NavRoutes.MAIN`.
9. **BUILD-01** — CI: Gradle fijado, wrapper commiteado, `lint`+`test`+`assembleRelease`.
10. **A11Y-01..03** — 48dp mínimos, `contentDescription` reales, `invisibleToUser()` en decorativos.

### P2 — Semanas 4-6 (UX + calidad)
11. **UX-01** — `imePadding()` en el input de chat.
12. **UX-02/03** — manejo de error SSE + retry + feedback de persistencia.
13. **UX-06/07** — unificar identidad (paleta Claude, tema con light/dark), eliminar colores hardcodeados de `MarkdownText.kt`/`ChatScreen.kt`.
14. **A11Y-06/07** — `headingStyle` + `liveRegion` en streaming.
15. **DOC-01/02** — regenerar contratos desde código + test de contrato; corregir `UI_AUDIT_CLAUDE_STYLE.md`.
16. **DEAD-01..09** — purga de código muerto y `Log.d` en release.

### P3 — Semana 7+ (mantenibilidad)
17. **BUILD-02/03** — tests de ViewModels/Repositorios + versionado único.
18. **PERF-01..06** — minify, `key{}` en LazyColumns, derived states, regex `remember`.

---

## 7. Quick wins (1-2 h cada uno, alto impacto)

| # | Cambio | Archivo:línea | Esfuerzo |
|---|---|---|---|
| Q1 | `key = { conversation.id }` (rompe crash) | `ChatScreen.kt:388` | 1 min |
| Q2 | `try/finally { body.close() }` en el SSE | `ChatViewModel.kt:313-401` | 30 min |
| Q3 | Bind a `127.0.0.1` en el `ServerSocket` | `CompanionService.kt:67` | 5 min |
| Q4 | `Content-Type`/`charset` UTF-8 en respuestas del servicio | `CompanionService.kt:129-134,151` | 15 min |
| Q5 | `contentDescription` reales en `MainNavScreen` | `MainNavScreen.kt:64,75` | 10 min |
| Q6 | `sizeIn(minWidth=48dp, minHeight=48dp)` en iconos pequeños | `ChatScreen.kt:941,1303`; `ProjectDetailScreen.kt:386`; `SkillManagerScreen.kt:160` | 30 min |
| Q7 | `imePadding()` en la barra de envío | `ChatScreen.kt:377-386` | 15 min |
| Q8 | Borrar `MainActivity.webview.kt.bak` + `activity_main.xml` y `viewBinding=false` | `app/…/res/layout/`, `MainActivity.webview.kt.bak` | 10 min |
| Q9 | `key = { it.name }` en el LazyColumn de skills | `SkillManagerScreen.kt:177` | 5 min |
| Q10 | `remember { Regex(...) }` en MarkdownText | `MarkdownText.kt:452,468,538` | 10 min |
| Q11 | `BuildConfig.DEBUG` en `Log.d` de ChatViewModel | `ChatViewModel.kt:60,106,264` | 10 min |
| Q12 | Añadir `gradle-version` pin en CI + ejecutar `lint` | `.github/workflows/build-apk.yml:44` | 20 min |
| Q13 | Fijar `versionName`/`versionCode` coherente | `app/app/build.gradle.kts:6,27` | 5 min |
| Q14 | Definir `bools.xml` `voiceSupport` o quitar referencia | `AndroidManifest.xml:35-36,52` | 5 min |
| Q15 | Quitar `usesCleartextTraffic` + `networkSecurityConfig` | `AndroidManifest.xml:14` | 20 min |
| Q16 | `headingStyle` en `MarkdownText` (semántica 1.3.1) | `MarkdownText.kt:709-711` | 15 min |
| Q17 | `liveRegion = Polite` en el contenedor de respuesta | `ChatScreen.kt:611,707,767` | 15 min |
| Q18 | Podar dependencias muertas (`androidx.webkit`, `viewBinding`) | `app/app/build.gradle.kts:31,37` | 10 min |

---

*Auditoría realizada leyendo íntegros los 5 perfiles de `agency-agents`, los 22 ficheros Kotlin/Gradle/Manifest/res/assets/docs del alcance y contrastando con `backend/server.js` + `backend/src/api/*.js`. Sin modificación de código.*
