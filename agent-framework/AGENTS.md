# Agent Framework — AGENTS.md

## 二级模块概述

Agent Framework 是基于 **AgentScope Java 2.0 HarnessAgent** 的独立可运行 Agent 服务框架。支持 **OAF v0.8.0** 配置规范 (`AGENTS.md` frontmatter)、**A2A v1.0.0** 通信协议 (JSON-RPC + SSE) 和 **A2UI v0.8** 声明式 UI 扩展。通过 MysqlDistributedStore 实现 AgentState + 工作区文件统一持久化，支持 MCP 工具原生集成、记忆管理、上下文压缩、技能自学习、Plan Mode、Channel SSE。

## 技术栈

| 层级 | 技术选型 |
|------|---------|
| Agent 引擎 | AgentScope Java 2.0 HarnessAgent (io.agentscope:agentscope-harness) |
| LLM 适配 | agentscope-extensions-model-openai (OpenAI 兼容 API) |
| 服务框架 | Spring Boot 3.3 + Tomcat (port 8100) |
| 配置解析 | SnakeYAML (frontmatter) + @ConfigurationProperties (env) |
| 状态持久化 | MysqlDistributedStore (MysqlAgentStateStore + JdbcStore → agent_state + agent_fs) |
| MCP 集成 | McpToolRegistrar (config.yaml → McpClientBuilder 原生注册) |
| A2A 协议 | AgentScopeA2aServer + HarnessAgentRunner (JSON-RPC) |
| A2UI | AgentScope 事件流驱动 |
| 数据库 | GreatSQL 8.0 (端口 3307, DB `agent_manager_test`) |
| 构建工具 | Maven 3.9+ |
| JDK | 21+ |

---

## 目录结构

> 注：以下为节选；SessionEventBus/SessionEventStore/SessionEventTailer/RedisEventLog（durable SSE）、SessionManager/SessionUserStore、SkillManageService/SkillManageController、storage/ 与 Tracing 中间件等后增类未逐一列出，完整清单以 src/main/java 实际为准。

```
agent-framework/
├── pom.xml                              # Maven 构建配置
├── src/
│   ├── main/
│   │   ├── java/io/agentmanager/framework/
│   │   │   ├── AgentFrameworkApplication.java  # Spring Boot 入口
│   │   │   ├── config/
│   │   │   │   ├── AgentManagerProperties.java  # 环境变量配置
│   │   │   │   ├── OafConfigLoader.java         # AGENTS.md 解析
│   │   │   │   ├── AgentScopeConfig.java        # Bean 装配 (HarnessAgent + MysqlDistributedStore)
│   │   │   │   ├── A2AServerConfig.java         # A2A Server 配置 (HarnessAgentRunner)
│   │   │   │   └── ChannelConfig.java           # ChatUiChannel Bean
│   │   │   ├── model/
│   │   │   │   └── OafConfig.java               # OAF 配置模型 (含 deniedTools)
│   │   │   ├── service/
│   │   │   │   ├── AgentRuntimeService.java     # Agent 运行时封装 (invoke/invokeStream + HITL 恢复)
│   │   │   │   ├── WorkspaceInitializer.java    # OAF → Workspace 目录转换（skills 由 L2 仓库动态加载，不再复制）
│   │   │   │   ├── SkillCatalogService.java     # 动态技能目录（frontmatter 声明 ∪ /config/skills 目录事实，/skills、A2A 卡片数据源）
│   │   │   │   ├── UserSkillService.java        # 用户技能 L4 管理（agent_fs 读写/删除/从包内下发 + 用户索引）
│   │   │   │   ├── McpToolRegistrar.java        # MCP 原生注册 (config.yaml → McpClientBuilder, 含 UI 元数据 + userHeaders 解析/装饰)
│   │   │   │   ├── McpManager.java              # MCP 配置加载
│   │   │   │   ├── mcp/                         # MCP 多租户按用户调用: UserScopedMcpClientWrapper (per-call header 注入) / McpUserContextMiddleware (userId→McpMeta) / McpUserHeaderCustomizer / UserHeaderRule
│   │   │   │   ├── UiContextStore.java          # MCP Apps: ui_context 持久化 (静默更新模型上下文, 4.7)
│   │   │   │   ├── UiContextInjectionHook.java  # MCP Apps: PreCallEvent Hook 注入 UI 上下文 (appendSystemContent)
│   │   │   │   ├── McpResourceProxy.java        # MCP Apps: 拉取/代理 MCP 服务器资源 (HtmlResource)
│   │   │   │   ├── HarnessAgentRunner.java      # A2A Server 适配器
│   │   │   │   ├── MySqlTaskStore.java          # A2A TaskStore 实现 (读 agent_state, save no-op)
│   │   │   │   ├── StateDataParser.java         # state_data JSON 公共解析 (context[] → 消息, 含工具状态/结果与敏感值遮掩)
│   │   │   │   ├── AgentStateReader.java        # AgentState 读取 (history 权威源 + HITL 挂起快照)
│   │   │   │   ├── TurnLeaseStore.java          # turn_lease 表 (单次流执行权互斥)
│   │   │   │   ├── TurnLeaseGuard.java          # turn 续租句柄 (close 幂等释放)
│   │   │   │   ├── ConfirmContextStore.java     # confirm_context 表 (HITL 确认上下文, CAS 防重复)
│   │   │   │   ├── ToolAuditStore.java          # tool_audit_log 异步批量审计写
│   │   │   │   ├── FileAssetStore.java          # file_asset 表 (上传/交付文件元数据)
│   │   │   │   ├── UploadWorkspaceInjector.java # 上传文件注入会话工作区 + 消息内容块构造
│   │   │   │   ├── SessionCleanupService.java   # 过期会话/附件联动清理
│   │   │   │   ├── storage/                     # 文件存储后端 (FileStorage: LocalFileStorage / S3FileStorage)
│   │   │   │   ├── A2uiService.java             # A2UI 协议
│   │   │   │   └── LLMLogger.java               # LLM 调用日志
│   │   │   ├── sandbox/opensandbox/             # OpenSandbox 沙箱集成 (OpenSandbox/Client/FilesystemSpec/WorkspaceSyncService：MEMORY.md+memory/ 与 skills/ 回写 KV)
│   │   │   ├── tool/
│   │   │   │   ├── BusinessTools.java           # @Tool 注解自定义工具 (get_current_time, echo)
│   │   │   │   ├── FileTools.java               # present_file / present_url 工具 (工作区产物与外部交付物注册)
│   │   │   │   ├── CustomTool.java              # 自定义工具标记接口（List 注入收集收窄，防循环依赖）
│   │   │   │   ├── ToolPlugin.java              # 工具插件 SPI（extends CustomTool，jar 内 META-INF/services 注册）
│   │   │   │   └── ToolPluginBootstrapper.java  # BFPP：plugins/ 目录扫描 + ServiceLoader 加载，工具实例并入 List<CustomTool> 注入源
│   │   │   └── controller/
│   │   │       ├── InfoController.java          # GET /、/metadata、/system-prompt
│   │   │       ├── HealthController.java        # GET /health
│   │   │       ├── ToolController.java          # GET /skills、/mcp、/tools
│   │   │       ├── AgentCardController.java     # GET /.well-known/agent-card.json
│   │   │       ├── UserSkillController.java     # /skills/users 系列（列出用户/读取/写入/删除/从包内下发用户技能）
│   │   │       ├── DebugController.java         # GET /debug
│   │   │       ├── DebugApiController.java      # GET /debug/config、/debug/threads 等
│   │   │       ├── ThreadController.java        # GET /threads、/{sid}/history、/{sid}/llm-calls
│   │   │       ├── ChatStreamController.java    # POST /threads/chat (SSE 单次流, 唯一对话入口)
│   │   │       ├── SessionStreamController.java # GET /threads/{sid}/subscribe、/{sid}/status
│   │   │       ├── ConfirmController.java       # POST /threads/{sid}/confirm、/confirm-stream (HITL)
│   │   │       ├── FileController.java          # POST /files/upload、GET /files/{fileId}
│   │   │       ├── AgentEventSseSerializer.java # SSE 序列化共用工具 (ChatStreamController + SessionEventBus)
│   │   │       ├── McpProxyController.java      # MCP Apps: GET /mcp/{server}/resources/ui 等 (前端资源代理)
│   │   │       ├── UiContextController.java     # MCP Apps: POST /mcp/ui-context (4.7 静默更新)
│   │   │       └── A2AController.java           # POST / (A2A JSON-RPC, 全量透传 SDK)
│   │   └── resources/
│   │       ├── application.yml                  # Spring Boot 配置
│   │       └── static/debug/                    # 调试页面 (拆分架构)
│   │           ├── index.html                   # 调试页入口
│   │           ├── css/                         # 样式 (base/components/layout)
│   │           ├── js/                          # 脚本 (api/app/router/state/utils), mcp-app-host.js (MCP App 卡片宿主)
│   │           └── modules/                     # 功能模块 (chat/tools/config/database/logs/mcp/memory/sandbox/skills/workspace)
│   └── test/                                  # 101 个实跑测试类 / 1026 个用例（2026-09-26 实跑，0 失败 4 跳过）
├── docs/                                     # 设计与改进方案文档 (38 份, 索引见 docs/README.md)
├── Dockerfile                                # 镜像构建 (多阶段: Maven 构建 → JRE 21 运行)
├── Dockerfile.dev                            # 离线开发镜像 (JDK 21 + Maven + 全量依赖缓存)
├── Makefile                                  # Maven 封装 (build/test/docker-build/offline 等)
├── docker/
│   └── offline-settings.xml                  # Maven 默认配置模板 (支持 Nexus 镜像)
└── .env.example                              # 环境变量模板
```

---

## 核心模块

### 1. AgentScopeConfig — Agent 装配

创建 `HarnessAgent` Bean，配置：
- **MysqlDistributedStore**: AgentState + 工作区文件统一持久化
- **RemoteFilesystemSpec(IsolationScope.USER)**: 按 userId 多租户隔离
- **MemoryConfig**: 记忆管理（MEMORY.md + memory/，flush 节流）
- **CompactionConfig**: 上下文压缩（30 条触发，保留 10 条）
- **ToolResultEvictionConfig**: 大工具结果卸载
- **Plan Mode / Skill 自学习**: 启用
- **Toolkit**: 自定义工具 (BusinessTools) + MCP 工具 (McpToolRegistrar)
- **WorkspaceInitializer**: OAF → Workspace 转换
- **Skill L2 市场仓库**: `/config/skills` 注册为 `FileSystemSkillRepository(writeable=false, source="oaf-package")`——HarnessSkillMiddleware 每轮推理重扫，PVC 上技能目录运行中原位变化（新增/修改/删除）无需重启即在下轮生效；目录缺失时跳过（包未携带 skills）

### 2. AgentRuntimeService — 运行时封装

```java
invoke(message, threadId)            → (response, threadId)
invoke(message, threadId, userId)    → (response, threadId)  // 多租户
invokeStream(message, threadId)      → Flux<Map>  // 流式
invokeStream(message, threadId, userId) → Flux<Map>
```

- `userId` 来源：A2A `metadata.userId` / Channel `SendOptions.userId()` / 默认回退 `vendorKey`
- `sessionId` 生成：`tenantPrefix.replace("/","-") + ":" + threadId`

### 3. McpToolRegistrar — MCP 原生注册

- 从 `mcp-configs/{server}/config.yaml` 读取 `connection` + `auth` + `permissions` + `userHeaders`
- 支持 `sse` / `streamableHttp` / `stdio` 三种传输
- **client 统一 `buildAsync()` 构建**（`McpClientWrapper` 返回 `McpAsyncClientWrapper`）：per-call 用户 header 依赖 Reactor Context 传播，sync 客户端 `block()` 桥接会断链（资源代理 `buildSyncClient` 独立链路不受影响）
- **支持 `permissions.read_only: true`**: 非只读 MCP 工具被权限系统拦截时，强制注册为只读绕过 HITL
- **支持 ActiveMCP.json 子集过滤**: `selectedTools` 中 `enabled: false` 的工具不注册到 Toolkit
- **工具注册名**: 使用远端裸名（`tool.name()`），确保 `McpTool.callAsync` 正确执行；`mcp__{server}__{tool}` 前缀名仅用于 API 展示和注册缓存（因 `McpTool.getName()` 是 `final` 字段，无法分离 LLM 暴露名和执行名）
- **MCP Apps (阶段一/二)**: config.yaml 支持 `ui.tools.{tool}.resource_uri`（`ui://xxx` 静态声明）与 `ui.app_only: true`（仅卡片展示、不入 LLM 工具集）；`resolveUiRef(toolName)` 供 SSE 序列化携带 `ui` 元数据；`/tools`、`/mcp` 接口输出 `uiResourceUri`/`appOnly`/`has_ui`；Manifest 动态发现（`tool.meta()` 的 `_meta`）用于 resourceUri 预检
- **多租户按用户调用（userHeaders + `_meta` 双通道，PR #9）**: config.yaml `userHeaders.headers` 声明 `header 名 ← McpMeta key`（如 `X-User-Id: userId`）+ `on-missing`（缺省 deny，fail-closed）；`McpUserContextMiddleware`（唯一注入点，覆盖 Channel/invoke/A2A/confirm 全链路）把生效 userId 写入 `McpMeta` —— Channel 链路 `RuntimeContext.userId` 为网关 peer（=会话 id），按 `session_user` 表反查真实用户；业务调用经 `UserScopedMcpClientWrapper` 注入下游 HTTP header（静态 `auth.token` 仅用于连接初始化与 tools/list 发现），meta 同时随 `CallToolRequest._meta` 下传；stdio 传输告警忽略；设计详见 [docs/mcp-user-scoped-headers-plan.md](docs/mcp-user-scoped-headers-plan.md)

### 3.5 MCP Apps 运行链路（Debug 页卡片 + 4.7 静默更新）

- **卡片渲染**: TOOL_CALL_START 事件带 `toolName` → `registrar.resolveUiRef` 解析 `ui://` 资源 → SSE payload 携带 `ui` 字段 → 前端 `mcp-app-host.js` 经 `McpResourceProxy` 拉取 HtmlResource（注入 CSP meta）→ 沙箱 iframe（srcdoc, sandbox=allow-scripts, opaque origin）→ JSON-RPC over postMessage（`ui/initialize` handshake → `tools/call` 转发 `McpProxyController` → `ui/update-model-context`）
- **卡片锚点（重要）**: 卡片必须挂 `r.contentEl`，不可挂 textEl —— `TEXT_BLOCK_DELTA` 会对 textEl `innerHTML` 整体重写，工具调用发生在文本输出之后时卡片会被误清
- **4.7 ui_context**: `POST /mcp/ui-context` 写 `ui_context` 表（sessionId 维度覆盖写）→ 前端在用户消息 `metadata` 写入会话 key（`UiContextStore.METADATA_SESSION_KEY`）→ `UiContextInjectionHook` 在 PreCallEvent 阶段按会话查库 `appendSystemContent` 注入（HarnessAgent 拒绝 inputMessages 中 SYSTEM 消息，只能经 setSystemMessage/appendSystemContent）

### 4. A2AController — A2A JSON-RPC（全量透传 SDK）

- `POST /` 全量透传给 AgentScopeA2aServer（SDK）处理：`message/send`、`message/stream`、`tasks/get`、`tasks/cancel`、`tasks/resubscribe` 等所有标准 A2A 方法
- **HITL 限制（2026-09-18 声明）**：A2A 通道**不支持** permission `ask` 工具（publish/update_env/republish/unpublish/delete）——挂起态只存 harness checkpoint、不落平台 confirm_context，A2A 客户端无法批准，且同会话后续请求会持续 `IllegalStateException`。该限制已写入注册卡 description（`AgentCardNotes.A2A_CHANNEL_LIMITATION`）；变更类操作必须走 `/threads/chat` + `/threads/{sid}/confirm-stream`
- 实现参考官方 `agentscope-a2a-spring-boot-starter` 的 `A2aJsonRpcController`
- 兼容转换：message/send 与 message/stream 自动补全 SDK 反序列化必需字段（`kind:"message"`、`messageId`、parts `kind:"text"`、`blocking:true`），顶层 `userId`/`sessionId` → `message.metadata`
- `MySqlTaskStore` 注入 SDK：tasks/get 从 agent_state 表读取构造 A2A Task（save no-op，消息已由 AgentScope 自动持久化）
- 多租户：SDK 从 `message.metadata.userId` / `message.metadata.sessionId` 读取（AgentScopeAgentExecutor）

---

## AgentScope 2.0 功能使用状态

| 功能 | 状态 | 说明 |
|------|------|------|
| 配置动态 reload | ✅ | OAF 包（PVC /config）原位更新免重启：`POST /admin/reload`（auto 指纹分流：仅 MCP 配置变→`OafReloadService.reloadMcpAll` 原地 reload（toolkit.removeMcpClient + registerOne，声明增删即时生效）；AGENTS.md 变→`reloadAgent` 整包重建（`HarnessAgentFactory` 重用启动装配，`WorkspaceInitializer.reinitialize` 覆盖生成文件，`AgentRuntimeService.swapAgent`/`A2aAgentRefHolder`/`OafConfigHolder` 原子切引用，旧 agent MCP 连接收尾）。失败回滚保持旧 agent；生效=下一轮对话；`AgentRuntimeService`/`HarnessAgentRunner` 持 volatile 引用，A2A 经 holder 间接持有。设计/时序/E2E 见 [docs/oaf-dynamic-reload-plan.md](docs/oaf-dynamic-reload-plan.md)；`tool/CustomTool` 标记接口收窄 `List` 注入候选（防 Spring 循环依赖） |
| 工具插件 | ✅ | Java SPI 免重编译加载自定义工具：插件 jar 放 `{AGENT_PLUGINS_DIR}`（默认 `{AGENT_CONFIG_DIR}/plugins`），启动期 `ToolPluginBootstrapper`（BFPP）扫描 + `ServiceLoader` 实例化 `ToolPlugin` 实现，工具实例注册为单例并入 `List<CustomTool>` 注入源——HarnessAgentFactory（启动 + reload 整包重建）、InternalToolRegistry（/tools）、HITL 白名单零改动共享；`{jar名}/config.yaml` 支持 `${ENV_VAR}` 替换；插件更新需重启（类卸载限制），框架类须 provided 不打进 jar。示例 `e2e/plugin-echo/` + 冒烟 `e2e/scripts/plugin-smoke.sh`（30 断言，含 issue #39 sdkInternal 段），设计/实施见 [docs/tool-plugin-extension-plan.md](docs/tool-plugin-extension-plan.md) |
| 技能（Skill） | ✅ | **动态加载**：/config/skills 注册为 L2 市场仓库（每轮重扫，不重启生效）；SkillCatalogService 为 /skills、A2A 卡片、debug config 提供声明 ∪ 目录合并视图；自学习 L4 覆盖（skill_manage/propose_skill → agent_fs per-user）；用户技能管理面 `/skills/users/*`（列出/读取/写入/删除/从包内下发，调试页 Skills 模块「用户技能」区块；删除 = 回落包内基线 + 写删除标记防沙箱回写复活）。**沙箱档 L4 写入落库（本次修复）**：沙箱会话内 skill_manage 把 L4 写进容器 `/workspace/skills`，由 `WorkspaceSyncService.syncBack` 在每次 call 结束回写 agent_fs（`WorkspaceSyncService.java:132` 起 `syncUserSkills`，命名空间/key 一律经 `WorkspaceReader.writeUserSkillFile`，`WorkspaceReader.java:414`）——修复前只回写 MEMORY.md/memory/，L4 技能随容器 TTL 到期丢失。**回写仲裁（两个 KV 元数据键，命中即跳过同名技能）**：删除写 `/{name}/.deleted`（防删除被容器内副本复活）、管理面写入（PUT/下发）写 `/{name}/.admin-override`（防管理面写入被同代容器内旧副本在下次 call 结束时改回）；代价是标记生效期间该技能在容器内的 skill_manage 修改不落库，状态与清除方式经列表 `tombstones`/`adminOverride` 字段与删除/PUT 响应下发（调试页醒目标注）。**生效范围分档**：非沙箱档管理面 L4 下轮会话生效；沙箱档会话读容器内 `/workspace/skills` 副本，管理面写入由「会话开始物化 L4」（`WorkspaceReader.materializeUserSkills`，`SandboxUserKeyMiddleware.onAgent` 在每次 acquire 后投影进容器；`/{name}/.deleted` 跳过、admin-override 照写、只写不删）在该用户下一个 turn 生效；`/skills/available`、`/skills/parse-refs` 与 `@Skill` 注入按网关注入的 `X-User-Id`（或 `?userId=`）合并该用户 L4。概览见下表端点说明与 `e2e/user-skill-admin-e2e.sh` 档位说明；设计/根因/验证见 [../docs/design/user-skill-admin-design.md](../docs/design/user-skill-admin-design.md) |
| 记忆管理 | ✅ | MEMORY.md + memory/，flush 节流 10 分钟；可经 `AGENT_MEMORY_ENABLED=false` 完全关闭（不注册 memory_* 工具 + 不执行 flush/整合 + 沙箱不注入/回写记忆文件） |
| 上下文压缩 | ✅ | CompactionConfig，30 条触发保留 10 条 |
| Plan Mode | ✅ | enablePlanMode() |
| Channel | ✅ | ChatUiChannel (POST /threads/chat) |
| 工作区（Workspace） | ✅ | WorkspaceInitializer 生成 .agentscope/workspace/ |
| 子 Agent | ✅ | subagents/*.md |
| 沙箱 | ✅ | OpenSandbox 集成（SANDBOX_ENABLED=true，USER 级复用 + 记忆/用户技能回写 KV） |
| Agent 状态存储 | ✅ | MysqlDistributedStore (agent_state + agent_fs) |
| 模型集成 | ✅ | OpenAI 兼容 API |
| MCP 集成 | ✅ | McpToolRegistrar (config.yaml permissions.read_only) |
| MCP 启动容错 | ✅ | 默认 fail-soft：server 不可达仅告警跳过不阻断启动；config.yaml `startup.required: true` 可声明严格失败 |
| A2A 协议 | ✅ | AgentScopeA2aServer + HarnessAgentRunner |
| 多租户 | ✅ | IsolationScope.USER (按 userId 隔离) |

---

## 工具体系

### 内置工具（Harness 自动注册，约 26 个）

文件: `read_file`, `write_file`, `edit_file`, `grep_files`, `glob_files`, `list_files`
记忆: `memory_search`, `memory_get`, `memory_save`
会话: `session_search`, `session_list`, `session_history`
子Agent: `agent_spawn`, `agent_send`, `agent_list`
计划: `plan_enter`, `plan_write`, `plan_exit`
技能: `propose_skill`, `skill_manage`, `load_skill_through_path`
任务: `task_list`, `task_output`, `task_cancel`, `wait_async_results`

### 自定义工具（@Tool 注解）

| 工具 | 说明 |
|------|------|
| `get_current_time(timezone)` | 返回指定时区当前时间 |
| `echo(text)` | 回显输入 |
| `present_file(file_path, file_content_base64?)` | 工作区产物注册到平台供用户下载（结果由 SSE 层合成 file_ready 帧） |
| `present_url(file_name, url, mime_type?, size?)` | 外部系统产物（http(s) URL）登记为下载卡片；`/files/{id}` 服务端代理回源（前缀白名单，SSRF 收敛） |

> OAF 打包工具（`check_oaf_package` / `create_oaf_zip`）2026-09 迁至平台 backend 的 platform-publisher MCP
> （直建包返回 packageId/download_url，零 base64 经 LLM），发布助手经 `present_url` 交付；
> 分层原则：业务领域工具走 MCP，框架通用能力走 @Tool。设计见 [../docs/design/oaf-tools-extraction-design.md](../docs/design/oaf-tools-extraction-design.md)

**工具插件（Java SPI）**：无需重编译/重建镜像加载自定义工具——独立 jar（`META-INF/services` 注册
`ToolPlugin` 实现）放入 `{AGENT_PLUGINS_DIR}`（默认 `{AGENT_CONFIG_DIR}/plugins`），启动期
`ToolPluginBootstrapper`（BFPP）经 `ServiceLoader` 加载并把工具实例并入 `List<CustomTool>` 注入源，
deniedTools 过滤、HITL、/tools、OAF reload 整包重建全部自动生效；`{jar名}/config.yaml` 支持
`${ENV_VAR}` 注入 `configure()`。约束：插件 jar 不得打包 agent-framework/agentscope-core 类（provided），
插件更新需重启。冒烟验证 `e2e/scripts/plugin-smoke.sh`；设计/实施记录见
[docs/tool-plugin-extension-plan.md](docs/tool-plugin-extension-plan.md)。

### MCP 工具

通过 `McpToolRegistrar` 从 `mcp-configs/{server}/config.yaml` 注册。
- 传输: `sse` / `streamableHttp` / `stdio`
- 认证: `auth.token` 支持 `${ENV_VAR}` 语法（静态凭据，仅用于连接初始化与 tools/list 发现）
- 多租户: `userHeaders.headers`（header 名 ← McpMeta key）+ `on-missing: deny|passthrough`（缺省 deny）；userId 经 `McpUserContextMiddleware` 写入 McpMeta，双通道生效（HTTP header + `_meta`）
- 权限: `permissions.read_only: true` 强制只读
- 子集: `mcp-configs/{server}/ActiveMCP.json` 的 `selectedTools.enabled` 控制注册子集
- 命名: Toolkit 注册用远端裸名；`/tools`、`/mcp` API 用 `mcp__{server}__{tool}` 展示名

### 工具过滤

`tools.json` 只写 `deny`，不写 `allow`（保留全部内置工具）。
OAF `deniedTools` 字段控制排除列表。

### 工具权限（HITL 三态）

规则来源与作用域（均按 Toolkit 注册名精确匹配，评估顺序 Deny → Ask → Allow → mode 兜底）：

| 来源 | 作用域 | 声明位置 |
|---|---|---|
| MCP 工具 | 各 server 的 MCP 工具 | `mcp-configs/{server}/config.yaml` 的 `permissions.tools`（allow/ask/deny） |
| 自定义/内置工具 | @Tool 注解工具 + Harness 内置工具 | AGENTS.md frontmatter `config.permission.tools`（2026-09-20 新增） |

- 自定义/内置工具**未声明默认 ALLOW**（不参与确认，零侵入）；声明 `ask` 即接入 HITL 确认卡链路（`permission_ask` → `confirm_context` → `/threads/{sid}/confirm-stream`，与 MCP 工具共用）
- 优先级：MCP 显式规则 > frontmatter 声明 > 自动放行；与 MCP 裸名冲突的 frontmatter 声明忽略并告警
- `config.permission.mode` 为全局模式（default/accept_edits/explore/bypass/dont_ask）；`require_confirmation: true` 仅兜底 MCP 工具 ASK，**不**扩展到自定义工具
- 装配：`AgentScopeConfig.buildPermissionContext`（存在任一规则来源即启用权限系统）；声明了未注册工具名告警忽略

---

## 多租户隔离

通过 `RemoteFilesystemSpec(IsolationScope.USER)` + `RuntimeContext(userId, sessionId)` 实现：

| 数据类型 | 隔离维度 | 存储位置 |
|----------|---------|---------|
| AgentState | (userId, sessionId) | agent_state 表 |
| MEMORY.md | userId | agent_fs 表 |
| memory/ | userId | agent_fs 表 |
| skills/ | 包内 L2 共享 + 用户 L4 覆盖 | agent_fs 表（L4：`agents/{agent}/users/{uid}/skills`，key `/{技能名}/{相对路径}`；沙箱档由 syncBack 回写该命名空间，回写仲裁靠 `/{技能名}/.deleted` 与 `/{技能名}/.admin-override` 两个元数据键） |
| sessions/ | userId | agent_fs 表 |

---

## 环境变量

| 变量 | 默认值 | 必填 | 说明 |
|------|--------|------|------|
| `LLM_API_KEY` | — | ✓ | LLM API 密钥 |
| `LLM_MODEL_ID` | — | ✓ | 模型 ID |
| `LLM_BASE_URL` | — | ✓ | LLM API 端点 |
| `LLM_PROVIDER` | `openai` | | 推理引擎方言（`openai`/`vllm`/`sglang`/`glm`/`deepseek`），决定思考/推理参数下发位置（见 ChatModelFactory.applyDialect） |
| `LLM_TEMPERATURE` | `0.7` | | 生成温度 |
| `LLM_MAX_TOKENS` | `4096` | | 最大 token |
| `LLM_TIMEOUT` | `120` | | 超时(秒) |
| `LLM_REASONING_EFFORT` | — | | 推理强度（如 low/medium/high）；空 = 不下发，值集因端点而异 |
| `LLM_FREQUENCY_PENALTY` | — | | 频率惩罚 [-2.0, 2.0]；空 = 不下发 |
| `LLM_CONTEXT_LENGTH` | `0` | | 模型上下文窗口大小（tokens，≤0 视为未配置，不传给模型） |
| `AGENT_CONFIG_DIR` | `/config` | | Agent 配置目录 |
| `SERVER_HOST` | `0.0.0.0` | | 监听地址 |
| `SERVER_PORT` | `8100` | | 服务端口 |
| `CHECKPOINT_JDBC_URL` | `jdbc:mysql://127.0.0.1:3307/agent_manager_test` | | MySQL JDBC URL |
| `CHECKPOINT_DB_NAME` | — | | agent_state 表所在数据库名（可选；未设置时自动从 JDBC URL 解析，保证与 agent_fs 同库） |
| `CHECKPOINT_USERNAME` | `agent_manager` | | MySQL 用户名 |
| `CHECKPOINT_PASSWORD` | `Agent@Manager2026` | | MySQL 密码 |
| `AGENT_REDIS_URL` | `redis://127.0.0.1:6379` | | session_event 事件流存储（Redis Streams）；集群内必配 `redis://oaf-redis.agent-platform.svc.cluster.local:6379`，缺省指向 Pod 自身 localhost 导致事件不落地（见 docs/api-frontend-sse.md §12） |
| `SANDBOX_ENABLED` | `false` | | 沙箱模式开关（true 时文件操作/Shell 在 OpenSandbox 隔离沙箱执行） |
| `SANDBOX_IMAGE` | `opensandbox/code-interpreter:v1.1.0` | | 沙箱镜像 |
| `SANDBOX_TIMEOUT_MINUTES` | `60` | | 沙箱超时（分钟） |
| `SANDBOX_PROJECTION_ENABLED` | `true` | | 工作区投影开关（issue #27）：关闭后每次 sandbox start 不再 hydrate 投影目录（AGENTS.md/skills 等），降低每轮对话沙箱同步开销；skills 依赖强的包不要关 |
| `SANDBOX_GUARD_ENABLED` | `true` | | 沙箱并发执行守卫（issue #27，官方 §9 对 USER 范围的建议）：Redis SET NX 串行化同 userId 的沙箱获取，消解并发 hydrate 互踩/端口竞态触发面；Redis 不可用时 fail-open |
| `SANDBOX_GUARD_LEASE_SECONDS` | `900` | | 守卫租约 TTL（崩溃自愈） |
| （env 未设置时）OAF `config.sandbox.enabled` | — | | 包级沙箱开关（issue #27b）：`SANDBOX_ENABLED` 显式设置时优先；env 未设置时读包 frontmatter `config.sandbox.enabled`，均未声明回退 false。生效裁决统一在 SandboxRuntime |
| `SANDBOX_MEMORY_MB` | `1024` | | 沙箱内存限制（MiB） |
| `SANDBOX_CPU_COUNT` | `1` | | 沙箱 CPU 限制 |
| `SANDBOX_ENTRYPOINT` | `/opt/code-interpreter/code-interpreter.sh` | | 沙箱启动命令（逗号分隔，如 `python,main.py`；默认即镜像启动脚本） |
| `SANDBOX_EXECD_GRACE_SHUTDOWN` | `100ms` | | execd 命令 SSE 尾窗保持时间，注入容器 `EXECD_API_GRACE_SHUTDOWN`（默认 1s 拖慢每条命令 ~1s，配 100ms 提速 ~10x） |
| `OPENSANDBOX_SERVER_URL` | `192.168.31.155:8090` | | OpenSandbox Server 地址 |
| `OPENSANDBOX_API_KEY` | — | | OpenSandbox API 密钥 |
| `FILE_UPLOAD_ENABLED` | `true` | | 文件上传开关（其余 FILE_* 见 application.yml / file-upload-download-plan.md：上限 20MB、pending 20、MIME 白名单、存储后端 FILE_STORAGE_TYPE=local/s3） |
| `FILE_EXTERNAL_URL_PREFIXES` | 空(禁用) | | present_url 外部交付物与 /files/{id} 代理下载共用的 URL 前缀白名单（逗号分隔）；发布助手集群内必配 `http://platform-backend.agent-platform.svc.cluster.local:8080` |
| `AGENT_CLEANUP_*` | 见 api.md | | confirm TTL / turn 租约 TTL / 审计与会话保留期 |
| `AGENT_HISTORY_TOOL_OUTPUT_MAX_CHARS` | `8000` | | history 工具结果文本（tool_result.output）截断上限，≤0 不截断（见 docs/history-agentstate-design.md） |
| `AGENT_MEMORY_ENABLED` | `true` | | 记忆总开关：`false` = 完全关闭记忆——不注册 `memory_*` 工具 + 不执行 flush/整合（`disableMemoryHooks` + `disableMemoryTools`）；沙箱不再注入/回写记忆文件（技能回写不受影响） |

> **`LLM_*` 的语义 = 系统模型（会话模型切换，2026-09-24）**：`LLM_*` 是**系统模型**——未显式选择模型的会话的对话模型，
> 同时固定用于**会话标题生成**与**记忆 flush/整合、上下文压缩**（后两者直调 model.stream 不经 onModelCall 链，不受会话切换影响）。
> 托管模型存 `model_config` 表（`/models` REST CRUD，debug 页「Models」模块可管理），会话经 `model` 字段按会话选择，
> 仅影响该会话的对话调用；详见 [docs/session-model-switch-design.md](docs/session-model-switch-design.md)。
> **采样参数方言（2026-09-27）**：思考开关/推理强度的下发位置由 `provider` 方言决定（`openai` 顶层 effort；
> `vllm`/`sglang` 合并走 `chat_template_kwargs`；`glm` 走 `thinking.type`+顶层；`deepseek` 不下发），
> NULL/空 = 不下发；详见 [docs/model-params-design.md](docs/model-params-design.md)。

---

## 服务端点

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/` | 服务信息 + 协议声明 |
| GET | `/metadata` | 完整 Agent 元数据（skills 对象数组，?includeDetails=true 含 tools/subAgents/model） |
| GET | `/health` | 健康检查 |
| GET | `/.well-known/agent-card.json` | Agent Card |
| GET | `/skills` | 技能列表（动态：frontmatter 声明 ∪ /config/skills 目录事实，冲突以目录为准；字段含 dynamic/declaredButMissing 标记） |
| GET | `/skills/available` | 可用技能摘要（name+description，供 @Skill 提示）。**按用户合并 L4**：`X-User-Id`（网关注入）或 `?userId=` 时并集该用户个人技能（同名以 L4 描述为准），无则仅全局目录 |
| GET | `/skills/parse-refs` | 解析消息中的 @Skill 引用（`?message=`；同样按 `X-User-Id`/`?userId=` 合并 L4 校验） |
| GET | `/skills/users` | 存在个人技能覆盖（L4，agent_fs `agents/{agent}/users/{uid}/skills`）的用户索引（触顶截断时带 `truncated=true`；**索引查询失败 500**，不降级成 200 + 空列表） |
| GET | `/skills/users/{userId}` | 某用户的个人技能列表（`hasPackageBaseline`=删除后回落该包内技能，`adminOverride`=带管理面写入栅栏；`tombstones` 列出已删除但标记仍在的技能；枚举/标记读取失败 500，不降级为空） |
| GET | `/skills/users/{userId}/{name}` | 技能文件内容（?file= 相对路径，默认 SKILL.md；L4 优先，无覆盖回落包内基线，source=user/package；`files`/`version`/`hasUserOverride` 与 source 同源，`userOverrideExists` 表示该用户另有个人覆盖） |
| PUT | `/skills/users/{userId}/{name}` | 新建/覆盖该用户 SKILL.md（个人覆盖；≤100KB）。**生效范围分档（务必看）**：非沙箱档（SANDBOX_ENABLED=false）下轮会话生效；沙箱档写 agent_fs KV 并由「会话开始物化 L4」在该用户下一个 turn 投影进容器 `/workspace/skills` 生效。写入同时置写侧栅栏 `/{name}/.admin-override`：同代容器内旧副本在下次 call 结束时**不会**把该 KV 写入改回容器版本；代价是该技能在容器内用 skill_manage 的后续修改也不再回写落库（删除该技能可清除栅栏） |
| DELETE | `/skills/users/{userId}/{name}` | 删除该用户个人覆盖（全部文件，写 KV 删除标记 `/{name}/.deleted` 防回写复活，并清除写侧栅栏；响应带 `tombstone`=标记名+清除方式）→ 有包内同名技能则回落基线，否则该技能消失。**标记无 TTL**：该用户此后在同代（及后续）容器内用 skill_manage 重建同名技能不会被回写落库，需管理面重新写入或从包内下发才清除标记。沙箱档下删除作用于 KV + tombstone：会话开始物化时不再写回容器，但容器内旧副本在容器换代前仍可能对该用户可见 |
| POST | `/skills/users/{userId}/{name}/sync-from-package` | 把包内同名技能以包内清单为准**全量替换**为该用户个人版本（含 scripts/ 等资源；差集清理多余旧文件、失败回滚）；非 UTF-8/二进制文件显式跳过并列入响应 `skipped`；同样置写侧栅栏 `/{name}/.admin-override` |
| GET | `/debug/user-skills` | 个人技能用户索引（调试页 Skills 模块「用户技能」区块数据源，与 `/skills/users` 同源；同上带 `truncated`；索引查询失败 500） |
| GET | `/mcp` | MCP 服务器列表 |
| GET | `/tools` | 工具列表 |
| POST | `/admin/reload?scope=auto\|mcp\|agent` | **OAF 配置动态 reload**（[docs/oaf-dynamic-reload-plan.md](docs/oaf-dynamic-reload-plan.md)）：auto=指纹比对自动分流（仅 MCP 配置变→原地 reload；AGENTS.md 变→整包重建 HarnessAgent）；mcp=仅 MCP 原地 reload（重解析 frontmatter，声明增删即时生效）；agent=强制整包重建。失败保持旧配置服务（500 + 结构化错误），生效语义=下一轮对话，进行中 turn 不打断 |
| GET | `/admin/reload` | reload 只读状态：当前已注册 MCP server（connected/tool_count） |
| GET | `/debug` | 调试页面（静态资源） |
| GET | `/system-prompt` | 系统提示词 |
| GET | `/threads` | Thread 列表（含 `title` 与 `model`=会话绑定模型，空串=默认） |
| GET | `/threads/{sid}/history` | 历史消息 + pendingConfirm（含文件下载卡片补齐） |
| GET | `/threads/{sid}/llm-calls` | LLM 调用记录 |
| PATCH | `/threads/{sid}` | 更新会话：`title` 重命名 + `model` 会话模型切换（""/system=回默认；未知/禁用 400） |
| POST | `/threads/chat` | 无状态单次流 SSE 对话（唯一对话入口，{message?, userId?, sessionId?, fileIds?, model?}；不传 sessionId 自动生成 UUID 并首发 `session_created`；Turn 租约排队 waiting 帧；model 传值即绑定本会话并本 turn 生效） |
| GET | `/models` | 会话可选模型列表（系统模型 + 托管模型；?all=true 含禁用以供管理页恢复） |
| GET | `/models/{id}` | 模型详情（托管模型 key 掩码；系统模型只读视图） |
| POST | `/models` | 新增托管模型（name/modelId/baseUrl 必填；采样参数可空：`reasoningEffort` 格式 `^[a-z0-9_]{1,16}$`、`frequencyPenalty` ∈[-2,2]、`provider` ∈方言枚举；重复 name 400） |
| PATCH | `/models/{id}` | 更新托管模型（字段缺省=不变；`apiKey=""`=清空回落系统密钥；`reasoningEffort=""`=清除不下发） |
| DELETE | `/models/{id}` | 删除托管模型（引用会话自动回落默认模型） |
| POST | `/models/{id}/test` | 模型连接测试（真实发一次最小 completion，返回 ok/latency_ms/reply） |
| POST | `/threads/{sid}/confirm` | HITL 同步确认 |
| POST | `/threads/{sid}/confirm-stream` | HITL 流式确认（新执行段重新 acquire 租约） |
| POST | `/files/upload` | 文件上传（multipart，MIME/大小/pending 上限校验） |
| GET | `/files/{fileId}` | 文件下载/预览（?inline=1 内联） |
| GET | `/mcp/{server}/resources/ui` | MCP Apps: 拉取工具 UI 资源（HtmlResource，经 CSP 注入返回） |
| GET | `/mcp/{server}/resources` | MCP Apps: 列出服务器资源 |
| POST | `/mcp/{server}/tools/{tool}` | MCP Apps: 卡片工具调用代理（ask 工具 403 + needsConfirm 走确认流） |
| POST | `/mcp/ui-context` | MCP Apps (4.7): 静默更新模型上下文（ui_context 表持久化，Hook 下次调用注入） |
| POST | `/` | A2A JSON-RPC (message/send, message/stream, tasks/get, tasks/cancel, tasks/resubscribe) |

---

## 启动

```bash
cd agent-framework
mvn clean package -DskipTests            # Maven 直接构建
make package                             # 或通过 Makefile
LLM_API_KEY=... LLM_MODEL_ID=... LLM_BASE_URL=... \
  AGENT_CONFIG_DIR=/path/to/config \
  java -jar target/agent-framework-*.jar
```

Docker 部署（多阶段构建，运行时非 root 用户，JAVA_OPTS 可覆盖 JVM 参数）：

```bash
make docker-build                         # 构建 docker.io/agent-framework:latest (自动下载 OTel agent jar)
docker run -d --name agent-framework -p 8100:8100 \
  -e LLM_API_KEY=... -e LLM_MODEL_ID=... -e LLM_BASE_URL=... \
  -e AGENT_CONFIG_DIR=/config -v ./config:/config \
  agent-framework:latest
```

链路追踪（OTel Java Agent 方案，详见 [docs/tracing-design.md](docs/tracing-design.md)）：设 `OTEL_EXPORTER_OTLP_ENDPOINT` 即自动启用（ENTRYPOINT 注入 `-javaagent`），**不可**设 `OTEL_TRACES_EXPORTER=none`（会连 Agent 导出一起禁用）；`ModelIoTracingMiddleware`/`ToolCallTracingMiddleware` 额外把模型与工具调用的实际输入输出补录到 chat/execute_tool span（`gen_ai.*.messages` / `gen_ai.tool.call.*`，超长截断）：

```bash
docker run -d --name agent-framework -p 8100:8100 \
  -e LLM_API_KEY=... -e LLM_MODEL_ID=... -e LLM_BASE_URL=... \
  -e AGENT_CONFIG_DIR=/config -v ./config:/config \
  -e OTEL_EXPORTER_OTLP_ENDPOINT=http://jaeger-collector:4318 \
  agent-framework:latest
```

内网离线开发镜像（JDK 21 + Maven + 全量依赖缓存）：

```bash
make docker-build-dev  # 或 docker build -f Dockerfile.dev -t gaoyue1989/agent-framework:java-dev .
make docker-save       # 导出 tar.gz 传输到内网机器
make offline           # 进入离线容器 (挂载当前工作目录)
mvn -o test            # 容器内离线测试 (825 用例 / 跳过 4)
```

Nexus 私有源接入、离线开发完整说明见 [docs/offline-dev-image.md](docs/offline-dev-image.md)。

---

## 测试

```bash
mvn test     # 2026-09-26 实跑：1026 用例、0 失败、0 错误、4 跳过、BUILD SUCCESS（~55s，JDK 21）
             # 实跑 101 个测试类。静态注解清点为 106 文件 / 1047 个 @Test，差额 21 全部来自
             # 5 支按命名不参与 surefire 的 *IT（RedisEventLogIT 10 / SessionEventStoreCrossReplicaIT 6 /
             # S3FileStorageIT 3 / SessionUserStoreMySqlIT 1 / ThreadHistoryConfirmIT 1），需 -Dtest= 显式跑
             # 跳过的 4 例全部来自 OpenSandboxApiIntegrationTest（需真实 OpenSandbox Server 可达）
mvn -o test  # 离线模式 (离线开发镜像内)
```

### E2E（GitHub Actions 实测 + 本地可复现）

`e2e/` 目录承载 CI 级黑盒 E2E（设计文档 [docs/e2e-ci-plan.md](docs/e2e-ci-plan.md)），随 agent-framework-ci 推送 master 触发，四个 job 并行：

| job | 内容 |
|-----|------|
| e2e-core | S 基础 / F 文件上传下载 / H HITL / M MCP Apps / A A2A + U Debug 页 UI |
| e2e-multi | R 组多副本（双实例 + nginx 轮询：断连续传/kill 接管/并发 confirm 互斥/事件对账）+ U9 |
| e2e-sandbox | X 组沙箱（mock OpenSandbox Server：Shell/文件/USER 隔离/GC 降级/pending 限流） |
| e2e-plugin | P 组工具插件（`scripts/plugin-smoke.sh`，非 Playwright：现场编译插件 jar + 自起进程，断言 SPI 注册链路 / `/tools?includeInternal` 运行时注册集 / OAF reload 存活 / deniedTools 剔除 / 自定义工具三态权限） |

另有 `eval-selftest` job 跑评测飞轮离线自检（`bench/eval/flywheel.py selftest`，零网络零 LLM）——联机 `run/verify` 需真实 LLM 与共享实例，仍不进门禁。

- **mock 架构 = 真实服务录制回放**：LLM 响应来自真实 LLM 录制件（`e2e/mock/fixtures/llm/`），沙箱协议来自本机真实 OpenSandbox Server 录制件（`e2e/mock/fixtures/sandbox/`）；场景标记 `[E2E:*]` 只做路由。录制脚本 `scripts/record-*.mjs` 仅开发机使用（密钥走 .env.secrets），CI 纯回放零密钥
- 环境编排 `scripts/env-up.sh / env-down.sh / run.sh`，CI 与本地同路径；`npm run check:fixtures` 校验录制件覆盖与脱敏。`plugin-smoke.sh` 自带编排，基础设施端口由 `MYSQL_URL`/`REDIS_URL` 推导（CI 3306/6379、本地 3307/16379 同路径）
- 已知框架语义缺陷（e2e 实测定位，详见 e2e-ci-plan.md §11.3）：
  ① **HITL 批准后恢复执行时工具参数丢失**——`agent_state` 里 SDK 持久化的 `tool_use.input` 为空 `{}`，而恢复路径优先从 state 重建（覆盖了 `confirm_context` 表里完好的参数）；
  ② **非沙箱模式 write_file→present_file 断裂**——KV 同步判定用 `ToolCallDeltaEvent.getToolCallName()`，而该字段实测返回占位符 `"__fragment__"`（工具名只在 `ToolCallStartEvent` 上），故同步永不执行、present_file 读不到文件；
  ③ **`ui.app_only` 与 `permissions.tools.ask` 不能同 server 共存**——app_only 触发 registerReadOnly 路径，只读语义短路 HITL；
  ④ **ASKING 态下新 turn 被 SDK 拒绝**（与 api-thread-spec 描述不符）。
  相关用例以 test.fixme 标记或按真实行为断言
