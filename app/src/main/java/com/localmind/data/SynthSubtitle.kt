package com.localmind.data

import kotlinx.serialization.Serializable

/**
 * 一句合成音频的「字幕轨」：念了什么、从哪毫秒到哪毫秒。
 *
 * 由 [com.localmind.ui.AppViewModel.synthToWavWithTimeline] 在逐句合成时顺手量出来，
 * 拼成 SRT 导出，或喂给播放器做「当前念到哪句」的高亮。
 *
 * 存的是时间区间而不是单句音频文件：音频本来就按整段 WAV 落盘，字幕只是索引。
 */
@Serializable
data class SynthSubtitle(
    /** 这一句对应的原文（按句切分后的那一句）。 */
    val text: String,
    /** 在本段音频里的起始毫秒（含）。 */
    val startMs: Long,
    /** 结束毫秒（不含）。 */
    val endMs: Long,
)
