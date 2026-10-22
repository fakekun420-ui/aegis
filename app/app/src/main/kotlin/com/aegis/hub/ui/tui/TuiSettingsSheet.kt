package com.aegis.hub.ui.tui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aegis.hub.BuildConfig
import kotlin.math.roundToInt

/**
 * Hoja de ajustes del TUI.
 *
 * Regla de esta pantalla: **solo datos que existen**. Nada de versiones de servidor
 * inventadas, nada de interruptores que no cambien una preferencia real y nada de
 * botones que no ejecuten nada. Las tres secciones son:
 *
 * 1. Densidad — los mismos [TuiZoomPreset] que mueven los botones `[-]`/`[+]` de
 *    [TuiStatusBar], leídos del MISMO estado (`LocalTuiZoomState`), no de una copia
 *    paralela: tocar aquí y tocar la barra de estado es literalmente la misma llamada.
 * 2. Aplicación — `versionName`/`versionCode` reales de [BuildConfig].
 * 3. Servidor — la dirección real a la que apunta la app en este entorno.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TuiSettingsSheet(onDismissRequest: () -> Unit) {
    val colores = LocalTuiColors.current
    // Mismo CompositionLocal que usa ChatScreen/TuiStatusBar: un único estado de zoom.
    val zoom = LocalTuiZoomState.current

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = colores.background,
        contentColor = colores.textPrimary,
        modifier = Modifier.border(1.dp, colores.border),
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Ajustes",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                    color = colores.textPrimary,
                )
                TextButton(onClick = onDismissRequest) {
                    Text(
                        text = "[esc close]",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                        color = colores.textSecondary,
                    )
                }
            }

            TituloSeccion("Densidad")
            TuiZoomPreset.entries.forEach { preset ->
                val activo = preset == zoom.preset
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Sin fondo ni forma en la fila inactiva: la marca es el "▌" y el
                        // color, igual que la selección del TUI.
                        .clickable { zoom.select(preset) }
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (activo) "▌" else " ",
                        fontFamily = FontFamily.Monospace,
                        color = colores.selection,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = preset.label.lowercase(),
                        fontFamily = FontFamily.Monospace,
                        color = if (activo) colores.selection else colores.textPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "${(preset.scale * 100).roundToInt()}%",
                        fontFamily = FontFamily.Monospace,
                        color = colores.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            TituloSeccion("Aplicación")
            FilaDato("versionName", BuildConfig.VERSION_NAME)
            FilaDato("versionCode", BuildConfig.VERSION_CODE.toString())

            TituloSeccion("Servidor")
            FilaDato("OpenCode", "127.0.0.1:49374")

            Text(
                text = "Sin más ajustes: los que no existen aquí son los que no están cableados.",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = colores.textSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colores.surface)
                    .padding(8.dp),
            )
        }
    }
}

@Composable
private fun TituloSeccion(texto: String) {
    val colores = LocalTuiColors.current
    Text(
        text = texto.uppercase(),
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.labelSmall,
        color = colores.textSecondary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun FilaDato(clave: String, valor: String) {
    val colores = LocalTuiColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = clave,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = colores.textSecondary,
        )
        Text(
            text = valor,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = colores.textPrimary,
        )
    }
}
