# MCP 多租户按用户调用设计(user-scoped headers + `_meta` 双通道)

| 项 | 值 |
|----|----|
| 编制时点 | 2026-09-21 |
| 状态 | **已实施(2026-09-21,feat/mcp-user-scoped-headers)**:T1/T2/T3/T5 落地(T4 随 Q1 延后、T6 按 Q6 撤销);单测 728 全绿、e2e core/sandbox/multi 三组全绿、kind 集群部署验证通过 |
| 评审决议 | 2026-09-21 全部议定:**Q1** 本期不实施 `X-User-Token`,仅映射身份头(`X-User-Id` ← userId),token 获取层与 Authorization 映射整体延后;**Q2** `_meta` 不排除敏感 key,McpMeta entries 全量随 `_meta` 下发,不引入 `meta-exclude` 配置;**Q3** A2A 链路纳入本期(userId 语义经 middleware 单点天然覆盖,token 形态随 Q1 延后);**Q5** 无需决策(本期 deny 不可达,维持默认);**Q6** 注入收敛到 middleware 单点,方案甲(四处直注)不采用。Q4 为实施期验证项(T5 实测)。详解见 §10.1/§10.2 |
| 现状核对 | 本文所有"项目现状"断言基于 2026-09-21 master(e91d1f0)代码核对;agentscope 2.0.3 / mcp-core 0.17.x 结论来自源码与反编译验证,验证方式见 §2.3 |
| 关联文档 | [tool-system-improvement-plan.md](tool-system-improvement-plan.md)(McpToolRegistrar 原生化)、[multi-tenancy-improvement-plan.md](multi-tenancy-improvement-plan.md)(IsolationScope.USER) |

---

## 1. 背景与目标

平台业务 Agent 通过 MCP 调用下游服务。当前所有 MCP 调用使用**服务级静态凭据**
(`config.yaml` 的 `auth.token`,启动时固定注入 `Authorization` header),多租户场景下
无法区分"哪个用户发起的调用":下游网关/服务无法按用户鉴权与审计,也无法实现
per-user 配额或数据隔离。

**目标**:同一条 MCP 连接上,每次工具调用按发起用户携带身份——

1. HTTP header 通道:`X-User-Id` / `Authorization` 等 header 按调用注入(下游网关鉴权);
2. 协议 `_meta` 通道:userId 等上下文进 `CallToolRequest._meta`(下游 MCP server 直读);
3. 配置声明式:业务包 `config.yaml` 内声明映射,不新增配置文件;凭据 fail-closed。

**非目标**(见 §8):MCP 资源代理(`McpProxyController`)per-user 化、stdio server、
token exchange、平台用户认证体系本身。

## 2. 调研结论(设计依据)

### 2.1 deer-flow(bytedance/deer-flow,master 2026-09)

多租户 MCP 是其一等能力,核心机制(源码 `backend/packages/harness/deerflow/mcp/`,
文档 `backend/docs/MCP_SERVER.md`):

| 机制 | 语义 |
|------|------|
| `headers_from_context` | 配置声明 `header 名 → 请求 secrets key` 映射;调用方每次 run 请求带 `config.context.secrets`,拦截器 per-call 重写 header |
| `user_auth` | 运营方配置 `用户 → 凭据` 映射;每次调用按五级解析链取 userId 查表注入 |
| 自定义拦截器 | `mcpInterceptors` 注册 Python 拦截器,per-call 改写请求 |
| 优先级 | 静态 headers < oauth < user_auth < headers_from_context(最具体者胜) |
| 安全语义 | 静态 headers 仅用于启动 tools/list 发现;`on_missing: deny` 默认 fail-closed;换行/首尾空白/非 ASCII 值一律拒绝(防凭据经错误信息泄漏);secrets 不进 prompt/trace/持久化 |
| 限制 | 仅 http/sse 传输;后台任务的状态轮询(脱离原 run)回落服务级凭据 |

本设计移植的是 `headers_from_context` 的语义(凭据由**调用方**而非运营方决定,贴合
平台"网关透传用户身份"的现有模型)。

### 2.2 agentscope-java(项目在用 2.0.3)

| 能力 | 位置(上游源码) | 说明 |
|------|----------------|------|
| `McpMeta` → `_meta` | `agentscope-core/.../tool/mcp/McpMeta.java`、`McpTool.java:211-219` | 注册进 `RuntimeContext` 的 `McpMeta` 在**每次工具调用**时自动提取,连同 `io.agentscope/toolCallId` 一起写入 `CallToolRequest` 的 `_meta`;`RuntimeContext` attributes 不持久化 |
| RuntimeContext 可运行时补写 | `RuntimeContext.java:180` `put(Class<T>, T)` | javadoc 明示 "Hooks and tools may read and update the same instance"——middleware 可在 `onAgent` 时补写 McpMeta |
| middleware 钩子 | `MiddlewareBase.onAgent(Agent, RuntimeContext, ReasoningInput, AgentChain)` | 与现有 `SandboxUserKeyMiddleware` 同模式(读 `ctx.getUserId()` 注入 ThreadLocal) |
| per-request HTTP 定制 | `McpClientBuilder.httpRequestCustomizer()`(2.0.0+) | 每个 HTTP 请求回调 `(HttpRequest.Builder, method, uri, body, McpTransportContext)` |
| client 可装饰 | `McpClientWrapper` 抽象类;`Toolkit.registerMcpClient(McpClientWrapper)` 接受任意实现 | 装饰器方案不需要改 agentscope 源码 |

### 2.3 MCP Java SDK(mcp-core 0.17.x,项目 `.m2` 在用 0.17.0/0.17.2)

**关键验证**(反编译 `HttpClientStreamableHttpTransport` 确认):transport 的
`sendMessage` 内部执行 `contextView.getOrDefault(McpTransportContext.KEY, EMPTY)` ——
**从 Reactor Context 读取 `McpTransportContext`**(KEY 常量值 `"MCP_TRANSPORT_CONTEXT"`)
后作为参数传给 request customizer。即:在调用链上 `.contextWrite()` 写入该 key,本次
调用的数据即可到达 HTTP 层,**无需 ThreadLocal**。SSE transport 引用同一机制。

**限制验证**:`McpSyncClient` 以 `block()` 桥接 async 流,Reactor Context 在 block 处
断链——**per-call header 通道要求 `McpClientBuilder.buildAsync()`**,`buildSync()` 不通。

### 2.4 项目现状(锚点)

| 现状 | 位置 |
|------|------|
| `X-User-Id` 由外部网关注入,框架直接信任;请求体 `userId` fallback;`debug-user` 兜底 | `UserIdHeaderFilter.java`、`ChatStreamController.java:186-189` |
| A2A/invoke 链路 `RuntimeContext` 由我方构建(userId/sessionId 已有) | `AgentRuntimeService.java:125-128、153-156`、`HarnessAgentRunner.java:46-52` |
| Channel 主链路(/threads/chat)经 `ChatUiChannel.sendStream(ChatUiRequest.withPeer(...))`,**无 context 透传字段**(2.0.3 实测仅 peerId/agentId/subagentId/messages),`RuntimeContext` 由 harness 网关内部构建 | `ChatStreamController.java:308` |
| confirm 恢复时 userId 从 `session_user` 表反查,不依赖原请求 | `ConfirmController.java:96`、`AgentRuntimeService.buildResumeContext:544` |
| MCP client 构建:`config.yaml` 的 `connection` + `auth.token`(静态 Authorization),`buildSync()` | `McpToolRegistrar.buildClient:681-732` |
| middleware 注入 RuntimeContext 先例 | `SandboxUserKeyMiddleware.java`(onAgent 读 `ctx.getUserId()`) |
| 跨副本存储可用(oaf-redis,session_event 在用) | 基础设施表 |

## 3. 方案总览

一次 `agent.call` 同时走两条通道,**同一份 `McpMeta` 数据,声明一次,双通道生效**:

```
网关(注入 X-User-Id;X-User-Token 本期不做,§10 Q1 决议)
        │
ChatStreamController / A2AController(invoke)
        │  解析 userId(PathSafe.sanitize;本期无 token)
        ▼
┌─ 注入层(Q6 已决议:middleware 单点,覆盖含 A2A 在内的全链路)─┐
│ McpUserContextMiddleware.onAgent → ctx.put(McpMeta)              │
│   取值:ctx.getUserId();Channel 链路该值是会话 id(peer),经        │
│   session_user 表反查真实 userId(Q4 实施期结论);A2A/invoke 原值  │
│   token 启用时才引入 TurnUserTokenStore                          │
└──────────────────────────────────────────────────────────┘
        │  agent.call(msgs, ctx)
        ▼
McpTool(agentscope):每次工具调用提取 McpMeta → callTool(name, args, meta)
        ├──► _meta 通道:meta 原样进 CallToolRequest._meta(下游 server 读 userId)
        ▼
UserScopedMcpClientWrapper(装饰器,deer-flow headers_from_context 语义)
  按 config.yaml userHeaders 映射:header 名 ← meta key
  缺值 → fail-closed 拒绝;非法值(换行/空白/非 ASCII)→ 拒绝
  命中 → contextWrite(MCP_TRANSPORT_CONTEXT → 本次 header 集)
        ▼
SDK transport sendMessage:从 Reactor Context 取 header 集 → customizer setHeader
        ▼
下游网关/服务看到 per-call 的 X-User-Id / Authorization(覆盖发现用静态凭据)
```

静态 `auth.token` 语义不变:**仅用于连接初始化与 tools/list 发现**(对齐 deer-flow:
发现凭据与用户凭据分离,建议发现用只读低权 token)。

## 4. 配置层设计

并入业务包现有 `mcp-configs/{server}/config.yaml`,不新增配置文件:

```yaml
# mcp-configs/{server}/config.yaml(现有文件)
connection:
  type: streamableHttp          # 现有
  url: https://mcp.example.com/mcp

auth:                            # 现有:静态发现凭据(建议只读低权)
  token: ${MCP_DISCOVERY_TOKEN}

userHeaders:                     # ★ 新增节:per-call 用户 header 注入
  headers:
    X-User-Id: userId            # header 名 ← McpMeta entries 的 key;本期仅此映射(Q1 决议)
    Authorization: user_token    # token 启用后(Q1 解冻)再加,本期不配
  on-missing: deny               # 缺省 deny;passthrough 显式回退静态凭据
```

规则:

- `headers` 空或无 `userHeaders` 节 → 该 server 行为完全不变(装饰器不启用);
- `stdio` 传输声明 `userHeaders` → 启动告警并忽略(无 HTTP header,同 deer-flow);
- `on-missing: deny`(默认):映射的 key 在本次调用 meta 中缺失/空 → 工具调用失败,
  错误信息**只含 key 名不含值**(模型可见的错误里泄漏凭据是 deer-flow 明确防的路径);
- `Authorization` 等 header 名大小写不敏感替换(用 `setHeader` 而非 `header` 追加)。

## 5. 详细设计

### 5.1 装饰器 `UserScopedMcpClientWrapper`(核心)

新增 `io.agentmanager.framework.mcp.UserScopedMcpClientWrapper extends McpClientWrapper`,
装饰 `McpToolRegistrar.buildClient` 的产物:

```java
@Override
public Mono<McpSchema.CallToolResult> callTool(
        String toolName, Map<String, Object> arguments, Map<String, Object> meta) {
    if (headerToMetaKey.isEmpty() || meta == null || meta.isEmpty()) {
        return delegate.callTool(toolName, arguments, meta);
    }
    Map<String, String> resolved = resolve(meta, toolName);   // fail-closed 校验,见下
    if (resolved.isEmpty()) {
        return delegate.callTool(toolName, arguments, meta);  // passthrough 路径
    }
    McpTransportContext tc = McpTransportContext.create(Map.of(HEADER_KEY, resolved));
    return delegate.callTool(toolName, arguments, meta)       // meta 原样下传,_meta 通道不受影响
            .contextWrite(ctx -> ctx.put(McpTransportContext.KEY, tc));
}
```

`resolve()` 校验(对齐 deer-flow fail-closed 语义):

- 映射 key 缺失或空值:`deny` → 抛异常(错误含 key 名与 server 名,不含值);
  `passthrough` → 跳过该 header;
- 值含换行/首尾空白/非 ASCII:**无论 on-missing 一律拒绝**(此类值会导致 transport
  抛错并可能回显完整值进模型可见的 tool 错误);
- `initialize()` / `listTools()` 直接委托 → 发现阶段走静态凭据,不注入。

### 5.2 HTTP 注入 `McpUserHeaderCustomizer`

`buildClient` 构建 HTTP 类 transport 时固定注册:

```java
builder.httpRequestCustomizer((b, method, uri, body, transportContext) -> {
    if (transportContext.get(UserScopedMcpClientWrapper.HEADER_KEY) instanceof Map<?, ?> hs) {
        hs.forEach((k, v) -> b.setHeader((String) k, String.valueOf(v)));  // setHeader=替换
    }
    // 无值(初始化/tools/list/passthrough)→ 不动,静态 header 生效
});
```

### 5.3 身份获取与注入链路(token 采用方案 1:请求头透传)

#### 5.3.1 获取层

- `X-User-Id`:现状不动(`UserIdHeaderFilter`);
- `X-User-Token`:**延后实施(Q1 决议,2026-09-21)**——本期仅映射身份头。启用时
  同一信任模型(网关注入,框架不解析只透传);仅接受 header、**不接受请求体字段**
  (避免 body 进访问日志/异常信息);只做非空与合法性校验、不 sanitize(sanitize
  会破坏 JWT 一类值的字符)、不落任何日志。本节及后文 token 相关内容均为启用时的
  既定后备设计。

#### 5.3.2 A2A / invoke 链路(方案甲,**Q6 决议不采用**;保留为决策记录,见 §10.2)

`AgentRuntimeService.invoke/invokeStream`、`buildResumeContext`、`HarnessAgentRunner`
四处构建 `RuntimeContext` 处统一改为:

```java
RuntimeContext.builder()
    .sessionId(fullThreadId)
    .userId(resolvedUserId)
    .put(McpMeta.class, new McpMeta(mcpUserContext.asMap()))   // userId / user_token
    .build();
```

token 来源(启用时):入口(`ChatStreamController` 之外的 invoke/A2A 入口)透传到 service 层。

#### 5.3.3 Channel 主链路(/threads/chat,middleware 注入)

`ChatUiRequest` 无透传字段(§2.4),`RuntimeContext` 由 harness 网关内部构建——采用
**middleware 补写**(先例:`SandboxUserKeyMiddleware`),实现见
`io.agentmanager.framework.mcp.McpUserContextMiddleware`:

- **userId 解析(实施期结论,原 Q4)**:Channel 链路 `ctx.getUserId()` = 网关 peer =
  会话 sessionId(项目实测记录:`AgentRuntimeService:582-605`),故按 `session_user` 表
  反查真实 userId(`SessionUserStore.findUserIdBySession`,与 `ConfirmController:96`
  同款);反查未命中(A2A / invoke 链路,userId 即真实用户)或反查异常时回落原值,
  反查失败 warn 不阻断调用;
- **写入语义**:`McpMeta` 合并——保留调用方已有的其他 entries,`userId` 以本次生效值为准;
- **token 启用时的后备设计**:`TurnUserTokenStore`(Q1 决议后本期不需要)——chat 请求
  进入时按 session 存 token(TTL ≈ turn 上限 + 冗余,turn 收尾 best-effort 清除;
  跨副本用 oaf-redis 兜底),middleware 反查 userId 时一并取出;
- 签名以 2.0.3 为准:`onAgent(Agent, RuntimeContext, AgentInput,
  Function<AgentInput, Flux<AgentEvent>> next)`。

middleware 注册一次对**所有链路生效**(Q6 决议:**唯一注入点**,方案甲不采用)。
A2A 链路的 userId 已由 `HarnessAgentRunner:46` 写入 `RuntimeContext`(经 A2A
params → message.metadata → options.getUserId(),`A2AController:130-154`),middleware
直接读到,无需 A2A 侧任何改动——Q3 决议"A2A 纳入本期"的依据。

#### 5.3.4 HITL confirm 恢复

confirm 是新的 HTTP 请求,网关会再次注入同用户的 `X-User-Token`——
`ConfirmController` 侧在 `resumeWithConfirm` 前按 §5.3.2/§5.3.3 同源逻辑重新注入,
无需存历史 token(与 deer-flow "后台轮询回落服务级凭据"不同,我们 的 confirm 是
在线请求,天然可拿到当次凭据)。

### 5.4 `buildClient` 改造(`McpToolRegistrar.java:681-732`)

- 解析 `userHeaders` 节 → 构造 `UserHeaderRule(headers, denyOnMissing)`;
- HTTP 类 transport:注册 customizer + `buildAsync().block()` + 装饰;
- **`buildSync()` → `buildAsync().block()` 迁移**:**前置条件**(§2.3,Reactor Context
  在 sync 桥接处断链)。未配置 `userHeaders` 的 server 也一并迁移(统一行为),影响
  见 §7;
- `McpManager.loadSingleConfig` 不动(已透传 `connection`;详情页需展示时补
  `config.put("userHeaders", data.get("userHeaders"))` 一行)。

## 6. 安全设计

| 项 | 设计 |
|----|------|
| 信任边界 | 与 `X-User-Id` 同模型:网关可信、NodePort 直连可伪造。**不新增**风险面,但文档明示;后续可在 ingress 层加签名/校验(§8 后续项) |
| fail-closed | `deny` 默认;缺值/空值/非法值拒绝,绝不静默回退静态发现凭据(否则 A 租户请求以共享身份发出) |
| 凭据防泄漏 | token 不进:请求体、`Msg.metadata`(会随消息进 checkpoint/agent_state)、`session_user` 等任何表、日志、异常信息。已核实 `RequestLoggingFilter` 仅记录 URI/状态码/userId,不落 header 值与 body——保持该约定,禁止将 `X-User-Token` 写入 MDC 或任何日志字段 |
| `_meta` 可见面 | `_meta` 随 JSON-RPC 到下游 server。**Q2 决议:不排除敏感 key**,McpMeta entries 全量随 `_meta` 下发,不引入 `meta-exclude` 配置;下游日志策略由下游自担 |
| 发现凭据分离 | 静态 `auth.token` 建议降为只读发现 token;用户级凭据只经映射注入 |
| 与沙箱隔离正交 | userId 语义与 `IsolationScope.USER`(multi-tenancy-plan)一致,互不依赖 |

## 7. 兼容性影响与回归范围

| 变更 | 影响 | 回归 |
|------|------|------|
| `buildSync` → `buildAsync` | wrapper 类型 `McpSyncClientWrapper` → `McpAsyncClientWrapper`(同父类,`registerMcpClient`/`registerFiltered` 兼容);sync 每调用 block → 真异步,吞吐更好,但错误传播与超时路径语义有差异 | **全量现有 MCP E2E**(核心/沙箱 job 中涉 MCP 的用例);重点:超时、连接失败 fail-soft、HITL 工具确认 |
| 未配置 `userHeaders` | 行为不变(除上述 sync→async) | 冒烟 |
| 配置了 `userHeaders` | 本期(userId-only):deny 不可达——userId 经三级兜底恒有值,直连时下游收到 `debug-user`(详见 §10.1);token 映射启用后,缺 token 才会 fail-closed 失败 | 新增单测/E2E |
| `McpProxyController` 资源代理 | 不动,服务级凭据(§8 边界) | 不涉及 |

## 8. 边界与后续项

**本期边界外**:

1. `McpProxyController` / `McpResourceProxy`(`buildSyncClient` 独立链路,无
   RuntimeContext)——ui:// 资源代理维持服务级凭据,语义同 deer-flow 后台任务;
2. stdio server(无 HTTP header,告警忽略);
3. 平台用户认证体系 / token exchange / ingress 层校验(信任模型不变);

**后续候选**:user_auth 式"平台保管 userId→token 映射"(方案 2)、token 启用时
A2A params 的透传字段形态(Q3 遗留;Q2 已决议不做 `_meta` 排除,不再候选)。

## 9. 实施拆分(评审决议已齐,待排期执行)

| # | 任务 | 验证 |
|---|------|------|
| T1 | `config.yaml` `userHeaders` 解析 + `UserHeaderRule` | 单测(deny 默认/空节/stdio 告警) |
| T2 | `UserScopedMcpClientWrapper` + `McpUserHeaderCustomizer` | 单测:fail-closed 各分支、非法值、`_meta` 不受影响、passthrough |
| T3 | `buildClient` 改造(含 buildAsync 迁移) | 全量 MCP E2E 回归 |
| T4 | ~~获取层 `X-User-Token` + `TurnUserTokenStore`~~ **延后(Q1 决议)**;本期仅核查日志约定(不落 header 值) | — |
| T5 | `McpUserContextMiddleware`(**唯一注入点**,Q6 决议;覆盖 Channel/invoke/A2A/confirm 全链路) | 单测(Channel + A2A 两链路)+ **实测 Channel ctx key(Q4)** |
| T6 | ~~A2A/invoke 链路注入(5.3.2 四处)~~ **已撤销(Q6 决议:middleware 单点,T5 全覆盖;A2A 经 HarnessAgentRunner 已有 userId,零改动)** | — |
| T7 | confirm 恢复注入 | 复用 HITL E2E |
| T8 | E2E:mock 下游 MCP server 断言 per-call header 与 `_meta` | 新增 e2e 用例 |

## 10. 开放问题(评审确认点)

| # | 问题 | 建议 |
|---|------|------|
| Q1 | ~~`X-User-Token` 信任模型:平台当前无真实用户认证,token 的颁发方/校验方是谁?~~ | **已决议(2026-09-21):本期不实施 X-User-Token**,仅映射身份头(`X-User-Id` ← userId);Authorization 映射与 token 获取层整体延后,启用时再定颁发方/校验方 |
| Q2 | ~~`_meta` 是否排除敏感 key(如 `user_token` 只走 header 不进 `_meta`)~~ | **已决议(2026-09-21):不排除**,McpMeta entries 全量随 `_meta` 下发,不引入 `meta-exclude` 配置 |
| Q3 | A2A 链路 user 语义与 token 透传形态 | **已决议(2026-09-21):A2A 纳入本期**——userId 语义经 middleware 单点天然覆盖(`HarnessAgentRunner` 已写 ctx,middleware 直读,A2A 侧零改动);token 透传形态随 Q1 延后 |
| Q4 | ~~Channel 链路 middleware 看到的 `ctx.getUserId()` 实际值~~ | **已解决(2026-09-21 实施期)**:项目内已有实测记录——Channel 链路 RuntimeContext.userId = peer = 会话 sessionId(网关 sessionId 恒为 gw-hash,`AgentRuntimeService:582-605`)。实现改为 middleware 按 **session_user 表反查真实 userId**(`SessionUserStore`,与 ConfirmController 同款),未命中/异常回落原值;e2e S5 以 mock MCP 回读的实收 header 值验证 |
| Q5 | 直连调试与 deny/fail-closed 语义的相互影响 | **详解见 §10.1**。要点:本期(userId-only)deny 实际不可达(userId 三级兜底恒有值),无需现在决策;真正影响出现在 token 启用时 |
| Q6 | 注入点收敛:§5.3.2(四处 RuntimeContext 构建)与 §5.3.3(middleware)并存还是收敛到 middleware 单点 | **已决议(2026-09-21):收敛到 middleware 单点**(论据见 §10.2——Channel 主链路只能走 middleware,两套并存无增量收益) |

### 10.1 Q5 详解:直连调试 × deny/fail-closed

**deny / passthrough 的确切行为**(`UserScopedMcpClientWrapper.resolve`,§5.1):

- 每次工具调用,装饰器按 `userHeaders.headers` 映射从 McpMeta 取值:
  - 值缺失或为空 + `on-missing: deny`(**默认**)→ 本次工具调用失败,错误信息含
    server 名与映射 key 名、不含值;
  - 同样情况 + `passthrough` → 跳过注入,该 header 走静态配置(或不发送)。
- 默认 deny 的动机(fail-closed):防止"映射了用户凭据但本次调用没带"时静默回落
  到共享发现凭据——多租户下即"调用方没带凭据,系统替他用共享身份调了下游"。
  与 deer-flow 默认语义一致。

**Q1 决议后的实际影响——本期 deny 实际不可达**:

- 本期仅映射 `X-User-Id ← userId`;middleware 从 `ctx.getUserId()` 注入,该值经
  `UserIdHeaderFilter.resolveUserId` 三级链(网关 header → 请求体 → `debug-user`)
  **永远非空**;
- 因此每次调用都有 userId,deny 分支不会被触发;
- 真正的行为变化:直连(绕过网关)时下游收到 `X-User-Id: debug-user`——所有直连
  流量共享同一下游身份。与现状(全部流量共享服务级凭据)相比没有变差,且直连本就
  在信任边界内(能直连 = 可信);但若下游按 `X-User-Id` 做数据隔离,`debug-user`
  会成为直连流量的公共桶,需下游侧知悉。

**deny 何时真正生效**:后续启用 `Authorization` / token 映射时(Q1 解冻)。届时
直连且不带 token 的 MCP 调用失败——这才是"直连调试全挂"的原始场景。

**处置选项**:

| 选项 | 说明 | 评价 |
|------|------|------|
| a. 维持 deny 默认(推荐) | 本期无感;token 启用时再定调试方案(显式带 token,或开发包配 `passthrough`) | 不为未启用的功能提前付复杂度 |
| b. 开发/演示包配 `passthrough` | 显式声明"此 server 允许回落" | token 启用后的常规做法 |
| c. `debug-user` 时刻意不注入 meta,触发 deny | 把调试语义耦合进 fail-closed 语义 | 不推荐:deny 错误信息面向最终用户,不应承载调试约定 |

### 10.2 Q6 详解:注入点收敛(四处直注 vs middleware 单点)

**方案甲 = §5.3.2(四处直注)**:在我们自己构建 `RuntimeContext` 的每个入口显式
`put(McpMeta)`:

| 构建点 | 链路 |
|--------|------|
| `AgentRuntimeService.invoke:125` | 单次 invoke |
| `AgentRuntimeService.invokeStream:153` | 流式 invoke |
| `AgentRuntimeService.buildResumeContext:544` | HITL confirm 恢复 |
| `HarnessAgentRunner.streamEvents:46` | A2A |

- 前提:注入值在入口 controller 可得并经方法签名传入(token 启用时 4 个签名都要
  加参数);
- 覆盖面恰好等于这张表——未来新增入口(新 controller / 新 channel)漏写即裸奔,
  deny 语义下表现为该入口 MCP 调用全部失败;
- 优势:每处显式代码,行为可静态读出。

**方案乙 = §5.3.3(middleware 单点)**:一个 `McpUserContextMiddleware` 注册在
HarnessAgent,所有链路统一经过;`onAgent(Agent, RuntimeContext, ReasoningInput,
AgentChain)` 中 `ctx.put(McpMeta.class, ...)`:

- `RuntimeContext` per-call 可变是 SDK javadoc 明示语义("Hooks and tools may read
  and update the same instance"),项目内已有同模式先例 `SandboxUserKeyMiddleware`;
- 数据来源 `ctx.getUserId()` 原生可得——**Q1 决议后(userId-only)连
  `TurnUserTokenStore` 都不需要**,middleware 主体就是一次 put;store 降级为 token
  启用时的后备设计;
- 残留风险即 Q4(Channel 链路 ctx 值待实测);即便实测值不理想,修改点也只有
  middleware 内取值逻辑这一处。

**决定性论据——Channel 主链路只能走乙**:`/threads/chat` 是平台主链路,其
`RuntimeContext` 由 harness 网关内部构建(`ChatUiRequest` 无透传字段,2.0.3 实测),
我方代码没有构建点可 put。因此:

- 甲**天然覆盖不了主链路**,支持主链路必须引入 middleware;
- 若 A2A/invoke 再用甲,即"甲+乙两套并存":规则、单测、排障都要同时考虑两个注入
  点,是维护成本最差的组合;
- 故真实选择是"仅乙" vs "甲+乙并存",后者没有任何乙单独不具备的收益。

**决议(2026-09-21)**:收敛到 middleware 单点(仅乙)。落到实施拆分:T5 全链路
注入即可,T6 撤销,四处构建点维持现状(只设 sessionId/userId,不碰)。
