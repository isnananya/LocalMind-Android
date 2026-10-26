package com.localmind.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 底栏背后的窗口截图。
 *
 * LiquidTab 用 RenderNode + RenderEffect 现模糊现取；在模拟器（SwiftShader）
 * 上 RuntimeShader 链会整层变透明，所以这里退一步：每次抓一帧**清晰**的
 * 窗口缩略图，再复制一份做 CPU 三次方框模糊（≈高斯）。
 *
 *  - [sharp] 给选中胶囊用：静止=清晰的透玻璃，按压时才叠色散透镜；
 *  - [blurred] 给底板和底部渐变模糊用。
 */
class Backdrop(
    val sharp: Bitmap,
    val blurred: Bitmap,
    val sample: Int,
)

object GlassBackdrop {

    /** 抓图缩放：3 => 1/9 像素。折射要看得见，背景不能糊成一团色块。 */
    const val SAMPLE = 3

    /** 采窗口缩略图时临时藏掉底栏，避免把上一帧的自己画进玻璃。 */
    @Volatile
    var capturing = false

    private const val MIN_INTERVAL_MS = 90L

    private var sharp: Bitmap? = null
    private var blurA: Bitmap? = null
    private var blurB: Bitmap? = null
    private var published: Bitmap? = null
    private var pixels: IntArray = IntArray(0)
    private var temp: IntArray = IntArray(0)
    private var lastCapture = 0L
    private var lastWidth = 0
    private var lastHeight = 0
    private var lastRadius = -1
    private var logged = false

    /**
     * @param blurRadiusPx 屏幕像素的模糊半径（会换算到缩略图尺度）
     */
    suspend fun capture(decor: View?, blurRadiusPx: Int, force: Boolean = false): Backdrop? {
        val view = decor ?: return null
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return null
        val radius = blurRadiusPx.coerceIn(0, 48)

        val now = System.currentTimeMillis()
        val cachedSharp = sharp
        val cachedBlurred = published
        if (!force && cachedSharp != null && cachedBlurred != null &&
            now - lastCapture < MIN_INTERVAL_MS &&
            lastWidth == w && lastHeight == h && lastRadius == radius &&
            !cachedSharp.isRecycled && !cachedBlurred.isRecycled
        ) {
            return Backdrop(cachedSharp, cachedBlurred, SAMPLE)
        }

        val bw = (w / SAMPLE).coerceAtLeast(1)
        val bh = (h / SAMPLE).coerceAtLeast(1)
        var s = sharp
        if (s == null || s.isRecycled || s.width != bw || s.height != bh) {
            s?.recycle()
            s = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            sharp = s
        }
        if (blurA == null || blurA!!.isRecycled || blurA!!.width != bw || blurA!!.height != bh) {
            blurA?.recycle()
            blurA = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            published = null
        }
        if (blurB == null || blurB!!.isRecycled || blurB!!.width != bw || blurB!!.height != bh) {
            blurB?.recycle()
            blurB = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            published = null
        }

        return try {
            val canvas = Canvas(s)
            canvas.drawColor(0xFF000000.toInt())
            val save = canvas.save()
            canvas.scale(1f / SAMPLE, 1f / SAMPLE)
            capturing = true
            try {
                view.draw(canvas)
            } finally {
                capturing = false
            }
            canvas.restoreToCount(save)

            // 清晰图 → 模糊图：写到「当前没在显示」的那块缓冲，再切换指针，
            // 这样 UI 线程正在画旧图时不会被踩。
            val target = if (published === blurA) blurB!! else blurA!!
            val r = (radius.toFloat() / SAMPLE).toInt().coerceIn(0, 30)
            withContext(Dispatchers.Default) {
                Canvas(target).drawBitmap(s, 0f, 0f, null)
                if (r > 0) stackBlur(target, r)
            }
            published = target

            if (!logged) {
                logged = true
                android.util.Log.i("GlassBackdrop", "capture ok " + bw + "x" + bh + " blur=" + r)
            }
            lastCapture = now
            lastWidth = w
            lastHeight = h
            lastRadius = radius
            Backdrop(s, target, SAMPLE)
        } catch (t: Throwable) {
            capturing = false
            android.util.Log.w("GlassBackdrop", "capture 失败：" + (t.message ?: t.javaClass.simpleName))
            null
        }
    }

    fun invalidate() {
        lastCapture = 0L
    }

    fun release() {
        sharp?.recycle()
        blurA?.recycle()
        blurB?.recycle()
        sharp = null
        blurA = null
        blurB = null
        published = null
        lastCapture = 0L
    }

    /** 三次方框模糊 ≈ 高斯。滑动窗口累加，代价与半径无关（StackBlur 结构）。 */
    fun stackBlur(bmp: Bitmap, radius: Int) {
        val w = bmp.width
        val h = bmp.height
        if (w <= 1 || h <= 1 || radius < 1) return
        if (pixels.size != w * h) pixels = IntArray(w * h)
        if (temp.size != w * h) temp = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val div = radius + radius + 1
        repeat(3) {
            boxBlurH(pixels, temp, w, h, radius, div)
            boxBlurV(temp, pixels, w, h, radius, div)
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    private fun boxBlurH(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int, div: Int) {
        val wm = w - 1
        for (y in 0 until h) {
            val row = y * w
            var a = 0; var r = 0; var g = 0; var b = 0
            for (i in -radius..radius) {
                val p = src[row + i.coerceIn(0, wm)]
                a += (p ushr 24) and 0xFF
                r += (p ushr 16) and 0xFF
                g += (p ushr 8) and 0xFF
                b += p and 0xFF
            }
            for (x in 0 until w) {
                dst[row + x] = ((a / div) shl 24) or ((r / div) shl 16) or ((g / div) shl 8) or (b / div)
                val pOut = src[row + (x - radius).coerceAtLeast(0)]
                val pIn = src[row + (x + radius + 1).coerceAtMost(wm)]
                a += ((pIn ushr 24) and 0xFF) - ((pOut ushr 24) and 0xFF)
                r += ((pIn ushr 16) and 0xFF) - ((pOut ushr 16) and 0xFF)
                g += ((pIn ushr 8) and 0xFF) - ((pOut ushr 8) and 0xFF)
                b += (pIn and 0xFF) - (pOut and 0xFF)
            }
        }
    }

    private fun boxBlurV(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int, div: Int) {
        val hm = h - 1
        for (x in 0 until w) {
            var a = 0; var r = 0; var g = 0; var b = 0
            for (i in -radius..radius) {
                val p = src[i.coerceIn(0, hm) * w + x]
                a += (p ushr 24) and 0xFF
                r += (p ushr 16) and 0xFF
                g += (p ushr 8) and 0xFF
                b += p and 0xFF
            }
            for (y in 0 until h) {
                dst[y * w + x] = ((a / div) shl 24) or ((r / div) shl 16) or ((g / div) shl 8) or (b / div)
                val pOut = src[(y - radius).coerceAtLeast(0) * w + x]
                val pIn = src[(y + radius + 1).coerceAtMost(hm) * w + x]
                a += ((pIn ushr 24) and 0xFF) - ((pOut ushr 24) and 0xFF)
                r += ((pIn ushr 16) and 0xFF) - ((pOut ushr 16) and 0xFF)
                g += ((pIn ushr 8) and 0xFF) - ((pOut ushr 8) and 0xFF)
                b += (pIn and 0xFF) - (pOut and 0xFF)
            }
        }
    }

    /** 从 Compose 的 View 往上找最外层的 decor（整窗截图，包含背景）。 */
    fun findDecor(view: View): View {
        var last: View = view
        var v: View? = view
        while (v != null) {
            last = v
            val p = v.parent
            v = if (p is View) p else null
        }
        return last
    }
}