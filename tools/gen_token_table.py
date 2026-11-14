#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 UiTheme.kt 的源码里抽出全部令牌，生成 docs 里的令牌表。

为什么自动生成：令牌表以前是手写的，结果代码里加了新令牌、文档没跟上，
作者照着文档写就发现"我改了这个键但没生效"—— 这是最坑的一类问题。

两份文档都有令牌表，都从这里生成：
  docs/PLUGIN.md    §8.3（通用插件文档）
  docs/UI-PLUGIN.md §4.1（界面插件专项文档）

跑法：python3 tools/gen_token_table.py
"""
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app/src/main/java/com/localmind/data/UiTheme.kt")
DOCS = os.path.join(ROOT, "docs")

text = open(SRC, encoding="utf-8").read()


def split_args(body):
    """按顶层逗号切参数列表，正确跳过字符串里的逗号和括号。"""
    parts, depth, cur, in_str = [], 0, "", False
    i = 0
    while i < len(body):
        ch = body[i]
        if in_str:
            cur += ch
            if ch == '"' and (i == 0 or body[i - 1] != "\\"):
                in_str = False
        elif ch == '"':
            in_str = True
            cur += ch
        elif ch in "([{":
            depth += 1
            cur += ch
        elif ch in ")]}":
            depth -= 1
            cur += ch
        elif ch == "," and depth == 0:
            parts.append(cur.strip())
            cur = ""
        else:
            cur += ch
        i += 1
    if cur.strip():
        parts.append(cur.strip())
    return parts


def scan_decls(src):
    """扫出所有 `val xxx = UiToken(...)` 的完整实参文本（括号配平）。"""
    out = {}
    for m in re.finditer(r"val\s+(\w+)\s*=\s*UiToken\s*\(", src):
        name = m.group(1)
        i = m.end()
        depth = 1
        in_str = False
        buf = []
        while i < len(src) and depth > 0:
            ch = src[i]
            if in_str:
                buf.append(ch)
                if ch == '"' and (i == 0 or src[i - 1] != "\\"):
                    in_str = False
            elif ch == '"':
                in_str = True
                buf.append(ch)
            elif ch == "(":
                depth += 1
                buf.append(ch)
            elif ch == ")":
                depth -= 1
                if depth == 0:
                    break
                buf.append(ch)
            else:
                buf.append(ch)
            i += 1
        out[name] = "".join(buf)
    return out


# --- 1) ALL 里声明的令牌顺序 ---
start = text.index("val ALL: List<UiToken> = listOf(")
end = text.index("val byKey:", start)
block = text[start:end]
# 去掉行注释再按逗号切：ALL 里一行可能写多个名字
block_nc = re.sub(r"//[^\n]*", "", block)
# 只取 listOf( 之后的部分
inner = block_nc[block_nc.index("listOf(") + len("listOf("):]
names = []
for part in inner.split(","):
    part = part.strip()
    if not part:
        continue
    if re.fullmatch(r"[a-z][A-Za-z0-9]*", part):
        names.append(part)

decls = scan_decls(text)

ENUM_VALUES = {
    "tabStyle": "glass / plain / hidden",
    "navTitleAlign": "center / leading",
    "tabBarShape": "capsule / rounded / square",
    "tabBarIndicator": "capsule / underline / dot / none",
    "tabBarWidth": "compact / full",
    "uiKit": "auto / library / compose",
    "dockImpl": "liquidTabBar / liquidDock",
    "fontFamily": "system / sans / serif / mono / cursive",
    "cardStyle": "flat / glass / gradient / neu",
    "bubbleStyle": "rounded / square / tail",
    "avatarShape": "circle / rounded / square",
    "bgStyle": "aurora / flat / gradient / wallpaper",
}

TYPE_LABEL = {
    "TokenType.COLOR": "COLOR",
    "TokenType.NUMBER": "NUMBER",
    "TokenType.BOOL": "BOOL",
    "TokenType.ENUM": "ENUM",
    "TokenType.TEXT": "TEXT",
}

rows = []
for n in names:
    body = decls.get(n)
    if not body:
        continue
    args = split_args(body)
    # 去掉具名参数（note / min / max / unitLabel），它们不是位置参数
    positional = []
    named = {}
    for a in args:
        km = re.match(r"^(\w+)\s*=\s*(.*)$", a, re.S)
        if km:
            named[km.group(1)] = km.group(2).strip()
        else:
            positional.append(a)
    if len(positional) < 4:
        continue
    key = positional[0].strip('"')
    label = positional[1].strip('"')
    ttype = positional[2]
    default = positional[3].strip('"')
    lo = positional[4] if len(positional) > 4 else ""
    hi = positional[5] if len(positional) > 5 else ""
    tlabel = TYPE_LABEL.get(ttype, ttype.replace("TokenType.", ""))

    if tlabel == "ENUM":
        rng = ENUM_VALUES.get(key, "")
    elif tlabel == "NUMBER":
        inf = ("Double.NEGATIVE_INFINITY", "Double.POSITIVE_INFINITY")
        if lo and hi and lo not in inf and hi not in inf:
            def fmt(x):
                try:
                    f = float(x)
                except ValueError:
                    return x
                return str(int(f)) if f == int(f) else str(f)
            rng = "%s – %s" % (fmt(lo), fmt(hi))
        else:
            rng = "任意数值"
    elif tlabel == "COLOR":
        rng = "任意合法颜色"
    elif tlabel == "BOOL":
        rng = "true / false"
    else:
        rng = "文本（≤200 字符）"

    unit = named.get("unitLabel", "").strip('"')
    if unit:
        rng += "（单位 %s）" % unit

    note = named.get("note", "").strip('"')
    rows.append((key, label, tlabel, default, rng, note))

# ---------------------------------------------------------------------------
# 表格体（两份文档共用，只是表头措辞不同）

TYPE_CN = {"COLOR": "颜色", "NUMBER": "数字", "BOOL": "开关", "ENUM": "枚举", "TEXT": "文本"}


def gen_table(title, extra_note):
    t = []
    t.append(title % len(rows))
    t.append("> 本节由 `tools/gen_token_table.py` 从 `UiTheme.kt` 自动生成 —— 改了令牌记得重跑，")
    t.append("> 免得文档和代码对不上（照着过时文档写主题是最容易踩的坑）。\n")
    if extra_note:
        t.append(extra_note)
    t.append("| 键 | 中文 | 类型 | 默认值 | 范围 | 语义 |")
    t.append("| --- | --- | --- | --- | --- | --- |")
    for key, label, tlabel, default, rng, note in rows:
        sem = note or label
        t.append("| `%s` | %s | %s | `%s` | %s | %s |" % (
            key, label, TYPE_CN.get(tlabel, tlabel), default, rng, sem.replace("|", "\\|")))
    t.append("")
    return "\n".join(t)


def replace_section(path, start_marker, end_marker, new_text):
    doc = open(path, encoding="utf-8").read()
    a = doc.index(start_marker)
    b = doc.index(end_marker)
    doc = doc[:a] + new_text + "\n" + doc[b:]
    open(path, "w", encoding="utf-8").write(doc)
    return doc


# --- docs/PLUGIN.md ---
plugin_doc = os.path.join(DOCS, "PLUGIN.md")
t = []
t.append("### 8.3 全部可用令牌（%d 个，白名单）\n" % len(rows))
t.append("> 本节由 `tools/gen_token_table.py` 从 `UiTheme.kt` 自动生成 —— 改了令牌记得重跑，")
t.append("> 免得文档和代码对不上（照着过时文档写主题是最容易踩的坑）。\n")
t.append("**值统一写成字符串。** 名单之外的键不会被应用，但会被**报出来**（见 8.5）。\n")
t.append("| 令牌键 | 中文 | 类型 | 默认 | 范围 / 允许值 | 说明 |")
t.append("| --- | --- | --- | --- | --- | --- |")
for key, label, tlabel, default, rng, note in rows:
    t.append("| `%s` | %s | %s | `%s` | %s | %s |" % (
        key, label, tlabel, default, rng, note.replace("|", "\\|")))
t.append("")
t.append("**深色模式怎么写**：不要用 `xxxDark` 这些键，而是把深色值写进 `darkTokens`，")
t.append("键名用**浅色那个键**：\n")
t.append("```json")
t.append("{")
t.append('  "tokens":     { "accent": "#FF6B35", "groupedBackground": "#FDF9F6" },')
t.append('  "darkTokens": { "accent": "#FF8A5C", "groupedBackground": "#0A0A0C" }')
t.append("}")
t.append("```\n")

replace_section(plugin_doc, "### 8.3 全部可用令牌", "### 8.4 取值规则", "\n".join(t))
print("已更新 docs/PLUGIN.md：%d 个令牌" % len(rows))

# --- docs/UI-PLUGIN.md ---
ui_doc = os.path.join(DOCS, "UI-PLUGIN.md")
ui_table = gen_table(
    "### 4.1 全部 %d 个令牌（白名单）—— **全部已接线**\n",
    "> **\"已接线\"的含义**：写了这个键，屏幕上**一定**有地方会变。\n"
    "> 上一版有 9 个键写着\"已实现\"但没有任何消费端 —— 那种\"写了没反应\"\n"
    "> 现在不允许再出现：加令牌的人必须同时加消费端，否则不加。\n",
)
replace_section(ui_doc, "### 4.1 全部 ", "### 4.2 关于三个已删除的键", ui_table)
print("已更新 docs/UI-PLUGIN.md：%d 个令牌" % len(rows))
