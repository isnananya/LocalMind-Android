package com.localmind.service

import android.content.Context
import com.localmind.data.MemoryPlanner
import com.localmind.native.image.ImageBridge
import com.localmind.native.llama.LlamaBridge

/**
 * In-app self test. This is the app's own smoke test: it exercises every native
 * boundary and the local HTTP API, and prints a readable report into Settings →
 * 诊断. It is what you run on a new device before blaming the model.
 */
object SelfTest {

    data class Check(
        val name: String,
        val ok: Boolean,
        val detail: String,
        val fatal: Boolean = false,
    )

    data class Report(
        val checks: List<Check>,
        val passed: Int,
        val failed: Int,
        val durationMs: Long,
    ) {
        val allCriticalPassed: Boolean get() = checks.none { it.fatal && !it.ok }
    }

    suspend fun run(context: Context, engine: LlamaEngine, apiServer: ApiServer): Report {
        val started = System.currentTimeMillis()
        val out = mutableListOf<Check>()

        fun check(name: String, fatal: Boolean = false, block: () -> Pair<Boolean, String>) {
            val result = runCatching { block() }.getOrElse { false to (it.message ?: it.javaClass.simpleName) }
            out += Check(name, result.first, result.second, fatal)
        }

        check("CPU 核心数", fatal = true) {
            val cores = MemoryPlanner.cpuCores()
            (cores >= 2) to "$cores 核"
        }

        check("系统内存", fatal = true) {
            val total = MemoryPlanner.totalRamBytes(context)
            val avail = MemoryPlanner.availableRamBytes(context)
            val gb = total / (1024.0 * 1024 * 1024)
            (gb >= 2.0) to String.format(
                java.util.Locale.US, "共 %.1f GB / 可用 %.1f GB", gb, avail / (1024.0 * 1024 * 1024)
            )
        }

        check("llama.cpp 原生库", fatal = true) {
            val info = LlamaBridge.systemInfoSafe()
            (info.isNotBlank()) to info.take(180)
        }

        check("原生引擎句柄", fatal = true) {
            val handle = LlamaBridge.nativeInit()
            val ok = handle != 0L
            if (ok) LlamaBridge.nativeFree(handle)
            ok to if (ok) "nativeInit() 正常" else "nativeInit() 返回 0"
        }

        check("图像渲染器", fatal = false) {
            // ⚠️ 边长用 64，不能用 32。
            //
            // `ImageBridge.render` 会把边长夹到 `coerceIn(64, 2048)`，所以传 32 拿到的是
            // 64×64；而这里又断言 `bmp.width == size` —— 尺寸必然对不上，
            // 于是这一项**永远是红的**，看起来像"图像渲染器坏了"，其实渲染一直正常
            //（`/v1/images/generations` 一直能出图）。
            val size = 64
            val ok = runCatching {
                val bmp = ImageBridge.render("selftest green forest", size, size, 1234, 0)
                val good = bmp.width == size && bmp.height == size
                bmp.recycle()
                good
            }.getOrDefault(false)
            ok to if (ok) "已渲染 ${size}x$size" else "渲染失败"
        }

        // ---- 生图链路：后端 + checkpoint，两样缺一不可 ------------------
        //
        // 这个检查是"生图不能用"这个反馈的正面回答，所以它必须说出**缺哪一环**：
        // 只说"后端已加载，可用"是不够的 —— 没有 checkpoint 时照样出不了真图，
        // 用户会以为模型白下了。
        check("生图链路", fatal = false) {
            val has = ImageBridge.hasDiffusionBackend
            val onDisk = com.localmind.service.NativeLibraryInstaller.isInstalled(
                com.localmind.data.Storage(context),
            )
            val ckpt = runCatching {
                com.localmind.service.DiffusionModels.resolve(
                    java.io.File(context.filesDir, "models"), "",
                )
            }.getOrNull()
            val backend = when {
                has -> "后端已加载"
                onDisk -> "后端已安装未加载（到「生图」页点「启用」）"
                else -> "后端未安装（14 MB 插件，见「生图」页）"
            }
            val model = if (ckpt == null) "没找到扩散模型" else "模型 " + ckpt.name
            // 两项都齐才算能真出图。缺任一项都判 false —— 用户点"生图"的期望
            // 就是拿到模型画的图，兜底渲染出来的渐变图不算"能用"。
            (has && ckpt != null) to (backend + " · " + model)
        }

        check("识图（多模态）", fatal = false) {
            // 三段式：build 有没有编进去 / 当前模型能不能看 / 差什么。
            // 「差什么」是最有用的一句 —— 用户下的多模态模型如果缺 mmproj，
            // 只写「不支持」他会去反复换模型。
            val runtime = engine.state.value.visionReady
            true to when {
                !com.localmind.ui.AppViewModel.CAN_SEE_IMAGES ->
                    "这个包没有编进识图能力（省体积的版本）"
                !com.localmind.ui.AppViewModel.VISION_USABLE ->
                    "链路已接好但还没打通：mtmd_tokenize 在图像预处理里失败" +
                        "（llama.cpp 的 llava-uhd 网格计算，见 HANDOFF §16.4）—— " +
                        "所以现在发图仍然按纯文字回答，开关是灰的"
                runtime -> "可以看图（当前模型：" + (engine.state.value.modelName ?: "?") + " + mmproj）"
                !engine.state.value.ready ->
                    "还没加载模型；加载一个多模态模型（带 mmproj 的那种）就能看图"
                else ->
                    "当前模型看不见图：要么它不是多模态模型，要么它的 mmproj " +
                        "（视觉投影，和主模型分开下载）没下全"
            }
        }

        check("扩散模型后端", fatal = false) {
            val has = ImageBridge.hasDiffusionBackend
            // 后端不再是"这个 build 有没有链进去"，而是**运行时下载的独立 .so**
            // （见 NativeLibraryInstaller）。所以这里要区分两种"没有"：
            // 文件没装 vs 装了但没加载进来 —— 后者的处置完全不同（点一次"启用"）。
            val onDisk = com.localmind.service.NativeLibraryInstaller.isInstalled(
                com.localmind.data.Storage(context),
            )
            true to when {
                has -> "已加载，可用"
                onDisk -> "文件已安装但未加载 —— 到「生图」页点「启用」"
                else -> "未安装（当前用内置程序化渲染器，见 docs/IMAGE_ENGINE.md）"
            }
        }

        check("本地 API 端口", fatal = false) {
            val state = apiServer.state.value
            if (state.running) true to "监听 ${state.port}"
            else true to "未开启（可在 API 页面启动）"
        }

        check("模型已加载", fatal = false) {
            val s = engine.state.value
            if (s.ready) true to "${s.modelName} · ctx ${s.contextSize} · ${s.threads} 线程"
            else false to "尚未加载模型（属于正常状态，先去模型页下载并加载）"
        }

        // ---- 设备感知（人设聊天的电量/位置/天气）----
        //
        // 这两项**永远返回 ok**：它们是"这台机器能不能用这个能力"的说明，
        // 不是"坏了"。没权限、定位关着都是正常状态（默认就是关的），
        // 标成红色只会让人以为应用坏了。真正的读取结果在测试模式的
        // 「设备感知」里一键读，那里才需要判断成没成。
        check("电量读取", fatal = false) {
            val b = DeviceSense.battery(context)
            true to if (b == null) {
                "读不到（有些机型/模拟器不报电量）"
            } else {
                buildString {
                    append(b.percent).append("%")
                    append(if (b.charging) " · 充电中" else " · 未充电")
                    if (b.source.isNotBlank()) append("（").append(b.source).append("）")
                    if (b.powerSave) append(" · 省电模式")
                    b.temperatureC?.let { append(" · ").append(it).append("℃") }
                }
            }
        }

        check("定位与天气", fatal = false) {
            val perm = DeviceSense.hasLocationPermission(context)
            val on = DeviceSense.locationEnabled(context)
            // 反向地理编码要看**上次实际结果**，不看 Geocoder.isPresent()：
            // 那个在本机和不少国产 ROM 上都是"说有、真用就超时"。
            val geo = DeviceSense.lastGeocodeOk
            true to when {
                !perm -> "没有定位权限 —— 人设聊天里的「知道你在哪 / 看天气」用不了"
                !on -> "系统定位是关的 —— 去手机设置的定位里打开"
                geo == false -> "权限与开关就绪；但这台机器**反向地理编码实际失败**，" +
                    "地名会退回「时区推断」（例如「上海一带」），天气仍然正常"
                geo == null -> "权限与开关就绪（还没实际解析过地名，" +
                    "在「设备感知」里读一次就知道能不能解析）"
                else -> "权限与系统开关都就绪，上次地名解析成功"
            }
        }

        check("存储空间", fatal = false) {
            val free = MemoryPlanner.availableStorageBytes(context.filesDir)
            (free > 200L * 1024 * 1024) to "可用 ${com.localmind.util.Formatters.size(free)}"
        }

        val passed = out.count { it.ok }
        return Report(out, passed, out.size - passed, System.currentTimeMillis() - started)
    }
}
