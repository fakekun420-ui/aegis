package com.aegis.hub.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aegis.hub.data.AuthGuideResponse
import com.aegis.hub.data.BootstrapPhase
import com.aegis.hub.data.BootstrapRollback
import com.aegis.hub.data.BootstrapState
import com.aegis.hub.data.BootstrapStep
import com.aegis.hub.data.BootstrapStepStatus
import com.aegis.hub.data.SetupCheck
import com.aegis.hub.data.SetupCheckStatus
import com.aegis.hub.ui.theme.*
import com.aegis.hub.ui.viewmodel.BootstrapUiState
import com.aegis.hub.ui.viewmodel.BootstrapViewModel
import com.aegis.hub.ui.viewmodel.friendlyError

// F4 — testTags estables de los botones principales, compartidos con la suite
// instrumentada (SetupWizardNavigationTest). Son el único ancla de localización:
// los tests NO dependen de los labels variables ("Ejecutar…"/"Reintentar…",
// "Enviando mensaje…") del botón de verificación ni del smoke test.
object SetupTestTags {
    const val START = "setup_btn_start"                 // "Iniciar instalación" (phase idle)
    const val FINAL_CHECK = "setup_btn_final_check"     // "Ejecutar/Reintentar verificación"
    const val SMOKE_TEST = "setup_btn_smoke_test"       // "Enviar mensaje de prueba"
}

// F1 — Wizard de configuración inicial de Aegis (contrato /api/bootstrap/*).
// Estilo de los screens existentes (ControlCenter/SkillManager): paleta Claude,
// tipografía Monospace, Cards con borde sutil. Todo el texto en español y los
// iconos con contentDescription en español; targets >= 48dp.
// El estado vive en BootstrapViewModel → sobrevive a rotación.
// F3: tarjeta "Verificación final" (checks ✓/✗/⚠, smoke test de la IA y guía de
// auth de Antigravity) visible SÓLO cuando phase == done (contrato /api/setup/*).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupWizardScreen(
    onFinished: () -> Unit,
    viewModel: BootstrapViewModel = viewModel()
) {
    val ui by viewModel.ui.collectAsState()
    val state = ui.state
    val steps = state?.stepList ?: emptyList()
    val phase = state?.phaseOrIdle ?: BootstrapPhase.idle

    // F3: si la verificación deja a Antigravity en fail/manual y aún no está la
    // guía de comandos, se carga sola al mostrar la tarjeta (phase done).
    val needsAuthGuide = ui.finalCheck?.data?.checkList
        ?.any { it.id == "antigravity" && it.statusOrManual != SetupCheckStatus.ok } == true
    LaunchedEffect(phase, needsAuthGuide) {
        if (phase == BootstrapPhase.done && needsAuthGuide &&
            ui.authGuide == null && !ui.authGuideLoading
        ) {
            viewModel.guideAuth()
        }
    }

    // ---- Cabecera: subtítulo según phase (contrato exacto) ----
    val subtitle = when (phase) {
        BootstrapPhase.idle -> "Instala todo lo necesario: Ubuntu, Node.js, OpenCode, Antigravity y skills"
        BootstrapPhase.running -> {
            val idx = currentStepIndex(state, steps)
            if (idx >= 0) "Instalando… paso ${idx + 1} de ${steps.size}" else "Instalando…"
        }
        BootstrapPhase.failed -> "Se detuvo en: «${failedStepTitle(state, steps)}»"
        BootstrapPhase.paused -> "Instalación pausada (reanudable)"
        BootstrapPhase.done -> "¡Todo listo!"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Configuración inicial de Aegis",
                        fontFamily = FontFamily.Monospace,
                        color = ClaudeOnSurface,
                        fontSize = 16.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ClaudeBackground)
            )
        },
        containerColor = ClaudeBackground
    ) { padding ->
        if (!ui.hubReachable) {
            // ---- Bloqueo amigable: el hub no responde (red/403) y se reintenta solo a los 5 s ----
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    CircularProgressIndicator(color = ClaudePrimary)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Esperando el hub (127.0.0.1:8765)…",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = ClaudeOnSurfaceVariant
                    )
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {

                // ---- Subtítulo + error de acción + progreso global ----
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        color = ClaudeOnSurface
                    )
                    // F2: motivo global del fallo (state.lastError) SIEMPRE visible bajo el
                    // subtítulo "Se detuvo en: «paso»", más la guía friendlyError() si aporta.
                    if (phase == BootstrapPhase.failed) {
                        state?.lastError?.takeIf { it.isNotBlank() }?.let { raw ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                raw,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                color = ClaudeError
                            )
                            val guide = friendlyError(raw)
                            if (guide != raw) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    guide,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = ClaudeOnSurfaceVariant
                                )
                            }
                        }
                    }
                    ui.actionError?.let { err ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            err,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = ClaudeError
                        )
                    }
                    if (steps.isNotEmpty()) {
                        val completed = steps.count {
                            val s = it.statusOrPending
                            s == BootstrapStepStatus.done || s == BootstrapStepStatus.skipped
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Progreso: $completed de ${steps.size} pasos",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            color = ClaudeOnSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { completed.toFloat() / steps.size },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp),
                            color = ClaudePrimary,
                            trackColor = ClaudeSurfaceVariant
                        )
                    }
                }

                // ---- Lista de pasos (se renderizan los que lleguen, en el orden recibido) ----
                if (state == null && ui.loading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = ClaudePrimary)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Consultando el estado…",
                                fontFamily = FontFamily.Monospace,
                                color = ClaudeOnSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(steps, key = { it.id }) { step ->
                            SetupStepCard(
                                step = step,
                                onRetry = { viewModel.retry(step.id) }
                            )
                        }
                        // F3: verificación final + smoke test — sólo cuando la instalación terminó
                        // (fuera de done la tarjeta NO aparece: documentado en F3)
                        if (phase == BootstrapPhase.done) {
                            item {
                                FinalVerificationCard(
                                    ui = ui,
                                    onRunFinalCheck = { viewModel.runFinalCheck() },
                                    onSmokeTest = { viewModel.runSmokeTest() }
                                )
                            }
                        }
                        item { Spacer(Modifier.height(8.dp)) }
                    }
                }

                // ---- Acciones según phase (fijas abajo: siempre visibles) ----
                Surface(color = ClaudeSurface, contentColor = ClaudeOnSurface) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        WizardActions(
                            phase = phase,
                            failedStepId = steps
                                .firstOrNull { it.statusOrPending == BootstrapStepStatus.failed }
                                ?.id,
                            loading = ui.loading,
                            onStart = { viewModel.start() },
                            onCancel = { viewModel.cancel() },
                            onRetryStep = { id -> viewModel.retry(id) },
                            onFinished = onFinished
                        )
                    }
                }
            }
        }
    }
}

// ---- Acciones del wizard según phase ----
@Composable
private fun WizardActions(
    phase: BootstrapPhase,
    failedStepId: String?,
    loading: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onRetryStep: (String) -> Unit,
    onFinished: () -> Unit
) {
    when (phase) {
        BootstrapPhase.idle ->
            SetupActionButton(
                "Iniciar instalación",
                enabled = !loading,
                tag = SetupTestTags.START,
                onClick = onStart
            )
        BootstrapPhase.running ->
            SetupActionButton("Cancelar", enabled = !loading, danger = true, onClick = onCancel)
        BootstrapPhase.failed -> Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            SetupActionButton(
                label = "Reintentar paso",
                enabled = !loading && failedStepId != null,
                modifier = Modifier.weight(1f),
                onClick = { failedStepId?.let(onRetryStep) }
            )
            SetupActionButton(
                label = "Reanudar",
                enabled = !loading,
                modifier = Modifier.weight(1f),
                onClick = onStart
            )
        }
        BootstrapPhase.paused ->
            SetupActionButton("Reanudar", enabled = !loading, onClick = onStart)
        BootstrapPhase.done ->
            SetupActionButton("Continuar", enabled = true, onClick = onFinished)
    }
}

@Composable
private fun SetupActionButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    loading: Boolean = false,
    tag: String? = null,
    onClick: () -> Unit
) {
    // F4: testTag opcional (SetupTestTags) para localizar el botón en androidTest
    val baseModifier = modifier
        .fillMaxWidth()
        .height(48.dp)
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = if (tag != null) baseModifier.testTag(tag) else baseModifier,
        colors = if (danger) {
            ButtonDefaults.buttonColors(containerColor = ClaudeError, contentColor = Color.Black)
        } else {
            ButtonDefaults.buttonColors()
        }
    ) {
        // F3: estado de carga (spinner + etiqueta) manteniendo el target de 48dp
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = LocalContentColor.current,
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
        )
    }
}

// ---- Card de un paso del bootstrap ----
@Composable
fun SetupStepCard(step: BootstrapStep, onRetry: () -> Unit) {
    val status = step.statusOrPending
    Card(
        colors = CardDefaults.cardColors(containerColor = ClaudeSurface),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ClaudeOutlineVariant, RoundedCornerShape(8.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepStatusIcon(status)
                Spacer(Modifier.width(12.dp))
                Text(
                    step.title,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = if (status == BootstrapStepStatus.failed) ClaudeError else ClaudeOnSurface
                )
            }
            if (!step.detail.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    step.detail,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = ClaudeOnSurfaceVariant
                )
            }
            if (status == BootstrapStepStatus.running) {
                val progress = step.progress ?: 0
                Spacer(Modifier.height(8.dp))
                if (progress > 0) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = ClaudePrimary,
                        trackColor = ClaudeSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "$progress %",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = ClaudeOnSurfaceVariant
                    )
                } else {
                    // Sin progreso numérico → barra indeterminada (el icono ya gira)
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = ClaudePrimary,
                        trackColor = ClaudeSurfaceVariant
                    )
                }
            }
            if (status == BootstrapStepStatus.failed) {
                if (!step.error.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    // F2: raw partido en título corto en negrita + detalle; el texto
                    // completo del error queda SIEMPRE visible (es la pista del usuario)
                    StepErrorText(step.error)
                    val guide = friendlyError(step.error)
                    if (guide != step.error) {
                        // Guía en español añadida por F2 (el raw de arriba no se sustituye)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            guide,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = ClaudeOnSurfaceVariant
                        )
                    }
                }
                // F2: qué pasó con el rollback del motor en este paso (none|pending|done|failed)
                RollbackNote(step.rollback)
                // Reintento por paso fallido (>= 48dp)
                TextButton(
                    onClick = onRetry,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier
                        .height(48.dp)
                        .align(Alignment.Start)
                ) {
                    Text(
                        "Reintentar",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = ClaudePrimary
                    )
                }
            }
        }
    }
}

// ---- F2: step.error → título corto en negrita + detalle, sin regex exóticas ----
// raw = título + ": " + detalle → se muestra en dos líneas; todo el contenido del
// texto raw queda visible (nunca se recorta ni se sustituye).
private fun splitStepError(raw: String): Pair<String, String>? {
    val idx = raw.indexOf(": ")
    if (idx <= 0) return null
    val title = raw.substring(0, idx)
    val detail = raw.substring(idx + 2)
    if (title.isBlank() || detail.isBlank()) return null
    return title to detail
}

@Composable
private fun StepErrorText(raw: String) {
    val split = splitStepError(raw)
    if (split == null) {
        Text(
            raw,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = ClaudeError
        )
    } else {
        Text(
            split.first,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = ClaudeError
        )
        Spacer(Modifier.height(2.dp))
        Text(
            split.second,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = ClaudeError
        )
    }
}

// ---- F2: estado del rollback del motor en un paso fallido (contrato: none|pending|done|failed) ----
// Colores: sólo paleta existente (Theme.kt) + verde 0xFF3FB950 ya usado en StepStatusIcon.
@Composable
private fun RollbackNote(rollback: BootstrapRollback?) {
    Spacer(Modifier.height(6.dp))
    when (rollback) {
        BootstrapRollback.done -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = "Cambios deshechos",
                tint = Color(0xFF3FB950), // verde de la casa (mismo que "Completado")
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Cambios deshechos — seguro reintentar",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = Color(0xFF3FB950)
            )
        }
        BootstrapRollback.failed -> Text(
            "Revisión manual recomendada: no se pudo deshecer",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = ClaudeSecondary // naranja/terracota existente de la paleta Claude
        )
        BootstrapRollback.pending -> Text(
            "Reversible: sin cambios aplicados",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = ClaudeOnSurfaceVariant
        )
        // none (el motor no registró rollback) u origen desconocido → nada
        else -> {}
    }
}

// ---- Icono de estado (contentDescription en español) ----
@Composable
private fun StepStatusIcon(status: BootstrapStepStatus) {
    when (status) {
        BootstrapStepStatus.pending -> Icon(
            Icons.Outlined.RadioButtonUnchecked,
            contentDescription = "Pendiente",
            tint = ClaudeOnSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        // ○ giratorio mientras el paso está en curso
        BootstrapStepStatus.running -> CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            color = ClaudePrimary,
            strokeWidth = 2.dp
        )
        BootstrapStepStatus.done -> Icon(
            Icons.Filled.CheckCircle,
            contentDescription = "Completado",
            tint = Color(0xFF3FB950), // verde de StatusCard (paleta de la casa)
            modifier = Modifier.size(24.dp)
        )
        BootstrapStepStatus.failed -> Icon(
            Icons.Filled.Close,
            contentDescription = "Fallido",
            tint = ClaudeError,
            modifier = Modifier.size(24.dp)
        )
        BootstrapStepStatus.skipped -> Icon(
            Icons.Outlined.Block,
            contentDescription = "Omitido",
            tint = ClaudeOnSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
    }
}

// ---- Índice del paso activo: currentStepId primero, luego el marcado running ----
private fun currentStepIndex(state: BootstrapState?, steps: List<BootstrapStep>): Int {
    val byId = state?.currentStepId
        ?.let { id -> steps.indexOfFirst { it.id == id } }
        ?: -1
    if (byId >= 0) return byId
    return steps.indexOfFirst { it.statusOrPending == BootstrapStepStatus.running }
}

// ---- Título del paso detenido (para el subtítulo de phase failed) ----
private fun failedStepTitle(state: BootstrapState?, steps: List<BootstrapStep>): String {
    steps.firstOrNull { it.statusOrPending == BootstrapStepStatus.failed }?.let { return it.title }
    val idx = currentStepIndex(state, steps)
    if (idx >= 0) return steps[idx].title
    return state?.currentStepId ?: "paso sin identificar"
}

// ==== F3: tarjeta "Verificación final" (sólo visible en phase == done) ====
// Fuera de done la tarjeta NO aparece (decisión F3): mientras instala o se
// reintenta un paso, el foco son los pasos del wizard; al resolverse un failed
// y pasar a done, la tarjeta se muestra sola. Sin inputs de texto: todo el
// estado vive en BootstrapViewModel (sobrevive a rotación).
@Composable
private fun FinalVerificationCard(
    ui: BootstrapUiState,
    onRunFinalCheck: () -> Unit,
    onSmokeTest: () -> Unit
) {
    val finalCheck = ui.finalCheck
    val checks = finalCheck?.data?.checkList ?: emptyList()
    val needsAuth = checks.any {
        it.id == "antigravity" && it.statusOrManual != SetupCheckStatus.ok
    }
    val clipboard = LocalClipboardManager.current

    Card(
        colors = CardDefaults.cardColors(containerColor = ClaudeSurface),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ClaudeOutlineVariant, RoundedCornerShape(8.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Verificación final",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = ClaudeOnSurface
            )
            Spacer(Modifier.height(12.dp))

            // Botón principal (>= 48dp): la primera vez "Ejecutar verificación final";
            // tras el primer chequeo pasa a "Reintentar verificación" (misma acción)
            SetupActionButton(
                label = when {
                    ui.finalCheckLoading -> "Verificando…"
                    finalCheck == null -> "Ejecutar verificación final"
                    else -> "Reintentar verificación"
                },
                enabled = !ui.finalCheckLoading,
                loading = ui.finalCheckLoading,
                tag = SetupTestTags.FINAL_CHECK,
                onClick = onRunFinalCheck
            )

            if (finalCheck != null) {
                Spacer(Modifier.height(12.dp))
                // Resultado global (ready) del contrato
                val ready = finalCheck.data?.ready == true
                Text(
                    if (ready) "Verificación superada: todo listo"
                    else "Hay puntos que requieren revisión",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = if (ready) Color(0xFF3FB950) else ClaudeSecondary
                )
                Spacer(Modifier.height(8.dp))
                checks.forEach { check -> CheckRow(check) }

                // Sub-bloque Antigravity: comando de autorización manual (si el check lo requiere)
                if (needsAuth) {
                    Spacer(Modifier.height(10.dp))
                    AuthGuideBlock(
                        authGuide = ui.authGuide,
                        loading = ui.authGuideLoading,
                        onCopy = { clipboard.setText(AnnotatedString(it)) }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            // ---- Smoke test: "La IA responde: «…»" o error en rojo ----
            SetupActionButton(
                label = if (ui.smokeLoading) "Enviando mensaje…" else "Enviar mensaje de prueba",
                enabled = !ui.smokeLoading,
                loading = ui.smokeLoading,
                tag = SetupTestTags.SMOKE_TEST,
                onClick = onSmokeTest
            )
            ui.smokeReply?.let { reply ->
                Spacer(Modifier.height(8.dp))
                // Fondo ClaudeSurface (paleta de la casa) + borde sutil para distinguirlo de la card
                Surface(
                    color = ClaudeSurface,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, ClaudeOutlineVariant, RoundedCornerShape(6.dp))
                ) {
                    Text(
                        "La IA responde: «$reply»",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = ClaudeOnSurface,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
            ui.smokeError?.let { err ->
                Spacer(Modifier.height(8.dp))
                // Guía (ya en español) + raw del hub, en rojo; si friendlyError
                // aporta algo más (código conocido del motor) se añade debajo (patrón F2)
                Text(
                    err,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = ClaudeError
                )
                val guide = friendlyError(err)
                if (guide != err) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        guide,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = ClaudeOnSurfaceVariant
                    )
                }
            }
        }
    }
}

// ---- F3: una comprobación de la verificación final (✓ ok · ✗ fail · ⚠ manual) ----
// label y detail SIEMPRE visibles; iconos con contentDescription en español.
@Composable
private fun CheckRow(check: SetupCheck) {
    val status = check.statusOrManual
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        when (status) {
            SetupCheckStatus.ok -> Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "Comprobación correcta",
                tint = Color(0xFF3FB950), // verde de la casa (mismo que "Completado")
                modifier = Modifier.size(20.dp)
            )
            SetupCheckStatus.fail -> Icon(
                Icons.Filled.Close,
                contentDescription = "Comprobación fallida",
                tint = ClaudeError,
                modifier = Modifier.size(20.dp)
            )
            SetupCheckStatus.manual -> Icon(
                Icons.Filled.Warning,
                contentDescription = "Requiere acción manual",
                tint = ClaudeSecondary, // naranja/terracota existente de la paleta Claude
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                check.label,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = ClaudeOnSurface
            )
            if (status == SetupCheckStatus.manual) {
                Text(
                    "Acción manual requerida",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = ClaudeSecondary
                )
            }
            if (!check.detail.isNullOrBlank()) {
                Text(
                    check.detail,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = ClaudeOnSurfaceVariant
                )
            }
        }
    }
}

// ---- F3: sub-bloque de autenticación de Antigravity (check en fail/manual) ----
@Composable
private fun AuthGuideBlock(
    authGuide: AuthGuideResponse?,
    loading: Boolean,
    onCopy: (String) -> Unit
) {
    val command = authGuide?.data?.command
    var copied by remember(command) { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Autenticación de Antigravity pendiente",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = ClaudeSecondary
        )
        Spacer(Modifier.height(6.dp))
        when {
            loading -> Text(
                "Obteniendo el comando de autorización…",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = ClaudeOnSurfaceVariant
            )
            !command.isNullOrBlank() -> {
                Text(
                    "Copia y ejecuta este comando, luego pulsa Reintentar verificación",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = ClaudeOnSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = ClaudeBackground,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        command,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = ClaudeOnSurface,
                        modifier = Modifier.padding(8.dp)
                    )
                }
                // Copiar al portapapeles (>= 48dp, misma text-button que "Reintentar")
                TextButton(
                    onClick = {
                        onCopy(command)
                        copied = true
                    },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier
                        .height(48.dp)
                        .align(Alignment.Start)
                ) {
                    Text(
                        if (copied) "Comando copiado" else "Copiar comando",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = ClaudePrimary
                    )
                }
                authGuide?.data?.status?.takeIf { it.isNotBlank() }?.let { st ->
                    Text(
                        "Estado: $st",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = ClaudeOnSurfaceVariant
                    )
                }
            }
            authGuide != null -> Text(
                "El hub no devolvió el comando de autorización",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = ClaudeError
            )
            else -> Text(
                "Obteniendo el comando de autorización…",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = ClaudeOnSurfaceVariant
            )
        }
    }
}
