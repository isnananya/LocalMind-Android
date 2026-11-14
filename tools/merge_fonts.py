# -*- coding: utf-8 -*-
"""
把字体库流水线的产物合并进云端 fonts.json，并上传样张。

## 两步分开的原因

二进制（字体文件）走 Release，样张和清单走仓库。前者一次 192 个 1.7 GB，
后者是 168 张 40 KB 的小图 —— 混在一个动作里，失败时很难判断是"图传丢了"
还是"json 没更新"，而这个 json 一旦和样张不同步，用户看到的就是一排空卡片。

用法：
    python tools/merge_fonts.py merge    # 本地合并，产出 _fontstage/merged.json
    python tools/merge_fonts.py upload   # 上传样张 + 清单（幂等，可重跑）
"""
import base64
import hashlib
import io
import json
import os
import subprocess
import sys
import time

BASE = r"N:\Data\big美铝难安\yiyuan\aichat"
STAGE = os.path.join(BASE, "_fontstage")
DATAREPO = os.path.join(BASE, "_datarepo")
REPO = "isnananya/LocalMind-Data"

RELEASE_BASE = "https://github.com/%s/releases/download/fonts-v1/" % REPO
RAW_BASE = "https://raw.githubusercontent.com/%s/main/data" % REPO


def style_of(name):
    """按名字猜一个风格标签。

    这不是"智能分类"，只是让卡片上那行小字别空着 —— 字体名里本来就带
    风格词（"叶根友圆趣卡通体""汉仪超粗黑"），取关键词比留空好。
    """
    table = [
        ("手写", ["手写", "手书", "手迹", "笔记", "手绘", "草书", "行书"]),
        ("圆体", ["圆", "泡泡", "软", "奶油", "布丁"]),
        ("卡通", ["卡通", "漫画", "童", "动画", "趣味"]),
        ("黑体", ["黑", "粗", "heavy", "bold"]),
        ("宋体", ["宋", "明朝", "衬线"]),
        ("书法", ["书", "毛笔", "楷", "隶", "篆"]),
    ]
    low = name.lower()
    for label, keys in table:
        if any(k in low for k in keys):
            return label
    return "个性"


def merge():
    man = json.load(io.open(os.path.join(STAGE, "fonts.json"), encoding="utf-8"))

    existing_path = os.path.join(DATAREPO, "data", "fonts.json")
    doc = json.load(io.open(existing_path, encoding="utf-8"))
    olds = doc["fonts"] if isinstance(doc, dict) else doc

    have_sha = set()
    for f in os.listdir(os.path.join(DATAREPO, "data", "fonts")):
        p = os.path.join(DATAREPO, "data", "fonts", f)
        with open(p, "rb") as fh:
            have_sha.add(hashlib.sha1(fh.read()).hexdigest())

    added, previews = [], []
    for e in man["entries"]:
        if e["sha1"] in have_sha:
            continue  # 仓库里已经有一模一样的字体（老条目），不重复上架
        name = e["name"]
        entry = {
            "id": e["id"],
            "name": name,
            "author": "本地字体库",
            "style": style_of(name),
            "description": "%s，来自本地字体库" % name,
            "sample": "你好，世界",
            "version": "1.0.0",
            # 二进制走 Release：数据仓库同时托管 Pages（1 GB 上限），
            # 1.7 GB 字体塞进仓库会把 Pages 发布搞挂。
            "fileUrl": RELEASE_BASE + e["id"] + "." + e["ext"],
            "previewUrl": RAW_BASE + "/previews/fonts/" + e["id"] + ".png",
            "sizeBytes": e["bytes"],
            "tags": [style_of(name), "字体库"],
        }
        if e["ext"] == "ttc":
            entry["tags"].append("字体集")
        added.append(entry)
        previews.append(e["id"] + ".png")

    merged = olds + added
    out = dict(doc) if isinstance(doc, dict) else {}
    out["fonts"] = merged
    with io.open(os.path.join(STAGE, "merged.json"), "w", encoding="utf-8") as fh:
        json.dump(out, fh, ensure_ascii=False, indent=1)
    with io.open(os.path.join(STAGE, "previews_todo.json"), "w", encoding="utf-8") as fh:
        json.dump(previews, fh)

    print("原有 %d 条，新增 %d 条，合计 %d 条" % (len(olds), len(added), len(merged)))
    print("需要上传的样张：%d 张" % len(previews))


def gh_put(repo_path, local_bytes, message):
    """用 Contents API 单文件上传（仓库本地副本和远端不同步，不能直接 push）。"""
    payload = {
        "message": message,
        "content": base64.b64encode(local_bytes).decode(),
    }
    # 已存在就要带 sha，否则 422（"sha wasn't supplied"）
    cur = subprocess.run(
        ["gh", "api", "-X", "GET", "repos/%s/contents/%s" % (REPO, repo_path),
         "-q", ".sha"],
        capture_output=True, text=True,
    )
    if cur.returncode == 0 and cur.stdout.strip():
        payload["sha"] = cur.stdout.strip()

    body = json.dumps(payload)
    for attempt in range(4):
        r = subprocess.run(
            ["gh", "api", "-X", "PUT", "repos/%s/contents/%s" % (REPO, repo_path),
             "--input", "-"],
            input=body, capture_output=True, text=True,
        )
        if r.returncode == 0:
            return True
        err = (r.stderr or "")[:200]
        if "sha" in err and attempt == 0:
            time.sleep(1)
            continue
        print("   失败：%s" % err.strip())
        time.sleep(1.5)
    return False


def upload():
    todo = json.load(io.open(os.path.join(STAGE, "previews_todo.json"), encoding="utf-8"))
    total = len(todo)
    done = 0
    for i, name in enumerate(todo):
        src = os.path.join(STAGE, "previews", name)
        if not os.path.isfile(src):
            print("  缺文件：%s" % name)
            continue
        with open(src, "rb") as fh:
            data = fh.read()
        ok = gh_put("data/previews/fonts/" + name, data, "fonts: 新增样张 " + name)
        if ok:
            done += 1
        if (i + 1) % 20 == 0:
            print("  ... 样张 %d/%d（成功 %d）" % (i + 1, total, done), flush=True)
    print("样张上传完成：%d/%d" % (done, total))

    with open(os.path.join(STAGE, "merged.json"), "rb") as fh:
        body = fh.read()
    ok = gh_put("data/fonts.json", body, "fonts: 字体库扩容（%d 条）" % count_entries())
    print("清单上传：%s" % ("成功" if ok else "失败"))


def count_entries():
    d = json.load(io.open(os.path.join(STAGE, "merged.json"), encoding="utf-8"))
    return len(d["fonts"])


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "merge"
    if cmd == "merge":
        merge()
    elif cmd == "upload":
        upload()
