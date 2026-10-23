package com.tim.quartzeditor.ui

import androidx.compose.ui.unit.dp

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

/** A set of interface colors (App settings → Appearance). */
enum class Palette(
    val label: String,
    val light: Boolean,
    val workspace: Color,
    val toolbar: Color,
    val panel: Color,
    val panelDark: Color,
    val field: Color,
    val row: Color,
    val rowSelected: Color,
    val divider: Color,
    val text: Color,
    val textDim: Color,
) {
    DARK(
        "Dark", false, Color(0xFF222222), Color(0xFF3A3A3A), Color(0xFF474747), Color(0xFF383838), Color(0xFF2C2C2C),
        Color(0xFF404040), Color(0xFF5A6478), Color(0xFF666666), Color(0xFFF2F2F2), Color(0xFFB8B8B8),
    ),
    MIDNIGHT(
        "Midnight", false, Color(0xFF0E0E10), Color(0xFF1C1C20), Color(0xFF232328), Color(0xFF18181C), Color(0xFF111114),
        Color(0xFF26262C), Color(0xFF3A4560), Color(0xFF3A3A42), Color(0xFFECECEF), Color(0xFF9A9AA4),
    ),
    MEDIUM(
        "Medium gray", false, Color(0xFF4A4A4A), Color(0xFF5A5A5A), Color(0xFF636363), Color(0xFF555555), Color(0xFF474747),
        Color(0xFF5C5C5C), Color(0xFF6E7890), Color(0xFF7A7A7A), Color(0xFFF7F7F7), Color(0xFFD0D0D0),
    ),
    LIGHT(
        "Light", true, Color(0xFFD9D9D9), Color(0xFFF1F1F1), Color(0xFFF7F7F7), Color(0xFFE9E9E9), Color(0xFFE1E1E1),
        Color(0xFFEDEDED), Color(0xFFC9D6EE), Color(0xFFBDBDBD), Color(0xFF1E1E1E), Color(0xFF5E5E5E),
    ),
}

/** Accent colors to choose from. */
val ACCENTS = listOf(
    0xFF4C8DFF, 0xFF2BB3A3, 0xFF8E6CFF, 0xFFE0559A, 0xFFFF8A3D, 0xFF4CAF50, 0xFFE53935, 0xFF9E9E9E,
).map { Color(it) }

/**
 * Quartz Editor's colors. They follow App settings (theme, accent, how see-through the panels
 * are) and everything on screen redraws when those change.
 */
object Q {
    var palette by mutableStateOf(Palette.DARK)
    var accentColor by mutableStateOf(Color(0xFF4C8DFF))
    /** How solid the toolbar, panels and windows are (1 = not see-through). */
    var panelAlpha by mutableFloatStateOf(1f)

    /** Compact interface: smaller margins and padding around the toolbar, panels, buttons and rows. */
    var compact by mutableStateOf(false)

    /** The menu bar layout's squarer look: the big containers' corners only slightly rounded. */
    var squared by mutableStateOf(false)

    /** A container's corner radius: [normal], or a slight rounding in the squarer look. */
    fun corner(normal: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp = if (squared) minOf(normal, 6.dp) else normal

    /** [normal] spacing, or [tight] with the compact interface on. */
    fun gap(normal: androidx.compose.ui.unit.Dp, tight: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp = if (compact) tight else normal

    val workspace: Color get() = palette.workspace
    val toolbar: Color get() = palette.toolbar.copy(alpha = panelAlpha)
    val panel: Color get() = palette.panel.copy(alpha = panelAlpha)
    val panelDark: Color get() = palette.panelDark.copy(alpha = if (panelAlpha < 1f) 0.6f else 1f)
    val field: Color get() = palette.field
    val row: Color get() = palette.row
    val rowSelected: Color get() = palette.rowSelected
    val divider: Color get() = palette.divider
    val text: Color get() = palette.text
    val textDim: Color get() = palette.textDim
    val accent: Color get() = accentColor
    val sliderThumb: Color get() = if (palette.light) accentColor else Color.White
    // The home screen keeps its own dark colors whatever the theme
    val launchCard = Color(0xFF333333)
    val launchText = Color(0xFFF2F2F2)
    val launchDim = Color(0xFFB8B8B8)
    val launchField = Color(0xFF2C2C2C)
    val launchCenter = Color(0xFF1F2D6B)
    val launchEdge = Color(0xFF080D26)

    /** Takes the saved settings. */
    fun load(prefs: android.content.SharedPreferences) {
        palette = Palette.entries.getOrElse(prefs.getInt("uiPalette", 0)) { Palette.DARK }
        accentColor = Color(prefs.getInt("uiAccent", 0xFF4C8DFF.toInt()))
        panelAlpha = prefs.getFloat("uiPanelAlpha", 1f).coerceIn(0.4f, 1f)
        compact = prefs.getBoolean("uiCompact", false)
    }

    fun save(prefs: android.content.SharedPreferences) {
        prefs.edit()
            .putInt("uiPalette", palette.ordinal)
            .putInt("uiAccent", accentColor.toArgb())
            .putFloat("uiPanelAlpha", panelAlpha)
            .putBoolean("uiCompact", compact)
            .apply()
    }
}

@Composable
fun QuartzTheme(content: @Composable () -> Unit) {
    val p = Q.palette
    val base = if (p.light) lightColorScheme() else darkColorScheme()
    val scheme = base.copy(
        primary = Q.accent,
        onPrimary = Color.White,
        secondary = Q.accent,
        surface = p.panelDark,
        onSurface = p.text,
        surfaceVariant = p.panel,
        onSurfaceVariant = p.textDim,
        background = p.workspace,
        onBackground = p.text,
        surfaceContainer = p.panelDark,
        surfaceContainerHigh = p.panel,
        surfaceContainerHighest = p.panel,
        surfaceContainerLow = p.panelDark,
        outline = p.divider,
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Gray checkerboard behind see-through pixels. */
fun Modifier.checker(cell: Float = 12f): Modifier = drawBehind {
    drawRect(Color(0xFFCFCFCF))
    val n = (size.width / cell).toInt() + 1
    val m = (size.height / cell).toInt() + 1
    for (i in 0..n) for (j in 0..m) {
        if ((i + j) % 2 == 0) drawRect(Color(0xFF9E9E9E), Offset(i * cell, j * cell), Size(cell, cell))
    }
}
