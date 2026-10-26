# 界面布局插件（ui_layout）—— layout.json 规范

适用版本：**1.0.5 起**。本文是 `ui_layout` 能力的字段级契约；`ui.json`（只换配色）见 [PLUGIN.md](PLUGIN.md) §8。

---

## 1. 它能改什么

| 字段 | 作用 |
| --- | --- |
| `tabs` | 底栏有哪几个入口、顺序、文字、图标、进哪一页（最多 5 个） |
| `startTab` | 启动时打开哪个标签 |
| `home` | 首页由哪些块组成（没有 `screens` 时的默认做法） |
| `screens` | **插件自己声明的整页**（1.0.5 新增） |
| `overrides` | **用插件页面替换内置页面**（1.0.5 新增） |
| `tokens` / `darkTokens` | 同一套外观令牌，见 PLUGIN.md §8.3 |
| `appName` / `subtitle` | 应用标题与首页副标题 |

一个插件可以同时提供 `ui_theme` 和 `ui_layout`，后者优先级更高。

---

## 2. 最小可用 layout.json

```json
{
  "version": 1,
  "packId": "com.example.mine",
  "packName": "我的界面",
  "tabs": [
    { "id": "chat", "label": "对话", "icon": "bubble" },
    { "id": "models", "label": "模型", "icon": "memory" },
    { "id": "me", "label": "我的", "icon": "person" }
  ],
  "startTab": "chat"
}
```

`packId` + `packName` 必填；`tabs` / `home` / `screens` / `overrides` / `tokens` / `appName` 全空会被拒绝（"什么都没改"）。

---

## 3. 用 screens + overrides 替换一整页

```json
{
  "packId": "com.example.console",
  "packName": "控制台首页",
  "tabs": [
    { "id": "home", "label": "控制台", "icon": "bolt" },
    { "id": "chat", "label": "对话", "icon": "bubble" },
    { "id": "models", "label": "模型", "icon": "memory" }
  ],
  "startTab": "home",
  "overrides": { "home": "console" },
  "screens": {
    "console": {
      "title": "控制台",
      "subtitle": "这一页由插件排版",
      "blocks": [
        { "type": "hero", "title": "今天想做什么？", "icon": "sparkle" },
        { "type": "actions", "items": [
            { "label": "新对话", "icon": "chat", "open": "new" },
            { "label": "模型", "icon": "memory", "open": "models" }
        ] },
        { "type": "list", "title": "更多", "items": [
            { "label": "设置", "icon": "tune", "open": "settings" }
        ] },
        { "type": "recent", "title": "最近", "limit": 3 },
        { "type": "status", "title": "本机状态" }
      ]
    }
  }
}
```

生效之后，`home` 这个标签完全不会绘制主程序原有的首页 —— 只按 `screens.console` 渲染。
`overrides` 的键只能是内置页面名：`home` / `chat` / `models` / `me` / `voice` / `breeze` / `plugins` / `image` / `pipeline`。

---

## 4. 块（blocks）

| type | 用途 | 用到的字段 |
| --- | --- | --- |
| `hero` | 顶部大卡 | title / text / icon |
| `actions` | 两列快捷入口 | title / items[] |
| `list` | 分组列表 | title / items[] |
| `recent` | 最近对话 | title / limit（1–8） |
| `status` | 本机状态（模型 / 上下文 / 速度 / 对话数） | title |
| `text` | 一段说明 | title / text |

`items[]` 每项字段：`label`（必填）、`icon`、`text`（副标题）、`open`（动作目标）。

### 图标 id

`chat` / `bubble` / `models` / `memory` / `me` / `person` / `home` / `voice` / `breeze` /
`plugins` / `extension` / `image` / `sparkle` / `cloud` / `bolt` / `mic` / `volume` /
`search` / `settings` / `tune`。认不出的会退回默认图标，不会导致安装失败。

### 动作目标（open）

| 写法 | 行为 |
| --- | --- |
| `chat` `models` `me` `home` `voice` `breeze` `plugins` `image` `pipeline` | 切到对应标签 |
| `new` | 新建一个 AI 对话并进入 |
| `settings` | 打开生成参数整页 |
| `plugins_import` | 打开插件页 |
| `debug` | 打开诊断页 |
| `screens` 里的另一个 id | 在同一标签内推入那一页（返回键可回） |
| `http://` / `https://` | 用系统浏览器打开 |

未识别的目标会在**安装时**被拒绝，并指出是哪个块、哪个值。

---

## 5. 校验与边界

* `screens` 最多 12 页；id 只能字母/数字/下划线/短横线。
* `blocks` 的 type 必须在白名单内；未知 type 安装时报错。
* `overrides` 只能指向内置页面 + 已声明过的 screen id。
* 插件是**数据不是代码**：没有脚本、没有 eval、没有 DexClassLoader。
  能力边界就是上面这些块，最坏情况是"某块不显示"，不会白屏或执行任意逻辑。

---

## 6. 参考实现

* `plugins-src/ui-layout-studio/` —— screens + overrides 的最小示例。
* `plugins-src/ui-layout-clean/` —— 只调底栏与配色的示例。

打包：zip 根目录直接放 `plugin.json` + `layout.json`（不要多套一层文件夹），
然后应用内「我的 → 插件 → 导入」。
