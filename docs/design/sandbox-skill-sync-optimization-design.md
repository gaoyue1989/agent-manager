# 沙箱技能同步链路优化设计（指纹短路 / 快照差分 / revision 门控 / 水合协调器）

> 状态：**已实施（2026-10-15 同日落地）**。收益数据来自本机真实环境基准（§2）；实施记录与偏差见附录 B，
> 验证证据（单测 1503 / e2e 沙箱组 13 断言 / 真实 OpenSandbox 冒烟）见附录 C。
> 前置阅读：[user-skill-admin-design.md](user-skill-admin-design.md)（L4 语义与回写栅栏）、`agent-framework/docs/e2e-ci-plan.md` X18（投影新鲜度）。

## 1. 背景与问题

沙箱模式下每次用户请求（call）的技能同步链路（`feat/sandbox-hydrate-optimization` 分支 982c955 已优化传输量：单文件直传/临时文件唯一化/gzip）之后，稳态下（任何内容都没变）每次 call 仍支付：

| # | 环节 | 现状行为 | 代码位置 |
|---|------|---------|---------|
| 1 | 投影新鲜度检查 | 每 call 首次 exec 无条件 `WorkspaceProjectionApplier.build()`：读投影根**全部文件字节** + sha256 + **堆内构建完整 tar**，hash 相同再整个丢弃 | `OpenSandbox.refreshProjectionIfStale`（`OpenSandbox.java:402`） |
| 2 | L4 物化 | resume 每次 acquire 新建 `OpenSandbox` 包装实例 → `userSkillsMaterialized` 归零 → 每 call 全量：KV list+glob+逐文件读 + execd 覆盖写**全部** L4 技能 | `WorkspaceReader.materializeUserSkills`（`WorkspaceReader.java:758`） |
| 3 | 回写拉取 | call 结束 `syncBack`：`listDirectory` 逐目录 + `readFile` **逐文件**读容器全部技能内容 | `WorkspaceSyncService.syncUserSkills`（`WorkspaceSyncService.java:145`） |
| 4 | 回写 KV | 逐文件 `writeUserSkillFile`：内部已有「读旧值比对、相同跳写」（`writeKvFile`，`WorkspaceReader.java:244`），稳态仍付 N 次 KV 比对读 | 同上 |

同一个用户的技能数据每次 call 被**完整读三遍**（宿主投影根 / KV / 容器）、**覆盖写一遍进容器**，而绝大多数 call 一个字节都没变。

## 2. 实测成本模型（本机基准，2026-10-15）

环境：OpenSandbox server `127.0.0.1:8090`（docker，useServerProxy=true，与生产同构）、镜像 `opensandbox/code-interpreter:v1.1.0`、MySQL `127.0.0.1:3307/agent_manager_test`（agent_fs 真表）、SDK agentscope-harness 2.0.3。基准程序：`/tmp/sandbox-bench/SandboxBench.java`（复现方法见附录 A）。

### 2.1 原始操作单价（B2/B3，SMALL：容器 72 文件 / 2.25MB）

| 操作 | 实测 | 说明 |
|------|------|------|
| `files().listDirectory` | **≈0.75 ms/op** | manifest 遍历 85 ops = 63.7ms |
| `files().readFile`（文本） | **≈5.5 ms/op** | 逐文件拉取 85 ops = 448.9ms（72 read + 13 list） |
| `files().readByteArray` | **100~193 MB/s** | 1MB=9ms / 4MB=23ms / 16MB=83ms，大块读吞吐极好 |
| `files().write`（batch） | 48 entries = **80ms** | 物化写侧现状成本 |
| `commands().run`（exec） | **固定 ≈1016 ms**（与载荷无关；`tar czf 2.25MB` 1105ms） | **热路径必须避开 exec** |

### 2.2 B1 投影新鲜度检查（每 call 一次，稳态）

| 工作区 | build() 现状 | stat 指纹（提案①） | 提速 | 附带收益 |
|--------|-------------|------------------|------|---------|
| SMALL（145 文件 / 4.5MB） | median **17.9ms** | **3.2ms** | 5.6× | 免掉每 call 4.6MB 堆内 tar 分配 |
| LARGE（613 文件 / 18.5MB） | median **47.9ms** | **4.9ms** | 9.7× | 免掉每 call 19MB 堆内 tar 分配 |

指纹灵敏度验证：增删/改动文件指纹必变；SDK hash 跨调用稳定。

### 2.3 B2 回写拉取方案对比（SMALL，72 文件）

| 方案 | 实测 | 结论 |
|------|------|------|
| 现状逐文件 | **448.9ms** / 85 ops | 基线 |
| 单 exec `tar\|gzip\|base64` 走 stdout 日志 | 10289ms | **否决**：日志流回传 ~3MB 慢 23× |
| exec tar 到 /tmp + `readByteArray` 读回 | 2144ms | **否决**：2×exec 固定 1s 主导，小规模反而慢 4.8× |
| **manifest 遍历（仅 listDirectory 元数据）** | **63.7ms** / 85 ops | **②′ 的基础**：+ 选择性读变更文件 |

关键结论：**批量传输形态不是出路（exec 固定 1s 时延杀死一切 exec 型方案），"不读没变的文件"才是**。`readByteArray` 吞吐好，留给真有大块变更时的单文件读取（readFile→readByteArray 换用在文件较大时收益明显，小文件二者近似）。

### 2.4 B3 KV 侧（SMALL，72 文件）

| 项 | 实测 |
|----|------|
| 物化读侧现状（listUserSkills+glob+逐文件 read） | median **36.5ms**（≈76 KV ops） |
| 提案③ 单 key get（revision 门控） | median **0.30ms**（**~120×**） |
| syncBack KV 稳态（逐文件比对读+跳写） | median 25.6ms |
| 首次全量写 72 文件 | 346ms（仅首写/真变更时支付） |

### 2.5 组合稳态收益估算

| 每 call 成本（SMALL / LARGE 外推） | 环节① | 环节②物化 | 环节③回写拉取 | 回写 KV | 合计 |
|-----------------------------------|-------|-----------|--------------|---------|------|
| 现状 | 17.9 / 47.9ms | 36.5+80 / ~480ms | 449 / ~1.9s | 25.6 / ~105ms | **~609ms / ~2.5s** |
| 优化后 | 3.2 / 4.9ms | 0.3+0 / 同 | 64 / ~260ms | ≈0 | **~68ms / ~265ms** |

**≈9× 提速；每 call 4.6~19MB 堆内 tar 分配归零；KV/op 写放大仅在真变更时支付。**

## 3. 设计①：投影 stat 指纹缓存（`ProjectionFingerprintCache`）

### 3.1 原理

`WorkspaceProjectionApplier.build()` 的 tar 构建与内容 sha256 占了现状成本的全部，而它的输入集合（`__workspace_projection__` 条目：`sourceRoot` + `includeRoots` 下所有 regular file，含隐藏文件，`Files.walk` 无 follow-links）在稳态下不变。用一个**不读文件内容**的 stat 指纹（`relPath|mtime|size` 排序后 sha256）做缓存键：指纹没变 → 宿主投影内容没变 → 直接复用上次 `build()` 得到的 SDK hash 与 `state.workspaceProjectionHash` 比对，跳过 build。

### 3.2 新类 `sandbox/opensandbox/ProjectionFingerprintCache`

```java
/** 进程级缓存：投影根 stat 指纹 → SDK 投影 hash（① 的核心，pod 本地即可） */
public final class ProjectionFingerprintCache {
    /** key = sourceRoot + '\0' + String.join(",", includeRoots)；条目 = statFp + sdkHash */
    private static final ConcurrentHashMap<String, Entry> CACHE = new ConcurrentHashMap<>();
    private static final int MAX_ENTRIES = 16;   // 单 pod 实际只有 1 个工作区，超限整体清空

    /** 与 SDK collectProjectedFiles 同文件集的 stat 指纹（includeRoots 下所有 regular file，含隐藏） */
    public static String statFingerprint(WorkspaceSpec spec) { ... }   // Files.walk + BasicFileAttributes，无内容读

    /** 命中且 state hash 相同 → true（调用方直接 return，跳过 build） */
    public static boolean skipBuild(WorkspaceSpec spec, String stateHash) { ... }

    /** build() 之后回填（fp 用 skipBuild 前算好的同一值，避免二次 walk） */
    public static void record(WorkspaceSpec spec, String statFp, String sdkHash) { ... }
}
```

`refreshProjectionIfStale` 改造（骨架）：

```java
var spec = state.getWorkspaceSpec();
if (spec == null) return;
if (SANDBOX_PROJECTION_FINGERPRINT_CACHE && ProjectionFingerprintCache.skipBuild(spec, state.getWorkspaceProjectionHash())) {
    return;                                  // 稳态：3~5ms，零 tar、零内容读
}
var payload = WorkspaceProjectionApplier.build(spec);   // 慢路径（指纹变化/缓存未命中/state hash 不一致）
... // 现有 hydrate + setWorkspaceProjectionHash 逻辑不变
```

### 3.3 边界与语义

- **指纹未变但 `cached.sdkHash != state.workspaceProjectionHash`**（新沙箱代/投影开关切换/缓存被清）：回落 build()（安全慢路径）。新沙箱代 start 时 SDK 已 hydrate 且 hash 即 `cached.sdkHash`，常态相等 → 正常短路。
- **mtime 粒度**：ext4/PVC 纳秒级 mtime；宿主投影根的写者全部经 `writeIfChanged`（SDK stage）/`WorkspaceInitializer`（启动重写）/OAF reload（重写），均会更新 mtime。「原位改内容且 mtime、size 不变」在生产写者集合中不存在；即便人为构造，后果止步于**漏一次投影补水合**（下个指纹变化点自愈），不损坏数据。
- **statFingerprint 抛 IO 异常**：不缓存、直接 build()（现行为）。
- 开关：`SANDBOX_PROJECTION_FINGERPRINT_CACHE`（默认 true，关闭=现行为）。

## 4. 设计②′：syncBack 快照差分（否决批量传输，改为"只读变更"）

### 4.1 数据否决记录（§2.3）

两个"单次批量拉取"方案均被实测否决：exec 型方案被 ~1s 的 exec 固定时延杀死（2 次起跳）；stdout 日志流形态再慢一个量级。`listDirectory` 元数据遍历（0.75ms/op）+ 只对**与快照不一致的文件**做 `readFile`/`readByteArray`，是唯一在所有规模下都严格优于现状的形态。

### 4.2 快照数据结构（与 ③ 共用）

`filesystemSpec` 上新增 pod 本地门控表（与现有 `userInjectedSandbox` 同模式）：

```java
/** userKey|sandboxId → 该沙箱代已知的 skills/ 文件状态（②′ 比对基准 + ③ 门控记录） */
private final ConcurrentHashMap<String, SkillSyncGate> skillSyncGates;

record SkillSyncGate(
    String revision,                  // 最近一次物化/回写时的 KV revision（③ 的比对值）
    Map<String, Long> manifest        // 容器 skills/{name}/{relPath} → 字节大小（listDirectory 的 size）
) { /* mutable holder，字段可更新 */ }
```

manifest 的三个来源（增量并集，**只会越齐越准**）：
1. **物化写入**：`materializeUserSkills` 实际写出的每个 WriteEntry（size=内容字节数）；
2. **回写遍历**：syncBack manifest walk 看到的每个文件（含被跳过未读的——记 size 供下轮比对）；
3. **投影刷新**（可选三期）：`refreshProjectionIfStale` 走慢路径时顺带解析 payload.tarBytes 的 entry（该分支本来就已付了 build 成本）。

初始（新沙箱代/pod 重启/无门控）：manifest 为空 → 所有文件视为"未知"→ 全读 = **现行为**，无正确性悬崖。

### 4.3 `syncUserSkills` 改造（骨架）

```java
var gate = filesystemSpec.skillSyncGate(userKey, sandboxId);   // 无则建空 gate
var manifest = walkManifest(osbSandbox, "/workspace/skills");  // 仅 listDirectory（≈0.75ms/op）
int changed = 0;
for (var entry : manifest) {
    var known = gate.manifest().get(entry.path());
    if (known != null && known.equals(entry.size())) continue;  // 未变：不读内容、不碰 KV
    var content = readFileForSync(osbSandbox, entry);           // readFile（小）或 readByteArray+UTF-8（大）
    ... // 现有栅栏（tombstone/disabled/admin-override）与限额过滤、SKILL.md 先写序全部保留
    changed++;
}
gate.manifest().putAll(manifest);          // 回写遍历结果（含未读文件）
gate.manifest().keySet().retainAll(manifest.keySet());   // 容器已消失的条目移出快照（不删 KV，语义不变）
```

- 栅栏三标记、`isValidSkillName`/`isMetadataSegment`、文件数/单文件/总量上限、SKILL.md-fail-fast 顺序：**全部原样保留**（只改"读什么"，不改"怎么判、怎么写"）。
- KV 侧：被读的文件仍走 `writeUserSkillFile`（内含比对短路，真变更才落库）。
- `MEMORY.md`/`memory/` 少量文件（2~3 个，~11ms），一期不动，三期可并入同一 manifest 机制。
- 残余风险（明确接受）：**同字节数的原位内容漂移不会被察觉**（size 是唯一比对元数据，execd listDirectory 无 mtime）。触发面=模型经 skill_manage 编辑后恰好字节数不变；后果=该次编辑不回写 KV，容器换代后丢失。开关 `SANDBOX_SYNC_SNAPSHOT_DIFF`（默认 true）一键回到全量读。
- 新沙箱代/删重建：gate 以 sandboxId 为键自然隔离；`OpenSandboxClient.delete` 后旧 gate 条目残留（无引用，可懒清理：容量上限 256，超限清全表）。

## 5. 设计③：L4 revision 门控（物化从"每 call 全量"到"一次 get"）

### 5.1 revision 键与自增点

技能命名空间（`agents/{agent}/users/{uid}/skills`）根下新增元数据键 `/.revision`（以 `.` 开头，`listUserSkills` 的 isMetadataSegment 天然过滤，glob 按 `/{name}/**` 也扫不到）：

- **值**：写入时间毫秒（无并发语义要求，任何变化都使值不同即可；`putIfVersion` 乐观写防乱序回退，失败重写一次）；
- **自增点（全部收敛在 `WorkspaceReader`）**：
  1. `writeUserSkillFile` → `writeKvFile` **实际发生写/删**的分支成功后（比对短路、参数非法不算）；
  2. `deleteUserSkillFile` 成功后（`deleteUserSkill` 内部逐文件走它，标记写入走 1，全覆盖）。
- 漏 bump 的后果=门控多物化一次（多付一次全量，不出错）；多 bump 同理。所有 L4 变更路径（管理面 PUT/DELETE/禁用、包内下发、syncBack 回写）都经这两个函数——**已逐一核对无旁路**。

### 5.2 门控流程（`materializeUserSkills` 包装）

```java
public void materializeUserSkills(OpenSandbox sandbox, String uid) {
    var sandboxId = sandbox.getOsbState().getSandboxId();
    var gate = filesystemSpec.skillSyncGate(uid, sandboxId);
    var rev = readSkillRevision(uid);                 // 1 次 KV get（0.30ms）
    if (gate != null && rev != null && rev.equals(gate.revision())) {
        return;                                       // 稳态：KV 未变 + 本沙箱代已投影过 → 跳过
    }
    int n = workspaceReader.materializeUserSkills(sandbox.getOsbSandbox(), uid);   // 现全量路径不动
    if (n >= 0) updateGate(uid, sandboxId, rev, materializedManifest);  // manifest 供 ②′
}
```

- **乒乓消除**：syncBack 完成后重读一次 revision 并刷新 gate（容器内容已写进 KV，二者此刻相等），避免"回写 bump → 下轮白物化"。syncBack 零变更时不刷（revision 没动）。
- 失败语义不变：物化抛异常只告警、不更新 gate，下轮重试（现 fail-soft 保持）。
- 多副本：gate 是 pod 本地缓存，KV revision 是全局真相——他 pod resume 同一沙箱无 gate → 回落全量物化（现行为），正确性不依赖缓存。
- 与 ②′ 的 manifest 联动见 §4.2；revision 键对 `UserSkillService.syncFromPackage` 等批量下发同样生效（其写路径经 `writeUserSkillFile`）。
- 开关：`SANDBOX_SKILL_REVISION_GATE`（默认 true）。

## 6. 设计④：SessionHydrate 水合协调器（纯重构，无行为变化）

`doExec` 里四段幂等注入各自解析 userKey（`resolveUserKey`/`peekPendingUserKey` 混用、`SandboxUserKeyMiddleware` 再来一遍），且 ①③ 的挂接点分散。收敛为单一协调器：

```java
/** 会话水合协调器：userKey 只解析一次，四步幂等注入的唯一入口（④） */
public class SessionHydrateCoordinator {
    public void hydrate(OpenSandbox sandbox, @Nullable RuntimeContext ctx) {
        // 1) userKey 解析（一次）：实例已绑 → spec.peekPendingUserKey → resolveUserKey(ctx)（session_user 反查）
        //    解析出且实例未绑 → setUserKey（middleware 与 doExec 双入口共用）
        // 2) injectRuntimeFiles   3) injectPendingUploads
        // 4) materializeUserSkills（③ 门控在内）   5) refreshProjectionIfStale（① 指纹在内）
    }
}
```

- 调用点收敛为两处：`SandboxUserKeyMiddleware.onAgent`（acquire 后、agent 执行前）与 `OpenSandbox.doExec`（首命令前）；幂等标志仍在 `OpenSandbox` 实例上，协调器无状态。
- middleware 中的上传 reset/`isInjectedOnSandbox` 记账留在 middleware（依赖 fileAssetStore，与水合无关）。
- 收益：一次 userKey 解析、统一日志口径、①③ 的开关与挂接只有一处；`stop()` 的回写侧（②′）独立留在 `WorkspaceSyncService`。

## 7. 风险与兼容性清单

| 风险 | 评估 | 缓解 |
|------|------|------|
| ① mtime 指纹漏检原位同 size 改动 | 生产写者集合（writeIfChanged/Initializer/reload）都更新 mtime；后果止步于漏一次补水合，下个变化点自愈 | 文档钉死 + 开关 |
| ②′ 同字节数内容漂移不回写 | 触发面小（编辑通常变 size）；后果=该次编辑不落 KV | 开关回到全量读；E2E 加"改 size 场景必回写"断言钉住主路径 |
| ③ revision 漏 bump | 已核对全部 L4 写路径收敛于 `writeUserSkillFile`/`deleteUserSkillFile` | 后果只是多物化一次；单测覆盖每个 bump 点 |
| exec 固定 1s 时延的环境差异 | 本机 docker 同构部署；K8s 内经 Server proxy 同路径 | 实施后先在 e2e 沙箱 job 观察 INFO 计数（见 §8） |
| 缓存条目泄漏 | gate 表/指纹表容量小且有上界 | 懒清理（超限清空），条目量级=沙箱代数 |
| 回归面 | X18（投影新鲜度）/E8/E9（L4 栅栏）语义不动 | §8 全量回归 |

## 8. 测试与验证策略

- **单测**：指纹缓存（命中/灵敏度/state-hash 不一致回落/IO 异常回落/超限清理）；revision 门控（五个 bump 点逐一、gate 命中跳过、物化失败不置 gate、syncBack 后刷新防乒乓）；快照差分（未知文件全读=现行为、size 变更必读、删除只出快照不删 KV、三栅栏原样、`SANDBOX_SYNC_SNAPSHOT_DIFF=false` 回退）。
- **既有回归**：`OpenSandboxTest`（含 X18 stage 晚于 start 场景）、`WorkspaceSyncServiceTest`、e2e X 组 / E 组（E-SKILL-4、E8/E9）零改动全绿。
- **新增 E2E 断言**（落 `e2e-ci-plan.md` X 组扩展）：同一会话连续两轮 `[E2E:plain]`，第二轮日志断言 `workspace projection refreshed` / `materialized` / `skill file ... synced` 计数为 0（稳态零传输）；两轮之间 `PUT /skills/users/:uid/:name` 后第三轮断言恰好一次 materialize。
- **开关矩阵**：三开关各自关闭跑 X 组，确认逐项回到现行为。

## 9. 实施顺序

1. **④ 协调器**（纯重构打底，先合，零语义变化）；
2. **① 指纹缓存**（独立、收益最大、风险最低）；
3. **③ revision 门控**（含 `/.revision` 键与 gate 表）；
4. **②′ 快照差分**（依赖 ③ 的 manifest，含开关与 E2E 断言）。

建议两个 PR：④+①、③+②′；每步合并后用附录 A 基准复核收益与 §2 表对齐。

## 附录 A：基准复现方法

```bash
cd agent-framework && mvn -q -o dependency:build-classpath -Dmdep.outputFile=/tmp/sandbox-bench/cp.txt
# SandboxBench.java 见本设计编制时点的工作目录 /tmp/sandbox-bench/（未入库；实施 PR 附回归脚本时以 CI 环境为准）
cd /tmp/sandbox-bench && CP="out:$(pwd)/../../agent-manager/agent-framework/target/classes:$(cat cp.txt)"
javac -proc:none -cp "$CP" -d out SandboxBench.java
java -cp "$CP" SandboxBench b1   # 投影指纹
java -cp "$CP" SandboxBench b2   # 容器侧（真实 OpenSandbox，自动建/销毁沙箱）
java -cp "$CP" SandboxBench b3   # KV 侧（真实 MySQL，bench-agent 命名空间，自动清理）
```

环境依赖：OpenSandbox server（`127.0.0.1:8090`，api_key 见容器 `/etc/opensandbox/config.toml`）、`code-interpreter:v1.1.0` 镜像、MySQL 3307/`agent_manager_test`（凭据见本机冒烟环境记录）。b3 仅写 `agents/bench-agent/**` 命名空间并自清理，不触碰业务数据。


## 附录 B：实施记录（2026-10-15）

| 项 | 落地 | 偏差说明 |
|----|------|---------|
| ① 指纹缓存 | `ProjectionFingerprintCache` + `OpenSandbox.refreshProjectionIfStale` 短路；开关 `SANDBOX_PROJECTION_FINGERPRINT_CACHE`（默认 true） | `record()` 内部自算 stat 指纹（慢路径多付 ~3-5ms walk，换无二次传参的正确性）；「hash 相同未 hydrate」路径同样回填缓存 |
| ②′ 快照差分 | `WorkspaceSyncService.collectSkillFiles` manifest 差分；开关 `SANDBOX_SYNC_SNAPSHOT_DIFF` | 与设计稿的差异（评审中发现并修复的两处一致性）：a) **只读到内容才记 manifest**（读失败回落目录递归的条目不记，否则下轮被当"未变"漏镜像）；b) **SKILL.md 回写失败中止时清掉本技能本轮 manifest 条目**（KV 未落库不得留在快照里）；差分模式下「files 缺 SKILL.md」的跳过判定改为看快照是否含 SKILL.md |
| ③ revision 门控 | `/.revision` 键 + `SkillSyncGate`（挂在 `OpenSandboxFilesystemSpec`，键 userKey|sandboxId）+ `OpenSandbox.materializeUserSkills` 门控；开关 `SANDBOX_SKILL_REVISION_GATE` | bump 收敛于 `writeKvFile`（仅 skillScoped 分支——MEMORY.md 等工作区写入不 bump，防记忆回写误触发物化）与 `deleteUserSkillFile`；值用 nanoTime（同毫秒连续写入也要变化）+ 先删后写（write 对已存在 key 失败） |
| ④ 协调器 | `OpenSandbox.hydrateForCall(ctx)`：middleware 主路径 + doExec 兜底共用 | userKey 解析保持旧行为（ctx 非空即重新解析），不采用设计稿的"实例优先"——测试暴露同实例先后不同 ctx 必须能更新绑定（Channel 降级链路依赖），且原本就是纯重构无收益 |
| 行为变化 | L4 命名空间新增元数据行 `/.revision`（每次 L4 实际写/删更新）；`agent_fs` 行计数 +1/用户 | 对管理面可见性无影响（listUserSkills 的 isMetadataSegment 过滤）；`WorkspaceSyncServiceTest` 行数断言同步更新 |

## 附录 C：验证证据（2026-10-15）

- **单测**：`mvn test` 1503 用例 0 失败 4 跳过（新增 ProjectionFingerprintCacheTest 7 例、OpenSandboxTest 门控 5 例、WorkspaceSyncServiceTest 快照差分 5 例）。
- **e2e**：`E2E_GROUP=sandbox` 全绿 13 passed 1 skipped（X18 投影新鲜度 / X10 管理面 PUT 物化 / X11 tombstone 防复活均零回归）。
- **真实 OpenSandbox 冒烟**（127.0.0.1 容器 IP + mock LLM + MySQL 3307 真库）：
  - 管理面 PUT 后 KV 出现 `/.revision`（bump 生效）；
  - 同会话连续两轮 turn：`materialize_user_skills` / `workspace projection refreshed` / `skill file ... synced` 计数**全部不变**（稳态零传输、revision 门控命中）；
  - 管理面 PUT 后新会话首轮恰好物化一次（revision 变化触发）；
  - `admin-override` 栅栏在差分模式下依旧生效（容器内副本不回写覆盖管理面内容）。
