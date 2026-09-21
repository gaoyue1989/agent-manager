/**
 * mock-mcp — streamableHttp MCP server（并发压测专用，零三方依赖）。
 *
 * JSON-RPC 方法（见 concurrency-benchmark-plan.md §4.2）：
 *   initialize      → 标准 capabilities(tools)
 *   tools/list      → 1 个工具 bench_echo(text)
 *   tools/call      → "BENCH-MCP-OK:" + text（延迟 <1ms）
 *
 * 响应编码：默认 application/json 单响应；客户端 Accept 含 text/event-stream
 * 时以 SSE 单事件包裹同一结果（两种模式同一 handler，联调取可用者）。
 * 端点：POST /mcp / GET /stats / POST /reset / GET /last-call / GET /health
 */
'use strict';

const http = require('http');
const crypto = require('crypto');

const PORT = parseInt(process.env.MOCK_MCP_PORT || '18082', 10);
const PROTOCOL_VERSION = '2025-03-26';

const stats = { initialize: 0, toolsList: 0, toolsCall: 0, latencyMs: [] };

/** 最近一次 tools/call 的观测面（e2e 断言 userHeaders / _meta 注入） */
let lastCall = null;

function reset() {
  stats.initialize = 0;
  stats.toolsList = 0;
  stats.toolsCall = 0;
  stats.latencyMs = [];
  lastCall = null;
}

function percentile(arr, p) {
  if (!arr.length) return 0;
  const sorted = [...arr].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.floor((p / 100) * sorted.length))];
}

function result(id, result) {
  return { jsonrpc: '2.0', id, result };
}

function handleRpc(body) {
  const { id, method, params } = body;
  switch (method) {
    case 'initialize':
      stats.initialize++;
      return result(id, {
        protocolVersion: PROTOCOL_VERSION,
        capabilities: { tools: {} },
        serverInfo: { name: 'bench-mock-mcp', version: '1.0.0' },
      });
    case 'notifications/initialized':
      return null; // 通知，无响应
    case 'tools/list':
      stats.toolsList++;
      return result(id, {
        tools: [{
          name: 'bench_echo',
          description: '回显输入文本（压测专用）',
          inputSchema: {
            type: 'object',
            properties: { text: { type: 'string', description: '待回显文本' } },
            required: ['text'],
          },
        }],
      });
    case 'tools/call': {
      stats.toolsCall++;
      const text = (params && params.arguments && params.arguments.text) || '';
      return result(id, {
        content: [{ type: 'text', text: 'BENCH-MCP-OK:' + text }],
        isError: false,
      });
    }
    case 'ping':
      return result(id, {});
    default:
      return { jsonrpc: '2.0', id: id ?? null, error: { code: -32601, message: 'method not found: ' + method } };
  }
}

function writeRpc(req, res, sessionId, body) {
  const started = Date.now();
  let payload;
  try {
    payload = JSON.parse(body || '{}');
  } catch {
    res.writeHead(400, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ jsonrpc: '2.0', id: null, error: { code: -32700, message: 'parse error' } }));
    return;
  }

  const headers = { 'Content-Type': 'application/json' };
  if (sessionId) headers['mcp-session-id'] = sessionId;

  // e2e 观测：记录本次 tools/call 收到的用户级 header 与 _meta（协议字段）
  if (payload.method === 'tools/call') {
    lastCall = {
      at: Date.now(),
      xUserId: req.headers['x-user-id'] ?? null,
      meta: payload.params ? payload.params._meta ?? null : null,
    };
  }

  // 通知（无 id）：202 无响应体
  if (payload.id === undefined || payload.id === null) {
    handleRpc(payload);
    res.writeHead(202, headers);
    res.end();
    return;
  }

  const answer = handleRpc(payload);
  stats.latencyMs.push(Date.now() - started);

  const wantsSse = String(req.headers.accept || '').includes('text/event-stream');
  if (wantsSse) {
    res.writeHead(200, { ...headers, 'Content-Type': 'text/event-stream' });
    res.write(`event:message\ndata:${JSON.stringify(answer)}\n\n`);
    res.end();
  } else {
    res.writeHead(200, headers);
    res.end(JSON.stringify(answer));
  }
}

const server = http.createServer((req, res) => {
  if (req.method === 'POST' && (req.url === '/mcp' || req.url.startsWith('/mcp?'))) {
    // streamableHttp：initialize 后会话经 mcp-session-id 头维系，mock 不强制校验
    const sessionId = req.headers['mcp-session-id'] || crypto.randomUUID();
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => writeRpc(req, res, sessionId, body));
    return;
  }
  if (req.method === 'GET' && req.url === '/stats') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      initialize: stats.initialize,
      toolsList: stats.toolsList,
      toolsCall: stats.toolsCall,
      p50Ms: percentile(stats.latencyMs, 50),
      p95Ms: percentile(stats.latencyMs, 95),
    }));
    return;
  }
  if (req.method === 'POST' && req.url === '/reset') {
    reset();
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true }));
    return;
  }
  if (req.method === 'GET' && req.url === '/last-call') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(lastCall ?? {}));
    return;
  }
  if (req.method === 'GET' && req.url === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ status: 'healthy' }));
    return;
  }
  res.writeHead(404, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ error: 'not found' }));
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`[mock-mcp] listening on 0.0.0.0:${PORT}`);
});
