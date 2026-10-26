package com.localmind.ui.chrome

import android.graphics.RuntimeShader
import android.os.Build

/** 原生玻璃栏是否可用：Android 13+ 且当前渲染器支持 RuntimeShader。 */
object NativeGlassBar {
    fun supported(): Boolean {
        // 原生 host 已搭好（MainActivity.attachGlassBar），但 compact 宽度下库的
        // LiquidGlass 子层重建后采样会失效，暂时关闭，走 Compose 里的 AGSL 紧凑玻璃。
        return false
    }
}
