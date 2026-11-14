# 主题系统扩展路线图

> 记录 2026-11-15 提的六个方向：**怎么落地、先做哪个、各自的风险在哪**。
> 写成文档而不是只留在对话里，是因为这些能力全都落在 `ui.json` 这一个协议上，
> 改一处要同步改解析、校验、渲染三层 —— 没有一份书面约定，下一次改动一定会漏。

## 现有能力（1.0.11 基线）

| 层 | 文件 | 现在能做什么 |
| --- | --- | --- |
| 令牌 | `data/UiTheme.kt` → `UiTokens` | 60+ 项颜色/几何/字号/形状；`darkTokens` 覆盖深色 |
| 解析 | `service/ThemeStoreService.kt` | `ui.json` → `ThemeState`，字段白名单校验 |
| 渲染 | `theme/IosTheme.kt` → `LocalMindTheme` | 令牌 → `IosPalette` / `IosMetrics` / 字体 |
| 布局 | `data/UiLayout.kt` | `ui_layout` 插件：底栏入口、首页区块（`home`） |
| 动效 | `ui/motion/Motion.kt` | 1.0.11 起可被 `animStyle` / `animSpeed` / `pageTransition` 驱动 |
| 控件 | `theme/IosTheme.kt` | 1.0.11 起 `controlStyle` 统一换算 `controlCorner` |

**协议现状**：`ui.json` 只有一个 `tokens` 字典。所有扩展都该是**新增可选字段**，
旧主题包（41 款已在线上）必须继续正常工作 —— 这是硬约束。

## 六个方向

### 1. 组件级主题（聊天卡 / 模型卡 / 设置卡 / 资料卡）

**做法**：`ui.json` 增加可选 `components`，每项是一小份令牌覆盖。

```json
"components": {
  "chatCard":   { "cardStyle": "glass", "cardCorner": "24", "cardElevation": "3" },
  "modelCard":  { "cardStyle": "flat",  "cardCorner": "12", "fill": "#0F000000" },
  "settingCard":{ "cardStyle": "neu",   "cardCorner": "18" },
  "profileCard":{ "cardStyle": "gradient", "cardCorner": "28" }
}
```

**难点**：现在的卡片是一个通用函数，四类卡片没有区分标识。需要先给每个卡片
调用点传 `role` 参数，再由 `LocalMindTheme` 提供 `LocalComponentTokens`。
**风险**：低（纯加法）。**工作量**：中。

### 2. 图标主题（导航图标 / 功能图标）

**做法**：`ui.json` 增加 `icons`，值是包内相对路径：

```json
"icons": { "tab.chat": "assets/icons/chat.png", "tab.models": "..." }
```

加载器按主题包目录解析，**必须**校验：路径不得跳出包目录、必须是 PNG/WebP、
单张 ≤ 512 KB。渲染端在 `AppIcons` 处优先取主题图标，缺失回退内置矢量图。

**难点**：图标是 `ImageVector`（矢量），主题给的是位图 —— 需要加一层
`Icon(painter = ...)` 与 `Icon(imageVector = ...)` 的分支。
**风险**：中（图标尺寸/对齐容易糊）。**工作量**：中。

### 3. 字体资源（标题 / 正文 / 消息）

**做法**：主题包可以带 `.ttf/.otf`，指定三个角色：

```json
"fonts": { "title": "assets/fonts/title.ttf", "body": "assets/fonts/body.ttf", "message": "..." }
```

安装主题时把字体复制到 `filesDir/theme_fonts/<packId>/`，交给已有的
`FontRegistry` 解析（它已经支持本地字体文件）。**不新增字体引擎**。
字数/体积限制：单个 ≤ 20 MB，三档合计 ≤ 40 MB。

**风险**：低。**工作量**：小（复用现有 FontRegistry）。
**注意**：主题包里塞大字体等于拖慢安装，装完要给出体积提示。

### 4. 装饰素材（透明 PNG）

**做法**：`decorations` 数组，每项描述一张透明 PNG 的贴放方式：

```json
"decorations": [
  { "file": "assets/decor/petals.png", "anchor": "bottom", "opacity": "0.7", "blur": "0" }
]
```

渲染在 `AuroraBackground` 的最上层（壁纸之上、内容之下）。

**风险**：中（多层绘制 + 大图内存）。**工作量**：小-中。
**限制**：单张 ≤ 2 MB，最多 4 张，解码必须按目标宽度采样。

### 5. 动态效果（背景动画 / 卡片光泽 / 按钮反馈）

**做法**：`motion` 段：

```json
"motion": {
  "bgAnim": "drift",        // none / drift（缓慢平移）/ aurora（渐变流动）
  "cardSheen": "true",      // 卡片上一道缓慢扫过的高光
  "pressFeedback": "scale"  // scale / glow / none
}
```

**难点**：`bgAnim` 是无限动画，**必须**在页面不可见时停（否则一直耗电）。
`cardSheen` 用 `Brush.linearGradient` 的 offset 动画实现，不用 shader。
**风险**：中-高（耗电、掉帧）。**工作量**：中。

### 6. 页面专属背景（聊天页 / 模型页 / 个人页）

**做法**：`pages` 段，每页独立壁纸 + 暗化 + 模糊：

```json
"pages": {
  "chat":   { "wallpaper": "assets/pages/chat.jpg",   "dim": "0.25", "blur": "0" },
  "models": { "wallpaper": "assets/pages/models.jpg", "dim": "0.35", "blur": "12" },
  "me":     { "wallpaper": "assets/pages/me.jpg",     "dim": "0.30", "blur": "8" }
}
```

未声明的页面回退全局 `wallpaperFile`。

**风险**：低-中（每页多一张大图，内存 + 包体）。**工作量**：中。
**建议**：这项**先做**，因为 `AuroraBackground` 已经有壁纸+暗化+模糊三件套，
只需要按当前页选图。

## 推进顺序（每步都能独立发版）

1. **页面专属背景**（复用现有背景管线，改动最小，用户感知最强）
2. **字体资源**（复用 FontRegistry，纯加法）
3. **组件级主题**（给卡片加 role，四类卡片分别取令牌）
4. **装饰素材**（背景层叠加）
5. **动态效果**（先做按钮反馈，再做背景动画，最后卡片光泽）
6. **图标主题**（位图/矢量两套分支，最容易糊，放最后）

每一步的验收标准：
- 旧主题包（41 款）行为不变；
- 新字段缺失/写错时降级到默认，不抛异常；
- 主题包体积增长有上限提示；
- 真机（24031PN0DC）跑一遍主题商店 → 安装 → 切页 → 深浅色切换。

## 协议版本

`ui.json` 增加顶层 `"schemaVersion": 2`。解析器按版本决定是否读新字段：

- 没有 `schemaVersion` → 视为 1，只读 `tokens` / `darkTokens`；
- `2` → 读 `components` / `icons` / `fonts` / `decorations` / `motion` / `pages`。

这样即使以后要改语义，老包也不会被误解释。
