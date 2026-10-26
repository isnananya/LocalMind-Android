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

一个 zip，根目录放 plugin.json，再按能力放内容文件。类型由程序识别，不需要先告诉它。

- 默认是**数据插件**：JSON / 图片 / 语音 / 主题 / 布局，主程序不执行任何代码。
- **代码插件**：zip 里带 classes.dex，安装前会弹确认，确认后由 DexClassLoader 加载。
- **网页插件**：zip 里带 web/index.html，用 WebView 渲染 HTML/CSS/JS。
- zip 必须扁平：plugin.json 在根目录，不要套一层文件夹。

## 能力总表

- model_library：models.json，模型库（带标签筛选）。
- model_source：config 里的镜像地址。
- emoji_pack：emoji.json + assets/，表情包。
- tts_voice：voices.json，离线音色。
- tts_engine：tts.json，外部语音服务。
- native_backend：lib/ 下的 .so。
- ui_theme：ui.json 或 theme.css，外观令牌。
- ui_layout：layout.json，底栏/首页/整页替换。
- chat_action：actions.json，输入框上方的快捷动作。
- code_plugin：classes.dex，可执行代码（会二次确认）。
- web_screen：web/index.html，HTML/CSS/JS 整页。
- pipeline：pipeline.json，分步流水线。

## 界面布局 layout.json

- tabs：底栏入口，最多 5 个，id 为 chat/models/me/home/voice 等。
- startTab：启动标签。
- screens：插件自己声明的整页，键是 id，值是 title/subtitle/blocks。
- overrides：把内置页面换成插件页。键是内置页面名，值可以是 screen id、code:插件 id、web:插件 id。

内置页面名：home、chat、models、me、voice、plugins、image、pipeline。

### 块类型

- hero：大卡（title/text/icon）。
- actions：两列入口（items）。
- list：分组列表（items）。
- recent：最近对话（limit）。
- status：本机状态。
- text：一段说明。

### 动作目标

- 页面：chat、models、me、home、voice、plugins、image、pipeline。
- 动作：new、settings、plugins_import、debug。
- prompt:文本（填进输入框）、send:文本（直接发送）、copy:文本。
- http(s) 链接、screens 里另一页的 id。

## 聊天动作 actions.json

> { "packId": "com.example.quick", "packName": "快捷动作", "actions": [ { "id": "translate", "label": "译成英文", "prompt": "翻译：{{input}}", "mode": "send" } ] }

- mode：send 直接发送；insert 填进输入框。
- {{input}} 会替换成当前草稿。
- 最多 12 个，应用显示前 8 个。

## 代码插件（classes.dex）

清单里写：
> "abilities": ["code_plugin"], "config": { "entryClass": "com.example.MyPlugin", "viewClass": "com.example.MyView" }

入口类实现 com.localmind.plugin.PluginEntry：

- id()：插件 id。
- onLoad(ctx)：注册动作，ctx 提供 log / toast / registerAction。
- onAction(actionId, input)：返回要发送的文本，或 null。
- 可选 viewClass：继承 android.view.View，构造器参数 Context，作为整页界面宿主。

编译：javac 编译插件类，再用 d8 生成 classes.dex，zip 里放 plugin.json + classes.dex。

示例：plugins-src/code-plugin-tools（含 View 页面与布局）。

## 网页插件（web/index.html）

清单里写 abilities: ["web_screen"]，zip 里放 web/index.html、web/style.css、web/app.js。

JS 桥只有四个动作：

- LM.post('{"action":"send","text":"你好"}')：直接发送。
- action=prompt：填进输入框并进入对话。
- action=toast：弹出提示。
- action=copy：复制文本。

示例：plugins-src/web-plugin-dashboard。

## theme.css：用 CSS 写主题

- 用 --令牌名: 值; 写令牌，例如 --accent: #2E6BE6; --card-corner: 24;
- 深色令牌加 dark- 前缀：--dark-accent: #6C9BFF;
- 支持 ui.json 的全部令牌，包括底栏：tab-bar-shape、tab-bar-margin、tab-bar-lift、tab-bar-blur、tab-bar-tint、tab-bar-indicator。
- tab-bar-shape：capsule / rounded / square。
- tab-bar-indicator：capsule / underline / dot / none。

示例：plugins-src/ui-style-mist/theme.css。

## 安全边界

- 数据插件：最坏情况是配色难看或某块不显示。
- 代码插件：以主程序同等权限运行，安装前必须用户确认；加载/运行异常只停用该插件。
- 网页插件：JS 只能通过四个桥接动作影响主程序，不能直接读文件或执行原生代码。
- 插件来源不可信时不要安装，尤其是带 classes.dex 的。

## 打包与测试

- zip 根目录放 plugin.json + 内容文件（web/、assets/、lib/ 可带目录）。
- 应用内「我的 → 插件 → 导入插件 zip」。
- 装完在「我的 → 插件」里开关；失败会显示原因。

完整字段表见仓库 docs/PLUGIN.md 与 docs/UI-LAYOUT.md。
""".trimIndent()
