# 沙箱技能同步守护设计（skills-syncd + 镜像 entrypoint）- 261112

> 状态：**设计定稿待评审**
> 前置调研：本设计经与维护者多轮收敛（备选方案对比：对象存储挂载 / CSI / ZeroFS-NFS / execd 文件 API 推送 / 守护自拉），最终选定「沙箱内定时同步守护 + 镜像 entrypoint」形态。备选方案淘汰理由见 §12。

## 1. 背景与问题机制

### 1.1 现状链路

沙箱技能分发当前为**推送式投影**：宿主工作区 `.skills-cache/<scopeSegment>/`（scope = userId）经
`OpenSandbox` 的 scoped 投放链路（stat 指纹缓存 P0-1 + 载荷缓存 P1-3 + prune，见
[sandbox-skill-sync-optimization-design.md](sandbox-skill-sync-optimization-design.md)）以 tar.gz 按需物化进沙箱。

### 1.2 规模劣化机制（两个乘法）

生产环境大规模用户下，性能劣化根源不是投影机制本身，而是两个乘法项：

1. **每用户复制**：scope = userId，同一份包技能被 staging 进每个用户的 scope 目录再逐用户投影。
   文件总量 = 包体积 × 用户数（application.yml:109 注释已佐证「防 agent_fs 按用户复制整份包内技能（66MB/用户）」）。
2. **冷路径 O(scope)**：换沙箱代时全量 tar（实测 66MB/2000f ≈ 4.9s；稳态短路 ≈35ms），随文件数线性增长。

### 1.3 关键事实（已核实）

- **沙箱全链路以 root 运行**：`opensandbox/code-interpreter:v1.1.0` 与自建 `gaoyue1989/code-interpreter-oa`
  均无 `USER` 指令、无 OSEP-0018 加固组件（bwrap/launcher/session-gate）。LLM 生成代码当前以 root 执行。
- **池化 warm pod 在被 `create + extensions.poolRef` 认领前没有 sandbox 身份**：execd 文件 API 按 sandbox ID
  经 server 代理寻址（OSEP-0007），**推送式方案无法预热未使用的 warm pod**——这是选定守护自拉形态的决定性理由。
- 守护住在 Pod 层（entrypoint 拉起），**Pod 创建即开始同步**，把 warm pod 等待认领的空闲期变成同步窗口。
- 守护方案对沙箱生命周期**无不利影响**：TTL 续期仍是框架 T2/T3 显式语义（OSEP-0009 自动续期三层 opt-in 默认关，
  不受影响）；容器重启后 entrypoint 重新拉起守护自愈；paused pod 守护一并暂停、唤醒后追版本。

## 2. 目标与非目标

### 目标

1. 会话开始的技能投影从「O(包技能全量) tar 物化」降为「O(启用技能数) 符号链接创建」（毫秒级）
2. 消除包技能的每用户物理复制：同一 warm pod 内全服务共享一份（per-pod 一份，非 per-user）
3. 版本新鲜度分钟级（守护轮询周期可配），版本切换对运行中会话零扰动
4. 沙箱内新增常驻进程资源占用可忽略（单二进制、轮询间歇休眠）
5. 同步源地址全部经环境变量配置，单集群零新增部署组件

### 非目标

- 不做多节点共享存储（PVC/NAS/对象存储挂载）——刻意接受 per-pod 一份复制换取部署简单，多节点场景升级路径见 §12
- 不改动用户技能（L4）与记忆（MEMORY.md/memory/）的分发——继续走现有小 scope hydrate/syncBack
- 不在本次启用 OSEP-0018 加固（独立事项，见 §9.3）
- 不做跨集群交付（oaf-agent charts 场景）——拉取源抽象预留，实施另立项

## 3. 总体架构

```
┌─ agent-framework（业务面，多副本）───────────────────────────┐
│  SkillBundleController（新增，内部端点，token 鉴权）           │
│    GET /internal/skills/version  → 版本指纹（轻）              │
│    GET /internal/skills/bundle   → tar.gz（重，仅版本变更时）   │
│  数据源：/config 包技能（PVC 只读 subPath，跨副本一致）         │
└──────────────┬──────────────────────────────────────────────┘
               │ Pod 内网络（沙箱 → agent-svc，egress 需放行）
┌──────────────▼──────────────────────────────────────────────┐
│ 沙箱 Pod（池化 warm pod，镜像 = code-interpreter-oa + 守护）    │
│                                                              │
│  entrypoint 包装脚本（镜像内）                                  │
│    setsid /usr/local/bin/skills-syncd &   ← Go 守护，root      │
│    exec /opt/code-interpreter/code-interpreter.sh "$@"        │
│                                                              │
│  /skills-store/                ← 守护维护，root:root 0755      │
│    CURRENT            (symlink → v-<hash>)                    │
│    v-<hash>/<skillName>/...      包技能，仅当前版本，旧版切换后即删│
│    .staging-<hash>/              下载中，原子 rename 前不可见    │
│                                                              │
│  会话开始（agent-framework acquire 链路）：                      │
│    manifest(userId) → /workspace/.skills-cache/<scope>/…/      │
│       <skillName> → /skills-store/v-<hash>/<skillName> (软链)   │
│    用户技能（L4）→ 原有小 scope hydrate（真实文件）              │
└──────────────────────────────────────────────────────────────┘
```

三组件职责：

| 组件 | 位置 | 职责 |
|---|---|---|
| `skills-syncd` | 仓库新顶层目录 `skills-syncd/`（Go） | 轮询版本、按需拉取、原子版本切换、GC；失败 fail-open 不影响沙箱 |
| entrypoint 包装 | 自建镜像 `code-interpreter-oa` | 后台拉起守护后 exec 原入口，execd 仍为 PID 1 语义不变 |
| agent-framework | `SkillBundleController` + acquire 链路改造 | 版本/打包端点；会话开始符号链接农场；syncBack 跳过符号链接 |

**内容分类边界（安全前提）**：`/skills-store` 只放**服务级共享的包技能**（发布内容，服务内用户可见无害）；
用户私有技能（L4）与记忆**绝不进库**——沙箱内进程可绕过符号链接遍历挂载点，链接不是隔离边界。

## 4. skills-syncd 详细设计（Go）

### 4.1 目录与构建

```
skills-syncd/
├── go.mod            # 独立 module（go 1.26，对齐 backend），零第三方依赖（纯标准库）
├── main.go           # env 解析 + 主循环
├── sync.go           # 版本协商 / 下载 / 校验 / 原子切换 / GC
├── sync_test.go      # 单测（t.TempDir 模拟 store，httptest 模拟端点）
└── Dockerfile.build  # CGO_ENABLED=0 静态编译 → 产物拷入沙箱镜像构建
```

静态单二进制（`CGO_ENABLED=0 go build`），体积 ~2MB，无运行时依赖。

### 4.2 环境变量（地址全部可配）

| 变量 | 默认 | 说明 |
|---|---|---|
| `SKILLSYNC_BASE_URL` | （必填，无默认即禁用） | 同步源基址，如 `http://oaf-{name}-svc.agent-platform.svc:8100`；一服务一包，基址本身即标识包 |
| `SKILLSYNC_TOKEN` | 空 | 端点鉴权 token（只读 scope，`Authorization: Bearer`）；为空则请求不带鉴权（源端点可配置为集群内免鉴权） |
| `SKILLSYNC_STORE_DIR` | `/skills-store` | 技能库根目录 |
| `SKILLSYNC_INTERVAL` | `5m` | 轮询周期（Go duration 格式，如 `30s`/`5m`） |
| `SKILLSYNC_TIMEOUT` | `120s` | 单次 HTTP 请求超时（bundle 可能数十 MB） |
| `SKILLSYNC_LOG` | `stderr` | 日志输出（stderr 供 execd 采集；可选文件路径） |

`SKILLSYNC_BASE_URL` 缺失 → 守护启动即退出（exit 0，日志说明），
沙箱按无同步库运行（acquire 链路自动回退现行为，见 §6.4）。

### 4.3 版本协商协议

```
GET {BASE_URL}/internal/skills/version
  → 200 {"version": "<sha256[:16]>", "skillCount": 2000, "sizeBytes": 66000000}
  ← 401 token 无效

GET {BASE_URL}/internal/skills/bundle?version=<v>
  → 200 application/gzip（tar.gz：顶层为各技能目录 <skillName>/...）
  响应头 X-Bundle-Sha256：<完整哈希>（守护校验后才落盘）
```

一服务一包：每个 agent-framework 服务实例只承载一个 OAF 包（服务即包身份的单一真相源），
端点无需 package 参数——`BASE_URL` 已标识请求的是哪个服务/包。

版本指纹由 agent-framework 侧计算：对包技能目录做**内容哈希**（相对路径 + 文件内容，排序遍历），
非包版本号——防「版本号没变、内容变了」。守护每次轮询只调 version 端点（响应 <200B），
版本一致零流量；变更才拉 bundle。

### 4.4 主循环与原子切换

```go
for {
    ver, err := fetchVersion()          // 失败 → 退避重试（1m 起指数，上限 = INTERVAL），fail-open
    if err == nil && ver != currentVersion(storeDir) {
        pullBundle(ver)                  // → $STORE/.staging-<ver>.tar.gz
        verifySha256()
        extract → $STORE/.staging-<ver>/  // 独立目录，读方不可见
        chmod -R a+rX, chown root:root
        rename → $STORE/v-<ver>/          // 原子（同目录 rename(2)）
        symlinkSwap($STORE/CURRENT → v-<ver>)  // ln -sfn + rename(TMP symlink)
        deleteSuperseded()                // 删除旧 v-* 目录（CURRENT 指向者除外）
    }
    sleep(INTERVAL)
}
// 启动时清扫：删除所有非 CURRENT 指向的 v-* 与 .staging-* 残留（上次崩溃幂等自愈）
```

一致性不变量：

- **单版本语义**：store 任一时刻只有一个有效版本（`CURRENT` 指向者），切换后旧版即删
- **读方一律经 `CURRENT` 间接寻址**（`/skills-store/CURRENT/<skillName>`），不钉 `v-<hash>` 绝对路径——
  版本切换对读方是原子指针跳变，旧版删除不破坏任何在用链接
- `.staging-*` 任何时刻崩溃均不影响可用版本；下次轮询重下
- 切换瞬间的解析竞态（读方刚解析完旧路径、旧目录即将删除）：已打开文件凭 inode 继续可读（Unix 语义）；
  尚未 open 的解析撞上删除 → §6.4 逐技能降级兜底（窗口为毫秒级）

### 4.5 失败语义（fail-open）

守护任何失败（源不可达、校验失败、磁盘满）**只影响新鲜度，不影响沙箱可用性**：
execd/命令执行与守护完全解耦（`setsid` 脱离会话、独立进程组）。守护无健康探针——
它的「不健康」表现为版本陈旧，由日志与 agent-framework 侧版本对账（§7.2）观测，不构成告警风暴。

## 5. 镜像与 entrypoint 集成

### 5.1 自建镜像变更（复用 code-interpreter-oa 构建链路）

```dockerfile
# code-interpreter-oa 构建末段追加
COPY --from=skills-syncd-build /out/skills-syncd /usr/local/bin/skills-syncd
COPY entrypoint-wrap.sh /usr/local/bin/entrypoint-wrap.sh
ENTRYPOINT ["/usr/local/bin/entrypoint-wrap.sh"]
```

```sh
#!/bin/sh
# entrypoint-wrap.sh —— 两条铁律：
# 1) 守护 setsid 后台化，绝不阻塞 execd 启动；守护退出不连带沙箱
# 2) 最后 exec 原入口，execd 保持原进程语义（健康检查/探针契约不变）
if [ -n "$SKILLSYNC_BASE_URL" ]; then
    setsid /usr/local/bin/skills-syncd 2>>/var/log/skills-syncd.log &
fi
exec /opt/code-interpreter/code-interpreter.sh "$@"
```

env 未注入时零行为变化（非技能同步场景的沙箱镜像可直接复用）。

### 5.2 池模板注入

OpenSandbox Pool `spec.template.spec` 追加 env（我们自己的清单，非 kubectl）：

```yaml
env:
  - { name: SKILLSYNC_BASE_URL,   value: "http://oaf-{name}-svc.agent-platform.svc:8100" }
  - { name: SKILLSYNC_TOKEN,      valueFrom: { secretKeyRef: { name: skillsync-token, key: token } } }
  - { name: SKILLSYNC_INTERVAL,   value: "5m" }
```

token 为平台与 agent-framework 共享的只读凭据（Secret 分发；agent-framework 侧校验）。

### 5.3 网络前提

沙箱 Pod → agent-svc 出网需在 OpenSandbox egress 策略放行（FQDN `oaf-{name}-svc.agent-platform.svc`
或集群内网段）。部署清单与运维备忘同步补一行（docs/deployment.md）。

## 6. agent-framework 侧变更

### 6.1 内部端点（新增 `SkillBundleController`）

- 路径 `/internal/skills/version`、`/internal/skills/bundle`；token 经 env `SKILLSYNC_TOKEN` 配置
  （空 = 端点关闭，404）
- 数据源 `/config`（PVC 只读 subPath，多副本天然一致）；版本指纹计算复用现有 stat 指纹思路升级为内容哈希，
  进程内缓存（源只读，指纹稳定）
- bundle 打包即时 tar.gz（源只读可缓存产物，LRU 1 份）

### 6.2 acquire 链路：符号链接农场

`OpenSandbox` scoped 投放路径改造为**包技能 → 软链，用户技能 → 真实文件**：

1. 解析 `CURRENT` 得 `v-<hash>`（一次 readlink；不存在 → 回退 §6.4）
2. 按 manifest（现有禁用判定链 `SkillDisabledChecker`）为每个启用的包技能创建
   `.skills-cache/<scope>/oaf-package/<skillName>` → `/skills-store/CURRENT/<skillName>` 符号链接
   （经 `CURRENT` 间接寻址，不钉具体版本目录——切换/删除旧版不破坏在用链接）
3. 用户技能（L4）走原 hydrate 路径物化真实文件（量级几十文件，现有 35ms 稳态机制接管）
4. 判定与现有「命令触发延迟投放」合并：首个触及 `.skills-cache` 的命令触发一次（CAS 每 call），语义不变

### 6.3 syncBack 符号链接跳过

`WorkspaceSyncService.syncUserSkills` 遍历时跳过 `Files.isSymbolicLink` 条目——
否则链接指向的库内容被整份吸回用户 L4 存储（`skipPackageBaseline` 所治问题的翻版）。
同步在 tar 投影（若仍保留的路径）与 `WorkspaceReader` 列举两侧一致处理。

### 6.4 回退与开关

| 开关 | 默认 | 语义 |
|---|---|---|
| `SANDBOX_SKILLS_SHARED_STORE` | 空 | 沙箱内共享库路径（`/skills-store`）；空 = 关闭软链农场，走现行为 |
| `SKILLSYNC_TOKEN` | 空 | agent-framework 端点鉴权；空 = 端点关闭 |

回退触发条件（任一）：store 目录缺失 / `CURRENT` 解析失败 / 目标技能目录不存在 →
该技能降级走原 tar 物化路径。**降级是逐技能粒度**，不整场失败。

## 7. 版本与一致性语义

### 7.1 时序保证

- 发布新版 → agent-framework 端点版本指纹变更（即时，随发布事件失效进程内缓存）
- 沙箱侧最长 `INTERVAL` 后拉到 → `CURRENT` 原子切换，**下一次路径解析即见新版**（含运行中会话）；
  已打开的技能脚本/文件凭 inode 继续按旧内容执行完毕（Unix 语义），无中途截断
- 与现行为对比：现 per-call 指纹在下一 call 换新版——本设计同为「切换即生效」语义，
  且单文件粒度更平滑（同会话内未重开的文件不受扰动）

### 7.2 对账观测

agent-framework 在 acquire 创建软链时记录「store 版本 vs 端点版本」；偏差持续超 3×INTERVAL 打 WARN 日志
（不告警、不阻断）——这是守护失联的唯一可观测面。

### 7.3 版本切换与删除次序（单版本语义）

固定次序：**先切 `CURRENT`，后删旧版**。读方全部经 `CURRENT` 间接寻址（§6.2），指针跳变原子、
旧版删除不破坏任何在用链接；已打开文件凭 inode 存活。守护启动时清扫非当前 `v-*` 与 `.staging-*`
残留（上次崩溃在「已切换未删除」或「下载中」的幂等自愈）。毫秒级解析竞态窗口由 §6.4 逐技能降级兜底。

## 8. 容量与性能预估

| 项 | 现状 | 本设计 |
|---|---|---|
| 会话开始投影 | O(包全量) tar：冷 4.9s / 稳态 35ms（P0-1 优化后） | O(启用技能数) symlink：~10ms 级 |
| 沙箱内磁盘 | 按需物化 ~66MB | 稳态 1 版 ≈ 66MB；切换瞬时峰值 ≈ 2 版 + tar.gz（~200MB），完成后回落 |
| 网络 | 每次 hydrate 全量 tar（跨 exec 通道） | 每 pod 每版本一次 HTTP 拉取（version 轮询 <200B/5m） |
| agent-framework | 每次 hydrate CPU（tar+gzip+指纹） | 版本指纹缓存 O(1)；bundle 打包每版本一次 |
| 每用户复制 | scope staging N 份 | 0（store 服务内共享 per-pod） |

## 9. 安全设计

### 9.1 凭据

- token 只授两个 GET 端点、只读、仅本服务包内容；经 K8s Secret 注入
- 暴露面评估：token 进入沙箱（不可信代码可读 env）。缓解：scope 收窄到「读本服务已发布技能」，
  泄露代价 = 技能内容本身（本就是发布内容）。**不接受**该 token 具有任何写能力或跨服务枚举能力

### 9.2 UID 分离（现状与过渡）

- 当前镜像全 root：守护与用户命令同 root——守护可被 `pkill`、store 可被篡改（跨会话投毒面：
  会话 A 污染 warm pod 的 store → 同池后续会话执行被污染技能）。**过渡期缓解**：
  entrypoint 以 root 启动守护后，守护自身立即 `setuid` 到独立 UID（如 65534/nobody）运行？
  ——不可行：降权后写不了 root 属主 store。正确做法：守护保持 root，**store 目录 root:root 0555（读+遍历，
  无写）**，版本切换经守护内临时提权目录操作（守护本身 root，无碍）；用户命令同为 root 是**现状既有风险**，
  非本设计引入，由 §9.3 加固升级统一解决
- 本设计不使安全状况劣化：库内容仅发布技能、无敏感数据；污染面 = 现状 `.skills-cache` 污染面的池级放大，
  以 store 目录 0555 + 只经守护写入收窄

### 9.3 OSEP-0018 加固升级的交互（预案，非本次范围）

上游 `execd:release-1.1.0` 的加固链（bwrap + launcher setuid 降权 + session-gate）启用后：
**entrypoint 本身也会被 launcher 降权**——守护拿不到 root，本设计的 root 写入前提失效。
预案（届时二选一，需单独评审）：守护改独立 init 容器（绕过 launcher，kubelet 直接拉起，天然 root）；
或切换为 execd 文件 API 推送形态（平台侧推，沙箱零进程）。本设计在镜像层的守护/entrypoint 解耦
（§5.1 独立二进制 + 包装脚本）为该迁移预留了清晰切面。

## 10. 测试与验收

### 10.1 单测（skills-syncd，Go）

- 版本协商：version 一致零下载 / 变更触发拉取 / 404 与 401 处理 / 退避重试
- 原子性：staging 中断（模拟 kill）不留半成品可读态；CURRENT 切换后旧版本仍可读
- 单版本切换：swap 后旧版必删、CURRENT 指向者不删、启动清扫非当前 `v-*`/`.staging-*` 残留幂等
- env 缺失 → 优雅退出

### 10.2 agent-framework 单测

- 端点：版本指纹稳定性（源只读）、bundle sha256 头正确、token 校验
- 软链农场：manifest 禁用项不建链 / CURRENT 缺失逐技能降级 / 用户技能与软链共存
- syncBack：符号链接条目不回写

### 10.3 真实环境验收（kind 集群）

1. 发布含技能的服务 → 池 warm pod 起后 ≤INTERVAL 内 `/skills-store/CURRENT` 就绪
2. 会话开始：首命令触及技能路径，断言符号链接生效、技能脚本可执行（bash/python 对 symlink 透明）
3. 改包发新版 → ≤INTERVAL+会话粒度 内新会话用新版、旧会话不受扰动
4. 容器重启（kill 1 Restart 回收路径）→ 守护自愈重拉
5. `pkill skills-syncd` → 沙箱命令执行不受影响（fail-open 验证）
6. L 组沙箱生命周期 e2e 全绿（回归门禁）

## 11. 分阶段实施（PR 切分）

| PR | 内容 | 依赖 |
|---|---|---|
| PR-1 | `skills-syncd/` Go 守护 + 单测 + 静态构建（不接线） | 无 |
| PR-2 | agent-framework：内部端点 + acquire 软链农场 + syncBack 跳链 + 开关（默认关） | PR-1 |
| PR-3 | 镜像集成（code-interpreter-oa 加守护与包装入口）+ 池模板 env + egress 放行 + 真实环境验收 | PR-1/2 |
| PR-4 | 默认开启 + 退役包技能维度的 scoped staging/指纹/载荷缓存路径（用户技能路径保留） | PR-3 验收通过 |

PR-4 退役范围在实施时以实际代码为准再定（`OpenSandboxFilesystemSpec` 的包技能分支、
`.skills-cache` per-user staging 的包部分）；`sandbox-skill-sync-optimization-design.md` 的
P0-1/P1-3 机制对用户技能维度继续生效。

## 12. 备选方案与淘汰理由（决策记录）

| 方案 | 淘汰理由 |
|---|---|
| 对象存储挂载（S3/rclone sidecar） | sidecar 资源与运维面（用户否决）；CSI 需集群组件与 SDK volume API（上游 implementing、我们的 Java SDK 无此面） |
| ZeroFS-NFS | Kind 节点 NFS 支持待验证 + 2026 新项目生产案例薄；NFS 路径正确但非当前最优解 |
| execd 文件 API 推送（平台侧） | **无法预热未认领的 warm pod**（无 sandbox 身份不可寻址，OSEP-0007 核实）；acquire 时补推可行但首推送延迟进会话路径 |
| initContainer 一次性拉取 | 新鲜度 = Pod 生命周期，池化 warm pod 长寿导致版本漂移（用户识别的缺陷） |
| OpenViking 接管记忆/技能 | 语义层（上下文组织）非传输层；FUSE 实验性未使用；技能执行仍需真文件物化 |
| 守护按命令分析按需取 | 路径解析真相只在内核 VFS；粗粒度版（命令触发 scope 投放）已存在于现行为 |
| **守护自拉 + entrypoint（本设计）** | 预热窗口最长（Pod 创建即同步）、生命周期零影响、自愈内建、零新部署组件、镜像链路现成 |

## 13. 开放问题（评审待定）

1. token 的分发与轮换节奏（平台 Secret 管理，随 PR-3 定）
2. 多包服务：当前 OAF 一服务一包（服务实例即包身份，端点无 package 参数）；若未来出现一服务多包，
   需端点契约演进与 store 目录分层，届时另立设计
3. `SKILLSYNC_INTERVAL` 生产推荐值（5m 起步，按版本发布频率调整）
4. bundle 缓存的进程内 LRU 容量（单包 66MB 常驻是否可接受，或磁盘暂存）
