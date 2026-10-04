#!/usr/bin/env node
/**
 * mock 上游 LLM（OpenAI 兼容子集）：collector 冒烟/e2e 的离线回退上游。
 * - POST /v1/chat/completions：stream=true 逐 chunk SSE；false 返回完整 body
 * - 按末条 user 消息包含的关键词返回固定应答（echo:<文本> → 原样回显）
 * - GET /stats：调用计数；POST /reset 清零
 * 零依赖，Node >= 18。用法：node mock-upstream-llm.mjs [port]
 */
import http from 'node:http';

const PORT = parseInt(process.argv[2] || process.env.MOCK_UPSTREAM_PORT || '18900', 10);
let calls = 0;

function json(res, code, obj) {
  res.writeHead(code, { 'content-type': 'application/json' });
  res.end(JSON.stringify(obj));
}

const server = http.createServer((req, res) => {
  if (req.method === 'GET' && req.url === '/stats') return json(res, 200, { calls });
  if (req.method === 'POST' && req.url === '/reset') { calls = 0; return json(res, 200, { ok: true }); }
  if (req.method === 'GET' && req.url === '/v1/models') {
    return json(res, 200, { object: 'list', data: [{ id: 'mock-record-model', object: 'model' }] });
  }
  // 沙箱协议 mock（collector e2e 用）：创建沙箱返回固定 id
  if (req.method === 'POST' && req.url === '/v1/sandboxes') {
    return json(res, 200, { id: 'sbx-mock-001', status: 'running', endpoints: {} });
  }
  // MCP streamableHttp mock（collector e2e 用）：JSON-RPC 通用回声
  if (req.url.startsWith('/mcp')) {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      let rpc = {};
      try { rpc = JSON.parse(Buffer.concat(chunks).toString('utf-8')); } catch { /* 按空 */ }
      return json(res, 200, { jsonrpc: '2.0', id: rpc.id ?? null,
        result: { content: [{ type: 'text', text: `mock-echo:${rpc.method}` }] } });
    });
    return;
  }
  if (req.method === 'POST' && req.url === '/v1/chat/completions') {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      calls += 1;
      let body = {};
      try { body = JSON.parse(Buffer.concat(chunks).toString('utf-8')); } catch { /* 按空处理 */ }
      const lastUser = [...(body.messages || [])].reverse().find((m) => m.role === 'user');
      const text = typeof lastUser?.content === 'string' ? lastUser.content : JSON.stringify(lastUser?.content ?? '');
      // echo: 前缀 → 原样回显（供确定性断言）；否则通用应答
      const em = text.match(/echo[:：]\s*(.+)/);
      const answer = em ? em[1].trim() : `这是针对「${text.slice(0, 30)}」的 mock 应答。`;
      const id = `chatcmpl-mock-${Date.now()}`;
      if (body.stream) {
        res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' });
        const pieces = answer.match(/.{1,6}/gs) || [''];
        const send = (obj) => res.write(`data: ${JSON.stringify(obj)}\n\n`);
        let i = 0;
        const timer = setInterval(() => {
          if (i < pieces.length) {
            send({ id, object: 'chat.completion.chunk', created: Math.floor(Date.now() / 1000),
              model: body.model || 'mock-record-model',
              choices: [{ index: 0, delta: { content: pieces[i] }, finish_reason: null }] });
            i += 1;
          } else {
            clearInterval(timer);
            send({ id, object: 'chat.completion.chunk', created: Math.floor(Date.now() / 1000),
              model: body.model || 'mock-record-model', choices: [{ index: 0, delta: {}, finish_reason: 'stop' }] });
            res.write('data: [DONE]\n\n');
            res.end();
          }
        }, 5);
        // 注意：Node 15+ 的 req 'close' 在请求体接收完成即触发（非连接关闭），
        // 挂在 req 上会把刚启动的 SSE 定时器立刻清掉——必须挂 res 的连接级 close
        res.on('close', () => clearInterval(timer));
      } else {
        json(res, 200, {
          id, object: 'chat.completion', created: Math.floor(Date.now() / 1000),
          model: body.model || 'mock-record-model',
          choices: [{ index: 0, message: { role: 'assistant', content: answer }, finish_reason: 'stop' }],
          usage: { prompt_tokens: 10, completion_tokens: 5, total_tokens: 15 },
        });
      }
    });
    return;
  }
  json(res, 404, { error: 'not found' });
});

server.listen(PORT, '127.0.0.1', () => console.log(`[mock-upstream-llm] http://127.0.0.1:${PORT}/v1`));
