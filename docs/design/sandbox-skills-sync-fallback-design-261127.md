# 沙箱技能同步无镜像依赖降级设计（Push-to-Store + 软链农场复用）- 261127

> 状态：**已实施并完成真实集群验收（2026-11-27，分支 `feat/sandbox-skills-sync-fallback`；单测 163/163 通过，真实 Kind OpenSandbox E2E-1~E2E-7 全项 PASS，验收证据见 §10）**
> 前置文档：[sandbox-skills-syncd-design-261112.md](sandbox-skills-syncd-design-261112.md)（PR-4b 已退役包技能 scoped tar 投影）、[sdk-stager-shadow-design-261114.md](sdk-stager-shadow-design-261114.md)（宿主 `.skills-cache` 零写盘 + 端点 manifest 制）
> SDK 实证基线：`com.alibaba.opensandbox:sandbox:1.0.18`（本地仓库 `javap -c` 核实，见 §2）

## 1. 背景与问题机制

### 1.1 现状强耦合

PR-4b（PR #339）退役包技能旧版 scoped tar 投放路径、且 PR #340 引入 `MarketplaceStager` 遮蔽类后，包技能（`oaf-package`）投放变为 **farm-only**：

1. **镜像强依赖**：沙箱镜像必须预装 `/usr/local/bin/skills-syncd` 与 `/usr/local/bin/skillsync-launcher.sh`（直建还需 `entrypoint-wrap.sh`）。
2. **基础设施强依赖**：池模板必须注入 `SKILLSYNC_BASE_URL` / `commandWrapper`，且 OpenSandbox egress 策略必须放行沙箱 Pod 访问 `oaf-{name}-svc:8100`。
3. **当前降级无可用性**：在以下场景中，`SkillsFarm.decideMode()` 判定失败后直接返回 `false`，**整会话零包技能可用**（仅 L4 用户技能可用）：
   * 使用上游标准镜像（如 `opensandbox/code-interpreter:v1.1.0`）或业务自定义/第三方沙箱镜像（`degraded_store_missing`）；
   * 沙箱严格断网 / egress 未放行 `agent-svc` / warm pod 首认领竞态（`degraded_endpoint_down`，但本端 `SkillBundleService` 健康）；
   * 滚动发布窗口或旧 warm pod 版本滞后（`degraded_version_mismatch`）。

### 1.2 现成可复用底座（无需重造轮子）

* **单一确定性 Bundle 缓存（D-3）**：`SkillBundleService.bundle(version)` 已按 version 产出并落盘缓存 `.tar.gz`（keep-2 LRU，落于 `{workspaceBase}/tmp/skills-bundle-cache`），天然剔除了服务级禁用技能，跨副本字节一致。
* **多模式稳态记账（D-10 / D-11）**：`OpenSandboxFilesystemSpec` 的 scoped 指纹表（Pod 级 + Redis 跨副本共享层）已采用 `mode|fingerprint` 结构，天然支持不同投放模式并存与模式互转时的自动重收敛。
* **控制面文件传输通道**：`OpenSandbox` 经 OpenSandbox Server 代理访问容器内 `execd`（`osbSandbox.files().write(...)` + `osbSandbox.commands().run(...)`），**不依赖沙箱主动出网（egress）**。

## 2. OpenSandbox SDK `WriteEntry` 二进制写入能力实证

对本地 Maven 仓库 `sandbox-1.0.18.jar` 执行 `javap -c -p` 反编译 `WriteEntry`、`FilesystemAdapter.write` 及匿名内部类 `FilesystemAdapter$write$1$fileBody$1`（`IsolatedFilesystemAdapter` 实现同构），核实结论如下：

| 检查项 | 实证结果（`sandbox-1.0.18.jar`） | 工程含义 |
| :--- | :--- | :--- |
| `WriteEntry.Builder.data(...)` 签名 | `data(java.lang.Object)` | 并非仅限 `String` |
| HTTP 传输协议 | `POST /files/upload`，`okhttp3.MultipartBody`（`multipart/form-data`），每条 entry 由 `metadata`（JSON）+ `file`（内容）两部分组成 | 原生支持二进制上传，无需 `base64` |
| `data instanceof byte[]` | `RequestBody.create(bytes, "application/octet-stream")` | 二进制直传，已知 `Content-Length`，但需整包驻留 JVM 堆 |
| `data instanceof InputStream` | 构造 `FilesystemAdapter$write$1$fileBody$1 extends RequestBody`：`contentType = application/octet-stream`，`contentLength = -1`，`writeTo(sink)` 通过 `Okio.source(in)` 调用 `sink.writeAll(source)` 并在 `finally` 中自动关闭流 | **支持真流式二进制上传，JVM 堆占用 O(1)**，可直接对接 `SkillBundleService.BundlePayload.stream()`（磁盘缓存命中时直读 `Files.newInputStream`） |
| `data instanceof String` | `RequestBody.create(str, "text/plain; charset=...")` | 仅用于文本；`doHydrateWorkspace` 历史代码用 `base64` 走了此分支，存在 33% 膨胀与堆峰值 |
| 其他类型 | 抛 `IllegalArgumentException("Unsupported file data type: ...")` | 不支持 `Path` / `File`，需传 `InputStream` 或 `byte[]` |

**实现注意点（由字节码推导）**：

1. **流关闭安全**：`fileBody$1.writeTo` 正常执行完毕后会关闭 `InputStream`，但若 `write()` 在构造请求或连接阶段提前抛异常，`writeTo` 未被调用，调用方仍须用 `try (var in = payload.stream().open())` 兜底关闭（`InputStream.close()` 幂等）。
2. **不可重放流边界**：`fileBody$1` 未覆写 `RequestBody.isOneShot()`（OkHttp 默认 `false`），`contentLength() == -1` 走 chunked transfer；若底层连接复用失败触发 OkHttp 自动重试，可能二次读取已关闭流。降级推送路径采用单次尝试 + 异常捕获 fail-open，不做底层重放依赖；若后续观测到特定代理不支持 chunked multipart（411 Length Required），可按小包阈值退化为 `byte[]`（有确切 `Content-Length`）。
3. **消除 `base64` 依赖**：二进制直传 `.tar.gz` 后，容器侧只需 `sh` + `tar`（`tar xzf`），对精简第三方镜像兼容性更强。

## 3. 目标与非目标

### 目标

1. **零镜像依赖兜底**：任意具备基础 `sh` 与 `tar` 的沙箱镜像（含上游标准镜像、第三方镜像），在不安装 `skills-syncd`、不配置池 `commandWrapper`、甚至沙箱断网（无 egress）的情况下，会话仍可正常使用包技能。
2. **零宿主乘法回归**：保持 `MarketplaceStager` 遮蔽类与宿主 `.skills-cache` 零写盘不变，直接复用 `SkillBundleService` 单份磁盘缓存。
3. **同沙箱跨会话共享**：同一沙箱内多个会话/用户顺序复用时，降级库只推送一次，后续会话命中同版本直接建软链（0 传输）。
4. **稳态零退化与无缝恢复**：同沙箱代同指纹稳态保持 0 exec / 0 传输（D-10）；当 `skills-syncd` 恢复或镜像升级后，新会话自动切回 `FARM` 模式并重收敛软链（D-11）。
5. **强护栏与可观测性**：具备包体上限、并发限流、fail-open 隔离与 D-5 指标/WARN 留痕，防止降级态演变为雪崩或长期掩盖配置缺陷。

### 非目标

* 不取代 `skills-syncd` 作为大规模池化生产环境的主路径（warm pod 预热期同步仍是主路径的核心优势）。
* 不改动 `skills-syncd`、`backend` 池模板逻辑（可选在 `platformconfig/template.go` 补充环境变量提示）、`MarketplaceStager` 遮蔽类或 L4 用户技能/回写链路。
* 不改造现有 `OpenSandbox.doHydrateWorkspace()` 的 base64 传输逻辑（保持变更面最小，另立项优化）。

## 4. 总体架构与方案对比

### 4.1 备选方案对比

| 方案 | 机制 | 镜像依赖 | Egress 依赖 | 宿主盘放大 | 同沙箱复用 | 结论 |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **A. Push-to-Store（本设计）** | 服务端将 `SkillBundleService` 缓存的 `.tar.gz` 流式推入 `/tmp/.skills-store`，原子切 `CURRENT`，复用 `SkillsFarm` 建软链 | 无（仅 `sh`/`tar`） | **无**（走控制面 `execd`） | 0 | **一次推送、跨会话共享** | **采用** |
| B. 恢复宿主 staging + tar | 撤销 `MarketplaceStager` 遮蔽，按用户写宿主 `.skills-cache` 再打包投放 | 无 | 无 | 66MB × 用户数 | 每用户重复推 | 否决 |
| C. 沙箱内 `curl` 自拉 | `commands().run("curl ... \| tar ...")` 拉取 `/internal/skills/bundle` | 需 `curl`/`wget` | **强依赖** | 0 | 共享 | 否决（无法兜底 egress 断网与直建场景） |

### 4.2 双路径收敛架构

```
┌─ agent-framework ──────────────────────────────────────────────────────────┐
│  SkillBundleService（单一 SSOT：version + skills 名单 + D-3 磁盘缓存 .tar.gz）│
│         │                                         │                        │
│         │ (主路径：守护轮询 HTTP GET)              │ (降级路径：流式读取)    │
│         │                                         ▼                        │
│         │                              SkillBundlePusher（限流 + 上限护栏） │
│         │                                         │ InputStream → execd    │
│         │                                         │ POST /files/upload     │
└─────────┼─────────────────────────────────────────┼────────────────────────┘
          ▼                                         ▼
┌─ 沙箱容器 ─────────────────────────────────────────────────────────────────┐
│  [主路径库] /skills-store/                [降级独立库] /tmp/.skills-store/  │
│    CURRENT → v-<ver> (skills-syncd 维护)    CURRENT → v-<ver> (服务端推)   │
│         │                                         │                        │
│         └──────────────────┬──────────────────────┘                        │
│                            ▼                                               │
│         SkillsFarm.hydrate(scope, activeStoreDir, hydrateMode)             │
│           /workspace/.skills-cache/<scope>/oaf-package/<skill>             │
│             → <activeStoreDir>/CURRENT/<skill>  (软链，L4 同名优先)         │
└────────────────────────────────────────────────────────────────────────────┘
```

### 4.3 独立降级库 `/tmp/.skills-store` 的四项不变量

1. **与 `skills-syncd` 零争抢**：即使容器内 `skills-syncd` 存活（如滚发版本漂移或 egress 闪断后恢复），守护只读写 `/skills-store`，服务端降级只读写 `/tmp/.skills-store`，永不互相覆写或误删 `CURRENT`。
2. **兼容非 root / OSEP-0018 降权镜像**：根目录 `/skills-store` 在非 root 镜像下可能无写权限（EACCES），而 `/tmp`（权限 `1777`）恒可写。
3. **不污染工作区持久化**：`OpenSandbox.doPersistWorkspace()` 仅打包 `/workspace`，`/tmp/.skills-store` 不会被打包回传。
4. **不触发回写吸回**：`WorkspaceSyncService` 仅遍历 `/workspace/skills` 且按 `type=symlink` 跳过软链，`/tmp/.skills-store` 与 `.skills-cache` 均在其视野之外。

## 5. 详细设计

### 5.1 配置与开关

在 `SandboxConfig`、`application.yml`（`agent.sandbox.*`）与 `OpenSandboxClientOptions` 新增以下配置项：

| 环境变量 | YAML 键（`agent.sandbox.*`） | 默认值 | 说明 |
| :--- | :--- | :--- | :--- |
| `SANDBOX_SKILLS_FALLBACK_MODE` | `skills-fallback-mode` | `push` | 降级模式：`push`（启用 Push-to-Store 降级）/ `off`（保持 PR-4b 现状：降级即无包技能，一键回滚开关） |
| `SANDBOX_SKILLS_FALLBACK_STORE_DIR` | `skills-fallback-store-dir` | `/tmp/.skills-store` | 沙箱内降级库根目录；必须通过 shell 安全路径校验（不含空白、引号或 `..`） |
| `SANDBOX_SKILLS_FALLBACK_MAX_BYTES` | `skills-fallback-max-bytes` | `67108864`（64 MiB） | 允许推送的压缩后 bundle 最大字节数；≤0 视为不限（不推荐生产配置） |
| `SANDBOX_SKILLS_FALLBACK_MAX_CONCURRENCY` | `skills-fallback-max-concurrency` | `4` | 单副本并发推送信号量上限（进程级共享 `Semaphore`）；防止全池冷降级压垮 OpenSandbox Server 或本地 I/O |
| `SANDBOX_SKILLS_FALLBACK_ACQUIRE_TIMEOUT_MS` | `skills-fallback-acquire-timeout-ms` | `5000` | 获取推送并发许可的等待超时（毫秒）；超时快速 fail-open，不阻塞对话主链路 |
| `SANDBOX_SKILLS_FALLBACK_ON_VERSION_MISMATCH` | `skills-fallback-on-version-mismatch` | `true` | `degraded_version_mismatch` 时是否允许走 `push` 降级（默认 `true`，吸收滚发窗口与旧池错版；若需严格阻断串池排障可设为 `false`） |

**开关优先级（铁律）**：`SANDBOX_SKILLS_SHARED_STORE=off`（或空）为包技能投放总闸——总闸关闭时 `SkillsFarm.enabled()` 返回 `false`，**绝不触发降级推送**。

### 5.2 会话级三态判定状态机

`SkillSyncGate.SkillFarmState` 扩展为三态模型（仍按 `userKey|sandboxId` 会话级记忆，降级会话不中途翻转，恢复以新会话/新沙箱代为粒度）：

```java
public enum FarmMode { FARM, PUSH, NONE }

public record SkillFarmState(FarmMode mode, String storeDir, Set<String> l4Names) {
    public boolean active() { return mode == FarmMode.FARM || mode == FarmMode.PUSH; }
}
```

完整判定矩阵（`SkillsFarm.decideMode(uid)`）：

| 条件 | 主路径探测结果 | `fallbackMode=off` 决策 | `fallbackMode=push` 决策 | `skills.farm.decision` result 标签 |
| :--- | :--- | :--- | :--- | :--- |
| `SANDBOX_SKILLS_SHARED_STORE` 为空或 `off` | 不探测 | `NONE` | `NONE` | 不打点（启动期 WARN） |
| 本端 `SkillBundleService` 不可用或返回 `ERR-*` | 不探测 | `NONE` | `NONE`（无源可推） | `degraded_endpoint_down` |
| `snapshot.skills().isEmpty()`（空技能包，#397） | 任一 | 沿用现逻辑 | 无需推送 bundle，直接以空候选收敛（0 传输） | `farm` / `fallback_push_hit` |
| `/skills-store/CURRENT == v-<ver>` | 命中 | `FARM`（`/skills-store`） | `FARM`（`/skills-store`） | `farm` |
| `/skills-store` 目录不存在（无守护镜像/未配池） | `store_missing` | `NONE` | 执行 `SkillBundlePusher.ensureSynced(...)` | 见 §5.5 |
| `/skills-store` 存在但无 `CURRENT`（egress 断网/首认领竞态） | `endpoint_down` | `NONE` | 执行 `SkillBundlePusher.ensureSynced(...)` | 见 §5.5 |
| `/skills-store/CURRENT != v-<ver>`（版本不一致） | `version_mismatch` | `NONE` | `fallbackOnVersionMismatch=true` 时执行推送；否则 `NONE` | 见 §5.5 / `degraded_version_mismatch` |

### 5.3 推送与原子落盘协议（`SkillBundlePusher`）

新增包级协作者 `SkillBundlePusher`（由 `AgentScopeConfig` 装配单一共享 `Semaphore`，避免每个 `OpenSandbox` 实例各自计数）：

1. **复用预检（0 传输快路径）**：
   * 执行 `probeStore(fallbackStoreDir)`（`readlink '<fallbackStoreDir>/CURRENT'`）。
   * 若返回值等于 `v-<endpointVersion>`：直接返回 `PushResult.HIT`（**0 上传、0 解压**，记录 `fallback_push_hit`），进入 `SkillsFarm.hydrate()`。
2. **并发许可与体积护栏**：
   * 先从 `SkillBundleService.bundle(endpointVersion)` 获取 `Optional<BundlePayload>`（命中 D-3 磁盘缓存，零压缩开销；若返回 empty 表示并发版本切换，返回 `degraded_fallback_failed` fail-open）。
   * 检查 `maxBytes > 0 && payload.size() > maxBytes`：超限立即拒绝，记录 `degraded_fallback_too_large` + WARN，fail-open 返回 `NONE`。
   * `semaphore.tryAcquire(acquireTimeoutMs, MILLISECONDS)`：未获许可记录 `degraded_fallback_throttled` + WARN，fail-open 返回 `NONE`。
3. **流式上传**：
   * 生成唯一临时路径 `tmpTar = "/tmp/.skills-bundle." + UUID.randomUUID() + ".tar.gz"`（与 `doHydrateWorkspace` 一致使用点号分隔，规避 e2e mock 的 `/workspace` 路径正则误匹配）。
   * `try (InputStream in = payload.stream().open())` 调用：
     ```java
     osbSandbox.files().write(List.of(
         WriteEntry.builder()
             .path(tmpTar)
             .data(in)
             .mode(644)
             .build()
     ));
     ```
4. **单条原子解包与切换命令**：
   * 在容器内执行单条 POSIX sh 命令（全路径单引号包裹，`$fbStore` 与 `$ver` 均已过白名单校验）：
     ```sh
     set -e
     fb='<fallbackStoreDir>'
     ver='v-<endpointVersion>'
     tmp='<tmpTar>'
     stg="$fb/.staging-<endpointVersion>.<uuid>"
     trap 'rm -rf "$stg" "$tmp"' EXIT
     mkdir -p "$fb"
     if [ ! -d "$fb/$ver" ]; then
       mkdir -p "$stg"
       tar xzf "$tmp" -C "$stg"
       chmod -R a+rX "$stg" 2>/dev/null || true
       mv "$stg" "$fb/$ver" 2>/dev/null || rm -rf "$stg"
     fi
     ln -sfn "$ver" "$fb/.CURRENT.<uuid>"
     mv -Tf "$fb/.CURRENT.<uuid>" "$fb/CURRENT"
     find "$fb" -mindepth 1 -maxdepth 1 -type d -name 'v-*' ! -name "$ver" -exec rm -rf {} + 2>/dev/null || true
     ```
   * **并发与幂等语义**：
     * `trap 'rm -rf "$stg" "$tmp"' EXIT` 保证无论成功、解压失败或磁盘满，临时 `.tar.gz` 与未完成的 `.staging-*` 都会被清理，避免 `/tmp` 泄漏。
     * 若同沙箱并发或重复推送同一版本，`[ ! -d "$fb/$ver" ]` + `mv ... || rm -rf "$stg"` 保证目录不损坏；`ln -sfn` + `mv -Tf` 原子替换 `CURRENT` 软链。
     * 清理旧版 `v-*` 紧跟 `CURRENT` 切换之后，遵循 `skills-syncd` §7.3 单版本不变量。
   * 在 `finally` 块中释放 `Semaphore`。若上传阶段成功但解压命令失败或抛异常，仍尽力清理 `tmpTar`（或由 `trap` 清理）并记 `degraded_fallback_failed` + WARN，fail-open 返回 `NONE`。

### 5.4 软链收敛与 D-10/D-11 稳态短路复用

* `OpenSandboxFilesystemSpec` 新增常量：`public static final String HYDRATE_MODE_PUSH = "push";`。
* `SkillsFarm.hydrate(String scope, SkillSyncGate gate, String uid, String storeDir, String hydrateMode)` 参数化：
  * 稳态比对使用 `filesystemSpec.scopedSkillCacheFingerprint(id, scope, hydrateMode)`；
  * 建链目标改为 `storeDir + "/CURRENT/" + OpenSandbox.leaf(rel)`；
  * 收敛成功后记账 `filesystemSpec.recordScopedSkillCacheFingerprint(id, scope, hydrateMode, fingerprint)`。
* **模式互转零残留**：
  * `PUSH` → `FARM`（例如同一沙箱复用时 `skills-syncd` 恢复就绪，或新会话判定为 `FARM`）：记账模式分量 `push` != `farm`，稳态短路自动失效，触发单次重收敛，将 `.skills-cache/<scope>/oaf-package/<skill>` 软链目标原子改指 `/skills-store/CURRENT/<skill>`。
  * `FARM` → `PUSH`（例如滚发窗口旧 warm pod 触发降级）：同理 `farm` != `push`，强制重收敛改指 `/tmp/.skills-store/CURRENT/<skill>`。
* **内容变更传播**：
  * 包技能发布新版或全局禁用变更 → `endpointVersion` 变化 → 指纹变化 → 打破稳态 → `hydrate()` 需确保降级库同步到新版本！
  * **关键细节（同会话内版本更新）**：在 `PUSH` 模式下，如果 `hydrate()` 发现指纹变化且 `storeDir == fallbackStoreDir`，须先调用 `SkillBundlePusher.ensureSynced(endpointVersion)` 确保 `/tmp/.skills-store/CURRENT` 已更新至 `v-<endpointVersion>`，再执行软链与孤儿清理（如果推送失败则上抛/不记账，保持旧版可用并在下轮重试）。

### 5.5 可观测性与防静默退化（D-5 扩展）

复用 Micrometer Counter `skills.farm.decision`，按 `result` 标签区分所有路径：

| `result` 标签 | 含义 | 日志级别 |
| :--- | :--- | :--- |
| `farm` | 主路径命中 `/skills-store/CURRENT` | DEBUG |
| `fallback_push_hit` | 降级路径复用沙箱已有 `/tmp/.skills-store/CURRENT`（0 传输） | INFO |
| `fallback_push_synced` | 降级路径本次完成 bundle 流式推送与落盘 | **WARN**（必含 `originReason`、`version`、`bytes`、`costMs`、`sandboxId`） |
| `degraded_store_missing` | `fallbackMode=off` 且无 `/skills-store` | WARN |
| `degraded_endpoint_down` | 本端端点异常，或 `fallbackMode=off` 且守护未拉通 | WARN |
| `degraded_version_mismatch` | `fallbackMode=off` 或 `fallbackOnVersionMismatch=false` 且版本不匹配 | ERROR + WARN |
| `degraded_fallback_too_large` | bundle 体积超过 `SANDBOX_SKILLS_FALLBACK_MAX_BYTES` | WARN |
| `degraded_fallback_throttled` | 推送并发信号量等待超时 | WARN |
| `degraded_fallback_failed` | 推送上传、解压或切换失败（fail-open） | WARN |

通过 `fallback_push_synced` 的 Counter 增长与 WARN 日志，运维可以立即区分「业务正常可用（降级兜住了）」与「基础设施有缺陷（镜像漏装 `skills-syncd` 或池漏配）」，避免静默退化长期不被察觉。

## 6. 风险、边界与兼容性清单

| 风险 / 边界 | 影响分析 | 缓解措施 |
| :--- | :--- | :--- |
| 精简镜像 `mv` 不支持 `-T`（如部分 BusyBox） | `mv -Tf` 在非 GNU coreutils 上可能报无效参数 | 兼容写法：先尝试 `mv -Tf "$fb/.CURRENT.<uuid>" "$fb/CURRENT" 2>/dev/null \|\| ln -sfn "$ver" "$fb/CURRENT"`，兼顾 GNU 原子性与 BusyBox 兼容 |
| 代理不支持 chunked multipart（`InputStream` 导致 `contentLength=-1`） | 极少数中间代理返回 411 | E2E 真实 OpenSandbox Server 实测验证；若遇 411 可按配置或小包回退 `byte[]` |
| 容器 `/tmp` 为小容量 `tmpfs`（如 64MB） | 解压瞬间需约 `tar.gz + 解压后体积` | `SANDBOX_SKILLS_FALLBACK_MAX_BYTES` 默认 64MiB；`trap` 失败必清残留；失败 fail-open 不影响沙箱主进程 |
| 同沙箱代内容更新（同会话长周期运行期间发新版） | `acquireMode` 已记忆 `PUSH`，后续 turn 仅进 `hydrate()` | §5.4 明确在 `PUSH` 模式非稳态重收敛前先调用 `ensureSynced(endpointVersion)` 刷新 `/tmp/.skills-store` |
| 容器中途重启（同 `sandboxId` resume） | `/tmp` 清空但 Pod 级指纹记账仍在 | 与 `FARM` 路径 §6.2-4 已接受盲点姿态一致；新会话或版本变更自愈 |
| 回滚 | 上线后如出现异常 | 设 `SANDBOX_SKILLS_FALLBACK_MODE=off` 即可瞬时回到 PR-4b 行为，无需回滚镜像或池模板 |

## 7. 单元测试矩阵

新增 `SkillBundlePusherTest` 并扩展 `OpenSandboxTest`、`OpenSandboxClientOptionsTest`、`OpenSandboxFilesystemSpecTest`：

| 用例 ID | 测试类 | 场景与断言 |
| :--- | :--- | :--- |
| **UT-1** | `OpenSandboxClientOptionsTest` | `SANDBOX_SKILLS_FALLBACK_MODE`（`push`/`off`/大小写/空白）、`MAX_BYTES`、`MAX_CONCURRENCY`、`STORE_DIR` 非法路径校验与默认值绑定 |
| **UT-2** | `SkillBundlePusherTest` | 复用快路径：`/tmp/.skills-store/CURRENT` 已指向 `v-<ver>` → 返回 `HIT`，**0 次 `files().write` 调用**，不消耗信号量 |
| **UT-3** | `SkillBundlePusherTest` | 首次推送成功：调用 `files().write` 且 `WriteEntry.getData()` 为 `InputStream`（验证二进制流式直传、非 base64 字符串）、mode=644；执行单条原子解包切换命令；验证 `trap` 清理与旧版 `v-*` 清理 |
| **UT-4** | `SkillBundlePusherTest` | 体积上限护栏：`payload.size() > maxBytes` → 拒绝上传、0 次 `files().write`、返回 `TOO_LARGE`、不占用信号量 |
| **UT-5** | `SkillBundlePusherTest` | 并发限流护栏：`Semaphore` 耗尽且等待超时 → 返回 `THROTTLED`、0 次 `files().write`、fail-open |
| **UT-6** | `SkillBundlePusherTest` | 上传/解压异常 fail-open：`files().write` 或 `commands().run` 抛异常/非零退出 → 返回 `FAILED`、必定释放 `Semaphore`、不污染 `SkillSyncGate` |
| **UT-7** | `OpenSandboxTest` | 总闸优先级：`SANDBOX_SKILLS_SHARED_STORE=off` + `FALLBACK_MODE=push` → `SkillsFarm` 不探测、不推送、不打点 |
| **UT-8** | `OpenSandboxTest` | 三类降级触发：`store_missing`、`endpoint_down`（本端健康但无 `CURRENT`）、`version_mismatch`（开关开/关）均按矩阵正确决策并记录对应 `skills.farm.decision` Counter |
| **UT-9** | `OpenSandboxTest` | 本端端点异常（`ERR-*`）：即便 `FALLBACK_MODE=push` 也不触发推送，记录 `degraded_endpoint_down` |
| **UT-10** | `OpenSandboxTest` | 空包优化（`snapshot.skills().isEmpty()`）：不调用 `SkillBundlePusher` 上传空 tar，正常收敛并清理孤儿链 |
| **UT-11** | `OpenSandboxTest` | `PUSH` 模式下的软链收敛：软链目标指向 `/tmp/.skills-store/CURRENT/<skill>`，L4 同名真实文件优先不被覆盖，用户级禁用技能不建链且旧链被清理，孤儿链被清理 |
| **UT-12** | `OpenSandboxTest` / `OpenSandboxFilesystemSpecTest` | D-10/D-11 稳态与模式互转：① `PUSH` 稳态第二轮 0 exec / 0 推送；② `PUSH` 模式同沙箱代包版本更新 → 自动触发 `ensureSynced` 推新版 + 重收敛；③ 同沙箱代 `PUSH` ↔ `FARM` 互转 → 记账模式分量不匹配，强制重收敛切换软链目标 |

## 8. E2E 验收用例矩阵（真实 OpenSandbox + Kind 集群）

在真实 OpenSandbox 环境（原生上游镜像 `opensandbox/code-interpreter:v1.1.0`，**不含 `skills-syncd` 与 `skillsync-launcher.sh`**）执行以下验收用例：

| 用例 ID | 场景 | 操作步骤 | 预期结果 |
| :--- | :--- | :--- | :--- |
| **E2E-1** | 无 `skills-syncd` 标准镜像直建可用 | 发布含 2+ 个包技能（含可执行脚本）的服务，配置标准镜像 `opensandbox/code-interpreter:v1.1.0`（无 pool / 无 syncd），发起对话触发技能脚本执行 | 首轮日志出现 `fallback_push_synced` WARN；容器内 `/tmp/.skills-store/CURRENT` 指向 `v-<ver>`；`/workspace/.skills-cache/<scope>/oaf-package/<skill>` 软链生效且脚本执行成功；`/tmp/.skills-bundle.*.tar.gz` 无残留 |
| **E2E-2** | 稳态零传输与同沙箱跨会话复用 | ① 同会话发起第 2 轮对话；② 复用同一沙箱发起新会话（或切换 scope） | ① 第 2 轮稳态 0 exec、0 上传；② 新会话命中 `fallback_push_hit`（0 上传，仅 1 次建链 exec），且旧 scope 目录被 prune |
| **E2E-3** | L4 同名优先与两级禁用语义 | ① 用户上传同名 L4 技能覆盖包技能；② 管理面全局禁用某包技能；③ 用户级禁用某包技能 | ① L4 真实文件优先不被软链覆盖；② 全局禁用后端点版本变更，降级库推送新版且被禁用技能从 `/tmp/.skills-store` 移除、孤儿软链清理；③ 用户级禁用仅删该用户软链 |
| **E2E-4** | 沙箱断网（egress 阻断）兜底 | 使用带 `skills-syncd` 镜像但注入不可达 `SKILLSYNC_BASE_URL`（模拟 egress 断网，`/skills-store` 存在但无 `CURRENT`） | 自动降级走 `PUSH`，`/tmp/.skills-store/CURRENT` 就绪、包技能可执行，且 `/skills-store` 目录未被服务端修改 |
| **E2E-5** | 护栏与开关回退 | ① 设 `SANDBOX_SKILLS_FALLBACK_MAX_BYTES=1024`；② 设 `SANDBOX_SKILLS_FALLBACK_MODE=off`；③ 设 `SANDBOX_SKILLS_SHARED_STORE=off` | ① 触发 `degraded_fallback_too_large` 且对话正常 fail-open；② 回到 PR-4b 行为（`degraded_store_missing`）；③ 完全关闭包技能投放且不触发推送 |
| **E2E-6** | `PUSH` → `FARM` 平滑回切 | 先在降级态运行，再切换至带 `skills-syncd` 且就绪的镜像/池，发起新会话 | `/actuator/metrics/skills.farm.decision` 出现 `farm` 计数增长；软链目标自动收敛回 `/skills-store/CURRENT/<skill>` |
| **E2E-7** | 宿主零写盘不变量 | 全程检查 `agent-framework` 宿主工作区 `.skills-cache/` 目录 | 宿主 `.skills-cache/` 无任何包技能文件写入（`MarketplaceStager` 遮蔽不受影响） |

## 9. 实施切分与回滚口径

1. **单 PR 落地（独立 `/tmp/wt-skills-sync-fallback` worktree）**：
   * 仅涉及 `agent-framework`（`SandboxConfig`、`application.yml`、`OpenSandboxClientOptions`、`AgentScopeConfig`、`SkillSyncGate`、`OpenSandboxFilesystemSpec`、`SkillBundlePusher`、`SkillsFarm` 及对应单测）+ 本文档 + `docs/design/README.md` 索引更新。
   * 可选附带 `backend/internal/service/platformconfig/template.go` 中 `SANDBOX_SKILLS_FALLBACK_MODE` 表单提示（若拆 PR 可独立合入）。
2. **回滚口径**：
   * 运行期一键降级回原行为：`SANDBOX_SKILLS_FALLBACK_MODE=off`（无需重启沙箱或改池模板）。
   * 代码回滚：单 PR `git revert` 干净可逆，零数据库变更、零镜像与 CRD 变更。

## 10. 验收与实施证据（2026-11-27）

* **单元测试（单 JVM `MAVEN_OPTS="-Xmx512m"`）**：163 / 163 全绿（0 失败 0 错误 0 跳过），覆盖 `SkillBundlePusherTest`（6 例）、`SkillsFarmFallbackTest`（7 例）、`OpenSandboxClientOptionsTest`（5 例）、`SandboxConfigTest`（6 例）、`OpenSandboxFilesystemSpecTest`（20 例）与 `OpenSandboxTest`（119 例）。
* **Kind 集群部署**：`172.20.0.1:5001/agent-framework:latest` 推送并经 Kind 节点 `crictl pull` 滚动重启 `oaf-release-agent`、`oaf-browser-takeover` 全部 `1/1 Running`，`/health` 与 `/internal/skills/version` 均返回 `200`。
* **真实 OpenSandbox 全生命周期文件同步验收**（`opensandbox-server` `172.20.0.2:30090`，上游标准无 `skills-syncd` 镜像 `opensandbox/code-interpreter:v1.1.0`，沙箱 `7e5f51c0-0b07-43b1-af8f-c8f147ae27e3`，验证完毕已销毁）：
  * **E2E-1**：`WriteEntry.data(InputStream)` 流式二进制 `.tar.gz` 上传成功（`4893 bytes / 639ms`，无 chunked/411 异常），`/tmp/.skills-store/CURRENT -> v-895036bcf34d73ea`，可执行脚本 `alpha/run.sh` 保留 `0755` 权限并输出 `ALPHA_OK_V1`，`/tmp/.skills-bundle.*.tar.gz` 零残留，信号量全额归还。
  * **E2E-2**：同会话第 2 轮稳态短路耗时 `5ms`（0 exec / 0 上传）；跨会话 `sess-B` 复用同一沙箱命中 `fallback_push_hit`（0 上传），旧 scope `sess-A` 已被 prune。
  * **E2E-3**：更新 `alpha/run.sh`（`ALPHA_OK_V2`）并全局禁用 `beta` 后，版本指纹变更为 `09059cd4323fd367`，同会话自动重推并切换 `CURRENT`，`beta` 孤儿软链被清理，旧 `v-895036bcf34d73ea` 目录被 GC。
  * **E2E-4**：容器内存在空 `/skills-store`（模拟 `skills-syncd` 存活但 egress 断网）时自动降级走 `PUSH`，`/skills-store` 保持零修改。
  * **E2E-5**：`maxBytes=10` 触发 `degraded_fallback_too_large` fail-open；`fallback=off` 退回 PR-4b 降级；`SANDBOX_SKILLS_SHARED_STORE=off` 总闸关闭时不探测不推送。
  * **E2E-6**：就绪 `/skills-store/CURRENT` 后新判定平滑切回 `FARM`，D-11 模式分量驱动软链目标由 `/tmp/.skills-store/CURRENT/alpha` 自动重收敛至 `/skills-store/CURRENT/alpha`，`skills.farm.decision{result=farm}` 计数递增。
  * **E2E-7**：全程宿主 `.skills-cache` 零包技能写盘。
