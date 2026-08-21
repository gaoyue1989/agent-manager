# HITL 人工确认工具调用方案（Permission System 接入）

> **状态: ✅ 已实施（阶段一~四完成，三模式 E2E 验证）**
> 基于 [AgentScope 2.0 Permission System 文档](https://java.agentscope.io/v2/zh/docs/building-blocks/permission-system.html)，
> 为 Agent Framework 接入权限系统，实现「高风险工具调用须人工确认」的 HITL 能力。
> **范围：仅 agent-framework 工程**（权限装配、事件链路、**Debug 页面确认卡片**、确认端点），Go 后端、React 前端本次不变更。
> **A2A 模式结论（2026-08-18 已验证）**：A2A SDK 未桥接 `RequireUserConfirmEvent`（AgentEvent 不进入 `Flux<Event>`），
> A2A 模式**无法支持 HITL**——SDK 设计限制，非本工程 bug。详细分析见 **6.5** 与 **附录 A**。
>
> **架构变更（2026-08-21，stateless-single-stream）**：原长连接 SSE + SessionEventBus 事件扇出模型已移除，
> 替换为单次 SSE 流（POST /threads/{sid}/chat）。确认上下文由内存 `confirmCache` 迁移至 `confirm_context` 持久化表；
> 并发请求通过 `turn_lease` 表串行化。

---

## 一、背景与现状

### 1.1 需求

Agent 工具调用目前**全自动执行**：LLM 决定调用 MCP 写工具等即立即执行，缺少人工把关环节。
对于调用外部 API 等高风险 MCP 操作，需要支持「LLM 请求调用 → 用户确认 → 执行 / 拒绝」的
人工确认（Human-in-the-Loop, HITL）能力。

**作用域界定**：
- **MCP 工具** → 需要权限控制（HITL 人工确认）
- **Agent 自带工具**（Harness 内置约 26 个 + 自定义 @Tool）→ **不参与权限确认**，仅需**可见性控制**（可配置隐藏/排除）

### 1.2 现状分析

| 项 | 现状 | 说明 |
|---|---|---|
| `HarnessAgent` 权限上下文 | ❌ 未装配 | `AgentScopeConfig.java:202-254` builder 无 `.permissionContext(...)` |
| OAF `require_confirmation` | ⚠️ 死配置 | `OafConfigLoader.java:295` 解析 → `OafConfig.java:73`，仅 `DebugApiController.java:119` 展示，未接入运行时 |
| HITL 事件转发 | ❌ 未处理 | `AgentRuntimeService.invokeStream()` 全量转发事件但无 `RequireUserConfirmEvent` 分支 |
| SSE 序列化 | ❌ 未覆盖 | `AgentEventSseSerializer` 无 `RequireUserConfirmEvent` 词条 |
| 前端聊天测试（React） | ⚠️ 范围外 | `agents/[id]/page.tsx` → Go `POST /agents/:id/chat` → A2A `message/send` 同步链路，本次不变更（A2A 调用方的确认交互见第六章 6.5 / 第八章 8.2） |
| Debug 页面（agent-framework 内嵌） | ✅ 范围内 | `static/debug/` 确认卡片 + 事件处理，阶段四实施 |
| 现有 MCP 权限处理 | ⚠️ 反向 | `McpToolRegistrar.java:48` 对 `permissions.read_only` 强制注册只读以**绕过**服务端拦截 |

### 1.3 现有链路（agent-framework 内，stateless-single-stream 架构）

```
调用方（A2A message/send|message/stream / Debug 页面 / 其他 HTTP 客户端）
  │
  ▶ A2A: agent-framework A2AController → AgentScopeA2aServer → HarnessAgentRunner.stream → agent.stream
  ▶ Channel: POST /threads/{sid}/chat  (单次流 SSE, TurnLease 串行化)
             → ConfirmContextStore 落库 (permission_ask 时)
             → agent.streamEvents → SSE 事件流一次性返回
```

---

## 二、目标

1. **装配权限系统**：`HarnessAgent` 启用 `PermissionContextState`，**仅对 MCP 工具**生效——`ask/allow/deny` 规则按 MCP 裸名匹配，Agent 自带工具自动放行、不参与确认。
2. **HITL 暂停/恢复**：MCP 工具调用触发 ASK 时 agent 暂停，事件链路向调用方推送待确认信息；用户决策后恢复执行。
3. **确认交互**：Debug 页面展示「待确认工具卡片」，提供 批准/拒绝 操作（agent-framework 内嵌）；A2A 调用方通过协议层推送确认。
4. **自带工具可见性控制**：内置 + 自定义工具通过 OAF `deniedTools` 统一过滤（不注册/不暴露给 LLM），不含权限确认语义。
5. **无人值守兜底**：未接入确认的调用方（如 A2A 无确认能力时）按 `DONT_ASK` 策略降级，保证不阻塞。

---

## 三、AgentScope 权限系统机制速览

### 3.1 决策流程

`io.agentscope.core.permission` 拦截每次工具调用，输出 ALLOW / DENY / ASK 三选一：

```
Tool Call → Deny Rules? → DENY
          → Ask Rules?  → ASK
          → Built-in Checks (危险路径/写操作等, 不可绕过)
          → Allow Rules? → ALLOW
          → Mode 兜底: DEFAULT=ASK / ACCEPT_EDITS=放行文件写 / EXPLORE=拒写 / BYPASS=放行 / DONT_ASK=拒绝
```

### 3.2 HITL 交互协议（关键 API）

**暂停（ASK 命中）**：

| 形态 | API | 说明 |
|---|---|---|
| Blocking `call()` | `Msg.getGenerateReason() == PERMISSION_ASKING`，`Msg.content` 中 `ToolUseBlock.state == ASKING` | 阻塞调用返回 |
| Streaming `streamEvents()` | `RequireUserConfirmEvent.getToolCalls()` → `List<ToolUseBlock>`（含 `getName()`/`getInput()`/`getId()`） | 事件流推送 |

**恢复**：构造 `ConfirmResult(confirmed, toolCall, acceptedRules)` 列表，
放在新 UserMessage 的 `metadata[Msg.METADATA_CONFIRM_RESULTS]` 中再次 `call()` / `streamEvents()`。
流式恢复时事件流先出现 `UserConfirmResultEvent`（`getReplyId()` 关联原 ASK）。

**全部拒绝停止**：装备 `onActing` middleware 观察 `AllToolsDeniedEvent` → `RequestStopEvent`（可选，P2）。

---

## 四、总体架构

### 4.1 架构：stateless-single-stream

原长连接 SSE + `SessionEventBus` 事件扇出模型**已移除**，替换为单次 SSE 流架构：

| 旧架构 | 新架构 |
|---|---|
| `GET /debug/threads/{sid}/events`（长连接 SSE 订阅） | `POST /threads/{sid}/chat`（单次流 SSE） |
| `POST /debug/threads/{sid}/chat`（fire-and-forget 触发） | 合并为 `POST /threads/{sid}/chat`（同步触发+返回 SSE 流） |
| `SessionEventBus` per-session 分桶广播 | 已删除 |
| 内存 `confirmCache`（ConcurrentHashMap） | `ConfirmContextStore` + `confirm_context` 表（MySQL 持久化） |
| 并发无保护 | `TurnLeaseStore` + `turn_lease` 表（串行化并发请求） |
| AGENT_END 时 teardown MCP App 卡片 | AGENT_START 时 teardown（防止 teardown 时机晚于用户点击确认） |

**设计动机**：
- **单次流**天然匹配 HTTP request-response 生命周期，无长连接状态管理开销
- **持久化确认上下文**支持跨请求恢复（重启/故障恢复后 confirm_context 不丢失）
- **Turn Lease**防止同一会话并发请求导致 agent 状态竞态

### 4.2 改造后链路

```
调用方 (Debug 页面 / A2A 客户端)
  │
  ├── Debug 页面: POST /threads/{sid}/chat    (单次流 SSE, 含 permission_ask + confirm-stream)
  │
  └── 确认: POST /threads/{sid}/confirm              (独立端点, 新)
  │          POST /threads/{sid}/confirm-stream       (确认后恢复 SSE)
  │
  └── A2A: POST / (message/send | message/stream, ❌ 不支持 HITL——SDK 限制, 见 6.5)
  ▼
agent-framework
  ├── AgentScopeConfig:  HarnessAgent + permissionContext(permCtx)   ← 新增
  ├── AgentRuntimeService: streamEvents 处理 RequireUserConfirmEvent → permission_ask
  │                        + resumeWithConfirm() / resumeWithConfirmStream() 恢复
  │                        constructor: 5 params (OafConfig, HarnessAgent, List<Map>, LLMLogger, ConfirmContextStore)
  │                                     — 无 SessionEventBus（已删除）
  ├── ConfirmContextStore: confirm_context 表 CRUD（替代内存 confirmCache）
  ├── TurnLeaseStore: turn_lease 表（串行化同一会话并发请求）
  ├── ConfirmController: POST /threads/{sessionId}/confirm + /confirm-stream      ← 新增
  ├── AgentEventSseSerializer: RequireUserConfirmEvent → permission_ask 词条       ← 新增
  └── A2AController: 透传 SDK（❌ HITL 不支持——SDK `Flux<Event>` 契约不含确认事件，见 6.5）
```

### 4.3 确认交互时序（单次流 SSE 链路）

```
前端(Debug页面)              agent-framework               HarnessAgent
 │  POST /threads/{sid}/chat │                              │
 │  (单次流 SSE)             │  TurnLease: 获取 turn       │
 │──────────────────────────►│  agent.streamEvents()       │
 │                           │─────────────────────────────►│ 调用 write_file → ASK
 │                           │  ConfirmContextStore.save()  │ (暂停, 等待恢复)
 │  ◄── permission_ask ──────│  → confirm_context 落库     │
 │  (渲染确认卡片)            │  → SSE 流 complete          │
 │                           │                              │
 │  POST /confirm-stream     │                              │
 │  {tool_call_id, true}     │                              │
 │──────────────────────────►│  ConfirmContextStore.get()   │
 │                           │  resume: 新 streamEvents()   │
 │                           │  + TurnLease 保护            │
 │                           │─────────────────────────────►│ 执行 write_file, 继续推理
 │  ◄─ token/result/done ────│── SSE 事件流一次性返回 ─────►│
 │  (同消息续渲染)            │                              │
```

**确认流程**：`permission_ask` → `confirm_context 落库` → `confirm` 或 `confirm-stream` → `resume`

---

## 五、配置设计

### 5.1 配置设计（MCP 工具三态权限）

**落点：`mcp-configs/{server}/config.yaml` 的 `permissions` 扩展**（OAF 规范明确 `permissions` 为
server-specific 访问控制，语义由 harness 定义；就近绑定工具与所属服务器）。

```yaml
# mcp-configs/{server}/config.yaml
vendor: "block"
server: "filesystem"
version: "1.0.0"

connection:
  type: "sse"
  url: "http://localhost:8811/sse"

permissions:
  read_only: false          # 既有字段：整服务器只读（保留）
  tools:                    # ← 新增：工具级三态（裸名匹配）
    list_directory: allow   #   自动执行（显式声明；缺省即 allow，见下）
    write_file: ask         #   需要人工确认（HITL）
    delete_file: deny       #   一律拒绝（不可绕过，优先级最高）
```

**全局 mode（可选，frontmatter `config.permission.mode`）**：`PermissionContextState` 是 agent 级单例，
mode 为全局属性，不适合放各 server 的 config.yaml。仅在需要时配置（缺省 `default`）：

```yaml
# AGENTS.md frontmatter
config:
  require_confirmation: true   # 兼容字段：true → 全部 MCP 工具 ASK（等价于 tools 全 ask）
  permission:
    mode: default              # default | accept_edits | explore | bypass | dont_ask
```

**映射规则（装配时）**：
1. `permissions.tools` 显式声明（allow/ask/deny）→ 生成对应规则
2. 其余已注册 MCP 工具 → 自动生成 **ALLOW 兜底规则**（不询问、不拒绝，与现状行为一致）
3. `require_confirmation: true` → 全部 MCP 工具 ASK（与显式 tools 规则共存时以显式规则为准，未声明者 ASK）
4. 未配置 `permissions.tools` 且 `require_confirmation` 缺省/false → **不装配权限系统**，零侵入

**作用域约束**：
- 规则只匹配 **MCP 工具裸名**（config.yaml 中与远端工具名一致）；非 MCP 工具名忽略并告警
- **Agent 自带工具（内置 + 自定义）自动生成 `ALLOW` 规则**，任何 mode 下不触发 ASK/DENY；
  显式配置 `EXPLORE`/`DONT_ASK` 时其全局语义仍对自带工具生效（如 EXPLORE 下写操作被拒）
- deny 优先级最高（引擎评估顺序 `Deny → Ask → Checks → Allow → Mode`），`dont_ask` 时 ask 降级 deny

### 5.2 自带工具可见性控制（不含权限确认）

| 工具类别 | 可见性控制 | 现状 |
|---|---|---|
| Harness 内置工具 | OAF `deniedTools` → `tools.json` deny 列表（Harness 侧隐藏，保留全部未列出的） | ✅ 已有（`WorkspaceInitializer.java:92-98`） |
| 自定义 @Tool 工具 | OAF `deniedTools` 过滤注册：`AgentScopeConfig` 注册前剔除命中项 | ⚠️ 需新增（当前无条件注册，`AgentScopeConfig.java:196-199`） |
| MCP 工具 | `ActiveMCP.json` `selectedTools.enabled` 子集过滤 + `permissions.read_only` | ✅ 已有（`McpToolRegistrar`） |

> 可见性 = 工具是否对 LLM 暴露（注册/不注册），与权限确认（运行时拦截）完全解耦。

### 5.3 规则落点

- `McpToolRegistrar`：
  - 新增 `loadToolPermissions(mcp)` → `Map<String, String>`（裸名 → `allow|ask|deny`），复用现有 `loadConfigYaml()`（行 210-223）
  - 新增 `collectPermissionRules()` → 聚合全部 server 的 tools 规则 + 已注册 MCP 工具名集合（ALLOW 兜底用）
- `OafConfig` / `OafConfigLoader`：仅新增 `permission.mode` 解析（缺省 `default`；`require_confirmation` 已有）
- `AgentScopeConfig.harnessAgent()`：`mode` 或 MCP tools 规则存在时装配（数据源：`McpToolRegistrar.collectPermissionRules()`）

---

## 六、agent-framework 改造方案

### 6.1 AgentScopeConfig — 装配权限上下文

**文件**: `src/main/java/io/agentmanager/framework/config/AgentScopeConfig.java`
**位置**: `harnessAgent()` builder 链（行 202-254）

```java
// 在 builder 链中（.compaction(...) 之后、.build() 之前）追加：
var permCfg = mcpToolRegistrar.collectPermissionRules(oafConfig);  // {mode, tools: {name: behavior}, mcpNames}
var requireAll = oafConfig.runtimeConfig().requireConfirmation();  // 兼容：全部 MCP ASK
if (!permCfg.tools().isEmpty() || requireAll) {
    var pb = io.agentscope.core.permission.PermissionContextState.builder()
        .mode(permCfg.mode());   // frontmatter config.permission.mode，缺省 DEFAULT

    // ① Agent 自带工具（内置 + 自定义 @Tool）自动放行：静态内置白名单 + 本次注册的自定义工具名，
    //    为每个生成 ALLOW 规则（覆盖 DEFAULT mode 兜底 ASK）
    //    ⚠️ 内置工具注册发生在 HarnessAgent$Builder.build() 内部（对传入 toolkit 实例执行
    //    registerTool），build 前无法通过 toolkit.getToolNames() 运行时枚举（已验证），
    //    故内置名使用 AgentScopeConfig.BUILT_IN_TOOL_NAMES 静态白名单
    //    （javap 从 agentscope-harness-2.0.0.jar 的 @Tool 注解/AgentTool 实现提取），
    //    build 后由 verifyToolCoverage() 差集校验兜底（名单漂移 → ERROR 日志）
    var builtinNames = new HashSet<>(AgentScopeConfig.BUILT_IN_TOOL_NAMES);
    builtinNames.addAll(customToolNames);                 // 本次实际注册的自定义 @Tool 名（deniedTools 剔除的不在列）
    for (var toolName : builtinNames) {
        if (permCfg.mcpNames().contains(toolName)) {
            continue;   // 与 MCP 重名时以 MCP 规则为准
        }
        pb.addAllowRule(toolName,
            new io.agentscope.core.permission.PermissionRule(
                toolName, null,
                io.agentscope.core.permission.PermissionBehavior.ALLOW, "builtinAutoAllow"));
    }

    // ② MCP 工具：显式规则 + ALLOW 兜底
    for (var name : permCfg.mcpNames()) {
        var behavior = permCfg.tools().getOrDefault(name,
            requireAll ? "ask" : "allow");   // 未声明者：兜底 allow；require_confirmation=true 时 ask
        var rule = new io.agentscope.core.permission.PermissionRule(
            name, null,
            io.agentscope.core.permission.PermissionBehavior.valueOf(behavior.toUpperCase()),
            "projectSettings");
        switch (behavior) {
            case "allow" -> pb.addAllowRule(name, rule);
            case "ask"   -> pb.addAskRule(name, rule);
            case "deny"  -> pb.addDenyRule(name, rule);
        }
    }
    builder.permissionContext(pb.build());
    log.info("Permission system enabled (MCP only): mode={}, rules={}", permCfg.mode(), permCfg.tools());
}
```

**说明**（均基于 javap 验证 agentscope-core-2.0.0.jar / agentscope-harness-2.0.0.jar）：
- `PermissionRule` 是 Record：`PermissionRule(String toolName, String ruleContent, PermissionBehavior behavior, String source)`，
  第 2 参数 `ruleContent` 为正则匹配模式（传 `null` 表示精确匹配工具名）
- `PermissionBehavior` 枚举有 4 值：`ALLOW` / `DENY` / `ASK` / `PASSTHROUGH`
- 规则顺序符合引擎评估：`Deny → Ask → Checks → Allow → Mode`，同一工具被多条规则命中时 deny > ask > allow，
  故 `write_file: ask` 与兜底 allow 共存时 ask 必然命中（allow 兜底不覆盖 ask）
- ✅ **内置工具 ALLOW 规则已覆盖**：静态白名单 `BUILT_IN_TOOL_NAMES`（25 个注册名，javap 提取自
  `@Tool` 注解/`AgentTool` 实现，含 plan/skill/子 Agent 等条件工具）+ 构建后 `verifyToolCoverage()`
  差集校验（未覆盖工具 → ERROR 日志）——**彻底消除 `DEFAULT` mode 下自带工具意外 ASK 的风险**；
  SDK 升级导致名单漂移时由启动日志兜底发现
- ⚠️ **shell 工具注册名是 `execute` 而非 `shell_execute`**：`ShellExecuteTool` 的 `@Tool` 注解无显式
  name，注册名取方法名 `execute`（与 `Toolkit.registerToolMethod` 派生规则一致，已实测验证）
- ⚠️ 自带工具白名单与 `collectPermissionRules` 均无法在 build 前运行时枚举内置工具名
  （`PermissionContextState` 不可变、引擎按会话缓存），静态名单方案是经确认的取舍
- 自定义 @Tool 可见性过滤（5.2）在 `registerTool` 之前执行（类粒度：类内任一 @Tool 方法名命中
  `deniedTools` 即整体跳过注册并告警）
- `collectPermissionRules(OafConfig)` 需要 OafConfig 参数（解析 `config.permission.mode`，与 5.3 的
  无参草图有出入，已按实现修正签名）

### 6.2 AgentRuntimeService — HITL 事件处理 + 恢复

**文件**: `src/main/java/io/agentmanager/framework/service/AgentRuntimeService.java`

**构造器（5 参数，无 SessionEventBus）**：
```java
public AgentRuntimeService(
        OafConfig oafConfig,
        HarnessAgent agent,
        List<Map<String, Object>> mcpConfigs,
        LLMLogger llmLogger,
        ConfirmContextStore confirmContextStore) { ... }
```

> 注：原 `SessionEventBus` 参数已移除。单次流架构下无需事件总线扇出。

#### 6.2.1 `invokeStream()` 新增事件分支（doOnNext，行 136 处）

```java
// ===== HITL 权限确认事件 =====
else if (type == io.agentscope.core.event.AgentEventType.REQUIRE_USER_CONFIRM) {
    var e = (io.agentscope.core.event.RequireUserConfirmEvent) event;
    var m = new LinkedHashMap<String, Object>();
    m.put("type", "permission_ask");
    m.put("task_id", tid);
    var calls = e.getToolCalls().stream().map(tc -> {
        var c = new LinkedHashMap<String, Object>();
        c.put("tool_call_id", tc.getId());          // ✅ javap 确认：getId() 返回 String
        c.put("name", tc.getName());
        c.put("input", tc.getInput());
        // 注意：ToolUseBlock 无 getSuggestedRules() 方法（javap 验证），
        // suggestedRules 在 PermissionDecision 上（引擎评估时产生）。
        // 若需展示建议规则，需从 PermissionDecision 获取并附加到 event metadata（P2）。
        return c;
    }).toList();
    m.put("tool_calls", calls);
    putIfNotNull(m, "reply_id", e.getReplyId());     // ✅ javap 确认：getReplyId() 返回 String

    // 落库 ConfirmContext（confirm_context 表，替代内存 confirmCache）
    confirmContextStore.save(fullThreadId, e);

    sink.next(m);   // 注意：此处不 complete，agent 处于暂停等待恢复状态
}
```

> ~~待确认~~：`ToolUseBlock.getId()` ✅ 已验证（非 `getToolCallId()`）；`RequireUserConfirmEvent.getReplyId()` ✅ 已验证。
> `ToolUseBlock` **无** `getSuggestedRules()` 方法——建议规则在 `PermissionDecision` 上，不在工具调用块上。

#### 6.2.2 新增恢复方法（确认端点调用，同步 + 流式两版）

```java
/**
 * ① 同步版：携带确认结果恢复暂停的 agent（阻塞直至本轮完成）。
 * results: [{tool_call_id, confirmed, accept_rule}]
 * 返回最终回复。
 *
 * 注意：agent.call() 是阻塞 API，**不产出中间事件**。
 * 此方法适用于：单次流同步拿最终回复、无人值守调用方等无需中间事件的场景。
 */
public Map<String, Object> resumeWithConfirm(
        String threadId, String userId, List<Map<String, Object>> results) {
    var tid = threadId != null && !threadId.isEmpty() ? threadId : UUID.randomUUID().toString();
    var fullThreadId = makeThreadId(tid);
    var ctx = RuntimeContext.builder().sessionId(fullThreadId)
        .userId(resolveUserId(userId)).build();

    var resumeMsg = buildResumeMsg(fullThreadId, results);   // 从 confirm_context 表取 ToolUseBlock

    var result = agent.call(List.of(resumeMsg), ctx).block();   // 阻塞恢复，无中间事件
    var responseText = result != null ? result.getTextContent() : "";
    return Map.of("response", responseText, "thread_id", tid);
}

/**
 * ② 流式版：同上，但以事件流返回（供确认后事件流接口/单次流场景）。
 * 复用 6.2.1 的事件转发逻辑（token/tool_call/tool_result/…/done）。
 */
public Flux<Map<String, Object>> resumeWithConfirmStream(
        String threadId, String userId, List<Map<String, Object>> results) {
    var tid = threadId != null && !threadId.isEmpty() ? threadId : UUID.randomUUID().toString();
    var fullThreadId = makeThreadId(tid);
    var ctx = RuntimeContext.builder().sessionId(fullThreadId)
        .userId(resolveUserId(userId)).build();

    return Flux.create(sink -> {
        agent.streamEvents(List.of(buildResumeMsg(fullThreadId, results)), ctx)
            .doOnNext(event -> {
                forwardEvent(sink, event, tid);                  // 复用 6.2.1 的逐事件转发（含 AGENT_END→done）
            })
            .doOnError(e -> {
                sink.next(Map.of("type", "error", "task_id", tid, "error", e.getMessage()));
                sink.next(Map.of("type", "done"));
                sink.complete();
            })
            .doOnComplete(() -> { if (!sink.isCancelled()) sink.complete(); })
            .subscribe();
    });
}

/**
 * 构造携带 confirm_results metadata 的恢复消息（公共）。
 * 从 confirm_context 表获取原始 ToolUseBlock，重建 ConfirmResult。
 *
 * buildResumeMsg 包含显式终止指令：恢复消息 textContent 告知 LLM
 * "已批准，成功请结束，不得再次调用同一工具"，防止 approve 后 LLM 重试同一 ASK 工具。
 */
private io.agentscope.core.message.Msg buildResumeMsg(String fullThreadId, List<Map<String, Object>> results) {
    var ctx = confirmContextStore.getAndConsume(fullThreadId);  // 从 confirm_context 表取出并标记已消费
    var confirmResults = results.stream().map(r -> {
        var toolCallId = (String) r.get("tool_call_id");
        var toolCall = ctx.rebuildToolUseBlock(toolCallId);     // 从持久化数据重建 ToolUseBlock 实例
        var confirmed = (boolean) r.getOrDefault("confirmed", true);
        var acceptRule = (boolean) r.getOrDefault("accept_rule", false);
        if (acceptRule) {
            return new io.agentscope.core.event.ConfirmResult(confirmed, toolCall, List.of());
        }
        return new io.agentscope.core.event.ConfirmResult(confirmed, toolCall);
    }).toList();
    var meta = new HashMap<String, Object>();
    meta.put(io.agentscope.core.message.Msg.METADATA_CONFIRM_RESULTS, confirmResults);
    return io.agentscope.core.message.Msg.builder()
        .name("user").role(io.agentscope.core.message.MsgRole.USER)
        .textContent("user confirmed, success please end, do not call the same tool again")  // 显式终止指令
        .metadata(meta)
        .build();
}
```

**关键约束**（均基于 javap 验证 agentscope-core-2.0.0.jar）：
- `ConfirmResult` 需要携带**原始 `ToolUseBlock` 实例**（`agent.call` 内部按 `replyId` 关联恢复）
- `ConfirmResult` 有两个构造函数：`ConfirmResult(boolean, ToolUseBlock)` 和 `ConfirmResult(boolean, ToolUseBlock, List<PermissionRule>)`
- `confirm_context` 表存储 ToolUseBlock 序列化数据，恢复时由 `ConfirmContextStore.rebuildToolUseBlock()` 重建

#### 6.2.3 阻塞 `invoke()` 的 HITL 语义（P1）

同步场景（如 A2A `message/send` 无确认能力时）返回结果可含 `permission_asking: true` + 待确认工具列表，
调用方决定是否走确认端点恢复；无人值守调用方忽略即不恢复（agent 保持暂停，或由调用方 cancel）。

### 6.3 ConfirmContextStore — 持久化确认上下文（替代内存 confirmCache）

**文件**: `src/main/java/io/agentmanager/framework/store/ConfirmContextStore.java`

原内存 `ConcurrentHashMap<String, ConfirmContext>` 已替换为 MySQL `confirm_context` 表持久化：

```sql
CREATE TABLE confirm_context (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id    VARCHAR(255) NOT NULL UNIQUE,
    tool_calls    JSON         NOT NULL,   -- ToolUseBlock 序列化 JSON
    reply_id      VARCHAR(255),
    consumed      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_session_id (session_id),
    INDEX idx_expires (created_at)
);
```

**接口**：
```java
public interface ConfirmContextStore {
    /** 存储确认上下文（RequireUserConfirmEvent → JSON 落库） */
    void save(String sessionId, RequireUserConfirmEvent event);

    /** 取出并标记已消费（CAS 语义：consumed=false → true，防重复确认） */
    ConfirmContextRecord getAndConsume(String sessionId);

    /** 检查确认上下文是否可用（存在且未消费） */
    boolean isAvailable(String sessionId);

    /** 恢复成功后清理 */
    void delete(String sessionId);

    /** 定时清理过期条目（TTL 30min） */
    void cleanupExpired();
}
```

**优势**（相比内存 confirmCache）：
- 进程重启后确认上下文不丢失
- 多实例部署时共享确认状态（无需粘性会话）
- 30min TTL + 定时清扫防止资源泄漏

### 6.4 TurnLeaseStore — 并发请求串行化

**文件**: `src/main/java/io/agentmanager/framework/store/TurnLeaseStore.java`

同一会话的并发请求通过 `turn_lease` 表串行化，防止 agent 状态竞态：

```sql
CREATE TABLE turn_lease (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id    VARCHAR(255) NOT NULL,
    lease_owner   VARCHAR(255) NOT NULL,   -- 请求唯一标识 (UUID)
    acquired_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at    DATETIME     NOT NULL,
    UNIQUE INDEX idx_session_lease (session_id),
    INDEX idx_expires (expires_at)
);
```

**接口**：
```java
public interface TurnLeaseStore {
    /** 尝试获取 turn lease（成功返回 true，已有 lease 返回 false） */
    boolean tryAcquire(String sessionId, String leaseOwner, Duration ttl);

    /** 释放 turn lease（仅 leaseOwner 匹配时释放） */
    void release(String sessionId, String leaseOwner);

    /** 定时清理过期 lease */
    void cleanupExpired();
}
```

**使用场景**：`POST /threads/{sid}/chat` 和 `POST /threads/{sid}/confirm-stream` 调用前先 `tryAcquire`，
完成后 `release`。同一会话并发请求排队等待（返回 429 或排队重试）。

### 6.5 ConfirmController — 独立确认端点（不在 /debug 下）

**文件**: `src/main/java/io/agentmanager/framework/controller/ConfirmController.java`（新建）

确认端点是业务能力，不归属 Debug 页面，独立 Controller 暴露：

| 方法 | 路径 | 请求体 | 响应 | 说明 |
|---|---|---|---|---|
| POST | `/threads/{sessionId}/confirm` | `{results: [{tool_call_id, confirmed, accept_rule}]}` | `{response, thread_id}` | 携带确认决策恢复 agent，**同步返回恢复执行后的最终回复** |
| POST | `/threads/{sessionId}/confirm-stream` | 同上 | **SSE 事件流** | 恢复执行的事件流式下发（单次流场景），事件词表与普通流一致（token/tool_result/…/done） |

```java
@RestController
@RequestMapping("/threads/{sessionId}")
public class ConfirmController {

    private final AgentRuntimeService runtimeService;

    /** 同步版：返回最终回复 */
    @PostMapping(value = "/confirm", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> confirm(
            @PathVariable String sessionId, @RequestBody ConfirmRequest body) {
        // 1. 从 confirm_context 表取确认上下文（不存在 → 404）
        // 2. CAS 标记已消费（防重复确认 → 已消费 → 409）
        // 3. 组装 ConfirmResult 列表（body.results）
        // 4. 恢复执行（内部 agent.call 阻塞直至本轮完成）
        try {
            var result = runtimeService.resumeWithConfirm(sessionId, /* userId */ null, body.results());
            return ResponseEntity.ok(result);
        } catch (ConfirmContextNotFoundException e) {
            return ResponseEntity.status(404).body(Map.of(
                "error", "confirm_context_not_found",
                "message", "Session not found or confirm context expired"));
        } catch (ConfirmAlreadyConsumedException e) {
            return ResponseEntity.status(409).body(Map.of(
                "error", "confirm_already_consumed",
                "message", "This confirm has already been processed"));
        }
    }

    /** 流式版：确认后事件流（供单次流/一次性连接调用方实时消费恢复过程） */
    @PostMapping(value = "/confirm-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> confirmStream(
            @PathVariable String sessionId, @RequestBody ConfirmRequest body) {
        try {
            runtimeService.checkConfirmAvailable(sessionId);  // 预检 confirm_context 存在且未消费
        } catch (Exception e) {
            return Flux.just(ServerSentEvent.<String>builder()
                .data(AgentEventSseSerializer.payload(Map.of("type", "error", "error", e.getMessage())))
                .build());
        }
        return runtimeService.resumeWithConfirmStream(sessionId, /* userId */ null, body.results())
            .map(m -> ServerSentEvent.<String>builder()
                .data(AgentEventSseSerializer.payload(m))   // Map 重载：与 invokeStream 词表一致序列化
                .build());
    }

    public record ConfirmRequest(List<Map<String, Object>> results) {}
}
```

### 6.6 AgentEventSseSerializer — 序列化词条

**文件**: `src/main/java/io/agentmanager/framework/controller/AgentEventSseSerializer.java`

```java
} else if (event instanceof io.agentscope.core.event.RequireUserConfirmEvent confirm) {
    var calls = confirm.getToolCalls().stream().map(tc -> {
        var c = new LinkedHashMap<String, Object>();
        c.put("tool_call_id", tc.getId());        // ✅ javap 确认：snake_case，与 invokeStream 6.2.1 一致
        c.put("name", tc.getName());
        c.put("input", tc.getInput());
        // ToolUseBlock 无 getSuggestedRules()（javap 验证）——不输出 suggested_rules
        return c;
    }).toList();
    payload.put("type", "permission_ask");         // 统一 type，与 invokeStream 一致
    payload.put("task_id", ...);                   // 从 event 上下文获取
    payload.put("tool_calls", calls);              // snake_case，与 invokeStream 一致
    payload.put("reply_id", confirm.getReplyId()); // ✅ javap 确认
}
```

SSE 事件词表：`RequireUserConfirmEvent` → `type=permission_ask` + `tool_calls[]`（统一 snake_case）。

**单次流覆盖**：`POST /threads/{sid}/chat` 单次流 SSE 统一调用 `AgentEventSseSerializer.payload()`，
新增词条后单次流自动覆盖；A2A 链路由 SDK 原生序列化（6.7）。

### 6.7 A2A 链路（❌ 结论：SDK 设计限制，不支持 HITL）

**验证结论（2026-08-18，源码级确认 agentscope-java v2.0.0）**：A2A 模式**无法**支持 HITL 人工确认，
根因在 AgentScope SDK 的双事件体系：

| | 老 `Event`（`io.agentscope.core.agent.Event`） | 新 `AgentEvent`（`io.agentscope.core.event.AgentEvent`） |
|---|---|---|
| 生产者 | `agent.stream()`（A2A `HarnessAgentRunner` 路径） | `agent.streamEvents()`（Channel/invokeStream 路径） |
| `getType()` 返回 | `EventType`（REASONING/TOOL_RESULT/HINT/AGENT_RESULT/SUMMARY） | `AgentEventType`（31 值） |
| `RequireUserConfirmEvent` | ❌ **不在其中**（是 AgentEvent 子类） | ✅ 在其中 |

链条：`AgentRunner.stream()` 接口契约强制 `Flux<Event>`（a2a-server `AgentRunner.java:58`）→
`AgentScopeAgentExecutor` 消费 `Event` 的 `getMessage()/getType()/isLast()/getMessageId()` →
`RequireUserConfirmEvent` 永远不进入 A2A 事件流 → 客户端收不到确认请求，无法交互确认。

**附加事实**：
- v2.0.0 起权限系统在 `stream()` 路径**同样执行**（`call` → `callInternal` → 共用 `buildAgentStream` 核心），
  ASK 命中时 agent 以 `GenerateReason.PERMISSION_ASKING` 暂停；但同步 `message/send`（blocking=true）
  会**永久挂起**（无确认方），仅 `tasks/cancel` 可救。
- 官方未计划迁移 A2A 模块：`agentscope-extensions-a2a-server` 在 v2.0.0→v2.0.2 之间**零代码变化**；
  `streamEvents()` 迁移（issue #2202 / PR #2306）只落在 **AG-UI** 模块，且 #2495 的 permission-type HITL
  修复（`RequireUserConfirmEvent` → AG-UI Interrupt）**未进入任何 release tag**（仅 main 分支，见附录 A）。

**因此**：A2A 调用方接入确认的能力**不由 agent-framework 承担**（8.3 A2A 模式降级说明）；
如 A2A 客户端需要 HITL，选项见 6.7.1。

#### 6.7.1 A2A HITL 可行性评估（2026-08-18）

| 方案 | 做法 | 结论 |
|---|---|---|
| 改 `HarnessAgentRunner` 用 `streamEvents()` | `AgentRunner` 契约 `Flux<Event>` 与 `Flux<AgentEvent>` 类型不兼容（独立类层级），编译失败 | ❌ 不可行 |
| 在 `stream()` 流上拦截 | 只能拿到 `AGENT_RESULT` 暂停 Msg，**取不到原始 `ToolUseBlock` 对象**（无法构造 `ConfirmResult` 恢复） | ❌ 不可行 |
| fork a2a-server 升级 executor | 仿官方 #2306/#2495：converter 把 `RequireUserConfirmEvent` 映射为可确认通知 + resume 重建 `ConfirmResult` | ⚠️ 可行但成本高，需长期维护 fork |
| **切换 AG-UI 协议**承载聊天 UI 流量 | 官方 v2.0.1 已支持 streamEvents + suspend-type HITL；permission-type HITL 需自研 `PermissionConfirmEventConverter` 或等官方发版（#2495） | ✅ **推荐**（详见附录 A） |
| A2A 保留给 agent 互调（无 HITL 的机器场景） | 无需改动，保持现状 | ✅ |

> 本工程结论：**维持现状**（A2A 透传 SDK），Debug 页面 A2A 模式不提供确认卡片；
> 未来聊天 UI 的 HITL 需求由 AG-UI 协议承载（附录 A 已给切换评估）。

---

## 七、范围界定

**本次仅变更 agent-framework 工程**：

| 工程 | 变更 | 说明 |
|---|---|---|
| agent-framework | ✅ 实施 | 权限装配、HITL 事件链路、确认端点、**Debug 页面确认卡片**（`static/debug/` 内嵌静态资源，属 agent-framework 工程） |
| backend (Go) | ❌ 不变更 | 现有 `/agents/:id/chat`（A2A `message/send`）保持原样；其调用者无确认 UI，ASK 时 agent 将暂停等待（无人值守可配 `mode: dont_ask` 降级，见 5.1） |
| frontend (React) | ❌ 不变更 | 聊天测试继续走同步 `POST /agents/:id/chat` |

A2A 调用方的确认交互（未来 Go/React 接入时）按 6.7 + 8.2 协议实现即可，agent-framework 侧能力本次一次到位。

---

## 八、事件与确认协议

### 8.1 SSE 事件词表（新增）

| 事件 `type` | 触发时机 | 关键字段 |
|---|---|---|
| `permission_ask` | 工具调用被 ASK 拦截（invokeStream / confirm-stream 链路统一） | `task_id`, `tool_calls[]`, `reply_id` |
| `user_confirm_result` | 恢复事件（可选，P2） | `reply_id` |

`tool_calls[]` 元素：`{tool_call_id, name, input}`

> 注意：`suggestedRules` 不在 `ToolUseBlock` 上（javap 验证），而在 `PermissionDecision` 上。
> 若需在确认卡片展示建议规则，需从 `PermissionDecision` 获取并附加到 event metadata（P2 扩展）。

> 统一为 `permission_ask`：`invokeStream` 产出与 `AgentEventSseSerializer` 序列化输出保持同一 type，
> 消除链路差异；字段命名统一 `snake_case`（`tool_call_id` 而非 `toolCallId`）。

### 8.2 确认 API 规格

**确认端点（独立于 Debug，任意调用方可使用）**：

```http
POST /threads/{sessionId}/confirm
Content-Type: application/json

{
  "results": [
    { "tool_call_id": "call_abc123", "confirmed": true,  "accept_rule": false },
    { "tool_call_id": "call_def456", "confirmed": false, "accept_rule": false }
  ]
}
```

- `confirmed=true`：批准执行；`accept_rule=true` 时接受建议规则（后续同型调用自动放行，不再询问）；
  **注意**：`accept_rule` 功能当前为 P2——`suggestedRules` 在 `PermissionDecision` 上（javap 验证），
  需额外传递才能在确认时生效
- `confirmed=false`：拒绝执行（agent 继续推理，若全部被拒默认继续，P2 可停止）
- 两种响应形态（按需选择）：
  - **同步 JSON**（`/confirm`）：`{response, thread_id}`——恢复执行后的最终回复
  - **确认后事件流**（`/confirm-stream`，SSE）：恢复执行的事件实时下发，词表与普通流一致
    （`token` / `tool_call` / `tool_result` / `thinking_block_delta` / … / `done`），供**单次流等一次性连接**
    调用方完整复现恢复过程；`done` 帧后连接关闭

**A2A 协议（对外调用方）— ❌ 不支持（SDK 限制，见 6.7）**：
- `RequireUserConfirmEvent` 不进入 A2A 的 `Flux<Event>` 事件流，`message/stream` 收不到确认请求；
  ASK 命中时阻塞 `message/send` 会挂起（仅 `tasks/cancel` 可救）
- A2A 调用方如需人工确认：配置 `mode: dont_ask` 降级（ASK→自动 DENY），或改用 Channel（8.3）/AG-UI（附录 A）链路

### 8.3 Debug 页面确认交互（单次流模式）

**文件**: `src/main/resources/static/debug/`（`js/api.js`、`modules/chat.js`、`css/`）

**事件展示（单次流统一）**：`handleEvent` 新增 `permission_ask` 分支——
在消息流中插入**确认卡片**（工具名 + 参数 JSON），卡片按钮：`批准` / `拒绝` / `批准并记住规则`
（accept_rule）；卡片出现期间 `isStreaming` 保持 true（禁输入、禁停止）。

> 建议规则提示（P2）：当前 `tool_calls[]` 不含 `suggested_rules`（`ToolUseBlock` 无此字段，javap 验证）；
> 后续可通过 `PermissionDecision.getSuggestedRules()` 附加到 event metadata 实现"批准并记住规则"按钮。

**确认后操作**：

| 模式 | ASK 事件来源 | 点击确认后的行为 |
|---|---|---|
| **单次流**（POST /threads/{sid}/chat） | 单次流 SSE 收到 `permission_ask`（`AgentEventSseSerializer` 统一词条） | 原流已 complete（恢复是新调用）；点击确认后**关闭原连接**，改走 `POST /threads/{sid}/confirm-stream`（SSE）——恢复执行事件实时下发到新连接，前端在同一消息上下文中继续渲染（token/tool_result/…/done）；也可用 `POST /threads/{sid}/confirm` 同步拿最终回复（无中间事件） |
| **A2A**（a2a） | `message/stream` 标准帧（SDK 原生序列化） | **不支持确认**：`RequireUserConfirmEvent`（AgentEvent）不进入 A2A 的 `Flux<Event>` 流（6.7），客户端收不到 `permission_ask`，无确认卡片。ASK 命中时同步 `message/send` 会挂起（仅 `tasks/cancel` 可救）；如遇此场景应降级为 `mode: dont_ask`（5.1）或改用 AG-UI 模式 |

**通用**：`accept_rule=true` 时后续同型调用不再询问（建议规则已写入引擎）。

**AGENT_START teardown**：MCP App 卡片的 iframe teardown 从 `AGENT_END` 移至 `AGENT_START`，
防止 teardown 时机晚于用户点击确认（tools/call 无人处理）。详见 MCP Apps 阶段二问题总结。

---

## 九、事件映射表

| AgentScope 事件/状态 | 中间表示 | 前端动作 |
|---|---|---|
| `RequireUserConfirmEvent` | `permission_ask`（统一 type，invokeStream + confirm-stream 链路） | 渲染确认卡片（工具名 + 参数），等待用户决策；`suggestedRules` 需从 `PermissionDecision` 获取（P2） |
| `GenerateReason.PERMISSION_ASKING`（阻塞） | 响应含 `permission_asking: true` + 工具列表 | 同上（同步模式） |
| `UserConfirmResultEvent` | `user_confirm_result` | 标记卡片已处理（可选） |
| `AllToolsDeniedEvent` | `all_tools_denied`（P2） | 显示"全部工具被拒"并结束本轮 |

---

## 十、实施计划

| 阶段 | 内容 | 文件 | 工作量 | 优先级 |
|---|---|---|---|---|
| 阶段一 | `McpToolRegistrar` 解析 `permissions.tools` 三态 + `collectPermissionRules()` + `PermissionContextState` 装配（MCP-only，自带工具自动放行）+ 自定义工具可见性过滤 | `McpToolRegistrar.java`、`OafConfig.java`、`OafConfigLoader.java`、`AgentScopeConfig.java` | 1 天 | P0 |
| 阶段二 | `invokeStream` HITL 事件转发 + `ConfirmContextStore` 落库 + `resumeWithConfirm` | `AgentRuntimeService.java`、`ConfirmContextStore.java` | 1 天 | P0 |
| 阶段三 | SSE 序列化词条 + 独立确认端点（`/threads/{sessionId}/confirm` 同步 + `/confirm-stream` 事件流，新 `ConfirmController`） | `AgentEventSseSerializer.java`、`ConfirmController.java`（新建） | 0.5 天 | P0 |
| 阶段四 | Debug 页面确认卡片 | `static/debug/`（`js/api.js`、`modules/chat.js`、`css/`） | 0.5 天 | P1 |
| 阶段五 | 阻塞链路 PERMISSION_ASKING / 全部拒绝停止 / 建议规则 UI / ~~A2A confirm_results 透传~~（SDK 限制取消，见 6.7） | 各层 | 1 天 | P2 |

**实施状态（2026-08-21）**: 阶段一~四已全部完成并单测通过。实现要点：
- 阶段二 `AgentRuntimeService` 构造器为 5 参 `(OafConfig, HarnessAgent, mcpConfigs, LLMLogger, ConfirmContextStore)`——无 `SessionEventBus`（已删除）；`AgentScopeConfig` bean 与 2 个测试构造点同步更新
- `ConfirmContextStore`：MySQL `confirm_context` 表持久化（替代内存 `confirmCache`），支持多实例共享、重启不丢失
- `TurnLeaseStore`：MySQL `turn_lease` 表，串行化同一会话并发请求
- 新增测试：`AgentRuntimeServiceHitlTest`（含 permission_ask 转发/ConfirmContextStore CAS/恢复 OK/拒绝/404/409）、`ConfirmControllerTest`（同步恢复/404/409/流式错误帧）
- `buildResumeMsg` 包含显式终止指令（"已批准，成功请结束，不得再次调用同一工具"），防止 approve 后 LLM 重试同一 ASK 工具
- AGENT_END teardown 移至 AGENT_START（MCP App 卡片生命周期修复）
- Debug 页面：`permission_ask` 卡片批量 Approve/Reject；单次流模式走 `confirm-stream` 拿恢复事件流

### 确认流程总结

```
permission_ask 事件
  │
  ▼
ConfirmContextStore.save(sessionId, event)  → confirm_context 表落库
  │
  ▼
SSE 流 complete（前端收到 permission_ask，渲染确认卡片）
  │
  ▼
用户点击「批准」→ POST /threads/{sid}/confirm-stream
  │
  ▼
ConfirmContextStore.getAndConsume(sessionId)  → 取出并标记已消费（CAS 防重复）
  │
  ▼
resumeWithConfirmStream() → agent.streamEvents() → SSE 事件流返回
```

**三模式 E2E 验证（2026-08-18/21）**:

| 模式 | 结果 | 说明 |
|---|---|---|
| Channel 单次流 | ✅ 功能正常 | 确认卡片出现 → Approve → agent 恢复 → 回复+工具调用同流续达 |
| A2A | ❌ | 确认卡片未出现 —— **SDK 限制**（6.7），非本工程 bug |

---

## 十一、测试验证方案

### 11.1 单元测试（agent-framework）

| 用例 | 验证点 |
|---|---|
| 配置解析 | `permissions.tools` 三态正确映射（allow/ask/deny）；`read_only` 既有字段不受影响；多 server 规则聚合正确 |
| 兼容映射 | `require_confirmation: true` → 全部 MCP ASK（未声明者）；与显式 tools 规则共存时显式优先 |
| 作用域 | `write_file: ask` 命中时触发 ASK；内置工具（`BUILT_IN_TOOL_NAMES` 静态白名单覆盖）调用**不**触发确认（ALLOW 规则 + `verifyToolCoverage` 校验）；未声明的 MCP 工具默认 allow 不询问 |
| 自带工具可见性 | `deniedTools` 命中内置工具名 → tools.json deny；命中自定义 @Tool → 不注册、`/tools` 不可见 |
| 事件转发 | `RequireUserConfirmEvent` → `permission_ask`（tool_calls 字段完整，统一 snake_case） |
| 恢复 | `resumeWithConfirm` 构造 `ConfirmResult(boolean, ToolUseBlock)`/`(boolean, ToolUseBlock, List<PermissionRule>)`；`ConfirmContextStore` CAS 消费/清理 |
| ConfirmContextStore | `save` → `getAndConsume` CAS → 404/409；TTL 过期清理 |
| 缓存 miss | `confirm` 返回 404（`confirm_context_not_found`）；`confirm-stream` 返回 error SSE 帧 |
| 重复确认 | 第二次 `confirm` 返回 409（`confirm_already_consumed`，CAS 防护） |
| 序列化 | `AgentEventSseSerializer` 输出 `permission_ask`（统一 type）+ `tool_calls[]`（snake_case） |
| 控制器 | `POST /threads/{sid}/confirm` 同步返回 + `/confirm-stream` SSE 参数校验与透传 |
| 序列化 Map 重载 | `AgentEventSseSerializer.payload(Map)` 输出词表与 invokeStream 一致 |

### 11.2 集成测试

1. 配置 `mcp-configs/filesystem/config.yaml`：`permissions.tools.write_file: ask`，Debug 页面发送"通过 MCP 写文件 test.txt" → 观察：
   - [x] 出现 `permission_ask`（tool_calls 含 MCP 写工具 + 参数）—— **已实现**（invokeStream 链路，单测覆盖转发帧）
   - [x] agent 暂停（无后续 token）—— **已实现**（RequireUserConfirmEvent 不 complete，等待恢复）
   - [x] 点击批准 → 文件写入，后续事件恢复；点击拒绝 → 无写入 —— **已实现**（单测覆盖 confirmed=true/false 两种恢复消息构造；真实 LLM 沙箱链路待 11.2 手工验收）
2. 内置工具确认隔离：同一配置下发送"直接写文件 read_file/write_file（内置）"→ 不出现确认卡片，直接执行
   - [x] **已实现**（BUILT_IN_TOOL_NAMES 静态白名单 ALLOW 规则 + verifyToolCoverage 兜底；真实链路待手工验收）
3. **单次流确认闭环**：
   - [x] **单次流**：`permission_ask` 卡片出现在一次性流；确认后走 `/confirm-stream` 拿到恢复事件流，同消息上下文续渲染；`/confirm` 同步版返回最终回复 —— **已实现**（前端单次流分支 + ConfirmController 两端点）
   - [ ] **A2A（❌ 不可行，SDK 限制）**：`RequireUserConfirmEvent` 不进入 A2A 的 `Flux<Event>` 流 —— 已源码级确认（6.7），非本工程可修复项；改用 AG-UI 承载聊天 UI 的 HITL 需求（附录 A）
4. 无人值守：`mode: dont_ask` 时 MCP ASK 自动降级 DENY，不阻塞 —— 未实现（P2，需 PermissionEngine 层确认兜底逻辑）
5. 可见性：`deniedTools` 配置自定义工具/内置工具后，`/tools` 与 LLM 均不可见 —— **已实现**（阶段一单测覆盖）
6. A2A 协议恢复 —— **不实施**：SDK `Flux<Event>` 契约不含 `RequireUserConfirmEvent`（6.7），协议层无法传输确认；需要时降级 `dont_ask`（5.1）

### 11.3 回归

```bash
cd /root/agent-manager/agent-framework && mvn test    # 用例全绿
```

---

## 十二、补充设计

### 12.1 多工具批量 ASK 行为

单次 LLM 推理可产出多个 tool_call，全部命中 ASK 时在一个 `RequireUserConfirmEvent` 中包含。
确认卡片**批量展示全部待确认工具**，用户**批量决策**（一个 confirm 请求携带全部 results）。

| 场景 | 行为 |
|---|---|
| 全部 confirmed | 全部执行，agent 继续推理 |
| 部分 confirmed、部分 rejected | 已确认的执行，被拒的跳过，agent 继续推理 |
| 全部 rejected | agent 继续推理但无工具执行（P2 可通过 `AllToolsDeniedEvent` → `RequestStopEvent` 停止） |

### 12.2 超时与取消（P2）

| 机制 | 设计 |
|---|---|
| **超时自动 deny** | 新增 `confirmTimeout` 配置（默认 5min）；`confirm_context.created_at` 超时后自动以 `confirmed=false` 恢复 agent，避免永久暂停 |
| **主动取消** | `POST /threads/{sid}/cancel`（复用 A2A `tasks/cancel` 语义）：清理 confirm_context，agent 收到 cancel 信号停止 |

### 12.3 accept_rule 持久化

`accept_rule=true` 接受的建议规则写入运行时 `PermissionContextState`（内存），仅当次进程生命周期内生效。
进程重启后规则丢失，用户需重新确认。

> 这是刻意设计：持久化到 `config.yaml` 或数据库需额外写入逻辑 + 多实例同步，复杂度高。
> 当前范围接受运行时生效限制；P2+ 可按需扩展为持久化规则。

### 12.4 构建模式与挂载模式

- **构建模式**：`mcp-configs/{server}/config.yaml` 打包在镜像内，`permissions.tools` 随镜像生效
- **挂载模式**：`mcp-configs/` 通过 ConfigMap 挂载到 `/config/mcp-configs/`，`McpToolRegistrar` 已有路径解析逻辑
  无需额外适配；AGENTS.md frontmatter `config.permission.mode` 同样由 `OafConfigLoader` 从 `/config` 读取

### 12.5 confirm 端点错误码

| HTTP 状态码 | 错误码 | 说明 |
|---|---|---|
| 404 | `confirm_context_not_found` | sessionId 不存在或 confirm_context 已过期（TTL 30min） |
| 409 | `confirm_already_consumed` | 该 session 的确认已被处理（防重复确认） |

---

## 十三、风险评估

| 风险 | 概率 | 影响 | 缓解措施 |
|---|---|---|---|
| ~~JAR API 与文档不符~~ | ~~中~~ | ~~编译/运行错误~~ | ✅ **已消除**：javap 验证 agentscope-core-2.0.0.jar，`getId()`/`getReplyId()`/`ConfirmResult`/`PermissionContextState.Builder`/`Toolkit.getToolNames()` 均确认；发现 `ToolUseBlock` 无 `getSuggestedRules()`（仅在 `PermissionDecision` 上），文档已修正 |
| `DEFAULT` mode 下内置工具未命中规则也会 ASK | ~~高~~ | ~~自带工具意外需要确认~~ | ✅ **已消除**：静态内置白名单 `BUILT_IN_TOOL_NAMES`（javap 提取验证 25 个注册名）+ 构建后 `verifyToolCoverage()` 差集校验，SDK 升级名单漂移时 ERROR 日志兜底（见 6.1 代码） |
| `ConfirmResult` 需原始 `ToolUseBlock` 实例，跨请求无法序列化 | 高 | 恢复失败 | ✅ **已解决**：`confirm_context` 表持久化 + `ConfirmContextStore.rebuildToolUseBlock()` 重建实例；多实例共享、重启不丢失 |
| 无确认方时 agent 永久暂停（现 Go/React 无确认 UI） | 中 | 会话卡死 | 配置层提供 `dont_ask` 降级；暂停超时兜底（P2，见 12.2）；Go/React 接入协议已定义（8.2） |
| A2A 链路不支持 HITL（SDK 双事件体系限制） | 高（已验证） | A2A 调用方无法交互确认 | ✅ **已定性**：`RequireUserConfirmEvent` 不进入 A2A 的 `Flux<Event>` 流（6.7）；A2A 场景降级 `dont_ask` 或改用 AG-UI（附录 A）；Debug 页面 A2A 模式不渲染确认卡片（8.3） |

---

## 十四、相关文档

| 文档 | 说明 |
|---|---|
| [AgentScope Permission System](https://java.agentscope.io/v2/zh/docs/building-blocks/permission-system.html) | 官方权限系统/HITL 文档 |
| [AgentScope Middleware](https://java.agentscope.io/v2/zh/docs/building-blocks/middleware.html) | 全部拒绝停止的 middleware 方案 |
| [事件系统升级方案](event-system-upgrade-plan.md) | 现有 SSE 事件链路（本文基础） |
| [Agent Framework AGENTS.md](../AGENTS.md) | 项目总览 |

---

## 附录 A：官方 SDK 支持现状与 AG-UI 切换评估（2026-08-18）

### A.1 HITL 两种类型（官方术语）

| 维度 | **suspend-type HITL** | **permission-type HITL**（我们的场景） |
|---|---|---|
| 触发方 | 工具代码主动抛 `ToolSuspendException`（`ToolSuspendException.java:40`）或 schema-only 外部执行工具 | 权限引擎（`PermissionEngine`）按规则拦截，与工具代码无关 |
| 停止信号 | `GenerateReason.TOOL_SUSPENDED` | `GenerateReason.PERMISSION_ASKING` + `RequireUserConfirmEvent` |
| 恢复数据 | `ToolResultBlock`（外部提供执行结果） | `ConfirmResult`（approved/denied，经 `Msg.METADATA_CONFIRM_RESULTS`） |
| 典型场景 | 前端表单类工具（日期选择、文件上传） | 敏感工具人工审批（`write_file: ask`） |
| AG-UI v2.0.1 支持 | ✅ | ❌（需 #2495） |

### A.2 官方 SDK 状态（源码级核实）

| 项 | 状态 |
|---|---|
| `ReActAgent.stream()` | `@Deprecated(forRemoval=true)`（v2.0.0 起），底层与 `streamEvents()` 共用 `buildAgentStream` 核心（权限检查两路径都执行） |
| A2A 模块迁移 streamEvents | ❌ `AgentRunner` 契约仍是 `Flux<Event>`（`AgentRunner.java:58`）；a2a-server 模块 v2.0.0→v2.0.2 **零代码变化**，官方未计划 |
| AG-UI 模块迁移 streamEvents | ✅ issue #2202 → PR #2306（2026-07-29 merged，进入 v2.0.1）：converter registry（`AgentEvent → AguiEvent`）+ `AguiResumeCoordinator` + 自定义 converter 扩展点 |
| permission-type HITL 到 AG-UI | ⚠️ issue #2437 → PR #2495（2026-08-08 merged，**仅 main 分支，未进 v2.0.1/v2.0.2**）：`PermissionConfirmEventConverter` 把 `RequireUserConfirmEvent` → 每个 ToolUseBlock 一个 AG-UI Interrupt（reason `tool_confirmation`），resume 重建 `ConfirmResult`；后续 #2639 跟进 |
| Maven Central | 已有 2.0.1 / 2.0.2（2.0.2 的 AG-UI 与 2.0.1 相同，均无 #2495） |

### A.3 AG-UI 切换评估结论

**结论：A2A 保留（agent 互调），聊天 UI 的 HITL 需求由 AG-UI 承载**（若需要）：

| 路线 | 内容 | 成本/风险 |
|---|---|---|
| 维持现状（本工程当前决策） | A2A 透传 SDK，Debug 页面 A2A 模式无确认卡片；Channel 单次流已具备完整 HITL | 零成本；A2A 调用方遇 ASK 会挂起（降级 `dont_ask`） |
| 切换/并存 AG-UI（未来） | 升级 SDK 2.0.2 + 新增 `agentscope-extensions-agui` 依赖 + debug 页面 A2A 模式改 AG-UI 客户端（`/runs` SSE + `resume[]`）+ 自研 `PermissionConfirmEventConverter`（对照 #2495）或等官方发版 | 升级回归（项目深度使用 SDK 内部类）；~2-3 天 |

> 复刻 #2495 的可行性：converter registry 是官方公开扩展点（v2.0.1 即有），自研仅 5 个类
> （`PermissionConfirmEventConverter` / `AguiResumeCoordinator` / `AguiMessageConverter` / registry 注册 / adapter 转发），
> 且完全对齐官方后续版本，升级不冲突。
