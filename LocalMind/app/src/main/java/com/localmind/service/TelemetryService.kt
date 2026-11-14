package com.localmind.service

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 匿名使用统计 + 用户主动反馈。
 *
 * 两条通道，都**不带任何令牌**：
 *
 *  1) 计数类（设备数 / 版本分布 / 日活 / 公告已读 / 内测选择）
 *     走公开免登录计数器 Abacus：一个 key 自增一次，服务端拿不到"谁"。
 *
 *  2) 文本类（设备画像 / 崩溃报告 / 意见留言）
 *     走 ntfy 公共主题，App 只发不收；仓库里的 GitHub Actions 每 15 分钟
 *     把主题里的新消息收进 data/inbox.json，后台从那里读。
 *     这样 APK 里没有 GitHub 令牌（否则谁都能反编译拿走去改仓库）。
 *
 * 隐私边界（写死在这里，改之前先想清楚）：
 *  - 只上报机型 / 系统版本 / 运存 / CPU 型号 / 核心数 / 分辨率 / App 版本；
 *  - **不上报** IMEI、MAC、Android ID、序列号、手机号、账号、位置、已装的其它 App；
 *  - `installId` 是本地随机生成的 UUID，只用于后台去重，卸载重装即换新，无法反查设备；
 *  - 崩溃报告与留言**必须用户自己点同意**才发送。
 */
class TelemetryService(private val context: Context) {

    private val prefs = context.getSharedPreferences("telemetry", Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /** 发送不阻塞调用方（大多在主线程被调用）。 */
    private val sender = Executors.newSingleThreadExecutor { r ->
        Thread(r, "telemetry-sender").apply { isDaemon = true }
    }

    // ------------------------------------------------------------ 计数类

    /** 启动时调：设备数 / 版本分布 / 日活。三个 key 各自去重，重复启动不会重复计数。 */
    fun reportLaunch(versionCode: Int) {
        runCatching {
            if (!prefs.getBoolean(KEY_INSTALL, false)) {
                if (hit("devices")) prefs.edit().putBoolean(KEY_INSTALL, true).apply()
            }
            val verKey = "ver_$versionCode"
            if (!prefs.getBoolean(verKey, false)) {
                if (hit(verKey)) prefs.edit().putBoolean(verKey, true).apply()
            }
            val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            if (prefs.getString(KEY_DAY, null) != day) {
                if (hit("active_$day")) prefs.edit().putString(KEY_DAY, day).apply()
            }
        }
    }

    /** 公告被用户看过（点了「知道了」或关掉弹窗）时调一次。 */
    fun reportAnnouncementRead(id: String) {
        if (id.isBlank()) return
        runCatching {
            val key = "ann_${sanitize(id)}"
            if (!prefs.getBoolean(key, false)) {
                if (hit(key)) prefs.edit().putBoolean(key, true).apply()
            }
        }
    }

    /** 更新弹窗上点了「立即更新」(accept) 或「稍后」(reject)，用于看内测接受率。 */
    fun reportUpdateDecision(action: String, fromVersion: String, toVersion: String, channel: String) {
        val a = if (action == "accept") "accept" else "reject"
        runCatching {
            hit("upd_${a}_${sanitize(channel)}")
            // 同时发一条事件，后台能看到"哪个版本被多少人拒了"
            publish("update", JSONObject().apply {
                put("action", a)
                put("from", fromVersion)
                put("to", toVersion)
                put("channel", channel)
            })
        }
    }

    // ------------------------------------------------------------ 设备画像

    /**
     * 每个版本上报一次设备画像（同版本只发一次，卸载重装会再发一次）。
     * 这就是后台"多少人用什么机器"的来源。
     */
    fun reportDevice(versionCode: Int, versionName: String, channel: String) {
        val key = "dev_$versionCode"
        if (prefs.getBoolean(key, false)) return
        sender.execute {
            val ok = runCatching { publish("device", deviceProfile(versionCode, versionName, channel)) }
                .getOrDefault(false)
            if (ok) prefs.edit().putBoolean(key, true).apply()
        }
    }

    /** 设备画像：只有硬件与版本信息，没有任何设备标识。 */
    fun deviceProfile(
        versionCode: Int = com.localmind.BuildConfig.VERSION_CODE,
        versionName: String = com.localmind.BuildConfig.VERSION_NAME,
        channel: String = "stable",
    ): JSONObject {
        val ramMb = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            (mi.totalMem / (1024L * 1024L)).toInt()
        }.getOrDefault(0)
        val dm = context.resources.displayMetrics
        val cpu = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null
        }.getOrNull()?.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
            ?: Build.HARDWARE

        return JSONObject().apply {
            put("model", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            put("brand", Build.BRAND)
            put("android", Build.VERSION.RELEASE)
            put("sdk", Build.VERSION.SDK_INT)
            put("ramMb", ramMb)
            put("cpu", cpu)
            put("cores", Runtime.getRuntime().availableProcessors())
            put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "")
            put("screen", "${dm.widthPixels}x${dm.heightPixels}")
            put("versionName", versionName)
            put("versionCode", versionCode)
            put("channel", channel)
        }
    }

    /** 给「更多」页面显示的本机信息（和上报内容完全一致，不做暗箱）。 */
    fun localDeviceSummary(): String {
        val p = deviceProfile()
        return buildString {
            append(p.optString("model"))
            append(" · Android ").append(p.optString("android"))
            append(" · ").append(p.optInt("ramMb") / 1024).append("GB 运存")
            append(" · ").append(p.optInt("cores")).append(" 核")
        }
    }

    // ------------------------------------------------------------ 用户主动发送

    /** 崩溃报告。只在用户点了「上传」之后调用。 */
    fun sendCrash(stack: String, versionName: String, versionCode: Int, note: String) {
        sender.execute {
            runCatching {
                publish("crash", deviceProfile(versionCode, versionName).apply {
                    put("stack", stack.take(2600))
                    put("note", note.take(200))
                })
            }
        }
    }

    /** 意见留言 / 功能建议。同步返回是否发送成功（要给用户一个明确反馈）。 */
    fun sendFeedback(text: String, contact: String): Boolean = runCatching {
        publish("idea", deviceProfile().apply {
            put("text", text.take(1500))
            put("contact", contact.take(120))
        })
    }.getOrDefault(false)

    // ------------------------------------------------------------ 发送

    /**
     * 往 ntfy 公共主题推一条消息。没有令牌、没有鉴权，任何人都能往这个主题写，
     * 所以后台收数据时只做展示、不当可信来源。
     *
     * 消息体限制 4KB（ntfy 默认上限），超了先截断。
     */
    private fun publish(kind: String, payload: JSONObject): Boolean = runCatching {
        payload.put("kind", kind)
        payload.put("id", installId())
        payload.put("ts", System.currentTimeMillis())
        var body = payload.toString()
        if (body.toByteArray().size > 3800) {
            // 超长时优先砍掉最大的字段（一般就是 stack）
            payload.remove("stack")
            payload.put("stack", "（过长已截断）")
            body = payload.toString()
            if (body.toByteArray().size > 3800) body = body.take(3000)
        }
        val req = Request.Builder()
            .url("$NTFY/$TOPIC")
            .post(body.toRequestBody("text/plain; charset=utf-8".toMediaType()))
            .header("X-Title", "LocalMind")
            .header("X-Tags", "lm")
            .build()
        client.newCall(req).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    /** 本地随机安装 ID：只用于后台去重，和任何系统标识无关。 */
    private fun installId(): String {
        prefs.getString(KEY_INSTALL_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString().replace("-", "").take(16)
        prefs.edit().putString(KEY_INSTALL_ID, id).apply()
        return id
    }

    /** 把公告 id 变成计数器 key 允许的字符（计数器服务只接受 [a-zA-Z0-9_-]）。 */
    private fun sanitize(raw: String): String =
        raw.trim().replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)

    /** 计数器 key 加一。成功返回 true，失败 false（失败不写去重标记，下次还能补）。 */
    private fun hit(key: String): Boolean = runCatching {
        val req = Request.Builder().url("$BASE/hit/$NAMESPACE/$key").build()
        client.newCall(req).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    private companion object {
        const val BASE = "https://abacus.jasoncameron.dev"
        const val NAMESPACE = "localmind-android"
        const val KEY_INSTALL = "reported_install"
        const val KEY_DAY = "reported_day"
        const val KEY_INSTALL_ID = "install_id"

        // 回收通道：App 只写不读，仓库的 Actions 负责收。
        const val NTFY = "https://ntfy.sh"
        const val TOPIC = "lm-inbox-7f3a91c4e5b2d8a6"
    }
}
