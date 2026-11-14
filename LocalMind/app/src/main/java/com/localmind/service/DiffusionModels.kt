package com.localmind.service

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

private const val TAG = "DiffusionModels"

/**
 * 找出本机哪些 `.gguf` 是**生图（扩散）checkpoint**。
 *
 * ## 为什么必须读文件头而不是猜文件名
 *
 * 这里原来是这样判断的：
 *
 * ```kotlin
 * f.name.contains("sd", true) || f.name.contains("diffusion", true) ||
 *     f.name.contains("turbo", true)
 * ```
 *
 * 这个判据**两头都错**：
 *
 *  * **漏**：真实的 SD checkpoint 叫 `dreamshaper-8-inpainting`、
 *    `anything-v5`、`meinamix`、`counterfeit-v3` —— 一个都不含那几个词，
 *    于是用户下好了模型，应用还是画程序生成的渐变图。
 *  * **误**：`qwen2.5-coder-7b` 之类不含那些词没问题，但
 *    `stablelm-2-1.6b`（一个**聊天**模型）含 "s"? 不 —— 它含 "stable"，
 *    而判据是 `contains("sd")`，那倒不会命中。真正会误判的是
 *    `sd` 这个两字母子串出现在 `qsd`、`msd`、文件名里的日期 `202409sd`
 *    等等。两字母子串做内容判据本身就不可靠。
 *
 * 正确做法是**读 GGUF 自己的元数据**：扩散模型和文本模型的 KV 键完全不同 ——
 * 扩散模型有 `vae` / `clip_l` / `cond_stage_model.*` / `model.diffusion_model.*`，
 * 文本模型有 `general.architecture = "llama" | "qwen2" | ...`。
 * 这是文件自己声明的类型，比文件名可靠得多。
 *
 * ## 读多少
 *
 * 只读文件头的 KV 段，不读张量数据。一个 2 GB 的 checkpoint 也只需要读
 * 前几十 KB，所以对每个候选文件做一次判断是可以接受的。
 */
object DiffusionModels {

    /** 判定结果。 */
    enum class Kind {
        /** 确认是扩散 checkpoint。 */
        DIFFUSION,

        /** 确认是文本/多模态 GGUF（不是生图模型）。 */
        TEXT,

        /** 头读不出来或是损坏的。 */
        UNKNOWN,
    }

    /** 结果缓存：文件路径 + 大小 + mtime 做键，避免每次刷新都重读。 */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Kind>()

    /**
     * 判断一个 gguf 是不是扩散模型。
     *
     * 只读必要的那一段。任何异常都返回 [Kind.UNKNOWN] 而不是抛出去 ——
     * 一个坏文件不该让整个生图页打不开。
     */
    fun kind(file: File): Kind {
        if (!file.isFile || file.length() < 512) return Kind.UNKNOWN
        // 非 GGUF 的 checkpoint（safetensors / ckpt）没有 GGUF 头可读，
        // 直接按扩展名认 —— 文本模型几乎不会以 .safetensors 躺在 models 目录里。
        if (!file.name.endsWith(".gguf", ignoreCase = true)) return Kind.DIFFUSION
        val key = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
        cache[key]?.let { return it }
        val result = runCatching { readKind(file) }.getOrElse {
            Log.w(TAG, "读不了 GGUF 头：${file.name}（${it.message?.take(60)}）")
            Kind.UNKNOWN
        }
        cache[key] = result
        // 缓存别无限长：一个目录里通常就几个模型，超过就清掉重来。
        if (cache.size > 64) cache.clear()
        return result
    }

    /**
     * 最近一次判定的**现场证据**，给自检/测试模式看。
     *
     * 加它的原因："生图不能用"在真机上表现为"没找到扩散 checkpoint"，
     * 而**文件明明在那里**。只报"没找到"没法查，所以把读到的架构名、
     * 见过的张量名前缀记下来 —— 一眼就能看出是文件不对还是判据太窄。
     */
    @Volatile
    var lastProbe: String = ""
        private set

    private fun readKind(file: File): Kind {
        RandomAccessFile(file, "r").use { raf ->
            // ---- 头 ----
            //
            // GGUF 头的字段宽度是**容易搞错**的地方，实测过一次：
            //
            //   offset 0   char[4]  magic "GGUF"
            //   offset 4   uint32   version
            //   offset 8   uint64   tensor_count   ← 8 字节，不是 4
            //   offset 16  uint64   kv_count       ← 8 字节，不是 4
            //   offset 24  第一个 KV
            //
            // 一开始按 uint32 读这两个计数，偏移就全错了：键名解析从半个
            // 计数里开始，读出一堆乱码，判据恒为 UNKNOWN —— 而且**不报错**，
            // 因为每一步都"成功"了。所以这里保留这段说明。
            val head = ByteArray(24)
            raf.readFully(head)
            val magic = String(head, 0, 4, Charsets.US_ASCII)
            if (magic != "GGUF") {
                lastProbe = "不是 GGUF 文件"
                return Kind.UNKNOWN
            }

            val buf = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            buf.position(4)
            val version = buf.int
            if (version < 2 || version > 3) {
                lastProbe = "GGUF 版本 " + version + " 不认识"
                return Kind.UNKNOWN
            }
            val tensorCount = buf.long
            val kvCount = buf.long

            // ---- KV 段 ----
            var sawDiffusionKey = false
            var sawTextArch = false
            var arch = ""
            var read = 0L
            var kvDone = true

            for (i in 0 until kvCount.coerceAtMost(4096).toInt()) {
                val key = readString(raf)
                if (key == null) { kvDone = false; break }
                read += key.length + 12
                if (read > MAX_HEADER_BYTES) { kvDone = false; break }

                if (key == "general.architecture") {
                    sawTextArch = true
                    // ⚠️ **要读它的值**。扩散模型在这里写的是 "stable-diffusion"，
                    // 而原代码只记了个"见过这个键"就当成文本模型的证据 —— 
                    // 于是每一份 sd.cpp 导出的 checkpoint 都被判成 TEXT。
                    arch = readStringValue(raf) ?: ""
                    val a = arch.lowercase(Locale.US)
                    if (a.contains("stable-diffusion") || a == "sd" || a.contains("diffusion")) {
                        sawDiffusionKey = true
                    }
                    continue
                }
                if (isDiffusionKey(key)) sawDiffusionKey = true

                if (!skipValue(raf)) { kvDone = false; break }
            }

            // ---- 张量段：真正的判据 ----
            //
            // GGUF 里**权重名字不在 KV 段**，它们在后半段的张量信息里。
            // sd.cpp 的 checkpoint 张量名是 vae.* / model.diffusion_model.* 这一套，
            // 文本模型是 blk.*.attn_q.weight 那一套 —— 这是文件自己声明的结构，
            // 比任何键名猜测都可靠。只读前若干个名字就够判断。
            var tensorHint = ""
            if (kvDone) {
                val names = readTensorNames(raf, tensorCount, limit = 24)
                tensorHint = names.firstOrNull().orEmpty()
                if (names.any { isDiffusionTensor(it) }) sawDiffusionKey = true
                if (names.any { isTextTensor(it) }) sawTextArch = true
            }

            lastProbe = buildString {
                append("arch=").append(arch.ifBlank { "?" })
                append(" kv=").append(kvCount)
                append(" tensor=").append(tensorCount)
                if (tensorHint.isNotBlank()) append(" 首个张量=").append(tensorHint)
                if (!kvDone) append("（KV 段没读完，判据只看前半段）")
            }

            return when {
                sawDiffusionKey -> Kind.DIFFUSION
                sawTextArch -> Kind.TEXT
                else -> Kind.UNKNOWN
            }
        }
    }

    /** 扩散 checkpoint 的张量名（这不是猜：它们是 UNet / VAE / 文本编码器的标准命名）。 */
    private fun isDiffusionTensor(name: String): Boolean =
        name.startsWith("vae.") ||
            name.startsWith("model.diffusion_model.") ||
            name.startsWith("cond_stage_model.") ||
            name.startsWith("first_stage_model.") ||
            name.startsWith("text_encoder") ||
            name.startsWith("clip_l.") ||
            name.startsWith("clip_g.") ||
            name.startsWith("t5xxl.") ||
            name.startsWith("unet.") ||
            name.startsWith("double_blocks.") ||
            name.startsWith("joint_blocks.") ||
            name.startsWith("single_blocks.") ||
            name.startsWith("time_embed.")

    /** 文本/多模态模型的张量名（llama.cpp 系）。 */
    private fun isTextTensor(name: String): Boolean =
        name.startsWith("blk.") ||
            name.startsWith("token_embd") ||
            name.startsWith("output.weight") ||
            name.startsWith("output_norm") ||
            name.startsWith("v.blk.") ||
            name.startsWith("mm.")

    /** 读一个字符串类型的 KV **值**（键已经读掉了）。 */
    private fun readStringValue(raf: RandomAccessFile): String? = runCatching {
        val typeBuf = ByteArray(4)
        raf.readFully(typeBuf)
        val type = ByteBuffer.wrap(typeBuf).order(ByteOrder.LITTLE_ENDIAN).int
        if (type != T_STRING) {
            skipTyped(raf, type)
            return null
        }
        readString(raf)
    }.getOrNull()

    /**
     * 从张量信息段读前 [limit] 个张量名。
     *
     * 每个张量的布局：name(string) + n_dims(uint32) + dims(uint64×n) + type(uint32) + offset(uint64)。
     * 只读名字，读完名字就直接跳到下一个（靠 dims 自己算步长）。
     */
    private fun readTensorNames(raf: RandomAccessFile, tensorCount: Long, limit: Int): List<String> {
        val out = mutableListOf<String>()
        runCatching {
            repeat(tensorCount.coerceAtMost(limit.toLong()).toInt()) {
                val name = readString(raf) ?: return@repeat
                out += name
                val dimsBuf = ByteArray(4)
                raf.readFully(dimsBuf)
                val nDims = ByteBuffer.wrap(dimsBuf).order(ByteOrder.LITTLE_ENDIAN).int
                if (nDims < 0 || nDims > 8) return@repeat
                raf.seek(raf.filePointer + 8L * nDims + 4L + 8L)
            }
        }
        return out
    }

    /** GGUF 里 `value_type` 的枚举。 */
    private const val T_UINT8 = 0
    private const val T_INT8 = 1
    private const val T_UINT16 = 2
    private const val T_INT16 = 3
    private const val T_UINT32 = 4
    private const val T_INT32 = 5
    private const val T_FLOAT32 = 6
    private const val T_BOOL = 7
    private const val T_STRING = 8
    private const val T_ARRAY = 9
    private const val T_UINT64 = 10
    private const val T_INT64 = 11
    private const val T_FLOAT64 = 12

    /** 最多读这么多字节的文件头就放弃 —— 防畸形文件让我们读遍整个 2 GB。 */
    private const val MAX_HEADER_BYTES = 4L * 1024 * 1024

    /**
     * 扩散 checkpoint 特有的键前缀。
     *
     * 这些都是 stable-diffusion.cpp / diffusers 的命名，文本模型不会有：
     *  * `vae.*` —— 变分自编码器
     *  * `cond_stage_model.*` —— 文本编码器（SD1.x/SDXL 老命名）
     *  * `model.diffusion_model.*` —— UNet
     *  * `clip_l.*` / `clip_g.*` / `t5xxl.*` —— 各代文本编码器
     */
    private fun isDiffusionKey(key: String): Boolean =
        key.startsWith("vae.") ||
            key.startsWith("cond_stage_model") ||
            key.startsWith("model.diffusion_model") ||
            key.startsWith("clip_l.") ||
            key.startsWith("clip_g.") ||
            key.startsWith("t5xxl.") ||
            key.startsWith("unet.") ||
            key == "sd.version" ||
            key.startsWith("sd.")

    /** GGUF 字符串：uint64 长度 + UTF-8 字节。 */
    private fun readString(raf: RandomAccessFile): String? = runCatching {
        val lenBuf = ByteArray(8)
        raf.readFully(lenBuf)
        val len = ByteBuffer.wrap(lenBuf).order(ByteOrder.LITTLE_ENDIAN).long
        if (len < 0 || len > 65535) return null
        val bytes = ByteArray(len.toInt())
        raf.readFully(bytes)
        String(bytes, Charsets.UTF_8)
    }.getOrNull()

    /** 跳过一个 KV 值。返回 false 表示读到了预料之外的东西，调用方应停止。 */
    private fun skipValue(raf: RandomAccessFile): Boolean = runCatching {
        val typeBuf = ByteArray(4)
        raf.readFully(typeBuf)
        val type = ByteBuffer.wrap(typeBuf).order(ByteOrder.LITTLE_ENDIAN).int
        skipTyped(raf, type)
        true
    }.getOrDefault(false)

    private fun skipTyped(raf: RandomAccessFile, type: Int) {
        val size = when (type) {
            T_UINT8, T_INT8, T_BOOL -> 1
            T_UINT16, T_INT16 -> 2
            T_UINT32, T_INT32, T_FLOAT32 -> 4
            T_UINT64, T_INT64, T_FLOAT64 -> 8
            T_STRING -> {
                readString(raf)
                return
            }
            T_ARRAY -> {
                // 数组：元素类型 + 元素个数 + 逐个元素。
                val head = ByteArray(12)
                raf.readFully(head)
                val b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
                val elemType = b.int
                val count = b.long
                // 防御：畸形文件可能声明一个天文数字的数组。
                if (count < 0 || count > 1_000_000) return
                repeat(count.toInt().coerceAtMost(1_000_000)) { skipTyped(raf, elemType) }
                return
            }
            else -> return
        }
        raf.seek(raf.filePointer + size)
    }

    /**
     * 列出本机所有的扩散 checkpoint，新的在前。
     *
     * 只看 [Storage.modelsDir] 下的 `.gguf`，并且**真的读头确认**。
     */
    /**
     * sd.cpp 能直接吃的 checkpoint 扩展名。
     *
     * 「通用版」的一条：不再只认 `.gguf`。stable-diffusion.cpp 本来就支持
     * safetensors 与原始 ckpt，门槛是**只是我们没列出来** —— 用户把 `xxx.safetensors`
     * 丢进 files/models/ 却在生图页看不到它，只会得出「不支持这个格式」。
     */
    private val CHECKPOINT_EXTS = listOf(".gguf", ".safetensors", ".ckpt", ".pt")

    fun findCheckpoints(modelsDir: File): List<File> {
        if (!modelsDir.isDirectory) return emptyList()
        return modelsDir.listFiles { f ->
            f.isFile && CHECKPOINT_EXTS.any { f.name.endsWith(it, ignoreCase = true) }
        }
            ?.filter { kind(it) == Kind.DIFFUSION }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }

    /**
     * 选一个用来生图的 checkpoint。
     *
     * @param preferred 用户明确指定的路径。**它优先**：用户在设置里指了一个文件，
     *   就是他想要那个；就算读头认不出来也照样用 —— 也许是我们不认识的新版本，
     *   试一下比直接拒绝好。
     */
    fun resolve(modelsDir: File, preferred: String?): File? {
        preferred?.takeIf { it.isNotBlank() }?.let { p ->
            val f = File(p)
            if (f.isFile && f.length() > 0) return f
        }
        return findCheckpoints(modelsDir).firstOrNull()
    }
}
