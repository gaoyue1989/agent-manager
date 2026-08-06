# Agent Framework 工具体系现状分析与改进方案

## 1. 现状分析

### 1.1 当前工具架构

```
OAF AGENTS.md
  └── tools: [Read, Bash, Edit]        ← OAF 工具名（Claude Code 风格）
  └── mcpServers: [...]                ← MCP 服务器配置
       │
       ▼
WorkspaceInitializer.writeToolsJson()
  ├── mapToolName(): Read→read_file, Bash→execute, Edit→edit_file
  ├── REQUIRED_TOOLS: 15 个 Harness 内置工具硬编码
  └── 生成 tools.json { allow: [...], mcpServers: {...} }
       │
       ▼
HarnessAgent.builder()
  └── ToolsConfigLoader 读取 tools.json
       └── ToolFilter.apply(allow, deny) 过滤 Toolkit 中的工具
```

### 1.2 当前问题

| 问题 | 影响 |
|------|------|
| `allow` 白名单误删内置工具 | 需要硬编码 `REQUIRED_TOOLS` 15 个工具名 |
| `REQUIRED_TOOLS` 与 AgentScope 版本耦合 | AgentScope 升级新增工具时，必须同步更新列表 |
| MCP 工具配置方式非原生 | 写入 `tools.json` 而非用 `McpClientBuilder` 注册 |
| 无自定义业务工具 | 没有 `@Tool` 注解或 `ToolBase` 实现 |
| 无 ToolGroup 组织 | 所有工具平铺，无按需激活/停用能力 |
| `mapToolName()` 硬编码 | OAF 工具名映射固定，无法扩展 |

### 1.3 AgentScope 工具体系能力

| 能力 | 机制 | 当前使用 |
|------|------|---------|
| `@Tool` 注解 | 反射注册自定义工具 | ❌ |
| `ToolBase` 继承 | 复杂工具（权限/外部执行） | ❌ |
| `Toolkit.registerTool()` | 动态注册 | ❌ |
| `ToolGroup` | 工具分组 + 按需激活 | ❌ |
| `SkillToolGroup` | 技能绑定工具组 | ❌ |
| `reset_tools` meta tool | Agent 自我管理工具集 | ❌ |
| `McpClientBuilder` | MCP 服务器注册 | ❌ (用 tools.json 替代) |
| `ToolFilter` (allow/deny) | 工具过滤 | ✅ (但用法不当) |

---

## 2. 改进方案

### 2.1 核心思路

**从 `tools.json` allow 白名单模式 → `deny` 排除模式 + ToolGroup 组织 + MCP 原生注册**

### 2.2 架构对比

**当前**:
```
OAF tools → mapToolName → tools.json { allow: [映射后工具 + REQUIRED_TOOLS] }
                         → ToolFilter 过滤
```

**目标**:
```
OAF tools → 不写 allow（保留所有内置工具）
          → 只写 deny（排除不需要的工具）
OAF mcpServers → McpClientBuilder 原生注册
自定义工具 → @Tool 注解 + Toolkit.registerTool()
工具组织 → ToolGroup 按需激活
```

### 2.3 具体改动

#### 改动 1: `WorkspaceInitializer.writeToolsJson()` — 移除 allow 白名单

**当前问题**: `allow` 白名单会过滤所有未列出的工具，导致需要硬编码 `REQUIRED_TOOLS`。

**改进**: 不写 `allow`，只在需要时写 `deny`。

```java
// 当前（有问题）
if (!oafConfig.tools().isEmpty()) {
    var allowNode = MAPPER.createArrayNode();
    for (var tool : oafConfig.tools()) {
        var mapped = mapToolName(tool);
        if (mapped != null) allowNode.add(mapped);
    }
    for (var builtin : REQUIRED_TOOLS) {
        allowNode.add(builtin);
    }
    root.set("allow", allowNode);  // ← 过滤所有未列出的工具
}

// 改进后
// 不写 allow → 所有注册的工具都可用
// 只在需要时写 deny 排除特定工具
if (!oafConfig.deniedTools().isEmpty()) {
    var denyNode = MAPPER.createArrayNode();
    for (var tool : oafConfig.deniedTools()) {
        denyNode.add(tool);
    }
    root.set("deny", denyNode);
}
```

**效果**:
- HarnessAgent 内置工具（memory_search, plan_write 等）自动可用
- 不需要 `REQUIRED_TOOLS` 硬编码
- OAF `tools` 字段语义从"allow 白名单"改为"deny 黑名单"（或忽略）

#### 改动 2: MCP 工具原生注册

**当前问题**: MCP 配置写入 `tools.json`，由 HarnessAgent 内部解析。但 `tools.json` 的 `mcpServers` 格式与 AgentScope 原生 `McpClientBuilder` 不一致。

**改进**: 在 `AgentScopeConfig` 中用 `McpClientBuilder` 原生注册 MCP 工具。

```java
// 当前（通过 tools.json）
for (var mcp : oafConfig.mcpServers()) {
    serverNode.put("transport", "sse");
    serverNode.put("url", "http://localhost:8811/sse");
    mcpServers.set(mcp.server(), serverNode);
}
root.set("mcpServers", mcpServers);

// 改进后（原生注册）
@Bean
public List<McpClientWrapper> mcpClients(OafConfig oafConfig, AgentManagerProperties props) {
    var clients = new ArrayList<McpClientWrapper>();
    for (var mcp : oafConfig.mcpServers()) {
        var configDir = Path.of(props.configDir()).resolve(mcp.configDir());
        var configYaml = configDir.resolve("config.yaml");
        if (!configYaml.toFile().exists()) continue;

        // 从 config.yaml 读取连接配置
        var yaml = new Yaml();
        var data = (Map<String, Object>) yaml.load(Files.newInputStream(configYaml));
        var conn = (Map<String, Object>) data.get("connection");
        var type = (String) conn.get("type");
        var url = (String) conn.get("url");

        McpClientWrapper client;
        if ("sse".equals(type)) {
            client = McpClientBuilder.sse().name(mcp.server()).url(url).build();
        } else if ("stdio".equals(type)) {
            client = McpClientBuilder.stdio().name(mcp.server())
                .command((String) conn.get("command")).build();
        } else {
            client = McpClientBuilder.streamableHttp().name(mcp.server()).url(url).build();
        }
        clients.add(client);
    }
    return clients;
}
```

#### 改动 2.5: MCP 工具 `permissions.read_only` 支持

**问题**: AgentScope 的 `McpTool.checkPermissions()` 对非只读工具返回 `PermissionDecision.ask()`（需 HITL 授权），导致工具调用挂起。`config.yaml` 中的 `permissions.read_only` 字段原先不被 `McpToolRegistrar` 读取。

**解决方案**: `McpToolRegistrar` 读取 `config.yaml` 的 `permissions.read_only`，当为 `true` 时走 `registerReadOnly()` 路径：遍历 MCP 工具，手动构造 `readOnly=true` 的 McpTool 注册到 Toolkit。

**优先级**: config.yaml `permissions.read_only: true` > server 端 `annotations.readOnlyHint` > 默认 HITL ask

```java
// McpToolRegistrar.registerAll() 中新增逻辑
boolean forceReadOnly = isReadOnlyConfigured(mcp);
if (forceReadOnly) {
    registerReadOnly(toolkit, wrapper, mcp.server());  // 手动注册 readOnly=true
} else {
    toolkit.registerMcpClient(wrapper).block();          // 标准注册（依赖 server annotations）
}
```

**E2E 验证**: 移除 MCP server 端 `readOnlyHint`，仅靠 `config.yaml permissions.read_only: true`，LLM 调用 `get_weather` 成功返回天气数据。

#### 改动 3: 自定义工具注册（可选扩展）

**场景**: 业务需要自定义工具（如调用内部 API、执行特定操作）。

**方式**: 使用 `@Tool` 注解 + `Toolkit.registerTool()`。

```java
// 示例：自定义业务工具
public class BusinessTools {
    @Tool(name = "query_order", description = "查询订单信息", readOnly = true)
    public String queryOrder(
            @ToolParam(name = "order_id", description = "订单ID") String orderId,
            RuntimeContext ctx) {
        var tenantId = ctx.getUserId();
        // 调用内部 API
        return orderService.query(tenantId, orderId);
    }
}

// 注册到 HarnessAgent
@Bean
public HarnessAgent harnessAgent(..., BusinessTools businessTools) {
    var toolkit = new Toolkit();
    toolkit.registerTool(businessTools);  // 注册自定义工具

    return HarnessAgent.builder()
        // ...
        .toolkit(toolkit)  // 传入自定义 toolkit
        .build();
}
```

#### 改动 4: ToolGroup 组织（可选扩展）

**场景**: 工具太多时，按需激活/停用工具组，减少上下文噪音。

```java
// 创建工具组
var toolkit = new Toolkit();

// 基础工具组（始终激活）
toolkit.createToolGroup("basic", "Basic tools", true);

// 数据库工具组（默认不激活）
toolkit.createToolGroup("database", "Database operations", false);
toolkit.registration()
    .tool(new DatabaseTools())
    .group("database")
    .apply();

// 部署工具组（默认不激活）
toolkit.createToolGroup("deployment", "Deployment tools", false);
toolkit.registration()
    .tool(new DeploymentTools())
    .group("deployment")
    .apply();

// 启用 meta tool，让 Agent 自己管理工具组
HarnessAgent.builder()
    .toolkit(toolkit)
    .enableMetaTool(true)  // Agent 可调用 reset_tools 切换工具组
    .build();
```

#### 改动 5: 技能绑定工具组（可选扩展）

**场景**: 某些工具只在加载特定技能时才需要。

```java
// 创建与技能绑定的工具组
toolkit.createSkillToolGroup(
    "code-analysis",           // 组名
    "Code analysis tools",     // 描述
    false,                     // 初始不激活
    "code-review"              // 绑定的技能名
);

// 注册工具到该组
toolkit.registration()
    .tool(new CodeAnalysisTools())
    .group("code-analysis")
    .apply();

// 当 Agent 通过 load_skill_through_path("code-review") 加载技能时
// → code-analysis 组自动激活
// → 该组中的工具立即可用
```

---

## 3. 实施计划

### 3.1 阶段划分

| 阶段 | 内容 | 工作量 | 优先级 | 状态 |
|------|------|--------|--------|------|
| 阶段一 | 移除 allow 白名单，改用 deny 模式 | 0.5 天 | P0 | ✅ 已完成 |
| 阶段二 | MCP 原生注册 | 1 天 | P1 | ✅ 已完成 |
| 阶段三 | 自定义工具注册机制 | 0.5 天 | P2 | ✅ 已完成 |
| 阶段四 | ToolGroup 组织 | 1 天 | P2 | ⏳ 未实施（可选） |

### 3.2 依赖关系

```
阶段一 (移除 allow) ──→ 阶段二 (MCP 原生) ──→ 阶段三 (自定义工具)
                                                    │
                                                    ▼
                                              阶段四 (ToolGroup)
```

### 3.3 向后兼容

- 阶段一：`tools.json` 中如果存在 `allow`，保留兼容但打 WARN 日志
- 阶段二：`tools.json` 中的 `mcpServers` 保留兼容，优先使用 `McpClientBuilder`
- 阶段三/四：纯新增能力，无破坏性变更

---

## 4. 文件变更清单

### 4.1 修改文件

| 文件 | 变更 |
|------|------|
| `service/WorkspaceInitializer.java` | 移除 allow 白名单 + REQUIRED_TOOLS；改用 deny 模式 |
| `model/OafConfig.java` | 新增 `deniedTools` 字段（可选） |
| `config/OafConfigLoader.java` | 解析 `deniedTools` 字段 |
| `config/AgentScopeConfig.java` | 新增 MCP 原生注册 Bean；新增自定义工具注册 |

### 4.2 新增文件（可选）

| 文件 | 说明 |
|------|------|
| `tool/BusinessTools.java` | 自定义业务工具（@Tool 注解） |

### 4.3 删除内容

| 内容 | 文件 | 说明 |
|------|------|------|
| `REQUIRED_TOOLS` 常量 | `WorkspaceInitializer.java` | 移除硬编码列表 |
| `mapToolName()` 方法 | `WorkspaceInitializer.java` | 移除 OAF 工具名映射 |

---

## 5. 验证方案

### 5.1 阶段一验证

```bash
# 启动后检查工具数量
curl -s http://localhost:8101/tools | python3 -c "import json,sys; d=json.load(sys.stdin); print(len(d['builtin']), 'tools')"

# 预期：所有 HarnessAgent 内置工具可用（约 20+ 个），不只有 16 个
```

### 5.2 阶段二验证

```bash
# 启动后检查 MCP 工具
curl -s http://localhost:8101/mcp

# 预期：MCP 服务器通过 McpClientBuilder 注册，工具可用
```

### 5.3 阶段三验证

```bash
# 自定义工具可用
curl -s -X POST http://localhost:8101/ -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"message/send","params":{"message":{"role":"user","parts":[{"kind":"text","text":"查询订单 12345"}]}},"id":"1"}'

# 预期：Agent 调用 query_order 工具
```

---

## 6. 相关文档

| 文档 | 说明 |
|------|------|
| [AgentScope Tool 文档](https://java.agentscope.io/v2/zh/docs/building-blocks/tool.html) | 工具体系官方文档 |
| [AgentScope Harness Tooling](https://java.agentscope.io/v2/zh/docs/harness/tool.html) | Harness 工具集成 |
| [OAF 改进方案](oaf-improvement-plan.md) | OAF → Workspace 转换 |
| [change-execution-order.md](change-execution-order.md) | 执行顺序文档 |
