#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成三个可安装的三方主题包（含壁纸），并压成 zip 放到 dist/plugins/。

主题包结构（与 PluginArchive 的识别规则对齐）：
    plugin.json      插件清单，abilities 含 ui_theme
    ui.json          主题令牌（这就是"主题"的全部内容）
    README.md        给用户看的说明
    assets/*.png     包内壁纸，ui.json 里用 wallpaperFile 引用文件名

注意 assets 里的文件落盘时用 **basename**，所以 wallpaperFile 只写文件名，
不要写 "assets/xxx.png"。
"""
import json
import os
import shutil
import zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(ROOT, "plugins-src")
DIST = os.path.join(ROOT, "dist", "plugins")
TMP = os.path.join(ROOT, ".tmp-wallpaper")

# ---------------------------------------------------------------- 主题定义

THEMES = [
    {
        "dir": "theme-sakura",
        "zip": "樱雪物语主题插件.zip",
        "plugin": {
            "id": "dev.localmind.theme.sakura",
            "name": "樱雪物语",
            "version": "1.0.0",
            "author": "LocalMind",
            "description": "二次元粉紫主题：带樱花壁纸、尾巴气泡、玻璃卡片、圆头像。",
            "abilities": ["ui_theme"],
            "requires": [],
            "config": {},
            "manifestVersion": 1,
        },
        "ui": {
            "version": 1,
            "packId": "dev.localmind.theme.sakura",
            "packName": "樱雪物语",
            "author": "LocalMind",
            "description": "粉紫二次元风格，带包内樱花壁纸。",
            "license": "MIT",
            "home": "",
            "tokens": {
                "accent": "#FF6FA5",
                "accentOn": "#FFFFFF",
                "danger": "#E5484D",
                "success": "#30A46C",
                "warning": "#F76B15",
                "groupedBackground": "#FFF5F9",
                "cardBackground": "#FFFFFF",
                "barBackground": "#FFFFFF",
                "barBorder": "#F3DCE6",
                "separator": "#F6E3EB",
                "fill": "#FDEFF4",
                "fillSecondary": "#FBE2EC",
                "label": "#3B2430",
                "secondaryLabel": "#8A6C7A",
                "tertiaryLabel": "#B295A3",
                "selection": "#FFD9E7",
                "overlay": "#1A0E14",
                "bubbleUser": "#FF8FB8",
                "bubbleUserText": "#FFFFFF",
                "bubbleAi": "#FFFFFF",
                "bubbleAiBorder": "#F2D3E0",
                "composerBackground": "#FFFFFF",
                "codeBackground": "#FFF0F5",
                "cardCorner": "24",
                "controlCorner": "18",
                "sheetCorner": "28",
                "bubbleCorner": "20",
                "pagePadding": "18",
                "bodySize": "15",
                "fontScale": "1.0",
                "tabStyle": "glass",
                "showTabBarLabels": "true",
                "navTitleAlign": "center",
                "tabBarShape": "capsule",
                "tabBarIndicator": "capsule",
                "tabBarWidth": "full",
                "fontFamily": "sans",
                "wallpaperFile": "sakura.png",
                "wallpaperDim": "0.25",
                "wallpaperBlur": "0",
                "wallpaperScale": "1.1",
                "cardStyle": "glass",
                "cardElevation": "2",
                "bubbleStyle": "tail",
                "avatarShape": "circle",
                "bgStyle": "wallpaper",
            },
            "darkTokens": {
                "groupedBackground": "#1A1218",
                "cardBackground": "#241A21",
                "barBackground": "#241A21",
                "barBorder": "#3A2A34",
                "separator": "#3A2A34",
                "fill": "#2E222A",
                "fillSecondary": "#382933",
                "label": "#F6E9EF",
                "secondaryLabel": "#C0A6B2",
                "tertiaryLabel": "#8F7683",
                "bubbleUser": "#C25C88",
                "bubbleAi": "#241A21",
                "bubbleAiBorder": "#3A2A34",
                "composerBackground": "#241A21",
                "codeBackground": "#2E222A",
                "accent": "#FF9BC0",
                "wallpaperDim": "0.55",
            },
            "appName": "",
        },
        "wallpaper": ("sakura.png", "手机壁纸_纯色背景风格_浅樱粉到淡紫的柔和渐变_零散樱花花瓣_2026-11-13T21-27-08.png"),
        "readme": """# 樱雪物语 · LocalMind 界面主题

粉紫二次元风格，内置一张樱花壁纸。

## 装法

**我的 → 插件 → 右上角 +**，选 `樱雪物语主题插件.zip`。装完在插件页启用。

## 特色

- **包内壁纸**：`assets/sakura.png` 是主题的一部分，跟着主题一起装。
  `bgStyle` 设为 `wallpaper` 才会启用背景图。
- **壁纸可调**：`wallpaperDim` 压暗（0~0.85）、`wallpaperBlur` 模糊（0~24dp）、
  `wallpaperScale` 缩放（1~2）。想看清壁纸就把 `wallpaperDim` 调到 0.1。
- **尾巴气泡**：`bubbleStyle` 设为 `tail`，聊天气泡会带小尾巴。
- **玻璃卡片**：`cardStyle` 设为 `glass`，卡片带半透明与描边。
- **圆头像**：`avatarShape` 设为 `circle`。

## 令牌速查

| 令牌 | 值 | 说明 |
| --- | --- | --- |
| `accent` | `#FF6FA5` | 主色（粉） |
| `cardCorner` | `24` | 卡片圆角 |
| `bubbleStyle` | `tail` | 气泡带尾巴 |
| `cardStyle` | `glass` | 玻璃卡片 |
| `avatarShape` | `circle` | 圆形头像 |
| `bgStyle` | `wallpaper` | 用包内壁纸当背景 |
| `wallpaperFile` | `sakura.png` | 壁纸文件名 |
| `wallpaperDim` | `0.25` | 壁纸压暗程度 |
| `fontFamily` | `sans` | 字体族 |

## 改自己的

改 `ui.json` 里的令牌值，然后把 `plugin.json`、`ui.json`、`README.md`、
`assets/` 一起压成 zip 导入即可。注意 `wallpaperFile` 只写文件名
（落盘时不带目录），不要写 `assets/sakura.png`。

## 授权

MIT。
""",
    },
    {
        "dir": "theme-aurora-night",
        "zip": "极光夜航主题插件.zip",
        "plugin": {
            "id": "dev.localmind.theme.aurora-night",
            "name": "极光夜航",
            "version": "1.0.0",
            "author": "LocalMind",
            "description": "暗色极光主题：星空壁纸、霓虹强调色、悬浮卡片、方角头像。",
            "abilities": ["ui_theme"],
            "requires": [],
            "config": {},
            "manifestVersion": 1,
        },
        "ui": {
            "version": 1,
            "packId": "dev.localmind.theme.aurora-night",
            "packName": "极光夜航",
            "author": "LocalMind",
            "description": "暗色星空极光风格。",
            "license": "MIT",
            "home": "",
            "tokens": {
                "accent": "#5EEAD4",
                "accentOn": "#04231F",
                "danger": "#F87171",
                "success": "#34D399",
                "warning": "#FBBF24",
                "groupedBackground": "#070B14",
                "cardBackground": "#101828",
                "barBackground": "#0B1220",
                "barBorder": "#1E293B",
                "separator": "#1E293B",
                "fill": "#1A2436",
                "fillSecondary": "#223047",
                "label": "#E6EDF6",
                "secondaryLabel": "#93A4BC",
                "tertiaryLabel": "#64748B",
                "selection": "#1E3A4D",
                "overlay": "#020617",
                "bubbleUser": "#0E7490",
                "bubbleUserText": "#E0F7FA",
                "bubbleAi": "#141E30",
                "bubbleAiBorder": "#24344F",
                "composerBackground": "#141E30",
                "codeBackground": "#0A1220",
                "cardCorner": "16",
                "controlCorner": "12",
                "sheetCorner": "22",
                "bubbleCorner": "14",
                "pagePadding": "16",
                "bodySize": "15",
                "fontScale": "1.0",
                "tabStyle": "glass",
                "showTabBarLabels": "true",
                "navTitleAlign": "center",
                "tabBarShape": "rounded",
                "tabBarIndicator": "underline",
                "tabBarWidth": "full",
                "fontFamily": "sans",
                "wallpaperFile": "aurora.png",
                "wallpaperDim": "0.45",
                "wallpaperBlur": "2",
                "wallpaperScale": "1.15",
                "cardStyle": "neu",
                "cardElevation": "4",
                "bubbleStyle": "rounded",
                "avatarShape": "rounded",
                "bgStyle": "wallpaper",
            },
            # 极光夜航是**纯暗色**主题：深浅色两套长得一样，
            # 所以 tokens 和 darkTokens 写同一份。用户切到浅色模式时
            # 看到的仍然是这张星空，不会突然变成一张白底壁纸。
            "darkTokens": {
                "accent": "#5EEAD4",
                "accentOn": "#04231F",
                "groupedBackground": "#070B14",
                "cardBackground": "#101828",
                "barBackground": "#0B1220",
                "barBorder": "#1E293B",
                "separator": "#1E293B",
                "fill": "#1A2436",
                "fillSecondary": "#223047",
                "label": "#E6EDF6",
                "secondaryLabel": "#93A4BC",
                "tertiaryLabel": "#64748B",
                "selection": "#1E3A4D",
                "overlay": "#020617",
                "bubbleUser": "#0E7490",
                "bubbleUserText": "#E0F7FA",
                "bubbleAi": "#141E30",
                "bubbleAiBorder": "#24344F",
                "composerBackground": "#141E30",
                "codeBackground": "#0A1220",
                "wallpaperDim": "0.45",
                "wallpaperBlur": "2",
            },
            "appName": "",
        },
        "wallpaper": ("aurora.png", "手机壁纸_深邃夜空与极光_深蓝到紫黑渐变背景_青绿色与品红色_2026-11-13T21-27-31.png"),
        "readme": """# 极光夜航 · LocalMind 界面主题

暗色极光风格，内置一张星空极光壁纸。

## 装法

**我的 → 插件 → 右上角 +**，选 `极光夜航主题插件.zip`。装完在插件页启用。

## 特色

- **包内壁纸 + 轻微虚化**：`wallpaperBlur` 设为 `2`，星空有一点景深，
  前景文字更清楚。设 `0` 就是锐利星空。
- **拟物卡片**：`cardStyle` 设为 `neu`，卡片带内阴影与厚度。
- **霓虹青强调色**：`accent` 用 `#5EEAD4`，在深底上很跳。

## 令牌速查

| 令牌 | 值 | 说明 |
| --- | --- | --- |
| `accent` | `#5EEAD4` | 强调色（霓虹青） |
| `cardStyle` | `neu` | 拟物卡片 |
| `cardElevation` | `4` | 卡片厚度 |
| `bubbleStyle` | `rounded` | 圆角气泡 |
| `avatarShape` | `rounded` | 圆角头像 |
| `bgStyle` | `wallpaper` | 用包内壁纸 |
| `wallpaperBlur` | `2` | 壁纸模糊（dp） |
| `wallpaperDim` | `0.45` | 壁纸压暗 |

## 授权

MIT。
""",
    },
    {
        "dir": "theme-qq-classic",
        "zip": "QQ经典蓝主题插件.zip",
        "plugin": {
            "id": "dev.localmind.theme.qq-classic",
            "name": "QQ经典蓝",
            "version": "1.0.0",
            "author": "LocalMind",
            "description": "QQ 经典风格：蓝白配色、直角矩形、方形头像、底部下划线标签栏。",
            "abilities": ["ui_theme"],
            "requires": [],
            "config": {},
            "manifestVersion": 1,
        },
        "ui": {
            "version": 1,
            "packId": "dev.localmind.theme.qq-classic",
            "packName": "QQ经典蓝",
            "author": "LocalMind",
            "description": "QQ 经典风格，直角矩形布局。",
            "license": "MIT",
            "home": "",
            "tokens": {
                "accent": "#12B7F5",
                "accentOn": "#FFFFFF",
                "danger": "#ED4014",
                "success": "#00A600",
                "warning": "#FF9D00",
                "groupedBackground": "#F2F5F8",
                "cardBackground": "#FFFFFF",
                "barBackground": "#FFFFFF",
                "barBorder": "#DCE3EA",
                "separator": "#E3E8EE",
                "fill": "#EDF2F7",
                "fillSecondary": "#E1E8EF",
                "label": "#1F2733",
                "secondaryLabel": "#6B7885",
                "tertiaryLabel": "#9AA5B1",
                "selection": "#E5F4FD",
                "overlay": "#0F1419",
                "bubbleUser": "#12B7F5",
                "bubbleUserText": "#FFFFFF",
                "bubbleAi": "#FFFFFF",
                "bubbleAiBorder": "#DCE3EA",
                "composerBackground": "#F2F5F8",
                "codeBackground": "#F5F7FA",
                "cardCorner": "4",
                "controlCorner": "4",
                "sheetCorner": "8",
                "bubbleCorner": "6",
                "pagePadding": "14",
                "bodySize": "15",
                "fontScale": "1.0",
                "tabStyle": "plain",
                "showTabBarLabels": "true",
                "navTitleAlign": "center",
                "tabBarShape": "square",
                "tabBarIndicator": "underline",
                "tabBarWidth": "full",
                "fontFamily": "sans",
                "cardStyle": "flat",
                "cardElevation": "0",
                "bubbleStyle": "square",
                "avatarShape": "square",
                "bgStyle": "flat",
            },
            "darkTokens": {
                "groupedBackground": "#111820",
                "cardBackground": "#1A222C",
                "barBackground": "#1A222C",
                "barBorder": "#2B3542",
                "separator": "#2B3542",
                "fill": "#232D39",
                "fillSecondary": "#2C3745",
                "label": "#E8EDF2",
                "secondaryLabel": "#96A3B1",
                "tertiaryLabel": "#6B7885",
                "bubbleUser": "#12B7F5",
                "bubbleAi": "#1A222C",
                "bubbleAiBorder": "#2B3542",
                "composerBackground": "#1A222C",
                "codeBackground": "#111820",
                "accent": "#3FC4FF",
            },
            "appName": "",
        },
        "wallpaper": None,
        "readme": """# QQ经典蓝 · LocalMind 界面主题

复刻 QQ 经典版（2010 年代）的视觉语言：蓝白配色、直角矩形、方形头像、
底部下划线式标签栏。

## 装法

**我的 → 插件 → 右上角 +**，选 `QQ经典蓝主题插件.zip`。装完在插件页启用。

## 特色

- **直角矩形**：`cardCorner` / `controlCorner` 都调到 `4`，复刻老 QQ 的方块控件。
- **纯色底栏**：`tabStyle` 为 `plain`、`tabBarShape` 为 `square`、
  `tabBarIndicator` 为 `underline` —— 标签栏不再是胶囊，是一根下划线。
- **方形头像**：`avatarShape` 设为 `square`。
- **直角气泡**：`bubbleStyle` 设为 `square`。
- **无阴影卡片**：`cardStyle` 为 `flat`、`cardElevation` 为 `0`。

## 令牌速查

| 令牌 | 值 | 说明 |
| --- | --- | --- |
| `accent` | `#12B7F5` | QQ 蓝 |
| `cardCorner` | `4` | 直角卡片 |
| `tabStyle` | `plain` | 纯色标签栏 |
| `tabBarIndicator` | `underline` | 下划线选中指示 |
| `bubbleStyle` | `square` | 直角气泡 |
| `avatarShape` | `square` | 方形头像 |
| `bgStyle` | `flat` | 纯色背景（无壁纸） |

## 授权

MIT。
""",
    },
]


def build(theme):
    d = os.path.join(SRC, theme["dir"])
    # 不删整个目录再重建 —— 覆盖写就行，而且删目录在这台机器上
    # 会被回收站机制拦下来（"系统找不到指定的文件"）。
    os.makedirs(os.path.join(d, "assets"), exist_ok=True)

    with open(os.path.join(d, "plugin.json"), "w", encoding="utf-8") as f:
        json.dump(theme["plugin"], f, ensure_ascii=False, indent=2)
    with open(os.path.join(d, "ui.json"), "w", encoding="utf-8") as f:
        json.dump(theme["ui"], f, ensure_ascii=False, indent=2)
    with open(os.path.join(d, "README.md"), "w", encoding="utf-8") as f:
        f.write(theme["readme"])

    if theme["wallpaper"]:
        name, src_name = theme["wallpaper"]
        src = os.path.join(TMP, src_name)
        if not os.path.isfile(src):
            raise SystemExit("找不到壁纸源文件：" + src)
        shutil.copy2(src, os.path.join(d, "assets", name))

    os.makedirs(DIST, exist_ok=True)
    out = os.path.join(DIST, theme["zip"])
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for name in ("plugin.json", "ui.json", "README.md"):
            z.write(os.path.join(d, name), name)
        ad = os.path.join(d, "assets")
        if os.path.isdir(ad):
            for fn in sorted(os.listdir(ad)):
                z.write(os.path.join(ad, fn), "assets/" + fn)
    size = os.path.getsize(out)
    print("生成 %s  (%.1f KB)" % (theme["zip"], size / 1024.0))


for t in THEMES:
    build(t)
