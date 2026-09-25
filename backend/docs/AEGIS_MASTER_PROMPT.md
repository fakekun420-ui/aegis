# AEGIS — MASTER EXECUTION PROMPT
## Instrucciones Maestras de Implementación Completa

---

## IDENTIDAD DEL PROYECTO

**Nombre:** Aegis  
**Descripción:** Mobile Development Hub — Centro de control de desarrollo, IA, automatización y producción digital ejecutándose desde un POCO F3 con Android 15, root Magisk, Ubuntu chroot.  
**Repositorio principal:** `/sdcard/projects/Aegis/`  
**Backend:** `/sdcard/projects/Aegis/backend/` (Node.js, puerto 8765)  
**App Android:** `/sdcard/projects/Aegis/app/` (Jetpack Compose)  
**Agentes:** `/sdcard/projects/Aegis/agents/` (agency-agents)  
**Workspace de proyectos:** `/sdcard/projects/` (visible desde Ubuntu como `/sdcard/projects/`, desde Android como `/data/media/0/projects/`)

---

## REGLAS CRÍTICAS — LEER ANTES DE EJECUTAR

1. **NUNCA** borres `providers.js` ni `server.js` originales hasta que la migración esté 100% verificada y los tests pasen.
2. Ejecuta `node -c <archivo>` después de crear o modificar cualquier archivo JS.
3. Ejecuta `timeout 10 node server.js` para verificar que el servidor levanta después de cada fase.
4. Si cualquier verificación falla, ejecuta `git checkout <archivo>` para revertir y reporta el error.
5. Haz un `git commit` al finalizar cada FASE completa.
6. El `WORKSPACE_ROOT="/sdcard/projects"` en `pathResolver.js` es la única fuente de verdad de rutas — nunca uses rutas absolutas hardcodeadas en otros módulos.
7. Valida siempre `projectId` con regex `/^[a-zA-Z0-9\-_]+$/` antes de construir rutas de filesystem.
8. NO uses `require()` — el proyecto usa ES Modules (`import`/`export`).
9. Ejecuta UN paso a la vez, verifica, luego continúa.
10. Si un paso falla, detente y reporta antes de continuar.

---

## ESTADO ACTUAL DEL PROYECTO (COMPLETADO)

Las siguientes fases ya fueron implementadas y commiteadas:

- ✅ **Fase 0** — Auditoría técnica completa
- ✅ **Fase 1** — Modularización de `providers.js` con patrón Strangler Fig
- ✅ **Fase 2** — Extracción del normalizer y del providerManager, limpieza de imports
  (**realidad verificada con grep**: NO existe `src/core/normalizer.js` ni
  `src/core/providerManager.js` — `normalizeMessage` y `ProviderManager` viven
  exportados en `backend/providers.js`, que es de donde los importan `server.js`
  y los adapters)
- ✅ **Fase 3** — `ClaudeCodeAdapter.js`, `GitAdapter.js`, `SkillManager.js`, `SkillInvoker.js`, `skillsRoutes.js`
- ✅ **Fase 3.5** — Integración de rutas `/api/skills` y `/api/projects/:id/path` en `server.js`
- ✅ **Fase 4** — `projectManager.js`, `projectRoutes.js`, estructura `.hub/` por proyecto

### Estructura actual de `/sdcard/projects/Aegis/backend/src/`:
```
src/
├── adapters/
│   ├── AntigravityAdapter.js
│   ├── BaseProviderAdapter.js
│   ├── ClaudeCodeAdapter.js
│   ├── GitAdapter.js
│   └── OpenCodeAdapter.js
├── agents/
│   ├── ArchitectAgent.js
│   ├── AuditorAgent.js
│   ├── BaseAgent.js
│   └── ResearchAgent.js
├── api/
│   ├── agentRoutes.js
│   ├── bootstrapRoutes.js
│   ├── contentRoutes.js
│   ├── jobRoutes.js
│   ├── projectRoutes.js
│   ├── setupRoutes.js
│   ├── skillsRoutes.js
│   └── workflowRoutes.js
├── bootstrap/
│   ├── orchestrator.js
│   ├── state.js
│   ├── steps.js
│   └── {node,ubuntu,skills}-manifest.json
├── core/
│   ├── agentPool.js
│   ├── eventBus.js
│   ├── jobScheduler.js
│   ├── logger.js
│   ├── pathResolver.js
│   ├── projectManager.js
│   ├── storage.js          ← fuente única: FileMutex/fileMutex/atomic*/loadProjectsStore
│   ├── workflowEngine.js
│   └── workflowParser.js
├── plugins/
│   └── content/index.js
├── skills/
│   ├── SkillInvoker.js
│   ├── SkillManager.js
│   └── catalog.json
└── workflows/
    └── templates/
```
(No hay `normalizer.js` ni `providerManager.js` en `core/`: `normalizeMessage` y
`ProviderManager` están en `backend/providers.js`. Tampoco hay `modelRouter.js` /
`taskClassifier.js`: ver NOTA de la Fase 7.)

---

## FASE 5 — SISTEMA DE AGENTES ASÍNCRONOS

### Objetivo
Implementar la infraestructura base para agentes que operan en background, se comunican mediante artefactos (archivos) y eventos, sin depender de chat humano directo.

### Paso 5.1 — Event Bus
Crea `src/core/eventBus.js`:
- Implementa un EventEmitter extendido con soporte para canales por `projectId`
- Métodos: `subscribe(projectId, event, handler)`, `publish(projectId, event, data)`, `unsubscribe(projectId, event, handler)`
- Eventos predefinidos como constantes exportadas: `AGENT_STARTED`, `AGENT_COMPLETED`, `AGENT_FAILED`, `ARTIFACT_CREATED`, `WORKFLOW_STARTED`, `WORKFLOW_COMPLETED`, `WORKFLOW_FAILED`
- Logging de cada evento con timestamp

### Paso 5.2 — Base Agent
Crea `src/agents/BaseAgent.js`:
- Clase abstracta con propiedades: `id`, `name`, `projectId`, `status` (idle/running/completed/failed)
- Métodos abstractos: `execute(context)`, `validate(artifact)`
- Métodos concretos: `readArtifact(filename)` (lee de `docs/` del proyecto), `writeArtifact(filename, content, metadata)` (escribe con frontmatter YAML), `log(message)`, `emit(event, data)`
- El frontmatter YAML de cada artefacto debe incluir: `version`, `last_updated`, `owned_by`, `state` (DRAFT/FINAL), `dependencies`
- Integra con `EventBus` para publicar eventos de ciclo de vida
- Integra con `SkillInvoker` para que los agentes puedan invocar skills

### Paso 5.3 — Agentes Especializados Iniciales
Crea los siguientes agentes en `src/agents/`:

**`ResearchAgent.js`**
- Hereda de `BaseAgent`
- `execute(context)`: lee el proyecto, invoca `graphify` via `SkillInvoker` si está disponible, genera `docs/RESEARCH.md` con análisis del repositorio
- Si `graphify` no está disponible, usa `ls -la` y `find` para mapear estructura

**`ArchitectAgent.js`**
- Hereda de `BaseAgent`
- `execute(context)`: lee `docs/RESEARCH.md` y `docs/REQ.md` si existe, invoca LLM via el adapter configurado en el contexto, genera `docs/ARCHITECTURE.md`
- El prompt al LLM debe incluir el contenido de los artefactos de dependencia

**`AuditorAgent.js`**
- Hereda de `BaseAgent`
- `execute(context)`: lee artefactos del proyecto, verifica que todos tengan frontmatter válido, genera `docs/AUDIT.md` con reporte de estado

### Paso 5.4 — Agent Pool
Crea `src/core/agentPool.js`:
- Registro de agentes disponibles por tipo
- Cola de tareas por `projectId` (máximo 2 agentes concurrentes por proyecto para proteger RAM)
- Métodos: `register(agentClass)`, `dispatch(agentType, projectId, context)`, `getStatus(projectId)`, `cancelAll(projectId)`
- Integra con `EventBus`

### Paso 5.5 — API de Agentes
Crea `src/api/agentRoutes.js`:
- `GET /api/agents` — lista agentes disponibles
- `POST /api/agents/dispatch` — despacha un agente: `{ agentType, projectId, context }`
- `GET /api/agents/status/:projectId` — estado de agentes activos en un proyecto
- `DELETE /api/agents/:projectId` — cancela todos los agentes de un proyecto
- `GET /api/agents/events/:projectId` — SSE stream de eventos del EventBus para un proyecto

### Paso 5.6 — Integración en server.js
Monta `agentRoutes` en `server.js` sin modificar rutas existentes.

### Paso 5.7 — Verificación
```bash
node -c src/core/eventBus.js
node -c src/agents/BaseAgent.js
node -c src/agents/ResearchAgent.js
node -c src/agents/ArchitectAgent.js
node -c src/agents/AuditorAgent.js
node -c src/core/agentPool.js
node -c src/api/agentRoutes.js
timeout 10 node server.js
```

### Paso 5.8 — Commit
```bash
git add src/agents/ src/core/eventBus.js src/core/agentPool.js src/api/agentRoutes.js
git commit -m "feat: Phase 5 - async agent system, EventBus, AgentPool, specialized agents"
```

---

## FASE 6 — WORKFLOW ENGINE

### Objetivo
Motor que procesa DAGs (Grafos Acíclicos Dirigidos) definidos en YAML/JSON, orquestando agentes en secuencia o en paralelo.

### Paso 6.1 — Workflow Parser
Crea `src/core/workflowParser.js`:
- Lee archivos YAML o JSON de workflows desde `workflows/` del proyecto
- Valida estructura: `id`, `trigger`, `steps[]` donde cada step tiene `id`, `agent`, `depends_on[]`, `inputs[]`, `outputs[]`, `model` (opcional)
- Detecta ciclos en el DAG y reporta error si existen
- Retorna el DAG en forma de grafo ordenado topológicamente

### Paso 6.2 — Workflow Engine
Crea `src/core/workflowEngine.js`:
- `run(workflowId, projectId, trigger)`: carga el workflow del proyecto, ejecuta el DAG
- Pasos sin `depends_on` se ejecutan en paralelo (Promise.all con límite de concurrencia)
- Pasos con `depends_on` esperan a que sus dependencias completen
- Estado del workflow persistido en `.hub/state.json` del proyecto
- Integra con `EventBus`: publica `WORKFLOW_STARTED`, `WORKFLOW_COMPLETED`, `WORKFLOW_FAILED`
- Integra con `AgentPool` para despachar cada step
- Timeout configurable por workflow (default 30 minutos)

### Paso 6.3 — Workflows Iniciales
Crea los siguientes archivos en `src/workflows/templates/`:

**`project-analysis.yaml`** — Análisis automático de proyecto clonado:
```yaml
id: project-analysis
trigger: manual
timeout: 1800
steps:
  - id: research
    agent: ResearchAgent
    outputs: ["docs/RESEARCH.md"]
  - id: audit
    agent: AuditorAgent
    depends_on: [research]
    outputs: ["docs/AUDIT.md"]
```

**`software-project.yaml`** — Desarrollo de software completo:
```yaml
id: software-project
trigger: manual
steps:
  - id: research
    agent: ResearchAgent
    outputs: ["docs/RESEARCH.md"]
  - id: architect
    agent: ArchitectAgent
    depends_on: [research]
    inputs: ["docs/RESEARCH.md", "docs/REQ.md"]
    outputs: ["docs/ARCHITECTURE.md"]
  - id: auditor
    agent: AuditorAgent
    depends_on: [architect]
    outputs: ["docs/AUDIT.md"]
```

**`quant-research.yaml`** — Pipeline de investigación cuantitativa:
```yaml
id: quant-research
trigger: manual
steps:
  - id: research
    agent: ResearchAgent
    outputs: ["docs/RESEARCH.md"]
  - id: hypothesis
    agent: ArchitectAgent
    depends_on: [research]
    outputs: ["docs/HYPOTHESIS.md"]
  - id: audit
    agent: AuditorAgent
    depends_on: [hypothesis]
    outputs: ["docs/AUDIT.md"]
```

### Paso 6.4 — API de Workflows
Crea `src/api/workflowRoutes.js`:
- `GET /api/workflows/:projectId` — lista workflows disponibles en el proyecto
- `POST /api/workflows/:projectId/run` — ejecuta un workflow: `{ workflowId }`
- `GET /api/workflows/:projectId/status` — estado del workflow activo
- `DELETE /api/workflows/:projectId/cancel` — cancela workflow activo
- `GET /api/workflows/:projectId/events` — SSE stream de progreso

### Paso 6.5 — Inicialización de Workflows en Proyectos
En `projectManager.js`, cuando se inicializa un proyecto con `.hub/`, copia automáticamente los templates de workflows a `workflows/` del proyecto si no existen.

### Paso 6.6 — Integración en server.js
Monta `workflowRoutes` sin tocar rutas existentes.

### Paso 6.7 — Verificación y Commit
```bash
timeout 10 node server.js
git add src/core/workflowParser.js src/core/workflowEngine.js src/workflows/ src/api/workflowRoutes.js
git commit -m "feat: Phase 6 - DAG workflow engine, templates, workflow API routes"
```

---

## FASE 7 — MODEL ROUTING

### Objetivo
Sistema de enrutamiento dinámico que selecciona automáticamente el modelo y adapter más apropiado según el tipo y complejidad de la tarea.

### Paso 7.1 — Task Classifier
Crea `src/core/taskClassifier.js`:
- `classify(prompt, context)`: analiza el prompt y retorna `{ complexity, type, recommendedAdapter, recommendedModel }`
- Tipos de tarea: `architecture`, `coding`, `research`, `qa`, `quick`, `content`
- Complejidad: `low`, `medium`, `high`
- Clasificación basada en palabras clave y longitud del prompt (sin LLM para evitar overhead)
- Ejemplos de reglas:
  - Keywords `["diseña", "arquitectura", "sistema", "planifica"]` → `architecture`, `high`
  - Keywords `["bug", "error", "fix", "corrige"]` → `coding`, `medium`
  - Keywords `["resume", "qué es", "explica"]` → `quick`, `low`

### Paso 7.2 — Model Router
Crea `src/core/modelRouter.js`:
- `route(task, availableAdapters)`: recibe clasificación de tarea y retorna el adapter y modelo recomendados
- Tabla de routing configurable en `.hub/config.json` del proyecto o config global
- Tabla default:
  - `architecture + high` → adapter con mayor capacidad disponible
  - `coding + medium/high` → `ClaudeCodeAdapter` si disponible, sino `AntigravityAdapter`
  - `quick + low` → `AntigravityAdapter` con modelo flash
  - `qa + low` → `AntigravityAdapter` con modelo flash
- Si el adapter recomendado no está disponible, fallback al siguiente disponible

### Paso 7.3 — Integración en el Gateway
En `server.js`, en el endpoint de mensajes, si el header `X-Provider` es `auto`:
1. Invoca `TaskClassifier.classify(prompt)`
2. Invoca `ModelRouter.route(task, providerManager.getAvailableAdapters())`
3. Sobreescribe el adapter interno con el resultado
4. Agrega header de respuesta `X-Routed-To` con el adapter seleccionado

### Paso 7.4 — Verificación y Commit
```bash
timeout 10 node server.js
git add src/core/taskClassifier.js src/core/modelRouter.js
git commit -m "feat: Phase 7 - task classifier and model router for auto provider selection"
```

> **NOTA — Backlog técnico F0-F2 (decisión documentada): ambos ELIMINADOS.**
> `taskClassifier.js` y `modelRouter.js` se crearon en Fase 7 pero **nunca se
> cablearon**: grep de consumidores antes de eliminar = 0 en runtime (sólo se
> importaban entre sí; `server.js` jamás los invocaba — ARQ-04/BUG-05/06 de las
> auditorías; el paso 7.3 `X-Provider: auto` tampoco existía en el código).
> Cablearlos habría CAMBIADO el comportamiento por defecto: `route()` clasifica
> POR PROMPT ("fix/bug/corrige" → adapter `claudecode`, "diseña/arquitectura" →
> `gemini-3.1-pro`) en lugar de caer siempre a `antigravity` +
> `gemini-3.8-flash-high`, y `providers.json` no tiene sección de modelos que
> consumir — el "fallback idéntico cuando no haya config" es imposible con esa
> diseño. Decisión: **eliminar ambos módulos** (grep tras la eliminación: 0);
> el hardcode `antigravity` + `gemini-3.8-flash-high` queda como default
> INTENCIONAL comentado en `server.js` (junto a `body.model`), y el proveedor
> por defecto sigue configurable vía `providers.json` /
> `POST /api/providers/default`. Si en el futuro se quiere routing por tarea,
> deberá ser config-driven (no prompt-driven) para no romper el default.

---

## FASE 8 — AUTOMATION Y JOBS

### Objetivo
Sistema de jobs recurrentes y automatización de tareas en background.

### Paso 8.1 — Job Scheduler
Crea `src/core/jobScheduler.js`:
- Sistema simple de jobs recurrentes basado en `setInterval` y timestamps
- Jobs predefinidos:
  - `graphify-watch`: cada 6 horas, si `graphify` está instalado, re-indexa proyectos que tuvieron cambios git recientes
  - `health-check`: cada 5 minutos, verifica que OpenCode daemon (:4096) y otros servicios estén activos
  - `cleanup-zombies`: cada 30 minutos, limpia procesos zombie (reutilizando lógica de `AntigravityAdapter`)
- Persistencia de jobs en `.hub/jobs.json` del workspace
- Métodos: `start()`, `stop()`, `registerJob(id, interval, handler)`, `listJobs()`, `getLastRun(jobId)`

### Paso 8.2 — API de Jobs
Crea `src/api/jobRoutes.js`:
- `GET /api/jobs` — lista jobs registrados con estado y último run
- `POST /api/jobs/:id/run` — ejecuta job manualmente
- `PATCH /api/jobs/:id` — habilita/deshabilita job: `{ enabled: bool }`

### Paso 8.3 — Arranque automático
En `server.js`, al inicializar, arranca el `JobScheduler` después de que el servidor esté listo.

### Paso 8.4 — Verificación y Commit
```bash
timeout 10 node server.js
git add src/core/jobScheduler.js src/api/jobRoutes.js
git commit -m "feat: Phase 8 - job scheduler, automation, background tasks"
```

---

## FASE 9 — CONTENT SYSTEM (DOMINIO AISLADO)

### Objetivo
Integrar producción de contenido como un dominio independiente (plugin), sin contaminar la arquitectura principal.

### Paso 9.1 — Content Domain Plugin
Crea `src/plugins/content/index.js`:
- Plugin autocontenido que expone sus propias rutas bajo `/api/content/`
- Interfaz con el hub únicamente via `EventBus` y `SkillInvoker`
- **NO** importa directamente `providers.js`, `server.js` ni adaptadores del core

### Paso 9.2 — Content Workflow
Crea `src/plugins/content/contentWorkflow.js`:
- Pipeline: `research → strategy → script → prompts → publish`
- Cada paso genera un artefacto en `content/` del proyecto
- Artefactos: `RESEARCH.md`, `STRATEGY.md`, `SCRIPT.md`, `PROMPTS.md`, `PUBLISH_LOG.md`

### Paso 9.3 — Money Printer Integration Stub
Crea `src/plugins/content/moneyPrinterAdapter.js`:
- Stub (placeholder) para futura integración con Money Printer Turbo
- Métodos: `isAvailable()` (verifica si el repo existe en `/sdcard/projects/MoneyPrinterTurbo/`), `generateVideo(script, outputDir)` (executa el script principal del repo via spawn con cwd aislado), `getStatus(jobId)`
- Documenta claramente que este es un stub y los métodos reales se implementarán cuando se clone el repo

### Paso 9.4 — API de Content
Crea `src/api/contentRoutes.js`:
- `GET /api/content/status` — estado del plugin de contenido
- `POST /api/content/workflow/run` — inicia pipeline de contenido para un proyecto
- `GET /api/content/money-printer/status` — verifica disponibilidad de Money Printer

### Paso 9.5 — Verificación y Commit
```bash
timeout 10 node server.js
git add src/plugins/ src/api/contentRoutes.js
git commit -m "feat: Phase 9 - content domain plugin, MoneyPrinter stub, content workflow"
```

---

## FASE 10 — OPTIMIZACIÓN Y SEGURIDAD

### Objetivo
Hardening de seguridad, optimización de memoria y observabilidad.

### Paso 10.1 — Security Hardening
En `server.js` y todos los endpoints:
- **Token (REALIDAD — F0/F4 implementado):** header `X-Aegis-Token` validado en el
  middleware de `server.js` con comparación **timing-safe** (`crypto.timingSafeEqual`).
  El token NO vive sólo en memoria: se persiste en `backend/.aegis_token`
  (mode 0600, gitignored) para que la app Android lo lea vía root
  (`ApiClient.authInterceptor` / `TokenProvider`). Cobertura: **todas las rutas
  `/api/*` y `/opencode/*`** (el cierre de `/opencode/*` es del backlog F0-F2,
  revisión A-1); única exención sin token: `GET /api/health` (sonda de keepalive.sh).
- **Rate limiting (REALIDAD):** sliding window en memoria, **120 req/min por IP por
  defecto** (`AEGIS_RATE_LIMIT_N`; `AEGIS_RATE_LIMIT=0` lo desactiva en tests),
  exentos `GET /api/health` y `GET /api/bootstrap/state`, respuesta 429 con
  envelope `{ok:false,error:{code:"RATE_LIMITED"}}` + header `Retry-After`.
- Audita todos los endpoints que aceptan `projectId` y verifica que usen `getProjectAbsPath()` con validación de regex
- Agrega middleware que loguea cada request con timestamp, método, path y tiempo de respuesta (sin loguear bodies que puedan contener secrets)

### Paso 10.2 — Memory Optimization
- En `AntigravityAdapter.js`, verifica que `cleanupZombieProcesses()` se invoca correctamente y no acumula referencias
- En `AgentPool`, implementa límite estricto de 2 agentes concurrentes por proyecto
- En `JobScheduler`, implementa `clearInterval` apropiado en el método `stop()`
- Agrega endpoint `GET /api/system/memory` que retorna uso de memoria Node.js via `process.memoryUsage()`

### Paso 10.3 — Observabilidad
Crea `src/core/logger.js`:
- Logger estructurado con niveles: `debug`, `info`, `warn`, `error`
- Formato: `[TIMESTAMP] [LEVEL] [MODULE] message {context}`
- **Realidad (F4):** NO mantiene "las últimas 1000 líneas en memoria" como fuente
  de verdad — el sink propio es `backend/logs/aegis.log` con **rotación por tamaño**
  (1 MB, 3 backups `.1/.2/.3`, dir redirigible con `AEGIS_LOG_DIR`); el array
  in-memory de 1000 entradas sigue existiendo como buffer secundario del `Logger`.
- Exporta un logger por módulo: `createLogger(moduleName)`

Crea endpoint `GET /api/system/logs` que retorna las últimas N líneas del log
(**REALIDAD F4:** lee el fichero rotado del sink — `getLogFile()` — no sólo el
buffer en memoria; params `?lines=` y legacy `?limit=`).

### Paso 10.4 — Health Dashboard Completo
Actualiza o crea `GET /api/system/health` con respuesta completa:
```json
{
  "ok": true,
  "data": {
    "server": "running",
    "port": 8765,
    "uptime": 3600,
    "memory": { "heapUsed": "...", "heapTotal": "..." },
    "workspace": "/sdcard/projects",
    "projects": 5,
    "agents": { "active": 0, "registered": 3 },
    "jobs": { "active": 3, "lastRun": "..." },
    "skills": { "installed": ["graphify", "opencode-mem"] },
    "adapters": { "opencode": "healthy", "antigravity": "healthy" }
  }
}
```

### Paso 10.5 — Verificación Final Completa
```bash
node -c server.js
find src/ -name "*.js" | xargs node -c
timeout 15 node server.js
git log --oneline -15
find src/ -name "*.js" | sort
```

### Paso 10.6 — Commit Final
```bash
git add -A
git commit -m "feat: Phase 10 - security hardening, memory optimization, observability, health dashboard"
git tag v1.0.0-aegis-hub
```

---

## FASE 11 — APP ANDROID (Jetpack Compose)

### Objetivo
Actualizar la app Android para reflejar la nueva arquitectura del Hub con pantallas de Control Center, Skill Manager y Project Workspace.

**Ubicación:** `/sdcard/projects/Aegis/app/`

### Paso 11.1 — Nueva Pantalla: Control Center
Crea `app/src/main/java/com/aegis/ui/screens/ControlCenterScreen.kt`:
- Consume `GET /api/system/health` via Retrofit
- Muestra: estado del servidor, memoria usada, adaptadores activos, agentes corriendo, jobs activos
- Refresco automático cada 10 segundos
- Colores de estado: verde (ok), amarillo (warning), rojo (error)
- Estética consistente con el resto de la app: fondo `#181816`, texto `#E6EDF3`, fuente monospace

### Paso 11.2 — Nueva Pantalla: Skill Manager
Crea `app/src/main/java/com/aegis/ui/screens/SkillManagerScreen.kt`:
- Consume `GET /api/skills` para listar skills instaladas y disponibles
- Cards por skill con: nombre, versión, descripción, botón Instalar/Desinstalar
- Al instalar: muestra overlay de terminal con SSE stream del progreso
- Al configurar: BottomSheet con campos dinámicos según el esquema de la skill
- ViewModel: `SkillManagerViewModel.kt`

### Paso 11.3 — Nueva Pantalla: Project Workspace
Crea `app/src/main/java/com/aegis/ui/screens/WorkspaceScreen.kt`:
- Consume `GET /api/workspace/projects` para listar proyectos en `/sdcard/projects/`
- Cards por proyecto con: nombre, estado `.hub/`, último commit git si disponible
- Botones: Abrir (navega al chat del proyecto), Inicializar Hub, Lanzar Workflow, Ver Agentes
- ViewModel: `WorkspaceViewModel.kt`

### Paso 11.4 — Nueva Pantalla: Workflow Runner
Crea `app/src/main/java/com/aegis/ui/screens/WorkflowScreen.kt`:
- Lista workflows disponibles para el proyecto seleccionado
- Botón Run por workflow
- SSE stream del progreso mostrando cada step del DAG con estado (pending/running/completed/failed)
- Visualización tipo pipeline horizontal: `Research → Architect → QA`

### Paso 11.5 — Actualizar Navegación
Actualiza `AppNavHost.kt`:
- Agrega rutas: `control_center`, `skill_manager`, `workspace`, `workflow/:projectId`
- Actualiza el Navigation Drawer o Bottom Navigation con las nuevas secciones:
  - 🏠 Control Center
  - 📁 Projects
  - 🔧 Skills
  - 💬 Chat (pantalla actual)

### Paso 11.6 — Actualizar ApiService
Actualiza `ApiService.kt` con los nuevos endpoints:
- `getSystemHealth()`, `getSkills()`, `installSkill()`, `getProjects()`, `runWorkflow()`, etc.

### Paso 11.7 — Build y Verificación
```bash
cd /sdcard/projects/Aegis/app
./gradlew assembleDebug
```
Verifica que el APK compila sin errores.

### Paso 11.8 — Commit
```bash
git add app/src/
git commit -m "feat: Phase 11 - Android Control Center, Skill Manager, Workspace, Workflow screens"
```

---

## VERIFICACIÓN FINAL DEL PROYECTO COMPLETO

Después de completar todas las fases, ejecuta:

```bash
# 1. Verificar estructura completa del backend
find /sdcard/projects/Aegis/backend/src/ -name "*.js" | sort

# 2. Syntax check de todos los archivos
find /sdcard/projects/Aegis/backend/src/ -name "*.js" | xargs node -c

# 3. Servidor levanta limpiamente
cd /sdcard/projects/Aegis/backend && timeout 15 node server.js

# 4. Historial de commits
git log --oneline

# 5. Tag de versión final
git tag v1.0.0-aegis-hub
```

---

## ENTREGA FINAL ESPERADA

Al completar todas las fases, Aegis debe ser capaz de:

1. **Gestionar proyectos** independientes en `/sdcard/projects/` con workspaces aislados
2. **Ejecutar agentes** especializados (Research, Architect, Auditor) en background
3. **Orquestar workflows** DAG definidos en YAML sin intervención humana
4. **Enrutar automáticamente** tareas al modelo más apropiado
5. **Gestionar skills** (graphify, opencode-mem) desde una UI visual en el móvil
6. **Ejecutar jobs** recurrentes de mantenimiento y monitoreo
7. **Producir contenido** via pipeline aislado (Money Printer stub listo para activar)
8. **Mostrar un Control Center** en la app Android con estado en tiempo real

---

## NOTAS PARA EL AGENTE EJECUTOR

- Lee este archivo completo antes de ejecutar cualquier paso
- Ante cualquier duda sobre una decisión arquitectónica, consulta `docs/ARCHITECTURE.md` del proyecto
- Los proyectos payload (Quant-Math, MoneyPrinterTurbo) son **independientes** — el Hub los gestiona externamente, nunca los importa como dependencias de código
- La separación `Android (/data/media/0/projects/)` vs `Ubuntu (/sdcard/projects/)` es una dualidad física del mismo filesystem — nunca mezcles las rutas entre contextos
- Si encuentras código que usa `require()`, conviértelo a `import` ES Module
- Ante cualquier error de permisos, verifica que estás operando dentro del chroot de Ubuntu con uid 0

---

*Generado para el proyecto Aegis — Mobile Development Hub*  
*Fecha: 2026-09-22*  
*Versión del prompt: 1.0*
