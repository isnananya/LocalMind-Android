# 雾海 · 今日

一个同时提供三种能力的样式插件：

* `ui_theme`：整套冷雾蓝外观（浅色/深色）；
* `ui_layout`：底栏 4 个入口 + `screens.today` 自定义首页（用 `overrides` 覆盖 home）；
* `chat_action`：输入框上方的两个快捷动作（译成英文 / 说短一点）。

## 安装

zip 根目录直接放 `plugin.json` / `ui.json` / `layout.json` / `actions.json`，
应用内「我的 → 插件 → 导入插件 zip」。

## 改它

* 颜色/圆角：改 `ui.json` 的 `tokens`/`darkTokens`；
* 首页块：改 `layout.json` 的 `screens.today.blocks`；
* 动作：改 `actions.json`，`{{input}}` 会被替换成你当前输入框里的文字。
