# agent-framework 评测数据集层（DatasetHub）与开源生态兼容设计

> 状态：M1/M2/M3 已实施（2026-10-26/27，PR #200/#201/#202）
> 前置设计：[双轨评测](agent-framework-eval-dual-track-design.md) · [离线录制回放](agent-framework-eval-offline-record-replay-design.md)
> 背景需求：① agent 离线/在线评测数据的生成与获取统一化；② 评测数据包兼容 deepeval/langfuse
> 等开源评测软件——本平台解决数据收集，评测执行交给外部生态。

---

## 1. 调研结论（方案依据，2026-10-24/25）

| 来源 | 结论 |
|---|---|
| AgentScope 官方 `agentscope.evaluate` | 有评测执行框架（BenchmarkBase/Task/MetricBase/GeneralEvaluator/RayEvaluator），**无数据生成与数据集管理层**；Task schema（id/input/ground_truth/tags/metrics/metadata）被本项目采纳为 item 骨架 |
| GitHub 开源（Arize Phoenix / Langfuse / Braintrust） | 均为平台型产品，无嵌库方案；共同模式 = trace 落库 → 筛选 → 人审 → 版本化数据集 → 实验 |
| deepeval EvaluationDataset | JSON/JSONL 文件友好（键名可重映射）；golden 字段 input/expected_output/context/expected_tools/additional_metadata/comments；多轮 conversational（scenario）与单轮**严禁混排同一文件**；expected_tools 是 ToolCall **映射列表**（纯字符串解析崩，实测） |
| langfuse 公共 API | dataset items 按 id upsert（幂等键）；**dataset-items 不自动建集**（实测 404，需先 POST /api/public/datasets）；dataset-run-items REST 已弃用（实验走 SDK）；OTLP 入口 /api/public/otel（M3 用） |

评审决议：执行引擎仍用现有 flywheel 编排（RayEvaluator/agentscope.evaluate 适配器不做，
Ray 依赖重且被测服务并发才是真瓶颈）；DatasetHub **收编统一**——成为唯一事实源，cases/ 降为投影。

## 2. 架构

```
【数据获取层】                     【数据集层 DatasetHub（唯一事实源）】        【消费层】
在线 T1 collector 旁路录制(已有) ─┐  datasets/{service}/{name}/               内部: flywheel run/verify/live/replay
在线 T2 checkpoint 骨架导出(新)  ─┼→  dataset.json + items/*.json + CHECKSUMS  外部: deepeval JSONL / langfuse items
离线 G1 diff 生成(已有) G2 派生(已有)┤  item = AgentScope Task 骨架 + 四块扩展    投影: cases/（兼容层，cases-legacy 再生）
离线 G3 LLM 合成 golden(新)     ─┘  状态机 draft→reviewed→active→archived
```

设计原则沿用既有决议：零侵入、人审硬闸门（draft 不进执行链路）、judge 分数永不阻断、
不建评测自有 MySQL（文件 + git + studio SQLite 索引）、凭据只走环境变量。

## 3. DatasetHub（M2 实施）

- **落盘**（`bench/eval/dataset.py`，format_version 1）：`datasets/{service}/{name}/` 下
  `dataset.json`（元信息/changelog/stats）+ `items/{item_id}.json` + `CHECKSUMS`
  （复用 `packager.verify_checksums` 三重校验）。item schema：`id/input/turns/ground_truth/
  expected/status/metadata`——expected（契约断言）与 turns 是对 Task 骨架的扩展，
  `to_case()` 投影为 runner.load_cases 同构字典，executor/checks 零改动。
- **状态机**：只进不退（archive 可从任意态进入）；`set_status` 强制。
- **收编**：9 条 cases 一次性迁移为 `datasets/agent-framework/regression/`（source=manual，
  status=active，随 PR 入库）；`flywheel run/verify/live/export` 读取链路统一切 `load_active_cases()`；
  cases/ 变投影，`export --target cases-legacy` 再生（带勿手改注记）；**freshness 守护**（selftest）：
  git 已跟踪的 active items == cases 投影 ids，漂移即红——口径刻意限定 git 已跟踪，
  运行态数据集（gen-drafts/skeleton/imports，见 datasets/.gitignore）自由流转不参与。
- **gen 闸门收编**：`run --with-gen` 冒烟存活候选 → `gen-drafts` 数据集 draft（g1_diff）。

## 4. 数据获取通道

| 通道 | 命令 | 机制与约束 |
|---|---|---|
| T2 checkpoint 骨架（`skeleton.py`） | `flywheel.py skeleton --out DIR [--since TS]` | 只读 SELECT session_message；默认 `--via kubectl`（exec 进 DB 容器执行，凭据全程留在 pod env，宿主机零账号）；`--via direct` 走 mysql CLI（密码只走 MYSQL_PWD）；utf8mb4 强制；输出按 sid 清洗落 JSON（thinking 剔除、终答取末段助手文本） |
| 采样导入 | `flywheel.py dataset --import-skeleton DIR [--limit N]` | 首轮输入归一化指纹去重 + limit 上限（sample 语义内置）→ draft item（保守 expected：no_error+AGENT_END 下限+实际工具） |
| G3 合成（`casegen/synthesizer.py`） | `flywheel.py synth --skeleton DIR [--base-url]` | LLM 蒸馏「改写指令 + 行为标准金标」（不逐字复制原文，防哨兵值/内网细节外泄）；四道闸门 = 字段校验→归一化去重→动态冒烟（可选）→draft 落库（g3_synth） |
| pack 派生 | `dataset --import-files "packs/*/cases-draft/*.json"` | 通用用例形态导入（draft） |

session 关联键统一为 sessionId（T1 头 / T2 主键 / T3 OTel attribute，M3）。

## 5. 外部生态兼容（M1 实施，bench/eval/exporters/）

- **deepeval**（纯文件零依赖）：单轮 `goldens.jsonl`（input/expected_output/expected_tools/
  additional_metadata/comments）+ 多轮 `conversational_goldens.jsonl`（scenario/expected_outcome）
  分文件；`--kind testcases` 从 run traces 导 LLMTestCase（input/actual_output/tools_called）；
  expected_tools/tools_called 投影为 `{"name": ...}` ToolCall 最小形态；`requires_tools: []`
  归一为缺省（空列表会被 ToolCorrectness 误读为禁止调用）。
- **langfuse**：本地 `langfuse-items.jsonl`（可 diff 人审）→ `--push` 先自动建集
  （409=已存在幂等）再按 id upsert（确定性 id：`{pack_id_}case_id`）；429/5xx 退避重试、
  4xx 快速失败；pk/sk 只从 LANGFUSE_* 环境变量，导出物零密钥。
- 导出器注册表 `exporters.TARGETS`，新增目标只登记不改分发。

## 6. studio 集成

数据集页（tab）：清单（状态统计）/明细（按状态过滤）/转正/归档/导出 deepeval/推送 langfuse。
API：`GET /api/datasets`、`GET /api/datasets/items`、`POST /api/datasets/item/status`、
`POST /api/datasets/export`（id 全部 validate_id 校验；DatasetError→409）。
镜像已打包 `eval/`（dataset/exporters 可直接 import）；数据集目录挂卷
`-v $PWD/datasets:/srv/eval/datasets`。

## 7. M3 实施（OTel→langfuse 观测 + scores 回流，2026-10-27）

1. **langfuse 升级 v3**：v2 无 OTel ingest 路由（实测 /api/public/otel 404）→
   compose 升级 v3 套件（web/worker/clickhouse/seaweedfs/redis/postgres，§8 实施记录）；
   OTLP 真实路径 = `/api/public/otel/v1/traces`（javaagent 默认拼接恰好吻合），需
   `Authorization: Basic <pk:sk>` 头。
2. **切流**（走平台 `PATCH /services/:id/env`，UpdateEnv 合并语义 + 自动滚动重启）：
   release-agent 注入三键 `OTEL_EXPORTER_OTLP_ENDPOINT`（指向宿主机 :18500）、
   `OTEL_EXPORTER_OTLP_HEADERS`（Basic 认证）、`OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf`；
   密钥只进 env（Secret 侧不动）。回滚 = PATCH 移除三键再重启。
3. **关联机制（traceparent 注入）**：FrameworkTracingMiddleware 的 `agentscope.session.id`
   attribute 未出现在 langfuse trace（SDK 行为，零侵入不查）→ 改客户端关联：flywheel
   `execute_once` 每用例生成 W3C traceparent 注入请求头，被测 OTel agent 提取上游 context，
   **langfuse traceId == 评测客户端生成的 id**；轨迹落盘 `langfuse_trace_id` 字段，
   scores 按 id 直挂（`find_traces` 按 sessionId 查询保留为兜底）。
4. **scores 回流**：`export --target langfuse-scores --run DIR [--push]`——checks 结论
   （1/0）与 judge 分数（0~1，带 judge_model）按 traceId 挂分；score id 确定性
   （sha1(traceId+name)）→ 重跑覆盖（upsert 幂等）；分数只展示永不阻断。
5. **验收（真实环境）**：release-agent 切流后评测产流量 → langfuse trace（134 observations：
   HTTP/SQL/GENERATION 全链路）→ traceId 与评测生成 id 完全一致 → score
   `eval-checks/case_chat_basic_001=1` 挂载同一 traceId → dataset release-regression 11 条
   在 v3 UI 可见；trace 页 span 树/Add to datasets/Scores tab 三向跳转元素齐备。

## 8. 实施记录与验收

- **M1（PR #200，已合并）**：9 条用例导出被 deepeval 真实安装环境加载；
  自建 langfuse v2.95.11（docker-compose.langfuse.yml）真实建集推送幂等；
  mock 契约 e2e（export_loop.py）进 selftest。实施修正三个 API 事实（§1 已收录）。
- **M2（本 PR）**：
  - 真实 checkpoint 库勘察：release-agent 用 oaf_checkpoint（issue #142 派生库为复测残留）；
    Msg JSON content[] 含 text/thinking/tool_use/tool_result——骨架解析器按此实现。
  - T2 全链路真实验收：kubectl exec 导出 22 会话 → 采样 8 条 → 转正 1 条 →
    `flywheel run --only case_t2_webui-mvhl42pg` 直打 release-agent **PASS（16.5s）** →
    deepeval conversational golden / langfuse items 双导出含转正条目。
  - G3 真实 LLM 合成 6 条 golden 入 gen-drafts（改写指令 + 行为标准金标，无原文复制）。
  - studio 数据集页 API/前端全链路验证（列表/过滤/转正/非法 id 400/导出）。
  - 实施中发现：mysql CLI 默认字符集会把 utf8mb4 转码致解码崩溃（强制 utf8mb4 修复）；
    kubectl port-forward 源 IP 不匹配容器内授权（放弃宿主机账号方案，kubectl exec 模式更优）。
