package com.localmind.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.SherpaNativeLoader
import com.localmind.data.Storage
import com.localmind.data.VoiceEntry
import java.io.File

private const val TAG = "NeuralTts"

/**
 * 神经语音：把文本念成人声，而不是系统 TTS 那种机器腔。
 *
 * 这一层存在的理由是**系统 TTS 在中文上不够用**：国产 ROM 内置的多是离线拼接音，
 * 断句和语气都硬；而且系统音色跟机型绑定，换台机器同一个人设听起来就换了个人。
 * sherpa-onnx 的 VITS 中文音色是模型文件，跟机型无关，下载一次到处一样。
 *
 * ## 为什么是可选能力
 *
 * 原生库 36 MB/ABI（onnxruntime 占大头），只在跑过 `:app:fetchSherpaOnnx`
 * 之后才打进 APK。所以**库缺失、模型没下、模型坏了**这三种情况都必须能安静地
 * 退回系统 TTS —— 语音是给人设加分的，不该因为一个可选组件让聊天说不出话。
 * 所有公开方法因此都不抛异常，只返回 null / false。
 */
object NeuralVoiceModels {

    /**
     * 音色模型的根目录：`filesDir/voices/<voice.id>/`。
     *
     * 和 `modelsDir` 分开放：那里放的是 GGUF 大模型，模型页会遍历它；
     * 把几十个 onnx/txt/fst 混进去会让"已下载模型"列表变得没法看。
     */
    fun voiceRoot(storage: Storage): File = File(storage.appContext().filesDir, "voices")

    fun dirOf(storage: Storage, voice: VoiceEntry): File =
        File(voiceRoot(storage), voice.id)

    /** 压缩包落在这里，解完就删。 */
    fun archiveOf(storage: Storage, voice: VoiceEntry): File =
        File(voiceRoot(storage), "${voice.id}.tar.bz2")

    fun isInstalled(storage: Storage, voice: VoiceEntry): Boolean {
        // 插件里登记的每个音色都是真的神经音色，没有"系统语音"这一项。
        return resolve(dirOf(storage, voice)) != null
    }

    fun installedBytes(storage: Storage, voice: VoiceEntry): Long {
        val dir = dirOf(storage, voice)
        if (!dir.isDirectory) return 0
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * 在一个解压出来的目录里找出 VITS 模型的实际布局。
     *
     * **刻意不写死路径**：sherpa-onnx 的 tarball 顶层目录名、模型文件名在各音色
     * 之间并不统一（piper 系是 `zh_CN-xiao_ya-medium.onnx`，icefall 系是
     * `model.onnx`，hf 系又不一样）。写死等于每加一个音色都要先解包确认一遍。
     * 改成扫目录之后，命名惯例变了也只是扫得久一点，不会失效。
     *
     * @return null 表示这个目录里没有能用的模型（没下、下坏了、解压没完成）。
     */
    fun resolve(dir: File): Layout? {
        if (!dir.isDirectory) return null

        val files = dir.walkTopDown().filter { it.isFile }.toList()
        if (files.isEmpty()) return null

        // 主模型：优先 .onnx，VITS 的生成器就是这个文件。
        // 取最大的那个 —— 有些音色目录里还带 vocoder 或 duration 的小 onnx，
        // 按体积挑才不会抓错。
        val model = files
            .filter { it.extension.equals("onnx", ignoreCase = true) }
            .maxByOrNull { it.length() }
            ?: return null

        val base = model.parentFile ?: return null

        val tokens = pick(files, base, "tokens.txt")
        // lexicon 在各家叫法不同：piper 是 lexicon.txt，hf 系有的叫 lexicon.txt
        // 有的干脆没有（靠 tokens 直接映射）。找不到就是空，sherpa 能跑。
        val lexicon = pick(files, base, "lexicon.txt", "lexicon-zh.txt")

        // espeak-ng 的发音数据目录。**Piper 类模型（含 coqui/icefall）必需**：
        // sherpa 的 VITS 前端在「不是 characters 前端、又没有 lexicon」时会走
        // PiperPhonemizeLexicon，而那条路要 data_dir；给空就会走到最后的分支，
        // 打一句 "Not a model using characters as modeling unit" 然后
        // `SHERPA_ONNX_EXIT(-1)` —— 整个进程消失，Java 侧连异常都拿不到。
        // 这正是「点了一个 Piper 音色，应用直接退出（exit 255）」的根因。
        val espeakData = dir.walkTopDown()
            .firstOrNull { it.isDirectory && it.name.equals("espeak-ng-data", true) }

        // 中文 VITS 的文本正则化：日期 / 数字 / 电话。给中文句子加"2026年"这种
        // 数字时才用得上；缺了不影响正常朗读，所以是可选的。
        val rules = listOf("date.fst", "number.fst", "phone.fst")
            .mapNotNull { pick(files, base, it) }

        // ---- 架构判定：VITS / Matcha / Kokoro / Kitten ----
        //
        // 判据和 sherpa-onnx 自己的一致：VITS 读 ONNX metadata 的 model_type/comment。
        // Kokoro / Kitten 的生成器 onnx 同样带 model_type；Matcha 有声学模型 + 声码器
        // 两个 onnx。 voices.bin 是 Kokoro / Kitten 独有的声纹库，见到它基本就是这两家。
        // **必须查**：把 Matcha 的声学模型当成 VITS 生成器塞进 OfflineTtsVitsModelConfig，
        // 那一步不是抛异常 —— libsherpa-onnx-jni.so 会直接结束进程（实测 exit）。
        val voicesBin = pick(files, base, "voices.bin")
        val otherOnnx = files
            .filter { it.extension.equals("onnx", ignoreCase = true) && it != model }
            .maxByOrNull { it.length() }

        // ZipVoice2（零样本声音克隆）：encoder / decoder / vocoder 三个 onnx + tokens.txt。
        // 见到这三件套就基本是它了；model_type 元数据大多写 "zipvoice"。
        val encOnnx = pick(files, base, "encoder.onnx", "encoder_quant.onnx")
        val decOnnx = pick(files, base, "decoder.onnx", "decoder_quant.onnx")
        val vocOnnx = pick(files, base, "vocoder.onnx", "vocos.onnx", "vocoder_quant.onnx")

        
        //
        // 判据和 sherpa-onnx 自己的一致：它的 VITS 实现读 ONNX metadata 里的
        // `comment`，读不到就报错退出。所以"我们拒绝"与"它会崩"永远是同一件事，
        // 不会分叉。**必须查**：语音插件里同时有 Matcha / Kokoro 这些别的架构，
        // 而它们同样只有一两个 .onnx —— 只按体积挑，就会把 Matcha 的声学模型
        // 当成 VITS 的生成器塞进 OfflineTtsVitsModelConfig。
        //
        // 那一步不是抛异常：libsherpa-onnx-jni.so 会直接结束进程（实测 exit，
        // 连 tombstone 都没有）。Java 侧 runCatching 拦不住，用户看到的就是闪退。
        val modelType = onnxMeta(model, "model_type").orEmpty()
        val comment = onnxMeta(model, "comment").orEmpty()
        val arch: NeuralVoiceModels.Arch = when {
            modelType.equals("zipvoice", ignoreCase = true) ||
                (encOnnx != null && decOnnx != null && vocOnnx != null) -> NeuralVoiceModels.Arch.ZIPVOICE
            modelType.equals("kitten", ignoreCase = true) -> NeuralVoiceModels.Arch.KITTEN
            modelType.equals("kokoro", ignoreCase = true) || comment.contains("kokoro", ignoreCase = true) ->
                NeuralVoiceModels.Arch.KOKORO
            modelType.equals("matcha", ignoreCase = true) -> NeuralVoiceModels.Arch.MATCHA
            voicesBin != null && otherOnnx != null -> NeuralVoiceModels.Arch.MATCHA
            voicesBin != null -> NeuralVoiceModels.Arch.KOKORO
            modelType.equals("vits", ignoreCase = true) || comment.isNotBlank() -> NeuralVoiceModels.Arch.VITS
            else -> NeuralVoiceModels.Arch.UNKNOWN
        }
        val isVits = arch == NeuralVoiceModels.Arch.VITS

        // ---- 前端：这个模型怎么把文字变成音素 ----
        //
        // 判据来自 sherpa-onnx 自己的 offline-tts-vits-model.cc + vits-impl.h：
        //  * frontend=="characters" / jieba / has_g2pw / melo → 不需要 espeak；
        //  * comment 里有 piper/coqui/icefall/Inflect 且 data_dir 非空 → 用 espeak；
        //  * 其余情况只有 lexicon 一条路，**没有 lexicon 就 exit(-1)**。
        // 我们这边必须先算出来，因为那个 exit 发生在原生层，拦不住。
        val frontend = onnxMeta(model, "frontend").orEmpty()
        val jieba = onnxMeta(model, "jieba").orEmpty() == "1"
        val g2pw = onnxMeta(model, "has_g2pw").orEmpty() == "1"
        val piperLike = listOf("piper", "coqui", "icefall", "inflect")
            .any { comment.contains(it, ignoreCase = true) }
        val charFrontend = frontend.equals("characters", ignoreCase = true) ||
            jieba || g2pw || comment.contains("melo", ignoreCase = true)
        val canPhonemize = charFrontend || (piperLike && espeakData != null) || lexicon != null

        // ---- 各架构的可播放条件 ----
        //
        // 原则：宁可拒绝也不让原生库 exit() 带崩进程。sherpa 对"缺关键文件"的
        // 反应是直接结束进程而不是抛异常，Java 侧拦不住，所以这里必须替它把门。
        val blockedReason = when (arch) {
            NeuralVoiceModels.Arch.KOKORO -> when {
                tokens == null -> "Kokoro 音色缺 tokens.txt，无法使用"
                // multi-lang Kokoro 靠 espeak-ng-data 做音素转换；英文版可以没有。
                espeakData == null && model.name.contains("multi-lang") ->
                    "多语言 Kokoro 缺 espeak-ng-data，原生引擎加载它会直接结束进程，已拦下"
                else -> ""
            }
            NeuralVoiceModels.Arch.KITTEN -> when {
                tokens == null -> "Kitten TTS 音色缺 tokens.txt，无法使用"
                espeakData == null -> "Kitten TTS 缺 espeak-ng-data，原生引擎加载它会直接结束进程，已拦下"
                else -> ""
            }
            NeuralVoiceModels.Arch.MATCHA -> when {
                tokens == null -> "Matcha 音色缺 tokens.txt，无法使用"
                otherOnnx == null -> "Matcha 音色缺声码器 onnx，无法使用"
                espeakData == null && lexicon == null -> "Matcha 缺 espeak-ng-data 且没有 lexicon，已拦下"
                else -> ""
            }
            NeuralVoiceModels.Arch.VITS -> when {
                !canPhonemize -> "这个音色缺少发音数据（espeak-ng-data 或 lexicon.txt）；" +
                    "原生引擎加载它会直接结束进程，已拦下"
                else -> ""
            }
            NeuralVoiceModels.Arch.ZIPVOICE -> when {
                tokens == null -> "ZipVoice 音色缺 tokens.txt，无法使用"
                encOnnx == null -> "ZipVoice 音色缺 encoder.onnx，无法使用"
                decOnnx == null -> "ZipVoice 音色缺 decoder.onnx，无法使用"
                vocOnnx == null -> "ZipVoice 音色缺 vocoder.onnx，无法使用"
                // 多语言（中英）ZipVoice 靠 espeak-ng-data 做音素转换；缺了加载即崩进程。
                espeakData == null -> "ZipVoice 缺 espeak-ng-data，原生引擎加载它会直接结束进程，已拦下"
                else -> ""
            }
            NeuralVoiceModels.Arch.UNKNOWN -> "这是 " + modelType.ifBlank { comment }.ifBlank { "未知架构" } +
                " 架构的音色，主程序不认识它，装上也发不出声"
        }

        return Layout(
            modelDir = base,
            model = model.name,
            encoder = encOnnx?.name.orEmpty(),
            decoder = decOnnx?.name.orEmpty(),
            tokens = tokens?.name.orEmpty(),
            lexicon = lexicon?.name.orEmpty(),
            dataDir = espeakData?.absolutePath.orEmpty(),
            ruleFsts = rules.joinToString(",") { it.absolutePath },
            sizeBytes = files.sumOf { it.length() },
            arch = arch,
            voices = voicesBin?.name.orEmpty(),
            vocoder = when (arch) {
                NeuralVoiceModels.Arch.MATCHA -> otherOnnx?.name.orEmpty()
                NeuralVoiceModels.Arch.ZIPVOICE -> vocOnnx?.name.orEmpty()
                else -> ""
            },
            modelType = arch.label,
            frontend = frontend,
            playable = blockedReason.isEmpty(),
            blockedReason = blockedReason,
        )
    }

    /**
     * 读 ONNX 尾部 metadata 里某个键的值（读不到返回 null）。
     *
     * ## 为什么自己解析
     *
     * 要判断"这个 .onnx 是不是 VITS"，唯一可靠的依据是它自己的 metadata ——
     * 文件名和目录布局在各家导出脚本之间完全不统一（piper 叫
     * zh_CN-xiao_ya-medium.onnx，icefall 叫 model-steps-3.onnx，hf 系又叫别的）。
     *
     * 不引 onnxruntime / protobuf 依赖：metadata_props 是 ModelProto 的最后一个
     * 字段，导出器都写在文件末尾。所以只读尾部 64 KB，再按 protobuf 的 map
     * 条目结构（0x0A 键长 键 0x12 值长 值）找一个键就够了 —— 十几行，
     * 而且不引入任何新依赖。
     */
    fun onnxMeta(model: File, key: String): String? = runCatching {
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        if (keyBytes.isEmpty() || keyBytes.size > 60) return@runCatching null
        val tail = 64 * 1024
        val len = model.length()
        val start = if (len > tail) len - tail else 0L
        val size = (len - start).toInt()
        if (size <= 0) return@runCatching null
        val buf = ByteArray(size)
        java.io.RandomAccessFile(model, "r").use { raf ->
            raf.seek(start)
            raf.readFully(buf)
        }
        for (i in 0..buf.size - keyBytes.size - 3) {
            // 0x0A = map 条目的 key 字段（LEN），后面跟键长与键本身
            if (buf[i] != 0x0A.toByte()) continue
            if ((buf[i + 1].toInt() and 0xFF) != keyBytes.size) continue
            var hit = true
            for (k in keyBytes.indices) {
                if (buf[i + 2 + k] != keyBytes[k]) { hit = false; break }
            }
            if (!hit) continue
            // 0x12 = map 条目的 value 字段（LEN）
            val vTag = i + 2 + keyBytes.size
            if (vTag + 1 >= buf.size || buf[vTag] != 0x12.toByte()) continue
            val vLen = buf[vTag + 1].toInt() and 0xFF
            if (vLen <= 0 || vTag + 2 + vLen > buf.size) continue
            return@runCatching String(buf, vTag + 2, vLen, Charsets.UTF_8)
        }
        null
    }.getOrNull()

    /** 先在同级目录找，再退到整个子树找。 */
    private fun pick(files: List<File>, base: File, vararg names: String): File? {
        for (n in names) {
            File(base, n).takeIf { it.isFile }?.let { return it }
        }
        return files.firstOrNull { f -> names.any { it.equals(f.name, ignoreCase = true) } }
    }

    /**
     * 解析结果。字段存的是**文件名**而不是绝对路径 —— 绝对路径在应用被移到
     * 别的用户/设备后会失效，而 sherpa 的 config 要的是绝对路径，所以在
     * [NeuralTtsEngine.load] 里现拼。
     */
    /** TTS 架构。决定 load() 构建哪种 OfflineTtsXxxModelConfig。 */
    enum class Arch(val label: String) {
        VITS("VITS"),
        MATCHA("Matcha"),
        KOKORO("Kokoro"),
        KITTEN("Kitten TTS"),
        /** 零样本声音克隆（上传参考音频即可复刻音色）。sherpa-onnx 的 ZipVoice2。 */
        ZIPVOICE("ZipVoice 克隆"),
        UNKNOWN("未知"),
    }

    data class Layout(
        val modelDir: File,
        val model: String,
        val encoder: String = "",
        val decoder: String = "",
        val tokens: String,
        val lexicon: String,
        /** espeak-ng-data 的绝对路径（Piper / Coqui / icefall / Kokoro multi-lang 必需）。 */
        val dataDir: String = "",
        val ruleFsts: String,
        val sizeBytes: Long,
        /** 模型架构（由 resolve 根据 onnx metadata / 目录布局判定）。 */
        val arch: Arch = Arch.UNKNOWN,
        /** Kokoro / Kitten 的 voices.bin 文件名。 */
        val voices: String = "",
        /** Matcha 的声码器 onnx 文件名。 */
        val vocoder: String = "",
        /** ONNX metadata 里的 model_type（或 comment）—— 就是"这是什么架构"。 */
        val modelType: String = "",
        /** ONNX metadata 的 frontend（characters / 空）。诊断用。 */
        val frontend: String = "",
        /**
         * 能不能用主程序**现有的** VITS 路径念出来。
         *
         * false = 文件在、也算"已安装"，但装上也发不出声（Matcha / Kokoro…），
         * 或者会**直接把进程带走**（Piper 缺 espeak-ng-data、非 characters 且无 lexicon）。
         * 这两种状态必须分开：用户要能删掉它，但不该被允许"使用"它 ——
         * 允许的代价是整个进程消失（见 [onnxMeta] 与 [resolve] 的说明）。
         */
        val playable: Boolean = true,
        /** playable=false 的具体原因（给用户看）。 */
        val blockedReason: String = "",
    ) {
        /** 给用户看的、为什么用不了；能用时是 null。 */
        val unsupportedReason: String?
            get() = if (playable) null else blockedReason.ifBlank {
                "这是 $modelType 架构的音色，主程序目前只接了 VITS 引擎，装上也发不出声"
            }
    }
}

/**
 * sherpa-onnx 的多架构离线 TTS 推理 + 播放（VITS / Matcha / Kokoro / Kitten）。
 *
 * 生命周期：模型常驻（一个音色几百 MB，反复读盘太亏），同一时刻只保留一个。
 * 换音色会先 [release] 再加载。
 */
class NeuralTtsEngine(
    private val context: Context,
    private val installer: SherpaLibraryInstaller,
) {

    init {
        // 启动时如果磁盘上已经有原生库，就**立刻 load 进来**。
        //
        // 不这么做会有一个很别扭的中间态：文件在磁盘上、`isInstalled` 为真，
        // 但 `System.load` 还没跑过，于是 `OfflineTts` 一构造就
        // `UnsatisfiedLinkError`。"文件在"和"能用"是两件事，启动时把它对齐，
        // 后面所有地方都只需要判断一个布尔值。
        //
        // 这只是 mmap 四个 .so，没有模型加载那么重，放在 init 里可以接受。
        if (installer.isInstalled && !SherpaNativeLoader.isLoaded) {
            runCatching { installer.ensureInstalled() }
        }
    }

    @Volatile private var tts: OfflineTts? = null

    /**
     * 置 1 会让进行中的合成在下一批 sample 时中断（回调返回 0，sherpa 停止生成）。
     * stop() 置位，speak() 开头清零。
     */
    @Volatile private var stoppedGeneration = false
    @Volatile private var loadedId: String = ""
    @Volatile private var playing: AudioTrack? = null

    /**
     * 原生库是否可用（已落盘并加载，或 APK 里自带）。
     *
     * 这里用 [SherpaNativeLoader.isLoaded] 而不是"文件在不在"：文件在磁盘上但
     * 没 `System.load` 进来时，`OfflineTts` 一构造就会 `UnsatisfiedLinkError`。
     * 所以"就绪"必须等价于"已经加载",否则界面会显示可用、一点就崩。
     */
    val available: Boolean get() = SherpaNativeLoader.isLoaded || SherpaNativeLoader.isBundled

    /** APK 里自带原生库（旧版本 / 本地开发构建）。 */
    val isBundled: Boolean get() = SherpaNativeLoader.isBundled

    /** 磁盘上有全套 `.so` 但还没加载（需要 [ensureLibrary] 把它们 load 进来）。 */
    val libraryOnDisk: Boolean get() = installer.isInstalled

    /** 就绪：已加载或自带。UI 的"能不能用"应该问这个。 */
    val libraryInstalled: Boolean get() = available

    /** 原生库对应的 ABI。 */
    val libraryAbi: String get() = installer.abi

    /** 原生库占用的磁盘空间。 */
    val libraryBytes: Long get() = installer.installedBytes()

    fun uninstallLibrary() {
        release()
        installer.uninstall()
    }

    /**
     * 只装原生库（不加载任何音色）。给"先下语音引擎，稍后再选音色"用。
     * 阻塞，要在 IO 调度器上跑。
     */
    fun ensureLibrary(): Boolean = installer.ensureInstalled()

    val loadedVoiceId: String get() = loadedId

    /**
     * 加载（或复用）某个音色。@return 加载成功、可以合成的音色 id；失败 null。
     */
    fun load(storage: Storage, voice: VoiceEntry): String? {
        
        if (loadedId == voice.id && tts != null) return loadedId

        // 原生库按需下载。这一步会阻塞（可能几十 MB），所以放在这里而不是
        // 应用启动时 —— 没打算用神经音色的人不该为它付流量。
        if (!installer.ensureInstalled()) {
            Log.i(TAG, "原生库不可用，神经音色放弃（调用方应退回系统 TTS）")
            return null
        }

        val layout = NeuralVoiceModels.resolve(NeuralVoiceModels.dirOf(storage, voice))
        if (layout == null) {
            Log.i(TAG, "${voice.id} 没有可用的模型文件")
            return null
        }

        // 架构不对就**绝不能**往下走。
        //
        // 构造 OfflineTts 时 sherpa 会按 VITS 去解析这个 onnx；对 Matcha / Kokoro
        // 这类模型它读不到需要的 metadata，然后**直接结束进程**（不是抛异常）。
        // 所以这道闸门是"能不能用"的唯一入口，放在原生调用之前，不是事后补救。
        if (!layout.playable) {
            Log.w(TAG, "${voice.id} 不是 VITS（${layout.modelType}），拒绝加载")
            return null
        }

        // 上次加载这个音色时进程被带走过，就不再自动重试（见 Storage 的看门狗）。
        if (voice.id in storage.neuralBadVoices()) {
            Log.w(TAG, "${voice.id} 在黑名单里（上次加载导致进程终止），跳过")
            return null
        }

        release()

        // 看门狗：原生库出问题时是这个进程直接消失，Java 侧一行代码都不会执行。
        // 所以标记必须写在构造**之前**，并在正常返回后清掉 —— 标记还在，就说明
        // 上一次是死在这里的，下次启动据此把音色拉黑，应用才不至于变成砖。
        storage.setNeuralLoadMarker(voice.id)
        return try {
            runCatching {
                val dir = layout.modelDir.absolutePath
                // 按架构构建对应的模型 config。缺关键文件的情况已在 resolve 里拦下，
                // 走到这里的路径都是"原生库能正常解析"的。
                val abs = { name: String -> if (name.isEmpty()) "" else "$dir/$name" }
                val modelCfg = OfflineTtsModelConfig(
                    numThreads = if (Runtime.getRuntime().availableProcessors() >= 8) 4 else 2,
                    debug = false,
                    provider = "cpu",
                )
                when (layout.arch) {
                    NeuralVoiceModels.Arch.KOKORO -> modelCfg.kokoro = OfflineTtsKokoroModelConfig(
                        model = abs(layout.model),
                        voices = abs(layout.voices),
                        tokens = abs(layout.tokens),
                        // multi-lang Kokoro 靠 espeak 做音素转换，不给 data_dir 会 exit(-1)。
                        dataDir = layout.dataDir,
                        lexicon = abs(layout.lexicon),
                    )
                    NeuralVoiceModels.Arch.KITTEN -> modelCfg.kitten = OfflineTtsKittenModelConfig(
                        model = abs(layout.model),
                        voices = abs(layout.voices),
                        tokens = abs(layout.tokens),
                        dataDir = layout.dataDir,
                    )
                    NeuralVoiceModels.Arch.MATCHA -> modelCfg.matcha = OfflineTtsMatchaModelConfig(
                        acousticModel = abs(layout.model),
                        vocoder = abs(layout.vocoder),
                        tokens = abs(layout.tokens),
                        lexicon = abs(layout.lexicon),
                        dataDir = layout.dataDir,
                    )
                    // ZipVoice2（零样本声音克隆）：用参考音频复刻音色，不走 sid。
                    // encoder / decoder / vocoder 三个 onnx + tokens 是它独有的。
                    NeuralVoiceModels.Arch.ZIPVOICE -> modelCfg.zipvoice = OfflineTtsZipVoiceModelConfig(
                        tokens = abs(layout.tokens),
                        encoder = abs(layout.encoder),
                        decoder = abs(layout.decoder),
                        vocoder = abs(layout.vocoder),
                        dataDir = layout.dataDir,
                        lexicon = abs(layout.lexicon),
                    )
                    // VITS 与 UNKNOWN 都走 VITS config：UNKNOWN 的 playable 必为 false，
                    // 到不了这里；VITS 保持原有行为。
                    else -> modelCfg.vits = OfflineTtsVitsModelConfig(
                        model = abs(layout.model),
                        lexicon = abs(layout.lexicon),
                        tokens = abs(layout.tokens),
                        // Piper / Coqui / icefall 靠 espeak 做音素转换；这条路径
                        // 不给 dataDir 就会被原生库 exit(-1) 掉（见 resolve 的注释）。
                        dataDir = layout.dataDir,
                    )
                }
                val config = OfflineTtsConfig(
                    model = modelCfg,
                    ruleFsts = layout.ruleFsts,
                    maxNumSentences = 1,
                    silenceScale = 0.2f,
                )
                val engine = OfflineTts(assetManager = null, config = config)
                tts = engine
                loadedId = voice.id
                Log.i(TAG, "音色就绪：${voice.id}  ${engine.numSpeakers()} 个说话人 @ ${engine.sampleRate()} Hz")
                loadedId
            }.getOrElse { t ->
                // 加载失败的原因很多（ABI 不匹配、onnx 版本、内存不够），
                // 但都不该让调用方崩 —— 它只需要知道自己该退回系统 TTS。
                Log.w(TAG, "加载 ${voice.id} 失败：${t.message}", t)
                tts = null
                loadedId = ""
                null
            }
        } finally {
            // 走到这里说明原生调用正常返回了（成功或失败），进程没死 —— 撤掉标记。
            storage.setNeuralLoadMarker(null)
        }
    }

    /**
     * 启动时检查看门狗：标记还在 = 上一次加载把进程带走了。
     *
     * 把那个音色拉黑并返回它的 id，调用方据此清掉用户的选择并如实提示。
     * 这是"闪退之后再也打不开"的**唯一**补救点 —— 那种崩溃在 Java 侧没有任何
     * 回调可用，只有磁盘上这个标记能留下证据。
     */
    fun recoverFromPreviousCrash(storage: Storage): String? {
        val marker = storage.neuralLoadMarker()
        if (marker.isBlank()) return null
        storage.setNeuralLoadMarker(null)
        storage.markNeuralVoiceBad(marker)
        Log.w(TAG, "上次加载 $marker 时进程被终止（原生库直接退出），已拉黑")
        return marker
    }

    fun release() {
        runCatching { tts?.free() }
        tts = null
        loadedId = ""
    }

    fun stop() {
        stoppedGeneration = true
        runCatching { playing?.pause(); playing?.flush() }
    }

    /**
     * 合成并播放。[text] 应该是已经清洗过的纯文本。
     *
     * @param speed 语速倍率（已经乘过情绪系数）。
     * @param pitch 音高倍率。**用重采样实现** —— VITS 本身没有变调输入，
     *   所以这里把采样率按 `sampleRate / pitch` 播放：pitch > 1 就是升调且变快，
     *   升调后再把播放速率补回去，听感上接近"音色不变、音高变"。
     * @return 是否真的开始播放。
     */
    fun speak(text: String, speed: Float = 1.0f, pitch: Float = 1.0f, volume: Float = 1.0f): Boolean {
        val engine = tts ?: return false
        if (text.isBlank()) return false
        stoppedGeneration = false

        return runCatching {
            // 回调返回 0 会让 sherpa 中断本次合成 —— stop() 一按，正在跑的
            // 合成下一批 sample 就停，不用等整段念完。
            val callback = NativeTtsCallback { if (stoppedGeneration) 0 else 1 }
            val audio = engine.generateWithCallback(
                text, sid = 0, speed = speed.coerceIn(0.3f, 2.5f), callback = callback,
            )
            if (audio.samples.isEmpty()) return false

            val shifted = applyPitch(audio.samples, audio.sampleRate, pitch)
            play(shifted.first, shifted.second, volume)
            true
        }.getOrElse { t ->
            Log.w(TAG, "合成失败：${t.message}", t)
            false
        }
    }

    /** 合成到 WAV 文件（`/v1/audio/speech` 用）。 */
    fun synthesizeToWav(text: String, speed: Float, pitch: Float, target: File): Boolean {
        val engine = tts ?: return false
        if (text.isBlank()) return false
        return runCatching {
            val audio = engine.generateWithCallback(
                text, sid = 0, speed = speed.coerceIn(0.3f, 2.5f),
                callback = NativeTtsCallback { 1 },
            )
            if (audio.samples.isEmpty()) return false
            val (samples, rate) = applyPitch(audio.samples, audio.sampleRate, pitch)
            target.writeBytes(wav(samples, rate))
            true
        }.getOrElse { t ->
            Log.w(TAG, "导出 WAV 失败：${t.message}", t)
            false
        }
    }

    /**
     * 零样本声音克隆：合成并播放。
     *
     * @param referenceAudio 参考音频的 PCM 浮点采样（已重采样到 [referenceSampleRate]）。
     * @param referenceSampleRate 参考音频采样率（ZipVoice2 约定 24000 Hz）。
     * @param referenceText 参考音频里念的文字；越准克隆越像。
     */
    fun speakClone(
        text: String,
        referenceAudio: FloatArray,
        referenceSampleRate: Int,
        referenceText: String?,
        speed: Float = 1.0f,
        pitch: Float = 1.0f,
        volume: Float = 1.0f,
        silenceScale: Float = 0.2f,
        numSteps: Int = 5,
    ): Boolean {
        val engine = tts ?: return false
        if (text.isBlank() || referenceAudio.isEmpty()) return false
        stoppedGeneration = false
        return runCatching {
            val callback = NativeTtsCallback { if (stoppedGeneration) 0 else 1 }
            val cfg = GenerationConfig(
                silenceScale = silenceScale.coerceIn(0f, 1f),
                speed = speed.coerceIn(0.3f, 2.5f),
                sid = 0,
                referenceAudio = referenceAudio,
                referenceSampleRate = referenceSampleRate,
                referenceText = referenceText?.takeIf { it.isNotBlank() },
                numSteps = numSteps.coerceIn(1, 32),
            )
            val audio = engine.generateWithConfig(text, cfg)
            if (audio.samples.isEmpty()) return false
            val shifted = applyPitch(audio.samples, audio.sampleRate, pitch)
            play(shifted.first, shifted.second, volume)
            true
        }.getOrElse { t ->
            Log.w(TAG, "克隆合成失败：${t.message}", t)
            false
        }
    }

    /** 克隆合成到 WAV 文件。 */
    fun synthesizeCloneToWav(
        text: String,
        referenceAudio: FloatArray,
        referenceSampleRate: Int,
        referenceText: String?,
        speed: Float,
        pitch: Float,
        target: File,
        silenceScale: Float = 0.2f,
        numSteps: Int = 5,
    ): Boolean {
        val engine = tts ?: return false
        if (text.isBlank() || referenceAudio.isEmpty()) return false
        return runCatching {
            val cfg = GenerationConfig(
                silenceScale = silenceScale.coerceIn(0f, 1f),
                speed = speed.coerceIn(0.3f, 2.5f),
                sid = 0,
                referenceAudio = referenceAudio,
                referenceSampleRate = referenceSampleRate,
                referenceText = referenceText?.takeIf { it.isNotBlank() },
                numSteps = numSteps.coerceIn(1, 32),
            )
            val audio = engine.generateWithConfig(text, cfg)
            if (audio.samples.isEmpty()) return false
            val (samples, rate) = applyPitch(audio.samples, audio.sampleRate, pitch)
            target.writeBytes(wav(samples, rate))
            true
        }.getOrElse { t ->
            Log.w(TAG, "克隆导出 WAV 失败：${t.message}", t)
            false
        }
    }

    /**
     * 变调：把采样率按 1/pitch 缩放写进 WAV 头，播放端就按新速率解释同一批采样。
     *
     * 这是"变速=变调"的老办法。真正保持时长不变的变调要做相位声码器，
     * 在手机上为了一句 TTS 不值当 —— 而且情绪本来就要连语速一起改。
     *
     * pitch 为 1（或非有限值）时原样返回，不引入无谓的重采样。
     */
    private fun applyPitch(samples: FloatArray, sampleRate: Int, pitch: Float): Pair<FloatArray, Int> {
        if (!pitch.isFinite() || pitch <= 0f || kotlin.math.abs(pitch - 1f) < 0.01f) {
            return samples to sampleRate
        }
        val rate = (sampleRate / pitch.coerceIn(0.3f, 2.2f)).toInt().coerceIn(8000, 96_000)
        return samples to rate
    }

    private fun play(samples: FloatArray, sampleRate: Int, volume: Float) {
        stop()
        runCatching { playing?.release() }

        val pcm = ShortArray(samples.size) { i ->
            (samples[i].coerceIn(-1f, 1f) * volume.coerceIn(0f, 1f) * Short.MAX_VALUE * 0.92f).toInt().toShort()
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        track.write(pcm, 0, pcm.size)
        track.play()
        playing = track
    }

    fun isSpeaking(): Boolean = runCatching { playing?.playState == AudioTrack.PLAYSTATE_PLAYING }
        .getOrDefault(false)

    /** 16-bit 单声道 WAV。和 `ProceduralVoice.wavBytes` 的头部写法保持一致。 */
    private fun wav(samples: FloatArray, sampleRate: Int): ByteArray {
        val dataSize = samples.size * 2
        val out = ByteArray(44 + dataSize)
        val h = java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray(Charsets.US_ASCII)); h.putInt(36 + dataSize)
        h.put("WAVE".toByteArray(Charsets.US_ASCII))
        h.put("fmt ".toByteArray(Charsets.US_ASCII)); h.putInt(16)
        h.putShort(1); h.putShort(1); h.putInt(sampleRate)
        h.putInt(sampleRate * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray(Charsets.US_ASCII)); h.putInt(dataSize)
        for (s in samples) {
            h.putShort((s.coerceIn(-1f, 1f) * Short.MAX_VALUE * 0.92f).toInt().toShort())
        }
        return out
    }
}

/**
 * sherpa-onnx 的 JNI 按**精确方法签名** `invoke([F)Ljava/lang/Integer;` 在回调对象上
 * 找回调方法。Kotlin lambda 经 invokedynamic + D8 desugar 后只暴露擦除后的
 * `invoke(Object)`，签名对不上 → NoSuchMethodError，且发生在 JNI 调用点，
 * 可能直接带崩进程。命名类保留了带类型的 `invoke(FloatArray)` 桥接方法，
 * 签名匹配，JNI 找得到。
 *
 * 这不是理论风险：VoiceStudio 项目（同为 sherpa-onnx Android 端）在实战中踩过
 * 这个坑并留下了同样的规避类。凡是要传给 sherpa 的 native 回调，都必须经这种
 * 命名类包装。
 */
private class NativeTtsCallback(val onChunk: () -> Int) : (FloatArray) -> Int {
    override fun invoke(samples: FloatArray): Int = onChunk()
}
