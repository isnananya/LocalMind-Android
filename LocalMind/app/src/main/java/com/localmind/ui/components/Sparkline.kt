package com.localmind.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.pow

/**
 * 轻量折线图（状态页的实时曲线）。
 *
 * ## 来源与许可
 *
 * 改编自 [AndroidMonitor](https://github.com/TerminalDev-1/AndroidMonitor-Preview)
 * 的 `ui/components/LineChart.kt`：
 * MIT License, Copyright (c) 2026 Android Monitor contributors。
 * 改动：去掉 MaterialTheme 依赖（改用调用方传入的令牌色）、换成固定容量环形数组、
 * 补一个 [SparkSeries.dashed] 的图例语义。绘制算法（填充渐变 / 网格 / 圆角线帽）保留。
 */
@Immutable
data class SparkSeries(
    val values: List<Float>,
    val color: Color,
    val fill: Boolean = true,
    val dashed: Boolean = false,
)

/**
 * 最新采样在最右边。
 *
 * @param maxValue null = 按数据自动取整（网络速率这类没有 100% 上限的曲线用它）。
 */
@Composable
fun Sparkline(
    series: List<SparkSeries>,
    modifier: Modifier = Modifier,
    maxValue: Float? = 100f,
    frameColor: Color = series.firstOrNull()?.color ?: Color.Transparent,
    gridColor: Color = Color.White.copy(alpha = 0.10f),
    showGrid: Boolean = true,
    capacity: Int = 60,
) {
    Canvas(modifier.clipToBounds()) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas
        val top = (maxValue ?: niceCeil(series.maxOfOrNull { s -> s.values.maxOrNull() ?: 0f } ?: 0f))
            .coerceAtLeast(1f)

        if (showGrid) {
            for (i in 1 until 10) {
                val x = width * i / 10f
                drawLine(gridColor, Offset(x, 0f), Offset(x, height), strokeWidth = 1f)
            }
            for (i in 1 until 5) {
                val y = height * i / 5f
                drawLine(gridColor, Offset(0f, y), Offset(width, y), strokeWidth = 1f)
            }
        }

        val stepX = width / (capacity - 1).coerceAtLeast(1)
        for (s in series) {
            val values = s.values.takeLast(capacity)
            if (values.size < 2) continue
            val offset = capacity - values.size
            val line = Path()
            values.forEachIndexed { i, value ->
                val x = (offset + i) * stepX
                val y = height - (value / top).coerceIn(0f, 1f) * height
                if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
            }
            if (s.fill) {
                val area = Path().apply {
                    addPath(line)
                    lineTo(width, height)
                    lineTo(offset * stepX, height)
                    close()
                }
                drawPath(
                    area,
                    Brush.verticalGradient(
                        listOf(s.color.copy(alpha = 0.30f), s.color.copy(alpha = 0.03f))
                    ),
                )
            }
            drawPath(
                line,
                s.color,
                style = Stroke(
                    width = 1.5.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = if (s.dashed) {
                        PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
                    } else null,
                ),
            )
        }

        if (frameColor.alpha > 0f) {
            drawRect(frameColor.copy(alpha = 0.20f), style = Stroke(width = 1.dp.toPx()))
        }
    }
}

/** 把最大值向上取到 1/2/5 × 10^n，避免曲线贴着顶边。 */
private fun niceCeil(value: Float): Float {
    if (value <= 0f) return 1f
    val exp = kotlin.math.floor(kotlin.math.log10(value.toDouble())).toInt()
    val base = 10.0.pow(exp).toFloat()
    val fraction = value / base
    val nice = when {
        fraction <= 1f -> 1f
        fraction <= 2f -> 2f
        fraction <= 5f -> 5f
        else -> 10f
    }
    return ceil((nice * base).toDouble()).toFloat()
}
