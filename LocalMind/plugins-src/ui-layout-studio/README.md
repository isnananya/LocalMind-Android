# 示例：控制台首页

演示 `ui_layout` 的两个新能力（1.0.5）：

* `screens`：插件自己声明一整页，由主程序渲染；
* `overrides`：把某个内置页面（home / chat / models / me）整页换成插件那一页。

安装这个插件后，底栏第一个标签「控制台」显示的就是 `screens.console`，
主程序的默认首页代码完全不会被调用。

## 改它

1. 改 `layout.json`；
2. 把两个文件压成 zip（根目录就是这两个文件，不要多套一层文件夹）；
3. 应用内「我的 → 插件 → 导入」。

## 能用的块

| type | 作用 | 关键字段 |
| --- | --- | --- |
| hero | 顶部大卡 | title / text / icon |
| actions | 两列快捷入口 | items[] |
| list | 分组列表 | items[]（每项可用 subtitle） |
| recent | 最近对话 | limit |
| status | 本机状态 | title |
| text | 一段说明 | title / text |

items 里的 `open` 可以是页面名（chat / models / me / home / voice / breeze / plugins / image / pipeline）、
动作名（new / settings / plugins_import / debug）、`screens` 里另一页的 id、或一个 http(s) 链接。
