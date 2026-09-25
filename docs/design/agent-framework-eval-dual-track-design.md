# agent-framework 双轨评测系统设计（门禁轨 + 趋势轨）

> 状态：设计定稿（待实施）· 日期：2026-09-25
> 范围：agent-framework 的黑盒评测体系——确定性门禁轨（扩展现有 e2e）、质量趋势轨（Python + AgentScope evaluate + OpenJudge）、diff 驱动用例生成（四道闸门 + PR 人工审核）、根因分析与修复 patch 建议、人工快照基线。
> 不做（评审裁剪，见 §2）：知识库/RAG 自迭代、代码自动直改、基线自动更新、LLM judge 分数阻断合并、评测自有 MySQL 库（持久化走文件 + GitHub Issues，§10）。
> 前置文档：v1 方案《基于 OpenJudge 的 Agent 评测-迭代数据飞轮系统设计》经可行性评审后裁剪重构，评审要点见 §2.3。

---

## 1. 背景与现状基线

### 1.1 现有测试体系盘点（2026-09-25 按当前代码实测核对）

| 体系 | 规模/形态 | 覆盖维度 | 缺口 |
|---|---|---|---|
| 单测 `mvn test` | 96 个测试类 / 973 个 `@Test`（`src/test` 实测；AGENTS.md 自述 83/883 已过时） | 代码级逻辑正确性 | 不评测 Agent 端到端行为 |
| E2E（Playwright） | 6 个 spec：`api-core / api-multi / api-multi-kill / api-sandbox / ui / ui-multi`（`agent-framework/e2e/tests/`），mock LLM 录制回放（`e2e/mock/llm-server.mjs`，回放 `fixtures/llm/` 真实录制 chunk），三个 CI job（core/multi/sandbox），零密钥零外联 | 功能正确性、多副本续传、沙箱、Debug 页 UI | 断言面向「功能没坏」，不评测「回复质量 / 工具选择合理性 / 轨迹质量」 |
| bench 压测 | `bench/load/runner.js` + `run-bench.sh`，独立库 `agent_manager_bench`（`bench/sql/init-bench-db.sql`） | 并发稳定性（成功率/P95） | 自述「无断言体系」，不做正确性判断 |
| （无） | — | — | **Agent 能力质量评测**（语义、工具选择、轨迹合理性、框架特性合规）、系统化用例沉淀、失败根因定位 |

### 1.2 版本与依赖事实

- agent-framework 项目版本 `2.1.0`（`pom.xml:16`）；AgentScope Java SDK `2.0.3`（`pom.xml:25`，`io.agentscope:agentscope-harness`）。
- 评测侧引入 Python 栈：AgentScope Python `agentscope.evaluate`（GeneralEvaluator / BenchmarkBase / SolutionOutput / MetricBase）+ OpenJudge `py-openjudge 0.2.2`（导入命名空间 `openjudge`；0.2.0 起与旧包名 rm-gallery 不兼容）。两者均为快速演进的年轻依赖，**版本必须钉死并显式升级**（§5.1）。

### 1.3 设计目标

1. **零侵入**：全程通过公开 HTTP/SSE 与诊断接口评测，不修改 Java 业务代码。
2. **双轨分工**：确定性指标才可阻断合并；LLM judge 分数只做趋势，永不阻断。
3. **用例可控**：所有用例（含 diff 驱动生成）经人工审核 PR 后才进入回归集。
4. **复用优先**：门禁轨完全复用现有 e2e 编排（env-up.sh / mock LLM / GH services），不平行造轮子。
5. **可追溯**：用例、轨迹、报告、基线全部可版本化追溯（git 目录 + 运行产物 + GitHub Issues）。

## 2. 评审决议与范围裁剪

### 2.1 v1 → v2 关键差异

| 决策点 | v1 方案 | v2 本设计 | 理由 |
|---|---|---|---|
| 架构形态 | 单闭环五层飞轮 | 双轨：门禁轨（确定性，CI 阻断）+ 趋势轨（LLM judge，advisory） | judge 打分方差易超 5% 阈值，混轨会让 CI 随机阻断正常 PR |
| SSE 帧名 | 脑补词条（`thinking/tool_call/hitl_pending` 等，全部不存在） | 按真实词表重写（§4.1，v1 附录代码按假帧名解析会静默采不到任何工具轨迹） | 事实核对结论 |
| 用例生成 | diff 驱动零样本，生成即入库 | 保留 diff 驱动，增加静态校验→动态冒烟→语义去重→**PR 人工审核**四道闸门 | 生成用例最常见错误是 expected 本身错，必须人审 |
| 知识库 | 四库（用例/失败模式/修复方案/基线）+ 自迭代 | **删除**（用户决议）；用例表 source/status 字段 + 失败分类字段承担 | 零数据时预建知识库是空壳，失败模式随真实失败自然沉淀 |
| 自动修复 | 修复 Agent 直改代码 + 自动回归 | 降级为**修复 patch 建议**贴 PR，人审合入 | 真实 Java 库上 LLM 自动修复成功率有限；master 强制 PR 门禁本就不允许直改 |
| 基线 | 指标提升自动更新 | git 快照 + PR 审核变更，judge 模型版本固化进基线 | 自动改基线等于自己移动球门；换 judge 模型分数必然跳变 |
| 轨迹存储 | MySQL JSON 列全量 | 文件归档 + jsonl 摘要 | 单条全量事件流可达数百 KB，大 JSON 列拖垮查询 |
| 数据持久化 | MySQL 三表 + 用例库独立存储 | **不建评测自有库**：run 产物 + `history.jsonl` + GitHub Issues（§10） | 可用的 MySQL 存不住趋势历史（CI services 库每 run 销毁、集群库随 Kind 重建丢）；issue 生命周期 GitHub 白拿 |
| 轨迹来源 | 仅客户端 SSE | 客户端 SSE + `GET /threads/{sid}/subscribe` 服务端回放**双视角** | 区分「事件没发」vs「传输丢失」 |

### 2.2 明确不做清单

知识库/RAG、代码自动直改、基线自动更新、judge 分数阻断/告警、评测自有 MySQL 库（文件 + GitHub Issues 替代，§10）、`SandboxExecutionGrader`（现有 e2e-sandbox job 已覆盖主路径）、性能并发 grader（bench 已覆盖，用例库仅打 `performance` 标签供压测场景引用）。

### 2.3 v1 评审结论摘要（存档）

- 可行性：前四层（触发→用例→评测→打分→报告）可行；第五层全自动修复过于乐观。
- 事实硬伤：SSE 帧名全部错误；`/llm-calls` 响应无 usage 字段（后经复核：token 可从 `MODEL_CALL_END` 帧采集，见 §4.4）。
- 设计级缺陷：LLM judge 噪声 vs「基线退化 5% 阻断」不成立；误差级联（生成→打分→根因→修复四环 LLM 连乘）；成本无估算。

## 3. 总体架构

```
                ┌────────────── 共享契约层 ──────────────┐
                │ 帧映射表 frame-mapping.json              │
                │ 统一轨迹 Schema（trace JSON）            │
                │ 用例库 bench/eval/cases/（git 唯一事实源）│
                └───────┬─────────────────────┬─────────┘
                        │                     │
 ┌───────── 门禁轨（轨道 A）─────────┐  ┌────────── 趋势轨（轨道 B）─────────────┐
 │ CI 每次 PR 触发                    │  │ nightly / 发版触发                     │
 │ Node，扩展现有 e2e（api-eval 组）  │  │ Python 3.11 + agentscope.evaluate      │
 │   + OpenJudge                       │
 │ mock LLM 回放，零密钥零外联        │  │ 真实 LLM 执行 + LLM judge 打分         │
 │ 确定性断言：SSE 帧/HITL/交付/MCP   │  │ 多维打分 + 基线趋势对比（不阻断）      │
 │ → 第七项必需检查，失败即阻断       │  │ → 报告归档 + 失败项进根因分析          │
 └────────────────┬──────────────────┘  └───────────────┬───────────────────────┘
                  │ 修复合入后回归                        │ 失败轨迹
                  └──────────────┬───────────────────────┘
                        ┌────────▼─────────┐
                        │ 分析层            │
                        │ 规则初筛 + LLM 根因│
                        │ 双视角交叉定位     │
                        │ → 修复 patch 建议  │
                        │   （贴 PR 人审）   │
                        └──────────────────┘
```

两轨回答不同问题：门禁轨「这次变更有没有破坏既有契约」；趋势轨「Agent 能力质量在往哪个方向走」。任何一轨都不自动改代码、不动基线。

## 4. 事实契约层（两轨共享地基，最先建设）

### 4.1 SSE 帧映射表（真实词表）

线上词表的权威定义在 `AgentEventSseSerializer.payload()`（`controller/AgentEventSseSerializer.java:51`）：`type` 默认取 SDK 枚举 `AgentEventType.name()`（大写蛇形，共 31 值，完整清单见附录 A），两处特殊：

- `RequireUserConfirmEvent` 序列化时 **type 覆写为 `permission_ask`**（`AgentEventSseSerializer.java:123`，携带 `tool_calls` + `reply_id`）——HITL 请求的线上词条不是枚举名。
- `MODEL_CALL_END` 携带 usage：`inputTokens / outputTokens / totalTokens`（`AgentEventSseSerializer.java:107-112`）。

控制器/服务层另有 9 个合成帧（不经 SDK 枚举；`permission_ask` 双路径：序列化覆写 + invokeStream 链路 emitSynthetic）：`session_created`（`ChatStreamController.java:249`）、`file_ready`（`:622`，present_file/present_url 返回后合成）、`tool_call_summary` / `tool_result_preview`（`TurnToolSummaryTracker.java:101,123`，多副本续传同样可回放）、`task_update`（`AgentRuntimeService.java:163`）、`waiting`、`done`、`error`、`permission_ask`。

**评测关注点 → 真实帧映射**（v1 方案错误词条一并对照，作为历史教训）：

| 评测关注点 | v1 假设（错误） | 真实帧（线上词条） |
|---|---|---|
| 会话创建 | `session_created` | `session_created`（合成，恰好正确） |
| 思考流 | `thinking` | `THINKING_BLOCK_START / THINKING_BLOCK_DELTA / THINKING_BLOCK_END` |
| 文本流 | — | `TEXT_BLOCK_START / TEXT_BLOCK_DELTA / TEXT_BLOCK_END` |
| 工具调用 | `tool_call` | `TOOL_CALL_START / TOOL_CALL_DELTA / TOOL_CALL_END`（+ 合成 `tool_call_summary`） |
| 工具结果 | `tool_result` | `TOOL_RESULT_START / TOOL_RESULT_TEXT_DELTA / TOOL_RESULT_DATA_DELTA / TOOL_RESULT_END`（+ `tool_result_preview`） |
| 模型调用 | — | `MODEL_CALL_START / MODEL_CALL_END`（END 带 token usage） |
| HITL 请求 | `hitl_pending` | **`permission_ask`**（RequireUserConfirmEvent 覆写） |
| HITL 确认 | `hitl_confirmed` | `USER_CONFIRM_RESULT`（由 `POST /threads/{sid}/confirm` 触发） |
| 轮次结束 | `agent_end` | `AGENT_END`（+ 合成 `done`） |
| 错误 | `error` | `error`（合成） |
| 文件交付 | `file_ready` | `file_ready`（合成，present_file/present_url 触发） |
| 流程中间态 | — | `waiting`、`task_update`、`AGENT_START`、`AGENT_RESULT`、`EXCEED_MAX_ITERS` 等 |

> **实证修正（2026-09-25，对 release-agent 真实流实测）**：`done` / `task_update` / `error` 合成帧仅出现在 `AgentRuntimeService.invokeStream`（A2A/内部通道）方言中；`POST /threads/chat` 主对话流**不发 `done`**，以 `AGENT_END` 收尾。门禁/趋势断言的轮次终止帧用 `AGENT_END`。

映射以配置文件 `bench/eval/config/frame-mapping.json` 维护（语义关注点 → 帧序列模式，如 `tool_call` 关注点 = `TOOL_CALL_START…TOOL_CALL_END` 完整配对），两轨共用作唯一事实源；框架升级新增枚举时只改配置 + 补映射，不改代码。

### 4.2 统一轨迹 Schema

每条用例每次执行产出一份规范轨迹（JSON 文件，归档 `bench/eval/reports/{task_id}/traces/{case_id}#{repeat}.json`，task_id 结构见 §10.2）：

```json
{
  "case_id": "case_present_url_whitelist_001",
  "task_id": 42,
  "session_id": "…",
  "status": "success | failed | timeout",
  "duration_ms": 8432,
  "events": [
    {"t_ms": 0, "type": "session_created", "raw": {"…": "…"}},
    {"t_ms": 120, "type": "TOOL_CALL_START", "raw": {"toolName": "present_url", "toolCallId": "…"}}
  ],
  "tool_calls": [
    {"name": "present_url", "args": {}, "result": {}, "ok": true,
     "duration_ms": 120, "summary_frame": true}
  ],
  "deliveries": [
    {"kind": "file_ready", "file_name": "…", "file_id": "…", "url": "…", "accessible": true}
  ],
  "hitl": [{"asked": true, "confirmed": true, "tools": ["present_url"]}],
  "token_usage": {"input": 1520, "output": 380, "total": 1900},
  "llm_call_count": 3,
  "final_output": "…",
  "server_view_diff": null
}
```

- `events` 保留原始帧（含 delta），供 grader 与根因分析取完整上下文。
- `tool_calls / deliveries / hitl / token_usage` 为帧映射解析后的**结构化视图**，断言与打分主要消费这层，不重复解析原始流。
- `server_view_diff` 为双视角比对结果（§4.3），无差异为 null。

### 4.3 双视角采集

| 视角 | 通道 | 用途 |
|---|---|---|
| 主视角（客户端） | 评测执行器解析 `POST /threads/chat` SSE 流 | 协议合规以此为准——所见即客户端所得 |
| 辅视角（服务端） | 执行结束后 `GET /threads/{sid}/subscribe`（`SessionStreamController.java:67`，durable SSE 回放 + 游标追赶，数据源为 Redis Streams 持久化 `SessionEventStore`） | 与主视角做事件集 diff，区分「事件从未产生」（两视角都缺）vs「传输丢失」（仅主视角缺） |
| LLM 明细 | `GET /threads/{sid}/llm-calls`（`ThreadController.java:310`） | 请求/响应原文，供根因分析；**该接口无 usage 字段** |

辅视角走 HTTP 回放，不直连 Redis，保持零侵入。回放需要有效的会话游标语义，评测器在 chat 连接关闭后立即订阅回放全程（from head）。

### 4.4 token 消耗采集（对 v1 评审结论的修正）

v1 评审时认为 token 只能估算；复核确认 `MODEL_CALL_END` 帧携带 `inputTokens/outputTokens/totalTokens`，**客户端轨迹即可精确累计**。`/llm-calls` 仅作请求响应明细补充。若未来需要按 provider 原始 usage 对账，再评估框架侧增强（暂不做，保持零侵入）。

## 5. 门禁轨（轨道 A）：扩展现有 e2e

### 5.1 落点与复用

不新建系统。在 `agent-framework/e2e/` 内扩展：

- 新增 spec：`tests/api-eval.spec.ts`（确定性断言组）；`run.sh` 增加 `eval` 分组（`PROJECTS="--project=api-eval"`），完全复用 `env-up.sh / env-down.sh / wait-ready.sh` 编排、mock LLM 回放（`:18081`）、mock MCP（`:18082` / `:8813`）、GH Actions services 的 MySQL/Redis。
- 用例库中 `status=active` 且 `category ∈ {core, boundary, protocol}` 的用例，由一个 Node 侧加载器（`e2e/lib/eval-cases.mjs`）读取 `bench/eval/cases/*.json`，生成结构化断言（§5.2 用例回归行）。

### 5.2 断言清单（全部确定性，mock 回放驱动）

| 断言组 | 内容 | 现状覆盖 |
|---|---|---|
| SSE 协议 | `session_created` 先行；`TEXT_BLOCK_*` / `THINKING_BLOCK_*` / `TOOL_CALL_*` / `TOOL_RESULT_*` 块配对完整（START 必有 END）；`AGENT_END` 后正常收尾（`done`）；`error` 帧格式规范 | 部分散落在 api-core，本组系统化 |
| HITL 流程 | `ask` 档工具触发 `permission_ask`（含 `tool_calls` 明细）；`POST /threads/{sid}/confirm` 后收到 `USER_CONFIRM_RESULT`；`read_only` 权限下写操作被拒 | api-core 有基础流程，补齐边界 |
| 交付 | `present_file`/`present_url` 成功 → `file_ready` 帧（`file_id/file_name` 元数据完整）+ `/files/{id}` 可下载；**白名单外 URL 调用 `present_url` 返回 err 且不产生 `file_ready`**（`FILE_EXTERNAL_URL_PREFIXES` SSRF 防护回归，`FileTools.java:182`） | 现有 F12 覆盖部分，本组收编 |
| MCP | `/tools` `/mcp` `/skills` 注册清单与 OAF 配置一致；MCP 工具调用成功/失败两路径的帧序列 | 部分覆盖 |
| 用例回归 | 用例库 active 用例按 `expected` 机器可判字段断言（见 §7.1 格式约束） | 全新 |

**约束**：门禁轨 expected 只允许机器可判断言（帧存在性/顺序/字段值/HTTP 状态码），禁止语义判断——语义判断属于趋势轨 grader。

### 5.3 CI 接入

`agent-framework-ci.yml` 新增 `eval-gate` job（与现有 e2e job 同构）：

```yaml
  eval-gate:
    name: E2E 评测门禁（api-eval）
    needs: [changes]
    if: needs.changes.outputs.framework == 'true' || needs.changes.outputs.workflows == 'true'
    runs-on: ubuntu-latest
    timeout-minutes: 30
    services:   # 与 e2e-core 相同的 mysql:8.0 + redis:7-bookworm
      …
    env:
      E2E_GROUP: eval
      E2E_BASE_PORT: "8100"
      MYSQL_URL: jdbc:mysql://127.0.0.1:3306/agent_framework_e2e
      …
    steps: [checkout, setup-java 21, setup-node 22, mvn -B -q -DskipTests package,
            npm ci, ./scripts/run.sh eval]   # 无需 Playwright 浏览器（纯 API 断言）
```

- 触发范围与现有 job 一致（`agent-framework/**` 或本 workflow 变更）。
- 合入后分支保护必需检查从六项扩为**七项**（需仓库管理员同步更新保护规则——开放事项 §14）。
- mock 回放依赖 fixtures：若 active 用例需要新场景的 LLM 响应，先按 `record-llm.mjs` 配方补录制件（`check-fixtures.mjs` 会把缺件挡在本地）。

## 6. 趋势轨（轨道 B）：Python + agentscope.evaluate + OpenJudge

### 6.1 工程与依赖

- 目录 `agent-framework/bench/eval/`，`pyproject.toml` 钉死：`py-openjudge==0.2.2`、`agentscope>=2.0,<2.1`（以官方 [Evaluation with OpenJudge](https://doc.agentscope.io) 适配路径为准）；升级必须显式 PR。
- Python 3.11；CI 用 `actions/setup-python`，锁 `requirements.lock`（uv/pip-tools 产物）。
- 运行环境：本地或专用 runner 起真实 agent-framework（`java -jar` + 集群 MySQL/Redis 或 GH services），LLM 走真实 OpenAI 兼容端点（`EVAL_LLM_API_KEY`）。

### 6.2 执行链路（AgentScope evaluate 编排）

1. `BenchmarkBase` 子类加载用例集（active + observing，来源 §7 用例库）。
2. Solution 函数 = SSE 客户端 + 帧映射解析，产出 `SolutionOutput(success, output, trajectory, meta)`：
   - 解析按 §4.1 真实词条（**v1 附录 A.1 示例按 `type=="tool_call"` 判定，线上采不到任何数据，已废弃**）；
   - trajectory = §4.2 统一轨迹的结构化视图（events 原始 + tool_calls/deliveries/hitl/token_usage）；
   - HITL 用例：收到 `permission_ask` 后按用例配置调 `POST /threads/{sid}/confirm`（或 confirm-stream）继续。
3. `GeneralEvaluator(n_workers=5, n_repeat=3)` 批量执行；`FileEvaluatorStorage` 落盘原始结果，轨迹与任务/用例级摘要落 run 产物目录（§4.2、§10.2）。
4. 双视角：每条执行完成后拉 `/threads/{sid}/subscribe` 回放做事件集 diff，写入 `server_view_diff`（可配置关闭，节省时长）。

### 6.3 打分器配置

| 层 | 打分器 | 输入 | 说明 |
|---|---|---|---|
| 通用 | `RelevanceGrader`、`CorrectnessGrader`、`ConcisenessGrader` | query、response、ground_truth | OpenJudge 内置 |
| Agent 专项 | `ToolSelectionGrader` | query、tool_definitions（取自 `GET /tools`/`GET /mcp`）、tool_calls（轨迹结构化视图） | 工具选择与参数合法性 |
| Agent 专项 | `TrajectoryGrader` | query、trajectory、ground_truth | 执行路径合理性、步骤冗余 |
| 框架定制 | `DeliveryCorrectnessGrader` | deliveries + final_output + ground_truth | 交付语义面（文件内容与请求一致性等**需要语义判断**的部分；帧结构/白名单等确定性部分在门禁轨断言） |
| 框架定制 | `HitlComplianceGrader` | hitl 轨迹 + 用例预期 | 该确认的是否确认、确认话术是否合规 |
| 框架定制 | `McpCompatibilityGrader` | MCP 工具调用轨迹 + `/mcp` 定义 | 调用语义与结果引用正确性 |

基于 `BaseGrader` 扩展定制 grader；经 `OpenJudgeMetric` 适配器（`MetricBase` 子类 + `parse_data_with_mapper`）接入 `GeneralEvaluator`。

**分界原则**：凡能确定性判断的（帧序、字段、状态码、白名单）不进 LLM grader——已在门禁轨零成本覆盖；趋势轨 grader 只承担语义判断。

### 6.4 聚合与统计

- `WeightedSumAggregator` 权重：任务正确性 35% / 工具调用质量 30% / 框架特性合规 20% / 回复质量 15%（配置文件可调）。
- `DistributionAnalyzer` 输出均值/中位数/通过率/分布。
- **方差过滤**：`n_repeat=3` 的单用例得分标准差 > 阈值（初值 0.25）标记 `unstable`，该用例本轮不计入趋势对比，单独列报告（LLM 随机性隔离）。
- judge 分数与基线对比结果**只进报告与看板，不阻断、不自动告警合流**。

### 6.5 触发与成本模型

| 触发 | 范围 | LLM | 估算成本 | 阻断 |
|---|---|---|---|---|
| 每次 PR | 门禁轨（§5） | mock | 0 | 是 |
| nightly（schedule） | active + observing 全量 + diff 用例生成 | 真实 | 用例数 × 3 重复 ×（1 solution + ~5 grader 调用）；150 用例 ≈ 2700 次调用/晚 | 否 |
| 发版（workflow_dispatch / tag） | 全量 + 基线对比报告 + 失败项根因分析 | 真实 | nightly + 失败项 RCA | 否 |

密钥隔离：`EVAL_LLM_API_KEY` 只存在于 nightly/release 专用 workflow（新文件 `.github/workflows/agent-framework-eval.yml`）的 secrets；现有三 e2e job 与门禁轨保持零密钥零外联不变。

## 7. 用例管理

### 7.1 用例格式（`bench/eval/cases/*.json`，一案一文件）

```json
{
  "case_id": "case_present_url_whitelist_001",
  "title": "present_url 白名单外 URL 拒绝",
  "category": "boundary",            // function | core | boundary | protocol | performance
  "priority": "high",                // high | medium | low
  "module": "tool/present_url",
  "source": "manual",                // manual | diff_gen | distill
  "status": "active",                // draft | observing | active | retired
  "input": {"message": "请把 http://evil.example.com/x.png 登记为下载卡片",
             "userId": "eval-test"},
  "hitl_policy": "auto_confirm",     // none | auto_confirm | auto_reject | manual
  "expected": {                       // 机器可判断言（门禁轨 + 趋势轨共用）
    "frames": {"file_ready": 0, "error_contains": "external URL prefixes"},
    "http": {"tool_result_ok": false}
  },
  "ground_truth": "present_url 应校验 FILE_EXTERNAL_URL_PREFIXES 白名单并拒绝登记，返回 err 说明",
  "eval_dimensions": ["delivery_correctness", "tool_selection"],
  "origin": {"commit": "…", "task_id": null}
}
```

约束：`expected` 必须机器可判（门禁轨断言直接消费）；`ground_truth` 供 grader 语义判断；`performance` 类只进趋势轨压测引用，不进门禁轨。

### 7.2 入口一：Git diff 驱动生成（LLM 零样本/少样本，四道闸门）

**输入**：PR diff、能力清单（`GET /tools` / `GET /mcp` / `GET /skills` + OAF 配置）、用例库现有样例（风格参照 + 去重基底）。

| 闸门 | 实现 | 说明 |
|---|---|---|
| ① 变更影响分析（纯规则） | 文件路径 → 模块映射（`tool/ mcp/ sandbox/ controller/ service/ storage/ config/`），输出变更模块 + 风险等级 | 不调 LLM |
| ② 候选生成（LLM） | 每模块 ≤5 条（防膨胀），function + boundary 两类；输入附库内 2-3 条优质用例做 few-shot | 提升 expected 可判定性 |
| ③ 静态校验 + 动态冒烟 + 语义去重 | JSON Schema 校验必填字段；在趋势轨环境**实际执行一遍**，过滤跑不通/无轨迹/超时（预期淘汰率 20-40%）；与库内用例语义相似度超阈值丢弃 | 保证入库质量的关键 |
| ④ PR 人工审核 | 存活用例以 **PR 形式提交 `cases/` 目录**（`source=diff_gen`、`status=draft`），审核重点是 expected/ground_truth 本身是否正确 | merge 前 = draft/observing，只进趋势轨 nightly；merge 后 = active，进门禁轨 + 趋势轨全量 |

触发时机：PR 创建/更新时生成候选并附在评测报告评论；merge 到 master 后由 nightly 批量补充生成（全量 diff）。

### 7.3 入口二：失败轨迹蒸馏（人工一键）

趋势轨失败用例在修复验证通过后，支持将真实输入一键固化为回归用例（预填 `source=distill`），仍走 ④ PR 审核。两个入口共用同一闸门，这是用例库质量最高的来源。

### 7.4 用例库治理

- 库即 git 目录 `bench/eval/cases/`，唯一事实源，无 DB 索引层（§10.1）；所有变更走 PR 可追溯。
- 状态机：`draft`（生成待审）→ `observing`（审核通过试运行，仅趋势轨）→ `active`（进门禁轨回归集）→ `retired`（淘汰）。
- 回归集（active）上限 300 条；超限按「优先级 + 最近失败率 + 距上次失败时间」淘汰，淘汰走 PR。
- 每季度人工复盘一次低价值用例（连续 90 天通过且无蒸馏价值 → 候选 retired）。

## 8. 根因分析与修复建议

1. **失败分类（规则初筛）**：七类映射——配置加载（`config/`、OAF 解析）/ 工具调用（`tool/`、`mcp/`）/ 流程控制（`service/`、HITL、租约）/ 输出交付（`storage/`、`present_*`）/ 协议接口（`controller/`、channel）/ 沙箱（`sandbox/`）/ 模型交互（LLM 封装）。基于错误码、帧特征、失败阶段匹配。
2. **LLM 深度分析**：输入 = 双视角轨迹（含 `server_view_diff`）+ `/llm-calls` 明细 + 关联代码片段。双视角定位「事件没发 vs 丢了」是 v1 没有的能力。
3. **修复建议输出**：生成 patch（unified diff）附在评测报告，**不直接改代码**。人工决定是否应用到修复分支；应用后走正常 PR，门禁轨自动回归（原失败用例 + active 回归集）。
4. **验证标准**：原失败用例转绿 + 门禁轨全量通过 = 修复生效；不通过则二次分析（最多 2 轮，避免无限循环），仍未解决标记 `fix_status=rejected` 转人工。
5. **建议质量度量**：按 GitHub Issue 的 fix_status label（suggested/applied/verified/rejected，§10.3）统计修复建议采纳率——为将来是否升级全自动修复提供真实数据（v2 不建知识库，issue 即数据积累）。

## 9. 基线管理（git 快照，人工变更）

- 基线文件：`bench/eval/config/baselines/{version}.yaml`，字段 = 各维度得分、通过率、平均耗时、**judge 模型版本、被测框架版本、用例集版本（cases/ 的 commit）**。
- 快照时机：发版时人工确认生成；基线任何变更走 PR（不允许自动写入，杜绝「自己移动球门」）。
- 可比性规则：judge 模型或用例集版本不一致 → 历史不可比，报告显式标注；门禁轨断言通过率是唯一可阻断的基线指标（天然确定性）。

## 10. 数据与持久化（文件 + GitHub Issues，不建评测自有库）

评测系统**不引入自有 MySQL 库**（2026-09-25 评审决议）。注意区分：被测框架自身运行仍依赖 MySQL/Redis（agent_state / agent_fs / SessionEventStore），那是运行时依赖；裁掉的是评测系统自己的数据层。

### 10.1 决策依据

| 数据 | MySQL 表方案 | 问题 | v2 归宿 |
|---|---|---|---|
| 用例 | 独立用例库存储 + 索引表 | 事实源必须唯一；回归集上限 300 条，扫 git 文件选案成本可忽略 | git `cases/*.json` 唯一事实源（§7.4） |
| 运行历史 | `eval_task` / `eval_trace` | 可用的 MySQL 都存不住趋势历史：CI services 库每 run 销毁；集群 `oaf-mysql` 活在单节点 Kind 开发集群、重建即丢 | 每 run 一个产物目录 + 追加式 `history.jsonl`（§10.2） |
| 问题跟踪 | `eval_issue` 表 + 状态字段 | 生命周期/检索/通知/协作都要在表上自己造 | GitHub Issues，label 即分类与状态（§10.3） |

仓库先例与成本：bench 压测结果本就是文件（`bench/results/` + report.js）无结果库；e2e 刻意零密钥零外联。引入自有库的代价（Phase 1-3 期间 DDL 迁移管理、nightly 凭据配置、Python 连接依赖）换不来文件方案覆盖不了的能力——年规模约 16 万行轨迹摘要（150 用例 × 3 重复 × 365 天），jsonl + pandas 足够。

### 10.2 运行产物布局（趋势轨）

```
bench/eval/reports/               # gitignored；持久化通道见 §10.4
├── history.jsonl                  # 跨 run 趋势账本，每 run 追加一行
└── {task_id}/                     # task_id = {commit8}-{track}-{yyyyMMdd-HHmmss}
    ├── task.json                  # 任务元数据：commit_hash / framework_ver / track / trigger_type / judge_model / status / case_count / pass_rate / total_score / started_at / finished_at
    ├── summary.jsonl              # 每用例每重复一行：case_id / session_id / repeat_no / status / duration_ms / event_count / tool_call_count / input_tokens / output_tokens / has_file_ready / unstable 标记 / trace 相对路径
    ├── report.md                  # 结构化报告（§8 口径）
    └── traces/                    # §4.2 统一轨迹 JSON 全量
```

`history.jsonl` 行示例：

```json
{"task_id":"d0c3eaa1-trend-20260926-030000","commit":"d0c3eaa1","framework_ver":"2.1.0","judge_model":"glm-4.7","case_count":150,"pass_rate":0.92,"total_score":0.87,"ts":"2026-09-26T03:00:00+08:00"}
```

趋势对比 = `history.jsonl` 最新行 vs 基线 yaml（§9）；方差过滤（§6.4）与双视角 diff 标记落在 `summary.jsonl` 行级，不另建存储。

### 10.3 问题跟踪：GitHub Issues

- nightly 对 blocker / critical 失败自动开 issue（`gh` CLI）：label 体系 `eval` + `severity::{blocker,critical,minor,tip}` + `category::{config,tool,flow,delivery,protocol,sandbox,model}`（对应 §8 七类）。
- issue 正文 = RCA 结果 + 关键轨迹片段 + trace 产物链接；修复 patch 建议以评论附上（不直改代码，§8）。
- fix_status 状态机映射：`suggested`（开 issue）→ `applied`（patch 被人采用开 PR 时打 label）→ `verified`（门禁轨回归转绿后打 label 再关闭）→ `rejected`（打 label 关闭）。
- 修复建议采纳率 = 按 label 检索 issue（一条 `gh` 命令），同时承担 v1「修复方案知识库」的数据积累职责。

### 10.4 持久化通道

| nightly 运行位置 | 通道 |
|---|---|
| 自建/专用宿主（推荐） | `reports/` 落宿主磁盘，`history.jsonl` 自然累积 |
| GH hosted runner | 每 run 上传全部产物为 artifact（retention 调至最大），趋势脚本先下载聚合；`history.jsonl` 可由 artifacts 重建 |

### 10.5 重新评估引入 DB 的触发条件

满足其一再议，届时优先独立实例或 SQLite（不占平台库）：自建 Web 看板需要任意维度 ad-hoc 查询；轨迹摘要超过数万条且切片查询变慢；出现多写入方并发；issue 统计外溢出 GitHub 能力。

## 11. 目录结构

```
agent-framework/
├── e2e/                          # 门禁轨（Node，扩展现有工程）
│   ├── tests/api-eval.spec.ts    # 确定性断言组（run.sh eval）
│   └── lib/eval-cases.mjs        # 用例库加载器（读 ../bench/eval/cases/）
└── bench/
    └── eval/                     # 趋势轨 + 用例生成 + 分析（Python 3.11）
        ├── pyproject.toml        # 钉死 py-openjudge / agentscope 版本
        ├── requirements.lock
        ├── cases/                # 用例库（git 版本化，PR 审核变更）
        ├── executor/             # SSE 客户端 + 帧映射解析 + 双视角采集
        ├── graders/              # 框架定制 Grader（BaseGrader 扩展）
        ├── case-gen/             # diff 驱动生成 + 蒸馏入口（四道闸门）
        ├── analyzer/             # 根因分析 + 报告生成
        ├── reports/              # 运行产物（gitignored）：history.jsonl + {task_id}/（§10.2）
        └── config/
            ├── frame-mapping.json
            ├── graders.yaml      # 打分器与权重配置
            └── baselines/        # 基线快照（git 版本化）
```

## 12. CI 集成汇总

| Workflow | 触发 | Job | 密钥 | 阻断 |
|---|---|---|---|---|
| `agent-framework-ci.yml`（改） | push master / PR master | 现有 5 job + **eval-gate** | 无 | 是（第七项必需检查） |
| `agent-framework-eval.yml`（新） | schedule nightly / workflow_dispatch / release | `trend-eval`（全量打分+报告+产物归档 §10.4）、`case-gen`（diff 生成+PR 提交）、`rca`（失败项根因+自动开 issue，发版时） | `EVAL_LLM_API_KEY`（仅此 workflow） | 否 |

分支保护需同步：必需检查增加「E2E 评测门禁（api-eval）」（§14 开放事项）。

## 13. 实施路线图与验收标准

| 阶段 | 周期 | 交付 | 验收 |
|---|---|---|---|
| **Phase 1 契约与门禁** | 1-2 周 | `frame-mapping.json` + 统一轨迹 schema；`api-eval.spec.ts` 断言五组；人工编写 20+ 核心用例（manual/active）；`eval-gate` CI job；`eval-cases.mjs` 加载器 | eval-gate 在 PR 上全绿成为必需检查；对 SSE 帧/HITL/交付/白名单的既有行为形成断言保护 |
| **Phase 2 趋势轨与用例生成** | 2-3 周 | `bench/eval/` Python 工程（executor/graders）；OpenJudge 打分链路 + 报告 + 趋势对比；diff 生成四道闸门 + PR 审核流；nightly workflow；运行产物与 `history.jsonl` 持久化 + blocker/critical 自动开 issue | nightly 稳定产出报告与趋势账本；生成的候选用例经人审 merge 进入 observing→active 闭环跑通 |
| **Phase 3 分析与基线** | 1-2 周 | 双视角根因分析 + 修复 patch 建议；蒸馏入口；基线快照与发版流程 | 至少一个真实失败从「轨迹 → 根因 → patch 建议 → 人审合入 → 门禁轨回归转绿」完整走通；首个基线快照落盘 |

Phase 3 结束后凭 GitHub Issue 的 fix_status label（§10.3）真实数据再评估是否投入更高自动化（届时决策依据是成功率而非假设）。

## 14. 风险与开放问题

| # | 项 | 影响 | 对策 |
|---|---|---|---|
| 1 | py-openjudge / agentscope-python 年轻依赖（0.2.0 曾破坏性改名） | 升级可能破坏打分链路 | 版本钉死 + requirements.lock；升级走 PR 全量回归 |
| 2 | 分支保护七项必需检查需管理员手动更新 | 遗漏则 eval-gate 不阻断 | Phase 1 验收项 |
| 3 | 门禁轨依赖 mock fixtures，新用例需录制 | 用例扩充成本 | 沿用 `record-llm.mjs` 配方；`check-fixtures.mjs` 前置拦截 |
| 4 | diff 生成用例的 expected 错误率 | 错误预期会造成误报 | 四道闸门 + PR 人审为硬性关卡 |
| 5 | judge 分数波动 | 趋势误读 | n_repeat=3 + 方差过滤 + judge 模型固化进基线 |
| 6 | `/threads/{sid}/subscribe` 回放语义与游标细节 | 双视角 diff 误判 | Phase 3 先在少量用例灰度开启，验证后默认开启 |
| 7 | nightly 真实 LLM 成本 | 费用失控 | §6.5 成本模型 + 用例集分档（active/observing）+ workflow 可手动暂停 |
| 8 | 多副本（R 组）场景趋势轨是否覆盖 | 趋势轨 v1 单副本 | 暂不覆盖，多副本正确性由门禁轨 e2e-multi 承担；后续按需扩展 |
| 9 | 趋势历史依赖宿主磁盘 / artifact 保留期（GH artifacts 默认 90 天） | 历史丢失 | §10.4：优先自建宿主；GH hosted 时 retention 调最大，`history.jsonl` 可由 artifacts 重建 |

## 附录 A：线上帧词表完整清单（截至 2026-09-25）

**SDK 枚举 `AgentEventType`（31 值，序列化默认取 `name()`）**：
`AGENT_START`、`AGENT_END`、`AGENT_RESULT`、`MODEL_CALL_START`、`MODEL_CALL_END`（带 usage）、`TEXT_BLOCK_START/DELTA/END`、`THINKING_BLOCK_START/DELTA/END`、`DATA_BLOCK_START/DELTA/END`、`TOOL_CALL_START/DELTA/END`、`TOOL_RESULT_START`、`TOOL_RESULT_TEXT_DELTA`、`TOOL_RESULT_DATA_DELTA`、`TOOL_RESULT_END`、`EXCEED_MAX_ITERS`、`REQUIRE_USER_CONFIRM`（线上覆写为 `permission_ask`）、`REQUIRE_EXTERNAL_EXECUTION`、`USER_CONFIRM_RESULT`、`EXTERNAL_EXECUTION_RESULT`、`REQUEST_STOP`、`SUBAGENT_EXPOSED`、`HINT_BLOCK`、`ALL_TOOLS_DENIED`、`CUSTOM`

**合成帧（9 个，控制器/服务层产生，同样入 Redis 事件流可回放；`permission_ask` 有两条产生路径——序列化覆写与 invokeStream 链路 emitSynthetic）**：
`session_created`、`waiting`、`done`、`error`、`task_update`、`permission_ask`（invokeStream 链路词条）、`file_ready`、`tool_call_summary`、`tool_result_preview`

权威来源：`controller/AgentEventSseSerializer.java`（枚举序列化与覆写）、`controller/ChatStreamController.java`（session_created/file_ready）、`service/TurnToolSummaryTracker.java`（tool 摘要帧）、`service/AgentRuntimeService.java`（task_update/error）。词表变更时同步更新 `frame-mapping.json`。

## 附录 B：评测对接的既有接口清单（零侵入通道）

| 接口 | 位置 | 评测用途 |
|---|---|---|
| `POST /threads/chat`（SSE） | `ChatStreamController.java:188` | 主对话通道，主视角轨迹 |
| `GET /threads/{sid}/history` | `ThreadController.java:177` | 会话历史权威数据 |
| `GET /threads/{sid}/llm-calls` | `ThreadController.java:310` | LLM 请求/响应明细（无 usage 字段） |
| `GET /threads/{sid}/subscribe`（SSE） | `SessionStreamController.java:67` | 服务端事件回放，双视角辅证 |
| `GET /threads/{sid}/status` | `SessionStreamController.java:101` | 会话状态查询 |
| `GET /tools`、`GET /mcp`、`GET /skills` | `ToolController.java` | 能力清单（用例生成输入 / grader 输入） |
| `POST /threads/{sid}/confirm`（及 confirm-stream） | `ConfirmController.java:90,144` | HITL 用例自动确认 |
| `GET /files/{fileId}` | `FileController.java` | 交付文件可访问性校验（external 代理回源受白名单约束） |
