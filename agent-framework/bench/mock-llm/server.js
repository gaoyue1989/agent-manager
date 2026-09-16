/**
 * mock-llm — OpenAI 兼容 mock（并发压测专用，零三方依赖）。
 *
 * 脚本化状态机（确定性，见 concurrency-benchmark-plan.md §4.1）：
 *   最后一条消息 role == "tool" → 收尾轮：返回 "BENCH-OK <工具结果回显>"
 *   最后一条消息 role == "user" → 解析场景标记 [BENCH:plain|file|shell|mcp]
 *     ├─ plain → 直接返回固定文本（单轮，1 次 LLM 调用）
 *     └─ file/shell/mcp → 返回 tool_calls（write_file / execute / bench_echo）
 *
 * 端点：POST /v1/chat/completions（stream 均支持）/ GET /stats / POST /reset
 * 延迟注入：MOCK_LLM_DELAY_MS（默认 0）
 * 固定 usage：in=200 / out=50
 */
'use strict';

const http = require('http');

const PORT = parseInt(process.env.MOCK_LLM_PORT || '18081', 10);
const DELAY_MS = parseInt(process.env.MOCK_LLM_DELAY_MS || '0', 10);
const MODEL = 'bench-model';

// ===== 统计（/stats 供与压测端对账） =====
const stats = {
  total: 0,
  byScenario: { plain: 0, file: 0, shell: 0, mcp: 0, finalize: 0 },
  latencyMs: [],
};

function reset() {
  stats.total = 0;
  stats.byScenario = { plain: 0, file: 0, shell: 0, mcp: 0, finalize: 0 };
  stats.latencyMs = [];
}

function percentile(arr, p) {
  if (!arr.length) return 0;
  const sorted = [...arr].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.floor((p / 100) * sorted.length))];
}

// ===== 请求解析 =====

function lastMessage(messages) {
  return messages && messages.length ? messages[messages.length - 1] : null;
}

function messageText(msg) {
  if (!msg) return '';
  if (typeof msg.content === 'string') return msg.content;
  if (Array.isArray(msg.content)) {
    return msg.content.map((b) => (typeof b === 'string' ? b : b.text || b.content || '')).join('');
  }
  return '';
}

/** 从用户消息解析场景与 sessionId："[BENCH:file] session=bench-B1-000001 ..." */
function parseScenario(text) {
  const m = /\[BENCH:(plain|file|shell|mcp)\]/.exec(text || '');
  const scenario = m ? m[1] : 'plain';
  const sm = /session=([A-Za-z0-9_-]+)/.exec(text || '');
  return { scenario, sid: sm ? sm[1] : 'nosid' };
}

// ===== 响应构造 =====

const USAGE = { prompt_tokens: 200, completion_tokens: 50, total_tokens: 250 };

/** 场景 → tool_calls 参数（工具名/参数名与 harness 2.0.0 实际 schema 对齐） */
function toolCallFor(scenario, sid) {
  const id = 'call_bench_' + sid;
  switch (scenario) {
    case 'file':
      return [{
        id, type: 'function',
        function: {
          name: 'write_file',
          arguments: JSON.stringify({ path: `bench/${sid}.txt`, content: 'BENCH-FILE-CONTENT\n' }),
        },
      }];
    case 'shell':
      return [{
        id, type: 'function',
        function: {
          name: 'execute',
          arguments: JSON.stringify({ command: "python3 -c \"print('BENCH-SHELL-OK')\"" }),
        },
      }];
    case 'mcp':
      return [{
        id, type: 'function',
        function: { name: 'bench_echo', arguments: JSON.stringify({ text: 'ping' }) },
      }];
    default:
      return null;
  }
}

const PLAIN_TEXT = 'BENCH-PLAIN-OK';

/** 收尾轮文本：回显工具结果标记（截断防超长） */
function finalizeText(toolContent) {
  const echo = String(toolContent || '').replace(/\s+/g, ' ').trim().slice(0, 120);
  return 'BENCH-OK ' + echo;
}

function sleep(ms) {
  return ms > 0 ? new Promise((r) => setTimeout(r, ms)) : Promise.resolve();
}

function chunk(id, created, delta, finish) {
  return JSON.stringify({
    id, object: 'chat.completion.chunk', created, model: MODEL,
    choices: [{ index: 0, delta, finish_reason: finish ?? null }],
  });
}

async function respond(reqBody, res) {
  const started = Date.now();
  const stream = !!reqBody.stream;
  const id = 'chatcmpl-bench-' + Date.now() + '-' + (stats.total + 1);
  const created = Math.floor(started / 1000);
  const last = lastMessage(reqBody.messages);
  const role = last ? last.role : 'user';

  let scenario = null;
  let text;
  let toolCalls = null;
  let finish;

  if (role === 'tool') {
    stats.byScenario.finalize++;
    text = finalizeText(messageText(last));
    finish = 'stop';
  } else {
    const parsed = parseScenario(messageText(last));
    scenario = parsed.scenario;
    stats.byScenario[scenario] = (stats.byScenario[scenario] || 0) + 1;
    toolCalls = toolCallFor(scenario, parsed.sid);
    finish = toolCalls ? 'tool_calls' : 'stop';
    if (!toolCalls) text = PLAIN_TEXT;
  }

  await sleep(DELAY_MS);

  if (!stream) {
    const message = toolCalls
      ? { role: 'assistant', content: null, tool_calls: toolCalls }
      : { role: 'assistant', content: text };
    writeJson(res, 200, {
      id, object: 'chat.completion', created, model: MODEL,
      choices: [{ index: 0, message, finish_reason: finish }],
      usage: USAGE,
    });
  } else {
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
    });
    const emit = (obj) => res.write(`data:${obj}\n\n`);
    if (toolCalls) {
      // 首块：role + 工具名；次块：完整 arguments；末块：finish_reason
      const head = { ...toolCalls[0], function: { name: toolCalls[0].function.name, arguments: '' } };
      emit(chunk(id, created, { role: 'assistant', tool_calls: [head] }, null));
      emit(chunk(id, created, {
        tool_calls: [{ index: 0, function: { arguments: toolCalls[0].function.arguments } }],
      }, null));
      emit(chunk(id, created, {}, finish));
    } else {
      // 文本按 4 块吐出，覆盖流式分支
      const n = 4;
      const size = Math.ceil(text.length / n);
      emit(chunk(id, created, { role: 'assistant' }, null));
      for (let i = 0; i < text.length; i += size) {
        emit(chunk(id, created, { content: text.slice(i, i + size) }, null));
      }
      emit(chunk(id, created, {}, finish));
    }
    if (reqBody.stream_options && reqBody.stream_options.include_usage) {
      res.write(`data:${JSON.stringify({ id, object: 'chat.completion.chunk', created, model: MODEL, choices: [], usage: USAGE })}\n\n`);
    }
    res.write('data:[DONE]\n\n');
    res.end();
  }

  stats.total++;
  stats.latencyMs.push(Date.now() - started);
}

function writeJson(res, code, obj) {
  res.writeHead(code, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(obj));
}

const server = http.createServer((req, res) => {
  if (req.method === 'POST' && (req.url === '/v1/chat/completions' || req.url === '/chat/completions')) {
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => {
      try {
        respond(JSON.parse(body || '{}'), res);
      } catch (e) {
        writeJson(res, 400, { error: { message: 'bad json: ' + e.message } });
      }
    });
    return;
  }
  if (req.method === 'GET' && req.url === '/stats') {
    writeJson(res, 200, {
      total: stats.total,
      byScenario: stats.byScenario,
      p50Ms: percentile(stats.latencyMs, 50),
      p95Ms: percentile(stats.latencyMs, 95),
    });
    return;
  }
  if (req.method === 'POST' && req.url === '/reset') {
    reset();
    writeJson(res, 200, { ok: true });
    return;
  }
  if (req.method === 'GET' && req.url === '/health') {
    writeJson(res, 200, { status: 'healthy' });
    return;
  }
  writeJson(res, 404, { error: 'not found' });
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`[mock-llm] listening on 0.0.0.0:${PORT} (delay=${DELAY_MS}ms)`);
});
