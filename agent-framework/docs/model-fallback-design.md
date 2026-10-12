# 模型备用切换设计（LLM fallback）

**版本:** v1
**日期:** 2026-09-28
**状态:** ✅ 已实施（2026-09-28）

相关：[session-model-switch-design.md](session-model-switch-design.md)（会话模型切换）、
[model-params-design.md](model-params-design.md)（采样参数方言）、
[AGENTS.md](../AGENTS.md)（环境变量表）。

---

## 0. 决策记录

| # | 决策项 | 结论 |
|---|--------|------|
| 1 | 备用模型来源 | **引用 `model_config` 托管模型**（`LLM_FALLBACK_MODEL_ID=<id>`）；不新建独立端点配置 |
| 2 | 触发条件 | **沿用 SDK 默认**（单次调用内部先按 429/5xx/超时/网络重试 3 次，耗尽后首次信号为 error 才切备用） |
| 3 | 覆盖范围 | **默认模型 + 会话自选模型**都要有备用（见 §3 为什么需要两处接线） |
| 4 | 实现形态 | 默认模型用 **SDK 原生 `.fallbackModel()`**；会话自选模型用 `SessionModelMiddleware` + `FallbackModelWrapper` 同语义补齐 |

## 1. 背景

- 现状（见上轮排查）：SDK 具备 `ModelConfig.fallbackModel` + `maxRetries` 能力，但本工程未接线；
  主模型重试耗尽即失败，无跨模型兜底。
- SDK 的 fallback 由 `ReActAgent.modelForCall()` 实现：它只包裹 agent 的 `this.model`（默认模型），
  用 `switchOnFirst` 在主模型**首个信号为 error** 时切换到备用模型（备用共享同一重试预算）。
- 本工程的**会话模型切换发生在 middleware**（`SessionModelMiddleware.onModelCall` 替换
  `ModelCallInput.model()`），会绕过 `modelForCall()` 的 fallback 包装——故需要两处接线。

## 2. 配置

```bash
LLM_FALLBACK_MODEL_ID=<model_config 托管模型 id>   # 空 = 不启用
```

- 绑定 `agent.llm.fallback-model-id`（`application.yml`）。
- `HarnessAgentFactory` 用 `@Value` 字段读取（字段注入而非构造参数：本类在单测中直接 `new`，
  避免改动所有构造调用点）。
- 构建期经 `ModelCatalog.resolve(id)` 解析：未配置 / 未找到 / 已禁用 → `null`，**仅告警不阻断启动**。

## 3. 机制

### 3.1 默认模型路径（SDK 原生）

`HarnessAgentFactory.build` 解析到备用模型后调用 `builder.fallbackModel(fallback)`；
SDK 在 `modelForCall()` 中包裹默认模型，首个信号失败切备用。

### 3.2 会话自选模型路径（`FallbackModelWrapper`）

`SessionModelMiddleware` 命中会话绑定模型时，用
`FallbackModelWrapper.wrap(target, fallbackModel)` 包裹后再进链：

```java
Model effective = FallbackModelWrapper.wrap(target, fallbackModel);
return next.apply(new ModelCallInput(input.messages(), input.tools(), input.options(), effective));
```

`FallbackModelWrapper` 复刻 SDK 的 `switchOnFirst` 语义：

```java
primary.stream(messages, tools, options)
    .switchOnFirst((signal, flux) -> signal.isOnError()
        ? fallback.stream(messages, tools, options) : flux);
```

元信息（`getModelName` / `supportsNativeStructuredOutput*` / `getContextWindowSize`）委托主模型，
保证下游 LLM 记录与 OTel span 口径不变。

## 4. 触发语义（重要）

- **重试**：`ExecutionConfig.MODEL_DEFAULTS` = 3 attempts，指数退避 2s 起、×2、上限 30s、jitter 0.5；
  过滤 429 / 5xx / 超时 / `IOException`（4xx 参数/鉴权错误不重试）。
- **切换**：SDK `switchOnFirst` 对**任何**首个信号错误都切换（含 400）——这是 SDK 原生语义，
  非仅"可重试错误"。若要收窄，需改为自定义 middleware 判定。
- **已产出增量后失败不切换**（`switchOnFirst` 只在首信号处理）：避免重复输出半截 token。
- 记忆 flush/整合、上下文压缩、会话标题**不经 onModelCall 链**（各自持有系统模型直调），
  故 fallback **不作用于**这些旁路调用。

## 5. 生效范围与限制

- **构建期解析一次**：`/models` 的 CRUD（改/删/禁该备用模型）需 `POST /admin/reload`
  才对备用生效（默认模型 reload 重建；会话 middleware 亦随之重建）。
- 备用模型实例由 `ModelCatalog` 缓存（TTL 30s），agent 持构建期引用。
- 备用模型与主模型相同（误配）无额外影响。
- 多副本：与现有模型切换一致，配置变更经 reload 各自生效。

## 6. 涉及文件

| 文件 | 改动 |
|------|------|
| `service/FallbackModelWrapper.java` | 新增：`switchOnFirst` 备用切换装饰器 + `wrap()` 空备用退化 |
| `service/SessionModelMiddleware.java` | 新增 3 参构造；会话模型包 `FallbackModelWrapper`（2 参构造保留兼容） |
| `service/HarnessAgentFactory.java` | `@Value` 注入 + `resolveFallbackModel()` + `builder.fallbackModel()` + 传给 middleware |
| `resources/application.yml` / `.env.example` | `LLM_FALLBACK_MODEL_ID` |
| `controller/DebugApiController.java` | `/debug/config/env` 的 `llm.fallback_model_id` |
| `static/debug/modules/config.js` | 展示 `LLM_FALLBACK_MODEL_ID`（空显示「未启用」） |
| `static/debug/modules/models.js` | 命中备用模型的行标 `fallback` 徽标 + 未命中提示 |
| `docs/*` | 本文 + AGENTS.md + api.md + agent-framework-deploy.md |

## 7. 测试

- `FallbackModelWrapperTest`（5 例）：首信号失败切备用 / 正常不切 / 已产出后失败不切且错误传播 /
  元信息委托主模型 / 空备用退化直返。
- `SessionModelMiddlewareTest`（+1 例）：配置备用时会话模型被包裹并在主模型失败时切备用；
  原有用例经 2 参构造保持 `assertSame` 不变。
- 全量：`mvn -o test` 1098 用例，0 失败，4 跳过。

## 8. 未做 / 后续可选

- 触发条件收窄（只对可重试错误切备用）：需自定义 middleware 判定，非 SDK 原生。
- 备用链（多级 fallback）、按会话指定备用：均需扩展配置与中间件（方案2 形态）。
- `/models` 管理页直接编辑 `fallback_model_id`（当前仅 env + 只读展示）。
