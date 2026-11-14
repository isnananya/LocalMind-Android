#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成要上传到 LocalMind-Data 仓库的清单文件与静态资源。

产出（全部放在 `dist/remote-data/`，直接对应仓库的 `data/` 目录）：
    themes.json       在线主题清单
    voices.json       在线音色清单
    theme-packs/*.zip 主题包本体
    previews/*.png    主题预览图

上传方式（数据仓库 https://github.com/isnananya/LocalMind-Data）：
把 `dist/remote-data/` 里的东西整体放进仓库的 `data/` 目录，App 端就能拉到。

## 为什么主地址写 raw、镜像单独列
2026-11 实测国内网络：`github.com` / `raw.githubusercontent.com` /
`*.github.io` / `cdn.jsdelivr.net` 全部 http=000 不通，只有 ghproxy 系代理
能 200。但国外用户直连 raw 最快，所以清单主地址写标准 GitHub 地址，
`mirrors` 里列国内可达的代理；App 端 `ThemeStoreService` 还会自动为
GitHub 系地址补 ghproxy 镜像，双保险。

## 清单格式与 App 端的解析规则

### themes.json
顶层是 `{"themes": [...]}`（也接受 `list`）。每条：
- `id`      必填，必须匹配 `^[A-Za-z0-9._-]+$`（会被拼成目录名）
- `name`    必填
- `downloadUrl` 必填，http/https
- `mirrors` 备用下载地址（数组，逐个回退）
- `previewUrl` / `previewMirrors` 预览图地址（失败不影响下载）
- 其余可选：`author` `description` `version` `sizeBytes` `tags`

### voices.json
顶层是 `{"voices": [...]}`。每条：
- `id`      必填，必须匹配 `^[A-Za-z0-9._-]+$`
- `label`   必填
- `url` / `mirrors`  http/https，留空则按官方 Release 约定拼
"""
import json
import os

ROOT = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(ROOT, "dist", "remote-data")
os.makedirs(OUT, exist_ok=True)

# ------------------------------------------------------------------ 主题清单

# 主题包本体与预览图都要传到仓库里。主地址用标准 GitHub raw（国外直连最快），
# mirrors 列国内可达的代理；App 自己还会再展开一轮 ghproxy。
REPO_RAW = "https://raw.githubusercontent.com/isnananya/LocalMind-Data/main/data"
GH_PAGES = "https://isnananya.github.io/LocalMind-Data/data"
JSDELIVR = "https://cdn.jsdelivr.net/gh/isnananya/LocalMind-Data@main/data"


def sources(path):
    """给一个仓库内相对路径生成主地址 + 镜像列表。"""
    return {
        "main": REPO_RAW + "/" + path,
        "mirrors": [
            "https://ghproxy.net/" + REPO_RAW + "/" + path,
            "https://gh-proxy.com/" + REPO_RAW + "/" + path,
            GH_PAGES + "/" + path,
            JSDELIVR + "/" + path,
        ],
    }


def theme_entry(id_, name, desc, short, tags):
    pack = os.path.join(OUT, "theme-packs", short + ".zip")
    pkg = sources("theme-packs/" + short + ".zip")
    pic = sources("previews/" + short + ".png")
    return {
        "id": id_,
        "name": name,
        "author": "LocalMind",
        "description": desc,
        "version": "1.0.0",
        "downloadUrl": pkg["main"],
        "mirrors": pkg["mirrors"],
        "previewUrl": pic["main"],
        "previewMirrors": pic["mirrors"],
        "sizeBytes": os.path.getsize(pack) if os.path.isfile(pack) else 0,
        "tags": tags,
    }


THEME_DEFS = [
    ("dev.localmind.theme.sakura", "樱雪物语",
     "二次元粉紫风格：带樱花壁纸、尾巴气泡、玻璃卡片、圆头像。",
     "sakura", ["二次元", "浅色", "壁纸"]),
    ("dev.localmind.theme.aurora-night", "极光夜航",
     "暗色极光风格：星空壁纸、霓虹青强调色、拟物卡片、方角头像。",
     "aurora-night", ["暗色", "壁纸", "极简"]),
    ("dev.localmind.theme.qq-classic", "QQ经典蓝",
     "复刻 QQ 经典版：蓝白配色、直角矩形、方形头像、下划线标签栏。",
     "qq-classic", ["经典", "浅色", "直角"]),
]

# 先把主题包复制到待上传目录，sizeBytes 才能读真实大小
import shutil
packs = os.path.join(OUT, "theme-packs")
os.makedirs(packs, exist_ok=True)
for src, dst in [
    ("樱雪物语主题插件.zip", "sakura.zip"),
    ("极光夜航主题插件.zip", "aurora-night.zip"),
    ("QQ经典蓝主题插件.zip", "qq-classic.zip"),
]:
    s = os.path.join(ROOT, "dist", "plugins", src)
    if os.path.isfile(s):
        shutil.copy2(s, os.path.join(packs, dst))

themes = [theme_entry(*d) for d in THEME_DEFS]

with open(os.path.join(OUT, "themes.json"), "w", encoding="utf-8") as f:
    json.dump({"themes": themes}, f, ensure_ascii=False, indent=2)

# ------------------------------------------------------------------ 音色清单

# 音色权重走 sherpa-onnx 官方 Release。url 留空时 App 会按约定拼：
#   https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/<id>.tar.bz2
# 这里显式写 mirrors，好让国内用户有别的路可走（App 端还会自己套 ghproxy）。
SHERPA = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"

voices = [
    {
        "id": "vits-melo-tts-zh_en",
        "label": "梅洛（中文女声 · 开源）",
        "description": "167 MB · 完全离线 · 社区开源模型。中文女声，音色温暖，适合当人设默认声音。",
        "badge": "开源",
        "sizeBytes": 167006755,
        "url": SHERPA + "/vits-melo-tts-zh_en.tar.bz2",
        "mirrors": [],
        "sampleText": "你好，我是梅洛，很高兴认识你。",
    },
    {
        "id": "kokoro-int8-multi-lang-v1_1",
        "label": "Kokoro 星野（多语言 · 推荐）",
        "description": "140 MB · 完全离线 · Apache-2.0（Kokoro）。中/英/日/韩多语言，目前清单里最自然的音色。",
        "badge": "140 MB",
        "sizeBytes": 147052523,
        "url": SHERPA + "/kokoro-int8-multi-lang-v1_1.tar.bz2",
        "mirrors": [],
        "sampleText": "你好，我是星野。用自然的声音，陪你把每一天过得更从容。",
    },
    {
        "id": "vits-piper-zh_CN-chaowen-medium-int8",
        "label": "超文（男声 · 轻量）",
        "description": "13 MB · 完全离线 · Apache-2.0（Piper）。低端机首选：几秒下完，合成也是这批里最快的。",
        "badge": "13 MB",
        "sizeBytes": 14011298,
        "url": SHERPA + "/vits-piper-zh_CN-chaowen-medium-int8.tar.bz2",
        "mirrors": [],
        "sampleText": "你好，我是超文。有什么可以帮你的吗？",
    },
    {
        "id": "vits-piper-zh_CN-huayan-medium",
        "label": "华研（女声 · 普通话）",
        "description": "64 MB · 完全离线 · Apache-2.0（Piper）。中文女声，断句自然，64 MB 在手机上不算大。",
        "badge": "64 MB",
        "sizeBytes": 67255926,
        "url": SHERPA + "/vits-piper-zh_CN-huayan-medium.tar.bz2",
        "mirrors": [],
        "sampleText": "你好呀，我是华研。今天想聊点什么？",
    },
    {
        "id": "vits-zh-hf-fanchen-unity",
        "label": "泛尘（女声 · 多说话人）",
        "description": "114 MB · 完全离线 · 社区模型（个人/非商用）。音色更接近真人，适合当人设的默认声音。",
        "badge": "114 MB",
        "sizeBytes": 119143475,
        "url": SHERPA + "/vits-zh-hf-fanchen-unity.tar.bz2",
        "mirrors": [],
        "sampleText": "你好，我是运行在你手机上的语音模型。",
    },
    {
        "id": "sherpa-onnx-vits-zh-ll",
        "label": "LL-VITS（中文 · 带词典）",
        "description": "113 MB · 完全离线 · Apache-2.0。带中文 lexicon：数字、日期、多音字读得更准，适合长句朗读。",
        "badge": "113 MB",
        "sizeBytes": 118810709,
        "url": SHERPA + "/sherpa-onnx-vits-zh-ll.tar.bz2",
        "mirrors": [],
        "sampleText": "数字 2026 年 10 月 15 日，多音字「行」在这里读 háng。",
    },
    {
        "id": "sherpa-onnx-zipvoice-distill-int8-zh-en-emilia",
        "label": "声音克隆底模（中英 · 零样本）",
        "description": "109 MB · 完全离线 · Apache-2.0（ZipVoice2）。上传一段你的录音即可复刻音色，无需训练。",
        "badge": "克隆",
        "sizeBytes": 109162785,
        "clonable": True,
        "url": SHERPA + "/sherpa-onnx-zipvoice-distill-int8-zh-en-emilia.tar.bz2",
        "mirrors": [],
        "sampleText": "这是用我的声音克隆出来的音色，以后就用这个声音和你说话。",
    },
]

with open(os.path.join(OUT, "voices.json"), "w", encoding="utf-8") as f:
    json.dump({"voices": voices}, f, ensure_ascii=False, indent=2)

# 顺便把主题包复制到待上传目录，用户一起传到仓库即可
print("已生成：")
for n in ("themes.json", "voices.json"):
    p = os.path.join(OUT, n)
    print("  %s  (%.1f KB)" % (n, os.path.getsize(p) / 1024.0))
print("  theme-packs/  (%d 个主题包)" % len(os.listdir(packs)))
prev = os.path.join(OUT, "previews")
print("  previews/     (%d 张预览图，由 tools/gen_theme_previews.py 生成)" %
      (len(os.listdir(prev)) if os.path.isdir(prev) else 0))
print()
print("上传说明：把 dist/remote-data/ 整个目录放进 LocalMind-Data 的 data/。")
print("  themes.json   → data/themes.json")
print("  voices.json   → data/voices.json")
print("  theme-packs/  → data/theme-packs/")
print("  previews/     → data/previews/")
print()
print("推上去之后 App 里「我的 → 主题 → 获取主题列表」就能拉到在线主题；")
print("国内网络靠清单里的 ghproxy 镜像下载，国外直连 raw。")
