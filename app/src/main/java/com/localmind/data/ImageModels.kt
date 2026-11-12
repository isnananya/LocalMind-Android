package com.localmind.data

/**
 * 可以下载的生图 checkpoint。
 *
 * ## 为什么选这几条、为什么全是 GGUF
 *
 * 手机上出图的第一约束不是画质，是**内存**和**时间**：
 *
 *  * 一个 fp16 的 SD1.5 checkpoint 是 4 GB 上下，加载峰值能到 6 GB ——
 *    12 GB 的手机在 VAE 解码那一步会被 LMK 直接杀掉；
 *  * GGUF 量化后 SD1.5 是 1.6 GB（Q4_0），能进 6 GB 机的常驻预算。
 *
 * 所以这里只放 stable-diffusion.cpp 能直接吃的 GGUF，且都来自同一个
 * 已验证可下载的仓库（`hf-mirror.com` 是国内唯一稳定的 HF 镜像，
 * 直连 huggingface.co 在手机上基本是超时）。
 *
 * ## 为什么不放 SD-Turbo / SDXS
 *
 * 那两类是**一步**模型，速度确实快得多，但 1 步蒸馏的 SDXL-Turbo 级
 * checkpoint 社区里没有可靠的 GGUF 转换版本（实测几个仓库都是 404 或
 * 只有 diffusers 格式），而 diffusers 格式 sd.cpp 吃不了。
 * 与其放一个"点了下不动"的条目，不如只放三个真的能下的 ——
 * [com.localmind.service.ImageEngine] 那边仍然认文件名里的 turbo/lcm/sdxs，
 * 用户自己把这类模型放进 models 目录时步数会自动降到 2–4 步。
 */
data class ImageModelEntry(
    val id: String,
    val label: String,
    /** 角标：量化档位。 */
    val badge: String,
    /** 一行说明，说清"选它的理由"。 */
    val note: String,
    val sizeBytes: Long,
    val fileName: String,
    val urls: List<String>,
)

object ImageModels {

    private const val MIRROR = "https://hf-mirror.com"

    private val SD15 = "$MIRROR/gpustack/stable-diffusion-v1-5-GGUF/resolve/main"
    private val SD21 = "$MIRROR/gpustack/stable-diffusion-v2-1-GGUF/resolve/main"

    val all: List<ImageModelEntry> = listOf(
        ImageModelEntry(
            id = "sd15-q4",
            label = "SD 1.5 · 通用",
            badge = "Q4_0",
            note = "1.6 GB，绝大多数手机的首选：12–24 步就能出像样的图",
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
    )

    fun byId(id: String): ImageModelEntry? = all.firstOrNull { it.id == id }

    /** 按文件名反查 —— 用户在文件管理器里手动丢进来的也能认出来。 */
    fun byFileName(name: String): ImageModelEntry? =
        all.firstOrNull { it.fileName.equals(name, ignoreCase = true) }
}
