// E2E 场景 D：Puppeteer 驱动前端 UI 完成发布→列表→详情→env 编辑→重发布→下线→删除
// 用法: FRONTEND=http://172.20.0.3:30881 node ui-e2e.js
const puppeteer = require("puppeteer");
const fs = require("fs");
const path = require("path");

const FRONT = process.env.FRONTEND || "http://172.20.0.3:30881";
const ZIP = path.join(__dirname, "fixtures", "demo-agent-v1.zip");
let pass = 0, fail = 0;
const failed = [];
const check = (name, cond, detail = "") => {
  if (cond) { pass++; console.log(`  \x1b[32mPASS\x1b[0m ${name}`); }
  else { fail++; failed.push(name); console.log(`  \x1b[31mFAIL\x1b[0m ${name} ${detail}`); }
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const browser = await puppeteer.launch({ headless: "new", args: ["--no-sandbox"] });
  const page = await browser.newPage();
  page.setDefaultTimeout(30000);
  try {
    console.log("== 场景 D：前端 UI 流程 ==");

    // D0 列表页可达
    await page.goto(`${FRONT}/`, { waitUntil: "networkidle2" });
    check("D0 列表页加载", !!(await page.$('[data-testid="services-page"]')));

    // D1 进入发布向导
    await page.click('[data-testid="publish-entry"]');
    await page.waitForSelector('[data-testid="publish-page"]');
    check("D1 发布向导页", true);

    // D2 上传 zip（file input 虽 hidden，可直接 uploadFile）
    const input = await page.$('input[data-testid="zip-input"]');
    await input.uploadFile(ZIP);
    await page.waitForFunction(
      () => document.querySelector('[data-testid="pkg-summary"]')?.textContent?.includes("@"),
      { timeout: 15000 });
    check("D2 上传并解析出包摘要", true);

    // D3 填写运行时 env
    const runtimeEnv = {
      LLM_API_KEY: process.env.LLM_API_KEY || "",
      LLM_MODEL_ID: process.env.LLM_MODEL || "",
      LLM_BASE_URL: process.env.LLM_ENDPOINT || "",
      CHECKPOINT_JDBC_URL: "jdbc:mysql://oaf-mysql.agent-platform.svc.cluster.local:3306/oaf_checkpoint?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
      CHECKPOINT_USERNAME: "oaf",
      CHECKPOINT_PASSWORD: "OafPlatform2026",
    };
    let idx = 0;
    for (const [k, v] of Object.entries(runtimeEnv)) {
      const keySel = `[data-testid="env-key-${idx}"]`;
      const valSel = `[data-testid="env-value-${idx}"]`;
      if (!(await page.$(keySel))) break;
      await page.click(keySel, { clickCount: 3 });
      await page.type(keySel, k);
      await page.click(valSel, { clickCount: 3 });
      await page.type(valSel, v);
      idx++;
      // 行不够则添加
      if (idx < Object.keys(runtimeEnv).length && !(await page.$(`[data-testid="env-key-${idx}"]`))) {
        await page.click('[data-testid="add-env-row"]');
      }
    }
    check("D3 env 编辑器填写完成", idx >= 6);

    // D4 提交发布 → 跳转详情
    await page.click('[data-testid="submit-publish"]');
    await page.waitForSelector('[data-testid="detail-page"]', { timeout: 20000 });
    const url = page.url();
    const svcId = url.match(/\/services\/(\d+)/)?.[1];
    check("D4 提交后跳转详情页", !!svcId, url);

    // D5 详情轮询至 running（最长 5 分钟）
    let running = false;
    for (let i = 0; i < 60; i++) {
      await sleep(5000);
      const st = await page.$eval('[data-testid="detail-status"]', (el) => el.textContent.trim()).catch(() => "?");
      if (st === "running") { running = true; break; }
      if (["deploy_failed", "error"].includes(st)) break;
    }
    check("D5 UI 状态轮询至 running", running);

    // D6 Agent Card 展示
    const cardShown = await page.$eval('[data-testid="agent-card"]', (el) => el.textContent).catch(() => "");
    check("D6 Agent Card 渲染", cardShown.includes("E2E Demo Agent"));

    // D7 重新发布按钮
    await page.click('[data-testid="republish-btn"]');
    await sleep(2000);
    const stAfterRepublish = await page.$eval('[data-testid="detail-status"]', (el) => el.textContent.trim());
    check("D7 重发布进入 deploying", ["deploying", "running"].includes(stAfterRepublish), stAfterRepublish);
    for (let i = 0; i < 60; i++) {
      await sleep(5000);
      const st = await page.$eval('[data-testid="detail-status"]', (el) => el.textContent.trim()).catch(() => "?");
      if (st === "running") break;
      if (["deploy_failed", "error"].includes(st)) break;
    }

    // D8 返回列表可见该服务
    await page.goto(`${FRONT}/`, { waitUntil: "networkidle2" });
    const rowVisible = !!(await page.$(`[data-testid="svc-row-${svcId}"]`));
    check("D8 列表出现新服务行", rowVisible);

    // D9 列表删除该服务
    page.on("dialog", (d) => d.accept());
    await page.evaluate((id) => {
      const row = document.querySelector(`[data-testid="svc-row-${id}"]`);
      row.querySelectorAll("button").forEach((b) => { if (b.textContent === "删除") b.click(); });
    }, svcId);
    await sleep(3000);
    const gone = !(await page.$(`[data-testid="svc-row-${svcId}"]`));
    check("D9 删除后行消失", gone);
  } catch (e) {
    fail++; failed.push("异常中断: " + e.message);
    console.error("E2E 异常:", e.message);
  } finally {
    await browser.close();
  }
  console.log("----------------------------------------");
  console.log(`PASS: ${pass}  FAIL: ${fail}`);
  if (fail > 0) { console.log(failed.join("\n")); process.exit(1); }
})();
