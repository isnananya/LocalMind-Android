package com.example.liquiddock

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一帧底栏背景：[sharp] 原图，[blurred] 磨砂图，[sample] 是抓图时用的缩小倍数。 */
internal class DockFrame(
    val sharp: Bitmap,
    val blurred: Bitmap,
    val sample: Int,
)

/**
 * 抓「底栏背后那张窗口截图」，并做一次 CPU 模糊。
 *
 * ## 为什么要抓整窗
 *
 * 真玻璃必须知道自己身后有什么。Android 没有跨 View 的实时毛玻璃 API，所以
 * 这里的做法是每 [MIN_INTERVAL_MS] 以上抓一帧 decorView，缩到 1/[SAMPLE] 存起来：
 *
 *  - 抓图时把底栏自己藏掉（[capturing]），否则玻璃里会出现上一帧的自己，
 *    越叠越脏；
 *  - 模糊在后台线程用三次方框模糊（≈高斯）算，代价与半径无关；
 *  - 写「当前没在显示」的那块缓冲再切指针，避免 UI 线程正在画旧图时被踩。
 *
 * 缩到 1/3 是有原因的：折射要看得见边缘弯折，背景就不能糊成一团色块；
 * 但全分辨率又太重。
 */
internal object DockBackdrop {

    /** 抓图缩放：3 => 1/9 像素。 */
    const val SAMPLE = 3

    /** 抓图期间为 true：底栏把自己的绘制跳过，避免自拍。 */
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

    /**
     * @param blurRadiusPx 屏幕像素单位的模糊半径（内部会换算到缩略图尺度）
     * @param force 忽略缓存强制重抓（缩放/旋转后调用）
     */
    suspend fun capture(decor: View?, blurRadiusPx: Int, force: Boolean = false): DockFrame? {
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
            return DockFrame(cachedSharp, cachedBlurred, SAMPLE)
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
            // 藏起自己：这一帧里不能有底栏，否则玻璃会「镜中镜」。
            capturing = true
            try {
                view.draw(canvas)
            } finally {
                capturing = false
            }
            canvas.restoreToCount(save)

            val target = if (published === blurA) blurB!! else blurA!!
            val r = (radius.toFloat() / SAMPLE).toInt().coerceIn(0, 30)
            withContext(Dispatchers.Default) {
                Canvas(target).drawBitmap(s, 0f, 0f, null)
                if (r > 0) stackBlur(target, r)
            }
            published = target

            lastCapture = now
            lastWidth = w
            lastHeight = h
            lastRadius = radius
            DockFrame(s, target, SAMPLE)
        } catch (t: Throwable) {
            capturing = false
            android.util.Log.w("DockBackdrop", "capture failed: " + (t.message ?: t.javaClass.simpleName))
            null
        }
    }

    /** 强制下一帧重抓（例如页面切换、软键盘关闭后）。 */
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

    /** 从任意 View 往上找到最外层的 decor（整窗截图，包含背景）。 */
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
