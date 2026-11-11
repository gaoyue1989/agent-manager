# agent-framework 能力下沉 AgentScope Java 上游 — 设计与评审

**日期**：2026-11-11　**状态**：评审定稿（未实施；执行计划见 [../implementation/agentscope-upstream-sink-plan-261111.md](../implementation/agentscope-upstream-sink-plan-261111.md)）

> 编制时点快照：本地 `io.agentscope:agentscope-harness` **2.0.3**；上游对照基线 **v2.0.4 tag**（2026-10 稳定 release；main 已进 2.1.0-BETA1）。核对方法：v2.0.4 源码检出逐类比对 + release notes 逐条溯源（#3214/#3255/#3279/#3283/#3145 等）。上游结论再核时以对应 tag 检出为准。

## 1. 背景与目标

agent-framework 主代码实测 **45,674 行**（service 23.9k、controller 9.4k、sandbox 7.4k、config 2.8k、tool 814、mcp 737 及 redis/model/util，2026-11-11 master 实数），其中约 1.5~2 万行是**多副本生产基础设施**（durable SSE、turn 租约/停止、HITL 确认表、沙箱后端与池化、MCP 用户态注入、协议面多副本化）——这类能力对任何基于 harness 的多副本服务都是必需件，却以「复刻 SDK 内部行为 + 自建共享存储协调」的形态留在本地，构成最大的维护复杂度与升级脆性来源（代码注释中 14 处「字节码核对」级耦合）。

目标：**把通用基础设施下沉到 [agentscope-ai/agentscope-java](https://github.com/agentscope-ai/agentscope-java) 上游，本地只留薄适配层**，降低本地代码量与 SDK 升级成本；同时明确哪些属平台语义必须留在本地。

非目标：不改变任何业务面行为（九项必需检查门禁语义不变）；不涉及 backend/frontend 控制面。

## 2. 基线核对结论（v2.0.4 vs 本地 2.0.3）

### 2.1 上游 v2.0.4 已有（升级即得，本地对应物可删/简化）

| # | 上游能力（证据） | 本地对应物 | 升级后动作 |
|---|---|---|---|
| U1 | ASKING tool_use 持久化参数来源改为 `input` 合并，content 仅校验（`ToolCallsAccumulator` + `ToolExecutor`，#3279） | `sandbox/opensandbox/AskingContentBackfillStateStore`（174 行，ASKING content=null 写侧回填） | e2e 验证 HITL 恢复参数不丢后**删除**（连带 e2e 已知缺陷①的 test.fixme 解除） |
| U2 | `JdkHttpTransport` 明文 http:// 每请求自动降级 HTTP/1.1（`HttpTransportConfig` 默认 null=auto，#3214） | `ChatModelFactory.buildHttpClient` 强制 HTTP/1.1（h2c 升级头 400 workaround） | **删除**（保留回归 e2e） |
| U3 | `compat/glm/GLMFormatter`、`compat/deepseek/DeepSeekFormatter`（kimi/minimax 同目录） | `ChatModelFactory.applyDialect` 的 glm/deepseek 分支 | 换用 formatter，保留 vllm/sglang 分支（上游无，见 2.2） |
| U4 | `McpTool` 新增 `name`/`remoteToolName` 双名构造器 + `toolNamePrefix`（#3255） | McpToolRegistrar「裸名注册 + `mcp__{server}__{tool}` 仅缓存展示名」hack（`getName()` final 限制） | 换用双名，注册与展示名逻辑简化 |
| U5 | propagateMeta 按 server/tool 可配 + 任一关闭则 `_meta` 整体省略（`McpClientManager`，#3255） | `UserScopedMcpClientWrapper` 的出站 `reqHeader:*` 剥离 | 剥离逻辑换用；**按用户组装双通道仍本地**（见 T3） |
| U6 | `FailoverListener`（#3145）+ 同步 fallback 失败处理 | `FallbackModelWrapper` 自打日志 | 接 listener；动态包裹保留（见 2.2 ModelResolver 缺口） |
| U7 | harness `agent/gateway/SessionTurnGate`/`TurnLease` 抽象（javadoc 明言分布式实现留给控制面） | `TurnLeaseStore`/`TurnLeaseGuard` 自建调用面 | 本地租约改**实现该接口**（为 T2 下沉铺路） |
| U8 | `validateSessionId` 仅查空/长度（含 `/` 不拒；javadoc 与实现不符是上游文档缺陷） | `SandboxAwareMysqlAgentStateStore`（31 行，放宽校验） | **验证后删除**（本地 workaround 针对的拒绝行为在 2.0.3/2.0.4 实现中均不存在——待 mvn 实证确认当初触发场景） |
| U9 | 沙箱投影每 call 新鲜度检查 + SHA-256 指纹缓存（`AbstractBaseSandbox.applyWorkspaceProjectionIfChanged`） | `OpenSandbox.refreshProjectionIfStale`（对齐 SDK 私有方法） | 换用官方路径，删复刻 |
| U10 | harness `SessionTranscriptWriter`（turn 边界 JSONL 归档、幂等、compaction 引用） | `SessionMessageArchiveStateStore`（MySQL 表归档） | **形态不同不直接替换**；概念对齐，下沉时以 T1 事件日志/归档钩子统一 |

### 2.2 上游缺口（下沉候选；库级均无，`agentscope-service` 整产品里的 PG/JPA 实现不可复用）

| # | 缺口 | 上游现状 | 本地实现（行数） |
|---|---|---|---|
| G1 | 库级 durable 事件日志（append 单调 seq / afterSeq 游标 / 跨副本订阅） | core/harness 无；service 产品 `SessionEventLog` 是 JPA+PG LISTEN/NOTIFY | SessionEventStore 690 + RedisEventLog 517 + SessionEventTailer 461 = 1,668 |
| G2 | 跨副本 turn 互斥 Redis/MySQL 实现；interrupt 持久化与跨副本派发 | `LocalSessionTurnGate` 进程内；`InterruptControl` runtime-only（javadoc 自认） | TurnLease 三件套 454 + turn_stop/intent 派发链 900 = 1,354 |
| G3 | per-call MCP 用户态注入封装（header/arguments 双通道 + 请求级透传 + fail-closed） | 只有原语（propagateMeta、`httpRequestCustomizer`），无按声明组装层 | mcp/ 包全量 737（wrapper 293 / ChatRequestHeaders 135 / middleware·customizer·rule 等） |
| G4 | OpenSandbox 沙箱后端 | 后端仅 docker/k8s/e2b/daytona/agentrun；贡献面清晰（继承 `AbstractBaseSandbox`） | OpenSandbox 2,010 + Client 494 + spec/options/state 514 = 3,018 |
| G5 | 沙箱池化生命周期（TTL 续期/配额/LRU/绑定） | 无池（复用=isolation key resume + guard） | LifecycleManager + BindingStore + RenewalRegistry = 1,764 |
| G6 | MCP 看门狗探活重建 / read_only 强制注册 / fail-soft 启动 | 三者皆无 | McpConnectionWatchdog 212 + registrar 内 ~150 |
| G7 | 会话级 ModelResolver / vllm/sglang 方言 / per-call ToolFilter | 均无（仅 SkillFilter 有 per-call 钩子） | SessionModelMiddleware 122 + ToolFilterMiddleware 239 + 方言分支 ~60 |
| G8 | A2A 旧客户端字段容错 / TaskStore 读 AgentState / Redis 版 AgentProtocolEventBus / DB 版 TaskRepository | 全部只有进程内默认实现；EventBus javadoc 明说"应用自己提供 Redis adapter" | A2AController 兼容层 ~100 + MySqlTaskStore 162 + ProtocolRedisEventBus 275 |
| G9 | X-Forwarded-Prefix 自引用 URL / SkillZip 安全解压 / GenAI 内容追踪 / S3 文件存储 | 均无 | ExternalUrlSupport 80 + SkillZipSupport 329 + Tracing 中间件 ~400 + storage/ 255 |
| G10 | AgentStateStore write-through/observer 装饰钩子 | 接口无；本地三个装饰器靠"显式覆写全部 default 方法"脆弱手法 | Archive/Backfill 装饰器 ~370 |

### 2.3 上游缺陷残留（本地有补丁，提 bugfix PR 后可消）

| # | 缺陷（v2.0.4 仍存在） | 本地补丁位置 |
|---|---|---|
| B1 | `FilesystemUtils.countOccurrences` 空 needle 死循环（`idx+=0`） | WorkspaceReader.writeKvFile 短路 |
| B2 | `AgentStartEvent.getSessionId()` 主链路恒 null | 四处兜底 |
| B3 | `McpClientManager.removeMcpClient` 不摘手动注册的裸名工具 | registrar clearServer 双路径 |
| B4 | `MysqlAgentStateStore.slotId`/`normalizeUser` private 未公开 | 两处字节码复刻（Archive 装饰器、SkillCacheScope） |
| B5 | `ToolCallDeltaEvent` 名字恒 `__fragment__`（上游自己也用 toolCallId 映射绕） | ChatStream/Tailer/Serializer 按 id 分片 |
| B6 | `RemoteFilesystem.edit` 空 needle 死循环 + `write` 已存在即失败 + glob miss 全量扫描 | WorkspaceReader 三补丁 |

## 3. 分层决策

```
P0 升级 2.0.3→2.0.4        换用 §2.1 十项，删本地 workaround（不依赖上游任何新合入）
P1 上游 bugfix PR           §2.3 六项小 PR（建立信任 + 消最脆耦合，不直接减行数）
P2 Tier1 下沉贡献           G1~G5 五大件（上游合入后本地换用，主要瘦身来源）
P3 Tier2 择机下沉           G6~G9（各 100~400 行，随上游节奏）
—— 不下沉：平台语义，永留本地（§3.1）
```

### 3.1 不下沉清单（平台特有）

- **OAF 体系**：frontmatter 解析、动态 reload（指纹分流/原子 swap）、`/config` 只读约定、WorkspaceInitializer
- **网关翻译层**：gw-hash 复刻、peer→真实 userId 反查（session_user）、`X-User-Id` 契约——长期解法是推动上游网关保留 canonical key（列入 P1 沟通项），而非把翻译层搬上去
- **业务语义**：RemoteConfirmBridge、L4 用户技能（tombstone/栅栏仲裁）、present_file 交付链、MCP Apps（ui:// 规范未稳定）、debug/monitor 页、沙箱 K8s 名注入
- **部署形态**：`{原库}_{k8s名}` 分库与 Redis prefix 约定（issue #142）

## 4. Tier1 各项下沉设计

### T1 durable 事件日志（G1）→ `extensions-redis`
- **形态**：core 或 harness 定义 `SessionEventLog` SPI（`append(sessionId, event)` 单调 seq / `listAfter(sessionId, afterSeq)` / `subscribe(sessionId, afterSeq): Flux`）；`extensions-redis` 提供 Redis Streams 实现（XADD ID=`<seq>-0`、ZSET reply 索引、cluster hash tag、AOF/noeviction 自检）
- **本地保留**：SessionEventBus（词表/replyId 注入属平台协议）、Tailer 的终态探测与 `TailFrameRenderer`（协议渲染留在本地，存储游标语义随 SPI 上移）
- **正确性资产随迁**：seq 播种只抬不降、丢租约 abandonTurn 禁 flush、非单调 XADD 原子拒绝
- **前置**：先开上游 issue 对齐 SPI 形态（Redis Streams vs PG NOTIFY 是上游 service 产品的既有分歧点）

### T2 跨副本 turn 互斥 + 停止（G2）→ `extensions-redis`/`extensions-mysql`
- **形态**：实现上游已备好的 `SessionTurnGate`（Redis SET NX+Lua / MySQL PK 冲突接管两版）；interrupt 跨副本派发提设计讨论（共享存储标记 + 属主轮询 + reply_id 代际栅栏，`InterruptControl` 持久化由上游定夺）
- **本地保留**：TurnStopService 的平台状态机（409 语义）、TurnControlRegistry 的 gw-hash 键翻译

### T3 per-call MCP 用户态注入（G3）→ core `tool/mcp`
- **形态**：`McpClientBuilder` 增加 per-call userHeaders 声明（headers/arguments 双通道 + `on-missing: deny|passthrough`），取值源 = McpMeta 键（内建 userId + 可注入取值函数，本地的 `reqHeader:<头名>` 请求级透传作为参考实现之一）；出站 `_meta` 卫生复用 U5
- **本地保留**：`McpUserContextMiddleware` 的 session_user 反查（平台网关特有，以取值函数注入）
- **对标**：deer-flow headers_from_context，跨框架刚需

### T4 OpenSandbox 后端（G4）→ 新模块 `extensions-sandbox-opensandbox`
- **形态**：继承 `AbstractBaseSandbox`（doSetup/doHydrate/doPersist/doDestroy/getWorkspaceRoot 五抽象点）+ `SandboxClient` 契约实现；随迁实战补丁：`SandboxFileTransfer` 单文件直传（免全量 tar hydrate）、resume 用 `connector().connect()`（resume 端点对 Running 409）、poolRef 时 image/resourceLimits 约束
- **本地保留**：K8s 名注入、exec→execd 翻译器中依赖 SDK 私有 shell 模板的部分需加模板版本探测降级后再迁；生命周期挂钩（T5）不随后端走
- **价值**：上游五个后端之外补第六个，贡献面已被上游自身结构定义清楚

### T5 沙箱池化生命周期（G5）→ harness 可选组件
- **形态**：借出决策树/配额 CAS/LRU 改绑/TTL 续期注册表抽象为可选组件；busy 判定与 HITL hold 窗抽为接口（本地 turn_lease/confirm_context 两耦合点以平台实现注入）；SQL 方言抽象（现全 MySQL 方言 + DB 时钟 UTC 假设需一并审视）
- **顺序**：五件中工作量最大、抽象争议最多，放 Tier1 最后；**若上游已有规划则改为对齐其形态**

### 通用纪律
- 每项下沉后本地保留**薄适配层**（接口实现 + 平台语义注入），业务面与 e2e 门禁零改动
- 本地换用上游能力时按项目原则**直接删本地实现**（不留双轨），e2e 门禁做行为等价验收
- 上游 PR 独立于本仓分支推进；本仓仅在对应上游 release 合入后做「升级 + 换用 + 删旧」一个 PR

## 5. 架构约束核对（原则二：多副本无状态 + 服务隔离）

- 所有下沉项均为**共享存储协调机制的上移**（MySQL/Redis 裁决语义不变），不引入任何 Pod 本地正确性状态；T1/T2 落地后 `/subscribe`、`/status`、confirm 仍全基于共享存储裁决，e2e-multi / e2e-protocol-multi 双副本门禁保持
- T5 池化下沉不改 per-service 库隔离与 Redis prefix 约定（表名/key 前缀由部署侧配置，上游组件不得内置 agent 维度假设）
- 控制面零改动；P0 升级仅动 agent-framework（pom + 装配工厂 + 受 breaking 影响面）

## 6. 风险与开放问题

| 风险 | 缓解 |
|---|---|
| 上游接收周期不可控（Tier1 五件均为新模块/新 SPI） | P0 立得不受影响；每项先 issue 对齐再动工；本地实现维持到上游 release 合入，不做超前删除 |
| 2.0.4 breaking 面撞击装配层 | 迁移清单已逐条比对（activeRc/getRuntimeContext 移除、RegisteredSkill/ToolkitAware 删除、AGUI ToolInjection→ToolRequestConfig、OpenAIMessage.cacheControl 移除、AGUI 消息 ID 规则、saveVersioned 签名）；影响集中在 HarnessAgentFactory（655 行）与 AGUI codec |
| U1/U8/U9 的"可删"结论基于源码比对，未实跑验证 | P0 内各设一个验证步骤（HITL 恢复 e2e / 沙箱 slot 持久化 IT / 投影指纹基准），验证不过则该项保留并记入上游 issue |
| 下沉后本地与上游版本强耦合 | pom 钉上游 release 版本；换用 PR 必须同版本回归九项必需检查 |
| SPI 形态与上游演进（2.1.0-BETA1 / main 的 AgentSession 方向）分叉 | T1 动工前核对 main 分支 AgentSession 落地形态，若上游已在做同类抽象则改为对齐贡献 |
