#!/usr/bin/env node
/**
 * replay-llm：LLM 回放器（设计 §4.2）——数据源为 evalpack 中会话的录制交互序列，
 * e2e/mock/llm-server.mjs（场景标记+夹具）的泛化形态。
 *
 * 匹配算法：
 *   1. 请求带 X-Eval-Session → 定位该会话游标（强路由）
 *   2. 否则按归一化首条 user 文本指纹匹配会话（与 bench/eval/replay/normalize.py 同构规则）
 *   3. 均不命中 → 背景调用合成良性响应（不计入主链路游标，title/compaction/memory 类旁路）
 * 主链路匹配：exact（原始请求一致）/ normalized（归一化指纹一致）/ drift（失配仍回放最近邻并计数）。
 * 回放内容 = 录制 chunks 原文（--rewrite from=to 占位改写可选），流式按 data: 载荷逐条重发。
 *
 * 用法：node replay-llm.mjs --pack <dir> --port <p> [--rewrite 'a=b' ...]
 * 端点：POST /v1/chat/completions · GET /stats · POST /reset · GET /healthz
 * 零依赖，Node >= 18。
 */

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

// ---- 参数 ----
const arg = (name, dflt = null) => {
  const i = process.argv.indexOf(`--${name}`);
  return i !== -1 ? process.argv[i + 1] : dflt;
};
const PACK_DIR = arg('pack');
const PORT = parseInt(arg('port', '18902'), 10);
const REWRITES = [];
{
  const argv = process.argv;
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--rewrite') {
      const [from, to = ''] = argv[i + 1].split('=');
      REWRITES.push([from, to]);
    }
  }
}
if (!PACK_DIR) { console.error('--pack 必填'); process.exit(2); }

// ---- 归一化规则（与 bench/eval/replay/normalize.py 同构；改动须两侧同步 + selftest） ----
const RULES = [
  [/\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:?\d{2})?/g, '<TS>'],
  [/(?<![\d.])1[3-9]\d{11}(?![\d])/g, '<TS_MS>'],
  [/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/g, '<UUID>'],
  [/gw-[0-9a-fA-F]{6,}/g, '<GW>'],
  [/(https?:\/\/)?(127\.0\.0\.1|localhost)(:\d+)?/g, '<HOST>'],
  [/(?<![A-Za-z0-9_-])[A-Fa-f0-9]{16,}(?![A-Za-z0-9_-])/g, '<TOKEN>'],
  [/(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{22}(?![A-Za-z0-9_-])/g, '<TOKEN>'],
];
function normalizeText(s) { let out = s; for (const [re, r] of RULES) out = out.replace(re, r); return out; }

function normalizeLlmRequest(req) {
  const messages = (req.messages || []).map((m) => ({
    role: m.role,
    content: typeof m.content === 'string' ? normalizeText(m.content) : normalizeText(JSON.stringify(m.content ?? '')),
  }));
  const tools = (req.tools || []).map((t) => (t.function?.name || t.name || '')).sort();
  const out = { messages };
  if (req.model) out.model = req.model;
  if (tools.length) out.tools = tools;
  return out;
}
function fingerprint(req) {
  return crypto.createHash('sha256').update(JSON.stringify(normalizeLlmRequest(req))).digest('hex');
}
function firstUserText(req) {
  for (const m of req.messages || []) {
    if (m.role === 'user') return normalizeText(typeof m.content === 'string' ? m.content : JSON.stringify(m.content ?? ''));
  }
  return '';
}

// ---- 加载 evalpack ----
const sessions = new Map(); // sid → {calls: [{request, chunks, body}], cursor, stats}
{
  const sdir = path.join(PACK_DIR, 'sessions');
  for (const f of fs.readdirSync(sdir).filter((f) => f.endsWith('.json'))) {
    const s = JSON.parse(fs.readFileSync(path.join(sdir, f), 'utf-8'));
    sessions.set(s.sid, {
      meta: s,
      calls: (s.llm_calls || []).map((id) => {
        const p = path.join(PACK_DIR, 'interactions', 'llm', `${id}.json`);
        return fs.existsSync(p) ? JSON.parse(fs.readFileSync(p, 'utf-8')) : null;
      }).filter(Boolean),
      consumed: new Set(), stats: { calls: 0, exact: 0, normalized: 0, drift: 0, background: 0 },
    });
  }
}
// 会话指纹索引：归一化首条 user 文本 → sid（多会话同锚点时按注册序）
const anchorIndex = [];
for (const [sid, s] of sessions) {
  const anchor = firstUserText(s.calls[0]?.request || {});
  anchorIndex.push({ sid, anchor });
}

function applyRewrites(text) {
  let out = text;
  for (const [from, to] of REWRITES) out = out.split(from).join(to);
  return out;
}

function synthBackground(req) {
  // 背景调用合成良性响应（不偷主链路游标；内容对 agent 无害）
  const isStream = !!req.stream;
  const content = '（回放：背景调用合成应答）';
  if (isStream) {
    return { stream: true, chunks: [
      JSON.stringify({ id: 'synth', object: 'chat.completion.chunk', choices: [{ index: 0, delta: { content }, finish_reason: null }] }),
      JSON.stringify({ id: 'synth', object: 'chat.completion.chunk', choices: [{ index: 0, delta: {}, finish_reason: 'stop' }] }),
      '[DONE]' ] };
  }
  return { stream: false, body: { id: 'synth', object: 'chat.completion',
    choices: [{ index: 0, message: { role: 'assistant', content }, finish_reason: 'stop' }] } };
}

const server = http.createServer((req, res) => {
  const json = (code, obj) => { res.writeHead(code, { 'content-type': 'application/json' }); res.end(JSON.stringify(obj)); };
  if (req.method === 'GET' && req.url === '/healthz') return json(200, { ok: true, sessions: sessions.size });
  if (req.method === 'GET' && req.url === '/stats') {
    const out = {};
    for (const [sid, s] of sessions) out[sid] = { ...s.stats, expected: s.calls.length,
      consumed: s.consumed.size, drift_samples: s.drift_samples || [] };
    return json(200, { sessions: out, rewrites: REWRITES });
  }
  if (req.method === 'GET' && req.url === '/captured') {
    // 回放期实际请求（按会话）：--rebase 生成新基线包的数据源
    const out = {};
    for (const [sid, s] of sessions) out[sid] = s.captured || [];
    return json(200, { sessions: out });
  }
  if (req.method === 'POST' && req.url === '/reset') {
    for (const s of sessions.values()) { s.consumed = new Set(); s.captured = [];
      s.stats = { calls: 0, exact: 0, normalized: 0, drift: 0, background: 0 }; }
    return json(200, { ok: true });
  }
  if (req.method === 'POST' && req.url !== '/v1/chat/completions') return json(404, { error: 'not found' });

  const chunks = [];
  req.on('data', (c) => chunks.push(c));
  req.on('end', () => {
    let body = {};
    try { body = JSON.parse(Buffer.concat(chunks).toString('utf-8')); } catch { /* 按空 */ }
    const sessionHeader = (req.headers['x-eval-session'] || '').trim();

    // 背景调用显式识别（与 packager._is_background 同规则）：memory extraction 特征
    const isBackgroundCall = (body.messages || []).some((m) => {
      if (m.role !== 'system') return false;
      const c = String(m.content || '').toLowerCase();
      return c.includes('memory extraction') || c.includes('会话标题生成助手') || c.includes('generate a concise title');
    });

    // 1) 会话定位
    let sess = null, matchHow = 'header';
    if (!isBackgroundCall) {
      if (sessionHeader && sessions.has(sessionHeader)) sess = sessions.get(sessionHeader);
      if (!sess) {
        const anchor = firstUserText(body);
        const hit = anchorIndex.find((a) => a.anchor && a.anchor === anchor);
        if (hit) { sess = sessions.get(hit.sid); matchHow = 'fingerprint'; }
      }
    }
    if (!sess) {
      // 3) 背景调用
      const synth = synthBackground(body);
      const any = sessions.values().next();
      if (!any.done) any.value.stats.background += 1;
      if (synth.stream) {
        res.writeHead(200, { 'content-type': 'text/event-stream' });
        for (const c of synth.chunks) res.write(`data: ${c}\n\n`);
        return res.end();
      }
      return json(200, synth.body);
    }

    // 2) 序不敏感匹配（未消费集合上找指纹命中）：标题生成等旁路调用与主链并发，
    // 到达顺序在录制与回放间可能互换，严格游标会假漂移。命中优先级 exact > normalized > 漂移。
    const remaining = sess.calls
      .map((c, i) => ({ c, i }))
      .filter((x) => !sess.consumed.has(x.i));
    if (!remaining.length) {
      // 录制件已耗尽：按漂移计（回放调用多于录制——行为变更信号），合成最近邻已无可放
      sess.stats.drift += 1;
      sess.stats.calls += 1;
      const synth = synthBackground(body);
      if (synth.stream) {
        res.writeHead(200, { 'content-type': 'text/event-stream' });
        for (const c of synth.chunks) res.write(`data: ${applyRewrites(c)}\n\n`);
        return res.end();
      }
      return json(200, synth.body);
    }
    const reqStr = JSON.stringify(body);
    const fp = fingerprint(body);
    let hit = remaining.find((x) => JSON.stringify(x.c.request || {}) === reqStr);
    let tier = 'exact';
    if (!hit) {
      hit = remaining.find((x) => fingerprint(x.c.request || {}) === fp);
      if (hit) tier = 'normalized';
    }
    const rec = hit ? hit.c : remaining[0].c;
    sess.consumed.add(hit ? hit.i : remaining[0].i);
    if (!hit) tier = 'drift';
    sess.stats.calls += 1;
    sess.stats[tier] += 1;
    if (tier === 'drift') {
      // 漂移样本（最多 3 条）：录制请求 vs 实际请求的消息形状对比（诊断用）
      sess.drift_samples = sess.drift_samples || [];
      if (sess.drift_samples.length < 3) {
        const brief = (r) => (r.messages || []).map((m) =>
          `${m.role}:${String(typeof m.content === 'string' ? m.content : JSON.stringify(m.content)).slice(0, 80)}`);
        sess.drift_samples.push({ call_id: rec.id, recorded: brief(rec.request || {}),
                                  actual: brief(body) });
      }
    }
    // 实际请求捕获（--rebase 基线演进：用回放期实际请求替换录制请求生成新基线包）
    sess.captured = sess.captured || [];
    sess.captured.push({ id: rec.id, request: body, tier });

    const isStream = !!body.stream;
    if (isStream) {
      res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' });
      const list = (rec.chunks && rec.chunks.length ? rec.chunks : null)
        || (rec.body ? [JSON.stringify(rec.body)] : []);
      for (const c of list) res.write(`data: ${applyRewrites(c)}\n\n`);
      return res.end();
    }
    // 非流式：优先回放录制 body
    const out = rec.body && rec.body.choices ? rec.body
      : { id: 'replay', object: 'chat.completion', choices: [{ index: 0, message: { role: 'assistant', content: '' }, finish_reason: 'stop' }] };
    if (REWRITES.length && typeof out.choices?.[0]?.message?.content === 'string') {
      out.choices[0].message.content = applyRewrites(out.choices[0].message.content);
    }
    return json(200, out);
  });
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`[replay-llm] pack=${PACK_DIR} port=${PORT} sessions=${sessions.size} rewrites=${REWRITES.length}`);
});
