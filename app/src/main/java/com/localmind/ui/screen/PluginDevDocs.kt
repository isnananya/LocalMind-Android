package com.localmind.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localmind.ui.components.IosNavigationBar
import com.localmind.ui.components.IosSection
import com.localmind.ui.components.statusBarTopPadding
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.theme.IosTheme

/**
 * 插件开发文档。
 *
 * ## 为什么把它放进应用里，而不是只留一份 `docs/PLUGIN.md`
 *
 * 写插件的人**是在手机上看到「自己写插件」这个入口的**。而 `docs/PLUGIN.md`
 * 在开发机的仓库里 —— 两者之间隔着一个"你得先把仓库 clone 下来"。
 * 结果就是这个入口点了之后只有四个"写出示例"的按钮，用户不知道那些 JSON
 * 该怎么改。
 *
 * 所以这里放一份**自包含**的文档：字段表、能力清单、路由判据、安全边界、
 * 最小示例。它和阿仓库里那份是**同源**的（内容按 `UiTokens.ALL` 那样的
 * 生成思路维护：改字段时两边一起改，见文件末尾的提醒）。
 *
 * 完整版（含逐个字段的设计理由、许可注意事项、提案部分）仍然在
 * `docs/PLUGIN.md` / `docs/PLUGINS.md` / `docs/UI-PLUGIN.md`。
 */
@Composable
fun PluginDevDocsScreen(onBack: () -> Unit) {
    val ios = IosTheme.palette

    Column(
        Modifier
            .fillMaxSize()
            .background(ios.groupedBackground)
    ) {
        IosNavigationBar(
            title = "插件开发文档",
            collapsed = true,
            topInset = statusBarTopPadding(),
            leading = {
                Icon(
                    AppIcons.ArrowBack,
                    contentDescription = "返回",
                    tint = ios.blue,
                    modifier = Modifier
                        .size(23.dp)
                        .clickable(onClick = onBack),
                )
            },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
        ) {
            item {
                DocBody(PLUGIN_DEV_DOC)
            }
        }
    }
}

/**
 * 极简 markdown 渲染：只认这一份文档里真正用到的四种记号 ——
 * `## ` 标题、`### ` 小标题、`- ` 列表、`> ` 引用，以及行内的 **粗体**
 * 和 \`代码\`。
 *
 * 为什么不去引一个 markdown 库：这份文档是**我们自己写的**，记号可控；
 * 而引一个通用解析器只为渲染一屏文字，等于给 APK 加几十 KB 和一类新崩溃面。
 */
@Composable
private fun DocBody(text: String) {
    val ios = IosTheme.palette
    val body = IosTheme.typography.footnote
    val mono = IosTheme.typography.caption1.copy(fontWeight = FontWeight.Medium)

    Column {
        text.split('\n').forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.startsWith("### ") -> {
                    Spacer(Modifier.height(14.dp))
                    Text(line.removePrefix("### "), style = IosTheme.typography.subheadline, color = ios.label)
                }
                line.startsWith("## ") -> {
                    Spacer(Modifier.height(18.dp))
                    Text(
                        line.removePrefix("## "),
                        style = IosTheme.typography.title3,
                        color = ios.label,
                    )
                    Spacer(Modifier.height(2.dp))
                }
                line.startsWith("> ") -> {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(ios.fillSecondary)
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                    ) {
                        Text(line.removePrefix("> "), style = body, color = ios.secondaryLabel)
                    }
                }
                line.startsWith("    ") -> {
                    // 缩进四格 = 代码 / JSON 片段
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(ios.isDark.let { if (it) ios.secondaryGroupedBackground else ios.codeBackground })
                            .padding(10.dp)
                    ) {
                        Text(line.trimStart(), style = mono, color = ios.label)
                    }
                }
                line.startsWith("- ") || line.startsWith("· ") -> {
                    Text(
                        "· " + line.removePrefix("- ").removePrefix("· "),
                        style = body,
                        color = ios.secondaryLabel,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                    )
                }
                line.isBlank() -> Spacer(Modifier.height(8.dp))
                else -> Text(line, style = body, color = ios.secondaryLabel)
            }
        }
    }
}

/**
 * 文档正文。
 *
 * ⚠️ **改字段时请同时改这里和 `docs/PLUGIN.md`**。这一份是手机上唯一能看到
 * 的说明，过期了用户会按错的字段名写插件，然后得到"什么都没改"的报错。
 */
private val PLUGIN_DEV_DOC: String = """
## 插件是什么

一个插件就是一个 **zip**，里面是 **JSON + 资源文件**。

- 主程序**不执行**插件里的任何代码。没有 dex、没有脚本、没有入口函数。
- 所以"装个插件"永远不等于"装个后门"。最坏情况是配色难看或模型地址失效。
- zip 必须是**平的**：`plugin.json` 在根目录，不要套一层文件夹。

装插件只有一个动作：**选一个 zip**。类型由主程序识别，不需要先告诉它这是什么。

## zip 的硬性要求

- `plugin.json` 必须在 **zip 根目录**（不能是 `myplugin/plugin.json`）
- 文件名走**白名单**，只读这些：`plugin.json` `emoji.json` `models.json` `voices.json` `ui.json` `backends.json` `assets/*` `lib/*`
- 拒绝 zip-slip（`../` 路径）、单文件与总量都有上限
- `lib/` 下的 `.so` 会先验 ELF 头（架构不对直接拒），只用于 `native_backend`

## plugin.json

    {
      "id": "com.example.myplugin",
      "name": "我的插件",
      "version": "1.0.0",
      "author": "你",
      "description": "一句话说明这个插件做什么",
      "abilities": ["model_library"],
      "requires": ["network"],
      "manifestVersion": 1
    }

- `id`：稳定唯一，建议反域名。**落库时以它为准**，内容文件里写的 `packId` 会被改写成它。
- `abilities`：见下一节。可以写多个（一个 zip 里同时带音色和主题）。
- 不写 `plugin.json` 也能装：主程序会按"包里有哪些已知文件"反推能力。

## 能力清单（主程序认识这些）

- `emoji_pack` —— 表情包。要 `emoji.json`，可选 `assets/*`（图片梗图优先）
- `model_library` —— 模型库。要 `models.json`
- `model_source` —— 模型下载源（镜像 / 代理地址）
- `tts_voice` —— 语音音色。要 `voices.json`（**只支持本地离线音色**）
- `native_backend` —— 推理后端。要 `backends.json` + `lib/*.so`
- `ui_theme` —— 界面主题。要 `ui.json`（37 个令牌，见应用内「主题」页）
- `remote_provider` / `image_backend` / `web_tool` / `mcp_server` / `persona_pack` / `api_endpoint` —— **占位，还没实现**

> 曾经还有 `cloud_credentials`（云端语音凭据）。它唯一的消费者是腾讯云 TTS，
> 而那整条链路已经按用户要求删掉了。**留着一个没有消费端的能力只会误导插件作者。**

## models.json

    {
      "version": 1,
      "packId": "com.example.mymodels",
      "packName": "我的模型库",
      "author": "你",
      "license": "清单本身 MIT；权重各自遵循原始许可",
      "note": "可选的一句话说明",
      "models": [
        {
          "id": "my-qwen-q4km",
          "name": "Qwen3.5 4B",
          "repoId": "unsloth/Qwen3.5-4B-GGUF",
          "fileName": "Qwen3.5-4B-Q4_K_M.gguf",
          "sizeBytes": 2740937888,
          "url": "https://hf-mirror.com/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_K_M.gguf",
          "mirrors": ["https://huggingface.co/.../Qwen3.5-4B-Q4_K_M.gguf"],
          "license": "Apache-2.0",
          "publisher": "Alibaba Qwen",
          "family": "Qwen3.5",
          "params": "4B",
          "quant": "Q4_K_M",
          "description": "会显示在模型卡片上",
          "tags": ["Qwen", "中文", "推荐"],
          "recommendedRamGb": 8,
          "vision": false,
          "embedding": false,
          "decisionModel": false
        }
      ]
    }

### 模型进哪个分类，由这三个布尔值决定

- 三个都 `false`（或不写）→ **对话模型**
- `vision: true` → **识图**分类
- `embedding: true` → **向量**分类（`/v1/embeddings`）
- `decisionModel: true` → **决策模型**分类（选一个选项 / 是 / 否 / 打分）
- 生图模型：`family` 必须**精确等于** `Stable Diffusion`，否则不会出现在「生图」分类里

> ⚠️ 生成模型（生图 / 识图 / 语音）**别硬塞进对话分类**。一个"能聊"的条目
> 其实是个投影器或音色包时，用户点下去只会得到一个莫名其妙的回复。

### sizeBytes 和 url

- `sizeBytes` 填**精确字节数**（用 `curl -sSI -L <url>` 的 `content-length`）。
  它是下载完成后校验大小的依据；填 0 也能用，但那样只跳过校验。
- `url` 建议指向国内可达的镜像（如 `hf-mirror.com`），`mirrors` 放官方地址兜底，按顺序回退。
- 下载支持断点续传。

## voices.json

     {
       "version": 1,
       "packId": "com.example.myvoices",
       "packName": "我的语音音色",
       "voices": [
         {
           "id": "vits-piper-zh_CN-xiao_ya-medium-int8",
           "label": "小雅（女声 · 普通话）",
           "description": "13 MB，完全离线",
           "badge": "轻量",
           "sizeBytes": 14016124,
           "sampleText": "你好，我是小雅。今天想聊点什么？"
         }
       ]
     }

> ⚠️ **`id` 必须等于官方 tarball 的名字**。下载器目前按
> `https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/<id>.tar.bz2`
> 拼地址，`url` / `mirrors` 字段**会被忽略**。

- 只有 **VITS** 系列当前能直接加载（主程序只构造了 `vits` 配置）。
- Matcha / Kokoro / ZipVoice 的类在 `.so` 里，但主程序**没写构造分支**，
  而且 Matcha 是"两个 onnx"、Kokoro 还要 `voices.bin` —— 现在装了也发不出声。
- `badge` 建议：`轻量` / `角色` / `多说话人` / `中英混读`。

## ui.json（界面主题）

界面插件是**声明式令牌**，不是代码 —— 这是刻意的：能塞代码就等于装后门。

- 令牌白名单 **37 个**（颜色 24 个 + 圆角 / 字号 / 标签栏等），全部有消费端。
- 深色用 `darkTokens` 覆盖同名浅色键，**不要**找 `*Dark` 结尾的键（没有那样的键）。
- 颜色写法：`#RRGGBB` / `#AARRGGBB`（前两位是透明度）/ `rgba(255,107,53,0.5)`
- 值越界会被**夹到区间**而不是报错；不认识的键会被如实列出来。
- 完整清单看「主题」页的「正在生效的覆盖」，或点下面的「写出界面主题示例」，
  它会在插件目录里生成一份 `可改的令牌.txt`（由代码生成，不会过时）。

## 安全边界

- 插件**不能**执行代码、不能申请权限、不能读你的密钥。
- 插件的网络访问由主程序代劳：只有 `requires` 里声明了 `network` 的插件才会去下载。
- 主题只能改外观（改不了应用名伪装成别的应用、改不了启动图标）。
- 装之前可以解开 zip 看 —— 里面就是 JSON 和图片，没有任何可执行内容。

## 调试

- 装不上：安装结果里会写**中文原因**（缺 packId、tabs 里有不认识的页面……）。
- 装上了没变化：模型页看「语音 / 生图 / 识图」分类对不对；主题页看"不认识的令牌"那一行。
- 不用点 UI 的装法（脚本化验证）：

    adb push my.zip /data/local/tmp/
    adb forward tcp:18080 tcp:8080
    curl -X POST http://127.0.0.1:18080/v1/debug/plugin \
      -H 'Content-Type: application/json' \
      -d '{"path":"/data/local/tmp/my.zip"}'

  （需要先在「我的 → 本机 API 服务」里把服务打开。）

## 想做出完整的插件？

下面四个按钮会在插件的私有目录里**写出可以直接压成 zip 的骨架**，
改完 JSON 重新压一遍就是一个新插件。这是最快的入手方式。
""".trimIndent()
