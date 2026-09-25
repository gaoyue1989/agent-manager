/**
 * e2e-core OAF 动态 reload：状态、单 MCP server、auto 指纹分流、整包重建与失败回滚。
 * 测试直接修改 .runtime/agent-config，并在 finally/afterAll 恢复原配置。
 */
import { readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test, expect, type APIRequestContext } from '@playwright/test';
import { chat, llmStats } from '../lib/client.js';
import { waitTerminal, toolNames, pollUntil } from '../lib/matchers.js';
import { sessionIdFor } from '../lib/env.js';

const RUNTIME_CONFIG = fileURLToPath(new URL('../.runtime/agent-config/', import.meta.url));
const AGENTS_PATH = join(RUNTIME_CONFIG, 'AGENTS.md');
const BENCH_CONFIG_PATH = join(RUNTIME_CONFIG, 'mcp-configs', 'bench', 'config.yaml');
const RELOAD_MARKER = `E2E_RELOAD_${Date.now().toString(36).toUpperCase()}`;

let originalAgents = '';
let originalBenchConfig = '';

async function reload(request: APIRequestContext, scope: string): Promise<Record<string, unknown>> {
  const response = await request.post(`/admin/reload?scope=${scope}`);
  expect(response.status()).toBe(200);
  return response.json() as Promise<Record<string, unknown>>;
}

test.describe.serial('OAF 动态 reload', () => {
  test.beforeAll(async () => {
    [originalAgents, originalBenchConfig] = await Promise.all([
      readFile(AGENTS_PATH, 'utf8'),
      readFile(BENCH_CONFIG_PATH, 'utf8'),
    ]);
  });

  test.afterAll(async ({ request }) => {
    const [currentAgents, currentBench] = await Promise.all([
      readFile(AGENTS_PATH, 'utf8'),
      readFile(BENCH_CONFIG_PATH, 'utf8'),
    ]);
    await writeFile(AGENTS_PATH, originalAgents, 'utf8');
    await writeFile(BENCH_CONFIG_PATH, originalBenchConfig, 'utf8');
    if (currentAgents !== originalAgents) {
      const response = await request.post('/admin/reload?scope=agent');
      expect(response.status()).toBe(200);
    } else if (currentBench !== originalBenchConfig) {
      const response = await request.post('/admin/reload?scope=mcp&server=bench');
      expect(response.status()).toBe(200);
    }
  });

  test('RL1 reload 状态与单 MCP server 原地重载', async ({ request }) => {
    const statusResponse = await request.get('/admin/reload');
    expect(statusResponse.status()).toBe(200);
    const status = await statusResponse.json() as { registeredServers: Array<Record<string, unknown>> };
    const bench = status.registeredServers.find(server => server.server === 'bench');
    expect(bench).toBeTruthy();
    expect(bench!.connected).toBe(true);
    expect(Number(bench!.tool_count)).toBeGreaterThan(0);

    const result = await reload(request, 'mcp&server=bench');
    expect(result.scope).toBe('mcp');
    expect(result.fingerprint_changed).toBe(true);
    expect(result.agent_rebuilt).toBe(false);
    const detail = (result.mcp_servers as Array<Record<string, unknown>>)[0];
    expect(detail.server).toBe('bench');
    expect(detail.action).toBe('reloaded');
    expect(detail.ok).toBe(true);
    expect(Number(detail.tool_count)).toBeGreaterThan(0);

    const noop = await request.post('/admin/reload?scope=auto');
    expect(noop.status()).toBe(200);
    expect((await noop.json()).scope).toBe('noop');

    const tools = await (await request.get('/tools')).json();
    expect(JSON.stringify(tools)).toContain('bench_echo');
    const sid = sessionIdFor(`rl-mcp-${Date.now().toString(36)}`);
    const stream = chat({ message: '[E2E:tool:mcp_echo](reload-probe)', userId: 'e2e-reload', sessionId: sid });
    await waitTerminal(stream);
    expect(stream.terminal?.type).toBe('done');
    expect(toolNames(stream.frames)).toContain('bench_echo');
  });

  test('RL2 auto 识别仅 MCP 文件变化并保持 agent 引用', async ({ request }) => {
    const changed = `${originalBenchConfig}\n# ${RELOAD_MARKER}\n`;
    await writeFile(BENCH_CONFIG_PATH, changed, 'utf8');
    try {
      const result = await reload(request, 'auto');
      expect(result.scope).toBe('mcp');
      expect(result.fingerprint_changed).toBe(true);
      expect(result.agent_rebuilt).toBe(false);
      const servers = result.mcp_servers as Array<Record<string, unknown>>;
      expect(servers.some(server => server.server === 'bench' && server.ok === true)).toBe(true);
    } finally {
      await writeFile(BENCH_CONFIG_PATH, originalBenchConfig, 'utf8');
      const response = await request.post('/admin/reload?scope=mcp&server=bench');
      expect(response.status()).toBe(200);
    }
  });

  test('RL3 auto 整包重建更新卡片与系统提示，非法配置保持旧版本', async ({ request }) => {
    const changed = originalAgents
      .replace('name: "E2E Test Agent"', 'name: "E2E Reload Agent"')
      .replace('description: "E2E 测试 agent（配合 mock LLM/MCP/沙箱，e2e-ci-plan §4.3）"',
        `description: "E2E reload probe ${RELOAD_MARKER}"`)
      .concat(`\n${RELOAD_MARKER}\n`);
    expect(changed).not.toBe(originalAgents);

    await writeFile(AGENTS_PATH, changed, 'utf8');
    try {
      const result = await reload(request, 'auto');
      expect(result.scope).toBe('agent');
      expect(result.fingerprint_changed).toBe(true);
      expect(result.agent_rebuilt).toBe(true);

      const root = await (await request.get('/')).json();
      expect(root.agent).toBe('E2E Reload Agent');
      const metadata = await (await request.get('/metadata')).json();
      expect(metadata.description).toContain(RELOAD_MARKER);
      const card = await (await request.get('/.well-known/agent-card.json')).json();
      expect(card.name).toBe('E2E Reload Agent');
      expect(card.description).toContain(RELOAD_MARKER);
      const prompt = await (await request.get('/system-prompt')).json();
      expect(prompt.base_prompt).toContain(RELOAD_MARKER);

      const sid = sessionIdFor(`rl-agent-${Date.now().toString(36)}`);
      const stream = chat({ message: '[E2E:plain]验证重建后的系统提示', userId: 'e2e-reload', sessionId: sid });
      await waitTerminal(stream);
      expect(stream.terminal?.type).toBe('done');
      await pollUntil(
        async () => (await llmStats()).calls,
        calls => calls.some(call => call.scenario === 'plain' && String(call.systemContent).includes(RELOAD_MARKER)),
      );

      await writeFile(AGENTS_PATH, '---\nname: [broken\n---\nbody\n', 'utf8');
      const failed = await request.post('/admin/reload?scope=agent');
      expect(failed.status()).toBe(500);
      const failure = await failed.json();
      expect(failure.error).toBeTruthy();
      expect(failure.note).toBe('old configuration remains active');

      expect((await (await request.get('/')).json()).agent).toBe('E2E Reload Agent');
      expect((await (await request.get('/system-prompt')).json()).base_prompt).toContain(RELOAD_MARKER);
    } finally {
      await writeFile(AGENTS_PATH, originalAgents, 'utf8');
      const response = await request.post('/admin/reload?scope=agent');
      expect(response.status()).toBe(200);
    }

    expect((await (await request.get('/')).json()).agent).toBe('E2E Test Agent');
    const invalidScope = await request.post('/admin/reload?scope=unknown');
    expect(invalidScope.status()).toBe(400);
  });
});
