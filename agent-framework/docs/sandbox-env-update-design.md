# 沙箱环境变量设置接口设计（`PUT /sandbox/env`，`EXECD_ENVS` 方案）

| 项 | 值 |
|----|----|
| 编制时点 | 2026-11-02 |
| 状态 | **已实施**（2026-11-02 实施、2026-11-03 修复旧 execd 兼容；2026-11-05 一致性修订 issue #230：写入调序 publish→writeManaged，见修订记录） |
| 需求 | 对话过程中，用户输入的敏感信息需要以环境变量形式进入沙箱；提供一个接口按 `userId + sessionId` 直接修改沙箱 env；沙箱不存在直接报错；后续 `doExec` 自动加载，**不增加每命令操作** |
| 现状核对 | SDK 能力基于 `com.alibaba.opensandbox:sandbox:1.0.18`（`javap` 反编译）；harness 行为基于 `agentscope-harness:2.0.3` 源码；execd `EXECD_ENVS` 行为基于 OpenSandbox 上游 `components/execd` 源码 |
| 关联文档 | [opensandbox-integration-plan.md](opensandbox-integration-plan.md)、[sandbox-skill-sync-optimization-design.md](../../docs/design/sandbox-skill-sync-optimization-design.md) |

> **方案结论**：execd 的 bootstrap 默认设置 `EXECD_ENVS=/opt/opensandbox/.env` 并 `touch` 该文件；`PUT /sandbox/env` 在沙箱内读取实际 `$EXECD_ENVS` 路径，直接写该文件；execd 每条命令自动重新读取。**`doExec` 零改动**，无进程内 profile、无宿主状态、无每命令新增操作；**默认不需要新增 agent-framework 配置**。
>
> **修订记录（2026-11-05，issue #230）**：修复「发布失败后重试静默 no-op」——原「先写 sidecar 后发布」在发布失败时 sidecar 已先行推进，调用方重试会被判定为「无变化」而跳过发布，生效文件永久缺键；调序为先发布生效文件、后写 sidecar，失败矩阵收敛（§6.3）。并发 PUT 的整键丢失为**已知限制**：接口定位为低频人工敏感输入，同一沙箱的并发提交为 last-writer-wins，调用方不应并发提交/自动重试同一沙箱的 env；本轮不引入分布式锁（§7.3）。

---

## 1. 背景与目标

用户在对话中输入敏感信息（第三方 API Key、访问令牌、租户凭据等）时，不希望这些值进入 Prompt/历史消息，但沙箱内执行的脚本又需要读取它们。当前沙箱环境变量只在创建沙箱时固定注入：

- `OpenSandboxClient.create()` 将 `OpenSandboxClientOptions.getEnvironment()` 传给 `CreateSandboxRequest.env`（`OpenSandboxClient.java:76-86`）；
- `AgentScopeConfig` 只装配了 `EXECD_API_GRACE_SHUTDOWN` 一个平台键（`AgentScopeConfig.java:63-75`）；
- `resume()` 不会重新应用 env；
- 没有运行期设置入口。

**目标**：新增 `PUT /sandbox/env`，调用方传入 `userId`、`sessionId` 和一组环境变量键值对：

1. 通过既有沙箱状态（`SandboxState`）定位当前用户的沙箱容器；
2. 在沙箱内读取 `$EXECD_ENVS` 实际路径（execd bootstrap 默认 `/opt/opensandbox/.env`）；
3. 直接调用沙箱文件接口，把 env 写入该文件；
4. 后续命令由 execd 自动加载，**`doExec` 不做任何改动**；
5. **沙箱不存在（状态缺失/容器已销毁）→ 直接返回错误**，不做 pending、不做进程内兜底。

**非目标**：

- 不做进程内 env profile、不做 pending 登记、不做 LRU/TTL cache；
- 不做 GET/DELETE、不做全量替换、不做 OAF 包声明；
- 不修改 `entrypoint` 启动的常驻进程、更新前已启动的后台进程；
- 不直接修改容器进程的 env（OpenSandbox Server 1.0.18 无此 API）；通过 `EXECD_ENVS` 文件自动加载实现等价效果；
- 不做宿主侧持久化（不写 DB/Redis/PVC）。

## 2. 关键决议

| # | 决议 | 说明 |
|---|------|------|
| D1 | **接口**：`PUT /sandbox/env` | 只此一个接口；不做 GET/DELETE |
| D2 | **入参**：`userId` + `sessionId` + env kv 列表 | 二者均必填；用于定位沙箱与隔离键生成 |
| D3 | **生效机制**：`EXECD_ENVS` 文件 | execd bootstrap 默认设置并 touch；接口动态读取实际路径后写文件；execd 每条命令自动加载 |
| D4 | **`doExec` 零改动** | 不加 source 前缀、不读文件、不解析 JSON、不调文件 API |
| D5 | **持久化**：只在容器 `/tmp` 文件 | 不落库、不落盘（宿主侧）；容器销毁即失效 |
| D6 | **沙箱不存在**：直接报错 | 状态缺失/connect 404 → 404；不做 pending/自动创建 |
| D7 | **值格式与约束**：裸 `KEY=value`（不包引号），禁止 `$`、换行/回车、首尾空白、首字符引号 | 已发布 execd 不解析引号（`KEY='v'` → `'v'`）；两版裸值都走 `os.ExpandEnv` 且无转义语法；首字符引号在新版会被当引号值、旧版按字面 → 语义分叉；命中 400 |
| D8 | **鉴权**：沿用集群内网信任 | 只允许平台后端/可信调用方；不新增 token |

## 3. 现状核对与关键依据

### 3.1 当前 env 注入链路

| 环节 | 位置 |
|------|------|
| 装配 sandbox 基础 env（当前只有 `EXECD_API_GRACE_SHUTDOWN`） | `AgentScopeConfig.java:63-75` |
| 传给 SDK options | `OpenSandboxFilesystemSpec.java:180-191` |
| 创建沙箱时写 `CreateSandboxRequest.env` | `OpenSandboxClient.java:76-86` |
| Agent Shell 命令入口 | `OpenSandbox.doExec`（`OpenSandbox.java:249-266`） |

### 3.2 execd `EXECD_ENVS` 能力（上游 main 分支）

| # | 结论 | 依据 |
|---|------|------|
| E0 | execd bootstrap 在 `EXECD_ENVS` 未设置时默认使用 `/opt/opensandbox/.env`，并 `mkdir -p` + `touch` 后 `export EXECD_ENVS` | `components/execd/bootstrap.sh:492-503` |
| E1 | execd 每条命令执行前都会调用 `UserEnvOverlay`，其中 `loadExtraEnvFromFile()` 重新读取 `EXECD_ENVS` 指向的文件 | `components/execd/pkg/runtime/env.go`：`UserEnvOverlay` 内调用 `loadExtraEnvFromFile` |
| E2 | 合并顺序：沙箱 binding env `<` `EXECD_ENVS` 文件 `<` 请求 envs；文件可覆盖容器内的同名变量 | 同上 `UserEnvOverlay` 层叠逻辑 |
| E3 | 文件格式为 `KEY=VALUE`。**版本分叉**：2026-09-17 commit `017952fc` 起（`release-1.1.0+`/main）支持 dotenv 引号（单引号字面量、双引号转义、多行）；**此前构建（含 server/v0.2.x 发布线）不解析引号**，`KEY='v'` 原样读成 `'v'` | `parseEnvFile`/`parseQuotedEntry`；旧版 `loadExtraEnvFromFile` |
| E4 | 文件不存在时 execd 每条命令打 WARN 并跳过 | `loadExtraEnvFromFile` 的 `os.ReadFile` 失败分支 |
| E5 | OpenSandbox Server 1.0.18 没有修改运行中容器 env 的 API | `SandboxesApi` 反编译无 env update/patch；`Sandbox` 类无 env setter |

> E1/E2 需在实施前用**实际部署的 execd 镜像**验证（§8.4）；上游 main 已确认。
> 本方案**不修改 execd 代码，也不修改 `OpenSandbox.doExec`**：execd 的 `UserEnvOverlay`/`loadExtraEnvFromFile` 已在命令执行路径中，`bootstrap.sh` 已负责设置/创建 `EXECD_ENVS`；框架只需新增运行期写文件的接口。

### 3.3 为什么不用 `POST /internal/init`

execd 的 `/internal/init` 支持 `PreserveRuntimeState=true` 替换 binding env，但：

- 它是内部控制面协议，无兼容承诺；
- 不传 `accessTokenHash` 会把 execd API 鉴权清空（`HasAccessToken=false`），而 agent-framework 不知道当前 token hash；
- 因此不能从业务面安全调用，**不采用**。

## 4. 接口定义

### 4.1 路径与方法

```http
PUT /sandbox/env
Content-Type: application/json
```

只此一个接口；不提供 GET/DELETE。

### 4.2 请求体

```json
{
  "userId": "u-1001",
  "sessionId": "sess-abc",
  "env": {
    "THIRD_PARTY_API_KEY": "sk-xxxx",
    "API_BASE": "https://api.example.com"
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `userId` | string | 是 | 规范业务用户 id（`session_user.user_id`），非空、≤256 字符 |
| `sessionId` | string | 是 | 规范会话 id（前端/A2A 调用方 sid），非空、≤256 字符 |
| `env` | object | 是 | 环境变量键值对集合；至少 1 个键；键名需符合 `^[A-Za-z_][A-Za-z0-9_]{0,127}$` |

语义：**合并式更新（upsert）**。

- 读取沙箱内已有 `.json`，合并请求 env（同名覆盖），再写回；
- 请求中未出现的键：**保持原值、继续生效**——包括之前通过本接口设置的键，以及镜像脚本写入的 `PATH`/`JAVA_HOME`/`GOROOT` 等行；
- 只对请求中出现的键追加新行（同名键以最后一次出现的为准），不删除、不覆盖其他行；
- 不做删除；沙箱销毁后文件随之消失。

### 4.3 响应

成功（200）：

```json
{
  "userId": "u-1001",
  "sessionId": "sess-abc",
  "sandboxId": "sbx-xxxxxxxx",
  "updatedKeys": ["THIRD_PARTY_API_KEY", "API_BASE"],
  "keys": ["THIRD_PARTY_API_KEY", "API_BASE"],
  "count": 2,
  "applied": "next_exec"
}
```

- `sandboxId`：本次定位到的沙箱容器 id；
- `updatedKeys`：本次实际写入的键；
- `keys`：沙箱 env 文件当前全部键名（不含值）；
- `count`：当前键数量；
- `applied`：固定 `next_exec`；
- **响应永不返回值**；日志也不打印值。

### 4.4 校验与错误

| 项 | 规则 | 错误码 / HTTP |
|----|------|--------------|
| `userId` | 非空、≤256、无控制字符 | `invalid_user_id` / 400 |
| `sessionId` | 非空、≤256、无控制字符 | `invalid_session_id` / 400 |
| `env` | 非空对象 | `invalid_env` / 400 |
| 键名 | `^[A-Za-z_][A-Za-z0-9_]{0,127}$` | `invalid_env_key` / 400 |
| 值 | 字符串、≤8192 字节、无 `NUL` | `invalid_env_value` / 400 |
| 值含 `$` / 首字符引号 | 旧/新版 execd 语义不一致或无法无损表达，禁止 | `unsupported_env_value` / 400 |
| 值含换行/回车、首尾空白 | 行式格式截断 / TrimSpace 口径不一致，禁止 | `invalid_env_value` / 400 |
| 合并后键数 | ≤64 | `env_limit_exceeded` / 400 |
| 合并后总字节 | key+value UTF-8 合计 ≤32 KiB | `env_limit_exceeded` / 400 |
| 保留键 | 禁止 `EXECD_API_GRACE_SHUTDOWN`、`EXECD_ENVS` | `reserved_env_key` / 400 |
| 沙箱不存在 | 状态缺失 / 容器已销毁（connect 404） | `sandbox_not_found` / 404 |
| 沙箱不可达 | 连接失败/服务端错误 | `sandbox_unavailable` / 502 |
| 沙箱未启用 | `SandboxRuntime.enabled()==false` | `sandbox_disabled` / 409 |
| 隔离范围不支持 | 配置为 `AGENT`/`GLOBAL` | `unsupported_isolation_scope` / 409 |
| EXECD_ENVS 不可用 | 沙箱内 `printf %s "$EXECD_ENVS"` 为空/非法 | `execd_env_file_unsupported` / 409 |
| 状态读取失败 | DB/序列化异常 | `state_unavailable` / 500 |

错误响应：

```json
{"error": "sandbox_not_found", "message": "no live sandbox for this user/session"}
```

- 校验失败 **不产生部分写入**：先校验、再合并、最后写文件；
- 未预期异常 500，不返回堆栈。

## 5. 沙箱定位与隔离键

### 5.1 定位链路

接口不依赖宿主进程内的沙箱实例，直接走既有共享状态：

```
PUT /sandbox/env
  │ ① SandboxRuntime.enabled() 检查（未启用 → 409）
  │ ② 读取配置的 IsolationScope，生成候选 SandboxIsolationKey（§5.2）
  │ ③ SessionSandboxStateStore.load(candidateKey) → SandboxState JSON
  │ ④ 从 JSON 取 sandboxId
  │ ⑤ Sandbox.connector().sandboxId(id).connectionConfig(...).skipHealthCheck(true).connect()
  │      connect 失败（404）→ sandbox_not_found
  │ ⑥ 沙箱内执行 `printf %s "$EXECD_ENVS"` → 实际 env 文件路径
  │      空/非法 → 409 execd_env_file_unsupported
  ▼
  沙箱容器（跨 Pod 可见：状态在 MySQL，文件在容器内）
```

- 状态由 harness 在每次 turn 结束时 `SandboxManager.persistState` 写入（`SandboxLifecycleMiddleware.releaseForCall`），因此**至少完成过一次 turn** 才能定位到沙箱；
- 接口可以从任意副本执行：状态在 MySQL，容器在 OpenSandbox Server，文件写在容器内。

### 5.2 按配置生成隔离键（固定映射）

沙箱槽位键必须由**配置的隔离范围**决定，不能硬编码：

- 当前 `AgentScopeConfig` 固定装配 `.isolationScope(IsolationScope.USER)`（`AgentScopeConfig.java:63-110`）；
- 接口从 `OpenSandboxFilesystemSpec.getIsolationScope()` 读取该配置（沙箱未启用时为 null → 409）；`scope` 为 null 时按 `USER` 处理；
- 用 `SandboxIsolationKey.resolve(scope, syntheticCtx, agentId)` 生成槽位键，与 harness 使用**同一个算法**，不手拼 `sandbox/user/...` 字符串。

| 配置 scope | harness 取键规则 | 接口候选键（按顺序） |
|-----------|-----------------|---------------------|
| `USER`（当前固定） | `ctx.userId`；为空才降级 `SESSION(ctx.sessionId)` | ① Channel：`USER(sessionId)`（Channel 的 `ctx.userId` = 网关 peer = 前端 sid）<br>② A2A/direct：`USER(userId)` |
| `SESSION` | `ctx.sessionId` | `SESSION(sessionId)` |
| `AGENT` | `agentId` | 不支持 → 409 `unsupported_isolation_scope` |
| `GLOBAL` | `__global__` | 不支持 → 409 `unsupported_isolation_scope` |

- 候选键统一用 `SandboxIsolationKey.resolve(scope, RuntimeContext.builder().userId(...).sessionId(...).build(), agentId)` 生成；
- `USER` 下两个候选是固定链路映射：Channel 的 `ctx.userId` 就是前端 sid，A2A 的 `ctx.userId` 就是真实用户；
- 按顺序 `load`，取第一个有状态且 `connect` 成功的；都失败 → 404；
- `agentId` 取 `OafConfig.name()`（与 `HarnessAgentFactory` 的 `.name(oafConfig.name())` 一致）。

## 6. 沙箱内 env 文件与生效机制

### 6.1 文件位置

```
<EXECD_ENVS>          # execd 实际路径；bootstrap 默认 /opt/opensandbox/.env
/tmp/.agent-env.json  # 合并用源数据（仅接口读写，固定路径）
```

- 实际 env 文件路径由沙箱内 `$EXECD_ENVS` 决定，接口不硬编码；默认 `/opt/opensandbox/.env`；
- 路径在沙箱内，不进入 workspace 投影/快照/PVC 同步，容器销毁即消失；
- 一个沙箱一个 `EXECD_ENVS` 文件（沙箱级）；Channel 沙箱槽位本身是会话级（`ctx.userId` = 前端 sid），因此等价于会话隔离；A2A 同用户多会话共享同一沙箱时 env 共享；
- `.json` 固定在 `/tmp`，仅记录接口管理的键，不参与 execd 读取。

### 6.2 配置：默认不需要新增，bootstrap 已提供

execd 的 `bootstrap.sh` 在 `EXECD_ENVS` 未设置时默认使用 `/opt/opensandbox/.env`，并 `mkdir -p` + `touch` + `export`（`components/execd/bootstrap.sh:492-503`）。因此：

- **默认无需修改 agent-framework 配置**；接口在沙箱内读取 `$EXECD_ENVS` 得到实际路径后直接写；
- 代码解释器镜像的脚本本来就会向 `$EXECD_ENVS` 追加 `PATH`/`JAVA_HOME`/`GOROOT`（`code-interpreter.sh:154-156`、`code-interpreter-env.sh:37-42`），说明当前镜像是按该机制构建的；
- 如需自定义路径，可在 `AgentScopeConfig` 的 `.environment(...)` 中显式加入 `"EXECD_ENVS", "<绝对路径>"`（可选；默认不设置，沿用 bootstrap 默认），`bootstrap.sh` 会保留已设置的值并 touch；
- **存量沙箱**：若 `$EXECD_ENVS` 为空（旧 bootstrap/未启用），接口返回 409 `execd_env_file_unsupported`；需升级/重建沙箱；
- 该 env 是**沙箱容器**的，不是 agent-framework Pod 的。

### 6.3 写入流程（必须保留镜像写入的 PATH 等行）

`gaoyue1989/code-interpreter-oa:v20260914` 的入口脚本会在启动时向 `$EXECD_ENVS` **追加** `PATH` / `JAVA_HOME` / `GOROOT` 等语言环境行（`code-interpreter.sh:154-156`、`code-interpreter-env.sh:37-42`）。因此接口**不能整文件重写**，必须保留这些行：

```
① 读取已有 <envPath> 原文（files().readFile；404/空 → ""）
② 读取 /tmp/.agent-env.json，合并请求 env（同名覆盖）
③ 校验合并结果（键名/保留键/`$`/换行/首尾空白/首字符引号/64 键/32KiB）
④ 生成变更行：每行裸 `KEY=value`（不包引号，跨 execd 版本兼容）
⑤ 追加到原文末尾（保证原文件以换行结尾）
⑥ 原子发布（先发布生效文件）：
   files().write(<envPath>.tmp.<uuid>)                 # 原文 + 新行
   commands().run("mv -f <tmp> <envPath>")
⑦ files().write(/tmp/.agent-env.json)                 # 后写 sidecar（仅记录接口管理的键）
```

- **写序（issue #230 修订，原 ④ 与 ⑦ 对调）**：变更判定只比对 sidecar（旧值 equals 新值），若 sidecar 先写而发布失败，调用方按本文档重试时判定为「无变化」静默 no-op，生效文件永久缺键。调序后失败矩阵收敛：
  - 发布失败 → sidecar 未推进 → 重试时变更判定重新命中、重新发布；
  - 发布成功而 sidecar 写失败 → 重试重复追加一行 `K=V`（execd 顺序解析取最后出现的行，幂等收敛）；
  - 无变化的 PUT（updatedKeys 为空）仍只写 sidecar、跳过发布，语义不变。

- **保留镜像行**：PATH/JAVA_HOME/GOROOT 等由镜像脚本追加，接口只追加自己的键，绝不覆盖；
- **保留未修改的键**：本次 PUT 未出现的键（之前接口设置的 + 镜像写入的 + 容器原有的）都不动；execd 按文件顺序取最后一次出现的值，因此旧键继续生效；
- 同名键重复出现时，execd 顺序解析、**后面的行覆盖前面的行**，所以更新一个键 = 追加一行新值；未更新的键不受影响；
- 文件会随 PUT 次数增长；沙箱生命周期有限（默认 60 分钟），增长可接受；后续可做压缩；
- 写入前完成全部校验，失败不产生部分写入；
- 值按裸 `KEY=value` 渲染（不包裹引号）：旧版 execd 不剥引号、新版支持 dotenv 引号，裸值在两者下语义一致；值内禁止 `$`（两版裸值都做 `os.ExpandEnv` 且无转义语法）、换行/回车（行式格式会截断）、首尾空白（两版 TrimSpace 口径不一）、首字符引号（新版按引号值解析、旧版按字面保留）；
- 通过临时文件 + `mv -f` 原子替换：execd 读取时要么看到旧文件、要么看到新文件；
- `.json` 只被接口读取，可直接覆盖；发布（生效文件写）失败返回 502，调用方重试收敛——写序修订（issue #230）后该承诺成立：发布失败时 sidecar 未推进，重试重新检出变化；发布成功而 sidecar 写失败亦返回 502，重试重复追加一行（execd 取最后行，幂等）；
- `mode(600)` 降低容器内其他进程读取面（Agent 本身仍可通过命令读取，属功能语义）。

### 6.4 自动加载（`doExec` 零改动）

execd 每条命令执行前由 `UserEnvOverlay` 重新读取 `EXECD_ENVS` 文件（E1/E2），因此：

- `OpenSandbox.doExec` 保持现有实现：`osbSandbox.commands().run(command)`，**不加任何前缀/读取/解析**；
- 接口写入后，下一条命令自动看到新值；
- **`EXECD_ENVS` 只保存路径，不在沙箱启动时快照文件内容**：沙箱启动后接口改写该文件，后续命令仍会重新读取并生效（前提是部署版本按 E1 每条命令重读）；不需要重启沙箱/execd；
- 文件不存在 → execd 打 WARN 并跳过（E4）；但 execd `bootstrap.sh` 已 `touch` 该文件（E0），因此正常路径下不会出现该 WARN；
- **无需修改 `OpenSandbox.doSetupWorkspace` / `doExec`**；框架侧只需要新增接口与写文件逻辑。

### 6.5 生效范围

| 场景 | 是否生效 |
|------|----------|
| 该沙箱的下一条 Shell/脚本命令 | ✅ execd 自动加载 |
| 本次 PUT 未出现的键（之前接口设置/镜像写入/容器原有） | ✅ 保持原值、继续生效 |
| 同沙箱内其他会话（A2A 共享容器） | ⚠️ 共享同一 env 文件 |
| 更新时正在执行中的命令 | ❌ 旧值 |
| 更新前已启动的后台进程 | ❌ 环境在进程启动时固化 |
| 容器 `entrypoint` / 常驻进程 | ❌ 不重新注入 |
| 沙箱销毁/重建 | ❌ 文件随容器消失；新沙箱由 bootstrap 重新创建默认文件，需重新调用本接口 |
| 跨 Pod 副本 | ✅ 状态在 MySQL、文件在容器，任意副本可写/可读 |

## 7. 实现设计

### 7.1 新增类

| 类 | 职责 |
|----|------|
| `sandbox/opensandbox/SandboxEnvFile` | `discoverEnvPath(osbSandbox)`（`printf %s "$EXECD_ENVS"`）；`mergeAndWrite(osbSandbox, envPath, env)` 读 `/tmp/.agent-env.json`→合并→校验→读 `<envPath>` 原文→追加变更行→临时文件+`mv -f` 原子发布→写 `.json`（**先发布后写 sidecar，issue #230；保留镜像写入的 PATH/JAVA_HOME/GOROOT 行**）；`validate(Map)` |
| `controller/SandboxEnvController` | `PUT /sandbox/env`：参数校验、沙箱定位、`EXECD_ENVS` 路径发现/校验、错误映射、响应 |
| `sandbox/opensandbox/SandboxLocator` | 读取配置 `IsolationScope` 生成候选 `SandboxIsolationKey`；`SessionSandboxStateStore.load`；从 JSON 取 `sandboxId`；`Sandbox.connector().connect()`；错误分类（404/409/502） |

### 7.2 装配

| 依赖 | 来源 |
|------|------|
| `SessionSandboxStateStore` | `new SessionSandboxStateStore(distributedStore.agentStateStore(), oafConfig.name())`，在 `AgentScopeConfig` 建 Bean |
| `DistributedStore` | 既有 Bean（`agent_state` 表） |
| `OafConfig` | 既有 Bean（取 agent name） |
| `SandboxConfig` / `SandboxRuntime` | 既有 Bean（serverUrl/apiKey、enabled 检查） |
| `OpenSandboxFilesystemSpec` | 既有 Bean（`getIsolationScope()` 作为候选键生成的唯一配置来源；未启用时为 null） |
| `SandboxEnvFile` | Controller 注入（写文件） |
| `SandboxLocator` | Controller 注入 |

`OpenSandbox`：

- **不需要改构造器、`doSetupWorkspace` 或 `doExec`**；接口通过 `Sandbox.connector()` 直接操作沙箱文件，不经过 `OpenSandbox` 实例。

### 7.3 并发

- **已知限制（issue #230，接受风险）**：同一沙箱的并发 PUT 无互斥，为 last-writer-wins——两个 PUT 各自基于「读旧原文 + 旧 sidecar」快照合并后 `mv` 发布，后 `mv` 者会覆盖先写入方的键（整键丢失）。接口定位为**低频、人工输入敏感信息**：沙箱默认按会话隔离（`SANDBOX_SESSION_AGENT_ISOLATION`）、同一表单重复提交的键集相同（后写覆盖旧值可接受），丢失可重新输入；调用方不应并发提交或自动重试同一沙箱的 env。若未来需要硬保证，再引入分布式锁（本轮明确不做）。
- execd 读 `<envPath>` 与 PUT 写并发：`mv -f` 原子替换，读侧要么看到旧文件、要么看到新文件；
- 命令执行期间文件被替换：当前命令已加载旧值，下一条命令使用新值。

### 7.4 日志

- 写入成功：`INFO [sandbox-env] updated user=u-1001 session=sess-abc sandbox=sbx-xxx keys=2 count=2`（不打印值）；
- 定位失败：`WARN [sandbox-env] sandbox not found user=... session=...`；
- 校验失败：`WARN [sandbox-env] rejected user=... session=... error=invalid_env_key`（不回显值）；
- `EXECD_ENVS` 未预置：`WARN [sandbox-env] execd env file unsupported sandbox=...`。

## 8. 安全与边界

### 8.1 调用方与鉴权

- 接口承载敏感值，**只允许平台后端/可信调用方**调用；
- 不建议直接暴露给浏览器或公网；如经网关暴露，必须由网关完成用户认证，并校验 body `userId` 与实际登录用户一致（本版不新增独立 token）；
- 响应不返回值，降低泄漏面。

### 8.2 值的安全

- 值只写到沙箱容器内的 `EXECD_ENVS` 文件，宿主侧不落盘、不落库、不打日志、不出现在响应；
- 沙箱内 Agent 及其命令本来就能读取注入的 env——这是功能语义；不要把“仅平台可见”的凭据放进来；
- 保留键禁止覆盖，防止改坏 execd 自身行为；
- 键名严格正则，值禁止 NUL；裸值渲染下禁止 `$`（`os.ExpandEnv` 展开且无转义）、换行/回车、首尾空白、首字符引号；值内单引号（非首字符）按字面保留；不经过 shell 拼接，无命令注入面；
- 建议平台在把用户消息发给模型前剥离敏感字段，仅通过本接口下发，避免敏感值进入 Prompt/历史。

### 8.3 生命周期

- env 文件由 execd bootstrap 在容器内创建，容器销毁/重建即消失；
- 接口不做 pending、不做宿主缓存：沙箱不存在直接 404；
- 同一容器内多次 PUT 累加生效；沙箱换代后由新 bootstrap 重建文件，需重新调用；
- 沙箱级单文件：Channel 沙箱=会话，天然隔离；A2A 同用户多会话共享同一沙箱时 env 共享；需要严格会话隔离时需改隔离范围或后续扩展；
- 不进入 workspace 快照/PVC 同步（默认 `/opt/opensandbox/.env` 不在 workspace 根下）。

### 8.4 前置条件与验证

- 沙箱必须已经创建，且 harness 已将沙箱状态持久化（至少完成过一次 turn 的 `persistState`）；
- 首次 turn 开始前（尚无沙箱）调用会返回 404；
- 沙箱 TTL 到期被销毁后，状态仍可能指向旧 id → `connect` 404 → `sandbox_not_found`；
- **部署验证**：实施前用实际 execd 镜像验证「沙箱内 `$EXECD_ENVS` 非空 → 接口写文件 → `echo $VAR` 能读到」；上游 main 已确认 bootstrap 默认设置与 `UserEnvOverlay` 每条命令调用 `loadExtraEnvFromFile`，若部署版本不支持，回退方案见 §10。

## 9. 测试计划

### 9.1 单元测试

| 测试类 | 覆盖 |
|--------|------|
| `SandboxEnvFileTest` | 路径发现（非空/空白/非法/命令失败 502）；`.json` 与原文缺失时从空开始；追加变更键且**保留镜像写入的 `PATH=...`/`JAVA_HOME=...` 行**；同名键追加新行覆盖旧值；无变化跳过发布；`.json` 损坏 fail-soft；校验拒绝：保留键（`EXECD_API_GRACE_SHUTDOWN`/`EXECD_ENVS`）、非法键名、值含 `$`/换行/回车/首尾空白/首字符引号、`NUL` 值、单值超 8KiB、超 64 键、合并后总量超 32KiB；值内单引号（非首字符）允许；发布：临时文件+`mv -f` 原子写入（mv 失败 502）、原文读取失败 502；行格式为裸 `KEY=value`（跨 execd 版本兼容）由 `renderLines`/`appendLines` 在合并用例内断言；**写批次顺序（issue #230）**：第一批=发布临时文件、第二批=sidecar；**失败收敛两用例**：mv 失败 → 502 且仅 1 次写（sidecar 未推进）→ 重试重新检出变化收敛；发布成功 + sidecar 写失败 → 502 → 重试追加第二行 K=V（execd 取最后行幂等） |
| `SandboxLocatorTest` | 按配置 scope 生成候选：`USER` → 先 `USER(sessionId)` 后 `USER(userId)` 回退；`SESSION` → `SESSION(sessionId)`；`AGENT`/`GLOBAL` → 409；全候选未命中 → 404；connect 失败落到下一候选；状态加载 IO 失败 → 500；状态记录无 `sandboxId` 时跳过 |
| `SandboxEnvControllerTest` | 200/400/404/409/502；字段缺失；响应不含值；`sandbox_disabled` / `unsupported_isolation_scope` / `execd_env_file_unsupported` / `unsupported_env_value`；`LocatedSandbox` 句柄 close |
| 回归保障 | `doExec` 零改动 → 无需专门测试；既有 `OpenSandboxTest` / `SandboxUserKeyMiddlewareTest` / mock e2e X1-X20 全组回归即覆盖 |

### 9.2 E2E（mock sandbox）

1. 扩展 `e2e/mock/sandbox-server.mjs`：
   - `POST /v1/sandboxes` 记录 create env；未提供 `EXECD_ENVS` 时按 bootstrap 规则默认 `/opt/opensandbox/.env` 并 `touch`（模拟 E0）；
   - `runCommand` 每次执行前读取 `env.EXECD_ENVS` 指向的文件（映射到 `sb.root/tmp` 或 `sb.root/opt/opensandbox/.env`），按**旧版 execd** 语义解析（`SplitN("=",2)` + `os.ExpandEnv`、**不解析引号**）并合并进 `spawn` 的 env，模拟已发布 execd 的 `UserEnvOverlay`——若接口回退到引号包裹，X-ENV 字节断言立即失败；
   - `files/upload`/`files/download` 已支持 absolute 路径映射，可承载 `.json`/`EXECD_ENVS` 文件写入与读取；
   - `stats.commands` 记录 env 键数量（不记录值）。
2. 新增 X 组用例：
   - 沙箱启动时 mock 向默认 `EXECD_ENVS` 预置 `PATH=/usr/local/bin:/usr/bin:/bin`（模拟镜像脚本）→ `PUT /sandbox/env` 后该行仍在，命令 `echo $PATH` 不受影响；
   - 完成一轮对话（沙箱状态落库）→ `PUT /sandbox/env` 设置 `SANDBOX_E2E_TOKEN=one` → 下一轮执行 `echo $SANDBOX_E2E_TOKEN` → 断言 `one`；
   - 再 PUT `SANDBOX_E2E_TOKEN=two` → 下一条命令断言 `two`（合并更新、`doExec` 零改动）；
   - 未提及的键保留（先设 A、B，再只 PUT A，命令仍能读到 B）；
   - 值含 `'` → 400 `unsupported_env_value`；
   - 沙箱不存在（未开始会话/已 destroy）→ 404；
   - 模拟 `$EXECD_ENVS` 为空（mock 不设置默认值）→ 409 `execd_env_file_unsupported`；
   - 非法键/保留键 → 400，且命令侧不受影响；
   - **X-ENV-8 发布失败收敛（issue #230）**：mock 故障注入 `POST /admin/fault {mvFail:true}`（发布临时文件写入 500）→ PUT 得 502 `sandbox_unavailable` 且 `stats.faultHits` 命中 → 清除注入 → 重试 PUT 得 200 且 `updatedKeys` 重新检出变更（非静默 no-op）→ 命令读到值（黑盒验证 §6.3 调序后的重试承诺）。

### 9.3 回归

- `mvn test` 全量（基线 1573 用例 / 0 失败 / 5 跳过）；
- e2e 核心/多副本/沙箱/协议多副本四组必需门禁；
- 未启用沙箱的实例启动/对话行为不变；`doExec` 无改动，现有沙箱用例不受影响。

## 10. 回退方案（仅当部署版本不支持 EXECD_ENVS 重读）

若实际 execd 镜像不是「每条命令重读」：

1. 优先升级 execd/OpenSandbox 镜像；
2. 仍不可行时，再实现 **source 前缀模式**：`doExec` 在命令前拼 `if [ -f /tmp/.agent-env-<hash>.sh ]; then . ...; fi; `，接口写 per-session `.sh`；该模式支持任意值，但每条命令多一次本地 source。作为后续兜底，不进入本版。

## 11. 验收清单

- [ ] `PUT /sandbox/env` 按 §4 校验与响应，合并式更新，不返回值；
- [ ] 候选键由配置的 `IsolationScope` 生成（当前 `USER` → `USER(sessionId)`/`USER(userId)`；`SESSION` → `SESSION(sessionId)`）；不支持的 scope → 409；
- [ ] 通过 `SessionSandboxStateStore` 定位沙箱；状态缺失/容器销毁 → 404；
- [ ] 沙箱内 `$EXECD_ENVS` 非空时接口使用实际路径写入；为空 → 409 `execd_env_file_unsupported`；
- [ ] 接口写 `<EXECD_ENVS>` + `/tmp/.agent-env.json`；镜像脚本写入的 `PATH`/`JAVA_HOME`/`GOROOT` 行原样保留；
- [ ] 本次 PUT 未出现的键（之前接口设置/镜像写入/容器原有）保持原值、继续生效；
- [ ] 下一条命令由 execd 自动加载；
- [ ] `doExec` 无改动（命令串与今天一致）；无每命令文件读取/解析/API 调用；`OpenSandbox` 构造器/`doSetupWorkspace` 无改动；
- [ ] 值含 `$`/换行/首尾空白/首字符引号 → 400；值内单引号（非首字符）允许；保留键/非法键/超限校验生效，失败无部分写入；
- [ ] 存量沙箱 `$EXECD_ENVS` 为空 → 409 `execd_env_file_unsupported`；
- [ ] 跨 Pod 可写/可读（状态 MySQL + 文件在容器）；
- [ ] 日志和响应不出现 env 值；
- [ ] mock e2e 覆盖“设置 → 下一条命令生效 → 合并更新 → 沙箱不存在 404 → 未预置 409”；
- [ ] 全量单测与四组必需 e2e 门禁通过。
