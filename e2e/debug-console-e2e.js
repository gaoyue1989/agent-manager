// Debug Console 子路径访问 E2E：页面加载/CSS 生效/JS 启动/对话能力
const puppeteer = require("puppeteer");
const URL = process.env.DEBUG_URL || "http://100.66.1.5:30080/agent/approval-demo/debug";
let pass = 0, fail = 0;
const check = (n, c, d = "") => { if (c) { pass++; console.log(`  \x1b[32mPASS\x1b[0m ${n}`); } else { fail++; console.log(`  \x1b[31mFAIL\x1b[0m ${n} ${d}`); } };

(async () => {
  const b = await puppeteer.launch({ headless: "new", args: ["--no-sandbox"] });
  const p = await b.newPage();
  const errors = [];
  const failed404 = [];
  p.on("pageerror", (e) => errors.push(String(e).slice(0, 120)));
  p.on("response", (r) => { if (r.status() >= 400) failed404.push(`${r.status()} ${r.url().slice(-60)}`); });
  await p.goto(URL, { waitUntil: "networkidle2", timeout: 60000 });

  check("D1 页面标题渲染", (await p.title()).includes("Agent Debug Console"));
  // CSS 生效：body 背景不再是默认白色（token 样式已加载）
  const bg = await p.evaluate(() => getComputedStyle(document.body).backgroundColor);
  check("D2 CSS 已生效(背景非默认)", bg && bg !== "rgba(0, 0, 0, 0)" && bg !== "rgb(255, 255, 255)", bg);
  // JS 模块启动：导航项由 router.js 动态生成
  const navCount = await p.evaluate(() => document.querySelectorAll(".nav-item").length);
  check("D3 JS 启动且导航生成", navCount > 3, `nav=${navCount}`);
  check("D4 无 JS 运行时错误", errors.length === 0, errors.join("; "));
  check("D5 无 404/5xx 资源", failed404.length === 0, failed404.join("; "));
  await b.close();
  console.log(`PASS: ${pass}  FAIL: ${fail}`);
  process.exit(fail ? 1 : 0);
})();
