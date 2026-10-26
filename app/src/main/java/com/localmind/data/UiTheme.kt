package com.localmind.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 界面插件的数据格式（`ui.json`）。
 *
 * ## 为什么是"令牌"而不是"代码"
 *
 * 这一层能改**整个应用长什么样**：主色、圆角、字号、密度、导航方式、
 * 甚至底部标签栏放哪几个。听起来应该让插件直接写 Compose 代码 —— 但那就
 * 等于把 `DexClassLoader` 请回来，而"装个插件"会立刻变成"装个后门"。
 *
 * 所以做成**声明式令牌**：插件只能从一份**固定的名单**里挑值。这带来三个好处：
 *
 *  1. **安全**：令牌是数据，不可能执行任何东西；最坏情况是配色难看。
 *  2. **不崩**：任何越界值都会被夹到合法区间（见 [UiThemes.resolve]），
 *     插件作者写错数字不会让应用挂掉或排版崩坏。
 *  3. **可组合**：多个界面插件能叠加（浅色/深色分开给），而代码注入做不到。
 *
 * 代价是表达力有上限 —— 但"换个主色、改圆角、藏掉某个标签页"已经覆盖了
 * 绝大多数人想要的"改 UI"。真需要任意布局的人应该去改源码，而不是装插件。
 *
 * ## 2026-10-15 的这次重做：从"五个颜色"到"整套语义槽位"
 *
 * 上一版的颜色令牌只有 5 个真正生效（`accent` `danger` `success`
 * `groupedBackground` `cardBackground`），而 [com.localmind.ui.theme.IosPalette]
 * 里有 18 个槽位是写死的。结果是**主题只能改一点点**：文字色、分隔线、导航栏、
 * 卡片描边、聊天气泡全部纹丝不动，用户反馈就是"改了跟没改一样"。
 *
 * 现在**每一个会被画到屏幕上的颜色都有一个令牌**，键名就是
 * `IosPalette` 的字段名（见 [UiTokens] 里每个令牌的说明）。
 * 默认值 = 今天的内置 iOS 值，所以不装插件时观感一个像素都不变。
 *
 * ### 深色怎么做：不要用 `*Dark` 键，用 [darkTokens]
 *
 * 上一版有三个多余且**从未生效**的键（`accentDark` / `groupedBackgroundDark` /
 * `cardBackgroundDark`）。它们是错的设计：同一件事有两条表达路径，而其中一条
 * 没人实现。现在只有一条：
 *
 * ```json
 * {
 *   "tokens":     { "groupedBackground": "#F7F7F9" },
 *   "darkTokens": { "groupedBackground": "#0A0A0C" }
 * }
 * ```
 *
 * 同名的浅色键在深色模式下被 `darkTokens` 里那一项覆盖（见 [UiThemes.resolve]）。
 * 三个 `*Dark` 键已经**从白名单里删掉**，写了会被当作"不认识的令牌"如实报告。
 *
 * ## 和其它能力的关系
 *
 * 界面插件影响的是**外观与导航**，不影响能力。想加功能的还是用
 * `emoji_pack` / `model_library` / `tts_voice` 那些。两者可以放在同一个 zip 里。
 */
@Serializable
data class UiTheme(
    val version: Int = 1,
    val packId: String = "",
    val packName: String = "",
    val author: String = "",
    val description: String = "",
    val license: String = "",
    val home: String = "",

    /**
     * 外观令牌。键名见 [UiTokens.ALL]。
     *
     * 值统一用字符串表达（`"#FF3B30"` / `"14"` / `"1.08"` / `"true"`），
     * 解析时按每个令牌声明的类型转换。这样 JSON 只需要一种值类型，
     * 插件的格式检查也简单得多。
     */
    val tokens: Map<String, String> = emptyMap(),

    /**
     * 深色模式专用令牌，覆盖 [tokens] 里的同名项。
     *
     * 为什么要分开：一个在浅色下好看的主色，在纯黑背景上往往太暗。
     * iOS 系统色就是两套（`systemBlue` 浅色 `#007AFF` / 深色 `#0A84FF`）。
     */
    val darkTokens: Map<String, String> = emptyMap(),

    /** 底部标签栏显示哪几个，按顺序。空 = 用默认的「对话 / 模型 / 我的」。 */
    val tabs: List<String> = emptyList(),

    /** 把首页直接设成某个标签页（`chat` / `models` / `me`）。空 = `chat`。 */
    val startTab: String = "",

    /** 覆盖应用标题（导航栏里那个名字）。空 = 用默认。 */
    val appName: String = "",
) {
    val isValid: Boolean get() = packId.isNotBlank() && packName.isNotBlank()
}

/** 一个外观令牌的声明：名字、类型、默认值、合法区间。 */
data class UiToken(
    val key: String,
    val label: String,
    val type: TokenType,
    /** 默认值（字符串形式），也是解析失败时的回退。 */
    val default: String,
    /** 数值型的最小/最大值。越界会被夹住而不是拒绝 —— 插件写错不该让应用崩。 */
    val min: Double = Double.NEGATIVE_INFINITY,
    val max: Double = Double.POSITIVE_INFINITY,
    /** 给插件作者看的一句话（写进导出目录里的说明文件）。 */
    val note: String = "",
    /** 数值型令牌的单位（`dp` / `sp`），只用于生成说明文件。 */
    val unitLabel: String = "",
)

enum class TokenType { COLOR, NUMBER, BOOL, ENUM }

object UiTokens {

    // ---------------------------------------------------------------- 颜色：品牌与语义
    //
    // 每个键都**有消费端**。默认值就是内置的 iOS 值 —— 浅色那一套。
    // 深色模式不要在这里找 `*Dark`：用 ui.json 的 `darkTokens`（见 [UiTheme]）。

    val accent = UiToken("accent", "主色", TokenType.COLOR, "#0A84FF",
        note = "按钮、开关、选中态、链接、用户气泡。改这个是最有效的一下")
    val accentOn = UiToken("accentOn", "主色上的文字色", TokenType.COLOR, "#FFFFFF",
        note = "主色按钮/气泡里的文字。选了浅色主色就必须把它改成深色，否则字看不见")
    val danger = UiToken("danger", "危险色", TokenType.COLOR, "#FF3B30",
        note = "删除、清空、错误态")
    val success = UiToken("success", "成功色", TokenType.COLOR, "#34C759",
        note = "开关打开、已完成")
    val warning = UiToken("warning", "提醒色", TokenType.COLOR, "#FF9500",
        note = "橙色警示、未完成状态")

    // ---------------------------------------------------------------- 颜色：背景与结构
    val groupedBackground = UiToken("groupedBackground", "页面背景", TokenType.COLOR, "#F7F7F9",
        note = "整页底色。深色默认纯黑，OLED 友好")
    val cardBackground = UiToken("cardBackground", "卡片背景", TokenType.COLOR, "#FFFFFF",
        note = "列表分组、卡片、AI 气泡")
    val barBackground = UiToken("barBackground", "导航栏/标签栏底色", TokenType.COLOR, "#F7FFFFFF",
        note = "前两位是透明度。要完全不透明就写 #FFFFFF")
    val barBorder = UiToken("barBorder", "导航栏/标签栏描边", TokenType.COLOR, "#14000000")
    val separator = UiToken("separator", "分隔线", TokenType.COLOR, "#14000000",
        note = "列表分隔线、卡片描边、气泡描边")
    val fill = UiToken("fill", "填充底色", TokenType.COLOR, "#1A787880",
        note = "输入框、次级按钮、未选中的开关")
    val fillSecondary = UiToken("fillSecondary", "次级填充色", TokenType.COLOR, "#0F787880",
        note = "更淡的一层填充：选中行、分段控件底")

    // ---------------------------------------------------------------- 颜色：文字三级
    val label = UiToken("label", "主要文字", TokenType.COLOR, "#0B0B0F")
    val secondaryLabel = UiToken("secondaryLabel", "次要文字", TokenType.COLOR, "#8A3C3C43",
        note = "前两位是透明度")
    val tertiaryLabel = UiToken("tertiaryLabel", "三级文字", TokenType.COLOR, "#4D3C3C43")

    val selection = UiToken("selection", "选中高亮", TokenType.COLOR, "#0A84FF",
        note = "标签栏选中指示器。留空/不写就跟随主色")
    val overlay = UiToken("overlay", "浮层遮罩", TokenType.COLOR, "#66000000",
        note = "弹层背后的压暗层")

    // ---------------------------------------------------------------- 颜色：聊天
    val bubbleUser = UiToken("bubbleUser", "用户气泡", TokenType.COLOR, "#0A84FF",
        note = "不写就跟随主色")
    val bubbleUserText = UiToken("bubbleUserText", "用户气泡文字", TokenType.COLOR, "#FFFFFF",
        note = "不写就跟随 accentOn")
    val bubbleAi = UiToken("bubbleAi", "AI 气泡", TokenType.COLOR, "#FFFFFF",
        note = "不写就跟随 cardBackground")
    val bubbleAiBorder = UiToken("bubbleAiBorder", "AI 气泡描边", TokenType.COLOR, "#14000000",
        note = "不写就跟随 separator")
    val composerBackground = UiToken("composerBackground", "输入框底色", TokenType.COLOR, "#1A787880",
        note = "不写就跟随 fill")
    val codeBackground = UiToken("codeBackground", "代码块底色", TokenType.COLOR, "#F2F2F7",
        note = "浅色默认浅灰、深色默认近黑；文字自动取对比色")

    // ---------------------------------------------------------------- 几何
    val cardCorner = UiToken("cardCorner", "卡片圆角", TokenType.NUMBER, "12", 0.0, 32.0, "dp")
    val controlCorner = UiToken("controlCorner", "控件圆角", TokenType.NUMBER, "10", 0.0, 32.0, "dp")
    val sheetCorner = UiToken("sheetCorner", "浮层圆角", TokenType.NUMBER, "16", 0.0, 40.0, "dp")
    val bubbleCorner = UiToken("bubbleCorner", "气泡圆角", TokenType.NUMBER, "18", 0.0, 32.0, "dp")
    val pagePadding = UiToken("pagePadding", "页面左右留白", TokenType.NUMBER, "16", 8.0, 28.0, "dp")
    val navBarHeight = UiToken("navBarHeight", "导航栏高度", TokenType.NUMBER, "44", 36.0, 64.0, "dp")
    val tabBarHeight = UiToken("tabBarHeight", "标签栏高度", TokenType.NUMBER, "62", 44.0, 80.0, "dp")
    val barBorderWidth = UiToken("barBorderWidth", "导航栏/标签栏描边粗细", TokenType.NUMBER, "0.5", 0.0, 2.0, "dp")
    val tabIconSize = UiToken("tabIconSize", "标签栏图标大小", TokenType.NUMBER, "23", 18.0, 30.0, "dp")

    // ---------------------------------------------------------------- 字号
    val bodySize = UiToken("bodySize", "正文字号", TokenType.NUMBER, "17", 12.0, 24.0,
        unitLabel = "sp", note = "只影响正文三档（body / callout / subheadline）")
    val fontScale = UiToken("fontScale", "整体字号缩放", TokenType.NUMBER, "1.0", 0.8, 1.4)

    // ---------------------------------------------------------------- 导航与标签栏
    val tabStyle = UiToken(
        "tabStyle", "标签栏样式", TokenType.ENUM, "glass",
        note = "glass（毛玻璃）/ plain（不透明）/ hidden（藏起来，但标签只剩一个时忽略）",
    )
    val showTabBarLabels = UiToken("showTabBarLabels", "标签栏显示文字", TokenType.BOOL, "true")
    val navTitleAlign = UiToken("navTitleAlign", "导航栏标题位置", TokenType.ENUM, "center",
        note = "center（居中，iOS 默认）/ leading（靠左）")

    val ALL: List<UiToken> = listOf(
        // 品牌与语义
        accent, accentOn, danger, success, warning,
        // 背景与结构
        groupedBackground, cardBackground, barBackground, barBorder,
        separator, fill, fillSecondary,
        // 文字
        label, secondaryLabel, tertiaryLabel,
        // 状态
        selection, overlay,
        // 聊天
        bubbleUser, bubbleUserText, bubbleAi, bubbleAiBorder, composerBackground, codeBackground,
        // 几何
        cardCorner, controlCorner, sheetCorner, bubbleCorner,
        pagePadding, navBarHeight, tabBarHeight, barBorderWidth, tabIconSize,
        // 字号
        bodySize, fontScale,
        // 导航与标签栏
        tabStyle, showTabBarLabels, navTitleAlign,
    )

    val byKey: Map<String, UiToken> = ALL.associateBy { it.key }

    /** ENUM 类型的允许值。名单外的值会被忽略并回退到默认。 */
    val enumValues: Map<String, Set<String>> = mapOf(
        tabStyle.key to setOf("glass", "plain", "hidden"),
        navTitleAlign.key to setOf("center", "leading"),
    )

    /** 合法的标签页 id。 */
    val tabIds: Set<String> = setOf("chat", "models", "me", "home", "voice", "breeze", "plugins", "image", "pipeline")
}

/** 界面插件的解析与校验。 */
object UiThemes {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
    }

    sealed interface ParseResult {
        data class Ok(val theme: UiTheme) : ParseResult
        data class Failed(val reason: String) : ParseResult
    }

    fun parse(text: String): ParseResult {
        if (text.isBlank()) return ParseResult.Failed("ui.json 是空的")
        val theme = runCatching { json.decodeFromString<UiTheme>(text) }.getOrElse { t ->
            return ParseResult.Failed("ui.json 格式错误：${t.message?.take(140) ?: "解析失败"}")
        }
        if (theme.packId.isBlank()) return ParseResult.Failed("ui.json 缺少 packId")
        if (theme.packName.isBlank()) return ParseResult.Failed("ui.json 缺少 packName")

        val badTabs = theme.tabs.filter { it !in UiTokens.tabIds }
        if (badTabs.isNotEmpty()) {
            return ParseResult.Failed(
                "tabs 里有不认识的页面：${badTabs.joinToString()}。" +
                    "只能是 ${UiTokens.tabIds.joinToString("、")}"
            )
        }
        if (theme.startTab.isNotBlank() && theme.startTab !in UiTokens.tabIds) {
            return ParseResult.Failed(
                "startTab 不认识：${theme.startTab}。只能是 ${UiTokens.tabIds.joinToString("、")}"
            )
        }
        if (theme.tokens.isEmpty() && theme.darkTokens.isEmpty() &&
            theme.tabs.isEmpty() && theme.appName.isBlank()
        ) {
            return ParseResult.Failed("ui.json 什么都没改（tokens / darkTokens / tabs / appName 都是空的）")
        }
        return ParseResult.Ok(theme)
    }

    fun encode(theme: UiTheme): String = json.encodeToString(UiTheme.serializer(), theme)

    /**
     * 把令牌表解析成"能直接用"的值，**顺便夹住越界值**。
     *
     * @param theme 生效的主题包。**传 null = 没装主题插件**，此时只有 [overrides]
     *   （用户手动覆盖 / 内置预设）生效 —— 这条路径以前在两个调用点各写了一遍，
     *   其中一处还漏了，所以这里收进函数本身。
     * @return key → 已规范化的字符串值；未知键不在这里（见 [unknownKeys]）。
     */
    fun resolve(
        theme: UiTheme?,
        dark: Boolean,
        overrides: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val merged = LinkedHashMap<String, String>()
        if (theme != null) merged.putAll(theme.tokens)
        if (dark && theme != null) merged.putAll(theme.darkTokens)   // 深色覆盖浅色

        // 用户手动覆盖优先级最高。其中 `dark:` 前缀的项**只在深色模式下**生效 ——
        // 预设就是这么表达"深色那一套"的（见 [UiPresets.Preset.overrideEntries]）。
        for ((rawKey, v) in overrides) {
            val isDarkOnly = rawKey.startsWith(UiPresets.Preset.DARK_PREFIX)
            if (isDarkOnly && !dark) continue
            val key = if (isDarkOnly) rawKey.removePrefix(UiPresets.Preset.DARK_PREFIX) else rawKey
            merged[key] = v
        }

        val out = LinkedHashMap<String, String>()
        for ((k, v) in merged) {
            val token = UiTokens.byKey[k] ?: continue
            out[k] = normalize(token, v)
        }
        return out
    }

    /** 把可能带 `dark:` 前缀的键还原成令牌名（界面展示用）。 */
    fun plainKey(key: String): String =
        key.removePrefix(UiPresets.Preset.DARK_PREFIX)

    /** 这个覆盖项是不是"只在深色下生效"。 */
    fun isDarkOnly(key: String): Boolean = key.startsWith(UiPresets.Preset.DARK_PREFIX)

    /** 插件里写了但主程序不认识的键。要在界面上如实告诉作者。 */
    fun unknownKeys(theme: UiTheme): List<String> =
        (theme.tokens.keys + theme.darkTokens.keys).filter { it !in UiTokens.byKey }.distinct()

    /**
     * 规范化一个值。**不合法的输入回退到默认，而不是报错** ——
     * 插件作者写错一个颜色不该让整个应用起不来。
     */
    fun normalize(token: UiToken, raw: String): String {
        val v = raw.trim()
        return when (token.type) {
            TokenType.COLOR -> when {
                v.startsWith("rgb") -> parseRgba(v) ?: token.default
                isColor(v) -> v.uppercase()
                else -> token.default
            }
            TokenType.NUMBER -> {
                val d = v.toDoubleOrNull() ?: return token.default
                val clamped = d.coerceIn(token.min, token.max)
                // 整数令牌不要拖 `.0`，否则拼进 dp 会有意外。
                if (clamped == clamped.toLong().toDouble() && token.default.contains('.').not()) {
                    clamped.toLong().toString()
                } else {
                    clamped.toString()
                }
            }
            TokenType.BOOL -> when (v.lowercase()) {
                "true", "1", "yes", "on" -> "true"
                "false", "0", "no", "off" -> "false"
                else -> token.default
            }
            TokenType.ENUM -> {
                val allowed = UiTokens.enumValues[token.key].orEmpty()
                if (v.lowercase() in allowed) v.lowercase() else token.default
            }
        }
    }

    /**
     * 颜色格式：`#RGB` / `#RRGGBB` / `#AARRGGBB`。
     *
     * 8 位一律按 **AARRGGBB** 解析（前两位是透明度）—— 这是 Android 的惯例，
     * 也是内置深色文字色（如次要文字的 `#8A3C3C43`）用的形式。
     * 想要明确可读的透明度写法就用 `rgba(r,g,b,a)`。
     */
    private fun isColor(s: String): Boolean {
        if (s.startsWith("rgba(") || s.startsWith("rgb(")) return parseRgba(s) != null
        if (!s.startsWith("#")) return false
        val hex = s.substring(1)
        if (hex.length !in setOf(3, 6, 8)) return false
        return hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }

    /**
     * `rgba(255,107,53,0.5)` / `rgb(255,107,53)` → `#AARRGGBB`，解析不了返回 null。
     *
     * 为什么加这个：`#AARRGGBB` 把透明度放在**前面**，和 CSS 相反，
     * 写主题的人十次有九次会写错成 `#RRGGBBAA`。给一个不会写错的写法。
     */
    private fun parseRgba(s: String): String? {
        val inner = s.substringAfter('(').substringBeforeLast(')')
        val parts = inner.split(',').map { it.trim() }
        if (parts.size !in setOf(3, 4)) return null
        val rgb = parts.take(3).map { it.toIntOrNull() ?: return null }
        if (rgb.any { it !in 0..255 }) return null
        val a = if (parts.size == 4) {
            val f = parts[3].toFloatOrNull() ?: return null
            if (f !in 0f..1f) return null
            (f * 255f).toInt().coerceIn(0, 255)
        } else 255
        return "#%02X%02X%02X%02X".format(a, rgb[0], rgb[1], rgb[2])
    }

    /**
     * 一份可以直接导入的完整示例。
     *
     * ⚠️ 这份示例**故意只改少数几个键**，因为它的用途是"给出一个能跑的最小骨架"，
     * 不是"展示全部能力"。想看全部能改什么，看界面导出目录里的
     * `可改的令牌.txt`（由 [com.localmind.ui.AppViewModel.saveSampleUiTheme] 生成），
     * 或者在应用里试 [UiPresets]。
     */
    val SAMPLE: UiTheme = UiTheme(
        packId = "com.example.mytheme",
        packName = "我的界面主题",
        author = "你",
        description = "示例：换主色、加大圆角、隐藏标签栏文字。",
        license = "你的授权",
        tokens = mapOf(
            UiTokens.accent.key to "#FF6B35",
            UiTokens.accentOn.key to "#FFFFFF",
            UiTokens.cardCorner.key to "18",
            UiTokens.bubbleCorner.key to "22",
        ),
        darkTokens = mapOf(
            UiTokens.accent.key to "#FF8A5C",
            UiTokens.groupedBackground.key to "#0A0A0C",
            UiTokens.cardBackground.key to "#17171A",
        ),
    )
}

/**
 * 内置预设主题。
 *
 * ## 为什么要有这个
 *
 * 界面插件本来只能靠 zip 装。结果是：想试一下"换个风格"，得先写 JSON、
 * 压成平铺 zip、在手机上导入 —— 而这一步几乎没人会走完，于是"能换风格"
 * 这件事实际上等于不存在。用户的原话就是"没办法彻底改风格"。
 *
 * 所以把几个**做好的**整轮令牌直接放进主程序：点一下就换，能立刻看到效果，
 * 不喜欢再点回"默认"。它是纯数据（一张 `Map<String,String>`），
 * 不进代码路径，也不违反"主程序不带素材"的原则 —— 这些是几十行键值对，
 * 不是模型。
 *
 * ## 它和插件的优先级
 *
 * 预设写进的是**用户手动覆盖**（`Storage.themeOverrides`），而
 * [UiThemes.resolve] 里用户覆盖的优先级最高。所以：
 *
 *  * 预设能盖住插件给的任何一项；
 *  * 用户可以只改其中一两项，剩下的继续跟着预设走；
 *  * "恢复默认"就是清空覆盖。
 *
 * 这样预设和插件不会互相打架，也不用引入"谁赢"的第二套规则。
 */
object UiPresets {

    /** 一个预设：id、显示名、一句话说明、浅色令牌、深色令牌。 */
    data class Preset(
        val id: String,
        val name: String,
        val description: String,
        val tokens: Map<String, String>,
        val darkTokens: Map<String, String> = emptyMap(),
    ) {
        /** 这个预设一共声明了多少个键。 */
        val allKeys: Set<String> get() = tokens.keys + darkTokens.keys

        /**
         * 写进「用户覆盖」的键值对。
         *
         * ⚠️ **深色那一套要在前面加前缀**，不能直接和浅色合并 ——
         * 合并的话深色值会盖掉浅色值（同一个 key），于是"在浅色模式下套预设"
         * 会得到一堆深色颜色：整页发黑、文字变成浅色、对比度全乱。
         *
         * 这个 bug 真的发生过，而且因为"颜色看起来是变了"很容易被当成
         * "主题生效了"而放过去。现在浅色覆盖用原名，深色覆盖用
         * [DARK_PREFIX] + 键名，由 [UiThemes.resolve] 在深色模式下才取用。
         */
        val overrideEntries: Map<String, String>
            get() = tokens + darkTokens.mapKeys { (k, _) -> DARK_PREFIX + k }

        /** 反向：从一份覆盖表里还原出这个预设的浅色/深色原始值（给匹配用）。 */
        fun toTokens(overrides: Map<String, String>): Map<String, String> = tokens
        fun toDarkTokens(overrides: Map<String, String>): Map<String, String> =
            darkTokens.mapValues { (k, _) -> overrides[DARK_PREFIX + k] ?: "" }

        companion object {
            /**
             * 深色覆盖的前缀。
             *
             * 为什么用前缀而不是给 Storage 再加一张表：加一张表意味着所有
             * "读写覆盖"的地方都要照顾两张表（诊断端点、清除、导出……），
             * 而它们**一定会有一处漏掉**。前缀只有一个约定，一张表，
             * 加一个解析分支就够了。
             */
            const val DARK_PREFIX = "dark:"
        }
    }

    val EARTH = Preset(
        id = "earth",
        name = "暖橙",
        description = "琥珀主色、奶油底、大圆角",
        tokens = mapOf(
            "accent" to "#E8590C",
            "accentOn" to "#FFFFFF",
            "success" to "#2F9E44",
            "warning" to "#F08C00",
            "groupedBackground" to "#FBF6F0",
            "cardBackground" to "#FFFFFF",
            "label" to "#2B211A",
            "secondaryLabel" to "#8A7A6D",
            "tertiaryLabel" to "#B9AA9C",
            "separator" to "#1F8A7A6D",
            "fill" to "#1AE8590C",
            "fillSecondary" to "#0FE8590C",
            "barBackground" to "#F7FBF6F0",
            "barBorder" to "#1A8A7A6D",
            "bubbleUser" to "#E8590C",
            "bubbleAi" to "#FFFFFF",
            "selection" to "#E8590C",
            "cardCorner" to "16",
            "controlCorner" to "12",
            "bubbleCorner" to "20",
            "sheetCorner" to "20",
        ),
        darkTokens = mapOf(
            "accent" to "#FF922B",
            "groupedBackground" to "#100D0B",
            "cardBackground" to "#1C1713",
            "label" to "#F5EDE6",
            "secondaryLabel" to "#99C9B6A8",
            "separator" to "#1FFFF0E0",
            "fill" to "#2EFF922B",
            "barBackground" to "#F214100D",
            "bubbleAi" to "#1C1713",
        ),
    )

    val AURORA = Preset(
        id = "aurora",
        name = "极光",
        description = "青紫渐变观感、深色底、小圆角",
        tokens = mapOf(
            "accent" to "#5F3DC4",
            "accentOn" to "#FFFFFF",
            "success" to "#0CA678",
            "warning" to "#F59F00",
            "groupedBackground" to "#F4F3FB",
            "cardBackground" to "#FFFFFF",
            "label" to "#16132B",
            "secondaryLabel" to "#8A16274A",
            "separator" to "#1416274A",
            "fill" to "#1A5F3DC4",
            "fillSecondary" to "#0F5F3DC4",
            "barBackground" to "#F7F4F3FB",
            "bubbleUser" to "#5F3DC4",
            "selection" to "#7048E8",
            "cardCorner" to "10",
            "controlCorner" to "8",
            "bubbleCorner" to "14",
        ),
        darkTokens = mapOf(
            "accent" to "#9775FA",
            "groupedBackground" to "#0B0A14",
            "cardBackground" to "#16152A",
            "label" to "#EDEBFA",
            "secondaryLabel" to "#99C4BEF0",
            "separator" to "#1FFFFFFF",
            "barBackground" to "#F216152A",
            "fill" to "#2E9775FA",
            "bubbleAi" to "#16152A",
            "selection" to "#845EF7",
        ),
    )

    val PAPER = Preset(
        id = "paper",
        name = "纸张",
        description = "高对比黑白、近直角、不透明导航栏",
        tokens = mapOf(
            "accent" to "#111111",
            "accentOn" to "#FFFFFF",
            "success" to "#137333",
            "danger" to "#C5221F",
            "warning" to "#B06000",
            "groupedBackground" to "#FFFFFF",
            "cardBackground" to "#F7F7F7",
            "label" to "#000000",
            "secondaryLabel" to "#7A000000",
            "tertiaryLabel" to "#4D000000",
            "separator" to "#33000000",
            "fill" to "#14000000",
            "fillSecondary" to "#0A000000",
            "barBackground" to "#FFFFFF",
            "barBorder" to "#33000000",
            "bubbleUser" to "#111111",
            "bubbleAi" to "#F2F2F2",
            "bubbleAiBorder" to "#33000000",
            "selection" to "#111111",
            "cardCorner" to "4",
            "controlCorner" to "4",
            "bubbleCorner" to "8",
            "sheetCorner" to "8",
            "tabStyle" to "plain",
        ),
        darkTokens = mapOf(
            "accent" to "#F2F2F2",
            "accentOn" to "#111111",
            "groupedBackground" to "#000000",
            "cardBackground" to "#141414",
            "label" to "#FFFFFF",
            "secondaryLabel" to "#99FFFFFF",
            "separator" to "#4DFFFFFF",
            "fill" to "#1FFFFFFF",
            "barBackground" to "#000000",
            "barBorder" to "#4DFFFFFF",
            "bubbleUser" to "#F2F2F2",
            "bubbleUserText" to "#111111",
            "bubbleAi" to "#141414",
            "selection" to "#F2F2F2",
        ),
    )

    val NIGHT = Preset(
        id = "night",
        name = "深海",
        description = "冷蓝底、低饱和、大留白",
        tokens = mapOf(
            "accent" to "#0B7285",
            "accentOn" to "#FFFFFF",
            "success" to "#087F5B",
            "warning" to "#E67700",
            "groupedBackground" to "#EEF2F5",
            "cardBackground" to "#FFFFFF",
            "label" to "#0B1B22",
            "secondaryLabel" to "#8A0B1B22",
            "separator" to "#140B1B22",
            "fill" to "#1A0B7285",
            "barBackground" to "#F7EEF2F5",
            "bubbleUser" to "#0B7285",
            "selection" to "#1098AD",
            "pagePadding" to "20",
            "cardCorner" to "14",
            "bubbleCorner" to "18",
        ),
        darkTokens = mapOf(
            "accent" to "#22B8CF",
            "accentOn" to "#04252C",
            "groupedBackground" to "#07171C",
            "cardBackground" to "#0E2429",
            "label" to "#E3F4F8",
            "secondaryLabel" to "#99B9D6DA",
            "separator" to "#1F9FD8E0",
            "fill" to "#2E22B8CF",
            "barBackground" to "#F20E2429",
            "bubbleAi" to "#0E2429",
            "selection" to "#3BC9DB",
        ),
    )

    /** 顺序即界面上的显示顺序。 */
    val ALL: List<Preset> = listOf(EARTH, AURORA, PAPER, NIGHT)

    fun byId(id: String): Preset? = ALL.firstOrNull { it.id == id }

    /**
     * 找出这组覆盖最像哪个预设（用于界面上标"当前：暖橙"）。
     *
     * 只比较**预设声明过的键**：用户可能另外多覆盖了几项，那不该让
     * "这是哪个预设"认不出来。
     */
    fun match(overrides: Map<String, String>): Preset? = ALL.firstOrNull { p ->
        p.tokens.all { (k, v) -> overrides[k] == v } &&
            p.darkTokens.all { (k, v) -> overrides[Preset.DARK_PREFIX + k] == v }
    }
}
