package com.localmind.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * iOS system colours (light + dark variants) and the label/separator scale.
 * Values follow Apple's Human Interface Guidelines so the UI reads as "iOS"
 * rather than "Material with round corners".
 */
data class IosPalette(
    val blue: Color,
    val green: Color,
    val indigo: Color,
    val orange: Color,
    val pink: Color,
    val purple: Color,
    val red: Color,
    val teal: Color,
    val yellow: Color,
    val gray: Color,

    /** grouped table background */
    val groupedBackground: Color,
    /** card / cell background */
    val secondaryGroupedBackground: Color,
    /** elevated sheets and nav bars */
    val barBackground: Color,
    val barBorder: Color,
    val separator: Color,

    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val fill: Color,
    val fillSecondary: Color,

    /**
     * 主色之上的文字/图标色（按钮、开关滑块、用户气泡里的字）。
     *
     * 为什么必须有这一项：原来全仓库写死 `Color.White`。一个浅色主色
     * （比如米黄 `#FFE066`）会让按钮上的白字**完全看不见**，而主题作者
     * 没有任何办法修 —— 这是上一版设计里最实际的一个洞。
     */
    val onAccent: Color,

    /** 标签栏选中指示器 / 列表选中高亮。允许独立于主色。 */
    val selection: Color,

    // ---------------------------------------------------------------- 聊天区
    //
    // 单独开这几项（而不是直接复用 blue / secondaryGroupedBackground）是因为
    // "改气泡"和"改主色/卡片色"经常不是同一个决定：有人只想要黑色气泡配白字，
    // 但按钮还是蓝的。默认值仍然跟随那两项，所以只改主色时气泡会一起变。

    /** 用户气泡底色。 */
    val bubbleUser: Color,
    /** 用户气泡里的文字色。 */
    val bubbleUserText: Color,
    /** AI 气泡底色。 */
    val bubbleAi: Color,
    /** AI 气泡描边色。 */
    val bubbleAiBorder: Color,
    /** 底部输入区底色。 */
    val composerBackground: Color,
    /** 代码块底色（聊天气泡里的 ``` 段）。 */
    val codeBackground: Color,

    val isDark: Boolean,
) {
    /** colour picker used by model avatars, tags, etc. */
    val accents: List<Color> get() = listOf(blue, indigo, purple, pink, orange, green, teal, yellow, red)

    /**
     * 警示/说明文字用的琥珀色。
     *
     * [orange] 是 iOS 的系统橙（#FF9500），当图标没问题，但当**正文**放在
     * 白底上只有约 2.2:1 的对比度 —— 实测在模拟器上「注意事项」那几行几乎
     * 融进卡片里。这个值只压暗不换色相，仍是同一种"警告"语义。
     */
    val warningText: Color
        get() = if (isDark) Color(0xFFFFB454) else Color(0xFFA25000)
}

/**
 * Refined palette.
 *
 * Tuned for a cleaner look than a straight iOS colour dump: the grouped
 * background stays very light, cards are pure white with a hairline border, and
 * accents are reserved for state (selected tab, primary action, status) instead
 * of being sprinkled over every icon. Dark mode is true black, OLED friendly.
 */
val LightPalette = IosPalette(
    blue = Color(0xFF0A84FF),
    green = Color(0xFF34C759),
    indigo = Color(0xFF5E5CE6),
    orange = Color(0xFFFF9500),
    pink = Color(0xFFFF375F),
    purple = Color(0xFFAF52DE),
    red = Color(0xFFFF3B30),
    teal = Color(0xFF32ADE6),
    yellow = Color(0xFFFFCC00),
    gray = Color(0xFF8E8E93),
    groupedBackground = Color(0xFFF7F7F9),
    secondaryGroupedBackground = Color.White,
    barBackground = Color(0xF7FFFFFF),
    barBorder = Color(0x14000000),
    separator = Color(0x14000000),
    label = Color(0xFF0B0B0F),
    secondaryLabel = Color(0x8A3C3C43),
    tertiaryLabel = Color(0x4D3C3C43),
    fill = Color(0x1A787880),
    fillSecondary = Color(0x0F787880),
    onAccent = Color.White,
    selection = Color(0xFF0A84FF),
    bubbleUser = Color(0xFF0A84FF),
    bubbleUserText = Color.White,
    bubbleAi = Color.White,
    bubbleAiBorder = Color(0x14000000),
    composerBackground = Color(0x1A787880),
    codeBackground = Color(0xFFF2F2F7),
    isDark = false,
)

val DarkPalette = IosPalette(
    blue = Color(0xFF0A84FF),
    green = Color(0xFF30D158),
    indigo = Color(0xFF5E5CE6),
    orange = Color(0xFFFF9F0A),
    pink = Color(0xFFFF375F),
    purple = Color(0xFFBF5AF2),
    red = Color(0xFFFF453A),
    teal = Color(0xFF64D2FF),
    yellow = Color(0xFFFFD60A),
    gray = Color(0xFF8E8E93),
    groupedBackground = Color(0xFF000000),
    secondaryGroupedBackground = Color(0xFF141416),
    barBackground = Color(0xF2141416),
    barBorder = Color(0x1FFFFFFF),
    separator = Color(0x1FFFFFFF),
    label = Color(0xFFF5F5F7),
    secondaryLabel = Color(0x99EBEBF5),
    tertiaryLabel = Color(0x4DEBEBF5),
    fill = Color(0x2E787880),
    fillSecondary = Color(0x1A787880),
    onAccent = Color.White,
    selection = Color(0xFF0A84FF),
    bubbleUser = Color(0xFF0A84FF),
    bubbleUserText = Color.White,
    bubbleAi = Color(0xFF141416),
    bubbleAiBorder = Color(0x1FFFFFFF),
    composerBackground = Color(0x2E787880),
    codeBackground = Color(0xFF0D0D0F),
    isDark = true,
)

val LocalIosPalette = staticCompositionLocalOf { LightPalette }

/**
 * iOS-ish type ramp (SF-like metrics, platform sans).
 *
 * Standalone objects rather than a [Typography] subclass, so the ramp can carry
 * iOS-only sizes such as `.largeTitle` while still offering a Material3
 * [Typography] for the Material components that need one.
 */
/**
 * iOS 字号阶梯。
 *
 * ## 为什么是 `data class` + `LocalIosTypography`，而不是一个 object
 *
 * 原来这里是个 `object IosTypography`，全仓库 326 处写死引用它。结果是
 * **界面插件根本改不动字号** —— `fontScale` / `bodySize` 令牌解析出来了、
 * 传进了 `MaterialTheme(typography = …)`，但没有任何组件读
 * `MaterialTheme.typography`（实测引用点 = 0），所有文字都直接取
 * `IosTheme.typography.body` 这种常量。于是"字号令牌"是个装饰品。
 *
 * 改成通过 CompositionLocal 提供之后，`IosTheme.typography.body` 会跟着
 * 令牌走，326 处调用点一行不用改逻辑。
 *
 * ## 缩放规则
 *
 *  * `fontScale`：整体倍率，夹在 0.8–1.4（见 [UiTokens.fontScale]）
 *  * `bodySize`：**只作用于正文**（body / callout / subheadline）。
 *    iOS 的正文是 17pt，有些语言或有些用户想要 18–19。如果让它等比放大
 *    所有样式，标题会大得离谱。
 *
 * 只缩 `fontSize` / `lineHeight`，不动字重和字距：整体放大时字距理论上也该
 * 跟着比例走，但那要逐项算；只缩字号在这个区间里观感已经够好，而且绝不会算错。
 */
data class IosTypographySet(
    val largeTitle: TextStyle = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 41.sp, letterSpacing = 0.37.sp),
    val title1: TextStyle = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp),
    val title2: TextStyle = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    val title3: TextStyle = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 25.sp),
    val headline: TextStyle = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.41).sp),
    val body: TextStyle = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.41).sp),
    val callout: TextStyle = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 21.sp, letterSpacing = (-0.32).sp),
    val subheadline: TextStyle = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.24).sp),
    val footnote: TextStyle = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = (-0.08).sp),
    val caption1: TextStyle = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    val caption2: TextStyle = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 13.sp, letterSpacing = 0.07.sp),
) {
    /**
     * 按令牌缩放。倍数已经被 [UiThemes.resolve] 夹过，这里再夹一次是防御性的 ——
     * 这个函数也可能被非插件来源调用。
     */
    fun scaled(scale: Float, bodyBoost: Float, baseBodySize: Float): IosTypographySet {
        val s = scale.coerceIn(0.8f, 1.4f)
        // bodySize 是**绝对字号**，换算成相对基准 17pt 的倍率。
        val b = if (baseBodySize > 0f) (bodyBoost / baseBodySize).coerceIn(0.8f, 1.4f) else 1f
        if (s == 1f && b == 1f) return this

        fun TextStyle.sz(k: Float) = copy(fontSize = fontSize * k, lineHeight = lineHeight * k)
        // 正文三档吃 bodySize，其余只吃整体倍率。
        return copy(
            largeTitle = largeTitle.sz(s),
            title1 = title1.sz(s),
            title2 = title2.sz(s),
            title3 = title3.sz(s),
            headline = headline.sz(s),
            body = body.sz(s * b),
            callout = callout.sz(s * b),
            subheadline = subheadline.sz(s * b),
            footnote = footnote.sz(s),
            caption1 = caption1.sz(s),
            caption2 = caption2.sz(s),
        )
    }

    /**
     * 换字体族。
     *
     * 二次元主题最明显的一步就是换字体。这里用**系统自带**的字体族，
     * 不打包字体文件 —— 少几 MB，而且不用操心授权。
     *
     * 用 [FontFamily.Monospace] 那种等宽时要注意：中文在等宽字体里
     * 字宽是按半角算的，整页会显得很挤。所以等宽只适合"代码块"，
     * 不适合当界面主字体 —— 但那是用户的自由，主题作者自己负责效果。
     */
    fun withFontFamily(family: FontFamily): IosTypographySet {
        if (family == FontFamily.SansSerif) return this
        fun TextStyle.ff() = copy(fontFamily = family)
        return copy(
            largeTitle = largeTitle.ff(),
            title1 = title1.ff(),
            title2 = title2.ff(),
            title3 = title3.ff(),
            headline = headline.ff(),
            body = body.ff(),
            callout = callout.ff(),
            subheadline = subheadline.ff(),
            footnote = footnote.ff(),
            caption1 = caption1.ff(),
            caption2 = caption2.ff(),
        )
    }

    /** Material3 counterpart used by MaterialTheme. */
    val material: Typography
        get() = Typography(
            headlineLarge = largeTitle,
            headlineMedium = title1,
            headlineSmall = title2,
            titleLarge = title3,
            titleMedium = headline,
            titleSmall = subheadline,
            bodyLarge = body,
            bodyMedium = callout,
            bodySmall = footnote,
            labelLarge = headline,
            labelMedium = caption1,
            labelSmall = caption2,
        )
}

/**
 * 默认字号阶梯。**不要直接在 composable 里用它** ——
 * 那样插件改不动字号。用 `IosTheme.typography`。
 *
 * 保留这个名字是因为有一处非组合上下文（`IosTheme.kt` 自己）要用到默认值。
 */
val DefaultIosTypography = IosTypographySet()

/** Corner radii, spacing and control sizes matching iOS conventions. */
/**
 * 尺寸令牌。
 *
 * 从 `object` 改成 `data class` 是为了让**界面插件能改它** ——
 * object 的 `val` 在运行时改不了，而插件是运行时装上的。
 * 默认值就是原来那一套 iOS 尺寸，所以不装插件时观感完全不变。
 *
 * 读它请用 `IosTheme.metrics`（走 CompositionLocal），不要直接引用
 * [IosMetrics] 的默认实例，否则插件覆盖不到。
 */
data class IosMetrics(
    val cardCorner: Dp = 12.dp,
    val sheetCorner: Dp = 16.dp,
    val controlCorner: Dp = 10.dp,
    val pillCorner: Dp = 999.dp,
    val pagePadding: Dp = 16.dp,
    val cellMinHeight: Dp = 44.dp,
    /** 聊天气泡圆角。和 `cardCorner` 分开，因为气泡通常要更圆。 */
    val bubbleCorner: Dp = 18.dp,
    val tabBarHeight: Dp = 62.dp,
    val navBarHeight: Dp = 44.dp,
    /** 导航栏/标签栏描边粗细。0 = 不要描边。 */
    val barBorderWidth: Dp = 0.5.dp,
    /** 标签栏图标边长。 */
    val tabIconSize: Dp = 23.dp,
)

/**
 * 应用主题。
 *
 * @param tokens 界面插件解析出的外观令牌（[LocalUiTokens]）。传空 Map 就是
 *   纯 iOS 原色，和没有插件系统时一模一样 —— 这是刻意的：不装插件的人
 *   不该察觉这个系统的存在。
 */
@Composable
fun LocalMindTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    tokens: Map<String, String> = emptyMap(),
    themeState: ThemeState = ThemeState(tokens = tokens),
    /** 当前主题包的 assets 目录。壁纸这类"包内文件"靠它定位。null = 没有。 */
    themeAssetsDir: java.io.File? = null,
    content: @Composable () -> Unit,
) {
    val darkTheme = darkTheme

    // ---- 界面插件（插件提供的声明式令牌）----
    //
    // 这里是"插件能改整个应用外观"的接入点。令牌在 rememberPluginUiTokens 里
    // 已经解析、夹过区间，所以这里只管取值，不需要再校验。
    val tokens = tokens

    fun tokColor(key: String, fallback: Color): Color =
        tokens[key]?.let { runCatching { parseHexColor(it) }.getOrNull() } ?: fallback

    fun tokFloat(key: String, fallback: Float): Float =
        tokens[key]?.toFloatOrNull() ?: fallback

    fun tokBool(key: String, fallback: Boolean): Boolean =
        tokens[key]?.toBooleanStrictOrNull() ?: fallback

    val base = if (darkTheme) DarkPalette else LightPalette

    // 只覆盖**声明了**的令牌；没声明的保持 iOS 原色。
    // 这样"只改一个主色"的主题不会意外把其它颜色也洗掉。
    //
    // ⚠️ 这里的每一条都必须有对应消费端。加一行进来之前先 grep 一下
    // 那个字段有没有人读 —— 上一版就是加了一堆没人读的令牌，
    // 结果"改了没反应"，然后被当成玄学（见 UiTheme.kt 文件头）。
    val accent = tokColor("accent", base.blue)
    val onAccent = tokColor("accentOn", base.onAccent)
    val palette = base.copy(
        blue = accent,
        green = tokColor("success", base.green),
        red = tokColor("danger", base.red),
        orange = tokColor("warning", base.orange),

        groupedBackground = tokColor("groupedBackground", base.groupedBackground),
        secondaryGroupedBackground = tokColor("cardBackground", base.secondaryGroupedBackground),
        barBackground = tokColor("barBackground", base.barBackground),
        barBorder = tokColor("barBorder", base.barBorder),
        separator = tokColor("separator", base.separator),

        label = tokColor("label", base.label),
        secondaryLabel = tokColor("secondaryLabel", base.secondaryLabel),
        tertiaryLabel = tokColor("tertiaryLabel", base.tertiaryLabel),

        fill = tokColor("fill", base.fill),
        fillSecondary = tokColor("fillSecondary", base.fillSecondary),

        onAccent = onAccent,
        selection = tokColor("selection", accent),

        // 聊天区。这四项允许"独立于主色/卡片色"，但默认跟随它们 ——
        // 这样"只改 accent"的主题，用户气泡也跟着变（见 UiThemes.resolve 的推导）。
        bubbleUser = tokColor("bubbleUser", accent),
        bubbleUserText = tokColor("bubbleUserText", onAccent),
        bubbleAi = tokColor("bubbleAi", tokColor("cardBackground", base.secondaryGroupedBackground)),
        bubbleAiBorder = tokColor("bubbleAiBorder", tokColor("separator", base.separator)),
        composerBackground = tokColor("composerBackground", tokColor("fill", base.fill)),
        codeBackground = tokColor("codeBackground", base.codeBackground),
    )

    // 尺寸也要走令牌。用 data class + CompositionLocal 而不是改常量 ——
    // 那些是 `val`，运行时改不了，而主题是运行时装上的。
    val metrics = IosMetrics(
        cardCorner = tokFloat("cardCorner", 12f).dp,
        controlCorner = tokFloat("controlCorner", 10f).dp,
        sheetCorner = tokFloat("sheetCorner", 16f).dp,
        bubbleCorner = tokFloat("bubbleCorner", 18f).dp,
        pagePadding = tokFloat("pagePadding", 16f).dp,
        navBarHeight = tokFloat("navBarHeight", 44f).dp,
        tabBarHeight = tokFloat("tabBarHeight", 62f).dp,
        barBorderWidth = tokFloat("barBorderWidth", 0.5f).dp,
        tabIconSize = tokFloat("tabIconSize", 23f).dp,
    )

    // 字号。这两行是"界面插件能改字号"的**唯一入口** ——
    // 之前 fontScale 传给了 MaterialTheme，但没有任何组件读它，等于没接。
    //
    // 字体族接在缩放之后：顺序无所谓，两者都是纯变换，可以叠加。
    val typography = DefaultIosTypography.scaled(
        scale = tokFloat("fontScale", 1f),
        bodyBoost = tokFloat("bodySize", 17f),
        baseBodySize = 17f,
    ).withFontFamily(fontFamilyFor(tokens["fontFamily"] ?: "system"))

    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = palette.blue,
            onPrimary = palette.onAccent,
            background = palette.groupedBackground,
            onBackground = palette.label,
            surface = palette.secondaryGroupedBackground,
            onSurface = palette.label,
            surfaceVariant = palette.fill,
            secondary = palette.green,
            error = palette.red,
        )
    } else {
        lightColorScheme(
            primary = palette.blue,
            onPrimary = palette.onAccent,
            background = palette.groupedBackground,
            onBackground = palette.label,
            surface = palette.secondaryGroupedBackground,
            onSurface = palette.label,
            surfaceVariant = palette.fill,
            secondary = palette.green,
            error = palette.red,
        )
    }

    CompositionLocalProvider(
        LocalIosPalette provides palette,
        LocalIosMetrics provides metrics,
        LocalIosTypography provides typography,
        LocalUiTokens provides tokens,
        LocalUiTheme provides themeState,
        LocalThemeAssetsDir provides themeAssetsDir,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography.material,
            content = content,
        )
    }
}

/**
 * 令牌里的字体族名 → Compose 的 [FontFamily]。
 *
 * 只用**系统自带**的字体族：不用下载、不用打包、不涉及授权。
 * 想要更"二次元"的字（圆体、卡通体）得自己装字体到系统，主题只能选这些。
 *
 * 注意 `cursive`（手写体）在中文环境里通常会回退到默认字体，
 * 因为 Android 的手写体族不含中文字形 —— 所以它对中文界面基本没效果，
 * 但对英文界面（应用标题、英文菜单）还是有区别的。
 */
fun fontFamilyFor(key: String): FontFamily = when (key.lowercase()) {
    "sans" -> FontFamily.SansSerif
    "serif" -> FontFamily.Serif
    "mono" -> FontFamily.Monospace
    "cursive" -> FontFamily.Cursive
    else -> FontFamily.Default
}

/**
 * `#RGB` / `#RRGGBB` / `#AARRGGBB` → Color。
 *
 * 不是 `internal`：组件层（主题预览等）也要用它把令牌还原成颜色。
 */
fun parseHexColor(s: String): Color {
    val hex = s.removePrefix("#")
    val v = hex.toLong(16)
    return when (hex.length) {
        3 -> {
            val r = ((v shr 8) and 0xF).let { it * 16 + it }
            val g = ((v shr 4) and 0xF).let { it * 16 + it }
            val b = (v and 0xF).let { it * 16 + it }
            Color(0xFF000000 or (r shl 16) or (g shl 8) or b)
        }
        6 -> Color(0xFF000000 or v)
        8 -> Color(v)
        else -> Color.Unspecified
    }
}

/** 当前生效的界面令牌。空 Map = 没装界面插件，一切用 iOS 原色。 */
val LocalUiTokens = staticCompositionLocalOf<Map<String, String>> { emptyMap() }

/**
 * 整个应用生效的界面主题。
 *
 * 比 [LocalUiTokens] 多的是**派生字段**：标签栏放哪几个、初始标签、应用标题。
 * 这些不是颜色，但同样"只有主题知道"，所以放在一起，避免再开一条到处传的路径。
 *
 * @param themeName 生效的主题包名（空 = 内置外观）。主题页拿它显示"当前：XX"。
 */
data class ThemeState(
    val tokens: Map<String, String> = emptyMap(),
    val tabs: List<String> = listOf("chat", "models", "me"),
    val startTab: String = "",
    val appName: String = "",
    val themeName: String = "",
    /**
     * 生效的主题包的插件 id。空 = 没装界面插件。
     *
     * 壁纸这类"包内文件"靠它定位磁盘目录：`filesDir/plugins/<pluginId>/assets`。
     */
    val pluginId: String = "",
)

/**
 * 当前主题的派生信息（标签栏 / 标题 / 初始标签）。
 *
 * 只读这个拿不到令牌 —— 要令牌用 [LocalUiTokens]，要颜色用 [IosTheme.palette]。
 */
val LocalUiTheme = staticCompositionLocalOf { ThemeState() }

/**
 * 当前主题包的**资源目录**（`filesDir/plugins/<pluginId>/assets`）。
 *
 * 壁纸、图标这类"主题包自带的文件"要靠它找到磁盘上的位置。
 * 为 null 表示没装界面插件（或这个主题包没带资源）——调用方要能优雅退回。
 *
 * 用 [staticCompositionLocalOf] 而不是 [compositionLocalOf]：它不跟踪读取，
 * 只在**提供方**变化时通知所有订阅者。这正好是我们要的 —— 换主题包时
 * 整体换一次，而不是每个读它的地方都单独订阅。
 */
val LocalThemeAssetsDir = staticCompositionLocalOf<java.io.File?> { null }

/**
 * ⚠️ 这个函数已经**删除**，别再加回来。
 *
 * 它原来是"界面层唯一需要调的东西"，但它是错的：
 *
 *  1. **它的 `remember` 键不是 Compose 状态。** `pluginRevision` /
 *     `overrideRevision` 是 LocalMindApp 上的普通 `@Volatile Int`。在
 *     `remember(...)` 的**参数位置**读它们只发生一次 —— 读普通变量不会建立
 *     任何订阅，所以换主题/改覆盖之后**界面永远不会重算**。
 *  2. **它和 AppViewModel 里的 `_uiTokens` 是两条独立的解析路径**，两份结果
 *     当然会分叉，而界面上画的到底哪一份要靠猜。
 *
 * 现在只有一条路径：`AppViewModel.refreshUiTheme()` 算出 [ThemeState]，
 * 界面通过 `viewModel.uiThemeState`（StateFlow）收集 —— StateFlow 会触发重组，
 * 所以它是真的生效。见 `AppViewModel._uiThemeState` 的说明。
 *
 * 保留这个名字做记念没意义，所以这里直接不提供替代品：要令牌就读
 * [LocalUiTokens]（由 [LocalMindTheme] 提供），要标签栏/标题就读 [LocalUiTheme]。
 */

/** 当前生效的尺寸。可被界面插件覆盖。 */
val LocalIosMetrics = staticCompositionLocalOf { IosMetrics() }

/** 当前生效的字号阶梯。可被界面插件覆盖。 */
val LocalIosTypography = staticCompositionLocalOf { DefaultIosTypography }

/** Shortcut used everywhere: `val ios = LocalIosPalette.current`. */
object IosTheme {
    val palette: IosPalette
        @Composable get() = LocalIosPalette.current

    /** 尺寸令牌。用它而不是直接读 `IosMetrics`，否则插件改不动。 */
    val metrics: IosMetrics
        @Composable get() = LocalIosMetrics.current

    /**
     * 字号阶梯。
     *
     * **全应用 326 处文字都用它** —— 这样界面插件的 `fontScale` / `bodySize`
     * 才真的能改到屏幕上，而不是只改一个没人读的 MaterialTheme.typography。
     */
    val typography: IosTypographySet
        @Composable get() = LocalIosTypography.current
}
