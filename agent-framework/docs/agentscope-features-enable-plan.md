# AgentScope 2.0 功能启用方案

## 1. 概述

当前 agent-framework 仅使用 AgentScope 底层 `ReActAgent` + `AgentStateStore`，未使用 Harness 层功能。本文档描述如何启用以下 5 个核心功能：

| 功能 | 说明 | 当前状态 |
|------|------|---------|
| **技能（Skill）** | Workspace `skills/` + `skillRepository()` | ❌ 自定义 SkillManager |
| **记忆管理** | `MEMORY.md` + `memory/` + MemoryFlushMiddleware | ❌ 未启用 |
| **上下文压缩** | CompactionConfig + CompactionMiddleware | ❌ 未启用 |
| **Plan Mode** | `plans/PLAN.md` + 只读规划态 | ❌ 未启用 |
| **Channel** | `agent.channel()` + Gateway + SSE | ❌ 自定义 StreamController |

**前置条件**: 需先完成 [MySQL 会话持久化方案](mysql-session-persistence-plan.md) 中的 `MysqlDistributedStore` 升级。

---

## 2. 技能（Skill）替换自定义 SkillManager

### 2.1 当前实现

自定义 `SkillManager` 读取 `skills/{name}/SKILL.md`，手动注入到 system prompt：

```java
// SkillManager.java - 当前实现
public List<SkillInfo> loadAll(List<SkillConfig> skillConfigs) {
    for (var sc : skillConfigs) {
        var skillDir = skillsDir.resolve(sc.name());
        var metadata = loadMetadata(sc, skillDir);
        loaded.add(new SkillInfo(sc.name(), skillDir.toString(), metadata, sc));
    }
}
```

### 2.2 AgentScope 2.0 Skill 机制

AgentScope 2.0 的 Skill 系统：

- **四层优先级**: `projectGlobalSkillsDir` → `skillRepository` → `workspace/skills/` → `<userId>/skills/`
- **自动注入**: `DynamicSkillMiddleware` 每轮推理前将 `<available_skills>` 块注入 system prompt
- **按需加载**: agent 看到 name + description 后决定是否 `load_skill_through_path` 加载详情
- **自学习**: agent 可自动沉淀成功模式为 SKILL.md

### 2.3 改造方案

#### 删除文件

| 文件 | 说明 |
|------|------|
| `service/SkillManager.java` | 由 AgentScope Skill 系统替代 |

#### 修改文件

**`config/AgentScopeConfig.java`** — 删除 SkillManager Bean，改用 Workspace skills/：

```java
// 删除以下 Bean
// @Bean
// public SkillManager skillManager(OafConfig oafConfig, AgentManagerProperties props) { ... }
//
// @Bean
// public List<SkillManager.SkillInfo> loadedSkills(SkillManager skillManager, OafConfig oafConfig) { ... }
```

**`service/AgentRuntimeService.java`** — 删除 Skill 相关代码：

```java
// 删除字段
// private final List<SkillManager.SkillInfo> loadedSkills;

// 删除构造函数参数
// List<SkillManager.SkillInfo> loadedSkills,

// 删除 buildSystemPrompt() 中的 Skills 注入逻辑
// if (!loadedSkills.isEmpty()) { ... }
```

**`service/WorkspaceInitializer.java`** — 在 Workspace 中生成 skills/：

```java
// 已在 oaf-improvement-plan.md 中定义
private void copySkills(Path workspace, OafConfig oafConfig) throws IOException {
    var skillsDir = workspace.resolve("skills");
    Files.createDirectories(skillsDir);
    for (var skill : oafConfig.skills()) {
        if ("local".equals(skill.source())) {
            var sourceDir = configDir.resolve("skills").resolve(skill.name());
            var targetDir = skillsDir.resolve(skill.name());
            if (Files.exists(sourceDir) && !Files.exists(targetDir)) {
                copyDirectory(sourceDir, targetDir);
            }
        }
    }
}
```

**`config/AgentScopeConfig.java`** — HarnessAgent Builder 中启用技能自学习：

```java
var agent = HarnessAgent.builder()
    // ... 其他配置 ...
    .enableSkillManageTool(true)  // 启用技能自学习（propose_skill / skill_manage 工具）
    .build();
```

### 2.4 远程 Skill 仓库（可选）

```java
// Git 仓库
.skillRepository(new GitSkillRepository("https://github.com/your-org/team-skills.git"))

// MySQL 仓库
.skillRepository(MysqlSkillRepository.builder(dataSource)
    .databaseName("agentscope")
    .skillsTableName("skills")
    .build())

// 多个仓库（后注册的优先级更高）
.skillRepository(communityMarket)
.skillRepository(internalRegistry)
```

---

## 3. 记忆管理

### 3.1 AgentScope 2.0 记忆机制

两层结构：

```
workspace/
├── MEMORY.md                  ← 第二层：策划后的长期记忆，每轮注入 system prompt
└── memory/
    └── YYYY-MM-DD.md          ← 第一层：每天追加的事实流水账（未去重）
```

三处 LLM 调用：

| # | 操作 | 写入目标 | 触发时机 |
|---|------|---------|---------|
| 1 | **Flush** | `memory/YYYY-MM-DD.md` | 每次 call 结束 / 节流 |
| 2 | **Consolidation** | `MEMORY.md`（整体重写） | 后台节流任务（默认 30 分钟） |
| 3 | **Compaction summary** | 注入当前上下文 | 对话超长时 |

### 3.2 启用配置

```java
var agent = HarnessAgent.builder()
    // ... 其他配置 ...
    .memory(MemoryConfig.builder()
        // 记忆操作使用轻量模型（可选，省成本）
        .model("openai:gpt-4.1-mini")
        // Flush 触发策略
        .flushTrigger(MemoryConfig.FlushTrigger.throttled(Duration.ofMinutes(10)))
        // MEMORY.md token 上限
        .consolidationMaxTokens(8_000)
        // 后台合并间隔
        .consolidationMinGap(Duration.ofHours(1))
        // 日流水账保留天数
        .dailyFileRetentionDays(90)
        // 会话日志保留天数
        .sessionRetentionDays(180)
        .build())
    .build();
```

### 3.3 记忆工具

启用后 agent 自动获得：

| 工具 | 说明 |
|------|------|
| `memory_search query="..."` | 关键词搜索 MEMORY.md + memory/*.md |
| `memory_get path="..."` | 读取指定记忆文件 |
| `memory_save content="..."` | 主动保存记忆 |
| `session_search query="..."` | 搜索历史会话日志 |

### 3.4 自定义 Prompt（可选）

```java
.memory(MemoryConfig.builder()
    .flushPrompt(MemoryFlushManager.DEFAULT_FLUSH_PROMPT + """
        Additional rules:
        - 不要记录用户 PII 信息
        - 项目术语使用中文
        """)
    .build())
```

---

## 4. 上下文压缩

### 4.1 AgentScope 2.0 压缩机制

四种策略（正交，可任意组合）：

| 策略 | 解决的问题 | 触发时机 |
|------|-----------|---------|
| 对话摘要压缩 | 消息条数/token 过多 | 模型推理前 |
| 大工具结果卸载 | 单条工具结果过大 | 工具执行后 |
| 上下文溢出兜底 | 撞到 model context 上限 | call() 抛错时 |
| 预压缩参数截断 | 工具参数过大 | 摘要前轻量预处理 |

### 4.2 启用配置

```java
var agent = HarnessAgent.builder()
    // ... 其他配置 ...
    .compaction(CompactionConfig.builder()
        // 按消息条数触发
        .triggerMessages(30)
        // 按 token 估算触发
        .triggerTokens(80_000)
        // 压缩后保留最近 N 条原文
        .keepMessages(10)
        // 压缩前先 flush 到 memory/
        .flushBeforeCompact(true)
        // 压缩前先存原始日志
        .offloadBeforeCompact(true)
        // 压缩摘要使用轻量模型
        .model("openai:gpt-4.1-mini")
        // 预压缩参数截断
        .truncateArgs(CompactionConfig.TruncateArgsConfig.builder()
            .maxArgLength(2000)
            .truncationText("... [truncated] ...")
            .build())
        .build())
    // 大工具结果卸载
    .toolResultEviction(ToolResultEvictionConfig.defaults())
    .build();
```

### 4.3 上下文溢出自动恢复

配了 `.compaction(...)` 后自动生效：模型返回 `context_length_exceeded` 时，强制压缩 + 重试一次。

---

## 5. Plan Mode

### 5.1 AgentScope 2.0 Plan Mode 机制

- **只读规划态**: agent 先用 `plan_write` 写计划到 `workspace/plans/PLAN.md`
- **跨调用保留**: 计划文件持久化，下次 call() 自动注入
- **执行驱动**: 计划写完后自动进入执行阶段
- **与 `todo_write` 协作**: Plan Mode 中的任务通过 `todo_write` 跟踪进度

### 5.2 启用配置

```java
var agent = HarnessAgent.builder()
    // ... 其他配置 ...
    .enablePlanMode()  // 启用 Plan Mode
    .build();
```

### 5.3 Plan Mode 工作流

```
用户: "帮我重构 auth 模块"
  │
  ▼
Agent 进入 Plan Mode (只读)
  │  调用 plan_write 写计划:
  │  # Auth 模块重构计划
  │  ## 目标
  │  - 将 JWT 逻辑从 Controller 抽到 Service
  │  - 添加 refresh token 支持
  │  ## 步骤
  │  1. 分析现有代码结构
  │  2. 创建 AuthService
  │  3. 迁移 JWT 逻辑
  │  4. 添加测试
  │
  ▼
Agent 退出 Plan Mode，开始执行
  │  按计划逐步执行
  │  使用 todo_write 跟踪进度
  │
  ▼
执行完成
```

### 5.4 Plan Mode 状态持久化

Plan Mode 状态存储在 `AgentState.getPlanModeContext()` 中，通过 `AgentStateStore` 自动持久化：
- 是否在 plan 阶段
- 当前计划文件路径
- 跨节点恢复时自动恢复 plan 状态

---

## 6. Channel API 暴露

### 6.1 当前实现

自定义 `StreamController` 处理 SSE：

```java
// StreamController.java - 当前实现
@PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<Map<String, Object>> chatStream(@RequestBody Map<String, Object> body) {
    var message = body.getOrDefault("message", "").toString();
    var threadId = ...;
    return agentRuntime.invokeStream(message, threadId);
}
```

### 6.2 AgentScope 2.0 Channel 机制

Channel 提供：
- **会话管理**: 自动映射 userId → sessionId
- **并发控制**: 同一 session 的并发消息排队
- **流式事件**: `sendStream()` 返回 `Flux<AgentEvent>`
- **子 Agent 暴露**: `expose_to_user` 子 agent 可直接对话

### 6.3 改造方案

#### 删除文件

| 文件 | 说明 |
|------|------|
| `controller/StreamController.java` | 由 AgentScope Channel 替代 |

#### 新增文件

**`config/ChannelConfig.java`** — Channel 配置：

```java
package io.agentmanager.framework.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.agentmanager.framework.service.AgentRuntimeService;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.channel.ChatUiChannel;

@Configuration
public class ChannelConfig {

    @Bean
    public ChatUiChannel chatUiChannel(HarnessAgent agent) {
        return agent.channel(ChatUiChannel.create());
    }
}
```

#### 修改文件

**`controller/StreamController.java`** — 改用 Channel：

```java
package io.agentmanager.framework.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;

import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.harness.channel.ChatUiChannel;
import io.agentscope.harness.channel.SendOptions;
import reactor.core.publisher.Flux;

@RestController
public class StreamController {

    private final ChatUiChannel chatChannel;

    public StreamController(ChatUiChannel chatChannel) {
        this.chatChannel = chatChannel;
    }

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(
            @RequestParam String message,
            @RequestParam String userId,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String subagentId
    ) {
        // 子 Agent 直接对话
        if (subagentId != null) {
            return chatChannel.sendToSubagentStream(subagentId, message)
                .map(event -> toSSE(event));
        }

        // 主 Agent 对话
        SendOptions options = sessionId != null
            ? SendOptions.of(userId, sessionId)
            : SendOptions.userId(userId);

        return chatChannel.sendStream(options, message)
            .map(event -> toSSE(event));
    }

    private ServerSentEvent<String> toSSE(io.agentscope.core.event.AgentEvent event) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("type", event.getType().name());
        payload.put("id", event.getId());

        if (event instanceof TextBlockDeltaEvent delta) {
            payload.put("delta", delta.getDelta());
        } else if (event instanceof ToolCallStartEvent tc) {
            payload.put("toolName", tc.getToolCallName());
            payload.put("toolCallId", tc.getToolCallId());
        } else if (event instanceof ToolResultEndEvent tr) {
            payload.put("state", tr.getState().name());
        }

        try {
            var data = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(payload);
            return ServerSentEvent.<String>builder().data(data).build();
        } catch (Exception e) {
            return ServerSentEvent.<String>builder().data("{}").build();
        }
    }
}
```

### 6.4 API 端点变更

| 端点 | 当前 | 目标 |
|------|------|------|
| `POST /chat/stream` | 自定义 `StreamController` | 删除 |
| `GET /chat/stream` | 不存在 | 新增（Channel SSE） |

**新 API 参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `message` | String | ✓ | 用户消息 |
| `userId` | String | ✓ | 用户标识（自动创建独立 session） |
| `sessionId` | String | | 可选，指定 session（同一用户多个对话） |
| `subagentId` | String | | 可选，直接与子 Agent 对话 |

### 6.5 多 Agent 路由（可选）

```java
@Bean
public GatewayBootstrap gatewayBootstrap(
    HarnessAgent mainAgent,
    HarnessAgent supportAgent
) {
    return GatewayBootstrap.builder()
        .agent("main", mainAgent)
        .agent("support", supportAgent)
        .mainAgent("main")
        .build();
}
```

---

## 7. HarnessAgent Builder 完整配置

### 7.1 `config/AgentScopeConfig.java` 最终版本

```java
@Bean
public HarnessAgent harnessAgent(
    AgentManagerProperties props,
    DistributedStore distributedStore,
    OafConfig oafConfig,
    WorkspaceInitializer workspaceInitializer
) {
    var llm = props.llm();
    var workspacePath = workspaceInitializer.initialize(
        Path.of(props.configDir()), oafConfig);

    var model = OpenAIChatModel.builder()
        .apiKey(llm.apiKey())
        .modelName(llm.modelId())
        .baseUrl(llm.baseUrl())
        .build();

    return HarnessAgent.builder()
        .name(oafConfig.name())
        .sysPrompt(oafConfig.systemPrompt())
        .model(model)
        .workspace(workspacePath)
        // MySQL 分布式存储
        .distributedStore(distributedStore)
        .filesystem(new RemoteFilesystemSpec()
            .isolationScope(IsolationScope.USER))
        // 记忆管理
        .memory(MemoryConfig.builder()
            .model("openai:gpt-4.1-mini")
            .flushTrigger(MemoryConfig.FlushTrigger.throttled(Duration.ofMinutes(10)))
            .consolidationMaxTokens(8_000)
            .consolidationMinGap(Duration.ofHours(1))
            .build())
        // 上下文压缩
        .compaction(CompactionConfig.builder()
            .triggerMessages(30)
            .keepMessages(10)
            .flushBeforeCompact(true)
            .offloadBeforeCompact(true)
            .model("openai:gpt-4.1-mini")
            .truncateArgs(CompactionConfig.TruncateArgsConfig.builder()
                .maxArgLength(2000)
                .build())
            .build())
        // 大工具结果卸载
        .toolResultEviction(ToolResultEvictionConfig.defaults())
        // Plan Mode
        .enablePlanMode()
        // 技能自学习
        .enableSkillManageTool(true)
        .build();
}
```

### 7.2 配置总览

| 功能 | Builder 方法 | 效果 |
|------|-------------|------|
| 记忆管理 | `.memory(MemoryConfig...)` | MEMORY.md + memory/ 自动维护 |
| 上下文压缩 | `.compaction(CompactionConfig...)` | 对话超长自动摘要 |
| 工具结果卸载 | `.toolResultEviction(...)` | 大输出自动落盘 |
| Plan Mode | `.enablePlanMode()` | 只读规划态 |
| 技能自学习 | `.enableSkillManageTool(true)` | agent 自动沉淀技能 |
| 分布式存储 | `.distributedStore(store)` | MySQL 状态 + 文件持久化 |
| 文件系统 | `.filesystem(RemoteFilesystemSpec...)` | 多副本共享工作区 |

---

## 8. 新增/删除/修改文件清单

### 新增文件

| 文件 | 说明 |
|------|------|
| `config/ChannelConfig.java` | ChatUiChannel Bean |
| `service/WorkspaceInitializer.java` | OAF → Workspace 转换 |

### 删除文件

| 文件 | 说明 |
|------|------|
| `service/SkillManager.java` | 由 AgentScope Skill 系统替代 |
| `controller/StreamController.java` | 由 Channel 替代（或改造） |

### 修改文件

| 文件 | 变更 |
|------|------|
| `config/AgentScopeConfig.java` | 替换 ReActAgent → HarnessAgent，删除 SkillManager Bean |
| `service/AgentRuntimeService.java` | 删除 Skill 相关代码 |
| `controller/StreamController.java` | 改用 ChatUiChannel |
| `service/WorkspaceInitializer.java` | 新增 skills/ 复制逻辑 |

### 不变文件

| 文件 | 说明 |
|------|------|
| `config/OafConfigLoader.java` | OAF 解析逻辑不变 |
| `model/OafConfig.java` | 数据模型不变 |
| `controller/A2AController.java` | A2A 改进方案中处理 |
| `controller/InfoController.java` | 不变 |
| `controller/HealthController.java` | 不变 |

---

## 9. 验证清单

### 技能
- [ ] `workspace/skills/` 目录下的 SKILL.md 被自动加载
- [ ] system prompt 中出现 `<available_skills>` 块
- [ ] agent 可调用 `load_skill_through_path` 加载技能详情
- [ ] `enableSkillManageTool(true)` 后 agent 可调用 `propose_skill`

### 记忆
- [ ] `workspace/MEMORY.md` 被创建并注入 system prompt
- [ ] `workspace/memory/YYYY-MM-DD.md` 每天自动追加
- [ ] `memory_search` 工具可用
- [ ] 后台 consolidation 定期合并到 MEMORY.md

### 上下文压缩
- [ ] 对话超过 30 条消息时自动触发压缩
- [ ] 压缩后保留最近 10 条原文
- [ ] 压缩前先 flush 事实到 memory/
- [ ] 大工具结果自动卸载到文件

### Plan Mode
- [ ] agent 可调用 `plan_write` 写计划
- [ ] `workspace/plans/PLAN.md` 被创建
- [ ] 计划跨 call() 保留
- [ ] `todo_write` 任务跟踪正常

### Channel
- [ ] `GET /chat/stream?message=...&userId=...` 返回 SSE 流
- [ ] 同一 userId 的多次请求共享会话历史
- [ ] 不同 userId 完全隔离
- [ ] 子 Agent `sendToSubagentStream` 可用

---

## 10. 验证用例

### 10.1 测试 LLM 配置

```yaml
agent:
  llm:
    api-key: ${LLM_API_KEY}
    model-id: LongCat-2.0
    base-url: https://api.longcat.chat/openai/v1
    provider: openai
    temperature: 0.2
    max-tokens: 200
```

### 10.2 技能验证用例

#### TC-FEAT-01: Workspace skills/ 自动加载

```java
@Test
void testWorkspaceSkillsLoaded() {
    var ctx = RuntimeContext.builder()
        .userId("test-user").sessionId("s1").build();
    var result = agent.call(List.of(new UserMessage("user", "请只回复 welcome")), ctx).block();

    assertThat(result.getTextContent()).containsIgnoringCase("welcome");
}
```

#### TC-FEAT-02: 技能自学习工具可用

```java
@Test
void testSkillManageToolAvailable() {
    // enableSkillManageTool(true) 后 agent 应有 propose_skill 工具
    var ctx = RuntimeContext.builder()
        .userId("test-user").sessionId("s2").build();
    var result = agent.call(List.of(new UserMessage("user",
        "创建一个技能叫 hello-world，功能是回复 hello")), ctx).block();

    // agent 应该调用 propose_skill
    assertThat(result).isNotNull();
}
```

### 10.3 记忆管理验证用例

#### TC-FEAT-03: MEMORY.md 自动创建

```java
@Test
void testMemoryMdAutoCreated() {
    var ctx = RuntimeContext.builder()
        .userId("test-user").sessionId("s3").build();

    agent.call(List.of(new UserMessage("user", "记住：我是测试用户")), ctx).block();
    Thread.sleep(5000);

    var memoryMd = workspace.resolve("MEMORY.md");
    // MEMORY.md 应该存在（或在 MySQL agent_fs 中）
    assertThat(Files.exists(memoryMd) || hasInAgentFs("MEMORY.md")).isTrue();
}
```

#### TC-FEAT-04: memory_search 工具可用

```java
@Test
void testMemorySearchToolAvailable() {
    var ctx = RuntimeContext.builder()
        .userId("test-user").sessionId("s4").build();

    // 先保存一些记忆
    agent.call(List.of(new UserMessage("user", "记住：项目代号是 Alpha")), ctx).block();
    Thread.sleep(5000);

    // 搜索记忆
    var result = agent.call(List.of(new UserMessage("user", "搜索记忆中关于项目代号的内容")), ctx).block();
    assertThat(result.getTextContent()).containsIgnoringCase("Alpha");
}
```

### 10.4 上下文压缩验证用例

#### TC-FEAT-05: 长对话自动压缩

```java
@Test
void testLongConversationCompaction() {
    var ctx = RuntimeContext.builder()
        .userId("test-user").sessionId("s5").build();

    // 发送 35 条消息触发压缩（triggerMessages=30）
    for (int i = 0; i < 35; i++) {
        agent.call(List.of(new UserMessage("user", "消息 " + i + "：请简短回复")), ctx).block();
    }

    // 验证对话被压缩（context 不应包含所有 35 条消息）
    var state = agent.getAgentState("test-user", "s5");
    assertThat(state.getContext().size()).isLessThan(35); // 应该被压缩了
}
```

### 10.5 Plan Mode 验证用例

#### TC-FEAT-06: Plan Mode 启用

```java
@Test
void testPlanModeEnabled() {
    var ctx = RuntimeContext.builder()
        .userId("test-user").sessionId("s6").build();

    var result = agent.call(List.of(new UserMessage("user",
        "请用 plan_write 写一个计划：回复 welcome")), ctx).block();

    // agent 应该调用 plan_write
    assertThat(result).isNotNull();
}
```

### 10.6 Channel 验证用例

#### TC-FEAT-07: Channel SSE 流式返回

```java
@Test
void testChannelSseStream() {
    var events = chatChannel.sendStream(
        SendOptions.userId("test-user"), "请只回复 welcome")
        .collectList().block();

    assertThat(events).isNotEmpty();
    assertThat(events.stream().anyMatch(e ->
        e instanceof TextBlockDeltaEvent)).isTrue();
}
```

#### TC-FEAT-08: Channel 会话管理

```java
@Test
void testChannelSessionManagement() {
    // 同一 userId 的多次调用共享会话
    chatChannel.send(SendOptions.userId("test-user"), "记住：我喜欢红色").block();
    var result = chatChannel.send(SendOptions.userId("test-user"), "我喜欢什么颜色？").block();

    assertThat(result.getTextContent()).containsIgnoringCase("红色");
}
```

### 10.7 LLM 连通性验证

```bash
curl -s "https://api.longcat.chat/openai/v1/chat/completions" \
  -H "Authorization: Bearer ${LLM_API_KEY}" \
  -H "Content-Type: application/json" \
  -d '{"model":"LongCat-2.0","messages":[{"role":"user","content":"请只回复 welcome"}],"max_tokens":200,"temperature":0.2}'
```
