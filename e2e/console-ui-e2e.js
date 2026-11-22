#!/usr/bin/env node
/**
 * 控制台浏览器 E2E（Puppeteer）：登录 → 工作空间 → Agent 列表/创建/详情/编辑 →
 * 模型连接 → 工具（MCP）→ 技能中心 → 沙箱配置总览 → 沙箱管理（admin）+ 分页回归（issue #298）
 * → 沙箱实例（console）→ 会话回放（issue #386 回归：回放初始会话取 replayTo 而非调试 persistKey 残留）。
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
const { execSync } = require("node:child_process");
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

// mock-sandbox 直连通道（admin/sandboxes 分页造数用；backend 无创建沙箱代理端点，
// api.ts 仅 list/delete——造数只能进 mock 容器打本机 8090，m12-sandbox-admin.sh 先例同路径）。
// mock 仅存在于 CI overlay + 本地预置：无 svc 时调用方跳过相应小节
const hasMockSandbox = () => {
  try { execSync("kubectl -n agent-platform get svc mock-sandbox", { stdio: "pipe" }); return true; }
  catch { return false; }
};
const mockNode = (js) => execSync("kubectl -n agent-platform exec -i deploy/mock-sandbox -- node", { input: js, encoding: "utf8" });
// 清空 mock 全量沙箱（内存 Map 跨步骤残留——失败运行/X 组遗留——不清基线分页计数必 flaky）
const MOCK_CLEAR = `
const base = "http://127.0.0.1:8090";
const j = async (m, p, b) => {
  const r = await fetch(base + p, { method: m, headers: { "content-type": "application/json" }, body: b === undefined ? undefined : JSON.stringify(b) });
  const t = await r.text();
  return t ? JSON.parse(t) : null;
};
for (let g = 0; g < 50; g++) {
  const pg = await j("GET", "/v1/sandboxes?pageSize=200");
  const items = pg.items || [];
  if (!items.length) break;
  for (const it of items) await j("DELETE", "/v1/sandboxes/" + it.id);
}
console.log("cleared");
`;

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
    // #375：perm-table 按 verify 快照回填行 + 三态编辑保存持久化 + platform 双口径标注
    await waitFor(async () => (await text(testid("perm-table"))).includes("list_services"), 30000, 1000);
    check("perm-table 回填快照工具行（含 list_services）", (await text(testid("perm-table"))).includes("list_services"));
    await page.select(testid("perm-select-list_services"), "deny");
    await click("perm-save");
    await waitFor(async () => (await text("body")).includes("权限策略已保存"), 30000, 500);
    check("权限策略保存反馈", (await text("body")).includes("权限策略已保存"));
    await goto("/console/assets/tools", testid("console-tools"));
    await waitFor(async () => (await text(testid("conn-list"))).includes(`ui-new-tool-${TS}`), 30000, 1000);
    await page.evaluate(() => {
      const el = document.querySelector('[data-testid="conn-list"]');
      const row = el && [...el.querySelectorAll("tr")].find((r) => r.textContent.includes("ui-new-tool-"));
      if (row) row.click();
    });
    await page.waitForSelector(testid("perm-select-list_services"), { timeout: 30000 });
    check("重新进入页面 deny 持久化", (await page.$eval(testid("perm-select-list_services"), (el) => el.value)) === "deny");
    const platPerm = await text(testid("platform-perm"));
    check("platform-mcp 行声明口径标注（不再含 ask 全量）",
      platPerm.includes("声明") && platPerm.includes("allow") && platPerm.includes("ask（HITL）")
      && !(await text(testid("conn-list"))).includes("ask 全量"));

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

    // ---- 运维侧沙箱管理（§6.1：汇总 StatCard + 池卡片区 + 沙箱实例表；只冒烟渲染，不做池 CRUD）----
    console.log("== UI：沙箱管理（admin）==");
    await goto("/admin/sandboxes", testid("sandbox-admin"));
    // summary/沙箱列表为接口拉取后渲染；空数据/未配置也应有页面框架（表格无条件渲染，空态行「暂无沙箱」）
    await page.waitForSelector(testid("sandbox-table"), { timeout: 30000 }).catch(() => {});
    check("沙箱管理页渲染", !!(await page.$(testid("sandbox-admin"))));
    check("沙箱管理汇总卡渲染", !!(await page.$(testid("sb-stat-pools"))) && !!(await page.$(testid("sb-stat-running"))));
    check("沙箱管理池区块渲染", !!(await page.$(testid("pool-create-open"))));
    check("沙箱管理实例表渲染", !!(await page.$(testid("sandbox-table"))));

    // ---- 沙箱管理分页（issue #298 回归：第 50 条之后的沙箱可达可销毁 + 空尾页兜底回退）----
    console.log("== UI：沙箱管理分页（admin）==");
    // 页码指示 span 无 testid，定位下一页按钮所在 footer 内匹配「n / n」的 span（池卡片
    // 的 available/allocated 计数在 <p>/<b> 内，不与本模式冲突）
    const pageIndicator = () => page.evaluate(() => {
      const next = document.querySelector('[data-testid="sandbox-page-next"]');
      const hit = next && [...next.parentElement.querySelectorAll("span")]
        .find((s) => /^\d+ \/ \d+$/.test((s.textContent || "").trim()));
      return hit ? hit.textContent.trim() : "";
    });
    const sandboxRows = () => page.$$eval('[data-testid^="sandbox-row-"]', (els) => els.length);
    if (hasMockSandbox()) {
      mockNode(MOCK_CLEAR);
      mockNode(`${MOCK_CLEAR}
for (let i = 1; i <= 51; i++) {
  await j("POST", "/v1/sandboxes", { metadata: { service: "ui-e2e-admin", session_id: "ui-e2e-page-" + i }, timeout: 1800 });
}
console.log("seeded");`);
      await goto("/admin/sandboxes", testid("sandbox-admin"));
      await page.waitForFunction(() => document.querySelectorAll('[data-testid^="sandbox-row-"]').length === 50, { timeout: 30000 }).catch(() => {});
      const rowsP1 = await sandboxRows();
      check("分页第 1 页恰 50 行（PAGE_SIZE=50）", rowsP1 === 50, `rows=${rowsP1}`);
      check("第 51 条使下一页可点（hasNextPage 透传）",
        (await page.$eval(testid("sandbox-page-next"), (el) => el.disabled)) === false);
      check("页码指示 1 / 2", (await pageIndicator()) === "1 / 2", await pageIndicator());

      await click("sandbox-page-next");
      // 页码指示随 page state 先行更新（React 重渲染早于重拉完成），数据行数才是终态信号：
      // 等第 2 页行数就位（1 行）再断言指示与行数
      await page.waitForFunction(() => document.querySelectorAll('[data-testid^="sandbox-row-"]').length === 1,
        { timeout: 15000 }).catch(() => {});
      check("翻页后页码指示 2 / 2", (await pageIndicator()) === "2 / 2", await pageIndicator());
      const rowsP2 = await sandboxRows();
      check("第 2 页恰 1 行（51 - 50）", rowsP2 === 1, `rows=${rowsP2}`);
      const rowId = await page.$eval('[data-testid^="sandbox-row-"]', (el) => el.dataset.testid.replace("sandbox-row-", ""));
      // confirm 对话框由 page.on(dialog,accept) 自动接受
      await click(`sandbox-destroy-${rowId}`);
      await page.waitForFunction((id) => !document.querySelector(`[data-testid="sandbox-row-${id}"]`),
        { timeout: 15000 }, rowId).catch(() => {});
      check("第 51 条可达可销毁：销毁后该行消失", !(await page.$(testid(`sandbox-row-${rowId}`))));

      // 空尾页兜底回退（page.tsx load() 不变量：page=2 空列表 → setPage(Math.max(1, totalPages))，
      // 回退重拉后不再满足 page>1，无死循环——此处为该不变量的 UI 级证实）
      // 同上：指示先变 1/1、行数待回退重拉完成，以行数=50 为终态信号
      await page.waitForFunction(() => document.querySelectorAll('[data-testid^="sandbox-row-"]').length === 50,
        { timeout: 15000 }).catch(() => {});
      check("空尾页兜底回退：页码指示回 1 / 1", (await pageIndicator()) === "1 / 1", await pageIndicator());
      const rowsBack = await sandboxRows();
      check("回退后第 1 页仍满 50 行", rowsBack === 50, `rows=${rowsBack}`);
      await sleep(5500); // 覆盖至少一个 5s 轮询周期，状态稳定 = 无回退死循环
      check("回退状态稳定（无死循环振荡）",
        (await pageIndicator()) === "1 / 1" && (await sandboxRows()) === 50,
        `${await pageIndicator()} rows=${await sandboxRows()}`);
      try { mockNode(MOCK_CLEAR); } catch (e) { console.log(`  ⚠️ mock 沙箱清理失败（下轮造数前会再清基线）：${e.message}`); }
    } else {
      console.log("  ⏭️ SKIP：集群内无 mock-sandbox svc（仅 CI overlay 部署）——分页小节跳过，渲染冒烟保持");
    }

    // ---- 用户侧沙箱实例（§6.2：本工作空间只读视图，空态亦渲染表格框架）----
    console.log("== UI：沙箱实例（console）==");
    await goto("/console/assets/sandbox-instances", testid("console-sandbox-instances"));
    await page.waitForSelector(testid("sandbox-instances-table"), { timeout: 30000 }).catch(() => {});
    check("沙箱实例页渲染", !!(await page.$(testid("console-sandbox-instances"))));
    check("沙箱实例表渲染", !!(await page.$(testid("sandbox-instances-table"))));

    // ---- 工作空间配置（跨集群导出 §10：runtime-defaults 单端点，掩码 + 三态）----
    console.log("== UI：工作空间配置 ==");
    await goto("/console/workspace-config", testid("ws-config-page"));
    check("空间配置页渲染", !!(await page.$(testid("ws-config-page"))));
    check("空间配置页签栏渲染", !!(await page.$(testid("ws-config-tabs"))));

    // ---- 导出发布页（跨集群导出 §9：源包编辑/标记/env 锁定行/派生键预览）----
    console.log("== UI：导出发布页 ==");
    await goto(`/console/agents/${ctx.svcId}/export`, testid("export-page"));
    check("导出页渲染", !!(await page.$(testid("export-page"))));
    // marks-table / env-table 为无条件渲染区块（preview 拉取后即出现）
    await page.waitForSelector(testid("marks-table"), { timeout: 30000 }).catch(() => {});
    check("发布标记列表区块渲染", !!(await page.$(testid("marks-table"))));
    check("生产环境变量表渲染", !!(await page.$(testid("env-table"))));

    // ---- 会话回放（issue #386 回归：回放面板初始会话必须取 replayTo，而非调试 persistKey 残留——
    // 修复前回放 B 渲染上一次持久化会话内容，「滞后一次」错配必现）----
    // 走带模型连接的沙箱 Agent（seed 创建时已挂 verified 连接 → LLM env 注入，导入模式 Agent 无 LLM 不可对话）
    console.log("== UI：会话回放（Agent 详情）==");
    if (RUNTIME === "mock") {
      console.log("  ⏭️ SKIP：E2E_AGENT_RUNTIME=mock——会话/调试类断言跳过");
    } else {
      const detail = await api("GET", `/console/workspaces/${ctx.wsId}/agents/${ctx.svcId}`, undefined, ctx.token);
      const me = await api("GET", "/auth/me", undefined, ctx.token);
      const debugUid = `u-${me.user.id}`;
      // 会话清单经浏览器上下文拉取（/agent/* 同源带 console 会话 cookie，与 ThreadsTab 同链路）；
      // 不过滤前缀：new-session 建的会话 sid 为 <userId>-…（ChatPane.resetSession），与 ThreadsTab 展示口径一致
      const listSids = () => page.evaluate(async (p) => {
        const list = await fetch(p).then((r) => r.json()).catch(() => []);
        return (Array.isArray(list) ? list : []).map((t) => String(t.session_id));
      }, `/agent/${detail.shortName}/threads?userId=${encodeURIComponent(debugUid)}`);
      // 用户气泡契约选择器（MessageItem 结构约定，file-support-ui 同源）：会话回放侧栏含全部
      // 会话标题，正文断言必须圈定在消息流用户气泡内，不能用整 pane textContent
      const userMsgs = (pane) => page.$$eval(`${testid(pane)} .justify-end > div`,
        (els) => els.map((el) => (el.textContent || "").trim())).catch(() => []);
      // 输入框可用 ⇔ sessionLocked 复位（busy/historyLoading/uploading 均锁定；chat-send 空输入恒 disabled，不能当就绪信号）
      const waitReady = (timeoutMs) => page.waitForFunction(() => {
        const el = document.querySelector('[data-testid="chat-input"]');
        return !!el && !el.disabled;
      }, { timeout: timeoutMs });
      // 调试 Tab 建会话：等就绪 → 发送 → 等用户气泡上屏（本轮已开始）→ 等 busy 复位 → checkpoint 落库余量
      const sendAndSettle = async (msg) => {
        await waitReady(60000);
        await page.$eval(testid("chat-input"), (el) => { el.value = ""; el.dispatchEvent(new Event("input", { bubbles: true })); });
        await page.type(testid("chat-input"), msg);
        await page.click(testid("chat-send"));
        await waitFor(async () => (await userMsgs("debug-pane")).some((t) => t.includes(msg)), 30000, 1000);
        await waitReady(300000);
        await sleep(5000);
      };

      const markerA = `E2E 回放标记甲 ${TS}`;
      const markerB = `E2E 回放标记乙 ${TS}`;
      await goto(`/console/agents/${ctx.svcId}?tab=debug`, testid("debug-pane"));
      await sendAndSettle(markerA);
      await click("new-session");
      await sleep(3000); // 两会话创建时刻错秒，sid 前缀定序不致并列
      await sendAndSettle(markerB);

      await goto(`/console/agents/${ctx.svcId}?tab=threads`, testid("threads-table"));
      await page.waitForSelector(testid("threads-table"), { timeout: 30000 });
      await waitFor(async () => (await listSids()).length >= 2, 30000, 1000);
      const sids = await listSids();
      check("会话 Tab 列表含新建两会话", sids.length >= 2, `sids=${sids.length}`);
      // 本 Agent 为本次运行全新创建，会话仅此两个；sid 尾两段恒为 <毫秒36>-<rand>（webui- 与 <userId>- 两种前缀皆然），定序不依赖列表排序
      const sidTs = (s) => parseInt(s.split("-").at(-2), 36);
      const [sidA, sidB] = [...sids].sort((a, b) => sidTs(a) - sidTs(b));

      // 回放 → 断言正文为所选会话历史 + 顶部条 id 与目标一致（副标题「回放 <sid>」同源）→ 返回列表
      const replayAndCheck = async (sid, marker, other, label) => {
        await click(`thread-replay-${sid}`);
        await page.waitForSelector(testid("replay-pane"), { timeout: 20000 });
        await waitFor(async () => (await userMsgs("replay-pane")).some((t) => t.includes(marker)), 60000, 2000);
        const msgs = await userMsgs("replay-pane");
        check(`${label}：正文为所选会话历史（含标记、无另一会话标记）`,
          msgs.some((t) => t.includes(marker)) && msgs.every((t) => !t.includes(other)),
          msgs.join(" | ").slice(0, 120));
        const topId = await page.$eval(`${testid("replay-pane")} .font-mono`, (el) => (el.textContent || "").trim());
        check(`${label}：顶部条会话 id === 回放目标`, topId === sid, `top=${topId} want=${sid}`);
        await click("threads-back");
        await page.waitForSelector(testid("threads-table"), { timeout: 20000 });
      };
      await replayAndCheck(sidA, markerA, markerB, "回放会话 A");
      // 切换目标回放 B：修复前此处必渲染 A 的内容（persistKey 残留），为错配回归的核心断言
      await replayAndCheck(sidB, markerB, markerA, "回放会话 B（切换目标）");

      // 回归：回放把「当前会话」补写进 persistKey（与 selectSession 同语义）→ 调试 Tab 恢复点 = 最后回放的 B
      await goto(`/console/agents/${ctx.svcId}?tab=debug`, testid("debug-pane"));
      await waitFor(async () => (await userMsgs("debug-pane")).some((t) => t.includes(markerB)), 60000, 2000);
      const dbgTop = await page.$eval(`${testid("debug-pane")} .font-mono`, (el) => (el.textContent || "").trim());
      check("调试 Tab 恢复点随回放切换（persistKey 单真相源）", dbgTop === sidB, `top=${dbgTop} want=${sidB}`);
    }

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
