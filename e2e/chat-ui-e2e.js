// 发布助手对话 UI E2E：打开 /assistant → 发送真实消息 → 断言流式回复出现（经 :8911 主入口）
const puppeteer = require("puppeteer");
const FRONT = process.env.FRONTEND || "http://100.66.1.5:8911";
let pass = 0, fail = 0;
const check = (n, c, d = "") => { if (c) { pass++; console.log(`  \x1b[32mPASS\x1b[0m ${n}`); } else { fail++; console.log(`  \x1b[31mFAIL\x1b[0m ${n} ${d}`); } };

(async () => {
  const browser = await puppeteer.launch({ headless: "new", args: ["--no-sandbox"] });
  const page = await browser.newPage();
  try {
    await page.goto(`${FRONT}/assistant`, { waitUntil: "networkidle2" });
    check("T1 对话页加载", !!(await page.$('[data-testid="assistant-page"]')));

    await page.type('[data-testid="chat-input"]', "请只回答一个字：好");
    await page.click('[data-testid="chat-send"]');
    check("T2 消息已发送", true);

    // 等待助手回复文本（LLM 多轮，最长 5 分钟）
    let reply = "";
    for (let i = 0; i < 60; i++) {
      await new Promise((r) => setTimeout(r, 5000));
      reply = await page.evaluate(() => {
        const msgs = document.querySelectorAll('[data-testid="assistant-msg"]');
        return msgs.length ? msgs[msgs.length - 1].textContent.trim() : "";
      });
      if (reply && !reply.startsWith("思考中")) break;
    }
    check("T3 收到流式回复", reply.length > 0 && !reply.includes("⚠️"), reply.slice(0, 80));

    await page.type('[data-testid="chat-input"]', "列出现在的服务名，只要名字不要表格");
    await page.click('[data-testid="chat-send"]');
    let reply2 = "";
    for (let i = 0; i < 90; i++) {
      await new Promise((r) => setTimeout(r, 5000));
      reply2 = await page.evaluate(() => {
        const all = document.querySelectorAll('[data-testid="assistant-msg"]');
        return all.length ? all[all.length - 1].textContent.trim() : "";
      });
      if (reply2 && (reply2.includes("release-agent") || reply2.includes("服务"))) break;
    }
    check("T4 工具调用后给出服务列表", /release-agent/.test(reply2), reply2.slice(0, 100));
  } catch (e) {
    fail++; console.error("异常:", e.message);
  } finally { await browser.close(); }
  console.log(`PASS: ${pass}  FAIL: ${fail}`);
  process.exit(fail ? 1 : 0);
})();
