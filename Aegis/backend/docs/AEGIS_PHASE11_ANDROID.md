# AEGIS — PHASE 11: ANDROID APP
## Instrucciones de Implementación — Jetpack Compose UI

---

## CONTEXTO DEL PROYECTO

**Proyecto:** Aegis — Mobile Development Hub  
**Dispositivo:** POCO F3, Android 15, root Magisk, Ubuntu chroot  
**App ubicación:** `/sdcard/projects/Aegis/app/`  
**Backend URL:** `http://127.0.0.1:8765` (Node.js corriendo en chroot)  
**Framework:** Jetpack Compose + Material 3 + Retrofit + OkHttp + Kotlin Coroutines

---

## REGLAS CRÍTICAS

1. **LEE** primero la estructura actual completa antes de crear cualquier archivo.
2. **NO** modifiques archivos existentes que funcionan sin entender su impacto.
3. **NO** cambies el tema visual existente: fondo `#181816`, texto `#E6EDF3`, fuentes monospace, cursor `▋`.
4. **USA** el patrón MVVM existente: ViewModel + StateFlow + LaunchedEffect.
5. **USA** el cliente Retrofit/OkHttp existente en `ApiClient.kt` — no crees uno nuevo.
6. **AGREGA** rutas nuevas a `AppNavHost.kt` sin eliminar las existentes.
7. **VERIFICA** que el proyecto compila con `./gradlew assembleDebug` al finalizar cada pantalla.
8. Haz `git commit` al finalizar cada pantalla completa.
9. Si `./gradlew assembleDebug` falla, revierte con `git checkout` y reporta el error.
10. **NO** uses `require()` ni código Java — solo Kotlin.

---

## PASO 0 — LECTURA Y ANÁLISIS INICIAL

Antes de escribir una sola línea de código, ejecuta y analiza:

```bash
# Estructura completa de la app
find /sdcard/projects/Aegis/app/src/main -type f | sort

# Archivos de navegación y tema actuales
cat /sdcard/projects/Aegis/app/src/main/java/com/aegis/ui/AppNavHost.kt
cat /sdcard/projects/Aegis/app/src/main/java/com/aegis/ui/theme/Theme.kt
cat /sdcard/projects/Aegis/app/src/main/java/com/aegis/network/ApiService.kt
cat /sdcard/projects/Aegis/app/src/main/java/com/aegis/network/ApiClient.kt
cat /sdcard/projects/Aegis/app/src/main/java/com/aegis/ui/screens/ChatScreen.kt

# Dependencias actuales
cat /sdcard/projects/Aegis/app/build.gradle
cat /sdcard/projects/Aegis/app/build.gradle.kts 2>/dev/null || true
```

Reporta:
- Nombre del package actual (ej. `com.opencode.companion` o similar)
- Versión de Compose BOM
- Estructura de carpetas de screens y viewmodels existentes
- Cómo está configurada la navegación actualmente
- Qué colores y tema usa actualmente

**USA el package name real que encuentres — no inventes uno.**

---

## PASO 1 — ACTUALIZAR ApiService.kt

Agrega los siguientes endpoints al `ApiService.kt` existente **sin eliminar los existentes**:

```kotlin
// System
@GET("api/system/health")
suspend fun getSystemHealth(): Response<HealthResponse>

@GET("api/system/logs")
suspend fun getSystemLogs(@Query("limit") limit: Int = 100): Response<LogsResponse>

@GET("api/system/memory")
suspend fun getSystemMemory(): Response<MemoryResponse>

// Skills
@GET("api/skills")
suspend fun getSkills(): Response<SkillsResponse>

@POST("api/skills/install")
suspend fun installSkill(@Body body: InstallSkillRequest): Response<TaskResponse>

@DELETE("api/skills/{id}")
suspend fun uninstallSkill(@Path("id") skillId: String): Response<BaseResponse>

@GET("api/skills/{id}/config")
suspend fun getSkillConfig(@Path("id") skillId: String): Response<SkillConfigResponse>

@PATCH("api/skills/{id}/config")
suspend fun updateSkillConfig(
    @Path("id") skillId: String,
    @Body config: Map<String, Any>
): Response<BaseResponse>

// Workspace / Projects
@GET("api/workspace/projects")
suspend fun getWorkspaceProjects(): Response<ProjectsResponse>

@POST("api/workspace/projects/{id}/init")
suspend fun initProject(@Path("id") projectId: String): Response<BaseResponse>

@GET("api/workspace/projects/{id}/state")
suspend fun getProjectState(@Path("id") projectId: String): Response<ProjectStateResponse>

@POST("api/workspace/projects/{id}/index")
suspend fun indexProject(@Path("id") projectId: String): Response<TaskResponse>

// Agents
@GET("api/agents")
suspend fun getAgents(): Response<AgentsResponse>

@POST("api/agents/dispatch")
suspend fun dispatchAgent(@Body body: DispatchAgentRequest): Response<TaskResponse>

@GET("api/agents/status/{projectId}")
suspend fun getAgentStatus(@Path("projectId") projectId: String): Response<AgentStatusResponse>

// Workflows
@GET("api/workflows/{projectId}")
suspend fun getWorkflows(@Path("projectId") projectId: String): Response<WorkflowsResponse>

@POST("api/workflows/{projectId}/run")
suspend fun runWorkflow(
    @Path("projectId") projectId: String,
    @Body body: RunWorkflowRequest
): Response<TaskResponse>

@GET("api/workflows/{projectId}/status")
suspend fun getWorkflowStatus(@Path("projectId") projectId: String): Response<WorkflowStatusResponse>

// Jobs
@GET("api/jobs")
suspend fun getJobs(): Response<JobsResponse>

@POST("api/jobs/{id}/run")
suspend fun runJob(@Path("id") jobId: String): Response<BaseResponse>
```

Crea un archivo `Models.kt` (o agrégalos al existente) con los data classes:

```kotlin
data class BaseResponse(val ok: Boolean, val error: String? = null)

data class HealthResponse(
    val ok: Boolean,
    val data: HealthData
)
data class HealthData(
    val server: String,
    val port: Int,
    val uptime: Long,
    val memory: MemoryData,
    val workspace: String,
    val projects: Int,
    val agents: AgentsSummary,
    val jobs: JobsSummary,
    val skills: SkillsSummary,
    val adapters: Map<String, String>
)
data class MemoryData(val heapUsed: String, val heapTotal: String)
data class AgentsSummary(val active: Int, val registered: Int)
data class JobsSummary(val active: Int, val lastRun: String?)
data class SkillsSummary(val installed: List<String>)

data class SkillItem(
    val id: String,
    val name: String,
    val version: String?,
    val description: String?,
    val installed: Boolean,
    val enabled: Boolean
)
data class SkillsResponse(val ok: Boolean, val data: SkillsData)
data class SkillsData(val installed: List<SkillItem>, val available: List<SkillItem>)

data class InstallSkillRequest(val skillId: String)
data class TaskResponse(val ok: Boolean, val data: TaskData?)
data class TaskData(val taskId: String?, val message: String?)

data class ProjectItem(
    val id: String,
    val name: String,
    val path: String,
    val hasHub: Boolean,
    val lastCommit: String?
)
data class ProjectsResponse(val ok: Boolean, val data: List<ProjectItem>)
data class ProjectStateResponse(val ok: Boolean, val data: Map<String, Any>)

data class AgentItem(val id: String, val name: String, val status: String)
data class AgentsResponse(val ok: Boolean, val data: List<AgentItem>)
data class DispatchAgentRequest(val agentType: String, val projectId: String, val context: Map<String, String>)
data class AgentStatusResponse(val ok: Boolean, val data: List<AgentItem>)

data class WorkflowItem(val id: String, val name: String, val steps: Int)
data class WorkflowsResponse(val ok: Boolean, val data: List<WorkflowItem>)
data class RunWorkflowRequest(val workflowId: String)
data class WorkflowStatusResponse(val ok: Boolean, val data: WorkflowStatus?)
data class WorkflowStatus(val id: String, val status: String, val currentStep: String?, val progress: Int)

data class JobItem(val id: String, val enabled: Boolean, val lastRun: String?, val interval: String)
data class JobsResponse(val ok: Boolean, val data: List<JobItem>)

data class LogsResponse(val ok: Boolean, val data: List<String>)
data class MemoryResponse(val ok: Boolean, val data: MemoryData)
data class SkillConfigResponse(val ok: Boolean, val data: Map<String, Any>)
```

**Commit:**
```bash
git add app/src/main/java/
git commit -m "feat(android): update ApiService with all new Aegis endpoints and models"
```

---

## PASO 2 — PANTALLA: CONTROL CENTER

**Archivo:** `ui/screens/ControlCenterScreen.kt`  
**ViewModel:** `ui/viewmodels/ControlCenterViewModel.kt`

### ControlCenterViewModel.kt

```kotlin
class ControlCenterViewModel : ViewModel() {
    private val _health = MutableStateFlow<HealthData?>(null)
    val health: StateFlow<HealthData?> = _health.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val response = ApiClient.service.getSystemHealth()
                if (response.isSuccessful) {
                    _health.value = response.body()?.data
                }
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun startAutoRefresh() {
        viewModelScope.launch {
            while (true) {
                refresh()
                delay(10_000)
            }
        }
    }
}
```

### ControlCenterScreen.kt

Implementa una pantalla con:

1. **Header** con título "AEGIS CONTROL CENTER" en fuente monospace, color `#E6EDF3`

2. **Status Card** — estado general del servidor:
   - Indicador circular: verde si `server == "running"`, rojo si no
   - Texto: `● ONLINE` o `● OFFLINE`
   - Uptime formateado (ej. `2h 34m`)

3. **Grid 2x2 de Metric Cards** — cada card con fondo `#1E1E1C`:
   - **Memory**: `heapUsed / heapTotal` con barra de progreso
   - **Projects**: número de proyectos en workspace
   - **Agents**: `active / registered`
   - **Jobs**: `active` con último run

4. **Skills List** — lista horizontal de chips con las skills instaladas

5. **Adapters Status** — fila con estado de cada adapter (opencode, antigravity) con indicador verde/rojo

6. **Botón Refresh** — ícono de recarga en la TopAppBar

7. **Auto-refresh** cada 10 segundos via `LaunchedEffect(Unit) { viewModel.startAutoRefresh() }`

Estética:
- Fondo: `#181816`
- Cards: `#1E1E1C` con borde sutil `#2A2A28`
- Texto primario: `#E6EDF3`
- Texto secundario: `#8B949E`
- Verde: `#3FB950`
- Rojo: `#F85149`
- Amarillo: `#D29922`
- Fuente: `FontFamily.Monospace`

**Commit:**
```bash
git commit -m "feat(android): ControlCenterScreen with health monitoring and auto-refresh"
```

---

## PASO 3 — PANTALLA: SKILL MANAGER

**Archivo:** `ui/screens/SkillManagerScreen.kt`  
**ViewModel:** `ui/viewmodels/SkillManagerViewModel.kt`

### SkillManagerViewModel.kt

```kotlin
class SkillManagerViewModel : ViewModel() {
    private val _skills = MutableStateFlow<SkillsData?>(null)
    val skills: StateFlow<SkillsData?> = _skills.asStateFlow()
    
    private val _installingId = MutableStateFlow<String?>(null)
    val installingId: StateFlow<String?> = _installingId.asStateFlow()
    
    private val _installLog = MutableStateFlow<List<String>>(emptyList())
    val installLog: StateFlow<List<String>> = _installLog.asStateFlow()

    fun loadSkills() { /* llamada a getSkills() */ }
    fun installSkill(skillId: String) { /* POST install, acumula SSE lines en _installLog */ }
    fun uninstallSkill(skillId: String) { /* DELETE */ }
}
```

### SkillManagerScreen.kt

1. **TopAppBar**: "SKILL MANAGER" + botón refresh

2. **Sección "Instaladas"** — LazyColumn de SkillCards:
   - Nombre + badge de versión
   - Descripción breve
   - Botón `[CONFIGURAR]` → abre `SkillConfigBottomSheet`
   - Botón `[DESINSTALAR]` en rojo discreto

3. **Sección "Disponibles"** — LazyColumn de SkillCards:
   - Nombre + descripción
   - Botón `[INSTALAR]`

4. **SkillConfigBottomSheet** — modal que aparece al configurar:
   - Campos dinámicos según config de la skill
   - Botón `[GUARDAR]`

5. **InstallOverlay** — cuando `installingId != null`:
   - Overlay semitransparente sobre la pantalla
   - Terminal simulada (igual que el chat actual) mostrando líneas de `_installLog`
   - Se cierra automáticamente cuando termina la instalación

**Commit:**
```bash
git commit -m "feat(android): SkillManagerScreen with install/uninstall and config UI"
```

---

## PASO 4 — PANTALLA: PROJECT WORKSPACE

**Archivo:** `ui/screens/WorkspaceScreen.kt`  
**ViewModel:** `ui/viewmodels/WorkspaceViewModel.kt`

### WorkspaceViewModel.kt

```kotlin
class WorkspaceViewModel : ViewModel() {
    private val _projects = MutableStateFlow<List<ProjectItem>>(emptyList())
    val projects: StateFlow<List<ProjectItem>> = _projects.asStateFlow()
    
    private val _selectedProject = MutableStateFlow<ProjectItem?>(null)
    val selectedProject: StateFlow<ProjectItem?> = _selectedProject.asStateFlow()

    fun loadProjects() { /* GET /api/workspace/projects */ }
    fun initProject(projectId: String) { /* POST init */ }
    fun indexProject(projectId: String) { /* POST index — lanza graphify */ }
    fun selectProject(project: ProjectItem) { _selectedProject.value = project }
}
```

### WorkspaceScreen.kt

1. **TopAppBar**: "PROJECTS" + botón refresh

2. **SearchBar** — filtro por nombre de proyecto

3. **LazyColumn de ProjectCards** — cada card:
   - Nombre del proyecto en monospace grande
   - Path relativo en texto secundario
   - Badge: `[HUB]` verde si `hasHub == true`, `[NO HUB]` gris si no
   - Último commit si disponible
   - Row de botones:
     - `[ABRIR]` → navega a ChatScreen con el projectId preseleccionado
     - `[INIT HUB]` (solo si `!hasHub`) → llama `initProject()`
     - `[INDEXAR]` → llama `indexProject()` (lanza graphify)
     - `[WORKFLOWS]` → navega a WorkflowScreen con el projectId

4. **Estado vacío** si no hay proyectos: texto "No se encontraron proyectos en /sdcard/projects/"

**Commit:**
```bash
git commit -m "feat(android): WorkspaceScreen with project listing and hub management"
```

---

## PASO 5 — PANTALLA: WORKFLOW RUNNER

**Archivo:** `ui/screens/WorkflowScreen.kt`  
**ViewModel:** `ui/viewmodels/WorkflowViewModel.kt`

### WorkflowViewModel.kt

```kotlin
class WorkflowViewModel : ViewModel() {
    private val _workflows = MutableStateFlow<List<WorkflowItem>>(emptyList())
    val workflows: StateFlow<List<WorkflowItem>> = _workflows.asStateFlow()
    
    private val _status = MutableStateFlow<WorkflowStatus?>(null)
    val status: StateFlow<WorkflowStatus?> = _status.asStateFlow()
    
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    fun loadWorkflows(projectId: String) { /* GET */ }
    fun runWorkflow(projectId: String, workflowId: String) { /* POST + poll status */ }
    fun pollStatus(projectId: String) { /* GET status cada 3s mientras isRunning */ }
}
```

### WorkflowScreen.kt

1. **TopAppBar**: nombre del proyecto + "WORKFLOWS"

2. **Lista de workflows disponibles** — cards con:
   - Nombre del workflow
   - Número de steps
   - Botón `[EJECUTAR]`

3. **Panel de progreso** (visible cuando `isRunning == true`):
   - Pipeline visual horizontal: `Step1 → Step2 → Step3`
   - Cada step con color:
     - `pending` → gris `#8B949E`
     - `running` → amarillo `#D29922` con animación pulsante
     - `completed` → verde `#3FB950`
     - `failed` → rojo `#F85149`
   - Porcentaje de progreso
   - Botón `[CANCELAR]`

4. **Log de eventos** — LazyColumn con eventos del workflow en tiempo real

**Commit:**
```bash
git commit -m "feat(android): WorkflowScreen with pipeline visualization and status polling"
```

---

## PASO 6 — ACTUALIZAR NAVEGACIÓN

Actualiza `AppNavHost.kt` agregando las nuevas rutas **sin eliminar las existentes**:

```kotlin
// Nuevas rutas — agregar a las existentes
composable("control_center") {
    ControlCenterScreen(navController = navController)
}
composable("skill_manager") {
    SkillManagerScreen(navController = navController)
}
composable("workspace") {
    WorkspaceScreen(navController = navController)
}
composable("workflow/{projectId}") { backStackEntry ->
    val projectId = backStackEntry.arguments?.getString("projectId") ?: ""
    WorkflowScreen(navController = navController, projectId = projectId)
}
```

Actualiza el **Navigation Drawer o Bottom Navigation** existente con las nuevas secciones:

```
🏠  Control Center    → route: "control_center"
📁  Projects          → route: "workspace"  
🔧  Skills            → route: "skill_manager"
💬  Chat              → route: existente (no cambiar)
```

**Commit:**
```bash
git commit -m "feat(android): update navigation with Control Center, Workspace, Skills routes"
```

---

## PASO 7 — PERMISOS Y CONFIGURACIÓN

Verifica que `AndroidManifest.xml` tiene los permisos necesarios. Agrega si faltan:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

Verifica que `PlatformConfig` (o donde esté la config de red) apunta correctamente a:
```kotlin
const val BASE_URL = "http://127.0.0.1:8765/"
```

Si existe algún archivo de configuración de red con URL hardcodeada diferente, actualízala.

---

## PASO 8 — BUILD Y VERIFICACIÓN FINAL

```bash
cd /sdcard/projects/Aegis/app

# Limpiar cache de Gradle
./gradlew clean

# Build debug
./gradlew assembleDebug

# Si el build pasa, verifica el APK
ls -la app/build/outputs/apk/debug/
```

Si el build falla:
1. Lee el error completo
2. Identifica el archivo y línea
3. Corrige el error específico
4. Repite el build
5. NO hagas cambios masivos para "arreglar todo" — corrige un error a la vez

**Commit final:**
```bash
git add -A
git commit -m "feat(android): Phase 11 complete - Control Center, Skills, Workspace, Workflow screens"
git tag v1.0.0-aegis-complete
```

---

## PASO 9 — REPORTE FINAL

Al terminar, genera un reporte con:

1. **Pantallas implementadas** con rutas de navegación
2. **Archivos creados** con paths completos
3. **Decisiones tomadas** que difieren de estas instrucciones (con justificación)
4. **Problemas encontrados** y cómo se resolvieron
5. **Estado del build** (éxito o errores pendientes)
6. **APK generado** en path completo

---

## NOTAS IMPORTANTES

- El ChatScreen existente **NO debe modificarse** — solo agregar rutas nuevas alrededor de él
- Si el proyecto usa un package name diferente a `com.aegis`, usa el real que encuentres
- Si Gradle falla por problemas de symlinks en `/sdcard/`, el build debe ejecutarse desde `/root/` o directamente desde Android Studio en el dispositivo
- Los ViewModels deben usar `viewModelScope` con `Dispatchers.IO` para las llamadas de red
- Todos los errores de red deben manejarse con try/catch y mostrar mensaje de error en la UI
- El SSE (Server-Sent Events) para el overlay de instalación de skills puede implementarse con OkHttp EventSource o simplemente como polling cada 2 segundos si SSE nativo es complejo

---

*Aegis — Phase 11 Android Implementation*  
*Versión: 1.0*  
*Fecha: 2026-09-22*
