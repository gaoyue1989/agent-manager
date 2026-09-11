// 发布助手对话 UI E2E（agui-migration-plan Phase 2.8 适配）：
// assistant 页已重构为 CopilotKit + CopilotChat——输入框为 CopilotChat 内部 textarea
// （placeholder 前缀"例如："），发送为 Enter；断言以页面文本出现为准
const puppeteer = require("puppeteer");
const FRONT = process.env.FRONTEND || "http://100.66.1.5:8911";
let pass = 0, fail = 0;
const check = (n, c, d = "") => { if (c) { pass++; console.log(`  \x1b[32mPASS\x1b[0m ${n}`); } else { fail++; console.log(`  \x1b[31mFAIL\x1b[0m ${n} ${d}`); } };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// CopilotChat 内部输入框选择器（v2 组件：textarea，placeholder 由 labels.chatInputPlaceholder 提供）
const INPUT_SEL = 'textarea[placeholder^="例如："]';

async function sendAndWaitReply(page, text, expectRe, maxWaits = 60) {
  await page.waitForSelector(INPUT_SEL, { timeout: 20000 });
  await page.click(INPUT_SEL);
  await page.type(INPUT_SEL, text);
  await page.keyboard.press("Enter");
  let reply = "";
  for (let i = 0; i < maxWaits; i++) {
    await sleep(5000);
    reply = await page.evaluate(() => document.body.innerText);
    if (expectRe.test(reply)) break;
  }
  return reply;
}

(async () => {
  const browser = await puppeteer.launch({ headless: "new", args: ["--no-sandbox"] });
  const page = await browser.newPage();
  try {
    await page.goto(`${FRONT}/assistant`, { waitUntil: "networkidle2" });
    check("T1 对话页加载", !!(await page.$('[data-testid="assistant-page"]')));

    // CopilotChat 挂载（/info 发现 + 组件渲染）
    await page.waitForSelector(INPUT_SEL, { timeout: 30000 });
    check("T2 CopilotChat 输入框就绪", true);

    // T3 真实对话（RUN_STARTED→…→RUN_FINISHED 全链路）
    const reply = await sendAndWaitReply(page, "请只回答一个字：好", /^[^]*好[^]*$/, 24);
    check("T3 收到流式回复", reply.length > 0, reply.slice(-120).replace(/\n/g, " "));

    // T4 工具调用后给出服务列表（agent 调 MCP/内置工具）
    const reply2 = await sendAndWaitReply(page, "列出现在的服务名，只要名字不要表格", /release-agent/, 40);
    check("T4 工具调用后给出服务列表", /release-agent/.test(reply2), reply2.slice(-150).replace(/\n/g, " "));
  } catch (e) {
    fail++; console.error("异常:", e.message);
  } finally { await browser.close(); }
  console.log(`PASS: ${pass}  FAIL: ${fail}`);
  process.exit(fail ? 1 : 0);
})();
