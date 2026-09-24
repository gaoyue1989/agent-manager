# agent-framework 文档索引（docs/）

本目录 36 篇文档分四档维护。**凡与本索引同级的 `*-plan.md` / `*-design.md` 均为编制时点快照**，
结论是否仍然成立以其头部「现状核对」声明与本表状态列为准；系统当前状态以
[../AGENTS.md](../AGENTS.md)、[api.md](api.md)、[api-frontend-sse.md](api-frontend-sse.md) 为权威。

> 惯例（自 2026-09-07 起）：代码大改后，先给受影响的历史文档补写/更新头部「现状核对」，
> 再同步本索引的状态列。

## ① 现状权威文档（描述系统当前状态，随代码同步维护）

| 文档 | 说明 |
|------|------|
| [agent-framework-design.md](agent-framework-design.md) | v2.1 框架总体设计：架构、模块分层、类/表/端点清单（目录树为 v2.1 快照，最新以 ../AGENTS.md 为准） |
| [agent-framework-deploy.md](agent-framework-deploy.md) | 部署手册：前置条件、Docker、全量环境变量表（含 AGENT_REDIS_URL 必配项） |
| [agent-framework-test.md](agent-framework-test.md) | 测试手册：LLM 测试配置、用例清单、运行方式 |
| [api.md](api.md) | REST API 全量参考（无状态单次流架构） |
| [api-thread-spec.md](api-thread-spec.md) | 会话 API 对接规范（前端↔后端协议契约，E2E 断言权威） |
| [api-frontend-sse.md](api-frontend-sse.md) | 前端对接全量文档 v2.3.0：Durable SSE + HITL + 文件 + Skill 管理 + 事件词表（被多处源码 javadoc 引用） |
| [checkpoint-design.md](checkpoint-design.md) | Checkpoint 持久化设计：MysqlDistributedStore、agent_state/agent_fs 表结构（schema 权威） |
| [history-agentstate-design.md](history-agentstate-design.md) | History 权威化设计：agent_state 为消息级事实来源（已实施，被源码注释引用） |
| [tracing-design.md](tracing-design.md) | OTel 链路追踪设计（已实施，被 Makefile/Dockerfile 引用） |
| [offline-dev-image.md](offline-dev-image.md) | 离线开发镜像 java-dev 手册（被 Dockerfile.dev 引用） |
| [e2e-ci-plan.md](e2e-ci-plan.md) | GitHub Actions E2E 体系：已实施的 v3 录制回放架构 + 实施记录与框架缺陷清单 D1–D9 |
| [harness-config-analysis.md](harness-config-analysis.md) | Harness 配置化分析：三层配置盘点 + 环境变量绑定证据链（1.3 发现的 LLM 参数问题已修复） |
| [concurrency-benchmark-plan.md](concurrency-benchmark-plan.md) | 并发压测方案与执行结论（被 bench/ 脚本引用，不移动） |

## ② 已实施的设计记录（历史快照，方案已落地，保留作决策与机制依据）

| 文档 | 状态 |
|------|------|
| [stateless-single-stream-plan.md](stateless-single-stream-plan.md) | 无状态单次流改造（O1–O7），已实施定稿 |
| [durable-sse-plan.md](durable-sse-plan.md) | Durable SSE 第一阶段（单实例），被 multinode 方案接续 |
| [durable-sse-multinode-plan.md](durable-sse-multinode-plan.md) | 多副本事件总线正确性改造（F1–F8），已实施 |
| [durable-sse-multinode-impl-plan.md](durable-sse-multinode-impl-plan.md) | 上述设计的实施任务书（被 pom.xml 注释引用） |
| [hitl-permission-plan.md](hitl-permission-plan.md) | HITL 权限系统接入（confirm_context/turn_lease），已实施（被 8 处源码引用） |
| [mcp-apps-extension-plan.md](mcp-apps-extension-plan.md) | MCP Apps 扩展（ui:// 卡片 + 4.7 静默上下文），已实施 |
| [file-upload-download-plan.md](file-upload-download-plan.md) | 文件上传下载 local/S3 双后端，已落地 |
| [opensandbox-integration-plan.md](opensandbox-integration-plan.md) | OpenSandbox 沙箱集成（USER 级复用），已落地 |
| [oaf-improvement-plan.md](oaf-improvement-plan.md) | OAF 字段解析 + OAF→Workspace 转换，已落地（skills 复制方案后被动态加载取代） |
| [oaf-skills-dynamic-loading-plan.md](oaf-skills-dynamic-loading-plan.md) | skills 目录运行时动态加载（M1/M2 完成，M3 未开始；被 README/测试/E2E 脚本引用） |
| [mysql-session-persistence-plan.md](mysql-session-persistence-plan.md) | MySQL 会话持久化 v2.0→v2.1，已完成 |
| [mysql-filesystem-plan.md](mysql-filesystem-plan.md) | MySQL 文件系统（agent_fs），已完成 |
| [multi-tenancy-improvement-plan.md](multi-tenancy-improvement-plan.md) | 多租户隔离（IsolationScope.USER），已落地 |
| [tool-system-improvement-plan.md](tool-system-improvement-plan.md) | 工具体系改进（McpToolRegistrar 原生化），已改进 |
| [mcp-user-scoped-headers-plan.md](mcp-user-scoped-headers-plan.md) | MCP 多租户按用户调用（userHeaders 声明式配置 + `_meta` 双通道、middleware 单点注入、X-User-Token 延后），**已实施**（PR #9，2026-09-21；含 Q1–Q6 评审决议与 Q4 实施期结论：Channel 链路经 session_user 反查真实 userId） |
| [agentscope-features-enable-plan.md](agentscope-features-enable-plan.md) | AgentScope 2.0 五大 Harness 功能启用，已完成 |
| [debug-page-refactor-plan.md](debug-page-refactor-plan.md) | Debug 页拆分架构重构，已完成 |
| [event-system-upgrade-plan.md](event-system-upgrade-plan.md) | 事件体系升级（含逐条作废声明），历史快照 |
| [a2a-tasks-get-plan.md](a2a-tasks-get-plan.md) | A2A tasks/get + SDK 全量透传，已完成 |
| [sse-optimization-a1-a5-design.md](sse-optimization-a1-a5-design.md) | SSE 链路优化五项（A1 toSSE 收口 / A2 Tailer 空闲退避 / A3 emit 失败不广播 / A4 控制器桶清理 / A5 TurnFinalizer 抽取），已实施 |

## ③ 未实施提案（仅作参考，勿按已实现理解）

| 文档 | 说明 |
|------|------|
| [file-support-plan.md](file-support-plan.md) | 文档解析（POI/Tika/PDFBox）+ 图片多模态识别提案，未实施 |
| [tool-plugin-extension-plan.md](tool-plugin-extension-plan.md) | Java SPI + plugins/ 热插拔自定义工具提案，未实施 |
| [pvc-to-s3-migration-plan.md](pvc-to-s3-migration-plan.md) | PVC 使用盘点 + 双集群（共用 MySQL）下文件/OAF 包迁 S3 影响评估（2026-09-20 评估稿，**未实施**；含 §6.4b 沙箱模式影响：OpenSandbox 自身 HA，部署形态决定会话能否跨集群续） |

## ④ 已被取代（结论失效，仅供考古）

| 文档 | 说明 |
|------|------|
| [a2a-improvement-plan.md](a2a-improvement-plan.md) | 2026-08-06 早期 A2A 合规方案；「tasks/* 未实现」等断言已被 a2a-tasks-get-plan 的 SDK 全量透传方案取代 |
| [debug-page-agentscope-refactor-plan.md](debug-page-agentscope-refactor-plan.md) | Debug 页对齐官方前端计划；长连接 SSE 章节已被无状态单次流取代（头部已声明） |

## 关联文档

- 平台级历史设计归档：根目录 [../../docs/design/](../../docs/design/)（REDESIGN.md、HITL 验收等）
- E2E 用例矩阵：[../../e2e/AGENTS.md](../../e2e/AGENTS.md)；压测工具：[../bench/README.md](../bench/README.md)
