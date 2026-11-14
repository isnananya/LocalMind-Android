cmake_minimum_required(VERSION 3.22.1)
project(localmind CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# ---------------------------------------------------------------------------
# llama.cpp (vendored at third_party/llama.cpp)
# ---------------------------------------------------------------------------
set(LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp")

if(NOT EXISTS "${LLAMA_DIR}/include/llama.h")
    message(FATAL_ERROR
        "llama.cpp sources not found at ${LLAMA_DIR}.\n"
        "Run:  git clone --depth 1 https://github.com/ggml-org/llama.cpp.git third_party/llama.cpp")
endif()

# ---------------------------------------------------------------------------
# GGML_MAX_NAME 这里**不需要**再设了。
# ---------------------------------------------------------------------------
# 曾经必须设成 160：生图后端和 llama 各带一份 ggml，两份的 GGML_MAX_NAME 不同
# （160 vs 64），而 `char name[GGML_MAX_NAME]` 是 `struct ggml_tensor` 的真实字段，
# 所以 sizeof 差 96 字节、`extra` / `view_src` 偏移全错 —— 链接能过，跑起来是
# 内存踩踏。两份 ggml 当时在同一个 .so 里，只能靠全局统一宏来绕。
#
# 现在生图后端是独立的 liblocalmind-sd.so，两个 ggml 各自留在自己的库里
# （bionic 解析符号时先看库自己），所以这里用 llama 自己的默认值就行。
#
# 留这段注释是因为"删掉一个看起来没用的宏"很容易被当成清理 —— 它不是没用，
# 是**前提变了**。如果哪天又想把 sd.cpp 链进这个 .so，这个宏必须加回来。
set(BUILD_SHARED_LIBS   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON  OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL          OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP         OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE      OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL     OFF CACHE BOOL "" FORCE)
# 识图要用的多模态库（tools/mtmd）。llama.cpp 提供了"只编这个库"的开关，
# 打开它就不会顺带编一整个 tools/ 目录 —— 见它根 CMakeLists 里那段的说明。
set(LLAMA_BUILD_MTMD     ON  CACHE BOOL "" FORCE)

# ---------------------------------------------------------------------------
# ARM KleidiAI（只对 arm64-v8a 开）
# ---------------------------------------------------------------------------
#
# 这是上游为 Android arm64-v8a 写的加速路径：ARM 官方的 i8mm / dotprod / SME 微内核。
# 三个性质让它比「全局加 -march」安全得多：
#
#  1. **不抬编译基线** —— 它按 HWCAP 在运行期探测 CPU 特性，每个内核由 KleidiAI
#     自己的 CMake 加 -march。全局 -march=armv8.2-a+dotprod 会让老 arm64 设备
#     直接 SIGILL，而这条路不会。
#  2. **只覆盖部分量化类型**（当前是 Q4_0 / Q8_0 / F16 系）。清单里占多数的
#     Q4_K_M 拿不到收益 —— 这一条必须写在这里，否则下一个人会以为「开了就全线变快」。
#  3. **按 ABI 分支是硬要求**：KleidiAI 只支持 AArch64，给 armeabi-v7a 或 x86_64 打开
#     会让 ggml 直接 FATAL_ERROR（见 ggml/src/ggml-cpu/CMakeLists.txt）。
#
# 代价：configure 时会 FetchContent 下载 kleidiai 源码包（v1.24.0，校验 MD5）。
# 下载失败就是 configure 失败；本机实测可用，网络到 GitHub 不稳时把这一段注释掉即可
#（关掉不影响任何功能，只是少一层加速）。
message(STATUS "LocalMind: ANDROID_ABI=[${ANDROID_ABI}] CMAKE_ANDROID_ARCH_ABI=[${CMAKE_ANDROID_ARCH_ABI}]")
if(ANDROID_ABI STREQUAL "arm64-v8a" OR CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
    message(STATUS "LocalMind: KleidiAI enabled for arm64-v8a")
else()
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
    message(STATUS "LocalMind: KleidiAI off for this ABI")
endif()

add_subdirectory("${LLAMA_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/llama.cpp" EXCLUDE_FROM_ALL)

# Targets exported by llama.cpp: `llama`, `ggml`, `ggml-base`. The GPU backends
# (CUDA/Vulkan/Metal) are irrelevant on Android; ggml always builds its CPU backend.
foreach(_tgt llama ggml ggml-base mtmd)
    if(NOT TARGET ${_tgt})
        message(FATAL_ERROR "llama.cpp did not export the CMake target '${_tgt}'")
    endif()
endforeach()

# ---------------------------------------------------------------------------
# 关掉 llama 的 unity build —— 非 ASCII 路径下它会直接编不过
# ---------------------------------------------------------------------------
# llama.cpp 的 src/CMakeLists.txt 里 `UNITY_BUILD ON` + `BATCH_SIZE 16`：
# 它会把 16 个 .cpp **拼成一个 unity_0_cxx.cxx**，而拼接方式是写
# `#include "<绝对路径>"`。路径里只要有中文（比如本机仓库在
# `N:/Data/big美铝难安/...`），clang 在 Windows 上就拿不到那个文件：
#
#   fatal error: cannot open file 'N:/Data/big<乱码>/.../models/afmoe.cpp'
#
# 报错只有一句 "unspecified system_category error"，看不出是编码问题。
# 关掉 unity 之后每个源文件由 ninja 直接以**相对路径**编译，这条路径就绕开了。
# 代价是编译单元从 ~26 个变成 ~400 个（首次编译慢几分钟，增量编译反而更细）。
set_target_properties(llama PROPERTIES UNITY_BUILD OFF)

# ---------------------------------------------------------------------------
# localmind JNI bridge
# ---------------------------------------------------------------------------
add_library(localmind SHARED
    llama_jni.cpp
    image_jni.cpp
)

target_include_directories(localmind PRIVATE
    "${LLAMA_DIR}/include"
    "${LLAMA_DIR}/ggml/include"
    "${LLAMA_DIR}/tools/mtmd"     # mtmd.h / mtmd-helper.h（识图）
    "${CMAKE_CURRENT_SOURCE_DIR}"
)

target_compile_options(localmind PRIVATE -O3 -fexceptions -frtti -Wall)

# ---------------------------------------------------------------------------
# 生图后端**不在这里**。
#
# 它曾经作为预编译静态库（libstable-diffusion.a）用 --whole-archive 链进
# liblocalmind.so。两个理由让它不适合继续留在这里：
#
#  1. **体积**：把 liblocalmind.so 从 3.5 MB 撑到 41.66 MB。而这个功能只有
#     愿意再下 1.5–6 GB checkpoint 的人用得上 —— 对绝大多数用户是纯粹的负担。
#
#  2. **ABI**：sd.cpp 和 llama.cpp 各带一份 ggml，而两份的 GGML_MAX_NAME
#     不同（160 vs 64）。`char name[GGML_MAX_NAME]` 是 `struct ggml_tensor`
#     的真实字段，所以两边的 sizeof 差 96 字节，`extra` / `view_src` 落在
#     不同偏移上。**不会报链接错误**，只是静默的内存错乱。当时靠全局把宏
#     统一成 160 绕过去了，代价是 llama 跑在 sd.cpp 那份打过补丁的 ggml 上 ——
#     任一方升级 ggml 这个巧合就没了，而且没有任何编译期提示。
#
# 现在它是独立的 liblocalmind-sd.so：用户第一次要真生图时下载到
# filesDir/native/<abi>/，再 System.load（见 NativeLibraryInstaller 与 ImageBridge）。
# bionic 解析符号时先看库自己，所以两个 ggml 各自留在自己的库里，ABI 冲突
# 从结构上消失，宏也不用再统一。
#
# 静态库仍然编（gradlew :app:compileStableDiffusion），只是不再链进主库 ——
# 它是**构建下载包**的中间产物：sd-alone 的 CMake 用同一个归档产出 .so。
#
# 想看现在的状态：gradlew :app:assembleSdPlugin
# ---------------------------------------------------------------------------

target_link_libraries(localmind
    llama
    mtmd
    ggml
    ggml-base
    android
    log
    z
)
