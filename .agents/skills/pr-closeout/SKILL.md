---
name: pr-closeout
description: 收口本仓库全部未合并 PR 与 open issues：逐个 PR 做 code review（CI 失败先归因：PR 引入 / flake / master 底红），缺陷在 worktree 修复推送、CI 全绿后按 strict 门禁轮转合并，随后 triage 并逐个处理 open issues（复核真实性→修复→Closes #N 提 PR）。当用户说「CR 一下 PR」「把 PR 合了」「处理 issue」「收口 PR/issue」「review 未合并的 PR」或要求批量消化 PR 与 issue 时使用。
---

# PR 收口流水线：CR → 修复 → 合并 → 处理 issue

四段式推进，每段产物对用户可见（合并了什么、修了什么、issue 状态表）。全程在 worktree 工作，不污染主工作区 master。

## 0. 盘点

```bash
gh pr list --state open --json number,title,headRefName,updatedAt,isDraft
gh issue list --state open --json number,title,labels,updatedAt
gh pr checks <N>          # CI 现状
gh pr view <N> --json mergeStateStatus,reviewDecision,additions,changedFiles,commits
```

多个 PR 时按「最小改动、先易后难」排序合并——strict 分支保护下每次合并 master 都使其余 PR 变 BEHIND，只能逐轮「update-branch → 等 CI → 合并」。

## 1. CR：每个未合并 PR

### CI 绿的 PR
不等于没问题。仍要过 diff：对照 AGENTS.md 架构强约束（控制面/业务面分层）、迁移幂等纪律、单一真相源；随机抽查关键文件而非全读。

### CI 红的 PR：先归因，再谈 CR
失败三类归因，动手顺序不同：

1. **master 底红**：`gh run list --branch master --workflow <wf> --limit 5`，master 近期同 job 也红 → 环境性，等修复或与用户确认跳过。
2. **flake**：失败点与 PR 改动无交集（如外部服务超时）→ `gh run rerun --failed` 重跑一次。
3. **PR 引入**（本文档重点）：失败点落在 PR 改动路径上 → 按下述手法定位。

归因手法（实战提炼）：

- `gh run view <run> --json jobs --jq '.jobs[] | select(.conclusion=="failure") | {name, steps: [.steps[] | select(.conclusion=="failure") | .name]}'` 先定位失败 step。注意带 `|| true` 兜底的 step 不会 fail job——ERROR 输出出现在该 step 不代表失败在该 step，以 `--json jobs` 的结论为准。
- **同一组失败签名（同 digest/同断言）跨 rerun attempt 复现 = 确定性失败**，不要重跑第二次，立刻换归因方向（master 同时段同 job 绿 → PR 特有 → 从 PR 改动面找与失败 step 的交点）。
- `--log-failed` 常被 cleanup 噪音淹没，改用 `gh run view <run> --log --job <id>` + grep 失败断言/异常关键词。
- 测试报错若被 `slice(-800)` 截断看不到根因（只留堆栈尾），从**失败模式反推**：多个实例报同一错（如 Flyway「run repair」）= 首错污染同库的连锁失败，先修首错连锁自消。
- 失败测试与 PR 改动的交点：PR 新增迁移 → 查存量升级路径测试（FW3）；PR 新增端口/组件 → 查多实例/多进程并行场景（e2e 多实例、本地冒烟配方）。
- **读测试代码里的契约注释**：本仓库在测试里钉死硬契约（如 `api-core.spec.ts` FW3「升级路径契约钉：新增迁移必须幂等且同步扩展期望列表」）。PR 违反注释钉契约即阻断缺陷。
- 对照项目先例定修法：幂等迁移抄 `V14__session_message_trace_id.sql`（列用 `information_schema.COLUMNS` 守卫、索引用 `STATISTICS` 守卫）；不要发明新写法。

### 修复
- worktree 修复：`git worktree add .tmp/<名称> -b <PR分支名> origin/<PR分支名>`（本地建同名分支跟踪远端）。
- commit 消息 conventional + 尾行 `Chat-Session: $CLAUDE_CODE_SESSION_ID`；push 直接推 PR head 分支（token 与作者同账号）。
- **修复必须实证**：SQL 幂等类用一次性 `docker run mysql:8.0` 容器三场景验证（fresh 应用 / 已应用库重放 no-op / 部分形态补齐），跑完删容器；脚本类 `bash -n`；**nginx 配置改动必须 `docker run --rm -v <conf>:/etc/nginx/nginx.conf:ro nginx:latest nginx -t`**（`;` 是 nginx 语句终止符，location 正则含分号必须整体双引号包裹，未引号实证报 `directive "location" has no opening "{"`）；时序竞态类本地起环境复现。
- 注意红线：已合并的 V 迁移文件禁止修改（checksum 拒启）；未合并 PR 内的 V 文件可以直接改。

## 2. 合并：strict 门禁轮转

细节与坑见 [references/strict-merge-dance.md](references/strict-merge-dance.md)。要点：

- 等 `mergeStateStatus == CLEAN` 才合；`skipping` 状态 job = 目录过滤设计内视为通过；`UNSTABLE` 且仅剩非必需 job pending 是暂态，等它收尾。
- 必需检查九项：三个单测 + 四个 agent-framework E2E（核心/多副本/沙箱/协议多副本 P 组）+ `E2E 平台（kind + 控制台全功能）` + Chart 校验。LF 组（沙箱生命周期）观察期内非必需。
- **每次 merge 前复核**：`git fetch origin master && git merge-base --is-ancestor origin/master <head> || gh pr update-branch <N>`——并行会话会中途往 master 落 PR，别信几分钟前的 CLEAN。
- 合并用 `gh pr merge <N> --merge`（仓库口径是 merge commit）；合并后 `git worktree remove` 与删分支（worktree 里先 `cd` 出去再删，见「worktree remove 后 shell cwd 悬空」坑）。

## 3. 处理 open issues

1. **Triage**：按 P0-P3（标题前缀）× 模块（backend/agent-framework/frontend/skills-syncd）分组排序；同模块相邻优先级的可合并一个分支修。
2. **复核真实性**：review 产物类 issue（标题带代码符号级证据的）先复现再修——复现不出的标记 `question` 评论说明，不盲目改代码。
3. **修复**：单个 issue 独立分支独立 PR（复杂多模块 issue 除外），正文带 `Closes #N` 自动关链；有端到端开发流水线需求时优先复用 `.zcode/workflows/issue-dev-pipeline.dwf.ts`（认领→评审→开发→部署验证→PR 全自动）。
4. **PR 引入的新缺陷**（CR 发现的）优先修在对应 PR 内，不开新 issue。

## 4. 收尾报告

对用户输出一张状态表：PR（CR 结论 / 修了什么 / 合并状态）、issue（triage 结果 / 已修列表含 PR 链接 / 待办含理由）。CI 未收尾的明确说清「合并条件与预计等待」。
