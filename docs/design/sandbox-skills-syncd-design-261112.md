# 沙箱技能同步守护设计（skills-syncd + 镜像 entrypoint）- 261112

> 状态：**设计定稿（三轮架构评审意见已吸收，决议 D-1~D-9 见 §13）**
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

1. 会话开始的技能投影从「O(包技能全量) tar 物化」降为「O(启用技能数) 符号链接创建」（建链毫秒级，经单条 exec 投放，端到端 ≈1 次 exec RTT，如实口径见 §8）
2. 消除包技能的每用户物理复制：同一 warm pod 内全服务共享一份（per-pod 一份，非 per-user）
3. 版本新鲜度分钟级（守护轮询周期可配），版本切换对运行中会话零扰动
4. 沙箱内新增常驻进程资源占用可忽略（单二进制、轮询间歇休眠）
5. 同步源地址全部经环境变量配置，单集群零新增部署组件

### 非目标

- 不做多节点共享存储（PVC/NAS/对象存储挂载）——刻意接受 per-pod 一份复制换取部署简单，多节点场景升级路径见 §12
- 不改动用户技能（L4）与记忆（MEMORY.md/memory/）的分发——继续走现有小 scope hydrate/syncBack
- 不在本次启用 OSEP-0018 加固（独立事项，见 §9.3）
- 不做跨集群交付（oaf-agent charts 场景）——拉取源抽象预留，实施另立项

### 后续演进（不进本次范围）

本设计只接管 `.skills-cache`（包技能）；`AGENTS.md`/`knowledge`/`subagents` 同为服务级共享只读内容，
仍走原投影。二期方向：store 扩容为「服务级共享只读内容库」，投影机制最终整体退役
（本次两套机制并存是刻意过渡，不是终态）。

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
│  启动点（镜像内 launcher 脚本）                                 │
│    池化：Pool 模板 command 包 wrapper（backend preset 参数化）  │
│    直建：镜像 ENTRYPOINT 包装                                  │
│    setsid /usr/local/bin/skills-syncd &   ← Go 守护，root      │
│    exec <原命令/task-executor> "$@"                            │
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
| `SKILLSYNC_INTERVAL` | `1m` | 轮询周期（Go duration 格式，如 `30s`/`1m`/`5m`）；轮询 <200B/次，默认收紧到 1m 把新版生效延迟压到不可感知 |
| `SKILLSYNC_TIMEOUT` | `120s` | 单次 HTTP 请求超时（bundle 可能数十 MB） |
| `SKILLSYNC_MAX_BUNDLE_BYTES` | `536870912` | bundle 大小上限（512MB；超限拒拉 + 告警退避，防异常大包撑爆容器盘） |
| `SKILLSYNC_LOG` | `stderr` | 日志输出目标；launcher 将守护 stderr 统一重定向至 `/var/log/skills-syncd.log`（唯一日志落点，自带上限截断，防长寿命 warm pod 涨盘，见 §5.1）；显式指定其他文件路径则守护直写该文件 |

`SKILLSYNC_BASE_URL` 缺失 → 守护启动即退出（exit 0，日志说明），
沙箱按无同步库运行（acquire 链路自动回退现行为，见 §6.4）。

### 4.3 版本协商协议

```
GET {BASE_URL}/internal/skills/version
  → 200 {"version": "<sha256[:16]>", "skillCount": 2000, "sizeBytes": 66000000}
  ← 401 token 无效

GET {BASE_URL}/internal/skills/bundle?version=<v>
  → 200 application/gzip（tar.gz：顶层为各技能目录 <skillName>/...，**仅含未被服务级禁用的技能**，B-2）
  响应头 X-Bundle-Sha256：<完整哈希>（守护校验后才落盘）
```

**确定性打包规格**：条目按路径排序、mtime 固定（0）、gzip 无时间戳——同一 version 在任意副本
产出字节一致（sha 头稳定，为未来内容寻址留路）。version 响应含可选 `serverProtocol` 字段
（守护忽略未知字段），守护启动日志输出 build SHA，排障可对齐协议版本。

一服务一包：每个 agent-framework 服务实例只承载一个 OAF 包（服务即包身份的单一真相源），
端点无需 package 参数——`BASE_URL` 已标识请求的是哪个服务/包。

协议演进约定：新增响应字段一律可选（守护忽略未知字段）；删除或改语义字段须升级路径版本
（如 `/internal/skills/v2/...`）。守护版本随镜像发布，协议对齐由池滚动升级保证。

**多通道预留（D-9）**：二期「服务级共享只读内容库」（AGENTS.md/knowledge 入库，§2）不走 v2 路径
迁移——届时 version 响应新增可选 `channels` 对象（如 `{"skills": {...}, "knowledge": {...}}`，
各通道独立 version、独立 bundle、独立比较与拉取），守护按认识通道处理、忽略未认识通道；现行
flat 字段即 skills 通道的平铺形态。避免「knowledge 小改触发全量技能 bundle 重拉」的通道间放大。

版本指纹由 agent-framework 侧计算：对包技能目录做 **stat 指纹**（`relPath|mtime|size` 排序遍历），
**叠加服务级（全局）禁用集**（B-2：全局禁用变更 → 指纹变 → 守护重拉新 bundle），与现有
`packageSourceFingerprint` 同口径——继承其 mtime/size 盲点（PVC tar 发布下稳定，可接受），
**不宣称**「防版本号没变内容变了」的强保证；内容哈希仅用于 bundle 完整性校验（`X-Bundle-Sha256`）。
守护每次轮询只调 version 端点（响应 <200B），版本一致零流量；变更才拉 bundle。

### 4.4 主循环与原子切换

```go
for {
    jitterSleep(0~30s 随机)             // 轮询错峰（version 请求 <200B，轻量）；重操作风暴由拉取退避承担
    ver, err := fetchVersion()          // 失败 → 退避重试（1m 起指数，上限 = INTERVAL），fail-open
    if err == nil && ver != currentVersion(storeDir) {
        sleep(rand(0, INTERVAL))         // 拉取退避（D-7）：集群级重拉铺满一个周期，防同刻风暴
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
  尚未 open 的解析撞上删除 → 该次命令失败，由 agent 自然重试消化（毫秒级窗口，观测项非降级机制）

### 4.5 失败语义（fail-open）

守护任何失败（源不可达、校验失败、磁盘满）**只影响新鲜度，不影响沙箱可用性**：
execd/命令执行与守护完全解耦（`setsid` 脱离会话、独立进程组）。守护无健康探针——
它的「不健康」表现为版本陈旧，由日志与 agent-framework 侧版本对账（§7.2）观测，不构成告警风暴。
守护异常退出由 launcher watchdog 自愈（D-6，§5.1）：退出后 ≤30s 重启（sleep 间隔兼任崩溃循环
节流阀），版本冻结窗口从「直到池回收」收窄到 ≤30s + 一个拉取周期；空闲 warm pod 上的静默陈旧
问题随之消除——冻结态不再存在，剩余观测缺口只有「从未成功拉取」，由 D-5 打点在首个认领会话暴露。

## 5. 镜像与 entrypoint 集成

### 5.1 自建镜像职责（复用 code-interpreter-oa 构建链路）

镜像只承担**载体**职责：提供 `skills-syncd` 二进制与 launcher 脚本；启动点分两态（§5.2）。

```dockerfile
# code-interpreter-oa 构建末段追加
COPY --from=skills-syncd-build /out/skills-syncd /usr/local/bin/skills-syncd
COPY skillsync-launcher.sh /usr/local/bin/skillsync-launcher.sh
COPY entrypoint-wrap.sh /usr/local/bin/entrypoint-wrap.sh
# 直建路径生效；池化路径的 ENTRYPOINT 被 Pool 模板 command 覆盖，见 §5.2
ENTRYPOINT ["/usr/local/bin/entrypoint-wrap.sh"]
```

```sh
#!/bin/sh
# skillsync-launcher.sh —— 通用启动器：watchdog 拉起守护后 exec 传入命令
# 两条铁律：1) 后台循环绝不阻塞主进程；2) exec 保持主进程语义
log_file=/var/log/skills-syncd.log
log_max_bytes=$((1024 * 1024))
if [ -n "$SKILLSYNC_BASE_URL" ]; then
    (while :; do
         setsid /usr/local/bin/skills-syncd 2>>"$log_file"
         sleep 30
     done) &
    (while :; do
         sleep 30
         if [ -f "$log_file" ] && [ "$(wc -c <"$log_file")" -gt "$log_max_bytes" ]; then
             tail -c "$((log_max_bytes / 2))" "$log_file" >"$log_file.tmp" &&
                 cat "$log_file.tmp" >"$log_file"
             rm -f "$log_file.tmp"
         fi
     done) &
fi
exec "$@"
```

守护由 watchdog 循环拉起（D-6）：正常/异常退出均 ≤30s 重启（sleep 兼任崩溃循环节流阀）；
stderr 统一重定向 `/var/log/skills-syncd.log`（§4.2 唯一日志落点）。守护正常态不退出
（`context.Background()` 恒存活），watchdog 重启仅在崩溃后发生，故 §4.2 的上限截断由
**独立周期循环**（同为 30s 节拍）执行：超 1MiB 触发，就地截断（`cat` 重写而非 `mv`，守护
O_APPEND fd 不悬空、后续写入继续落原 inode）并保留尾部 512KiB（滞回：回落至半限，再涨
半限才再触发，避免稳态反复重写）。

```sh
#!/bin/sh
# entrypoint-wrap.sh（直建路径）：委托 launcher 后 exec 原入口
exec /usr/local/bin/skillsync-launcher.sh /opt/code-interpreter/code-interpreter.sh "$@"
```

env 未注入时零行为变化（非技能同步场景的沙箱镜像可直接复用）。

### 5.2 池模板注入：preset 受控参数化（保留手动通道）

**启动点事实（评审核实，决定本节的形态）**：池化 warm pod 的主容器 `command` 由 backend preset 固定
（`/bin/sh -c "/opt/opensandbox/task-executor ..."`，`opensandboxproxy.go:491-545`）——K8s `command`
覆盖镜像 ENTRYPOINT；且 `/opt/opensandbox` 为 emptyDir 挂载（遮蔽镜像内同路径），task-executor 二进制
由 initContainer 从固定上游镜像拷入。**结论：镜像 entrypoint 在池化路径无效，守护启动点必须由
backend preset 参数化提供**；镜像仅提供二进制/launcher（§5.1）。

**受控参数化字段（`sandboxPresetPodTemplate` 新增两个结构化可选参数，不接受自由 PodSpec）**：

| 参数 | 形态 | 缺省 |
|---|---|---|
| `extraEnv` | `[{name, value}` 或 `{name, secretKeyRef}]` 白名单键集（`SKILLSYNC_*`） | 空 = 模板逐字段与现状一致 |
| `commandWrapper` | 布尔/固定脚本名（`/usr/local/bin/skillsync-launcher.sh`） | off = 现 command 原样 |

**注入来源两态（保留手动实施空间）**：

1. **自动**（常规路径，C-1 决议——一期仅校验）：不新增发布自动建池步骤；发布/框架侧校验管理员
   **预建池**符合 `{k8s名}-pool` 命名且与服务绑定唯一，`SKILLSYNC_*` 参数经建池 API 随池带入
   （`SKILLSYNC_BASE_URL` 由服务身份生成、`SKILLSYNC_TOKEN` 引用 per-service Secret
   `{服务名}-skillsync-token`、`commandWrapper` 开）。发布链路自动建池列为后续增强
2. **手动**（C-2 决议——一期 = admin API）：允许手填 `extraEnv`/`commandWrapper` 与池名——覆盖特殊服务、
   灰度、排障、存量池补配等场景；运维控制台表单列为后续。手填路径校验：池名全局唯一 +
   与服务的绑定关系显式（避免 G1 共享池误配）；未通过校验的建池请求拒绝

**池与服务一一对应（G1）**：自动路径保证唯一；手动路径由上述校验 + 运行期 §7.2 版本硬校验双重兜底
（B 服务认领到 A 服务 store → 指纹不一致 → 会话级降级 + ERROR）。

示例（自动路径生成的 Pool 模板片段）：

```yaml
# backend 在 preset 生成时注入
env:
  - { name: SKILLSYNC_BASE_URL,   value: "http://oaf-{name}-svc.agent-platform.svc:8100" }
  - { name: SKILLSYNC_TOKEN,      valueFrom: { secretKeyRef: { name: "{name}-skillsync-token", key: token } } }
  - { name: SKILLSYNC_INTERVAL,   value: "1m" }
command: ["/bin/sh", "-c"]
args: ["W=/usr/local/bin/skillsync-launcher.sh; [ -x \"$W\" ] || W=\"\"; exec $W /opt/opensandbox/task-executor -listen-addr=0.0.0.0:5758 -log-dir=/tmp"]
```

**模板-镜像偏斜守卫（D-1）**：args 内联 launcher 存在性检查——模板已开 `commandWrapper` 而池内
pod 仍是旧镜像（无 launcher 脚本）时，未加引号的空 `$W` 在 POSIX sh 中展开为空、原命令原样执行：
偏斜退化为「无守护的旧行为」（fail-open），而非 warm pod crashloop。镜像回退、模板先行两类偏斜
方向均被覆盖；镜像升级后守卫恒真，无需回改模板。

token 为 per-service 只读凭据（Secret 由平台下发或管理员预建；agent-framework 侧校验），
`§9.1` 的「不接受跨服务枚举能力」由 per-service token 兑现。

### 5.3 网络前提

沙箱 Pod → agent-svc 出网需在 OpenSandbox egress 策略放行（FQDN `oaf-{name}-svc.agent-platform.svc`
或集群内网段）。部署清单与运维备忘同步补一行（docs/deployment.md）。

## 6. agent-framework 侧变更

### 6.1 内部端点（新增 `SkillBundleController`）

- 路径 `/internal/skills/version`、`/internal/skills/bundle`；token 经 env `SKILLSYNC_TOKEN` 配置
  （空 = 端点关闭，404）。**鉴权关系**：仅 token 校验，不参与 `ROUTER_AUTH_MODE`/业务鉴权链，
  且不经业务 Ingress 对外暴露（仅集群内访问）
  > 补记（2026-11-13）：该断言在 host 模式（`INGRESS_HOST_SUFFIX` 非空）根路径直出下曾被
  > 静默穿透（`/internal/skills/**` 与 `/actuator/**` 均经业务域名可达）。已由平台强制实现：
  > host 模式 per-service Ingress 构造时把 `/internal`、`/actuator` 前缀改道
  > `platform-router-svc`（router `location /` 恒 404），`validateIngress` 拒绝丢失或改指的
  > overlay——backend/internal/k8s objects.go `hostModeInternalPath`、template.go
  > `validateIngress`；运维口径见 docs/deployment.md §七之二/§七之三。router 模式只路由
  > `/agent/{short}`，天然满足。
- 数据源 `/config`（PVC 只读 subPath，多副本天然一致）
- **版本指纹 singleflight + ≤5s TTL 缓存**（D-4，修订 G2 的绝对化表述）：对包技能目录做 stat 指纹
  （relPath|mtime|size 排序遍历，2000 文件 ≈10-50ms）+ 服务级禁用集（B-2）。新鲜度语义由守护侧
  轮询 INTERVAL（分钟级）决定，服务端 5s 缓存不改变任何消费方可见语义；agent-framework 收不到
  发布事件因此不做事件失效，靠 TTL 过期自然重算。规模化下（1000 pod × 1/60s 轮询 ≈ 0.8 核纯
  stat 遍历）该缓存是必要项而非优化项；内容哈希只在 bundle 打包时使用
- **bundle 产物按 version 落盘缓存**（D-3，关闭原开放问题 4）：确定性打包（§4.3）保证同 version
  字节一致，磁盘缓存即内容寻址——keep-2 LRU、目录可配（默认 `/tmp/skills-bundle-cache`）、
  pod 重启冷启重建一次可接受。版本发布的集群级风暴从「N 次 tar+gzip 压缩」降为
  「1 次压缩 + N 次文件读」；流式即时压缩仅作缓存目录不可写时的实现内退化路径，不作为设计形态

### 6.2 acquire 链路：会话级二值判定 + 符号链接农场（B-1/B-2/B-4 决议）

**会话级二值判定**：首个触及 `.skills-cache` 的命令触发时（沿用现有 CAS 每 call 触发点）执行一次——
`CURRENT` 可解析且 §7.2 版本硬校验通过 → **本会话软链模式**；任一不满足（含端点不可达）→
**本会话整体走原 tar 物化**（现行为，≈现状体验）。判定结果按会话键记忆（与 session_user 规范键同源）：
降级会话不中途翻转，恢复以会话为粒度（新会话重新判定）。**无逐技能混排、无并行第二状态机**。

**workspace 前提（D-2，代码核实）**：绑定模型 session→sandbox 并发 1:1（`sandbox_binding` 的
uk_session 唯一键），但**沙箱跨会话顺序复用**（池回收再认领 / LRU 改绑，可写层含 `.skills-cache`
跨会话残留）——农场不得假设「每会话全新 workspace」，必须具备残留收敛语义（规则 4）。

软链模式下的农场规则：

1. **次序固定：先 L4 用户技能物化（真实文件），后包技能软链**——同名时 L4 真实文件优先、包技能软链跳过
   （对齐现有 L4-enabled 覆盖语义；该裁决发生在 SDK 消费层而非文件系统层）；软链目标
   `/skills-store/CURRENT/<skillName>`（间接寻址，不钉版本目录——B-4 决议：接受切换即生效语义，
   不做会话钉版，SKILL.md 与脚本的换版错配窗口 ≤INTERVAL 为已接受项）
2. **manifest 强约束**：农场与旧投放路径必须复用**同一个** manifest/禁用判定服务（`SkillDisabledChecker`
   同源），禁止农场侧重写过滤规则
3. **禁用两级语义（B-2）**：**服务级（全局）禁用 = 新版本**——全局禁用集纳入 bundle 内容与版本指纹
   （禁用变更 → 指纹变 → 守护重拉；被全局禁用的技能**内容不进 store**，恢复内容级隔离）；
   **用户级禁用维持原消费路径拦截**——不建链，且农场触发时对 manifest 已禁用/移除的既有软链执行删除
   （对齐现有容器内 rm 清理语义，覆盖会话中途禁用生效）
4. **幂等与残留收敛（D-2）+ 稳态短路（D-10）**：农场对 **manifest 内的包技能路径有全权**——目标态为
   软链，路径现存任何物（缺失 / 悬空 / 降级会话 tar 物化残留的真实文件）统一收敛为软链。「真实文件不替换」
   仅保护 manifest 判定为 L4 用户技能的名字（同名归属由 manifest 裁决，与 SDK 消费层同源，规则 2）——
   降级残留若不收敛，同沙箱后续农场会话将永久停留在陈旧私有副本（跨会话顺序复用是常态而非边角）。
   收敛**不按每次触发**执行（原 H2「每次触发收敛」口径已修订，见 D-10）：与 tar 路径 P0-1 同源的稳态
   短路——宿主指纹（包内源 stat + L4 revision，缺口④同源）+ sandboxId 代际记账（复用 scoped 指纹表），
   稳态 0 exec（原口径每次触发跑全量收敛 exec，单条 exec RTT 固定 ≈1s，农场稳态反而劣于 tar 稳态 ≈35ms，
   与 §8 目标内在冲突）。收敛触发面 = 新沙箱代（池认领/降级重建/换代，记账随新 sandboxId 清零，覆盖
   §10.3-6 与 §10.3-11 的残留场景）、宿主内容变化（发布 → 包指纹；用户禁用/变更 → L4 revision bump；
   全局禁用 = 新版本，store 内容移除使既有链悬空即生效）、收敛 exec 失败（不记账，CAS 复位下次重试）。
   **显式接受盲点**：会话中途容器重启（同 sandboxId resume）后软链不重建——与投影指纹缓存已接受的
   容器态盲点同一系统性姿态（同代内 tar 物化/记忆注入/L4 物化同样不重投，宿主内容未变即视为容器即最新）；
   否决 per-call 容器侧标记兜底——任何容器态观测本身就是 1 次 exec RTT，与稳态目标互斥
5. 用户技能（L4）走原 hydrate 路径物化真实文件（量级几十文件，现有稳态机制接管）

### 6.3 syncBack 符号链接跳过（防御性）

`WorkspaceSyncService.syncUserSkills` 遍历时跳过 `Files.isSymbolicLink` 条目。
核实现状：该遍历只覆盖 `/workspace/skills`（`WorkspaceSyncService.java:216`、`WorkspaceReader.SKILLS_DIR`），
**不遍历 `.skills-cache`**——因此当前「库内容被吸回 L4」的风险并不成立；跳链保留为防御性约束，
确保未来遍历范围扩展时不引入吸回。files API 侧的软链透明性验收见 §10.3。

### 6.4 回退与开关

| 开关 | 默认 | 语义 |
|---|---|---|
| `SANDBOX_SKILLS_SHARED_STORE` | 空 | 沙箱内共享库路径（`/skills-store`）；空 = 关闭软链农场，走现行为 |
| `SKILLSYNC_TOKEN` | 空 | agent-framework 端点鉴权；空 = 端点关闭 |

回退 = **会话级二值**（B-1 决议）：判定失败（store 目录缺失 / `CURRENT` 解析失败 / 版本硬校验不一致
（§7.2）/ 端点不可达）→ 本会话整体走原 tar 物化路径（现行为）。无逐技能混排；
恢复以会话为粒度，新会话重新判定。

## 7. 版本与一致性语义

### 7.1 时序保证

- 发布新版 → agent-framework 端点版本指纹变更（即时，随发布事件失效进程内缓存）
- 沙箱侧最长 `2×INTERVAL` 内拉到（轮询 `INTERVAL` + 拉取退避 `INTERVAL`，D-7）→ `CURRENT` 原子切换，**下一次路径解析即见新版**（含运行中会话）；
  已打开的技能脚本/文件凭 inode 继续按旧内容执行完毕（Unix 语义），无中途截断
- 与现行为对比：现 per-call 指纹在下一 call 换新版——本设计同为「切换即生效」语义，
  且单文件粒度更平滑（同会话内未重开的文件不受扰动）
- **首认领竞态（G4，记录）**：warm pod 创建后首拉完成前若被认领，该会话按 §6.4 全量降级
  （≈现状体验，无回退）；poolMin 预热在先 + 内网拉取秒级，现实风险低。若观测到高命中率，
  可加 initContainer 首拉加速（与守护共存不冲突）
- **发布窗口语义（F1）**：agent-framework 滚动发布期间多副本新旧指纹交替（svc 轮询可能命中不同副本），
  守护会反复拉取切换、acquire 硬校验可能持续失败。守护侧容忍策略：对「最近已见版本集（当前 + 上一版）」
  在 2×INTERVAL 内不触发重拉；硬校验以会话级回退吸收（判定失败 → 本会话降级），发布窗口内新会话
  短暂走旧投影属可接受行为。发布链路应错峰滚动（现有 rollout 语义）

### 7.2 版本硬校验与对账观测

**硬校验（G1 防串服务）**：acquire 创建软链前，取端点版本指纹（可带 ≤INTERVAL 的短 TTL 缓存——
只造成假阴性窗口内的多余降级，不造成污染）与 store `CURRENT` 版本比对——**不一致视同 store 不可用，
本会话降级 + ERROR 日志**。同时拦截两类错误：共享池误配导致 store 内容属于别的服务（池名唯一性约束见
§5.2）、守护长期失联。

**store 侧版本读取钉死（SSOT 边界）**：store 版本 = `readlink CURRENT` 得到的目录名，仅做**字符串相等**
比较；**禁止**在 Java 侧重算 store 内容的 stat 指纹（跨语言 stat 规则——mtime 精度、tar 解包还原、
排序——必然漂移，会导致永久误报降级）。版本字符串对守护是不透明 token，单向数据流无重复实现。

纯观测面：acquire 创建软链时记录「store 版本 vs 端点版本」，偏差持续超 3×INTERVAL 打 WARN（不阻断）。

**判定打点（D-5，本功能第一 SLO）**：会话级二值判定结果落 Micrometer Counter——
`skills.farm.decision`，tag `result` ∈ {`farm`, `degraded_store_missing`, `degraded_version_mismatch`,
`degraded_endpoint_down`}。农场命中率直接回答「池漏配 / 灰度进度 / F1 发布窗口影响面」三个运维
问题，是 C-1 手动预建池漏配的唯一非日志信号；micrometer-core 随 actuator 已引入（零新增依赖），
`management.endpoints.web.exposure` 增补 `metrics` 端点即可读。一期集群无 Prometheus 采集器，
人工/脚本对账 + 每次降级判定同步落一行结构化 WARN（含 result）；接入采集器列为后续。

### 7.3 版本切换与删除次序（单版本语义）

固定次序：**先切 `CURRENT`，后删旧版**。读方全部经 `CURRENT` 间接寻址（§6.2），指针跳变原子、
旧版删除不破坏任何在用链接；已打开文件凭 inode 存活。守护启动时清扫非当前 `v-*` 与 `.staging-*`
残留（上次崩溃在「已切换未删除」或「下载中」的幂等自愈）。毫秒级解析竞态：运行中命令撞删除即失败，
由 agent 自然重试消化（观测项）。

## 8. 容量与性能预估

| 项 | 现状 | 本设计 |
|---|---|---|
| 换代/首触冷路径 | 4.9s（P0-1 前每 call 全量；现仅在换沙箱代或换 scope 时发生，被会话中首个触及技能的命令触发） | 判定（readlink exec + 端点版本指纹）+ 单条收敛 exec——建链毫秒级，端到端 ≈1 次 exec RTT（实测 exec 固定 ≈1s）；对比 tar 慢路径（P1-3 后）省 gz build+gzip 与全量 tar 传输，载荷降为 O(启用技能数) 链接命令 |
| 稳态 per-call | ≈0.08–0.1s（P0-1 优化后：指纹 35ms + revision 1ms + syncBack 24ms） | 与现状同源持平（同一指纹稳态短路，0 exec；农场判定与硬校验仅会话首判一次，不进稳态路径——如实口径，非 ~10ms） |
| 每用户复制 | scope staging N 份（66MB/用户量级） | 0（store 服务内共享 per-pod） |
| 沙箱内磁盘 | 按需物化 ~66MB | 稳态 1 版 ≈ 66MB；切换瞬时峰值 ≈ 2 版 + tar.gz（~200MB），完成后回落 |
| 网络 | 每次 hydrate 全量 tar（跨 exec 通道） | 每 pod 每版本一次 HTTP 拉取（version 轮询 <200B/1m） |
| agent-framework | 每次 hydrate CPU（tar+gzip+指纹） | 版本指纹 singleflight+5s TTL（D-4）；bundle 每版本压缩一次、此后磁盘缓存命中即文件读（D-3） |

节点维度：切换瞬时峰值（≈2 版 + tar.gz，~200MB/pod）经拉取退避（D-7）铺满 INTERVAL 后自然
错峰，节点级同时切换 burst 不再叠加；服务端压缩风暴同理由 D-3 消解。

收益主体排序（避免误读基线）：①换代冷路径全量 tar 物化 → 单条收敛 exec（省 build/gzip 与传输）；
②每用户 66MB 级复制消除；③宿主内容变化后的重投放从 tar 慢路径降为单条收敛 exec；
④稳态 per-call 与现状持平——同一指纹短路（0 exec），农场不引入稳态回退（修订原「35ms→~10ms」口径：
农场收益在冷路径与复制消除，不在稳态）。

## 9. 安全设计

### 9.1 凭据

- token 只授两个 GET 端点、只读、仅本服务包内容；经 K8s Secret 注入
- 暴露面评估：token 进入沙箱（不可信代码可读 env）。缓解：scope 收窄到「读本服务已发布技能」，
  泄露代价 = 技能内容本身（本就是发布内容）。**不接受**该 token 具有任何写能力或跨服务枚举能力
- **轮换契约（D-8，关闭原开放问题 1）**：轮换 = 更新 Secret + 池模板 rollout 重启（K8s env 变更的
  自然语义；重启后守护携新 token 自动拉平版本）。不引入服务端双 token 重叠窗口——为罕见运维
  事件在鉴权路径维护双值状态，复杂度不成比例

### 9.2 UID 现实与写穿语义（B-3 决议）

- **如实声明**：当前镜像全链路 root（§1.3），守护可被 `pkill`、store 可被篡改；`0555`/root 属主
  在全 root 下**无访问控制意义**（root 绕过 DAC），仅为 OSEP-0018 降权后的预留姿态，真实隔离由
  §9.3 加固升级兑现
- **写穿污染生命周期（B-3：包技能只读，沙箱重建后修改不生效即可）**：store 位于容器可写层，
  任何写穿修改的生命周期 = Pod 生命周期；同版本内守护不重拉（版本未变零流量），污染**不自愈**，
  随容器重启 / 池回收 / 下一版本发布消除（重拉经 sha256 校验，干净重建）。「重建后修改不生效」
  由 store 重建语义保证
- **全局禁用技能内容不进 store**（B-2，§6.2）——全局禁用恢复内容级隔离；用户级禁用维持消费路径拦截
- 本设计不使安全状况劣化：库内容仅发布技能、无敏感数据；「包技能只读」为产品语义（写路径不承诺成功，
  成功也不延续过 Pod 重建）

### 9.3 OSEP-0018 加固升级的交互（预案，非本次范围）

上游 `execd:release-1.1.0` 的加固链（bwrap + launcher setuid 降权 + session-gate）启用后：
**entrypoint 本身也会被 launcher 降权**——守护拿不到 root，本设计的 root 写入前提失效。
预案（届时二选一，需单独评审）：守护改独立 init 容器（绕过 launcher，kubelet 直接拉起，天然 root）；
或切换为 execd 文件 API 推送形态（平台侧推，沙箱零进程）。本设计在镜像层的守护/entrypoint 解耦
（§5.1 独立二进制 + 包装脚本）为该迁移预留了清晰切面。

## 10. 测试与验收

### 10.1 单测（skills-syncd，Go）

- 版本协商：version 一致零下载 / 变更触发拉取 / 404 与 401 处理 / 退避重试
- 拉取退避（D-7）：版本变更后拉取延迟分布 ∈ [0, INTERVAL)；重拉容忍集（当前 + 上一版）窗口判定
- 原子性：staging 中断（模拟 kill）不留半成品可读态；CURRENT 切换后旧版本仍可读
- 单版本切换：swap 后旧版必删、CURRENT 指向者不删、启动清扫非当前 `v-*`/`.staging-*` 残留幂等
- env 缺失 → 优雅退出

### 10.2 agent-framework 单测

- 端点：版本指纹稳定性（源只读）、bundle sha256 头正确、token 校验
- 软链农场：会话级二值判定（store 就绪 + 硬校验）与判定记忆（会话键）/ 判定失败全会话降级不混排 /
  manifest 禁用项不建链且既有链删除 / 全局禁用集变更触发版本指纹变化 / 悬空链重建 / 用户技能与软链共存 /
  稳态短路（D-10）：同沙箱代同指纹跳过收敛 exec（0 exec）/ 宿主内容变化（包源变更、用户禁用经
  L4 revision bump）重收敛 / 收敛 exec 失败不记账、下次触发重试
- syncBack：符号链接条目不回写

### 10.3 真实环境验收（kind 集群）

1. 发布含技能的服务 → 池 warm pod 起后 ≤INTERVAL 内 `/skills-store/CURRENT` 就绪
2. 会话开始：首命令触及技能路径，断言符号链接生效、技能脚本可执行（bash/python 对 symlink 透明）
3. 改包发新版 → ≤INTERVAL+会话粒度 内新会话用新版、旧会话不受扰动
4. 容器重启（kill 1 Restart 回收路径）→ 守护自愈重拉
5. `pkill skills-syncd` → 沙箱命令执行不受影响（fail-open 验证）
6. **容器重启（kill 1 → 池化复用，新沙箱代认领）**：守护自愈重拉 + 农场链随新代首收敛重建，技能可用
   （原 H2 口径已修订为 D-10 代际收敛）；会话中途容器重启（同 sandboxId resume）软链不重建——与投影
   指纹缓存已接受的容器态盲点同一系统性姿态（§6.2-4），宿主内容下次变化或新沙箱代自愈
7. **execd files API 软链透明性**：`read_file`/`listDirectory` 对 `/skills-store/CURRENT/<skill>`
   实测——穿透则直读；不穿透则验证框架翻译层 exec 兜底读正确（§13-8 决议）
8. L 组沙箱生命周期 e2e 全绿（回归门禁）
9. **模板-镜像偏斜（D-1）**：旧镜像 pod + 开 `commandWrapper` 的新模板 → 守卫兜底，
   task-executor 正常启动（无守护、会话降级），不出现 crashloop
10. **守护自愈（D-6）**：`kill -9 skills-syncd` → watchdog ≤30s 重启，版本继续跟进，会话不受扰动
11. **降级残留收敛（D-2）**：同沙箱先降级会话（tar 物化真实文件）→ 后农场会话，包技能路径
    收敛为软链（`ls -l` 断言）
12. **判定打点（D-5）**：`/actuator/metrics/skills.farm.decision` 各 result 计数可读，
    降级判定伴随 WARN 日志

### 10.4 排障 Runbook

| 现象 | 查法 |
|---|---|
| 技能是旧版 | 沙箱内 `readlink /skills-store/CURRENT` + `cat /var/log/skills-syncd.log`（拉取失败/退避原因）；agent-framework 侧 `curl -H "Authorization: Bearer $TOKEN" $BASE_URL/internal/skills/version` 对账 |
| 技能缺失/降级 | agent-framework 日志查 §7.2 ERROR（版本硬校验失败）与降级 WARN；确认 store 目录与 `CURRENT` 是否存在 |
| 农场软链丢失（会话中途容器重启后技能不可用） | 已接受盲点（§6.2-4/D-10）：同 sandboxId 容器重启可写层清空而记账未失效 → 不重收敛；宿主内容下次变化或新会话（新 sandboxId）自愈；紧急恢复 = 重开会话 |
| 跨服务技能污染 | 查 §5.2 池名是否被两服务共用；`CURRENT` 版本指纹 vs 本服务端点指纹必然不一致（§7.2 已拦截并降级） |
| 拉取风暴 | 拉取退避已铺满 INTERVAL（D-7）+ 服务端磁盘缓存（D-3）后理论风暴 = 文件读；仍异常先查缓存命中，再考虑调大 `SKILLSYNC_INTERVAL` |
| 守护未运行 | 沙箱内 `ps` 查 `skills-syncd`；确认 `SKILLSYNC_BASE_URL` env 是否注入（preset/手动通道） |
| 技能被改坏（写穿） | 包技能只读（§9.2）；污染生命周期 = Pod 生命周期，同版本不自愈——重启容器/回收池/发新版消除；勿手工修 store |
| 守护崩溃 | launcher watchdog ≤30s 自动重启（D-6）；呈崩溃循环查 `/var/log/skills-syncd.log` 根因，必要时摘 env 停用（BASE_URL 缺失即不启动） |
| 灰度混合池（部分 pod 旧镜像） | launcher 缺失的更早失效点由偏斜守卫兜底为无守护运行（D-1）；旧 pod store 缺失 → 会话级降级走旧投影；灰度口径按 D-5 判定打点 result 维度区分 |

## 11. 分阶段实施（PR 切分）

| PR | 内容 | 依赖 |
|---|---|---|
| PR-1 | `skills-syncd/` Go 守护 + 单测 + 静态构建（不接线）；**CI 归属**：backend-ci 新增 job（paths 加 `skills-syncd/**`，go vet + go test，Go 1.26），**一期非必需观察**（C-3，转必需按分支保护加勾流程） | 无 |
| PR-2 | agent-framework：内部端点（bundle 磁盘缓存 + version 端点短 TTL，D-3/D-4）+ acquire 会话级二值判定与软链农场（残留收敛 D-2 + 判定 Counter D-5）+ syncBack 跳链 + 开关（默认关）+ **`.skills-cache` 布局消费方核对清单**（X18 探针 / SkillCacheGcService / 快照差分键，source-ns 两层布局逐点确认） | PR-1 |
| PR-3 | **backend preset 受控参数化**（`extraEnv`/`commandWrapper` + 偏斜守卫内联生成（D-1）+ 手动通道（一期 admin API，C-2）+ 池名/绑定校验（一期校验预建池，C-1）+ per-service Secret 下发与轮换契约（D-8））+ 镜像集成（`skills-syncd` 二进制 + launcher watchdog（D-6）+ 直建路径 entrypoint 包装）+ egress 放行 + kind 真实验收 + **回滚 runbook**（镜像 tag 回退、池模板参数摘除、Secret/egress 清理） | PR-1/2 |
| PR-4a | 默认开启（旧代码 dormant 保留，可回滚） | PR-3 验收通过 |
| PR-4b | 观察期后退役包技能维度的 scoped staging/指纹/载荷缓存路径（用户技能路径保留；**连带拆分混合指纹函数**，包指纹逻辑整体移交端点侧） | PR-4a 稳定观察 |

PR-4 退役范围在实施时以实际代码为准再定（`OpenSandboxFilesystemSpec` 的包技能分支、
`.skills-cache` per-user staging 的包部分）；`sandbox-skill-sync-optimization-design.md` 的
P0-1/P1-3 机制对用户技能维度继续生效。**PR-4b 之后的回滚语义**：回滚 = 回滚整个 release，
不再支持拨开关回退（此时旧投影路径已删）；PR-4a 阶段拨开关仍可完整回退。

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

## 13. 决议记录与开放问题

### 已决议

| 决议 | 内容 | 落点 |
|---|---|---|
| A-1 files API 软链 | 失败回退 exec 读（翻译层实现，SDK 契约失败不自动重试） | §10.3 验收实测 |
| B-1 降级粒度 | **会话级二值回退**：判定通过全会话软链，失败全会话走现行为；删逐技能混排 | §6.2/§6.4 |
| B-2 禁用语义 | **服务级（全局）禁用 = 新版本**（内容不进 store，恢复内容级隔离）；用户级禁用维持消费路径拦截 + 既有链删除 | §6.2-3/§4.3/§6.1 |
| B-3 包技能只读 | 写穿污染生命周期 = Pod 生命周期（同版本不自愈），沙箱重建后修改不生效（store 重建 + sha256 校验保证） | §9.2 |
| B-4 会话换版 | 不做会话钉版，接受 CURRENT 切换即生效与 ≤INTERVAL 错配窗口 | §6.2-1 |
| C-1 池管理 | 一期仅校验管理员预建池（`{k8s名}-pool` 唯一 + 服务绑定）；发布自动建池列后续 | §5.2 |
| C-2 手动通道 | 一期 = admin API；运维控制台表单列后续 | §5.2 |
| C-3 CI 必需性 | 一期非必需观察；转必需走分支保护加勾流程 | §11 |
| 池名派生/硬校验 | preset 校验 + §7.2 readlink 字符串比较 | §5.2/§7.2 |
| D-1 模板-镜像偏斜守卫 | preset args 内联 launcher 存在性检查，缺失时 exec 原命令——偏斜退化为无守护运行，防 warm pod crashloop | §5.2 |
| D-2 农场管辖与残留收敛 | manifest 内包技能路径农场全权收敛为软链（含降级 tar 残留）；真实文件保护仅限 manifest 判定 L4；前提「沙箱跨会话顺序复用」已代码核实（uk_session 并发 1:1） | §6.2 |
| D-3 bundle 服务端缓存 | 按 version 落盘（确定性打包 = 内容寻址；keep-2 LRU、`/tmp`、冷启重建）——关闭原开放问题 4 | §6.1 |
| D-4 version 端点缓存 | singleflight + ≤5s TTL（修订 G2 绝对化表述；新鲜度语义仍由守护轮询 INTERVAL 决定） | §6.1 |
| D-5 判定打点 SLO | Micrometer Counter `skills.farm.decision{result}`（actuator 已带 micrometer-core，零新增依赖）；降级同步 WARN | §7.2 |
| D-6 守护自愈 | launcher watchdog 30s 重启（sleep 兼任崩溃循环节流），修订原「无 supervise」口径 | §5.1/§4.5 |
| D-7 拉取退避 | bundle 拉取前 random(0,INTERVAL)，集群级铺满周期；新鲜度口径放宽为 ≤2×INTERVAL | §4.4/§7.1 |
| D-8 token 轮换契约 | 轮换 = Secret 更新 + 池 rollout 重启（K8s env 变更自然语义）；否决双 token 重叠窗口——关闭原开放问题 1 | §9.1 |
| D-9 多通道协议预留 | version 响应二期新增可选 `channels` 对象（通道独立 version/bundle），免 v2 路径迁移 | §4.3 |
| D-10 农场稳态短路 | 农场收敛不按每次触发执行（否决原 H2 口径——每次触发全量收敛 exec ≈1 次 exec RTT，农场稳态反而劣于 tar 稳态 ≈35ms，与 §8 内在冲突）：采用与 tar 路径 P0-1 同源的稳态短路（宿主指纹 = 包内源 stat + L4 revision；sandboxId 代际记账复用 scoped 指纹表），收敛触发面 = 新沙箱代 / 指纹变化 / exec 失败重试；**否决 per-call 容器侧标记兜底**（任何容器态观测即 1 次 exec RTT，与稳态目标互斥）；会话中途容器重启（同 sandboxId resume）盲点显式接受，与投影指纹缓存 §3.3 系统性盲点同姿态 | §6.2-4/§8/§10.2 |

### 开放（实施前/中定）

1. OSEP-0018 加固落地时的迁移方向预承诺（评审建议 init 容器形态优先，附 store 属主迁移 checklist）
2. 二期（store 扩容为服务级共享只读内容库，AGENTS.md/knowledge 入库、投影退役）启动判据；
   多通道协议形态见 D-9 预留

