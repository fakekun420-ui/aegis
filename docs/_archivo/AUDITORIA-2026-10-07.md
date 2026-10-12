# AUDITORÍA 2026-10-07 — tras el plan de estabilización F0–F10 (código)

Segunda crónica del sistema. La anterior (`AUDITORIA-2026-10-06.md`) no se
reescribe: esto es lo que cambió con el plan ejecutado (fases de código en
ramas `estabilizar/*`, pendientes solo de validación en el móvil y merges).

## 1. Qué se estabilizó (por fase, con evidencia CI)

| Fase | Cambio | CI |
|---|---|---|
| F0 | `AegisTrace` (contador debug), StrictMode, veto de `su` en main, `1.1.2` | ✅ main |
| F1 | 11 métodos muertos fuera, provider fuera, vocabulario Hub fuera, `ErroresRed` real, docs a la realidad, CI sin `backend-checks` | ✅ rama |
| F2 | `SesionesRepo`: un POST, avisos, idempotencia 3 s; costura fachada; VMs al repo | ✅ rama |
| F3 | `SesionConfigRepo`: servidor-primero, guardia anti-carrera, rollback, skip 60 s | ✅ rama |
| F4 | `CatalogoRepo` TTL 5 min; abrir = leer+cola+historial; poll por tail | ✅ rama |
| F5 | `EventosServidor` + `ChatSync` + cableado tras `SYNC_POR_EVENTOS=false`; fixtures de captura real | ✅ rama (260+ tests) |
| F7 | Lotes 1-`su`, TTL skills/health, `Raiz` (semáforo 2), regex a consts, guard en CI | ✅ rama |
| F8 | `Models.kt` → `data/modelos/`; `Sesion`/`Agente`/`ModeloElegible`/`ModeloRef`; fuera `ModelsHub` | ⏳ rama |
| F10 | `FakeOpenCode` + 6 flujos + costura inyectable | ⏳ rama |
| F6 | Bloqueada: requiere `su -c cat` del script en el dispositivo | ⬜ |
| F9 | Bloqueada por condición de entrada (F1–F7 estables 3 días en móvil) | ⬜ |

## 2. Diagrama de flujo actualizado

```mermaid
flowchart TD
    subgraph ARRANQUE["Arranque (frío o app)"]
        BOOT["service.d/99-aegis-opencode.sh\nmonta chroot (con espera)\nlanza serve --service (guarda pgrep)"]
        LAUNCH["OpenCodeLauncher.asegurarAbierto\nsonda HTTP → lanza si no hay proceso"]
        BOOT --> SRV
        LAUNCH --> SRV
    end
    subgraph MOVIL["Móvil"]
        APP["Aegis APK\nCompose + ViewModels"]
        SRV["opencode serve --service\n127.0.0.1:49374\nBasic (service.json del chroot)"]
    end
    APP -->|HTTP Basic\n(interceptor + reintento 401)| SRV

    subgraph CHAT["Abrir chat (F4)"]
        LOAD["ChatViewModel.load(sid)\n1. cargarConfig: 1 GET (titulo+modelo+agente)\n2. cola rapida (pinta ya) + historial reconcilia\n3. catalogo (0 en caliente)"]
    end
    subgraph ENVIAR["Enviar mensaje"]
        FIX["SesionConfigRepo.fijarModelo\n(skip 60 s si mismo valor + lectura fresca)"]
        PROMPT["POST /session/id/prompt\n(acepta texto+archivos+agentes, NO modelo)"]
        SYNC["F5 off: poll tail(50)+mergeTail\nF5 on: ChatSync (SSE + reconcilia)"]
        FIX --> PROMPT --> SYNC
    end
    subgraph PROYECTO["Proyecto vinculado (F2)"]
        NEW["SesionesRepo.crear:\n1 POST + agente/modelo/vinculo (avisos)\nidempotencia 3 s"]
        LIST["load: getProjects + getProjectSessions\n+ getSkills (1 su, TTL 10 s)"]
        NEW --> LIST
    end
    subgraph MODELOS["Modelos (F3/F4)"]
        CAT["CatalogoRepo TTL 5 min\n(single-flight, gratis al cachear)"]
        SEL["select optimista con reversa\n(servidor primero, prefs despues)"]
        CAT --> SEL
    end
    APP --> CHAT --> ENVIAR
    APP --> PROYECTO
    APP --> MODELOS
```

Reglas del flujo (todas con causa medida, no volver a discutir):
- El servidor es la única verdad de modelo/agente/título/mensajes; prefs = caché.
- Un camino por operación (`SesionesRepo`); éxito parcial = avisos, nunca fallo mudo.
- Un canal de sincronización (F5 tras flag); el fin de turno solo lo dice
  `session.execution.succeeded`, nunca el fin de un segmento.
- Lo caro se pide una vez (catálogo TTL, `su` en lote con TTL, `Raiz` con semáforo).
- Ningún `su`/red/E-S en el hilo principal (StrictMode + `check_hilo_principal` en CI).
- Ningún `Regex()` en caliente (constantes; `esTituloTecnico` única).
- Ningún fallo mudo en botones (`GUARD-SILENCIO-OK` donde el silencio es diseño).

## 3. Deuda que queda (F6, F9, validación)

- **F6**: un solo lanzador + `ServidorOpenCode`. Bloqueada en T-F6.1 (copiar el
  script del dispositivo con `su -c cat`).
- **F9**: partir `RutaNativa`/`ChatViewModel`/`ChatScreen`/demás dioses. Bloqueada
  por su condición de entrada (3 días estables en móvil).
- **Validación móvil**: `PERF-BASELINE.md` (cifras "antes"), V-01…V-11 por fase y
  merges a `main`. Sin esto, F1–F5/F7/F8/F10 no se fusionan.
