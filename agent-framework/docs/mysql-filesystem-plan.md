# MySQL 文件系统实现方案

## 1. 现状分析

### 1.1 当前 MySQL 持久化

当前仅使用 `MysqlAgentStateStore` 持久化 AgentState（运行时状态）：

```java
// AgentScopeConfig.java:84-86
@Bean
public AgentStateStore agentStateStore(DataSource dataSource) {
    return new MysqlAgentStateStore(dataSource, "agent_manager_test", "agent_state", true);
}
```

**表结构**:
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

**局限性**: 仅存储运行时状态（对话缓冲、压缩摘要等），**不存储工作区文件**（MEMORY.md、skills/、memory/、subagents/ 等）。

### 1.2 AgentScope 文件系统架构

AgentScope 2.0 提供三种文件系统模式：

| 模式 | 配置 | Shell | 适用场景 |
|---|---|---|---|
| **共享存储** | `RemoteFilesystemSpec(store)` | ❌ | 多副本共享 MEMORY.md/对话日志 |
| **沙箱** | `DockerFilesystemSpec()` | ✅ (容器内) | 隔离执行、不可信代码 |
| **本机** | `LocalFilesystemSpec()` (默认) | ✅ (宿主) | 单进程/开发环境 |

**共享存储模式**使用 `BaseStore` 接口，AgentScope 提供以下实现：

| 实现 | 说明 |
|---|---|
| `RedisStore` | 基于 Jedis，低延迟高并发 |
| `JdbcStore` | 基于 JDBC，支持 MySQL/PostgreSQL/H2 |
| `InMemoryStore` | 内存实现，适合测试 |

**关键发现**: `agentscope-extensions-mysql` 已包含 `JdbcStore` 和 `MysqlDistributedStore`，可直接使用。

---

## 2. 实现方案

### 2.1 方案选择：`MysqlDistributedStore` + `RemoteFilesystemSpec`

**选择理由**:
- `pom.xml` 已引入 `agentscope-extensions-mysql:2.0.0`
- `MysqlDistributedStore` 一键配置 `AgentStateStore` + `BaseStore`
- 复用现有 MySQL 连接池（HikariDataSource）
- 自动按 `IsolationScope` 实现多租户隔离
- 无需额外依赖（Redis 等）

### 2.2 架构对比

**当前架构**:
```
请求 → ReActAgent → MysqlAgentStateStore (仅 AgentState)
                     └── agent_state 表 (session_id, state_key, data)
```

**目标架构**:
```
请求 → HarnessAgent → MysqlDistributedStore
                        ├── MysqlAgentStateStore (AgentState)
                        │   └── agent_state 表
                        └── JdbcStore (工作区文件)
                            └── agent_fs 表 (namespace, path, content)
                                ├── MEMORY.md
                                ├── memory/YYYY-MM-DD.md
                                ├── skills/{name}/SKILL.md
                                ├── subagents/{id}.md
                                └── agents/{id}/sessions/*.jsonl
```

### 2.3 实现步骤

#### 第一步：修改 `config/AgentScopeConfig.java`

将 `MysqlAgentStateStore` 替换为 `MysqlDistributedStore`：

```java
@Bean
public io.agentscope.extensions.mysql.MysqlDistributedStore distributedStore(
    DataSource dataSource
) {
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

`MysqlDistributedStore` 自动创建两张表：
- `agent_state` — AgentState 持久化（与当前相同）
- `agent_fs` — 工作区文件 KV 存储（新增）

#### 第二步：修改 `config/AgentScopeConfig.java` 使用 `HarnessAgent`

```java
@Bean
public io.agentscope.harness.agent.HarnessAgent harnessAgent(
    AgentManagerProperties props,
    MysqlDistributedStore distributedStore,
    OafConfig oafConfig,
    WorkspaceInitializer workspaceInitializer
) {
    var llm = props.llm();
    var workspacePath = workspaceInitializer.initialize(
        Path.of(props.configDir()), oafConfig);

    var model = io.agentscope.extensions.model.openai.OpenAIChatModel.builder()
        .apiKey(llm.apiKey())
        .modelName(llm.modelId())
        .baseUrl(llm.baseUrl())
        .build();

    return io.agentscope.harness.agent.HarnessAgent.builder()
        .name(oafConfig.name())
        .sysPrompt(oafConfig.systemPrompt())
        .model(model)
        .workspace(workspacePath)
        .distributedStore(distributedStore)              // 一键配置 stateStore + baseStore
        .filesystem(new RemoteFilesystemSpec()
            .isolationScope(IsolationScope.USER))        // 按 userId 隔离
        .enablePlanMode()
        .compaction(CompactionConfig.builder()
            .maxContextTokens(llm.maxTokens() * 4)
            .build())
        .build();
}
```

#### 第三步：删除旧的 `agentStateStore` Bean

```java
// 删除以下 Bean（由 MysqlDistributedStore 自动提供）
// @Bean
// public AgentStateStore agentStateStore(DataSource dataSource) {
//     return new MysqlAgentStateStore(dataSource, "agent_manager_test", "agent_state", true);
// }
```

### 2.4 数据库表结构

#### 表 1: `agent_state`（已有，不变）

```sql
CREATE TABLE agent_state (
    session_id VARCHAR(255) NOT NULL,
    state_key  VARCHAR(255) NOT NULL,
    item_index INT NOT NULL DEFAULT 0,
    state_data LONGTEXT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, state_key, item_index)
);
```

#### 表 2: `agent_fs`（新增，JdbcStore 自动创建）

```sql
CREATE TABLE agent_fs (
    namespace VARCHAR(255) NOT NULL,    -- 命名空间（如 agent name）
    path      VARCHAR(512) NOT NULL,    -- 文件相对路径（如 MEMORY.md）
    content   LONGBLOB,                -- 文件内容
    metadata  TEXT,                     -- JSON 元数据（大小、修改时间等）
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (namespace, path)
);
```

### 2.5 命名空间隔离

`RemoteFilesystemSpec` 的 `IsolationScope` 决定命名空间前缀：

| Scope | 命名空间键 | 效果 |
|---|---|---|
| `USER`（默认） | `agents/{agentId}/users/{userId}/...` | 每个用户独立的工作区 |
| `SESSION` | `agents/{agentId}/sessions/{sessionId}/...` | 每个会话独立 |
| `AGENT` | `agents/{agentId}/shared/...` | 所有用户共享 |
| `GLOBAL` | `global/...` | 全局共享 |

**示例**: 用户 alice 的 MEMORY.md 存储路径：
```
namespace: agents/MyAgent/users/alice
path: MEMORY.md
```

### 2.6 内置路由规则

框架自动将以下路径路由到 MySQL KV：

| 路径 | KV 命名空间段 |
|---|---|
| `AGENTS.md`, `MEMORY.md`, `tools.json` | `root` |
| `memory/` | `memory` |
| `skills/` | `skills` |
| `subagents/` | `subagents` |
| `knowledge/` | `knowledge` |
| `agents/<agentId>/sessions/` | `sessions` |
| `agents/<agentId>/tasks/` | `tasks` |

不在上表的路径落到本地 `LocalFilesystem`（无 shell）。

---

## 3. 完整配置示例

### 3.1 `AgentScopeConfig.java` 最终版本

```java
package io.agentmanager.framework.config;

import java.nio.file.Path;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.zaxxer.hikari.HikariDataSource;

import io.agentmanager.framework.model.OafConfig;
import io.agentmanager.framework.service.WorkspaceInitializer;
import io.agentscope.extensions.mysql.MysqlDistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.compaction.CompactionConfig;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.filesystem.IsolationScope;

@Configuration
public class AgentScopeConfig {
    private static final Logger log = LoggerFactory.getLogger(AgentScopeConfig.class);

    @Bean
    public DataSource dataSource(AgentManagerProperties props) {
        var cp = props.checkpoint();
        var ds = new HikariDataSource();
        ds.setJdbcUrl(cp.jdbcUrl());
        ds.setUsername(cp.username());
        ds.setPassword(cp.password());
        ds.setMaximumPoolSize(10);
        ds.setMinimumIdle(2);
        ds.setConnectionTimeout(30000);
        ds.setIdleTimeout(600000);
        ds.setMaxLifetime(1800000);
        return ds;
    }

    @Bean
    public MysqlDistributedStore distributedStore(DataSource dataSource) {
        var store = DistributedStore.builder()
            .agentStateStore(new MysqlAgentStateStore(
                dataSource, "agent_manager_test", "agent_state", true))
            .baseStore(JdbcStore.builder(dataSource)
                .tableName("agent_fs")
                .initializeSchema(true)
                .build())
            .build();
        log.info("DistributedStore initialized (agent_manager_test.agent_state + agent_fs)");
        return store;
    }

    @Bean
    public OafConfig oafConfig(OafConfigLoader loader) {
        var config = loader.load();
        log.info("Loaded OAF: {} v{}", config.name(), config.version());
        return config;
    }

    @Bean
    public HarnessAgent harnessAgent(
        AgentManagerProperties props,
        MysqlDistributedStore distributedStore,
        OafConfig oafConfig,
        WorkspaceInitializer workspaceInitializer
    ) {
        var llm = props.llm();
        var workspacePath = workspaceInitializer.initialize(
            Path.of(props.configDir()), oafConfig);

        var model = io.agentscope.extensions.model.openai.OpenAIChatModel.builder()
            .apiKey(llm.apiKey())
            .modelName(llm.modelId())
            .baseUrl(llm.baseUrl())
            .build();

        var agent = HarnessAgent.builder()
            .name(oafConfig.name())
            .sysPrompt(oafConfig.systemPrompt())
            .model(model)
            .workspace(workspacePath)
            .distributedStore(distributedStore)
            .filesystem(new RemoteFilesystemSpec()
                .isolationScope(IsolationScope.USER))
            .enablePlanMode()
            .compaction(CompactionConfig.builder()
                .maxContextTokens(llm.maxTokens() * 4)
                .build())
            .build();

        log.info("HarnessAgent created: {} (model: {}, workspace: {}, filesystem: MySQL)",
            oafConfig.name(), llm.modelId(), workspacePath);
        return agent;
    }
}
```

### 3.2 `application.yml` 配置

```yaml
agent:
  config-dir: ${AGENT_CONFIG_DIR:/config}
  llm:
    api-key: ${LLM_API_KEY:}
    model-id: ${LLM_MODEL_ID:}
    base-url: ${LLM_BASE_URL:}
    provider: ${LLM_PROVIDER:openai}
    temperature: ${LLM_TEMPERATURE:0.7}
    max-tokens: ${LLM_MAX_TOKENS:4096}
    timeout: ${LLM_TIMEOUT:120}
  checkpoint:
    jdbc-url: ${CHECKPOINT_JDBC_URL:jdbc:mysql://127.0.0.1:3307/agent_manager_test}
    username: ${CHECKPOINT_USERNAME:agent_manager}
    password: ${CHECKPOINT_PASSWORD:Agent@Manager2026}
```

---

## 4. 数据流示意

### 4.1 写入流程

```
Agent call()
  ├── 对话过程
  │   ├── 工具结果落盘 → agent_fs (namespace=..., path=agents/{id}/evictions/...)
  │   └── 会话日志追加 → agent_fs (namespace=..., path=agents/{id}/sessions/{sid}.log.jsonl)
  ├── 对话结束
  │   ├── MemoryFlushMiddleware → agent_fs (namespace=..., path=memory/YYYY-MM-DD.md)
  │   ├── AgentState 序列化 → agent_state (session_id=..., state_key=agent_state)
  │   └── 计划文件写入 → agent_fs (namespace=..., path=plans/PLAN.md)
  └── 后台任务
      └── MemoryConsolidator → agent_fs (namespace=..., path=MEMORY.md)
```

### 4.2 读取流程

```
Agent call() 开始
  ├── 加载 AgentState ← agent_state (session_id=..., state_key=agent_state)
  ├── WorkspaceContextMiddleware 拼装 system prompt
  │   ├── 读 AGENTS.md ← agent_fs (两层读: 先 MySQL, 后本地模板)
  │   ├── 读 MEMORY.md ← agent_fs (受预算约束)
  │   ├── 读 knowledge/KNOWLEDGE.md ← agent_fs
  │   └── 读 skills/ → DynamicSkillMiddleware → <available_skills> 块
  └── ReAct 循环开始
      ├── 模型调用
      ├── 工具执行 (read_file/write_file/edit_file → agent_fs)
      └── 返回结果
```

---

## 5. 限制与注意事项

### 5.1 无 Shell 支持

`RemoteFilesystemSpec` 模式**不提供 shell**。如果需要执行 shell 命令（如 `execute_shell_command`），需要：
- 使用 `LocalFilesystemSpec`（本机模式）
- 或使用 `DockerFilesystemSpec`（沙箱模式）

**解决方案**: 对于需要 shell 的场景，使用沙箱模式 + MySQL 共享存储：
```java
.distributedStore(distributedStore)
.filesystem(new DockerFilesystemSpec()
    .image("ubuntu:24.04")
    .isolationScope(IsolationScope.USER))
```

### 5.2 静态资产 vs 运行时数据

| 类型 | 存储位置 | 说明 |
|---|---|---|
| **静态资产** (AGENTS.md, skills/, knowledge/) | MySQL + 本地模板 | 两层读：先 MySQL，后本地 |
| **运行时数据** (MEMORY.md, memory/, sessions/) | MySQL | 完全在 MySQL 中 |
| **AgentState** | MySQL agent_state 表 | 与当前相同 |

### 5.3 性能考虑

- MySQL 读写延迟 ~1-5ms（本机）/ ~5-20ms（远程），适合文件级操作
- 大文件（>1MB）建议分块存储或使用 OSS
- 频繁读取的文件（AGENTS.md, MEMORY.md）由框架缓存，不会每次查 MySQL

### 5.4 多副本部署

多 pod 部署时：
- 所有 pod 共享同一个 MySQL 数据库
- 静态资产（AGENTS.md, skills/）通过 git 同步到各 pod 本地作为模板
- 运行时数据（MEMORY.md, memory/）自动存到 MySQL，任意 pod 可读
- AgentState 自动持久化到 MySQL，支持跨 pod 恢复

---

## 6. 迁移步骤

### 6.1 数据库迁移

```sql
-- 1. 备份现有 agent_state 表
CREATE TABLE agent_state_backup AS SELECT * FROM agent_state;

-- 2. MysqlDistributedStore 会自动创建 agent_fs 表
-- 无需手动创建

-- 3. 验证
SHOW TABLES;  -- 应看到 agent_state 和 agent_fs
```

### 6.2 代码迁移

| 步骤 | 文件 | 变更 |
|---|---|---|
| 1 | `pom.xml` | 无需变更（已包含 `agentscope-extensions-mysql`） |
| 2 | `AgentScopeConfig.java` | 替换 `agentStateStore` Bean 为 `distributedStore` Bean |
| 3 | `AgentScopeConfig.java` | 替换 `reactAgent` Bean 为 `harnessAgent` Bean |
| 4 | `AgentRuntimeService.java` | 适配 `HarnessAgent` API（`call()` 签名兼容） |
| 5 | 新增 `WorkspaceInitializer.java` | OAF → Workspace 转换 |

### 6.3 验证清单

- [ ] MySQL 中 `agent_fs` 表自动创建
- [ ] `MEMORY.md` 写入 MySQL 并可读取
- [ ] `memory/YYYY-MM-DD.md` 自动追加
- [ ] `skills/` 从本地模板加载
- [ ] 多用户隔离：不同 userId 的 MEMORY.md 互不影响
- [ ] AgentState 跨会话恢复正常

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

#### TC-FS-01: MysqlDistributedStore 创建 agent_fs 表

```java
@Test
void testAgentFsTableCreated() {
    var store = DistributedStore.builder()
        .agentStateStore(new MysqlAgentStateStore(
            dataSource, "agent_manager_test", "agent_state", true))
        .baseStore(JdbcStore.builder(dataSource)
            .tableName("agent_fs")
            .initializeSchema(true)
            .build())
        .build();

    var tables = jdbcTemplate.queryForList("SHOW TABLES LIKE 'agent_fs'");
    assertThat(tables).isNotEmpty();
}
```

#### TC-FS-02: MEMORY.md 写入 MySQL

```java
@Test
void testMemoryMdWrittenToMysql() {
    var ctx = RuntimeContext.builder()
        .userId("test-user").sessionId("s1").build();

    // 多轮对话触发 flush
    agent.call(List.of(new UserMessage("user", "记住：我喜欢猫")), ctx).block();
    Thread.sleep(5000); // 等待异步 flush

    var rows = jdbcTemplate.queryForList(
        "SELECT * FROM agent_fs WHERE path = 'MEMORY.md'");
    assertThat(rows).isNotEmpty();
}
```

#### TC-FS-03: memory/ 目录自动追加

```java
@Test
void testMemoryDirectoryAutoAppend() {
    var ctx = RuntimeContext.builder()
        .userId("test-user").sessionId("s2").build();

    agent.call(List.of(new UserMessage("user", "请只回复 welcome")), ctx).block();
    Thread.sleep(5000);

    var today = java.time.LocalDate.now().toString();
    var rows = jdbcTemplate.queryForList(
        "SELECT * FROM agent_fs WHERE path = ?",
        "memory/" + today + ".md");
    assertThat(rows).isNotEmpty();
}
```

#### TC-FS-04: 不同 userId 的 MEMORY.md 隔离

```java
@Test
void testMemoryIsolationByUserId() {
    var ctxA = RuntimeContext.builder()
        .userId("alice").sessionId("s1").build();
    var ctxB = RuntimeContext.builder()
        .userId("bob").sessionId("s2").build();

    agent.call(List.of(new UserMessage("user", "记住：我的秘密是 X")), ctxA).block();
    agent.call(List.of(new UserMessage("user", "请只回复 welcome")), ctxB).block();
    Thread.sleep(5000);

    // 验证 namespace 不同
    var aliceRows = jdbcTemplate.queryForList(
        "SELECT namespace FROM agent_fs WHERE namespace LIKE '%alice%'");
    var bobRows = jdbcTemplate.queryForList(
        "SELECT namespace FROM agent_fs WHERE namespace LIKE '%bob%'");

    assertThat(aliceRows).isNotEmpty();
    assertThat(bobRows).isNotEmpty();
    assertThat(aliceRows.get(0).get("namespace"))
        .isNotEqualTo(bobRows.get(0).get("namespace"));
}
```

#### TC-FS-05: LLM 调用验证

```bash
curl -s "https://api.longcat.chat/openai/v1/chat/completions" \
  -H "Authorization: Bearer ${LLM_API_KEY}" \
  -H "Content-Type: application/json" \
  -d '{"model":"LongCat-2.0","messages":[{"role":"user","content":"请只回复 welcome"}],"max_tokens":50,"temperature":0.2}'
```

#### TC-FS-06: SQL 验证数据

```sql
-- 查看所有命名空间
SELECT DISTINCT namespace FROM agent_fs;

-- 查看用户 alice 的文件
SELECT path, LENGTH(content) as size FROM agent_fs
WHERE namespace LIKE '%alice%';

-- 查看 MEMORY.md 内容
SELECT CONVERT(content USING utf8mb4) as content FROM agent_fs
WHERE path = 'MEMORY.md' LIMIT 1;
```
