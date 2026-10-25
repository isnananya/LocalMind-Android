import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.net.URI
import java.util.zip.ZipInputStream
import org.gradle.api.tasks.PathSensitivity

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.localmind"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.localmind"
        minSdk = 26
        targetSdk = 35
        // 1.0.4（内测）= 本轮四件事：
        //   1) 提速与常驻：启动预热对话模型（可关）、设置页新增「常驻与预热」（列出
        //      真正在内存里的东西）、多模型策略写进文档；
        //   2) 语音换血：新增「Breeze TTS 2」独立页 + tts_engine 插件，手机当客户端；
        //   3) 插件能力：新增 tts_engine / ui_layout 两种能力，插件从"加素材+改配色"
        //      升级到"加语音后端 + 换整个界面结构"；
        //   4) 界面插件化：ui_layout 能换底栏入口、首页块结构、应用标题与外观令牌。
        //
        // ⚠️ 版本号按**发布批次**走，不是按我改了几轮走：这一批对外就叫 1.0.4（内测）。
        // 上一批 1.0.3 是 2026-10-17 发的那份。
        versionCode = 5
        versionName = "1.0.4(内测)"

        vectorDrawables.useSupportLibrary = true

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DLLAMA_BUILD_COMMON=OFF",
                    "-DLLAMA_CURL=OFF",
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_LLAMAFILE=OFF",
                    "-DGGML_BACKEND_DL=OFF",
                    "-DGGML_ALL_WARNINGS=OFF",
                    "-DGGML_FATAL_WARNINGS=OFF",
                    "-DLLAMA_ALL_WARNINGS=OFF",
                    "-DLLAMA_FATAL_WARNINGS=OFF"
                )
                cppFlags += listOf("-O3", "-fexceptions", "-frtti")
            }
        }

        ndk {
            // arm64 is the real target. Add x86_64 only when building for an emulator:
            //   ./gradlew assembleDebug -PabiList=arm64-v8a,x86_64
            val abis = (project.findProperty("abiList") as String?)
                ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: listOf("arm64-v8a", "armeabi-v7a")
            abiFilters += abis
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isJniDebuggable = true
        }
        /**
         * 和 release 一样过 R8，但**保持 debug 签名 + debuggable**。
         *
         * 存在的理由：R8 会把 `run-as` 挡在门外（`package not debuggable`），
         * 于是没法把 GGUF 推进 `files/models/` 去实测「加载模型 → 对话 → 语音」
         * 这条链路。而 debug 变体又因为 `isMinifyEnabled = false` 完全绕过了 R8，
         * 测不出 R8 有没有把反射用到的东西剪掉。
         *
         * 这个变体同时满足两边：既走 R8（体积和 release 同量级），又能 run-as。
         * 出包仍然用 `assembleRelease`；这个只用来验证。
         */
        create("r8debug") {
            initWith(getByName("debug"))
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            matchingFallbacks += listOf("debug")
        }
        release {
            // R8 打开。之前是 false，理由是"JNI 桥和 Compose 运行时都靠反射" ——
            // 但这两条都已经在 proguard-rules.pro 里显式 keep 了（见该文件）。
            // 关着 R8 的代价是 release 包里塞进整个 material-icons-extended
            // （34 MB 的 AAR / 上千个图标，实际只用到约 70 个）和大量不可达代码。
            // 实测：dex 从 56.5 MB 降到 2.93 MB。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Unsigned-by-default release is useless for sideloading; sign with the debug key so
            // `assembleRelease` produces an installable APK out of the box.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xjvm-default=all")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/*.kotlin_module",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST"
            )
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// ---------------------------------------------------------------------------
// stable-diffusion.cpp：单独一遍 CMake，产出 libstable-diffusion.a
// ---------------------------------------------------------------------------
// 为什么不并进 app/src/main/cpp/CMakeLists.txt：llama.cpp 和 sd.cpp 各捆绑一份
// ggml，公共头文件同名但版本差很远；CMake 会把 link 依赖的 include 排在目标自身
// include 之前，所以同工程内 sd.cpp 必然拿到 llama 的旧 ggml.h 而编译失败。独立
// 一遍 CMake 后可见集合里只有它自己那份 ggml，冲突从根上消失。
//
// 不传 -DSD_DIR 时（例如没拉 sd.cpp 源码）该任务自动跳过，主构建仍能通过，
// 只是生图退回程序化渲染器。
val sdSourceDir = listOf(
    rootProject.file("third_party/stable-diffusion.cpp"),
    // 也可能是 GitHub zip 解开的目录（带 -master 后缀）。
    rootProject.file("third_party/stable-diffusion.cpp-master"),
).firstOrNull { File(it, "src/stable-diffusion.cpp").isFile }
    ?: rootProject.file("third_party/stable-diffusion.cpp")
val sdAloneSource = file("src/main/cpp/sd-alone")

/** ABI 列表，与 android.defaultConfig.ndk.abiFilters 保持一致。 */
fun requestedAbis(): List<String> =
    (project.findProperty("abiList") as String?)
        ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        ?: listOf("arm64-v8a", "armeabi-v7a")

fun ndkToolchain(): File? {
    // android.ndkVersion 是非空 String（build.gradle.kts 里显式设了），别再写 ?:。
    val ndkVersion = android.ndkVersion
    val sdkDir = android.sdkDirectory
    return File(sdkDir, "ndk/$ndkVersion/build/cmake/android.toolchain.cmake")
        .takeIf { it.isFile }
}

/** 每个 ABI 一个输出目录；up-to-date 检查看产物是否已存在。 */
val sdAbiOutputs = requestedAbis().associateWith {
    file("src/main/jniLibs/$it/libstable-diffusion.a")
}

// ---------------------------------------------------------------------------
// 拉取预编译的静态库（推荐路径）
// ---------------------------------------------------------------------------
// sd.cpp 有 476 个 C/C++ 文件，干净构建要为每个 ABI 编一两百个编译单元。
// 同一份源码 + 同一个 NDK + 同一组开关，编出来的东西每次都一样，所以这个成本
// 放在每个开发者本机是纯浪费 —— 交给 CI 编一次、发成 Release 附件（见
// .github/workflows/prebuilt-sd.yml），这里拉下来直接用。
//
// 配置：根目录 gradle.properties 里
//   localmind.sdPrebuiltVersion=sd-1.0.0
//   localmind.sdPrebuiltRepo=用户/仓库            （默认本仓库）
// 没有网 / 版本不存在时**不报错**，静默退回本机编译，不会把构建弄挂。
val sdPrebuiltVersion = (project.findProperty("localmind.sdPrebuiltVersion") as String?)
    ?.takeIf { it.isNotBlank() }
val sdPrebuiltRepo = (project.findProperty("localmind.sdPrebuiltRepo") as String?)
    ?.takeIf { it.isNotBlank() }

val fetchStableDiffusion by tasks.registering {
    group = "build"
    description = "从 GitHub Release 拉取预编译的 stable-diffusion 静态库（省去本机编译）"

    // 产物已在就不重复下载 —— 这是它省时间的关键。
    outputs.file(sdAbiOutputs.values.first())

    // 打 APK 用不到它（主库不链 SD），别为一个用不上的包发网络请求。
    onlyIf("SD 静态库被显式请求（-PwithSdLib=true 或 assembleSdPlugin）") {
        val wantsSd = (project.findProperty("withSdLib") as String?)?.toBoolean() ?: false
        val sdTaskRequested = gradle.startParameter.taskNames.any {
            it.contains("SdPlugin", ignoreCase = true)
        }
        (wantsSd || sdTaskRequested) && sdAbiOutputs.values.any { !it.isFile }
    }

    doLast {
        val missing = sdAbiOutputs.filterValues { !it.isFile }
        if (missing.isEmpty()) {
            logger.lifecycle("生图后端预编译库已就位，跳过下载。")
            return@doLast
        }
        if (sdPrebuiltVersion == null || sdPrebuiltRepo == null) {
            logger.lifecycle(
                "未配置预编译库来源（localmind.sdPrebuiltVersion / localmind.sdPrebuiltRepo），" +
                    "将回退到本机编译。"
            )
            return@doLast
        }

        val url = "https://github.com/$sdPrebuiltRepo/releases/download/" +
            "$sdPrebuiltVersion/localmind-sd-prebuilt.zip"
        // 变量名不叫 zip：那个名字会遮蔽 java.util.zip 包，导致下面的
        // ZipInputStream 解析失败（Kotlin DSL 里踩过一次）。
        val archive = layout.buildDirectory.file("sd-prebuilt/download.zip").get().asFile
        archive.parentFile.mkdirs()

        logger.lifecycle("下载生图后端预编译库：$url")
        val ok = runCatching {
            // 顶层 import 了 URI / ZipInputStream：Gradle 的 Kotlin DSL 脚本
            // 默认只导入 java.io / java.lang 等少数包，写全限定名会解析失败。
            val conn = URI(url).toURL().openConnection().apply {
                connectTimeout = 20_000
                readTimeout = 300_000
                setRequestProperty("User-Agent", "LocalMind-build")
            }
            conn.getInputStream().use { input ->
                archive.outputStream().use { out -> input.copyTo(out) }
            }
            true
        }.getOrElse { t ->
            logger.lifecycle("下载失败（${t.message?.take(80)}），回退到本机编译。")
            false
        }
        if (!ok) return@doLast

        // 只取我们认识的路径：zip 里是 <abi>/libstable-diffusion.a。
        // 不整包解压到工程目录 —— 来自网络的压缩包不该被无条件展开。
        var extracted = 0
        ZipInputStream(archive.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                val abi = sdAbiOutputs.keys.firstOrNull { name.startsWith("$it/") }
                if (!entry.isDirectory && abi != null && name.endsWith("libstable-diffusion.a")) {
                    val target = sdAbiOutputs.getValue(abi)
                    target.parentFile.mkdirs()
                    target.outputStream().use { out -> zis.copyTo(out) }
                    extracted++
                    logger.lifecycle("  ← $abi  ${target.name} (${target.length() / 1024 / 1024} MB)")
                }
                entry = zis.nextEntry
            }
        }
        if (extracted == 0) {
            logger.lifecycle("压缩包里没有可用的静态库，回退到本机编译。")
        }
    }
}

val compileStableDiffusion by tasks.registering {
    group = "build"
    description = "本机编译 stable-diffusion.cpp 成静态库（预编译库拿不到时的兜底）"

    // 预编译库先试；这一步能拿到就不会走到本机编译。
    dependsOn(fetchStableDiffusion)

    inputs.dir(sdAloneSource)
    inputs.dir(sdSourceDir).withPathSensitivity(PathSensitivity.RELATIVE).skipWhenEmpty()
    inputs.property("abis", requestedAbis().joinToString(","))
    sdAbiOutputs.values.forEach { outputs.file(it) }

    // ⚠️ 只有明确要 SD 静态库时才编。
    //
    // 主库（liblocalmind）**不链** stable-diffusion（见 cpp/CMakeLists.txt 那段
    // 长注释）。它的唯一用途是产出插件 zip 的中间产物。所以给手机编 APK 时
    // 编这 363 个文件是**纯浪费** —— 实测卡十几分钟不动。
    //
    // 要出插件包：./gradlew :app:assembleSdPlugin （它自己 dependsOn 这个任务）
    // 或用 -PwithSdLib=true 显式打开。
    val wantsSd = (project.findProperty("withSdLib") as String?)?.toBoolean() ?: false
    val sdTaskRequested = gradle.startParameter.taskNames.any {
        it.contains("SdPlugin", ignoreCase = true)
    }
    onlyIf("SD 静态库被显式请求（-PwithSdLib=true 或 assembleSdPlugin）") {
        (wantsSd || sdTaskRequested) && sdAbiOutputs.values.any { !it.isFile }
    }

    doLast {
        if (!sdSourceDir.isDirectory || !File(sdSourceDir, "src/stable-diffusion.cpp").isFile) {
            logger.lifecycle(
                "跳过生图后端：${sdSourceDir.path} 缺少 src/stable-diffusion.cpp。" +
                    "拉取方式见 docs/DOWNLOAD.md，生图将退回程序化渲染器。"
            )
            return@doLast
        }
        val toolchain = ndkToolchain()
        if (toolchain == null) {
            logger.lifecycle("跳过生图后端：找不到 NDK 工具链。")
            return@doLast
        }
        // android.externalNativeBuild.cmake.version 是非空 String（已在 build.gradle.kts
        // 的 externalNativeBuild 块里显式设为 "3.22.1"），不要写成 nullable。
        val cmakeVersion = android.externalNativeBuild.cmake.version
        val sdkDir = android.sdkDirectory
        val cmakeBin = File(sdkDir, "cmake/$cmakeVersion/bin/cmake.exe")
            .takeIf { it.isFile }
            ?: File(sdkDir, "cmake/$cmakeVersion/bin/cmake").takeIf { it.isFile }
        val ninjaBin = cmakeBin?.parentFile?.let { binDir ->
            listOf("ninja.exe", "ninja").map { File(binDir, it) }.firstOrNull { it.isFile }
        }
        if (cmakeBin == null) {
            logger.lifecycle(
                "跳过生图后端：找不到 cmake。" +
                    "sdkDirectory=${sdkDir.path} cmakeVersion=$cmakeVersion"
            )
            return@doLast
        }
        if (ninjaBin == null) {
            logger.lifecycle("跳过生图后端：在 ${cmakeBin.parentFile.path} 下找不到 ninja。")
            return@doLast
        }
        logger.lifecycle("生图后端工具链：cmake=${cmakeBin.path}")

        requestedAbis().forEach { abi ->
            val buildDir = layout.buildDirectory.dir("sd-alone/$abi").get().asFile
            val outFile = sdAbiOutputs.getValue(abi)
            if (outFile.isFile) {
                logger.lifecycle("生图后端 [$abi] 已存在，跳过：${outFile.name}")
                return@forEach
            }
            logger.lifecycle("编译生图后端 [$abi] …（首次约数分钟）")

            fun run(vararg args: String) {
                // ProcessBuilder + inheritIO instead of exec{}: we want the native
                // compilers' output to stream straight to the Gradle console, and
                // piped stdio is not always available under the file sandbox.
                val proc = ProcessBuilder(*args)
                    .directory(projectDir)
                    .inheritIO()
                    .start()
                val code = proc.waitFor()
                if (code != 0) error("命令失败（exit $code）：${args.joinToString(" ")}")
            }

            run(
                cmakeBin.absolutePath,
                "-S", sdAloneSource.absolutePath,
                "-B", buildDir.absolutePath,
                "-G", "Ninja",
                "-DCMAKE_MAKE_PROGRAM=${ninjaBin.absolutePath}",
                "-DCMAKE_TOOLCHAIN_FILE=${toolchain.absolutePath}",
                "-DANDROID_ABI=$abi",
                "-DANDROID_PLATFORM=android-26",
                "-DANDROID_STL=c++_shared",
                "-DCMAKE_BUILD_TYPE=Release",
                "-DSD_DIR=${sdSourceDir.absolutePath}",
            )
            run(
                cmakeBin.absolutePath, "--build", buildDir.absolutePath,
                "--target", "stable-diffusion", "-j", "6",
            )

            // 静态库可能落在不同子目录，找出来再拷到 jniLibs。
            val produced = buildDir.walkTopDown()
                .firstOrNull { it.isFile && it.name == "libstable-diffusion.a" }
                ?: error("找不到编译产物 libstable-diffusion.a（$abi）")
            outFile.parentFile.mkdirs()
            produced.copyTo(outFile, overwrite = true)
            logger.lifecycle("生图后端 [$abi] → ${outFile.absolutePath}")
        }
    }
}

// 主 CMake 只有在**显式**要静态库时才依赖它。
//
// 不能无条件 dependsOn：compileStableDiffusion 的 onlyIf 把「本次调用里有
// SdPlugin 任务」也算成想要静态库，于是 `assembleRelease + assembleSdPlugin`
// 会把同一份 sd.cpp 编两遍（一遍静态、一遍共享），白白多花十几分钟，
// 而主库根本不链它。
val withSdLib = (project.findProperty("withSdLib") as String?)?.toBoolean() ?: false
if (withSdLib) {
    tasks.matching { it.name.startsWith("buildCMake") }.configureEach {
        dependsOn(compileStableDiffusion)
    }
}

// ---------------------------------------------------------------------------
// 生图后端**插件**（liblocalmind-sd.so + zip）
// ---------------------------------------------------------------------------
// 生图后端不再链进 liblocalmind.so —— 那样会把它从 5.4 MB 撑到 41.7 MB，
// 而这个功能只有愿意再下 1.5–6 GB checkpoint 的人用得上（见 cpp/CMakeLists.txt
// 与 sd-alone/CMakeLists.txt 里的完整理由，包括两份 ggml 的 GGML_MAX_NAME
// 不一致导致 struct ggml_tensor 差 96 字节那个真 ABI 地雷）。
//
// 这里产出用户真正拿到的东西：一个能直接导入的 zip。
//
// 和 compileStableDiffusion 的关系：复用同一份 sd-alone 工程与同一遍编译 ——
// 静态库和共享库都由那个 CMakeLists 产出（它定义了 stable-diffusion 和
// localmind-sd 两个目标）。所以这里只额外构建 localmind-sd 目标并打包。
val assembleSdPlugin by tasks.registering {
    group = "build"
    description = "产出「本地生图后端（通用版）」插件 zip（arm64 + x86_64）"

    inputs.dir(sdAloneSource)
    inputs.dir(project.layout.projectDirectory.dir("../plugins-src/sd-backend"))

    // 生图插件的 ABI 列表**与主 APK 不同**。
    //
    // 主程序默认 arm64-v8a + armeabi-v7a（要覆盖老 32 位机），但生图后端在
    // armeabi-v7a 上根本 configure 不过（sd.cpp 的 ggml 需要 64 位），而
    // dist/plugins 里一直也只有 arm64 与 x86_64 两个包 —— 也就是说
    // 「直接跑 :app:assembleSdPlugin」这条路一直是坏的（默认列表里带 v7a）。
    // 现在默认取插件真正支持的两种，仍然可以用 -PabiList=... 覆盖。
    val pluginAbis = (project.findProperty("abiList") as String?)
        ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        ?: listOf("arm64-v8a", "x86_64")
    inputs.property("abis", pluginAbis.joinToString(","))

    val distDir = project.layout.projectDirectory.dir("../dist/plugins").asFile

    doLast {
        if (!sdSourceDir.isDirectory || !File(sdSourceDir, "src/stable-diffusion.cpp").isFile) {
            error(
                "缺少 stable-diffusion.cpp 源码：${sdSourceDir.path}\n" +
                    "拉取方式见 docs/DOWNLOAD.md"
            )
        }
        val toolchain = ndkToolchain() ?: error("找不到 NDK 工具链，无法编译生图后端。")
        val cmakeVersion = android.externalNativeBuild.cmake.version
        val sdkDir = android.sdkDirectory
        val cmakeBin = File(sdkDir, "cmake/$cmakeVersion/bin/cmake.exe")
            .takeIf { it.isFile }
            ?: File(sdkDir, "cmake/$cmakeVersion/bin/cmake")
        val ninjaBin = cmakeBin?.parentFile?.let { binDir ->
            listOf("ninja.exe", "ninja").map { File(binDir, it) }.firstOrNull { it.isFile }
        }
        if (cmakeBin == null || ninjaBin == null) error("找不到 cmake/ninja（$sdkDir/cmake/$cmakeVersion）")

        // strip 工具：未 strip 的 .so 是 147 MB，strip 后约 37 MB（zip 内约 14 MB）。
        //
        // 别再「数几级 parentFile」了 —— 这里已经错过两次（五级时指到 SDK 根，四级时
        // 指到 ndk/ 而不带版本号），而失败只是一行警告，插件包就静默地变成 3-4 倍大。
        // 改成**向上找**：哪一级目录下真的有 toolchains/llvm/prebuilt，那就是 NDK 根。
        val ndkDir = generateSequence(toolchain.parentFile) { it.parentFile }
            .take(8)
            .firstOrNull { File(it, "toolchains/llvm/prebuilt").isDirectory }
        val stripHosts = listOf("windows-x86_64", "linux-x86_64", "darwin-x86_64")
        val stripNames = listOf("llvm-strip.exe", "llvm-strip")
        val stripBin = stripHosts
            .flatMap { host -> stripNames.map { exe -> File(ndkDir, "toolchains/llvm/prebuilt/" + host + "/bin/" + exe) } }
            .firstOrNull { it.isFile }
        if (stripBin == null) {
            logger.warn(
                "找不到 llvm-strip（找过 " + ndkDir?.absolutePath +
                    " 下的 toolchains/llvm/prebuilt/*/bin/）：产物不会 strip，插件 zip 会大 3-4 倍。"
            )
        }

        fun run(vararg args: String) {
            val proc = ProcessBuilder(*args).directory(projectDir).inheritIO().start()
            val code = proc.waitFor()
            if (code != 0) error("命令失败（exit $code）：${args.joinToString(" ")}")
        }

        val staging = layout.buildDirectory.dir("sd-plugin-staging").get().asFile
        staging.deleteRecursively()
        staging.mkdirs()

        pluginAbis.forEach { abi ->
            val buildDir = layout.buildDirectory.dir("sd-plugin/$abi").get().asFile
            run(
                cmakeBin.absolutePath,
                "-S", sdAloneSource.absolutePath,
                "-B", buildDir.absolutePath,
                "-G", "Ninja",
                "-DCMAKE_MAKE_PROGRAM=${ninjaBin.absolutePath}",
                "-DCMAKE_TOOLCHAIN_FILE=${toolchain.absolutePath}",
                "-DANDROID_ABI=$abi",
                "-DANDROID_PLATFORM=android-26",
                "-DANDROID_STL=c++_shared",
                "-DCMAKE_BUILD_TYPE=Release",
                "-DSD_DIR=${sdSourceDir.absolutePath}",
            )
            run(cmakeBin.absolutePath, "--build", buildDir.absolutePath, "--target", "localmind-sd", "-j", "6")

            val so = buildDir.walkTopDown()
                .firstOrNull { it.isFile && it.name == "liblocalmind-sd.so" }
                ?: error("找不到 liblocalmind-sd.so（$abi）")

            // 先 strip 再打包：这是用户要下载的字节数，省的每一个都是真的。
            val libDir = File(staging, "lib/$abi")
            libDir.mkdirs()
            val target = File(libDir, "liblocalmind-sd.so")
            so.copyTo(target, overwrite = true)
            if (stripBin != null) {
                run(stripBin.absolutePath, "--strip-unneeded", target.absolutePath)
            } else {
                logger.lifecycle("警告：找不到 llvm-strip，包会是未 strip 的（大好几倍）")
            }
            logger.lifecycle(
                "生图后端 [$abi] → ${target.absolutePath} " +
                    "(${target.length() / 1024 / 1024} MB)"
            )
        }

        // 清单与说明从 plugins-src 拷过来（单一来源，不在这里重新拼 JSON）。
        val src = project.layout.projectDirectory.dir("../plugins-src/sd-backend").asFile
        listOf("plugin.json", "backends.json", "README.md").forEach { name ->
            val f = File(src, name)
            if (f.isFile) f.copyTo(File(staging, name), overwrite = true)
        }

        // 打包。用 JDK 自带的 ZipOutputStream 而不是调外部 zip —— 不依赖宿主工具。
        //
        // 类名写全限定：Kotlin DSL 里 `java` 会被解析成 Gradle 的 JavaPluginExtension，
        // 所以 `java.util.zip.X` 编译不过。
        distDir.mkdirs()
        val out = File(distDir, "本地生图后端插件-通用版.zip")
        if (out.isFile) out.delete()
        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            staging.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f ->
                val entryName = f.relativeTo(staging).invariantSeparatorsPath
                zos.putNextEntry(ZipEntry(entryName))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        logger.lifecycle("生图后端插件 → ${out.absolutePath} (${out.length() / 1024 / 1024} MB)")
    }
}

// ---------------------------------------------------------------------------
// sherpa-onnx 神经语音：原生库**不进 APK**，按需在设备上跑
// ---------------------------------------------------------------------------
// 官方 v1.13.8 Release **没有 Android AAR**（288 个附件里跟 Android 有关的只有
// tar.bz2，见 docs/BLOCKERS.md），所以只能自己取 .so。
//
// ⚠️ 关键决定：**不把这些 .so 打进 APK**。
//
// 每个 ABI 共 36 MB（`libonnxruntime.so` 一家 21–25 MB）。打进包等于让所有用户
// 先付这 36 MB，而绝大多数人一次都不会打开神经音色。现在改成：
//   * 运行时 `SherpaLibraryInstaller` 从同一个官方 tarball 下载并解压到
//     `filesDir/native/<abi>/`，再 `System.load`
//   * 下面这个任务只是**开发机上的缓存**，方便离线开发 / 手工验证"打包路径"
//
// 想回到"打进包"（例如要出一个离线可用的版本）：
//   ./gradlew :app:fetchSherpaOnnx && cp -r app/build/sherpa-prebuilt/* app/src/main/jniLibs/
val sherpaVersion = (project.findProperty("localmind.sherpaVersion") as String?)
    ?.takeIf { it.isNotBlank() } ?: "1.13.8"

/** sherpa 只发过这几个 ABI；x86 不要（模拟器是 x86_64）。 */
val sherpaAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

val sherpaStaging = layout.buildDirectory.dir("sherpa-prebuilt")

val fetchSherpaOnnx by tasks.registering {
    group = "build"
    description = "（开发用）把 sherpa-onnx 原生库缓存到 build/sherpa-prebuilt，便于离线开发"

    val marker = layout.buildDirectory.file("sherpa-onnx/.fetched-$sherpaVersion").get().asFile
    val stagingRoot = sherpaStaging.get().asFile
    outputs.file(marker)

    doLast {
        val wanted = requestedAbis().filter { it in sherpaAbis }
        val missing = wanted.filter { !File(stagingRoot, "$it/libsherpa-onnx-jni.so").isFile }
        if (missing.isEmpty()) {
            logger.lifecycle("sherpa-onnx 原生库已缓存，跳过下载。")
            return@doLast
        }

        val asset = "sherpa-onnx-v$sherpaVersion-android.tar.bz2"
        // 直连 GitHub Releases 不稳（实测随机会超时），gh-proxy 是唯一稳定可用的。
        val urls = listOf(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/$asset",
            "https://gh-proxy.com/https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/$asset",
        )

        val archive = layout.buildDirectory.file("sherpa-onnx/$asset").get().asFile
        archive.parentFile.mkdirs()

        var downloaded = archive.isFile && archive.length() > 40_000_000
        if (!downloaded) {
            for (url in urls) {
                logger.lifecycle("拉取 sherpa-onnx 原生库：$url")
                downloaded = runCatching {
                    val conn = URI(url).toURL().openConnection().apply {
                        connectTimeout = 20_000
                        readTimeout = 900_000
                        setRequestProperty("User-Agent", "LocalMind-build")
                    }
                    conn.getInputStream().use { input ->
                        archive.outputStream().use { out -> input.copyTo(out) }
                    }
                    archive.length() > 40_000_000
                }.getOrElse { t ->
                    logger.lifecycle("  失败（${t.message?.take(60)}）")
                    false
                }
                if (downloaded) break
            }
        }
        if (!downloaded) {
            logger.lifecycle("拿不到 sherpa-onnx 原生库，跳过。运行时仍会按需下载。")
            return@doLast
        }

        val wantedFiles = setOf(
            "libonnxruntime.so",
            "libsherpa-onnx-jni.so",
            "libsherpa-onnx-c-api.so",
            "libsherpa-onnx-cxx-api.so",
        )
        val staging = layout.buildDirectory.dir("sherpa-onnx/staging").get().asFile
        staging.deleteRecursively()
        staging.mkdirs()
        val tar = ProcessBuilder("tar", "-xjf", archive.absolutePath, "-C", staging.absolutePath)
            .inheritIO()
            .start()
        val code = tar.waitFor()
        if (code != 0) {
            logger.lifecycle("解压失败（tar exit $code），跳过。")
            return@doLast
        }

        var extracted = 0
        staging.walkTopDown()
            .filter { it.isFile && it.name in wantedFiles && it.parentFile.name in wanted }
            .forEach { so ->
                val abi = so.parentFile.name
                val dest = File(stagingRoot, "$abi/${so.name}")
                dest.parentFile.mkdirs()
                so.copyTo(dest, overwrite = true)
                extracted++
                logger.lifecycle("  ← $abi/${so.name}  (${so.length() / 1024 / 1024} MB)")
            }
        staging.deleteRecursively()

        if (extracted == 0) {
            logger.lifecycle("压缩包里没有可用的原生库，跳过。")
            return@doLast
        }
        marker.parentFile.mkdirs()
        marker.writeText("$sherpaVersion\n")
        logger.lifecycle(
            "已缓存到 ${stagingRoot.path}。" +
                "要打进 APK 就手工拷到 app/src/main/jniLibs/（默认不打，运行时按需下载）。"
        )
    }
}

// 打包 jniLibs 的任务**不再依赖 compileStableDiffusion**。
//
// 以前必须依赖：生图静态库落在 src/main/jniLibs/<abi>/，而那是 merge*JniLibFolders
// 的输入目录；不声明这层依赖，Gradle 8 的 implicit-dependency 校验会直接让
// assembleDebug 失败（硬错误，不是警告）：
//   Task ':app:mergeDebugJniLibFolders' uses this output of task ':app:compileStableDiffusion'
//   without declaring an explicit or implicit dependency.
//
// 现在生图后端是**插件**（独立 .so，用户按需下载），静态库不再进 APK ——
// 它只是构建插件 zip 的中间产物，落在 build/ 里，jniLibs 目录已经空掉了。
// 所以这层依赖既不需要、留着还会让每次打包都白编一遍 252 MB 的静态库。
//
// ⚠️ 这里也**故意不包含 fetchSherpaOnnx**：它只往 build/ 缓存，同样不碰 jniLibs。
//
// ── 2026-10-15：这层依赖在实践里是**有害的**，加个开关关掉 ──────────────
//
// 现象：给手机编 arm64 时，构建卡在 clang 编 stable-diffusion.cpp 的 363 个文件上，
// 十几分钟不动。原因正是下面那个 dependsOn —— `mergeJniLibFolders` 被拖进
// fetchStableDiffusion，而预编译库只有 x86_64（原来就是为模拟器拉的），
// 于是 arm64 走进"拿不到预编译库 → 本机编译"的兜底分支。
// 而**主库根本不需要它**（CMakeLists 里 liblocalmind 不链 SD）。
//
// 所以：默认**不**在打包 APK 时拉/编 SD。要出插件包时显式开：
//     ./gradlew :app:assembleSdPlugin -PwithSdLib=true
// 需要把静态库放进 jniLibs（老流程）时同理。
if (withSdLib) {
    logger.lifecycle("withSdLib=true：本次构建会拉取/编译 stable-diffusion 静态库。")
    tasks.matching {
        it.name.startsWith("merge") && it.name.endsWith("JniLibFolders")
    }.configureEach {
        dependsOn(fetchStableDiffusion)
    }
} else {
    logger.lifecycle(
        "跳过 stable-diffusion 静态库（只打 APK 用不到它）。" +
            "要出插件包请加 -PwithSdLib=true，或直接跑 :app:assembleSdPlugin。"
    )
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-process:2.8.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
