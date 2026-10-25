package com.localmind.data

import kotlinx.serialization.Serializable

/**
 * A model is either one of the curated catalogue entries, or a GGUF the user
 * sideloaded (file picker / adb push / URL import).
 */
@Serializable
data class LocalModel(
    val id: String,
    val name: String,
    val repoId: String,
    val fileName: String,
    val sizeBytes: Long,
    val url: String,
    val mirrors: List<String> = emptyList(),
    val license: String = "unknown",
    val publisher: String = "",
    val family: String = "",
    val params: String = "",
    val quant: String = "",
    val description: String = "",
    val tags: List<String> = emptyList(),
    val recommendedRamGb: Int = 4,
    val vision: Boolean = false,
    val embedding: Boolean = false,
    /**
     * 语音模型（离线 TTS 音色 / 未来的 ASR）。
     *
     * ⚠️ **这类条目不能走普通的 GGUF 下载器**。语音模型是 sherpa-onnx 的
     * `.tar.bz2` 音色包，要解压到 `files/voices/` 下、并且由
     * [com.localmind.service.NeuralVoiceModels] 管理。所以：
     *
     *  * 它在模型页的「语音」分类里展示（让用户**看得见**有哪些语音模型）；
     *  * 但下载按钮走的是音色下载器（见 `AppViewModel.downloadVoice`），
     *    下载完就能在「我的 → 语音与音色」里直接用。
     *
     * 把它做成普通 `LocalModel` 再让 GGUF 下载器拉，会得到一个躺在
     * `files/models/` 里、永远加载不了的 `.tar.bz2` —— 那是假功能。
     */
    val voice: Boolean = false,
    /**
     * 语音识别（ASR）模型。
     *
     * 主程序目前**没有**离线 ASR：`SttEngine` 用的是 Android 系统的
     * `SpeechRecognizer`。所以这类条目今天只用于**如实展示**"有哪些可用的
     * 端侧识别模型"，并写明还没接线 —— 比让用户以为能用要好。
     */
    val asr: Boolean = false,
    /**
     * Trained for judgement rather than conversation: pick an option, answer yes/no,
     * rate something. These are the "decision model" (System One / Jev-style) family
     * and are offered in the 决策模型 picker instead of the chat picker.
     */
    val decisionModel: Boolean = false,
    /**
     * 这个模型在**上游页面**（Ollama / GitHub / ModelScope）的地址。
     *
     * 为什么要它：模型页只有一个下载按钮，用户看不到"这是谁家的、有没有更新、
     * 有没有别的量化"。而 Ollama 的库里几乎所有主流开源模型都有页面，
     * GitHub 上的项目页则有用法和许可说明。
     *
     * ⚠️ **不能拿 Ollama 的地址当下载源**：Ollama registry 的 blob 是它自己的
     * 内容寻址格式（要 manifest 才能拼出来），不是能直接喂给 llama.cpp 的
     * 单文件 GGUF。所以这个字段只用于"去看看"，下载仍走 [url]。
     */
    val sourceUrl: String = "",
    val imported: Boolean = false,
    /**
     * 配套的视觉投影（mmproj）：文件名 / 地址 / 镜像 / 大小。
     *
     * 多模态模型是**两个文件**：主 GGUF 负责说话，mmproj 负责把图变成它读得懂的
     * 嵌入。只下主模型的话，用户看到的是"发了图它却说没看见" —— 这一直是识图
     * 用不起来的直接原因之一。所以这两个字段必须有，下载器才会一起拉。
     *
     * 非多模态模型全部留空。文件名与字节数见 docs/MODEL-CANDIDATES.md §3（逐个实测过）。
     */
    val mmprojFileName: String = "",
    val mmprojUrl: String = "",
    val mmprojMirrors: List<String> = emptyList(),
    val mmprojSizeBytes: Long = 0,
    /**
     * 这个条目来自哪个模型包。
     *
     * 空串表示它来自 APK 内置的那份精简清单；其它值对应一个已安装的模型包插件。
     * 用它来区分"内置"和"插件提供" —— 卸载插件时要按它把条目从列表里摘掉。
     */
    val packId: String = "",
) {
    /**
     * 配套视觉投影在本机的绝对路径（没下全 / 不是多模态 → null）。
     *
     * 抽出来是因为**两处都要用**：界面加载模型、API 切模型。之前 API 那条路漏了，
     * 结果"用 API 切过去的模型看不见图"，而界面切过去就行 —— 这种不一致最难查。
     */
    fun mmprojPathOn(modelsDir: java.io.File): String? =
        mmprojFileName.takeIf { it.isNotBlank() }
            ?.let { java.io.File(modelsDir, it) }
            ?.takeIf { it.isFile && it.length() > 0 }
            ?.absolutePath

    /** Absolute path of the .gguf on this device, filled in by [ModelStore]. */
    @kotlinx.serialization.Transient
    var localPath: String? = null

    val isDownloaded: Boolean
        get() = localPath?.let { java.io.File(it).let { f -> f.exists() && f.length() > 0 } } ?: false

    /**
     * 是不是生图（扩散）模型。
     *
     * 和 `ModelsScreen` 的「生图」分类用同一个判据 —— 那里原本写的是
     * `model.family == "Stable Diffusion"`，两处各写一份迟早会分叉，
     * 所以收敛到这里。
     */
    val isImageModel: Boolean get() = family == "Stable Diffusion"

    /** 是不是多模态（能看图）模型。 */
    val isVisionModel: Boolean get() = vision

    /** 是不是语音模型（TTS 音色 / ASR）。 */
    val isVoiceModel: Boolean get() = voice || asr

    fun file(): java.io.File? = localPath?.let { java.io.File(it) }

    /** Shown in the UI when the model is a sideloaded file with no catalogue entry. */
    val displayQuant: String get() = quant.ifEmpty { if (imported) "本机导入" else "GGUF" }

    /**
     * 界面上该显示的体积：**文件在本地时以磁盘上的真实长度为准**。
     *
     * 清单里的 sizeBytes 是下载前用来显示「要下多大」的声明值，它会过期
     *（模型仓库换了文件、或者清单写错了）。而同一个 GGUF 可能被两条清单引用
     *（对话 + 看图 / 对话 + 决策），两条的声明值不一致时，用户看到的就是
     ***同一个文件两个体积** —— 一条 468.64 MB、另一条 378.61 MB。
     * 落盘之后真值只有一个：文件本身。
     */
    val displaySizeBytes: Long
        get() = localPath?.let { java.io.File(it).length() }?.takeIf { it > 0 } ?: sizeBytes
}

/**
 * Resolves catalogue entries against what is actually on disk; also merges in
 * models the user sideloaded.
 */
class ModelStore(private val storage: Storage) {

    companion object {
        private const val PREFS = "localmind"
        private const val KEY_REPAIRED = "imported_repaired_v1"
        private const val KEY_ORPHAN_PACKS = "orphan_packs_v1"

        /**
         * 清掉**没有对应插件的模型包**。
         *
         * 为什么需要：1.0.2 之前，APK 里塞了一份 34 条的模型清单，首次运行
         * 由 BuiltinPacks 直接写进 model_packs —— 它**不走插件注册**，所以：
         *  * 插件页里看不到它（用户以为没装东西）；
         *  * 也就**删不掉**（插件页的删除按插件走）。
         *
         * 这些包现在必须消失：那份清单是第三方模型的索引，主程序不该分发它。
         * 判据很硬：model_packs 里有、plugins 里没有 = 不是用户导入的插件（正常导入
         * 一定两处都写），只可能是旧版内置塞进去的。
         */
        fun dropOrphanModelPacks(storage: Storage) {
            val prefs = storage.prefs()
            if (prefs.getBoolean(KEY_ORPHAN_PACKS, false)) return
            runCatching {
                val packs = storage.loadModelPacks()
                if (packs.isEmpty()) {
                    prefs.edit().putBoolean(KEY_ORPHAN_PACKS, true).apply()
                    return
                }
                val installed = storage.loadPlugins().keys
                val orphans = packs.keys.filterNot { it in installed }
                if (orphans.isNotEmpty()) {
                    storage.saveModelPacks(packs.filterKeys { it !in orphans })
                    storage.saveEnabledModelPackIds(storage.enabledModelPackIds() - orphans.toSet())
                    android.util.Log.i("ModelStore", "清掉无插件来源的模型包：" + orphans.joinToString())
                }
                prefs.edit().putBoolean(KEY_ORPHAN_PACKS, true).apply()
            }
        }

        /** One-time cleanup of imported entries whose file is no longer on disk. */
        fun repairOnce(storage: Storage) {
            dropOrphanModelPacks(storage)
            val prefs = storage.prefs()
            if (prefs.getBoolean(KEY_REPAIRED, false)) return
            val kept = storage.loadImportedModels().filter { model ->
                java.io.File(storage.modelsDir, model.fileName).exists()
            }
            storage.saveImportedModels(kept)
            prefs.edit().putBoolean(KEY_REPAIRED, true).apply()
        }
    }

    fun resolveAll(): List<LocalModel> {
        repairOnce(storage)
        val imported = storage.loadImportedModels()
        // 内置精简清单 + 已安装模型包提供的条目 + 用户自己导入的文件。
        //
        // 为什么地址不放 APK 里：模型地址会失效、会出新的量化版本、会换镜像，
        // 而每改一次都发一个 APK 是不现实的。做成模型包之后更新地址只需要
        // 换一个 zip，用户自己也能加私有镜像。
        val fromPacks = storage.loadModelPacks()
            .values
            .flatMap { it.models }
            .filter { m -> m.packId.isBlank() || m.packId in storage.enabledModelPackIds() }

        // 去重顺序有讲究：**模型包优先于内置清单**。
        //
        // 内置那份是"能用但会过期"的兜底，让新装的 APK 开箱就能下一个模型；
        // 模型包则是可更新的权威来源。同 id 时让包赢，用户装一个包就能把
        // 所有失效地址一次性换掉 —— 这正是把地址做成包的目的。
        //
        // 用户自己导入的文件排在最后：它没有版本概念，不该覆盖有维护的来源。
        val all = (fromPacks + ModelCatalog.models + imported)
            .distinctBy { it.id }
            .distinctBy { it.fileName.lowercase() + "|" + roleKey(it) }
        val resolved = all.map { m -> m.also { it.localPath = resolvePath(m) } }
        return resolved + orphanFiles(resolved)
    }

    /**
     * `files/models/` 里**没有任何清单条目引用**的 .gguf。
     *
     * 为什么需要它：`resolveAll()` 只认清单里的 fileName，于是清单更新过之后
     *（模型下架、换量化、换文件名），用户**已经下好的文件就从应用里彻底消失**：
     * 模型页看不到、删不掉，而磁盘上那几百 MB 一直在。用户的原话是
     * 「我下过的东西不见了，磁盘还被占着」——这比下载失败更让人不信任。
     *
     * 现在它们作为「本机文件」列出来：能加载、能删除，也能看出是什么。
     * 只收 `.gguf` 且跳过所有被引用的名字（含 mmproj），所以不会和正常条目重复，
     * 也不会把 `.part` / `.part.d` 这些临时产物混进列表。
     */
    private fun orphanFiles(known: List<LocalModel>): List<LocalModel> {
        val referenced = known
            .flatMap { listOf(it.fileName, it.mmprojFileName) }
            .filter { it.isNotBlank() }
            .map { it.lowercase() }
            .toSet()
        return storage.modelsDir.listFiles()
            ?.filter { it.isFile && it.length() > 0 && it.name.endsWith(".gguf", ignoreCase = true) }
            ?.filter { it.name.lowercase() !in referenced }
            ?.sortedBy { it.name }
            ?.map { f ->
                LocalModel(
                    id = "local-" + f.name.hashCode().toString(16),
                    name = f.nameWithoutExtension,
                    repoId = "本机文件",
                    fileName = f.name,
                    sizeBytes = f.length(),
                    url = "",
                    license = "见原始仓库",
                    publisher = "本机文件",
                    family = "GGUF",
                    description = "这个文件在模型库里没有对应条目（清单更新过，或者是手动放进来的）。" +
                        "可以直接加载；不用了就删掉腾空间。",
                    tags = listOf("本机文件"),
                    recommendedRamGb = (f.length() / (1024L * 1024 * 1024)).toInt() + 2,
                    imported = true,
                ).also { it.localPath = f.absolutePath }
            }
            ?: emptyList()
    }

    /**
     * 「同一个文件、但用途不同」要算两条，不能算重复。
     *
     * 原来这里是 `distinctBy { it.fileName.lowercase() }` —— 只按文件名去重，
     * 于是一份 GGUF 只能出现在一个槽位里。而本项目的设计恰恰相反：
     *  * Qwen3.5 / Gemma 4 这类是**一个 GGUF 两用**（加了 mmproj 能看图，
     *    不加也能纯文字对话），清单里本来就同时有「对话」和「看图」两条；
     *  * 内置清单里的「Qwen2.5 0.5B（通用决策备选）」和「Qwen2.5 0.5B Instruct」
     *    是同一个权重文件、两种用途。
     *
     * 实测后果（模拟器，全新安装）：内置清单有 34 条，界面只列出 33 条，
     * **消失的正是那条唯一的中文决策模型** —— 用户在决策模型选择器里永远看不到它，
     * 也不会知道少了什么。装官方模型库插件（52 条、5 组同名）时一次会少 5 条。
     *
     * 现在按「文件名 + 用途」去重：同一个文件、同一种用途才算重复（用户自己导入的
     * 文件仍然会让位给清单里的同名条目），用途不同就两条都留着。
     */
    private fun roleKey(m: LocalModel): String = buildString {
        if (m.vision) append('v')
        if (m.decisionModel) append('d')
        if (m.embedding) append('e')
        if (m.voice) append('o')
        if (m.asr) append('a')
    }

    /** GGUF header metadata, only for models that are actually on disk. */
    fun infoOf(model: LocalModel): MemoryPlanner.GgufInfo? {
        val file = model.file() ?: return null
        return runCatching { MemoryPlanner.readGguf(file) }.getOrNull()
    }

    fun resolvePath(model: LocalModel): String? {
        val candidate = java.io.File(storage.modelsDir, model.fileName)
        if (candidate.exists() && candidate.length() > 0) return candidate.absolutePath
        // fall back to any file whose name matches loosely (user renamed it)
        val loose = storage.modelsDir.listFiles()?.firstOrNull {
            it.name.equals(model.fileName, ignoreCase = true)
        }
        return loose?.absolutePath
    }

    fun downloaded(): List<LocalModel> = resolveAll().filter { it.isDownloaded }

    fun addImported(file: java.io.File): LocalModel {
        val info = MemoryPlanner.readGguf(file)
        val model = LocalModel(
            id = "local-" + file.name.hashCode().toString(16),
            name = file.nameWithoutExtension,
            repoId = "本机文件",
            fileName = file.name,
            sizeBytes = file.length(),
            url = "",
            license = "见原始仓库",
            publisher = "本机导入",
            family = info?.architecture ?: "GGUF",
            params = info?.embeddingLength?.let { "d=$it" } ?: "",
            quant = info?.quantLabel ?: "",
            description = "从设备导入的模型文件：" + MemoryPlanner.preview(info),
            tags = listOf("本机导入"),
            recommendedRamGb = ((file.length() / (1024L * 1024 * 1024)).toInt() + 2),
            imported = true,
        )
        model.localPath = file.absolutePath
        val list = storage.loadImportedModels().filterNot { it.fileName == file.name }.toMutableList()
        list += model
        storage.saveImportedModels(list)
        return model
    }

    fun removeImported(id: String) {
        val list = storage.loadImportedModels().filterNot { it.id == id }
        storage.saveImportedModels(list)
    }
}

/**
 * Curated, URL-verified GGUF catalogue. Hugging Face is frequently unreachable
 * from mainland China, so every entry carries an `hf-mirror.com` URL first and a
 * ModelScope / GitHub mirror where one exists. Sizes are the real
 * Content-Length of the target file (verified at build time).
 */
object ModelCatalog {

    const val HF = "https://hf-mirror.com"
    const val HF_ORIGINAL = "https://huggingface.co"

    /**
     * 内置模型清单**故意为空**。
     *
     * 模型库现在是一份可通过插件导入的 JSON（`官方模型库插件.zip`，83 条）。主程序不自带清单的三个理由：地址会失效、每改一次都要
     * 发 APK、以及"APK 只是运行时"这件事应该对用户诚实。
     *
     * 保留这个空 list 而不是删掉它，是为了让 `ModelStore.resolveAll()` 不必
     * 到处判空 —— 没装模型库插件时，用户仍然可以导入本地 .gguf 或用云端 API。
     */
    val models: List<LocalModel> = emptyList()

    /** Models that are plausible on a typical 2024-2026 phone, best first. */
    fun recommendedFor(totalRamGb: Double): List<LocalModel> =
        models.sortedBy { it.sizeBytes }.filter { it.recommendedRamGb <= totalRamGb + 1 }

    fun byId(id: String): LocalModel? = models.firstOrNull { it.id == id }

    /** Parses `owner/repo` + optional file name out of a Hugging Face style URL or id. */
    fun parseRepoInput(input: String): Pair<String, String?>? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val hfResolve = Regex("""(?:huggingface\.co|hf-mirror\.com)/([^/]+/[^/]+)/resolve/[^/]+/(.+)""")
        hfResolve.find(trimmed)?.let { m ->
            return m.groupValues[1] to m.groupValues[2]
        }
        val plain = Regex("""^([\w.\-]+/[\w.\-]+)$""")
        plain.find(trimmed)?.let { return it.groupValues[1] to null }
        return null
    }
}
