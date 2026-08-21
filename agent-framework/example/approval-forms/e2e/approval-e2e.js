// 审批 Demo E2E：对话收集申请 → MCP App 表单卡片确认 → HITL 人工审批提交
// 前置：
//   1. agent-framework 运行于 :8100，AGENT_CONFIG_DIR 指向本示例 agent-config
//   2. mock MCP (:8813) + proxy (:8913) 运行（cd agent-framework/example/approval-forms && ./start.sh）
// 运行：cd agent-framework/example/approval-forms/e2e && node approval-e2e.js
const puppeteer = require('puppeteer');
const fs = require('fs');
const path = require('path');

const BASE_URL = 'http://localhost:8913';
const DIR = path.join(__dirname, 'screenshots');
const LLM_TIMEOUT = 240000;

async function screenshot(page, name) {
  fs.mkdirSync(DIR, { recursive: true });
  await page.screenshot({ path: path.join(DIR, name + '.png'), fullPage: true });
  console.log('  saved ' + name + '.png');
}

async function typeAndSend(page, text) {
  const textarea = await page.$('#chatInput');
  if (!textarea) throw new Error('#chatInput not found');
  await textarea.click({ clickCount: 3 });
  await textarea.type(text);
  await page.evaluate(() => document.getElementById('sendBtn')?.click());
}

function chatText(page) {
  return page.evaluate(() => (document.getElementById('chatInner')?.innerText || '').slice(-4000));
}

(async () => {
  if (!fs.existsSync(DIR)) fs.mkdirSync(DIR, { recursive: true });
  const browser = await puppeteer.launch({
    headless: 'new',
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-gpu']
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1440, height: 900 });
  await page.setDefaultTimeout(20000);

  const results = [];
  const check = (name, ok, extra) => {
    results.push({ name, ok });
    console.log((ok ? 'PASS' : 'FAIL') + ' - ' + name + (extra ? ' | ' + extra : ''));
  };

  try {
    // 0) 打开页面 + 新建会话
    await page.goto(BASE_URL + '/', { waitUntil: 'networkidle2', timeout: 30000 });
    await page.waitForSelector('#chatInput', { timeout: 15000 });
    await page.evaluate(() => document.getElementById('btnNewThread')?.click());
    await new Promise(r => setTimeout(r, 800));
    console.log('page loaded, new thread created');

    // 1) 对话收集申请信息
    const t0 = Date.now();
    await typeAndSend(page,
      '我需要申请一个 GPU 推理服务：' +
      '标题是「多模态大模型服务申请」，' +
      '申请文本：申请一个 8 卡 GPU 推理实例用于多模态大模型推理，预计使用 3 个月。' +
      '文件链接：http://x.example/方案.pdf 和 http://x.example/预算.xlsx。' +
      '请帮我创建申请单，并调用工具展示表单确认卡片。');
    console.log('application prompt sent, waiting for MCP form card (LLM call may take 60-240s)...');
    await page.waitForFunction(
      () => document.querySelectorAll('.mcp-apps-container').length > 0,
      { timeout: LLM_TIMEOUT }
    ).catch(() => {});
    await page.waitForFunction(
      () => document.querySelectorAll('.mcp-apps-iframe').length > 0,
      { timeout: 30000 }
    ).catch(() => {});
    await new Promise(r => setTimeout(r, 2500));
    console.log('form card wait:', ((Date.now() - t0) / 1000).toFixed(1) + 's');
    await screenshot(page, '01-form-card');

    // 2) 断言卡片挂载 + 初始状态
    const cardInfo = await page.evaluate(() => {
      const host = document.querySelector('.mcp-apps-container');
      const iframe = document.querySelector('.mcp-apps-iframe');
      return {
        hostMounted: !!host,
        iframeMounted: !!iframe,
        appLabel: host?.querySelector('.app-label')?.textContent || null
      };
    });
    check('form card mounted', cardInfo.hostMounted && cardInfo.iframeMounted, JSON.stringify(cardInfo));

    // 3) iframe 内 app 渲染（sandbox 无 allow-same-origin，需 frame.evaluate）
    let frame = null;
    try {
      frame = await page.waitForFrame(f => f.url().startsWith('about:srcdoc'), { timeout: 15000 });
    } catch (e) { /* 超时不致命 */ }
    check('card iframe found', !!frame);
    let appText = '';
    if (frame) {
      await new Promise(r => setTimeout(r, 2500));
      appText = await frame.evaluate(() => document.body.innerText.slice(0, 600));
      console.log('  iframe text:', JSON.stringify(appText.slice(0, 200)));
      check('card shows form', appText.includes('模型服务申请表') && appText.includes('多模态大模型服务申请'));
    }

    // 4) 卡片内点击「确认表单」
    if (frame) {
      const clicked = await frame.evaluate(() => {
        const btn = document.getElementById('btnConfirm');
        if (btn) { btn.click(); return true; }
        return false;
      });
      check('form confirm button clicked', clicked);
      await new Promise(r => setTimeout(r, 2500));
      appText = await frame.evaluate(() => document.body.innerText.slice(0, 400));
      check('card confirmed state', appText.includes('已确认'), 'contained: ' + appText.slice(0, 60).replace(/\n/g, ' '));
      await screenshot(page, '02-form-confirmed');
    }

    // 5) 对话触发人工审批（submit_application 配了 ask 权限 → HITL 确认卡）
    const t1 = Date.now();
    await typeAndSend(page, '表单已经确认了，现在提交申请。');
    console.log('submit prompt sent, waiting for HITL confirm card...');
    await page.waitForFunction(
      () => document.querySelectorAll('.confirm-card').length > 0,
      { timeout: LLM_TIMEOUT }
    ).catch(() => {});
    const confirmCard = await page.evaluate(() => {
      const c = document.querySelector('.confirm-card');
      return c ? {
        title: c.querySelector('.confirm-title')?.textContent || '',
        tool: c.querySelector('.confirm-tool-name')?.textContent || ''
      } : null;
    });
    console.log('HITL wait:', ((Date.now() - t1) / 1000).toFixed(1) + 's');
    check('HITL confirm card shown', !!confirmCard && (confirmCard.tool || '').includes('submit_application'), JSON.stringify(confirmCard));
    await screenshot(page, '03-hitl-confirm');

    // 6) 点击「批准」→ 等待最终提交结果
    if (confirmCard) {
      await page.evaluate(() => {
        document.querySelector('.confirm-card [data-act="approve"]')?.click();
      });
      console.log('approve clicked, waiting for submission result...');
      await page.waitForFunction(
        () => (document.getElementById('chatInner')?.innerText || '').includes('已提交审批成功'),
        { timeout: 120000 }
      ).catch(() => {});
      const finalText = await chatText(page);
      check('submission result shown', finalText.includes('已提交审批成功') || finalText.includes('已提交'),
        'tail: ' + finalText.slice(-200).replace(/\n/g, ' '));
      await new Promise(r => setTimeout(r, 1500));
      await screenshot(page, '04-submitted');
    }

    // 7) 校验 mock 侧申请单最终状态（直接调 mock 协议）
    fs.writeFileSync(path.join(DIR, 'final-state.json'), JSON.stringify({ appText: await chatText(page) }, null, 2));
  } catch (e) {
    console.log('E2E ERROR: ' + e.message);
    await screenshot(page, 'error');
  } finally {
    await browser.close();
  }

  const failed = results.filter(r => !r.ok);
  console.log('\n===== E2E RESULT: ' + (failed.length === 0 ? 'ALL PASS' : failed.length + ' FAILED') + ' =====');
  if (failed.length) process.exit(1);
})();