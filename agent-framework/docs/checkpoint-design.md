# Agent Framework — Checkpoint 持久化设计文档

**版本:** v2.1.0 (Java)
**日期:** 2026-08-06
**复核日期:** 2026-09-26

> **现状核对（2026-09-26）**：本篇被 docs/README.md 列为 **schema 权威**，但头部日期停在 2026-08-06，
> 且 SDK 已从 2.0.0 升到 **2.0.3**——带来 `agent_state` 的 `version` 列与 CAS 乐观锁语义（见 §10，
> 另见 [history-agentstate-design.md](history-agentstate-design.md) §10）。
> 本版订正组件版本、补齐 §5.1 的 version/CAS 说明、补全 §4.1 尾注漏掉的三张表。
> **权威范围**：§5 的表结构为权威；§2.3 组件树为快照，最新以 [../AGENTS.md](../AGENTS.md) 为准。

---

## 1. 概述

Agent Framework 使用 **AgentScope MySQL 扩展**实现会话持久化。通过 `MysqlDistributedStore` 统一持久化 AgentState 和工作区文件到 MySQL。

### 技术选型

| 组件 | 版本 | 用途 |
|------|------|------|
| agentscope-extensions-mysql | 2.0.3 | MysqlDistributedStore (MysqlAgentStateStore + JdbcStore) |
| agentscope-harness | 2.0.3 | HarnessAgent + Workspace + Filesystem |
| agentscope-core | 2.0.3 | RuntimeContext / AgentState |
| agentscope-extensions-model-openai | 2.0.3 | OpenAI 兼容 LLM |
| MySQL Connector/J | 8.x | JDBC 驱动 |
| HikariCP | 5.x | 连接池 |

---

## 2. 架构设计

### 2.1 目标架构

```
请求 → HarnessAgent → MysqlDistributedStore
                        ├── MysqlAgentStateStore (agent_state 表)
                        │   └── AgentState: 对话历史、压缩摘要、权限、Plan Mode、任务
                        └── JdbcStore (agent_fs 表)
                            └── 工作区文件: MEMORY.md, memory/, skills/, subagents/, sessions/
```

### 2.2 数据流

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
  │   读 MEMORY.md ← agent_fs (受 maxContextTokens 预算约束)
  │   读 skills/ → DynamicSkillMiddleware → <available_skills> 块
  │
  ▼
推理循环 (HarnessAgent)
  │   中间件改写 state.contextMutable()
  │   MemoryFlushMiddleware → 写 memory/YYYY-MM-DD.md → agent_fs
  │   工具结果落盘 → agent_fs (超大输出卸载)
  │   Plan Mode → 写 plans/PLAN.md → agent_fs
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

### 2.3 核心组件

```
src/main/java/io/agentmanager/framework/
├── config/
│   ├── AgentScopeConfig.java        # DataSource + MysqlDistributedStore + HarnessAgent Bean
│   └── AgentManagerProperties.java  # 环境变量配置
├── service/
│   ├── AgentRuntimeService.java     # invoke / invokeStream
│   ├── WorkspaceInitializer.java    # OAF → Workspace 转换
│   └── McpToolRegistrar.java        # MCP 原生注册
└── model/
    └── OafConfig.java               # OAF 配置模型
```

---

## 3. 多租户实现

### 3.1 AgentState 隔离

通过 `RuntimeContext` 的 `(userId, sessionId)` 二元组实现：

| 组件 | 值 | 示例 |
|------|-----|------|
| tenantPrefix | `vendorKey/agentKey` | `acme/test-agent` |
| threadId | 调用方传入或 UUID | `thread-123` |
| 完整 sessionId | `tenantPrefix:threadId` | `acme/test-agent:thread-123` |
| userId | `vendorKey` 或调用方指定 | `acme` |

### 3.2 工作区文件隔离

`RemoteFilesystemSpec` 的 `IsolationScope` 自动按维度分桶：

| Scope | 命名空间键 | 效果 |
|---|---|---|
| `USER`（默认） | `agents/{agentId}/users/{userId}/...` | 每个用户独立的工作区 |
| `SESSION` | `agents/{agentId}/sessions/{sessionId}/...` | 每个会话独立 |
| `AGENT` | `agents/{agentId}/shared/...` | 所有用户共享 |

### 3.3 隔离矩阵

| 数据类型 | 隔离维度 | 存储位置 | 说明 |
|---|---|---|---|
| AgentState | `(userId, sessionId)` | `agent_state` 表 | 每个会话独立 |
| MEMORY.md | `userId` | `agent_fs` 表 | 每个用户独立，跨会话共享 |
| memory/ | `userId` | `agent_fs` 表 | 每个用户独立 |
| skills/ | 共享 + 用户覆盖 | `agent_fs` 表 | 共享底座 + 用户定制 |
| sessions/ | `userId` | `agent_fs` 表 | 每个用户独立 |

---

## 4. 配置

### 4.1 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `CHECKPOINT_JDBC_URL` | `jdbc:mysql://127.0.0.1:3307/agent_manager_test` | MySQL JDBC URL |
| `CHECKPOINT_DB_NAME` | — | agent_state 表所在数据库名（可选；未设置时自动从 JDBC URL 解析，保证与 agent_fs 同库） |
| `CHECKPOINT_USERNAME` | `agent_manager` | MySQL 用户名 |
| `CHECKPOINT_PASSWORD` | `Agent@Manager2026` | MySQL 密码 |

除 `agent_state`/`agent_fs`（SDK 侧）外，框架自建 8 张表（服务启动自动建表，结构见 [api.md](api.md) §数据库表）：
`confirm_context` / `turn_lease` / `tool_audit_log` / `ui_context` / `file_asset` / `kv_sync_key` /
`model_config` / `session_user`。

> 此前此处的尾注只列了 5 张，漏了 `session_user`、`model_config`、`kv_sync_key`；本版已补齐。

另外，**事件流（`session_event`）自 2026-09-16 起整体迁至 Redis Streams**——MySQL 侧仅保留迁移期结构。
事件事实来源是 Redis 的 `sess:{sid}:events`（Stream）与 `sess:{sid}:replies`（ZSET），
见 [api.md](api.md) §`session_event` 与 [api-frontend-sse.md](api-frontend-sse.md) §14。

K8s Pod 内连接需使用 Docker 网关 IP `172.20.0.1` 代替 `127.0.0.1`。

### 4.2 DataSource 配置 (HikariCP)

| 参数 | 值 |
|------|-----|
| maximumPoolSize | 10 |
| minimumIdle | 2 |
| connectionTimeout | 30000ms |
| idleTimeout | 600000ms |
| maxLifetime | 1800000ms |

---

## 5. MySQL 表结构

### 5.1 agent_state 表

```sql
CREATE TABLE IF NOT EXISTS agent_state (
    session_id VARCHAR(255) NOT NULL,
    state_key  VARCHAR(255) NOT NULL,
    item_index INT NOT NULL DEFAULT 0,
    state_data LONGTEXT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, state_key, item_index)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

> ⚠️ **上表是 2.0.0 时的 DDL 快照。** agentscope **2.0.3** 的 `MysqlAgentStateStore` 改用
> `namespace_path` / `item_key` / `version` 三列并启用**乐观锁 CAS** 写入。
> 实际表结构由 SDK 在 `createIfNotExist=true` 时自建，**以 `DESCRIBE agent_state` 的实际输出为准**。
> CAS 与 `conflictPolicy` 的行为分析见 [history-agentstate-design.md](history-agentstate-design.md) §10。

```sql
CREATE TABLE IF NOT EXISTS agent_fs (
    namespace  VARCHAR(255) NOT NULL,
    path       VARCHAR(512) NOT NULL,
    content    LONGBLOB,
    metadata   TEXT,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (namespace, path)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

**内置路由规则**（框架自动将以下路径路由到 `agent_fs`）:

| 路径 | KV 命名空间段 |
|---|---|
| `AGENTS.md`, `MEMORY.md`, `tools.json` | `root` |
| `memory/` | `memory` |
| `skills/` | `skills` |
| `subagents/` | `subagents` |
| `knowledge/` | `knowledge` |
| `agents/<agentId>/sessions/` | `sessions` |
| `agents/<agentId>/tasks/` | `tasks` |

---

## 6. 生命周期

### 6.1 启动流程

```
1. AgentScopeConfig.java
   ├── DataSource (HikariCP) 创建
   └── MysqlDistributedStore 创建 (DistributedStore.builder())
       ├── MysqlAgentStateStore (agent_state 表, createIfNotExist=true)
       └── JdbcStore (agent_fs 表, initializeSchema=true)

2. WorkspaceInitializer.java
   └── 将 OAF 配置转换为 Workspace 目录结构
       ├── AGENTS.md (人格 + 系统提示词)
       ├── tools.json (工具过滤配置)
       ├── skills/{name}/SKILL.md (技能)
       └── subagents/{id}.md (子 Agent)

3. HarnessAgent Bean
   └── HarnessAgent.builder()
       .workspace(workspacePath)
       .distributedStore(distributedStore)
       .filesystem(RemoteFilesystemSpec(IsolationScope.USER))
       .memory(...)
       .compaction(...)
       .build()
```

### 6.2 跨节点恢复

任意节点只要连接同一个 MySQL，即可恢复完整 AgentState + 工作区文件。

---

## 7. 设计决策

### 7.1 为什么使用 MysqlDistributedStore？

| 维度 | MysqlAgentStateStore | MysqlDistributedStore |
|---|---|---|
| AgentState 持久化 | ✅ | ✅ |
| 工作区文件存储 | ❌ | ✅ (JdbcStore) |
| 与 HarnessAgent 兼容 | 需手动配置 | ✅ 一键配置 |
| 与 RemoteFilesystemSpec 兼容 | ❌ (build 抛异常) | ✅ |
| 记忆管理 | ❌ | ✅ (MEMORY.md + memory/) |
| 技能持久化 | ❌ | ✅ (skills/) |
| 会话日志 | ❌ | ✅ (sessions/) |

---

## 8. 验证用例

### 8.1 测试 LLM 配置

```yaml
agent:
  llm:
    api-key: ${LLM_API_KEY}
    model-id: ${LLM_MODEL}        # mimo-v2.5（见 .env.secrets）
    base-url: ${LLM_ENDPOINT}
    provider: openai
    temperature: 0.2
    max-tokens: 50
```

### 8.2 验证用例

| 编号 | 场景 | 验证方法 |
|------|------|---------|
| TC-CP-01 | 数据库连接正常 | `SELECT 1` |
| TC-CP-02 | agent_state 表结构 | `DESCRIBE agent_state` |
| TC-CP-03 | agent_fs 表结构 | `DESCRIBE agent_fs` |
| TC-CP-04 | AgentState 读写 | agent.call() → 查 agent_state |
| TC-CP-05 | 工作区文件读写 | agent.call() → 查 agent_fs |
| TC-CP-06 | 跨节点恢复 | 新 HarnessAgent 实例 + 相同 MysqlDistributedStore |
| TC-CP-07 | LLM 连通性 | 测试 LLM API 调用（见 .env.secrets） |
| TC-CP-08 | HikariCP 连接池 | 验证 pool 参数 |

---

## 9. 相关文档

| 文档 | 说明 |
|------|------|
| [OAF 改进方案](oaf-improvement-plan.md) | OAF → Workspace 转换 |
| [MySQL 文件系统方案](mysql-filesystem-plan.md) | MysqlDistributedStore + RemoteFilesystemSpec |
| [MySQL 会话持久化方案](mysql-session-persistence-plan.md) | AgentStateStore 升级细节 |
| [A2A 改进方案](a2a-improvement-plan.md) | A2A 协议合规性改进 |
| [多租户改进方案](multi-tenancy-improvement-plan.md) | 多租户隔离 |
| [工具体系改进方案](tool-system-improvement-plan.md) | MCP 权限控制 + 工具过滤 |
