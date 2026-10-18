# M0 · 身份认证与双侧拆分骨架

| | |
|---|---|
| 依赖 | 无（首个模块） |
| 对应设计 | §5 身份与鉴权、§6 数据模型与迁移、§10 前端信息架构、§12 P0 |
| demo 对照 | 登录页 `#oaLogin`（开发者/管理员双角色卡）、顶栏 `oa-top`、运维侧导航 `#navAdmin` |
> **实装与验收**：页面实拍 [../screenshots/console-split/01-login.png](../screenshots/console-split/01-login.png)（画廊：[../screenshots/console-split/README.md](../screenshots/console-split/README.md)）；
> e2e 套件 [../../e2e/console-split/m0-auth.sh](../../e2e/console-split/m0-auth.sh)（kind 集群实跑 PASS，2026-10-17；聚合入口 [run-all.sh](../../e2e/console-split/run-all.sh)）。

> **完成定义（DoD，硬性门槛）**：本模块标记完成必须**单测 + e2e 双绿**——①单测：§5 列出的 jwt/auth/migrate/router 四组测试全绿（`cd backend && make test`）+ `cd frontend && npm run lint && npm run build`；②e2e（核心 job 扩展）：登录→双侧骨架可达、存量 URL 重定向（`/services/1`→`/admin/services/1`）、四必需 job 保持全绿；③§4 验收清单全勾。PR 附 CI run 与 e2e 证据链接，缺一不可。

## 1. 范围

- backend：`users` / `workspaces` / `workspace_members` / `api_keys` 四表 + AutoMigrate + 幂等回填；`/api/v1/auth/*` 三端点；JWT 签发与校验中间件；admin 鉴权中间件。
- frontend：`/login` 页、`middleware.ts` 守卫、`(console)` / `(admin)` 双 layout 骨架、现有 8 页迁入 `(admin)` + 旧 URL 重定向。
- 不在本模块：admin/users、admin/workspaces 治理端点（M1）；console 业务端点（M2）；api_keys 签发逻辑（M8，本模块仅建表）。

## 2. 后端改动

### 2.1 文件清单

| 文件 | 动作 | 内容 |
|------|------|------|
| `internal/store/model.go` | 修改 | 新增 User / Workspace / WorkspaceMember / ApiKey 四实体（§2.2） |
| `internal/store/db.go` | 修改 | AutoMigrate 列表追加四实体 |
| `internal/store/migrate.go` | 新建 | 幂等回填（§2.3） |
| `internal/handler/auth.go` | 新建 | login / me / logout 三端点（§2.4） |
| `internal/handler/jwt.go` | 新建 | 签发与校验（HS256；claims：`sub`=username、`uid`、`role`、`exp`；默认 12h；密钥 `JWT_SECRET` env） |
| `internal/handler/middleware.go` | 修改 | 新增 `JWTAuth()` / `RequireAdmin()`；保留现有 `Auth(authToken)` |
| `internal/handler/router.go` | 修改 | 路由重组（§2.5），存量路由**一行不改**地挪进 legacy 组 |
| `cmd/backend/main.go`（或装配处） | 修改 | 启动顺序：Open(AutoMigrate) → migrate.Backfill → 路由装配；`JWT_SECRET` 为空时 fail-fast（启动期校验，非请求期） |
| 对应 `_test.go` ×4 | 新建 | 单测见 §5 |

### 2.2 数据模型（model.go 追加）

```go
type User struct {
    ID           uint       `gorm:"primaryKey" json:"id"`
    Username     string     `gorm:"size:64;uniqueIndex" json:"username"`
    PasswordHash string     `gorm:"size:128" json:"-"`          // bcrypt
    DisplayName  string     `gorm:"size:128" json:"displayName"`
    Email        string     `gorm:"size:128" json:"email"`
    Role         string     `gorm:"size:16;index" json:"role"`   // admin | user
    Status       string     `gorm:"size:16" json:"status"`       // active | disabled
    LastLoginAt  *time.Time `json:"lastLoginAt"`
    CreatedAt    time.Time  `json:"createdAt"`
    UpdatedAt    time.Time  `json:"updatedAt"`
}
func (User) TableName() string { return "users" }

type Workspace struct {
    ID          uint   `gorm:"primaryKey" json:"id"`
    Slug        string `gorm:"size:32;uniqueIndex" json:"slug"` // ws-{8 hex}，创建时随机生成
    Name        string `gorm:"size:128" json:"name"`
    Description string `gorm:"size:256" json:"description"`
    OwnerUserID uint   `gorm:"index" json:"ownerUserId"`
    Status      string `gorm:"size:16" json:"status"`            // active | disabled
    QuotaJSON   string `gorm:"type:json" json:"quotaJson"`       // {"maxAgents":20}，一期仅记录
    CreatedAt   time.Time `json:"createdAt"`
    UpdatedAt   time.Time `json:"updatedAt"`
}
func (Workspace) TableName() string { return "workspaces" }

type WorkspaceMember struct {
    ID          uint      `gorm:"primaryKey" json:"id"`
    WorkspaceID uint      `gorm:"index:idx_ws_user" json:"workspaceId"`
    UserID      uint      `gorm:"index:idx_ws_user" json:"userId"`
    Role        string    `gorm:"size:16" json:"role"`            // owner | admin | member
    CreatedAt   time.Time `json:"createdAt"`
}
func (WorkspaceMember) TableName() string { return "workspace_members" }
// uniqueIndex(workspace_id, user_id) 用 gorm tag: `gorm:"uniqueIndex:uk_ws_user"`（两列同名）

type ApiKey struct {
    ID          uint       `gorm:"primaryKey" json:"id"`
    WorkspaceID uint       `gorm:"index" json:"workspaceId"`
    AgentID     *uint      `gorm:"index" json:"agentId"`          // nil = 空间内全部 Agent（M8 用）
    UserID      uint       `json:"userId"`                       // 签发人
    Name        string     `gorm:"size:128" json:"name"`
    KeyPrefix   string     `gorm:"size:16" json:"keyPrefix"`     // ak- 前 8 位
    KeyHash     string     `gorm:"size:64;uniqueIndex" json:"-"` // sha256 hex，不存明文
    Status      string     `gorm:"size:16" json:"status"`        // active | revoked | expired
    LastUsedAt  *time.Time `json:"lastUsedAt"`
    ExpiresAt   *time.Time `json:"expiresAt"`
    CreatedAt   time.Time  `json:"createdAt"`
}
func (ApiKey) TableName() string { return "api_keys" }
```

### 2.3 幂等回填（migrate.go）

启动时执行，逐条幂等（二次启动零写入）：

1. `users` 空表 → 用 `ADMIN_INIT_USER` / `ADMIN_INIT_PASSWORD` 种子创建 admin（两者任一为空则跳过并 warn——空库无 admin 时 console/admin 不可登录，属部署配置问题，启动不失败）；
2. 不存在 `slug=default` 的工作空间 → 创建「默认工作空间」（name=default，owner=种子 admin，若有）；
3. `services.workspace_id IS NULL`（新列默认 0）的行 → 置为默认工作空间 id（对应决议 2：存量服务对普通用户不可见，admin 经纳管再分配；本期仅落归属，可见性由查询条件裁决，见 M2）；
4. `oaf_packages.workspace_id` 保持 0 = 公共包，**不回填**（决议 7）。

### 2.4 auth 端点（handler/auth.go）

```
POST /api/v1/auth/login    {username, password}
  → 200 {data:{token, user:{id,username,displayName,role}, workspaces:[{id,slug,name,role}], defaultWorkspace:{...}}}
  → 401 {message:"invalid username or password"}   // 统一文案，不区分用户不存在/密码错
  → 403 user.Status=disabled
POST /api/v1/auth/logout   → 200（无服务端动作；语义 = 前端清 cookie）
GET  /api/v1/auth/me       （JWT）→ 200 同 login 响应 data（减 token）
```

- login 成功更新 `last_login_at`；响应 Set-Cookie：`oaf_session={token}; HttpOnly; Path=/; SameSite=Lax; Max-Age=43200`（集群 http 部署不加 Secure，注释说明）。
- `me` 的 workspaces：admin 角色追加一条虚拟成员关系 `{id:0, slug:"admin", name:"平台管理员", role:"admin"}`（前端运维侧入口判定用）。

### 2.5 路由重组（router.go，关键改造）

现状 `Register` 把 `Auth(authToken)` 挂在整个 `/api/v1` 组上。重组为（**存量 handler 代码零改动，只挪挂载点**）：

```go
func Register(r *gin.Engine, core *service.Core, images [...], authToken, jwtSecret string) {
    r.Use(CORS())
    g := r.Group("/api/v1")

    // 1) 公开端点（先建组，不继承后续中间件）
    au := g.Group("/auth")
    { au.POST("/login", ...); au.POST("/logout", ...); au.GET("/me", JWTAuth(jwtSecret), ...) }

    // 2) console / admin 新面
    cg := g.Group("/console", JWTAuth(jwtSecret))        // M2 填充
    ag := g.Group("/admin", RequireAdmin(authToken, jwtSecret)) // M1 填充；AUTH_TOKEN 或 JWT-admin

    // 3) legacy 存量（行为与今天完全一致）
    if authToken != "" { g.Use(Auth(authToken)) }
    // …现有 packages/services/images/platform-config 挂载原样下沉…
}
```

注意 gin 语义：`g.Group()` 在 `Use` 之前创建的子组不继承该中间件，auth/console/admin 三个组必须在 legacy `Use` 之前创建（写单测锁住：设 AUTH_TOKEN 时 `/auth/login` 与带 JWT 的 `/console/*` 不被 AUTH_TOKEN 拦截）。

### 2.6 JWT 中间件

- `JWTAuth(secret)`：读 `Authorization: Bearer <jwt>` 或 cookie `oaf_session`（优先 header）；失败 401；通过后 `c.Set("uid"/"role"/"username")`。
- `RequireAdmin(authToken, secret)`：先试 AUTH_TOKEN Bearer（命中直接放行，`c.Set("uid",0,"role","admin")`）；否则 JWT 且 `role==admin`，否则 403。
- 登出/改密/禁用的吊销语义：一期不做服务端吊销（JWT 12h 自然过期），`disabled` 用户在 token 有效期内仍可通过 JWT——**已知取舍**，login 时拦截 + 文档记录（设计 §5.2）。

## 3. 前端改动

### 3.1 文件清单

| 文件 | 动作 | 内容 |
|------|------|------|
| `src/app/login/page.tsx` | 新建 | 登录页（demo `oa-login` 还原：左品牌区 + 右登录卡；调 `api.auth.login`，成功写 cookie 由后端 Set-Cookie 完成，前端 push 到 `/console` 或 `/admin`（admin）） |
| `src/middleware.ts` | 新建 | 守卫：无 `oaf_session` cookie → 302 `/login`；`/admin/*` 解 JWT payload（base64 decode，仅 UX）非 admin → 302 `/console`；白名单 `/login`、`/_next`、`/api`、`/agent`、`/mcp` |
| `src/app/(console)/layout.tsx` | 新建 | 用户侧骨架：顶栏（logo + WorkspaceSwitcher 占位 + 用户菜单）+ 左侧导航（demo `navConsole` 分组：概览/观测/AGENT 构建/资产管理/身份与权限）；M0 阶段子路由全为占位卡片 |
| `src/app/(admin)/layout.tsx` | 新建 | 运维侧骨架：沿用现有顶栏 + `#navAdmin` 导航（平台概览/服务管理/平台治理/平台配置）+ 顶栏「运维侧/用户侧」切换（仅 admin 可见，调 `auth/me.role`） |
| 现有 8 页 | 迁移 | 整目录挪入 `(admin)/admin/*`：`page.tsx`→`(admin)/admin/page.tsx`、`services/*`→`(admin)/admin/services/*`、`publish`、`packages`、`settings`、`assistant` 同理；**import 路径与组件逻辑零改动** |
| `src/app/page.tsx` 等旧路径 | 替换 | 根路由与旧路径改为 `redirect('/admin/...')`（保 e2e：`/services/1` → `/admin/services/1`，HTTP 307/308 均可） |
| `src/lib/api.ts` | 修改 | 新增 `api.auth = { login, logout, me }`；`request` 加 401 拦截（非 /login 页跳登录） |

### 3.2 关键交互（对照 demo）

- 登录页双角色卡为**演示专用**，实装只有用户名密码表单（demo 的「以开发者/管理员进入」不实现）。
- `(admin)` 迁移后 `assistant` 页保持 `/admin/assistant` 直连 `/agent/release-agent/*`（proxy 规则不动，e2e 断言 URL 变化由重定向兜底）。

## 4. 验收标准

- [ ] 空库启动：AutoMigrate 建四表；配置 `ADMIN_INIT_USER/PASSWORD` 后种子 admin 存在；二次启动无新增写入（单测断言 affected rows = 0）。
- [ ] login 正确/错误/禁用三分支 + Set-Cookie 属性；`me` 返回工作空间列表。
- [ ] 设 `AUTH_TOKEN` 时：`/api/v1/auth/login` 可匿名访问；`/api/v1/services` 仍要求 AUTH_TOKEN（存量行为不变）；带 JWT 访问 `/console/*` 不被 AUTH_TOKEN 拦截（路由分层单测）。
- [ ] 存量 e2e 四必需 job 全绿（旧 URL 重定向生效是关键）。
- [ ] 前端：未登录访问任意页跳 `/login`；admin 登录后可切运维侧；普通用户访问 `/admin/*` 被弹回 `/console`。
- [ ] `go vet ./... && go test ./...`、`npm run lint && npm run build` 全绿。

## 5. 测试要点（单测）

- `jwt_test.go`：签发/校验往返、过期 token 401、篡改签名 401、cookie 与 header 双通道。
- `auth_test.go`：bcrypt 校验、disabled 拦截、last_login_at 落库。
- `migrate_test.go`：种子幂等（跑两遍第二遍零写）、default 工作空间只建一次、services 回填只做一次。
- `router_test.go` 扩展：AUTH_TOKEN 与 JWT 并存时的分层放行矩阵（§2.5 表格逐格）。
