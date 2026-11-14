package com.localmind.data

import java.io.File

/**
 * 可以从应用内直接下载的生图模型。
 *
 * ## 为什么全是 GGUF / 单文件
 *
 * 手机上出图的第一约束不是画质，是**内存**和**时间**：
 *
 *  * fp16 的 SD1.5 checkpoint 约 4 GB，加载峰值能到 6 GB —— 12 GB 的手机
 *    在 VAE 解码那一步会被 LMK 直接杀掉；
 *  * GGUF 量化后 SD1.5 是 1.6 GB（Q4_0），能进 6 GB 机的常驻预算；
 *  * 单文件还能走分段下载（[com.localmind.service.MultiPartDownload]），
 *    1.6 GB 走单连接实测只有 13 KB/s，分段能上一个数量级。
 *
 * 源走 `hf-mirror.com`：直连 huggingface.co 在国内手机上基本是超时，
 * 而镜像实测支持 `Range`（206），分段才成立。每条都给多个仓库做备份，
 * 任一仓库被删/被限流都不会让这个入口变成"点了没反应"。
 *
 * ## 为什么 2026-11 把 SD-Turbo 加回来了
 *
 * 上一版这里写着"社区没有可靠的 Turbo GGUF"，那是当时的实测结论。
 * 现在 `gpustack/stable-diffusion-v2-1-turbo-GGUF` 提供了基于
 * stable-diffusion.cpp 转换的 Q4_0 / Q8_0，实测 206 可分段、大小
 * 2,185,459,424 字节。**1 步出图**对手机是质变：同样 512²，SD1.5 要
 * 24 步 × 2（CFG），Turbo 是 1 步 × 1，理论快近 50 倍，VAE 解码反而
 * 变成主要耗时。代价是提示词的精细度和细节不如多步模型 —— 所以它没有
 * 取代 SD1.5，而是并列放在第一位（推荐给"先要能用"的人）。
 */
data class ImageModelEntry(
    val id: String,
    val label: String,
    /** 角标：量化档位 / 倍率。 */
    val badge: String,
    /** 一行说明，说清"选它的理由"。 */
    val note: String,
    val sizeBytes: Long,
    val fileName: String,
    val urls: List<String>,
    /** checkpoint 是扩散主模型；upscaler 是独立的 ESRGAN 权重，两者不能混着选。 */
    val kind: Kind = Kind.CHECKPOINT,
) {
    enum class Kind { CHECKPOINT, UPSCALER }
}

object ImageModels {

    private const val MIRROR = "https://hf-mirror.com"

    private val SD15 = "$MIRROR/gpustack/stable-diffusion-v1-5-GGUF/resolve/main"
    private val SD21 = "$MIRROR/gpustack/stable-diffusion-v2-1-GGUF/resolve/main"
    private val TURBO = "$MIRROR/gpustack/stable-diffusion-v2-1-turbo-GGUF/resolve/main"
    private const val ESRGAN_FILE = "RealESRGAN_x4plus_anime_6B.pth"

    val all: List<ImageModelEntry> = listOf(
        ImageModelEntry(
            id = "sd-turbo-q4",
            label = "SD 2.1 Turbo · 极速",
            badge = "1 步",
            note = "2.0 GB · 1 步出图、不做 CFG，手机上最快；细节比多步模型少一些",
            sizeBytes = 2_185_459_424L,
            fileName = "stable-diffusion-v2-1-turbo-Q4_0.gguf",
            urls = listOf("$TURBO/stable-diffusion-v2-1-turbo-Q4_0.gguf"),
        ),
        ImageModelEntry(
            id = "sd15-q4",
            label = "SD 1.5 · 通用",
            badge = "Q4_0",
            note = "1.6 GB，画质与速度最均衡：12–24 步就能出像样的图",
            sizeBytes = 1_747_190_784L,
            fileName = "stable-diffusion-v1-5-Q4_0.gguf",
            urls = listOf("$SD15/stable-diffusion-v1-5-Q4_0.gguf"),
        ),
        ImageModelEntry(
            id = "sd15-q8",
            label = "SD 1.5 · 高清",
            badge = "Q8_0",
            note = "1.8 GB，量化损失更小，细节和色彩更接近原模型",
            sizeBytes = 1_881_241_504L,
            fileName = "stable-diffusion-v1-5-Q8_0.gguf",
            urls = listOf("$SD15/stable-diffusion-v1-5-Q8_0.gguf"),
        ),
        ImageModelEntry(
            id = "sd21-q4",
            label = "SD 2.1 · 构图",
            badge = "Q4_0",
            note = "2.0 GB，更听得懂长提示词，但 CFG 要开到 8 左右",
            sizeBytes = 2_185_459_424L,
            fileName = "stable-diffusion-v2-1-Q4_0.gguf",
            urls = listOf("$SD21/stable-diffusion-v2-1-Q4_0.gguf"),
        ),
        ImageModelEntry(
            id = "esrgan-x4",
            label = "RealESRGAN · 4× 放大",
            badge = "18 MB",
            note = "给已生成的图做 AI 超分；小图采样 + 放大比直接出大图省几倍内存",
            sizeBytes = 17_938_799L,
            fileName = ESRGAN_FILE,
            urls = listOf(
                "$MIRROR/ximso/RealESRGAN_x4plus_anime_6B/resolve/main/$ESRGAN_FILE",
                "$MIRROR/amd/realesrgan-x4plus-anime-6b/resolve/main/$ESRGAN_FILE",
                "$MIRROR/gemasai/RealESRGAN_x4plus_anime_6B/resolve/main/$ESRGAN_FILE",
            ),
            kind = ImageModelEntry.Kind.UPSCALER,
        ),
    )

    /** 只给「选择生图模型」用；超分权重不能被当成 checkpoint 选中。 */
    val checkpoints: List<ImageModelEntry>
        get() = all.filter { it.kind == ImageModelEntry.Kind.CHECKPOINT }

    val upscalers: List<ImageModelEntry>
        get() = all.filter { it.kind == ImageModelEntry.Kind.UPSCALER }

    fun byId(id: String): ImageModelEntry? = all.firstOrNull { it.id == id }

    /** 按文件名反查 —— 用户在文件管理器里手动丢进来的也能认出来。 */
    fun byFileName(name: String): ImageModelEntry? =
        all.firstOrNull { it.fileName.equals(name, ignoreCase = true) }

    /** 本机已有的第一个超分模型，没有就 null。不认后缀的话用户手动放的文件名对不上。 */
    fun upscalerFile(modelsDir: File): File? =
        upscalers.map { File(modelsDir, it.fileName) }
            .firstOrNull { it.isFile && it.length() > 1_000_000L }
            ?: modelsDir.listFiles { f -> f.isFile && f.name.endsWith(".pth", ignoreCase = true) }
                ?.maxByOrNull { it.length() }
}
