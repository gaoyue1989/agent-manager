/* zcode-workflow
description: 近 N 天变更定期评审：三轨并行——①代码评审（安全/UX/开发体验/AI agent 体验/架构强约束）→独立复核→自动创建
  GitHub Issues；②文档时效检查（docs/ 与 agent-framework/docs/ 及各级 AGENTS.md）→独立核实→自动提文档
  PR；③测试覆盖缺口（单测/e2e/CI 门禁）→分诊核实→自动提测试 PR（本地定向验证）。最后统一盯守全部 PR 的 CI 门禁并在通过后合并（最多 2
  次失败修复尝试），产出评审报告。
whenToUse: 需要定期（如每两周）评审最近合入 master 的变更，并把发现落成 Issue 与 PR 时使用。也可在大幅迭代后做一次全面体检。参数
  days 控制回看天数（默认 14），since 可显式指定起始日期（YYYY-MM-DD）。
args:
  days:
    type: number
    description: 回看天数：评审最近 N 天合入 master 的变更
    default: 14
  since:
    type: string
    description: 可选：显式指定窗口起始日期（YYYY-MM-DD），填写后覆盖 days
  proxy:
    type: string
    description: git/gh 走的代理地址（如 http://127.0.0.1:7890）；本机可直连 GitHub 时传空字符串
    default: http://127.0.0.1:7890
  startAt:
    type: string
    description: 可选：定时启动时刻（24 小时制 HH:MM，本机时区），填写后先等待到该时刻再开始评审；留空立即开始
*/
// ===== 近 N 天变更评审工作流（可保存复用）=====
// 三轨并行：A 代码评审→独立复核→建 GitHub Issues；B 文档时效→独立核实→文档 PR；
// C 测试缺口→分诊→独立核实→测试 PR。最后统一盯守 PR 门禁与合并，输出总报告。

// ---------- 结果类型 ----------
interface RawFinding {
  /** 工作区相对路径，可带行号：backend/internal/service/publish.go:42 */
  path: string;
  /** 一句话问题标题（issue 标题候选） */
  title: string;
  /** 哪里有问题、为什么，必须引用代码证据 path:line */
  detail: string;
  /** 分类 */
  category: "security" | "ux" | "dx" | "agent-experience" | "other";
  /** high 仅用于安全漏洞/数据损坏/崩溃/架构强约束违反 */
  severity: "low" | "medium" | "high";
  /** 建议的修复方向（一句话） */
  suggestion: string;
}
interface ReviewResult {
  findings: RawFinding[];
  /** 评审覆盖说明：看了哪些、哪些没看 */
  coverage: string;
}
interface TriagedFinding extends RawFinding {
  id: string;
  area: string;
  /** 是否值得创建 GitHub Issue（低价值项只进报告） */
  fileIssue: boolean;
}
interface TriagedSet { items: TriagedFinding[] }
interface Verdict {
  id: string;
  /** confirmed=成立；refuted=不成立；adjusted=成立但表述需按 corrected 修正 */
  verdict: "confirmed" | "refuted" | "adjusted";
  /** 复核后的最终表述（可直接用于 issue 正文/施工清单；无修正就原样复述） */
  corrected: string;
  /** 复核证据：path:line 或只读命令的关键输出 */
  evidence: string;
  severity: "low" | "medium" | "high";
}
interface DocIssue {
  path: string;
  /** 小节标题或行号 */
  section: string;
  /** 文档现在说什么 vs 实际是什么 */
  problem: string;
  /** 可直接执行的修改建议 */
  fix: string;
  action: "update" | "delete" | "none";
}
interface DocsResult { issues: DocIssue[]; coverage: string }
interface TestGap {
  /** 缺口目标：未覆盖的源文件/端点/功能点 */
  target: string;
  kind: "unit" | "e2e" | "gate" | "other";
  /** 为什么重要（结合近 N 天变更） */
  why: string;
  /** 建议补的测试：文件、用例名、关键断言 */
  suggestion: string;
  /** fixable=一次 PR 可补齐（≤3 用例）；heavy=需大型基建，仅报告 */
  feasibility: "fixable" | "heavy";
}
interface GapResult { gaps: TestGap[]; coverage: string }
interface BucketedGap extends TestGap {
  id: string;
  bucket: "agent-framework" | "backend-frontend" | "report-only";
}
interface BucketSet { items: BucketedGap[] }
interface IssueOutcome {
  created: { findingId: string; number: number; url: string; title: string }[];
  skipped: { title: string; reason: string }[];
  note: string;
}
interface PrOutcome {
  done: boolean;
  prNumber?: number;
  prUrl?: string;
  branch?: string;
  /** 保留的 git worktree 路径（失败修复时复用；无则留空） */
  worktree?: string;
  /** 做了什么（逐条） */
  summary: string;
  /** 本地验证：跑过的命令与结果 */
  localChecks: string;
  /** 未处理项与原因 */
  notHandled: string[];
}
interface WatchRow {
  pr: number;
  state: "merged" | "open" | "closed" | "blocked";
  note: string;
}
interface WatchState { allSettled: boolean; rows: WatchRow[] }
interface LedgerItem {
  id: string;
  title: string;
  path: string;
  category: string;
  severity: "low" | "medium" | "high";
  status: "verified" | "unconfirmed" | "refuted";
  disposition: string;
}
interface PrRef { n: number; url: string; branch: string; worktree: string; kind: string }
interface WorkflowReport {
  conclusion: string;
  findings: LedgerItem[];
  verified: string[];
  notCovered: string[];
}

// ---------- 看板与台账声明（顶层一次） ----------
artifact.table("findings", {
  title: "评审发现台账",
  columns: [
    { field: "id", label: "编号" },
    { field: "title", label: "问题" },
    { field: "path", label: "位置" },
    { field: "category", label: "分类" },
    { field: "severity", label: "严重度" },
    { field: "status", label: "复核状态" },
    { field: "disposition", label: "处置" },
  ],
  key: "id",
});
artifact.board("deliverables", {
  title: "交付物看板（Issue 与 PR）",
  key: "id",
  status: "status",
  columns: ["已提交", "PR 开放", "已合并", "受阻", "已关闭", "已跳过"],
  cardTitle: "title",
  detail: [{ field: "note", label: "说明" }],
});

// ---------- 参数 ----------
const days = typeof args.days === "number" && args.days > 0 ? args.days : 14;
const sinceArg = typeof args.since === "string" && args.since.trim() !== "" ? args.since.trim() : `-${days} days`;
const proxy = typeof args.proxy === "string" ? args.proxy.trim() : "http://127.0.0.1:7890";
const startAt = typeof args.startAt === "string" ? args.startAt.trim() : "";

// ---------- 公共上下文 ----------
const GIT_PROXY = proxy === "" ? "" : ` -c http.proxy=${proxy} -c https.proxy=${proxy}`;
const GH_PROXY = proxy === "" ? "" : `HTTPS_PROXY=${proxy} HTTP_PROXY=${proxy}`;
const PROXY = proxy === ""
  ? "GitHub 网络操作本机可直连：git 与 gh 命令一律不要加代理参数（不加 -c http.proxy/https.proxy，不设 HTTPS_PROXY/HTTP_PROXY）"
  : `GitHub 网络操作必须走本地代理 ${proxy}（本机直连 GitHub 不通）：git 命令加${GIT_PROXY}；gh 命令前缀 ${GH_PROXY}`;
const REPO_NOTES = `仓库背景：/root/agent-manager = OAF 服务发布平台。backend/（Go 控制面：REST /api/v1 + MCP /mcp、client-go）；agent-framework/（Java 业务面运行时：A2A、单次流、MCP 注册、HITL、MySQL checkpoint/Redis）；frontend/（Next.js 控制台）；charts/oaf-agent/（业务 Helm 图表）；e2e/ 与 agent-framework/e2e/（回归脚本）。
架构强约束（违反即 high）：
1) backend 是控制面：禁止启动路径 reconcile/sync-all/后台批量迁移业务 K8s 资源；禁止 ownerReferences/finalizer 把业务资源生命周期绑到平台对象；业务运行时不得依赖 backend 可用性。
2) agent-framework 是业务面：影响正确性的状态只允许存 MySQL checkpoint/Redis 共享存储；Pod 本地内存/emptyDir 只放可丢弃缓存；禁止 sessionAffinity；多服务隔离靠 per-service checkpoint 库名与 Redis 前缀。
3) 表结构演进只允许新增 Flyway 迁移文件；禁止修改已合并的 V 文件；禁止请求路径手工 DDL。`;

// ---------- 可选：定时启动（等待到指定时刻再开始评审） ----------
if (startAt !== "") {
  phase("等待定时启动");
  const waitSecRaw = (await world.run("sh", ["-c", `echo $(( $(date -d "today ${startAt}" +%s) - $(date +%s) ))`])).stdout.trim();
  const waitSec = Number(waitSecRaw);
  if (Number.isFinite(waitSec) && waitSec > 0) {
    log(`定时启动：先等待 ${waitSec} 秒，到本地时间 ${startAt} 后自动开始评审`);
    await world.run("sleep", [String(waitSec)], { timeoutMs: waitSec * 1000 + 60000, output: "discard" });
    log(`已到 ${startAt}，开始评审`);
  } else {
    log(`startAt=${startAt} 已过或无效，立即开始评审`);
  }
}

// ---------- 阶段一：盘点变更窗口 ----------
phase("盘点变更窗口");
const since = (await world.run("date", ["-d", sinceArg, "+%Y-%m-%d"])).stdout.trim();
const logRes = await world.run("git", ["log", `--since=${since}`, "--format=%H%x09%s", "--no-merges", "-n", "100"]);
const commitLines = logRes.stdout.trim().split("\n").filter(Boolean);
const commits = commitLines.map((line) => {
  const i = line.indexOf("\t");
  return { hash: line.slice(0, i), subject: line.slice(i + 1) };
});
const oldest = commits.length > 0 ? commits[commits.length - 1].hash : "HEAD";
let baseParent = oldest;
try {
  baseParent = (await world.run("git", ["rev-parse", `${oldest}^`])).stdout.trim();
} catch {
  baseParent = oldest; // 窗口首提交是根提交时兜底
}
let diffOut = "";
try {
  diffOut = (await world.run("git", ["diff", "--name-only", baseParent, "HEAD"])).stdout;
} catch {
  diffOut = (await world.run("git", ["log", `--since=${since}`, "--format=", "--name-only", "--no-merges"])).stdout;
}
const changed = diffOut.split("\n").map((s) => s.trim()).filter(Boolean).sort();
const commitSubjects = commits.map((c) => c.subject).join("\n");
log(`近 ${days} 天（自 ${since}）：${commits.length} 个提交，${changed.length} 个文件，基线 ${baseParent.slice(0, 8)}..HEAD`);

if (commits.length === 0 || changed.length === 0) {
  const emptyReport: WorkflowReport = {
    conclusion: `自 ${since} 以来没有任何合入 master 的提交，无需评审。`,
    findings: [],
    verified: ["git log --since 核实窗口为空"],
    notCovered: [],
  };
  return emptyReport;
}

// ---------- 变更文件分组 ----------
const isAfMain = (f: string) => f.startsWith("agent-framework/src/main/");
const afService = changed.filter((f) => isAfMain(f) && f.includes("/framework/service/"));
const afSandbox = changed.filter((f) => isAfMain(f) && f.includes("/framework/sandbox"));
const afRest = changed.filter((f) => isAfMain(f) && !f.includes("/framework/service/") && !f.includes("/framework/sandbox"));
const backendFiles = changed.filter((f) => f.startsWith("backend/"));
const frontendFiles = changed.filter((f) => f.startsWith("frontend/"));
const chartsFiles = changed.filter((f) => f.startsWith("charts/") || f.startsWith("manifests/"));
const afGapFiles = changed.filter((f) => f.startsWith("agent-framework/"));
const beFeGapFiles = changed.filter((f) => f.startsWith("backend/") || f.startsWith("frontend/") || f.startsWith("charts/") || f.startsWith("e2e/") || f.startsWith(".github/"));
const sourceChanges = changed.filter((f) => !f.startsWith("docs/screenshots/"));

// ---------- 轨道 A：代码评审 ----------
const trackA = future(async () => {
  phase("并行评审各模块代码变更");
  const areas = [
    { key: "Java服务层", scope: "agent-framework/src/main/java 下 framework/service/ 全部（含 export、eval、threads 等服务层）", files: afService },
    { key: "Java接口与配置", scope: "agent-framework/src/main/ 下除 service 与 sandbox 外的 controller、config、mcp、util 及 resources", files: afRest },
    { key: "沙箱子系统", scope: "agent-framework/src/main/java 下 framework/sandbox 全部（含 opensandbox）", files: afSandbox },
    { key: "后端Go", scope: "backend/ 全部（handler、service、store、imagebuilder、config）", files: backendFiles },
    { key: "前端", scope: "frontend/src 全部", files: frontendFiles },
    { key: "Helm与清单", scope: "charts/oaf-agent 与 manifests/", files: chartsFiles },
  ];
  const reviews = await Promise.all(areas.map((a) =>
    agent(`评审·${a.key}`).ask<ReviewResult>(
      `你是代码评审员，评审仓库 /root/agent-manager 近 ${days} 天（自 ${since} 起）合入 master 的变更中你负责的模块。\n\n` +
      `【你负责的模块】${a.scope}\n【改动文件清单】\n${a.files.join("\n") || "（无）"}\n\n` +
      `【获取 diff】本地只读操作（无需代理）：git -C /root/agent-manager diff ${baseParent}..HEAD -- <路径>\n` +
      `【窗口内全部提交主题（帮助理解变更意图）】\n${commitSubjects}\n\n${REPO_NOTES}\n\n` +
      `【评审视角（按优先级）】\n` +
      `1. 安全漏洞：各类注入、敏感信息泄漏（日志/响应/env）、鉴权越权、SSRF、路径穿越、并发竞态导致数据损坏\n` +
      `2. 架构强约束违反（见上，违反即 high）\n` +
      `3. AI agent 使用体验：MCP/A2A 契约清晰、错误可诊断、工具描述质量、HITL 流程顺畅\n` +
      `4. 用户/开发体验：边界与失败态、状态一致性、提示文案、命名、重复代码、配置易错点\n\n` +
      `【硬性要求】\n` +
      `- 只评审近 ${days} 天的变更本身，或变更与存量代码交互产生的新问题；不翻旧账\n` +
      `- 每条发现必须先读到代码证据（path:line）再下结论；拿不准的不写；没有问题就返回空数组，宁缺毋滥、不凑数\n` +
      `- 严格只读：不得修改/新增/删除任何文件，不得执行任何写操作；不要运行测试套件（CI 会跑）`,
    ),
  ));

  // 汇总分诊是屏障：跨模块去重需要全部评审结果
  phase("汇总去重评审发现");
  const rawAll = reviews.flatMap((r, i) => r.findings.map((f) => ({ ...f, area: areas[i].key })));
  log(`6 个模块共产出 ${rawAll.length} 条原始发现`);
  let triagedItems: TriagedFinding[] = [];
  if (rawAll.length > 0) {
    const t = await agent("评审分诊员").ask<TriagedSet>(
      `以下是 6 个模块评审员对近 ${days} 天变更的原始发现。请：\n` +
      `1) 去重：同一问题被多人/跨模块重复报告的合并为一条；\n` +
      `2) 剔除明显误报与噪音（把存量问题当新问题且与本次变更无关联、纯风格偏好等）；\n` +
      `3) 对值得创建 GitHub Issue 的条目标 fileIssue=true（上限 12 条，按 security/high/medium 与用户体验实质问题排序优先），其余 fileIssue=false 仅进报告；\n` +
      `4) 编号 F1、F2……保留 area 字段。\n原始发现（JSON）：\n${JSON.stringify(rawAll, null, 1)}`,
    );
    triagedItems = t.items;
  }

  phase("独立复核每条评审发现");
  const toConfirm = triagedItems.filter((f) => f.fileIssue);
  const verdicts = toConfirm.length > 0
    ? await Promise.all(toConfirm.map((f) =>
        agent(`复核员-${f.id}`).ask<Verdict>(
          `你是独立复核员，与原评审员相互独立。请凭代码证据复核这条发现是否成立：\n${JSON.stringify(f, null, 1)}\n\n` +
          `要求：亲自在 /root/agent-manager 读相关代码（path:line），必要时用只读命令验证；不要运行测试套件；不要修改任何文件。\n` +
          `verdict：confirmed（成立）/ refuted（不成立，说明为什么）/ adjusted（成立但表述需修正）。\n` +
          `corrected 给出可直接用于 issue 正文的最终表述（无修正就原样复述）；severity 重新校准：high 仅用于安全漏洞/数据损坏/崩溃/架构强约束违反；evidence 必须具体。`,
        )))
    : [];
  const verdictById = new Map<string, Verdict>();
  for (const v of verdicts) verdictById.set(v.id, v);

  const ledgerA: LedgerItem[] = [];
  const issuesToFile: { id: string; title: string; body: string; severity: string; category: string }[] = [];
  for (const f of triagedItems) {
    const v = verdictById.get(f.id);
    if (v === undefined) {
      const item: LedgerItem = { id: f.id, title: f.title, path: f.path, category: f.category, severity: f.severity, status: "unconfirmed", disposition: "低价值，仅报告" };
      ledgerA.push(item);
      report(item, "findings");
      continue;
    }
    if (v.verdict === "refuted") {
      const item: LedgerItem = { id: f.id, title: f.title, path: f.path, category: f.category, severity: f.severity, status: "refuted", disposition: "独立复核未成立" };
      ledgerA.push(item);
      report(item, "findings");
      continue;
    }
    const finalText = v.verdict === "adjusted" ? v.corrected : f.detail;
    const item: LedgerItem = { id: f.id, title: f.title, path: f.path, category: f.category, severity: v.severity, status: "verified", disposition: "待建 Issue" };
    ledgerA.push(item);
    report(item, "findings");
    issuesToFile.push({
      id: f.id,
      title: f.title,
      body: `## 问题\n${finalText}\n\n## 复核证据\n${v.evidence}\n\n## 位置\n${f.path}\n\n## 建议\n${f.suggestion}\n\n---\n> 来源：近 ${days} 天变更自动评审，独立复核成立（severity: ${v.severity}，category: ${f.category}）`,
      severity: v.severity,
      category: f.category,
    });
  }

  phase("提交 GitHub Issues");
  let issueOut: IssueOutcome = { created: [], skipped: [], note: "无需创建" };
  if (issuesToFile.length > 0) {
    issueOut = await agent("Issue提交员", { system: "你是 GitHub Issue 提交员：只依据清单创建真实、可执行的 issue，一切以 gh 命令的真实输出为准，绝不伪造创建结果；被阻塞时如实上报，不要绕过。" }).ask<IssueOutcome>(
      `为以下经独立复核成立的评审发现创建 GitHub Issues（仓库 gaoyue1989/agent-manager）。\n${PROXY}\n\n` +
      `步骤：\n` +
      `1) 查重：${GH_PROXY} gh issue list -R gaoyue1989/agent-manager --state open --limit 200 --json number,title，必要时再 --search 关键词；与已有 open issue 重复的跳过并记入 skipped（注明原因）\n` +
      `2) ${GH_PROXY} gh label list -R gaoyue1989/agent-manager 看现有标签，选贴切的（如 bug/enhancement/security），没有贴切就不加\n` +
      `3) 逐条创建：gh issue create -R gaoyue1989/agent-manager --title "<标题>" --body <正文>（多行正文建议写临时文件放 /tmp 再 --body-file）；标题用中文、不加 emoji；正文直接用清单中给出的 body\n` +
      `4) 创建后用 gh issue view 确认编号与 URL，输出 created（必须带回对应 findingId）/skipped\n` +
      `只依据下方清单创建，不添加清单外内容。\n【待建 Issue 清单】\n${JSON.stringify(issuesToFile, null, 1)}`,
    );
    log(`已创建 ${issueOut.created.length} 个 Issue，查重跳过 ${issueOut.skipped.length} 个`);
    for (const c of issueOut.created) {
      report({ id: `issue-${c.number}`, title: c.title, status: "已提交", note: c.url }, "deliverables");
      const it = ledgerA.find((x) => x.id === c.findingId);
      if (it !== undefined) {
        it.disposition = `已建 issue #${c.number}`;
        report(it, "findings");
      }
    }
  }
  return { ledger: ledgerA, issueOut };
});

// ---------- 轨道 B：文档时效 ----------
const trackB = future(async () => {
  phase("审查文档是否陈旧");
  const docAreas = [
    { key: "平台docs", scope: "docs/ 下全部 .md（design/、implementation/、deployment.md、oaf-specification.md、blackbox-testing.md、README 等；不含 docs/screenshots/ 图片）" },
    { key: "框架docs", scope: "agent-framework/docs/ 全部、agent-framework/AGENTS.md、agent-framework/README.md" },
    { key: "根级AGENTS与README", scope: "AGENTS.md、README.md、backend/AGENTS.md、e2e/AGENTS.md，以及 backend/README.md、frontend/README.md（存在才看）" },
  ];
  const docsReviews = await Promise.all(docAreas.map((d) =>
    agent(`文档审查·${d.key}`).ask<DocsResult>(
      `你在审查仓库 /root/agent-manager 的文档是否因近 ${days} 天（自 ${since} 起）合入 master 的变更而陈旧。你负责：${d.scope}\n\n` +
      `【近 ${days} 天变更的文件清单（判断哪些文档陈述可能被推翻）】\n${sourceChanges.join("\n")}\n\n` +
      `【窗口内全部提交主题】\n${commitSubjects}\n\n` +
      `【核查方法】通读你负责的文档，对其中可被代码推翻的关键陈述（命令、路径、环境变量、API 端点、schema/Flyway 版本、状态机、架构描述、回归命令、门禁清单等），用 grep/读代码验证是否仍成立（本地只读）。文档陈旧 = 与 HEAD 现状矛盾，或描述的功能已删除/重命名。\n\n` +
      `【输出】每条滞后项：path、section（小节标题或行号）、problem（文档现在说什么 vs 实际是什么）、fix（具体到可直接执行的修改建议）、action（update/delete/none；delete 仅当整篇或整节确实废弃）。只报有证据的；拿不准的 action 用 none 并说明。严格只读：不得修改任何文件。`,
    ),
  ));
  const docAll = docsReviews.flatMap((r) => r.issues).filter((d) => d.action !== "none");
  log(`文档审查共 ${docAll.length} 条滞后项（action=none 已过滤）`);

  phase("独立核实文档滞后项");
  const docToVerify = docAll.slice(0, 18);
  const docVerdicts = docToVerify.length > 0
    ? await Promise.all(docToVerify.map((d, i) =>
        agent(`文档核实员-${i + 1}`).ask<Verdict>(
          `你是独立复核员。复核这条“文档陈旧”判断是否属实：\n${JSON.stringify(d, null, 1)}\n\n` +
          `要求：亲自读该文档原文与相关代码/配置（grep、读文件，本地只读），验证 problem 是否准确、fix 是否可行且足够具体；不要修改任何文件；不要运行测试。\n` +
          `verdict：confirmed / refuted（文档其实没过时或建议错误）/ adjusted（成立但表述需修正）；corrected 给出核实后的最终修改表述（可直接执行）；id 用 D${i + 1}。`,
        )))
    : [];
  const docFixes: { id: string; path: string; section: string; fix: string }[] = [];
  const ledgerB: LedgerItem[] = [];
  docAll.forEach((d, i) => {
    const id = `D${i + 1}`;
    const v = i < docToVerify.length ? docVerdicts[i] : undefined;
    if (v === undefined || v.verdict === "refuted") {
      const item: LedgerItem = { id, title: `文档滞后：${d.path} ${d.section}`, path: d.path, category: "docs", severity: "low", status: v === undefined ? "unconfirmed" : "refuted", disposition: v === undefined ? "超出核实上限，仅报告" : "独立核实未成立" };
      ledgerB.push(item);
      report(item, "findings");
      return;
    }
    const item: LedgerItem = { id, title: `文档滞后：${d.path} ${d.section}`, path: d.path, category: "docs", severity: "low", status: "verified", disposition: "待文档 PR" };
    ledgerB.push(item);
    report(item, "findings");
    docFixes.push({ id, path: d.path, section: d.section, fix: v.verdict === "adjusted" ? v.corrected : `${d.problem} → 修改为：${d.fix}` });
  });

  phase("文档更新 PR");
  let docPr: PrOutcome = { done: false, summary: "无需更新", localChecks: "无", notHandled: [] };
  if (docFixes.length > 0) {
    docPr = await agent("文档修复员", { system: "你是谨慎的文档工程师：只做指派范围内的修改，保持各文档原有结构与语气；一切以命令真实输出为准，绝不伪造成功；被阻塞时如实上报，不要绕过。" }).ask<PrOutcome>(
      `把以下经核实的文档滞后项落实为一次 PR（仓库 gaoyue1989/agent-manager）。\n${PROXY}\n\n` +
      `【工作方式——必须严格遵守】\n` +
      `1) 一切改动在独立 git worktree 中进行，绝不触碰 /root/agent-manager 主工作区（不得在主工作区 checkout/stash/commit；主工作区的未跟踪文件 agent-framework/Dockerfile.local、agent-framework/docker/settings-aliyun.xml、sandbox/ 不要动）：\n` +
      `   git -C /root/agent-manager worktree add /tmp/wt-docs-${since} -b docs/review-align-${since} master\n` +
      `2) 在 /tmp/wt-docs-${since} 内按清单逐条修改文档；update 为主，delete 仅当整篇/整节确实废弃；修改克制、聚焦清单内的问题\n` +
      `3) 提交：git -C /tmp/wt-docs-${since} add -A && git -C /tmp/wt-docs-${since} commit -m "docs: 对齐近 ${days} 天变更的文档滞后项"\n` +
      `4) 推送：git -C /tmp/wt-docs-${since}${GIT_PROXY} push -u origin docs/review-align-${since}\n` +
      `5) 建 PR：${GH_PROXY} gh pr create -R gaoyue1989/agent-manager --base master --head docs/review-align-${since} --title "docs: 对齐近 ${days} 天变更的文档滞后项" --body <逐条修改说明 + 来源：近 ${days} 天变更自动评审>\n` +
      `   （若分支已存在导致 push 被拒，改用 docs/review-align-${since}-2 并同步调整后续命令）\n` +
      `6) 不要尝试合并（盯守员负责合并）；不要删除 worktree（失败修复时复用）\n\n` +
      `返回 PrOutcome：done/prNumber/prUrl/branch/worktree/summary（逐条改动）/localChecks（如用 grep 自检过引用一致性）/notHandled（未落实项+原因）。任何一步失败要如实返回，不要伪造。\n` +
      `【待落实清单】\n${JSON.stringify(docFixes, null, 1)}`,
    );
    if (docPr.done && docPr.prNumber !== undefined) {
      report({ id: "pr-docs", title: `文档更新 PR #${docPr.prNumber}`, status: "PR 开放", note: docPr.prUrl ?? "" }, "deliverables");
      for (const it of ledgerB) {
        if (it.status === "verified") {
          it.disposition = `随 PR #${docPr.prNumber} 修复`;
          report(it, "findings");
        }
      }
    } else {
      log(`文档 PR 未创建：${docPr.summary}`);
    }
  }
  return { docFixes, ledger: ledgerB, pr: docPr };
});

// ---------- 轨道 C：测试缺口 ----------
const trackC = future(async () => {
  phase("分析测试覆盖缺口");
  const gapAreas = [
    { key: "Java框架", scope: "agent-framework/：src 主代码 vs src/test 单测、agent-framework/e2e 回归、bench 覆盖，以及 .github/workflows 中 agent-framework-ci 的目录过滤与门禁是否漏掉新增能力", files: afGapFiles },
    { key: "后端与前端", scope: "backend/、frontend/、charts/、e2e/、.github/workflows 的测试覆盖与门禁", files: beFeGapFiles },
  ];
  const gapResults = await Promise.all(gapAreas.map((g) =>
    agent(`测试缺口·${g.key}`).ask<GapResult>(
      `你是测试覆盖分析员，分析 /root/agent-manager 近 ${days} 天（自 ${since} 起）变更中你负责模块的测试缺口。\n\n` +
      `【你负责】${g.scope}\n【近 ${days} 天改动文件】\n${g.files.join("\n") || "（无）"}\n\n` +
      `【窗口内全部提交主题】\n${commitSubjects}\n\n${REPO_NOTES}\n\n` +
      `【任务】\n` +
      `1) 对照新功能/新端点/新 env/新 Flyway 迁移，找缺失的单测与 e2e（先搜索确认确实没有既有覆盖）\n` +
      `2) CI 门禁：.github/workflows/*.yml 的目录过滤、必需检查是否漏掉新增目录/关键行为\n` +
      `3) 顺带看近 ${days} 天新增/修改的测试代码本身有无明显失真（mock 掩盖真实行为、断言空洞）\n\n` +
      `【判定】feasibility=fixable（一个 PR 内 1-3 个用例可补齐）或 heavy（需大型基建，仅报告不施工）\n` +
      `【硬性要求】只读分析：不修改文件、不运行测试套件；每条缺口必须给出 target/kind/why/suggestion（建议的测试文件、用例名、关键断言）/feasibility；宁缺毋滥`,
    ),
  ));
  const gapAll = gapResults.flatMap((r) => r.gaps);
  log(`测试覆盖分析共 ${gapAll.length} 条原始缺口`);

  phase("分诊测试缺口");
  let bucketed: BucketedGap[] = [];
  if (gapAll.length > 0) {
    const t = await agent("测试缺口分诊员").ask<BucketSet>(
      `以下是测试覆盖分析员的原始缺口清单。请：\n` +
      `1) 去重、剔除伪缺口（其实已有覆盖或价值很低）；\n` +
      `2) fixable 项总数上限 8，按价值排序；\n` +
      `3) 每条分配 bucket：agent-framework / backend-frontend / report-only（heavy 或低价值项一律 report-only；涉及修改 .github CI 配置或生产代码的也是 report-only）；\n` +
      `4) 编号 T1、T2……\n原始清单（JSON）：\n${JSON.stringify(gapAll, null, 1)}`,
    );
    bucketed = t.items;
  }

  phase("独立核实测试缺口");
  const fixableGaps = bucketed.filter((g) => g.bucket !== "report-only");
  const gapVerdicts = fixableGaps.length > 0
    ? await Promise.all(fixableGaps.map((g) =>
        agent(`缺口核实员-${g.id}`).ask<Verdict>(
          `你是独立复核员。复核这条测试缺口是否真实存在且值得补：\n${JSON.stringify(g, null, 1)}\n\n` +
          `要求：亲自在 /root/agent-manager 搜索测试文件确认没有等价覆盖、读相关源码确认 suggestion 可行（≤3 用例）；不修改文件、不运行测试套件。\n` +
          `verdict：confirmed / refuted / adjusted；corrected 给出最终可施工的用例清单。`,
        )))
    : [];
  const verdictGapById = new Map<string, Verdict>();
  for (const v of gapVerdicts) verdictGapById.set(v.id, v);
  const confirmedAf: BucketedGap[] = [];
  const confirmedBe: BucketedGap[] = [];
  const ledgerC: LedgerItem[] = [];
  for (const g of bucketed) {
    if (g.bucket === "report-only") {
      const item: LedgerItem = { id: g.id, title: `测试缺口：${g.target}`, path: g.target, category: `test-gap/${g.kind}`, severity: "low", status: "unconfirmed", disposition: "仅报告（heavy/低价值/涉 CI 配置）" };
      ledgerC.push(item);
      report(item, "findings");
      continue;
    }
    const v = verdictGapById.get(g.id);
    if (v === undefined || v.verdict === "refuted") {
      const item: LedgerItem = { id: g.id, title: `测试缺口：${g.target}`, path: g.target, category: `test-gap/${g.kind}`, severity: "low", status: v === undefined ? "unconfirmed" : "refuted", disposition: v === undefined ? "仅报告" : "独立核实未成立" };
      ledgerC.push(item);
      report(item, "findings");
      continue;
    }
    const item: LedgerItem = { id: g.id, title: `测试缺口：${g.target}`, path: g.target, category: `test-gap/${g.kind}`, severity: "low", status: "verified", disposition: "待测试 PR" };
    ledgerC.push(item);
    report(item, "findings");
    const confirmed: BucketedGap = { ...g, suggestion: v.verdict === "adjusted" ? v.corrected : g.suggestion };
    if (g.bucket === "agent-framework") confirmedAf.push(confirmed);
    else confirmedBe.push(confirmed);
  }

  phase("补齐测试并提 PR");
  const fixerBrief = (bucketLabel: string, bucketKey: string, branchName: string, scope: string, gaps: BucketedGap[]) =>
    `你负责补齐以下测试缺口（bucket：${bucketLabel}），产出一次 PR（仓库 gaoyue1989/agent-manager）。\n${PROXY}\n\n` +
    `【工作方式——必须严格遵守】\n` +
    `1) 在独立 git worktree 工作，绝不触碰 /root/agent-manager 主工作区（不得在主工作区 checkout/stash/commit）：\n` +
    `   git -C /root/agent-manager worktree add /tmp/wt-test-${bucketKey} -b ${branchName} master\n` +
    `2) 按 gap.suggestion 写测试，遵循各模块现有测试风格（backend: go test 表驱动；agent-framework: JUnit5，参照 src/test 同包已有用例；frontend: *.test.mjs）。每个用例必须有真实断言，禁止空转\n` +
    `3) 只新增/修改测试；若补测试必须改生产代码才能通过，说明这是缺陷——放弃该条并记入 notHandled；不要修改 AGENTS.md 或任何文档（文档由另一 PR 处理）\n` +
    `4) 本地验证（在 worktree 内）：agent-framework 用 mvn -f agent-framework/pom.xml test -Dtest=<类名>；backend 用 cd backend && go test ./internal/... -run <TestName>；frontend 需先在 worktree 的 frontend/ 内 npm install 再 npm test。失败就修到通过（最多迭代 3 轮；修不动就撤销该用例并记入 notHandled）\n` +
    `5) git add 相关测试文件 && commit -m "test(${scope}): 补齐近 ${days} 天变更的测试覆盖缺口"\n` +
    `6) 推送：git -C /tmp/wt-test-${bucketKey}${GIT_PROXY} push -u origin ${branchName}\n` +
    `7) 建 PR：${GH_PROXY} gh pr create -R gaoyue1989/agent-manager --base master --head ${branchName} --title "test(${scope}): 补齐近 ${days} 天变更的测试覆盖缺口" --body <逐条缺口与本地验证方式>\n` +
    `   （若分支已存在导致 push 被拒，改用 ${branchName}-2 并同步调整）\n` +
    `8) 不要尝试合并（盯守员负责）；保留 worktree（失败修复时复用）\n\n` +
    `返回 PrOutcome：done/prNumber/prUrl/branch/worktree/summary（补了哪些）/localChecks（命令与结果）/notHandled（未补项+原因）。如实返回，不要伪造。\n` +
    `【缺口清单】\n${JSON.stringify(gaps, null, 1)}`;
  const prTasks: PromiseLike<PrOutcome | null>[] = [];
  prTasks.push(confirmedAf.length > 0
    ? agent("测试补齐·Java框架").ask<PrOutcome>(fixerBrief("agent-framework", "af", `test/review-gaps-af-${since}`, "agent-framework", confirmedAf))
    : Promise.resolve(null));
  prTasks.push(confirmedBe.length > 0
    ? agent("测试补齐·后端前端").ask<PrOutcome>(fixerBrief("backend-frontend", "be", `test/review-gaps-be-${since}`, "backend-frontend", confirmedBe))
    : Promise.resolve(null));
  const prResults = await Promise.all(prTasks);
  const testPrs: PrRef[] = [];
  const prRaws: PrOutcome[] = [];
  for (const p of prResults) {
    if (p === null) continue;
    prRaws.push(p);
    if (p.done && p.prNumber !== undefined && p.branch !== undefined && p.worktree !== undefined) {
      testPrs.push({ n: p.prNumber, url: p.prUrl ?? "", branch: p.branch, worktree: p.worktree, kind: "tests" });
      report({ id: `pr-${p.prNumber}`, title: `测试补齐 PR #${p.prNumber}`, status: "PR 开放", note: p.prUrl ?? "" }, "deliverables");
      for (const it of ledgerC) {
        if (it.status === "verified") {
          it.disposition = `随 PR #${p.prNumber} 补齐`;
          report(it, "findings");
        }
      }
    } else {
      for (const it of ledgerC) {
        if (it.status === "verified") it.disposition = "补齐失败，仅报告";
      }
      log(`测试 PR 未创建：${p.summary}`);
    }
  }
  return { ledger: ledgerC, prs: testPrs, prRaws, bucketed };
});

// ---------- 汇合（盯守需要全部 PR，报告需要全部结果） ----------
const [ra, rb, rc] = await Promise.all([trackA, trackB, trackC]);
const ledger = [...ra.ledger, ...rb.ledger, ...rc.ledger];

const prList: PrRef[] = [];
if (rb.pr.done && rb.pr.prNumber !== undefined && rb.pr.branch !== undefined && rb.pr.worktree !== undefined) {
  prList.push({ n: rb.pr.prNumber, url: rb.pr.prUrl ?? "", branch: rb.pr.branch, worktree: rb.pr.worktree, kind: "docs" });
}
prList.push(...rc.prs);
log(`待盯守 PR：${prList.map((p) => `#${p.n}(${p.kind})`).join(", ") || "无"}`);

// ---------- 盯守 PR 门禁与合并 ----------
const watchPrompt = (round: number) =>
  `第 ${round}/36 轮。检查以下 PR 的门禁与合并状态（仓库 gaoyue1989/agent-manager）：\n${JSON.stringify(prList, null, 1)}\n\n` +
  `${PROXY}\n` +
  `对每个 PR：gh pr view <n> -R gaoyue1989/agent-manager --json state,mergeStateStatus,statusCheckRollup,mergeable\n` +
  `规则：\n` +
  `- 必需检查中 Skipped 视为通过；仅当全部必需检查通过且 mergeable 时执行：gh pr merge <n> -R gaoyue1989/agent-manager --merge（本仓库未开启 auto-merge，禁止 --auto/--squash/--rebase）\n` +
  `- mergeStateStatus=BEHIND 时先 gh pr update-branch <n> -R gaoyue1989/agent-manager，下轮再试合并\n` +
  `- 有必需检查失败 → state=blocked，note 写明失败 job 名与初步原因（可用 gh pr checks <n> -R gaoyue1989/agent-manager）\n` +
  `- 全部 PR 都 merged/closed → allSettled=true\n` +
  `- 本轮没有 PR 落定且都还在跑：sleep 300 后返回当前快照（allSettled=false）\n` +
  `- 一切以命令真实输出为准，绝不编造状态；gh 偶发失败如实标注 state=open 并在 note 说明`;
const boardStatus = (s: WatchRow["state"]) => s === "merged" ? "已合并" : s === "open" ? "PR 开放" : s === "blocked" ? "受阻" : "已关闭";
let finalRows: WatchRow[] = prList.map((p) => ({ pr: p.n, state: "open" as const, note: "回合上限，未落定" }));
if (prList.length > 0) {
  const watcher = agent("盯守员", { system: "你只做 PR 状态核查与合并操作，如实反映每条命令的真实输出，绝不编造状态；被阻塞时如实上报。" });
  let repairs = 0;
  for (let round = 1; round <= 36; round++) {
    phase("盯守 PR 门禁与合并");
    const state = await watcher.ask<WatchState>(watchPrompt(round));
    for (const row of state.rows) {
      const prev = finalRows.find((r) => r.pr === row.pr);
      if (prev === undefined || prev.state !== row.state) {
        const ref = prList.find((p) => p.n === row.pr);
        report({ id: `pr-${row.pr}`, title: `${ref?.kind === "docs" ? "文档" : "测试"} PR #${row.pr}`, status: boardStatus(row.state), note: row.note }, "deliverables");
      }
    }
    finalRows = state.rows;
    const mergedCnt = state.rows.filter((r) => r.state === "merged").length;
    log(`第 ${round} 轮：merged ${mergedCnt}/${state.rows.length}`);
    if (state.allSettled) break;
    const blocked = state.rows.filter((r) => r.state === "blocked");
    if (blocked.length === 0) continue;
    if (repairs >= 2) break; // 修复额度用尽，如实报告
    const target = blocked[0];
    const ref = prList.find((p) => p.n === target.pr);
    if (ref === undefined) break;
    repairs += 1;
    phase("修复失败的 PR");
    await agent(`修复员-${repairs}`, { system: "你是谨慎的工程师：只修 CI 暴露的最小问题，一切以命令真实输出为准，绝不伪造成功；被阻塞时如实上报。" }).ask<PrOutcome>(
      `PR #${target.pr} 的必需检查失败：${target.note}\n\n${PROXY}\n\n` +
      `请在对应 worktree 中修复并推送（分支 ${ref.branch}，worktree ${ref.worktree}）：\n` +
      `1) ${GH_PROXY} gh pr checks ${target.pr} -R gaoyue1989/agent-manager 找到失败 job；${GH_PROXY} gh run view <run-id> -R gaoyue1989/agent-manager --log-failed 看失败日志（只读）\n` +
      `2) 在 ${ref.worktree} 内修最小必要的问题（只修 CI 暴露的问题，不扩大范围）\n` +
      `3) 本地跑对应的最小验证（定向测试/构建）\n` +
      `4) commit（fix: ...）并 git -C ${ref.worktree}${GIT_PROXY} push origin ${ref.branch}，CI 自动重跑\n` +
      `5) 不要合并，不要动主工作区\n` +
      `返回 PrOutcome（done=推送成功；summary=修了什么；localChecks）。失败要如实说。`,
    );
  }
} else {
  log("没有需要盯守的 PR");
}

// ---------- 清理与报告 ----------
phase("清理工作树并输出报告");
const wtPaths = Array.from(new Set([...(rb.pr.worktree ? [rb.pr.worktree] : []), ...rc.prRaws.map((p) => p.worktree ?? "").filter((w) => w !== "")]));
const cleanupNotes: string[] = [];
for (const wt of wtPaths) {
  try {
    await world.run("git", ["worktree", "remove", wt]);
  } catch {
    cleanupNotes.push(`${wt} 未能自动移除（可能有未提交改动），请手动处理`);
  }
}
try {
  await world.run("git", ["worktree", "prune"]);
} catch {
  // prune 失败不影响结论
}
const mergedRows = finalRows.filter((r) => r.state === "merged");
const unmergedRows = finalRows.filter((r) => r.state !== "merged");

const md: string[] = [];
md.push(`# 近 ${days} 天变更评审报告`);
md.push("");
md.push(`窗口：${since} 起 · ${commits.length} 个提交 · ${changed.length} 个文件 · 基线 ${baseParent.slice(0, 8)}..HEAD`);
md.push("");
md.push(`## 概览`);
md.push(`- 代码评审：原始发现 ${ra.ledger.length} 条（已建 Issue ${ra.issueOut.created.length} 个，查重跳过 ${ra.issueOut.skipped.length} 个）`);
md.push(`- 文档时效：滞后项 ${rb.ledger.length} 条，其中 ${rb.docFixes.length} 条经核实落实${rb.pr.prNumber !== undefined ? `（PR #${rb.pr.prNumber}）` : ""}`);
md.push(`- 测试缺口：${rc.bucketed.length} 条，其中 fixable 经核实进入施工${rc.prs.length > 0 ? `（PR ${rc.prs.map((p) => `#${p.n}`).join("、")}）` : ""}`);
md.push("");
md.push(`## Issue 清单`);
if (ra.issueOut.created.length === 0) md.push("- 无");
for (const c of ra.issueOut.created) md.push(`- #${c.number} [${c.title}](${c.url})`);
for (const s of ra.issueOut.skipped) md.push(`- （查重跳过）${s.title}：${s.reason}`);
md.push("");
md.push(`## PR 与合并状态`);
if (prList.length === 0) md.push("- 无 PR");
for (const row of finalRows) md.push(`- PR #${row.pr}：${row.state} —— ${row.note}`);
md.push("");
md.push(`## 文档修改明细`);
if (rb.docFixes.length === 0) md.push("- 无");
for (const f of rb.docFixes) md.push(`- ${f.path}（${f.section}）：${f.fix}`);
md.push("");
md.push(`## 测试补齐明细`);
const fixedGaps = rc.bucketed.filter((g) => g.bucket !== "report-only");
if (fixedGaps.length === 0) md.push("- 无");
for (const g of fixedGaps) md.push(`- ${g.target}（${g.kind}）：${g.suggestion}`);
md.push("");
md.push(`## 发现台账`);
md.push("| 编号 | 问题 | 位置 | 分类 | 严重度 | 复核状态 | 处置 |");
md.push("|---|---|---|---|---|---|---|");
for (const it of ledger) md.push(`| ${it.id} | ${it.title} | ${it.path} | ${it.category} | ${it.severity} | ${it.status} | ${it.disposition} |`);
if (cleanupNotes.length > 0) {
  md.push("");
  md.push(`## 清理备注`);
  for (const n of cleanupNotes) md.push(`- ${n}`);
}
await artifact.markdown("report", md.join("\n"), {
  title: `近${days}天变更评审报告`,
  description: "代码评审 Issue、文档时效 PR、测试缺口 PR 的汇总",
  primary: true,
});

const verifiedNotes = [
  `每条 fileIssue 的评审发现、每条文档滞后项与每个 fixable 测试缺口，均由相互独立的复核员以代码/文档证据复核`,
  `测试补齐 PR 在推送前由补齐员本地定向运行了相关用例`,
  `PR 合并以仓库必需 CI 检查通过为准（盯守员逐轮 gh pr view 核查后执行合并）`,
  `窗口盘点基于 git log --since=${since} 与 git diff ${baseParent.slice(0, 8)}..HEAD`,
];
const notCovered = [
  "未在本地跑全量 mvn test / go test（由 PR CI 全量执行）",
  "agent-framework/bench/** 只做覆盖分析，未做性能评审",
  "docs/screenshots/ 截图内容未逐一核对",
  "窗口之前的存量代码问题不在本次评审范围",
  ...unmergedRows.map((r) => `PR #${r.pr} 未合并（${r.state}）：${r.note}`),
  ...cleanupNotes,
];
const conclusion = prList.length === 0
  ? `评审完成（${commits.length} 个提交 / ${changed.length} 个文件）：代码评审成立 ${ra.issueOut.created.length + ra.ledger.filter((l) => l.status === "verified").length} 条中的 Issue 已建 ${ra.issueOut.created.length} 个；文档滞后 ${rb.docFixes.length} 条、测试缺口 fixable ${fixedGaps.length} 条均无需 PR 或未能创建 PR，详见报告。`
  : `评审完成（${commits.length} 个提交 / ${changed.length} 个文件）：已建 Issue ${ra.issueOut.created.length} 个；文档 PR ${rb.pr.prNumber !== undefined ? `#${rb.pr.prNumber}` : "未创建"}、测试 PR ${rc.prs.map((p) => `#${p.n}`).join("、") || "未创建"}，其中 ${mergedRows.length} 个已合并${unmergedRows.length > 0 ? `，${unmergedRows.length} 个未落定` : ""}。详见报告与台账。`;
const result: WorkflowReport = {
  conclusion,
  findings: ledger,
  verified: verifiedNotes,
  notCovered,
};
return result;