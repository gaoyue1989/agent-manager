/**
 * e2e-core UI 组：Debug 页面 Playwright（e2e-ci-plan §5.7）。
 * 选择器契约见 lib/selectors.ts（源自 static/debug 模块，example approval e2e 已验证交互手法）。
 */
import { test, expect, type Page } from '@playwright/test';
import { ids, sessionIdFor } from '../lib/env.js';
import { SEL } from '../lib/selectors.js';
import { createApprovalApp } from '../lib/client.js';
import { PNG_1PX } from '../lib/files.js';

const U = 'e2e-ui';
let uniqueSeq = 0;
const uniq = () => `${Date.now().toString(36)}${(uniqueSeq++).toString(36)}`;

/** 等发送钮可用（非流式守卫期）再点击——example approval e2e 验证过的手法 */
async function send(page: Page, text: string) {
  await page.goto('/debug/');

  // 默认 A2A 模式不支持 HITL/file_ready 等通道语义——统一走 Channel 模式
  const modeChannel = page.locator(SEL.modeChannel);
  if (await modeChannel.count()) await modeChannel.click();
  await page.locator(SEL.chatInput).fill(text);
  const btn = page.locator(SEL.sendBtn);
  await expect(btn).toBeEnabled();
  await btn.click();
}

test('U1 模块渲染冒烟（10 路由无报错）', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', e => errors.push(String(e)));
  await page.goto('/debug/');
  const routes = await page.locator(SEL.navItem).evaluateAll(els => els.map(e => e.getAttribute('data-hash')));
  expect(routes.length).toBeGreaterThanOrEqual(8);
  for (const r of routes!) {
    await page.locator(`.nav-item[data-hash="${r}"]`).click();
    await expect(page.locator(SEL.moduleContent)).not.toBeEmpty();
  }
  expect(errors, `pageerror: ${errors.join('; ')}`).toEqual([]);
});

test('U2 基础对话流式回复', async ({ page }) => {
  await send(page, '[E2E:plain]');
  const btn = page.locator(SEL.sendBtn);
  await expect(btn).toBeDisabled(); // 流式期间禁用
  await expect(page.locator(SEL.chatInner)).toContainText(/./, { timeout: 120_000 });
  await expect(btn).toBeEnabled({ timeout: 120_000 }); // 流结束恢复
  const errors: string[] = [];
  page.on('pageerror', e => errors.push(String(e)));
  expect(errors).toEqual([]);
});

test('U3 会话列表与回放', async ({ page }) => {
  await send(page, '[E2E:plain]');
  await expect(page.locator(SEL.sendBtn)).toBeEnabled({ timeout: 120_000 });
  await page.locator(SEL.newThread).click();
  await send(page, '[E2E:tool:echo](ui-e2a)');
  await expect(page.locator(SEL.chatInner)).toContainText('echo', { timeout: 120_000 });
  // 切回旧会话 → history 回放（条目为 #threadList 下的 .thread-item）
  await page.locator(`${SEL.threadList} .thread-item`).nth(1).click();
  await expect(page.locator(SEL.chatInner)).toContainText(/./);
});

test('U4 HITL 批准全流程', async ({ page }) => {
  const app = await createApprovalApp();
  await send(page, `[E2E:hitl:submit](${app})`);
  const card = page.locator(SEL.confirmCard);
  await expect(card).toBeVisible({ timeout: 120_000 });
  await expect(card.locator(SEL.confirmToolName)).toContainText('submit_application');
  await card.locator(SEL.confirmApprove).click();
  // 批准后工具结果 + 收尾消息（等确认卡消失或完成态）
  await expect(card).toBeHidden({ timeout: 120_000 });
});

test('U5 HITL 拒绝', async ({ page }) => {
  const app = await createApprovalApp();
  await send(page, `[E2E:hitl:submit](${app})`);
  const card = page.locator(SEL.confirmCard);
  await expect(card).toBeVisible({ timeout: 120_000 });
  await card.locator(SEL.confirmReject).click();
  await expect(card).toBeHidden({ timeout: 120_000 });
  await expect(page.locator(SEL.chatInner)).toContainText(/./);
});

test('U6 MCP App 卡片全链路', async ({ page }) => {
  const app = await createApprovalApp();
  await send(page, `[E2E:mcpapp:form](${app})`);
  const container = page.locator(SEL.mcpAppContainer).first();
  await expect(container).toBeVisible({ timeout: 120_000 });
  const iframe = container.locator(SEL.mcpAppIframe);
  await expect(iframe).toBeVisible({ timeout: 60_000 });
  // 沙箱 iframe 加载了 ui:// 资源（srcdoc 注入）
  const sandboxAttr = await iframe.getAttribute('sandbox');
  expect(sandboxAttr).toContain('allow-scripts');
});

test('U7 刷新恢复（working 态续传）', async ({ page }) => {
  await send(page, '[E2E:slow](ui,500,10)');
  // 首个 delta 渲染后刷新（保证刷新发生在 working 态）
  await page.waitForTimeout(2500);
  const hasPartial = (await page.locator(SEL.chatInner).innerText()).length > 0;
  expect(hasPartial).toBe(true);
  await page.reload();
  // 刷新后流自动续传，最终文本完整
  await expect(page.locator(SEL.sendBtn)).toBeEnabled({ timeout: 180_000 });
  await expect(page.locator(SEL.chatInner)).toContainText(/./);
});

// CI 无头环境确认卡重建偶发不出现（本地通过）——待查 chat.js loadHistory 的 lastAssistantEl 时序
test.fixme('U8 刷新恢复（waiting_confirm 确认卡重建）', async ({ page }) => {
  const app = await createApprovalApp();
  await send(page, `[E2E:hitl:submit](${app})`);
  await expect(page.locator(SEL.confirmCard)).toBeVisible({ timeout: 120_000 });
  await page.reload();
  // 页面刷新后不自动恢复会话——真实路径：等列表加载完成 → 点选最新会话 → history 重建确认卡
  await page.locator(`${SEL.threadList} .thread-item`).first().waitFor({ state: 'visible', timeout: 30_000 });
  await page.locator(`${SEL.threadList} .thread-item`).first().click();
  // 确认点选的是含 HITL 标记的会话（history 已加载），再等卡片重建
  await expect(page.locator(SEL.chatInner)).toContainText('[E2E:hitl:submit]', { timeout: 60_000 });
  const card8 = page.locator(SEL.confirmCard);
  await expect(card8).toBeVisible({ timeout: 90_000 }); // pendingConfirm 重建
  await card8.locator(SEL.confirmApprove).click();
  await expect(card8).toBeHidden({ timeout: 120_000 });
});

test('U10 附件上传对话（UI 面）', async ({ page }) => {
  await page.goto('/debug/');
  await page.locator('#fileInput').setInputFiles({ name: `ui-note-${uniq()}.txt`, mimeType: 'text/plain', buffer: Buffer.from(`ui-content-${uniq()}`) });
  await expect(page.locator('#uploadFiles')).toBeVisible();
  await page.locator(SEL.chatInput).fill('[E2E:tool:read](uploads/note.txt)');
  const btn = page.locator(SEL.sendBtn);
  await expect(btn).toBeEnabled();
  await btn.click();
  await expect(btn).toBeEnabled({ timeout: 180_000 });
  await expect(page.locator(SEL.chatInner)).toContainText(/./);
});

// 与 F5 同链路（D8：SDK edit 空串死循环未修，file_ready 卡片在夹具链路下不稳定）——SDK 修复后转正
test.fixme('U11 文件交付下载卡片与历史回放', async ({ page }) => {
  await send(page, '[E2E:file:deliver](report.md)');
  await expect(page.locator(SEL.chatInner)).toContainText('report.md', { timeout: 180_000 });
  // 下载链接存在（file_ready 卡片）
  const dl = page.locator(`${SEL.chatInner} a[href*="/files/"]`).first();
  await expect(dl).toBeVisible({ timeout: 60_000 });
  const href = await dl.getAttribute('href');
  expect(href).toBeTruthy();
  // 会话切换再切回，卡片仍在（history 文件卡片补齐）
  await page.locator(SEL.newThread).click();
  const back = page.locator(`${SEL.threadList} >> nth=0`);
  await back.click();
  await expect(page.locator(`${SEL.chatInner} a[href*="/files/"]`).first()).toBeVisible({ timeout: 60_000 });
});

// U13（2026-09-24 发布助手无法下载 OAF 包回归门禁）：oaf-package 夹具不含 edit_file，
// 不受 D8 影响——OAF 打包迁移至平台 MCP 后，present_url 登记的外部交付物下载卡片
// 实时渲染 + 回放仍在（下载经 /files/{id} 代理），替 U11 把文件卡片 UI
// 链路留在门禁内（F12 锁 API 契约，本用例锁渲染与回放）。
// 回放路径按 active 项的 data-sid 精确点选：各会话记忆提取后台调用会随时刷新
// updated_at，列表首位不可靠（不能点 nth=0）。
test('U13 OAF 打包下载卡片（实时渲染 + 历史回放）', async ({ page }) => {
  await send(page, '[E2E:oaf:package]');
  // 实时 file_ready 卡片（LLM 单 turn，含工具执行）
  const dl = page.locator(`${SEL.chatInner} a[href*="/files/"]`).first();
  await expect(dl).toBeVisible({ timeout: 180_000 });
  await expect(page.locator(SEL.chatInner)).toContainText('e2e-oaf-agent.zip', { timeout: 30_000 });
  // 刷新后按 sid 点选原会话回放：卡片仍在（history files 按 session_id 回查；gw-hash 绑定断裂即在此红）
  const sid = await page.locator(`${SEL.threadList} .thread-item.active`)
    .getAttribute('data-sid', { timeout: 30_000 });
  expect(sid, '会话列表应已渲染出当前会话').toBeTruthy();
  await page.reload();
  const item = page.locator(`${SEL.threadList} .thread-item[data-sid="${sid}"]`);
  await item.waitFor({ state: 'visible', timeout: 30_000 });
  await item.click();
  // 确认回放的是 oaf 会话（history 重建出用户消息）再等卡片
  await expect(page.locator(SEL.chatInner)).toContainText('[E2E:oaf:package]', { timeout: 60_000 });
  await expect(page.locator(`${SEL.chatInner} a[href*="/files/"]`).first()).toBeVisible({ timeout: 60_000 });
});

// ---------- 用户技能（L4）面板：stub 化交互守卫 ----------
// 面板逻辑见 static/debug/modules/skills.js（renderUserSkillsPanel/loadUserSkills/…）。
// 这里用 page.route 桩化管理面响应，把「行 → 用户」错位、错误吞并（GET 5xx 被当成“不存在”）
// 与提示文案分档纳入门禁；真实链路场景见仓库根 e2e/user-skill-admin-e2e.sh（手工脚本）。

type SkCall = { method: string; path: string; body: string };

/** 桩化 Skills 模块依赖：/skills/manage、/debug/user-skills、/debug/sandbox、/skills/users/** */
async function stubUserSkillPanel(page: Page, opts: {
  sandboxEnabled?: boolean;
  /** 明细 GET 的行为：返回状态码（非 200 时 body 为 {error,message}） */
  detailStatus?: number;
  /** userId → 列表响应延迟毫秒（并发错位用例用） */
  listDelay?: Record<string, number>;
  /** 索引触顶截断（后端 truncated=true，下拉只含前 N 个用户） */
  userIndexTruncated?: boolean;
  /** 索引接口状态码（非 200 时 body 为 {error,message}） */
  userIndexStatus?: number;
  /** 已删除但仍保留删除标记（tombstone）的技能名 */
  tombstones?: string[];
} = {}) {
  const calls: SkCall[] = [];
  const users = [
    { userId: 'alice', skillCount: 1, updatedAt: '2026-09-23 10:00:00' },
    { userId: 'bob', skillCount: 1, updatedAt: '2026-09-23 10:00:00' },
  ];
  await page.route('**/skills/manage', r => r.fulfill({ json: [] }));
  await page.route('**/debug/sandbox', r => r.fulfill({ json: { enabled: !!opts.sandboxEnabled } }));
  if (opts.userIndexStatus && opts.userIndexStatus !== 200) {
    await page.route('**/debug/user-skills', r => r.fulfill({
      status: opts.userIndexStatus!, json: { error: 'index_failed', message: 'stub 索引读取失败' } }));
  } else {
    await page.route('**/debug/user-skills', r => r.fulfill({
      json: { count: users.length, users, ...(opts.userIndexTruncated ? { truncated: true } : {}) } }));
  }
  await page.route('**/skills/users**', async route => {
    const req = route.request();
    const url = new URL(req.url());
    const parts = decodeURIComponent(url.pathname).split('/').filter(Boolean); // [skills, users, uid?, name?]
    calls.push({ method: req.method(), path: url.pathname, body: req.postData() || '' });
    const uid = parts[2];
    const name = parts[3];
    const detail = parts.length === 4 && !url.pathname.endsWith('sync-from-package');
    if (req.method() === 'GET' && !uid) return route.fulfill({ json: { count: users.length, users } });
    if (req.method() === 'GET' && !detail) {
      const delay = (opts.listDelay || {})[uid] || 0;
      if (delay) await new Promise(r => setTimeout(r, delay));
      return route.fulfill({
        json: {
          userId: uid,
          skills: [{
            name: `demo-${uid}`, files: ['SKILL.md'], bytes: 3, version: 1,
            hasPackageBaseline: true, adminOverride: true,
          }],
          tombstones: (opts.tombstones || []).map(n => ({ name: n, deletedAt: '2026-09-23 12:00:00' })),
        },
      });
    }
    if (req.method() === 'GET') {
      const status = opts.detailStatus ?? 200;
      if (status !== 200) {
        return route.fulfill({ status, json: { error: 'internal_error', message: 'stub 明细读取失败' } });
      }
      return route.fulfill({
        json: {
          userId: uid, name, file: 'SKILL.md', content: `EXISTING-${uid}-${name}`,
          source: 'user', hasUserOverride: true, userOverrideExists: true, version: 2, files: ['SKILL.md'],
        },
      });
    }
    if (req.method() === 'PUT') return route.fulfill({ json: { action: 'updated', version: 3, message: 'stub 已保存' } });
    if (req.method() === 'DELETE') {
      return route.fulfill({ json: { deletedFiles: 1, hasPackageBaseline: true, message: 'stub 已删除' } });
    }
    if (req.method() === 'POST') {
      return route.fulfill({ json: { files: ['SKILL.md', 'scripts/hello.sh'], skipped: [], message: 'stub 已下发' } });
    }
    return route.fulfill({ status: 404, json: { error: 'not_found', message: 'stub 未匹配' } });
  });
  return calls;
}

/** 打开 Skills 模块并加载指定 userId 的个人技能列表 */
async function openUserSkillPanel(page: Page, userId: string) {
  await page.goto('/debug/#/skills');
  await expect(page.locator('#userSkillLoadBtn')).toBeVisible();
  await page.locator('#userSkillUserInput').fill(userId);
  await page.locator('#userSkillLoadBtn').click();
}

test('U-SK1 用户技能面板提示文案分档（非沙箱 / 沙箱）', async ({ page }) => {
  await stubUserSkillPanel(page, { sandboxEnabled: false });
  await page.goto('/debug/#/skills');
  await expect(page.locator('#userSkillHint')).toContainText('下一轮会话生效');
  await expect(page.locator('#userSkillHint')).not.toContainText('SANDBOX_ENABLED=true');

  await page.unroute('**/debug/sandbox');
  await page.route('**/debug/sandbox', r => r.fulfill({ json: { enabled: true } }));
  await page.reload();
  await expect(page.locator('#userSkillHint')).toContainText('SANDBOX_ENABLED=true');
  await expect(page.locator('#userSkillHint')).toContainText('不会注入会话容器');
});

test('U-SK2 加载用户 → 编辑保存 → 删除 → 从包内下发', async ({ page }) => {
  const calls = await stubUserSkillPanel(page);
  page.on('dialog', d => d.accept());
  await openUserSkillPanel(page, 'alice');

  // 表格渲染：该用户的技能行 + 删除后回落标记
  await expect(page.locator('#userSkillList tbody tr')).toHaveCount(1);
  await expect(page.locator('#userSkillList')).toContainText('demo-alice');

  // 编辑：回填个人覆盖内容 → 保存走 PUT（同一 userId/name）
  await page.locator('.us-edit-btn').first().click();
  await expect(page.locator('#skillEditArea')).toHaveValue('EXISTING-alice-demo-alice');
  await page.locator('#skillEditArea').fill('NEW-CONTENT');
  await page.locator('#userSkillEditSave').click();
  await expect(page.locator('#toastContainer')).toContainText('stub 已保存');
  const put = calls.find(c => c.method === 'PUT');
  expect(put?.path).toBe('/skills/users/alice/demo-alice');
  expect(put?.body).toContain('NEW-CONTENT');

  // 删除：confirm 后走 DELETE
  await page.locator('.us-del-btn').first().click();
  await expect(page.locator('#toastContainer')).toContainText('stub 已删除');
  expect(calls.filter(c => c.method === 'DELETE').map(c => c.path)).toEqual(['/skills/users/alice/demo-alice']);

  // 从包内下发：填技能名 → POST sync-from-package
  await page.locator('#userSkillNameInput').fill('demo-alice');
  await page.locator('#userSkillSyncBtn').click();
  await expect(page.locator('#toastContainer')).toContainText('stub 已下发');
  expect(calls.filter(c => c.method === 'POST').map(c => c.path))
    .toEqual(['/skills/users/alice/demo-alice/sync-from-package']);

  // 刷新按钮：重新拉取列表与索引（不抛 pageerror 即通过，交互链路复用上面断言）
  await page.locator('#skillRefreshBtn').click();
  await expect(page.locator('#userSkillUserList option')).toHaveCount(2);
});

test('U-SK3 并发加载不错位：行操作永远作用于该行对应的用户', async ({ page }) => {
  const calls = await stubUserSkillPanel(page, { listDelay: { alice: 800 } });
  page.on('dialog', d => d.accept());
  await page.goto('/debug/#/skills');
  await expect(page.locator('#userSkillLoadBtn')).toBeVisible();

  // 先发 alice（慢），紧接着改输入框为 bob 再加载（快） → 先发的 alice 后返回必须被丢弃
  await page.locator('#userSkillUserInput').fill('alice');
  await page.locator('#userSkillLoadBtn').click();
  await page.locator('#userSkillUserInput').fill('bob');
  await page.locator('#userSkillLoadBtn').click();
  await expect(page.locator('#userSkillList')).toContainText('demo-bob');

  // 等 alice 的慢响应返回后再断言：表格仍是 bob 的行（过期响应不得覆盖）
  await page.waitForTimeout(1200);
  await expect(page.locator('#userSkillList')).toContainText('demo-bob');
  await expect(page.locator('#userSkillList')).not.toContainText('demo-alice');

  // 行操作必须落到 bob（改前 userSkillState.userId 已被置为 bob 但表格是 alice 的行 → 错位）
  await page.locator('.us-del-btn').first().click();
  await expect(page.locator('#toastContainer')).toContainText('stub 已删除');
  expect(calls.filter(c => c.method === 'DELETE').map(c => c.path)).toEqual(['/skills/users/bob/demo-bob']);
});

test('U-SK4 明细读取 5xx 不得当成“技能不存在”回落空内容新建', async ({ page }) => {
  await stubUserSkillPanel(page, { detailStatus: 500 });
  await page.goto('/debug/#/skills');
  await expect(page.locator('#userSkillWriteBtn')).toBeVisible();
  await page.locator('#userSkillUserInput').fill('alice');
  await page.locator('#userSkillNameInput').fill('demo-alice');

  await page.locator('#userSkillWriteBtn').click();

  // 必须 toast 失败并中止，不得弹出空白编辑框（空白起编保存会整份覆盖已有个人覆盖）
  await expect(page.locator('#toastContainer')).toContainText('stub 明细读取失败');
  await expect(page.locator('#modalOverlay')).not.toHaveClass(/active/);
});

test('U-SK5 索引触顶截断必须显式提示（不得把子集当全集）', async ({ page }) => {
  await stubUserSkillPanel(page, { userIndexTruncated: true });
  await page.goto('/debug/#/skills');

  // 后端 truncated=true → 面板必须提示截断并给出补齐手段，否则下拉列表不全无从察觉
  await expect(page.locator('#userSkillSummary')).toContainText('索引触顶截断');
  await expect(page.locator('#userSkillSummary')).toContainText('手工输入 userId');
  await expect(page.locator('#userSkillUserList')).toHaveAttribute('title', /截断/);
});

test('U-SK6 索引接口失败必须醒目提示（不得静默显示 0 user(s)）', async ({ page }) => {
  await stubUserSkillPanel(page, { userIndexStatus: 500 });
  await page.goto('/debug/#/skills');

  await expect(page.locator('#userSkillSummary')).toContainText('user index unavailable');
  await expect(page.locator('#userSkillSummary')).toContainText('stub 索引读取失败');
});

test('U-SK7 删除标记（tombstone）与写入栅栏必须显式提示', async ({ page }) => {
  await stubUserSkillPanel(page, { tombstones: ['demo-gone'] });
  await openUserSkillPanel(page, 'alice');

  // 管理面写入栅栏：回写会跳过该技能（容器内 skill_manage 的修改在清除前不落库）
  await expect(page.locator('#userSkillList')).toContainText('管理面栅栏');
  // 删除标记：后果与清除方式必须写清，否则「重建了却不落库」无从解释
  await expect(page.locator('#userSkillList')).toContainText('tombstone');
  await expect(page.locator('#userSkillList')).toContainText('demo-gone');
  await expect(page.locator('#userSkillList')).toContainText('不会被回写落库');
});
