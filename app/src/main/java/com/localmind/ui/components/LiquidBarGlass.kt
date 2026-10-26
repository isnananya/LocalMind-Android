package com.localmind.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * LiquidTab 的液态玻璃绘制内核（AGSL 与几何/透明度常量按原项目源码移植）。
 *
 * 底板 = 模糊截图 → 圆角折射着色器 → 40% 表面填充 → 双光源高光描边；
 * 胶囊 = 清晰截图；按压时才换上色散透镜（LiquidTab 的 lensProgress 行为）。
 */
internal class LiquidBarGlass {

    var density = 2.75f

    private val sdfSrc = """
            float radiusAt(float2 coord, float4 radii) {
                if (coord.x >= 0.0) {
                    if (coord.y <= 0.0) return radii.y;
                    else return radii.z;
                } else {
                    if (coord.y <= 0.0) return radii.x;
                    else return radii.w;
                }
            }

            float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
                float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
                float outside = length(max(cornerCoord, 0.0)) - radius;
                float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
                return outside + inside;
            }

            float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
                float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
                if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
                    return sign(coord) * normalize(max(cornerCoord, 0.0));
                } else {
                    float gradX = step(cornerCoord.y, cornerCoord.x);
                    return sign(coord) * float2(gradX, 1.0 - gradX);
                }
            }

            float circleMap(float x) {
                return 1.0 - sqrt(1.0 - x * x);
            }
    """

    private val panelAgs = sdfSrc + """
            uniform shader content;
            uniform float2 size;
            uniform float2 offset;
            uniform float4 cornerRadii;
            uniform float refractionHeight;
            uniform float refractionAmount;
            uniform float depthEffect;

            half4 main(float2 coord) {
                float2 halfSize = size * 0.5;
                float2 centeredCoord = (coord + offset) - halfSize;
                float radius = radiusAt(coord, cornerRadii);
                float sd = sdRoundedRect(centeredCoord, halfSize, radius);
                if (-sd >= refractionHeight) return content.eval(coord);
                sd = min(sd, 0.0);
                float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
                float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
                float2 grad = normalize(
                    gradSdRoundedRect(centeredCoord, halfSize, gradRadius) +
                    depthEffect * normalize(centeredCoord)
                );
                return content.eval(coord + d * grad);
            }
    """

    private val pillAgs = sdfSrc + """
            uniform shader content;
            uniform float2 size;
            uniform float2 offset;
            uniform float4 cornerRadii;
            uniform float refractionHeight;
            uniform float refractionAmount;
            uniform float depthEffect;
            uniform float chromaticAberration;

            half4 main(float2 coord) {
                float2 halfSize = size * 0.5;
                float2 centeredCoord = (coord + offset) - halfSize;
                float radius = radiusAt(coord, cornerRadii);
                float sd = sdRoundedRect(centeredCoord, halfSize, radius);
                if (-sd >= refractionHeight) return content.eval(coord);
                sd = min(sd, 0.0);
                float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
                float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
                float2 grad = normalize(
                    gradSdRoundedRect(centeredCoord, halfSize, gradRadius) +
                    depthEffect * normalize(centeredCoord)
                );
                float2 refractedCoord = coord + d * grad;
                float dispersionIntensity = chromaticAberration *
                    ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));
                float2 dispersedCoord = d * grad * dispersionIntensity;

                half4 color = half4(0.0);
                half4 red = content.eval(refractedCoord + dispersedCoord);
                color.r += red.r / 3.5;
                color.a += red.a / 7.0;
                half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));
                color.r += orange.r / 3.5;
                color.g += orange.g / 7.0;
                color.a += orange.a / 7.0;
                half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));
                color.r += yellow.r / 3.5;
                color.g += yellow.g / 3.5;
                color.a += yellow.a / 7.0;
                half4 green = content.eval(refractedCoord);
                color.g += green.g / 3.5;
                color.a += green.a / 7.0;
                half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));
                color.g += cyan.g / 3.5;
                color.b += cyan.b / 3.0;
                color.a += cyan.a / 7.0;
                half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));
                color.b += blue.b / 3.0;
                color.a += blue.a / 7.0;
                half4 purple = content.eval(refractedCoord - dispersedCoord);
                color.r += purple.r / 7.0;
                color.b += purple.b / 3.0;
                color.a += purple.a / 7.0;
                return color;
            }
    """

    private val bloomAgs = """
            uniform float2 halfView;
            uniform float highlightAlpha;

            layout(color) uniform half4 strokeColor;
            uniform float strokeAlphaMul;

            uniform float3 lightDir1;
            layout(color) uniform half4 lightColor1;
            uniform float lightIntensity1;

            uniform float3 lightDir2;
            layout(color) uniform half4 lightColor2;
            uniform float lightIntensity2;

            half4 main(float2 fragCoord) {
                float2 delta = fragCoord - halfView;
                float2 n = delta / max(length(delta), 0.001);
                half3 rgb = strokeColor.rgb * half(strokeAlphaMul);
                float l1 = dot(n, normalize(lightDir1.xy));
                rgb += half(l1 * l1 * lightIntensity1) * lightColor1.rgb;
                float l2 = dot(n, normalize(lightDir2.xy));
                rgb += half(l2 * l2 * lightIntensity2) * lightColor2.rgb;
                return half4(rgb * half(highlightAlpha), half(highlightAlpha));
            }
    """

    private val panelShader: RuntimeShader? = runCatching { RuntimeShader(panelAgs) }
        .onFailure { android.util.Log.w("LiquidBarGlass", "panel shader: " + it.message) }
        .getOrNull()
    private val pillShader: RuntimeShader? = runCatching { RuntimeShader(pillAgs) }
        .onFailure { android.util.Log.w("LiquidBarGlass", "pill shader: " + it.message) }
        .getOrNull()
    private val bloomShader: RuntimeShader? = runCatching { RuntimeShader(bloomAgs) }
        .onFailure { android.util.Log.w("LiquidBarGlass", "bloom shader: " + it.message) }
        .getOrNull()

    private val lensPaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
    private val bitmapPaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
    private val fillPaint = Paint().apply { isAntiAlias = true }
    private val plainStroke = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE }
    private val bloomPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE }
    private val panelPath = Path()
    private val pillPath = Path()
    private val matrix = Matrix()

    private var panelContent: BitmapShader? = null
    private var panelContentKey = 0
    private var pillContent: BitmapShader? = null
    private var pillContentKey = 0

    private val canShader: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    private fun contentShader(
        bitmap: Bitmap,
        sample: Int,
        winLeft: Float,
        winTop: Float,
    ): BitmapShader {
        val key = System.identityHashCode(bitmap) * 31 + (winLeft.toInt() xor (winTop.toInt() shl 8))
        val cached = panelContent
        if (cached != null && panelContentKey == key) return cached
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        matrix.setScale(sample.toFloat(), sample.toFloat())
        matrix.postTranslate(-winLeft, -winTop)
        shader.setLocalMatrix(matrix)
        panelContent = shader
        panelContentKey = key
        return shader
    }

    private fun drawBackdrop(
        canvas: Canvas,
        bitmap: Bitmap?,
        sample: Int,
        winLeft: Float,
        winTop: Float,
    ) {
        val bmp = bitmap ?: return
        matrix.setScale(sample.toFloat(), sample.toFloat())
        matrix.postTranslate(-winLeft, -winTop)
        canvas.drawBitmap(bmp, matrix, bitmapPaint)
    }

    /** 底板：模糊背景 → 边缘折射 → 40% 填充 → 描边。 */
    fun drawPanel(
        canvas: Canvas,
        backdrop: Bitmap?,
        sample: Int,
        winLeft: Float,
        winTop: Float,
        width: Float,
        height: Float,
        radius: Float,
        dark: Boolean,
        liquid: Boolean,
        outline: Boolean,
        highlight: Float,
        fillColor: Int,
        fillAlpha: Float,
        tiltX: Float,
        lensBoost: Float = 1f,
    ) {
        if (width <= 0f || height <= 0f) return
        panelPath.reset()
        panelPath.addRoundRect(0f, 0f, width, height, radius, radius, Path.Direction.CW)

        val save = canvas.save()
        canvas.clipPath(panelPath)
        val shader = if (liquid && backdrop != null && canShader) panelShader else null
        if (shader != null && backdrop != null) {
            shader.setInputShader("content", contentShader(backdrop, sample, winLeft, winTop))
            shader.setFloatUniform("size", width, height)
            shader.setFloatUniform("offset", 0f, 0f)
            shader.setFloatUniform("cornerRadii", radius, radius, radius, radius)
            shader.setFloatUniform("refractionHeight", 24f * density * lensBoost)
            shader.setFloatUniform("refractionAmount", -24f * density * lensBoost)
            shader.setFloatUniform("depthEffect", 0f)
            lensPaint.shader = shader
            canvas.drawRect(-radius, -radius, width + radius, height + radius, lensPaint)
            lensPaint.shader = null
        } else {
            drawBackdrop(canvas, backdrop, sample, winLeft, winTop)
        }
        fillPaint.color = withAlpha(fillColor, fillAlpha)
        canvas.drawPath(panelPath, fillPaint)
        canvas.restoreToCount(save)

        if (!outline) return
        if (liquid && bloomShader != null && canShader) {
            val alpha = (if (dark) 0.58f * highlight else 0.75f).coerceAtMost(1f)
            drawBloom(canvas, bloomShader, 0f, 0f, width, height, radius, alpha, -45f, tiltX)
        } else {
            plainStroke.strokeWidth = 1f * density
            plainStroke.color = if (dark) {
                Color.argb((0.32f * 255f).toInt(), 255, 255, 255)
            } else {
                Color.argb((0.24f * 255f).toInt(), 0, 0, 0)
            }
            canvas.drawPath(panelPath, plainStroke)
        }
    }

    /**
     * 选中胶囊：整槽宽的圆角胶囊。静止 = 清晰背景 + 10% 白/黑 + 16% 的轻透镜；
     * 按压 = 透镜拉满（色散）+ 白色光环 + 3% 暗部。与 LiquidTab 的
     * drawSelection / drawLiquidIndicatorLighting 一致。
     */
    fun drawPill(
        canvas: Canvas,
        sharp: Bitmap?,
        blurred: Bitmap?,
        sample: Int,
        winLeft: Float,
        winTop: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        dark: Boolean,
        liquid: Boolean,
        press: Float,
        tiltX: Float,
    ) {
        val w = right - left
        val h = bottom - top
        if (w <= 0f || h <= 0f) return
        val radius = h * 0.5f
        pillPath.reset()
        pillPath.addRoundRect(left, top, right, bottom, radius, radius, Path.Direction.CW)

        val p = press.coerceIn(0f, 1f)
        val save = canvas.save()
        canvas.clipPath(pillPath)
        // 静止：磨砂（和底板同一张模糊图），不是能读字的透明窗口；
        // 按下去：换成清晰图 + 色散透镜，液体才"活"。
        val pressing = p > 0.12f
        val content = if (pressing) (sharp ?: blurred) else (blurred ?: sharp)
        if (content != null) {
            val lensP = if (liquid) (if (pressing) (0.16f + 0.84f * p) else 0.16f).coerceIn(0f, 1f) else 0f
            if (lensP > 0.001f && canShader && pillShader != null) {
                val shader = pillShader!!
                val bitmapShader = BitmapShader(content, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                matrix.setScale(sample.toFloat(), sample.toFloat())
                matrix.postTranslate(-winLeft, -winTop)
                bitmapShader.setLocalMatrix(matrix)
                shader.setInputShader("content", bitmapShader)
                shader.setFloatUniform("size", w, h)
                shader.setFloatUniform("offset", -left, -top)
                shader.setFloatUniform("cornerRadii", radius, radius, radius, radius)
                shader.setFloatUniform("refractionHeight", 10f * density * lensP)
                shader.setFloatUniform("refractionAmount", -14f * density * lensP)
                shader.setFloatUniform("depthEffect", 1f)
                shader.setFloatUniform("chromaticAberration", 0.5f)
                lensPaint.shader = shader
                canvas.drawRect(left - radius, top - radius, right + radius, bottom + radius, lensPaint)
                lensPaint.shader = null
            } else {
                drawBackdrop(canvas, content, sample, winLeft, winTop)
            }
        }

        // LiquidTab drawSelection：静止 10% 白/黑；按压再叠 3% 黑。
        val idle = 0.10f * (1f - p)
        val base = if (dark) Color.WHITE else Color.BLACK
        fillPaint.color = Color.argb(
            (idle * 255f).toInt().coerceIn(0, 255),
            Color.red(base), Color.green(base), Color.blue(base),
        )
        canvas.drawPath(pillPath, fillPaint)
        val pressed = 0.03f * p
        if (pressed > 0f) {
            fillPaint.color = Color.argb((pressed * 255f).toInt().coerceIn(0, 255), 0, 0, 0)
            canvas.drawPath(pillPath, fillPaint)
        }
        // drawLiquidIndicatorLighting：PLUS 提亮（低版本退回 SRC_OVER）。
        fillPaint.color = Color.argb(
            (((if (dark) 0.012f else 0.008f) + 0.024f * p) * 255f).toInt().coerceIn(0, 255),
            255, 255, 255,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            fillPaint.blendMode = android.graphics.BlendMode.PLUS
        }
        canvas.drawPath(pillPath, fillPaint)
        fillPaint.blendMode = null
        canvas.restoreToCount(save)

        val bloom = bloomShader
        if (liquid && bloom != null && canShader && p > 0.01f) {
            drawBloom(canvas, bloom, left, top, w, h, radius, p * (if (dark) 0.48f else 1f), 90f, tiltX)
        }
    }

    /** 底部渐变模糊：把模糊图铺在底栏上方（遮罩由调用方做）。 */
    fun drawBlurredVeil(
        canvas: Canvas,
        blurred: Bitmap,
        sample: Int,
        winLeft: Float,
        winTop: Float,
        width: Float,
        height: Float,
    ) {
        drawBackdrop(canvas, blurred, sample, winLeft, winTop)
    }

    private fun drawBloom(
        canvas: Canvas,
        shader: RuntimeShader,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        radius: Float,
        alpha: Float,
        rotation: Float,
        tiltX: Float,
    ) {
        if (alpha <= 0f) return
        shader.setFloatUniform("halfView", width / 2f, height / 2f)
        shader.setFloatUniform("highlightAlpha", alpha)
        shader.setColorUniform("strokeColor", Color.WHITE)
        shader.setFloatUniform("strokeAlphaMul", 0.12f)
        setBloomLight(shader, "1", rotation, tiltX)
        setBloomLightSecondary(shader, "2")
        bloomPaint.shader = shader
        bloomPaint.strokeWidth = density
        panelPath.reset()
        panelPath.addRoundRect(left, top, left + width, top + height, radius, radius, Path.Direction.CW)
        canvas.drawPath(panelPath, bloomPaint)
        bloomPaint.shader = null
    }

    private fun setBloomLight(shader: RuntimeShader, suffix: String, rotation: Float, tiltX: Float) {
        val lx0 = tiltX
        val ly0 = -1f
        val norm = sqrt(lx0 * lx0 + ly0 * ly0)
        val ux = lx0 / norm
        val uy = ly0 / norm
        val rad = Math.toRadians(rotation.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val dx = c * ux - s * uy
        val dy = s * ux + c * uy
        val dz = -0.05f
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        shader.setFloatUniform("lightDir" + suffix, dx / len, dy / len, dz / len)
        shader.setColorUniform("lightColor" + suffix, Color.WHITE)
        shader.setFloatUniform("lightIntensity" + suffix, 1f)
    }

    private fun setBloomLightSecondary(shader: RuntimeShader, suffix: String) {
        val dx = 0f
        val dy = 0.1f
        val dz = -0.5f
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        shader.setFloatUniform("lightDir" + suffix, dx / len, dy / len, dz / len)
        shader.setColorUniform("lightColor" + suffix, Color.WHITE)
        shader.setFloatUniform("lightIntensity" + suffix, 0.4f)
    }

    private fun withAlpha(color: Int, alpha: Float): Int = Color.argb(
        (alpha * 255f).toInt().coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )
}