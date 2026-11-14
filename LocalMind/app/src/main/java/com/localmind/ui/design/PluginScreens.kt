package com.localmind.ui.design

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.localmind.ui.AppViewModel
import com.localmind.ui.theme.IosTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 代码插件提供的整页 View。
 *
 * 插件在 manifest.config.viewClass 里写类名，类继承 android.view.View 且有
 * (Context) 构造器。这里只负责宿主和生命周期；View 内部怎么做由插件决定。
 */
@Composable
fun PluginCodeScreen(viewModel: AppViewModel, pluginId: String) {
    val ios = IosTheme.palette
    val context = LocalContext.current
    val plugins by viewModel.plugins.collectAsState()
    val plugin = plugins[pluginId]
    val viewClass = plugin?.manifest?.config?.get("viewClass").orEmpty()
    val dex = remember(pluginId) {
        File(File(viewModel.storage.pluginsDir, pluginId), "code/classes.dex")
    }
    var view by remember(pluginId) { mutableStateOf<android.view.View?>(null) }
    var error by remember(pluginId) { mutableStateOf<String?>(null) }

    LaunchedEffect(pluginId, viewClass) {
        if (viewClass.isBlank()) {
            error = "这个插件的清单没有 config.viewClass"
            return@LaunchedEffect
        }
        val made = withContext(Dispatchers.IO) {
            com.localmind.service.PluginRuntime.createView(
                pluginId = pluginId,
                dex = dex,
                viewClass = viewClass,
                optimizedDir = File(context.codeCacheDir, "plugin-" + pluginId.replace(':', '_')),
                context = context,
            )
        }
        if (made == null) error = "插件界面加载失败，检查 classes.dex / viewClass"
        view = made
    }

    Box(Modifier.fillMaxSize().padding(top = 8.dp)) {
        val v = view
        if (v != null) {
            AndroidView(factory = { v }, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                text = error ?: "正在启动插件界面…",
                style = IosTheme.typography.subheadline,
                color = ios.secondaryLabel,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/** 给网页插件用的 JS 桥。只暴露四个动作，避免把整个应用 API 交给 HTML。 */
class WebBridge(
    private val viewModel: AppViewModel,
    private val appContext: android.content.Context,
) {
    @JavascriptInterface
    fun post(json: String) {
        runCatching {
            val o = JSONObject(json)
            when (o.optString("action")) {
                "send" -> {
                    val t = o.optString("text")
                    if (t.isNotBlank()) viewModel.send(t)
                }
                "prompt" -> {
                    val t = o.optString("text")
                    if (t.isNotBlank()) {
                        viewModel.newConversation(com.localmind.data.ChatMode.AI)
                        viewModel.selectTab(com.localmind.ui.Tab.CHAT)
                        viewModel.setDraft(t)
                    }
                }
                "toast" -> viewModel.toast(o.optString("text"))
                "copy" -> {
                    val cm = appContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("LocalMind", o.optString("text")))
                    viewModel.toast("已复制")
                }
            }
        }
    }
}

/**
 * 网页插件整页：加载 filesDir/plugins/<id>/web/index.html。
 *
 * CSS/JS 全部交给 WebView，主程序只提供 JS 桥（send / prompt / toast / copy）。
 * 适合做活动页、可视化面板这类"主程序不值得为它写 Compose"的界面。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PluginWebScreen(viewModel: AppViewModel, pluginId: String) {
    val ios = IosTheme.palette
    val context = LocalContext.current
    val webRoot = remember(pluginId) {
        File(File(viewModel.storage.pluginsDir, pluginId), "web")
    }
    val entry = remember(pluginId) { File(webRoot, "index.html") }
    val bridge = remember(pluginId) { WebBridge(viewModel, context.applicationContext) }

    if (!entry.isFile) {
        Text(
            "网页插件缺少 web/index.html",
            style = IosTheme.typography.subheadline,
            color = ios.secondaryLabel,
            modifier = Modifier.fillMaxSize().statusBarsPadding().padding(24.dp),
        )
        return
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.allowFileAccessFromFileURLs = true
                settings.allowContentAccess = false
                webViewClient = WebViewClient()
                addJavascriptInterface(bridge, "LM")
                loadUrl("file://" + entry.absolutePath)
            }
        },
    )
}
