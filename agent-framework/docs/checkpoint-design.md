# Agent Framework — Checkpoint 持久化设计文档

**版本:** v2.0.0 (Java)
**日期:** 2026-07-13

---

## 1. 概述

Agent Framework 使用 **AgentScope MySQL AgentStateStore** 实现基于 `sessionId` 的会话持久化。所有对话历史通过 `(userId, sessionId)` 关联，自动存储到 MySQL 数据库中。

### 技术选型

| 组件 | 版本 | 用途 |
|------|------|------|
| agentscope-extensions-mysql | 2.0.0 | MySQL AgentStateStore |
| agentscope-core | 2.0.0 | RuntimeContext 状态管理 |
| MySQL Connector/J | 8.x | JDBC 驱动 |
| HikariCP | 5.x | 连接池 |

---

## 2. 架构设计

### 2.1 数据流

```
用户请求 (sessionId=X)
    │
    ▼
AgentRuntimeService.invoke() / invokeStream()
    │ RuntimeContext.builder()
    │   .sessionId(tenantPrefix + ":" + threadId)
    │   .userId(oafConfig.vendorKey())
    │   .build()
    ▼
ReActAgent.call([userMsg], ctx)
    │ AgentStateStore 自动读取/写入
    ▼
MySQL agent_state 表
    ├── session_id (主键)
    ├── state (msgpack 序列化)
    └── updated_at
```

### 2.2 核心组件

```
src/main/java/io/agentmanager/framework/
├── config/
│   └── AgentScopeConfig.java        # DataSource + AgentStateStore Bean
└── service/
    └── AgentRuntimeService.java     # invoke / invokeStream
```

---

## 3. 多租户实现

### 3.1 sessionId 设计

```
完整 sessionId = {tenantPrefix}:{threadId}
其中 tenantPrefix = oafConfig.slug() (如 "acme/test-agent")
```

| 组件 | 值 | 示例 |
|------|-----|------|
| tenantPrefix | `vendorKey/agentKey` | `acme/test-agent` |
| threadId | 调用方传入或 UUID | `thread-123` |
| 完整 sessionId | `tenantPrefix:threadId` | `acme/test-agent:thread-123` |

### 3.2 隔离效果

两个不同 Agent 使用相同 threadId 时数据完全隔离：

| Agent A | Agent B |
|---------|---------|
| slug = `org-a/agent-x` | slug = `org-b/agent-y` |
| sessionId = `org-a/agent-x:my-thread` | sessionId = `org-b/agent-y:my-thread` |
| **完全隔离** | **完全隔离** |

---

## 4. 配置

### 4.1 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `CHECKPOINT_JDBC_URL` | `jdbc:mysql://127.0.0.1:3307/agent_manager_test` | MySQL JDBC URL |
| `CHECKPOINT_USERNAME` | `agent_manager` | MySQL 用户名 |
| `CHECKPOINT_PASSWORD` | `Agent@Manager2026` | MySQL 密码 |

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

### agent_state 表

由 `MysqlAgentStateStore` 自动创建：

```sql
CREATE TABLE IF NOT EXISTS agent_state (
    session_id VARCHAR(255) NOT NULL PRIMARY KEY,
    state LONGBLOB,
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 建表时机

`MysqlAgentStateStore` 构造时自动 `CREATE TABLE IF NOT EXISTS`。

---

## 6. 生命周期

### 启动流程

```
1. AgentScopeConfig.java
   ├── DataSource (HikariCP) 创建
   └── MysqlAgentStateStore(dataSource, database, table, autoCreate) 创建

2. ReActAgent Bean
   └── ReActAgent.builder().stateStore(stateStore).build()
       → Agent 创建时注入 stateStore
```

### 请求处理

```
POST / → message/send {metadata.thread_id: "T1"}
    │
    ├── AgentRuntimeService.invoke("Hello", "T1")
    │   ├── fullSessionId = "org/agent:T1"
    │   ├── ctx = RuntimeContext(sessionId=fullSessionId, userId=vendorKey)
    │   ├── agent.call([UserMessage], ctx)
    │   │   ├── AgentStateStore 自动读取历史
    │   │   ├── LLM 生成回复 (含上下文)
    │   │   └── AgentStateStore 写入新状态
    │   └── 返回响应
```

---

## 7. 设计决策

### 7.1 为什么使用 MySQL 而非内存存储？

| 方案 | 持久化 | 跨进程 | 水平扩展 |
|------|--------|--------|---------|
| 内存 | 否 | 否 | 否 |
| MySQL | 是 | 是 | 是 |

### 7.2 为什么使用 sessionId 前缀隔离？

AgentScope 的 RuntimeContext 使用 `sessionId` 作为状态标识。通过添加 `{tenantPrefix}:` 前缀实现多租户隔离，比使用 `checkpoint_ns`（LangGraph 概念）更简单直接。

### 7.3 thread_id 管理

- threadId 由调用方指定（通过 `metadata.thread_id`），未指定时自动生成 UUID
- 同一 threadId 的多次请求自动恢复历史上下文
- 当前版本 ThreadController 仅返回空列表，完整的 Thread CRUD 需配合 AgentStateStore 管理 API
