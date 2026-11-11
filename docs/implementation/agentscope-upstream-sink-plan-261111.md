# agent-framework 能力下沉上游 — 执行计划

**日期**：2026-11-11　**状态**：待实施　**设计依据**：[../design/agentscope-upstream-sink-design-261111.md](../design/agentscope-upstream-sink-design-261111.md)（编号 U/G/B/T 沿用该文档）

> 完成定义（DoD，每阶段通用）：`mvn test` 全绿 + 受影响 e2e 套件全绿 + kind 部署冒烟通过；涉及删码的项必须有等价行为验收（e2e 断言不弱化）。

## P0 升级 2.0.3 → 2.0.4（本仓独立 PR，不依赖上游）

**范围**：pom `agentscope.version` 2.0.3→2.0.4 + breaking 适配 + 可删项验证删除。

| 步骤 | 内容 | 验收 |
|---|---|---|
| 0.1 | pom 升版本，全量编译修 breaking：`activeRc`/agent 级 `getRuntimeContext()` 移除、`RegisteredSkill`/`ToolkitAware` 删除、AGUI `ToolInjection`→`ToolRequestConfig`（本地 AGUI 面本就不用 starter 协调器，影响面在 codec 依赖）、`OpenAIMessage.cacheControl` 移除、AGUI reasoning 消息 ID 规则（`replyId-blockId`）、`RedisAgentStateStore.saveVersioned` 返回 `long` | `mvn test` 全绿；AGUI e2e（AG 组）过 |
| 0.2 | U2：删 `ChatModelFactory` HTTP/1.1 强制 | e2e-core 对话链路过（明文 http 端点回归） |
| 0.3 | U3：glm/deepseek 分支换 `GLMFormatter`/`DeepSeekFormatter`，保留 vllm/sglang | 模型方言单测换桩断言 |
| 0.4 | U1：删 `AskingContentBackfillStateStore` + 解除 e2e 已知缺陷① test.fixme | e2e HITL（H 组）恢复轮参数完整 |
| 0.5 | U4/U5：McpTool 双名注册 + propagateMeta 换用，简化裸名 hack 与 wrapper `_meta` 剥离 | e2e MCP + `#272` reqHeader 用例过 |
| 0.6 | U8：删 `SandboxAwareMysqlAgentStateStore`（先跑沙箱 slot 持久化 IT 实证 2.0.4 不拒 `/`；不过则保留并在上游开 issue 附实证） | X 组沙箱 e2e 过 |
| 0.7 | U9：投影新鲜度换官方 `applyWorkspaceProjectionIfChanged` 路径 | E-SKILL-4 用例（技能目录原位变化下轮生效） |

**瘦身量化**：删 ~400 行主代码 + 对应测试（Backfill 174 + HTTP workaround ~30 + 方言 ~60 + 裸名 hack/`_meta` 剥离简化 ~80 + SandboxAware 31）。
**回退**：单 PR，revert 即回 2.0.3。

## P1 上游 bugfix PR 六项（上游仓库推进，与 P0 并行）

B1 空 needle 死循环（附 CPU 100% 复现）/ B2 AgentStartEvent sessionId / B3 removeMcpClient 裸名残留 / B4 slotId·normalizeUser 公开化 / B5 `__fragment__` 事件契约 / B6 RemoteFilesystem 三缺陷。
每项：最小复现测试 + 修复；B4 若上游不愿公开则改为官方 slot 布局文档化，本地复刻注释锚定该文档。
**本仓动作**：上游合入 release 后随 P2 各换用 PR 顺带删本地补丁；不单独动本仓。

## P2 Tier1 下沉（逐项独立推进：上游 issue → 上游 PR → 合入 release → 本仓换用 PR）

顺序与理由：**T2 → T3 →（T1）→ T4 → T5**（T2/T3 依赖少且上游接口已备好；T1 需先对齐 SPI；T4 独立成模块；T5 最重）。

| 项 | 上游动作 | 本仓换用 PR 内容 | 换用后净瘦身（估） |
|---|---|---|---|
| T2 turn 互斥/停止 | `extensions-redis`+`extensions-mysql` 各一个 `SessionTurnGate` 实现 PR；interrupt 跨副本派发开设计 issue | TurnLeaseStore/Guard 改实现上游接口；StopStore/Watcher 的通用部分随迁，平台状态机保留 | ~900 行 |
| T3 MCP 用户态注入 | core per-call userHeaders 声明 PR（双通道 + fail-closed + denylist 可配） | wrapper/Customizer/UserHeaderRule 下沉；middleware 反查改取值函数注入 | ~420 行 |
| T1 durable 事件日志 | SPI issue 对齐（先核对 main 的 AgentSession 方向）→ `SessionEventLog` SPI + `extensions-redis` Streams 实现 PR | Store/RedisEventLog/Tailer 存储游标层换用；Bus 词表与终态探测保留 | ~1100 行 |
| T4 OpenSandbox 后端 | 新模块 `extensions-sandbox-opensandbox` PR（含 SandboxFileTransfer 直传与三实战补丁） | Client/OpenSandbox/spec 主体（3,018 行）下沉；K8s 名注入与 exec 翻译器（加版本探测后）保留 | ~2,200 行 |
| T5 沙箱池化 | harness 可选组件 PR（busy/hold 抽接口、SQL 方言抽象）；与上游对齐后再动工 | 三件套（1,764 行）换用，平台实现注入 busy/hold 接口 | ~1,000 行 |

**节奏约束**：本仓每项换用 PR 必须钉上游已发布版本（不依赖 SNAPSHOT）；上游未合入前本地代码不动。

## P3 Tier2 择机（G6~G9，各 100~400 行，随上游节奏单独立项）

MCP 看门狗/read_only/fail-soft、ModelResolver、vllm/sglang formatter、per-call ToolFilter、A2A 容错+MySqlTaskStore、Redis EventBus（上游 javadoc 明等此 PR，优先级最高）、X-Forwarded-Prefix、SkillZipSupport、GenAI 追踪中间件、FileStorage+S3、幂等 Job 状态机+HeaderTokenAuthFilter 模板。

## 瘦身汇总（对 45,674 行主代码，2026-11-11 master 实数）

| 阶段 | 净减行数（估） | 依赖 |
|---|---|---|
| P0 升级 2.0.4 | ~400（+等量测试） | 无，立得 |
| P2 Tier1 全部换用 | ~5,650（Tier1 五件全量 8,541，扣薄适配层保留） | 上游合入周期 |
| P3 Tier2 择机 | 数百~千级 | 上游节奏 |
| **合计** | **~6,000+（约 13%）** | 分阶段兑现 |

另有不直接减行数但消脆性的收益：P1 六项 bugfix 消「字节码核对」耦合锚点中至少 6 处；G10 装饰钩子 SPI 落地后 Archive 装饰器从「覆写全部 default 方法」脆弱手法改为官方扩展点。

## 里程碑判定

- **M1（P0 完成）**：升级收益兑现，e2e 九项必需检查全绿 → 本计划首个可验证瘦身点
- **M2（T2/T3 换用完成）**：上游协作模式跑通（issue→PR→release→换用）验证成立
- **M3（T1 换用完成）**：最大件落地，durable SSE 存储层由上游承载
- 任一上游 issue 被拒/长期无响应：该项转「本地维持 + 复核下一 release」，不阻塞其他项
