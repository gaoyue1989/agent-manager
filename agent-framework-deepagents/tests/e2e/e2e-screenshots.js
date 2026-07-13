const puppeteer = require('puppeteer');
const path = require('path');

const BASE = 'http://localhost:8100';
const OUTPUT_DIR = path.join(__dirname, '..', '..', '..', 'e2e', 'screenshots');

async function screenshot(name, page, options = {}) {
  const filePath = path.join(OUTPUT_DIR, `${name}.png`);
  await page.screenshot({ path: filePath, fullPage: options.fullPage || false });
  console.log(`Screenshot: ${filePath}`);
  return filePath;
}

async function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

(async () => {
  const browser = await puppeteer.launch({
    headless: 'new',
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage', '--window-size=1440,900'],
  });

  try {
    const page = await browser.newPage();
    await page.setViewport({ width: 1440, height: 900 });

    // 1. 打开 debug 页面
    console.log('\n=== 1. 打开 Debug 页面 ===');
    await page.goto(`${BASE}/debug`, { waitUntil: 'networkidle0' });
    await sleep(2000);
    await screenshot('01-debug-page', page);

    // 2. 验证 Skills 展示
    console.log('\n=== 2. 验证 Skills 展示 ===');
    await screenshot('02-skills-display', page);

    // 3. 点击 System Prompt 按钮
    console.log('\n=== 3. 验证 System Prompt 展示 ===');
    const sysPromptBtn = await page.$('.sysprompt-btn');
    if (sysPromptBtn) {
      await sysPromptBtn.click();
      await sleep(1000);
      await screenshot('03-system-prompt-modal', page);
      await page.evaluate(() => {
        document.getElementById('modalOverlay').classList.remove('active');
      });
      await sleep(500);
    }

    // 4. 发送对话消息
    console.log('\n=== 4. 发送对话消息 ===');
    await page.type('#userInput', 'Hello, what tools do you have?');
    await sleep(500);
    await page.click('#sendBtn');
    console.log('Waiting for response...');
    await sleep(15000);
    await screenshot('04-conversation-response', page);

    // 5. 点击 LLM Calls 按钮
    console.log('\n=== 5. 验证 LLM Calls 展示 ===');
    await sleep(1000);
    const threadItem = await page.$('.thread-item');
    if (threadItem) {
      await threadItem.click();
      await sleep(1000);
    }
    const llmBtn = await page.$('#llmBtn');
    if (llmBtn) {
      await llmBtn.click();
      await sleep(1500);
      await screenshot('05-llm-calls-modal', page);
    }

    // 6. 展开第一个 LLM 调用查看详情
    console.log('\n=== 6. 展开 LLM 调用详情 ===');
    await page.evaluate(() => {
      const entry = document.getElementById('llm-entry-0');
      if (entry) entry.classList.add('open');
    });
    await sleep(1000);
    await screenshot('06-llm-call-expanded', page);

    // 7. 发送带 tool call 的消息
    console.log('\n=== 7. 发送带 tool call 的消息 ===');
    await page.evaluate(() => {
      document.getElementById('modalOverlay').classList.remove('active');
    });
    await sleep(500);
    await page.type('#userInput', 'Please run: echo "test123"');
    await sleep(500);
    await page.click('#sendBtn');
    console.log('Waiting for tool call response...');
    await sleep(20000);
    await screenshot('07-conversation-with-tool', page);

    // 8. 查看多轮 LLM Calls
    console.log('\n=== 8. 查看多轮 LLM Calls ===');
    await sleep(1000);
    const llmBtn2 = await page.$('#llmBtn');
    if (llmBtn2) {
      await llmBtn2.click();
      await sleep(1500);
      await screenshot('08-llm-calls-multi-turn', page);
    }

    // 9. 展开最后一个 LLM 调用（包含 tool call）
    console.log('\n=== 9. 展开包含 tool call 的 LLM 调用 ===');
    await page.evaluate(() => {
      const entries = document.querySelectorAll('.llm-call-entry');
      const lastEntry = entries[entries.length - 1];
      if (lastEntry) lastEntry.classList.add('open');
    });
    await sleep(1000);
    await screenshot('09-llm-call-with-tool', page);

    console.log('\n=== 所有截图完成 ===');

  } catch (err) {
    console.error('Error:', err.message);
    console.error(err.stack);
  } finally {
    await browser.close();
  }
})();
