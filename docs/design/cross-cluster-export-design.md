# OAF 跨集群导出发布设计（测试环境 → 生产跨集群交付）— 设计文档

**日期**：2026-10-29　**状态**：设计定稿（评审决议齐备，未实施）

> 本文档记录 2026-10-29 评审定稿的跨集群导出发布方案。四项关键决议见 §0，正文按决议编写；被否决的备选路径（独立镜像同步服务、values 占位符脱敏）仅在 §0 与 §13 v2 演进中留痕。配套可交互原型 [demo/cross-cluster-export-demo.html](demo/cross-cluster-export-demo.html)（单文件离线可开：导出发布页 / 运维默认配置 / 生产部署 / 交付包与 DoD）。

## 0. 决议记录（2026-10-29）

| # | 议题 | 决议 |
|---|------|------|
| ① | 生产密钥注入通道 | 暂不做占位符脱敏：敏感值明文写入 values 文件、经文件代入部署；values 随 ITSM 工单按密级流转 |
| ② | 生产 Helm 部署接口形态 | 复用生产侧已有 HTTP Helm 部署接口，平台不重建部署能力 |
| ③ | 镜像同步方式 | 不引入镜像同步服务：backend 构建后直推指定镜像仓库，生产侧自己拉取生产同步镜像 |
| ④ | 生产 A2A 服务目录登记 | 走另外流程处理，本期不涉及 |

## 1. 背景与问题

现状（单集群）：OAF 配置包上传后存共享 PVC（`packages/{id}`），backend 经 client-go 直构业务 Deployment/Service/Ingress，包以 subPath 只读挂 `/config`，就绪后自动调用 A2A `agent-card` 注册。该链路隐含一个前提——**backend 与业务集群同处一个 K8s**，因此：

1. 测试环境定稿的 agent 无法交付到另一个 K8s 集群（生产环境）；
2. 生产环境没有平台 backend，业务面必须自治运行：配置包、镜像、运行时环境（MySQL/Redis/OTel/沙箱）都要随交付物自带，不能依赖平台进程注入；
3. 测试与生产的 MCP 地址、模型网关、密钥等配置天然不同，需要一次受控的「导出时重配置」，而不是直接搬测试环境的包和 env。

## 2. 目标与非目标

### 2.1 目标

1. **用户侧导出发布页**（测试环境 Console）：从服务详情进入，提供配置包更新、版本查看、生产环境变量、模型配置、沙箱配置能力：
   - 配置包更新 = 文本直改：包文件树（AGENTS.md / skills 只读）+ 纯文本编辑器（复用 package-online-edit 的在线编辑思路）；保存即打**发布标记**（`{包版本}-e{序号}`）并把文件快照（path → content + checksum）写入发布标记表，确认导出时按该标记内容固化进 OAF 镜像；
   - 生产环境变量：可添加 / 编辑 / 删除服务专属键；**来自运行时默认的行锁定不可修改**，只能由运维在配置管理改默认；
   - 模型 / 沙箱配置按模板展示全量参数（LLM 10 键 / Sandbox 6 键，与 `platformconfig/template.go` 同源），默认锁定。
   确认后由 backend 生成 2 类交付物：OAF 配置镜像（构建后直推指定镜像仓库）+ values 文件（随 manifest 提交 ITSM）。
2. **运维侧工作空间运行时默认配置**：按工作空间维护 agent-framework 运行时所需的 MySQL / Redis / OTel / 沙箱 / 镜像仓库 / 路由等默认值，作为导出页预填与 values 组装来源。
3. **审批与部署**：values + manifest 作为 ITSM 工单附件审批；审批通过后调用生产侧已有 HTTP Helm 部署接口，用 4 件套（chart / 基础镜像 / OAF 镜像 / values）完成跨集群部署。
4. **测试集群零改动**：现有发布链路（PVC 直挂、backend 构造业务资源）不变，导出是新增旁路能力。

### 2.2 非目标（本期不做）

- 生产侧平台 backend、服务目录登记（决议 ④，走另外流程）；
- values 占位符脱敏（决议 ①；如后续合规提出，v2 在渲染层引入，不改 API 形状，见 §13）；
- 单集群发布链路、PVC 挂载方式的任何变更；
- MCP `export_service` 对话式导出（v2）；
- 导出单与部署接口的自动对接（ITSM 回调、部署回传，v2）。

## 3. 总体流程

四条泳道（用户侧 → 平台 backend → 外部服务 → 生产环境）：

```text
泳道1 用户侧（测试环境 Console）
  [1 服务详情 → 导出发布] → [2 导出发布页] → [3 确认导出 → 创建导出单]
        │ 导出单（backend 异步执行）
        ▼
泳道2 平台 backend（测试集群）
  [4 构建 OAF 配置镜像] ──直推──> 指定镜像仓库（busybox 基底 + go-containerregistry）
  [5 组装 values.yaml + manifest.json]（四层合并，敏感值明文文件代入）
        │ 交付物齐备（built）
        ▼
泳道3 外部服务
  [6 ITSM 审批]（附件：values.yaml + manifest.json，values 含敏感值、按密级流转）
        │ 审批通过（approved）
        ▼
泳道4 生产环境（另一 K8s 集群）
  [7 领取 4 件套：chart / 基础镜像 / OAF 镜像 / values]
      → [8 调用已有 HTTP 部署接口（helm upgrade --install，幂等）]
      → [9 就绪验证：Pod Ready + /.well-known/agent-card.json 可达]
```

要点：

- 泳道 2 的镜像构建与推送发生在 backend 进程 + 镜像仓库侧，**不写测试业务集群任何 K8s 资源**；
- 泳道 4 的部署完全不经平台 backend 进程，与生产已有部署体系对接；
- 「配置包更新」以发布标记冻结：保存即写入发布标记表（快照 + checksum），构建 OAF 镜像只认标记（无标记不可构建），标记不可修改——改动会生成新标记；values 不携带 MCP 覆盖段。

## 4. 交付物规范（生产侧 4 件套）

| 交付物 | 版本来源 | 流转通道 | 说明 |
|--------|----------|----------|------|
| `oaf-agent` chart | 固定版本（随平台迭代发布，如 0.1.0） | 制品库 / 离线包 | 新业务 chart，与控制面 chart `oaf-platform` 分离，只描述单个业务 agent 运行时（§6） |
| 基础镜像 `agent-framework:agentscope-X` | values 指定（运维白名单内选择） | backend 直推指定镜像仓库 | Java 运行时，`/config` 为配置挂载点（镜像已定义 VOLUME） |
| OAF 配置镜像 `oaf-{k8s名}:{包版本}-e{导出序号}` | 本次导出构建，digest 固定 | backend 构建后直推指定镜像仓库 | busybox 基底 + 配置层，labels 记录 slug/version/checksum/export-id（§5） |
| `values.yaml` + `manifest.json` | 导出单生成 | ITSM 审批附件 | 敏感值明文写入 values（决议 ①），按密级随工单流转；manifest 用于审计与一致性校验（§7、§12） |

## 5. OAF 镜像构建方案

- **内容**：OAF 包目录（`AGENTS.md` / `mcp-configs/` / `skills/` / `versions/`…）打 tar 层，包内挂载点 `/oaf`；基底 `busybox:1.36`，保证 initContainer 在镜像内执行 `sh -c "cp -a /oaf/. /config/"` 可用。
- **构建与推送**：backend 内用 `google/go-containerregistry` 的 `remote.Write` 直推指定镜像仓库。OAF 镜像是纯数据镜像（KB~MB 级），该方式无 docker 依赖、无特权、同 tag 内容寻址可幂等重推，适合 backend 进程内完成；不引入 Kaniko（需特权与 RBAC，收益不匹配）。
- **构建输入**：导出单当前生效的发布标记（`export_package_marks` 快照，含文件树 JSON 与 checksum）；无标记不可构建，标记不可修改（改则生成新标记）。
- **tag 规则**：`{指定registry}/{namespace}/oaf-{k8s名}:{包版本}-e{导出序号}`；构建完成后 digest 记录进导出单，部署前可按 digest 校验镜像就绪。
- **推送凭证**：来自工作空间运行时默认配置的 registry 组（§10），凭证存平台 Secret，不进任何导出产物。

构建方式对比：

| 方式 | 依赖 | 特权 | 适用性 |
|------|------|------|--------|
| go-containerregistry remote.Write（选定） | backend 引入一个 Go 库 | 无 | 纯数据镜像直推，进程内完成，幂等 |
| Kaniko Job | 集群内 Job + RBAC | 需要特权运行时 | 通用镜像构建，对配置镜像过重 |

## 6. 业务 chart：`oaf-agent`

与控制面 chart `charts/oaf-platform`（backend/frontend/router 自举）完全分离，新增 `charts/oaf-agent`，**只渲染单个业务 agent 运行时**，交付时固定版本。模板清单：

- `deployment.yaml`：initContainer 注入配置 + 主容器（基础镜像）+ probes；
- `service.yaml`、`ingress.yaml`：路由双模式（router/host），语义对齐 `INGRESS_HOST_SUFFIX`；
- `configmap.yaml` / `secret.yaml`：`envFrom` 两源（对齐平台既有 per-service CM + Secret 模式）；
- `mysql-init-job.yaml`（可选，`mysql.createDatabase: true` 时启用）：pre-install hook Job 幂等建库（`CREATE DATABASE IF NOT EXISTS`）；
- `serviceaccount.yaml`、`_helpers.tpl`、`NOTES.txt`。

deployment 核心节选：

```yaml
initContainers:
  - name: inject-oaf
    image: {{ .Values.image.oaf }}            # busybox 基底的 OAF 配置镜像
    command: ["sh", "-c", "cp -a /oaf/. /config/"]
    volumeMounts:
      - { name: config, mountPath: /config }
containers:
  - name: agent
    image: {{ .Values.image.base }}           # agent-framework 基础镜像
    envFrom:
      - configMapRef: { name: {{ include "oaf-agent.fullname" . }}-env }
      - secretRef:    { name: {{ include "oaf-agent.fullname" . }}-env-secret }
    volumeMounts:
      - { name: config, mountPath: /config, readOnly: true }
volumes:
  - name: config
    emptyDir: {}    # 只读配置副本，Pod 重建即恢复，符合原则二（可丢弃/可重建）
```

设计说明：

- initContainer 替代单集群的 PVC subPath 挂载——生产环境无共享 PVC 依赖，配置随镜像分发；
- checkpoint 库名沿用 issue #142 派生规则 `{基库}_{k8s名}`（如 `oaf_checkpoint_travel_agent`），由 hook Job 幂等建库或运维预建；
- 默认 `replicas >= 2`、无 `sessionAffinity`，多副本横向扩展语义与单集群一致；
- **自包含性（4 件套可独立部署，v1 验收前提）**：chart 不硬编码 namespace（release 名与 `-n` 由部署方指定）、不引用平台任何对象（共享 PVC `platform-data` / platform-router / platform backend / platform_config）；MySQL/Redis/OTel/沙箱地址、`imagePullSecrets`、路由 host 全部来自 values——满足后在任一全新 namespace 仅凭 4 件套即可部署成功（见 §13 DoD）。

## 7. values 组装

### 7.1 四层合并（优先级从低到高）

| 层 | 来源 | 典型内容 | 覆盖关系 |
|----|------|----------|----------|
| L0 chart 默认 | chart 内置 values.yaml | 资源模板默认值、健康检查 | 被上层覆盖 |
| L1 工作空间运行时默认 | 工作空间配置管理（`/config/*`，运维维护，§10） | MySQL/Redis/OTel/沙箱/模型全量参数、registry、路由模式 | 导出页锁定（仅运维可改默认） |
| L2 用户导出覆盖 | 导出发布页表单 | 包内 MCP 生产地址改写（进包）、服务专属新增 env 键（如 MCP token）、部署参数 | 仅新增，不覆盖 L1 键 |
| L3 平台生成 | 导出单 | 镜像引用与 digest、保留键（`AGENT_CONFIG_DIR` 等）、派生键（JDBC 库名/Redis 前缀）、版本与审计标签 | **不可覆盖** |

### 7.2 敏感值规则（决议 ①：文件代入）

- 敏感键清单与平台既有 sensitiveKeys 一致（`LLM_API_KEY` / `CHECKPOINT_PASSWORD` / `AGENT_REDIS_URL` / `OPENSANDBOX_API_KEY` 等），渲染进 values 的 `secretEnv` 段，由 chart 渲染为服务 Secret；
- **v1 不做占位符脱敏**：敏感值明文写入 values 文件、经文件代入部署；values 随 ITSM 工单按密级流转，文件保管由审批流程约束，平台侧下载记录审计（§12）；
- 包内敏感值统一以 `${ENV_VAR}` 占位引用（如 MCP `auth.token: ${MCP_FLIGHT_TOKEN}`），跨环境只改 connection.url 等非敏感项——改完的包直接固化为 OAF 镜像，敏感值经 env/Secret 注入。

### 7.3 values.yaml 完整示例

```yaml
# oaf-agent values.yaml — 由导出单 EXP-20261029-0007 自动生成
# 服务: 差旅助手 travel-agent · 工作空间 ws-3f9a21c4
# 源包: agentmanager/travel-agent@1.4.1 → 导出版本 1.4.2
# chart: oaf-agent-0.1.0（固定版本）· 基础镜像 agentscope-2.1.0-v20260907
# 敏感值明文写入本文件（文件代入）：随 ITSM 工单按密级流转
fullnameOverride: travel-agent

image:
  base: registry.prod.example.com/oaf/agent-framework:agentscope-2.1.0-v20260907
  oaf: registry.prod.example.com/oaf/oaf-travel-agent:1.4.2-e7
  oafDigest: sha256:3f8ac1d2…    # 构建后回填，部署前可校验
imagePullSecrets:
  - prod-registry-secret

env:                              # 渲染为 ConfigMap envFrom
  AGENT_CONFIG_DIR: /config
  AGENT_WORKSPACE_DIR: /workspace
  SERVER_HOST: 0.0.0.0
  SERVER_PORT: "8100"
  CHECKPOINT_JDBC_URL: jdbc:mysql://mysql.prod.svc:3306/oaf_checkpoint_travel_agent
  CHECKPOINT_USERNAME: oaf_writer
  AGENT_REDIS_PREFIX: travel-agent:
  OTEL_EXPORTER_OTLP_ENDPOINT: http://otel-collector.prod.svc:4318
  # 模型组（模板全量 10 键，空值不下发；此处列示例非空项）
  LLM_BASE_URL: https://llm-gw.prod.example.com/v1
  LLM_MODEL_ID: glm-4.7
  LLM_PROVIDER: openai
  LLM_TEMPERATURE: "0.3"
  LLM_MAX_TOKENS: "16384"
  LLM_ENABLE_THINKING: "true"
  LLM_CONTEXT_LENGTH: "128000"
  # 沙箱组（模板全量 6 键）
  OPENSANDBOX_SERVER_URL: sandbox.prod.example.com:8090
  SANDBOX_IMAGE: opensandbox/code-interpreter:v1.1.0
  SANDBOX_TIMEOUT_MINUTES: "60"
  SANDBOX_MEMORY_MB: "1024"
  SANDBOX_CPU_COUNT: "1"

secretEnv:                        # 渲染为 Secret envFrom（明文，文件代入）
  LLM_API_KEY: "sk-prod-9f8e7d6c"
  CHECKPOINT_PASSWORD: "Prod#2026Mysql"
  AGENT_REDIS_URL: "redis://redis.prod.svc:6379/0"
  OPENSANDBOX_API_KEY: "sbx-prod-5a5a"
  MCP_FLIGHT_TOKEN: "flt-prod-xxxx"
  MCP_HOTEL_TOKEN: "htl-prod-xxxx"

configInjection:
  mode: initContainer             # initContainer 拷贝 /oaf → 共享卷 /config
  dir: /config

replicas: 2
resources:
  requests: {cpu: 500m, memory: 1Gi}
  limits: {cpu: "2", memory: 2Gi}

ingress:
  mode: router                    # router | host，对齐 INGRESS_HOST_SUFFIX 语义
  tls: false                      # host 模式时由平台生成 host: {k8s名}{hostSuffix}

mysql:
  createDatabase: true            # pre-install hook Job 幂等建库

podAnnotations:
  oaf.platform/export-id: EXP-20261029-0007
  oaf.platform/package-checksum: sha256:ab73f20c9e…8d41
```

manifest.json 与 values 配套提交 ITSM，记录导出单号、服务与工作空间、包版本链（sourcePackageId 派生）、4 件套引用与 digest、覆盖摘要（改动的 MCP server / env 键数 / 敏感键数 / 应用的工作空间默认数）、ITSM 工单号、操作人与时间——用于生产侧一致性校验与审计追溯。

## 8. 数据模型与状态机（backend 新增）

新表随 backend 现有 GORM AutoMigrate 机制创建（与 users/workspaces 同模式）；agent-framework 零改动，不涉及 Flyway。

```go
// 导出单：一次跨集群交付的载体
type ExportOrderEntity struct {
    ID               uint64
    ServiceID        uint64 `gorm:"index"`
    WorkspaceID      uint64 `gorm:"index"`
    SourcePackageID  uint64 // 导出时的源包
    ExportPackageID  uint64 // 在线编辑派生的新包（复用 package-online-edit 的 copy-on-write 链）
    ChartVersion     string
    BaseImage        string
    OafImageRef      string
    OafImageDigest   string
    ValuesYAML       string `gorm:"type:mediumtext"` // 渲染产物（含明文敏感值，接口层不做明文回显，仅文件下载）
    ManifestJSON     string `gorm:"type:json"`
    Status           string // draft/building/built/approving/approved/deploying/deployed/failed/cancelled
    ItsmTicketID     string
    Error            string
    CreatedBy        string
    CreatedAt        time.Time
    UpdatedAt        time.Time
}
// 索引：(workspace_id, status)

// 发布标记：导出级包编辑的快照（文本直改 → 打标记 → 落表 → 构建固化）
type ExportPackageMarkEntity struct {
    ID             uint64
    ServiceID      uint64 `gorm:"index"`
    WorkspaceID    uint64 `gorm:"index"`
    ExportOrderID  uint64 `gorm:"index"` // 0 = 尚未挂到导出单（草稿标记）
    Marker         string `gorm:"size:64;uniqueIndex"` // {包版本}-e{序号}，与 OAF 镜像 tag 对应
    PackageVersion string
    FilesJSON      string `gorm:"type:mediumtext"` // 文件树快照：path → content
    FileCount      int
    Checksum       string // 快照内容 sha256
    Status         string // frozen（已冻结，可构建）
    CreatedBy      string
    CreatedAt      time.Time
}

// 工作空间运行时默认配置（运维侧维护，导出页预填来源）
type WorkspaceRuntimeDefaultEntity struct {
    ID          uint64
    WorkspaceID uint64 `gorm:"uniqueIndex:uk_ws_runtime_default"`
    EnvKey      string `gorm:"size:128;uniqueIndex:uk_ws_runtime_default"`
    Value       string `gorm:"type:text"`
    Sensitive   bool   // 敏感键：接口回显掩码，仅渲染进 values secretEnv
    Category    string // mysql|redis|otel|sandbox|llm|registry|routing
    UpdatedBy   string
    CreatedAt   time.Time
    UpdatedAt   time.Time
}
```

发布标记是 OAF 镜像构建的唯一输入：保存即冻结快照（`FilesJSON` + checksum），不可修改（改动生成新标记）；导出单创建必须携带 marker。

状态机：

```text
draft → building → built → approving(ITSM) → approved → deploying → deployed
任意态可入 failed / cancelled
```

v1 中 `approving → approved → deploying` 由运维在平台人工推进（或脚本回填），v2 接 ITSM/部署回调自动化。

## 9. API 设计（console JWT 之下）

| Method · Path | 说明 |
|---------------|------|
| `GET /api/v1/services/:id/export/package` | 包文件树与文本内容（AGENTS.md / skills 只读项标注） |
| `PUT /api/v1/services/:id/export/package/file` | 保存单文件文本修改（写编辑草稿，不冻结） |
| `POST /api/v1/services/:id/export/package/mark` | 打发布标记：冻结当前快照写入 `export_package_marks`，返回 marker 与 checksum |
| `GET /api/v1/services/:id/export/package/marks` | 发布标记列表（marker / checksum / 时间 / 状态） |
| `GET /api/v1/services/:id/export/preview` | 预填：源包结构与版本、现 env、工作空间默认值、镜像白名单、派生键预览 |
| `POST /api/v1/services/:id/export` | 创建导出单（携带发布标记 marker + env/model/sandbox/部署参数）→ 异步构建 |
| `GET /api/v1/exports/:id` | 导出单状态与详情（构建进度） |
| `GET /api/v1/exports/:id/values.yaml` · `/manifest.json` | 下载交付文本（values 含真实敏感值，下载记录审计） |
| `POST /api/v1/exports/:id/status` | 人工推进状态（v1：approving/approved/deploying），记录操作人与 ITSM 单号 |
| `GET|PUT /api/v1/workspaces/:id/config/runtime-defaults` | 运行时默认 env（分类键值，敏感掩码；导出页锁定来源） |
| `GET|PUT /api/v1/workspaces/:id/config/mcp-connectors` | MCP 连接器模板（connection / auth / permissions / 工具子集） |
| `GET|PUT /api/v1/workspaces/:id/config/templates/:group` | 模型 / 沙箱参数模板（`llm` / `sandbox`，复用 platformconfig 模板驱动） |
| `GET|PUT /api/v1/workspaces/:id/config/registry` | 仓库 / 拉取凭证 / 路由 / 镜像白名单 |

权限口径：导出发起要求 workspace 成员（与 services 归属校验同模式）；runtime-defaults 仅 workspace owner 与平台 admin；导出单可见性跟随 workspace。生产部署不新增平台能力：审批通过后由运维/流程调用**已有 HTTP Helm 部署接口**提交 4 件套（chart + values 文件，镜像按 values 引用从指定仓库拉取）。

## 10. 运维侧：工作空间配置管理（API + 页面重设计）

页面：控制台（运维视角）→ 工作空间 → 配置管理，页签 = 运行时默认 / MCP 连接器模板 / 模型参数 / 沙箱参数 / 仓库与路由 / API。统一入口管理导出所需的全部默认值；**导出发布页全部继承本页，且默认值在导出页锁定**——单次导出不能悄悄改基线，要改只能由运维在此修改（留审计）。与 `platform_config`（平台级、全服务表单默认）的区别：本模块 **per-workspace、面向生产导出**，测试集群「平台默认配置不经 envFrom 注入、仅表单预填」的语义不变。

参数模板与 `backend/internal/service/platformconfig/template.go` 同源（模板驱动表单 / 敏感路由 / 掩码输出）：LLM 组 10 键（API_KEY / BASE_URL / MODEL_ID / PROVIDER / TEMPERATURE / MAX_TOKENS / ENABLE_THINKING / CONTEXT_LENGTH / REASONING_EFFORT / FREQUENCY_PENALTY）、Sandbox 组 6 键（OPENSANDBOX_SERVER_URL / API_KEY / SANDBOX_IMAGE / TIMEOUT_MINUTES / MEMORY_MB / CPU_COUNT）；`SANDBOX_ENABLED` 刻意排除（agent-framework 三层裁决：env 显式存在 > OAF 包 frontmatter > 默认 false，进默认配置会让包级沙箱声明永久失效）。变更审计复用 `platform_config_events` 思路；导出页对新增键与 MCP 改写留痕，默认值本身不可在导出页修改。字段分组：

| 分类 | 键 / 配置 | 敏感 | 说明 |
|------|-----------|------|------|
| mysql | `CHECKPOINT_JDBC_BASE`（如 `jdbc:mysql://mysql.prod.svc:3306/oaf_checkpoint`） | 否 | 平台按 `{基库}_{k8s名}` 派生实际库 |
| mysql | `CHECKPOINT_USERNAME` / `CHECKPOINT_PASSWORD` | 密码 ★ | |
| redis | `AGENT_REDIS_URL` | ★ | |
| redis | `AGENT_REDIS_PREFIX` 规则 | 否 | 平台派生 `{k8s名}:`，防跨服务串写 |
| otel | `OTEL_EXPORTER_OTLP_ENDPOINT` | 否 | |
| llm | LLM 组模板全量 10 键（API_KEY / BASE_URL / MODEL_ID / PROVIDER / TEMPERATURE / MAX_TOKENS / ENABLE_THINKING / CONTEXT_LENGTH / REASONING_EFFORT / FREQUENCY_PENALTY） | API Key ★ | 与 `platformconfig/template.go` 同源，空值不下发 |
| sandbox | `OPENSANDBOX_SERVER_URL` / `OPENSANDBOX_API_KEY` / `SANDBOX_IMAGE` / `SANDBOX_TIMEOUT_MINUTES` / `SANDBOX_MEMORY_MB` / `SANDBOX_CPU_COUNT`（模板全量 6 键） | API Key ★ | `SANDBOX_ENABLED` 不进模板（包 frontmatter 三层裁决） |
| protocol | `AGENT_PROTOCOL_AUTH_TOKEN` / `AGENT_REMOTE_HEADERS_JSON` / `AGENT_A2A_JOB_TOKEN` | ★ | 敏感键走模板 Secret 路由；开关类键刻意不进默认 |
| registry | 指定镜像仓库地址 / `imagePullSecrets` | 否 | backend 直推目标，生产侧自己拉取生产同步镜像 |
| registry | 推送凭证 | ★ | 存平台 Secret，不进导出产物 |
| routing | ingress.mode / hostSuffix | 否 | 对齐 `INGRESS_HOST_SUFFIX` 语义 |
| runtime | 基础镜像白名单 | 否 | 导出页只允许白名单内版本 |

## 11. 架构强约束核对

**原则一（backend 是控制面）**：

- 导出链路对测试业务集群**只读**（读包/env），构建与直推指定镜像仓库发生在 backend 进程 + 仓库侧，不写任何业务 K8s 资源；
- 无启动期 reconcile、无后台批量迁移、无 ownerReferences/finalizer；
- 生产部署走已有 HTTP 部署接口，不经平台 backend 进程；生产业务运行时不依赖 backend（env 经 chart 渲染的 CM/Secret envFrom 常驻、配置经镜像 + initContainer 自带、流量经 Ingress 直连）。

**原则二（agent-framework 是业务面）**：

- chart 无本地有状态：`/config` 为 emptyDir 只读副本（每次 Pod 重建即恢复，可丢弃/可重建）；checkpoint/Redis/OTel 全部指向生产共享存储；
- 默认 replicas ≥ 2、无 `sessionAffinity`，任意请求可落任意副本；
- 多服务隔离沿用 per-service 派生：checkpoint 库 `{基库}_{k8s名}`（幂等建库 hook）、Redis 前缀 `{k8s名}:`，与 issue #142 规则一致。

## 12. 安全与审计

1. **敏感键路由**：沿用 platformconfig 模板 sensitiveKeys 清单，values 中落 `secretEnv` 段（渲染为服务 Secret），普通键落 `env` 段（ConfigMap）；
2. **明文 values 的边界**（决议 ① 接受的代价）：values 含明文敏感值，仅经 ITSM 密级流转与平台下载（下载写审计日志：操作人/时间/导出单号）；导出单详情接口对 `values_yaml` 不做明文回显，只提供文件下载；
3. **推送凭证**：registry 推送凭证存平台 Secret，注入 backend 进程 env，不进导出单、values、manifest 任何产物；
4. **包内密钥**：OAF 包配置文件一律 `${ENV_VAR}` 占位引用密钥，包本身可入库、可构建镜像、不携带明文；
5. **manifest 审计链**：导出单号 ↔ 包 checksum ↔ 镜像 digest ↔ ITSM 工单号四方关联，生产侧可校验「部署的 values 就是审批的那份」。

## 13. 实施分期

**v1（MVP）**：

1. `workspace_runtime_defaults` 表 + CRUD API + 运维侧页面；
2. 导出发布页（前端）+ `export/preview` / `POST export` API + 导出单状态机；
3. go-containerregistry 构建并直推指定镜像仓库；
4. values（四层合并 + 明文 secretEnv）/ manifest 渲染与下载；
5. 新 chart `charts/oaf-agent`（initContainer / 双模式 ingress / mysql hook）；
6. 人工状态推进；审批通过后人工调用已有 HTTP 部署接口。

**v1 完成定义（DoD）**：仅凭 4 件套（chart 固定版本 + 基础镜像 + OAF 镜像 + values）在任一**全新 namespace** 执行一次 `helm upgrade --install` 部署成功——建库 hook 执行、initContainer 注入 /config、Pod Ready、`/.well-known/agent-card.json` 可达，全程不依赖平台 backend / 共享 PVC / platform_config；验证手段为 charts-ci 增加 `oaf-agent` 的 helm lint + template 渲染矩阵（CI 占位必填值渲染全量对象）+ kind 集群以样例 values 部署冒烟。

**v2（可选演进）**：

- ITSM 回调自动推进（approving → approved）；
- 部署接口直连（平台提交产物并触发部署、回传 deploying/deployed）；
- values 脱敏占位符（如合规提出，渲染层引入，不改 API 形状）；
- MCP `export_service`（release-agent 对话式驱动导出）；
- 导出单与发布历史/回滚链路打通；
- 多目标环境（workspace_defaults × target 二维化）。

## 14. 关联文档

- [helm-offline-deploy-design.md](helm-offline-deploy-design.md) — 控制面 chart 形态先例，`oaf-agent` 交付格式与其保持衔接；
- [package-online-edit-design.md](package-online-edit-design.md) — 配置包文本编辑复用其在线编辑交互与安全校验思路；导出快照落 `export_package_marks` 表（不派生 oaf_packages 记录）；
- [platform-default-config-secret-design.md](platform-default-config-secret-design.md) — sensitiveKeys 清单与「普通键 CM / 敏感键 Secret」路由模式同源；
- [console-user-workspace-split-design.md](console-user-workspace-split-design.md) — workspaces 实体与角色模型（owner/admin/member）；
- [subpath-routing-design.md](subpath-routing-design.md) — router/host 双模式路由语义，chart ingress 段对齐。
