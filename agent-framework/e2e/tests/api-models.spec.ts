/**
 * e2e-core 模型管理 API：托管模型 CRUD、连接测试、会话绑定与实际路由。
 * 仅使用本地 LLM mock，不访问真实模型服务。
 */
import { test, expect, type APIRequestContext } from '@playwright/test';
import { BASE } from '../lib/env.js';
import { chat, deleteThread, llmReset, llmStats, patchThread } from '../lib/client.js';
import { waitTerminal, pollUntil } from '../lib/matchers.js';

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
