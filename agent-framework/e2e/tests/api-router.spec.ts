/**
 * e2e-router RT 组（subpath-routing-design §9.3 本地变体）：经 platform-router nginx
 * （fixtures/nginx-router.conf.template，与 manifests/platform-router.yaml 语义同源）
 * 的 /agent/{short} 前缀全链路——路由剥前缀、X-Forwarded-Prefix 注入（方案 A 端到端）、
 * SSE/上传/Debug Console/负向语义。设计文档 R 组中依赖集群/backend 形态的断言
 * （R1-R3/R10/R11 与 H 组）由 backend go test（fake 集群）+ §9.5 手工清单覆盖。
 */
import { test, expect } from '@playwright/test';
import { ROUTER_BASE } from '../lib/env.js';
import { chat, subscribe } from '../lib/client.js';
import { waitTerminal } from '../lib/matchers.js';
import { upload } from '../lib/files.js';

// e2e 变体里所有 /agent/{short} 前缀都路由到同一本地实例：short 固定 e2e-x，
// 断言聚焦「前缀剥离 + 头注入」语义本身
const PREFIX = '/agent/e2e-x';
const R = ROUTER_BASE;

test.beforeEach(() => {
  expect(R, 'ROUTER_BASE 缺失（须由 run.sh router 组经 env-up 启动）').not.toBe('');
});

test('RT1 前缀路由主链路（rewrite 剥前缀）', async ({ request }) => {
  const health = await request.get(`${R}${PREFIX}/health`);
  expect(health.status()).toBe(200);
  const root = await (await request.get(`${R}${PREFIX}/`)).json();
  expect(root.agent).toBeTruthy();
  const threads = await request.get(`${R}${PREFIX}/threads`);
  expect(threads.status()).toBe(200);
  // 精确前缀（无尾斜杠）= jsonrpc 根，同样 200
  const bare = await request.get(`${R}${PREFIX}`);
  expect(bare.status()).toBe(200);
});

test('RT2 agent-card url 经 router 填充外部基址（X-Forwarded-Prefix 端到端）', async ({ request }) => {
  const card = await (await request.get(`${R}${PREFIX}/.well-known/agent-card.json`)).json();
  // router 兜底 $http_host（含端口）→ 基址即 ROUTER_BASE + 前缀
  expect(card.url).toBe(`${R}${PREFIX}/`);
});

test('RT3 chat SSE 经 router：file_ready.download_url 带前缀 + 经 router 下载走通', async () => {
  const stream = chat({ message: `[E2E:oaf:package]`, userId: 'e2e-router',
    base: `${R}${PREFIX}` });
  await waitTerminal(stream);
  expect(stream.terminal?.type).toBe('done');
  const ready = stream.frames.find(f => f.type === 'file_ready') as Record<string, unknown> | undefined;
  expect(ready, '缺少 file_ready 帧').toBeTruthy();
  expect(String(ready!.download_url)).toBe(`${PREFIX}/files/${ready!.file_id}`);
  // 从域名根解析的完整路径经 router 直接可下载（PK 魔数）
  const dl = await fetch(`${R}${ready!.download_url}`);
  expect(dl.status).toBe(200);
  const buf = Buffer.from(await dl.arrayBuffer());
  expect(buf.subarray(0, 2).toString('latin1')).toBe('PK');
});

test('RT4 Debug Console：尾斜杠 302 带前缀 + 静态资源经前缀可达', async ({ request }) => {
  const redir = await request.get(`${R}${PREFIX}/debug`, { maxRedirects: 0 });
  expect(redir.status()).toBe(302);
  expect(redir.headers()['location']).toBe(`${PREFIX}/debug/`);
  const page = await request.get(`${R}${PREFIX}/debug/`);
  expect(page.status()).toBe(200);
  const css = await request.get(`${R}${PREFIX}/debug/css/base.css`);
  expect(css.status()).toBe(200);
});

test('RT5 SSE 断线续传经前缀（durable 回放）', async () => {
  const stream = chat({ message: `[E2E:plain]`, userId: 'e2e-router' });
  await waitTerminal(stream);
  // 不传 sessionId → 首帧 session_created（与核心组 S2 同姿势）
  const sid = String((stream.frames[0] as Record<string, unknown>).session_id);
  const replay = subscribe(sid, 0, `${R}${PREFIX}`);
  await replay.closed;
  expect(replay.frames.map(f => f.type)).toContain('done');
});

test('RT6 负向语义：/ 前缀外 404 / 大小写敏感', async ({ request }) => {
  const root = await request.get(`${R}/`);
  expect(root.status()).toBe(404);
  const upper = await request.get(`${R}/Agent/e2e-x/health`);
  expect(upper.status()).toBe(404);
  // router 自身存活探针
  const hz = await request.get(`${R}/healthz`);
  expect(hz.status()).toBe(200);
});

test('RT7 上传经前缀（client_max_body_size 不阻拦常规上传）', async () => {
  // mime 走应用白名单（octet-stream 会被 415 拒，与 router 无关），同核心组 F4 用 text/plain
  const up = await upload(`${R}${PREFIX}`, Buffer.alloc(64 * 1024, 0x62),
    `rt7-${Date.now()}.txt`, 'text/plain', { userId: 'e2e-router' });
  expect(up.status).toBe(200);
  expect(String((up.body as Record<string, unknown>).file_id ?? '')).not.toBe('');
});

test('RT8 endpoints 广告带前缀 + base_url（经真实 router 而非头注入）', async ({ request }) => {
  const root = await (await request.get(`${R}${PREFIX}/`)).json();
  expect(root.endpoints.agent_card).toBe(`${PREFIX}/.well-known/agent-card.json`);
  expect(root.endpoints.threads).toBe(`${PREFIX}/threads`);
  expect(root.base_url).toBe(`${R}${PREFIX}`);
});
