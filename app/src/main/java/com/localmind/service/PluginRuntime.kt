package com.localmind.service

import com.localmind.plugin.PluginContext
import com.localmind.plugin.PluginEntry
import dalvik.system.DexClassLoader
import java.io.File

/**
 * 代码插件的加载器。
 *
 * 隔离边界：
 *  * 每个插件一个 DexClassLoader，parent 是主程序的 ClassLoader（这样插件才能
 *    看到 [PluginEntry] 接口）；
 *  * 优化目录放在 codeCacheDir/plugin-<id>；
 *  * 任何异常（找不到类、构造函数不是无参、接口不匹配、插件自己抛）都被收敛成
 *    一句错误信息，插件状态置 FAILED，主程序照常运行。
 */
object PluginRuntime {

    data class Loaded(
        val pluginId: String,
        val entry: PluginEntry,
        val actions: List<Pair<String, String>>,
        val logs: List<String>,
    )

    private val cache = mutableMapOf<String, Loaded>()

    fun get(pluginId: String): Loaded? = cache[pluginId]

    fun actions(): List<Triple<String, String, String>> =
        cache.values.flatMap { l -> l.actions.map { Triple(l.pluginId, it.first, it.second) } }

    fun clear() = cache.clear()

    fun load(
        pluginId: String,
        dex: File,
        entryClass: String,
        optimizedDir: File,
        onLog: (String) -> Unit = {},
    ): Result<Loaded> = runCatching {
        require(dex.isFile) { "classes.dex 不存在" }
        require(entryClass.isNotBlank()) { "plugin.json 没有 config.entryClass" }
        // Android 14+ 拒绝从**可写**的 dex 加载代码（安全策略）。安装时 / 加载前
        // 都设成只读；setReadOnly 对已只读文件返回 false，不影响后续流程。
        dex.setReadOnly()
        optimizedDir.mkdirs()
        val loader = DexClassLoader(
            dex.absolutePath,
            optimizedDir.absolutePath,
            null,
            PluginEntry::class.java.classLoader,
        )
        val cls = loader.loadClass(entryClass)
        val instance = cls.getDeclaredConstructor().newInstance()
        val entry = instance as? PluginEntry
            ?: error(entryClass + " 没有实现 com.localmind.plugin.PluginEntry")

        val actions = mutableListOf<Pair<String, String>>()
        val logs = mutableListOf<String>()
        val ctx = object : PluginContext {
            override fun log(message: String) {
                logs += message
                onLog(message)
            }

            override fun toast(message: String) {
                logs += "toast: " + message
            }

            override fun registerAction(id: String, label: String) {
                if (id.isNotBlank() && label.isNotBlank()) actions += (id to label)
            }
        }
        entry.onLoad(ctx)
        Loaded(pluginId, entry, actions.distinctBy { it.first }.take(8), logs).also {
            cache[pluginId] = it
        }
    }

    fun runAction(pluginId: String, actionId: String, input: String): String? =
        runCatching { cache[pluginId]?.entry?.onAction(actionId, input) }.getOrNull()
}
