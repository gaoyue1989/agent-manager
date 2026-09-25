# OAF 包动态加载 + MCP 动态 Reload 方案

> **状态：✅ M1/M2 已实施 + 部署环境 E2E 验证通过（2026-09-25）**。目标：OAF 配置包（PVC `/config`，subPath 只读挂载）
> 内容**原位更新后无需重启 Pod** 即可生效：frontmatter（系统提示词/model/权限/mcpServers 声明）
> 与 MCP server 连接（增/删/改/重连）运行时 reload。skills 目录动态加载已由
> [oaf-skills-dynamic-loading-plan.md](oaf-skills-dynamic-loading-plan.md) 完成（M1/M2），本方案不重复覆盖。
>
> **范围：仅 agent-framework**。平台 backend（Go）零改动——包内容如何更新不由平台保证，
> reload 只对"PVC 上文件原位变化"这一事实生效（与 skills 动态加载的平台边界完全一致）。
>
> **实施记录（2026-09-25）**：
> - 实现与方案的差异：①MCP 配置目录布局为 `/config/{server}/`（McpToolRegistrar.resolveMcpDir 口径），指纹扫描按 configDir 下目录树（排除 skills/）；②`A2aAgentRefHolder`/`OafReloadService` 经 `List<Object> customTools` 泛型收集被扫为装配候选，引入 `tool/CustomTool` 标记接口收窄候选 + holder 改 `ObjectProvider` 惰性注入，消除两处 Spring 循环依赖；③OafReloadService 的 build 依赖用构造器注入（`@Autowired` 方法注入实测不触发）；④`scope=mcp` 会重解析 frontmatter（声明增删即时生效并同步 holder）；⑤GET /admin/reload 提供只读状态。
> - 部署环境 E2E（独立进程 + 本地 MySQL/Redis + mock MCP server，E-RL-1~5 全 PASS）：fail-soft 修复后 reload 恢复工具（pid 不变）→ 经 /mcp 代理真实调用成功；声明移除 → 工具下线；整包重建 → 提示词/卡片版本/MCP 全量重注册；非法 frontmatter → 500 + 旧配置继续服务 → 修正后重建成功；重复触发 → noop 幂等。
> - SIGHUP 通道与定时扫描（M4）未实施；多副本各自触发语义见 §4.3。

---

## 一、结论先行

**官方 agentscope-java 2.0.3 没有"OAF 配置热加载"的现成开关，但两条官方路径可以搭出完整方案，且都已在本项目验证过先例：**

| 官方机制 | 说明 | 状态 |
|----------|------|------|
| `HarnessAgent` 可重建 + `AgentRuntimeService.setAgent()` | 官方 dataagent 示例的"配置变更→重建 agent"模式（写 workspace/tools.json 后按会话重建 HarnessAgent） | `setAgent()` 已存在（AgentRuntimeService.java:183），只差重建逻辑 |
| `Toolkit.registerMcpClient / removeMcpClient / removeTool`（core 公开 API） | MCP 连接运行时增删：remove 关连接摘工具 → register 重连重列工具，即为一次完整 reload | Toolkit 公开方法，无需改 SDK |
| `FileSystemSkillRepository` 每轮重扫 | skills 动态加载已用此机制上线 | **已完成**（本方案不动） |
| `DynamicSubagentsMiddleware`（每推理步重扫 `subagents/*.md`） | subagent 定义热加载的官方实现 | 本项目子代理走 `WorkspaceInitializer.writeSubagents` 静态生成，**未启用**（可选增强，见 §8） |
| tools.json / sysPrompt 文件 watch | **无**。`ToolsConfigLoader.load()` 仅在 `HarnessAgent.Builder.build()` 时调用一次 | 官方无，需自研触发 |

**因此方案 = 「MCP 原地 reload（官方 Toolkit API）+ 整包重建 agent（官方 dataagent 模式）+ 文件变更感知（唯一自研点，SIGHUP/端点/定时三通道）」**，不 fork SDK、不引新依赖。

---

## 二、现状与差距

### 2.1 现有启动期装配链（reload 要打通的对象）

```
Pod 启动
 └─ AgentScopeConfig（@Configuration，全部单例 Bean）
     ├─ oafConfig(OafConfigLoader)          ← /config/AGENTS.md frontmatter 解析一次
     ├─ mcpManager / mcpConfigs             ← mcp-configs/{server}/config.yaml 读一次
     ├─ harnessAgent(...)                   ← HarnessAgent 单例：
     │    ├─ sysPrompt(oafConfig.systemPrompt())
     │    ├─ toolkit：customTools + McpToolRegistrar.registerAll（MCP 连接+工具注册一次）
     │    ├─ workspace：WorkspaceInitializer 写 AGENTS.md/tools.json/subagents（存在即跳过）
     │    └─ skillRepository：/config/skills L2 仓库（每轮重扫，已是动态 ✅）
     ├─ AgentRuntimeService(harnessAgent)   ← agent.call/streamEvents 持有引用
     ├─ A2AServerConfig(harnessAgent)       ← A2A server 持有引用
     ├─ ChannelConfig(harnessAgent)         ← ChatUiChannel 持有引用
     └─ HarnessAgentRunner(agent)
```

MCP 链路的三个记忆缓存（`McpToolRegistrar`）：`registeredTools`（server:tool → ToolInfo）、
`uiMappings` / `toolPermissions` / `readOnlyServers` / `startupRequired` / `destructiveHints`（server 级）。
另有 `McpResourceProxy` 独立懒连接 `McpSyncClient`（server → client）。

### 2.2 差距清单

| # | 差距 | 影响 |
|---|------|------|
| G1 | frontmatter（sysPrompt/model/permission/mcpServers 声明）变更无感知、无重载路径 | 改提示词/权限/mcpServers 必须重启 Pod（republish） |
| G2 | MCP 连接无 reload：server 下线后 fail-soft 跳过的工具，server 恢复后不回来；tools.json 变更不生效 | 远端 MCP 独立演进时必须重启 |
| G3 | `OafConfig`、`mcpConfigs` 是不可变 Bean，被 10+ 类直接注入引用 | 引用刷新需要收敛读取点（见 §4.3） |
| G4 | `WorkspaceInitializer` 生成文件"存在即跳过" | 即使重建 agent，旧 workspace 文件（AGENTS.md/tools.json/subagents）会挡住新内容 |
| G5 | A2A agent-card、/skills、/debug/config、/info 读静态 `OafConfig` | 配置变更后对外元数据不更新 |

### 2.3 官方参考（agentscope-java）

- **dataagent 示例**（`agentscope-examples/agents/agentscope-dataagent`）：REST 写 workspace `tools.json` / `subagents/*.md` → `catalogService.invalidateUca(...)` → 按会话重建 `HarnessAgent`。即"配置落盘 + 重建"的官方范式。
- **Toolkit MCP API**（`agentscope-core/io.agentscope.core.tool.Toolkit`，2.0.3 javadoc 与 main 一致）：
  - `Mono<Void> registerMcpClient(McpClientWrapper)` / `registration().mcpClient(w)...apply()`
  - `Mono<Void> removeMcpClient(String mcpClientName)`（按注册名关连接并摘除该 client 全部工具）
  - `void removeTool(String name)` / `void closeMcpClients()`
- **注意**：`McpClientWrapper` 无内置自动重连/健康检查；`listTools()` 每次注册实时拉取。重连 = remove + register 手动编排。

---

## 三、总体设计

### 3.1 两条 reload 路径（按变更类型分流）

```
PVC /config 原位变化（文件级）
   │
   ├─ A. mcp-configs/{server}/** 变化，AGENTS.md 未变
   │      → MCP 原地 reload：toolkit.removeMcpClient(name) + registerAll(server)
   │        （连接级重建，工具列表实时刷新；零中断其他 server）
   │
   └─ B. AGENTS.md 变化（sysPrompt/model/permission/mcpServers 声明/任何 frontmatter 字段）
          → 整包重建 HarnessAgent（官方 dataagent 模式）：
            WorkspaceInitializer 强制重写 → 新 OafConfig → 新 Toolkit → MCP 全量注册
            → 原子切换 AgentRuntimeService/A2A/Channel 持有的引用
            → 旧 agent 的 MCP 连接关闭
```

**为什么不全部走"重建 agent"：** MCP 原地 reload 粒度更细（单 server 故障重连不牵动整个 agent），且不需要处理 agent 切换瞬间的会话边界；**为什么不全部走"原地 reload"：** sysPrompt/model/权限是 HarnessAgent 构建参数，Toolkit API 覆盖不了，SDK 也未提供改 sysPrompt 的运行时接口。

### 3.2 生效语义（关键决策）

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 变更触发 | 三通道可选：`POST /admin/reload`（显式）+ `kill -HUP 1`（SIGHUP）+ 可选定时扫描（默认关） | 不自研 inotify watcher（容器内 PVC bind mount 可用 WatchService，但多一路常驻线程与误触发面）；三通道都收敛到同一个 `ReloadService` |
| 生效时机 | **下一轮对话**（新 turn 起用新 agent/toolset）；进行中 turn 不打断 | 与 skills 动态加载的生效语义一致；turn 级原子切换，无半新半旧 |
| 会话记忆 | 保留（agent_state/agent_fs 在 MySQL，重建 agent 不丢上下文） | HarnessAgent 是无状态外壳，状态在 DistributedStore |
| MCP 重建失败 | 新 agent 内 fail-soft：启动语义原样保留（`startup.required=true` 的 server 失败 → 整次 reload 拒绝，保持旧 agent 运行） | 复用现有 fail-soft / required 语义，reload 永不比启动更严格 |
| 切换并发 | 读引用用 volatile/AtomicReference；重建期间到达的 turn 落在旧或新 agent，二选一原子 | 避免锁住对话路径 |
| `/config` 只读 | 方案永不写 `/config`；workspace 重写只发生在容器层 `/workspace/.agentscope/workspace` | PVC 保持只读挂载 |

### 3.3 与 skills 动态加载的关系

不改动。L2 仓库每轮重扫逻辑继续生效；重建 agent 时 `builder.skillRepository(...)` 按同一方式重新注册（目录存在性检查逻辑保持）。

---

## 四、详细设计（文件级改动清单）

### 4.1 新增 `service/OafReloadService`（核心，唯一新增有状态服务）

**文件**：`src/main/java/io/agentmanager/framework/service/OafReloadService.java`（新增）

职责：reload 编排入口，三通道触发的共同落点。

```
reloadMcp(String serverName)        // A 路径：单 server 原地 reload
reloadMcpAll()                      // A 路径：全部 server（对比 mcpConfigs 声明，增/删/改差异处理）
reloadAgent()                       // B 路径：整包重建
reload()                            // 总入口：比对 /config 指纹，自动分流 A/B（或先 B 后 A 一次完成）
```

实现要点：
1. **指纹比对**：对 `/config/AGENTS.md` + `/config/mcp-configs/**` 计算 mtime+size 指纹（对齐
   `FileSystemSkillRepository` 的快照口径），与上次加载快照比对决定走 A/B/无操作；
2. **MCP 原地 reload**（A）：
   - 重建前 `toolkit.removeMcpClient(serverName)`（官方 API，关连接摘工具）；
   - `McpToolRegistrar` 补一个 `registerOne(toolkit, serverName)` 公开方法（现有 `registerAll` 内循环体提取复用，不改行为）；
   - 同步刷新 `registeredTools`/`uiMappings`/`toolPermissions` 等 server 级缓存（先清该 server 旧键再写入）；
   - 声明中已删除的 server：仅 remove；新增 server：仅 register；
3. **整包重建**（B）：提取 `AgentScopeConfig.harnessAgent(...)` 主体为可重复调用的
   `HarnessAgentFactory`（@Bean 保留兼容，内部委托 factory），重建流程：
   `OafConfigLoader.load()` → `WorkspaceInitializer.reinitialize(...)`（见 4.2）→ 新 Toolkit +
   `McpToolRegistrar.registerAll`（旧 toolkit 用 `closeMcpClients()` 收尾）→ 新 HarnessAgent →
   原子发布引用（见 4.3）→ 权限覆盖校验 `verifyToolCoverage` 照旧执行；
4. **失败回滚**：任何异常都保持旧 agent/旧连接继续服务，reload 返回错误详情（结构化，供平台/运维诊断）；
5. **并发防抖**：`AtomicBoolean reloading`，进行中重复触发直接返回"reload in progress"；
6. **进度可观测**：复用 INFO 日志口径（`[Reload] ...`），结果含每个 MCP server 的成败与工具数。

### 4.2 修改 `service/WorkspaceInitializer`

**文件**：`src/main/java/io/agentmanager/framework/service/WorkspaceInitializer.java`

- 新增 `reinitialize(Path baseDir, OafConfig oafConfig)`：与 `initialize()` 相同目录目标，但
  AGENTS.md / tools.json / subagents 一律**覆盖重写**（消除 G4"存在即跳过"）；
- `initialize()` 保持原语义（Pod 启动路径不变，存在即跳过——保留用户手改 workspace 的容忍度）；
- 注意：`/workspace` 是容器层 + agent_fs 双层，覆盖重写只动本地 AGENTS.md/tools.json/subagents
  三个生成文件，不触碰用户运行时文件与 per-user 技能（L4）。

### 4.3 引用收敛：`OafConfig` / agent 的动态读取（消除 G3、G5）

现状 `OafConfig` 以构造器注入散布 10+ 类。**不做大面积改造**，只把"配置变更后需要看到新值"的读取点收敛为一个动态门面：

**新增** `config/OafConfigHolder`（极薄，volatile 持有当前 `OafConfig`，`get()` 返回最新实例）：

| 消费方 | 现状 | 改为 |
|--------|------|------|
| `AgentRuntimeService`（agent 引用） | 构造注入 + `setAgent()` | 构造注入 holder；`setAgent()` 保留，新增经 holder 的原子切换（`volatile agent` 字段已具备，直接赋值即可） |
| `AgentCardController`（A2A 卡片） | 注入 `OafConfig` | 注入 holder（卡片 name/version/skills 动态可见） |
| `InfoController` / `DebugApiController`（/debug/config/oaf） | 注入 `OafConfig` | 注入 holder |
| `ToolController`（MCP summaries） | 注入 `mcpConfigs` Bean | 改读 `McpManager.getMcpSummaries(current)`（mcpConfigs 的动态版） |
| 其余（`A2uiService`、`WorkspaceReader`、`A2AServerConfig` 装配参数等） | 构造注入 | **不动**——它们的值（catalogId、agentName）实际不随包更新变化；A2A server 持有的 agent 引用经 holder 刷新 |

> 多副本一致性：多副本部署时 reload 只落在收到触发的那一副本（SIGHUP/端点都是单 Pod 动作）。
> 其余副本在下一次自身触发前保持旧配置。这与 skills 动态加载的多副本语义一致（每副本各自感知），
> 不引入跨副本协调（平台 republish 滚动重启仍是全量一致换包的权威路径）。

### 4.4 触发通道实现

**新增** `controller/AdminReloadController`（`POST /admin/reload`，可选 `?scope=mcp|agent|auto`）：

- 返回结构化结果：`{scope, fingerprintChanged, agentsRebuilt, mcpServers: [{server, action, tools, error}]}`；
- 鉴权：与现有管理面一致（本服务无独立管理端口，走宿主 nginx 的 `/agent/{name}` 入口；如需收紧，
  可加环境变量开关 `AGENT_RELOAD_TOKEN`，缺省仅集群内可调）；
- `scope=auto`（默认）：指纹比对自动分流。

**SIGHUP**：`AgentFrameworkApplication` 启动后注册 `Signal.handle("HUP", ...)` → 调 `OafReloadService.reload()`。
JDK 9+ com.sun.security.sig 段可用 `sun.misc.Signal`（已在用 JDK 17/21 容器）——若担心内部 API，
可用备选：`kill -USR1` 同理；两者都不引依赖。失败（非容器环境）仅 WARN 不阻断启动。

**定时扫描（可选，默认关）**：`AGENT_RELOAD_POLL_SECONDS`（缺省 0 = 关闭）。开启后每 N 秒指纹比对，
变化即 `reload()`。供"完全无人值守"场景；默认关闭避免 PVC 慢同步半写状态（先写一半被扫到）——
三通道里显式触发是推荐用法，触发方保证"写完再触发"。

### 4.5 `McpToolRegistrar` / `McpManager` 配套改动

**文件**：`service/McpToolRegistrar.java`、`service/McpManager.java`

- `McpToolRegistrar`：
  - 提取 `registerAll` 循环体为 `registerOne(Toolkit, OafConfig.McpServerConfig)`（public）；
  - 新增 `clearServer(String serverName)`：从 `registeredTools`/`uiMappings`/`toolPermissions`/
    `readOnlyServers`/`startupRequired`/`destructiveHints` 移除该 server 全部条目；
  - `registerAll` 行为不变（内部改调 `registerOne`，语义等价）；
- `McpManager`：
  - `loadConfigs` 已是纯函数（入参 mcpServers → 重新读盘），可直接复用为动态版；
  - 新增 `reloadCurrentConfigs(OafConfig)` 返回新 configs（供 B 路径与 ToolController 动态读）；
- `McpResourceProxy`：新增 `evictClient(String serverName)`（现有 `clients` map 是
  "失败不缓存"，补一个主动失效即可——A/B 路径都调用，下次访问自然重连新配置）。

### 4.6 明确不做的事

- **不自研 inotify/WatchService watcher**（触发收敛为显式三通道；官方 skill 模型也是"每轮重扫"而非 watcher）；
- **不改 SDK、不 fork harness**：全部用公开 API（Toolkit / builder / setAgent）；
- **不做单工具级 diff reload**（`toolkit.removeTool(name)` 虽存在，但 MCP 工具与连接、权限、UI 映射
  强耦合，按 server 整体重建语义清晰且足够快）；
- **不做跨副本 reload 广播**（理由见 4.3）；
- **不改平台 backend**（与 skills 动态加载同一边界）。

---

## 五、生效链路（时序推演）

### 5.1 场景一：MCP server 恢复/换地址（A 路径）

```
T0  server 'travel' 启动期不可达，fail-soft 跳过（现有语义）
T1  运维在 PVC 上修正 mcp-configs/travel/config.yaml（原位文件级更新）→ 触发 reload
T2  reloadMcpAll()：指纹显示仅 mcp-configs 变化
    → 'travel' 不在已注册集合 → registerOne：buildClient → initialize → listTools → 注册工具
    → 刷新 registeredTools / 权限缓存 / McpResourceProxy.evictClient
T3  下轮对话：LLM 工具列表含 travel 工具；/tools、/mcp summaries 同步更新
    （权限规则：collectPermissionRules 基于 registeredTools 重建，随 reload 一并生效）
```

### 5.2 场景二：改系统提示词 / 权限 / 换模型（B 路径）

```
T1  PVC 上更新 /config/AGENTS.md → POST /admin/reload（或 SIGHUP）
T2  reloadAgent()：
      OafConfigLoader.load()（新 OafConfig，解析告警语义与启动一致）
      → WorkspaceInitializer.reinitialize（覆盖 AGENTS.md/tools.json/subagents）
      → 新 Toolkit：customTools 重注册（deniedTools 新值生效）+ registerAll（全部 MCP server）
      → HarnessAgentFactory.build（sysPrompt/permission/memory/compaction 全按新配置）
      → verifyToolCoverage 校验
      → AtomicReference 切换：AgentRuntimeService.agent / A2A holder / Channel holder
      → 旧 agent.closeMcpClients()（旧 toolkit 连接关闭，不影响的：McpResourceProxy 独立连接已 evict）
T3  进行中的 turn 继续在旧 agent 上完成（引用已捕获）；下一 turn 走新 agent；
    会话历史/记忆在 MySQL（agent_state/agent_fs），新 agent 无缝续读
T4  A2A agent-card：下一请求即返回新 name/version/description/skills
```

### 5.3 失败场景

- 新 AGENTS.md frontmatter 语法错误 → `OafConfigLoader.load()` 抛异常 → reload 中止，旧 agent 服务不中断，返回错误详情；
- `startup.required=true` 的 server 在重建时不可达 → 整次 reload 拒绝（保持旧 agent），fail-soft 的 server 照常跳过；
- reload 与对话并发：turn 已捕获旧引用则跑完在旧 agent；未开始则落在新 agent——两者都原子，无中间态。

---

## 六、测试方案

### 6.1 单元测试（随代码同 PR）

| 用例组 | 覆盖 |
|--------|------|
| `OafReloadServiceTest` | ① 指纹不变 → no-op；② 仅 mcp-configs 变 → A 路径；③ AGENTS.md 变 → B 路径；④ B 路径中 MCP 失败（required）→ 整体回滚旧引用；⑤ 并发 reload 防抖；⑥ WorkspaceInitializer.reinitialize 覆盖旧文件 |
| `McpToolRegistrarTest`（扩展） | registerOne/clearServer：单 server 重注册后 registeredTools 正确；移除后 /tools 与权限缓存同步清空 |
| `AgentRuntimeServiceTest`（扩展） | agent 原子切换后新 turn 用新 agent；进行中 turn 不受影响 |
| `AdminReloadControllerTest` | scope 参数分流；鉴权开关；错误结构化返回 |

### 6.2 E2E（真实集群，沿用 e2e/skills-dynamic-e2e.sh 的 PVC 直写手法）

| 场景 | 步骤 | 断言 |
|------|------|------|
| E-RL-1 MCP 恢复 | 发布含 fail-soft MCP 的包 → 直写 PVC 修正 config.yaml → reload → 对话 | 新工具可用；Pod restartCount 不变 |
| E-RL-2 MCP 移除 | 删除 frontmatter mcpServers 条目 + reload | 工具消失、/mcp summaries 更新 |
| E-RL-3 提示词热更 | 直写新 AGENTS.md → reload → 对话 | 新提示词生效（行为可判定 marker）；会话历史延续 |
| E-RL-4 A2A 卡片刷新 | 换包版本号 → reload | agent-card version/skills 更新 |
| E-RL-5 失败回滚 | 写入非法 frontmatter → reload | 返回错误；旧配置继续服务；修正后 reload 成功 |
| E-RL-6 多副本 | 2 副本，仅对副本1 reload | 副本1 生效；副本2 不变（预期行为断言） |

### 6.3 回归门禁

`mvn test` 全绿 + e2e 既有脚本无回归；skills 动态加载 E2E（skills-dynamic-e2e.sh）复跑通过（重建 agent 路径不得破坏 L2 重扫）。

---

## 七、里程碑

| 阶段 | 内容 | 门禁 |
|------|------|------|
| **M1** MCP 原地 reload | 4.5 + OafReloadService A 路径 + AdminReloadController(scope=mcp) + 单测 | `mvn test` 全绿 |
| **M2** 整包重建 | HarnessAgentFactory 提取 + WorkspaceInitializer.reinitialize + OafConfigHolder 收敛 + B 路径 + 单测 | 同上 + AgentCard/Debug 测试适配 |
| **M3** 集群验证 | E-RL-1~6 | 全 PASS |
| **M4**（可选） | SIGHUP 通道 + 定时扫描 | 同上 |

M1 可独立交付价值（MCP 故障自愈/换地址不重启），M2 是完整动态包能力。

---

## 八、开放问题（评审定）

1. **subagent 动态化是否顺带启用**：官方 `DynamicSubagentsMiddleware`（每推理步重扫 `subagents/*.md`）
   可让 OAF `agents:` 声明的子代理也动态化（B 路径重建已覆盖 subagents 重写，但不启用 middleware 时
   子代理集合仍是 build 时冻结）。建议作为独立后续项（启用即一行 builder 配置 + E2E），不混入本方案。
2. **reload 触发的权限边界**：`/admin/reload` 是否需要独立 token（`AGENT_RELOAD_TOKEN`）？
   现状本服务所有端点都在集群内网入口后，倾向先不加（与 /debug/* 同级），平台化时再收紧。
3. **指纹口径**：mtime+size 对 PVC bind mount 足够（skills 方案已验证）；是否需要内容 hash？
   倾向不需要（mtime+size 短路是官方仓库缓存同款口径）。

---

## 附录 A：官方参考索引

- dataagent 运行时改配置范式：`agentscope-examples/agents/agentscope-dataagent`（AgentToolsController / SessionAgentManager）
- Toolkit MCP API：`agentscope-core/.../tool/Toolkit.java`（registerMcpClient / removeMcpClient / closeMcpClients / registration()）
- 连接生命周期：`agentscope-core/.../tool/mcp/McpClientWrapper.java`（initialize/listTools/callTool/close，无自动重连）
- MCP 配置加载边界：`agentscope-harness/.../tools/ToolsConfigLoader.java`（build 期一次性，无 watch）
- subagent 动态重扫：`agentscope-harness/.../middleware/DynamicSubagentsMiddleware.java` + `subagent/AgentSpecLoader.java`
- aistio 控制面热更新（重型方案，本项目不采用）：`agentscope-service/aistio/internal/controller/config_watcher.go`

## 附录 B：本项目现状代码索引

- 装配链：`config/AgentScopeConfig.java:327` harnessAgent（单例）、`:146` oafConfig、`:158` mcpManager/mcpConfigs
- MCP：`service/McpToolRegistrar.java:94` registerAll（fail-soft/required 语义）、`:203` registeredTools 缓存、`service/McpManager.java:31` loadConfigs、`service/McpResourceProxy.java:39` 独立连接缓存
- agent 引用持有：`service/AgentRuntimeService.java:42`（`setAgent` :183 已存在）、`config/A2AServerConfig.java:27`、`config/ChannelConfig.java:13`、`service/HarnessAgentRunner.java:19`
- workspace 生成：`service/WorkspaceInitializer.java`（存在即跳过 = G4）
- skills 动态加载（已上线，本方案不动）：`config/AgentScopeConfig.java:410`（L2 仓库注册）
- 前置方案：[oaf-skills-dynamic-loading-plan.md](oaf-skills-dynamic-loading-plan.md)（平台边界、PVC 文件级更新语义、E2E 手法均沿用）
