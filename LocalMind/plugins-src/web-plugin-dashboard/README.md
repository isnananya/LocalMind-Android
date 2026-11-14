# 示例：网页首页

用 HTML/CSS/JS 写整页，通过 WebView 宿主。

* `web/index.html` / `style.css` / `app.js`
* `layout.json` 把 home override 成 `web:com.example.web.dashboard`
* JS 桥：`LM.post(JSON.stringify({action:'send'|'prompt'|'toast'|'copy', text:'...'}))`

改完重新压 zip（plugin.json + layout.json + web/）再导入。
