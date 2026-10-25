# ---------------------------------------------------------------------------
# 只编译 stable-diffusion.cpp，产出 libstable-diffusion.a
# ---------------------------------------------------------------------------
# 为什么单独一遍 CMake，而不是和 llama.cpp 放进同一个工程：
#
#   llama.cpp 和 stable-diffusion.cpp 各捆绑一份 ggml，两者的公共头文件**同名**
#   （ggml.h / ggml-backend.h / ggml-cpu.h …）但版本相差很远。CMake 会把
#   `target_link_libraries(A PUBLIC B)` 里 B 的 include 目录排在 A 自己的 include
#   **之前**，所以只要两个 ggml 在同一个工程里可见，sd.cpp 就必然先拿到 llama 的旧
#   ggml.h，然后在自己的源码上编译失败：
#
#     src/core/ggml_extend.cpp: error: use of undeclared identifier
#         'ggml_quantize_i8_convrot' / 'ggml_mul_mat_i8_tensorwise'
#         / 'GGML_SAGE_ATTN_AUTO'
#
#   试过并**失败**的两种绕法：
#     · 清空 llama ggml 的 INTERFACE_INCLUDE_DIRECTORIES
#       → 连 llama 自己的 ggml-cpu 都找不到 ggml.h，整个 llama 编译崩
#     · 给 stable-diffusion 加 BEFORE PRIVATE
#       → 仍被 link 依赖的 include 抢占，无效
#
#   独立一遍 CMake 后，工程的 include 集合里只有 sd.cpp 自己那份 ggml，冲突从根上消失。
#
# 用法（见 app/build.gradle.kts 的 compileStableDiffusion 任务）：
#   cmake -S app/src/main/cpp/sd-alone -B <build> -G Ninja \
#     -DCMAKE_TOOLCHAIN_FILE=<ndk>/build/cmake/android.toolchain.cmake \
#     -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
#     -DANDROID_STL=c++_shared -DCMAKE_BUILD_TYPE=Release -DSD_DIR=<sd.cpp 根目录>
#
# ---------------------------------------------------------------------------
# 产出**两个**东西，两个都有用：
#
#   stable-diffusion  → libstable-diffusion.a    （静态库，给别处链接用）
#   localmind-sd      → liblocalmind-sd.so       （自包含共享库 = 插件形态）
#
# 后者是**运行时下载**的那个：主程序不带生图后端，用户第一次要真生图时
# 把这个 .so 下到 filesDir/native/<abi>/ 再 System.load。
#
# 为什么共享库必须自包含（--no-undefined）：Android 链接器**允许**共享库带
# 未定义符号，问题要到 System.load 时才以 "cannot locate symbol" 暴露。
# 而这份 .so 是下载来的、没法在编译期看到它跟谁一起加载，所以必须在**链接期**
# 就保证没有悬空符号。
# ---------------------------------------------------------------------------
cmake_minimum_required(VERSION 3.22.1)
project(sd_alone CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT DEFINED SD_DIR)
    message(FATAL_ERROR "必须传入 -DSD_DIR=<third_party/stable-diffusion.cpp 的绝对路径>")
endif()
if(NOT EXISTS "${SD_DIR}/src/stable-diffusion.cpp")
    message(FATAL_ERROR
        "在 ${SD_DIR}/src 下找不到 stable-diffusion.cpp。\n"
        "源码可能不完整（只有 CMakeLists.txt + ggml/ 而缺少 src/）。\n"
        "拉取方式见 docs/DOWNLOAD.md")
endif()

# WebP / WebM 必须关闭：它们的子模块（thirdparty/libwebp、thirdparty/libwebm）
# 来自 git submodule，源码包里是空目录；而 WebM 打开时 WebP 关闭会直接 FATAL_ERROR。
set(BUILD_SHARED_LIBS         OFF CACHE BOOL "" FORCE)
set(SD_BUILD_EXAMPLES         OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_GGML_LIB  OFF CACHE BOOL "" FORCE)
set(SD_WEBP                   OFF CACHE BOOL "" FORCE)
set(SD_WEBM                   OFF CACHE BOOL "" FORCE)
set(SD_CUDA                   OFF CACHE BOOL "" FORCE)
set(SD_HIPBLAS                OFF CACHE BOOL "" FORCE)
set(SD_METAL                  OFF CACHE BOOL "" FORCE)
set(SD_VULKAN                 OFF CACHE BOOL "" FORCE)
set(SD_BUILD_TESTS            OFF CACHE BOOL "" FORCE)

# ggml 默认会打开 OpenMP，Android 上会变成对 NDK 的 libomp.so 的 DT_NEEDED 依赖 ——
# 那个 .so 不进 APK（也不该进下载包），于是 System.load 直接失败。主工程编 llama 的
# ggml 时同样关掉了它（app/src/main/cpp/CMakeLists.txt 的 GGML_OPENMP）。
set(GGML_OPENMP               OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE               OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS          OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES       OFF CACHE BOOL "" FORCE)

# ---------------------------------------------------------------------------
# GGML_MAX_NAME 只在**这个**工程里设成 160。
#
# 它是 sd.cpp 自己那份 ggml 的期望值，也是 `struct ggml_tensor` 里
# `char name[GGML_MAX_NAME]` 的长度。因为生图后端现在是独立的 .so，
# 这里设多少都**不会**影响 llama.cpp —— 两个 ggml 各自留在自己的库里，
# bionic 解析符号时先看库自己。
#
# （曾经把两者链进同一个 .so，那时必须全局统一成 160，否则 llama 的
#  sizeof(ggml_tensor) 会短 96 字节、extra/view_src 落在错偏移上 ——
#  一个不会在编译期报错的内存错乱。分成两个 .so 之后这个雷就拆掉了。）
# ---------------------------------------------------------------------------
add_compile_definitions(GGML_MAX_NAME=160)

add_subdirectory("${SD_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/sd" EXCLUDE_FROM_ALL)

# ---------------------------------------------------------------------------
# 把 ggml 的 .o 直接塞进 sd.cpp 自己的 libstable-diffusion.a
# ---------------------------------------------------------------------------
# 为什么必须塞进去：链静态库的那条路用 `-Wl,--whole-archive` 只链
# libstable-diffusion.a **这一个文件**，而 sd.cpp 的每个目标文件都在引用 ggml_*
# 符号。ggml 的归档如果不在同一个文件里，这些符号就没人定义。
#
# 也不能改链 llama 那份 ggml 来顶替：两份 ggml 的 enum ggml_op、
# struct ggml_tensor 布局都不一样（sd.cpp 用的是 leejet/ggml 打了补丁的分支），
# 混用就是 ABI 不匹配，比编不过更危险。
#
# `$<TARGET_OBJECTS:tgt>` 对 STATIC 库同样有效（CMake ≥3.21），所以这里不需要
# 拆包/重新打包 .a —— ggml 的 .o 只编译一次，然后被两个目标同时引用。
#
# zip / onig / sd-utf8proc 是 OBJECT 库，sd.cpp 的 CMakeLists 已经用
# `target_sources(${SD_LIB} PRIVATE $<TARGET_OBJECTS:zip>)` 之类的方式并进去了，
# 所以这里只补 ggml 三个。重复加会出现同名成员 → --whole-archive 时重复符号。
target_sources(stable-diffusion PRIVATE
    $<TARGET_OBJECTS:ggml>
    $<TARGET_OBJECTS:ggml-base>
    $<TARGET_OBJECTS:ggml-cpu>
)

# ---------------------------------------------------------------------------
# 自包含共享库 —— 这就是"生图后端插件"的实体
# ---------------------------------------------------------------------------
add_library(localmind-sd SHARED sd_jni.cpp)

# sd_jni.cpp 只有四个入口，剩下全在静态库里，所以这里要 whole-archive
# 把整个 sd.cpp + ggml 拉进来。前面已经把 ggml 的 .o 并进同一个归档，
# 所以这里只写一个文件名就够。
target_link_libraries(localmind-sd PRIVATE
    "-Wl,--whole-archive"
    stable-diffusion
    "-Wl,--no-whole-archive"
    # __android_log_print。忘了它的话 --no-undefined 会在这里报错 ——
    # 这正是那道闸门存在的意义：没有它，我们会在用户手机上才看到
    # "cannot locate symbol"。
    log
)

target_include_directories(localmind-sd PRIVATE
    "${SD_DIR}/include"
    "${SD_DIR}/ggml/include"
)

# --no-undefined：见文件头的说明。少一个符号就要在这里失败，而不是在用户的手机上。
# -Wl,--exclude-libs,ALL：把静态库的内部符号藏起来，只留我们的 JNI 入口和
# sd.cpp 的公开 C API。既减小体积，也避免和主程序的符号撞名。
target_link_options(localmind-sd PRIVATE
    "-Wl,--no-undefined"
    "-Wl,--exclude-libs,ALL"
    "-Wl,-z,max-page-size=16384"
)

# -fvisibility=hidden + 显式导出的 JNI 入口：Android 上 JNI 符号必须可见，
# 所以 JNIEXPORT 已经带了 default visibility，这里只藏其它的。
set_target_properties(localmind-sd PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
    # 输出名固定成 liblocalmind-sd.so，和下载器里的文件名一致。
    OUTPUT_NAME "localmind-sd"
    PREFIX "lib"
    SUFFIX ".so"
)

message(STATUS "生图后端插件：将产出 liblocalmind-sd.so（自包含，运行时下载）")
