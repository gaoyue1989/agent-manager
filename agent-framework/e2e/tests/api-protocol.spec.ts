/**
 * e2e-protocol 组（T 组先行，travel-fulfillment 设计 §11 M1 / PR #62 遗留 P1-1 切片）：
 * 双实例——a=存量形态（协议关），p=协议实例（AGENT_PROTOCOL_ENABLED=true +
 * AGENT_PROTOCOL_AUTH_TOKEN=e2e-protocol-token，见 env-up.sh protocol 分支）。
 *
 * 本切片覆盖确定性 HTTP 契约：T7 /tasks 强制 token（无/错 token 一律 401，验收断言 11）、
 * T8 存量零影响（未启用协议的服务 /tasks 404、基础链路不变，验收断言 8）、
 * 卡片透出 agent_protocol（P1-4）。
 * spawn/确认/拒绝/超时/父崩溃恢复五场景需 mock-LLM 双进程脚本化编排，属 T 组二期
 * （demo/order-fulfillment/e2e 已在真实环境覆盖大半）。
 */
import { test, expect } from '@playwright/test';
import { BASE, PROTOCOL_BASE, runId as RUN_ID } from '../lib/env.js';

const TOKEN = 'e2e-protocol-token';
// 协议实例寻址：env-up 产物 env.json 的 protocolBase（R2/R4 教训——空串地址要显式暴露，
// 不静默错路由）
const P = PROTOCOL_BASE || '';
if (!P) {
  test.describe.configure({ mode: 'serial' });
  test.skip(true, 'PROTOCOL_BASE 未配置（protocol 组 env-up 未运行？）');
}

test.describe('T8 存量零影响（协议关实例 a）', () => {
  test('未启用协议的存量服务无 /tasks 端点，基础链路不受影响', async ({ request }) => {
    // SDK 端点按 agentscope.agent-protocol.enabled 注册：关闭即不装配 → 未映射路径。
    // 存量行为：GlobalExceptionHandler 把 NoResourceFoundException 按未处理异常落 500
    //（非协议端点、无任何远程调度面），断言"非 401/200 的端点不存在语义"。
    const tasks = await request.get(`${BASE}/tasks`);
    expect([404, 500]).toContain(tasks.status());
    const task = await request.get(`${BASE}/tasks/t-1`);
    expect([404, 500]).toContain(task.status());
    // 基础链路零影响：健康/会话列表/卡片照常
    expect((await request.get(`${BASE}/health`)).status()).toBe(200);
    expect((await request.get(`${BASE}/threads`)).status()).toBe(200);
    const card = await (await request.get(`${BASE}/.well-known/agent-card.json`)).json();
    expect(card.agent_protocol?.enabled).toBe(false);
  });
});

test.describe('T7 /tasks 强制 token（协议实例 p）', () => {
  test('无 token 一律 401（GET/POST /tasks 与 /tasks/{id} 全路径）', async ({ request }) => {
    expect((await request.get(`${P}/tasks`)).status()).toBe(401);
    expect((await request.get(`${P}/tasks/t-1`)).status()).toBe(401);
    const post = await request.post(`${P}/tasks`, { data: { input: 'hi' } });
    expect(post.status()).toBe(401);
    const events = await request.get(`${P}/tasks/t-1/events`);
    expect(events.status()).toBe(401);
  });

  test('错 token 一律 401', async ({ request }) => {
    const wrong = { 'X-Agent-Protocol-Token': 'wrong-token' };
    expect((await request.get(`${P}/tasks`, { headers: wrong })).status()).toBe(401);
    expect((await request.get(`${P}/tasks/t-1`, { headers: wrong })).status()).toBe(401);
    const post = await request.post(`${P}/tasks`, {
      headers: wrong, data: { input: 'hi' },
    });
    expect(post.status()).toBe(401);
  });

  test('正确 token 通过认证：未知任务按 SDK 契约返回 error 体（非 401）', async ({ request }) => {
    const headers = { 'X-Agent-Protocol-Token': TOKEN };
    const res = await request.get(`${P}/tasks/t-never-submitted`, { headers });
    expect(res.status(), 'auth passed then task lookup misses').toBe(200);
    const body = await res.json();
    expect(body.error).toContain('task not found');
  });

  test('正确 token + 非法提交体 → 认证通过进入参数处理（非 401/404）', async ({ request }) => {
    const res = await request.post(`${P}/tasks`, {
      headers: { 'X-Agent-Protocol-Token': TOKEN },
      data: { no_input_field: true },
    });
    // 认证已过（非 401）、任务未创建（非 404/202）；具体校验码随 SDK 提交校验实现
    expect([400, 422, 500]).toContain(res.status());
  });

  test('卡片透出 agent_protocol.enabled=true', async ({ request }) => {
    const card = await (await request.get(`${P}/.well-known/agent-card.json`)).json();
    expect(card.agent_protocol?.enabled).toBe(true);
    // streaming/hitl 读 SDK 扩展属性（启用时存在）
    expect(card.agent_protocol?.streaming).toBe(true);
    expect(card.agent_protocol?.hitl).toBe(true);
  });
});

// ---------- J 组：A2A 幂等 Job HTTP 面（Issue #69 §2.1/§2.3，member 侧单实例） ----------
// p 实例同开 AGENT_A2A_JOB_ENABLED=true（env-up protocol 组注入）；token 与 /tasks 分域。
// 双副本同键收敛（P7）在 e2e-protocol-multi 门禁 job（阶段 3）。
test.describe('J0 组 A2A Job 存量零影响（协议关实例 a）', () => {
  test('未启用 A2A Job 的存量服务 /a2a/jobs 不可达（P0-1 守卫：条件装配关端点）', async ({ request }) => {
    // 未启用时 POST/GET 落到根映射（SDK JSON-RPC handler）返回 4xx/5xx 错误体——
    // 守卫目标：不存在活的 Job 端点（无认证/无幂等的触发面），断言非 2xx 且无 job 载荷
    const post = await request.post(`${BASE}/a2a/jobs`, { data: { text: 'x' } });
    expect(post.status()).toBeGreaterThanOrEqual(400);
    expect((await post.json()).job).toBeUndefined();
    const get = await request.get(`${BASE}/a2a/jobs/any-key`);
    expect(get.status()).toBeGreaterThanOrEqual(400);
    expect((await get.json()).job).toBeUndefined();
  });
});

test.describe('J 组 /a2a/jobs 强制 token 与幂等语义（协议实例 p）', () => {
  const JOB_TOKEN = 'e2e-a2ajob-token';

  test('无/错 token 一律 401（POST/GET 全路径）', async ({ request }) => {
    expect((await request.post(`${P}/a2a/jobs`, { data: { text: 'x' } })).status()).toBe(401);
    expect((await request.post(`${P}/a2a/jobs`, {
      headers: { 'Agent-A2A-Job-Token': 'wrong' }, data: { text: 'x' } })).status()).toBe(401);
    expect((await request.get(`${P}/a2a/jobs/some-key`)).status()).toBe(401);
  });

  test('正确 token + 非法键/空 text → 400', async ({ request }) => {
    const h = { 'Agent-A2A-Job-Token': JOB_TOKEN };
    expect((await request.post(`${P}/a2a/jobs`, {
      headers: { ...h, 'Idempotency-Key': 'bad key!' }, data: { text: 'x' } })).status()).toBe(400);
    expect((await request.post(`${P}/a2a/jobs`, {
      headers: h, data: { text: '' } })).status()).toBe(400);
  });

  test('幂等全链路：同键两次提交 → 同一 taskId、idempotent=true、仅建一个任务', async ({ request }) => {
    const h = { 'Agent-A2A-Job-Token': JOB_TOKEN, 'Idempotency-Key': `e2e-job-${RUN_ID}` };
    const first = await request.post(`${P}/a2a/jobs`, { headers: h, data: { text: '报告你的名字' } });
    expect(first.status()).toBe(200);
    const firstBody = await first.json();
    expect(firstBody.job.taskId).toBeTruthy();
    expect(firstBody.idempotent).toBe(false);

    // 同键重放：mock-LLM 即时返回 → 任务已完成（done 态）→ 幂等命中
    const second = await request.post(`${P}/a2a/jobs`, { headers: h, data: { text: '报告你的名字' } });
    expect(second.status()).toBe(200);
    const secondBody = await second.json();
    expect(secondBody.job.taskId).toBe(firstBody.job.taskId);
    expect(secondBody.idempotent).toBe(true);

    // GET 收敛
    const st = await request.get(`${P}/a2a/jobs/e2e-job-${RUN_ID}`, { headers: h });
    expect(st.status()).toBe(200);
    expect((await st.json()).job.state).toBe('done');
  });

  test('GET 未知键 → 404；卡片/metadata 透出 a2a_job.enabled=true', async ({ request }) => {
    const h = { 'Agent-A2A-Job-Token': JOB_TOKEN };
    expect((await request.get(`${P}/a2a/jobs/no-such-key-${RUN_ID}`, { headers: h })).status()).toBe(404);
    const meta = await (await request.get(`${P}/metadata`)).json();
    expect(meta.a2a_job?.enabled).toBe(true);
    expect(meta.a2a_job?.maxConcurrent).toBeGreaterThan(0);
  });
});
