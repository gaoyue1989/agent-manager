# order-fulfillment 多 Agent 协同演示（Agent Protocol 远程子 agent）

> 对齐 AgentScope 官方案例 [order-fulfillment](https://java.agentscope.io/v2/zh/service/cases/order-fulfillment) 的两阶段履约语义，按 [travel-fulfillment-agent-protocol-design.md](../../docs/design/travel-fulfillment-agent-protocol-design.md) §0 的适配落地：**不引入 aistio Service 平台**，以 Agent Protocol（`/tasks` 远程子 agent）+ OAF 平台（K8s Deployment/Service/Ingress）替代。
>
> 状态：**已部署 + e2e 验证通过**（本文件 §4 有逐项证据）。

## 1. 架构

```
用户 / e2e 脚本
   │  POST :8911/agent/fulfillment-lead/threads/chat (SSE)
   ▼
┌─ fulfillment-lead（OAF 服务，K8s Deployment）────────────────────┐
│ HarnessAgent（两阶段协议提示词）                                  │
│  ├─ 远程子 agent 声明（AGENTS.md agents[].endpoint）：             │
│  │   order-agent     → http://order-agent.agent-platform.svc:8100 │
│  │   inventory-agent → http://inventory-agent.agent-platform.svc:8100
│  ├─ agent_spawn（RemoteSpawnForceSyncMiddleware 注入 force_sync， │
│  │   阻塞等子任务完成、结果确定性回流）                            │
│  ├─ RemoteUserIdMiddleware / RemoteSpawnCaptureMiddleware         │
│  └─ RemoteConfirmBridge（确认卡快照轮询/决策路由/终态唤醒）        │
└───────────┬──────────────────────────────┬───────────────────────┘
            │ Agent Protocol（POST /tasks，svc 直连 + token）
            ▼                              ▼
┌─ order-agent（OAF 服务）─┐   ┌─ inventory-agent（OAF 服务）─┐
│ AGENT_PROTOCOL_ENABLED=1 │   │ AGENT_PROTOCOL_ENABLED=1      │
│ AgentProtocolAuthFilter  │   │ AgentProtocolAuthFilter       │
│ MCP: get_order(allow)    │   │ MCP: get_inventory(allow)     │
│      create_resolution   │   └───────────────────────────────┘
│      （allow + L3 校验）  │
└──────────┬───────────────┘
           ▼
   biz-mcp（业务 mock MCP，K8s Deployment）
   get_order / get_inventory / create_resolution
   L3：expected_version 乐观并发 + plan_id 幂等（重放拒绝）
```

一期人工闸门（设计 §6.1 L2）：**执行需批准 = 父级 plan 批准**（lead 输出处置方案 JSON → 用户批准 → 才发起写委派）；子服务写工具 allow + L3 服务端校验兜底（规避 SDK F17 批准续跑缺陷）。

## 2. 资产清单

| 路径 | 说明 |
|---|---|
| `packages/fulfillment-lead/AGENTS.md` | 主管包：两阶段协议 + 后台任务收割规约；`agents[].endpoint` 声明两个远程子 agent |
| `packages/order-agent/` | 订单专员包：`get_order`（只读）+ `create_resolution`（写，L3 契约） |
| `packages/inventory-agent/` | 库存专员包：`get_inventory`（只读） |
| `mcp/biz_mcp.py` | 业务 mock MCP（streamableHttp，零依赖）：订单/库存状态内存态 + L3 校验 |
| `k8s/biz-mcp.yaml` | biz-mcp ConfigMap+Deployment+Service（agent-platform 命名空间） |
| `deploy.sh` | 一键部署：渲染 K8s 清单 → 打包上传平台 → 发布三服务（env/secret 路由） |
| `e2e/order-fulfillment-e2e.mjs` | 端到端验证（T0-T6，零依赖 node>=18） |

框架侧依赖（PR #62 分支）：member 协议装配（`AgentProtocolConfig` + `AgentProtocolAuthFilter`）、lead 接线（远程声明/身份规范化/spawn 抓取）、确认桥、以及本轮新增的 **RemoteSpawnForceSyncMiddleware**（§3）。

## 3. 部署过程要点（实际执行记录）

1. **镜像**：`agent-framework` 多阶段 Dockerfile 构建（M1 + 本轮修复），推送 `172.20.0.1:5001/agent-framework:latest`（平台 `AVAILABLE_IMAGES` 白名单既有项、默认镜像）。节点 imagePullPolicy=IfNotPresent，替换 tag 后需 `crictl rmi` 清节点陈旧缓存再 republish。
2. **biz-mcp**：kind 节点无法直拉 Docker Hub，改为 `python:3.12-alpine` 推入本地 registry（`172.20.0.1:5001/python:3.12-alpine`）后由节点拉取。
3. **发布**：三个 OAF zip 经 `POST /api/v1/packages` 上传，`POST /api/v1/services` 发布（image=latest、replicas=1）；env 沿用现网 release-agent 的平台基础设施值（LLM/CHECKPOINT/REDIS），成员服务加 `AGENT_PROTOCOL_ENABLED=true` + `AGENT_PROTOCOL_AUTH_TOKEN`，lead 加 `AGENT_REMOTE_HEADERS_JSON`（同 token）——敏感键经请求 `secretKeys` 显式路由 `{name}-env-secret`，不落 ConfigMap。
4. **就绪后注册**：平台自动拉取各服务 `/.well-known/agent-card.json`；成员装配日志确认 `Agent Protocol enabled` 两 bean override + MCP biz 注册成功 + 无权限覆盖缺口。

## 4. e2e 验证（T0-T6）

运行：`node demo/order-fulfillment/e2e/order-fulfillment-e2e.mjs`

运行：`node demo/order-fulfillment/e2e/order-fulfillment-e2e.mjs`

最近一次全绿运行（2026-09-29，订单 O-1002 / SKU-7K，plan_id=PLAN-E2E-B26F809868）：

```text
== T0 前置健康 ==
  ✅ fulfillment-lead /health=200
  ✅ order-agent /health=200
  ✅ inventory-agent /health=200
  本轮订单：O-1002（sku=SKU-7K, version=4），plan_id=PLAN-E2E-B26F809868
  ✅ mock 订单事实可查
== T1 远程诊断（lead 委派 order-agent，Agent Protocol 远程 spawn）==
  ✅ 委派子 agent（agent_spawn）
  ✅ 诊断阶段零写（无 create_resolution）
  ✅ 子任务真实执行（get_order 落 mock，远程 member 实际调用）
== T1b member 工具路径（inventory-agent 直驱）==
  ✅ inventory member 调用 get_inventory
  ✅ get_inventory 落 mock
  ✅ 库存事实回流（仓级/调拨）
== T2 处置方案（lead 汇总并给出方案，等待批准）==
  ✅ 产出处置方案（expected_version/plan_id）
  ✅ 方案引用本轮订单
  ✅ 等待用户批准（未自行执行）
== T3 批准后执行（全参数批准指令 → 远程写委派）==
  ✅ 执行委派（agent_spawn）
  ✅ 子任务真实落单（create_resolution 恰 +1）
  ✅ 处理单锚定批准方案（plan_id 匹配 + 版本推进）
== T4 执行交付 ==
  ✅ 真实处理单编号回流汇总
== T5 L3 版本拒绝（服务端硬约束）==
  ✅ 最新订单版本可查
  ✅ 版本不符 → VERSION_CONFLICT
== T6 L3 幂等拒绝（plan_id 先消费后重放）==
  ✅ 同 plan 首次提交成功（对照）
  ✅ plan_id 重放 → IDEMPOTENT_REJECT
== T7 /tasks 鉴权（经 Ingress，断言 11）==
  ✅ order-agent /tasks 无 token → 401
  ✅ inventory-agent /tasks 无 token → 401

结果：23 通过 / 0 失败
EXIT=0
```

断言分层说明：框架保证类断言（T1 远程诊断落 mock / T3 批准后恰 +1 落单且 plan_id 锚定批准方案 / T5-T7 L3 与鉴权）确定性；T2「等待批准」话术属模型编排观察项——框架侧由「T3 必须有显式批准轮才发起写委派」保证闸门语义。

## 5. 演示对话剧本（人工复现）

1. 打开平台前端 → 发布助手/调试页选择 `fulfillment-lead` 会话。
2. 发送：「订单 O-1001 迟迟未发货（承诺 2026-09-15 送达），请处理。先完成诊断。」
   → 观察：lead 并行委派 order-agent（get_order）与 inventory-agent（get_inventory），诊断事实回流（订单 status/version、仓级可用量），随后输出处置方案 JSON（含 expected_version / plan_id）并**停下等待批准**。
3. 发送：「我批准该处置方案，请立即按方案执行创建处理单。」
   → 观察：lead 委派 order-agent 创建处理单（携带方案中的 expected_version/plan_id），真实 RES- 编号回流并汇总交付。
4. 反证（可选，直接调 biz-mcp 或查审计）：重复同一 plan_id → `IDEMPOTENT_REJECT`；篡改 expected_version → `VERSION_CONFLICT`。

## 6. 本轮实测沉淀（框架级）

| # | 发现 | 处置 |
|---|------|------|
| F20 | PROPAGATE 会把子 ask 转发进父流并被落成 `local` 行，但 local 行确认**不转发决策给远程任务**（幽灵卡） | Bridge routeDecision 是唯一有效决策路径（§5.4 强化）；治理转后续迭代 |
| F21 | F17 新形态：resume(approved) 后批准未生效，同工具以新 toolCallId 重新 ask | 一期闸门上移父级 plan 批准再确认 |
| F22 | SDK AgentSpawnTool schema 主键为 `agent_id`；taskId 解析须剥离尾随引号 | PR #62 已修（含回归单测） |
| F23 | 远程 spawn 恒异步受理，结果收割靠模型自觉不可靠 | 框架新增 `RemoteSpawnForceSyncMiddleware`（注入 SDK force_sync 属性，默认开） |
| F24 | 内建工具白名单缺 `web_fetch/web_search/load_skill_through_path` → DEFAULT 模式未覆盖 ASK | `BUILT_IN_TOOL_NAMES` 补齐 |

## 7. 清理

```bash
# 平台下线三服务（网页或 API）：POST /api/v1/services/{id}/unpublish
kubectl -n agent-platform delete deployment,service,configmap -l demo=order-fulfillment
```
