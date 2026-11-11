package com.example.liquiddock

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
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 液态玻璃的绘制内核：三个 AGSL 着色器 + 对应的四层画法。
 *
 * ## 视线里到底发生了什么
 *
 * 面板（底座）从下往上叠四层：
 *
 *  1. **背景**：一张磨砂的窗口截图，按窗口坐标贴进面板矩形里；
 *  2. **边缘折射**：只在离圆角边缘 [refractionHeight] 像素以内生效，把采样点
 *     沿 SDF 梯度往外推 [refractionAmount]，形成「玻璃有厚度」的弯折；
 *  3. **表面填充**：40% 的 surfaceContainer 色。它负责「有色玻璃」的观感，
 *     但透明度必须够低，否则磨砂和折射都被盖住；
 *  4. **双光源高光描边**：两盏点光源（一主一辅，位置随重力倾斜）在描边法线
 *     上做 dot² 衰减，描一圈 1dp 的光。
 *
 * 选中胶囊画在内容**之上**：
 *
 *  1. 先用和面板完全相同的磨砂底 + 40% 填充盖住这一格；
 *  2. 再把「底栏自己的内容截图」经过透镜着色器画上去 —— 于是被掰弯的是
 *     图标和文字，而不是底栏下面的页面（那样会看起来像底栏被挖了个洞）；
 *  3. 透镜带色散：七个通道按偏离量错位采样，按压时最明显；
 *  4. 静止叠 10% 白/黑，按压再叠 3% 黑，最后 PLUS 混合提亮。
 *
 * 需要 Android 13（API 33）才有 [RuntimeShader]；更低版本自动退回「贴图不折射」，
 * 不会崩、也不会空白。
 */
internal class LiquidDockGlass {

    /** 屏幕像素密度：所有「像素级」的厚度/描边都乘它，保证不同 DPI 观感一致。 */
    var density = 2.75f

    // ------------------------------------------------------------------ 着色器

    /** SDF 基础函数：圆角矩形距离场、梯度、圆形映射。三个着色器共用。 */
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

    /**
     * 面板折射：只在边缘带内偏移采样点。
     *
     * - `refractionHeight`：边缘带宽度（px）。带内才弯；
     * - `refractionAmount`：最大偏移（px），负值 = 往内缩，看起来像「凹面」；
     * - `depthEffect`：额外沿中心方向推一点，制造「球面」感（面板用 0）。
     */
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

    /**
     * 胶囊透镜：在面板折射之上加**色散**。
     *
     * 每通道按 `chromaticAberration * (x*y / halfSize²)` 的偏离量错位采样，
     * 四角最强、中心为零 —— 所以色散只在胶囊边缘的圆弧处闪出彩虹，
     * 正好对应真实凸透镜的色环。
     */
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

    /**
     * 双光源描边光晕：沿描边法线 (n) 与光源方向点乘，取平方做衰减。
     *
     * 光在描边上「扫」过去，而不是均匀一圈，所以玻璃看起来是有体积的；
     * 主光源方向由重力倾斜驱动，辅光源固定在下方偏前（0, 0.1, -0.5）。
     */
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
        .onFailure { android.util.Log.w("LiquidDockGlass", "panel shader: " + it.message) }
        .getOrNull()
    private val pillShader: RuntimeShader? = runCatching { RuntimeShader(pillAgs) }
        .onFailure { android.util.Log.w("LiquidDockGlass", "pill shader: " + it.message) }
        .getOrNull()
    private val bloomShader: RuntimeShader? = runCatching { RuntimeShader(bloomAgs) }
        .onFailure { android.util.Log.w("LiquidDockGlass", "bloom shader: " + it.message) }
        .getOrNull()

    // ------------------------------------------------------------------ 画笔

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

    /** API 33+ 才有 RuntimeShader；更低的系统一律走「贴图」降级。 */
    private val canShader: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** 把「整窗截图 + 窗口原点」包成画布坐标可用的 BitmapShader（带一层缓存）。 */
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

    // ------------------------------------------------------------------ 面板

    /**
     * 面板：模糊背景 → 边缘折射 → 填充 → 描边。
     *
     * 调用方必须先把画布坐标原点对到面板左上角（[winLeft]/[winTop] 用于把
     * 窗口截图对齐过来）。
     */
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
            // 24dp 的弯折带 + 24dp 的偏移量：边缘像 3~4mm 厚的玻璃。
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
            // 没有 AGSL：一条 1dp 的静态描边，浅色 24% 黑 / 深色 32% 白。
            plainStroke.strokeWidth = 1f * density
            plainStroke.color = if (dark) {
                Color.argb((0.32f * 255f).toInt(), 255, 255, 255)
            } else {
                Color.argb((0.24f * 255f).toInt(), 0, 0, 0)
            }
            canvas.drawPath(panelPath, plainStroke)
        }
    }

    // ------------------------------------------------------------------ 胶囊

    /**
     * 选中胶囊：整槽宽的圆角胶囊，折射的是**底栏自己的内容截图**。
     *
     * 画的顺序很关键（也是原实现踩过的坑）：
     *
     *  1. 磨砂底 + 填充：和面板完全一致，胶囊不再「透出页面」；
     *  2. 内容透镜：静止 lensProgress = 0.16（几乎只是轻微弯折），按住拉到 1.0；
     *  3. 静止叠 10% 白/黑，按压再叠 3% 黑；
     *  4. PLUS 混合提亮（按压时最多 +2.9%）；
     *  5. 按压时才亮描边光晕。
     *
     * @param base 底栏的磨砂背景（和面板同一张），用来铺胶囊底色
     * @param content 底栏内容截图，原点在底栏左上角（[contentLeft]/[contentTop]）
     * @param press 0..1 的按压进度，来自弹簧
     */
    fun drawPill(
        canvas: Canvas,
        base: Bitmap?,
        content: Bitmap?,
        sample: Int,
        winLeft: Float,
        winTop: Float,
        contentLeft: Float,
        contentTop: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        dark: Boolean,
        liquid: Boolean,
        press: Float,
        tiltX: Float,
        fillColor: Int = 0xFFFFFFFF.toInt(),
        fillAlpha: Float = 0.40f,
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
        if (base != null) {
            drawBackdrop(canvas, base, sample, winLeft, winTop)
            fillPaint.color = Color.argb(
                (fillAlpha * 255f).toInt(),
                Color.red(fillColor),
                Color.green(fillColor),
                Color.blue(fillColor),
            )
            canvas.drawRect(left, top, right, bottom, fillPaint)
        }
        val pressing = p > 0.12f
        if (content != null) {
            val lensP = if (liquid) (if (pressing) (0.16f + 0.84f * p) else 0.16f).coerceIn(0f, 1f) else 0f
            if (lensP > 0.001f && canShader && pillShader != null) {
                val bitmapShader = BitmapShader(content, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                matrix.setScale(1f, 1f)
                matrix.postTranslate(-contentLeft, -contentTop)
                bitmapShader.setLocalMatrix(matrix)
                pillShader.setInputShader("content", bitmapShader)
                shaderSetupPill(pillShader, w, h, left, top, radius, lensP)
                lensPaint.shader = pillShader
                canvas.drawRect(left - radius, top - radius, right + radius, bottom + radius, lensPaint)
                lensPaint.shader = null
            } else {
                // 没有 AGSL：原样铺内容，至少胶囊里还有图标。
                canvas.drawBitmap(content, contentLeft, contentTop, null)
            }
        }

        // 静止 10% 白/黑；按压再叠 3% 黑。
        val idle = 0.10f * (1f - p)
        val tone = if (dark) Color.WHITE else Color.BLACK
        fillPaint.color = Color.argb(
            (idle * 255f).toInt().coerceIn(0, 255),
            Color.red(tone), Color.green(tone), Color.blue(tone),
        )
        canvas.drawPath(pillPath, fillPaint)
        val pressed = 0.03f * p
        if (pressed > 0f) {
            fillPaint.color = Color.argb((pressed * 255f).toInt().coerceIn(0, 255), 0, 0, 0)
            canvas.drawPath(pillPath, fillPaint)
        }
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

    private fun shaderSetupPill(
        shader: RuntimeShader,
        w: Float,
        h: Float,
        left: Float,
        top: Float,
        radius: Float,
        lensP: Float,
    ) {
        shader.setFloatUniform("size", w, h)
        shader.setFloatUniform("offset", -left, -top)
        shader.setFloatUniform("cornerRadii", radius, radius, radius, radius)
        shader.setFloatUniform("refractionHeight", 10f * density * lensP)
        shader.setFloatUniform("refractionAmount", -14f * density * lensP)
        shader.setFloatUniform("depthEffect", 1f)
        shader.setFloatUniform("chromaticAberration", 0.5f)
    }

    /** 底部渐变模糊：把磨砂图铺在底栏上方（竖直遮罩由调用方做）。 */
    fun drawBlurredVeil(
        canvas: Canvas,
        blurred: Bitmap,
        sample: Int,
        winLeft: Float,
        winTop: Float,
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

    /** 主光源：方向 = (tiltX, -1) 绕面板中心转 [rotation] 度，再压一点 z。 */
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

    /** 辅光源：固定在下方偏前，只负责补一点下沿的亮。 */
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
