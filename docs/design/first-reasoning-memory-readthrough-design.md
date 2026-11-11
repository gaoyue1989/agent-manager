# 首推理 KV 路径优化：混合式记忆读穿 + 探针直供 + 批量枚举/合并写

> 目标：压缩「一次用户请求里，模型调用真正发生之前」的框架侧耗时。
> 口径与 first-reasoning-latency-optimization 系列一致：
> `ChatStreamController [chat] start`（HTTP 进入）→ `AgentTraceMiddleware PRE_REASONING`
> （首个 main LLM 请求发出）。模型自身 prefill/decode 不在口径内。
>
> 状态：**已实施（2026-11-11，feat/first-reasoning-kv-readthrough 分支）**。
> 本设计基于 master `5a15bb7` 实码落点逐项验证后编写；同目标的
> `first-reasoning-host-readthrough-design.md`（宿主读穿 + 惰性 acquire）为互补设计、
> 本文不覆盖其范围。OA 环境曾按纯读穿形态实施过一版（实测中位 12.03s→4.65s、
> 空闲窗口 2.1–2.5s），本版吸收其全部收益项并修正其一处数据丢失缺陷（§3.1）。

---

## 1. 问题定位（实码证据）

首推理前，框架侧串行执行四段 KV/容器同步，全部挡在首个 LLM 请求之前：

| # | 段 | 现状实现 | 成本 | 代码落点 |
|---|---|---|---|---|
| 1 | 运行时记忆注入 | `readRuntimeFiles` 逐文件 KV 读（1 read + 1 ls + N read，26 文件 = 27 次往返，≈24MB）+ `injectToSandbox` 全量写容器 | KV 2–3s + 容器写 0.8–4.2s | `WorkspaceReader.readRuntimeFiles:152` / `injectToSandbox:940` / `OpenSandbox.injectRuntimeFilesIfNeeded:826` |
| 2 | L4 技能物化 | 1 次 `listUserSkills` 枚举 + 每技能 2 次栅栏 get + 每文件 read + **每技能 1 次 `files().write`**（4 技能/46 文件 = 55 次跨网往返） | 1.1–5.1s | `WorkspaceReader.materializeUserSkills:855` |
| 3 | 系统提示探针 | SDK 提示组装期 `find skills -name SKILL.md` + 逐个 `sed -n '1,Np'` 读——读的正是第 2 段刚写入的字节，却再付 5 次 execd 往返 | 0.65–1.8s | `OpenSandbox.tryTranslateExec:466`（探测命令经此走真 exec 之外尚无容器侧短路） |
| 4 | exec 探测翻译缺陷 | `test -d /workspace` 经 `translateExists` 先 `readByteArray`——execd 对目录返回 500 异常路径；`test -e` 对不存在路径付两次 404 | 0.3–1.2s | `OpenSandbox.translateExists:587`（先 read 后 listDirectory） |

另有 turn 收尾 `.skills-cache` 补投（6.8MB gz）与下一请求首推理争用（PR #285~#287 引入的
stop() 兜底路径，`OpenSandbox.stop:256`）。

JDBC 底座能力已验证（反编译 `agentscope-extensions-mysql-2.0.3` 确认）：
`BaseStore.search` 的 SQL 为
`SELECT item_key, value_json, version FROM agent_fs WHERE namespace_path LIKE ? ESCAPE '!' ORDER BY item_key LIMIT ? OFFSET ?`
——**单次枚举自带内容**，`StoreItem(key, value:Map, version)` 三元组，非 N+1。

## 2. 设计总览

| 项 | 手段 | 开关 | 默认 |
|---|---|---|---|
| D1 混合式记忆读穿 | 探测窗口 KV 回落 + 首个真实 exec 触发补注入 | `SANDBOX_RUNTIME_READ_THROUGH` | `false`（旧行为） |
| D2 运行时文件单次枚举 | 27 次往返 → 1 次 search | —（纯内部重构） | — |
| D3 L4 单次枚举 + 合并单批写 | 55 次往返 → 1 search + 1 write | —（纯内部重构，失败回落逐技能） | — |
| D4 探针直供缓存 | 物化内容回填实例缓存，探测窗口免容器读 | —（随物化生命周期，严格失效） | — |
| D5 exec 探测修正 | `test -d` 目录直查 / `test -e` 父目录列举 | 复用 `SANDBOX_EXEC_TRANSLATE` | `true` |
| D6 收尾补投开关 | stop 补投可关 | `SANDBOX_SKILL_CACHE_STOP_BACKFILL` | `true`（旧行为） |

D2–D4 为往返数削减、结果语义不变；D1/D5/D6 有行为面变化，全部开关化且默认关闭/保持旧行为。

## 3. 各项设计

### 3.1 D1 混合式记忆读穿（P0，含一处数据丢失缺陷的修正）

**纯读穿方案（OA 一版）有一个已闭环验证的丢数据链，本设计以「混合式」修正：**

```
纯读穿：容器不再注入记忆文件
  → 模型 bash `cat /workspace/MEMORY.md` / `ls memory/`：文件不存在，读不到自己的记忆
  → 模型认知「无记忆」→ 新建一份只含上下文片段的部分内容 MEMORY.md
  → stop() 回写 syncRuntimeFiles（WorkspaceSyncService:134-144 缺席探测）：
    memoryMdExists=true → 不跳过 → readByteArray 成功
  → writeWorkspaceFile → writeKvFile：KV 现值(3.18MB) ≠ 新值(部分) → 全量覆盖
  → KV 记忆被截断，无告警
```

文档一版的「回写侧天然安全」只论证了「容器无文件不删 KV」这半边；「容器有部分新文件
覆盖 KV」这半边在纯读穿下被打开（现状注入形态下模型能先读到全量再改，此链不存在）。

**混合式（本设计）**：三层组成，任何一层单独看都与现状语义对齐——

1. **探测窗口 KV 回落**：`SANDBOX_RUNTIME_READ_THROUGH=true` 时跳过预热注入
   （`injectRuntimeFilesIfNeeded` 直接置位并跳过，日志
   `runtime files read-through enabled, skip eager injection`）；此后
   `translateRead` / `translateLs` / `translateGlob` / `translateExists` 在容器缺失时，
   对记忆路径（`MEMORY.md` / `memory/*.md`）回落 KV 直读/列举
   （`WorkspaceReader.readMemoryFile` 点查 `baseStore.get`、`listRuntimeFiles` 枚举），
   `translateRead` 按原 `sed '1,Np'` 语义渲染。覆盖 SDK 提示组装期全部记忆探针。
2. **真实访问补注入（修正点）**：`doExec` 里翻译不命中（= 真实 exec）且命令符合记忆访问
   形态时，**先同步补注入再执行**（CAS 一次性，照搬 `.skills-cache` 延迟投放先例
   `OpenSandbox.doExec:300-304` 的模式）。此后容器有全量副本，bash 读、edit_file 追加、
   turn 收尾回写全部回到与现状完全一致的语义。翻译命中的探测命令**不触发**补注入——
   首推理探测全是模板命令，注入成本保持为零。触发判定（`looksLikeMemoryAccess`）：
   命令文本含 `MEMORY.md` / `memory`，**外加 `python3 -c` 前缀**——SDK edit_file 模板把
   路径/old/new 编进 base64 载荷（字母表不含 `.`），按文本匹配永远命不中；edit 追加记忆
   是核心语义，宽松触发可接受（补注入一次性、fail-soft、空 KV 零成本）。
   另一边界：容器存在但为空的记忆文件按 `__EMPTY__` 应答、**不回落 KV**——「模型在
   call 内清空过 MEMORY.md」≠「文件缺失」，回落会读到陈旧全量内容（评审修正）。
3. **回写不变**：`syncRuntimeFiles` 原样（只回写容器存在的文件）。混合触发器保证
   「模型动记忆之前容器必先有全量副本」，部分内容覆盖链被关闭。

KV 点查用 `baseStore.get`（主键查询）而非 search+过滤——search 是 `namespace_path LIKE
'runtime_ns%'` 前缀扫描，会把 `…/users/{uid}/skills` 子命名空间整族行（含内容）拖过
JDBC（L4 大时即 `OpenSandbox.java:822` 注释记载的 ≈1.5s/call 噪声来源）。

### 3.2 D2 运行时文件单次枚举（P0）

`readRuntimeFiles` 从「1 read + 1 ls + N read」改为**一次 `baseStore.search` 大页分页
枚举**（机制先例：`listUserSkills:395` 同款），客户端按 `MEMORY.md` 精确匹配 /
`memory/一级.md` 前缀过滤，跳过元数据段（`.`/`_` 开头）。往返 27 → 1。
`StoreItem.value.content` 即内容（search 自带，无需逐条 get）。

### 3.3 D3 L4 单次枚举 + 合并单批写（P0）

- 新增 `readUserSkillBundle(userId)`：一次 search 枚举技能命名空间，同时产出
  「技能文件内容 + `.deleted`/`.disabled` 栅栏」。
  **栅栏键不能复用 `collectSkillKey` 的 `isMetadataSegment` 过滤**——`.deleted`/
  `.disabled` 以 `.` 开头恰是被滤掉的段（`WorkspaceReader.java:59,364,378`），bundle
  按「rel 段精确等于栅栏名」单独归类，其余按元数据规则过滤。
- 新增 3 参 `materializeUserSkills(sandbox, userId, contentSink)`：全部技能合并为
  **一次 `files().write(entries)`**（N 技能 N 次 execd 往返 → 1 次），保留原有上限
  （单文件/单技能总字节/文件数）、tombstone/disabled 跳过规则与 **SKILL.md 门控**
  （无主文件的纯资源目录不算技能、不物化——对齐旧枚举路径语义，评审补回）；
  **合并写失败回落逐技能写**（保持现状的失败粒度：单技能失败不影响其余技能）。
  `contentSink` 把写入内容回填给 D4 的探针缓存；2 参版委托 3 参版（sink=null），行为不变。
- `OpenSandbox.materializeUserSkills` 的 revision 门控（`SANDBOX_SKILL_REVISION_GATE`）
  交互：revision 未变跳过物化时 sink 不回填、探针缓存不建立——该 call 探测回落容器读
  （容器本就有上一代物化内容），行为正确，仅无直供收益。

### 3.4 D4 探针直供缓存（P0）

实例级 `probeContentCache`（容器绝对路径 → 内容）：

- 物化成功后置 `probeCacheValid=true` 并回填写入内容；
- `translateRead` 命中缓存直接渲染（与容器读共用同一渲染函数，`__NOT_FOUND__`/
  `__EMPTY__`/`sed '1,Np'` 语义一致）；`translateGlob` 对 `-name SKILL.md` 模式且根在
  `skills/` 下时，跳过缓存已覆盖技能目录的容器遍历（`walkFilesSkippingDirs`）、合并
  缓存条目——**仅限 SKILL.md 模式**（系统提示探针读的恰是刚写入的字节）；其他模式
  照常遍历容器，保持「容器实时状态」语义（物化只写不删，历史残留文件对 glob 仍可见）；
- **失效不变量**（缓存有效期内容器 skills 树必须与缓存一致，任何破坏即失效）：
  ① 任何真实 exec（翻译不命中）立即失效并清空——SDK 文件工具写容器走 shell 模板
  命令，天然被覆盖；② `refreshProjectionIfStale` 实际执行了 `doHydrateWorkspace`
  时失效（投影可能含包内 L2 skills）；③ `stop()` 失效并清空。失效只损失性能、不损失
  正确性，因此从严不从宽。

### 3.5 D5 exec 探测修正（P1）

- `P_TEST_D`（`test -d <path>`）：改为**一次 `listDirectory` 直查**（目录 → 200 返回
  条目 → exit 0；不存在 → 404 → exit 1）。不再走 `translateExists` 的「先
  `readByteArray`」——那对目录必付一次 500 异常路径（整段堆栈 0.3–1.2s）。
- `translateExists`（`test -e`）：改为**一次父目录列举判存在**（文件/目录通吃，
  叶名精确匹配；父目录不存在即 404 → false）；父目录列举异常时回落旧两步实现
  （readByteArray → listDirectory），保证根路径等边角语义不回退。
  网络成本：不存在路径两次 404 + 异常栈 → 一次 200/404，无异常路径。

### 3.6 D6 收尾补投开关（P2）

`SANDBOX_SKILL_CACHE_STOP_BACKFILL`（默认 `true` = 旧行为）：置 `false` 时 stop()
不再执行「本 call 从未投放 `.skills-cache` 则补投」（`OpenSandbox.stop:256-258`），
缓存只在命令首次真正访问时投放。代价：turn 终态容器不含 `.skills-cache`，依赖它的
X18 探针/下轮快照差分基准需一并调整——因此默认不改变现有语义，仅提供关闭选项。

## 4. 正确性论证（D1 为什么不丢数据）

| 访问形态 | 现状（预热注入） | 混合读穿 | 是否等价 |
|---|---|---|---|
| SDK 提示探针（sed MEMORY.md） | 容器读 | KV 回落（同内容同渲染） | ✓（内容一致） |
| SDK 提示探针（ls/find memory） | 容器读 | KV 列举回落（mtime=0，SDK 仅用于排序展示） | ✓ |
| bash `cat` / `ls` 记忆路径 | 容器读 | 补注入后容器读 | ✓（先注后读） |
| SDK edit_file 追加记忆 | 容器全量副本上编辑 | 补注入后同 | ✓ |
| write_file 全量覆盖记忆 | 覆盖容器全量副本 → 回写覆盖 KV | 同（补注入保证覆盖发生在全量副本上） | ✓（盲写截断风险与现状同源等价，非本设计新增） |
| turn 收尾回写 | 容器 → KV | 同 | ✓ |
| 记忆关闭（AGENT_MEMORY_ENABLED=false） | readRuntimeFiles 空集、回写跳过 | 读穿回落同样短路（workspaceReader.isMemoryEnabled） | ✓ |

## 5. 风险与边界

1. **execd 单请求上限**：D3 合并写把全部技能 entries 并入一次请求，需确认 execd 对
   文件数/字节的请求上限（scoped `.skills-cache` 单次 6.8MB gz 已在生产验证同量级；
   超上限场景由「合并失败 → 逐技能回落」兜底，退化为现状行为）。
2. **探测清单未穷举**：SDK 提示组装的探测命令集以实测为准（host-readthrough 设计 R1
   同款第一步）；翻译模板升级改版时回落真 exec，安全降级（既有约定）。
3. **`test -d` 非 `/workspace` 路径**：D5 直查对任意路径成立（目录 200/缺失 404），
   不做 `/workspace` 特判短路——避免「模型 `rm -rf /workspace` 后框架仍答 0」的盲区。
   附带修正一处潜在缺陷：旧 `translateExists` 对「目录路径」先 `readByteArray` 成功也
   判存在，`test -d <普通文件>` 会误答 0；`directoryExists` 以列举语义正确答 1（有意的
   语义修正，非纯性能改动）。
4. **多副本一致性**：probe 缓存/读穿标志均为实例级，无跨副本状态；KV 为权威源，
   与架构强约束（业务面无状态可横向扩展）相容。
   **混合版本滚动升级窗口（评审披露）**：读穿开启的 Pod 会把 Pod 级
   `SkillSyncGate.lastRuntimeInjected` 置位而不注入——滚动更新期间旧代码 Pod 复用同一
   容器时按 gate 跳过预热注入、又没有 KV 回落能力，重新打开「容器无记忆副本」窗口
   （真实访问触发器只在新代码 Pod 生效）。**因此 `SANDBOX_RUNTIME_READ_THROUGH=true`
   的灰度必须一次翻完所有副本，或先关 gate 共享再灰度**；默认关闭不受影响。
5. **读穿 ls/glob 回落仍走全命名空间扫描**：`listRuntimeFiles` 复用 search 分页扫描
   （内容随行），L4 大时与旧 `ls` 路径同源（≈1.5s/call 量级）；读穿的性能收益在
   「消除预热注入的 24MB KV 读 + 容器写」主项，探测窗口的 ls/glob 回落为兼容兜底，
   每至多一次；单文件探测回落走 `get` 点查，无此成本。
6. **非工作区绝对路径**：`translateExists` 的父目录列举仅对工作区路径生效
   （`/etc/hosts` 等不改写成 `/workspace` 镜像误判），其余回落旧两步实现。
7. **与 host-readthrough 设计的关系**：互补不冲突；若未来实施宿主读穿，`translateRead`
   的回落层级需显式定为「直供缓存 → 容器 → KV → 宿主」，本设计已把 KV 回落点收敛在
   翻译层单一入口，届时按序插桩即可。

## 6. 回滚

| 开关 | 默认 | 置 false 效果 |
|---|---|---|
| `SANDBOX_RUNTIME_READ_THROUGH` | `false` | D1/D2 读穿与补注入全关，恢复预热注入（D2 单次枚举仍生效——纯内部重构，语义不变） |
| `SANDBOX_SKILL_CACHE_STOP_BACKFILL` | `true` | 关闭 stop 收尾补投 |
| `SANDBOX_EXEC_TRANSLATE` | `true` | D5 失效，探测全部回落真 exec（既有开关） |

D2/D3/D4 为纯内部重构（往返数减少、结果语义不变），不需要开关；
如需彻底回退，回滚 `WorkspaceReader` + `OpenSandbox` 两个文件即可。

## 7. 验证计划与实施记录

- 单测：`WorkspaceReaderTest`（readRuntimeFiles 单枚举过滤 / readMemoryFile 点查 /
  listRuntimeFiles / bundle 栅栏归类 / 合并单批写+contentSink / 失败回落逐技能 /
  SKILL.md 门控 / bundle 枚举失败显式抛）+ `OpenSandboxTest`（读穿跳过注入 /
  探测 KV 回落 / 空文件不回落 / cat 与 edit 形态补注入+inOrder 顺序 / 探针直供与
  真实 exec 失效 / test -d 直查 / translateExists 父目录 / 补投开关两态 /
  3 参重载 mock 适配）。
- 全量回归：`mvn test` 1937+ 例（master 1922 + 本次新增）；CI agent-framework-ci
  九 job 以 PR 门禁为准。
- **部署验证（kind 集群实测，2026-11-11）**：本地构建镜像
  `agent-framework:first-reasoning-1111` → kind load → patch e2e demo 双副本 →
  ①默认开关（旧行为）真实会话全链路正常（探测→物化→stop 回写完成，无异常栈）；
  ②`SANDBOX_RUNTIME_READ_THROUGH=true` 后日志实证
  `runtime files read-through enabled, skip eager injection`；③模型经 read 工具读到
  **只存在于 KV、从未预热注入**的记忆内容（探测窗口 KV 回落实证）；④模型执行
  `cat /workspace/MEMORY.md`（真实 exec）触发
  `runtime files lazily injected on real memory access (files=1)` 并原样复述 KV-only
  记忆标记——**D1 混合语义（防丢数据链核心）在生产形态闭环实证**；
  ⑤turn 收尾回写把容器记忆落回 KV（写回路径顺带实证）。验证后镜像/env/种子数据
  全部还原。
- code review：独立审查（对照 SDK 2.0.3 字节码核验命令模板与绕过面）提出 3 P1
  （edit_file base64 绕过触发器 / D3 SKILL.md 门控缺失 / 空文件与缺失混淆）与若干 P2，
  已全部修复（见 §3.1/§3.3/§5 与对应测试）。
- 时延收益不在本环境复现口径内（依赖 OA 记忆数据集），由 OA 侧回归（历史实测：
  中位 12.03s→4.65s、空闲窗口 2.1–2.5s，见文首）。

## 8. 涉及文件

| 文件 | 改动 |
|---|---|
| `agent-framework/src/main/java/io/agentmanager/framework/service/WorkspaceReader.java` | D2 单次枚举 `readRuntimeFiles`；D1 `readMemoryFile`/`listRuntimeFiles`；D3 `readUserSkillBundle`/`SkillBundle`/3 参 `materializeUserSkills` |
| `agent-framework/src/main/java/io/agentmanager/framework/sandbox/opensandbox/OpenSandbox.java` | D1 读穿开关 + 翻译层 KV 回落 + 真实访问补注入；D4 `probeContentCache`；D5 `P_TEST_D` 直查 + `translateExists` 父目录列举；D6 补投开关 |
| `agent-framework/src/test/java/io/agentmanager/framework/service/WorkspaceReaderTest.java` | 上述对应用例 |
| `agent-framework/src/test/java/io/agentmanager/framework/sandbox/opensandbox/OpenSandboxTest.java` | 上述对应用例 + 3 参重载 mock 适配 |
| `docs/design/first-reasoning-memory-readthrough-design.md` | 本文 |
