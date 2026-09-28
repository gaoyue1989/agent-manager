# 设计文档归档（docs/design/）

本目录收录**历史设计文档与环境搭建记录**。它们描述各自编制时点的设计方案或一次性操作过程，
不代表系统当前状态；阅读时请以下列现状文档为准：

- 平台现状：[../deployment.md](../deployment.md)、[../oaf-specification.md](../oaf-specification.md) 与各模块 AGENTS.md
- agent-framework 现状：[../../agent-framework/AGENTS.md](../../agent-framework/AGENTS.md)、[../../agent-framework/docs/README.md](../../agent-framework/docs/README.md)（模块文档索引）

## 归档清单

| 文档 | 状态 | 说明 |
|------|------|------|
| [REDESIGN.md](REDESIGN.md) | 已实施（2026-08-25 完工，附录 B 实施记录延续至 2026-09-07） | v1→v2 平台重构总体设计（目标/架构/组件/数据模型/测试策略 + 实施记录）。此后的演进（Redis 事件流、HITL 参数确认、durable SSE、history 权威化、CI 级 E2E）见各模块 AGENTS.md 与 agent-framework/docs 对应设计文档 |
| [archive-PLAN-v1.md](archive-PLAN-v1.md) | 已废弃 | v1 执行计划（DeepAgents 代码生成 + agent-sandbox CRD + MinIO 架构），v2 重构后整体废弃；旧实现见 git tag `v1-archive` |
| [01-k8s-deployment.md](01-k8s-deployment.md) | 一次性记录（2026-05-01） | Kind 集群环境搭建记录：kubectl/kind 安装、集群创建、端口规划（集群配置模板见 [../kind-config.yaml](../kind-config.yaml)） |
| [14-ingress-controller-deployment.md](14-ingress-controller-deployment.md) | 一次性记录（2026-05-03） | ingress-nginx Controller 部署方案与实施过程 |
| [oaf-a2a-a2ui-protocols.md](oaf-a2a-a2ui-protocols.md) | 概念仍有效，v1 集成映射过时 | OAF v0.8.0 / A2A v1.0.0 / A2UI v0.8 协议关系参考；文中「组件映射/集成链路」描述的是 v1 架构（codegen/sandbox），已被 REDESIGN.md v2 取代 |
| [llm-context-length-config-design.md](llm-context-length-config-design.md) | 已实施（2026-09-18） | `LLM_CONTEXT_LENGTH` 环境变量配置设计（模型上下文窗口大小） |
| [release-agent-mcp-app-hitl-design-acceptance.md](release-agent-mcp-app-hitl-design-acceptance.md) | 部分实施（原生确认卡已上线；草稿工具/MCP App iframe 等原始设计未实现） | 发布助手变更确认（HITL）设计、实施与验收记录（2026-09-17/18 两轮真实集群 E2E 证据） |
| [package-online-edit-design.md](package-online-edit-design.md) | 已实施（2026-09-22 合并，E2E 34 断言全绿） | OAF 包在线预览/在线编辑生成新版本（copy-on-write）/republish 发布更新的设计与实施记录 |
| [user-skill-admin-design.md](user-skill-admin-design.md) | 已实施（2026-09-23 终审补丁后全量单测 825 用例实跑 0 失败） | 用户技能（L4）管理面 `/skills/users/*` + 调试页「用户技能」区块；L4 存储形态、删除回落/下发语义、生效范围分档；沙箱档 `skill_manage` 写 L4 不落库的根因与 `WorkspaceSyncService` 回写修复；回写仲裁两个 KV 标记（`.deleted`/`.admin-override`）与标记/截断可见性、索引失败 500、E2E（E8/E9）与 UI 断言口径 |
| [oaf-tools-extraction-design.md](oaf-tools-extraction-design.md) | 已实施（2026-09-24，一期+二期一次落地） | OAF 打包工具自 agent-framework 迁出：check_oaf_package/create_oaf_zip 并入 backend platform-publisher MCP（直建包、零 base64）、框架新增通用 present_url + /files/{id} external 代理下载、F12/U13 门禁迁移、发布时序（先框架后 backend 再 republish）与已知偏差记录 |
| [agent-framework-eval-dual-track-design.md](agent-framework-eval-dual-track-design.md) | 设计定稿（待实施，2026-09-25） | agent-framework 双轨评测系统：门禁轨（扩展现有 e2e，mock LLM 确定性断言，第七项必需检查）+ 趋势轨（Python + AgentScope evaluate + OpenJudge 打分，nightly/发版，advisory 不阻断）；真实 SSE 帧词表契约（含 v1 方案帧名错误对照）、双视角轨迹采集（客户端 SSE + /subscribe 回放）、diff 驱动用例生成四道闸门（PR 人审）、根因分析与修复 patch 建议（不直改代码）、git 快照基线；裁剪掉知识库/自动修复/自动基线/评测自有 MySQL（持久化走文件 + GitHub Issues）（评审决议） |
| [agent-framework-eval-env-provisioning-design.md](agent-framework-eval-env-provisioning-design.md) | 已实施（2026-09-26，验收轮 ca9085d..cb2f222 预检全绿 + 手写 12 用例全 PASS） | 评测环境按需供给（变更驱动 provisioning）：变更→env_spec 规则、OAF 包组装器（base+覆盖层：plugins/mcp-configs/AGENTS.md，manifest 留痕）、mock MCP 服务（allow/ask 工具目录）、实例生命周期与契约预检（插件注册/mock 注册/reload 回路/模型切换探针）、用例 `requires_env` 门禁；补齐飞轮对 OAF 包级变更（工具插件/HITL 摘要/reload/会话模型）的覆盖缺口 |
| [platform-default-config-secret-design.md](platform-default-config-secret-design.md) | 已实施（2026-09-28，R3 修订同日完成，E2E 两套全绿，见 §11） | 平台默认配置与敏感键 Secret 化：每服务发布即创建 `{name}-env-secret`，用户 env 敏感键（LLM_API_KEY/CHECKPOINT_PASSWORD/AGENT_REDIS_URL/OPENSANDBOX_API_KEY 等）自动路由进服务 Secret、不落 ConfigMap/env_json（sticky 三态 + 存量键写路径自愈迁移）；Deployment envFrom 两源（服务 Secret + 服务 CM）；平台默认配置（R3 最终形态）**仅作发布/编辑 env 时的表单默认填入**（`/settings` 维护 → `GET /platform-config/defaults` 预填），不经运行时注入、不影响任何已发布服务；`SANDBOX_ENABLED` 明确排除（保护 OAF 包 frontmatter 三层裁决）；agent-framework 零改动 |
| [db-migration-flyway-design.md](db-migration-flyway-design.md) | 已实施（2026-09-28，PR #58） | agent-framework 表结构/数据演进迁入 Flyway：V1 基线（e91d1f0 时点现网 schema，按 SHOW CREATE 固化排序规则漂移）+ V2..V5 历史变更收录 + V6 存量会话回填（自洽规范槽位 INSERT IGNORE，守卫 unknown/共享 gw-hash 桶）；baseline-on-migrate 兼容存量库、多副本历史表锁互斥、失败即启动失败；8 个 Store 构造器手工 DDL 全部移除；伴生修复 /threads slot 双形态匹配（JOIN 方向缺陷：A2A 会话不可见/updated_at 退化/删除留孤儿行）；含后续演进纪律（Expand-Contract、回填同版本、禁改已合并 V 文件） |

> 约定：新增归档时在本表补一行「文档 | 状态 | 说明」；被新设计取代的方案不删除、移入本目录并标注状态。
