// v2 平台界面截图：列表/发布向导/详情/助手对话（输出到 docs/screenshots/）
const puppeteer = require("puppeteer");
const FRONT = process.env.FRONTEND || "http://100.66.1.5:8911";
const OUT = process.env.OUT || "../docs/screenshots";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const b = await puppeteer.launch({ headless: "new", args: ["--no-sandbox"] });
  const p = await b.newPage();
  await p.setViewport({ width: 1440, height: 860, deviceScaleFactor: 1.5 });

  // 1. 服务列表
  await p.goto(`${FRONT}/`, { waitUntil: "networkidle2" });
  await p.waitForSelector('[data-testid="service-table"]', { timeout: 20000 }).catch(() => {});
  await sleep(800);
  await p.screenshot({ path: `${OUT}/01-service-list.png` });
  console.log("shot 01-service-list");

  // 2. 发布向导
  await p.goto(`${FRONT}/publish`, { waitUntil: "networkidle2" });
  await sleep(600);
  await p.screenshot({ path: `${OUT}/02-publish-wizard.png` });
  console.log("shot 02-publish-wizard");

  // 3. 服务详情（取 release-agent）
  const list = await fetch(`${FRONT}/api/v1/services`).then((r) => r.json());
  const svc = (list.data || []).find((s) => s.k8sName === "oaf-release-agent") || (list.data || [])[0];
  if (svc) {
    await p.goto(`${FRONT}/services/${svc.id}`, { waitUntil: "networkidle2" });
    // 等 Agent Card 渲染
    await p.waitForSelector('[data-testid="agent-card"]', { timeout: 20000 }).catch(() => {});
    await sleep(500);
    await p.screenshot({ path: `${OUT}/03-service-detail.png` });
    console.log("shot 03-service-detail");
  }

  // 4. 发布助手对话（真实 LLM 回复）
  await p.goto(`${FRONT}/assistant`, { waitUntil: "networkidle2" });
  await p.type('[data-testid="chat-input"]', "现在平台上有哪些服务？请用一句话简短回答");
  await p.click('[data-testid="chat-send"]');
  let reply = "";
  for (let i = 0; i < 90; i++) {
    await sleep(5000);
    reply = await p.evaluate(() => {
      const all = document.querySelectorAll('[data-testid="assistant-msg"]');
      return all.length ? all[all.length - 1].textContent.trim() : "";
    });
    if (reply && !reply.startsWith("思考中")) break;
  }
  await sleep(500);
  await p.screenshot({ path: `${OUT}/04-assistant-chat.png` });
  console.log("shot 04-assistant-chat, reply:", reply.slice(0, 60));

  await b.close();
})();
