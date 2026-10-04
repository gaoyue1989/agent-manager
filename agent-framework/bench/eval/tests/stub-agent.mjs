#!/usr/bin/env node
/**
 * stub 业务 Agent：模拟 agent-framework 的对外协议面（/threads/chat SSE 帧方言），
 * 作为录制/回放 e2e 的被测服务——它把用户消息原样转发给 LLM（经 collector 或 replay-llm），
 * 并按帧词表契约发出事件帧（见 config/frame-mapping.json）。
 *
 * 帧序（对齐 /threads/chat 真实方言）：
 *   session_created → AGENT_START → MODEL_CALL_START → [TEXT_BLOCK_START …DELTA…END]
 *   → MODEL_CALL_END(usage) → AGENT_END
 *
 * 环境变量：
 *   STUB_LLM_BASE_URL   LLM base（collector: http://collector:18200/{ns}/v1）
 *   STUB_LLM_API_KEY    透传给上游的 key（可占位，collector 档案配置了 llm_api_key 时被覆盖）
 *   STUB_LLM_MODEL      请求 model（可空，触发 collector 档案的默认模型注入）
 *   STUB_PORT           监听端口（默认 18901）
 * 零依赖，Node >= 18。
 */
import http from 'node:http';

const PORT = parseInt(process.argv[2] || process.env.STUB_PORT || '18901', 10);
const LLM_BASE = (process.env.STUB_LLM_BASE_URL || 'http://127.0.0.1:18200/demo/v1').replace(/\/$/, '');
const LLM_KEY = process.env.STUB_LLM_API_KEY || 'stub-dummy-key';
const LLM_MODEL = process.env.STUB_LLM_MODEL || '';
// 漂移注入（e2e 金标验证用）：开启后修改发往 LLM 的消息 → 与录制请求形状失配
const MUTATE = process.env.STUB_MUTATE === '1';

let turnSeq = 0; // 每轮 turn 递增，session 只在内存（stub 无持久化）

function sse(res, obj) { res.write(`data: ${JSON.stringify(obj)}\n\n`); }

/** 调 LLM：stream=true，返回 (拼接文本, usage)。session 头透传给上游（采集强关联）。 */
async function callLLM(message, sessionId) {
  const resp = await fetch(`${LLM_BASE}/chat/completions`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${LLM_KEY}`,
               ...(sessionId ? { 'x-eval-session': sessionId } : {}) },
    body: JSON.stringify({
      ...(LLM_MODEL ? { model: LLM_MODEL } : {}),
      stream: true,
      messages: [{ role: 'user', content: message }],
    }),
  });
  if (!resp.ok) throw new Error(`LLM ${resp.status}: ${(await resp.text()).slice(0, 200)}`);
  const reader = resp.body.getReader();
  const dec = new TextDecoder();
  let buf = '', text = '', usage = null;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buf += dec.decode(value, { stream: true });
    let idx;
    while ((idx = buf.indexOf('\n\n')) !== -1) {
      const ev = buf.slice(0, idx); buf = buf.slice(idx + 2);
      for (const line of ev.split('\n')) {
        if (!line.startsWith('data:')) continue;
        const payload = line.slice(5).trim();
        if (!payload || payload === '[DONE]') continue;
        try {
          const j = JSON.parse(payload);
          const d = j.choices?.[0]?.delta?.content;
          if (typeof d === 'string') text += d;
          if (j.usage) usage = j.usage;
        } catch { /* 忽略坏帧 */ }
      }
    }
  }
  return { text, usage };
}

const server = http.createServer(async (req, res) => {
  if (req.method === 'POST' && req.url === '/threads/chat') {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', async () => {
      let payload = {};
      try { payload = JSON.parse(Buffer.concat(chunks).toString('utf-8')); } catch { /* 忽略 */ }
      const message = payload.message ?? '';
      turnSeq += 1;
      const sessionId = payload.sessionId || `stub-sess-${String(turnSeq).padStart(4, '0')}`;
      res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' });
      const t0 = Date.now();
      sse(res, { type: 'session_created', session_id: sessionId });
      sse(res, { type: 'AGENT_START' });
      sse(res, { type: 'MODEL_CALL_START' });
      sse(res, { type: 'TEXT_BLOCK_START' });
      try {
        const outMessage = MUTATE ? `${message} [mutated-行为变更注入]` : message;
        const { text, usage } = await callLLM(outMessage, sessionId);
        // 模拟流式：按 6 字符切片发 TEXT_BLOCK_DELTA
        for (const piece of (text.match(/.{1,6}/gs) || [])) {
          sse(res, { type: 'TEXT_BLOCK_DELTA', delta: piece });
        }
        sse(res, { type: 'TEXT_BLOCK_END' });
        sse(res, { type: 'MODEL_CALL_END',
          inputTokens: usage?.prompt_tokens ?? 10, outputTokens: usage?.completion_tokens ?? 5,
          totalTokens: usage?.total_tokens ?? 15 });
        sse(res, { type: 'AGENT_END' });
      } catch (e) {
        sse(res, { type: 'TEXT_BLOCK_END' });
        sse(res, { type: 'error', error: String(e.message || e) });
      }
      res.end(`: done ${Date.now() - t0}ms\n\n`);
    });
    return;
  }
  if (req.method === 'GET' && req.url === '/healthz') {
    res.writeHead(200, { 'content-type': 'application/json' });
    return res.end(JSON.stringify({ ok: true }));
  }
  res.writeHead(404, { 'content-type': 'application/json' });
  res.end(JSON.stringify({ error: 'not found' }));
});

server.listen(PORT, '127.0.0.1', () =>
  console.log(`[stub-agent] http://127.0.0.1:${PORT}  llm=${LLM_BASE} model=${LLM_MODEL || '(缺省→collector注入)'}`));
