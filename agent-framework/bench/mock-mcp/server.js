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

// ---------- OAF 包生成工具 mock（e2e F12/U13：MCP 打包 → present_url 交付 → 代理下载）----------

/** CRC32（IEEE 802.3，zip 标准） */
const CRC_TABLE = (() => {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c;
  }
  return t;
})();

function crc32(buf) {
  let c = -1;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}

/** 最小 stored zip（无压缩，确定性字节）：e2e 下载内容断言的固定基准 */
function buildStoredZip(files) {
  const locals = [];
  const centrals = [];
  let offset = 0;
  for (const [name, content] of files) {
    const nameBuf = Buffer.from(name, 'utf8');
    const data = Buffer.from(content, 'utf8');
    const crc = crc32(data);
    const l = Buffer.alloc(30);
    l.writeUInt32LE(0x04034b50, 0);
    l.writeUInt16LE(20, 4); // version needed
    l.writeUInt16LE(0, 6); // flags
    l.writeUInt16LE(0, 8); // method: store
    l.writeUInt16LE(0, 10); // mod time
    l.writeUInt16LE(0x21, 12); // mod date（1980-01-01，确定性）
    l.writeUInt32LE(crc, 14);
    l.writeUInt32LE(data.length, 18);
    l.writeUInt32LE(data.length, 22);
    l.writeUInt16LE(nameBuf.length, 26);
    l.writeUInt16LE(0, 28);
    locals.push(l, nameBuf, data);

    const c = Buffer.alloc(46);
    c.writeUInt32LE(0x02014b50, 0);
    c.writeUInt16LE(20, 4); // version made by
    c.writeUInt16LE(20, 6); // version needed
    c.writeUInt16LE(0, 8);
    c.writeUInt16LE(0, 10);
    c.writeUInt16LE(0, 12);
    c.writeUInt16LE(0x21, 14);
    c.writeUInt32LE(crc, 16);
    c.writeUInt32LE(data.length, 20);
    c.writeUInt32LE(data.length, 24);
    c.writeUInt16LE(nameBuf.length, 28);
    c.writeUInt16LE(0, 30);
    c.writeUInt16LE(0, 32);
    c.writeUInt16LE(0, 34);
    c.writeUInt16LE(0, 36);
    c.writeUInt32LE(0, 38);
    c.writeUInt32LE(offset, 42);
    centrals.push(c, nameBuf);

    offset += 30 + nameBuf.length + data.length;
  }
  const cd = Buffer.concat(centrals);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(files.length, 8);
  eocd.writeUInt16LE(files.length, 10);
  eocd.writeUInt32LE(cd.length, 12);
  eocd.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, cd, eocd]);
}

/** 固定 OAF 包字节（backend create_oaf_zip 的 mock 等价产物） */
const OAF_PACKAGE_ZIP = buildStoredZip([
  ['AGENTS.md', '---\nname: "e2e-oaf-agent"\nvendorKey: "e2e"\nagentKey: "e2e-oaf-agent"\nversion: "1.0.0"\n'
    + 'description: "E2E 下载卡片门禁验证包"\nauthor: "@e2e"\nlicense: "MIT"\n---\n# E2E OAF Agent\n\n下载卡片门禁验证用包。\n'],
  ['skills/help/SKILL.md', '# help\n\nE2E 附加文件。\n'],
]);

function checkOafPackageResult() {
  return {
    valid: true, note: 'OK',
    missing: [], invalid: [],
    present: ['agentKey', 'author', 'description', 'license', 'name', 'vendorKey', 'version'],
  };
}

function createOafZipResult() {
  return {
    packageId: 7, name: 'e2e-oaf-agent', slug: 'e2e/e2e-oaf-agent', version: '1.0.0',
    warnings: [], fileCount: 2,
    file_name: 'e2e-oaf-agent.zip', size: OAF_PACKAGE_ZIP.length,
    download_url: `http://127.0.0.1:${PORT}/packages/7/download`,
    hint: 'deliver via agent present_url(file_name, url=download_url), then publish_service(packageId)',
  };
}

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
        }, {
          name: 'check_oaf_package',
          description: 'Validate an OAF package AGENTS.md frontmatter（mock：固定返回 valid=true，e2e F12/U12 链路）',
          inputSchema: {
            type: 'object',
            properties: { agents_md: { type: 'string', description: '完整 AGENTS.md 内容' } },
            required: ['agents_md'],
          },
        }, {
          name: 'create_oaf_zip',
          description: 'Assemble an OAF package and register it（mock：固定返回 packageId/download_url，'
            + 'download_url 指向本服务的 /packages/7/download 固定 zip 字节）',
          inputSchema: {
            type: 'object',
            properties: {
              package_name: { type: 'string', description: 'zip 文件名' },
              agents_md: { type: 'string', description: '完整 AGENTS.md 内容' },
              extra_files: { type: 'array', description: '附加文件' },
            },
            required: ['agents_md'],
          },
        }],
      });
    case 'tools/call': {
      stats.toolsCall++;
      const name = (params && params.name) || '';
      const text = (params && params.arguments && params.arguments.text) || '';
      if (name === 'check_oaf_package') {
        return result(id, {
          content: [{ type: 'text', text: JSON.stringify(checkOafPackageResult()) }],
          isError: false,
        });
      }
      if (name === 'create_oaf_zip') {
        return result(id, {
          content: [{ type: 'text', text: JSON.stringify(createOafZipResult()) }],
          isError: false,
        });
      }
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
  if (req.method === 'GET' && /^\/packages\/\d+\/download$/.test(req.url)) {
    // OAF 包下载端点（create_oaf_zip mock 返回的 download_url 指向此处；
    // agent present_url 登记后经 /files/{id} 服务端代理回源到本端点）
    res.writeHead(200, {
      'Content-Type': 'application/zip',
      'Content-Length': OAF_PACKAGE_ZIP.length,
      'Content-Disposition': 'attachment; filename="e2e-oaf-agent.zip"',
    });
    res.end(OAF_PACKAGE_ZIP);
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
