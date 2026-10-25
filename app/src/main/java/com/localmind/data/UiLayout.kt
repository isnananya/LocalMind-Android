package com.localmind.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 界面布局插件（能力 id = `ui_layout`，内容文件 = `layout.json`）。
 *
 * ## 它和 [UiTheme] 的分工
 *
 *  - `ui_theme` 管**外观令牌**：颜色、圆角、字号（37 项）。
 *  - `ui_layout` 管**结构与形态**：底部有哪几个入口、每个入口进哪一页、
 *    首页由哪些块组成、应用叫什么名字，并且可以顺带覆盖同一套外观令牌。
 *
 * 分开的理由：只换配色的插件不该被迫声明导航，只想改导航的插件也不该抄 37 个颜色。
 * 两者可以放在同一个 zip 里（一个插件可以同时提供两种能力）。
 *
 * ## 为什么是"块"而不是任意组件
 *
 * 主程序只认 [BLOCK_TYPES] 里这几种块。好处是可预期：插件写错了最坏是"这块不显示"，
 * 不会白屏、不会崩。代价是自由度的上限被主程序定死了 —— 这是刻意的，
 * 插件是**数据**不是代码（见 [PluginManifest] 的说明）。
 *
 * ## 优先级
 *
 * 令牌：内置 iOS 原色 → 主题插件 → **布局插件** → 用户手动覆盖。
 * 导航：布局插件优先于主题插件声明的那份 `tabs`。
 */
@Serializable
data class LayoutTab(
    /** 稳定 id，同时也是默认要打开的页面名（见 [Layouts.SCREENS]）。 */
    val id: String,
    /** 底栏文字。空 = 用页面自己的默认名。 */
    val label: String = "",
    /** 图标 id（见 [Layouts.ICONS]）。空 = 用页面默认图标。 */
    val icon: String = "",
    /** 进哪一页。空 = 和 id 相同。 */
    val screen: String = "",
) {
    val target: String get() = screen.ifBlank { id }
}

/**
 * 一个可点的动作。首页的按钮、快捷入口都用它。
 *
 * `open` 是**页面名**（见 [Layouts.SCREENS]）或少数动作名
 * （`new` = 新建对话、`settings` = 打开设置、`plugins_import` = 去插件页）。
 */
@Serializable
data class LayoutAction(
    val label: String,
    val icon: String = "",
    val open: String = "",
    /** 备注文字（可选，显示在标签下面一行）。 */
    val text: String = "",
)

/**
 * 首页的一块内容。
 *
 * [type] 只能是 [Layouts.BLOCK_TYPES] 里的值；其余字段按类型取用，没用到的忽略。
 */
@Serializable
data class LayoutBlock(
    val type: String,
    val title: String = "",
    val text: String = "",
    val icon: String = "",
    val items: List<LayoutAction> = emptyList(),
    /** recent 块最多显示几条。 */
    val limit: Int = 3,
)

/** 一个完整的界面布局包。 */
@Serializable
data class UiLayout(
    val version: Int = 1,
    val packId: String = "",
    val packName: String = "",
    val author: String = "",
    val description: String = "",
    /** 覆盖应用标题（导航栏、关于页）。空 = 用默认。 */
    val appName: String = "",
    /** 首页顶部的一句话副标题。 */
    val subtitle: String = "",
    val tabs: List<LayoutTab> = emptyList(),
    /** 初始标签。空 = 第一个 tab。 */
    val startTab: String = "",
    /** 首页的块。空 = 首页用主程序的默认样子（对话列表）。 */
    val home: List<LayoutBlock> = emptyList(),
    /** 外观令牌覆盖，键名与 [UiTokens] 相同。 */
    val tokens: Map<String, String> = emptyMap(),
    val darkTokens: Map<String, String> = emptyMap(),
) {
    val isValid: Boolean get() = packId.isNotBlank() && packName.isNotBlank()

    /** 这个布局是不是自带块首页。 */
    val hasBlocks: Boolean get() = home.any { it.type in Layouts.BLOCK_TYPES }
}

/** 布局包的解析与校验。 */
object Layouts {

    /**
     * 布局插件能打开的页面。
     *
     * 每加一个页面，主程序要同时改三处：`Tab` 枚举、`LocalMindRoot` 的渲染分支、
     * 这里的白名单。少改一处就会出现"插件声明了但界面不认"的空白页，
     * 所以校验放在解析阶段（装插件时就报错，而不是打开时白屏）。
     */
    val SCREENS: Set<String> = setOf(
        "chat", "models", "me", "home", "voice", "breeze", "plugins", "image", "pipeline",
    )

    /** 图标 id → 主程序内置图标。认不出的回退到页面默认图标。 */
    val ICONS: Set<String> = setOf(
        "chat", "bubble", "models", "memory", "me", "person", "home",
        "voice", "breeze", "plugins", "extension", "image", "sparkle",
        "cloud", "bolt", "mic", "volume", "search", "settings", "tune",
    )

    /** 首页块类型。 */
    val BLOCK_TYPES: Set<String> = setOf("hero", "actions", "text", "recent", "status")

    /** 动作名（除了页面名之外）。 */
    val ACTIONS: Set<String> = setOf("new", "settings", "plugins_import", "debug")

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
    }

    sealed interface ParseResult {
        data class Ok(val layout: UiLayout) : ParseResult
        data class Failed(val reason: String) : ParseResult
    }

    fun parse(text: String): ParseResult {
        if (text.isBlank()) return ParseResult.Failed("layout.json 是空的")
        val layout = runCatching { json.decodeFromString<UiLayout>(text) }.getOrElse { t ->
            return ParseResult.Failed("layout.json 格式错误：${t.message?.take(140) ?: "解析失败"}")
        }
        if (layout.packId.isBlank()) return ParseResult.Failed("layout.json 缺少 packId")
        if (layout.packName.isBlank()) return ParseResult.Failed("layout.json 缺少 packName")
        if (layout.tabs.isEmpty() && layout.home.isEmpty() && layout.tokens.isEmpty() &&
            layout.darkTokens.isEmpty() && layout.appName.isBlank()
        ) {
            return ParseResult.Failed("layout.json 什么都没改（tabs / home / tokens / appName 都是空的）")
        }

        // 标签：数量、重复、未知页面都要在**装的时候**说清楚。
        if (layout.tabs.size > 5) {
            return ParseResult.Failed("tabs 最多 5 个（底栏放不下更多），现在是 ${layout.tabs.size} 个")
        }
        val dup = layout.tabs.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (dup.isNotEmpty()) return ParseResult.Failed("tabs 里有重复的 id：${dup.joinToString()}")

        val badScreens = layout.tabs.filter { it.target !in SCREENS }.map { "${it.id}→${it.target}" }
        if (badScreens.isNotEmpty()) {
            return ParseResult.Failed(
                "tabs 指向了不存在的页面：${badScreens.joinToString()}。" +
                    "只能是 ${SCREENS.joinToString("、")}"
            )
        }
        if (layout.startTab.isNotBlank() && layout.tabs.none { it.id == layout.startTab }) {
            return ParseResult.Failed("startTab ${layout.startTab} 不在 tabs 里")
        }

        val badBlocks = layout.home.filter { it.type !in BLOCK_TYPES }.map { it.type }
        if (badBlocks.isNotEmpty()) {
            return ParseResult.Failed(
                "home 里有不认识的块：${badBlocks.joinToString()}。" +
                    "只能是 ${BLOCK_TYPES.joinToString("、")}"
            )
        }
        for (block in layout.home) {
            val badActions = block.items.filter { it.open.isNotBlank() && it.open !in SCREENS && it.open !in ACTIONS }
            if (badActions.isNotEmpty()) {
                return ParseResult.Failed(
                    "home 里「${block.type}」块的动作打开了不存在的目标：" +
                        badActions.joinToString { it.open }
                )
            }
        }

        val unknownTabs = layout.tabs.filter { it.icon.isNotBlank() && it.icon !in ICONS }
        if (unknownTabs.isNotEmpty()) {
            // 图标认不出只是回退成默认图标，不该让整个插件装不上。
            // 但要在安装报告里如实说明（见 PluginInstall）。
        }

        return ParseResult.Ok(layout)
    }

    fun encode(layout: UiLayout): String = json.encodeToString(UiLayout.serializer(), layout)

    /** 插件写错、但被容忍的项（目前只有图标），用于安装报告。 */
    fun tolerated(layout: UiLayout): List<String> =
        layout.tabs.filter { it.icon.isNotBlank() && it.icon !in ICONS }
            .map { "图标 ${it.icon} 不认识，用了默认图标" }

    /** 给插件作者看的一份可直接改的示例（"复制示例"按钮用）。 */
    val SAMPLE: String = encode(
        UiLayout(
            packId = "com.example.cardboard",
            packName = "示例：卡片首页",
            author = "你",
            description = "把首页换成卡片流：上面一张问候卡，中间几个快捷入口，下面是最近对话。",
            appName = "LocalMind",
            subtitle = "全部在本机运行",
            tabs = listOf(
                LayoutTab("home", "首页", "home"),
                LayoutTab("chat", "对话", "bubble"),
                LayoutTab("breeze", "语音", "breeze"),
                LayoutTab("me", "我的", "person"),
            ),
            startTab = "home",
            home = listOf(
                LayoutBlock(
                    type = "hero",
                    title = "今天想说点什么？",
                    text = "模型、语音、生图都在本机跑，断网也能用。",
                    icon = "sparkle",
                ),
                LayoutBlock(
                    type = "actions",
                    items = listOf(
                        LayoutAction("新建对话", "bubble", "new"),
                        LayoutAction("模型", "memory", "models"),
                        LayoutAction("Breeze 语音", "breeze", "breeze"),
                        LayoutAction("插件", "extension", "plugins"),
                    ),
                ),
                LayoutBlock(type = "recent", title = "最近", limit = 3),
                LayoutBlock(type = "status", title = "本机状态"),
            ),
        )
    )
}
