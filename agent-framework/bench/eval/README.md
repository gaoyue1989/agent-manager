# agent-framework 评测飞轮（walking skeleton）

> **日常使用看 [FLYWHEEL.md](FLYWHEEL.md)**（怎么用 / 何时用 / 场景命令表 / 排障）；
> 本文是模块结构、关键事实与已知问题的参考手册。

对应设计：[docs/design/agent-framework-eval-dual-track-design.md](../../docs/design/agent-framework-eval-dual-track-design.md)
（双轨评测：门禁轨=CI e2e 断言，趋势轨=本目录 Python 工程。本 README 描述趋势轨骨架的现状。）

## 已实现（骨架期）

| 六步流程 | 命令/模块 | 状态 |
|---|---|---|
| ① 分析最近提交的功能修改 | `run --since <sha>` → `casegen.generator.analyze_diff`（纯规则：diff→模块映射+风险） | ✅ 已验证 |
| ② LLM 生成真实评测用例 | `run --with-gen N` → `casegen/generator.py`（few-shot 生成→字段校验→去重→动态冒烟） | 已实现，需 EVAL_LLM_*，未实测 |
| ③ 评测执行+轨迹采集+断言 | `executor/`（SSE 客户端按 `config/frame-mapping.json` 解析帧→结构化视图→确定性检查） | ✅ 已验证（真实 LLM 5/5） |
| ④ 根因分析+问题报告 | `analyzer/rca.py`（七类规则初筛→report.md；`--rca-llm` 可选 LLM 深度根因） | ✅ 已验证（正确归类帧缺失问题） |
| ⑤ 人工确认 | 按设计：生成用例/修复 patch 均以 PR 呈现人审，无自动化动作 | 设计如此 |
| ⑥ 修改代码后重新评测 | `verify`（重跑上一轮失败用例 + core 集，输出转绿对比） | ✅ 已验证 |

语义打分（`--judge`，`graders/correctness.py`）：OpenAI 兼容 judge，Phase 2 换装 OpenJudge
`CorrectnessGrader`（接口不变）。judge 分数只进报告，**永不阻断**。

## 快速开始

```bash
cd agent-framework
python3 bench/eval/flywheel.py selftest                # 离线自检（无网络依赖）
# 冒烟目标：集群内 release-agent（经宿主 nginx，真实 LLM）
BASE=http://127.0.0.1:8911/agent/release-agent
python3 bench/eval/flywheel.py run --base-url $BASE --repeat 1          # 一圈（①→④）
python3 bench/eval/flywheel.py verify --base-url $BASE                  # ⑥ 修复后回归
python3 bench/eval/flywheel.py status                                   # 运行历史
# 完整闭环（含生成/打分/深度根因）：
EVAL_LLM_BASE_URL=... EVAL_LLM_API_KEY=... EVAL_LLM_MODEL=... \
  python3 bench/eval/flywheel.py run --base-url $BASE --repeat 3 --with-gen 3 --judge --rca-llm
```

产物（`bench/eval/reports/`，gitignored）：`{commit8}-trend-{ts}/` 下
`task.json` / `summary.jsonl`（每用例每重复一行摘要）/ `report.md` / `traces/{case_id}#{n}.json`（全量事件帧+结构化视图）/ `gen_cases/`（draft 用例）+ 跨轮趋势账本 `history.jsonl`。

## 用例格式（`cases/*.json`，入库走 PR 人审）

必填：`case_id / title / category / input / expected`；关键可选：
`requires_tools`（能力门禁：MCP 工具缺失则跳过；内置工具恒可用）、`hitl_policy`
（`auto_confirm` = 自动调 confirm-stream）、`expected` 机器可判键：
`frames`（数量/`">=n"`）、`frame_order`、`tool_calls.required/forbidden`、
`tool_result.{name}.ok`（state==SUCCESS）、`final_text_contains`、`final_text_min_len`、
`error_contains`、`no_error`。`ground_truth` 供语义 judge。

## 关键事实（实测确认，勿凭文档想当然）

- **帧词表**：31 个 SDK 枚举（`AgentEventType`）+ 9 个合成帧，权威清单在
  `config/frame-mapping.json`。`REQUIRE_USER_CONFIRM` 线上覆写为 `permission_ask`；
  error 帧字段是 `error` 不是 `message`。
- **`/threads/chat` 不发 `done`/`task_update`**：那是 `invokeStream`（A2A/内部通道）方言；
  主对话流以 `AGENT_END` 收尾（2026-09-25 实测）。断言终止帧用 `AGENT_END`。
- **token 消耗**：`MODEL_CALL_END` 帧自带 `inputTokens/outputTokens/totalTokens`，
  客户端轨迹即可精确累计（release-agent 单轮 system prompt ~14K tokens）。
- **内置工具恒可用**：echo/get_current_time/present_file/present_url 运行时无条件注册，
  `/tools?includeInternal=true` 只反映 OAF 声明视图——能力门禁对内置工具放行。
- **HITL**：流以 `permission_ask` 终止（turn 边界），续段走
  `POST /threads/{sid}/confirm-stream`，results 形如
  `[{tool_call_id, confirmed}]`（AgentRuntimeService.java:404）。
- **清理**：run/verify 默认 `DELETE /threads/{sid}` 清理评测会话（失败等 3s 重试一次，
  仍失败打印 WARN——2026-09-25 曾发现静默失败导致共享实例残留，现已修复并外部验证）。

## 已知问题与边界（按影响排序）

1. **外部 OpenSandbox 故障拖垮评测目标（环境问题，需运维处理）**：release-agent
   `SANDBOX_ENABLED=true`，每轮对话（含纯聊天）都经外部沙箱（宿主 docker 容器
   `opensandbox-server`，:8090）做工作区同步。2026-09-25 诊断：**进程活着（/health 200）
   但功能故障持续**——execd 404 / `chmod /tmp/workspace.tar.b64` 500 / `hydrateWorkspace
   exit 2`。根因方向（有证据、未最终定位）：USER 级沙箱复用下多轮次并发 hydrate 同一
   容器互踩 /tmp 临时文件（单并发 PASS、并发全挂可佐证）+ 沙箱容器 60min TTL 堆积
   （28 容器常驻 ~2.4G）挤压 8.8G 宿主机内存（free 仅 ~216Mi）。
   **修复前 release-agent 不是稳定评测目标**；缓解已做（TTL 60→10、清理堆积容器），
   根治跟踪 [issue #27](https://github.com/gaoyue1989/agent-manager/issues/27)；
   对共享实例跑评测建议 `--workers 2`；替代方案=本地起被测实例（见
   memory/agent-framework-local-smoke-env）。
2. **稳定性数据（2026-09-25 三组对照，每用例 repeat=3）**：workers=5 缓解前 14/15 超时
   （hydrate 互踩）；workers=2 缓解后 0 超时但 3 轮失败（其中 2 轮为 opensandbox 宿主端口
   分配竞态 `SANDBOX_START_FAILED`，1 轮为 LLM 未调工具——真实模型随机性样本）；
   **workers=1 全量 15/15 PASS**。故障与并发完全相关，两个缺陷点均已记入
   [issue #27](https://github.com/gaoyue1989/agent-manager/issues/27)（含
   [证据评论](https://github.com/gaoyue1989/agent-manager/issues/27#issuecomment-5825384448)）。
   共享实例评测：`--workers 1` 稳定；并发评测等 issue 修复。
3. **HITL 用例不可对共享实例跑**：release-agent 连着真实平台，`auto_confirm` 会真的执行
   确认（如改服务 env）——HITL 用例必须指向专用本地实例。
4. **`--with-gen` 已实测（2026-09-25，since=09a1b5b）**：3 模块 × 2 条 = 6 候选，6/6 过
   闸门与冒烟，并入当轮评测（10/11 PASS）。质量：两条直接命中 diff 变更功能（不存在模型
   回退 → judge 0.00 抓到语义不符：Agent 拒绝切换而非回退，疑似 model 字段未透传或
   Agent 保守，需人工核；config 泄露拦截 → 1.00）；1 条预期写错（delete_service 用例假设
   error 帧而非 permission_ask + 用了不存在的服务名）——正是 PR 人审闸门要拦的形态。
5. **后台运行需 `PYTHONUNBUFFERED=1`**：stdout 块缓冲导致日志文件实时不可见。
6. **内置工具恒可用**仅在 release-agent 验证；`/tools?includeInternal` 与运行时注册集失真已立 [issue #28](https://github.com/gaoyue1989/agent-manager/issues/28)（含修复方案）。
7. 联机评测（`run` / `verify`）的 CI 集成未做，需真实 LLM 与共享实例，当前仅本地可跑；**离线自检已进门禁**——`eval-selftest` job 随 agent-framework 变更执行 `flywheel.py selftest`（零网络、零 LLM、<1min）。
8. eval-test 的 agent_fs 记忆可能跨轮累积（会话已清理但记忆 flush 未验证），列为观察项。

## 已实测可用的链路（2026-09-25，真实 LLM）

- `run --repeat 1`（5 用例 5 PASS）、`verify`（转绿判定）、`selftest`、`status`
- `--judge`（mimo-v2.6-flash；超时用例判 0.00、正常回复判分合理、present_url 判 1.00）
- `--rca-llm`（修复 import 后验证：输出指向模块级根因假设与修复方向）
- 增量进度输出、每轨迹即时落盘、finally 兜底会话清理（含崩溃/中断路径）

## 骨架期边界（Phase 2 待办）

- OpenJudge 正式接入（`py-openjudge==0.2.2` + `OpenJudgeMetric` 适配器，钉版本）
- `GeneralEvaluator` 编排换装（当前自研 asyncio 并发 + n_repeat）
- 双视角采集（`/threads/{sid}/subscribe` 服务端回放交叉比对）
- 语义去重（当前仅文本规范化去重）、用例状态机（draft→observing→active）
- `eval-gate` CI job（门禁轨，扩展 e2e）与 nightly workflow（离线 selftest 已由 `eval-selftest` job 覆盖）
- gen 冒烟与 judge 依赖 `EVAL_LLM_*`，未在真实 key 下实测
