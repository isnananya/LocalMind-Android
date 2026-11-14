package com.localmind.ui.theme

import android.content.Context
import android.net.Uri
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 运行时字体。
 *
 * ## 为什么要自己管
 *
 * `FontFamily.SansSerif` 这类只能用系统自带的四五个族，中文环境下
 * `cursive` 甚至直接回退成默认字体 —— 二次元主题最想要的那一步（换圆体/手写体）
 * 就完全落空。Compose 支持 `Font(File)`：把一个 TTF 文件变成 `FontFamily`，
 * 于是"下载一个字体就用上"成为可能，不用打包进 APK（省几十 MB、也不用随包授权）。
 *
 * ## 生命周期
 *
 *  - App 启动时 [init]：扫一遍 `filesDir/fonts/`，把已经下过的字体都加载好。
 *    这样换主题、重启都不需要重新解析文件。
 *  - 用户在字体商店点了某个字体：下载 → [register] → 把 id 写进 `fontId` 令牌。
 *  - 主题包带字体时：装主题的动作里同样走一遍下载 + register。
 *
 * 解析失败**不抛异常**：一个坏字体只应该让"换字体"无效，不该让应用起不来。
 * `Font(file)` 是惰性读文件的，真正出错可能发生在第一次绘制 —— 所以
 * 加载时顺手让 Typeface 立刻创建一次（[Font] 内部会 catch 吗？不会），
 * 这里用 try/catch 圈住整个构造过程。
 */
object FontRegistry {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _families = MutableStateFlow<Map<String, FontFamily>>(emptyMap())

    /** 已加载的字体：id → FontFamily。界面订阅它，加载完成后自动重绘。 */
    val families: StateFlow<Map<String, FontFamily>> = _families

    @Volatile
    private var appContext: Context? = null

    /** 字体文件目录。和主题包分开，字体可以被多个主题共用。 */
    fun fontsDir(context: Context): File = File(context.filesDir, "fonts").apply { mkdirs() }

    fun fontFile(context: Context, id: String): File {
        val safe = id.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(fontsDir(context), "$safe.ttf")
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        val ctx = appContext ?: return
        scope.launch {
            val files = fontsDir(ctx).listFiles().orEmpty().filter { it.isFile && it.length() > 1024 }
            for (f in files) {
                val id = f.nameWithoutExtension
                if (_families.value.containsKey(id)) continue
                load(ctx, id, f)
            }
        }
    }

    /** 当前已加载的字体族；没加载到返回 null。 */
    fun familyFor(id: String): FontFamily? =
        if (id.isBlank()) null else _families.value[id]

    /** 文件已经下过但还没加载 → 后台加载一次（界面组合时调，幂等）。 */
    fun ensureLoaded(id: String) {
        if (id.isBlank() || _families.value.containsKey(id)) return
        val ctx = appContext ?: return
        val f = fontFile(ctx, id)
        if (!f.isFile || f.length() <= 1024) return
        scope.launch { load(ctx, id, f) }
    }

    suspend fun register(context: Context, id: String, file: File): Boolean =
        load(context, id, file)

    private suspend fun load(context: Context, id: String, file: File): Boolean =
        withContext(Dispatchers.IO) {
            if (!file.isFile || file.length() <= 1024) return@withContext false
            runCatching {
                val family = FontFamily(
                    Font(file = file, weight = FontWeight.Normal, style = FontStyle.Normal),
                )
                // 触发一次真实解析：坏 TTF 在这里就会抛，而不是等到某一行文字要画的时候。
                android.graphics.Typeface.createFromFile(file)
                _families.update { it + (id to family) }
                true
            }.getOrElse { false }
        }

    /** 从系统文件选择器导入一个字体（用户自己收藏的 TTF/OTF）。 */
    suspend fun importFromUri(context: Context, uri: Uri, id: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val target = fontFile(context, id)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { out -> input.copyTo(out) }
                } ?: return@runCatching false
                if (target.length() <= 1024) {
                    target.delete()
                    return@runCatching false
                }
                load(context, id, target)
            }.getOrDefault(false)
        }

    fun delete(context: Context, id: String): Boolean {
        val ok = runCatching { fontFile(context, id).delete() }.getOrDefault(false)
        _families.update { it - id }
        return ok
    }

    /** 已下载到本机的字体 id。 */
    fun installedIds(context: Context): Set<String> =
        fontsDir(context).listFiles().orEmpty()
            .filter { it.isFile && it.length() > 1024 && it.name.endsWith(".ttf") }
            .map { it.nameWithoutExtension }
            .toSet()
}
