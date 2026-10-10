/**
 * mock-llm — OpenAI 兼容 mock（并发压测专用，零三方依赖）。
 *
 * 脚本化状态机（确定性，见 concurrency-benchmark-plan.md §4.1）：
 *   最后一条消息 role == "tool" → 收尾轮：返回 "BENCH-OK <工具结果回显>"
 *   最后一条消息 role == "user" → 解析场景标记 [BENCH:plain|file|shell|mcp|slow]
 *     ├─ plain → 直接返回固定文本（单轮，1 次 LLM 调用）
 *     ├─ file/shell/mcp → 返回 tool_calls（write_file / execute / bench_echo）
 *     └─ slow → 长推理流式（issue #87 回归防线）：delta 分片拉满 MOCK_LLM_SLOW_MS
 *        （默认 30s）再收尾，构造确定性「长 LLM 流式」在途 turn，用于 C 档并发
 *        启动延迟断言（agent_start_delay_ms，run-bench.sh 场景 C）
 *
 * 端点：POST /v1/chat/completions（stream 均支持）/ GET /stats / POST /reset
 * 延迟注入：MOCK_LLM_DELAY_MS（默认 0）；slow 场景总时长 MOCK_LLM_SLOW_MS（默认 30000，
 * run-bench.sh 显式导出 12000：缺陷形态下 C=2 排队签名 ≈12s 仍超 10s 门禁阈值，
 * 且与 runner P95_ABORT_MS=30000 保持 2.5× 余量，见 issue #97 问题 14）
 * 数值 env 校验：MOCK_LLM_DELAY_MS / MOCK_LLM_SLOW_MS 必须是 ≥0 的数字，非法即 exit 1
 * （NaN 会让 sleep 立即返回 → slow 场景静默退化为瞬时响应 → 门禁恒过假阴性）
 * 固定 usage：in=200 / out=50
 */
'use strict';

const http = require('http');

// 数值 env 解析：未设/空串用默认；否则必须是非负有限数，非法立即退出（issue #97 问题 14）。
// 此前 parseInt 无校验：'abc' → NaN → sleep(NaN) 立即返回，slow 场景静默退化为瞬时响应
// （实测 0.015s/HTTP 200），C 档「长 LLM 流式」在途 turn 消失 → 启动延迟门禁恒过假阴性，
// 故拒绝启动而非带病降级。PORT 保持 parseInt：绑定失败 listen 回调本就显式报错，非静默。
function readMsEnv(name, def) {
  const raw = process.env[name];
  if (raw === undefined || raw === '') return def;
  const v = Number(raw);
  if (!Number.isFinite(v) || v < 0) {
    console.error(`[mock-llm] 环境变量 ${name}="${raw}" 非法：必须是 ≥0 的毫秒数。` +
      `非法值会使 slow 场景静默退化为立即返回（NaN），启动延迟门禁变恒过假阴性，拒绝启动`);
    process.exit(1);
  }
  return v;
}

const PORT = parseInt(process.env.MOCK_LLM_PORT || '18081', 10);
const DELAY_MS = readMsEnv('MOCK_LLM_DELAY_MS', 0);
const SLOW_MS = readMsEnv('MOCK_LLM_SLOW_MS', 30000);
const MODEL = 'bench-model';

// ===== 统计（/stats 供与压测端对账） =====
const stats = {
  total: 0,
  byScenario: { plain: 0, file: 0, shell: 0, mcp: 0, slow: 0, finalize: 0 },
  latencyMs: [],
};

function reset() {
  stats.total = 0;
  stats.byScenario = { plain: 0, file: 0, shell: 0, mcp: 0, slow: 0, finalize: 0 };
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
  const m = /\[BENCH:(plain|file|shell|mcp|slow)\]/.exec(text || '');
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
    if (!toolCalls) text = scenario === 'slow' ? 'BENCH-SLOW-OK' : PLAIN_TEXT;
  }

  // slow 场景非流式：整体 sleep 模拟长推理（流式走下方分片分支）
  await sleep(DELAY_MS + (scenario === 'slow' && !stream ? SLOW_MS : 0));

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
    if (scenario === 'slow' && !toolCalls) {
      // 长推理流式：delta 分片拉满 SLOW_MS 再收尾（确定性「长 LLM 流式」在途 turn）
      const slices = 20;
      const per = Math.floor(SLOW_MS / slices);
      emit(chunk(id, created, { role: 'assistant' }, null));
      for (let i = 0; i < slices; i++) {
        await sleep(per);
        emit(chunk(id, created, { content: 'BENCH-SLOW-DELTA ' }, null));
      }
      emit(chunk(id, created, { content: 'BENCH-SLOW-OK' }, null));
      emit(chunk(id, created, {}, finish));
    } else if (toolCalls) {
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
  console.log(`[mock-llm] listening on 0.0.0.0:${PORT} (delay=${DELAY_MS}ms, slow=${SLOW_MS}ms)`);
});
