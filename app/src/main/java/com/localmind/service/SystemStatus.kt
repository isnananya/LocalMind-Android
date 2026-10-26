package com.localmind.service

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.os.Process as AndroidProcess
import java.io.File
import kotlin.math.abs

/**
 * 设备实时状态采样。
 *
 * ## 来源与许可
 *
 * CPU / 内存 / 电池 / 网络 / 图表的**读取思路与字段选择**改编自
 * [AndroidMonitor](https://github.com/TerminalDev-1/AndroidMonitor-Preview)
 * （MIT License, Copyright (c) 2026 Android Monitor contributors）。
 * 它原本依赖 Shizuku/root 才能读 /proc/stat；这里改成**普通应用权限下能读到多少算多少**：
 * 读不到就如实返回 null，界面显示「系统限制」，绝不编一个数字出来。
 *
 * ## 为什么不在 Compose 里直接读
 *
 * 一次采样要读 8~16 个 sysfs 文件、注册一次粘性广播、查一次 ConnectivityManager。
 * 放在主线程每秒做一遍会掉帧；统一放到 IO 线程，界面只消费 [Snapshot]。
 */
class SystemStatus(private val context: Context) {

    data class CpuCore(
        val id: Int,
        val online: Boolean,
        val usagePercent: Float?,
        val curMhz: Int?,
        val maxMhz: Int?,
    )

    data class Snapshot(
        val cpuUsagePercent: Float?,
        val cpuCores: List<CpuCore>,
        val cpuAvgMhz: Int?,
        val cpuMaxMhz: Int?,
        /** 内核计数 / 频率估算 / 系统限制。 */
        val cpuSource: String,
        val ramTotal: Long,
        val ramAvailable: Long,
        val ramUsed: Long,
        val ramPercent: Float,
        val swapTotal: Long,
        val swapFree: Long,
        val storageTotal: Long,
        val storageFree: Long,
        val batteryPercent: Int,
        val batteryCharging: Boolean,
        val batteryTempC: Float?,
        val batteryHealth: String,
        val batteryPowerW: Float?,
        val networkType: String,
        val rxBytesPerSec: Long,
        val txBytesPerSec: Long,
        val uptimeMs: Long,
        /** 本应用占用的 CPU（占全部核心的百分比）。 */
        val appCpuPercent: Float?,
        val soc: String,
        val android: String,
        val deviceName: String,
        val abi: String,
        val screen: String,
        val sampledAt: Long,
    ) {
        val ramFree: Long get() = ramAvailable
        val storageUsed: Long get() = (storageTotal - storageFree).coerceAtLeast(0L)
    }

    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val batteryManager = context.getSystemService(BatteryManager::class.java)

    private val coreIds: List<Int> = parseCpuList(readText(CPU_DIR + "/possible"))
        ?: List(Runtime.getRuntime().availableProcessors()) { it }

    private var previousCpu: Map<String, CpuTimes>? = null
    private var previousRx = -1L
    private var previousTx = -1L
    private var previousNetAt = 0L
    private var previousAppCpuMs = -1L
    private var previousAppAt = 0L

    /** 每秒调用一次；第一次 CPU 采样没有差值，usage 为 null（界面显示"正在采样"）。 */
    fun sample(): Snapshot {
        val cpu = sampleCpu()
        val mem = sampleMemory()
        val battery = sampleBattery()
        val net = sampleNetwork()
        val appCpu = sampleAppCpu(cpu.cores.size)
        val metrics = context.resources.displayMetrics
        val screen = buildString {
            append(metrics.widthPixels).append('×').append(metrics.heightPixels)
            val rate = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) context.display?.refreshRate
                else @Suppress("DEPRECATION") (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
                    .defaultDisplay.refreshRate
            }.getOrNull()
            if (rate != null && rate > 1f) append(" · ").append(String.format(java.util.Locale.US, "%.0f Hz", rate))
        }
        return Snapshot(
            cpuUsagePercent = cpu.overall,
            cpuCores = cpu.cores,
            cpuAvgMhz = cpu.avgMhz,
            cpuMaxMhz = cpu.maxMhz,
            cpuSource = cpu.source,
            ramTotal = mem.total,
            ramAvailable = mem.available,
            ramUsed = mem.used,
            ramPercent = mem.percent,
            swapTotal = mem.swapTotal,
            swapFree = mem.swapFree,
            storageTotal = mem.storageTotal,
            storageFree = mem.storageFree,
            batteryPercent = battery.level,
            batteryCharging = battery.charging,
            batteryTempC = battery.tempC,
            batteryHealth = battery.health,
            batteryPowerW = battery.powerW,
            networkType = net.type,
            rxBytesPerSec = net.rx,
            txBytesPerSec = net.tx,
            uptimeMs = SystemClock.elapsedRealtime(),
            appCpuPercent = appCpu,
            soc = socName(),
            android = "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")",
            deviceName = listOf(Build.MANUFACTURER, Build.MODEL)
                .filter { it.isNotBlank() && it != Build.UNKNOWN }
                .joinToString(" "),
            abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            screen = screen,
            sampledAt = System.currentTimeMillis(),
        )
    }

    // ------------------------------------------------------------------ CPU

    private data class CpuTimes(val idle: Long, val total: Long)
    private data class CpuSample(
        val overall: Float?,
        val cores: List<CpuCore>,
        val avgMhz: Int?,
        val maxMhz: Int?,
        val source: String,
    )

    private fun sampleCpu(): CpuSample {
        val freqPaths = coreIds.map { "$CPU_DIR/cpu$it/cpufreq/scaling_cur_freq" }
        val maxPaths = coreIds.map { "$CPU_DIR/cpu$it/cpufreq/cpuinfo_max_freq" }
        val curKhz = freqPaths.map { readLong(it) }
        val maxKhz = maxPaths.map { readLong(it) }

        val procStat = readText("/proc/stat")
        val times = procStat?.let(::parseProcStat)?.takeIf { "cpu" in it }
        val before = previousCpu
        previousCpu = times

        // ⚠️ 不再用"当前频率 / 最高频率"去估 CPU 占用。
        //
        // 那是 AndroidMonitor 在拿到 Shizuku/root 时的兜底做法，但在普通应用里
        // 频率本身经常读不到，读到的单位也不统一（有的 kHz、有的 Hz），
        // 估出来的数字会像"50% + 0 MHz"这样自相矛盾。读不到内核计数就
        // 如实说"系统限制"，同时把核心数 / 频率单独展示。
        val source = when {
            times != null && before != null -> "内核计数"
            times != null -> "正在采样"
            else -> "系统限制"
        }

        val cores = coreIds.mapIndexed { index, id ->
            CpuCore(
                id = id,
                online = (times?.containsKey("cpu$id") == true) || curKhz[index] != null,
                usagePercent = if (times != null && before != null) {
                    usageBetween(before["cpu$id"], times["cpu$id"])
                } else null,
                curMhz = khzToMhz(curKhz[index]),
                maxMhz = khzToMhz(maxKhz[index]),
            )
        }

        val overall = if (times != null && before != null) {
            usageBetween(before["cpu"], times["cpu"])
        } else null
        val freqs = cores.mapNotNull { it.curMhz }
        return CpuSample(
            overall = overall,
            cores = cores,
            avgMhz = freqs.takeIf { it.isNotEmpty() }?.average()?.toInt(),
            maxMhz = cores.mapNotNull { it.maxMhz }.maxOrNull(),
            source = source,
        )
    }

    private fun parseProcStat(text: String): Map<String, CpuTimes> =
        text.lineSequence()
            .filter { it.startsWith("cpu") }
            .mapNotNull { line ->
                val parts = line.trim().split(WHITESPACE)
                val numbers = parts.drop(1).take(8).map { it.toLongOrNull() ?: 0L }
                if (numbers.size < 5) null
                else parts[0] to CpuTimes(idle = numbers[3] + numbers[4], total = numbers.sum())
            }
            .toMap()

    private fun usageBetween(old: CpuTimes?, new: CpuTimes?): Float? {
        if (old == null || new == null) return null
        val total = new.total - old.total
        if (total <= 0) return null
        val idle = (new.idle - old.idle).coerceIn(0, total)
        return ((1f - idle.toFloat() / total) * 100f).coerceIn(0f, 100f)
    }

    private fun sampleAppCpu(cores: Int): Float? {
        val nowCpu = runCatching { AndroidProcess.getElapsedCpuTime() }.getOrNull() ?: return null
        val now = SystemClock.elapsedRealtime()
        val result = if (previousAppCpuMs >= 0 && previousAppAt > 0) {
            val cpuDelta = (nowCpu - previousAppCpuMs).coerceAtLeast(0)
            val wallDelta = (now - previousAppAt).coerceAtLeast(1)
            (cpuDelta.toFloat() / wallDelta.toFloat() * 100f / cores.coerceAtLeast(1)).coerceIn(0f, 100f)
        } else null
        previousAppCpuMs = nowCpu
        previousAppAt = now
        return result
    }

    // -------------------------------------------------------------- memory

    private data class MemorySample(
        val total: Long,
        val available: Long,
        val used: Long,
        val percent: Float,
        val swapTotal: Long,
        val swapFree: Long,
        val storageTotal: Long,
        val storageFree: Long,
    )

    private fun sampleMemory(): MemorySample {
        val info = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val meminfo = readText("/proc/meminfo")?.let(::parseMeminfo).orEmpty()
        val storage = runCatching { StatFs(Environment.getDataDirectory().path) }
            .getOrElse { StatFs(context.filesDir.path) }
        return MemorySample(
            total = info.totalMem,
            available = info.availMem,
            used = (info.totalMem - info.availMem).coerceAtLeast(0L),
            percent = if (info.totalMem > 0) (info.totalMem - info.availMem) * 100f / info.totalMem else 0f,
            swapTotal = meminfo["SwapTotal"]?.times(1024) ?: 0L,
            swapFree = meminfo["SwapFree"]?.times(1024) ?: 0L,
            storageTotal = storage.totalBytes,
            storageFree = storage.availableBytes,
        )
    }

    private fun parseMeminfo(text: String): Map<String, Long> =
        text.lineSequence().mapNotNull { line ->
            val key = line.substringBefore(':', "").trim()
            val value = line.substringAfter(':').trim().substringBefore(' ').toLongOrNull()
            if (key.isEmpty() || value == null) null else key to value
        }.toMap()

    // ------------------------------------------------------------- battery

    private data class BatterySample(
        val level: Int,
        val charging: Boolean,
        val tempC: Float?,
        val health: String,
        val powerW: Float?,
    )

    private fun sampleBattery(): BatterySample {
        val intent: Intent? = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale
        else runCatching { batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }.getOrDefault(0)
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }?.let { it / 10f }
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            ?.takeIf { it > 0 }?.let { if (it > 100) it / 1000f else it.toFloat() }
        val rawCurrent = runCatching {
            batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        }.getOrDefault(Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE && it != 0 }
        val currentMa = rawCurrent?.let { if (abs(it) >= 10_000) abs(it) / 1000 else abs(it) }
        val health = when (intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "良好"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "过热"
            BatteryManager.BATTERY_HEALTH_DEAD -> "失效"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "过压"
            BatteryManager.BATTERY_HEALTH_COLD -> "过冷"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "异常"
            else -> "未知"
        }
        return BatterySample(
            level = percent.coerceIn(0, 100),
            charging = charging,
            tempC = temp,
            health = health,
            powerW = if (voltage != null && currentMa != null) voltage * currentMa / 1000f else null,
        )
    }

    // ------------------------------------------------------------- network

    private data class NetworkSample(val type: String, val rx: Long, val tx: Long)

    private fun sampleNetwork(): NetworkSample {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - previousNetAt
        fun rate(current: Long, last: Long): Long =
            if (previousNetAt > 0 && elapsed > 0 && last >= 0 && current >= last) {
                (current - last) * 1000 / elapsed
            } else 0L
        val sample = NetworkSample(connectionType(), rate(rx, previousRx), rate(tx, previousTx))
        previousRx = rx
        previousTx = tx
        previousNetAt = now
        return sample
    }

    private fun connectionType(): String {
        val caps = runCatching {
            connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        }.getOrNull() ?: return "离线"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "已连接"
        }
    }

    // -------------------------------------------------------------- helpers

    private fun socName(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val model = Build.SOC_MODEL
            if (model.isNotBlank() && model != Build.UNKNOWN) {
                val maker = if (Build.SOC_MANUFACTURER == "QTI") "Qualcomm" else Build.SOC_MANUFACTURER
                return listOf(maker, model)
                    .filter { it.isNotBlank() && it != Build.UNKNOWN }
                    .joinToString(" ")
            }
        }
        return Build.HARDWARE
    }

    private fun readText(path: String): String? =
        runCatching { File(path).readText() }.getOrNull()

    private fun readLong(path: String): Long? =
        runCatching { File(path).readText().trim().toLong() }.getOrNull()

    /**
     * cpufreq 的单位不统一：Android 常见的是 kHz，个别模拟器 / 内核直接给 Hz。
     * 小于 100 MHz 的"频率"基本可以确定是读坏了，返回 null 让界面显示"—"。
     */
    private fun khzToMhz(value: Long?): Int? {
        if (value == null || value <= 0) return null
        val mhz = when {
            value > 10_000_000 -> value / 1_000_000
            value > 100_000 -> value / 1_000
            else -> return null
        }
        return mhz.toInt().takeIf { it in 50..10_000 }
    }

    companion object {
        private const val CPU_DIR = "/sys/devices/system/cpu"
        private val WHITESPACE = Regex("\\s+")

        /** 解析 "0-7" / "0-3,6" 这种内核 CPU 列表。 */
        internal fun parseCpuList(text: String?): List<Int>? {
            if (text.isNullOrBlank()) return null
            return text.split(',').flatMap { part ->
                val bounds = part.trim().split('-').mapNotNull { it.toIntOrNull() }
                when (bounds.size) {
                    1 -> listOf(bounds[0])
                    2 -> (bounds[0]..bounds[1]).toList()
                    else -> emptyList()
                }
            }.takeIf { it.isNotEmpty() }
        }
    }
}
