# Agent Framework — Checkpoint 持久化设计文档

**版本:** v1.0.0
**日期:** 2026-05-16

---

## 1. 概述

Agent Framework 使用 **LangGraph MySQL Checkpoint** 实现基于 DeepAgents 的 `thread_id` 会话持久化。所有对话历史通过 `thread_id` 关联，自动存储到 MySQL 数据库中，支持：

- **多轮对话上下文保持**：同一 `thread_id` 的多次请求自动加载历史消息，Agent 具备记忆能力
- **对话历史查询**：通过 REST API 或 JSON-RPC 方法检索完整对话记录（含工具调用）
- **Thread 生命周期管理**：列表、获取、删除
- **跨服务重启持久化**：消息存储在 MySQL 中，服务重启不丢失

### 技术选型

| 组件 | 版本 | 用途 |
|------|------|------|
| langgraph | 1.2.0 | Agent 工作流编排 |
| langgraph-checkpoint-mysql | 3.0.0 | MySQL checkpoint 存储 |
| langgraph-checkpoint | 4.1.0 | Checkpoint 抽象层 |
| asyncmy | >=0.2.10 | 异步 MySQL 驱动 |
| DeepAgents | 0.6.1 | Agent + checkpointer 集成 |

---

## 2. 架构设计

### 2.1 数据流

```
用户请求 (thread_id=X)
    │
    ▼
A2A Routes / Thread Routes
    │
    ▼
AgentRuntime.invoke() / invoke_stream()
    │ config={"configurable": {"thread_id": X}}
    ▼
DeepAgents Agent (create_deep_agent)
    │ checkpointer=AsyncMySaver
    ▼
LangGraph Graph Execution
    │
    ├── 读取历史: aget_state(config) → 恢复 messages 列表
    │
    ├── 执行 LLM + Tools
    │
    └── 写入状态: aput(config, checkpoint) → MySQL 持久化
         ├── checkpoints 表 (元数据)
         ├── checkpoint_blobs 表 (channel 版本数据)
         └── checkpoint_writes 表 (消息内容, msgpack 序列化)
```

### 2.2 核心组件

```
server/
├── services/
│   ├── checkpoint_manager.py   # MySQL checkpoint 生命周期管理
│   └── agent_runtime.py        # DeepAgents 运行时 + thread CRUD
├── routes/
│   ├── thread_routes.py        # REST API: GET/DELETE /threads, /threads/{id}
│   └── a2a_routes.py           # JSON-RPC: threads/list, get, delete, create
```

---

## 3. 核心实现

### 3.1 CheckpointManager

**文件:** `server/services/checkpoint_manager.py`

```python
class CheckpointManager:
    """MySQL checkpoint 生命周期管理"""
    
    def __init__(self, dsn: str):
        self._dsn = dsn  # mysql+asyncmy://user:pass@host:port/db
    
    async def start(self):
        # 1. 解析 DSN → 连接参数
        # 2. 创建 asyncmy 连接
        # 3. 创建 AsyncMySaver 实例
        # 4. 调用 saver.setup() 创建表结构
        return saver
    
    async def close(self):
        # 关闭 asyncmy 连接
    
    @property
    def saver(self) -> AsyncMySaver:
        return self._saver
```

**MySQL 表结构：**

| 表名 | 用途 |
|------|------|
| `checkpoints` | 存储 checkpoint 元数据 (thread_id, checkpoint_id, JSON checkpoint) |
| `checkpoint_blobs` | 存储 channel 版本的 blob 数据 (msgpack 序列化) |
| `checkpoint_writes` | 存储写入操作的消息内容 (用户/AI 消息, msgpack 序列化) |
| `checkpoint_migrations` | 记录迁移版本号 |

**DSN 格式：**
```
mysql+asyncmy://{user}:{password}@[{host}]:{port}/{database}
```

密码中的特殊字符需 URL 编码（如 `@` → `%40`）。

### 3.2 AgentRuntime — thread_id 会话

**文件:** `server/services/agent_runtime.py`

AgentRuntime 在创建 DeepAgent 时传入 `checkpointer`，后续所有 `invoke`/`invoke_stream` 调用通过 `config` 参数指定 `thread_id`，LangGraph 自动处理消息的读取和写入。

```python
class AgentRuntime:
    def __init__(self, ..., checkpointer=None):
        self._checkpointer = checkpointer  # AsyncMySaver 实例
        self._agent = None                 # 延迟创建的 DeepAgent

    def _ensure_agent(self):
        """首次调用时创建 DeepAgent，注入 checkpointer"""
        if self._agent is None and self.llm.is_valid():
            self._agent = create_deep_agent(
                model=model,
                system_prompt=self.system_prompt,
                tools=tools,
                checkpointer=self._checkpointer,  # 注入 MySQL checkpointer
            )
        return self._agent

    async def invoke(self, message: str, thread_id: str = None):
        """同步风格调用，返回 (response_text, thread_id)"""
        config = {"configurable": {"thread_id": thread_id}}
        result = await agent.ainvoke(
            {"messages": [HumanMessage(content=message)]},
            config=config,  # thread_id 驱动状态恢复和持久化
        )
        return extract_response(result), thread_id
```

### 3.3 Thread 管理 API

#### REST API

| 方法 | 路径 | 用途 |
|------|------|------|
| GET | `/threads` | 列出所有 thread（从 checkpoint 聚合） |
| GET | `/threads/{id}` | 获取 thread 完整对话历史（含工具调用） |
| DELETE | `/threads/{id}` | 删除 thread 的所有 checkpoint 数据 |

#### JSON-RPC 方法

| 方法 | 用途 |
|------|------|
| `threads/list` | 列出所有 thread |
| `threads/get` | 获取指定 thread 对话历史 |
| `threads/delete` | 删除指定 thread |
| `threads/create` | 创建新 thread（返回 UUID） |

#### 对话历史格式

```json
{
  "thread_id": "persist-alice",
  "messages": [
    {"role": "user", "content": "My name is Alice."},
    {"role": "assistant", "content": "Got it, Alice."},
    {"role": "assistant", "type": "tool_call", "tool_name": "bash_execute", "tool_args": {"command": "ls"}},
    {"role": "tool", "type": "tool_result", "tool_name": "bash_execute", "content": "file1.txt\nfile2.txt"}
  ]
}
```

---

## 4. 生命周期

### 4.1 启动流程

```
1. create_app(config)
   ├── CheckpointManager(dsn) 创建
   └── AgentRuntime(..., checkpointer=None) 创建
   
2. lifespan startup
   ├── await checkpoint_manager.start()
   │   ├── asyncmy.connect() → MySQL 连接
   │   ├── AsyncMySaver(conn=conn) 创建
   │   └── await saver.setup() → 创建/验证表结构
   ├── agent_runtime._checkpointer = saver  # 注入
   └── MCP client 初始化

3. 首次请求
   └── agent_runtime._ensure_agent()
       └── create_deep_agent(checkpointer=saver)
```

### 4.2 请求处理流程

```
POST / → message/send {metadata: {thread_id: "T1"}}
    │
    ├── AgentRuntime.invoke("Hello", "T1")
    │   └── agent.ainvoke({messages: [...]}, config={thread_id: "T1"})
    │       ├── [LangGraph] 从 checkpointer 读取历史
    │       │   └── aget_state(config) → 恢复 messages 列表
    │       ├── [LangGraph] LLM 生成回复 (含历史上下文)
    │       └── [LangGraph] 写入 checkpoint
    │           └── aput(config, checkpoint) → MySQL 持久化
    │
    └── 返回响应 + thread_id

GET /threads/T1
    │
    └── AgentRuntime.get_thread_state("T1")
        └── agent.aget_state(config) → StateSnapshot
            └── 解析 messages → 返回对话历史

DELETE /threads/T1
    │
    └── AgentRuntime.delete_thread("T1")
        └── checkpointer.adelete_thread("T1") → MySQL 清理
```

---

## 5. 配置

### 5.1 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `CHECKPOINT_MYSQL_DSN` | `mysql+asyncmy://agent_manager:Agent%40Manager2026@127.0.0.1:3307/agent_manager_test` | MySQL checkpoint DSN |

### 5.2 AppConfig

```python
class MySQLCheckpointConfig(BaseModel):
    dsn: str = Field(
        default="mysql+asyncmy://agent_manager:Agent%40Manager2026@127.0.0.1:3307/agent_manager_test",
        alias="CHECKPOINT_MYSQL_DSN",
    )
```

---

## 6. 测试

### 6.1 单元测试 — tests/unit/test_checkpoint_manager.py

| 测试 | 内容 |
|------|------|
| `test_parse_dsn_standard` | 标准 DSN 解析 |
| `test_parse_dsn_url_encoded_password` | URL 编码密码 (`@` → `%40`) |
| `test_parse_dsn_no_password` | 无密码格式 |
| `test_parse_dsn_default_port` | 默认端口 3306 |
| `test_parse_dsn_invalid` | 非法 DSN 抛出异常 |
| `test_start_and_close` | 启动/关闭 checkpointer 连接 |
| `test_saver_setup_creates_tables` | setup() 创建 MySQL 表 |

### 6.2 集成测试 — tests/integration/test_checkpoint_integration.py

| 测试 | 内容 |
|------|------|
| `test_send_message_creates_thread` | 发送消息创建 thread |
| `test_thread_state_has_messages` | Thread 状态包含用户和助手消息 |
| `test_continue_conversation_remembers_context` | 同一 thread_id 多轮对话保持上下文 |
| `test_state_accumulates_messages` | 多轮对话后消息数量累加 |
| `test_list_threads_includes_our_thread` | 新建 thread 出现在列表中 |
| `test_delete_thread` | 删除 thread 成功 |
| `test_deleted_thread_not_found` | 删除后返回 404 |

### 6.3 运行测试

```bash
# 单元测试 (无需 MySQL)
pytest tests/unit/test_checkpoint_manager.py -v

# 集成测试 (需要 MySQL + LLM)
pytest tests/integration/test_checkpoint_integration.py -v
```

---

## 7. 设计决策记录

### 7.1 为什么使用 MySQL checkpoint 而非内存存储？

| 方案 | 持久化 | 跨进程 | 水平扩展 | 恢复速度 |
|------|--------|--------|---------|---------|
| 内存 (MemorySaver) | 否 | 否 | 否 | 最快 |
| SQLite | 是 | 否 | 否 | 快 |
| **MySQL** | **是** | **是** | **是** | 中 |

选择 MySQL 的理由：
1. 与 Agent Manager 平台共享同一 MySQL 实例（端口 3307）
2. 支持多 Agent 服务实例水平扩展
3. 服务重启后自动恢复所有对话
4. 支持 SQL 级别的 thread 查询和管理

### 7.2 为什么使用 asyncmy 而非 aiomysql？

- `asyncmy` 基于 Rust 实现，性能优于纯 Python 的 `aiomysql`
- `langgraph-checkpoint-mysql` 原生依赖 `asyncmy`
- 支持连接池和自动重连

### 7.3 thread_id 的设计

- `thread_id` 由调用方指定（通过 `metadata.thread_id` 字段），未指定时自动生成 UUID
- 同一 `thread_id` 的多次请求自动恢复历史上下文，实现多轮对话
- `thread_id` 可作为用户 session ID 或对话 ID 使用
- Checkpoint 按 `(thread_id, checkpoint_ns, checkpoint_id)` 三元组唯一标识

### 7.4 消息序列化

- LangGraph MySQL Checkpoint 使用 **msgpack** 序列化消息
- 消息包括：`HumanMessage`（用户输入）、`AIMessage`（AI 回复含 tool_calls）、`ToolMessage`（工具执行结果）
- `checkpoint_writes` 表按 `(thread_id, channel, task_id, idx)` 存储写入记录
- 版本管理：每个 channel 维护版本号，checkpoint 只存储增量变更
