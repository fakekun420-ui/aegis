# Auditoría integral Aegis — 2026-10-06

Método: evidencia medida (trazas ANR de `/data/anr`, CLI vivo en `:49374`, prefs reales
del móvil, grafo graphify con control por grep). Nada de lo que sigue es supuesto.

## 1. Mapa de estructura (líneas reales)

| Capa | Ficheros | Líneas | Responsabilidad |
|---|---|---|---|
| `data/` (costura + nativo) | 18 | 6456 | `RutaNativa` (costura 58/58), `OpenCodeApi`, `NativeModels`, `Models`, `ModelsHub`, `Credentials`, `EventStream`, `ProjectsStore`, `SetupNative`, `BootstrapNative`, `OpenCodeLauncher`, `NativeMapper` |
| `ui/viewmodel/` | 8 | 2905 | `ChatViewModel`, `MainViewModel`, `ProjectDetailViewModel`, + workspace/workflow/skills/control |
| `ui/` | 10 | 5312 | `ChatScreen` (2041), `MarkdownText`, `TtsQueue`, `TurnNotifier`, pantallas de lista |
| `ui/screens/` | 5 | 1507 | Wizard setup y pantallas auxiliares |
| `util/` | 1 | 49 | `relativeTime` |
| tests JVM | 28 ficheros, 209 tests | — | CI los corre en `build-debug` |

Objetos dios (deuda, no defecto): `ChatScreen` 2041, `RutaNativa` ~1900, `ChatViewModel`
~1380. Partirlos es el "resto" del plan del arquitecto, despues de estable.

## 2. Diagrama de flujo del sistema

```mermaid
flowchart TD
    subgraph ARRANQUE["Arranque (frío o app)"]
        BOOT["service.d/99-aegis-opencode.sh\nmonta dev/proc/sys/sdcard (con espera)\nlanza serve --service (guarda pgrep)"]
        LAUNCH["OpenCodeLauncher.asegurarAbierto\nsonda HTTP → monta chroot → lanza si no hay proceso"]
        BOOT --> SRV
        LAUNCH --> SRV
    end
    subgraph MOVIL["Móvil"]
        APP["Aegis APK\nCompose + 8 ViewModels"]
        SRV["opencode serve --service\n127.0.0.1:49374\nBasic opencode:password\n(service.json del chroot)"]
    end
    APP -->|HTTP Basic\n(interceptor + reintento 401)| SRV

    subgraph CHAT["Abrir chat"]
        LOAD["ChatViewModel.load(sid)\n1. restoreModelFor: servidor→prefs→nada\n2. restoreAgentFor: idem agente\n3. loadModels + loadAgents\n4. getMessages + poll 1.5s"]
    end
    subgraph ENVIAR["Enviar mensaje"]
        FIX["fijarModelo: POST /session/id/model\n(proveedor por prefijo/pista/catálogo,\nvariant max si existe)"]
        PROMPT["POST /session/id/prompt\n(acepta texto+archivos+agentes, NO modelo)"]
        POLL["poll 1.5s + SSE /api/event\n(TextUpdate → streaming)"]
        FIX --> PROMPT --> POLL
    end
    subgraph PROYECTO["Proyecto vinculado"]
        LINK["vincular carpeta → folder real en store"]
        NEW["+ Nuevo chat:\ncreate (título+directorio+agente)\n→ set agent + set model del agente\n→ link explícito → navegar → primer mensaje"]
        LIST["load: getProjects + getProjectSessions\n(store: explícito o carpeta coincidente)\n+ getSkills (su a IO)"]
        LINK --> LIST
        NEW --> LIST
    end
    subgraph MODELOS["Modelos"]
        CAT["GET /api/model (480, costo lista)\nfree = todo costo 0/0 o sufijo -free"]
        SEL["selector → setSessionModel\n(nunca solo prefs)"]
        CAT --> SEL
    end
    APP --> CHAT --> ENVIAR
    APP --> PROYECTO
    APP --> MODELOS
```

Reglas del flujo (todas con causa medida):
- El servidor es la única verdad de modelo/agente; prefs = caché que se autocorrige.
- El prompt no acepta modelo: fijar ANTES, nunca en el cuerpo.
- Ningún `su` en el hilo principal (todo por `sh()` a IO).
- Ningún `Regex()` en caliente (12 patrones compilados una vez).
- Ningún `stop()/speak()` de TTS en el hilo principal.
- Ningún fallo mudo en botones: crear/vincular/enviar informan con motivo.

## 3. Bugs eliminados en esta auditoría (evidencia)

| Bug | Evidencia | Fix (commit) |
|---|---|---|
| ANR poll: 12 regex ICU × 200 mensajes cada 2 s en main | traza `trace_00`: `startViewRefresh→isEmpty→strippedText→Pattern.compile` | `e45e9bc` patrones a `private val` |
| ANR TTS: `stop()` en main esperando lock del motor | traza ANR 12:57/12:58 `TtsQueue.stop:95` | `2d78a28` fondo + hilo propio |
| ANR proyecto: `getSkills→su` en main (>5 s) | traza ANR 18:37 `getSkills:665→Process.waitFor` | `f41eea6`/`f380bac` `sh()` a IO |
| Sesión nueva sin agente/modelo (Space Bunny fijo) | `CreateSessionRequest` sin campos; servidor crea con defaults | `d4c7163` agente + `modeloDelAgente` tras crear |
| Sesión en proyecto en blanco, sin error | carpeta del NOMBRE (no la vinculada) + 3 `catch` mudos | este commit: carpeta real + errores honestos |
| Chip "No disponible: opencode/x" | prefs con prefijo + servidor en null | normalizar + `migrarPrefijos` + chip tolerante |
| Servidor envuelto leído como vacío | OpenAPI `{"data":…}` vs retorno plano | `OpenCodeSessionResponse/MessageResponse` |
| Modelo nunca empujado al CLI | `sendMessage` ignoraba `body.model`; spec lo confirma | `fijarModelo` antes del prompt |
| Doble servidor 2×660 MB | sonda HTTP "no responde" también al arrancar | guarda `pgrep` en lanzador + boot |
| Cliente TUI muerto por LMK | `oom_adj` heredado no explícito | `-1000` en ambos lanzadores |
| SSE al Hub muerto + creación por `:8765` | 235 líneas a puerto sin escucha | envío nativo + `api.createSession` |

## 4. Riesgos residuales conocidos (no tocados)

- `su` lento: con el fix la UI no se cuelga, pero una carga de pantalla puede tardar
  los 5 s del timeout si Magisk va lento. Reducción real: `getSkills` en 1 exec (plan).
- Debug de CI firma con clave desechable por run: cada debug exige desinstalar. El
  canal estable es release. Estructural, no de código.
- Sesiones viejas con `model: null` en el servidor: se respetan (no se reescriben
  sesiones del CLI). El chip muestra default/"Elige" en vez de inventar.
- `origin/master` fósil eliminado; ramas `agent/*` borradas tras fusionar.
- `.opencode/plugins/graphify-staleness.js` aparece borrado en el árbol sin que esta
  sesión lo tocara (otra sesión lo movió a `_tmp`: hay `.bak`). Pendiente aclarar
  quién y reponerlo si el hook deja de avisar.
- `graphify update` del CLI falla por `flock` en FUSE; rodeo documentado: llamada
  interna con `acquire_lock=False`.

## 5. Verificación

- Checkers locales en verde antes de cada commit (los de `_tmp` se perdieron en una
  limpieza ajena; quedan `tools/check_composable.py` + `tools/chk_forma.py` + CI).
- CI 7 jobs por push; `chk_forma.py`: 0 tipos incompatibles contra el CLI vivo.
- Instalación por `pm` con copia previa a `/data/local/tmp` (SELinux bloquea /sdcard).
