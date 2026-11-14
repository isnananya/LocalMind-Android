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
            key = "breeze-tts",
            asset = "builtin/breeze-tts2.zip",
            id = "com.breezeblue.tts2",
            name = "Breeze TTS 2 连接配置",
            version = "1.0.0",
            note = "只带端点/密钥/模型名的配置；模型跑在 PC 或 GPU 服务器上",
        ),
    )

    /**
     * 曾经以插件形式分发、现在直接长在应用里的 id。
     *
     * `dev.localmind.backend.sd` 是本地生图后端：1.0.6 起 `liblocalmind-sd.so`
     * 直接打进 `jniLibs/`，不再需要一个 31 MB 的 zip 在 assets 里陪着
     * （两份加起来 APK 里白占 30 MB，运行时还可能被 linker 当成两个库，
     * 见 [com.localmind.service.NativeLibraryInstaller.dropBundledDuplicate]）。
     *
     * 但**老用户手机里的插件记录还在**，如果 `isBuiltin` 不认它，插件页就会
     * 冒出一条带「卸载」按钮的陌生条目。所以这里只把它从安装清单里摘掉，
     * 仍然算「内置」。
     */
    private val LEGACY_IDS = setOf("dev.localmind.backend.sd")

    private const val PREFS = "localmind-builtin-plugins"

    fun itemForId(id: String): Item? = ALL.firstOrNull { it.id == id }

    fun isBuiltin(id: String): Boolean = itemForId(id) != null || id in LEGACY_IDS

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
            install(context, storage, item)?.let { out += it }
        }
        return out
    }
}
