# Agent Framework — 测试文档

**版本:** v2.1.0 (Java)
**日期:** 2026-08-06
**复核日期:** 2026-09-26

> **现状核对（2026-09-26）**：§8 统计表的**基线是 2026-09-17 的实跑结果**（657 用例 / 80 文件），
> 之后新增了大量测试类，原表部分分类数字已明显偏低。本版已在 **JDK 21.0.12 + Maven 3.8.7 上实跑
> `mvn test`** 核对，下表数字已按实跑结果更新，并补登记 2026-09 新增的测试类。

---

## 1. 测试概览

Agent Framework (Java) 的测试基于 **Spring Boot Test** + **JUnit 5**。

| 层级 | 说明 |
|------|------|
| 单元测试 | Service/Config/Tool 级别, Mock 外部依赖 |
| 集成测试 | 全流程 Controller 测试 (MockMvc) |
| E2E 测试 | 真实 LLM + 工具调用 + MCP (手动验证) |

---

## 2. 测试 LLM 配置

所有 E2E 测试使用以下 LLM 配置（2026-08-20 更新为 mimo-v2.5，见 `.env.secrets`）：

```yaml
# application-test.yml
agent:
  llm:
    api-key: ${LLM_API_KEY}          # .env.secrets 配置
    model-id: ${LLM_MODEL}           # mimo-v2.5
    base-url: ${LLM_ENDPOINT}        # https://token-plan-cn.xiaomimimo.com/v1
    provider: openai
    temperature: 0.2
    max-tokens: 50
    timeout: 30
  checkpoint:
    jdbc-url: jdbc:mysql://127.0.0.1:3307/agent_manager_test
    username: agent_manager
    password: Agent@Manager2026
```

---

## 3. 运行测试

### 3.1 全部测试

```bash
cd agent-framework
mvn test
```

> 集成测试默认跳过：沙箱集成需 OpenSandbox Server 可达，S3FileStorageIT 需真实对象存储环境变量。

### 3.2 指定测试类

```bash
mvn test -Dtest=AgentFrameworkApplicationTests -Dspring.profiles.active=test
```

### 3.3 跳过测试

```bash
mvn clean package -DskipTests
```

---

## 4. 测试用例

### 4.1 AgentFrameworkApplicationTests

```java
// src/test/java/.../AgentFrameworkApplicationTests.java
// Spring Boot context 加载测试
@SpringBootTest
class AgentFrameworkApplicationTests {
    @Test
    void contextLoads() {
        // 验证 ApplicationContext 加载成功
    }
}
```

### 4.2 OafConfigLoaderTest (40 个用例)

覆盖字段解析：name、vendorKey、agentKey、version、slug、description、author、license、tags、skills、mcpServers、tools、systemPrompt、model、runtimeConfig、memory、deniedTools（空默认值、显式值）。

### 4.3 A2AControllerTest (10 个用例)

覆盖场景：
- `message/send` 正常返回
- `metadata.userId` 透传到 `AgentRuntimeService`
- `metadata.thread_id` / `metadata.contextId` / `taskId` 回退
- 缺少 params / 缺少 message 返回 -32602
- 未知 method 返回 -32601
- 缺少 method 返回 -32600

### 4.4 ChatStreamControllerTest (37 个用例)

- `POST /threads/chat` 单次流事件经 EventBus 输出 / 租约释放 / waiting 排队 / 空消息拒绝
- sessionId 省略时自动生成 UUID 并发 `session_created`；传了则不生成
- write_file → KV 同步（沙箱开关、路径规范化、userId 作 key、缺 path 跳过）
- 工具事件审计、MCP Apps ui 元数据序列化
- `file_ready` 契约用例：钉死 `download_url` 为 `/files/{id}` 相对路径形态——前端统一拼
  AGENT_BASE（历史回放与实时流两处入口），若服务端再改拼接头此用例即红（2026-09-17 新增，
  防 e2e 中曾出现的「前端入口 404」回归）
- MCP Apps ui 注入**接线**契约（2026-09-17 新增）：命中 ui 映射的工具，对话流里
  TOOL_CALL_START 必须带 `ui{resourceUri,server}`，无映射工具 payload 不得覆写——
  此前只测了序列化器本身、控制器从未查 registrar，运行时 MCP App 卡片永不挂载
  （approval-forms e2e 发现）

### 4.5 ToolControllerTest (7 个用例)

> **2026-09-26 实跑核对**：原表记 4 个，现为 7 个——issue #39（2026-09-26）新增 `sdkInternal` 段断言。

- `GET /skills` 返回 OAF skill 列表
- `GET /mcp` 返回 MCP 配置
- `GET /tools` 默认只返回 MCP 工具
- `GET /tools?includeInternal=true` 追加内置工具
- `sdkInternal` 段透出 SDK 内置工具实际注册集（issue #39）
- 三个计数字段（`totalCount` / `mcpCount` / `internalCount`）与 `sdkInternalCount` 的口径
- `deniedTools` 剔除后 internal 与 sdkInternal 两段同时反映

### 4.6 AgentRuntimeServiceTest (16 个用例)

覆盖场景：
- userId 透传 / 回退 vendorKey / 空值回退
- sessionId 自动生成 / slug `/` 替换
- invoke 异常返回 Error 响应
- invokeStream working 事件 + AGENT_END 触发 done
- userId 传播到 RuntimeContext
- 2 参 invoke 委托到 3 参
- tenantPrefix = slug

### 4.7 WorkspaceInitializerTest (7 个用例)

覆盖场景：
- workspace 结构创建（AGENTS.md + tools.json）
- AGENTS.md frontmatter 生成（name/model/temperature）
- 空 tools.json（无 deny 时为空对象）
- deny 列表生成
- **skills 不再本地复制**（/config/skills 由 L2 仓库动态加载，初始化不触碰）
- subagents 生成
- 幂等不覆盖已有文件

### 4.8 McpToolRegistrarTest (38 个用例)

覆盖场景：
- SSE / stdio / streamableHttp 三种传输构建
- auth 环境变量 token / 静态 token
- 缺失 config.yaml / 缺失 connection 段返回 null
- configDir 回退到 server 名目录
- `permissions.read_only: true` → `isReadOnlyConfigured()` 返回 true
- 无 permissions 段 → 返回 false
- `permissions.read_only: false` → 返回 false
- ActiveMCP.json `selectedTools` enabled 标志解析（含缺省默认 true）
- 无 ActiveMCP.json → `loadActiveMcpConfig()` 返回 null
- ActiveMCP.json 损坏 → 返回 null 不抛异常

### 4.9 BusinessToolsTest (5 个用例)

- `echo` 前缀 / 空值 / 空白保留
- `get_current_time` ISO 格式（含纳秒）、UTC、非法时区抛异常

### 4.10 MCP Apps 测试（阶段一/二，2026-08-19 新增）

| 测试类 | 用例数 | 覆盖点 |
|--------|--------|--------|
| UiContextStoreTest | 9 | upsert 覆盖写 / 查询 / 删除 / 会话 key 聚合、sessionId 格式校验 |
| UiContextControllerTest | 5 | 正常更新 / 缺 sessionId 400 / 缺 content+structured 400 / 非法 sessionId 400 |
| UiContextInjectionHookTest | 4 | 命中注入 / 无记录跳过 / 无 metadata key 跳过 / store 异常不阻断 |
| McpResourceProxyTest | 10 | ui:// 资源读取 / CSP 注入 / 列表 / 工具代发 / 403 needsConfirm / 异常透传 |
| ChatStreamControllerTest | 24 | 单次流触发 / sessionId 自动生成 / fileIds 注入 / write_file KV 同步 / 审计 / 丢租约即停写 / 准备段与租约启动失败的回滚 / file_ready 契约（download_url 相对路径）/ MCP Apps ui 注入接线契约 |
| SessionStreamControllerTest | 7 | subscribe 回放+done / status 四态 / ui 元数据序列化 |

### 4.11 Stateless Single-Stream 测试（stateless-single-stream 新增）

| 测试类 | 用例数 | 覆盖点 |
|--------|--------|--------|
| TurnLeaseStoreTest | 5 | renew 三态（更新到 1 行 → HELD / 0 行 → LOST / SQLException → ERROR） / ttl() 暴露给 guard 做「到必须停手」的判据 |
| TurnLeaseGuardTest | 11 | 瞬时故障继续重试而不停续租 / 故障持续超过判据才判丢锁 / 续租线程卡死时写入侧仍能按时间判丢锁 / 在接管可能之前就停手（判据是 ttl−interval 而非 ttl） / 主动 release 不算丢锁 / 丢锁通知闸门只开一次 / 已确认丢锁不被后续成功续租翻回 / 丢锁后不再碰租约 |
| ConfirmControllerTest | 6 | 同步确认 / confirm-stream / 404 与 409 语义 / 丢租约即停写 |

> **修正（2026-09-16）：** 上表原先还列了 `ConfirmContextStoreTest`（10 用例）与
> `ToolAuditStoreTest`（6 用例）——**这两个类都从未存在过**，用例数也是凭空写的；
> `TurnLeaseStoreTest` 原写 8 个用例，实际为 5 个（该类的其余行为由 `TurnLeaseGuardTest`
> 从消费侧覆盖）。`ConfirmContextStore` 与 `ToolAuditStore` 目前**没有独立测试类**，
> 只经由 `ConfirmControllerTest` / `AgentRuntimeServiceHitlTest` 间接覆盖。补类时请同时
> 更新本表——本表此前正是因为「先写文档、后没建类」而失真。

### 4.12 文件上传下载测试（file-upload-download，2026-09-07 新增）

| 测试类 | 用例数 | 覆盖点 |
|--------|--------|--------|
| FileControllerTest | 15 | 上传校验链（MIME 白名单/大小/文件名 sanitize/pending 上限）/ 下载 inline / 存储缺失 502 / external 代理下载（白名单放行/拒绝/上游 502） |
| FileToolsTest | 17 | present_file 路径越界拦截 / 沙箱与非沙箱模式 / 大小上限 / present_url 外部交付（白名单/前缀伪装/幂等复用/文件名 sanitize） |
| FileAssetStoreTest | 8 | file_asset CRUD / pending 计数 / TTL 清理 |
| UploadWorkspaceInjectorTest | 6 | 工作区注入幂等 / 图片内联 ImageBlock / 文档路径提示 |
| LocalFileStorageTest | 6 | 本地存储 write/read/exists/delete |
| S3EnvBindingTest | 2 | yml 平铺键绑定（防嵌套回退默认值） |
| S3FileStorageIT | 集成 | 七牛云实测（默认跳过，需真实 S3 环境变量） |

### 4.13 技能动态加载测试（oaf-skills-dynamic-loading，2026-09-07 新增）

| 测试类 | 用例数 | 覆盖点 |
|--------|--------|--------|
| SkillCatalogServiceTest | 7 | frontmatter 声明 ∪ 目录事实合并 / 冲突以目录为准 / dynamic、declaredButMissing 标记 |
| OafSkillRepositoryTest | 5 | L2 仓库注册 / 只读 / 目录缺失跳过 |

### 4.14 追踪测试（tracing-design 配套）

| 测试类 | 用例数 | 覆盖点 |
|--------|--------|--------|
| TracingSandboxClientTest | 6 | 沙箱客户端 span 装饰 |
| TracingModelWrapperTest | 6 | LLM 调用 span |
| FrameworkTracingMiddlewareTest | 5 | 框架事件 span |
| ReasoningTracingMiddlewareTest | 5 | 推理轮次 span |
| OtelConfigTest | 5 | 条件装配 / 配置绑定 |
| HttpTracingFilterTest | 3 | HTTP 入口 span |
| TraceIdConverterTest | 2 | traceId → MDC |
| **ModelIoTracingMiddlewareTest** | **6** | **模型 IO 补录**（2026-09-26 新增，PR #37）：`gen_ai.input.messages` / `gen_ai.output.messages`、超 8192 字符截断 |
| **ToolCallTracingMiddlewareTest** | **10** | **工具 IO 补录**（2026-09-26 新增，PR #37）：`gen_ai.tool.call.arguments` / `.result`、截断与错误路径 |

> 2026-09-26 实跑核对：上表 7 个既有类用例数全部未变（合计 32），新增 2 类 16 例，追踪系列现共 **48 例**。

### 4.15 seq 分配回归测试（2026-09-16，线上事故回填）

来自一次真实的线上报错：`唯一键冲突——session debug-user_mu3ydga6 本批 200 行（seq 5153..5153）已整批丢弃`。
**首尾 seq 相同**说明不是「第二个 writer」，而是同一个副本把整批算成了同一个 seq。

| 测试 | 钉住的性质 |
|------|-----------|
| `SessionEventStoreTest#unseededAppendsMustStillAdvanceSeq` | 没有 seq 计数器时（`beginTurn` 未跑）逐行 append 必须递增，不能全撞同一个 seq |
| `SessionEventStoreTest#appendsAfterReleaseSeqAlsoAdvance` | 计数器释放后的尾部事件要接得上已落库的 seq，不能撞回去 |
| `SessionEventStoreTest#stragglerAppendDuringFinalFlushIsNotRenumbered` | `finishTurn` 的 INSERT 往返期间到达的尾部事件，seq 不得被下一次播种重新发号 |
| `SessionEventStoreTest#duplicateKeyCollisionIsReportedAsSecondWriter` | 真冲突仍要记为「第二个 writer」（日志同时打出判据：批内不同 seq 数） |

> 写这类测试时的一个教训：**`SELECT MAX` 的桩必须跟着 INSERT 走**（写进去的行要能被下一次
> `MAX` 读到）。用固定值的桩会造出一个「MAX 永远停在旧值」的假世界——第一版
> `appendsAfterReleaseSeqAlsoAdvance` 正是因此测出了与实际语义无关的失败。

---

## 5. 手动验证

### 5.1 健康检查

```bash
curl -s http://localhost:8101/health | python3 -m json.tool
```

### 5.2 A2A message/send

```bash
curl -s -X POST http://localhost:8101/ \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"message/send","params":{"message":{"role":"user","parts":[{"kind":"text","text":"请只回复 welcome"}]}},"id":"1"}' \
  | python3 -m json.tool
```

### 5.3 对话单次流

```bash
curl -s -N -X POST "http://localhost:8101/threads/chat" \
  -H 'Content-Type: application/json' \
  -d '{"message":"请只回复welcome","userId":"test-user"}'
```

### 5.4 MCP 工具调用

```bash
curl -s -X POST http://localhost:8101/ \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"message/send","params":{"message":{"role":"user","parts":[{"kind":"text","text":"用 get_weather 查询北京天气"}]},"metadata":{"userId":"alice"}},"id":"m1"}'
```

### 5.5 LLM 连通性

```bash
curl -s "${LLM_ENDPOINT}/chat/completions" \
  -H "Authorization: Bearer ${LLM_API_KEY}" \
  -H "Content-Type: application/json" \
  -d '{"model":"${LLM_MODEL}","messages":[{"role":"user","content":"请只回复 welcome"}],"max_tokens":50,"temperature":0.2}'
```

---

## 6. 测试 Fixtures

### test-agent 配置

```
src/test/resources/fixtures/test-agent/
├── AGENTS.md
├── skills/
│   └── bash-tool/
│       └── SKILL.md
└── mcp-configs/
    └── weather/
        └── config.yaml          # connection: { type: streamableHttp, url: ... }
                                   # permissions: { read_only: true }
```

---

## 7. 测试环境要求

| 依赖 | 版本 | 用途 |
|------|------|------|
| JDK | ≥ 21 | Java 运行时 |
| Maven | ≥ 3.9 | 构建工具 |
| MySQL | ≥ 8.0 | MysqlDistributedStore |
| JUnit 5 | 内置 | 测试框架 |
| Spring Boot Test | 3.3.5 | 集成测试支持 |
| LLM API | mimo-v2.5 | E2E 测试（见 .env.secrets） |

---

## 8. 测试统计

> **2026-09-26 实跑**（JDK 21.0.12 / Maven 3.8.7，`mvn -B test`，55s）：
> **`Tests run: 1026, Failures: 0, Errors: 0, Skipped: 4` — BUILD SUCCESS**，耗时 55.4s。
> 跳过的 4 例全部来自 `OpenSandboxApiIntegrationTest`（需真实 OpenSandbox Server 可达）。
>
> **与静态清点的对账**（两条口径差异都能解释清楚，可复算）：
>
> | 口径 | 数值 | 说明 |
> |------|------|------|
> | `grep -rE '@Test\b'` 注解总数 | **1047** | 全量注解 |
> | 其中 `*IT` 类的注解数 | 21 | `RedisEventLogIT` 10 / `SessionEventStoreCrossReplicaIT` 6 / `S3FileStorageIT` 3 / `SessionUserStoreMySqlIT` 1 / `ThreadHistoryConfirmIT` 1 |
> | **实跑用例数** | **1026** | 1047 − 21；surefire 默认 include 不匹配 `*IT` |
> | 含 `@Test` 的源文件 | 106 | 其中 5 个是 `*IT` |
> | **实跑类数** | **101** | 106 − 5 |
>
> 即：`mvn test` **不跑那 5 支 `*IT`**，需显式 `-Dtest=`（见 §8.2）。真实 S3 集成 `S3FileStorageIT` 亦需环境变量启用。

下表**逐类取自 2026-09-26 surefire 实跑输出**（`-- in <FQCN>` 行），基线列是 2026-09-17 的旧值：

| 类别 | 用例数 | 对比基线 | 核对方式 |
|------|--------|---------|----------|
| OafConfigLoaderTest | 40 | 37 ↑ | ✅ 实跑 |
| McpToolRegistrarTest | 38 | 37 ↑ | ✅ 实跑 |
| AgentRuntimeService 系列（Service/McpConfig/Hitl） | 40 | 40 = | 基线值 |
| DebugApiControllerTest | 19 | 15 ↑ | ✅ 实跑 |
| ThreadControllerTest（含 history 文件下载卡片） | 29 | 25 ↑ | ✅ 实跑 |
| FileControllerTest / FileToolsTest / FileAssetStoreTest | 20 / 16 / 8 = 44 | 36 ↑ | ✅ 实跑 |
| ChatStreamControllerTest / SessionStreamControllerTest | 37 / 7 = 44 | 30 ↑ | ✅ 实跑 |
| ToolControllerTest | 7 | 4 ↑ | ✅ 实跑（含 issue #39 `sdkInternal` 断言） |
| ConfirmControllerTest | 8 | 6 ↑ | ✅ 实跑 |
| TurnLeaseStoreTest / TurnLeaseGuardTest | 16 | 16 = | 基线值 |
| OpenSandbox 单测（SandboxConfig/State/Client/Reader 等） | 44 | 44 = | 基线值 |
| 追踪系列（OtelConfig/Filter/Middleware/Wrapper 等） | 32 | 32 = | 基线值，**未含**下述两个新类 |
| └ `ModelIoTracingMiddlewareTest` | 6 | 新增 | ✅ 实跑（2026-09-26，PR #37） |
| └ `ToolCallTracingMiddlewareTest` | 10 | 新增 | ✅ 实跑（2026-09-26，PR #37） |
| 其余（tool/config/service/controller/storage） | 见 §8.3 清单 | — | 明细已逐类列出 |

### 8.3 2026-09 新增测试类登记（基线后未纳入 §8.1/§8.2 的）

按功能归属分组，均为 2026-09 新增。**用例数取自 2026-09-26 实跑输出**（逐类已核对）：

| 归属 | 测试类 | 用例数 |
|------|--------|--------|
| 工具插件 | `ToolPluginBootstrapperTest` | 11 |
| 工具插件 | `InternalToolRegistryTest` | 8 |
| 用户技能 | `UserSkillControllerTest` | 27 |
| 用户技能 | `UserSkillServiceTest` | 35 |
| 用户技能 | `SkillManageServiceTest` | 12 |
| 会话模型 | `ModelControllerTest` | 19 |
| 会话模型 | `ModelCatalogTest` | 14 |
| 会话标题 | `SessionTitleServiceTest` | 9 |
| OAF reload | `OafReloadServiceTest` | 8 |
| 工具摘要 | `ToolSummaryGeneratorTest` | 22 |
| 工具摘要 | `TurnToolSummaryTrackerTest` | 13 |
| MCP 多租户 | `McpToolRegistrarUserHeadersTest` | 8 |
| MCP 多租户 | `UserScopedMcpClientWrapperTest` | 8 |
| 其他 | `McpManagerTest` / `MySqlTaskStoreTest` / `HarnessAgentRunnerTest` / `AgentScopeConfigTest` / `TurnFinalizerTest` / `EmptyCompletionRecoveryHookTest` / `ToolCallValidationMiddlewareTest` / `A2uiServiceTest` / `SandboxRuntimeTest` / `PathSafeTest` / `AskingContentBackfillStateStoreTest` | 9 / 9 / 7 / 21 / 10 / 12 / 10 / 7 / 7 / 14 / 7 |

> 另有 `eval-selftest`（`bench/eval/flywheel.py selftest`，评测飞轮离线自检）是 CI 门禁之一，
> 但**不属 `mvn test` 范畴**，故不在本表。详见 [e2e-ci-plan.md](e2e-ci-plan.md) §2.3。

### 8.1 沙箱测试（OpenSandbox 集成，2026-08-12 新增）

> **2026-09-26 实跑核对**：下表用例数已逐类取自 surefire 输出，其中 `WorkspaceReaderTest`
> 与 `WorkspaceSyncServiceTest` 随 2026-09-24 用户技能管理面大幅增长（原表 5 / 6 → 现 25 / 26），
> 其余未变。

| 测试类 | 用例数 | 覆盖点 |
|--------|--------|--------|
| OpenSandboxTest | 13 | doExec 映射/注入/回写/快照 tar/失败容错/sessionId 降级 |
| OpenSandboxClientTest | 6 | create/resume(connector)/delete/序列化 |
| OpenSandboxStateTest | 6 | Jackson 序列化 round-trip/type 鉴别器/workspaceSpec→manifest |
| OpenSandboxFilesystemSpecTest | 4 | clientOptions/workspaceSpec/isolationScope |
| OpenSandboxClientOptionsTest | 3 | 默认值/fluent 链 |
| WorkspaceReaderTest | **25** | InMemoryStore KV 读写/用户隔离/注入/沙箱物化 L4 与仲裁标记（2026-09-24 随用户技能管理面大幅扩充，原表记 5） |
| WorkspaceSyncServiceTest | **26** | 回写 write(新建)/edit(更新)/容错 + 沙箱 L4 回写（原表记 6） |
| SandboxAwareMysqlAgentStateStoreTest | 2 | slot ID 斜杠放行/空 ID 拒绝 |
| SandboxConfigTest | 2 | 配置默认值/覆盖 |
| TracingSandboxClientTest | 6 | 沙箱客户端 Tracing 装饰 |
| OpenSandboxApiIntegrationTest | 4 | **真实 Server 全流程**（创建/命令/文件/契约，默认跳过，需沙箱 Server 可达） |

### 8.2 真 Redis 集成测试（`session_event` 迁 Redis Streams 后新增，2026-09-16）

两支 `*IT` 都需要**真 Redis**，由环境变量门控；**surefire 默认 include 是
`*Test`/`Test*`/`*Tests`/`*TestCase`，不匹配 `*IT`**，所以 `mvn test` 不会捡到它们，必须显式 `-Dtest=`：

```bash
REDIS_IT=1 REDIS_IT_URL=redis://127.0.0.1:6399 \
  mvn -o test -Dtest='RedisEventLogIT,SessionEventStoreCrossReplicaIT'
```

| 测试类 | 用例数 | 覆盖点 |
|--------|--------|--------|
| RedisEventLogIT | 10（**不参与 `mvn test`**，需 `-Dtest=`）| `appendBatch` 字段往返（空 replyId 归一为 null）/ `XRANGE` 边界 / `tailSeq` 随流顶端 / reply 索引保留首个 seq 并按序 / `DEL` 两 key 且幂等 / TTL 落到两 key / XADD ID 非递增返回 -1 并记为 writer 冲突 / `maxLenPerStream` 真的裁剪 / 大 payload 字节级往返 / 启动自检日志与服务器实际配置一致 |
| SessionEventStoreCrossReplicaIT | 6（**不参与 `mvn test`**）| **两个 store 共享同一 Redis**：pod B 回放 pod A 的完整 turn 含终止帧 / pod B 从中途游标续传只看到剩余部分 / pod A 未刷出的缓冲对 pod B 不可见 / 跨 pod seq 交接不冲突 / pod B 经 tailer 看到 pod A 的终止帧 / pod A 删除后 pod B 读到的 key 一并消失 |

> 两支 IT 都**不会** `FLUSHALL`/`FLUSHDB`，sessionId 全部 UUID 化，只动自己的 key，因此可以指向
> 共享实例。`REDIS_IT_URL` 不设时默认 `redis://127.0.0.1:6379` —— 若那是你在用的实例，建议显式
> 指到一个临时实例（例如 `redis-server --port 6399 --dir /tmp/x`）。

> 另有 4 例「配置反向验证」不在这两支 IT 里，靠**手动**跑：把服务端设成 `appendonly no` 或
> `maxmemory-policy allkeys-lru` 后启动应用，确认启动自检如实 ERROR（不 abort 启动）。
