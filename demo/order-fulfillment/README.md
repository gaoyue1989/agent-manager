# order-fulfillment 多 Agent 协同演示（Agent Protocol 远程子 agent）

> 对齐 AgentScope 官方案例 [order-fulfillment](https://java.agentscope.io/v2/zh/service/cases/order-fulfillment)
> 的完整用例语义（5 agent + 6 业务工具 + 诊断→审批→执行闭环），按
> [travel-fulfillment-agent-protocol-design.md](../../docs/design/travel-fulfillment-agent-protocol-design.md)
> §0 的适配落地：**不引入 aistio Service 平台**，以 Agent Protocol（`/tasks` 远程子 agent）
> + OAF 平台（K8s Deployment/Service/Ingress）替代。
>
> **演示部署指南（含关键场景截图与演示剧本）见 [DEMO_GUIDE.md](DEMO_GUIDE.md)。**
> 状态：**5 服务已部署，e2e 28/28 通过**（2026-09-30，镜像 `agentscope-2.1.0-v20260930-2`）。

## 1. 架构

```
用户 / e2e 脚本
   │  POST :8911/agent/fulfillment-lead/threads/chat (SSE)
   ▼
┌─ fulfillment-lead（OAF 服务，lead，协议关）──────────────────────────┐
│ HarnessAgent（两阶段协议提示词：诊断四委派 → 方案 → 批准后执行）      │
│  ├─ 远程子 agent 声明（AGENTS.md agents[].endpoint）：                │
│  │   order-agent / inventory-agent /                                 │
│  │   logistics-agent / after-sales-agent（svc 直连 + token）          │
│  ├─ agent_spawn（RemoteSpawnForceSyncMiddleware 注入 force_sync，    │
│  │   阻塞等子任务完成、结果确定性回流）                               │
│  ├─ RemoteUserIdMiddleware / RemoteSpawnCaptureMiddleware            │
│  └─ RemoteConfirmBridge（确认卡快照轮询/决策路由/终态唤醒/后台收割）  │
└──────────┬──────────┬──────────┬──────────┬──────────────────────────┘
           │ Agent Protocol（POST /tasks，svc 直连 + X-Agent-Protocol-Token）
           ▼          ▼          ▼          ▼
      order-agent  inventory  logistics  after-sales（4 member，协议开）
      get_order    get_inventory  get_logistics  get_policy/get_resolution
                                                create_resolution（写，L3 校验）
           │
           ▼
   biz-mcp（业务 mock MCP，streamableHttp，K8s Deployment）
   6 工具对齐官方案例契约；L3：expected_version 乐观并发 + plan_id 幂等
```

角色分工对齐官方案例：诊断阶段四个专员只读（订单/库存/物流/政策）；写操作归售后专员
（`create_resolution`），人工闸门在父级 plan 批准（设计 §6.1 L2，规避 SDK F17 批准续跑缺陷），
服务端 L3 校验兜底——**模型口的"批准"不是授权**。

## 2. 资产清单

| 路径 | 说明 |
|---|---|
| `packages/fulfillment-lead/AGENTS.md` | 主管包（v1.1.0）：两阶段协议（诊断四委派含物流/政策）+ 后台任务收割规约；`agents[].endpoint` 声明四个远程子 agent |
| `packages/order-agent/` | 订单专员包（v1.1.0）：`get_order`（只读；写操作已归售后专员） |
| `packages/inventory-agent/` | 库存专员包：`get_inventory`（只读） |
| `packages/logistics-agent/` | 物流专员包：`get_logistics`（运单事实与时效，只读） |
| `packages/after-sales-agent/` | 售后专员包：`get_policy` + `create_resolution`（写，L3 契约）+ `get_resolution` |
| `mcp/biz_mcp.py` | 业务 mock MCP（streamableHttp，零依赖）：6 工具对齐官方契约，订单/库存/物流/政策内存态 + L3 校验 |
| `k8s/biz-mcp.yaml.tmpl` | biz-mcp ConfigMap+Deployment+Service 模板（脚本内嵌渲染） |
| `deploy.sh` | 一键部署：渲染 K8s 清单 → 打包上传平台 → 发布 5 服务（env/secret 路由） |
| `e2e/order-fulfillment-e2e.mjs` | 端到端验证（T0–T7 共 28 断言，零依赖 node>=18） |
| `docs/img/` | 关键场景截图（服务列表/诊断方案/L3 拒绝/执行交付/member 透传） |
| `DEMO_GUIDE.md` | **演示部署指南**（前置条件/一键部署/演示剧本/截图/e2e/排查） |

框架侧依赖（master，PR #62/#66/#67 已合并）：member 协议装配（`AgentProtocolConfig` +
`AgentProtocolAuthFilter`）、lead 接线（远程声明/身份规范化/spawn 抓取/force_sync）、
确认桥（快照轮询/决策路由/终态唤醒/后台收割/幽灵卡治理）、MCP 连接看门狗、
deny_rules 动态 DENY、平台侧协议敏感键 Secret 路由与 A2A 幂等 Job 端点。

## 3. 快速开始

```bash
./deploy.sh                            # 一键部署（biz-mcp + 5 OAF 服务）
node e2e/order-fulfillment-e2e.mjs     # 端到端验证（28 断言）
# 演示入口：http://127.0.0.1:8911/agent/fulfillment-lead/debug
```

部署要点（首次执行记录）：

1. **镜像**：`agent-framework` 多阶段 Dockerfile 构建，推送 `172.20.0.1:5001/agent-framework:latest`
   与带日期 tag（平台 `AVAILABLE_IMAGES` 白名单需包含所用 tag）。节点 imagePullPolicy=IfNotPresent，
   **同 tag 重推后节点缓存不会自愈**——需 `kind load docker-image` 直灌节点再重启/republish。
2. **biz-mcp**：kind 节点无法直拉 Docker Hub，`python:3.12-alpine` 需推入本地 registry
   （`172.20.0.1:5001/python:3.12-alpine`）。
3. **发布**：5 个 OAF zip 经 `POST /api/v1/packages` 上传，`POST /api/v1/services` 发布；env 沿用
   现网 release-agent 的平台基础设施值，成员加 `AGENT_PROTOCOL_ENABLED=true` +
   `AGENT_PROTOCOL_AUTH_TOKEN`，lead 加 `AGENT_REMOTE_HEADERS_JSON`（同 token）——协议敏感键现已
   进平台模板清单自动路由 `{name}-env-secret`（P2-1），`secretKeys` 逃生门不再必要。
4. **LLM key**：成员/lead 的 `LLM_API_KEY` 失效会以 "Handle Agent execute error ... 401" 形态
   出现在回复中——`PATCH /services/:id/env` 更新（走服务 Secret）即可，滚动自愈。
5. **就绪后注册**：平台自动拉取各服务 `/.well-known/agent-card.json`（含 `agent_protocol` 状态）；
   成员装配日志确认 `Agent Protocol enabled` 两 bean override + MCP biz 注册 + 权限覆盖无缺口。

## 4. e2e 验证（T0–T7，28 断言）

运行：`node demo/order-fulfillment/e2e/order-fulfillment-e2e.mjs`

最近一次全绿运行（2026-09-30，订单 O-1003，plan_id=PLAN-E2E-…）：

```text
== T0 前置健康 ==            ✅ 三服务健康 + mock 订单事实可查
== T1 远程诊断 ==            ✅ 委派子 agent / 诊断零写 / get_order 落 mock
== T1b member 直驱 ==        ✅ get_inventory 调用 + 落 mock + 事实回流
== T2 处置方案 ==            ✅ 方案（expected_version/plan_id）+ 引用订单 + 等待批准
                             ✅ 物流委派落 mock + 政策核对落 mock + 方案引用 POL-v3.2
== T3 批准后执行 ==          ✅ 写委派 + create_resolution 恰 +1 + plan_id 锚定/版本推进
== T4 执行交付 ==            ✅ 真实 RES 编号回流汇总
== T5 L3 版本拒绝 ==         ✅ VERSION_CONFLICT
== T6 L3 幂等拒绝 ==         ✅ 首次成功（对照）+ 重放 IDEMPOTENT_REJECT
== T7 /tasks 鉴权 ==         ✅ 4 个协议成员无 token 一律 401

结果：28 通过 / 0 失败
```

断言分层：框架保证类（T1 落 mock / T3 恰 +1 且 plan_id 锚定 / T5–T7 L3 与鉴权 / T2 物流与
政策计数）确定性；「等待批准」话术属模型编排观察项——闸门语义由「T3 必须有显式批准轮才
发起写委派」框架性保证。

## 5. 本轮实测沉淀（框架级）

| # | 发现 | 处置 |
|---|------|------|
| F20 | PROPAGATE 会把子 ask 转发进父流并被落成 `local` 行，但 local 行确认**不转发决策给远程任务**（幽灵卡） | ~~治理转后续迭代~~ **已修（PR #66）**：捕获侧按 tool_call_id 对撞远程行锚点抑制 + Bridge 落卡侧清理（GHOST_LOCAL_PURGED 审计） |
| F21 | F17 新形态：resume(approved) 后批准未生效，同工具以新 toolCallId 重新 ask | 一期闸门上移父级 plan 批准再确认（持续跟进 SDK） |
| F22 | SDK AgentSpawnTool schema 主键为 `agent_id`；taskId 解析须剥离尾随引号 | PR #62 已修（含回归单测） |
| F23 | 远程 spawn 恒异步受理，结果收割靠模型自觉不可靠 | `RemoteSpawnForceSyncMiddleware`（force_sync）；**后台任务终态由 Bridge 确定性唤醒收割（PR #66）** |
| F24 | 内建工具白名单缺 `web_fetch/web_search/load_skill_through_path` → DEFAULT 模式未覆盖 ASK | `BUILT_IN_TOOL_NAMES` 补齐 + **防漂移测试（PR #66，扫描 SDK @Tool 全集）** |
| F16 | 扩展不消费 `context.deny_rules` | **已自实现（PR #66）**：Customizer 挂 RuntimeContext + 中间件 acting 拦截动态 DENY |
| — | MCP 长连接静默失效（biz-mcp 重启后 ConnectException） | **MCP 连接看门狗（PR #66）**：失联按 swap-on-success 原地重建；streamableHttp 传输天然自愈，SSE 场景生效 |
| — | A2A message/send 非幂等 | **平台幂等 Job 端点（PR #67）**：Idempotency-Key → 稳定 taskId 锚点（认领语义防双发） |

## 6. 清理

不需要演示时，在平台服务列表依次「删除」5 个 demo 服务；biz-mcp 保留无碍（内存态）：

```bash
kubectl -n agent-platform delete deployment/biz-mcp service/biz-mcp configmap/biz-mcp-script
```
