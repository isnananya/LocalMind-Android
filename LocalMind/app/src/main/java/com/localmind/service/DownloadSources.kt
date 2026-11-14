package com.localmind.service

import com.localmind.data.ProxySettings
import com.localmind.data.Storage
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 「这个文件该从哪几个地址下」的统一出口。
 *
 * ## 为什么要有它
 *
 * 项目里原来有三个下载器（GGUF、音色 tarball、sherpa 原生库），每个都自己
 * 拼一遍地址：GGUF 读设置里的代理，另外两个把 `gh-proxy.com` 写死在常量里。
 * 结果是同一个设置页的开关只对模型生效 —— 用户抱怨"开了代理，语音还是下不动"，
 * 原因就在这里。
 *
 * 现在所有下载都走这里：[order] 给出「直连 / 代理」的先后顺序，
 * 并且**尊重设置里那条代理模板**（可以是任意前缀型代理，不限于 gh-proxy）。
 *
 * ## 顺序
 *
 *  * 代理不可用 → 直连，然后内置镜像兜底（老行为）；
 *  * `preferProxy = true` → 代理优先，直连兜底；
 *  * 否则 → 直连优先，代理兜底。
 *
 * 为什么默认直连在前：GitHub Releases 在国内虽然不稳，但一旦连上，
 * 直连的带宽通常比公共代理高；而代理是"连不上就换"的兜底。
 */
object DownloadSources {

    /** 内置兜底镜像（设置里没配代理时用）。 */
    private val BUILTIN_MIRRORS = listOf("https://gh-proxy.com/{url}")

    /**
     * 把一批「原始地址」展开成实际要尝试的完整列表（已去重、保持顺序）。
     */
    fun order(storage: Storage, rawUrls: List<String>): List<String> =
        order(storage.loadProxySettings(), rawUrls)

    fun order(proxy: ProxySettings, rawUrls: List<String>): List<String> {
        val raw = rawUrls.filter { it.isNotBlank() }.distinct()
        if (raw.isEmpty()) return emptyList()

        val proxied = raw.map { proxy.apply(it) }
        val builtin = raw.map { url ->
            BUILTIN_MIRRORS.map { tpl -> tpl.replace("{url}", url) }
        }.flatten()

        val ordered = if (proxy.isUsable()) {
            if (proxy.preferProxy) proxied + raw + builtin else raw + proxied + builtin
        } else {
            raw + builtin
        }
        // distinct 之后还要去掉"代理其实没生效"的情况（apply 返回原值）。
        return ordered.filter { it.isNotBlank() }.distinct()
    }

    /**
     * 下载用的 OkHttp 客户端。
     *
     * `readTimeout` 给 60s 而不是默认 10s：慢速链路上单次读经常超过 10s，
     * 默认值会把"只是慢"误判成"断了"。
     */
    fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .build()

    /** 人可读的速度，给 UI 直接显示。 */
    fun speedText(bytesPerSecond: Double): String = when {
        bytesPerSecond <= 0.0 -> ""
        bytesPerSecond >= 1024 * 1024 -> "%.1f MB/s".format(bytesPerSecond / 1024 / 1024)
        else -> "%d KB/s".format((bytesPerSecond / 1024).toInt())
    }
}
