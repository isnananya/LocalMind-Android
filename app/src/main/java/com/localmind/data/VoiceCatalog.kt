package com.localmind.data

/**
 * 当前可用的神经音色 —— **由语音插件填充，主程序不带任何音色**。
 *
 * ## 为什么不再是 enum
 *
 * 原来这里是一个 8 项的 `enum class VoiceCatalog`：音色清单写死在 APK 里。
 * 问题和模型库一样 —— 地址会失效、会出新音色、会换镜像，每改一次都要发版。
 *
 * 现在它是一个**运行时注册表**：装一个语音插件（`voices.json`）就往里加一批。
 * 不装任何语音插件时 [all] 是空的，语音页就只会说"还没有音色"。
 *
 * ## 为什么保留这个对象名和这些字段
 *
 * `VoiceCatalog` 有十来个消费点（下载器、语音页、模型分工、人设编辑），
 * 它们要的其实只是"有哪些音色 / 按 id 找一个"。把形状保持不变、
 * 只把**数据来源**换掉，比改十几处调用点安全得多。
 */
object VoiceCatalog {

    @Volatile
    private var entries: List<VoiceEntry> = emptyList()

    /** 当前所有音色（来自所有已启用的语音插件）。 */
    val all: List<VoiceEntry> get() = entries

    /** 有音色可用吗。语音页据此决定显示列表还是空态。 */
    val hasAny: Boolean get() = entries.isNotEmpty()

    /** 用插件提供的清单整体替换。没装语音插件时传空表。 */
    fun setFromPlugins(list: List<VoiceEntry>) {
        // 按 id 去重：两个插件提供同一个音色时，先装的赢（稳定、可预期）。
        entries = list.distinctBy { it.id }
    }

    fun byId(id: String?): VoiceEntry? =
        id?.takeIf { it.isNotBlank() }?.let { key -> entries.firstOrNull { it.id == key } }

    /**
     * 一个音色的下载地址列表，按顺序回退。
     *
     * 直连 GitHub Releases 在本机实测**会随机超时**，所以代理永远是第二候选。
     */
    fun urlsFor(voice: VoiceEntry): List<String> = buildList {
        if (voice.url.isNotBlank()) {
            add(voice.url)
            addAll(voice.mirrors)
        } else {
            add(VoicePacks.defaultUrl(voice.id))
            addAll(voice.mirrors)
        }
        add(VoicePacks.proxyUrl(voice.id))
    }.filter { it.isNotBlank() }.distinct()
}
