#!/usr/bin/env node
/**
 * mock 上游 LLM（OpenAI 兼容子集）：collector 冒烟/e2e 的离线回退上游。
 * - POST /v1/chat/completions：stream=true 逐 chunk SSE；false 返回完整 body
 * - POST /threads/chat：业务 SSE 真实形态（帧方言对齐 stub-agent.mjs，data: [DONE] 收尾），
 *   供 collector agent 口（:18203）录制/透传用例覆盖 SSE 分支
 * - POST /v1/echo：请求体回显（received_len/json_ok），供"超录制上限转发完整体/超硬上限 413
 *   不转发"用例断言；命中计数经 GET /stats 的 echo_calls 查看
 * - 按末条 user 消息包含的关键词返回固定应答（echo:<文本> → 原样回显）
 * - GET /stats：调用计数；POST /reset 清零
 * 零依赖，Node >= 18。用法：node mock-upstream-llm.mjs [port]
 */
import http from 'node:http';

const PORT = parseInt(process.argv[2] || process.env.MOCK_UPSTREAM_PORT || '18900', 10);
let calls = 0;
let echoCalls = 0; // /v1/echo 命中计数（e2e 断言"413 不转发"用：超硬上限请求不得到达上游）

function json(res, code, obj) {
  res.writeHead(code, { 'content-type': 'application/json' });
  res.end(JSON.stringify(obj));
}

const server = http.createServer((req, res) => {
  const p = req.url.split('?')[0]; // 精确匹配路由剥查询串（collector e2e 验证查询串原样透传）
  if (req.method === 'GET' && p === '/stats') return json(res, 200, { calls, echo_calls: echoCalls });
  if (req.method === 'POST' && p === '/reset') { calls = 0; echoCalls = 0; return json(res, 200, { ok: true }); }
  if (req.method === 'GET' && p === '/v1/models') {
    return json(res, 200, { object: 'list', data: [{ id: 'mock-record-model', object: 'model' }] });
  }
  // 沙箱协议 mock（collector e2e 用）：创建沙箱返回固定 id
  if (req.method === 'POST' && p === '/v1/sandboxes') {
    return json(res, 200, { id: 'sbx-mock-001', status: 'running', endpoints: {} });
  }
  // 请求体回显（问题6 回归用）：上报收到的字节数与 JSON 可解析性，供断言"转发体完整"
  if (req.method === 'POST' && req.url === '/v1/echo') {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      echoCalls += 1;
      const raw = Buffer.concat(chunks);
      let jsonOk = true;
      try { JSON.parse(raw.toString('utf-8')); } catch { jsonOk = false; }
      return json(res, 200, { received_len: raw.length, json_ok: jsonOk });
    });
    return;
  }
  // 业务服务反代目标（collector e2e 用）：/threads/* 与 /health
  if (req.method === 'GET' && p === '/health') {
    return json(res, 200, { ok: true });
  }
  if (p.startsWith('/threads/')) {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      const sid = p.match(/^\/threads\/([^/]+)/)?.[1] || 'unknown';
      if (req.method === 'POST' && p === '/threads/chat') {
        let b = {}; try { b = JSON.parse(Buffer.concat(chunks).toString('utf-8')); } catch { /* 忽略 */ }
        // 业务 /threads/chat 真实形态是 SSE（Spring TEXT_EVENT_STREAM）——按 stub-agent.mjs
        // 帧序吐事件流，data: [DONE] 收尾（collector attachSSECollector 以 [DONE] 为语义结束）
        const sessionId = b.sessionId || sid;
        const text = b.message ?? '';
        res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' });
        const sse = (obj) => res.write(`data: ${JSON.stringify(obj)}\n\n`);
        sse({ type: 'session_created', session_id: sessionId });
        sse({ type: 'AGENT_START' });
        sse({ type: 'MODEL_CALL_START' });
        sse({ type: 'TEXT_BLOCK_START' });
        for (const piece of (text.match(/.{1,6}/gs) || [])) {
          sse({ type: 'TEXT_BLOCK_DELTA', delta: piece });
        }
        sse({ type: 'TEXT_BLOCK_END' });
        sse({ type: 'MODEL_CALL_END', inputTokens: 10, outputTokens: 5, totalTokens: 15 });
        sse({ type: 'AGENT_END' });
        res.write('data: [DONE]\n\n');
        return res.end();
      }
      return json(res, 200, { sessionId: decodeURIComponent(sid), status: 'idle' });
    });
    return;
  }
  // MCP streamableHttp mock（collector e2e 用）：JSON-RPC 通用回声
  if (p.startsWith('/mcp')) {
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
  if (req.method === 'POST' && p === '/v1/chat/completions') {
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
