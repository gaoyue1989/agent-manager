/**
 * e2e-core 模型管理 API：托管模型 CRUD、连接测试、会话绑定与实际路由。
 * 仅使用本地 LLM mock，不访问真实模型服务。
 */
import { test, expect, type APIRequestContext } from '@playwright/test';
import { BASE } from '../lib/env.js';
import { chat, deleteThread, llmReset, llmStats, patchThread } from '../lib/client.js';
import { waitTerminal, pollUntil } from '../lib/matchers.js';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const USER_ID = 'e2e-model-tester';
const LLM_MOCK = (process.env.E2E_LLM_MOCK ?? 'http://127.0.0.1:18081').replace(/\/$/, '');
const API_KEY = 'sk-e2e-1234567890abcd';

let uniqueSeq = 0;
const uniq = () => `${Date.now().toString(36)}${(uniqueSeq++).toString(36)}`;
const managedIds: string[] = [];
const sessionIds: string[] = [];

async function createModel(
  request: APIRequestContext,
  overrides: Record<string, unknown> = {},
): Promise<Record<string, unknown>> {
  const response = await request.post('/models', {
    data: {
      name: `e2e-model-${uniq()}`,
      modelId: 'e2e-managed-model',
      baseUrl: `${LLM_MOCK}/v1`,
      apiKey: API_KEY,
      timeoutSeconds: 5,
      ...overrides,
    },
  });
  expect(response.status()).toBe(200);
  const body = await response.json() as Record<string, unknown>;
  const id = String(body.id ?? '');
  expect(id).not.toBe('');
  managedIds.push(id);
  return body;
}

test.beforeEach(async () => { await llmReset(); });

test.afterEach(async ({ request }) => {
  for (const id of managedIds.splice(0)) {
    await request.delete(`/models/${id}`).catch(() => undefined);
  }
  for (const sid of sessionIds.splice(0)) {
    await deleteThread(sid).catch(() => undefined);
  }
});

test('MOD1 托管模型 CRUD、掩码、禁用过滤与系统模型只读', async ({ request }) => {
  const created = await createModel(request, { name: `e2e-crud-${uniq()}` });
  const id = String(created.id);

  expect(created.source).toBe('managed');
  expect(created.read_only).toBe(false);
  expect(created.api_key_masked).toBe('sk-***abcd');
  expect(JSON.stringify(created)).not.toContain(API_KEY);

  const duplicate = await request.post('/models', {
    data: { name: created.name, modelId: 'duplicate', baseUrl: `${LLM_MOCK}/v1` },
  });
  expect(duplicate.status()).toBe(400);
  expect((await duplicate.json()).error).toBe('duplicate_name');

  const cleared = await request.patch(`/models/${id}`, {
    data: { modelId: 'e2e-managed-model-v2', apiKey: '' },
  });
  expect(cleared.status()).toBe(200);
  const clearedBody = await cleared.json();
  expect(clearedBody.model_id).toBe('e2e-managed-model-v2');
  expect(clearedBody.api_key_masked).toBeNull();

  const detail = await request.get(`/models/${id}`);
  expect(detail.status()).toBe(200);
  expect((await detail.json()).api_key_masked).toBeNull();

  const enabledList = await (await request.get('/models')).json() as { models: Array<Record<string, unknown>> };
  expect(enabledList.models.some(m => m.id === id)).toBe(true);
  const disabled = await request.patch(`/models/${id}`, { data: { enabled: false } });
  expect(disabled.status()).toBe(200);
  expect((await disabled.json()).enabled).toBe(false);

  const picker = await (await request.get('/models')).json() as { models: Array<Record<string, unknown>> };
  expect(picker.models.some(m => m.id === id)).toBe(false);
  const all = await (await request.get('/models?all=true')).json() as { models: Array<Record<string, unknown>> };
  expect(all.models.some(m => m.id === id && m.enabled === false)).toBe(true);

  expect((await request.get('/models/system')).status()).toBe(200);
  expect((await request.patch('/models/system', { data: { name: 'changed' } })).status()).toBe(400);
  expect((await request.delete('/models/system')).status()).toBe(400);
  expect((await request.get(`/models/missing-${uniq()}`)).status()).toBe(404);
});

test('MOD2 模型连接测试使用目标配置，失败返回 502', async ({ request }) => {
  const okModel = await createModel(request);
  const okId = String(okModel.id);
  const ok = await request.post(`/models/${okId}/test`);
  expect(ok.status()).toBe(200);
  const okBody = await ok.json();
  expect(okBody.ok).toBe(true);
  expect(typeof okBody.latency_ms).toBe('number');

  await pollUntil(
    async () => (await llmStats()).calls,
    calls => calls.some(call => call.model === 'e2e-managed-model'),
  );

  const badModel = await createModel(request, {
    baseUrl: 'http://127.0.0.1:1/v1',
    timeoutSeconds: 1,
  });
  const bad = await request.post(`/models/${String(badModel.id)}/test`);
  expect(bad.status()).toBe(502);
  const badBody = await bad.json();
  expect(badBody.ok).toBe(false);
  expect(badBody.error).toBe('model_test_failed');
  expect((await request.post(`/models/missing-${uniq()}/test`)).status()).toBe(404);
});

test('MOD3 会话 PATCH 切换模型并影响下一轮真实 LLM 请求', async ({ request }) => {
  const model = await createModel(request);
  const id = String(model.id);
  const sid = `e2e-model-session-${uniq()}`;
  sessionIds.push(sid);

  const first = chat({ message: '[E2E:plain]先使用系统模型', userId: USER_ID, sessionId: sid });
  await waitTerminal(first);

  const patched = await patchThread(sid, { model: id });
  expect(patched.status).toBe(200);
  expect((await patched.json()).model).toBe(id);
  const detail = await request.get(`/threads/${sid}`);
  expect((await detail.json()).model).toBe(id);

  const second = chat({ message: '[E2E:plain]切换到托管模型', userId: USER_ID, sessionId: sid });
  await waitTerminal(second);
  await pollUntil(
    async () => (await llmStats()).calls,
    calls => calls.some(call => call.scenario === 'plain' && call.model === 'e2e-managed-model'),
  );

  await request.patch(`/models/${id}`, { data: { enabled: false } });
  const rejected = await patchThread(sid, { model: id });
  expect(rejected.status).toBe(400);
  expect((await rejected.json()).error).toBe('model_disabled');
  expect((await (await request.get(`/threads/${sid}`)).json()).model).toBe(id);

  const invalid = chat({ message: '[E2E:plain]未知模型', userId: USER_ID, sessionId: `e2e-invalid-${uniq()}`, model: `missing-${uniq()}` });
  await waitTerminal(invalid);
  expect(invalid.terminal?.type).toBe('error');
  expect(String(invalid.terminal?.error)).toContain('unknown_model');

  const beforeDelete = (await llmStats()).calls.length;
  const deleted = await request.delete(`/models/${id}`);
  expect(deleted.status()).toBe(200);
  managedIds.splice(managedIds.indexOf(id), 1);

  const third = chat({ message: '[E2E:plain]模型删除后回落系统模型', userId: USER_ID, sessionId: sid });
  await waitTerminal(third);
  await pollUntil(
    async () => (await llmStats()).calls.slice(beforeDelete),
    calls => calls.some(call => call.scenario === 'plain' && call.model === 'e2e-mock-model'),
  );

  const listUrl = new URL('/threads', BASE);
  listUrl.searchParams.set('userId', USER_ID);
  const rows = await (await request.get(`${listUrl.pathname}${listUrl.search}`)).json() as Array<Record<string, unknown>>;
  expect(rows.find(row => row.session_id === sid)?.model).toBe(id);
});

/** 新采样参数 400 契约：invalid_config + message 片段 */
async function expectInvalidConfig(response: { status: () => number; json: () => Promise<Record<string, unknown>> }, messageFragment: string): Promise<void> {
  expect(response.status()).toBe(400);
  const body = await response.json();
  expect(body.error).toBe('invalid_config');
  expect(String(body.message)).toContain(messageFragment);
}

test('MOD4 新采样参数契约：provider 枚举/effort 格式/penalty 范围 400 与回显三态', async ({ request }) => {
  // —— POST 负例：provider 值域（ModelController.validateSamplingParams）——
  await expectInvalidConfig(
    await request.post('/models', { data: { name: `e2e-bad-${uniq()}`, modelId: 'm', baseUrl: `${LLM_MOCK}/v1`, provider: 'azure' } }),
    'provider must be one of',
  );
  // —— POST 负例：reasoningEffort 格式（^[a-z0-9_]{1,16}$，大写与超长均拒）——
  await expectInvalidConfig(
    await request.post('/models', { data: { name: `e2e-bad-${uniq()}`, modelId: 'm', baseUrl: `${LLM_MOCK}/v1`, reasoningEffort: 'Medium' } }),
    'reasoningEffort must match',
  );
  await expectInvalidConfig(
    await request.post('/models', { data: { name: `e2e-bad-${uniq()}`, modelId: 'm', baseUrl: `${LLM_MOCK}/v1`, reasoningEffort: 'x'.repeat(17) } }),
    'reasoningEffort must match',
  );
  // —— POST 负例：frequencyPenalty 开区间越界（[-2.0, 2.0]）——
  await expectInvalidConfig(
    await request.post('/models', { data: { name: `e2e-bad-${uniq()}`, modelId: 'm', baseUrl: `${LLM_MOCK}/v1`, frequencyPenalty: 2.1 } }),
    'frequencyPenalty must be within [-2.0, 2.0]',
  );
  await expectInvalidConfig(
    await request.post('/models', { data: { name: `e2e-bad-${uniq()}`, modelId: 'm', baseUrl: `${LLM_MOCK}/v1`, frequencyPenalty: -2.1 } }),
    'frequencyPenalty must be within [-2.0, 2.0]',
  );

  // —— 边界值 + trim 归一：±2.0 恰好过界（POST 双侧对称）；effort 前后空白被去除 ——
  const edgeLow = await createModel(request, {
    provider: 'vllm', reasoningEffort: ' high ', frequencyPenalty: -2,
  });
  const edgeId = String(edgeLow.id);
  expect(edgeLow.provider).toBe('vllm');
  expect(edgeLow.reasoning_effort).toBe('high');
  expect(edgeLow.frequency_penalty).toBe(-2);

  const edgeHigh = await createModel(request, { frequencyPenalty: 2 });
  expect(edgeHigh.frequency_penalty).toBe(2);

  // —— POST 缺省新字段：provider 落默认 openai、effort/penalty 回显 null（不下发语义）——
  const plain = await createModel(request);
  expect(plain.provider).toBe('openai');
  expect(plain.reasoning_effort).toBeNull();
  expect(plain.frequency_penalty).toBeNull();

  // —— POST 空串 effort：清除语义（normEffort 空白 → null）——
  const blank = await createModel(request, { reasoningEffort: '' });
  expect(blank.reasoning_effort).toBeNull();

  // —— PATCH 三态：缺省=不变；空串=清除；非 null=替换；provider 空白串=保持旧值 ——
  const untouched = await request.patch(`/models/${edgeId}`, {
    data: { name: `e2e-renamed-${uniq()}` },
  });
  expect(untouched.status()).toBe(200);
  const untouchedBody = await untouched.json();
  expect(untouchedBody.provider).toBe('vllm');
  expect(untouchedBody.reasoning_effort).toBe('high');
  expect(untouchedBody.frequency_penalty).toBe(-2);

  const blankProvider = await request.patch(`/models/${edgeId}`, { data: { provider: '' } });
  expect(blankProvider.status()).toBe(200);
  expect((await blankProvider.json()).provider).toBe('vllm');

  const cleared = await request.patch(`/models/${edgeId}`, {
    data: { reasoningEffort: '', frequencyPenalty: 2 },
  });
  expect(cleared.status()).toBe(200);
  const clearedBody = await cleared.json();
  expect(clearedBody.reasoning_effort).toBeNull();
  expect(clearedBody.frequency_penalty).toBe(2);

  // —— PATCH 负例不落库：非法值 400 后 GET 确认旧值未动 ——
  await expectInvalidConfig(
    await request.patch(`/models/${edgeId}`, { data: { reasoningEffort: 'HIGH', frequencyPenalty: 3 } }),
    'reasoningEffort must match',
  );
  const afterReject = await request.get(`/models/${edgeId}`);
  expect(afterReject.status()).toBe(200);
  const afterRejectBody = await afterReject.json();
  expect(afterRejectBody.reasoning_effort).toBeNull();
  expect(afterRejectBody.frequency_penalty).toBe(2);
});

test('MOD5 系统模型视图：env 缺省下 provider 默认值与采样参数 null 归一', async ({ request }) => {
  // e2e 的 start-agent.sh 只注入 LLM_BASE_URL/LLM_API_KEY/LLM_MODEL_ID；
  // LLM_PROVIDER/LLM_REASONING_EFFORT/LLM_FREQUENCY_PENALTY 未设置 → 走 application.yml 默认；
  // 注意 enable-thinking 缺省为 false（application.yml ${LLM_ENABLE_THINKING:false}，
  // LLM_ENABLE_THINKING 在 e2e 脚本/CI 均未导出，覆盖 LLMConfig 注解里的 @DefaultValue("true")）
  const system = await request.get('/models/system');
  expect(system.status()).toBe(200);
  const sys = await system.json() as Record<string, unknown>;
  expect(sys.provider).toBe('openai');          // LLM_PROVIDER 缺省（yml ${LLM_PROVIDER:openai}）
  expect(sys.reasoning_effort).toBeNull();      // LLM_REASONING_EFFORT 空串 → blankToNull 归一为 null（非 ''）
  expect(sys.frequency_penalty).toBeNull();     // LLM_FREQUENCY_PENALTY 未配置 → Double null
  expect(sys.enable_thinking).toBe(false);      // yml 显式缺省 false（与 LLMConfig @DefaultValue("true") 不同，yml 优先）
  expect(sys.source).toBe('system');
  expect(sys.read_only).toBe(true);
  expect(sys.is_default).toBe(true);
  expect(sys.model_id).toBe('e2e-mock-model');  // 与 start-agent.sh 注入的 LLM_MODEL_ID 同源

  // 列表契约（docs/model-params-design.md：GET /models 列表项不扩展，维持 picker 轻量语义）
  // → 列表项只断 ModelOption 既有字段，不涉及 reasoning_effort/frequency_penalty/read_only
  const list = await (await request.get('/models')).json() as {
    default_model: string;
    models: Array<Record<string, unknown>>;
  };
  expect(list.default_model).toBe('system');
  const systemOption = list.models.find(m => m.id === 'system');
  expect(systemOption).toBeTruthy();
  expect(systemOption!.provider).toBe('openai');
  expect(systemOption!.is_default).toBe(true);
  expect(systemOption!.source).toBe('system');

  // 托管项：source/enabled 在列表项可见；read_only 仅存在于详情视图（managedView）
  const created = await createModel(request);
  const createdId = String(created.id);
  const relist = await (await request.get('/models')).json() as { models: Array<Record<string, unknown>> };
  const occurrences = relist.models.filter(m => m.id === createdId);
  expect(occurrences.length).toBe(1);           // 列表中恰好出现一次（不重复、不遗漏）
  expect(occurrences[0].source).toBe('managed');

  const detail = await request.get(`/models/${createdId}`);
  expect(detail.status()).toBe(200);
  const detailBody = await detail.json() as Record<string, unknown>;
  expect(detailBody.read_only).toBe(false);     // read_only 断在详情视图
  expect(detailBody.source).toBe('managed');
  expect(detailBody.reasoning_effort).toBeNull();   // 详情视图含新采样键（POST 缺省 → null）
  expect(detailBody.frequency_penalty).toBeNull();
});

test('MOD6 托管模型方言矩阵：采样参数在 LLM 请求体中的实际落点', async ({ request }) => {
  // 依赖 llm-server.mjs stats.calls[].sampling 增记（chat_template_kwargs/thinking/
  // reasoning_effort/frequency_penalty，取请求体同名键 ?? null）。
  // 四方言统一配置：enableThinking=false + effort + penalty，差异只来自 applyDialect 分支。
  // 每方言用唯一 modelId：stats.calls[].model 记录的是请求体 model 字段 = 配置 modelId
  //（ChatModelFactory .modelName(llm.modelId())），非托管配置 UUID——
  // 唯一 modelId 保证逐轮 find 精确命中本轮调用，不与前一方言的调用串扰
  const dialectExpect: Record<string, Record<string, unknown>> = {
    // vllm：合并方言——effort+开关同入一个 chat_template_kwargs，顶层无 effort/thinking
    vllm: { chat_template_kwargs: { enable_thinking: false, reasoning_effort: 'high' }, thinking: null, reasoning_effort: null, frequency_penalty: 0.5 },
    // glm：thinking.type 嵌套对象 + 顶层一等 effort
    glm: { chat_template_kwargs: null, thinking: { type: 'disabled' }, reasoning_effort: 'high', frequency_penalty: 0.5 },
    // deepseek：官方无对应参数——effort/开关均不下发，仅顶层标准项 penalty
    deepseek: { chat_template_kwargs: null, thinking: null, reasoning_effort: null, frequency_penalty: 0.5 },
    // openai：顶层一等 effort，enable_thinking=false 不产生任何附加字段（D8）
    openai: { chat_template_kwargs: null, thinking: null, reasoning_effort: 'high', frequency_penalty: 0.5 },
  };

  for (const [provider, expected] of Object.entries(dialectExpect)) {
    const modelId = `e2e-dialect-${provider}-${uniq()}`;
    const model = await createModel(request, {
      name: `e2e-dialect-${provider}-${uniq()}`,
      modelId,
      provider,
      enableThinking: false,
      reasoningEffort: 'high',
      frequencyPenalty: 0.5,
    });
    const id = String(model.id);
    expect(model.provider).toBe(provider);
    expect(model.model_id).toBe(modelId);

    const sid = `e2e-dialect-${provider}-${uniq()}`;
    sessionIds.push(sid);
    const patched = await patchThread(sid, { model: id });
    expect(patched.status).toBe(200);

    const stream = chat({ message: `[E2E:plain]验证 ${provider} 方言`, userId: USER_ID, sessionId: sid });
    await waitTerminal(stream);
    expect(stream.terminal?.type).toBe('done');

    // 只认主对话调用（scenario='plain'，排除标题/记忆 background-synth），
    // 且按本轮唯一 modelId 过滤（请求体 model 字段，对齐 MOD3 既有匹配方式）
    const call = await pollUntil(
      async () => (await llmStats()).calls.find(c => c.scenario === 'plain' && c.model === modelId),
      c => c !== undefined,
    );
    // 逐方言精确断言请求体落点（toEqual 全量匹配：多下的键与少下的键都算错）
    expect(call.sampling, `provider=${provider} 请求体采样落点`).toEqual(expected);
  }
});

// ---------- MOD7：env 路径大写 provider 归一（进程编排先例 api-multi-kill spawnSync start-agent.sh） ----------

const SIDE_PORT = '8110';
const SIDE_BASE = `http://127.0.0.1:${SIDE_PORT}`;
const RUNTIME_DIR = process.env.E2E_RUNTIME_DIR ?? '.runtime';
const SIDE_PID_FILE = path.join(RUNTIME_DIR, 'agent-model-env.pid');
const START_SCRIPT = path.join(path.dirname(fileURLToPath(import.meta.url)), '../scripts/start-agent.sh');

/** 起 env 方言侧实例：LLM_PROVIDER 大写注入（nohup java 继承父环境 → application.yml ${LLM_*} 绑定） */
function startDialectInstance(): void {
  const r = spawnSync('bash', [START_SCRIPT, 'model-env', SIDE_PORT], {
    encoding: 'utf8',
    timeout: 120_000,
    // 大写 VLLM 是被测点：归一发生在 ChatModelFactory.applyDialect（trim+toLowerCase），
    // 视图层原样回显不归一
    env: {
      ...process.env,
      LLM_PROVIDER: 'VLLM',
      LLM_REASONING_EFFORT: 'high',
      LLM_ENABLE_THINKING: 'false',
      LLM_FREQUENCY_PENALTY: '0.5',
    },
  });
  // 以脚本 exit 0 为成功判据（wait-ready 在脚本内）：pid 文件在 wait-ready 之前就写入，
  // JVM 起不来时 pid 文件仍存在，不能作为启动成功依据——对齐 api-multi-kill restoreReplicaA
  if (r.error || r.status !== 0) {
    throw new Error(
      `侧实例启动失败 exit=${r.status}${r.error ? `，error=${r.error.message}` : ''}\n` +
      `stdout: ${(r.stdout ?? '').slice(-400)}\nstderr: ${(r.stderr ?? '').slice(-800)}`,
    );
  }
}

/** 清场：SIGKILL 侧实例（若存活）并移除 pid 文件（与 start-agent.sh 端口清场同语义） */
function stopDialectInstance(): void {
  try {
    const pid = Number(fs.readFileSync(SIDE_PID_FILE, 'utf8').trim());
    if (Number.isInteger(pid) && pid > 0) {
      process.kill(pid, 0);            // 探活：死 pid（启动失败的残留）直接跳过
      process.kill(pid, 'SIGKILL');
    }
  } catch { /* pid 文件缺失或进程已死即已清场 */ }
  fs.rmSync(SIDE_PID_FILE, { force: true });
}

test.afterAll(() => stopDialectInstance());

test('MOD7 env 路径大写 provider 归一：VLLM 注入落 vllm 方言分支而非 openai 兜底', async () => {
  startDialectInstance();

  // ① 视图层：env 值原样回显（大写保留）——归一只发生在方言层，视图与方言各自可断言
  const sys = await (await fetch(`${SIDE_BASE}/models/system`)).json() as Record<string, unknown>;
  expect(sys.provider).toBe('VLLM');
  expect(sys.reasoning_effort).toBe('high');
  expect(sys.frequency_penalty).toBe(0.5);
  expect(sys.enable_thinking).toBe(false);

  // ② 行为层：beforeEach 的 llmReset 已清空 stats；system 模型 modelId=e2e-mock-model，
  //    reset 后本轮 plain 调用只能来自侧实例（MOD6 各托管模型 modelId 唯一，不受影响）
  const sid = `e2e-env-dialect-${uniq()}`;
  sessionIds.push(sid);
  const stream = chat({ message: '[E2E:plain]验证 env 大写 provider 归一', userId: USER_ID, sessionId: sid, base: SIDE_BASE });
  await waitTerminal(stream);
  expect(stream.terminal?.type).toBe('done');

  const call = await pollUntil(
    async () => (await llmStats()).calls.find(c => c.scenario === 'plain' && c.model === 'e2e-mock-model'),
    c => c !== undefined,
  );
  // 大写 VLLM 命中 vllm 合并方言：kwargs 含 effort+开关；顶层 effort/thinking 均不下发。
  // 若归一失效会落 openai 兜底分支：顶层 reasoning_effort='high' 且无 kwargs——对严格
  // vLLM 端点即 400，此前门禁不可见
  expect(call.sampling).toEqual({
    chat_template_kwargs: { enable_thinking: false, reasoning_effort: 'high' },
    thinking: null,
    reasoning_effort: null,
    frequency_penalty: 0.5,
  });
});
