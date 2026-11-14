package com.localmind.service

import android.content.Context
import android.view.View
import com.localmind.plugin.PluginContext
import com.localmind.plugin.PluginEntry
import dalvik.system.DexClassLoader
import java.io.File

/**
 * 代码插件的加载器（classes.dex -> DexClassLoader）。
 *
 * 隔离与收敛：
 *  * 每个插件一个 DexClassLoader，parent 是主程序（插件才能看到 PluginEntry）；
 *  * dex 必须是只读文件（Android 14+ 拒绝从可写 dex 加载）；
 *  * 加载/执行异常只返回 null 或失败 Result，主程序不受影响。
 */
object PluginRuntime {

    data class Loaded(
        val pluginId: String,
        val entry: PluginEntry,
        val actions: List<Pair<String, String>>,
        val logs: List<String>,
        val loader: DexClassLoader,
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
        Loaded(pluginId, entry, actions.distinctBy { it.first }.take(8), logs, loader).also {
            cache[pluginId] = it
        }
    }

    fun runAction(pluginId: String, actionId: String, input: String): String? =
        runCatching { cache[pluginId]?.entry?.onAction(actionId, input) }.getOrNull()

    /**
     * 用插件的 dex 创建一个 Android View（整页注入）。
     *
     * 约定：manifest.config.viewClass 指定类名；类继承 android.view.View，
     * 并有一个 (Context) 构造器。失败返回 null，由界面显示错误。
     */
    fun createView(
        pluginId: String,
        dex: File,
        viewClass: String,
        optimizedDir: File,
        context: Context,
    ): View? = runCatching {
        require(dex.isFile) { "classes.dex 不存在" }
        require(viewClass.isNotBlank()) { "plugin.json 没有 config.viewClass" }
        dex.setReadOnly()
        optimizedDir.mkdirs()
        val loader = DexClassLoader(
            dex.absolutePath,
            optimizedDir.absolutePath,
            null,
            PluginEntry::class.java.classLoader,
        )
        val cls = loader.loadClass(viewClass)
        val ctor = cls.getDeclaredConstructor(Context::class.java)
        val view = ctor.newInstance(context) as? View
            ?: error(viewClass + " 不是 android.view.View")
        view
    }.getOrElse { e ->
        android.util.Log.w("PluginRuntime", "创建 View 失败 " + pluginId + ": " + (e.message ?: e.javaClass.simpleName))
        null
    }
}
