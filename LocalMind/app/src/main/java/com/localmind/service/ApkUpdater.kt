package com.localmind.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 应用内更新：**自己下 APK，自己拉起安装器**。
 *
 * ## 为什么要从"开浏览器"改掉
 *
 * 1.0.10 之前点「去更新」只是 `ACTION_VIEW` 打开 GitHub 页面 —— 用户还要在
 * 浏览器里再点一次下载、等一个 60 MB 的文件、再从通知栏里点开安装。
 * 中间任何一步走错（页面打不开、下载被浏览器中断、找不到文件）都变成
 * "更新不了"的反馈。而 App 自己其实有全部条件：网络、进度、存储、权限。
 *
 * 现在的路径：点「立即更新」→ 这里多源下载（加速源优先，见 [GitHubMirror]）
 * → 校验包名和 versionCode → 直接拉起系统安装器。用户只需要最后点一下「安装」。
 *
 * ## 为什么必须校验
 *
 * 加速源是第三方中转。拿到一个 HTML 错误页、或者被换掉的包，如果不校验就
 * 丢给安装器，轻则安装失败，重则装进一个同一签名的替换包。这里检查：
 *  * 是不是能解析成 APK；
 *  * 包名是不是本应用；
 *  * versionCode 是不是比当前高。
 * 签名由系统安装器自己校验（同签名才能覆盖安装），这里不重复。
 */
class ApkUpdater(private val context: Context) {

    /** 更新流程的状态机。UI 只读这个。 */
    sealed interface State {
        /** 空闲（没有在下载）。 */
        object Idle : State

        /** 正在下载。[percent] 为 -1 表示总长度未知。 */
        data class Downloading(
            val version: String,
            val percent: Int,
            val received: Long,
            val total: Long,
            val bytesPerSecond: Double,
            val sourceIndex: Int,
            val sourceCount: Int,
        ) : State

        /** 下载并校验完成，等安装。 */
        data class Ready(val version: String, val file: File) : State

        /** 全部源都失败。 */
        data class Failed(val version: String, val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .build()

    private val dir: File
        get() = File(context.filesDir, "updates").apply { mkdirs() }

    val isRunning: Boolean get() = job?.isActive == true

    /**
     * 开始下载。
     *
     * @param version   版本名（只用于文件名与 UI）
     * @param rawUrls   原始地址（可以多个，都会被展开成加速源）
     */
    fun download(version: String, rawUrls: List<String>) {
        if (isRunning) {
            Log.i(TAG, "已有下载在跑，忽略重复请求")
            return
        }
        val urls = GitHubMirror.expand(rawUrls.filter { it.isNotBlank() })
        if (urls.isEmpty()) {
            _state.value = State.Failed(version, "没有可用的下载地址")
            return
        }
        // LAZY 启动：先把 job 赋上值再跑，否则协程体里读 job 可能还是 null。
        job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            runDownload(version, urls)
        }
        job?.start()
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = State.Idle
    }

    /** 用户关掉更新弹窗：停下载，别让它在后台白耗流量。 */
    fun reset() {
        if (isRunning) cancel() else _state.value = State.Idle
    }

    init {
        // 上次下完没装成（用户点了稍后 / 安装器被打断）的包，这次启动直接认回来，
        // 免得再下 60 MB。
        runCatching { restoreDownloaded() }
    }

    private fun restoreDownloaded() {
        val apk = dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("localmind-") && it.name.endsWith(".apk") }
            ?.maxByOrNull { it.lastModified() }
            ?: return
        val version = apk.name.removePrefix("localmind-").removeSuffix(".apk")
        if (version.isBlank()) return
        if (verify(apk, version) != null) {
            Log.i(TAG, "复用上次下载好的安装包：$version")
            _state.value = State.Ready(version, apk)
        } else {
            runCatching { apk.delete() }
        }
    }

    private suspend fun runDownload(version: String, urls: List<String>) = withContext(Dispatchers.IO) {
        val target = File(dir, "localmind-$version.apk")
        val part = File(dir, target.name + ".part")
        // 上一次留下的残包会占几十 MB，先清掉（留着也没用）。
        runCatching {
            dir.listFiles()?.forEach { f ->
                if (f.name != target.name && f.name != part.name) f.delete()
            }
        }

        var lastError = "下载失败"

        for ((index, url) in urls.withIndex()) {
            if (!isActive) return@withContext
            part.delete()
            val ok = runCatching { fetch(url, part, version, index, urls.size) }
                .onFailure { lastError = it.message ?: "下载失败" }
                .getOrDefault(false)
            if (!ok) {
                part.delete()
                continue
            }
            // 只有"看起来真是个能装的包"才继续，否则换下一个源。
            val verified = verify(part, version)
            if (verified != null) {
                target.delete()
                val moved = part.renameTo(target) ||
                    runCatching { part.copyTo(target, overwrite = true).let { part.delete(); true } }
                        .getOrDefault(false)
                if (moved) {
                    Log.i(TAG, "APK 下载完成：$url → ${target.length()} 字节")
                    _state.value = State.Ready(version, target)
                    return@withContext
                }
                lastError = "无法写入安装包"
            } else {
                lastError = "下到的不是有效的 $version 安装包"
            }
            part.delete()
        }
        _state.value = State.Failed(version, lastError)
    }

    private fun fetch(
        url: String,
        out: File,
        version: String,
        index: Int,
        count: Int,
    ): Boolean {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "LocalMind-Updater")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "HTTP ${resp.code}：$url")
                return false
            }
            val body = resp.body ?: return false
            val total = body.contentLength()
            val started = System.currentTimeMillis()
            var written = 0L
            var lastEmit = 0L

            body.byteStream().use { input ->
                out.outputStream().use { sink ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        if (job?.isActive != true) return false
                        val n = input.read(buf)
                        if (n <= 0) break
                        sink.write(buf, 0, n)
                        written += n
                        // 安装包 60 MB，超过 300 MB 一定是错误页或者被喂了垃圾。
                        if (written > 300L * 1024 * 1024) return false

                        val now = System.currentTimeMillis()
                        if (now - lastEmit > 180) {
                            lastEmit = now
                            val elapsed = (now - started).coerceAtLeast(1)
                            val speed = written * 1000.0 / elapsed
                            val percent = if (total > 0) {
                                (written * 100 / total).toInt().coerceIn(0, 100)
                            } else -1
                            _state.value = State.Downloading(
                                version = version,
                                percent = percent,
                                received = written,
                                total = total,
                                bytesPerSecond = speed,
                                sourceIndex = index + 1,
                                sourceCount = count,
                            )
                        }
                    }
                }
            }
            // 声明的长度和实际长度差太多 → 是个被截断的包。
            if (total > 0 && written < total * 0.99) {
                Log.w(TAG, "下载不完整：$written / $total")
                return false
            }
            return written > 1024 * 1024
        }
    }

    /**
     * 校验下载到的文件。通过返回 PackageInfo 的 versionCode，否则 null。
     *
     * 只读 archive info，不安装 —— 安装是用户的事。
     */
    private fun verify(file: File, version: String): Int? {
        if (!file.isFile || file.length() < 1024 * 1024) return null
        val info = runCatching {
            context.packageManager.getPackageArchiveInfo(
                file.absolutePath,
                android.content.pm.PackageManager.GET_ACTIVITIES,
            )
        }.getOrNull() ?: return null

        if (info.packageName != context.packageName) {
            Log.w(TAG, "包名不符：${info.packageName}")
            return null
        }
        @Suppress("DEPRECATION")
        val code = info.versionCode
        if (code <= com.localmind.BuildConfig.VERSION_CODE) {
            Log.w(TAG, "versionCode 没有提升：$code ≤ ${com.localmind.BuildConfig.VERSION_CODE}")
            return null
        }
        Log.i(TAG, "校验通过：$version (code=$code)")
        return code
    }

    /**
     * 拉起系统安装器。
     *
     * 返回 false 有两种情况：文件没了，或者用户还没给「安装未知应用」权限
     * （这时已经跳到系统设置页，用户回来再点一次即可）。
     */
    fun install(ready: State.Ready): Boolean {
        val file = ready.file
        if (!file.isFile) {
            _state.value = State.Failed(ready.version, "安装包已不在（可能被清理）")
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { Log.w(TAG, "打不开安装权限页：${it.message}") }
            return false
        }

        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrElse {
            Log.w(TAG, "FileProvider 拿不到 uri：${it.message}")
            _state.value = State.Failed(ready.version, "无法共享安装包")
            return false
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.onFailure {
            Log.w(TAG, "拉起安装器失败：${it.message}")
            _state.value = State.Failed(ready.version, "打不开系统安装器")
        }.getOrDefault(false)
    }

    private companion object {
        const val TAG = "ApkUpdater"
    }
}
