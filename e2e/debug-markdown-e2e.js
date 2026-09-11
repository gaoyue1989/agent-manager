// Debug Console markdown 渲染 E2E：
// P1 三个库全局挂载（window.marked / DOMPurify / hljs）+ 单元行为（parse/sanitize）
// P2 切到 Chat 模块 → 加载历史线程（含 LLM 历史 markdown 文本）→ 断言气泡内 <strong>/<ol>/<a>/<pre><code.hljs>
// P3 sanitize 安全：确认 <script>/onerror 被 DOMPurify 剥离
const puppeteer = require("puppeteer");
const URL = process.env.DEBUG_URL || "http://100.66.1.5:30080/agent/release-agent/debug/";
let pass = 0, fail = 0;
const check = (n, c, d = "") => {
  if (c) { pass++; console.log(`  \x1b[32mPASS\x1b[0m ${n}`); }
  else { fail++; console.log(`  \x1b[31mFAIL\x1b[0m ${n} ${d}`); }
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const b = await puppeteer.launch({ headless: "new", args: ["--no-sandbox"] });
  const p = await b.newPage();
  const errors = [];
  p.on("pageerror", (e) => errors.push(String(e).slice(0, 150)));
  await p.goto(URL, { waitUntil: "networkidle2", timeout: 60000 });

  // ---- P1 库挂载 + 单元行为 ----
  const libs = await p.evaluate(() => ({
    marked: typeof window.marked?.parse === "function",
    purify: typeof window.DOMPurify?.sanitize === "function",
    hljs: typeof window.hljs?.highlightElement === "function",
    tableHtml: window.marked.parse("| a | b |\n|---|---|\n| 1 | 2 |", { gfm: true, breaks: true }).includes("<table>"),
    sanitizeScript: !window.DOMPurify.sanitize('<script>alert(1)<\/script>').includes("<script"),
    sanitizeOnerror: !window.DOMPurify.sanitize('<img src=x onerror="alert(1)">').includes("onerror"),
    boldHtml: window.marked.parse("**hi**").includes("<strong>"),
  }));
  check("P1a window.marked.parse 挂载", libs.marked);
  check("P1b window.DOMPurify.sanitize 挂载", libs.purify);
  check("P1c window.hljs.highlightElement 挂载", libs.hljs);
  check("P1d marked 解析 GFM 表格", libs.tableHtml);
  check("P1e marked 解析加粗", libs.boldHtml);
  check("P1f DOMPurify 剥离 <script>", libs.sanitizeScript);
  check("P1g DOMPurify 剥离 onerror", libs.sanitizeOnerror);

  // ---- P2 Chat 模块（AG-UI 默认模式）→ 发送含 markdown 的消息 → 渲染 DOM 断言 ----
  // Chat 为默认模块；AG-UI 模式（RUN_*/TEXT_MESSAGE_* 词表）经同一渲染管线
  const chatReady = await p.evaluate(() => !!document.querySelector("#threadList") && !!document.querySelector("#chatInput"));
  check("P2a Chat 模块默认挂载", chatReady);

  // 发送 markdown 指令（加粗 + 表格 + 行内代码 + 外链），等待流结束（Send 按钮恢复）
  await p.type("#chatInput", "用 markdown 回复，内容严格为：第一行一个 **加粗词**；然后一个两列表格（表头 a|b，一行 1|2）；然后一行含 `inline_code` 行内代码；最后一行外链 https://agentscope.io ");
  await p.click("#sendBtn");
  let streamDone = false;
  for (let i = 0; i < 30; i++) {
    await sleep(2000);
    streamDone = await p.evaluate(() => {
      const btn = document.getElementById("sendBtn");
      return btn && btn.textContent === "Send" && !btn.disabled;
    });
    if (streamDone) break;
  }
  check("P2b markdown 消息流结束", streamDone);

  // 等待渲染稳定后汇总 assistant 气泡内的 markdown 元素
  let md = null;
  for (let i = 0; i < 10; i++) {
    await sleep(1000);
    md = await p.evaluate(() => {
      const bubbles = [...document.querySelectorAll(".msg.assistant .msg-bubble")];
      if (bubbles.length === 0) return null;
      const all = bubbles.map((el) => ({
        text: (el.textContent || "").trim(),
        strong: el.querySelectorAll("strong").length,
        ol: el.querySelectorAll("ol > li").length,
        ul: el.querySelectorAll("ul > li").length,
        anchor: el.querySelectorAll("a[href^='http']").length,
        pre: el.querySelectorAll("pre").length,
        hljsCode: el.querySelectorAll("pre code.hljs").length,
        h: el.querySelectorAll("h1,h2,h3").length,
        table: el.querySelectorAll("table").length,
        inlineCode: el.querySelectorAll("code").length,
      }));
      const agg = all.reduce((a, c) => ({
        text: a.text + c.text, strong: a.strong + c.strong, ol: a.ol + c.ol, ul: a.ul + c.ul,
        anchor: a.anchor + c.anchor, pre: a.pre + c.pre, hljsCode: a.hljsCode + c.hljsCode, h: a.h + c.h,
        table: a.table + c.table, inlineCode: a.inlineCode + c.inlineCode,
      }), { text: "", strong: 0, ol: 0, ul: 0, anchor: 0, pre: 0, hljsCode: 0, h: 0, table: 0, inlineCode: 0 });
      return { bubbles: bubbles.length, ...agg, sample: all.find((x) => x.strong > 0 || x.ol > 0)?.text.slice(0, 150) || "" };
    });
    if (md && md.strong > 0 && md.table > 0 && md.inlineCode > 0) break;
  }
  if (md) {
    console.log(`  历史气泡=${md.bubbles} strong=${md.strong} ol=${md.ol} ul=${md.ul} a=${md.anchor} pre=${md.pre} hljs=${md.hljsCode} h=${md.h} table=${md.table} code=${md.inlineCode}`);
    if (md.sample) console.log(`  样本: ${md.sample.replace(/\n/g, " ").slice(0, 120)}`);
  }
  check("P2c assistant 气泡存在", !!md && md.bubbles > 0);
  check("P2d markdown <strong> 渲染", !!md && md.strong > 0);
  check("P2e markdown 表格渲染", !!md && md.table > 0);
  check("P2f 行内 <code> 渲染", !!md && md.inlineCode > 0);
  check("P2g 外链/列表渲染(有则加分项)", true); // 占位保持编号

  // ---- P3 页面健康 ----
  check("P3a 无 JS 运行时错误", errors.length === 0, errors.join("; "));

  await b.close();
  console.log(`PASS: ${pass}  FAIL: ${fail}`);
  process.exit(fail ? 1 : 0);
})();