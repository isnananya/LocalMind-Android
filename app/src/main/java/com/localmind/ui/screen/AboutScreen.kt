package com.localmind.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import com.localmind.BuildConfig
import com.localmind.ui.components.IosNavigationBar
import com.localmind.ui.components.IosSection
import com.localmind.ui.components.statusBarTopPadding
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.theme.IosTheme

/**
 * 关于：作者、捐献感谢榜、开源声明。
 *
 * 单独一页而不是塞进「我的」的滚动流里，因为开源声明会越来越长
 * （每引入一个第三方组件就要加一条），塞进主页会把其它设置挤下去。
 */
@Composable
fun AboutScreen(onBack: () -> Unit, onOpenDebug: () -> Unit = {}) {
    // 版本号连点三下进测试模式。
    //
    // 用**时间窗**而不是单纯计数：慢慢点三下不该进去（用户可能只是手抖点了两下
    // 又点了一下）。900ms 是经验值 —— 比双击的判定宽，比"随手点"窄。
    var taps by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var lastTapAt by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    val ios = IosTheme.palette

    Column(
        Modifier
            .fillMaxSize()
            .background(ios.groupedBackground)
    ) {
        IosNavigationBar(
            title = "关于",
            collapsed = true,
            topInset = statusBarTopPadding(),
            leading = {
                Icon(
                    AppIcons.ArrowBack,
                    contentDescription = "返回",
                    tint = ios.blue,
                    modifier = Modifier
                        .size(23.dp)
                        .clickable(onClick = onBack),
                )
            },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
        ) {
            // ------------------------------------------------------ 作者
            item {
                IosSection(header = "作者") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AvatarOrIcon(
                            base64 = com.localmind.ui.theme.BuiltInAvatars.AUTHOR,
                            size = 52.dp,
                            fallbackTint = ios.blue,
                            fallbackAlpha = 0.16f,
                            fallbackIconSize = 26.dp,
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("难安", style = IosTheme.typography.title3, color = ios.label)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "LocalMind 的作者与维护者",
                                style = IosTheme.typography.caption1,
                                color = ios.secondaryLabel,
                            )
                        }
                    }
                    Divider(ios.separator)
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            "这是一个完全离线、不需要任何服务器就能在手机上跑大模型的项目。" +
                                "如果它对你有点用，那就是它存在的意义。",
                            style = IosTheme.typography.footnote,
                            color = ios.secondaryLabel,
                        )
                    }
                }
            }

            // -------------------------------------------------- 支持作者
            //
            // 赞赏码是作者自己的收款码，图片自带说明文字，所以这里不再解释"这是什么"。
            // 点一下放大，方便对方用另一台设备扫。深色模式下垫一层白底：这张图本身
            // 是白底海报，直接贴在黑背景上会变成一块刺眼的白方块。
            item {
                var showQr by remember { mutableStateOf(false) }
                IosSection(header = "支持作者", footer = "扫码赞赏，金额随意。") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource(com.localmind.R.drawable.author_reward),
                            contentDescription = "作者的赞赏码",
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                            modifier = Modifier
                                .width(300.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(androidx.compose.ui.graphics.Color.White)
                                .clickable { showQr = true },
                        )
                    }
                }
                if (showQr) {
                    androidx.compose.ui.window.Dialog(onDismissRequest = { showQr = false }) {
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource(com.localmind.R.drawable.author_reward),
                            contentDescription = "作者的赞赏码",
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .clickable { showQr = false },
                        )
                    }
                }
            }

            // -------------------------------------------------- 版本信息
            item {
                IosSection(header = "版本") {
                    InfoLine("应用", "LocalMind")
                    Divider(ios.separator)
                    // 只显示版本名。**不要把 versionCode 拼在后面** ——
                    //
                    // 那是给 Android 比大小的一个整数（每次出包 +1），用户看到
                    // 「1.0.3(内测) (4)」只会问「这个 4 是什么」。它仍然写在导出的
                    // 诊断报告里（Diagnostics，排查时要它区分同名构建），但不该出现在
                    // 用户读的版本号里。
                    InfoLine(
                        "版本",
                        BuildConfig.VERSION_NAME,
                    ) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        taps = if (now - lastTapAt > 900L) 1 else taps + 1
                        lastTapAt = now
                        if (taps >= 3) {
                            taps = 0
                            onOpenDebug()
                        }
                    }
                    Divider(ios.separator)
                    InfoLine("推理内核", "llama.cpp v0.6.0")
                }
            }

            // -------------------------------------------------- 应用图标捐赠
            //
            // 和下面的「捐献感谢榜」分开：一个出钱、一个出画，是两件不同的事。
            // 只有一行署名，不加任何说明文字 —— 她捐的是图标，记的是这件事本身。
            item {
                IosSection(header = "应用图标捐赠") {
                    ContributorRow(
                        name = "小柠",
                        role = "捐赠人",
                        avatar = com.localmind.R.drawable.credit_artist_avatar,
                        tint = ios.orange,
                    )
                }
            }

            // -------------------------------------------------- 捐献感谢榜
            item {
                IosSection(
                    header = "捐献感谢榜",
                    footer = "感谢每一位愿意支持这个项目的人。金额不分大小，" +
                        "每一份都是让项目继续走下去的理由。",
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AppIcons.Favorite, null, tint = ios.pink, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "愿意留名的支持者会列在这里",
                            style = IosTheme.typography.footnote,
                            color = ios.secondaryLabel,
                        )
                    }
                    Divider(ios.separator)
                    DonorRow(
                        name = "沉璧",
                        amount = "20 元",
                        base64 = com.localmind.ui.theme.BuiltInAvatars.DONOR_CHENBI,
                    )
                    Divider(ios.separator)
                    DonorRow(
                        name = "韪嘆",
                        amount = "15 元",
                        base64 = com.localmind.ui.theme.BuiltInAvatars.DONOR_WEITAN,
                    )
                }
            }

            // -------------------------------------------------- 开源声明
            item {
                IosSection(
                    header = "开源声明",
                    footer = "本项目自身代码为 MIT；下列组件各自遵循其原始许可，" +
                        "完整文本见各自仓库。APK 与运行时下载的原生库里包含它们。",
                ) {
                    LicenseRow(
                        name = "LocalMind",
                        license = "MIT",
                        note = "本项目自身代码",
                    )
                    Divider(ios.separator)
                    LicenseRow(
                        name = "llama.cpp",
                        license = "MIT",
                        note = "本地文本推理内核（ggml-org/llama.cpp）",
                    )
                    Divider(ios.separator)
                    LicenseRow(
                        name = "stable-diffusion.cpp",
                        license = "MIT",
                        note = "本地扩散生图后端（leejet/stable-diffusion.cpp）",
                    )
                    Divider(ios.separator)
                    LicenseRow(
                        name = "ggml",
                        license = "MIT",
                        note = "张量计算库（ggml-org/ggml）",
                    )
                    Divider(ios.separator)
                    // 这一条是**随 arm64 构建一起进来的**：llama.cpp 的 ARM 微内核后端。
                    // 它的许可证与 MIT 不同（Apache-2.0 + BSD-3-Clause，署名 Arm），
                    // 所以必须单独点名，不能并进上面那条 ggml。
                    LicenseRow(
                        name = "KleidiAI（Arm）",
                        license = "Apache-2.0 / BSD-3-Clause",
                        note = "arm64 版里的 ARM i8mm / dotprod / SME 微内核" +
                            "（ARM-software/kleidiai，经 llama.cpp 的 GGML_CPU_KLEIDIAI 编入）。" +
                            "Copyright 2024-2026 Arm Limited and/or its affiliates" +
                            " <open-source-office@arm.com>。完整文本见其仓库 LICENSES/ 目录",
                    )
                    Divider(ios.separator)
                    LicenseRow(
                        name = "libc++（Android NDK）",
                        license = "Apache-2.0",
                        note = "随 NDK 发布的 C++ 运行库（libc++_shared.so），" +
                            "Copyright (c) 2015, The Android Open Source Project",
                    )
                    Divider(ios.separator)
                    LicenseRow(
                        name = "sherpa-onnx",
                        license = "Apache-2.0",
                        note = "本地神经网络语音合成（k2-fsa/sherpa-onnx）",
                    )
                    Divider(ios.separator)
                    // 语音引擎那 30 MB 里 onnxruntime 一家占 21–25 MB，是最该点名的一个。
                    LicenseRow(
                        name = "ONNX Runtime",
                        license = "MIT",
                        note = "语音引擎的推理运行时（microsoft/onnxruntime，随 sherpa-onnx 一起下载）",
                    )
                    Divider(ios.separator)
                    LicenseRow(
                        name = "AirLLM",
                        license = "Apache-2.0",
                        note = "「先算预算、再按层取权重」的内存规划思路参考" +
                            "（lyogavin/airllm）",
                    )
                    Divider(ios.separator)
                    LicenseRow(
                        name = "Jetpack Compose / AndroidX",
                        license = "Apache-2.0",
                        note = "界面框架",
                    )
                    Divider(ios.separator)
                    LicenseRow(
                        name = "OkHttp / Okio",
                        license = "Apache-2.0",
                        note = "HTTP 下载与本地 API 服务",
                    )
                    Divider(ios.separator)
                    LicenseRow(
                        name = "Kotlin / kotlinx",
                        license = "Apache-2.0",
                        note = "语言标准库、协程与序列化（coroutines / serialization）",
                    )
                    Divider(ios.separator)
                    // ⚠️ 这一条不是"用到了人家的库"，而是**许可要求署名**：
                    // Open-Meteo 的免费接口按 CC BY 4.0 提供数据，署名是使用条件。
                    // 删掉这一行 = 违约，不是"少写一行"。
                    LicenseRow(
                        name = "Open-Meteo",
                        license = "CC BY 4.0",
                        note = "人设聊天的天气数据（open-meteo.com）—— 按许可要求在此署名",
                    )
                }
            }

            // -------------------------------------------------- 第三方服务
            //
            // 这一块是**给用户看的**，不是给律师看的：应用一直说"完全离线"，
            // 而离线的前提是"你没打开那几个会联网的开关"。与其让人猜，
            // 不如把"什么时候、把什么、发给谁"一行一行写清楚。
            item {
                IosSection(
                    header = "会联网的功能",
                    footer = "应用的对话与推理全部在本机进行。下面是「仅有的」几处可能" +
                        "离开这台设备的数据，全部默认关闭，打开后也只在需要时发生。",
                ) {
                    NetRow(
                        what = "下载模型 / 音色 / 后端",
                        data = "下载请求",
                        to = "HuggingFace 镜像、GitHub",
                        when_ = "你点了下载才发生",
                    )
                    Divider(ios.separator)
                    NetRow(
                        what = "联网检索",
                        data = "你的查询词",
                        to = "Bing / DuckDuckGo 的公开结果页",
                        when_ = "默认关（「我的 → 能力开关」）",
                    )
                    Divider(ios.separator)
                    NetRow(
                        what = "所在地天气",
                        data = "经纬度（不含设备标识、不含对话内容）",
                        to = "Open-Meteo（天气服务）",
                        when_ = "默认关；且只在人设聊天打开「看天气」并聊到天气时读一次",
                    )
                    Divider(ios.separator)
                    NetRow(
                        what = "本机 API 服务",
                        data = "你发过去的对话内容",
                        to = "局域网内的其它设备（你自己开的）",
                        when_ = "默认关（「我的 → 本机 API 服务」）",
                    )
                }
            }

            // （这里原本有一块「模型权重许可」。按要求去掉了：应用**不分发任何权重**，
            //   每个模型的许可在模型页的条目上逐个标注 —— 那才是用户真正要做决定的地方。）

            // -------------------------------------------------- 免责
            item {
                IosSection(header = "免责说明") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            "对话内容、记忆、人设全部存在这台设备上，不上传、不收集。" +
                                "可能离开设备的数据只有上面「会联网的功能」里你主动打开的那几项：" +
                                "联网检索发送查询词，天气发送经纬度。" +
                                "本项目没有服务器，也就没有任何地方能收到这些数据 —— " +
                                "天气和检索都是直连第三方服务的。",
                            style = IosTheme.typography.footnote,
                            color = ios.secondaryLabel,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "定位信息只用于在本机显示地名与查询天气，不会写进对话历史，" +
                                "也不会随对话一起发给模型（模型看到的只有地名）。" +
                                "模型生成的内容由模型自身产生，使用者需自行判断其准确性与合规性。" +
                                "第三方音色模型仅供个人学习使用，请勿用于商业用途。",
                            style = IosTheme.typography.footnote,
                            color = ios.secondaryLabel,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String, onClick: (() -> Unit)? = null) {
    val ios = IosTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = IosTheme.typography.body, color = ios.label, modifier = Modifier.weight(1f))
        Text(value, style = IosTheme.typography.footnote, color = ios.secondaryLabel)
    }
}

/**
 * 「图标与美术」里的一行。
 *
 * 用真图画（圆形裁切）而不是退回图标：这张头像本身就是她的作品，
 * 显示成一个通用小人等于把「谁画的」这件事又抹掉了。
 */
@Composable
private fun ContributorRow(
    name: String,
    role: String,
    @androidx.annotation.DrawableRes avatar: Int,
    tint: Color,
) {
    val ios = IosTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(avatar),
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(tint.copy(alpha = 0.14f)),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = IosTheme.typography.title3, color = ios.label)
            Spacer(Modifier.height(2.dp))
            Text(role, style = IosTheme.typography.caption1, color = ios.secondaryLabel)
        }
    }
}

@Composable
private fun DonorRow(name: String, amount: String, base64: String? = null) {
    val ios = IosTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarOrIcon(
            base64 = base64,
            size = 34.dp,
            fallbackTint = ios.pink,
            fallbackAlpha = 0.15f,
            fallbackIconSize = 16.dp,
        )
        Spacer(Modifier.width(12.dp))
        Text(name, style = IosTheme.typography.body, color = ios.label, modifier = Modifier.weight(1f))
        Text(
            amount,
            style = IosTheme.typography.footnote,
            color = ios.pink,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun LicenseRow(name: String, license: String, note: String) {
    val ios = IosTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = IosTheme.typography.body, color = ios.label)
            Text(note, style = IosTheme.typography.caption1, color = ios.secondaryLabel)
        }
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(ios.fillSecondary)
                .padding(horizontal = 9.dp, vertical = 4.dp),
        ) {
            Text(license, style = IosTheme.typography.caption2, color = ios.secondaryLabel)
        }
    }
}

/**
 * 「会联网的功能」里的一行：**什么时候、把什么、发给谁**。
 *
 * 三件事分三行写，而不是揉成一句话：用户真正要判断的是"我打开它之后
 * 具体会漏出去什么"，把数据那一栏单独拎出来，他一眼就能看到"经纬度"三个字。
 */
@Composable
private fun NetRow(what: String, data: String, to: String, when_: String) {
    val ios = IosTheme.palette
    Column(Modifier.padding(horizontal = 16.dp, vertical = 11.dp)) {
        Text(what, style = IosTheme.typography.body, color = ios.label)
        Spacer(Modifier.height(3.dp))
        Text(
            data,
            style = IosTheme.typography.caption1,
            color = ios.secondaryLabel,
        )
        Text(
            "→ " + to,
            style = IosTheme.typography.caption1,
            color = ios.secondaryLabel,
        )
        Text(
            when_,
            style = IosTheme.typography.caption2,
            color = ios.tertiaryLabel,
        )
    }
}

/**
 * 圆形头像：有内置图就画图，没有就退回一个图标。
 *
 * 为什么放在这里而不是做成 `AvatarPicker` 那种通用组件：
 * 那一个服务的是"用户自己选的头像"（要能点、能存、能删），
 * 而这里只是**内置的静态图**，不需要任何交互。硬塞进同一个组件只会让
 * 两边都多出一堆用不到的参数。
 *
 * 解码失败（base64 坏了）时同样退回图标 —— 关于页不该因为一张图崩掉。
 */
@Composable
private fun AvatarOrIcon(
    base64: String?,
    size: androidx.compose.ui.unit.Dp,
    fallbackTint: Color,
    fallbackAlpha: Float,
    fallbackIconSize: androidx.compose.ui.unit.Dp,
) {
    val bmp = androidx.compose.runtime.remember(base64) {
        base64?.let { com.localmind.ui.theme.BuiltInAvatars.bitmap(it) }
    }
    val shape = RoundedCornerShape(999.dp)
    if (bmp != null) {
        androidx.compose.foundation.Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(shape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(shape)
                .background(fallbackTint.copy(alpha = fallbackAlpha)),
            contentAlignment = Alignment.Center,
        ) {
            // 退回用一个中性图标：`Favorite` 是"捐献"的语义（捐赠者那几行用），
            // 作者那行用它会让两件事看起来是同一类。
            Icon(AppIcons.Person, null, tint = fallbackTint, modifier = Modifier.size(fallbackIconSize))
        }
    }
}
