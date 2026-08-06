# MySQL 会话持久化改进方案（基于 AgentScope Context 文档）

## 1. 现状分析

### 1.1 当前实现

```java
// AgentScopeConfig.java:83-86
@Bean
public AgentStateStore agentStateStore(DataSource dataSource) {
    return new MysqlAgentStateStore(dataSource, "agent_manager_test", "agent_state", true);
}
```

使用 `MysqlAgentStateStore` 持久化 `AgentState` 到 MySQL，表结构：

```sql
CREATE TABLE agent_state (
    session_id VARCHAR(255) NOT NULL,  -- {userId}:{sessionId}
    state_key  VARCHAR(255) NOT NULL,
    item_index INT NOT NULL DEFAULT 0,
    state_data LONGTEXT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, state_key, item_index)
);
```

### 1.2 对照 AgentScope Context 文档的评估

| 官方要求 | 当前实现 | 状态 |
|---|---|---|
| 实现 `AgentStateStore` 接口 | `MysqlAgentStateStore` | ✅ 正确 |
| 按 `(userId, sessionId)` 寻址 | `RuntimeContext(sessionId, userId)` | ✅ 正确 |
| call 结束时自动持久化 | AgentScope 框架自动处理 | ✅ 正确 |
| 跨节点恢复 | MySQL 共享，任意节点可恢复 | ✅ 正确 |
| 同 (uid,sid) 串行，不同并行 | `HarnessAgent` 自动处理 | ⚠️ 当前用 `ReActAgent`，需升级 |
| 分布式工作区支持 | `MysqlAgentStateStore` 不含 `BaseStore` | ❌ 需升级 |

### 1.3 关键发现

根据文档：

> 如果你已经在用 `filesystem(RemoteFilesystemSpec)`（分布式工作区），HarnessAgent 会**强制要求**状态存储也换成分布式后端，否则 `build()` 直接抛 `IllegalStateException`。

**结论**: 要使用 Harness 功能（Workspace、Memory、Compaction），必须将 `MysqlAgentStateStore` 升级为 `MysqlDistributedStore`。

---

## 2. 改进方案

### 2.1 组件关系

```
当前:
  MysqlAgentStateStore ──→ agent_state 表 (仅 AgentState)
  ReActAgent ──→ .stateStore(store)

目标:
  MysqlDistributedStore ──→ MysqlAgentStateStore (agent_state 表)
                         └→ JdbcStore (agent_fs 表, 工作区文件)
  HarnessAgent ──→ .distributedStore(store)
               └→ .filesystem(RemoteFilesystemSpec)
```

### 2.2 `MysqlDistributedStore` vs `MysqlAgentStateStore`

| 维度 | `MysqlAgentStateStore` | `MysqlDistributedStore` |
|---|---|---|
| AgentState 持久化 | ✅ | ✅ (内部包含) |
| 工作区文件存储 | ❌ | ✅ (JdbcStore) |
| 自动建表 | `agent_state` | `agent_state` + `agent_fs` |
| 与 `RemoteFilesystemSpec` 兼容 | ❌ | ✅ |
| 与 `HarnessAgent` 兼容 | 需手动配置 | ✅ 一键配置 |

### 2.3 数据库变更

`MysqlDistributedStore` 自动创建第二张表：

```sql
-- 已有表（不变）
CREATE TABLE agent_state (
    session_id VARCHAR(255) NOT NULL,
    state_key  VARCHAR(255) NOT NULL,
    item_index INT NOT NULL DEFAULT 0,
    state_data LONGTEXT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, state_key, item_index)
);

-- 新增表（JdbcStore 自动创建）
CREATE TABLE agent_fs (
    namespace VARCHAR(255) NOT NULL,    -- 命名空间（如 agents/MyAgent/users/alice）
    path      VARCHAR(512) NOT NULL,    -- 文件相对路径（如 MEMORY.md）
    content   LONGBLOB,                -- 文件内容
    metadata  TEXT,                     -- JSON 元数据
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (namespace, path)
);
```

---

## 3. 代码变更清单

### 3.1 `config/AgentScopeConfig.java`

#### 变更 1: 替换导入

```java
// 删除
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;

// 新增
import io.agentscope.extensions.mysql.MysqlDistributedStore;
```

#### 变更 2: 替换 `agentStateStore` Bean 为 `distributedStore`

```java
// 删除
@Bean
public io.agentscope.core.state.AgentStateStore agentStateStore(DataSource dataSource) {
    return new MysqlAgentStateStore(dataSource, "agent_manager_test", "agent_state", true);
}

// 新增
@Bean
public MysqlDistributedStore distributedStore(DataSource dataSource) {
    return DistributedStore.builder()
        .agentStateStore(new MysqlAgentStateStore(
            dataSource, "agent_manager_test", "agent_state", true))
        .baseStore(JdbcStore.builder(dataSource)
            .tableName("agent_fs")
            .initializeSchema(true)
            .build())
        .build();
}
```

#### 变更 3: 替换 `reactAgent` Bean

```java
// 删除
@Bean
public ReActAgent reactAgent(
    AgentManagerProperties props,
    io.agentscope.core.state.AgentStateStore stateStore,
    OafConfig oafConfig
) { ... }

// 新增
@Bean
public io.agentscope.harness.agent.HarnessAgent harnessAgent(
    AgentManagerProperties props,
    MysqlDistributedStore distributedStore,
    OafConfig oafConfig
) {
    var llm = props.llm();

    var model = io.agentscope.extensions.model.openai.OpenAIChatModel.builder()
        .apiKey(llm.apiKey())
        .modelName(llm.modelId())
        .baseUrl(llm.baseUrl())
        .build();

    var workspacePath = java.nio.file.Path.of(props.configDir(), ".agentscope", "workspace");

    return io.agentscope.harness.agent.HarnessAgent.builder()
        .name(oafConfig.name())
        .sysPrompt(oafConfig.systemPrompt())
        .model(model)
        .workspace(workspacePath)
        .distributedStore(distributedStore)                          // 一键配置 stateStore + baseStore
        .filesystem(new io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec()
            .isolationScope(io.agentscope.harness.agent.filesystem.IsolationScope.USER))
        .enablePlanMode()
        .compaction(io.agentscope.harness.agent.compaction.CompactionConfig.builder()
            .maxContextTokens(llm.maxTokens() * 4)
            .build())
        .build();
}
```

#### 变更 4: 更新 `agentRuntimeService` Bean

```java
// 删除
@Bean
public AgentRuntimeService agentRuntimeService(
    OafConfig oafConfig,
    ReActAgent reactAgent,
    List<SkillManager.SkillInfo> loadedSkills,
    List<java.util.Map<String, Object>> mcpConfigs,
    LLMLogger llmLogger
) {
    return new AgentRuntimeService(oafConfig, reactAgent, loadedSkills, mcpConfigs, llmLogger);
}

// 新增（HarnessAgent 继承 ReActAgent，类型兼容）
@Bean
public AgentRuntimeService agentRuntimeService(
    OafConfig oafConfig,
    io.agentscope.harness.agent.HarnessAgent harnessAgent,
    List<SkillManager.SkillInfo> loadedSkills,
    List<java.util.Map<String, Object>> mcpConfigs,
    LLMLogger llmLogger
) {
    return new AgentRuntimeService(oafConfig, harnessAgent, loadedSkills, mcpConfigs, llmLogger);
}
```

### 3.2 `service/AgentRuntimeService.java`

#### 变更 1: 字段类型

```java
// 删除
private io.agentscope.core.ReActAgent agent;

// 新增（HarnessAgent 继承 ReActAgent，API 兼容）
private io.agentscope.core.ReActAgent agent;  // 保持不变，HarnessAgent 是 ReActAgent 子类
```

**说明**: `HarnessAgent` 继承 `ReActAgent`，`call()` 和 `streamEvents()` API 完全兼容，`AgentRuntimeService` 无需修改。

### 3.3 `pom.xml`

**无需变更**。已包含所需依赖：

```xml
<!-- 已有 -->
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-harness</artifactId>
    <version>2.0.0</version>
</dependency>
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-mysql</artifactId>
    <version>2.0.0</version>
</dependency>
```

`MysqlDistributedStore` 在 `agentscope-extensions-mysql` 中，`HarnessAgent` 在 `agentscope-harness` 中。

---

## 4. 数据流对比

### 4.1 当前数据流

```
call(msg, RuntimeContext(userId, sessionId))
  │
  ▼
从 MysqlAgentStateStore 加载 AgentState
  │   SELECT * FROM agent_state WHERE session_id='{userId}:{sessionId}'
  │
  ▼
推理循环 (ReActAgent)
  │   中间件改写 state.contextMutable()
  │
  ▼
保存 AgentState
  │   INSERT/UPDATE agent_state SET state_data=... WHERE session_id='{userId}:{sessionId}'
  │
  ▼
返回结果
```

### 4.2 改进后数据流

```
call(msg, RuntimeContext(userId, sessionId))
  │
  ▼
从 MysqlDistributedStore 加载 AgentState
  │   SELECT * FROM agent_state WHERE session_id='{userId}:{sessionId}'
  │
  ▼
WorkspaceContextMiddleware 拼装 system prompt
  │   读 AGENTS.md ← agent_fs (两层读: 先 MySQL, 后本地模板)
  │   读 MEMORY.md ← agent_fs (受预算约束)
  │   读 skills/ → DynamicSkillMiddleware → <available_skills> 块
  │
  ▼
推理循环 (HarnessAgent)
  │   中间件改写 state.contextMutable()
  │   MemoryFlushMiddleware → 写 memory/YYYY-MM-DD.md → agent_fs
  │   工具结果落盘 → agent_fs
  │
  ▼
保存 AgentState + 工作区文件
  │   INSERT/UPDATE agent_state ...
  │   INSERT/UPDATE agent_fs (MEMORY.md, memory/, sessions/)
  │
  ▼
后台任务
  │   MemoryConsolidator → 合并 memory/ → MEMORY.md → agent_fs
  │
  ▼
返回结果
```

---

## 5. 多租户隔离

### 5.1 AgentState 隔离（已有）

`MysqlAgentStateStore` 按 `(userId, sessionId)` 寻址：

```sql
-- 用户 alice 的会话
SELECT * FROM agent_state WHERE session_id = 'alice:thread-001';

-- 用户 bob 的会话
SELECT * FROM agent_state WHERE session_id = 'bob:thread-001';
```

### 5.2 工作区文件隔离（新增）

`RemoteFilesystemSpec` 的 `IsolationScope.USER` 自动按 userId 隔离：

```sql
-- 用户 alice 的 MEMORY.md
SELECT * FROM agent_fs WHERE namespace = 'agents/MyAgent/users/alice' AND path = 'MEMORY.md';

-- 用户 bob 的 MEMORY.md
SELECT * FROM agent_fs WHERE namespace = 'agents/MyAgent/users/bob' AND path = 'MEMORY.md';
```

### 5.3 隔离矩阵

| 数据类型 | 隔离维度 | 存储位置 | 说明 |
|---|---|---|---|
| AgentState | `(userId, sessionId)` | `agent_state` 表 | 每个会话独立 |
| MEMORY.md | `userId` | `agent_fs` 表 | 每个用户独立 |
| memory/ | `userId` | `agent_fs` 表 | 每个用户独立 |
| skills/ | 共享 + 用户覆盖 | `agent_fs` 表 | 共享底座 + 用户定制 |
| sessions/ | `userId` | `agent_fs` 表 | 每个用户独立 |

---

## 6. 迁移步骤

### 6.1 数据库

```sql
-- 1. 备份
CREATE TABLE agent_state_backup AS SELECT * FROM agent_state;

-- 2. MysqlDistributedStore 启动时自动创建 agent_fs 表
-- 无需手动操作

-- 3. 验证
SHOW TABLES;  -- 应看到 agent_state 和 agent_fs
```

### 6.2 代码

1. 修改 `AgentScopeConfig.java`（3 处变更，见第 3 节）
2. `AgentRuntimeService.java` 无需修改
3. `pom.xml` 无需修改

### 6.3 验证清单

- [ ] `agent_fs` 表自动创建
- [ ] `MysqlDistributedStore` Bean 正常注入
- [ ] `HarnessAgent` Bean 正常创建
- [ ] `call()` 正常返回结果
- [ ] `streamEvents()` 正常返回事件流
- [ ] `MEMORY.md` 写入 `agent_fs` 表
- [ ] 不同 userId 的 MEMORY.md 互不影响
- [ ] AgentState 跨会话恢复正常

---

## 7. 验证用例

### 7.1 测试 LLM 配置

所有验证用例使用以下 LLM 配置：

```yaml
# application-test.yml
agent:
  llm:
    api-key: ${LLM_API_KEY}
    model-id: LongCat-2.0
    base-url: https://api.longcat.chat/openai/v1
    provider: openai
    temperature: 0.2
    max-tokens: 50
    timeout: 30
  checkpoint:
    jdbc-url: jdbc:mysql://127.0.0.1:3307/agent_manager_test
    username: agent_manager
    password: Agent@Manager2026
```

### 7.2 验证用例

#### TC-SP-01: MysqlDistributedStore 自动建表

```java
@Test
void testDistributedStoreAutoCreateTables() {
    var store = DistributedStore.builder()
        .agentStateStore(new MysqlAgentStateStore(
            dataSource, "agent_manager_test", "agent_state", true))
        .baseStore(JdbcStore.builder(dataSource)
            .tableName("agent_fs")
            .initializeSchema(true)
            .build())
        .build();

    // 验证 agent_state 表存在
    var stateTable = jdbcTemplate.queryForList(
        "SHOW TABLES LIKE 'agent_state'");
    assertThat(stateTable).isNotEmpty();

    // 验证 agent_fs 表存在
    var fsTable = jdbcTemplate.queryForList(
        "SHOW TABLES LIKE 'agent_fs'");
    assertThat(fsTable).isNotEmpty();
}
```

#### TC-SP-02: HarnessAgent 创建成功

```java
@Test
void testHarnessAgentCreation() {
    var model = OpenAIChatModel.builder()
        .apiKey("${LLM_API_KEY}")
        .modelName("LongCat-2.0")
        .baseUrl("https://api.longcat.chat/openai/v1")
        .build();

    var agent = HarnessAgent.builder()
        .name("TestAgent")
        .sysPrompt("You are a test agent.")
        .model(model)
        .workspace(tempDir)
        .distributedStore(distributedStore)
        .build();

    assertThat(agent).isNotNull();
    assertThat(agent.getName()).isEqualTo("TestAgent");
}
```

#### TC-SP-03: LLM 调用正常返回

```java
@Test
void testLlmCallReturnsResult() {
    var ctx = RuntimeContext.builder()
        .userId("test-user")
        .sessionId("test-session-1")
        .build();

    var userMsg = new UserMessage("user", "请只回复 welcome");
    var result = agent.call(List.of(userMsg), ctx).block();

    assertThat(result).isNotNull();
    assertThat(result.getTextContent()).containsIgnoringCase("welcome");
}
```

#### TC-SP-04: AgentState 自动持久化

```java
@Test
void testAgentStatePersistedAfterCall() {
    var ctx = RuntimeContext.builder()
        .userId("test-user")
        .sessionId("test-session-2")
        .build();

    // 第一轮对话
    agent.call(List.of(new UserMessage("user", "记住：我的名字是 Alice")), ctx).block();

    // 验证 agent_state 表有数据
    var rows = jdbcTemplate.queryForList(
        "SELECT * FROM agent_state WHERE session_id LIKE '%test-session-2%'");
    assertThat(rows).isNotEmpty();
}
```

#### TC-SP-05: AgentState 跨会话恢复

```java
@Test
void testAgentStateRestoredOnNewCall() {
    var ctx = RuntimeContext.builder()
        .userId("test-user")
        .sessionId("test-session-3")
        .build();

    // 第一轮：告诉 agent 记住信息
    agent.call(List.of(new UserMessage("user", "记住：我的名字是 Bob")), ctx).block();

    // 第二轮：验证 agent 记住了
    var result = agent.call(List.of(new UserMessage("user", "我叫什么名字？")), ctx).block();
    assertThat(result.getTextContent()).containsIgnoringCase("Bob");
}
```

#### TC-SP-06: 工作区文件写入 agent_fs 表

```java
@Test
void testWorkspaceFileWrittenToAgentFs() {
    var ctx = RuntimeContext.builder()
        .userId("test-user")
        .sessionId("test-session-4")
        .build();

    // 触发对话，让 MemoryFlushMiddleware 写入 memory/
    agent.call(List.of(new UserMessage("user", "请只回复 welcome")), ctx).block();

    // 等待异步 flush 完成
    Thread.sleep(5000);

    // 验证 agent_fs 表有数据
    var rows = jdbcTemplate.queryForList(
        "SELECT * FROM agent_fs WHERE path LIKE 'memory/%'");
    assertThat(rows).isNotEmpty();
}
```

#### TC-SP-07: 不同 userId 数据隔离

```java
@Test
void testDifferentUserIdIsolation() {
    var ctxA = RuntimeContext.builder()
        .userId("alice").sessionId("s1").build();
    var ctxB = RuntimeContext.builder()
        .userId("bob").sessionId("s2").build();

    // Alice 告诉 agent 秘密
    agent.call(List.of(new UserMessage("user", "记住：我的密码是 12345")), ctxA).block();

    // Bob 问 agent
    var result = agent.call(List.of(new UserMessage("user", "Alice 的密码是什么？")), ctxB).block();

    // Bob 不应该知道 Alice 的密码
    assertThat(result.getTextContent()).doesNotContain("12345");
}
```

#### TC-SP-08: HTTP API 验证

```bash
# 测试 LLM 连通性
curl -s "https://api.longcat.chat/openai/v1/chat/completions" \
  -H "Authorization: Bearer ${LLM_API_KEY}" \
  -H "Content-Type: application/json" \
  -d '{"model":"LongCat-2.0","messages":[{"role":"user","content":"请只回复 welcome"}],"max_tokens":50,"temperature":0.2}'

# 预期响应
# {"choices":[{"message":{"content":"welcome"},"finish_reason":"stop"}],...}
```

### 7.3 验证环境准备

```bash
# 1. 确保 MySQL 可用
mysql -h 127.0.0.1 -P 3307 -u agent_manager -p'Agent@Manager2026' -e "SELECT 1"

# 2. 创建测试数据库（如不存在）
mysql -h 127.0.0.1 -P 3307 -u agent_manager -p'Agent@Manager2026' \
  -e "CREATE DATABASE IF NOT EXISTS agent_manager_test"

# 3. 运行测试
cd agent-framework
mvn test -Dtest="*SP*" -Dspring.profiles.active=test
```
