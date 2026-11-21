# 工具调用观测（面板监控）设计

> 状态：已实施（2026-11-21，feat/tool-call-observability 分支；framework/backend/manifests/frontend/e2e 五件套，单测 Go 全绿 + Java 定向全绿 + 前端 build/lint 全绿，真实环境部署验证见 deployment.md 运维备忘）

## 1. 需求与路线

统计 agent 各类工具调用次数并做面板监控（用户侧观测大盘 + 运维侧全局页 + agent 自带 Debug/Monitor 轻量统计）。选定路线：**统一观测栈（Prometheus + Grafana）**——指标经 Micrometer → Prometheus（pull），面板经 backend 查 PromQL 聚合；最近调用明细是日志型数据、指标系统管不了，事实源仍为 framework `tool_audit_log`。Trace（Tempo/OTLP）与 Loki 本期不引入，作为观测栈后续演进（对齐观测大盘占位页原规划方向）。

## 2. 数据契约（SSOT）

| 数据 | 事实源 | 消费方 |
|---|---|---|
| 计数 / 趋势 / 失败率 | Prometheus 指标 `oaf_tool_calls_total{service, tool, result=success\|error}`（Micrometer `oaf.tool.calls`） | backend `internal/service/observability.go` PromQL 聚合 → 前端双面板；Grafana 预置仪表盘 |

**service 标签归一化（真机验证修正）**：framework 进程内不知道平台 k8s 名，OAF slug 是 `{vendor}/{agentKey}`（如 `agentmanager/release-agent`），与 backend 选择器（DB k8s 名去 `oaf-` 前缀）无机械映射——归一化在采集层做：prometheus scrape 配置 `metric_relabel_configs` 把 service 改写为 k8s 名派生值，原 slug 保留到 `service_slug` 标签便于对照。SSOT = 发布链路 k8s 名。

**计数口径补充**：PromQL 侧 = result 标签化计数器（终态调用；中断/在途不计）；SQL 侧（Monitor 端点）= TOOL_CALL_START 行（尝试口径）。二者对中断类调用略有差异，面板以 PromQL 为主。窗口计数用 `increase()`（Prometheus 外推估计口径，与 Grafana 同语义；稀疏序列有近似误差）。
| 最近调用明细 / Debug 轻量统计 | 各 framework `tool_audit_log`（30 天保留） | framework `GET /debug/monitor/tools`（Monitor 页 + backend fan-out，一端点两消费方） |

计数口径 = `TOOL_CALL_START` 行（调用尝试，含最终失败的调用）；失败 = `TOOL_RESULT_END` 行 `result_state='error'`（非 SUCCESS 终态一律 error 桶，与 Monitor 问题口径一致）。

## 3. 采集点（agent-framework）

- **`ToolCallObservabilityMiddleware`**（`onActing`，覆盖 ChatStream / AGUI / A2A JSON-RPC / Agent Protocol 全部协议链路）：
  - Micrometer 计数 + 审计写入（每调用 2 行：`TOOL_CALL_START` + `TOOL_RESULT_END` 含 result_state）
  - **行来源分工（真机验证修正）**：`ToolCallStartEvent` 在本中间件更外层才汇入事件流（内层不可见，实测漏记），START 行在 onActing 入口直接由 `ActingInput.toolCalls()` 落（不落 payload，元信息已有列）；`ToolResultEndEvent` 由工具执行内核发出、全链路可见，RESULT 行取事件侧。中断调用只剩 START 行（明细按「未落终态」展示）
  - 会话标识经 `SessionKeyResolver`（与 `FrameworkTracingMiddleware` 同实例、turn 级 memo 共享；Channel 链路 ctx.sessionId 是网关 gw-hash 不能直接用）
  - **注册位置 = ToolFilterMiddleware / ProtocolDenyRulesMiddleware 内侧**：被过滤/DENY 短路的调用不经过本层，只计真实执行的调用（装配锚点测试锁位置）
- **SSOT 重构**：ChatStreamController / AguiRunController 各自的审计写点已删除（此前 A2A 链路漏记即因写点分散）；`RemoteConfirmBridge` HITL 决策审计语义不同，保留
- **V17 迁移**：`tool_audit_log` 增 `result_state` 列 + `idx_tool_created(tool_name, created_at)` + `idx_tool_call_id`（存量行 result_state 为 NULL，聚合按未失败计）

## 4. 指标暴露与抓取

- `micrometer-registry-prometheus`；`management.server.port: 9091`（管理端口独立，Service/Ingress 只暴露业务 8100，指标端点不出数据面）
- 业务 Deployment Pod 模板注解（backend `internal/k8s/objects.go`）：`prometheus.io/scrape=true / port=9091 / path=/actuator/prometheus`；Prometheus kubernetes_sd(pod) 注解发现。**存量服务 republish 后生效**（不自动迁移，遵循 env 固化同口径）

## 5. 聚合与面板

- backend `observability.go`：六发 instant 并行（totals/errors/byTool/byToolErr/bySvc/bySvcErr，`increase(...[W])`）+ 双系列 query_range 趋势（step 即 rate 窗口：24h→1h、7d→6h、30d→1d，缺失桶补 0）；byService 与 DB 服务清单全外联（窗口内无序列 = HasData=false：离线/零调用/存量未 republish）；明细 fan-out 并行拉各 agent `/debug/monitor/tools?limit=50`，单服务失败静默跳过，合并倒序取 50 附服务名
- 端点（鉴权沿用分组中间件）：
  - `GET /api/v1/console/workspaces/:ws/observability/tool-calls?range=24h|7d|30d`（空间隔离）
  - `GET /api/v1/admin/observability/tool-calls?range=...`（全平台）
  - range 校验先行（400 确定性）；观测栈不可达 503（前端展示明确错误态，而非静默 0）
- 前端共享组件 `ToolCallStatsPanel`：4 张 StatCard + 工具排行水位条 + 趋势 div 柱状（零新依赖）+ Agent 维度表格 + 最近明细；10s 静默门控轮询（失败保留旧数据）。落点：用户侧 `/console/observability`（占位页替换，导航已预留）、运维侧 `/admin/observability`
- Debug/Monitor 轻量统计：`/debug/monitor/tools`（每工具聚合 + 最近明细）+ overview 增 `tool_calls_today` + Monitor 页第三视图「工具调用」（点工具行筛选明细，15s 自动刷新沿用）

## 6. 观测栈自举（manifests）

- `prometheus.yaml`：kubernetes_sd + 注解 relabel（namespace 级 RBAC）、platform-data subPath `prometheus/` 持久化（**Recreate 部署策略**——TSDB 目录锁独占，滚动更新新旧 Pod 并发持锁会 CrashLoop）、retention 31d（≥ 面板最大窗口 30d）、自抓取、metric_relabel service 归一化（见 §2）
- `grafana.yaml`：数据源/预置「OAF 工具调用」仪表盘 provisioning、admin 凭据 Secret 占位（部署覆盖）；本期仅 ClusterIP（port-forward 访问，见 deployment.md）；charts（oaf-platform）观测栈 chart 化另立 issue
- `PROMETHEUS_URL`：platform.yaml 注入 backend；空 = 观测栈未部署（面板 503 错误态）

## 7. 明确不做（本期）

- Tempo/OTLP trace、Loki 日志聚合（后续演进）
- charts 观测栈 chart 化、Grafana 外部 Ingress、告警规则
- 后端 Go 进程自身 /metrics 暴露（面板指标全部来自业务 agent）
