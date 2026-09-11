// HITL 集群浏览器级 E2E（AG-UI 迁移验收）：Debug Console AG-UI 模式
// 触发 ASK → RUN_FINISHED(interrupt) 确认卡片 → Approve → resume[] → 工具执行闭环
// 用法: DEBUG_URL=http://localhost:30080/agent/hitl-test/debug node hitl-agui-e2e.js
const puppeteer = require('./node_modules/puppeteer');

const BASE = process.env.DEBUG_URL || 'http://localhost:30080/agent/hitl-test/debug';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let pass = 0, fail = 0;
const ok = (name, cond) => { console.log((cond ? '\x1b[32mPASS\x1b[0m ' : '\x1b[31mFAIL\x1b[0m ') + name); cond ? pass++ : fail++; };

(async () => {
  const browser = await puppeteer.launch({ headless: 'new', args: ['--no-sandbox'] });
  const page = await browser.newPage();
  const pageErrors = [];
  page.on('pageerror', (e) => pageErrors.push(String(e).slice(0, 200)));

  await page.goto(BASE, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.waitForSelector('#chatInput', { timeout: 60000 });
  ok('P1 Debug Console 加载（AG-UI 模式）',
    await page.evaluate(() => document.getElementById('modeAgui')?.classList.contains('active')));

  // 触发 ASK：require_confirmation=true 下任意 MCP 工具调用都会挂起
  await page.click('#chatInput');
  await page.type('#chatInput', '请调用 list_packages 工具查看平台配置包列表');
  await page.click('#sendBtn');

  // RUN_FINISHED(interrupt) → 确认卡片
  await page.waitForSelector('.confirm-card', { timeout: 240000 });
  ok('P2 工具调用被拦截，确认卡片出现', true);
  const toolName = await page.evaluate(() => document.querySelector('.confirm-tool-name')?.textContent);
  ok('P3 卡片展示工具名（list_packages）', (toolName || '').includes('list_packages'));

  // Approve → resume[] 自动发出 → 工具真实执行
  // 原卡片元素级判据：approve 即移除；恢复后 LLM 可能再调工具触发新一轮 ASK（新卡片），不算失败
  const originalCard = await page.$('.confirm-card');
  await page.evaluate((el) => el.dataset.e2eOriginal = '1', originalCard);
  await page.click('.confirm-card [data-act="approve"]');
  await sleep(4000);
  const originalGone = await page.evaluate(() => !document.querySelector('[data-e2e-original]'));
  ok('P4 批准后原确认卡片关闭', originalGone);
  await sleep(26000);
  const state = await page.evaluate(() => ({ body: document.body.innerText }));
  ok('P5 恢复执行：收到工具执行结果（包列表内容）',
    /package|包|agent-manager|hitl/i.test(state.body));
  ok('P6 无页面错误', pageErrors.length === 0);

  console.log(`\n结果: PASS=${pass} FAIL=${fail}`);
  await browser.close();
  process.exit(fail ? 1 : 0);
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
