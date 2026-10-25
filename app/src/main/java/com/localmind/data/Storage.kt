package com.localmind.data

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.File

/**
 * All persistence lives here: settings in SharedPreferences, models/images on disk.
 * Deliberately dependency-free (no Room/DataStore) so the app stays buildable and
 * the data is trivially inspectable over adb.
 */
class Storage(private val context: Context) {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val prefs = context.getSharedPreferences("localmind", Context.MODE_PRIVATE)

    /** Exposed so helpers (e.g. ModelStore's one-time repair) can read flags. */
    fun prefs(): android.content.SharedPreferences = prefs

    /**
     * 应用上下文。给那些不属于这里、但又需要 `filesDir` 的模块用
     * （目前只有 `NeuralVoiceModels` 的音色目录）。
     *
     * 之所以不把音色目录也加成本类的属性：那边的路径规则（一个音色一个子目录、
     * 压缩包落点）是语音模块自己的事，摊在这里会把 244 行的存储层变成杂物间。
     */
    fun appContext(): Context = context

    private companion object {
        const val KEY_PROMPT_VERSION = "system_prompt_version"
    }

    val modelsDir: File get() = File(context.filesDir, "models").apply { mkdirs() }

    /**
     * 一个模型名下的全部临时产物：
     *  * `<名字>.part`     —— 单连接下载拼好的中间文件；
     *  * `<名字>.<总大小>.part.d` —— 多连接分段下载的段目录。
     *
     * 两者都是**断点续传的单位**，所以下载失败时刻意留着（下次接着下）。
     * 但"用户已经把模型删了"之后它们就没有主人了：界面上的存储占用看不见它们，
     * 模型列表里也没有它们，只有手机空间在变少。（实测上一个安装的
     * `files/models/` 里就躺着 `sd_turbo-f16-q8_0.gguf.2023745376.part.d`。）
     */
    fun leftoverParts(fileName: String): List<File> =
        modelsDir.listFiles()?.filter {
            it.name == "$fileName.part" ||
                (it.name.startsWith("$fileName.") && it.name.endsWith(".part.d"))
        } ?: emptyList()

    /** 所有模型的临时产物 —— 给测试模式的「存储占用」用。 */
    fun allLeftoverParts(): List<File> =
        modelsDir.listFiles()?.filter {
            it.name.endsWith(".part") || it.name.endsWith(".part.d")
        } ?: emptyList()
    val imagesDir: File get() = File(context.filesDir, "images").apply { mkdirs() }
    val sharedDir: File get() = File(context.filesDir, "shared").apply { mkdirs() }
    val logDir: File get() = File(context.filesDir, "logs").apply { mkdirs() }
    val conversationsFile: File get() = File(context.filesDir, "conversations.json")
    val apiLogFile: File get() = File(context.filesDir, "api-log.json")
    val importedModelsFile: File get() = File(context.filesDir, "imported-models.json")
    val personasFile: File get() = File(context.filesDir, "personas.json")
    val companionFile: File get() = File(context.filesDir, "companion.json")
    val providersFile: File get() = File(context.filesDir, "remote-providers.json")

    // ------------------------------------------------------------ settings

    /** 1.0.3 的推理默认值迁移标记（KV q8_0 / prefill 批 1024）。 */
    private val KEY_GEN_V2 = "gen_v2"

    fun loadGenerationSettings(): GenerationSettings {
        var stored = read("gen", GenerationSettings())
        // The system prompt ships with the app, so a stale saved copy would keep a
        // model lying about its identity ("I was made by DeepSeek / Huawei / …") even
        // after the prompt is fixed. Bump PROMPT_VERSION whenever the default changes.
        val seen = prefs.getInt(KEY_PROMPT_VERSION, 0)
        if (seen < GenerationSettings.PROMPT_VERSION) {
            prefs.edit().putInt(KEY_PROMPT_VERSION, GenerationSettings.PROMPT_VERSION).apply()
            stored = stored.copy(systemPrompt = GenerationSettings.DEFAULT_SYSTEM_PROMPT)
            saveGenerationSettings(stored)
        }

        // ---- 1.0.3 的推理默认值（只迁一次，且只迁「还停在旧默认值」的那份）----
        //
        // GenerationSettings 的默认值只在**第一次写盘之前**生效。老用户手机里存着
        // kvQuant=f16 / batchSize=512 这两个旧默认值，光改 Kotlin 的 default 对他们
        // 零作用 —— 而这两项正是「同样模型、更快一点」的一部分。
        // 判据用**精确匹配旧默认值**：用户自己调过的值不会被碰。
        if (!prefs.getBoolean(KEY_GEN_V2, false)) {
            prefs.edit().putBoolean(KEY_GEN_V2, true).apply()
            var next = stored
            var changed = false
            if (next.kvQuant.equals("f16", ignoreCase = true)) {
                next = next.copy(kvQuant = "q8_0")
                changed = true
            }
            if (next.batchSize == 512) {
                next = next.copy(batchSize = 1024)
                changed = true
            }
            if (changed) {
                saveGenerationSettings(next)
                stored = next
            }
        }
        return stored
    }
    fun saveGenerationSettings(s: GenerationSettings) = write("gen", s)

    fun loadVoiceSettings(): VoiceSettings = read("voice", VoiceSettings())
    fun saveVoiceSettings(s: VoiceSettings) = write("voice", s)

    fun loadApiSettings(): ApiSettings = read("api", ApiSettings())
    fun saveApiSettings(s: ApiSettings) = write("api", s)

    /** 1.0.3 起默认出图尺寸从 768² 改成 512² 的一次性迁移标记。 */
    private val KEY_IMAGE_SIZE_V2 = "image_size_v2"

    /**
     * 出图设置。
     *
     * ## 为什么要迁移"尺寸"这一项
     *
     * 1.0.2 及以前默认 768²，而手机 CPU 出图耗时和面积成正比：768² 是 512² 的
     * 2.25 倍。用户根本没选过这个数字（它是默认值），却一直在付这笔时间 ——
     * "生图太慢"的反馈里有一大半是这个默认值造成的。
     *
     * 所以升级时把**恰好停在旧默认值**的那一份改成 512²；用户自己挑过的（512/1024）
     * 一律不动 —— 那不是默认值带来的，是他自己的决定。
     */
    fun loadImageSettings(): ImageSettings {
        val stored = read("image", ImageSettings())
        if (!prefs.getBoolean(KEY_IMAGE_SIZE_V2, false)) {
            prefs.edit().putBoolean(KEY_IMAGE_SIZE_V2, true).apply()
            if (stored.width == 768 && stored.height == 768) {
                val migrated = stored.copy(width = 512, height = 512)
                saveImageSettings(migrated)
                return migrated
            }
        }
        return stored
    }
    fun saveImageSettings(s: ImageSettings) = write("image", s)

    fun loadCapabilities(): CapabilitySettings = read("capabilities", CapabilitySettings())
    fun saveCapabilities(c: CapabilitySettings) = write("capabilities", c)

    fun loadProxySettings(): ProxySettings = read("proxy", ProxySettings())
    fun saveProxySettings(p: ProxySettings) = write("proxy", p)

    fun loadRouting(): ModelRouting = read("routing", ModelRouting())
    fun saveRouting(r: ModelRouting) = write("routing", r)

    fun loadUserProfile(): UserProfile = read("user_profile", UserProfile())
    fun saveUserProfile(p: UserProfile) = write("user_profile", p)

    /** 已安装的插件。key 是插件 id，方便按 id 覆盖/卸载。 */
    fun loadPlugins(): Map<String, InstalledPlugin> = read("plugins", emptyMap())
    fun savePlugins(map: Map<String, InstalledPlugin>) = write("plugins", map)

    /**
     * 已安装的**模型包**。key 是 packId。
     *
     * 和 [loadPlugins] 分开存：模型包是纯数据（一堆下载地址），没有"能力"的概念，
     * 也不需要权限声明，塞进插件那套结构反而要多写一堆空字段。
     */
    fun loadModelPacks(): Map<String, ModelPackFile> = read("model_packs", emptyMap())
    fun saveModelPacks(map: Map<String, ModelPackFile>) = write("model_packs", map)

    /** 缓存目录。可随时被系统清掉，只放能重新生成的东西（如合成出来的 wav）。 */
    val cacheDir: File get() = appContext().cacheDir

    /** 已启用的模型包 id。没启用的包里的条目不会出现在模型列表里。 */
    fun enabledModelPackIds(): Set<String> = read("model_packs_enabled", emptySet<String>())
    fun saveEnabledModelPackIds(ids: Set<String>) = write("model_packs_enabled", ids)

    /**
     * 已安装的**语音音色包**。key 是插件 id。
     *
     * 和模型包一样的处理：纯数据（一堆音色地址），没有"能力"概念。
     * 音色能不能用取决于插件是否启用 —— 见 `AppViewModel.refreshVoiceCatalog`。
     */
    fun loadVoicePacks(): Map<String, VoicePackFile> = read("voice_packs", emptyMap())
    fun saveVoicePacks(map: Map<String, VoicePackFile>) = write("voice_packs", map)

    /**
     * 已安装的**界面主题包**。key 是插件 id。
     *
     * 生效的是**最后一个启用的**（多个界面插件叠加会互相打架，所以只取一个，
     * 并如实告诉用户现在用的是哪个）。
     */
    fun loadUiThemes(): Map<String, UiTheme> = read("ui_themes", emptyMap())
    fun saveUiThemes(map: Map<String, UiTheme>) = write("ui_themes", map)

    /** 用户对主题令牌的手动覆盖。优先级高于任何插件。 */
    fun themeOverrides(): Map<String, String> = read("theme_overrides", emptyMap())
    fun saveThemeOverrides(map: Map<String, String>) = write("theme_overrides", map)

    /**
     * 已安装的**语音引擎包**（`tts.json`）。key 是插件 id。
     *
     * 和 [loadVoicePacks] 的区别：那个是"音色模型文件能在手机上跑"，
     * 这个是"合成发生在别的机器上"。两者可以同时存在，界面里分开列。
     */
    fun loadTtsPacks(): Map<String, TtsPackFile> = read("tts_packs", emptyMap())
    fun saveTtsPacks(map: Map<String, TtsPackFile>) = write("tts_packs", map)

    /** 当前用哪个引擎，形如 `插件id/引擎id`。空 = 没选。 */
    fun activeTtsEngineId(): String = read("active_tts_engine", "")

    /**
     * 启动时预热上次用的对话模型。
     *
     * 为什么值得单开一个开关：加载 1~4 GB 的 GGUF 要几秒到几十秒，而用户点开
     * 应用后的第一个动作几乎总是"接着上一次聊"。预热把这段等待挪到他没有在等的
     * 时候（启动画面、看列表），代价是启动后多占一份内存。
     * 低内存机型应该关掉它。
     */
    fun preloadModel(): Boolean = read("preload_model", true)
    fun savePreloadModel(on: Boolean) = write("preload_model", on)

    /** 用户是否把外部语音引擎设成"念回复时优先用它"。 */
    fun breezeEnabled(): Boolean = read("breeze_enabled", false)
    fun saveBreezeEnabled(on: Boolean) = write("breeze_enabled", on)
    fun saveActiveTtsEngineId(id: String) = write("active_tts_engine", id)

    /**
     * 用户改了引擎配置（地址 / 密钥 / 默认演技）之后写回去。
     *
     * 为什么要写回插件包而不是另存一份覆盖：引擎的配置项很多（地址、密钥、模型名、
     * 采样率、格式…），另存一份"用户覆盖"就要为每一项再定义一次优先级，
     * 而这里只有"插件给的初值"和"用户后来的值"两种情况。
     */
    fun updateTtsEngine(packId: String, engine: TtsEngineSpec) {
        val packs = loadTtsPacks()
        val pack = packs[packId] ?: return
        val updated = pack.copy(engines = pack.engines.map { if (it.id == engine.id) engine else it })
        saveTtsPacks(packs + (packId to updated))
    }

    /** 已安装的**分步流水线包**（`pipeline.json`）。key 是插件 id。 */
    fun loadPipelinePacks(): Map<String, PipelinePackFile> = read("pipeline_packs", emptyMap())
    fun savePipelinePacks(map: Map<String, PipelinePackFile>) = write("pipeline_packs", map)

    /** 已安装的**界面布局包**（`layout.json`）。key 是插件 id。 */
    fun loadUiLayouts(): Map<String, UiLayout> = read("ui_layouts", emptyMap())
    fun saveUiLayouts(map: Map<String, UiLayout>) = write("ui_layouts", map)

    /** 当前生效的布局包 id。空 = 用主程序默认布局。 */
    fun activeLayoutId(): String = read("active_layout", "")
    fun saveActiveLayoutId(id: String) = write("active_layout", id)

    /** 插件解压/导入后的资源目录，插件按 id 各占一个子目录。 */
    val pluginsDir: File get() = File(context.filesDir, "plugins").apply { mkdirs() }

    /**
     * 运行时下载的原生库目录，按 ABI 分子目录。
     *
     * 和 APK 里的 `nativeLibraryDir`（只读、随包走）是两回事：这里是**下载来的**
     * 后端，`System.load` 一个绝对路径。语音引擎与生图后端共用这个目录。
     */
    val nativeDir: File get() = File(context.filesDir, "native").apply { mkdirs() }

    /**
     * 当前启用的表情包（来自表情包插件）。
     *
     * 只存**一份**而不是按插件 id 存多份：同一时刻只能有一套表情在生效，
     * 否则提示词里会同时出现两套标签，模型会摇摆。装新的覆盖旧的。
     */
    fun loadStickerPack(): StickerPack? = read("sticker_pack", null)

    /** 表情包的资源目录（图片在哪里）。装包时记下来，换包时跟着换。 */
    fun stickerAssetDir(): File? =
        read<String>("sticker_assets", "")?.takeIf { it.isNotBlank() }?.let { File(it) }

    fun saveStickerPack(pack: StickerPack?, assets: File?) {
        if (pack == null) {
            prefs.edit().remove("sticker_pack").remove("sticker_assets").apply()
            return
        }
        write("sticker_pack", pack)
        write("sticker_assets", assets?.absolutePath.orEmpty())
    }

    /** Built-in presets plus anything the user created or edited. */
    fun loadPersonas(): MutableList<Persona> {
        val f = personasFile
        val stored = if (f.exists()) {
            runCatching { json.decodeFromString<MutableList<Persona>>(f.readText()) }
                .getOrDefault(mutableListOf())
        } else {
            mutableListOf()
        }
        // Merge: built-ins always available, user edits by id win.
        val merged = PersonaPresets.all.toMutableList()
        stored.forEach { saved ->
            val idx = merged.indexOfFirst { it.id == saved.id }
            if (idx >= 0) merged[idx] = saved else merged.add(saved)
        }
        return merged
    }

    /** Only user-created/edited personas are persisted. */
    fun savePersonas(list: List<Persona>) {
        val custom = list.filter { p ->
            val preset = PersonaPresets.all.firstOrNull { it.id == p.id }
            preset == null || preset != p
        }
        runCatching { personasFile.writeText(json.encodeToString(serializer<List<Persona>>(), custom)) }
    }

    fun activePersonaId(): String = prefs.getString("active_persona", PersonaPresets.default.id)
        ?: PersonaPresets.default.id
    fun setActivePersonaId(id: String) = prefs.edit().putString("active_persona", id).apply()

    // ------------------------------------------------- 神经音色的崩溃看门狗
    //
    // 加载音色要进 libsherpa-onnx-jni.so。那个库在配置不对时**不是抛异常**，
    // 而是直接 exit() / abort 掉整个进程 —— runCatching 拦不住，Java 侧连栈都拿不到。
    // 实测：把 Matcha 的 onnx 当 VITS 加载，进程会在 2 秒内消失。
    //
    // 于是出现一种很难查的故障：用户选了一个坏音色 → 闪退；而音色 id 在选择时
    // 就已经落盘，restoreNeuralVoice() 每次启动都重试同一个 → **应用再也打不开**。
    //
    // 看门狗的做法很土但有效：进原生调用**之前**写一个标记，调用返回后清掉。
    // 标记还在 = 上一次就是死在里面的。启动时据此把那个音色拉黑 —— 宁可少一个音色，
    // 也不能让应用变成砖。

    /** 正在加载的音色 id；空串表示当前没有加载中的原生调用。 */
    fun neuralLoadMarker(): String = prefs.getString("neural_load_marker", "").orEmpty()

    fun setNeuralLoadMarker(id: String?) =
        prefs.edit().putString("neural_load_marker", id.orEmpty()).apply()

    /** 已知会把进程带走的音色 id。 */
    fun neuralBadVoices(): Set<String> =
        prefs.getStringSet("neural_bad_voices", emptySet())?.toSet() ?: emptySet()

    fun markNeuralVoiceBad(id: String) {
        if (id.isBlank()) return
        prefs.edit().putStringSet("neural_bad_voices", neuralBadVoices() + id).apply()
    }

    fun clearNeuralVoiceBad(id: String) {
        if (id.isBlank()) return
        prefs.edit().putStringSet("neural_bad_voices", neuralBadVoices() - id).apply()
    }

    /**
     * Long-term companion state per persona (memory / mood / drifting traits).
     * Separate file from `personas.json` on purpose: this grows continuously while a
     * persona definition is small and rarely written.
     */
    fun loadCompanionStates(): MutableMap<String, CompanionState> {
        val f = companionFile
        if (!f.exists()) return mutableMapOf()
        return runCatching {
            json.decodeFromString<MutableMap<String, CompanionState>>(f.readText())
        }.getOrDefault(mutableMapOf())
    }

    fun saveCompanionStates(map: Map<String, CompanionState>) {
        runCatching {
            val ordered = linkedMapOf<String, CompanionState>()
            map.entries.sortedByDescending { it.value.lastTalkedAt }.forEach { ordered[it.key] = it.value }
            companionFile.writeText(json.encodeToString(serializer<Map<String, CompanionState>>(), ordered))
        }
    }

    fun loadRemoteProviders(): MutableList<RemoteProvider> {
        val f = providersFile
        if (!f.exists()) return mutableListOf()
        return runCatching {
            json.decodeFromString<MutableList<RemoteProvider>>(f.readText())
        }.getOrDefault(mutableListOf())
    }

    fun saveRemoteProviders(list: List<RemoteProvider>) {
        runCatching { providersFile.writeText(json.encodeToString(serializer<List<RemoteProvider>>(), list)) }
    }

    /** Which engine answers chat: "local" or a [RemoteProvider] id. */
    fun chatBackend(): String = prefs.getString("chat_backend", "local") ?: "local"
    fun setChatBackend(id: String) = prefs.edit().putString("chat_backend", id).apply()

    fun lastModelId(): String? = prefs.getString("last_model", null)
    fun setLastModelId(id: String?) = prefs.edit().putString("last_model", id).apply()

    fun themeMode(): String = prefs.getString("theme_mode", "system") ?: "system"

    /**
     * 现在到底是不是深色。
     *
     * ViewModel 里拿不到 `isSystemInDarkTheme()`（那是 @Composable），
     * 但又必须**在第一帧之前**就知道深浅色，否则会先按浅色画一帧再跳。
     * 所以这里自己按 `theme_mode` 判断一次：`system` 看系统的
     * `uiMode`，其余看用户选的那个。
     */
    fun isDarkNow(): Boolean = when (themeMode()) {
        "dark" -> true
        "light" -> false
        else -> (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
    fun setThemeMode(mode: String) = prefs.edit().putString("theme_mode", mode).apply()

    fun keepScreenOn(): Boolean = prefs.getBoolean("keep_screen_on", true)

    /**
     * 通知权限是否已经问过。
     *
     * Android 13+ 的 POST_NOTIFICATIONS 被拒绝之后**还能再弹**（不像别的权限
     * 拒绝一次就不再弹），所以只要启动时无条件申请，拒绝过的用户就会
     * **每一次启动都看到同一个弹窗** —— 这在内测里就是「这应用每次打开都弹广告」。
     * 记一个标记：问过一次就不再问，用户以后想开可以去系统设置里开。
     */
    fun notificationAsked(): Boolean = prefs.getBoolean("notif_asked", false)
    fun setNotificationAsked() = prefs.edit().putBoolean("notif_asked", true).apply()
    fun setKeepScreenOn(v: Boolean) = prefs.edit().putBoolean("keep_screen_on", v).apply()

    fun acceptedLicense(): Boolean = prefs.getBoolean("accepted_license", false)
    fun setAcceptedLicense(v: Boolean) = prefs.edit().putBoolean("accepted_license", v).apply()

    fun firstRunDone(): Boolean = prefs.getBoolean("first_run_done", false)
    fun setFirstRunDone(v: Boolean) = prefs.edit().putBoolean("first_run_done", v).apply()

    private inline fun <reified T> read(key: String, default: T): T {
        val raw = prefs.getString(key, null) ?: return default
        return runCatching { json.decodeFromString<T>(raw) }.getOrDefault(default)
    }

    private inline fun <reified T> write(key: String, value: T) {
        prefs.edit().putString(key, json.encodeToString(serializer<T>(), value)).apply()
    }

    // ------------------------------------------------------- conversations

    fun loadConversations(): MutableList<Conversation> {
        val f = conversationsFile
        if (!f.exists()) return mutableListOf()
        return runCatching {
            json.decodeFromString<MutableList<Conversation>>(f.readText())
        }.getOrDefault(mutableListOf())
    }

    fun saveConversations(list: List<Conversation>) {
        runCatching { conversationsFile.writeText(json.encodeToString(serializer<List<Conversation>>(), list)) }
    }

    fun loadImportedModels(): MutableList<LocalModel> {
        val f = importedModelsFile
        if (!f.exists()) return mutableListOf()
        return runCatching {
            json.decodeFromString<MutableList<LocalModel>>(f.readText())
        }.getOrDefault(mutableListOf())
    }

    fun saveImportedModels(list: List<LocalModel>) {
        runCatching { importedModelsFile.writeText(json.encodeToString(serializer<List<LocalModel>>(), list)) }
    }

    fun saveApiLog(list: List<ApiLogEntry>) {
        runCatching { apiLogFile.writeText(json.encodeToString(serializer<List<ApiLogEntry>>(), list)) }
    }

    fun loadApiLog(): MutableList<ApiLogEntry> {
        val f = apiLogFile
        if (!f.exists()) return mutableListOf()
        return runCatching {
            json.decodeFromString<MutableList<ApiLogEntry>>(f.readText())
        }.getOrDefault(mutableListOf())
    }

    /** Total bytes used by downloaded models + generated images. */
    fun usage(): Triple<Long, Long, Long> {
        val modelBytes = modelsDir.listFiles()?.sumOf { it.length() } ?: 0L
        val imageBytes = imagesDir.listFiles()?.sumOf { it.length() } ?: 0L
        val free = MemoryPlanner.availableStorageBytes(context.filesDir)
        return Triple(modelBytes, imageBytes, free)
    }

    fun clearImages(): Int {
        val files = imagesDir.listFiles() ?: return 0
        var n = 0
        files.forEach { if (it.delete()) n++ }
        return n
    }
}
