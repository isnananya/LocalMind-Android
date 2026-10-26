package com.localmind.data

import android.content.Context
import android.os.Build
import android.os.StatFs
import com.localmind.util.Formatters
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Reads GGUF metadata without loading the model, then decides how much context
 * and how many threads this device can support for that specific model.
 *
 * This is LocalMind's take on AirLLM's central idea — *decide what can be
 * resident before you allocate* — adapted to Android, where the equivalent of
 * AirLLM's "stream layers from disk" is the kernel's page cache driven by
 * `mmap` + no-mlock (see docs/AIRLLM.md).
 */
object MemoryPlanner {

    private const val GGUF_MAGIC = 0x46554747 // "GGUF" little-endian
    private const val MAX_KV_PREVIEW = 24

    /**
     * 低端模式的封顶策略（规则见 [lowEndPolicy]）。
     *
     * [note] 是**给用户看的一行字**，会原样出现在设置页 / 状态页：
     * "低端模式 · 2.4 GB 内存 · ctx 1024 · KV q8"。
     * 夹了却不说，用户只会觉得"这个模型怎么这么蠢"（其实是他设定的上下文
     * 被砍了一半），所以这句话必须能被看到。
     */
    data class LowEndPolicy(
        val contextCap: Int,
        val batchCap: Int,
        val kvQuant: String,
        val totalRamGb: Double,
        val note: String,
    )

    data class GgufInfo(
        val version: Int,
        val tensorCount: Long,
        val kvCount: Long,
        val architecture: String?,
        val name: String?,
        val contextLength: Int?,
        val blockCount: Int?,
        val embeddingLength: Int?,
        val headCount: Int?,
        val quantLabel: String?,
        val fileType: Int?,
    )

    /** Minimal GGUF header + metadata walk. Returns null when the file is not GGUF. */
    fun readGguf(file: File): GgufInfo? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(512 * 1024)
            val read = raf.read(header)
            if (read < 24) return null
            val buf = ByteBuffer.wrap(header, 0, read).order(ByteOrder.LITTLE_ENDIAN)
            val magic = buf.int
            if (magic != GGUF_MAGIC) return null
            val version = buf.int
            val tensorCount = buf.long
            val kvCount = buf.long
            var arch: String? = null
            var name: String? = null
            var ctx: Int? = null
            var blocks: Int? = null
            var embd: Int? = null
            var heads: Int? = null
            var ftype: Int? = null

            // Walk as many KV pairs as fit in the preview window. A pair we cannot
            // walk (non-ASCII key, or a value type we do not understand) still gets
            // recorded when it is one of the keys we care about, and otherwise just
            // ends the scan — we would rather return a partial result than "not GGUF".
            val limit = (kvCount.coerceAtMost(8192)).toInt()
            for (i in 0 until limit) {
                // ⚠️ 每一步都要自己检查剩余字节。少一处检查，就会把**完好的文件判成无效**。
                //
                // 原来只在循环开头查了一次 remaining() < 12，于是实际走的路径是：
                // KV 段里有一个几百 KB 的 tokenizer 词表数组（每个真实 GGUF 都有），
                // 512 KB 的预览窗口一定走不完它；走到窗口边缘时，skipValue 里那次
                // 没有保护的 buf.long 抛 BufferUnderflowException，被最外层的
                // runCatching 吞掉，readGguf 于是返回 null。
                //
                // 后果有两个，而且**都是静默的**：
                //  * 界面显示「非 GGUF / 无法解析头部」，而文件其实完好 ——
                //    llama.cpp 能正常加载它（实测 qwen2.5-0.5b 在 pos=524287 处溢出）；
                //  * 内存规划器拿不到层数 / 上下文 / 几何，KV 估算退回 64 KB/token 的兜底，
                //    于是「建议上下文」与真实模型无关。
                //
                // 下面这些检查保证的是本文件上面写下的那句话：**宁可返回部分结果，
                // 也不要因为窗口走不完就返回 null**。
                if (buf.remaining() < 12) break
                // 一次 KV 的解析整体包一层。
                //
                // 上面那些 remaining 检查覆盖了已知的越界点；这一层保证的是
                // **未知的越界点也不会把结果变成 null** —— 外层那个 runCatching
                // 一旦接住异常，readGguf 就返回 null，而正确语义是「读到哪算哪」。
                val more = runCatching {
                    val key = readString(buf) ?: return@runCatching false
                    if (buf.remaining() < 4) return@runCatching false
                    val type = buf.int
                    val known = when {
                        key == "general.architecture" && type == 8 -> { arch = readString(buf); true }
                        key == "general.name" && type == 8 -> { name = readString(buf); true }
                        key == "general.file_type" && type == 4 -> { ftype = buf.int; true }
                        key.endsWith(".context_length") && type == 4 -> { ctx = buf.int; true }
                        key.endsWith(".block_count") && type == 4 -> { blocks = buf.int; true }
                        key.endsWith(".embedding_length") && type == 4 -> { embd = buf.int; true }
                        key.endsWith(".attention.head_count") && type == 4 -> { heads = buf.int; true }
                        else -> false
                    }
                    if (known) true else skipValue(buf, type)
                }.getOrDefault(false)
                if (!more) break
            }

            GgufInfo(
                version = version,
                tensorCount = tensorCount,
                kvCount = kvCount,
                architecture = arch,
                name = name,
                contextLength = ctx,
                blockCount = blocks,
                embeddingLength = embd,
                headCount = heads,
                quantLabel = quantLabel(ftype),
                fileType = ftype,
            )
        }
    }.getOrNull()

    private fun readString(buf: ByteBuffer): String? {
        if (buf.remaining() < 8) return null
        val len = buf.long
        if (len < 0 || len > (1 shl 20) || buf.remaining() < len) return null
        val bytes = ByteArray(len.toInt())
        buf.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * GGUF value type ids:
     * 0 u8, 1 i8, 2 u16, 3 i16, 4 u32, 5 i32, 6 f32, 7 bool,
     * 8 string, 9 array, 10 u64, 11 i64, 12 f64
     *
     * @return false when the buffer does not hold the whole value, so the caller
     *         stops instead of reading garbage.
     */
    private fun skipValue(buf: ByteBuffer, type: Int): Boolean {
        when (type) {
            0, 1, 7 -> {                       // u8, i8, bool
                if (buf.remaining() < 1) return false
                buf.position(buf.position() + 1)
            }

            2, 3 -> {                          // u16, i16
                if (buf.remaining() < 2) return false
                buf.position(buf.position() + 2)
            }

            4, 5, 6 -> {                       // u32, i32, f32
                if (buf.remaining() < 4) return false
                buf.position(buf.position() + 4)
            }

            10, 11, 12 -> {                    // u64, i64, f64
                if (buf.remaining() < 8) return false
                buf.position(buf.position() + 8)
            }

            8 -> {                             // string
                // 长度域本身那 8 个字节也要先确认在窗口内。
                //
                // 少了这一行，走一个大数组（tokenizer 词表就是几万个字符串）时，
                // 一到窗口边缘就会抛 BufferUnderflowException —— 那正是
                // readGguf 把完好 GGUF 判成「非 GGUF」的直接原因。
                if (buf.remaining() < 8) return false
                val len = buf.long
                if (len < 0 || len > (1 shl 22) || buf.remaining() < len) return false
                buf.position(buf.position() + len.toInt())
            }

            9 -> {                             // array: elem_type, count, payload
                if (buf.remaining() < 12) return false
                val elemType = buf.int
                val count = buf.long
                if (count < 0 || count > (1 shl 24)) return false
                // Fixed-size element types can be skipped in one jump; strings and
                // nested arrays must be walked element by element.
                if (elemType != 8 && elemType != 9) {
                    val size = when (elemType) {
                        0, 1, 7 -> 1
                        2, 3 -> 2
                        4, 5, 6 -> 4
                        10, 11, 12 -> 8
                        else -> return false
                    }
                    val total = count * size
                    if (total < 0 || buf.remaining() < total) return false
                    buf.position(buf.position() + total.toInt())
                } else {
                    for (k in 0 until count) {
                        if (!skipValue(buf, elemType)) return false
                    }
                }
            }

            else -> return false
        }
        return true
    }

    private fun quantLabel(fileType: Int?): String? = when (fileType) {
        0 -> "F32"; 1 -> "F16"; 2 -> "Q4_0"; 3 -> "Q4_1"; 7 -> "Q8_0"
        8 -> "Q5_0"; 9 -> "Q5_1"; 10 -> "Q2_K"; 11 -> "Q3_K_S"; 12 -> "Q3_K_M"
        13 -> "Q3_K_L"; 14 -> "Q4_K_S"; 15 -> "Q4_K_M"; 16 -> "Q5_K_S"
        17 -> "Q5_K_M"; 18 -> "Q6_K"; 19 -> "IQ2_XXS"; 20 -> "IQ2_XS"
        else -> null
    }

    // ---------------------------------------------------------------- device

    fun availableStorageBytes(dir: File): Long = runCatching {
        val stat = StatFs(dir.absolutePath)
        stat.availableBytes
    }.getOrDefault(0L)

    fun totalRamBytes(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo()
        am?.getMemoryInfo(info)
        return info.totalMem
    }

    fun availableRamBytes(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo()
        am?.getMemoryInfo(info)
        return info.availMem
    }

    fun cpuCores(): Int = Runtime.getRuntime().availableProcessors()

    /**
     * 低端模式：按**总内存**给上下文 / 批大小 / KV 精度封顶。
     *
     * ## 为什么需要它
     *
     * 手机上的默认值（ctx 4096 / batch 1024 / KV f16）是按"8 GB 起"定的。
     * 4 GB 甚至 2.5 GB 的机器上，光是 KV cache 就能把应用顶到 OOM，而 Android
     * 的 OOM killer 是**整进程**杀掉 —— 用户看到的是"加载模型时闪退"，
     * 不是"内存不够"的提示。夹一层之后同一台机器至少能跑起来，只是上下文短一些。
     *
     * ## 规则
     *
     * | 总内存 | ctx 上限 | batch 上限 | KV |
     * |---|---|---|---|
     * | < 4 GB | 1024 | 128 | q8_0 |
     * | < 6 GB | 2048 | 256 | q8_0 |
     * | < 8 GB | 4096 | 512 | q8_0 |
     * | ≥ 8 GB | 用户设置 | 用户设置 | 用户设置 |
     *
     * 6–8 GB 这一档是"均衡"：ctx 4096 本来就是默认值，等于不夹上下文，
     * 只把 batch 和 KV 收一收；< 6 GB 才是真正的低端模式。
     *
     * KV 用 q8_0 是**收益最大的一刀**：同样内存能装下两倍的上下文，精度损失
     * 在对话场景里听不出来。需要 flash attention —— 不支持时原生侧会自动退回
     * f16 并继续加载，所以这里不需要判断设备支不支持。
     *
     * @return null = 这台设备不需要夹（≥ 8 GB 或读不到内存信息）。
     */
    fun lowEndPolicy(context: Context): LowEndPolicy? {
        val total = totalRamBytes(context)
        if (total <= 0L) return null
        val gb = total.toDouble() / (1024.0 * 1024.0 * 1024.0)
        val ctx: Int
        val batch: Int
        when {
            gb < 4.0 -> { ctx = 1024; batch = 128 }
            gb < 6.0 -> { ctx = 2048; batch = 256 }
            gb < 8.0 -> { ctx = 4096; batch = 512 }
            else -> return null
        }
        val label = String.format(Locale.US, "%.1f GB", gb)
        val mode = if (gb < 6.0) "低端模式" else "均衡模式"
        return LowEndPolicy(
            contextCap = ctx,
            batchCap = batch,
            kvQuant = "q8_0",
            totalRamGb = gb,
            note = mode + " · " + label + " 内存 · ctx " + ctx + " · KV q8",
        )
    }

    /**
     * 大核数：从 `cpufreq/cpuinfo_max_freq` 里数**最高一档**有几个。
     *
     * 为什么不用 `availableProcessors()`：手机是大小核，8 个核里通常只有 4 个
     * 大核。llama.cpp 的矩阵乘是内存带宽瓶颈，摊到小核上不会更快，反而要等
     * 最慢的那个。厂商会写 1804800 和 1800000 这种近似值，所以按最高值的 95%
     * 判定同一档。
     *
     * 读不到（模拟器、部分 ROM 不给权限）时返回 0，由 [recommendedThreads]
     * 退回到保守近似。
     */
    fun bigCoreCount(): Int = bigCores

    private val bigCores: Int by lazy {
        val freqs = (0 until 16).mapNotNull { i ->
            runCatching {
                val f = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
                if (!f.isFile) null else f.readText().trim().toLongOrNull()
            }.getOrNull()
        }.filter { it > 0 }
        if (freqs.isEmpty()) return@lazy 0
        val top = freqs.max()
        freqs.count { it >= top * 0.95 }
    }

    /**
     * 自动线程数：优先大核数，夹在 2–6。
     *
     * 上限 6 而不是"全用"：超过 6 个线程后带宽抢得比算得快，实测曲线是平的
     * 甚至往下走（见 [plan] 里那段注释）。
     */
    fun recommendedThreads(): Int {
        val big = bigCoreCount()
        if (big < 2) return autoThreads()
        return big.coerceIn(2, 6)
    }

    /**
     * 自动线程数。**界面那行「自动（N）」必须调这个函数**，不要再自己算一份。
     *
     * 原来设置页写的是 `cores - 1`，而这里算的是 `cores <= 4 ? cores : 4`：
     * 4 核机器上界面说「自动（3）」而实际用 4，8 核机器上界面说「自动（7）」
     * 而实际用 4 —— 用户按界面显示的线程数去理解速度，结论必然是错的。
     */
    fun autoThreads(cores: Int = cpuCores()): Int =
        (if (cores <= 4) cores else 4).coerceIn(2, 8)

    /**
     * @param contextSizeHint user override; 0 means "decide for me".
     * @param kvBytesPerElement KV cache 每个元素的字节数（f16 = 2, q8_0 = 1, q4_0 ≈ 0.5）。
     *   量化 KV 的意义就在这里：**同样的内存能装下两倍的上下文**。
     *   它必须由调用方传进来 —— 否则规划器会按 f16 估算，
     *   而用户明明开了 q8_0，界面上的「建议上下文」就会偏保守一倍。
     */
    fun plan(
        context: Context,
        file: File,
        contextSizeHint: Int = 0,
        threadsHint: Int = 0,
        preferStreaming: Boolean = true,
        kvBytesPerElement: Int = 2,
        /**
         * 低端模式策略。默认在这一层自己算，这样**所有**调用方（设置页、模型页、
         * 引擎）看到的是同一份规则；引擎那边还要拿它决定真的传给 llama.cpp 的
         * KV 类型，所以算完再传进来，避免两处不一致。
         */
        policy: LowEndPolicy? = lowEndPolicy(context),
    ): ModelMemoryPlan {
        val fileBytes = file.length()
        val info = readGguf(file)
        val totalRam = totalRamBytes(context)
        val availRam = availableRamBytes(context)
        val warnings = mutableListOf<String>()

        // Reserve for the OS, the Compose UI, the API server and the KV cache.
        val reserve = 900L * 1024 * 1024
        val usable = (availRam - reserve).coerceAtLeast(128L * 1024 * 1024)

        val trainCtx = info?.contextLength ?: 4096
        val layers = info?.blockCount ?: 0

        // KV cache bytes for one token, approximated from the GGUF geometry.
        val embd = info?.embeddingLength ?: 0
        val heads = info?.headCount ?: 0
        // 低端模式强制 q8_0：调用方可能还按用户设置传了 2（f16），
        // 那样估出来的上下文会保守一倍，和最终真的跑的数字对不上。
        val kvElem = (if (policy != null) 1 else kvBytesPerElement).coerceIn(1, 4).toLong()
        val kvPerToken = if (embd > 0 && heads > 0 && layers > 0) {
            // 2 (K 和 V) * 层数 * embd * 每个元素的字节数
            (2L * layers * embd * kvElem).coerceAtLeast(1024L)
        } else {
            64L * 1024L // conservative fallback ~64 KB/token
        }

        val fitsInRam = fileBytes + reserve < totalRam && fileBytes < usable
        val needsStreaming = !fitsInRam && preferStreaming
        if (!fitsInRam && !preferStreaming) {
            warnings += "模型大于可用内存且已关闭 mmap 流式加载，加载可能失败"
        }
        if (fileBytes > availRam) {
            warnings += "模型 ${Formatters.size(fileBytes)} 超过当前可用内存 ${Formatters.size(availRam)}，" +
                if (preferStreaming) "将依赖磁盘流式加载（首个 token 会更慢）" else "建议开启 mmap 流式加载"
        }

        // Context budget: how many tokens of KV cache still fit next to the weights.
        val weightsResident = if (preferStreaming) minOf(fileBytes, usable / 2) else fileBytes
        val kvBudget = (usable - weightsResident).coerceAtLeast(16L * 1024 * 1024)
        val byMemory = (kvBudget / kvPerToken).toInt().coerceAtLeast(256)

        val userHint = if (contextSizeHint > 0) contextSizeHint else Int.MAX_VALUE
        val byBudget = minOf(userHint, byMemory, trainCtx).coerceAtLeast(512)
        if (contextSizeHint > byMemory && contextSizeHint > 0) {
            warnings += "设定的上下文 $contextSizeHint 超出内存预算，已下调为 $byBudget"
        }
        // 内存预算之外再加一道"设备档位"的硬顶。两者取小：
        // 预算宽裕但机器只有 4 GB 时，短上下文换来的是**不闪退**。
        val recommended = if (policy != null) {
            minOf(byBudget, policy.contextCap).coerceAtLeast(512)
        } else {
            byBudget
        }
        if (policy != null && recommended < byBudget) {
            warnings += policy.note + "：上下文按内存档位下调到 $recommended"
        }

        val threads = if (threadsHint > 0) {
            threadsHint
        } else {
            // 自动选线程数：优先"数得出来的大核"，读不到时退回保守近似。
            // 细节见 [recommendedThreads] / [bigCoreCount]。
            // 用户可以在设置里写死（`threadsHint`），也可以在模型页看到实际值。
            recommendedThreads()
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            warnings += "Android 8.0 以下未验证"
        }

        return ModelMemoryPlan(
            fileBytes = fileBytes,
            availableBytes = availableStorageBytes(file.parentFile ?: file),
            freeRamBytes = availRam,
            recommendedContext = recommended,
            recommendedThreads = threads,
            fitsInRam = fitsInRam,
            needsStreaming = needsStreaming,
            warnings = warnings,
            layers = layers,
            // 0 = 不夹，用用户设置的 batch。
            recommendedBatch = policy?.batchCap ?: 0,
            lowEndNote = policy?.note.orEmpty(),
        )
    }

    fun preview(info: GgufInfo?): String {
        if (info == null) return "非 GGUF / 无法解析头部"
        val parts = mutableListOf<String>()
        info.architecture?.let { parts += it }
        info.blockCount?.let { parts += "$it 层" }
        info.embeddingLength?.let { parts += "d=$it" }
        info.contextLength?.let { parts += "ctx $it" }
        info.quantLabel?.let { parts += it }
        return if (parts.isEmpty()) "GGUF v${info.version}" else parts.joinToString(" · ")
    }

    @Suppress("unused")
    fun kvPreviewOf(info: GgufInfo?): List<String> {
        if (info == null) return emptyList()
        return listOfNotNull(
            info.name,
            info.architecture,
            info.quantLabel,
        ).take(MAX_KV_PREVIEW)
    }
}
