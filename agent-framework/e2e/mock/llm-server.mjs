#!/usr/bin/env node
/**
 * mock LLM 回放引擎（e2e-ci-plan §4.1.2）：响应体一律来自真实 LLM 录制件回放，
 * 场景标记只做路由；slow/hang 是回放时序包装，不改写 chunk 内容。
 *
 * 端点：
 *   POST /v1/chat/completions   回放（stream: 逐 chunk 原文重发；非 stream: body 原文）
 *   GET  /stats                 调用记录（messages 摘要 + systemContent，M5/S3/F2 断言依据）
 *   POST /reset                 清空调用记录与会话内调用游标
 *   GET  /health
 *
 * 环境：
 *   MOCK_LLM_PORT=18081  MOCK_LLM_FIXTURES=<fixtures/llm 目录>
 *   MOCK_LLM_ALLOW_SYNTH=1（仅本地排障：缺夹具时回落合成响应并打标 synthesized）
 */
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const PORT = Number(process.env.MOCK_LLM_PORT ?? 18081);
const FIXTURES = process.env.MOCK_LLM_FIXTURES
  ?? path.join(path.dirname(fileURLToPath(import.meta.url)), 'fixtures', 'llm');
const ALLOW_SYNTH = process.env.MOCK_LLM_ALLOW_SYNTH === '1';

// 场景标记 → 夹具名（e2e-ci-plan §4.1.3 场景路由表）
const MARKER_MAP = {
  'plain': 'plain', 'remember': 'remember', 'recall': 'recall',
  'tool:echo': 'tool-echo', 'tool:time': 'tool-time', 'tool:write': 'tool-write', 'tool:read': 'tool-read',
  'file:deliver': 'file-deliver', 'tool:mcp_echo': 'tool-mcp-echo',
  'hitl:submit': 'hitl-submit', 'mcpapp:form': 'mcpapp-form',
  'execute': 'execute', 'execute:fail': 'execute-fail',
  'tool:write:sb': 'sandbox-write', 'tool:read:sb': 'sandbox-read',
};

// 需要运行时参数的场景：tool_call arguments 整体重写为指定 JSON（{{appId}} → 标记参数）。
// 录制件中 app id 被切分在多个 chunk 片段里，字符串替换不可行，必须在组装语义层整体覆盖。
const ARGS_OVERRIDE = {
  'hitl-submit': '{"application_id": "{{appId}}"}',
  'mcpapp-form': '{"application_id": "{{appId}}"}',
};

const cache = new Map(); // name → { mtime, fx }
function fixtureOf(name) {
  const file = path.join(FIXTURES, `${name}.json`);
  if (!fs.existsSync(file)) { cache.delete(name); return null; }
  const mtime = fs.statSync(file).mtimeMs;
  const hit = cache.get(name);
  if (hit && hit.mtime === mtime) return hit.fx; // mtime 变化即重读（录制/策展后无需重启 mock）
  const fx = JSON.parse(fs.readFileSync(file, 'utf8'));
  cache.set(name, { mtime, fx });
  return fx;
}

const stats = { calls: [], count: 0 };
const sessionCursor = new Map(); // sessionKey → 已回放调用数（仅日志观察用，索引按请求形状推导）

function textOfContent(content) {
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) return content.map(c => (typeof c === 'text' ? c.text : c?.text ?? '')).join('');
  return '';
}

/** 请求形状 → 夹具调用索引：末条 user → 0；末条 tool → tool 消息条数（≥1） */
function callIndex(messages) {
  const last = messages[messages.length - 1];
  if (!last || last.role === 'user' || last.role === 'system') return 0;
  const n = messages.filter(m => m.role === 'tool').length;
  return Math.max(1, n);
}

function route(reqBody) {
  const messages = reqBody.messages ?? [];
  // 最后一条 user 消息携带场景标记
  let marker = null, arg = null;
  for (let i = messages.length - 1; i >= 0; i--) {
    if (messages[i].role !== 'user') continue;
    const m = String(textOfContent(messages[i].content)).match(/\[E2E:([a-zA-Z:_-]+)\](?:\(([^)]*)\))?/);
    if (m) { marker = m[1]; arg = m[2] ?? null; }
    break;
  }
  const deniedResume = (() => {
    const last = messages[messages.length - 1];
    return last?.role === 'tool' && String(textOfContent(last.content)).includes('Permission denied');
  })();
  const systemContent = messages.filter(m => m.role === 'system').map(m => textOfContent(m.content)).join('\n');
  return { messages, marker, arg, deniedResume, systemContent };
}

function substitute(chunk, placeholders, arg) {
  let out = chunk;
  for (const p of placeholders) {
    if (!p) continue; // 空占位符（录制期 strip 的产物）会污染 split('')，跳过
    out = out.split(p).join(arg ?? '');
  }
  return out;
}

/**
 * tool_call arguments 整体覆盖：首个携带 arguments 的 chunk 写入完整 JSON，
 * 同一 tool_call 的后续参数片段清空（OpenAI 增量协议下语义等价于一次完整传参）。
 */
function applyArgsOverride(chunks, json) {
  const escaped = JSON.stringify(json).slice(1, -1); // 去 JSON.stringify 外层引号，得到字符串字面量内容
  let done = false;
  return chunks.map(ch => ch.replace(/("arguments":")((?:[^"\\]|\\.)*)(")/g, (m, pre, body, post) => {
    if (done) return pre + post;
    done = true;
    return pre + escaped + post;
  }));
}

function synthChunks(text) {
  const mk = content => JSON.stringify({ id: 'synth', object: 'chat.completion.chunk', choices: [{ index: 0, delta: { content }, finish_reason: null }] });
  return [mk(text), JSON.stringify({ id: 'synth', object: 'chat.completion.chunk', choices: [{ index: 0, delta: {}, finish_reason: 'stop' }] }), '[DONE]'];
}

const server = http.createServer((req, res) => {
  if (req.method === 'GET' && req.url === '/health') { res.writeHead(200, { 'content-type': 'application/json' }); return res.end('{"status":"healthy"}'); }
  if (req.method === 'GET' && req.url === '/stats') {
    res.writeHead(200, { 'content-type': 'application/json' });
    return res.end(JSON.stringify({ count: stats.count, calls: stats.calls }));
  }
  if (req.method === 'POST' && req.url === '/reset') {
    stats.calls = []; stats.count = 0; sessionCursor.clear();
    res.writeHead(200, { 'content-type': 'application/json' }); return res.end('{"status":"reset"}');
  }
  if (req.method === 'POST' && req.url === '/v1/chat/completions') {
    const cs = []; let size = 0;
    req.on('data', c => { size += c.length; if (size < 8 * 1024 * 1024) cs.push(c); });
    req.on('end', () => {
      let reqBody = {};
      try { reqBody = JSON.parse(Buffer.concat(cs).toString()); } catch { /* 保持空 */ }
      const { messages, marker, arg, deniedResume, systemContent } = route(reqBody);
      const name = MARKER_MAP[marker];
      // jar 后台调用（记忆提取/会话标题等）：无 system 消息（主对话必带 agent 系统提示）。
      // 这类调用若带场景标记会偷走主对话的 fixture 调用序——一律合成良性响应。
      const isBackground = !messages.some(m => m.role === 'system');
      const isMemoryFlush = isBackground && String(systemContent).includes('memory extraction assistant');
      stats.count += 1;
      stats.calls.push({
        scenario: isBackground ? 'background-synth' : (name ?? marker), arg,
        msgCount: messages.length,
        roles: messages.map(m => m.role),
        systemContent,
        toolCallNames: messages.flatMap(m => (m.tool_calls ?? []).map(t => t?.function?.name)).filter(Boolean),
        hasImageBlock: JSON.stringify(messages).includes('"image_url"') || JSON.stringify(messages).includes('data:image'),
        at: Date.now(),
      });
      if (isBackground) {
        const body = { id: 'memsynth', object: 'chat.completion', model: reqBody.model ?? 'e2e-mock-model', choices: [{ index: 0, message: { role: 'assistant', content: '(无新增记忆)' }, finish_reason: 'stop' }], usage: { prompt_tokens: 10, completion_tokens: 5, total_tokens: 15 } };
        if (reqBody.stream === false) { res.writeHead(200, { 'content-type': 'application/json' }); return res.end(JSON.stringify(body)); }
        res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' });
        res.write(`data: ${JSON.stringify({ id: 'memsynth', object: 'chat.completion.chunk', choices: [{ index: 0, delta: { role: 'assistant', content: '(无新增记忆)' }, finish_reason: null }] })}\n\n`);
        res.write(`data: ${JSON.stringify({ id: 'memsynth', object: 'chat.completion.chunk', choices: [{ index: 0, delta: {}, finish_reason: 'stop' }] })}\n\n`);
        return res.end('data: [DONE]\n\n');
      }

      const sendSSE = async chunks => {
        res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache', connection: 'keep-alive' });
        for (const payload of chunks) {
          res.write(`data: ${payload}\n\n`);
          await new Promise(r => setTimeout(r, 5));
        }
        res.end();
      };

      // slow / hang：plain 夹具的时序包装（不改写内容）
      if (marker === 'slow' || marker === 'hang') {
        const fx = fixtureOf('plain');
        let chunks = (fx?.calls?.[0]?.chunks ?? synthChunks('mock slow/hang 需要夹具')).slice();
        if (marker === 'hang') {
          // 放 2 个 delta 后挂起：保持连接不结束
          res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache', connection: 'keep-alive' });
          chunks.slice(0, 2).forEach(p => res.write(`data: ${p}\n\n`));
          return; // 有意不 end
        }
        const [, chunkMs] = (arg ?? '').split(','); // slow(text,chunkMs,n)：第 2 槽为每 chunk 延迟
        const d = Number(chunkMs) || 150;
        res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache', connection: 'keep-alive' });
        (async () => {
          for (const p of chunks) { res.write(`data: ${p}\n\n`); await new Promise(r => setTimeout(r, d)); }
          res.end();
        })();
        return;
      }

      if (!name) {
        const msg = `missing scenario marker: ${marker ?? '(none)'}`;
        if (!ALLOW_SYNTH) { res.writeHead(500, { 'content-type': 'application/json' }); return res.end(JSON.stringify({ error: msg })); }
        return sendSSE(synthChunks('(synth) ' + msg));
      }
      const fx = fixtureOf(name);
      if (!fx) {
        const msg = `missing fixture: ${name}.json`;
        if (!ALLOW_SYNTH) { res.writeHead(500, { 'content-type': 'application/json' }); return res.end(JSON.stringify({ error: msg })); }
        return sendSSE(synthChunks('(synth) ' + msg));
      }

      // 变体选择：拒绝恢复（末条 tool 含 Permission denied）→ variants.denied
      let call;
      if (deniedResume && fx.variants?.denied?.chunks) call = fx.variants.denied;
      else {
        const idx = callIndex(messages);
        const calls = fx.calls ?? [];
        call = calls[Math.min(idx, calls.length - 1)];
      }
      if (!call) { res.writeHead(500, { 'content-type': 'application/json' }); return res.end(JSON.stringify({ error: `fixture ${name} 无可回放调用` })); }

      // 占位符替换（录制件改写留下的 {{xxx}}）
      const placeholders = fx.rewrites ?? [];
      const argValue = arg ?? '';
      let chunks = call.chunks ?? [];
      chunks = chunks.map(c => (placeholders.length ? substitute(c, placeholders, argValue) : c));
      const overrideJson = ARGS_OVERRIDE[name];
      if (overrideJson) chunks = applyArgsOverride(chunks, overrideJson.replace('{{appId}}', argValue));
      if (reqBody.stream === false) {
        const body = JSON.stringify(call.body ?? {});
        res.writeHead(200, { 'content-type': 'application/json' });
        return res.end(placeholders.length ? substitute(body, placeholders, argValue) : body);
      }
      return sendSSE(chunks);
    });
    return;
  }
  res.writeHead(404, { 'content-type': 'application/json' }); res.end('{"error":"not found"}');
});

server.listen(PORT, '127.0.0.1', () => console.log(`[mock-llm] :${PORT} fixtures=${FIXTURES} allowSynth=${ALLOW_SYNTH}`));
