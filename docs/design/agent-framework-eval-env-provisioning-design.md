# agent-framework 评测环境按需供给设计（变更驱动 provisioning）

> 配套：[agent-framework-eval-dual-track-design.md](agent-framework-eval-dual-track-design.md)（双轨评测总体设计）
> · [../../agent-framework/bench/eval/README.md](../../agent-framework/bench/eval/README.md)（趋势轨现状）
> 状态：已实施（2026-09-26）。验收轮 `ca9085d..cb2f222`：预检 5 项全绿，手写 12 条用例全 PASS
> （插件/HITL 摘要/模型切换三类由不可测转可测），`run --with-gen 2 --judge --rca-llm` 全流程走通
> （12/15，3 条失败均为生成草稿用例 expected 写错，无 agent 缺陷）；详见 §7 验收标准。

---

## 1. 背景：缺口分析（2026-09-25，基于 ca9085d..HEAD 近 3 天变更实证）

### 1.1 飞轮现状的能力边界

| 组件 | 能做什么 | 边界 |
|---|---|---|
| ① `bench/eval/casegen/generator.py` `analyze_diff` | diff → 粗粒度模块名（tool/mcp/config/…）+ 风险 | 只回答"改了什么模块"，不回答"测它需要什么环境" |
| ② 同文件 `_GENERATE_PROMPT` | LLM 生成对话级候选用例 | 生成词汇表限死"只通过 POST /threads/chat + SSE 帧可测"（generator.py:41），控制面行为无法表达 |
| ③ `bench/eval/executor/` | 对单一 `--base-url` 实例跑用例 + 帧断言 | 无实例生命周期、无环境前置条件、无控制面动作；能力门禁 `requires_tools` 视图缺工具即跳过 |
| 环境 | — | OAF 包、插件、MCP 后端、模型配置全部由被测实例部署时决定，飞轮不可变更（flywheel.py:89 单 base_url） |
| HITL | `hitl_policy=auto_confirm` 可表达 | **共享实例禁跑**（README 已知问题 3：确认会真的执行平台变更），无 mock 后端则整类不可测 |

### 1.2 近 3 天变更（ca9085d..HEAD）× 飞轮可测性对照

| 变更 | 变更点 | 测它需要的环境 | 飞轮现状 |
|---|---|---|---|
| #34 feat/tool-plugin-extension | `tool/ToolPlugin*`（SPI 插件并入 CustomTool） | OAF 包含 `plugins/*.jar` 的最新 jar 实例 | ❌ 不可测（release-agent 包无 plugins/，插件行为不可达） |
| #32 fix(hitl) 恢复流工具摘要 | `service/ToolSummaryGenerator`、`TurnToolSummaryTracker`、`ChatStreamController` | 带 ask 权限 MCP 工具的实例 + confirm 流 | ❌ 不可测（唯一 MCP 指向真实平台；共享实例禁 HITL） |
| #32 fix(reload) 对话 Channel 跟随 Agent 引用 | `config/ChannelConfig`、`ChatUiChannelProvider` | 多 Agent 引用 + reload 场景 | ⚠️ 仅对话层可测，通道切换行为黑盒不可表达 |
| 区间内 oaf-dynamic-reload | `config/*`、`AdminReloadController` | 可改 OAF 配置 + `POST /admin/reload?scope=agent` | ❌ 不可测（executor 无控制面动作） |
| 区间内会话模型切换 | `ChatRequest.model`（api.md:335） | 对话请求透传 model 参数 | ⚠️ 用例 input 无 model 字段，趋势轨表达不了 |
| e2e / bench / docs | 测试与文档 | — | 不适用（门禁轨/自测范围） |

**结论**：近期变更的大头（工具插件、HITL 摘要、reload/通道、模型切换）都要求"被测 OAF 包 + 插件 + MCP 后端"随变更重新配置；飞轮只会对既定实例跑对话级用例，上述覆盖面缺口成立。

### 1.3 根因

飞轮把被测环境当外部既定输入（`--base-url`），缺少**环境即产物（env as artifact）**的供给环节：

1. 用例词汇表只有对话动作，无环境前置条件与控制面动作；
2. 变更分析只产出模块名，不产出环境需求；
3. 无 mock 服务编排——外部依赖（platform MCP）要么真实接入（危险）要么整类用例静默跳过。

---

## 2. 目标 / 非目标

**目标**

- **G1 变更→环境需求**：diff 分析升级为结构化 `env_spec`（需要哪些插件 / mock 服务 / 探针）
- **G2 按需组装 OAF 包**：base 模板 + 变更覆盖层确定性组装 `agent-config/`（plugins/ + mcp-configs/ + AGENTS.md），manifest 留痕
- **G3 mock MCP 服务**：按 spec 起本地 mock（allow/ask 工具集，streamableHttp），支撑工具调用与 HITL 确认流评测
- **G4 供给与生命周期**：infra（MySQL/Redis）+ 实例启动 + 就绪与**契约预检** + teardown，一条命令供给
- **G5 用例扩展 `requires_env`**：环境前置条件门禁（不满足 → SKIP，与 `requires_tools` 同语义）
- **G6 以 ca9085d..HEAD 一轮评测验收**：§1.2 的 ❌/⚠️ 项全部变为可测

**非目标**

- 不改产品代码行为（全部改动落在 `bench/eval/` 评测侧）
- 不替代门禁轨 e2e；UI 浏览器层（通道切换的 chat UI 面）归门禁轨 `ui.spec`
- 不做沙箱联动（本地评测 `SANDBOX_ENABLED=false`；沙箱缺陷跟踪 issue #27）
- 不自动入库：用例（含生成与手写）仍以 draft 进 reports、入库走 PR 人审；provision 产物只进 `reports/` 与 gitignored 运行目录

---

## 3. 总体设计

六步闭环插入 **①.5 环境供给**：

```
代码变更 ──①──> 变更分析 ──①.5──> 环境供给 ──②──> 用例生成 ──③──> 评测执行 ──④──> 根因报告 ──⑤──> 人工确认 ──⑥──> 修复回归
              (diff→模块+env_spec)  (OAF组装+mock+实例)   (LLM+闸门)    (轨迹+断言)   (规则+LLM)    (PR 人审)     (verify 转绿)
```

模块落位（`agent-framework/bench/eval/`，产品代码零改动）：

```
bench/eval/
├── provision/                  # ①.5 环境供给（新增）
│   ├── needs.py                # 变更→EnvNeeds 规则（路径前缀 + diff 关键词）
│   ├── oaf.py                  # OAF 包组装器（base + 覆盖层，确定性，manifest 留痕）
│   ├── instance.py             # infra + 实例生命周期 + 契约预检
│   └── templates/eval-agent/   # 最小 OAF base 模板
├── mock/
│   └── mcp_server.py           # mock MCP 服务（streamableHttp 子集，工具目录 JSON 驱动）
├── casegen/ executor/ analyzer/ graders/   # 既有；executor 仅加 model 透传与 requires_env
├── cases/                      # 用例库（入库走 PR）
└── flywheel.py                 # 新增 provision 子命令 + run --provision
```

---

## 4. 关键组件设计

### 4.1 `provision/needs.py`——变更→环境需求（G1）

复用 `analyze_diff` 的 diff 采集，在模块映射之上产出 `env_spec`：

```json
{
  "since": "ca9085d",
  "modules": {"tool": ["src/.../ToolPluginBootstrapper.java"], "service": ["..."]},
  "env_needs": {
    "plugins": [{"name": "echo-tool", "src": "e2e/plugin-echo", "reason": "tool/ 变更"}],
    "mock_mcp": {"tools": ["echo_query", "list_services", "publish_service"],
                 "ask_tools": ["publish_service"]},
    "reload_probe": true,
    "session_model_probe": true
  }
}
```

规则表（常量集中文件顶部，与 `_MODULE_RULES` 同风格，随变更演进由 PR 补充）：

| 触发条件（路径前缀 / diff 内容关键词） | env 需求 | 供给动作 |
|---|---|---|
| `src/main/java/.../tool/**` | plugins | 编译 `e2e/plugin-echo`（`--plugin-src` 可覆盖）→ `{config}/plugins/` |
| `src/main/java/.../mcp/**` | mock_mcp（allow 工具） | 起 mock MCP，AGENTS.md `mcpServers` 指向 |
| diff 含 `permission_ask` / `confirm` / `ToolSummary` | mock_mcp（增设 ask 工具） | ask 工具非只读注册 → 触发确认流 |
| `src/main/java/.../config/**`、`AdminReloadController` | reload_probe | 预检执行 reload 回路 |
| diff 含 `ChatRequest` / `model`（会话模型语义） | session_model_probe | 预检 model 透传探针 |
| 其余 `src/**` | plain_dialog | 标准对话用例即可 |
| `e2e/`、`bench/`、`docs/` | — | 无（门禁轨/自测） |

### 4.2 `provision/oaf.py`——OAF 包组装器（G2）

三层组装（base 模板 → 变更覆盖层 → 生成配置），输出固定结构 + manifest：

```
{oaf_out}/agent-config/
├── AGENTS.md                      # base + 覆盖层合并（mcpServers/deniedTools/config.permission）
├── mcp-configs/eval-mock/config.yaml   # 生成：url→mock 地址、permissions.tools allow|ask
├── plugins/echo-tool.jar               # 按 spec 编译（plugin-smoke.sh 配方）
├── plugins/echo-tool/config.yaml       # 插件配置（可选，支持 ${ENV} 替换）
└── oaf-manifest.json            # 留痕：各层来源、动机 diff 文件、内容 hash、组装时间
```

- base 来源：`templates/eval-agent/`（最小包）或 `--oaf-base release-agent/`（对齐真实发布助手能力）
- 确定性：同 spec 同输入 → 同输出（文件内容不含时间戳，时间戳只进 manifest），产物可 diff 对比
- 插件 jar 构建复用 `e2e/scripts/plugin-smoke.sh` 配方（javac `target/classes` + agentscope-core → `META-INF/services` → jar）

### 4.3 `mock/mcp_server.py`——mock MCP 服务（G3）

- 形态：Python 标准库单文件（零第三方依赖），`POST /mcp` JSON-RPC 应答
  （`initialize` / `notifications/initialized` / `tools/list` / `tools/call`），`GET /mcp` 探活
- 工具目录 JSON 驱动（启动参数指定），handler 三类：
  - `echo`：回显参数（确定性断言）
  - `fail`：返回工具错误（错误路径断言）
  - `canned`：固定 JSON 载荷（模拟 platform 域工具，如 `list_services` 返回夹具）
- 权限建模：目录内 `permission: allow|ask`；ask 工具注册为非只读 → 框架发 `permission_ask` 帧 → HITL 确认流
- 请求日志 `mock-mcp.jsonl`（供 RCA 对照与报告佐证）
- 兼容性风险与兜底见 §6.1

### 4.4 `provision/instance.py`——供给与生命周期（G4）

1. `ensure_infra()`：MySQL:3307 + Redis:16379（复用 `e2e/scripts/local-infra.sh`；缺失时 docker 直起并初始化 `agent_manager_test` 库 / `agent_manager` 用户）
2. `start_instance(env_spec)`：`java -jar`（无 JDK 时经 maven 容器），env 按部署文档 §4：`LLM_*`（`EVAL_TARGET_LLM_*`）、`CHECKPOINT_*`、`AGENT_REDIS_URL`、`AGENT_CONFIG_DIR`=组装产物、插件目录缺省回落 `{config}/plugins`、`SANDBOX_ENABLED=false`、`FILE_STORAGE_TYPE=local`
3. 就绪探测：`GET /health` 轮询至超时
4. **契约预检（preflight）**——供给是否到位在跑用例前暴露，不产出假 FAIL：
   - plugins：启动日志 `Tool plugin [x] registered tools:` + `/tools?includeInternal=true` 含插件工具
   - mock_mcp：`/tools` 含 mock 工具（MCP 注册 fail-soft，缺即供给失败）
   - reload_probe：改 marker → `POST /admin/reload?scope=agent` → 重建后工具仍在（plugin-smoke.sh 断言集移植）
   - session_model_probe：`GET /models` 可用 + 对话带 `model` 无 error 帧
   - 结果落 `reports/{task}/preflight.json`
5. `teardown()`：停实例与 mock（infra 容器保留复用）；`--no-teardown` 调试用

### 4.5 CLI 与流程接入

```bash
python3 bench/eval/flywheel.py provision --since ca9085d [--oaf-base DIR] [--plugin-src DIR] [--keep]
python3 bench/eval/flywheel.py run --provision --since ca9085d --repeat 1 --with-gen 2 --judge --rca-llm
```

- `provision`：①+①.5 独立可用（组装 + 起服务 + 预检，打印 base-url 供人工调试）
- `run --provision`：provision → 原 run 全流程（② 的 capabilities 取自供给后实例，天然含插件/mock 工具）→ teardown
- 退出码不变（0 全过 / 1 有失败 / 2 无可执行用例或环境）；**preflight 硬失败 → 退出 2**（环境问题不伪装成用例失败）

### 4.6 用例格式扩展（G5）与 executor 小改

- 用例新增可选 `requires_env: ["plugin:echo-tool", "mock_mcp:ask", "reload"]`：env_spec 不满足 → SKIP（与 `requires_tools` 同语义，报告单列）
- executor 的 `input.model` 透传已存在（runner.py `execute_once`，`/threads/chat` body model 字段；语义 api.md:335：未知模型 → error 帧 `unknown_model`），会话模型切换用例可直接表达，本设计无需改 executor 请求面
- 断言词汇表不动（frames / tool_calls / tool_result / final_text_* / error_*）：控制面动作放 preflight 而非用例 setup，维持"用例=纯对话黑盒"原则（决策见 §6.3）

---

## 5. 与飞轮既有原则的兼容

| 原则 | 本设计的处理 |
|---|---|
| ⑤ 人审硬闸门 | 不变：生成/手写用例以 draft 进 reports、入库走 PR；provision 产物不入库 |
| judge 只参考、永不阻断 | 不变 |
| 只有确定性断言可阻断 | preflight 与用例断言同为确定性检查；preflight 失败=退出 2 |
| 趋势可比性 | `history.jsonl` 增记 `provision` 与 `env_spec.hash`；供给方式变化时趋势断代有据可查 |

---

## 6. 风险与边界

1. **java-sdk streamableHttp 兼容性**：手写 mock 的应答方言（JSON vs SSE、协议版本协商）可能与 modelcontextprotocol java-sdk 不合。缓解：`initialize` 握手即为预检项，失败当场暴露；兜底改用 node `@modelcontextprotocol/sdk` 重写 mock（对上接口不变）。
2. **插件 jar 构建依赖**：需要 `target/classes` + agentscope-core jar（plugin-smoke.sh 配方）。缓解：provision 前置检查产物存在，缺失明确报错提示先构建。
3. **mock ≠ 真实平台**：mock 下 HITL/工具用例验证的是**框架行为**（帧序、工具摘要、确认流、错误处理），不验证平台业务正确性。用例 `ground_truth` 标注 `scope: framework`；平台语义回归仍靠共享实例安全用例与门禁轨。
4. **模型随机性**：mock LLM 确定性执行留待与门禁轨共用 `e2e/mock/llm-server.mjs`（非本期）；本期仍真实 LLM + `--repeat`。
5. **环境依赖**：docker（MySQL/Redis）、JDK 或 maven 容器；先支持 Linux 本地，CI 接入（eval-gate job）另立任务。
6. **通道切换（PR #32）**：趋势轨只覆盖 reload 后通道引用一致性预检；chat UI 面归门禁轨 `ui.spec`，不重复建设。

---

## 7. 验收标准（以 ca9085d..HEAD 一轮评测为准）

| # | 验收项 | 判定 |
|---|---|---|
| A1 | `provision --since ca9085d` 产出 env_spec / oaf-manifest / preflight 三件套且预检全绿 | 文件存在且 preflight 无 fail |
| A2 | 插件变更可测：≥1 条对话用例调用插件工具并断言 `tool_result.ok` | 对比现状 0 条 |
| A3 | HITL 摘要变更可测：≥1 条 ask 工具用例走 `permission_ask` → confirm-stream → 摘要断言 | 对比现状禁跑 |
| A4 | reload 变更可测：preflight reload 回路通过 + reload 后对话用例仍绿 | preflight `reload_probe=pass` |
| A5 | 会话模型切换可测：`input.model` 透传用例（含 `unknown_model` 负例）在趋势轨执行 | trace 含 error 帧断言 |
| A6 | `run --provision --with-gen 2 --judge --rca-llm` 全流程走通 | report.md 完整，含新增用例与 mock 日志佐证 |

## 8. 实施步骤

1. `mock/mcp_server.py` + 本地实例 `initialize` 握手验证（兼容性预检）
2. `provision/needs.py` + `oaf.py` + `instance.py`
3. `flywheel.py` 接 `provision` 子命令 / `run --provision`；executor 加 `input.model` 透传与 `requires_env` 门禁
4. 补覆盖用例（插件调用 / HITL 摘要 / reload 后一致性 / model 切换），draft 入 reports 供 PR 人审
5. 按 §7 跑验收轮；修复回归以 `verify` 转绿为准

## 附录 A：与双轨设计的衔接

provision 产出的确定性环境（OAF 包 + mock 服务 + 预检协议）后续可直接被门禁轨 eval-gate job 复用：
门禁轨只需要 mock LLM（e2e 已有）+ 本设计的供给能力，即可在 CI 内以确定性断言阻断——两轨共享"环境即产物"，避免各搭一套。
