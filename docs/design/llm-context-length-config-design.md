# 模型上下文长度环境变量配置设计（LLM_CONTEXT_LENGTH）

> 状态：已实施 · 日期：2026-09-18
> 范围：仅新增「模型上下文长度」环境变量配置，其余（模型配置结构化、Secret 分流、管理 API 等）均不在本期。

## 1. 背景

模型上下文窗口长度（context window size，输入+输出 token 总预算）当前不可配置：`OpenAIChatModel.Builder.contextWindowSize(int)` 在 AgentScope 2.0.0 中存在（已核实 jar 方法签名），但 agent-framework 构建模型时从未调用（当时的 builder 链无此参数），运行时对模型窗口大小无感知。

目标：新增环境变量 `LLM_CONTEXT_LENGTH`，使上下文长度可通过 env 配置。

## 2. 配置项定义

| 项 | 值 |
|----|-----|
| 环境变量名 | `LLM_CONTEXT_LENGTH` |
| 语义 | 模型上下文窗口大小（tokens） |
| 类型 / 默认 | int，默认 `0` = 未配置（保持框架现状） |
| 建议范围 | [1024, 2000000]；≤0 视为未配置 |
| 示例 | `131072`（128K）、`262144`（256K）、`1048576`（1M） |

## 3. 改动清单（仅 agent-framework，3 处）

1. **application.yml** `agent.llm` 段（:41-52）追加：

```yaml
    context-length: ${LLM_CONTEXT_LENGTH:0}
```

2. **AgentManagerProperties.java** `LLMConfig` record（:81-95）追加字段：

```java
        @DefaultValue("0") int contextLength
```

3. 模型构建的 builder 链（现址 `ChatModelFactory.java:37-46`，实施时在 `AgentScopeConfig.java` 内，后由 model-params 改造抽出为共用工厂），按条件追加：

```java
            var modelBuilder = io.agentscope.extensions.model.openai.OpenAIChatModel.builder()
                .apiKey(llm.apiKey())
                .modelName(llm.modelId())
                .baseUrl(llm.baseUrl());
            if (llm.contextLength() > 0) {
                modelBuilder.contextWindowSize(llm.contextLength());
            }
```

行为：`LLM_CONTEXT_LENGTH > 0` 时传入框架；未配置或 ≤0 时不调用，行为与现状完全一致。非数字值由 Spring 绑定在启动期 fail-fast 报错。

## 4. 平台侧使用方式（零改动）

env 本就是自由 KV，无需改 backend/frontend：

- 发布向导第 3 步 env 表格手工加一行 `LLM_CONTEXT_LENGTH=131072`；或
- `PATCH /api/v1/services/:id/env`；或
- MCP `publish_service` / `update_service_env`（后者经 release-agent HITL 确认）。

变更后走既有 rollout restart 生效。

## 5. 效果边界（如实说明）

> **2026-10-01 复核修订**：下述「无消费方」结论是针对 **AgentScope 2.0.0** 的扫描结果。运行框架已升级到 **2.0.3**（`pom.xml` `agentscope.version`），该结论**已不成立**，见本节末「2.0.3 复核」。

已对 AgentScope 2.0.0 三个 jar（core / harness / extensions-model-openai）做字节码全量扫描：

- 配置后 `Model.getContextWindowSize()`（接口 default 方法，`ChatModelBase` 返回 builder 设置的字段）即返回配置值，成为运行时的窗口事实来源；
- 但 2.0.0 框架内部**暂无** `getContextWindowSize()` 的消费方（压缩当前仅按消息条数触发，`CompactionConfig.triggerTokens` 字段同样无消费方）。

即：本配置在 2.0.0 上的直接行为收益，以框架后续版本或运行时自定义 Hook 的消费为前提；当前价值是**打通配置入口 + 建立窗口事实来源**，为按窗口感知的策略（如 token 触发压缩）铺路。

### 5.1 2.0.3 复核：压缩已成为真实消费方

对运行版本（2.0.3）三个 jar 重新扫描，`getContextWindowSize()` 出现两个消费方，其中
`CompactionMiddleware` 落在每轮 reasoning 的热路径上：

- `agentscope-harness` `CompactionMiddleware.resolveEffectiveConfig()`：当 `triggerTokens == 0`
  且 `keepTokens == -1`（Builder 默认值）时进入「token 模式」，读取 `model.getContextWindowSize()`
  派生动态阈值——`trigger = contextWindow - reserved`（`reserved` 默认 20000；差值 ≤0 时退化为
  `max(1, contextWindow/2)`），窗口 ≤0 时回落 `FALLBACK_TRIGGER_TOKENS = 160000`；`keepTokens`
  同法按 `keepTokensRatio`（默认 0.25）夹在 `[keepTokensMin=2000, keepTokensMax=8000]` 内派生。
  该结果经 `ConversationCompactor.compactIfNeeded()` 用于实际压缩判定。
- `agentscope-core` `ReActAgent$2`：透传包装，无独立语义。

**对本项目的意义**：agent-framework 只设 `triggerMessages`/`keepMessages`
（`HarnessAgentFactory` 的 `CompactionConfig.builder()`，`AGENT_COMPACTION_TRIGGER_MESSAGES`
默认 30 / `AGENT_COMPACTION_KEEP_MESSAGES` 默认 10），**从不设** `triggerTokens`/`keepTokens`
——即默认的 token 模式条件恒成立，`resolveEffectiveConfig()` 每次都读窗口大小。

因此在 2.0.3 上 `LLM_CONTEXT_LENGTH` **已有直接行为收益**：未配置或 ≤0 时窗口报 0、走 160000
的固定兜底触发；配置后压缩触发点按 `配置值 - 20000` 自适应（`keepTokens` 亦随之变化）。
即原先「只打通入口」的判断已被框架升级兑现，token 触发压缩落地。

## 6. 测试与文档同步

### 6.1 测试

- 单测：`contextLength > 0` 断言 builder 参数传入；`= 0` 断言不调用；`mvn test` 回归（455 用例）。

### 6.2 文档同步清单（随实现一并提交）

已全仓排查引用 LLM env 清单的文档/模板，共 8 处：

**必须同步（运行时 env 的权威清单）**

| 文件 | 位置 | 改动 |
|------|------|------|
| agent-framework/AGENTS.md | 环境变量表（:233-239，LLM 行区域） | 加一行：`LLM_CONTEXT_LENGTH` / 默认 `0` / 选填 / 模型上下文窗口大小（tokens，≤0 视为未配置） |
| agent-framework/README.md | 环境变量表（:212-218） | 同上 |
| agent-framework/.env.example | :2-7 LLM 段 | 追加注释行 `# LLM_CONTEXT_LENGTH=131072`（可选变量，默认注释） |
| REDESIGN.md | §5.2 agent-framework 参考契约表（:361-362） | 可选行追加 `LLM_CONTEXT_LENGTH` |

**建议同步（描述部署/env 的细节文档）**

| 文件 | 位置 | 改动 |
|------|------|------|
| agent-framework/docs/agent-framework-deploy.md | env 表（:144-145 区域） | 加一行（含绑定关系：绑 `agent.llm.context-length`） |
| agent-framework/docs/harness-config-analysis.md | :26 LLM 行、:36-47 分析结论 | env 清单加 `LLM_CONTEXT_LENGTH`；该文档记录了"temperature/maxTokens 未传递给 builder"的分析——新字段是**传递**的，结论段应区分注明 |
| agent-framework/docs/agent-framework-design.md | env 表（:417-418 区域） | 加一行 |

**明确不改（判断依据）**

- agent-framework/docs/mysql-filesystem-plan.md:340-341、debug-page-refactor-plan.md:835 — 历史 plan 文档中的 yml/UI 快照，记录当时状态，不追溯修改
- 根 Makefile / .env.secrets.example — 本地开发敏感配置三件套，`LLM_CONTEXT_LENGTH` 非敏感可选变量，本地调试手动 export 即可
- release-agent/AGENTS.md:72、release-agent/skills/env-required/SKILL.md — 必填校验清单是「6 项必填」，`LLM_CONTEXT_LENGTH` 为可选增强，不进必填清单；平台侧本期零改动（§4）
- docs/deployment.md、docs/oaf-specification.md — 未引用 LLM env 清单 / 包规范与 env 无关，grep 无命中
- agent-framework/example/approval-forms/** — 示例应用文档，必填三要素即可运行
