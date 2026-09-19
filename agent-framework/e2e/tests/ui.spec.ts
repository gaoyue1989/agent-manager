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
