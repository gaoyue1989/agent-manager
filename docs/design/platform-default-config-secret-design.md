# 平台默认配置与敏感键 Secret 化（redis / mysql / llm / sandbox）— 设计文档

**日期**：2026-09-28　**状态**：已实施（P1 当日落地，R3 修订同日完成并经真实集群 E2E，见 §11 实施与验证记录）

> **R3 修订（2026-09-28 评审后，最终形态）**：默认配置**不经 envFrom 运行时注入**，仅作为「发布新服务 / 编辑环境变量」时的**表单默认填入**；修改平台默认配置不影响任何已发布服务（存量服务只携带自己显式配置的 env）。据此相对 R2 的变化：
> 1. Deployment envFrom 由四源回归**两源**（服务 Secret `{name}-env-secret` + 服务 CM `{name}-env`），平台 CM/Secret 对象（`oaf-platform-default-config` / `oaf-platform-default-secret`）不再创建（文中相关表格/链路描述已被取代）；
> 2. `POST /platform-config/apply-restart` 端点移除（失去存在意义），PUT 只写 DB；
> 3. 新增 `GET /api/v1/platform-config/defaults`：返回已配置默认值的平面键值表（**含敏感键明文**——默认值本就是供复制进服务的模板，展示视图 GET /platform-config 仍掩码）；
> 4. 前端：发布向导预填 defaults、详情页「填入平台默认」按钮、设置页移除重启按钮；
> 5. per-service Secret、敏感路由、sticky 三态、存量迁移机制**保持不变**（这些关乎服务自身 env 的落点，与默认配置的注入方式无关）。

> R2 修订说明（2026-09-28 评审后）：非敏感键不进 Secret，改由平台级 ConfigMap 承载；Secret 只放敏感键（`LLM_API_KEY`、`OPENSANDBOX_API_KEY`、`AGENT_REDIS_URL`、`CHECKPOINT_PASSWORD` 等）；每创建一个 agent 服务同时创建对应的 per-service Secret，服务级敏感 env 落该 Secret 而非 ConfigMap。

## 1. 背景与问题

agent-framework 的基础运行配置（LLM 系统模型、MySQL checkpoint、Redis 事件流、OpenSandbox）全部通过环境变量注入，由 `application.yml` 占位符收口到 `@ConfigurationProperties` record。当前分发方式带来三类问题：

1. **敏感明文扩散**：`LLM_API_KEY`、`CHECKPOINT_PASSWORD`、`AGENT_REDIS_URL`（可内嵌密码）、`OPENSANDBOX_API_KEY` 以明文形态出现在发布向导预填行（`frontend/src/app/publish/page.tsx:31-38`）、业务 `services.env_json`（MySQL 明文列）与 `{name}-env` ConfigMap；平台自举清单 `manifests/platform.yaml` 中 `oaf-mysql-secret` stringData 硬编码、platform-backend 的 `MYSQL_DSN` 明文 env；仓库 `.env`/`.env.secrets` 有 key 泄漏史。
2. **重复配置**：后续接入的业务 agent 大概率共用同一份 redis/mysql/llm/sandbox 配置，目前每个服务发布都要手工填一遍，改一次 key 要逐服务 PATCH。
3. **无平台级默认配置入口**：没有一个页面能集中查看/修改"所有 agent 共享的基础配置"。

## 2. 目标与非目标

### 2.1 目标

1. **敏感键 Secret 化**：模板中标记敏感的键（`LLM_API_KEY`、`OPENSANDBOX_API_KEY`、`AGENT_REDIS_URL`、`CHECKPOINT_PASSWORD` 等）一律落 K8s Secret，不进 ConfigMap、不在 API/页面回显明文。
2. **非敏感键走 ConfigMap**：非敏感默认配置落平台级 ConfigMap，保持 `kubectl` 可读、运维友好。
3. **模板化**：字段清单（分组 / 键名 / 是否敏感 / 必填 / 是否多行）由 backend 代码单一模板定义驱动；页面表单、CM/Secret 拆分渲染、服务 env 路由分类、掩码输出全部由该模板生成，新增字段只改模板一处。
4. **平台配置页面**：前端新增 `/settings` 页分组编辑默认配置，敏感字段掩码；保存即更新 DB + 集群对象，支持显式"滚动重启运行中服务"生效。
5. **每服务独立 Secret**：发布即创建 `{k8sName}-env-secret`；用户 env 中的敏感键**不再拒绝、改为自动路由**进该 Secret（取代 R1 的"硬拒"方案，消除 breaking 变更）。
6. **agent-framework 零改动**：环境变量名完全不变。

### 2.2 非目标（本期不做）

- agent-framework 配置热更新（env 为启动期绑定，改默认配置后需滚动重启生效，显式触发）；
- S3（`FILE_STORAGE_S3_*`）、OTLP headers 等其他敏感组迁移（模板可加组扩展）；
- DB 敏感值静态加密（见 §7 已知限制）；
- 任意键"标记为敏感"的前端 UI（仅 API 层支持 `secretKeys`，见 §3.6）。

## 3. 核心设计

### 3.1 配置分层与生效优先级（五层）

业务容器最终 env（K8s 语义已实测：显式 `env` 覆盖一切 `envFrom`；多个 `envFrom` 源同键**后引用者覆盖先引用者**，kind K8s 1.32 实测确认，E2E 保留断言防漂移）：

| 层 | 载体 | 内容 | 优先级 |
|---|---|---|---|
| ① 平台保留键 | 容器显式 `env`（`fixedEnv`，`objects.go:106-116`） | `AGENT_CONFIG_DIR` / `AGENT_WORKSPACE_DIR` / `SERVER_HOST` / `SERVER_PORT` / `HOST_NAME` | 最高，不可覆盖 |
| ② 服务敏感 env | `envFrom secretRef: {k8sName}-env-secret` | 该服务的敏感键覆盖 | 高 |
| ③ 服务普通 env | `envFrom configMapRef: {k8sName}-env`（现状） | 该服务的非敏感 env | 中 |
| ④ 平台敏感默认 | `envFrom secretRef: oaf-agent-default-secret` | 平台敏感键默认值 | 低 |
| ⑤ 平台非敏感默认 | `envFrom configMapRef: oaf-agent-default-env` | 平台非敏感默认值 | 兜底 |

`envFrom` 列表顺序：`[configMapRef: oaf-platform-default-config, secretRef: oaf-platform-default-secret, configMapRef: {name}-env, secretRef: {name}-env-secret]`。

- 模板路由保证：**敏感键只可能出现在两个 Secret 源，非敏感键只可能出现在两个 CM 源**（§3.6），跨源同键只沿"服务覆盖平台"方向发生，无歧义；
- **每服务 Secret 采用"引用链"而非"物化拷贝"**：平台默认值不复制进各服务 Secret；改平台默认只需刷新 1 个平台对象 + 滚动重启（apply-restart），无需扇出重渲染 N 个服务对象，事实源唯一。

### 3.2 字段模板（"模板化"的落点）

新增 `backend/internal/service/platformconfig/template.go`，全仓唯一字段定义源：

```go
type Field struct {
    EnvKey      string // 注入 Pod 的环境变量名（与 agent-framework 现有变量名严格一致）
    Label       string // 页面展示名
    Required    bool   // 清除该键时拒绝（防误清，非全局完整性检查，见 §3.3）
    Sensitive   bool   // true → 渲染进 Secret（平台级）/ 路由进服务 Secret（服务级）；页面掩码
    Multiline   bool   // JDBC URL 等长值用 textarea
    Placeholder string // 页面占位提示（不作为值）
}

type Group struct {
    Name   string // llm / mysql / redis / sandbox
    Title  string
    Fields []Field
}

var Template = []Group{...}
```

初版四组字段（**Sensitive=true 的键即"Secret 只需要"的集合**，加粗标出）：

| 组 | EnvKey | 必填 | 敏感 | 说明 |
|---|---|---|---|---|
| llm | **`LLM_API_KEY`** | ✓ | ✓ | 系统模型密钥 |
| llm | `LLM_BASE_URL` / `LLM_MODEL_ID` / `LLM_PROVIDER` | ✓ / ✓ / — | — | 端点 / 模型 / 推理方言（缺省 openai） |
| llm | `LLM_TEMPERATURE` / `LLM_MAX_TOKENS` / `LLM_ENABLE_THINKING` / `LLM_CONTEXT_LENGTH` / `LLM_REASONING_EFFORT` / `LLM_FREQUENCY_PENALTY` | — | — | 采样与推理参数，留空走框架默认 |
| mysql | `CHECKPOINT_JDBC_URL` | ✓ | — | 指向集群内 oaf-mysql |
| mysql | `CHECKPOINT_USERNAME` / **`CHECKPOINT_PASSWORD`** | — / ✓ | — / ✓ | checkpoint 库凭据 |
| redis | **`AGENT_REDIS_URL`** | ✓ | ✓ | URL 形态可内嵌密码，整体按敏感处理 |
| redis | `AGENT_REDIS_COMMAND_TIMEOUT_MS` / `AGENT_REDIS_CONNECT_TIMEOUT_MS` | — | — | 超时参数 |
| sandbox | `OPENSANDBOX_SERVER_URL` | ✓ | — | OpenSandbox Server 地址 |
| sandbox | **`OPENSANDBOX_API_KEY`** | — | ✓ | OpenSandbox 密钥 |
| sandbox | `SANDBOX_IMAGE` / `SANDBOX_TIMEOUT_MINUTES` / `SANDBOX_MEMORY_MB` / `SANDBOX_CPU_COUNT` | — | — | 沙箱资源参数 |

**明确排除 `SANDBOX_ENABLED`**：该变量在 agent-framework 是三层裁决（env 显式存在 > OAF 包 frontmatter `config.sandbox.enabled` > 默认 false，`SandboxRuntime.java:46-57`）。一旦进入平台默认配置，env 即"显式存在"，包级声明永远失效。沙箱开关仍由 OAF 包 frontmatter / 服务 env 显式决定。

模板驱动四件事：① `GET /platform-config` 返回表单 schema；② 平台 CM/Secret 按 `Sensitive` 拆分渲染；③ 服务 env 按 `Sensitive` 路由分类（§3.6）；④ 所有读路径的掩码输出。

### 3.3 存储与审计

MySQL（AutoMigrate，`internal/store/db.go:17` 追加）：

```go
// 平台默认配置事实源：键 → 值（含敏感与非敏感；渲染时按模板 Sensitive 拆分到 CM/Secret）
type PlatformConfigEntity struct {
    EnvKey    string    `gorm:"primaryKey;column:env_key" json:"envKey"`
    Value     string    `gorm:"type:text" json:"-"`
    UpdatedAt time.Time `json:"updatedAt"`
}

// 平台配置审计：只记键名与动作，不记值
type PlatformConfigEvent struct {
    ID        uint64    `gorm:"primaryKey" json:"id"`
    Action    string    `json:"action"`                 // update / apply_restart
    EnvKeys   string    `gorm:"type:json" json:"envKeys"`
    CreatedAt time.Time `json:"createdAt"`
}
```

- `services` 表新增列 **`env_secret_json`**（`type:json`）：服务级敏感键值。此后 `env_json` 只承载非敏感键；`env_secret_json` 在所有读路径掩码（GET 只回键名 + hasValue）。
- **掩码的实现机制**：`List` / `GetDetail` / `Publish` 等响应直接内嵌 `ServiceEntity` 序列化（status.go），故 `EnvSecretJSON` 字段 tag 必须为 `json:"-"`（杜绝随实体直出），掩码键集（键名 + hasValue）由响应视图层补充 `envSecretKeys` 字段；前端对敏感键的分类依据 = `GET /platform-config` 返回的 Sensitive 键集合（存量服务 `env_json` 中尚未迁移的敏感键，页面同样据此模板集合识别并掩码显示）。
- 服务级 env 变更审计复用既有 `ServiceEvent`（记动作与键名，不记值）。
- 平台配置保存语义（PUT，部分更新）：出现的键 → upsert；空串 → 删除该键；未出现 → 不变。校验：未知键 → 400；**清除必填键 → 400**（必填仅防误清，不做"全部必填键有值才能保存"的全局完整性检查——否则全新环境首次只配 llm 组会被 mysql 必填键卡死；未配置状态由 GET 的 `hasValue` 透出给页面提示）；单值 ≤ 32KB。
- 空值永不进入 CM/Secret（避免 Spring 占位符 `${VAR:default}` 遇空串 env 不回落默认值的坑）。

### 3.4 K8s 对象生命周期与 RBAC

**平台级（namespace 单例）**：

| 对象 | 类型 | 内容 |
|---|---|---|
| `oaf-platform-default-config` | ConfigMap | 模板 Sensitive=false 且有值的键 |
| `oaf-platform-default-secret` | Secret | 模板 Sensitive=true 且有值的键 |

**命名规则（防同类型碰撞）**：服务 CM 名恒为 `{k8sName}-env`、服务 Secret 名恒为 `{k8sName}-env-secret`（k8sName 恒以 `oaf-` 开头）。平台对象名**不得以 `-env` 或 `-env-secret` 结尾**，否则用户创建特定名称的服务（如 "agent-default" → `oaf-agent-default`）时会与平台对象同类型同名冲突。`oaf-platform-default-config` / `oaf-platform-default-secret` 均不满足碰撞后缀，安全。

Ensure 时机三处（幂等，参照既有 `EnsureConfigMap`）：① backend 启动（空配置也建**空对象**，保证 envFrom 引用永不 `CreateContainerConfigError`）；② 保存平台配置事务提交后；③ 每次 `applyAll` 前兜底自愈。

**服务级**：

| 对象 | 类型 | 内容 |
|---|---|---|
| `{k8sName}-env` | ConfigMap（现状） | 服务非敏感 env |
| `{k8sName}-env-secret` | Secret（新增） | 服务敏感 env；**创建服务时一律创建**（无敏感键则建空对象，保持 Deployment spec 统一，后续 PATCH 增敏感键无需改 Deployment 结构） |

- `applyAll` 中 `EnsureSecret({name}-env-secret)` 与 `EnsureConfigMap({name}-env)` 并列；`Unpublish` 保留两者（与现状 CM 一致），`Delete` 一并删除。
- 生命周期独立于服务：平台级两个对象不被 `Unpublish`/`Delete` 触碰。
- RBAC（`manifests/platform.yaml:220` rules）：platform-backend 增加 `secrets` 资源 `get/list/create/update/delete`（**现状无 secrets 权限，必须随本期一并下发**；`delete` 为服务删除时清理 `{name}-env-secret` 所必需——实施期实测发现：缺 delete 时 `Delete()` 的 `_ =` 吞掉 403 导致服务 Secret 残留；平台默认 Secret 后端从不删除，无扩散风险）。

### 3.5 更新与生效链路

```
/settings 页保存
  → PUT /api/v1/platform-config（校验 → 事务写 platform_config + event → 按 Sensitive 拆分渲染，Ensure 平台 CM + Secret）
  → 新发布/重启的服务自动携带（envFrom 引用链，无需重渲染服务级对象）
  → 运行中服务：POST /api/v1/platform-config/apply-restart 显式触发
      遍历 running / register_failed 服务，逐个执行：
      loadServiceEnv（双 map）→ EnsureDeployment（重刷 spec，含四源 envFrom）
      → RestartDeployment 打 restartedAt 注解（publish.go:229-233 / client.go:111-124）
      → asyncWaitAndRegister（Ready 后重新注册 A2A，状态回 running / 失败落 deploy_failed 或 register_failed）
      逐服务独立推进，单个失败不阻塞其余；返回受影响服务列表

服务 env PATCH（全量覆盖）
  → 按模板 Sensitive 拆分：敏感键写 env_secret_json + 重写 {name}-env-secret，非敏感键写 env_json + 重写 {name}-env
  → RestartDeployment 滚动重启（现状机制不变）
```

- apply-restart 支持 `{"serviceIds": [...]}` 可选过滤，不传 = 全部运行中服务。重启是有损动作，**不做自动重启**，前端二次确认。
- **apply-restart 必须重刷 Deployment spec 而非只打注解**：仅 patch restartedAt 不会变更 podTemplate——存量服务（升级前创建、envFrom 仍为单源）永远带不上四源引用与平台默认值。重刷 spec + 滚动重启一步到位，同时完成"新默认值生效"与"旧服务 envFrom 升级"两件事。
- **全部四条重应用路径统一收口**（Publish / UpdateEnv / Republish / StartAgain）：现 `Republish` 与 `StartAgain` 只从 `EnvJSON` 读 env（publish.go:257-260、326-328）后 `applyAll` 重建资源——必须改为经统一的 `loadServiceEnv(svc)` 同时加载 `env_json` 与 `env_secret_json` 两张 map，`applyAll` 内 Ensure 服务 Secret 与 CM 并列。否则 Republish/换包时 EnsureSecret 的覆盖语义会把服务 Secret **清空**（存量敏感覆盖静默丢失）。
- agent-framework 侧零改动；文档明确"保存后需 apply-restart 才对运行中服务生效"。

### 3.6 用户 env 校验与敏感路由（取代 R1 的"硬拒"）

`ValidateEnv`（`internal/service/env.go:17-36`）保留现有检查（键名正则 / 64 键上限 / 32KB / 保留键拒绝，64 键按 env+secretKeys 合并计）。**取消对敏感键的拒绝，改为路由**：

| 场景 | 行为 |
|---|---|
| `env` 中命中模板 Sensitive 的键 | 路由进 `env_secret_json` + 服务 Secret，**绝不写 env_json/CM** |
| `env` 中不在模板的任意键 | 进 CM（现状） |
| 请求显式携带 `secretKeys: ["MY_MCP_TOKEN"]` | 指定的任意键也路由进服务 Secret（覆盖 MCP token 等非模板敏感场景） |

服务级敏感键写入语义（PATCH）：出现且非空 = 设置；出现且空串 = **删除该键**（服务回落平台默认值）；未出现 = **保持不变**（sticky——页面重存非敏感 env 绝不会误删看不见的敏感键；与 `env` 部分的全量覆盖语义并存，文档与 MCP 工具描述中写明）。

**存量键的 PATCH 防丢失规则（自愈迁移的实现口径）**：每次 env 写入路径（含 PATCH/Republish/StartAgain）先对**旧** `env_json` 做模板分区——其中命中 Sensitive 的存量键视为服务敏感集合的现存成员参与 sticky 合并，然后 `env_json` 才被传入的非敏感部分全量覆盖。这样存量服务**任意一次** PATCH 都自动完成"敏感键 → env_secret_json + 服务 Secret"迁移（无需用户重发该键），且不可能被静默清掉；显式空串删除仍优先。

**存量自愈迁移**：老服务 `env_json`/CM 里已有的敏感键继续生效（层②③覆盖④⑤）；按上文防丢失规则，存量服务**任意一次** PATCH/republish 都自动把旧 `env_json` 中的模板敏感键迁入 `env_secret_json` + 服务 Secret 并从 CM 清除，无需用户重发该键。

### 3.7 Deployment 构造与 overlay 不变量

- `internal/k8s/objects.go`：新增平台对象名常量（`DefaultConfigCMName` = `oaf-platform-default-config` / `DefaultSecretName` = `oaf-platform-default-secret`，命名规则见 §3.4）与服务 Secret 命名（`{k8sName}-env-secret`）；`ObjectParams` 增加 `EnvSecret map[string]string`（与 `Env` 同约定：已校验、按模板分区后互斥），`Deployment` envFrom 由单源改四源（顺序见 §3.1），新增 `EnvSecret(p)` 构造服务 Secret（`EnvConfigMap` 逻辑不变——`Env` 分区后天然不含敏感键）。
- `internal/k8s/template.go` overlay 不变量同步扩展：`envFrom` 必须保留四个平台引用（现有 `{name}-env` 校验 template.go:122-131 的同款风格）。
- 保留键 `ReservedEnvKeys` 不变。

## 4. API 契约（backend/internal/handler/router.go）

平台配置（新增）：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/platform-config` | `{groups:[{name,title,fields:[{envKey,label,required,sensitive,multiline,placeholder,hasValue,value?,updatedAt}]}],updatedAt}`；**sensitive 字段永不回明文**，仅 `hasValue` 布尔 |
| PUT | `/api/v1/platform-config` | `{values:{ENV_KEY:"v"│""}}`，语义见 §3.3；返回同 GET 概要 |
| POST | `/api/v1/platform-config/apply-restart` | `{serviceIds?:[]}`；返回 `{restarted:[{id,name}],skipped:[{id,reason}]}` |

服务 env（既有端点扩展，向后兼容）：

| 端点 | 变更 |
|---|---|
| `POST /api/v1/services` | body 增加可选 `secretKeys:[...]`；`env` 中敏感键自动路由（§3.6） |
| `PATCH /api/v1/services/:id/env` | 同上；敏感键 sticky 语义（§3.6） |
| `GET /api/v1/services` / `GET /api/v1/services/:id` | 响应增加 `envSecretKeys:[{key,hasValue,updatedAt}]`；`envJson` 不再包含模板敏感键（存量数据在下次 PATCH 前原样返回，前端据模板掩码显示） |

MCP 对齐（P2，见 §9）：`get_platform_config` / `update_platform_config`；`publish_service` / `update_service_env` 自动继承路由与掩码（同调 Core 层）；`get_service_status` 现将 `detail.EnvJSON` 原文直出（server.go:221-225），需同步改为"非敏感 env + envSecretKeys 掩码"结构。注意敏感参数会进入 release-agent 会话历史与 checkpoint（轨迹落 MySQL/Redis），实现时敏感值需掩码回显并标注"页面为推荐通道"。

## 5. 前端设计

### 5.1 新页面 `/settings`（平台默认配置）

`frontend/src/app/settings/page.tsx`，复用发布向导分组 section 惯例（`publish/page.tsx:121-194`）：

- 按组渲染 `<section>`；`sensitive` 字段 `type=password` + 显示切换 + 角标"存于 Secret"，`multiline` 用 textarea；
- 加载：GET 后非敏感键回填真实值；敏感键 input 置空，占位"已配置，留空保持不变"（`hasValue=false` 时"未配置"）；
- 保存：组装 values——敏感键仅在用户输入新值时上送（留空=不变），"清空"需显式点字段旁清除按钮（上送空串）并二次确认；
- 动作两个：「保存」与「保存并滚动重启运行中服务」（PUT 成功后确认卡 → apply-restart，展示受影响服务）；
- 页头说明：配置为全部业务 agent 共享的兜底值，服务 env 可覆盖；修改后需重启生效；
- data-testid：`cfg-group-llm` / `cfg-field-LLM_API_KEY` / `cfg-save` / `cfg-save-restart` 等。

### 5.2 接入点

- 导航：`frontend/src/app/layout.tsx:11-16` 增加「平台配置」；`lib/api.ts` 增加 `getPlatformConfig` / `updatePlatformConfig` / `applyRestartPlatformConfig`；
- 发布向导：移除预填敏感行（`publish/page.tsx:31-38`），改提示"LLM / MySQL / Redis / 沙箱基础配置由平台默认配置提供（'平台配置'页可修改）；此处可添加需要按服务覆盖的项"。敏感键仍允许填写（落服务 Secret），预填移除是 UX 引导而非硬性要求；
- 服务详情页 env 编辑区拆两段：**普通变量**（现有键值编辑器，全量覆盖）+ **敏感变量**（键名列表 + 掩码值输入，留空保持不变，可逐键删除）；保存时分别进 `env` / 服务 Secret。

## 6. 兼容性与迁移

| 场景 | 行为 |
|---|---|
| 未配置任何默认值 | 平台 CM/Secret 为空对象，正常引用，行为与现状一致 |
| 存量服务 env 已含敏感键 | 继续生效；任意一次 PATCH/republish 自动迁入服务 Secret（§3.6 防丢失规则） |
| backend 升级后的存量运行中服务 | apply-restart 重刷 Deployment spec（§3.5），一步带上四源 envFrom 与最新平台默认值；仅 rollout restart 不重建 spec，不能替代 |
| `manifests/platform.yaml` 中 release-agent 的 env（`oaf-release-agent-env` 承担的 `AGENT_REDIS_URL` 等） | 迁移到平台默认配置：release-agent Deployment 增加 `envFrom` 引用平台 CM+Secret，并从该 ConfigMap 移除对应键（P2，P1 不动平台自举清单） |
| e2e 脚本 | 发布用例如显式携带敏感键 env 仍可工作（路由后行为等价），新增落点断言（§8） |

## 7. 安全分析

- **敏感值可见面收敛**：模板敏感键从「页面预填 → env_json → ConfigMap → 页面回显」全明文链路，收敛为「设置页/详情页输入（掩码）→ platform_config / env_secret_json → Secret」；GET / 事件 / 日志全程不回显、不落值只落键名。
- **残余面（如实记录）**：不在模板且未通过 `secretKeys` 指定的任意键仍走 CM 明文（与现状同级，API 提供逃生门）。
- **已知限制**：
  - `platform_config` 与 `env_secret_json` 敏感值为 MySQL 明文，与现状 `model_config.api_key` 明文列同信任域；静态加密（master key 置 `.env.secrets`）列为后续增强。
  - **Secret 化收敛的是"清单与平台存储面"，不是 Pod 内可见性**——env 注入后对拥有业务 Pod exec 权限者仍可 `env` 读明文，这是 env 传递机制固有属性；进一步收敛需 Secret volume 挂载 + 框架侧支持，超出本期。
- RBAC 最小化：secrets `get/list/create/update/delete`（delete 仅服务 Secret 清理路径）；平台与服务 Secret 仅本 namespace 业务 Pod envFrom 消费。
- 框架侧既有防线不变：`/debug` 密码掩码、Redis 日志只打 host/port/db。

## 8. 测试与验收

- **backend 单测**：模板与应用变量名一致性对拍；PUT 校验（未知键 / 清必填 400 / 空串删除 / 32KB）；env 路由拆分（敏感→env_secret_json+Secret、非敏感→env_json+CM、`secretKeys` 强制）；读路径掩码（GET services 不含敏感明文、`env_secret_json` 不随实体序列化）；sticky 语义（PATCH 非敏感不动敏感键）；**存量迁移（旧 env_json 含敏感键时 PATCH 其他键 → 敏感键迁入 Secret 且 CM 不再含）**；**Republish/StartAgain 后服务 Secret 内容保持（EnsureSecret 不清空）**；Ensure 幂等与空对象；Deployment 四源 envFrom 顺序断言；**平台对象名不满足 `{oaf-*}-env(-secret)` 碰撞后缀的对拍断言**；overlay 不变量；Delete 清理服务 Secret。
- **前端**：eslint / tsc / next build；settings 页与详情页两段编辑的 testid 断言。
- **E2E**：
  1. PUT 平台默认（llm+redis）→ 发布**不带 env** 的服务 → Pod Ready 且 A2A 注册成功（证明默认值链路走通）；
  2. 断言落点：`kubectl get cm {name}-env` 不含敏感键、`kubectl get secret {name}-env-secret` 含对应键（只断言键存在，值不落日志）；`kubectl exec ... env` 断言 `LLM_API_KEY` 存在；
  3. 发布时 `env` 携带 `LLM_API_KEY` → 落服务 Secret 而非 CM；`GET /services/:id` 响应无明文；
  4. PATCH 仅改非敏感 env → 敏感键 sticky 不变；空串删除服务级敏感键 → 回落平台默认；
  5. 发布时携带 `LLM_API_KEY` 的服务执行 republish（换镜像）→ 服务 Secret 内容保持（验证 EnsureSecret 覆盖语义不清空）；
  6. 修改平台默认 → apply-restart → 服务 deploying→running、新值生效，且升级前创建的旧服务 Deployment envFrom 含四源引用（重刷 spec 断言）；
  7. 删除服务 → `{name}-env-secret` 一并删除。

## 9. 实施拆分建议

- **P1（本期）**：backend 模板 + store（`platform_config` / `platform_config_event` / `services.env_secret_json`）+ k8s（平台 CM/Secret、服务 Secret、四源 envFrom、overlay 不变量、RBAC）+ API（平台三端点 + env 路由/掩码/sticky）+ 前端（`/settings` 页、导航、发布向导预填移除、详情页两段编辑）+ E2E + 文档同步（backend/AGENTS.md、根 AGENTS.md 基础设施约定、docs/deployment.md）。
- **P2（后续，独立 PR）**：MCP 工具 `get/update_platform_config`（敏感值掩码回显 + "页面为推荐通道"标注）；`manifests` 自举 secret 占位符化（`oaf-mysql-secret` 明文、platform-backend `MYSQL_DSN` 改 secretKeyRef）与 release-agent 接入平台 CM/Secret（§6）；agent-framework 侧 yml/代码默认值漂移修复（temperature 0.3/0.7、maxTokens 16384/4096）与死配置清理（`CHECKPOINT_MYSQL_DSN`）。
- **P3（可选项）**：`platform_config` / `env_secret_json` 静态加密；任意键"标记为敏感"前端 UI；Secret volume 挂载替代 env 传递。

## 10. 待确认问题

1. ~~用户 env 硬拒敏感键（breaking）~~ —— **R2 已解决**：改为路由进服务 Secret，无 breaking。
2. **apply-restart 是否并入 PUT**（一个按钮 vs 两个动作）——本文档取两个动作，避免误触全局重启；如总要多点一次可再加 `applyRestart:true` 参数。
3. **S3 组本期不纳入**——模板结构已预留，随时可加组。
4. **服务级敏感键 sticky 语义**（PATCH 未出现的敏感键保持不变）与 env 全量覆盖心智不完全一致——选择 sticky 是为防页面盲点误删；如希望严格全量，需前端始终回传完整敏感键集合。

## 11. 实施与验证记录（2026-09-28）

**实现范围**：§9 P1 全量（backend / frontend / manifests RBAC / 文档）；镜像 `platform-backend:v4`、`platform-frontend:v7` 已导入集群并滚动上线。

- **单测**：`go vet` + `go test ./...` 全绿（新增 template/envroute/platformconfig/handler 四组用例：模板键一致性对拍、SANDBOX_ENABLED 排除、平台对象名防碰撞后缀、Split 路由、sticky/显式删除优先于存量迁移、republish 保持服务 Secret、平台配置校验与拆分渲染、apply-restart 状态过滤与 spec 重刷、Delete 清理、掩码不泄漏）；前端 `eslint` 0 error、`next build` 通过（/settings 路由生成）。
- **真实集群实测**（kind，K8s 1.32，nginx :8911 入口）：
  1. backend 启动自动创建空平台对象 `oaf-platform-default-config`（CM）/ `oaf-platform-default-secret`（Secret）✅；
  2. 从 release-agent 现网配置提取模板键值 PUT /platform-config（code 0）→ 拆分渲染正确：Secret 含 AGENT_REDIS_URL / CHECKPOINT_PASSWORD / LLM_API_KEY / OPENSANDBOX_API_KEY，CM 含 CHECKPOINT_JDBC_URL / CHECKPOINT_USERNAME / LLM_BASE_URL / LLM_MODEL_ID / OPENSANDBOX_SERVER_URL，GET 响应敏感键仅 hasValue ✅；
  3. **发布不带任何 env 的服务 → A2A 注册成功转 running**（平台默认值经 envFrom 跑通 LLM/MySQL/Redis 全链路）；Deployment envFrom 四源顺序正确；Pod 内 `env` 断言敏感键存在（值不回显）；服务 CM 空、服务 Secret 空对象 ✅；
  4. 发布携带 `LLM_API_KEY` → envJson 剔除该键、服务 Secret 收纳（CM 仅 LOG_LEVEL）；`GET /services/:id` 响应无明文、`envSecretKeys` 掩码视图正确 ✅；
  5. PUT 修改 LLM_TEMPERATURE → apply-restart 点名测试服务 → deploying→running（scope 过滤生效，未触碰现网 mcdonalds-ordering-agent / release-agent）✅；
  6. 删除测试服务 → Deployment/CM/Ingress 与服务 Secret 全部清理，平台对象保留，服务列表零残留 ✅。
- **实施期发现并修复**：RBAC 缺 `delete` verb 导致服务删除时 `{name}-env-secret` 残留（`Delete()` 的 `_ =` 吞掉 403）——已补 `delete` verb（仅服务 Secret 清理路径使用）并端到端复验零残留；§3.4/§7 RBAC 表述同步修正。
- **存量数据**：现网两个服务未动；其 env 中如仍有模板敏感键，按 §3.6 防丢失规则在下一次 PATCH/republish 时自动迁入服务 Secret。
- **E2E**（2026-09-28 两轮实跑，CI 之外本机集群执行）：
  - 新增 `e2e/platform-config-secret-e2e.sh`（46 断言全绿）：P 场景模板 schema/PUT 掩码/未知键与清必填 400；Q 场景集群拆分渲染（Secret 敏感 / CM 非敏感互斥断言）；R 场景发布**不带 env** → running（默认值跑通 LLM/MySQL/Redis 全链路）+ envFrom 四源顺序 + Pod 内敏感键存在性（值不回显）+ 服务 CM 空对象；S 场景携带敏感键发布 → 路由服务 Secret、详情无明文、envSecretKeys 掩码；T 场景 sticky 保持 + 空串删除回落默认；U 场景 apply-restart 点名生效 + spec 重刷保持四源；V 场景删除零残留且平台对象保留；
  - 存量 `platform-e2e.sh` 回归 50/50 全绿（修复其 E1 用例 `/tmp/opencode` 目录未创建的存量缺陷）；`package-edit-e2e.sh`/`file-support-e2e.sh` 等携带敏感键 env 的脚本与新路由语义兼容（值仍送达 Pod，仅落点移入 Secret）。
- **P2 待办**：MCP 工具 `get/update_platform_config`；`get_service_status` 的 env 输出改造为掩码结构；manifests 自举 secret 占位符化；release-agent 静态清单 env 可改用发布页默认填入。

### R3 实施与验证（同日，最终形态）

R1~R4 评审决定改为「默认填入」语义（见文首 R3 修订），同日完成实施并重验：

- **代码变化**：envFrom 回归两源（服务 Secret + 服务 CM）；移除平台 CM/Secret 构造与启动/发布期 Ensure、移除 `apply-restart` 端点；新增 `GET /platform-config/defaults`（含敏感明文，专供表单预填）；前端发布向导预填 defaults（敏感键 password 呈现）、详情页「填入平台默认」按钮（缺失键显式填入，保存才生效）、设置页移除重启按钮并更新文案。单测 `go vet` + `go test ./...` 全绿（用例同步改为两源断言 + defaults 数据源断言）；前端 eslint 0 error、`next build` 通过。
- **E2E（R3 后两套全绿）**：
  - `e2e/platform-config-secret-e2e.sh` 改写为 R3 语义，26 断言全绿：P 模板 schema / PUT 掩码 / 未知键与清必填 400 / defaults 数据源；R **隔离性**——最小 env（仅 checkpoint/redis）发布 → running、envFrom 两源、Pod 无平台默认 LLM 键、平台对象不存在；S **默认填入流**——以 defaults 返回值整体作为 env 发布（模拟向导预填提交）→ running（真实配置全链路）+ 敏感键路由服务 Secret + 详情无明文 + envJson 剔除全部模板敏感键；T sticky 保持 + 空串删除；U **改平台默认不影响存量服务**（服务状态与 Deployment generation 均不变，新值仅反映在 defaults 端点）；V 删除零残留。
  - 存量 `platform-e2e.sh` 回归 50/50 全绿。
- **部署状态**：`platform-backend:v5` / `platform-frontend:v8` 已滚动上线；集群遗留的平台 CM/Secret 已删除。
