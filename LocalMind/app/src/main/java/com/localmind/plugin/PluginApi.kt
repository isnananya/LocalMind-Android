package com.localmind.plugin

/**
 * 代码插件的入口接口。
 *
 * 插件作者写一个类实现它，把 classes.dex 放进 zip，再在 plugin.json 里写
 * `"config": { "entryClass": "com.example.MyPlugin" }`。
 *
 * 这个接口**不依赖 Android**（只有 String），所以插件可以用纯 javac 编译，
 * 不需要把 android.jar 拖进工程。能做的事情刻意收得很窄：
 *  * 读输入、返回一段提示词（交给聊天发送）；
 *  * 通过 [PluginContext] 打日志 / toast / 注册聊天动作。
 *
 * 不做 View / Context 注入：那会把"插件"变成"主程序内部的代码"，
 * 出问题时无法收敛。需要换整页 UI 的，用 ui_layout 的 screens/overrides。
 */
interface PluginEntry {
    /** 稳定 id，建议和 plugin.json 的 id 一致。 */
    fun id(): String

    /** 加载时调用一次；在这里注册动作。 */
    fun onLoad(ctx: PluginContext)

    /**
     * 用户点了这个插件注册的动作。
     *
     * @param actionId 动作 id
     * @param input    当前输入框里的文字（可能为空）
     * @return 要发送的文本；返回 null 表示不发送
     */
    fun onAction(actionId: String, input: String): String?
}

/** 插件能看到的主程序能力（刻意很小）。 */
interface PluginContext {
    fun log(message: String)
    fun toast(message: String)
    fun registerAction(id: String, label: String)
}
