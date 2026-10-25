package com.localmind.data

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断记录：**最近做了什么** + **崩在哪**。
 *
 * ## 为什么要有它
 *
 * 内测阶段最贵的东西是"用户的描述"。用户说"它卡住了"，而日志在别人手机上，
 * 你既不知道他用的是哪台机器，也不知道他点之前做了什么。这个类就是把这部分
 * 补上：所有关键动作按时间记一份，崩溃时连机型、系统、应用版本、以及**崩溃前
 * 那几十步操作**一起落盘，导出成一个文件发回来就能直接看原因。
 *
 * ## 设计约束
 *
 *  * **绝不能再抛异常**。崩溃处理器本身崩掉会把原始异常一起吞掉，
 *    所以这里每一步都包在 runCatching 里，失败就当没发生。
 *  * **不做依赖注入**。它会被 Application、ViewModel、崩溃处理器三方调用，
 *    构造顺序不确定，所以只接收 Context。
 *  * **文件要小**。操作日志是追加写，超过阈值就砍掉前半段（保留最近的），
 *    免得长期不清理把存储吃满。
 */
object Diagnostics {

    /** 最多留多少条操作。够还原"崩溃前发生了什么"，又不至于让导出文件失控。 */
    private const val MAX_ACTIONS = 400

    /** 最多留多少条崩溃记录。 */
    private const val MAX_CRASHES = 20

    /** 操作日志超过这个长度就裁剪一次。 */
    private const val ACTIONS_TRIM_BYTES = 96 * 1024L

    private val lock = Any()

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private val fullStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun dir(context: Context): File =
        File(context.filesDir, "diagnostics").apply { runCatching { mkdirs() } }

    private fun actionsFile(context: Context) = File(dir(context), "actions.log")
    private fun crashesFile(context: Context) = File(dir(context), "crashes.log")
    private fun sessionFlag(context: Context) = File(dir(context), "session.running")

    // ------------------------------------------------------------------ 操作流水

    /**
     * 记一步操作。界面上的入口（toast、打开页面、点下载…）都走这里。
     *
     * 写成同步追加而不是"攒在内存里定期落盘"：崩溃是随时发生的，攒在内存里的
     * 那部分正好是**最需要的最后几步**。
     */
    fun action(context: Context, text: String) {
        if (text.isBlank()) return
        runCatching {
            synchronized(lock) {
                val f = actionsFile(context)
                f.appendText(stamp.format(Date()) + "  " + text.replace('\n', ' ') + "\n")
                if (f.length() > ACTIONS_TRIM_BYTES) trimActions(f)
            }
        }
    }

    /** 砍掉前半段，保留最近的操作。 */
    private fun trimActions(f: File) {
        val lines = f.readLines()
        val keep = lines.takeLast(MAX_ACTIONS)
        f.writeText(keep.joinToString("\n", postfix = "\n"))
    }

    /** 最近的操作，**新的在前**。 */
    fun actions(context: Context, limit: Int = MAX_ACTIONS): List<String> =
        runCatching {
            val lines = actionsFile(context).takeIf { it.isFile }?.readLines().orEmpty()
            lines.filter { it.isNotBlank() }.takeLast(limit).reversed()
        }.getOrDefault(emptyList())

    // ------------------------------------------------------------------ 崩溃记录

    /**
     * 记一条崩溃。[throwable] 可以为 null —— 原生崩溃、被系统杀掉这类情况
     * 拿不到 Java 异常，但"什么时候、在哪台机器、最后做了什么"照样有价值。
     */
    fun recordCrash(
        context: Context,
        thread: Thread?,
        throwable: Throwable?,
        note: String? = null,
    ) {
        runCatching {
            val sb = StringBuilder()
            sb.append("===== ").append(fullStamp.format(Date())).append(" =====\n")
            sb.append("类型: ").append(note ?: "未捕获异常").append('\n')
            if (thread != null) sb.append("线程: ").append(thread.name).append('\n')
            deviceRows(context).forEach { (k, v) -> sb.append(k).append(": ").append(v).append('\n') }
            if (throwable != null) {
                sb.append("异常: ").append(throwable.javaClass.name)
                    .append(": ").append(throwable.message ?: "").append('\n')
                sb.append("堆栈:\n")
                sb.append(throwable.stackTraceToString().take(6000)).append('\n')
            }
            // 崩溃前最后 40 步操作 —— 这一段的命中率最高。
            val tail = actions(context, 40)
            if (tail.isNotEmpty()) {
                sb.append("崩溃前操作（新的在前）:\n")
                tail.forEach { sb.append("  ").append(it).append('\n') }
            }
            sb.append('\n')

            synchronized(lock) {
                val f = crashesFile(context)
                f.appendText(sb.toString())
                val all = f.readText().split("===== ").filter { it.isNotBlank() }
                if (all.size > MAX_CRASHES) {
                    f.writeText(all.takeLast(MAX_CRASHES).joinToString("") { "===== $it" })
                }
            }
        }
    }

    /** 崩溃记录，**新的在前**。 */
    fun crashes(context: Context, limit: Int = MAX_CRASHES): List<String> =
        runCatching {
            val text = crashesFile(context).takeIf { it.isFile }?.readText().orEmpty()
            text.split("===== ").filter { it.isNotBlank() }.takeLast(limit).reversed()
        }.getOrDefault(emptyList())

    fun clear(context: Context) {
        runCatching {
            synchronized(lock) {
                actionsFile(context).delete()
                crashesFile(context).delete()
            }
        }
    }

    // ------------------------------------------------------- 会话是否正常结束

    /**
     * 标记文件：第一行开始时间，第二行 **1=当时在前台 / 0=在后台**。
     *
     * 为什么必须分前台后台：原来只记「有没有正常退出」，于是**每一次**退出
     *（用户划掉、系统回收、真崩溃）都会在下次启动时记一条「崩溃」。
     * 真机上跑一天能攒出好几条假的 —— 用户打开测试模式看到「崩溃记录 3 条」，
     * 结论是"这应用老闪退"，而实际上一条真的都没有。
     *
     * 区分之后：**前台**消失才可能是真崩溃（值得看堆栈）；后台被回收是系统的
     * 正常行为，只记一行流水，不进崩溃列表。
     */
    private data class Flag(val startedAt: String, val foreground: Boolean)

    private fun readFlag(context: Context): Flag? = runCatching {
        val f = sessionFlag(context)
        if (!f.isFile) return null
        val lines = f.readLines()
        if (lines.isEmpty()) return null
        Flag(lines.getOrElse(0) { "?" }, lines.getOrNull(1)?.trim() == "1")
    }.getOrNull()

    private fun writeFlag(context: Context, startedAt: String, foreground: Boolean) = runCatching {
        val fg = if (foreground) "1" else "0"
        sessionFlag(context).writeText(startedAt + "\n" + fg + "\n")
    }

    /**
     * 启动时调：上一次的标记还在，说明那个进程**没有走到正常退出**。
     *
     * **只在前台消失时才记成崩溃**；后台被系统回收是正常的，记一行流水就够。
     *
     * @return 上一次异常结束的说明（后台回收返回 null，因为它不是故障）。
     */
    fun beginSession(context: Context): String? {
        val previous = readFlag(context)
        writeFlag(context, fullStamp.format(Date()), foreground = true)
        if (previous == null) return null

        if (!previous.foreground) {
            action(context, "上次进程在后台被回收（正常，不是崩溃）")
            return null
        }
        val note = "前台异常退出（很可能是真的崩溃；也可能是被系统强杀或用户划掉）"
        recordCrash(context, null, null, note)
        return note
    }

    /** 前台/后台切换时调，让下一次启动能分清"真崩溃"和"被回收"。 */
    fun setForeground(context: Context, foreground: Boolean) {
        val cur = readFlag(context) ?: return
        if (cur.foreground == foreground) return
        writeFlag(context, cur.startedAt, foreground)
    }

    /** 界面正常销毁时调，撤掉"运行中"标记。 */
    fun endSession(context: Context) {
        runCatching { sessionFlag(context).delete() }
    }

    // ------------------------------------------------------------------ 机型信息

    /**
     * 机型 / 系统 / 应用 三组信息，导出的报告开头就是这几行。
     *
     * 这些是内测反馈里**最常缺**的东西：只说"闪退了"，没有机型没有系统版本，
     * 复现都无从谈起。
     */
    fun deviceRows(context: Context): List<Pair<String, String>> {
        val rows = mutableListOf<Pair<String, String>>()
        runCatching {
            rows += "机型" to (Build.MANUFACTURER + " " + Build.MODEL)
            rows += "设备代号" to Build.DEVICE
            rows += "系统" to ("Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")")
            rows += "系统指纹" to Build.FINGERPRINT
            rows += "ABI" to Build.SUPPORTED_ABIS.joinToString(", ")
            rows += "CPU 核数" to MemoryPlanner.cpuCores().toString()
            val total = MemoryPlanner.totalRamBytes(context)
            val avail = MemoryPlanner.availableRamBytes(context)
            rows += "内存" to (gb(total) + " 总 / " + gb(avail) + " 可用")
            rows += "存储可用" to gb(MemoryPlanner.availableStorageBytes(context.filesDir))
            rows += "应用版本" to (com.localmind.BuildConfig.VERSION_NAME +
                " (" + com.localmind.BuildConfig.VERSION_CODE + ")")
            rows += "构建类型" to (if (com.localmind.BuildConfig.DEBUG) "debug" else "release")
        }
        return rows
    }

    private fun gb(bytes: Long): String =
        String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))

    /**
     * 拼出可以直接发给人看的报告。
     *
     * [extra] 是调用方补的运行时状态（引擎、原生库、后端…）—— 那些东西需要
     * service 层的对象，放在这里会把存储层和推理层绑在一起。
     */
    fun buildReport(
        context: Context,
        extra: List<Pair<String, String>>,
        selfTest: String? = null,
    ): String {
        val sb = StringBuilder()
        sb.append("LocalMind 诊断报告\n")
        sb.append("生成时间: ").append(fullStamp.format(Date())).append("\n\n")

        sb.append("---- 机型与系统 ----\n")
        deviceRows(context).forEach { (k, v) -> sb.append(k).append(": ").append(v).append('\n') }

        if (extra.isNotEmpty()) {
            sb.append("\n---- 运行状态 ----\n")
            extra.forEach { (k, v) -> sb.append(k).append(": ").append(v).append('\n') }
        }

        if (!selfTest.isNullOrBlank()) {
            sb.append("\n---- 自检 ----\n").append(selfTest).append('\n')
        }

        val crashes = crashes(context)
        sb.append("\n---- 崩溃记录（").append(crashes.size).append(" 条，新的在前）----\n")
        if (crashes.isEmpty()) {
            sb.append("（没有记录）\n")
        } else {
            crashes.forEach { sb.append(it).append('\n') }
        }

        val actions = actions(context, 120)
        sb.append("\n---- 最近操作（").append(actions.size).append(" 条，新的在前）----\n")
        if (actions.isEmpty()) sb.append("（没有记录）\n") else actions.forEach { sb.append(it).append('\n') }

        return sb.toString()
    }
}
