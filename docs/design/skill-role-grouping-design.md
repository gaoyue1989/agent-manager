# Skill 按角色分组载入（会话内角色切换）— 设计文档

**日期**：2026-11-02
**状态**：设计定稿（未实施）
**范围**：agent-framework 运行时；控制面（backend/frontend）零改动

---

## 1. 背景与问题

当前一个 OAF 包只有一套平铺技能目录 `/config/skills`：

- `HarnessAgentFactory` 把它注册为 SDK 的 L2 `FileSystemSkillRepository`（`HarnessAgentFactory.java` 中 `builder.skillRepository(...)`）；
- SDK 的 `HarnessSkillMiddleware` 每轮把仓库里的全部技能渲染进模型侧 `<available_skills>`，并允许 `load_skill_through_path` 加载；
- L4 个人技能（`agent_fs` KV）按 userId 叠加；被禁用的技能由 `SkillDisabledFilterMiddleware` 以 per-call `SkillFilter` 排除。

当一个 Agent 需要扮演多个角色（客服 / 运维 / 审核 / 管理员）时，所有角色共用同一份技能清单：

1. **上下文膨胀**：每个角色的技能都进 `<available_skills>`，角色越多、技能越多，提示词越大；
2. **误用与串角色**：模型可能调用不属于当前职责的技能（例如客服轮次加载运维排障脚本）；
3. **无法按会话收敛**：同一个服务、同一个会话内切换职责时，没有"只载入本角色技能组"的机制。

本设计新增**会话内角色**：角色在包里声明，技能按角色分组；会话选定角色后，运行时只把该角色组（+共享组）的技能暴露给模型。角色可在一段会话中切换，切换结果随会话持久化，多副本一致。

### 1.1 目标

- 包作者用一份独立配置文件声明角色与技能分组，**不修改 AGENTS.md、不依赖控制面**；
- 会话级选择角色（新建/续聊均可），角色写入共享存储，任意副本、重启后仍生效；
- 运行时按角色过滤 SDK 的技能清单（`<available_skills>`、`load_skill_through_path`）、框架自有视图（`/skills`、`/skills/available`、`@Skill` 注入）；
- 无角色配置的包行为**逐字节不变**（兼容存量）；
- 被禁用技能、L4 个人技能、动态新增技能的既有语义不被破坏。

### 1.2 非目标

- **不做权限控制**：角色不是授权边界。能调用 chat 的调用方可以选择任意已声明角色；按用户/租户限制可选角色属于后续能力；
- **不做工具 / MCP / 子 Agent 的按角色过滤**：一期只作用于技能；
- **不做物理隔离**：沙箱容器内仍会投影全部技能文件（见 §10.2）；需要硬隔离时走独立服务/独立包；
- **不做控制面 UI**：发布向导、包生成器不生成角色配置；包作者手工放入 `skill-roles.yaml`（在线包编辑器可直接编辑该文件）；
- **不做角色专属提示词、角色继承、角色级模型参数**。

---

## 2. 现状事实（设计依据）

| 链路 | 现状 | 代码位置 |
|------|------|----------|
| L2 包内技能 | `/config/skills` 注册为只读 `FileSystemSkillRepository`，每轮重扫 | `service/HarnessAgentFactory.java`（`oafSkillsDir` 分支） |
| L4 个人技能 | `agent_fs` KV `agents/{agent}/users/{uid}/skills`，按 userId 合并 | `service/UserSkillService.java`、`service/WorkspaceReader.java` |
| 技能清单 | `SkillCatalogService.list()` = 目录事实 ∪ frontmatter 声明，减禁用集 | `service/SkillCatalogService.java` |
| 模型侧技能 | SDK `HarnessSkillMiddleware` 读仓库 + per-call `SkillFilter` | SDK 2.0.3 `agentscope-core` |
| 禁用过滤 | `SkillDisabledFilterMiddleware` 把禁用名以 `SkillFilter.disable(...)` 放进 `RuntimeContext` | `service/SkillDisabledFilterMiddleware.java` |
| `@Skill` 注入 | 入口按启用集合把 `@Name` 替换为完整 SKILL.md | `service/SkillInjectionService.java` |
| 会话状态 | `session_user` 表存 `user_id` / `model` / `remark`；`upsertColumn` 白名单 | `service/SessionUserStore.java` |
| 会话切换先例 | `PATCH /threads/{sid}` 支持 `title` / `model`；chat body 支持 `model` | `controller/ThreadController.java`、`controller/ChatStreamController.java` |
| 配置 reload | `OafConfigHolder` 原子替换；技能目录动态重扫 | `config/OafConfigHolder.java`、`service/OafReloadService.java` |

SDK `SkillFilter`（2.0.3 字节码实测）提供：

```java
SkillFilter.all();                 // 全部允许
SkillFilter.none();                // 全部拒绝
SkillFilter.only(String...);       // 白名单（Set.of，重复名会抛异常）
SkillFilter.disable(String...);    // overlay：禁用这些名字
SkillFilter.overlay(SkillFilter);  // base.overlay(overlay)：overlay 集合内的名字以 overlay 判定为准，其余用 base
```

因此「角色白名单 + 禁用名单」可以组合为：

```java
var filter = SkillFilter.only(roleSkillNames);   // 角色组
filter = filter.overlay(SkillFilter.disable(disabledNames)); // 再减禁用
ctx.put(SkillFilter.class, filter);
```

---

## 3. 总体方案

### 3.1 三个核心决策

| 编号 | 决策 | 理由 |
|------|------|------|
| D1 | 角色声明放在包根目录独立文件 `skill-roles.yaml`，不改 AGENTS.md frontmatter | 控制面 `agentgen` 会重写 AGENTS.md（`deriveFrontmatter` 只保留已知字段），放 frontmatter 会在控制台编辑/重新发布时丢失；独立文件是普通包文件，copy-on-write 与在线编辑都保留。运行时零控制面改动即可用 |
| D2 | 载入 = per-call SDK `SkillFilter` overlay，不做物理目录切换/重建 Agent | 会话内可切换；SDK 已支持 per-call filter（`effectiveFilter = builderFilter.overlay(ctx.filter)`，现有禁用中间件即用此钩子）；L2 动态重扫、L4 合并、沙箱投影全部复用现有链路 |
| D3 | 角色状态存 `session_user.role`（Flyway V15），空串 = 默认角色 | 共享存储，多副本/重启一致；与 `model` 列同一模式；不依赖进程内状态，不违反「每 Pod 无状态」约束 |

### 3.2 链路

```
Client
  │ POST /threads/chat { role }        PATCH /threads/{sid} { role }        A2A message.metadata.role
  ▼
入口控制器
  │ 1. RoleCatalogService.validateSelectable(role)
  │ 2. SessionUserStore.upsertRole(sid, role)     ← 共享存储
  │ 3. SkillInjectionService.inject(@Skill, uid, effectiveRole)
  ▼
Agent turn
  │ SkillRoleFilterMiddleware.onAgent
  │   ├─ SessionUserStore.findRoleBySession(canonicalSid)
  │   ├─ RoleCatalogService.skillNames(role)
  │   └─ ctx.put(SkillFilter.only(roleSkills))
  │ SkillDisabledFilterMiddleware.onAgent
  │   └─ ctx.put(existing.overlay(SkillFilter.disable(disabled)))
  ▼
SDK HarnessSkillMiddleware
  └─ <available_skills> / load_skill_through_path 只看到角色组 − 禁用集
```

---

## 4. 角色声明规范

### 4.1 文件位置

```
{AGENT_CONFIG_DIR}/skill-roles.yaml      # 部署后 = /config/skill-roles.yaml
```

- 普通包根文件，随 OAF 包版本一起复制/上传/导出；
- 不在 `skills/` 下，不会被当成技能目录；
- 控制面 `agentgen` 不生成、不删除、不改写它（在线编辑 AGENTS.md / skills 时原样保留）。

### 4.2 Schema

```yaml
# 可选，当前仅支持 1；缺省按 1 处理
version: 1

# 可选：默认角色名。缺省取 roles[0]；显式值必须存在于 roles
default: support

# 可选：所有角色都载入的共享技能（与角色技能取并集）
sharedSkills:
  - common-format

roles:
  - name: support                 # 必填，^[a-z][a-z0-9-]{0,31}$，全局唯一
    label: 客服                    # 可选，展示名（缺省 = name）
    description: 退款、工单与订单查询  # 可选，展示用
    skills:                       # 必填；可为空数组（只载入 sharedSkills）
      - refund-policy
      - order-query

  - name: ops
    label: 运维
    description: 日志分析与故障排查
    skills:
      - log-analysis
      - runbook

  - name: admin
    label: 管理员
    skills: ["*"]                 # 唯一元素 "*" = 全部启用技能（仍减禁用集）
```

### 4.3 解析与校验规则

| 规则 | 行为 |
|------|------|
| 文件不存在 | `hasRoles=false`，完全走存量逻辑（全部技能） |
| YAML 解析失败 / 结构非法 | **降级为无角色**（`hasRoles=false`）+ `WARN`，不阻断启动与对话。角色分组是上下文收敛能力，不是安全边界；fail-closed 会让服务不可用（理由见 §10.1） |
| `roles` 为空 / 缺失 | 视为无角色配置，`WARN` |
| 角色名不匹配 `^[a-z][a-z0-9-]{0,31}$` | 该角色跳过 + `WARN`；其余角色继续 |
| 角色重名 | 保留首个 + `WARN` |
| `roles` 数量 > 32 | 截断到前 32 + `WARN` |
| 单角色技能数 > 256、`sharedSkills` > 256 | 截断 + `WARN` |
| `skills` 含 `"*"` 且还有其他元素 | 视为非法角色，跳过 + `WARN`（避免"全部 + 部分"的歧义） |
| `default` 缺失 | 取 `roles[0]` + `WARN` |
| `default` 不存在于 `roles` | 取 `roles[0]` + `WARN` |
| 角色引用了不存在的技能名 | 不阻断（技能可运行中动态出现）；`GET /roles` 的 `resolvedSkills` 只列实际存在的名字 |
| 角色配置存在，但某技能既不在任何角色也不在 `sharedSkills` | 该技能在**显式角色组**下不载入（`*` 角色仍可见）；`GET /roles` 的 `unassignedSkills` 列出，便于包作者发现漏配 |
| `required: true` 的技能未被任何角色引用 | 仍满足 OAF 包完整性校验；但该技能在显式角色组下不载入。`unassignedSkills` 会暴露，不额外提升为共享 |

> 解析缓存：按 `mtime + size` 缓存（与技能目录动态加载同思路）；文件原位变化下一轮生效，无需重启。

### 4.4 与 OAF 规范的关系

- `skill-roles.yaml` 是**平台运行时扩展文件**，不改变 OAF v0.8.0 的 AGENTS.md/skills 规范；
- 其他 harness 忽略该文件即可；
- 不写入 `harnessConfig`（避免与 SDK 自有配置混淆），也不要求 backend 识别。

---

## 5. 会话角色状态

### 5.1 Flyway 迁移

新增 `agent-framework/src/main/resources/db/migration/V15__session_user_role.sql`：

```sql
-- V15：会话级角色（skill 按角色分组载入）。
-- 空串 = 未显式选择，运行时回落 skill-roles.yaml 的 default 角色。
ALTER TABLE session_user ADD COLUMN role VARCHAR(64) NOT NULL DEFAULT '' AFTER model;
```

- 无存量回填：空串即默认角色，存量会话升级后行为由包是否声明角色决定；
- 遵守 Flyway 纪律：只新增 V 文件，不改已合并文件；
- 多副本启动由 Flyway 历史表锁互斥（既有机制）。

### 5.2 SessionUserStore

在 `SessionUserStore` 增加：

```java
private static final Set<String> UPDATABLE_COLUMNS =
    Set.of("model", "remark", "role");   // 白名单追加 role

public void upsertRole(String sessionId, String role) {
    upsertColumn(sessionId, "role", role != null ? role : "");
}

public String findRoleBySession(String sessionId) {
    return findColumnBySession(sessionId, "role");
}
```

- 复用既有 `upsertColumn`（UPDATE 优先、0 行补建、`PLACEHOLDER_USER` 防 `user_id=unknown` 过渡行）；
- 写入时机：`sessionUserStore.upsert(sid, uid)` 之后，与 model 同序；
- 读取时机：每个 turn 的 `SkillRoleFilterMiddleware`、入口的 `@Skill` 注入、`GET /threads/{sid}`；
- 查询失败沿用 `findColumnBySession` 的 `WARN + 空串`，调用方按"未设置"处理（回落默认角色）。

### 5.3 与模型切换的语义对照

| 维度 | `model`（既有） | `role`（本设计） |
|------|----------------|------------------|
| 存储 | `session_user.model` | `session_user.role` |
| 空值语义 | 回落系统默认模型 | 回落 `skill-roles.yaml` 的 default 角色 |
| chat body 字段 | `model` | `role` |
| PATCH 字段 | `model` | `role` |
| 非法值 | 拒绝，不改变既有绑定 | 拒绝，不改变既有绑定 |
| 生效时机 | 下一轮模型调用 | 下一轮 turn 的技能过滤 |
| 多副本 | 每次调用实时读库 | 每个 turn 实时读库 |

---

## 6. 运行时载入

### 6.1 RoleCatalogService（新增）

`service/RoleCatalogService.java`：

```java
public record RoleDefinition(
    String name, String label, String description,
    List<String> skills, boolean allSkills) {}

public record RoleCatalog(
    boolean enabled, String defaultRole,
    List<RoleDefinition> roles, List<String> sharedSkills) {}

/** 文件不存在/解析失败 → enabled=false（存量行为） */
public RoleCatalog current();

public boolean hasRoles();

public String defaultRole();

public Optional<RoleDefinition> find(String name);

/** null/空 = default；未知角色返回 Optional.empty() */
public Optional<RoleDefinition> resolve(String roleOrNull);

/** 角色生效技能名（shared ∪ role.skills，去重）；allSkills=true 返回 null（= 不限制） */
public Set<String> skillNames(RoleDefinition role);

/** 校验可选角色：null = 合法；"roles_not_configured" / "unknown_role" = 拒绝 */
public String validateSelectable(String roleOrNull);

/** 会话生效角色名：stored → default；stored 未知 → default + WARN；无角色配置 → null */
public String effectiveRole(String sessionId);
```

- 依赖 `AgentManagerProperties.resolvedConfigDir()`（与 `SkillCatalogService` 同口径）与 `SessionUserStore`；
- 不依赖 `SkillCatalogService`，避免循环依赖；技能引用是否存在的展示放在 `/roles` 控制器层用 catalog 补 `resolvedSkills`；
- 缓存快照不可变，读侧无锁。

### 6.2 SkillRoleFilterMiddleware（新增）

`service/SkillRoleFilterMiddleware.java`，`MiddlewareBase.onAgent`：

```
if (!roleCatalog.hasRoles()) return next;                 // 存量包零开销
var role = roleCatalog.effectiveRole(sessionKeyResolver.canonicalSessionId(ctx));
if (role == null) return next;
var def = roleCatalog.resolve(role);
if (def.isEmpty() || def.get().allSkills()) return next;  // "*" = 不限制，仍由禁用中间件裁剪
var names = roleCatalog.skillNames(def.get());            // 已去重
var filter = names.isEmpty() ? SkillFilter.none() : SkillFilter.only(names.toArray(String[]::new));
ctx.put(SkillFilter.class, filter);
```

- `SkillFilter.only` 内部 `Set.of` 拒绝重复，`skillNames` 必须去重；
- 角色技能为空（只有 `sharedSkills` 也为空）→ `SkillFilter.none()`，模型看不到任何技能；
- 任何异常（DB 读失败、配置损坏）→ 先尝试默认角色；默认角色也解析失败时不设置 filter + `WARN`（回落全部技能，见 §10.1）；
- 中间件顺序：**必须排在 `SkillDisabledFilterMiddleware` 之前**（在 `HarnessAgentFactory` 的 `.middleware(...)` 链中插入）；
- SDK overlay 语义补充：`ctx.filter` 若不是 overlay（`only`/`none`/`all`），`builderFilter.overlay(ctx.filter)` 会**直接采用 ctx.filter**（字节码实测）。当前 builder 级 filter 为默认 `all()`，因此无影响；若未来给 builder 设置更严格的 filter，需要改为在角色 filter 上叠加 builder 约束。

### 6.3 SkillDisabledFilterMiddleware（改一处）

现状是"有禁用名就覆盖 `ctx` 里的 `SkillFilter`"。角色过滤器也写同一个键，直接覆盖会把角色白名单冲掉。改为叠加：

```java
var existing = ctx.get(SkillFilter.class);
var disabled = SkillFilter.disable(names.toArray(String[]::new));
ctx.put(SkillFilter.class, existing != null ? existing.overlay(disabled) : disabled);
```

- `existing = only(role)` → `only(role).overlay(disable(names))`：禁用名从角色组中剔除；
- `existing = none()` → 仍为 none；
- 无禁用名时保持"不写 ctx"，角色 filter 原样保留；
- 无角色配置时行为与现状完全一致。

### 6.4 SkillCatalogService

保持 `list()` / `listAll()` 的现有语义（`list()` = 启用集，`listAll()` = 含禁用），新增角色过滤重载：

```java
/** role=null = 不做角色过滤（存量语义）；非 null = 按该角色组过滤 */
public List<Map<String, Object>> list(String roleOrNull);
public List<Map<String, Object>> availableSkills(String userId, String roleOrNull);
```

- `list(role)`：先走现有合并视图（目录 ∪ 声明 − 禁用），再按 `RoleCatalogService.skillNames(role)` 过滤；`allSkills` 不过滤；未知角色 → 空列表 + `WARN`（HTTP 层已先校验，正常不会走到）；
- `listAll()` 保持全量，供 `/skills/manage` 与调试页；
- `availableSkills(userId, role)`：L4 合并后再过滤（L4 同名技能按角色名匹配；角色未列出的 L4 独有技能不出现）；未知角色 → 空列表 + `WARN`；
- 旧签名 `list()` / `availableSkills()` / `availableSkills(userId)` 保持"不过滤"（等价 `role=null`），避免改变未知调用方与既有单测的语义；HTTP 只读端点自行解析 `role`（缺省 = default，无角色配置 = null）后传入。

### 6.5 SkillInjectionService

新增重载：

```java
public String injectSkillReferences(String message, String userId, String role);
public List<String> parseSkillReferences(String message, String userId, String role);
```

- `enabledSkillNames(userId, role)`：`catalogService.list(role)` 的启用集 ∪ 该用户 L4；再按角色集过滤；
- `@Skill` 指向角色外技能：**不注入，保留原文**（与"禁用技能"同语义），不报错、不提示；
- 旧重载（`inject(message)` / `inject(message, userId)` 等）解析为 **default 角色**（无角色配置 = 不过滤），保证未改造的调用方也不会看到角色外技能；新入口显式传入会话生效角色。

### 6.6 入口与控制器

#### ChatStreamController

`ChatRequest` 增加 `String role`：

```java
public record ChatRequest(String message, String userId, String sessionId,
                          List<String> fileIds, String model, String role) {}
```

处理顺序（与 model 完全同构）：

1. `role != null` 时 trim；非空先 `roleCatalog.validateSelectable(role)`，非法 → `errorSSE("unknown_role: " + role)` / `roles_not_configured`，**不写库**；
2. 仅在 `role` 字段提供时写入：`sessionUserStore.upsert(sid, uid)` 之后 `upsertRole(sid, role)`（空串 = 清除）；字段缺省 = 保持已存值；
3. 解析 `effectiveRole = roleCatalog.effectiveRole(sid)`，传给 `injectSkillReferences(message, uid, effectiveRole)`；
4. 中间件在 turn 内再次按 sid 读库，读到同一值。

#### ThreadController

- `GET /threads/{sid}` 响应增加 `role`（生效角色名；无角色配置为空串）；
- `PATCH /threads/{sid}` 的 `PatchRequest` 增加 `role`：
  - `null` = 不改变；
  - `""` = 清除，回落 default；
  - 非空 = 校验后写入；
  - 非法 → 400 `{"error":"unknown_role","message":"unknown_role: x"}`；
  - 响应增加 `role`。

#### A2AController

`normalizeMessageSendBody` 已把顶层参数挪入 `message.metadata`；新增：

- 从 `message.metadata.role` 提取角色；兼容旧客户端把 `role` 放在 `params` 顶层时，在 `normalizeMessageSendBody` 中一并挪入 `message.metadata`（与 `userId`/`sessionId` 同处理）；
- 有值时先校验，再随 `recordSessionUser` 一起 `upsertRole`；非法 → JSON-RPC `-32602`（`error.data.role`），不转发给 SDK；
- 无值 = 不改变会话角色（沿用已存值/default）。

#### ToolController / SkillManageController

| 端点 | 行为 |
|------|------|
| `GET /skills` | 新增 `?role=`；缺省 = default；未知 → 400 |
| `GET /skills/available` | 新增 `?role=`；与 `X-User-Id` 一起传给 `availableSkills(userId, role)` |
| `GET /skills/parse-refs` | 新增 `?role=`；与 `@Skill` 注入同口径 |
| `GET /skills/manage` | 不变（全量 + 启停状态） |

#### AgentCardController / InfoController / DebugApiController

- `GET /.well-known/agent-card.json`：`skills` 用 **default 角色**的可见集（与无 role 参数的调用方行为一致）；新增 additive 字段 `roles`（`name/label/description/default`），无角色配置时不出现；
- `GET /metadata`：增加 `roles` 数组；
- `GET /debug/config/oaf`：增加 `skillRoles`（原始解析结果 + 生效 default + `unassignedSkills`）。

#### 新增 `GET /roles`

供前端/调试页展示角色选择器：

```json
{
  "enabled": true,
  "default": "support",
  "sharedSkills": ["common-format"],
  "roles": [
    {
      "name": "support",
      "label": "客服",
      "description": "退款、工单与订单查询",
      "skills": ["refund-policy", "order-query"],
      "resolvedSkills": ["refund-policy", "order-query"],
      "allSkills": false
    }
  ],
  "unassignedSkills": []
}
```

无角色配置：`{"enabled":false,"default":"","sharedSkills":[],"roles":[],"unassignedSkills":[]}`。

---

## 7. 切换语义与一致性

| 场景 | 行为 |
|------|------|
| 新会话不传 role，包有角色配置 | 使用 default 角色；`session_user.role` 保持空串 |
| 新会话传 role | 校验 → 写入 `session_user.role` → 本轮生效 |
| 续聊不传 role | 读 `session_user.role`；空串 → default |
| 续聊传新 role | 覆盖写入，**下一轮 turn** 生效；当前 turn 不受影响 |
| `PATCH /threads/{sid} {role:""}` | 清除，回落 default |
| 未知 role | chat：error SSE；PATCH：400；A2A：-32602；均不改变已存值 |
| 包无角色配置但请求带 role | 拒绝（`roles_not_configured`），不静默忽略 |
| 包 reload 后已存 role 不存在 | 回落 default + `WARN`，会话不中断 |
| 同一会话两个副本并发 | 每 turn 实时读 `session_user.role`，最后写入者生效；turn lease 已保证同会话 turn 串行，无额外锁 |
| 角色切换后的历史 | 不重写、不裁剪；模型仍能看到此前轮次内容。需要严格隔离时新建会话 |
| 运行中的 turn 被 PATCH 切换 | 当前 turn 已解析的角色不变；下一 turn 生效（与 model 同语义） |

---

## 8. API 契约汇总

### 8.1 `GET /roles`

见 §6.6。无鉴权，与既有 `/skills` 同级。

### 8.2 `POST /threads/chat`

```json
{
  "message": "帮我查一下订单 O-123",
  "sessionId": "abc-123",
  "role": "support"
}
```

- `role` 可选；缺省/`null` = 不改变会话角色；
- `""` = 清除，回落 default；
- 非法值 → SSE `{"type":"error","error":"unknown_role: x"}`。

### 8.3 `PATCH /threads/{sessionId}`

```json
{ "role": "ops" }
```

响应：

```json
{ "session_id": "abc-123", "title": "", "model": "", "role": "ops" }
```

### 8.4 `GET /threads/{sessionId}`

响应新增 `"role": "ops"`（生效角色；无角色配置为 `""`）。

### 8.5 A2A `message/send` / `message/stream`

```json
{
  "method": "message/send",
  "params": {
    "message": {
      "metadata": { "sessionId": "abc-123", "role": "support" }
    }
  }
}
```

- `role` 写入该会话的 `session_user.role`；
- 未知 role → JSON-RPC `-32602`。

### 8.6 技能读取端点

| 端点 | `role` 参数 |
|------|------------|
| `GET /skills?role=` | 可选；缺省 default |
| `GET /skills/available?role=` | 可选；缺省 default |
| `GET /skills/parse-refs?role=` | 可选；缺省 default |
| `GET /skills/manage` | 无（全量） |

### 8.7 错误码

| 错误 | HTTP / SSE / JSON-RPC | 含义 |
|------|----------------------|------|
| `roles_not_configured` | 400 / error SSE / -32602 | 包未提供 `skill-roles.yaml`，但请求指定了角色 |
| `unknown_role` | 400 / error SSE / -32602 | 角色名非法或未声明 |
| `invalid_role_name` | 400 | 角色名不匹配 `^[a-z][a-z0-9-]{0,31}$`（可由 `unknown_role` 统一表达，实施时二选一） |

---

## 9. 兼容与灰度

| 场景 | 行为 |
|------|------|
| 存量包（无 `skill-roles.yaml`） | 与当前完全一致：全部技能、无额外 DB 读取（`hasRoles=false` 快速返回） |
| 存量会话 | `role=''`；包声明角色时按 default 角色载入（预期行为变化，随包发布生效） |
| 控制台生成的包 | `agentgen` 不生成 `skill-roles.yaml` → 存量行为；手工/在线编辑加入文件后启用 |
| 包在线编辑 | AGENTS.md/skills 重建时 `skill-roles.yaml` 作为普通文件保留；编辑技能名后旧引用会落入 `unassignedSkills`/未知引用，`GET /roles` 可见 |
| 包 reload | `skill-roles.yaml` 的 mtime/size 变化下一轮生效；已存未知角色回落 default |
| 多副本 | 角色状态在 `session_user`；配置在 PVC（各副本同挂载）；无进程内状态 |
| 回滚 | 删除 `skill-roles.yaml` 即回到"全部技能"；`session_user.role` 列保留无副作用 |

---

## 10. 安全与边界

### 10.1 角色不是授权边界

- 任何能访问业务入口的调用方都可以选择任意**已声明**角色；
- 角色只控制"模型能看到哪些技能"，不控制工具、MCP、文件系统、子 Agent；
- 需要"某用户只能选某角色"时，应在网关/控制面增加角色授权映射（后续能力，不在本期）。

**fail-soft 选择**：配置文件损坏/读库失败时回落默认角色或全部技能，而不是拒绝服务。理由：角色分组是上下文收敛能力，fail-closed 会让已发布服务在配置小错误时整体不可用；如果未来把角色用作安全边界，必须改为 fail-closed 并单独评审。

### 10.2 沙箱模式的文件可见性

- SDK 投影会把 `/config/skills`（以及 L4 物化结果）写进容器 `/workspace/skills`；
- `SkillFilter` 只影响 `<available_skills>` 与 `load_skill_through_path`，**不删除文件**；
- 沙箱内具备文件/Shell 工具时，模型理论上可读取其他角色的 `SKILL.md`；
- 因此角色是**上下文/行为边界，不是文件隔离边界**。硬隔离需要：按角色拆服务/拆包，或 SDK 投影层支持 allowlist（当前 2.0.3 无公开钩子，列为后续评估）。

### 10.3 与 L4 / 禁用语义的交互

| 来源 | 角色模式下行为 |
|------|----------------|
| L2 包内技能 | 仅角色组 + shared 可见 |
| L4 个人技能（同名） | 角色组列出该名字时可见，内容 L4 优先 |
| L4 个人技能（角色未列出的独有名字） | 不可见（除非角色 `*`） |
| 禁用技能（L2 `.skill-states.json` / L4 `.disabled`） | 始终从角色组中剔除（禁用优先） |
| 动态新增技能 | 角色显式列出则可见；`*` 角色自动可见；未列出则不可见 |

### 10.4 未知角色与配置损坏

- 请求未知角色：拒绝，不静默回落（避免"以为切了角色、实际用了默认组"）；
- 已存角色在 reload 后消失：回落 default + `WARN`（不中断会话）；
- 配置文件损坏：`hasRoles=false` + `WARN`（回到存量全部技能）。

---

## 11. 可观测性

| 位置 | 内容 |
|------|------|
| `SkillRoleFilterMiddleware` | `DEBUG`：`[skill-role] sid={} role={} skills={}`；`WARN`：未知已存角色、配置解析失败、DB 读取失败 |
| `ChatStreamController` | `INFO`：`[chat] role: sid={}, role={}, source=request/session/default` |
| `GET /roles` | 角色清单、default、shared、`unassignedSkills` |
| `GET /debug/config/oaf` | `skillRoles` 原始快照 + 解析结果 |
| `GET /metadata` | `roles` 数组 |
| 日志约束 | 不记录技能内容；角色名是配置键，可记录 |

---

## 12. 测试计划

### 12.1 单测

| 类 | 覆盖 |
|----|------|
| `RoleCatalogServiceTest` | 正常解析；default 缺省/非法；重名/非法名；`*` 混用拒绝；shared 去重；空角色；配置缺失；YAML 损坏降级；mtime+size 缓存刷新；角色数/技能数截断 |
| `SkillRoleFilterMiddlewareTest` | 显式角色 → `only`；`*` → 不设置；空组 → `none`；无配置 → 不设置；未知已存角色 → default；DB 失败 → 默认；`ctx` 已有 filter 时不覆盖 |
| `SkillDisabledFilterMiddlewareTest`（改） | 有角色 filter 时 overlay 而非覆盖；无禁用名时保留角色 filter；无角色时与现状一致 |
| `SkillCatalogServiceTest` | `list(role)` 过滤 L2+L4；`list()`/`listAll()` 不变；`availableSkills(uid, role)`；禁用优先；`unassignedSkills` |
| `SkillInjectionServiceTest` | 角色内 `@Skill` 注入；角色外保留原文；L4 同名；default 角色；无角色配置回归 |
| `SessionUserStoreTest` | `upsertRole`/`findRoleBySession`；空串；占位行；白名单拒绝非法列 |
| `ChatStreamControllerTest` | 合法 role 持久化；未知 role error SSE 不写库；`""` 清除；无配置拒绝；新会话顺序（upsert → role） |
| `ThreadControllerTest` | PATCH role 三种语义；GET 返回 role；非法 400 |
| `A2AControllerTest` | metadata.role 持久化；非法 -32602；无 role 不改变 |
| `AgentCardControllerTest` / `InfoControllerTest` / `DebugApiControllerTest` | default 角色技能集；`roles` 字段；无配置不变 |
| `HarnessAgentFactoryTest` | 角色中间件排在禁用中间件之前（顺序回归） |

### 12.2 E2E

新增 `e2e/skill-role-e2e.sh`（集群，非沙箱 + 沙箱两档）：

1. 包内放入 `skill-roles.yaml`（support/ops/admin），发布 running；
2. `GET /roles` 断言 default、shared、roles；
3. `GET /skills?role=support` 只含 support+shared；`role=ops` 只含 ops+shared；`role=admin` 全量；
4. `POST /threads/chat {role:"support"}`，用 mock/真实模型断言 `<available_skills>` 或 `load_skill_through_path` 只能看到 support 组；
5. `PATCH /threads/{sid} {role:"ops"}` 后下一轮切组；
6. `@ops-only-skill` 在 support 轮次不注入，ops 轮次注入；
7. 未知角色：chat error / PATCH 400 / A2A -32602；
8. 重启 Pod 后同会话角色仍生效；
9. 多副本：副本 A 切换角色，副本 B 续聊仍按新角色过滤（`E2E 多副本` 组扩展）；
10. 包 reload（删除/新增角色）后已存未知角色回落 default；
11. 无 `skill-roles.yaml` 的存量包回归（全部技能）；
12. `SANDBOX=1`：`<available_skills>` 过滤成立；文件可见性按 §10.2 记录为已知边界（不做硬隔离断言）。

### 12.3 回归门禁

- `mvn test` 全量通过；
- `E2E 核心` / `E2E 多副本` / `E2E 沙箱` 原有断言不回归；
- `e2e/skill-role-e2e.sh` 纳入非必需 job（先观察），稳定后按需转必需。

---

## 13. 实施拆分（文件级）

### Phase 1（核心，单 PR）

| 文件 | 改动 |
|------|------|
| `agent-framework/src/main/resources/db/migration/V15__session_user_role.sql` | 新增 `session_user.role` |
| `service/SessionUserStore.java` | 白名单加 `role`；`upsertRole` / `findRoleBySession` |
| `service/RoleCatalogService.java`（新） | 解析 `skill-roles.yaml`、缓存、`resolve`/`skillNames`/`validateSelectable`/`effectiveRole` |
| `service/SkillRoleFilterMiddleware.java`（新） | per-call `SkillFilter.only` |
| `service/SkillDisabledFilterMiddleware.java` | 改为 `existing.overlay(disable(...))` |
| `service/HarnessAgentFactory.java` | 装配角色中间件（禁用中间件之前） |
| `service/SkillCatalogService.java` | `list(role)` / `availableSkills(userId, role)` |
| `service/SkillInjectionService.java` | `inject/parse` 角色重载 + `enabledSkillNames` 过滤 |
| `controller/ChatStreamController.java` | `ChatRequest.role`、校验/持久化、注入传角色 |
| `controller/ThreadController.java` | `PatchRequest.role`、GET 返回 role |
| `controller/A2AController.java` | metadata.role 提取/校验/持久化 |
| `controller/ToolController.java` | `/skills` 的 `?role=` |
| `controller/SkillManageController.java` | `/available`、`/parse-refs` 的 `?role=` |
| `controller/AgentCardController.java` | default 角色技能集 + `roles` 字段 |
| `controller/InfoController.java` | `/metadata` 增加 roles |
| `controller/DebugApiController.java` | `/config/oaf` 增加 skillRoles |
| `controller/RoleController.java`（新，或并入 ToolController） | `GET /roles` |
| 对应测试类 | 见 §12.1 |

### Phase 2（后续，不在本期）

- 沙箱投影 allowlist / L4 物化按角色裁剪（降低容器内文件面）；
- 本地子 Agent 的 `SkillFilter` 传递验证与补齐；
- 控制面：发布向导角色编辑、agentgen 生成 `skill-roles.yaml`、技能中心按角色打组；
- 按用户/租户限制可选角色（授权）；
- 角色级工具/MCP/子 Agent 过滤、角色专属提示词。

---

## 14. 备选方案与不采纳理由

| 方案 | 不采纳理由 |
|------|-----------|
| `skills/{role}/...` 目录分层 | SDK `FileSystemSkillRepository` 以一级目录为技能，嵌套角色层会改变技能名解析；L4 命名空间、沙箱投影、@Skill 名称全部要改；且同一技能跨角色复用会重复拷贝 |
| 每个角色一个 Agent 实例/仓库 | 会话内切换要重建 Agent；L4/沙箱/会话状态全部要按角色复制；内存与启动成本高 |
| builder 级 `SkillFilter`（构造时固定） | 只能在 Agent 重建时切换，不满足"会话内切换"；reload 成本高 |
| 只靠提示词约束"只用本角色技能" | 模型仍可 `load_skill_through_path` 加载其他技能，不可执行 |
| 把角色写进 AGENTS.md frontmatter | 控制面 `deriveFrontmatter` 重写 AGENTS.md 时丢字段（§3.1 D1）；独立文件对控制面零侵入 |
| 角色状态只放 `RuntimeContext` | turn 级、不跨请求/副本；续聊与多副本都会丢 |
| 物理删除/隐藏其他角色技能文件 | 破坏 L2 动态加载与沙箱投影；角色切换要重新同步文件；且不是安全边界 |

---

## 15. 验收标准（DoD）

1. 包内 `skill-roles.yaml` 声明 support/ops/admin，发布 running；
2. `GET /roles` 返回正确 default/shared/roles；
3. support 会话的 `<available_skills>` 与 `load_skill_through_path` 仅含 support+shared；ops/admin 同理；
4. `PATCH /threads/{sid}` 切换角色后下一轮生效；重启与多副本续聊仍生效；
5. `@Skill` 注入与 `/skills/available`、`/skills/parse-refs` 与模型侧一致；
6. 禁用技能在角色组内仍被剔除；
7. 未知角色在 chat/PATCH/A2A 三条入口均被拒绝且不改变已存值；
8. 无 `skill-roles.yaml` 的包行为与当前完全一致；
9. 配置损坏降级为无角色（全部技能）+ `WARN`；reload 后已存未知角色回落 default，服务不中断；
10. 单测全绿 + `e2e/skill-role-e2e.sh` 全绿 + 既有 E2E 组不回归；
11. 架构强约束核对：角色状态在共享存储（`session_user`），无进程内状态、无 `sessionAffinity`、无控制面写入业务资源。

---

## 16. 开放问题

1. **本地子 Agent 的技能可见性**：`SkillFilter` 是否随 SDK 子 Agent 调用传播，需实施时用探针/单测确认；未传播则在本地子 Agent 链路补一次 overlay（不改变角色语义），或明确记录"子 Agent 仍可见全部技能"。
2. **`GET /skills` 无 role 参数时的默认**：本文取 default 角色（与无 role 调用方的实际行为一致）；若前端希望"全部"，改走 `/skills/manage`。
3. **角色名大小写**：本文按小写 kebab 严格匹配；如需大小写不敏感，在 `validateSelectable` 归一化后再匹配。
4. **角色切换是否注入系统提示**：本期不做。若后续需要"当前角色：客服"的显式提示，可在 `SkillRoleFilterMiddleware` 追加一条 system 消息，但会改变历史渲染，需单独评审。
