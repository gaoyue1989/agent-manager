# 模型备用切换设计（LLM fallback）

**版本:** v2
**日期:** 2026-10-22
**状态:** ✅ v1 已实施（2026-09-28）；v2「备用模型可配置」已实施（2026-10-22）

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
| 4 | 实现形态 | v1：默认模型用 SDK 原生 `.fallbackModel()`；会话自选模型用 `SessionModelMiddleware` + `FallbackModelWrapper`。**v2 起统一收到中间件**（见 §9） |
| 5 | 运行期配置（v2） | `model_config.is_fallback`（V12）为**首选来源**；未配置时回退 `LLM_FALLBACK_MODEL_ID`；`PUT /models/{id}/fallback` 设置、id=`system` 清除 DB 覆盖。备用只允许托管模型 |
| 6 | 生效时机（v2） | 每次模型调用实时解析（DB > env），**无需 `/admin/reload`**；多副本设置后下一次调用即生效 |

## 1. 背景

- 现状（见上轮排查）：SDK 具备 `ModelConfig.fallbackModel` + `maxRetries` 能力，但本工程未接线；
  主模型重试耗尽即失败，无跨模型兜底。
- SDK 的 fallback 由 `ReActAgent.modelForCall()` 实现：它只包裹 agent 的 `this.model`（默认模型），
  用 `switchOnFirst` 在主模型**首个信号为 error** 时切换到备用模型（备用共享同一重试预算）。
- 本工程的**会话模型切换发生在 middleware**（`SessionModelMiddleware.onModelCall` 替换
  `ModelCallInput.model()`），会绕过 `modelForCall()` 的 fallback 包装——故需要两处接线。

## 2. 配置（v2：DB 优先，env 兜底）

| 来源 | 写法 | 优先级 |
|------|------|--------|
| DB（debug 页 Models / REST） | `PUT /models/{id}/fallback` → `model_config.is_fallback=1` | 高 |
| 部署 env | `LLM_FALLBACK_MODEL_ID=<model_config 托管模型 id>`（空 = 不启用） | 低 |
| 未配置 | `fallback_source=none` | — |

- `LLM_FALLBACK_MODEL_ID` 绑定 `agent.llm.fallback-model-id`（`application.yml`）。
- `PUT /models/system/fallback` 清除 DB 覆盖；若 env 有值会重新生效（UI 会显示来源 `env`）。
- DB/env 指向的模型不存在或已禁用 → 自动跳过/告警，不阻断对话。
- 备用只允许**启用的托管模型**，不支持把系统模型设为备用。

## 3. 机制（v2：中间件单一入口）

`SessionModelMiddleware.onModelCall` 每次调用统一解析：

1. 主模型：会话绑定 → 全局默认对话模型 → 系统模型；
2. 备用模型：`ModelCatalog.resolveFallback()`（DB > env）；
3. 有备用时用 `FallbackModelWrapper.wrap(primary, fallback)` 包裹后替换 `ModelCallInput.model()`；
   无会话绑定/默认/备用时直接 `next.apply(input)`（零改动直通）。

```java
var target = resolveSessionModel(ctx);          // null = 系统模型
var fallback = modelCatalog.resolveFallback();  // DB > env
Model effective = FallbackModelWrapper.wrap(target != null ? target : input.model(),
                                            fallback.orElse(null));
return next.apply(new ModelCallInput(input.messages(), input.tools(), input.options(), effective));
```

- **agent 构建期不再调用 `builder.fallbackModel(...)`**（v1 的默认模型 SDK 原生路径已移除），
  避免“SDK 已包一层 + 中间件再包一层”的双重包裹；env 备用也由中间件动态解析。
- `FallbackModelWrapper` 复刻 SDK 的 `switchOnFirst` 语义：

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

## 5. 生效范围与限制（v2）

- **运行期实时解析**：设置/清除备用后下一次模型调用即生效，无需 reload；多副本实时读库。
- 模型实例由 `ModelCatalog` 缓存（TTL 30s，仅实例构建；选择关系不缓存）。
- 禁用/删除备用模型：`PATCH enabled=false` 同语句清除 `is_fallback`；删除行后 DB 覆盖消失，
  自动回退 env/不启用。
- 备用模型与主模型相同（误配）无额外影响。
- **清除 DB 覆盖 ≠ 彻底关闭**：若部署仍配置 `LLM_FALLBACK_MODEL_ID`，清除后会回落 env；
  要完全关闭需在部署 env 中移除该键。
- 不支持系统模型作为备用（方案 A 取舍；需要时见 session-model-switch-design 的扩展方向）。

## 6. 涉及文件

| 文件 | 改动 |
|------|------|
| `service/FallbackModelWrapper.java` | `switchOnFirst` 备用切换装饰器 + `wrap()` 空备用退化 |
| `service/SessionModelMiddleware.java` | 主模型/备用模型统一动态解析与包裹（移除 3 参构造） |
| `service/HarnessAgentFactory.java` | 移除构建期 fallback 解析与 `builder.fallbackModel()`（避免双重包裹） |
| `service/ModelCatalog.java` | `fallbackSelection()/resolveFallback()`（DB > env）+ options 标记 |
| `service/ModelConfigStore.java` | `findFallbackId/setFallback/clearFallback` + 禁用清标记 |
| `db/migration/V12__model_config_fallback.sql` | `is_fallback` 列 + 索引 |
| `controller/ModelController.java` | `PUT /models/{id}/fallback`；`GET /models` 增 `fallback_model/fallback_source` |
| `resources/application.yml` / `.env.example` | `LLM_FALLBACK_MODEL_ID` 语义更新（部署级兜底） |
| `controller/DebugApiController.java` | `/debug/config/env` 的 `llm.fallback_model_id`（保留） |
| `static/debug/js/api.js` / `modules/models.js` | `Set Fallback` / `Clear` + 来源提示 |
| `docs/*` | 本文 + AGENTS.md + api.md |

## 7. 测试

- `FallbackModelWrapperTest`（5 例）：首信号失败切备用 / 正常不切 / 已产出后失败不切且错误传播 /
  元信息委托主模型 / 空备用退化直返。
- `SessionModelMiddlewareTest`：会话模型/系统模型分别被动态备用包裹并在主模型失败时切备用；
  无备用直通不变。
- `ModelCatalogTest`：DB > env 优先级、DB 失效回落 env、env 模型失效跳过、options 标记。
- `ModelControllerTest`：set/clear fallback 成功、禁用 400、未知 404、来源回显。
- 全量：`mvn test` 0 失败（2026-10-22 实跑见 CI）。

## 8. 未做 / 后续可选

- 触发条件收窄（只对可重试错误切备用）：需自定义 middleware 判定，非 SDK 原生。
- 备用链（多级 fallback）、按会话指定备用：均需扩展配置与中间件（方案2 形态）。
- 系统模型作为备用（需把 `is_fallback` 迁移为单行 `model_routing` 表）。

## 9. v2 实施记录（2026-10-22，备用模型可配置）

- **存储**：V12 给 `model_config` 加 `is_fallback`；单语句原子维护唯一备用行；禁用同语句清标记。
- **解析**：`ModelCatalog.fallbackSelection()` 实时读库，DB 优先、env 兜底；`fallback_source`
  取 `db`/`env`/`none` 供 UI/API 展示。
- **运行期**：移除 `HarnessAgentFactory` 的构建期解析与 `.fallbackModel()`，统一由
  `SessionModelMiddleware` 在每次 `onModelCall` 动态包裹，设置即生效、无需 reload。
- **接口/UI**：`PUT /models/{id}/fallback`（id=`system` 清除 DB 覆盖）；debug Models 列表
  `Set Fallback` / `Clear` + `fallback` 徽章 + 来源提示。
