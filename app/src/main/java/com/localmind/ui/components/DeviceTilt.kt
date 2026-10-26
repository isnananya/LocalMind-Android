package com.localmind.ui.components

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * 重力方向（左右倾斜），-1..1。
 *
 * LiquidTab 的高光随设备倾斜移动（BloomTiltTracker 用旋转矢量传感器）；
 * 这里用加速度计的 x 分量近似，低通滤波防抖。不需要权限。
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
