package com.localmind.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 聊天里用到的图片（用户附的 + AI 画的）的**持久化**。
 *
 * ## 为什么不能只用缓存目录
 *
 * 用户选的图原来是丢在 `cacheDir` 里的：系统随时可以清掉，而且用户"清缓存"
 * 之后**历史消息里的图就永久没了** —— 而消息本身还在，于是气泡里剩一块空白。
 * 对话是长期数据，附件必须和它同寿命。
 *
 * 所以复制到 `filesDir/chat_images/`，并且**落盘时就压到合理尺寸**：
 * 一张 4000×3000 的手机照片是 5 MB，而聊天里最大只画到屏宽。
 * 存原图的结果是几十条消息就把应用撑到几百 MB。
 *
 * ## 尺寸
 *
 * 长边压到 [MAX_DIMENSION]：足够在手机全屏查看时不糊，又比原图小一个量级。
 * 用 JPEG 而不是 PNG：照片用 PNG 是纯浪费（这个语料里没有需要无损的场景）。
 */
object ChatImageStore {

    private const val TAG = "ChatImageStore"

    /** 长边上限。1600 在 1080p 屏上全屏看仍然清楚，体积约 200–400 KB。 */
    private const val MAX_DIMENSION = 1600

    private const val QUALITY = 85

    /**
     * 附件目录。
     *
     * **不放进 `Storage` 的 `File` 系列**是因为它需要 `Context`，而 `Storage`
     * 那边是 lazy 计算 property；放这里让调用方（ViewModel）自己传 context，
     * 和 [com.localmind.ui.components.AvatarStore] 保持一致。
     */
    fun dir(context: Context): File = File(context.filesDir, "chat_images").apply { mkdirs() }

    /**
     * 把用户选的图存进附件目录，返回绝对路径；读不出来返回 null。
     *
     * 已经压过的图（长边 ≤ [MAX_DIMENSION]）只重新编码，不再二次缩放 ——
     * 反复缩放的图会越来越糊。
     */
    fun saveFromUri(context: Context, uri: Uri): String? {
        val bitmap = runCatching {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }.getOrNull() ?: return null

        val out = File(dir(context), "img_${System.currentTimeMillis()}.jpg")
        val ok = writeScaled(bitmap, out)
        if (!bitmap.isRecycled) bitmap.recycle()
        return if (ok) out.absolutePath else null
    }

    /**
     * 把 AI 生成的图**复制**进附件目录。
     *
     * 生成器写出来的文件可能在临时目录里，将来被覆盖或清掉；复制一份进
     * 附件目录之后，这条消息的图就和用户附的图一样可靠了。
     */
    fun adopt(context: Context, source: File): String? {
        if (!source.isFile) return null
        val bitmap = runCatching {
            BitmapFactory.decodeFile(source.absolutePath)
        }.getOrNull() ?: return null

        val out = File(dir(context), "gen_${System.currentTimeMillis()}.jpg")
        val ok = writeScaled(bitmap, out)
        if (!bitmap.isRecycled) bitmap.recycle()
        return if (ok) out.absolutePath else null
    }

    private fun writeScaled(bitmap: Bitmap, out: File): Boolean = runCatching {
        val long = maxOf(bitmap.width, bitmap.height)
        val scaled = if (long > MAX_DIMENSION) {
            val ratio = MAX_DIMENSION.toFloat() / long
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        FileOutputStream(out).use { scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
        true
    }.getOrElse {
        Log.w(TAG, "写图片失败：${it.message}")
        false
    }

    /** 这个路径是不是附件目录里的文件（决定要不要在删除消息时一起删）。 */
    fun owns(context: Context, path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return runCatching { File(path).canonicalPath.startsWith(dir(context).canonicalPath) }
            .getOrDefault(false)
    }

    /** 删一张附件。尽力而为，失败不抛。 */
    fun delete(context: Context, path: String?) {
        if (!owns(context, path)) return
        runCatching { File(path!!).delete() }
    }

    /**
     * 返回解码后的位图，**按 [maxWidth] 采样**。
     *
     * 为什么不用 `decodeFile` 直接拿：一张 1600×1200 的 JPEG 解码成
     * ARGB_8888 就是 7.7 MB，而聊天气泡里只需要 300 dp 宽。列表里几十张
     * 一起解码会直接 OOM —— 所以先读边界算采样率，再解码。
     */
    fun decodeScaled(path: String, maxWidth: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2

        BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }.getOrNull()
}
