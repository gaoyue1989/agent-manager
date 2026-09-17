# AgentScope Harness 配置化分析文档

> **目的**：梳理 agent-framework 中 AgentScope Harness 相关的固定变量，识别可配置化项，形成变更方案。
> **状态**：设计文档（仅分析，不做代码变更）
> **日期**：2026-09-15

---

## 1. 现状概览

### 1.1 配置分层架构

当前 agent-framework 的配置分为三层：

| 层级 | 来源 | 作用域 | 机制 |
|------|------|--------|------|
| **平台级** | 环境变量 / `application.yml` | 全局（所有 agent 实例共享） | `@ConfigurationProperties` + `@Value` |
| **OAF 包级** | `AGENTS.md` frontmatter `config` 节 | 单个 OAF 包 | `OafConfigLoader` 解析 → `OafConfig.RuntimeConfig` |
| **硬编码** | Java 源码常量 / 构造参数 | 全局 | `static final` / 构造器参数 |

### 1.2 已配置化项（运行时可通过环境变量调整）

| 类别 | 环境变量 | 默认值 | 绑定位置 |
|------|---------|--------|---------|
| LLM | `LLM_API_KEY`, `LLM_MODEL_ID`, `LLM_BASE_URL`, `LLM_PROVIDER` | - | `AgentManagerProperties.LLMConfig` |
| LLM | `LLM_TEMPERATURE`, `LLM_MAX_TOKENS`, `LLM_TIMEOUT` | 0.7, 4096, 120 | `AgentManagerProperties.LLMConfig` |
| LLM | `LLM_CONTEXT_LENGTH` | 0（≤0 不传给模型） | `AgentManagerProperties.LLMConfig`；>0 时传入 `OpenAIChatModel.contextWindowSize()`（见 AgentScopeConfig.buildChatModel） |
| 服务 | `SERVER_PORT`, `SERVER_HOST` | 8100, 0.0.0.0 | `AgentManagerProperties.ServerConfig` |
| 数据库 | `CHECKPOINT_JDBC_URL`, `CHECKPOINT_USERNAME`, `CHECKPOINT_PASSWORD`, `CHECKPOINT_DB_NAME` | - | `AgentManagerProperties.CheckpointConfig` |
| 沙箱 | `SANDBOX_ENABLED`, `SANDBOX_IMAGE`, `SANDBOX_TIMEOUT_MINUTES` 等 | - | `SandboxConfig` |
| 文件 | `FILE_UPLOAD_ENABLED`, `FILE_UPLOAD_MAX_MB`, `FILE_STORAGE_TYPE` 等 | - | `AgentManagerProperties.FileConfig` |
| 清理 | `AGENT_CLEANUP_CONFIRM_TTL_MINUTES` 等 | 30, 60, 20, 30, 7 | `AgentManagerProperties.CleanupConfig` |
| 追踪 | `OTEL_TRACES_EXPORTER`, `OTEL_EXPORTER_OTLP_ENDPOINT` 等 | - | `OtelConfig` |

### 1.3 ⚠️ 关键发现：LLM 参数未生效

**`LLM_TEMPERATURE` 和 `LLM_MAX_TOKENS` 虽然在 `application.yml` 中绑定到了 `AgentManagerProperties.LLMConfig`，但在 `AgentScopeConfig.harnessAgent()` 构造模型时从未传递给 `OpenAIChatModel.builder()` 或 `HarnessAgent.builder()`。**

同样，OAF 包 frontmatter 中的 `config.temperature` 和 `config.max_tokens` 也仅写入了生成的 AGENTS.md 文档（`WorkspaceInitializer`），未传递给模型构建器。

**影响**：所有 LLM 调用使用 OpenAI SDK 内部默认值（temperature=1.0，maxTokens 无限制），而非用户配置的值。

**证据链**：

```
application.yml 第 22-23 行：
  temperature: ${LLM_TEMPERATURE:0.7}
  max-tokens: ${LLM_MAX_TOKENS:4096}
      ↓ 绑定到
AgentManagerProperties.LLMConfig.temperature / maxTokens
      ↓ 但仅在 DebugApiController.oafConfig() 中展示
AgentScopeConfig.harnessAgent() 第 249-263 行：
  OpenAIChatModel.builder()
    .apiKey(llm.apiKey())       // ✓ 使用
    .modelName(llm.modelId())   // ✓ 使用
    .baseUrl(llm.baseUrl())     // ✓ 使用
    .httpTransport(...)         // ✓ 使用
    // ✗ 未调用 .generateOptions(GenerateOptions.builder()
    //       .temperature(...).maxTokens(...).build())
```

---

## 2. 未配置化的硬编码项清单

### 2.1 Harness Agent 构建参数（`AgentScopeConfig.java`）

| 参数 | 当前硬编码值 | 代码位置 | 说明 | 建议优先级 |
|------|-------------|---------|------|-----------|
| `maxIters` | `20` | L298 | ReAct 推理最大轮次（SDK 默认 10） | **P0** — 不同任务复杂度差异大 |
| HTTP `connectTimeout` | `30s` | L255, L258 | LLM API 连接超时 | P1 |
| HTTP `readTimeout` | `180s` | L259 | LLM API 读超时（长推理场景需更长） | **P0** — 长推理链易超时 |
| HTTP `writeTimeout` | `30s` | L260 | LLM API 写超时 | P1 |

### 2.2 Memory 配置（`AgentScopeConfig.java` L346-351）

| 参数 | 当前硬编码值 | 说明 | 建议优先级 |
|------|-------------|------|-----------|
| `flushTrigger` | `throttled(10min)` | 记忆刷写节流间隔 | P2 |
| `consolidationMaxTokens` | `8000` | 记忆整合最大 token 数 | P2 |
| `consolidationMinGap` | `1h` | 记忆整合最小间隔 | P2 |

### 2.3 Compaction 配置（`AgentScopeConfig.java` L353-359）

| 参数 | 当前硬编码值 | 说明 | 建议优先级 |
|------|-------------|------|-----------|
| `triggerMessages` | `30` | 触发压缩的消息数阈值 | **P0** — 对话长度直接影响上下文窗口 |
| `keepMessages` | `10` | 压缩后保留的消息数 | P1 |
| `flushBeforeCompact` | `true` | 压缩前先刷写记忆 | P2（保持默认即可） |
| `offloadBeforeCompact` | `true` | 压缩前先卸载大工具结果 | P2（保持默认即可） |

### 2.4 HikariCP 连接池（`AgentScopeConfig.java` L156-161）

| 参数 | 当前硬编码值 | 说明 | 建议优先级 |
|------|-------------|------|-----------|
| `maximumPoolSize` | `10` | 最大连接数 | P1 |
| `minimumIdle` | `2` | 最小空闲连接 | P2 |
| `connectionTimeout` | `30000ms` | 连接超时 | P2 |
| `idleTimeout` | `600000ms` | 空闲超时 | P2 |
| `maxLifetime` | `1800000ms` | 连接最大生命周期 | P2 |

### 2.5 服务层常量

| 类 | 参数 | 当前硬编码值 | 代码位置 | 说明 | 建议优先级 |
|----|------|-------------|---------|------|-----------|
| `ToolAuditStore` | `FLUSH_INTERVAL_MS` | `100` | L32 | 审计日志刷写间隔 | P2 |
| `ToolAuditStore` | `BATCH_SIZE` | `50` | L34 | 审计日志批大小 | P2 |
| `TurnLeaseStore` | `POLL_INTERVAL_MS` | `500` | L27 | 租约轮询间隔 | P2 |
| `LLMLogger` | `maxCallsPerThread` | `50` | L18 | Debug 页 LLM 日志保留条数 | P2 |
| `SessionManager` | `DEFAULT_TTL` | `30 days` | L21 | 内存会话 TTL | P2 |
| `InMemoryLogAppender` | `MAX_ENTRIES` | `500` | L18 | 内存日志环形缓冲大小 | P2 |
| `McpResourceProxy` | `MAX_RESOURCE_BYTES` | `1MB` | L34 | MCP 资源读取大小上限 | P2 |
| `SessionCleanupService` | cron 表达式 | `0 0 3 * * ?` | L58 | 每日凌晨 3 点清理 | P2 |

### 2.6 A2A Server 配置（`A2AServerConfig.java` L31）

| 参数 | 当前硬编码值 | 说明 | 建议优先级 |
|------|-------------|------|-----------|
| Agent Card URL | `"http://localhost:8100"` | A2A 卡片注册地址 | **P0** — 多实例/非标准端口场景会注册错误地址 |

---

## 3. 配置化方案设计

### 3.1 配置层级与优先级

```
OAF 包 frontmatter (config.harness 节)
    ↓ 覆盖
环境变量 / application.yml (agent.harness.* 节)
    ↓ 覆盖
代码默认值 (当前硬编码值)
```

**原则**：
- **OAF 包级**：允许包作者声明包级别的参数（如 maxIters、compaction 触发阈值），但仅限"行为调优"类参数，不涉及安全/基础设施
- **平台级**：允许运维通过环境变量设置全局默认值
- **代码默认值**：保持当前硬编码值作为最终兜底

### 3.2 新增配置属性结构

在 `AgentManagerProperties` 中新增 `harness` 子 record：

```java
@ConfigurationProperties(prefix = "agent")
public record AgentManagerProperties(
    LLMConfig llm,
    ServerConfig server,
    CheckpointConfig checkpoint,
    @DefaultValue("/config") String configDir,
    @DefaultValue("") String workspaceDir,
    CleanupConfig cleanup,
    FileConfig file,
    HarnessConfig harness          // ← 新增
) {

    // ... 现有 record 不变 ...

    /**
     * Harness 运行时配置：ReAct 推理、Memory、Compaction、HTTP 超时、连接池。
     * 环境变量前缀：AGENT_*（如 AGENT_REACT_MAX_ITERS）
     */
    public record HarnessConfig(
        // ReAct 推理
        /** ReAct 推理最大轮次（SDK 默认 10，长流程需放宽） */
        @DefaultValue("20") int maxIters,
        // HTTP 超时（秒）
        /** LLM API 连接超时 */
        @DefaultValue("30") int httpConnectTimeoutSeconds,
        /** LLM API 读超时（长推理场景需更长） */
        @DefaultValue("180") int httpReadTimeoutSeconds,
        /** LLM API 写超时 */
        @DefaultValue("30") int httpWriteTimeoutSeconds,
        // Memory
        /** 记忆刷写节流间隔（分钟） */
        @DefaultValue("10") int memoryFlushThrottleMinutes,
        /** 记忆整合最大 token 数 */
        @DefaultValue("8000") int memoryConsolidationMaxTokens,
        /** 记忆整合最小间隔（分钟） */
        @DefaultValue("60") int memoryConsolidationMinGapMinutes,
        // Compaction
        /** 触发压缩的消息数阈值 */
        @DefaultValue("30") int compactionTriggerMessages,
        /** 压缩后保留的消息数 */
        @DefaultValue("10") int compactionKeepMessages,
        /** 压缩前先刷写记忆 */
        @DefaultValue("true") boolean compactionFlushBeforeCompact,
        /** 压缩前先卸载大工具结果 */
        @DefaultValue("true") boolean compactionOffloadBeforeCompact,
        // HikariCP
        /** DB 连接池最大连接数 */
        @DefaultValue("10") int dbPoolMaxSize,
        /** DB 连接池最小空闲连接 */
        @DefaultValue("2") int dbPoolMinIdle,
        /** DB 连接超时（毫秒） */
        @DefaultValue("30000") long dbPoolConnectionTimeoutMs,
        /** DB 空闲超时（毫秒） */
        @DefaultValue("600000") long dbPoolIdleTimeoutMs,
        /** DB 连接最大生命周期（毫秒） */
        @DefaultValue("1800000") long dbPoolMaxLifetimeMs
    ) {}
}
```

### 3.3 对应 application.yml 变更

```yaml
agent:
  # ... 现有配置不变 ...

  harness:
    # ReAct 推理
    max-iters: ${AGENT_REACT_MAX_ITERS:20}
    # HTTP 超时（秒）
    http-connect-timeout-seconds: ${AGENT_HTTP_CONNECT_TIMEOUT_SECONDS:30}
    http-read-timeout-seconds: ${AGENT_HTTP_READ_TIMEOUT_SECONDS:180}
    http-write-timeout-seconds: ${AGENT_HTTP_WRITE_TIMEOUT_SECONDS:30}
    # Memory
    memory-flush-throttle-minutes: ${AGENT_MEMORY_FLUSH_THROTTLE_MINUTES:10}
    memory-consolidation-max-tokens: ${AGENT_MEMORY_CONSOLIDATION_MAX_TOKENS:8000}
    memory-consolidation-min-gap-minutes: ${AGENT_MEMORY_CONSOLIDATION_MIN_GAP_MINUTES:60}
    # Compaction
    compaction-trigger-messages: ${AGENT_COMPACTION_TRIGGER_MESSAGES:30}
    compaction-keep-messages: ${AGENT_COMPACTION_KEEP_MESSAGES:10}
    compaction-flush-before-compact: ${AGENT_COMPACTION_FLUSH_BEFORE_COMPACT:true}
    compaction-offload-before-compact: ${AGENT_COMPACTION_OFFLOAD_BEFORE_COMPACT:true}
    # HikariCP
    db-pool-max-size: ${AGENT_DB_POOL_MAX_SIZE:10}
    db-pool-min-idle: ${AGENT_DB_POOL_MIN_IDLE:2}
    db-pool-connection-timeout-ms: ${AGENT_DB_POOL_CONNECTION_TIMEOUT_MS:30000}
    db-pool-idle-timeout-ms: ${AGENT_DB_POOL_IDLE_TIMEOUT_MS:600000}
    db-pool-max-lifetime-ms: ${AGENT_DB_POOL_MAX_LIFETIME_MS:1800000}
```

### 3.4 OAF 包 frontmatter 扩展（Phase 2，可选）

在 `AGENTS.md` frontmatter 的 `config` 节中新增可选字段：

```yaml
config:
  temperature: 0.7
  max_tokens: 4096
  require_confirmation: false
  permission:
    mode: default
  # ↓ 新增（Phase 2）
  harness:
    max_iters: 20
    compaction_trigger_messages: 30
    compaction_keep_messages: 10
```

**OAF 包级覆盖规则**：
- 仅允许覆盖 `harness` 下的**行为调优**参数（maxIters、compaction、memory 相关）
- 不允许覆盖基础设施参数（HTTP 超时、DB 连接池）
- OAF 包级值 > 平台环境变量值 > 代码默认值

### 3.5 `AgentScopeConfig.harnessAgent()` 变更要点

#### 3.5.1 修复 temperature/maxTokens 传递（Bug 修复）

```java
// ===== 当前代码（Bug）=====
var model = OpenAIChatModel.builder()
    .apiKey(llm.apiKey())
    .modelName(llm.modelId())
    .baseUrl(llm.baseUrl())
    .httpTransport(...)
    .build();
// ✗ 未调用 .generateOptions()

// ===== 修复后 =====
var model = OpenAIChatModel.builder()
    .apiKey(llm.apiKey())
    .modelName(llm.modelId())
    .baseUrl(llm.baseUrl())
    .generateOptions(GenerateOptions.builder()
        .temperature(oafConfig.runtimeConfig().temperature())
        .maxTokens(oafConfig.runtimeConfig().maxTokens())
        .build())
    .httpTransport(...)
    .build();
```

#### 3.5.2 HTTP 超时参数化

```java
// ===== 当前代码 =====
.connectTimeout(java.time.Duration.ofSeconds(30))     // 硬编码
.readTimeout(java.time.Duration.ofSeconds(180))        // 硬编码
.writeTimeout(java.time.Duration.ofSeconds(30))        // 硬编码

// ===== 变更后 =====
var harness = props.harness();
.connectTimeout(java.time.Duration.ofSeconds(harness.httpConnectTimeoutSeconds()))
.readTimeout(java.time.Duration.ofSeconds(harness.httpReadTimeoutSeconds()))
.writeTimeout(java.time.Duration.ofSeconds(harness.httpWriteTimeoutSeconds()))
```

#### 3.5.3 ReAct 轮次参数化

```java
// ===== 当前代码 =====
.maxIters(20)   // 硬编码

// ===== 变更后 =====
.maxIters(harness.maxIters())
```

#### 3.5.4 Memory 配置参数化

```java
// ===== 当前代码 =====
.memory(MemoryConfig.builder()
    .flushTrigger(MemoryConfig.FlushTrigger.throttled(Duration.ofMinutes(10)))   // 硬编码
    .consolidationMaxTokens(8_000)                                               // 硬编码
    .consolidationMinGap(Duration.ofHours(1))                                    // 硬编码
    .model(memoryModel)
    .build())

// ===== 变更后 =====
.memory(MemoryConfig.builder()
    .flushTrigger(MemoryConfig.FlushTrigger.throttled(
        Duration.ofMinutes(harness.memoryFlushThrottleMinutes())))
    .consolidationMaxTokens(harness.memoryConsolidationMaxTokens())
    .consolidationMinGap(Duration.ofMinutes(harness.memoryConsolidationMinGapMinutes()))
    .model(memoryModel)
    .build())
```

#### 3.5.5 Compaction 配置参数化

```java
// ===== 当前代码 =====
.compaction(CompactionConfig.builder()
    .triggerMessages(30)                  // 硬编码
    .keepMessages(10)                     // 硬编码
    .flushBeforeCompact(true)             // 硬编码
    .offloadBeforeCompact(true)           // 硬编码
    .model(compactionModel)
    .build())

// ===== 变更后 =====
.compaction(CompactionConfig.builder()
    .triggerMessages(harness.compactionTriggerMessages())
    .keepMessages(harness.compactionKeepMessages())
    .flushBeforeCompact(harness.compactionFlushBeforeCompact())
    .offloadBeforeCompact(harness.compactionOffloadBeforeCompact())
    .model(compactionModel)
    .build())
```

### 3.6 HikariCP 变更要点

```java
// ===== 当前代码（AgentScopeConfig.dataSource()） =====
ds.setMaximumPoolSize(10);        // 硬编码
ds.setMinimumIdle(2);             // 硬编码
ds.setConnectionTimeout(30000);   // 硬编码
ds.setIdleTimeout(600000);        // 硬编码
ds.setMaxLifetime(1800000);       // 硬编码

// ===== 变更后 =====
var harness = props.harness();
ds.setMaximumPoolSize(harness.dbPoolMaxSize());
ds.setMinimumIdle(harness.dbPoolMinIdle());
ds.setConnectionTimeout(harness.dbPoolConnectionTimeoutMs());
ds.setIdleTimeout(harness.dbPoolIdleTimeoutMs());
ds.setMaxLifetime(harness.dbPoolMaxLifetimeMs());
```

### 3.7 A2A Server URL 修复

```java
// ===== 当前代码（A2AServerConfig.java L31） =====
.url("http://localhost:8100")    // 硬编码

// ===== 变更后 =====
// 需要注入 AgentManagerProperties，host 为 0.0.0.0 时替换为 127.0.0.1
var host = "0.0.0.0".equals(props.server().host()) ? "127.0.0.1" : props.server().host();
.url(String.format("http://%s:%d", host, props.server().port()))
```

---

## 4. 不建议配置化的项

以下参数保持硬编码，理由：

| 参数 | 硬编码值 | 不配置化理由 |
|------|---------|-------------|
| `ToolAuditStore.FLUSH_INTERVAL_MS` | 100ms | 内部实现细节，调整无实际收益 |
| `ToolAuditStore.BATCH_SIZE` | 50 | 同上 |
| `TurnLeaseStore.POLL_INTERVAL_MS` | 500ms | 锁轮询间隔，调优空间极小 |
| `LLMLogger.maxCallsPerThread` | 50 | Debug 页面专用，非生产关键路径 |
| `InMemoryLogAppender.MAX_ENTRIES` | 500 | Debug 页面专用 |
| `McpResourceProxy.MAX_RESOURCE_BYTES` | 1MB | 安全上限，不应放宽 |
| `SessionCleanupService` cron | `0 0 3 * * ?` | 清理时间点无需调整 |
| `enablePlanMode()` | true | 功能开关，当前固定启用 |
| `enableSkillManageTool(true)` | true | 功能开关，当前固定启用 |
| `toolResultEviction` | `defaults()` | SDK 默认值合理 |

---

## 5. 实施计划

### Phase 1：Bug 修复 + P0/P1 参数配置化

| 步骤 | 变更文件 | 内容 |
|------|---------|------|
| 1 | `AgentManagerProperties.java` | 新增 `HarnessConfig` record（17 个参数） |
| 2 | `application.yml` | 新增 `agent.harness.*` 配置节（17 个环境变量绑定） |
| 3 | `AgentScopeConfig.java` | **Bug 修复**：`OpenAIChatModel.builder()` 添加 `.generateOptions()` 传递 temperature/maxTokens |
| 4 | `AgentScopeConfig.java` | 将 `maxIters`、HTTP 超时改为从 `harness` 配置读取 |
| 5 | `AgentScopeConfig.java` | Memory、Compaction 参数改为从 `harness` 配置读取 |
| 6 | `AgentScopeConfig.java` | HikariCP 参数改为从 `harness` 配置读取 |
| 7 | `A2AServerConfig.java` | 注入 `AgentManagerProperties`，修复 Agent Card URL |
| 8 | `.env.example` | 新增 `AGENT_*` 环境变量说明 |

### Phase 2：OAF 包级覆盖（可选）

| 步骤 | 变更文件 | 内容 |
|------|---------|------|
| 1 | `OafConfig.java` | `RuntimeConfig` 新增 `harness` 子 record |
| 2 | `OafConfigLoader.java` | 解析 frontmatter `config.harness` 节 |
| 3 | `AgentScopeConfig.java` | OAF 包级 harness 参数覆盖平台级默认值 |
| 4 | `WorkspaceInitializer.java` | 生成的 AGENTS.md 包含 harness 配置文档 |

---

## 6. 风险评估

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| `generateOptions` 修复后 temperature/maxTokens 生效，现有行为变化 | 中 | 默认值与当前 OAF frontmatter 默认值对齐（0.7/4096），需回归验证 |
| OAF 包级覆盖引入配置冲突 | 低 | 优先级链清晰：OAF > 环境变量 > 代码默认 |
| HikariCP 参数变更影响连接稳定性 | 低 | 默认值不变，仅暴露可调性 |

---

## 7. 总结

| 类别 | 可配置项数 | P0（必须） | P1（建议） | P2（可选） |
|------|-----------|-----------|-----------|-----------|
| Harness Agent 构建 | 4 | 2 | 2 | 0 |
| Memory | 3 | 0 | 0 | 3 |
| Compaction | 4 | 1 | 1 | 2 |
| HikariCP | 5 | 0 | 1 | 4 |
| 服务层常量 | 8 | 0 | 0 | 8 |
| A2A Server | 1 | 1 | 0 | 0 |
| **合计** | **25** | **4** | **4** | **17** |

**核心收益**：
1. 修复 temperature/maxTokens 未生效的 bug（影响所有 LLM 调用的生成行为）
2. ReAct 轮次、HTTP 超时、Compaction 阈值等关键行为参数可运行时调整
3. 为后续 OAF 包级个性化配置打下基础
