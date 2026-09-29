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
import { BASE, PROTOCOL_BASE } from '../lib/env.js';

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
