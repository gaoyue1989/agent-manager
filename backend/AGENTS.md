# Backend — AGENTS.md

## 模块概述

OAF 服务发布平台管理后端（Go + Gin + GORM + client-go）。同一 HTTP 进程暴露 REST(`/api/v1`) 与 MCP(streamableHttp `/mcp`) 两个协议门面，业务逻辑在 `internal/service.Core`（协议无关）。

核心链路：上传 OAF zip 包 → 校验解包落共享 PVC → 发布（Deployment+Service+Ingress，envFrom ConfigMap，包 subPath 只读挂 /config + 独立可写工作区卷 /workspace）→ 就绪后拉 `/.well-known/agent-card.json` 注册入库 → 列表/状态/重新发布/下线/删除。包支持在线预览（文件树/单文件/整包下载）与在线编辑（copy-on-write 生成新版本包 → republish 切换服务）。

## 目录结构

```
backend/
├── cmd/server/main.go          # 入口：装配 REST + MCP + K8s + DB
├── config/config.go            # 环境变量（MYSQL_DSN 必填无默认）
├── Dockerfile                  # golang:1.26 多阶段构建
├── templates/                  # deployment-overlay.example.yaml / ingress-overlay.example.yaml（overlay 示例）
├── internal/
│   ├── handler/                # Gin 薄层（respond/middleware/router）
│   ├── mcpsrv/server.go        # MCP 工具门面（go-sdk v1.3.1 streamableHttp）
│   ├── service/                # 业务层：package/publish/register/status/env/envroute/platformconfig
│   │   ├── platformconfig/     # 平台默认配置字段模板（全仓唯一字段定义源）
│   │   └── package_version.go  # 包在线预览/编辑派生（FileContent/Zip/CreateVersion）
│   ├── k8s/                    # client-go typed 封装 + 对象构造（纯函数可测）
│   │   ├── template.go         # DeploymentBuilder/IngressBuilder：内置构造 + overlay(SMP) + 不变量校验
│   │   └── k8sfake/            # 测试用 fake Client 实现
│   ├── store/                  # GORM 模型（oaf_packages/services/service_events/platform_config）+ PVC 文件操作
│   └── oaf/oaf.go              # OAG v0.8.0 frontmatter 解析校验（宽松模式 warnings）
```

## 启动

```bash
make test    # go vet + go test ./...
make image && make kind-load
kubectl apply -f manifests/platform.yaml manifests/platform-ingress.yaml manifests/frontend.yaml
```

本地开发：`KUBECONFIG=~/.kube/config DATA_ROOT=/tmp/oaf-data MYSQL_DSN=... go run ./cmd/server`

## 关键约定

- 平台保留键：AGENT_CONFIG_DIR / AGENT_WORKSPACE_DIR / SERVER_HOST / SERVER_PORT（用户 env 出现即 400；HOST_NAME 为日志注入保留键）
- env 全量覆盖语义（PATCH /services/:id/env），上限 64 键 × 32KB
- 服务状态机：created→deploying→running|register_failed|deploy_failed；stopped/error 可再 publish
- WaitReady 要求完整滚动更新完成（generation 对齐 + updatedReplicas 达标 + unavailable=0），防止注册打到旧 Pod
- 业务 Ingress 注入 proxy-read/send-timeout=3600（A2A blocking 长对话必需）
- zip 安全校验：20MB/2000 条目/100MB 解压上限、zip-slip 与符号链接拒绝、文件最低 0644（业务 Pod 非 root 需可读）

## 平台默认配置与敏感 env Secret 化（platformconfig + envroute）

**敏感键一律走 K8s Secret，不进 ConfigMap/env_json；平台默认配置仅作为发布/编辑 env 时的表单默认填入，不经运行时注入、不影响任何已发布服务**。设计见 [../docs/design/platform-default-config-secret-design.md](../docs/design/platform-default-config-secret-design.md)（R3 修订为最终形态）。

- **字段模板**（`internal/service/platformconfig/template.go`，全仓唯一字段定义源）：分组 llm/mysql/redis/sandbox；`Sensitive` 标记驱动服务 env 路由分类与页面掩码。`SANDBOX_ENABLED` 明确排除（保护 OAF 包 frontmatter 三层裁决）
- **envFrom 两源**：容器 envFrom = 服务 Secret `{name}-env-secret`（敏感，在前）+ 服务 CM `{name}-env`（非敏感，在后可覆盖）；无平台级注入
- **默认填入语义（R3）**：平台默认配置只存 DB（`platform_config` 表），`GET /platform-config` 展示视图敏感键掩码，`GET /platform-config/defaults` 返回含敏感明文的平面键值表专供表单预填；发布向导预填 defaults，详情页「填入平台默认」补缺失键（显式保存才生效）；改默认配置不影响存量服务
- **服务 env 路由**（`internal/service/envroute.go`）：发布/PATCH env 中命中模板 Sensitive 或 `secretKeys` 的键路由进 `services.env_secret_json` + 服务 Secret，绝不写 env_json/CM；敏感键三态——非空=设置、空串=删除、缺失=sticky 保持不变；旧 env_json 中的存量敏感键在任意写路径自动迁入 Secret（防丢失规则）
- **平台配置 API**：`GET/PUT /api/v1/platform-config`（PUT 部分更新：出现=设置、空串=删除、缺失=不变；未知键 400；清除必填键 400；展示视图敏感值永不回明文）、`GET /api/v1/platform-config/defaults`（预填数据源）
- **服务 Secret 生命周期**：publish 创建（无敏感键为空对象）；Delete 连带清理（RBAC secrets `get/list/create/update/delete`，delete 仅此路径使用）；Unpublish 保留
- **RBAC**：platform-backend Role 含 secrets `get/list/create/update/delete`（manifests/platform.yaml）；overlay 不变量：envFrom 两源引用必须保留（template.go）
- MCP：`publish_service`/`update_service_env` 自动继承路由（`UpdateEnvIn` 增 `secretKeys`）；`get/update_platform_config` 工具为 P2

## 包在线预览与编辑（package_version.go + fs.go 扩展）

- **包不可变**：包目录经 subPath 只读挂载进业务 Pod，在线编辑永不原地写 —— `CreateVersion` 基于「基础包 + upserts − deletes」在内存合成 zip，走与 Upload 完全相同的校验落盘管线（InspectZip → ParseOAF/Validate → 事务入库 → ExtractZipTo），生成新 `OafPackage` 记录 + 新 PVC 目录 `packages/{newID}`
- REST：`GET /packages/:id/files?path=`（单文件预览，文本判定 = 扩展名白名单 + NUL 嗅探双保险，512KB 上限，二进制返回 `binary:true` 引导下载）、`GET /packages/:id/files/download`、`GET /packages/:id/download`（整包 zip 打包，产物可通过 InspectZip 复检）、`POST /packages/:id/versions`（生成新版本）
- CreateVersion 校验链：路径复用 `store.CleanSubPath`（`..`/`\`/绝对路径/超长统一包装 `ErrZipSlip`→400）→ 单文件 256KB 上限 → 根级 `AGENTS.md` 删除保护 → 无有效变更 400（与基础包逐字节对比）→ frontmatter 重新 ParseOAF/Validate → 同 slug+version 重复追加 warning 不阻断
- 乐观锁：请求带 `expectedBaseChecksum` 与当前基础包不符返回 409（`ErrChecksumMismatch`）
- `OafPackage.SourcePackageID` 记录派生溯源（0=上传原始包）；fileCount/totalSize 上传/派生时统计
- 错误映射：fs.ErrNotExist → 404（文件不存在），ErrZipSlip/ErrNoEffectiveChanges 等 → 400
- 列表过滤：`GET /packages?slug=`（版本历史）、`GET /services?packageId=`（引用服务）
- MCP 工具：`get_package_file`、`create_package_version`（与 REST 同语义，配合 `republish_service` 完成对话式改包→换版发布闭环）；`check_oaf_package` / `create_oaf_zip`（OAF 打包工具，2026-09 自 agent-framework 迁入：校验复用 `internal/oaf.CheckAgentsMD`（聚合契约），组包经 Upload 管线直建平台包并返回 packageId/download_url，供发布助手 `present_url` 交付，见 [../docs/design/oaf-tools-extraction-design.md](../docs/design/oaf-tools-extraction-design.md)）

## 业务 Deployment 模板（DEPLOYMENT_TEMPLATE）

- 业务 Deployment 由 `internal/k8s/template.go` 的 `DeploymentBuilder` 构造：**内置纯函数构造为基线**（`objects.go:Deployment`，平台演进自动带入）+ 可选 YAML overlay（环境策略）经 **Strategic Merge Patch** 合并 + **不变量校验**
- 环境变量 `DEPLOYMENT_TEMPLATE` 指向 overlay 文件路径（建议 ConfigMap 只读挂载）；**不设置 = 纯内置构造，行为与历史版本完全一致**
- overlay 生效时机：发布/重新发布/上下线的 apply 时合并落集群；存量服务需重新 apply（republish 或 rollout restart）才滚动到新形态
- fail-fast：启动时以哑参数试渲染，overlay 语法/类型/不变量错误直接 `log.Fatal` 拒绝启动；发布期再校验兜底（哑参数恰好通过、真实参数违规的 overlay 在 apply 时拒绝，服务转 error）
- 校验不变量（违规即拒）：禁改 metadata.name/namespace、spec.replicas、spec.selector（含 matchExpressions）；必含 agent 主容器、envFrom 四源引用（服务 CM/Secret + 平台默认 CM/Secret）、/config 只读 subPath、/workspace、/data/files、/applog 可写挂载、保留键 env；volumeMount 引用的 volume 必须存在
- overlay 用法与可改项（PVC 名、imagePullSecrets、nodeSelector、tolerations、sidecar 等）见 `templates/deployment-overlay.example.yaml` 内注释

## 业务 Ingress 模板（INGRESS_TEMPLATE）

- 业务 Ingress 由 `internal/k8s/template.go` 的 `IngressBuilder` 构造，模式与 DeploymentBuilder 完全一致：**内置纯函数构造为基线**（`objects.go:Ingress`）+ 可选 YAML overlay 经 **Strategic Merge Patch** 合并 + **不变量校验**；SMP helper 为泛型 `applySMPOverlay[T]`（两种 builder 共用）
- 环境变量 `INGRESS_TEMPLATE` 指向 overlay 文件路径；**不设置 = 纯内置构造，行为与历史版本完全一致**。overlay 支持每服务占位符 `{{K8S_NAME}}`/`{{SHORT_NAME}}`（发布期替换，保证模板服务无关、启动探针可哑参渲染）
- 允许改 host/path/TLS/追加注解；rules/tls 是普通列表（SMP **整体替换**），annotations/labels 是 map（按 key 合并）。校验不变量（违规即拒）：禁改 metadata.name/namespace、平台 labels、spec.ingressClassName；归属唯一（所有 backend 指向本服务 `{name}-svc:8100`）；每条 path 必须以 `(/|$)(.*)` 结尾且 use-regex=true、rewrite-target=/$2 保留；**x-forwarded-prefix 必须等于对外前缀**（改 path 必须同步改它）；proxy-read/send-timeout 必须保留
- **Endpoint 随合并结果派生**（`k8s.IngressEndpoint`）：host 取第一条规则的 Host（空回落 INGRESS_HOST，自带端口不重复拼）、path 剥尾缀得对外前缀、TLS→https、端口取 INGRESS_PORT。Publish 落库前 Build（违规直接拒绝**不落库**，与 image 校验同级）；Republish 事务前 Build 随事务回写 endpoint；StartAgain 不改 endpoint（经 applyAll 重新校验，违规转 error）
- overlay 用法与完整 rules 抄改样例见 `templates/ingress-overlay.example.yaml`；设计见 [../docs/design/ingress-template-design.md](../docs/design/ingress-template-design.md)
