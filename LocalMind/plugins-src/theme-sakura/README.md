# 樱雪物语 · LocalMind 界面主题

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
