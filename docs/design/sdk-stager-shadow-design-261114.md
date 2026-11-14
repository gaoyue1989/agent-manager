# SDK MarketplaceStager 类遮蔽 + 端点 manifest 审计设计 - 261114

> 状态：**审计通过（可行性探针实证），随实现 PR 落地**
> 前置：[sandbox-skills-syncd-design-261112.md](sandbox-skills-syncd-design-261112.md)（PR-4b 已合并 #339）、
> [sandbox-skills-syncd-design-261112 §1.2-1 宿主乘法项残留](sandbox-skills-syncd-design-261112.md)
> 探针分支结论存档：`feat/verify-isolation-global`（GLOBAL 方案已否决并删除，见 §2.3）

## 1. 问题与目标

### 1.1 残留问题（PR-4b 后唯一未消除面）

SDK `HarnessSkillMiddleware` 每轮推理经 `MarketplaceStager.stage()` 把包技能物化到宿主
`<wsRoot>/.skills-cache/<userId>/oaf-package/<skill>/`——每用户一份（66MB/用户 × 副本数，
SSOT §1.2-1 乘法项）。PR-4b 消除了 sandbox 侧乘法（tar 投放退役），宿主侧这份写盘在沙箱
模式下**内容已无任何消费方**（farm 只读目录结构做 manifest，容器内容由守护下发），仅剩：

1. **farm manifest**：`linkCandidates` 遍历宿主 scope 目录取候选名单
2. **SDK 渲染记账**：`StageResult` → `ShellPathPolicy.resolve` 产出 `<available_skills>` 路径

### 1.2 目标

沙箱模式下消除宿主包技能写盘（内容物化归零）+ farm manifest 源切端点，**不依赖上游 SDK
发版**；非沙箱模式 staging 是真实消费方，不在改动面。

### 1.3 非目标

- 不动 L4 用户技能链路（WorkspaceSkillRepository/WorkspaceNative 原生路径）
- 不改 skills-syncd 守护协议（version 响应加字段，D-9 预留兼容）
- 不处理非沙箱模式的 staging（真实消费，保留）

## 2. 方案决策记录

### 2.1 被否决路径（实证）

| 路径 | 否决证据 |
|---|---|
| builder 注入自定义 stager | `HarnessAgent$Builder` 字节码硬编码 `new MarketplaceStager(workspaceRoot)`，无注入口 |
| post-build 反射改写 | `ReActAgent.middlewares` 私有无访问器；违反项目「禁止 hack」红线 |
| 自建 middleware 双注册 | `composeSkillRepositories` 恒含 workspace 层 repo → 内部 middleware 必然构造，双实例冲突 |
| **isolationScope=GLOBAL（公开面直达）** | **真实集群探针实证隔离击穿**：同一 isolationScope 喂 `toNamespaceFactory()` → SDK 记忆/状态命名空间拍平 → 两用户 MEMORY.md 双向串写（U1 写 ALPHA-7741、U2 写 BETA-9032，双方新会话召回互见对方代号）。探针分支已删 |

### 2.2 选定路径：classpath 类遮蔽（同包同类名，非反射非字节码注入）

Spring Boot `LaunchedURLClassLoader` 加载顺序规范保证 `BOOT-INF/classes/` 优先于
`BOOT-INF/lib/*.jar`——在 `agent-framework/src/main/java/io/agentscope/harness/agent/skill/runtime/`
放置同全限定名 `MarketplaceStager`，运行期加载我们的版本，Builder 硬编码构造照常拿到遮蔽类
（45KB 的 Builder 零改动）。这不是反射也不是运行时字节码改写，是**显式受控的单元级二进制分叉**，
代价与维护协议见 §5。

### 2.3 可行性探针结论（javap 实证，2026-11-14）

| 探针 | 结论 |
|---|---|
| ① StageResult 家族 | `Cached(String scopeSegment, String sourceNamespace, String skillName)` 公开 record——**纯数据可直接合成**；`None`/`WorkspaceNative` 无参 record；`RepoBound(AgentSkill, AgentSkillRepository)` 公开 record |
| ② `ShellPathPolicy.resolve` 分支 | `Cached` → `joinCache(scope, ns, name)`（= farm 建链路径同构）；`None` → **null（技能不渲染——与 PR-4b「降级=无包技能投放」自动衔接）**；`WorkspaceNative` → `/workspace/skills/<name>`（L4 原生路径不动） |
| ③ 中间件对 stager 的公开契约 | `stage(...)` 两个重载 + 构造器 + 静态 `resolveSourceNamespaces`（middleware 构造期调用）+ 常量 `CACHE_DIR` 等——合成类只需覆盖此公开面 |

### 2.4 收益

- 消除每用户 66MB × 副本数 宿主写盘（磁盘乘法项归零）
- 消除每轮 O(技能文件数)×用户 的文件级比对/写入
- farm manifest 零宿主读（候选来自端点 version 响应）
- 磁盘 GC（SkillCacheGcService）对包技能维度的依赖随之消失

## 3. 设计

### 3.1 遮蔽类 `MarketplaceStager`（唯一分叉单元）

行为契约（保持全部公开签名/常量值）：

- `stage(repoBounds, nsMap, scope)`：**不写盘**，按 repo 来源分类合成：
  - `repo instanceof WorkspaceSkillRepository` → `WorkspaceNative`（L4 原生，不经过 .skills-cache）
  - 其余（市场层 FileSystemSkillRepository，ns=`oaf-package`）→ `Cached(scope, ns, skill.name())`
    ——路径与 farm 建链逐字同构（实现期单测钉死 `joinCache` 输出 == farm 容器路径）
- 宿主 stage 目录缺失/不写 → `linkCandidates` 宿主遍历失效由 §3.2 端点名单承接
- `invalidateAll()`：no-op（无缓存可失效）
- 静态 `resolveSourceNamespaces`/常量：逐字节保持原值（`SkillCacheScope` 等消费方依赖）

**降级语义（与 PR-4b 一致）**：端点不可达由上层（farm 判定）承接——遮蔽类本身不感知端点；
staging 不写盘后，宿主目录不再产生内容，farm manifest 只信端点名单。

### 3.2 farm 候选源切端点（我方正常代码）

- `SkillBundleService.SkillVersion` 增加 `skills: List<String>`（computeVersion 同源枚举 =
  bundleFiles 的 enabled 名单；version 响应加字段对守护透明，D-9 预留）
- `SkillsFarm.linkCandidates` 改端点名单制：候选 = 端点 `skills` 名单（`oaf-package/<name>` 两层
  布局常量）− 用户级禁用 − L4 同名；删除宿主 scopeDir 遍历（`SkillCacheScope`/禁用判定原语保留）
- 容器布局不变：`.skills-cache/<scope>/oaf-package/<skill> → /skills-store/CURRENT/<skill>`

### 3.3 一致性检查项（实现期）

| # | 检查 | 手段 |
|---|---|---|
| C-1 | `ShellPathPolicy.joinCache` 输出 == farm 容器路径（逐字） | 单测：合成 Cached → resolve 输出断言 |
| C-2 | `WorkspaceManager.getSkillsDir` repo 归属（决定 WorkspaceNative vs Cached） | 实现期核实；若它指向 L4 workspace 区则归 WorkspaceNative |
| C-3 | 遮蔽生效 | 启动自检：`MarketplaceStager.class.getProtectionDomain().getCodeSource()` 指向 BOOT-INF/classes；CI 断言 SDK 版本 pin |

## 4. 测试与验收

- **单测（TDD 先行）**：遮蔽类合成行为（Cached/WorkspaceNative/None 分类、常量值、
  resolveSourceNamespaces 等价性）、端点 skills 名单、farm 端点名单制候选（含禁用/L4 同名排除、
  降级空名单）、C-1/C-3 检查
- **既有回归**：farm 组 81 测试改写后全绿、X18（mock，farm 关=无投放不变）、三路径验收脚本、
  R/P 组双副本门禁
- **kind 真实验收**：发布带技能服务 → ①宿主 `.skills-cache` **不再产生任何 per-user/内容写盘**
  （`_shared` 亦无）；②容器软链照常、prompt 技能可见且可 load；③版本切换/移除技能 → 链清理；
  ④L4 添加/禁用不受影响；⑤端点停机 → 降级无包技能（prompt 无路径）
- **多副本**：共享记账跨副本稳态（复用 PR-4b 验收脚本）

## 5. 维护协议（受控分叉条款）

1. **分叉面冻结**：仅允许遮蔽 `MarketplaceStager` 一个类；其余 SDK 类一律不动（写入
   agent-framework/AGENTS.md）
2. **SDK 版本 pin**：pom `agentscope.version` 变更必须同步重 derive 遮蔽类（decompile 对比
   → 更新 → 全量回归），CI 加版本断言（Test：`getPackage().implementationVersion` 或 jar
   checksum 比对）
3. **遮蔽生效自检**：C-3 纳入应用启动 fail-fast（遮蔽失效 = 宿主写盘回归 = 静默劣化，必须硬失败）
4. **升级路径**：若上游后续提供 stager 注入口/`skillStagingScope`，删除遮蔽类切官方 API（本设计
   的合成行为即官方 seam 的参考实现）

## 6. 回滚

遮蔽类删除 + farm 候选源切回宿主遍历（单 commit 可逆）；回滚后回到 PR-4b 形态（宿主写盘恢复、
行为与 #339 合并点一致）。
