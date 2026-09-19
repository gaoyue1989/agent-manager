#!/usr/bin/env node
/**
 * LLM 录制反向代理（e2e-ci-plan §4.1.1，仅开发机使用——真实密钥只出现在本机）。
 *
 * agent-framework → 本代理(:18091) → 真实 LLM API。
 * 透传请求/响应并把「请求 messages + 流式 SSE data 载荷序列（或非流式 JSON）」
 * 按场景聚合写入 mock/fixtures/llm/<scenario>.json。
 *
 * 只录 body，永不落请求头 —— Authorization 不入库。
 *
 * 控制面（由录制编排脚本调用）：
 *   GET  /health
 *   POST /begin  {"scenario":"plain","model":"mimo-v2.5"}   清空缓冲，设定输出场景
 *   POST /flush  把当前场景缓冲写入夹具文件
 *   POST /stop   flush 后退出
 */
import http from 'node:http';
import https from 'node:https';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const FIXTURE_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'mock', 'fixtures', 'llm');

const PORT = Number(process.env.LLM_RECORD_PORT ?? 18091);
const UPSTREAM = (process.env.LLM_RECORD_UPSTREAM ?? '').replace(/\/+$/, '');
const API_KEY = process.env.LLM_RECORD_API_KEY ?? '';
if (!UPSTREAM || !API_KEY) { console.error('[record-llm] 需要 LLM_RECORD_UPSTREAM / LLM_RECORD_API_KEY'); process.exit(1); }
const u = new URL(UPSTREAM);

let scenario = null, model = '', rewrites = [];
// calls: 有序 LLM 请求 { request:{stream,messages}, chunks:[payload]|null, body|object|null }
let calls = [];

function applyRewrites(s) {
  for (const [from, to] of rewrites) s = s.split(from).join(to);
  return s;
}

function flush() {
  if (!scenario) return;
  if (!calls.length) { console.log(`[record-llm] scenario=${scenario} 无调用，跳过`); return; }
  // 过滤 1：jar 后台记忆提取调用（MemoryFlushMiddleware，非对话链路）
  const isMemoryExtract = c => (c.request.messages ?? []).some(m =>
    m.role === 'system' && String(typeof m.content === 'string' ? m.content : JSON.stringify(m.content)).includes('memory extraction assistant'));
  // 过滤 2：前导裁剪——首个含真实 tool_call 的调用之前的多余调用（模型偶发 NO_REPLY 前置）
  const hasRealToolCall = c => (c.chunks ?? []).some(ch => ch.includes('"tool_calls": [') || ch.includes('"tool_calls":{'));
  let good = calls.filter(c => !isMemoryExtract(c));
  const firstTool = good.findIndex(hasRealToolCall);
  if (firstTool > 0) good = good.slice(firstTool);
  const fixture = {
    scenario, model, recordedAt: new Date().toISOString(), rewrites: rewrites.map(r => r[1]),
    calls: good.map(c => ({ request: { stream: c.request.stream, messages: c.request.messages }, ...(c.request.stream ? { chunks: c.chunks.map(applyRewrites) } : { body: c.body }) })),
  };
  fs.mkdirSync(FIXTURE_DIR, { recursive: true });
  const out = path.join(FIXTURE_DIR, `${scenario}.json`);
  fs.writeFileSync(out, JSON.stringify(fixture, null, 1));
  console.log(`[record-llm] 写入 ${out}（calls=${calls.length}）`);
}
process.on('SIGINT', () => { flush(); process.exit(0); });

function readBody(req) { return new Promise(r => { const cs = []; req.on('data', c => cs.push(c)); req.on('end', () => r(Buffer.concat(cs))); }); }

const server = http.createServer(async (req, res) => {
  if (req.method === 'GET' && req.url === '/health') { res.writeHead(200, { 'content-type': 'application/json' }).end('{"status":"recording"}'); return; }
  if (req.method === 'POST' && req.url === '/begin') {
    const b = JSON.parse((await readBody(req)).toString() || '{}');
    flush(); scenario = b.scenario; model = b.model ?? ''; rewrites = b.rewrite ?? []; calls = [];
    res.writeHead(200).end('ok'); return;
  }
  if (req.method === 'POST' && req.url === '/flush') { flush(); scenario = null; calls = []; res.writeHead(200).end('ok'); return; }
  if (req.method === 'POST' && req.url === '/stop') { flush(); res.writeHead(200).end('ok'); process.exit(0); }

  const reqBody = await readBody(req);
  const isStream = (() => { try { return !!JSON.parse(reqBody.toString()).stream; } catch { return false; } })();
  const recordable = req.url.includes('/chat/completions');
  // 每请求独立记录对象（jar 的主对话与后台记忆提取会并发流式——共享变量会互相覆盖丢数据）
  let rec = null;
  if (recordable) {
    let messages = [];
    try { messages = JSON.parse(reqBody.toString()).messages ?? []; } catch { /* 非 JSON 不录 messages */ }
    rec = { request: { stream: isStream, messages }, chunks: [], body: null };
  }

  // 上游路径：LLM_BASE_URL 已含 /v1（agent 请求路径原样透传）；否则前缀上游 pathname
  const upPath = req.url.startsWith(u.pathname.replace(/\/+$/, '') + '/') ? req.url : u.pathname.replace(/\/+$/, '') + req.url;
  const transport = u.protocol === 'https:' ? https : http;
  const upReq = transport.request({ protocol: u.protocol, hostname: u.hostname, port: u.port || (u.protocol === 'https:' ? 443 : 80), path: upPath, method: req.method, headers: { 'content-type': req.headers['content-type'] ?? 'application/json', authorization: `Bearer ${API_KEY}` } }, upRes => {
    res.writeHead(upRes.statusCode, upRes.headers);
    if (isStream && recordable) {
      let buf = '';
      let pushed = false;
      const pushOnce = () => { if (!pushed && rec) { calls.push(rec); pushed = true; } };
      upRes.setEncoding('utf8');
      upRes.on('data', d => {
        res.write(d);
        buf += d;
        let idx;
        while ((idx = buf.indexOf('\n')) >= 0) {
          let line = buf.slice(0, idx); buf = buf.slice(idx + 1);
          if (line.endsWith('\r')) line = line.slice(0, -1);
          if (line.startsWith('data:')) rec?.chunks.push(line.slice(5).trim());
        }
      });
      // HITL 挂起时 SDK 会中途 cancel 上游流：end 可能不触发，必须以 close 兜底落盘
      upRes.on('end', () => { res.end(); pushOnce(); });
      upRes.on('close', () => { pushOnce(); });
    } else {
      const parts = [];
      upRes.on('data', d => { parts.push(d); res.write(d); });
      upRes.on('end', () => {
        res.end();
        if (recordable && rec) { try { rec.body = JSON.parse(Buffer.concat(parts).toString()); } catch { /* 跳过坏体 */ } calls.push(rec); rec = null; }
      });
    }
  });
  upReq.on('error', e => { console.error('[record-llm] upstream error:', e.message); if (!res.headersSent) res.writeHead(502); res.end('upstream error'); });
  upReq.end(reqBody);
});

server.listen(PORT, '127.0.0.1', () => console.log(`[record-llm] :${PORT} → ${UPSTREAM}`));
