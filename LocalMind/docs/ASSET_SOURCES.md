# 主题素材来源与授权

> 加新主题时的取材顺序：**先来这里列的仓库找现成素材，实在找不到再考虑生成**。
> 记录每张图/每个素材的出处与授权，避免把来源不明的图打进主题包。

## 壁纸

| 仓库 | 授权 | 说明 |
|---|---|---|
| [JoshuaThadi/Wall-E-Desk](https://github.com/JoshuaThadi/Wall-E-Desk) | MIT | 桌面+手机壁纸，`Anime/`、`landscape-anime/`、`Sci-Fi/` 分类。**当前主力来源**。注意：仓库虽标 MIT，部分图是网上收集的同人画， redistributed 时在主题 README/author 里注明出处即可；**带具体角色形象的图不要用**（版权风险），优先选场景类。 |
| [ind4skylivey/curated-wallpapers](https://github.com/ind4skylivey/aesthetic-wallpapers) | GPL-3.0 | 147 张精选，`wallpapers/{high-quality,standard}/anime/`。Git LFS 存储，下载要走 `media.githubusercontent.com`，比 Wall-E-Desk 麻烦。 |
| [SleepyCatHey/CozyPixels](https://github.com/SleepyCatHey/CozyPixels) | MIT（存疑） | Catppuccin/Nord 风格壁纸合集，约 1.9k star。README 自述"很多图没记录来源"，**授权追溯不了，慎用**。 |

### 下载方式

raw.githubusercontent.com 国内不通，走 `gh api` 的 raw 通道：

```bash
gh api "repos/JoshuaThadi/Wall-E-Desk/contents/<路径>" -H "Accept: application/vnd.github.raw" > out.jpg
```

列出仓库全部图片：

```bash
gh api "repos/<owner>/<repo>/git/trees/main?recursive=1" \
  --jq '.tree[] | select(.path|test("(?i)\\.(jpg|png|webp)$")) | "\(.size)\t\(.path)"'
```

### 壁纸处理规格

- 竖屏裁切：目标比例 **9:19.5**（1080×2340），场景图居中裁即可；
- JPEG quality 82 + optimize，单张控制在 **500KB 以内**；
- 带角色的图一律不用；深色主题选壁纸时留意上半部分要暗（状态栏文字可读）。

## 渐变 / 抽象背景

| 来源 | 授权 | 说明 |
|---|---|---|
| [itmeo/webgradients](https://github.com/itmeo/webgradients) | MIT | 180 组渐变，`gradients.json` 是结构化数据（色标+角度），可以直接写脚本批量渲染成 PNG 壁纸，不用手调颜色。 |

## 磨砂/玻璃质感参数参考

不是图片素材，是调 `ui.json` 令牌时的取值参考：

- [xvhuan/frostglass](https://github.com/xvhuan/frostglass)（React）：`--glass-bg: rgba(255,255,255,0.08)`、`--glass-border: rgba(255,255,255,0.15)`、`blur 12px` —— 对应我们 tokens 里 `cardBackground=#14FFFFFF`、`barBorder=#26FFFFFF` 一档的透明度；
- [NimuStudio/Nimu-glass-ui](https://github.com/NimuStudio/Nimu-glass-ui)：三主题玻璃拟态组件的明暗边界处理，浅色玻璃用 0.65~0.8 白、深色玻璃用 0.1~0.2 白。

## 已用素材台账

| 主题 | 素材 | 来源 |
|---|---|---|
| 猫羽雫 | assets/nekoha.png（猫耳少女夜窗） | AI 生成（2026-11-14，原创风格，无具体角色） |
| 星夜 | assets/starfall.jpg（紫蓝流星夜城） | Wall-E-Desk `landscape-anime/anime-night-sky.jpg` |
| 月栖 | assets/moonwell.jpg（月下湖谷） | Wall-E-Desk `landscape-anime/anime-moon-landscape.jpg` |
| 彗星 | assets/comet.jpg（低饱和彗星夜城） | Wall-E-Desk `Anime/yourname-nord.png` |
| 晴空 | assets/clearsky.jpg（蓝天白云城市） | Wall-E-Desk `Anime/weathering with you.png` |

## 上架流程（新主题）

```bash
# 1. 建包：plugins-src/theme-xxx/{plugin.json, ui.json, README.md, assets/壁纸}
# 2. 接入四个列表（都有现成条目照抄）：
#    tools/pack_themes.py THEMES
#    tools/gen_theme_previews.py 顶部列表
#    build_remote_manifests.py  THEMES + FILES
#    tools/push_theme_data.py   FILES
# 3. 打包 + 预览 + 清单 + 推送：
python tools/pack_themes.py
python tools/gen_theme_previews.py
python build_remote_manifests.py
python tools/push_theme_data.py
```
