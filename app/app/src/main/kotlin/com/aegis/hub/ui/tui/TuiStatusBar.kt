package com.aegis.hub.ui.tui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Colores de la barra TUI inferior.
 */
private val StatusBarBackground = Color(0xFF0A0A0A)
private val StatusBarDivider = Color(0xFF1E1E1E)
private val StatusBarText = Color(0xFF999999)
private val StatusBarTextHighlighted = Color(0xFFD4D4D4)
private val StatusBarTextMuted = Color(0xFF555555)
private val StatusBarKey = Color(0xFF61AFEF)
private val StatusBarCost = Color(0xFF98C379)

/**
 * Barra de Estado TUI persistente al pie de la pantalla en fondo #0A0A0A con línea divisoria superior #1E1E1E.
 *
 * Información en una sola línea en tipografía monospace:
 * - Ruta y rama: "/sdcard/projects/...:main"
 * - Contexto y coste: "558.9K (53%) · $20.64" (o métricas de la sesión)
 * - Botón táctil clickeable a la derecha: "ctrl+p commands" que dispara la apertura del CommandPaletteDialog.
 * - Botones o presets rápidos para agrandar/achicar escala (Zoom / Densidad táctil: "[-] 85% [+]").
 */
@Composable
fun TuiStatusBar(
    pathAndBranch: String = "/sdcard/projects/...:main",
    contextAndCost: String = "558.9K (53%) · $20.64",
    zoomPercent: Int = 100,
    onZoomIn: () -> Unit = {},
    onZoomOut: () -> Unit = {},
    onOpenCommandPalette: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(StatusBarBackground)
    ) {
        // Línea divisoria superior #1E1E1E
        HorizontalDivider(
            thickness = 1.dp,
            color = StatusBarDivider
        )

        // Contenedor horizontal en una sola línea
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Sección izquierda con scroll horizontal si el contenido es largo en pantallas angostas
            Row(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. Ruta y rama: "/sdcard/projects/...:main"
                Text(
                    text = pathAndBranch,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = StatusBarText
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = "  |  ",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = StatusBarTextMuted
                    )
                )

                // 2. Contexto y coste: "558.9K (53%) · $20.64"
                Text(
                    text = contextAndCost,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = StatusBarCost
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(8.dp))

            // Sección derecha: Zoom y Command Palette
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 3. Control de escala / densidad / Zoom: "[-] 85% [+]"
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.semantics { contentDescription = "Zoom scale controls" }
                ) {
                    Text(
                        text = "[-]",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = StatusBarTextHighlighted
                        ),
                        modifier = Modifier
                            .clickable { onZoomOut() }
                            .padding(horizontal = 2.dp, vertical = 4.dp)
                    )
                    Text(
                        text = " ${zoomPercent}% ",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = StatusBarText
                        )
                    )
                    Text(
                        text = "[+]",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = StatusBarTextHighlighted
                        ),
                        modifier = Modifier
                            .clickable { onZoomIn() }
                            .padding(horizontal = 2.dp, vertical = 4.dp)
                    )
                }

                Text(
                    text = "|",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = StatusBarTextMuted
                    )
                )

                // 4. Botón táctil clickeable a la derecha: "ctrl+p commands"
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable { onOpenCommandPalette() }
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                        .semantics { contentDescription = "Open command palette" }
                ) {
                    Text(
                        text = "ctrl+p",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = StatusBarKey
                        )
                    )
                    Text(
                        text = " commands",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = StatusBarTextHighlighted
                        )
                    )
                }
            }
        }
    }
}
