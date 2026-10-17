# M8 · 工作空间资产余页（Skill / 记忆 / 凭证 / 模板 / 成员 / 占位页）

| | |
|---|---|
| 依赖 | M2（骨架）；凭证作用域联动 M3/M4 |
| 对应设计 | §8.4 资产管理、§3.2 映射行、§13 决议 7 |
| demo 对照 | `c/skills`、`c/memory`、`c/credentials`、`c/templates`、`c/members`、`c/knowledge`、`c/obs` |
> **实装与验收**：页面实拍 [../screenshots/console-split/12-assets-credentials.png](../screenshots/console-split/12-assets-credentials.png)（画廊：[../screenshots/console-split/README.md](../screenshots/console-split/README.md)）；
> e2e 套件 [../../e2e/console-split/m8-workspace-assets.sh](../../e2e/console-split/m8-workspace-assets.sh)（kind 集群实跑 PASS，2026-10-17；聚合入口 [run-all.sh](../../e2e/console-split/run-all.sh)）。

> **完成定义（DoD，硬性门槛）**：本模块标记完成必须**单测 + e2e 双绿**——①单测：apikey 签发格式/哈希/作用域/吊销过期惰性判定等全绿（`cd backend && make test`）+ `cd frontend && npm run lint && npm run build`；②e2e（脚本 workspace-assets）：API Key 签发（明文仅一次）/作用域过滤/吊销即时生效、成员角色变化即时生效、Skill L4 增删对调试即时生效、模板「去导入」链路；③§9 验收清单全勾。PR 附 CI run 与 e2e 证据链接，缺一不可。

## 1. 范围

六个页面 + API Key 完整签发链路（M0 只建了表）。全部为用户侧（凭证页含 workspace 级 CRUD）。

## 2. 凭证管理（API Key，本模块核心后端增量）

### 2.1 端点契约

```
GET  /api/v1/console/workspaces/:ws/api-keys
  → {data:[{id, name, keyPrefix, agentId?, agentName?, status, lastUsedAt, expiresAt, createdAt}]}
POST /api/v1/console/workspaces/:ws/api-keys     （owner/admin）
  {name, agentId?, expiresInDays?}
  → 201 {data:{id, name, keyPrefix, plaintext}}   // plaintext 仅此一次返回：ak-{8 随机}-{24 随机 hex}
     // KeyHash=sha256(plaintext)；KeyPrefix=ak-前 8 位
DELETE /api/v1/console/workspaces/:ws/api-keys/:id   // 吊销（status=revoked，行保留审计）
```

- 作用域：`agentId` nil = 空间内全部 Agent（M0 表已留列）；
- 校验/生效：**数据面校验在 M9（P2 网关）**；一期仅身份标识与控制台内调用记账（demo `p6` 警示条原文已注明 §5.4 二期生效）——本模块交付签发/列表/吊销 + 明文一次性展示。
- 审计：签发/吊销写 service_events（M1 词表 `api_key.issue/api_key.revoke`）。

### 2.2 前端：凭证管理页（`assets/credentials/page.tsx` 新建）

对照 demo `c/credentials`：表格（名称/Key 前缀 mono/作用范围（Agent 名或「空间内全部 Agent」）/过期时间/最近使用/状态（启用·已过期）/吊销·删除）+「签发凭证」弹窗（名称/作用范围下拉（全部 + 各 Agent）/有效期天数）+ 签发成功**一次性明文弹窗**（复制按钮 + 「关闭后不再可见」警示）+ 底部说明条（服务级敏感 env 由平台自动路由 Secret，不在此管理）。

Agent 详情 Tab6（M4 留位）：过滤 `agentId=当前` 的列表 + curl 示例块（endpoint 真实值替换）。

## 3. Skill 管理（口径修订 2026-10-17：OAF 包配置技能清单）

> **口径修订**：页面不再涉及 L4 用户技能/运行时分级概念——技能分级是 Agent 运行时的加载概念，
> 本页只做 **OAF 包配置技能**的展示与查询。技能的新增/修改/删除 = Agent 编辑页（copy-on-write
> 生成新版本包）或包编辑器，页面提供入口而非直接操作。

### 3.1 页面（assets/skills/page.tsx 重写）

- 数据源：`GET /console/workspaces/:ws/skills?keyword=`（新增端点）——遍历空间可见包
  （公共 workspace_id=0 + 本空间，决议 7），聚合两类条目：
  - **frontmatter skills 声明**（`declared: true`）：解析包 manifest 的 skills 段（name/description）；
  - **包内 skills/ 目录扫描**（`declared: false`）：`skills/<name>/SKILL.md` 的 frontmatter
    description 字段（缺失取首个正文行）——目录是事实来源，声明是意图，未声明也列出；
- 按包分组展示：包头（slug/版本/公共标记/被引用服务/「包编辑器」与「经 Agent 编辑添加技能」入口）+
  技能表（名称 mono/描述/来源 Badge）；关键字过滤（技能名/描述）+「仅看 frontmatter 声明」开关；
- 原上传/停用/删除操作与「所选 Agent 直连 L4」交互全部移除（M8 §10 对应用例作废）。

### 3.2 实现口径

- 后端：`SpaceSkills` 聚合（consoleview.go）+ `GET /console/workspaces/:ws/skills` 路由；
  引用标注 = 空间内运行包 id 反查服务 displayName；
- 运行时技能分级（L1-L4）仍由 agent-framework 维护，零改动。

## 4. 记忆空间

页面（`assets/memory/page.tsx` 新建，对照 demo `c/memory`）：

- 顶部 Agent 下拉；左卡「用户 u-{me} · 会话记忆」：`GET /agent/{short}/threads?userId=u-{me}` 聚合（标题 + 消息数 + 最近时间），一期**只读摘要**（每会话首条助手消息截断展示），编辑/清除策略远期；
- 右卡「API 调用身份」：`u-{keyPrefix 对应身份}`——一期无身份映射（M9 后 introspect 才有 api_key→user），**渲染为占位说明**（数据面身份记账二期开放），不造假数据；
- 页脚注（demo 原文）：存储位置 = 服务 checkpoint 库（session_user / agent_state），每服务独立库名，跨空间不共享。

## 5. Agent 模板页

页面（`agents/templates/page.tsx` 新建，对照 demo `c/templates`）：

- 数据：`api.console.templates()`（M2 已交付：workspace_id=0 + is_template）；
- 卡片：官方徽标/描述/被引用次数/版本 tag/「去导入」按钮 → 跳创建页 import 模式并预选该包（派生 = 既有 `POST /packages/:id/versions` copy-on-write，M3 §2.5 已通）；
- 副标题（demo 原文）：不作为创建入口，可经「导入 OAF 包」派生副本。

## 6. 成员管理

页面（`settings/page.tsx` 新建，对照 demo `c/members`）：

- 表格（成员（「你」tag）/空间角色/平台角色/加入时间/操作：改角色·移除——owner 行无操作）；
- 「邀请成员」弹窗：从 admin 建好的用户中选（`api.admin.listUsers`——member 不可见 admin 端点 → backend 新增薄端点 `GET /console/workspaces/:ws/invitable-users`（owner/admin，返回 id+displayName，仅未加入者））+ 角色选择；
- 页脚注（demo 原文）：owner/admin 可发布与改资产，member 只读 + 调试。

## 7. 占位页（知识库 / 观测大盘）

- `assets/knowledge/page.tsx`：demo `c/knowledge` 空态（图标 + 「知识库能力规划中」+ 一期可经 MCP 连接器接入外部检索（工具管理页可见）+ 「先看工具管理」按钮）；
- `observability/page.tsx`：demo `c/obs` 空态（「一期不做 · 指标与 Trace 后续统一从可观测系统（OTel）接入」+「打开 eval-studio」外链按钮（env `EVAL_STUDIO_URL`，默认 :18400））；侧导航该项带「规划中」小标（demo `soon` 样式）。

## 8. 文件清单汇总

| 文件 | 动作 |
|------|------|
| `internal/handler/console.go` | 修改：api-keys CRUD + invitable-users |
| `internal/service/consoleview.go` | 修改：api_keys 逻辑（签发/校验 hash/过期标记） |
| `src/app/(console)/console/assets/{credentials,skills,memory,knowledge}/page.tsx` | 新建 ×4 |
| `src/app/(console)/console/agents/templates/page.tsx`、`settings/page.tsx`、`observability/page.tsx` | 新建 ×3 |
| `src/app/(console)/console/agents/[id]/page.tsx` | 修改：Tab6 接真数据 |
| `src/lib/api.ts` | 修改：`api.console.{apiKeys, invitableUsers}` |

## 9. 验收标准

- [ ] API Key 签发：明文仅出现一次；列表只见前缀；吊销后状态变更且审计有记录；过期（expiresAt 到点）列表显示「已过期」（查询时惰性判定，不做后台任务——红线：无后台写）。
- [ ] 凭证作用域：绑定单 Agent 的 Key 只出现在该 Agent 详情 Tab6 与筛选后的凭证页。
- [ ] Skill：所选 Agent 的 L4 增删在调试对话即时生效（既有机制回归）；包级技能只读正确。
- [ ] 记忆页只读聚合正常、空态优雅；模板页「去导入」链路通（派生新版本包 → 发布）。
- [ ] 成员：邀请/改角色/移除生效；owner 保护。
- [ ] 知识库/观测大盘占位页与导航「规划中」标注一致。

## 10. 测试要点

- `apikey_test.go`：签发格式与前缀、sha256 落库、明文不回读（列表无 hash 字段）、吊销/过期状态惰性判定、作用域过滤。
- 手工：双账号验证成员权限变化即时生效（role 查询为请求期裁决）。
- ~~Skill L4 增删对调试即时生效~~（口径修订后作废：本页只做包配置技能展示，无 L4 操作）。
