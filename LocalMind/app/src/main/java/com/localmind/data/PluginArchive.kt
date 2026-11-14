package com.localmind.data

import android.util.Log
import kotlinx.serialization.json.Json
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipInputStream

private const val TAG = "PluginArchive"

/**
 * 插件 **zip 包**的读取与展开。
 *
 * ## 用户只做一个动作
 *
 * 界面上只有"选一个 zip"。包是什么类型**由程序判断**：
 *
 *  1. 读 `plugin.json`（有的话）拿到 `abilities`
 *  2. 按能力注册表（[PluginAbilities]）查各自需要哪些内容文件
 *  3. 没有清单时反过来 —— 看包里有哪些已知内容文件，推断出能力
 *
 * 用户不需要先想清楚"我这是表情包还是模型库"。那种设计是把程序的活推给人。
 *
 * ## 安全边界
 *
 *  * 只认注册表里登记过的文件（`emoji.json` / `models.json`，以及 `assets/` 下的图片）
 *  * 路径里出现 `..` 或绝对路径直接跳过（防 zip-slip）
 *  * 资源文件也**只取 basename**，不接受任何目录穿越
 *  * 单文件与总解压量都有上限（防 zip 炸弹）
 *  * 不执行任何代码 —— zip 里没有"可执行文件"这个概念
 */
object PluginArchive {

    /** 清单文件名，永远允许。 */
    const val MANIFEST_FILE = "plugin.json"

    /** 展示用的文本文件，允许但只当说明读。 */
    private val DOC_FILES = setOf("README.md", "LICENSE")

    /** 单个文本文件上限。清单类都是几十 KB，1 MB 已经很宽松。 */
    private const val MAX_TEXT_BYTES = 1_000_000L

    /** 单个资源文件上限（表情图片）。 */
    private const val MAX_ASSET_BYTES = 8_000_000L

    /** 所有资源加起来的上限，防 zip 炸弹。 */
    private const val MAX_TOTAL_ASSET_BYTES = 120_000_000L

    /**
     * 单个原生库文件的上限。
     *
     * 生图后端 strip 后是 36 MB，arm64 上更大一些，所以给到 220 MB ——
     * 这不是"随便很大"，而是"比已知的真实产物留一倍余量"。超过这个数
     * 基本可以确定是打包错误或者恶意文件。
     */
    private const val MAX_LIB_BYTES = 220_000_000L

    /** 条目数上限。 */
    private const val MAX_ENTRIES = 512

    private const val MAX_DEX_BYTES = 16L * 1024 * 1024
    private const val MAX_WEB_BYTES = 8L * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    /** 从 zip 里读出来的内容。 */
    data class Contents(
        /** 文本文件：文件名 → 内容。 */
        val files: Map<String, String>,
        /** 资源文件：原始 zip 路径 → 已落盘的本地文件。 */
        val assets: Map<String, File> = emptyMap(),
        /**
         * 原生库：文件名 → 已落盘的本地文件。
         *
         * 和 [assets] 分开是因为两者的**去处不同**：assets 是插件自己的资源
         * （表情图，只有那个插件读），原生库要落到 `filesDir/native/<abi>/`
         * 才能被 `System.load`。混在一起的话安装器得靠文件名猜，很脆。
         */
        val libs: Map<String, File> = emptyMap(),
        /** 代码插件：文件名 → 已落盘的 .dex。 */
        val code: Map<String, File> = emptyMap(),
        /** 网页插件：相对路径 → 已落盘文件（web/ 下的 html/css/js）。 */
        val web: Map<String, File> = emptyMap(),
        /** 被跳过的条目，用来在界面上如实告诉用户"我忽略了什么"。 */
        val skipped: List<String> = emptyList(),
        /**
         * 包里带了、但属于**别的 ABI** 的原生库架构名（例如在 arm64 手机上
         * 装通用包时看到 x86_64）。它们不是错误，只是被按本机架构筛掉了。
         */
        val otherAbis: Set<String> = emptySet(),
    ) {
        fun text(name: String): String? = files[name]
        val manifestText: String? get() = files[MANIFEST_FILE]
        val isEmpty: Boolean get() = files.isEmpty() && assets.isEmpty() && libs.isEmpty() && code.isEmpty() && web.isEmpty()
    }

    /** 这个 zip 路径是插件的资源（`assets/` 下）。 */
    fun isLibPath(zipPath: String): Boolean =
        zipPath.startsWith("lib/") || zipPath.contains("/lib/")

    /** Android 认得的 ABI 目录名。用作通用包的架构选择依据。 */
    private val KNOWN_ABIS = setOf(
        "arm64-v8a", "armeabi-v7a", "armeabi",
        "x86_64", "x86", "mips", "mips64", "riscv64",
    )

    /**
     * 从 `lib/arm64-v8a/liblocalmind-sd.so` 这种路径里取出 ABI。
     * 老式的 `lib/liblocalmind-sd.so` 没有 ABI 段，返回 null（按旧行为处理）。
     */
    private fun libEntryAbi(path: String): String? {
        val parts = path.split('/')
        for (i in 0 until parts.size - 1) {
            if (parts[i] == "lib" && parts[i + 1] in KNOWN_ABIS) return parts[i + 1]
        }
        return null
    }

    /**
     * 读取并展开一个插件 zip。
     *
     * @param assetDir 资源落盘目录（通常是 `filesDir/plugins/<pluginId>/assets`）。
     *   传 null 表示只读文本、不解压资源。
     * @param libDir 原生库落盘目录（`filesDir/native/<abi>/`）。传 null = **不解压** `.so`，
     *   条目会被跳过并记进 [Contents.skipped]。所以调用方漏传这个参数时，
     *   现象是"插件装好了、后端却没出现" —— 而且没有任何报错。
     *   必须传**能被 System.load 的绝对路径**下的目录 —— 这也是它和 [assetDir]
     *   分开的原因。
     * @param libAbi 本机 ABI（如 `arm64-v8a`）。通用包里同时有 `lib/arm64-v8a/`
     *   和 `lib/x86_64/` 时，只解压与它匹配的那一个；其他架构记进
     *   [Contents.otherAbis]。传 null = 保持老行为（谁最后出现谁覆盖）。
     */
    fun read(
        zip: File,
        assetDir: File?,
        libDir: File? = null,
        libAbi: String? = null,
        codeDir: File? = null,
        webDir: File? = null,
    ): Result<Contents> = runCatching {
        require(zip.isFile) { "文件不存在：${zip.name}" }
        require(zip.length() > 0) { "文件是空的" }

        val texts = mutableMapOf<String, String>()
        val assets = mutableMapOf<String, File>()
        val libs = mutableMapOf<String, File>()
        val code = mutableMapOf<String, File>()
        val web = mutableMapOf<String, File>()
        val otherAbis = mutableSetOf<String>()
        val skipped = mutableListOf<String>()
        var entries = 0
        var totalAssetBytes = 0L

        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (++entries > MAX_ENTRIES) {
                    skipped += "（条目超过 $MAX_ENTRIES，后面的已忽略）"
                    break
                }
                if (entry.isDirectory) continue

                val raw = entry.name.replace('\\', '/')

                // zip-slip：路径里有 .. 或绝对路径就不要。我们只按 basename 取用、
                // 本来也不会真的往那个路径写，但显式拒绝能让审计一眼看清。
                if (raw.contains("..") || raw.startsWith("/")) {
                    skipped += "$raw（路径不安全）"
                    continue
                }

                val base = raw.substringAfterLast('/')
                val isAsset = PluginAbilities.isAsset(raw)
                val isLib = isLibPath(raw)
                val isDex = base.endsWith(".dex", ignoreCase = true)
                val isWeb = raw.startsWith("web/") && raw.length > 4
                val isKnownText = base == MANIFEST_FILE || base in DOC_FILES ||
                    PluginAbilities.ALL.any { PluginAbilities.matchesContent(it, raw) }

                if (!isKnownText && !isAsset && !isLib && !isDex && !isWeb) {
                    skipped += raw
                    continue
                }

                // 网页插件：web/ 下的文件按相对路径落盘（html/css/js 可能互相引用）。
                if (isWeb) {
                    if (webDir == null) {
                        skipped += raw + "（未提供网页目录）"
                        continue
                    }
                    if (entry.size > MAX_WEB_BYTES) {
                        skipped += raw + "（超过 " + MAX_WEB_BYTES / 1024 / 1024 + " MB）"
                        continue
                    }
                    val rel = raw.removePrefix("web/")
                    if (rel.contains("..")) {
                        skipped += raw + "（路径不安全）"
                        continue
                    }
                    val out = File(webDir, rel)
                    out.parentFile?.mkdirs()
                    out.outputStream().use { os -> zis.copyTo(os) }
                    web[rel] = out
                    continue
                }

                // 代码插件：dex 直接落盘，安装时**不加载**；加载发生在启用之后。
                if (isDex) {
                    if (codeDir == null) {
                        skipped += raw + "（未提供代码目录）"
                        continue
                    }
                    if (entry.size > MAX_DEX_BYTES) {
                        skipped += raw + "（超过 " + MAX_DEX_BYTES / 1024 / 1024 + " MB）"
                        continue
                    }
                    codeDir.mkdirs()
                    val out = File(codeDir, base)
                    out.outputStream().use { os -> zis.copyTo(os) }
                    if (out.length() <= 0L) {
                        out.delete()
                        skipped += raw + "（空文件）"
                        continue
                    }
                    out.setReadOnly()
                    code[base] = out
                    continue
                }

                // 原生库单独一条路：上限高得多（36 MB 是正常的），而且落到**另一个**
                // 目录（nativeDir，能被 System.load 的路径）。
                if (isLib) {
                    if (libDir == null) {
                        skipped += "$raw（未提供原生库目录）"
                        continue
                    }
                    // 通用包（arm64 + x86_64）按本机 ABI 选一个。以前这里不筛，
                    // 两个同名 .so 会先后写进同一个 libDir，最后一个是别的架构，
                    // 校验失败后被删掉 —— 表现就是「通用包装完，后端反而没了」。
                    val entryAbi = libEntryAbi(raw)
                    if (libAbi != null && entryAbi != null && entryAbi != libAbi) {
                        otherAbis += entryAbi
                        continue
                    }
                    if (entry.size > MAX_LIB_BYTES) {
                        skipped += "$raw（超过 ${MAX_LIB_BYTES / 1024 / 1024} MB）"
                        continue
                    }
                    libDir.mkdirs()
                    val out = File(libDir, base)
                    var written = 0L
                    var tooBig = false
                    out.outputStream().use { os: OutputStream ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = zis.read(buf)
                            if (n <= 0) break
                            written += n
                            if (written > MAX_LIB_BYTES) {
                                tooBig = true
                                break
                            }
                            os.write(buf, 0, n)
                        }
                    }
                    if (tooBig || written <= 0) {
                        out.delete()
                        skipped += "$raw（超过大小上限或为空）"
                        continue
                    }
                    libs[base] = out
                    continue
                }

                if (isAsset) {
                    if (assetDir == null) {
                        skipped += "$raw（未提供资源目录）"
                        continue
                    }
                    if (entry.size > MAX_ASSET_BYTES) {
                        skipped += "$raw（超过 ${MAX_ASSET_BYTES / 1024 / 1024} MB）"
                        continue
                    }
                    var written = 0L
                    var tooBig = false
                    val out: File = File(assetDir, base)
                    assetDir.mkdirs()
                    out.outputStream().use { os: OutputStream ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            val n = zis.read(buf)
                            if (n <= 0) break
                            written += n
                            totalAssetBytes += n
                            if (written > MAX_ASSET_BYTES || totalAssetBytes > MAX_TOTAL_ASSET_BYTES) {
                                tooBig = true
                                break
                            }
                            os.write(buf, 0, n)
                        }
                    }
                    if (tooBig) {
                        out.delete()
                        skipped += "$raw（超过大小上限）"
                        continue
                    }
                    // 用 basename 作为 key：emoji.json 里写的就是文件名。
                    assets[base] = out
                    continue
                }

                // 文本：按声明长度有上限地读。
                // 不用 `zis.readBytes()` —— 对"entry.size 撒谎"的畸形包它会一直读到
                // 流末尾，把后面条目的数据也吞进来。
                val buf = java.io.ByteArrayOutputStream(8192)
                val chunk = ByteArray(16 * 1024)
                var total = 0L
                var tooBig = false
                while (true) {
                    val n = zis.read(chunk)
                    if (n <= 0) break
                    total += n
                    if (total > MAX_TEXT_BYTES) {
                        tooBig = true
                        break
                    }
                    buf.write(chunk, 0, n)
                }
                if (tooBig) {
                    skipped += "$raw（超过 1 MB）"
                } else {
                    texts[base] = buf.toString("UTF-8")
                }
            }
        }
        Log.i(
            TAG,
            "读取 ${zip.name}：文本 ${texts.keys} 资源 ${assets.size} 个 " +
                "原生库 ${libs.size} 个 跳过 ${skipped.size} 项",
        )
        Contents(
            files = texts,
            assets = assets,
            libs = libs,
            code = code,
            web = web,
            skipped = skipped,
            otherAbis = otherAbis,
        )
    }

    /**
     * 按内容判断是不是 zip。文件选择器回来的临时文件常常没有后缀，
     * 而 zip 的魔数比文件名可靠：`PK\x03\x04`。
     */
    fun looksLikeZip(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(4)
            input.read(head) == 4 &&
                head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
                head[2] == 0x03.toByte() && head[3] == 0x04.toByte()
        }
    }.getOrDefault(false)

    /** 应用内"新建插件"用的最小模板：一个能立刻导入、立刻生效的表情包骨架。 */
    val SAMPLE_STICKER_PACK: String = StickerPacks.encode(
        StickerPack(
            packId = "com.example.mystickers",
            packName = "我的表情包",
            author = "你",
            description = "示例：两条。图片放在 zip 的 assets/ 里，image 写文件名。",
            license = "图片素材的授权由你负责填写",
            stickers = listOf(
                StickerEntry(
                    emotion = "开心",
                    image = "happy.png",
                    description = "开心的大笑",
                    aliases = listOf("高兴", "笑"),
                    char = "😄",
                ),
                StickerEntry(
                    emotion = "无语",
                    char = "😐",
                    description = "面无表情的无语",
                    aliases = listOf("服了", "麻了"),
                ),
            ),
            keywords = mapOf("哈哈" to "开心", "唉" to "难过"),
        )
    )

    /** 应用内"新建模型库"的骨架。 */
    val SAMPLE_MODEL_LIBRARY: String = json.encodeToString(
        ModelPackFile.serializer(),
        ModelPackFile(
            version = 1,
            packId = "com.example.mylib",
            packName = "我的模型库",
            author = "你",
            note = "把 url 换成你自己的直链（.gguf）。sizeBytes 可以填 0 —— " +
                "那样下载完成后就不做精确大小校验。",
            models = listOf(
                LocalModel(
                    id = "my-first-model",
                    name = "我的模型",
                    repoId = "owner/repo",
                    fileName = "my-model-Q4_K_M.gguf",
                    sizeBytes = 0,
                    url = "https://hf-mirror.com/owner/repo/resolve/main/my-model-Q4_K_M.gguf",
                    mirrors = listOf("https://huggingface.co/owner/repo/resolve/main/my-model-Q4_K_M.gguf"),
                    license = "见原始仓库",
                    publisher = "owner",
                    family = "Qwen2.5",
                    params = "1.5B",
                    quant = "Q4_K_M",
                    description = "示例条目，改掉它。",
                    tags = listOf("中文"),
                    recommendedRamGb = 4,
                )
            ),
        )
    )
}
