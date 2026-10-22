#!/usr/bin/env node
// CI 专用 OpenAI 兼容 mock LLM（零依赖，node:22-alpine 直接运行）。
// 目的：让模型连接 test/verify、Agent 创建与 /models 下发链路在 CI 中不依赖真实
// LLM 密钥与外网。只实现平台/运行时用到的最小面：
//   GET  /health
//   GET  /v1/models
//   POST /v1/chat/completions（支持 stream=true 的 SSE 与普通 JSON）
//   GET  /v1/models/{id}
import http from 'node:http';

const PORT = Number(process.env.PORT || 8080);
const MODEL = process.env.MOCK_MODEL || 'e2e-mock';

function sendJSON(res, status, body) {
  const data = JSON.stringify(body);
  res.writeHead(status, {
    'Content-Type': 'application/json',
    'Content-Length': Buffer.byteLength(data),
  });
  res.end(data);
}

function textOfContent(content) {
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) return content.map((c) => c?.text ?? '').join('');
  return '';
}

// 回显 system prompt 中的关键标记：m3 的「提示词热生效」断言依赖回答含 v2。
function replyText(body) {
  const messages = body.messages || [];
  const text = messages.map((m) => textOfContent(m.content)).join('\n');
  if (text.includes('v2')) return 'v2';
  return 'E2E mock reply';
}

function completionBody(model, text) {
  return {
    id: 'chatcmpl-e2e',
    object: 'chat.completion',
    created: Math.floor(Date.now() / 1000),
    model,
    choices: [{
      index: 0,
      message: { role: 'assistant', content: text },
      finish_reason: 'stop',
    }],
    usage: { prompt_tokens: 8, completion_tokens: 4, total_tokens: 12 },
  };
}

function streamCompletion(res, model, text) {
  res.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
  });
  const chunk = (delta, finish = null) => ({
    id: 'chatcmpl-e2e',
    object: 'chat.completion.chunk',
    created: Math.floor(Date.now() / 1000),
    model,
    choices: [{ index: 0, delta, finish_reason: finish }],
  });
  res.write(`data: ${JSON.stringify(chunk({ role: 'assistant', content: '' }))}\n\n`);
  for (const part of [text]) {
    res.write(`data: ${JSON.stringify(chunk({ content: part }))}\n\n`);
  }
  res.write(`data: ${JSON.stringify(chunk({}, 'stop'))}\n\n`);
  res.write('data: [DONE]\n\n');
  res.end();
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);

  if (req.method === 'GET' && url.pathname === '/health') {
    return sendJSON(res, 200, { ok: true });
  }

  if (req.method === 'GET' && url.pathname === '/v1/models') {
    return sendJSON(res, 200, {
      object: 'list',
      data: [{ id: MODEL, object: 'model', owned_by: 'e2e' }],
    });
  }

  if (req.method === 'GET' && url.pathname.startsWith('/v1/models/')) {
    return sendJSON(res, 200, { id: MODEL, object: 'model', owned_by: 'e2e' });
  }

  if (req.method === 'POST' && url.pathname === '/v1/chat/completions') {
    let raw = '';
    req.on('data', (c) => { raw += c; });
    req.on('end', () => {
      let body = {};
      try { body = raw ? JSON.parse(raw) : {}; } catch { /* 非法 JSON 也返回 200，保持测试确定性 */ }
      const model = body.model || MODEL;
      const text = replyText(body);
      if (body.stream) return streamCompletion(res, model, text);
      return sendJSON(res, 200, completionBody(model, text));
    });
    return;
  }

  sendJSON(res, 404, { error: { message: `not found: ${req.method} ${url.pathname}` } });
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`mock-llm listening on :${PORT} (model=${MODEL})`);
});
