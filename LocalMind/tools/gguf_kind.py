"""独立的 GGUF 头校验器。

用途：验证 Kotlin 侧 DiffusionModels 的判据是否正确 ——
它必须能把「文本模型」和「扩散 checkpoint」区分开。

这里用 Python 独立实现一遍，是为了不拿被测代码验证被测代码：
两边各写一次，结果一致才有意义。

用法：
    python gguf_kind.py <file.gguf> [...]

输出每个文件的 magic / version / KV 键，以及判定结果。
"""

import struct
import sys

T_UINT8, T_INT8, T_UINT16, T_INT16 = 0, 1, 2, 3
T_UINT32, T_INT32, T_FLOAT32, T_BOOL = 4, 5, 6, 7
T_STRING, T_ARRAY, T_UINT64, T_INT64, T_FLOAT64 = 8, 9, 10, 11, 12

SIZES = {
    T_UINT8: 1, T_INT8: 1, T_BOOL: 1,
    T_UINT16: 2, T_INT16: 2,
    T_UINT32: 4, T_INT32: 4, T_FLOAT32: 4,
    T_UINT64: 8, T_INT64: 8, T_FLOAT64: 8,
}


def is_diffusion_key(key: str) -> bool:
    """必须和 Kotlin 侧的 DiffusionModels.isDiffusionKey 完全一致。"""
    return (
        key.startswith("vae.")
        or key.startswith("cond_stage_model")
        or key.startswith("model.diffusion_model")
        or key.startswith("clip_l.")
        or key.startswith("clip_g.")
        or key.startswith("t5xxl.")
        or key.startswith("unet.")
        or key == "sd.version"
        or key.startswith("sd.")
    )


class Reader:
    def __init__(self, data: bytes):
        self.d = data
        self.p = 0

    def take(self, n: int) -> bytes:
        if self.p + n > len(self.d):
            raise EOFError(f"需要 {n} 字节但只剩 {len(self.d) - self.p}")
        b = self.d[self.p:self.p + n]
        self.p += n
        return b

    def u32(self) -> int:
        return struct.unpack("<I", self.take(4))[0]

    def u64(self) -> int:
        return struct.unpack("<Q", self.take(8))[0]

    def string(self) -> str:
        n = self.u64()
        if n > 65535:
            raise ValueError(f"字符串长度离谱：{n}")
        return self.take(n).decode("utf-8", errors="replace")

    def skip(self, t: int):
        if t == T_STRING:
            self.string()
        elif t == T_ARRAY:
            et = self.u32()
            n = self.u64()
            if n > 1_000_000:
                raise ValueError(f"数组长度离谱：{n}")
            for _ in range(n):
                self.skip(et)
        elif t in SIZES:
            self.take(SIZES[t])
        else:
            raise ValueError(f"未知类型 {t}")


def inspect(path: str):
    with open(path, "rb") as f:
        data = f.read(4 * 1024 * 1024)   # 和 Kotlin 侧一样的上限

    r = Reader(data)
    magic = r.take(4).decode("ascii", errors="replace")
    if magic != "GGUF":
        return magic, None, [], "UNKNOWN(不是 GGUF)", 0, 0, ""

    # GGUF 头：magic[4] + version(u32) + tensor_count(u64) + kv_count(u64)
    # 两个计数都是 **u64**。按 u32 读会让后面所有偏移错位，
    # 而且不会报错 —— 只是读出一堆乱码键名，判据恒为 UNKNOWN。
    version = r.u32()
    tensor_count = r.u64()
    kv_count = r.u64()

    keys = []
    saw_diffusion = False
    saw_text_arch = False
    arch = ""

    for _ in range(min(kv_count, 4096)):
        try:
            k = r.string()
        except (EOFError, ValueError, struct.error):
            break
        keys.append(k)
        if k == "general.architecture":
            saw_text_arch = True
        if is_diffusion_key(k):
            saw_diffusion = True
        try:
            r.skip(r.u32())
        except Exception:
            break

    # 取 architecture 的值（需要重扫一遍，这里简化：从 keys 里找）
    for i, k in enumerate(keys):
        if k == "general.architecture":
            arch = "(present)"
            break

    if saw_diffusion:
        kind = "DIFFUSION"
    elif saw_text_arch:
        kind = "TEXT"
    else:
        kind = "UNKNOWN"

    return magic, version, keys, kind, tensor_count, kv_count, arch


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    for path in sys.argv[1:]:
        try:
            res = inspect(path)
        except Exception as e:
            print(f"{path}\n  ERROR: {e}\n")
            continue
        magic, version, keys, kind, tc, kv, arch = res
        print(f"{path}")
        print(f"  magic={magic} version={version} tensors={tc} kv={kv}")
        print(f"  general.architecture present: {arch or 'NO'}")
        interesting = [k for k in keys if is_diffusion_key(k) or k.startswith("general.")][:12]
        print(f"  前几个相关键: {interesting}")
        print(f"  ==> 判定: {kind}")
        print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
