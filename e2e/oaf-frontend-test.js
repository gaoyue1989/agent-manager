const puppeteer = require('puppeteer');

const BASE_URL = process.env.BASE_URL || 'http://localhost:8911';

async function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

function log(name, status, desc = '') {
  console.log(`[${status === 'PASS' ? '✓' : '✗'}] ${name}${desc ? ': ' + desc : ''}`);
}

async function main() {
  console.log('=== OAF Frontend E2E Tests ===\n');

  const browser = await puppeteer.launch({ headless: 'new' });
  const page = await browser.newPage();

  try {
    // Test 1: Navigate to create page
    console.log('\n--- Test 1: Navigate to create page ---');
    await page.goto(`${BASE_URL}/agents/create`, { waitUntil: 'networkidle0' });
    await sleep(2000);
    
    const title = await page.title();
    log('Navigate to create page', 'PASS', title || 'loaded');

    // Test 2: Check form mode elements
    console.log('\n--- Test 2: Check form mode elements ---');
    const content = await page.content();
    
    if (content.includes('身份标识') || content.includes('Identity')) {
      log('Identity section visible', 'PASS');
    } else {
      log('Identity section visible', 'FAIL');
    }
    
    if (content.includes('元数据') || content.includes('Metadata')) {
      log('Metadata section visible', 'PASS');
    } else {
      log('Metadata section visible', 'FAIL');
    }
    
    if (content.includes('技能') || content.includes('Skills')) {
      log('Skills section visible', 'PASS');
    } else {
      log('Skills section visible', 'FAIL');
    }
    
    if (content.includes('MCP')) {
      log('MCP section visible', 'PASS');
    } else {
      log('MCP section visible', 'FAIL');
    }
    
    if (content.includes('工具') || content.includes('Tools')) {
      log('Tools section visible', 'PASS');
    } else {
      log('Tools section visible', 'FAIL');
    }

    // Test 3: Fill identity fields
    console.log('\n--- Test 3: Fill identity fields ---');
    try {
      await page.type('input[placeholder="My Agent"]', 'E2E Test Agent');
      log('Fill name field', 'PASS');
    } catch (e) {
      log('Fill name field', 'FAIL', e.message);
    }
    
    try {
      const vendorInputs = await page.$$('input');
      for (const input of vendorInputs) {
        const placeholder = await input.getProperty('placeholder').then(p => p.jsonValue());
        if (placeholder === 'mycompany') {
          await input.type('e2etest');
          log('Fill vendorKey field', 'PASS');
          break;
        }
      }
    } catch (e) {
      log('Fill vendorKey field', 'FAIL', e.message);
    }
    
    try {
      const agentInputs = await page.$$('input');
      for (const input of agentInputs) {
        const placeholder = await input.getProperty('placeholder').then(p => p.jsonValue());
        if (placeholder === 'my-agent') {
          await input.type('test-agent');
          log('Fill agentKey field', 'PASS');
          break;
        }
      }
    } catch (e) {
      log('Fill agentKey field', 'FAIL', e.message);
    }

    // Test 4: Toggle tools
    console.log('\n--- Test 4: Toggle tools ---');
    try {
      const toolLabels = await page.$$('label');
      let foundTools = 0;
      for (const label of toolLabels) {
        const text = await label.evaluate(el => el.textContent);
        if (text && (text.includes('Read') || text.includes('Edit') || text.includes('Bash'))) {
          foundTools++;
        }
      }
      if (foundTools >= 3) {
        log('Tools checkboxes visible', 'PASS', `${foundTools} tools found`);
      } else {
        log('Tools checkboxes visible', 'FAIL', `${foundTools} tools found`);
      }
    } catch (e) {
      log('Tools checkboxes visible', 'FAIL', e.message);
    }

    // Test 5: Switch to YAML mode
    console.log('\n--- Test 5: Switch to YAML mode ---');
    try {
      const buttons = await page.$$('button');
      for (const btn of buttons) {
        const text = await btn.evaluate(el => el.textContent);
        if (text && text.includes('YAML')) {
          await btn.click();
          await sleep(1000);
          log('Switch to YAML mode', 'PASS');
          break;
        }
      }
    } catch (e) {
      log('Switch to YAML mode', 'FAIL', e.message);
    }
    
    // Check YAML textarea
    const yamlContent = await page.content();
    if (yamlContent.includes('textarea') && yamlContent.includes('font-mono')) {
      log('YAML textarea visible', 'PASS');
    } else {
      log('YAML textarea visible', 'FAIL');
    }

    // Test 6: Switch back to form mode
    console.log('\n--- Test 6: Switch back to form mode ---');
    try {
      const buttons = await page.$$('button');
      for (const btn of buttons) {
        const text = await btn.evaluate(el => el.textContent);
        if (text && text.includes('表单')) {
          await btn.click();
          await sleep(1000);
          log('Switch to form mode', 'PASS');
          break;
        }
      }
    } catch (e) {
      log('Switch to form mode', 'FAIL', e.message);
    }

    // Test 7: Check LLM config notice
    console.log('\n--- Test 7: Check LLM config notice ---');
    const noticeContent = await page.content();
    if (noticeContent.includes('LLM_API_KEY') || noticeContent.includes('环境变量')) {
      log('LLM config notice visible', 'PASS');
    } else {
      log('LLM config notice visible', 'FAIL');
    }

    // Test 8: Navigate to agents list
    console.log('\n--- Test 8: Navigate to agents list ---');
    await page.goto(`${BASE_URL}/agents`, { waitUntil: 'networkidle0' });
    await sleep(2000);
    
    const listContent = await page.content();
    if (listContent.includes('Agent') || listContent.includes('agent')) {
      log('Agents list page loaded', 'PASS');
    } else {
      log('Agents list page loaded', 'FAIL');
    }

    console.log('\n=== All frontend tests completed ===');
  } catch (e) {
    console.error('Test error:', e);
  } finally {
    await browser.close();
  }
}

main().catch(console.error);
