# 多租户隔离方案

## 1. 现状分析

### 1.1 已实现的隔离能力

当前实现使用 AgentScope 原生机制，**无需自定义认证/鉴权代码**即可获得多租户隔离：

| 维度 | 实现方式 | 隔离程度 |
|---|---|---|
| **AgentState 隔离** | `RuntimeContext(userId, sessionId)` 按 `(userId, sessionId)` 寻址 | ✅ 完全隔离 |
| **工作区文件隔离** | `RemoteFilesystemSpec(IsolationScope.USER)` 按 userId 分桶 | ✅ 完全隔离 |
| **记忆隔离** | `memory_search`/`memory_save` 按当前 userId 命名空间 | ✅ 完全隔离 |
| **会话日志隔离** | `sessions/*.jsonl` 按 userId 分桶 | ✅ 完全隔离 |
| **静态资产覆盖** | `<userId>/skills/` 目录覆盖共用版 | ✅ 用户定制 |
| **并发控制** | per-session 异步门，同 (uid,sid) 串行 | ✅ 自动处理 |
| **跨节点恢复** | MysqlDistributedStore 分布式存储 | ✅ 任意节点恢复 |

### 1.2 当前 userId 来源

| 来源 | userId | 说明 |
|---|---|---|
| A2A JSON-RPC | `oafConfig.vendorKey()` | 当前默认使用 vendorKey |
| Channel SSE | `SendOptions.userId()` | 由调用方指定 |
| A2A metadata | `params.metadata.userId` | 由调用方指定 |

### 1.3 AgentScope 2.0 原生多租户能力

```
HarnessAgent (单例)
  │
  ├── RuntimeContext(userId="alice", sessionId="s1")
  │   └── AgentState → agent_state 表 (session_id="acme:acme-test-agent:s1")
  │   └── 工作区文件 → agent_fs 表 (namespace="agents/test-agent/users/acme/...")
  │
  ├── RuntimeContext(userId="bob", sessionId="s2")
  │   └── AgentState → agent_state 表 (session_id="acme:acme-test-agent:s2")
  │   └── 工作区文件 → agent_fs 表 (namespace="agents/test-agent/users/bob/...")
  │
  └── 不同 (userId, sessionId) 完全并行
      相同 (userId, sessionId) 自动串行
```

---

## 2. 隔离机制详解

### 2.1 IsolationScope 四种模式

| Scope | 命名空间键 | 典型场景 |
|---|---|---|
| `SESSION` | `agents/<agentId>/sessions/<sessionId>/...` | 每个会话完全隔离 |
| `USER`（默认） | `agents/<agentId>/users/<userId>/...` | 同一用户跨会话共享记忆 |
| `AGENT` | `agents/<agentId>/shared/...` | 共享知识库型 agent |
| `GLOBAL` | `global/...` | 全局共享 |

当前配置使用 `IsolationScope.USER`：同一用户的多个会话共享记忆，不同用户完全隔离。

### 2.2 降级规则

- `USER` scope 下，如果 `userId` 为空，降级为 `SESSION`（按 sessionId 隔离）
- `SESSION` scope 下，如果 `sessionId` 为空，跳过状态查找，创建全新环境
- `AGENT` scope 的命名空间键由 agent name 决定，不会因缺少上下文字段而降级

### 2.3 运行时数据 vs 静态资产

| 类型 | 隔离方式 | 说明 |
|---|---|---|
| **运行时数据** (MEMORY.md, memory/, sessions/) | 自动按 `IsolationScope` 隔离 | 框架自动处理 |
| **静态资产** (AGENTS.md, skills/, knowledge/) | 共享 + 用户覆盖目录 | `<userId>/skills/` 覆盖共用版 |

用户覆盖目录示例：

```
workspace/
├── skills/code-reviewer/SKILL.md     ← 共用版（所有人可见）
└── alice/
    └── skills/
        └── code-reviewer/
            └── SKILL.md              ← 只对 alice 生效，覆盖共用版
```

### 2.4 隔离矩阵

| 数据类型 | 隔离维度 | 存储位置 | 说明 |
|---|---|---|---|
| AgentState | `(userId, sessionId)` | `agent_state` 表 | 每个会话独立 |
| MEMORY.md | `userId` | `agent_fs` 表 | 每个用户独立，跨会话共享 |
| memory/ | `userId` | `agent_fs` 表 | 每个用户独立 |
| skills/ | 共享 + 用户覆盖 | `agent_fs` 表 | 共享底座 + `<userId>/skills/` 覆盖 |
| subagents/ | 共享 | `agent_fs` 表 | 所有用户共享 |
| knowledge/ | 共享 | `agent_fs` 表 | 所有用户共享 |
| sessions/ | `userId` | `agent_fs` 表 | 每个用户独立 |

---

## 3. 实现方案

### 3.1 架构概览

```
请求 → Controller → AgentRuntimeService → HarnessAgent (单例，通过 RuntimeContext 隔离)
    ├── MysqlDistributedStore (AgentState + 工作区文件)
    │   ├── agent_state 表 (按 userId:sessionId 隔离)
    │   └── agent_fs 表 (按 IsolationScope 隔离)
    └── RemoteFilesystemSpec(IsolationScope.USER)
        └── 自动按 userId 分桶
```

### 3.2 当前实现

**AgentScopeConfig.java**:
```java
@Bean
public DistributedStore distributedStore(DataSource dataSource) {
    return DistributedStore.builder()
        .agentStateStore(new MysqlAgentStateStore(
            dataSource, "agent_manager_test", "agent_state", true))
        .baseStore(JdbcStore.builder(dataSource)
            .tableName("agent_fs").initializeSchema(true).build())
        .build();
}

@Bean
public HarnessAgent harnessAgent(...) {
    return HarnessAgent.builder()
        // ...
        .distributedStore(distributedStore)
        .filesystem(new RemoteFilesystemSpec()
            .isolationScope(IsolationScope.USER))
        .build();
}
```

**AgentRuntimeService.java**:
```java
var ctx = RuntimeContext.builder()
    .sessionId(fullThreadId)
    .userId(oafConfig.vendorKey())  // userId 来源
    .build();
```

### 3.3 userId 来源定制

当前 `userId` 固定为 `oafConfig.vendorKey()`。要实现真正的多租户，需从请求中提取 userId：

#### A2A JSON-RPC 请求

从 `params.metadata.userId` 提取：

```java
// A2AController.handleMessageSend()
var metadata = (Map<String, Object>) params.get("metadata");
var userId = metadata != null ? (String) metadata.get("userId") : null;
if (userId == null || userId.isBlank()) {
    userId = oafConfig.vendorKey(); // 默认值
}
```

客户端请求示例：
```json
{
  "jsonrpc": "2.0",
  "method": "message/send",
  "params": {
    "message": {"role": "user", "parts": [{"kind": "text", "text": "hello"}]},
    "metadata": {"thread_id": "t1", "userId": "alice"}
  },
  "id": "1"
}
```

#### Channel SSE 请求

通过 `SendOptions.userId()` 指定（已支持）：

```bash
curl -N "http://localhost:8100/chat/stream?message=hello&userId=alice"
```

#### HTTP Header（可选）

从 `X-User-Id` header 提取：

```java
var userId = request.getHeader("X-User-Id");
if (userId == null || userId.isBlank()) {
    userId = oafConfig.vendorKey();
}
```

---

## 4. 实施计划

### 4.1 阶段划分

| 阶段 | 内容 | 工作量 | 状态 |
|---|---|---|---|
| 阶段一 | MysqlDistributedStore + IsolationScope.USER | 1 天 | ✅ 已完成 |
| 阶段二 | userId 从请求中提取（A2A metadata + Channel SendOptions） | 0.5 天 | ✅ 已完成 |

### 4.2 向后兼容

- **userId 为空时自动降级**：`IsolationScope.USER` 下 userId 为空时降级为 `SESSION` scope
- **默认值回退**：未指定 userId 时使用 `oafConfig.vendorKey()` 作为默认值
- **现有行为不变**：Channel SSE 通过 `SendOptions.userId()` 已支持多租户

### 4.3 与认证方案的关系

本方案**不依赖认证鉴权**。多租户隔离通过 AgentScope 原生机制实现，userId 由调用方显式传递。如需后续添加认证（JWT/SSO），只需在 Filter 层提取 userId 并设置到请求上下文，无需修改核心隔离逻辑。

---

## 5. 数据库查询示例

```sql
-- 查看所有用户的命名空间
SELECT DISTINCT namespace_path FROM agent_fs WHERE namespace_path LIKE 'agents/%/users/%';

-- 用户 alice 的 MEMORY.md
SELECT * FROM agent_fs 
WHERE namespace_path LIKE 'agents/test-agent/users/acme%' AND item_key = '/MEMORY.md';

-- 不同用户的记忆完全隔离
SELECT namespace_path, item_key, LENGTH(value_json) as len 
FROM agent_fs 
WHERE item_key LIKE '/memory/%'
ORDER BY namespace_path;
```

---

## 6. 相关文档

| 文档 | 说明 |
|------|------|
| [AgentScope 文件系统](https://java.agentscope.io/v2/zh/docs/harness/filesystem.html) | IsolationScope 官方文档 |
| [AgentScope Context](https://java.agentscope.io/v2/zh/docs/building-blocks/context.html) | RuntimeContext + AgentState 官方文档 |
| [MySQL 会话持久化方案](mysql-session-persistence-plan.md) | MysqlDistributedStore 升级 |
| [MySQL 文件系统方案](mysql-filesystem-plan.md) | RemoteFilesystemSpec 配置 |
| [OAF 改进方案](oaf-improvement-plan.md) | OAF → Workspace 转换 |

---

## 7. 验证用例

### 7.1 测试 LLM 配置

```yaml
agent:
  llm:
    api-key: ${LLM_API_KEY}
    model-id: LongCat-2.0
    base-url: https://api.longcat.chat/openai/v1
    provider: openai
    temperature: 0.2
    max-tokens: 50
```

### 7.2 验证用例

#### TC-MT-01: 不同 userId AgentState 隔离

```java
@Test
void testAgentStateIsolationByUserId() {
    var ctxA = RuntimeContext.builder()
        .userId("alice").sessionId("s1").build();
    var ctxB = RuntimeContext.builder()
        .userId("bob").sessionId("s2").build();

    agent.call(List.of(new UserMessage("user", "记住：我的密码是 secret123")), ctxA).block();
    var result = agent.call(List.of(new UserMessage("user", "Alice 的密码是什么？")), ctxB).block();

    assertThat(result.getTextContent()).doesNotContain("secret123");
}
```

#### TC-MT-02: 不同 userId 工作区文件隔离

```java
@Test
void testWorkspaceFileIsolationByUserId() throws Exception {
    var ctxA = RuntimeContext.builder()
        .userId("alice").sessionId("s1").build();
    var ctxB = RuntimeContext.builder()
        .userId("bob").sessionId("s2").build();

    agent.call(List.of(new UserMessage("user", "请只回复 welcome")), ctxA).block();
    agent.call(List.of(new UserMessage("user", "请只回复 welcome")), ctxB).block();
    Thread.sleep(5000);

    var namespaces = jdbcTemplate.queryForList(
        "SELECT DISTINCT namespace_path FROM agent_fs WHERE namespace_path LIKE 'agents/%/users/%'");
    assertThat(namespaces.size()).isGreaterThanOrEqualTo(2);
}
```

#### TC-MT-03: 同一 userId 不同 sessionId 共享记忆

```java
@Test
void testSameUserDifferentSessionsShareMemory() throws Exception {
    var ctx1 = RuntimeContext.builder()
        .userId("alice").sessionId("s1").build();
    var ctx2 = RuntimeContext.builder()
        .userId("alice").sessionId("s2").build();

    agent.call(List.of(new UserMessage("user", "记住：我喜欢蓝色")), ctx1).block();
    Thread.sleep(5000);

    var result = agent.call(List.of(new UserMessage("user", "我喜欢什么颜色？")), ctx2).block();
    assertThat(result.getTextContent()).containsIgnoringCase("蓝色");
}
```

#### TC-MT-04: 并发不同用户无竞争

```java
@Test
void testConcurrentDifferentUsersNoRace() {
    var alice = agent.call(List.of(new UserMessage("user", "Hi")),
        RuntimeContext.builder().userId("alice").sessionId("s1").build());
    var bob = agent.call(List.of(new UserMessage("user", "Hi")),
        RuntimeContext.builder().userId("bob").sessionId("s2").build());

    Mono.zip(alice, bob).block();
}
```

#### TC-MT-05: 同一用户同一会话自动串行

```java
@Test
void testSameUserSameSessionSerialized() {
    var ctx = RuntimeContext.builder()
        .userId("alice").sessionId("s1").build();

    var call1 = agent.call(List.of(new UserMessage("user", "第一句")), ctx);
    var call2 = agent.call(List.of(new UserMessage("user", "第二句")), ctx);

    Flux.merge(call1, call2).collectList().block();
}
```

#### TC-MT-06: LLM 连通性

```bash
curl -s "https://api.longcat.chat/openai/v1/chat/completions" \
  -H "Authorization: Bearer ${LLM_API_KEY}" \
  -H "Content-Type: application/json" \
  -d '{"model":"LongCat-2.0","messages":[{"role":"user","content":"请只回复 welcome"}],"max_tokens":50,"temperature":0.2}'
```

#### TC-MT-07: SQL 验证隔离

```sql
-- 查看所有用户的命名空间
SELECT DISTINCT namespace_path FROM agent_fs WHERE namespace_path LIKE 'agents/%/users/%';

-- 验证不同用户的 MEMORY.md 完全隔离
SELECT namespace_path, item_key FROM agent_fs
WHERE item_key = '/MEMORY.md' AND namespace_path LIKE 'agents/%/users/%'
ORDER BY namespace_path;
```
