# MCP 工具结果直返（direct_return）设计

- 日期：2026-11-23
- 状态：**已实施**（[issue #454](https://github.com/gaoyue1989/agent-manager/issues/454)）
- 真相源：配置契约见 [../oaf-specification.md](../oaf-specification.md)「config.yaml Format」，SSE 词表与 history 字段见 [agent-framework/docs/api.md](../../agent-framework/docs/api.md)，运行时要点见 [agent-framework/AGENTS.md](../../agent-framework/AGENTS.md)「McpToolRegistrar」节。本文只记录决策、依据与边界。

## 1. 需求

对某个 MCP 工具做配置标记。模型调用该工具并执行成功后：

1. 返回内容**原样**送达前端，不经模型转述；
2. **本轮立即结束**，不发起下一次模型推理。

用途是前端的信息渲染 / 确认类交互。

## 2. 核心决策

### 2.1 用 SDK 内建 `returnDirect`，不自写中间件

issue #454 原提案是新增 `ToolDirectReturnMiddleware`：onActing 捕获结果，下一次推理前合成响应跳过模型调用。核对 SDK 源码（agentscope-core 2.0.4）后改用 SDK 已有的同一能力：

| 事实 | 出处 |
|------|------|
| ReActAgent 内建 `returnDirect`：工具成功后短路循环。上下文 tool_result 写占位句；合成收尾 assistant 消息（content = 工具输出块，`generateReason=TOOL_RETURN_DIRECT`）；发带 `generate_reason=TOOL_RETURN_DIRECT` metadata 的 TextBlock 事件；随后 `AGENT_END`，不再调模型 | `ReActAgent.acting()` → `finalizeReturnDirect()` |
| 短路条件：本步**全部**工具调用都是 returnDirect 且 SUCCESS | `ReActAgent.acting()` |
| `McpTool` 构造器不暴露 returnDirect，`ToolBase.isReturnDirect()` 为 `final`；2.0.5 构造器签名未变，升级不解决 | `McpTool` / `ToolBase` |
| 权限引擎对**不继承 `ToolBase` 的 `AgentTool` 一律放行** | `ReActAgent.evaluateOne()` |
| issue 提议的机制可行：`onReasoning` 中间件不调 `next` 直接发文本事件，SDK 会据此拼出收尾消息 | `ReActAgent.reasoning()` |

自写中间件与 SDK 原生能力的差别：

| 维度 | 采用：SDK `returnDirect` + 装饰器 | 否决：自写中间件 |
|------|------|------|
| 自写的循环干预逻辑 | 无，装饰器只打开一个标志 | 跨阶段传参 + 合成文本事件 |
| 模型上下文里的载荷 | 一份（工具结果写占位句） | 两份（工具结果原样 + 合成消息） |
| history 能否识别直返消息 | 能，SDK 写入 `TOOL_RETURN_DIRECT` | 不能，合成的是普通 assistant 消息 |
| 同批混合调用 | **不短路**，整批回喂模型 | 短路 |
| 协议面 | SDK 原生，各协议面一致 | 需逐一核对空完成恢复、日志、追踪等钩子 |

唯一代价是「直返工具与普通工具同一步调用时不终止本轮」。这是 SDK 的既定语义，由用户在方案评审时明确接受。

### 2.2 装饰器必须继承 `ToolBase` 并委托权限判定

朴素装饰器会让工具绕过 deny / ask 规则（与本项目 D6 缺陷同型）。`DirectReturnMcpTool` 继承 `ToolBase`，`callAsync` / `checkPermissions` / `getOutputSchema` 全部委托原 `McpTool`，只多出 `returnDirect(true)`。执行、多租户注入、共享句柄换芯都沿用 `McpTool` 实现。

### 2.3 配置不另设缓存

issue 提案与初版设计都打算新增 `directReturnTools` 缓存。实际落地以 `ToolInfo.directReturn` 为唯一事实源：`registerOne` 每次重读 config.yaml 并重建 `registeredTools`，`clearServer` 随之清理，OAF reload 语义自然成立，不需要第二份派生副本。该语义覆盖 SSE 标记面；per-session agent 隔离时的会话级执行面由 `OafReloadService` 在 A/B 路径 reload 成功后对 `SessionAgentCache` 世代失效（issue #491），惰性重建经 `registerOne` 重读 config.yaml 收敛。

### 2.4 SSE 只增标记，不增帧

工具结果原文已经按 `toolCallId` 逐字存在于 `TOOL_RESULT_TEXT_DELTA`（已落事件流、可续传回放、无截断）。再造一个带全文的合成帧意味着服务端全文缓冲、上限截断和事件流存储翻倍。`file_ready` 需要合成帧是因为文件元数据本就不在流里，这里不成立。

## 3. 前端契约

| 位置 | 字段 | 含义 |
|------|------|------|
| `TOOL_CALL_START` | `directReturn: true` | 该工具配置了直返 |
| `TOOL_RESULT_TEXT_DELTA` | 无变更 | 原样内容，按 `toolCallId` 拼接 |
| `TEXT_BLOCK_DELTA` | `generateReason: "TOOL_RETURN_DIRECT"` | **本轮以直返结束的权威信号**，只有真的短路才出现；多块时块间以 `\n` 分隔（与 /history 拼接一致，issue #494） |
| history assistant 消息 | `generate_reason: "TOOL_RETURN_DIRECT"` | 收尾消息，`content` 即原样内容；纯图片直返 `content` 为空、图片经 `blocks[]` 的 `image` 块下发 |

判定规则：

1. `TOOL_CALL_START.directReturn` → 标记该步骤并累积它的 `TOOL_RESULT_TEXT_DELTA`。
2. 出现 `generateReason: "TOOL_RETURN_DIRECT"` 的文本 → 本轮以直返结束：渲染同一批次直返步骤的内容，跳过这段文本。
3. 没出现该标记 → 本轮没走直返（同批混合或失败），按普通工具步骤展示。

**纯图片直返的信号补发（issue #492）**：SDK 的收尾文本事件只遍历 `TextBlock`（`publishReturnDirectTextEvents`），纯图片直返轮不会发出上述信号——与空输出不可区分。框架在短路成立（直返工具成功结束且其后无模型回喂）而 SDK 未发信号时，于 `AGENT_END` 前补一帧 `delta` 为空的合成信号帧（`generateReason` 同值，经合成帧落库、订阅回放与续传一致）；判定规则 2 不变，空 `delta` 对既有前端零影响。

**归属关系（实施中更正）**：初版设计称 `TOOL_CALL_START` 与工具结果共用 `replyId`，实测不成立。`TOOL_CALL_START` 产生于推理阶段，带的是推理 `replyId`；`TOOL_RESULT_*` 与收尾文本产生于执行阶段，三者共用执行 `replyId`。前端用 `toolCallId` 关联调用与结果，用执行 `replyId` 关联结果与收尾文本。同批多个直返调用时，各工具的内容在各自的 `TOOL_RESULT_TEXT_DELTA` 里，不会粘连。

**收尾文本多块分隔（issue #494 更正）**：初版设计约定收尾文本「按序拼接，不带分隔符」，但 SDK 把同批直返结果的所有 `TextBlock` 合成**一条**收尾 assistant 消息，而 /history 对该消息的非空 text 块以 `\n` 拼接（`StateDataParser.extractContentText`）——单工具多 TextContent 或同批多个直返工具都会触发刷新前后内容漂移。框架在事件入口按 /history 口径重写直返 delta（`DirectReturnDeltaJoiner`，两处 chat 流同接线）：非空块之间插 `\n`，blank 块归一为空文本帧，块内文本零改动；重写后落库，实时流、订阅回放、/history 三面一致。

不提供 issue 提到的 `sourceTool` 字段：上述两个 id 已足够归组。

**协议面差异**：Channel `/threads/chat`、`/confirm-stream`、native 续传面下发两个标记；AG-UI 面只有落库的 native 载荷带标记，AG-UI 类型化事件未扩展（与 `ui` 元数据的现状一致）；A2A `message/send` 的返回消息正文即原样内容，metadata 带 SDK 的 `_tool_return_direct`。

**现有前端零改动**：收尾文本本来就按 `TEXT_BLOCK_DELTA` 追加为助手正文，原样内容直接可见。专用确认卡片属于按上述标记做的可选扩展，不在本次范围。

## 4. 语义边界

| 情形 | 行为 |
|------|------|
| 工具失败（`isError` / 异常 / DENIED / 被中断） | 不短路，结果回喂模型 |
| 成功但内容为空 | 装饰器归一化为 ERROR 并告警，回喂模型（避免空直返，也避免空白文本触发循环推理） |
| 直返工具与普通工具同一步调用 | 不短路，整批回喂模型 |
| 同批多个直返调用且全部成功 | 短路，各调用内容按 `toolCallId` 完整下发 |
| 与 HITL `ask` 组合 | 先挂起，批准后执行并直返；恢复流不再调用模型 |
| 声明直返的工具同时是 `app_only` | app_only 优先并告警（对 LLM 隐藏的工具不会被模型调用） |
| 声明的工具未注册（子集关闭 / server 未提供） | 告警，无副作用 |
| `permissions` | 保持正交，直返不隐式放行（直返工具也可能是写操作，如「下单回执」）；演示型工具需自行声明 `allow` |
| 保真范围 | MCP `content` 文本块逐字；图片走 `TOOL_RESULT_DATA_DELTA`，history 侧直返收尾消息保留 `blocks[]` 的 `image` 块（`{type:"image", media_type, data?/url?}`，issue #492）；只返回 `structuredContent` 的工具得到空文本 |
| 上下文留存 | 原样内容作为 assistant 消息留在模型上下文，后续轮模型知道「已向用户展示过什么」；占位 tool_result 不触发大结果卸载，适合表单 / 演示型载荷，不适合大数据量传输 |
| 续接 | 本轮已终止；用户在前端完成确认后，由前端把确认结果作为下一条用户消息发起下一轮，零新增状态 |

## 5. 控制面

- 连接器 `mcp_connectors.direct_return`（JSON `{"tools":[…]}`）。表结构由 GORM `AutoMigrate` 演进，加结构体字段即可；存量行为 NULL，读取为空即「未配置」。issue 原文写的「提供迁移脚本」与本仓库机制不符，已按实际机制落地。
- `validateDirectReturn` 强校验：只接受对象形态，拒绝双重编码、空工具名、重复工具名。
- `mcpConfigYAML` 在 `direct_return.tools` 非空时输出该段，与 `permissions` 段互不混入。
- 「工具管理」页权限表新增「结果直返」列，随「保存策略」把 `permissions` 与 `directReturn` 一并提交；草稿纳入既有脏检测；已声明直返但不在快照 / 权限里的工具也出行，保存不丢。
- 修改连接器的直返配置后，已发布的 Agent 需经编辑（重新生成包）才生效，与 `permissions` 的现状一致。

## 6. 验证

**单元测试**：装饰器委托与标志镜像、经 `PermissionEngine` 的 deny / ask / allow 规则逐条生效、空结果归一化；Registrar 解析、注册分流、冲突裁定、`registerOne` 级路径选择与 reload 双向切换；序列化两种标记且普通帧逐字节不变；三发源地接线；history 透传；`/tools` 标记；连接器校验与持久化、存量 NULL 读取、生成包链路（含清空后重新生成）。

**真实环境**（真实 MCP + 真实 LLM，本地起 agent）：直返成功时模型调用恰 1 次、下发内容与 MCP 载荷逐字节相等（含中文、换行、双空格、哨兵串）；普通工具、失败、空内容、HITL 组合、同批混合、同批多直返、history、下一轮上下文、续传回放、OAF reload 双向切换、A2A 通道共 12 项全部通过。

**E2E**：[agent-framework/docs/e2e-ci-plan.md](../../agent-framework/docs/e2e-ci-plan.md) §5.5b DR1–DR8，6 个真实 LLM 录制夹具（`direct-*.json`）。DR1 以 mock LLM 的调用记录断言「主对话请求恰 1 次」，并逐字节比对 mock 的 `/expected` 原文。

## 7. 实施中处理的工具链问题

- **录制件混入会话标题生成调用**：`SessionTitleService` 的后台调用与主对话并发，且先结束，被录制代理当作场景调用写入夹具；回放时它和主对话请求都是「末条为 user」，会取到同一份 `call[0]`。录制代理与夹具规整脚本的后台过滤已补上标题生成。回放侧的后台判定刻意未改：`S9` 用例依赖「标题请求回放场景的 `call[0]`」这一现状。
- **`direct-submit` 夹具触发敏感信息扫描**：模型对该场景稳定用英文「authorization」推理，被 `check:fixtures` 的宽规则命中。按录制器既有的改写机制在场景声明里删除该词（`record-drive.mjs` 的 `rewrite`），不放宽扫描规则。
- **`env-up.sh` 存量缺陷**：使用非默认 `APPROVAL_MCP_PORT` 时，就绪探测早于端口转发启动，必然超时。已改为探测 python mock 的固定端口。

## 8. 已否决的方案

| 方案 | 否决原因 |
|------|----------|
| 自写 `ToolDirectReturnMiddleware` | 重复实现 SDK 已有能力，上下文存两份载荷，history 无法识别，见 §2.1 |
| 朴素 `AgentTool` 装饰器 | 绕过权限引擎 |
| acting 阶段发 `RequestStopEvent` | 工具结果不落上下文，留下悬空 tool_use |
| 反射修改 `ToolBase.returnDirect` | 掩耳盗铃 |
| 遮蔽 SDK 类（同 `MarketplaceStager` 做法） | `ToolBase` 分叉面过大 |
| 升级 2.0.5 指望原生支持 | `McpTool` 构造器签名未变 |
| `agent.interrupt()` 式强停 | 语义是「用户中断」，会产生恢复话术与 `cancelled` 终态 |
| 新造带全文的合成帧 | 见 §2.4 |
| 注册期对权限为 `ask` 的直返工具告警 | `ask` + 直返是 issue 明确支持的组合；未声明时的默认行为取决于权限模式，告警会误报 |
| 多个直返工具的结果用 `\n\n` 拼接 | 多块以 `\n` 分隔对齐 /history 拼接口径（issue #494，见 §3）；前端按 `toolCallId` 取各自 `TOOL_RESULT_TEXT_DELTA` 内容的展示面不变 |
