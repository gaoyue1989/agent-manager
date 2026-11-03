# 会话级技能列表（POST /threads/chat 传 skills）— 设计文档

**日期**：2026-11-03
**状态**：已实施 + 部署验证
**范围**：agent-framework 运行时；控制面（backend/frontend）零改动
**取代**：早前「skill-roles.yaml 角色分组」方案（未实施，已废弃）

---

## 1. 背景与目标

一个 OAF 包只有一套平铺技能目录 `/config/skills`，SDK 每轮把仓库里的全部技能渲染进
`<available_skills>`。当一个 Agent 同时承载多条业务线（客服/运维/审核）时：

1. **上下文膨胀**：与本次任务无关的技能也进提示词；
2. **误用**：模型可能加载不属于当前职责的技能；
3. **无法按请求收敛**：调用方最清楚"这一轮该用哪些技能"，但运行时没有入口接收。

本设计给 **`POST /threads/chat` 增加 `skills` 字段**：调用方在请求中直接声明本轮可用技能列表，
运行时把模型侧技能可见集收敛为该列表。列表**仅对本轮生效**（不落库、不跨轮沿用），
不引入包内声明文件、不引入会话状态、控制面零改动。

### 1.1 目标

- `POST /threads/chat {skills:[...]}` 与 A2A `message.metadata.skills` 三处（chat/A2A/只读视图）同口径；
- 模型侧 `<available_skills>`、`load_skill_through_path`、`@Skill` 注入、`/skills*` 视图按列表收敛；
- 缺省行为与存量完全一致；被禁用技能仍剔除（禁用优先）；
- 多副本/重启无状态差异（列表随本轮消息传递，不落任何存储）。

### 1.2 非目标

- 不做角色/授权模型：列表是上下文边界，不是安全边界；能调用 chat 的调用方可以传任意技能名；
- 不做跨轮记忆：本轮未传即恢复全部技能（需要固定清单的调用方每轮都传）；
- 不做工具 / MCP / 子 Agent 过滤、不做文件物理隔离（沙箱内技能文件仍可见）；
- 不做控制面 UI（前端/发布向导不感知该字段，属可选高级用法）。

---

## 2. 契约

### 2.1 语义（唯一权威表）

| 请求值 | 模型侧可见技能 | 说明 |
|--------|----------------|------|
| 字段缺省 / `null` | 全部启用技能 | 存量行为逐字节不变 |
| `[]`（显式空数组） | 无 | 模型看不到任何技能；`@Skill` 全部不注入 |
| `["a","b"]` | 仅 `a`、`b`（存在且未禁用者） | 未知名忽略（WARN 级 debug）；已禁用剔除 |

- **仅本轮生效**：不写 `session_user`、不写消息历史附加状态；下一轮未传即恢复全部。
- **未知名忽略**：不校验、不报错；白名单中的未知名字在技能仓库中匹配不到任何技能。
- **禁用优先**：被禁用的技能即使出现在列表里也不可见（由禁用中间件的 overlay 剔除）。
- **L4 个人技能**：列表按名字匹配；用户在 L4 中存在同名技能时照常可见/注入（内容 L4 优先）。

### 2.2 API

```jsonc
// POST /threads/chat（/agent/{name}/threads/chat 等价）
{
  "message": "帮我查一下订单 O-123",
  "sessionId": "abc-123",
  "model": "01J8XK...",
  "skills": ["refund-policy", "order-query"]   // 可选；缺省=全部，[]=无技能
}
```

A2A `message/send` / `message/stream`：

```jsonc
{"jsonrpc":"2.0","id":1,"method":"message/send","params":{
  "message":{"role":"user","parts":[{"kind":"text","text":"hi"}],
    "metadata":{"sessionId":"abc","userId":"u1","skills":["a","b"]}}}}
```

兼容：顶层 `params.skills` 与旧格式 `params.metadata.skills` 由 `normalizeMessageSendBody`
归一进 `message.metadata.skills`（与 userId/sessionId 同处理）。

只读视图（均支持逗号分隔，缺省/空串 = 不过滤）：

| 端点 | 参数 |
|------|------|
| `GET /skills` | `?skills=a,b` |
| `GET /skills/available` | `?skills=a,b`（与 `X-User-Id` 合并 L4 后裁剪） |
| `GET /skills/parse-refs` | `?skills=a,b`（与 `@Skill` 注入同口径） |
| `GET /skills/manage` | 无（全量 + 启停状态） |

> 查询参数无法表达"显式空列表"（`?skills=` 视为缺省）；"空 = 无技能"仅存在于 chat 请求体与
> A2A metadata。

---

## 3. 传递链路

```
调用方
  │ POST /threads/chat {skills}            A2A message.metadata.skills
  ▼
入口（ChatStreamController / SDK A2A server）
  │ skills → 本轮用户消息 metadata["skills"] = List<String>（显式空列表也写入）
  ▼
Agent turn
  │ SkillFilterMiddleware.onAgent
  │   ├─ 取 input.msgs() 最后一条 USER 消息（不回溯历史）
  │   ├─ metadata 无 "skills" → 直通（全部技能）
  │   ├─ 空列表 → SkillFilter.none()
  │   └─ 非空 → SkillFilter.only(去重后名单)
  │ SkillDisabledFilterMiddleware.onAgent
  │   └─ ctx.put(existing.overlay(SkillFilter.disable(禁用集)))
  ▼
SDK HarnessSkillMiddleware
  └─ <available_skills> / load_skill_through_path 只看到「本轮名单 − 禁用集」
```

设计要点：

- **为什么走消息 metadata**：中间件拿不到 HTTP 请求对象，但 `AgentInput.msgs()` 携带本轮用户
  消息（UiContextInjectionHook 已用同一机制读 metadata）；列表随消息天然"仅本轮"，
  不引入进程内缓存/共享存储，无多副本一致性问题。
- **只认最后一条 USER 消息**：历史消息若带同名键（含旧轮次注入的 metadata）不参与本轮判定。
- **为什么 per-call SkillFilter**：SDK 每轮以 `builderFilter.overlay(ctx.filter)` 计算生效过滤；
  本中间件写 `only/none`（非 overlay）时 SDK 整体采用，不重建 Agent、不切目录，
  L2 动态重扫 / L4 合并 / 沙箱投影全部复用既有链路。
- **顺序**：`SkillFilterMiddleware` 必须排在 `SkillDisabledFilterMiddleware` 之前——
  后者以 `existing.overlay(disable(...))` 叠加，先立白名单再减禁用集（禁用优先）。

---

## 4. SkillFilterMiddleware

```
if (ctx == null || input == null) return next;
lastUser = input.msgs() 中最后一条 role=USER 的消息
if (lastUser == null || lastUser.metadata 无 "skills") return next;     // 缺省 = 全部
names = 归一（去空白/去重）
filter = names.isEmpty() ? SkillFilter.none() : SkillFilter.only(names)
existing = ctx.get(SkillFilter.class)
ctx.put(SkillFilter.class, existing != null ? existing.overlay(filter) : filter)
```

- `SkillFilter.only` 内部 `Set.of` 拒绝重复，中间件先去重；
- 任何异常（metadata 畸形等）→ WARN + 不设置 filter（回落全部技能，fail-soft，不阻断对话）；
- 无额外依赖（不读目录/DB），未传 skills 时每轮成本为一次消息列表遍历。

## 5. 视图与注入

- `SkillCatalogService.list(List<String>)`：合并视图（目录 ∪ 声明 − 禁用）后按名单裁剪；
  `null` = 不过滤，`[]` = 空列表。
- `SkillCatalogService.availableSkills(userId, List<String>)`：L4 合并后裁剪（白名单外的 L4 独有技能不出现）。
- `SkillInjectionService.injectSkillReferences(message, userId, List<String>)` /
  `parseSkillReferences(...)`：`enabledSkillNames = (目录 ∪ L4 − 禁用) ∩ 名单`；
  名单外 `@Skill` 保留原文（与禁用同语义），未知名自然不在集合内。
- 旧重载（`inject(message)` / `inject(message, userId)` 等）= `null`（不过滤），
  未改造调用方（AguiRunController）行为不变。

---

## 6. 兼容与边界

| 场景 | 行为 |
|------|------|
| 不传 skills 的存量客户端 | 与当前完全一致（全部启用技能） |
| 显式 `[]` | 模型无技能可用；仍可正常对话（工具/文件不受影响） |
| 未知技能名 | 忽略（无告警阻断）；`/skills?skills=` 视图同样过滤不到 |
| 已禁用技能 | 即使列入名单也不可见/不注入（禁用优先） |
| 包 reload（技能增删） | 名单按名字匹配，下一轮自然生效（无需重启） |
| 多副本 / 重启 | 列表随本轮消息传递，无状态差异；同一会话两次请求可带不同名单 |
| 历史轮次 | 不重写、不裁剪；模型仍能看到此前轮次内容（上下文历史与技能可见集是两件事） |

**边界**：列表是上下文/行为边界，不是授权或文件隔离边界。沙箱容器内仍投影全部技能文件，
具备文件/Shell 工具时模型理论上可读取名单外技能；硬隔离需拆服务/拆包（非目标）。

---

## 7. 测试与验收（DoD）

单测：

| 类 | 覆盖 |
|----|------|
| `SkillFilterMiddlewareTest` | 名单 → only；`[]` → none；缺省 → 不设置；未知名忽略；重复/空白归一；最后一条 USER 生效；逗号字符串；existing filter 不扩权；畸形值/null 不抛 |
| `SkillDisabledFilterMiddlewareTest` | 禁用集 overlay 剔除名单内技能；无禁用名保留名单；无名单时行为不变 |
| `SkillCatalogServiceTest` | `list(名单)` / `availableSkills(uid, 名单)`；`null`/`[]` 语义；禁用优先；L4 交集 |
| `SkillInjectionServiceTest` | 名单内注入、名单外保留原文、空列表、未知名忽略、旧重载回归 |
| `ChatStreamControllerTest` | skills 写入消息 metadata（含空列表）、注入参数、未传不写键 |
| `A2AControllerTest` | message.metadata.skills 透传；顶层/旧格式归一 |
| `ToolControllerTest` | `?skills=` 白名单与空串缺省 |
| `HarnessAgentFactoryMiddlewareAssemblyTest` | 技能名单中间件排在禁用中间件之前 |

e2e（`e2e/session-skills-e2e.sh`，kind 部署验证）：

1. 发布带 3 个技能的包（无任何角色声明文件）；
2. `skills:["support-only"]` 轮次 available_skills 仅 support-only（llm-calls 证据）；
3. 下一轮不传 → 恢复全部（仅本轮生效）；
4. `skills:[]` → 模型无技能；未知名混入 → 忽略且不报错；
5. `@Skill` 名单外不注入、名单内注入；
6. 只读视图 `?skills=` 与模型侧同口径；
7. 禁用技能名单内剔除（PVC 直写 `.skill-states.json`）；
8. A2A `message.metadata.skills` 生效；
9. 双副本轮换续聊同口径（无状态，任意副本一致）。

---

## 8. 实施清单

| 文件 | 改动 |
|------|------|
| `service/SkillFilterMiddleware.java`（新） | 读本轮消息 metadata → per-call `SkillFilter.only/none` |
| `service/SkillDisabledFilterMiddleware.java` | 覆盖改 `existing.overlay(disable(...))` |
| `service/HarnessAgentFactory.java` | 装配（禁用中间件之前） |
| `service/SkillCatalogService.java` | `list(List)` / `availableSkills(userId, List)` |
| `service/SkillInjectionService.java` | `inject/parse` 的名单重载 + `enabledSkillNames` 裁剪 |
| `controller/ChatStreamController.java` | `ChatRequest.skills`、metadata 下传、注入传名单 |
| `controller/A2AController.java` | `params.skills` / 旧格式归一进 `message.metadata` |
| `controller/ToolController.java` | `/skills?skills=` |
| `controller/SkillManageController.java` | `/available`、`/parse-refs` 的 `?skills=` |
| 对应测试类 | 见 §7 |
| `e2e/session-skills-e2e.sh`（新） | 部署验证脚本 |
