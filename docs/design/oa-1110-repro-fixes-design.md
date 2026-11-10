# oa-1110 复现问题批量修复设计（技能禁用闭环 / 压缩前归档 / 沙箱建箱韧性 / stop 回归）

> 状态：实施中（2026-11-10）。来源：2026-11-10 测试环境（`agent-framework-oa.region-c86-test`，镜像基线 f3aba60）复现报告。
> 分支：`fix/oa-1110-repro-issues`（基线 origin/master a4eae87）。四个问题在基线代码的存在性均已核实，根因位置见 §1。

## 0. 背景与范围

1110 复现报告共 9 项，其中代码可修、且在当前基线核实存在缺口的为：

| 序号 | 问题 | 复现结论 | 本设计范围 |
|---|---|---|---|
| #1 | 并发生成 PPT 失败（建箱超时 / 数据面 502） | 已复现 | agent-framework 侧韧性（§2.3）；池 warm 水位属平台/OpenSandbox 侧，另案 |
| #7 | 历史对话部分执行过程缺失 | 已证实（trace 20 次 vs history 15 次） | 写入侧捕获点前移（§2.2） |
| #24 | 已停用技能仍能被模型直读使用 | 已复现并定位 | 投放过滤 + 管理面旁路收口（§2.1） |
| #25 | 上传技能"更新时间"不更新 | 未复现，但代码核实存在口径缺陷 | 标记键不计入 updatedAt（§2.5） |
| #29 | 手工终止 409 turn_not_ready | 已修复（a811c27） | 仅补回归（§2.4） |
| #5/#13/#17/#23/#16 | 未复现 / 需样本 / 平台侧 | — | 不在本设计范围 |

## 1. 根因结论（基线 a4eae87）

### 1.1 #24 已停用技能仍可用 —— 三个叠加缺口

模型侧（`<available_skills>` 注入、`load_skill_through_path`）经 `SkillDisabledFilterMiddleware` 的
per-call `SkillFilter.disable` overlay 已正确拦截（复现中 `Skill not found` 佐证）。旁路全在文件系统与框架自有视图：

1. **投放层不过滤**：scoped `.skills-cache` 投放（`OpenSandbox.hydrateScopedSkillCache`
   `sandbox/opensandbox/OpenSandbox.java:979` → `scopedPayloadGz:1122` → `tarScopeDir:1149`）将宿主
   scope 目录**整棵 tar 直投容器**，L2（`.skill-states.json`）与 L4（`/{name}/.disabled` 标记）禁用集
   均不参与过滤。宿主残留副本来自 SDK `MarketplaceStager` 的 orphan GC 宽限（禁用前 stage 过的目录
   最长 30 分钟后才被 GC，且需同 scope 再触发 stage）。容器内文件即可被模型 `read_file`/`execute` 直读。
2. **切换无失效钩子**：L2 toggle（`SkillManageService.toggleSkill:224`）/L4 toggle
   （`UserSkillService.toggleSkill:470`）只写状态，不触发任何容器 prune / 指纹失效；
   `pruneContainerSkillCache`（`OpenSandbox.java:1237`）为无调用死代码，现行 prune 仅 scope 粒度。
   但 `.skill-states.json` 恰在指纹源目录内（`AgentScopeConfig.java:109`），toggle 天然改变指纹、
   触发下一轮慢路径重投——可作为失效机制载体（见 §2.1c）。
3. **管理面旁路**：L2 包级禁用 + 同名 L4 enabled 覆盖时，
   `SkillCatalogService.collectAvailable`（`SkillCatalogService.java:239-268`）与
   `SkillInjectionService.enabledSkillNames`（`SkillInjectionService.java:269-285`）只查 L4
   `.disabled` 标记、不看 L2 禁用集，会把该技能重新放回 `/skills/available`、`/skills/parse-refs`
   与 `/Skill` 注入，且 `readSkillContent` 还会回落包内基线内容。与模型侧"名字并集禁用"口径不一致。

### 1.2 #7 历史执行过程缺失 —— 写入侧时序窗口（非读侧合并 bug）

- 读侧 `ThreadController.loadMessagesMerged`（`ThreadController.java:760-836`）已做
  session_message 归档 + state 双源合并，工具调用配对（`StateDataParser.collectToolResults`）
  覆盖压缩边界前后——读侧无需改动。
- 写侧：SDK 2.0.3（字节码核实）整轮只在 `doCall` 结束 `saveStateToSession` 一次，
  `SessionMessageArchiveStateStore` 仅在 save 时 write-through 归档**当时的** context；
  `CompactionMiddleware.onReasoning` 在 turn 中途就地 `clear()+addAll([摘要]+尾部)`。
  **长 turn 中途触发压缩时，压缩点之前的 assistant(tool_use)/TOOL(tool_result) 从未进入
  session_message，也从 agent_state 物理消失**——两条 history 路径都无从渲染。
- token 阈值触发压缩（提交 329a630，PR #303）加剧了"单长 turn 中途压缩"的概率，与复现现象吻合。
- `agent-framework/docs/session-history-archive-design.md` §12.4 已预判该窗口并预留修复后手：
  "捕获点前移至自定义 Middleware.onReasoning（压缩中间件之前）"。

### 1.3 #1 沙箱建箱/数据面韧性缺口

1. **create 无本仓库侧重试**：`OpenSandboxClient.create`（`OpenSandboxClient.java:139-169`）
   SDK `SandboxTimeoutException` 落进 catch(Exception) 被拼成
   `"Lifecycle acquire failed: Request timed out: timeout"` 一次性透出。SDK 默认
   requestTimeout=30s、RetryPolicy 对非幂等 POST 不重试。
2. **exec 数据面 502 不感知**：`OpenSandbox.doExec:313` 不捕获 `SandboxApiException(502)`，
   harness `SandboxBackedFilesystem.execute` 捕获全部 Exception 转成
   `"Internal sandbox error: <原文>"` 工具结果文本回喂 LLM → LLM 反复重试（复现中 118 次 502、
   根 span 142s/859s），框架既不分类也不标记不健康。
3. **SSE error 无分类**：`ChatStreamController.java:737-744` 除 quota 先例外一律
   `e.getMessage()` 原文，无 retryable/可读化。

### 1.4 #25 updatedAt 口径缺陷（代码核实）

`UserSkillService.skillUpdatedAt`（`UserSkillService.java:413-457`）取 `/​{name}/` 键下
agent_fs.updated_at 的 max，`topSkillName` 把 `.disabled`/`.deleted`/`.admin-override`
等**纯标记键**也计入 → 启停/删除/加栅栏等非内容操作会"刷新" updatedAt；
而内容不变的重上传因 `writeKvFile` 短路（`:259-263`）不刷新（语义上可接受）。

### 1.5 #29 已修复，仅回归缺口

a811c27 已实现"租约到手后保留 turn 意图行直到终态"，单测
`TurnStopServiceTest.leaseHeldWithoutEventsShouldFallBackToIntentReplyId`（:105）覆盖核心分支。
缺口：`ChatStreamControllerTest.chatRegistersQueuedIntentAndClearsItAfterLeaseAcquired`（:402）
注释停留在旧行为（"租约到手即清除"）；e2e 无任何 `/threads/{sid}/stop` 场景。

## 2. 修复设计

### 2.1 #24 技能禁用闭环

**a) 投放过滤（主修复）**：`OpenSandbox` 增加 `SkillDisabledChecker` 协作者（经
`OpenSandboxClientOptions` 传入，null = 不过滤，兼作回退开关）：

```java
public interface SkillDisabledChecker {
    /** L2 包级禁用名集合（.skill-states.json） */
    Set<String> packageDisabled();
    /** 单个技能名是否被 L4 用户级禁用（/​{name}/.disabled 标记，单 KV 读） */
    boolean isUserDisabled(String name);
}
```

- `scopedPayloadGz`/`tarScopeDir` 构建载荷时按候选条目逐个判定：目录名 ∈ L2 禁用集或
  `isUserDisabled(name)` → **跳过打包**。判定成本 = 1 次 json 读 + 每候选 1 次 KV 单查，
  仅慢路径执行，不进指纹短路热路径。
- **同 exec 清理容器残留**：`hydrateScopedArchive` 的合并 exec 追加对禁用目录的
  `rm -rf`（复用现有 prune 命令的拼接与转义模式），保证"上一轮已投放、本轮被禁用"的目录
  在下次慢路径投放时被清出容器。

**b) 管理面旁路收口**：`collectAvailable` 在 L4 合并后追加"L2 禁用集强制剔除"（同名 L4 enabled
不得覆盖 L2 禁用）；`SkillInjectionService.enabledSkillNames` 同口径（注入排除），`readSkillContent`
的包内基线回落由调用方名字排除自然阻断。与 `SkillDisabledFilterMiddleware` 的"名字并集禁用"对齐。

**c) 失效机制（不新增主动推送）**：沿用现有指纹派生——`.skill-states.json` 在指纹源目录内、
L4 toggle 改 revision，toggle 后指纹必然变化 → 下一次 `.skills-cache` 触发（命令访问/downloadFile/
readWorkspaceFile/stop 收尾）走慢路径 = 本设计的过滤版重投 + 禁用目录清理。不做跨副本主动失效
（与 sandbox-skill-sync-turn-perf-design 的"无主动失效 API"现状一致），窗口 = 至下一次 hydrate 触发。

### 2.2 #7 压缩前归档：PreCompactionArchiveMiddleware

新增 `service/PreCompactionArchiveMiddleware`（模式仿 `TurnInputArchiveMiddleware`）：

- **挂载点**：`onReasoning`（每步推理前），order 取 1——位于 SDK `CompactionMiddleware`
  （默认 order 更小/更内层，实施时以 jar 实测为准校准）之前执行，赶在压缩改写上下文前完成归档。
- **行为**：读取 `agent.state()` 当前 context，对**有稳定 id 的消息**做增量归档：实例内维护
  `slotKey → 已归档 msgId 集`，差量部分经 `SessionMessageArchiveStateStore.toRecord` +
  `SessionMessageStore.archiveBatch` 落库（`(session_id,msg_id)` upsert 幂等，与 turn 末
  write-through 天然合并，`mergeMsgJson` 取长/取新语义保证不回退）。
- **范围**：归档全部角色（assistant/tool_use、TOOL/tool_result 正是本次要保住的数据），
  跳过空 id 合成消息；compaction 摘要消息（`__compaction_summary__`）同样归档，
  保证归档轨可渲染压缩分隔条。
- **开关与 fail-soft**：沿用 `AGENT_HISTORY_ARCHIVE_ENABLED` 装配门控（与
  TurnInputArchiveMiddleware 同点装配）；单条归档异常只 WARN，绝不影响对话主链路。
- **性能**：每次 onReasoning 仅内存遍历 context + id 集合差分 O(n)，只有新消息产生 DB 写；
  无新增网络/LLM 调用。

效果：压缩驱逐前，tool_use/tool_result 已在 session_message；turn 末 write-through 落压缩后
context，压缩点之前的明细由归档轨补齐——`loadMessagesMerged` 现有合并逻辑直接可见。

### 2.3 #1 沙箱建箱韧性

**a) create 重试 + 退避**（`SandboxLifecycleManager` create 调用点包裹）：

- 仅对**瞬时类**失败重试：SDK `SandboxTimeoutException` / `SandboxConnectionException` /
  IOException；配额、409、身份类失败不重试。重试期间复用既有 `compensatePending` 补偿语义，
  每次尝试前不重复占额。
- 可配：`SANDBOX_CREATE_MAX_ATTEMPTS`（默认 2，即 1 次重试）、
  `SANDBOX_CREATE_RETRY_BACKOFF_MS`（默认 1000，指数 ×2，上限 10s）。
- **b) 建箱 HTTP 超时可配**：`OpenSandboxClient` 的 `ConnectionConfig` 增加
  `requestTimeout`，env `SANDBOX_REQUEST_TIMEOUT_SECONDS`（默认 30 = SDK 默认；
  并发重场景可调大）。注意 `SANDBOX_TIMEOUT_MINUTES` 语义仍是生命周期 TTL，不混用。

**c) SSE error 分类可读化**（`ChatStreamController` 错误回调，仿 quota 先例）：

- cause 链扫描（复用 `isTrailingSandboxTeardownError:1306` 的遍历模式）识别 SDK 沙箱异常，
  映射结构化 error 帧：`{"type":"error","error":"<中文可读文案>","code":"sandbox_create_failed",
  "retryable":true,"detail":"<原始异常信息>"}`；其余异常保持现行为（原文透出），纯增量字段。
- 文案区分：超时（建箱排队/上游无响应，建议稍后重试）vs 连接失败（数据面不可达，建议联系管理员）。

**d) exec 数据面故障感知**（`OpenSandbox.doExec`）：

- `osbSandbox.commands().run` 捕获连接类失败（`SandboxApiException` 502/503/504、
  `SandboxConnectionException`、连接类 IOException），按沙箱实例计**连续**失败数。
- 连续 ≥ `SANDBOX_EXEC_DATAPLANE_FAIL_LIMIT`（默认 3，0=关闭）时：
  ① 异常消息改写为可读引导（"沙箱数据面不可达…请停止重试并告知用户重新发起会话"），
  LLM 收到后停止重试风暴；② best-effort 标记绑定不健康（复用 `markSandboxRebuilt` 语义，
  下轮 acquire 走重建）；③ ERROR 日志带 sandboxId/endpoint。阈值内保持原文透传（容忍瞬时抖动），
  任何情况下不改变 turn 控制流（harness 将异常转工具文本的行为不动）。

### 2.4 #29 stop 回归

- 修正 `ChatStreamControllerTest:402` 过时注释，补断言："租约已交接、首事件未产出"窗口内
  turn_intent 行仍可被 `TurnStopService` 解析（202），终态才清理。
- e2e（sandbox 组 `api-sandbox.spec.ts`）新增 STP1b：`POST /threads/chat` 后**不等首个 SSE 事件**
  立即 stop，断言状态码 ∈ {202, 404} 且 ≠ 409 turn_not_ready（mock LLM 极速完成时允许 404）。

### 2.5 #25 updatedAt 口径修正

`UserSkillService.skillUpdatedAt`/`topSkillName` 排除纯标记键（`.disabled`/`.deleted`/
`.admin-override` 后缀，常量对齐 `WorkspaceReader`），updatedAt 只反映内容写入。
内容不变重上传不刷新属预期语义（无变化即无更新），不改 `writeKvFile` 短路。

## 3. 测试计划（先红后绿）

| 问题 | 复现/回归测试（改前应红） |
|---|---|
| #24a | `OpenSandboxTest`：scope 目录含禁用技能 → 载荷不含该目录、exec 命令含 rm；禁用集合为空 → 全量打包（守卫） |
| #24b | `SkillCatalogServiceTest`：L2 禁用 + 同名 L4 enabled → 不在 available；`SkillInjectionServiceTest`：同组合不注入 |
| #7 | `PreCompactionArchiveMiddlewareTest`：context 含 tool_use/tool_result → 归档行包含、二次 onReasoning 不重复写、压缩后 context 变化仅差量；`ThreadControllerHistoryMergeTest` 补带 tool_use fixture 的合并断言（读侧守卫） |
| #1a/b | `SandboxLifecycleManagerTest`：create 超时 → 重试后成功 / 重试耗尽抛出且 pending 补偿一次；`OpenSandboxClientTest`：requestTimeout 透传 ConnectionConfig |
| #1c | `ChatStreamControllerTest`：SDK 超时异常 → error 帧含 code/retryable/中文文案；普通异常保持原文 |
| #1d | `OpenSandboxTest`：连续 502 达阈值 → 消息改写 + 标记调用；阈值内原文透传 |
| #25 | `UserSkillServiceTest`：toggle 后 updatedAt 不变 |
| #29 | `ChatStreamControllerTest` 断言补强；e2e STP1b |

回归门禁：`mvn test` 全量（CI 基线 1864 用例）+ e2e `core`（S8/HA1/SK4 既有断言）与
`sandbox`（STP1/STP1b）组。

## 4. 部署与验证

本地 kind（`agent-manager-control-plane`）：

```bash
cd agent-framework && make IMAGE=172.20.0.1:5001/agent-framework:oa1110-fix docker-build
docker push 172.20.0.1:5001/agent-framework:oa1110-fix
kubectl -n agent-platform set image deploy/oaf-e2e-demo \
  oaf-e2e-demo=172.20.0.1:5001/agent-framework:oa1110-fix
```

验收：服务起来后 `GET /skills/available`（禁用过滤）、`GET /threads/{sid}/history`（归档轨）、
`POST /threads/{sid}/stop`（无 409）三接口冒烟；e2e sandbox 组全绿。

## 5. 风险与回退

| 改动 | 风险 | 回退 |
|---|---|---|
| #24a 投放过滤 | 判定依赖 KV/json 读失败 → fail-soft 不过滤（保持现状，不误删） | checker 置 null 即关闭 |
| #24b available 收口 | 依赖 L2 禁用集读取失败 → 视为空集（不扩大禁用面） | — |
| #7 新中间件 | DB 写频增加（每 reasoning 差量）→ 幂等 upsert、量=新消息数 | 装配处同 `AGENT_HISTORY_ARCHIVE_ENABLED` 门控 |
| #1a create 重试 | 重试放大上游压力 → 默认仅 1 次重试、指数退避、瞬时类异常才重试 | attempts=1 即关闭 |
| #1c SSE 新字段 | 前端不识别 code/retryable → 字段为增量，`error` 文案本就可读 | 文案回退原文 |
| #1d exec 阈值改写 | 误判瞬时故障 → 连续计数阈值（默认 3）+ 0 关闭 | `SANDBOX_EXEC_DATAPLANE_FAIL_LIMIT=0` |
| #25 updatedAt | 存量标记键时间被忽略 → updatedAt 只降不升（取 max 口径不变） | — |
