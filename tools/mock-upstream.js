// mock-upstream.js —— 内测用的假上游，三种协议都实现。
//
// 为什么需要它：验证「多接口兼容 + 转发」不能靠真密钥（内测环境没有，也不该有）。
// 这个 mock 复刻三家协议最关键的形状，并**把收到的请求原样打日志**，
// 于是「我们发出去的 body 对不对」也能直接看到。
//
//   node mock-upstream.js 9099
const http = require('http');
const PORT = Number(process.argv[2] || 9099);

const PNG_B64 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';

function readBody(req) {
  return new Promise((resolve) => {
    let data = '';
    req.on('data', (c) => (data += c));
    req.on('end', () => resolve(data));
  });
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const body = await readBody(req);
  console.log('>>>', req.method, url.pathname + url.search);
  if (body) console.log('    auth=', req.headers.authorization || req.headers['x-api-key'] || '(none)',
                          'ver=', req.headers['anthropic-version'] || '-');
  if (body) console.log('    body=', body.slice(0, 300));

  // 模型列表：三家都用一个形状，够 probe 用
  if (url.pathname.endsWith('/models')) {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ object: 'list', data: [{ id: 'mock-chat' }, { id: 'mock-vision' }] }));
    return;
  }

  // ---- OpenAI 兼容 ----
  if (url.pathname.endsWith('/chat/completions')) {
    const parsed = JSON.parse(body || '{}');
    const hasImage = JSON.stringify(parsed.messages || []).includes('image_url');
    const text = 'MOCK-OPENAI' + (hasImage ? '+VISION' : '') + ':' + (parsed.model || '?');
    if (parsed.stream) {
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' });
      for (const ch of [text.slice(0, 6), text.slice(6)]) {
        res.write('data: ' + JSON.stringify({ choices: [{ delta: { content: ch } }] }) + '\n\n');
      }
      res.write('data: [DONE]\n\n');
      res.end();
    } else {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ choices: [{ message: { role: 'assistant', content: text } }],
                               usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } }));
    }
    return;
  }

  if (url.pathname.endsWith('/images/generations')) {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ created: Math.floor(Date.now() / 1000), data: [{ b64_json: PNG_B64 }] }));
    return;
  }

  if (url.pathname.endsWith('/audio/speech')) {
    const wav = Buffer.from('UklGRiQAAABXQVZFZm10IBAAAAABAAEAESsAABErAAABAAgAZGF0YQAAAAA=', 'base64');
    res.writeHead(200, { 'Content-Type': 'audio/wav' });
    res.end(wav);
    return;
  }

  // ---- Anthropic ----
  if (url.pathname.endsWith('/messages')) {
    const parsed = JSON.parse(body || '{}');
    const hasImage = JSON.stringify(parsed.messages || []).includes('"type":"image"');
    const text = 'MOCK-ANTHROPIC' + (hasImage ? '+VISION' : '') + ':' + (parsed.model || '?');
    if (parsed.stream) {
      res.writeHead(200, { 'Content-Type': 'text/event-stream' });
      res.write('event: content_block_delta\ndata: ' + JSON.stringify({ type: 'content_block_delta', delta: { type: 'text_delta', text: text } }) + '\n\n');
      res.end();
    } else {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ content: [{ type: 'text', text }] }));
    }
    return;
  }

  // ---- Gemini ----
  if (url.pathname.includes(':generateContent') || url.pathname.includes(':streamGenerateContent')) {
    const parsed = JSON.parse(body || '{}');
    const hasImage = JSON.stringify(parsed.contents || []).includes('inline_data');
    const text = 'MOCK-GEMINI' + (hasImage ? '+VISION' : '') + ':' + (url.pathname.match(/models\/([^:]+)/) || [])[1];
    if (url.pathname.includes(':streamGenerateContent')) {
      res.writeHead(200, { 'Content-Type': 'text/event-stream' });
      res.write('data: ' + JSON.stringify({ candidates: [{ content: { parts: [{ text }] } }] }) + '\n\n');
      res.end();
    } else {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ candidates: [{ content: { parts: [{ text }] } }] }));
    }
    return;
  }

  res.writeHead(404, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ error: 'mock: unknown path ' + url.pathname }));
});

server.listen(PORT, '0.0.0.0', () => console.log('mock upstream on http://0.0.0.0:' + PORT));
