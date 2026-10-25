package com.localmind

import android.app.Application
import android.content.Context
import android.util.Log
import kotlinx.coroutines.launch
import com.localmind.data.ApiSettings
import com.localmind.data.GenerationSettings
import com.localmind.data.ImageSettings
import com.localmind.data.ModelStore
import com.localmind.data.Storage
import com.localmind.data.VoiceSettings
import com.localmind.service.ApiServer
import com.localmind.service.LlamaEngine
import com.localmind.service.ModelDownloader
import com.localmind.service.SttEngine
import com.localmind.service.TtsEngine

/**
 * Single dependency container. Everything is created once here and shared by the
 * UI, the foreground API service and any inbound HTTP request.
 */
class LocalMindApp : Application() {

    lateinit var storage: Storage
        private set
    lateinit var modelStore: ModelStore
        private set
    lateinit var engine: LlamaEngine
        private set
    /**
     * A **second, independent** llama.cpp engine that holds nothing but the small
     * decision model.
     *
     * Why a separate instance: [engine] is single-model, so binding a 0.5B router to
     * it would evict the chat model, and an unbound router silently fell back to
     * making routing decisions with the big chat model. Each native `nativeInit()`
     * returns its own `new engine()`, so two models can stay resident at once.
     *
     * Cost: both models occupy RAM simultaneously. The decision model is tiny
     * (~0.4 GB for a 0.6B Q4) and its context is kept minimal, but on a low-RAM phone
     * the user should simply leave the decision model unset — rules remain the floor.
     */
    lateinit var decisionEngine: LlamaEngine
        private set
    lateinit var downloader: ModelDownloader
        private set
    lateinit var tts: TtsEngine
        private set
    /**
     * 神经音色（sherpa-onnx VITS）的推理与播放。
     *
     * 和 [tts] 是**互补**关系而不是替代：系统 TTS 永远在，负责"至少有声音"；
     * 这个只在用户下载了音色模型、而且原生库确实落盘之后才生效。
     * 缺失时 [tts] 自己会退回系统音色，不需要调用方判断。
     */
    lateinit var neuralTts: com.localmind.service.NeuralTtsEngine
        private set
    /** 神经语音原生库的按需下载（36 MB/ABI，不进 APK）。 */
    lateinit var sherpaLibs: com.localmind.service.SherpaLibraryInstaller
        private set
    /** 音色模型的下载与安装。 */
    lateinit var voiceDownloader: com.localmind.service.VoiceModelDownloader
        private set
    /**
     * 表情包（插件提供）。
     *
     * 主程序**只带加载器和匹配算法，不带任何表情数据**。没装表情包插件时
     * [StickerResolver.isActive] 为 false，`[[emoji: …]]` 标记会被解析成空 ——
     * 也就是"这个能力不存在"，而不是"发了但没显示"。
     */
    lateinit var emoji: com.localmind.service.StickerResolver
        private set
    lateinit var stt: SttEngine
        private set
    lateinit var apiServer: ApiServer
        private set
    lateinit var remoteEngine: com.localmind.service.RemoteChatEngine
        private set
    /**
     * 云端 / 局域网接口的统一入口（对话、识图、生图、语音、转发）。
     *
     * 和 [remoteEngine] 的关系：那个只会说 OpenAI 的 chat/completions，是历史包袱；
     * 新的四类任务都走这个。remoteEngine 暂时留着给旧调用点，后续可以合并掉。
     */
    lateinit var remoteApi: com.localmind.service.RemoteApi
        private set
    lateinit var companion: com.localmind.service.CompanionMind
        private set

    /**
     * 外部语音引擎客户端（Breeze TTS 2 这类跑在别的机器上的模型）。
     *
     * 和 [tts] / [neuralTts] 是三条并列的路：系统 TTS 永远在，神经音色要下载模型，
     * 这一个只发 HTTP 请求（见 [com.localmind.service.BreezeTts]）。
     * 默认关闭，端点由 `tts_engine` 插件提供。
     */
    lateinit var breeze: com.localmind.service.BreezeTts
        private set

    /**
     * 后台学习结束时通知界面刷新「它现在的状态」。
     *
     * 为什么需要回调：学习从「等它跑完再画」改成了「扔后台就不管」，
     * 界面那一栏（亲密度 / 心情 / 记忆条数）就不会自己变了 —— 用户会以为
     * 那个面板坏了。ViewModel 在 init 里挂一个刷新函数，这里跑完叫一声。
     */
    @Volatile
    var onCompanionUpdated: (() -> Unit)? = null

    /**
     * 记忆 / 关系总结这类「没人等」的工作跑在这里。
     *
     * 它和用户那条消息是**两条独立的命**：钩子把活扔进来就返回，用户不用等它，
     * 它也不许插队（真正的让位逻辑在 LlamaEngine.Lane）。
     */
    private val learningScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    /**
     * 等一个「没人正在等回答」的窗口。
     *
     * 两个条件：引擎没在生成、用户也没在输入框里打字（打字说明大概率马上要发）。
     * 最多等 [LEARN_MAX_WAIT_MS]；等不到就这一轮不学 —— 丢一条记忆永远比
     * 「发出去半天没反应」划算。
     */
    private suspend fun awaitQuietWindow(): Boolean {
        val deadline = System.currentTimeMillis() + LEARNING_MAX_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!engine.state.value.generating && !userIsTyping) return true
            kotlinx.coroutines.delay(180)
        }
        return false
    }

    /**
     * 装崩溃捕获，并判断上一次进程是不是正常结束的。
     *
     * ## 为什么要有它
     *
     * 内测时用户只会说"它闪退了"。没有堆栈、没有机型、不知道他点之前做了什么 ——
     * 这条反馈基本没法用。这里把三件事一起落盘：异常堆栈、机型与系统信息、
     * 以及**崩溃前的那几十步操作**（见 [com.localmind.data.Diagnostics]）。
     * 用户只要导出那个文件发回来，原因基本就在里面。
     *
     * ## 为什么不吞掉异常
     *
     * 记完之后**必须交回给上一个处理器**：系统的崩溃弹窗、进程重启、
     * "应用屡次停止运行"这些行为都依赖它。我们只是路过抄一份。
     */
    private fun installDiagnostics() {
        runCatching { com.localmind.data.Diagnostics.beginSession(this) }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // 处理器自己抛异常会把原始异常一起吞掉，所以整段包起来。
            runCatching { com.localmind.data.Diagnostics.recordCrash(this, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        storage = Storage(this)

        // 诊断要**尽早**装上：启动期崩溃（模型加载、原生库、存储损坏）恰恰是
        // 内测里最难问清楚的一类，装晚了就抓不到。
        installDiagnostics()

        // ⚠️ APK **不再自带任何模型清单**（曾经有过，见下）。
        //
        // 原来这里会在首次运行时把 `assets/model-library/model-library.json`（34 条）
        // 装成模型包，理由是「新装的应用模型页空空如也，用户以为它坏了」。
        // 但那条路是错的：模型清单是**第三方模型的索引**，把它打进主程序包里，
        // 等于主程序在分发/推荐这些模型 —— 许可与责任都跟着 APK 走。
        // 清单必须是**用户自己导入的插件**，主程序一条都不带。
        //
        // 空白页的问题改用「明确的空态」解决：模型页会直接告诉你
        // 「模型库由插件提供，去我的 → 插件导入」，并给一个按钮跳过去（见 ModelsScreen）。

        modelStore = ModelStore(storage)
        engine = LlamaEngine(this)
        decisionEngine = LlamaEngine(this)
        downloader = ModelDownloader(storage)
        tts = TtsEngine(this).also { it.init() }
        // 原生库按需下载，所以 installer 要先建，再交给引擎。
        sherpaLibs = com.localmind.service.SherpaLibraryInstaller(storage)
        neuralTts = com.localmind.service.NeuralTtsEngine(this, sherpaLibs)
        // 把神经音色挂到 TtsEngine 上：之后 tts.speak() 会自己先试神经、失败再退系统。
        // 挂载本身不做任何事（不加载模型），所以放在 onCreate 里是安全的。
        tts.attachNeural(neuralTts)
        voiceDownloader = com.localmind.service.VoiceModelDownloader(storage)
        // 表情包：把上一次装的那份装回内存（含图片资源目录）。
        // 没装过就是 null，能力自然关闭。
        emoji = com.localmind.service.StickerResolver().also {
            it.setPack(storage.loadStickerPack(), storage.stickerAssetDir())
        }
        stt = SttEngine(this)
        // 外部语音引擎（Breeze TTS 2 等）：只发 HTTP 请求，不加载本机模型。
        breeze = com.localmind.service.BreezeTts(this)
        apiServer = ApiServer(storage, engine, tts, modelStore)
        remoteEngine = com.localmind.service.RemoteChatEngine()
        remoteApi = com.localmind.service.RemoteApi()
        companion = com.localmind.service.CompanionMind(storage, engine, decisionEngine)

        // Every completed generation — UI chat or HTTP API — feeds the companion's
        // long-term memory. Bound here so no call path can forget to do it.
        //
        // 记忆挂在**对话**上，所以这里要拿到"当前打开的是哪个对话"。UI 路径在发起
        // 生成前会写进 activeConversationId；HTTP API 路径没有打开对话的概念，
        // 落到一个稳定的虚拟 id（相当于"接口那条线"自成一个关系）。
        engine.onTurnComplete = { userText, assistantText ->
            // 用缓存的人设而不是每轮读一遍全部对话：这个钩子在**每次生成之后**都跑，
            // 反序列化整份对话列表只为取一个名字太浪费。
            val humanMind = activeConversationHumanMind
            // ---- AI 对话：什么都不做 ----
            //
            // 建的时候选「AI 对话」的会话 companionMode=false。它不留记忆、没有情绪、
            // 不积累经验 —— 那不只是产品定义，也是"最快"的必要条件。
            //
            // ---- 人设聊天：学，但**不在用户这条路上学** ----
            //
            // 这里必须是"扔到后台就返回"。原来的写法是就地 await 整段 afterTurn，
            // 而它里面有钱的活（提炼经验 / 每 12 轮重写关系总结都要调一次模型），
            // 于是用户看到回答之后紧接着发下一句，那一句就排在提炼后面 ——
            // 真机反馈的「回答完想接着说一句，半天没反应」就是这条路径。
            //
            // 现在的规则：钩子立刻返回；学习任务在后台等一个空闲窗口再跑，
            // 并且走 LlamaEngine.Lane.BACKGROUND —— 一旦有新的一条用户消息
            // 进来，正在跑的后台生成会被直接掐掉（见 LlamaEngine.chat）。
            if (humanMind) {
                val conversationId = activeConversationId ?: API_CONVERSATION_ID
                learningScope.launch {
                    if (!awaitQuietWindow()) return@launch
                    // 学习期间用户要是又发了一句，这里再确认一次：**不学也不影响回答**。
                    if (engine.state.value.generating) return@launch
                    runCatching {
                        val state = companion.stateOf(conversationId)
                        val recalled = companion.recall(state, userText)
                        companion.afterTurn(
                            personaId = conversationId,
                            userMessage = userText,
                            assistantMessage = assistantText,
                            recalled = recalled,
                            personaHumanMind = true,
                        )
                    }.onSuccess {
                        // 状态变了 → 让界面那栏刷新（见 onCompanionUpdated 的说明）。
                        runCatching { onCompanionUpdated?.invoke() }
                    }.onFailure { Log.w("LocalMindApp", "学习任务失败（不影响对话）：" + it.message) }
                }
            }
            Unit
        }

        val api = storage.loadApiSettings()
        apiServer.updateSettings(api)
        if (api.enabled) {
            apiServer.start(api)
            if (api.bindLan) com.localmind.service.ApiForegroundService.start(this)
        }

        val lastId = storage.lastModelId()
        if (lastId != null) {
            // Loading is intentionally deferred to the UI so the user sees progress;
            // we only remember the choice here.
            pendingModelId = lastId
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        apiServer.stop()
        tts.shutdown()
        decisionEngine.release()
        engine.release()
    }

    /**
     * 插件清单的版本号。装/卸/开关插件时 +1。
     *
     * ⚠️ **必须是 StateFlow，不能是普通 Int。**
     *
     * 这里踩过一个很贵的坑：原来是 `@Volatile var Int`，然后界面用
     * `remember(dark, pluginRevision, overrideRevision)` 去算主题令牌。
     * 在 `remember` 的**参数位置**读一个普通变量**不会建立任何订阅** ——
     * 值变了也没人告诉 Compose，界面永远不重画。
     *
     * 表现就是"换了主题，界面没反应；切一下深浅色（整页重建）才偶尔生效"。
     * 现在改成 StateFlow，谁收集谁就会在变化时重组，行为不再依赖运气。
     */
    val pluginRevisionFlow = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** 用户手动覆盖主题令牌的版本号，同上。 */
    val overrideRevisionFlow = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** 当前值。给不收集 Flow 的调用方（如诊断端点）读。 */
    val pluginRevision: Int get() = pluginRevisionFlow.value
    val overrideRevision: Int get() = overrideRevisionFlow.value

    fun bumpPluginRevision() {
        pluginRevisionFlow.value += 1
    }

    fun bumpOverrideRevision() {
        overrideRevisionFlow.value += 1
    }

    fun settings(): GenerationSettings = storage.loadGenerationSettings()
    fun saveSettings(s: GenerationSettings) = storage.saveGenerationSettings(s)
    fun voice(): VoiceSettings = storage.loadVoiceSettings()
    fun saveVoice(v: VoiceSettings) = storage.saveVoiceSettings(v)
    fun api(): ApiSettings = storage.loadApiSettings()
    fun saveApi(a: ApiSettings) {
        storage.saveApiSettings(a)
        apiServer.updateSettings(a)
        val prefs = getSharedPreferences("localmind", Context.MODE_PRIVATE)
        prefs.edit().putInt("api_port", a.port).apply()
    }

    fun image(): ImageSettings = storage.loadImageSettings()
    fun saveImage(s: ImageSettings) = storage.saveImageSettings(s)

    companion object {
        @Volatile
        var pendingModelId: String? = null

        /**
         * 当前打开的对话 id，由 `AppViewModel` 在进入/离开对话时写入。
         *
         * 放在 Application 上而不是 ViewModel 里，是因为 `LlamaEngine.onTurnComplete`
         * 是应用级的钩子：HTTP API 触发的生成也会走它，而那时没有任何 ViewModel 参与。
         */
        @Volatile
        var activeConversationId: String? = null

        /**
         * 当前对话是不是「当人」模式。
         *
         * 与 [activeConversationId] 一起由 `AppViewModel` 维护。做法很土但很划算：
         * 这个钩子每轮都跑，而判断要不要积累经验只依赖这一个布尔值，
         * 没必要为它反序列化整个对话列表。
         */
        @Volatile
        var activeConversationHumanMind: Boolean = false

        /** HTTP API 触发的对话没有真实会话记录，用这个固定的虚拟 id 承载它的关系状态。 */
        const val API_CONVERSATION_ID = "api-server"

        /** 学习任务最多等多久的空闲窗口；等不到就跳过这一轮。 */
        private const val LEARNING_MAX_WAIT_MS = 12_000L

        /**
         * 用户现在是不是正在输入框里打字。
         *
         * 由 AppViewModel.setDraft 维护。学习的意义是「以后更像个人」，而用户
         * 正在敲字的时候恰恰是**下一句马上就要发出来**的时候 —— 这时候去占引擎，
         * 换来的就是「我一发送就卡住」。
         */
        @Volatile
        var userIsTyping: Boolean = false

        lateinit var instance: LocalMindApp
            private set

        fun of(context: Context): LocalMindApp = context.applicationContext as LocalMindApp
    }
}
