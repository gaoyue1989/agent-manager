#!/usr/bin/env node
/**
 * OpenSandbox 协议录制反向代理（e2e-ci-plan §4.4.1，仅开发机使用）。
 *
 * agent-framework(SANDBOX_ENABLED=true, OPENSANDBOX_SERVER_URL=:记录端口) → 本代理 → 真实 OpenSandbox Server(:8090)。
 * 管理 API 与经 server 代理的 execd 请求（/proxy/44772/...）全部走同一个上游端口，
 * 因此一个全量透传代理即可抓全协议交互。
 *
 * 每条交互记录：{ method, path, status, contentType, req: bodyObj|str|null, resp: bodyObj|str|null, sse: [payload]|null }
 * 写入 mock/fixtures/sandbox/interactions.json（追加式，按 /begin /flush 分段成多个文件）。
 *
 * 脱敏：endpoint 类响应中的内网 IP 替换为 127.0.0.1；不落任何请求头。
 */
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const FIXTURE_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'mock', 'fixtures', 'sandbox');
const PORT = Number(process.env.OSB_RECORD_PORT ?? 8091);
const UPSTREAM = process.env.OSB_RECORD_UPSTREAM ?? '127.0.0.1:8090';
if (!UPSTREAM.includes(':')) throw new Error('OSB_RECORD_UPSTREAM 需为 host:port');

let segment = 'interactions';
let entries = [];

function sanitize(s) { return typeof s === 'string' ? s.replace(/\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b/g, '127.0.0.1') : s; }
function tryJson(s) { try { return JSON.parse(s); } catch { return null; } }

function flush() {
  if (!entries.length) return;
  fs.mkdirSync(FIXTURE_DIR, { recursive: true });
  const out = path.join(FIXTURE_DIR, `${segment}.json`);
  let existing = [];
  try { existing = JSON.parse(fs.readFileSync(out, 'utf8')); } catch { /* 新文件 */ }
  fs.writeFileSync(out, JSON.stringify(existing.concat(entries), null, 1));
  console.log(`[record-osb] 追加 ${entries.length} 条 → ${out}`);
  entries = [];
}
process.on('SIGINT', () => { flush(); process.exit(0); });

function readBody(req) { return new Promise(r => { const cs = []; req.on('data', c => cs.push(c)); req.on('end', () => r(Buffer.concat(cs))); }); }

const server = http.createServer(async (req, res) => {
  if (req.method === 'GET' && req.url === '/_recorder/health') { res.writeHead(200).end('ok'); return; }
  if (req.method === 'POST' && req.url === '/_recorder/begin') { const b = JSON.parse((await readBody(req)).toString() || '{}'); flush(); segment = b.segment ?? 'interactions'; res.writeHead(200).end('ok'); return; }
  if (req.method === 'POST' && req.url === '/_recorder/flush') { flush(); res.writeHead(200).end('ok'); return; }

  const reqBody = await readBody(req);
  const rec = { method: req.method, path: req.url, status: 0, contentType: '', req: null, resp: null, sse: null };
  // endpoint 响应改写：把 host:port 指回本录制器，令 execd 代理流量也经过这里被录制
  const isEndpointResp = /\/endpoints\/\d+/.test(req.url);

  const [host, port] = UPSTREAM.split(':');
  // 强制 identity：OkHttp 透明 gzip 会让 SSE 明文行解析失效
  const upReq = http.request({ hostname: host, port: Number(port), path: req.url, method: req.method, headers: { ...req.headers, host: `${host}:${port}`, 'accept-encoding': 'identity' } }, upRes => {
    rec.status = upRes.statusCode;
    rec.contentType = upRes.headers['content-type'] ?? '';
    const isSse = (rec.contentType || '').includes('text/event-stream');
    if (isEndpointResp && !isSse) {
      // 先缓冲，改写 endpoint 字段后再下发（录制件保留原始值）
      const parts = [];
      upRes.on('data', d => parts.push(d));
      upRes.on('end', () => {
        const raw = Buffer.concat(parts).toString();
        try {
          const j = JSON.parse(raw);
          rec.resp = j;
          if (j.endpoint) {
            j.endpoint = j.endpoint.replace(/^[^/]+\//, `127.0.0.1:${PORT}/`);
            rec._endpointRewrittenTo = j.endpoint;
          }
          const out = JSON.stringify(j);
          res.writeHead(upRes.statusCode, { 'content-type': rec.contentType, 'content-length': Buffer.byteLength(out) });
          res.end(out);
        } catch {
          res.writeHead(upRes.statusCode, upRes.headers); res.end(raw);
        }
        rec.req = (() => { const s = reqBody.toString(); const j2 = tryJson(s); return j2 !== null ? j2 : (s.slice(0, 2048) || null); })();
        entries.push(rec);
      });
      return;
    }
    res.writeHead(upRes.statusCode, upRes.headers);
    if (isSse) {
      rec.sse = [];
      rec.req = (() => { const s = reqBody.toString(); const j = tryJson(s); return j !== null ? j : (s.slice(0, 2048) || null); })();
      let buf = '';
      upRes.setEncoding('utf8');
      upRes.on('data', d => {
        res.write(d);
        buf += d;
        let idx;
        while ((idx = buf.indexOf('\n')) >= 0) {
          let line = buf.slice(0, idx); buf = buf.slice(idx + 1);
          if (line.endsWith('\r')) line = line.slice(0, -1);
          if (line.trim()) rec.sse.push(line);   // 保留原始行（SSE data: 或 NDJSON 裸行）
        }
      });
      upRes.on('end', () => { res.end(); entries.push(rec); });
    } else {
      const parts = [];
      upRes.on('data', d => { parts.push(d); res.write(d); });
      upRes.on('end', () => {
        res.end();
        const raw = Buffer.concat(parts).toString();
        const ct = rec.contentType || '';
        if (ct.includes('json')) { const j = tryJson(raw); rec.resp = j !== null ? sanitize(JSON.stringify(j)) : raw.slice(0, 4096); if (j !== null) rec.resp = j; }
        else rec.resp = raw.slice(0, 2048);
        rec.req = (() => { const s = reqBody.toString(); const j = tryJson(s); return j !== null ? j : (s.slice(0, 2048) || null); })();
        entries.push(rec);
      });
    }
  });
  upReq.on('error', e => { console.error('[record-osb] upstream error:', e.message); if (!res.headersSent) res.writeHead(502); res.end('upstream error'); });
  upReq.end(reqBody);
});

server.listen(PORT, '0.0.0.0', () => console.log(`[record-osb] :${PORT} → ${UPSTREAM}`));
