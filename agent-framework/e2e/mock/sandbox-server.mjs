#!/usr/bin/env node
/**
 * mock OpenSandbox Server（e2e-ci-plan §4.4）：线上协议取自本机真实 Server 录制件
 * （mock/fixtures/sandbox/interactions.json），语义层 = 本地目录 + 受控命令执行。
 *
 * 管理 API（:SANDBOX_MOCK_PORT）：
 *   GET    /health                                    → {"status":"healthy"}
 *   POST   /v1/sandboxes                              → 202 创建（根目录 .runtime/sandboxes/{id}/）；
 *                                                        extensions.poolRef = 池认领（warm Pod 分配 + status
 *                                                        变动 + 补位，L6）；expiresAt 按 body.timeout（秒，
 *                                                        sandbox-pool-opensandbox-design §2.3）计算；请求体
 *                                                        全量记录进 /stats.creates（poolRef/metadata/timeout
 *                                                        断言源，L6/L7）
 *   GET    /v1/sandboxes/{id}                         → 200 / 404（destroyed → 404 触发 SDK 降级 create）
 *   GET    /v1/sandboxes/{id}/endpoints/{port}        → {"endpoint":"127.0.0.1:<port>/v1/sandboxes/<id>/proxy/<port>"}
 *   DELETE /v1/sandboxes/{id}                         → 204（池认领沙箱回收：allocated-1，补位 warm Pod 维持 available）
 *   POST   /v1/sandboxes/{id}/renew-expiration        → 200 {expiresAt}（更新内存值 + 计入 /stats.renews，
 *                                                        L4/L5 断言源；renew404 故障或沙箱已删 → 404，L17）
 *   POST   /v1/metrics/events                         → 204（吞掉 SDK 埋点）
 *   POST   /v1/pools                                  → 201 创建池（name/capacitySpec；同名 409）
 *   GET    /v1/pools · /v1/pools/{name}              → 池列表/详情（status{total,allocated,available,revision}）
 *   PUT    /v1/pools/{name}                           → 200（仅 capacitySpec）
 *   DELETE /v1/pools/{name}                           → 204
 *   POST   /admin/destroy/{id}                        → 模拟容器 GC（之后 GET/connect 全 404）
 *   POST   /admin/fault {mvFail,renew404,poolExhausted,slowCommandMs} → 故障注入（{} 清除）：
 *                                                       mvFail：files/upload 对 '.tmp.' 路径 500（X-ENV-8）；
 *                                                       renew404：renew-expiration 一律 404（L17）；
 *                                                       poolExhausted：create(poolRef) 一律 429
 *                                                       KUBERNETES::POOL_CAPACITY_EXHAUSTED（池耗尽面，
 *                                                       报文对齐上游 §11 V5 实测）；slowCommandMs：命令事件流首包延迟
 *   GET    /stats  POST /reset                        → 观测（creates[带请求体]/renews/connects/commands/fileOps/pools）
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
const ROOT = process.env.MOCK_SANDBOX_ROOT ?? path.join(process.cwd(), '.runtime', 'sandboxes');
fs.mkdirSync(ROOT, { recursive: true });

const EXEC_WHITELIST = /^(\|\s*)?(echo|ls|cat|mkdir|tar|base64|rm|mv|printf|test|wc|stat|find|sort|head|tail|grep|sed|sh|true|false)\b/;
const sandboxes = new Map(); // id → {id, root, env, execdEnvsPath, destroyed, createdAt, expiresAt, poolRef}
const pools = new Map();     // name → {name, capacitySpec, warmPods[], allocated{sandboxId→podId}, revision}
const stats = { creates: [], renews: [], connects: [], commands: [], fileOps: [], errors: [], faultHits: 0 };
// 故障注入开关（/admin/fault）：
//   mvFail = 发布用临时文件（path 含 '.tmp.'）写入返回 500，黑盒验证 PUT /sandbox/env 发布失败
//            → 502 → sidecar 未推进 → 调用方重试收敛（issue #230）
//   renew404 / poolExhausted / slowCommandMs = 沙箱生命周期 L 组故障面（§9.2）
const faults = { mvFail: false, renew404: false, poolExhausted: false, slowCommandMs: 0 };

// ---- 池语义（sandbox-pool-opensandbox-design §2.1/§2.2，e2e 断言源）----

let poolRevisionSeq = 0;

/** 建 warm Pod 占位（纯簿记 id，语义层复用沙箱目录——池预热在本 mock 里不落真实容器） */
function warmPodId(poolName) {
  return `${poolName}-${crypto.randomBytes(3).toString('hex')}`;
}

function createPool(name, capacitySpec) {
  const pool = {
    name,
    capacitySpec: { bufferMin: 1, bufferMax: 2, poolMin: 1, poolMax: 3, ...capacitySpec },
    warmPods: [],
    allocated: {},   // sandboxId → podId
    revision: (++poolRevisionSeq).toString(16),
  };
  for (let i = 0; i < pool.capacitySpec.bufferMin; i++) pool.warmPods.push(warmPodId(name));
  pools.set(name, pool);
  return pool;
}

function poolStatus(pool) {
  return {
    total: Object.keys(pool.allocated).length + pool.warmPods.length,
    allocated: Object.keys(pool.allocated).length,
    available: pool.warmPods.length,
    revision: pool.revision,
  };
}

/** 池认领：分配 warm Pod + 补位（recycleStrategy=Delete 归还换新语义，§11 V4/V5 实测口径） */
function poolClaim(poolName, sandboxId) {
  const pool = pools.get(poolName);
  if (!pool) return { error: `pool ${poolName} not found`, status: 404 };
  if (pool.warmPods.length === 0) {
    return { error: 'No Pool capacity became available before the acquisition timeout.' };
  }
  const podId = pool.warmPods.shift();
  pool.allocated[sandboxId] = podId;
  pool.warmPods.push(warmPodId(poolName));   // controller 补位（认领即补，维持 bufferMin 水位）
  return { podId };
}

/** 池回收（DELETE 沙箱）：allocated-1；available 由认领时补位维持（total-1） */
function poolRelease(sandboxId) {
  for (const pool of pools.values()) {
    if (pool.allocated[sandboxId]) {
      delete pool.allocated[sandboxId];
      return pool.name;
    }
  }
  return null;
}

function poolOfSandbox(sandboxId) {
  for (const pool of pools.values()) {
    if (pool.allocated[sandboxId]) return pool;
  }
  return null;
}

/** 模拟 execd bootstrap（components/execd/bootstrap.sh:492-503）：默认并 touch EXECD_ENVS 文件 */
function initExecdEnvs(id, createEnv) {
  const env = createEnv ?? {};
  const envPath = env.EXECD_ENVS || '/opt/opensandbox/.env';
  const mapped = sandboxPath(id, envPath);
  if (mapped) {
    fs.mkdirSync(path.dirname(mapped), { recursive: true });
    // 模拟 code-interpreter.sh:154-156 向 EXECD_ENVS 追加 PATH（语言环境行）
    fs.writeFileSync(mapped, 'PATH=/usr/local/bin:/usr/bin:/bin\n');
  }
  return { env, execdEnvsPath: envPath };
}

/**
 * 模拟**已发布 execd**（server/v0.2.x 线、2026-09-17 前构建）的 UserEnvOverlay：
 * 逐行 `SplitN("=", 2)` + `os.ExpandEnv`，**不解析引号**——`KEY='v'` 会原样读成 `'v'`。
 * 新版 execd 才支持 dotenv 引号（parseQuotedEntry）；框架按裸 `KEY=value` 写入，
 * 两版语义一致（SandboxEnvFile 值约束据此禁止 $、换行、首尾空白、首字符引号）。
 * 本 mock 故意保留旧版语义：若接口回退到引号包裹，X-ENV 字节断言会立即失败。
 */
function execdEnvOverlay(sb) {
  const envPath = sb.execdEnvsPath || '/opt/opensandbox/.env';
  const mapped = sandboxPath(sb.id, envPath);
  const merged = { EXECD_ENVS: envPath }; // execd 进程自身 env 含 EXECD_ENVS（路径发现命令依赖）
  if (!mapped || !fs.existsSync(mapped)) return merged;
  for (const raw of fs.readFileSync(mapped, 'utf8').split('\n')) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const eq = line.indexOf('=');
    if (eq <= 0) continue;
    const key = line.slice(0, eq).trim();
    // 旧版行为：不 TrimSpace 值、不剥引号，仅 os.ExpandEnv
    const value = expandEnvLikeGo(line.slice(eq + 1));
    if (key) merged[key] = value;
  }
  return merged;
}

/** 复刻 Go os.ExpandEnv：$VAR / ${VAR} 按 execd 进程 env 展开，未定义→空串；$$ → env["$"]（空） */
function expandEnvLikeGo(value) {
  return value.replace(/\$(\$|\{[^}]*\}|[A-Za-z0-9_]+)/g, (_m, name) => {
    const key = name.startsWith('{') ? name.slice(1, -1) : name;
    const v = process.env[key];
    return v === undefined ? '' : String(v);
  });
}

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
  return {
    id,
    image: { uri: 'opensandbox/code-interpreter:v1.1.0' },
    status: { state: 'Running', reason: 'CONTAINER_RUNNING', message: 'Sandbox container is running.', lastTransitionAt: now() },
    entrypoint: ['/opt/code-interpreter/code-interpreter.sh'],
    expiresAt: sandboxes.get(id)?.expiresAt ?? new Date(Date.now() + 3600_000).toISOString(),
    createdAt: sandboxes.get(id)?.createdAt ?? now(),
  };
}

async function runCommand(sb, command, onEvent, onEnd) {
  const execId = crypto.randomBytes(16).toString('hex');
  const send = obj => onEvent(JSON.stringify({ ...obj, timestamp: Date.now() }));
  // slowCommand 故障注入（§9.2）：命令事件流首包延迟，配合 mock-llm 的 slow/hang 标记
  // 驱动「长执行期间在途续期」类用例（L5b 的另一种驱动方式）
  if (faults.slowCommandMs > 0) {
    await new Promise(r => setTimeout(r, faults.slowCommandMs));
  }
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
  const mapped = cmd.replace(/\/(tmp|workspace|opt\/opensandbox)(?![.\w])/g,
    (m, kind) => (kind === 'tmp' ? sb.root + '/tmp' : kind === 'workspace' ? sb.root : sb.root + '/opt/opensandbox'));
  const p = spawn('bash', ['-c', mapped], { cwd: sb.root, timeout: 15_000, env: { ...process.env, ...execdEnvOverlay(sb) } });
  let stdout = '', stderr = '';
  p.stdout.on('data', d => { stdout += d; });
  p.stderr.on('data', d => { stderr += d; });
  const code = await new Promise(resolve => p.on('close', resolve).on('error', () => resolve(127)));
  if (stdout) send({ type: 'stdout', text: stdout.replace(/\n$/, '') });
  if (stderr) send({ type: 'stderr', text: stderr.replace(/\n$/, '') });
  stats.commands.push({ sandboxId: sb.id, cmd: command, mappedCmd: mapped, exitCode: code, at: Date.now() });
  if (code === 0) send({ type: 'execution_complete', execution_time: 1 });
  else send({ type: 'error', error: { ename: 'CommandExecError', evalue: String(code), traceback: [`exit status ${code}`] } });
  onEnd();
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
    const json = (code, obj) => { res.writeHead(code, { 'content-type': 'application/json' }); res.end(JSON.stringify(obj)) };
    // 请求日志（排障）：管理面 + 生命周期路由全记，execd 代理/埋点噪音不记
    if (!p.includes('/proxy/') && p !== '/v1/metrics/events') {
      console.log(`[mock-sandbox] ${req.method} ${p}${body.length ? ` body=${body.toString().slice(0, 200)}` : ''} -> started`);
    }

    // 观测/管理
    if (p === '/health') return json(200, { status: 'healthy' });
    if (p === '/stats') return json(200, stats);
    if (p === '/reset') {
      stats.creates = []; stats.renews = []; stats.connects = []; stats.commands = []; stats.fileOps = []; stats.errors = []; stats.faultHits = 0;
      pools.clear();
      faults.mvFail = false; faults.renew404 = false; faults.poolExhausted = false; faults.slowCommandMs = 0;
      // 重置同步清故障注入与池账本，防单用例失败污染后续测试
      return json(200, { status: 'reset' });
    }
    if (p.startsWith('/admin/destroy/')) {
      const id = p.split('/')[3];
      const sb = sandboxes.get(id);
      if (sb) sb.destroyed = true;
      return json(sb ? 200 : 404, { destroyed: !!sb });
    }
    if (p === '/admin/fault' && req.method === 'POST') {
      let requested = {};
      try { requested = JSON.parse(body.toString()) ?? {}; } catch { /* 空体 = 清除 */ }
      faults.mvFail = requested.mvFail === true;
      faults.renew404 = requested.renew404 === true;             // L17：renew 一律 404
      faults.poolExhausted = requested.poolExhausted === true;   // L 池耗尽：create(poolRef) 429
      faults.slowCommandMs = Number(requested.slowCommandMs) || 0;
      return json(200, { faults });
    }

    // ---- /v1/pools（§9.2：内存 CRUD + status 变动，L6/池耗尽断言源）----
    if (p === '/v1/pools' && req.method === 'POST') {
      let req_ = {};
      try { req_ = JSON.parse(body.toString()) ?? {}; } catch { /* 空体 */ }
      const name = String(req_.name ?? '');
      if (!name) return json(400, { message: 'name is required' });
      if (pools.has(name)) return json(409, { message: `pool ${name} already exists` });
      const pool = createPool(name, req_.capacitySpec ?? {});
      return json(201, { name: pool.name, capacitySpec: pool.capacitySpec, status: poolStatus(pool), createdAt: now() });
    }
    if (p === '/v1/pools' && req.method === 'GET') {
      return json(200, [...pools.values()].map(pl => ({ name: pl.name, capacitySpec: pl.capacitySpec, status: poolStatus(pl) })));
    }
    {
      const mPool = /^\/v1\/pools\/([^/]+)$/.exec(p);
      if (mPool) {
        const name = decodeURIComponent(mPool[1]);
        const pool = pools.get(name);
        if (!pool) return json(404, { message: `pool ${name} not found` });
        if (req.method === 'GET') return json(200, { name: pool.name, capacitySpec: pool.capacitySpec, status: poolStatus(pool), createdAt: now() });
        if (req.method === 'PUT') {
          // 上游 PUT /v1/pools/{name} 仅收 capacitySpec（§12-1 已落定）
          let req_ = {};
          try { req_ = JSON.parse(body.toString()) ?? {}; } catch { /* 空 */ }
          if (req_.capacitySpec) pool.capacitySpec = { ...pool.capacitySpec, ...req_.capacitySpec };
          pool.revision = (++poolRevisionSeq).toString(16);
          return json(200, { name: pool.name, capacitySpec: pool.capacitySpec, status: poolStatus(pool) });
        }
        if (req.method === 'DELETE') {
          pools.delete(name);
          res.writeHead(204); return res.end();
        }
      }
    }
    if (p === '/v1/metrics/events') { res.writeHead(204); return res.end(); }

    if (p === '/v1/sandboxes' && req.method === 'POST') {
      let createReq = {};
      try { createReq = JSON.parse(body.toString()) ?? {}; } catch { /* 空 */ }
      let createEnv = createReq.env ?? {};
      const id = crypto.randomUUID();
      const sb = { id, root: path.join(ROOT, id), destroyed: false, createdAt: now(), ...initExecdEnvs(id, createEnv) };
      // 池认领（extensions.poolRef，L6）与池耗尽故障注入（报文对齐上游 §11 V5 实测）
      const poolRef = createReq.extensions?.poolRef;
      if (poolRef != null && poolRef !== '') {
        if (faults.poolExhausted) {
          stats.faultHits += 1;
          return json(429, { code: 'KUBERNETES::POOL_CAPACITY_EXHAUSTED',
            message: 'No Pool capacity became available before the acquisition timeout.' });
        }
        const claim = poolClaim(String(poolRef), id);
        if (claim.error) {
          // 池不存在 → 404（配置错误）；池耗尽 → 429（对齐上游 §11 V5 报文）
          if (claim.status === 404) return json(404, { message: claim.error });
          return json(429, { code: 'KUBERNETES::POOL_CAPACITY_EXHAUSTED', message: claim.error });
        }
        sb.poolRef = String(poolRef);
      }
      // TTL 语义（§2.3）：expiresAt 按 body.timeout（秒）计算，默认 3600s——L4/L5 的
      // expiresAt 后移断言与 GET /v1/sandboxes/{id} 读数同源
      const timeoutSec = Number(createReq.timeout) > 0 ? Number(createReq.timeout) : 3600;
      sb.expiresAt = new Date(Date.now() + timeoutSec * 1000).toISOString();
      fs.mkdirSync(sb.root, { recursive: true });
      sandboxes.set(id, sb);
      // 请求体全量记录（L6 poolRef / L7 metadata.session_id / timeout 断言源）
      stats.creates.push({ id, at: Date.now(),
        body: { extensions: createReq.extensions ?? null, metadata: createReq.metadata ?? null,
                timeout: createReq.timeout ?? null, image: createReq.image ?? null,
                resource: createReq.resource ?? null, entrypoint: createReq.entrypoint ?? null } });
      return json(202, { id, status: { state: 'Running', reason: 'CONTAINER_RUNNING', message: 'Sandbox container started successfully.', lastTransitionAt: now() }, metadata: createReq.metadata ?? {}, extensions: createReq.extensions ?? {}, expiresAt: sb.expiresAt, createdAt: sb.createdAt, entrypoint: createReq.entrypoint ?? ['/opt/code-interpreter/code-interpreter.sh'] });
    }

    const mSb = /^\/v1\/sandboxes\/([0-9a-f-]+)(\/.*)?$/.exec(p);
    if (mSb) {
      const id = mSb[1];
      const rest = mSb[2] ?? '/';
      if (req.method === 'DELETE' && rest === '/') {
        poolRelease(id);   // 池认领沙箱回收（allocated-1；available 由认领补位维持）
        sandboxes.delete(id);
        res.writeHead(204); return res.end();
      }
      // renew-expiration（§2.3 上游契约）：更新内存 expiresAt + 计入 /stats.renews（L4/L5/L17 断言源）
      if (rest === '/renew-expiration' && req.method === 'POST') {
        const sb = getSb(id);
        let req_ = {};
        try { req_ = JSON.parse(body.toString()) ?? {}; } catch { /* 空体 */ }
        const target = String(req_.expiresAt ?? '');
        if (faults.renew404 || !sb) {
          if (faults.renew404) stats.faultHits += 1;
          return json(404, { message: 'sandbox not found' });
        }
        sb.expiresAt = target || sb.expiresAt;
        stats.renews.push({ sandboxId: id, expiresAt: sb.expiresAt, at: Date.now() });
        return json(200, { expiresAt: sb.expiresAt });
      }
      if (rest === '/' && req.method === 'GET') {
        const sb = getSb(id);
        return sb ? json(200, sandboxObj(id)) : json(404, { message: 'sandbox not found' });
      }
      const mEp = /^\/endpoints\/(\d+)$/.exec(rest);
      if (mEp && req.method === 'GET') {
        if (!getSb(id)) return json(404, { message: 'sandbox not found' });
        // 对齐录制件形态：server 代理路径，端口不变
        return json(200, { endpoint: `127.0.0.1:${PORT}/v1/sandboxes/${id}/proxy/${mEp[1]}` });
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
          // 故障注入（X-ENV-8）：发布用临时文件写入失败（仅命中 .env.tmp.<uuid> 形态，
          // 不影响 .json sidecar / 工作区 tar 等其他上传）
          if (faults.mvFail && target.includes('.tmp.')) {
            stats.faultHits += 1;
            res.writeHead(500); return res.end();
          }
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
server.listen(PORT, '127.0.0.1', () => console.log(`[mock-sandbox] :${PORT} root=${ROOT}`));
