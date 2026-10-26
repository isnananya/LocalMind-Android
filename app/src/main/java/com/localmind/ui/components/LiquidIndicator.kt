package com.localmind.ui.components

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.localmind.ui.theme.IosTheme
import kotlin.math.abs
import kotlin.math.min

/**
 * 重力方向（左右倾斜），-1..1。
 *
 * 真实液态玻璃的高光随设备倾斜走；没有这个，光泽永远钉在同一个位置，
 * 看起来就是一张贴图。加速度计不需要权限。
 */
@Composable
fun rememberTiltX(): Float {
    val context = LocalContext.current
    val sm = remember(context) { context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager }
    val sensor = remember(sm) { sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }
    var tilt by remember { mutableFloatStateOf(0f) }
    DisposableEffect(sensor) {
        if (sensor == null) return@DisposableEffect onDispose { }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                // 横屏/竖屏都取 x 分量，限制在 ±1，低通滤波防抖。
                val raw = (event.values[0] / 9.81f).coerceIn(-1f, 1f)
                tilt = tilt * 0.85f + raw * 0.15f
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sm?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm?.unregisterListener(listener) }
    }
    return tilt
}

/**
 * 真正的液态选中框：不是固定椭圆。
 *
 *  * 位置用 [Animatable] + spring（有速度、会过冲）；
 *  * 拖动时按速度拉伸/压扁，松手把速度交给 spring，回弹带惯性；
 *  * 形状是动态 squircle：左右控制点随速度外扩，形成"被拖着走"的水滴；
 *  * 高光随重力方向偏移（[rememberTiltX]）。
 */
@Composable
fun LiquidIndicator(
    x: Float,
    velocity: Float,
    dragging: Boolean,
    tiltX: Float,
    modifier: Modifier = Modifier,
    baseWidth: Dp = 54.dp,
    baseHeight: Dp = 54.dp,
) {
    val ios = IosTheme.palette
    val density = LocalDensity.current
    val w0 = with(density) { baseWidth.toPx() }
    val h0 = with(density) { baseHeight.toPx() }

    // 速度越大越像水滴；拖动时保底 12% 拉伸。
    val speed = abs(velocity)
    val stretch = 1f + min(0.55f, speed / 2600f) + if (dragging) 0.14f else 0f
    val squash = 1f - min(0.18f, speed / 7000f) - if (dragging) 0.06f else 0f
    val w = w0 * stretch
    val h = h0 * squash

    Canvas(modifier) {
        val cy = size.height / 2f
        val cx = x
        val left = (cx - w / 2f).coerceAtLeast(0f)
        val right = (cx + w / 2f).coerceAtMost(size.width)
        val top = cy - h / 2f
        val bottom = cy + h / 2f
        val r = h / 2f
        // 速度方向的一侧更鼓，另一侧更收 —— 这是"液体被拖拽"的关键。
        val lead = (velocity / 9000f).coerceIn(-0.35f, 0.35f)
        val path = Path().apply {
            moveTo(left + r, top)
            lineTo(right - r, top)
            cubicTo(
                right - r + r * (0.55f + lead), top,
                right + r * lead * 0.6f, cy - r * 0.35f,
                right + r * 0.10f + r * lead, cy,
            )
            cubicTo(
                right + r * lead * 0.6f, cy + r * 0.35f,
                right - r + r * (0.55f + lead), bottom,
                right - r, bottom,
            )
            lineTo(left + r, bottom)
            cubicTo(
                left + r - r * (0.55f - lead), bottom,
                left - r * lead * 0.6f, cy + r * 0.35f,
                left - r * 0.10f + r * lead, cy,
            )
            cubicTo(
                left - r * lead * 0.6f, cy - r * 0.35f,
                left + r - r * (0.55f - lead), top,
                left + r, top,
            )
            close()
        }

        val highlightShift = tiltX * w * 0.18f
        drawPath(
            path = path,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = if (ios.isDark) 0.30f else 0.96f),
                    (if (ios.isDark) Color(0xFF7FA8FF) else Color(0xFFE3EEFF)).copy(alpha = 0.62f),
                    Color.White.copy(alpha = if (ios.isDark) 0.12f else 0.70f),
                ),
                start = Offset(left + highlightShift, top),
                end = Offset(right + highlightShift, bottom),
            ),
        )
        drawPath(path, Color.White.copy(alpha = 0.55f), style = Stroke(width = 1.dp.toPx()))
        // 顶部镜面高光：跟重力方向走。
        drawOval(
            brush = Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.55f), Color.Transparent),
                center = Offset(cx + highlightShift, top + h * 0.22f),
                radius = w * 0.42f,
            ),
            topLeft = Offset(left, top),
            size = Size(w, h * 0.55f),
        )
    }
}
