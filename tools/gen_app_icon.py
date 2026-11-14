"""从一张 1024+ 的方形图生成 Android 自适应图标全套资源。

用法（在仓库根目录执行）：

    python tools/gen_app_icon.py path/to/icon.png
    python tools/gen_app_icon.py path/to/icon.png --ratio 0.78

产出（直接覆盖 app/src/main/res 下的文件）：

    drawable-nodpi/ic_launcher_background.png   432×432   背景：图案同色系柔光渐变
    drawable-nodpi/ic_launcher_foreground.png   432×432   前景：图案缩到 78% 居中
    mipmap-{m,h,x,xx,xxx}dpi/ic_launcher.png    48…192    旧式方形图标
    mipmap-{m,h,x,xx,xxx}dpi/ic_launcher_round.png          旧式圆形图标

## 为什么是 78%

自适应图标画布 108dp，**系统只显示中间 72dp**，外圈 18dp 是留给视差和裁切的。
所以：

  * 按 108dp 铺满 → 用户只看到图案中心 66% 的一块放大图（"图标显示不全"）；
  * 缩到 70% 居中 → 图案自带的圆角方块边框正好压在圆形遮罩内，出现"方框套圆"
    的双层框；
  * 78% → 对话气泡、四角星、行星环都在可视区内，图案自带的圆角边框落在遮罩外。

改这个数字之前请先按 `--preview` 看一眼模拟效果。

## 背景层为什么是模糊图

原图四角是纯黑（JPEG 没有 alpha）。直接把整图当背景，圆形遮罩一裁就会在
四角露出黑边；裁掉四角又会有白边。用图案中心区域降采样再放大 + 高斯模糊，
得到的渐变色和图案同色系，遮罩边缘自然过渡。
"""
from __future__ import annotations

import argparse
import pathlib

from PIL import Image, ImageChops, ImageDraw, ImageFilter

RES = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/res"
CANVAS = 432          # 108dp @4x
ART = 1024            # 内部工作尺寸
CORNER = 228          # 圆角半径，占 ART 的 22.3%（与原图一致）
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def rounded_alpha(size: int, radius: int) -> Image.Image:
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size - 1, size - 1), radius=radius, fill=255)
    return mask


def circle_alpha(size: int) -> Image.Image:
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
    return mask


def trim_to_square(src: Image.Image) -> Image.Image:
    """去掉外圈纯黑/纯白留白，取中间的方形图案。"""
    rgb = src.convert("RGB")
    solid = Image.new("RGB", rgb.size, rgb.getpixel((0, 0)))
    diff = ImageChops.difference(rgb, solid).convert("L").point(lambda v: 255 if v > 10 else 0)
    box = diff.getbbox()
    if not box:
        return rgb.resize((ART, ART), Image.LANCZOS)
    x0, y0, x1, y1 = box
    side = max(x1 - x0, y1 - y0)
    cx, cy = (x0 + x1) // 2, (y0 + y1) // 2
    half = side // 2
    w, h = rgb.size
    box = (max(0, cx - half), max(0, cy - half), min(w, cx + half), min(h, cy + half))
    return rgb.crop(box).resize((ART, ART), Image.LANCZOS)


def build(art: Image.Image, ratio: float) -> tuple[Image.Image, Image.Image]:
    art = art.copy()
    art.putalpha(rounded_alpha(ART, CORNER))

    inner = art.crop((112, 112, ART - 112, ART - 112)).convert("RGB")
    bg = inner.resize((28, 28), Image.LANCZOS).resize((CANVAS, CANVAS), Image.BICUBIC)
    bg = bg.filter(ImageFilter.GaussianBlur(18))

    side = int(CANVAS * ratio)
    fg = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    fg.paste(art.resize((side, side), Image.LANCZOS), ((CANVAS - side) // 2,) * 2)
    return bg, fg


def preview(bg: Image.Image, fg: Image.Image) -> Image.Image:
    layer = bg.convert("RGBA")
    layer.alpha_composite(fg)
    # 系统只显示中间 72dp（= 432 * 2/3）
    inner = layer.crop((72, 72, CANVAS - 72, CANVAS - 72))
    size = inner.size[0]
    out = Image.new("RGB", (size * 2 + 40, size), (235, 235, 235))
    for i, mask in enumerate((circle_alpha(size), rounded_alpha(size, int(size * 0.28)))):
        tile = inner.copy()
        tile.putalpha(mask)
        flat = Image.new("RGBA", (size, size), (235, 235, 235, 255))
        flat.alpha_composite(tile)
        out.paste(flat.convert("RGB"), (i * (size + 40), 0))
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("source")
    ap.add_argument("--ratio", type=float, default=0.78, help="前景占 108dp 的比例")
    ap.add_argument("--preview", action="store_true", help="只输出遮罩模拟图，不写资源")
    ap.add_argument("--preview-out", default="icon_preview.png")
    args = ap.parse_args()

    src = Image.open(args.source)
    art = trim_to_square(src)
    bg, fg = build(art, args.ratio)

    if args.preview:
        preview(bg, fg).save(args.preview_out)
        print(f"预览已写入 {args.preview_out}")
        return

    art.putalpha(rounded_alpha(ART, CORNER))
    bg.save(RES / "drawable-nodpi/ic_launcher_background.png")
    fg.save(RES / "drawable-nodpi/ic_launcher_foreground.png")

    for name, size in DENSITIES.items():
        art.resize((size, size), Image.LANCZOS).save(RES / f"mipmap-{name}/ic_launcher.png")
        canvas = bg.resize((size, size), Image.LANCZOS).convert("RGBA")
        canvas.alpha_composite(art.resize((size, size), Image.LANCZOS))
        canvas.putalpha(circle_alpha(size))
        canvas.save(RES / f"mipmap-{name}/ic_launcher_round.png")

    print(f"已更新 {RES} 下的图标资源（前景比例 {args.ratio:.0%}）")


if __name__ == "__main__":
    main()
