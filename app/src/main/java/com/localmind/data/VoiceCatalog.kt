package com.localmind.data

/**
 * 当前可用的神经音色 —— **内置 4 个 + 语音插件提供的**。
 *
 * ## 两层清单
 *
 *  * [BuiltinVoices]：随 APK 带来的 4 个 VITS/Piper 音色，保证空装机也能用；
 *  * 插件（`voices.json`）：可以加新音色、改地址、换镜像。
 *
 * 合并规则写在 [setFromPlugins] 里：**同 id 时插件优先**，其余按"内置在前、
 * 插件在后"的顺序排。这样：
 *  * 下架/改地址只换插件，不用发版（当初把音色全挪进插件的理由，仍然成立）；
 *  * 但"语音页是空的"这个最差的首次体验不会再出现。
 *
 * ## 为什么保留这个对象名和这些字段
 *
 * `VoiceCatalog` 有十来个消费点（下载器、语音页、模型分工、人设编辑），
 * 它们要的其实只是"有哪些音色 / 按 id 找一个"。把形状保持不变、
 * 只把**数据来源**换掉，比改十几处调用点安全得多。
 */
object VoiceCatalog {

    @Volatile
    private var entries: List<VoiceEntry> = BuiltinVoices.all

    /**
     * **在线**音色清单（从云端拉的）。
     *
     * 它的优先级在「内置」之上、「插件」之下：
     *  * 内置是兜底，保证空装机也有几个能用的；
     *  * 在线是"不用装任何东西就能看到的新音色"，新音色上架不用发版；
     *  * 插件是用户自己装的，优先级最高 —— 用户装的东西不能被云端覆盖掉。
     */
    @Volatile
    private var remote: List<VoiceEntry> = emptyList()

    /** 插件层。拆出来是为了让 [rebuild] 能同时处理三层。 */
    @Volatile
    private var plugins: List<VoiceEntry> = emptyList()

    /** 当前所有音色（内置 + 在线 + 所有已启用的语音插件）。 */
    val all: List<VoiceEntry> get() = entries

    /** 有音色可用吗。语音页据此决定显示列表还是空态。内置清单在就永远非空。 */
    val hasAny: Boolean get() = entries.isNotEmpty()

    /**
     * 设置在线清单并重算合并结果。
     *
     * 单独一个入口，是因为在线清单是**异步**拉到的：拿到之前先用「内置 + 插件」，
     * 拿到之后再合一次。不用等网络。
     */
    fun setRemote(list: List<VoiceEntry>) {
        remote = list
        rebuild()
    }

    /**
     * 用插件提供的清单整体替换（内置那一层永远保留）。
     *
     * 顺序：内置音色按声明顺序在前，插件里**额外**的音色接在后面。
     * 同 id 的条目用插件那一份 —— 插件可以改说明、改镜像、改体积，
     * 而且这个覆盖是"重装插件即生效"的，不需要发版。
     */
    fun setFromPlugins(list: List<VoiceEntry>) {
        plugins = list
        rebuild()
    }

    /**
     * 合并三层清单。
     *
     * 合并规则：**同 id 时，后加入的层覆盖先加入的层**（插件 > 在线 > 内置）。
     * 顺序保持「内置在前」，因为内置是默认推荐，用户不该在列表里找不到它。
     */
    private fun rebuild() {
        val builtin = BuiltinVoices.all
        // 覆盖表：先放内置，再被在线覆盖，再被插件覆盖。
        val byId = LinkedHashMap<String, VoiceEntry>()
        builtin.forEach { byId[it.id] = it }
        remote.forEach { byId[it.id] = it }
        plugins.forEach { byId[it.id] = it }

        val merged = buildList {
            // 内置按声明顺序在前（顺序是"推荐度"，不能乱）。
            builtin.forEach { add(byId.getValue(it.id)) }
            val builtinIds = builtin.mapTo(HashSet()) { it.id }
            // 在线里额外的接在后面，保持清单里的顺序。
            remote.forEach { if (it.id !in builtinIds) add(byId.getValue(it.id)) }
            // 插件里额外的接在最后。
            val seenIds = (builtinIds + remote.map { it.id }).toHashSet()
            plugins.forEach { if (it.id !in seenIds) add(byId.getValue(it.id)) }
        }
        // 按 id 再去一次重：插件内部自己也可能重复。
        entries = merged.distinctBy { it.id }
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