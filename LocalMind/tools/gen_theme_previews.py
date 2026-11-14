#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""给每个主题包生成一张「看起来就是这个主题」的 preview.png。

为什么不截图、不找 AI 画图：
  * 截图要有真机 / 模拟器，本机没有设备；
  * AI 生成的 UI 截图里的文字是假的、控件对不上真实令牌；
  * 这里直接读主题的 ui.json，用它的 accent / cardCorner / bubbleStyle /
    wallpaperFile 画一张迷你界面 —— 预览图和真装上去的样子是同一套数据。
依赖：只需要 ImageMagick 的 magick（把壁纸转成 raw RGB），PNG 编码用标准库。

产物：
  plugins-src/theme-*/assets/preview.png   随主题包一起打包
  dist/remote-data/previews/<id>.png        上传到 GitHub 数据仓库
  app/src/main/assets/builtin/themes/previews/<id>.png   内置进 APK
"""
import json
import os
import struct
import subprocess
import zipfile
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "plugins-src")
REMOTE = os.path.join(ROOT, "dist", "remote-data", "previews")
APK = os.path.join(ROOT, "app", "src", "main", "assets", "builtin", "themes", "previews")
DIST = os.path.join(ROOT, "dist", "plugins")

W, H = 360, 640

THEMES = [
    ("theme-sakura", "樱雪物语主题插件.zip", "sakura"),
    ("theme-aurora-night", "极光夜航主题插件.zip", "aurora-night"),
    ("theme-qq-classic", "QQ经典蓝主题插件.zip", "qq-classic"),
    ("theme-nekoha", "猫羽雫主题插件.zip", "nekoha"),
    ("theme-frost", "雾霜主题插件.zip", "frost"),
    ("theme-cyber", "赛博霓虹主题插件.zip", "cyber"),
    ("theme-cream", "奶油手账主题插件.zip", "cream"),
    ("theme-starfall", "星夜主题插件.zip", "starfall"),
    ("theme-moonwell", "月栖主题插件.zip", "moonwell"),
    ("theme-comet", "彗星主题插件.zip", "comet"),
    ("theme-clearsky", "晴空主题插件.zip", "clearsky"),
]


# ----------------------------------------------------------------- PNG 基础

def chunk(tag, data):
    return (struct.pack(">I", len(data)) + tag + data +
            struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))


def write_png(path, buf):
    raw = bytearray()
    stride = W * 3
    for y in range(H):
        raw.append(0)  # filter type 0
        raw += buf[y * stride:(y + 1) * stride]
    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", W, H, 8, 2, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)


def color(s, fallback=(255, 255, 255)):
    if not isinstance(s, str):
        return fallback
    s = s.strip().lstrip("#")
    if len(s) == 8:            # AARRGGBB
        s = s[2:]
    if len(s) == 6:
        try:
            return tuple(int(s[i:i + 2], 16) for i in (0, 2, 4))
        except ValueError:
            pass
    return fallback


def blend(buf, x, y, rgb, a=1.0):
    if a <= 0 or x < 0 or y < 0 or x >= W or y >= H:
        return
    if a >= 1:
        p = (y * W + x) * 3
        buf[p:p + 3] = bytes(rgb)
        return
    p = (y * W + x) * 3
    buf[p] = int(buf[p] * (1 - a) + rgb[0] * a)
    buf[p + 1] = int(buf[p + 1] * (1 - a) + rgb[1] * a)
    buf[p + 2] = int(buf[p + 2] * (1 - a) + rgb[2] * a)


def rect(buf, x0, y0, x1, y1, rgb, a=1.0, radius=0):
    r = min(radius, (x1 - x0) // 2, (y1 - y0) // 2)
    for y in range(max(0, y0), min(H, y1)):
        for x in range(max(0, x0), min(W, x1)):
            if r > 0:
                cx = min(max(x, x0 + r), x1 - r - 1)
                cy = min(max(y, y0 + r), y1 - r - 1)
                dx, dy = x - cx, y - cy
                if dx * dx + dy * dy > r * r:
                    # 圆角外的区域做一点点抗锯齿
                    d = (dx * dx + dy * dy) ** 0.5 - r
                    if d > 1:
                        continue
                    blend(buf, x, y, rgb, a * (1 - d))
                    continue
            blend(buf, x, y, rgb, a)


def line(buf, x0, y0, x1, y1, rgb, a=1.0, width=3, radius=2):
    rect(buf, x0, y0, x1, y1, rgb, a, min(radius, width // 2))


def circle(buf, cx, cy, r, rgb, a=1.0):
    for y in range(cy - r, cy + r + 1):
        for x in range(cx - r, cx + r + 1):
            if (x - cx) ** 2 + (y - cy) ** 2 <= r * r:
                blend(buf, x, y, rgb, a)


# ----------------------------------------------------------------- 背景

def wallpaper_bg(zip_path, wallpaper_file, dim):
    """把主题包里的壁纸缩放裁切成 360x640 的 raw RGB，并叠加压暗。"""
    if not wallpaper_file:
        return None
    with zipfile.ZipFile(zip_path) as z:
        name = "assets/" + wallpaper_file
        if name not in z.namelist():
            return None
        data = z.read(name)
    tmp = os.path.join(ROOT, ".preview-src.png")
    raw = os.path.join(ROOT, ".preview-src.raw")
    with open(tmp, "wb") as f:
        f.write(data)
    try:
        subprocess.run(
            ["magick", tmp, "-resize", "%dx%d^" % (W, H),
             "-gravity", "center", "-extent", "%dx%d" % (W, H),
             "-depth", "8", "rgb:" + raw],
            check=True, capture_output=True,
        )
        with open(raw, "rb") as f:
            pixels = bytearray(f.read())
    finally:
        for p in (tmp, raw):
            try:
                os.remove(p)
            except OSError:
                pass
    if len(pixels) < W * H * 3:
        return None
    if dim > 0:
        k = 1.0 - min(dim, 0.85)
        for i in range(0, W * H * 3, 3):
            pixels[i] = int(pixels[i] * k)
            pixels[i + 1] = int(pixels[i + 1] * k)
            pixels[i + 2] = int(pixels[i + 2] * k)
    return pixels


def gradient_bg(top, bottom):
    buf = bytearray(W * H * 3)
    for y in range(H):
        t = y / max(1, H - 1)
        c = tuple(int(top[i] * (1 - t) + bottom[i] * t) for i in range(3))
        row = bytes(c) * W
        buf[y * W * 3:(y + 1) * W * 3] = row
    return buf


# ----------------------------------------------------------------- 画界面

def render(zip_path):
    with zipfile.ZipFile(zip_path) as z:
        ui = json.loads(z.read("ui.json"))
    t = ui.get("tokens", {})
    accent = color(t.get("accent"), (18, 183, 245))
    card = color(t.get("cardBackground"), (255, 255, 255))
    bar = color(t.get("barBackground"), (255, 255, 255))
    bg = color(t.get("groupedBackground"), (242, 242, 247))
    label = color(t.get("label"), (28, 28, 30))
    sub = color(t.get("secondaryLabel"), (142, 142, 147))
    bubble_user = color(t.get("bubbleUser"), accent)
    bubble_ai = color(t.get("bubbleAi"), (255, 255, 255))
    fill = color(t.get("fill"), (235, 235, 240))

    corner = int(float(t.get("cardCorner", "14")))
    radius = max(0, min(28, int(corner * 0.85)))
    bubble_radius = max(0, min(22, int(float(t.get("bubbleCorner", "18")) * 0.8)))
    tail = t.get("bubbleStyle") == "tail"
    square_tab = t.get("tabBarShape") == "square"
    avatar_square = t.get("avatarShape") == "square"

    dim = float(t.get("wallpaperDim", "0") or "0")
    buf = wallpaper_bg(zip_path, t.get("wallpaperFile", ""), dim)
    if buf is None:
        style = t.get("bgStyle", "flat")
        buf = gradient_bg(bg, card if style == "gradient" else bg)

    # 状态栏：时间 + 右侧三个点（不画文字，避免字体依赖）
    line(buf, 22, 20, 46, 23, label, 0.65, 3)
    circle(buf, 316, 22, 3, label, 0.55)
    circle(buf, 327, 22, 3, label, 0.55)
    rect(buf, 336, 17, 346, 26, label, 0.45, 2)

    # 导航栏
    rect(buf, 0, 38, W, 88, bar, 0.94)
    line(buf, 0, 87, W, 88, sub, 0.25, 1)
    line(buf, 150, 56, 210, 60, label, 0.8, 6, 3)
    circle(buf, 24, 62, 8, accent, 0.9)

    # 一张分组卡片：标题 + 两行占位文字 + 一个强调色开关
    card_a = 0.94 if t.get("cardStyle") in ("glass", "neu") else 1.0
    rect(buf, 16, 104, W - 16, 214, card, card_a, radius)
    line(buf, 32, 124, 108, 128, label, 0.85, 6, 3)
    line(buf, 32, 146, 250, 149, sub, 0.45, 4, 2)
    line(buf, 32, 160, 210, 163, sub, 0.35, 4, 2)
    rect(buf, W - 74, 140, W - 34, 160, accent, 0.9, 10)
    circle(buf, W - 44, 150, 8, (255, 255, 255), 1.0)
    rect(buf, 32, 184, W - 32, 185, fill, 0.9, 1)

    # AI 气泡（左）：方头像 + 圆角气泡 + 两行文字
    if avatar_square:
        rect(buf, 18, 240, 50, 272, accent, 0.85, 0)
    else:
        circle(buf, 34, 256, 16, accent, 0.85)
    ai_shape_r = 2 if t.get("bubbleStyle") == "square" else bubble_radius
    rect(buf, 58, 238, 268, 306, bubble_ai, 0.95, ai_shape_r)
    if tail:
        rect(buf, 56, 292, 64, 302, bubble_ai, 0.95, 2)
    line(buf, 74, 254, 224, 257, label, 0.7, 5, 2)
    line(buf, 74, 270, 248, 273, sub, 0.45, 4, 2)
    line(buf, 74, 284, 190, 287, sub, 0.35, 4, 2)

    # 用户气泡（右）：强调色 + 反色文字条
    user_r = 2 if t.get("bubbleStyle") == "square" else bubble_radius
    rect(buf, 116, 328, W - 18, 388, bubble_user, 0.96, user_r)
    if tail:
        rect(buf, W - 22, 374, W - 14, 384, bubble_user, 0.96, 2)
    line(buf, 134, 344, 296, 347, (255, 255, 255), 0.9, 5, 2)
    line(buf, 160, 362, 310, 365, (255, 255, 255), 0.65, 4, 2)

    # 第二张卡片（列表行）
    rect(buf, 16, 410, W - 16, 490, card, card_a, radius)
    rect(buf, 30, 424, 62, 456, accent, 0.75, 3 if avatar_square else 16)
    line(buf, 76, 432, 200, 435, label, 0.75, 5, 2)
    line(buf, 76, 450, 250, 453, sub, 0.4, 4, 2)
    rect(buf, 30, 474, W - 30, 475, fill, 0.8, 1)

    # 输入框
    rect(buf, 16, 512, W - 78, 556, card, 0.96, 18)
    line(buf, 32, 530, 150, 533, sub, 0.4, 4, 2)
    rect(buf, W - 68, 512, W - 16, 556, accent, 0.95, 18)
    line(buf, W - 54, 530, W - 30, 533, (255, 255, 255), 0.85, 4, 2)

    # 底部标签栏
    rect(buf, 0, H - 74, W, H, bar, 0.96)
    line(buf, 0, H - 74, W, H - 73, sub, 0.25, 1)
    seg = W // 3
    for i in range(3):
        cx = seg * i + seg // 2
        if square_tab:
            rect(buf, cx - 12, H - 54, cx + 12, H - 42,
                 accent if i == 0 else sub, 0.9 if i == 0 else 0.35, 0)
            if i == 0:
                rect(buf, cx - 22, H - 24, cx + 22, H - 21, accent, 1.0, 0)
        else:
            circle(buf, cx, H - 48, 10,
                   accent if i == 0 else sub, 0.9 if i == 0 else 0.35)
        line(buf, cx - 16, H - 28, cx + 16, H - 26,
             accent if i == 0 else sub, 0.8 if i == 0 else 0.3, 3, 1)

    return buf


def main():
    os.makedirs(REMOTE, exist_ok=True)
    os.makedirs(APK, exist_ok=True)
    for d, zipname, short in THEMES:
        zp = os.path.join(DIST, zipname)
        if not os.path.isfile(zp):
            print("跳过（还没打包）：", zipname)
            continue
        buf = render(zp)
        out1 = os.path.join(SRC, d, "assets", "preview.png")
        out2 = os.path.join(REMOTE, short + ".png")
        out3 = os.path.join(APK, short + ".png")
        write_png(out1, buf)
        for p in (out2, out3):
            os.makedirs(os.path.dirname(p), exist_ok=True)
            with open(p, "wb") as f:
                f.write(open(out1, "rb").read())
        print("预览图 %-12s %6.1f KB  -> %s" %
              (short, os.path.getsize(out1) / 1024.0,
               os.path.relpath(out1, ROOT)))


if __name__ == "__main__":
    main()
