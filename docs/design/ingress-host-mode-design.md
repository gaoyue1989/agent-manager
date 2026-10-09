# Ingress host 模式（INGRESS_HOST_SUFFIX 双模式）— 设计与实施记录

> 状态：**已实施**（Issue #75，2026-09-30）。本文描述实现时点的设计；现状以
> [../deployment.md](../deployment.md) 与 [../../backend/AGENTS.md](../../backend/AGENTS.md) 为准。
> 承接 [ingress-template-design.md](ingress-template-design.md)（INGRESS_TEMPLATE overlay 门面，
> 本期在其上做双模式扩展，而非新建门面）。

## 1. 背景

平台内置的 Ingress 构造只有一种形态：**无 host，基于 path + rewrite**——所有服务共享
ingress 入口 IP，靠 `/agent/{short}` 前缀区分路由。部分环境（独立测试集群、生产）
要求每服务**独立域名**：`host = {K8sName}{zone-suffix}`、`path = /`、pathType `Prefix`，
无需 rewrite。

## 2. 方案

新增环境变量 `INGRESS_HOST_SUFFIX`（默认空）切换生成形态，**双模式并存**：

| `INGRESS_HOST_SUFFIX` | 模式 | 内置形态 |
|---|---|---|
| 空（默认） | path | 无 host；path `/agent/{short}(/|$)(.*)`、pathType ImplementationSpecific；rewrite-target=`/$2`、use-regex=true、x-forwarded-prefix=`/agent/{short}` |
| 非空（`.region-c86-test.test-kzx1.cncb`） | host | host=`{K8sName}{suffix}`、path `/`、pathType Prefix；**仅** ssl-redirect=false + proxy-read/send-timeout=3600，无 rewrite 注解 |

**模式判定一律按配置**（`ObjectParams.IngressHostSuffix != ""`），`Ingress()` 构造、
`validateIngress` 校验与 `IngressEndpoint` 派生共用同一依据——不从 path 尾缀嗅探
（overlay 改 path 后嗅探会误判，属 issue 评审修正的脆弱设计）。

## 3. 与 Issue 原文的两处偏差（已核实并按实际代码定稿）

1. **overlay 门面已存在**（PR #59）：`IngressBuilder`/`NewIngressBuilder`/`validateIngress`/
   `IngressEndpoint`/`INGRESS_TEMPLATE` 在实施时均已落地，本期为**在既有门面上扩双模式**，
   不新建。
2. **`NewIngressBuilder` 签名取三参** `(path, ingressClass, ingressHostSuffix)` 而非 issue
   写的两参：`ingressClass` 是 36b5f57「探针与发布期同参」修复的一部分，删掉会让启动探针
   与发布期不同参。`ingressHostSuffix` 同理——探针必须与发布期同模式，否则 host 模式的
   overlay 会在启动期被误拒。**运维含义**：探针同模式意味着模式不匹配的 overlay 是
   **启动即失败**（`main.go` 的 `log.Fatalf` → backend CrashLoop → 平台 API/UI 整体不可用），
   而非"个别服务发布被拒"；切换 `INGRESS_HOST_SUFFIX` 前须先把 `INGRESS_TEMPLATE` 改成
   对应形态的样例（见 docs/deployment.md §七之二的前置检查）。

## 4. 改动清单

| 文件 | 改动 |
|------|------|
| `backend/config/config.go` | `Config.IngressHostSuffix` + `Load()` 读 env + `validateIngressHostSuffix` fail-fast |
| `backend/internal/k8s/objects.go` | `ObjectParams.IngressHostSuffix`；`Ingress()` 双模式分支；新增 `pathTypePtr`/`ingressBackend` helper |
| `backend/internal/k8s/template.go` | `NewIngressBuilder` 加 hostSuffix 参（探针同参）；`validateIngress` 分模式；`IngressEndpoint(ing, host, port, hostSuffix)` 分模式 |
| `backend/internal/service/publish.go` | `ConfigView.IngressHostSuffix` + `params()` 透传；`applyAll` 返回已落集群的 `*Ingress`；`StartAgain` 补 `refreshEndpoint` 重算落库 |
| `backend/cmd/server/main.go` | 装配传参 |
| `backend/internal/k8s/k8sfake/fake.go` | `EnsureIngress` 补「已存在则整体覆盖 spec/annotations/labels 后 Update」分支，与 `client.go` 的 `RealClient` 同形（见 §5 末条） |
| `backend/config/config_test.go` | 后缀格式校验（空值放行 / 6 类非法 / 186 字符压线） |
| `backend/internal/k8s/objects_test.go` | host 模式内置构造；`testParams()` 不设 suffix 作 path 模式回归锁 |
| `backend/internal/k8s/template_test.go` | host 模式 overlay 合并（rewrite 三项空串放行）、违规 overlay 表（含 path 非根、rewrite 三项非空）、探针正反例；`TestIngressEndpointTable` 加 host 模式维度 |
| `backend/internal/service/publish_test.go` | host 模式发布端到端（fake 集群 Ingress 形态 + Endpoint 落库）；path→host 切换后 StartAgain 重算 |
| `backend/templates/ingress-overlay.example.yaml`、`backend/AGENTS.md`、`docs/deployment.md`、根 `AGENTS.md` | 示例按 P1-P3/H1-H3 分组并写明校验随模式切换；契约与运维姿势文档 |

## 5. 关键决策

- **配置层 fail-fast**：后缀必须以 `.` 开头、去点后为合法 DNS-1123 subdomain（整体形态）**且逐段为合法 DNS label（各段 ≤63）**、且叠加 K8sName 后总长 ≤253。前两条不可互相替代：`IsDNS1123Subdomain` 只做点分正则 + 整体 253，其 `dns1123SubdomainFmt` 正则不含每段长度约束（每段 ≤63 是 `IsDNS1123Label` 的职责），只跑前者会让 64 字符的段放行。
  **K8sName 上界是 67 不是 63**——`DeriveK8sName = Prefix + SanitizeK8sName(base)`，而 `SanitizeK8sName` 内部先截断到 63 再前置 `oaf-`（objects.go），按 63 校验会让超长名服务生成 >253 的非法 host（即后缀上界 186 而非 190）。拼接后首段（`{K8sName}{后缀首段}`）可能超 63，**不额外拒绝**：K8s 对 `rules[].host` 只强校验 253 总量（issue §5 的不变量亦只写 253），且平台本就在别处产出超 63 的对象名（`{K8sName}-env-secret` 等）。
- **host 模式 host 与 path 均取严**：host 必须等于 `{K8sName}{suffix}`（issue §4.3 伪码放过空 host，§5 表要求必须相等——按严格实现，否则会退化成「共享入口 IP + 承载根路径」、流量串服务）；path 恒为 `/` —— Endpoint 固定派生为根 URL，若放开 path 会让落库地址与实际路由错位、前端链接 404。
- **rewrite 三项从「豁免」收紧为「必须为空」**（issue §4.3 只写「不要求」，实现期评审提出）：「不校验」等于任意值都放行，而 `x-forwarded-prefix` 有明确下游消费者——agent-framework `DebugController`（`DebugController.java:25-38`）读 `X-Forwarded-Prefix`，非空即 302 到 `{该值}/debug/`，从 path 模式模板照抄写出的错前缀会让 `/debug` 直接 404；`rewrite-target` 则可能被 ingress-nginx 用于改写整条 location（是否生效随 controller 版本，本期无集群未验证）。内置构造三项一个都不生成，收紧不影响任何合法 host 模式 overlay，path 模式分支不动。校验口径：注解不存在或值为空串均放行。
- **k8sfake.EnsureIngress 补齐更新分支**：原实现只有「不存在则 Create」，与 `client.go` 的 RealClient 不同形，导致「重新发布/重新上线把 Ingress 刷成新形态」在测试中不可见（path↔host 模式切换用例因此失败）。已按 client.go 补 spec/annotations/labels 覆盖 + Update。
- **`ingressClassName` 校验保持现状**（nil 放行、非 nil 必须等于配置值）：设计文档
  ingress-template-design.md 明确「置 null 视为交集群默认，允许」，收紧属独立行为变更。
- **存量服务不做后台批量迁移**：`EnsureIngress` 整体覆盖 `spec`，模式切换后经
  Republish / StartAgain 即收敛；`StartAgain` 原先不写 endpoint，本期补 `refreshEndpoint`
  同步重算落库（写库失败只记日志不阻断，与 `transition()` 同风格）。`UpdateEnv` 不触
  Ingress，仍不改 endpoint。
- **`applyAll` 改为返回 `*networkingv1.Ingress`**：让 `StartAgain` 复用已 Build 对象，
  避免二次 Build 且不新增失败路径；其余三个调用点忽略返回值。

## 6. 测试口径

- host 模式内置构造（host/path/pathType/backend/注解恰为三条且无 rewrite 相关）
- path 模式逐字符回归锁（`testParams()` 不设 suffix，既有断言与 `String()` 比对零改动）
- host 模式 overlay 改 annotations 仍守住 host/path/backend 不变量；rewrite 三项空串放行、非空拒绝
- 违规 overlay 表（改 host / 空 host / 改 backend / 丢 timeout / 丢 pathType / 加
  defaultBackend / 丢 rules / path 非根 / rewrite 三项非空）逐条拒绝
- `NewIngressBuilder` 探针同模式：host 模式 overlay 过探针；硬编码探针域名只能骗过探针、
  发布期被拒
- Endpoint 派生两模式 + TLS（https）
- suffix 格式校验（非 `.` 开头 / 大写 / 非法字符 / 空 label / 单段超 63 / 叠加超 253）→ Load 报错
- host 模式 Publish 端到端（fake 集群 Ingress 形态 + Endpoint 落库）、path→host 切换后
  StartAgain 重算

**未覆盖**：host 模式（根路径、无 x-forwarded-prefix）下 Debug Console 与 A2A 链路需真机
回归（真实域名后缀的 DNS 解析 + 放通 A2A blocking/SSE），需在 kind 上配
`INGRESS_HOST_SUFFIX` 后手工验证。注册链路本身不经过 Ingress（`service/register.go` 走
集群内 Service DNS `svc.ClusterURL`），host 模式不破坏注册。
