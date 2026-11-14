function bridge(payload) {
  try { if (window.LM && LM.post) LM.post(JSON.stringify(payload)); } catch (e) { console.log(e); }
}
function sendHello() { bridge({ action: 'send', text: '你好，这是从网页插件发出来的消息。' }); }
function fillPrompt() { bridge({ action: 'prompt', text: '用三点总结今天的计划：' }); }
function copyText() { bridge({ action: 'copy', text: 'LocalMind 网页插件示例' }); }
function toastIt() { bridge({ action: 'toast', text: '网页里的按钮也可以调用主程序' }); }
document.addEventListener('dblclick', function (e) { e.preventDefault(); }, { passive: false });
