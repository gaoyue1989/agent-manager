/**
 * E2E 测试：三种模式 HITL 权限确认流程
 *
 * 模式：
 * 1. A2A 长连接（SSE 标准帧）
 * 2. Channel 单次流（/chat/stream）
 * 3. Channel 长连接（/events + /chat fire-and-forget）
 *
 * 每种模式：发送触发 write_file → 等待确认卡片 → Approve → 验证恢复
 */
const puppeteer = require('puppeteer');
const fs = require('fs');
const path = require('path');

const BASE_URL = process.env.BASE_URL || 'http://100.66.1.5:8100';
const SCREENSHOT_DIR = path.join(__dirname, 'screenshots');

(async () => {
  if (!fs.existsSync(SCREENSHOT_DIR)) fs.mkdirSync(SCREENSHOT_DIR, { recursive: true });

  const ts = () => new Date().toISOString().replace(/[:.]/g, '-');
  const screenshots = [];
  const snap = async (name, page) => {
    const file = `${ts()}-${name}.png`;
    await page.screenshot({ path: path.join(SCREENSHOT_DIR, file), fullPage: false });
    screenshots.push({ name, file });
    console.log(`  📸 ${name}`);
  };

  const browser = await puppeteer.launch({
    headless: 'new',
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage']
  });

  const results = [];
  const check = (label, ok, detail) => {
    results.push({ label, ok, detail: String(detail || '').substring(0, 200) });
    console.log(`  ${ok ? '✅' : '❌'} ${label}${detail ? ': ' + detail : ''}`);
  };

  const PROMPT = '帮我用 write_file 工具创建一个文件，路径 /tmp/test-hitl-modes.txt，内容写 hello hitl modes';

  async function runModeTest(modeName, modeConfig) {
    console.log(`\n${'='.repeat(60)}`);
    console.log(`🔹 模式: ${modeName}`);
    console.log('='.repeat(60));

    const page = await browser.newPage();
    await page.setViewport({ width: 1440, height: 900 });

    try {
      // 1. 打开页面
      await page.goto(`${BASE_URL}/debug#/`, { waitUntil: 'networkidle2', timeout: 15000 });
      await new Promise(r => setTimeout(r, 2000));

      // 2. 选择模式
      console.log(`  设置模式: ${modeName}`);
      if (modeConfig.mode === 'a2a') {
        await page.click('#modeA2A');
      } else {
        await page.click('#modeChannel');
        await new Promise(r => setTimeout(r, 300));
        if (modeConfig.conn === 'single') {
          await page.click('#connSingle');
        } else {
          await page.click('#connSession');
        }
      }
      await new Promise(r => setTimeout(r, 500));
      await snap(`${modeName}-01-mode-set`, page);
      check(`${modeName} 模式已选择`, true);

      // 3. 输入消息
      await page.type('#chatInput', PROMPT, { delay: 5 });
      await new Promise(r => setTimeout(r, 300));
      await snap(`${modeName}-02-message-typed`, page);

      // 4. 发送
      console.log('  发送消息...');
      await page.click('#sendBtn');

      // 5. 等待确认卡片
      console.log('  等待确认卡片...');
      let confirmCard = null;
      for (let i = 0; i < 120; i++) {
        confirmCard = await page.$('.confirm-card');
        if (confirmCard) break;
        await new Promise(r => setTimeout(r, 1000));
        if (i % 15 === 0 && i > 0) console.log(`  ⏳ ${i}s...`);
      }
      await snap(`${modeName}-03-after-wait`, page);

      if (!confirmCard) {
        check(`${modeName} 确认卡片出现`, false, '120s 超时');
        return;
      }

      const cardText = await confirmCard.evaluate(el => el.textContent);
      check(`${modeName} 确认卡片出现`, true, cardText.substring(0, 80));
      await snap(`${modeName}-04-confirm-card`, page);

      // 6. 点击 Approve
      console.log('  点击 Approve...');
      const approveBtn = await page.$('.confirm-card button[data-act="approve"]');
      if (approveBtn) {
        await approveBtn.click();
        check(`${modeName} Approve 已点击`, true);
      } else {
        check(`${modeName} Approve 按钮`, false, '未找到');
        return;
      }
      await snap(`${modeName}-05-approved`, page);

      // 7. 等待恢复
      console.log('  等待 agent 恢复...');
      let restored = false;
      for (let i = 0; i < 60; i++) {
        // 检查确认卡片状态变化
        const titleEl = await page.$('.confirm-card .confirm-title');
        if (titleEl) {
          const t = await titleEl.evaluate(el => el.textContent);
          if (t.includes('已批准') || t.includes('已拒绝')) {
            restored = true;
            break;
          }
        }
        // 检查新助手消息
        const msgs = await page.$$('.msg.assistant');
        if (msgs.length > 1) {
          restored = true;
          break;
        }
        await new Promise(r => setTimeout(r, 1000));
        if (i % 10 === 0 && i > 0) console.log(`  ⏳ 恢复等待 ${i}s...`);
      }
      await snap(`${modeName}-06-restored`, page);
      check(`${modeName} Agent 恢复执行`, restored);

      // 8. 等待最终响应
      await new Promise(r => setTimeout(r, 10000));
      await snap(`${modeName}-07-final`, page);

      const assistantMsgs = await page.$$('.msg.assistant');
      const toolGroups = await page.$$('.tool-group');
      check(`${modeName} 助手回复`, assistantMsgs.length > 0, `${assistantMsgs.length} 条`);
      check(`${modeName} 工具调用`, toolGroups.length > 0, `${toolGroups.length} 组`);

    } catch (e) {
      console.error(`  ❌ ${modeName} 错误: ${e.message}`);
      check(`${modeName} 执行`, false, e.message);
      await snap(`${modeName}-error`, page);
    } finally {
      await page.close();
    }
  }

  try {
    // 三种模式依次测试
    await runModeTest('A2A', { mode: 'a2a' });
    await new Promise(r => setTimeout(r, 3000));

    await runModeTest('Channel-单次流', { mode: 'channel', conn: 'single' });
    await new Promise(r => setTimeout(r, 3000));

    await runModeTest('Channel-长连接', { mode: 'channel', conn: 'session' });

  } finally {
    await browser.close();
  }

  // 报告
  console.log('\n' + '='.repeat(60));
  console.log('HITL 三模式 E2E 测试报告');
  console.log('='.repeat(60));
  const passed = results.filter(r => r.ok).length;
  const failed = results.filter(r => !r.ok).length;
  console.log(`Total: ${results.length} | Passed: ${passed} | Failed: ${failed}`);
  console.log('-'.repeat(60));
  for (const r of results) {
    console.log(`  ${r.ok ? '✅' : '❌'} ${r.label}${r.detail ? ': ' + r.detail : ''}`);
  }
  console.log('-'.repeat(60));
  console.log(`Screenshots: ${screenshots.length}`);

  const report = {
    name: 'HITL Three-Mode E2E Test',
    timestamp: new Date().toISOString(),
    results,
    screenshots: screenshots.map(s => s.name),
    passed, failed
  };
  fs.writeFileSync(path.join(SCREENSHOT_DIR, 'hitl-modes-report.json'), JSON.stringify(report, null, 2));

  process.exit(failed > 0 ? 1 : 0);
})();
