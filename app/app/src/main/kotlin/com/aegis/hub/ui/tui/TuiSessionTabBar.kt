package com.aegis.hub.ui.tui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.aegis.hub.ui.viewmodel.SessionTab

// ============================================================================
// Paleta TUI de OpenCode (medida sobre el tema del CLI): fondo negro puro,
// texto claro para la activa, texto atenuado para las inactivas y divisores
// finos de una linea como en la terminal.
// ============================================================================

private val TuiNegro = Color(0xFF000000)
private val TuiTextoActivo = Color(0xFFE6EDF3)
private val TuiTextoAtenuado = Color(0xFF8B949E)
private val TuiResaltadoPestana = Color(0xFF161B22) // fondo de la pestana activa
private val TuiBordePestana = Color(0xFF30363D)     // borde tenue de la activa
private val TuiDivisor = Color(0xFF21262D)          // linea fina de 1 dp

private val FormaPestana = RoundedCornerShape(3.dp)

/** Prefijo que marca la pestaña activa, igual que el TUI. */
private const val PREFIJO_ACTIVA = "⠆ "

/**
 * Barra de pestañas de sesión estilo navegador/TUI de OpenCode para el tope
 * de la pantalla: fila deslizable de pestañas con monospace sobre negro puro.
 *
 * Reglas visuales y de interacción:
 * - Pestaña ACTIVA: prefijo "⠆ " + titulo, fondo [TuiResaltadoPestana] y borde
 *   tenue [TuiBordePestana], y "✕" al lado para CERRAR (solo la pestaña; la
 *   sesión del backend queda intacta: localizarla es trabajo del gestor de
 *   pestañas (`SessionTabManager.cerrarPestana`), no de esta barra).
 *   Tocar el cuerpo de la activa también la selecciona (no-op de ida y vuelta).
 * - Pestaña INACTIVA: titulo atenuado [TuiTextoAtenuado]; un tap conmuta.
 * - Botón "+" al final: abre pestaña nueva (el gesto post-creación es del
 *   llamador, tipicamente `SessionTabManager.abrirNueva`).
 *
 * Sin estados internos: componente tonto controlado por el llamador, para que
 * el estado (pestañas, activa) viva en [SessionTabManager].
 */
@Composable
fun TuiSessionTabBar(
    pestanas: List<SessionTab>,
    onPestanaSeleccionada: (SessionTab) -> Unit,
    onPestanaCerrada: (SessionTab) -> Unit,
    onNueva: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(TuiNegro),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LazyRow(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(pestanas, key = { it.sessionId }) { p ->
                    PestanaTui(
                        p = p,
                        onClick = { onPestanaSeleccionada(p) },
                        onClose = { onPestanaCerrada(p) },
                    )
                }
            }
            // Botón "+" del extremo derecho, siempre accesible sin deslizar.
            Text(
                text = "+",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp,
                    color = TuiTextoActivo,
                ),
                modifier = Modifier
                    .clickable(onClick = onNueva)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .semantics { contentDescription = "Nueva pestaña de sesión" },
            )
        }
        // Divisor fino de cierre, como la linea inferior del TUI.
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(TuiDivisor),
        )
    }
}

@Composable
private fun PestanaTui(
    p: SessionTab,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    val texto = MaterialTheme.typography.bodyMedium.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
    )
    if (p.isCurrent) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(horizontal = 4.dp, vertical = 4.dp)
                .background(TuiResaltadoPestana, FormaPestana)
                .border(1.dp, TuiBordePestana, FormaPestana)
                .clickable(onClick = onClick),
        ) {
            Text(
                text = PREFIJO_ACTIVA + p.title,
                style = texto.copy(
                    color = TuiTextoActivo,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = 160.dp)
                    .padding(start = 10.dp, top = 8.dp, bottom = 8.dp),
            )
            Text(
                text = "✕",
                style = texto.copy(color = TuiTextoAtenuado, fontSize = 12.sp),
                modifier = Modifier
                    .clickable(onClick = onClose)
                    .padding(start = 6.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
                    .semantics { contentDescription = "Cerrar pestaña ${p.title}" },
            )
        }
    } else {
        Text(
            text = p.title,
            style = texto.copy(color = TuiTextoAtenuado),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(horizontal = 4.dp, vertical = 4.dp)
                .background(Color.Transparent, FormaPestana)
                .widthIn(max = 160.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}
