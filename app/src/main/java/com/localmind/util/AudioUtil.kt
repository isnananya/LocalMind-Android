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
    fun concatWavs(files: List<File>, gapMs: Int, target: File): Boolean {
        if (files.isEmpty()) return false
        val first = readWavToMonoFloat(files.first().absolutePath) ?: return false
        val rate = first.second
        val out = ArrayList<Float>(first.first.size * files.size)
        val gap = (rate.toLong() * gapMs / 1000).toInt().coerceAtLeast(0)
        files.forEachIndexed { index, f ->
            val parsed = readWavToMonoFloat(f.absolutePath) ?: return false
            if (parsed.second != rate) return false
            if (index > 0 && gap > 0) repeat(gap) { out.add(0f) }
            parsed.first.forEach { out.add(it) }
        }
        return writeWav(target.absolutePath, out.toFloatArray(), rate)
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
