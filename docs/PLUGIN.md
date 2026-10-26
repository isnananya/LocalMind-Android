# LocalMind 插件开发指南

> 版本：1.0.6 起。界面布局（screens / overrides / 首页块）另见 [UI-LAYOUT.md](UI-LAYOUT.md)；
> 外观令牌参考见 [UI-PLUGIN.md](UI-PLUGIN.md)。

> 一个插件 = **一个 zip + 几份 JSON**。
> **默认只处理数据**；带 `classes.dex` 的代码插件会在安装前单独确认，
> 以主程序权限运行 —— 只装你信任的来源。
>
> 这份文档面向"会改 JSON、不想写代码"的人。所有字段名、默认值、取值范围、
> 报错原文都来自源码，不是概述。

---

## 0. 先记住这 8 条

| # | 事实 | 意味着什么 |
| --- | --- | --- |
| 1 | 主程序**不带**任何模型库、音色表、表情数据 | "要先装插件才有内容"是**正常状态**，不是缺配置 |
| 2 | 用户只做一个动作：**选一个 zip** | 是表情包还是模型库，由程序按内容判断，不用用户先想清楚 |
| 3 | zip 必须**扁平**：`plugin.json` 在压缩包**根目录** | 这是第一大坑，见 §2 |
| 4 | 表情包 = **图片梗图**，`char`（emoji）只是兜底 | 两者都给时图片优先 |
| 5 | 界面插件默认只改**声明式令牌**；逻辑要写代码就走 `code_plugin` | 令牌改配色/圆角/底栏；需要逻辑时用 dex 插件（要用户确认） |
| 6 | 越界数值会被**夹住**，颜色写错**回退默认** | 你的 JSON 写得再糙也不会把应用弄崩 |
| 7 | 不认识的能力 id、不认识的令牌键会被**报出来** | 不会静默吞掉，你会知道自己写错了 |
| 8 | 文本单文件 ≤ 1 MB，单张图 ≤ 8 MB，全部图片 ≤ 120 MB，条目 ≤ 512 | 超出的会被跳过，跳过几项会在安装结果里说明 |

---

## 1. 30 秒快速上手

```
1. 建一个文件夹（名字随意）
2. 里面放 plugin.json + 你要的数据文件（emoji.json / models.json / voices.json / ui.json）
   如果有图片，放进里面的 assets/ 目录
3. 选中【里面的文件】→ 压缩成 zip（不要压缩那个文件夹！）
4. 应用里：我的 → 插件 → 右上角「+」→ 选这个 zip
5. 回到插件页，确认这一行是「已启用」（开关打开）
```

最小可用插件（只有 2 个文件，扁平）：

```
我的表情包.zip
├── plugin.json
├── emoji.json
└── assets/
    └── happy.png
```

`plugin.json`：

```json
{
  "id": "com.example.mystickers",
  "name": "我的表情包",
  "version": "1.0.0",
  "author": "你",
  "description": "两张图片表情。",
  "abilities": ["emoji_pack"],
  "manifestVersion": 1
}
```

`emoji.json`：

```json
{
  "version": 1,
  "packId": "com.example.mystickers",
  "packName": "我的表情包",
  "author": "你",
  "license": "图片由我自己绘制，CC0",
  "stickers": [
    { "emotion": "开心", "image": "happy.png", "char": "😄", "description": "开心的大笑", "aliases": ["高兴", "笑"] },
    { "emotion": "无语", "char": "😐", "description": "面无表情", "aliases": ["服了", "麻了"] }
  ]
}
```

装完之后，AI 在回复里写 `[[emoji: 开心]]` 就会发那张图。

---

## 2. zip 的结构

```
插件名.zip                     ← 用户选中的就是这个文件
├── plugin.json                ← 清单。强烈建议写（不写也能装，见下）
├── emoji.json                 ← 表情包内容（可选）
├── models.json                ← 模型库内容（可选）
├── voices.json                ← 语音音色内容（可选）
├── ui.json                    ← 界面主题内容（可选）
├── README.md                  ← 可选，给人看的说明
├── LICENSE                    ← 可选，授权声明
└── assets/                    ← 资源目录：图片放这里
    ├── happy.png
    └── speechless.png
```

> ### ⚠️ 第一大坑：zip 里套了一层文件夹
>
> **错误的**（压了文件夹）：
>
> ```
> 我的表情包.zip
> └── 我的表情包/            ← 多出来的这一层
>     ├── plugin.json
>     └── emoji.json
> ```
>
> **正确的**（压的是文件）：
>
> ```
> 我的表情包.zip
> ├── plugin.json
> └── emoji.json
> ```
>
> 各平台的正确做法：
>
> | 平台 | 做法 |
> | --- | --- |
> | Windows | 进入文件夹 → 全选里面的**内容** → 右键 → 发送到 → 压缩(zipped)文件夹 |
> | macOS | 进入文件夹 → 全选内容 → 右键 → 压缩 |
> | 手机（MT 管理器 / 解压专家） | 进入文件夹 → 长按选中多个条目 → 压缩 |
>
> **顺带说明（诚实版）**：当前版本的读取是**按文件名（basename）**取用的，
> 所以"套一层文件夹"在多数情况下**仍然能装上**。但不要依赖它：
>
> * 两个不同子目录里同名的 `emoji.json` / 图片会互相覆盖，**后面读到的赢**，你不会收到任何提示；
> * 扁平是文档、示例生成器、示例插件（`plugins-src/`）唯一使用的形态，也是唯一被测试的形态；
> * 这条路以后可能被收紧（按"根目录必须有 `plugin.json`"来判）。
>
> 一句话：**永远打成扁平 zip。**

### 读取白名单：只有这些文件会被读

| zip 里的路径 | 处理方式 |
| --- | --- |
| `plugin.json` | 读成文本（清单） |
| `emoji.json` / `models.json` / `voices.json` / `ui.json` | 读成文本（对应能力的内容文件） |
| `README.md` / `LICENSE` | 读成文本（允许，但当前界面没有展示它们的入口） |
| 路径里含 `assets/` 的任意文件 | 解压到 `filesDir/plugins/<插件id>/assets/` |
| 其它一律 | **忽略**，并在安装结果里计数：`（忽略了 N 个无关文件）` |

大小与数量上限（超出即跳过，不会导致安装失败）：

| 限制 | 数值 | 超限表现 |
| --- | --- | --- |
| 单个文本文件 | 1 MB | 该文件被丢弃（例如 `emoji.json` 直接变成"不存在"） |
| 单个资源文件（图片） | 8 MB | 该图片被丢弃，`image` 指向它时会退回 `char` 或什么都不发 |
| 全部资源合计 | 120 MB | 超出的图片被丢弃（防 zip 炸弹） |
| zip 条目总数 | 512 | 第 512 个之后的条目忽略 |

其它安全规则（都是硬规则，不是建议）：

* 路径里出现 `..` 或以 `/` 开头 → 该条目直接跳过（防 zip-slip）；
* 资源文件**只取文件名**，不接受任何目录穿越；
* **默认不执行代码**：数据类插件没有任何可执行文件。带 `classes.dex` 的代码插件
  是显式开启的第二条路，安装时会弹确认框，且异常只停用该插件。

---

## 3. `plugin.json`（清单）

```json
{
  "id": "com.example.myplugin",
  "name": "我的插件",
  "version": "1.0.0",
  "author": "你的名字",
  "description": "一句话说清楚它做什么。",
  "abilities": ["emoji_pack"],
  "requires": ["network"],
  "config": { "note": "这里只能放字符串" },
  "manifestVersion": 1
}
```

| 字段 | 必填 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | ✅ | string | — | 稳定唯一 id，建议反向域名。**只允许**字母、数字、`.`、`-`、`_` |
| `name` | ✅ | string | — | 显示名 |
| `abilities` | ✅ | string[] | `[]` | 至少要 1 个，且必须是 §4 表里的 id |
| `version` | | string | `"1.0.0"` | 语义化版本，显示用 |
| `author` | | string | `""` | 显示用 |
| `description` | | string | `""` | 显示用。**授权声明也建议写在这里**（README 目前没地方看） |
| `requires` | | string[] | `[]` | 安装前提示用。见下表的翻译表 |
| `config` | | Map&lt;string,string&gt; | `{}` | 任意键值，**键和值都必须是字符串**，由具体能力解释 |
| `manifestVersion` | | int | `1` | 清单格式版本 |

`requires` 会被翻译成一句人话提示（大小写不敏感）：

| 你写 | 提示 |
| --- | --- |
| `network` / `internet` | 需要联网 |
| `storage` / `files` | 需要读写文件 |
| `microphone` | 需要麦克风 |
| `camera` | 需要相机 |
| 其它任意字符串 | `需要：<你写的原文>` |

### 校验规则（决定了你会看到哪句报错）

| 情况 | 结果 |
| --- | --- |
| `plugin.json` 是空文件/全空白 | `内容是空的` |
| JSON 语法错误 / 字段类型不对（如 `config` 里放了数字） | `不是合法的插件清单：<解析器原文，截断 120 字>` |
| 没有 `id` | `缺少 id` |
| `name` 空，或 `abilities` 为空，或 `id` 含非法字符 | `清单不完整：name=…，abilities=N 个；id 只能包含字母、数字、点、短横线和下划线` |
| `abilities` **全部**都是主程序不认识的 id | `这个插件声明的能力当前版本都不认识：…。已知能力：…` |
| `abilities` 里**部分**不认识 | 安装成功；插件页会把不认识的 id 原样显示成一个**灰色小标签** |
| 清单里有主程序不认识的**字段** | 忽略（`ignoreUnknownKeys`），不报错 |

### 不写 `plugin.json` 也能装（自动合成清单）

主程序会按包里**实际存在的内容文件**反推能力，并合成一份清单：

| 合成字段 | 取值来源 |
| --- | --- |
| `abilities` | 有 `emoji.json` → `emoji_pack`；`models.json` → `model_library`；`voices.json` → `tts_voice`；`ui.json` → `ui_theme`（可多个） |
| `id` | `plugin.json` 的 id → 否则 `emoji.json` 的 `packId` → 否则 `models.json` 的 `packId` → 否则 **zip 的文件名（去扩展名）** |
| `name` | 表情包 → 模型库 → 语音 → 主题里第一个能拿到的 `packName` |
| `author` / `description` | 同理，取内容文件里的 `author` / `description`、`note` |

反推不到的四种情况会失败（原文见 §12.1）。**推荐还是自己写清单** —— 这样名字、
作者、描述是你说了算，而不是被文件名决定。

---

## 4. 能力总表

这是**白名单**。插件只能声明这张表里的 id —— 所以"装个插件"永远不会让应用
去执行它不理解的逻辑。表里的顺序就是插件页上的显示顺序。

| `abilities` id | 中文名 | 需要哪个内容文件 | 消费端已接好？ | 装上了会发生什么 |
| --- | --- | --- | --- | --- |
| `emoji_pack` | 表情包 | `emoji.json` + `assets/` | ✅ 是 | AI 能按 `[[emoji: 标签]]` 发表情（图片或 emoji） |
| `model_library` | 模型库 | `models.json` | ✅ 是 | 模型页出现这些条目（含标签筛选），可直接下载 |
| `model_source` | 模型下载源 | 无（读 `config`） | ✅ 是 | 给所有模型下载多加一个镜像地址 |
| `tts_voice` | 语音音色 | `voices.json` | ✅ 是 | 语音页出现可下载的神经音色 |
| `tts_engine` | 语音引擎 | `tts.json` | ✅ 是 | 接入 Breeze TTS 2 这类外部合成服务 |
| `native_backend` | 推理后端 | `backends.json` + `lib/` | ✅ 是 | 提供可 `System.load` 的原生库（生图/语音） |
| `ui_theme` | 界面主题 | `ui.json` | ✅ 是 | 换主色/圆角/字号/导航外观 |
| `ui_layout` | 界面布局 | `layout.json` | ✅ 是 | 换底栏入口、首页块；`screens`/`overrides` 可整页替换内置页面 |
| `chat_action` | 聊天动作 | `actions.json` | ✅ 是 | 输入框上方出现一键提示词动作 |
| `code_plugin` | 代码插件 | `classes.dex` | ✅ 是（需用户确认） | 加载 Java 代码，可注册动作 / 提供整页 View |
| `web_screen` | 网页界面 | `web/index.html` | ✅ 是 | 用 HTML/CSS/JS 写整页，JS 桥可发消息/复制 |
| `pipeline` | 分步流水线 | `pipeline.json` | ✅ 是 | 把长任务拆成小步逐段跑 |
| `remote_provider` | 云端服务 | 无 | ❌ 否（仅声明） | 目前只有界面标签，没有行为 |
| `image_backend` | 生图后端 | 无 | ❌ 否（仅声明） | 同上 |
| `web_tool` | 联网工具 | 无 | ❌ 否（仅声明） | 同上 |
| `mcp_server` | MCP 服务 | 无 | ❌ 否（仅声明） | 同上 |
| `persona_pack` | 人设包 | 无 | ❌ 否（仅声明） | 同上 |
| `api_endpoint` | 接口扩展 | 无 | ❌ 否（仅声明） | 同上 |

界面上怎么区分：**已实现**的能力标签是蓝色，**仅声明**的和**不认识**的是灰色
（不认识的直接显示原始 id）。声明了未实现的能力不会报错，但也不会有行为 ——
如实告知比假装可用重要。

一个 zip 里可以同时有多个能力（比如同时带 `emoji.json` 和 `ui.json`），
`abilities` 里都写上即可。

---

## 5. `emoji.json` —— 表情包

### 5.1 先说清楚：表情包是**图片**，不是 emoji

中文语境里的"表情包"是**图片梗图**：一张图能表达的"我不想说但我有情绪"，
emoji 表达不出来。所以：

* `image`：**首选**。写 `assets/` 下的**文件名**（如 `happy.png`）；
* `char`：**兜底**。一个 emoji 字符（如 `😄`），给不想带图片的轻量包用；
* **两个都给时图片优先**；图片文件找不到时（被大小上限丢掉、名字写错）才退回 `char`；
* 两个都没有的条目是非法的，会让整个 `emoji.json` 被拒绝。

### 5.2 顶层字段（`StickerPack`）

| 字段 | 必填 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `packId` | ✅ | string | `""` | 包 id。也是资源目录名的兜底来源 |
| `packName` | ✅ | string | `""` | 显示名 |
| `stickers` | ✅ | array | `[]` | 表情条目，不能为空 |
| `version` | | int | `1` | 格式版本 |
| `author` | | string | `""` | |
| `description` | | string | `""` | |
| `license` | | string | `""` | **强烈建议填**：图片素材的授权，是使用者最需要知道的事 |
| `home` | | string | `""` | 主页/仓库地址 |
| `keywords` | | Map&lt;string,string&gt; | `{}` | 关键词 → 情感标签的兜底映射：`{"哈哈": "开心"}` |
| `promptHint` | | string | `""` | 额外注入给模型的提示词片段（留空用主程序的通用说明） |

### 5.3 表情条目（`StickerEntry`）

| 字段 | 必填 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `emotion` | ✅ | string | — | 情感标签，中文即可（`"开心"`）。**模型就是按这个点菜的** |
| `image` | | string | `""` | `assets/` 下的文件名，如 `"happy.png"`（写成 `assets/happy.png` 也能用，只取最后一段，但推荐只写文件名） |
| `char` | | string | `""` | 兜底 emoji，如 `"😄"`。可以是多个字符（`"😂🤣"`） |
| `description` | | string | `""` | 更细的场景描述（`"开心的大笑"`），模糊匹配也会用到 |
| `aliases` | | string[] | `[]` | 同义标签，让模型换个词也能命中 |
| `weight` | | int | `1` | 声明里有，**但当前实现不使用** —— 见 §15 |

### 5.4 完整示例（带图片）

`emoji.json`：

```json
{
  "version": 1,
  "packId": "com.example.mystickers",
  "packName": "我的表情包",
  "author": "你",
  "description": "6 张图片表情。图片在 assets/ 里，image 写文件名。",
  "license": "示例图由我绘制，CC0。转载请保留此声明。",
  "home": "",
  "promptHint": "标签挑最贴近此刻语气的一个；发出来是一张小图，别在正文里解释它。",
  "keywords": {
    "哈哈": "开心", "笑死": "开心",
    "无语": "无语", "服了": "无语", "麻了": "无语",
    "呜呜": "难过", "难受": "难过"
  },
  "stickers": [
    {
      "emotion": "开心",
      "image": "happy.png",
      "char": "😄",
      "description": "开心的大笑",
      "aliases": ["高兴", "快乐", "笑"],
      "weight": 3
    },
    {
      "emotion": "无语",
      "image": "speechless.png",
      "char": "😐",
      "description": "面无表情的无语",
      "aliases": ["服了", "无奈"]
    },
    {
      "emotion": "难过",
      "char": "😢",
      "description": "有点难过（这个包故意不放图，演示 char 兜底）",
      "aliases": ["伤心", "委屈"]
    }
  ]
}
```

对应的 zip：

```
我的表情包.zip
├── plugin.json            （abilities: ["emoji_pack"]，id 建议与 packId 一致）
├── emoji.json
└── assets/
    ├── happy.png          ← 建议 ≤ 几百 KB，单张硬上限 8 MB
    └── speechless.png
```

装完之后，图片会被解压到插件自己的资源目录
（`filesDir/plugins/<插件id>/assets/`），聊天里显示的是这张本地图片。

### 5.5 模型怎么触发、怎么匹配

模型在回复里写：`[[emoji: 开心]]`（也认 `[[表情：开心]]`，中英文冒号都行；
标签最多 12 个字符）。解析按可靠性从高到低三级：

| 级别 | 判据 |
| --- | --- |
| 1. 精确 | `emotion` 或 `aliases` 里有一项与模型写的词**完全相等**（忽略大小写） |
| 2. 关键词表 | `keywords` 里有这个键 → 取它映射到的 `emotion` |
| 3. 模糊 | 互相包含：`emotion` 与词互相包含，或 `description` 包含该词。**只对长度 ≥ 2 的词生效** |

三级都不中 → **什么都不发**（宁可不发，也不乱发；发错表情比不发更糟）。
命中结果会被缓存，同一个词不会重复计算。

注入给模型的提示词里**只列标签**（不列全部条目），所以标签要短、好记、彼此区分明显。
如果同一个包里有两条相同 `emotion`，**当前实现取靠前的那条**（`weight` 不参与排序）。

### 5.6 校验（决定你会看到哪句报错）

| 情况 | 报错原文 |
| --- | --- |
| 文件空 | `emoji.json 是空的` |
| JSON 语法/类型错误 | `emoji.json 格式错误：<解析器原文>` |
| 没 `packId` | `emoji.json 缺少 packId` |
| 没 `packName` | `emoji.json 缺少 packName` |
| `stickers` 空（**或字段名写错，例如写成了 `emojis`**） | `emoji.json 里 stickers 是空的` |
| 有条目缺 `emotion` | `有 N 个表情缺少 emotion（标签）` |
| 有条目既无 `image` 也无 `char` | `有 N 个表情既没有 image 也没有 char（第一个的 emotion='…'）` |

`emoji.json` 解析失败**不会**让安装失败：插件照样装上，但内容为空，
安装结果里会带一句 `表情包：<原因>`。所以**一定要看安装后弹出的那句话**。

### 5.7 同一时刻只有一个表情包生效

表情包只保存**一份**（不是按插件存多份）—— 否则提示词里会同时出现两套标签，
模型会摇摆。所以：**最后安装的那个表情包赢**。想换回去，重新导入旧的 zip 即可。

---

## 6. `models.json` —— 模型库

**主程序内置清单故意是空的**（`ModelCatalog.models = emptyList()`）。
也就是说：不装模型库插件，模型页就没有任何可下载条目 —— 这是设计，不是 bug。

### 6.1 顶层字段（`ModelPackFile`）

| 字段 | 必填 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `models` | ✅ | array | `[]` | 不能为空 |
| `packId` | | string | `""` | 空则用 `plugin.json` 的 `id` 兜底 |
| `packName` | | string | `""` | 显示名 |
| `author` | | string | `""` | |
| `updatedAt` | | string | `""` | 自己填的日期字符串，显示用 |
| `note` | | string | `""` | 说明 |
| `version` | | int | `1` | 格式版本 |

### 6.2 模型条目（`LocalModel`）

⚠️ 见"必填"列：**没有默认值的字段缺一个，整个文件就解析失败**（报解析器原文）。

| 字段 | 必填 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | ✅ | string | — | 唯一 id。同 id 时**插件里的条目赢过内置清单** |
| `name` | ✅ | string | — | 显示名 |
| `repoId` | ✅ | string | — | `owner/repo`，显示 + `{repo}` 占位符用 |
| `fileName` | ✅ | string | — | 落盘文件名。**必须和真实文件名一致**，否则断点续传找不到文件 |
| `sizeBytes` | ✅ | long | — | 精确字节数。**填 `0` = 下载后不做大小校验**（见 6.4） |
| `url` | ✅ | string | — | 主下载地址 |
| `mirrors` | | string[] | `[]` | 备用地址，按顺序回退 |
| `license` | | string | `"unknown"` | 授权名 |
| `publisher` | | string | `""` | 发布者（会影响「DeepSeek」「中文」分类，见 6.5） |
| `family` | | string | `""` | 系列名（`"Qwen2.5"`）。**`"Stable Diffusion"` 是生图模型的判据** |
| `params` | | string | `""` | 参数量，如 `"1.5B"` |
| `quant` | | string | `""` | 量化，如 `"Q4_K_M"` |
| `description` | | string | `""` | 一句话说明 |
| `tags` | | string[] | `[]` | 标签，会影响分类筛选（见 6.5） |
| `recommendedRamGb` | | int | `4` | 建议内存。目前用于「推荐」分类的筛选（≤ 8） |
| `vision` | | bool | `false` | `true` = 多模态（能看图） |
| `embedding` | | bool | `false` | `true` = 向量模型 |
| `decisionModel` | | bool | `false` | `true` = 决策模型（做判断而不是聊天） |
| `imported` | | bool | `false` | **插件不要填**（这是"本机导入文件"的标记） |
| `packId` | | string | `""` | **插件不用填**：安装时会被改写成本包的 `packId` |

### 6.3 路由含义：一个标记决定它出现在哪个槽位

| 出现在哪 | 判据（`ModelRoutingSection` / `ModelsScreen`） |
| --- | --- |
| 对话模型（默认槽位） | 四个标记**全否**：`!decisionModel && !vision && !isImageModel && !embedding` |
| 识图 / 看图 | `vision: true` |
| 生图 | `family` **精确等于** `"Stable Diffusion"` |
| 决策 | `decisionModel: true` |
| 向量 | `embedding: true` |

也就是说：**只想让它当对话模型，就三个标记都别写**；写了 `vision: true` 它就不会
再出现在普通对话模型的可选列表里。挑错了不要紧，改 `models.json` 重新导一遍即可。

### 6.4 `sizeBytes: 0` 的用法

| 你填 | 下载完成后的行为 |
| --- | --- |
| 真实字节数 | 落盘大小不等于它 → 报 `大小不符：期望 X，实际 Y`（**文件其实下完了，但状态是失败**） |
| `0` | 不做精确校验，只看"下载字节数是否达到 Content-Length/预期" |

所以：**不确定就填 `0`**，先跑通再加精确值。取真实大小：

```powershell
# Windows
(Invoke-WebRequest -Uri "<url>" -Method Head).Headers['Content-Length']
```

```bash
# Linux / macOS
curl -sI "<url>" | grep -i content-length
```

### 6.5 分类筛选是靠什么命中的

模型页的分类标签用的是下面这些判据，写对 `tags` / `publisher` / `family`
才能出现在对应的分类里：

| 分类 | 判据 |
| --- | --- |
| 推荐 | `recommendedRamGb <= 8` 且不是向量、不是生图 |
| DeepSeek | `publisher` 含 `DeepSeek`，或 `family` 以 `DeepSeek` 开头 |
| Qwen | `family` 以 `Qwen` 开头 |
| 中文 | `tags` 含 `"中文"`，或 `publisher` 含 `Qwen` / `BAAI` |
| 推理 | `tags` 含 `"思维链"` / `"推理"` / `"数学"` |
| 英文 | `tags` 含 `"英文"` / `"英文强"` |

### 6.6 完整示例

```json
{
  "version": 1,
  "packId": "com.example.mylib",
  "packName": "我的模型库",
  "author": "你",
  "note": "把 url 换成你自己的直链（.gguf）。sizeBytes 不确定就填 0。",
  "models": [
    {
      "id": "my-first-model",
      "name": "我的模型（镜像）",
      "repoId": "owner/repo",
      "fileName": "my-model-Q4_K_M.gguf",
      "sizeBytes": 0,
      "url": "https://hf-mirror.com/owner/repo/resolve/main/my-model-Q4_K_M.gguf",
      "mirrors": ["https://huggingface.co/owner/repo/resolve/main/my-model-Q4_K_M.gguf"],
      "license": "Apache-2.0",
      "publisher": "owner",
      "family": "Qwen2.5",
      "params": "1.5B",
      "quant": "Q4_K_M",
      "description": "示例条目，改掉它。",
      "tags": ["中文"],
      "recommendedRamGb": 4
    }
  ]
}
```

### 6.7 校验与去重

| 情况 | 报错原文 |
| --- | --- |
| `models` 空 | `模型包里没有任何模型` |
| 有条目缺 `id` / `fileName` / `url` | `有模型条目缺少 id / fileName / url` |
| 缺没有默认值的字段（`name` / `repoId` / `sizeBytes` …） | 解析器原文（形如 `Field 'xxx' is required…`） |
| JSON 语法错误 | 解析器原文 |

安装结果里的前缀是 `模型库：<原因>`。安装成功时会显示
`模型库 <packName>（N 个模型）`，并且这个包**默认启用**（刚导入就是想用它）。

去重规则：模型库条目 **优先于** 内置清单，同 id 时插件赢；再按 `fileName`
（忽略大小写）去重。用户自己导入的本地文件排在最后。

模型包和界面主题一样，可以在模型页单独关掉（不影响插件本身）。

---

## 7. `voices.json` —— 语音音色

音色清单也不在主程序里：音色模型 13–115 MB 一个，内置一份清单只会让地址
写死在 APK 里。不装语音插件时，语音页就是空的。

### 7.1 顶层字段（`VoicePackFile`）

| 字段 | 必填 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `voices` | ✅ | array | `[]` | 不能为空 |
| `packId` | | string | `""` | 空则用 `plugin.json` 的 `id` 兜底 |
| `packName` | | string | `""` | 显示名（空会被拒绝） |
| `author` | | string | `""` | |
| `license` | | string | `""` | 建议填：音色模型各自的许可 |
| `home` | | string | `""` | |
| `note` | | string | `""` | |
| `version` | | int | `1` | |

### 7.2 音色条目（`VoiceEntry`）

| 字段 | 必填 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | ✅ | string | — | 稳定 id，**默认就等于官方 tarball 名**（不含 `.tar.bz2`） |
| `label` | ✅ | string | — | 中文显示名，如 `"小雅（女声 · 普通话）"` |
| `description` | | string | `""` | 说明 |
| `badge` | | string | `""` | 徽标，如 `"轻量"` / `"角色"` |
| `url` | | string | `""` | 压缩包地址。**当前实现忽略它**，见 §15 |
| `sizeBytes` | | long | `0` | 压缩包字节数。`0` = 未知，进度只能按已下载量显示；有 Content-Length 时优先用它 |
| `mirrors` | | string[] | `[]` | 备用地址（当前实现同样忽略，见 §15） |
| `sampleText` | | string | `"你好，我是运行在你手机上的语音模型。"` | 试听用的短句（音色差别在短句上听不出来） |

### 7.3 音色从哪来

目前唯一被使用的来源是 **sherpa-onnx 官方 Release 的 `tts-models` 标签**：

```
https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/<id>.tar.bz2
```

下载器按 `id` 拼这个地址，直连失败会自动回退到 `https://gh-proxy.com/` 前缀
（实测直连 GitHub 会随机超时）。压缩包解开到 `filesDir/voices/<id>/` 之后，
主程序**扫目录找布局，不写死路径**：

| 找什么 | 要求 | 说明 |
| --- | --- | --- |
| 主模型 | **必需** | 目录树里**最大的那个 `.onnx`**（扩展名忽略大小写）。目录里还带 vocoder / duration 小模型时，按体积挑才不会抓错 |
| `tokens.txt` | 可选 | 与主模型同目录 |
| `lexicon.txt` / `lexicon-zh.txt` | 可选 | 各家叫法不同，找不到也能跑 |
| `date.fst` / `number.fst` / `phone.fst` | 可选 | 中文文本正则化（读"2026年"这类数字时才用得上） |

找不到最大的 `.onnx` 就报 `解压后没有找到 VITS 模型文件`，并把这个音色标成失败。
所以：**只要你的 tarball 内部布局是常规 VITS 布局（含一个 `.onnx`），`id` 换成
自己的也能用** —— 前提是那个 tarball 真的能从官方 Release 地址下到（见 §15 第 6 条）。

### 7.4 完整示例

```json
{
  "version": 1,
  "packId": "com.example.myvoices",
  "packName": "我的语音音色",
  "author": "你",
  "license": "音色模型各自遵循其原始许可",
  "note": "id 就是官方 Release 里 tarball 的名字（不带 .tar.bz2）。",
  "voices": [
    {
      "id": "vits-piper-zh_CN-xiao_ya-medium-int8",
      "label": "小雅（女声 · 普通话）",
      "description": "Piper 中文女声，13 MB，离线",
      "badge": "轻量",
      "sizeBytes": 14016124,
      "sampleText": "你好，我是小雅。今天想聊点什么？"
    }
  ]
}
```

### 7.5 校验

| 情况 | 报错原文 |
| --- | --- |
| 文件空 | `voices.json 是空的` |
| JSON 语法/类型错误 | `voices.json 格式错误：<解析器原文>` |
| 没 `packId` | `voices.json 缺少 packId` |
| 没 `packName` | `voices.json 缺少 packName` |
| `voices` 空 | `voices.json 里 voices 是空的` |
| 有条目缺 `id` 或 `label` | `有 N 个音色缺少 id 或 label` |

安装结果里的前缀是 `语音音色：<原因>`。两个插件提供同一个 `id` 时，
**先装的赢**（按 id 去重，稳定可预期）。

---

## 8. `ui.json` —— 界面主题

### 8.1 为什么界面插件用令牌，而不是直接写 Compose 代码

外观和导航需要的是**声明**，不是逻辑：主色、圆角、字号、底栏形状都是值，
写成令牌可校验、可叠加、可回退，插件写错也不会崩。

需要逻辑的插件走独立的 `code_plugin`（见 §9.1）：那是显式安装、显式确认、
可单独停用的另一条路。两条路分开，审计边界才清楚。

所以做成**声明式令牌**：插件只能从一份**固定名单**里挑值。三个好处：

1. **安全**：令牌是数据，不可能执行任何东西，最坏情况是配色难看；
2. **不崩**：越界数值被夹到合法区间，颜色写错回退默认 —— 你的笔误不会让应用起不来；
3. **可组合**：深浅色可以分别给（`tokens` / `darkTokens`）。

代价是表达力有上限。真需要任意布局的人应该去改源码，而不是装插件。

### 8.2 顶层字段（`UiTheme`）

| 字段 | 必填 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `packId` | ✅ | string | `""` | 包 id |
| `packName` | ✅ | string | `""` | 显示名 |
| `tokens` | | Map&lt;string,string&gt; | `{}` | 外观令牌，**值统一用字符串**（`"#FF3B30"` / `"14"` / `"1.08"` / `"true"`） |
| `darkTokens` | | Map&lt;string,string&gt; | `{}` | 深色专用，**覆盖 `tokens` 里的同名项** |
| `tabs` | | string[] | `[]` | 底部标签栏显示哪几个、按什么顺序。只能是 `chat` / `models` / `me` |
| `startTab` | | string | `""` | 首页标签页。只能是 `chat` / `models` / `me`（空 = `chat`） |
| `appName` | | string | `""` | 覆盖应用标题（空 = 用默认） |
| `version` / `author` / `description` / `license` / `home` | | | | 与其它内容文件同义 |

`tokens` / `darkTokens` / `tabs` / `appName` **四个都空**会被拒绝：
`ui.json 什么都没改（tokens / darkTokens / tabs / appName 都是空的）`。

### 8.3 全部可用令牌（16 个，白名单）

**值统一写成字符串。** 名单之外的键不会被应用，但会被**报出来**（见 8.5）。

| 令牌键 | 中文 | 类型 | 默认 | 范围 / 允许值 | 当前是否已接线 |
| --- | --- | --- | --- | --- | --- |
| `accent` | 主色 | COLOR | `#0A84FF` | 任意合法颜色 | ✅ 已生效 |
| `accentDark` | 主色（深色） | COLOR | `#0A84FF` | 同上 | ⚠️ 未接线（深色请用 `darkTokens.accent`） |
| `danger` | 危险色 | COLOR | `#FF3B30` | 同上 | ✅ 已生效 |
| `success` | 成功色 | COLOR | `#34C759` | 同上 | ✅ 已生效 |
| `groupedBackground` | 页面背景 | COLOR | `#F7F7F9` | 同上 | ✅ 已生效 |
| `groupedBackgroundDark` | 页面背景（深色） | COLOR | `#000000` | 同上 | ⚠️ 未接线（深色请用 `darkTokens.groupedBackground`） |
| `cardBackground` | 卡片背景 | COLOR | `#FFFFFF` | 同上 | ✅ 已生效 |
| `cardBackgroundDark` | 卡片背景（深色） | COLOR | `#1C1C1E` | 同上 | ⚠️ 未接线（深色请用 `darkTokens.cardBackground`） |
| `cardCorner` | 卡片圆角 | NUMBER | `12` | 0 – 32 | ✅ 已生效 |
| `controlCorner` | 控件圆角 | NUMBER | `10` | 0 – 32 | ✅ 已生效 |
| `sheetCorner` | 浮层圆角 | NUMBER | `16` | 0 – 40 | ✅ 已生效 |
| `bodySize` | 正文字号 | NUMBER | `17` | 12 – 24 | ⚠️ 未接线（字号请用 `fontScale`） |
| `fontScale` | 整体字号缩放 | NUMBER | `1.0` | 0.8 – 1.4 | ✅ 已生效 |
| `tabStyle` | 标签栏样式 | ENUM | `glass` | `glass` / `plain` / `hidden` | ⚠️ 未接线 |
| `showTabBarLabels` | 标签栏显示文字 | BOOL | `true` | true / false | ⚠️ 未接线 |
| `navTitleAlign` | 导航栏标题位置 | ENUM | `center` | `center` / `leading` | ⚠️ 未接线 |

**深色模式怎么写**：不要用 `xxxDark` 这些键，而是把深色值写进 `darkTokens`，
键名用**浅色那个键**：

```json
{
  "tokens":     { "accent": "#FF6B35", "groupedBackground": "#FDF9F6" },
  "darkTokens": { "accent": "#FF8A5C", "groupedBackground": "#0A0A0C" }
}
```

### 8.4 取值规则：夹取与回退

| 类型 | 规则 |
| --- | --- |
| COLOR | 支持 `#RGB` / `#RRGGBB` / `#AARRGGBB`（大小写都行，会被统一成大写）。不合法 → **回退默认**（不报错） |
| NUMBER | 先按数字解析；失败的 → 回退默认。太小的夹到 `min`，太大的夹到 `max` |
| BOOL | `true` / `1` / `yes` / `on` → `true`；`false` / `0` / `no` / `off` → `false`；其它 → 回退默认 |
| ENUM | 值必须在允许列表里（忽略大小写）；否则 → 回退默认 |

举例：`cardCorner` 写 `"999"` → 实际生效 `32`；`accent` 写 `"红色"` → 实际用默认
`#0A84FF`；`fontScale` 写 `"3"` → 实际生效 `1.4`。**都不会崩，但你也不会得到想要的效果** ——
所以改完要看一眼界面。

### 8.5 未知令牌会被报出来

你写了主程序不认识的键（例如 `"primaryColor"`、`"corner"`、拼错的 `"cardCorner "`），
它不会被应用，但安装结果里会如实说：

```
界面主题 我的主题（忽略了不认识的令牌：primaryColor、corner）
```

这是刻意的：静默接受会让你以为自己改生效了。

### 8.6 优先级：谁说了算

从低到高（后者覆盖前者）：

```
内置 iOS 原色  →  ui_theme 插件  →  用户手动覆盖
```

细节：

1. **内置原色**：没装主题插件时一切照旧，装了才变（只覆盖你**声明了**的令牌，
   没声明的保持 iOS 原色）；
2. **插件**：多个 `ui_theme` 插件**不合并**，只取**最后一个启用的**（多个会互相打架）。
   所以两个主题插件同时开着时，生效的是列表里靠后的那个；插件页会如实说明当前用的是哪个；
3. **用户手动覆盖**：优先级最高（用户永远有最终话语权，插件不该钉死一个用户不喜欢的颜色）。
   注意：当前版本这个覆盖通道在存储与解析层是通的，但还没有对应的界面入口。

`tabs` / `startTab` / `appName` 已经会被解析、校验并保存下来，但当前版本的
界面外壳还没有读取它们（见 §15）。

### 8.7 完整示例

`ui.json`：

```json
{
  "version": 1,
  "packId": "com.example.mytheme",
  "packName": "我的界面主题",
  "author": "你",
  "description": "换主色、加大圆角、隐藏标签栏文字。",
  "license": "你的授权",
  "tokens": {
    "accent": "#FF6B35",
    "danger": "#E5484D",
    "success": "#30A46C",
    "groupedBackground": "#FDF9F6",
    "cardBackground": "#FFFFFF",
    "cardCorner": "18",
    "controlCorner": "12",
    "sheetCorner": "22",
    "fontScale": "1.0"
  },
  "darkTokens": {
    "accent": "#FF8A5C",
    "groupedBackground": "#0A0A0C",
    "cardBackground": "#16161A"
  },
  "tabs": ["chat", "models", "me"],
  "startTab": "chat",
  "appName": ""
}
```

### 8.8 校验

| 情况 | 报错原文 |
| --- | --- |
| 文件空 | `ui.json 是空的` |
| JSON 语法/类型错误 | `ui.json 格式错误：<解析器原文>` |
| 没 `packId` | `ui.json 缺少 packId` |
| 没 `packName` | `ui.json 缺少 packName` |
| `tabs` 里有不认识的页面 | `tabs 里有不认识的页面：xxx。只能是 chat、models、me` |
| `startTab` 不认识 | `startTab 不认识：xxx。只能是 chat、models、me` |
| 什么都没改 | `ui.json 什么都没改（tokens / darkTokens / tabs / appName 都是空的）` |

安装结果里的前缀是 `界面主题：<原因>`。

---

## 8.9 `actions.json` —— 聊天快捷动作

装好之后，输入框上方会出现一排胶囊按钮。点一下就：

* `send` —— 直接把 `prompt` 发出去；
* `insert` —— 把 `prompt` 填进输入框，让你接着改。

`prompt` 里可以写 `{{input}}`，它会被替换成你**当前输入框里的文字**，
所以"翻译/润色/总结"这类动作不需要额外做输入框。

```json
{
  "version": 1,
  "packId": "com.example.quick",
  "packName": "快捷动作",
  "actions": [
    {
      "id": "translate",
      "label": "译成英文",
      "icon": "language",
      "prompt": "把下面这段话翻译成自然英文，只输出译文：\n{{input}}",
      "mode": "send"
    },
    {
      "id": "weekly",
      "label": "写周报",
      "icon": "bolt",
      "prompt": "帮我写一段本周工作总结：",
      "mode": "insert"
    }
  ]
}
```

字段：`id` / `label` / `prompt` 必填；`mode` 只能是 send/insert；`placement`
暂时只支持 `composer`。最多 12 个动作，应用只展示前 6 个（按安装顺序）。
校验失败会在**安装时**报出具体是哪一个动作错了。

---

## 9. `model_source` —— 模型下载源（不用内容文件）

最简单的能力：不读任何内容文件，全靠 `plugin.json` 的 `config`。

```json
{
  "id": "com.example.mirror",
  "name": "我的镜像加速",
  "version": "1.0.0",
  "abilities": ["model_source"],
  "requires": ["network"],
  "config": {
    "baseUrl": "https://my-mirror.example.com/models",
    "filePattern": "{baseUrl}/{file}"
  }
}
```

| `config` 键 | 必填 | 说明 |
| --- | --- | --- |
| `baseUrl` | ✅ | 镜像根地址，末尾有没有 `/` 都行（会被去掉）。**没填这个键的插件会被直接跳过**（只在 logcat 里留一句警告） |
| `filePattern` | | 自己拼 URL 的模板。`{baseUrl}` → 根地址，`{file}` → 文件名，`{repo}` → 仓库 id。不填 = `{baseUrl}/{file}` |

效果：**不改一行代码、不重新编译**，就给所有模型多加一个候选下载地址。
插件提供的地址默认**排在最后**（内置镜像 → 模型条目的 `mirrors` → 插件镜像 → 代理），
因为插件地址是用户自己加的，可信度不如内置源；失败了才会轮到它。
拼出来的地址必须以 `http` 开头才会被采用。

---

## 9.1 `code_plugin` —— 代码插件（classes.dex）

> ⚠️ 这是唯一会**执行代码**的能力。安装时会弹确认；加载/运行异常只把该插件停用。

### 清单

```json
{
  "id": "com.example.tools",
  "name": "工具页",
  "abilities": ["code_plugin", "ui_layout"],
  "config": {
    "entryClass": "com.example.tools.ToolsPlugin",
    "viewClass": "com.example.tools.ToolsView"
  }
}
```

### 入口接口

`com.localmind.plugin.PluginEntry`（纯 String，不需要 Android）：

* `id(): String`
* `onLoad(ctx: PluginContext)` —— 在这里 `ctx.registerAction(id, label)`
* `onAction(actionId: String, input: String): String?` —— 返回要发送的文本

`PluginContext`：`log` / `toast` / `registerAction`。

### 整页 View

清单里的 `config.viewClass` 指定一个继承 `android.view.View`、带
`(Context)` 构造器的类。再配一个 `ui_layout` 插件，把某页 overrides 到：

```json
"overrides": { "home": "code:com.example.tools" }
```

主程序只负责把 View 放进页面，页面内容完全由插件绘制。

### 编译

1. `javac` 编译插件类（实现 PluginEntry 的类 + 可选 View 类）；
2. `d8 --lib android.jar --min-api 26 --output dex <classes>`；
3. zip 根目录放 `plugin.json` + `classes.dex`（可以有多个 class）。

安卓 14+ 要求 dex **只读**；应用在安装/加载时会自动 `setReadOnly()`。

示例：`plugins-src/code-plugin-tools`（动作 + View + layout）。

## 9.2 `web_screen` —— 网页界面（HTML/CSS/JS）

zip 里放：

```
plugin.json
layout.json
web/index.html
web/style.css
web/app.js
```

`abilities` 写 `["web_screen", "ui_layout"]`，overrides 写：

```json
"overrides": { "home": "web:com.example.web.dashboard" }
```

网页加载后可以用 JS 桥：

```js
LM.post(JSON.stringify({ action: 'send', text: '你好' }));
LM.post(JSON.stringify({ action: 'prompt', text: '帮我写一段周报：' }));
LM.post(JSON.stringify({ action: 'toast', text: '提示' }));
LM.post(JSON.stringify({ action: 'copy', text: '复制内容' }));
```

边界：网页没有文件/网络/原生 API，只能通过这四个动作影响主程序。
CSS/JS 随便写，包括动画、渐变、暗色模式。

示例：`plugins-src/web-plugin-dashboard`。

## 9.3 `theme.css` —— 用 CSS 写主题

`ui_theme` 除了 `ui.json`，也可以只放一份 `theme.css`：

```css
:root {
  --accent: #2E6BE6;
  --card-corner: 24;
  --tab-bar-shape: capsule;
  --tab-bar-margin: 16;
  --tab-bar-lift: 6;
  --tab-bar-tint: #F2F6FB;
  --tab-bar-indicator: capsule;
  --tab-bar-blur: 100;
}
.dark {
  --dark-accent: #6C9BFF;
  --dark-grouped-background: #070A0F;
}
```

规则：`--令牌名: 值;`；深色用 `dark-` 前缀；kebab-case 会转成令牌的 camelCase。
只支持变量声明，不实现选择器/层叠/媒体查询。

### 底栏可调令牌（1.0.4 测试通道起）

| 令牌 | 取值 |
| --- | --- |
| `tabBarShape` | capsule / rounded / square |
| `tabBarMargin` | 0–48 dp |
| `tabBarLift` | 0–40 dp |
| `tabBarBlur` | 0–100（0 = 不模糊） |
| `tabBarTint` | 颜色（浅色/深色各一份） |
| `tabBarIndicator` | capsule / underline / dot / none |
| `tabStyle` | glass / plain / hidden |

---

## 10. 打包与测试

### 10.1 写文件

1. 新建一个文件夹（例如 `我的表情包`）；
2. 写好 `plugin.json` 和内容文件（`emoji.json` / `models.json` / `voices.json` / `ui.json`）；
3. 图片放进同级的 `assets/` 目录，`image` 字段写**文件名**；
4. 编辑器建议：VS Code / Notepad++ / 手机上的 MT 管理器。**用记事本改 JSON 容易出编码问题**（见 §14）。

### 10.2 打包（关键一步）

| 平台 | 正确 | 错误 |
| --- | --- | --- |
| Windows | 进文件夹 → `Ctrl+A` 全选**内容** → 右键 → 发送到 → 压缩(zipped)文件夹 | 右键点**文件夹** → 压缩（会套一层） |
| macOS | 进文件夹 → 全选内容 → 右键 → 压缩 | 右键点文件夹 → 压缩 |
| 手机 | 进文件夹 → 多选**里面的条目** → 压缩为 zip | 长按文件夹 → 压缩 |

命令行（最不容易出错）：

```powershell
# Windows PowerShell：在插件目录里执行
Compress-Archive -Path .\plugin.json, .\emoji.json, .\assets -DestinationPath ..\my-plugin.zip -Force
```

```bash
# macOS / Linux：在插件目录里执行
zip -r ../my-plugin.zip plugin.json emoji.json assets
```

两者都是把**条目**放进 zip 根，不会多一层文件夹。

### 10.3 导入并检查

```
我的 → 插件 → 右上角「+」（或页面上「导入插件 zip」）→ 选 zip
```

导入后会弹一句结果，**这句话就是你的第一手调试信息**：

| 结果长什么样 | 含义 |
| --- | --- |
| `已安装 我的表情包：表情包 我的表情包（6 个，其中 5 张图片）` | 成功，且有 5 张图被解压 |
| `已安装 X：表情包：有 2 个表情既没有 image 也没有 char（第一个的 emotion='开心'）` | 装上了，但内容为空 —— 去改 JSON |
| `已安装 X（忽略了 3 个无关文件）` | 成功，但 zip 里有 3 个主程序不认识的文件（例如 `__MACOSX/`、`.DS_Store`、缩略图） |
| `压缩包里没有可识别的内容（…）` | 装不上。看 §13 排错表 |

导入后回到插件页，检查三件事：

1. **这一行在不在**：名字、作者、`v版本`、以及内容摘要（`表情 6` / `模型 34` / `音色 8` / `主题 5 项` / `图片 6`）；
2. **开关是不是开的**（zip 导入的插件默认就是「已启用」）；
3. **能力标签的颜色**：蓝色 = 已实现，灰色 = 仅声明或不认识的 id（不认识的会显示原始 id）。

如果装了但"没效果"，按能力去对应的页面看：表情包 → 聊天里发一句带情绪的
（或直接让 AI 发表情）；模型库 → 模型页；音色 → 语音页；主题 → 整个界面外观。

### 10.4 用应用生成骨架（"写出示例"按钮）

插件页底部有三个按钮，会把**可立即修改、打包、导入**的骨架写到应用私有目录：

| 按钮 | 写到哪 | 里面有 |
| --- | --- | --- |
| 写出表情包示例 | `filesDir/plugins/示例-表情包/` | `plugin.json`、`emoji.json`、空的 `assets/`、`怎么改.txt` |
| 写出模型库示例 | `filesDir/plugins/示例-模型库/` | `plugin.json`、`models.json` |
| 写出语音音色示例 | `filesDir/plugins/示例-语音音色/` | `plugin.json`、`voices.json` |

写入成功会弹一句 `已写到 /data/data/com.localmind/files/plugins/示例-表情包`。
拿到文件的方式：

```bash
# 看目录
adb shell run-as com.localmind ls -R files/plugins

# 打包取回（tar 在多数机型的 toybox 里都有）
adb exec-out run-as com.localmind tar -cf - -C files/plugins . > plugins-backup.tar
```

界面主题的骨架在代码里也有（`saveSampleUiTheme`：`ui.json` + `plugin.json` +
一份带默认值和范围的 `可改的令牌.txt`），但当前版本的界面还没有它的按钮 ——
需要的话照 §8.7 自己写一份即可。

### 10.5 脚本化验证（开发者）

应用内导入走的是系统文件选择器（SAF），没法用 adb 自动化，改一次试一次要人工点
五六下。所以本机 API 上有一个诊断端点，用的是**和界面完全同一条安装路径**：

```bash
adb push my-plugin.zip /data/local/tmp/
curl -X POST http://127.0.0.1:8080/v1/debug/plugin \
     -H 'Content-Type: application/json' \
     -d '{"path":"/data/local/tmp/my-plugin.zip"}'
```

返回：

```json
{ "ok": true, "result": "已安装 我的表情包：表情包 我的表情包（6 个，其中 5 张图片）",
  "plugin_id": "com.example.mystickers", "abilities": "emoji_pack" }
```

失败时是 `{"ok": false, "result": "<和界面一模一样的报错原文>"}`。
请求本身不合法时返回的是标准错误对象 `{"error": {"message": …, "type": "invalid_request_error", "code": …}}`，
`code` 分别是 `missing_path` / `path_not_allowed` / `not_found`。
限制：`path` 必须落在应用自己的目录或 `/data/local/tmp/` 下；它只读这个路径，
不接受上传内容，也不写回该路径。前提是本机 API 已开启（端口见应用内的 API 设置）。

### 10.6 自检清单

* [ ] zip 里第一层就能看到 `plugin.json`（不是文件夹）
* [ ] 文件名**全小写**：`plugin.json` / `emoji.json` / `models.json` / `voices.json` / `ui.json` / `assets/`
* [ ] `plugin.json` 的 `id` 只含字母、数字、`.`、`-`、`_`
* [ ] `abilities` 里的 id 与 §4 表**逐字符一致**
* [ ] JSON 用 UTF-8 保存，没有尾逗号、没有注释、没有中文引号
* [ ] 内容文件里的 `packId` 与 `plugin.json` 的 `id` 一致（不是硬性要求，但能少一类困惑）
* [ ] 图片在 `assets/` 里，`image` 只写文件名
* [ ] 打包压的是**文件**，不是文件夹
* [ ] 导入后**读了那句弹出结果**，并回插件页确认开关是开的

---

## 11. 授权（写插件前请读）

### 11.1 你的插件

在 `plugin.json` 的 `description` 里写清楚授权（当前版本没有展示 `README.md` /
`LICENSE` 的界面入口，写在那里更保险）。数据文件本身也可能有版权
（比如你整理的一份模型清单）。

### 11.2 图片素材的授权是**你**的责任

这是最容易被忽略、后果最重的一条：

* 网图、番剧截图、明星照片、表情包合辑 —— 几乎都带着来源不明的版权，**分发给别人就是侵权**；
* 合法的来源只有两类：**你自己画的**，或**明确允许再分发的**（CC0 / CC-BY / OFL 等，
  且你必须**保留署名**）；
* 请在 `license` 字段里如实写明（例如 `"示例图片由我绘制，CC0"`）。

主程序**不分发任何表情素材**：不带任何图片，也不带任何表情数据。
你装了什么素材，是你自己的选择和行为。

### 11.3 为什么留着 emoji 兜底

`char`（Unicode emoji）不是"图片功能的简配版"，它是**刻意保留的零素材方案**：
emoji 是标准字符，由用户手机的**系统字体**渲染 —— 你和我都没有分发任何素材，
所以不存在授权问题。想在不想碰版权的情况下做一个能用的表情包，只用 `char` 就够了。

### 11.4 模型权重不由本应用分发

模型库插件里放的是**下载地址**，不是权重文件。应用不会、也不能把权重打进 APK：
下载下来的 `.gguf` 落在用户自己的设备上。每个模型各自的许可（Apache-2.0 / MIT /
Llama 社区许可 / 非商业许可…）由**使用者**遵守，插件作者应在 `license` 字段里
如实标注。同理，语音音色模型来自 sherpa-onnx 官方 Release，各自遵循其原始许可
（多为 Apache-2.0；社区角色音色通常仅供个人非商业使用）。

### 11.5 GPL-3.0 边界：为什么表情包能力是插件

表情包的「给表情打标签 → 按标签/描述匹配 → 命中结果缓存」这三个做法参考自
[RiyaBot](https://github.com/hsd221/riyabot) 的 `emoji_api`，而 **RiyaBot 是 GPL-3.0**。

LocalMind 主程序是 MIT。把参考了 GPL 实现的代码内置进来，会让整个应用在授权上
变成 GPL 的衍生作品。所以边界划在这里：

* **主程序**（MIT）：只提供扩展点和一个纯数据格式，不搬运任何代码或数据；
* **实现是独立的**：RiyaBot 是 Python 进程内 API + 本地图片文件 + VLM 两段式识别；
  这里是纯函数式匹配，相同之处只有"用情感标签做索引"这个思路本身（思路不受版权保护）；
* **能力以插件形式分发**：用户自己决定装不装，插件本身是纯数据、不含任何 GPL 代码。

**授权边界和代码边界重合，两边都干净。** 这条设计不是随手定的，改动前请先想清楚
它为什么在这里。代码插件是后来新增的能力：它不改变这条边界，而是把"可执行"
单独做成一个需要用户确认的能力。

---

## 12. 排错表

### 12.1 安装层（选完 zip 立刻弹出）

| 报错原文 | 原因 |
| --- | --- |
| `文件不存在：xxx.zip` | 临时文件没了 / 路径不对（一般是选择器返回的文件被系统回收） |
| `文件是空的` | zip 是 0 字节：下载没完成、云盘占位文件、或者导出失败 |
| `读取压缩包失败：<原文>` | 不是 zip（扩展名是 `.zip` 但内容是别的）、zip 被截断、加密 zip |
| `展开压缩包失败：<原文>` | 第二遍读取（要解压 `assets/`）时失败：磁盘满、权限、zip 结构损坏 |
| `压缩包里没有可识别的内容（plugin.json / emoji.json / models.json / assets）` | 一个白名单文件都没有。最常见原因：**套了一层文件夹且里面也没有这些文件**、或者压错了目录 |
| `压缩包里没有可识别的内容。要有 plugin.json，或 emoji.json（表情包）/ models.json（模型库）/ voices.json（语音音色）/ ui.json（界面主题）` | 有 `assets/` 图片或 `README.md`，但没有可解析的内容文件 —— 只有图片是成不了插件的 |
| `安装失败：XxxException <原文>` | 兜底捕获（畸形 zip、内存不足等）。zip 本身有问题，重新压一份 |
| `导入失败：<原文>` | 文件选择器读 URI 失败（权限撤销、文件被移走、URI 过期），还没走到解析 |
| `已安装 X（忽略了 N 个无关文件）` | 不是错误：zip 里有 N 个白名单之外的文件（`__MACOSX/`、`.DS_Store`、原图、PSD…），它们被跳过了 |
| 导入瞬间闪退不了、但什么也没变 | 看 logcat：`PluginInstall` / `PluginArchive` 两个 tag 会打出读了哪些文本、解压了几个资源、跳过了几项 |

### 12.2 清单（`plugin.json`）

| 报错原文 | 原因 |
| --- | --- |
| `内容是空的` | `plugin.json` 存在但是空文件/全空白 |
| `不是合法的插件清单：<原文>` | JSON 语法错误，或字段类型不对（例如 `config` 的值写成了数字） |
| `缺少 id` | 没写 `id`，或 `id` 是空字符串 |
| `清单不完整：name=空，abilities=0 个` | 少了 `name` 或 `abilities`（至少 1 个） |
| `清单不完整：…；id 只能包含字母、数字、点、短横线和下划线` | `id` 里有空格、中文、`/`、`:` 等 |
| `这个插件声明的能力当前版本都不认识：<你写的>。已知能力：…` | `abilities` 里全是拼错的 id（对照 §4） |

### 12.3 `emoji.json`

| 报错原文 | 原因 |
| --- | --- |
| `emoji.json 是空的` | 文件确实是空的（0 字节或只有空白）。注意区分：文件**超过 1 MB** 时会被直接丢弃，症状是"没有表情包内容"而不是这一句 |
| `emoji.json 格式错误：<原文>` | JSON 语法错误，或字段类型不符（`aliases` 写成了字符串而不是数组） |
| `emoji.json 缺少 packId` | 没写 `packId` |
| `emoji.json 缺少 packName` | 没写 `packName` |
| `emoji.json 里 stickers 是空的` | `stickers` 是空数组，**或者字段名写错了**（最常见：写成了 `emojis` / `emoji` / `items`） |
| `有 N 个表情缺少 emotion（标签）` | 有条目没写 `emotion`（或写成了 `Emotion` —— 键名大小写敏感） |
| `有 N 个表情既没有 image 也没有 char（第一个的 emotion='…'）` | 有条目既没图也没字符；或 `image` 指向的文件没被解压出来 |
| 装了但聊天里发不出表情 | ① 插件开关没开；② 标签没被模型写对（标签要短、要出现在注入的标签表里）；③ 命中了但条目只有 `image` 而图片文件不存在（被 8 MB 上限丢掉、文件名大小写不符、放错了目录） |

### 12.4 `models.json`

| 报错原文 | 原因 |
| --- | --- |
| `模型包里没有任何模型` | `models` 是空数组 |
| `有模型条目缺少 id / fileName / url` | 这三个字段不能空 |
| `<解析器原文，形如 Field 'name' is required…>` | 缺了没有默认值的字段：`id` / `name` / `repoId` / `fileName` / `sizeBytes` / `url` |
| 模型条目装了但不出现 | 模型包被单独关掉了（模型页），或插件本身没启用 |
| 下载完成却报失败 `大小不符：期望 X，实际 Y` | `sizeBytes` 不是真实字节数 → 改成 `0` 或查 Content-Length |
| `该条目没有可用下载地址，请用"导入文件"` | `url` 和 `mirrors` 都空，且没有插件镜像可用 |

### 12.5 `voices.json`

| 报错原文 | 原因 |
| --- | --- |
| `voices.json 是空的` | 空文件 |
| `voices.json 格式错误：<原文>` | JSON 语法/类型错误 |
| `voices.json 缺少 packId` | 没写 `packId` |
| `voices.json 缺少 packName` | 没写 `packName`（空字符串也算缺） |
| `voices.json 里 voices 是空的` | `voices` 是空数组，或键名写错 |
| `有 N 个音色缺少 id 或 label` | 有条目缺 `id` 或 `label` |
| 下载音色报 `解压失败` | 压缩包损坏 / 空间不足 |
| 下载音色报 `解压后没有找到 VITS 模型文件` | tarball 内部布局不是常规 VITS 布局（主程序扫目录认最大的 `.onnx`） |
| 下载音色报 `全部下载源均失败` | 官方 Release + gh-proxy 都不通；音色 id 拼出来的 tarball 不存在 |
| 自定义的 `url` / `mirrors` 没被使用 | 当前实现确实忽略它们，见 §15 |

### 12.6 `ui.json`

| 报错原文 | 原因 |
| --- | --- |
| `ui.json 是空的` | 空文件 |
| `ui.json 格式错误：<原文>` | JSON 语法错误；或令牌值不是字符串（`"cardCorner": 18` 是错的，要写 `"18"`） |
| `ui.json 缺少 packId` | 没写 `packId` |
| `ui.json 缺少 packName` | 没写 `packName` |
| `tabs 里有不认识的页面：xxx。只能是 chat、models、me` | `tabs` 里写了别的 id（也不能写中文标签名） |
| `startTab 不认识：xxx。只能是 chat、models、me` | 同上 |
| `ui.json 什么都没改（tokens / darkTokens / tabs / appName 都是空的）` | 文件是合法 JSON，但一个字段都没给 |
| 装上了但界面没变 | ① 插件开关没开；② 有另一个主题插件排在后面（只有最后一个启用的生效）；③ 令牌键名拼错（会被报为"不认识的令牌"）；④ 这个令牌当前还没接线（§8.3 的"未接线"行）；⑤ 值不合法被回退成默认了 |
| `忽略了不认识的令牌：xxx` | 不是错误：这些键不是白名单里的，没有被应用 |

---

## 13. 常见编辑陷阱

### 13.1 ⚠️ Kotlin 的块注释会**嵌套**（这个项目真的踩过）

如果你要给插件写**工具脚本**，或者要改 LocalMind 的 Kotlin 源码，请记住：

**在 Kotlin 里，块注释是可以嵌套的。** 三个字符 `/` `*` 出现在一个块注释
（包括 KDoc `/** … */`）内部，就会**再开一层注释**；于是原本的 `*/` 只关掉了
内层，外层继续把后面的代码全部吃掉 —— 症状是一堆莫名其妙的
`Unclosed comment`、参数解析错误、甚至"函数没定义"，而报错位置离你真正写错的
地方很远。

这个项目就是这么踩的：有人在文档注释里写了"通配写法是 `assets/` 加星号"，
顺手写成了那三个字符的连写，整个文件后半段被吞掉。

处理方式（项目里现在就是这么做的）：

* **不要在注释里写出那三个字符的连写**，用文字描述（"斜杠加星号"）、
  或把 `assets/` 和 `*` 拆成两段拼接；
* 需要表达"目录下全部文件"时，用拼接而不是字面量：

  ```kotlin
  private const val ASSET_WILDCARD = "assets/"
  // 正确：ASSET_WILDCARD + "*"
  ```

* **不要为了"看得清楚"在注释里画 ASCII 图**：`/` `*` 的连写会**再开一层**注释，
  而 `*` `/` 的连写会**提前关掉**当前这层 —— 两种都能让一大段代码凭空消失。

补充说明（免得你过度紧张）：

* 只有**块注释**会嵌套。行注释（`// …`）在换行处就结束了；
* **字符串字面量里**的 `/` `*` 在 Kotlin 词法上是安全的（`"assets/*"` 能编译）；
  但这个项目把它当"源码里不出现的写法"来对待 —— 少一个需要判断上下文的地方，
  就少一次被吞掉半个文件的机会。

### 13.2 JSON 陷阱

| 陷阱 | 症状 | 正确做法 |
| --- | --- | --- |
| **键名拼错**（`emojis` 而不是 `stickers`、`title` 而不是 `name`） | **不报语法错**：主程序忽略不认识的键，然后告诉你"stickers 是空的" | 对照本文档的字段表，逐字符抄 |
| 键名大小写不符（`Emotion`、`PackId`） | 同上：被当成不认识的键忽略 | 严格小驼峰 |
| 尾逗号 `{"a":1,}`、缺逗号 | 通常直接报解析失败（`格式错误` / `不是合法的插件清单`）。解析配置里**没有**开启"允许尾逗号" | 用编辑器的 JSON 校验，或把多余逗号删掉 |
| 中文标点：`“ ”` `，` `：` | 解析失败 | 引号、逗号、冒号必须半角英文 |
| JSON 里写注释 `// 说明` | 解析失败 | JSON 不支持注释，说明写进 `description` / `note` |
| 编码不是 UTF-8（记事本另存为 ANSI/GBK） | 中文变乱码，或解析失败 | 一律另存为 **UTF-8**（不带 BOM 最稳） |
| `config` 的值写成数字/布尔 | `不是合法的插件清单`（值必须是字符串） | 写 `"8080"`、`"true"` |
| 令牌值写成数字（`"cardCorner": 18`） | `ui.json 格式错误`（`tokens` 的值必须是字符串） | 写 `"18"` |
| 少了没有默认值的模型字段 | 解析器原文（`Field 'repoId' is required…`） | 见 §6.2 的"必填"列 |

### 13.3 zip 陷阱

| 陷阱 | 症状 | 正确做法 |
| --- | --- | --- |
| 压了文件夹而不是文件 | 见 §2（当前多半还能装上，但不要依赖） | 全选内容再压 |
| 文件名大小写不符（`Emoji.json`、`Assets/`） | 文件被当成"无关文件"忽略 → 内容为空 | 全小写 |
| macOS 压缩带出 `__MACOSX/`、`.DS_Store` | `忽略了 N 个无关文件`（无害） | 可以忽略，或用命令行 `zip` |
| JSON 文件超过 1 MB | 该文件被直接丢弃（表情包几千条就会撞到） | 拆成多个包，或精简字段 |
| 单张图超过 8 MB | 那张图被丢弃，`image` 指向它时退回 `char` / 什么都不发 | 压缩图片（几百 KB 足够） |
| 两个子目录里有同名文件 | **后面读到的赢**，无提示 | 保持扁平、文件名唯一 |
| 加密 zip | 读不出内容 | 不要加密 |

---

## 14. 排错思路（给不想读表的人）

1. **看弹出来的那句话**：它已经说了是"没内容"、"格式错"还是"缺字段"；
2. **回插件页看那一行**：开关是否打开、能力标签是蓝还是灰、内容摘要有几个；
3. **模型/音色/表情不出现**：先确认插件启用，再确认对应的包没被单独关掉，
   最后确认 `packId` 与插件 `id` 一致；
4. **界面没变**：看是不是有第二个主题插件排在后面，或者你改的令牌属于"未接线"（§8.3）；
5. **实在看不出来**：接 logcat 过滤 `PluginInstall` / `PluginArchive` / `StickerResolver`，
   三条日志分别对应"装了什么"、"读了什么跳过了什么"、"表情包生效了没有"；
6. 还不行就用 §10.5 的诊断端点，把报错原文整句复制出来对照 §12。

---

## 15. 已知限制与实现细节（诚实清单）

这些是当前版本的真实行为，写插件前值得知道 —— 免得你把时间花在"改了没反应"上。

| # | 事项 | 现状 | 对你意味着什么 |
| --- | --- | --- | --- |
| 1 | `StickerEntry.weight` | 声明了、会被解析，但匹配时**不使用**（同标签取靠前的那条） | 想让某条更容易命中，就把它**排在前面**；`weight` 写了不报错、不生效 |
| 2 | 停用表情包插件 | 关闭开关**不会**立刻让表情失效（只有"删除"才会清掉并删除资源目录） | 想立刻换效果：卸载插件，或直接导入新的表情包（后装的赢） |
| 3 | `ui.json` 的 `tabs` / `startTab` / `appName` | 会被解析、校验、保存，但当前界面外壳还没读取 | 写了不会报错，暂时看不到变化 |
| 4 | 部分令牌 | `accentDark` / `groupedBackgroundDark` / `cardBackgroundDark` / `bodySize` / `tabStyle` / `showTabBarLabels` / `navTitleAlign` 属于"已登记未接线" | 深色请用 `darkTokens` + 浅色键名；字号请用 `fontScale` |
| 5 | 用户手动覆盖令牌 | 存储与解析层已支持（优先级最高），但**没有界面入口** | 设计时假定"用户可能覆盖任何令牌"，别把配色当唯一手段 |
| 6 | `voices.json` 的 `url` / `mirrors` | 当前下载器只按 `id` 拼官方地址 + gh-proxy，**忽略这两个字段** | 自定义音色请让 `id` 与官方 tarball 同名；自建地址暂时不会生效 |
| 7 | `README.md` / `LICENSE` | 允许放进 zip、会被读进来，但没有展示入口 | 关键信息写进 `plugin.json` 的 `description` |
| 8 | 嵌套文件夹的 zip | 按文件名取用，多数情况能装上 | 不要依赖；扁平是唯一被保证的形态 |
| 9 | 从文本粘贴清单安装 | 代码里有这条路径，当前界面没有暴露 | 用户侧只有"选 zip"一个入口 |
| 10 | 界面主题示例骨架 | 代码里有（含"可改的令牌.txt"），界面上没有按钮 | 照 §8.7 手写 |
| 11 | `recommendedRamGb` | 只用于「推荐」分类的筛选（≤ 8），不是硬门槛 | 真正跑不跑得动由主程序读 GGUF 头自己算 |
| 12 | 六个"仅声明"的能力 | `remote_provider` / `image_backend` / `web_tool` / `mcp_server` / `persona_pack` / `api_endpoint` 没有消费端 | 声明了不报错，也不会有行为；插件页标签是灰色 |

---

## 16. 参考：仓库里的现成插件

`plugins-src/` 下有几个可以直接对着改的完整插件（把里面的**文件**压成 zip 就能装）：

| 目录 | 内容 |
| --- | --- |
| `plugins-src/emoji-pack/` | 纯 emoji 表情包（`char` 兜底方案，无图片素材） |
| `plugins-src/sticker-pack/` | 图片表情包（6 张图在 `assets/`，演示图片链路） |
| `plugins-src/model-library/` | 模型库（对话 / 识图 / 生图 / 向量 / 决策） |
| `plugins-src/voice-pack/` | 语音音色（sherpa-onnx 官方 Release） |
| `plugins-src/ui-theme/` | 界面主题（换主色、圆角、深浅色两套） |

每个目录里都有 `plugin.json`，可以当作写清单的模板。
