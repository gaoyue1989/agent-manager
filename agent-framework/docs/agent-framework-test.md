# Agent Framework — 测试文档

**版本:** v2.1.0 (Java)
**日期:** 2026-08-06

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

### 4.2 OafConfigLoaderTest (37 个用例)

覆盖字段解析：name、vendorKey、agentKey、version、slug、description、author、license、tags、skills、mcpServers、tools、systemPrompt、model、runtimeConfig、memory、deniedTools（空默认值、显式值）。

### 4.3 A2AControllerTest (9 个用例)

覆盖场景：
- `message/send` 正常返回
- `metadata.userId` 透传到 `AgentRuntimeService`
- `metadata.thread_id` / `metadata.contextId` / `taskId` 回退
- 缺少 params / 缺少 message 返回 -32602
- 未知 method 返回 -32601
- 缺少 method 返回 -32600

### 4.4 StreamControllerTest (2 个用例)

- `GET /chat/stream` Channel SSE 流式事件
- 空消息处理

### 4.5 ToolControllerTest (4 个用例)

- `GET /skills` 返回 OAF skill 列表
- `GET /mcp` 返回 MCP 配置
- `GET /tools` 默认只返回 MCP 工具
- `GET /tools?includeInternal=true` 追加内置工具

### 4.6 AgentRuntimeServiceTest (10 个用例)

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

### 4.8 McpToolRegistrarTest (37 个用例)

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
| SessionStreamControllerTest | 11 | metadata 携带会话 key 注入 / ui 元数据 SSE / 单次流触发 / fileIds 注入 |
| StreamControllerTest | 8 | ui 元数据序列化 / 无 UI 工具降级原词表 |

### 4.11 Stateless Single-Stream 测试（stateless-single-stream 新增）

| 测试类 | 用例数 | 覆盖点 |
|--------|--------|--------|
| TurnLeaseStoreTest | 8 | acquire 成功 / PK 冲突排队 / 过期接管 / renew 续租 / renew 失败自停 / release 释放 / 并发 acquire 竞争 |
| ConfirmContextStoreTest | 10 | put 覆盖写 / consume CAS 成功 / consume 已消费 409 / consume 不存在 404 / TTL 过期 / 查询 pendingConfirm / 前缀兼容查询 / 清理过期条目 |
| ToolAuditStoreTest | 6 | 异步批量写入 / 单条写入 / 过期清理 / 批量合并 / 写入失败静默降级 / 查询审计日志 |
| ConfirmControllerTest | 5 | 同步确认 / confirm-stream / 404 与 409 语义 |

### 4.12 文件上传下载测试（file-upload-download，2026-09-07 新增）

| 测试类 | 用例数 | 覆盖点 |
|--------|--------|--------|
| FileControllerTest | 12 | 上传校验链（MIME 白名单/大小/文件名 sanitize/pending 上限）/ 下载 inline / 存储缺失 502 |
| FileToolsTest | 11 | present_file 路径越界拦截 / 沙箱与非沙箱模式 / 大小上限 |
| OafPackageToolsTest | 8 | check_oaf_package 校验规则 / create_oaf_zip 打包与注册 |
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

### 5.3 Channel SSE

```bash
curl -s -N "http://localhost:8101/chat/stream?message=请只回复welcome&userId=test-user"
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

> 2026-09-07 更新：**456 个 @Test 用例、61 个测试类**（默认跳过沙箱集成测试 `OpenSandboxApiIntegrationTest` 4 例与真实 S3 集成 `S3FileStorageIT`，需对应环境变量启用）。

| 类别 | 数量 | 状态 |
|------|------|------|
| OafConfigLoaderTest | 37 | ✅ |
| McpToolRegistrarTest | 37 | ✅ |
| AgentRuntimeService 系列（Service/McpConfig/Hitl） | 40 | ✅ |
| DebugApiControllerTest | 15 | ✅ |
| ThreadControllerTest（含 history 文件下载卡片） | 12 | ✅ |
| FileControllerTest / FileToolsTest / FileAssetStoreTest | 31 | ✅ |
| SessionStreamControllerTest / StreamControllerTest | 19 | ✅ |
| TurnLeaseStoreTest / ConfirmContextStoreTest / ToolAuditStoreTest | 24 | ✅ |
| OpenSandbox 单测（SandboxConfig/State/Client/Reader 等） | 44 | ✅ |
| 追踪系列（OtelConfig/Filter/Middleware/Wrapper 等） | 32 | ✅ |
| 其余（tool/config/service/controller/storage） | 约 195 | ✅ |

### 8.1 沙箱测试（OpenSandbox 集成，2026-08-12 新增）

| 测试类 | 用例数 | 覆盖点 |
|--------|--------|--------|
| OpenSandboxTest | 13 | doExec 映射/注入/回写/快照 tar/失败容错/sessionId 降级 |
| OpenSandboxClientTest | 6 | create/resume(connector)/delete/序列化 |
| OpenSandboxStateTest | 6 | Jackson 序列化 round-trip/type 鉴别器/workspaceSpec→manifest |
| OpenSandboxFilesystemSpecTest | 4 | clientOptions/workspaceSpec/isolationScope |
| OpenSandboxClientOptionsTest | 3 | 默认值/fluent 链 |
| WorkspaceReaderTest | 5 | InMemoryStore KV 读写/用户隔离/注入 |
| WorkspaceSyncServiceTest | 6 | 回写 write(新建)/edit(更新)/容错 |
| SandboxAwareMysqlAgentStateStoreTest | 2 | slot ID 斜杠放行/空 ID 拒绝 |
| SandboxConfigTest | 2 | 配置默认值/覆盖 |
| TracingSandboxClientTest | 6 | 沙箱客户端 Tracing 装饰 |
| OpenSandboxApiIntegrationTest | 4 | **真实 Server 全流程**（创建/命令/文件/契约，默认跳过，需沙箱 Server 可达） |
