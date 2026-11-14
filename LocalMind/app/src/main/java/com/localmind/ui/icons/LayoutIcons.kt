package com.localmind.ui.icons

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 界面布局插件里写的图标 id → 内置图标。
 *
 * 插件只能从这张表里挑（见 `Layouts.ICONS` 的校验）。理由：图标是主程序的视觉资产，
 * 允许插件塞任意图形等于允许它改变整个应用的识别度；而"挑一个意思接近的"已经够用。
 */
object LayoutIcons {
    fun of(id: String): ImageVector? = when (id.trim().lowercase()) {
        "chat", "bubble" -> AppIcons.ChatBubble
        "models", "memory" -> AppIcons.Memory
        "me", "person" -> AppIcons.Person
        "home", "sparkle" -> AppIcons.AutoAwesome
        "voice", "mic" -> AppIcons.GraphicEq
        "breeze", "volume" -> AppIcons.RecordVoiceOver
        "plugins", "extension" -> AppIcons.Extension
        "image" -> AppIcons.Image
        "cloud" -> AppIcons.Cloud
        "bolt" -> AppIcons.Bolt
        "search" -> AppIcons.Search
        "settings", "tune" -> AppIcons.Tune
        else -> null
    }
}
