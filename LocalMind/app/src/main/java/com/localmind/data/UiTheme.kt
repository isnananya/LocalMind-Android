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

/**
 * 令牌的取值类型。
 *
 * 新增类型时**必须同时**在 [UiTokens.normalize] 里加分支，否则会落到
 * `else` 分支被当成未知类型丢掉。
 */
enum class TokenType {
    /** 颜色。`#RGB` / `#RRGGBB` / `#AARRGGBB` / `rgb(r,g,b)` / `rgba(r,g,b,a)`。 */
    COLOR,

    /** 数值。越界会被夹到 [UiToken.min]~[UiToken.max]。 */
    NUMBER,

    /** 布尔。`true/false/1/0/yes/no/on/off`。 */
    BOOL,

    /** 枚举。合法值在 [UiTokens.enumValues] 里写死。 */
    ENUM,

    /** 自由文本。不会做格式校验，只做 trim 和长度限制。 */
    TEXT,
}


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
    // 56 时"图标 + 文字"只剩 2dp 呼吸空间（实测选中胶囊里的文字快贴到底边），改成 60。
    val tabBarHeight = UiToken("tabBarHeight", "标签栏高度", TokenType.NUMBER, "60", 44.0, 80.0, "dp")
    val barBorderWidth = UiToken("barBorderWidth", "导航栏/标签栏描边粗细", TokenType.NUMBER, "0.5", 0.0, 2.0, "dp")
    val tabIconSize = UiToken("tabIconSize", "标签栏图标大小", TokenType.NUMBER, "23", 18.0, 30.0, "dp")

    // ---------------------------------------------------------------- 字号
    val bodySize = UiToken("bodySize", "正文字号", TokenType.NUMBER, "17", 12.0, 24.0,
        unitLabel = "sp", note = "只影响正文三档（body / callout / subheadline）")
    val fontScale = UiToken("fontScale", "整体字号缩放", TokenType.NUMBER, "1.0", 0.8, 1.4)

    // ---------------------------------------------------------------- 导航与标签栏
    val tabBarShape = UiToken(
        "tabBarShape", "标签栏形状", TokenType.ENUM, "capsule",
        note = "capsule（胶囊）/ rounded（圆角矩形）/ square（直角）",
    )
    val tabBarMargin = UiToken("tabBarMargin", "标签栏左右留白", TokenType.NUMBER, "18", 0.0, 48.0, "dp")
    val tabBarLift = UiToken("tabBarLift", "标签栏离底距离", TokenType.NUMBER, "12", 0.0, 40.0, "dp")
    val tabBarBlur = UiToken(
        "tabBarBlur", "玻璃模糊半径", TokenType.NUMBER, "10", 0.0, 16.0,
        unitLabel = "dp",
        note = "10 = 默认磨砂（2 太轻，底下的字会透出来）；0 = 完全清晰（纯折射），16 = 重度磨砂",
    )
    val tabBarTint = UiToken("tabBarTint", "玻璃染色", TokenType.COLOR, "#FFFFFF",
        note = "深色主题用 darkTokens 覆盖成深色")
    val tabBarWidth = UiToken(
        "tabBarWidth", "标签栏宽度", TokenType.ENUM, "compact",
        note = "compact（悬浮紧凑胶囊）/ full（通栏）",
    )

    val tabBarIndicator = UiToken(
        "tabBarIndicator", "选中指示器", TokenType.ENUM, "capsule",
        note = "capsule（玻璃胶囊）/ underline（下划线）/ dot（圆点）/ none",
    )

    val uiKit = UiToken(
        "uiKit", "液态玻璃组件库", TokenType.ENUM, "auto",
        note = "auto（硬件支持则用 AndroidLiquidGlassView）/ library（强制组件库）/ compose（用内置降级）",
    )

    // ------------------------------------------------- 液态玻璃（LiquidTab 风格）
    val glassBarLiquid = UiToken(
        "glassBarLiquid", "液态玻璃效果", TokenType.BOOL, "true",
        note = "开：边缘折射 + 色散 + 按压放大；关：只保留模糊与染色（LiquidTab 的默认行为）",
    )
    val dynamicColor = UiToken(
        "dynamicColor", "动态配色", TokenType.BOOL, "false",
        note = "从系统壁纸主色生成 accent；关掉回到主题包/预设的颜色",
    )
    val glassBarOutline = UiToken("glassBarOutline", "底栏描边", TokenType.BOOL, "true",
        note = "开启时画一圈 1dp 高光描边")
    val glassBarHighlight = UiToken(
        "glassBarHighlight", "高光强度", TokenType.NUMBER, "0.5", 0.5, 1.7,
        note = "描边与边缘高光的强度，随设备倾斜移动",
    )
    val glassBarSlotWidth = UiToken(
        "glassBarSlotWidth", "单栏宽度", TokenType.NUMBER, "76", 56.0, 112.0,
        unitLabel = "dp", note = "悬浮胶囊的总宽 = 单栏宽度 × 标签数",
    )
    val glassBarBottomBlur = UiToken(
        "glassBarBottomBlur", "底部渐变模糊", TokenType.BOOL, "false",
        note = "底栏上方页面底部的渐隐模糊",
    )
    val glassBarIconOnly = UiToken("glassBarIconOnly", "仅图标", TokenType.BOOL, "false",
        note = "开启后不画文字（悬浮胶囊会自动更紧凑）")
    val glassBarLabelSize = UiToken("glassBarLabelSize", "标签文字大小", TokenType.NUMBER, "11", 9.0, 14.0,
        unitLabel = "sp")

    val tabStyle = UiToken(
        "tabStyle", "标签栏样式", TokenType.ENUM, "glass",
        note = "glass（毛玻璃）/ plain（不透明）/ hidden（藏起来，但标签只剩一个时忽略）",
    )
    val showTabBarLabels = UiToken("showTabBarLabels", "标签栏显示文字", TokenType.BOOL, "true")
    val dockImpl = UiToken(
        "dockImpl", "底栏实现", TokenType.ENUM, "liquidTabBar",
        note = "liquidTabBar（当前液态玻璃底栏）/ liquidDock（自定义液态底栏，更多可调项）",
    )
    val navTitleAlign = UiToken("navTitleAlign", "导航栏标题位置", TokenType.ENUM, "center",
        note = "center（居中，iOS 默认）/ leading（靠左）")

    // ---------------------------------------------------------------- 字体
    //
    // 二次元主题几乎都要换字体 —— 这是"一眼看出是二次元"最省力的一下。
    // 用系统自带字体族，不打包字体文件：省体积，也不用担心授权。
    val fontFamily = UiToken(
        "fontFamily", "界面字体", TokenType.ENUM, "system",
        note = "system（系统默认）/ sans（无衬线）/ serif（衬线，像书）/ mono（等宽，像代码）/ cursive（手写体）",
    )

    // ---------------------------------------------------------------- 壁纸
    //
    // 壁纸是**主题包自带的一张图**，装主题时一起落到插件资源目录。
    // 这里存的是相对路径（插件 id 下的文件名），不是 URL —— URL 要下载，
    // 那属于插件安装流程的事，主题令牌只负责"用哪张"。
    val wallpaperFile = UiToken(
        "wallpaperFile", "壁纸图片", TokenType.TEXT, "",
        note = "主题包内的图片文件名（放在插件 assets/ 下）。留空 = 用内置极光背景",
    )
    val wallpaperDim = UiToken(
        "wallpaperDim", "壁纸压暗", TokenType.NUMBER, "0.0", 0.0, 0.85,
        note = "壁纸上的黑色遮罩浓度。压太深壁纸就白装了，太浅文字看不清",
    )
    val wallpaperBlur = UiToken(
        "wallpaperBlur", "壁纸模糊", TokenType.NUMBER, "0", 0.0, 24.0,
        unitLabel = "dp", note = "壁纸背景虚化半径。深色主题常用 12~20 让文字浮起来",
    )
    val wallpaperScale = UiToken(
        "wallpaperScale", "壁纸缩放", TokenType.NUMBER, "1.0", 1.0, 2.0,
        note = "壁纸放大倍数。手机屏幕比例和壁纸图比例不一致时会露边，放大能盖住",
    )

    // ---------------------------------------------------------------- 风格
    //
    // 这几个是"卡片长什么样"的开关。不同主题的差别往往不在颜色，
    // 而在**卡片有没有厚度**（扁平 / 毛玻璃 / 渐变）、**气泡是什么形状**。
    val cardStyle = UiToken(
        "cardStyle", "卡片风格", TokenType.ENUM, "flat",
        note = "flat（扁平）/ glass（毛玻璃，半透）/ gradient（渐变）/ neu（拟物，双层描边）",
    )
    val cardElevation = UiToken(
        "cardElevation", "卡片厚度", TokenType.NUMBER, "0", 0.0, 12.0,
        unitLabel = "dp", note = "卡片投影高度。0 = 纯扁平，靠描边分层",
    )
    val bubbleStyle = UiToken(
        "bubbleStyle", "气泡形状", TokenType.ENUM, "rounded",
        note = "rounded（全圆角）/ square（直角）/ tail（带小尾巴，像 QQ）",
    )
    val avatarShape = UiToken(
        "avatarShape", "头像形状", TokenType.ENUM, "circle",
        note = "circle（正圆）/ rounded（圆角方）/ square（直角）",
    )
    val bgStyle = UiToken(
        "bgStyle", "页面背景", TokenType.ENUM, "aurora",
        note = "aurora（内置极光）/ flat（纯色）/ gradient（双色渐变）/ wallpaper（主题壁纸）",
    )

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
        tabBarShape, tabBarMargin, tabBarLift, tabBarBlur, tabBarTint, tabBarIndicator, tabBarWidth, uiKit,
        glassBarLiquid, glassBarOutline, glassBarHighlight, glassBarSlotWidth,
        glassBarBottomBlur, glassBarIconOnly, glassBarLabelSize, dynamicColor, dockImpl,
        // 字体
        fontFamily,
        // 壁纸
        wallpaperFile, wallpaperDim, wallpaperBlur, wallpaperScale,
        // 风格
        cardStyle, cardElevation, bubbleStyle, avatarShape, bgStyle,
    )

    val byKey: Map<String, UiToken> = ALL.associateBy { it.key }

    /** ENUM 类型的允许值。名单外的值会被忽略并回退到默认。 */
    val enumValues: Map<String, Set<String>> = mapOf(
        tabStyle.key to setOf("glass", "plain", "hidden"),
        navTitleAlign.key to setOf("center", "leading"),
        tabBarShape.key to setOf("capsule", "rounded", "square"),
        tabBarIndicator.key to setOf("capsule", "underline", "dot", "none"),
        tabBarWidth.key to setOf("compact", "full"),
        uiKit.key to setOf("auto", "library", "compose"),
        dockImpl.key to setOf("liquidTabBar", "liquidDock"),
        fontFamily.key to setOf("system", "sans", "serif", "mono", "cursive"),
        cardStyle.key to setOf("flat", "glass", "gradient", "neu"),
        bubbleStyle.key to setOf("rounded", "square", "tail"),
        avatarShape.key to setOf("circle", "rounded", "square"),
        bgStyle.key to setOf("aurora", "flat", "gradient", "wallpaper"),
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
            TokenType.TEXT -> {
                // 自由文本只做长度限制。文件名这类东西在真正用到的��方
                // 还会再过一次路径校验（见 WallpaperLoader）。
                if (v.length > 200) v.take(200) else v
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
    /**
     * 二次元。
     *
     * 目标不是"把颜色变粉"就完事 —— 二次元观感来自**一组特征同时出现**：
     * 粉紫主色、大圆角、带尾巴的气泡、卡片带厚度、背景用渐变。
     * 只改一个主色会显得只是"换了个色"。
     *
     * 气泡用 tail 形状、卡片用 neu（双层描边拟物），这两项是二次元最认得出的特征。
     */
    val ANIME = Preset(
        id = "anime",
        name = "二次元",
        description = "粉紫主色、大圆角、带尾巴气泡、卡片带厚度",
        tokens = mapOf(
            "accent" to "#FF6FA5",
            "accentOn" to "#FFFFFF",
            "success" to "#3FC1A0",
            "warning" to "#FFA53B",
            "danger" to "#FF5B6E",
            "groupedBackground" to "#FFF3F8",
            "cardBackground" to "#FFFFFF",
            "label" to "#3A2436",
            "secondaryLabel" to "#9A7189",
            "tertiaryLabel" to "#BE9AAC",
            "separator" to "#1AFF6FA5",
            "fill" to "#22FF8FC4",
            "fillSecondary" to "#12FF8FC4",
            "barBackground" to "#F7FFFFFF",
            "barBorder" to "#26FF6FA5",
            "bubbleUser" to "#FF6FA5",
            "bubbleUserText" to "#FFFFFF",
            "bubbleAi" to "#FFFFFF",
            "bubbleAiBorder" to "#2AFF6FA5",
            "composerBackground" to "#FFFFFF",
            "codeBackground" to "#FFF0F6",
            "selection" to "#FF6FA5",
            // 二次元的三个特征：圆、软、有厚度。
            "cardCorner" to "24",
            "controlCorner" to "18",
            "bubbleCorner" to "22",
            "sheetCorner" to "28",
            "cardStyle" to "neu",
            "cardElevation" to "3",
            "bubbleStyle" to "tail",
            "avatarShape" to "circle",
            "bgStyle" to "gradient",
            "tabStyle" to "glass",
            "tabBarShape" to "capsule",
            "tabBarIndicator" to "capsule",
            "showTabBarLabels" to "true",
        ),
        darkTokens = mapOf(
            "accent" to "#FF8FB8",
            "accentOn" to "#2A1622",
            "groupedBackground" to "#160F14",
            "cardBackground" to "#241A21",
            "label" to "#F7EAF1",
            "secondaryLabel" to "#BFA0B2",
            "tertiaryLabel" to "#8F7385",
            "separator" to "#26FF8FC4",
            "barBackground" to "#E620161E",
            "barBorder" to "#30FF8FC4",
            "bubbleUser" to "#FF8FB8",
            "bubbleUserText" to "#2A1622",
            "bubbleAi" to "#241A21",
            "bubbleAiBorder" to "#2AFF8FC4",
            "codeBackground" to "#1E151B",
        ),
    )

    /**
     * QQ 经典。
     *
     * 2010 年代 QQ 的观感：蓝白、通栏直角底栏、小圆角、无渐变。
     * 那时候的桌面端就是方方正正的列表加一个蓝色高亮条。
     *
     * 和现在的 iOS 风是完全相反的方向，所以做成一个预设正好 ——
     * 有人就喜欢这种"复古桌面端"的味道。
     */
    val QQ_CLASSIC = Preset(
        id = "qq-classic",
        name = "QQ 经典",
        description = "蓝白配色、直角卡片、通栏底栏、小圆角",
        tokens = mapOf(
            "accent" to "#12B7F5",
            "accentOn" to "#FFFFFF",
            "success" to "#2BC55B",
            "warning" to "#FF9D2E",
            "danger" to "#F2554A",
            "groupedBackground" to "#F2F6FB",
            "cardBackground" to "#FFFFFF",
            "label" to "#1A2733",
            "secondaryLabel" to "#7A8B99",
            "tertiaryLabel" to "#A8B5C0",
            "separator" to "#1F1A2733",
            "fill" to "#1F12B7F5",
            "fillSecondary" to "#0F12B7F5",
            "barBackground" to "#FFFFFFFF",
            "barBorder" to "#201A2733",
            "bubbleUser" to "#12B7F5",
            "bubbleUserText" to "#FFFFFF",
            "bubbleAi" to "#FFFFFF",
            "bubbleAiBorder" to "#201A2733",
            "composerBackground" to "#FFFFFF",
            "codeBackground" to "#F5F8FC",
            "selection" to "#12B7F5",
            // 直角、小圆角 —— 桌面端的方正感。
            "cardCorner" to "4",
            "controlCorner" to "3",
            "bubbleCorner" to "6",
            "sheetCorner" to "8",
            "cardStyle" to "flat",
            "cardElevation" to "0",
            "bubbleStyle" to "square",
            "avatarShape" to "rounded",
            "bgStyle" to "flat",
            "tabStyle" to "plain",
            "tabBarShape" to "square",
            "tabBarWidth" to "full",
            "tabBarIndicator" to "underline",
            "tabBarMargin" to "0",
            "tabBarLift" to "0",
            "showTabBarLabels" to "true",
            "navTitleAlign" to "center",
        ),
        darkTokens = mapOf(
            "accent" to "#35C3F8",
            "accentOn" to "#06222C",
            "groupedBackground" to "#111A21",
            "cardBackground" to "#1A242C",
            "label" to "#E8EFF5",
            "secondaryLabel" to "#93A5B3",
            "tertiaryLabel" to "#6B7C89",
            "barBackground" to "#F01A242C",
            "barBorder" to "#28FFFFFF",
            "bubbleUser" to "#35C3F8",
            "bubbleUserText" to "#06222C",
            "bubbleAi" to "#1A242C",
            "bubbleAiBorder" to "#28FFFFFF",
            "codeBackground" to "#151E25",
        ),
    )

    val ALL: List<Preset> = listOf(EARTH, AURORA, PAPER, NIGHT, ANIME, QQ_CLASSIC)

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

/**
 * theme.css：用 CSS 自定义属性写主题令牌。
 *
 * 只认 `--令牌名: 值;`，不实现选择器/层叠/媒体查询 —— 这是"用熟悉的语法写令牌"，
 * 不是浏览器。深色令牌用 `--dark-accent` 这类前缀。
 */
object CssTheme {

    private val VAR = Regex("--([a-zA-Z0-9-]+)\\s*:\\s*([^;]+);")

    fun parse(text: String): Pair<Map<String, String>, Map<String, String>> {
        val clean = text.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        val light = LinkedHashMap<String, String>()
        val dark = LinkedHashMap<String, String>()
        VAR.findAll(clean).forEach { m ->
            val rawName = m.groupValues[1]
            val value = m.groupValues[2].trim().removeSuffix("!important").trim()
            if (value.isEmpty()) return@forEach
            val isDark = rawName.startsWith("dark-")
            val key = camel(if (isDark) rawName.removePrefix("dark-") else rawName)
            if (UiTokens.byKey.containsKey(key)) {
                if (isDark) dark[key] = value else light[key] = value
            }
        }
        return light to dark
    }

    private fun camel(name: String): String =
        name.split('-').filter { it.isNotEmpty() }
            .mapIndexed { i, s -> if (i == 0) s else s.replaceFirstChar { it.uppercaseChar() } }
            .joinToString("")
}
