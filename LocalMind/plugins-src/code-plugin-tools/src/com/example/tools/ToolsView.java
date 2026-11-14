package com.example.tools;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 代码插件提供的整页 View。
 *
 * 这是"替换源码"的最小示例：这个页面完全由插件里的 Java 类绘制，
 * 主程序只负责把它塞进 home 标签。
 */
public class ToolsView extends LinearLayout {

    public ToolsView(Context ctx) {
        super(ctx);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setPadding(72, 140, 72, 72);
        setBackgroundColor(Color.parseColor("#F3F6FA"));

        TextView title = new TextView(ctx);
        title.setText("代码插件页面");
        title.setTextSize(30);
        title.setTextColor(Color.parseColor("#0D1320"));
        title.setGravity(Gravity.CENTER);
        addView(title);

        TextView sub = new TextView(ctx);
        sub.setText("这一页由 classes.dex 里的 Java 类绘制\n主程序只提供宿主，不写这里的布局");
        sub.setTextSize(15);
        sub.setTextColor(Color.parseColor("#5A6472"));
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, 24, 0, 48);
        addView(sub);

        final TextView counter = new TextView(ctx);
        counter.setText("点击次数：0");
        counter.setTextSize(18);
        counter.setTextColor(Color.parseColor("#2E6BE6"));
        counter.setGravity(Gravity.CENTER);
        counter.setPadding(0, 0, 0, 32);
        addView(counter);

        Button button = new Button(ctx);
        button.setText("点我一下");
        button.setAllCaps(false);
        button.setOnClickListener(new OnClickListener() {
            private int n = 0;

            @Override
            public void onClick(View v) {
                n++;
                counter.setText("点击次数：" + n);
            }
        });
        addView(button, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }
}
