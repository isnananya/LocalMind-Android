package com.localmind.service

import com.localmind.data.ImageSettings
import com.localmind.data.ImageStyle

/**
 * 把「用户写的一句话 + 风格 + 质量词」拼成扩散模型真的能听懂的提示词。
 *
 * ## 为什么必须拼
 *
 * 以前的实现把用户输入**原样**丢给 sd.cpp，于是：
 *  * 风格选择对扩散模型完全没有作用（它只影响程序化渲染器）；
 *  * 没有质量词 → SD1.5 出图像「十年前的手机照片」；
 *  * 没有负面词 → 水印、畸形手、糊边全靠运气。
 *
 * 这三件事叠在一起，就是「明明装了模型，图还是不精美」。
 *
 * ## 关于中文
 *
 * SD / SDXL 的文本编码器（CLIP）只认识英文。中文提示词在它眼里近似噪声，
 * 这不是"翻译得准不准"的问题，而是**根本没有对应概念**。所以界面上必须
 * 如实提示用英文写；这里不做机器翻译（离线翻译质量比不过用户自己写一句）。
 */
object ImagePrompts {

    /** 通用质量词：SD1.5 吃 tag 这一套，SDXL 也基本兼容。 */
    private const val QUALITY = "masterpiece, best quality, highly detailed, sharp focus, beautiful lighting"

    /**
     * 每个风格对应的英文补充词。
     *
     * 这些是「风格」在扩散链路里**唯一**会生效的地方（程序化渲染器走 styleId），
     * 所以每个都写得足够具体：电影感 = 打光/景深/胶片感，而不是只加一个 "cinematic"。
     */
    private val STYLE_POSITIVE = mapOf(
        ImageStyle.NATURAL to "",
        ImageStyle.CINEMATIC to
            "cinematic still, dramatic lighting, volumetric light, shallow depth of field, " +
                "movie color grading, 35mm anamorphic",
        ImageStyle.ILLUSTRATION to
            "illustration, clean line art, vibrant colors, detailed artwork, " +
                "studio ghibli inspired, soft shading",
        ImageStyle.NEON to
            "cyberpunk, neon lights, glowing signage, night city, wet street reflections, vivid colors",
        ImageStyle.FILM to
            "35mm film photograph, soft natural light, subtle grain, muted tones, Kodak portra",
    )

    /**
     * 默认负面提示词。
     *
     * 这些词覆盖的是手机端小模型最常见的崩坏：低分辨率、糊、水印、文字、
     * 畸形手/多肢体、被裁掉的构图。用户填了自己的负面词就整份替换 ——
     * 不叠加，避免"越删越长"。
     */
    const val DEFAULT_NEGATIVE =
        "lowres, worst quality, low quality, blurry, jpeg artifacts, watermark, signature, text, " +
            "logo, deformed, bad anatomy, bad hands, extra fingers, extra limbs, extra arms, " +
            "disfigured, mutated, cropped, out of frame"

    /** 最终负面提示词：用户填了就用用户的，否则用默认。 */
    fun negative(settings: ImageSettings): String =
        settings.negativePrompt.trim().ifBlank { DEFAULT_NEGATIVE }

    /** 最终正向提示词：用户输入 → 风格词 → 质量词。 */
    fun positive(userPrompt: String, style: ImageStyle): String {
        val parts = buildList {
            add(userPrompt.trim())
            STYLE_POSITIVE[style]?.takeIf { it.isNotBlank() }?.let { add(it) }
            add(QUALITY)
        }
        return parts.filter { it.isNotBlank() }.joinToString(", ")
    }
}
