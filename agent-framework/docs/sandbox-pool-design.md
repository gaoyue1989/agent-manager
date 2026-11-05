# 沙箱池化设计（session 级借出/归还，销毁重建复用）

| 项 | 值 |
|----|----|
| 编制时点 | 2026-11-04（2026-11-05 增补决议 D17–D19，并复核对齐最新 master `f19b8724`） |
| 状态 | **评审已通过（2026-11-04）· 实施暂缓**（实施启动时以本文档为设计基线，测试与验收见 §8/§10；D17–D19 为 2026-11-05 增补，实施启动时一并复核） |
| 需求 | agent 启动后按配置预初始化一定数量的沙箱池；创建 session 时从池中借一个沙箱；session 使用完成断开后沙箱**销毁重建**回池；池无可用沙箱时 session **等待** |
| 现状核对 | harness 行为基于 `agentscope-harness:2.0.3`（`javap` 反编译 `SandboxManager`/`SandboxLifecycleMiddleware`/`AbstractBaseSandbox` 字节码）；本仓链路核对至 `OpenSandboxClient`/`OpenSandboxFilesystemSpec`/`AgentScopeConfig` 源码；二次复核补充核对 `TurnLeaseGuard` 续约（`ChatStreamController.java:497`）、issue #87 等待心跳（`ChatStreamController.java:518`）、`confirm_context TTL=30min`（`AgentManagerProperties` `@DefaultValue("30")`）、网关超时 3600s（`platform-router.yaml`）；**2026-11-05 增补复核（master `f19b8724`）**：PR #251 删除 `latestSandbox`/`registerSandbox`（present_file/onAgent 改 per-turn `SandboxAcquireResult` 寻址，§3.2 已同步）；SDK `sandbox-1.0.18` 补充核对 `Sandbox.Builder.resource()`（limit）/`resourceRequests()`（request）双档与自带 pool 模块（核对结论见 D19）；`TurnLeaseGuard` 硬寿命（默认 10min）纳入 D14 边界；`ChatStreamController` 行号锚点随 #228 漂移（内容复核不变） |
| 关联文档 | [opensandbox-integration-plan.md](opensandbox-integration-plan.md)、[sandbox-env-update-design.md](sandbox-env-update-design.md)、[sandbox-skill-sync-optimization-design.md](../../docs/design/sandbox-skill-sync-optimization-design.md) |
| 关联 Issue | [gaoyue1989/agent-manager#241](https://github.com/gaoyue1989/agent-manager/issues/241) |

> **方案结论**：池做在 `OpenSandboxClient`（SDK `SandboxClient` 契约层）——`create()` 从池借出预创建容器并绑定 userKey，`resume()` 保持现状（connect 已借出容器），归还由**空闲租约**触发（kill 旧容器 + 异步重建补位）。`SandboxManager` 四级 acquire 优先级、守卫、KV 回写等 SDK 语义**零改动**；总开关 `SANDBOX_POOL_ENABLED` 默认 false，关闭即完全现状。

---

## 1. 背景与目标

### 1.1 现状：容器按会话创建、滞留至超时

当前链路（per-session agent 隔离默认开启，`SANDBOX_SESSION_AGENT_ISOLATION=true`）：

1. 每会话一个 `HarnessAgent` 实例（`AgentRuntimeService.SessionAgentCache`，LRU 500 / TTL 30min）；
2. 沙箱隔离键 = 隔离键原值（Channel 链路 = 前端 sid），slot 持久化于 `agent_state`（`sandbox/user/{agentId}/{sid}`）；
3. 会话首次使用沙箱时 `SandboxManager.acquire()` 走 Priority 4 `client.create()` **真建容器**（1C/1Gi，默认配置）；
4. 每轮 turn 结束 `SandboxManager.release()` → `sandbox.stop()`（复位 running + KV/记忆回写）→ `sandbox.shutdown()`（**SDK 默认空实现**，字节码确认 `return`）——**容器不销毁**；
5. 容器一直占用资源直到 OpenSandbox Server 侧 `timeoutMinutes`（默认 60 分钟）空闲超时被 GC；slot 指向已销毁容器时，下次 acquire resume 404 降级 create。

**浪费点**：

- 会话数无供给上限：N 个会话 = N 个容器（1C1Gi × N），无并发容量控制；
- 会话失活后容器滞留最长 60 分钟（会话无显式"结束"信号，SSE 断开≠会话结束）；
- 容器冷启动（拉起 + execd 就绪）发生在**用户等待路径**上（首 turn 可感知延迟）。

### 1.2 目标

| # | 目标 |
|---|------|
| G1 | 容器供给有上限：`idle + busy ≤ max-size`，容器总数从"随会话数无界"收敛为"随并发有界" |
| G2 | 会话级借出：session 存续期间独占一个容器（可 resume，跨 turn 保留工作区文件，隔离性不弱于现状） |
| G3 | 归还即销毁重建：session 失活后容器 kill，重建全新空容器入池（杜绝跨会话文件残留） |
| G4 | 池空等待：借出时无可用容器则等待（有上限），等待期间不新建超额容器 |
| G5 | 预热消除冷启动：池常备 min-idle 个就绪容器，借出即用 |
| G6 | 开关灰度：默认关闭，开启才走池；关闭路径行为与现状逐字节一致 |
| G7 | 用户级公平与文件连续性（D17，2026-11-05 增补）：单用户容器持有有界（含空闲），配额压力下同用户新会话优先继承粘滞空闲容器（工作区/env 连续） |

### 1.3 非目标

- 不做 turn 级复用（每 turn 换容器）——违背"session 存续期工作区连续"语义，且投影/上传注入每 turn 重做，开销更大；
- 不做**跨服务**共享池（多 Agent 服务各自的池靠 `AGENT_REDIS_PREFIX` 天然隔离——同前缀隔离语义）；**同服务多副本经 Redis 共享池**（D10，见 §5.5）；
- 不改变 IsolationScope.USER / slot 持久化语义（`agent_state` 的 sandbox slot 照存照 resume）；
- 不做容器内会话数据迁移/快照（归还即销毁，未落 KV 的容器内临时文件随容器丢弃——与现状"容器 60min 超时销毁"的数据丢失面一致）。

## 2. 关键决议

| # | 决议 | 说明 |
|---|------|------|
| D1 | **池化层级**：`OpenSandboxClient`（`SandboxClient` 实现层） | 备选 B（`SandboxContext.externalSandbox` userManaged 路径）会绕过 `SandboxManager.release()` 的 `stop()` 调用（`isSelfManaged()==false` 直接 return，字节码确认）→ 每 turn 的 KV/记忆回写丢失，需额外补丁；备选 C（替换 `SandboxManager` 调用点）需侵入 SDK 构建链。A 层改动面最小、SDK 语义全保留 |
| D2 | **借出点**：`OpenSandboxClient.create()` | acquire Priority 4 是"无 slot state → 新建"的唯一入口；池开启时 create 不真建，而是借出池内就绪容器 + 补位重建。resume（Priority 2/3）容器 connect 逻辑**不动**（session 存续期 connect 同一容器），仅**新增续约钩子**：按 state.sandboxId 续全局租约键（续约点②，跨副本有效——键是全局的，busy 表才是本地的）；**D17 扩展（2026-11-05）**：resume 钩子兼做**账本校正**——connect 成功后单 key Lua `HDEL i:{id}` 原子拿回（容器在粘滞空闲区时回 busy、原会话复活成功）；拿不到（已被他人继承/借走）则模拟 404 走 SDK P4 降级、进入借出决策树（§5.2），关闭"继承后旧会话复活与新会话并发互踩"窗口 |
| D3 | **session 结束判定**：Redis 空闲租约（`sbx:pool:lease:{sandboxId}`，TTL = idle-return-seconds） | 借出/每次 `doExec` 续约；后台扫描发现 TTL 过期**且无活跃 turn**（D14）即判定失活归还。SSE 断开不可靠（可重连续传），turn 结束≠session 结束——与 per-session agent cache TTL 30min 同源的"失活即结束"语义 |
| D4 | **归还 = kill + 异步重建**：kill 容器 → busy 表移除 → 异步新建空容器补入 idle 池 | 容器内无跨会话残留（等价全新建）；重建失败池容量-1 并告警，下轮扫描补位 |
| D5 | **slot 不主动清理** | 归还后同 session 若复活（误判），resume connect 404 → acquire 自动降级 create 再借新容器——复用现有"resume 404 降级"框架行为，零额外状态管理 |
| D6 | **等待语义**：idle 池 poll 阻塞等待，上限 max-wait-seconds（默认 120s），超时报错 | 错误经现有 SandboxException 链路回传对话（模型/用户可感知"沙箱繁忙请稍后重试"）。上限防饿死 + 防 guard lease（900s）与 turn lease 被长等待拖爆 |
| D7 | **续约点五覆盖**：借出（borrow）、resume（P3 命中）、`doExec` 每次执行、turn 结束（`stop()`）、确认入口（D16-④ touch）均续约租约 | 只靠 doExec 有两个误回收缺口：①纯 LLM 无工具调用的长 turn 不触发 exec；②turn 间空闲间隙依赖 resume 续约。五点覆盖后语义为"**session 活跃期间（有任何 turn 活动）容器绝不被误回收**"，误回收窗口收窄为"TTL 内完全无 turn 活动"= 按定义的失活。Redis EXPIRE 开销可忽略 |
| D8 | **Redis fail-soft**：Redis 不可用时回收判定跳过（容器滞留退化为现状行为）+ 借出 fail-open 直接建容器（账本外、Server TTL 兜底，§5.8） | 池的可用性增强不得反成对话可用性单点（与守卫 fail-open 同哲学）；代价 = 故障期容量上限失效，可接受降级 |
| D9 | **健康兜底**：借出前对 idle 容器轻探测（execd ping）；失效容器直接 kill 换新 | 防池内容器被 Server 侧 GC/异常后借出即失败 |
| D10 | **Redis 共享池（同服务多副本，Cluster 兼容）**：idle/busy/creating 收敛为**单一 ledger HASH**（骑 `AGENT_REDIS_PREFIX`，per-service 隔离），租约键按 sandboxId 分散；副本无本地账本 | 池化的资源（OpenSandbox Server 容器）本身共享，**容量上限必须是集群级**——per-pod 池在流量倾斜时并发上限退化为单副本上限、且 N 份本地账本跨副本难排障。**Cluster 兼容**：cluster 下多 key Lua 报 CROSSSLOT（redis-cluster-prefix-design §约束），故所有状态迁移（borrow/cap/claim/promote/prune）收敛为 ledger 单 key 脚本；不聚合 hash tag（会让续约热路径打热单 slot），ledger 与租约键间无跨 key 原子性需求（borrow 两步缺口由 §5.3 宽限规则兜底、DEL 租约为卫生操作）。**附带修复两个边界**：副本崩溃无孤儿（账本在 Redis 存活，任意副本经租约过期回收）、rollout 重启容器存活（容器为集群资产，D12 简化） |
| D11 | **总开关 + 池参数全 env 化**，默认 false | 存量部署零影响；e2e mock 沙箱环境维持关闭 |
| D12 | **优雅停机不杀任何容器**（共享池为集群资产，rollout 重启零影响）；`@PreDestroy` 仅清理本地 creating 句柄并 HDEL 对应 creating 条目 | 共享账本（D10）下容器归属集群而非副本——杀容器只会造成池容量瞬时缩水或打断他副本在途 turn；creating 条目若不清理由扫描 5min 剪枝自愈 |
| D13 | **`delete()` 路由进池**：kill + 补位 | 防止 SDK 内部/未来调用方直杀容器导致池容量静默缩水（busy 移除但补位无人做） |
| D14 | **回收双保险**：租约过期 **且** 绑定 sid 的 turn_lease 不活跃（MySQL 单查）才回收 | 关闭"turn 中途稀疏执行"误杀窗口：两次 doExec 之间的 LLM 长生成（无 exec、未到 stop）可超租约 TTL，但 turn 执行期 `TurnLeaseGuard` 每 20s 续约 turn_lease（`ChatStreamController` §2），turn_lease 活跃即跨副本可见的"turn 进行中"信号。busy 记录须存 sid（从日志字段升格为功能字段）。**活性上限（2026-11-05 复核补）**：turn_lease 活跃性受 `TurnLeaseGuard` 硬寿命约束（`agent.cleanup.turn-lease-max-lifetime-minutes`，默认 10min，到期停续租并释放租约）——超硬寿命的 turn 已失去执行权（guard 已宣告 lost、新 turn 可合法并发），此后其容器被回收与「新 turn 并发写」同级、非新故障类别；续约点③ 相应扩展为 **doExec 在途流式续约**（命令执行期间周期续约 Redis 租约，关闭「单条长命令 > 硬寿命被中途 kill」窗口——该场景 turn_lease 已死但 exec 流活跃）；纯 LLM 长生成超硬寿命后的首个工具调用将命中死容器、经 resume 404 自愈重借（行为锁定，非静默） |
| D15 | **TTL 不变量（启动校验，fail-fast）**：`IDLE_RETURN_SECONDS ≥ AGENT_CONFIRM_DEADLINE_MINUTES`（deadline 默认 10min → **`IDLE_RETURN` 默认 `600s` 等值**） | HITL 保护的前提修正：**confirm_context 行 TTL ≠ 确认窗口**——当前确认可用性是双源设计（AgentState 的 ASKING 无 TTL 权威源 + confirm_context 兜底行），行过期后确认仍经 state 恢复执行（"确认卡长期可操作"），即确认窗口实际无界、单改行 TTL 收窄不了容器保活需求。D16 硬截止把确认窗口真正封顶为 deadline；**等值成立的额外前提是 D16-④ 预检续约+存活校验**（否则"租约/截止双钟到点重合 + MySQL/Redis 时钟偏差 ±秒级 + 扫描滞后"会在 deadline 边界产生 kill-vs-confirm 竞态——通过预检的确认可能中途 404 借新容器丢产物）。④ 落地后保证：**deadline 内的每一刻容器必活，或干净 410** |
| D16 | **确认硬截止（配套行为变更，独立于池化先行落地）**：ASKING 挂起超 `AGENT_CONFIRM_DEADLINE_MINUTES`（默认 10）后——① 迟到确认 → `410 confirm_expired` + 自动按"超时拒绝"收尾（复用现有 reject→DENY 分支），turn 以拒绝终态结束、会话从 ASKING 解锁；② 迟到普通消息 → 入口预检（`hasPendingConfirm`）发现已超截止 → 同样自动收尾后放行新 turn；③ 用户从此不交互 → state 里 ASKING 无害挂起（无 turn lease、容器已被池按租约回收），任意后续交互触发懒收尾；④ **确认预检续约 + 存活校验**：确认入口（confirm-stream 预检 `checkConfirmAvailable` **与 POST /confirm 实际入口两处**）未超截止即调 `pool.touch(sid)`——**存活判定以租约键为准，不查 busy 账本**（busy 是归属账本、租约才是活性真相；容器可能已被回收认领而 busy 已清）：`EXISTS {leaseKey}` → `EXPIRE` 续约放行；键不存在 → `410 confirm_expired`，不进入 resume 流程（本项使 D15 等值成立）。Redis 键过期与 MySQL deadline 双钟偏差只会产生"略早的干净 410"，绝不产生"通过预检却中途 404"——方向安全——**零后台任务、零定时写**（符合原则二的正确姿势） | 时钟源：confirm_context 行 `created_at`（行在时）/ AgentState 更新时刻（行已被 TTL 清理的兜底，实施时以表结构核实）；行 TTL（`AGENT_CLEANUP_CONFIRM_TTL_MINUTES`，按决议调 10min）与 deadline 同值时行恰好覆盖有效窗口。确认卡语义变化：超时=作废（现状=无限期可确认），前端确认卡应展示倒计时/过期态（实施项） |
| D17 | **用户粘滞复用 + 用户配额（2026-11-05 增补）**：归还不立即 kill，容器以 `owner=userKey` 转入**粘滞空闲区**（占用户配额、占集群容量，`STICKY_TTL` 到期才 kill 回收）；借出决策树（§5.2）：`held(user) = Σ(i:owner=user) + Σ(b:user) + Σ(c:user)`——`held < USER_MAX`（默认 5）→ **借新**（通用 idle 优先 → 按需建 → 全局满等待，均沿用既有语义）；`held ≥ USER_MAX` → **LRU 继承**该用户粘滞空闲容器（单 key Lua 原子换绑新 sid）；无粘滞空闲 → 报错 `user_quota_exhausted` | 配额**含空闲**是关键：用户关会话后容器立即可被继承，不必等 kill；配额内优先借新（尊重"新会话=干净环境"预期），继承是配额压力下的显式降级；继承容器保留工作区与 env（env 丢失面收窄到粘滞窗口外，§6/§7）；**语义代价**：继承=新会话可见上一会话工作区残留（按规则显式发生，非意外）。**配套挡板**：resume 账本校正（D2 扩展）——继承-复活竞态由单 key Lua 原子裁决（一方赢），不产生并发互踩 |
| D18 | **弹性水位（2026-11-05 增补）**：ledger 新增 `t:target`（`MIN_IDLE ≤ T ≤ MAX_SIZE`，初始 = MIN_IDLE）；扫描周期任一副本评估 `\|b:\|/T ≥ 80%` → `T = min(T+GROW_STEP, MAX_SIZE)`（Lua 内读判写，并发扫描单赢家）；异步补建通用 idle 至 T；`\|b:\|/T < 50%` 连续 3 周期 + 冷却（lastAdjust ≥ 2 周期仅许一次变更）→ `T = max(T-GROW_STEP, MIN_IDLE)` 并 LRU kill 超额**通用** idle（粘滞不参与强制回缩，走自身 TTL） | 参考数据库连接池的初始/步进/上限模型；T 只管"提前预备多少"、**不约束借出**——§5.2 按需同步建仍以 MAX_SIZE 为界（水位滞后于 burst 时按需建兜底，触发条件随后把 T 追上），把冷启动成本从借出路径挪到扫描路径；80% 抬升自稳定（分母变大比例即回落），回缩必须滞回+冷却防负载在阈值附近摆动时 T 抖动 |
| D19 | **容器资源双档（request/limit）+ 借出剩余寿命校验（2026-11-05 增补）**：SDK `Sandbox.Builder.resource()`=limit（现状已用）+ `resourceRequests()`=request（新增透传）——新 env `SANDBOX_CPU_REQUEST`（默认 `100m`）/`SANDBOX_MEMORY_REQUEST`（默认 `128Mi`），idle 容器只占 request 水位、借出工作时 burst 到 limit；**独立于池开关生效**（非池路径同样低 request 启动）。借出前探测（D9）吸收 SDK 池模块 `acquireMinRemainingTtl`：容器剩余寿命 < 5min 弃用换新 | 「最低资源启动」的正解是低 request 而非双规格（requests/limits 创建时固化、不可热调，借出不改资源）；超卖治理显式化：调度保障 `MIN_IDLE×request ≤ 节点可分配`，峰值竞争 `MAX_SIZE×limit`（写入部署文档）。**SDK pool 模块核对**：`sandbox-1.0.18` 自带 `SandboxPoolManager`（单实例 + primaryLockTtl 选主 reconcile 模型，`PoolCreationSpec` 无 resourceRequests）——不满足 D10 免选主共享池与本需求，D1 决议维持。**P0 实施前验证**：部署的 OpenSandbox Server 是否透传 requests 到底层 runtime（mock e2e 补断言） |

## 3. 现状核对与关键依据

### 3.1 SDK acquire 四级优先级（agentscope-harness 2.0.3，`SandboxManager.acquire` 字节码）

| 优先级 | 条件 | 动作 | 池化影响 |
|--------|------|------|----------|
| P1 | `SandboxContext.externalSandbox != null` | 返回 `userManaged(result)` | 不使用（`toSandboxContext()` 从不设置 externalSandbox，字节码确认） |
| P2 | `externalSandboxState != null` | `client.resume(state)` | 不变 |
| P3 | slot 有持久化 state | `deserializeState` → `client.resume(state)` | **不变**（session 存续期走这里） |
| P4 | 以上全 miss | `client.create(workspaceSpec, snapshotSpec, options)` | **池借出点** |

关键语义（字节码确认）：

- `release(result)`：仅 `isSelfManaged()` 时执行 `stop()` + `shutdown()`；`shutdown()` 接口默认实现为空——**框架从不销毁容器**；
- `acquireForCall` 的 `SandboxContext` 来自 `RuntimeContext.get(SandboxContext.class)`，由 agent 构建期 `SandboxFilesystemSpec.toSandboxContext()` 固化——**per-call 不可变**（排除 per-call 动态注入 externalSandbox 的方案）；
- P4 create 在 `SandboxExecutionGuard.tryEnter` **之后**（持有 guard lease 期间）——池等待时间受 guard lease TTL 900s 约束（D6 取 120s，安全）；
- `acquireForCall` 在 `SandboxManager.acquire` 返回后显式调 `sandbox.start()`——借出容器须处于"未 start"状态，借出时注入当次 `workspaceSpec` 到 state（保证投影 hydrate 正常，见 §5.4）。

### 3.2 本仓链路（池开启后不变的部分）

| 环节 | 位置 | 池化影响 |
|------|------|----------|
| userKey/skillScope 绑定 | `OpenSandboxClient.create/resume` → `bindUserKey`（ThreadLocal） | 借出实例照走（借出时执行） |
| 上传注入双保险 | `create` 内 `resetInjectedToPending` + `injectPendingUploads` | 照走 |
| 每 turn KV 回写 | `OpenSandbox.stop()`（release 路径） | 照走（D1 选 A 的核心理由） |
| skillSyncGate | `userKey|sandboxId` 键控 | 新容器新 id → gate miss → 全量物化，天然自洽 |
| 沙箱寻址（present_file/onAgent） | per-turn `SandboxAcquireResult`（RuntimeContext，SDK `acquireForCall` 预置；PR #251 起进程级 `latestSandbox`/`registerSandbox` 已删除） | 池化不动 `acquireForCall`，寻址与借出/归还天然兼容，不新增竞态面（2026-11-05 复核对齐） |
| resume 404 降级 create | `SandboxManager.acquire` P3 异常 → P4 | D5 依赖此行为 |
| 守卫 | `RedisSandboxExecutionGuard`（per-session slot 粒度） | 不变 |

## 4. 方案选型

| 方案 | 说明 | 结论 |
|------|------|------|
| **A：`SandboxClient` 层池化** | `create()` 借出 / `resume()` 不动 / 归还走空闲租约 | ✅ 采用。SDK 生命周期语义（start/stop/回写/守卫/404 降级）全保留，改动集中在 client + 新增 pool manager |
| B：`externalSandbox`（userManaged） | per-call 注入池容器为外部沙箱 | ❌ `release()` 不调 `stop()` → 每 turn KV 回写丢失；`SandboxContext` 构建期固化，per-call 注入需 hack RuntimeContext |
| C：替换/包装 `SandboxManager` | 在 middleware 层接管 acquire/release | ❌ `SandboxManager` 由 SDK `HarnessAgentBuilderSupport` 内部构建（非 bean），无注入点；反射替换脆弱 |

## 5. 池设计

### 5.1 状态机与账本

```
              borrow(借出)                租约过期(扫描判定失活)
  ┌────────┐ ───────────► ┌──────┐ ────────────────────────────► 销毁(kill)
  │  IDLE  │              │ BUSY │                                   │
  │(就绪池)│ ◄─────────── └──────┘ ◄── 每次 turn 活动续约租约 ──┤ 异步补位(占容量)
  └────────┘  kill+重建归还  ▲                                    ▼
   ▲    │                  └─ resume/connect(会话存续期复用) ──┘  ┌──────────┐
   │    │                                                        │ CREATING │
   │    └─ 借出前探测失败: kill → 按需新建/回池重取(≤2次) ─────────►│(计入总量)│
   │                                                              └────┬─────┘
   └─────────────────────────── 新容器就绪 ─────────────────────────────┘
```

> **resume 404 不是池状态迁移**：容器被 Server GC/误杀后，同会话下一 turn 走 acquire P3 → resume 404 → 框架降级 P4 → **重新借出新容器**（会话级恢复）。死容器的 busy 记录保留至其租约过期，由扫描清理并补位（期间总量核算含死容器，可用容量瞬时低估，自愈）。
>
> **D17 修订（2026-11-05）**：图中「kill+重建归还」改为「**转粘滞空闲**（`i:owner=user`）」——归还容器不 kill，`STICKY_TTL` 到期才 kill 回收；「kill+重建」仍适用于：借出/继承前探测失败、idle 保活失效、D13 `delete()` 路径。

- **账本（Redis 共享，D10；全部骑 `AGENT_REDIS_PREFIX`，per-service 天然隔离；**Cluster 兼容**，见下）**：

  | key | 类型 | 内容 | slot 分布 |
  |-----|------|------|-----------|
  | `{prefix}sbx:pool:ledger` | HASH | **单 key 全量账本**：字段 `i:{sandboxId}` = `{owner:userKey\|null, returnedAt}`（idle：owner=null 通用就绪，服务"借新"；owner=user 粘滞空闲，服务 D17 继承与复活）/ `b:{sandboxId}` = `{sid,userKey,podId,borrowedAt}`（busy，sid 为 D14 查询键、userKey 为 D17 配额键）/ `c:{entryId}:{createdAt}`（creating，超时 5min 扫描剪枝——自愈计数器）/ `t:target` = `{value,lastAdjustAt}`（D18 弹性水位） | 每 service 一个 key，**状态迁移全在此 key 上** |
  | `{prefix}sbx:pool:lease:{sandboxId}` | STRING（TTL） | 失活判定唯一真相（续约×5 / D14 / D16-④ 骑此键） | 按 sandboxId **天然分散**（续约热路径不聚集单节点） |

- **Cluster 兼容性（`AGENT_REDIS_MODE=cluster` 必须支持）**：cluster 下多 key 命令/Lua 要求同 slot（CROSSSLOT，见 redis-cluster-prefix-design.md §约束）。本设计**不使用 hash tag 聚合池 key**（那会让全部借出/续约打热单 slot），而是把 idle/busy/creating 收敛进**单一 ledger HASH**——所有状态迁移 Lua（borrow/cap/claim/promote/prune）都是单 key 脚本，cluster 天然合法；租约键独立分散，与 ledger 之间**无跨 key 原子性需求**（见下分析）：
  - borrow：Lua(ledger) 原子 `HDEL i:{id}` + `HSET b:{id}`（同 key 内完成，并发借出各得唯一容器）；随后单独 `SET lease`——两步之间的崩溃缺口由扫描的 **borrowedAt 宽限规则**兜底（§5.3）；
  - 按需建/补位：Lua(ledger) 单 key 内完成"`i:`+`b:`+`c:` 计数 < MAX_SIZE"判定与 `HSET c:`；
  - 回收认领：`HDEL b:{id}` 单 key 先到先得（免选主）；`DEL lease` 为卫生操作（TTL 自灭，非正确性依赖）；
  - 容量约束（**集群级**）：`|i:| + |b:| + |c:| ≤ MAX_SIZE`。
- **进程内仅存**：creating 句柄（本副本正在建的容器引用）与池状态只读缓存（可观测性用），无正确性状态——Pod 本地状态全部可丢弃（原则二）。

### 5.2 借出流程（`OpenSandboxClient.create()`，池开启分支）

1. `pool.borrow(maxWait)`（集群级，D17 决策树；全部判定在 ledger 单 key Lua 内）：
   - **配额判定** `held(user) = Σ(i:owner=user) + Σ(b:user) + Σ(c:user)`：
     - `held < USER_MAX` → **借新**：Lua `borrow`（`HDEL i:{id}`（仅 owner=null）+ `HSET b:{id,user}` 原子完成）非空 → SET 租约 → 进入步骤 2 探测；通用 idle 空 **且集群容量未满**（Lua cap，同 key）→ **按需同步新建**（`HSET c:`；消除"有容量却干等归还"的死等）；通用 idle 空且已达集群上限 → 轮询 ledger（1s 间隔，≤ `MAX_WAIT`；归还/补位入 `i:` 即可被任意副本借走——**集群级公平等待**）；
     - `held ≥ USER_MAX` → **LRU 继承**：该用户粘滞空闲（`i:owner=user` 按 `returnedAt` 最旧）取一，Lua 原子换绑（`HDEL i:{id}` + `HSET b:{id, sid=新sid, user}`）→ SET 租约（覆盖旧值，续约点①）→ 进入步骤 2 探测；
     - `held ≥ USER_MAX` 且无粘滞空闲 → 抛 `SandboxException(CONFIGURATION_ERROR, "sandbox pool user quota exhausted (user={key}, max={n})")`——可感知错误（"并发沙箱已达上限，请结束其他会话"），经现有异常链路上报到对话；
   - 超时（借新等待路径）→ 抛 `SandboxException(CONFIGURATION_ERROR, "sandbox pool exhausted (waited {n}s)")` —— 经现有异常链路上报到对话（G4/D6）；
   - **Redis 不可达 → fail-open**：跳过账本直接建容器（不计数、限流告警）——沿用"守卫不能成为对话可用性单点"的仓哲学，Redis 停机对话可用性不劣于现状（**账本外容器不受扫描管理，由 Server TTL 兜底消亡**，§5.8）；
2. **借出前探测（D9 + D19）**：execd ping（轻量 exec `true`，超时 3s）+ **剩余寿命校验**（`getInfo` 距 Server `timeoutMinutes` 剩余 < 5min → 弃用换新，防"借出即临期、turn 中途被 Server GC"，吸收 SDK 池 `acquireMinRemainingTtl`）；失败 → kill（幂等）→ Lua 认领 `HDEL b:{id}`（held-1）→ **重走步骤 1 决策树**；连续 2 次失败（OpenSandbox Server 异常等）→ 报错；
3. 绑定租约：`SET {leaseKey} {sid} EX idleReturnSeconds`（续约点 ①）；
4. 注入当次 `workspaceSpec`：`state.setWorkspaceSpec(workspaceSpec)`（借出容器预创建时无 spec，start 时投影 hydrate 依赖它，见 §3.1）；
5. `bindUserKey` + `resetInjectedToPending` + `injectPendingUploads` —— **照现状代码路径执行**（`registerSandbox` 已随 PR #251 删除，寻址改 per-turn ctx，见 §3.2）；
6. 返回 `OpenSandbox`（未 start；由 `acquireForCall` 统一调 `start()`：`running` CAS + `mkdir -p` 幂等 + 投影 hydrate）。

> 现状 `create()` 内部已调一次 `sandbox.start()`（`OpenSandboxClient.java:119`），随后 `acquireForCall` 再 start 一次——依赖 `AbstractBaseSandbox.start()` 的 `running` CAS 幂等性（实施时以单测锁定该行为）。

### 5.3 归还流程（后台扫描，`SANDBOX_POOL_SCAN_INTERVAL_SECONDS` 周期，任一副本可扫、免选主）

对 ledger 的 `b:` 条目逐个判定（`HGETALL` 一次读取，条目量 ≤ MAX_SIZE）：

1. `EXISTS {leaseKey}` = true → 活跃，跳过；
2. **borrowedAt 宽限规则**：租约键缺失但 `borrowedAt` 距今 < 60s → 借出方在"ledger 入账 → SET 租约"两步之间的窗口（正常毫秒级完成；崩溃遗留则 60s 后进入正常判定）→ 跳过；
3. 租约过期 → **turn_lease 双保险（D14）**：查绑定 sid 的 turn_lease（MySQL 单查）——活跃（turn 执行中，续约器每 20s 保活）→ 重新 SET 租约跳过；不活跃才进入回收。此步关闭"LLM 长生成稀疏执行"误杀窗口（两次 doExec 间隔可超 TTL，但 turn_lease 必活）；
4. 判定通过 → **Lua 认领回收**（`HDEL b:{id}` 先到先得——多副本并发扫描只有一个赢家，免分布式锁）：
   a. **认领后复核 + 撤销**：kill 前 `EXISTS {leaseKey}` 再查一次——若租约在此间隙被 resume 续约重建（判定到 kill 的毫秒~秒级窗口内有新 turn），`HSET b:{id}` 撤销认领跳过（撤销安全：认领期间该容器不在 `i:`，无借出方可能拿走）。残余微窗口（复核→kill 间）击中时表现为容器中途死亡，与 Server TTL 死亡同构自愈（下 turn resume 404 重借），非新故障类别；
   b. 复核通过 → `sandbox.stop()` 兜底（幂等，KV/记忆回写）→ **转粘滞空闲（D17）**：`HSET i:{id} = {owner=userKey, returnedAt}` + `DEL {leaseKey}`（卫生操作）——不 kill，工作区/env 保留，等待同用户继承或原会话复活（§5.2 决策树 / D2 账本校正）；
   c. 粘滞 TTL 到期（扫描判 `returnedAt`）→ `osb.kill()` + `close()`（kill 失败=容器已被 Server GC/已死，照常继续）→ 释放用户配额与集群容量，通用池按 `MIN_IDLE` 水面（只数 owner=null）决定补位；
   d. 异步补位（通用池）：Lua cap（`REBUILD_CONCURRENCY` 为**集群级**并发上限）→ 新建容器 → 就绪探测 → `HSET i:{newId, owner=null}`；失败告警，下轮扫描重试补位。
5. **creating 剪枝**：`c:` 字段年龄 > 5min 删除（副本崩溃 mid-create 的自愈，容量不泄漏）；
6. **idle 保活**：对 `i:` 容器（通用与粘滞）轻量存活检查（`getInfo`），失效 → Lua `HDEL i:{id}`（原子认领，并发只有一个赢）+ 通用池触发补位（粘滞失效同 kill，释放配额）；
7. **弹性水位（D18）**：`|b:|/T ≥ 80%` → `T = min(T+GROW_STEP, MAX_SIZE)` 并异步补建通用 idle 至 T；`|b:|/T < 50%` 连续 3 周期 + 冷却 → `T = max(T-GROW_STEP, MIN_IDLE)` 并 LRU kill 超额通用 idle（粘滞不参与）。水位判定与变更在同一单 key Lua 内完成，多副本并发扫描单赢家、免选主。

**不清理 slot**（D5）：误判复活的 session 经 resume 404 → 降级 create → 再借新容器，数据面由 KV/MySQL 承载（容器内临时文件随销毁丢弃，与现状一致）。

### 5.4 预热、续约与保活

- **预热**：`ApplicationReadyEvent` 后异步补齐 `MIN_IDLE` 个**通用**容器（owner=null；**初始/保底水位**——D18 回缩下限，多副本同时启动经 Lua cap 合力补齐、不超额；OpenSandbox Server 不可用只告警，借出时按需创建兜底，见 §5.2）；
- **续约（D7 五点覆盖）**：
  | 续约点 | 位置 | 防护目标 |
  |--------|------|----------|
  | ① borrow | `create()` 借出成功即 SET 租约 | 借出即受保护 |
  | ② resume | P3 命中、connect 成功后续约 | **turn 间空闲间隙**（活跃会话两 turn 间隔 < TTL 时绝不误回收） |
  | ③ doExec | 每次命令执行 + **在途流式续约**（D14 补：命令执行期间周期续约，兜住单条长命令 > 硬寿命场景） | **长 turn 中途**（工具命令高频续约 + 长命令执行期） |
  | ④ stop | turn 结束回写后 | turn 收尾窗口 |
  | ⑤ confirm 入口 | 确认两入口（预检 + POST）未超截止时 `pool.touch(sid)`：租约键 EXISTS → EXPIRE 续约；键缺失 → 410（D16-④） | **deadline 边界竞态**（IDLE_RETURN = deadline 等值的前提，D15）：键在 → 续约保活至 resume 接管；键失（租约键是**活性真相**，busy 账本是归属账本——判活不看 busy）→ 预检即 410，杜绝"通过预检却中途 404 借新容器" |
  ②③④⑤ 统一为 `SET {leaseKey} {sid} EX {ttl}` **幂等 upsert**（不用 EXPIRE：租约若在两次扫描间隙过期，upsert 直接重建，自愈且无窗口；正确性由 D14 双保险兜底——扫描回收前必查 turn_lease，重建租约不会掩盖真失活）；语义保证：**turn 执行中绝不被回收**（续约点 + D14 turn_lease 双保险兜住稀疏执行窗口）、**turn 间空闲 < TTL 绝不被回收**（resume 续约）；
  **ASKING 挂起是唯一例外**：turn 已结束（挂起点 stop() 为最后续约）、无 turn_lease、无 exec——此窗口靠 D16 硬截止（10min）+ D15 等值不变量（IDLE_RETURN = deadline，⑤ 预检续约关闭边界竞态）保护：**截止内确认容器必活、无缝续跑；迟到确认/容器已回收的确认被 410 作废并自动收尾，不存在"续跑丢产物"路径**；
- **busy 容器保活兜底**：容器寿命超 Server `timeoutMinutes`（60min 绝对计时）会被 Server GC → 该 turn 的 exec 失败、下轮 resume 404 降级重借——与现状行为一致（非回归）。可选优化：池容器创建时配置更长 timeout 减少换血；
- **idle 容器保活**：池扫描每轮轻量存活检查（`getInfo`），失效即 kill 替换（通用容器按水面补位）——兜底 Server 空闲 GC（idle 容器寿命可能长于单次借出期）。低流量下每 ~60min/容器一次换血为可接受成本；粘滞容器失效同 kill，触发配额释放与水面补位核算。

### 5.5 多副本语义（原则二对齐：Redis 共享池）

**账本与裁决全部在共享存储，副本无正确性状态**：

| 共享（Redis） | 共享（MySQL） | 共享（OpenSandbox Server） |
|---------------|---------------|---------------------------|
| idle/busy/creating 账本、租约键（失活判定） | slot（sandboxId，resume 依据）、turn_lease（D14 双保险） | 容器本身（任意副本可连/可 kill） |

副本进程内仅存：creating 句柄、可观测性只读缓存——全部可丢弃（原则二）。

**共享池下的跨副本会话路径**（比 per-pod 池更简单，容量语义同时修正）：

1. mid-session 跨副本（turn 1 在 A 借出、turn 2 落 B）：B 走 P3 resume 直连容器（不经池），从 B 续约同一租约键——不变；
2. 会话失活回收：**任一副本**的扫描都能认领回收（HDEL 先到先得）——容器归属集群而非某个副本；
3. 会话复活：粘滞窗口内容器仍活 → P3 resume 直连 + **账本校正**（D2 扩展：`HDEL i:` 拿回 busy，与继承竞态单 key 原子裁决）；容器已被回收 → resume 404 → 降级 P4 → 走 D17 借出决策树（无论落在哪个副本，同一份池）——**并发上限 = 集群 `MAX_SIZE`、单用户持有 ≤ `USER_MAX`**，流量倾斜不再退化为单副本上限（per-pod 池的核心缺陷，用户复核指出）；
4. 预热/补位/回收：多副本并发执行由 Lua cap / HDEL 认领原子裁决——免选主、免分布式锁。

**per-pod 池 → 共享池修复的两个既有边界**：

| 边界 | per-pod 池 | 共享池 |
|------|-----------|--------|
| 副本崩溃 | busy 账本随进程消失 → 孤儿容器滞留至 Server GC | busy 账本在 Redis 存活 → 任意副本在租约过期后认领回收，**无孤儿** |
| rollout 重启 | 需权衡杀 busy（打断在途 turn）vs 留孤儿 | 容器为集群资产，副本重启**零影响**（D12 简化为不杀任何容器） |

**新增依赖与兜底**：借出路径经 Redis（ledger 单 key 1-2 次 RTT，集群内 Redis 亚毫秒）；**Cluster 模式天然支持**（`AGENT_REDIS_MODE=cluster`，状态迁移全为单 key Lua，无 CROSSSLOT 面；多 service 各自的 ledger key 分散在不同 slot，续约键按 sandboxId 分散——无热点 slot）。Redis 不可达 → fail-open 直接建容器（§5.2 步骤 1），对话可用性不劣于现状。

双副本 e2e 门禁（e2e-multi / e2e-protocol-multi）行为不变：池关闭路径零改动，池开启路径不引入副本间写业务 K8s 资源（纯沙箱容器，OpenSandbox Server 面）。

### 5.6 配置（`agent.sandbox.pool.*`，全 env 绑定 `SandboxConfig` 新增嵌套 record）

| env | 默认 | 说明 |
|-----|------|------|
| `SANDBOX_POOL_ENABLED` | `false` | 总开关；false 走现状（D11） |
| `SANDBOX_POOL_MIN_IDLE` | `5`（D18 调整，原 2） | **初始/保底水位**（D18 回缩下限；集群级，多副本启动经 Lua cap 合力补齐、不超额；`MIN_IDLE×request` 为调度保障底线，见 D19） |
| `SANDBOX_POOL_MAX_SIZE` | `20`（D18 调整，原 8） | **集群级**总上限：`|idle| + |busy| + |creating| ≤ MAX_SIZE`（跨副本共享账本，D10；对齐被池化资源——OpenSandbox Server 容器——的共享语义）；峰值竞争 = `MAX_SIZE×limit`（D19 超卖治理旋钮） |
| `SANDBOX_POOL_GROW_STEP` | `5`（新增，D18） | 弹性水位扩/缩步长；80% 扩 / 50%×3 周期+冷却缩（高低水位/周期/冷却为内置常量，有需要再 env 化） |
| `SANDBOX_POOL_USER_MAX_CONTAINERS` | `5`（新增，D17） | 单用户容器配额（**含粘滞空闲与使用中**，集群级 ledger 计数；配额满 → LRU 继承粘滞空闲，无则 `user_quota_exhausted`；建议满足 `MAX_SIZE ≥ 2×USER_MAX` 并按并发用户数规划） |
| `SANDBOX_POOL_STICKY_TTL_SECONDS` | `600`（新增，D17） | 粘滞空闲保留窗口（继承/复活窗口；到期 kill、释放配额与容量。与 `IDLE_RETURN` 同值默认，语义统一"10 分钟无动静 = 会话结束"；要长窗口业务调大，代价是配额被粘滞容器占据更久、更早触发继承/报错） |
| `SANDBOX_POOL_MAX_WAIT_SECONDS` | `120` | 借出等待上限，超时报错；0=无限等（不推荐，D6）。等待期间 chat 链路有 issue #87 的 15s `waiting` 心跳帧覆盖（§6"等待体验"行），网关读超时 3600s 无风险；非流式调用方（A2A POST / a2a-job）无心跳，靠此上限兜底（< a2a-job send-timeout 300s 默认） |
| `SANDBOX_POOL_IDLE_RETURN_SECONDS` | `600` | busy 容器空闲租约 TTL（= session 失活判定阈值，D3）。**硬不变量：≥ 确认截止 deadline（`AGENT_CONFIRM_DEADLINE_MINUTES` 默认 10min），默认等值（D15，启动 fail-fast；等值安全性由 D16-④ 预检续约保证）**；调小必须同步下调 deadline，调大则放宽确认窗口外的滞留 |
| `SANDBOX_POOL_SCAN_INTERVAL_SECONDS` | `60` | 回收/补位/保活扫描周期 |
| `SANDBOX_POOL_REBUILD_CONCURRENCY` | `2` | 补位创建并发（复用现有 scheduling pool 之外的独立单线程 executor ×N） |

> `IDLE_RETURN_SECONDS` 与 per-session agent cache TTL（30min）独立：前者回收**容器**，后者驱逐 **HarnessAgent 实例**（内存）。agent 驱逐不触发容器归还（容器由租约裁决），两者目的不同。
>
> 配套 HITL 侧配置（D16，属确认流而非池参数）：`AGENT_CONFIRM_DEADLINE_MINUTES`（默认 10，确认硬截止）；`AGENT_CLEANUP_CONFIRM_TTL_MINUTES` 按决议调 10（兜底行寿命，与 deadline 同值即恰好覆盖有效窗口）。
>
> D19 配置（**独立于池开关生效**，非池路径同样低 request 启动）：`SANDBOX_CPU_REQUEST`（默认 `100m`）/ `SANDBOX_MEMORY_REQUEST`（默认 `128Mi`，K8s quantity 字符串）——透传 SDK `resourceRequests`；limit 沿用现有 `SANDBOX_CPU_COUNT`/`SANDBOX_MEMORY_MB`。

### 5.7 可观测性（开发体验）

- **结构化日志**（`[sandbox-pool]` 前缀）：borrow / inherit（D17，含 from-user）/ quota-exhausted / return-sticky / sticky-expire / scale-out / scale-in / target-changed（D18）/ rebuild / probe-fail / wait-start / wait-timeout / scan-kill 全事件 INFO 级，含 `sid + userKey + sandboxId + idle(通用/粘滞)/busy/creating/waiters/T` 快照——排障"我的会话在哪个容器/为什么在等/池水位多少"一眼可查；
- **池状态透出**：`DebugApiController` 增加池状态端点（通用/粘滞 idle、busy（含 userKey 归属）、creating、waiters 明细 + 水位 T + 最近 N 条流转事件，进程内存环形缓冲），对齐现有 Debug Console 的排障体验；
- **容量水位告警**：`busy == max-size`（池满）连续超 2 个扫描周期打 WARN——容量该扩的信号。

### 5.8 生命周期边界（停机 / 孤儿 / delete 路由）



| 场景 | 行为 |
|------|------|
| 副本**优雅停机**（republish / rollout restart） | **不杀任何容器**（D12：共享池为集群资产）——在途 turn 在其他/本副本重启后经 resume 续跑；仅清理本地 creating 句柄（HDEL creating，漏清由 5min 剪枝自愈） |
| 副本**崩溃**（kill -9 / OOM，无停机钩子） | **无孤儿**（共享池核心收益）：busy 账本在 Redis 存活，任意存活副本的扫描在租约过期 + turn_lease 不活跃后认领回收；仅"崩溃时 mid-create"的容器短暂无名（creating 剪枝 5min + 容器本身由 Server TTL 兜底） |
| `SandboxClient.delete()` 被调用 | 路由进池（D13）：kill + Lua 认领（`HDEL b:{id}` 或粘滞 `HDEL i:{id}`）+ 通用池按水面补位——防 SDK 内部/未来调用方直杀容器导致池容量静默缩水（D17 后粘滞容器同受配额核算，kill 即释放配额） |
| **Redis 故障期 fail-open 容器** | 账本外容器不受扫描管理（无 ledger 条目 → 不会被认领回收）——由会话 slot 照常 resume 复用，失活后靠 Server TTL（≤60min）消亡；容量上限在故障期失效为**已知降级**（fail-open 的代价，与"守卫 fail-open"同级取舍） |
| **borrow 两步间隙崩溃**（ledger 入账后、SET 租约前） | §5.3 宽限规则兜底：60s 内跳过；超时后按正常租约缺失路径回收（借出方已死，回收正确） |
| 扫描与借出并发竞态 | 回收 Lua 认领（`HDEL busy` 先到先得，§5.3）+ kill 幂等（kill 已死容器无副作用）；共享池容量经 Lua cap 原子裁决借出/按需建/补位（§5.1），无信号量竞态面 |

## 6. 与现有机制的交互核对

| 机制 | 核对结论 |
|------|----------|
| 每 turn KV 回写（`OpenSandbox.stop`） | 不变（方案 A 核心）；归还前再 stop 一次兜底（幂等） |
| 技能 revision 门控 / 快照差分 gate（`skillSyncGates`） | 新容器新 sandboxId → gate miss → 全量物化/回写，自洽 |
| 上传注入（pending/injected 状态机） | create 路径照走 `resetInjectedToPending` + 注入；归还 kill 与现状 delete 同款语义（§6.2.4 双保险不变） |
| `.skills-cache` scoped 投放 | 借出容器全新空白，首 call 投放当前 scope，`pruneContainerSkillCache` 清残留——不变 |
| 守卫（Redis SET NX per-slot） | 不变；池等待发生在 guard lease 持有期内，max-wait 120s ≪ lease 900s |
| turn_lease / stop | 不变；池等待位于 acquire（turn 执行内），等待期间 turn lease 靠 renew 维持——120s 上限与 renew 20s 兼容。turn_lease 另升格为**回收双保险信号**（D14：扫描回收前校验 sid 租约活跃性）；活性上限 = `TurnLeaseGuard` 硬寿命（默认 10min，超期 turn 已失执行权，见 D14 说明），部署可经 `agent.cleanup.turn-lease-max-lifetime-minutes` 调高 |
| HITL / confirm | **强耦合，非不涉及（前提修正 + 硬截止决议）**：确认可用性双源（AgentState ASKING 无 TTL 权威 + confirm_context 兜底行），行 TTL 过期不阻断确认（state 恢复）→ 确认窗口原本无界，池化前"容器 60min > 确认任何合理延迟"勉强安全。决议改为 D16 硬截止（deadline 10min）：截止内确认容器必活（D15 不变量）无缝续跑；迟到确认 410 作废 + 自动按拒绝收尾（复用 reject→DENY），会话解锁。超时处理三种形态见 D16：迟到确认 410 / 迟到消息懒收尾放行 / 永不交互无害挂起（零后台任务） |
| durable SSE | 不涉及（事件面与沙箱生命周期无耦合；借出等待的心跳覆盖见下） |
| 借出等待体验 | **零新增机制**：池等待发生在 turn 租约之后的执行排队段，issue #87 的跨层 `waiting` 心跳（`ChatStreamController` §2.5，15s/帧，首事件前）自动覆盖——用户看到既有"排队中"反馈，网关读超时 3600s 无切断风险；等待超时报错经 SandboxException 链路回传（消息明确"沙箱繁忙请稍后重试"，模型/用户双向可读） |
| Debug Console / `PUT /sandbox/env` / present_file | present_file/onAgent 改 per-turn `SandboxAcquireResult` 寻址（PR #251，进程级 `getLatestSandbox` 已删除）；`SandboxLocator` 语义不变（容器 id 仍真实存在）。**env 生命周期与池化强相关（2026-11-05 复核补）**：`PUT /sandbox/env` 只写容器内 `.env`+sidecar、无宿主真相源（sandbox-env-update-design §7"容器销毁需重调"）——归还 kill 即丢；池化使换代从 60min 异常路径变为常规路径，丢失面收窄依赖 D17 粘滞窗口（窗口内继承/复活 env 保留）；窗口外丢失为**已知代价**（§7/§11），env 宿主真相源（借出时重放）为可选后续、另立 issue |
| `SandboxClient.delete()` | 路由进池 kill + 补位（D13，§5.7）——不再是无副作用的死代码路径 |
| Pod 生命周期 | 优雅停机 `@PreDestroy` 清理（D12）；崩溃孤儿由 Server GC 兜底（§5.7） |
| e2e mock 沙箱 | 池默认关闭，mock 路径零改动；池开启的 e2e 单列（§8） |

## 7. 资源模型对比

| 维度 | 现状 | 池化后 |
|------|------|--------|
| 容器总数 | = 历史会话数（无界），每个滞留至 60min 超时 | ≤ `MAX_SIZE=20`（**集群级**，D10 共享池——任意副本的借出/归还共用同一份容量，流量倾斜不退化；D18 弹性水位使常驻随需求在 `MIN_IDLE~MAX` 间滑动；D17 单用户 ≤ `USER_MAX=5`）（**主要收益：上限有界且语义对齐共享的 Server 容量**） |
| idle 常驻成本 | 每容器全额 limit 保障（request 未设置、等效 request=limit） | 每容器仅 request 水位（`100m/128Mi`，D19），借出工作时 burst 到 limit；`MIN_IDLE=5` 底座 ≈ 0.5C/640Mi 保留 |
| 会话首 turn 延迟 | 含容器冷启动（秒级） | 命中 idle 池即用（毫秒级借出）；池满时排队 `MAX_WAIT`（15s waiting 心跳反馈，issue #87） |
| 会话失活后资源占用 | 容器滞留 ≤ 60min | `IDLE_RETURN_SECONDS`（默认 10min = 确认截止 deadline 等值，D15/D16-④）后归还——单容器滞留从 60min 收窄到 10min，配合总数上限才是完整收益 |
| 会话回来工作区窗口 | 容器存活至 60min（TTL 内回来文件还在） | 同 sid：`IDLE_RETURN`(10min) 内 resume 直连（文件/env 还在）；同用户新 sid：`STICKY_TTL` 窗口内可继承（D17，工作区/env 延续）；窗口外借新容器（KV 资产自动重注入，容器内未跟踪产物与 `PUT /sandbox/env` 写入的 env 丢失）——**窗口 60min→10min 为已知代价**，需长窗口的业务调大 `IDLE_RETURN`/`STICKY_TTL`（前者须同步调大 deadline 维持 D15 不变量） |

## 8. 测试计划

- **单测**（`SandboxPoolManagerTest` / `OpenSandboxClientTest` 扩展）：
  - 借出/归还状态机（含 max-wait 超时报错、借出前探测失败重试、补位失败降容量）；
  - `start()` 重复调用幂等（CAS 锁定，§5.2 注）；
  - Redis fail-soft：租约操作异常时跳过回收、借出不受阻；
  - **D14 双保险**：租约过期但 turn_lease 活跃 → 不回收且重置租约（模拟"LLM 长生成无 exec"场景：手工过期 Redis 键 + 存活 turn_lease）；
  - **D15 不变量**：`IDLE_RETURN < deadline + 缓冲` 配置 → 启动 fail-fast；
  - **D16 硬截止**：截止内 confirm → 容器未换、续跑可见前半段产物；迟到 confirm → `410 confirm_expired` + 自动按拒绝收尾（turn 拒绝终态、会话解锁）；迟到普通消息 → 懒收尾后放行新 turn；时钟源双档（行 created_at / state 更新时刻）一致；
  - **⑤ 预检续约边界竞态**：deadline 最后 1s 内 confirm → 容器存活则续约续跑；容器已被扫描回收 → 预检即 410（绝不进入 resume 中途 404）；注入 MySQL/Redis 双钟偏差 ±5s 用例；**跨副本用例**：副本 A 借出、确认落副本 B → touch 走租约键（活性真相，非 busy 归属账本）不被误 410；POST /confirm 直达路径（不经 confirm-stream 预检）同样续约；
  - **优雅停机**：@PreDestroy 不杀任何容器（D12，仅清理本地 creating 句柄）；在途 turn 不被 kill 打断（rollout 场景）；
  - **跨副本会话迁移（§5.5 不变量）**：turn 1 在 A 借出、turn 2 落 B → resume 直连同一容器、**B 不发生借出**；迁移后回收判定依赖全局租约+turn_lease（B 活跃期间不回收）；回收后复活落任意副本 → 404 降级从**共享 idle** 借新容器；
  - **共享池原子性（D10）**：并发 borrow 唯一性（N 并发借出 N 容器、零双借）；并发回收认领（多副本同时扫同一过期条目，仅一个 kill）；集群 cap（并发按需建/补位不破 `MAX_SIZE`）；多副本同时启动预热不超额（Lua cap 合力补齐）；
  - **Redis fail-open**：Redis 停机 → 借出降级直接建容器、对话可用性不劣于现状、限流告警；恢复后账本自动收敛（fail-open 超发容器由 Server TTL 兜底消亡）；
  - **Cluster 模式**：`AGENT_REDIS_MODE=cluster` 下全部 Lua 单 key（borrow/cap/claim/promote/prune 无 CROSSSLOT）；borrow 两步间隙（ledger 入账→SET 租约）崩溃 → 宽限规则跳过/超时回收；续约 upsert 在租约过期后自愈重建（不掩盖真失活：D14 兜底）；
  - **认领-续约竞态**：判定通过后、kill 前注入 resume 续约（upsert 租约）→ 认领被复核撤销、容器存活续跑；复核后才续约（微窗口）→ 容器死亡但下 turn resume 404 自愈重借（行为锁定，不 silent）；
  - **崩溃无孤儿**：副本 kill -9 后其借出条目由存活副本在租约过期后认领回收（不再依赖 Server GC 兜底）；
  - 开关关闭：`create()` 行为与现状逐分支一致（对照测试）；
  - **D17 用户配额/粘滞（2026-11-05 增补）**：配额内借新（不复用自己粘滞容器）；配额满 LRU 继承（原子换绑新 sid）；配额满无粘滞空闲 → `user_quota_exhausted`（与 `pool_exhausted` 两种错误可区分）；关会话立即转粘滞、立即可继承；STICKY_TTL 到期 kill 释放配额与容量；继承后旧会话复活被 resume 账本校正挡下（模拟 404 → 降级走借出决策树）；复活 vs 继承并发竞态单赢家（单 key Lua）；双副本配额集群级（A 副本借出 B 副本计数）；MIN_IDLE 水面不含粘滞 idle；
  - **D18 弹性水位（2026-11-05 增补）**：利用率 80% 触发 T 抬升且并发扫描单赢家；抬升后异步补建到 T 不超额（含多副本）；50%×3 周期+冷却回缩、LRU kill 超额通用 idle 不杀粘滞；冷却窗口内二次水位变更被拒；T 滞后于需求时按需建直通（借出不因 T 等待）；5→10→15→20 阶梯与 clamp（T 永不超 MAX）；79↔81% 摆动负载 T 稳定不抖；
  - **D19 资源双档（2026-11-05 增补）**：options 组装含 `resourceRequests`（env 覆盖默认）；借出剩余寿命 < 5min 弃用换新；池关闭路径同样透传 requests（独立于开关生效）；
  - **D14 硬寿命边界（2026-11-05 复核补）**：exec 在途（流式续约）超硬寿命不被回收；纯 LLM 长生成超硬寿命后首个工具调用命中死容器 → resume 404 自愈重借（行为锁定，非静默）。
- **E2E**（CI 新增非必需 job 或并入现有沙箱套件）：mock OpenSandbox 下池开启——多会话借还、池满等待、失活回收后新会话复用重建容器、resume 404 降级。
- **双副本门禁**：e2e-multi 保持绿（池开启配置跑一轮冒烟）。

## 9. 回退方案

`SANDBOX_POOL_ENABLED=false` 即回现状（容器按会话建、60min 超时 GC）。池开启运行中发现问题，切开关重启即回退，无数据迁移（Redis 池账本与租约键 TTL 自亡、粘滞容器由 Server TTL 兜底消亡）。D19 的两个 request env 独立于池开关、不随开关回退——非池路径同样受益于低 request 启动，一般无需回退；如需完全复原删 env 即可。

## 10. 验收清单

- [ ] 池开启：容器峰值 ≤ `MAX_SIZE`（**集群级**，双副本压测倾斜流量验证不退化）
- [ ] 会话失活（停止发言）后 `IDLE_RETURN_SECONDS` 内容器被 kill 并重建入池
- [ ] **活跃会话容器绝不被误回收**：覆盖纯 LLM 无工具 turn、turn 间空闲 < TTL、长 turn 中途、确认前touch（五点续约逐一验证）
- [ ] 池满时新会话等待 ≤ `MAX_WAIT`，超时收到可感知错误（不悬挂）；**idle 空但总量 < MAX 时按需新建、不干等**
- [ ] 归还重建的容器对新会话完全干净（无上一会话文件/skills 残留）
- [ ] resume 404 降级 create 正常（kill 后同 session 复活场景）
- [ ] Redis 停机：对话可用性不劣于现状（fail-soft）；**`AGENT_REDIS_MODE=cluster` 下全功能可用**（单 key Lua 无 CROSSSLOT、多副本并发借出/回收正确）
- [ ] 优雅停机/rollout：容器零销毁、在途 turn 不被打断；副本崩溃无孤儿（存活副本认领回收）；**跨副本确认不被本地状态误 410**（touch 全局键）；`delete()` 调用不缩池容量
- [ ] **HITL**：ASKING 挂起截止内确认 → 原容器续跑、前半段产物可访问；**deadline 边界（最后 1s）确认** → 预检续约生效续跑或干净 410，绝无"通过预检却中途 404 借新容器"
- [ ] **HITL 超时**：迟到确认 → `410 confirm_expired` + turn 拒绝终态收尾 + 会话解锁（后续消息可正常发）；迟到普通消息 → 懒收尾放行；永不交互 → 无害挂起、无后台任务
- [ ] **turn 中途**：无 exec 的长生成期间（租约手工过期 + turn_lease 活跃）容器不被回收
- [ ] **D15 启动校验**：`IDLE_RETURN < deadline + 缓冲` 配置被 fail-fast 拒绝
- [ ] 借出等待期间 chat 链路有 `waiting` 心跳帧；池状态（idle/busy/creating/waiters）可从 Debug 端点读取
- [ ] 池关闭：全量回归与 master 行为一致
- [ ] **D17（2026-11-05 增补）**：单用户容器持有 ≤ `USER_MAX`（含空闲）；配额满新会话继承粘滞容器（工作区/env 可延续）、无粘滞空闲报 `user_quota_exhausted`（消息可感知）；继承后旧会话复活不与新会话并发互踩（resume 账本校正挡板，跨副本生效）
- [ ] **D18（2026-11-05 增补）**：利用率 80% 水位抬升、低负载滞回回缩；借出永不因 T 等待（按需建兜底）；多副本水位变更单赢家
- [ ] **D19（2026-11-05 增补）**：池容器 request 生效（Server 侧 Pod spec 断言）；剩余寿命校验防借出临期容器；池关闭路径 request 透传不回归

## 11. 体验影响评估（三类使用方）

| 使用方 | 影响 | 结论/缓解 |
|--------|------|-----------|
| **用户**（前端对话） | ✅ 首 turn 免冷启动（idle 借出毫秒级）<br>✅ 池满等待有既有 15s `waiting` 心跳反馈（issue #87 自动覆盖，无新增静默窗口）<br>✅ 超时报错消息可读（"沙箱繁忙请稍后重试"）<br>⚠️ 确认卡改为 **10min 硬截止**（现状无限期可确认）：超时点确认 → 410 作废 + 按拒绝收尾，需前端展示倒计时/过期态（实施项）<br>⚠️ 离开超 10min 回来：KV 资产（记忆/技能/上传）自动重注入无感，但容器内未跟踪产物（pip 包、outputs 中间文件）丢失——窗口 60min→10min<br>⚠️ env（`PUT /sandbox/env` 录入的敏感值）随容器换代丢失（粘滞窗口内保留/可继承），窗口外需重设——错误表现为脚本读到空 env、指向外部服务而非平台，注意甄别（2026-11-05 复核补）<br>⚠️ 用户并发沙箱配额（D17，默认 5、含空闲）：配额满且无空闲时新会话报错（提示结束其他会话）——单用户不再能挤占全池<br>✅ 同用户新会话在粘滞窗口内可继承工作区/env（配额压力下的显式规则，非意外串数据） | 确认截止是产品语义决议（本轮已确认采纳）；需要长窗口的业务调大 `IDLE_RETURN`（同步调大 deadline 维持 D15）——有界容量的必然交换，已在 §7 明示 |
| **开发者**（OAF 服务作者/运维） | ✅ 全 env 配置、默认关闭零影响、开关联回退<br>✅ `[sandbox-pool]` 全事件结构化日志（sid+sandboxId+水位）<br>✅ Debug 端点池状态 + 水位告警（池满 WARN = 该扩容的信号）<br>✅ 共享池（D10）**单一账本**（Redis）：排障一处可查全集群 idle/busy/creating，无跨副本翻日志；rollout/崩溃不再有容器善后心智<br>⚠️ 容量规划新增维度：集群 `MAX_SIZE` 与 OpenSandbox Server 容量对齐（副本扩缩容**不动**池容量——池与副本数解耦）；D19 公式：调度保障 `MIN_IDLE×request ≤ 节点可分配`、峰值竞争 `MAX_SIZE×limit`、公平性建议 `MAX_SIZE ≥ 2×USER_MAX` 并按并发用户数规划 | §5.7 可观测性覆盖排障主路径；容量公式写入部署文档（实施项） |
| **AI agent**（模型视角的沙箱） | ✅ 活跃会话容器稳定（turn 执行中/turn 间 <TTL 绝不换容器，D7+D14+D15 三层保证）——模型对环境的连续性感知与现状一致<br>✅ 新容器与现状"容器超时重建"同构：投影/技能/上传/记忆四类注入链路原样复用，模型无新失败模式<br>✅ 确认超时自动 DENY 与现有 reject 语义同构（模型看到工具被拒、turn 正常收尾），非新事件类型<br>⚠️ 容器换代后模型可能引用已不存在的历史文件（`cat result.csv` → not found）——现状 60min 超时同样存在该失败模式，池化仅提高频率（60min→10min 窗口）；错误信息本身是模型可理解并可自愈（重新生成）的 | 不做产物跟踪/迁移（非目标）；换代时日志含 sid+sandboxId 供会话层追溯 |
