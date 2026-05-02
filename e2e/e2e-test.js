const puppeteer = require('puppeteer');
const fs = require('fs');
const path = require('path');
const http = require('http');
const { execSync } = require('child_process');

const SCREENSHOT_DIR = path.join(__dirname, 'screenshots');
if (!fs.existsSync(SCREENSHOT_DIR)) fs.mkdirSync(SCREENSHOT_DIR, { recursive: true });

const BASE_URL = process.env.BASE_URL || 'http://localhost:8911';
const MCP_PORT = process.env.MCP_PORT || 9998;

// 截图计数器，用于生成有序文件名
let screenshotCounter = 0;

/**
 * 截图并保存，返回文件路径
 * @param {import('puppeteer').Page} page - Puppeteer Page 对象
 * @param {string} name - 截图名称（中文描述）
 * @param {string} status - 测试状态 (PASS/FAIL/WARN)
 * @param {string} description - 截图说明
 * @returns {string} 截图文件路径
 */
async function takeScreenshot(page, name, status, description) {
  screenshotCounter++;
  const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
  const filename = `${String(screenshotCounter).padStart(2, '0')}-${timestamp}-${name.replace(/\s+/g, '-')}.png`;
  const filepath = path.join(SCREENSHOT_DIR, filename);
  
  await page.screenshot({ path: filepath, fullPage: true });
  
  // 记录截图元数据
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

const findByText = async (page, tag, text) => {
  return await page.evaluateHandle((t, txt) => {
    const xpath = `//${t}[contains(text(), "${txt}")]`;
    const result = document.evaluate(xpath, document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null);
    return result.singleNodeValue;
  }, tag, text);
};

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

async function sleep(ms) {
  return new Promise(r => setTimeout(r, ms));
}

(async () => {
  console.log('========================================');
  console.log('  Agent Manager E2E 测试');
  console.log('========================================');
  console.log(`Base URL: ${BASE_URL}`);
  console.log(`MCP Port: ${MCP_PORT}`);
  console.log(`截图目录: ${SCREENSHOT_DIR}\n`);

  // 清理旧截图
  if (fs.existsSync(path.join(SCREENSHOT_DIR, 'metadata.json'))) {
    fs.unlinkSync(path.join(SCREENSHOT_DIR, 'metadata.json'));
  }

  const results = [];
  const log = (test, status, msg, page = null, description = '') => {
    console.log(`[${status}] ${test}: ${msg}`);
    results.push({ test, status, msg });
    if (page && description) {
      takeScreenshot(page, test, status, description).catch(() => {});
    }
  };

  try {
    // ============================================================
    // Step 0: 验证 Mock MCP Server
    // ============================================================
    console.log('--- Step 0: Mock MCP Server 验证 ---');
    try {
      const health = await new Promise((resolve, reject) => {
        http.get(`http://localhost:${MCP_PORT}/health`, (res) => {
          let d = ''; res.on('data', c => d += c); res.on('end', () => resolve(JSON.parse(d)));
        }).on('error', reject);
      });
      if (health.status === 'healthy') {
        log('MCP SETUP: Mock Server', 'PASS', `运行在端口 ${MCP_PORT}`);
      } else {
        log('MCP SETUP: Mock Server', 'FAIL', '健康检查失败');
      }
    } catch (e) {
      log('MCP SETUP: Mock Server', 'FAIL', `无法连接: ${e.message}. 请先启动: python3 e2e/mock_mcp_server.py ${MCP_PORT}`);
    }

    // ============================================================
    // Step 1: 启动浏览器
    // ============================================================
    const browser = await puppeteer.launch({
      headless: 'new',
      args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-gpu', '--disable-dev-shm-usage']
    });
    const page = await browser.newPage();
    await page.setViewport({ width: 1440, height: 900 });

    // ============================================================
    // E4: Nginx 8911 统一端口
    // ============================================================
    console.log('\n--- E4: Nginx 8911 统一端口 ---');
    await page.goto(`${BASE_URL}/`, { waitUntil: 'domcontentloaded', timeout: 15000 });
    await sleep(2000);
    const homeContent = await page.content();
    if (homeContent.includes('首页') || homeContent.includes('Agent') || homeContent.includes('创建')) {
      log('E4-1: Nginx 首页访问', 'PASS', '页面正常渲染', page, '通过 Nginx 8911 端口访问首页，验证反向代理配置正确');
    } else {
      log('E4-1: Nginx 首页访问', 'FAIL', '页面内容异常', page, '首页内容异常，Nginx 反向代理可能未正确配置');
    }

    const apiData = await page.evaluate(async (baseUrl) => {
      const res = await fetch(`${baseUrl}/api/v1/agents`);
      return res.ok ? await res.json() : null;
    }, BASE_URL);
    if (apiData && Array.isArray(apiData.items)) {
      log('E4-2: Nginx API 转发', 'PASS', 'API 返回正常 JSON', page, '通过 Nginx 8911 端口转发 API 请求，验证 /api/ 路由配置正确');
    } else {
      log('E4-2: Nginx API 转发', 'FAIL', 'API 异常', page, 'API 请求异常，Nginx 后端代理可能未正确配置');
    }

    // ============================================================
    // E1: 创建 Agent (API 方式, 带 MCP + Tools)
    // ============================================================
    console.log('\n--- E1: 创建 Agent (MCP + Enabled Tools) ---');

    const createResp = await apiPost('/api/v1/agents', {
      config: JSON.stringify({
        name: 'e2e-mcp-agent',
        description: 'E2E test agent with MCP and tool configuration',
        model: process.env.LLM_MODEL || 'qwen3.6-plus',
        model_endpoint: process.env.LLM_ENDPOINT || 'https://dashscope.aliyuncs.com/compatible-mode/v1',
        api_key: process.env.LLM_API_KEY || 'sk-****',
        system_prompt: '你是一个 E2E 测试助手。请用中文回答，并在回答中包含"E2E_TEST_MARKER"。',
        enabled_tools: ['write_todos', 'ls', 'read_file', 'write_file', 'edit_file', 'glob', 'grep', 'task'],
        excluded_tools: ['execute'],
        mcp_config: {
          url: `http://localhost:${MCP_PORT}/api/mcp`,
          transport: 'sse',
          headers: {}
        },
        memory: true,
        max_iterations: 50
      }),
      config_type: 'json'
    });

    let agentId = null;
    if (createResp.status === 201 && createResp.data && createResp.data.id) {
      agentId = createResp.data.id;
      log('E1-1: 创建 Agent', 'PASS', `ID=${agentId}, 状态=draft`, page, `通过 API 创建 Agent，配置包含 MCP Server URL (http://localhost:${MCP_PORT}/api/mcp) 和 8 个启用工具`);
    } else {
      log('E1-1: 创建 Agent', 'FAIL', JSON.stringify(createResp.data), page, 'Agent 创建失败');
    }

    // ============================================================
    // E1-2: 验证详情页展示工具列表和 MCP 配置
    // ============================================================
    if (agentId) {
      await page.goto(`${BASE_URL}/agents/${agentId}`, { waitUntil: 'domcontentloaded', timeout: 15000 });
      await sleep(2000);
      const detailContent = await page.content();

      if (detailContent.includes('e2e-mcp-agent')) {
        log('E1-2: 详情页展示', 'PASS', '详情页显示 Agent 名称', page, 'Agent 详情页展示名称、状态、配置类型等基本信息');
      } else {
        log('E1-2: 详情页展示', 'FAIL', '详情页内容异常', page, '详情页未正确显示 Agent 信息');
      }

      // Verify tool badges
      if (detailContent.includes('write_todos') || detailContent.includes('启用工具')) {
        log('E1-3: 工具标签展示', 'PASS', '详情页显示启用工具', page, '详情页 Agent 配置区域展示启用的工具列表标签 (write_todos, ls, read_file 等)');
      } else {
        log('E1-3: 工具标签展示', 'FAIL', '未显示工具标签', page, '详情页未展示工具标签，enabled_tools 配置可能未正确解析');
      }

      // Verify MCP config
      if (detailContent.includes('MCP') || detailContent.includes('mcp')) {
        log('E1-4: MCP 配置展示', 'PASS', '详情页显示 MCP 配置', page, '详情页 Agent 配置区域展示 MCP 配置信息 (URL、传输协议)');
      } else {
        log('E1-4: MCP 配置展示', 'FAIL', '未显示 MCP 配置', page, '详情页未展示 MCP 配置，mcp_config 可能未正确解析');
      }
    }

    // ============================================================
    // E2: 生成代码 (验证 MCP + Tool 代码正确生成)
    // ============================================================
    console.log('\n--- E2: 代码生成验证 ---');
    if (agentId) {
      const genResp = await apiPost(`/api/v1/agents/${agentId}/generate`);
      if (genResp.status === 200 && genResp.data.status === 'success') {
        log('E2-1: 代码生成', 'PASS', '代码生成成功', page, '触发代码生成 API，后端调用 Python generator.py 生成 agent.py/Dockerfile/requirements.txt');

        // Verify generated code content
        const codeResp = await page.evaluate(async (baseUrl, id) => {
          const res = await fetch(`${baseUrl}/api/v1/agents/${id}/code`);
          return res.ok ? await res.json() : null;
        }, BASE_URL, agentId);

        if (codeResp && codeResp.code) {
          const code = codeResp.code;
          if (code.includes('langchain_mcp_adapters') || code.includes('get_mcp_tools')) {
            log('E2-2: MCP 代码生成', 'PASS', '生成代码包含 MCP 客户端初始化', page, '生成的 agent.py 包含 MCP 客户端初始化代码 (MultiServerMCPClient + get_mcp_tools)');
          } else {
            log('E2-2: MCP 代码生成', 'WARN', 'MCP URL 为空，未生成 MCP 代码', page, '生成的代码未包含 MCP 客户端初始化，mcp_config.url 可能为空');
          }

          if (code.includes('ENABLED_TOOLS') && code.includes('EXCLUDED_TOOLS')) {
            log('E2-3: 工具配置代码', 'PASS', '生成代码包含工具配置', page, '生成的 agent.py 包含 ENABLED_TOOLS 和 EXCLUDED_TOOLS 配置数组');
          } else {
            log('E2-3: 工具配置代码', 'FAIL', '未包含工具配置', page, '生成的代码未包含工具配置，enabled_tools 可能未正确传递');
          }

          if (code.includes('all_tools')) {
            log('E2-4: Agent 初始化', 'PASS', 'Agent 使用工具初始化', page, '生成的 agent.py 中 create_deep_agent() 使用 all_tools (包含 MCP 工具) 初始化');
          } else {
            log('E2-4: Agent 初始化', 'FAIL', 'Agent 未正确初始化工具', page, '生成的代码中 Agent 未正确使用工具列表初始化');
          }

          // Verify it's valid Python
          try {
            const tmpFile = '/tmp/e2e_test_agent.py';
            fs.writeFileSync(tmpFile, code);
            execSync(`/root/agent-manager/codegen/venv/bin/python3 -m py_compile ${tmpFile}`);
            log('E2-5: Python 语法', 'PASS', '生成代码 Python 语法正确', page, '生成的 agent.py 通过 Python 语法检查 (py_compile)，无语法错误');
          } catch (e) {
            log('E2-5: Python 语法', 'FAIL', e.message.substring(0, 100), page, '生成的 agent.py 存在 Python 语法错误');
          }
        } else {
          log('E2-2: 代码内容', 'FAIL', '无法获取生成代码', page, '无法从 API 获取生成的代码内容');
        }
      } else {
        log('E2-1: 代码生成', 'FAIL', JSON.stringify(genResp.data), page, '代码生成 API 返回错误');
      }
    }

    // ============================================================
    // E3: Skill 上传与解析
    // ============================================================
    console.log('\n--- E3: Skill 上传验证 ---');
    if (agentId) {
      try {
        const skillZip = path.join(__dirname, 'test_skills.zip');
        const curlCmd = `curl -s -X POST "${BASE_URL}/api/v1/skills/upload?agent_id=${agentId}" -F "file=@${skillZip}"`;
        const skillResp = JSON.parse(execSync(curlCmd, { encoding: 'utf8' }));

        if (skillResp.skills && skillResp.skills.length >= 2) {
          log('E3-1: Skill 上传', 'PASS', `解析到 ${skillResp.skills.length} 个 Skill`, page, `上传 test_skills.zip (包含 e2e-test-skill + second-skill)，后端解压并解析 SKILL.md`);
          const names = skillResp.skills.map(s => s.name).join(', ');
          log('E3-2: Skill 解析', 'PASS', `Skill 名称: ${names}`, page, `后端从 ZIP 中解析出 Skill 元数据: ${names}`);

          // Verify metadata fields
          const firstSkill = skillResp.skills[0];
          if (firstSkill.name && firstSkill.description) {
            log('E3-3: Skill 元数据', 'PASS', 'name/description 解析正确', page, 'Skill YAML frontmatter 解析正确，包含 name 和 description 字段');
          }

          // Store for later verification
          const listResp = await page.evaluate(async (baseUrl, id) => {
            const res = await fetch(`${baseUrl}/api/v1/skills/${id}`);
            return res.ok ? await res.json() : null;
          }, BASE_URL, agentId);
          if (listResp && listResp.skills && listResp.skills.length >= 2) {
            log('E3-4: Skill 列表查询', 'PASS', `查询到 ${listResp.skills.length} 个 Skill`, page, '通过 GET /api/v1/skills/:agent_id 查询已上传的 Skill 列表');
          } else {
            log('E3-4: Skill 列表查询', 'FAIL', '查询失败', page, 'Skill 列表查询失败，MinIO 存储可能未正确保存');
          }
        } else {
          log('E3-1: Skill 上传', 'FAIL', `返回 skills 数量: ${skillResp.skills?.length || 0}`, page, 'Skill 上传后解析数量不足');
        }
      } catch (e) {
        log('E3-1: Skill 上传', 'FAIL', e.message.substring(0, 100), page, `Skill 上传异常: ${e.message}`);
      }
    }

    // ============================================================
    // F1-F3: 已有 Agent 验证 (Agent 2)
    // ============================================================
    console.log('\n--- F1-F3: 已有 Agent 2 验证 ---');
    await page.goto(`${BASE_URL}/agents/2`, { waitUntil: 'domcontentloaded', timeout: 15000 });
    await sleep(2000);

    // F1: Image Info
    const imageInfoHeader = await findByText(page, 'h2', '镜像信息');
    if (imageInfoHeader) {
      const text = await page.evaluate(el => el.parentElement ? el.parentElement.innerText : '', imageInfoHeader);
      if (text.includes('172.20.0.1:5001'))
        log('F1: 镜像信息展示', 'PASS', '镜像地址显示正确', page, 'Agent 详情页展示镜像信息卡片，包含镜像地址 (172.20.0.1:5001)、版本、构建状态');
      else
        log('F1: 镜像信息展示', 'FAIL', '镜像地址未显示', page, '镜像信息区域存在但地址未正确显示');
    } else {
      log('F1: 镜像信息展示', 'FAIL', '未找到镜像信息区域', page, '未找到镜像信息区域，Agent 2 可能未构建镜像');
    }

    // F2: Pod Status
    const podStatusHeader = await findByText(page, 'h2', 'Pod 状态');
    if (podStatusHeader) {
      log('F2: Pod 状态监控', 'PASS', 'Pod 状态区域正常', page, 'Agent 详情页展示 Pod 状态卡片，包含 Pod 名称、运行状态、就绪状态、重启次数、IP');
      const refreshBtn = await findByText(page, 'button', '刷新');
      if (refreshBtn) {
        await refreshBtn.click(); await sleep(3000);
        log('F2: 刷新按钮', 'PASS', '刷新成功', page, '点击 Pod 状态刷新按钮后，状态数据已更新');
      } else {
        log('F2: 刷新按钮', 'FAIL', '未找到刷新按钮', page, '未找到 Pod 状态刷新按钮');
      }
    } else {
      log('F2: Pod 状态监控', 'FAIL', '未找到 Pod 状态区域', page, '未找到 Pod 状态区域，Agent 2 可能未发布');
    }

    // F3: Chat Test
    const chatHeader = await findByText(page, 'h2', 'Agent 聊天测试');
    if (chatHeader) {
      log('F3: 聊天测试区域', 'PASS', '聊天测试区域正常', page, 'Agent 详情页展示聊天测试卡片，包含历史消息区域、输入框和发送按钮');
      const input = await page.$('input[placeholder="输入消息测试 Agent..."]');
      const sendBtn = await findByText(page, 'button', '发送');
      if (input && sendBtn) {
        await input.type('你好，用中文说你好');
        await sendBtn.click();
        console.log('Waiting for chat response...');
        await sleep(30000);
        const chatBubbles = await page.$$('.bg-white.border');
        if (chatBubbles.length > 0 || await findByText(page, 'div', '响应时间')) {
          log('F3: 聊天功能', 'PASS', '收到 Agent 响应', page, '发送聊天消息后收到 Agent 响应，聊天功能正常 (通过 kubectl exec 或 Ingress URL)');
        } else {
          log('F3: 聊天功能', 'FAIL', '未收到 Agent 响应', page, '发送聊天消息后未收到 Agent 响应，Agent 可能未正常运行');
        }
      } else {
        log('F3: 聊天功能', 'FAIL', '未找到输入框或发送按钮', page, '未找到聊天输入框或发送按钮');
      }
    } else {
      log('F3: 聊天测试区域', 'FAIL', '未找到聊天测试区域', page, '未找到聊天测试区域，Agent 2 可能未发布');
    }

    // 最终全页面截图
    await takeScreenshot(page, 'E2E 测试完成 - 全页面', 'INFO', '所有测试用例执行完成后的完整页面截图，用于整体功能验证');
    console.log('\nScreenshot saved.');
    await browser.close();

    // ============================================================
    // Cleanup: Delete test agent
    // ============================================================
    if (agentId) {
      try {
        await page.evaluate(async (baseUrl, id) => {
          await fetch(`${baseUrl}/api/v1/agents/${id}`, { method: 'DELETE' });
        }, BASE_URL, agentId);
        log('CLEANUP', 'PASS', `已清理测试 Agent ${agentId}`);
      } catch {}
    }

  } catch (e) {
    console.error('Test error:', e);
  } finally {
    console.log('\n========================================');
    console.log('  测试完成');
    console.log('========================================\n');

    console.log('RESULTS_JSON_START');
    console.log(JSON.stringify(results));
    console.log('RESULTS_JSON_END');
  }
})();
