#!/usr/bin/env node
/**
 * 把 plugins-src/<pack> 打成 app/src/main/assets/builtin/<asset>.zip。
 *
 * ## 为什么必须有这个脚本
 *
 * APK 里那几个内置插件（模型库 / 液态底栏 / Breeze 连接配置）是**zip 快照**，
 * 不是运行时从 plugins-src 读的。于是改完 plugins-src 里的 JSON 忘了重新打包，
 * 应用里跑的仍然是旧数据 —— 而且因为「模型包按 id 覆盖内置清单」，
 * 旧包会把新加的字段整条盖掉（比如识图条目的 mmproj 地址），症状是
 * 「源码里明明写了，装上去还是没有」。
 *
 * 所以：**改完 plugins-src 就跑一次这个脚本**，然后提交 zip。
 *
 * ## 用法
 *
 *     node tools/build_builtin_packs.mjs            # 默认重打 model-library
 *     node tools/build_builtin_packs.mjs all        # 把所有能重打的都重打
 *     node tools/build_builtin_packs.mjs glass-bar-module
 *
 * ## 为什么 sd-backend 不在这里
 *
 * 它的 zip 里装的是 30 MB 的编译产物（.so），plugins-src/sd-backend 只有元数据。
 * 从源码目录重打会得到一个没有后端的空包 —— 那种"打包成功但功能消失"的坑
 * 不能留给下一个人。它的产物由 :app:assembleSdPlugin 负责。
 *
 * ## 为什么自己拼 zip
 *
 * 工程里没有 zip 依赖，也不想为了一个打包脚本引入第三方包。只用 zlib 的
 * deflateRaw 就够：store/deflate + 中央目录，三十行的事。
 */
import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";

const ROOT = path.resolve(import.meta.dirname, "..");
const SRC = path.join(ROOT, "plugins-src");
const OUT = path.join(ROOT, "app/src/main/assets/builtin");

/** pack 目录名 -> 产物文件名。没列出来的一律不碰。 */
const PACKS = {
    "model-library": "official-model-library.zip",
    "glass-bar-module": "glass-bar-module.zip",
    "breeze-tts2": "breeze-tts2.zip",
};

const SKIPPED = {
    "sd-backend": "zip 里要放编译好的 .so，只能由 :app:assembleSdPlugin 产出",
};

// ------------------------------------------------------------------ zip 写入

const CRC_TABLE = (() => {
    const t = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
        let c = n;
        for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
        t[n] = c;
    }
    return t;
})();

function crc32(buf) {
    let c = -1;
    for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
    return (c ^ -1) >>> 0;
}

/** 固定时间戳的 DOS 时间（1980-01-01 00:00），保证同样的输入产出同样的字节。 */
const DOS_TIME = 0;
const DOS_DATE = (1 << 5) | 1;   // 1980-01-01

/**
 * @param {{name: string, data: Buffer}[]} entries 路径用 / 分隔，都在根目录下
 * @returns {Buffer}
 */
function makeZip(entries) {
    const locals = [];
    const centrals = [];
    let offset = 0;

    for (const e of entries) {
        const name = Buffer.from(e.name, "utf8");
        const crc = crc32(e.data);
        const deflated = zlib.deflateRawSync(e.data, { level: 9 });
        // 压不小就别压：小 JSON 加 2 字节头反而更大。
        const useDeflate = deflated.length < e.data.length;
        const body = useDeflate ? deflated : e.data;
        const method = useDeflate ? 8 : 0;

        const local = Buffer.alloc(30);
        local.writeUInt32LE(0x04034b50, 0);
        local.writeUInt16LE(20, 4);        // version needed
        local.writeUInt16LE(0x0800, 6);    // flag: UTF-8 名字
        local.writeUInt16LE(method, 8);
        local.writeUInt16LE(DOS_TIME, 10);
        local.writeUInt16LE(DOS_DATE, 12);
        local.writeUInt32LE(crc, 14);
        local.writeUInt32LE(body.length, 18);
        local.writeUInt32LE(e.data.length, 22);
        local.writeUInt16LE(name.length, 26);
        local.writeUInt16LE(0, 28);
        locals.push(local, name, body);

        const central = Buffer.alloc(46);
        central.writeUInt32LE(0x02014b50, 0);
        central.writeUInt16LE(20, 4);      // version made by
        central.writeUInt16LE(20, 6);      // version needed
        central.writeUInt16LE(0x0800, 8);
        central.writeUInt16LE(method, 10);
        central.writeUInt16LE(DOS_TIME, 12);
        central.writeUInt16LE(DOS_DATE, 14);
        central.writeUInt32LE(crc, 16);
        central.writeUInt32LE(body.length, 20);
        central.writeUInt32LE(e.data.length, 24);
        central.writeUInt16LE(name.length, 28);
        central.writeUInt16LE(0, 30);      // extra
        central.writeUInt16LE(0, 32);      // comment
        central.writeUInt16LE(0, 34);      // disk
        central.writeUInt16LE(0, 36);      // internal attrs
        central.writeUInt32LE(0, 38);      // external attrs
        central.writeUInt32LE(offset, 42);
        centrals.push(central, name);

        offset += local.length + name.length + body.length;
    }

    const centralBytes = Buffer.concat(centrals);
    const end = Buffer.alloc(22);
    end.writeUInt32LE(0x06054b50, 0);
    end.writeUInt16LE(0, 4);
    end.writeUInt16LE(0, 6);
    end.writeUInt16LE(entries.length, 8);
    end.writeUInt16LE(entries.length, 10);
    end.writeUInt32LE(centralBytes.length, 12);
    end.writeUInt32LE(offset, 16);
    end.writeUInt16LE(0, 20);

    return Buffer.concat([...locals, centralBytes, end]);
}

// ------------------------------------------------------------------ 打包

function listFiles(dir, acc = []) {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true }).sort((a, b) =>
        a.name < b.name ? -1 : a.name > b.name ? 1 : 0
    )) {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) listFiles(full, acc);
        else acc.push(full);
    }
    return acc;
}

function build(packDir) {
    const assetName = PACKS[packDir];
    if (!assetName) {
        if (SKIPPED[packDir]) {
            console.log("skip " + packDir + "：" + SKIPPED[packDir]);
            return false;
        }
        throw new Error("未知的 pack：" + packDir + "（先在 PACKS 里登记）");
    }
    const dir = path.join(SRC, packDir);
    if (!fs.existsSync(dir)) throw new Error("没有这个目录：" + dir);

    // zip 里的路径必须是相对 pack 根目录的（plugin.json 在根上），否则导入器找不到清单。
    const entries = listFiles(dir).map((f) => ({
        name: path.relative(dir, f).split(path.sep).join("/"),
        data: fs.readFileSync(f),
    }));
    if (!entries.some((e) => e.name === "plugin.json")) {
        throw new Error(packDir + " 缺 plugin.json，打出来的包装不上");
    }

    const zip = makeZip(entries);
    const target = path.join(OUT, assetName);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, zip);
    console.log(
        "ok   " + assetName + "  " + entries.length + " 个文件 · " +
        (zip.length / 1024).toFixed(1) + " KB"
    );
    return true;
}

const args = process.argv.slice(2);
const targets = args.length === 0
    ? ["model-library"]
    : args[0] === "all" ? [...Object.keys(PACKS), ...Object.keys(SKIPPED)] : args;

let built = 0;
for (const t of targets) if (build(t)) built++;
console.log("\n完成：" + built + " 个包已重打 → " + path.relative(ROOT, OUT));
