package com.aegis.hub.ui.tui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
// TuiTheme — paleta y tipografía estrictas del estilo TUI OpenCode v2.
//
// Sin tema del sistema, sin modo claro: negro puro de terminal, bordes grises
// GitHub, texto claro, highlight melocotón/crema para selecciones y modales
// (como en los modales de OpenCode v2) y cursor amber. Todas las medidas de
// color vienen de la especificación visual TUI; añadir colores fuera de esta
// lista rompe el look.
// ---------------------------------------------------------------------------

// Fondos
val TuiBackground = Color(0xFF000000) // Fondo puro terminal
val TuiSurface = Color(0xFF0D0D0D) // Surface elevado (paneles, modales, tarjetas)

// Bordes y divisores
val TuiBorder = Color(0xFF262626) // Borde sutil de panel
val TuiBorderStrong = Color(0xFF333333) // Borde marcado (foco, cabeceras, selección de borde)
val TuiDivider = Color(0xFF21262D) // Divisores tenues entre filas

// Texto
val TuiTextPrimary = Color(0xFFE6EDF3) // Texto primario
val TuiTextSecondary = Color(0xFF8B949E) // Texto secundario/atenuado

// Selección / highlight (melocotón-crema, texto negro encima)
val TuiSelection = Color(0xFFF5D6B8)
val TuiOnSelection = Color(0xFF000000)

// Cursor
val TuiCursorAmber = Color(0xFFD29922) // Cursor amber
val TuiCursorWhite = Color(0xFFFFFFFF) // Cursor alternativo blanco

/**
 * Esquema TUI: los 11 colores base en un único objeto estable.
 *
 * Los componentes TUI leen de [LocalTuiColors] en vez de suelto, para poder
 * sobrescribir la paleta entera en previews/tests sin tocar cada componente.
 */
@Immutable
data class TuiColorScheme(
    val background: Color,
    val surface: Color,
    val border: Color,
    val borderStrong: Color,
    val divider: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val selection: Color,
    val onSelection: Color,
    val cursor: Color,
    val cursorFallback: Color
)

/** Instancia por defecto de la paleta TUI estricta. */
val TuiColors = TuiColorScheme(
    background = TuiBackground,
    surface = TuiSurface,
    border = TuiBorder,
    borderStrong = TuiBorderStrong,
    divider = TuiDivider,
    textPrimary = TuiTextPrimary,
    textSecondary = TuiTextSecondary,
    selection = TuiSelection,
    onSelection = TuiOnSelection,
    cursor = TuiCursorAmber,
    cursorFallback = TuiCursorWhite
)

val LocalTuiColors = staticCompositionLocalOf { TuiColors }

/**
 * Tipografía monoespaciada estricta: todo el TUI renderiza en monoespaciada
 * (FontFamily.Monospace), en la escala clásica de terminal. La familia se
 * fuerza además en [TerminalDensityProvider] vía LocalTextStyle, para que ni
 * un `Text` sin estilo se salga de la monoespaciada.
 */
val TuiTypography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 16.sp
    )
)

/**
 * Mapeo de la paleta TUI al [androidx.compose.material3.ColorScheme] para que
 * los componentes Material3 hereden el look TUI (listas, diálogos, snackbar).
 *
 * El rol "selección" es el highlight melocotón: cualquier composable Material
 * que resalte algo pinta crema con texto negro, como en OpenCode v2.
 */
private val TuiMaterialColorScheme = darkColorScheme(
    primary = TuiColors.selection,
    onPrimary = TuiColors.onSelection,
    background = TuiColors.background,
    onBackground = TuiColors.textPrimary,
    surface = TuiColors.surface,
    onSurface = TuiColors.textPrimary,
    surfaceVariant = TuiColors.surface,
    onSurfaceVariant = TuiColors.textSecondary,
    surfaceContainerLowest = TuiColors.background,
    surfaceContainerLow = TuiColors.surface,
    surfaceContainer = TuiColors.surface,
    surfaceContainerHigh = TuiColors.surface,
    surfaceContainerHighest = TuiColors.surface,
    outline = TuiColors.borderStrong,
    outlineVariant = TuiColors.divider
)

/**
 * Tema TUI: siempre oscuro y estricto (ignora el tema del sistema), con la
 * paleta [TuiColors] y la tipografía monoespaciada [TuiTypography].
 *
 * Combinación canónica con la capa de densidad:
 * ```
 * TuiTheme {
 *     TerminalDensityProvider {
 *         // contenido del TUI: zoom + monoespaciada + colores OpenCode
 *     }
 * }
 * ```
 */
@Composable
fun TuiTheme(
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalTuiColors provides TuiColors) {
        MaterialTheme(
            colorScheme = TuiMaterialColorScheme,
            typography = TuiTypography,
            content = content
        )
    }
}
