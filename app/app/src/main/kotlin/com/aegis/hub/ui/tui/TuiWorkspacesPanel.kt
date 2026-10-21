package com.aegis.hub.ui.tui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
// TuiWorkspacesPanel — panel flotante inferior de OpenCode (subagentes,
// shell y terminales), replicado del TUI de referencia.
//
// Estructura, igual que en las capturas:
//   1. Encabezado: 3 pestañas ("Subagents", "Shell", "Terminals") + botón
//      táctil "esc" a la derecha para cerrar el panel.
//   2. Contenido según la pestaña activa (subagente seleccionado con la barra
//      melocotón, log de comandos bash, o el botón "+ New terminal").
//   3. Pie con los atajos táctiles del TUI: "interrupt ctrl+d",
//      "show active / show inactive ctrl+a" y "tabs ←/→".
//
// Estilo estricto de la familia TUI: fondo [TuiColors.surface] (#0D0D0D),
// borde fino [TuiColors.borderStrong] (#333333), tipografía monoespaciada
// (FontFamily.Monospace) y paddings compactos.
//
// Es un componente CONTROLADO en sus efectos (cerrar, interrumpir, abrir
// terminal son callbacks del llamador) y con el estado de navegación
// (pestaña, selección, filtro) dentro, del mismo modo que
// [TuiSessionTabBar] y `CommandPaletteDialog`: la UI no decide nada de la
// sesión, solo pinta lo que se le pasa.
// ---------------------------------------------------------------------------

/** Fondo de la pestaña activa, misma tinta que la barra de sesiones del TUI. */
private val TuiPestanaActivaBg = Color(0xFF161B22)

/** Chrome de los botones táctiles (fondo/borde), idéntico a la paleta de comandos. */
private val TuiBotonTactilBg = Color(0xFF141414)
private val TuiBotonTactilBorde = Color(0xFF262626)

/** Pestañas del panel de workspaces. */
enum class TuiWorkspacesTab(val label: String) {
    SUBAGENTS("Subagents"),
    SHELL("Shell"),
    TERMINALS("Terminals");

    /** Siguiente pestaña en bucle circular, para el atajo "tabs ←/→". */
    fun siguiente(): TuiWorkspacesTab = entries[(ordinal + 1) % entries.size]
}

/**
 * Fila de un subagente en ejecución para [TuiWorkspacesPanel].
 *
 * Solo datos de presentación: nombre del cargo, tarea en curso y estado a la
 * derecha ("Running"). [activo] alimenta el filtro del atajo `ctrl+a`
 * (mostrar solo los activos o también los inactivos); por defecto todo es
 * activo, que es lo que muestra el TUI al abrirse.
 */
data class TuiSubagentRow(
    val agent: String,
    val task: String,
    val status: String = "Running",
    val activo: Boolean = true,
)

/**
 * Panel flotante inferior con las tres pestañas de workspaces del TUI.
 *
 * - **Subagents**: lista de subagentes en ejecución (`agente: tarea` + estado
 *   "Running" a la derecha). El elemento seleccionado se pinta con la barra de
 *   highlight melocotón [TuiColors.selection] y texto negro
 *   [TuiColors.onSelection]. Sin subagentes → "No active subagents".
 *   Pie: "interrupt ctrl+d" (solo con selección), "show active / show
 *   inactive ctrl+a" (conmuta el filtro de activos) y "tabs ←/→" (cicla la
 *   pestaña).
 * - **Shell**: "No shell commands" o el log de comandos bash de la sesión
 *   actual, uno por línea con el prompt "$" en ámbar.
 * - **Terminals**: botón táctil "+ New terminal" para abrir una consola
 *   interactiva nueva.
 *
 * @param subagentes filas de subagentes a mostrar (vacío → estado vacío).
 * @param comandosShell log de comandos bash de la sesión actual.
 * @param onCerrar cierra el panel (botón "esc").
 * @param onInterrumpir interrumpe el subagente seleccionado (ctrl+d).
 * @param onNuevoTerminal abre una terminal interactiva nueva.
 * @param pestanaInicial pestaña con la que se abre el panel.
 * @param modifier posicionamiento del llamador (típico: anclado abajo,
 *   `fillMaxWidth`, por encima del composer).
 */
@Composable
fun TuiWorkspacesPanel(
    subagentes: List<TuiSubagentRow> = emptyList(),
    comandosShell: List<String> = emptyList(),
    onCerrar: () -> Unit,
    onInterrumpir: (TuiSubagentRow) -> Unit = {},
    onNuevoTerminal: () -> Unit = {},
    pestanaInicial: TuiWorkspacesTab = TuiWorkspacesTab.SUBAGENTS,
    modifier: Modifier = Modifier,
) {
    val colores = LocalTuiColors.current
    val textoBase = MaterialTheme.typography.bodyMedium

    var pestana by remember { mutableStateOf(pestanaInicial) }
    var seleccion by remember { mutableStateOf(0) }
    var soloActivos by remember { mutableStateOf(true) }

    // La selección es un índice sobre lo VISIBLE: con el filtro puesto la
    // lista cambia de tamaño, así que se acota en el punto de lectura. Nunca
    // puede salir del rango y, con la lista vacía, simplemente no hay fila
    // seleccionada.
    val visibles = if (soloActivos) subagentes.filter { it.activo } else subagentes
    val indiceSel = seleccion.coerceIn(0, (visibles.size - 1).coerceAtLeast(0))
    val seleccionado = visibles.getOrNull(indiceSel)

    val formaPanel = RoundedCornerShape(6.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colores.surface, formaPanel)
            .border(1.dp, colores.borderStrong, formaPanel),
    ) {
        // 1. ENCABEZADO: pestañas a la izquierda + botón táctil "esc" a la derecha.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TuiWorkspacesTab.entries.forEach { t ->
                PestanaWorkspaces(
                    tab = t,
                    activa = t == pestana,
                    onClick = { pestana = t },
                )
            }
            Spacer(Modifier.weight(1f))
            BotonTactilEsc(onClick = onCerrar)
        }

        LineaDivisoria()

        // 2. CONTENIDO de la pestaña activa, con scroll propio y altura acotada
        // para que el panel no cubra toda la pantalla con logs largos.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
        ) {
            when (pestana) {
                TuiWorkspacesTab.SUBAGENTS -> {
                    if (visibles.isEmpty()) {
                        TextoVacio("No active subagents")
                    } else {
                        visibles.forEachIndexed { i, sub ->
                            val destacado = i == indiceSel
                            FilaSubagente(
                                sub = sub,
                                destacado = destacado,
                                onClick = { seleccion = i },
                            )
                            if (i < visibles.lastIndex) LineaDivisoria()
                        }
                    }
                }

                TuiWorkspacesTab.SHELL -> {
                    if (comandosShell.isEmpty()) {
                        TextoVacio("No shell commands")
                    } else {
                        comandosShell.forEach { cmd ->
                            FilaShell(cmd)
                        }
                    }
                }

                TuiWorkspacesTab.TERMINALS -> {
                    Surface(
                        color = TuiBotonTactilBg,
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(1.dp, TuiBotonTactilBorde),
                        modifier = Modifier
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .clickable(onClick = onNuevoTerminal)
                            .semantics { contentDescription = "Nueva terminal interactiva" },
                    ) {
                        Text(
                            text = "+ New terminal",
                            style = textoBase.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = colores.textPrimary,
                            ),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }

        // 3. PIE con los atajos táctiles: solo en la pestaña Subagents, como en
        // el TUI (shell/terminals no tienen selección que interrumpir).
        if (pestana == TuiWorkspacesTab.SUBAGENTS) {
            LineaDivisoria()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BotonAtajo(
                    label = "interrupt",
                    tecla = "ctrl+d",
                    habilitado = seleccionado != null,
                    onClick = { seleccionado?.let(onInterrumpir) },
                )
                BotonAtajo(
                    label = if (soloActivos) "show inactive" else "show active",
                    tecla = "ctrl+a",
                    onClick = { soloActivos = !soloActivos },
                )
                BotonAtajo(
                    label = "tabs",
                    tecla = "←/→",
                    onClick = { pestana = pestana.siguiente() },
                )
            }
        }
    }
}

/** Pestaña del encabezado: la activa con prefijo "⠆", fondo tenue y borde. */
@Composable
private fun PestanaWorkspaces(
    tab: TuiWorkspacesTab,
    activa: Boolean,
    onClick: () -> Unit,
) {
    val colores = LocalTuiColors.current
    val forma = RoundedCornerShape(4.dp)
    Row(
        modifier = Modifier
            .padding(end = 4.dp)
            .then(
                if (activa) {
                    Modifier
                        .background(TuiPestanaActivaBg, forma)
                        .border(1.dp, colores.borderStrong, forma)
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics { contentDescription = "Pestaña ${tab.label}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (activa) "⠆ ${tab.label}" else tab.label,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = if (activa) FontWeight.Medium else FontWeight.Normal,
                color = if (activa) colores.textPrimary else colores.textSecondary,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Botón táctil "esc" del extremo derecho: cierra el panel. */
@Composable
private fun BotonTactilEsc(onClick: () -> Unit) {
    val colores = LocalTuiColors.current
    Surface(
        color = TuiBotonTactilBg,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, TuiBotonTactilBorde),
        modifier = Modifier
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Cerrar panel de workspaces" },
    ) {
        Text(
            text = "esc",
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = colores.textSecondary,
            ),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/**
 * Fila de subagente: `agente: tarea` a la izquierda con "Running" a la
 * derecha. La seleccionada va sobre la barra melocotón con texto negro, como
 * el highlight de selección de OpenCode.
 */
@Composable
private fun FilaSubagente(
    sub: TuiSubagentRow,
    destacado: Boolean,
    onClick: () -> Unit,
) {
    val colores = LocalTuiColors.current
    val textoBase = MaterialTheme.typography.bodyMedium
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (destacado) colores.selection else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics { contentDescription = "Subagente ${sub.agent}: ${sub.task}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${sub.agent}: ${sub.task}",
            style = textoBase.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = if (destacado) colores.onSelection else colores.textPrimary,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = sub.status,
            style = textoBase.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = if (destacado) colores.onSelection else colores.textSecondary,
            ),
            maxLines = 1,
        )
    }
}

/** Línea de log de shell: prompt "$" en ámbar y el comando en texto primario. */
@Composable
private fun FilaShell(comando: String) {
    val colores = LocalTuiColors.current
    val textoBase = MaterialTheme.typography.bodyMedium
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$",
            style = textoBase.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = colores.cursor,
            ),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = comando,
            style = textoBase.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = colores.textPrimary,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Botón táctil del pie: etiqueta + tecla, al estilo de la paleta de comandos. */
@Composable
private fun BotonAtajo(
    label: String,
    tecla: String,
    onClick: () -> Unit,
    habilitado: Boolean = true,
) {
    val colores = LocalTuiColors.current
    val textoBase = MaterialTheme.typography.bodyMedium
    // Sin selección el atajo queda apagado (alfa reducido) y sin clickable:
    // interrumpir "nada" no es una acción.
    val modificador = if (habilitado) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }
    Surface(
        color = TuiBotonTactilBg,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, TuiBotonTactilBorde),
        modifier = modificador.semantics { contentDescription = "Atajo: $label $tecla" },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
        ) {
            Text(
                text = label,
                style = textoBase.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = if (habilitado) colores.textSecondary else colores.textSecondary.copy(alpha = 0.4f),
                ),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = tecla,
                style = textoBase.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (habilitado) colores.cursor else colores.cursor.copy(alpha = 0.4f),
                ),
            )
        }
    }
}

/** Estado vacío atenuado de cada pestaña. */
@Composable
private fun TextoVacio(texto: String) {
    val colores = LocalTuiColors.current
    Text(
        text = texto,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = colores.textSecondary,
        ),
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
    )
}

/** Divisor fino de 1 dp, igual que las líneas de la terminal. */
@Composable
private fun LineaDivisoria() {
    val colores = LocalTuiColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(colores.divider),
    )
}
