"""造一个**合成的 GGUF 头**，用来验证判据的两条分支。

为什么需要它：手边只有文本模型的真文件（Qwen3），而扩散 checkpoint 是
2 GB 级的，为了验证一个 if 分支去下 2 GB 不值当。

这里按 GGUF v3 规范写出**合法的头 + KV 段**（不带张量数据），
键名用真实 stable-diffusion.cpp checkpoint 的命名，所以走的是和真文件
完全一样的解析路径 —— 不是 mock，是一个小的真文件。

用法：
    python make_fake_gguf.py diffusion out_diffusion.gguf
    python make_fake_gguf.py text      out_text.gguf
    python make_fake_gguf.py garbage   out_garbage.gguf
"""

import struct
import sys

T_STRING = 8
T_UINT32 = 4
T_FLOAT32 = 6
T_BOOL = 7


def kv_string(key: str, value: str) -> bytes:
    return (
        struct.pack("<Q", len(key)) + key.encode()
        + struct.pack("<I", T_STRING)
        + struct.pack("<Q", len(value)) + value.encode()
    )


def kv_u32(key: str, value: int) -> bytes:
    return struct.pack("<Q", len(key)) + key.encode() + struct.pack("<I", T_UINT32) + struct.pack("<I", value)


def kv_f32(key: str, value: float) -> bytes:
    return struct.pack("<Q", len(key)) + key.encode() + struct.pack("<I", T_FLOAT32) + struct.pack("<f", value)


def kv_bool(key: str, value: bool) -> bytes:
    return struct.pack("<Q", len(key)) + key.encode() + struct.pack("<I", T_BOOL) + struct.pack("<B", 1 if value else 0)


def build(entries: list[bytes], tensor_count: int = 0) -> bytes:
    """entries 是已经编码好的 KV 字节块（按顺序拼即可）。"""
    head = b"GGUF" + struct.pack("<I", 3) + struct.pack("<Q", tensor_count) + struct.pack("<Q", len(entries))
    return head + b"".join(entries)


# 扩散 checkpoint：stable-diffusion.cpp 真实导出里的键名
DIFFUSION_KVS = [
    kv_string("general.architecture", "stable-diffusion"),
    kv_string("general.name", "dreamshaper-8"),
    kv_u32("sd.version", 2),
    kv_string("sd.unet_config", "{}"),
    kv_f32("vae.scale_factor", 0.18215),
    kv_u32("vae.embed_dim", 4),
    kv_u32("clip_l.hidden_size", 768),
    kv_u32("clip_g.hidden_size", 1280),
    kv_u32("unet.in_channels", 4),
    kv_string("model.diffusion_model.name", "unet"),
    kv_string("cond_stage_model.kind", "clip"),
]

# 文本模型：Qwen3 的真实键名（从设备上那个文件抄来的前几个）
TEXT_KVS = [
    kv_string("general.architecture", "qwen3"),
    kv_string("general.type", "model"),
    kv_string("general.name", "Qwen3 0.6B"),
    kv_string("general.basename", "Qwen3"),
    kv_string("general.quantized_by", "unsloth"),
    kv_u32("general.size_label", 1),
    kv_u32("qwen3.block_count", 28),
    kv_u32("qwen3.embedding_length", 1024),
    kv_bool("qwen3.attention.causal", True),
]

# 一个带嵌套数组的，验证 skipValue 的数组分支不会跑飞
NESTED_KVS = [
    kv_string("general.architecture", "llama"),
    # tokenizer.ggml.tokens: 数组(字符串) —— 手工构造
    struct.pack("<Q", len("tokenizer.ggml.tokens")) + b"tokenizer.ggml.tokens"
    + struct.pack("<I", 9)                       # T_ARRAY
    + struct.pack("<I", T_STRING)                # 元素类型
    + struct.pack("<Q", 3)                       # 3 个元素
    + b"".join(struct.pack("<Q", len(t)) + t.encode() for t in ("a", "bb", "ccc")),
    kv_u32("llama.block_count", 32),
]


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 1
    kind, out = sys.argv[1], sys.argv[2]

    if kind == "diffusion":
        data = build(DIFFUSION_KVS, tensor_count=1200)
    elif kind == "text":
        data = build(TEXT_KVS, tensor_count=310)
    elif kind == "nested":
        data = build(NESTED_KVS)
    elif kind == "garbage":
        data = b"NOTGGUF" + b"\x00" * 500
    elif kind == "empty":
        data = b"GGUF" + struct.pack("<I", 3) + struct.pack("<Q", 0) + struct.pack("<Q", 0)
    else:
        print(f"未知类型：{kind}")
        return 1

    with open(out, "wb") as f:
        f.write(data)
    print(f"写出 {out}  ({len(data)} 字节, 类型={kind})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
