# -*- coding: utf-8 -*-
"""
字体库 → 云端字体商店 的预处理流水线。

干三件事，都只看本地文件、不联网：

1. **扫描去重**：`Z:\\big美铝难安\\pj\\自定义\\字体库` 里有 200 个字体文件，
   其中难免有重复（同一个字体换个文件名）。按文件内容 md5 去重，id 直接
   用内容前 10 位 —— 内容寻址，天然幂等，重跑不会产生重复条目。

2. **渲染样张**：用**字体本身**画一张 720×460 的样张（和已有 25 款同一版式），
   这样用户在下之前就能看到这个字体长什么样，而不是靠想象。

3. **产出清单**：`fonts.json` 的条目数组 + 上传用的重命名副本。

## 为什么字体二进制不进数据仓库

数据仓库同时托管着 GitHub Pages（国内实测 0.8s 的那个源），而 **Pages 站点
有 1 GB 上限**。1.8 GB 字体塞进仓库会直接把 Pages 发布搞挂 —— 等于为了上
字体，把最稳的下载源弄死。所以二进制走 GitHub Release 资源，仓库里只留
清单和样张。

用法：
    python tools/font_pipeline.py scan      # 扫描 + 渲染样张（不联网）
"""
import hashlib
import io
import json
import os
import sys

from PIL import Image, ImageDraw, ImageFont

SRC = r"Z:\big美铝难安\pj\自定义\字体库"
STAGE = r"N:\Data\big美铝难安\yiyuan\aichat\_fontstage"
PREVIEW_DIR = os.path.join(STAGE, "previews")
BIN_DIR = os.path.join(STAGE, "bins")
MANIFEST = os.path.join(STAGE, "fonts.json")

W, H = 720, 460
SAMPLE = "你好，世界"

# 六套配色循环使用，避免 200 张样张一片粉。
PALETTES = [
    ((255, 236, 244), (233, 226, 255), (255, 92, 157)),
    ((232, 243, 255), (214, 232, 255), (58, 140, 245)),
    ((238, 250, 240), (219, 241, 226), (41, 168, 108)),
    ((255, 246, 232), (255, 232, 210), (240, 140, 40)),
    ((243, 238, 255), (226, 219, 252), (124, 92, 224)),
    ((255, 240, 238), (255, 223, 220), (226, 82, 74)),
]


def gradient(c1, c2, w=W, h=H):
    """对角柔和渐变。逐行插值太慢也用不着，画 2×2 再放大即可。"""
    small = Image.new("RGB", (2, 2))
    small.putpixel((0, 0), c1)
    small.putpixel((1, 0), tuple((a + b) // 2 for a, b in zip(c1, c2)))
    small.putpixel((0, 1), tuple((a + b) // 2 for a, b in zip(c1, c2)))
    small.putpixel((1, 1), c2)
    return small.resize((w, h), Image.BICUBIC)


def blob(img, center, radius, color, alpha=52):
    """一团柔光。用一张小图模糊后贴上去，比画圆再模糊快得多。"""
    layer = Image.new("RGBA", (radius * 2, radius * 2), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    d.ellipse((0, 0, radius * 2 - 1, radius * 2 - 1), fill=color + (alpha,))
    layer = layer.filter(__import__("PIL.ImageFilter", fromlist=["ImageFilter"]).GaussianBlur(radius // 2))
    img.paste(layer, (center[0] - radius, center[1] - radius), layer)


def fit_size(font_path, text, max_w, start, min_size=18, index=0):
    """从 start 往下试，直到文字宽度塞进 max_w。"""
    size = start
    while size > min_size:
        try:
            f = ImageFont.truetype(font_path, size, index=index)
        except Exception:
            return None, 0
        try:
            box = f.getbbox(text)
        except Exception:
            box = None
        if box and (box[2] - box[0]) <= max_w:
            return f, size
        size -= 4
    return None, 0


def render(font_path, name, idx, index=0):
    c1, c2, accent = PALETTES[idx % len(PALETTES)]
    canvas = gradient(c1, c2).convert("RGBA")
    blob(canvas, (90, 70), 170, accent, 34)
    blob(canvas, (640, 400), 200, accent, 30)

    d = ImageDraw.Draw(canvas)
    # 白色主卡片
    d.rounded_rectangle((28, 28, W - 28, H - 28), radius=34, fill=(255, 255, 255))

    title_font, tsize = fit_size(font_path, SAMPLE, W - 130, 112, index=index)
    if title_font is None:
        return None
    d.text((62, 60), SAMPLE, font=title_font, fill=(28, 28, 34))

    # 副行：拉丁 + 数字，字重小一点，顺便暴露拉丁字形缺失
    sub_font, _ = fit_size(font_path, "Aa Bb 0123", 420, 40, min_size=20, index=index)
    if sub_font is None:
        sub_font = ImageFont.load_default()
    d.text((64, 60 + tsize + 18), "Aa Bb 0123 · 中文样张", font=sub_font, fill=(122, 122, 132))

    # 强调气泡（模拟自己发的消息）
    bubble_font, _ = fit_size(font_path, "这个字体好看吗", 330, 34, min_size=18, index=index)
    if bubble_font is None:
        bubble_font = sub_font
    bx0, by0 = 196, H - 178
    d.rounded_rectangle((bx0, by0, bx0 + 464, by0 + 60), radius=30, fill=accent)
    d.text((bx0 + 26, by0 + 11), "这个字体好看吗", font=bubble_font, fill=(255, 255, 255))

    # 描边气泡（模拟对方） + 字体名
    b2 = (56, H - 106, 370, H - 46)
    d.rounded_rectangle(b2, radius=30, fill=(255, 255, 255), outline=(196, 196, 204), width=2)
    d.text((b2[0] + 22, b2[1] + 11), "今天也要好好吃饭呀", font=bubble_font, fill=(60, 60, 68))

    name_font, _ = fit_size(font_path, name, 250, 26, min_size=14, index=index)
    if name_font is None:
        name_font = ImageFont.load_default()
    nb = d.textbbox((0, 0), name, font=name_font)
    d.text((W - 62 - (nb[2] - nb[0]), H - 92), name, font=name_font, fill=(92, 92, 100))

    return canvas.convert("RGB")


def is_font(data):
    head = data[:4]
    return head in (b"\x00\x01\x00\x00", b"OTTO", b"true", b"ttcf", b"ttcf")


def scan():
    os.makedirs(PREVIEW_DIR, exist_ok=True)
    os.makedirs(BIN_DIR, exist_ok=True)

    files = sorted(
        f for f in os.listdir(SRC)
        if os.path.isfile(os.path.join(SRC, f)) and f.lower().rsplit(".", 1)[-1] in ("ttf", "otf", "ttc")
    )

    seen = {}
    entries, skipped, dups = [], [], []
    for i, fname in enumerate(files):
        path = os.path.join(SRC, fname)
        with open(path, "rb") as fh:
            data = fh.read()
        if len(data) < 2048 or not is_font(data):
            skipped.append((fname, "不是字体文件"))
            continue
        digest = hashlib.md5(data).hexdigest()
        if digest in seen:
            dups.append((fname, seen[digest]))
            continue
        seen[digest] = fname

        fid = "font-" + digest[:10]
        ext = fname.lower().rsplit(".", 1)[-1]
        display = os.path.splitext(fname)[0]
        # 去掉【肆柒】这类作者前缀，留下真正的字体名
        display = display.strip()

        idx = 0
        if ext == "ttc":
            idx = 0
        try:
            img = render(path, display, i, index=idx)
        except Exception as e:  # noqa: BLE001
            skipped.append((fname, "渲染失败: %s" % e))
            continue
        if img is None:
            skipped.append((fname, "字形读不出来"))
            continue

        img.save(os.path.join(PREVIEW_DIR, fid + ".png"), optimize=True)
        with open(os.path.join(BIN_DIR, fid + "." + ext), "wb") as fh:
            fh.write(data)

        entries.append({
            "id": fid,
            "name": display,
            "src": fname,
            "ext": ext,
            "bytes": len(data),
            "sha1": hashlib.sha1(data).hexdigest(),
        })
        if (i + 1) % 20 == 0:
            print("  ... %d/%d" % (i + 1, len(files)), flush=True)

    with open(MANIFEST, "w", encoding="utf-8") as fh:
        json.dump({"entries": entries, "skipped": skipped, "dups": dups}, fh, ensure_ascii=False, indent=1)

    total = sum(e["bytes"] for e in entries)
    print("字体文件 %d 个" % len(files))
    print("去重后 %d 个，合计 %.1f MB" % (len(entries), total / 1048576.0))
    print("重复 %d 个：%s" % (len(dups), ", ".join(d[0] for d in dups[:8])))
    print("跳过 %d 个：" % len(skipped))
    for n, why in skipped[:15]:
        print("   - %s -> %s" % (n, why))


if __name__ == "__main__":
    scan()
