#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 plugins-src/theme-*/ 打成可安装的主题 zip（dist/plugins/）。

和 build_theme_packs.py 的区别：那个脚本的 ui.json 是**写在 Python 里**的，
改一个令牌得改代码；这里直接读目录里的 plugin.json / ui.json / assets/*，
主题内容只存在数据文件里，加主题 = 加一个目录。

用法：
    python tools/pack_themes.py            # 打包下面列表里的主题
    python tools/pack_themes.py --all      # 打包 plugins-src/theme-* 全部
"""
import json
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "plugins-src")
DIST = os.path.join(ROOT, "dist", "plugins")
os.makedirs(DIST, exist_ok=True)

# 目录名 -> (zip 里显示的文件名, 仓库里用的短名)
THEMES = [
    ("theme-nekoha", "猫娘主题插件.zip", "nekoha"),
    ("theme-frost", "雾霜主题插件.zip", "frost"),
    ("theme-cyber", "赛博霓虹主题插件.zip", "cyber"),
    ("theme-cream", "奶油手账主题插件.zip", "cream"),
    ("theme-starfall", "星夜主题插件.zip", "starfall"),
    ("theme-moonwell", "月栖主题插件.zip", "moonwell"),
    ("theme-comet", "彗星主题插件.zip", "comet"),
    ("theme-clearsky", "晴空主题插件.zip", "clearsky"),
]


def pack(dir_name: str, zip_name: str) -> str:
    src = os.path.join(SRC, dir_name)
    manifest = json.load(open(os.path.join(src, "plugin.json"), encoding="utf-8"))
    ui = json.load(open(os.path.join(src, "ui.json"), encoding="utf-8"))
    if "ui_theme" not in manifest.get("abilities", []):
        raise SystemExit(f"{dir_name} 的 plugin.json 没有 ui_theme 能力")

    out = os.path.join(DIST, zip_name)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("plugin.json", json.dumps(manifest, ensure_ascii=False, indent=2))
        z.writestr("ui.json", json.dumps(ui, ensure_ascii=False, indent=2))
        readme = os.path.join(src, "README.md")
        if os.path.isfile(readme):
            z.write(readme, "README.md")
        assets = os.path.join(src, "assets")
        if os.path.isdir(assets):
            for name in sorted(os.listdir(assets)):
                path = os.path.join(assets, name)
                if os.path.isfile(path):
                    # 落盘用 basename：ui.json 里的 wallpaperFile 只写文件名
                    z.write(path, "assets/" + name)
    return out


def main() -> None:
    targets = THEMES
    if "--all" in sys.argv:
        targets = [
            (d, json.load(open(os.path.join(SRC, d, "plugin.json"), encoding="utf-8"))["name"] + "主题插件.zip", d.replace("theme-", ""))
            for d in sorted(os.listdir(SRC))
            if d.startswith("theme-") and os.path.isfile(os.path.join(SRC, d, "plugin.json"))
        ]
    for dir_name, zip_name, _short in targets:
        path = pack(dir_name, zip_name)
        print(f"{zip_name}  {os.path.getsize(path) / 1024:.0f} KB")


if __name__ == "__main__":
    main()
