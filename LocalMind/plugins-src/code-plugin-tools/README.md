# 示例：代码插件（classes.dex）

这个插件里没有 JSON 数据能力，只有一份 **classes.dex**：安装时会提示
「可执行代码，谨慎安装」，确认后由 DexClassLoader 加载。

还会提供一个整页 Java View（home 标签），由 `layout.json` 的 `code:` override 挂上去。

加载后注册两个聊天动作：

* 转成大写：把输入框里的文字转大写后发送；
* 润色一下：把输入框内容套进润色提示词发送。

## 自己编一个

1. 实现 `com.localmind.plugin.PluginEntry`（只有 3 个方法，纯 String）；
2. javac 编译后 d8 成 `classes.dex`：

```bash
javac -d out src/com/example/tools/ToolsPlugin.java
d8 --lib $ANDROID_HOME/platforms/android-35/android.jar --min-api 26 --output dex out/com/example/tools/*.class
```

3. zip 根目录放 `plugin.json` + `classes.dex`，导入。

## 整页 View

`ToolsView` 继承 LinearLayout，`(Context)` 构造器；清单里的 `config.viewClass` 指向它。
`layout.json` 把 home override 成 `code:com.example.code.tools`。

## 边界

代码插件能拿到的是 `PluginContext`：日志 / toast / 注册动作。
没有 Context、没有 View 注入 —— 换整页 UI 用 `ui_layout` 的 screens/overrides，
这样"改界面"和"跑代码"是两件可分别审计的事。
