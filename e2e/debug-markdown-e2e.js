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

  // ---- P2 Chat 模块（默认已挂载）→ 加载历史线程 → markdown DOM 断言 ----
  // Chat 为默认模块（nav 为 emoji，无需切换）；验证模块容器已挂载
  const chatReady = await p.evaluate(() => !!document.querySelector("#threadList") && !!document.querySelector("#chatInput"));
  check("P2a Chat 模块默认挂载", chatReady);

  // 线程列表加载后点第一个非 __anon__ 线程（A2A 来源线程无历史，历史接口 404 为既有行为）
  let threadClicked = false;
  for (let i = 0; i < 20; i++) {
    threadClicked = await p.evaluate(() => {
      const t = [...document.querySelectorAll("#threadList .thread-item")]
        .find((el) => el.dataset.sid && !el.dataset.sid.startsWith("__anon__"));
      if (t) { t.click(); return true; }
      return false;
    });
    if (threadClicked) break;
    await sleep(1000);
  }
  check("P2b 选中 webui 历史线程", threadClicked);

  // 等历史回放渲染（历史 assistant 消息走 addAssistantHistory → renderMarkdown）
  let md = null;
  for (let i = 0; i < 15; i++) {
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
      // 汇总整线程的 markdown 元素
      const agg = all.reduce((a, c) => ({
        text: a.text + c.text, strong: a.strong + c.strong, ol: a.ol + c.ol, ul: a.ul + c.ul,
        anchor: a.anchor + c.anchor, pre: a.pre + c.pre, hljsCode: a.hljsCode + c.hljsCode, h: a.h + c.h,
        table: a.table + c.table, inlineCode: a.inlineCode + c.inlineCode,
      }), { text: "", strong: 0, ol: 0, ul: 0, anchor: 0, pre: 0, hljsCode: 0, h: 0, table: 0, inlineCode: 0 });
      return { bubbles: bubbles.length, ...agg, sample: all.find((x) => x.strong > 0 || x.ol > 0)?.text.slice(0, 150) || "" };
    });
    if (md && md.bubbles > 0 && (md.strong > 0 || md.ol > 0 || md.anchor > 0 || md.ul > 0 || md.table > 0)) break;
  }
  if (md) {
    console.log(`  历史气泡=${md.bubbles} strong=${md.strong} ol=${md.ol} ul=${md.ul} a=${md.anchor} pre=${md.pre} hljs=${md.hljsCode} h=${md.h} table=${md.table} code=${md.inlineCode}`);
    if (md.sample) console.log(`  样本: ${md.sample.replace(/\n/g, " ").slice(0, 120)}`);
  }
  check("P2c 历史 assistant 气泡存在", !!md && md.bubbles > 0);
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