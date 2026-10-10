#!/usr/bin/env node
/**
 * eval-collector：离线评测数据收集代理（设计 docs/design/agent-framework-eval-offline-record-replay-design.md §2）
 *
 * 四固定端口 + 路径前缀多路复用（多业务服务共用实例，不随接入数扩端口）：
 *   :18200  LLM 代理（OpenAI 兼容）       /{ns}/v1/...        → profile.upstream.llm
 *   :18201  沙箱代理（管理API + execd）    /{ns}/...           → profile.upstream.sandbox
 *   :18202  MCP 代理（streamableHttp）    /mcp/{ns}/{server}/ → profile.upstream.mcp[server]
 *   :18300  管理控制台 + 管理 API（Bearer token；未设 token 时仅绑 127.0.0.1）
 *
 * 数据面端口不加认证（零侵入前提：业务服务无需携带凭据）；安全边界 = 仅测试网络可达 + 采集窗口期。
 * 录制输出：{data}/{ns}/{kind}/YYYYMMDD.jsonl，一行一个完整交互（LLM chunks 为 SSE data 载荷原文，
 * 与 e2e/mock/fixtures/llm 夹具格式同构，可直接回放）。
 * 脱敏：录制副本逐字符串值过 mask 规则（透传内容不受影响）；Authorization 等请求头一律不入库。
 *
 * 零 npm 依赖，Node >= 18。
 */

import http from 'node:http';
import https from 'node:https';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const VERSION = '0.1.0';
const START_AT = Date.now();

// ---------------------------------------------------------------------------
// 配置加载：全局层（collector.yaml + env 覆盖）+ 接入档案层（profiles.json，控制台管理）
// ---------------------------------------------------------------------------

const ENV = process.env;
const DATA_DIR = ENV.EVAL_COLLECTOR_DATA || path.join(__dirname, 'data');
const CONF_DIR = ENV.EVAL_COLLECTOR_CONF || path.join(__dirname, 'conf');
const PORT_LLM = intEnv('EVAL_COLLECTOR_PORT_LLM', 18200);
const PORT_SANDBOX = intEnv('EVAL_COLLECTOR_PORT_SANDBOX', 18201);
const PORT_MCP = intEnv('EVAL_COLLECTOR_PORT_MCP', 18202);
const PORT_AGENT = intEnv('EVAL_COLLECTOR_PORT_AGENT', 18203);
const PORT_ADMIN = intEnv('EVAL_COLLECTOR_PORT_ADMIN', 18300);
const ADMIN_TOKEN = ENV.EVAL_COLLECTOR_ADMIN_TOKEN || '';
const PACKAGER_URL = (ENV.EVAL_PACKAGER_URL || '').replace(/\/$/, '');
// 请求体硬上限（转发语义）：超限直接 413 拒绝且不转发；录制上限（body_max_bytes）只影响录制副本。
// 默认 64MB 的取舍：旁路组件、安全边界 = 仅测试网络可达（见文件头），64MB 足以容纳大上下文
// LLM 请求，同时防单请求内存无界；并发下最坏 64MB/请求，需要收紧时用该 env 调小。
const BODY_HARD_LIMIT = intEnv('EVAL_COLLECTOR_BODY_HARD_LIMIT_BYTES', 64 * 1024 * 1024);

function intEnv(name, dflt) {
  const v = parseInt(ENV[name], 10);
  return Number.isFinite(v) ? v : dflt;
}

/** 极简 YAML 子集解析（仅支持本服务自用 schema：嵌套 map / "- " 列表 / 标量）。
 *  设计取舍：全局配置项有限，不引入 js-yaml 依赖（零依赖红线）。 */
function parseMiniYaml(text) {
  const root = {};
  const stack = [{ indent: -1, obj: root }];
  let pendingListKey = null;
  for (const rawLine of text.split(/\r?\n/)) {
    if (!rawLine.trim() || rawLine.trim().startsWith('#')) continue;
    const indent = rawLine.length - rawLine.trimStart().length;
    const line = rawLine.trim();
    while (stack.length > 1 && indent <= stack[stack.length - 1].indent) {
      stack.pop();
      pendingListKey = null;
    }
    const cur = stack[stack.length - 1].obj;
    if (line.startsWith('- ')) {
      // 列表项：挂在上一次出现 "key:" 的位置（仅一层 map 列表，够用）
      const item = parseScalar(line.slice(2).trim());
      if (pendingListKey && Array.isArray(cur[pendingListKey])) {
        if (item && typeof item === 'object' && !Array.isArray(item)) cur[pendingListKey].push(item);
        else cur[pendingListKey].push(item);
      }
      continue;
    }
    const m = line.match(/^([^:]+):\s*(.*)$/);
    if (!m) continue;
    const key = m[1].trim().replace(/^["']|["']$/g, '');
    const val = m[2].trim();
    if (val === '') {
      // 可能是子 map，也可能是列表容器——两者都先占位数组，遇到 "- " 复用、遇到缩进 map 覆盖
      cur[key] = [];
      pendingListKey = key;
      stack.push({ indent, obj: cur[key] });
      // 用代理对象继续收子键：数组也能挂属性（JS 允许），后续转 map
      const holder = cur[key];
      holder.__isListHolder = true;
    } else {
      cur[key] = parseScalar(val);
      pendingListKey = null;
    }
  }
  // 后处理：仅占位未出现 "- " 的数组且挂了子键 → 转普通对象
  return postProcess(root);

  function postProcess(node) {
    if (Array.isArray(node)) {
      const hasKeys = Object.keys(node).some((k) => !/^\d+$/.test(k) && k !== '__isListHolder');
      if (hasKeys) {
        const obj = {};
        for (const [k, v] of Object.entries(node)) if (k !== '__isListHolder') obj[k] = postProcess(v);
        return obj;
      }
      return node.filter((v) => v !== undefined).map(postProcess);
    }
    if (node && typeof node === 'object') {
      const out = {};
      for (const [k, v] of Object.entries(node)) if (k !== '__isListHolder') out[k] = postProcess(v);
      return out;
    }
    return node;
  }

  function parseScalar(s) {
    if (s === 'true') return true;
    if (s === 'false') return false;
    if (s === 'null' || s === '~') return null;
    if (/^-?\d+(\.\d+)?$/.test(s)) return Number(s);
    if (s.startsWith('{') || s.startsWith('[')) { try { return JSON.parse(s); } catch { /* 按字符串 */ } }
    return s.replace(/^["']|["']$/g, '');
  }
}

const GLOBAL_DEFAULTS = {
  defaults: { sampling: 1.0, body_max_bytes: 1048576 },
  storage: { retention_days: 14 },
  mask_rules: [],
};

function loadGlobalConfig() {
  const file = path.join(CONF_DIR, 'collector.yaml');
  let parsed = {};
  try {
    if (fs.existsSync(file)) parsed = parseMiniYaml(fs.readFileSync(file, 'utf-8'));
  } catch (e) {
    console.error(`[config][WARN] collector.yaml 解析失败，使用默认值: ${e.message}`);
  }
  const cfg = {
    defaults: { ...GLOBAL_DEFAULTS.defaults, ...(parsed.defaults || {}) },
    storage: { ...GLOBAL_DEFAULTS.storage, ...(parsed.storage || {}) },
    mask_rules: Array.isArray(parsed.mask_rules) ? parsed.mask_rules : [],
  };
  // env 覆盖（容器部署最常用项）
  if (ENV.EVAL_COLLECTOR_RETENTION_DAYS) cfg.storage.retention_days = parseInt(ENV.EVAL_COLLECTOR_RETENTION_DAYS, 10) || cfg.storage.retention_days;
  return cfg;
}

let GLOBAL = loadGlobalConfig();

const DEFAULT_MASK_RULES = [
  { pattern: '(sk|tp)-[A-Za-z0-9_-]{8,}', replace: '$1-***' },
  { pattern: '"(api_?key|token|secret|password|authorization)"\\s*:\\s*"[^"]*"', replace: '"$1": "***"' },
  { pattern: '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}', replace: '***@***' },
  { pattern: '(?<![0-9])1[3-9][0-9]{9}(?![0-9])', replace: '1**********' },
];

function maskRulesFor(profile) {
  const rules = [...DEFAULT_MASK_RULES, ...GLOBAL.mask_rules, ...((profile?.record?.mask_rules) || [])];
  return rules
    .filter((r) => r && r.pattern)
    .map((r) => ({ re: safeRegex(r.pattern), replace: r.replace ?? '***' }))
    .filter((r) => r.re);
}

function safeRegex(p) { try { return new RegExp(p, 'g'); } catch { return null; } }

/** 递归对字符串值做脱敏（录制副本专用；透传内容不受影响）。 */
function maskValue(v, rules) {
  if (typeof v === 'string') {
    let s = v;
    for (const r of rules) s = s.replace(r.re, r.replace);
    return s;
  }
  if (Array.isArray(v)) return v.map((x) => maskValue(x, rules));
  if (v && typeof v === 'object') {
    const out = {};
    for (const [k, val] of Object.entries(v)) out[k] = maskValue(val, rules);
    return out;
  }
  return v;
}

// ---------------------------------------------------------------------------
// 接入档案（profiles.json）：CRUD + 校验 + 审计
// ---------------------------------------------------------------------------

const PROFILES_FILE = path.join(CONF_DIR, 'profiles.json');

function loadProfiles() {
  try {
    if (fs.existsSync(PROFILES_FILE)) return JSON.parse(fs.readFileSync(PROFILES_FILE, 'utf-8'));
  } catch (e) {
    console.error(`[profiles][WARN] profiles.json 读取失败: ${e.message}`);
  }
  return {};
}

let PROFILES = loadProfiles();

function saveProfiles() {
  fs.mkdirSync(CONF_DIR, { recursive: true });
  fs.writeFileSync(PROFILES_FILE, JSON.stringify(PROFILES, null, 2));
}

function validateProfile(p) {
  if (!p || typeof p !== 'object') return 'profile 必须是对象';
  const ns = String(p.ns || '').trim();
  if (!/^[a-z0-9][a-z0-9-]{1,62}$/.test(ns)) return 'ns 须为 2~63 位小写字母/数字/连字符';
  if (!p.upstream || typeof p.upstream !== 'object') return 'upstream 必填';
  const hasAny = p.upstream.llm || p.upstream.sandbox || p.upstream.agent || (p.upstream.mcp && Object.keys(p.upstream.mcp).length);
  if (!hasAny) return 'upstream 至少配置 llm / sandbox / mcp 其一';
  for (const key of ['llm', 'sandbox', 'agent']) {
    if (p.upstream[key] && !/^https?:\/\//.test(String(p.upstream[key]))) return `upstream.${key} 须为 http(s) 地址`;
  }
  if (p.upstream.mcp) {
    for (const [k, v] of Object.entries(p.upstream.mcp)) {
      if (!/^[a-z0-9][a-z0-9_-]{0,62}$/.test(k)) return `mcp server 名非法: ${k}`;
      if (!/^https?:\/\//.test(String(v))) return `upstream.mcp.${k} 须为 http(s) 地址`;
    }
  }
  if (p.record?.sampling !== undefined && !(Number(p.record.sampling) > 0 && Number(p.record.sampling) <= 1)) return 'record.sampling 须在 (0,1]';
  if (p.state && !['recording', 'passthrough', 'disabled'].includes(p.state)) return 'state 非法';
  return null;
}

function audit(action, detail) {
  try {
    fs.mkdirSync(DATA_DIR, { recursive: true });
    fs.appendFileSync(path.join(DATA_DIR, 'audit.jsonl'),
      JSON.stringify({ ts: new Date().toISOString(), action, detail }) + '\n');
  } catch { /* 审计失败不阻断主流程 */ }
}

// ---------------------------------------------------------------------------
// 录制存储：data/{ns}/{kind}/YYYYMMDD.jsonl（原子追加）；计数内存缓存（启动时重建）
// ---------------------------------------------------------------------------

const counters = new Map(); // key: ns|kind → {today, total, bytes, last_at, last_error}

function kindDir(ns, kind) { return path.join(DATA_DIR, ns, kind); }

function todayTag() {
  const d = new Date();
  return `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, '0')}${String(d.getDate()).padStart(2, '0')}`;
}

function recordInteraction(ns, kind, entry) {
  const dir = kindDir(ns, kind);
  fs.mkdirSync(dir, { recursive: true });
  const line = JSON.stringify(entry) + '\n';
  const file = path.join(dir, `${todayTag()}.jsonl`);
  fs.appendFileSync(file, line);
  const key = `${ns}|${kind}`;
  const c = counters.get(key) || { today: 0, total: 0 };
  c.total += 1;
  c.today += 1;
  c.last_at = entry.ts_end || entry.ts_start;
  counters.set(key, c);
}

function rebuildCounters() {
  counters.clear();
  let nsList = [];
  try { nsList = fs.readdirSync(DATA_DIR, { withFileTypes: true }).filter((d) => d.isDirectory()).map((d) => d.name); } catch { return; }
  for (const ns of nsList) {
    let kinds = [];
    try { kinds = fs.readdirSync(path.join(DATA_DIR, ns), { withFileTypes: true }).filter((d) => d.isDirectory()).map((d) => d.name); } catch { continue; }
    for (const kind of kinds) {
      let files = [];
      try { files = fs.readdirSync(path.join(DATA_DIR, ns, kind)).filter((f) => f.endsWith('.jsonl')).sort(); } catch { continue; }
      let total = 0, today = 0, lastAt = null;
      for (const f of files) {
        const n = fs.readFileSync(path.join(DATA_DIR, ns, kind, f), 'utf-8').split('\n').filter((l) => l.trim()).length;
        total += n;
        if (f.startsWith(todayTag())) today += n;
        try {
          const first = JSON.parse(fs.readFileSync(path.join(DATA_DIR, ns, kind, f), 'utf-8').split('\n')[0]);
          if (first?.ts_end) lastAt = first.ts_end;
        } catch { /* 空文件 */ }
      }
      counters.set(`${ns}|${kind}`, { today, total, last_at: lastAt });
    }
  }
}

function retentionSweep() {
  const days = GLOBAL.storage.retention_days;
  if (!days || days <= 0) return;
  const cutoff = Date.now() - days * 86400_000;
  let nsList = [];
  try { nsList = fs.readdirSync(DATA_DIR, { withFileTypes: true }).filter((d) => d.isDirectory()).map((d) => d.name); } catch { return; }
  for (const ns of nsList) {
    for (const kind of ['llm', 'sandbox', 'mcp', 'http']) {
      const dir = path.join(DATA_DIR, ns, kind);
      let files = [];
      try { files = fs.readdirSync(dir).filter((f) => f.endsWith('.jsonl')); } catch { continue; }
      for (const f of files) {
        const m = f.match(/^(\d{4})(\d{2})(\d{2})\.jsonl$/);
        if (!m) continue;
        if (new Date(`${m[1]}-${m[2]}-${m[3]}T00:00:00Z`).getTime() < cutoff) {
          try { fs.unlinkSync(path.join(dir, f)); } catch { /* 忽略 */ }
        }
      }
    }
  }
}

// ---------------------------------------------------------------------------
// 上游探测（预检）：与代理转发完全一致的网络视角（由 collector 容器主动探测）
// ---------------------------------------------------------------------------

function probeHttp(url, { method = 'GET', headers = {}, body = null, timeoutMs = 8000 } = {}) {
  return new Promise((resolve) => {
    let u;
    try { u = new URL(url); } catch { return resolve({ ok: false, detail: 'URL 非法' }); }
    const mod = u.protocol === 'https:' ? https : http;
    const req = mod.request(u, { method, headers, timeout: timeoutMs, rejectUnauthorized: false }, (res) => {
      const chunks = [];
      res.on('data', (c) => { if (chunks.reduce((n, b) => n + b.length, 0) < 4096) chunks.push(c); });
      res.on('end', () => resolve({ ok: res.statusCode < 500, status: res.statusCode,
        detail: `HTTP ${res.statusCode} ${Buffer.concat(chunks).toString('utf-8').slice(0, 120)}` }));
    });
    req.on('timeout', () => { req.destroy(); resolve({ ok: false, detail: `超时 ${timeoutMs}ms` }); });
    req.on('error', (e) => resolve({ ok: false, detail: e.message }));
    if (body) req.write(body);
    req.end();
  });
}

async function precheckProfile(p) {
  const out = {};
  const auth = p.upstream.llm_api_key ? { Authorization: `Bearer ${p.upstream.llm_api_key}` } : {};
  if (p.upstream.llm) {
    out.llm = await probeHttp(`${String(p.upstream.llm).replace(/\/$/, '')}/models`, { headers: auth });
  }
  if (p.upstream.sandbox) {
    out.sandbox = await probeHttp(String(p.upstream.sandbox).replace(/\/$/, '') + '/');
  }
  if (p.upstream.agent) {
    out.agent = await probeHttp(String(p.upstream.agent).replace(/\/$/, '') + '/health');
  }
  if (p.upstream.mcp) {
    out.mcp = {};
    for (const [name, base] of Object.entries(p.upstream.mcp)) {
      // MCP streamableHttp：initialize 握手（JSON-RPC），2xx/406/415 均说明端点存活
      const r = await probeHttp(String(base).replace(/\/$/, ''), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream' },
        body: JSON.stringify({ jsonrpc: '2.0', id: 0, method: 'initialize',
          params: { protocolVersion: '2025-03-26', capabilities: {}, clientInfo: { name: 'eval-collector-precheck', version: VERSION } } }),
      });
      out.mcp[name] = { ...r, ok: r.ok || r.status === 406 || r.status === 415 };
    }
  }
  return out;
}

// ---------------------------------------------------------------------------
// HTTP 代理核心：透传 + 录制（LLM/Sandbox/MCP 三协议共用一套转发骨架）
// ---------------------------------------------------------------------------

const HOP_HEADERS = new Set(['host', 'connection', 'content-length', 'transfer-encoding', 'keep-alive', 'upgrade', 'proxy-authorization', 'proxy-connection', 'accept-encoding']);

function forward({ req, res, targetUrl, method, headers, body, profile, onResp }) {
  return new Promise((resolve) => {
    let u;
    try { u = new URL(targetUrl); } catch {
      res.writeHead(502, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ error: `upstream 地址非法: ${targetUrl}` }));
      return resolve();
    }
    const mod = u.protocol === 'https:' ? https : http;
    const outHeaders = { ...headers };
    for (const h of Object.keys(outHeaders)) if (HOP_HEADERS.has(h.toLowerCase())) delete outHeaders[h];
    if (body) outHeaders['content-length'] = Buffer.byteLength(body);
    const upstream = mod.request(u, { method, headers: outHeaders, rejectUnauthorized: false }, (ur) => {
      const respHeaders = { ...ur.headers };
      for (const h of ['transfer-encoding', 'content-length', 'connection']) delete respHeaders[h];
      res.writeHead(ur.statusCode || 502, respHeaders);
      onResp && onResp(ur);
      ur.pipe(res);
      ur.on('end', () => resolve());
      ur.on('error', () => { try { res.end(); } catch { /* 已关闭 */ } resolve(); });
    });
    upstream.on('error', (e) => {
      if (!res.headersSent) {
        res.writeHead(502, { 'content-type': 'application/json' });
        res.end(JSON.stringify({ error: `上游不可达: ${e.message}` }));
      } else { try { res.end(); } catch { /* 已关闭 */ } }
      resolve();
    });
    if (body) upstream.write(body);
    upstream.end();
  });
}

/** SSE 响应流拆帧：收集 data: 载荷原文数组（与 e2e 夹具格式同构），同时照常透传。 */
function attachSSECollector(upstreamResp, sink, maxBytes) {
  let buf = '';
  let total = 0;
  upstreamResp.setEncoding('utf-8');
  upstreamResp.on('data', (s) => {
    total += s.length;
    if (total > maxBytes) { sink.truncated = true; return; } // 超限停止收集，透传不受影响
    buf += s;
    let idx;
    while ((idx = buf.indexOf('\n\n')) !== -1) {
      const rawEvent = buf.slice(0, idx);
      buf = buf.slice(idx + 2);
      for (const line of rawEvent.split('\n')) {
        if (line.startsWith('data:')) {
          const payload = line.slice(5).trim();
          if (payload) {
            sink.chunks.push(payload);
            // SSE 语义结束：上游 keep-alive 下 [DONE] 后连接可能长时间不关，
            // 不能等 'end'——收到 [DONE] 立即触发落盘回调
            if (payload === '[DONE]' && sink.onDone) { const cb = sink.onDone; sink.onDone = null; cb(); }
          }
        }
      }
    }
  });
}

/** 请求体读取双上限（截断与转发分离）：
 *  - recordLimit（即 body_max_bytes，录制上限）：只决定录制副本的 truncated 标记，
 *    字节始终完整缓存，保证转发体完整（转发体截断曾致上游收到畸形 JSON，issue #97 问题6）；
 *  - hardLimit（BODY_HARD_LIMIT，硬上限）：超限置 tooLarge 并停止缓存、继续排空连接
 *    （排空避免半途断开导致客户端收不到 413 响应），调用方据此直接 413 拒绝且不转发。
 *  返回的 body 为截至硬上限的完整体（tooLarge 时为部分体，但调用方不会转发它）。 */
function readBody(req, recordLimit, hardLimit = BODY_HARD_LIMIT) {
  return new Promise((resolve) => {
    const chunks = [];
    let size = 0;
    let truncated = false;
    let tooLarge = false;
    req.on('data', (c) => {
      size += c.length;
      if (size > hardLimit) { tooLarge = true; req.resume(); return; } // 硬上限：停止缓存继续排空
      if (size > recordLimit) truncated = true; // 录制上限：只置标记，字节照常缓存供转发
      chunks.push(c);
    });
    req.on('end', () => resolve({ body: Buffer.concat(chunks), truncated, tooLarge }));
    req.on('error', () => resolve({ body: Buffer.concat(chunks), truncated, tooLarge }));
  });
}

function sendJson(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { 'content-type': 'application/json; charset=utf-8' });
  res.end(body);
}

function getSessionId(req) {
  const h = req.headers['x-eval-session'];
  return typeof h === 'string' && h.trim() ? h.trim() : null;
}

function resolveProfile(ns) {
  const p = PROFILES[ns];
  if (!p) return { error: 404, msg: `接入档案不存在: ${ns}（先在控制台创建）` };
  if (p.state === 'disabled') return { error: 503, msg: `该接入档案已停用: ${ns}` };
  return { profile: p };
}

// ---- :18200 LLM 代理 -------------------------------------------------------

async function handleLLM(req, res) {
  // 路由：/{ns}/v1/... → profile.upstream.llm + /v1/...
  const m = req.url.match(/^\/([a-z0-9][a-z0-9-]{1,62})(\/.+)$/);
  if (!m) return sendJson(res, 404, { error: '路径须为 /{ns}/v1/...' });
  const [, ns, rest] = m;
  const r = resolveProfile(ns);
  if (r.error) return sendJson(res, r.error, { error: r.msg });
  const p = r.profile;
  if (!p.upstream.llm) return sendJson(res, 404, { error: `档案 ${ns} 未配置 LLM 上游` });

  const maxBody = (p.record?.body_max_bytes) || GLOBAL.defaults.body_max_bytes;
  const { body, truncated: reqTrunc, tooLarge } = await readBody(req, maxBody);
  // 超硬上限：直接 413 拒绝且不转发（转发体始终是完整体，见 readBody 注释）
  if (tooLarge) return sendJson(res, 413, { error: `请求体超过硬上限 ${BODY_HARD_LIMIT} 字节，拒绝转发` });
  const base = String(p.upstream.llm).replace(/\/$/, '');
  // 版本段归一：客户端 base 含 /v1（LLM_BASE_URL=…/{ns}/v1），剥掉后再拼上游
  // （upstream 填业务直连时用的完整 base，如 https://openrouter.ai/api/v1）
  const tail = rest.replace(/^\/v1(?=\/|$)/, '') || '/';
  const target = base + tail; // rest 以 /v1 开头（base 已含或不含 /v1 均按客户端原样拼接）

  const headers = { ...req.headers };
  // 上游鉴权注入：档案配置 llm_api_key 则覆盖；否则透传调用方凭据
  if (p.upstream.llm_api_key) headers.authorization = `Bearer ${p.upstream.llm_api_key}`;

  // 请求录制副本（脱敏 + model 缺省注入前留档原样；注入只影响转发不影响录制）
  let reqObj = null;
  try { reqObj = JSON.parse(body.toString('utf-8')); } catch { /* 非 JSON（如 embeddings）按原文 */ }
  const shouldRecord = p.state === 'recording' && Math.random() <= (p.record?.sampling ?? GLOBAL.defaults.sampling);
  const rec = shouldRecord ? {
    kind: 'llm', id: `llm-${crypto.randomUUID().slice(0, 8)}`, ns,
    session: getSessionId(req), ts_start: new Date().toISOString(),
    upstream: base, path: rest,
    request: reqObj ?? body.toString('utf-8').slice(0, maxBody),
    request_truncated: reqTrunc, chunks: [], status: null, duration_ms: null,
    truncated: false,
  } : null;

  // model 缺省注入（开箱即用：业务侧不指定模型时由档案补齐）
  let fwdBody = body;
  if (rec && reqObj && !reqObj.model && p.upstream.llm_default_model) {
    reqObj = { ...reqObj, model: p.upstream.llm_default_model };
    fwdBody = Buffer.from(JSON.stringify(reqObj));
  }

  const t0 = Date.now();
  let sink = null;
  // 录制收尾（幂等）：SSE 以 [DONE] 为语义结束（上游 keep-alive 下 'end' 可能长期不触发），
  // 非流式以 'end' 为准；再加 60s 兜底，保证录制不因上游连接形态而丢失
  let finalized = false;
  const finalize = () => {
    if (finalized || !rec) return;
    finalized = true;
    rec.ts_end = new Date().toISOString();
    rec.duration_ms = Date.now() - t0;
    if (sink?.truncated) rec.truncated = true;
    const rules = maskRulesFor(p);
    const masked = maskValue(JSON.parse(JSON.stringify(rec)), rules);
    try { recordInteraction(ns, 'llm', masked); } catch (e) {
      console.error(`[record][ERROR] ${ns}/llm 写入失败: ${e.message}`);
    }
  };
  const finalizeTimer = rec ? setTimeout(finalize, 60_000) : null;
  if (finalizeTimer) finalizeTimer.unref?.();
  await forward({
    req, res, targetUrl: target, method: req.method, headers,
    body: fwdBody.length ? fwdBody : null, profile: p,
    onResp: (ur) => {
      rec && (rec.status = ur.statusCode);
      const ct = String(ur.headers['content-type'] || '');
      if (rec && ct.includes('text/event-stream')) {
        sink = { chunks: rec.chunks, truncated: false, onDone: () => finalize() };
        attachSSECollector(ur, sink, maxBody);
        ur.on('end', () => finalize());
        ur.on('close', () => finalize());
      } else if (rec) {
        // 非流式：缓冲响应体（限长）用于录制
        const chunks = [];
        let total = 0;
        ur.setEncoding('utf-8');
        ur.on('data', (s) => {
          total += s.length;
          if (total > maxBody) { rec.truncated = true; return; }
          chunks.push(s);
        });
        ur.on('end', () => {
          const text = chunks.join('');
          try { rec.body = JSON.parse(text); } catch { rec.body = text.slice(0, maxBody); }
          finalize();
        });
        ur.on('close', () => finalize());
      }
    },
  });
  finalize();
  if (finalizeTimer) clearTimeout(finalizeTimer);
}

// ---- :18201 沙箱代理 -------------------------------------------------------

async function handleSandbox(req, res) {
  // 路由：/{ns}/... → profile.upstream.sandbox + 原路径
  const m = req.url.match(/^\/([a-z0-9][a-z0-9-]{1,62})(\/.*)$/);
  if (!m) return sendJson(res, 404, { error: '路径须为 /{ns}/...' });
  const [, ns, rest] = m;
  const r = resolveProfile(ns);
  if (r.error) return sendJson(res, r.error, { error: r.msg });
  const p = r.profile;
  if (!p.upstream.sandbox) return sendJson(res, 404, { error: `档案 ${ns} 未配置沙箱上游` });

  const maxBody = (p.record?.body_max_bytes) || GLOBAL.defaults.body_max_bytes;
  const { body, truncated: reqTrunc, tooLarge } = await readBody(req, maxBody);
  // 超硬上限：直接 413 拒绝且不转发（转发体始终是完整体，见 readBody 注释）
  if (tooLarge) return sendJson(res, 413, { error: `请求体超过硬上限 ${BODY_HARD_LIMIT} 字节，拒绝转发` });
  const base = String(p.upstream.sandbox).replace(/\/$/, '');
  const target = base + rest;
  const headers = { ...req.headers };
  if (p.upstream.sandbox_api_key) headers.authorization = `Bearer ${p.upstream.sandbox_api_key}`;

  const shouldRecord = p.state === 'recording' && Math.random() <= (p.record?.sampling ?? GLOBAL.defaults.sampling);
  const rec = shouldRecord ? {
    kind: 'sandbox', id: `sbx-${crypto.randomUUID().slice(0, 8)}`, ns,
    session: getSessionId(req), ts_start: new Date().toISOString(),
    method: req.method, path: rest,
    request: body.length ? safeJson(body.toString('utf-8'), maxBody) : '',
    request_truncated: reqTrunc, status: null, duration_ms: null, truncated: false,
  } : null;

  const t0 = Date.now();
  let respCapture = null;
  if (rec) {
    respCapture = { chunks: [], total: 0 };
    // 响应统一缓冲收集（NDJSON/JSON 均按原文），限长
    var collectResp = (ur) => {
      rec.status = ur.statusCode;
      ur.setEncoding('utf-8');
      ur.on('data', (s) => {
        respCapture.total += s.length;
        if (respCapture.total > maxBody) { rec.truncated = true; return; }
        respCapture.chunks.push(s);
      });
      ur.on('end', () => { rec.response = safeJson(respCapture.chunks.join(''), maxBody); });
    };
  }

  await forward({
    req, res, targetUrl: target, method: req.method, headers,
    body: body.length ? body : null, profile: p,
    onResp: rec ? collectResp : undefined,
  });
  if (!rec) return;
  rec.ts_end = new Date().toISOString();
  rec.duration_ms = Date.now() - t0;
  const rules = maskRulesFor(p);
  try { recordInteraction(ns, 'sandbox', maskValue(JSON.parse(JSON.stringify(rec)), rules)); } catch (e) {
    console.error(`[record][ERROR] ${ns}/sandbox 写入失败: ${e.message}`);
  }
}

// ---- :18202 MCP 代理 -------------------------------------------------------

async function handleMCP(req, res) {
  // 路由：/mcp/{ns}/{server}/... → profile.upstream.mcp[server] + 余下路径
  const m = req.url.match(/^\/mcp\/([a-z0-9][a-z0-9-]{1,62})\/([a-z0-9][a-z0-9_-]{0,62})(\/.*)?$/);
  if (!m) return sendJson(res, 404, { error: '路径须为 /mcp/{ns}/{server}/...' });
  const [, ns, server, tail = '/'] = m;
  const r = resolveProfile(ns);
  if (r.error) return sendJson(res, r.error, { error: r.msg });
  const p = r.profile;
  const base = p.upstream.mcp?.[server];
  if (!base) return sendJson(res, 404, { error: `档案 ${ns} 未配置 MCP server: ${server}` });

  const maxBody = (p.record?.body_max_bytes) || GLOBAL.defaults.body_max_bytes;
  const { body, truncated: reqTrunc, tooLarge } = await readBody(req, maxBody);
  // 超硬上限：直接 413 拒绝且不转发（转发体始终是完整体，见 readBody 注释）
  if (tooLarge) return sendJson(res, 413, { error: `请求体超过硬上限 ${BODY_HARD_LIMIT} 字节，拒绝转发` });
  const target = String(base).replace(/\/$/, '') + tail;
  const headers = { ...req.headers };
  if (p.upstream.mcp_api_keys?.[server]) headers.authorization = `Bearer ${p.upstream.mcp_api_keys[server]}`;

  const shouldRecord = p.state === 'recording' && Math.random() <= (p.record?.sampling ?? GLOBAL.defaults.sampling);
  const rec = shouldRecord ? {
    kind: 'mcp', id: `mcp-${crypto.randomUUID().slice(0, 8)}`, ns, server,
    session: getSessionId(req), ts_start: new Date().toISOString(),
    method: req.method, path: tail,
    request: body.length ? safeJson(body.toString('utf-8'), maxBody) : '',
    request_truncated: reqTrunc, status: null, duration_ms: null, truncated: false,
  } : null;

  const t0 = Date.now();
  let respCapture = null;
  if (rec) {
    respCapture = { chunks: [], total: 0 };
    var collectResp = (ur) => {
      rec.status = ur.statusCode;
      ur.setEncoding('utf-8');
      ur.on('data', (s) => {
        respCapture.total += s.length;
        if (respCapture.total > maxBody) { rec.truncated = true; return; }
        respCapture.chunks.push(s);
      });
      ur.on('end', () => {
        const text = respCapture.chunks.join('');
        // JSON-RPC 批量响应可能为 SSE 帧（streamableHttp 服务器选择），逐 data: 行拆出
        if (text.includes('data:')) {
          rec.response = text.split('\n\n').flatMap((ev) =>
            ev.split('\n').filter((l) => l.startsWith('data:')).map((l) => safeJson(l.slice(5).trim(), maxBody)));
        } else {
          rec.response = safeJson(text, maxBody);
        }
      });
    };
  }

  await forward({
    req, res, targetUrl: target, method: req.method, headers,
    body: body.length ? body : null, profile: p,
    onResp: rec ? collectResp : undefined,
  });
  if (!rec) return;
  rec.ts_end = new Date().toISOString();
  rec.duration_ms = Date.now() - t0;
  const rules = maskRulesFor(p);
  try { recordInteraction(ns, `mcp`, maskValue(JSON.parse(JSON.stringify(rec)), rules)); } catch (e) {
    console.error(`[record][ERROR] ${ns}/mcp 写入失败: ${e.message}`);
  }
}

// ---- :18203 业务服务反代（kind=http 录制，设计附录 C）-------------------------

/** 从 path/body 提取会话主键（强关联）：/threads/chat 的 body.sessionId、
 *  /threads/{sid}/... 的 path 段。提取不到返回 null（仍录制，session 为空）。 */
function extractSession(pathname, reqObj) {
  let m = pathname.match(/^\/threads\/([^/]+)(?:\/|$)/);
  // POST /threads/chat 时 path 段是 "chat" 不是 sid——交给 body.sessionId
  if (m && m[1] !== 'chat') return decodeURIComponent(m[1]);
  if (reqObj && typeof reqObj.sessionId === 'string' && reqObj.sessionId.trim()) {
    return reqObj.sessionId.trim();
  }
  return null;
}

async function handleAgent(req, res) {
  // 路由：/{ns}/<业务服务路径> → profile.upstream.agent
  const m = req.url.match(/^\/([a-z0-9][a-z0-9-]{1,62})(\/.*)$/);
  if (!m) return sendJson(res, 404, { error: '路径须为 /{ns}/...' });
  const [, ns, rest] = m;
  const r = resolveProfile(ns);
  if (r.error) return sendJson(res, r.error, { error: r.msg });
  const p = r.profile;
  if (!p.upstream.agent) return sendJson(res, 404, { error: `档案 ${ns} 未配置业务服务上游（upstream.agent）` });

  const maxBody = (p.record?.body_max_bytes) || GLOBAL.defaults.body_max_bytes;
  const { body, truncated: reqTrunc, tooLarge } = await readBody(req, maxBody);
  // 超硬上限：直接 413 拒绝且不转发（转发体始终是完整体，见 readBody 注释）
  if (tooLarge) return sendJson(res, 413, { error: `请求体超过硬上限 ${BODY_HARD_LIMIT} 字节，拒绝转发` });
  const base = String(p.upstream.agent).replace(/\/$/, '');
  const target = base + rest;

  let reqObj = null;
  try { if (body.length) reqObj = JSON.parse(body.toString('utf-8')); } catch { /* 非 JSON 按原文 */ }
  const session = extractSession(rest.split('?')[0], reqObj);
  const shouldRecord = p.state === 'recording' && Math.random() <= (p.record?.sampling ?? GLOBAL.defaults.sampling);
  const rec = shouldRecord ? {
    kind: 'http', id: `http-${crypto.randomUUID().slice(0, 8)}`, ns,
    session, ts_start: new Date().toISOString(),
    method: req.method, path: rest,
    request: reqObj ?? body.toString('utf-8').slice(0, maxBody),
    // chunks 与 handleLLM 对齐：SSE 响应时 attachSSECollector 向 rec.chunks 收集 data 载荷，
    // 缺字段会对 undefined.push 抛 TypeError 致整进程崩溃（issue #97 问题4）
    request_truncated: reqTrunc, chunks: [], status: null, duration_ms: null, truncated: false,
  } : null;

  const t0 = Date.now();
  let sink = null;
  let finalized = false;
  const finalize = () => {
    if (finalized || !rec) return;
    finalized = true;
    rec.ts_end = new Date().toISOString();
    rec.duration_ms = Date.now() - t0;
    if (sink?.truncated) rec.truncated = true;
    const rules = maskRulesFor(p);
    try { recordInteraction(ns, 'http', maskValue(JSON.parse(JSON.stringify(rec)), rules)); } catch (e) {
      console.error(`[record][ERROR] ${ns}/http 写入失败: ${e.message}`);
    }
  };
  const finalizeTimer = rec ? setTimeout(finalize, 60_000) : null;
  if (finalizeTimer) finalizeTimer.unref?.();

  const headers = { ...req.headers };
  if (p.upstream.agent_api_key) headers.authorization = `Bearer ${p.upstream.agent_api_key}`;
  await forward({
    req, res, targetUrl: target, method: req.method, headers,
    body: body.length ? body : null, profile: p,
    onResp: (ur) => {
      rec && (rec.status = ur.statusCode);
      const ct = String(ur.headers['content-type'] || '');
      if (rec && ct.includes('text/event-stream')) {
        sink = { chunks: rec.chunks, truncated: false, onDone: () => finalize() };
        attachSSECollector(ur, sink, maxBody);
        ur.on('end', () => finalize());
        ur.on('close', () => finalize());
      } else if (rec) {
        const chunks = [];
        let total = 0;
        ur.setEncoding('utf-8');
        ur.on('data', (s) => {
          total += s.length;
          if (total > maxBody) { rec.truncated = true; return; }
          chunks.push(s);
        });
        ur.on('end', () => {
          const text = chunks.join('');
          try { rec.response = JSON.parse(text); } catch { rec.response = text.slice(0, maxBody); }
          finalize();
        });
        ur.on('close', () => finalize());
      }
    },
  });
  finalize();
  if (finalizeTimer) clearTimeout(finalizeTimer);
}

function safeJson(text, maxBytes) {
  try { return JSON.parse(text); } catch { return text.slice(0, Math.min(text.length, maxBytes)); }
}

// ---------------------------------------------------------------------------
// 管理 API（:18300）
// ---------------------------------------------------------------------------

function adminAuth(req, res) {
  if (!ADMIN_TOKEN) return true; // 未设 token：监听层已仅绑 127.0.0.1
  const h = req.headers.authorization || '';
  if (h === `Bearer ${ADMIN_TOKEN}`) return true;
  sendJson(res, 401, { error: '管理 token 缺失或错误' });
  return false;
}

async function handleAdmin(req, res) {
  const u = new URL(req.url, 'http://x');
  const p = u.pathname;

  if (p === '/healthz') {
    return sendJson(res, 200, { ok: true, version: VERSION, uptime_s: Math.floor((Date.now() - START_AT) / 1000), profiles: Object.keys(PROFILES).length });
  }
  if (!adminAuth(req, res)) return;

  const host = req.headers.host || '127.0.0.1:18300';
  const collectorHost = host.replace(/:.*$/, '') || '127.0.0.1';

  // ---- 状态 ----
  if (p === '/api/status' && req.method === 'GET') {
    const profiles = Object.values(PROFILES).map((pf) => ({
      ns: pf.ns, display: pf.display, state: pf.state,
      upstream: { llm: !!pf.upstream?.llm, sandbox: !!pf.upstream?.sandbox, mcp: Object.keys(pf.upstream?.mcp || {}) },
      counts: {
        llm: counters.get(`${pf.ns}|llm`) || { today: 0, total: 0 },
        sandbox: counters.get(`${pf.ns}|sandbox`) || { today: 0, total: 0 },
        mcp: counters.get(`${pf.ns}|mcp`) || { today: 0, total: 0 },
        http: counters.get(`${pf.ns}|http`) || { today: 0, total: 0 },
      },
      created_at: pf.created_at,
    }));
    let bytes = 0;
    try { bytes = dirSize(DATA_DIR); } catch { /* 目录不存在 */ }
    return sendJson(res, 200, { version: VERSION, uptime_s: Math.floor((Date.now() - START_AT) / 1000), profiles, storage_bytes: bytes, packager_url: PACKAGER_URL || null });
  }

  // ---- 档案 CRUD ----
  if (p === '/api/profiles' && req.method === 'GET') {
    return sendJson(res, 200, { profiles: Object.values(PROFILES) });
  }
  if (p === '/api/profiles' && req.method === 'POST') {
    const { body } = await readBody(req, 1 << 20);
    let pf;
    try { pf = JSON.parse(body.toString('utf-8')); } catch { return sendJson(res, 400, { error: 'JSON 非法' }); }
    const err = validateProfile(pf);
    if (err) return sendJson(res, 400, { error: err });
    if (PROFILES[pf.ns]) return sendJson(res, 409, { error: `档案已存在: ${pf.ns}` });
    pf.state = pf.state || 'recording';
    pf.record = { ...GLOBAL.defaults, ...(pf.record || {}) };
    pf.created_at = new Date().toISOString();
    PROFILES[pf.ns] = pf;
    saveProfiles();
    audit('profile.create', { ns: pf.ns });
    return sendJson(res, 201, pf);
  }
  const pm = p.match(/^\/api\/profiles\/([a-z0-9][a-z0-9-]{1,62})(\/.*)?$/);
  if (pm) {
    const [, ns, sub] = pm;
    const pf = PROFILES[ns];
    if (req.method === 'GET' && !sub) {
      if (!pf) return sendJson(res, 404, { error: '档案不存在' });
      return sendJson(res, 200, pf);
    }
    if (req.method === 'PUT' && !sub) {
      if (!pf) return sendJson(res, 404, { error: '档案不存在' });
      const { body } = await readBody(req, 1 << 20);
      let patch;
      try { patch = JSON.parse(body.toString('utf-8')); } catch { return sendJson(res, 400, { error: 'JSON 非法' }); }
      const merged = { ...pf, ...patch, ns }; // ns 不可改
      const err = validateProfile(merged);
      if (err) return sendJson(res, 400, { error: err });
      PROFILES[ns] = merged;
      saveProfiles();
      audit('profile.update', { ns });
      return sendJson(res, 200, merged);
    }
    if (req.method === 'DELETE' && !sub) {
      if (!pf) return sendJson(res, 404, { error: '档案不存在' });
      delete PROFILES[ns];
      saveProfiles();
      audit('profile.delete', { ns });
      return sendJson(res, 200, { deleted: true });
    }
    if (sub === '/state' && req.method === 'POST') {
      if (!pf) return sendJson(res, 404, { error: '档案不存在' });
      const { body } = await readBody(req, 4096);
      let b;
      try { b = JSON.parse(body.toString('utf-8')); } catch { return sendJson(res, 400, { error: 'JSON 非法' }); }
      if (!['recording', 'passthrough', 'disabled'].includes(b.state)) return sendJson(res, 400, { error: 'state 非法' });
      pf.state = b.state;
      saveProfiles();
      audit('profile.state', { ns, state: b.state });
      return sendJson(res, 200, pf);
    }
    if (sub === '/precheck' && req.method === 'POST') {
      if (!pf) return sendJson(res, 404, { error: '档案不存在' });
      const result = await precheckProfile(pf);
      pf.precheck = { last_status: Object.values(result).every((v) => v.ok || (v.mcp ? Object.values(v.mcp).every((x) => x.ok) : false)) ? 'ok' : 'degraded', checked_at: new Date().toISOString(), detail: result };
      saveProfiles();
      return sendJson(res, 200, result);
    }
    if (sub === '/switch-snippet' && req.method === 'GET') {
      if (!pf) return sendJson(res, 404, { error: '档案不存在' });
      return sendJson(res, 200, buildSwitchSnippet(pf, collectorHost, true));
    }
    if (sub === '/restore-snippet' && req.method === 'GET') {
      if (!pf) return sendJson(res, 404, { error: '档案不存在' });
      return sendJson(res, 200, buildSwitchSnippet(pf, collectorHost, false));
    }
  }

  // ---- 交互查询 ----
  if (p === '/api/interactions' && req.method === 'GET') {
    const ns = u.searchParams.get('ns');
    const kind = u.searchParams.get('kind') || 'llm';
    const limit = Math.min(parseInt(u.searchParams.get('limit') || '20', 10) || 20, 200);
    if (!ns || !PROFILES[ns]) return sendJson(res, 400, { error: 'ns 必填且档案须存在' });
    const items = readInteractions(ns, kind, limit);
    return sendJson(res, 200, { items });
  }

  // ---- 脱敏预览 ----
  if (p === '/api/mask-test' && req.method === 'POST') {
    const { body } = await readBody(req, 1 << 20);
    let b;
    try { b = JSON.parse(body.toString('utf-8')); } catch { return sendJson(res, 400, { error: 'JSON 非法' }); }
    const pf = b.ns ? PROFILES[b.ns] : null;
    const rules = maskRulesFor(pf);
    const masked = maskValue(b.text ?? '', rules);
    return sendJson(res, 200, { masked, rule_count: rules.length });
  }

  // ---- 打包（转调 packager sidecar，或返回等价 CLI） ----
  if (p === '/api/pack' && req.method === 'POST') {
    const { body } = await readBody(req, 1 << 20);
    let b;
    try { b = JSON.parse(body.toString('utf-8')); } catch { b = {}; }
    const ns = b.ns || Object.keys(PROFILES)[0];
    if (!ns) return sendJson(res, 400, { error: '无接入档案' });
    if (PACKAGER_URL) {
      try {
        // EVAL_PACKAGER_URL 为完整打包端点（如 http://studio:18400/api/packs/from-collector）
        const resp = await fetch(PACKAGER_URL, {
          method: 'POST', headers: { 'content-type': 'application/json' },
          body: JSON.stringify({ ...b, ns, collector_data: DATA_DIR }),
        });
        const text = await resp.text();
        return sendJson(res, resp.status, safeJson(text, 1 << 20));
      } catch (e) {
        return sendJson(res, 502, { error: `packager 不可达: ${e.message}`, cli: packCli(ns) });
      }
    }
    return sendJson(res, 200, { cli: packCli(ns), note: '未部署 packager sidecar（EVAL_PACKAGER_URL 未设置）' });
  }

  return sendJson(res, 404, { error: 'not found' });
}

function packCli(ns) {
  return `python3 bench/eval/flywheel.py pack --collector-dir ${DATA_DIR}/${ns} --out bench/eval/reports/packs/pk-${ns}`;
}

function buildSwitchSnippet(pf, collectorHost, isSwitch) {
  const addr = {
    llm: `http://${collectorHost}:${PORT_LLM}/${pf.ns}/v1`,
    sandbox: `${collectorHost}:${PORT_SANDBOX}/${pf.ns}`,
  };
  const mcpAddrs = {};
  for (const name of Object.keys(pf.upstream?.mcp || {})) {
    mcpAddrs[name] = `http://${collectorHost}:${PORT_MCP}/mcp/${pf.ns}/${name}`;
  }
  if (isSwitch) {
    return {
      direction: 'switch',
      env_keys: {
        LLM_BASE_URL: addr.llm,
        OPENSANDBOX_SERVER_URL: addr.sandbox,
        ...(pf.upstream.agent ? { AGENT_INTERNAL_URL: `http://${collectorHost}:${PORT_AGENT}/${pf.ns}` } : {}),
      },
      env_patch_curl: `curl -X PATCH "http://${collectorHost}:30880/api/v1/services/{SERVICE_ID}/env" \\\n  -H "Content-Type: application/json" \\\n  -d '{"env_json": {"LLM_BASE_URL": "${addr.llm}", "OPENSANDBOX_SERVER_URL": "${addr.sandbox}"}}'`,
      mcp_config_diff: Object.entries(mcpAddrs).map(([name, url]) =>
        `# mcp-configs/${name}/config.yaml\n  connection:\n    url: ${url}   # 原值: ${pf.upstream.mcp[name]}`),
      note: '敏感键（LLM_API_KEY 等）保持原值；mcp url 改写须 republish 或在线编辑 OAF 包后生效',
    };
  }
  return {
    direction: 'restore',
    env_keys: { LLM_BASE_URL: pf.upstream.llm || '(未配置)', OPENSANDBOX_SERVER_URL: pf.upstream.sandbox || '(未配置)' },
    env_patch_curl: `curl -X PATCH "http://${collectorHost}:30880/api/v1/services/{SERVICE_ID}/env" \\\n  -H "Content-Type: application/json" \\\n  -d '{"env_json": {"LLM_BASE_URL": "${pf.upstream.llm || ''}", "OPENSANDBOX_SERVER_URL": "${pf.upstream.sandbox || ''}"}}'`,
    mcp_config_diff: Object.entries(pf.upstream?.mcp || {}).map(([name, url]) =>
      `# mcp-configs/${name}/config.yaml\n  connection:\n    url: ${url}   # 还原真实上游`),
    note: '还原后建议停止 collector 或将档案置为 passthrough',
  };
}

function readInteractions(ns, kind, limit) {
  const dir = kindDir(ns, kind);
  let files = [];
  try { files = fs.readdirSync(dir).filter((f) => f.endsWith('.jsonl')).sort().reverse(); } catch { return []; }
  const items = [];
  for (const f of files) {
    if (items.length >= limit) break;
    let lines = [];
    try { lines = fs.readFileSync(path.join(dir, f), 'utf-8').split('\n').filter((l) => l.trim()).reverse(); } catch { continue; }
    for (const l of lines) {
      if (items.length >= limit) break;
      try {
        const rec = JSON.parse(l);
        items.push({
          id: rec.id, kind: rec.kind, ns: rec.ns, session: rec.session,
          ts: rec.ts_end || rec.ts_start, status: rec.status, duration_ms: rec.duration_ms,
          summary: summarize(rec), truncated: !!rec.truncated,
        });
      } catch { /* 跳过坏行 */ }
    }
  }
  return items;
}

function summarize(rec) {
  if (rec.kind === 'llm') {
    const msgs = rec.request?.messages;
    if (Array.isArray(msgs)) {
      const lastUser = [...msgs].reverse().find((m) => m.role === 'user');
      const text = typeof lastUser?.content === 'string' ? lastUser.content
        : JSON.stringify(lastUser?.content ?? '');
      return `model=${rec.request?.model || '?'} msgs=${msgs.length} | ${text.slice(0, 80)}`;
    }
    return `${rec.method || 'POST'} ${rec.path || ''}`;
  }
  if (rec.kind === 'mcp') {
    const rq = Array.isArray(rec.request) ? rec.request[0] : rec.request;
    const method = rq?.method || rec.method;
    const tool = rq?.params?.name || '';
    return `${method}${tool ? ` ${tool}` : ''}`;
  }
  if (rec.kind === 'http') {
    return `${rec.method} ${rec.path}${rec.session ? ` [${rec.session}]` : ''}`;
  }
  return `${rec.method} ${rec.path}`;
}

function dirSize(dir) {
  let total = 0;
  const stack = [dir];
  while (stack.length) {
    const cur = stack.pop();
    let entries = [];
    try { entries = fs.readdirSync(cur, { withFileTypes: true }); } catch { continue; }
    for (const e of entries) {
      const fp = path.join(cur, e.name);
      if (e.isDirectory()) stack.push(fp);
      else { try { total += fs.statSync(fp).size; } catch { /* 忽略 */ } }
    }
  }
  return total;
}

// ---------------------------------------------------------------------------
// 启动
// ---------------------------------------------------------------------------

fs.mkdirSync(DATA_DIR, { recursive: true });
fs.mkdirSync(CONF_DIR, { recursive: true });
rebuildCounters();
retentionSweep();
setInterval(retentionSweep, 3600_000).unref();

// 进程级异常兜底（仅运行期）：采集代理是旁路组件，可用性优先——五个端口共享同一进程，
// 任一未捕获异常的默认行为是整进程退出（一崩全崩），这里只记结构化错误日志、不退出。
// 代价（Node 语义）：异常发生后当前这条请求的响应可能永不完成，由客户端自身超时收场。
// 注册时机在启动同步逻辑（数据目录构建等）之后：启动期不可恢复错误（如 DATA_DIR 创建失败）
// 仍走 Node 默认快速崩溃，不被兜底吞成"半初始化但进程存活"的僵尸态。
process.on('uncaughtException', (e) => {
  console.error(`[process][ERROR] ${new Date().toISOString()} uncaughtException: ${e.message}\n${e.stack || ''}`);
});
process.on('unhandledRejection', (reason) => {
  const detail = reason instanceof Error ? `${reason.message}\n${reason.stack || ''}` : String(reason);
  console.error(`[process][ERROR] ${new Date().toISOString()} unhandledRejection: ${detail}`);
});

/** 端口绑定失败属启动期不可恢复错误（如 EADDRINUSE）：server 'error' 若无监听会抛进
 *  上面的进程兜底被吞掉（healthz 假活，e2e 编排器靠"启动即死"判定端口冲突会失灵），
 *  这里显式监听并快速失败退出；运行期异常仍按上方兜底只记日志不退出。 */
function onListenError(port) {
  return (e) => {
    console.error(`[collector][ERROR] ${new Date().toISOString()} 端口 ${port} 监听失败: ${e.message}`);
    process.exit(1);
  };
}

http.createServer(handleLLM).listen(PORT_LLM, () => console.log(`[llm]      :${PORT_LLM}  /{ns}/v1/...`)).on('error', onListenError(PORT_LLM));
http.createServer(handleSandbox).listen(PORT_SANDBOX, () => console.log(`[sandbox]  :${PORT_SANDBOX}  /{ns}/...`)).on('error', onListenError(PORT_SANDBOX));
http.createServer(handleMCP).listen(PORT_MCP, () => console.log(`[mcp]      :${PORT_MCP}  /mcp/{ns}/{server}/...`)).on('error', onListenError(PORT_MCP));
http.createServer(handleAgent).listen(PORT_AGENT, () => console.log(`[agent]    :${PORT_AGENT}  /{ns}/...  （业务服务反代，kind=http 录制）`)).on('error', onListenError(PORT_AGENT));

const adminHost = ADMIN_TOKEN ? '0.0.0.0' : '127.0.0.1';
http.createServer((req, res) => {
  if (req.url === '/' || req.url.startsWith('/index.html')) {
    if (!adminAuth(req, res)) return;
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
    return res.end(fs.readFileSync(path.join(__dirname, 'console.html')));
  }
  handleAdmin(req, res).catch((e) => {
    console.error(`[admin][ERROR] ${e.stack || e.message}`);
    try { sendJson(res, 500, { error: e.message }); } catch { /* 已响应 */ }
  });
}).listen(PORT_ADMIN, adminHost, () => {
  console.log(`[admin]    http://${adminHost}:${PORT_ADMIN}  （${ADMIN_TOKEN ? 'token 已启用' : '未设 EVAL_COLLECTOR_ADMIN_TOKEN，仅本机可访问'}）`);
}).on('error', onListenError(PORT_ADMIN));
console.log(`[collector] v${VERSION} data=${DATA_DIR} conf=${CONF_DIR}`);
