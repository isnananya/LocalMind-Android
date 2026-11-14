package com.localmind.util

import java.io.File
import java.io.RandomAccessFile

/**
 * 轻量音频工具：把参考音频读成单声道 FloatArray，并按需重采样。
 *
 * 只支持**标准 16-bit / 8-bit PCM WAV**（无压缩）——这正是录音和「导出 WAV」的产物。
 * MP3 / m4a 这类有损压缩格式不在范围内：解码它们要引 MediaCodec 一大套，而克隆功能
 * 只收 WAV（录制直接出 WAV，上传也限定 WAV），没必要为小众格式付这套复杂度。
 */
object AudioUtil {

    /**
     * 读 16-bit PCM WAV → 单声道 FloatArray（归一化到 [-1,1]）。
     * @return (samples, sampleRate)，读不了返回 null。
     */
    fun readWavToMonoFloat(path: String): Pair<FloatArray, Int>? {
        val f = File(path)
        if (!f.isFile) return null
        return runCatching {
            RandomAccessFile(f, "r").use { raf ->
                val riff = ByteArray(4); raf.readFully(riff)
                if (!riff.contentEquals("RIFF".toByteArray())) return@use null
                raf.skipBytes(4)
                val wave = ByteArray(4); raf.readFully(wave)
                if (!wave.contentEquals("WAVE".toByteArray())) return@use null

                var sampleRate = 0
                var bits = 16
                var channels = 1
                var dataStart = -1L
                var dataLen = 0
                while (true) {
                    val id = ByteArray(4)
                    if (raf.read(id) != 4) break
                    val size = readLE32(raf)
                    when {
                        id.contentEquals("fmt ".toByteArray()) -> {
                            val fmt = ByteArray(size); raf.readFully(fmt)
                            channels = (fmt[2].toInt() and 0xFF) or ((fmt[3].toInt() and 0xFF) shl 8)
                            sampleRate = readLE32From(fmt, 4)
                            bits = (fmt[14].toInt() and 0xFF) or ((fmt[15].toInt() and 0xFF) shl 8)
                        }
                        id.contentEquals("data".toByteArray()) -> {
                            dataStart = raf.filePointer
                            dataLen = size
                            break
                        }
                        else -> raf.skipBytes(size)
                    }
                }
                if (dataStart < 0 || sampleRate <= 0) return@use null
                raf.seek(dataStart)
                val bytesPerSample = (bits / 8).coerceAtLeast(1)
                val totalSamples = (dataLen / bytesPerSample).coerceAtLeast(0)
                val ch = channels.coerceAtLeast(1)
                val out = FloatArray(totalSamples / ch)
                var oi = 0
                val buf16 = ByteArray(2)
                for (i in 0 until totalSamples) {
                    val fv = when (bits) {
                        16 -> {
                            raf.readFully(buf16)
                            val s = (buf16[0].toInt() and 0xFF) or ((buf16[1].toInt() and 0xFF) shl 8)
                            val v = if (s >= 32768) s - 65536 else s
                            v / 32768f
                        }
                        8 -> {
                            val b = raf.read()
                            if (b < 0) 0f else (b - 128) / 128f
                        }
                        else -> {
                            raf.skipBytes(bytesPerSample)
                            0f
                        }
                    }
                    if (i % ch == 0) out[oi++] = fv
                }
                out to sampleRate
            }
        }.getOrNull()
    }

    /** 线性插值重采样到 [toRate]（参考音频通常要归一到 24000 Hz 给 ZipVoice2）。 */
    fun resample(samples: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate <= 0 || toRate <= 0 || fromRate == toRate || samples.isEmpty()) return samples
        val ratio = toRate.toDouble() / fromRate
        val n = (samples.size * ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(n)
        for (i in 0 until n) {
            val pos = i / ratio
            val i0 = pos.toInt().coerceIn(0, samples.size - 1)
            val i1 = (i0 + 1).coerceIn(0, samples.size - 1)
            val frac = (pos - i0).toFloat()
            out[i] = samples[i0] * (1 - frac) + samples[i1] * frac
        }
        return out
    }

    /**
     * 把 FloatArray（[-1,1]）写成 16-bit 单声道 PCM WAV。
     *
     * 录音直接落这个格式，而不是先存 PCM 再转：克隆只收 WAV，多转一道没有任何收益，
     * 而且 WAV 头 44 字节是定长的，边录边写也能在结束时回填长度。
     */
    fun writeWav(path: String, samples: FloatArray, sampleRate: Int): Boolean {
        if (samples.isEmpty() || sampleRate <= 0) return false
        return runCatching {
            val file = File(path)
            file.parentFile?.mkdirs()
            val dataLen = samples.size * 2
            java.io.DataOutputStream(file.outputStream().buffered()).use { out ->
                fun le32(v: Int) {
                    out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
                    out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF)
                }
                fun le16(v: Int) {
                    out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
                }
                out.writeBytes("RIFF"); le32(36 + dataLen); out.writeBytes("WAVE")
                out.writeBytes("fmt "); le32(16); le16(1); le16(1)
                le32(sampleRate); le32(sampleRate * 2); le16(2); le16(16)
                out.writeBytes("data"); le32(dataLen)
                for (s in samples) {
                    val v = (s.coerceIn(-1f, 1f) * 32767f).toInt()
                    le16(v and 0xFFFF)
                }
            }
            true
        }.getOrDefault(false)
    }

    /** 把 16-bit 采样的 ShortArray 写成同格式 WAV（录音路径用，省一次 Float 转换）。 */    fun writeWavShorts(path: String, samples: ShortArray, sampleRate: Int): Boolean {
        if (samples.isEmpty() || sampleRate <= 0) return false
        return runCatching {
            val file = File(path)
            file.parentFile?.mkdirs()
            val dataLen = samples.size * 2
            java.io.DataOutputStream(file.outputStream().buffered()).use { out ->
                fun le32(v: Int) {
                    out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
                    out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF)
                }
                fun le16(v: Int) {
                    out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
                }
                out.writeBytes("RIFF"); le32(36 + dataLen); out.writeBytes("WAVE")
                out.writeBytes("fmt "); le32(16); le16(1); le16(1)
                le32(sampleRate); le32(sampleRate * 2); le16(2); le16(16)
                out.writeBytes("data"); le32(dataLen)
                for (s in samples) le16(s.toInt() and 0xFFFF)
            }
            true
        }.getOrDefault(false)
    }

    /**
     * 把多个 WAV 按顺序拼成一个，句间插 [gapMs] 毫秒静音。
     *
     * 为什么按文件拼而不是内存里拼：合成是**按句调引擎、每句落一个临时 WAV**
     * （引擎的 API 就是写文件），所以这里是"读回来、接着写"。
     * 采样率取第一个文件的；中途遇到不一致的直接放弃 —— 拼出来的是垃圾。
     */
    /**
     * 一个 WAV 的布局信息（只解析 header，不碰采样数据）。
     */
    private class WavInfo(
        val rate: Int,
        val bits: Int,
        val channels: Int,
        val dataStart: Long,
        val dataLen: Int,
    ) {
        val frameBytes: Int get() = channels.coerceAtLeast(1) * (bits / 8).coerceAtLeast(1)
        val frames: Int get() = if (frameBytes > 0) dataLen / frameBytes else 0
    }

    /**
     * 一段 WAV 的时长（毫秒）；读不出返回 null。供字幕时间轴、长文合并等场景用，
     * 不走 [android.media.MediaPlayer]（那个要准备解码，逐句算起太重）。
     */
    fun durationMs(f: File): Long? {
        val info = probeWav(f) ?: return null
        if (info.rate <= 0) return null
        return info.frames * 1000L / info.rate
    }

    private fun probeWav(f: File): WavInfo? {
        if (!f.isFile) return null
        return runCatching {
            RandomAccessFile(f, "r").use { raf ->
                val riff = ByteArray(4); raf.readFully(riff)
                if (!riff.contentEquals("RIFF".toByteArray())) return@use null
                raf.skipBytes(4)
                val wave = ByteArray(4); raf.readFully(wave)
                if (!wave.contentEquals("WAVE".toByteArray())) return@use null

                var rate = 0
                var bits = 16
                var ch = 1
                while (true) {
                    val id = ByteArray(4)
                    if (raf.read(id) != 4) break
                    val size = readLE32(raf)
                    when {
                        id.contentEquals("fmt ".toByteArray()) -> {
                            val fmt = ByteArray(size); raf.readFully(fmt)
                            ch = (fmt[2].toInt() and 0xFF) or ((fmt[3].toInt() and 0xFF) shl 8)
                            rate = readLE32From(fmt, 4)
                            bits = (fmt[14].toInt() and 0xFF) or ((fmt[15].toInt() and 0xFF) shl 8)
                        }
                        id.contentEquals("data".toByteArray()) -> {
                            return@use if (rate > 0) WavInfo(rate, bits, ch, raf.filePointer, size) else null
                        }
                        else -> raf.skipBytes(size)
                    }
                }
                null
            }
        }.getOrNull()
    }

    /**
     * 把多段 WAV 拼成一个，段之间插 [gapMs] 毫秒静音。
     *
     * ## 为什么是流式
     *
     * 上一版是 `ArrayList<Float>` 逐样本 `add`，再 `toFloatArray()` 拷一份。
     * 三个问题叠在一起：
     *  * **装箱**：每个样本一个 `java.lang.Float` 对象，10 分钟 24 kHz 音频
     *    是 1440 万个盒子，光这一项就要 300 MB 以上，长文合成就 OOM 了；
     *  * **扩容**：ArrayList 反复扩容 + 逐元素边界检查，比批量拷贝慢一个量级；
     *  * **峰值双份**：最后那次 `toFloatArray()` 又复制一遍。
     *
     * 现在只按段读 data 块、32 KB 批量转成 16-bit little-endian 直接写出去，
     * 峰值内存等于两个缓冲区 —— 和音频多长无关。
     *
     * 逐样本的规则和 [readWavToMonoFloat] 一致：多声道**只取第一声道**，
     * 24/32 bit 写成静音。采样率不一致直接返回 false（调用方会报错，
     * 拼出变了调的音频比失败更糟）。
     */
    fun concatWavs(files: List<File>, gapMs: Int, target: File): Boolean = runCatching {
        if (files.isEmpty()) return false
        val infos = files.map { probeWav(it) ?: return false }
        val rate = infos.first().rate
        if (infos.any { it.rate != rate }) return false

        val gapFrames = (rate.toLong() * gapMs / 1000).toInt().coerceAtLeast(0)
        var totalFrames = 0L
        infos.forEachIndexed { i, info ->
            totalFrames += info.frames + if (i > 0) gapFrames else 0
        }
        // RIFF 的 data 长度是 32 位字节数，超过就没法用这个容器表达了（约 12 小时）。
        if (totalFrames <= 0L || totalFrames * 2 > Int.MAX_VALUE - 64) return false

        val file = target
        file.parentFile?.mkdirs()
        val dataLen = (totalFrames * 2).toInt()
        java.io.DataOutputStream(file.outputStream().buffered(64 * 1024)).use { out ->
            fun le32(v: Int) {
                out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
                out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF)
            }
            fun le16(v: Int) {
                out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
            }
            out.writeBytes("RIFF"); le32(36 + dataLen); out.writeBytes("WAVE")
            out.writeBytes("fmt "); le32(16); le16(1); le16(1)
            le32(rate); le32(rate * 2); le16(2); le16(16)
            out.writeBytes("data"); le32(dataLen)

            infos.forEachIndexed { index, info ->
                if (index > 0 && gapFrames > 0) {
                    // 静音也走批量：64 KB 的 0 字节块重复用。
                    val zero = ByteArray(64 * 1024)
                    var left = gapFrames.toLong() * 2
                    while (left > 0) {
                        val n = minOf(left, zero.size.toLong()).toInt()
                        out.write(zero, 0, n)
                        left -= n
                    }
                }
                appendPcm(out, files[index], info)
            }
        }
        true
    }.getOrDefault(false)

    /** 把一段 WAV 的采样按「单声道 16-bit LE」追加到 [out]，不整段读进内存。 */
    private fun appendPcm(out: java.io.DataOutputStream, f: File, info: WavInfo) {
        val frameBytes = info.frameBytes
        if (frameBytes <= 0 || info.dataLen <= 0) return
        RandomAccessFile(f, "r").use { raf ->
            raf.seek(info.dataStart)
            val inBuf = ByteArray(64 * 1024)
            val outBuf = ByteArray(64 * 1024)
            var oc = 0
            var remaining = info.dataLen.toLong()
            while (remaining > 0) {
                val want = minOf(inBuf.size.toLong(), remaining).toInt()
                val n = raf.read(inBuf, 0, want)
                if (n <= 0) break
                remaining -= n
                var off = 0
                // 只处理完整帧：块边界可能把一个帧切成两半，留到下一轮 —— 这里
                // 直接丢弃半帧（最多 4 字节），比维护跨块残留简单且听不出来。
                while (off + frameBytes <= n) {
                    val base = off
                    when (info.bits) {
                        16 -> {
                            outBuf[oc++] = inBuf[base]
                            outBuf[oc++] = inBuf[base + 1]
                        }
                        8 -> {
                            val v = ((inBuf[base].toInt() and 0xFF) - 128) shl 8
                            outBuf[oc++] = (v and 0xFF).toByte()
                            outBuf[oc++] = ((v ushr 8) and 0xFF).toByte()
                        }
                        else -> {
                            outBuf[oc++] = 0
                            outBuf[oc++] = 0
                        }
                    }
                    off += frameBytes
                    if (oc >= outBuf.size - 4) {
                        out.write(outBuf, 0, oc)
                        oc = 0
                    }
                }
            }
            if (oc > 0) out.write(outBuf, 0, oc)
        }
    }

    private fun readLE32(raf: RandomAccessFile): Int {
        val b = ByteArray(4); raf.readFully(b); return readLE32From(b, 0)
    }

    private fun readLE32From(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
}
