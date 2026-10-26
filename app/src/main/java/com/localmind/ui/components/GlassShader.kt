package com.localmind.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.ui.geometry.Rect

/**
 * 液态玻璃的「折射」着色器（Android 13+ AGSL）。
 *
 * 模糊只是一半。真正的玻璃感来自边缘：靠近边缘的像素会被法线方向掰弯、
 * 不同通道弯曲量略微不同（色散），再叠一层高光。这里用 SDF 判断离边缘多远，
 * 越靠边位移越大。
 *
 * 低版本没有 RuntimeShader，调用方会退回「模糊图 + 渐变高光」的旧路径。
 */
object GlassShader {

    private const val AGSL = """
        uniform shader content;
        uniform float2 size;
        uniform float radius;
        uniform float refraction;
        uniform float3 tint;
        uniform float tintAlpha;
        uniform float highlight;

        half4 main(float2 coord) {
            float2 halfSize = size * 0.5;
            float2 p = coord - halfSize;
            float2 q = abs(p) - (halfSize - float2(radius));
            float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;

            // 边缘带：0 = 内部，1 = 贴边
            float edge = 1.0 - smoothstep(0.0, 16.0, -d);
            float2 dir = normalize(p + float2(0.001));
            float2 uv = coord + dir * edge * refraction;

            half4 c = content.eval(uv);
            // 色散：红/蓝通道偏移略不同
            half4 cr = content.eval(uv + dir * edge * refraction * 0.45);
            half4 cb = content.eval(uv - dir * edge * refraction * 0.45);
            c.r = cr.r;
            c.b = cb.b;

            // 玻璃底色（轻微染色）
            c.rgb = mix(c.rgb, tint, tintAlpha);

            // 上缘高光 + 下缘暗边，模拟玻璃厚度
            float rimTop = smoothstep(0.0, 2.0, coord.y) * (1.0 - smoothstep(0.0, 2.0, abs(coord.y - 1.0)));
            float topGlow = (1.0 - smoothstep(0.0, 22.0, abs(coord.y - 1.5))) * highlight;
            float bottomShade = smoothstep(0.0, 14.0, abs(coord.y - size.y + 1.0)) * 0.10;

            c.rgb += topGlow;
            c.rgb -= bottomShade;
            c.rgb = clamp(c.rgb, 0.0, 1.0);
            return c;
        }
    """

    /**
     * 生成一个已经映射到 [width] x [height] 视图坐标的着色器。
     *
     * @param bitmap 背景模糊图（整窗缩略图）
     * @param src    该栏在缩略图里对应的区域
     * @param refraction 边缘位移像素，建议 6–14
     */
    fun create(
        bitmap: Bitmap,
        src: Rect,
        width: Int,
        height: Int,
        radius: Float,
        refraction: Float,
        tint: Int,
        tintAlpha: Float,
        highlight: Float,
    ): Shader? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        if (width <= 0 || height <= 0 || bitmap.isRecycled) return null
        return try {
            val shader = RuntimeShader(AGSL)
            val bitmapShader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val sw = src.width.coerceAtLeast(1f)
            val sh = src.height.coerceAtLeast(1f)
            val sx = width / sw
            val sy = height / sh
            val matrix = Matrix().apply {
                setScale(sx, sy)
                postTranslate(-src.left * sx, -src.top * sy)
            }
            bitmapShader.setLocalMatrix(matrix)
            shader.setInputShader("content", bitmapShader)
            shader.setFloatUniform("size", width.toFloat(), height.toFloat())
            shader.setFloatUniform("radius", radius)
            shader.setFloatUniform("refraction", refraction)
            shader.setFloatUniform(
                "tint",
                ((tint shr 16) and 0xFF) / 255f,
                ((tint shr 8) and 0xFF) / 255f,
                (tint and 0xFF) / 255f,
            )
            shader.setFloatUniform("tintAlpha", tintAlpha)
            shader.setFloatUniform("highlight", highlight)
            shader
        } catch (t: Throwable) {
            android.util.Log.w("GlassShader", "shader 创建失败，退回模糊层：" + (t.message ?: t.javaClass.simpleName))
            null
        }
    }
}
