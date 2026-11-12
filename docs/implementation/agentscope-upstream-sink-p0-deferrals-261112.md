# agentscope-204 P0 执行偏差记录（U1 保留 / U5 部分换用）

**日期**：2026-11-12　**状态**：评审修复轮补记　**关联**：[docs/design/agentscope-upstream-sink-design-261111.md](../design/agentscope-upstream-sink-design-261111.md)（U1/U5 行，分支检出）、commits 9ffcbeb..5212c5d（P0 步骤 0.1~0.7）

本文档是 P0 两项计划偏差在仓内的唯一持久化记录；0.4/0.5 步骤返回中另有同等记载。

## U1（步骤 0.4）：AskingContentBackfillStateStore 保留，删码顺延 2.1.0

**决定**：不删（174 行回填保留），e2e 已知缺陷①相关 test.fixme 不解除。

**依据**（三点均可复核）：

1. 「升级即得」前提不成立——input 合并 / content 兜底机制在 2.0.3 已存在：agentscope-core 2.0.3 与 2.0.4 jar 的 `ToolCallsAccumulator.class` md5 一致（`5d2688d9ac95162bc2d7347b0520c9c0`，实测）；ToolExecutor 2.0.3→2.0.4 仅动 RuntimeContext/ToolRequestConfig 装配，mergedInput 与校验路径未动。
2. 回填防御的缺陷在 v2.0.4 未修：无流式分片时 content 兜底 `"{}"` 掩蔽完整 parsed input → 必填参数校验拒绝；修复为上游 PR **#3886**（2026-11-11 合入 2.1.0-SNAPSHOT，兜底 content 改为序列化 input，语义等同本地回填），v2.0.4（2026-10-08 发布）不含。
3. 判定门不可得：缺陷是否触发取决于模型面分片形态（单 chunk 仅 input 无流式分片是常见形态），现有 H 组 e2e 不覆盖该形态——绿不能证明删除安全。

**删码前置条件**（2.1.0 升级 PR 顺带执行，#3886 在 release 内后）：删 `sandbox/opensandbox/AskingContentBackfillStateStore.java` 及其测试、AgentScopeConfig 装饰链③装配与链注释、SessionMessageArchiveStateStore javadoc 的装配顺序引用；并评估读侧两处同款兜底（`AgentStateReader`、`ConfirmContextStore`）。

**上游 issue 材料**：上述 1/2 两点即 issue 正文（2.0.4 仍受 content 掩蔽影响，#3886 后无需 workaround）；issue 随 P1 上游沟通项提交，本仓内先以此记录留证。

**test.fixme 现状**：全仓 5 处（F5 present_file / U8 刷新确认卡重建 / U11 文件交付 / X3 沙箱文件写读 + D8 注释），无一处与「HITL 恢复参数丢失」对应；U1 行引用的「已知缺陷①」编号清单在设计与 e2e 文档中均不存在，解除对象不可定。

## U5（步骤 0.5）：propagateMeta「换用」实为开关面就绪，按键剥离保留本地

**决定**：`UserScopedMcpClientWrapper.stripForwardKeys`（出站 `reqHeader:*` 选择性剥离）原样保留；上游 propagateMeta 不换用。

**依据**：上游开关为 server/tool 粒度全有全无（`McpClientManager` 8 参 registerMcpClient + `McpTool.callAsync` 的 AND 语义），表达不了「userId 等继续走 `_meta` 协议通道、仅 `reqHeader:*` 剥离」的按键选择性语义（测试契约 UserScopedMcpClientWrapperTest）；以 `propagateMeta(false)` 替代会把 userId 一并抹掉，违反 P0 非目标「不改变业务面行为」。换用落点 = P2/T3（core per-call userHeaders 声明 PR，随 wrapper/Customizer/UserHeaderRule 一起下沉）。

**上游 issue 材料**：请求 per-key 粒度 propagate 增强（per-meta-key 开关或 denylist），本地 `stripForwardKeys` 可作参考实现；随 P1 沟通项提交。

**commit 措辞修正**：2f8ce55 标题「propagateMeta 换用」不准确——实际交付为「McpTool 双名构造器换用（完整）+ propagateMeta 开关面就绪（Toolkit.ToolRegistration 已暴露 propagateMeta(boolean)/propagateMeta(String,boolean)，javap 实证）+ 按键剥离保留」。以本文档为准，不改写既有 commit。

## 验收注记

- U1：保留即无删除发生，无需删除后回归；0.1 的 pom 升级已通过全量 breaking 适配与单测。
- U5：行为零变化；e2e core 组 100 passed（含 #272 reqHeader 透传用例 S5/S5a/S5b/S5c）覆盖剥离契约。
- P0 六个已执行步骤（0.1/0.2/0.3/0.5/0.6/0.7）的 e2e 裁决均为本地实跑：core 组 100 passed（0.5/0.7 后）、sandbox 组 24 passed（0.6/0.7 后）。
