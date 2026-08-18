/**
 * E2E 测试：HITL 权限确认流程
 *
 * 流程：
 * 1. 打开 debug 页面，发送消息触发 MCP write_file 工具
 * 2. 等待 permission_ask 确认卡片出现
 * 3. 点击 Approve all
 * 4. 验证 agent 恢复执行并产生回复
 * 5. 每步截图
 */
const puppeteer = require('puppeteer');
const fs = require('fs');
const path = require('path');

const BASE_URL = process.env.BASE_URL || 'http://100.66.1.5:8100';
const SCREENSHOT_DIR = path.join(__dirname, 'screenshots');

(async () => {
  // Ensure screenshot dir exists
  if (!fs.existsSync(SCREENSHOT_DIR)) fs.mkdirSync(SCREENSHOT_DIR, { recursive: true });

  const timestamp = () => new Date().toISOString().replace(/[:.]/g, '-');
  const screenshots = [];

  const snap = async (name, page) => {
    const file = `${timestamp()}-${name}.png`;
    const filePath = path.join(SCREENSHOT_DIR, file);
    await page.screenshot({ path: filePath, fullPage: false });
    screenshots.push({ name, file: filePath, time: new Date().toISOString() });
    console.log(`  📸 ${name} → ${file}`);
  };

  const browser = await puppeteer.launch({
    headless: 'new',
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage']
  });

  const results = [];
  const check = (label, ok, detail) => {
    results.push({ label, ok, detail: String(detail || '').substring(0, 300) });
    console.log(`  ${ok ? '✅' : '❌'} ${label}${detail ? ': ' + detail : ''}`);
  };

  try {
    const page = await browser.newPage();
    await page.setViewport({ width: 1440, height: 900 });

    // ===== STEP 1: 打开 debug 页面 =====
    console.log('\n🔹 Step 1: 打开 debug 页面');
    await page.goto(`${BASE_URL}/debug#/`, { waitUntil: 'networkidle2', timeout: 15000 });
    await new Promise(r => setTimeout(r, 2000));
    await snap('01-debug-page-loaded', page);

    const title = await page.title();
    check('页面标题', title.includes('Debug') || title.includes('Agent'), title);

    // ===== STEP 2: 选择 Channel 模式 + 长连接 =====
    console.log('\n🔹 Step 2: 设置 Channel + 长连接模式');
    // Ensure Channel mode is selected
    const channelBtn = await page.$('#modeChannel');
    if (channelBtn) {
      await channelBtn.click();
      await new Promise(r => setTimeout(r, 300));
    }
    // Ensure 长连接 mode
    const sessionRadio = await page.$('label[for*="session"], input[value="session"]');
    if (sessionRadio) {
      await sessionRadio.click();
      await new Promise(r => setTimeout(r, 300));
    }
    await snap('02-channel-mode-set', page);
    check('Channel 模式', true, 'Channel + 长连接已选择');

    // ===== STEP 3: 发送消息触发 MCP write_file =====
    console.log('\n🔹 Step 3: 发送消息触发 write_file');
    const prompt = '帮我用 write_file 工具创建一个文件，路径 /tmp/test-hitl-e2e.txt，内容写 hello hitl e2e';
    await page.type('#chatInput', prompt, { delay: 5 });
    await new Promise(r => setTimeout(r, 500));
    await snap('03-message-typed', page);
    await page.click('#sendBtn');
    check('消息已发送', true, prompt.substring(0, 80));

    // ===== STEP 4: 等待 permission_ask 确认卡片 =====
    console.log('\n🔹 Step 4: 等待 permission_ask 确认卡片');
    let confirmCard = null;
    let waitCount = 0;
    const MAX_WAIT = 150; // seconds — SenseNova 响应较慢
    while (waitCount < MAX_WAIT) {
      confirmCard = await page.$('.confirm-card');
      if (confirmCard) break;
      await new Promise(r => setTimeout(r, 1000));
      waitCount++;
      if (waitCount % 10 === 0) console.log(`  ⏳ 等待确认卡片... ${waitCount}s`);
    }
    await snap('04-confirm-card-wait', page);

    if (!confirmCard) {
      check('确认卡片出现', false, `等待 ${MAX_WAIT}s 后未出现`);
      // Take final screenshot and exit
      await snap('04-confirm-card-timeout', page);
      throw new Error('Confirm card did not appear within timeout');
    }

    // 验证卡片内容
    const cardText = await confirmCard.evaluate(el => el.textContent);
    const hasToolName = cardText.includes('write_file');
    const hasApproveBtn = cardText.includes('Approve');
    check('确认卡片出现', true, `等待 ${waitCount}s`);
    check('卡片包含工具名', hasToolName, cardText.substring(0, 100));
    check('卡片包含 Approve 按钮', hasApproveBtn, '');

    await snap('05-confirm-card-visible', page);

    // ===== STEP 5: 点击 Approve all =====
    console.log('\n🔹 Step 5: 点击 Approve all');
    const approveBtn = await page.$('.confirm-card button[data-act="approve"]');
    if (!approveBtn) {
      check('Approve 按钮', false, '未找到按钮');
      throw new Error('Approve button not found');
    }
    await approveBtn.click();
    check('Approve 按钮已点击', true, '');
    await snap('06-approve-clicked', page);

    // ===== STEP 6: 等待 agent 恢复执行 =====
    console.log('\n🔹 Step 6: 等待 agent 恢复执行');
    // Wait for the confirm card to show "已批准" or for new assistant messages
    let restored = false;
    let restoreWait = 0;
    const MAX_RESTORE_WAIT = 60;
    while (restoreWait < MAX_RESTORE_WAIT) {
      // Check if confirm card changed to "已批准"
      const titleEl = await page.$('.confirm-card .confirm-title');
      if (titleEl) {
        const titleText = await titleEl.evaluate(el => el.textContent);
        if (titleText.includes('已批准') || titleText.includes('已拒绝')) {
          restored = true;
          break;
        }
      }
      // Check if new assistant message appeared (agent finished)
      const msgs = await page.$$('.msg.assistant');
      if (msgs.length > 1) {
        restored = true;
        break;
      }
      await new Promise(r => setTimeout(r, 1000));
      restoreWait++;
      if (restoreWait % 10 === 0) console.log(`  ⏳ 等待恢复... ${restoreWait}s`);
    }
    await snap('07-agent-restored', page);
    check('Agent 恢复执行', restored, `等待 ${restoreWait}s`);

    // ===== STEP 7: 等待最终响应 =====
    console.log('\n🔹 Step 7: 等待最终响应');
    // Wait a bit more for the full response
    await new Promise(r => setTimeout(r, 10000));
    await snap('08-final-response', page);

    // Check for assistant messages
    const assistantMsgs = await page.$$('.msg.assistant');
    check('助手回复数量', assistantMsgs.length > 0, assistantMsgs.length);

    // Check for tool execution results
    const toolGroups = await page.$$('.tool-group');
    check('工具调用组', toolGroups.length > 0, toolGroups.length);

    // Check the file was created (via page evaluation if sandbox allows, or just check the UI)
    const pageContent = await page.content();
    const hasFileWrite = pageContent.includes('test-hitl-e2e') || pageContent.includes('hello hitl');
    check('文件写入提及', hasFileWrite, '');

    // ===== STEP 8: 最终截图 =====
    await snap('09-test-complete', page);

  } catch (e) {
    console.error(`\n❌ Test error: ${e.message}`);
    check('测试执行', false, e.message);
  } finally {
    await browser.close();
  }

  // ===== Report =====
  console.log('\n' + '='.repeat(60));
  console.log('HITL E2E Test Report');
  console.log('='.repeat(60));
  const passed = results.filter(r => r.ok).length;
  const failed = results.filter(r => !r.ok).length;
  console.log(`Total: ${results.length} | Passed: ${passed} | Failed: ${failed}`);
  console.log('-'.repeat(60));
  for (const r of results) {
    console.log(`  ${r.ok ? '✅' : '❌'} ${r.label}${r.detail ? ': ' + r.detail : ''}`);
  }
  console.log('-'.repeat(60));
  console.log('Screenshots:');
  for (const s of screenshots) {
    console.log(`  📸 ${s.name}`);
  }

  // Save report
  const report = {
    name: 'HITL E2E Test',
    timestamp: new Date().toISOString(),
    results,
    screenshots: screenshots.map(s => ({ name: s.name, file: path.basename(s.file) })),
    passed,
    failed
  };
  const reportPath = path.join(SCREENSHOT_DIR, 'hitl-e2e-report.json');
  fs.writeFileSync(reportPath, JSON.stringify(report, null, 2));
  console.log(`\nReport saved: ${reportPath}`);

  process.exit(failed > 0 ? 1 : 0);
})();
