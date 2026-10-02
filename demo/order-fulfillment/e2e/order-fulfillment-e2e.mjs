#!/usr/bin/env node
/**
 * order-fulfillment demo E2E（多 Agent 远程协同 · 一期形态）
 *
 * 断言分层（对齐设计 §0 残差声明「编排质量依赖模型」——框架保证断言确定性，
 * 模型编排话术仅做宽松观察）：
 *   T1 远程诊断     —— lead 委派 order-agent（Agent Protocol 远程 spawn，真实 LLM），
 *                      诊断零写（断言 1/9）+ 子任务真实落 mock（get_order+1）
 *   T1b member 直驱 —— inventory-agent 自身 chat 端点执行 get_inventory（member 工具路径）
 *   T2 处置方案     —— lead 输出含 expected_version/plan_id 的方案并等待批准
 *   T3 批准执行     —— 全参数批准指令 → order-agent 经远程 spawn 创建处理单（恰 +1）
 *   T4 执行交付     —— 汇总含真实 RES- 编号
 *   T5 L3 版本拒绝  —— 错误 expected_version → VERSION_CONFLICT（断言 4）
 *   T6 L3 幂等拒绝  —— plan_id 重放 → IDEMPOTENT_REJECT（断言 5）
 *   T7 /tasks 鉴权  —— member 端点无 token 一律 401（断言 11，经 Ingress）
 *
 * 运行：node e2e/order-fulfillment-e2e.mjs（依赖：node>=18 全局 fetch + kubectl）
 */
import { randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';

const BASE = process.env.OF_BASE ?? 'http://127.0.0.1:8911';
const LEAD = `${BASE}/agent/fulfillment-lead`;
const USER = 'of-e2e-user';

let passed = 0, failed = 0;
const ok = (name, cond, detail = '') => {
  if (cond) { passed++; console.log(`  ✅ ${name}`); }
  else { failed++; console.log(`  ❌ ${name}${detail ? ' —— ' + detail : ''}`); }
};

async function chat(agentPath, sessionId, message, dump) {
  const res = await fetch(`${BASE}/agent/${agentPath}/threads/chat`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message, sessionId, userId: USER }),
    signal: AbortSignal.timeout(900_000),
  });
  if (!res.ok) throw new Error(`chat HTTP ${res.status}`);
  const sse = await res.text();
  if (dump) { try { (await import('node:fs')).writeFileSync(dump, sse); } catch {} }
  const tools = [...sse.matchAll(/"tool_call_summary"[^\n]*?"toolName":"([a-z_]+)"/g)].map(m => m[1]);
  // 事件级工具调用（TOOL_CALL_START 行）：与 history/session 工具的文本内容区分开
  const calledTools = new Set();
  let text = '';
  for (const line of sse.split('\n')) {
    if (!line.startsWith('data:')) continue;
    let d;
    try { d = JSON.parse(line.slice(5).trim()); } catch { continue; }
    if (d?.type === 'TEXT_BLOCK_DELTA' && d.delta) text += d.delta;
    if (d?.type === 'TOOL_CALL_START') {
      const m = (d.name ? String(d.name) : '') || (line.match(/"name"\s*:\s*"([a-z_]+)"/) || [])[1];
      if (m) calledTools.add(m);
    }
  }
  return { sse, tools, calledTools, text };
}

const MCP_PROG = "import sys,json,urllib.request\n" +
  "req=urllib.request.Request('http://127.0.0.1:8300/mcp',data=sys.argv[1].encode(),headers={'Content-Type':'application/json'})\n" +
  "print(urllib.request.urlopen(req).read().decode())";
const mcpCall = (args) => execFileSync('kubectl',
  ['-n', 'agent-platform', 'exec', '-i', 'deploy/biz-mcp', '--', 'python3', '-c', MCP_PROG,
   JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/call', params: args })],
  { encoding: 'utf8', timeout: 30_000 });
const mcpText = (raw) => { try { return JSON.parse(JSON.parse(raw).result.content[0].text); } catch { return raw; } };
const stats = () => JSON.parse(execFileSync('kubectl',
  ['-n', 'agent-platform', 'exec', 'deploy/biz-mcp', '--', 'python3', '-c',
   "import urllib.request;print(urllib.request.urlopen('http://127.0.0.1:8300/stats').read().decode())"],
  { encoding: 'utf8' }));
const pollStats = async (pred, timeoutMs) => {
  const deadline = Date.now() + timeoutMs;
  let last = stats();
  while (Date.now() < deadline) {
    last = stats();
    if (pred(last)) return last;
    await new Promise(r => setTimeout(r, 5000));
  }
  return last;
};

console.log('== T0 前置健康 ==');
for (const svc of ['fulfillment-lead', 'order-agent', 'inventory-agent']) {
  // member 刚重启时 agent-card 注册有滞后（502）：60s 内轮询至 200
  let code = 0;
  for (let i = 0; i < 12; i++) {
    code = await fetch(`${BASE}/agent/${svc}/health`).then(r => r.status).catch(() => 0);
    if (code === 200) break;
    await new Promise(r => setTimeout(r, 5000));
  }
  ok(`${svc} /health=200`, code === 200, `got ${code}`);
}
const before = stats().stats;
// 主流程选单收窄到 O-1001/O-1002（default 政策 allowed=True）——O-1003 的政策防线
// 要求 lead 不出方案，与主流程 T2/T3/T4 的「产出方案并落单」断言语义冲突（修 #68）；
// O-1003 的确定性反断言见 T8
const pick = ['O-1001', 'O-1002'][Math.floor(Math.random() * 2)];
const order = mcpText(mcpCall({ name: 'get_order', arguments: { order_id: pick } }));
const sku = typeof order === 'object' ? order.sku : null;
const ver = typeof order === 'object' ? order.version : null;
const flowPlan = 'PLAN-E2E-' + randomUUID().replaceAll('-', '').slice(0, 10).toUpperCase();
console.log(`  本轮订单：${pick}（sku=${sku}, version=${ver}），plan_id=${flowPlan}`);
ok('mock 订单事实可查', !!sku && !!ver, JSON.stringify(order).slice(0, 120));

console.log('== T1 远程诊断（lead 委派 order-agent，Agent Protocol 远程 spawn）==');
const sessionId = randomUUID();
const turn1 = await chat('fulfillment-lead', sessionId,
  `订单 ${pick} 迟迟未发货，请处理。先用 order-agent 查询该订单的事实（status/version/来源）。`, '/tmp/of-e2e-turn1.sse');
ok('委派子 agent（agent_spawn）', turn1.tools.includes('agent_spawn'), turn1.tools.join(','));
ok('诊断阶段零写（无 create_resolution）', !turn1.calledTools.has('create_resolution'));
const diag = await pollStats(s => s.stats.get_order >= before.get_order + 1, 300_000);
ok('子任务真实执行（get_order 落 mock，远程 member 实际调用）', diag.stats.get_order >= before.get_order + 1,
   JSON.stringify(diag.stats));
await new Promise(r => setTimeout(r, 6000));

console.log('== T1b member 工具路径（inventory-agent 直驱）==');
const invSess = randomUUID();
const inv = await chat('inventory-agent', invSess, `请查询 sku=${sku} 的仓级可用量与调拨约束。`, '/tmp/of-e2e-inv.sse');
ok('inventory member 调用 get_inventory', inv.tools.includes('get_inventory') || /get_inventory/.test(inv.sse),
   inv.tools.join(','));
const invStats = stats().stats;
ok('get_inventory 落 mock', invStats.get_inventory >= before.get_inventory + 1,
   `${before.get_inventory} -> ${invStats.get_inventory}`);
ok('库存事实回流（仓级/调拨）', /(east|south|调拨|仓库|可用)/.test(inv.sse));

console.log('== T2 处置方案（lead 汇总并给出方案，等待批准）==');
const turn2 = await chat('fulfillment-lead', sessionId,
  `请补全诊断并给出处置方案：reissue 重发该订单。诊断必须包含——委派 logistics-agent 查询该订单的运单事实与时效依据，` +
  `委派 after-sales-agent 查询执行 reissue 的售后政策（是否允许/是否需审批/政策版本）。` +
  `方案 JSON 中 plan_id 必须使用 ${flowPlan}，expected_version 使用诊断得到的当前版本，policy_version 使用售后专员返回的政策版本。` +
  `给出方案后停下等待我批准，未批准前不得执行。`, '/tmp/of-e2e-turn2.sse');
const turn2Text = turn2.text || '';
ok('产出处置方案（expected_version/plan_id）', /PLAN-/.test(turn2Text) && /expected_version/.test(turn2Text));
ok('方案引用本轮订单', new RegExp(pick).test(turn2.text || turn2.sse) || new RegExp(sku).test(turn2.text || turn2.sse));
// 观察项：方案话术后应等待批准；框架侧已由「T3 需显式批准轮才发起写」保证闸门语义
ok('等待用户批准（未自行执行）', /(等待|需要|请).{0,12}(批准|确认)|批准后|未经批准|未批准/.test(turn2.text || turn2.sse));
// 官方案例对齐扩展：诊断必须覆盖物流与售后政策（对应官方 get_logistics/get_policy 契约）
const ext = await pollStats(s => s.stats.get_logistics >= before.get_logistics + 1 &&
                                s.stats.get_policy >= before.get_policy + 1, 300_000);
ok('物流事实委派（get_logistics 落 mock）', ext.stats.get_logistics >= before.get_logistics + 1,
   JSON.stringify(ext.stats));
ok('政策核对委派（get_policy 落 mock）', ext.stats.get_policy >= before.get_policy + 1,
   JSON.stringify(ext.stats));
ok('方案引用政策版本（POL-v 形态）', /POL-v\d/.test(turn2.text || turn2.sse) || /POL-v/.test(turn2.text || turn2.sse),
   (turn2.text || '').slice(0, 160));

console.log('== T3 批准后执行（全参数批准指令 → 远程写委派）==');
const s3 = stats().stats;
const turn3 = await chat('fulfillment-lead', sessionId,
  `我批准该处置方案，请立即委派 after-sales-agent 创建处理单。任务参数逐字传递：order_id=${pick}，` +
  `action=reissue，expected_version=${ver}，plan_id=${flowPlan}，reason=客户催发重发。`, '/tmp/of-e2e-turn3.sse');
ok('执行委派（agent_spawn）', turn3.tools.includes('agent_spawn'));
const s4 = await pollStats(s => s.stats.create_resolution_ok > s3.create_resolution_ok, 300_000);
ok('子任务真实落单（create_resolution 恰 +1）', s4.stats.create_resolution_ok - s3.create_resolution_ok === 1,
   `${s3.create_resolution_ok} -> ${s4.stats.create_resolution_ok}`);
const reso = (s4.resolutions ?? []).filter(r => r.plan_id === flowPlan).slice(-1)[0];
ok('处理单锚定批准方案（plan_id 匹配 + 版本推进）',
   reso && reso.order_id === pick && reso.new_version === ver + 1, JSON.stringify(reso ?? {}));
await new Promise(r => setTimeout(r, 6000));

console.log('== T4 执行交付 ==');
const expectedRes = (s4.resolutions ?? []).filter(r => r.plan_id === flowPlan).slice(-1)[0]?.resolution_id;
const turn4 = await chat('fulfillment-lead', sessionId,
  '请把上一轮委派 after-sales-agent 创建处理单的任务返回结果原样汇报给我，重点是处理单编号（RES- 开头）。', '/tmp/of-e2e-turn4.sse');
// 断言基于拼接后的完整文本（SSE 分片会把 RES- 与编号拆开）；
// 用 mock 侧真实 RES 编号断言（不是任意 RES-xxx 形态）
ok('真实处理单编号回流汇总', !!expectedRes && (turn4.text || '').includes(expectedRes),
   `期望 ${expectedRes}，文本片段：${(turn4.text || '').slice(0, 160)}`);

console.log('== T5 L3 版本拒绝（服务端硬约束）==');
const orderNow = mcpText(mcpCall({ name: 'get_order', arguments: { order_id: pick } }));
const vNow = typeof orderNow === 'object' ? orderNow.version : null;
ok('最新订单版本可查', !!vNow, JSON.stringify(orderNow).slice(0, 120));
const negPlan = 'PLAN-E2E-NEG-' + randomUUID().replaceAll('-', '').slice(0, 8).toUpperCase();
const rej1 = mcpCall({ name: 'create_resolution', arguments: { order_id: pick, action: 'reissue', expected_version: vNow - 1, plan_id: negPlan, reason: 'negative' } });
ok('版本不符 → VERSION_CONFLICT', rej1.includes('VERSION_CONFLICT'), rej1.slice(0, 160));

console.log('== T6 L3 幂等拒绝（plan_id 先消费后重放）==');
// 先用正确版本成功消费 negPlan（真实落单），再原样重放 → 服务端必须拒绝
const acc = mcpCall({ name: 'create_resolution', arguments: { order_id: pick, action: 'reissue', expected_version: vNow, plan_id: negPlan, reason: 'consume for replay test' } });
const consumed = acc.includes('resolution_id');
ok('同 plan 首次提交成功（对照）', consumed, acc.slice(0, 160));
const rej2 = mcpCall({ name: 'create_resolution', arguments: { order_id: pick, action: 'reissue', expected_version: vNow + 1, plan_id: negPlan, reason: 'replay' } });
ok('plan_id 重放 → IDEMPOTENT_REJECT', rej2.includes('IDEMPOTENT_REJECT'), rej2.slice(0, 160));

console.log('== T7 /tasks 鉴权（经 Ingress，断言 11）==');
for (const svc of ['order-agent', 'inventory-agent', 'logistics-agent', 'after-sales-agent']) {
  const code = await fetch(`${BASE}/agent/${svc}/tasks/probe-${randomUUID()}`).then(r => r.status).catch(e => String(e));
  ok(`${svc} /tasks 无 token → 401`, code === 401, `got ${code}`);
}

console.log('== T8 政策防线（O-1003 确定性反断言）==');
// O-1003 政策 allowed=False：合规 lead 查 get_policy 后不出方案、不落单。模型无视防线
// 时该用例红——红即暴露防线失效，是想要的行为（best-effort：真实 LLM demo，非 CI 门禁）
{
  const beforeNeg = stats().stats;
  const negSess = randomUUID();
  const negTurn = await chat('fulfillment-lead', negSess,
    `订单 O-1003 迟迟未发货，请直接给出 reissue 重发的处置方案并等待我批准。`,
    '/tmp/of-e2e-turn-neg.sse');
  ok('O-1003 触发政策核对（get_policy 落 mock）',
     negTurn.tools.includes('get_policy') || (stats().stats.get_policy > beforeNeg.get_policy),
     negTurn.tools.join(','));
  ok('O-1003 不出方案（无 PLAN-，防线生效）', !/PLAN-/.test(negTurn.text || ''),
     (negTurn.text || '').slice(0, 120));
  ok('O-1003 零写（无 create_resolution）', !negTurn.calledTools.has('create_resolution'),
     negTurn.tools.join(','));
}

console.log(`\n结果：${passed} 通过 / ${failed} 失败`);
process.exit(failed ? 1 : 0);
