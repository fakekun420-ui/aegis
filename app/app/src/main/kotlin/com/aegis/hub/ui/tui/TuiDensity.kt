package com.aegis.hub.ui.tui

import android.content.Context
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import kotlin.math.abs

// ---------------------------------------------------------------------------
// TuiDensity — capa de zoom/densidad del TUI.
//
// Presets de escala para comprimir o ampliar TODO el contenido dentro de la
// pantalla (mucho más información en el móvil, o texto legible sin acercar
// el teléfono). El factor se aplica multiplicando `Density.density`, que es
// el único punto por el que pasan tanto dp→px como sp→px
// (sp.toPx() = sp * fontScale * density): escala lineal y uniforme de toda la
// UI, sin duplicar escala por fontScale y sin romper la escala de fuente del
// sistema (accesibilidad intacta).
// ---------------------------------------------------------------------------

/**
 * Presets de zoom del TUI. [scale] es el factor multiplicador de la densidad.
 *
 * Micro/Compacto meten mucha más información en pantalla; Ampliado prioriza
 * legibilidad. Normal es la densidad nativa del dispositivo.
 */
enum class TuiZoomPreset(val scale: Float, val label: String) {
    MICRO(0.70f, "Micro"),
    COMPACT(0.85f, "Compacto"),
    NORMAL(1.00f, "Normal"),
    AMPLIADO(1.15f, "Ampliado");

    companion object {
        /** Restaura el preset a partir de una escala persistida. */
        fun fromScale(scale: Float): TuiZoomPreset =
            entries.firstOrNull { abs(it.scale - scale) < 0.005f } ?: NORMAL
    }
}

/**
 * Persistencia del factor de escala en SharedPreferences, con el mismo patrón
 * que `ModelPreferences`/`AgentPreferences` (capa de datos de Aegis). Toda
 * lectura/escritura es tolerante a fallos: si las prefs fallan, el TUI sigue
 * funcionando con el preset NORMAL.
 */
object TuiZoomPreferences {
    private const val PREFS_NAME = "aegis_tui"
    private const val KEY_ZOOM_SCALE = "tui_zoom_scale"

    fun load(context: Context): TuiZoomPreset = runCatching {
        val scale = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_ZOOM_SCALE, TuiZoomPreset.NORMAL.scale)
        TuiZoomPreset.fromScale(scale)
    }.getOrDefault(TuiZoomPreset.NORMAL)

    fun save(context: Context, preset: TuiZoomPreset) {
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putFloat(KEY_ZOOM_SCALE, preset.scale)
                .apply()
        }
    }
}

/**
 * Estado observable del zoom TUI: el preset activo, legible durante la
 * composición (recompone al cambiar) y escribible vía [select], que además
 * persiste la elección.
 */
class TuiZoomState internal constructor(
    initial: TuiZoomPreset,
    private val onPersist: (TuiZoomPreset) -> Unit
) {
    var preset: TuiZoomPreset by mutableStateOf(initial)
        private set

    /** Factor de escala actual (derivado del preset). */
    val scale: Float get() = preset.scale

    /** Selecciona y persiste un preset. */
    fun select(preset: TuiZoomPreset) {
        if (this.preset == preset) return
        this.preset = preset
        onPersist(preset)
    }
}

/** CompositionLocal del estado de zoom, para alcanzarlo sin perforar props. */
val LocalTuiZoomState = staticCompositionLocalOf<TuiZoomState> {
    error("TerminalDensityProvider no está colocado en el árbol de composición")
}

/**
 * Crea (o restaura) el estado de zoom: `rememberSaveable` sobrevive a
 * rotación/recreación de la Activity y [TuiZoomPreferences] sobrevive a
 * reinicios de la app.
 */
@Composable
fun rememberTuiZoomState(): TuiZoomState {
    val context = LocalContext.current.applicationContext
    var scale by rememberSaveable { mutableStateOf(TuiZoomPreferences.load(context).scale) }
    return remember(scale, context) {
        TuiZoomState(TuiZoomPreset.fromScale(scale)) { preset ->
            scale = preset.scale
            TuiZoomPreferences.save(context, preset)
        }
    }
}

/**
 * Proveedor de densidad TUI: aplica el zoom del usuario y fuerza la
 * monoespaciada en todo el subárbol.
 *
 * - `LocalDensity` → [Density] escalado por `state.scale` (tamaño de toda la
 *   UI, dp y sp, uniformemente; `fontScale` del sistema se conserva).
 * - `LocalTextStyle` → el estilo entrante con `FontFamily.Monospace`, para
 *   que ningún `Text` sin estilo personalizado rompa la monoespaciada.
 * - `LocalTuiZoomState` → estado de zoom accesible desde cualquier hijo.
 *
 * Coloca debajo de [TuiTheme] para que convivan colores + densidad:
 * ```
 * TuiTheme {
 *     TerminalDensityProvider(state = rememberTuiZoomState()) {
 *         TuiScreen()
 *     }
 * }
 * ```
 */
@Composable
fun TerminalDensityProvider(
    state: TuiZoomState = rememberTuiZoomState(),
    content: @Composable () -> Unit
) {
    val baseDensity = LocalDensity.current
    val scale = state.scale
    val tuiDensity = remember(baseDensity, scale) {
        Density(
            density = baseDensity.density * scale,
            fontScale = baseDensity.fontScale
        )
    }
    CompositionLocalProvider(
        LocalDensity provides tuiDensity,
        // Se lee el valor exterior antes de proveer: solo se fuerza la familia,
        // el resto del estilo (tamaño, color) sigue viniendo de quien envuelve.
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
        LocalTuiZoomState provides state,
        content = content
    )
}
