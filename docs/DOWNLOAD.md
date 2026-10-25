# 拉取第三方源码的网络实测记录

本文件记录**开发这台机器**上拉取 GitHub 源码时踩过的坑与当前的可用路径，
目的是下次换机器 / 换网络时能少试几次。

> 实测时间：2026-10 · 被测目标：`leejet/stable-diffusion.cpp`（master）

---

## 一、结论速查

> **2026-10 复测更新**：本节表格是**第一轮**的记录（当时测的是 `codeload` 归档，
> 且把带 `Range` 的 403 误判成"代理已死"，见第四节）。**第二轮**改用 GitHub
> **Release 资产**（44 MB 的 sherpa 包）复测，结论有变 —— 见下面第二张表。

### 第一轮（codeload 归档分支 tarball）

| 方式 | 结果 | 备注 |
| --- | --- | --- |
| `git clone` GitHub **直连** | ✅ **可用，首选** | 浅克隆 15.6 s / 252 MB |
| `codeload.github.com` **直连** | ✅ 可用，最快 | 实测 2.3 MB/s；见下方 ⚠️ Range 陷阱 |
| `gh-proxy.com` 转发 GitHub | ✅ **可用** | ~480 KB/s；但**忽略 Range**，不可续传 |
| `hk.gh-proxy.com` 转发 GitHub | ✅ 可用 | ~340 KB/s |
| `ghproxy.net` | ❌ 403 | |
| `ghfast.top` / `gh.llkk.cc` | ❌ 连接超时 | |
| 腾讯云 Gradle 镜像 | ✅ 可用 | 仅 Gradle 发行包，见 `BUILD.md` |

### 第二轮（GitHub Release 资产，16 个候选）

| 代理 | 结果 | 处置 |
| --- | --- | --- |
| `gh-proxy.com` | ✅ 200，**~750 KB/s** | 保留 |
| `hk.gh-proxy.com` | ✅ 200，~450 KB/s | 保留 |
| `down.npee.cn` | ✅ 200，~150 KB/s | **新增** |
| `gh-proxy.net` | ⚠️ 200，但只有 **9 KB/s** | 不采用 |
| `gitproxy.click` | ⚠️ 200，**7 KB/s** | 不采用 |
| `slink.ltd` | ⚠️ 200，**1 KB/s** | 不采用 |
| `ghproxy.net` | ❌ 连接超时 | **已从预设删除** |
| `ghfast.top` | ❌ 连接超时 | **已从预设删除** |
| `gh.llkk.cc` | ❌ 连接超时 | **已从预设删除** |
| `ghproxy.cc` | ❌ TLS 证书不受信 | 不采用 |
| `ghproxy.imxylz.dev` / `gh-proxy.top` / `ghp.ci` / `github.moeyy.xyz` / `gh.api.99988866.xyz` / `ghproxy.cfd` / `gh-proxy.ygxz.in` / `gh.jasonzeng.dev` / `proxy.yaoyaoyao.de` / `hub.gitmirror.com` / `gh-proxy.llyke.com` / `gh.ddlc.top` | ❌ 全部连接失败 | 不采用 |

**一句话：`gh-proxy.com` 是唯一又稳又快的，`hk.gh-proxy.com` 是可用备选。
`ProxySettings.PRESETS` 已按这张表收敛成 3 个。**

> ⚠️ **GitHub 直连在本机不稳定**：同一个 Release URL 有时 200、有时读超时。
> 所以 `:app:fetchSherpaOnnx` 和 `VoiceModelDownloader` 都实现成
> **先直连、失败自动换 `gh-proxy.com`**，而不是二选一写死。

> ⚠️ **这些代理对「模型下载」无效。** 实测把 `hf-mirror.com` 的模型 URL
> 套上 `gh-proxy.com` 前缀会得到 **404**（见第四节）。
> 模型走的是 hf-mirror 路径，不需要也不会受益于 GitHub 代理。

---

## 二、⚠️ codeload 的 Range 陷阱（最容易浪费时间的坑）

`codeload.github.com/<owner>/<repo>/tar.gz/refs/heads/<branch>` 这个归档端点
**会忽略 HTTP `Range` 请求头，总是返回 200 + 完整 tarball**。

这意味着：

```bash
# 以为只下 1KB 做连通性测试，实际下的是整个仓库
curl -sSL -r 0-1023 -o NUL -w "%{http_code} %{size_download}\n" \
  https://codeload.github.com/leejet/stable-diffusion.cpp/tar.gz/refs/heads/master
# → 200 124411457      ← 124 MB，不是 1024 字节
```

所以**不要**用 `-r` 去"试探"codeload。想做连通性检查，用：

```bash
curl -sS -o NUL -w "%{http_code}\n" -I \
  https://codeload.github.com/leejet/stable-diffusion.cpp/tar.gz/refs/heads/master
```

> 这个坑同样适用于 `gh-proxy.com`：实测带 `Range` 请求它返回 **200 + 整个文件**
> （124 MB），而不是 `206`。它和 codeload 行为一致，都会忽略 `Range`。

---

## 三、推荐命令

### 3.1 首选：直连浅克隆

```bash
git clone --depth 1 https://github.com/ggml-org/llama.cpp.git \
  third_party/llama.cpp
```

`llama.cpp` v0.6.0 的 tarball **自带 ggml**（不是子模块），所以拉完就能编。
`stable-diffusion.cpp` 则**依赖 ggml 子模块**，克隆后还要取：

```bash
cd third_party/stable-diffusion.cpp
git submodule update --init --depth 1 ggml
```

| 子模块 | 必需性 |
| --- | --- |
| `ggml`（`leejet/ggml`） | **必需**，缺了编译不过 |
| `thirdparty/libwebp` / `libwebm` | 仅视频相关功能需要，可跳过 |
| `examples/server/frontend` | 仅自带 web UI 需要，可跳过 |

### 3.2 兜底：codeload tarball

`git` 被 reset 时改用归档下载（注意上面第二节，它会下全量）：

```bash
# llama.cpp v0.6.0（实测 36 MB / 148 s）
curl -L --retry 3 -o llama.tar.gz \
  https://codeload.github.com/ggml-org/llama.cpp/tar.gz/refs/tags/v0.6.0
tar -xzf llama.tar.gz -C third_party
mv third_party/llama.cpp-0.6.0 third_party/llama.cpp   # 注意顶层目录名
```

tarball **不含** `.git`。`stable-diffusion.cpp` 的子模块要单独取：

```bash
curl -L -o ggml.tar.gz https://codeload.github.com/leejet/ggml/tar.gz/refs/heads/master
```

> 顶层目录名是 `<repo>-<tag>` 或 `<repo>-<branch>`，解压后要改名，
> 否则 `CMakeLists.txt` 里的路径检查会报 `llama.cpp sources not found`。

### 3.3 GitHub Release 资产（如 sherpa-onnx）

Release 下载走 `github.com/<owner>/<repo>/releases/download/...`，
**直连会随机超时**，加前缀最稳：

```bash
curl -L -o sherpa.tar.bz2 \
  https://gh-proxy.com/https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-v1.13.8-android.tar.bz2
```

项目里对应两个任务，都是"先直连、失败换代理"：
`:app:fetchSherpaOnnx`（原生库）与音色模型的 `VoiceModelDownloader`。

---

## 四、历史现象与判断

| 现象 | 当时判断 | 事后结论 |
| --- | --- | --- |
| `gh-proxy` 系列对 codeload 归档返回 403 | 代理已死 | **判断错误**：403 是**带 `Range` 的请求**触发的。 |
| `gh-proxy.com` 不带 Range | — | 实测 ✅ 200，~480 KB/s，**可用** |
| `git clone` 走代理 403 | 代理不支持 git 协议 | 未复测；直连可用即无需代理 |
| GitHub 直连 `git` 被 reset | 直连不可用 | **是临时的**；复测 `ls-remote` / `clone` 均成功 |

> **教训一**：`git reset` 类现象先重试再下结论。当时若重试一次，就不必绕道 codeload。
> **教训二**：测代理**不要一开始就带 `Range`**。`-r` 会同时踩到两个坑
> （codeload 忽略 Range + 部分代理对带 Range 的请求直接 403），
> 于是把「代理可用」误判成「代理已死」。先用无 Range 的 `GET` 或 `-I` 探活。

---

## 五、代理对「模型下载」无效（实测）

模型清单走 `hf-mirror.com`，而 GitHub 代理是路径前缀式的，套上去会 404：

| URL | 结果 |
| --- | --- |
| `hf-mirror.com/unsloth/Qwen3-0.6B-GGUF/resolve/main/...gguf` | ✅ 200，7.0 MB/s |
| `gh-proxy.com/` + 上面这条 | ❌ **404**（34824 B 错误页） |
| `hk.gh-proxy.com/` + 上面这条 | ❌ **404** |

**结论**：`ProxySettings` 里的预设只对 `third_party` 源码下载有意义。
模型下载走 hf-mirror 镜像链（见 [MODELS.md](MODELS.md)），
在 `preferProxy = true` 时只会白白多一次 404 往返再回退。

---

## 六、复测方法

换网络环境后，用这两条命令重新确认（都很快，不会下大文件）：

```bash
# 1) git 协议是否通（秒级）
git ls-remote --heads https://github.com/leejet/stable-diffusion.cpp.git

# 2) codeload 头信息是否通（用 -I，不要用 -r）
curl -sS -I -o NUL -w "%{http_code}\n" \
  https://codeload.github.com/leejet/stable-diffusion.cpp/tar.gz/refs/heads/master

# 3) 代理解活（不带 Range！）
curl -sS -L -o NUL --max-time 30 --max-filesize 2000000 \
  -w "%{http_code} %{size_download}\n" \
  https://gh-proxy.com/https://codeload.github.com/leejet/stable-diffusion.cpp/tar.gz/refs/heads/master

# 4) 模型源是否通（不带 Range）
curl -sS -L -o NUL --max-time 30 --max-filesize 2000000 \
  -w "%{http_code} %{speed_download}\n" \
  https://hf-mirror.com/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf
```

两条都 ✅ → 用第 3.1 节直连克隆。
`git` ❌ 而 codeload ✅ → 用第 3.2 节 tarball，并**记得手动补子模块**。

---

## 七、已就位的目录

```
third_party/
├── llama.cpp/                  # v0.6.0，自带 ggml（本机已在位，36 MB tarball）
├── llama.cpp.b4567/            # 旧版备份，回滚用（见 BUILD.md §7）
├── stable-diffusion.cpp/       # ❌ 未就位（生图用）
│   └── ggml/                   # 子模块，必须单独取
└── airllm-reference/           # 仅 LICENSE + README，出处凭据
```

> `third_party/llama.cpp/` 与 `app/src/main/jniLibs/*/` 下的原生库都在
> `.gitignore` 里 —— 它们是构建输入/产物，不进仓库。
