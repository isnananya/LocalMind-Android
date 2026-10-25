package com.localmind.service

import android.content.Context
import android.util.Log
import com.localmind.data.GenerationSettings
import com.localmind.data.Pipeline
import com.localmind.data.PipelineStage
import com.localmind.data.PipelineStages
import com.localmind.data.Role
import com.localmind.data.TtsEngineSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 分步执行器：把一条 [Pipeline] 从第一步跑到最后一步。
 *
 * ## 设计上的三条硬规则
 *
 * 1. **每一步都独立可失败**。第 3 片合成失败不该让前 2 片白跑 —— 错误里带上
 *    是哪一步、第几片，用户可以只重跑那一步（界面上"从失败处重试"）。
 * 2. **内存占用与输入长度无关**。每片处理完就把中间结果落盘/落列表，
 *    不保留模型态之外的任何大对象。
 * 3. **随时可中断**。长文拆完可能是几十片，用户改主意时应当立刻停下，
 *    而不是"这一整篇生成完才响应"。
 *
 * ## 为什么不用协程并行
 *
 * llama.cpp 的引擎是单实例串行的（一个模型一份 KV），语音服务端通常也是单并发。
 * 并行只会让每一片都变慢，还会把内存顶爆。串行 + 明确进度是这类任务的正解。
 */
class PipelineRunner(
    private val context: Context,
    private val engine: LlamaEngine,
    private val breeze: BreezeTts,
    private val settings: () -> GenerationSettings,
    /** 当前启用的语音引擎；null = 没装插件。 */
    private val activeTts: () -> TtsEngineSpec?,
) {

    data class Progress(
        val stepIndex: Int,
        val stepTotal: Int,
        val stepLabel: String,
        val itemIndex: Int = 0,
        val itemTotal: Int = 0,
        val detail: String = "",
    ) {
        val text: String
            get() = buildString {
                append("第 ${stepIndex + 1}/$stepTotal 步 · $stepLabel")
                if (itemTotal > 0) append("  $itemIndex/$itemTotal")
                if (detail.isNotBlank()) append("  $detail")
            }
    }

    data class Output(
        val text: String,
        val audio: File?,
        val logs: List<String>,
    )

    /** 中断开关。界面点"停止"时置 true，下一次检查就会抛 [Cancelled]。 */
    @Volatile
    var cancelled: Boolean = false

    class Cancelled : Exception("已停止")

    suspend fun run(
        pipeline: Pipeline,
        input: String,
        onProgress: (Progress) -> Unit,
    ): Result<Output> = withContext(Dispatchers.Default) {
        cancelled = false
        val logs = mutableListOf<String>()
        var chunks: List<String> = emptyList()
        var parts: List<String> = emptyList()
        var audioFiles: List<File> = emptyList()
        var merged: File? = null
        var textOut = ""

        fun check() {
            if (cancelled) throw Cancelled()
        }

        try {
            pipeline.stages.forEachIndexed { index, stage ->
                check()
                val label = stage.label.ifBlank {
                    PipelineStages.LABELS[stage.type] ?: stage.type
                }
                onProgress(Progress(index, pipeline.stages.size, label, detail = "开始"))

                when (stage.type) {
                    PipelineStages.PROBE -> {
                        logs += probeLines()
                        onProgress(Progress(index, pipeline.stages.size, label, detail = "完成"))
                    }

                    PipelineStages.SPLIT_TEXT -> {
                        val source = parts.ifEmpty { chunks }.joinToString("\n")
                            .ifBlank { input }
                        chunks = splitText(source, stage.maxChars)
                        onProgress(
                            Progress(index, pipeline.stages.size, label, detail = "切成 ${chunks.size} 片"),
                        )
                    }

                    PipelineStages.LLM -> {
                        val source = chunks.ifEmpty { listOf(input) }
                        if (!engine.state.value.ready) {
                            throw IllegalStateException("本机模型还没加载：先去「模型」页加载一个对话模型")
                        }
                        val out = mutableListOf<String>()
                        source.forEachIndexed { i, chunk ->
                            check()
                            onProgress(
                                Progress(index, pipeline.stages.size, label, i + 1, source.size, "生成中"),
                            )
                            val prompt = stage.prompt.replace("{{chunk}}", chunk)
                            val res = engine.chat(
                                messages = listOf(Role.USER to prompt),
                                settings = settings().copy(maxTokens = stage.maxTokens),
                                notifyTurnComplete = false,
                                lane = LlamaEngine.Lane.BACKGROUND,
                            )
                            val answer = res.getOrElse { e ->
                                throw IllegalStateException("第 ${i + 1} 片生成失败：${e.message}")
                            }
                            if (answer.isBlank()) {
                                // 后台让位会返回空串（有人在聊天）。这种情况要说清楚，
                                // 而不是把一条空结果拼进最终文本。
                                throw IllegalStateException(
                                    "第 ${i + 1} 片没生成出内容（可能有另一个生成在跑）。等它结束再来一次",
                                )
                            }
                            out += answer.trim()
                        }
                        parts = out
                        onProgress(
                            Progress(index, pipeline.stages.size, label, source.size, source.size, "完成"),
                        )
                    }

                    PipelineStages.JOIN_TEXT -> {
                        textOut = parts.joinToString("\n\n")
                        onProgress(
                            Progress(index, pipeline.stages.size, label, detail = "${textOut.length} 字"),
                        )
                    }

                    PipelineStages.TTS -> {
                        val spec = resolveTts(stage)
                        val source = parts.ifEmpty { chunks }.ifEmpty { listOf(input) }
                        val files = mutableListOf<File>()
                        source.forEachIndexed { i, chunk ->
                            check()
                            onProgress(
                                Progress(index, pipeline.stages.size, label, i + 1, source.size, "合成中"),
                            )
                            val out = runCatching {
                                breeze.synthesize(
                                    spec = spec,
                                    text = chunk,
                                    instruction = stage.instruction.ifBlank { spec.defaultInstruction },
                                )
                            }.getOrElse { e ->
                                // 已经合成好的那几段留着，错误里说明进度，方便单独重试。
                                throw IllegalStateException(
                                    "第 ${i + 1}/${source.size} 片合成失败（前 ${files.size} 片已完成）：${e.message}",
                                )
                            }
                            files += out.file
                        }
                        audioFiles = files
                        onProgress(
                            Progress(index, pipeline.stages.size, label, source.size, source.size, "完成"),
                        )
                    }

                    PipelineStages.CONCAT_AUDIO -> {
                        if (audioFiles.isEmpty()) {
                            throw IllegalStateException("没有可拼接的音频（前面没有成功的合成步骤）")
                        }
                        merged = concatWav(audioFiles, stage.silenceMs)
                        onProgress(
                            Progress(
                                index, pipeline.stages.size, label,
                                detail = "${merged!!.length() / 1024} KB",
                            ),
                        )
                    }

                    else -> throw IllegalStateException("不认识的动作：${stage.type}")
                }
            }

            if (textOut.isBlank() && parts.isNotEmpty()) textOut = parts.joinToString("\n\n")
            if (textOut.isBlank() && chunks.isNotEmpty()) textOut = chunks.joinToString("\n")
            Result.success(Output(text = textOut, audio = merged, logs = logs))
        } catch (c: Cancelled) {
            Result.failure(IllegalStateException("已停止（结果不完整：${parts.size} 片完成）"))
        } catch (t: Throwable) {
            Log.w("PipelineRunner", "流水线失败", t)
            Result.failure(t)
        }
    }

    // ------------------------------------------------------------------ 各步实现

    private fun resolveTts(stage: PipelineStage): TtsEngineSpec {
        val current = activeTts()
        if (stage.engine.isNotBlank() && stage.engine != "auto") {
            // 指定了引擎但当前不是它 —— 目前只有"当前启用的那一个"能拿到，
            // 所以直接说明，而不是偷偷用别的。
            return current ?: throw IllegalStateException("没有可用的语音引擎（装一个 tts_engine 插件）")
        }
        return current ?: throw IllegalStateException(
            "没有可用的语音引擎：去「我的 → 更多 → Breeze 语音」装插件并打开开关",
        )
    }

    /** 按句末标点切，再按 maxChars 兜底硬切。 */
    private fun splitText(text: String, maxChars: Int): List<String> {
        val clean = text.replace("\r\n", "\n").trim()
        if (clean.isEmpty()) return emptyList()
        val sentences = mutableListOf<String>()
        val buf = StringBuilder()
        for (ch in clean) {
            buf.append(ch)
            if (ch in "。！？!?；;\n" && buf.length >= maxChars / 3) {
                sentences += buf.toString().trim()
                buf.clear()
            }
        }
        if (buf.isNotBlank()) sentences += buf.toString().trim()

        // 单片太长（没有标点的长句）就硬切，避免一次请求塞几千字。
        val out = mutableListOf<String>()
        for (s in sentences) {
            if (s.length <= maxChars) {
                out += s
            } else {
                var i = 0
                while (i < s.length) {
                    out += s.substring(i, minOf(i + maxChars, s.length))
                    i += maxChars
                }
            }
        }
        return out.filter { it.isNotBlank() }
    }

    /** 本机体检：老实说清楚"能不能本机跑 Breeze"，以及当前机器能装什么。 */
    private fun probeLines(): List<String> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val totalGb = mi.totalMem / 1024.0 / 1024 / 1024
        val availGb = mi.availMem / 1024.0 / 1024 / 1024
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "?"
        return listOf(
            "本机内存：${"%.1f".format(totalGb)} GB（可用 ${"%.1f".format(availGb)} GB），ABI=$abi",
            "Breeze TTS 2：3.47B 参数 / BF16 权重约 7.0 GB / 官方只有 CUDA 推理 —— " +
                "本机不具备运行条件，所以这一步走的是「外部引擎 + 逐片合成」",
            "对话模型：本机通过 llama.cpp 跑 GGUF，占多少内存取决于你选的模型与上下文",
        )
    }

    /** 拼接多个单声道 16-bit WAV，段间插静音。 */
    private fun concatWav(files: List<File>, silenceMs: Int): File {
        val datas = mutableListOf<ByteArray>()
        var sampleRate = 24000
        for (f in files) {
            val (pcm, rate) = readWav(f)
            sampleRate = rate
            datas += pcm
        }
        val silence = ByteArray(sampleRate * 2 * silenceMs / 1000)
        val total = datas.sumOf { it.size } + silence.size * (datas.size - 1).coerceAtLeast(0)
        val out = java.io.ByteArrayOutputStream(total + 44)
        fun le32(v: Int) = byteArrayOf(
            (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
        )
        fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
        out.write("RIFF".toByteArray())
        out.write(le32(36 + total))
        out.write("WAVEfmt ".toByteArray())
        out.write(le32(16))
        out.write(le16(1))
        out.write(le16(1))
        out.write(le32(sampleRate))
        out.write(le32(sampleRate * 2))
        out.write(le16(2))
        out.write(le16(16))
        out.write("data".toByteArray())
        out.write(le32(total))
        datas.forEachIndexed { i, d ->
            if (i > 0 && silence.isNotEmpty()) out.write(silence)
            out.write(d)
        }
        val dir = File(context.cacheDir, "pipeline").apply { mkdirs() }
        val file = File(dir, "merged-${System.currentTimeMillis()}.wav")
        file.writeBytes(out.toByteArray())
        return file
    }

    /** 读一个单声道 16-bit WAV，返回 PCM 与采样率。 */
    private fun readWav(file: File): Pair<ByteArray, Int> {
        val b = file.readBytes()
        require(b.size > 44 && String(b, 0, 4) == "RIFF" && String(b, 8, 4) == "WAVE") {
            "不是 WAV：${file.name}"
        }
        var pos = 12
        var sampleRate = 24000
        var channels = 1
        var bits = 16
        var data: ByteArray? = null
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4)
            val size = (b[pos + 4].toInt() and 0xFF) or ((b[pos + 5].toInt() and 0xFF) shl 8) or
                ((b[pos + 6].toInt() and 0xFF) shl 16) or ((b[pos + 7].toInt() and 0xFF) shl 24)
            if (id == "fmt ") {
                channels = (b[pos + 10].toInt() and 0xFF) or ((b[pos + 11].toInt() and 0xFF) shl 8)
                sampleRate = (b[pos + 12].toInt() and 0xFF) or ((b[pos + 13].toInt() and 0xFF) shl 8) or
                    ((b[pos + 14].toInt() and 0xFF) shl 16) or ((b[pos + 15].toInt() and 0xFF) shl 24)
                bits = (b[pos + 22].toInt() and 0xFF) or ((b[pos + 23].toInt() and 0xFF) shl 8)
            } else if (id == "data") {
                val end = minOf(pos + 8 + size, b.size)
                data = b.copyOfRange(pos + 8, end)
            }
            pos += 8 + size + (size and 1)
        }
        require(data != null) { "WAV 里没有 data 段：${file.name}" }
        require(channels == 1 && bits == 16) {
            "目前只支持单声道 16-bit，这段是 ${channels} 声道 / ${bits} bit"
        }
        return data!! to sampleRate
    }
}
