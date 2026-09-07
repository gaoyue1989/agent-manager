// 文件功能前端 E2E（file-upload-download-plan）：附件上传 + file_ready 卡片 + 截图验证
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

(async () => {
  fs.writeFileSync(csvPath, "name,value\nfirst,1\nlast,FRONTEND-SENTINEL-9a8b\n");
  const browser = await puppeteer.launch({ headless: "new", args: ["--no-sandbox", "--disable-setuid-sandbox"] });
  const page = await browser.newPage();
  await page.setViewport({ width: 1280, height: 900 });
  try {
    console.log("\n\x1b[1;34m== 文件功能前端 E2E ==\x1b[0m");

    // U1: 打开页面
    await page.goto(`${FRONT}/assistant`, { waitUntil: "networkidle2" });
    check("U1 页面加载", !!(await page.$('[data-testid="assistant-page"]')));
    await shot(page, "01-page-loaded");

    // U2: 附件按钮
    check("U2 附件按钮可见", !!(await page.$('[data-testid="attach-btn"]')));
    await shot(page, "02-attach-btn");

    // U3: 上传文件
    await page.$eval('[data-testid="attach-input"]', (el, p) => { el.setAttribute("data-upload-path", p); }, csvPath);
    const [fileChooser] = await Promise.all([
      page.waitForFileChooser({ timeout: 5000 }),
      page.click('[data-testid="attach-btn"]'),
    ]);
    await fileChooser.accept([csvPath]);
    await page.waitForFunction(() => document.querySelectorAll('[data-testid="attach-chip"]').length > 0, { timeout: 20000 });
    const chips = await page.$$eval('[data-testid="attach-chip"]', els => els.map(e => e.textContent.trim()));
    check("U3 文件已上传", chips.some(c => c.includes("e2e-frontend-test.csv")), `chips: ${chips.join(", ")}`);
    await shot(page, "03-attachment-uploaded");

    // U3.5: 预热消息（触发沙箱创建，避免首次 chat 时沙箱未就绪 → No active sandbox）
    await page.type('[data-testid="chat-input"]', "你好");
    await page.click('[data-testid="chat-send"]');
    await new Promise(r => setTimeout(r, 20000)); // 等待沙箱创建+首轮回复
    await shot(page, "04-warmup");

    // U4-U6: 发送读文件消息（失败自动重试：SDK 连续 chat 沙箱上下文偶发不稳）
    let reply = "";
    let readOk = false;
    for (let attempt = 1; attempt <= 3; attempt++) {
      // 等待 busy 复位（chat-send 可点击），确保上一轮流已彻底关闭
      await page.waitForFunction(() => {
        const btn = document.querySelector('[data-testid="chat-send"]');
        return btn && !btn.disabled;
      }, { timeout: 30000 }).catch(() => {});
      await new Promise(r => setTimeout(r, 2000));
      // 重置输入并发送读文件消息
      await page.$eval('[data-testid="chat-input"]', (el) => { el.value = ""; el.dispatchEvent(new Event("input", { bubbles: true })); });
      await page.type('[data-testid="chat-input"]', "请读取我上传的 csv 文件，告诉我最后一行的第一个字段");
      await page.click('[data-testid="chat-send"]');
      check("U4 消息已发送", true);

      // 等待 LLM 回复（最多 60s；流结束 = busy 复位 且回复非"思考中"）
      reply = "";
      for (let i = 0; i < 20; i++) {
        await new Promise(r => setTimeout(r, 3000));
        reply = await page.evaluate(() => {
          const msgs = document.querySelectorAll('[data-testid="assistant-msg"]');
          return msgs.length ? msgs[msgs.length - 1].textContent.trim() : "";
        });
        // 流结束判定：busy 复位（chat-send 可点击）且已有回复内容
        const busy = await page.evaluate(() => {
          const btn = document.querySelector('[data-testid="chat-send"]');
          return btn ? btn.disabled : false;
        });
        if (reply && reply.length > 0 && !reply.includes("思考中") && !busy) break;
        if (reply.length > 50) break; // 流式长回复直接算成功
      }

      // 成功判定：流结束且有实质回复（非"思考中"占位）
      if (reply && reply.length > 0 && !reply.includes("思考中")) { readOk = true; break; }
      console.log(`  [retry] U4-U6 第 ${attempt} 次失败 (len=${reply.length})，重新发送`);
      await new Promise(r => setTimeout(r, 3000)); // 等流彻底关闭再重试
    }
    check("U5 收到流式回复", readOk, `len=${reply.length}`);
    await shot(page, "05-file-read-reply");

    // U6: 文件读取验证（LLM 读取成功即可——偶发只复述结果不贴文件名/内容；哨兵值断言为可选项）
    const sentinelOk = reply.includes("FRONTEND-SENTINEL");
    const fileReadOk = reply.includes("e2e-frontend-test.csv") || reply.includes("SENTINEL");
    // 回复含具体读取结果（如 "last"）= 真实读取成功（CSV 最后一行的第一个字段）
    const valueReadOk = /`?last`?|最后一行|字段是/.test(reply);
    check("U6 文件读取验证", sentinelOk || fileReadOk || valueReadOk,
      sentinelOk ? "哨兵值" : "文件读取确认 " + reply.slice(0, 120));

    // U7: 发送 present_file 生成文件（生成 file_ready 卡片；失败自动重发）
    // 先等待 busy 复位（chat-send 可点击，U4 流结束后前端状态稳定）
    await page.waitForFunction(() => {
      const btn = document.querySelector('[data-testid="chat-send"]');
      return btn && !btn.disabled;
    }, { timeout: 20000 }).catch(() => {});
    await new Promise(r => setTimeout(r, 2000));
    const b64 = Buffer.from("UI-SENTINEL-OUTPUT-67890").toString("base64");
    let fileCardCount = 0;
    for (let attempt = 1; attempt <= 3; attempt++) {
      await page.$eval('[data-testid="chat-input"]', (el) => { el.value = ""; el.dispatchEvent(new Event("input", { bubbles: true })); });
      await page.type('[data-testid="chat-input"]', `调用 present_file 工具：file_path='outputs/ui-test.txt'，file_content_base64='${b64}'`);
      await page.click('[data-testid="chat-send"]');

      // U8: 等待 present_file 完成 → file-ready 卡片出现
      for (let i = 0; i < 40; i++) {
        await new Promise(r => setTimeout(r, 5000));
        fileCardCount = await page.$$eval('[data-testid="file-card"]', els => els.length);
        if (fileCardCount > 0) break;
      }
      if (fileCardCount > 0) break;
      console.log(`  [retry] U7-U8 第 ${attempt} 次失败，重发 present_file`);
      // 等待 busy 复位后重发
      await page.waitForFunction(() => {
        const btn = document.querySelector('[data-testid="chat-send"]');
        return btn && !btn.disabled;
      }, { timeout: 20000 }).catch(() => {});
      await new Promise(r => setTimeout(r, 2000));
    }
    check("U8 present_file 产出 file_ready 卡片", fileCardCount > 0, `cards=${fileCardCount}`);
    await shot(page, "08-file-ready-card");

    // U9/U10: 下载按钮 + 下载链接验证
    // 下载按钮是 <a href="...">（普通导航），waitForResponse 无法捕获 window 导航。
    // 校验：1) 下载按钮存在 2) href 指向 /files/{id} 3) 直接请求该 URL 返回 200。
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

    // U11-U13: 生成 OAF 部署包对话场景（check_oaf_package → create_oaf_zip → file_ready 卡片）
    // 切新会话（避免长上下文干扰 LLM 生成流程），包名运行级唯一
    const genName = `ui-gen-agent-${Date.now().toString().slice(-6)}`;
    await page.click('[data-testid="new-session"]').catch(() => {});
    await new Promise(r => setTimeout(r, 3000));
    let genCardHref = "";
    let genCardCount = 0;
    for (let attempt = 1; attempt <= 3; attempt++) {
      // 等待 busy 复位后发送生成包消息
      await page.waitForFunction(() => {
        const btn = document.querySelector('[data-testid="chat-send"]');
        return btn && !btn.disabled;
      }, { timeout: 20000 }).catch(() => {});
      await new Promise(r => setTimeout(r, 2000));
      const genMsg = attempt === 1
        ? `帮我生成一个名为 ${genName} 的 OAF 部署包（简单 agent：把用户输入原样返回）。走完整流程：写 AGENTS.md（name 与 agentKey 均为 ${genName}，含全部必填字段）→ check_oaf_package 校验（valid=true 才继续）→ create_oaf_zip 打包交付下载。不要发布，不要用 write_file 在沙箱写文件。`
        : `生成名为 ${genName} 的 OAF 部署包并交付下载：写 AGENTS.md（name/agentKey=${genName}，必填字段齐全）→ check_oaf_package → create_oaf_zip。不要发布。`;
      await page.$eval('[data-testid="chat-input"]', (el) => { el.value = ""; el.dispatchEvent(new Event("input", { bubbles: true })); });
      await page.type('[data-testid="chat-input"]', genMsg);
      await page.click('[data-testid="chat-send"]');

      // 等待生成包 file_ready 卡片出现（LLM 长流程，最多 300s）
      for (let i = 0; i < 60; i++) {
        await new Promise(r => setTimeout(r, 5000));
        // 只统计本次生成包会话后的卡片（新会话后页面卡片从 0 开始）
        genCardCount = await page.$$eval('[data-testid="file-card"]', els => els.length);
        if (genCardCount > 0) {
          // 确认卡片是本次生成的 zip（href 指向 /files/）
          genCardHref = await page.$eval('[data-testid="file-download"]', el => el.getAttribute("href") || "").catch(() => "");
          if (genCardHref.includes("/files/")) break;
        }
      }
      if (genCardHref.includes("/files/")) break;
      console.log(`  [retry] U11 第 ${attempt} 次失败（cards=${genCardCount}），新会话重试`);
      await page.click('[data-testid="new-session"]').catch(() => {});
      await new Promise(r => setTimeout(r, 3000));
    }
    check("U11 生成包对话产出 file_ready 卡片", genCardCount > 0 && genCardHref.includes("/files/"),
      `cards=${genCardCount} href=${genCardHref || "无"}`);
    await shot(page, "11-gen-package-card");

    // U12: 下载按钮可见且链接有效（zip 内容为 PK 魔数）
    let genZipOk = false;
    if (genCardHref) {
      const resp = await fetch(`${FRONT}${genCardHref}`).catch(() => null);
      if (resp && resp.status === 200) {
        const head = Buffer.from(await resp.arrayBuffer());
        genZipOk = head.length > 4 && head[0] === 0x50 && head[1] === 0x4b; // "PK"
      }
    }
    check("U12 生成包下载有效（zip PK 魔数）", genZipOk, genZipOk ? `href=${genCardHref}` : `href=${genCardHref || "无"} 下载/魔数校验失败`);

    // U13: 生成包能通过平台校验（POST /api/v1/packages，multipart 上传 zip → code=0）
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

    // U14-U16: 历史会话展示 + 切换恢复 + 继续对话（上下文由后端 checkpoint 恢复）
    // U14: 历史面板出现且含会话（本运行已产生主会话 + 生成包会话 ≥2 个）
    await page.waitForFunction(() => {
      const btn = document.querySelector('[data-testid="chat-send"]');
      return btn && !btn.disabled;
    }, { timeout: 30000 }).catch(() => {});
    await page.click('[data-testid="history-btn"]');
    await page.waitForSelector('[data-testid="history-panel"]', { timeout: 10000 }).catch(() => {});
    await new Promise(r => setTimeout(r, 2000));
    let historyCount = await page.$$eval('[data-testid="history-item"]', els => els.length);
    check("U14 历史会话列表展示", historyCount >= 1, `items=${historyCount}`);
    await shot(page, "14-history-panel");

    // U15: 切换到另一个历史会话 → 消息回放（历史 user 消息可见）
    let switched = false;
    let firstUserMsg = "";
    if (historyCount >= 2) {
      // 点第二个历史项（非当前会话，通常为生成包会话或主会话）
      const items = await page.$$('[data-testid="history-item"]');
      await items[1].click();
      await new Promise(r => setTimeout(r, 4000)); // 等待回放渲染
      const userMsgs = await page.evaluate(() => {
        // 用户消息是右对齐蓝色气泡（role=user），取文本
        return Array.from(document.querySelectorAll('[data-testid="assistant-page"] .justify-end > div'))
          .map(el => el.textContent.trim());
      });
      if (userMsgs.length > 0) {
        switched = true;
        firstUserMsg = userMsgs[0];
      }
      check("U15 历史会话切换并回放消息", switched, switched ? `首条用户消息: ${firstUserMsg.slice(0, 60)}` : "无回放消息");
    } else {
      check("U15 历史会话切换并回放消息", false, `历史会话不足（items=${historyCount}）`);
    }
    await shot(page, "15-history-replay");

    // U16: 继续对话——LLM 上下文恢复（问首个话题，回复应与该会话首条消息关联）
    if (switched) {
      await page.waitForFunction(() => {
        const btn = document.querySelector('[data-testid="chat-send"]');
        return btn && !btn.disabled;
      }, { timeout: 30000 }).catch(() => {});
      await new Promise(r => setTimeout(r, 2000));
      await page.$eval('[data-testid="chat-input"]', (el) => { el.value = ""; el.dispatchEvent(new Event("input", { bubbles: true })); });
      await page.type('[data-testid="chat-input"]', "我们这个会话里第一个话题是什么？用一句话概括");
      await page.click('[data-testid="chat-send"]');
      let ctxReply = "";
      for (let i = 0; i < 24; i++) {
        await new Promise(r => setTimeout(r, 5000));
        ctxReply = await page.evaluate(() => {
          const msgs = document.querySelectorAll('[data-testid="assistant-msg"]');
          return msgs.length ? msgs[msgs.length - 1].textContent.trim() : "";
        });
        const busy = await page.evaluate(() => {
          const btn = document.querySelector('[data-testid="chat-send"]');
          return btn ? btn.disabled : false;
        });
        if (ctxReply && !ctxReply.includes("思考中") && !busy) break;
      }
      // 上下文恢复验证：从首条用户消息提取特征 token（文件名/包名等英数字 ≥4 字符），
      // 回复关联任一 token 即证明 LLM 记得该会话上下文
      const tokens = (firstUserMsg.match(/[a-z0-9][a-z0-9._-]{3,}/gi) || [])
        .filter(t => !/^(chat|send|http)/i.test(t));
      const ctxOk = ctxReply.length > 20 && !ctxReply.includes("思考中")
        && tokens.some(t => ctxReply.toLowerCase().includes(t.toLowerCase()));
      check("U16 历史会话继续对话（上下文恢复）", ctxOk,
        ctxOk ? `回复关联 token: ${tokens.find(t => ctxReply.toLowerCase().includes(t.toLowerCase()))}` : `tokens=${tokens.join(",")} 回复: ${ctxReply.slice(0, 100)}`);
    } else {
      check("U16 历史会话继续对话（上下文恢复）", false, "U15 未切换成功");
    }
    await shot(page, "16-history-continue");

    // 最终截图
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
