# LocalMind 界面插件（UI Theme）开发规范

> **一句话**：界面插件 = 一个扁平 zip + 一份 `ui.json`。
> 它是**纯数据**，不执行任何代码，所以"装个主题"永远不等于"装个后门"。
>
> 这份文档是**契约**。字段名、默认值、取值范围、回退行为、报错原文全部逐条
> 对照源码写成；**每一项都标注了"今天是否真的接进界面"**。
> 你不需要读 Kotlin 就能做出一个正确的主题插件。

---

> ## ⚠️ 2026-10-15：令牌体系已经重做，§4 请看新版
>
> 你如果是从旧版文档过来的，只需要记住三件事：
>
> 1. **颜色令牌从 5 个生效扩到 31 个全部生效**。文字色、分隔线、导航栏、
>    卡片描边、聊天气泡、输入框、代码块 —— 全部有对应的令牌了。
>    旧文档 §4.1 那张"只有 7 个接线"的表**已经作废**。
> 2. **`accentDark` / `groupedBackgroundDark` / `cardBackgroundDark` 这三个键被删了**。
>    它们从来没有生效过，而且设计是错的：深色就该用 `darkTokens` 覆盖同名浅色键。
>    写了这三个键现在会被列进"不认识的令牌"。
> 3. **`fontScale` / `bodySize` / `sheetCorner` / `tabStyle` / `showTabBarLabels` / `navTitleAlign` / `appName` 现在都真的生效了**。
>    旧文档里说的"改了没反应"是当时的事实，现在是过时结论。
>
> 另外：主题**不再只靠 zip**。应用里「外观 → 主题」有 6 个内置预设
> （暖橙 / 极光 / 纸张 / 深海 / **二次元** / **QQ 经典**）和一个微调面板，
> 点一下就换风格。预设和插件写的是同一份覆盖表，所以两者可以叠加。
>
> 主题商店里还有 3 个**内置主题包**（樱雪物语 / 极光夜航 / QQ经典蓝），
> 不联网也能装 —— 商店不会因为没网就变成空白页。

---

## 1. 这份文档是什么 / 读者是谁

### 1.1 读者

| 你是 | 该看哪几节 |
| --- | --- |
| **主题作者**（会改 JSON，不想写代码） | §2 → §7 → §10 → §11 → §12 |
| **设计师**（想知道"我到底能控制哪些像素"） | §4 令牌总表 + **§15 附录：全部槽位清单** |
| **主程序维护者**（要往上加字段） | §13 安全模型 + **§14 提案** |
| **写 Kotlin 工具的人**（示例生成器、校验脚本） | §13.5 的**嵌套块注释陷阱**（会静默吞掉你半个文件） |

### 1.2 这份文档和 `docs/PLUGIN.md` 的关系

| 文档 | 范围 |
| --- | --- |
| `docs/PLUGIN.md` | **整个插件系统**：zip 结构、`plugin.json`、11 种能力、安装/卸载、排错 |
| **本文** | **只有 `ui_theme` 这一种能力**，但写到"可以直接照着写"的精度 |

两者冲突时以本文为准（本文的"已接线"列是**逐个 grep 消费端**核对过的，
`PLUGIN.md` §8.3 的同一列有至少两处过时/错误，见 §4.3）。

### 1.3 本文的两种内容，务必分清

> **【已实现】** = 当前源码就是这个行为，你可以依赖它。
>
> **【提案 / roadmap】** = **还没写**。第十四节整节都是提案，正文里出现
> "提案"字样的段落也一律是提案。**不要按提案写 `ui.json` —— 今天不会被应用。**

---

## 2. 插件是什么、怎么装

### 2.1 用户只做一个动作

```
我的 → 插件 → 右上角「+」 → 选一个 zip
```

**没有"这是主题还是表情包"的选择。** 主程序按 zip 内容自己判断：

1. 有 `plugin.json` → 按它声明的 `abilities` 去能力注册表查要读哪些文件；
2. 没有清单 → 看包里出现了哪些已知内容文件，反推能力（有 `ui.json` → `ui_theme`）。

安装成功后，`ui_theme` 类插件的初始状态是 **`ENABLED`（启用中）**，
所以主题**装完立刻生效**，不需要再去开开关。

### 2.2 zip 必须是**扁平**的

```
暖橙主题.zip                  ← 用户选中的就是这个文件
├── plugin.json               ← 清单（强烈建议写）
├── ui.json                   ← 主题内容（本节的主角）
├── README.md                 ← 可选，给人看的说明
└── assets/                   ← 可选，资源目录（今天对 ui_theme 无用，见 §14）
```

**错误写法**（压了文件夹）：

```
暖橙主题.zip
└── 暖橙主题/                 ← 多出来的这一层
    ├── plugin.json
    └── ui.json
```

> **诚实说明**：当前读取是**按文件名（basename）**取用的，所以套一层文件夹
> **多半仍然能装上**。但不要依赖它：两个子目录里的同名文件会互相覆盖
> （**后面读到的赢**，没有任何提示）。扁平是唯一被文档、示例生成器、
> `plugins-src/ui-theme/` 和测试使用的形态。

### 2.3 读取白名单与上限【已实现】

只有这些路径会被处理（`PluginArchive.read`）：

| zip 里的路径 | 处理 |
| --- | --- |
| `plugin.json` | 读成文本（清单） |
| `ui.json`（以及 `emoji.json` / `models.json` / `voices.json`） | 读成文本 |
| `README.md` / `LICENSE` | 读成文本（允许，**当前界面没有展示入口**） |
| 任意路径里含 `assets/` 的文件 | 解压到 `filesDir/plugins/<插件 id>/assets/`，**只取 basename** |
| 其它一律 | 忽略，并在安装结果里计数：`（忽略了 N 个无关文件）` |

| 上限 | 数值 | 超限表现 |
| --- | --- | --- |
| 单个文本文件 | **1 MB** | 该文件被丢弃（`ui.json` 直接变成"不存在"→ 主题不会装上） |
| 单个资源文件 | **8 MB** | 该图被丢弃 |
| 全部资源合计 | **120 MB** | 超出的被丢弃（防 zip 炸弹） |
| zip 条目数 | **512** | 第 512 个之后的条目忽略 |

路径安全：含 `..` 或以 `/` 开头的条目被**跳过**并计入"跳过项"（防 zip-slip）。
**`ui.json` 超 1 MB 是最容易被忽略的一条** —— 主题只有几十行，正常不会撞到。

### 2.4 `plugin.json`（`ui_theme` 的最小写法）

```json
{
  "id": "dev.localmind.theme.warm",
  "name": "暖橙主题",
  "version": "1.0.0",
  "author": "你的名字",
  "description": "把主色换成暖橙、卡片圆角放大到 18。",
  "abilities": ["ui_theme"],
  "manifestVersion": 1
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | ✅ | 稳定唯一 id，建议反向域名。**只能含字母、数字、`.`、`-`、`_`** |
| `name` | ✅ | 插件列表里显示的名字 |
| `version` | | 自由字符串，默认 `"1.0.0"`。**主程序不解析它，不参与比较** |
| `author` / `description` | | 显示用 |
| `abilities` | ✅ | 至少一个，且至少一个被主程序认识，否则拒绝安装 |
| `requires` | | 如 `["network"]`，会在安装前翻译成"需要联网"给人看 |
| `config` | | `Map<string,string>`。**值必须是字符串**，`ui_theme` 不使用它 |
| `manifestVersion` | | 默认 `1`。清单格式版本 |

**`abilities` 里的未知 id**：只要还有至少一个认识的 id，安装照样成功，
未知的那个会被忽略（插件页标签显示为灰色）。全部不认识才会失败：
`这个插件声明的能力当前版本都不认识：…`。

### 2.5 重装/更新

重装同一个 `id` 的插件**不会重置用户的启用开关**（保留原 `state`）。
所以"改完 `ui.json` 重新压 zip 再导入"是一条安全的迭代路径。

---

## 3. `ui.json` 完整字段参考

【已实现】顶层字段就是下面这 12 个。JSON 是 **kotlinx.serialization** 解析的，
配置为 `ignoreUnknownKeys = true` / `isLenient = true`：

* **不认识的键被静默忽略**（顶层字段级别）—— 拼错 `"token"` 而不是 `"tokens"`
  不会报语法错，只会得到"什么都没改"的拒绝；
* 但 **`tokens` / `darkTokens` 里的未知键会被如实报出来**（见 §3.3）。

| 字段 | 类型 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `version` | int | | `1` | **当前源码里没有任何地方读它。** 写了不报错，也不起任何作用 |
| `packId` | string | ✅ | `""` | 包 id。**会被强制改写**，见下面 ⚠️ |
| `packName` | string | ✅ | `""` | 显示名。缺失 → 拒绝安装 |
| `author` | string | | `""` | 显示用 |
| `description` | string | | `""` | 主题页会显示（最多 2 行） |
| `license` | string | | `""` | 显示用 |
| `home` | string | | `""` | 当前无展示入口 |
| `tokens` | Map&lt;string,string&gt; | | `{}` | 浅色令牌。**值必须是字符串** |
| `darkTokens` | Map&lt;string,string&gt; | | `{}` | 深色令牌，**键名用浅色那个键**（见 §6） |
| `tabs` | string[] | | `[]` | 标签栏显示哪几个、什么顺序。只能是 `chat`/`models`/`me` |
| `startTab` | string | | `""` | 首页标签页。只能是上面三个之一 |
| `appName` | string | | `""` | 覆盖应用标题（导航栏那个名字） |

> ⚠️ **`packId` 只是给你自己看的。**
> 安装时主程序会执行 `theme.copy(packId = manifest.id)`
> （`PluginInstall.kt` 第三步落库），所以**最终存下来的 `packId` 永远等于
> `plugin.json` 的 `id`**。`ui.json` 里的 `packId` 只需要"非空"以通过校验。
> 主题页的"使用中"判断也是按这个改写后的 id 比的。

### 3.1 顶层字段的取值规则细节

| 字段 | 规则 |
| --- | --- |
| `tokens` / `darkTokens` | `Map<String,String>`。**值必须是 JSON 字符串**：`"18"` 对，`18` 直接解析失败 |
| `tabs` | 数组元素必须在 `{chat, models, me}` 里，**区分大小写**；空了就是默认三个；**允许重复**（不会被拒，但渲染端今天不读它） |
| `startTab` | 必须精确等于 `chat`/`models`/`me`；空串合法（= 用 `chat`） |
| `appName` | 任意字符串；空串 = 用默认标题 |

### 3.2 "什么都没改"会被拒

`tokens`、`darkTokens`、`tabs`、`appName` **四个全空** → 安装失败：

```
界面主题：ui.json 什么都没改（tokens / darkTokens / tabs / appName 都是空的）
```

注意 `startTab` **不在**这个判定里 —— 只写 `startTab` 的主题会被拒。

### 3.3 未知令牌会被报出来【已实现】

你写了白名单之外的键（`"primaryColor"`、`"corner"`、拼错的 `"cardCorner "`），
**不会生效，但会被告知**。安装结果的文案是：

```
界面主题 我的主题（忽略了不认识的令牌：primaryColor、corner）
```

主题设置页也会在主题行下面显示 `· 忽略 N 项不认识的`，并在"改了什么"表格末尾
用橙色列出键名。**这是刻意的**：静默接受会让你以为自己改生效了。

拼写对比是在**去掉首尾空白之后**做的（`raw.trim()`），所以 `" 18 "` 和 `"18"`
等价 —— 但**键名**不做 trim，`" cardCorner"` 会被当成未知键。

---

## 4. 令牌总表

### 4.1 全部 64 个令牌（白名单）—— **全部已接线**

> 本节由 `tools/gen_token_table.py` 从 `UiTheme.kt` 自动生成 —— 改了令牌记得重跑，
> 免得文档和代码对不上（照着过时文档写主题是最容易踩的坑）。

> **"已接线"的含义**：写了这个键，屏幕上**一定**有地方会变。
> 上一版有 9 个键写着"已实现"但没有任何消费端 —— 那种"写了没反应"
> 现在不允许再出现：加令牌的人必须同时加消费端，否则不加。

| 键 | 中文 | 类型 | 默认值 | 范围 | 语义 |
| --- | --- | --- | --- | --- | --- |
| `accent` | 主色 | 颜色 | `#0A84FF` | 任意合法颜色 | 按钮、开关、选中态、链接、用户气泡。改这个是最有效的一下 |
| `accentOn` | 主色上的文字色 | 颜色 | `#FFFFFF` | 任意合法颜色 | 主色按钮/气泡里的文字。选了浅色主色就必须把它改成深色，否则字看不见 |
| `danger` | 危险色 | 颜色 | `#FF3B30` | 任意合法颜色 | 删除、清空、错误态 |
| `success` | 成功色 | 颜色 | `#34C759` | 任意合法颜色 | 开关打开、已完成 |
| `warning` | 提醒色 | 颜色 | `#FF9500` | 任意合法颜色 | 橙色警示、未完成状态 |
| `groupedBackground` | 页面背景 | 颜色 | `#F7F7F9` | 任意合法颜色 | 整页底色。深色默认纯黑，OLED 友好 |
| `cardBackground` | 卡片背景 | 颜色 | `#FFFFFF` | 任意合法颜色 | 列表分组、卡片、AI 气泡 |
| `barBackground` | 导航栏/标签栏底色 | 颜色 | `#F7FFFFFF` | 任意合法颜色 | 前两位是透明度。要完全不透明就写 #FFFFFF |
| `barBorder` | 导航栏/标签栏描边 | 颜色 | `#14000000` | 任意合法颜色 | 导航栏/标签栏描边 |
| `separator` | 分隔线 | 颜色 | `#14000000` | 任意合法颜色 | 列表分隔线、卡片描边、气泡描边 |
| `fill` | 填充底色 | 颜色 | `#1A787880` | 任意合法颜色 | 输入框、次级按钮、未选中的开关 |
| `fillSecondary` | 次级填充色 | 颜色 | `#0F787880` | 任意合法颜色 | 更淡的一层填充：选中行、分段控件底 |
| `label` | 主要文字 | 颜色 | `#0B0B0F` | 任意合法颜色 | 主要文字 |
| `secondaryLabel` | 次要文字 | 颜色 | `#8A3C3C43` | 任意合法颜色 | 前两位是透明度 |
| `tertiaryLabel` | 三级文字 | 颜色 | `#4D3C3C43` | 任意合法颜色 | 三级文字 |
| `selection` | 选中高亮 | 颜色 | `#0A84FF` | 任意合法颜色 | 标签栏选中指示器。留空/不写就跟随主色 |
| `overlay` | 浮层遮罩 | 颜色 | `#66000000` | 任意合法颜色 | 弹层背后的压暗层 |
| `bubbleUser` | 用户气泡 | 颜色 | `#0A84FF` | 任意合法颜色 | 不写就跟随主色 |
| `bubbleUserText` | 用户气泡文字 | 颜色 | `#FFFFFF` | 任意合法颜色 | 不写就跟随 accentOn |
| `bubbleAi` | AI 气泡 | 颜色 | `#FFFFFF` | 任意合法颜色 | 不写就跟随 cardBackground |
| `bubbleAiBorder` | AI 气泡描边 | 颜色 | `#14000000` | 任意合法颜色 | 不写就跟随 separator |
| `composerBackground` | 输入框底色 | 颜色 | `#1A787880` | 任意合法颜色 | 不写就跟随 fill |
| `codeBackground` | 代码块底色 | 颜色 | `#F2F2F7` | 任意合法颜色 | 浅色默认浅灰、深色默认近黑；文字自动取对比色 |
| `cardCorner` | 卡片圆角 | 数字 | `12` | 0 – 32 | 卡片圆角 |
| `controlCorner` | 控件圆角 | 数字 | `10` | 0 – 32 | 控件圆角 |
| `sheetCorner` | 浮层圆角 | 数字 | `16` | 0 – 40 | 浮层圆角 |
| `bubbleCorner` | 气泡圆角 | 数字 | `18` | 0 – 32 | 气泡圆角 |
| `pagePadding` | 页面左右留白 | 数字 | `16` | 8 – 28 | 页面左右留白 |
| `navBarHeight` | 导航栏高度 | 数字 | `44` | 36 – 64 | 导航栏高度 |
| `tabBarHeight` | 标签栏高度 | 数字 | `60` | 44 – 80 | 标签栏高度 |
| `barBorderWidth` | 导航栏/标签栏描边粗细 | 数字 | `0.5` | 0 – 2 | 导航栏/标签栏描边粗细 |
| `tabIconSize` | 标签栏图标大小 | 数字 | `23` | 18 – 30 | 标签栏图标大小 |
| `bodySize` | 正文字号 | 数字 | `17` | 12 – 24（单位 sp） | 只影响正文三档（body / callout / subheadline） |
| `fontScale` | 整体字号缩放 | 数字 | `1.0` | 0.8 – 1.4 | 整体字号缩放 |
| `tabStyle` | 标签栏样式 | 枚举 | `glass` | glass / plain / hidden | glass（毛玻璃）/ plain（不透明）/ hidden（藏起来，但标签只剩一个时忽略） |
| `showTabBarLabels` | 标签栏显示文字 | 开关 | `true` | true / false | 标签栏显示文字 |
| `navTitleAlign` | 导航栏标题位置 | 枚举 | `center` | center / leading | center（居中，iOS 默认）/ leading（靠左） |
| `tabBarShape` | 标签栏形状 | 枚举 | `capsule` | capsule / rounded / square | capsule（胶囊）/ rounded（圆角矩形）/ square（直角） |
| `tabBarMargin` | 标签栏左右留白 | 数字 | `18` | 0 – 48 | 标签栏左右留白 |
| `tabBarLift` | 标签栏离底距离 | 数字 | `12` | 0 – 40 | 标签栏离底距离 |
| `tabBarBlur` | 玻璃模糊半径 | 数字 | `10` | 0 – 16（单位 dp） | 10 = 默认磨砂（2 太轻，底下的字会透出来）；0 = 完全清晰（纯折射），16 = 重度磨砂 |
| `tabBarTint` | 玻璃染色 | 颜色 | `#FFFFFF` | 任意合法颜色 | 深色主题用 darkTokens 覆盖成深色 |
| `tabBarIndicator` | 选中指示器 | 枚举 | `capsule` | capsule / underline / dot / none | capsule（玻璃胶囊）/ underline（下划线）/ dot（圆点）/ none |
| `tabBarWidth` | 标签栏宽度 | 枚举 | `compact` | compact / full | compact（悬浮紧凑胶囊）/ full（通栏） |
| `uiKit` | 液态玻璃组件库 | 枚举 | `auto` | auto / library / compose | auto（硬件支持则用 AndroidLiquidGlassView）/ library（强制组件库）/ compose（用内置降级） |
| `glassBarLiquid` | 液态玻璃效果 | 开关 | `true` | true / false | 开：边缘折射 + 色散 + 按压放大；关：只保留模糊与染色（LiquidTab 的默认行为） |
| `glassBarOutline` | 底栏描边 | 开关 | `true` | true / false | 开启时画一圈 1dp 高光描边 |
| `glassBarHighlight` | 高光强度 | 数字 | `0.5` | 0.5 – 1.7 | 描边与边缘高光的强度，随设备倾斜移动 |
| `glassBarSlotWidth` | 单栏宽度 | 数字 | `76` | 56 – 112（单位 dp） | 悬浮胶囊的总宽 = 单栏宽度 × 标签数 |
| `glassBarBottomBlur` | 底部渐变模糊 | 开关 | `false` | true / false | 底栏上方页面底部的渐隐模糊 |
| `glassBarIconOnly` | 仅图标 | 开关 | `false` | true / false | 开启后不画文字（悬浮胶囊会自动更紧凑） |
| `glassBarLabelSize` | 标签文字大小 | 数字 | `11` | 9 – 14（单位 sp） | 标签文字大小 |
| `dynamicColor` | 动态配色 | 开关 | `false` | true / false | 从系统壁纸主色生成 accent；关掉回到主题包/预设的颜色 |
| `dockImpl` | 底栏实现 | 枚举 | `liquidTabBar` | liquidTabBar / liquidDock | liquidTabBar（当前液态玻璃底栏）/ liquidDock（自定义液态底栏，更多可调项） |
| `fontFamily` | 界面字体 | 枚举 | `system` | system / sans / serif / mono / cursive | system（系统默认）/ sans（无衬线）/ serif（衬线，像书）/ mono（等宽，像代码）/ cursive（手写体） |
| `wallpaperFile` | 壁纸图片 | 文本 | `` | 文本（≤200 字符） | 主题包内的图片文件名（放在插件 assets/ 下）。留空 = 用内置极光背景 |
| `wallpaperDim` | 壁纸压暗 | 数字 | `0.0` | 0 – 0.85 | 壁纸上的黑色遮罩浓度。压太深壁纸就白装了，太浅文字看不清 |
| `wallpaperBlur` | 壁纸模糊 | 数字 | `0` | 0 – 24（单位 dp） | 壁纸背景虚化半径。深色主题常用 12~20 让文字浮起来 |
| `wallpaperScale` | 壁纸缩放 | 数字 | `1.0` | 1 – 2 | 壁纸放大倍数。手机屏幕比例和壁纸图比例不一致时会露边，放大能盖住 |
| `cardStyle` | 卡片风格 | 枚举 | `flat` | flat / glass / gradient / neu | flat（扁平）/ glass（毛玻璃，半透）/ gradient（渐变）/ neu（拟物，双层描边） |
| `cardElevation` | 卡片厚度 | 数字 | `0` | 0 – 12（单位 dp） | 卡片投影高度。0 = 纯扁平，靠描边分层 |
| `bubbleStyle` | 气泡形状 | 枚举 | `rounded` | rounded / square / tail | rounded（全圆角）/ square（直角）/ tail（带小尾巴，像 QQ） |
| `avatarShape` | 头像形状 | 枚举 | `circle` | circle / rounded / square | circle（正圆）/ rounded（圆角方）/ square（直角） |
| `bgStyle` | 页面背景 | 枚举 | `aurora` | aurora / flat / gradient / wallpaper | aurora（内置极光）/ flat（纯色）/ gradient（双色渐变）/ wallpaper（主题壁纸） |

### 4.2 关于三个已删除的键

| 旧键 | 现在写了会怎样 | 正确做法 |
| --- | --- | --- |
| `accentDark` | 被列为"不认识的令牌" | `darkTokens.`**`accent`** |
| `groupedBackgroundDark` | 同上 | `darkTokens.groupedBackground` |
| `cardBackgroundDark` | 同上 | `darkTokens.cardBackground` |

这三个键**从来没有生效过**（旧文档也承认了，但把它们留在了白名单里）。
留着比删掉更糟：插件作者会以为自己用对了。**同一件事只能有一条表达路径。**

### 4.3 未接线令牌怎么处理

**今天没有未接线的令牌。** 如果未来有人加了却忘了接：

| 情况 | 行为 |
| --- | --- |
| 在白名单里但没消费端 | 值会被解析、被夹取、存在令牌表里，但界面不变 —— **这是 bug，不是设计** |
| 不在白名单里 | 安装报告和主题页都会列出来（§3.3） |

### 4.4 `accentOn`：为什么必须单独有一个"主色上的字色"

这是上一版设计里**最实际的一个洞**。

以前按钮文字、开关滑块、用户气泡里的字全部写死 `Color.White`。
于是一个浅色主色（比如米黄 `#FFE066`）会让按钮上的白字**完全看不见**，
而主题作者没有任何办法修 —— 白名单里没有那个键。

现在：

* `accentOn` 控制**主色之上**的文字/图标色（按钮、填充徽标、选中 chip）；
* 底色**不是主色**的地方（比如一个绿色徽标）用亮度公式自动挑黑或白，
  主题作者不需要为每个颜色配一次字色；
* 所以"用户选的任何主色都不会出现看不见的字"这件事是**结构上保证**的。

### 4.4.1 数字令牌的单位

所有长度类令牌的单位是 **dp**，字号类是 **sp**。越界会被**夹到区间边界**
（不是报错、不是忽略）—— 插件作者写 `"100"` 会得到 32dp 的圆角，
应用不会崩也不会排版炸掉。


### 4.5 与 `docs/PLUGIN.md` §8.3 的差异（诚实清单）

本文的接线状态是**逐个 grep 消费端**核对的，`PLUGIN.md` 的同一列有**三处**不准：

| 令牌 | `PLUGIN.md` 说 | 实际 |
| --- | --- | --- |
| `sheetCorner` | ✅ 已生效 | ❌ **未接线**。它只被写进 `IosMetrics.sheetCorner`，而全仓库**没有任何** `metrics.sheetCorner` 的读取点 |
| `fontScale` | ✅ 已生效 | ⚠️ **读了但无消费端**（§4.4）。今天是"看不到变化" |
| `accentDark` / `groupedBackgroundDark` / `cardBackgroundDark` | ⚠️ 未接线 | ⚠️ 未接线（一致）—— 但**更准确的说法**是：这三个键**在语义上就是多余的**，正确的深色通道是 `darkTokens` + 浅色键名（§6） |

另外一处措辞需要收紧：`PLUGIN.md` §15 说 `tabs` / `startTab` / `appName`
"会被解析、校验、保存，但界面外壳还没读取"。**当前状态**是：

* `tabs` 和 `appName` 已经被写进 `AppViewModel.visibleTabs` / `appTitleOverride`，
  但**这两个 StateFlow 仍然没有任何界面消费者** → 用户看不出变化；
* `startTab` **连读取点都没有**，只在 `UiThemes.parse` 里被校验。

（`PLUGIN.md` 的部分内容在这两份文档写成的同一时间窗内已被更新，
上表以本文的 grep 结果为准。）

---

## 5. 颜色写法与非法值的回退

### 5.1 三种写法【已实现】

| 写法 | 例子 | 展开成 |
| --- | --- | --- |
| `#RGB` | `#F63` | `#FFFF6633`（每位重复一次，alpha = `FF`） |
| `#RRGGBB` | `#FF6B35` | `#FFFF6B35`（补上不透明 alpha） |
| `#AARRGGBB` | `#80FF6B35` | 直接用（这里是 50% 透明的暖橙） |

* 字母**大小写都行**，生效前会被统一成**大写**；
* 前导 `#` **必须有**；
* 长度**只有** 3 / 6 / 8 三种合法。

### 5.2 非法颜色的回退【已实现】

颜色写错**不会报错、不会崩**，会**回退到该令牌的默认值**（不是 iOS 原色，
也不是你上一次写的值）：

| 你写的 | 结果 |
| --- | --- |
| `"红色"` | ❌ → 默认值（`accent` → `#0A84FF`） |
| `"FF6B35"`（漏了 `#`） | ❌ → 默认 |
| `"#FF6B3"`（5 位） | ❌ → 默认 |
| `"#GGGGGG"` | ❌ → 默认 |
| `"#ff6b35"` | ✅ → 生效，规范化为 `#FF6B35` |
| `" #FF6B35 "` | ✅ → 首尾空白被 trim，生效 |
| `"#FFF"` | ✅ → `#FFFFFF` |

> ⚠️ **回退是"回退到令牌默认"，不是"回退到 iOS 深色原色"。**
> 例：深色模式下 `darkTokens.danger` 写错 → 用的是 `#FF3B30`（浅色红），
> **不是** iOS 深色红 `#FF453A`。所以**别在深色块里写错颜色**——
> 你会得到一个比预期更扎眼的值，而且没有任何提示。

### 5.3 alpha 的坑

`#AARRGGBB` 的第一个字节是 **alpha**，不是红色分量。写 `#FF0000` 是"纯红"，
写 `#00FF0000` 是"**完全透明**的红"（等于看不见）。
想要半透明请显式写 `#80` 开头，例如 `#80000000`（50% 黑）。

---

## 6. 深浅色两套怎么给（含最常见的错误）

### 6.1 正确写法

```json
{
  "tokens": {
    "accent": "#FF6B35",
    "groupedBackground": "#FDF9F6",
    "cardBackground": "#FFFFFF"
  },
  "darkTokens": {
    "accent": "#FF8A5C",
    "groupedBackground": "#0A0A0C",
    "cardBackground": "#16161A"
  }
}
```

**规则一句话**：`darkTokens` 的键名用**浅色那一套键名**。

### 6.2 ⚠️ 最常见的错误

```json
"darkTokens": {
  "groupedBackgroundDark": "#0A0A0C"     ← 错！这个键永远不会被读
}
```

正确：

```json
"darkTokens": {
  "groupedBackground": "#0A0A0C"         ← 对，键名照样是 groupedBackground
}
```

**为什么会错**：白名单里**确实存在** `groupedBackgroundDark` / `cardBackgroundDark` /
`accentDark` 三个键，看起来就是"深色版"。但它们**没有任何消费端**（§4.1）。
真正的深色机制完全走 `darkTokens` + 浅色键名。

> 这个坑连**主程序自带的示例**（`UiThemes.SAMPLE`，即"写出界面主题示例"生成的那份）
> 都踩了：它的 `darkTokens` 里写的是 `groupedBackgroundDark = "#0A0A0C"`，
> 结果这份示例的深色背景**根本不生效**（深色背景仍是内置的纯黑 `#000000`）。
> **以本文档为准，不要照抄那份示例的 `darkTokens`。**

### 6.3 合并语义【已实现】

深色下实际使用的令牌表按这个顺序合并（后者覆盖前者）：

```
theme.tokens  →  （dark 时）theme.darkTokens  →  用户手动覆盖  →  逐项 normalize
```

* 只在 `darkTokens` 里出现、`tokens` 里没有的键：**深色下也会生效**（不是必须两边都写）；
* 浅色下 `darkTokens` **完全被忽略**；
* 合并是**按 key 覆盖**，不是按类型或分组，所以你可以只覆盖 `accent` 一项。

### 6.4 深色模式什么时候触发

`MainActivity` 在组合时决定：

| 设置里的"外观" | 行为 |
| --- | --- |
| 跟随系统（默认） | `isSystemInDarkTheme()` |
| 浅色 | 恒 `false` |
| 深色 | 恒 `true` |

令牌由 `AppViewModel.uiThemeState`（一个 StateFlow）提供，所以系统切换深浅色时
主题**立刻**重算，不需要重启应用。切换的失效条件是：
`dark` 变了、插件清单版本号变了、或用户覆盖版本号变了。

---

## 7. `tabs` / `startTab` / `appName`

### 7.1 今天的状态：**三个都接线了**

| 字段 | 解析 | 校验 | 落库 | 屏幕上有效果 |
| --- | --- | --- | --- | --- |
| `tabs` | ✅ | ✅ | ✅ | ✅ 决定底部标签栏显示哪几个、什么顺序 |
| `startTab` | ✅ | ✅ | ✅ | ✅ 冷启动落在哪一页 |
| `appName` | ✅ | — | ✅ | ✅ 空对话页那个大标题 |

**这三个字段现在都从 `AppViewModel.uiThemeState`（StateFlow）流到界面**，
由 `LocalMindRoot` 读标签栏配置、由 `ChatScreen` 读标题。

> ⚠️ 关于 `appName` 的边界：它只改**空对话页的大标题**。关于页/设置页里的
> "LocalMind" 是**事实陈述**（这个应用叫什么、谁维护），不是外观，
> 主题改它等于允许主题伪装成别的应用。

**对你的实际影响**：

* 写 `tabs` / `startTab` / `appName` **不会报错**（它们通过了校验），
  但**用户看不到任何变化**；
* 写错（`"tabs": ["home"]`）会**直接让整个主题装不上**：

  ```
  界面主题：tabs 里有不认识的页面：home。只能是 chat、models、me
  ```

  也就是说：**这三个字段今天唯一的可见效果就是"写错会让主题装不上"。**
  不确定就先别写。

### 7.2 合法值

```json
{
  "tabs": ["chat", "models", "me"],
  "startTab": "chat",
  "appName": "我的助手"
}
```

* `tabs` 元素只能是 `chat` / `models` / `me`，**区分大小写**（`"Chat"` 会被拒）；
* `tabs` 为空数组 = 用默认三个；
* `startTab` 空串 = 用 `chat`；
* 未来的语义（见 §14）是：`tabs` 的**顺序就是显示顺序**，元素个数就是标签个数。

---

## 8. 优先级与多插件冲突

### 8.1 优先级链【已实现】

从低到高（后者覆盖前者）：

```
① 内置 iOS 配色（LightPalette / DarkPalette）
        ↓
② ui_theme 插件（tokens → 深色时叠 darkTokens）
        ↓
③ 用户手动覆盖（Storage "theme_overrides"）  ← 最高
```

* 插件**只覆盖自己声明了的令牌**，没声明的保持 iOS 原色。
  所以"只改一个 `accent`"的主题**不会**顺手把别的颜色也洗掉；
* 用户手动覆盖的通道在**存储层与解析层是通的**，并且有界面能看到它
  （主题设置页会列出"手动覆盖"并允许**全部清除**），
  但**还没有"逐项调色"的入口** —— 只能清除，不能编辑。

### 8.2 ⚠️ 手动覆盖有一个已知的刷新缺陷【已实现，属于 bug 级细节】

口令的重新解析依赖两个版本号：

| 版本号 | 谁在 bump | 状态 |
| --- | --- | --- |
| `pluginRevision` | 装/卸/开关插件时 | ✅ 正常，所以**换主题立刻生效** |
| `overrideRevision` | `bumpOverrideRevision()` | ❌ **全仓库没有任何调用点** |

后果：**用户改手动覆盖之后，界面不会自动重算令牌**（要等下一次插件增删或
应用重启）。主题设置页自己的"手动覆盖"列表会刷新（它读的是 ViewModel 的
StateFlow），但**实际生效的配色**要等下一次 `pluginRevision` 变化。
主题作者不需要管这件事，但**设计时不要假设"用户覆盖一定立刻可见"**。

### 8.3 多个 `ui_theme` 插件同时启用：**只取一个，不合并**【已在代码中确认】

```kotlin
// IosTheme.kt / rememberPluginUiTokens
val enabled = storage.loadPlugins().values
    .filter { it.isActive && "ui_theme" in it.manifest.abilities }
val theme = enabled.lastOrNull()?.let { packs[it.manifest.id] }
```

`lastOrNull()` 决定了"**最后一个启用的赢**"。注意这里的"最后一个"是
**已安装插件表的遍历顺序**（= 安装顺序），不是"最后一次点开关的时间"：

| 操作 | 谁生效 |
| --- | --- |
| 依次装 A、B 两个主题 | B |
| 关掉 B，再开 B | **仍然是 B**（表里 B 还在 A 后面） |
| 想让 A 生效 | 用主题设置页选中 A（会把其它主题插件全部停用），或卸载/重装 A |

**不合并是刻意的**：两套配色混在一起通常都很难看，而且出了问题无法归因。

### 8.4 主题设置页：把"多插件"变成"单选"【已实现】

`我的 → 主题`（**装了至少一个界面插件才会出现这一行**）提供：

| 功能 | 行为 |
| --- | --- |
| 列表 | 「内置」+ 所有已安装的主题（按 `packName` 排序），带**主题自己声明的**四色预览块 |
| 选中某主题 | **把所有 `ui_theme` 插件停用，只启用它一个**（`useUiTheme`） |
| 「内置」 | 把所有 `ui_theme` 插件停用（`useBuiltinTheme`） |
| 「改了什么」 | 列出**解析并夹取之后**的令牌值（不是 JSON 原值），颜色型带真实色块；末尾用橙色列出不认识的令牌 |
| 手动覆盖 | 有覆盖时显示键名 = 值，并提供「全部清除」 |

> ⚠️ 一个边角行为：`useUiTheme` 是按 **`packId`** 匹配插件的，而数据库里的
> `packId` 在安装时已被改写成 `manifest.id`（§3），所以正常路径下两者相等。
> 但如果你手工改过存储、或将来某条路径漏了这次改写，会导致**点了主题却没启用任何插件**
> （表现：所有主题插件被停用 → 回到内置外观）。

---

## 9. 和别的能力放在同一个 zip 里

**一个 zip 可以有多种能力**，`ui_theme` 可以和别的共存：

```
我的大礼包.zip
├── plugin.json      ← "abilities": ["ui_theme", "emoji_pack", "model_library"]
├── ui.json          ← 主题
├── emoji.json       ← 表情包
├── models.json      ← 模型库
└── assets/
    └── happy.png
```

要点：

1. **`abilities` 要如实列出全部能力**。漏写某个能力 → 对应的内容文件会被
   当成"无关文件"跳过（并计入 `忽略了 N 个无关文件`）；
2. 内容文件是**按文件名**认的：`ui.json` 属于 `ui_theme`，`emoji.json` 属于
   `emoji_pack`，互不干扰；
3. **有一个开关**：插件页的开关是把**整个插件**（全部能力）一起开关的，
   不能只启用其中一种能力；
4. **卸载也是整体的**：删掉这个插件，主题和表情包一起消失；
5. 想让用户能单独换主题，就**把主题单独打一个 zip**。

`ui_theme` 的 `contentFiles` 就是 `setOf("ui.json")` —— 它**不需要** `assets/`。
`assets/` 今天对主题完全无用（§14 提案里会用上）。

---

## 10. 完整可导入示例

下面这份是**可以直接用**的（值都合法、键都在白名单里、不依赖任何未接线字段）。

### 10.1 目录结构

```
暖橙主题/
├── plugin.json
├── ui.json
└── README.md        ← 可选
```

把**这三个文件**（不是文件夹）全选 → 压成 `暖橙主题.zip`。

### 10.2 `plugin.json`

```json
{
  "id": "dev.localmind.theme.warm",
  "name": "暖橙主题",
  "version": "1.0.0",
  "author": "你的名字",
  "description": "暖橙主色 + 更大的圆角。深浅色两套。",
  "abilities": ["ui_theme"],
  "requires": [],
  "config": {},
  "manifestVersion": 1
}
```

| 行 | 为什么 |
| --- | --- |
| `id` | 反向域名风格，只能字母/数字/`.`/`-`/`_`。**改了 id 就是另一个插件**（会并存而不是更新） |
| `name` | 插件页和主题页里显示的名字 |
| `version` | 自由字符串；主程序拿来做展示，不比较 |
| `abilities` | 只有 `ui_theme` 一项；想带表情包就在这里加 `"emoji_pack"` |
| `config` | 空对象。**不要**塞数字/布尔值（值必须是字符串） |
| `manifestVersion` | 写 `1` |

### 10.3 `ui.json`（逐行解释）

```json
{
  "version": 1,
  "packId": "dev.localmind.theme.warm",
  "packName": "暖橙主题",
  "author": "你的名字",
  "description": "暖橙主色、暖白页面底色、18dp 卡片圆角。",
  "license": "MIT",
  "home": "",

  "tokens": {
    "accent": "#FF6B35",
    "danger": "#E5484D",
    "success": "#30A46C",
    "groupedBackground": "#FDF9F6",
    "cardBackground": "#FFFFFF",
    "cardCorner": "18",
    "controlCorner": "12",
    "fontScale": "1.0"
  },

  "darkTokens": {
    "accent": "#FF8A5C",
    "danger": "#FF6369",
    "success": "#3DD68C",
    "groupedBackground": "#0A0A0C",
    "cardBackground": "#16161A",
    "cardCorner": "20"
  }
}
```

| 键 | 解释 |
| --- | --- |
| `version` | 写 `1`。**当前无任何作用**，但所有示例都带它，留着不亏 |
| `packId` | 必须非空才能通过校验。**安装后会被改写成 `plugin.json` 的 `id`**（§3） |
| `packName` | **必须非空**，否则拒绝安装。这是主题页显示的名字 |
| `description` | 主题页会显示（最多两行），值得写 |
| `license` / `home` | 展示用；`home` 当前没有入口 |
| `tokens.accent` | 主色 → 按钮、开关、选中态、链接、滑块、进度条、Tab 指示器。**最值钱的一个令牌** |
| `tokens.danger` | 危险色 → "删除"、"清空这段关系的记忆"、错误态 |
| `tokens.success` | 成功色 → iOS 开关打开时的轨道色 |
| `tokens.groupedBackground` | 页面背景（根背景 + Tab 栏玻璃底 + 列表页缝隙）。**同时**是 Material3 的 `background` |
| `tokens.cardBackground` | 卡片/列表组背景。**同时**是 Material3 的 `surface` |
| `tokens.cardCorner` | `18` → 卡片圆角 18dp（合法范围 0–32） |
| `tokens.controlCorner` | `12` → 按钮圆角 12dp（合法范围 0–32） |
| `tokens.fontScale` | 显式写 `"1.0"` = 不改字号。**今天改成别的值也不会有变化**（§4.4）—— 写 `"1.0"` 只是明确表达"我不改字号" |
| `darkTokens.*` | **键名用浅色那一套**（§6）。深色下这几项覆盖上面的同名项 |
| — | **故意没写** `tabs` / `startTab` / `appName`（今天无效果，写错还会让主题装不上，§7） |
| — | **故意没写** `accentDark` / `groupedBackgroundDark` / `cardBackgroundDark` / `bodySize` / `tabStyle` / `showTabBarLabels` / `navTitleAlign` / `sheetCorner`（未接线，§4.2） |

### 10.4 最小可用版本（8 行）

```json
{
  "packId": "dev.localmind.theme.min",
  "packName": "只是换个主色",
  "tokens": {
    "accent": "#7C5CFF"
  }
}
```

配一份 `plugin.json`（`id` 可以随便起，比如 `dev.localmind.theme.min`，
`abilities: ["ui_theme"]`），装上即可。**只改一个令牌不会污染其它颜色。**

> `packId` 与 `plugin.json` 的 `id` 不一致也**能装上**（会被改写成 `id`），
> 但为了可读性，建议两边写一样。

---

## 11. 在手机上看效果的流程 / 怎么迭代

### 11.1 一条真正走得通的循环

```
① 电脑上建目录，写 plugin.json + ui.json
② 全选【里面的文件】→ 压成扁平 zip
③ 把 zip 传到手机（数据线 / 聊天软件传文件 / 网盘）
④ 应用：我的 → 插件 → 右上角「+」→ 选 zip
⑤ 看弹出的一句话：
     "已安装 暖橙主题：界面主题 暖橙主题（忽略了不认识的令牌：…）"
⑥ 界面立刻变了 → 我的 → 主题 → 点开「暖橙主题改了什么」核对每一项的值
⑦ 改 ui.json → 重压 zip → 回到 ④
```

### 11.2 迭代期的加速技巧

| 技巧 | 说明 |
| --- | --- |
| **重装不重置开关** | 用同一个 `id` 重装，启用状态保留，改完导入就生效 |
| **多存几个变体** | 用不同 `id`（`…warm` / `…cool` / `…warm2`）各打一个 zip，一次装完，在**主题设置页点选切换** —— 这比反复导入快得多 |
| **深色调试** | 设置里把"外观"切成**深色 / 浅色**，不用改系统设置；切回来立刻能看到两套值 |
| **核对实际生效值** | 主题设置页的「改了什么」显示的是**夹取之后**的值。你写 `cardCorner: "999"`，这里会显示 `32` —— 这是判断"我写的值被吃掉了没有"的最快方法 |
| **验证未知键** | 安装结果的那句话会列出被你写错的键名 |
| **回到原样** | 主题设置页点「内置」→ 所有主题插件停用，立刻恢复 iOS 原色（不用卸载） |

### 11.3 必须重启应用的情况

| 情况 | 需要重启？ |
| --- | --- |
| 换主题 / 改令牌 / 重装插件 | ❌ 立刻生效 |
| 系统深浅色切换 | ❌ 立刻生效 |
| 改了**手动覆盖**（今天没有编辑入口，只有"全部清除"） | ⚠️ **可能要**（§8.2 的 `overrideRevision` 缺陷） |
| 改了 `tabs` / `startTab` / `appName` | 重启也没用（未接线） |

### 11.4 用 logcat 排错

| Tag | 内容 |
| --- | --- |
| `PluginArchive` | 读了哪些文本/资源、跳过了多少项 |
| `PluginInstall` | 安装失败的异常堆栈（含畸形 zip 触发的 OOM 等） |

---

## 12. 排错表

### 12.1 安装期：`ui.json` 的报错原文与成因

安装结果里的前缀是 `界面主题：<原因>`。以下原文**逐字**来自 `UiThemes.parse`。

| 报错原文 | 成因 | 怎么修 |
| --- | --- | --- |
| `ui.json 是空的` | 文件 0 字节，或全是空白字符 | 写内容，存成 UTF-8 |
| `ui.json 格式错误：<解析器原文>` | JSON 语法错；或 **`tokens` 的值不是字符串**（`"cardCorner": 18` 缺引号）；或用了中文标点；或尾逗号 | 值一律加引号写字符串：`"18"`、`"1.0"`、`"true"`；引号/逗号/冒号用**半角**；删掉尾逗号；JSON 不支持注释 |
| `ui.json 缺少 packId` | 没写 `packId`，或写成空串 | 补 `"packId": "反向域名"` |
| `ui.json 缺少 packName` | 没写 `packName`，或写成空串 | 补 `"packName": "显示名"` |
| `tabs 里有不认识的页面：<值>。只能是 chat、models、me` | `tabs` 元素不在白名单，或大小写不符（`"Chat"`） | 只用 `chat` / `models` / `me`，全小写；**不确定就整个删掉**（今天它没有视觉效果） |
| `startTab 不认识：<值>。只能是 chat、models、me` | 同上，`startTab` 拼错 | 删掉，或改成三个合法值之一 |
| `ui.json 什么都没改（tokens / darkTokens / tabs / appName 都是空的）` | 四个字段全空（**只写 `startTab` 也会命中这一条**） | 至少写一个令牌；只写 `startTab` 不算"改了东西" |

### 12.2 安装期：不是 `ui.json` 的错

| 报错原文 | 成因 | 怎么修 |
| --- | --- | --- |
| `压缩包里没有可识别的内容（plugin.json / emoji.json / models.json / assets）` | **有** `plugin.json`，但包里没有可识别的内容文件 | 至少放一份 `ui.json` |
| `压缩包里没有可识别的内容。要有 plugin.json，或 emoji.json（表情包）/ models.json（模型库）/ voices.json（语音音色）/ ui.json（界面主题）` | **没有** `plugin.json`，且没有可识别的内容文件 | 放一份 `ui.json`，或补 `plugin.json` |
| `不是合法的插件清单：<原文>` | `plugin.json` 语法错 | 校验 JSON |
| `缺少 id` | 清单没 `id` | 补 `id` |
| `清单不完整：name=…，abilities=N 个…` | 缺 `name` / `abilities` 为空 / id 含非法字符 | `abilities` 至少一项；id 只用字母数字和 `.` `-` `_` |
| `这个插件声明的能力当前版本都不认识：…` | 全部 ability id 都拼错 | 正确写法是 `"ui_theme"` |
| `读取压缩包失败：…` / `展开压缩包失败：…` | 不是合法 zip；加密 zip；文件损坏 | 重新压缩，不要加密 |
| `安装失败：<异常类> …` | 畸形 zip 触发的兜底（含 OOM） | 重新压缩；确认 zip 完整 |
| `（忽略了 N 个无关文件）`（不是错误，是提示） | zip 里有白名单外的文件（`__MACOSX/`、`.DS_Store`、两个同名文件里的一个…） | 无害；保持扁平、文件名唯一 |

### 12.3 装上了但"界面没变"

**按这个顺序排查**：

| # | 检查 | 怎么看 |
| --- | --- | --- |
| 1 | **插件是否启用** | 我的 → 插件：这一行的开关是否打开、能力标签是否蓝色 |
| 2 | **你到底改的是哪个键** | 对照 §4.1 的"已接线"列。**9 个键写了不会有任何效果**（`accentDark`、`groupedBackgroundDark`、`cardBackgroundDark`、`sheetCorner`、`bodySize`、`tabStyle`、`showTabBarLabels`、`navTitleAlign`、**`fontScale`**） |
| 3 | **是不是有别的主题在后面赢** | 我的 → 主题：当前选中的是哪个？（多个主题插件**只取一个**，§8.3） |
| 4 | **值是不是被夹掉/回退了** | 主题设置页 → 「改了什么」：显示的是**实际生效值**。写 `999` 会显示 `32`；写 `"红色"` 会显示默认蓝 |
| 5 | **深色值是不是写错了键名** | `darkTokens.groupedBackgroundDark` 是**错的**，要写 `darkTokens.groupedBackground`（§6.2） |
| 6 | **是不是 `tabs` / `startTab` / `appName`** | 这三个今天**没有任何视觉效果**（§7） |
| 7 | **深浅色模式对不对** | 设置里的"外观"是跟随系统还是被钉死？用错模式会看错那一套值 |
| 8 | **`ui.json` 是否超 1 MB** | 超了会被整份丢弃 → 主题根本不存在；安装结果里可能只有"没有可识别的内容" |

### 12.4 症状 → 最可能的原因（速查）

| 症状 | 最可能的原因 |
| --- | --- |
| 主色没变 | 键名不是 `accent`；或值不是合法颜色（漏 `#`）；或有别的主题在后面赢 |
| 页面背景没变 | 你写进了 `groupedBackgroundDark` 而不是 `darkTokens.groupedBackground` |
| 卡片背景没变 | 同上，`cardBackgroundDark` → 应该是 `darkTokens.cardBackground` |
| 深色下颜色没变 | `darkTokens` 的键名写错（加了 `Dark` 后缀）；或应用处于浅色模式 |
| 圆角没变 | 浮层圆角 `sheetCorner` **未接线**（§4.1）；卡片/控件圆角是接线的，检查范围是否被夹（0–32） |
| 字号没变 | `bodySize` 未接线；`fontScale` **也看不到变化**（§4.4）→ **今天没有任何令牌能改字号** |
| 标签栏文字还在 | `showTabBarLabels` **未接线** |
| 标签栏没藏起来 | `tabStyle: "hidden"` **未接线**，标签栏不会被藏起来 |
| 导航栏标题还是居中 | `navTitleAlign` **未接线** |
| 换了标题没反应 | `appName` 今天无效果（§7） |
| 装了主题反而变回默认配色 | 两个主题插件冲突；或该插件被 `useUiTheme` 判定为"非当前"而被停用 |
| 安装后立刻弹错误又变回原样 | 主题未能通过校验 → 没有落库 → 不是"装上了不生效"，而是**根本没装上**。看那句话的前缀是不是 `界面主题：` |

---

## 13. 安全模型

### 13.1 为什么插件不能执行代码

**能力越强，越不能用"执行代码"来实现。**

如果界面插件可以直接写 Compose 代码，主程序就必须用 `DexClassLoader` 之类的
机制在运行时加载插件里的 dex/class。那一刻发生的事情是：

* 加载进来的代码和**主程序拥有完全相同的权限**（同一个进程、同一个 UID）；
* 它可以读**聊天记录**（明文存在应用私有目录）、读**用户填的云端密钥**、
  静默联网上传、调用麦克风/相机；
* 用户看到的却是"我装了个主题"。

**"装个插件"就立刻变成"装个后门"。** 而且这种风险**无法靠审核解决**：
插件是用户自己从任意来源拿到的 zip，没有应用商店，没有签名校验链。

所以这一层的设计是：**插件只能从一份固定的名单里挑值。**

| | 声明式令牌 | 代码插件 |
| --- | --- | --- |
| 能做什么 | 改配色、圆角、字号、密度、（未来）字体与背景图 | 任意 |
| 最坏情况 | 配色难看 | 数据外泄、静默联网、远程控制 |
| 是否需要审核 | 不需要 | 必须 |
| 崩溃风险 | 无（越界被夹、非法值回退） | 高（插件崩 = 应用崩） |
| 可组合 | 是（浅/深/高对比可分层） | 否 |

代价是**表达力有上限**。真需要任意布局的人应该去改源码、自己编译，
而不是装一个能读走他聊天记录的插件。

> **这条底线不可协商。** 第十四节的提案在"能表达多少"上大幅前进，
> 但在"不执行代码"上**一步不退** —— 所有新增能力都必须是**数据 + 白名单枚举 +
> 数值区间**。

### 13.2 哪些输入会被夹住 / 回退

| 输入 | 处理 | 会不会崩 |
| --- | --- | --- |
| 越界数字（`cardCorner: "999"`） | **夹到 max**（→ `32`） | 不会 |
| 负数（`cardCorner: "-5"`） | **夹到 min**（→ `0`） | 不会 |
| 非数字（`cardCorner: "很大"`） | **回退默认**（→ `12`） | 不会 |
| 非法颜色（`"红色"` / `"FF6B35"`） | **回退默认** | 不会 |
| 未知 ENUM（`tabStyle: "fancy"`） | **回退默认**（`glass`） | 不会 |
| 未知 BOOL 写法（`"maybe"`） | **回退默认**（`true`） | 不会 |
| 未知令牌键（顶层） | **忽略 + 报出来** | 不会 |
| 非法 `tabs` / `startTab` | **拒绝整个主题**（清楚的报错） | 不会 |
| 畸形 zip | 兜底 `catch (Throwable)` → 弹一句错误 | 不会（含 OOM/StackOverflow） |
| 路径穿越（`../`）、绝对路径 | **跳过该条目** | 不会 |
| zip 炸弹（超大/超多条目） | 单文件 8 MB / 合计 120 MB / 512 条目封顶 | 不会 |

**核心不变量**：`ui.json` 里的任何内容都**不可能**让应用崩溃 ——
要么被夹/回退，要么整个主题被礼貌拒绝。

### 13.3 数字夹取的一个细节：整数字符串

数值令牌在输出时有个小规则：**如果夹取后的值是整数、且该令牌的默认值里没有
小数点，就输出整数形式**（不拖 `.0`）。

| 你写的 | 默认值 | 输出 | 说明 |
| --- | --- | --- | --- |
| `cardCorner: "18"` | `12` | `"18"` | 整数令牌，输出整数 |
| `cardCorner: "18.9"` | `12` | `"19"` | **小数会被四舍五入成整数** |
| `cardCorner: "999"` | `12` | `"32"` | 夹到 max |
| `cardCorner: "-5"` | `12` | `"0"` | 夹到 min |
| `fontScale: "1.08"` | `1.0`（含小数点） | `"1.08"` | 默认值含小数点 → 保留小数形式 |
| `fontScale: "3"` | `1.0` | `"1.4"` | 夹到 max，仍保留小数形式 |

**结论**：圆角类令牌（`cardCorner` / `controlCorner` / `sheetCorner`）**写整数最稳** ——
写小数会被四舍五入，而"18" 和 "18.4" 在屏幕上没有区别，没必要冒这个风险。
唯一含小数点的令牌是 `fontScale`，而它今天**没有任何可见效果**（§4.4），
所以格式怎么写都无所谓。

### 13.4 装了恶意主题，最坏会怎样

**能做的**：

* 把整个应用配成难以阅读的配色（低对比、刺眼、同色）；
* 用未接线字段声明一些值（例如 `tabStyle: "hidden"`、`fontScale: "1.4"`），
  这些**今天不会生效**，所以它们连"难看"都做不到；
* 让 `tabs` / `startTab` / `appName` 声明一些（今天无效果的）值；
* 占一点存储（≤ 120 MB 的 `assets/`，虽然主题用不到）。

**做不到的**：

* ❌ 读取任何聊天内容、用户资料、API 密钥；
* ❌ 联网、上传、下载（`ui_theme` 没有网络路径，`requires` 只是提示文字）；
* ❌ 访问文件系统（除了它自己的 `assets/` 目录）；
* ❌ 让应用崩溃或卡死（越界被夹、非法值回退、解析有兜底）；
* ❌ 改变任何功能行为（不改路由、不改提示词、不改模型选择）；
* ❌ 在没有用户二次确认的情况下被安装（安装是用户主动选 zip 的动作）。

**结论**：**最坏情况是"界面难看"，而且用户一个动作就能恢复**
（主题设置页 → 内置，或卸载插件）。

### 13.5 ⚠️ 给写 Kotlin 工具的人：嵌套块注释陷阱

**Kotlin 的块注释是嵌套的。** 这意味着在 `/* … */` 里面再出现 `/*`，
会**开一个新的注释层**，而原来那个 `*/` 只关掉内层 —— **后面整个文件都被吞掉**。

真正会咬人的写法：在 KDoc 里直接写"目录通配符"那个字符串，也就是
**斜杠 + 星号**（`assets/` 紧跟一个 `*`）。源码里踩过一次，症状是一堆莫名的
`Unclosed comment` 和参数解析错误，而**出错位置在实际问题的几十行之后**。

| 场景 | 结果 |
| --- | --- |
| `/** 资源目录写成 assets/ 加星号 */`（用文字描述） | ✅ 安全 |
| `/** 资源目录写成 `assets/*` */` | ❌ **灾难**：`/*` 开新层，之后的代码全被注释掉 |
| 字符串字面量 `"assets/*"` 在**普通代码**里 | ✅ 安全（字符串在注释之外） |
| 在块注释里写 `/*` 想表达"斜杠星号" | ❌ 同上灾难 |

**正确做法**（仓库里已经在用）：拼出来，或者用文字描述。

```kotlin
private const val ASSET_WILDCARD = "assets/"
// 用 endsWith 判断而不是比较字面量，避免源码里出现「斜杠加星号」那三个字符
if (pattern.endsWith("*")) { zipPath.startsWith(pattern.dropLast(1)) }
```

**任何生成/校验 `ui.json` 的脚本、任何给主题写的 KDoc，都要遵守这一条。**

### 13.6 主题内容的授权

令牌数据（颜色值、数字、枚举）**本身没有版权问题**，随便授权。
但如果将来按 §14 打包了**字体文件或背景图**，那些素材的授权由**你**负责声明
（写进 `ui.json` 的 `license` 字段和 zip 里的 `LICENSE`）。

---

## 14. 【提案 / roadmap，**尚未实现**】完整自定义 UI 的接口设计

> ⚠️ **本节描述的东西今天一行都不存在。** 按本节写的 `ui.json` 会出现：
> 顶层新增块**被静默忽略**（因为 `ignoreUnknownKeys = true`），
> 只有 `tokens` / `darkTokens` / `tabs` / `appName` 生效。
> 本节是给主程序维护者的**设计契约**，目标是"照着实现，不用再讨论"。

### 14.1 目标与非目标

| 要支持 | 不做什么 |
| --- | --- |
| 自带字体（zip 里的 `assets/`） | ❌ 任意代码 / dex / 脚本 / 表达式 |
| 背景图与渐变 | ❌ SVG（解析器攻击面大，且能引用外部资源） |
| 分组件样式（气泡、输入框、卡片、导航栏、标签栏） | ❌ 自定义布局（那是改源码的活） |
| 图标集选择与单图标替换 | ❌ 插件自带的图标**字体**（字形解析 = 解析器攻击面） |
| 布局密度、圆角梯度、阴影/层级 | ❌ 覆盖系统启动图标（见 14.7） |
| 动效速度 / 弹簧预设 | ❌ 任意动画曲线表达式 |
| 启动页 / 开屏配置 | ❌ 网络资源（主题必须完全离线可用） |
| 浅色 / 深色 / **高对比** 三套 | ❌ 插件自定义"令牌名"（白名单不可扩展，见 14.8） |

### 14.2 安全模型（提案的底线）

**依然是纯数据。** 三条不可协商的规则：

1. **没有代码路径**。插件目录里的文件**永远**只被当作数据读；
   主程序**不引入** `DexClassLoader` / `System.load` / 任何动态加载。
   理由同 §13.1：动态加载的代码拥有和主程序一样的权限，
   会把"装主题"变成"装后门"，而且这条风险无法通过审核消除。
2. **每个新字段都必须有 `default` + `min/max`（或枚举白名单）+ 垃圾输入行为**。
   没有这三样，字段不许进白名单。验收标准：**新字段不可能让应用崩溃**。
3. **所有资源都在 `assets/` 下，且按内容校验**（不信任扩展名）：
   字体必须是合法 TTF/OTF（检查魔数 `0x00010000` / `OTTO` / `true` / `ttcf`），
   图片必须是 PNG/WebP/JPEG（检查文件头），**单文件 ≤ 4 MB，全部资源 ≤ 32 MB，
   ZIP 条目 ≤ 512**（沿用今天已有的上限思路）。

**为什么不用"沙箱执行"替代**（例如把插件代码跑在独立进程/WebView 里）：
Android 上要做到"真的隔离"需要独立 UID + 独立进程 + 完整的 IPC 表面，
那是一整个平台的工程量；而且只要插件能画自己的 UI，它就能画一个**假的登录页**
骗用户输密钥 —— 这是 UI 层的钓鱼，沙箱挡不住。
**所以 UI 插件的边界应该停在"数据描述外观"，而不是"代码绘制界面"。**

### 14.3 v2 的 `ui.json` 结构（提案）

保持 v1 的**全部字段原样可用**（向后兼容），新增四个顶层块：

```jsonc
{
  "version": 2,                       // 主题内容版本
  "manifestVersion": 2,               // 可选；不匹配时只降级应用基础令牌
  "packId": "dev.localmind.theme.aurora",
  "packName": "极光",
  "author": "…",
  "description": "…",
  "license": "字体 OFL-1.1，图片 CC0",

  // ① 颜色：v1 的 tokens 不变，只是白名单扩大（新增 label/accent2/bubble… 见 14.4）
  "tokens": { "accent": "#5B8CFF", "groupedBackground": "#0B0D12" },
  "darkTokens": { "accent": "#7FA8FF" },
  "highContrastTokens": { "accent": "#A8C4FF", "label": "#FFFFFF" },  // 新增块

  // ② 资源清单：显式声明，便于审核与缺失回退
  "assets": {
    "fonts": [
      { "id": "ui",      "file": "fonts/Inter-Regular.ttf", "weight": 400, "style": "normal" },
      { "id": "ui",      "file": "fonts/Inter-SemiBold.ttf", "weight": 600, "style": "normal" }
    ],
    "images": [
      { "id": "chatBg",  "file": "images/aurora.webp", "scale": "fill", "tint": "#0B0D12", "tintAlpha": 0.35 }
    ],
    "icons": [
      { "id": "chat",    "file": "icons/chat.png" }
    ]
  },

  // ③ 排版与素材绑定
  "typography": {
    "fontFamily": "ui",                  // 引用 assets.fonts 的 id；缺失 → 回退系统无衬线
    "fontScale": "1.0",
    "lineHeightScale": "1.0",
    "weightShift": "0"                   // -100..100，叠加在每个文本样式原有字重上
  },

  // ④ 分组件样式
  "components": { /* 见 14.5 */ },

  // ⑤ 全局几何与动效
  "geometry": {
    "cornerScale": "1.0",                // 0.0..2.0，乘到所有圆角上
    "density": "comfortable",            // compact | comfortable | spacious
    "pagePadding": "16",                 // 8..28
    "shadow": "soft",                    // none | hairline | soft | elevated
    "borderWidth": "0.5",                // 0..2
    "tabBarHeight": "62"                 // 52..76
  },
  "motion": {
    "preset": "system",                  // system | reduced | brisk | playful
    "speedScale": "1.0"                  // 0.5..2.0
  },

  // ⑥ 应用图标与开屏
  "branding": {
    "appName": "极光",
    "icon": "asset:icons/app.png",       // 仅应用内展示，不覆盖启动器（14.7）
    "splash": { "background": "#0B0D12", "image": "asset:splash/logo.png", "durationMs": "600" }
  },

  "tabs": ["chat", "models", "me"],
  "startTab": "chat"
}
```

### 14.4 颜色白名单的扩大 —— **已实现（2026-10-15）**

v1 只有 5 个颜色真正生效，这限制了"能不能做得好看"。这一节原本是提案，
**现在已经实现**：白名单扩到 64 个令牌（其中 24 个是 COLOR），默认值 = 内置 iOS 值，
**全部接线**。实际实现比下面这张表还多几项（聊天区、输入框、代码块），
完整清单以 §4.1 为准：

| 键名 | 语义 | 影响面 |
| --- | --- | --- |
| `label` / `secondaryLabel` / `tertiaryLabel` | 三级文字色 | 全部文字 |
| `separator` | 分隔线 | 列表分隔、导航栏底线 |
| `fill` / `fillSecondary` | 填充/控件底 | 输入框、次级按钮、未选中开关 |
| `barBackground` / `barBorder` | 导航栏与标签栏 | 顶栏、Tab 栏 |
| `bubbleUser` / `bubbleUserText` | 用户气泡 | 聊天 |
| `bubbleAi` / `bubbleAiText` | AI 气泡 | 聊天 |
| `composerBackground` / `composerBorder` | 输入区 | 聊天 |
| `accentOn` | 主色之上的文字色 | 按钮文字（**解决"浅色主色配白字看不清"**） |
| `overlay` | 浮层遮罩 | Sheet / 弹层 |
| `selection` | 选中高亮 | 列表选中、文本选择 |

> **为什么必须加 `accentOn`**：今天按钮文字恒为白色（`Color.White`）。
> 一个浅黄色的 `accent` 会让按钮文字**完全看不清**，而主题作者没有任何办法修。
> 这是当前设计里最实际的一个洞。

### 14.5 分组件样式（提案）

每个组件块都是**可选的**；缺省 = 今天的样式。所有数字都声明范围，越界夹取。

```jsonc
"components": {
  "messageBubble": {
    "corner": "18",              // 0..32
    "tailCorner": "6",           // 0..32，气泡"尖角"那一侧的圆角
    "maxWidthPercent": "78",     // 60..90
    "paddingH": "14",            // 8..24
    "paddingV": "10",            // 6..20
    "gap": "8",                  // 2..20，同侧气泡间距
    "shadow": "none",            // none | hairline | soft
    "outlineWidth": "0"          // 0..2
  },
  "composer": {
    "corner": "20",              // 0..32
    "background": "fill",        // token 引用（枚举，见 14.6）
    "borderWidth": "0.5",        // 0..2
    "minHeight": "44",           // 36..72
    "buttonStyle": "filled"      // filled | tinted | plain
  },
  "card": { "corner": "12", "outlineWidth": "0", "padding": "16" },
  "navBar": { "height": "52", "titleAlign": "center", "blur": "false", "borderWidth": "0.5" },
  "tabBar": {
    "style": "glass",            // glass | plain | hidden
    "showLabels": "true",
    "height": "62",
    "indicator": "pill",         // pill | underline | none
    "iconSize": "23"             // 18..30
  }
}
```

**注意 `style: "hidden"` 的语义要写清楚**（今天没有这个能力，所以是新的决定）：
`hidden` 时底部栏不渲染，但**导航必须有替代路径** —— 所以 `hidden` 只有在
`tabs` 长度 ≤ 1 时才被接受，否则**回退到 `plain`**（而不是把用户困在某一页）。
这是"安全默认"的一个例子：**宁可忽略你的配置，也不能让应用不可用。**

### 14.6 三种"引用"语法（提案）

为了避免同一个值在多处重复导致的不一致，提案只允许**三种**值形式：

| 形式 | 语法 | 例子 |
| --- | --- | --- |
| 字面量 | 直接写 | `"#FF6B35"` / `"18"` / `"true"` |
| 令牌引用 | `"token:<键名>"` | `"token:accent"`（解析后取该令牌的**已夹取值**） |
| 资源引用 | `"asset:<assets 里的路径>"` | `"asset:images/aurora.webp"` |

引用失败（键名不存在 / 资源缺失）→ **回退该字段的默认值**，并计入 `unknownKeys`
式的报告清单。**任何情况下都不因为引用失败而拒绝整个主题。**

### 14.7 关于"自定义应用图标 / 启动图标"

| 想要的效果 | 提案能做到吗 | 说明 |
| --- | --- | --- |
| **应用内**显示自己的图标（关于页、主题页预览、启动页） | ✅ 可以 | 读 `assets/` 里的图 |
| **覆盖系统启动器图标** | ❌ **不做** | 需要 `activity-alias` + `setComponentEnabledSetting`，是**功能行为**而非外观数据；一个"主题"能改启动图标 = 它能伪装成别的应用（钓鱼入口）。**明确划到范围外** |
| **更换开屏画面** | ✅ 可以（`branding.splash`） | 纯应用内、纯静态 |

### 14.8 版本策略与向前兼容

**两个版本号，各管一件事**：

| 字段 | 位置 | 管什么 | 不匹配时 |
| --- | --- | --- | --- |
| `manifestVersion` | `plugin.json` | **清单格式**（能力/权限声明的结构） | 主程序只读它认识的字段；不认识的块忽略并提示 |
| `version` | `ui.json` | **主题内容格式** | 见下 |

**`ui.json` 的 `version` 规则（提案，要写死成契约）**：

| 情况 | 行为 |
| --- | --- |
| `version` 缺失 | 当作 `1`（= 今天的格式） |
| `version` ≤ 主程序支持的最高版本 | 正常解析 |
| `version` > 主程序支持的版本 | **仍然安装，但只应用 `tokens` / `darkTokens` / `tabs` / `appName`**；所有新块忽略，并在安装结果和主题页**明确告知**：`主题声明的版本 vN 高于本版本支持的 vM，已按基础令牌应用` |
| 同版本内的**未知键** | 忽略 + **报出来**（沿用 v1 的 `unknownKeys` 机制，**永不致命**） |
| 同版本内某个块**类型不对**（例如 `assets` 写成数组） | 该块当作缺失，**其余块照常应用**（块级降级，不是整体拒绝） |

**三条铁律**：

1. **未知永远不致命。** 唯一会拒绝整个主题的情况，仍然是 v1 就有的那些
   （缺 `packId`/`packName`、`tabs` 非法、什么都没改）；
2. **每个块独立降级。** 一个块坏了不该拖垮其它块 —— 这是"向前兼容"能真正
   工作的前提；
3. **旧版本主程序跑新主题**必须得到一个**能看的界面**，而不是空白或崩溃。

### 14.9 每个新字段的"三件套"（实现时的验收清单）

任何新字段进白名单前，必须能填满这一行：

| 字段 | 默认值 | 合法范围 / 白名单 | 垃圾输入 → |
| --- | --- | --- | --- |
| `geometry.cornerScale` | `"1.0"` | `0.0 – 2.0` | 回退 `1.0` |
| `geometry.density` | `"comfortable"` | `compact` / `comfortable` / `spacious` | 回退 `comfortable` |
| `geometry.shadow` | `"soft"` | `none` / `hairline` / `soft` / `elevated` | 回退 `soft` |
| `motion.speedScale` | `"1.0"` | `0.5 – 2.0` | 回退 `1.0` |
| `components.messageBubble.maxWidthPercent` | `"78"` | `60 – 90` | 夹取 |
| `typography.fontFamily` | `""`（系统） | `assets.fonts` 里的 `id` | 回退系统字体 |
| `assets.fonts[].file` | — | 合法 TTF/OTF、≤ 4 MB | 跳过该字体并报告 |
| `assets.images[].scale` | `"fill"` | `fill` / `fit` / `tile` / `center` | 回退 `fill` |
| `branding.splash.durationMs` | `"600"` | `0 – 2000` | 夹取 |

**没有这一行，字段不许合并。**

### 14.10 诚实讨论：表达力 vs 安全 vs 可审核性

| 方案 | 表达力 | 安全 | 可审核 | 结论 |
| --- | --- | --- | --- | --- |
| A. 今天：16 个令牌 | 低（只够换色和圆角） | ★★★★★ | ★★★★★（30 秒看完） | 保留为 v1 |
| B. **本文提案**：数据 + 资源 + 分组件 | **中高**（能做"有性格"的主题） | ★★★★☆（需按内容校验资源） | ★★★★☆（结构化，逐块看） | **推荐** |
| C. 模板引擎/表达式（哪怕是"安全子集"） | 高 | ★★☆（每个新函数都是新漏洞面） | ★★（要读懂逻辑） | ❌ 不做 |
| D. 加载插件代码（`DexClassLoader`） | 无限 | ☆ | ☆ | ❌ **永不** |

**审核成本才是真正的瓶颈。** B 的每个字段都能被一句"它能干什么"说完
（"这个数字是气泡圆角，0–32"），而 C/D 需要审核者理解**逻辑**：
"这个表达式在什么输入下会算出什么"。插件生态的规模一旦上来，
审核成本会直接决定这个系统能不能活下去。

**明确留在范围外的，以及为什么**：

| 不做 | 为什么 |
| --- | --- |
| 自定义布局 / 新页面 | 那是"改功能"，不是"改外观"；正确路径是改源码 |
| 覆盖系统启动图标 | 功能行为 + 钓鱼入口（14.7） |
| SVG 素材 | 解析器攻击面大、可引用外部资源，收益不抵风险 |
| 网络资源（远程字体/远程背景图） | 主题必须离线可用；联网还会泄露"谁装了什么主题" |
| 插件自定义**令牌名**（让插件定义新变量） | 白名单一旦可扩展，"未知键报告"机制就失去意义，审核也不再可能 |
| 脚本 / 条件逻辑 / 表达式 | 见 C/D 行 |
| 覆盖无障碍设置（强制动效、强制字号） | 可访问性优先级高于主题；主题只能**在**系统偏好之内调整 |

---

## 15. 附录：全部槽位清单

主题作者最需要的一张表：**界面上每一个"槽位"叫什么、在哪、改哪个令牌能碰到它。**

### 15.1 `IosPalette` —— 全部 20 个槽位

`IosPalette` 是渲染层唯一读的颜色载体（`IosTheme.palette` / `LocalIosPalette`）。
**只有 5 个槽位可以被插件改写**，其余 15 个是写死的 iOS 原色。

| 槽位 | 界面上是什么 | 浅色默认 | 深色默认 | 插件能改吗 | 哪个令牌 |
| --- | --- | --- | --- | --- | --- |
| `blue` | **主色**：按钮、开关、选中态、链接、滑块、进度条、Tab 指示器、勾选 | `#0A84FF` | `#0A84FF` | ✅ | **`accent`**（+ `darkTokens.accent`） |
| `red` | **危险/错误**：删除、清空记忆、失败提示 | `#FF3B30` | `#FF453A` | ✅ | **`danger`** |
| `green` | **成功**：开关打开时的轨道、成功提示 | `#34C759` | `#30D158` | ✅ | **`success`** |
| `groupedBackground` | **页面背景**：根背景、列表页缝隙、Tab 栏玻璃底色、Sheet 背景 | `#F7F7F9` | `#000000` | ✅ | **`groupedBackground`** |
| `secondaryGroupedBackground` | **卡片/单元格背景**：`IosSection`、`IosCard`、列表行、空态图标底 | `#FFFFFF` | `#141416` | ✅ | **`cardBackground`** |
| `barBackground` | 导航栏底色（半透明） | `#F7FFFFFF` | `#F2141416` | ❌ | 提案 14.4 `barBackground` |
| `barBorder` | 导航栏底部发丝线（收起时） | `#14000000` | `#1FFFFFFF` | ❌ | 提案 `barBorder` |
| `separator` | 列表分隔线、单元格分隔、Tab 栏分隔 | `#14000000` | `#1FFFFFFF` | ❌ | 提案 `separator` |
| `label` | 一级文字（标题、正文、单元格标题） | `#0B0B0F` | `#F5F5F7` | ❌ | 提案 `label` |
| `secondaryLabel` | 二级文字（说明、副标题、页脚、空态描述） | `#8A3C3C43` | `#99EBEBF5` | ❌ | 提案 `secondaryLabel` |
| `tertiaryLabel` | 三级文字（占位、箭头、空态图标、极弱提示） | `#4D3C3C43` | `#4DEBEBF5` | ❌ | 提案 `tertiaryLabel` |
| `fill` | 填充控件底（禁用按钮、未选中开关轨道、非激活滑块） | `#1A787880` | `#2E787880` | ❌ | 提案 `fill` |
| `fillSecondary` | 更弱的填充（次级按钮底、未选中标签、选中行底） | `#0F787880` | `#1A787880` | ❌ | 提案 `fillSecondary` |
| `indigo` | 数据可视化：熟悉度条、模型标签、"经验"标签 | `#5E5CE6` | `#5E5CE6` | ❌ | — |
| `purple` | 数据可视化：话多程度条 | `#AF52DE` | `#BF5AF2` | ❌ | — |
| `pink` | 数据可视化：亲密度条、人设行图标 | `#FF375F` | `#FF375F` | ❌ | — |
| `orange` | 数据可视化：主动性条、生图相关图标、**未知令牌警告文字** | `#FF9500` | `#FF9F0A` | ❌ | — |
| `teal` | 数据可视化：熟悉度条、生图模型图标 | `#32ADE6` | `#64D2FF` | ❌ | — |
| `yellow` | 强调色（当前使用面很窄） | `#FFCC00` | `#FFD60A` | ❌ | — |
| `gray` | 中性：取消/禁用图标、未选中项、次要胶囊 | `#8E8E93` | `#8E8E93` | ❌ | — |

补充事实：

* `IosPalette.accents` 是一个**派生列表**：`[blue, indigo, purple, pink, orange,
  green, teal, yellow, red]`，被模型头像配色、标签色板使用。
  **只有其中的 `blue` / `green` / `red` 跟随令牌**，其余 6 个是固定值 ——
  所以换 `accent` 不会改变标签色板的多样性，只会改第一格；
* `IosPalette.isDark` 由深浅色模式决定，**不是令牌**。它被 Tab 栏玻璃的
  alpha 计算、导航栏边界等使用（`if (ios.isDark) … else …`）。

### 15.2 `IosMetrics` —— 全部 8 个槽位

`IosMetrics` 通过 `LocalIosMetrics` 提供（读法：`IosTheme.metrics.<字段>`）。

| 槽位 | 默认 | 用在哪 | 插件能改吗 |
| --- | --- | --- | --- |
| `cardCorner` | `12.dp` | `IosSection`（列表组）、`IosCard` 的圆角 | ✅ **`cardCorner`** |
| `controlCorner` | `10.dp` | `IosPrimaryButton`、`IosSecondaryButton` 的圆角（也是默认参数 `shape`） | ✅ **`controlCorner`** |
| `sheetCorner` | `16.dp` | **没有任何组件读它**（浮层今天用各页自己写的圆角） | ⚠️ 令牌写了会进这个字段，但**无人消费** |
| `pillCorner` | `999.dp` | 胶囊形：`IosPill` / `IosSwitch` / `IosTag` / `IosProgressBar` 的全部圆角 | ❌ 没有对应令牌（提案 `geometry.cornerScale` 会乘到它上面） |
| `pagePadding` | `16.dp` | **没有任何组件读它**（各页写死 `16.dp`） | ❌ 无令牌 |
| `cellMinHeight` | `44.dp` | **没有任何组件读它** | ❌ 无令牌 |
| `tabBarHeight` | `49.dp` | **没有任何组件读它**（Tab 栏写死 `62.dp`） | ❌ 无令牌 |
| `navBarHeight` | `44.dp` | **没有任何组件读它**（导航栏写死 `52.dp`） | ❌ 无令牌 |

> **实现细节（值得知道）**：`LocalMindTheme` 每次都**新建**一个 `IosMetrics`，
> 只填 `cardCorner` / `controlCorner` / `sheetCorner` 三项，
> 其余五项**永远是 data class 的默认值**。
> 所以即使将来给 `pagePadding` 加了令牌，也要记得在这里接上。

### 15.3 `IosTypography` —— 全部 11 个文本样式

| 样式 | 字号/行高 | 字重 | 典型用在哪 |
| --- | --- | --- | --- |
| `largeTitle` | 34 / 41 | Bold | 大标题（引导页标题、iOS 大标题） |
| `title1` | 28 / 34 | Bold | 一级标题 |
| `title2` | 22 / 28 | Bold | 二级标题 |
| `title3` | 20 / 25 | SemiBold | **用得最多**：页面小标题、弹层标题、模型名、人设名 |
| `headline` | 17 / 22 | SemiBold | 按钮文字、导航栏标题、单元格主文字（强调） |
| `body` | 17 / 22 | Normal | **正文**：单元格标题、输入文字、正文段落 |
| `callout` | 16 / 21 | Normal | 稍小的正文 |
| `subheadline` | 15 / 20 | Normal | 次要正文、Toast 文字、空态描述 |
| `footnote` | 13 / 18 | Normal | 页脚说明、"自己写插件"、小按钮 |
| `caption1` | 12 / 16 | Normal | 副标题、时间戳、设置项说明 |
| `caption2` | 11 / 13 | Normal | 胶囊标签、Tab 栏文字（Tab 栏会覆写成 `10.sp`） |

**关键事实**：

* 全部样式共用 `FontFamily.SansSerif`（**平台默认无衬线字体**）
  —— 今天**没有任何办法换字体**（提案 14.3 的 `typography.fontFamily`）；
* `fontScale` 令牌会通过 `IosTypography.material(contentScale)` 缩放一份排版
  （只缩 `fontSize` 和 `lineHeight`，**不动字重和字距**），
  然后交给 `MaterialTheme(typography = …)`；
* **但没有任何地方读 `MaterialTheme.typography`。** 全部 325 处文字都是直接写
  `IosTypography.<样式>`，而那些是**写死的常量对象**，不受缩放影响。
  所以 **`fontScale` 今天的实际效果是零**（详见 §4.4）。

  | 读取路径 | 引用点数量 |
  | --- | --- |
  | `IosTypography.<样式>`（写死常量） | **325** |
  | `MaterialTheme.typography`（受 `fontScale` 影响） | **0** |

  > **对主题作者**：今天**没有任何令牌能让字变大或变小**。
  > 如果将来要走通 `fontScale`，最小改动是把 `IosTypography` 的样式读取换成
  > 一个 CompositionLocal，让那 325 处一起受益。

### 15.4 结构与动效槽位（今天不可改，但决定了"能不能好看"）

| 槽位 | 今天 | 可否被令牌影响 |
| --- | --- | --- |
| 底部标签栏形状 | 浮动椭圆胶囊（`RoundedCornerShape(percent = 50)`），高 `62.dp`，左右 `12.dp`，离底 `10.dp` + 导航条 inset | ❌（提案 `components.tabBar`） |
| 标签栏玻璃层 | 实时模糊背景 + 白色渐变 tint + 1px 高光描边 + spring 指示器 | ❌ |
| 标签栏项目 | 图标 `23.dp`，选中放大 `1.12` + 上移 `1.5dp`，文字 `10.sp` | ❌（提案 `iconSize` / `indicator`） |
| 导航栏 | 高 `52.dp`，标题居中，左右各 `84.dp` | ❌（提案 `components.navBar`） |
| 卡片阴影 | **没有阴影**，靠背景色差 + 发丝线区分层次 | ❌（提案 `geometry.shadow`） |
| 动效曲线 | `Motion`：`smooth`（无回弹 tween 220ms）、`snappy`（回弹 0.82）、`bouncy`（0.55）、`gentle`（0.9）；错峰 `28ms`；按下缩放 `0.96` | ❌（提案 `motion.preset` / `speedScale`） |
| 页面切换 | 同级标签：淡入 + 上浮 3.5%；子页：右侧推入 25% | ❌ |
| 沉浸式聊天 | 进入某个对话后**底栏收起** | ❌ |

---

## 16. 一页速查（打印这一页）

```
【zip】扁平。plugin.json + ui.json 放在【根目录】。
       全选文件 → 压 zip（不要压文件夹）。

【装】我的 → 插件 → 右上角「+」→ 选 zip → 装完立刻生效。

【值】tokens 的值一律是字符串："18" / "1.0" / "true" / "#FF6B35"

【颜色】#RGB / #RRGGBB / #AARRGGBB，大小写都行，写错 → 回退默认（不报错）

【深色】darkTokens 用【浅色键名】：
        ✅ "darkTokens": { "groupedBackground": "#0A0A0C" }
        ❌ "darkTokens": { "groupedBackgroundDark": "#0A0A0C" }

【数字】越界被夹到 min/max，写错回退默认。都不会崩。

【真正有效的 7 个令牌】
        accent  danger  success  groupedBackground  cardBackground
        cardCorner  controlCorner

【写了没用的 9 个】accentDark  groupedBackgroundDark  cardBackgroundDark
        sheetCorner  bodySize  tabStyle  showTabBarLabels  navTitleAlign
        fontScale   ← 读了但没人消费，等于没用（§4.4）

【今天完全看不到效果的顶层字段】tabs  startTab  appName
        （写错反而会让主题装不上 → 不确定就别写）

【改不了的东西】字体、字号、背景图、阴影、气泡、图标、动效
        （见 §14 提案，尚未实现）

【多个主题插件】只取最后一个启用的，不合并。用「我的 → 主题」点选。

【优先级】内置 iOS 原色 → 插件 → 用户手动覆盖

【出问题】我的 → 主题 → 点开主题看「改了什么」（显示的是【夹取之后】的值）
        以及橙色的「不认识的令牌」列表。
```

---

*本文对应源码：`data/UiTheme.kt`、`data/Plugin.kt`、`data/PluginArchive.kt`、
`data/Storage.kt`、`ui/PluginInstall.kt`、`ui/AppViewModel.kt`、
`ui/theme/IosTheme.kt`、`ui/screen/ThemeScreen.kt`、`ui/LocalMindRoot.kt`、
`ui/components/LiquidGlassTabBar.kt`、`ui/components/IosComponents.kt`、
`ui/components/IosScaffold.kt`、`ui/motion/Motion.kt`。
第十四节是提案，不对应任何现有代码。*
