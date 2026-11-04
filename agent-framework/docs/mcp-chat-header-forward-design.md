# Chat 请求 Header 按声明透传至 MCP（`reqHeader:` 取值源）设计

| 项 | 值 |
|----|----|
| 编制时点 | 2026-11-02 |
| 状态 | **已实施（2026-11-02，feat/mcp-chat-header-forward，实施记录见 §12）**。已确认决议：**chat 入口捕获名单由 `userHeaders.headers` 配置驱动**（各 server 声明的 `reqHeader:<头名>` 取值源求并集），不做全量捕获 |
| 需求 | 调用方请求 `/threads/chat` 时携带的 HTTP header（如 `Authorization`、`X-Request-Id`），按 OAF 包 `mcp-configs/{server}/config.yaml` 的声明透传到该 server 的 MCP 工具调用 HTTP 请求上（下游按调用方凭据鉴权/审计/路由） |
| 现状核对 | 本文所有「现状/SDK 能力」断言基于 2026-11-02 master（f3bcd06）代码与 `agentscope-harness/core 2.0.3` **源码**（`agentscope-harness-2.0.3-sources.jar`）逐条核实，来源标注到文件/行；实施前需按惯例复核行号漂移 |
| 关联文档 | [mcp-user-scoped-headers-plan.md](mcp-user-scoped-headers-plan.md)（前置机制：userHeaders + `_meta` 双通道 + middleware 单点，本文是其取值源的增量扩展）、[oaf-dynamic-reload-plan.md](oaf-dynamic-reload-plan.md)（reload 兼容）、[e2e-ci-plan.md](e2e-ci-plan.md)（回归矩阵） |

---

## 1. 背景与目标

[MCP 多租户按用户调用](mcp-user-scoped-headers-plan.md)（PR #9）已打通「McpMeta → 下游 HTTP header」的
**下半段**：config.yaml `userHeaders.headers` 声明 `header 名 ← McpMeta key` 映射，
`UserScopedMcpClientWrapper` 每次 callTool 按映射注入、fail-closed，`_meta` 双通道生效。

但 McpMeta 里目前**只有 middleware 注入的 `userId`**——取值源是框架内推导的会话身份，
调用方在 chat 请求上携带的原始 header（最典型：下游服务自己的 `Authorization` 凭据、
链路追踪头、租户头）在入口即被丢弃，无法透传。

**目标**：让 `userHeaders.headers` 的映射值支持一个新取值源——**chat 请求的原始 header**，
声明式、按 server 生效、凭据不落盘：

1. 包作者在 `config.yaml` 声明 `Authorization: reqHeader:authorization`；
2. 调用方 `POST /threads/chat` 带该 header → 本次 turn 的 MCP 工具调用把原值注入下游
   HTTP header；
3. 未声明 → 行为与今天完全一致（零影响）。

**非目标**：A2A / invoke / 进程内子 agent / MCP 资源代理（`McpResourceProxy`）链路的
透传（见 §8 边界）；token exchange；平台认证体系变更。

## 2. 调研结论（SDK 2.0.3 源码核实，设计依据）

> 原设计文档 §10.2 的决定性论据「`ChatUiRequest` 无透传字段 → Channel 主链路只能走
> middleware」**已过时**：2.0.3 的 `ChatUiChannel` 实际公开了携带上下文的 in-band 通道。
> 以下 5 条均来自 `agentscope-harness-2.0.3-sources.jar` 源码（非字节码推断）。

| # | 结论 | 来源 |
|---|------|------|
| R1 | `ChatUiChannel.sendStream(ChatUiRequest)` 内部就是 `dispatchStream(buildInbound(request))`；对 `withPeer(peer, msgs)` 产出的正是 `InboundMessage.dm(CHANNEL_ID, peerId.trim(), messages)`——**无 subagent 路由分支参与** | `ChatUiChannel.java:412-418`、`:468-483` |
| R2 | `dispatchStream(InboundMessage)` 是 public 的 Channel SPI：`router.resolveRoute(config, message)` 路由后调 `gateway.runStream(route.context(), messages, route.outboundAddress(), message.runtimeContext(), message)`——与现路径**同一条路由代码**，仅多传 runtimeContext | `ChatUiChannel.java:150-163, 321-333` |
| R3 | `InboundMessage` 是 record，含 `runtimeContext` 字段与 `withRuntimeContext(...)` 拷贝方法；javadoc 明示「not used for routing and does not participate in session」——只随行，不影响会话键 | `InboundMessage.java:63-76, 176` |
| R4 | `HarnessGateway.buildRuntimeContext` 合并顺序：①以 callerContext 为 base → ②（可选 `ChannelRuntimeContextResolver`，本方案不用）→ ③网关身份字段（`sessionId`/`userId`/`msgContext`/`gateKey`/`outboundAddress`）**覆盖式生效**。入口放进去的自定义 typed attribute 全程保留，身份字段不被污染 | `HarnessGateway.java:279-322`；`SendOptions.java` javadoc「Gateway-owned identity fields still win on conflict」 |
| R5 | `RuntimeContext` attributes **不持久化**；`McpMeta` 存 typed 槽、`merge` 保留既有 entries；`RuntimeContext.builder(base)` 会继承 base 的 attributes | mcp-user-scoped-headers-plan §2.2（已核实）；`RuntimeContext.java:180` javadoc；`McpUserContextMiddleware.java:53-56` 在用 |

另两条出口侧事实（沿用既有验证）：

- `McpTool` 每次 callTool 从 ctx 提取 `McpMeta`，把 **entries 全量**写进
  `CallToolRequest._meta` → 共享 per-turn 的 McpMeta 意味着 A server 声明的透传值会出现在
  B server 的 `_meta` 里（§6.4 的剥离设计针对此点）；
- wrapper 是我方装饰器，**先处理再转交 delegate** 可行（meta 为 unmodifiableMap，过滤用拷贝）。

## 3. 现状核对（锚点）

> 行号取 2026-11-02 master（f3bcd06），类名/方法名经复核有效。

| 现状 | 位置 |
|------|------|
| chat 入口只显式读 `X-User-Id` / `X-Forwarded-Prefix`，其余 header 丢弃 | `ChatStreamController.java:303-305` |
| Channel 主链路下发：`channel.sendStream(ChatUiRequest.withPeer(sid, msgs))` | `ChatStreamController.java:608`；AGUI 同型 `AguiRunController.java:307` |
| middleware 把生效 userId 写入 McpMeta（唯一注入点） | `McpUserContextMiddleware.java:48-60`；装配 `HarnessAgentFactory.java:232` |
| `userHeaders` 解析：`parseUserHeaders`（value 语义透明，仅按 meta key 取值）；stdio 告警忽略；`buildAsync` + 装饰器仅在 rule 非空时启用 | `McpToolRegistrar.java:952-978, 998-1030` |
| wrapper per-call 解析 + `contextWrite`；customizer `setHeader` 替换注入；值合法性校验（CRLF/控制字符/非 ASCII） | `UserScopedMcpClientWrapper.java:58-73, 95-121, 131-145`；`McpUserHeaderCustomizer.java:27-41` |
| **wrapper 的 rule 只存在于 wrapper 实例内，registrar 无集中缓存**——捕获名单推导需新增 | `McpToolRegistrar.buildClient:962-978` |
| confirm 恢复是新 HTTP 请求，`buildResumeContext` 只带 sessionId/userId；已有 `X-User-Id` 捕获先例 | `AgentRuntimeService.java:723-736`；`ConfirmController.java:126, 213` |
| confirm 恢复入口三重载 | `AgentRuntimeService.resumeWithConfirm:585 / resumeWithConfirmStream:607 / resumeWithConfirmEvents:658` |

**缺口一句话**：McpMeta → HTTP header 的下半段齐备；缺的是「chat 请求头 → RuntimeContext」
的上游随行链路，以及「哪些头值得捕获」的名单来源。

## 4. 方案总览

```
调用方 → POST /threads/chat（Header: Authorization / X-Request-Id / ...）
        │
ChatStreamController
        │ ① 取捕获名单：mcpToolRegistrar.getForwardHeaderNames()
        │    （= Σ 各 server userHeaders 规则中 reqHeader:* 头名，解析期已过平台 denylist）
        │ ② 按名单捕获请求头（小写归一、多值合并、限额）→ ChatRequestHeaders(typed)
        │ ③ 下发（与现路径同路由，R1/R2）：
        │    channel.dispatchStream(InboundMessage.dm(channelId, sid, msgs)
        │                              .withRuntimeContext(ctx.put(ChatRequestHeaders.class, captured)))
        ▼
McpUserContextMiddleware.onAgent（唯一 McpMeta 写入点不变）
        │ ④ ctx.get(ChatRequestHeaders.class) → 以保留命名空间 reqHeader:<头名>
        │    合并进 McpMeta（userId 注入逻辑不变，merge 保留既有 entries）
        ▼
McpTool（SDK）每次工具调用：McpMeta entries → callTool(name, args, meta)
        │
UserScopedMcpClientWrapper（常驻化 + 两项扩展，§6.4）
        │ ⑤ resolve()：本 server 规则声明的 reqHeader:* 键 → 注入 HTTP header（沿用 fail-closed）
        │ ⑥ 剥离：转交 delegate 的 meta 恒移除 reqHeader:* → 透传值不进任何 server 的 _meta
        ▼
McpUserHeaderCustomizer（零改动）→ 下游 MCP server 收到 per-call 透传 header
```

设计原则：

- **捕获即声明**：不声明不捕获（已确认决议）——进程内不出现任何「多余的头」，名单天然
  受包配置约束；平台 denylist 在**解析期**剔除声明（§5），捕获期是第二道防线；
- **值全程 in-band**：入口 → RuntimeContext typed attribute（不持久化，R5）→ McpMeta →
  wrapper，无 store、无 TTL、无跨副本状态（原设计 §5.3.3 的 `TurnUserTokenStore`
  后备设计**不再需要**）；
- **McpMeta 写入单点不变**（Q6 决议精神延续）：controller 只放原始原料（typed attribute），
  McpMeta 组装仍只在 middleware。

## 5. 配置层设计

并入现有 `userHeaders` 节，value 支持新取值源（保留命名空间）：

```yaml
# mcp-configs/{server}/config.yaml（现有文件）
connection: { type: streamableHttp, url: ... }
auth: { token: ${MCP_DISCOVERY_TOKEN} }      # 不变：仅连接初始化与 tools/list 发现

userHeaders:
  headers:
    X-User-Id: userId                         # 现有取值源：McpMeta key（middleware 注入的身份）
    Authorization: reqHeader:authorization    # ★ 新增：chat 请求同名 header 透传
    X-Request-Id: reqHeader:x-request-id      #    声明约定：reqHeader: + 小写头名
  on-missing: deny                            # 语义不变，缺省 deny（fail-closed）
```

规则（解析期，`parseUserHeaders` 增量）：

- value 以 `reqHeader:` 前缀 → 取值源 = chat/confirm 请求的同名 header；**前缀后部分
  解析期强制 lowercase 归一**（作者写 `reqHeader:Authorization` 也归一，DEBUG 日志提示），
  保证与捕获键（小写）确定匹配；
- **平台 denylist 剔除**：`cookie` / `set-cookie` / hop-by-hop（`connection`/`keep-alive`/
  `transfer-encoding`/`upgrade`/`te`/`trailer`/`proxy-*`）/ `host` / `content-*` / `accept*` /
  `user-agent` / `x-forwarded-*` / `sec-*` / `x-forwarded-prefix`（框架自用）——命中即
  **丢弃该映射 + WARN**（与现有「invalid entry ignored」同风格；调用期 deny 兜底可见）；
  denylist 常量放在捕获工具类（§6.2），registrar 调用，单一权威；
- `reqHeader:` 与 `userId` 可混用、可映射到同一 header 名（LinkedHashMap 后值覆盖先值，
  既有行为）；stdio 声明 userHeaders 仍走既有告警忽略（rule 为 null → 不进名单缓存）；
- 名单为空（无任何 server 声明 `reqHeader:*`）→ 该服务捕获集为空，链路近似零开销。

> 被否的替代形态：新增独立 `forward:` 节（列表语义更直观，但与 `on-missing`/映射语义
> 重复，解析/校验/文档三份成本；`headers` 的 value 本就是「meta key」抽象，`reqHeader:`
> 只是新增一个命名空间，`parseUserHeaders`/`UserHeaderRule`/`resolve()` 三处零改动）。

## 6. 详细设计

### 6.1 捕获名单推导（`McpToolRegistrar`）

- 新增缓存 `userHeaderRules: serverName -> UserHeaderRule`。**装载点照抄 uiMappings
  模式：`registerOne`（`:213` 附近）与 `registerBuiltClient`（`:140-144` 附近），与
  `loadUiMapping` 等并排调一次 `parseUserHeaders`（独立重读磁盘），`clearServer:303-306`
  同组移除**。不得放在 `buildClient` 里——swap-on-success 顺序是
  `buildClientForReload（build）→ clearServer → registerBuiltClient`，build 时放入会被
  紧跟的 clearServer 抹掉且不再重装，产生「已注册但无规则」的不一致态。装载点放对后，
  三条刷新通道（A 路径 registerBuiltClient / B 路径 registerAll→registerOne / watchdog
  重建 registerBuiltClient）自动流经，无需新机制；
- 新增公开方法 `Set<String> getForwardHeaderNames()`：并集所有 rule 中 `reqHeader:` 前缀
  metaKey 的头名部分（已 lowercase、已过 denylist）。**实现红线：并集必须来自内存缓存，
  禁止每请求重读/重解析 config.yaml（文件 IO + SnakeYAML 解析才是真性能风险）**；并集
  结果可再缓存、register/clear 失效。每请求开销 = 遍历 server 数，可忽略；
- `McpManager.loadSingleConfig`（`/mcp` 详情）维持原样（前例：需要展示时补一行）。

### 6.2 入口捕获与下发（`ChatStreamController` / `AguiRunController`）

新增 `mcp/ChatRequestHeaders`（record：`Map<String,String>`，小写头名 → 值）+ 静态捕获方法：

- 入参：`HttpHeaders` + 名单（`getForwardHeaderNames()`）；只取名单内 header，多值按
  RFC 7230 以 `", "` 连接；
- **限额**：单值 ≤4KB、总量 ≤32KB、头数 ≤32（声明数天然 ≤ 此值，防御配置极端），超限
  drop 该头 + WARN（不报错；调用期 deny 会以只含头名的错误浮出，fail-closed 语义不变）；
- 捕获期再过一遍 denylist（第二道防线，防御名单缓存与 denylist 演进不同步的窗口）；
- **永不打印 header 值**（只记头名与 drop 原因）。

下发改造（`ChatStreamController.java:608`，AGUI `:307` 同型）：

```java
var ctxBuilder = RuntimeContext.builder();
if (!captured.isEmpty()) {
    ctxBuilder.put(ChatRequestHeaders.class, captured);   // typed，仅本轮存活（R5）
}
var inbound = InboundMessage.dm(currentChatChannel.channelId(), finalSessionId, messages)
    .withRuntimeContext(ctxBuilder.build());
currentChatChannel.dispatchStream(inbound);               // R1/R2：与 sendStream(withPeer) 同路由
```

- 统一单路径（captured 为空也走 `dispatchStream`）：路由代码与现路径逐字节相同（R1），
  `channelId()` 用当前 channel 实例的值，`InboundMessage.dm` 对 messages 的非空校验与
  `ChatUiRequest` 一致；
- 排队 turn（`Flux.create` 回调在 boundedElastic 执行）不受影响：捕获在控制器方法体内
  同步完成，经闭包随行；
- **不采用 `sendStream(SendOptions, List<Msg>)` 重载**：其内部 `buildContextFromOptions`
  与现路径的 `buildInbound`+`router.resolveRoute` 是两套 MsgContext 构造，主对话路径不
  值得为它重验 PER_PEER 会话键推导等价性（决策记录，见 §11 Q5）。

### 6.3 middleware 扩展（`McpUserContextMiddleware`，唯一注入点不变）

`onAgent` 在既有 userId 逻辑之后追加：

```java
var reqHeaders = ctx.get(ChatRequestHeaders.class);
if (reqHeaders != null && !reqHeaders.headers().isEmpty()) {
    var entries = new LinkedHashMap<String, Object>();
    reqHeaders.headers().forEach((name, value) -> entries.put("reqHeader:" + name, value));
    ctx.put(McpMeta.class, McpMeta.merge(existing, new McpMeta(entries)));
}
```

- merge 语义保留 userId 与其他既有 entries；无 attribute 的链路（A2A/invoke/子 agent，
  §8）零变化；
- `reqHeader:` 命名空间与用户自定义 metaKey 冲突概率可忽略（约定文档化）。

### 6.4 wrapper：常驻化 + 注入 + `_meta` 剥离（`UserScopedMcpClientWrapper` / `McpToolRegistrar.buildClient`）

1. **常驻化**：`buildClient` 对**全部传输**（sse/streamableHttp/stdio）**一律**装饰
   wrapper（rule 允许为空；stdio 用 EMPTY rule，仅做 `_meta` 剥离、无 header 注入，
   issue #225——HTTP server 声明的透传凭据随 per-turn 全 server 共享 McpMeta 同样会
   漏进 stdio 子进程的 `CallToolRequest._meta`）。
   原因：McpMeta 是 per-turn 全 server 共享——A server 声明的透传值若不剥离，会经
   `McpTool` 进 **B server**（含未配 userHeaders 的 server）的 `CallToolRequest._meta`
   （§2 出口侧事实）。常驻装饰让剥离成为统一卫生行为；空 rule 时 resolve 为空 → 纯
   passthrough，行为与今天完全一致。**判据必须是传输类型而非「rule 非空」**：现有
   `parseUserHeaders` 对「无 userHeaders 节」返回 null（`McpToolRegistrar.java:999`），
   该语义保留（null ≠ 空 rule），buildClient 将 null 归一为空 rule 传入——实现时若
   沿用「rule != null 才装饰」的旧判据，剥离会漏未配置 server；
2. **剥离**：callTool 转交 delegate 前，对 meta 做过滤拷贝移除全部 `reqHeader:*` 键——
   透传值只走 HTTP header 单通道（userId 的 `_meta` 双通道语义**不变**）；
3. **注入**：resolve() 逻辑零改动（`reqHeader:*` 本就是普通 meta key 查找），值合法性
   校验（CRLF/控制字符/非 ASCII）与 fail-closed 语义沿用——捕获值源自真实 HTTP 请求，
   Tomcat 已保证传输层合法性，此处为纵深防御；
4. `McpUserHeaderCustomizer` 零改动；`listTools`/`initialize` 仍走静态凭据（deer-flow
   语义不变）。

### 6.5 HITL confirm 恢复（必须同期，否则挂起恢复的工具调用断 header）

沿用 mcp-user-scoped-headers-plan §5.3.4 既定模式（confirm 是在线请求，重新捕获、不存历史）。
**恢复入口有两个，捕获必须双侧覆盖**：

- **native 面**：`ConfirmController` 两个端点（`:126`/`:213` 已有 `X-User-Id` 先例）按同一
  `ChatRequestHeaders.capture` 捕获；
- **AGUI 面**：`AguiRunController.resumeRun`（`:339`）——AGUI 有独立 HITL 恢复入口
  （`POST` run 请求体 `resume[]` 非空即恢复轮，`:404` 调 `resumeWithConfirmEvents`），与
  ConfirmController 同源 confirm_context（双面互认）。**Q3 若决议 AGUI 同期，本入口捕获
  必须同期**，否则 AGUI 恢复轮的工具调用断 header；
- 两个入口捕获后传入 `resumeWithConfirm / resumeWithConfirmStream / resumeWithConfirmEvents`
  （新增可空参数，null = 行为不变）；
- `buildResumeContext`（`AgentRuntimeService.java:723`）非 null 时追加
  `.put(ChatRequestHeaders.class, ...)`；
- **明确不采用**：header 快照落 `confirm_context` 表——违反「凭据不落表」红线
  （mcp-user-scoped-headers-plan §6）；
- 多副本无粘性下，恢复副本必然是「confirm 请求到达的副本」，捕获天然可用（e2e R 组
  拓扑回归保障）。

## 7. 安全设计

| 项 | 设计 |
|----|------|
| 信任模型 | 与 `X-User-Id` 同款：网关可信、直连可伪造，不新增风险面（前设计 §6 结论延续） |
| 捕获最小化 | **按声明捕获**（已确认决议）：未声明的头一个字节都不进进程内上下文；denylist 解析期剔除 + 捕获期二道防线 |
| 不落盘 | header 值仅存于 RuntimeContext typed attribute（R5：attributes 不持久化）+ 进程内闭包；不进 Msg.metadata、不进任何表、不进 Redis |
| 不进日志 | 捕获/注入全链路只记头名；错误信息沿用「只含 key 名不含值」约束（`McpUserHeaderException` 现状） |
| `_meta` 隔离 | `reqHeader:*` 恒剥离（§6.4）：透传值只走 HTTP header；userId 双通道不变；其他 server（含未配置者）的 `_meta` 不受污染 |
| 平台凭据防误转 | denylist 挡 `cookie`（平台会话凭据）；**`Authorization` 无法 deny（恰是主用例）**——若部署把平台自身认证放在 `Authorization`（如 ROUTER_AUTH_MODE=enforce 的 auth_request 凭据），声明转发即转发平台凭据。评审点 Q1：网关侧平台认证改独立头，或文档强声明「转发 Authorization = 包作者显式行为」 |
| fail-closed | `on-missing: deny` 缺省：请求未带声明头 → 该 MCP 调用失败（错误只含头名），绝不静默回退静态发现凭据 |
| 限额 | §6.2 三重限额，超限 drop + WARN |

## 8. 边界与已知限制（文档须明示）

1. **A2A / invoke / 远程协议 / 进程内子 agent（`runSubagentStream` 不带 callerContext）的
   turn 无 `ChatRequestHeaders`** → 声明了 `reqHeader:*` 的 server 在这些 turn 上
   **fail-closed 可达**（与 userId 时代「deny 不可达」不同）。受影响部署对该 server 用
   `passthrough`，或后续版本给 A2A/invoke 补捕获链路（HarnessAgentRunner /
   `RuntimeContext.builder()` 构造点各一处，机制同型）；
2. **会话标题生成 / 记忆 flush / 压缩**为直调 model，不触 MCP，无影响；
3. **跨副本 confirm 恢复**依赖 confirm 请求自身携带头（在线请求语义，§6.5）；
4. **排队 turn 的 header 时效性**：捕获发生在请求时刻，turn 可能在租约队列等待最长
   `ACQUIRE_TIMEOUT`（120s）后才执行——短时效凭据（如短 TTL JWT）可能已过期。
   **header 时效由调用方保证，框架不做刷新/重取**（不做与「凭据不落盘」冲突的缓存续期）；
5. `McpResourceProxy` / `buildSyncClient` 资源代理链路维持服务级凭据（与前设计 §8 边界
   一致，后续项）；
6. reload 语义：名单随 `reloadMcpAll` 即时更新，**已排队 turn 用旧名单、下一 turn 用新
   名单**（与 reload「生效=下一轮对话」总语义一致）。另注意 **watchdog 的隐式刷新**：
   连接失联自愈/补偿重建会重读磁盘 config.yaml 并按 `registerBuiltClient` 重装缓存——
   存在「未显式 reload 但该 server 规则已更新」的中间态；语义无害（同为下一轮生效），
   排障时勿据「没调过 /admin/reload」排除配置变化因素；
7. 请求线程读取名单缓存与 reload 并发时为弱一致（ConcurrentHashMap 逐条遍历）——最坏
   情形丢一个头 → 调用期 deny 暴露，无害，不做额外同步。

## 9. 兼容性影响与回归范围

| 变更 | 影响 | 回归 |
|------|------|------|
| `sendStream(ChatUiRequest)` → `dispatchStream(InboundMessage)` | 路由代码同一条（R1/R2）；新增 runtimeContext 随行 | **e2e 全量核心**（S/F/H/M/A/X 组天然覆盖 PER_PEER 会话键、confirm、durable SSE 续传）+ R 组多副本 |
| 未声明 `reqHeader:*` 的存量包 | 捕获集为空、attribute 为空、middleware merge no-op、wrapper passthrough——**行为逐字节一致** | 冒烟 + 既有 userHeaders 单测 |
| 声明 `reqHeader:*` | 本次 turn 的工具调用按 on-missing 语义注入 | 新增单测/E2E（§10 T6） |
| wrapper 常驻化 | 空 rule 走 passthrough 分支，行为一致；`_meta` 对存量包多一次过滤拷贝（透明） | 既有 wrapper 单测全量重跑 |
| confirm 链路加参 | 新参数可空，旧调用点（A2A forwardEvent 路径）传 null 零变化 | HITL H 组 + R 组跨副本恢复 |

无 backend / 平台侧改动（配置在 OAF 包内，发布链路零感知）；无 DB 迁移。

## 10. 实施拆分

| # | 任务 | 验证 |
|---|------|------|
| T1 | `ChatRequestHeaders`（捕获/denylist/限额/多值合并/lowercase 归一） | 单测：denylist 各分支、超限 drop、多值合并、空名单 |
| T2 | registrar：`userHeaderRules` 缓存 + `parseUserHeaders` 扩展（`reqHeader:` 归一 + denylist 剔除）+ `getForwardHeaderNames()` | 单测：解析归一、denylist 剔除 WARN、stdio 忽略、clearServer/reload 缓存一致 |
| T3 | ChatStream/AGUI 切 `dispatchStream(InboundMessage)` + typed 注入 | 单测 + e2e 全量核心 + R 组（路由等价性） |
| T4 | middleware 扩展（`reqHeader:*` 合并） | 单测：与 userId 共存、无 attribute 零变化 |
| T5 | wrapper 常驻化 + `reqHeader:*` 剥离 + 注入 | 单测：注入/剥离/passthrough/`_meta` 无 reqHeader/空 rule 等价 |
| T6 | confirm 捕获 + resume 链路加参 + `buildResumeContext` 注入（native 面 `ConfirmController` + **AGUI 面 `resumeRun`**，随 Q3 决议） | H 组 HITL + R 组跨副本恢复（断言 mock 实收 header） |
| T7 | e2e 新增：chat 带 `Authorization` 实收断言 / 缺头 deny / `passthrough` / 未声明包零影响 / **`CallToolRequest._meta` 不含 `reqHeader:*`（剥离断言，含未配置该头的第二 server）** | 复用 bench mock `/last-call` 观测面（mcp-user-scoped-headers T8 先例） |
| T8 | 文档：本设计状态列、`AGENTS.md`（McpToolRegistrar 节 + 环境变量无新增）、`oaf-specification.md`（`reqHeader:` 命名空间）、`api.md`（chat 请求头契约） | — |

## 11. 开放问题（评审确认点）

| # | 问题 | 建议 |
|---|------|------|
| Q1 | `Authorization` 转发与平台自身认证头的关系（ROUTER_AUTH_MODE=enforce 部署形态） | 网关侧平台认证改独立头，或 `api.md` 强声明「转发 Authorization = 包作者显式行为」；不阻塞实施 |
| Q2 | `reqHeader:*` 是否需要 `_meta` 双通道选项（某些下游只读 `_meta`，如经 stdio 桥接的 server） | 默认 header-only（剥离）；有真实诉求再加 `userHeaders.forward-meta: true` |
| Q3 | AGUI 入口是否同期 | 建议同期（同型改动，§6.2 + **§6.5 的 `resumeRun` 捕获——两者必须成对**，只改 send 不改 resume 会让 AGUI 恢复轮断 header）；要收窄首期则 send 与 resume 一并后置为单独提交 |
| Q4 | denylist 是否需要 env 可扩展（部署侧想再挡自定义内部头） | 本期代码固化；出现真实诉求再加 `MCP_FORWARD_DENYLIST` env |
| Q5 | 下发通道选型（决策记录）：`dispatchStream(InboundMessage)` vs `sendStream(SendOptions)` | **已定前者**——R1/R2 路由等价可证；后者需重验 PER_PEER 会话键推导等价性，收益为零 |
| Q6 | 捕获名单是否随包声明变化即时生效 | reload 语义已覆盖（§8.5），无需额外机制 |

## 12. 实施记录（2026-11-02）

**验证结果**：单测全量 1679 例 / 0 失败 / 9 跳过（docker maven 离线镜像内实跑）；本地 e2e
core 组（api-core + api-models + api-reload）**75 passed / 1 skipped**（含新增 S5b）；
kind 集群部署冒烟全链路通过——`agent-framework:smoke-header-forward` 镜像 Pod（集群内
oaf-mysql Flyway 14 迁移 + oaf-redis）经 port-forward 发起携带
`Authorization: Bearer …` 的 chat，下游 bench mock `/last-call` 实收
`authorization` 原值、`xUserId` 反查值、`_meta` 仅含 `userId`（剥离生效）。

**落地差异（相对本文设计）**：

1. **T7 形态**：S5b 落在 `api-core.spec.ts`（S5 扩展位）；bench mock `/last-call` 新增
   `authorization` 字段；bench fixture 增加 `Authorization: reqHeader:authorization` 映射并把
   `on-missing` 显式设为 `passthrough`（S5 主流程不携带 Authorization，deny 语义由单测覆盖）；
2. **测试桩机械迁移**：`ChatStreamControllerTest` 40+ 处 `sendStream(any(ChatUiRequest))`
   → `dispatchStream(any(InboundMessage))`，并补 `channelId()` 打桩（`InboundMessage`
   构造要求 channelId/peer/messages 非空——mock channel 缺省 null 会 NPE）；
3. **confirm 链路**：`resumeWithConfirm` / `resumeWithConfirmEvents` 各加 4 参重载（3 参
   委托 null，存量调用/测试零改动）；`resumeWithConfirmStream` 无调用方未加重载，
   `buildResumeContext` 直改 4 参签名；
4. **缓存装载**：`loadUserHeaderRule` 装载点在 `registerOne` / `registerBuiltClient`
   （§6.1 修正案），并 null-guard 无 config.yaml 场景；测试桥 `registerOneForTest`
   同步装载保持保真；
5. **文档**：`docs/oaf-specification.md` 一并补齐 PR #9 未入平台规范的 `userHeaders` 节
   （含 reqHeader 取值源）；`api.md` 增补 chat 请求级透传契约；`AGENTS.md` 同步。

**issue #225 追记（2026-11-04）**：`buildClient` 的 stdio 早退分支漏装饰——HTTP server
声明的透传凭据随 per-turn 全 server 共享 McpMeta 漏进 stdio 子进程的
`CallToolRequest._meta`，违背 §6.4「剥离是全 server 卫生行为」的既定口径。修复：删除
早退分支，stdio 与 HTTP 传输同样常驻装饰（`UserHeaderRule.EMPTY`，仅 `_meta` 剥离、
无 header 注入，initialize/listTools/getCachedTool/isInitialized/close 全部委托透传，
stdio 连接生命周期零变化）；§6.4 第 1 条同步改为「全部传输一律装饰」。Q2 决议不变：
仍 header-only（剥离），`userHeaders.forward-meta: true` 后门未启用。

### 11.1 被否方案记录（决策依据）

| 方案 | 否因 |
|------|------|
| 全量捕获（denylist 过滤）+ wrapper 按需取 | 进程内携带未声明数据，违背最小化；已由需求方决议为按配置捕获（2026-11-02） |
| header 值放 `Msg.metadata` | metadata 随消息持久化进 checkpoint/agent_state，违反「凭据不落盘」红线（前设计 §6） |
| `TurnUserTokenStore`（turn 级 Redis store，前设计 §5.3.3 后备设计） | side-channel + TTL/清理 + 跨副本一致性问题；SDK 已有 in-band 通道（R1-R4），必要性消失 |
| `ChannelRuntimeContextResolver`（网关级钩子） | 钩子同样拿不到 HTTP 请求，仍需 side-channel 供值；相比入口随行无增益 |
| ThreadLocal / controller 侧 `contextWrite` | agent 执行流由 gateway 自行订阅（`withGatedStream`），入口线程局部变量与 Reactor Context 均不可达 |
