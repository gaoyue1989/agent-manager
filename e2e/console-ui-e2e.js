#!/usr/bin/env node
/**
 * 控制台浏览器 E2E（Puppeteer）：登录 → 工作空间 → Agent 列表/创建/详情/编辑 →
 * 模型连接 → 工具（MCP）→ 技能中心 → 沙箱配置总览。
 *
 * 设计：
 *  - 数据准备走 API（登录/建空间/模型连接/工具/技能/导入包/沙箱 Agent），浏览器只驱动 UI；
 *  - import 模式创建 Agent 不需要 LLM，适合作为 UI 主链路；
 *  - E2E_AGENT_RUNTIME=mock 时跳过会话/调试类断言（mock 业务镜像只提供 A2A 面）。
 *
 * 用法：
 *   BASE=http://localhost:30080/api/v1 FRONTEND=http://localhost:30881 \
 *   ADMIN_USER=admin ADMIN_PASS=OafConsole2026 \
 *   E2E_LLM_BASE_URL=http://mock-llm... E2E_LLM_API_KEY=e2e-dummy E2E_LLM_MODEL=e2e-mock \
 *   node console-ui-e2e.js
 */
const fs = require("node:fs");
const path = require("node:path");
const puppeteer = require("puppeteer");

const BASE = process.env.BASE || "http://localhost:30080/api/v1";
const FRONT = (process.env.FRONTEND || "http://localhost:30881").replace(/\/+$/, "");
const ADMIN_USER = process.env.ADMIN_USER || "admin";
const ADMIN_PASS = process.env.ADMIN_PASS || "OafConsole2026";
const LLM_BASE = process.env.E2E_LLM_BASE_URL || "http://mock-llm.agent-platform.svc.cluster.local:8080/v1";
const LLM_KEY = process.env.E2E_LLM_API_KEY || "e2e-dummy-key";
const LLM_MODEL = process.env.E2E_LLM_MODEL || "e2e-mock";
const RUNTIME = process.env.E2E_AGENT_RUNTIME || "real";
const ART = path.join(__dirname, "ci-artifacts");
const TS = Date.now().toString().slice(-8);
const WS_NAME = `ui-ws-${TS}`;
const SKILL = `ui-skill-${TS}`;
const CONN = `ui-conn-${TS}`;
const TOOL = `ui-tool-${TS}`;
const SANDBOX_NAME = `ui-sandbox-${TS}`;

let pass = 0;
let fail = 0;
const failed = [];
function check(name, cond, detail = "") {
  if (cond) {
    pass++;
    console.log(`  \x1b[32mPASS\x1b[0m ${name}`);
  } else {
    fail++;
    failed.push(name);
    console.log(`  \x1b[31mFAIL\x1b[0m ${name} ${detail}`);
  }
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function api(method, p, body, token) {
  const headers = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined && !(body instanceof FormData)) headers["Content-Type"] = "application/json";
  const res = await fetch(`${BASE}${p}`, {
    method,
    headers,
    body: body === undefined ? undefined : body instanceof FormData ? body : JSON.stringify(body),
  });
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { /* 非 JSON 错误体 */ }
  if (!res.ok) {
    throw new Error(`${method} ${p} -> ${res.status}: ${text.slice(0, 400)}`);
  }
  return data?.data;
}

async function waitFor(fn, timeoutMs, intervalMs = 1000) {
  const deadline = Date.now() + timeoutMs;
  let last;
  while (Date.now() < deadline) {
    try {
      const v = await fn();
      if (v) return v;
    } catch (e) { last = e; }
    await sleep(intervalMs);
  }
  throw new Error(`timeout: ${last ? last.message : "condition not met"}`);
}

async function seed() {
  console.log("== 数据准备（API）==");
  const login = await api("POST", "/auth/login", { username: ADMIN_USER, password: ADMIN_PASS });
  const token = login.token;
  if (!token) throw new Error("admin login failed");

  const ws = await api("POST", "/admin/workspaces", { name: WS_NAME }, token);
  const wsId = ws.id;

  const conn = await api("POST", `/console/workspaces/${wsId}/model-connections`, {
    name: CONN, modelId: LLM_MODEL, baseUrl: LLM_BASE, apiKey: LLM_KEY,
  }, token);
  await waitFor(async () => {
    const r = await api("POST", `/console/workspaces/${wsId}/model-connections/${conn.id}/test`, undefined, token);
    return r.ok === true;
  }, 60000, 2000);

  const tool = await api("POST", `/console/workspaces/${wsId}/tools`, {
    name: TOOL,
    transport: "streamable_http",
    endpoint: "http://oaf-platform-backend.agent-platform.svc.cluster.local:8080/mcp",
    authType: "none",
    timeoutSec: 30,
  }, token);
  await api("POST", `/console/workspaces/${wsId}/tools/${tool.id}/verify`, undefined, token);

  const skill = await api("POST", `/console/workspaces/${wsId}/skill-center`, {
    name: SKILL,
    displayName: `UI Skill ${TS}`,
    description: "console ui e2e skill",
    tags: ["e2e", "ui"],
    content: `# ${SKILL}\n\nUI e2e skill body.`,
    publishNow: true,
  }, token);

  // 导入用公共包（legacy 上传，workspaceId=0 对所有空间可见）
  const zipPath = path.join(__dirname, "fixtures", "demo-agent-v1.zip");
  const fd = new FormData();
  fd.append("file", new Blob([fs.readFileSync(zipPath)], { type: "application/zip" }), "demo-agent-v1.zip");
  const pkg = await api("POST", "/packages", fd);

  // 沙箱 Agent：带技能引用，供技能使用情况/引用计数/沙箱总览三处 UI 断言
  const agent = await api("POST", `/console/workspaces/${wsId}/agents`, {
    mode: "harness",
    name: SANDBOX_NAME,
    description: "console ui sandbox agent",
    modelConnections: [{ connectionId: conn.id, isDefault: true }],
    prompt: "你是 UI E2E 沙箱助手。",
    skills: [{ name: SKILL, source: "skill", skillId: skill.id, version: "1.0.0" }],
    subAgents: [],
    mcpServers: [],
    sandbox: { enabled: true, image: "opensandbox/code-interpreter:v1.1.0", timeoutMinutes: 30, memoryMb: 512, cpuCount: 1 },
    replicas: 1,
  }, token);
  const svc = agent.service;
  const terminal = ["running", "deploy_failed", "register_failed", "error"];
  const final = await waitFor(async () => {
    const d = await api("GET", `/console/workspaces/${wsId}/agents/${svc.id}`, undefined, token);
    return terminal.includes(d.status) ? d : null;
  }, 300000, 3000);
  if (final.status !== "running") {
    throw new Error(`sandbox agent not running: ${final.status} (${final.errorMessage || ""})`);
  }

  console.log(`   workspace=${wsId} conn=${conn.id} tool=${tool.id} skill=${skill.id} pkg=${pkg.id} sandboxAgent=${svc.id}`);
  return { token, wsId, connId: conn.id, toolId: tool.id, skillId: skill.id, pkgId: pkg.id, svcId: svc.id, svcName: svc.displayName };
}

async function main() {
  fs.mkdirSync(ART, { recursive: true });
  const ctx = await seed();

  const browser = await puppeteer.launch({
    headless: "new",
    args: ["--no-sandbox", "--disable-setuid-sandbox", "--disable-dev-shm-usage"],
  });
  const page = await browser.newPage();
  page.setDefaultTimeout(30000);
  page.on("dialog", (d) => d.accept().catch(() => {}));

  const testid = (id) => `[data-testid="${id}"]`;
  const shot = async (name) => {
    try { await page.screenshot({ path: path.join(ART, `ui-${name}.png`), fullPage: true }); } catch { /* ignore */ }
  };
  const goto = async (p, waitSel) => {
    await page.goto(`${FRONT}${p}`, { waitUntil: "domcontentloaded", timeout: 45000 });
    if (waitSel) await page.waitForSelector(waitSel, { timeout: 30000 });
  };
  const click = async (id) => {
    await page.waitForSelector(testid(id), { visible: true, timeout: 20000 });
    await page.click(testid(id));
  };
  const text = async (sel) => page.$eval(sel, (el) => el.textContent || "").catch(() => "");

  try {
    // ---- 登录 ----
    console.log("== UI：登录 ==");
    await goto("/login", testid("login-page"));
    await page.type(testid("login-username"), ADMIN_USER);
    await page.type(testid("login-password"), ADMIN_PASS);
    await click("login-submit");
    await page.waitForFunction(() => !location.pathname.startsWith("/login"), { timeout: 30000 });
    check("UI 登录后离开 /login", !page.url().includes("/login"), page.url());

    // 固定工作空间，避免依赖切换器默认选择
    await page.evaluate((id) => window.localStorage.setItem("oaf-console-ws", String(id)), ctx.wsId);

    // ---- Agent 列表 ----
    console.log("== UI：Agent 列表 ==");
    await goto("/console/agents", testid("console-agents"));
    await page.waitForSelector(testid(`agent-card-${ctx.svcId}`), { timeout: 30000 }).catch(() => {});
    check("Agent 列表页渲染", !!(await page.$(testid("console-agents"))));
    check("Agent 列表含沙箱 Agent 卡片", !!(await page.$(testid(`agent-card-${ctx.svcId}`))), await text(testid("agents-grid")));
    check("创建入口可见（admin）", !!(await page.$(testid("agents-create"))));

    // ---- 创建 Agent（import 模式，无需 LLM）----
    console.log("== UI：创建 Agent（导入模式）==");
    await goto("/console/agents/create", testid("agent-create"));
    // #136：默认规格必须是 small（micro 的 256Mi limits 会 OOMKilled 托管 Java 运行时）
    await page.waitForSelector(testid("spec-small"), { timeout: 20000 });
    const selectedSpec = await page.$$eval(`${testid("spec-cards")} button[data-selected="true"]`,
      (els) => els.map((el) => el.dataset.testid));
    check("创建页默认规格 small（micro 非默认）",
      selectedSpec.length === 1 && selectedSpec[0] === "spec-small", selectedSpec.join(","));
    await click("mode-import");
    await page.waitForSelector(testid("import-pkg-select"), { timeout: 20000 });
    // 必须等目标包 option 真正加载（占位 option 也会让 length>0 提前通过）
    await page.waitForFunction((sel, id) => {
      const el = document.querySelector(sel);
      return el && el.options && [...el.options].some((o) => o.value === String(id));
    }, { timeout: 30000 }, testid("import-pkg-select"), ctx.pkgId);
    // import 模式不渲染基础信息区（名称由包派生），只需选包后提交
    await page.select(testid("import-pkg-select"), String(ctx.pkgId));
    const pickedPkg = await page.$eval(testid("import-pkg-select"), (el) => el.value);
    check("导入包已选中", pickedPkg === String(ctx.pkgId), pickedPkg);
    // 头部「立即创建」按钮对所有 create 模式可见（import 区块内的提交按钮无 testid）
    await click("agent-submit");
    try {
      await page.waitForFunction(() => /\/console\/agents\/\d+/.test(location.pathname), { timeout: 60000 });
    } catch (e) {
      const msg = await text(testid("agent-form-msg"));
      console.log(`  DBG import form msg: ${msg || "(empty)"}`);
      console.log(`  DBG url: ${page.url()}`);
      throw e;
    }
    const importedId = Number(page.url().match(/\/console\/agents\/(\d+)/)[1]);
    await page.waitForSelector(testid("agent-detail"), { timeout: 30000 });
    check("导入创建后进入详情页", !!importedId, page.url());

    // ---- 详情 / 编辑 ----
    console.log("== UI：Agent 详情/编辑 ==");
    check("详情 Tab 栏渲染", !!(await page.$(testid("detail-tabs"))));
    await click("tab-versions");
    await page.waitForSelector(testid("versions-table"), { timeout: 20000 });
    check("版本 Tab 表格渲染", !!(await page.$(testid("versions-table"))));
    await click("detail-edit");
    await page.waitForSelector(testid("agent-edit"), { timeout: 30000 });
    check("编辑页渲染", !!(await page.$(testid("agent-edit"))));
    check("编辑页含沙箱区块", !!(await page.$(testid("sandbox-switch"))));
    check("编辑页含资源规格区块", !!(await page.$(testid("spec-cards"))));
    await page.goBack({ waitUntil: "domcontentloaded" });

    // ---- 模型连接 ----
    console.log("== UI：模型连接 ==");
    await goto("/console/assets/models", testid("console-models"));
    check("模型连接页渲染", !!(await page.$(testid("console-models"))));
    await click("mc-create-btn");
    await page.waitForSelector(testid("conn-drawer"), { timeout: 20000 });
    await page.type(testid("mc-name"), `ui-new-conn-${TS}`);
    await page.type(testid("mc-model"), LLM_MODEL);
    await page.type(testid("mc-url"), LLM_BASE);
    await page.type(testid("mc-key"), LLM_KEY);
    await click("mc-save");
    await page.waitForFunction((name) => {
      const el = document.querySelector('[data-testid="conn-list"]');
      return el && el.textContent.includes(name);
    }, { timeout: 30000 }, `ui-new-conn-${TS}`);
    check("新建模型连接出现在列表", (await text(testid("conn-list"))).includes(`ui-new-conn-${TS}`));

    // ---- 工具管理 ----
    console.log("== UI：工具管理 ==");
    await goto("/console/assets/tools", testid("console-tools"));
    check("工具页渲染", !!(await page.$(testid("console-tools"))));
    await click("tool-create-btn");
    await page.waitForSelector(testid("tool-drawer"), { timeout: 20000 });
    await page.type(testid("tool-name"), `ui-new-tool-${TS}`);
    await page.type(testid("tool-endpoint"), "http://oaf-platform-backend.agent-platform.svc.cluster.local:8080/mcp");
    await click("tool-save");
    await waitFor(async () => (await text(testid("conn-list"))).includes(`ui-new-tool-${TS}`), 45000, 1000);
    check("新建 MCP 连接器出现在列表", (await text(testid("conn-list"))).includes(`ui-new-tool-${TS}`));
    // 选中连接器 → 权限表
    await page.evaluate(() => {
      const el = document.querySelector('[data-testid="conn-list"]');
      const row = el && [...el.querySelectorAll("tr")].find((r) => r.textContent.includes("ui-new-tool-"));
      if (row) row.click();
    });
    check("工具权限表渲染", !!(await page.$(testid("perm-table"))));

    // ---- 技能中心 ----
    console.log("== UI：技能中心 ==");
    await goto("/console/assets/skills", testid("console-skills"));
    await page.waitForSelector(testid(`skill-row-${SKILL}`), { timeout: 30000 }).catch(() => {});
    check("技能中心渲染", !!(await page.$(testid("console-skills"))));
    check("技能资产行存在", !!(await page.$(testid(`skill-row-${SKILL}`))), await text(testid("skill-center-table")));
    await click(`skill-detail-${SKILL}`);
    await page.waitForSelector(testid("skill-detail-drawer"), { timeout: 20000 });
    check("技能详情抽屉渲染", !!(await page.$(testid("skill-detail-drawer"))));
    check("技能版本表渲染", !!(await page.$(testid("skill-version-table"))));
    check("技能 1.0.0 版本行存在", !!(await page.$(testid("skill-version-1.0.0"))));
    // Skill 详情是页面内自定义抽屉（无 drawer-close），点遮罩左上角关闭
    await page.mouse.click(10, 10);
    await page.waitForSelector(testid("skill-detail-drawer"), { hidden: true, timeout: 10000 }).catch(() => {});
    await click("skill-tab-market");
    await page.waitForSelector(testid("skills-market"), { timeout: 20000 });
    check("市场 Tab 源选择器渲染", !!(await page.$(testid("skill-market-source"))));
    await click("skill-tab-usage");
    await page.waitForSelector(testid("skills-usage"), { timeout: 20000 });
    await page.waitForSelector(testid(`usage-row-${ctx.svcId}`), { timeout: 30000 }).catch(() => {});
    check("使用情况 Tab 渲染", !!(await page.$(testid("skill-usage-table"))));
    check("使用情况含沙箱 Agent 行", !!(await page.$(testid(`usage-row-${ctx.svcId}`))), await text(testid("skill-usage-table")));

    // ---- 沙箱配置总览 ----
    console.log("== UI：沙箱配置 ==");
    await goto("/console/assets/sandbox", testid("console-sandbox"));
    await page.waitForFunction((name) => {
      const el = document.querySelector('[data-testid="sandbox-table"]');
      return el && el.textContent.includes(name);
    }, { timeout: 30000 }, SANDBOX_NAME).catch(() => {});
    check("沙箱总览页渲染", !!(await page.$(testid("console-sandbox"))));
    check("沙箱总览表格渲染", !!(await page.$(testid("sandbox-table"))));
    check("沙箱总览含沙箱 Agent", (await text(testid("sandbox-table"))).includes(SANDBOX_NAME), await text(testid("sandbox-table")));

    if (RUNTIME === "mock") {
      console.log("  ⏭️  E2E_AGENT_RUNTIME=mock：跳过会话/调试类 UI 断言");
    }
  } catch (e) {
    fail++;
    failed.push("uncaught");
    console.error(`\n\x1b[31mUI E2E 异常：${e.stack || e}\x1b[0m`);
    await shot("failure");
  } finally {
    await browser.close();
  }

  console.log(`\n== UI 结果：pass=${pass} fail=${fail} ==`);
  if (fail > 0) {
    console.log(`failed: ${failed.join(", ")}`);
    process.exit(1);
  }
}

main().catch(async (e) => {
  console.error(e.stack || e);
  process.exit(1);
});
