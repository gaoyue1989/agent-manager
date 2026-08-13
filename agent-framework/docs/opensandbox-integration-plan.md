# OpenSandbox 集成设计方案

## 1. 背景与目标

### 1.1 背景

当前 agent-framework 使用 `RemoteFilesystemSpec(IsolationScope.USER)` + `MysqlDistributedStore` 实现共享存储模式（Mode 1）。该模式下：

- 工作区文件存储在 MySQL KV（`agent_fs` 表）
- 不提供 Shell 执行能力
- Agent 服务依赖本地磁盘存储模板文件（`.agentscope/workspace/`）

**问题**：无法执行不可信代码、无法隔离生产环境、无法跨调用恢复可执行环境（如 `npm install` 的依赖）。

### 1.2 目标

实现 **无状态 Agent + OpenSandbox 沙箱** 集成：

1. Agent 服务本身无状态（Pod 重启不丢数据）
2. 需要沙箱执行时，从共享存储读取 workspace 文件注入沙箱
3. 沙箱按 **USER 级别** 跨会话复用
4. 使用 **OpenSandbox** 作为沙箱后端（独立部署）
5. 保留现有架构：未配置沙箱时仍使用 `RemoteFilesystemSpec`

---

## 2. 技术背景

### 2.1 AgentScope 沙箱扩展点

AgentScope 2.0 提供完整的沙箱扩展接口：

| 接口/类 | 包路径 | 说明 |
|---------|--------|------|
| `SandboxClient<O>` | `io.agentscope.harness.agent.sandbox` | 沙箱客户端接口 |
| `SandboxClientOptions` | `io.agentscope.harness.agent.sandbox` | 沙箱配置抽象类 |
| `AbstractBaseSandbox` | `io.agentscope.harness.agent.sandbox` | 沙箱运行时基类 |
| `SandboxState` | `io.agentscope.harness.agent.sandbox` | 沙箱状态持久化 |
| `SandboxFilesystemSpec` | `io.agentscope.harness.agent.filesystem.spec` | 文件系统配置 |

**核心接口**：

```java
// SandboxClient - 沙箱客户端接口
public interface SandboxClient<O extends SandboxClientOptions> {
    Sandbox create(WorkspaceSpec workspaceSpec, SandboxSnapshotSpec snapshotSpec, O options);
    Sandbox resume(SandboxState state);
    void delete(Sandbox sandbox);
    String serializeState(SandboxState state);
    SandboxState deserializeState(String serialized);
}

// AbstractBaseSandbox - 沙箱运行时基类
public abstract class AbstractBaseSandbox implements Sandbox {
    // 模板方法（已实现）
    public void start() throws Exception;          // 调用 doSetupWorkspace()
    public void stop() throws Exception;           // 调用 doDestroyWorkspace()
    public ExecResult exec(RuntimeContext ctx, String command, Integer timeoutSeconds) throws Exception;
    public InputStream persistWorkspace() throws Exception;
    public void hydrateWorkspace(InputStream archive) throws Exception;

    // 抽象方法（需实现）
    protected abstract ExecResult doExec(RuntimeContext ctx, String command, int timeoutSeconds) throws Exception;
    protected abstract InputStream doPersistWorkspace() throws Exception;
    protected abstract void doHydrateWorkspace(InputStream archive) throws Exception;
    protected abstract void doSetupWorkspace() throws Exception;
    protected abstract void doDestroyWorkspace() throws Exception;
    protected abstract String getWorkspaceRoot();
}
```

### 2.2 OpenSandbox 部署信息

| 项目 | 值 |
|------|-----|
| 服务地址 | `http://192.168.31.155:8090`（本机 `http://127.0.0.1:8090`） |
| API Key | `CWpXBzEIlS3edCFQBxK2u+cGK9n08GiYKT22f2JzlxdmJNeAh4waxPHwOEp7pFNW` |
| 认证方式 | Header `OPEN-SANDBOX-API-KEY` |
| 运行时 | Docker（bridge 网络） |
| execd 端口 | `44772/tcp`（映射到宿主机动态端口 40000-60000） |
| 默认镜像 | `opensandbox/code-interpreter:v1.1.0` |
| 默认入口 | `["tail", "-f", "/dev/null"]` |
| 部署文件 | `/root/opensandbox-deploy/docker-compose.yaml` |
| 健康检查 | `curl http://127.0.0.1:8090/health` → `{"status":"healthy"}` |
| API 文档 | `http://127.0.0.1:8090/docs` (Swagger UI) |

**API 端点**：

| 操作 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 创建沙箱 | POST | `/v1/sandboxes` | 异步创建，返回 202 + sandbox ID |
| 查询沙箱 | GET | `/v1/sandboxes/<id>` | 查询单个沙箱状态 |
| 列出沙箱 | GET | `/v1/sandboxes` | 列出所有沙箱 |
| 删除沙箱 | DELETE | `/v1/sandboxes/<id>` | 删除沙箱 |
| 获取端点 | GET | `/v1/sandboxes/<id>/endpoints/44772` | 获取 execd 访问地址 |
| 暂停沙箱 | POST | `/v1/sandboxes/<id>/pause` | 暂停沙箱 |
| 恢复沙箱 | POST | `/v1/sandboxes/<id>/resume` | 恢复沙箱 |
| 创建快照 | POST | `/v1/sandboxes/<id>/snapshots` | 创建沙箱快照 |
| 诊断信息 | GET | `/v1/sandboxes/<id>/diagnostics/summary` | 获取容器诊断 |

**错误码**：

| HTTP 状态 | 含义 |
|-----------|------|
| 401 | API Key 缺失或错误 |
| 404 | 沙箱不存在 |
| 202 | 创建请求已受理（沙箱创建为异步） |

### 2.3 OpenSandbox Java SDK

OpenSandbox 提供 Java SDK（`com.alibaba.opensandbox:sandbox`，**最新稳定版 1.0.18**），核心 API：

```java
// 连接配置
ConnectionConfig config = ConnectionConfig.builder()
    .domain("192.168.31.155:8090")
    .apiKey("CWpXBzEIlS3edCFQBxK2u+cGK9n08GiYKT22f2JzlxdmJNeAh4waxPHwOEp7pFNW")
    .protocol("http")
    .build();

// 创建沙箱
Sandbox sandbox = Sandbox.builder()
    .connectionConfig(config)
    .image("opensandbox/code-interpreter:v1.1.0")
    .timeout(Duration.ofMinutes(60))
    .resource(map -> {
        map.put("cpu", "2");
        map.put("memory", "4Gi");
    })
    .metadata("userId", "alice")
    .build();

// 命令执行
Execution execution = sandbox.commands().run("echo 'Hello!'");

// 文件操作
sandbox.files().write(List.of(
    WriteEntry.builder().path("/workspace/file.txt").data("content").mode(644).build()
));
String content = sandbox.files().readFile("/workspace/file.txt", "UTF-8", null);

// 生命周期
sandbox.renew(Duration.ofMinutes(60));
sandbox.pause();
sandbox.resume();
sandbox.kill();

// 管理器（查找已有沙箱）
SandboxManager manager = SandboxManager.builder().connectionConfig(config).build();
PagedSandboxInfos sandboxes = manager.listSandboxInfos(
    SandboxFilter.builder().states(SandboxState.RUNNING).metadata(Map.of("userId", "alice")).build()
);
```

### 2.3 当前架构文件层次

agent-framework 使用 `RemoteFilesystemSpec` 时，存在两层读取机制：

| 层级 | 来源 | 内容 |
|------|------|------|
| **上层 (KV)** | `MysqlDistributedStore` → `agent_fs` 表 | MEMORY.md, memory/, sessions/, tasks/ |
| **下层 (本地)** | `WorkspaceInitializer` 生成 | AGENTS.md, tools.json, skills/, subagents/ |

**读取顺序**：KV 优先 → 本地模板兜底

**本地 `.agentscope/workspace/` 不是缓存，而是模板层**：
- 启动时 `WorkspaceInitializer` 从 OAF 配置生成 workspace 文件到本地磁盘
- 首次读取时 KV 为空，从本地模板读取
- 运行时写入 KV（如 MEMORY.md、memory/）
- 后续读取 KV 有数据则从 KV 读，无数据则回退本地

---

## 3. 架构设计

### 3.1 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                    Agent Pod (Stateless)                         │
│                                                                 │
│  启动时:                                                        │
│  ┌───────────────┐    ┌──────────────┐    ┌──────────────────┐  │
│  │ OAF Config    │───▶│ Workspace    │───▶│ Shared Storage   │  │
│  │ (ConfigMap)   │    │ Initializer  │    │ (MySQL KV)       │  │
│  └───────────────┘    └──────────────┘    └────────┬─────────┘  │
│                                                    │            │
│  ┌─────────────────────────────────────────────────▼─────────┐  │
│  │              RemoteFilesystemSpec (默认模式)                │  │
│  │    读: KV 优先 → 本地模板兜底                              │  │
│  │    写: KV                                                │  │
│  └───────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                           │
                           │ SANDBOX_ENABLED=true 时
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│                    AgentScope 框架                               │
│                                                                 │
│  HarnessAgent.Builder                                           │
│    .filesystem(OpenSandboxFilesystemSpec)                        │
│    .build()                                                     │
│      │                                                         │
│      ├─ 创建 OpenSandboxClient                                  │
│      ├─ 创建 SandboxManager                                     │
│      └─ 创建 SandboxLifecycleMiddleware                          │
│                                                                 │
│  每次 call():                                                    │
│    ├─ SandboxManager.acquire(ctx)                               │
│    │   ├─ 检查是否有已存在的沙箱 (按 userId)                     │
│    │   ├─ 有 → resume(state) 恢复沙箱                           │
│    │   └─ 无 → create(workspaceSpec, snapshotSpec, options)     │
│    │       ├─ 通过 OpenSandbox SDK 创建沙箱                     │
│    │       ├─ 通过 SDK 注入 workspace 文件                      │
│    │       └─ 返回 OpenSandbox 实例                             │
│    │                                                           │
│    ├─ 文件操作/Shell 执行 → 转发到沙箱                          │
│    │   ├─ read_file → sandbox.exec("cat ...")                  │
│    │   ├─ write_file → sandbox.exec("echo ... > ...")          │
│    │   └─ execute → sandbox.exec(command)                      │
│    │                                                           │
│    ├─ SandboxManager.persistState()                             │
│    │   └─ 保存沙箱状态到 AgentStateStore                        │
│    │                                                           │
│    └─ SandboxManager.release()                                  │
│        └─ 保留沙箱，不销毁 (供后续复用)                         │
└─────────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│                    OpenSandbox Server (独立部署)                 │
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  Sandbox (按 userId 复用, IsolationScope.USER)             │  │
│  │    ┌───────────────────────────────────────────────────┐  │  │
│  │    │  /workspace (从 KV + 本地模板预注入)               │  │  │
│  │    │    ├── AGENTS.md                                  │  │  │
│  │    │    ├── tools.json                                 │  │  │
│  │    │    ├── skills/                                    │  │  │
│  │    │    ├── subagents/                                 │  │  │
│  │    │    └── knowledge/                                 │  │  │
│  │    └───────────────────────────────────────────────────┘  │  │
│  └───────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

### 3.2 数据流

#### 启动流程

```
Agent Pod 启动
  │
  ├─ 读取 OAF 配置 (ConfigMap /config)
  │
  ├─ WorkspaceInitializer.initialize()
  │   └─ 写入本地 .agentscope/workspace/ (临时模板)
  │
  ├─ 检查 SANDBOX_ENABLED
  │   ├─ false → 使用 RemoteFilesystemSpec (当前模式)
  │   └─ true →
  │       ├─ 初始化 OpenSandboxFilesystemSpec
  │       └─ 传入 HarnessAgent.Builder.filesystem()
  │
  └─ HarnessAgent 创建完成
```

#### 请求处理流程 (沙箱模式)

```
用户请求到达 (userId = "alice")
  │
  ├─ 构建 RuntimeContext(userId="alice", sessionId="session-1")
  │
  ├─ SandboxLifecycleMiddleware.acquireForCall(ctx)
  │   └─ SandboxManager.acquire(ctx)
  │       ├─ 计算 SandboxIsolationKey(USER, "alice")
  │       ├─ 从 SessionSandboxStateStore 加载状态
  │       │   ├─ 有状态 → OpenSandboxClient.resume(state)
  │       │   │   └─ 恢复已有沙箱
  │       │   └─ 无状态 → OpenSandboxClient.create(...)
  │       │       ├─ OpenSandbox SDK 创建沙箱
  │       │       ├─ 从 KV + 本地读取 workspace 文件
  │       │       ├─ 通过 SDK 文件 API 注入沙箱
  │       │       └─ 返回 OpenSandbox 实例
  │       └─ 返回 SandboxAcquireResult
  │
  ├─ HarnessAgent.call()
  │   ├─ LLM 调用 (宿主侧)
  │   ├─ 文件操作 → SandboxBackedFilesystem
  │   │   ├─ read_file → sandbox.exec("cat /workspace/...")
  │   │   ├─ write_file → sandbox.exec("echo ... > /workspace/...")
  │   │   └─ execute → sandbox.exec(command)
  │   └─ 记忆文件读写 → 沙箱内 /workspace/MEMORY.md、memory/
  │
  ├─ 请求完成后（同步 invoke 返回后 / 流式 AGENT_END 处）
  │   └─ WorkspaceSyncService.syncBack()
  │       └─ 从沙箱拉取 MEMORY.md/memory/ → 写回 KV (agent_fs 表)
  │
  ├─ SandboxLifecycleMiddleware.releaseForCall(ctx)
  │   └─ SandboxManager.persistState()
  │       └─ 保存沙箱状态到 AgentStateStore
  │
  └─ 请求结束 (沙箱保留，不销毁)
```

### 3.3 沙箱复用策略

| IsolationScope | 缓存 Key | 行为 |
|----------------|----------|------|
| `USER` (默认) | `userId` | 同一用户跨会话复用同一沙箱 |
| `SESSION` | `sessionId` | 每个会话独立沙箱 |
| `AGENT` | agent name | 所有用户共享 |

**USER 降级逻辑**：当 `RuntimeContext.userId` 缺失时，自动降级为 `sessionId` 隔离。

---

## 4. 实现设计

### 4.1 组件清单

```
agent-framework/src/main/java/io/agentmanager/framework/
├── config/
│   ├── AgentScopeConfig.java           // 修改：条件装配沙箱
│   └── SandboxConfig.java              // 新增：沙箱配置
├── sandbox/
│   └── opensandbox/
│       ├── OpenSandboxClientOptions.java   // 新增：沙箱配置
│       ├── OpenSandboxClient.java          // 新增：沙箱客户端
│       ├── OpenSandbox.java                // 新增：沙箱运行时
│       ├── OpenSandboxState.java           // 新增：沙箱状态
│       ├── OpenSandboxFilesystemSpec.java  // 新增：文件系统配置
│       └── WorkspaceSyncService.java       // 新增：沙箱 → KV 回写同步
└── service/
    └── WorkspaceReader.java              // 新增：KV + 本地读取
```

### 4.2 类图关系

```
SandboxClientOptions (AgentScope)
  └── OpenSandboxClientOptions (新增)
        ├── serverUrl: String
        ├── apiKey: String
        ├── image: String
        ├── timeout: Duration
        ├── resource: Map<String, String>
        └── createClient() → OpenSandboxClient

SandboxClient<O> (AgentScope)
  └── OpenSandboxClient (新增)
        ├── create() → OpenSandbox
        ├── resume() → OpenSandbox
        ├── delete() → void
        ├── serializeState() → String
        └── deserializeState() → OpenSandboxState

AbstractBaseSandbox (AgentScope)
  └── OpenSandbox (新增)
        ├── osbSandbox: com.alibaba.opensandbox.sandbox.Sandbox
        ├── doExec() → ExecResult
        ├── doPersistWorkspace() → InputStream
        ├── doHydrateWorkspace() → void
        ├── doSetupWorkspace() → void
        ├── doDestroyWorkspace() → void
        └── getWorkspaceRoot() → "/workspace"

SandboxState (AgentScope)
  └── OpenSandboxState (新增)
        ├── sandboxId: String
        ├── sandboxEndpoint: String
        ├── userId: String
        ├── image: String
        └── createdAt: long

SandboxFilesystemSpec (AgentScope)
  └── OpenSandboxFilesystemSpec (新增)
        ├── createClient() → OpenSandboxClient
        ├── clientOptions() → OpenSandboxClientOptions
        ├── snapshotSpec() → SandboxSnapshotSpec
        └── workspaceSpec() → WorkspaceSpec
```

### 4.3 详细实现

#### 4.3.1 OpenSandboxState

```java
package io.agentmanager.framework.sandbox.opensandbox;

import io.agentscope.harness.agent.sandbox.SandboxState;

public class OpenSandboxState extends SandboxState {
    private String sandboxId;
    private String sandboxEndpoint;
    private String userId;
    private String image;
    private long createdAt;

    // getters/setters
}
```

#### 4.3.2 OpenSandboxClientOptions

```java
package io.agentmanager.framework.sandbox.opensandbox;

import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;

public class OpenSandboxClientOptions extends SandboxClientOptions {

    private String serverUrl;
    private String apiKey;
    private String image = "opensandbox/code-interpreter:v1.1.0";
    private Duration timeout = Duration.ofMinutes(60);
    private Map<String, String> resource = Map.of("cpu", "1", "memory", "1024Mi");
    private Map<String, String> environment = new HashMap<>();
    private String workspaceRoot = "/workspace";

    @Override
    public String getType() {
        return "opensandbox";
    }

    @Override
    public SandboxClient<?> createClient() {
        return new OpenSandboxClient(this);
    }

    @Override
    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    // Fluent builder methods
    public OpenSandboxClientOptions serverUrl(String serverUrl) { this.serverUrl = serverUrl; return this; }
    public OpenSandboxClientOptions apiKey(String apiKey) { this.apiKey = apiKey; return this; }
    public OpenSandboxClientOptions image(String image) { this.image = image; return this; }
    public OpenSandboxClientOptions timeout(Duration timeout) { this.timeout = timeout; return this; }
    public OpenSandboxClientOptions resource(Map<String, String> resource) { this.resource = resource; return this; }
    public OpenSandboxClientOptions environment(Map<String, String> env) { this.environment = env; return this; }
}
```

#### 4.3.3 OpenSandboxClient

```java
package io.agentmanager.framework.sandbox.opensandbox;

import com.alibaba.opensandbox.sandbox.config.ConnectionConfig;
import io.agentscope.harness.agent.sandbox.*;

public class OpenSandboxClient implements SandboxClient<OpenSandboxClientOptions> {

    private final OpenSandboxClientOptions options;
    private final ConnectionConfig connectionConfig;
    private final ObjectMapper objectMapper;

    public OpenSandboxClient(OpenSandboxClientOptions options) {
        this.options = options;
        this.objectMapper = new ObjectMapper();
        this.connectionConfig = ConnectionConfig.builder()
            .domain(options.getServerUrl())
            .apiKey(options.getApiKey())
            .protocol("http")
            .build();
    }

    @Override
    public io.agentscope.harness.agent.sandbox.Sandbox create(
            WorkspaceSpec workspaceSpec,
            SandboxSnapshotSpec snapshotSpec,
            OpenSandboxClientOptions options) {

        // 1. 通过 OpenSandbox SDK 创建沙箱
        com.alibaba.opensandbox.sandbox.Sandbox osbSandbox =
            com.alibaba.opensandbox.sandbox.Sandbox.builder()
                .connectionConfig(connectionConfig)
                .image(options.getImage())
                .timeout(options.getTimeout())
                .resource(map -> map.putAll(options.getResource()))
                .environment(options.getEnvironment())
                .build();

        // 2. 构建状态
        OpenSandboxState state = new OpenSandboxState();
        state.setSandboxId(osbSandbox.getInfo().getId());
        // OpenSandbox execd 端口为 44772
        state.setSandboxEndpoint(osbSandbox.getEndpoint(44772).getEndpoint());
        state.setWorkspaceSpec(workspaceSpec);
        state.setImage(options.getImage());

        // 3. 包装为 AgentScope Sandbox
        OpenSandbox sandbox = new OpenSandbox(state, osbSandbox, options);
        sandbox.start();

        // 4. 注入 KV 运行时文件（MEMORY.md/memory/）：
        //    WorkspaceReader 从 KV（agent_fs）读取 → SDK 文件 API 写入沙箱 /workspace
        //    （静态模板 AGENTS.md/skills/ 等由框架投影在 start/hydrateWorkspace 时注入）
        var runtimeFiles = workspaceReader.readRuntimeFiles();
        workspaceReader.injectToSandbox(osbSandbox, runtimeFiles);

        return sandbox;
    }

    @Override
    public io.agentscope.harness.agent.sandbox.Sandbox resume(SandboxState state) {
        OpenSandboxState osbState = (OpenSandboxState) state;

        // 通过 OpenSandbox SDK 恢复沙箱
        com.alibaba.opensandbox.sandbox.Sandbox osbSandbox =
            com.alibaba.opensandbox.sandbox.Sandbox.builder()
                .connectionConfig(connectionConfig)
                .sandboxId(osbState.getSandboxId())
                .build();

        // 注意：resume 不调用 start()。沙箱实例已存在（Server 侧），
        // 若沙箱已销毁（404）此处应抛出异常 → SandboxManager 捕获后降级 create（框架行为）。
        // 实施时确认框架 acquire() 在 resume 后是否调用 start()（Docker 后端行为需对齐）
        return new OpenSandbox(osbState, osbSandbox, options);
    }

    @Override
    public void delete(io.agentscope.harness.agent.sandbox.Sandbox sandbox) {
        OpenSandbox osb = (OpenSandbox) sandbox;
        try {
            osb.getOsbSandbox().kill();
            osb.getOsbSandbox().close();
        } catch (Exception e) {
            throw new SandboxException(
                SandboxErrorCode.WORKSPACE_STOP_ERROR,
                "delete",
                "Failed to delete OpenSandbox: " + e.getMessage(),
                e
            );
        }
    }

    @Override
    public String serializeState(SandboxState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (Exception e) {
            throw new SandboxException(
                SandboxErrorCode.CONFIGURATION_ERROR,
                "serializeState",
                "Failed to serialize sandbox state",
                e
            );
        }
    }

    @Override
    public SandboxState deserializeState(String serialized) {
        try {
            return objectMapper.readValue(serialized, OpenSandboxState.class);
        } catch (Exception e) {
            throw new SandboxException(
                SandboxErrorCode.CONFIGURATION_ERROR,
                "deserializeState",
                "Failed to deserialize sandbox state",
                e
            );
        }
    }
}
```

#### 4.3.4 OpenSandbox (extends AbstractBaseSandbox)

```java
package io.agentmanager.framework.sandbox.opensandbox;

import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.Execution;
import com.alibaba.opensandbox.sandbox.domain.models.files.WriteEntry;
import io.agentscope.harness.agent.sandbox.*;

public class OpenSandbox extends AbstractBaseSandbox {

    private final OpenSandboxState state;
    private final com.alibaba.opensandbox.sandbox.Sandbox osbSandbox;
    private final OpenSandboxClientOptions options;

    public OpenSandbox(OpenSandboxState state,
                       com.alibaba.opensandbox.sandbox.Sandbox osbSandbox,
                       OpenSandboxClientOptions options) {
        this.state = state;
        this.osbSandbox = osbSandbox;
        this.options = options;
    }

    @Override
    protected ExecResult doExec(RuntimeContext ctx, String command, int timeoutSeconds) throws Exception {
        Execution execution = osbSandbox.commands().run(command);

        return new ExecResult(
            execution.getExitCode(),
            execution.getLogs().getStdout().stream()
                .map(Object::toString)
                .collect(Collectors.joining("\n")),
            execution.getLogs().getStderr().stream()
                .map(Object::toString)
                .collect(Collectors.joining("\n")),
            false
        );
    }

    @Override
    protected InputStream doPersistWorkspace() throws Exception {
        // 通过 tar 打包 workspace 并 base64 编码传输
        Execution execution = osbSandbox.commands().run("tar cf - -C /workspace . | base64");
        String base64 = execution.getLogs().getStdout().stream()
            .map(Object::toString)
            .collect(Collectors.joining());
        return new ByteArrayInputStream(Base64.getDecoder().decode(base64));
    }

    @Override
    protected void doHydrateWorkspace(InputStream archive) throws Exception {
        byte[] bytes = archive.readAllBytes();
        String base64 = Base64.getEncoder().encodeToString(bytes);

        // 写入临时文件
        osbSandbox.files().write(List.of(
            WriteEntry.builder()
                .path("/tmp/workspace.tar.b64")
                .data(base64)
                .mode(644)
                .build()
        ));

        // 解压到 workspace
        osbSandbox.commands().run(
            "base64 -d /tmp/workspace.tar.b64 | tar xf - -C /workspace && rm /tmp/workspace.tar.b64"
        );
    }

    @Override
    protected void doSetupWorkspace() throws Exception {
        osbSandbox.commands().run("mkdir -p /workspace");
    }

    @Override
    protected void doDestroyWorkspace() throws Exception {
        // OpenSandbox 的 close/kill 会处理资源清理
    }

    @Override
    protected String getWorkspaceRoot() {
        return options.getWorkspaceRoot();
    }

    public com.alibaba.opensandbox.sandbox.Sandbox getOsbSandbox() {
        return osbSandbox;
    }
}
```

#### 4.3.5 OpenSandboxFilesystemSpec

```java
package io.agentmanager.framework.sandbox.opensandbox;

import io.agentscope.harness.agent.sandbox.*;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;

public class OpenSandboxFilesystemSpec extends SandboxFilesystemSpec {

    private String serverUrl;
    private String apiKey;
    private String image = "opensandbox/code-interpreter:v1.1.0";
    private Duration timeout = Duration.ofMinutes(60);
    private Map<String, String> resource = Map.of("cpu", "1", "memory", "1024Mi");
    private Map<String, String> environment = new HashMap<>();
    private String workspaceRoot = "/workspace";

    @Override
    protected SandboxClient<?> createClient() {
        return new OpenSandboxClient(clientOptions());
    }

    @Override
    protected SandboxClientOptions clientOptions() {
        return new OpenSandboxClientOptions()
            .serverUrl(serverUrl)
            .apiKey(apiKey)
            .image(image)
            .timeout(timeout)
            .resource(resource)
            .environment(environment)
            .workspaceRoot(workspaceRoot);
    }

    @Override
    protected SandboxSnapshotSpec snapshotSpec() {
        return snapshotSpecOverride != null ? snapshotSpecOverride : new NoopSnapshotSpec();
    }

    @Override
    protected WorkspaceSpec workspaceSpec() {
        return new WorkspaceSpec(workspaceRoot);
    }

    // Fluent builder methods
    public OpenSandboxFilesystemSpec serverUrl(String serverUrl) { this.serverUrl = serverUrl; return this; }
    public OpenSandboxFilesystemSpec apiKey(String apiKey) { this.apiKey = apiKey; return this; }
    public OpenSandboxFilesystemSpec image(String image) { this.image = image; return this; }
    public OpenSandboxFilesystemSpec timeout(Duration timeout) { this.timeout = timeout; return this; }
    public OpenSandboxFilesystemSpec resource(Map<String, String> resource) { this.resource = resource; return this; }
    public OpenSandboxFilesystemSpec environment(Map<String, String> env) { this.environment = env; return this; }
    public OpenSandboxFilesystemSpec snapshotSpec(SandboxSnapshotSpec spec) { this.snapshotSpecOverride = spec; return this; }
    public OpenSandboxFilesystemSpec isolationScope(IsolationScope scope) { this.isolationScope = scope; return this; }
}
```

#### 4.3.6 SandboxConfig

```java
package io.agentmanager.framework.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "agent.sandbox")
public record SandboxConfig(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("opensandbox/code-interpreter:v1.1.0") String image,
    @DefaultValue("60") int timeoutMinutes,
    @DefaultValue("1024") int memoryMb,
    @DefaultValue("1") int cpuCount,
    OpenSandboxConfig opensandbox
) {
    public record OpenSandboxConfig(
        String serverUrl,
        String apiKey
    ) {}
}
```

#### 4.3.7 AgentScopeConfig 修改

```java
@Configuration
@EnableConfigurationProperties(SandboxConfig.class)
public class AgentScopeConfig {

    @Bean
    @ConditionalOnProperty(name = "agent.sandbox.enabled", havingValue = "true")
    public OpenSandboxFilesystemSpec sandboxFilesystemSpec(SandboxConfig config) {
        return new OpenSandboxFilesystemSpec()
            .serverUrl(config.opensandbox().serverUrl())
            .apiKey(config.opensandbox().apiKey())
            .image(config.image())
            .timeout(Duration.ofMinutes(config.timeoutMinutes()))
            .resource(Map.of(
                "cpu", String.valueOf(config.cpuCount()),
                "memory", config.memoryMb() + "Mi"
            ))
            .isolationScope(IsolationScope.USER);
    }

    @Bean
    public HarnessAgent harnessAgent(
        AgentManagerProperties props,
        DistributedStore distributedStore,
        OafConfig oafConfig,
        WorkspaceInitializer workspaceInitializer,
        @Autowired(required = false) OpenSandboxFilesystemSpec sandboxSpec,
        // ... 其他依赖
    ) {
        var builder = HarnessAgent.builder()
            .name(oafConfig.name())
            .model(model)
            .workspace(workspacePath)
            .distributedStore(distributedStore);

        if (sandboxSpec != null) {
            builder.filesystem(sandboxSpec);
        } else {
            builder.filesystem(new RemoteFilesystemSpec()
                .isolationScope(IsolationScope.USER));
        }

        return builder.build();
    }
}
```

#### 4.3.8 WorkspaceSyncService（新增：回写同步服务）

```java
package io.agentmanager.framework.sandbox.opensandbox;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.sandbox.Sandbox;

/**
 * 沙箱 → KV 回写同步服务。
 * 每次用户请求完成后调用：从沙箱 /workspace 拉取运行时文件（MEMORY.md、memory/），
 * 写入 DistributedStore.baseStore()（agent_fs 表），保证 Agent 服务无状态 + 记忆持久化。
 *
 * 沙箱句柄获取：通过 SandboxManager 获取当前 call 绑定的 OpenSandbox 实例
 * （SandboxAware），再用 OpenSandbox.getOsbSandbox() 取 SDK 句柄直接读文件。
 */
public class WorkspaceSyncService {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceSyncService.class);

    /** 回写文件范围：与注入时对称 */
    private static final List<String> SYNC_PATHS = List.of("MEMORY.md", "memory/");

    private final DistributedStore distributedStore;

    public WorkspaceSyncService(DistributedStore distributedStore) {
        this.distributedStore = distributedStore;
    }

    /**
     * 每次用户请求完成后调用（同步 invoke 返回后 / 流式 AGENT_END 处）。
     * 从沙箱拉取 MEMORY.md + memory/，写回 KV。
     */
    public void syncBack() {
        try {
            // 1. 从沙箱读取 MEMORY.md（若存在）
            // sandboxFilesystem 基于当前 sandbox 实例，读 /workspace/MEMORY.md

            // 2. 从沙箱读取 memory/ 目录下所有文件（若存在）
            // 3. 写回 DistributedStore.baseStore()，key 与注入时一致：
            //    - MEMORY.md → root 命名空间
            //    - memory/xxx.md → memory 命名空间
            log.info("Workspace sync back completed");
        } catch (Exception e) {
            // 回写失败不阻塞主流程：记录日志，沙箱内数据保留，下次 call 或销毁前补偿
            log.warn("Workspace sync back failed: {}", e.getMessage(), e);
        }
    }
}
```

### 4.4 回写同步机制（每次请求完成后）

> **实施确认（2026-08-12）**：回写挂载点从 AgentRuntimeService 调整为 **OpenSandbox.stop()**——
> 框架每次 call 结束都会调用 SandboxManager.release() → sandbox.stop()（反编译确认），
> stop() 内通过 doExec 记录的 userId 回写，无需改动 AgentRuntimeService，且同步/流式天然覆盖。

> **实施确认（2026-08-12）**：resume 用 `Sandbox.connector().sandboxId(...).connect()` 而非
> `resumer().resume()`——Server 的 resume 端点仅对 Paused 沙箱有效，Running 沙箱会 409 Conflict
> （实测确认），connect() 是连接已有沙箱的正确 API。

> **实施确认（2026-08-12）**：官方 MysqlAgentStateStore.validateSessionId 拒绝含 "/" 的 ID，
> 而框架 SessionSandboxStateStore 的 slot ID 形如 `sandbox/user/{agentId}/{userId}`（必然含斜杠），
> 导致沙箱状态无法持久化（每次请求都降级新建）。新增 **SandboxAwareMysqlAgentStateStore**
> 子类放宽校验（仅拒绝空 ID），在 distributedStore bean 中替换。

> **实施确认（2026-08-12）**：框架内部文件操作（memory_save 等）调用沙箱 exec 时
> **RuntimeContext 为空**（实测 `ctx.userId=null`、`state.sessionId=null`），OpenSandbox 无法从
> exec 获得 userId → 回写被跳过。新增 **SandboxUserKeyMiddleware**（`MiddlewareBase.onAgent`
> 拿请求 ctx 的 userId → OpenSandboxFilesystemSpec ThreadLocal）→ `OpenSandboxClient.create/resume`
> 读取绑定到沙箱（`setUserKey`）→ stop() 回写。userId 为空时降级用 sessionId
> （与框架 IsolationScope.USER → SESSION 降级语义一致）。

> **实施确认（2026-08-12）**：`RemoteFilesystem.write` 对**已存在文件返回失败**（"already exists"
> 防覆盖保护，`putIfVersion` 乐观锁语义），首次回写成功、后续覆盖写全部静默失败（syncBack
> 未检查返回值）。回写改为 **read→edit 语义**：先 read 拿旧内容 → 已存在则 `edit`（全量替换）、
> 不存在则 `write`（创建）——保留框架防覆盖/并发检测，不用 BaseStore 直写。

> **实施确认（2026-08-12）**：debug 页面适配——① `/debug/workspace` 增加 `sandbox_mode` 标记 +
> 前端提示（本地目录为静态模板层）；② 新增 `/debug/sandbox` 端点展示沙箱配置；③ `/debug/memory`
> SQL 兼容两种 key 格式（框架 `/MEMORY.md` 带斜杠 + 回写 `MEMORY.md` 无斜杠）；
> ④ `extractUserFromNamespace` 修复 JdbcStore 尾随 0x1F 控制字符泄漏到页面。

> **实施确认（2026-08-12）**：A2A 流（agentscope-extensions-a2a-server 2.0.0）**不携带工具参数**
> （`tool_use` data part 只有工具名 + call_id，`ToolUseBlockParser.getInput()` 为空）。
> debug 页面方案：工具卡片先渲染工具名，`fillToolArgsFromHistory` 异步从 history API
> （agent_state 权威来源）按 tool_call_id 匹配补全参数；`StateDataParser.toRoleContentList`
> 增加 `tool_calls`（{id, name, input}）提取，history 端点 SQL 支持 sessionId 后缀匹配。

> **实施确认（2026-08-13）**：**MemoryFlush 消息卸载（offload）在 call 外失败**
> （"No active sandbox — sandbox filesystem used outside of a call context"）：
> 框架 `SandboxLifecycleMiddleware.releaseForCall` 会 `setSandbox(null)`（反编译确认），
> 而 `MemoryFlushMiddleware.doFlush` 在 call 结束后**无条件**执行 `offloadMessages`
> （写 sessions/ 消息日志）→ 沙箱文件系统无活动沙箱。**影响有限**：offload 仅是会话日志
> 的冗余备份，`agent_state` 表全量保留消息（debug history 从 agent_state 读）；
> `CompactionMiddleware` 对 offload 失败有 catch 容错（压缩仍工作）。框架行为，无法关闭，
> 接受 warn 级日志。
>
> **userId 注入并发竞态**：`SandboxUserKeyMiddleware` 用 ThreadLocal 传递 userId，
> reactor 线程切换时偶发读取失败（userKey=null → 回写跳过）。已在 `OpenSandbox.stop()`
> 增加 pendingUserKey 兜底重读；单用户串行场景稳定（实测 create + resume×2 均绑定成功）。

#### 4.4.1 触发时机

| 调用方式 | 挂载点 | 说明 |
|---------|--------|------|
| 同步 `invoke()` | SandboxManager.release → OpenSandbox.stop() | call 结束自动触发 |
| 流式 `invokeStream()` | 同上（mono 完成时 release） | 流结束自动触发 |

> **实施确认**：KV 注入从 create() 调整为 **首次 exec 延迟注入**——`SandboxClient.create()`
> 无 RuntimeContext 参数拿不到 userId（反编译确认），而 doExec(ctx) 有 ctx.getUserId()；
> 框架所有文件操作最终都走 exec，首次文件操作前注入即可（见 OpenSandbox.java 注释）。

#### 4.4.2 回写流程

```
用户请求完成（agent.call / streamEvents 结束）
  │
  ├─ 1. 获取当前用户沙箱的 SDK 句柄（OpenSandbox.getOsbSandbox()，
  │      经 SandboxAware/SandboxManager 获取当前 call 绑定的沙箱实例）
  │
  ├─ 2. 从沙箱拉取运行时文件
  │     ├─ /workspace/MEMORY.md          (存在则拉取)
  │     └─ /workspace/memory/*.md         (目录列表 + 逐个拉取)
  │
  ├─ 3. 写入 KV（DistributedStore.baseStore() → agent_fs 表）
  │     ├─ MEMORY.md      → root 命名空间（与 RemoteFilesystemSpec 路由一致）
  │     └─ memory/xxx.md  → memory 命名空间
  │
  └─ 4. 完成（失败仅告警，不阻塞主流程）
```

#### 4.4.3 设计要点

| 要点 | 决策 |
|------|------|
| 回写频率 | 每次用户请求完成后（同步/流式都覆盖），一致性最好 |
| 回写内容 | 仅运行时文件（MEMORY.md、memory/），静态模板不回写（沙箱不可修改模板语义） |
| 回写通道 | `DistributedStore.baseStore()`（JdbcStore → agent_fs），key 与注入时一致 |
| 失败处理 | 不阻塞主流程，日志告警；沙箱内数据保留，下次 call 或销毁前补偿拉取 |
| 并发安全 | 同 userId 并发时最后写入胜出（与框架 AgentStateStore 语义一致） |
| 开销评估 | MEMORY.md 通常几 KB~几十 KB，memory/ 为每日日志（consolidation 上限 8K tokens），每次请求一次拉取可接受 |

---

## 5. 配置

### 5.1 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `SANDBOX_ENABLED` | `false` | 是否启用沙箱模式 |
| `SANDBOX_IMAGE` | `opensandbox/code-interpreter:v1.1.0` | 沙箱镜像 |
| `SANDBOX_TIMEOUT_MINUTES` | `60` | 沙箱超时时间 |
| `SANDBOX_MEMORY_MB` | `1024` | 内存限制 (MiB) |
| `SANDBOX_CPU_COUNT` | `1` | CPU 限制 |
| `SANDBOX_ENTRYPOINT` | `/opt/code-interpreter/code-interpreter.sh` | 覆盖镜像默认启动命令（逗号分隔，如 `python,main.py`；默认即镜像启动脚本） |
| `OPENSANDBOX_SERVER_URL` | `192.168.31.155:8090` | OpenSandbox Server 地址 |
| `OPENSANDBOX_API_KEY` | — | API 密钥 |

### 5.2 application.yml

```yaml
agent:
  sandbox:
    enabled: ${SANDBOX_ENABLED:false}
    image: ${SANDBOX_IMAGE:opensandbox/code-interpreter:v1.1.0}
    timeout-minutes: ${SANDBOX_TIMEOUT_MINUTES:60}
    memory-mb: ${SANDBOX_MEMORY_MB:1024}
    cpu-count: ${SANDBOX_CPU_COUNT:1}
    entrypoint: ${SANDBOX_ENTRYPOINT:/opt/code-interpreter/code-interpreter.sh}
    opensandbox:
      server-url: ${OPENSANDBOX_SERVER_URL:192.168.31.155:8090}
      api-key: ${OPENSANDBOX_API_KEY}
```

### 5.3 .env.secrets (新增)

```bash
# OpenSandbox
OPENSANDBOX_API_KEY=CWpXBzEIlS3edCFQBxK2u+cGK9n08GiYKT22f2JzlxdmJNeAh4waxPHwOEp7pFNW
```

---

## 6. 实现步骤

| 步骤 | 内容 | 文件 | 优先级 |
|------|------|------|--------|
| 1 | 添加 OpenSandbox SDK 依赖 | `pom.xml` | P0 |
| 2 | 实现 `SandboxConfig` | `config/SandboxConfig.java` | P0 |
| 3 | 实现 `OpenSandboxState` | `sandbox/opensandbox/OpenSandboxState.java` | P0 |
| 4 | 实现 `OpenSandboxClientOptions` | `sandbox/opensandbox/OpenSandboxClientOptions.java` | P0 |
| 5 | 实现 `WorkspaceReader`（KV 运行时文件读取 + 沙箱注入） | `service/WorkspaceReader.java` | P0 |
| 6 | 实现 `OpenSandbox` | `sandbox/opensandbox/OpenSandbox.java` | P0 |
| 7 | 实现 `OpenSandboxClient`（create 时注入 KV 运行时文件） | `sandbox/opensandbox/OpenSandboxClient.java` | P0 |
| 8 | 实现 `OpenSandboxFilesystemSpec` | `sandbox/opensandbox/OpenSandboxFilesystemSpec.java` | P0 |
| 9 | 修改 `AgentScopeConfig` | `config/AgentScopeConfig.java` | P0 |
| 10 | 实现 `WorkspaceSyncService`（每次请求后回写 KV） | `sandbox/opensandbox/WorkspaceSyncService.java` | P0 |
| 11 | 修改 `AgentRuntimeService`（同步 invoke 返回后 / 流式 AGENT_END 处挂载回写） | `service/AgentRuntimeService.java` | P0 |
| 12 | 实现沙箱超时回收（定时清理 SessionSandboxStateStore） | `sandbox/opensandbox/OpenSandboxManager.java` | P1 |
| 13 | 实现沙箱健康检查 | `sandbox/opensandbox/OpenSandboxHealthChecker.java` | P1 |

---

## 7. 风险与注意事项

### 7.1 OpenSandbox 官方镜像兼容性

AgentScope 对沙箱镜像有严格约束：

| 要求 | 说明 |
|------|------|
| Shell | POSIX 兼容 `sh`（支持 `[ ]`、`&&`、管道、重定向） |
| 核心工具 | `mkdir` `dirname` `rm` `mv` `test` `printf` `sort` |
| 文本/查找 | `sed` `grep`（支持 `-rHnF` `--include`）`find` |
| 元数据 | GNU 风格 `stat -c`（非 BSD `stat -f`） |
| 归档/编码 | `tar`、`base64`（编码 + `-d` 解码） |
| 解释器 | `python3` |

**验证结论（2026-08-12 实测通过）**：`opensandbox/code-interpreter:v1.1.0` 满足全部约束：

| 要求 | 实测结果 |
|------|---------|
| POSIX sh | ✅ |
| python3 | ✅ Python 3.12.3 |
| tar | ✅ GNU tar 1.35 |
| base64 | ✅ 编码 + 解码 |
| stat -c (GNU) | ✅ |
| grep -rHnF --include | ✅ |
| 核心工具（mkdir/dirname/rm/mv/test/printf/sort/sed/find） | ✅ 全部存在 |
| /workspace 可写 | ✅ |

> 注意：镜像默认 entrypoint 是 Jupyter 启动脚本，验证/调试需用 `--entrypoint sh` 覆盖。

### 7.2 沙箱创建失败处理

沙箱创建失败时直接报错，不回退到其他模式。错误信息应包含：
- OpenSandbox Server 连接状态
- 错误码和错误消息
- Request ID（用于排查）

### 7.3 并发控制

USER 级别共享时，同一用户的并发请求需要串行化。AgentScope 提供 `SandboxExecutionGuard` 接口：

- `RedisSandboxExecutionGuard`（基于 Redis `SET NX PX`）
- `JdbcSandboxExecutionGuard`（基于 MySQL `GET_LOCK()`）

当前使用 `DistributedStore` 时，框架会自动注入执行守卫。

### 7.4 资源清理

沙箱超时后由 OpenSandbox Server 自动销毁（Agent 侧无沙箱实例句柄缓存）。Agent 侧需要：
- 定时任务（如每 10 分钟）清理 `SessionSandboxStateStore` 中失效状态（沙箱已销毁的 sandboxId）
- resume 失败（404）时即清理该用户状态（配合框架自动降级新建）
- Pod 优雅关闭时无需特殊处理（沙箱在 Server 侧，timeout 到期自动回收）

---

## 8. 待确认事项

### 8.1 OpenSandbox 相关

| # | 问题 | 状态 | 答案 | 影响范围 |
|---|------|------|------|---------|
| 1 | **OpenSandbox Server 部署方式** | ✅ 已确认 | Docker Compose 独立部署，`/root/opensandbox-deploy/docker-compose.yaml` | 运维部署 |
| 2 | **OpenSandbox Java SDK 版本** | ✅ 已确认 | `1.0.18`（2026-08-06 发布，GitHub Releases + Maven Central 确认） | `pom.xml` 依赖 |
| 3 | **官方镜像地址** | ✅ 已确认 | `opensandbox/code-interpreter:v1.1.0` | 沙箱配置 |
| 4 | **镜像兼容性** | ✅ 已验证 | `code-interpreter:v1.1.0` 实测全部通过：POSIX sh ✅ / python3 3.12.3 ✅ / GNU tar 1.35 ✅ / base64 ✅ / stat -c ✅ / grep 等核心工具全齐 ✅ / /workspace 可写 ✅（2026-08-12 实测） | 沙箱功能 |
| 5 | **沙箱端口** | ✅ 已确认 | execd 端口 `44772/tcp`（映射到宿主机动态端口 40000-60000） | `OpenSandboxClient` 实现 |
| 6 | **沙箱续期机制** | ✅ 已确认 | **不续期**。AgentScope 无 renew 机制（Sandbox 接口反编译无续期方法），但 SandboxManager.acquire() 内置"resume 失败自动降级新建"（catch Exception → falling through to fresh create，反编译确认）：沙箱 timeout 到期销毁 → 下次请求 resume 404 → 自动 create 重建 → 注入 KV 记忆，用户无感恢复。需保留执行环境时启用 JdbcSnapshotSpec（MySQL 快照）<br>**注意**：`SANDBOX_TIMEOUT_MINUTES` 默认 60 分钟（已确认），沙箱销毁后跨会话复用退化为重建（仅活跃窗口内复用）。若需"跨会话保留执行环境"，应调大 timeout（如 7 天）或启用 JdbcSnapshotSpec | 生命周期管理 |
| 7 | **沙箱网络策略** | ✅ 已确认 | 默认由沙箱服务决策（不传 `networkPolicy` = allow-all）。创建 API 支持 `networkPolicy` 字段（defaultAction + egress 域名规则，实测 OpenAPI 确认），需要精细控制时可传入 | 网络策略配置 |

### 8.2 AgentScope 相关

| # | 问题 | 状态 | 答案 | 影响范围 |
|---|------|------|------|---------|
| 8 | **Workspace 注入方式** | ✅ 已决策 | **分层结合**：doHydrateWorkspace（框架契约，投影+快照恢复）为主 + KV 运行时文件（MEMORY.md/memory/）**首次 exec 延迟注入**（create() 无 RuntimeContext 拿不到 userId，反编译确认；框架所有文件操作最终走 exec，首次文件操作前注入即可） | `OpenSandboxClient` 实现 |
| 9 | **沙箱状态序列化** | ⏳ 待测试 | 需测试 `SandboxState` JSON 序列化兼容性 | 状态持久化 |
| 10 | **沙箱并发控制** | ✅ 已确认 | 使用框架自动注入的 `JdbcSandboxExecutionGuard`（MySQL `GET_LOCK()`）：通过 `distributedStore(...)` 配置时框架自动注入 executionGuard，复用现有 MySQL + DistributedStore | 并发安全 |
| 11 | **沙箱超时清理** | ✅ 已确认 | **三层清理**：① OpenSandbox timeout 到期自动销毁沙箱；② Agent 侧定时任务（如每 10 分钟）清理 SessionSandboxStateStore 中失效状态；③ resume 失败（404）时即清理该用户状态（配合框架自动降级新建） | 资源管理 |

### 8.3 Workspace 相关

| # | 问题 | 状态 | 答案 | 影响范围 |
|---|------|------|------|---------|
| 12 | **Workspace 文件范围** | ✅ 已确认 | **静态模板 + 运行时数据都需要注入**。静态：AGENTS.md/tools.json/skills/subagents/knowledge（框架投影自动注入）；运行时：MEMORY.md/memory/（记忆系统依赖，须 SDK 文件 API 补注入） | `WorkspaceReader` 实现 |
| 13 | **Workspace 增量更新** | ✅ 已确认 | 静态模板（AGENTS.md/skills/subagents/knowledge）：依赖框架投影自带的 SHA-256 内容哈希增量比对（未变更跳过传输），无需额外机制；运行时文件（MEMORY.md/memory/）：每次请求回写 KV + 下次注入时整体覆盖 | 性能优化 |
| 14 | **运行时文件同步** | ✅ 已确认 | 需要，且**每次用户请求完成后回写**：框架 call 结束 → SandboxManager.release → OpenSandbox.stop() 时从沙箱拉取 MEMORY.md/memory/ 写入 KV（见 4.4 回写同步机制） | 数据流设计 |

### 8.4 部署运维相关

| # | 问题 | 状态 | 答案 | 影响范围 |
|---|------|------|------|---------|
| 15 | **OpenSandbox Server 高可用** | ✅ 已确认 | **不考虑 OpenSandbox Server 自身高可用**（沙箱服务按单点部署运维）。Agent 侧无状态多副本已满足：SandboxState 存 MySQL（agent_state）、并发锁 JdbcSandboxExecutionGuard（MySQL GET_LOCK 跨副本有效）、记忆回写 KV（MySQL）→ 任意 Agent 副本可 resume 同一用户的同一沙箱 | 运维部署 |
| 16 | **沙箱资源限制** | ✅ 已确认 | 默认 `cpu: 1, memory: 1Gi`，可通过 `resourceLimits` 配置 | 沙箱配置 |
| 17 | **沙箱监控** | ⏳ 待确认 | 可通过 `/v1/sandboxes/<id>/diagnostics/summary` 获取诊断 | 运维监控 |
| 18 | **沙箱日志** | ⏳ 待确认 | `docker compose logs -f` 查看 Server 日志 | 调试排查 |

---

## 9. 验证清单

在实施前，需要完成以下验证：

### 9.1 OpenSandbox Server 验证

```bash
# 1. 健康检查
curl http://192.168.31.155:8090/health
# 预期: {"status":"healthy"}

# 2. 创建测试沙箱
curl -X POST http://192.168.31.155:8090/v1/sandboxes \
  -H "OPEN-SANDBOX-API-KEY: CWpXBzEIlS3edCFQBxK2u+cGK9n08GiYKT22f2JzlxdmJNeAh4waxPHwOEp7pFNW" \
  -H "Content-Type: application/json" \
  -d '{
    "image": {"uri": "opensandbox/code-interpreter:v1.1.0"},
    "entrypoint": ["tail", "-f", "/dev/null"],
    "resourceLimits": {"cpu": "1", "memory": "1Gi"}
  }'
# 预期: 202 + sandbox ID

# 3. 查询沙箱状态
curl http://192.168.31.155:8090/v1/sandboxes/<sandbox_id> \
  -H "OPEN-SANDBOX-API-KEY: CWpXBzEIlS3edCFQBxK2u+cGK9n08GiYKT22f2JzlxdmJNeAh4waxPHwOEp7pFNW"

# 4. 获取 execd 端点
curl http://192.168.31.155:8090/v1/sandboxes/<sandbox_id>/endpoints/44772 \
  -H "OPEN-SANDBOX-API-KEY: CWpXBzEIlS3edCFQBxK2u+cGK9n08GiYKT22f2JzlxdmJNeAh4waxPHwOEp7pFNW"
# 预期: {"endpoint": "192.168.31.155:52051/proxy/44772"}

# 5. 删除测试沙箱
curl -X DELETE http://192.168.31.155:8090/v1/sandboxes/<sandbox_id> \
  -H "OPEN-SANDBOX-API-KEY: CWpXBzEIlS3edCFQBxK2u+cGK9n08GiYKT22f2JzlxdmJNeAh4waxPHwOEp7pFNW"
```

### 9.2 镜像兼容性验证

```bash
# 在 opensandbox/code-interpreter:v1.1.0 镜像内执行自检
# 注意：镜像默认 entrypoint 为 Jupyter 启动脚本，必须用 --entrypoint sh 覆盖
docker run --rm --entrypoint sh opensandbox/code-interpreter:v1.1.0 -c '
  sh -c "echo ok" && \
  python3 --version && \
  tar --version && \
  printf x | base64 | base64 -d && \
  stat -c %Y /tmp && \
  grep -rHnF --include="*.txt" x /tmp; \
  true
'
# 预期: 全部成功（2026-08-12 实测已通过）
```

### 9.3 Java SDK 集成验证

```java
// 1. 验证 SDK 依赖可以正常引入
// 2. 验证 ConnectionConfig 可以正常创建
// 3. 验证 Sandbox.builder() 可以正常构建
// 4. 验证 sandbox.commands().run() 可以正常执行
// 5. 验证 sandbox.files().write()/readFile() 可以正常操作
```

### 9.4 AgentScope SandboxClient 接口验证

```java
// 1. 验证 OpenSandboxClient 实现 SandboxClient 接口
// 2. 验证 create() 可以正常创建沙箱
// 3. 验证 resume() 可以正常恢复沙箱
// 4. 验证 delete() 可以正常删除沙箱
// 5. 验证 serializeState()/deserializeState() 可以正常序列化
```

---

## 10. 决策记录

| 决策 | 选项 | 选择 | 理由 | 状态 |
|------|------|------|------|------|
| 沙箱后端 | Docker / Kubernetes / OpenSandbox | OpenSandbox | 统一沙箱管理平台，支持多语言 SDK | ✅ 已决策 |
| 隔离级别 | SESSION / USER / AGENT | USER | 同一用户跨会话复用沙箱 | ✅ 已决策 |
| 沙箱创建失败 | 回退 / 报错 | 报错 | 明确失败，避免静默降级 | ✅ 已决策 |
| 默认镜像 | ubuntu:24.04 / python:3.11-slim / opensandbox/code-interpreter:v1.1.0 | opensandbox/code-interpreter:v1.1.0 | 用户指定 OpenSandbox 官方 Code Interpreter 镜像 | ✅ 已决策 |
| 沙箱端口 | 8888 / 44772 | 44772 | OpenSandbox execd 端口 | ✅ 已决策 |
| Workspace 注入 | doHydrateWorkspace / SDK 文件 API | 分层结合（doHydrateWorkspace 为主 + 首次 exec 延迟注入） | 框架契约必须实现 doHydrateWorkspace；KV 运行时文件因 create() 无 userId，改为首次 exec 时注入 | ✅ 已决策 |
| 沙箱续期 | 自动 renew / 依赖框架自动重建 | 依赖框架自动重建（不续期） | AgentScope 无 renew 机制，但 resume 失败自动降级新建；记忆靠每次回写 KV 恢复；需保留执行环境时加 JdbcSnapshotSpec | ✅ 已决策 |
| 并发控制 | JdbcSandboxExecutionGuard / 自定义 | JdbcSandboxExecutionGuard | 框架通过 distributedStore 自动注入（MySQL GET_LOCK），复用现有 MySQL + DistributedStore，零额外开发 | ✅ 已决策 |
| 超时清理 | OpenSandbox 自动销毁 / Agent 定时清理 / resume 失败即清理 | 三层结合 | ① 到期自动销毁 ② 定时清理 SessionSandboxStateStore 失效状态 ③ resume 404 即清理并降级新建 | ✅ 已决策 |
| 增量更新 | 框架投影哈希比对 / 自定义同步 | 框架投影哈希比对（静态）+ 每次覆盖（运行时） | 静态模板靠框架自带 SHA-256 增量；MEMORY.md/memory/ 每次回写 + 注入覆盖 | ✅ 已决策 |
| Server 高可用 | 多实例 / 单实例 | 单实例（不考虑 Server 自身高可用） | Agent 侧无状态多副本已满足（SandboxState/并发锁/记忆均在 MySQL），沙箱服务按单点运维 | ✅ 已决策 |
