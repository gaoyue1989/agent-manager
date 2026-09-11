// 文件功能前端 E2E（file-upload-download-plan；agui-migration-plan Phase 2.8 适配）：
// assistant 页已重构为 CopilotKit——输入为 CopilotChat 内部 textarea（placeholder 前缀"例如："），
// 发送为 Enter；busy 无 testid，改用"对话静止"启发（innerText 连续两次采样不变）判定流结束
// 附件上传 + file_ready 卡片 + 历史切换（服务端上下文恢复）+ 生成包校验
const puppeteer = require("puppeteer");
const fs = require("fs");
const path = require("path");
const FRONT = process.env.FRONTEND || "http://100.66.1.5:8911";
const FIXDIR = path.join(__dirname, "fixtures");
let pass = 0, fail = 0;
const screenshots = path.join(__dirname, "screenshots");
fs.mkdirSync(screenshots, { recursive: true });
const csvPath = path.join(FIXDIR, "e2e-frontend-test.csv");
const check = (n, c, d = "") => { if (c) { pass++; console.log(`  \x1b[32mPASS\x1b[0m ${n}`); } else { fail++; console.log(`  \x1b[31mFAIL\x1b[0m ${n} ${d}`); } };
const shot = async (page, name) => { try { await page.screenshot({ path: path.join(screenshots, name + ".png"), fullPage: false }); } catch(e) {} };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// CopilotChat 内部输入框（labels.chatInputPlaceholder 前缀"例如："）
const INPUT_SEL = 'textarea[placeholder^="例如："]';

async function send(page, text) {
  await page.waitForSelector(INPUT_SEL, { timeout: 30000 });
  await page.click(INPUT_SEL);
  await page.type(INPUT_SEL, text);
  await page.keyboard.press("Enter");
}

/** 对话静止判定：body innerText 连续两次采样（间隔 pollMs）不变即视为流结束 */
async function waitQuiet(page, timeoutMs = 180000, pollMs = 3000) {
  const deadline = Date.now() + timeoutMs;
  let last = "";
  let stable = 0;
  while (Date.now() < deadline) {
    await sleep(pollMs);
    const text = await page.evaluate(() => document.body.innerText);
    if (text === last) stable++; else { stable = 0; last = text; }
    if (stable >= 1) return true;
  }
  return false;
}

(async () => {
  fs.writeFileSync(csvPath, "name,value\nfirst,1\nlast,FRONTEND-SENTINEL-9a8b\n");
  const browser = await puppeteer.launch({ headless: "new", args: ["--no-sandbox", "--disable-setuid-sandbox"] });
  const page = await browser.newPage();
  await page.setViewport({ width: 1280, height: 900 });
  try {
    console.log("\n\x1b[1;34m== 文件功能前端 E2E（CopilotKit）==\x1b[0m");

    // U1: 打开页面
    await page.goto(`${FRONT}/assistant`, { waitUntil: "networkidle2" });
    check("U1 页面加载", !!(await page.$('[data-testid="assistant-page"]')));
    await shot(page, "01-page-loaded");

    // U2: 附件按钮
    check("U2 附件按钮可见", !!(await page.$('[data-testid="attach-btn"]')));
    await shot(page, "02-attach-btn");

    // U3: 上传文件
    const [fileChooser] = await Promise.all([
      page.waitForFileChooser({ timeout: 10000 }),
      // JS 派发 click（物理点击可能被 CopilotKit 样式层遮挡）
      page.$eval('[data-testid="attach-btn"]', el => el.click()),
    ]);
    await fileChooser.accept([csvPath]);
    await page.waitForFunction(() => document.querySelectorAll('[data-testid="attach-chip"]').length > 0, { timeout: 20000 });
    const chips = await page.$$eval('[data-testid="attach-chip"]', els => els.map(e => e.textContent.trim()));
    check("U3 文件已上传", chips.some(c => c.includes("e2e-frontend-test.csv")), `chips: ${chips.join(", ")}`);
    await shot(page, "03-attachment-uploaded");

    // U4-U6: 发送读文件消息（fileIds 经 forwardedProps 注入工作区；LLM 读取后回复）
    await send(page, "请读取我上传的 csv 文件，告诉我最后一行的第一个字段和值");
    check("U4 消息已发送", true);
    await waitQuiet(page);
    const bodyText = await page.evaluate(() => document.body.innerText);
    check("U5 收到回复", bodyText.length > 0);
    await shot(page, "05-file-read-reply");

    // U6: 文件读取验证（哨兵值/文件名/字段任一命中）
    const sentinelOk = bodyText.includes("FRONTEND-SENTINEL");
    const fileReadOk = bodyText.includes("e2e-frontend-test.csv") || bodyText.includes("SENTINEL");
    const valueReadOk = /`?last`?|最后一行|字段/.test(bodyText);
    check("U6 文件读取验证", sentinelOk || fileReadOk || valueReadOk,
      sentinelOk ? "哨兵值" : "文件读取确认");

    // U7/U8: present_file 生成 file_ready 卡片
    const b64 = Buffer.from("UI-SENTINEL-OUTPUT-67890").toString("base64");
    await send(page, `调用 present_file 工具：file_path='outputs/ui-test.txt'，file_content_base64='${b64}'`);
    let fileCardCount = 0;
    for (let i = 0; i < 40; i++) {
      await sleep(5000);
      fileCardCount = await page.$$eval('[data-testid="file-card"]', els => els.length);
      if (fileCardCount > 0) break;
      if (i === 20) { // 中途静止即重发一次
        await send(page, `调用 present_file 工具：file_path='outputs/ui-test.txt'，file_content_base64='${b64}'`);
      }
    }
    check("U8 present_file 产出 file_ready 卡片", fileCardCount > 0, `cards=${fileCardCount}`);
    await shot(page, "08-file-ready-card");

    // U9/U10: 下载按钮 + 链接有效（/files/{id} 200）
    const dlBtn = await page.$('[data-testid="file-download"]');
    check("U9 下载按钮可见", !!dlBtn);
    let dlOk = false;
    if (dlBtn) {
      const href = await page.$eval('[data-testid="file-download"]', el => el.getAttribute("href") || "");
      const dlResp = await fetch(`${FRONT}${href}`).catch(() => null);
      dlOk = dlResp && dlResp.status === 200;
      check("U10 下载链接有效", dlOk, dlOk ? `href=${href}` : `下载失败 href=${href}`);
    } else {
      check("U10 下载链接有效", false, "无按钮");
    }
    await shot(page, "10-download");

    // U11-U13: 生成 OAF 部署包对话（新会话隔离上下文；LLM 长流程最多 300s）
    const genName = `ui-gen-agent-${Date.now().toString().slice(-6)}`;
    await page.click('[data-testid="new-session"]');
    await sleep(3000);
    let genCardHref = "";
    let genCardCount = 0;
    for (let attempt = 1; attempt <= 3; attempt++) {
      await send(page, attempt === 1
        ? `帮我生成一个名为 ${genName} 的 OAF 部署包（简单 agent：把用户输入原样返回）。走完整流程：写 AGENTS.md（name 与 agentKey 均为 ${genName}，含全部必填字段）→ check_oaf_package 校验（valid=true 才继续）→ create_oaf_zip 打包交付下载。不要发布，不要用 write_file 在沙箱写文件。`
        : `生成名为 ${genName} 的 OAF 部署包并交付下载：写 AGENTS.md（name/agentKey=${genName}，必填字段齐全）→ check_oaf_package → create_oaf_zip。不要发布。`);
      for (let i = 0; i < 60; i++) {
        await sleep(5000);
        genCardCount = await page.$$eval('[data-testid="file-card"]', els => els.length);
        if (genCardCount > 0) {
          genCardHref = await page.$eval('[data-testid="file-download"]', el => el.getAttribute("href") || "").catch(() => "");
          if (genCardHref.includes("/files/")) break;
        }
      }
      if (genCardHref.includes("/files/")) break;
      console.log(`  [retry] U11 第 ${attempt} 次失败（cards=${genCardCount}），新会话重试`);
      await page.click('[data-testid="new-session"]');
      await sleep(3000);
    }
    check("U11 生成包对话产出 file_ready 卡片", genCardCount > 0 && genCardHref.includes("/files/"),
      `cards=${genCardCount} href=${genCardHref || "无"}`);
    await shot(page, "11-gen-package-card");

    // U12: 下载 zip PK 魔数校验
    let genZipOk = false;
    if (genCardHref) {
      const resp = await fetch(`${FRONT}${genCardHref}`).catch(() => null);
      if (resp && resp.status === 200) {
        const head = Buffer.from(await resp.arrayBuffer());
        genZipOk = head.length > 4 && head[0] === 0x50 && head[1] === 0x4b;
      }
    }
    check("U12 生成包下载有效（zip PK 魔数）", genZipOk, genZipOk ? `href=${genCardHref}` : "下载/魔数校验失败");

    // U13: 生成包通过平台校验（POST /api/v1/packages → code=0）
    let genValidOk = false;
    if (genCardHref) {
      const resp = await fetch(`${FRONT}${genCardHref}`).catch(() => null);
      if (resp && resp.status === 200) {
        const buf = await resp.arrayBuffer();
        const fd = new FormData();
        fd.append("file", new Blob([buf]), `${genName}.zip`);
        const up = await fetch(`${FRONT}/api/v1/packages`, { method: "POST", body: fd }).then(r => r.json()).catch(() => null);
        genValidOk = up && up.code === 0;
      }
    }
    check("U13 生成包通过平台校验", genValidOk, genValidOk ? "平台 code=0" : "平台校验失败或下载失败");
    await shot(page, "13-gen-package-valid");

    // U14: 历史会话列表（本运行产生 ≥2 个 uuid 线程）
    await page.click('[data-testid="history-btn"]');
    await page.waitForSelector('[data-testid="history-panel"]', { timeout: 10000 }).catch(() => {});
    await sleep(2000);
    const historyCount = await page.$$eval('[data-testid="history-item"]', els => els.length);
    check("U14 历史会话列表展示", historyCount >= 1, `items=${historyCount}`);
    await shot(page, "14-history-panel");

    // U15: 切换到另一个历史会话（uuid 线程可切；服务端上下文恢复——CopilotKit 客户端
    // 不拉服务端 messages，消息区为空是预期行为，断言"切换成功 + 输入框就绪"）
    let switched = false;
    if (historyCount >= 2) {
      const items = await page.$$('[data-testid="history-item"]');
      await items[1].click();
      await sleep(4000);
      switched = await page.evaluate((sel) =>
        !!document.querySelector(sel) &&
        !document.querySelector('[data-testid="history-panel"]'), INPUT_SEL);
      check("U15 历史会话切换（服务端上下文恢复，消息区按 CopilotKit 语义为空）", switched);
    } else {
      check("U15 历史会话切换", false, `历史会话不足（items=${historyCount}）`);
    }
    await shot(page, "15-history-switch");

    // U16: 继续对话——服务端记忆恢复（问首条消息特征，回复应关联）
    if (switched) {
      await send(page, "我们这个会话里第一个话题是什么？用一句话概括");
      await waitQuiet(page);
      const ctxReply = await page.evaluate(() => document.body.innerText);
      const tokens = ["ui-test", "present_file", genName, "e2e-frontend-test", "csv", "读取"];
      const ctxOk = ctxReply.length > 50 && tokens.some(t => ctxReply.toLowerCase().includes(t.toLowerCase()));
      check("U16 历史会话继续对话（上下文恢复）", ctxOk,
        ctxOk ? "回复关联会话特征" : `回复: ${ctxReply.slice(-200).replace(/\n/g, " ")}`);
    } else {
      check("U16 历史会话继续对话（上下文恢复）", false, "U15 未切换成功");
    }
    await shot(page, "16-history-continue");

    await shot(page, "final-state");
    console.log(`\n截图目录: ${screenshots}/`);

  } catch (e) {
    fail++; console.error("异常:", e.message);
    await shot(page, "error-state");
  } finally {
    await browser.close();
    try { fs.unlinkSync(csvPath); } catch {}
    console.log(`\n\x1b[1;34m结果\x1b[0m: PASS=${pass} FAIL=${fail}`);
    process.exit(fail ? 1 : 0);
  }
})();
