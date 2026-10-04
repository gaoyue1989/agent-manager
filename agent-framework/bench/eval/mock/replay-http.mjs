#!/usr/bin/env node
/**
 * replay-http：沙箱 / MCP 通用回放器（设计 §4.5）——数据源为 evalpack interactions。
 *
 * - kind=sandbox：按 (method, 归一化 path, 归一化 body) 匹配录制交互，逐级回退
 *   （exact → normalized → 同路径 FIFO），回放录制响应（status + body 原文）
 * - kind=mcp：JSON-RPC 匹配——initialize/tools list 用包内录制目录自描述；
 *   tools/call 按 (tool, 归一化 arguments) 匹配回放 CallToolResult
 * 统一 /stats（exact/normalized/fallback/miss）供 report 聚合；/healthz /reset。
 *
 * 用法：node replay-http.mjs --pack <dir> --kind sandbox|mcp --port <p>
 * 零依赖，Node >= 18。
 */

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const arg = (name, dflt = null) => {
  const i = process.argv.indexOf(`--${name}`);
  return i !== -1 ? process.argv[i + 1] : dflt;
};
const PACK_DIR = arg('pack');
const KIND = arg('kind', 'sandbox');
const PORT = parseInt(arg('port', '18903'), 10);
if (!PACK_DIR) { console.error('--pack 必填'); process.exit(2); }

// 归一化规则（与 bench/eval/replay/normalize.py 同构；改动两侧同步）
const RULES = [
  [/\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:?\d{2})?/g, '<TS>'],
  [/(?<![\d.])1[3-9]\d{11}(?![\d])/g, '<TS_MS>'],
  [/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/g, '<UUID>'],
  [/gw-[0-9a-fA-F]{6,}/g, '<GW>'],
  [/(https?:\/\/)?(127\.0\.0\.1|localhost)(:\d+)?/g, '<HOST>'],
  [/(?<![A-Za-z0-9_-])[A-Fa-f0-9]{16,}(?![A-Za-z0-9_-])/g, '<TOKEN>'],
  [/(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{22}(?![A-Za-z0-9_-])/g, '<TOKEN>'],
];
function normalizeText(s) { let out = String(s ?? ''); for (const [re, r] of RULES) out = out.replace(re, r); return out; }
function normalizeObj(v) {
  if (typeof v === 'string') return normalizeText(v);
  if (Array.isArray(v)) return v.map(normalizeObj);
  if (v && typeof v === 'object') { const o = {}; for (const [k, x] of Object.entries(v)) o[k] = normalizeObj(x); return o; }
  return v;
}
const sha = (s) => crypto.createHash('sha256').update(s).digest('hex');
// JSON 语义等价（两侧序列化空格/键序可能不同）
const sameJson = (a, b) => {
  try { return JSON.stringify(JSON.parse(a)) === JSON.stringify(JSON.parse(b)); }
  catch { return String(a) === String(b); }
};
const asStr = (v) => (typeof v === 'string' ? v : JSON.stringify(v ?? ''));

// ---- 加载录制交互 ----
const records = [];
{
  const dir = path.join(PACK_DIR, 'interactions', KIND);
  if (fs.existsSync(dir)) {
    for (const f of fs.readdirSync(dir).filter((f) => f.endsWith('.json')).sort()) {
      records.push(JSON.parse(fs.readFileSync(path.join(dir, f), 'utf-8')));
    }
  }
}
const stats = { served: 0, exact: 0, normalized: 0, fallback: 0, miss: 0 };
const cursors = new Map(); // key → FIFO 游标

function nextFifo(key, pool) {
  const i = cursors.get(key) || 0;
  cursors.set(key, i + 1);
  return pool[i % Math.max(pool.length, 1)];
}

function matchSandbox(method, p, bodyStr) {
  const pathRecs = records.filter((r) => r.method === method && r.path === p);
  if (!pathRecs.length) {
    // 归一化路径二级匹配（execd 代理路径含易变沙箱 id 等）
    const np = normalizeText(p);
    const normRecs = records.filter((r) => r.method === method && normalizeText(r.path) === np);
    if (!normRecs.length) return { rec: null, tier: 'miss' };
    const bodyMatch = bodyStr ? normRecs.find((r) =>
      sha(normalizeText(asStr(r.request ?? ''))) === sha(normalizeText(bodyStr))) : null;
    return { rec: bodyMatch || nextFifo(`${method}:${np}`, normRecs), tier: bodyMatch ? 'normalized' : 'fallback' };
  }
  if (bodyStr) {
    const exact = pathRecs.find((r) => sameJson(asStr(r.request ?? ''), bodyStr));
    if (exact) return { rec: exact, tier: 'exact' };
    const nb = sha(normalizeText(bodyStr));
    const norm = pathRecs.find((r) => sha(normalizeText(asStr(r.request ?? ''))) === nb);
    if (norm) return { rec: norm, tier: 'normalized' };
  }
  return { rec: nextFifo(`${method}${p}`, pathRecs), tier: 'fallback' };
}

function matchMcp(rpc) {
  const method = rpc.method || '';
  if (method === 'initialize' || method === 'tools/list' || method === 'resources/list' || method === 'ping') {
    const rec = records.find((r) => (Array.isArray(r.request) ? r.request[0] : r.request)?.method === method);
    if (rec) return { rec, tier: 'exact' };
    return { rec: null, tier: 'miss' };
  }
  const tool = rpc.params?.name || '';
  const argsHash = sha(normalizeText(JSON.stringify(normalizeObj(rpc.params?.arguments ?? {}))));
  const byTool = records.filter((r) => (Array.isArray(r.request) ? r.request[0] : r.request)?.method === method
    && (Array.isArray(r.request) ? r.request[0] : r.request)?.params?.name === tool);
  if (!byTool.length) return { rec: null, tier: 'miss' };
  const exact = byTool.find((r) => {
    const rq = Array.isArray(r.request) ? r.request[0] : r.request;
    return sha(JSON.stringify(rq.params?.arguments ?? {})) === sha(JSON.stringify(rpc.params?.arguments ?? {}));
  });
  if (exact) return { rec: exact, tier: 'exact' };
  const norm = byTool.find((r) => {
    const rq = Array.isArray(r.request) ? r.request[0] : r.request;
    return sha(normalizeText(JSON.stringify(normalizeObj(rq.params?.arguments ?? {})))) === argsHash;
  });
  if (norm) return { rec: norm, tier: 'normalized' };
  return { rec: nextFifo(`${method}:${tool}`, byTool), tier: 'fallback' };
}

function recordedBody(rec) {
  let body = rec.response;
  if (KIND === 'mcp' && Array.isArray(body)) body = body[0] ?? body;
  return typeof body === 'string' ? body : JSON.stringify(body ?? {});
}

const server = http.createServer((req, res) => {
  const json = (code, obj, status = code) => {
    res.writeHead(status, { 'content-type': 'application/json' });
    res.end(JSON.stringify(obj));
  };
  if (req.method === 'GET' && req.url === '/healthz') return json(200, { ok: true, kind: KIND, records: records.length });
  if (req.method === 'GET' && req.url === '/stats') return json(200, { kind: KIND, ...stats });
  if (req.method === 'POST' && req.url === '/reset') {
    stats.served = stats.exact = stats.normalized = stats.fallback = stats.miss = 0;
    cursors.clear();
    return json(200, { ok: true });
  }

  const chunks = [];
  req.on('data', (c) => chunks.push(c));
  req.on('end', () => {
    const bodyStr = Buffer.concat(chunks).toString('utf-8');
    if (KIND === 'mcp') {
      let rpc = {};
      try { rpc = JSON.parse(bodyStr); } catch { /* 按空 */ }
      const { rec, tier } = matchMcp(rpc);
      stats.served += 1;
      if (!rec) { stats.miss += 1; return json(200, { jsonrpc: '2.0', id: rpc.id ?? null, error: { code: -32601, message: `replay: 无录制交互 ${rpc.method}` } }); }
      stats[tier] += 1;
      const out = rec.response;
      return json(200, Array.isArray(out) ? (out[0] ?? out) : out);
    }
    // sandbox：路径 = 客户端原样（上游 base 已被指向本回放器）
    const { rec, tier } = matchSandbox(req.method, req.url, bodyStr);
    stats.served += 1;
    if (!rec) { stats.miss += 1; return json(404, { error: `replay: 无录制交互 ${req.method} ${req.url}` }); }
    stats[tier] += 1;
    const body = recordedBody(rec);
    res.writeHead(rec.status || 200, { 'content-type': 'application/json' });
    res.end(body);
  });
});

server.listen(PORT, '127.0.0.1', () =>
  console.log(`[replay-http] kind=${KIND} pack=${PACK_DIR} port=${PORT} records=${records.length}`));
