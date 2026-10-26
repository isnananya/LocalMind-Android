package com.localmind.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup

/**
 * Backdrop capture + blur for the liquid-glass bar.
 *
 * Technique reimplemented (Kotlin/Compose, no code copied) after
 * [liuran001/WeChat-LiquidGlass](https://github.com/liuran001/WeChat-LiquidGlass):
 * the bar needs the pixels *behind* it to refract, and Compose's `Modifier.blur`
 * cannot blur what another composable painted. So we grab the window's decor view
 * once per N frames into a small bitmap and blur that.
 *
 * Two deliberate choices:
 *
 *  * **Down-sampled capture.** The decor view is drawn at 1/[SAMPLE] scale. A box
 *    blur costs O(pixels) and we only need a soft background, so capturing at 1/6
 *    and scaling back up is both much cheaper and visually indistinguishable
 *    after a 3-pass blur.
 *  * **Nobody is invalidated by this.** `View.draw(Canvas)` on a hardware canvas
 *    re-records display lists; we call it on a *software* canvas backed by our own
 *    bitmap, which is the documented way to snapshot a view tree off-screen.
 */
object GlassBackdrop {

    /** Capture scale. 6 => ~1/36 the pixels to blur. */
    private const val SAMPLE = 6

    private var bitmap: Bitmap? = null
    private var lastCapture = 0L
    private var lastWidth = 0
    private var lastHeight = 0
    private var loggedFailure = false
    private var loggedSuccess = false

    /** Minimum gap between captures; the bar re-reads the cached bitmap in between. */
    private const val MIN_INTERVAL_MS = 90L

    /**
     * Returns a blurred bitmap of what is currently on screen, or null when the
     * decor view is not ready yet.
     *
     * @param force ignore the frame throttle (used when the tab changes)
     */
    fun capture(decor: View?, force: Boolean = false): Bitmap? {
        if (decor == null) return null
        val w = decor.width
        val h = decor.height
        if (w <= 0 || h <= 0) return null

        val now = System.currentTimeMillis()
        val cached = bitmap
        if (!force && cached != null && now - lastCapture < MIN_INTERVAL_MS &&
            lastWidth == w && lastHeight == h && !cached.isRecycled
        ) {
            return cached
        }

        val bw = (w / SAMPLE).coerceAtLeast(1)
        val bh = (h / SAMPLE).coerceAtLeast(1)

        if (cached == null || cached.isRecycled || cached.width != bw || cached.height != bh) {
            cached?.recycle()
            bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        }
        val target = bitmap ?: return null

        return try {
            val canvas = Canvas(target)
            // Fill first: areas the view tree does not paint would otherwise stay
            // transparent, and transparency turns black once blurred.
            canvas.drawColor(0xFF000000.toInt())
            val save = canvas.save()
            canvas.scale(1f / SAMPLE, 1f / SAMPLE)
            decor.draw(canvas)
            canvas.restoreToCount(save)

            stackBlur(target, BLUR_RADIUS)
            if (!loggedSuccess) {
                loggedSuccess = true
                android.util.Log.i("GlassBackdrop", "capture ok " + target.width + "x" + target.height)
            }
            lastCapture = now
            lastWidth = w
            lastHeight = h
            target
        } catch (t: Throwable) {
            if (!loggedFailure) {
                loggedFailure = true
                android.util.Log.w("GlassBackdrop", "capture 失败：" + (t.message ?: t.javaClass.simpleName))
            }
            // A capture failure must never take the UI down; the bar falls back to
            // its translucent tint.
            null
        }
    }

    /** Radius in *captured* pixels (the bitmap is 1/SAMPLE of the screen). */
    private const val BLUR_RADIUS = 10

    fun invalidate() {
        lastCapture = 0L
    }

    fun release() {
        bitmap?.recycle()
        bitmap = null
        lastCapture = 0L
    }

    /**
     * Three box-blur passes ≈ Gaussian. Sliding-window sums, so cost is independent
     * of radius — this is the classic StackBlur structure.
     */
    fun stackBlur(bmp: Bitmap, radius: Int) {
        val w = bmp.width
        val h = bmp.height
        if (w <= 1 || h <= 1 || radius < 1) return

        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val temp = IntArray(w * h)
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

    /** Walks up from a Compose view to the window decor view. */
    fun findDecor(view: View?): View? {
        // 一直走到"父级不是 View"的那一层，最后一个 View 就是 DecorView。
        // 旧实现判断 parent.parent == null，在 Compose + ViewRootImpl 的结构下
        // 永远不成立（DecorView 的 parent 是 ViewRootImpl，不是 View），
        // 于是 capture 一直返回 null，底栏从来没有真正模糊过。
        var v: View? = view
        var last: View? = null
        while (v != null) {
            last = v
            val p = v.parent
            v = if (p is View) p else null
        }
        return last
    }

    @Suppress("unused")
    private val unusedRect = Rect()
}
