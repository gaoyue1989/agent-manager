/**
 * bench load runner — 闭环并发压测（零三方依赖，见 concurrency-benchmark-plan.md §6/§7）。
 *
 * 用法：
 *   node runner.js --mode warmup --base-url http://127.0.0.1:8101 --session-pool 10 \
 *                  --scenario B1 --results-dir ../results
 *   node runner.js --mode run --scenario B1 --stage 8 --concurrency 8 \
 *                  --ramp-seconds 15 --stage-seconds 180 --session-pool 8 ...
 *
 * 口径：
 *   请求 = POST /threads/chat → SSE 收到 AGENT_END；error / permission_ask /
 *   无终帧断连 / 5xx / 超时(120s) 均为失败。
 *   计时窗口：ramp（线性拉起）+ 稳态固定窗口；仅稳态窗口内**发起**的请求计入指标。
 *   停止条件（终止当前档）：错误率 >5%（≥20 样本）/ P95 >30s（≥20 样本）/ 服务失联。
 *
 * 产物：{results-dir}/{scenario}/{stage}.jsonl（每请求一行）、
 *       {stage}.observers.jsonl（5s 周期采样）、{stage}.summary.json（档汇总）。
 */
'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

// ===== 参数 =====
function arg(name, def) {
  const i = process.argv.indexOf('--' + name);
  return i > -1 ? process.argv[i + 1] : def;
}

const MODE = arg('mode', 'run');                 // run | warmup
const SCENARIO = arg('scenario', 'B1');
const STAGE = arg('stage', '1');
const CONCURRENCY = parseInt(arg('concurrency', '1'), 10);
const RAMP_SECONDS = parseInt(arg('ramp-seconds', '15'), 10);
const STAGE_SECONDS = parseInt(arg('stage-seconds', '180'), 10);
// 会话池（本实现中沙箱隔离键 = sessionId，见 ChatUiRequest.withPeer —— 方案 §6.1 的
// "userId 池"落地为"会话池"：池内会话复用（预热建沙箱，稳态 resume），池大小 ≈ 沙箱数）
const SESSION_POOL = parseInt(arg('session-pool', '10'), 10);
const BASE_URL = arg('base-url', 'http://127.0.0.1:8101');
const RESULTS_DIR = arg('results-dir', path.join(__dirname, '..', 'results'));
const REQ_TIMEOUT_MS = parseInt(arg('req-timeout-ms', '120000'), 10);
const P95_ABORT_MS = parseInt(arg('p95-abort-ms', '30000'), 10);
const ERROR_RATE_ABORT = parseFloat(arg('error-rate-abort', '0.05'));
const MIN_SAMPLES = parseInt(arg('min-samples', '20'), 10);
// 同会话最小 turn 间隔。实测：沙箱模式下一 turn 的 POST_CALL stop()（工作区/记忆回写）
// 与下一 turn 复用同一沙箱实例存在竞态——背靠背（0 间隔）时 ~50% turn 被上一 turn 的
// stop() 中途停掉而静默死亡（详见压测报告缺陷发现）。≥500ms 间隔实测 100% 稳定。
// B0（非沙箱）无此问题，保持 0 间隔测纯容量。
const MIN_TURN_GAP_MS = parseInt(arg('min-turn-gap-ms', SCENARIO === 'B0' ? '0' : '500'), 10);

// 场景 → 消息标记（mock-llm 状态机据此决定轮数与工具）
const SCENARIO_MARKER = {
  B0: '[BENCH:plain]', B1: '[BENCH:plain]', B2: '[BENCH:file]',
  B3: '[BENCH:shell]', B4: '[BENCH:mcp]', B5: '[BENCH:plain]',
};
const MARKER = SCENARIO_MARKER[SCENARIO] || '[BENCH:plain]';
/** 每成功请求的期望 LLM 调用数（plain=1，工具场景=2），用于与 mock /stats 对账 */
const EXPECTED_LLM_CALLS = MARKER === '[BENCH:plain]' ? 1 : 2;

const base = new URL(BASE_URL);
const scenarioDir = path.join(RESULTS_DIR, SCENARIO);
fs.mkdirSync(scenarioDir, { recursive: true });

// ===== 状态 =====
let aborted = false;
let abortReason = null;
const metrics = [];          // 稳态窗口记录
const errorSamples = [];     // 前 50 条错误全量留存
let healthFails = 0;
let stageT0 = 0;             // 档起始时间（evaluateStop 判无响应用）
let rampMsConst = 0;

function pct(arr, p) {
  if (!arr.length) return 0;
  const s = [...arr].sort((a, b) => a - b);
  return s[Math.min(s.length - 1, Math.floor((p / 100) * s.length))];
}

function record(rec) {
  metrics.push(rec);
  fs.appendFileSync(path.join(scenarioDir, `${STAGE}.jsonl`), JSON.stringify(rec) + '\n');
  if (!rec.ok && errorSamples.length < 50) errorSamples.push(rec);
}

// ===== 单请求（SSE 流式读取） =====
function chatOnce(sid, userId) {
  return new Promise((resolve) => {
    const startMs = Date.now();
    let ttftMs = null;
    let waiting = 0;
    let settled = false;

    const body = JSON.stringify({
      message: `bench load test ${MARKER} session=${sid}`,
      userId,
      sessionId: sid,
    });
    const req = http.request({
      hostname: base.hostname,
      port: base.port,
      path: '/threads/chat',
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) },
    }, (res) => {
      if (res.statusCode !== 200) {
        finish(false, `http_${res.statusCode}`);
        res.resume();
        return;
      }
      let buf = '';
      res.setEncoding('utf8');
      res.on('data', (c) => {
        // 绝对截止时间：SSE waiting 帧（15s 一发）会不断重置 socket 不活动计时器，
        // 服务端卡死时（实测沙箱异常可挂 ~350s）必须按总时长主动断开
        if (Date.now() - startMs > REQ_TIMEOUT_MS) {
          req.destroy(new Error('deadline'));
          finish(false, 'deadline');
          return;
        }
        buf += c;
        let idx;
        while ((idx = buf.indexOf('\n')) !== -1) {
          const line = buf.slice(0, idx).trim();
          buf = buf.slice(idx + 1);
          if (!line.startsWith('data:')) continue;
          const dataStr = line.slice(5).trim();
          let frame = null;
          try { frame = JSON.parse(dataStr); } catch { continue; }
          const type = frame.type || '';
          if (ttftMs === null && type !== 'waiting') ttftMs = Date.now() - startMs;
          if (type === 'waiting') waiting++;
          else if (type === 'AGENT_END') finish(true, null);
          else if (type === 'error') finish(false, String(frame.error || 'error_frame').slice(0, 200));
          else if (type === 'permission_ask') finish(false, 'permission_ask');
        }
      });
      res.on('end', () => finish(false, ttftMs === null ? 'empty_stream' : 'no_agent_end'));
      res.on('error', (e) => finish(false, 'conn_error:' + e.message));
    });
    req.on('error', (e) => finish(false, 'req_error:' + e.message));
    req.setTimeout(REQ_TIMEOUT_MS, () => {
      req.destroy(new Error('timeout'));
      finish(false, 'timeout');
    });
    req.end(body);

    function finish(ok, error) {
      if (settled) return;
      settled = true;
      resolve({
        sid, userId, startMs, ok,
        latencyMs: Date.now() - startMs,
        ttftMs, waiting,
        error: error || null,
      });
    }
  });
}

// ===== 观测采样（5s 周期） =====
function sampleObserver() {
  const obs = { ts: Date.now() };
  try {
    // 容器 CPU/内存（--no-stream 单次快照；宿主 docker CLI）
    const out = execFileSync('docker', ['stats', '--no-stream', '--format',
      '{{.CPUPerc}} {{.MemUsage}}', 'bench-agent-fw'], { timeout: 8000 }).toString().trim();
    const [cpu, mem] = out.split(/\s+/);
    obs.containerCpu = parseFloat(cpu);
    obs.containerMemMb = parseFloat(mem);
  } catch { /* 容器不在/退出 */ }
  try {
    // JVM 堆（JRE 镜像可能无 jcmd，失败即跳过）
    const heap = execFileSync('docker', ['exec', 'bench-agent-fw', 'jcmd', '1', 'GC.heap_info'],
      { timeout: 8000 }).toString();
    const m = /garbage-first heap total \S+, size (\S+)K used (\S+)K/.exec(heap)
      || /used (\S+)K/.exec(heap);
    if (m) obs.heapUsedMb = Math.round(parseInt(m[2] || m[1], 10) / 1024);
  } catch { /* ignore */ }
  try {
    const st = execFileSync('mysql', ['-h127.0.0.1', '-P3307', '-uagent_manager',
      `-p${process.env.MYSQL_PASSWORD || 'Agent@Manager2026'}`, '-N', '-e',
      "SHOW GLOBAL STATUS LIKE 'Threads_connected'; SHOW GLOBAL STATUS LIKE 'Innodb_row_lock_waits';"],
      { timeout: 8000, env: { ...process.env, MYSQL_PWD: process.env.MYSQL_PASSWORD || 'Agent@Manager2026' } })
      .toString();
    const tc = /Threads_connected\s+(\d+)/.exec(st);
    const rl = /Innodb_row_lock_waits\s+(\d+)/.exec(st);
    if (tc) obs.threadsConnected = parseInt(tc[1], 10);
    if (rl) obs.rowLockWaits = parseInt(rl[1], 10);
  } catch { /* ignore */ }
  try {
    obs.loadavg = fs.readFileSync('/proc/loadavg', 'utf8').split(/\s+/).slice(0, 3).join(' ');
  } catch { /* ignore */ }
  obs.sandboxes = null; // 由异步采样补充
  return obs;
}

function fetchSandboxCount() {
  return new Promise((resolve) => {
    const headers = process.env.OPENSANDBOX_API_KEY
      ? { 'OPEN-SANDBOX-API-KEY': process.env.OPENSANDBOX_API_KEY } : {};
    const req = http.get({ hostname: '127.0.0.1', port: 8090, path: '/v1/sandboxes', timeout: 5000, headers }, (res) => {
      let b = '';
      res.on('data', (c) => { b += c; });
      res.on('end', () => {
        try {
          const d = JSON.parse(b);
          const n = Array.isArray(d) ? d.length
            : Array.isArray(d.items) ? d.items.length
              : Array.isArray(d.data) ? d.data.length
                : (typeof d.total === 'number' ? d.total : null);
          resolve(n);
        } catch { resolve(null); }
      });
    });
    req.on('error', () => resolve(null));
    req.on('timeout', () => { req.destroy(); resolve(null); });
  });
}

// ===== 停止条件评估（每 2s） =====
function evaluateStop() {
  // 服务无响应：稳态窗口开始 60s 内没有任何请求完成（连错误都没有 = 全部挂起）
  if (!aborted && metrics.length === 0 && stageT0 > 0
      && Date.now() - stageT0 > rampMsConst + 60000) {
    aborted = true;
    abortReason = 'service unresponsive: no completion within 60s of steady window';
    return;
  }
  if (metrics.length < MIN_SAMPLES) return;
  const okCount = metrics.filter((m) => m.ok).length;
  const errRate = 1 - okCount / metrics.length;
  const lat = metrics.map((m) => m.latencyMs);
  if (errRate > ERROR_RATE_ABORT) {
    aborted = true;
    abortReason = `error_rate ${(errRate * 100).toFixed(1)}% > 5%`;
  } else if (pct(lat, 95) > P95_ABORT_MS) {
    aborted = true;
    abortReason = `p95 ${pct(lat, 95)}ms > ${P95_ABORT_MS}ms`;
  }
}

function healthCheck() {
  return new Promise((resolve) => {
    const req = http.get({ hostname: base.hostname, port: base.port, path: '/health', timeout: 4000 }, (res) => {
      res.resume();
      resolve(res.statusCode === 200);
    });
    req.on('error', () => resolve(false));
    req.on('timeout', () => { req.destroy(); resolve(false); });
  });
}

// ===== warmup：每会话串行 1 次（create，稳态不再出现 create） =====
// 实测：沙箱 create 后 execd 代理有短暂 502 启动窗口，连续 create 易触发
// resume 异常 → 每会话之间留 settle 间隔，失败退避重试
const WARMUP_SETTLE_MS = parseInt(arg('warmup-settle-ms', '2000'), 10);
const WARMUP_RETRIES = 3;

async function warmup() {
  console.log(`[warmup] sessions=${SESSION_POOL} scenario=${SCENARIO}`);
  const file = path.join(scenarioDir, 'warmup.jsonl');
  for (let u = 0; u < SESSION_POOL; u++) {
    const sid = sessionAt(u);
    const userId = USER_ID_PREFIX + u;
    const t0 = Date.now();
    let rec = null;
    for (let attempt = 1; attempt <= WARMUP_RETRIES; attempt++) {
      rec = await chatOnce(sid, userId);
      if (rec.ok) break;
      console.log(`  [warmup] ${sid} 第 ${attempt} 次失败（${rec.error}），退避重试`);
      await new Promise((r) => setTimeout(r, 5000));
    }
    const line = { sid, userId, ok: rec.ok, latencyMs: rec.latencyMs, error: rec.error };
    fs.appendFileSync(file, JSON.stringify(line) + '\n');
    console.log(`  [warmup] ${sid}: ${rec.ok ? 'ok' : 'FAIL ' + rec.error} (${Date.now() - t0}ms)`);
    if (!rec.ok) {
      console.error(`[warmup] 重试耗尽仍失败，中止（先排查服务/依赖再跑压测）`);
      process.exit(1);
    }
    await new Promise((r) => setTimeout(r, WARMUP_SETTLE_MS));
  }
  console.log('[warmup] 全部就绪');
}

/** 池内第 i 个会话：场景级固定命名（预热 create，各档复用走 resume） */
function sessionAt(i) {
  return `bench-${SCENARIO}-sess-${i}`;
}
const USER_ID_PREFIX = 'bench-user-';

// ===== run：闭环阶梯档 =====
async function run() {
  const t0 = Date.now();
  const rampMs = RAMP_SECONDS * 1000;
  stageT0 = t0;
  rampMsConst = rampMs;
  const steadyMs = STAGE_SECONDS * 1000;
  const windowEnd = t0 + rampMs + steadyMs;
  const drainDeadline = windowEnd + 30000;

  console.log(`[run] ${SCENARIO} C=${CONCURRENCY} S=${SESSION_POOL} ramp=${RAMP_SECONDS}s steady=${STAGE_SECONDS}s`);

  // 周期任务：停止条件评估 + 健康检查 + 观测采样
  const evalTimer = setInterval(evaluateStop, 2000);
  const healthTimer = setInterval(async () => {
    const ok = await healthCheck();
    healthFails = ok ? 0 : healthFails + 1;
    if (!ok && healthFails >= 3 && !aborted) {
      aborted = true;
      abortReason = 'service health check failed x3';
    }
  }, 5000);
  const obsFile = path.join(scenarioDir, `${STAGE}.observers.jsonl`);
  const obsTimer = setInterval(async () => {
    const obs = sampleObserver();
    obs.sandboxes = await fetchSandboxCount();
    fs.appendFileSync(obsFile, JSON.stringify(obs) + '\n');
  }, 5000);

  // 闭环 worker：线性 ramp 拉起；稳态窗口外不再发起新请求。
  // worker w 固定绑定会话 w % SESSION_POOL：C ≤ S 时每个 inflight 独占会话（无租约排队）；
  // C > S 时会话被多 worker 共享 → turn 租约排队（池受限区间的真实语义）
  async function worker(wIdx) {
    const startDelay = CONCURRENCY > 1 ? rampMs * (wIdx / CONCURRENCY) : 0;
    const readyAt = t0 + startDelay;
    if (Date.now() < readyAt) await new Promise((r) => setTimeout(r, readyAt - Date.now()));
    const sid = sessionAt(wIdx % SESSION_POOL);
    const userId = USER_ID_PREFIX + (wIdx % SESSION_POOL);
    while (!aborted && Date.now() < windowEnd) {
      const rec = await chatOnce(sid, userId);
      if (rec.startMs >= t0 + rampMs) record(rec); // 仅稳态发起的请求计入
      if (MIN_TURN_GAP_MS > 0) {
        const endMs = rec.startMs + rec.latencyMs;
        const remain = MIN_TURN_GAP_MS - (Date.now() - endMs);
        if (remain > 0) await new Promise((r) => setTimeout(r, remain));
      }
    }
  }

  const workers = [];
  for (let i = 0; i < CONCURRENCY; i++) workers.push(worker(i));
  await Promise.race([
    Promise.all(workers),
    new Promise((r) => setTimeout(r, drainDeadline - Date.now())),
  ]);
  clearInterval(evalTimer);
  clearInterval(healthTimer);
  clearInterval(obsTimer);

  // ===== 档汇总 =====
  const okRecs = metrics.filter((m) => m.ok);
  const lat = okRecs.map((m) => m.latencyMs);
  const ttfts = metrics.map((m) => m.ttftMs).filter((v) => v !== null);
  const waitings = metrics.map((m) => m.waiting);
  const steadyMinutes = STAGE_SECONDS / 60;
  const summary = {
    scenario: SCENARIO,
    stage: STAGE,
    concurrency: CONCURRENCY,
    sessionPool: SESSION_POOL,
    windowSeconds: STAGE_SECONDS,
    ok: okRecs.length,
    error: metrics.length - okRecs.length,
    errorRate: metrics.length ? +(1 - okRecs.length / metrics.length).toFixed(4) : null,
    reqPerMin: +(okRecs.length / steadyMinutes).toFixed(1),
    rps: +(okRecs.length / STAGE_SECONDS).toFixed(2),
    p50Ms: pct(lat, 50),
    p95Ms: pct(lat, 95),
    p99Ms: pct(lat, 99),
    maxMs: lat.length ? Math.max(...lat) : 0,
    ttftP50Ms: pct(ttfts, 50),
    ttftP95Ms: pct(ttfts, 95),
    waitingAvg: waitings.length ? +(waitings.reduce((a, b) => a + b, 0) / waitings.length).toFixed(2) : 0,
    llmCallsExpected: okRecs.length * EXPECTED_LLM_CALLS,
    aborted,
    abortReason,
    finishedAt: Date.now(),
  };
  fs.writeFileSync(path.join(scenarioDir, `${STAGE}.summary.json`), JSON.stringify(summary, null, 2));
  console.log(`[done] ${SCENARIO} C=${CONCURRENCY}: ok=${summary.ok} err=${summary.error} ` +
    `req/min=${summary.reqPerMin} p50=${summary.p50Ms} p95=${summary.p95Ms}` +
    (aborted ? ` [中止: ${abortReason}]` : ''));
  // 非正常中止以非零码退出，编排脚本据此跳过该场景剩余档
  process.exit(aborted ? 2 : 0);
}

(async () => {
  if (MODE === 'warmup') await warmup();
  else await run();
})().catch((e) => {
  console.error('[runner] fatal:', e);
  process.exit(1);
});
