// Vendored from sherpa-onnx v1.13.8, sherpa-onnx/kotlin-api/Tts.kt (Apache-2.0).
// 原文件版权：Copyright (c) 2023 Xiaomi Corporation。
//
// ⚠️ **这些 data class 的字段一个都不能删。**
//
// 踩过的坑：一开始只留了 VITS 那几个字段（想着"Matcha/Kokoro 我们又不用"），
// 结果是启动时 **JNI DETECTED ERROR + 进程直接 SIGABRT**：
//
//   NoSuchFieldError: no type "Lcom/k2fsa/sherpa/onnx/OfflineTtsMatchaModelConfig;"
//   found and so no field "matcha" could be found in class
//   "com/k2fsa/sherpa/onnx/OfflineTtsModelConfig;"
//
// 原因：libsherpa-onnx-jni.so 是**按字段名反射**读配置对象的（GetObjectField /
// GetIntField），它会无条件读遍 OfflineTtsModelConfig 的每一个字段，而不是只读
// 你这次用的那一种模型。所以 Kotlin 侧的类形状必须和上游一一对应：字段名、类型、
// 顺序都不能动。少一个就是 NoSuchFieldError —— 而且它是 Error，会被 ART 当成致命
// JNI 错误打印并终止进程，runCatching 拦不住。
//
// 加新模型同理：改这里就必须同步确认 .so 的字段名（`strings libsherpa-onnx-jni.so`
// 能看到它找的那些名字）。
//
// 包名同样不能改：JNI 符号是按包名+类名绑定的。
package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager

data class OfflineTtsVitsModelConfig(
    var model: String = "",
    var lexicon: String = "",
    var tokens: String = "",
    var dataDir: String = "",
    var dictDir: String = "", // unused
    var noiseScale: Float = 0.667f,
    var noiseScaleW: Float = 0.8f,
    var lengthScale: Float = 1.0f,
)

data class OfflineTtsMatchaModelConfig(
    var acousticModel: String = "",
    var vocoder: String = "",
    var lexicon: String = "",
    var tokens: String = "",
    var dataDir: String = "",
    var dictDir: String = "", // unused
    var noiseScale: Float = 1.0f,
    var lengthScale: Float = 1.0f,
)

data class OfflineTtsKokoroModelConfig(
    var model: String = "",
    var voices: String = "",
    var tokens: String = "",
    var dataDir: String = "",
    var lexicon: String = "",
    var lang: String = "",
    var dictDir: String = "", // unused
    var lengthScale: Float = 1.0f,
)

data class OfflineTtsZipVoiceModelConfig(
    var tokens: String = "",
    var encoder: String = "",
    var decoder: String = "",
    var vocoder: String = "",
    var dataDir: String = "",
    var lexicon: String = "",
    var featScale: Float = 0.1f,
    var tShift: Float = 0.5f,
    var targetRms: Float = 0.1f,
    var guidanceScale: Float = 1.0f,
)

data class OfflineTtsKittenModelConfig(
    var model: String = "",
    var voices: String = "",
    var tokens: String = "",
    var dataDir: String = "",
    var lengthScale: Float = 1.0f,
)

data class OfflineTtsPocketModelConfig(
    var lmFlow: String = "",
    var lmMain: String = "",
    var encoder: String = "",
    var decoder: String = "",
    var textConditioner: String = "",
    var vocabJson: String = "",
    var tokenScoresJson: String = "",
    var voiceEmbeddingCacheCapacity: Int = 50,
)

data class OfflineTtsSupertonicModelConfig(
    var durationPredictor: String = "",
    var textEncoder: String = "",
    var vectorEstimator: String = "",
    var vocoder: String = "",
    var ttsJson: String = "",
    var unicodeIndexer: String = "",
    var voiceStyle: String = "",
)

data class OfflineTtsModelConfig(
    var vits: OfflineTtsVitsModelConfig = OfflineTtsVitsModelConfig(),
    var matcha: OfflineTtsMatchaModelConfig = OfflineTtsMatchaModelConfig(),
    var kokoro: OfflineTtsKokoroModelConfig = OfflineTtsKokoroModelConfig(),
    var zipvoice: OfflineTtsZipVoiceModelConfig = OfflineTtsZipVoiceModelConfig(),
    var kitten: OfflineTtsKittenModelConfig = OfflineTtsKittenModelConfig(),
    var pocket: OfflineTtsPocketModelConfig = OfflineTtsPocketModelConfig(),
    var supertonic: OfflineTtsSupertonicModelConfig = OfflineTtsSupertonicModelConfig(),

    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "cpu",
)

data class OfflineTtsConfig(
    var model: OfflineTtsModelConfig = OfflineTtsModelConfig(),
    var ruleFsts: String = "",
    var ruleFars: String = "",
    var maxNumSentences: Int = 1,
    var silenceScale: Float = 0.2f,
)

class GeneratedAudio(
    val samples: FloatArray,
    val sampleRate: Int,
) {
    fun save(filename: String) = saveImpl(filename = filename, samples = samples, sampleRate = sampleRate)

    private external fun saveImpl(filename: String, samples: FloatArray, sampleRate: Int): Boolean
}

data class GenerationConfig(
    var silenceScale: Float = 0.2f,
    var speed: Float = 1.0f,
    var sid: Int = 0,
    var referenceAudio: FloatArray? = null,
    var referenceSampleRate: Int = 0,
    var referenceText: String? = null,
    var numSteps: Int = 5,
    var extra: Map<String, String>? = null,
)

class OfflineTts(
    assetManager: AssetManager? = null,
    var config: OfflineTtsConfig,
) {
    private var ptr: Long

    init {
        ptr = if (assetManager != null) {
            newFromAsset(assetManager, config)
        } else {
            newFromFile(config)
        }
        require(ptr != 0L) { "Invalid OfflineTtsConfig: failed to create native OfflineTts" }
    }

    fun sampleRate() = getSampleRate(ptr)

    fun numSpeakers() = getNumSpeakers(ptr)

    fun generate(text: String, sid: Int = 0, speed: Float = 1.0f): GeneratedAudio =
        generateImpl(ptr, text = text, sid = sid, speed = speed)

    fun generateWithCallback(
        text: String,
        sid: Int = 0,
        speed: Float = 1.0f,
        callback: (samples: FloatArray) -> Int,
    ): GeneratedAudio = generateWithCallbackImpl(ptr, text = text, sid = sid, speed = speed, callback = callback)

    fun generateWithConfig(text: String, config: GenerationConfig): GeneratedAudio =
        generateWithConfigImpl(ptr, text, config, null)

    fun free() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    fun release() = finalize()

    private external fun newFromAsset(assetManager: AssetManager, config: OfflineTtsConfig): Long
    private external fun newFromFile(config: OfflineTtsConfig): Long
    private external fun delete(ptr: Long)
    private external fun getSampleRate(ptr: Long): Int
    private external fun getNumSpeakers(ptr: Long): Int
    private external fun generateImpl(ptr: Long, text: String, sid: Int = 0, speed: Float = 1.0f): GeneratedAudio
    private external fun generateWithCallbackImpl(
        ptr: Long,
        text: String,
        sid: Int = 0,
        speed: Float = 1.0f,
        callback: (samples: FloatArray) -> Int,
    ): GeneratedAudio

    private external fun generateWithConfigImpl(
        ptr: Long,
        text: String,
        config: GenerationConfig,
        callback: ((samples: FloatArray) -> Int)?,
    ): GeneratedAudio

    companion object {
        init {
            // 触发探测：APK 里自带就加载，没有就什么都不做（不抛异常）。
            // 从下载目录加载由 SherpaLibraryInstaller 在创建 OfflineTts **之前**做 ——
            // 不是在这里，因为那需要知道 ABI 和文件路径，是上层的事。
            SherpaNativeLoader.isBundled
        }
    }
}
