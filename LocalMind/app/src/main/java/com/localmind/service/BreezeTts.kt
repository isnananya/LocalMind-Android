package com.localmind.service

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import com.localmind.data.TtsEngineSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Breeze TTS 2（以及任意 OpenAI 兼容 TTS）的客户端。
 *
 * ## 为什么是"客户端"而不是"本机推理"
 *
 * Breeze TTS 2 是 3.47B / BF16 约 7 GB 的模型，官方只提供 PyTorch + CUDA 推理
 * （T5Gemma2 文本编码器 + Qwen3 风格主干 + 深度解码器 + Mimi 系 codec）。
 * 手机上既没有 CUDA，也没有把这条链路搬到 ARM 的运行时 —— 在手机上"本地跑"
 * 这个模型不是优化问题，是没有路。所以正确的接法是：**PC / GPU 服务器上自建
 * 服务，手机当客户端**，这也正是插件存在的意义（见 [com.localmind.data.TtsPack]）。
 *
 * ## 协议
 *
 *  - `breeze`：`POST {base}/v1/audio/speech`，multipart 表单
 *    （text / instruction / ref_audio / ref_text / cfg_scale / seed），
 *    返回 24 kHz 单声道 s16le PCM 流。
 *  - `openai`：`POST {base}/v1/audio/speech`，JSON（model / input / voice /
 *    response_format），返回音频文件。
 *
 * ## 为什么不用流式播放
 *
 * 一句聊天回复通常一两百字，合成时间远小于句子之间的间隔；而边收边播要求
 * 自己写 AudioTrack 环形缓冲 + 断点重连，收益只在"超长文本连续朗读"时才明显。
 * 这里选**收完再播**，换来的是失败能整句重试、不会播到一半断掉。
 */
class BreezeTts(private val context: Context) {

    data class Outcome(val file: File, val elapsedMs: Long, val bytes: Int, val sampleRate: Int)

    /** 合成一句话，返回落盘的 WAV。网络 / 服务端错误会以异常形式抛出。 */
    suspend fun synthesize(
        spec: TtsEngineSpec,
        text: String,
        instruction: String = spec.defaultInstruction,
        voice: String = spec.defaultVoice,
        cfgScale: Float = spec.cfgScale,
        refAudio: File? = null,
        refText: String = "",
        seed: Int = spec.seed,
    ): Outcome = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val url = spec.endpoint
        require(url.isNotBlank()) { "这个引擎还没填地址" }
        require(text.isNotBlank()) { "要合成的文本是空的" }

        val raw: ByteArray
        val contentType: String
        if (spec.isBreeze) {
            val boundary = "----LocalMind" + UUID.randomUUID().toString().replace("-", "")
            val body = buildBreezeMultipart(
                boundary = boundary,
                spec = spec,
                text = text,
                instruction = instruction,
                voice = voice,
                cfgScale = cfgScale,
                refAudio = refAudio,
                refText = refText,
                seed = seed,
            )
            val res = post(
                url,
                contentType = "multipart/form-data; boundary=$boundary",
                body = body,
                apiKey = spec.apiKey,
                connectTimeoutMs = spec.connectTimeoutMs,
                readTimeoutMs = spec.readTimeoutMs,
            )
            raw = res.first
            contentType = res.second
        } else {
            val json = buildJsonBody(
                model = spec.model.ifBlank { "tts-1" },
                input = text,
                voice = voice.ifBlank { "alloy" },
            )
            val res = post(
                url,
                contentType = "application/json",
                body = json.toByteArray(),
                apiKey = spec.apiKey,
                connectTimeoutMs = spec.connectTimeoutMs,
                readTimeoutMs = spec.readTimeoutMs,
            )
            raw = res.first
            contentType = res.second
        }

        if (raw.isEmpty()) throw IllegalStateException("服务返回了空音频")

        val wav = if (looksLikeContainer(raw, contentType)) {
            raw
        } else {
            wrapPcmAsWav(raw, spec.sampleRate)
        }
        val out = File(ttsCacheDir(), "breeze-${System.currentTimeMillis()}.wav")
        out.writeBytes(wav)
        Outcome(
            file = out,
            elapsedMs = System.currentTimeMillis() - started,
            bytes = wav.size,
            sampleRate = spec.sampleRate,
        )
    }

    /** 播放刚合成的 WAV。返回播放器，调用方可以 stop。 */
    fun play(file: File): MediaPlayer? = runCatching {
        MediaPlayer().apply {
            setDataSource(file.absolutePath)
            prepare()
            start()
        }
    }.onFailure { Log.w(TAG, "播放失败", it) }.getOrNull()

    /**
     * 探一下服务是否可达（GET 根地址）。返回一句给人看的结果。
     *
     * 它不是"合成测试"：合成要跑模型，可能几十秒；这里只证明网络 / 地址 / 鉴权
     * 这条路是通的。真正的合成测试在「试听」那一栏。
     */
    suspend fun probe(spec: TtsEngineSpec): String = withContext(Dispatchers.IO) {
        val base = spec.baseUrl.trim().trimEnd('/')
        require(base.startsWith("http://") || base.startsWith("https://")) {
            "地址要以 http:// 或 https:// 开头"
        }
        val started = System.currentTimeMillis()
        val timeout = spec.connectTimeoutMs.coerceIn(1_000, 10_000)
        val conn = (URL(base).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeout
            readTimeout = timeout
            if (spec.apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer " + spec.apiKey)
        }
        try {
            val code = conn.responseCode
            val ms = System.currentTimeMillis() - started
            "服务可达：HTTP " + code + " · " + ms + " ms"
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------ 内部

    private fun ttsCacheDir(): File = File(context.cacheDir, "tts").apply { mkdirs() }

    private fun buildBreezeMultipart(
        boundary: String,
        spec: TtsEngineSpec,
        text: String,
        instruction: String,
        voice: String,
        cfgScale: Float,
        refAudio: File?,
        refText: String,
        seed: Int,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun field(name: String, value: String) {
            out.write("--$boundary\r\n".toByteArray())
            out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
            out.write(value.toByteArray(Charsets.UTF_8))
            out.write("\r\n".toByteArray())
        }
        field("text", text)
        if (instruction.isNotBlank()) field("instruction", instruction)
        if (voice.isNotBlank()) field("voice", voice)
        field("cfg_scale", cfgScale.toString())
        field("seed", seed.toString())
        if (refText.isNotBlank()) field("ref_text", refText)
        if (refAudio != null && refAudio.isFile) {
            out.write("--$boundary\r\n".toByteArray())
            out.write(
                "Content-Disposition: form-data; name=\"ref_audio\"; filename=\"${refAudio.name}\"\r\n"
                    .toByteArray()
            )
            out.write("Content-Type: audio/wav\r\n\r\n".toByteArray())
            out.write(refAudio.readBytes())
            out.write("\r\n".toByteArray())
        }
        out.write("--$boundary--\r\n".toByteArray())
        return out.toByteArray()
    }

    private fun buildJsonBody(model: String, input: String, voice: String): String {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n")
        return """{"model":"${esc(model)}","input":"${esc(input)}","voice":"${esc(voice)}","response_format":"wav"}"""
    }

    private fun post(
        url: String,
        contentType: String,
        body: ByteArray,
        apiKey: String,
        connectTimeoutMs: Int = 8_000,
        readTimeoutMs: Int = 120_000,
    ): Pair<ByteArray, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", contentType)
            setRequestProperty("Accept", "audio/*")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            conn.outputStream.use { os: OutputStream -> os.write(body) }
            val code = conn.responseCode
            val stream: InputStream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            if (code !in 200..299) {
                val snippet = runCatching { String(bytes, Charsets.UTF_8).take(200) }.getOrDefault("")
                throw IllegalStateException("服务返回 $code：$snippet")
            }
            return bytes to (conn.contentType ?: "")
        } finally {
            conn.disconnect()
        }
    }

    /** 是不是已经是容器格式（wav / mp3 / ogg），是就别再套一层 WAV 头。 */
    private fun looksLikeContainer(bytes: ByteArray, contentType: String): Boolean {
        if (contentType.contains("wav", true) || contentType.contains("mpeg", true) ||
            contentType.contains("ogg", true)
        ) return true
        if (bytes.size < 4) return false
        val riff = bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()
        val id3 = bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()
        val ogg = bytes[0] == 'O'.code.toByte() && bytes[1] == 'g'.code.toByte() && bytes[2] == 'g'.code.toByte()
        return riff || id3 || ogg
    }

    /** 给裸 PCM 套一个 44 字节的 WAV 头（单声道 / 16 bit）。 */
    private fun wrapPcmAsWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(pcm.size + 44)
        fun le32(v: Int) = byteArrayOf(
            (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
        )
        fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
        out.write("RIFF".toByteArray())
        out.write(le32(36 + pcm.size))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(le32(16))
        out.write(le16(1))                       // PCM
        out.write(le16(1))                       // mono
        out.write(le32(sampleRate))
        out.write(le32(sampleRate * 2))          // byte rate
        out.write(le16(2))                       // block align
        out.write(le16(16))                      // bits
        out.write("data".toByteArray())
        out.write(le32(pcm.size))
        out.write(pcm)
        return out.toByteArray()
    }

    private companion object {
        const val TAG = "BreezeTts"
    }
}
