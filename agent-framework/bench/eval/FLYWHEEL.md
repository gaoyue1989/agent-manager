# 评测数据飞轮使用指南

> 配套文档：[README.md](README.md)（模块结构与关键事实） · [设计文档](../../../docs/design/agent-framework-eval-dual-track-design.md)（双轨架构与评审依据）
>
> 本文回答三个问题：**飞轮是什么、怎么转（使用方法）、什么时候转（使用时机）**。所有数据与结论均来自 2026-09-25 对 release-agent（真实 LLM）的 55+ 轮实测。

---

## 1. 飞轮是什么：六步闭环

```
代码变更 ──①──> 变更分析 ──②──> 用例生成 ──③──> 评测执行 ──④──> 根因报告 ──⑤──> 人工确认 ──⑥──> 修复回归
   ^              (diff→模块)    (LLM+闸门)   (轨迹+断言)   (规则+LLM)    (PR 人审)     (verify 转绿)
   │                                                                                      │
   └──────────────────── 沉淀：用例库增长 / history.jsonl 趋势 / 失败经验 ◄──────────────────┘
```

每转一圈，三样东西变厚：**用例库**（回归集变大、生成质量因 few-shot 提升）、**趋势账本**（`history.jsonl`，退化/改善可查）、**问题资产**（失败分类与根因积累——首轮就挖出了 #27/#28/#29 三个真实缺陷）。

**飞轮的转动原则**（设计评审决议，勿破坏）：
- ⑤ 人工确认是硬闸门——生成的用例、修复 patch 一律走 PR 人审，不自动入库；
- judge 语义分数**只做参考，永不阻断**；能阻断的只有确定性断言（未来门禁轨）；
- judge 模型一旦更换，趋势不可比（`history.jsonl` 记录了 `judge_model`，换模型时看板断代是预期行为）。

---

## 2. 使用方法

### 2.0 前置条件

| 依赖 | 说明 |
|---|---|
| 被测服务 | 一个运行中的 agent-framework 实例。共享集群目标：`http://127.0.0.1:8911/agent/release-agent`（真实 LLM）；本地专用栈：MySQL :3307 + Redis :16379 + 自选 LLM，`SERVER_PORT=18100` 起 jar |
| Python | ≥3.10，仅需 `httpx`（无其余第三方依赖） |
| `EVAL_LLM_*` 环境变量 | **只有** `--with-gen` / `--judge` / `--rca-llm` 需要（任意 OpenAI 兼容端点）；不配置时这三项自动跳过，其余功能零密钥可用 |

```bash
export EVAL_LLM_BASE_URL=https://.../v1   # 评测裁判模型端点（建议与被测模型区分开并固定）
export EVAL_LLM_API_KEY=...
export EVAL_LLM_MODEL=...                 # 记入 history.jsonl，更换=趋势断代
```

密钥只放环境变量，**不写入任何文件**；后台/重定向运行加 `PYTHONUNBUFFERED=1`。

### 2.1 run——转一圈（①→④）

```bash
cd agent-framework
python3 bench/eval/flywheel.py run \
  --base-url http://127.0.0.1:8911/agent/release-agent \
  --repeat 3 --workers 2 \
  [--since <commit>] [--only <子串>] [--with-gen 2] [--judge] [--rca-llm] [--no-cleanup]
```

| 参数 | 语义 | 备注 |
|---|---|---|
| `--base-url` | 被测服务 | 缺省读 `EVAL_AGENT_BASE_URL`，再缺省 `http://127.0.0.1:8100` |
| `--since` | 变更分析基线 commit | **缺省自动取 `history.jsonl` 最后一行的 commit**——这就是"上次评到哪"的状态机 |
| `--repeat N` | 每用例重复次数 | 稳定性数据用 3；快速回归用 1 |
| `--workers N` | 并发 | 见 §4.1 沙箱约束；共享 release-agent 建议 1（稳）或 2（快，~7% 容忍重试） |
| `--only 子串` | 只跑 case_id 含子串的库内用例 | 调试单用例 |
| `--with-gen N` | 每个变更模块生成 N 条候选用例 | 走四道闸门：字段校验→文本去重→**动态冒烟**→draft 落盘（PR 人审后才能进回归库） |
| `--judge` | 语义打分（advisory） | 对每个用例首条轨迹打 0~1 分，写进报告 |
| `--rca-llm` | 失败项 LLM 深度根因 | 输出模块级根因假设；单项失败只降级不阻断 |
| `--no-cleanup` | 保留评测会话 | 默认自动 `DELETE /threads/{sid}` 清理（失败重试一次，仍失败打 WARN） |

**退出码**：`0` 全过 / `1` 存在失败用例（CI 可用）/ `2` 无可执行用例。

**产物**（`bench/eval/reports/`，gitignored）：

```
reports/
├── history.jsonl                    # 跨轮趋势账本，每 run 一行（commit/通过率/分数/judge_model）
└── {commit8}-trend-{时间戳}/
    ├── task.json                    # 任务元数据 + 失败分析汇总
    ├── summary.jsonl                # 每用例每重复一行摘要（耗时/token/是否 file_ready…）
    ├── report.md                    # 人读报告：明细表 + judge 分 + 问题清单 + 修复建议
    ├── analyze.json                 # ①变更分析结果（模块/风险）
    ├── gen_cases/*.json             # ②生成的 draft 用例（待 PR 人审）
    └── traces/{case}#{n}.json       # ③全量轨迹：原始 SSE 帧 + 结构化视图 + 断言明细
```

### 2.2 verify——修复后回归（⑥）

```bash
python3 bench/eval/flywheel.py verify --base-url ... [--task-id <上轮ID>] [--repeat 1]
```

自动重跑**上一轮失败用例 + core 回归集**，输出每条用例的 `转绿 / 仍失败 / 通过 / 新失败` 判定。`--task-id` 可指定任意历史轮次。修复是否生效以这个命令为准，不以手感为准。

### 2.3 selftest / status

```bash
python3 bench/eval/flywheel.py selftest   # 离线自检：帧映射/检查器/HITL 视图/失败分类（零依赖，改代码后必跑）
python3 bench/eval/flywheel.py status     # 趋势账本一览：每轮通过率与分数曲线
```

### 2.4 用例的增删改

用例即 `cases/*.json` 文件（[格式见 README](README.md#用例格式casesjson入库走-pr-人审)）。**所有变更走 PR**——这是飞轮的人工闸门：生成用例最常见错误是 expected 本身写错（实测 6 条候选错 1 条），人审是质量底线。改 `frame-mapping.json` 或解析代码后先跑 `selftest`。

---

## 3. 使用时机（场景 → 命令 → 预期）

| 时机 | 跑什么 | 为什么 | 实测参考 |
|---|---|---|---|
| **改了 agent-framework 代码，提 PR 前** | `run --repeat 1`（库内用例回归） | 快速确认没破坏既有契约；~6 用例 | workers=2 约 3 分钟，单轮 P50 14.5s |
| **代码合入 master 后** | `run --repeat 1 --with-gen 2 --since <上轮>` | 变更分析定位改动模块，生成针对性候选用例补库 | 3 模块×2 条，含冒烟与评测全程约 8 分钟 |
| **发版 / 更换被测 LLM / 换 judge 前** | `run --repeat 3 --judge` | 拿稳定性数据（方差）+ 语义基线；换 judge 后趋势断代要心里有数 | 15 轮 workers=2 约 6 分钟，token 均值 ~25K/轮 |
| **修完一个评测发现的 bug 后** | `verify` | 转绿判定 + core 回归防退化；这是飞轮闭环的"合拢"动作 | 按失败数定量，1~2 分钟 |
| **集群 rollout 新镜像 / 环境变更后** | `run --repeat 1 --only case_chat_basic`（单点冒烟）再全量 | 环境问题优先暴露（沙箱/LLM 端点） | 单用例 ~10-30s |
| **每周例行** | `status` + 翻最近 report.md 的不稳定用例 | 趋势巡检；3 次重复中 PASS/FAIL 交替的用例按"不稳定"治理（改 expected 或加引导） | 秒级 |

**两个不要**：
- 不要对共享实例跑 `hitl_policy=auto_confirm` 的用例——确认会真的执行（改 env/发布服务）。HITL 用例只在专用本地实例上跑。
- 不要在沙箱故障期间跑 `--with-gen`（候选冒烟会被环境性超时全灭，浪费生成）——先看 release-agent 近期日志有无 `SANDBOX_START_FAILED`。

---

## 4. 约束与排障

### 4.1 workers 怎么选（沙箱约束，实测数据）

| workers | 表现 | 结论 |
|---|---|---|
| 1 | 15/15 PASS，0 失败 | **共享实例默认档** |
| 2 | 14/15，~7% 失败 | 可用（失败是 opensandbox server 残余端口竞态，[#27](https://github.com/gaoyue1989/agent-manager/issues/27) 跟踪），失败重跑即可 |
| ≥3 | 超时率显著上升 | 避免对共享实例使用 |

守卫（`SANDBOX_GUARD_ENABLED`，PR #30 已上线）把同用户调用串行化后，workers=2 从 3/15 失败降到 1/15；残余在 server 内部，等上游修复。

### 4.2 常见问题

| 症状 | 判断 | 处理 |
|---|---|---|
| 大面积 timeout、无 error 帧 | 环境（沙箱/LLM）而非代码 | 查 release-agent 日志 `SandboxApiException`；workers 降到 1 复跑 |
| 用例 FAIL 且证据是 `frames.X 期望 n 实际 0` | 先怀疑 expected 写错（对照 frame-mapping.json 词表），再怀疑真缺陷 | 用 trace 里的原始帧核对；真缺陷走 issue |
| judge 全 0 分 | final_output 为空（超时/断流） | judge 只评文本，先解决执行问题 |
| 后台跑日志不动 | stdout 块缓冲 | `PYTHONUNBUFFERED=1` |
| cleanup WARN 有 sid | turn 租约未释放 | 稍后手动 `DELETE /threads/{sid}` |

### 4.3 成本意识

每次 `run --repeat 3 --judge` ≈ 被测侧 15 次真实对话（token 均值 25K/轮，其中 system prompt 固定 ~14K）+ judge 6 次 + （可选）生成 3 次 + RCA 失败项次数。例行回归用 `--repeat 1` 省一半以上。

---

## 5. 飞轮成效实录（截至 2026-09-25）

首轮运转（55+ 轨迹）产出：发现并修正了评测契约自身 3 处事实错误（`done` 帧方言、内置工具恒可用、token 采集路径）；挖出框架 3 个真实问题并全部落地修复——#27 沙箱并发缺陷（三组对照实验定界 + 官方方案分析 + PR #30 缓解上线）、#28 `/tools` 清单失真、#29 模型切换认知脱节。用例库从 0 到 6 条，趋势账本就位。**这就是飞轮的价值模型：每转一圈，出问题的成本比上一圈更高——因为回归集变厚了。**
