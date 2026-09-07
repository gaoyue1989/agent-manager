# OAF Skills 目录动态加载方案

> **状态：✅ M1 完成（agent-framework 主体 + 单测，2026-09-07）；✅ M2 完成（集群 E2E，2026-09-07）；M3 远程技能扩展（可选，未开始）**
> 目标：让 OAF 配置包内的 `skills/` 目录（PVC 只读挂载于 `/config/skills`）支持**运行时动态加载**——
> 包内容原位更新后，agent **无需重启 Pod** 即可在下一轮推理中感知新增/修改/删除的技能。
> **范围：仅 agent-framework 工程**。平台 backend（Go）不涉及（包内容如何更新不由平台保证，动态加载只对"PVC 上文件原位变化"这一事实生效）。
>
> 依赖版本：agentscope-harness **2.0.0**（本项目 pom 现用版本）。
> 官方实现位置：agentscope-java `agentscope-core/io.agentscope.core.skill` + `agentscope-harness/io.agentscope.harness.agent.skill`。
> **M2 过程中发现并修复了平台 backend 的包目录误清 bug（见附录 C）。**

---

## 一、结论先行（官方已有，缺的是接线）

对 [agentscope-java](https://github.com/agentscope-ai/agentscope-java)（main 分支 + 本地 2.0.0 jar 字节码双重验证）的调研结论：

**官方 SDK 已原生具备"skill 目录动态加载"的完整能力，本项目的问题只是没用上它**：

| 官方机制 | 说明 | 本项目现状 |
|----------|------|-----------|
| `AgentSkillRepository` SPI | 技能来源抽象，`getAllSkills()` 每次调用重新枚举 | 未使用（未注册任何市场仓库） |
| `FileSystemSkillRepository` | 目录仓库：每次 `getAllSkills()` 重扫目录，按 SKILL.md `mtime+size` 做快照缓存，**新增/删除/修改自动感知** | 未使用 |
| `HarnessSkillMiddleware` | Harness 默认技能中间件（`build()` 内自动装配，默认**动态模式**）：每轮推理 `onSystemPrompt` 前重新合并所有仓库 → 重建目录 → 渲染 `<available_skills>` | 随 SDK 默认生效，但仓库列表里只有 workspace 层 |
| `disableDynamicSkills()` | 关闭动态（改为 build 时冻结快照） | 未调用（保持动态，正确） |
| `MarketplaceStager` | 市场（Layer 2）技能资源物化到 `<wsRoot>/.skills-cache/<source>/<name>/`：文件级 SHA-256 去重、孤儿清理、shebang 恢复可执行位 | 未触发（无市场仓库） |
| 四层优先级合成 | `composeSkillRepositories()`：项目全局目录 < **市场仓库** < `workspace/skills/`（本地） < `<userId>/skills/`（store，per-user） | 仅 Layer 3/4 生效 |
| workspace 模板下层 | 共享存储模式下 `CompositeFilesystem`：store 为上层、本地 workspace 目录为**只读模板下层**；官方设计即允许模板被外部（git 同步等）运行时更新 | 成立，未利用 |

**当前动态加载失效的根因**（不是 SDK 缺能力）：

`WorkspaceInitializer.copySkills()` 只在 **Pod 启动时**把 `/config/skills/{name}` 复制到
`workspace/skills/{name}`，且 `!Files.exists(targetDir)` 存在即跳过（WorkspaceInitializer.java:116）。
之后运行中 `/config/skills` 的任何变化**永远无人重新复制**，Layer 3 扫描的是过期的静态副本。
换包场景下平台走 `republish`（新 Deployment → Pod 重启），掩盖了这一问题。

---

## 二、官方 skill 体系调研细节

### 2.1 核心类图（2.0.0）

```
AgentSkillRepository (SPI: getAllSkills/getSkill/save/delete/getSource/isWriteable)
 ├── FileSystemSkillRepository      (core)   目录重扫 + mtime/size 快照缓存；writeable 可关
 ├── ClasspathSkillRepository       (core)   JAR 内 skills/（兼容 Spring Boot Fat JAR）
 ├── GitSkillRepository             (extension) HEAD 变化才 pull
 ├── MysqlSkillRepository           (extension) 表存储，writeable 控制写回
 ├── NacosSkillRepository           (extension) 变更订阅推送
 └── WorkspaceSkillRepository       (harness) per-user 命名空间，走 AbstractFilesystem（store），LazyResourceCapable

HarnessSkillMiddleware (harness, 默认动态)
 ├── 每轮 onSystemPrompt: merge(repositories) → visibilityFilter → skillFilter
 │                        → MarketplaceStager.stage() → SkillCatalog → SkillRuntime.install()
 │                        → 渲染 <available_skills>（name/description/skill-id/files-root）
 ├── frozen 模式 = disableDynamicSkills()（build 时快照，运行期不重扫）
 └── SkillLoadTool: load_skill_through_path(skillId, path) 统一读取（内存命中 → 文件系统 → 路径清单兜底）
```

### 2.2 四层优先级（同名覆盖，低 → 高）

| 层 | 来源 | 本项目对应 | 存储位置 |
|----|------|-----------|---------|
| L1 | `projectGlobalSkillsDir(path)` | 未使用 | 本地磁盘 |
| L2 | `builder.skillRepository(...)`（市场，后注册优先） | **本方案新增：`/config/skills`** | PVC（只读） |
| L3 | `workspace/skills/`（agent 共享，框架默认注册） | 启动时 copySkills 复制品 → **本方案移除** | 容器层本地磁盘 |
| L4 | `<userId>/skills/`（per-user 覆盖/自学习，框架默认注册，source=`workspace-namespaced`） | `skill_manage` / `propose_skill` 写入 | agent_fs（MySQL，持久） |

关键语义：**下层独有的 skill 保留，同名时上层覆盖**。包内 skill 是基线，用户可在 L4 覆盖出个人版本。

### 2.3 官方文档对"动态"的定位（docs/v2/zh/docs/harness/skill.md）

- 平时不建议调 `disableDynamicSkills()`："单次任务跑完就退出，或市场后端慢"才用；
- 合并结果用 SHA-256 内容签名短路——仓库内容没变时每轮开销近似于零；
- 共享存储模式下"管理台改完下一轮推理即可生效"（filesystem.md §多用户隔离）；
- 市场 skill 沙箱脚本执行靠 `.skills-cache` 投影（projection roots 默认含 `skills` 与 `.skills-cache`，SHA-256 增量 hydrate）。

### 2.4 对比：skill 与 MCP 的"动态"差异（消除误解）

| | MCP | Skill |
|--|-----|-------|
| 注册时机 | 启动一次性（`McpToolRegistrar`，连接建立即定） | **每轮推理重扫**（HarnessSkillMiddleware） |
| 运行时生效 | 需重启（本项目 fail-soft 机制解决的也是启动期） | 目录变化**下轮自动生效** |
| 所需机制 | watcher / reload 端点（官方无，需自研） | **官方原生，零自研** |

skill 的动态性来自"每轮重扫"模型，不需要文件 watcher、不需要 reload API——这与 MCP 完全不同。

---

## 三、现状与差距分析

### 3.1 现有链路（问题链）

```
OAF 包(zip) → PVC packages/{id} → subPath 只读挂 /config
   → WorkspaceInitializer.copySkills()   [仅启动时，存在即跳过]
   → 容器层 /workspace/.agentscope/workspace/skills/{name}   ← 冻结的静态副本
   → HarnessSkillMiddleware L3 扫描该副本                      ← 永远是旧内容
```

### 3.2 差距清单

| # | 差距 | 影响 |
|---|------|------|
| G1 | `/config/skills` 未注册为任何 `AgentSkillRepository` | 目录变化无从感知 |
| G2 | copySkills 复制语义 + 存在即跳过 | 副本漂移；即使重启，旧副本也挡住新内容（除非容器层恰好丢失） |
| G3 | `/skills`、`/debug/config`、`/.well-known/agent-card.json` 读 `oafConfig.skills()`（静态 frontmatter） | 动态目录中的技能对外不可见 |
| G4 | frontmatter `skills[].source` 支持 well-known URL（OAF 规范），`OafConfig.remoteSkills()` 有过滤无实现 | 远程技能未落地（本方案作为扩展阶段） |

---

## 四、总体设计

### 4.1 目标链路

```
OAF 包 skills/ (PVC /config/skills, 只读)
   │  注册为 L2 市场仓库（不复制！）
   ▼
FileSystemSkillRepository(configDir/skills, writeable=false, source="oaf-package")
   │  每轮推理 getAllSkills() 重扫（mtime+size 短路）
   ▼
HarnessSkillMiddleware.merge() ◀── L3 workspace/skills（移除后为空→自动跳过）
   │                              ◀── L4 store per-user（自学习/覆盖，不变）
   ▼
SkillCatalog → <available_skills> prompt + load_skill_through_path
```

**一句话：把"启动时复制"改成"运行时注册市场仓库"，动态性全部由官方中间件承担，本项目零自研调度。**

### 4.2 设计原则

1. **单一事实来源**：包内 skill 只读 `/config/skills`，不再复制，杜绝双份漂移；
2. **官方原生优先**：仓库合成、重扫、staging、投影全部走 SDK，无 watcher/定时器/自研同步器；
3. **frontmatter 声明降级为元数据**：`skills:` 列表继续用于 A2A 卡片展示、required 启动告警、`loadSkillDescription`；目录是事实来源，**未声明但目录中存在的 skill 同样可用**（这正是"动态"的意义）；
4. **只读防御**：市场仓库 `writeable=false`，防止 `skill_manage` 写回只读 PVC（写会直接抛 IO 异常，必须在仓库层拒绝语义化）；
5. **四层语义不变**：L4 用户自学习（agent_fs 持久化）与 L2 包基线的覆盖关系保持官方默认。

---

## 五、详细设计（文件级改动清单）

### 5.1 新增 `service/OafSkillRepositoryFactory`（或内联于 AgentScopeConfig）

**文件**：`src/main/java/io/agentmanager/framework/config/AgentScopeConfig.java`

在 `harnessAgent(...)` 装配处新增（伪码级别描述，实现按现有代码风格）：

```java
// OAF 包内技能目录注册为市场层（L2）：writeable=false 只读分发；
// 目录不存在时跳过（包未携带 skills），并记录日志。
var oafSkillsDir = configDir.resolve("skills");
if (Files.isDirectory(oafSkillsDir)) {
    builder.skillRepository(new FileSystemSkillRepository(
        oafSkillsDir, /*writeable=*/false, /*source=*/"oaf-package"));
    log.info("OAF skill repository registered (dynamic): {}", oafSkillsDir);
}
```

要点：
- `FileSystemSkillRepository` 构造要求目录已存在 → 以 `Files.isDirectory` 守卫（与官方 `composeSkillRepositories` 对 L1/L3 的守卫一致）；
- `writeable=false`：`save()`/`delete()` 直接返回 false + WARN，`skill_manage` 对市场层的写操作被语义化拒绝；
- `source="oaf-package"`：决定 L2 物化目录 `.skills-cache/oaf-package/<name>/`（沙箱脚本路径前缀）与 skill-id 稳定性。

### 5.2 修改 `service/WorkspaceInitializer`

**文件**：`src/main/java/io/agentmanager/framework/service/WorkspaceInitializer.java`

- **删除 `copySkills()`** 及调用（`initialize()` 中移除 `copySkills(workspace, oafConfig)`）；
- `skills/` 目录不再预创建：`composeSkillRepositories` 对 L3 的 `Files.isDirectory` 判断会自动跳过，无需空目录；
- 保留 `writeAgentsMd` / `writeToolsJson` / `writeSubagents` 不动。

**存量兼容**：历史 Pod 容器层 `/workspace/.agentscope/workspace/skills/` 里的旧副本随容器重建自然消失（容器层非持久）；`agent_fs`（MySQL）中 L4 用户自学习内容不受影响，继续按 L4 生效。**无需迁移**。

### 5.3 新增 `service/SkillCatalogService`（动态技能目录数据源）

**文件**：`src/main/java/io/agentmanager/framework/service/SkillCatalogService.java`（新增）

职责：为对外 API 提供合并后的技能视图（frontmatter 声明 ∪ 目录实际内容），三处消费：

| 消费方 | 现数据源 | 改后 |
|--------|---------|------|
| `ToolController.listSkills()`（GET /skills） | `oafConfig.skills()` | `SkillCatalogService.list()` |
| `AgentCardController`（A2A agent-card skills 字段） | `oafConfig.skills()` | `SkillCatalogService.list()` |
| `DebugApiController`（GET /debug/config） | `oafConfig.skills()` | `SkillCatalogService.list()` |

合并语义：

```
条目 = merge(
    frontmatter 声明 (OafConfig.SkillConfig: name/source/version/required/allowedTools/metadata),
    目录事实     (configDir/skills/{name}/SKILL.md frontmatter: name/description/allowed-tools/metadata.version)
)
冲突时以目录 SKILL.md 为准（事实优先）；声明独有 → 条目标 "declared-but-missing": true；
目录独有 → source = "local-dynamic"（动态出现、未声明）。
```

实现要点（复用现有代码，不引新依赖）：
- 扫描逻辑对齐 `FileSystemSkillRepository`（mtime+size 快照缓存避免重复解析），可直接**实例化一个 `FileSystemSkillRepository(configDir/skills, false, "oaf-package")` 供本服务只读复用**，避免重复实现缓存；
- 描述解析复用 `OafConfigLoader.loadSkillDescription` 的解析口径（或抽取共用）；
- 无锁化：方法内局部变量构建，读路径无共享可变状态；
- 输出字段对齐现有 API 形状（见 5.4），新增 `dynamic: true` / `declared-but-missing: true` 两个布尔标记，前端可无感兼容。

### 5.4 API 输出调整（保持形状兼容）

| 文件 | 改动 |
|------|------|
| `controller/ToolController.java` | `listSkills()` 切 `SkillCatalogService`；字段映射保持现有键不变 |
| `controller/AgentCardController.java` | `skills` 字段切 `SkillCatalogService`；A2A 卡片 schema 不变 |
| `controller/DebugApiController.java` | `skills` 块切 `SkillCatalogService`（debug 页技能面板自动获得动态视图） |
| `config/OafConfigLoader.java` | `parseSkills`/`loadSkillDescription` 保留；`warnSkill*` 降级告警保留；**新增**：`required=true` 且目录缺失时 WARN（现有仅 description 告警，对齐 required 语义） |

### 5.5 不做的事（明确边界）

- **不**自研文件 watcher / 定时同步器 / reload 端点——官方每轮重扫已覆盖，reload 概念不存在于该模型；
- **不**修改 `mcp-configs` 加载逻辑——MCP 是启动期一次性注册，与 skill 动态模型不同（见 §2.4）；
- **不**调用 `disableDynamicSkills()` / `disableDefaultWorkspaceSkills()`；
- **不**在本阶段实现 well-known URL 远程技能（G4 → M3 可选扩展）。

---

## 六、生效链路（时序推演）

### 6.1 包内容原位更新（核心场景）

```
T0  Pod running，/config/skills/{a,b} 已注册（L2），下轮推理 prompt 含 a、b
T1  PVC packages/{id} 内容原位更新（运维手段，平台不参与）：
      - 新增 skills/c/（含 SKILL.md）
      - 修改 skills/a/SKILL.md（mtime 变）
      - 删除 skills/b/
T2  下一轮推理（任一会话发消息）：
      HarnessSkillMiddleware.onSystemPrompt
        → repo.getAllSkills() 重扫 → {a(新), c}
        → 内容签名变化 → 重建 SkillCatalog → stager 增量物化
        → <available_skills> 渲染 a、c；b 消失
T3  agent 调 load_skill_through_path("c") 读取新技能详情并使用
全程无重启、无 reload 调用
```

### 6.2 L4 覆盖（自学习闭环不变）

```
skill_manage(修改 a) → 写 L4（agent_fs, per-user）
→ 该用户下轮推理：merge 后 L4 的 a 覆盖 L2 的 a（官方优先级，无需改动）
→ 其他用户仍见 L2 包内版本
```

### 6.3 沙箱模式（SANDBOX_ENABLED=true）

- L2 技能资源由 `MarketplaceStager` 物化到 `/workspace/.skills-cache/oaf-package/<name>/`（本地 wsRoot，容器层可写，不触碰只读 `/config`）；
- SDK workspace projection（2.0.0 默认 roots 含 `skills`、`.skills-cache`）随沙箱启动 hydrate 进容器 `/workspace`，SHA-256 增量；
- `<files-root>` 由 `ShellPathPolicy.sandbox(workspaceRoot)` 渲染为容器内绝对路径；
- **验证点（里程碑 M2）**：本项目 `OpenSandboxFilesystemSpec` 为自定义沙箱 spec，需实测确认 2.0.0 的 projection 路径在其上生效（`hydrateWorkspace` 注释已声明"静态模板由框架投影注入"，预期成立）。

---

## 七、平台边界（明确不涉及）

平台 backend（Go）**不做任何配套改动**：不提供包内容热更新接口，不调整 republish 流程。

本方案对"PVC 上 `packages/{id}` 目录文件级原位变化"这一事实生效——变化从哪来（运维 `kubectl exec`/直接写 PVC、或未来任何手段）不属于本设计范围。若未来需要"换包不重启"，属于平台独立需求，另行立项。

> K8s 事实（供排障参考）：ConfigMap/Secret subPath 有已知同步延迟问题（kubelet 不推送），**PVC subPath 不同**——bind mount 直接反映宿主文件变化，文件级更新实时可见。这是本方案"无需重启即可生效"的前提。

---

## 八、测试方案（按全流程门禁要求）

### 8.1 单元测试（新增，随代码同 PR）

| 用例组 | 覆盖 |
|--------|------|
| `OafSkillRepositoryTest` | 临时目录模拟 `/config/skills`：① 新增 `{name}/SKILL.md` → `getAllSkills()` 出现；② 修改 SKILL.md（mtime 变）→ 内容更新；③ 删除目录 → 消失；④ `writeable=false` 时 `save/delete` 返回 false 且不落盘；⑤ 目录不存在时构造守卫不抛异常（条件注册路径） |
| `SkillCatalogServiceTest` | ① frontmatter 声明 ∪ 目录合并；② 冲突以目录为准；③ declared-but-missing 标记；④ 目录独有 → `source=local-dynamic`；⑤ 空目录/空声明 → 空列表不抛 |
| `WorkspaceInitializerTest`（改造） | 移除 copySkills 后：不再产出 workspace/skills；AGENTS.md/tools.json/subagents 生成不变（回归） |
| `AgentCardControllerTest` / `ToolControllerTest`（如已有则扩展） | 动态条目出现在 card.skills 与 /skills；字段形状与旧版兼容 |

### 8.2 E2E（真实集群 + 真实镜像）

| 场景 | 步骤 | 断言 |
|------|------|------|
| E-SKILL-1 基线 | 发布带 `skills/demo-a/` 的包 → 对话"你有哪些技能/使用 demo-a 技能" | `<available_skills>` 含 demo-a；`load_skill_through_path` 可读 |
| E-SKILL-2 动态新增 | 直接向 PVC `packages/{id}/skills/` 原位写入 `demo-b/`（kubectl exec 到节点/hostPath） → **不重启** → 再对话 | 新技能下轮可见可用；`GET /skills` 含 demo-b |
| E-SKILL-3 动态修改/删除 | 原位改 demo-a 描述、删 demo-b → 再对话 | 描述更新；demo-b 消失 |
| E-SKILL-4 沙箱 | SANDBOX_ENABLED=true 复跑 E-SKILL-1/2；技能带 `scripts/*.sh` | 沙箱内 `/workspace/.skills-cache/oaf-package/...` 可执行 |
| E-SKILL-5 L4 覆盖 | 会话内 `skill_manage` 修改包内同名技能 → 该用户生效、他用户不受影响 | 官方四层优先级 |

### 8.3 回归门禁

`mvn test`（现 383 用例）+ 上述新增全绿；`e2e/` 既有脚本（platform-e2e / mcpclient / ui-e2e / agent-e2e）无回归。

---

## 九、里程碑

| 阶段 | 内容 | 门禁 |
|------|------|------|
| **M1** agent-framework 主体 | §5.1–5.4 全部改动 + 单测 | `mvn test` 全绿 |
| **M2** 集群验证 | E2E E-SKILL-1~5（含沙箱） | 全 PASS |
| **M3** 远程技能扩展（可选） | well-known URL source：按 OAF frontmatter `source` 装配官方 `GitSkillRepository`/HTTP 拉取仓库；`remoteSkills()` 接线 | E2E 扩展 |

---

## 十、风险与开放问题

| # | 风险 | 缓解 |
|---|------|------|
| R1 | 2.0.0 与 main 分支 `composeSkillRepositories` 实现漂移 | 已 javap 反汇编 2.0.0 jar 逐字节核对（L2/L3 构造与 main 一致）；SDK 升级时纳入回归 |
| R2 | PVC 目录级 rename 不可见（K8s subPath 语义） | 生效前提为原位文件级更新（§7 K8s 事实）；E-SKILL-2/3 用文件级操作 |
| R3 | 包内 SKILL.md 与 frontmatter 声明不一致 | 事实优先 + `declared-but-missing` 标记 + required WARN（§5.3/5.4） |
| R4 | 沙箱投影未在自定义 OpenSandboxFilesystemSpec 上实测 | M2 E-SKILL-4 显式验证；不通过则降级方案：把 `.skills-cache` 并入 `workspaceSyncService` 同步范围 |
| R5 | `skill_manage`（autoPromote=true，本项目已开）误写市场层 | `writeable=false` 仓库层拒绝；提示词可补充"包内技能为平台分发，请用个人覆盖" |
| R6 | 每 60 分钟 `flushTrigger` 记忆 flush 与重扫叠加的 DB 压力 | 重扫为本地文件 mtime 比较（内存命中短路），无 DB 参与；无压力增量 |

**开放问题（评审定）**：
1. `/config/skills` 目录不存在时：条件跳过（推荐）还是注册空目录（`Files.createDirectories`）？→ 倾向前者，避免在只读 PVC 上产生写意图。
2. `source="oaf-package"` 命名是否需与平台 PackageID 绑定（如 `oaf-pkg-42`）以支持未来多包仓库？→ 倾向固定值，多包场景由 M3 远程扩展时再细分。

---

## 附录 A：官方参考实现索引（代理可访问）

- `agentscope-core/src/main/java/io/agentscope/core/skill/`：`AgentSkill` / `SkillRegistry` / `DynamicSkillMiddleware` / `repository/FileSystemSkillRepository`
- `agentscope-harness/src/main/java/io/agentscope/harness/agent/skill/`：`WorkspaceSkillRepository` / `runtime/SkillRuntime` / `runtime/MarketplaceStager` / `runtime/SkillLoadTool`
- `agentscope-harness/.../middleware/HarnessSkillMiddleware.java`（每轮合并主流程）
- 文档：`docs/v2/zh/docs/harness/skill.md`（四层合成/自学习）、`docs/v2/zh/docs/harness/filesystem.md`（模板下层/投影）
- 扩展仓库：`agentscope-extensions-skill-{git,mysql,postgresql}-repository`、`agentscope-extensions-nacos-skill`

## 附录 B：本项目现状代码索引

- `service/WorkspaceInitializer.java` `copySkills`（已删除的复制语义根因）
- `config/OafConfigLoader.java:97` `parseSkills` / `:209` `loadSkillDescription`
- `config/AgentScopeConfig.java` `harnessAgent` builder（L2 注册插入点，已接线）
- `controller/ToolController.java` GET /skills、`controller/AgentCardController.java` 卡片 skills、`controller/DebugApiController.java` debug config（均已切 SkillCatalogService）
- `model/OafConfig.java:27` `localSkills()`/`remoteSkills()`（M3 接线点）

## 附录 C：M2 实施记录（2026-09-07）

### C.1 E2E 结果（e2e/skills-dynamic-e2e.sh）

| 档 | 结果 | 覆盖 |
|----|------|------|
| 非沙箱 | **20/20 PASS** | P 发布 + E-SKILL-1/2/3/5 |
| SANDBOX=1 | **23/23 PASS** | 上表全部 + E-SKILL-4（沙箱 .skills-cache 物化 → 投影 → 容器内脚本执行，含**运行中新增的 demo-c 脚本**） |

关键验证点：
- **不重启生效**：宿主直写 PVC（bind mount 文件级实时可见），下一轮对话即读新技能内容；Pod restartCount 前后不变（2.5）
- **`<available_skills>` 实际生效**：以 A2A 真实对话验证（load_skill_through_path 读到 marker），非仅 API 断言
- **L4 用户覆盖隔离**：skill_manage 修改包内同名技能 → 该 userId 生效（5.2）、其他 userId 不受影响（5.3）
- **OpenSandboxFilesystemSpec 投影验证通过**（设计文档 R4 风险解除）：市场 skill 经 `.skills-cache/oaf-package/` 物化 + 沙箱投影 + `<files-root>` 容器内路径，脚本执行退出码 0

### C.2 顺带修复：platform-backend 包目录误清（Go，backend/AGENTS.md 范畴）

**现象**：同包双服务删除其一后，**仍被另一服务引用的包目录被误清**（AGENTS.md/技能文件消失 → 业务 Pod `AGENTS.md not found` CrashLoop）。inotify 抓到完整 `os.RemoveAll` 递归序列与删除服务动作精确对齐。

**根因**：`Core.Delete()` 在事务内用**非原子读-改-写**判断 refCount（`First` 读出后应用侧减 1），并发发布/删除时序下误判归零 → `RemovePackage` 清掉仍被引用的目录。`mvn test` 类比的 Go 单测已补回归锁 `TestDeleteKeepsPackageWhenRefCountPositive`（同包双服务删一，断言目录完好）。

**修复**（backend/internal/service/publish.go）：
1. refCount 改**原子递减**：`UPDATE ... SET ref_count=CASE WHEN ref_count>0 THEN ref_count-1 ELSE 0 END`（MySQL 8 / SQLite 兼容）
2. `RemovePackage` **移出事务**，按提交后的最终 refCount 判定（与并发发布天然互斥）
3. 补删除日志（`[delete] package N dir ... removed (ref_count=0)`）——包目录清理不再静默

**教训（E2E 脚本）**：直写 PVC 的文件需 `chown 10001:10001` 对齐 backend 的 app 用户，否则平台删包时 unlink 失败 500。

### C.3 M1 改动清单（agent-framework，452 单测全绿）

| 文件 | 改动 |
|------|------|
| `config/AgentScopeConfig.java` | harnessAgent 装配处注册 `/config/skills` 为 L2 市场仓库（`FileSystemSkillRepository, writeable=false, source="oaf-package"`），目录缺失时跳过 |
| `service/WorkspaceInitializer.java` | 删除 `copySkills`/`copyDirectory`（复制语义根因） |
| `service/SkillCatalogService.java` | **新增**：声明 ∪ 目录事实合并视图，冲突以目录为准，复用官方 mtime+size 缓存 |
| `controller/ToolController.java` | GET /skills 切动态数据源 |
| `controller/AgentCardController.java` | A2A 卡片 skills 切动态数据源 |
| `controller/DebugApiController.java` | /debug/config/oaf 切动态数据源 |
| `config/OafConfigLoader.java` | required=true 且目录缺失 WARN（不阻断启动） |

新增测试：`config/OafSkillRepositoryTest`（5）、`service/SkillCatalogServiceTest`（7）；改造 `WorkspaceInitializerTest`；适配 ToolControllerTest/AgentCardControllerTest/DebugApiControllerTest。
