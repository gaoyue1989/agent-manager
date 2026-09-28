# 业务 Ingress 模板（INGRESS_TEMPLATE）设计

> 状态：实施中（2026-09-28）
> 关联代码：`backend/internal/k8s/template.go`（IngressBuilder）、`backend/internal/service/publish.go`（接线）、`backend/templates/ingress-overlay.example.yaml`（示例）

## 1. 背景与目标

发布服务时平台为每个业务服务创建独立 Ingress 对象（`oaf-{name}`，path `/agent/{short}`，共享 ingress-nginx NodePort 30080），但形态由代码写死：无法按环境自定义域名（host）、对外前缀（path）、TLS 与 WAF/白名单类注解。Deployment 已有同诉求的成熟方案——`DEPLOYMENT_TEMPLATE` overlay（Strategic Merge Patch + 启动探针 + 不变量校验，见 `backend/internal/k8s/template.go`）。

本设计将同一模式复制到 Ingress：

- 发布/重新发布/重新上线时，Ingress 由「内置构造 + 可选 overlay」生成；
- overlay 允许**改 host、改 path 前缀、加 TLS、增删注解**（第一期放开 host/path，经评审确认）;
- 平台记录的访问地址（Endpoint，详情页/列表/republish 展示）自动跟随合并结果派生；
- **不设置 `INGRESS_TEMPLATE` = 纯内置构造，行为与历史版本完全一致**。

## 2. 配置方式

- 环境变量 `INGRESS_TEMPLATE` 指向 overlay 文件路径（YAML，裸 Ingress 对象）。运维姿势与 `DEPLOYMENT_TEMPLATE` 相同：ConfigMap 只读挂载 → `kubectl set env` → `rollout restart`（见 `docs/deployment.md` §六/§七）。
- 启动 fail-fast：`NewIngressBuilder` 在文件打开失败、YAML 语法/类型错误、**哑参数试渲染违规**时直接启动失败，避免带病受理发布请求。试渲染用哑参数 `oaf-template-probe/default`——overlay 不得硬编码 `metadata.name/namespace`。
- overlay 变更在下次 apply（发布/重新发布/重新上线）时生效，存量 Ingress 不自动刷新。

## 3. 合并语义（Strategic Merge Patch on `networkingv1.Ingress`）

overlay 支持每服务占位符（发布期替换，保证单文件模板服务无关、启动探针可哑参渲染）：`{{K8S_NAME}}` → `oaf-{short}`（backend 指向 `{{K8S_NAME}}-svc`）、`{{SHORT_NAME}}` → 去前缀短名（path/x-forwarded-prefix 用）。rules 整体替换语义下 backend/path 必须经占位符引用服务名，硬编码会被启动探针拒绝。

| 字段 | SMP 行为 | 使用方式 |
|------|----------|----------|
| `metadata.annotations` | map 按 key 合并 | 只写新增/覆盖项即保留全部内置注解；写 `null` 删除单条 |
| `metadata.labels` | map 按 key 合并 | 可追加，平台两个 label 禁改 |
| `spec.rules` / `spec.tls` | 普通列表，**整体替换** | 想改 host/path 必须写完整 rules 块（示例文件给完整可抄样例）；漏写 backend 会被不变量校验拦截 |
| 其余字段 | 按字段覆盖 | — |

## 4. 不变量校验（合并后强校验，违规即发布失败）

错误信息指明违例项，风格同 `validateDeployment`：

1. `metadata.name/namespace` 禁改；平台 labels（`app.kubernetes.io/name` + managed-by）禁改，追加允许；
2. `spec.ingressClassName` 禁改：overlay 显式写了必须等于配置值；置 `null` 视为交集群默认，允许；
3. **归属唯一**：所有 rules 的 backend 必须指向本服务 `{k8sName}-svc:{AgentPort}`——Ingress 对象与服务一一对应，禁止跨服务路由；
4. **路由形状**：每条 path 必须以 `(/|$)(.*)` 结尾（`rewrite-target /$2` 依赖第 2 捕获组），`use-regex: "true"`、`rewrite-target: "/$2"` 注解必须保留；
5. **x-forwarded-prefix 一致性**：该注解必须等于本服务对外前缀（由指向本服务的 path 剥掉 `(/|$)(.*)` 得到）。改 path 必须同步改它，否则 agent-framework 的 Debug Console 尾斜杠重定向/外链错位；
6. `proxy-read/proxy-send-timeout` 注解必须存在（SSE 长连接契约，数值允许按环境调整）；
7. TLS / `ssl-redirect` 不限制（允许开）。

同一 Ingress 只允许一个对外前缀：多条不同前缀的 path 会让第 5 条校验失败——这是刻意约束（x-forwarded-prefix 是单值注解）。

## 5. Endpoint 派生（host/path 可改后的联动）

现状 Endpoint 是 config 固定公式（`http://{INGRESS_HOST}:{INGRESS_PORT}/agent/{short}/`），与 Ingress spec 脱节。改为：

- `k8s.IngressEndpoint(ing, fallbackHost, port)`：取第一条带尾缀 path 的规则 → host 取 `rule.Host`（空则回落 `INGRESS_HOST`；host 已含 `:` 视为自带端口，不再拼接）→ path 剥 `(/|$)(.*)` 得对外前缀（剥空即根路径）→ scheme 按 `spec.tls` 是否非空（https/http）→ 端口取 `INGRESS_PORT`。
- 对内置构造（无 overlay）派生结果与旧公式逐字符一致——`TestPublishHappyPath` 的 endpoint 断言即回归锁。
- 模板改 host 后的 DNS/端口可达性由环境负责，平台只保证展示地址一致；TLS 下端口仍取 `INGRESS_PORT`（若环境未在该端口终止 TLS，不要在模板配 TLS）。

## 6. 发布时序与失败语义

| 路径 | 语义 |
|------|------|
| Publish | 落库**前** Build 一次（纯函数、零成本）：非法 overlay 直接拒绝且**不落库**（与 image 校验同级——模板违规属平台配置错误，不产生 deploying/error 垃圾记录）；Endpoint 随 entity 一次写入。applyAll 内再 Build + EnsureIngress，为权威校验点 |
| Republish | 事务**前** Build：非法 overlay 直接拒绝，库与 K8s 均保持原状；合法则派生 Endpoint 随事务 updates 回写（模板/配置变更后展示地址跟随） |
| StartAgain / UpdateEnv | 不改 Endpoint。StartAgain 经 applyAll 重新校验（违规转 error，与 Deployment overlay 同语义）；UpdateEnv 不触 Ingress |

与 Deployment overlay 违规（落库后转 error）存在刻意的语义差异：Ingress overlay 在 Publish/Republish 的纯函数前置阶段即拒绝；Deployment 保持历史行为不变，后续可对齐（不在本期）。

## 7. 改动清单

| 文件 | 改动 |
|------|------|
| `backend/config/config.go` | `Config` 加 `IngressTemplate`（env `INGRESS_TEMPLATE`，空=纯内置构造） |
| `backend/internal/k8s/template.go` | `IngressBuilder`（New/Build）+ `validateIngress` + `IngressEndpoint`；`applyOverlay` 泛化为 `applySMPOverlay[T]` 供两种 builder 复用 |
| `backend/internal/k8s/objects.go` | Ingress 注解 key / path 尾缀 / rewrite 值提为常量，构造与校验共用 |
| `backend/internal/service/publish.go` | `ConfigView.IngressBuilder` + `ingressBuilder()` 兜底（nil=纯内置）；Publish/Republish 前置 Build 派生 Endpoint；applyAll 换 builder |
| `backend/cmd/server/main.go` | `NewIngressBuilder` fail-fast 装配注入 |
| `backend/templates/ingress-overlay.example.yaml` | 示例与语义说明（全注释，不设默认行为） |
| `backend/internal/k8s/template_test.go` | SMP 语义 / 不变量违规 / Endpoint 派生 / 启动探针用例 |
| `backend/internal/service/publish_test.go` | Endpoint 跟随模板、违规不落库、Republish 回写 |
| `backend/AGENTS.md`、`docs/deployment.md`、根 `AGENTS.md` | 契约与运维姿势文档 |

不动 `manifests/`（缺省关闭；`frontend.yaml`/`platform.yaml` 当时的未提交改动属并行工作，不混入本特性）。

## 8. 测试与验收口径

- 单测：`cd backend && make test`（go vet + go test）全绿；不设 `INGRESS_TEMPLATE` 时全部现有用例原样通过即历史行为回归证明。
- e2e：`e2e/platform-e2e.sh`（REST 主链路 50 断言，覆盖发布→注册→republish→上下线→清理）在部署新镜像后全绿。
- 部署：镜像构建 → kind load → rollout restart platform-backend → 冒烟（服务列表 API、现网 Ingress 形态不变）。

## 9. 实施记录

- 2026-09-28：设计定稿，编码与测试进行中（分支 `feat/ingress-template`）。
