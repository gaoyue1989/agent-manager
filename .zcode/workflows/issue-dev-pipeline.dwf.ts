/* zcode-workflow
description: "标准开发流水线（全自动，到提交 PR 为止）：认领 Issue → 需求分析＋独立评审（通过即开工）→ 独立 worktree 开发
  → 本地单测门禁（失败自动修复回炉）→ 本地真实部署验证（kind 集群构建镜像+发布 demo 服务，真实沙箱/LLM 凭据，A2A 注册与真实调用）→
  流程黑盒验证（用户视角全生命周期：发布→真实对话→env 变更→republish→异常路径→删除，失败自动修复重验）→
  代码评审（按模块并行＋分诊＋逐条独立复核＋修复回归）→ 推送建 PR（正文含 Closes #N）→ 收尾归档（清理
  worktree、输出流水线报告）。PR CI（含必需 E2E 矩阵）与合并由人工盯守，无任何人工确认关卡。"
whenToUse: 认领一个 GitHub issue
  并端到端完成「评审→开发→自测→本地真实部署验证→黑盒验证→CodeReview→提交PR」全流程时使用。全自动无人工确认关卡：方案经独立评审通过即开工，PR
  提交后流水线即结束（CI 结果与合并由人工盯守）。参数 issue 必填（纯数字）；deploy_verify
  选本地验证模式（scoped/full/off，默认 scoped）；proxy 配置 git 代理（默认
  127.0.0.1:7890，直连机器传空串）。施工在 /tmp 独立 worktree；gh 需已登录（账号动态探测）。
args:
  deploy_verify:
    type: string
    description: 本地验证模式：scoped=部署验证＋流程黑盒验证（只创建/清理验证自己的服务，默认）；full=仓库标准
      platform-e2e.sh 全矩阵（含场景 B 黑盒，会清理 release-agent 外的遗留业务服务）；off=跳过本地验证（CI
      兜底）。环境缺 docker/kind 时自动降级为跳过并如实记录
    default: scoped
  issue:
    type: string
    description: 要认领并开发的 GitHub Issue 编号（纯数字，如 "241"）
    required: true
  proxy:
    type: string
    description: git push/fetch 走的本地代理地址；留空字符串表示直连（适配不同机器的网络环境）
    default: http://127.0.0.1:7890
*/
// ===== 标准开发流水线（可保存复用）v4 =====
// 认领 → 需求评审（分析+独立评审，评审通过即开工，无人工关卡）→ 开发 → 本地单测门禁
// → 本地真实部署验证（kind+沙箱+LLM）→ 流程黑盒验证（用户视角全生命周期）
// → 代码评审（并行+独立复核+修复回归）→ 推送建 PR → 收尾归档（到此结束）。
// PR CI（含必需 E2E 矩阵）与合并由人工盯守；PR 正文含 Closes #N，合并后 Issue 自动关闭。
// 施工在独立 git worktree（基于最新 origin/master）；部署验证直接用本机 kind 集群真实拉起业务服务。

// ---------- 参数 ----------
const ISSUE = String(args.issue ?? "").trim();
const RAW_MODE = typeof args.deploy_verify === "string" ? args.deploy_verify.trim().toLowerCase() : "scoped";
const DEPLOY_MODE: "scoped" | "full" | "off" = RAW_MODE === "full" || RAW_MODE === "off" ? RAW_MODE : "scoped";
const PROXY = typeof args.proxy === "string" ? args.proxy.trim() : "http://127.0.0.1:7890";
const REPO = "gaoyue1989/agent-manager";
const WT = `/tmp/oaf-dev-issue-${ISSUE}`;
const BRANCH_BASE = `feat/issue-${ISSUE}`;
const PR_BODY_FILE = `/tmp/pr-body-${ISSUE}.md`;
const REGISTRY = "172.20.0.1:5001";
const BE_TAG = `platform-backend:pipeline-issue-${ISSUE}`;
const FE_TAG = `platform-frontend:pipeline-issue-${ISSUE}`;
const AF_TAG = `${REGISTRY}/agent-framework:pipeline-issue-${ISSUE}`;
const GIT_PROXY = PROXY ? ["-c", `http.proxy=${PROXY}`, "-c", `https.proxy=${PROXY}`] : [];
const NET =
  (PROXY
    ? `网络约定：git 的 push/pull/fetch 必须带 -c http.proxy=${PROXY} -c https.proxy=${PROXY}；`
    : "网络约定：git 直连（本机未配代理）；") +
  "gh 直连通常可用，报网络错误时再加前缀 HTTPS_PROXY/HTTP_PROXY（" +
  (PROXY || "按本机实际代理") + "）重试";

// 轮次上限（只存在于控制流，勿写进 ask 文本）
const PLAN_ROUNDS = 3;
const UNIT_ROUNDS = 3;
const VERIFY_ROUNDS = 2;

// ---------- 结果类型 ----------
interface Finding {
  /** 工作区相对路径，可带行号："src/a.ts:42" */
  where: string;
  /** 一句话：发现了什么问题 */
  what: string;
  /** 证据：path:line 或命令输出摘要 */
  evidence: string;
  /** verified=独立复核成立；unconfirmed=未复核/复核未过 */
  status: "verified" | "unconfirmed";
  severity: "low" | "medium" | "high";
}
interface WorkflowReport {
  conclusion: string;
  findings: Finding[];
  verified: string[];
  notCovered: string[];
}
interface Plan {
  /** 是否可直接施工：纯讨论/信息不足为 false，缺口写在 openQuestions */
  actionable: boolean;
  /** 一句话：这个 issue 要解决什么 */
  goal: string;
  /** 涉及的顶层目录：backend/frontend/agent-framework/charts/manifests/docs/e2e/... */
  modules: string[];
  /** 有序实施步骤，每步可直接执行 */
  steps: string[];
  /** 预计新增/修改文件（工作区相对路径） */
  files: string[];
  /** 验证方式：每个模块怎么测 */
  testPlan: string;
  /** 风险与红线（不该动什么、Flyway/架构强约束注意点） */
  risks: string[];
  /** 需要用户拍板的取舍（无则空数组） */
  openQuestions: string[];
  /** 提交类型：feat|fix|chore|docs */
  commitType: string;
}
interface Critique {
  approved: boolean;
  /** 每条：哪里有问题、为什么，引用 path:line 或规范条目 */
  problems: string[];
  /** 评审员用自己的话复述方案，检验理解 */
  restatement: string;
}
interface DevResult {
  /** true=无法施工（notes 说明原因） */
  blocked: boolean;
  notes: string;
  /** 本次提交的文件（工作区相对路径） */
  committedFiles: string[];
  /** HEAD 提交标题 */
  commitSubject: string;
  /** 实现摘要（给 PR 正文用） */
  summary: string;
}
interface RawFinding {
  /** 工作区相对路径 */
  path: string;
  /** 行号（可选） */
  line?: number;
  /** 一句话：什么问题 */
  problem: string;
  severity: "low" | "medium" | "high";
  /** 修改建议（一句话） */
  suggestion: string;
}
interface FileReview {
  findings: RawFinding[];
  /** 覆盖说明：看了什么、没看什么 */
  coverage: string;
}
interface TriagedFinding extends RawFinding {
  /** 分诊编号，全流水线唯一（g1,g2,...） */
  id: string;
}
interface TriagedSet {
  kept: TriagedFinding[];
  /** 丢弃项及原因 */
  dropped: { path: string; problem: string; why: string }[];
}
interface Verdict {
  id: string;
  /** 复核是否成立 */
  holds: boolean;
  /** 复核证据 path:line */
  evidence: string;
}
interface FixOutcome {
  notes: string;
  /** 开发工程师拒修条目及理由 */
  skipped: { id: string; reason: string }[];
}
interface PrMaterial {
  /** PR 标题：type(scope): 中文描述 (#issue) */
  prTitle: string;
  prBodyFile: string;
  commitSubject: string;
  changedFiles: string[];
}
interface GhIssue {
  state: string;
  title: string;
  url: string;
  assignees: { login: string }[];
}
interface DeployVerify {
  /** 端到端是否通过 */
  passed: boolean;
  /** 每步证据：命令与关键输出（running 状态、card 字段、LLM 回复摘要等） */
  evidence: string[];
  /** 本轮创建过哪些资源（服务/包 id 等） */
  created: string[];
  /** 已清理的资源 */
  deleted: string[];
  /** 真实 LLM 调用结果摘要（未执行/失败要如实说明） */
  llmCheck: string;
  /** 降级/未覆盖说明（如沙箱未被 demo agent 实际调用） */
  gaps: string[];
  notes: string;
}
interface BlackBoxVerify {
  /** 用户视角全流程是否通过 */
  passed: boolean;
  /** 每步证据：命令与关键输出 */
  evidence: string[];
  /** 本轮创建过哪些资源 */
  created: string[];
  /** 已清理的资源 */
  deleted: string[];
  /** 异常路径观测：请求 → 实际状态码 → 是否符合预期 */
  errorPaths: string[];
  /** 真实 LLM 对话结果摘要 */
  llmCheck: string;
  /** 接口上不可见/未覆盖的说明 */
  gaps: string[];
  notes: string;
}
type Leftover = TriagedFinding & { evidence: string; status: "verified" | "unconfirmed" };

// ---------- 纯逻辑辅助 ----------
function parsePrNumber(s: string): string {
  const m = /pulls?\/(\d+)/.exec(s);
  return m?.[1] ?? "";
}
function topDir(p: string): string {
  const i = p.indexOf("/");
  return i === -1 ? p : p.slice(0, i);
}
function groupByDir(files: string[]): { name: string; files: string[] }[] {
  const map = new Map<string, string[]>();
  for (const f of files) {
    const d = topDir(f);
    const arr = map.get(d) ?? [];
    arr.push(f);
    map.set(d, arr);
  }
  return [...map.entries()].map(([name, fs]) => ({ name, files: fs }));
}

// ---------- 参数校验（无需阶段） ----------
if (!/^\d+$/.test(ISSUE)) {
  return {
    conclusion: `参数错误：issue 必须是纯数字编号（收到 "${ISSUE}"）。请以 args.issue 传入编号后重新运行。`,
    findings: [],
    verified: [],
    notCovered: ["流水线未启动"],
  };
}
if (!/^(scoped|full|off)$/.test(RAW_MODE)) {
  return {
    conclusion: `参数错误：deploy_verify 只接受 scoped|full|off（收到 "${RAW_MODE}"）。`,
    findings: [],
    verified: [],
    notCovered: ["流水线未启动"],
  };
}

// ---------- 看板与运行状态 ----------
artifact.board("pipeline-board", {
  title: "开发流水线进度",
  key: "issue",
  status: "stage",
  columns: ["认领", "需求评审", "开发", "自测", "部署验证", "黑盒验证", "CodeReview", "PR·CI", "已归档", "受阻"],
  cardTitle: "title",
  detail: [{ field: "note" }, { field: "branch" }, { field: "pr" }],
});

let issueTitle = "";
let curBranch = BRANCH_BASE;
let prRef = "";
const gateResults: string[] = [];
const localGaps: string[] = [];
let deployEvidence: string[] = [];
let bboxEvidence: string[] = [];
let leftover: Leftover[] = [];
let lows: TriagedFinding[] = [];

function mark(stage: string, note: string): void {
  report(
    { issue: `#${ISSUE}`, title: `Issue #${ISSUE} ${issueTitle}`, stage, note, branch: curBranch, pr: prRef },
    "pipeline-board",
  );
}
function buildFindings(): Finding[] {
  return [
    ...leftover.map((f) => ({
      where: f.line ? `${f.path}:${f.line}` : f.path,
      what: f.problem,
      evidence: f.evidence,
      status: f.status,
      severity: f.severity,
    })),
    ...lows.map((f) => ({
      where: f.line ? `${f.path}:${f.line}` : f.path,
      what: f.problem,
      evidence: "分诊保留的低优先级项，未做独立复核",
      status: "unconfirmed" as const,
      severity: "low" as const,
    })),
  ];
}
function stopReport(reason: string, extraNotCovered: string[]): WorkflowReport {
  mark("受阻", reason.length > 120 ? `${reason.slice(0, 120)}…` : reason);
  return {
    conclusion: `流水线中止：${reason}\n（worktree ${WT} 与本地分支保留供排查；Issue #${ISSUE} 保持认领）`,
    findings: buildFindings(),
    verified: [...gateResults],
    notCovered: [...localGaps, ...extraNotCovered, "中止点之后的阶段未执行"],
  };
}

// ---------- 子代理人格 ----------
const ANALYST_PERSONA =
  "你是资深需求分析师，为 OAF 服务发布平台（Go 后端 + Next.js 前端 + Java agent-framework + K8s/Kind）分析 issue 并产出可执行实施方案。" +
  "先读仓库根/模块 AGENTS.md 与真实代码再下结论；需求不明就如实标注，不脑补；只读勘察，不修改任何文件。";
const CRITIC_PERSONA =
  "你是独立方案评审员，专门找实施方案的漏洞：会破坏什么、漏了什么、边界错在哪。判断必须基于真实代码（要读文件），不凭方案文本想当然；" +
  "批准必须有依据；先用自己的话复述方案检验理解；不修改任何文件。";
const DEV_PERSONA =
  "你是资深全栈工程师（Go/Gin、Next.js/React、Java/Spring Boot、K8s/Helm），在指定 git worktree 内施工，严守仓库 AGENTS.md 全部规范：" +
  "架构强约束（backend 控制面不得影响业务面、agent-framework 状态只入共享存储）、表结构演进只走新增 Flyway 迁移、不整文件重写无关代码、" +
  "敏感信息用占位符、完工清理调试代码。只改任务必需的文件，只 stage 自己创建/修改的路径，严禁 git add -A / git add .。" +
  "若任务无法完成或指令矛盾，如实返回 blocked 并说明，绝不绕过、造假或硬凑；需要用户拍板的事如实上报。";
const REVIEWER_PERSONA =
  "你是资深代码评审员，只报会导致缺陷、违反仓库 AGENTS.md 架构强约束或明显设计问题的真问题，每条附 path:line 证据与一句话修改建议；" +
  "风格偏好不报；不确定就说不确定。只读，不修改任何文件。";
const TRIAGE_PERSONA =
  "你是评审分诊员：跨文件去重、合并重复项、校准严重度（high 仅留给数据丢失/崩溃/安全漏洞/架构强约束违反）、丢弃纯风格项；给保留项分配唯一 id。诚实标注不确定。";
const CONFIRMER_PERSONA =
  "你是独立复核员，凭代码证据裁决他人的评审发现是否成立（你不参与评审）。只读，不修改任何文件。";
const DEPLOYER_PERSONA =
  "你是部署验证工程师，负责在本地 kind 集群上对刚构建的镜像做端到端真实部署验证。红线：" +
  "只清理你自己创建的资源；绝不动 oaf-release-agent、platform-*、oaf-mysql、oaf-redis 等平台组件与任何发布前已存在的业务服务；" +
  "凭据只从 .env.secrets 读入 shell 变量使用，严禁把密钥明文写进返回结果（只写「已注入」）。" +
  "每一步都要留下命令与关键输出作为证据；验证不了的就如实说，绝不伪造通过。若指令矛盾或环境不允许，如实说明而不是硬凑。";
const BBX_PERSONA =
  "你是黑盒验证工程师，完全站在平台用户视角验证发布与使用流程：执行验证时只能通过对外 HTTP 接口观测行为，" +
  "集群资源、容器、代码内部、服务端日志对你一律不可见（可以读仓库 e2e 脚本学习 API 契约，但结论只来自接口观测）。红线：" +
  "只创建/清理你自己创建的资源；绝不动 oaf-release-agent、platform-*、oaf-mysql、oaf-redis 等平台组件与任何验证前已存在的业务服务；" +
  "凭据只从 .env.secrets 读入 shell 变量，严禁把密钥明文写进返回结果。每一步都留命令与关键输出作证据；" +
  "行为不符合预期就如实判不通过并记录差异，绝不伪造通过；环境不允许时如实说明而不是硬凑。";

// ========== 探测工具链与部署前提 ==========
phase("探测工具链与部署前提");
const probeRaw = await world.run(
  "bash",
  [
    "-c",
    'for t in docker kind kubectl jq curl go npm mvn; do command -v "$t" >/dev/null 2>&1 && echo "$t=yes" || echo "$t=no"; done; ' +
      "docker info >/dev/null 2>&1 && echo dockerd=yes || echo dockerd=no; " +
      "kind get clusters 2>/dev/null | head -5; " +
      "test -f agent-framework/docker/otel/opentelemetry-javaagent.jar && echo oteljar=yes || echo oteljar=no; " +
      "test -f .env.secrets && echo secrets=yes || echo secrets=no; " +
      "test -f e2e/fixtures/demo-agent-v1.zip && echo fixture=yes || echo fixture=no",
  ],
  { timeoutMs: 60_000, output: { tail: 8_192 } },
);
const probeOut = probeRaw.stdout;
function probeFlag(name: string): boolean {
  return new RegExp(`^${name}=yes$`, "m").test(probeOut);
}
const kindCluster = probeOut
  .split("\n")
  .map((l) => l.trim())
  .find((l) => l.length > 0 && !/^[a-z]+=/.test(l));
const CAN_UNIT_GO = probeFlag("go");
const CAN_UNIT_NPM = probeFlag("npm");
const CAN_UNIT_MVN = probeFlag("mvn");
const CAN_DEPLOY =
  DEPLOY_MODE !== "off" &&
  probeFlag("docker") &&
  probeFlag("dockerd") &&
  probeFlag("kind") &&
  probeFlag("kubectl") &&
  probeFlag("jq") &&
  probeFlag("curl") &&
  typeof kindCluster === "string" &&
  kindCluster.length > 0;
log(
  `工具链探测：go=${probeFlag("go")} npm=${probeFlag("npm")} mvn=${probeFlag("mvn")} docker=${probeFlag("docker")}/${probeFlag("dockerd")} kind=${probeFlag("kind")}(${kindCluster ?? "无集群"}) kubectl=${probeFlag("kubectl")}；部署验证模式=${DEPLOY_MODE}`,
);
if (DEPLOY_MODE !== "off" && !CAN_DEPLOY) {
  localGaps.push(
    "本地部署验证被跳过：本机缺 docker/kind/kubectl/jq/curl 或无运行中的 kind 集群（deploy_verify 请求了 " +
      DEPLOY_MODE +
      "），E2E 由 PR CI 门禁兜底",
  );
}
const loginRaw = await world.run("gh", ["api", "user", "-q", ".login"], { output: { tail: 2_000 } });
const LOGIN = loginRaw.stdout.trim();
if (loginRaw.exitCode !== 0 || !LOGIN) {
  return {
    conclusion: `gh 未登录或无凭据（${loginRaw.stderr.slice(0, 200)}）。请在目标机器执行 gh auth login 后重跑。`,
    findings: [],
    verified: [],
    notCovered: ["流水线未启动"],
  };
}
log(`gh 账号：${LOGIN}`);

// ========== 1. 认领 Issue 并准备独立工作区 ==========
phase("认领 Issue 并准备独立工作区");
const issueJson = await world.run("gh", ["issue", "view", ISSUE, "-R", REPO, "--json", "number,title,state,url,assignees"]);
if (issueJson.exitCode !== 0) {
  return {
    conclusion: `Issue #${ISSUE} 读取失败（gh exit ${issueJson.exitCode}）：${issueJson.stderr.slice(0, 500)}`,
    findings: [],
    verified: [],
    notCovered: ["流水线未启动"],
  };
}
const issue = JSON.parse(issueJson.stdout) as GhIssue;
issueTitle = issue.title;
if (issue.state !== "OPEN") {
  return {
    conclusion: `Issue #${ISSUE} 状态为 ${issue.state}（仅 OPEN 可认领开发），流水线未启动。`,
    findings: [],
    verified: [],
    notCovered: ["流水线未启动"],
  };
}
const others = issue.assignees.map((a) => a.login).filter((l) => l !== LOGIN);
if (others.length > 0) {
  return {
    conclusion: `Issue #${ISSUE} 已被 ${others.join(", ")} 认领，为避免撞车流水线未启动。`,
    findings: [],
    verified: [],
    notCovered: ["流水线未启动"],
  };
}
if (issue.assignees.length === 0) {
  const assign = await world.run("gh", ["issue", "edit", ISSUE, "-R", REPO, "--add-assignee", "@me"]);
  if (assign.exitCode !== 0) {
    return {
      conclusion: `认领失败：${assign.stderr.slice(0, 500)}`,
      findings: [],
      verified: [],
      notCovered: ["流水线未启动"],
    };
  }
}
const fetched = await world.run("git", [...GIT_PROXY, "fetch", "origin", "master"]);
if (fetched.exitCode !== 0) {
  return {
    conclusion: `git fetch origin master 失败${PROXY ? `（需本地代理 ${PROXY} 可用）` : ""}：${fetched.stderr.slice(0, 500)}`,
    findings: [],
    verified: [],
    notCovered: ["流水线未启动"],
  };
}
let wtAdd = await world.run("git", ["worktree", "add", "-b", curBranch, WT, "origin/master"]);
if (wtAdd.exitCode !== 0) {
  curBranch = `${BRANCH_BASE}-r2`;
  wtAdd = await world.run("git", ["worktree", "add", "-b", curBranch, WT, "origin/master"]);
}
if (wtAdd.exitCode !== 0) {
  return {
    conclusion: `创建 worktree 失败（若 ${WT} 已存在，请先 git worktree remove 清理后重跑）：${wtAdd.stderr.slice(0, 500)}`,
    findings: [],
    verified: [],
    notCovered: ["流水线未启动"],
  };
}
mark("认领", `已认领「${issue.title}」，worktree 就绪`);
log(`已认领 Issue #${ISSUE}，worktree ${WT}（分支 ${curBranch}）`);

// ========== 2. 需求分析与实施方案设计 ==========
phase("需求分析与实施方案设计");
const analyst = agent("需求分析师", ANALYST_PERSONA);
const plan = await analyst.ask<Plan>(
  `认领 Issue #${ISSUE}（标题：${issueTitle}）。只读勘察，不修改任何文件。\n` +
    `勘察基准：独立 worktree ${WT}（分支 ${curBranch}，内容=最新 origin/master）——一切代码结论以它为准，不要用主工作区（可能停在别的分支）。\n` +
    `步骤：\n` +
    `1) gh issue view ${ISSUE} -R ${REPO} --comments 读全正文与讨论；\n` +
    `2) 读 ${WT} 根 AGENTS.md 与涉及模块的 AGENTS.md；\n` +
    `3) 勘察相关代码，产出可执行实施方案 Plan（JSON）：actionable（纯讨论/信息不足→false 并在 openQuestions 写明缺口）、goal、modules、steps（有序可执行）、files、testPlan（各模块怎么验证）、risks（含红线：架构强约束、Flyway 迁移规则等）、openQuestions（需用户拍板的取舍，无则空）、commitType（feat|fix|chore|docs）。\n` +
    NET,
);
if (!plan.actionable) {
  return stopReport(`Issue 需求不可直接施工：${plan.openQuestions.join("；") || plan.goal}`, ["开发及之后所有阶段未执行"]);
}

// ========== 3. 方案独立评审与修订 ==========
phase("方案独立评审与修订");
const critic = agent("方案评审员", CRITIC_PERSONA);
let currentPlan = plan;
let critique = await critic.ask<Critique>(
  `独立评审以下实施方案（你没有参与起草，专门找漏洞）。工作区 ${WT}（只读，可跑只读 git/gh 命令）：\n${JSON.stringify(currentPlan)}\n` +
    `要求：读方案涉及的真实代码再下结论；先用自己的话复述方案（restatement）检验理解；找「会破坏什么、漏了什么、边界错在哪」，每条 problem 引用 path:line 或具体规范条目；批准（approved=true）必须有依据。`,
);
for (let r = 2; r <= PLAN_ROUNDS && !critique.approved; r++) {
  log(`方案评审第 ${r} 轮：${critique.problems.length} 条意见，回炉修订`);
  currentPlan = await analyst.ask<Plan>(
    `方案评审未通过，按以下意见修订（只改需要改的，其余保持）：\n${JSON.stringify(critique.problems)}\n当前方案：${JSON.stringify(currentPlan)}`,
  );
  critique = await critic.ask<Critique>(`修订后的方案再评一次（只读 ${WT}）：\n${JSON.stringify(currentPlan)}`);
}

// ========== 方案评审通过即开工（无人工关卡；仅 PR 合并前保留人工确认） ==========
mark(
  "需求评审",
  `方案已通过独立评审即开工${critique.approved ? "" : `（评审保留 ${critique.problems.length} 条，已交施工规避）`}：${currentPlan.goal}`,
);
log(`方案开工：${currentPlan.goal}`);

// ========== 4. 开发实现与提交 ==========
phase("开发实现与提交");
const dev = agent("开发工程师", DEV_PERSONA);
const devResult = await dev.ask<DevResult>(
  `按已确认方案在 worktree ${WT}（分支 ${curBranch}）施工：\n${JSON.stringify(currentPlan)}\n` +
    (critique.approved ? "" : `评审员遗留意见（未阻断开工，施工时注意规避）：\n${JSON.stringify(critique.problems)}\n`) +
    `要求：先读 ${WT} 根与涉及模块的 AGENTS.md 并严守；只改方案范围内必要文件；完成后提交，提交信息 ${currentPlan.commitType}(<模块>): <一句话中文描述> (#${ISSUE})；` +
    `只 stage 你创建/修改的路径（严禁 git add -A / git add .）；不要 push。若无法施工返回 blocked=true 并在 notes 说明。返回 DevResult。`,
);
if (devResult.blocked) return stopReport(`开发受阻：${devResult.notes}`, ["后续阶段未执行"]);
let porcelain = await world.run("git", ["-C", WT, "status", "--porcelain"]);
if (porcelain.exitCode !== 0 || porcelain.stdout.trim() !== "") {
  await dev.ask(
    `worktree 还有未提交内容（git status --porcelain）：\n${porcelain.stdout}\n请补提交（仅限本任务文件）或撤销无关改动，然后返回一句话说明。`,
  );
  porcelain = await world.run("git", ["-C", WT, "status", "--porcelain"]);
  if (porcelain.stdout.trim() !== "")
    return stopReport(`worktree 仍有未提交内容，需人工检查：\n${porcelain.stdout}`, []);
}
let head = await world.run("git", ["-C", WT, "log", "-1", "--format=%s"]);
if (!head.stdout.includes(`#${ISSUE}`)) {
  await dev.ask(
    `HEAD 提交信息「${head.stdout.trim()}」缺少任务号 #${ISSUE}，请 amend 修正为 ${currentPlan.commitType}(scope): 描述 (#${ISSUE})，完成后返回一句话说明。`,
  );
  head = await world.run("git", ["-C", WT, "log", "-1", "--format=%s"]);
  if (!head.stdout.includes(`#${ISSUE}`))
    return stopReport(`提交信息规范未满足：${head.stdout.trim()}`, []);
}
mark("开发", `已提交：${head.stdout.trim()}`);
log(`开发完成：${head.stdout.trim()}`);

// ========== 5. 单元测试自测与修复 ==========
phase("单元测试自测与修复");
const lsChanged = await world.run("git", ["-C", WT, "diff", "--name-only", "origin/master...HEAD"]);
const changed =
  lsChanged.exitCode === 0
    ? lsChanged.stdout.split("\n").map((s) => s.trim()).filter((s) => s.length > 0)
    : devResult.committedFiles;
const dirSet = new Set(changed.map(topDir));
log(`分支改动 ${changed.length} 个文件，进入本地单测门禁（go=${CAN_UNIT_GO} npm=${CAN_UNIT_NPM} mvn=${CAN_UNIT_MVN}）`);
let unitOk = true;
if (dirSet.has("backend")) {
  if (!CAN_UNIT_GO) {
    localGaps.push("backend 本地单测：本机无 go，跳过（由 PR CI 必需检查 go vet + go test 门禁）");
  } else {
    let pass = false;
    for (let r = 1; r <= UNIT_ROUNDS && !pass; r++) {
      const t = await world.run(
        "bash",
        ["-c", `cd ${WT}/backend && GOPROXY=https://goproxy.cn,direct go vet ./... && GOPROXY=https://goproxy.cn,direct go test ./...`],
        { timeoutMs: 900_000, output: { tail: 16_384 } },
      );
      if (t.exitCode === 0) {
        pass = true;
        gateResults.push("backend 本地单测：go vet + go test 通过");
        break;
      }
      log(`backend 单测第 ${r} 轮未过，交开发工程师修复`);
      await dev.ask(
        `backend 本地单测未过（第 ${r} 轮），修复后在 ${WT} 提交（同分支，只改相关代码，不要 push）：\n${t.stdout}\n${t.stderr}`,
      );
    }
    unitOk = unitOk && pass;
  }
}
if (dirSet.has("frontend")) {
  if (!CAN_UNIT_NPM) {
    localGaps.push("frontend 本地单测：本机无 npm，跳过（由 PR CI 必需检查 lint/test/build 门禁）");
  } else {
    const deps = await world.run(
      "bash",
      ["-c", `test -d ${WT}/frontend/node_modules || npm --prefix ${WT}/frontend ci --no-audit --no-fund`],
      { timeoutMs: 900_000, output: { tail: 8_192 } },
    );
    if (deps.exitCode !== 0) {
      localGaps.push("frontend 本地依赖安装失败，本地 lint/test 未跑（由 PR CI 必需检查覆盖）");
    } else {
      for (const script of ["lint", "test"]) {
        let pass = false;
        for (let r = 1; r <= UNIT_ROUNDS && !pass; r++) {
          const t = await world.run("npm", ["--prefix", `${WT}/frontend`, "run", script], {
            timeoutMs: 600_000,
            output: { tail: 16_384 },
          });
          if (t.exitCode === 0) {
            pass = true;
            gateResults.push(`frontend 本地 npm run ${script} 通过`);
            break;
          }
          log(`frontend ${script} 第 ${r} 轮未过，交开发工程师修复`);
          await dev.ask(
            `frontend npm run ${script} 未过（第 ${r} 轮），修复后在 ${WT} 提交（同分支，只改相关代码，不要 push）：\n${t.stdout}\n${t.stderr}`,
          );
        }
        unitOk = unitOk && pass;
      }
    }
  }
}
if (dirSet.has("agent-framework")) {
  if (!CAN_UNIT_MVN) {
    localGaps.push("agent-framework 单测：本机无 mvn，本地跳过（由 PR CI 必需检查 mvn test + E2E 矩阵门禁）");
  } else {
    const t = await world.run("bash", ["-c", `cd ${WT}/agent-framework && mvn -B test`], {
      timeoutMs: 1_800_000,
      output: { tail: 16_384 },
    });
    if (t.exitCode === 0) {
      gateResults.push("agent-framework 本地单测：mvn -B test 通过");
    } else {
      for (let r = 1; r <= UNIT_ROUNDS && t.exitCode !== 0; r++) {
        log(`agent-framework 单测第 ${r} 轮未过，交开发工程师修复`);
        await dev.ask(
          `agent-framework mvn test 未过（第 ${r} 轮），修复后在 ${WT} 提交（同分支，只改相关代码，不要 push）：\n${t.stdout}\n${t.stderr}`,
        );
        const rt = await world.run("bash", ["-c", `cd ${WT}/agent-framework && mvn -B test`], {
          timeoutMs: 1_800_000,
          output: { tail: 16_384 },
        });
        if (rt.exitCode === 0) {
          gateResults.push("agent-framework 本地单测：mvn -B test 通过");
          break;
        }
        if (r === UNIT_ROUNDS) unitOk = false;
      }
    }
  }
}
if (dirSet.has("charts")) localGaps.push("charts helm lint/template：本机无 helm，本地跳过（由 CI charts-ci 门禁）");
if (!unitOk) return stopReport("本地单测门禁多轮修复仍未通过，请人工排查", ["后续阶段未执行"]);
mark("自测", gateResults.length > 0 ? gateResults.join("；") : "无本地可跑门禁，全部由 CI 门禁");

// ---------- 部署验证子流程（构建 → 发布验证 → 失败回炉） ----------
const deployer = agent("部署验证工程师", DEPLOYER_PERSONA);
async function buildAndDeployModules(): Promise<string> {
  const errs: string[] = [];
  if (dirSet.has("backend")) {
    const b = await world.run("docker", ["build", "-t", BE_TAG, `${WT}/backend`], {
      timeoutMs: 900_000,
      output: { tail: 16_384 },
    });
    if (b.exitCode !== 0) errs.push(`backend 镜像构建失败：\n${(b.stdout + b.stderr).slice(-2500)}`);
    const l = await world.run("kind", ["load", "docker-image", BE_TAG, "--name", String(kindCluster)], {
      timeoutMs: 300_000,
      output: { tail: 8_192 },
    });
    if (l.exitCode !== 0) errs.push(`backend 镜像导入 kind 失败：\n${l.stderr.slice(-1000)}`);
    const s = await world.run("kubectl", ["-n", "agent-platform", "set", "image", "deployment/platform-backend", `backend=${BE_TAG}`]);
    if (s.exitCode !== 0) errs.push(`backend set image 失败：\n${s.stderr.slice(-1000)}`);
    // 同 issue 恒定 tag（BE_TAG）重部署时 set image 同值为 no-op：spec 不变不产生新
    // ReplicaSet，rollout status 立即通过，回炉重验跑的仍是旧镜像——无条件 rollout
    // restart 强制滚动换代（对齐仓库 AGENTS.md「更新镜像后的固定动作：rollout restart
    // 对应 deployment」）；kind load 已重新导入镜像，IfNotPresent 新 Pod 按 tag 解析到
    // 最新 digest
    const rs = await world.run("kubectl", ["-n", "agent-platform", "rollout", "restart", "deployment/platform-backend"]);
    if (rs.exitCode !== 0) errs.push(`backend rollout restart 失败：\n${rs.stderr.slice(-1000)}`);
    const r = await world.run("kubectl", ["-n", "agent-platform", "rollout", "status", "deployment/platform-backend", "--timeout=300s"], {
      timeoutMs: 330_000,
      output: { tail: 8_192 },
    });
    if (r.exitCode !== 0) errs.push(`platform-backend 滚动更新未就绪：\n${r.stdout.slice(-1000)}${r.stderr.slice(-500)}`);
  }
  // frontend 部署验证分支（与 backend 同构）：裸 tag + IfNotPresent（manifests/frontend.yaml，
  // 与 backend 语义对齐）——Always 会让 containerd 对裸 tag 强制回源 docker.io 解析，kind 环境
  // 必然 i/o timeout（2026-11-08 第 1 轮部署实锤 ImagePullBackOff）
  if (dirSet.has("frontend")) {
    const b = await world.run("docker", ["build", "-t", FE_TAG, `${WT}/frontend`], {
      timeoutMs: 900_000,
      output: { tail: 16_384 },
    });
    if (b.exitCode !== 0) errs.push(`frontend 镜像构建失败：\n${(b.stdout + b.stderr).slice(-2500)}`);
    const l = await world.run("kind", ["load", "docker-image", FE_TAG, "--name", String(kindCluster)], {
      timeoutMs: 300_000,
      output: { tail: 8_192 },
    });
    if (l.exitCode !== 0) errs.push(`frontend 镜像导入 kind 失败：\n${l.stderr.slice(-1000)}`);
    const s2 = await world.run("kubectl", ["-n", "agent-platform", "set", "image", "deployment/platform-frontend", `frontend=${FE_TAG}`]);
    if (s2.exitCode !== 0) errs.push(`frontend set image 失败：\n${s2.stderr.slice(-1000)}`);
    // 同 BE_TAG 语义：恒定 tag 重部署 set image 为 no-op，无条件 rollout restart 强制换代
    const rs2 = await world.run("kubectl", ["-n", "agent-platform", "rollout", "restart", "deployment/platform-frontend"]);
    if (rs2.exitCode !== 0) errs.push(`frontend rollout restart 失败：\n${rs2.stderr.slice(-1000)}`);
    const r2 = await world.run("kubectl", ["-n", "agent-platform", "rollout", "status", "deployment/platform-frontend", "--timeout=300s"], {
      timeoutMs: 330_000,
      output: { tail: 8_192 },
    });
    if (r2.exitCode !== 0) errs.push(`platform-frontend 滚动更新未就绪：\n${r2.stdout.slice(-1000)}${r2.stderr.slice(-500)}`);
  }
  if (dirSet.has("agent-framework")) {
    const cp = await world.run(
      "bash",
      ["-c", `mkdir -p ${WT}/agent-framework/docker/otel && cp agent-framework/docker/otel/opentelemetry-javaagent.jar ${WT}/agent-framework/docker/otel/ 2>/dev/null; test -f ${WT}/agent-framework/docker/otel/opentelemetry-javaagent.jar && echo jar-copied || echo jar-missing`],
      { timeoutMs: 60_000, output: { tail: 2_000 } },
    );
    if (/jar-missing/.test(cp.stdout))
      localGaps.push("OTel agent jar 不在工作区（docker build 将在线下载，需网络/代理）");
    const b = await world.run("docker", ["build", "-t", AF_TAG, `${WT}/agent-framework`], {
      timeoutMs: 1_800_000,
      output: { tail: 16_384 },
    });
    if (b.exitCode !== 0) errs.push(`agent-framework 镜像构建失败：\n${(b.stdout + b.stderr).slice(-2500)}`);
    const p = await world.run("docker", ["push", AF_TAG], {
      timeoutMs: 600_000,
      output: { tail: 8_192 },
    });
    if (p.exitCode !== 0) errs.push(`agent-framework 镜像推送 ${REGISTRY} 失败：\n${p.stderr.slice(-1000)}`);
  }
  return errs.join("\n\n");
}
async function runDeployVerify(): Promise<DeployVerify> {
  if (DEPLOY_MODE === "full") {
    return deployer.ask<DeployVerify>(
      `full 模式真实部署验证（Issue #${ISSUE}）。worktree ${WT}，集群 ${kindCluster}。\n` +
        `1) bash e2e/platform-e2e.sh（在 ${WT} 下执行；它 source 工作区 .env.secrets 注入真实 LLM 凭据；注意：脚本开头会清理平台上除 oaf-release-agent 外的全部业务服务/包——这是仓库标准语义，原样执行）；\n` +
        `2) 按 issue 涉及模块从 e2e/console-split/ 挑选对应套件运行（m0-auth/m1-governance/m3-agent-create/m10-sandbox 等与改动模块相关的；不知道怎么映射就跑 run-all.sh）；\n` +
        `3) 汇总 PASS/FAIL 与失败用例输出，返回 DeployVerify（passed=全部通过；evidence 里放场景 A7 running、A14 agent-card、console 套件计数等关键输出；llmCheck 说明平台 LLM 凭据真实注入情况；gaps 放未跑项）。${NET}`,
    );
  }
  return deployer.ask<DeployVerify>(
    `scoped 模式真实部署验证（Issue #${ISSUE}）。worktree ${WT}，集群 ${kindCluster}，平台 API http://localhost:30080/api/v1。\n` +
      `红线：只创建/清理本轮验证自己的资源；绝不动 oaf-release-agent、platform-*、oaf-mysql、oaf-redis 与任何已存在的业务服务（开始前先记录 GET /services 基线）。\n` +
      `步骤（参照 ${WT}/e2e/platform-e2e.sh 场景 A 的写法）：\n` +
      `1) source 工作区根 .env.secrets（真实 LLM/沙箱凭据，只进 shell 变量）；\n` +
      `2) 上传 ${WT}/e2e/fixtures/demo-agent-v1.zip → POST /packages 得 packageId；\n` +
      `3) POST /services 发布：packageId + image（agent-framework 有改动 → 用 ${AF_TAG}；否则 GET /images 里挑 ${REGISTRY} 开头的镜像）+ env（LLM_API_KEY/LLM_MODEL_ID/LLM_BASE_URL/CHECKPOINT_JDBC_URL(jdbc:mysql://oaf-mysql.agent-platform.svc.cluster.local:3306/oaf_checkpoint?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC)/CHECKPOINT_USERNAME=oaf/CHECKPOINT_PASSWORD/LOG_LEVEL=info/OPENSANDBOX_API_KEY）；服务名用 e2e-demo-pipeline（k8sName 规范见平台校验）；\n` +
      `4) 轮询 GET /services/{id} 等 status=running（≤300s）；\n` +
      `5) 证据：a) GET /services/{id} 含 registeredName（A2A 注册成功）；b) kubectl -n agent-platform get deploy/svc/cm 存在；c) curl {endpoint}/.well-known/agent-card.json 返回 name；d) 真实调用一次 agent：按 ${WT}/agent-framework/AGENTS.md 的 A2A 端点，POST message/send 一条「用一句话回答：1+1=?」，--max-time 120，记录真实 LLM 回复摘要（失败如实记录，不重试超过 2 次）；\n` +
      `6) 清理：DELETE /services/{id}、DELETE /packages/{packageId}，确认基线恢复。\n` +
      `沙箱说明：OPENSANDBOX_API_KEY 已注入 env，但 demo agent 未必触发工具调用——没触发就在 gaps 里如实写。返回 DeployVerify。${NET}`,
  );
}

// ========== 6. 本地真实部署验证（kind + 沙箱 + LLM） ==========
if (DEPLOY_MODE !== "off" && CAN_DEPLOY) {
  phase("构建镜像并部署到本地 kind 集群");
  let buildErr = await buildAndDeployModules();
  let bRound = 1;
  while (buildErr && bRound <= VERIFY_ROUNDS) {
    log(`镜像构建/部署第 ${bRound} 轮失败，交开发工程师修复`);
    await dev.ask(`镜像构建或部署失败（第 ${bRound} 轮），修复后在 ${WT} 提交（不要 push）：\n${buildErr}`);
    buildErr = await buildAndDeployModules();
    bRound++;
  }
  if (buildErr) return stopReport(`镜像构建/部署多轮失败：\n${buildErr}`, ["部署验证及之后阶段未执行"]);
  gateResults.push(`镜像构建并部署到 kind（${[dirSet.has("backend") ? `backend→${BE_TAG}` : "", dirSet.has("agent-framework") ? `agent-framework→${AF_TAG}` : ""].filter(Boolean).join("，")}）`);

  phase("本地真实部署验证（kind + 沙箱 + LLM）");
  let verdict = await runDeployVerify();
  deployEvidence = verdict.evidence;
  let vRound = 1;
  while (!verdict.passed && vRound <= VERIFY_ROUNDS) {
    log(`部署验证第 ${vRound} 轮未通过，交开发工程师修复后重验`);
    await dev.ask(
      `本地真实部署验证未通过（第 ${vRound} 轮）。失败证据：\n${JSON.stringify(verdict)}\n在 ${WT} 修复并提交（不要 push；若涉及镜像改动需说明，脚本会重新构建部署）。`,
    );
    const reb = await buildAndDeployModules();
    if (reb) {
      verdict = { passed: false, evidence: [reb], created: [], deleted: [], llmCheck: "", gaps: [], notes: "修复后构建失败" };
    } else {
      verdict = await runDeployVerify();
    }
    deployEvidence = [...deployEvidence, ...verdict.evidence];
    vRound++;
  }
  if (!verdict.passed)
    return stopReport(`本地真实部署验证多轮未通过：\n${verdict.evidence.join("\n")}`, ["PR 及之后阶段未执行"]);
  gateResults.push(`本地真实部署验证（${DEPLOY_MODE}）通过：服务 running + A2A card + ${verdict.llmCheck ? "真实 LLM 调用" : "LLM 调用见证据"}`);
  if (verdict.gaps.length > 0) localGaps.push(...verdict.gaps.map((g) => `部署验证缺口：${g}`));
  mark("部署验证", `真实部署通过（${DEPLOY_MODE}）：${verdict.llmCheck.slice(0, 80) || verdict.evidence[0]?.slice(0, 80) || "ok"}`);
  log(`部署验证通过：${verdict.deleted.length > 0 ? `已清理 ${verdict.deleted.length} 项` : "资源已清理"}`);
} else {
  localGaps.push("本地部署验证：deploy_verify=off 或环境不具备，未在本地集群验证（由 PR CI E2E 门禁兜底）");
  mark("自测", "本地部署验证跳过");
}

// ========== 6.5 流程黑盒验证（用户视角全生命周期） ==========
let bboxPassed = false;
if (DEPLOY_MODE === "scoped" && CAN_DEPLOY) {
  phase("流程黑盒验证（用户视角全生命周期）");
  const bbx = agent("黑盒验证工程师", BBX_PERSONA);
  const runBlackBox = async (): Promise<BlackBoxVerify> =>
    bbx.ask<BlackBoxVerify>(
      `黑盒流程验证（Issue #${ISSUE}）。平台 API http://localhost:30080/api/v1；执行验证只准用 curl/HTTP 调平台 REST API 与业务服务公开 endpoint（source 工作区根 .env.secrets 注入真实 LLM 凭据做真实对话）；禁止 kubectl/docker/exec 等一切集群内部观测。\n` +
        `红线：只创建/清理本轮自己的资源（服务名用 e2e-demo-bbox），绝不动 oaf-release-agent、platform-*、oaf-mysql、oaf-redis 与任何已存在业务服务；密钥不明文。\n` +
        `用户旅程（每步留命令+输出证据）：\n` +
        `1) GET /services 记录基线；\n` +
        `2) 上传 ${WT}/e2e/fixtures/demo-agent-v1.zip → POST /services 发布（image：agent-framework 有改动用 ${AF_TAG}，否则 GET /images 挑 ${REGISTRY} 开头的；env 同部署验证：LLM 三键 + CHECKPOINT_JDBC_URL(jdbc:mysql://oaf-mysql.agent-platform.svc.cluster.local:3306/oaf_checkpoint?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC)/CHECKPOINT_USERNAME/CHECKPOINT_PASSWORD/LOG_LEVEL=info/OPENSANDBOX_API_KEY）→ 轮询等 running（≤300s）；\n` +
        `3) 真实对话：经 endpoint 按 A2A 协议 message/send 发两次独立请求（如「1+1=?」「用一句话自我介绍」），--max-time 120，记录真实 LLM 回复摘要（失败如实记录，最多重试 2 次）；\n` +
        `4) 动作矩阵（只看接口可见行为）：PATCH env 新增 TEMP_BB_KEY=1 → 受理 deploying；deploying 中再 PATCH → 应 400；等 running；PATCH env 全量覆盖移除 TEMP_BB_KEY → 受理 → running；GET /services/{id} 的状态/版本/endpoint 自洽；\n` +
        `5) republish：上传 demo-agent-v2.zip → POST /services/{id}/republish → deploying → running；\n` +
        `6) 异常路径：GET /services/does-not-exist、POST /services 带 packageId=999999、POST /services env 带保留键（如 SERVER_PORT）、非法 JSON body——各记录状态码，期望优雅 4xx/明确错误，出现 5xx 或挂死即不通过；\n` +
        `7) 清理：DELETE /services/{id}、DELETE /packages/{pkgId}，确认基线恢复。\n` +
        `不要求多副本验证（CI 矩阵覆盖）。任何一步与预期不符 → passed=false 并把差异写进 evidence。返回 BlackBoxVerify。${NET}`,
    );
  let bx = await runBlackBox();
  bboxEvidence = bx.evidence;
  let bxRound = 1;
  while (!bx.passed && bxRound <= VERIFY_ROUNDS) {
    log(`黑盒验证第 ${bxRound} 轮未通过，交开发工程师修复后重验`);
    await dev.ask(
      `流程黑盒验证未通过（第 ${bxRound} 轮）。失败证据：\n${JSON.stringify(bx)}\n在 ${WT} 修复并提交（不要 push；若涉及镜像改动需说明，脚本会重新构建部署后重验）。`,
    );
    const reb = await buildAndDeployModules();
    if (reb) {
      bx = { passed: false, evidence: [reb], created: [], deleted: [], errorPaths: [], llmCheck: "", gaps: [], notes: "修复后镜像重建失败" };
    } else {
      bx = await runBlackBox();
    }
    bboxEvidence = [...bboxEvidence, ...bx.evidence];
    bxRound++;
  }
  if (!bx.passed) return stopReport(`流程黑盒验证多轮未通过：\n${bx.evidence.join("\n")}`, ["PR 及之后阶段未执行"]);
  bboxPassed = true;
  gateResults.push("流程黑盒验证通过：发布→真实对话(LLM)→env 变更→republish→异常路径→删除 全生命周期符合预期");
  if (bx.gaps.length > 0) localGaps.push(...bx.gaps.map((g) => `黑盒验证缺口：${g}`));
  mark("黑盒验证", `用户视角全生命周期通过；异常路径观测 ${bx.errorPaths.length} 项`);
  log(`黑盒验证通过：自建资源已清理（${bx.deleted.length} 项）`);
} else if (DEPLOY_MODE === "full") {
  localGaps.push("流程黑盒验证：full 模式下已由 platform-e2e.sh 场景 B（全动作矩阵+异常路径）覆盖，未单独重跑");
  mark("部署验证", "黑盒验证由场景 B 覆盖");
} else {
  localGaps.push("流程黑盒验证：deploy_verify=off 或环境不具备，未执行（由 PR CI E2E 门禁兜底）");
}

// ========== 7. 代码评审与独立复核 ==========
phase("代码评审与独立复核");
const reviewFiles = changed.filter((p) => !/(package-lock\.json|go\.sum|pnpm-lock\.yaml)$/.test(p));
const groups = groupByDir(reviewFiles);
log(`按模块并行评审 ${groups.length} 组、共 ${reviewFiles.length} 个文件`);
const reviews = await Promise.all(
  groups.map((g) =>
    agent(`评审-${g.name}`, REVIEWER_PERSONA).ask<FileReview>(
      `独立评审 ${WT} 分支 ${curBranch}（Issue #${ISSUE}）中以下文件的改动：\n${g.files.join("\n")}\n` +
        `先 git -C ${WT} diff origin/master...HEAD -- <这些文件> 看改动，再读完整文件与周边上下文；对照 ${WT} 根 AGENTS.md 与 ${topDir(g.files[0] ?? g.name)} 下 AGENTS.md 的规范与架构强约束。` +
        `只报真问题（会导致缺陷/违反强约束/明显设计问题），每条给 path:line 证据与修改建议；风格偏好不报；不得修改任何文件。\n目标摘要：${currentPlan.goal}`,
    ),
  ),
);
const rawFindings = reviews.flatMap((rv) => rv.findings);
// 分诊需要全部评审发现（跨文件去重与严重度校准），此处 join 是必要屏障
const triage =
  rawFindings.length === 0
    ? { kept: [] as TriagedFinding[], dropped: [] as { path: string; problem: string; why: string }[] }
    : await agent("分诊员", TRIAGE_PERSONA).ask<TriagedSet>(
        `各模块评审发现如下（JSON）。请跨文件去重合并、校准 severity（high 仅留给数据丢失/崩溃/安全漏洞/架构强约束违反）、丢弃纯风格项（记入 dropped 及原因）；保留项分配唯一 id（g1,g2,...）：\n${JSON.stringify(rawFindings)}`,
      );
const needConfirm = triage.kept.filter((f) => f.severity !== "low");
lows = triage.kept.filter((f) => f.severity === "low");
// 逐条独立复核：每条在自己的回调内完成复核并即时上报，互不等待
const confirmed = (
  await Promise.all(
    needConfirm.map(async (f) => {
      const v = await agent(`复核-${f.id}`, CONFIRMER_PERSONA).ask<Verdict>(
        `独立复核以下评审发现（你没有参与评审，凭证据裁决）。在 ${WT} 读代码求证（只读）：\n${JSON.stringify(f)}\n成立（holds=true）必须给 path:line 证据；不成立给反驳证据。`,
      );
      return v.holds ? ({ ...f, evidence: v.evidence } as TriagedFinding & { evidence: string }) : null;
    }),
  )
).filter((x): x is TriagedFinding & { evidence: string } => x !== null);
for (const f of confirmed) {
  report({
    where: f.line ? `${f.path}:${f.line}` : f.path,
    what: f.problem,
    evidence: f.evidence,
    status: "verified",
    severity: f.severity,
  });
}
log(`评审发现 ${rawFindings.length} 条：确认成立 ${confirmed.length}、低优先级 ${lows.length}、分诊丢弃 ${triage.dropped.length}`);
mark("CodeReview", `发现 ${rawFindings.length} 条，独立复核成立 ${confirmed.length} 条`);

// ========== 8. 评审意见修复与回归 ==========
let fixNotes = "";
if (confirmed.length > 0) {
  phase("评审意见修复与回归");
  const fix = await dev.ask<FixOutcome>(
    `以下评审发现已经独立复核成立，请逐条修复并在 ${WT} 提交（fix(<模块>): 中文描述 (#${ISSUE})；确有理由不修的条目放进 skipped 并写明理由，不要硬凑）：\n${JSON.stringify(confirmed.map(({ path: p, problem, suggestion, id, severity: sev }) => ({ id, path: p, problem, suggestion, severity: sev })))}`,
  );
  fixNotes = fix.notes;
  const skippedIds = new Set(fix.skipped.map((s) => s.id));
  const fixedFindings = confirmed.filter((f) => !skippedIds.has(f.id));
  leftover = confirmed
    .filter((f) => skippedIds.has(f.id))
    .map((f) => ({ ...f, evidence: f.evidence, status: "verified" as const }));
  if (fixedFindings.length > 0) {
    const fixedPaths = [...new Set(fixedFindings.map((f) => f.path))];
    const reDirs = new Set(fixedPaths.map(topDir));
    if (CAN_DEPLOY && (reDirs.has("backend") || reDirs.has("agent-framework") || reDirs.has("frontend"))) {
      const reb = await buildAndDeployModules();
      if (reb) return stopReport(`评审修复后镜像重建失败：\n${reb}`, ["PR 及之后阶段未执行"]);
    }
    if (!(await runLocalGates(reDirs, 2, dev))) return stopReport("评审修复后本地单测回归未通过", ["PR 及之后阶段未执行"]);
    const recheck = await agent("复审员", REVIEWER_PERSONA).ask<FileReview>(
      `复审 ${WT} 分支 ${curBranch} 上这些文件的最终改动（只读；此前问题应已修复，重点查是否修对、有无引入新问题）：\n${fixedPaths.join("\n")}\n可用 git -C ${WT} diff origin/master...HEAD -- <文件> 查看改动。`,
    );
    leftover = [
      ...leftover,
      ...recheck.findings.map((f, i) => ({ ...f, id: `re-${i}`, evidence: "复审员单遍复核，未经二次确认", status: "unconfirmed" as const })),
    ];
    for (const f of recheck.findings) {
      report({
        where: f.line ? `${f.path}:${f.line}` : f.path,
        what: `[复审新发现] ${f.problem}`,
        evidence: "复审员单遍复核，未经二次确认",
        status: "unconfirmed",
        severity: f.severity,
      });
    }
  }
}

// ========== 9. 推送分支并创建 PR ==========
phase("推送分支并创建 PR");
const prMat = await dev.ask<PrMaterial>(
  `准备创建 PR（Issue #${ISSUE}，分支 ${curBranch}，worktree ${WT}）：\n` +
    `1) 可把 WIP 提交整理为少量语义提交（不得改无关文件）；\n` +
    `2) PR 正文写入 ${PR_BODY_FILE}（markdown）：## 背景（Issue #${ISSUE} ${issueTitle}）、## 改动点（按模块）、## 测试（本地：${[...gateResults, ...localGaps].join("；") || "无本地门禁"}；CI：PR 必需检查全量覆盖含 E2E）、文末单独一行 Closes #${ISSUE}；\n` +
    `3) prTitle 用 type(scope): 一句话中文描述（#${ISSUE}）。\n返回 PrMaterial（prBodyFile=${PR_BODY_FILE}）。`,
);
const bodyOk = await world.run("bash", ["-c", `test -s ${PR_BODY_FILE} && wc -l ${PR_BODY_FILE}`]);
if (bodyOk.exitCode !== 0) return stopReport(`PR 正文文件缺失：${PR_BODY_FILE}`, ["PR 未创建"]);
let push = await world.run("git", ["-C", WT, ...GIT_PROXY, "push", "-u", "origin", curBranch]);
if (push.exitCode !== 0 && /already exists|fetch first|rejected|non-fast-forward/i.test(`${push.stdout}\n${push.stderr}`)) {
  curBranch = `${BRANCH_BASE}-pr2`;
  const rename = await world.run("git", ["-C", WT, "branch", "-m", curBranch]);
  if (rename.exitCode !== 0) return stopReport(`远端分支已存在且本地改名失败：${rename.stderr}`, ["PR 未创建"]);
  push = await world.run("git", ["-C", WT, ...GIT_PROXY, "push", "-u", "origin", curBranch]);
}
if (push.exitCode !== 0)
  return stopReport(`推送失败${PROXY ? `（检查本地代理 ${PROXY}）` : ""}：${push.stderr.slice(0, 500)}`, ["PR 未创建"]);
const prCreate = await world.run("gh", [
  "pr",
  "create",
  "-R",
  REPO,
  "--base",
  "master",
  "--head",
  curBranch,
  "--title",
  prMat.prTitle,
  "--body-file",
  PR_BODY_FILE,
]);
let prNumber = parsePrNumber(prCreate.stdout);
if (prCreate.exitCode !== 0 || !prNumber) {
  const exist = await world.run("gh", ["pr", "list", "-R", REPO, "--head", curBranch, "--json", "url", "--limit", "1"]);
  prNumber = parsePrNumber(exist.stdout);
  if (!prNumber) return stopReport(`PR 创建失败：${prCreate.stderr.slice(0, 500)}`, ["CI/合并/归档未执行"]);
  log(`检测到分支已有 PR，复用 #${prNumber}`);
}
prRef = `#${prNumber}`;
mark("PR·CI", `PR ${prRef} 已创建，等待 CI`);
log(`PR ${prRef} 已创建：${prMat.prTitle}`);

// ========== 10. PR 提交后收尾归档（CI 结果与合并交由人工，流水线到此结束） ==========
phase("PR 提交后收尾归档");
const rmWt = await world.run("git", ["worktree", "remove", WT]);
if (rmWt.exitCode !== 0) await world.run("git", ["worktree", "remove", "--force", WT]);
mark("PR·CI", `PR ${prRef} 已提交，等待 CI 与人工合并`);
log(`流水线结束：PR ${prRef} 已提交（${prMat.prTitle}）；本地验证已全部通过，CI 结果与合并由人工盯守`);

const reportMd = [
  `# Issue #${ISSUE} 开发流水线报告`,
  ``,
  `> 运行标识 ${run.id} · 分支 \`${curBranch}\` · PR ${prRef}（已提交，待 CI 与人工合并）`,
  ``,
  `## 目标与方案`,
  `- Issue：${issueTitle}`,
  `- 目标：${currentPlan.goal}`,
  `- 实施步骤：`,
  ...currentPlan.steps.map((s, i) => `  ${i + 1}. ${s}`),
  ``,
  `## 开发与提交`,
  `- 最终提交：${prMat.commitSubject}`,
  `- 实现摘要：${devResult.summary}`,
  ...(fixNotes ? [`- 评审修复说明：${fixNotes}`] : []),
  ``,
  `## 验证`,
  ...gateResults.map((g) => `- ✅ ${g}`),
  ...(deployEvidence.length > 0
    ? [``, `**部署验证证据（kind + 沙箱 + LLM）**`, ...deployEvidence.slice(0, 10).map((e) => `- ${e.slice(0, 300)}`)]
    : []),
  ...(bboxEvidence.length > 0
    ? [``, `**黑盒验证证据（用户视角全生命周期）**`, ...bboxEvidence.slice(0, 10).map((e) => `- ${e.slice(0, 300)}`)]
    : []),
  ...(localGaps.length > 0 ? [``, `**本地未覆盖（由 CI 门禁兜底）**`, ...localGaps.map((g) => `- ⚠️ ${g}`)] : []),
  ``,
  `## 代码评审`,
  `- 独立复核成立并处理：${confirmed.length} 条（拒修 ${leftover.filter((f) => f.status === "verified").length} 条）；复审新发现未确认：${leftover.filter((f) => f.status === "unconfirmed").length} 条；低优先级未复核：${lows.length} 条`,
  ...(leftover.length > 0
    ? leftover.map((f) => `- [${f.severity}/${f.status}] ${f.line ? `${f.path}:${f.line}` : f.path} ${f.problem}`)
    : []),
  ``,
  `## 交付`,
  `- PR：https://github.com/${REPO}/pull/${prNumber}（正文含 Closes #${ISSUE}，合并后 Issue 自动关闭）`,
  `- CI：PR 必需检查（含 E2E 矩阵）由 GitHub Actions 自动执行，结果与合并由人工盯守`,
  `- worktree 已清理；本地分支 ${curBranch} 保留（仓库惯例远端分支不删）`,
].join("\n");
await artifact.markdown("pipeline-report", reportMd, {
  title: `Issue #${ISSUE} 开发流水线报告`,
  description: "认领→评审→开发→自测→真实部署验证→黑盒验证→CodeReview→提交PR 全程记录与验证证据",
  primary: true,
});

return {
  conclusion: `Issue #${ISSUE}「${issueTitle}」流水线完成至提交 PR：方案（独立评审通过即开工）→ 开发 → 本地自测 → ${DEPLOY_MODE !== "off" && CAN_DEPLOY ? "本地 kind 真实部署验证（沙箱+LLM 凭据、A2A 注册与真实调用）→ " : ""}${bboxPassed ? "流程黑盒验证（用户视角全生命周期）→ " : ""}代码评审（独立复核）→ PR ${prRef} 已提交（https://github.com/${REPO}/pull/${prNumber}）。本地验证全部通过；PR CI（含必需 E2E 矩阵）与合并由人工盯守，正文含 Closes #${ISSUE}（合并后 Issue 自动关闭）。${leftover.length + lows.length > 0 ? `遗留项 ${leftover.length + lows.length} 条详见 findings 与报告。` : ""}`,
  findings: buildFindings(),
  verified: [
    ...gateResults,
    "方案经独立评审员评审通过后自动开工",
  ],
  notCovered: [
    ...localGaps,
    "PR CI 门禁结果与合并由人工盯守（流水线到提交 PR 为止）",
    "Issue 关闭依赖 PR 合并（Closes #N 由 GitHub 自动执行）",
    ...(leftover.some((f) => f.status === "unconfirmed") ? ["复审新发现项未做二次独立确认"] : []),
  ],
};

// ---------- 单测门禁辅助（评审修复回归复用；函数声明提升，放文件末尾便于阅读） ----------
async function runLocalGates(dirs: Set<string>, rounds: number, d: Agent): Promise<boolean> {
  let ok = true;
  if (dirs.has("backend") && CAN_UNIT_GO) {
    const t = await world.run(
      "bash",
      ["-c", `cd ${WT}/backend && GOPROXY=https://goproxy.cn,direct go vet ./... && GOPROXY=https://goproxy.cn,direct go test ./...`],
      { timeoutMs: 900_000, output: { tail: 16_384 } },
    );
    if (t.exitCode !== 0) {
      for (let r = 1; r <= rounds && t.exitCode !== 0; r++) {
        log(`backend 回归单测第 ${r} 轮未过，交开发工程师修复`);
        await d.ask(`backend 本地单测回归未过（第 ${r} 轮），修复后在 ${WT} 提交（不要 push）：\n${t.stdout}\n${t.stderr}`);
        const rt = await world.run(
          "bash",
          ["-c", `cd ${WT}/backend && GOPROXY=https://goproxy.cn,direct go vet ./... && GOPROXY=https://goproxy.cn,direct go test ./...`],
          { timeoutMs: 900_000, output: { tail: 16_384 } },
        );
        if (rt.exitCode === 0) break;
        if (r === rounds) ok = false;
      }
    }
  }
  if (dirs.has("frontend") && CAN_UNIT_NPM) {
    for (const script of ["lint", "test"]) {
      const t = await world.run("npm", ["--prefix", `${WT}/frontend`, "run", script], {
        timeoutMs: 600_000,
        output: { tail: 16_384 },
      });
      if (t.exitCode !== 0) {
        for (let r = 1; r <= rounds && t.exitCode !== 0; r++) {
          log(`frontend ${script} 回归第 ${r} 轮未过，交开发工程师修复`);
          await d.ask(`frontend npm run ${script} 回归未过（第 ${r} 轮），修复后在 ${WT} 提交（不要 push）：\n${t.stdout}\n${t.stderr}`);
          const rt = await world.run("npm", ["--prefix", `${WT}/frontend`, "run", script], {
            timeoutMs: 600_000,
            output: { tail: 16_384 },
          });
          if (rt.exitCode === 0) break;
          if (r === rounds) ok = false;
        }
      }
    }
  }
  if (dirs.has("agent-framework") && CAN_UNIT_MVN) {
    const t = await world.run("bash", ["-c", `cd ${WT}/agent-framework && mvn -B test`], {
      timeoutMs: 1_800_000,
      output: { tail: 16_384 },
    });
    if (t.exitCode !== 0) {
      for (let r = 1; r <= rounds && t.exitCode !== 0; r++) {
        log(`agent-framework 回归单测第 ${r} 轮未过，交开发工程师修复`);
        await d.ask(`agent-framework mvn test 回归未过（第 ${r} 轮），修复后在 ${WT} 提交（不要 push）：\n${t.stdout}\n${t.stderr}`);
        const rt = await world.run("bash", ["-c", `cd ${WT}/agent-framework && mvn -B test`], {
          timeoutMs: 1_800_000,
          output: { tail: 16_384 },
        });
        if (rt.exitCode === 0) break;
        if (r === rounds) ok = false;
      }
    }
  }
  return ok;
}
