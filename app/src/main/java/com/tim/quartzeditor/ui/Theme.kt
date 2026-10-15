package com.tim.quartzeditor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

/** Quartz Editor's colors (dark, neutral grays so they don't fight with the picture). */
object Q {
    val workspace = Color(0xFF222222)
    val toolbar = Color(0xFF3A3A3A)
    val panel = Color(0xFF474747)
    val panelDark = Color(0xFF383838)
    val field = Color(0xFF2C2C2C)
    val row = Color(0xFF404040)
    val rowSelected = Color(0xFF5A6478)
    val divider = Color(0xFF666666)
    val text = Color(0xFFF2F2F2)
    val textDim = Color(0xFFB8B8B8)
    val accent = Color(0xFF4C8DFF)
    val launchCard = Color(0xFF333333)
    val launchCenter = Color(0xFF1F2D6B)
    val launchEdge = Color(0xFF080D26)
}

private val scheme = darkColorScheme(
    primary = Q.accent,
    onPrimary = Color.White,
    secondary = Q.accent,
    surface = Q.panelDark,
    onSurface = Q.text,
    surfaceVariant = Q.panel,
    onSurfaceVariant = Q.textDim,
    background = Q.workspace,
    onBackground = Q.text,
    surfaceContainer = Q.panelDark,
    surfaceContainerHigh = Q.panel,
    surfaceContainerHighest = Q.panel,
    outline = Q.divider,
)

@Composable
fun QuartzTheme(content: @Composable () -> Unit) {
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
