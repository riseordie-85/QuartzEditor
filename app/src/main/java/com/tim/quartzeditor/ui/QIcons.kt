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

    /** A box with corner handles and a turning arrow: free transform (scale, rotate, distort). */
    /** A box with a handle box at each corner: Transform. */
    val Transform: ImageVector by lazy {
        build("Transform") {
            // The four sides, between the corner handles
            line {
                moveTo(7.5f, 5f); lineTo(16.5f, 5f)
                moveTo(7.5f, 19f); lineTo(16.5f, 19f)
                moveTo(5f, 7.5f); lineTo(5f, 16.5f)
                moveTo(19f, 7.5f); lineTo(19f, 16.5f)
            }
            // The handles: small open boxes on the corners
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f, strokeLineJoin = StrokeJoin.Miter) {
                for ((x, y) in listOf(5f to 5f, 19f to 5f, 19f to 19f, 5f to 19f)) {
                    moveTo(x - 2.5f, y - 2.5f); lineTo(x + 2.5f, y - 2.5f); lineTo(x + 2.5f, y + 2.5f); lineTo(x - 2.5f, y + 2.5f); close()
                }
            }
        }
    }

    /** A slanted four-sided shape with a corner handle on each corner: Perspective crop. */
    val PerspectiveCrop: ImageVector by lazy {
        build("PerspectiveCrop") {
            line {
                moveTo(7f, 5f); lineTo(17f, 5f); lineTo(21f, 19f); lineTo(3f, 19f); close()
            }
            path(fill = SolidColor(Color.Black)) {
                for ((x, y) in listOf(7f to 5f, 17f to 5f, 21f to 19f, 3f to 19f)) {
                    moveTo(x - 2f, y - 2f); lineTo(x + 2f, y - 2f); lineTo(x + 2f, y + 2f); lineTo(x - 2f, y + 2f); close()
                }
            }
        }
    }

    /** Tim's emblem for the floating modifier button (from his modifier.svg, 500×500). */
    val ModifierKey: ImageVector by lazy {
        ImageVector.Builder("ModifierKey", 24.dp, 24.dp, 500f, 500f).apply {
            addPath(
                androidx.compose.ui.graphics.vector.addPathNodes(
                    "M325.52,450.5c-52.85,21.06-111.71,19.01-162.89-5.21-79.6-37.66-129.08-118.67-124.42-207.1,4.25-80.68,52.5-150.95,125.72-184.42,34.5-15.76,71.86-21.63,109.69-17.45,37.46,4.13,72.95,18.69,103,41.23,71.51,53.66,101.79,146.96,76.86,232.51C434.85,373.97,387.53,425.8,325.52,450.5ZM177.36,203.2l-2.14-66.38,16.16-9.13,62.72-36.5,10.57-30.85-77.21,32.98-12.83,43.84-22.19,10.02L177.36,203.2ZM366.5,115.13c-31.67-17.72-63.23-35.31-96.3-52.11l61.06,80.19-22.16,24.17,40.03,4.99-52.08,44.75-30.72-122.97-50.5,94.73,25.5,157.34,12.97-31.11,28.96-67.24,13.5-29.63,4.87,72.01-49.33,52.1-11.64,12.74c-.29-3.56-.74-5.86-2.06-8.88l-47.57-108.68-96.22-67.86,32.64,76.74,21.72,51-7.31-38.09,49.08-21.7-29.48,62.89,75.98,57.75c2.39,1.82,4.11,3.49,5.68,6.1l42.61,71.1,43.77-51.71-22.98-15.7,32.89-22.46-17.15-40.4-22.41,71.5-58.74-16.18,82.45-56.44,36.74-25.4-63.25-61.11,62.03-16.78-9.24-28.81-7.95-15.06-13.19-24.64,37.68-16.75L366.5,115.13ZM95.43,168.27l48.29-20.43,25.8-58.38-52.49,55.14L95.43,168.27ZM367.05,115.79l-.32.14-12.59,47.36,62.11,27.58L367.05,115.79ZM410.97,318.46l-.86-13.08,6.4-113.83-49.99,30.72,20.99,45.36L410.97,318.46ZM116.65,239.32l-10.01-16.15-33.48,41.69L116.65,239.32ZM156.28,319.54l-83.11-54.36,42.88,102.01L156.28,319.54ZM388.56,356.14l24.38-33.56-40.03-29.17-34.58,83.66L388.56,356.14ZM259.31,426.98l-43.8-20.02,22.55-42.08-105.64-11.02,34.56,59.71,61.26,13.24L259.31,426.98Z"
                ),
                fill = SolidColor(Color.Black),
            )
        }.build()
    }

    /** A pen nib with a curve and its anchor points: the shape pen. */
    val Pen: ImageVector by lazy {
        build("Pen") {
            line {
                // Nib
                moveTo(12f, 3f)
                lineTo(17f, 11f)
                lineTo(14.5f, 17f)
                lineTo(9.5f, 17f)
                lineTo(7f, 11f)
                close()
                moveTo(12f, 3f)
                lineTo(12f, 10.5f)
                moveTo(9.5f, 20.5f)
                lineTo(14.5f, 20.5f)
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(13.3f, 11.5f)
                arcTo(1.3f, 1.3f, 0f, true, true, 10.7f, 11.5f)
                arcTo(1.3f, 1.3f, 0f, true, true, 13.3f, 11.5f)
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

    /** Dashed square: rectangle selection. */
    val SelectRect: ImageVector by lazy {
        build("SelectRect") {
            line {
                // Dashes around a square
                val pts = listOf(4f to 4f, 20f to 4f, 20f to 20f, 4f to 20f, 4f to 4f)
                for (i in 0 until 4) {
                    val (x0, y0) = pts[i]
                    val (x1, y1) = pts[i + 1]
                    for (k in 0 until 4) {
                        val a = k / 4f
                        val b = (k + 0.55f) / 4f
                        moveTo(x0 + (x1 - x0) * a, y0 + (y1 - y0) * a)
                        lineTo(x0 + (x1 - x0) * b, y0 + (y1 - y0) * b)
                    }
                }
            }
        }
    }

    /** Dashed circle: ellipse selection. */
    val SelectEllipse: ImageVector by lazy {
        build("SelectEllipse") {
            line {
                val n = 14
                for (k in 0 until n) {
                    val a0 = 2 * Math.PI * k / n
                    val a1 = 2 * Math.PI * (k + 0.55) / n
                    moveTo(12f + 8.5f * kotlin.math.cos(a0).toFloat(), 12f + 8.5f * kotlin.math.sin(a0).toFloat())
                    lineTo(12f + 8.5f * kotlin.math.cos(a1).toFloat(), 12f + 8.5f * kotlin.math.sin(a1).toFloat())
                }
            }
        }
    }
}
