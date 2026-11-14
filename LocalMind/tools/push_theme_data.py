#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 dist/remote-data/ 推到 isnananya/LocalMind-Data 仓库的 data/ 目录。

为什么不 git clone + push：
  * 只新增文件，不碰仓库里已有的 announcement/donors/release；
  * Contents API 可以逐条看到"创建 / 更新"结果，失败不影响其它文件；
  * 二进制走 JSON + base64 的 `--input` 文件，不受命令行长度限制。

前置：gh CLI 已登录且有 repo 写权限（gh auth status）。
跑法：python3 tools/push_theme_data.py [--dry-run]
"""
import base64
import json
import os
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "dist", "remote-data")
REPO = "isnananya/LocalMind-Data"
BRANCH = "main"
DRY = "--dry-run" in sys.argv

FILES = [
    "themes.json",
    "voices.json",
    "theme-packs/sakura.zip",
    "theme-packs/aurora-night.zip",
    "theme-packs/nekoha.zip",
    "theme-packs/frost.zip",
    "theme-packs/cyber.zip",
    "theme-packs/cream.zip",
    "previews/nekoha.png",
    "previews/frost.png",
    "previews/cyber.png",
    "previews/cream.png",
    "theme-packs/starfall.zip",
    "theme-packs/moonwell.zip",
    "theme-packs/comet.zip",
    "theme-packs/clearsky.zip",
    "previews/starfall.png",
    "previews/moonwell.png",
    "previews/comet.png",
    "previews/clearsky.png",
    "theme-packs/qq-classic.zip",
    "previews/sakura.png",
    "previews/aurora-night.png",
    "previews/qq-classic.png",
]
# 已有的三个 JSON 是应用现役数据，绝不覆盖。
PROTECTED = {"announcement.json", "donors.json", "release.json"}


def gh(args, input_path=None):
    cmd = ["gh", "api"] + args
    if input_path:
        cmd += ["--input", input_path]
    return subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8")


def sha_of(rel):
    r = gh(["repos/%s/contents/data/%s?ref=%s" % (REPO, rel, BRANCH)])
    if r.returncode != 0:
        return None
    try:
        return json.loads(r.stdout).get("sha")
    except Exception:
        return None


def main():
    for rel in FILES:
        if rel in PROTECTED:
            print("跳过受保护文件：", rel)
            continue
        src = os.path.join(SRC, rel.replace("/", os.sep))
        if not os.path.isfile(src):
            print("缺少文件，跳过：", rel)
            continue
        data = open(src, "rb").read()
        payload = {
            "message": "chore(data): 上架在线主题清单、主题包与预览图 (%s)" % rel,
            "content": base64.b64encode(data).decode("ascii"),
            "branch": BRANCH,
        }
        old = sha_of(rel)
        if old:
            payload["sha"] = old
        action = "更新" if old else "新建"
        print("%s data/%-32s %8.1f KB" % (action, rel, len(data) / 1024.0), end=" ")
        if DRY:
            print("(dry-run)")
            continue
        fd, tmp = tempfile.mkstemp(suffix=".json")
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                json.dump(payload, f)
            r = gh([
                "--method", "PUT",
                "-H", "Content-Type: application/json",
                "repos/%s/contents/data/%s" % (REPO, rel),
            ], input_path=tmp)
            if r.returncode == 0:
                print("OK")
            else:
                print("失败 ->", (r.stderr or r.stdout).strip()[:200])
        finally:
            try:
                os.remove(tmp)
            except OSError:
                pass


if __name__ == "__main__":
    main()
