const puppeteer = require('puppeteer');
const fs = require('fs');
const path = require('path');
const http = require('http');

const SCREENSHOT_DIR = path.join(__dirname, 'screenshots');
if (!fs.existsSync(SCREENSHOT_DIR)) fs.mkdirSync(SCREENSHOT_DIR, { recursive: true });

const BASE_URL = process.env.BASE_URL || 'http://localhost:8911';

let screenshotCounter = 0;

async function takeScreenshot(page, name, status, description) {
  screenshotCounter++;
  const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
  const filename = `${String(screenshotCounter).padStart(2, '0')}-${timestamp}-${name.replace(/\s+/g, '-')}.png`;
  const filepath = path.join(SCREENSHOT_DIR, filename);
  
  await page.screenshot({ path: filepath, fullPage: true });
  
  const metaPath = path.join(SCREENSHOT_DIR, 'metadata.json');
  let metadata = [];
  if (fs.existsSync(metaPath)) {
    try { metadata = JSON.parse(fs.readFileSync(metaPath, 'utf8')); } catch {}
  }
  metadata.push({
    index: screenshotCounter,
    filename,
    name,
    status,
    description,
    timestamp: new Date().toISOString(),
    url: page.url()
  });
  fs.writeFileSync(metaPath, JSON.stringify(metadata, null, 2));
  
  console.log(`  📸 截图: ${filename} (${status})`);
  return filepath;
}

const apiPost = (urlPath, body) => {
  return new Promise((resolve, reject) => {
    const url = new URL(urlPath, BASE_URL);
    const data = body ? JSON.stringify(body) : null;
    const opts = {
      hostname: url.hostname,
      port: url.port || 80,
      path: url.pathname,
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Content-Length': data ? Buffer.byteLength(data) : 0 }
    };
    const req = http.request(opts, (res) => {
      let body = '';
      res.on('data', chunk => body += chunk);
      res.on('end', () => {
        try { resolve({ status: res.statusCode, data: JSON.parse(body) }); }
        catch { resolve({ status: res.statusCode, data: body }); }
      });
    });
    req.on('error', reject);
    if (data) req.write(data);
    req.end();
  });
};

const apiGet = (urlPath) => {
  return new Promise((resolve, reject) => {
    const url = new URL(urlPath, BASE_URL);
    const opts = {
      hostname: url.hostname,
      port: url.port || 80,
      path: url.pathname,
      method: 'GET'
    };
    const req = http.request(opts, (res) => {
      let body = '';
      res.on('data', chunk => body += chunk);
      res.on('end', () => {
        try { resolve({ status: res.statusCode, data: JSON.parse(body) }); }
        catch { resolve({ status: res.statusCode, data: body }); }
      });
    });
    req.on('error', reject);
    req.end();
  });
};

const apiDelete = (urlPath) => {
  return new Promise((resolve, reject) => {
    const url = new URL(urlPath, BASE_URL);
    const opts = {
      hostname: url.hostname,
      port: url.port || 80,
      path: url.pathname,
      method: 'DELETE'
    };
    const req = http.request(opts, (res) => {
      let body = '';
      res.on('data', chunk => body += chunk);
      res.on('end', () => {
        try { resolve({ status: res.statusCode, data: JSON.parse(body) }); }
        catch { resolve({ status: res.statusCode, data: body }); }
      });
    });
    req.on('error', reject);
    req.end();
  });
};

async function sleep(ms) {
  return new Promise(r => setTimeout(r, ms));
}

(async () => {
  console.log('========================================');
  console.log('  Deployment Mode E2E 测试');
  console.log('========================================');
  console.log(`Base URL: ${BASE_URL}\n`);

  // Clear old metadata
  const metaPath = path.join(SCREENSHOT_DIR, 'metadata.json');
  if (fs.existsSync(metaPath)) fs.unlinkSync(metaPath);

  const results = [];
  const log = (test, status, msg, page = null, description = '') => {
    console.log(`[${status}] ${test}: ${msg}`);
    results.push({ test, status, msg });
    if (page && description) {
      takeScreenshot(page, test, status, description).catch(() => {});
    }
  };

  try {
    const browser = await puppeteer.launch({
      headless: 'new',
      args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-gpu', '--disable-dev-shm-usage']
    });
    const page = await browser.newPage();
    await page.setViewport({ width: 1440, height: 900 });

    // ============================================================
    // E7-1: 首页访问 - 验证新代码部署后前端正常
    // ============================================================
    console.log('\n--- E7: Deployment Mode 验证 ---');
    await page.goto(`${BASE_URL}/`, { waitUntil: 'domcontentloaded', timeout: 15000 });
    await sleep(2000);
    const homeContent = await page.content();
    if (homeContent.includes('Agent') || homeContent.includes('创建')) {
      log('E7-1: 首页访问', 'PASS', '页面正常渲染', page, '后端更新代码后，前端页面正常访问，验证服务可用性');
    } else {
      log('E7-1: 首页访问', 'FAIL', '页面内容异常', page, '首页内容异常，服务可能未正常启动');
    }

    // ============================================================
    // E7-2: API 健康检查 - 验证新代码 API 正常
    // ============================================================
    const agentsResp = await apiGet('/api/v1/agents');
    if (agentsResp.status === 200 && Array.isArray(agentsResp.data.items)) {
      log('E7-2: API 健康检查', 'PASS', `返回 ${agentsResp.data.total} 个 Agent`, page, 'API 返回正常，验证后端服务健康');
    } else {
      log('E7-2: API 健康检查', 'FAIL', `状态码: ${agentsResp.status}`, page, 'API 请求异常');
    }

    // ============================================================
    // E7-3: 创建 Agent (build 模式)
    // ============================================================
    const createResp = await apiPost('/api/v1/agents', {
      config: JSON.stringify({
        name: 'e2e-deploy-test',
        description: 'E2E deployment mode test agent',
        model: process.env.LLM_MODEL || 'qwen3.6-plus',
        model_endpoint: process.env.LLM_ENDPOINT || 'https://dashscope.aliyuncs.com/compatible-mode/v1',
        api_key: process.env.LLM_API_KEY || 'sk-****',
        system_prompt: '你是一个 E2E 部署测试助手。',
        enabled_tools: ['write_todos', 'ls', 'read_file'],
        excluded_tools: ['execute'],
        memory: true,
        max_iterations: 10
      }),
      config_type: 'json'
    });

    let agentId = null;
    if (createResp.status === 201 && createResp.data && createResp.data.id) {
      agentId = createResp.data.id;
      log('E7-3: 创建 Agent', 'PASS', `ID=${agentId}, 状态=${createResp.data.status}`, page, `创建测试 Agent (build 模式)，验证创建 API 正常`);
    } else {
      log('E7-3: 创建 Agent', 'FAIL', JSON.stringify(createResp.data), page, 'Agent 创建失败');
    }

    // ============================================================
    // E7-4: 代码生成
    // ============================================================
    if (agentId) {
      const genResp = await apiPost(`/api/v1/agents/${agentId}/generate`);
      if (genResp.status === 200 && genResp.data.status === 'success') {
        log('E7-4: 代码生成', 'PASS', '代码生成成功', page, '触发代码生成 API，验证代码生成流程正常');
      } else {
        log('E7-4: 代码生成', 'FAIL', JSON.stringify(genResp.data), page, '代码生成失败');
      }
    }

    // ============================================================
    // E7-5: 验证 Agent 状态流转
    // ============================================================
    if (agentId) {
      await sleep(1000);
      const agentResp = await apiGet(`/api/v1/agents/${agentId}`);
      if (agentResp.status === 200 && agentResp.data.status === 'generated') {
        log('E7-5: 状态流转', 'PASS', `draft → generated`, page, 'Agent 状态正确流转: draft → generated');
      } else {
        log('E7-5: 状态流转', 'FAIL', `状态: ${agentResp.data?.status}`, page, 'Agent 状态流转异常');
      }
    }

    // ============================================================
    // E7-6: 验证部署相关 API 端点存在
    // ============================================================
    if (agentId) {
      const endpoints = [
        `/api/v1/agents/${agentId}/build`,
        `/api/v1/agents/${agentId}/deploy`,
        `/api/v1/agents/${agentId}/publish`,
        `/api/v1/agents/${agentId}/unpublish`,
        `/api/v1/agents/${agentId}/image-info`,
        `/api/v1/agents/${agentId}/pod-status`,
      ];

      let allEndpointsExist = true;
      for (const endpoint of endpoints) {
        // Use GET for image-info and pod-status, POST for others
        const method = endpoint.includes('image-info') || endpoint.includes('pod-status') ? 'GET' : 'POST';
        const resp = await new Promise((resolve) => {
          const url = new URL(endpoint, BASE_URL);
          const opts = {
            hostname: url.hostname,
            port: url.port || 80,
            path: url.pathname,
            method: method,
            headers: { 'Content-Type': 'application/json' }
          };
          const req = http.request(opts, (res) => {
            let body = '';
            res.on('data', chunk => body += chunk);
            res.on('end', () => resolve({ status: res.statusCode }));
          });
          req.on('error', () => resolve({ status: 0 }));
          if (method === 'POST') {
            req.write('{}');
          }
          req.end();
        });
        
        // 404 for image-info is expected (no build yet), 405 for POST on GET endpoints is expected
        if (resp.status === 404 && !endpoint.includes('image-info') && !endpoint.includes('pod-status')) {
          allEndpointsExist = false;
        }
      }
      
      if (allEndpointsExist) {
        log('E7-6: API 端点验证', 'PASS', '所有部署相关端点存在', page, '验证 build/deploy/publish/unpublish/image-info/pod-status 端点均可访问');
      } else {
        log('E7-6: API 端点验证', 'FAIL', '部分端点不存在', page, '部署相关 API 端点缺失');
      }
    }

    // ============================================================
    // E7-7: 创建挂载模式 Agent
    // ============================================================
    const mountResp = await apiPost('/api/v1/agents', {
      config: JSON.stringify({
        name: 'e2e-mount-test',
        description: 'E2E mount mode test agent',
        model: process.env.LLM_MODEL || 'qwen3.6-plus',
        model_endpoint: process.env.LLM_ENDPOINT || 'https://dashscope.aliyuncs.com/compatible-mode/v1',
        api_key: process.env.LLM_API_KEY || 'sk-****',
        system_prompt: '你是一个 E2E 挂载模式测试助手。',
        memory: true,
        max_iterations: 10
      }),
      config_type: 'json',
      runtime_mode: 'mount',
      image: 'agent-framework:latest'
    });

    let mountAgentId = null;
    if (mountResp.status === 201 && mountResp.data && mountResp.data.id) {
      mountAgentId = mountResp.data.id;
      log('E7-7: 创建挂载 Agent', 'PASS', `ID=${mountAgentId}, 模式=mount`, page, `创建挂载模式 Agent，验证 runtime_mode=mount 配置正确`);
    } else {
      log('E7-7: 创建挂载 Agent', 'FAIL', JSON.stringify(mountResp.data), page, '挂载模式 Agent 创建失败');
    }

    // ============================================================
    // E7-8: 验证挂载模式 Agent 配置
    // ============================================================
    if (mountAgentId) {
      const mountAgentResp = await apiGet(`/api/v1/agents/${mountAgentId}`);
      if (mountAgentResp.status === 200 && 
          mountAgentResp.data.runtime_mode === 'mount' && 
          mountAgentResp.data.image === 'agent-framework:latest') {
        log('E7-8: 挂载模式配置', 'PASS', 'runtime_mode=mount, image 正确', page, '挂载模式 Agent 配置正确: runtime_mode=mount, image=agent-framework:latest');
      } else {
        log('E7-8: 挂载模式配置', 'FAIL', JSON.stringify(mountAgentResp.data), page, '挂载模式 Agent 配置异常');
      }
    }

    // ============================================================
    // E7-9: 验证删除功能 (清理测试数据)
    // ============================================================
    if (agentId) {
      const deleteResp = await apiDelete(`/api/v1/agents/${agentId}`);
      if (deleteResp.status === 200 && deleteResp.data.cleanup && deleteResp.data.cleanup.database) {
        log('E7-9: 删除 Agent', 'PASS', '删除成功，数据库已清理', page, '删除 build 模式 Agent，验证清理功能正常');
      } else {
        log('E7-9: 删除 Agent', 'FAIL', JSON.stringify(deleteResp.data), page, 'Agent 删除失败');
      }
    }

    if (mountAgentId) {
      const mountDeleteResp = await apiDelete(`/api/v1/agents/${mountAgentId}`);
      if (mountDeleteResp.status === 200 && mountDeleteResp.data.cleanup && mountDeleteResp.data.cleanup.database) {
        log('E7-10: 删除挂载 Agent', 'PASS', '删除成功，数据库已清理', page, '删除挂载模式 Agent，验证清理功能正常');
      } else {
        log('E7-10: 删除挂载 Agent', 'FAIL', JSON.stringify(mountDeleteResp.data), page, '挂载模式 Agent 删除失败');
      }
    }

    // ============================================================
    // E7-11: 验证 Agent 列表页 (前端)
    // ============================================================
    await page.goto(`${BASE_URL}/`, { waitUntil: 'domcontentloaded', timeout: 15000 });
    await sleep(2000);
    const listContent = await page.content();
    if (listContent.includes('Agent 列表') || listContent.includes('创建')) {
      log('E7-11: Agent 列表页', 'PASS', '列表页正常渲染', page, 'Agent 列表页正常显示，验证前端页面渲染正确');
    } else {
      log('E7-11: Agent 列表页', 'FAIL', '列表页内容异常', page, 'Agent 列表页内容异常');
    }

    // Final screenshot
    await takeScreenshot(page, 'E2E 测试完成 - 全页面', 'INFO', '所有测试用例执行完成后的完整页面截图');

    await browser.close();

  } catch (e) {
    console.error('Test error:', e);
  } finally {
    console.log('\n========================================');
    console.log('  测试完成');
    console.log('========================================\n');

    // Summary
    const passCount = results.filter(r => r.status === 'PASS').length;
    const failCount = results.filter(r => r.status === 'FAIL').length;
    console.log(`总计: ${results.length} 个测试`);
    console.log(`通过: ${passCount}`);
    console.log(`失败: ${failCount}`);
    console.log(`通过率: ${((passCount / results.length) * 100).toFixed(1)}%`);
    console.log(`\n截图目录: ${SCREENSHOT_DIR}`);
  }
})();
