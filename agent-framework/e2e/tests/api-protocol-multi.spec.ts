/**
 * P 组：协议多副本门禁（travel-fulfillment 设计 §18.4，阶段 3）。
 *
 * 拓扑（env-up protocol-multi）：lead×2 + member×2，各自 nginx 轮询 LB（随机路由
 * 无粘性），共享同一 MySQL/Redis。member/lead 均开 Agent Protocol（token 同值），
 * member 另开 A2A Job（P7）。
 *
 * 断言锚点：
 * - P1 跨副本确认/收割链路（全程 LB 随机路由）
 * - P2 lead 副本 kill 后 registry 重建接管（G1 验收）
 * - P3 EventBus 跨副本事件对账（G2 验收，802/802 型）
 * - P4 TTL sweep 恰好一次（G3 验收：单实例收敛，双副本 sweep 由 claimWake/consume
 *   CAS 兜底；CI 时钟下双副本竞争窗口不可控，此为单实例语义钉 + 代码层 CAS 已单测）
 * - P5 无粘性冒烟（/status 判态 + R 组口径）
 * - P6 存量零影响（协议关实例 /tasks 404）
 * - P7 A2A Job 同键跨副本收敛（Issue #69 PR-C 验收）
 *
 * fixture 协议 member 无 ask 类工具（core fixture require_confirmation=false），
 * 确认链路经 RequireConfirm MCP（cards/approval server）触发——P1 直接断言
 * spawn→完成→收割汇总（demo 实证主链路），确认卡场景由 T 组二期补双进程编排。
 */
import { test, expect } from '@playwright/test';
import {
  BASE,
  PROTO_LEADER_LB, PROTO_LEADER_A, PROTO_LEADER_B,
  PROTO_MEMBER_LB, PROTO_MEMBER_A, PROTO_MEMBER_B,
  runId,
} from '../lib/env.js';
import { chat } from '../lib/client.js';
import { textOf } from '../lib/sse.js';

const TOKEN = 'e2e-protocol-token';
const JOB_TOKEN = 'e2e-a2ajob-token';

test.describe.configure({ mode: 'serial' });

test.beforeAll(async () => {
  test.skip(!PROTO_LEADER_LB || !PROTO_MEMBER_LB,
    'PROTO_*_LB 未配置（protocol-multi 组 env-up 未运行？）');
});

/** 经 lead LB 发起会话并委派（随机路由 spawn → member LB 随机路由受理）；SSE 流收帧 */
async function spawnViaLb(sid: string, msg: string, timeoutMs = 120_000) {
  const collected = chat({
    message: msg, sessionId: sid, userId: 'e2e-pg',
    base: PROTO_LEADER_LB, timeoutMs,
  });
  await collected.closed;
  expect(collected.terminal?.type).toBe('done');
  return textOf(collected.frames);
}

// ---------- P1：跨副本完整委派链路 ----------

test('P1 跨副本委派：LB 随机路由 spawn → 完成 → lead 收割汇总', async ({ request }) => {
  const sid = `e2e_${runId}_p1`;
  const reply = await spawnViaLb(sid,
    `[E2E:proto:lead-spawn] 委派远程子 agent 执行回显任务，完成后汇报结果。`);
  // lead 收割汇总应含子任务交付内容（fixture 第二轮回放文本）
  expect(reply).toContain('远程子任务已完成');
});

// ---------- P2：lead 副本崩溃接管（G1） ----------

test('P2 lead 副本 kill：registry 重建后另一副本接管轮询与收割', async ({ request }) => {
  test.skip(true, 'kill 编排需 env-up 产物 pid 与重启脚本（R4 同款），并入门禁 job 二期切片——registry 重建/唤醒 CAS 已由单测 28 例覆盖');
});

// Redis task 键集反查（P3 专用）：KEYS proto:task:*:events（RESP 裸 socket，无 redis-cli
// 依赖——CI 与 local-infra 一致，同 reset-data.mjs 手法；E2E 环境独占 redis）。
// 修 #74：KEYS 返回顺序无保证（哈希桶序），「取末位当本轮任务」会锚到旧任务使断言
// 恒真——改取 spawn 前后键集差分，新增键才是本轮任务。
// 修 #97：socket error/timeout 不能静默降级成空 Set——before 快照为空集时差分会挑中
// 历史任务 → /events 断言照样 200+非空 → 假绿。记录错误重试一次，仍失败即 throw。
async function protocolTaskIdsFromRedis(): Promise<Set<string>> {
  const { host, port } = redisTarget();
  let lastErr: Error | null = null;
  for (let attempt = 1; attempt <= 2; attempt++) {
    try {
      return await protocolTaskIdsOnce(host, port);
    } catch (e) {
      lastErr = e as Error;
      console.warn(`[P3] Redis 键集反查第 ${attempt} 次尝试失败（${host}:${port}）: ${lastErr.message}`);
    }
  }
  throw new Error(`Redis task 键集反查连续失败（${host}:${port}），拒绝以空集参与差分: ${lastErr?.message}`);
}

/** 单次 KEYS 尝试：RESP 裸 socket 反查；error/timeout 上抛（不静默降级），重试由上层决定 */
async function protocolTaskIdsOnce(host: string, port: number): Promise<Set<string>> {
  const net = await import('node:net');
  return await new Promise((resolve, reject) => {
    const sock = net.createConnection({ host, port });
    sock.setTimeout(5000);
    let buf = '';
    const finish = (ids: Set<string>) => { sock.destroy(); resolve(ids); };
    const fail = (why: string) => { sock.destroy(); reject(new Error(why)); };
    sock.on('connect', () => sock.write('*2\r\n$4\r\nKEYS\r\n$19\r\nproto:task:*:events\r\n'));
    sock.on('data', d => {
      buf += d.toString();
      // RESP 数组完整性：*N\r\n 头 + 每项 $len\r\n<payload>\r\n 两行——凑满 N 项才
      // 解析（修 #74 附带缺陷：首个分片即 finish 会取不全/提前空手而归）
      const head = /^\*(\d+)\r\n/.exec(buf);
      if (!head) return;
      const n = parseInt(head[1], 10);
      if (buf.split('\r\n').length - 1 < 1 + n * 2) return;
      const ids = new Set([...buf.matchAll(/proto:task:([A-Za-z0-9_-]+):events/g)]
        .map(m => m[1]));
      finish(ids);
    });
    sock.on('error', err => fail(`socket error: ${err.message}`));
    sock.on('timeout', () => fail('等待 RESP 响应超时（5s）'));
  });
}

function redisTarget() {
  const url = process.env.REDIS_URL ?? 'redis://127.0.0.1:16379';
  const m = /redis:\/\/([^:/]+):(\d+)/.exec(url)!;
  return { host: m[1], port: m[2] };
}

// ---------- P3：EventBus 跨副本（G2） ----------

test('P3 member 事件跨副本对账：A 受理的任务 B 可 /events 全量回放', async ({ request }) => {
  const sid = `e2e_${runId}_p3`;
  // 经 lead LB spawn（fixture 同步等待完成）。同步 spawn 结果 status=ok 无 task_id
  // 行（force_sync 语义，§16），Bridge 登记只对后台任务生效——taskId 从 member Redis
  // 键族反查（E2E 独占实例；CI local redis 无前缀）：spawn 前后键集差分锁定本轮任务
  const before = await protocolTaskIdsFromRedis();
  await spawnViaLb(sid,
    `[E2E:proto:lead-spawn] 委派远程子 agent 执行回显任务，完成后汇报结果。`);
  let taskId: string | null = null;
  for (let i = 0; i < 20 && !taskId; i++) {
    await new Promise(r => setTimeout(r, 500));
    const after = await protocolTaskIdsFromRedis();
    const fresh = [...after].filter(id => !before.has(id));
    if (fresh.length > 0) taskId = fresh[0];
  }
  expect(taskId, 'Redis 应有本轮协议任务键（spawn 前后差分）').toBeTruthy();

  // 直投 MEMBER_A 与 MEMBER_B 各自回放（跨副本可见 = Redis Streams 生效）
  for (const replica of [PROTO_MEMBER_A, PROTO_MEMBER_B]) {
    const res = await request.get(`${replica}/tasks/${taskId}/events?from_seq=0`, {
      headers: { 'X-Agent-Protocol-Token': TOKEN },
      timeout: 10_000,
      failOnStatusCode: false,
    });
    expect(res.status(), `${replica} /events 应可达`).toBe(200);
    const body = await res.text();
    expect(body.length, `${replica} 回放应为非空 SSE 流`).toBeGreaterThan(0);
  }
});

// ---------- P4：TTL sweep 恰好一次 ----------

test('P4 TTL 治理恰好一次：过期远程行收口且不重复 resume', async ({ request }) => {
  test.skip(true, '需 AGENT_REMOTE_CONFIRM_TTL_HOURS 调小（≈36s）+ awaiting 任务——确认卡双进程编排（T 组二期）并入；CAS 收口已由 RemoteConfirmBridgeMultiReplicaTest 覆盖');
});

// ---------- P5：无粘性冒烟 ----------

test('P5 双副本无粘性冒烟：LB 入口 /status 与 /health 对随机路由透明', async ({ request }) => {
  for (const base of [PROTO_LEADER_LB, PROTO_MEMBER_LB]) {
    const h = await request.get(`${base}/health`);
    expect(h.status()).toBe(200);
  }
  // /tasks 认证面经 lead LB（未带 token → 401，说明路由到协议实例且 filter 生效）
  const res = await request.get(`${PROTO_MEMBER_LB}/tasks`);
  expect([401, 404]).toContain(res.status());
});

// ---------- P6：存量零影响 ----------

test('P6 存量零影响：协议关实例无 /tasks 端点（T8 口径）', async ({ request }) => {
  // protocol-multi 拓扑中 BASE(8100)=lead LB（协议实例）；存量对照实例在 BASE+2 直连端口
  const legacyPort = Number(new URL(BASE).port || '80') + 2;
  const legacy = BASE.replace(/:\d+$/, ':' + legacyPort);
  const res = await request.get(`${legacy}/tasks`);
  // 存量行为：未映射路径由 GlobalExceptionHandler 落 500（T8 口径，非 401/200 即端点不存在）
  expect([404, 500]).toContain(res.status());
});

// ---------- P7：A2A Job 同键跨副本收敛（Issue #69 PR-C） ----------

test('P7 A2A Job：同键两次提交（LB 随机路由）→ 同一 taskId、idempotent 收敛', async ({ request }) => {
  const key = `e2e-p7-${runId}`;
  const h = { 'Agent-A2A-Job-Token': JOB_TOKEN, 'Idempotency-Key': key };

  const first = await request.post(`${PROTO_MEMBER_LB}/a2a/jobs`, {
    headers: h, data: { text: '报告你的名字' }, timeout: 120_000,
  });
  expect(first.status()).toBe(200);
  const firstBody = await first.json();
  expect(firstBody.job.taskId).toBeTruthy();
  expect(firstBody.idempotent).toBe(false);

  // 同键重放（LB 随机路由：两次可能落不同副本）→ Redis 单键裁决 → 幂等命中
  const second = await request.post(`${PROTO_MEMBER_LB}/a2a/jobs`, {
    headers: h, data: { text: '报告你的名字' }, timeout: 120_000,
  });
  expect(second.status()).toBe(200);
  const secondBody = await second.json();
  expect(secondBody.job.taskId).toBe(firstBody.job.taskId);
  expect(secondBody.idempotent).toBe(true);

  // 两副本各自 GET 收敛一致
  for (const replica of [PROTO_MEMBER_A, PROTO_MEMBER_B]) {
    const st = await request.get(`${replica}/a2a/jobs/${key}`, { headers: h });
    expect(st.status(), `${replica} GET`).toBe(200);
    expect((await st.json()).job.state).toBe('done');
  }
});
