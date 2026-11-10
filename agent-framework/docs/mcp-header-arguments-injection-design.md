# 请求级 Header 注入下游工具参数（`reqHeader:*` → arguments）设计

| 项 | 值 |
|----|----|
| 编制时点 | 2026-11-10 |
| 状态 | **已实施（2026-11-10，feat/mcp-header-arguments-injection，实施记录见 §11）**。对已实施的 [mcp-chat-header-forward-design.md](mcp-chat-header-forward-design.md) 的增量修订——新增「参数注入通道」，存量 HTTP header 通道语义不变 |
| 需求来源 | 2026-11-10 现场联调实证（§1.1）：下游 MCP 业务服务的工具参数**只从 JSON-RPC `params.arguments` 取值**，HTTP header 到不了业务代码——存量透传机制的 header 单通道在该场景失效 |
| 需求 | 调用方在对话请求上携带的业务参数 header（如 `taskId: 1001-1757858`），按包配置声明在工具调用时**合并进下游 MCP 工具调用的 `arguments`**，使参数真正到达下游工具业务逻辑 |
| 现状核对 | 本文所有「现状」断言基于 2026-11-10 master（5a15bb7）代码逐条核实，来源标注到文件/行 |
| 关联文档 | [mcp-chat-header-forward-design.md](mcp-chat-header-forward-design.md)（存量 header 通道设计，本文不重复其已定决议）、[mcp-user-scoped-headers-plan.md](mcp-user-scoped-headers-plan.md)（userHeaders + `_meta` 双通道前置机制） |

---

## 1. 背景与目标

### 1.1 现场实证（需求取证）

下游联调方经 higress 网关调用其业务 MCP 服务（`POST …/pcc-api/service-mcp`）时，
把业务参数 `taskId: 1001-1757858` 放在 **HTTP header** 上，服务端取不到。联调结论
（对话原文归纳）：

1. 「你往 header 里面是拿不到的吗？」→「不是的，从 params」；
2. `tools/call` 请求体实形：`{"params": {…, "arguments": {"taskId": "xxxx"}}, "method": "tools/call"}`
   ——**所有工具参数都必须放在 `params.arguments` 里**；
3. 「MCP 不支持从 header 里面取出参数吗」→ MCP 协议的业务参数通道就是 arguments，
   header 不承载业务参数（header 只到网关/传输层，不进工具业务逻辑）。

对存量机制的含义：mcp-chat-header-forward 把捕获的请求头注入**下游 HTTP header**
（`McpUserHeaderCustomizer` 落到出站请求），该通道只对「读 header 做鉴权/审计/路由」的
网关与 server 传输层有效——**下游工具方法签名里拿不到**。`taskId` 这类业务参数按现机制
透传后，工具依然收不到。

### 1.2 目标

`userHeaders` 机制新增**参数注入通道**：包作者声明「工具参数名 ← 取值源」映射，框架在
每次 `callTool` 出站前把解析值合并进 `arguments`：

1. 包作者在 `config.yaml` 声明 `arguments: { taskId: reqHeader:taskid }`；
2. 调用方 `POST /threads/chat` 带 `taskId: 1001-1757858` → 本次 turn 内该 server 的
   工具调用 `arguments.taskId` 被注入原值（调用方/模型显式给过的参数**不覆盖**）；
3. 未声明 → 行为与今天逐字节一致（零影响）。

存量 HTTP header 通道（`headers` 节）**原样保留**——网关鉴权/审计场景仍然需要它；
两个通道互补而非替代。

### 1.3 非目标

- A2A / invoke / 进程内子 agent 链路的捕获补齐（继承存量设计 §8.1 边界，见 §11 Q2）；
- stdio 传输的 arguments 注入（v1 维持 stdio 对 userHeaders 节整体告警忽略，§11 Q3）；
- 参数值类型转换（header 值恒以字符串注入，§8.4）；
- token exchange、平台认证体系变更。

## 2. 方案总览

```
调用方 → POST /threads/chat（Header: taskId: 1001-1757858）
        │
ChatStreamController / AguiRunController / ConfirmController（存量，零改动）
        │ ① 捕获名单 = getForwardHeaderNames()（★ 扩为 headers ∪ arguments 两节并集）
        │ ② ChatRequestHeaders.capture → RuntimeContext typed attribute（存量链路不变）
        ▼
McpUserContextMiddleware.onAgent（存量，零改动）
        │ ③ reqHeader:* 合并进 McpMeta（存量不变）
        ▼
McpTool（SDK）：callTool(name, args, meta)
        │
UserScopedMcpClientWrapper.callTool（★ 唯一改动点）
        │ ④ headers 通道：resolve → HTTP header 注入（存量不变）
        │ ⑤ arguments 通道（新增）：
        │    a. 按本 server arguments 映射从 meta 取值（reqHeader:* / userId 同款取值源）
        │    b. schema 门控：getCachedTool(toolName).inputSchema() 已声明该参数（或允许
        │       additionalProperties）才注入；schema 不可得时 best-effort 注入（§6.3）
        │    c. args-win 合并：仅填 arguments 中**不存在**的键，显式参数优先
        │ ⑥ _meta 剥离 reqHeader:*（存量不变——arguments 注入不改变 _meta 单通道语义）
        ▼
下游 MCP server：CallToolRequest.params.arguments 含注入参数 → 工具业务逻辑可达
```

设计原则（延续存量决议）：

- **捕获即声明**：arguments 节的 `reqHeader:*` 头名并入捕获名单，未声明不捕获（不变）；
- **值全程 in-band**：入口 → RuntimeContext → McpMeta → wrapper，无落盘、无 store（不变）；
- **McpMeta 写入单点不变**：middleware 零改动，注入只发生在 wrapper 出站层；
- **args-win**：框架只补缺，不覆盖调用方/模型显式给出的参数——幂等、可预期，避免
  「header 旧值静默顶替模型决策值」。

## 3. 现状核对（锚点）

> 行号取 2026-11-10 master（5a15bb7）。

| 现状 | 位置 |
|------|------|
| `UserHeaderRule`：record（`headers` 映射 + `denyOnMissing`），无 arguments 概念 | `UserHeaderRule.java:13-17` |
| 规则缓存：`userHeaderRules: serverName -> UserHeaderRule`，装载点 `registerOne`/`registerBuiltClient`/`registerAll` 路径，`clearServer` 同组移除 | `McpToolRegistrar.java:88, 154, 224, 318` |
| 捕获名单：`getForwardHeaderNames()` 只并集 `rule.headers().values()` 的 `reqHeader:*` 头名 | `McpToolRegistrar.java:340-351` |
| 解析：`parseUserHeaders` 只认 `userHeaders.headers`；`reqHeader:` 后段 lowercase 归一 + 平台 denylist 解析期剔除；无 `arguments` 节 | `McpToolRegistrar.java:1082-1136` |
| stdio：声明 userHeaders 整节告警忽略（rule=null → EMPTY 常驻装饰仅做 `_meta` 剥离） | `McpToolRegistrar.java:1039-1046` |
| 装饰：HTTP 传输一律包 `UserScopedMcpClientWrapper`（常驻化，issue #225） | `McpToolRegistrar.java:1061-1062` |
| wrapper：`callTool` 先 resolve header（fail-closed / 值合法性校验）→ `contextWrite` 注入；`stripForwardKeys` 恒剥 `reqHeader:*` | `UserScopedMcpClientWrapper.java:55-76, 84-100` |
| schema 可得：`getCachedTool(toolName)` 委托 delegate；`McpSchema.Tool.inputSchema()` 在 registrar 已有使用先例 | `UserScopedMcpClientWrapper.java:102-104`；`McpToolRegistrar.java:878` |
| 入口捕获：chat / AGUI / confirm（native+AGUI 双面）已全覆盖，`ChatRequestHeaders.capture` 名单驱动 | `ChatRequestHeaders.java:87-126`（入口接线见存量设计 §6.2/§6.5） |
| middleware：`reqHeader:*` 以保留命名空间合并进 McpMeta，唯一注入点 | `McpUserContextMiddleware.java:54-77` |
| tracing 只记录 model 侧入参：`gen_ai.tool.call.arguments` 取自 ToolUseBlock（模型产出），注入发生在 wrapper 出站层，**span 不含注入值** | `ToolCallTracingMiddleware.java:150-165` |
| 平台规范：`userHeaders` 节只描述 headers 通道 | `docs/oaf-specification.md:392-409` |

**缺口一句话**：捕获 → McpMeta → wrapper 的上游链路齐备且无需触碰；缺的是 wrapper
出站层的「meta → arguments」合并，以及 arguments 节的解析与名单并集。

## 4. 配置层设计

并入现有 `userHeaders` 节（延续「否决独立 forward 节」的既有决议精神——同一取值源抽象、
同一 denylist、同一解析入口，不拆两个节点）：

```yaml
# mcp-configs/{server}/config.yaml（现有文件）
connection: { type: streamableHttp, url: ... }
auth: { token: ${MCP_DISCOVERY_TOKEN} }      # 不变

userHeaders:
  headers:
    X-Task-Id: reqHeader:taskid              # 存量：HTTP header 通道（网关鉴权/审计）
  arguments:                                 # ★ 新增：参数注入通道
    taskId: reqHeader:taskid                 # 工具参数名 ← 取值源（与 headers 同款抽象）
    tenantId: userId                         # 取值源支持 userId（内建 meta key）
  on-missing: deny                           # 语义不变，仅作用于 headers 通道（§6.4）
```

解析规则（`parseUserHeaders` 增量）：

- `arguments` 节缺省 / 非映射 → 空映射（零影响）；键 = 工具参数名（原样大小写，不归一
  ——参数名匹配的是下游 schema，大小写敏感）；值 = meta key，`reqHeader:<小写头名>`
  取值源沿用既有归一 + denylist 解析期剔除逻辑（与 headers 节逐条同款，代码级复用）；
- **stdio 传输**：维持整节告警忽略（告警文案更新为涵盖 arguments），arguments 注入 v1
  仅对 sse / streamableHttp 生效（§11 Q3）；
- `headers` 与 `arguments` 可只声明其一；两节可映射同一头名（捕获名单求并去重）。

## 5. 详细设计

### 5.1 `UserHeaderRule` 扩展

```java
public record UserHeaderRule(Map<String, String> headers,
                             Map<String, String> arguments,
                             boolean denyOnMissing) {
    public static final UserHeaderRule EMPTY = new UserHeaderRule(Map.of(), Map.of(), true);
}
```

- record 组件新增为**源码不兼容变更**（规范构造器签名变化）：更新全部构造点
  （`UserHeaderRule.EMPTY`、`parseUserHeaders`、单测桩）；读取方（wrapper）只走
  accessor，天然兼容；
- 不引入 `schemaMatch` 配置项：schema 门控恒开启（§6.3），逃生舱走 §11 Q5 再议——
  先用最小配置面验证场景。

### 5.2 `parseUserHeaders` / 捕获名单

- `parseUserHeaders` 增加 `arguments` 节解析：键值合法性校验、`reqHeader:` 归一、
  denylist 剔除均与 headers 节同款（抽私有方法 `resolveMetaKey(serverName, rawValue)`
  复用）；`headers` 与 `arguments` 均空时返回 null 的既有语义不变；
- `getForwardHeaderNames()` 并集来源扩为 `headers.values() ∪ arguments.values()` 的
  `reqHeader:*` 头名（仍从内存缓存取，禁每请求重读 config.yaml 的红线不变）。

### 5.3 wrapper 注入（`UserScopedMcpClientWrapper.callTool`）

`callTool(toolName, arguments, meta)` 在既有 header resolve 之后追加：

```java
var merged = injectArguments(toolName, arguments, meta);   // 新增；无映射/无可注入值时原引用返回
// header 通道 contextWrite 注入逻辑不变，delegate.callTool(toolName, merged, outboundMeta)
```

注入语义（按序）：

1. **取值**：对本 server `arguments` 映射逐条从 meta 取值；空值/缺值 → 跳过该条
   （参数通道不做 fail-closed，§6.4）；值用捕获原串，不做 HTTP 合法性校验
   （不走传输层 header，无 CRLF 约束）；
2. **schema 门控**：`delegate.getCachedTool(toolName)` 取 `inputSchema()`：
   - `properties` 含参数名 → 注入；
   - `properties` 未含但 `additionalProperties` 非 `false`（含缺省——JSON Schema 缺省
     即允许）→ 注入；
   - `properties` 未含且 `additionalProperties: false` → 跳过（DEBUG 日志，只记参数名
     与工具名）——防止污染同 server 上不接受该参数的严格 schema 工具；
   - 工具/schema 不可得（未缓存、异常）→ best-effort 注入（与「下游 schema 写漏但
     实际读参」的松散实现兼容）；
3. **args-win 合并**：`arguments` 中**已存在**同名键 → 保留原值不注入（DEBUG 日志）；
   注入时对 arguments 做防御性拷贝（入参可能不可变），构造 `LinkedHashMap` 合并。

`arguments` 映射为空（存量包）→ 方法首行短路，零开销、零行为变化。

### 5.4 通道语义对照（评审重点）

| 维度 | headers 通道（存量） | arguments 通道（本期新增） |
|------|---------------------|--------------------------|
| 载体 | 下游 HTTP request header | JSON-RPC `params.arguments` 键值 |
| 到达层 | 网关 / server 传输层 | 工具业务方法签名 |
| 缺值策略 | `on-missing`（缺省 deny，fail-closed） | 恒跳过注入（工具调用照常，缺参由下游 schema 校验报错，错误天然可定位） |
| 值合法性校验 | CRLF/控制字符/非 ASCII 拒绝（HTTP 约束） | 不校验（JSON 字符串无传输层约束） |
| `_meta` | 恒剥离 `reqHeader:*`（不变） | 同左（arguments 注入不产生 `_meta` 通道） |
| stdio | 告警忽略 | 告警忽略（v1 一致，§11 Q3） |

**arguments 通道不引入 on-missing/deny 的理由**：fail-closed 存在于 header 通道是为防
「静态发现凭据静默顶替用户凭据」；参数通道注入的是业务标识符，缺值时不注入即可——
工具若必需该参数，下游按自身 schema 报错，链路天然可定位，无需框架层拒绝整个调用。
若评审认为需要 deny（调用方漏带 header 即失败，暴露更早），见 §11 Q1。

## 6. 安全设计

| 项 | 设计 |
|----|------|
| 信任模型 | 与存量一致：网关可信、直连可伪造，不新增风险面 |
| 凭据防误注入 | 平台 denylist（`cookie`/hop-by-hop/`x-forwarded-*`/`sec-*` 等）在**解析期**对 arguments 节同样生效——平台会话凭据类头无法声明。注意 `authorization` **不在** denylist（headers 通道主用例，存量设计 §7 Q1 决议延续）：`arguments: { token: reqHeader:authorization }` 在声明上是合法的 |
| 下游回显面 | 注入值可能经下游结果/错误回显进 ToolMessage → **模型上下文与 checkpoint**（与 headers 通道的本质差异：header 值只到传输层，参数值可能被回显）——arguments 通道定位为**业务标识符**（taskId/订单号/租户）非机密；规范文档强声明「勿将凭据类参数走此通道」，`authorization` 声明进 arguments 视同包作者显式行为（回显风险自负），与存量「转发 Authorization = 包作者显式行为」同口径 |
| tracing/checkpoint 不含注入值 | 注入发生在 wrapper 出站层；`gen_ai.tool.call.arguments` 记录的是 model 侧 ToolUseBlock（`ToolCallTracingMiddleware.java:150-165`），checkpoint 持久化的也是 model 产出消息——注入值仅存在于出站 JSON-RPC 报文 |
| 错误不泄漏值 | 沿用存量约束：日志与异常只记参数名/头名/meta key 名，绝不打印值 |
| `_meta` 隔离 | `stripForwardKeys` 逻辑零改动，注入不影响剥离语义 |

## 7. 边界与已知限制

1. **A2A / invoke / 子 agent turn 无捕获集**（存量 §8.1 边界延续）：arguments 通道在这些
   turn 上同样无可注入值 → 不注入。若现场调用方经 A2A 进来，透传断——是否本期补齐见
   §11 Q2；
2. **stdio 不生效**（§11 Q3）；
3. **类型恒为字符串**：schema 期望 number/boolean 时注入 string 值可能被下游校验拒绝
   ——v1 不做类型推断（§11 Q4），规范文档提示包作者只对 string 型参数声明注入；
4. **排队 turn 时效性**：header 时效由调用方保证（存量 §8.4 不变）；
5. **reload 语义**：arguments 映射随 `reloadMcpAll` / watchdog 重建即时更新，已排队
   turn 用旧映射、下一 turn 用新映射（存量 §8.6 语义延续）；
6. **schema 门控依赖工具缓存**：`getCachedTool` 未命中时 best-effort 注入（§5.3）——
   极端情况下严格 schema 工具可能收到未声明参数被下游拒绝；该情形仅发生在工具缓存
   异常时，属可接受退化。

## 8. 兼容性影响与回归范围

| 变更 | 影响 | 回归 |
|------|------|------|
| `UserHeaderRule` 加组件 | 规范构造器签名变化，构造点全量更新（EMPTY / parse / 单测桩） | 既有 `McpToolRegistrarUserHeadersTest` / `UserScopedMcpClientWrapperTest` 全量重跑 |
| `parseUserHeaders` 加节解析 | 无 `arguments` 节的包解析结果等价（新组件为空映射） | 既有解析单测 + 新增分支单测 |
| `getForwardHeaderNames` 并集扩围 | 仅声明了 arguments 的包捕获集变大（原 headers 节并集不变） | 名单推导单测 + chat 捕获单测 |
| wrapper 加注入 | 无 arguments 映射 → 首行短路，行为逐字节一致；有映射 → 仅出站 arguments 变化 | 既有 wrapper 单测 + 新增注入/schema 门控/args-win 单测 |
| middleware / 入口捕获 / customizer | **零改动** | e2e 存量核心组天然覆盖 |
| 文档 | oaf-specification / api.md / AGENTS.md 增补 | — |

无 backend / 平台侧改动（配置在 OAF 包内）；无 DB 迁移。

## 9. 实施拆分

| # | 任务 | 验证 |
|---|------|------|
| T1 | `UserHeaderRule` 加 `arguments` 组件 + 构造点更新 | 编译 + 既有单测重跑 |
| T2 | `parseUserHeaders` arguments 节解析（denylist 复用/归一/stdio 告警文案） | 单测：解析、denylist 剔除、归一、空节、两节混用 |
| T3 | `getForwardHeaderNames()` 并集扩围 | 单测：两节求并去重 |
| T4 | wrapper `injectArguments`（取值/schema 门控/args-win/防御性拷贝/短路） | 单测：注入、门控跳过（additionalProperties:false）、schema 缺失 best-effort、args 覆盖保留、不可变入参、空映射短路 |
| T5 | e2e：chat 带 `taskId` header → bench mock `/last-call` 断言下游实收 `arguments.taskId`；缺头不注入；模型显式参数不被覆盖；schema 未声明工具（同 server 第二工具）不注入 | 复用 bench mock `/last-call` 观测面（存量 S5b 先例） |
| T6 | 文档：本设计状态列、`oaf-specification.md`（arguments 节 + 通道对照表）、`api.md`（请求头契约补参数通道）、`AGENTS.md`（McpToolRegistrar/userHeaders 节同步） | — |

## 10. 开放问题（评审确认点）

| # | 问题 | 建议 |
|---|------|------|
| Q1 | arguments 通道是否需要独立 `on-missing: deny`（调用方漏带 header 即快速失败，暴露早于下游报错） | v1 不引入（§5.4 理由）；出现真实诉求再加独立开关 |
| Q2 | A2A / invoke 入口捕获是否同期补齐（现场调用方若经 A2A 进来，headers/arguments 两通道在其上均断） | 本期不做、维持存量边界；若现场确认走 A2A 再立项（机制同型：A2AController + resume 链路各一处捕获） |
| Q3 | stdio 是否放开 arguments 注入（JSON-RPC 通道对 stdio 同样可达，技术上成立） | v1 维持告警忽略与 headers 通道口径一致；有本地 stdio 工具收参诉求再放 |
| Q4 | 参数类型转换（按 schema 把 string 转 number/boolean） | v1 不做，规范提示只声明 string 型参数；真实场景受阻再立项 |
| Q5 | schema 门控是否需要逃生舱（`schema-match: false` 全量注入，覆盖下游 schema 写漏场景） | best-effort 分支（schema 不可得时注入）已覆盖主要松散实现；先不加配置项 |

## 11. 实施记录（2026-11-10）

**验证结果**：单测全量 2000 例 / 0 失败 / 9 跳过（docker maven 离线镜像内实跑，其中
arguments 通道新增目标单测 38 例全绿）；本地 e2e core 组（api-core + api-models +
api-reload + ui）**98 passed / 3 skipped**（含新增 S5c：chat 携带 `taskId` header →
bench mock `/last-call` 实收 `arguments.taskId` 原值 + 既有 userId header/`_meta` 不受
影响；缺头调用不注入、工具照常）。

**落地差异（相对本文设计）**：

1. **schema 门控实现类型**：mcp-core 0.17.2 的 `McpSchema.Tool.inputSchema()` 实为
   `McpSchema.JsonSchema` record（`properties()` Map / `additionalProperties()` Boolean，
   null = 未声明即允许）——§5.3 以 Map 语义描述，落地为 record accessor 读取，门控语义
   与设计逐条等价；
2. **判定顺序**：args-win 检查先于 schema 门控（已显式给参的键不产生「schema 不接受」
   误导日志），注入结果与 §5.3 等价；
3. **T5 e2e 形态**：bench mock `/last-call` 增记 `toolName`/`arguments`；`bench_echo`
   inputSchema 增声明非必需 `taskId` 参数（schema 门控正例路径）；`check_oaf_package`
   未追加 `additionalProperties: false`（schema 门控负例由单测
   `shouldGateInjectionByToolInputSchema` 覆盖，e2e 保持最小 mock 改动）；
4. **文档**：`oaf-specification.md`（userHeaders 双通道节 + 通道对照）、`api.md`（请求级
   透传契约）、`AGENTS.md`（目录注释 / 多租户节 / 请求级透传节）同步完成。
