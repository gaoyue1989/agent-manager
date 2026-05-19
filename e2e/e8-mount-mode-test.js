const puppeteer = require('puppeteer');
const fs = require('fs');
const { execSync } = require('child_process');

const BASE_URL = process.env.BASE_URL || 'http://127.0.0.1:8080';
const API_URL = `${BASE_URL}/api/v1`;

let screenshotIndex = 1;
const screenshots = [];

async function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

async function takeScreenshot(page, name, description) {
  const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
  const filename = `${String(screenshotIndex).padStart(2, '0')}-${timestamp}-${name.replace(/\s+/g, '-')}.png`;
  const filepath = `screenshots/${filename}`;

  await page.screenshot({ path: filepath, fullPage: true });

  screenshots.push({
    index: screenshotIndex,
    filename,
    name,
    description,
    timestamp: new Date().toISOString(),
  });

  console.log(`[${screenshotIndex}] ${name}: ${description}`);
  screenshotIndex++;
}

async function log(name, status, description, data = {}) {
  console.log(`[${status}] ${name}: ${description}`);
  if (Object.keys(data).length > 0) {
    console.log('  ', JSON.stringify(data));
  }
}

async function main() {
  const browser = await puppeteer.launch({ headless: 'new', args: ['--no-sandbox', '--disable-setuid-sandbox'] });
  const page = await browser.newPage();

  let allPass = true;

  try {
    console.log('=== E8: 挂载模式 (Mount Mode) 测试 ===\n');

    // E8-1: 镜像列表接口
    {
      const res = await fetch(`${API_URL}/images`);
      const data = await res.json();
      if (data.items && data.items.length > 0) {
        await log('E8-1', 'PASS', '镜像列表接口正常', { count: data.items.length, images: data.items.map(i => i.name) });
      } else {
        await log('E8-1', 'WARN', '镜像列表为空');
      }
    }

    // E8-2: 创建挂载模式 Agent (使用 OAF YAML 格式)
    let agentId;
    {
      const oafConfig = `---
name: "E8 Mount Test Agent"
vendorKey: "e2e"
agentKey: "e8-mount-test"
version: "1.0.0"
slug: "e2e/e8-mount-test"
description: "E2E mount mode test agent"
author: "@e2e"
license: "MIT"
tags: ["e2e", "mount"]
tools: ["Read", "Edit", "Bash"]
---
You are a helpful AI assistant for testing mount mode deployment.`;

      const createRes = await fetch(`${API_URL}/agents`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          config: oafConfig,
          config_type: 'oaf',
          runtime_mode: 'mount',
          image: 'agent-framework:latest',
        }),
      });
      const createData = await createRes.json();
      agentId = createData.id;
      if (!agentId) {
        allPass = false;
        await log('E8-2', 'FAIL', '创建 Agent 失败', createData);
        throw new Error('Agent creation failed');
      }
      await log('E8-2', 'PASS', '创建挂载模式 Agent', { agentId, runtimeMode: createData.runtime_mode, image: createData.image });
    }

    // E8-3: 前端详情页截图
    {
      await page.goto(`${BASE_URL.replace(':8080', ':3000')}/agents/${agentId}`, { waitUntil: 'domcontentloaded' });
      await sleep(5000);
      await takeScreenshot(page, 'E8-3 挂载模式Agent详情页', '挂载模式Agent的详情页面，显示运行时配置卡片');
    }

    // E8-4: 验证 Agent 字段
    {
      const res = await fetch(`${API_URL}/agents/${agentId}`);
      const data = await res.json();
      if (data.runtime_mode === 'mount' && data.image === 'agent-framework:latest') {
        await log('E8-4', 'PASS', '验证运行时模式字段', { runtime_mode: data.runtime_mode, image: data.image });
      } else {
        allPass = false;
        await log('E8-4', 'FAIL', '运行时模式字段不正确', { runtime_mode: data.runtime_mode, image: data.image });
      }
    }

    // E8-5: 前端创建页面截图（先选择挂载模式）
    {
      await page.goto(`${BASE_URL.replace(':8080', ':3000')}/agents/create`, { waitUntil: 'domcontentloaded' });
      await sleep(3000);
      // 点击挂载模式单选按钮的 label（input 是 hidden 的）
      try {
        await page.evaluate(() => {
          const labels = document.querySelectorAll('label');
          for (const label of labels) {
            if (label.textContent.includes('挂载模式')) {
              label.click();
              break;
            }
          }
        });
        await sleep(500);
      } catch (e) {
        console.log('Note: Could not click mount mode radio button');
      }
      await sleep(1000);
      await takeScreenshot(page, 'E8-5 创建页面-挂载模式', '创建Agent页面，展示运行模式选择器和镜像下拉框');
    }

    // E8-6: 更新 Agent 为挂载模式
    {
      const updatedOaf = `---
name: "E8 Mount Test Agent Updated"
vendorKey: "e2e"
agentKey: "e8-mount-test"
version: "1.0.0"
slug: "e2e/e8-mount-test"
description: "Updated E2E mount mode test agent"
author: "@e2e"
license: "MIT"
tags: ["e2e", "mount"]
tools: ["Read", "Edit", "Bash"]
---
You are an updated helpful AI assistant for testing mount mode deployment.`;

      const updateRes = await fetch(`${API_URL}/agents/${agentId}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          config: updatedOaf,
          config_type: 'oaf',
          runtime_mode: 'mount',
          image: 'agent-framework:v0.5.5',
          checkpoint_dsn: 'mysql+asyncmy://e2e:pass@127.0.0.1:3307/agent_manager_checkpoint',
        }),
      });
      const updateData = await updateRes.json();
      if (updateData.runtime_mode === 'mount' && updateData.image === 'agent-framework:v0.5.5' && updateData.version === 2) {
        await log('E8-6', 'PASS', '更新挂载模式 Agent', { version: updateData.version, image: updateData.image });
      } else {
        allPass = false;
        await log('E8-6', 'FAIL', '更新失败', updateData);
      }
    }

    // E8-7: 前端编辑页面截图
    {
      await page.goto(`${BASE_URL.replace(':8080', ':3000')}/agents/${agentId}/edit`, { waitUntil: 'domcontentloaded' });
      await sleep(5000);
      await takeScreenshot(page, 'E8-7 编辑页面-挂载模式', '编辑Agent页面，展示运行模式选择器和镜像选择');
    }

    // E8-8: 更新后详情页截图
    {
      await page.goto(`${BASE_URL.replace(':8080', ':3000')}/agents/${agentId}`, { waitUntil: 'domcontentloaded' });
      await sleep(5000);
      await takeScreenshot(page, 'E8-8 更新后详情页', '更新后的Agent详情页面，展示新的镜像和checkpoint DSN');
    }

    // E8-9: 发布/部署 Agent (挂载模式直接部署)
    {
      await log('E8-9', 'INFO', '发布挂载模式 Agent...');
      const publishRes = await fetch(`${API_URL}/agents/${agentId}/publish`, { method: 'POST' });
      const publishData = await publishRes.json();
      
      if (publishData.status === 'running' || publishData.status === 'deployed') {
        await log('E8-9', 'PASS', '发布成功', { status: publishData.status, endpoint: publishData.endpoint_url });
      } else {
        allPass = false;
        await log('E8-9', 'FAIL', '发布失败', publishData);
      }
      
      // 等待部署完成
      await sleep(15000);
    }

    // E8-10: 部署成功详情页截图
    {
      await page.goto(`${BASE_URL.replace(':8080', ':3000')}/agents/${agentId}`, { waitUntil: 'domcontentloaded' });
      await sleep(5000);
      await takeScreenshot(page, 'E8-10 部署成功详情页', '部署成功后的Agent详情页，显示已发布状态和Pod信息');
    }

    // E8-11: 验证部署状态
    {
      const res = await fetch(`${API_URL}/agents/${agentId}`);
      const data = await res.json();
      if (data.status === 'published' || data.status === 'deployed') {
        await log('E8-11', 'PASS', '验证部署状态', { status: data.status });
      } else {
        allPass = false;
        await log('E8-11', 'FAIL', '部署状态不正确', { status: data.status });
      }
    }

    // E8-12: 下线 Agent
    {
      await log('E8-12', 'INFO', '下线 Agent...');
      const unpublishRes = await fetch(`${API_URL}/agents/${agentId}/unpublish`, { method: 'POST' });
      if (unpublishRes.ok) {
        await log('E8-12', 'PASS', '下线成功');
      } else {
        allPass = false;
        await log('E8-12', 'FAIL', '下线失败');
      }
      await sleep(5000);
    }

    // E8-13: 下线后详情页截图
    {
      await page.goto(`${BASE_URL.replace(':8080', ':3000')}/agents/${agentId}`, { waitUntil: 'domcontentloaded' });
      await sleep(5000);
      await takeScreenshot(page, 'E8-13 下线后详情页', '下线后的Agent详情页，显示已下线状态');
    }

    // 清理
    {
      await log('CLEANUP', 'INFO', '清理测试 Agent...');
      const delRes = await fetch(`${API_URL}/agents/${agentId}`, { method: 'DELETE' });
      if (delRes.ok) {
        await log('E8-14', 'PASS', 'Agent 已清理');
      } else {
        await log('E8-14', 'WARN', '清理可能失败');
      }
    }

    console.log(`\n=== E8 测试${allPass ? '全部通过' : '有失败'} ===\n`);
  } catch (e) {
    await log('E8', 'FAIL', '测试失败', { error: e.message });
  } finally {
    await browser.close();
  }

  if (typeof process.env.CI === 'undefined') {
    fs.writeFileSync('screenshots/metadata.json', JSON.stringify(screenshots, null, 2));
  }
}

main();
