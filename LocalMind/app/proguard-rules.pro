# LocalMind keeps R8 disabled (isMinifyEnabled = false) because the JNI bridge and
# Compose runtime are both reflection-sensitive and the APK is shipped by sideload.
# These rules are here for the case where someone flips minification on.

-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.localmind.native.** { *; }

# JNI 的逐 token 回调：接口名、实现类、方法名和签名都必须是原样。
#
# 原生侧按 "onToken" + "(Ljava/lang/String;)V" 反射查找。以前传给原生的是一个
# lambda，而 **R8 会为传给原生的 lambda 合成一个新类，合成发生在 keep 规则之后**，
# 于是规则管不到它、invoke 被抹掉 —— release 包一个 token 都收不到（流式失效），
# 而 debug 包完全正常。这条规则是第二道闸：即使有人把接口搬走，也仍然保住。
-keep interface com.localmind.native.llama.LlamaBridge$TokenSink { *; }
-keep class * implements com.localmind.native.llama.LlamaBridge$TokenSink { *; }

# ---------------------------------------------------------------------------
# sherpa-onnx 的 Kotlin 侧**整个都是 JNI 的 ABI 表面**，必须原样保留
# ---------------------------------------------------------------------------
# libsherpa-onnx-jni.so 不用 JNI 函数表，而是拿类名 / 字段名 / 构造函数签名去反射：
#   * OfflineTts*Config 这一组 data class 的**字段名**（GetFieldID）—— 少一个字段
#     就是 NoSuchFieldError，而它是 Error，ART 会把进程直接终止；
#   * GeneratedAudio 的**构造函数** `([FI)V`（NewObject）。
#
# 这些用法只存在于 .so 里，R8 看不见，于是：
#   * 配置类的字段因为 Kotlin 侧还在用而侥幸留下；
#   * GeneratedAudio 的构造函数**没有任何 Kotlin 调用点**，被整个剪掉。
#
# 实测症状（release / r8debug 包，debug 包看不出来 —— 这也是它一直没被发现的原因）：
#     合成失败：no non-static method "Lcom/k2fsa/sherpa/onnx/GeneratedAudio;.<init>([FI)V"
#   at com.k2fsa.sherpa.onnx.OfflineTts.generateImpl(Native Method)
# 引擎能加载、能报出"1 个说话人 @ 22050 Hz"，但**一句都念不出来** ——
# 合成返回 false，界面静默退回系统 TTS，只在 logcat 留一行 W。
#
# 整个包一起 keep：这里的每一行都是和 .so 的约定，不是内部实现，
# 挑着 keep 迟早会漏掉下一个（这个包本来就只有 184 行）。
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class com.localmind.data.** { *; }
-keep class com.localmind.service.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.localmind.**$$serializer { *; }
-keepclassmembers class com.localmind.** {
    *** Companion;
}
-keepclasseswithmembers class com.localmind.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**


# 插件 API：外部 classes.dex 按这些名字实现/引用，R8 不能改名
-keep interface com.localmind.plugin.** { *; }
-keep class com.localmind.plugin.** { *; }

# 网页插件：@JavascriptInterface 方法必须保留
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
