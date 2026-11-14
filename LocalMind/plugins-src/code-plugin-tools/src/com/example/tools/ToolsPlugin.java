package com.example.tools;

import com.localmind.plugin.PluginContext;
import com.localmind.plugin.PluginEntry;

/**
 * 示例代码插件：用 Java 注册两个聊天动作。
 *
 * 只依赖 com.localmind.plugin 的两个接口（纯 String），不需要 android.jar。
 */
public class ToolsPlugin implements PluginEntry {

    @Override
    public String id() {
        return "com.example.code.tools";
    }

    @Override
    public void onLoad(PluginContext ctx) {
        ctx.registerAction("upper", "转成大写");
        ctx.registerAction("polish", "润色一下");
        ctx.log("ToolsPlugin loaded");
    }

    @Override
    public String onAction(String actionId, String input) {
        String text = input == null ? "" : input.trim();
        if (text.isEmpty()) {
            return "请先在输入框里写点内容，再点这个动作。";
        }
        if ("upper".equals(actionId)) {
            return text.toUpperCase();
        }
        if ("polish".equals(actionId)) {
            return "请把下面这段话润色得更自然、简洁，只输出改好的版本：\n" + text;
        }
        return null;
    }
}
