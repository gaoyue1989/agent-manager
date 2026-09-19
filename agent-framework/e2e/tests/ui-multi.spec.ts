/** e2e-multi UI 组：经 nginx 轮询 LB 的多副本页面冒烟（U9） */
import { test, expect } from '@playwright/test';
import { SEL } from '../lib/selectors.js';

const U = 'e2e-ui-multi';

test('U9 多副本 LB 页面冒烟与刷新续传', async ({ page }) => {
  test.setTimeout(240_000);
  await page.goto('/debug/');
  await page.locator(SEL.uidInput).fill(U);
  await page.locator(SEL.chatInput).fill('[E2E:slow](lb,500,10)');
  const btn = page.locator(SEL.sendBtn);
  await expect(btn).toBeEnabled();
  await btn.click();
  await page.waitForTimeout(3000); // 首批气泡渲染（working 态，且已随机命中某副本）
  await page.reload();             // 刷新 → 经 LB 随机路由到任一副本续传
  await expect(page.locator(SEL.sendBtn)).toBeEnabled({ timeout: 200_000 });
  await expect(page.locator(SEL.chatInner)).toContainText(/./);
});
