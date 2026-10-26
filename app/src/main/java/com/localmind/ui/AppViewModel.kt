package com.localmind.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localmind.LocalMindApp
import com.localmind.data.AiAbilities
import com.localmind.data.ApiSettings
import com.localmind.data.CapabilitySettings
import com.localmind.data.ChatMessage
import com.localmind.data.ChatMode
import com.localmind.data.CompanionState
import com.localmind.data.Conversation
import com.localmind.data.Emotion
import com.localmind.data.GenerationSettings
import com.localmind.data.ImageSettings
import com.localmind.data.LocalModel
import com.localmind.data.ModelMemoryPlan
import com.localmind.data.ModelRouting
import com.localmind.data.Persona
import com.localmind.data.PersonaPresets
import com.localmind.data.UserProfile
import com.localmind.data.RemoteProvider
import com.localmind.data.Role
import com.localmind.data.VoiceSettings
import com.localmind.service.ApiForegroundService
import com.localmind.service.ImageEngine
import com.localmind.service.SelfTest
import com.localmind.util.TextUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Three top-level destinations. Everything else lives *inside* one of them:
 * downloads + network settings under 模型, detailed model routing / TTS / persona
 * under the chat screen's own settings sheet.
 */
enum class Tab(val title: String) {
    /** 插件布局里的「首页」：块式组合页（问候卡 / 快捷入口 / 最近对话 / 状态）。 */
    HOME("首页"),
    CHAT("对话"),
    MODELS("模型"),
    ME("我的"),
    /** 语音与音色整页。默认不是标签，但界面布局插件可以把它放到底栏。 */
    VOICE("语音"),
    /** Breeze TTS 2（外部语音引擎）整页。 */
    BREEZE("Breeze 语音"),
    PLUGINS("插件"),
    IMAGE("生图"),
    /** 分步流水线（拆解 → 逐步执行 → 聚合）。 */
    PIPELINE("流水线"),
    ;

    companion object {
        /**
         * 插件用的页面 id → 标签。
         *
         * 认不出返回 null（而不是抛异常）：插件写错一个页面名的代价应该是
         * "这个入口不显示"，不该是启动就崩。
         */
        fun fromId(id: String): Tab? = when (id.trim().lowercase()) {
            "home" -> HOME
            "chat" -> CHAT
            "models" -> MODELS
            "me", "profile" -> ME
            "voice" -> VOICE
            "breeze", "breeze_tts", "tts" -> BREEZE
            "plugins" -> PLUGINS
            "image", "draw" -> IMAGE
            "pipeline", "stages" -> PIPELINE
            else -> null
        }
    }
}

data class ChatUiState(
    val streaming: Boolean = false,
    val currentModel: String? = null,
    val lastTokensPerSecond: Double = 0.0,
    val draft: String = "",
    val error: String? = null,
    val thinking: Boolean = false,
    /** model is currently inside a chain-of-thought block */
    val reasoning: Boolean = false,
)

/**
 * Context window for the dedicated decision engine.
 *
 * A decision prompt is a short system line plus the choice list plus one user message,
 * and the answer is a single `CALL ...` line — so this never needs to be chat-sized.
 * Keeping it at 1024 (versus the memory planner's much larger chat recommendation) is
 * what makes two resident models affordable.
 */
private const val DECISION_CONTEXT = 1024

/** The router is one short generation; a single thread is enough and costs no RAM. */
private const val DECISION_THREADS = 2

/**
 * AI 对话在用户没写自己的系统提示词时用的那一句。
 *
 * 刻意**很短**：AI 对话的定位是"问事"，它不需要人格、不需要规则清单。
 * 每多一个字都是每轮都要重新 prefill 的成本，直接反映在"第一个字多久出来"上。
 * 身份类的问题（"你是谁"）由界面上那句"本机运行"回答，不必写进提示词。
 */
private const val AI_CHAT_DEFAULT_PROMPT =
    "你是一个直接、准确的助手。用用户的语言简洁回答，需要时用 Markdown。"

/**
 * 「主动来找你说话」的静默门槛：超过这么久没聊，打开对话时它先开口。
 *
 * 8 小时 ≈ "睡了一觉起来"。定得比这短会变成烦人，比这长就等于这个开关没开。
 */
private const val PROACTIVE_AFTER_IDLE_MS = 8 * 60 * 60 * 1000L

/**
 * 每轮带进提示词的历史条数（一轮 = 用户 + 助手两条）。
 *
 * 这个数字是**质量与速度的折中**：历史越长，前缀复用能省的就越多（长历史一旦
 * 进了缓存几乎不花钱），但第一次填充的成本也越大。24 条 ≈ 12 轮对话。
 */
private const val HISTORY_KEEP = 24

/**
 * 历史窗口滑动步长（条）。6 条 = 3 轮。
 *
 * 为什么不能逐条滑：窗口每轮整体前移一格，新提示词和上一轮的公共前缀会在
 * 第一句话就断掉 —— KV cache 全废，等于每轮都把整个历史重算一遍。
 * 按 3 轮一跳，代价变成「每三轮一次完整 prefill」，其余轮次只算新增的那一条。
 */
private const val HISTORY_ALIGN = 6

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val localApp = app as LocalMindApp
    val storage get() = localApp.storage
    val engine get() = localApp.engine

    /** Second engine reserved for the decision router; see [LocalMindApp.decisionEngine]. */
    val decisionEngine get() = localApp.decisionEngine
    val downloader get() = localApp.downloader
    val modelStore get() = localApp.modelStore
    val tts get() = localApp.tts
    val neuralTts get() = localApp.neuralTts
    /** 外部语音引擎客户端（Breeze TTS 2 等，见 [com.localmind.service.BreezeTts]）。 */
    val breezeTts get() = localApp.breeze
    val emoji get() = localApp.emoji
    val voiceDownloader get() = localApp.voiceDownloader
    val stt get() = localApp.stt
    val apiServer get() = localApp.apiServer
    val remoteEngine get() = localApp.remoteEngine
    val companion get() = localApp.companion

    /**
     * 对话模型驱动的消歧与内心思考。
     *
     * 挂在**对话引擎**上而不是决策引擎：看懂一句话需要真正的理解力，小模型做不好；
     * 而且它紧跟在一次对话生成之后，模型已加载、KV 缓存也热着，边际成本很低。
     */
    private val deliberation by lazy { com.localmind.service.Deliberation(localApp.engine) }

    private var generateJob: Job? = null

    /** 「主动来找你说话」那一句的生成任务；一次只允许一个。 */
    private var proactiveJob: Job? = null

    // ------------------------------------------------------------- app state

    private val _tab = MutableStateFlow(Tab.CHAT)
    val tab: StateFlow<Tab> = _tab.asStateFlow()

    private val _showSettings = MutableStateFlow(false)
    val showSettings: StateFlow<Boolean> = _showSettings.asStateFlow()

    private val _showImageStudio = MutableStateFlow(false)
    val showImageStudio: StateFlow<Boolean> = _showImageStudio.asStateFlow()

    /**
     * 「语音与音色」整页（[com.localmind.ui.screen.VoiceScreen]）。
     *
     * 之前聊天页的麦克风按钮和「我的 → 语音与音色」都只打开设置栏，而设置栏里
     * **没有任何语音项** —— 点两下等于什么都没发生。情绪九宫格、语速/音高/音量、
     * 系统音色列表、试听都只长在 VoiceScreen 上，那页却没有任何入口。
     *
     * 做成一个状态位而不是 VoiceScreen 内部的局部变量，是因为它有两个来源：
     * 聊天页的麦克风按钮和「我的」页的导航行。放在这里两边都能开。
     */
    private val _showVoiceScreen = MutableStateFlow(false)

    /** 「Breeze 语音」整页（外部语音引擎）。 */
    private val _showBreeze = MutableStateFlow(false)
    val showBreeze: StateFlow<Boolean> = _showBreeze.asStateFlow()
    val showVoiceScreen: StateFlow<Boolean> = _showVoiceScreen.asStateFlow()

    /**
     * 测试模式整页（[com.localmind.ui.screen.DebugScreen]）。
     *
     * 入口刻意是隐藏的：关于页的版本号**连点三下**。诊断信息对普通用户是噪音，
     * 对内测的人是刚需 —— 藏起来两边都满意。
     */
    private val _showDebug = MutableStateFlow(false)
    val showDebug: StateFlow<Boolean> = _showDebug.asStateFlow()

    /**
     * 「我的」里面那一层整页子页（主题 / 本机 API / 语音与音色 / 关于）有没有开着。
     *
     * ## 为什么需要它
     *
     * 底栏显示与否是**根层**说了算，而那几页是 ProfileScreen 自己用局部状态
     * early-return 出来的 —— 根层看不见，于是它们底部一直留着标签栏：
     * 一个"整页"看起来只换了一半，用户会以为点进去的是浮层。
     *
     * 与其把四个页面状态都搬到 ViewModel（调用点很多），不如让「我的」在打开
     * 任何一个整页时**告诉根层一声**：现在是子页，底栏让位。
     */
    private val _meFullPage = MutableStateFlow(false)
    val meFullPage: StateFlow<Boolean> = _meFullPage.asStateFlow()

    fun setMeFullPage(open: Boolean) {
        if (_meFullPage.value != open) _meFullPage.value = open
    }

    /**
     * 插件页整页。
     *
     * 它原来只是「我的」页里的一个局部状态（`showPlugins`），从别处打不开。
     * 但插件页现在承担一个别处会指过来的职责：**装后端**。生图页发现
     * "后端没装"时要能直接把人送到那儿，而不是让他自己去翻菜单。
     *
     * 提到根层之后，它和语音页/对话设置同级：整页覆盖、有自己的导航栏。
     */
    private val _showPlugins = MutableStateFlow(false)
    val showPlugins: StateFlow<Boolean> = _showPlugins.asStateFlow()

    fun openPlugins(open: Boolean) {
        _showPlugins.value = open
        if (open) _tab.value = Tab.ME
        // 从插件页往外走时把文档页也收起来，免得返回键要按两次。
        if (!open) _showPluginDocs.value = false
    }

    /**
     * 插件开发文档整页。
     *
     * 独立于 [showPlugins] 而不是在插件页里折叠：文档有一屏多，折在列表里
     * 会把"已装插件"挤下去；而且用户常常是**一边看文档一边写插件**，
     * 独立的返回层级更顺手。
     */
    private val _showPluginDocs = MutableStateFlow(false)
    val showPluginDocs: StateFlow<Boolean> = _showPluginDocs.asStateFlow()

    fun openPluginDocs(open: Boolean) {
        _showPluginDocs.value = open
    }

    /**
     * 「本对话设置」整页。
     *
     * 为什么要提到根层，而不是留在聊天页里：
     *
     * 之前它是画在 `ChatThread` 内部的，于是**聊天页自己的导航栏还在下面**，
     * 详情页的导航栏叠在它上面 —— 屏幕上方同时出现两条 `‹ 对话`，看起来就像
     * "点进去又高了一层"，而不是"换了一页"。这是一个纯粹的层级错误：
     * 整页的东西必须在根层渲染，才能盖掉底下的导航栏和标签栏。
     *
     * 现在它和「语音与音色」同级：整页覆盖、有自己的导航栏、返回键先关它。
     */
    private val _showConversationDetail = MutableStateFlow(false)
    val showConversationDetail: StateFlow<Boolean> = _showConversationDetail.asStateFlow()

    /**
     * 待用户确认的歧义点。
     *
     * 由对话模型在回复之后生成：一个追问 + 若干候选含义。用户点一下就把
     * 「这句话到底什么意思」变成**被确认过的语义**，比模型自己猜可靠得多。
     */
    private val _clarification = MutableStateFlow<com.localmind.service.Deliberation.Clarification?>(null)
    val clarification: StateFlow<com.localmind.service.Deliberation.Clarification?> =
        _clarification.asStateFlow()

    /** 模型对当前这轮的判断与内心独白，用于「它在想什么」的展示。 */
    private val _understanding = MutableStateFlow<com.localmind.service.Deliberation.Understanding?>(null)
    val understanding: StateFlow<com.localmind.service.Deliberation.Understanding?> =
        _understanding.asStateFlow()

    private val _models = MutableStateFlow<List<LocalModel>>(emptyList())
    val models: StateFlow<List<LocalModel>> = _models.asStateFlow()

    private val _plan = MutableStateFlow<ModelMemoryPlan?>(null)
    val plan: StateFlow<ModelMemoryPlan?> = _plan.asStateFlow()

    private val _chat = MutableStateFlow(ChatUiState())
    val chat: StateFlow<ChatUiState> = _chat.asStateFlow()

    /**
     * 对话列表。
     *
     * ## 为什么元素类型是一个「equals 按引用算」的 ArrayList
     *
     * `StateFlow` 只在 `newValue != oldValue` 时才发出。而 `ArrayList.equals` 是
     * **逐元素**比较的 —— 于是"就地改一个对话，再把列表重新赋值一次"这种写法，
     * 新旧两个列表装的还是**同一个** Conversation 实例，equals 判定相等，
     * `StateFlow` 一声不吭。
     *
     * 这不是理论问题，它是真机上这一整类症状的唯一原因：
     *   * 「本对话设置」改完必须退出去再进来才生效；
     *   * **流式对话看起来没用** —— token 一直在写进对象，界面却永不重组；
     *   * 头像、标题、单独的模型设置改完不刷新。
     *
     * 两条修法：把每一处写入都改成"造新对象"（要动整条发送链路，风险大），
     * 或者让列表的相等性按引用算（这里，一行）。选后者还有一层理由：列表的
     * **内容本来就是可变对象**，拿内容相等去判断"变没变"从根上就不成立。
     */
    private class ConversationList(items: Collection<Conversation>) :
        ArrayList<Conversation>(items) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    private val _conversations =
        MutableStateFlow<MutableList<Conversation>>(ConversationList(emptyList()))
    val conversations: StateFlow<MutableList<Conversation>> = _conversations.asStateFlow()

    /**
     * 把当前列表原样重发一次，强制界面重组。
     *
     * **就地改完 Conversation 之后必须调它** —— 直接写
     * `_conversations.value = _conversations.value.toMutableList()` 是无效的，
     * 原因见 [ConversationList] 的说明。
     */
    private fun emitConversations() {
        _conversations.value = ConversationList(_conversations.value)
    }

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    private val _settings = MutableStateFlow(GenerationSettings())
    val settings: StateFlow<GenerationSettings> = _settings.asStateFlow()

    private val _voice = MutableStateFlow(VoiceSettings())
    val voice: StateFlow<VoiceSettings> = _voice.asStateFlow()

    private val _api = MutableStateFlow(ApiSettings())
    val api: StateFlow<ApiSettings> = _api.asStateFlow()

    private val _image = MutableStateFlow(ImageSettings())
    val image: StateFlow<ImageSettings> = _image.asStateFlow()

    private val _capabilities = MutableStateFlow(CapabilitySettings())
    val capabilities: StateFlow<CapabilitySettings> = _capabilities.asStateFlow()

    private val _providers = MutableStateFlow<MutableList<RemoteProvider>>(mutableListOf())
    val providers: StateFlow<MutableList<RemoteProvider>> = _providers.asStateFlow()

    private val _backend = MutableStateFlow("local")
    val backend: StateFlow<String> = _backend.asStateFlow()

    private val _routing = MutableStateFlow(ModelRouting())
    val routing: StateFlow<ModelRouting> = _routing.asStateFlow()

    private val _personas = MutableStateFlow<MutableList<Persona>>(mutableListOf())
    val personas: StateFlow<MutableList<Persona>> = _personas.asStateFlow()

    private val _activePersonaId = MutableStateFlow(PersonaPresets.default.id)
    val activePersonaId: StateFlow<String> = _activePersonaId.asStateFlow()

    private val _userProfile = MutableStateFlow(UserProfile())
    val userProfile: StateFlow<UserProfile> = _userProfile.asStateFlow()

    /**
     * 已安装的插件。
     *
     * 设计意图：新功能先做成插件装上来试，确认可用、稳定之后再考虑做进主体。
     * 这样"试一个新后端"不需要重新编译整个应用，用户也能自己决定要哪些能力。
     */
    private val _plugins = MutableStateFlow<Map<String, com.localmind.data.InstalledPlugin>>(emptyMap())
    val plugins: StateFlow<Map<String, com.localmind.data.InstalledPlugin>> = _plugins.asStateFlow()

    /**
     * 安装一个**插件 zip 包**。
     *
     * 解析与落库都在 [PluginInstall] 里（诊断端点也用同一份逻辑），这里只做
     * 界面该做的事：把结果同步进 StateFlow、把表情包装进解析器、刷新模型列表。
     */
    fun installPluginZip(file: java.io.File): String {
        return when (val o = PluginInstall.install(storage, file)) {
            is PluginInstall.Outcome.Failed -> o.reason
            is PluginInstall.Outcome.Ok -> {
                _plugins.value = storage.loadPlugins()
                _modelPacks.value = storage.loadModelPacks()
                _enabledPackIds.value = storage.enabledModelPackIds()
                o.result.stickerPack?.let { pack ->
                    localApp.emoji.setPack(pack, storage.stickerAssetDir())
                }
                refreshVoiceCatalog()
                refreshModels()
                localApp.bumpPluginRevision()
                o.result.message
            }
        }
    }

    /**
     * 把示例表情包写到 `pluginsDir/示例-表情包/`。
     *
     * 为什么是"写文件"而不是"填进输入框"：表情包内容有几十行 JSON，
     * 贴在手机输入框里没法编辑。写成文件之后用户可以用任意编辑器改，
     * 图片放进同目录的 `assets/`，改完直接压 zip 再导入 ——
     * 这是一条真的能走通的路径，不是演示。
     */
    fun saveSampleStickerPack(): String = runCatching {
        val dir = java.io.File(storage.pluginsDir, "示例-表情包")
        dir.mkdirs()
        // 图片放这里；emoji.json 里的 image 字段就写文件名。
        java.io.File(dir, "assets").mkdirs()
        java.io.File(dir, "emoji.json").writeText(com.localmind.data.PluginArchive.SAMPLE_STICKER_PACK)
        java.io.File(dir, "plugin.json").writeText(
            """{"id":"com.example.mystickers","name":"我的表情包","version":"1.0.0","abilities":["emoji_pack"],"manifestVersion":1}"""
        )
        java.io.File(dir, "怎么改.txt").writeText(
            """
            1. emoji.json 里每条表情：emotion 是标签（模型按这个点菜），
               image 写 assets/ 里的文件名，没有图片就用 char 填一个 emoji。
            2. 把你的图片放进 assets/ 目录。
            3. 把本目录里的【文件】压成 zip（不要套一层文件夹），
               然后在应用里「插件 -> 导入插件 zip」。
            """.trimIndent()
        )
        "已写到 ${dir.absolutePath}"
    }.getOrElse { "写出失败：${it.message?.take(70)}" }

    /** 同上，模型库骨架。 */
    fun saveSampleModelLibrary(): String = runCatching {
        val dir = java.io.File(storage.pluginsDir, "示例-模型库")
        dir.mkdirs()
        java.io.File(dir, "models.json").writeText(com.localmind.data.PluginArchive.SAMPLE_MODEL_LIBRARY)
        java.io.File(dir, "plugin.json").writeText(
            """{"id":"com.example.mylib","name":"我的模型库","version":"1.0.0","abilities":["model_library"],"manifestVersion":1}"""
        )
        "已写到 ${dir.absolutePath}"
    }.getOrElse { "写出失败：${it.message?.take(70)}" }

    /** 从清单文本安装（或更新）一个插件。返回值是一句可直接 toast 的结果说明。 */
    fun installPluginFromText(text: String): String {
        return when (val r = com.localmind.data.PluginManifests.parse(text)) {
            is com.localmind.data.PluginManifests.ParseResult.Failed -> r.reason
            is com.localmind.data.PluginManifests.ParseResult.Ok -> {
                val existing = _plugins.value[r.manifest.id]
                val record = com.localmind.data.InstalledPlugin(
                    manifest = r.manifest,
                    // 更新已存在的插件时保留它原来的启用状态，别让重装把开关重置了。
                    state = existing?.state ?: com.localmind.data.PluginState.INSTALLED,
                    installedAt = existing?.installedAt ?: System.currentTimeMillis(),
                    source = "import",
                )
                _plugins.value = _plugins.value + (r.manifest.id to record)
                storage.savePlugins(_plugins.value)
                val needs = com.localmind.data.PluginManifests.describeRequirements(r.manifest)
                val suffix = if (needs.isEmpty()) "" else "（${needs.joinToString("、")}）"
                if (existing == null) "已安装：${r.manifest.name}$suffix" else "已更新：${r.manifest.name}"
            }
        }
    }

    fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        val p = _plugins.value[pluginId] ?: return
        val next = p.copy(
            state = if (enabled) com.localmind.data.PluginState.ENABLED else com.localmind.data.PluginState.DISABLED,
            lastError = "",
        )
        _plugins.value = _plugins.value + (pluginId to next)
        storage.savePlugins(_plugins.value)
        // 停用一个语音插件要把它的音色从列表里摘掉；启用了要加回来。
        // 所以每次开关都重建一遍注册表 —— 它只是个几十项的列表，不值得做增量。
        refreshVoiceCatalog()
        refreshModels()
        localApp.bumpPluginRevision()
        toast(if (enabled) "已启用 ${p.manifest.name}" else "已停用 ${p.manifest.name}")
    }

    fun uninstallPlugin(pluginId: String) {
        val p = _plugins.value[pluginId] ?: return
        _plugins.value = _plugins.value - pluginId
        storage.savePlugins(_plugins.value)
        // 顺手清掉它的资源目录，避免卸载后留下几十 MB 的孤儿文件。
        runCatching { java.io.File(storage.pluginsDir, pluginId).deleteRecursively() }
        // 它提供的音色/模型也要跟着消失，否则会留下一堆点不动的条目。
        if (p.isActive) {
            val hadVoice = com.localmind.data.PluginAbilities.TTS_VOICE.id in p.manifest.abilities
            if (hadVoice) {
                storage.saveVoicePacks(storage.loadVoicePacks() - pluginId)
                // 当前正用着它的音色就关掉，免得留一个加载不出来的状态。
                if (_voice.value.neuralVoiceId.isNotBlank()) releaseNeuralVoice()
            }
            if (com.localmind.data.PluginAbilities.EMOJI_PACK.id in p.manifest.abilities) {
                storage.saveStickerPack(null, null)
                localApp.emoji.setPack(null, null)
            }
        }
        refreshVoiceCatalog()
        refreshModels()
        localApp.bumpPluginRevision()
        toast("已卸载 ${p.manifest.name}")
    }

    /** 启用了某个能力的插件。供各功能点按需取用。 */
    fun activePluginsFor(ability: com.localmind.data.PluginAbility): List<com.localmind.data.InstalledPlugin> =
        _plugins.value.values.filter { it.isActive && ability.id in it.manifest.abilities }

    // ------------------------------------------------------------ 模型包

    /**
     * 已安装的模型包。key 是 packId。
     *
     * 模型地址做成可安装的包而不是写在 APK 里：地址会失效、会出新的量化、
     * 会换镜像，每改一次都发版是不现实的。用户也能加自己的私有镜像。
     */
    private val _modelPacks = MutableStateFlow<Map<String, com.localmind.data.ModelPackFile>>(emptyMap())
    val modelPacks: StateFlow<Map<String, com.localmind.data.ModelPackFile>> = _modelPacks.asStateFlow()

    private val _enabledPackIds = MutableStateFlow<Set<String>>(emptySet())
    val enabledPackIds: StateFlow<Set<String>> = _enabledPackIds.asStateFlow()

    // ---------------------------------------------------- 插件驱动的状态
    //
    // ⚠️ 这几个 StateFlow **必须声明在 `init` 之前**。
    //
    // Kotlin 按书写顺序初始化属性，而 `init { }` 会调 refreshVoiceCatalog() /
    // refreshUiTheme()。声明写在文件末尾的话，init 跑的时候它们还是 null，
    // 直接崩在 `MutableStateFlow.setValue ... on a null object reference`。
    //
    // 这不是理论风险 —— 线上就是被这个崩掉的（refreshVoiceCatalog，
    // AppViewModel.kt:1575）。所以：**新加会被 init 碰到的状态，一律放这一段。**

    /** 语音页要显示的音色列表。来自**已启用的语音插件**。 */
    private val _voiceCatalog = MutableStateFlow<List<com.localmind.data.VoiceEntry>>(emptyList())

    /** 界面令牌。空 = 没装界面插件，一切用 iOS 原色。 */
    private val _uiTokens = MutableStateFlow<Map<String, String>>(emptyMap())

    /** 当前生效的界面主题包（多个界面插件时最后一个启用的赢）。 */
    private val _activeUiTheme = MutableStateFlow<com.localmind.data.UiTheme?>(null)

    /** 标签栏显示哪几个。 */
    private val _visibleTabs = MutableStateFlow(listOf("chat", "models", "me"))

    /** 插件指定的初始标签页 id。空 = 用默认（对话）。 */
    private val _startTab = MutableStateFlow("")

    /** 覆盖应用标题。空 = 用默认。 */
    private val _appTitleOverride = MutableStateFlow("")

    /** 用户手动覆盖的界面令牌。 */
    private val _themeOverrides = MutableStateFlow<Map<String, String>>(emptyMap())

    /** 已安装的界面主题包（key = 插件 id）。 */
    private val _installedUiThemes =
        MutableStateFlow<Map<String, com.localmind.data.UiTheme>>(emptyMap())

    /**
     * 当前生效的**界面布局包**（`ui_layout` 插件）。null = 用主程序默认布局。
     *
     * 和 [_activeUiTheme] 并存：主题管颜色，布局管结构。布局也能带外观令牌，
     * 优先级见 [refreshUiTheme]。
     */
    private val _chatActions = MutableStateFlow<List<com.localmind.data.ChatAction>>(emptyList())

    /** 插件提供的聊天快捷动作（输入框上方那一排）。 */
    val chatActions: StateFlow<List<com.localmind.data.ChatAction>> get() = _chatActions.asStateFlow()

    private val _uiLayout = MutableStateFlow<com.localmind.data.UiLayout?>(null)

    /** 已安装的布局包（key = 插件 id），给设置页列出来用。 */
    private val _installedLayouts =
        MutableStateFlow<Map<String, com.localmind.data.UiLayout>>(emptyMap())

    /**
     * 外部语音引擎（Breeze TTS 2 等）的界面状态。
     *
     * ⚠️ 必须声明在 `init` 之前 —— `init` 会调 refreshBreeze()，而 Kotlin 按书写
     * 顺序初始化，写在文件后半段的话这里还是 null，直接崩在
     * "MutableStateFlow.getValue() on a null object reference"。
     * 这一条已经踩过一次（本轮 1.0.4 的第一次启动就是它）。
     */
    private val _breeze = MutableStateFlow(BreezeState())
    val breeze: StateFlow<BreezeState> = _breeze.asStateFlow()

    /**
     * 分步流水线的界面状态。
     *
     * 同样必须声明在 init 之前（init 会调 refreshPipelines()）。
     */
    /** 流水线页面的状态（列表 / 进度 / 结果）。 */
    data class PipelineUi(
        val pipelines: List<com.localmind.data.Pipeline> = emptyList(),
        val packName: String = "",
        val running: Boolean = false,
        val progress: String = "",
        val resultText: String = "",
        val audioPath: String = "",
        val logs: List<String> = emptyList(),
        val error: String = "",
        val activeId: String = "",
    )

    private val _pipelineUi = MutableStateFlow(PipelineUi())
    val pipelineUi: StateFlow<PipelineUi> = _pipelineUi.asStateFlow()

    /** 分步执行器。懒建：它只是几个引用的持有者，不加载任何东西。 */
    private val pipelineRunner by lazy {
        com.localmind.service.PipelineRunner(
            context = getApplication(),
            engine = engine,
            breeze = breezeTts,
            settings = { _settings.value },
            activeTts = { _breeze.value.active?.spec },
        )
    }

    /**
     * **整个应用生效的界面主题**：令牌 + 标签栏 + 标题。唯一的真相来源。
     *
     * ## 为什么要有它（这是一次 bug 修复，不是重构洁癖）
     *
     * 令牌以前有**两条**解析路径：这里一份（`_uiTokens`，只有 LocalMindRoot 的
     * 标签栏在用），和 `rememberPluginUiTokens` 里另一份（那才是真正画到屏幕上的
     * 大部分颜色）。两份当然会分叉。
     *
     * 更糟的是界面那一份：它在 `remember(dark, pluginRevision, overrideRevision)`
     * 里算，而那两个"版本号"是 LocalMindApp 上的**普通 @Volatile Int**，
     * 不是 Compose 状态 —— **读它们不会触发重组**。于是换主题、改覆盖之后
     * 界面根本不会重画，只有整页重建（比如切深浅色）才偶然生效。
     * 用户看到的就是"改了没反应 / 没办法彻底改风格"。
     *
     * 这个字段是 StateFlow，收集它就会重组，所以真实生效。
     */
    private val _uiThemeState = MutableStateFlow(com.localmind.ui.theme.ThemeState())

    /** 待发送的图片（本地绝对路径）。 */
    private val _pendingImage = MutableStateFlow<String?>(null)

    /** 本机可用的生图 checkpoint。 */
    private val _diffusionModels = MutableStateFlow<List<java.io.File>>(emptyList())

    /**
     * 扩散后端**此刻是不是真的能用** —— 由原生层回答，不是"文件在不在"。
     *
     * 这两个判断必须分开。后端插件装完后文件确实在 `filesDir/native/<abi>/`，
     * 但只要没 `System.load` 进进程，能力探测就一律回答"没有"。界面拿文件存在
     * 当"已安装"显示，就会出现"显示已安装、生图还是程序化渲染"的分叉。
     */
    private val _diffusionBackendReady = MutableStateFlow(false)

    // 公开只读视图。
    //
    // 放在这里而不是各自的"功能区"，就是为了让「声明必须早于 init」这件事
    // 一眼可见 —— 下面几个都被 init 里的 refresh* 碰到过，晚声明就是空指针。
    // 已经因为这个崩过两次（refreshVoiceCatalog、refreshUiTheme），别再挪。
    val themeOverrides: StateFlow<Map<String, String>> get() = _themeOverrides.asStateFlow()
    val installedUiThemes: StateFlow<Map<String, com.localmind.data.UiTheme>>
        get() = _installedUiThemes.asStateFlow()
    val pendingImage: StateFlow<String?> get() = _pendingImage.asStateFlow()
    val diffusionModels: StateFlow<List<java.io.File>> get() = _diffusionModels.asStateFlow()
    val diffusionBackendReady: StateFlow<Boolean> get() = _diffusionBackendReady.asStateFlow()

    /**
     * 深色模式。
     *
     * `isSystemInDarkTheme()` 是 @Composable，ViewModel 里调不了，所以这里自己
     * 按 `themeMode()` 算一遍（`system` → 跟系统走）。这样**第一帧就是对的**，
     * 不会先按浅色画一帧再跳成深色。界面层之后还会用真实值调一次
     * [refreshUiTheme] 兜底。
     */
    private var isDarkTheme: Boolean = runCatching { storage.isDarkNow() }.getOrDefault(false)

    /**
     * 从 zip 或单个 JSON 安装模型包。
     *
     * 支持两种形态，因为分发方式不同：
     *  - **zip**：里面有一个 `models.json`（或任意单个 .json）。适合放多个文件和说明。
     *  - **纯 JSON**：适合"我就想粘一段地址进来"。
     *
     * 解压时只读需要的那个 JSON，不往磁盘写任何东西 ——
     * 模型包只是地址清单，没有资源文件，落地一份反而多一处要清理的状态。
     */
    fun installModelPackFrom(file: java.io.File): String {
        val text = runCatching {
            // 按**内容**判断而不是扩展名：文件选择器回来的临时文件常常没有后缀，
            // zip 的魔数比文件名可靠。PK\x03\x04 = zip。
            val isZip = runCatching {
                file.inputStream().use { input ->
                    val head = ByteArray(4)
                    input.read(head) == 4 &&
                        head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
                        head[2] == 0x03.toByte() && head[3] == 0x04.toByte()
                }
            }.getOrDefault(false)

            if (isZip) {
                readJsonFromZip(file) ?: return "压缩包里没找到 models.json（且没有唯一的 json）"
            } else {
                file.readText()
            }
        }.getOrElse { return "读取失败：${it.message?.take(80)}" }

        val pack = com.localmind.data.ModelPacks.parse(text).getOrElse {
            return "模型包格式不对：${it.message?.take(120)}"
        }
        val id = pack.packId.ifBlank { file.nameWithoutExtension }
        val stamped = pack.copy(
            packId = id,
            models = pack.models.map { it.copy(packId = id) },
        )
        _modelPacks.value = _modelPacks.value + (id to stamped)
        // 新装的包默认启用：用户刚导入就是想用它，再让他去点一下开关很多余。
        _enabledPackIds.value = _enabledPackIds.value + id
        storage.saveModelPacks(_modelPacks.value)
        storage.saveEnabledModelPackIds(_enabledPackIds.value)
        refreshModels()
        return "已导入 ${stamped.models.size} 个模型：${stamped.packName.ifBlank { id }}"
    }

    fun setModelPackEnabled(packId: String, enabled: Boolean) {
        _enabledPackIds.value = if (enabled) {
            _enabledPackIds.value + packId
        } else {
            _enabledPackIds.value - packId
        }
        storage.saveEnabledModelPackIds(_enabledPackIds.value)
        refreshModels()
    }

    fun uninstallModelPack(packId: String) {
        _modelPacks.value = _modelPacks.value - packId
        _enabledPackIds.value = _enabledPackIds.value - packId
        storage.saveModelPacks(_modelPacks.value)
        storage.saveEnabledModelPackIds(_enabledPackIds.value)
        refreshModels()
        toast("已移除模型包")
    }

    /** 从 zip 里取模型包 JSON。优先 `models.json`，否则用唯一一个 json 条目。 */
    private fun readJsonFromZip(zip: java.io.File): String? {
        val found = LinkedHashMap<String, String>()
        java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name.substringAfterLast('/')
                if (!entry.isDirectory && name.endsWith(".json", ignoreCase = true)) {
                    found[name] = zis.readBytes().toString(Charsets.UTF_8)
                }
                entry = zis.nextEntry
            }
        }
        // 明确优先 models.json：包里可能还有 manifest.json 之类的说明性文件。
        found.entries.firstOrNull { it.key.equals("models.json", ignoreCase = true) }
            ?.let { return it.value }
        // 否则只有当包里**只有一个** json 时才采用它 —— 多个的话选错会给出莫名其妙的错误。
        return if (found.size == 1) found.values.first() else null
    }

    /**
     * 改这个对话的表达能力（语音/生图/识图/表情）。
     *
     * 只是偏好，不涉及安全，所以不做确认、不加限制 —— 唯一约束是文字不能关。
     */
    fun updateConversationAbilities(conversationId: String, transform: (com.localmind.data.AiAbilities) -> com.localmind.data.AiAbilities) {
        updateConversationIdentity(conversationId) { conv ->
            conv.abilities = transform(conv.abilities).copy(text = true)
        }
    }

    /** 改这个对话的大模型参数微调。 */
    fun updateConversationSampling(
        conversationId: String,
        transform: (com.localmind.data.SamplingOverrides) -> com.localmind.data.SamplingOverrides,
    ) {
        updateConversationIdentity(conversationId) { conv -> conv.sampling = transform(conv.sampling) }
    }

    /**
     * 每个**对话**的拟人状态（情绪/性格/记忆/经验）。
     *
     * key 是对话 id，不是人设 id：关系是按对话建立的，和「小美」聊工作那段与和
     * 「老陈」聊人生那段，本该是两个人、两套记忆。
     */
    private val _companionStates = MutableStateFlow<Map<String, CompanionState>>(emptyMap())
    val companionStates: StateFlow<Map<String, CompanionState>> = _companionStates.asStateFlow()

    fun companionStateOf(conversationId: String): CompanionState =
        _companionStates.value[conversationId] ?: CompanionState(personaId = conversationId)

    /** 当前打开对话的拟人状态；没有打开对话时返回一个空态。 */
    fun activeCompanionState(): CompanionState =
        _activeId.value?.let { companionStateOf(it) } ?: CompanionState(personaId = "")

    fun forgetPersona(conversationId: String) {
        companion.reset(conversationId)
        _companionStates.value = companion.allStates()
        toast("已清空这段关系的记忆")
    }

    fun deleteMemory(conversationId: String, memoryId: String) {
        val state = companion.stateOf(conversationId)
        companion.save(state.copy(memories = state.memories.filterNot { it.id == memoryId }.toMutableList()))
        _companionStates.value = companion.allStates()
    }

    /** Currently selected persona — 现在只作为**新建对话时的默认身份**。 */
    fun activePersona(): Persona =
        _personas.value.firstOrNull { it.id == _activePersonaId.value } ?: PersonaPresets.default

    /**
     * 当前打开对话里的 AI 是谁。
     *
     * 这是对话自己的身份；对话没建立或还是旧存档时，退回到全局活跃人设。
     */
    fun conversationPersona(): Persona {
        val conv = activeConversation() ?: return activePersona()
        return conv.persona(activePersona())
    }

    /** true while a conversation is open (iOS Messages pushes into it). */
    private val _inChat = MutableStateFlow(false)
    val inChat: StateFlow<Boolean> = _inChat.asStateFlow()

    private val _diagnostics = MutableStateFlow<SelfTest.Report?>(null)
    val diagnostics: StateFlow<SelfTest.Report?> = _diagnostics.asStateFlow()

    private val _diagnosticsRunning = MutableStateFlow(false)
    val diagnosticsRunning: StateFlow<Boolean> = _diagnosticsRunning.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    val engineState = engine.state
    val apiState = apiServer.state
    val downloadStates = downloader.states
    val apiLog = apiServer.logFlow
    val voiceSttState = stt.state

    /**
     * 给「字段声明必须早于 init」这件事加一道**真的会生效**的护栏。
     *
     * ## 为什么需要它
     *
     * Kotlin 按书写顺序初始化属性。在 init 里碰一个还没初始化的 `val`，
     * 拿到的是 `null` —— 而 Kotlin 的非空类型在**字段之间**不做运行期检查，
     * 所以编译期一切正常，运行期炸在几层调用之后：
     *
     *     NullPointerException: Attempt to invoke interface method
     *     'void MutableStateFlow.setValue(Object)' on a null object reference
     *
     * 这个坑在本项目里踩了**两次**，两次都是把新状态追加到文件末尾造成的。
     *
     * ## 它怎么生效
     *
     * 把每个"会被 init 用到的"字段包一层可空读取。字段还没初始化时读到 null，
     * 于是报出**是哪个字段**，而不是一个指向 `setValue` 的栈。
     * 调用点在 init 的最前面，所以报错离原因只有一行。
     *
     * 加了新状态就往列表里补一项，这是唯一的维护成本。
     */
    private fun assertFieldsInitialized() {
        fun def(name: String, read: () -> Any?) {
            check(read() != null) {
                "AppViewModel 字段 `_$name` 在 init 时还是 null —— " +
                    "它的声明写在 init 之后了。请把它移到文件顶部「插件驱动的状态」那一段。"
            }
        }
        def("plugins") { _plugins }
        def("modelPacks") { _modelPacks }
        def("enabledPackIds") { _enabledPackIds }
        def("voiceCatalog") { _voiceCatalog }
        def("uiTokens") { _uiTokens }
        def("uiThemeState") { _uiThemeState }
        def("activeUiTheme") { _activeUiTheme }
        def("visibleTabs") { _visibleTabs }
        def("appTitleOverride") { _appTitleOverride }
        def("themeOverrides") { _themeOverrides }
        def("installedUiThemes") { _installedUiThemes }
        def("pendingImage") { _pendingImage }
        def("diffusionModels") { _diffusionModels }
    }

    init {
        // 第一件事：确认下面要用到的字段都真的初始化了。
        // 放在最前面，报错才离原因近。见 assertFieldsInitialized 的说明。
        assertFieldsInitialized()

        _settings.value = storage.loadGenerationSettings()
        _voice.value = storage.loadVoiceSettings()
        _api.value = storage.loadApiSettings()
        _image.value = storage.loadImageSettings()
        _capabilities.value = storage.loadCapabilities()
        _providers.value = storage.loadRemoteProviders().toMutableList()
        _backend.value = storage.chatBackend()
        _routing.value = storage.loadRouting()
        _personas.value = storage.loadPersonas().toMutableList()
        _activePersonaId.value = storage.activePersonaId()
        _userProfile.value = storage.loadUserProfile()
        _companionStates.value = companion.allStates()
        _plugins.value = storage.loadPlugins()
        _modelPacks.value = storage.loadModelPacks()
        _enabledPackIds.value = storage.enabledModelPackIds()
        _conversations.value = ConversationList(storage.loadConversations())
        // 列表为空就让它空着。原来这里会**自动补一个空对话**，删最后一个之后
        // 立刻又冒出来一个，用户的感受是"删不掉" —— 他删了就是不想留着。
        // 空列表有专门的空态（「开始一段对话」+ 新建按钮）。
        _activeId.value = null
        _inChat.value = false
        refreshModels()

        // Auto-load the previously used model once, in the background.
        LocalMindApp.pendingModelId?.let { id ->
            LocalMindApp.pendingModelId = null
            viewModelScope.launch {
                val model = modelStore.resolveAll().firstOrNull { it.id == id && it.isDownloaded }
                if (model != null && !engine.state.value.ready) loadModel(model)
            }
        }

        // Bring the dedicated router engine back up too, if one was configured.
        restoreDecisionModel()

        // 神经音色同理：上次选的音色要装回来，但**不能在这里同步做** ——
        // 加载一个 VITS 模型要读几十上百 MB，init 跑在主线程上。
        // 音色清单来自插件，所以先把注册表建起来再恢复选择。
        refreshVoiceCatalog()
        restoreNeuralVoice()

        // 把"多重思考"同步给拟人层：它决定经验/心事要不要用对话模型兜底提炼。
        companion.allowChatModelFallback = _settings.value.deepThinking

        // 后台学习跑完会叫一声，这里把「它现在的状态」重新发一次。
        // 学习已经不在用户这条路上等了（见 LocalMindApp 的钩子），所以必须靠回调刷新，
        // 否则那一栏会一直停在上一次的值。
        localApp.onCompanionUpdated = {
            _companionStates.value = companion.allStates()
        }

        // 上次装过、但还没进进程的原生后端（生图 / 语音引擎）在这里补一次加载。
        //
        // 为什么必须补：插件的安装动作和"这个进程能不能用"是两件事。用户装完
        // 后端、重启应用之后，如果不重新 System.load，"有没有生图后端"就永远
        // 回答没有 —— 界面显示"已安装"，生图却还在走程序化渲染。
        //
        // 放后台：dlopen 一个 36 MB 的 .so 要几百毫秒，不该压在主线程启动路径上。
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    com.localmind.service.NativeLibraryInstaller.activateInstalled(storage)
                    com.localmind.native.image.ImageBridge.hasDiffusionBackend
                }.getOrDefault(false)
            }
            _diffusionBackendReady.value = ok
        }

        // 界面令牌也先建一遍。深浅色在这里是**自己算出来的**（见 isDarkTheme），
        // 所以第一帧就是对的；MainActivity 之后还会用真实值再对一次。
        refreshUiTheme(isDarkTheme)
        // 语音引擎同理：插件可能在别处被装上/停用。
        refreshBreeze()
        refreshPipelines()

        // 预热：等首帧画完再动手，别和界面初始化抢 IO / 内存。
        if (storage.loadUserProfile().onboarded) {
            viewModelScope.launch {
                delay(900)
                preloadChatModel()
            }
        }

        // 插件清单 / 用户覆盖一变就重算主题。
        //
        // 为什么需要这两个收集：改动可能来自**别的地方** —— 诊断端点
        // （POST /v1/debug/theme）、另一个界面、或以后的后台服务。只在自己的
        // 函数里调 refreshUiTheme() 的话，那些来源改了存储界面也不知道。
        // 这也顺手把"overrideRevision 永远是 0"那个洞补上了。
        viewModelScope.launch {
            localApp.pluginRevisionFlow.collect {
                refreshUiTheme()
                refreshBreeze()
                refreshPipelines()
            }
        }
        viewModelScope.launch {
            localApp.overrideRevisionFlow.collect { refreshUiTheme() }
        }
    }

    // ------------------------------------------------------------ navigation

    fun selectTab(tab: Tab) {
        _tab.value = tab
    }

    fun openSettings(open: Boolean) {
        _showSettings.value = open
    }

    /**
     * 生图工作台。
     *
     * Deliberately a **sub-page of the 模型 tab**, not a fourth bottom tab: the user
     * asked for 对话 / 模型 / 我的 only, and 生图 is a place you visit to try a model
     * rather than a place you live in. It also keeps the tab bar's liquid-glass
     * geometry (which assumes three evenly spaced items) untouched.
     */
    fun openImageStudio(open: Boolean) {
        _showImageStudio.value = open
    }

    /** 打开/关闭「语音与音色」整页。见 [_showVoiceScreen] 的说明。 */
    fun openVoiceScreen(open: Boolean) {
        _showVoiceScreen.value = open
    }

    /**
     * 打开/关闭「Breeze 语音」整页。
     *
     * 为什么默认做成**子页**而不是第四个标签：「我的 → 更多」进来是最常见的路径，
     * 而底栏是给"每天都要去"的地方留的。界面布局插件想把它放到底栏也可以
     *（`tabs` 里写 `breeze`），两条路都通。
     */
    fun openBreeze(open: Boolean) {
        _showBreeze.value = open
        if (open) refreshBreeze()
    }

    /** 打开/关闭「本对话设置」整页。见 [_showConversationDetail] 的说明。 */
    fun openConversationDetail(open: Boolean) {
        _showConversationDetail.value = open
    }

    /**
     * 弹一条提示。
     *
     * 顺带**记一条操作流水**（见 [com.localmind.data.Diagnostics]）。
     *
     * 为什么挂在这里：全应用几乎所有"用户做了一件事"都会走到 toast —— 下载、
     * 切换、导入、保存、失败提示。一个收口点就能覆盖绝大部分操作轨迹，
     * 比在几十个调用点各插一行可靠得多（那种迟早会漏）。
     */
    fun toast(message: String) {
        com.localmind.data.Diagnostics.action(localApp, message)
        _toast.value = message
        viewModelScope.launch {
            delay(2600)
            if (_toast.value == message) _toast.value = null
        }
    }

    fun openDebug(open: Boolean = true) {
        _showDebug.value = open
        if (open) {
            com.localmind.data.Diagnostics.action(localApp, "打开测试模式")
        }
    }

    /**
     * 诊断报告里的"运行状态"那一段。
     *
     * 放在 ViewModel 而不是 [com.localmind.data.Diagnostics]：那些信息要问引擎、
     * 原生库、后端，把存储层和推理层绑在一起不值得。
     */
    fun diagnosticsExtra(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val s = engine.state.value
        out += "模型" to (s.modelName ?: "未加载")
        out += "上下文" to s.contextSize.toString()
        out += "线程" to s.threads.toString()
        out += "上次速度" to String.format(java.util.Locale.US, "%.1f tok/s", s.lastTokensPerSecond)
        out += "模型文件" to (s.modelPath ?: "-")
        out += "llama.cpp" to engine.systemInfo()
        out += "语音引擎" to if (voiceEngineReady) {
            "已就绪（$voiceEngineAbi, ${com.localmind.util.Formatters.size(voiceEngineBytes())}）"
        } else {
            "未安装"
        }
        out += "生图后端" to if (diffusionBackendReady.value) "已加载" else "未加载"
        val api = apiServer.state.value
        out += "API 服务" to if (api.running) "运行中 :${api.port}" else "已停止"
        out += "聊天后端" to _backend.value.ifBlank { "local" }
        // ---- 上一轮的推理账 ----
        //
        // 「对话快不快」这件事只有这三个数字能回答：提示词多少 token、其中多少
        // 直接复用了 KV cache、首 token 等了多久。它们也能立刻暴露「提示词布局
        // 又不稳了」这种回归 —— 复用率掉到个位数就说明前缀被什么东西打断了。
        if (s.lastPromptTokens > 0) {
            out += "前缀复用" to ("${s.lastReusedTokens}/${s.lastPromptTokens} token（${s.cacheHitPercent}%）")
            out += "prefill" to "${s.lastPrefillMs} ms（其中首 token ${s.lastFirstTokenMs} ms）"
        }

        // ---- 设备感知 ----
        //
        // 导出报告里必须有这一段：内测反馈里"它怎么不知道我在哪"这类问题，
        // 一半是权限/开关的问题（用户以为开了），一半是读取失败（网络/后端）。
        // 这两种在报告里长得完全不一样，缺了这一段就只能猜。
        // 只做**同步且便宜**的读取：电量 + 权限/开关状态。真正带 GPS 的读取
        // 放在测试模式的「设备感知」里点（那里可以等几秒）。
        out += "设备感知" to buildString {
            val b = com.localmind.service.DeviceSense.battery(localApp)
            append(if (b == null) "电量读不到" else "电量 ${b.percent}%" + if (b.charging) "充电中" else "未充电")
            append(" · 定位权限 ")
            append(if (com.localmind.service.DeviceSense.hasLocationPermission(localApp)) "有" else "无")
            append(" · 系统定位 ")
            append(if (com.localmind.service.DeviceSense.locationEnabled(localApp)) "开" else "关")
            append(" · 反向地理编码 ")
            // 用**上次实际结果**，不用 Geocoder.isPresent()：那个会撒谎
            //（本机 isPresent=true 但实际解析超时）。见 DeviceSense.lastGeocodeOk。
            append(
                when (com.localmind.service.DeviceSense.lastGeocodeOk) {
                    true -> "上次解析成功"
                    false -> "上次解析失败（地名会退回时区推断）"
                    null -> "还没试过"
                },
            )
        }
        // 每个对话各自用哪个模型 —— "模型配合没生效"是最难从界面上看出来的一类问题。
        val convs = _conversations.value
        if (convs.isNotEmpty()) {
            out += "对话数" to convs.size.toString()
            convs.take(4).forEach { c ->
                val kind = if (c.companionMode) "人设" else "AI"
                val model = c.effectiveChatModel(_routing.value).ifBlank { "默认" }
                out += "  · $kind" to "${c.title.take(12)} / ${model.take(24)}"
            }
        }
        return out
    }

    /**
     * 测试模式「设备感知」里的一键读取：**不管对话开关**，三项都读一遍。
     *
     * 对话里的按需读取要受开关和关键词限制，而测试页要回答的是另一个问题：
     * **这台机器到底读不读得到**。所以这里绕开开关，直读。
     */
    suspend fun debugSenseSnapshot(): String = com.localmind.service.DeviceSense.snapshot(
        localApp,
        setOf(
            com.localmind.service.DeviceSense.Need.BATTERY,
            com.localmind.service.DeviceSense.Need.LOCATION,
            com.localmind.service.DeviceSense.Need.WEATHER,
        ),
    )

    fun dismissToast() {
        _toast.value = null
    }

    // ------------------------------------------------------------- settings

    fun updateSettings(transform: (GenerationSettings) -> GenerationSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        storage.saveGenerationSettings(next)
        // 「多重思考」同时决定"经验/心事"要不要用对话模型兜底提炼：
        // 它是一次额外生成，和下一句话抢引擎，只有明确选了"更慢但更准"才开。
        companion.allowChatModelFallback = next.deepThinking
    }

    fun updateVoice(transform: (VoiceSettings) -> VoiceSettings) {
        val next = transform(_voice.value)
        _voice.value = next
        storage.saveVoiceSettings(next)
        tts.setLanguage(next.language)
    }

    fun updateApi(transform: (ApiSettings) -> ApiSettings) {
        val next = transform(_api.value)
        _api.value = next
        localApp.saveApi(next)
        apiServer.updateSettings(next)
    }

    fun updateImage(transform: (ImageSettings) -> ImageSettings) {
        val next = transform(_image.value)
        _image.value = next
        storage.saveImageSettings(next)
    }

    fun updateCapabilities(transform: (CapabilitySettings) -> CapabilitySettings) {
        val next = transform(_capabilities.value)
        _capabilities.value = next
        storage.saveCapabilities(next)
    }

    fun updateProviders(transform: (MutableList<RemoteProvider>) -> MutableList<RemoteProvider>) {
        val next = transform(_providers.value.toMutableList())
        _providers.value = next
        storage.saveRemoteProviders(next)
    }

    fun setBackend(id: String) {
        _backend.value = id
        storage.setChatBackend(id)
    }

    // ------------------------------------------------------------ 云端接口
    //
    // 这一组是「云端接口」页的全部后端逻辑。放在 ViewModel 而不是界面里，
    // 是因为**诊断端点也要用**（测试连接、加一个 provider 都不该只存在于 UI 里）。

    private val _providerTests = MutableStateFlow<Map<String, String>>(emptyMap())
    /** provider.id -> 最近一次「测试连接」的结果（给界面显示的一行字）。 */
    val providerTests: StateFlow<Map<String, String>> = _providerTests.asStateFlow()

    private val remoteApi: com.localmind.service.RemoteApi
        get() = localApp.remoteApi

    /** 从预设建一个接口。**密钥留空** —— 预设只填 baseUrl 与示例模型。 */
    fun addProviderFromPreset(preset: com.localmind.data.RemoteProvider.Companion.Preset) {
        val id = "prov-" + preset.id + "-" + System.currentTimeMillis().toString(36).takeLast(4);
        val provider = RemoteProvider(
            id = id,
            name = preset.name,
            kind = preset.kind,
            baseUrl = preset.baseUrl,
            model = preset.model,
            visionModel = preset.visionModel,
            imageModel = preset.imageModel,
            // 新建的默认**开启**：用户刚点完「添加」，下一个动作就是填密钥然后用它。
            enabled = true,
            isLocalNetwork = preset.baseUrl.contains("192.168.") || preset.baseUrl.contains("127.0.0.1"),
        )
        updateProviders { it.apply { add(provider) } }
        toast("已添加 " + provider.name + "，填入 API Key 就能用")
    }

    fun updateProvider(id: String, transform: (RemoteProvider) -> RemoteProvider) {
        updateProviders { list ->
            val i = list.indexOfFirst { it.id == id }
            if (i >= 0) list[i] = transform(list[i])
            list
        }
        // 改了地址或密钥，上次的测试结果就不再代表现在，清掉它 ——
        // 留着一个绿色的「连接正常」比空着更误导。
        _providerTests.value = _providerTests.value - id
    }

    fun removeProvider(id: String) {
        updateProviders { list ->
            list.removeAll { it.id == id }
            list
        }
        // 删掉的正好是当前后端时要退回来，否则下一次发消息会去找一个不存在的 id。
        if (_backend.value == id) setBackend("local")
        _providerTests.value = _providerTests.value - id
    }

    /** 测试连通性。结果写进 [providerTests]，界面直接显示。 */
    fun testProvider(id: String) {
        val provider = _providers.value.firstOrNull { it.id == id } ?: return;
        _providerTests.value = _providerTests.value + (id to "测试中…");
        viewModelScope.launch {
            val result = remoteApi.probe(provider);
            val text = result.getOrElse { t ->
                "连不上：" + (t.message ?: t.javaClass.simpleName)
            }
            _providerTests.value = _providerTests.value + (id to text);
            toast(provider.name + "：" + text);
        }
    }

    /**
     * 手填一个 OpenAI 兼容接口（不靠预设），可以顺手绑到某个对话上。
     *
     * 这是「对话设置 → 接 API」那条捷径的后端：用户从别处拿到一个 baseUrl + key，
     * 不该被要求先跑去「我的 → 云端接口」新建、再回来在模型选择器里找一次。
     * 填完这一次就算接上了，而且**立刻**把测试请求发出去，配错了当场就知道。
     *
     * @return 新建接口的 id；失败返回空串（原因已经 toast 出来）。
     */
    fun addCustomOpenAiProvider(
        name: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        bindConversationId: String? = null,
    ): String {
        val url = normalizeOpenAiBaseUrl(baseUrl)
        if (url == null) {
            toast("Base URL 要写成 http(s):// 开头的地址")
            return ""
        }
        val modelName = model.trim()
        if (modelName.isBlank()) {
            toast("模型名不能空（例如 deepseek-chat / gpt-4o-mini）")
            return ""
        }
        val id = "prov-custom-" + System.currentTimeMillis().toString(36).takeLast(5)
        val provider = com.localmind.data.RemoteProvider(
            id = id,
            name = name.trim().ifBlank { "我的接口" },
            kind = com.localmind.data.ProviderKind.OPENAI,
            baseUrl = url,
            apiKey = apiKey.trim(),
            model = modelName,
            visionModel = modelName,
            enabled = true,
            isLocalNetwork = url.contains("127.0.0.1") || url.contains("192.168.") ||
                url.contains("10.0.") || url.contains("localhost"),
        )
        updateProviders { it.apply { add(provider) } }
        if (bindConversationId != null) {
            updateConversationModels(bindConversationId) { it.copy(chatModelId = id) }
        }
        toast("已接入 " + provider.name + "，正在测试连接…")
        testProvider(id)
        return id
    }

    /**
     * 把用户粘进来的地址整理成 baseUrl。
     *
     * 三种常见写法都要能吃：
     *  * https://api.deepseek.com            → 补 /v1（绝大多数网关的兼容路径在 /v1 下）
     *  * https://api.deepseek.com/v1/        → 去掉尾斜杠
     *  * https://x/v1/chat/completions       → 把已经写全的端点拆回 baseUrl
     *
     * ⚠️ 只对「路径是空」的地址补 /v1。像智谱那样 /api/paas/v4、Gemini 那样 /v1beta
     * 的上游，多补一层就是 404 —— 用户填对了却被我们改坏，是这里最贵的错误。
     */
    fun normalizeOpenAiBaseUrl(raw: String): String? {
        var url = raw.trim()
        if (url.isEmpty()) return null
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        url = url.trimEnd('/')
        listOf("/chat/completions", "/completions", "/models").forEach { suffix ->
            if (url.endsWith(suffix)) url = url.removeSuffix(suffix)
        }
        url = url.trimEnd('/')
        val path = runCatching { java.net.URI(url).path.orEmpty() }.getOrDefault("")
        if (path.isBlank() || path == "/") url = url + "/v1"
        return url
    }

    fun updateRouting(transform: (ModelRouting) -> ModelRouting) {
        val next = transform(_routing.value)
        _routing.value = next
        storage.saveRouting(next)
    }
    /** Called by the first-run wizard once the user has filled in their profile. */
    fun finishOnboarding() {
        updateUserProfile { it.copy(onboarded = true) }
        _inChat.value = false
        toast("设置完成，可以开始对话了")
    }

    /** Re-run the wizard from 我的. */
    fun restartOnboarding() = updateUserProfile { it.copy(onboarded = false) }

    /** Streaming toggle, shared by the chat settings sheet and onboarding. */
    fun setStreamToUi(on: Boolean) = updateSettings { it.copy(streamToUi = on) }

    /**
     * 短话连发的断句点：从 [from] 开始，找**第一个**够长的句子结尾，返回它的下一个下标；
     * 找不到返回 -1（表示还不够一条，继续等）。
     *
     * 三条规则：
     *  * 句末标点（。！？；换行，以及英文半角）可以作为断点 —— 但整条不能短于 [minLen]，
     *    否则模型每写一个"好。"就发一条，看起来像抽风；
     *  * 一直没句读也不能无限长：到 [maxLen] 就在最近的逗号/顿号/空格处断开，
     *    都没有就直接硬断 —— "短话"是这一项的目的，宁可断得糙一点；
     *  * 返回的是**下标**而不是子串，调用方自己去取，避免这里多复制一份。
     */
    /**
     * 模型写的表情标签 -> 真正能渲染的东西（图片梗图优先，其次 emoji 字符）。
     *
     * 抽出来是因为短话连发要**逐条**解析：一条回答可能被切成好几段，
     * 每段都可能带自己的表情，不能在收尾时只解析一次。
     */
    private fun stickerOf(tag: String?): String? {
        val s = emoji.resolve(tag) ?: return null
        return emoji.imageFile(s)?.let { "file://${it.absolutePath}" }
            ?: s.char.takeIf { it.isNotBlank() }
    }

    private fun burstCut(text: String, from: Int, minLen: Int = 10, maxLen: Int = 60): Int {
        var i = from
        while (i < text.length) {
            val end = i + 1
            val c = text[i]
            val hard = c == '。' || c == '！' || c == '？' || c == '；' || c == '\n' ||
                c == '!' || c == '?' || c == ';'
            val softDot = c == '.' && (end >= text.length || text[end].isWhitespace())
            if ((hard || softDot) && end - from >= minLen) return end
            if (end - from >= maxLen) {
                val win = text.substring(from, end)
                val soft = win.indexOfLast { it == '，' || it == ',' || it == '、' || it == ' ' }
                return if (soft > 0) from + soft + 1 else end
            }
            i++
        }
        return -1
    }

    /** 短话连发：把回答拆成几条短消息依次发。 */
    fun setBurstReplies(on: Boolean) = updateSettings { it.copy(burstReplies = on) }

    /** 多重思考：默认关，见 [com.localmind.data.GenerationSettings.deepThinking]。 */
    fun setDeepThinking(on: Boolean) {
        updateSettings { it.copy(deepThinking = on) }
        if (!on) {
            _understanding.value = null
            _clarification.value = null
        }
        toast(if (on) "已打开多重思考：每轮会多跑 3 次模型，回答更准但更慢" else "已关闭多重思考")
    }

    /** Bind (or clear) the function-calling decision model. */
    fun setDecisionModel(modelId: String) {
        updateRouting { it.copy(decisionModelId = modelId) }
        companion.bindDecisionModel(modelId)
        val id = modelId.trim()
        if (id.isEmpty()) {
            viewModelScope.launch {
                decisionEngine.unload()
                toast("决策改用内置规则")
            }
            return
        }
        loadDecisionModel(id)
    }

    /**
     * Loads [modelId] into the **dedicated** decision engine so the router stops
     * borrowing the chat model.
     *
     * The decision model is deliberately loaded with a tiny context and a single
     * thread: it only ever emits one short `CALL ...` line, so it never needs the
     * chat-sized context the memory planner would otherwise recommend. Keeping it
     * small is what makes two resident models affordable on a phone.
     */
    private fun loadDecisionModel(modelId: String) {
        viewModelScope.launch {
            val model = withContext(Dispatchers.IO) {
                modelStore.resolveAll().firstOrNull { it.id == modelId && it.isDownloaded }
            }
            if (model == null) {
                companion.bindDecisionModel("")
                updateRouting { it.copy(decisionModelId = "") }
                toast("决策模型未下载，已改用内置规则")
                return@launch
            }
            if (decisionEngine.state.value.ready &&
                decisionEngine.state.value.modelPath == model.localPath
            ) {
                return@launch
            }
            toast("正在加载决策模型 ${model.name}…")
            val decisionSettings = _settings.value.copy(
                contextSize = DECISION_CONTEXT,
                threads = DECISION_THREADS,
                maxTokens = 96,
                temperature = 0f,
                topP = 1f,
                topK = 1,
                streamToUi = false,
            )
            decisionEngine.load(model, decisionSettings)
                .onSuccess {
                    toast(
                        "决策模型已接管（${model.name} · ctx " +
                            "${decisionEngine.state.value.contextSize}）",
                    )
                }
                .onFailure {
                    // Never let a failed router load silently demote the decision to the
                    // chat model — tell the companion so it uses the rule floor instead.
                    companion.markDecisionLoadFailed()
                    toast("决策模型加载失败：${it.message}")
                }
        }
    }

    /**
     * Restores the persisted router binding on startup. Called once from the view
     * model's initializer.
     */
    private fun restoreDecisionModel() {
        val bound = storage.loadRouting().decisionModelId
        if (bound.isBlank()) {
            companion.bindDecisionModel("")
            return
        }
        companion.bindDecisionModel(bound)
        loadDecisionModel(bound)
    }

    // ------------------------------------------------------------- personas

    fun selectPersona(id: String) {
        _activePersonaId.value = id
        storage.setActivePersonaId(id)
        _personas.value.firstOrNull { it.id == id }?.let { toast("已切换人设：${it.name}") }
    }

    fun upsertPersona(persona: Persona) {
        val list = _personas.value.toMutableList()
        val idx = list.indexOfFirst { it.id == persona.id }
        if (idx >= 0) list[idx] = persona else list.add(persona)
        _personas.value = list
        storage.savePersonas(list)
    }

    fun deletePersona(id: String) {
        if (id == PersonaPresets.default.id) {
            toast("内置人设不能删除")
            return
        }
        val list = _personas.value.filterNot { it.id == id }.toMutableList()
        _personas.value = list
        storage.savePersonas(list)
        if (_activePersonaId.value == id) selectPersona(PersonaPresets.default.id)
    }

    fun updateUserProfile(transform: (UserProfile) -> UserProfile) {
        val next = transform(_userProfile.value)
        _userProfile.value = next
        storage.saveUserProfile(next)
    }

    /** Per-conversation override, written straight into the conversation record. */
    fun updateConversationModels(conversationId: String, transform: (Conversation) -> Conversation) {
        // 用 ConversationList 而不是 toMutableList()：后者得到的是普通 ArrayList，
        // 逐元素比较下"内容没变"就发不出去（见 ConversationList 的说明）。
        _conversations.value = ConversationList(
            _conversations.value.map { if (it.id == conversationId) transform(it) else it },
        )
        persistConversations()
    }

    /**
     * 改这个对话自己的设置（系统提示词 / 决策模型 / 采样）。
     *
     * 和 [updateConversationModels] 分开只是为了读起来清楚：那一个改的是"用哪个模型"，
     * 这一个改的是"这个对话怎么说话"。两者都只影响这一个对话。
     */
    fun updateConversationSettings(conversationId: String, transform: (Conversation) -> Conversation) {
        _conversations.value = ConversationList(
            _conversations.value.map {
                if (it.id == conversationId) transform(it).also { c -> c.updatedAt = System.currentTimeMillis() } else it
            },
        )
        emitConversations()
        // 「多重思考」这类开关要立刻让应用级的钩子看到（它决定记忆抽取要不要借聊天模型）。
        publishActiveConversation()
        persistConversations()
    }

    // ------------------------------------------------------------ 四类任务的路由

    /**
     * 这个对话选定的**生图模型**在本机的文件路径。
     *
     * 返回 null 时表示"没有指定"——引擎会退回扫目录的老办法（那是没得选时的兜底）。
     * 指定了但没下载/文件没了，也返回 null，但会在诊断里留一句，
     * 因为"选了却没生效"必须能查。
     */
    fun imageModelPathFor(conv: Conversation): String? {
        val id = conv.effectiveImageModel(_routing.value)
        // 对话里没指定时，用**生图页「选择」过的那一个**（ImageSettings.customModelPath）。
        // 两个入口指的是同一件东西（这次生图用哪个 checkpoint），分开算必然出现
        // 「在生图页选好了，对话里让它画却又换了模型」。
        if (id.isBlank()) {
            return _image.value.customModelPath?.takeIf { p -> p.isNotBlank() && java.io.File(p).isFile }
        }
        val model = _models.value.firstOrNull { it.id == id }
        val path = model?.localPath
        if (path == null || !java.io.File(path).isFile) {
            android.util.Log.w("AppViewModel", "生图模型不可用：$id")
            return null
        }
        return path
    }

    /**
     * 测试模式里的「试画一张」：**用当前生效的路由**画一张 64×64 的图。
     *
     * 为什么要有它：生图链路上有四个可能断的地方（后端插件 / checkpoint /
     * 模型加载 / 出图），而"生图不能用"这个反馈本身不带任何信息。
     * 这个按钮把整条链路跑一遍，并明确回答"这张是谁画的、花了多久、为什么不是模型画的"。
     * 64×64 是刻意的：真模型也只要几秒，能把"能不能跑通"和"画得好不好"分开。
     */
    suspend fun debugDrawTest(): String {
        val started = System.currentTimeMillis()
        val conv = activeConversation()
        val res = ImageEngine.generateWithSettings(
            storage = storage,
            prompt = "a green forest, self test",
            settings = _image.value.copy(width = 64, height = 64),
            modelPath = conv?.let { imageModelPathFor(it) },
        )
        val ms = System.currentTimeMillis() - started
        return res.fold(
            onSuccess = { g ->
                val via = if (g.via == ImageEngine.Via.DIFFUSION) "生图模型出图" else "内置渲染器出图"
                "结果：" + via + " · " + ms + "ms · " + g.file.name +
                    (if (g.note.isNotBlank()) "\n原因：" + g.note else "")
            },
            onFailure = { "失败：" + (it.message ?: "未知") + "（" + ms + "ms）" },
        )
    }

    /** 这个对话选定的**识图模型**（多模态）。 */
    fun visionModelFor(conv: Conversation): com.localmind.data.LocalModel? {
        val id = conv.effectiveVisionModel(_routing.value)
        if (id.isBlank()) return null
        return _models.value.firstOrNull { it.id == id }
    }

    /**
     * 四类任务各自会用哪个模型 —— 给测试模式显示。
     *
     * 「多模型配合」最难验证的地方就是**界面上看不出谁在用谁**：
     * 用户选了四个模型，出问题时不知道是没生效还是模型不行。把解析结果直接摊开，
     * 一眼就能对上。
     */
    fun taskRoutingReport(conv: Conversation?): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val routing = _routing.value
        fun nameOf(id: String): String {
            if (id.isBlank()) return "未指定"
            _providers.value.firstOrNull { it.id == id }?.let { return it.name + "（API）" }
            val m = _models.value.firstOrNull { it.id == id }
            if (m == null) return id
            return m.name + if (m.isDownloaded) "" else "（未下载）"
        }
        val chat = conv?.effectiveChatModel(routing).orEmpty()
        out += "对话" to (if (chat.isBlank()) "当前已加载：${
            engine.state.value.modelName ?: "无"
        }" else nameOf(chat))
        out += "识图" to buildString {
            append(nameOf(conv?.effectiveVisionModel(routing).orEmpty()))
            append(if (engineState.value.visionReady) "（现在能看图）" else "（现在看不见图：缺 mmproj 或未加载）")
        }
        out += "生图" to nameOf(conv?.effectiveImageModel(routing).orEmpty())
        val decision = if (conv?.companionMode == true) conv.decisionModelId else routing.decisionModelId
        out += "决策" to if (decision.isBlank()) "内置规则（不占模型）" else nameOf(decision)
        return out
    }

    // ------------------------------------------------------------ 设备感知

    /**
     * 这个对话开了哪几项设备感知。没开就是空集，调用方据此完全跳过。
     *
     * 只对**人设聊天**生效：AI 对话的定位是"最快地问事"，多一次定位查询
     *（可能几百毫秒到几秒）就违背了它的定义。
     */
    private fun senseNeeds(conv: Conversation): Set<com.localmind.service.DeviceSense.Need> {
        if (!conv.companionMode) return emptySet()
        return buildSet {
            if (conv.abilities.battery) add(com.localmind.service.DeviceSense.Need.BATTERY)
            if (conv.abilities.location) add(com.localmind.service.DeviceSense.Need.LOCATION)
            if (conv.abilities.weather) add(com.localmind.service.DeviceSense.Need.WEATHER)
        }
    }

    /**
     * 按需读一次设备，拼成给模型的段。
     *
     * **这里最关键的一步是 [com.localmind.service.DeviceSense.needs]**：
     * 只有这句话聊到电量/位置/天气时才真的去读。不是每回合都读 —— 那既费电、
     * 又会让模型每句话都汇报天气（最像机器人的说话方式）。
     *
     * 读不到就返回空串：调用方什么都不加，模型按自己的知识答。
     */
    private suspend fun readDeviceSense(conv: Conversation, userText: String): String {
        val enabled = senseNeeds(conv)
        if (enabled.isEmpty()) return ""
        val need = com.localmind.service.DeviceSense.needs(userText, enabled)
        if (need.isEmpty()) return ""
        val battery = if (com.localmind.service.DeviceSense.Need.BATTERY in need) {
            com.localmind.service.DeviceSense.battery(localApp)
        } else null
        // 位置用一个比默认更短的预算：这一步挡在用户消息前面，4 秒还没结果就别等了
        //（历史位置通常秒回，等不到的多半是室内没信号）。
        val place = if (com.localmind.service.DeviceSense.Need.LOCATION in need ||
            com.localmind.service.DeviceSense.Need.WEATHER in need
        ) {
            com.localmind.service.DeviceSense.place(localApp, budgetMs = 4_000L)
        } else null
        val weather = if (com.localmind.service.DeviceSense.Need.WEATHER in need && place != null) {
            com.localmind.service.DeviceSense.weather(place)
        } else null
        val block = com.localmind.service.DeviceSense.buildBlock(
            battery = battery,
            place = place,
            weather = weather,
            userNickname = _userProfile.value.nickname.ifBlank { "对方" },
        )
        // 注入的内容必须留痕：出问题时"它怎么不知道天气"和"注入了但模型没说"
        // 是两件完全不同的事，日志能一眼分清。
        if (block.isNotBlank()) {
            android.util.Log.i("DeviceSense", "注入：" + block.replace("\n", " ").take(220))
        }
        return block
    }

    /**
     * 设置页「现在读一次」：把开了的那几项强制读一遍，返回人话。
     *
     * 和聊天走**同一套读取函数**，所以这里显示的就是模型会看到的东西。
     * 一个只能演示的按钮没有意义。
     */
    suspend fun senseSnapshot(conv: Conversation): String {
        val need = senseNeeds(conv)
        if (need.isEmpty()) return ""
        return com.localmind.service.DeviceSense.snapshot(localApp, need)
    }

    /** 定位权限 / 定位开关的当前状态，给设置页显示"缺什么"。 */
    fun locationReady(): Boolean =
        com.localmind.service.DeviceSense.hasLocationPermission(localApp) &&
            com.localmind.service.DeviceSense.locationEnabled(localApp)

    fun hasLocationPermission(): Boolean =
        com.localmind.service.DeviceSense.hasLocationPermission(localApp)

    /**
     * 对话设置里选了「对话模型」。
     *
     * 光写字段是不够的 —— 真正回答你的是**已经加载在内存里的那个模型**，
     * 所以这里顺手把它切换过去（本地模型），下次发消息就是直接开跑，
     * 不用在发送路径上临时等一次几秒的加载。
     */
    fun selectConversationChatModel(conversationId: String, modelId: String) {
        updateConversationModels(conversationId) { it.copy(chatModelId = modelId) }
        val model = _models.value.firstOrNull { it.id == modelId && !it.voice } ?: return
        if (!model.isDownloaded) {
            download(model)
            toast("开始下载 ${model.name}，下完就能用它了")
            return
        }
        if (engine.state.value.ready && engine.state.value.modelPath == model.localPath) return
        loadModel(model)
    }

    /**
     * 对话设置里选了「决策模型」。
     *
     * 决策模型跑在**独立的第二个引擎**上（见 [loadDecisionModel]），设备上只有一个实例，
     * 所以规则是：选择按对话记，切到哪个对话就把这个引擎绑到它选的模型上。
     */
    fun selectConversationDecisionModel(conversationId: String, modelId: String) {
        updateConversationSettings(conversationId) { it.copy(decisionModelId = modelId) }
        setDecisionModel(modelId)
    }

    /**
     * 改**这个对话里 AI 是谁**（名字/头像/年龄/性别/人设/是否当人 + 模型覆盖）。
     *
     * 和改全局人设是两件事：这里改完只影响这一个对话。同一部手机上，
     * 「和小美聊工作」与「和老陈聊人生」本就该是两个人。
     */
    fun updateConversationIdentity(conversationId: String, transform: (Conversation) -> Unit) {
        val conv = _conversations.value.firstOrNull { it.id == conversationId } ?: return
        transform(conv)
        conv.updatedAt = System.currentTimeMillis()
        // 这里必须用 emitConversations()：transform 是**就地改**这个对象的，
        // 直接重发 toMutableList() 会因为"逐元素相等"而被 StateFlow 丢掉，
        // 表现就是"改完要退出去再进来才生效"。
        emitConversations()
        // 改了「是否当人」要立刻让生成钩子看到，否则本轮的经验积累会被漏掉。
        publishActiveConversation()
        persistConversations()
    }

    /** The remote provider currently selected for chat, if any. */
    fun activeProvider(): RemoteProvider? =
        _providers.value.firstOrNull { it.id == _backend.value && it.enabled }

    // ---------------------------------------------------------------- models

    fun refreshModels() {
        viewModelScope.launch {
            val gguf = withContext(Dispatchers.IO) { modelStore.resolveAll() }
            // 语音音色也列进模型页。
            //
            // 用户要的是「在一个地方看到全部可下载的东西」。但**下载路径必须分开**：
            // 音色是 .tar.bz2 音色包，要解压到 files/voices/，走音色下载器。
            // 所以这里只做展示映射，模型页看到 voice = true 就改调 [downloadVoice]。
            // 详细理由见 VoiceEntry.toLocalModel 的注释。
            val packLicenses = withContext(Dispatchers.IO) { storage.loadVoicePacks() }
            val voices = _voiceCatalog.value.map { v ->
                val lic = packLicenses.values.firstOrNull { p -> p.voices.any { it.id == v.id } }?.license
                v.toLocalModel(lic ?: "见音色原始许可")
            }
            _models.value = gguf + voices
        }
    }

    /**
     * **模型页统一的下加入口。**
     *
     * 为什么要有它：模型页现在同时列 GGUF 和语音音色，而这两类的下载路径
     * **必须不同** —— GGUF 存进 `files/models/`，音色是 .tar.bz2 要解压到
     * `files/voices/`。让界面去判断"这条该走哪个下载器"迟早会漏一处，
     * 所以判断收在这里：界面只管调 [downloadAny]。
     */
    fun downloadAny(model: com.localmind.data.LocalModel) {
        if (!model.voice) {
            download(model)
            return
        }
        val id = model.id.removePrefix("voice:")
        val entry = _voiceCatalog.value.firstOrNull { it.id == id }
        if (entry == null) {
            toast("找不到这个音色，可能语音插件被停用了")
            return
        }
        // 复用语音页那条：它会先检查语音引擎在不在，避免用户白下 100 MB。
        downloadVoice(entry)
    }

    fun memoryPlanFor(model: LocalModel): ModelMemoryPlan? {
        val file = model.file() ?: return null
        return runCatching {
            com.localmind.data.MemoryPlanner.plan(localApp, file, _settings.value.contextSize, _settings.value.threads)
        }.getOrNull()
    }

    /**
     * 模型详情里那行 GGUF 头信息。**没下载就不显示**。
     *
     * 原来无论有没有文件都调 preview()，而 preview(null) 返回的是
     * 「非 GGUF / 无法解析头部」—— 用户点开一个还没下载的模型，
     * 看到的是「这个文件坏了」，而事实是「还没有这个文件」。
     * 同一句话在两个完全不同的处境里出现，是最容易被当成 bug 报上来的那种文案。
     */
    fun ggufPreview(model: LocalModel): String =
        if (!model.isDownloaded) ""
        else com.localmind.data.MemoryPlanner.preview(modelStore.infoOf(model))

    fun download(model: LocalModel) {
        refreshModels()
        downloader.start(model)
        toast("开始下载 ${model.name}")
        viewModelScope.launch {
            // reflect file appearance as soon as the download completes
            delay(1500)
            refreshModels()
        }
    }

    fun pauseDownload(model: LocalModel) = downloader.pause(model.id)

    fun deleteModel(model: LocalModel) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = model.file()
            file?.delete()
            // 单连接的 .part **和**多连接的段目录都要删。
            //
            // 原来只删 .part，于是「下到一半反悔」的用户会在 files/models/ 里留下
            // 一个几百 MB 的段目录：存储占用看不见它、模型列表里也没有它，
            // 只有手机空间在变少。（实测上一个安装里躺着
            // sd_turbo-f16-q8_0.gguf.2023745376.part.d。）
            storage.leftoverParts(model.fileName).forEach { it.deleteRecursively() }

            // 多模态模型的视觉投影是**第二个文件**。删主模型时如果不带上它，
            // 一个 100-600 MB 的 mmproj 会永远留在手机上，而任何界面都再也看不到它。
            // 但它是可以共用的（清单里「对话」和「看图」两条指向同一个主模型），
            // 所以只在没有别的条目还引用它时才删。
            val mmproj = model.mmprojFileName
            if (mmproj.isNotBlank()) {
                val stillUsed = modelStore.resolveAll()
                    .any { it.id != model.id && it.mmprojFileName == mmproj }
                if (!stillUsed) File(storage.modelsDir, mmproj).delete()
            }

            if (model.imported) modelStore.removeImported(model.id)
            withContext(Dispatchers.Main) {
                toast("已删除 ${model.name}")
                refreshModels()
            }
        }
    }

    fun importModel(file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            val target = File(storage.modelsDir, file.name)
            runCatching {
                if (file.absolutePath != target.absolutePath) {
                    file.inputStream().use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                modelStore.addImported(target)
            }.onSuccess {
                withContext(Dispatchers.Main) {
                    toast("已导入 ${target.name}")
                    refreshModels()
                }
            }.onFailure {
                withContext(Dispatchers.Main) { toast("导入失败：${it.message}") }
            }
        }
    }

    fun importFromRepo(input: String) {
        viewModelScope.launch {
            toast("正在解析仓库…")
            val model = withContext(Dispatchers.IO) { downloader.resolveRemote(input) }
            if (model == null) {
                toast("无法解析，请检查 仓库ID（owner/repo）或直链")
                return@launch
            }
            _models.value = _models.value + model
            downloader.start(model)
            toast("开始下载 ${model.name}")
        }
    }

    fun loadModel(model: LocalModel) {
        // ---- 生图（扩散）模型不能当聊天模型加载 ----
        //
        // 用户看到模型页里有个「加载」就会点，而扩散 checkpoint 的 GGUF 结构和文本
        // 模型完全不同：llama.cpp 加载它必然失败，**而且可能在原生层直接 abort**
        //（真机诊断报告里就有连续 5 次「llama.cpp 加载失败（sdxs-512-…）」，
        // 紧接着一条异常退出记录）。所以在这里挡掉，并告诉他去哪儿用。
        if (model.isImageModel) {
            toast("「" + model.name + "」是生图模型，不能当聊天模型加载 —— 到「生图」页用它")
            return
        }
        if (model.embedding) {
            toast("「" + model.name + "」是向量模型，不能对话")
            return
        }
        viewModelScope.launch {
            if (engine.state.value.ready && engine.state.value.modelPath == model.localPath) {
                toast("该模型已加载")
                return@launch
            }
            _chat.value = _chat.value.copy(error = null)
            val plan = memoryPlanFor(model)
            _plan.value = plan
            // 配套的视觉投影：有就一起装上（多模态模型才有），没有就只加载主模型。
            val mmproj = model.mmprojFileName
                .takeIf { it.isNotBlank() }
                ?.let { File(storage.modelsDir, it) }
                ?.takeIf { it.isFile && it.length() > 0 }
                ?.absolutePath
            if (mmproj == null && model.vision) {
                // 说清楚而不是默默当文字模型用：用户会以为是模型不认识图。
                toast("这个模型是多模态的，但配套的视觉投影（mmproj）还没下全 —— 识图会保持关闭")
            }
            val result = engine.load(model, _settings.value, mmproj)
            result.onSuccess {
                storage.setLastModelId(model.id)
                toast("已加载 ${model.name}（ctx ${engine.state.value.contextSize}）")
            }.onFailure {
                toast("加载失败：${it.message}")
            }
        }
    }

    fun unloadModel() {
        viewModelScope.launch {
            engine.unload()
            toast("已卸载模型，释放内存")
        }
    }

    // ------------------------------------------------------- 常驻与预热
    //
    // 手机上"多模型同时跑"的真实约束是**内存容量**，不是算力也不是并发。
    // 当前这份应用的常驻策略（也是唯一在 8~12 GB 机器上站得住的策略）：
    //
    //   1. 对话模型：1 个。切换模型 = 卸载再加载，所以启动时预热能省掉那一次。
    //   2. 决策模型：可选的第 2 个，ctx 固定 1024、2 线程 —— 小到可以和对话模型共存。
    //   3. 语音：系统 TTS 常驻（几乎不占内存），神经音色/外部引擎**按需**加载。
    //   4. 生图后端：一个 .so，checkpoint 只在出图那一刻进内存。
    //
    // 也就是说"同时跑多个"的正确做法不是"多开几个引擎"，而是**把常驻的压到最小，
    // 把其他的做成按需 + 快速换入**。这就是下面这两个函数存在的理由。

    /** 启动时预热上次的对话模型（可关，见 [Storage.preloadModel]）。 */
    fun preloadChatModel() {
        if (!storage.preloadModel()) return
        if (engine.state.value.ready || engine.state.value.loading) return
        val wanted = _routing.value.chatModelId.ifBlank { storage.lastModelId().orEmpty() }
        if (wanted.isBlank()) return
        val model = modelStore.resolveAll().firstOrNull { it.id == wanted } ?: return
        if (model.isImageModel || model.embedding) return
        if (!File(model.localPath).isFile) return
        viewModelScope.launch {
            if (engine.state.value.ready || engine.state.value.generating) return@launch
            android.util.Log.i("AppViewModel", "预热模型：${model.name}")
            engine.load(model, _settings.value, null).onFailure {
                android.util.Log.w("AppViewModel", "预热失败：${it.message}")
            }
        }
    }

    fun setPreloadModel(on: Boolean) {
        storage.savePreloadModel(on)
        if (on) preloadChatModel() else toast("下次启动不再预热")
    }

    /**
     * 现在有哪些东西是常驻的。
     *
     * 界面要显示**真的**，不是"用户选了什么" —— 决策模型选了但加载失败的情况
     * 必须如实写出来，否则用户会以为它在省内存。
     */
    fun residencyLines(): List<Pair<String, String>> {
        val chat = engine.state.value
        val decision = decisionEngine.state.value
        val out = mutableListOf<Pair<String, String>>()
        out += "对话模型" to if (chat.ready) {
            "${chat.modelName ?: "已加载"} · ctx ${chat.contextSize}"
        } else if (chat.loading) {
            "加载中…"
        } else {
            "未加载"
        }
        out += "决策模型" to when {
            decision.ready -> "${decision.modelName ?: "已加载"} · ctx ${decision.contextSize}"
            decision.loading -> "加载中…"
            _routing.value.decisionModelId.isBlank() -> "未设置（用内置规则）"
            else -> "未加载（会在用时加载）"
        }
        out += "语音" to when {
            _breeze.value.ready -> "外部引擎 · ${_breeze.value.active?.spec?.label ?: ""}"
            neuralTts.loadedVoiceId.isNotEmpty() -> "神经音色 · ${neuralTts.loadedVoiceId}"
            else -> "系统 TTS"
        }
        out += "生图后端" to if (diffusionBackendReady.value) "已加载" else "未加载"
        return out
    }

    // ------------------------------------------------------------------ chat

    /**
     * 新建一个对话。
     *
     * ## 类型在建的时候定，之后不能改
     *
     * 两种对话是**两种东西**，不是一个开关：
     *
     *  * [ChatMode.AI] —— 无人设。不套身份、不留记忆、不跑拟人层，
     *    发送路径上直接调模型。要的是**最快回应**；
     *  * [ChatMode.COMPANION] —— 人设聊天（人机恋那种）。有身份、有记忆、
     *    有情绪和关系状态，提示词长、每轮还要做关系记账，慢但像个人。
     *
     * 合成一个再给个开关切换，等于让用户在两套完全不同的东西之间反复横跳，
     * 而且每切一次都要处理"已有的记忆怎么办"。所以建的时候就选清楚。
     */
    fun newConversation(mode: ChatMode = ChatMode.COMPANION): Conversation {
        val conv = blankConversation(mode)
        _conversations.value = ConversationList(_conversations.value + conv)
        _activeId.value = conv.id
        _inChat.value = true
        publishActiveConversation()
        persistConversations(delayMs = 0)
        return conv
    }

    private fun blankConversation(mode: ChatMode = ChatMode.COMPANION): Conversation {
        val companion = mode.isCompanion
        val conv = Conversation(
            id = TextUtils.randomId("conv"),
            title = Conversation.DEFAULT_TITLE,
            personaId = if (companion) _activePersonaId.value else "",
        )
        if (companion) {
            // 新对话以当前活跃人设为起点。之后它有自己的身份，改这个对话的人设
            // 不会影响别的对话 —— 关系是按对话建立的。
            conv.adoptPersona(activePersona())
            conv.companionMode = true
        } else {
            // AI 对话**不带人设**：名字、头像、年龄、情绪、关系状态一律没有。
            // 这既是产品定义（"就是问事用的"），也是"最快回应"的前提 ——
            // 发送路径见到 companionMode=false 就会跳过整个拟人层。
            conv.companionMode = false
            conv.abilities = AiAbilities.PLAIN
        }
        return conv
    }

    /** iOS Messages: tap a row to push into the thread. */
    fun openConversation(id: String) {
        _activeId.value = id
        _inChat.value = true
        publishActiveConversation()
        maybeProactiveOpening(id)
    }

    /**
     * 「主动来找你说话」—— 打开一个对话时，它可能先开口。
     *
     * 只在**人设聊天**里、且用户打开了这个能力时才会发生。触发条件刻意保守：
     *  1. 上一句是**你说的**（它欠你一个回应，进来看到你还在等）；
     *  2. 或者已经隔了大半天（"好久没聊了"该由它先开口）。
     *
     * 两个不做的理由同样重要：
     *  * 空对话不主动 —— 还不认识就先搭话，那是推销不是恋爱；
     *  * 引擎忙的时候不主动 —— 抢锁只会让你发的第一句话卡在后面。
     */
    fun maybeProactiveOpening(conversationId: String) {
        val conv = _conversations.value.firstOrNull { it.id == conversationId } ?: return
        if (!conv.companionMode || !conv.abilities.proactive) return
        if (conv.messages.isEmpty()) return
        if (_chat.value.streaming || engine.state.value.generating) return
        val remote = activeProvider()
        if (!engine.state.value.ready && remote == null) return
        val last = conv.messages.last()
        val idleMs = System.currentTimeMillis() - conv.updatedAt
        if (last.role != Role.USER && idleMs < PROACTIVE_AFTER_IDLE_MS) return
        if (proactiveJob?.isActive == true) return

        proactiveJob = viewModelScope.launch {
            val persona = conv.persona(activePersona())
            val state = companion.stateOf(conv.id)
            // 召回走的是**已有记忆**，不额外调模型挑：主动开口这一句不值得
            // 再多花一次推理，慢一点都会让"她先说话"这件事变得尴尬。
            val recalled = runCatching { companion.recall(state, last.content) }.getOrDefault(emptyList())
            // 主动开口时也读一次设备：它更该知道"你那边在下雨"。
            // 触发文本用最后一条用户消息 —— 没有它就没有"聊到相关内容"这回事，
            // 那就干脆不读（主动开口不等于可以随便读用户的位置）。
            val sense = runCatching { readDeviceSense(conv, last.content) }.getOrDefault("")
            val system = buildSystemPrompt(_settings.value.systemPrompt, _voice.value, persona) +
                com.localmind.data.CompanionPrompt.build(
                    persona = persona,
                    state = state,
                    recalled = recalled,
                    userNickname = _userProfile.value.nickname.ifBlank { "对方" },
                    hoursSinceLastTalk = companion.hoursSinceLastTalk(state),
                    experiences = companion.topExperiencesFor(state),
                    pendingQuestion = null,
                ) + sense +
                "\n\n【现在】对方刚打开你们的对话，还没说话。" +
                "你想先说一句 —— 一两句话，像随手发条消息。不要问「有什么可以帮您」，" +
                "不要罗列，也不要解释你为什么突然说话。"
            val history = buildList {
                add(Role.SYSTEM to system)
                conv.messages.takeLast(12).forEach { add(it.role to it.content) }
            }
            _chat.value = _chat.value.copy(thinking = true)
            val settings = conv.sampling.applyTo(
                if (remote != null) GenerationSettings() else _settings.value.copy(maxTokens = 160),
            )
            val result = if (remote != null) {
                remoteEngine.chat(
                    provider = remote,
                    messages = history,
                    temperature = settings.temperature,
                    maxTokens = settings.maxTokens,
                )
            } else {
                engine.chat(
                    messages = history,
                    settings = settings,
                    // 这**不是**一轮对话：没有用户发言，绝不能触发记忆抽取，
                    // 否则会把上一条用户消息再学一遍。
                    notifyTurnComplete = false,
                )
            }
            _chat.value = _chat.value.copy(thinking = false)
            result.onSuccess { raw ->
                val text = com.localmind.util.TextUtils.splitReasoning(raw).answer.trim()
                if (text.isNotEmpty()) {
                    conv.messages += ChatMessage(
                        id = TextUtils.randomId("msg"),
                        role = Role.ASSISTANT,
                        content = text,
                    )
                    conv.updatedAt = System.currentTimeMillis()
                    emitConversations()
                    persistConversations()
                    if (conv.abilities.voice && _voice.value.enabled) tts.speakQueued(text, _voice.value)
                }
            }
        }
    }

    /** Back to the conversation list. */
    fun leaveConversation() {
        _inChat.value = false
        _activeId.value = null
        com.localmind.LocalMindApp.activeConversationId = null
        com.localmind.LocalMindApp.userIsTyping = false
    }

    /**
     * 把"当前聊的是哪段关系、它是不是当人模式"发布到应用级状态。
     *
     * `LlamaEngine.onTurnComplete` 是应用级钩子（HTTP API 也会走），拿不到 ViewModel，
     * 所以只能通过 `LocalMindApp` 的静态字段传。人设变化后也要重发一次。
     */
    private fun publishActiveConversation() {
        val id = _activeId.value
        com.localmind.LocalMindApp.activeConversationId = id
        val conv = id?.let { cid -> _conversations.value.firstOrNull { it.id == cid } }
        com.localmind.LocalMindApp.activeConversationHumanMind = conv?.companionMode ?: false
        syncDecisionModelFor(conv)
        // 没有专用决策模型时，要不要借聊天模型来判断"该记什么"？
        // 跟这个对话的「多重思考」走：开着说明用户愿意为质量付费，关着就只用内置规则
        // —— 后者不花任何模型调用，是"最快"的一部分。
        companion.allowChatModelFallback = conv?.deepThinking == true
    }

    /**
     * 把独立决策引擎绑到**这个对话**选的决策模型上。
     *
     * 决策模型是第二个引擎实例（两个模型同时在内存里已经很贵，按对话各起一个不现实），
     * 所以：选择按对话记，绑定时按当前对话来。没选就回落到内置规则 —— 那是最快的路径，
     * 而且不占任何模型。
     */
    private fun syncDecisionModelFor(conv: Conversation?) {
        // AI 对话不跑拟人层，也就没有"该记什么/什么情绪"要判断，不必为它占一个决策模型。
        val want = if (conv?.companionMode == true) conv.decisionModelId else ""
        if (want == _routing.value.decisionModelId) return
        setDecisionModel(want)
    }

    fun selectConversation(id: String) = openConversation(id)

    /**
     * 清空一个对话的**消息**，保留这个对话本身（人设、记忆、设置都不动）。
     *
     * 和 [deleteConversation] / [forgetPersona] 是三件不同的事，别混：
     *  * 清空消息 —— 聊天记录没了，但它还记得你是谁；
     *  * 清空记忆 —— 记录还在，但它忘了你（人设聊天才有）；
     *  * 删除对话 —— 全没了。
     * 用户要的往往是第一种："这段聊乱了，重开"，而不是"我们重新认识"。
     */
    fun clearConversationMessages(id: String) {
        val conv = _conversations.value.firstOrNull { it.id == id } ?: return
        conv.messages.clear()
        conv.title = Conversation.DEFAULT_TITLE
        conv.updatedAt = System.currentTimeMillis()
        emitConversations()
        persistConversations(delayMs = 0)
        toast("已清空这个对话的消息")
    }

    fun deleteConversation(id: String) {
        val list = _conversations.value.filterNot { it.id == id }
        _conversations.value = ConversationList(list)
        if (_activeId.value == id) {
            // 删光了就回到列表页的空态，而不是替他新建一个。
            val next = list.firstOrNull()?.id
            _activeId.value = next
            if (next == null) _inChat.value = false
            publishActiveConversation()
        }
        persistConversations(delayMs = 0)
    }

    fun activeConversation(): Conversation? =
        _conversations.value.firstOrNull { it.id == _activeId.value }

    fun setDraft(text: String) {
        _chat.value = _chat.value.copy(draft = text)
        // 告诉应用级钩子「人正在敲字」：这时候后台的学习任务该让开（见 LlamaEngine.Lane）。
        com.localmind.LocalMindApp.userIsTyping = text.isNotBlank()
    }

    fun stopGeneration() {
        engine.cancel()
        generateJob?.cancel()
        _chat.value = _chat.value.copy(streaming = false)
    }

    fun send(text: String) {        val prompt = text.trim()
        if (prompt.isEmpty()) return
        val conv = activeConversation() ?: return

        // ---------------------------------------------------- 这个对话用哪个模型
        //
        // 模型是**按对话选的**（对话自己的设置页里那一栏）。以前
        // [Conversation.effectiveChatModel] 定义了却没有任何调用点 —— 于是
        // "本对话用的模型"选完等于没选，回答你的永远是碰巧加载在内存里的那一个。
        //
        // 解析顺序：这个对话指定的 → 全局后端（_backend / 路由）→ 当前已加载的。
        val targetId = conv.effectiveChatModel(_routing.value)
        val targetProvider = _providers.value.firstOrNull { it.id == targetId && it.enabled }
        // 只有"对话自己没指定"时才回落到全局后端，否则会盖掉用户在这个对话里的选择。
        val remote = targetProvider ?: if (targetId.isBlank()) activeProvider() else null
        // 对话指定了一个**本地**模型：它可能还没加载进来。
        val wantedLocal = if (remote == null && targetId.isNotBlank()) {
            _models.value.firstOrNull { it.id == targetId && !it.voice }
        } else {
            null
        }
        val needSwitch = wantedLocal != null && wantedLocal.isDownloaded &&
            !(engine.state.value.ready && engine.state.value.modelPath == wantedLocal.localPath)

        if (remote == null && !needSwitch) {
            if (!engine.state.value.ready) {
                toast("请先到「模型」页下载并加载一个模型，或在「能力」页接入一个 API 服务")
                _tab.value = Tab.MODELS
                return
            }
            if (engine.state.value.encoderOnly) {
                toast("当前加载的是向量模型（encoder-only），不能对话；请换一个 Instruct 模型")
                _tab.value = Tab.MODELS
                return
            }
        }

        // 把待发送的图片**存进这条消息**，然后清掉待发送状态。
        //
        // 这里原来有 bug：图片路径只活在 `_pendingImage` 里，发出去时
        // **从来没写进 ChatMessage**，所以一发送缩略图就消失、历史里也永远
        // 看不到自己发过的图（数据模型里明明有 `imagePath` 字段）。
        // 现在整条链路是：选图 → ChatImageStore 落盘 → 写进消息 → 气泡里渲染。
        val attached = _pendingImage.value
        val userMessage = ChatMessage(
            id = TextUtils.randomId("msg"),
            role = Role.USER,
            content = prompt,
            imagePath = attached,
        )
        conv.messages += userMessage
        // 这条消息在列表里的位置：等会儿要把「易变段提示词」挂到它身上（见下面 systemPrompt）。
        val userMessageIndex = conv.messages.lastIndex
        if (attached != null) _pendingImage.value = null
        if (conv.title == Conversation.DEFAULT_TITLE) conv.title = prompt.take(18)
        conv.updatedAt = System.currentTimeMillis()
        _chat.value = _chat.value.copy(draft = "", error = null, streaming = true, thinking = true)
        com.localmind.LocalMindApp.userIsTyping = false
        persistConversations()

        val voiceSettings = _voice.value
        // 用**这个对话自己的**AI 身份，而不是全局人设。
        val persona = conv.persona(activePersona())
        val companionState = companion.stateOf(conv.id)

        // 短话连发会**边生成边开新气泡**，所以这两个必须是可变的，而且是同一个
        // 变量 —— 收尾（成功 / 失败 / 生图回填）用的都是"当前最后那一条"。
        var reply = ChatMessage(TextUtils.randomId("msg"), Role.ASSISTANT, "")
        conv.messages += reply
        var replyIndex = conv.messages.lastIndex
        val startReplyIndex = replyIndex
        // 短话连发：已经"发出去"的原文游标（在完整回答里的下标）。
        // 流式对话（像人一样一句一句发）的默认值**跟对话模式走**：
        //  * 拟人模式：开 —— 那是这个模式该有的样子；
        //  * 普通 AI 对话：关 —— 要一段完整的答案，不要被切成几条。
        // 全局开关仍然可以强制打开（比如 AI 对话也想要那种口气）。
        // 这两个开关是**按对话**的（见 Conversation.burstReplies / deepThinking）：
        // 一个用来谈心的对话和一个用来问事的对话，节奏本来就不该一样。
        val burst = conv.burstReplies ?: conv.companionMode
        val deepThinking = conv.deepThinking ?: false
        var burstFrom = 0

        generateJob = viewModelScope.launch {
            // ---- 先把这个对话指定的模型切上来 ----
            //
            // 切换要几秒（要重新 mmap 权重），所以只在**真的没加载**时才做；
            // 在对话设置里点选模型时已经预热过一次（selectConversationChatModel），
            // 正常情况下走到这里引擎里已经是它了。
            // needSwitch 里已经包含了 wantedLocal != null，别再写一遍（编译器会报 always true）。
            if (needSwitch) {
                withContext(Dispatchers.Main) { toast("正在切到 ${wantedLocal.name}…") }
                engine.load(wantedLocal, _settings.value).onFailure { e ->
                    withContext(Dispatchers.Main) {
                        toast("切换失败，改用当前模型：${e.message}")
                    }
                }
            }

            // 逻辑召回会调用模型（让模型按含义挑，而不是算字面重叠），所以必须在
            // 协程里、且在占用引擎之前完成。它跑的时候引擎是空闲的，不会和主回复抢锁。
            // ---- AI 对话：不经过拟人层 ----
            //
            // 建的时候选「AI 对话」的会话 companionMode=false，那里就没有"人"这回事：
            // 不召回记忆、不拼关系状态、不加人设与情绪指令。少一次模型调用
            //（召回挑选）和一大段提示词 —— 这就是"最快回应"的来源。
            val aiOnly = !conv.companionMode

            // ---- 召回 / 联网 / 设备感知：**并行**做，别一条条排队 ----
            //
            // 这三件事互不依赖，但它们都不便宜：按含义召回可能是一次小模型的生成，
            // 联网检索是一次网络往返，设备感知要读定位（冷启动能到 2-4 秒）。
            // 串行做的话，首 token 要等三者相加；并行之后只等最慢的那一个。
            //
            // 它们都 happens-before「占用对话引擎」，所以不会和真正的回答抢锁。
            val (recall, senseBlock, webBlock) = kotlinx.coroutines.coroutineScope {
                // AI 对话不经过拟人层：不召回记忆、不读设备（那两项只属于人设聊天）。
                val recallDeferred = async {
                    if (aiOnly) {
                        com.localmind.service.CompanionMind.Recall()
                    } else {
                        runCatching {
                            companion.recallByMeaning(companionState, prompt)
                        }.getOrElse { com.localmind.service.CompanionMind.Recall() }
                    }
                }
                // 设备感知：只有聊到才读（readDeviceSense 内部按关键词判断）。
                val senseDeferred = async {
                    if (aiOnly) {
                        ""
                    } else {
                        runCatching { readDeviceSense(conv, prompt) }.getOrElse {
                            android.util.Log.w("AppViewModel", "设备感知读取失败：" + it.message)
                            ""
                        }
                    }
                }
                // 联网检索：「允许 AI 上网」打开时每条消息先查一次 —— 开关是用户自己开的，
                // 他就希望回答有网上的依据；查不到就什么都不注入（塞一段"检索失败"只会污染上下文）。
                val webDeferred = async {
                    if (_capabilities.value.webSearch) {
                        runCatching {
                            com.localmind.service.WebSearch.search(
                                prompt,
                                _capabilities.value.webSearchEndpoint,
                            )
                        }.getOrDefault(emptyList())
                            .let { com.localmind.service.WebSearch.buildBlock(prompt, it) }
                    } else {
                        ""
                    }
                }
                Triple(recallDeferred.await(), senseDeferred.await(), webDeferred.await())
            }
            if (webBlock.isNotEmpty()) {
                com.localmind.data.Diagnostics.action(localApp, "联网检索：" + prompt.take(24))
            }
            if (webBlock.isNotEmpty()) {
                com.localmind.data.Diagnostics.action(localApp, "联网检索：" + prompt.take(24))
            }

            // ---------------------------------------------------------- 提示词：稳定段 / 易变段
            //
            // ## 为什么必须分开（这是「人设聊天慢得没法用」的根因）
            //
            // 每一轮都要把整段提示词重新喂给模型。llama.cpp 会拿新提示词和 KV cache
            // 里已有的 token **从头逐字节**比最长公共前缀，只有完全相同的那一段能跳过
            // 计算，后面全部要重新 prefill。而手机上 prefill 常常比逐 token 生成还慢：
            // 几千 token 的人设 + 记忆 + 历史 = 十几秒的「发出去没反应」。
            //
            // 原来「几点 / 聊过几轮 / 心情 / 召回了哪几条记忆」全都拼在 system prompt 里。
            // 这些每轮都在变，于是公共前缀在 system prompt 中段就断了 —— 后面的人设、
            // 历史、全部重算。聊天越久越慢，和人设本身没关系，纯粹是布局问题。
            //
            // 现在分两段：
            //  * 稳定段（身份 / 规则 / 能力声明）留在 system prompt —— 它跨轮不变；
            //  * 易变段（关系状态 / 记忆 / 设备感知 / 联网结果）挂到**最新那条用户消息**
            //    前面，并写进那条消息（ChatMessage.promptPrefix）。下一轮重建历史时
            //    逐字节还原，于是「稳定段 + 全部历史」都落在可复用的前缀里，
            //    每轮真正要算的只剩这条新消息。
            //
            // 实测口径见 docs/SPEED.md：命中的 token 数会打在 logcat 的
            // 「prefill: prompt=… reused=…」那一行里，也显示在测试模式。
            val systemPrompt = if (aiOnly) {
                // AI 对话：**没有**人设、没有记忆、没有情绪、也没有全局系统提示词。
                // 只有这个对话自己写的那一句 —— 指令越短，首 token 越快。
                // 全局提示词也在这里被切断：用户要的是「这个对话怎么说话」，
                // 不是一个所有人共用的人格。
                conv.systemPrompt.trim().ifBlank { AI_CHAT_DEFAULT_PROMPT }
            } else {
                buildSystemPrompt(_settings.value.systemPrompt, voiceSettings, persona) +
                    // 「额外的要求」是**追加**，不是替换。
                    //
                    // buildSystemPrompt 里人设的 systemPrompt 一旦非空就会盖掉传进去的 base，
                    // 所以那个 base 不能用来带对话级要求 —— 否则用户写了"别叫我宝贝"，
                    // 只要这个人设有自己的人设正文，那句话就静默失效了。
                    (if (conv.systemPrompt.isBlank()) {
                        ""
                    } else {
                        "\n\n【对方对这个对话的额外要求】\n" + conv.systemPrompt
                    }) +
                    // 长期状态里**几乎不变**的那一半（它是谁、关系到哪一步、行为倾向、
                    // 自己的经历）。放进稳定段和身份设定一起被缓存 —— 见 CompanionPrompt 的说明。
                    com.localmind.data.CompanionPrompt.buildStable(
                        persona = persona,
                        state = companionState,
                        userNickname = _userProfile.value.nickname.ifBlank { "对方" },
                    )
            } +
                // 只在对应能力打开时才告诉它"你可以发图/发表情"。关着的时候连提都不提。
                // 这段只跟对话的设置有关，跨轮不变 → 属于稳定段。
                com.localmind.util.RichReply.instruction(
                    canDraw = conv.abilities.imageGeneration,
                    // ⚠️ 这里必须再问一句"**真的装了表情包吗**"。
                    //
                    // 原来只看对话自己的能力开关，而「人设聊天」默认就开着 emoji ——
                    // 于是模型被告知"你可以写 [[emoji: 😄]]"，可设备上根本没有表情包资源，
                    // 标记解析后什么都不剩，用户看到的是**一个空白回复**
                    //（模拟器实测：LFM2-VL 450M 回了一整条只有标记的回答，气泡是空的）。
                    // 广告一个不存在的能力，比不广告更糟。
                    canEmoji = conv.abilities.emoji && emoji.availableTags().isNotEmpty(),
                ) +
                // ⚠️ 这两段必须各自带括号。
                //
                // 原来写的是 `... + if (burst) {a} else {""} + if (emoji) {b} else {""}`，
                // 而 Kotlin 把 `+` 后面的 if 表达式**一直吃到 else 分支的末尾** ——
                // 实际语义变成「不是 burst 时才加表情包说明」，表情包在短话连发下永远不生效。
                // 少一对括号，就是一个静默失效的开关。
                (if (burst) {
                    // 开了短话连发就得**告诉模型**这件事。只靠程序切分，模型写的是
                    // 一篇连贯的文章，切出来会像被腰斩；模型自己按短句写，切出来才像
                    // 人在发微信。两件事要一起做。
                    "\n\n【说话方式】像发微信一样：一句话一条，每条尽量短" +
                        "（一般不超过 40 字）。想说几句就分成几条说，" +
                        "不要写成一大段，也不要用「首先/其次/总之」这种书面结构。"
                } else {
                    ""
                }) + (if (conv.abilities.emoji && emoji.availableTags().isNotEmpty()) {
                    val pack = storage.loadStickerPack()
                    emoji.promptHint(pack?.promptHint.orEmpty())
                } else {
                    ""
                })

            // ---- 易变段：每轮都会变，所以只能放在最后 ----
            //
            // 它们以前在 system prompt 里。挪出来之后模型看到的仍然是同一批信息
            // （甚至更近：就贴在用户这句话前面），但前缀复用终于能生效了。
            val volatileBlock = buildString {
                if (!aiOnly) {
                    append(
                        com.localmind.data.CompanionPrompt.buildLive(
                            state = companionState,
                            recalled = recall.memories,
                            userNickname = _userProfile.value.nickname.ifBlank { "对方" },
                            hoursSinceLastTalk = companion.hoursSinceLastTalk(companionState),
                            pendingQuestion = recall.pendingQuestion,
                            experiences = recall.experiences,
                        ),
                    )
                }
                // 设备感知（电量/位置/天气）。开关没开、或者这句话没聊到相关内容时是空串。
                if (senseBlock.isNotBlank()) append("\n\n").append(senseBlock.trim())
                // 联网检索结果：每次都可能不一样，绝对不能进稳定段。
                if (webBlock.isNotBlank()) append("\n\n").append(webBlock.trim())
            }.trim()

            // 把易变段挂到**这条用户消息**上，并记住它 —— 下一轮重建历史时逐字节重放，
            // 前缀才能一直命中。
            if (volatileBlock.isNotEmpty() && userMessageIndex in conv.messages.indices) {
                conv.messages[userMessageIndex] = conv.messages[userMessageIndex]
                    .copy(promptPrefix = volatileBlock)
            }

            // 采样参数：**这个对话的微调**盖在基线之上。
            //
            // 基线分两种：
            //  * AI 对话 —— 内置默认值（没有人设可读，也不该读全局，见上）；
            //  * 人设聊天 —— 人设自己的覆盖 > 全局值。
            // 越靠"具体"的越优先，所以同一个模型在不同对话里可以有不同性格。
            val base = if (aiOnly) {
                GenerationSettings()
            } else {
                _settings.value.copy(
                    temperature = persona.temperature ?: _settings.value.temperature,
                    topP = persona.topP ?: _settings.value.topP,
                    maxTokens = persona.maxTokens ?: _settings.value.maxTokens,
                )
            }
            val effective = conv.sampling.applyTo(base)

            // 每一轮记一行"注入了什么"。
            //
            // 排查"它怎么不知道我这边下雨 / 不记得我说过的事"时，第一个要回答的问题
            // 就是**提示词里到底有没有那段**。没有这一行，只能靠猜是没读到、
            // 没注入，还是模型没说。放在操作流水里，导出报告也自带。
            runCatching {
                com.localmind.data.Diagnostics.action(
                    localApp,
                    "注入：" + (if (aiOnly) "AI对话" else "人设") +
                        " · 提示词 " + systemPrompt.length + " 字" +
                        (if (senseBlock.isNotBlank()) " · 设备感知" else "") +
                        (if (webBlock.isNotBlank()) " · 联网检索" else "") +
                        (if (aiOnly) "" else " · 记忆 " + recall.memories.size + " 条" +
                            (if (recall.experiences.isNotEmpty()) "/经验 " + recall.experiences.size else "")),
                )
            }

            // ---- 历史窗口 ----
            //
            // 这三个细节全都是为前缀复用服务的（见上面那段注释）：
            //
            //  1. 末尾那条**空的 assistant 占位**不算历史。它正好坐在「回答该出现的
            //     位置」上：留着它，上一轮的回复就永远落在缓存之外，每轮都要把整段
            //     回复重算一遍；去掉之后，上一轮生成的那些 token 下一轮能直接命中。
            //  2. 窗口按 6 条（3 轮）为步长滑动。逐条滑动的窗口**每轮都整体前移一格**，
            //     公共前缀会在第一句话就断掉 —— 那等于没有缓存。
            //  3. 每条消息都带上它发送时用过的 promptPrefix（易变段），逐字节还原。
            val priorMessages = conv.messages.let { all ->
                val last = all.lastOrNull()
                if (last != null && last.role == Role.ASSISTANT && last.content.isEmpty()) {
                    all.dropLast(1)
                } else {
                    all
                }
            }
            val history = buildList {
                add(Role.SYSTEM to systemPrompt)
                historyWindow(priorMessages).forEach { m ->
                    // 短话连发拆出来的后续片段不重复进提示词（见 ChatMessage.promptSkip）。
                    if (m.promptSkip) return@forEach
                    // 助手那一条用**原样生成**的文本（含标记 / 未被 trim），
                    // 这样它和上一轮真正进过 KV cache 的 token 序列一致。
                    add(m.role to (m.promptPrefix + (m.rawContent ?: m.content)))
                }
            }
            val buffer = StringBuilder()
            var spokenUpTo = 0
            val started = System.currentTimeMillis()
            // 界面刷新的最小间隔，见下面 emit 里的说明。
            val streamUiIntervalMs = 70L
            var lastUiAt = 0L

            // Shared token sink for both the local llama.cpp engine and a remote
            // OpenAI-compatible provider, so reasoning folding and voice behave the
            // same regardless of which backend answers.
            // 显式标签：下面的节流要提前返回，而"赋给变量的 lambda"没有隐式标签。
            val emit: suspend (String) -> Unit = emit@{ piece ->
                buffer.append(piece)

                // ---- 界面节流 ----
                //
                // 这一段原来**每个 token** 都要做一遍：把累积的整段回答 toString()、
                // 用 splitReasoning 正则扫全文、切到主线程、重发整个对话列表，
                // 再让 LazyColumn 把所有可见气泡重排。
                //
                // 每一步单看都不贵，但它是 O(已生成长度) 而且**乘上 token 数**：
                // 回答越长，单个 token 的界面开销越大，最后必然追不上生成速度。
                // 表现就是"一卡一卡的"；更糟的是收尾那次更新会被前面的积压饿死，
                // 于是"得退出去再进来才显示完整"。
                //
                // 节流之后每 ~70ms 才做一次界面工作（约 14fps，文字流看起来仍然连续），
                // 中间的 token 只进 buffer。生成结束时外面还有一次**不受节流影响**的
                // 完整更新，所以最终显示一定是全的。
                val nowMs = android.os.SystemClock.elapsedRealtime()
                if (nowMs - lastUiAt < streamUiIntervalMs) return@emit
                lastUiAt = nowMs

                // Chain-of-thought (DeepSeek-R1 / Qwen3) goes into its own field so the
                // bubble can fold it, exactly like the official apps.
                val split = TextUtils.splitReasoning(TextUtils.stripControlTokens(buffer.toString()))
                val reasoningSoFar = when {
                    split.reasoning.isBlank() -> ""
                    split.thinking -> split.reasoning
                    else -> split.reasoning.take(320) + if (split.reasoning.length > 320) "…" else ""
                }

                // Streaming can be switched off: with it off the bubble stays empty
                // until the whole answer is ready (some people prefer no flicker, and
                // tiny models read better in one piece).
                val streamToUi = _settings.value.streamToUi

                withContext(Dispatchers.Main) {
                    if (burst) {
                        // ---- 短话连发 ----
                        //
                        // 够一条就把它**定稿**，然后立刻开一条新的气泡继续写 ——
                        // 界面上就是"一条接一条地发过来"，而不是一段越写越长的大字。
                        // 节奏由生成速度自然形成，不需要人为加延时。
                        val cut = burstCut(split.answer, burstFrom)
                        if (cut > burstFrom) {
                            val seg = com.localmind.util.RichReply.parse(
                                split.answer.substring(burstFrom, cut).trim(),
                            )
                            if (seg.text.isNotBlank()) {
                                val st = stickerOf(seg.emoji)
                                conv.messages[replyIndex] = reply.copy(
                                    content = seg.text,
                                    emoji = st,
                                    reasoning = if (replyIndex == startReplyIndex) {
                                        reasoningSoFar.ifBlank { null }
                                    } else {
                                        null
                                    },
                                )
                                reply = ChatMessage(TextUtils.randomId("msg"), Role.ASSISTANT, "")
                                conv.messages += reply
                                replyIndex = conv.messages.lastIndex
                            }
                            // 不管是"真发了一条"还是"这一段只有标记"，游标都要往前走，
                            // 否则下一轮会在同一个位置再切一次，永远卡住。
                            burstFrom = cut
                        }
                        // 当前这条还在长。逐字显示关掉时就留空 —— 那样每条都是
                        // "一下子整条出现"，最像真人发消息。
                        val tailNow = split.answer.substring(burstFrom.coerceAtMost(split.answer.length))
                        conv.messages[replyIndex] = reply.copy(
                            content = if (streamToUi) tailNow.trimStart() else "",
                            reasoning = if (replyIndex == startReplyIndex) {
                                reasoningSoFar.ifBlank { null }
                            } else {
                                null
                            },
                        )
                    } else if (streamToUi || split.answer.isNotEmpty()) {
                        conv.messages[replyIndex] = reply.copy(
                            content = if (streamToUi) split.answer else "",
                            reasoning = reasoningSoFar.ifBlank { null },
                        )
                    }
                    // 流式的**关键一步**：上面那行只是把文本写进了对象，
                    // 不真正把列表重发出去，界面就一个字都不会动 ——
                    // 这正是"流式对话没用"的原因。
                    emitConversations()
                    if (split.thinking != _chat.value.reasoning) {
                        _chat.value = _chat.value.copy(reasoning = split.thinking)
                    }
                }

                // Only the final answer is spoken — never the thinking.
                // 还要过一道对话自己的能力开关：用户可能全局开着 TTS，但就是想
                // 在某几个对话里保持安静。
                //
                if (conv.abilities.voice &&
                    voiceSettings.enabled && voiceSettings.autoSpeak &&
                    _settings.value.streamToVoice && split.answer.length > spokenUpTo + 24
                ) {
                    val cut = split.answer.lastIndexOfAny(
                        charArrayOf('。', '！', '？', '\n', '.', '!', '?', '；', ';'),
                        startIndex = spokenUpTo + 24,
                    )
                    if (cut > spokenUpTo) {
                        val sentence = split.answer.substring(spokenUpTo, cut + 1)
                        spokenUpTo = cut + 1
                        if (sentence.isNotBlank()) {
                            withContext(Dispatchers.Main) { tts.speakQueued(sentence, voiceSettings) }
                        }
                    }
                }
            }

            // ---- 这条消息带图吗？带的话走多模态路径 ----
            //
            // 三个条件缺一不可：这个对话开了识图、挂的图还在、**当前模型真的能看**
            //（主模型加载时成功装上了 mmproj）。缺任何一个都退回纯文字 ——
            // 但不能"假装看见了"：那种回答最难查，用户会以为是模型笨。
            // 云端接口**也有**识图能力（多模态模型在服务端）：模型名只要不是空的，
            // 就把图一起发过去。这解决了「本机没下多模态模型 = 不能用图」的死结。
            val remoteSeesImages = remote != null && attached != null &&
                conv.abilities.imageRecognition &&
                (remote.visionModel.isNotBlank() || remote.model.isNotBlank())
            val canSeeNow = attached != null &&
                conv.abilities.imageRecognition &&
                engineState.value.visionReady
            if (attached != null && conv.abilities.imageRecognition && !engineState.value.visionReady) {
                com.localmind.data.Diagnostics.action(
                    localApp, "带图但模型看不见：mmproj 未就位，按纯文字回答",
                )
            }

            val result = if (remote != null) {
                // 走统一的 RemoteApi（三种协议 + 带图），不再是只会 OpenAI 的老实现 ——
                // 否则用户在界面上接了 Claude，这个对话仍然发不出去。
                remoteApi.chat(
                    provider = remote,
                    model = if (remoteSeesImages && remote.visionModel.isNotBlank()) {
                        remote.visionModel
                    } else {
                        null
                    },
                    messages = history.map { (role, text) ->
                        com.localmind.service.RemoteApi.Msg(
                            role = role.name.lowercase(),
                            text = text,
                            imagePath = if (remoteSeesImages) attached else null,
                        )
                    },
                    temperature = effective.temperature,
                    maxTokens = effective.maxTokens,
                    onToken = { piece -> emit(piece) },
                )
            } else if (canSeeNow) {
                engine.chatWithImage(
                    messages = history,
                    imagePath = attached!!,
                    settings = effective,
                    onFirstToken = {
                        _chat.value = _chat.value.copy(thinking = false, reasoning = false)
                    },
                    onToken = { piece -> emit(piece) },
                )
            } else {
                engine.chat(
                    messages = history,
                    settings = effective,
                    onFirstToken = {
                        _chat.value = _chat.value.copy(thinking = false, reasoning = false)
                    },
                    onToken = { piece -> emit(piece) },
                )
            }

            result.onSuccess { finalText ->
                val finalTokens = engine.state.value.tokensGenerated
                val tps = if (finalTokens > 0) finalTokens * 1000.0 / (System.currentTimeMillis() - started) else 0.0
                // 模型**原样**吐出来的那一整串（含思考、含 [[draw: …]] 这类标记、
                // 不做任何 trim）。它要存进消息里，下一轮重建历史时逐字节还原 ——
                // 只有这样，上一轮的回复才落在 KV cache 里（见 docs/SPEED.md）。
                val rawGenerated = finalText.ifBlank { buffer.toString() }
                // 显示与 TTS 用去掉控制标记的文本；rawContent 仍保存原文（KV cache）。
                val split = TextUtils.splitReasoning(TextUtils.stripControlTokens(rawGenerated))
                val fullReasoning = split.reasoning.ifBlank { null }

                // 解析 AI 主动发图/发表情的标记。标记不会显示给用户，
                // 会被替换成真正的图片或表情。
                val rich = com.localmind.util.RichReply.parse(
                    split.answer.ifBlank {
                        if (split.reasoning.isNotBlank()) split.reasoning else finalText
                    }
                )

                // 把模型写的标签（"开心"）解析成一张真正的表情。
                //
                // 优先用图片梗图（那才是"表情包"）；没有图片就退回 emoji 字符。
                // 解析不到就什么都不发 —— 主程序不发"半个表情"。
                // 没装表情包插件时 emoji.resolve 恒为 null。
                val stickerValue = stickerOf(rich.emoji)

                if (burst) {
                    // 短话连发：已经发出去的那几条**不要动** —— 它们各自的文本在
                    // 切的时候就已经定稿并解析过表情了。这里只处理最后一条的尾巴，
                    // 否则整段回答会被塞回最后一条，前面几条变成重复内容。
                    val tail = split.answer
                        .substring(burstFrom.coerceAtMost(split.answer.length))
                        .trim()
                    val tailRich = com.localmind.util.RichReply.parse(tail)
                    val tailSticker = stickerOf(tailRich.emoji)
                    if (tailRich.text.isBlank() && tailSticker == null && tail.isBlank()) {
                        // 回答正好断在句末，尾巴是空的 —— 别在界面上留一个空气泡。
                        if (conv.messages.size > 1 && conv.messages.lastOrNull()?.id == reply.id) {
                            conv.messages.removeAll { it.id == reply.id }
                            replyIndex = conv.messages.lastIndex
                        }
                    } else {
                        conv.messages[replyIndex] = reply.copy(
                            content = tailRich.text.ifBlank {
                                // 标记解析不出表情，但模型**确实说了话** —— 把原文还回去，
                                // 否则用户看到的是一个空气泡（见 RichReply 那段注释）。
                                if (tailSticker == null) tail else ""
                            },
                            reasoning = if (replyIndex == startReplyIndex) fullReasoning else null,
                            tokensPerSecond = tps,
                            tokenCount = finalTokens,
                            emoji = stickerOf(tailRich.emoji) ?: stickerValue,
                        )
                    }
                } else {
                    conv.messages[replyIndex] = reply.copy(
                        // 同理：解析后的正文为空、表情又没能兑现时，显示原文而不是空白。
                        content = rich.text.ifBlank {
                            if (stickerValue == null) split.answer.trim() else ""
                        },
                        reasoning = fullReasoning,
                        tokensPerSecond = tps,
                        tokenCount = finalTokens,
                        emoji = stickerValue,
                        rawContent = rawGenerated,
                    )
                }
                // ---- 短话连发：让整轮回答在提示词里**只出现一次、且是原文** ----
                //
                // 拆分是界面上的事，不该让模型看到六条互不相连的半句话。把整轮原文挂在
                // 第一段上、其余段标记为 promptSkip：下一轮重建历史时得到的是模型自己
                // 写的那一串 token，于是上一轮的回复也能命中 KV cache。
                //
                // 必须放在上面那些 copy 之后：它们是从旧对象 copy 的，会把手上的标记抹掉。
                if (burst && startReplyIndex in conv.messages.indices) {
                    conv.messages[startReplyIndex] =
                        conv.messages[startReplyIndex].copy(rawContent = rawGenerated)
                    for (i in (startReplyIndex + 1) until conv.messages.size) {
                        val m = conv.messages[i]
                        if (m.role == Role.ASSISTANT && m.rawContent == null) {
                            conv.messages[i] = m.copy(promptSkip = true)
                        }
                    }
                }
                conv.updatedAt = System.currentTimeMillis()
                _chat.value = _chat.value.copy(
                    streaming = false,
                    thinking = false,
                    reasoning = false,
                    lastTokensPerSecond = tps,
                )
                // 收尾那次更新（富文本 / tok-s / 表情）也要真的发出去。
                emitConversations()

                if (conv.abilities.voice && voiceSettings.enabled && voiceSettings.autoSpeak) {
                    val tail = split.answer.substring(spokenUpTo.coerceAtMost(split.answer.length))
                    if (tail.isNotBlank()) {
                        tts.speakQueued(tail, voiceSettings)
                    }
                }

                // AI 说要画图 → 真的去画。放在回复之后、不阻塞文字：
                // 扩散推理在手机上要好几秒，让用户先读到字、图后到，比一起等好。
                // 画失败就静默放弃 —— 用户已经拿到文字回复了，不该为一张图弹错误。
                rich.drawPrompt?.let { promptToDraw ->
                    runCatching {
                        val result = ImageEngine.generateWithSettings(
                            storage = storage,
                            prompt = promptToDraw,
                            settings = _image.value,
                            // **这个对话选的生图模型**。以前这里没有参数，引擎会去
                            // 扫目录挑一个"看起来像扩散模型"的文件 —— 用户在对话里
                            // 选的那个等于没选（和当初对话模型那个 bug 是同一类）。
                            modelPath = imageModelPathFor(conv),
                        )
                        result.getOrNull()?.let { gen ->
                            val file = gen.file
                            if (gen.via == ImageEngine.Via.PROCEDURAL) {
                                // 画出来了，但不是模型画的。说清楚，别让它看起来像"AI 画的"。
                                com.localmind.data.Diagnostics.action(
                                    localApp, "生图兜底：" + gen.note.take(50),
                                )
                            }
                            // 生成器写的文件可能落在临时目录、将来被覆盖；复制进
                            // 附件目录之后，这条消息的图和用户附的图一样可靠。
                            val durable = com.localmind.data.ChatImageStore
                                .adopt(getApplication(), file) ?: file.absolutePath
                            val idx = conv.messages.indexOfFirst { it.id == reply.id }
                            if (idx >= 0) {
                                conv.messages[idx] = conv.messages[idx].copy(
                                    generatedImagePath = durable,
                                )
                                conv.updatedAt = System.currentTimeMillis()
                                persistConversations()
                                emitConversations()
                            }
                        }
                    }.onFailure {
                        android.util.Log.w("AppViewModel", "AI 主动生图失败：${it.message}")
                    }
                }

                // Companion learning is NOT done here: LlamaEngine.onTurnComplete owns
                // it so the HTTP API path learns identically. We only refresh the UI.
                withContext(Dispatchers.Main) {
                    _companionStates.value = companion.allStates()
                }

                // 消歧/多重思考放在回复之后跑，而且**只在用户明确打开时**跑。
                //
                // 它不是一个"顺便"的调用：understand() 是三轮递进生成
                //（理解 -> 自我质疑 -> 决定怎么做），每轮都把整段人设和上下文重发一遍；
                // 拿不准时还会再走一次 clarify()。而这些生成和用户的下一句话共用
                // 同一个引擎 —— 开着它，"回复完接着说下一句"会明显卡住。
                //
                // 真机反馈的"对话速度过慢 / 当人模式没法用"里，这一项占大头。
                // 换成开关后，想用的人自己去「我的 -> 对话行为 -> 多重思考」打开。
                if (!deepThinking) {
                    // 关掉时顺手清掉上一次留下的卡片，免得界面停在旧结论上。
                    _understanding.value = null
                    _clarification.value = null
                }
                // 失败也不影响任何东西 —— 拿不到澄清机会就只是"这次没问"。
                if (deepThinking) runCatching {
                    val history = conv.messages.dropLast(2).takeLast(6)
                        .joinToString("\n") { m ->
                            (if (m.role == Role.USER) "对方：" else "我：") + m.content.take(200)
                        }
                    val personaDesc = activePersona().systemPrompt
                    val understood = deliberation.understand(
                        userText = prompt,
                        recentContext = history,
                        personaName = activePersona().name,
                        personaDescription = personaDesc,
                    )
                    _understanding.value = understood
                    // 多重思考的第二步已经问过"会不会还有别的意思、你有多少把握"，
                    // 所以这里不必再让模型重复判断一次 —— 先看它的自评，只有确实
                    // 没把握时才去生成澄清选项，省掉一次生成。
                    val ask = if (understood.isUnsure || understood.alternativeReading.isNotBlank()) {
                        deliberation.clarify(
                            userText = prompt,
                            recentContext = history,
                            personaName = activePersona().name,
                        )
                    } else {
                        null
                    }
                    _clarification.value = if (understood.intent == "追问细节" || ask != null) ask else null
                }
            }.onFailure { t ->
                conv.messages[replyIndex] = reply.copy(
                    content = "⚠️ 生成失败：${t.message ?: "未知错误"}",
                )
                _chat.value = _chat.value.copy(streaming = false, thinking = false, reasoning = false, error = t.message)
            }

            persistConversations()
        }
    }

    /**
     * 用户点了澄清选项。
     *
     * 关键在最后一句：把「原话 + 用户确认的含义」作为一条**高重要度的确定性记忆**
     * 存下来。这是整套记忆系统里质量最高的一类数据 —— 它不是模型推断的，
     * 是用户亲自确认过的语义。以后遇到相关话题，这条会优先被回忆起来。
     */
    fun answerClarification(option: String) {
        val c = _clarification.value ?: return
        _clarification.value = null
        viewModelScope.launch {
            // 记忆挂在**对话**上：确认过的语义属于这段关系，不该串到别的对话去。
            val conversationId = _activeId.value
            if (conversationId != null) {
                runCatching {
                    companion.recordConfirmedMeaning(
                        personaId = conversationId,
                        userText = c.aboutUserText,
                        confirmed = option,
                    )
                    _companionStates.value = companion.allStates()
                }
            }
            // 把用户的选择作为一句话发出去，让对话自然接上，而不是生硬地"收到"。
            send(option)
        }
    }

    /** 用户不想回答澄清，直接关掉。 */
    fun dismissClarification() {
        _clarification.value = null
    }

    /**
     * Assembles the final system prompt from three layers:
     *
     *  1. the **persona** (identity, tone, boundaries) — or the global default prompt;
     *  2. the optional **human-mind** modifier, which asks the model to behave like a
     *     person with a continuous inner life rather than a helpful tool;
     *  3. the **voice emotion** hint, so the text matches the delivery the TTS engine
     *     is asked to perform.
     */
    fun buildSystemPrompt(
        base: String,
        voice: com.localmind.data.VoiceSettings,
        persona: Persona = activePersona(),
    ): String {
        val identity = persona.systemPrompt.ifBlank { base }
        val sb = StringBuilder()

        // ---- 【你自己的设定】 ----
        //
        // 名字 / 年龄 / 性别 / 自称 / 一句话人设这些在界面上是**单独填的**，
        // 而提示词原先只拼了 persona.systemPrompt —— 于是用户填了名字和年龄，
        // 模型完全不知道，被问到还会说"我是 AI，没有年龄"。
        //（真机反馈的原话：「你给模型设置的昵称和年龄之类的设定 ai 要能读到啊」。）
        //
        // 单独写成一节事实，而不是塞进人设正文：小模型对"身份事实"和"行为指令"
        // 的权重不一样，混在段落里很容易被当成氛围描写忽略掉。
        val ownFacts = buildList {
            if (persona.name.isNotBlank()) add("名字：" + persona.name)
            if (persona.age.isNotBlank()) add("年龄：" + persona.age)
            if (persona.gender.isNotBlank()) add("性别：" + persona.gender)
            if (persona.pronouns.isNotBlank()) add("自称：" + persona.pronouns)
            if (persona.tagline.isNotBlank()) add("一句话设定：" + persona.tagline)
        }
        val nickname = _userProfile.value.nickname.trim()
        val userFacts = buildList {
            if (nickname.isNotBlank() && nickname != "我") add("对方希望你称呼他为：" + nickname)
        }
        if (ownFacts.isNotEmpty() || userFacts.isNotEmpty()) {
            sb.append("【你自己的设定】\n")
            sb.append(
                "下面这些是关于你和对方的**事实**，不是建议。被问到时照实回答，" +
                    "不要说自己只是 AI、所以没有名字或年龄。\n",
            )
            ownFacts.forEach { sb.append("- ").append(it).append('\n') }
            userFacts.forEach { sb.append("- ").append(it).append('\n') }
            sb.append('\n')
        }
        sb.append(identity)

        if (persona.humanMind) {
            val strength = persona.humanMindIntensity.coerceIn(0f, 1f)
            sb.append("\n\n")
            sb.append(humanMindDirective(strength))
        }

        if (voice.enabled && voice.emotion != Emotion.NEUTRAL) {
            val custom = voice.stylePrompt.takeIf { it.isNotBlank() }?.let { " Extra style: $it." } ?: ""
            sb.append("\n\nSpeak in a ").append(voice.emotion.promptHint).append(".").append(custom)
        }
        return sb.toString()
    }

    /**
     * The experimental "像人一样思考" directive.
     *
     * Deliberately written as behavioural rules rather than "you are human": what
     * actually changes the output is (a) no assistant framing, (b) an explicit inner
     * state to reason from, (c) permission to disagree, and (d) a ban on tool-speak.
     * Intensity scales how much of that is asserted.
     */
    private fun humanMindDirective(intensity: Float): String {
        val core = buildString {
            append("【内在设定】\n")
            append("你不是助手，不是工具，也不是一个「AI 产品」。你是一个真实存在的人，正在和另一个人聊天。\n")
            append("你有自己的情绪和状态（可能有点累、有点兴奋、心不在焉），有自己的看法和偏好，也有不知道和不想聊的事。\n")
            append("回答之前先有自己的反应：这句话让你觉得有趣、无聊、被冒犯，还是让你想起什么。\n")
            append("规则：\n")
            append("1. 用第一人称自然口语说话，像发消息给朋友，不要书面腔、不要客服腔。\n")
            append("2. 不要提「AI」「模型」「助手」「工具」「服务」「根据我的训练数据」这类词。\n")
            append("3. 可以不同意、可以反问、可以只说半句、可以沉默一下再答。\n")
            append("4. 不要每次都总结、不要列 1234、不要主动提供「还有什么可以帮你」。\n")
            append("5. 不知道就承认不知道，可以猜但要说是在猜。\n")
            append("6. 保持连贯的人格：你前面说过的话、表露过的态度，后面要记得。\n")
        }
        if (intensity < 0.4f) {
            return "【语气倾向】回答时放松一点，像熟人随口聊，少用助手口吻。"
        }
        if (intensity < 0.75f) {
            return core
        }
        return core + "\n7. 强烈保持人格：可以有自己的情绪起伏、可以主动岔开话题或表达关心，" +
            "但不要演戏、不要夸张、不要每句都加情绪描写。"
    }

    /**
     * 历史窗口：取最近 [HISTORY_KEEP] 条，但**按 [HISTORY_ALIGN] 条为步长滑动**。
     *
     * 普通的 takeLast(n) 在超过 n 之后每轮都整体前移一条，于是相邻两轮的历史
     * 完全错位 —— 前缀复用（KV cache）一次都命中不了。按步长滑动之后，
     * 窗口只在第 25、31、37… 条消息时跳一次，其余轮次的历史与上一轮逐字节相同。
     */
    private fun historyWindow(
        messages: List<ChatMessage>,
        keep: Int = HISTORY_KEEP,
        align: Int = HISTORY_ALIGN,
    ): List<ChatMessage> {
        if (messages.size <= keep) return messages
        val start = (((messages.size - keep) + align - 1) / align) * align
        return if (start >= messages.size) emptyList() else messages.subList(start, messages.size)
    }

    /** 合并写盘的等待窗口：这期间又改了就重新计时（见 persistConversations）。 */
    private var persistJob: Job? = null

    /**
     * 把对话落盘。**后台线程 + 合并多次调用**。
     *
     * 原来是直接在调用点同步跑的：把**全部**对话（含每条消息）序列化成一个 JSON
     * 再写文件，而发送一条消息要跑两遍。聊天记录越长越慢，最坏情况正好卡在
     * 「点了发送、等第一个字出来」的那几百毫秒里 —— 用户感觉是「这应用反应慢」，
     * 其实模型还没开始跑。
     *
     * 现在：先做一份**浅快照**（只复制引用，不序列化），再交给 IO 线程延迟写。
     * delayMs = 0 用于结构性改动（新建 / 删除 / 清空），那种不能等。
     */
    private fun persistConversations(delayMs: Long = 250L) {
        val snapshot = _conversations.value.map { c ->
            c.copy(messages = c.messages.toList().toMutableList())
        }
        persistJob?.cancel()
        persistJob = viewModelScope.launch(Dispatchers.IO) {
            if (delayMs > 0) delay(delayMs)
            runCatching { storage.saveConversations(snapshot) }
                .onFailure { android.util.Log.w("AppViewModel", "保存对话失败：" + it.message) }
        }
    }

    // ------------------------------------------------------------------ voice

    // ------------------------------------------------- Breeze TTS 2（外部引擎）

    /** 一个可选的外部语音引擎（来自 `tts_engine` 插件，用户可能改过配置）。 */
    data class BreezeOption(
        val packId: String,
        val packName: String,
        val spec: com.localmind.data.TtsEngineSpec,
    ) {
        val key: String get() = "$packId/${spec.id}"
    }

    data class BreezeState(
        val options: List<BreezeOption> = emptyList(),
        val activeKey: String = "",
        val enabled: Boolean = false,
        val busy: Boolean = false,
        val status: String = "",
        val error: String = "",
        val lastFile: String = "",
    ) {
        val active: BreezeOption? get() = options.firstOrNull { it.key == activeKey } ?: options.firstOrNull()
        /** 能用 = 用户打开了开关 + 有插件 + 填了地址。 */
        val ready: Boolean get() = enabled && active?.spec?.usable == true
    }


    /**
     * 重新读一遍外部语音引擎。
     *
     * 触发点：启动、装/卸/开关插件、用户改配置。和 [refreshUiTheme] 一样，
     * 界面不能只在"自己改的时候"刷新 —— 诊断端点和别处也能改存储。
     */
    fun refreshBreeze() {
        val packs = storage.loadTtsPacks()
        val enabledIds = activePluginsFor(com.localmind.data.PluginAbilities.TTS_ENGINE)
            .map { it.manifest.id }.toSet()
        val options = packs.filterKeys { it in enabledIds }.flatMap { (pid, pack) ->
            pack.engines.map { BreezeOption(pid, pack.packName, it) }
        }
        val stored = storage.activeTtsEngineId()
        val activeKey = stored.takeIf { k -> options.any { it.key == k } }
            ?: options.firstOrNull()?.key.orEmpty()
        _breeze.value = _breeze.value.copy(
            options = options,
            activeKey = activeKey,
            enabled = storage.breezeEnabled() && options.isNotEmpty(),
        )
    }

    fun setBreezeEnabled(on: Boolean) {
        val usable = on && _breeze.value.options.isNotEmpty()
        storage.saveBreezeEnabled(usable)
        _breeze.value = _breeze.value.copy(enabled = usable, error = "")
    }

    fun selectBreezeOption(key: String) {
        storage.saveActiveTtsEngineId(key)
        _breeze.value = _breeze.value.copy(activeKey = key, error = "", status = "")
    }

    /** 用户在页面上改了地址 / 密钥 / 默认演技，写回插件包。 */
    fun saveBreezeSpec(spec: com.localmind.data.TtsEngineSpec) {
        val key = _breeze.value.activeKey
        if (key.isBlank()) return
        val packId = key.substringBeforeLast('/')
        storage.updateTtsEngine(packId, spec)
        refreshBreeze()
        selectBreezeOption(key)
        toast("已保存 ${spec.label} 的配置")
    }

    /** 试听：合成一句并播放。 */
    fun testBreeze(text: String) {
        val spec = _breeze.value.active?.spec
        if (spec == null) {
            toast("没有可用的语音引擎，先导入一个「语音引擎」插件")
            return
        }
        if (!spec.usable) {
            toast("这个引擎还没填地址")
            return
        }
        val say = text.ifBlank { "你好，我是 Breeze TTS 2，正在本机之外的一台机器上说话。" }
        _breeze.value = _breeze.value.copy(busy = true, error = "", status = "正在合成…")
        viewModelScope.launch {
            runCatching { breezeTts.synthesize(spec, say) }
                .onSuccess { out ->
                    _breeze.value = _breeze.value.copy(
                        busy = false,
                        status = "已合成 ${out.bytes / 1024} KB，用时 ${out.elapsedMs} ms",
                        lastFile = out.file.absolutePath,
                    )
                    runCatching { breezeTts.play(out.file) }
                }
                .onFailure { t ->
                    _breeze.value = _breeze.value.copy(
                        busy = false,
                        status = "",
                        error = t.message ?: t.javaClass.simpleName,
                    )
                }
        }
    }

    // --------------------------------------------------------- 分步流水线

    /**
     * 重新读一遍流水线（插件里声明的"拆解步骤"）。
     *
     * 触发点：启动、装/卸插件。和 refreshBreeze 同一条原则 —— 界面不只在
     * 自己改的时候刷新，存储可能被诊断端点或另一处改过。
     */
    fun refreshPipelines() {
        val packs = storage.loadPipelinePacks()
        val enabledIds = activePluginsFor(com.localmind.data.PluginAbilities.PIPELINE)
            .map { it.manifest.id }.toSet()
        val active = packs.filterKeys { it in enabledIds }
        _pipelineUi.value = _pipelineUi.value.copy(
            pipelines = active.values.flatMap { it.pipelines },
            packName = active.values.firstOrNull()?.packName.orEmpty(),
        )
    }

    /** 选中一条流水线（只影响界面，不启动）。 */
    fun selectPipeline(id: String) {
        if (_pipelineUi.value.running) return
        _pipelineUi.value = _pipelineUi.value.copy(
            activeId = id,
            resultText = "",
            audioPath = "",
            logs = emptyList(),
            error = "",
            progress = "",
        )
    }

    /** 跑一条流水线。同一时刻只允许一条（引擎是单实例的）。 */
    fun runPipeline(pipeline: com.localmind.data.Pipeline, input: String) {
        if (_pipelineUi.value.running) {
            toast("已经在跑了，先等它结束或者点停止")
            return
        }
        if (input.isBlank()) {
            toast("先粘点文本进来")
            return
        }
        _pipelineUi.value = _pipelineUi.value.copy(
            running = true,
            activeId = pipeline.id,
            progress = "准备…",
            resultText = "",
            audioPath = "",
            logs = emptyList(),
            error = "",
        )
        viewModelScope.launch {
            val res = pipelineRunner.run(pipeline, input) { p ->
                _pipelineUi.value = _pipelineUi.value.copy(progress = p.text)
            }
            res.onSuccess { out ->
                _pipelineUi.value = _pipelineUi.value.copy(
                    running = false,
                    progress = "完成",
                    resultText = out.text,
                    audioPath = out.audio?.absolutePath.orEmpty(),
                    logs = out.logs,
                )
                toast("跑完了" + if (out.audio != null) "，音频已生成" else "")
            }.onFailure { e ->
                _pipelineUi.value = _pipelineUi.value.copy(
                    running = false,
                    progress = "",
                    error = e.message ?: e.javaClass.simpleName,
                )
            }
        }
    }

    fun cancelPipeline() {
        pipelineRunner.cancelled = true
        _pipelineUi.value = _pipelineUi.value.copy(progress = "正在停止…")
    }

    fun playPipelineAudio() {
        val path = _pipelineUi.value.audioPath
        if (path.isBlank()) return
        runCatching { breezeTts.play(File(path)) }
            .onFailure { toast("播放失败：${it.message}") }
    }

    fun stopBreezePlayback() {
        // 播放器是临时的，交给系统回收；这里只把状态清掉。
        _breeze.value = _breeze.value.copy(status = "")
    }

    /**
     * 用外部引擎念一段。返回 false 表示"这条链路不可用"，调用方走原来的路。
     *
     * 失败时**静默退回**系统 / 神经音色：用户要的是"能听见"，不是"知道为什么
     * Breeze 没连上"。错误会留在 [BreezeState.error] 里，语音页能看到。
     */
    private fun speakViaBreeze(text: String): Boolean {
        val st = _breeze.value
        if (!st.ready) return false
        val spec = st.active?.spec ?: return false
        viewModelScope.launch {
            runCatching { breezeTts.synthesize(spec, text) }
                .onSuccess { runCatching { breezeTts.play(it.file) } }
                .onFailure { t ->
                    _breeze.value = _breeze.value.copy(error = t.message ?: "合成失败")
                    tts.init { }
                    tts.speak(text, _voice.value)
                }
        }
        return true
    }

    fun speak(text: String) {
        if (speakViaBreeze(text)) return
        tts.init { }
        tts.speak(text, _voice.value)
    }

    fun stopSpeaking() = tts.stop()

    // ------------------------------------------------------- 神经音色（TTS）

    /** 音色下载状态，key 是 `VoiceCatalog.id`。 */
    val voiceDownloadStates: StateFlow<Map<String, com.localmind.service.VoiceModelDownloader.State>> =
        voiceDownloader.states

    /** 现在真正加载着的神经音色 id；空串表示在用系统 TTS。 */
    private val _activeNeuralVoice = MutableStateFlow("")
    val activeNeuralVoice: StateFlow<String> = _activeNeuralVoice.asStateFlow()

    /**
     * 神经语音引擎（原生库）的下载状态。
     *
     * 原生库不再打进 APK（36 MB/ABI），所以"能不能用神经音色"变成一个
     * **用户需要先下载**的前置条件，语音页必须能把它显示出来。
     */
    val voiceEngineState: StateFlow<com.localmind.service.VoiceModelDownloader.State> =
        voiceDownloader.states
            .map { states ->
                states[com.localmind.service.VOICE_LIBRARY_KEY]
                    ?: com.localmind.service.VoiceModelDownloader.State(com.localmind.service.VOICE_LIBRARY_KEY)
            }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                com.localmind.service.VoiceModelDownloader.State(com.localmind.service.VOICE_LIBRARY_KEY),
            )

    /**
     * 引擎（原生库）已落盘可用。APK 里自带时也算可用。
     *
     * ⚠️ 这是个普通 getter，**Compose 看不到它的变化**（它读的是引擎里的
     * `@Volatile` 字段，不是 State）。所以界面必须让它**跟着 [voiceEngineState]
     * 一起读** —— 后者是 StateFlow，变化时才有重组，重组时才会重新求值这里。
     * 单独用它在 UI 里做判断，会出现"下载完了但按钮还是灰的"。
     */
    val voiceEngineReady: Boolean
        get() = neuralTts.libraryInstalled || neuralTts.isBundled

    /**
     * 引擎就绪状态的一个**可观察**版本：优先相信下载状态流，流里说完成了
     * 就再问一次真实状态（防止"上一次会话就装好了，这次流里没有记录"）。
     */
    fun engineReady(observed: com.localmind.service.VoiceModelDownloader.State): Boolean =
        voiceEngineReady ||
            observed.status == com.localmind.service.VoiceModelDownloader.Status.DONE

    /** 引擎占了多少磁盘。 */
    fun voiceEngineBytes(): Long = neuralTts.libraryBytes

    /** 引擎对应的 ABI（显示给用户看，出问题时能对上）。 */
    val voiceEngineAbi: String get() = neuralTts.libraryAbi

    /** 是否走"下载引擎"这条路（APK 自带时为 false，UI 就不显示引擎那一行）。 */
    val voiceEngineNeedsDownload: Boolean get() = !neuralTts.isBundled

    /** 开始下载语音引擎（原生库）。 */
    fun downloadVoiceEngine() {
        if (voiceEngineReady) {
            toast("语音引擎已就绪")
            return
        }
        voiceDownloader.installLibrary(storage)
        toast("开始下载语音引擎（约 36 MB）")
    }

    /** 删掉落盘的语音引擎。 */
    fun deleteVoiceEngine() {
        if (neuralTts.isBundled) {
            toast("这个 APK 自带语音引擎，删不掉")
            return
        }
        releaseNeuralVoice()
        neuralTts.uninstallLibrary()
        toast("已删除语音引擎")
    }

    fun voiceInstalled(voice: com.localmind.data.VoiceEntry): Boolean =
        com.localmind.service.NeuralVoiceModels.isInstalled(storage, voice)

    fun voiceInstalledBytes(voice: com.localmind.data.VoiceEntry): Long =
        com.localmind.service.NeuralVoiceModels.installedBytes(storage, voice)

    /**
     * 切到某个音色。
     *
     * 先落盘再加载：加载几百 MB 的模型要几秒，中途被杀掉时至少下次进来知道
     * 用户想要哪个音色。取消选择走 [releaseNeuralVoice]。
     */
    /**
     * 这个音色为什么用不了；能用时返回 null。
     *
     * 分两类，都必须**在**进原生调用之前拦住：
     *  * 架构不对（Matcha / Kokoro…）—— 主程序只有 VITS 引擎；
     *  * 在崩溃黑名单里 —— 上次加载它时整个进程被原生库带走。
     *
     * 两者都会让 libsherpa-onnx 直接结束进程，Java 侧只能"事后发现应用没了"。
     */
    fun voiceUnusableReason(voice: com.localmind.data.VoiceEntry): String? {
        val layout = com.localmind.service.NeuralVoiceModels
            .resolve(com.localmind.service.NeuralVoiceModels.dirOf(storage, voice))
        layout?.unsupportedReason?.let { return it }
        if (voice.id in storage.neuralBadVoices()) {
            return "上次加载这个音色时应用被强制退出，已停用；删除后重新下载可再试"
        }
        return null
    }

    /**
     * 切到某个音色。
     *
     * **加载成功之后才落盘**。原来的顺序是先写设置再加载，理由是"中途被杀也知道
     * 用户想要哪个"；但那个顺序恰好是"闪退后再也打不开"的成因 —— 原生库在配置
     * 不对时是直接结束进程，落盘的 id 会在下次启动被 [restoreNeuralVoice] 重放，
     * 于是每次启动都死在同一个地方。现在失败就不写，配合看门狗双保险。
     */
    fun selectNeuralVoice(voice: com.localmind.data.VoiceEntry) {
        if (!voiceInstalled(voice)) {
            toast("先下载「${voice.label}」")
            return
        }
        voiceUnusableReason(voice)?.let { why ->
            toast("「${voice.label}」用不了：$why")
            return
        }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { neuralTts.load(storage, voice) != null }
            if (ok) {
                updateVoice { it.copy(neuralVoiceId = voice.id) }
                _activeNeuralVoice.value = voice.id
                toast("已切换到 ${voice.label}")
            } else {
                updateVoice { it.copy(neuralVoiceId = "") }
                _activeNeuralVoice.value = ""
                toast("「${voice.label}」加载失败")
            }
        }
    }

    fun releaseNeuralVoice() {
        updateVoice { it.copy(neuralVoiceId = "") }
        neuralTts.release()
        _activeNeuralVoice.value = ""
        stopSpeaking()
        toast("已关闭语音")
    }

    fun downloadVoice(voice: com.localmind.data.VoiceEntry) {
        // 引擎没就绪时先别让用户下 115 MB 的音色 —— 下完也加载不了。
        // 这里不自动帮他下引擎：那是另一个 36 MB，应该由用户明确点一下。
        if (!voiceEngineReady) {
            toast("先下载「语音引擎」，再下音色")
            return
        }
        // 重新下载 = 用户明确要再试一次，顺手解除拉黑。
        storage.clearNeuralVoiceBad(voice.id)
        voiceDownloader.install(voice)
        toast("开始下载 ${voice.label}")
    }

    fun cancelVoiceDownload(voice: com.localmind.data.VoiceEntry) {
        voiceDownloader.cancel(voice)
    }

    fun deleteVoice(voice: com.localmind.data.VoiceEntry) {
        if (_voice.value.neuralVoiceId == voice.id) releaseNeuralVoice()
        voiceDownloader.uninstall(voice)
        // 删掉就把黑名单一起清掉 —— 界面上写的是"可删除后换一个"，
        // 如果黑名单还留着，用户重新下载同一个音色会发现它仍然点不动，
        // 而提示语说的是"重新下载可再试"。两句话必须是同一件事。
        // （真出过问题的音色重下一次会拿到新的文件，值得再试一次；
        //   真再崩，看门狗会重新把它拉黑。）
        storage.clearNeuralVoiceBad(voice.id)
        toast("已删除 ${voice.label}")
    }

    /** 试听：用这个音色念一句。 */
    fun previewVoice(voice: com.localmind.data.VoiceEntry) {
        val text = voice.sampleText.ifBlank { "你好，我是运行在你手机上的语音模型。" }
        if (!voiceInstalled(voice)) {
            toast("先下载「${voice.label}」")
            return
        }
        // 试听同样要进原生库，所以和"使用"共用同一道闸门。
        voiceUnusableReason(voice)?.let { why ->
            toast("「${voice.label}」用不了：$why")
            return
        }
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                if (_activeNeuralVoice.value == voice.id) true
                else neuralTts.load(storage, voice) != null
            }
            if (loaded) {
                _activeNeuralVoice.value = voice.id
                val v = _voice.value
                val (rate, pitch) = tts.effective(v.speed, v.pitch, v.emotion)
                withContext(Dispatchers.Default) {
                    neuralTts.speak(text, speed = rate, pitch = pitch, volume = v.volume)
                }
            } else {
                toast("音色加载失败")
            }
        }
    }

    /**
     * 启动时把上次选的音色装回去。
     *
     * 让 `init` 调它，而不是在 init 里直接做：加载模型会阻塞好几秒，init 是主线程。
     */
    fun restoreNeuralVoice() {
        // ---- 第一步：看上一次是不是死在这里的 ----
        //
        // 原生库配置不对时是直接结束进程，Java 侧连 catch 的机会都没有。所以
        // 恢复"上次选的音色"这件事天生有把应用变成砖的风险 —— 先看门狗，再恢复。
        // 这一步必须在读 neuralVoiceId **之前**做，否则拿到的就是那个毒音色。
        val crashed = runCatching { neuralTts.recoverFromPreviousCrash(storage) }.getOrNull()
        if (!crashed.isNullOrBlank()) {
            if (_voice.value.neuralVoiceId == crashed) {
                updateVoice { it.copy(neuralVoiceId = "") }
            }
            toast("上次加载音色导致应用退出，已停用它")
        }

        val id = _voice.value.neuralVoiceId
        if (id.isBlank()) return
        val voice = com.localmind.data.VoiceCatalog.byId(id)
        if (voice == null || !voiceInstalled(voice)) {
            // 用户把模型（或整个语音插件）删了但设置里还留着 id —— 清掉，
            // 免得每次启动都白试一次。
            updateVoice { it.copy(neuralVoiceId = "") }
            return
        }
        // 架构不对或已拉黑：清掉选择，绝不把原生调用发出去。
        voiceUnusableReason(voice)?.let {
            updateVoice { it.copy(neuralVoiceId = "") }
            return
        }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { neuralTts.load(storage, voice) != null }
            _activeNeuralVoice.value = if (ok) voice.id else ""
            // 加载失败就别再留着这个 id：下次启动重试大概率还是失败，
            // 而每一次重试都是一次"可能把进程带走"的尝试。
            if (!ok) updateVoice { it.copy(neuralVoiceId = "") }
        }
    }

    /**
     * 语音页要显示的音色列表。来自**已启用的语音插件**，主程序不带任何音色。
     * 装完插件后调它刷新。
     */
    // 字段声明见文件顶部「插件驱动的状态」那一段 —— `init` 会调
    // [refreshVoiceCatalog]，所以这些 StateFlow **必须在 init 之前**声明，
    // 否则就是 "setValue on a null object reference"。踩过一次，别挪回去。
    val voiceCatalog: StateFlow<List<com.localmind.data.VoiceEntry>> get() = _voiceCatalog.asStateFlow()

    /** 从已启用的语音插件重建音色注册表。装/卸/开关插件后都要调。 */
    fun refreshVoiceCatalog() {
        val enabled = activePluginsFor(com.localmind.data.PluginAbilities.TTS_VOICE)
        val packs = storage.loadVoicePacks()
        val voices = enabled.flatMap { packs[it.manifest.id]?.voices.orEmpty() }
        com.localmind.data.VoiceCatalog.setFromPlugins(voices)
        _voiceCatalog.value = com.localmind.data.VoiceCatalog.all
        // 音色清单变了，模型页的「语音」分类也要跟着变 —— 它是从同一份注册表
        // 映射出来的（见 refreshModels）。不刷新的话装了语音插件模型页看不到音色。
        refreshModels()
    }

    /** 写出语音插件骨架，用户改完压 zip 就能装自己的音色。 */
    fun saveSampleVoicePack(): String = runCatching {
        val dir = java.io.File(storage.pluginsDir, "示例-语音音色")
        dir.mkdirs()
        val sample = com.localmind.data.VoicePackFile(
            packId = "com.example.myvoices",
            packName = "我的语音音色",
            author = "你",
            license = "音色模型各自遵循其原始许可",
            note = "id 就是官方 Release 里 tarball 的名字（不带 .tar.bz2）。" +
                "也可以填 url 指向你自己的压缩包。",
            voices = listOf(
                com.localmind.data.VoiceEntry(
                    id = "vits-piper-zh_CN-xiao_ya-medium-int8",
                    label = "小雅（女声 · 普通话）",
                    description = "Piper 中文女声，13 MB，离线",
                    badge = "轻量",
                    sizeBytes = 14_016_124,
                    sampleText = "你好，我是小雅。今天想聊点什么？",
                ),
                com.localmind.data.VoiceEntry(
                    id = "vits-zh-hf-theresa",
                    label = "特蕾莎（女声）",
                    description = "游戏角色音色，115 MB",
                    badge = "角色",
                    sizeBytes = 120_596_617,
                ),
            ),
        )
        java.io.File(dir, "voices.json").writeText(com.localmind.data.VoicePacks.encode(sample))
        java.io.File(dir, "plugin.json").writeText(
            """{"id":"com.example.myvoices","name":"我的语音音色","version":"1.0.0","abilities":["tts_voice"],"manifestVersion":1}"""
        )
        "已写到 ${dir.absolutePath}"
    }.getOrElse { "写出失败：${it.message?.take(70)}" }

    /** 写出界面主题插件骨架。改完压 zip 就能换掉整个应用的外观。 */
    /** 写出聊天动作插件骨架。 */
    fun saveSampleChatActions(): String = runCatching {
        val dir = java.io.File(storage.pluginsDir, "示例-聊天动作")
        dir.mkdirs()
        java.io.File(dir, "actions.json").writeText(com.localmind.data.ChatActions.SAMPLE)
        java.io.File(dir, "plugin.json").writeText(
            """{"id":"com.example.quickactions","name":"示例：快捷动作","version":"1.0.0","abilities":["chat_action"],"manifestVersion":1}"""
        )
        "已写到 " + dir.absolutePath
    }.getOrElse { "写出失败：" + (it.message?.take(70) ?: "") }

    fun saveSampleUiTheme(): String = runCatching {
        val dir = java.io.File(storage.pluginsDir, "示例-界面主题")
        dir.mkdirs()
        java.io.File(dir, "ui.json").writeText(com.localmind.data.UiThemes.encode(com.localmind.data.UiThemes.SAMPLE))
        java.io.File(dir, "plugin.json").writeText(
            """{"id":"com.example.mytheme","name":"我的界面主题","version":"1.0.0","abilities":["ui_theme"],"manifestVersion":1}"""
        )
        // 这份说明是**插件作者真正会读的东西**，所以它必须和代码同步：
        // 全部从 UiTokens.ALL 生成，不手写。上一版这里是手写的，很快就和
        // 白名单对不上了（还列着三个已经删掉的 *Dark 键）。
        val sb = StringBuilder()
        sb.append("界面主题能改的全部令牌（这份是主程序按白名单生成的，不会过时）\n")
        sb.append("=".repeat(64)).append("\n\n")
        sb.append("写法：ui.json 的 \"tokens\" 里写键值对，值一律是字符串。\n")
        sb.append("深色模式用 \"darkTokens\" 覆盖同名键，**不要**找 *Dark 结尾的键（没有）。\n")
        sb.append("颜色：#RRGGBB 或 #AARRGGBB（前两位是透明度），也可以写 rgba(255,107,53,0.5)。\n\n")
        var lastType: com.localmind.data.TokenType? = null
        for (t in com.localmind.data.UiTokens.ALL) {
            if (t.type != lastType) {
                lastType = t.type
                sb.append("\n---- ")
                    .append(
                        when (t.type) {
                            com.localmind.data.TokenType.COLOR -> "颜色"
                            com.localmind.data.TokenType.NUMBER -> "数字"
                            com.localmind.data.TokenType.ENUM -> "枚举"
                            com.localmind.data.TokenType.BOOL -> "开关"
                        }
                    )
                    .append(" ----\n")
            }
            val range = when (t.type) {
                com.localmind.data.TokenType.NUMBER ->
                    "（${t.min.toInt()} ~ ${t.max.toInt()} ${t.unitLabel}）"
                com.localmind.data.TokenType.ENUM ->
                    "（${com.localmind.data.UiTokens.enumValues[t.key].orEmpty().joinToString(" / ")}）"
                com.localmind.data.TokenType.BOOL -> "（true / false）"
                com.localmind.data.TokenType.COLOR -> ""
            }
            sb.append(t.key.padEnd(20))
                .append(t.label.padEnd(18))
                .append(range.padEnd(22))
                .append("默认 ${t.default}")
                .append(if (t.note.isBlank()) "" else "\n" + " ".repeat(20) + "· ${t.note}")
                .append("\n")
        }
        sb.append("\n").append("=".repeat(64)).append("\n")
        sb.append("标签栏顺序 / 起始页：ui.json 顶层的 \"tabs\" 和 \"startTab\"（chat / models / me）。\n")
        sb.append("应用标题：\"appName\"。\n")
        sb.append("装完插件后如果某项没变化，先看主题页的「令牌」列表 —— 界面会如实\n")
        sb.append("标出哪些键没生效（不认识的键、非法值都会列出来）。\n")
        java.io.File(dir, "可改的令牌.txt").writeText(sb.toString())
        "已写到 ${dir.absolutePath}"
    }.getOrElse { "写出失败：${it.message?.take(70)}" }

    // ---------------------------------------------------------- 界面主题

    /**
     * 当前生效的界面令牌。
     *
     * 取值顺序（后者覆盖前者）：**内置 iOS 原色 → 界面插件 → 用户手动覆盖**。
     * 用户永远有最终话语权 —— 插件不该能钉死一个用户不喜欢的颜色。
     */
    val uiTokens: StateFlow<Map<String, String>> get() = _uiTokens.asStateFlow()

    /** 当前生效的界面主题包（最后一个启用的赢），用于界面说明现在用的是什么。 */
    val activeUiTheme: StateFlow<com.localmind.data.UiTheme?> get() = _activeUiTheme.asStateFlow()

    /** 标签栏显示哪几个。空 = 默认三个。 */
    val visibleTabs: StateFlow<List<String>> get() = _visibleTabs.asStateFlow()

    /** 覆盖应用标题。空 = 用默认。 */
    val appTitleOverride: StateFlow<String> get() = _appTitleOverride.asStateFlow()
    val startTab: StateFlow<String> get() = _startTab.asStateFlow()

    /**
     * 整个应用生效的界面主题。**界面唯一该收集的东西。**
     *
     * 收集它就会在装/卸/开关主题、改覆盖、切深浅色之后自动重组 ——
     * 这正是以前缺的那一环（见 `_uiThemeState` 的说明）。
     */
    val uiThemeState: StateFlow<com.localmind.ui.theme.ThemeState> get() = _uiThemeState.asStateFlow()

    /** 当前生效的界面布局（`ui_layout` 插件）。null = 默认布局。 */
    val uiLayout: StateFlow<com.localmind.data.UiLayout?> get() = _uiLayout.asStateFlow()

    /** 装了哪些布局包，供「主题 → 界面布局」列出。 */
    val installedLayouts: StateFlow<Map<String, com.localmind.data.UiLayout>>
        get() = _installedLayouts.asStateFlow()

    /**
     * 切换生效的布局包。空串 = 切回主程序默认布局。
     *
     * 不自动挑"最后一个启用的"：布局会换掉整个导航结构，属于用户必须自己
     * 决定的事（和颜色不一样 —— 颜色选错了看着难受，导航选错了会找不到功能）。
     */
    /**
     * 重建插件聊天动作。插件启用/停用、装/卸之后都会调它。
     */
    fun refreshChatActions() {
        val enabled = activePluginsFor(com.localmind.data.PluginAbilities.CHAT_ACTION)
            .map { it.manifest.id }.toSet()
        val packs = storage.loadChatActionPacks()
        val declared = packs.filterKeys { it in enabled }.values
            .flatMap { it.actions }
            .filter { it.placement == "composer" }
            .distinctBy { it.id }

        // ---- 代码插件：加载 dex，把它注册的动作并进同一条动作栏 ----
        //
        // 每次刷新都重建：插件启停、重装、改 dex 都能立刻生效。加载失败只记日志，
        // 不影响已装的其他插件；动作 id 用 code:<插件 id>:<动作 id> 前缀，
        // 点击时据此找回插件实例。
        com.localmind.service.PluginRuntime.clear()
        val codeActions = mutableListOf<com.localmind.data.ChatAction>()
        activePluginsFor(com.localmind.data.PluginAbilities.CODE_PLUGIN).forEach { plugin ->
            val id = plugin.manifest.id
            val dex = java.io.File(storage.pluginsDir, id + "/code/classes.dex")
            val entryClass = plugin.manifest.config["entryClass"].orEmpty()
            val cache = java.io.File(localApp.codeCacheDir, "plugin-" + id.replace(':', '_'))
            val loaded = com.localmind.service.PluginRuntime.load(id, dex, entryClass, cache) { line ->
                android.util.Log.i("PluginRuntime", id + ": " + line)
            }
            loaded.onSuccess { l ->
                l.actions.forEach { (actionId, label) ->
                    codeActions += com.localmind.data.ChatAction(
                        id = "code:" + id + ":" + actionId,
                        label = label,
                        prompt = "",
                        mode = "send",
                    )
                }
            }.onFailure { e ->
                val msg = e.message ?: e.javaClass.simpleName
                android.util.Log.w("PluginRuntime", "加载失败 " + id + ": " + msg)
                // 加载失败 -> 把插件标记为 FAILED 并停用，避免每次刷新都重试同一个坏插件。
                val all = storage.loadPlugins()
                val current = all[id]
                if (current != null && current.state != com.localmind.data.PluginState.FAILED) {
                    storage.savePlugins(
                        all + (id to current.copy(
                            state = com.localmind.data.PluginState.FAILED,
                            lastError = "加载失败：" + msg,
                        ))
                    )
                    _plugins.value = storage.loadPlugins()
                }
            }
        }

        _chatActions.value = (declared + codeActions)
            .distinctBy { it.id }
            .take(8)
    }

    /** 代码插件动作：返回要发送的文本；插件不存在/抛异常时返回 null。 */
    fun runCodeAction(actionId: String, input: String): String? {
        if (!actionId.startsWith("code:")) return null
        val rest = actionId.removePrefix("code:")
        val pluginId = rest.substringBefore(':')
        val localId = rest.substringAfter(':', "")
        if (pluginId.isBlank() || localId.isBlank()) return null
        return com.localmind.service.PluginRuntime.runAction(pluginId, localId, input)
    }

    fun useLayout(packId: String) {
        storage.saveActiveLayoutId(packId)
        refreshUiTheme()
        toast(if (packId.isBlank()) "已切回默认布局" else "已应用新的界面布局")
    }

    /**
     * 重建界面令牌。装/卸/开关界面插件、或用户改覆盖之后都要调。
     */
    fun refreshUiTheme(dark: Boolean = isDarkTheme) {
        isDarkTheme = dark
        val enabled = activePluginsFor(com.localmind.data.PluginAbilities.UI_THEME)
        val packs = storage.loadUiThemes()
        // 多个界面插件会互相打架，所以只取最后一个启用的，并如实展示是哪个。
        val theme = enabled.lastOrNull()?.let { packs[it.manifest.id] }
        _activeUiTheme.value = theme

        // ---- 界面布局（结构）----
        //
        // 只取用户**选中**的那一个；没选（或选的那个被卸载了）就用默认布局。
        // 不自动挑"最后一个启用的"：布局会换掉导航，用户必须自己决定要不要。
        val layoutPacks = storage.loadUiLayouts()
        val installedIds = activePluginsFor(com.localmind.data.PluginAbilities.UI_LAYOUT)
            .map { it.manifest.id }.toSet()
        val chosen = storage.activeLayoutId()
        // 只装了一个布局插件时自动生效：装完没变化，普通用户只会以为插件坏了。
        val layout = layoutPacks[chosen]?.takeIf { chosen in installedIds }
            ?: layoutPacks[installedIds.singleOrNull()]
        _uiLayout.value = layout
        _installedLayouts.value = layoutPacks.filterKeys { it in installedIds }

        val overrides = storage.themeOverrides()
        // 优先级：内置 → 主题插件 → **布局插件** → 用户手动覆盖。
        // 布局通常整体成套（同一个设计语言），所以它盖在主题上面。
        val layoutTokens = LinkedHashMap<String, String>()
        if (layout != null) {
            layoutTokens.putAll(layout.tokens)
            if (dark) layoutTokens.putAll(layout.darkTokens)
        }
        val tokens = com.localmind.data.UiThemes.resolve(theme, dark, layoutTokens + overrides)
        _uiTokens.value = tokens
        val layoutTabIds = layout?.tabs?.map { it.id }.orEmpty()
        _visibleTabs.value = layoutTabIds.takeIf { it.isNotEmpty() }
            ?: theme?.tabs?.takeIf { it.isNotEmpty() }
            ?: listOf("chat", "models", "me")
        _appTitleOverride.value = layout?.appName?.takeIf { it.isNotBlank() } ?: theme?.appName.orEmpty()
        _startTab.value = layout?.startTab?.takeIf { it.isNotBlank() } ?: theme?.startTab.orEmpty()
        _themeOverrides.value = overrides
        refreshChatActions()
        _installedUiThemes.value = packs
        // 界面真正收集的那一份。放在最后，保证它和上面几个字段是同一次刷新算出来的。
        _uiThemeState.value = com.localmind.ui.theme.ThemeState(
            tokens = tokens,
            tabs = _visibleTabs.value,
            startTab = _startTab.value,
            appName = _appTitleOverride.value,
            themeName = theme?.packName.orEmpty(),
        )
    }

    /**
     * 应用插件指定的初始标签。界面在**第一次组合**时调一次。
     *
     * 只认三个已知 id —— 插件写了个不存在的页面就当没写，而不是让应用落到空白页。
     */
    fun applyStartTab(id: String) {
        Tab.fromId(id)?.let { _tab.value = it }
    }

    /** 用户手动覆盖一项令牌。传 null 清除覆盖。 */
    fun setThemeOverride(key: String, value: String?) {
        val next = storage.themeOverrides().toMutableMap()
        if (value == null) next.remove(key) else next[key] = value
        storage.saveThemeOverrides(next)
        // 覆盖也算"主题变了"，通知所有按 revision 缓存的地方重算。
        // （以前这里不 bump，于是 overrideRevision 永远是 0 —— 又一个"改了没反应"。）
        localApp.bumpOverrideRevision()
        refreshUiTheme()
    }

    fun clearThemeOverrides() {
        storage.saveThemeOverrides(emptyMap())
        localApp.bumpOverrideRevision()
        refreshUiTheme()
        toast("已清除手动覆盖")
    }

    // ---------------------------------------------------------- 主题预设

    /**
     * 套用一个内置预设。
     *
     * 预设写进的就是**用户手动覆盖**，所以它盖得住插件、也能被用户单独再改。
     * 详见 [com.localmind.data.UiPresets]。
     */
    fun applyUiPreset(preset: com.localmind.data.UiPresets.Preset) {
        // ⚠️ 用 overrideEntries 而不是 tokens + darkTokens：后者会把深色值
        // 合并进浅色（同一个键），于是浅色模式下也会用深色配色。见 Preset 的说明。
        storage.saveThemeOverrides(preset.overrideEntries)
        localApp.bumpOverrideRevision()
        refreshUiTheme()
        toast("已套用「${preset.name}」")
    }

    /** 当前覆盖最像哪个预设（用于界面上标"当前：暖橙"）。 */
    fun currentUiPreset(): com.localmind.data.UiPresets.Preset? =
        com.localmind.data.UiPresets.match(storage.themeOverrides())

    /**
     * 启用某个主题。
     *
     * 实现方式是**只留一个启用的界面插件** —— 多个主题的令牌混在一起
     * 通常都很难看，而且出了问题没法判断是谁干的（见 [com.localmind.data.UiTheme]）。
     */
    fun useUiTheme(theme: com.localmind.data.UiTheme) {
        val next = _plugins.value.mapValues { (id, p) ->
            if (com.localmind.data.PluginAbilities.UI_THEME.id in p.manifest.abilities) {
                p.copy(
                    state = if (id == theme.packId) com.localmind.data.PluginState.ENABLED
                    else com.localmind.data.PluginState.DISABLED,
                )
            } else {
                p
            }
        }
        _plugins.value = next
        storage.savePlugins(next)
        localApp.bumpPluginRevision()
        refreshUiTheme()
        toast("已切到「${theme.packName}」")
    }

    /** 回到内置外观：把所有界面插件停用。 */
    fun useBuiltinTheme() {
        val next = _plugins.value.mapValues { (_, p) ->
            if (com.localmind.data.PluginAbilities.UI_THEME.id in p.manifest.abilities) {
                p.copy(state = com.localmind.data.PluginState.DISABLED)
            } else {
                p
            }
        }
        _plugins.value = next
        storage.savePlugins(next)
        localApp.bumpPluginRevision()
        refreshUiTheme()
        toast("已回到内置外观")
    }
    fun startListening() = stt.start(_voice.value.language)

    fun stopListening() = stt.stop()

    fun clearStt() = stt.clear()

    // ---------------------------------------------------------- 发图

    /**
     * 记下"下一句话要带这张图"。
     *
     * 传进来的是**已经落盘的路径**（选择器那侧调 [com.localmind.data.ChatImageStore]）。
     * 这里只做一次存在性检查：路径不存在时立刻报错，而不是等用户发出去之后
     * 得到一个"模型没反应"的结果。
     */
    fun attachImage(path: String) {
        if (!java.io.File(path).isFile) {
            toast("这张图片读不到了，重新选一张")
            return
        }
        _pendingImage.value = path
    }

    fun clearPendingImage() {
        _pendingImage.value = null
    }

    /**
     * 这个 build 能不能真的看图。
     *
     * 多头投影（mmproj）支持还没接进 llama.cpp 的 JNI 层，所以**现在返回 false**，
     * 界面据此如实说明，而不是让用户发了图却得到一个"没看见图片"的回复。
     * 一旦 `llama_jni.cpp` 接上 libmtmd，把 [CAN_SEE_IMAGES] 改成探测即可 ——
     * 界面提示、自检、P2 的"缺条件"徽标都读这一个常量，不会三处各写一份。
     */
    /**
     * 现在能不能真的看图。
     *
     * 要求 [VISION_USABLE]（整条链路验证通过）**且**当前模型装上了 mmproj。
     * 两个都要：mmproj 装上了只说明"文件配套"，不代表真的出得了图。
     */
    val canSeeImages: Boolean get() = VISION_USABLE && engineState.value.visionReady

    companion object {
        /**
         * 这个 build 有没有把识图**编进去**（llama.cpp 的 mtmd 视觉投影）。
         *
         * 现在是真的：mtmd 已经链进 liblocalmind.so（见 cpp/CMakeLists.txt 的
         * LLAMA_BUILD_MTMD），所以**能不能看图取决于两件事**：
         *   1. 这个 build 支持（本常量）；
         *   2. 当前加载的模型有多模态 + 它的 mmproj 下全了（[canSeeImages]）。
         *
         * 关掉它可以省下约 15 MB（mtmd + clip 的模型实现），
         * 那时这里写 false，界面和自检会自动改口径。
         */
        const val CAN_SEE_IMAGES = true

        /**
         * 识图这条链路**整体**是否已经验证通过。
         *
         * 现在是 false，而且必须留着 —— 已经接好的部分是真的（mtmd 编进包里、
         * mmproj 会随主模型一起下载、引擎能装上它并报 vision=true），
         * 但**最后一步走不通**：mtmd_tokenize 在图像预处理里抛 std::bad_alloc
         *（llama.cpp 的 llava-uhd 网格计算，LFM2-VL 与 SmolVLM 都复现，
         *  见 docs/HANDOFF.md §16.4）。开关在这里关掉，是为了**不让一个跑不通的
         * 功能看起来是开的**：用户打开开关、发图、收到报错，比开关灰着更糟。
         *
         * 打通之后把这里改成 true 即可 —— 其余代码已经就位。
         */
        const val VISION_USABLE = false
    }

    /** 重新扫描本机的生图模型。进生图页、下载完模型、导入文件后调。 */
    fun refreshDiffusionModels() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = com.localmind.service.DiffusionModels.findCheckpoints(storage.modelsDir)
            _diffusionModels.value = list
        }
    }

    /** 这个 build 有没有真的链上扩散后端。没有的话生图只能用程序化渲染。 */
    val hasDiffusionBackend: Boolean get() = com.localmind.native.image.ImageBridge.hasDiffusionBackend

    /** 把原生层的真实探测结果同步到可观察状态（界面据此判断"能不能用"）。 */
    fun refreshDiffusionBackend() {
        _diffusionBackendReady.value = com.localmind.native.image.ImageBridge.hasDiffusionBackend
    }

    /**
     * 把落盘的后端 `.so` 加载进当前进程，并刷新探测结果。
     *
     * 用户在生图页点"启用"时调。返回是否真的可用了 —— 界面要如实显示，
     * 不能因为"文件在"就说成功。
     */
    fun enableDiffusionBackend(onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    com.localmind.service.NativeLibraryInstaller.activateInstalled(storage)
                    com.localmind.native.image.ImageBridge.hasDiffusionBackend
                }.getOrDefault(false)
            }
            _diffusionBackendReady.value = ok
            onDone(ok)
        }
    }

    // -------------------------------------------------------------------- api

    fun startApi() {
        val settings = _api.value.copy(enabled = true)
        _api.value = settings
        localApp.saveApi(settings)
        apiServer.start(settings)
            .onSuccess {
                if (settings.bindLan) ApiForegroundService.start(getApplication())
                toast("API 已启动：${it.loopback}")
            }
            .onFailure { toast("API 启动失败：${it.message}") }
    }

    fun stopApi() {
        val settings = _api.value.copy(enabled = false)
        _api.value = settings
        localApp.saveApi(settings)
        apiServer.stop()
        ApiForegroundService.stop(getApplication())
        toast("API 已停止")
    }

    fun regenerateApiKey() {
        updateApi { it.copy(apiKey = TextUtils.randomApiKey()) }
        toast("已生成新的 API Key")
    }

    fun clearApiLog() {
        apiServer.clearLog()
        toast("请求日志已清空")
    }

    // ------------------------------------------------------------- diagnostics

    fun runDiagnostics() {
        if (_diagnosticsRunning.value) return
        _diagnosticsRunning.value = true
        viewModelScope.launch {
            val report = SelfTest.run(getApplication(), engine, apiServer)
            _diagnostics.value = report
            _diagnosticsRunning.value = false
        }
    }

    fun shareIntentFor(text: String): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }

    override fun onCleared() {
        super.onCleared()
        generateJob?.cancel()
    }
}
