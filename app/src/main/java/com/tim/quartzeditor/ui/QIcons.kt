package com.tim.quartzeditor.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Icons Material doesn't have. */
object QIcons {
    private fun build(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    private fun ImageVector.Builder.line(block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )

    val Eraser: ImageVector by lazy {
        build("Eraser") {
            line {
                moveTo(15.5f, 4.5f)
                lineTo(20.5f, 9.5f)
                lineTo(11f, 19f)
                lineTo(6f, 19f)
                lineTo(3.5f, 16.5f)
                close()
                moveTo(10f, 10f)
                lineTo(15f, 15f)
                moveTo(11f, 19f)
                lineTo(20.5f, 19f)
            }
            path(fill = SolidColor(Color.Black), fillAlpha = 0.35f) {
                moveTo(10f, 10f)
                lineTo(15f, 15f)
                lineTo(11f, 19f)
                lineTo(6f, 19f)
                lineTo(3.5f, 16.5f)
                close()
            }
        }
    }

    /** Rectangle with a circle cut out: Photoshop's "add mask". */
    val Mask: ImageVector by lazy {
        build("Mask") {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                moveTo(3f, 5f)
                lineTo(21f, 5f)
                lineTo(21f, 19f)
                lineTo(3f, 19f)
                close()
                moveTo(12f, 7.5f)
                arcToRelative(4.5f, 4.5f, 0f, true, true, 0f, 9f)
                arcToRelative(4.5f, 4.5f, 0f, true, true, 0f, -9f)
                close()
            }
        }
    }

    /** Small checkerboard: lock transparent pixels. */
    val LockAlpha: ImageVector by lazy {
        build("LockAlpha") {
            line {
                moveTo(4f, 4f); lineTo(20f, 4f); lineTo(20f, 20f); lineTo(4f, 20f); close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(4f, 4f); lineTo(9.33f, 4f); lineTo(9.33f, 9.33f); lineTo(4f, 9.33f); close()
                moveTo(14.67f, 4f); lineTo(20f, 4f); lineTo(20f, 9.33f); lineTo(14.67f, 9.33f); close()
                moveTo(9.33f, 9.33f); lineTo(14.67f, 9.33f); lineTo(14.67f, 14.67f); lineTo(9.33f, 14.67f); close()
                moveTo(4f, 14.67f); lineTo(9.33f, 14.67f); lineTo(9.33f, 20f); lineTo(4f, 20f); close()
                moveTo(14.67f, 14.67f); lineTo(20f, 14.67f); lineTo(20f, 20f); lineTo(14.67f, 20f); close()
            }
        }
    }

    /** Four arrows: move the layer. */
    val Move: ImageVector by lazy {
        build("Move") {
            line {
                moveTo(12f, 3f); lineTo(12f, 21f)
                moveTo(3f, 12f); lineTo(21f, 12f)
                moveTo(9.5f, 5.5f); lineTo(12f, 3f); lineTo(14.5f, 5.5f)
                moveTo(9.5f, 18.5f); lineTo(12f, 21f); lineTo(14.5f, 18.5f)
                moveTo(5.5f, 9.5f); lineTo(3f, 12f); lineTo(5.5f, 14.5f)
                moveTo(18.5f, 9.5f); lineTo(21f, 12f); lineTo(18.5f, 14.5f)
            }
        }
    }

    /** Two overlapping squares with arrows: swap colors. */
    val Swap: ImageVector by lazy {
        build("Swap") {
            line {
                moveTo(6f, 9f); lineTo(6f, 6f); lineTo(15f, 6f)
                moveTo(13f, 4f); lineTo(15f, 6f); lineTo(13f, 8f)
                moveTo(18f, 15f); lineTo(18f, 18f); lineTo(9f, 18f)
                moveTo(11f, 16f); lineTo(9f, 18f); lineTo(11f, 20f)
            }
        }
    }
}
