package com.localmind.util

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * 录一段「参考音频」用的小录音器：16 kHz / 单声道 / PCM-16，直接落 WAV。
 *
 * 为什么自己写而不是用 MediaRecorder：后者只能出 m4a/amr 这类有损容器，而
 * ZipVoice2 的参考音频要的是能解码成浮点波形的 PCM。用 AudioRecord 拿裸采样、
 * 自己补 44 字节 WAV 头，是这条路里最短的一段。
 *
 * 16 kHz 够用：克隆只关心 5–30 秒内的音色与韵律，人声的有效信息基本在 8 kHz 以下；
 * 而且采样率越低，之后重采样到 24 kHz 的插值误差越小、内存占用也越小。
 *
 * 线程模型：[start] 里起一个后台线程读采样，[stop] 只置一个标志位。回调
 * （[onAmplitude] / [onFinished]）都在那个后台线程上执行 —— 调用方若是更新 Compose
 * 状态，请自行确保线程安全（Snapshot 的写入是安全的，直接写即可）。
 */
class VoiceRecorder(private val sampleRate: Int = 16_000) {

    @Volatile private var stopFlag = false
    @Volatile private var active = false

    fun isRecording(): Boolean = active

    /**
     * 开始录音。
     *
     * @param target 目标 WAV 文件（父目录会自动创建）
     * @param maxSeconds 硬上限，到点自动停（默认 30 秒，克隆的推荐时长上限）
     * @param onAmplitude 每块的音量（0..1，已做平方根压缩，肉眼看着更线性）
     * @param onFinished 结束回调：(是否成功写出文件, 时长毫秒)
     */
    fun start(
        target: File,
        maxSeconds: Int = 30,
        onAmplitude: (Float) -> Unit = {},
        onFinished: (Boolean, Int) -> Unit = { _, _ -> },
    ) {
        if (active) return
        stopFlag = false
        active = true
        thread(name = "voice-clone-recorder", isDaemon = true) {
            var record: AudioRecord? = null
            val collected = ArrayList<Short>(sampleRate * 6)
            var started = false
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                if (minBuf <= 0) throw IllegalStateException("设备不支持 16 kHz 单声道录音")
                @Suppress("MissingPermission")
                val rec = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, sampleRate / 5 * 2),
                )
                record = rec
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("录音器初始化失败（可能没有麦克风权限）")
                }
                rec.startRecording()
                started = true

                val buf = ShortArray(maxOf(minBuf / 2, 1024))
                val maxSamples = sampleRate * maxSeconds
                while (!stopFlag && collected.size < maxSamples) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) {
                        if (n < 0) break
                        continue
                    }
                    var sum = 0.0
                    for (i in 0 until n) {
                        collected.add(buf[i])
                        val v = buf[i] / 32768.0
                        sum += v * v
                    }
                    // 平方根压缩：线性 RMS 在安静段几乎贴 0，波形条看起来像没在动。
                    onAmplitude(sqrt(sum / n).toFloat().coerceIn(0f, 1f).let { sqrt(it) })
                }
            } catch (t: Throwable) {
                started = false
            } finally {
                runCatching { record?.stop() }
                runCatching { record?.release() }
                active = false
                val durationMs = collected.size * 1000 / sampleRate
                val ok = started && collected.size > sampleRate / 4 && // 至少 250 ms，否则没意义
                    AudioUtil.writeWavShorts(target.absolutePath, collected.toShortArray(), sampleRate)
                onFinished(ok, durationMs)
            }
        }
    }

    /** 请求停止（不阻塞；真正的收尾在后台线程的 finally 里）。 */
    fun stop() {
        stopFlag = true
    }
}
