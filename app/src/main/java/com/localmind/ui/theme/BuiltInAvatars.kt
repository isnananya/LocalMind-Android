package com.localmind.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * 内置头像。
 *
 * ## 为什么是 base64 而不是放进 `res/drawable`
 *
 * 这两个做法都能用，选 base64 是因为**改头像不用碰资源目录**：
 * 作者/捐赠者的头像换一张，只要把新的 base64 贴进这个文件就行，
 * 不需要管 drawable 的命名、密度目录（`drawable-xxhdpi` 那一套）、
 * 也不用担心 aapt 的压缩策略。
 *
 * 代价是它比 drawable 大（base64 比原始字节大 1/3），所以**先在开发机上把图
 * 缩到 256×256 再转码** —— 这里三张加起来约 25 KB，可以忽略。
 *
 * ## 为什么运行时解码而不是编译期生成 Bitmap
 *
 * 编译期没有 Android 的 `BitmapFactory`。所以这里是"启动后第一次用到才解码"，
 * 解完缓存住（见 [BuiltInAvatars.bitmap]）—— 关于页可能被反复打开，
 * 每次都解码 3 张图是没必要的。
 */
object BuiltInAvatars {

    /** 作者「难安」。256×256，来自作者提供原图。 */
    val AUTHOR =
        "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAQDAwQDAwQEAwQFBAQFBgoHBgYGBg0JCggKDw0QEA8NDw4RExgUERIXEg4PFRwV" +
        "FxkZGxsbEBQdHx0aHxgaGxr/2wBDAQQFBQYFBgwHBwwaEQ8RGhoaGhoaGhoaGhoaGhoaGhoaGhoaGhoaGhoaGhoaGhoaGhoa" +
        "GhoaGhoaGhoaGhoaGhr/wAARCAEAAQADASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAA" +
        "AgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6" +
        "Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXG" +
        "x8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREA" +
        "AgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5" +
        "OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPE" +
        "xcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD73J6dOg7UZ9h+VB7fQUlAC59h+VGfYflS" +
        "UUALn2H5UZ9h+VJRQAufYflRn2H5UlFAC59h+VGfYflSUUALn2H5UZ9h+VJRQAufYflRn2H5UlFAC59h+VGfYflSUUALn2H5" +
        "UZ9h+VJRQAufYflRn2H5UlFAC59h+VGfYflSUUALn2H5UZ9h+VJRQAufYflRn2H5UlFAC59h+VGfYflSUUALn2H5UZ9h+VJR" +
        "QAufYflQD16dD2pKUd/oaAA9voKSlPb6CkoAKKKKACiiigAooooAKKKKACiiigAoriPGHxi8CeApvs/ivxPYWF30+zBzLMPr" +
        "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"

    /** 捐赠者「韪嘆」。88×88，捐赠者提供。 */
    val DONOR_WEITAN =
        "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"

    /** 捐赠者「沉璧」。88×88，捐赠者提供。 */
    val DONOR_CHENBI =
        "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" +
        "mm8ruRSrMWppK0PbK0tvuGlCEI9hXVH/2Q=="

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Bitmap?>()

    /**
     * 解出一张内置头像。
     *
     * 返回 `null` 表示这份 base64 坏了（理论上不会，但解码失败不该让关于页崩）——
     * 调用方回退到图标占位。
     */
    fun bitmap(base64: String): Bitmap? = cache.getOrPut(base64) {
        runCatching {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    /** 字节数，用来在界面上说明"这些图占了多大"（诊断用）。 */
    fun rawBytes(base64: String): Int =
        runCatching { Base64.decode(base64, Base64.DEFAULT).size }.getOrDefault(0)

    /** 把一段 bitmap 重新编码回 PNG 的 base64。给"导出内置头像"这类调试用途。 */
    fun encodePng(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
