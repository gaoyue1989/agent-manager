# 沙箱技能同步每 turn 性能优化（scoped 投放短路 / 快照键前缀 / 基线跳过 / 单次枚举）

> 状态：**已实施 + 基准验证 + 部署验证（2026-11-09）**。动机：66MB/2000 文件技能目录实测，session 首轮物化 ≈10~68s、第 2/3 轮稳态每 call ≈5.35s（远超 3s 目标）；本轮落地后稳态 ≈0.09s/call、新用户首轮 ≈2.86s。基准方法与原始成本模型见 [sandbox-skill-sync-optimization-design.md](sandbox-skill-sync-optimization-design.md)（上一轮稳态优化的前置：指纹缓存/revision 门控/快照差分，本轮不改动其语义）。

> **后续演进（2026-11-09，PR #285~#287，first-llm-latency）**——本文 §2.1 P0-1 描述的机制在同日后续 PR 中有三点实质演进（下文 §2.1/§3 为演进前时点表述）：
> ① **指纹源改为「包内技能源目录 stat 指纹 + L4 revision」组合**：stage 副本不参与指纹（stage 是 SDK 对包内源 + L4 的确定性物化，跨副本 mtime 不同会使共享层永不命中）；包内源生产为同 PVC 只读挂载、跨副本指纹天然一致，L4 revision 覆盖用户级差异——这也是跨副本共享层生效的前提（`OpenSandbox.java` 指纹注释，`OpenSandboxFilesystemSpec.java:83` packageSkillsDir 注入、装配点 `AgentScopeConfig.java:109`）。
> ② **新增跨副本 Redis 共享层 `ScopedFingerprintStore`**（键 `<agentRedisPrefix>sbx:scopedfp:{sandboxId}|{scope}`，TTL 7 天，读/写 fail-open，`ScopedFingerprintStore.java`）：**Pod 级三表仍为主读路径**，Pod miss 才回落共享层、命中时回填 Pod 指纹/scope 两表，投放成功后 Pod 级 + 共享层双写（`OpenSandboxFilesystemSpec.java:152-176`）——并非「Pod 三表降级为回填层」。
> ③ **scoped 投放移出首推理关键路径**：不再随每 call `hydrateForCall` 同步执行（`OpenSandbox.java:358-363`「不再在此处跑」），改为 doExec/readWorkspaceFile/downloadFile 命中 `.skills-cache` 时同步投放（CAS 每 call 一次；downloadFile 补漏）+ `stop()` 收尾若本 call 从未投放则补投。

## 1. 问题清单（66MB/2000f 真实环境基准，2026-11-09）

每 call（turn）技能同步链路实测分布（改造前）：

| # | 环节 | 实测 | 代码位置 | 根因 |
|---|------|------|---------|------|
| 1 | scoped `.skills-cache` 投放 | **每 call ≈4.9s**（build 1.03 + prune exec 1.12 + hydrate 2.74） | `OpenSandbox.hydrateScopedSkillCache` | 比对 hash 记在**实例字段** `lastSkillCacheHash`，而 `OpenSandbox` 包装实例每次 acquire 重建（`OpenSandboxClient.resume/create` 每次 `new OpenSandbox`）→ 「未变不重投」短路条件每 call 失忆 → 每轮全量重投 |
| 2 | 回写快照差分跨技能冲突 | **20 技能只回写第 1 个**（正确性 bug） | `WorkspaceSyncService.collectSkillFiles` | gate manifest 键为**技能内相对路径**（无技能名前缀）——多技能同布局同大小文件（包内技能常见 `scripts/run.sh` 结构）互相遮蔽，第 2+ 技能被当「未变」整技能跳过内容读；用户修改永不落库、容器换代即丢 |
| 3 | L4 物化（KV → 容器） | 首次 ≈57s（读侧 44.5 + 容器写 12.8） | `WorkspaceReader.listUserSkills` | ls + 每技能一次 glob，而 SDK glob 内部每次做**全命名空间分页扫描**（含内容）→ N 技能 = N+1 次全量搬运（20 技能 ≈1.4GB MySQL 流量） |
| 4 | L4 按用户膨胀 | `agent_fs` 66MB/用户；新代首次物化 57s | `WorkspaceSyncService.syncUserSkills` | 包内技能经投影进容器 `/workspace/skills` 后被回写**整份复制**进每个用户的 L4 KV；L4 越大，revision 未知的保守回退越慢，且 MySQL 存储 O(用户数) 增长 |
| 5 | KV 运行时文件注入 | ≈1.5s/call（L4 大时） | `OpenSandbox.injectRuntimeFilesIfNeeded` | `readRuntimeFiles` 的 `memory/` 列举走 KV 命名空间搜索，JdbcStore 按前缀连带扫描 skills 命名空间（2000 行 ×35KB）；实例标志每 call 失忆 → 每 call 重付等值幂等的重注入 |

另有两项护栏缺口（多会话规模化评估，2026-11-09）：`skillSyncGates` 容量 256 超限 **`clear()` 清全表**（全部活跃会话下一 call 回落全量路径，风暴面）；宿主 `.skills-cache` GC 只有按龄规则（2 天窗口内高会话量可写满宿主盘，66MB/会话 scope）。

## 2. 设计决议

### 2.1 P0-1：scoped 投放 Pod 级指纹短路

- **指纹**：宿主 scope 目录的 stat 指纹（`relPath|mtime|size` 排序摘要，复用 `ProjectionFingerprintCache.statFingerprint`，2000 文件 ≈35ms，不读内容）。
- **记忆位置**：`OpenSandboxFilesystemSpec` 三张 Pod 级表（键 = Pod 本地，跨实例/跨 call 有效）：
  - `scopedSkillCacheFingerprints`：`sandboxId|scope` → 最近投放时指纹；
  - `hydratedScopedScopes`：`sandboxId` → 当前已投放 scope；
  - `scopedPayloadCache`：`sourceRoot|scope` → `{指纹, gz 字节}`（P1-3 载荷缓存）。
- **稳态判定**：指纹未变 **且** 本沙箱代已投放过当前 scope → 跳过 build、prune、hydrate 全部三步（稳态零 server 负载）。`hydratedScopedScope` 比对是关键：同代容器换会话（池化借出复用/同用户双会话，scope 不同）必须走慢路径 prune 旧 scope——**跨用户残留防护**不因短路而失效。
- **正确性边界**：与投影指纹缓存同一已接受盲点（容器内文件被模型删除而宿主未变 → 漏补投，宿主下次变化点自愈，不损坏数据，见上轮设计 §3.3）；mtime 纳秒级 + 宿主写者集合全部更新 mtime。
- 实例局部 `lastSkillCacheFingerprint`/`lastHydratedScope` 保留为无 spec 测试构造的兜底层。

### 2.2 P0-2：回写快照键加技能名前缀（正确性修复）

manifest 键从 `{relPath}` 改为 `{技能名}/{relPath}`（`WorkspaceSyncService.manifestKey`）。写入点（collectSkillFiles 记录、基线副本回填、SKILL.md 回写失败时的 `removeSkillEntriesFromGate` 清理）与读取点（差分比对、快照含 SKILL.md 判定）同步对齐。快照旧键形态不存在存量兼容问题：gate 是 Pod 本地缓存，升级后首轮为空 → 全读一遍即重建（语义 = 未知走现行为）。

### 2.3 P1-3：scoped 载荷缓存 + 单 exec 投放

- **载荷缓存**：慢路径优先复用 Pod 级 gz（指纹一致），省 build+gzip（66MB 实测 ≈1.8s）；字节上界单载荷 32MB / 总量 64MB，超限自动不缓存（LRU 逐条淘汰）。
- **自建 tar**：不走 `WorkspaceProjectionApplier.build`（它为比对语义附带全量 sha256，66MB ≈1.03s）——scoped 通道的变更检测已由 stat 指纹承担，只需 tar 字节（commons-compress，条目 = 工作区根相对路径，含隐藏文件，LONGFILE_POSIX）。
- **gzip 最快级别**（`Deflater.BEST_SPEED`）：传输量换 CPU（构建热点减半以上）。
- **prune 并入投放 exec**：`base64 -d | tar xzf - && rm tmp && { find ... ! -name '<scope>' -exec rm -rf {} + ; true; }` 单条命令——exec 固定 ≈1s，省一次独立 prune exec；尾段 `; true` 吞掉清理退出码（残留清理失败不判定投放失败，untar 段失败仍按失败上报重试）。

### 2.4 P1-4：L4 枚举改单次命名空间扫描

`listUserSkills` 从「ls + 每技能一次 glob」（N+1 次全命名空间搬运）改为**一次 `baseStore.search` 大页枚举**（500/页，分页取尽）+ 客户端按顶级段分组。过滤规则与 SDK 版逐条一致：元数据段（`.`/`_` 开头，含 `/.revision`）不进任何技能、无 SKILL.md 的分组不算技能、字典序输出。枚举失败仍抛 `IllegalStateException`（物化/删除路径的正确性契约不变）；管理面 `listSkills` 保持存量钉死的降级语义（捕获后返回空列表，`UserSkillServiceTest` 固定）。`listSkillFiles`（glob 版）保留给 `deleteUserSkill` 单技能路径。

### 2.5 P1-5：包内基线不回写 L4

- **规则**：本沙箱代**首次见到**某容器技能时（gate manifest 无该技能前缀键），比对容器清单与包内基线（`SkillManageService.getSkillsDir()`，L2）：文件集合一致且逐文件字节数一致 → 纯基线副本 → 只把清单记入快照、不回写 KV。任一文件不同 → 照常回写（用户覆盖）。
- **只在首见时比对**：已入快照且未变的技能走 collectSkillFiles 差分自然零读写，每轮重复走基线清单是纯开销（20 技能 ≈21 次 listDirectory/call）。
- **大小一致的原位内容漂移不感知**：与快照差分同一已接受边界（上轮设计 §7）。
- **效果**：L4 只存用户真实覆盖；`agent_fs` 从 O(66MB×用户数) 收敛为 O(覆盖差异)；新代物化、memory 命名空间扫描等连带成本同步消失。存量 L4 行不自动清理（无害：revision 门控命中后零成本；收敛需显式删除覆盖）。
- 开关 `SANDBOX_SYNC_SKIP_PACKAGE_BASELINE`（默认 true）。

### 2.6 P2-6：memory 缺席探测 + 注入收敛到每沙箱代

- **缺席探测**：`syncRuntimeFiles` 先一次 `listDirectory(/workspace)`（无尾斜杠形态，≈12ms）——MEMORY.md 与 memory/ 都不存在（新用户会话常态）直接返回，省 readByteArray 与 memory/ 列举两次 404 异常路径；列举返回 null/异常 → 保守回落原逐项路径（适配器兼容）。
- **注入收敛**：`injectRuntimeFilesIfNeeded` 的记忆从实例标志（每 call 失忆）收敛到 gate 的 `lastRuntimeInjected`（每沙箱代一次）——同代内 turn 结束回写已把容器记忆同步进 KV，重注入是等值幂等；代边界 gate 随 sandboxId 换新，首 turn 必注入。

### 2.7 P1 收尾：create 时投影指纹预热

`OpenSandboxClient.create/createFromPool` 在 `sandbox.start()` 后调用 `prewarmProjectionFingerprintCache()`：start 的 SDK 投影刚验证过「宿主投影 ↔ 容器」一致并写入 state hash——此刻播种 `ProjectionFingerprintCache`（指纹 ↔ hash 对应关系是真值），本代首个 exec 的投影新鲜度检查直接短路（省冷 build ≈0.8s）。**resume 不播种**（跨 pod 恢复的 state hash 未经验证，播种会跳过必要的补水合）。

### 2.8 护栏（规模化评估落地）

- **gate 表 / userInjectedSandbox / scoped 三表全部 LRU 有界**（accessOrder LinkedHashMap，超限**逐条淘汰最久未访问项**，替代原 `clear()` 清全表——清全表会让全部活跃会话下一 call 回落全量路径，多会话并发下形成风暴）。
- **`.skills-cache` 容量护栏**：`AGENT_CLEANUP_SKILL_CACHE_MAX_TOTAL_MB`（默认 5120=5GB，≤0 关闭）——总大小超限按「子树最新 mtime 最旧优先」逐目录回收，与按龄规则叠加（时间护栏防「不再使用」，容量护栏防「2 天窗口写满盘」）。

## 3. 基准验证（66MB/2000f 原条件，真实 OpenSandbox + MySQL）

基准程序驱动真实 `hydrateForCall`（revision 门控 + 投影指纹 + scoped 投放）与 `stop()`→`syncBack`（快照差分 + 基线跳过）完整每 call 序列，新包装实例模拟生产 acquire：

| 场景 | 改造前 | 改造后 | 达标 |
|------|--------|--------|------|
| 稳态每 call（第 2/3 轮） | **5.35s** | **0.08~0.10s**（指纹 35ms + revision 1ms + syncBack 24ms） | ✅（目标 <3s） |
| session 首轮（新用户，P1-5 后世界，L4 空） | ≈10.3s | **2.86s**（scoped 慢路径 2.5s + 投影预热后零 build） | ✅ |
| 首轮（L4 已膨胀存量，2000 行 KV） | ≈67.6s | 4.86s（每代一次性；含 revision 缺失的保守全量物化，生产有 revision 时 ≈0s） | ✅（稳态达标） |
| 每 call server 负载 | ≈5 次重操作（2 exec + 66MB 传输） | **0 次**（稳态零 exec、零传输） | — |

单元单价复核：`build()` 1029ms → 自建 tar+gzip-fast ≈0.75s；hydrate 单 exec（含 prune）2.74s → 2.5s；`listUserSkills` 44.5s → ≈2s；基线比对首轮后 0 content read。

## 4. 测试与部署验证

- **单测**（1870 用例 0 失败 5 跳过，2026-11-09）：新增/扩展——OpenSandboxTest（P0-1 跨实例短路、同代换 scope 必重投、P1-3 单 exec 断言）、WorkspaceSyncServiceTest（P0-2 同布局同大小双技能都落库、P1-5 纯基线跳过 + 修改技能照常回写）、OpenSandboxFilesystemSpecTest（LRU 逐条淘汰、scoped 三表读写与字节上界）、SkillCacheGcServiceTest（容量超限最旧优先、未超限不删、≤0 关闭）、WorkspaceReaderTest（SearchFailingStore 失败点随单次枚举重校准）。
- **E2E**：`E2E_GROUP=sandbox`（mock OpenSandbox）全绿——X18 投影新鲜度 / X10 管理面 PUT 物化 / X11 tombstone 防复活零回归。
- **部署验证**：镜像构建（agentscope-2.1.0-v20261109 分支版）→ kind 加载 → oaf-e2e-demo 服务更新 → 真实 3 连轮会话日志断言：首轮一次 scoped 投放、第 2/3 轮零 materialize/零 scoped/零 projection 日志（稳态零传输）。

## 5. 配置项汇总（本轮新增/语义不变）

| env | 默认 | 说明 |
|-----|------|------|
| `SANDBOX_SYNC_SKIP_PACKAGE_BASELINE` | true | P1-5 包内基线不回写 L4；false = 容器 skills/ 全量回写（旧行为） |
| `AGENT_CLEANUP_SKILL_CACHE_MAX_TOTAL_MB` | 5120 | `.skills-cache` 容量护栏（≤0 关闭），与按龄 GC 叠加 |

既有开关语义不变：`SANDBOX_PROJECTION_FINGERPRINT_CACHE` / `SANDBOX_SKILL_REVISION_GATE` / `SANDBOX_SYNC_SNAPSHOT_DIFF` / `SANDBOX_PROJECTION_ENABLED`。
