import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";

const serial = process.env.ADB_SERIAL || "emulator-5554";
const out = path.resolve(".shots/ui.xml");

function adb(args) {
  return execFileSync("adb", ["-s", serial, ...args], { encoding: "utf8" });
}

function dump() {
  adb(["shell", "uiautomator", "dump", "/sdcard/ui.xml"]);
  adb(["pull", "/sdcard/ui.xml", out]);
  return fs.readFileSync(out, "utf8");
}

function decode(s) {
  return (s || "")
    .replaceAll("&quot;", '"')
    .replaceAll("&apos;", "'")
    .replaceAll("&lt;", "<")
    .replaceAll("&gt;", ">")
    .replaceAll("&amp;", "&");
}

function parse(xml) {
  const re = /<(\/?)node\b([^>]*?)(\/?)>/g;
  const root = { attrs: {}, parent: null, children: [] };
  const stack = [root];
  let m;
  while ((m = re.exec(xml))) {
    const closing = m[1] === "/";
    const selfClosing = m[3] === "/";
    const attrText = m[2] || "";
    if (closing) {
      if (stack.length > 1) stack.pop();
      continue;
    }
    const attrs = {};
    const are = /([\w-]+)="([^"]*)"/g;
    let a;
    while ((a = are.exec(attrText))) attrs[a[1]] = decode(a[2]);
    const node = { attrs, parent: stack[stack.length - 1], children: [] };
    node.parent.children.push(node);
    if (!selfClosing) stack.push(node);
  }
  return root;
}

function bounds(node) {
  const b = node?.attrs?.bounds || "";
  const m = b.match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/);
  if (!m) return null;
  return { left: +m[1], top: +m[2], right: +m[3], bottom: +m[4],
    cx: Math.round((+m[1] + +m[3]) / 2), cy: Math.round((+m[2] + +m[4]) / 2) };
}

function walk(node, fn) {
  for (const c of node.children) { fn(c); walk(c, fn); }
}

function label(node) {
  return (node.attrs["content-desc"] || "") + " " + (node.attrs.text || "");
}

function clickableAncestor(node) {
  let n = node;
  while (n && n.attrs) {
    if (n.attrs.clickable === "true") return n;
    n = n.parent;
  }
  return null;
}

const cmd = process.argv[2] || "list";
const query = (process.argv[3] || "").toLowerCase();
const xml = dump();
const root = parse(xml);
const all = [];
walk(root, (n) => all.push(n));

if (cmd === "list") {
  const seen = new Set();
  for (const n of all) {
    const t = (n.attrs.text || "").trim();
    if (t && !seen.has(t)) { seen.add(t); console.log(t); }
  }
} else if (cmd === "has") {
  const found = all.some((n) => label(n).toLowerCase().includes(query));
  console.log(found ? "yes" : "no");
  process.exit(found ? 0 : 1);
} else if (cmd === "tap") {
  const matches = all.filter((n) => label(n).toLowerCase().includes(query));
  if (!matches.length) {
    console.error("not found: " + process.argv[3]);
    process.exit(2);
  }
  const target = matches.map((n) => clickableAncestor(n) || n).find((n) => bounds(n));
  const b = bounds(target);
  if (!b) { console.error("no bounds"); process.exit(3); }
  console.log("tap " + (target.attrs["content-desc"] || target.attrs.text || process.argv[3]) + " @ " + b.cx + "," + b.cy);
  adb(["shell", "input", "tap", String(b.cx), String(b.cy)]);
} else if (cmd === "where") {
  const matches = all.filter((n) => label(n).toLowerCase().includes(query));
  for (const n of matches) {
    const b = bounds(n);
    console.log((label(n).trim() || "(no label)") + " clickable=" + n.attrs.clickable + " " + (b ? b.cx + "," + b.cy : ""));
  }
} else {
  console.error("usage: node ui.mjs <list|has|tap|where> [text]");
  process.exit(1);
}
