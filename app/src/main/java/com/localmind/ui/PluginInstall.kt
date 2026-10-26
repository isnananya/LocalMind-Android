package com.localmind.ui

import com.localmind.data.InstalledPlugin
import com.localmind.data.ModelPackFile
import com.localmind.data.ModelPacks
import com.localmind.data.PluginAbilities
import com.localmind.data.PluginArchive
import com.localmind.data.PluginManifest
import com.localmind.data.PluginManifests
import com.localmind.data.PluginState
import com.localmind.data.Storage
import com.localmind.data.StickerPack
import com.localmind.data.StickerPacks
import java.io.File

/**
 * 安装一个插件 zip 的**全部逻辑**，不依赖 ViewModel。
 *
 * ## 用户只做一个动作
 *
 * 界面上只有"选一个 zip"。包是什么类型由程序判断：
 *  1. 有 `plugin.json` → 按它声明的 `abilities` 去注册表查需要读哪些文件
 *  2. 没有清单 → 看包里有哪些已知内容文件，反推出能力
 *
 * 用户不需要先判断"我这是表情包还是模型库"。把程序的活推给人是坏设计。
 *
 * ## 为什么抽成独立对象
 *
 * 两条路径都要装插件：界面（文件选择器）和诊断端点（`POST /v1/debug/plugin`，
 * 用于脚本化验证插件格式）。逻辑留两份必然分叉 —— 一个 bug 只会在一半的入口被修掉。
 */
object PluginInstall {

    data class Result(
        val manifest: PluginManifest,
        val stickerPack: StickerPack? = null,
        val modelPack: ModelPackFile? = null,
        val voicePack: com.localmind.data.VoicePackFile? = null,
        val uiTheme: com.localmind.data.UiTheme? = null,
        /** 语音引擎包（tts.json）：Breeze TTS 2 这类外部合成服务。 */
        val ttsPack: com.localmind.data.TtsPackFile? = null,
        /** 界面布局包（layout.json）：导航 + 首页结构 + 形态。 */
        val uiLayout: com.localmind.data.UiLayout? = null,
        /** 分步流水线包（pipeline.json）：拆解 → 逐步执行 → 聚合。 */
        val pipelinePack: com.localmind.data.PipelinePackFile? = null,
        /** 给用户看的一句话，已含各类内容摘要。 */
        val message: String,
        /** 成功解析出的内容摘要。 */
        val report: List<String> = emptyList(),
    )

    sealed interface Outcome {
        data class Ok(val result: Result) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    fun install(storage: Storage, file: File): Outcome = try {
        installUnsafe(storage, file)
    } catch (t: Throwable) {
        // 兜底：插件是**外部输入**，解析它的任何一步都可能抛出没预料到的东西
        // （畸形 zip 触发的 ZipException、超大字段导致的 OOM…）。
        // 装插件失败应该是"弹一句错误"，绝不该把应用带走。
        //
        // 接 Throwable 而不是 Exception：OOM / StackOverflow 也是 Error 的子类，
        // 而它们恰恰是畸形压缩包最容易触发的两种。
        android.util.Log.e("PluginInstall", "安装插件失败：${file.name}", t)
        Outcome.Failed("安装失败：${t.javaClass.simpleName} ${t.message?.take(80) ?: ""}".trim())
    }

    private fun installUnsafe(storage: Storage, file: File): Outcome {
        // ---- 第一步：先读清单，才能知道要解压哪些资源到哪里 ----
        val probe = PluginArchive.read(file, assetDir = null).getOrElse {
            return Outcome.Failed("读取压缩包失败：${it.message?.take(90)}")
        }
        val manifestFromFile = probe.manifestText
            ?.let { PluginManifests.parse(it) }
            ?.let { if (it is PluginManifests.ParseResult.Ok) it.manifest else null }

        // 没清单就先猜 id（资源目录要用它），有清单就用清单的 id。
        val probeId = manifestFromFile?.id
            ?: probe.files["emoji.json"]?.let { extractPackId(it) }
            ?: probe.files["models.json"]?.let { extractPackId(it) }
            ?: file.nameWithoutExtension

        // ---- 第二步：带资源目录 + **原生库目录**正式读一遍 ----
        //
        // libDir 必须给。不给的话 zip 里的 lib/*.so 会被**静默跳过**，于是
        // 「生图后端」插件"装成功但什么都没发生" —— 这正是它的历史 bug。
        // 落点只能是 filesDir/native/<abi>/：只有那下面的 .so 能被 System.load。
        val abi = com.localmind.service.NativeLibraryInstaller.currentAbi()
        val assetsDir = File(File(storage.pluginsDir, probeId), "assets")
        val libDir = File(storage.nativeDir, abi)
        // libAbi 必须一起传：通用包里有 lib/arm64-v8a 和 lib/x86_64 两份同名 .so，
        // 不按本机 ABI 选就会后一份覆盖前一份，最后校验失败、后端消失。
        val contents = PluginArchive.read(file, assetsDir, libDir, abi).getOrElse {
            return Outcome.Failed("展开压缩包失败：${it.message?.take(90)}")
        }
        if (contents.isEmpty) {
            return Outcome.Failed("压缩包里没有可识别的内容（plugin.json / emoji.json / models.json / assets）")
        }

        val report = mutableListOf<String>()

        // 通用包不是错误：装的时候只取本机那一份，其余架构如实说明。
        if (contents.otherAbis.isNotEmpty()) {
            report += "通用包，已选 $abi（包内还有 " +
                contents.otherAbis.sorted().joinToString("、") + "）"
        }

        // ---- 原生库：先校验 ELF 头（架构对不对），再谈加载 ----
        //
        // 用户在设置里填的地址可能是另一个 ABI 的包，或者干脆是别的文件。
        // 那种情况 System.load 只会回一句 "is 64-bit instead of 32-bit"，
        // 而 36 MB 已经白下了 —— 所以校验放在落库这一步。
        contents.libs.forEach { (name, f) ->
            val why = com.localmind.service.NativeLibraryInstaller.validate(f, abi)
            if (why != null) {
                f.delete()
                report += "原生库 $name 已丢弃：$why"
            } else {
                report += "原生库 $name（${f.length() / 1024 / 1024} MB）"
            }
        }
        // 包里有别的架构、但没有本机这一份 —— 不能装作装好了。
        if (contents.libs.isEmpty() && contents.otherAbis.isNotEmpty()) {
            report += "这个包里没有 $abi 的原生库，生图后端未安装"
        }

        // 放对位置只是第一步：不 System.load 进来，"有没有生图后端"永远回答没有。
        if (contents.libs.isNotEmpty()) {
            report += com.localmind.service.NativeLibraryInstaller.activateInstalled(storage)
        }

        var stickerPack: StickerPack? = null
        contents.text("emoji.json")?.let { text ->
            when (val r = StickerPacks.parse(text)) {
                is StickerPacks.ParseResult.Failed -> report += "表情包：${r.reason}"
                is StickerPacks.ParseResult.Ok -> {
                    stickerPack = r.pack
                    val imgs = r.pack.stickers.count { it.image.isNotBlank() }
                    report += "表情包 ${r.pack.packName}（${r.pack.stickers.size} 个" +
                        if (imgs > 0) "，其中 $imgs 张图片）" else "）"
                }
            }
        }

        var modelPack: ModelPackFile? = null
        contents.text("models.json")?.let { text ->
            ModelPacks.parse(text)
                .onSuccess {
                    modelPack = it
                    report += "模型库 ${it.packName}（${it.models.size} 个模型）"
                }
                .onFailure { report += "模型库：${it.message?.take(90)}" }
        }

        var voicePack: com.localmind.data.VoicePackFile? = null
        contents.text("voices.json")?.let { text ->
            when (val r = com.localmind.data.VoicePacks.parse(text)) {
                is com.localmind.data.VoicePacks.ParseResult.Failed -> report += "语音音色：${r.reason}"
                is com.localmind.data.VoicePacks.ParseResult.Ok -> {
                    voicePack = r.pack
                    report += "语音音色 ${r.pack.packName}（${r.pack.voices.size} 个）"
                    if (r.droppedCloud > 0) {
                        report += "跳过了 ${r.droppedCloud} 个云端音色（云端合成已移除，只支持本地音色）"
                    }
                }
            }
        }

        var uiTheme: com.localmind.data.UiTheme? = null
        contents.text("ui.json")?.let { text ->
            when (val r = com.localmind.data.UiThemes.parse(text)) {
                is com.localmind.data.UiThemes.ParseResult.Failed -> report += "界面主题：${r.reason}"
                is com.localmind.data.UiThemes.ParseResult.Ok -> {
                    uiTheme = r.theme
                    val unknown = com.localmind.data.UiThemes.unknownKeys(r.theme)
                    report += "界面主题 ${r.theme.packName}" +
                        if (unknown.isEmpty()) "" else "（忽略了不认识的令牌：${unknown.joinToString("、")}）"
                }
            }
        }

        var ttsPack: com.localmind.data.TtsPackFile? = null
        contents.text("tts.json")?.let { text ->
            when (val r = com.localmind.data.TtsPacks.parse(text)) {
                is com.localmind.data.TtsPacks.ParseResult.Failed -> report += "语音引擎：${r.reason}"
                is com.localmind.data.TtsPacks.ParseResult.Ok -> {
                    ttsPack = r.pack
                    val n = r.pack.engines.size
                    report += "语音引擎 ${r.pack.packName}（$n 个）"
                    com.localmind.data.TtsPacks.warnings(r.pack).forEach { report += it }
                }
            }
        }

        var uiLayout: com.localmind.data.UiLayout? = null
        contents.text("layout.json")?.let { text ->
            when (val r = com.localmind.data.Layouts.parse(text)) {
                is com.localmind.data.Layouts.ParseResult.Failed -> report += "界面布局：${r.reason}"
                is com.localmind.data.Layouts.ParseResult.Ok -> {
                    uiLayout = r.layout
                    val tabs = r.layout.tabs.size
                    val blocks = r.layout.home.size
                    report += "界面布局 ${r.layout.packName}（$tabs 个入口 / $blocks 块首页）" +
                        if (r.layout.tokens.isEmpty() && r.layout.darkTokens.isEmpty()) "" else " + 外观令牌"
                    com.localmind.data.Layouts.tolerated(r.layout).forEach { report += it }
                }
            }
        }

        var chatActionPack: com.localmind.data.ChatActionPack? = null
        contents.text("actions.json")?.let { text ->
            when (val r = com.localmind.data.ChatActions.parse(text)) {
                is com.localmind.data.ChatActions.ParseResult.Failed -> report += "聊天动作：" + r.reason
                is com.localmind.data.ChatActions.ParseResult.Ok -> {
                    chatActionPack = r.pack
                    report += "聊天动作 " + r.pack.packName + "（" + r.pack.actions.size + " 个）"
                }
            }
        }

        var pipelinePack: com.localmind.data.PipelinePackFile? = null
        contents.text("pipeline.json")?.let { text ->
            when (val r = com.localmind.data.PipelinePacks.parse(text)) {
                is com.localmind.data.PipelinePacks.ParseResult.Failed -> report += "流水线：${r.reason}"
                is com.localmind.data.PipelinePacks.ParseResult.Ok -> {
                    pipelinePack = r.pack
                    report += "流水线 ${r.pack.packName}（${r.pack.pipelines.size} 条）"
                }
            }
        }

        val stickers = stickerPack
        val models = modelPack
        val voices = voicePack
        val theme = uiTheme
        val engines = ttsPack
        val layout = uiLayout
        val pipes = pipelinePack
        val actions = chatActionPack

        // ---- 第三步：合成清单（没写的话）----
        val manifest = manifestFromFile ?: run {
            val abilities = buildList {
                if (stickers != null) add(PluginAbilities.EMOJI_PACK.id)
                if (models != null) add(PluginAbilities.MODEL_LIBRARY.id)
                if (voices != null) add(PluginAbilities.TTS_VOICE.id)
                if (engines != null) add(PluginAbilities.TTS_ENGINE.id)
                if (theme != null) add(PluginAbilities.UI_THEME.id)
                if (layout != null) add(PluginAbilities.UI_LAYOUT.id)
                if (pipes != null) add(PluginAbilities.PIPELINE.id)
                if (actions != null) add(PluginAbilities.CHAT_ACTION.id)
                if (isEmpty()) {
                    return Outcome.Failed(
                        "压缩包里没有可识别的内容。要有 plugin.json，" +
                            "或 emoji.json（表情包）/ models.json（模型库）/" +
                            "voices.json（语音音色）/ ui.json（界面主题）/" +
                            "tts.json（语音引擎）/ layout.json（界面布局）/ actions.json（聊天动作）"
                    )
                }
            }
            PluginManifest(
                id = probeId.ifBlank { "plugin-" + System.currentTimeMillis() },
                name = stickers?.packName ?: models?.packName ?: voices?.packName
                    ?: theme?.packName ?: file.nameWithoutExtension,
                author = stickers?.author ?: models?.author ?: voices?.author
                    ?: theme?.author.orEmpty(),
                description = stickers?.description ?: models?.note ?: voices?.note
                    ?: theme?.description.orEmpty(),
                abilities = abilities,
            )
        }

        // ---- 第四步：落库 ----
        val plugins = storage.loadPlugins()
        val existing = plugins[manifest.id]
        val contentsSummary = buildList {
            if (stickers != null) add("表情 ${stickers.stickers.size}")
            if (models != null) add("模型 ${models.models.size}")
            if (voices != null) add("音色 ${voices.voices.size}")
            if (theme != null) add("主题 ${theme.tokens.size} 项")
            if (engines != null) add("语音引擎 ${engines.engines.size}")
            if (layout != null) add("布局 ${layout.home.size} 块")
            if (pipes != null) add("流水线 ${pipes.pipelines.size}")
            if (actions != null) add("动作 ${actions.actions.size}")
            if (contents.assets.isNotEmpty()) add("图片 ${contents.assets.size}")
        }
        storage.savePlugins(
            plugins + (
                manifest.id to InstalledPlugin(
                    manifest = manifest,
                    // 重装不要重置用户的启用开关。
                    state = existing?.state ?: PluginState.ENABLED,
                    installedAt = existing?.installedAt ?: System.currentTimeMillis(),
                    source = "zip:${file.name}",
                    contents = contentsSummary,
                )
                )
        )

        models?.let { pack ->
            val id = pack.packId.ifBlank { manifest.id }
            val stamped = pack.copy(packId = id, models = pack.models.map { it.copy(packId = id) })
            storage.saveModelPacks(storage.loadModelPacks() + (id to stamped))
            storage.saveEnabledModelPackIds(storage.enabledModelPackIds() + id)
        }
        stickers?.let {
            // 记下资源目录，解析图片时要用。
            storage.saveStickerPack(it, assetsDir)
        }
        voices?.let { pack ->
            val id = pack.packId.ifBlank { manifest.id }
            storage.saveVoicePacks(storage.loadVoicePacks() + (manifest.id to pack.copy(packId = id)))
        }
        theme?.let { t ->
            storage.saveUiThemes(storage.loadUiThemes() + (manifest.id to t.copy(packId = manifest.id)))
        }
        engines?.let { pack ->
            storage.saveTtsPacks(storage.loadTtsPacks() + (manifest.id to pack.copy(packId = manifest.id)))
        }
        layout?.let { l ->
            storage.saveUiLayouts(storage.loadUiLayouts() + (manifest.id to l.copy(packId = manifest.id)))
        }
        pipes?.let { pack ->
            storage.savePipelinePacks(storage.loadPipelinePacks() + (manifest.id to pack.copy(packId = manifest.id)))
        }
        actions?.let { pack ->
            storage.saveChatActionPacks(storage.loadChatActionPacks() + (manifest.id to pack.copy(packId = manifest.id)))
        }

        val skipped = if (contents.skipped.isEmpty()) "" else "（忽略了 ${contents.skipped.size} 个无关文件）"
        val message = if (report.isEmpty()) {
            "已安装 ${manifest.name}$skipped"
        } else {
            "已安装 ${manifest.name}：${report.joinToString("、")}$skipped"
        }
        return Outcome.Ok(
            Result(
                manifest = manifest,
                stickerPack = stickers,
                modelPack = models,
                voicePack = voices,
                uiTheme = theme,
                ttsPack = engines,
                uiLayout = layout,
                pipelinePack = pipes,
                message = message,
                report = report,
            )
        )
    }

    /** 从内容文件的 JSON 里抠出 packId，用来定资源目录名。 */
    private fun extractPackId(text: String): String? =
        Regex("\"packId\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
}
