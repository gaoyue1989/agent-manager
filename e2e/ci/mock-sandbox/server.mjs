#!/usr/bin/env node
/**
 * mock OpenSandbox Server（e2e-ci-plan §4.4）：线上协议取自本机真实 Server 录制件
 * （mock/fixtures/sandbox/interactions.json），语义层 = 本地目录 + 受控命令执行。
 *
 * 管理 API（:SANDBOX_MOCK_PORT）：
 *   GET    /health                                    → {"status":"healthy"}
 *   POST   /v1/sandboxes                              → 202 创建（根目录 .runtime/sandboxes/{id}/；
 *                                                        记录 metadata/extensions.poolRef/timeout）
 *   GET    /v1/sandboxes                              → 200 分页列表（state/metadata 过滤 + page/pageSize，
 *                                                        m12 沙箱管理面数据源；release-1.1.0 语法；
 *                                                        metadata 非法编码 → 400，对齐上游）
 *   GET    /v1/sandboxes/{id}                         → 200 / 404（destroyed → 404 触发 SDK 降级 create）
 *   GET    /v1/sandboxes/{id}/endpoints/{port}        → {"endpoint":"127.0.0.1:<port>/v1/sandboxes/<id>/proxy/<port>"}
 *   POST   /v1/sandboxes/{id}/renew-expiration        → 200 {expiresAt}（TTL 续期；expiresAt 非严格
 *                                                        更晚 → 409，对齐上游）
 *   DELETE /v1/sandboxes/{id}                         → 204（poolRef 认领的归还池 available）
 *   POST   /v1/pools                                  → 201 建池（name/template/capacitySpec；
 *                                                        409 同名；模板/容量原样记账 stats.pools）
 *   GET    /v1/pools | /v1/pools/{name}               → 200 列表/详情（status{total,allocated,available}）
 *   PUT    /v1/pools/{name}                           → 200 仅 capacitySpec
 *   DELETE /v1/pools/{name}                           → 204
 *   POST   /v1/metrics/events                         → 204（吞掉 SDK 埋点）
 *   POST   /admin/destroy/{id}                        → 模拟容器 GC（之后 GET/connect 全 404）
 *   POST   /admin/fault                               → 故障注入开关（m12 上游故障分支）：
 *                                                        {"mode":"pools501"|"unauthorized"|"poolExhausted"|"renew404","on":true|false}
 *                                                        / {"reset":true} 全清；GET /admin/fault 查看当前
 *   GET    /stats  POST /reset                        → 观测（X 组断言：creates/connects/commands/fileOps；
 *                                                        pools 记录建池载荷供 m12 断言）
 *
 * execd 代理（同端口 /v1/sandboxes/{id}/proxy/{port}/...）：
 *   GET  /ping            → 200 空体
 *   POST /command         → NDJSON 事件流：init → ping → stdout/stderr* → execution_complete|error
 *                           命令白名单内经 child_process 以沙箱根目录为 cwd 真实执行
 *   POST /files/upload    → multipart（metadata JSON{path} + file base64）→ 写入沙箱根
 *   GET  /files/download?path= → 文件字节 / 404
 *   GET  /directories/list?path= → 404（对齐录制件中目录缺失行为，SDK 侧 warn 容忍）
 */
import http from 'node:http';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const PORT = Number(process.env.MOCK_SANDBOX_PORT ?? 8090);
// 容器内需绑定 0.0.0.0；endpoint 返回集群可达地址（本地默认 127.0.0.1）
const PUBLIC_HOST = process.env.MOCK_SANDBOX_PUBLIC_HOST || '127.0.0.1';
const ROOT = process.env.MOCK_SANDBOX_ROOT ?? path.join(process.cwd(), '.runtime', 'sandboxes');
fs.mkdirSync(ROOT, { recursive: true });

const EXEC_WHITELIST = /^(\|\s*)?(echo|ls|cat|mkdir|tar|base64|rm|printf|test|wc|stat|find|sort|head|tail|grep|sed|sh|true|false)\b/;
const sandboxes = new Map(); // id → {root, destroyed, createdAt, metadata, poolRef, expiresAt}
const pools = new Map();     // name → {name, template, capacitySpec, allocated, createdAt}
const stats = { creates: [], connects: [], commands: [], fileOps: [], errors: [], pools: [] };
const faults = { pools501: false, unauthorized: false, poolExhausted: false, renew404: false }; // m12 故障注入

const now = () => new Date().toISOString();
function sandboxPath(id, rel) {
  return sandboxJoin(path.join(ROOT, id), rel);
}
/** 以沙箱根为基解析相对/容器路径并钳制在根内（防越界） */
function sandboxJoin(root, rel) {
  const p = path.resolve(root, '.' + path.sep + String(rel ?? '').replace(/^\/workspace\/?/, ''));
  return p.startsWith(root) ? p : null;
}
function getSb(id) {
  const sb = sandboxes.get(id);
  if (!sb || sb.destroyed) return null;
  return sb;
}
function sandboxObj(id) {
  const sb = sandboxes.get(id);
  return {
    id,
    image: { uri: 'opensandbox/code-interpreter:v1.1.0' },
    status: { state: 'Running', reason: 'CONTAINER_RUNNING', message: 'Sandbox container is running.', lastTransitionAt: now() },
    entrypoint: ['/opt/code-interpreter/code-interpreter.sh'],
    expiresAt: sb?.expiresAt ?? new Date(Date.now() + 3600_000).toISOString(),
    metadata: sb?.metadata ?? {},
    createdAt: sb?.createdAt ?? now(),
  };
}

function poolObj(name) {
  const p = pools.get(name);
  if (!p) return null;
  const total = Number(p.capacitySpec?.poolMin ?? 0) + 1; // 确定性水位：warm Pod = poolMin，1 个可被认领
  const allocated = p.allocated ?? 0;
  return {
    name: p.name,
    capacitySpec: p.capacitySpec,
    status: { total, allocated, available: Math.max(0, total - allocated), revision: 'mock-rev-1' },
    createdAt: p.createdAt,
  };
}

function runCommand(sb, command, onEvent, onEnd) {
  const execId = crypto.randomBytes(16).toString('hex');
  const send = obj => onEvent(JSON.stringify({ ...obj, timestamp: Date.now() }));
  send({ type: 'init', text: execId });
  send({ type: 'ping', text: 'pong' });
  if (!EXEC_WHITELIST.test(command.trim())) {
    stats.commands.push({ sandboxId: sb.id, cmd: command, whitelisted: false, at: Date.now() });
    send({ type: 'stderr', text: `mock-sandbox: command not in e2e whitelist: ${command.slice(0, 80)}` });
    send({ type: 'error', error: { ename: 'CommandExecError', evalue: '126', traceback: ['whitelist rejected'] } });
    onEnd();
    return;
  }
  // SDK 对 WriteEntry.data 的线上编码存在两种形态（base64 文本 / 原始字节，随调用路径不同）：
  // 若 staging 文件不是合法 base64，则 hydrate 管道的 base64 -d 段必然失败，改写为直接 tar 解包。
  let cmd = command;
  const mB64 = /base64 -d (\S+) \|/.exec(cmd);
  if (mB64) {
    const staged = sandboxJoin(sb.root, mB64[1]);
    if (staged && fs.existsSync(staged)) {
      const head = fs.readFileSync(staged).subarray(0, 64).toString('latin1');
      if (!/^[A-Za-z0-9+/=\r\n]+$/.test(head)) {
        // SDK 对 staging 文件的线上字节存在 base64 文本/原始二进制两种形态；后者跳过解码段
        cmd = cmd.replace(/base64 -d \S+ \|/, 'cat ');
      }
    }
  }
  // 绝对路径映射：容器内 /workspace、/tmp ↔ 沙箱根。单遍替换 + 负向前瞻，
  // 避免 /tmp/workspace.tar.b64 里 '/workspace' 子串被误替换（两遍 replace 会互相踩）。
  const mapped = cmd.replace(/\/(tmp|workspace)(?![.\w])/g, (m, kind) => (kind === 'tmp' ? sb.root + '/tmp' : sb.root));
  const p = spawn('bash', ['-c', mapped], { cwd: sb.root, timeout: 15_000 });
  let stdout = '', stderr = '';
  p.stdout.on('data', d => { stdout += d; });
  p.stderr.on('data', d => { stderr += d; });
  const code = new Promise(resolve => p.on('close', resolve).on('error', () => resolve(127)));
  code.then(c => {
    if (stdout) send({ type: 'stdout', text: stdout.replace(/\n$/, '') });
    if (stderr) send({ type: 'stderr', text: stderr.replace(/\n$/, '') });
    stats.commands.push({ sandboxId: sb.id, cmd: command, mappedCmd: mapped, exitCode: c, at: Date.now() });
    if (c === 0) send({ type: 'execution_complete', execution_time: 1 });
    else send({ type: 'error', error: { ename: 'CommandExecError', evalue: String(c), traceback: [`exit status ${c}`] } });
    onEnd();
  });
}

/** 最小 multipart 解析：返回 [{name, filename, body(Buffer)}] */
function parseMultipart(buf, contentType) {
  const m = /boundary=(?:"([^"]+)"|([^;]+))/.exec(contentType ?? '');
  if (!m) return [];
  const boundary = '--' + (m[1] || m[2]);
  const parts = [];
  const str = buf;
  let idx = str.indexOf(boundary);
  while (idx >= 0) {
    const next = str.indexOf(boundary, idx + boundary.length);
    if (next < 0) break;
    let seg = str.slice(idx + boundary.length, next);
    if (seg.slice(0, 2).toString().startsWith('\r\n')) seg = seg.slice(2);
    const headEnd = seg.indexOf('\r\n\r\n');
    if (headEnd > 0) {
      const head = seg.slice(0, headEnd).toString();
      const body = seg.slice(headEnd + 4);
      if (body.slice(-2).toString() === '\r\n') seg_body = body.slice(0, -2); else seg_body = body;
      const name = /name="([^"]*)"/.exec(head)?.[1] ?? '';
      const filename = /filename="([^"]*)"/.exec(head)?.[1] ?? '';
      parts.push({ name, filename, body: seg_body });
    }
    idx = next;
  }
  return parts;
}
let seg_body; // parseMultipart 内部使用的局部变量（node 顶层 var 语义）

const server = http.createServer((req, res) => {
  const chunks = [];
  req.on('data', c => chunks.push(c));
  req.on('end', () => {
    const body = Buffer.concat(chunks);
    const u = new URL(req.url, `http://127.0.0.1:${PORT}`);
    const p = u.pathname;
    const json = (code, obj) => { res.writeHead(code, { 'content-type': 'application/json' }); res.end(JSON.stringify(obj)); };

    // 观测/管理
    if (p === '/health') return json(200, { status: 'healthy' });
    if (p === '/stats') return json(200, stats);
    if (p === '/reset') { stats.creates = []; stats.connects = []; stats.commands = []; stats.fileOps = []; stats.errors = []; stats.pools = []; return json(200, { status: 'reset' }); }
    if (p === '/admin/fault' && req.method === 'GET') return json(200, faults);
    if (p === '/admin/fault' && req.method === 'POST') {
      let f = {};
      try { f = JSON.parse(body.toString() || '{}'); } catch { /* 空体 */ }
      if (f.reset) { for (const k of Object.keys(faults)) faults[k] = false; return json(200, faults); }
      if (typeof f.mode === 'string' && f.mode in faults) {
        faults[f.mode] = f.on !== false;
        return json(200, faults);
      }
      return json(400, { message: 'mode must be one of ' + Object.keys(faults).join('|') + ' (or {"reset":true})' });
    }
    if (p.startsWith('/admin/destroy/')) {
      const id = p.split('/')[3];
      const sb = sandboxes.get(id);
      if (sb) sb.destroyed = true;
      return json(sb ? 200 : 404, { destroyed: !!sb });
    }
    if (p === '/v1/metrics/events') { res.writeHead(204); return res.end(); }

    // ---- 池管理（m12：/v1/pools 内存 CRUD；faults.pools501/unauthorized 注入上游故障分支）----
    if (p === '/v1/pools' || p.startsWith('/v1/pools/')) {
      if (faults.pools501) return json(501, { code: 'K8S_POOL_NOT_SUPPORTED', message: 'mock fault: pools501' });
      if (faults.unauthorized) return json(401, { message: 'mock fault: unauthorized' });
      const poolName = p.startsWith('/v1/pools/') ? p.slice('/v1/pools/'.length) : '';
      if (req.method === 'POST' && p === '/v1/pools') {
        let req2 = {};
        try { req2 = JSON.parse(body.toString() || '{}'); } catch { /* 空体 */ }
        if (!req2.name || !req2.capacitySpec) return json(400, { message: 'name and capacitySpec are required' });
        if (pools.has(req2.name)) return json(409, { code: 'POOL_ALREADY_EXISTS', message: `pool ${req2.name} already exists` });
        pools.set(req2.name, { name: req2.name, template: req2.template ?? null, capacitySpec: req2.capacitySpec, allocated: 0, createdAt: now() });
        stats.pools.push({ name: req2.name, template: req2.template ?? null, capacitySpec: req2.capacitySpec, at: Date.now() });
        return json(201, poolObj(req2.name));
      }
      if (req.method === 'GET' && p === '/v1/pools') return json(200, { items: [...pools.keys()].map(poolObj) });
      if (poolName && req.method === 'GET') {
        const po = poolObj(poolName);
        return po ? json(200, po) : json(404, { message: `pool ${poolName} not found` });
      }
      if (poolName && req.method === 'PUT') {
        const po = pools.get(poolName);
        if (!po) return json(404, { message: `pool ${poolName} not found` });
        let req2 = {};
        try { req2 = JSON.parse(body.toString() || '{}'); } catch { /* 空体 */ }
        if (!req2.capacitySpec) return json(400, { message: 'capacitySpec is required' });
        po.capacitySpec = req2.capacitySpec; // 仅容量（release-1.1.0 UpdatePoolRequest）
        return json(200, poolObj(poolName));
      }
      if (poolName && req.method === 'DELETE') {
        if (!pools.delete(poolName)) return json(404, { message: `pool ${poolName} not found` });
        res.writeHead(204); return res.end();
      }
    }

    if (p === '/v1/sandboxes' && req.method === 'GET') {
      // 分页列表（release-1.1.0 语法：state 可重复 OR、metadata=k%3Dv、page/pageSize≤200）
      const states = u.searchParams.getAll('state');
      let items = [...sandboxes.entries()].filter(([, sb]) => !sb.destroyed).map(([id]) => sandboxObj(id));
      if (states.length) items = items.filter(it => states.includes(it.status.state));
      const rawMeta = u.searchParams.get('metadata');
      if (rawMeta) {
        // 上游契约（release-1.1.0 实测）：metadata 为 URL 编码 k=v 串，非法编码 → 400。
        // URLSearchParams 对任意串都不抛错，必须显式校验段形态——否则 garbage 静默滤空
        // 返回 200 空列表，与真实上游脱节（保真度修订，评审遗留）
        if (!/^[^=&]+=[^=&]*(&[^=&]+=[^=&]*)*$/.test(rawMeta)) {
          return json(400, { message: 'invalid metadata format' });
        }
        const kv = Object.fromEntries(new URLSearchParams(rawMeta));
        items = items.filter(it => Object.entries(kv).every(([k, v]) => it.metadata?.[k] === v));
      }
      const page = Math.max(1, Number(u.searchParams.get('page') ?? 1));
      const pageSize = Math.min(200, Math.max(1, Number(u.searchParams.get('pageSize') ?? 20)));
      const totalItems = items.length;
      const totalPages = Math.ceil(totalItems / pageSize);
      items = items.slice((page - 1) * pageSize, page * pageSize);
      return json(200, { items, pagination: { page, pageSize, totalItems, totalPages, hasNextPage: page < totalPages } });
    }

    if (p === '/v1/sandboxes' && req.method === 'POST') {
      if (faults.unauthorized) return json(401, { message: 'mock fault: unauthorized' });
      let req2 = {};
      try { req2 = JSON.parse(body.toString() || '{}'); } catch { /* 空体 */ }
      if (faults.poolExhausted && req2?.extensions?.poolRef) {
        return json(429, { code: 'KUBERNETES::POOL_CAPACITY_EXHAUSTED', message: 'No Pool capacity became available before the acquisition timeout.' });
      }
      const id = crypto.randomUUID();
      const poolRef = req2?.extensions?.poolRef ?? null;
      const ttlSec = Number(req2?.timeout ?? 3600);
      const sb = {
        root: path.join(ROOT, id), destroyed: false, createdAt: now(),
        metadata: req2?.metadata ?? {}, poolRef,
        expiresAt: new Date(Date.now() + ttlSec * 1000).toISOString(),
      };
      fs.mkdirSync(sb.root, { recursive: true });
      sandboxes.set(id, sb);
      if (poolRef && pools.has(poolRef)) {
        const po = pools.get(poolRef);
        po.allocated += 1; // 认领占坑（简化模型：删除时归还）
      }
      stats.creates.push({ id, at: Date.now(), poolRef, metadata: sb.metadata });
      return json(202, { id, status: { state: 'Running', reason: 'CONTAINER_RUNNING', message: 'Sandbox container started successfully.', lastTransitionAt: now() }, metadata: sb.metadata, expiresAt: sb.expiresAt, createdAt: sb.createdAt, entrypoint: ['/opt/code-interpreter/code-interpreter.sh'] });
    }

    const mSb = /^\/v1\/sandboxes\/([0-9a-f-]+)(\/.*)?$/.exec(p);
    if (mSb) {
      const id = mSb[1];
      const rest = mSb[2] ?? '/';
      if (req.method === 'DELETE' && rest === '/') {
        const sb = sandboxes.get(id);
        if (sb?.poolRef && pools.has(sb.poolRef)) {
          const po = pools.get(sb.poolRef);
          po.allocated = Math.max(0, (po.allocated ?? 0) - 1); // 归还坑位（recycleStrategy=Delete 语义简化）
        }
        sandboxes.delete(id);
        res.writeHead(204); return res.end();
      }
      if (rest === '/' && req.method === 'GET') {
        const sb = getSb(id);
        return sb ? json(200, sandboxObj(id)) : json(404, { message: 'sandbox not found' });
      }
      if (rest === '/renew-expiration' && req.method === 'POST') {
        if (faults.renew404) return json(404, { message: 'mock fault: renew404' });
        const sb = getSb(id);
        if (!sb) return json(404, { message: 'sandbox not found' });
        let req2 = {};
        try { req2 = JSON.parse(body.toString() || '{}'); } catch { /* 空体 */ }
        const next = req2?.expiresAt ?? new Date(Date.now() + 3600_000).toISOString();
        // 上游契约（release-1.1.0）：expiresAt 必须严格晚于当前值，否则 409
        //（防时钟回拨/同值重复续期；保真度修订，评审遗留）
        if (new Date(next).getTime() <= new Date(sb.expiresAt ?? 0).getTime()) {
          return json(409, { message: 'expiresAt must be strictly later than the current expiration' });
        }
        sb.expiresAt = next;
        return json(200, { expiresAt: next });
      }
      const mEp = /^\/endpoints\/(\d+)$/.exec(rest);
      if (mEp && req.method === 'GET') {
        if (!getSb(id)) return json(404, { message: 'sandbox not found' });
        // 对齐录制件形态：server 代理路径，端口不变
        return json(200, { endpoint: `${PUBLIC_HOST}:${PORT}/v1/sandboxes/${id}/proxy/${mEp[1]}` });
      }
      const mProxy = /^\/proxy\/\d+(\/.*)$/.exec(rest);
      if (mProxy) {
        const sb = getSb(id);
        if (!sb) return json(404, { message: 'sandbox not found' });
        const route = mProxy[1];
        if (route === '/ping' && req.method === 'GET') { res.writeHead(200); return res.end(); }
        if (route === '/command' && req.method === 'POST') {
          let cmdReq = {};
          try { cmdReq = JSON.parse(body.toString()); } catch { /* 空 */ }
          res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' });
          runCommand(sb, String(cmdReq.command ?? ''), line => res.write(line + '\n'), () => res.end());
          return;
        }
        if (route === '/files/upload' && req.method === 'POST') {
          const parts = parseMultipart(body, req.headers['content-type']);
          const meta = parts.find(x => x.name === 'metadata');
          const file = parts.find(x => x.name === 'file');
          let target = null;
          if (meta) {
            try {
              const mj = JSON.parse(meta.body.toString());
              target = sandboxPath(id, String(mj.path ?? ''));
            } catch { /* 元数据坏包 */ }
          }
          if (!target) { stats.errors.push({ op: 'files/upload', at: Date.now() }); res.writeHead(400); return res.end(); }
          fs.mkdirSync(path.dirname(target), { recursive: true });
          // 对齐真实 execd：part body 原样落盘（base64 文本文件保持文本，供后续命令解码）
          const bytes = file?.body ?? Buffer.alloc(0);
          fs.writeFileSync(target, bytes);
          stats.fileOps.push({ op: 'upload', sandboxId: id, path: target, size: bytes.length, at: Date.now() });
          res.writeHead(200); return res.end();
        }
        if (route === '/files/download' && req.method === 'GET') {
          const target = sandboxPath(id, u.searchParams.get('path') ?? '');
          if (!target || !fs.existsSync(target) || !fs.statSync(target).isFile()) { res.writeHead(404); return res.end(); }
          stats.fileOps.push({ op: 'download', sandboxId: id, path: target, at: Date.now() });
          res.writeHead(200, { 'content-type': 'application/octet-stream' });
          return res.end(fs.readFileSync(target));
        }
        if (route === '/directories/list' && req.method === 'GET') {
          const target = sandboxPath(id, u.searchParams.get('path') ?? '');
          if (!target || !fs.existsSync(target) || !fs.statSync(target).isDirectory()) { res.writeHead(404); return res.end(); }
          const entries = fs.readdirSync(target).map(name => {
            const st = fs.statSync(path.join(target, name));
            return { path: '/' + name, is_dir: st.isDirectory(), size: st.isFile() ? st.size : 0, mod_time: Math.floor(st.mtimeMs / 1000), permission: st.isDirectory() ? 755 : 644, owner: 'root', group: 'root' };
          });
          return json(200, entries);
        }
      }
    }

    json(404, { message: `mock-sandbox: no route ${req.method} ${p}` });
  });
});

process.on('uncaughtException', e => console.error('[mock-sandbox] uncaught:', e.message));
process.on('unhandledRejection', e => console.error('[mock-sandbox] unhandled:', String(e).slice(0, 200)));
server.listen(PORT, '0.0.0.0', () => console.log(`[mock-sandbox] :${PORT} root=${ROOT} public=${PUBLIC_HOST}`));
