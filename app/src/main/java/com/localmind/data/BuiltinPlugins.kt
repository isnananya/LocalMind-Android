package com.localmind.data

import android.content.Context
import com.localmind.ui.PluginInstall
import java.io.File

/**
 * APK **内置插件**：模型库、液态玻璃底栏、本地生图后端。
 *
 * ## 为什么改了主意，把它们塞回 APK
 *
 * 之前的原则是"按需下载，不让所有人替少数人付体积"。这一版用户明确要求
 * **开箱即用**：模型页不该是空的，外观里不该先教用户去导 zip，生图不该
 * 先让用户下 30 MB 的后端。代价是 APK 大约多 31 MB —— 这是一个产品取舍，
 * 不是疏忽。
 *
 * ## 内置 ≠ 强塞
 *
 *  * 用户卸载之后**不会**在下次启动被偷偷装回来：卸载会写一个 `removed` 标记。
 *  * 插件页仍然把它们列在「内置」一栏，可以手动重装。
 *  * 安装走的是和导入 zip **完全相同**的 [PluginInstall]，没有第二条特权的路径。
 *
 * ## 许可
 *
 *  * 官方模型库 / 液态玻璃底栏：LocalMind 自己的数据包（MIT）。
 *  * 本地生图后端：stable-diffusion.cpp（MIT），见插件 README。
 */
object BuiltinPlugins {

    data class Item(
        /** 稳定的键，用于 SharedPreferences 与界面。 */
        val key: String,
        /** assets 下的路径。 */
        val asset: String,
        /** 安装后的 plugin id。 */
        val id: String,
        val name: String,
        val version: String,
        val note: String,
        /** 首次启动是否自动安装。 */
        val auto: Boolean = true,
    )

    val ALL: List<Item> = listOf(
        Item(
            key = "model-library",
            asset = "builtin/official-model-library.zip",
            id = "localmind.official.models",
            name = "官方模型库",
            version = "3.0.1",
            note = "83 个对话 / 识图 / 生图 / 决策模型 + 17 个离线音色",
        ),
        Item(
            key = "sd-backend",
            asset = "builtin/sd-backend.zip",
            id = "dev.localmind.backend.sd",
            name = "本地生图后端",
            version = "1.1.1",
            note = "stable-diffusion.cpp（MIT）· 约 37 MB / ABI，装完还要一个模型",
        ),
        Item(
            key = "breeze-tts",
            asset = "builtin/breeze-tts2.zip",
            id = "com.breezeblue.tts2",
            name = "Breeze TTS 2 连接配置",
            version = "1.0.0",
            note = "只带端点/密钥/模型名的配置；模型跑在 PC 或 GPU 服务器上",
        ),
    )

    private const val PREFS = "localmind-builtin-plugins"

    fun itemForId(id: String): Item? = ALL.firstOrNull { it.id == id }

    fun isBuiltin(id: String): Boolean = itemForId(id) != null

    /** 用户卸载过：不要再自动装回来。 */
    fun isRemoved(context: Context, id: String): Boolean =
        prefs(context).getBoolean("removed_" + id, false)

    fun markRemoved(context: Context, id: String) {
        prefs(context).edit().putBoolean("removed_" + id, true).apply()
    }

    fun clearRemoved(context: Context, id: String) {
        prefs(context).edit().remove("removed_" + id).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 已安装的版本；没装返回 null。 */
    fun installedVersion(storage: Storage, item: Item): String? =
        storage.loadPlugins()[item.id]?.manifest?.version

    fun isInstalled(storage: Storage, item: Item): Boolean =
        installedVersion(storage, item) != null

    /**
     * 装一个内置项。返回一句给人看的结果；已经是最新时返回 null。
     */
    fun install(context: Context, storage: Storage, item: Item, force: Boolean = false): String? {
        val current = installedVersion(storage, item)
        if (!force && current == item.version) return null

        val tmp = File(context.cacheDir, "builtin-" + item.key + ".zip")
        return runCatching {
            context.assets.open(item.asset).use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            }
            when (val outcome = PluginInstall.install(storage, tmp)) {
                is PluginInstall.Outcome.Failed -> "内置插件「" + item.name + "」安装失败：" + outcome.reason
                is PluginInstall.Outcome.Ok -> {
                    clearRemoved(context, item.id)
                    "已内置：" + item.name + "（" + item.version + "）"
                }
            }
        }.getOrElse { t ->
            "内置插件「" + item.name + "」安装失败：" + (t.message?.take(100) ?: t.javaClass.simpleName)
        }.also {
            tmp.delete()
        }
    }

    /**
     * 首次启动的自动安装。只装没装过、且用户没卸载过的。
     *
     * 调用方必须放在**后台线程**：sd 后端要解 30 MB zip、校验并加载 37 MB .so。
     */
    fun installMissing(context: Context, storage: Storage): List<String> {
        val out = mutableListOf<String>()
        for (item in ALL) {
            if (!item.auto) continue
            if (isRemoved(context, item.id)) continue
            val current = installedVersion(storage, item)
            if (current == item.version) continue
            // ⚠️ 原生后端**不能热更新**。
            //
            // 旧版 .so 一旦被 System.load 映射进进程，就没有"卸载"这回事；
            // 再用新文件覆盖同一路径，正在映射的页会变成 SIGBUS/SIGSEGV。
            // 这不是理论风险：1.0.5 的模拟器回归里，旧版 1.1.0 已加载、
            // 新版 1.1.1 自动覆盖，进程在 DefaultDispatcher 上直接段错误。
            // 所以原生后端只在**没装过**时自动安装；升级请走"重启后再装"。
            if (item.key == "sd-backend" && current != null) continue
            install(context, storage, item)?.let { out += it }
        }
        return out
    }
}
