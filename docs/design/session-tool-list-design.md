# 会话级工具列表（POST /threads/chat 传 tools）— 设计文档

**日期**：2026-11-09
**状态**：已实施（单测全绿 1901/0/5；kind 部署验证用 `e2e/session-tools-e2e.sh`，见 §9）
**范围**：agent-framework 运行时；控制面（backend/frontend）零改动
**姊妹篇**：[session-skill-list-design.md](session-skill-list-design.md)（skills 白名单，已实施——本设计与其机制同构、完全对齐）

---

## 0. 可行性评估结论（先答问题）

**可行，SDK 侧无阻碍，无需升级或魔改依赖。** 依据（agentscope-core/harness 2.0.3 反编译实证）：

| # | 证据 | 结论 |
|---|------|------|
| 1 | `MiddlewareBase.onReasoning(agent, ctx, ReasoningInput, next)`，其中 `ReasoningInput` 含 `tools: List<ToolSchema>` 字段（record，可构造新实例传 `next`） | **per-call 改写模型可见工具 schema 完全可行**——白名单外工具根本不进发给 LLM 的请求（主防线） |
| 2 | `ProtocolDenyRulesMiddleware`（已上线）在 `onActing` 层按 RuntimeContext 拦截工具调用、合成 `DENIED` 工具结果回模型 | **acting 层兜底拦截有同构先例**，防模型幻觉调用白名单外工具（次防线） |
| 3 | skills 白名单全链路（入口写消息 metadata → 中间件读最后一条 USER 消息 → per-call 生效不落库）已实施 + 部署验证 | **传递机制复用已验证链路**，多副本无状态差异、HITL 恢复轮自然延续 |
| 4 | `ToolBase.isMcp()` 存在且 McpToolRegistrar 两条注册路径**同一登记点**（裸名 ToolInfo）；平台侧 `customTools` bean 集（自定义全集，build 期 `toolToolNames()` 已算出）注册期已知 | **「内置 / MCP / 自定义」三分类可确定性判定**（MCP 取登记集、自定义正向枚举、其余为内置——§11 实施口径），接口作用域限定有可靠依据 |
| 5 | `Toolkit` 为共享单例，但中间件过滤不触碰 Toolkit 本身（只改写本轮 `ReasoningInput`） | 只读过滤、不动共享状态，并发会话安全 |

**作用域约束（本设计核心边界）**：工具分三类，关闭/收敛途径严格分层——

| 类别 | 运行时判定 | 会话级接口（`tools` 字段） | 包级静态配置 |
|------|-----------|---------------------------|--------------|
| Harness 内置（`read_file`/`execute`/`plan_*`/`agent_spawn`/`memory_*`/`load_skill_through_path`…） | Toolkit 注册集 − MCP − 自定义 | **❌ 不受影响，恒可见** | ✅ frontmatter `deniedTools` → workspace `tools.json` → Harness `ToolFilter.apply` 摘除（既有链路，本设计不动） |
| MCP（Toolkit 注册名 = 远端**裸名**；`mcp__{server}__{tool}` 为展示名） | McpToolRegistrar 登记集派生（非 app_only 裸名；两条注册路径同一登记点，§11） | ✅ 白名单收敛 | ✅ `ActiveMCP.json` selectedTools（既有） |
| 自定义（平台 `CustomTool` bean + ToolPlugin 插件，裸名） | build 期 `toolToolNames(customTools)` 正向枚举 | ✅ 白名单收敛 | ✅ `deniedTools` 类粒度剔除（既有） |

理由：内置工具是框架基础能力（文件操作、Plan Mode、技能加载、子 Agent 派生、记忆/会话检索），
误剔会静默破坏框架行为且调用方难以察觉；MCP/自定义才是业务面工具，也正是 token 膨胀
（MCP server 多时 schema 线性增长）与误用风险的主体——**会话级收敛只作用于业务面**。

---

## 1. 背景与目标

一个服务的工具集在 build 期注册进共享 `Toolkit`，对所有会话、每一轮全量可见。当一个 Agent
同时承载多条业务线（客服只该用工单 MCP 工具、审核只该用内容 MCP 工具）时：

1. **上下文膨胀**：全量 MCP/自定义工具 schema 进每轮请求；
2. **误用**：模型可能调用不属于当前职责的业务工具；
3. **无法按请求收敛**：现有过滤全部是**包级静态**（`deniedTools`/`ActiveMCP.json`/`app_only`），
   没有会话/轮次粒度入口。

本设计给 **`POST /threads/chat` 增加 `tools` 字段**：调用方声明本轮可用工具列表，运行时把
模型侧 **MCP + 自定义** 工具可见集收敛为该列表（内置工具恒保留）。**仅对本轮生效**（不落库、
不跨轮沿用），控制面零改动，与 skills 白名单正交组合。

### 1.1 目标

- `POST /threads/chat {tools:[...]}` 与 A2A `message.metadata.tools` 两处入口同口径；
- 作用域严格限定 **MCP + 自定义**：白名单命中与兜底拦截都只针对这两类，内置工具不受接口影响；
- 模型侧每轮 reasoning 的工具 schema 按 `内置全量 ∪ (MCP/自定义 ∩ 白名单)` 收敛；
  白名单外 **MCP/自定义** 调用在 acting 层兜底 DENY（防幻觉）；
- 缺省行为与存量完全一致；包级静态过滤仍然优先（build 期已摘除的工具不在已注册集内）；
- 多副本/重启无状态差异（列表随本轮消息传递，不落任何存储）。

### 1.2 非目标

- 不做角色/授权模型：列表是上下文边界，不是安全边界；能调用 chat 的调用方可以传任意工具名；
- 不做跨轮记忆：本轮未传即恢复全部（需要固定清单的调用方每轮都传）；
- **不提供内置工具的会话级开关**：内置工具启停只走包级配置（`deniedTools`，见 §0 分层表）；
- 不做 skills / 子 Agent 声明过滤（skills 已有独立入口）；
- 不做控制面 UI（前端/发布向导不感知该字段，属可选高级用法）；
- 不做 AG-UI `POST /threads/{sid}/runs` 入口（与 skills 同口径，`AguiRunController` 维持现状）。

---

## 2. 契约

### 2.1 语义（唯一权威表）

| 请求值 | 模型侧可见工具 | 说明 |
|--------|----------------|------|
| 字段缺省 / `null` | 全部已注册工具 | 存量行为逐字节不变（零开销直通） |
| `[]`（显式空数组） | 仅内置工具 | MCP/自定义全部不可见；内置恒保留（agent 基础能力不受影响） |
| `["query","get_order"]` | 内置全量 + 名单内 MCP/自定义 | 未知名忽略；**内置名写入名单 = WARN 且不改变其可见性**（见下） |

- **仅对本轮生效**：不写 `session_user`、不写消息历史附加状态；下一轮未传即恢复全部。
- **未知名忽略**：不校验、不报错；匹配不到任何已注册 MCP/自定义工具的名字自然无效。
- **内置名显式 WARN**：名单出现内置工具名（如 `read_file`）时打 WARN 明示"内置工具不受
  会话级 tools 控制"——它本来恒可见，静默忽略会让 `tools:["read_file"]` 的调用方误以为
  收敛到了单一工具（实际效果是 MCP/自定义全剔除）。
- **工具名匹配口径**（两级别名解析，仅在 MCP + 自定义集内生效）——命名事实依据：
  **MCP 工具以远端裸名注册进 Toolkit**（`McpToolRegistrar.registerFiltered`："使用远端
  裸名注册，确保 callAsync 正确执行"），模型侧 schema 与调用名均为裸名；
  `mcp__{server}__{tool}` 仅为视图展示名（`ToolInfo.displayName`）：
  1. **裸名精确匹配**（主口径，= Toolkit 注册名 / `ToolSchema.name()`）——MCP 为远端
     工具名（如 `query`），自定义为 `@Tool` 注解 name 或方法名（如 `get_order`）；
  2. **`mcp__{server}__{tool}` 全名别名**（易用性扩展）——经 `McpToolRegistrar` 的
     displayName → 裸名映射解析后按 1 匹配；该工具未被注册（被覆盖/静态摘除）则无效忽略。
- **跨 server 同名**：Toolkit 注册为**覆盖语义**（后注册者胜，先天的、与白名单无关）——
  名单写裸名匹配的就是当前生效的那个工具，不存在白名单层面的歧义分支；全名指向的若非
  当前生效注册（被后注册同名覆盖）则自然无效。
- **静态过滤优先**：`deniedTools`/`ActiveMCP.json`/`app_only` 在 build 期摘除的工具不在
  已注册集内，写进名单也匹配不到（自然忽略）。

### 2.2 API

```jsonc
// POST /threads/chat（/agent/{name}/threads/chat 等价）
{
  "message": "帮我查一下订单 O-123",
  "sessionId": "abc-123",
  "skills": ["order-query"],            // 与 skills 正交，可同时传
  "tools": ["query", "get_order"]       // 可选；缺省=全部，[]=仅内置。
                                        // MCP 可传裸名（主口径）或全名 "mcp__db__query"（别名）
}
```

A2A `message/send` / `message/stream`：

```jsonc
{"jsonrpc":"2.0","id":1,"method":"message/send","params":{
  "message":{"role":"user","parts":[{"kind":"text","text":"hi"}],
    "metadata":{"sessionId":"abc","tools":["query"]}}}}
```

兼容与归一（`normalizeMessageSendBody`，与 skills 同段平行扩展）：取值源链
first-non-null-wins——`message.metadata.tools` → `params.tools` → `params.metadata.tools`
（旧格式同义），归一为 SDK A2A→Msg 转换唯一消费的分组形态
`message.metadata[<msgId>].tools` + `parts[*].metadata._agentscope_msg_id` 标组。

只读视图（闭环查询，§5）：

| 端点 | 参数 |
|------|------|
| `GET /tools` | `?tools=query,get_order`（**只裁剪 MCP + 自定义段**，与模型侧同口径；内置段不受影响；缺省/空串 = 不过滤；裸名/全名均可） |

> 查询参数无法表达"显式空列表"（`?tools=` 视为缺省）；"空 = 仅内置"仅存在于 chat 请求体与
> A2A metadata（与 skills 完全同约束）。

---

## 3. 传递链路

```
调用方
  │ POST /threads/chat {tools}            A2A message.metadata.tools
  ▼
入口（ChatStreamController / SDK A2A server）
  │ tools → 本轮用户消息 metadata["tools"] = List<String>（显式空列表也写入）
  ▼
Agent turn（每轮 reasoning / acting）
  │ ToolFilterMiddleware（新，单中间件双防线）
  │   ├─ onReasoning：读最后一条 USER 消息 metadata["tools"]
  │   │    ├─ 无该键 → 直通（全部工具，存量行为）
  │   │    ├─ 内置 schema → 恒保留（三分类判定，§4）
  │   │    ├─ 空列表 → MCP/自定义全部剔除（结果 = 仅内置）
  │   │    └─ 非空  → MCP/自定义 ∩ 名单（两级名匹配）
  │   └─ onActing：白名单外的 MCP/自定义 ToolUseBlock 不执行 → 合成 DENIED 结果回模型
  │              （ProtocolDenyRulesMiddleware 同构；防线仅在当轮 metadata 有 "tools" 键时启用）
  ▼
LLM 请求（tools 字段已收敛）→ 模型始终可见全部内置 + 白名单内业务工具
```

设计要点：

- **为什么走消息 metadata**（与 skills 同决策）：中间件拿不到 HTTP 请求对象，但
  `ReasoningInput.messages()`/`AgentInput.msgs()` 携带本轮用户消息；列表随消息天然"仅本轮"，
  不引入进程内缓存/共享存储，无多副本一致性问题。
- **为什么 onReasoning 改写 schema 是主防线**：白名单外工具不进 LLM 请求——省 token、
  模型不会尝试调用（对比纯 acting deny：模型看得到工具、调用后失败，浪费轮次且行为混淆）。
  ReAct 多轮循环中每次 reasoning 都过本中间件，多轮一致性天然成立。
- **为什么 acting 还要兜底**：模型可能对白名单外 MCP/自定义工具产生幻觉调用（历史上下文里
  见过该工具名）。acting 兜底保证此类调用不执行、以 DENIED 结果显式回模型（复用
  `ProtocolDenyRulesMiddleware` 已验证的合成事件/结果注入模式，SSE 流与模型上下文双通道完整）。
  **内置工具调用永不触发该防线**。
- **只认最后一条 USER 消息**：与 skills 同——历史消息同名键不参与本轮判定；HITL 恢复轮
  （confirm/AG-UI resume）不产生新 USER 消息，最后一条仍是原轮带名单的消息，
  **白名单跨确认轮自然延续，confirm 链路零改动**。

---

## 4. ToolFilterMiddleware

### 4.1 三分类判定（MCP 取登记集，自定义正向枚举，内置兜底）

- **MCP**：`McpToolRegistrar` 登记集派生（`getSessionControllableToolNames()` =
  非 app_only 裸名集）——两条注册路径（SDK 标准 `registerMcpClient` / 手动
  `registerFiltered`）同一登记点，裸名 = Toolkit 注册名；`clearServer`/reload 即时反映；
  与 Toolkit 覆盖注册语义一致（同名后注册者胜者即登记的当前工具，§11 注：实施时从
  原设计的运行时 `Toolkit.getTool → isMcp()` 反查改为登记集派生，行为等价、免去
  每轮 Toolkit 查找）；
- **自定义**：build 期 `toolToolNames(customTools)` 正向枚举的名字集合（含 ToolPlugin
  插件注册集——插件工具同为 `CustomTool` 收集器暴露，注册点唯一），构造参数注入中间件；
- **内置 = 其余**：既非 MCP 又不在自定义名单者（含 SDK 升级漂移新增的内置工具如
  `web_fetch`/`web_search`——默认归内置即**不受接口影响**，方向安全：宁可不收窄，
  不误伤框架能力）。

> 全名别名解析依赖 `McpToolRegistrar` 的 `ToolInfo`（displayName → 裸名/server 映射，
> `registerOne` 登记点已有数据，reload 复用同一登记）——注入中间件作只读映射。
> OAF reload（MCP 增删）后 `HarnessAgentFactory.build` 重建 agent 时重新构造中间件，
> 自定义名单随之刷新（与 `SkillDisabledFilterMiddleware` 依赖注入同模式）。

### 4.2 中间件逻辑

```
onReasoning:
  if ctx == null || input == null → next
  lastUser = input.messages() 中最后一条 role=USER 的消息
  if lastUser == null || metadata 无 "tools" → next            // 缺省 = 全部
  names = 归一（去空白/去重）
  schemas = input.tools()（null/空 → 直通）
  kept  = schemas 中「内置名」恒保留
        + schemas 中「MCP/自定义名」∈ 名单解析集（两级匹配，§2.1；内置名入名单仅 WARN）
  next.apply(input.withTools(kept))                            // 全保留时原引用直传（零拷贝）

onActing:
  if 当轮名单缺省 → next                                       // 防线仅白名单轮启用
  denied = input.toolCalls() 中 name 为 MCP/自定义 且不在名单解析集者
  if denied 为空 → next
  合成 ToolCallStart/End + ToolResultStart/TextDelta/End(DENIED) 事件
  + 注入 DENIED ToolResultMsg 到 agent 上下文（ProtocolDenyRules 同构）
  allowed（含全部内置调用）照常 next.apply
```

- **fail-soft**：metadata 畸形（非 List/非字符串元素）→ 按未知名处理（解析异常 WARN +
  该名单视为无效名集合，白名单生效即仅内置），不抛不阻断对话；解析过程任何异常 → WARN +
  本轮直通（全部工具）；
- **未传 tools 时零开销**：每轮一次消息遍历（与 skills 中间件同量级），无名单即不进过滤分支。

## 5. 查询接口（闭环体验）

调用方（前端/上游编排/release-agent）发 chat 前需要先知道"**哪些名字可以传进 `tools` 字段**"。
扩展既有 `GET /tools`（单一端点，不新增碎片）：

**① 条目级补充**：MCP 段每条补 `fullName`（= `ToolInfo.displayName`，`mcp__{server}__{tool}`
——现视图缺失，调用方拿不到可传的全名）；MCP/自定义段补 `sessionControllable: true`
（SDK 内置段恒 false）。响应分段与 `category` 字段（`mcp`/`internal`/`sdk`）维持既有语义。

**② 顶层汇总 `sessionTools`**（一键可传清单）：

```jsonc
GET /tools
{
  "tools": [ ... ],                     // 既有分段结构不变（MCP 段补 fullName）
  "totalCount": 12, "mcpCount": 5, "internalCount": 2, "sdkInternal": [...], "sdkInternalCount": 5,
  "sessionTools": {                     // 新增：可传入 chat/A2A tools 字段的清单
    "tools": ["query", "get_order", ...],   // 主口径裸名（与 Toolkit 注册名一致）
    "count": 7,
    "hint": "MCP 工具亦可传全名 mcp__{server}__{tool}；内置工具不受会话级 tools 控制（关闭走包级 deniedTools）"  // 实现文案为英文（见 api.md §GET /tools）
  }
}
```

- `sessionTools.tools` = MCP 已注册集（登记集派生，非 app_only——app_only 工具不注册
  Toolkit、不可传入 tools 字段，条目 `sessionControllable=false` 且 `?tools=` 预览恒保留
  其在视图中）∪ 自定义
  `customToolNames`，**与 `?tools=` 过滤、模型侧收敛严格同口径**——三处共用同一分类服务
  （见 §10 `SessionToolScopeService`），查询即所得、所查即所收敛；
- `?tools=` 过滤参数：仅裁剪 MCP 段与自定义段（SDK 内置段不受影响）；`null`/空串 =
  不过滤（存量行为）。

> **为什么不新建独立端点**：`GET /tools` 已是工具视图的单一事实源（三分段 + category
> 字段已有），闭环信息是它的一个正交维度；新端点会造成两处清单漂移风险。

## 6. 装配

`HarnessAgentFactory.build` 中间件链插入（`ToolCallValidationMiddleware` 之后、
`ProtocolDenyRulesMiddleware` 前后皆可，二者不共享状态）：

```java
// 会话级工具过滤（本轮 tools 声明，session-tool-list-design §4）：作用域限 MCP+自定义，
// 内置恒保留（内置启停走包级 deniedTools/tools.json）。onReasoning 收敛模型侧 schema
// （主防线），onActing 兜底 DENY 白名单外业务工具调用；未传 tools 时零开销直通。
// 三分类与全名别名解析统一走 SessionToolScopeService（与 /tools 查询接口同源，§5）
.middleware(new ToolFilterMiddleware(sessionToolScopeService))
```

与权限系统正交：`PermissionContextState`（构建期烘焙）的 ASK 规则对白名单**内**工具照常
触发 HITL 确认；白名单**外** MCP/自定义调用被 acting DENY，不进入权限/HITL 流程。

---

## 7. 兼容与边界

| 场景 | 行为 |
|------|------|
| 不传 tools 的存量客户端 | 与当前完全一致（全部工具） |
| 显式 `[]` | 仅内置工具（MCP/自定义全剔除）；agent 基础能力（文件/技能/Plan/子 Agent）不受影响 |
| 与 skills 同时传 | 正交组合：技能白名单 ∩ 工具白名单各自生效 |
| 未知工具名 / 静态已摘除名 | 忽略（无告警阻断） |
| 内置名写入名单 | WARN + 不改变可见性（内置恒可见，§2.1） |
| HITL 确认/恢复轮 | 白名单延续原轮（最后一条 USER 消息未变），confirm 链路零改动 |
| 包 reload（工具增删） | agent 重建时中间件名单刷新；名单按名匹配，下一轮自然生效 |
| 多副本 / 重启 | 列表随本轮消息传递，无状态差异；同一会话两次请求可带不同名单 |
| 历史轮次 | 不重写、不裁剪；模型仍能看到此前轮次内容与工具调用记录 |

**边界与调用方责任**：

- 列表是上下文/行为边界，不是授权边界——工具实现层面不做校验（能调用 chat 即可传任意名单）；
  硬隔离需拆服务/拆包（与 skills 同结论）。
- 内置工具的会话级收敛**刻意不提供**：需要关闭内置工具（如禁 Shell）走包级 frontmatter
  `deniedTools`（经 workspace `tools.json` 由 Harness 摘除，对全部会话生效）——
  会话粒度的内置开关属新需求，不在本设计。
- 白名单内工具的权限规则（allow/ask/deny）不受影响——deny 静态规则命中的工具本就不该再列入。

---

## 8. 风险与开放问题

| # | 风险/问题 | 处置 |
|---|-----------|------|
| R1 | `ReasoningInput`/`ActingInput` 为 SDK record，升级若改字段签名则中间件编译失败 | 编译期暴露（非运行时），升级时随 SDK 适配；2.0.3 已验证字段存在 |
| R2 | 白名单把 MCP/自定义全部剔除后模型只剩内置，轮次行为变化 | 显式语义（`[]` = 仅内置）+ 文档明示；调用方自行权衡 |
| R3 | 合成 DENIED 事件与 `ToolCallValidationMiddleware`/SSE 编解码顺序 | 复用 `ProtocolDenyRulesMiddleware` 已上线验证的同构模式（它已与 Validation 共存） |
| R4 | MCP 跨 server 同名工具 | Toolkit 覆盖语义（后注册者胜）先天消解——裸名匹配当前生效者；全名指向非生效注册时自然无效（§2.1），白名单不引入新的歧义分支 |
| R5 | LLM 请求记录/debug 页展示的 tools 与实际不一致造成困惑 | 过滤发生在 reasoning 层，`RequestBodyLoggingModelWrapper`/llm-calls 记录的是**过滤后**请求——天然一致，反而是验收证据来源 |
| R6 | SDK 升级新增内置工具（未在注册期任何名单中）被误判为"自定义"而受白名单影响 | 分类口径 = MCP（登记集派生）+ 自定义名单**正向枚举**，其余默认内置（不收窄）——漂移方向安全 |
| R7 | 查询接口（`sessionTools`）、`?tools=` 过滤、中间件收敛三处口径漂移 | 三处共用同一 `SessionToolScopeService`（单一分类/别名解析实现），结构上消除漂移 |
| O1 | 是否需要 `tools` 白名单也约束**子 Agent 派生声明**（subagents/*.md 文本仍可见） | 非目标（与 skills 对沙箱内文件同结论：上下文边界） |
| O2 | 是否需要会话级**内置工具**开关（如按业务线禁 Shell/文件写） | 本设计非目标；如确有需求另立设计（作用面、防线语义均不同） |

---

## 9. 测试与验收（DoD）

单测：

| 类 | 覆盖 |
|----|------|
| `ToolFilterMiddlewareTest` | 名单 → MCP/自定义 schema 收敛、内置恒保留；`[]` → 仅内置；缺省 → 直通原引用；未知名忽略；内置名入名单 WARN 不生效；两级名匹配（裸名精确 / 全名别名解析 / 全名指向非生效注册无效）；重复/空白归一；最后一条 USER 生效；畸形 metadata 不抛；onActing 白名单外 **MCP/自定义** DENY 合成、内置调用放行；缺省轮 acting 直通 |
| `SessionToolScopeServiceTest` | 三分类判定（isMcp MCP / 自定义枚举 / 内置兜底）；全名 ↔ 裸名映射；同名覆盖语义；SDK 漂移新名默认内置 |
| `ChatStreamControllerTest` | tools 写入消息 metadata（含空列表）、未传不写键 |
| `A2AControllerTest` | `message.metadata.tools` / `params.tools` / 旧格式归一（分组形态、显式空列表、与 skills 共存） |
| `ToolControllerTest` | `?tools=` 白名单与空串缺省；只裁剪 MCP/自定义段、SDK 内置段不裁剪；MCP 条目 `fullName`；`sessionTools` 汇总与分类服务同源 |
| `HarnessAgentFactoryMiddlewareAssemblyTest` | ToolFilterMiddleware 装配位置与名单注入（MCP/自定义集合） |

e2e（`e2e/session-tools-e2e.sh`，kind 部署验证，参照 `session-skills-e2e.sh`）：

1. 发布带 ≥2 个 MCP server（≥3 工具）+ ≥1 自定义工具的包；
2. `tools:["mcp__a__x"]` 轮次 llm-calls 请求体 tools 含该工具 + 全部内置、不含其他 MCP/自定义（R5 证据链）；
3. 下一轮不传 → 恢复全部（仅本轮生效）；
4. `tools:[]` → 请求 tools 仅剩内置；模型纯对话/内置工具照常可用；
5. 未知名混入 → 忽略且不报错；内置名混入 → 有 WARN 日志、可见性不变；全名别名解析生效、全名指向被覆盖注册无效；
6. 白名单外 MCP 工具调用（诱导提示词）→ acting 层 DENIED 事件回模型、不执行；内置工具调用不受影响；
7. 与 `skills` 同请求组合，两白名单各自生效；
8. 只读视图 `?tools=` 与模型侧同口径（内置段不变）；
9. **闭环**：`GET /tools` 的 `sessionTools.tools` 逐名传入 `tools` 字段 → 收敛结果 = 该清单 ∪
   全部内置（查询即所得）；`fullName` 传全名同样生效；MCP 条目 `fullName` 与 `sessionTools` 一致；
10. HITL：白名单内 ask 的 MCP 工具照常确认；确认轮（resume）白名单延续；
11. 双副本轮换续聊同口径（无状态，任意副本一致）。

---

## 10. 实施清单

| 文件 | 改动 |
|------|------|
| `service/ToolFilterMiddleware.java`（新） | 双防线中间件：onReasoning schema 收敛（内置恒保留）+ onActing 兜底 DENY |
| `service/SessionToolScopeService.java`（新） | 三分类（MCP 登记集 / 自定义枚举 / 内置兜底）+ 全名 ↔ 裸名别名解析；中间件与 `/tools` 查询共用（R7） |
| `service/McpToolRegistrar.java` | 暴露 `getToolInfoByDisplayName()`（全名 → ToolInfo 映射，`registerOne` 登记点已有数据） |
| `service/HarnessAgentFactory.java` | 装配（§6） |
| `controller/ChatStreamController.java` | `ChatRequest.tools`、metadata 下传（与 skills 同段平行） |
| `controller/A2AController.java` | 归一链平行扩展 `tools`（§2.2） |
| `controller/ToolController.java` | `/tools?tools=`（只裁剪 MCP/自定义段）+ MCP 条目 `fullName` + 顶层 `sessionTools` 汇总（§5） |
| 对应测试类 | 见 §9 |
| `e2e/session-tools-e2e.sh`（新） | 部署验证脚本（含 §9.9 闭环用例） |

控制面（backend/frontend）、数据库（无表变更）、manifests：**零改动**。

---

## 11. 实施记录（2026-11-09，与 §4/§10 的偏差注记）

- **MCP 三分类判定源**：§4.1 原文「Toolkit `isMcp()` 运行时判定」实施时改为 **McpToolRegistrar
  登记集**（`getSessionControllableToolNames()`，非 app_only 裸名）——两条注册路径同一登记点、
  reload/clearServer 即时反映，与 Toolkit 注册集同口径且免去运行时 Toolkit 反查；R6 的
  「漂移方向安全」结论不变（正向枚举之外的名单默认内置）。
- **测试桥**：`McpToolRegistrar.putToolInfoForTest`（包私有）供
  `SessionToolScopeServiceTest` 免起 MCP 连接注入登记。
- **视图增强落地细节**：`/tools` 的 internal 段条目为不可变 `Map.of`，`sessionControllable`
  标注在 controller 侧拷贝后写入（sdkInternal 段同样拷贝后置 false）。
- **`GET /tools` 的 `?tools=` 过滤口径**：MCP 段按裸名匹配（全名别名经 resolve 归一）、
  internal 段按裸名、sdkInternal 段不受影响。
- **部署验证**：本设计单测矩阵全绿（新增 `ToolFilterMiddlewareTest` 11 例、
  `SessionToolScopeServiceTest` 9 例，全仓 1901/0/5）；`e2e/session-tools-e2e.sh`
  （含 `e2e/mock-mcp-server.py` 双 server mock，8814/8815）覆盖 §9 全部场景，
  在具备 kind 集群的部署机执行（前置：新镜像已导入，mock 端口可被 Pod 访问）。
  §9.10 的 HITL 确认轮用例暂缓：需包内声明 ask 权限 MCP 工具 + 人工确认交互，
  成本高于其余用例一个量级；白名单跨确认轮的语义已由「最后一条 USER 消息不变」
  的中间件机制保证 + `ToolFilterMiddlewareTest.actingWithoutWhitelistShouldFailOpen`
  覆盖 fail-open 分支，部署验证先行验证非确认轮场景。
