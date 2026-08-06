# OAF 规范补全 + OAF → AgentScope Workspace 转换方案

## 1. 现状分析

### 1.1 字段解析覆盖度

| OAF v0.8.0 字段 | 实现状态 | 文件位置 |
|---|---|---|
| `name` | ✅ | `OafConfigLoader.java:38` |
| `vendorKey` | ✅ | `OafConfigLoader.java:40` |
| `agentKey` | ✅ | `OafConfigLoader.java:42` |
| `version` | ✅ | `OafConfigLoader.java:44` |
| `slug` | ✅ | `OafConfigLoader.java:46` |
| `description` | ✅ | `OafConfigLoader.java:48` |
| `author` | ✅ | `OafConfigLoader.java:50` |
| `license` | ✅ | `OafConfigLoader.java:52` |
| `tags` | ✅ | `OafConfigLoader.java:54` |
| `tools` | ✅ | `OafConfigLoader.java:56` |
| `skills` | ✅ | `OafConfigLoader.java:95-108` |
| `mcpServers` | ✅ | `OafConfigLoader.java:126-140` |
| `agents` (sub-agents) | ✅ | `OafConfigLoader.java:143-159` |
| `model` (string/object) | ✅ | `OafConfigLoader.java:162-176` |
| `config` | ✅ | `OafConfigLoader.java:179-190` |
| `memory` | ✅ | `OafConfigLoader.java:193-205` |
| `packs` | ❌ 未解析 | — |
| `weblets` | ❌ 未解析 | — |
| `orchestration` | ❌ 未解析 | — |
| `harnessConfig` | ⚠️ 部分 | 仅解析 A2UI catalog_id |

**覆盖率: 16/20 (80%)**

### 1.2 目录结构支持

| OAF 目录 | 支持状态 | 说明 |
|---|---|---|
| `skills/{name}/SKILL.md` | ✅ | `SkillManager.java:44-72` |
| `mcp-configs/{server}/ActiveMCP.json` | ✅ | `McpManager.java:51-58` |
| `mcp-configs/{server}/config.yaml` | ✅ | `McpManager.java:61-72` |
| `versions/` | ❌ | 不支持版本历史 |
| `examples/` | ❌ | 不加载示例 |
| `tests/` | ❌ | 不加载测试场景 |
| `docs/` | ❌ | 不加载文档 |
| `assets/` | ❌ | 不加载资源文件 |

### 1.3 AgentScope 2.0 功能使用情况

当前实现使用 AgentScope 最底层的 `ReActAgent` + `Toolkit` + `AgentStateStore`，**完全绕过了 Harness 层**：

| AgentScope 2.0 功能 | 当前使用状态 | 说明 |
|---|---|---|
| **技能（Skill）** | ❌ 未使用 | 自定义 `SkillManager`，未用 AgentScope `skillRepository()` 或 Workspace `skills/` |
| **记忆仓库** | ❌ 未使用 | 未接入 Mem0/ReMe/百炼记忆 |
| **记忆管理** | ❌ 未使用 | 未配置 `MEMORY.md` + `memory/` 分层记忆 |
| **上下文压缩** | ❌ 未使用 | 未配置 `CompactionConfig` |
| **任务规划（Plan Mode）** | ❌ 未使用 | 未启用 `enablePlanMode()` |
| **Channel** | ❌ 未使用 | 未配置消息通道 |
| **工作区（Workspace）** | ❌ 未使用 | 未调用 `.workspace(path)` |
| **子 Agent（Subagent）** | ❌ 未使用 | 未用 `subagents/*.md` 声明 |
| **沙箱（Sandbox）** | ❌ 未使用 | 未配置 Docker/K8s 沙箱 |

### 1.4 关键缺失

1. **Remote Skills**: 仅支持 `source: "local"`，忽略远程 URL
2. **Packs**: Skills 集合包未解析
3. **Weblets**: Web 工具/接口未解析
4. **Orchestration**: 编排配置未解析
5. **harnessConfig**: 仅解析 A2UI catalog_id，未透传完整配置
6. **未利用 AgentScope Workspace**: 所有 Harness 特有功能均未使用

---

## 2. OAF → AgentScope Workspace 转换方案（核心改进）

### 2.1 设计理念

**核心思路**: 将 OAF `AGENTS.md` 解析结果在 Agent 启动时转换为 AgentScope Workspace 目录结构，然后用 `HarnessAgent.builder().workspace(path)` 加载。这样一次性获得所有 Harness 能力（记忆管理、上下文压缩、技能自学习、Plan Mode、子 Agent、沙箱）。

**优势**:
- 零代码获得 6 大 Harness 能力
- OAF 配置格式不变，用户无感
- 多租户自动支持（Workspace `IsolationScope`）
- 与 AgentScope 生态完全兼容

### 2.2 OAF → Workspace 映射关系

```
OAF AGENTS.md                          AgentScope Workspace
─────────────────────────────────────────────────────────────────
frontmatter.name/description     →     AGENTS.md (Builder .name()/.sysPrompt())
frontmatter.body (system prompt) →     AGENTS.md 正文
skills/{name}/SKILL.md           →     workspace/skills/{name}/SKILL.md  (直接复制)
mcp-configs/{server}/            →     workspace/tools.json              (格式转换)
agents (sub-agents)              →     workspace/subagents/{agent-id}.md (格式转换)
model (string/object)            →     Builder .model()
config.temperature/max_tokens    →     Builder 配置
memory                           →     AgentScope 自动管理 MEMORY.md + memory/
orchestration                    →     workspace/subagents/ + agent_spawn
packs                            →     skillRepository() 技能市场
weblets                          →     tools.json MCP server 配置
```

### 2.3 Workspace 目录布局生成

启动时将 OAF 配置转换为以下 Workspace 目录：

```
.agentscope/workspace/
├── AGENTS.md                    ← OAF frontmatter + body 转换
├── tools.json                   ← OAF mcpServers + weblets 转换
├── skills/                      ← OAF skills (本地直接复制)
│   └── {skill-name}/
│       └── SKILL.md
├── subagents/                   ← OAF agents 转换
│   └── {agent-id}.md
└── knowledge/                   ← OAF 额外知识 (可选)
```

### 2.4 实现方案

#### 2.4.1 新增 `service/WorkspaceInitializer.java`

```java
package io.agentmanager.framework.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentmanager.framework.model.OafConfig;

@Service
public class WorkspaceInitializer {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceInitializer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 将 OAF 配置转换为 AgentScope Workspace 目录结构。
     * 返回 Workspace 根路径，可直接传给 HarnessAgent.builder().workspace(path)。
     */
    public Path initialize(Path baseDir, OafConfig oafConfig) throws IOException {
        var workspace = baseDir.resolve(".agentscope").resolve("workspace");
        Files.createDirectories(workspace);

        writeAgentsMd(workspace, oafConfig);
        writeToolsJson(workspace, oafConfig);
        copySkills(workspace, oafConfig);
        writeSubagents(workspace, oafConfig);

        log.info("Workspace initialized at: {}", workspace);
        return workspace;
    }

    /**
     * 生成 AGENTS.md：OAF frontmatter 转换为 AgentScope 格式。
     * AgentScope 的 AGENTS.md 支持 YAML frontmatter + Markdown body，
     * 与 OAF 格式天然兼容。
     */
    private void writeAgentsMd(Path workspace, OafConfig oafConfig) throws IOException {
        var agentsMd = workspace.resolve("AGENTS.md");
        if (Files.exists(agentsMd)) {
            log.info("AGENTS.md already exists, skipping generation");
            return;
        }

        var sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(oafConfig.name()).append("\n");
        sb.append("description: ").append(oafConfig.description()).append("\n");
        if (oafConfig.model() != null) {
            sb.append("model:\n");
            sb.append("  provider: ").append(oafConfig.model().provider()).append("\n");
            sb.append("  name: ").append(oafConfig.model().name()).append("\n");
        }
        if (oafConfig.runtimeConfig() != null) {
            sb.append("config:\n");
            sb.append("  temperature: ").append(oafConfig.runtimeConfig().temperature()).append("\n");
            sb.append("  max_tokens: ").append(oafConfig.runtimeConfig().maxTokens()).append("\n");
        }
        sb.append("---\n\n");
        sb.append(oafConfig.systemPrompt());

        Files.writeString(agentsMd, sb.toString());
        log.info("Generated AGENTS.md");
    }

    /**
     * 生成 tools.json：OAF mcpServers 转换为 AgentScope tools.json 格式。
     *
     * OAF 格式:
     *   mcpServers:
     *     - vendor: block
     *       server: filesystem
     *       configDir: mcp-configs/filesystem
     *
     * AgentScope tools.json 格式:
     *   {
     *     "mcpServers": {
     *       "filesystem": {
     *         "transport": "sse",
     *         "url": "http://localhost:8811/sse"
     *       }
     *     }
     *   }
     */
    private void writeToolsJson(Path workspace, OafConfig oafConfig) throws IOException {
        var toolsJson = workspace.resolve("tools.json");
        if (Files.exists(toolsJson)) {
            log.info("tools.json already exists, skipping generation");
            return;
        }

        var root = MAPPER.createObjectNode();
        var mcpServers = MAPPER.createObjectNode();

        for (var mcp : oafConfig.mcpServers()) {
            var serverNode = MAPPER.createObjectNode();
            // 从 config.yaml 读取连接配置（如果有）
            // 这里先用占位符，实际运行时由 McpManager 填充
            serverNode.put("transport", "sse");
            serverNode.put("url", "http://localhost:8811/sse"); // 占位符
            mcpServers.set(mcp.server(), serverNode);
        }

        // 添加工具白名单（如果有）
        if (!oafConfig.tools().isEmpty()) {
            var allowNode = MAPPER.createArrayNode();
            for (var tool : oafConfig.tools()) {
                allowNode.add(tool);
            }
            root.set("allow", allowNode);
        }

        if (!mcpServers.isEmpty()) {
            root.set("mcpServers", mcpServers);
        }

        MAPPER.writerWithDefaultPrettyPrinter().writeValue(toolsJson.toFile(), root);
        log.info("Generated tools.json with {} MCP servers", oafConfig.mcpServers().size());
    }

    /**
     * 复制 skills：OAF skills/{name}/SKILL.md 直接复制到 Workspace。
     * 本地 skill 直接复制，远程 skill 下载后复制。
     */
    private void copySkills(Path workspace, OafConfig oafConfig) throws IOException {
        var skillsDir = workspace.resolve("skills");
        Files.createDirectories(skillsDir);

        for (var skill : oafConfig.skills()) {
            if ("local".equals(skill.source())) {
                var sourceDir = workspace.getParent().getParent()
                    .resolve("skills").resolve(skill.name());
                var targetDir = skillsDir.resolve(skill.name());
                if (Files.exists(sourceDir) && !Files.exists(targetDir)) {
                    copyDirectory(sourceDir, targetDir);
                    log.info("Copied skill: {}", skill.name());
                }
            }
            // 远程 skill 由 AgentScope 的 skillRepository() 处理
        }
    }

    /**
     * 生成 subagents：OAF agents 转换为 AgentScope subagents/*.md 格式。
     *
     * OAF 格式:
     *   agents:
     *     - vendor: openai
     *       agent: code-reviewer
     *       role: reviewer
     *       delegations: ["code-quality"]
     *
     * AgentScope 格式 (subagents/code-reviewer.md):
     *   ---
     *   description: 代码评审专家
     *   model: ...
     *   tools: [...]
     *   ---
     *   你是一个代码评审专家...
     */
    private void writeSubagents(Path workspace, OafConfig oafConfig) throws IOException {
        var subagentsDir = workspace.resolve("subagents");
        Files.createDirectories(subagentsDir);

        for (var agent : oafConfig.subAgents()) {
            var agentFile = subagentsDir.resolve(agent.agent() + ".md");
            if (Files.exists(agentFile)) {
                continue;
            }

            var sb = new StringBuilder();
            sb.append("---\n");
            sb.append("description: ").append(agent.role()).append("\n");
            if (!agent.delegations().isEmpty()) {
                sb.append("delegations:\n");
                for (var d : agent.delegations()) {
                    sb.append("  - ").append(d).append("\n");
                }
            }
            sb.append("---\n\n");
            sb.append("你是").append(agent.role()).append("。\n");

            Files.writeString(agentFile, sb.toString());
            log.info("Generated subagent: {}", agent.agent());
        }
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        try (var stream = Files.walk(source)) {
            stream.forEach(src -> {
                try {
                    var dest = target.resolve(source.relativize(src));
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dest);
                    } else {
                        Files.createDirectories(dest.getParent());
                        Files.copy(src, dest);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
```

#### 2.4.2 修改 `config/AgentScopeConfig.java`

将 `ReActAgent` 替换为 `HarnessAgent`，使用 Workspace：

```java
@Bean
public io.agentscope.harness.agent.HarnessAgent harnessAgent(
    AgentManagerProperties props,
    DistributedStore distributedStore,
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

    var builder = io.agentscope.harness.agent.HarnessAgent.builder()
        .name(oafConfig.name())
        .sysPrompt(oafConfig.systemPrompt())
        .model(model)
        .workspace(workspacePath)
        .distributedStore(distributedStore)
        // 启用 Harness 核心能力
        .enablePlanMode()                    // Plan Mode
        .enableSkillManageTool(true)         // 技能自学习
        .compaction(CompactionConfig.builder()
            .maxContextTokens(llm.maxTokens() * 4)
            .build())                        // 上下文压缩 + 记忆管理
        ;

    // 注册远程 Skill 仓库（如果有 packs）
    for (var pack : oafConfig.packs()) {
        // builder.skillRepository(new GitSkillRepository(pack.url()));
    }

    var agent = builder.build();
    log.info("HarnessAgent created: {} (model: {}, workspace: {})",
        oafConfig.name(), llm.modelId(), workspacePath);
    return agent;
}
```

#### 2.4.3 修改 `service/AgentRuntimeService.java`

适配 `HarnessAgent` API：

```java
// 当前: 使用 ReActAgent
private io.agentscope.core.ReActAgent agent;

// 改为: 使用 HarnessAgent
private io.agentscope.harness.agent.HarnessAgent agent;

// invoke() 方法变化:
// ReActAgent: agent.call(List.of(userMsg), ctx).block()
// HarnessAgent: agent.call(new UserMessage("user", message), ctx).block()
// API 兼容，无需大改
```

### 2.5 转换后获得的 AgentScope 能力

| 能力 | 来源 | 效果 |
|---|---|---|
| **记忆管理** | `CompactionConfig` + Workspace | 自动维护 `MEMORY.md` + `memory/`，每轮注入 system prompt |
| **上下文压缩** | `CompactionConfig` | 超大工具结果落盘，结构化压缩保留目标/状态/发现 |
| **技能自学习** | `enableSkillManageTool(true)` | agent 自动沉淀成功模式为 SKILL.md |
| **Plan Mode** | `enablePlanMode()` | 只读规划态编排长任务 |
| **子 Agent** | `workspace/subagents/*.md` | `agent_spawn` / `agent_send` 委派 |
| **多租户** | `IsolationScope.USER` | 自动按 userId 隔离记忆/会话/技能 |

---

## 3. OAF 字段补全（阶段一：补充缺失字段）

### 3.1 修改 `model/OafConfig.java`

**新增 Record 类型**:
```java
public record PackConfig(
    String vendor,
    String pack,
    String version,
    boolean required
) {}

public record WebletConfig(
    String vendor,
    String weblet,
    String version,
    String launch  // "onDemand", "background", "foreground"
) {}

public record OrchestrationConfig(
    String entrypoint,
    String fallback,
    List<TriggerConfig> triggers
) {}

public record TriggerConfig(
    String event,
    String action
) {}
```

**OafConfig Record 新增字段**:
```java
public record OafConfig(
    // ... 现有字段 ...
    List<PackConfig> packs,           // 新增
    List<WebletConfig> weblets,       // 新增
    OrchestrationConfig orchestration, // 新增
    Map<String, Object> harnessConfig, // 新增
    Map<String, Object> rawFrontmatter
) {
    public boolean hasPacks() { return !packs.isEmpty(); }
    public boolean hasWeblets() { return !weblets.isEmpty(); }
    public boolean hasOrchestration() { return orchestration != null; }
}
```

### 3.2 修改 `config/OafConfigLoader.java`

新增解析方法 `parsePacks()`, `parseWeblets()`, `parseOrchestration()`, `parseHarnessConfig()`。
在 `load()` 方法中调用并传入 `OafConfig` 构造函数。

### 3.3 Remote Skills 支持

新增 `service/RemoteSkillFetcher.java`，修改 `SkillManager.java` 支持 `source: "http..."` 的远程 Skill 获取。

---

## 4. 实施计划

### 4.1 阶段划分

| 阶段 | 内容 | 工作量 | 优先级 |
|---|---|---|---|
| **阶段一** | OAF → Workspace 转换 (`WorkspaceInitializer`) | 2-3 天 | P0 |
| **阶段二** | AgentScopeConfig 改用 `HarnessAgent` | 1-2 天 | P0 |
| **阶段三** | AgentRuntimeService 适配 | 1 天 | P0 |
| **阶段四** | OAF 字段补全 (packs/weblets/orchestration) | 1-2 天 | P1 |
| **阶段五** | Remote Skills 支持 | 1 天 | P1 |

### 4.2 依赖关系

```
阶段一 (WorkspaceInitializer) ──→ 阶段二 (HarnessAgent) ──→ 阶段三 (RuntimeService)
                                                                      │
阶段四 (字段补全) ──────────────────────────────────────────────────────┘
阶段五 (Remote Skills) ─────────────────────────────────────────────────┘
```

### 4.3 向后兼容

- `WorkspaceInitializer` 仅在 Workspace 目录不存在时生成，已存在则跳过
- 现有 `AGENTS.md` 格式与 AgentScope Workspace 兼容，无需修改
- `HarnessAgent` API 与 `ReActAgent` 兼容（都支持 `call()` / `streamEvents()`）
- 新增功能通过 Builder 开关控制，可选择性启用

---

## 5. 验证方案

### 5.1 Workspace 生成测试

```java
@Test
void testWorkspaceGeneration() throws IOException {
    var oafConfig = new OafConfig(
        "TestAgent", "test", "agent", "1.0.0", "test/agent",
        "A test agent", "@test", "MIT",
        List.of("test"), "You are a test agent.",
        List.of(new SkillConfig("code-review", "local", "1.0.0", true, "Code review", List.of())),
        List.of(new McpServerConfig("block", "filesystem", "1.0.0", "mcp-configs/filesystem", true)),
        List.of(), List.of("bash", "read"),
        new ModelConfig("openai", "gpt-4", ""),
        new RuntimeConfig(0.7, 4096, false),
        new MemoryConfig("editable", Map.of()),
        List.of(), List.of(), null, Map.of(), Map.of()
    );

    var workspace = workspaceInitializer.initialize(tempDir, oafConfig);

    assertThat(Files.exists(workspace.resolve("AGENTS.md"))).isTrue();
    assertThat(Files.exists(workspace.resolve("tools.json"))).isTrue();
    assertThat(Files.exists(workspace.resolve("skills/code-review/SKILL.md"))).isTrue();
}
```

### 5.2 HarnessAgent 集成测试

```java
@Test
void testHarnessAgentWithWorkspace() {
    var agent = harnessAgentBean;  // 注入的 HarnessAgent Bean
    var ctx = RuntimeContext.builder()
        .sessionId("test-session")
        .userId("test-user")
        .build();

    var result = agent.call(new UserMessage("user", "Hello"), ctx).block();
    assertThat(result.getTextContent()).isNotEmpty();
}
```

### 5.3 记忆管理验证

```java
@Test
void testMemoryPersistence() {
    // 第一轮对话
    agent.call(new UserMessage("user", "My name is Alice"), ctx).block();

    // 验证 MEMORY.md 被创建
    var memoryMd = workspace.resolve("MEMORY.md");
    assertThat(Files.exists(memoryMd)).isTrue();

    // 第二轮对话，验证记忆被注入
    var result = agent.call(new UserMessage("user", "What is my name?"), ctx).block();
    assertThat(result.getTextContent()).contains("Alice");
}
```

---

## 6. 验证用例

### 6.1 测试 LLM 配置

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

### 6.2 验证用例

#### TC-OAF-01: AGENTS.md 解析完整性

```java
@Test
void testOafConfigParsing() throws IOException {
    var yaml = """
        ---
        name: TestAgent
        vendorKey: test-vendor
        agentKey: test-agent
        version: 1.0.0
        description: A test agent
        author: "@test"
        license: MIT
        tags: [test, demo]
        tools: [bash, read]
        model:
          provider: openai
          name: LongCat-2.0
        config:
          temperature: 0.2
          max_tokens: 50
        memory:
          type: editable
        packs:
          - vendor: langchain
            pack: python-dev-tools
            version: 1.0.0
        weblets:
          - vendor: stripe
            weblet: payment-api
            version: 2.0.0
            launch: onDemand
        orchestration:
          entrypoint: main
          fallback: error-handler
        ---
        # Test Agent
        You are a test agent.
        """;
    Files.writeString(configDir.resolve("AGENTS.md"), yaml);
    var config = loader.load();

    assertThat(config.name()).isEqualTo("TestAgent");
    assertThat(config.packs()).hasSize(1);
    assertThat(config.weblets()).hasSize(1);
    assertThat(config.orchestration()).isNotNull();
    assertThat(config.orchestration().entrypoint()).isEqualTo("main");
}
```

#### TC-OAF-02: Workspace 目录生成

```java
@Test
void testWorkspaceGeneration() throws IOException {
    // 准备 skills 目录
    var skillDir = configDir.resolve("skills").resolve("code-review");
    Files.createDirectories(skillDir);
    Files.writeString(skillDir.resolve("SKILL.md"), """
        ---
        name: code-review
        description: Code review skill
        ---
        # Code Reviewer
        """);

    var workspace = workspaceInitializer.initialize(configDir, oafConfig);

    assertThat(Files.exists(workspace.resolve("AGENTS.md"))).isTrue();
    assertThat(Files.exists(workspace.resolve("tools.json"))).isTrue();
    assertThat(Files.exists(workspace.resolve("skills/code-review/SKILL.md"))).isTrue();
}
```

#### TC-OAF-03: Workspace skills/ 自动加载

```java
@Test
void testWorkspaceSkillsAutoLoaded() {
    var ctx = RuntimeContext.builder()
        .userId("test-user").sessionId("s1").build();
    var result = agent.call(List.of(new UserMessage("user", "请只回复 welcome")), ctx).block();

    // 验证 agent 能正常调用（skills 加载不影响基本功能）
    assertThat(result.getTextContent()).containsIgnoringCase("welcome");
}
```

#### TC-OAF-04: subagents/ 文件生成

```java
@Test
void testSubagentsGeneration() throws IOException {
    var oafConfigWithSubagents = new OafConfig(
        "TestAgent", "test", "agent", "1.0.0", "test/agent",
        "A test agent", "@test", "MIT",
        List.of(), "You are a test agent.",
        List.of(), List.of(), List.of(),
        List.of(new SubAgentConfig("openai", "researcher", "1.0.0", "researcher",
            List.of("research"), false, "")),
        List.of(), new ModelConfig("openai", "LongCat-2.0", ""),
        new RuntimeConfig(0.2, 50, false),
        new MemoryConfig("editable", Map.of()),
        List.of(), List.of(), null, Map.of(), Map.of()
    );

    var workspace = workspaceInitializer.initialize(configDir, oafConfigWithSubagents);
    assertThat(Files.exists(workspace.resolve("subagents/researcher.md"))).isTrue();
}
```

#### TC-OAF-05: tools.json 格式正确

```java
@Test
void testToolsJsonFormat() throws IOException {
    var workspace = workspaceInitializer.initialize(configDir, oafConfig);
    var toolsJson = workspace.resolve("tools.json");
    var content = Files.readString(toolsJson);
    var mapper = new ObjectMapper();
    var node = mapper.readTree(content);

    assertThat(node.has("mcpServers")).isTrue();
}
```

#### TC-OAF-06: LLM 调用验证

```bash
curl -s "https://api.longcat.chat/openai/v1/chat/completions" \
  -H "Authorization: Bearer ${LLM_API_KEY}" \
  -H "Content-Type: application/json" \
  -d '{"model":"LongCat-2.0","messages":[{"role":"user","content":"请只回复 welcome"}],"max_tokens":50,"temperature":0.2}'
```
