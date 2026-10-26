package com.localmind.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 插件：把"新功能"和"主程序"解耦。
 *
 * 为什么需要它：
 *
 *  1. **先验证再内置**。一个新能力能不能用、值不值得做进 APK，装上去试过才知道。
 *  2. **让用户自己决定要什么**。全部内置会让 APK 越来越大、默认行为越来越吵。
 *     模型库尤其如此 —— 主程序**不带任何模型**，从哪来由用户决定。
 *  3. **开放性接口**（MCP 等）本质上就是插件：声明它能做什么，然后被主程序调用。
 *
 * 一个插件的形态刻意做得很轻：**一个 zip + 一份 JSON 清单**。
 * 不做代码加载（DexClassLoader）：那会让"装个插件"变成"装个后门"，
 * 而现有和可预见的能力都只需要"声明 + 数据"。
 */
@Serializable
data class PluginManifest(
    /** 稳定唯一 id，建议反向域名风格。 */
    val id: String,
    val name: String,
    val version: String = "1.0.0",
    val author: String = "",
    val description: String = "",
    /** 这个插件提供哪些能力，见 [PluginAbilities]。 */
    val abilities: List<String> = emptyList(),
    /**
     * 声明它需要什么。
     *
     * 用来在安装前就告诉用户"这东西要联网 / 要读文件"，而不是装完才发现。
     */
    val requires: List<String> = emptyList(),
    /** 任意键值参数，由具体能力自己解释。 */
    val config: Map<String, String> = emptyMap(),
    /** 清单格式版本，将来改字段时用。 */
    val manifestVersion: Int = 1,
) {
    val isValid: Boolean
        get() = id.isNotBlank() && name.isNotBlank() && abilities.isNotEmpty() &&
            id.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
}

/**
 * 一种插件能力。
 *
 * ## 为什么不是 enum
 *
 * 原来这里是 `enum class PluginAbility`：加一种扩展点就要改枚举、重新编译，
 * 而且消费端到处 `when (ability)`。但**插件类型本来就会长出来更多**
 * （UI 类、接口类…），把"主程序认识哪些能力"写死在枚举里，等于每次扩展都要发版。
 *
 * 现在是一张**注册表**：[PluginAbilities.ALL]。加一种能力 = 往表里加一行，
 * 其余地方（安装、列表、校验、界面）全部自动跟上，因为它们都只读这张表。
 *
 * ## `contentFiles` 的作用
 *
 * 它让"装插件"变成**一个动作**：用户只需要选一个 zip，主程序按清单里声明的
 * 能力去表里查需要读哪些文件。用户不需要（也不该）先判断自己手上的是表情包
 * 还是模型库 —— 那是程序该干的事。
 */
data class PluginAbility(
    /** 清单 `abilities` 里写的稳定 id。 */
    val id: String,
    /** 中文显示名。 */
    val label: String,
    val description: String,
    /**
     * 这种能力需要 zip 里的哪些文件。
     *
     * 末尾一个星号是通配（写成 `assets/` 加星号，即该目录下全部文件）。
     * ⚠️ 注意别在注释里直接写「斜杠加星号」—— Kotlin 的块注释**可嵌套**，
     * 那三个字符会开一个新的注释层，把后面整个文件吃掉。这里踩过一次，
     * 症状是一堆莫名其妙的 "Unclosed comment" 和参数解析错误。
     *
     * 以 `assets/` 开头的条目会被**解压到插件的资源目录**
     * `filesDir/plugins/<pluginId>/assets/`，其余只作为文本读进内存。
     */
    val contentFiles: Set<String> = emptySet(),
    /** 消费端是否已经接好。没接的能力装上了也不会有行为，界面上要如实说。 */
    val implemented: Boolean = false,
)

/**
 * 主程序认识的全部能力。
 *
 * 这是**白名单**：插件只能声明这张表里的 id，所以"装个插件"永远不会让应用
 * 去执行它不理解的逻辑。新增能力只需要在这里加一行。
 */
object PluginAbilities {

    /** 资源目录的通配写法。**不要**写成「斜杠加星号」的字面量，见 contentFiles 的说明。 */
    private const val ASSET_WILDCARD = "assets/"

    val EMOJI_PACK = PluginAbility(
        id = "emoji_pack",
        label = "表情包",
        description = "让 AI 能发表情包（图片梗图或 emoji）",
        contentFiles = setOf("emoji.json", ASSET_WILDCARD + "*"),
        implemented = true,
    )

    val MODEL_LIBRARY = PluginAbility(
        id = "model_library",
        label = "模型库",
        description = "导入一整套模型清单（对话 / 语音 / 生图 / 识图 / 决策）",
        contentFiles = setOf("models.json"),
        implemented = true,
    )

    val MODEL_SOURCE = PluginAbility(
        id = "model_source",
        label = "模型下载源",
        description = "给模型下载增加一个镜像或代理地址",
        implemented = true,
    )

    val REMOTE_PROVIDER = PluginAbility(
        id = "remote_provider",
        label = "云端服务",
        description = "接入一个 OpenAI 兼容的 API 服务",
    )

    val TTS_VOICE = PluginAbility(
        id = "tts_voice",
        label = "语音音色",
        description = "提供离线语音音色（模型跑在本机）",
        contentFiles = setOf("voices.json"),
        implemented = true,
    )

    /**
     * 语音引擎：接到**本机之外**的合成服务（Breeze TTS 2 这类手机跑不动的模型）。
     *
     * 和 [TTS_VOICE] 是互补关系：那个是"把音色模型下载到手机上"，这个是
     * "手机只当客户端，模型跑在 PC / GPU 服务器上"。
     *
     * 内容文件 `tts.json` 里只有连接方式（地址 / 密钥 / 模型名 / 默认演技），
     * 没有权重、没有二进制、不执行代码。
     */
    val TTS_ENGINE = PluginAbility(
        id = "tts_engine",
        label = "语音引擎",
        description = "接入 Breeze TTS 2 等外部语音合成服务（手机跑不动的大模型）",
        contentFiles = setOf("tts.json"),
        implemented = true,
    )

    // 这里曾经有一个 `cloud_credentials`（providers.json）：插件声明"接入了腾讯云"，
    // 密钥由用户填。它唯一的消费者就是腾讯云 TTS，而那整条链路已经删掉了
    // （原因见 VoicePack.kt 文件头）。留着一个没有任何消费端的能力只会误导
    // 插件作者 —— 声明了也不会有任何东西读它。

    val IMAGE_BACKEND = PluginAbility(
        id = "image_backend",
        label = "生图后端",
        description = "接入一个本机或局域网的扩散模型服务",
    )

    /**
     * 原生推理后端（`.so`）。
     *
     * 语音引擎和生图后端都属于这一类。它们的共同点：**很大，但只有一部分人用**。
     * 打进 APK 意味着所有人替少数人付体积 —— 生图后端一家 36 MB，
     * 而只有愿意再下 1.5–6 GB 模型的人用得上。
     *
     * zip 里放 `backends.json`（声明 ABI、文件名、大小、地址）和 `lib/` 下的
     * `.so` 本体。安装时校验 ELF 头（架构对不对）再落到
     * `filesDir/native/<abi>/`，然后 `System.load`。
     */
    val NATIVE_BACKEND = PluginAbility(
        id = "native_backend",
        label = "推理后端",
        description = "提供原生推理库（语音引擎 / 生图后端）",
        contentFiles = setOf("backends.json", "lib/" + "*"),
        implemented = true,
    )

    val WEB_TOOL = PluginAbility(
        id = "web_tool",
        label = "联网工具",
        description = "让 AI 能搜索或抓取网页",
    )

    val MCP_SERVER = PluginAbility(
        id = "mcp_server",
        label = "MCP 服务",
        description = "通过 Model Context Protocol 接入外部工具",
    )

    val PERSONA_PACK = PluginAbility(
        id = "persona_pack",
        label = "人设包",
        description = "一次导入多个人设",
    )

                /**
     * 网页界面：zip 里带 web/index.html（可含 css/js）。
     *
     * 用 WebView 渲染，JS 桥只有四个动作：send / prompt / toast / copy。
     * 适合活动页、可视化面板；主程序不执行网页里的任何原生代码。
     */
    val WEB_SCREEN = PluginAbility(
        id = "web_screen",
        label = "网页界面",
        description = "用 HTML/CSS/JS 写整页，JS 桥可发消息/填输入框/复制",
        contentFiles = setOf("web/index.html"),
        implemented = true,
    )

/**
     * 代码插件：zip 里带一份 classes.dex，安装后由 DexClassLoader 加载。
     *
     * 这是本项目第一次开放**可执行代码**。风险由用户承担，所以安装前会弹一次
     * 明确的确认框；加载失败只会把插件的状态置为 FAILED，不影响主程序。
     *
     * 入口类写在 plugin.json 的 config.entryClass 里，必须实现
     * com.localmind.plugin.PluginEntry。能做的事情见 PluginApi.kt。
     */
    val CODE_PLUGIN = PluginAbility(
        id = "code_plugin",
        label = "代码插件",
        description = "加载 classes.dex，注册动作 / 处理输入（可执行代码，谨慎安装）",
        contentFiles = setOf("classes.dex"),
        implemented = true,
    )

/**
     * 聊天快捷动作：在输入框上方加几个一键提示词。
     *
     * 内容文件 actions.json。动作只有两种语义（直接发送 / 填进输入框），
     * 所以它仍然是可以完整校验的数据，而不是脚本。
     */
    val CHAT_ACTION = PluginAbility(
        id = "chat_action",
        label = "聊天动作",
        description = "在输入框上方加一键提示词动作",
        contentFiles = setOf("actions.json"),
        implemented = true,
    )

/**
     * 界面插件：改整个应用的外观与导航。
     *
     * 能力形态是**声明式令牌**（`ui.json`），不是代码 —— 原因见 [UiTheme]。
     * 能改的包括主色、圆角、字号缩放、标签栏样式与显示哪几个标签页。
     */
    val UI_THEME = PluginAbility(
        id = "ui_theme",
        label = "界面主题",
        description = "自定义主色、圆角、字号、标签栏",
        contentFiles = setOf("ui.json", "theme.css"),
        implemented = true,
    )

    /**
     * 界面布局：改**整个应用的结构**，不只是配色。
     *
     * 内容文件 `layout.json` 能声明：底部有哪几个入口、每个入口进哪一页、
     * 首页由哪些块组成（问候卡 / 快捷入口 / 最近对话 / 状态行）、应用叫什么名字，
     * 并且可以顺带覆盖 [UI_THEME] 那套外观令牌。
     *
     * 和 [UI_THEME] 分开的理由见 [UiLayout] 的文件头：只换配色的插件不该被迫
     * 声明导航，只改导航的插件也不该抄 37 个颜色。同一个 zip 可以同时给两种能力。
     */
    val UI_LAYOUT = PluginAbility(
        id = "ui_layout",
        label = "界面布局",
        description = "改整个应用的导航、首页结构与形态（不只是配色）",
        contentFiles = setOf("layout.json"),
        implemented = true,
    )

    /**
     * 流水线：把"一件事"拆成能单独跑的小步，逐步执行再聚合。
     *
     * 场景就是"模型装不进这台手机"：Breeze TTS 2 跑不了本机、7B 模型没有足够上下文。
     * 内容文件 `pipeline.json` 只声明步骤组合，动作类型由主程序提供
     *（见 [com.localmind.data.PipelineStages]），插件仍然不执行代码。
     */
    val PIPELINE = PluginAbility(
        id = "pipeline",
        label = "分步流水线",
        description = "把长文本/大任务拆成多步单独跑，最后聚合（拆句合成、分段理解）",
        contentFiles = setOf("pipeline.json"),
        implemented = true,
    )

    /** 接口类插件：暴露新的 HTTP 端点给外部调用。 */
    val API_ENDPOINT = PluginAbility(
        id = "api_endpoint",
        label = "接口扩展",
        description = "给本机 API 增加自定义端点",
    )

    /** 注册表。顺序即界面上的显示顺序。 */
    val ALL: List<PluginAbility> = listOf(
        EMOJI_PACK, MODEL_LIBRARY, MODEL_SOURCE, TTS_VOICE, TTS_ENGINE,
        NATIVE_BACKEND, REMOTE_PROVIDER, IMAGE_BACKEND, WEB_TOOL, MCP_SERVER,
        PERSONA_PACK, UI_THEME, UI_LAYOUT, CHAT_ACTION, CODE_PLUGIN, WEB_SCREEN, PIPELINE, API_ENDPOINT,
    )

    fun fromId(id: String): PluginAbility? = ALL.firstOrNull { it.id == id }

    /** zip 里的某个路径是不是这种能力要的内容文件。 */
    fun matchesContent(ability: PluginAbility, zipPath: String): Boolean {
        val name = zipPath.substringAfterLast('/')
        return ability.contentFiles.any { pattern ->
            // 通配：以「目录 + 斜杠 + 星号」结尾。这里用 endsWith 判断而不是比较
            // 字面量，避免在源码里出现「斜杠加星号」那三个字符（见 contentFiles 说明）。
            if (pattern.endsWith("*")) {
                zipPath.startsWith(pattern.dropLast(1))
            } else {
                name == pattern
            }
        }
    }

    /** 这个 zip 路径是不是资源文件（要解压到插件目录，而不是读成文本）。 */
    fun isAsset(zipPath: String): Boolean =
        zipPath.startsWith("assets/") || zipPath.contains("/assets/")
}

/** 插件当前的安装状态。 */
@Serializable
enum class PluginState {
    /** 只有清单，还没启用。 */
    INSTALLED,

    /** 启用中。 */
    ENABLED,

    /** 启用过但出错了，主程序已把它停掉。 */
    FAILED,

    /** 用户手动停用。 */
    DISABLED,
}

/** 已安装插件在本地的一条记录。 */
@Serializable
data class InstalledPlugin(
    val manifest: PluginManifest,
    val state: PluginState = PluginState.INSTALLED,
    val installedAt: Long = System.currentTimeMillis(),
    /** 上次出错的原因，用于在界面上解释为什么它被停用了。 */
    val lastError: String = "",
    /** 来源：从 zip 导入 / 手动粘贴 / 内置示例。 */
    val source: String = "import",
    /** zip 里除清单外实际读到了哪些内容文件，用于在界面上如实展示。 */
    val contents: List<String> = emptyList(),
) {
    val isActive: Boolean get() = state == PluginState.ENABLED
}

/**
 * 插件清单的解析与校验。
 *
 * 单独放一个对象而不是塞进 Storage：解析要给出**人能看懂的失败原因**
 * （"缺少 id"、"abilities 为空"），而导入失败时用户最需要的就是这句话。
 */
object PluginManifests {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
    }

    /** 解析结果：要么得到清单，要么得到一句能直接显示给用户的错误。 */
    sealed interface ParseResult {
        data class Ok(val manifest: PluginManifest) : ParseResult
        data class Failed(val reason: String) : ParseResult
    }

    fun parse(text: String): ParseResult {
        if (text.isBlank()) return ParseResult.Failed("内容是空的")
        val manifest = runCatching { json.decodeFromString<PluginManifest>(text) }.getOrElse { t ->
            return ParseResult.Failed("不是合法的插件清单：${t.message?.take(120) ?: "解析失败"}")
        }
        if (manifest.id.isBlank()) return ParseResult.Failed("缺少 id")
        if (!manifest.isValid) {
            return ParseResult.Failed(
                "清单不完整：name=${manifest.name.ifBlank { "空" }}，" +
                    "abilities=${manifest.abilities.size} 个" +
                    if (!manifest.id.all { it.isLetterOrDigit() || it in ".-_" }) {
                        "；id 只能包含字母、数字、点、短横线和下划线"
                    } else {
                        ""
                    }
            )
        }
        val unknown = manifest.abilities.filter { PluginAbilities.fromId(it) == null }
        if (unknown.size == manifest.abilities.size) {
            return ParseResult.Failed(
                "这个插件声明的能力当前版本都不认识：${unknown.joinToString()}。" +
                    "已知能力：${PluginAbilities.ALL.joinToString("、") { it.id }}"
            )
        }
        return ParseResult.Ok(manifest)
    }

    fun encode(manifest: PluginManifest): String =
        json.encodeToString(PluginManifest.serializer(), manifest)

    /** 这个插件需要但当前没被满足的依赖，用于安装前提示。 */
    fun describeRequirements(manifest: PluginManifest): List<String> =
        manifest.requires.map { req ->
            when (req.lowercase()) {
                "network", "internet" -> "需要联网"
                "storage", "files" -> "需要读写文件"
                "microphone" -> "需要麦克风"
                "camera" -> "需要相机"
                else -> "需要：$req"
            }
        }

    /** 一个可直接导入的示例，让用户知道清单长什么样。 */
    val SAMPLE: String = encode(
        PluginManifest(
            id = "com.example.mirror",
            name = "示例：额外模型镜像",
            version = "1.0.0",
            author = "你",
            description = "演示插件清单的写法。启用后会在模型下载源里多一个候选地址。",
            abilities = listOf(PluginAbilities.MODEL_SOURCE.id),
            requires = listOf("network"),
            config = mapOf(
                "baseUrl" to "https://example.com/models/",
                "note" to "把 baseUrl 换成你自己的镜像地址",
            ),
        )
    )
}
