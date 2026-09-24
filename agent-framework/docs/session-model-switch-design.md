# 会话模型切换设计（模型配置保存 + 会话级切换 + 系统模型）

**版本:** v2（决策定稿）
**日期:** 2026-09-24
**状态:** ✅ 已实施（2026-09-24，同一 PR；实施记录与两处偏差见文末「§13 实施记录」）

---

## 0. 决策记录

| # | 决策项 | 结论 |
|---|--------|------|
| 1 | API key 存储 | **明文列**（内网 MySQL，最简）；接口返回仍做掩码展示 |
| 2 | 会话标题生成 | **纳入本次实现**（系统模型生成，写 `session_user.remark`） |
| 3 | 模型管理 UI | **本次一起做，放 debug 页面**（`static/debug/` 新增"模型管理"模块） |
| 4 | 删除被引用模型 | **允许删除**；引用会话回落默认模型，后续新请求可随时切换到新模型 |

## 1. 调研结论（背景）

- **agentscope-java 2.0.3 无现成"运行时/每会话切换模型"方案**（模型在 `HarnessAgent.builder().model(...)` 构建期固定）。可用官方切口：`MiddlewareBase.onModelCall` 可拿 `ModelCallInput(..., model)` 并整体替换后传 next（官方文档明确的 model override 用法）；`MemoryConfig.model`/`CompactionConfig.model` 可独立指定模型；`modelResolver` 可按名解析多模型。
- **deer-flow 参考**：模型列表（YAML + 托管模型 CRUD + 测试连接）、前端 model picker、**thread 级模型覆盖持久化**（"Thread-specific model overrides"）。
- **关键隔离事实**（实测，`TracingModelWrapper` 注释）：记忆 flush/整合与压缩的 LLM 调用**不经 onModelCall 链**（各自持有 Model 实例直调 `model.stream()`）——"切换只影响对话、系统模型管标题/记忆压缩"**天然成立，无需防护代码**。

## 2. 模型三层结构

| 层 | 来源 | 用途 | 变更方式 |
|----|------|------|----------|
| **系统模型** | `LLM_*` 环境变量（**保留现状**） | 会话标题生成、记忆 flush/整合、上下文压缩 | env，重启生效 |
| **默认对话模型** | 同系统模型 | 未显式选模型的会话 | 同上 |
| **托管模型（可切换）** | `model_config` 表（新增） | 会话对话，按会话选择 | REST CRUD，热生效 |

## 3. 模型配置保存

### 3.1 表结构 `model_config`（与 checkpoint 同库，纯新增表）

```sql
CREATE TABLE IF NOT EXISTS model_config (
  id              VARCHAR(64)   NOT NULL PRIMARY KEY,       -- ULID；会话按 id 引用，改名不断链
  name            VARCHAR(128)  NOT NULL,                   -- 展示名，唯一
  provider        VARCHAR(32)   NOT NULL DEFAULT 'openai',  -- 当前仅 openai 兼容端点
  model_id        VARCHAR(128)  NOT NULL,                   -- 发给推理端点的模型名
  base_url        VARCHAR(512)  NOT NULL,
  api_key         VARCHAR(512)  DEFAULT NULL,               -- 明文（内网库，决策#1）；NULL=回落系统 LLM_API_KEY
  temperature     DOUBLE        NOT NULL DEFAULT 0.3,
  max_tokens      INT           NOT NULL DEFAULT 16384,
  timeout_seconds INT           NOT NULL DEFAULT 120,
  enable_thinking TINYINT(1)    NOT NULL DEFAULT 0,
  context_length  INT           NOT NULL DEFAULT 0,
  enabled         TINYINT(1)    NOT NULL DEFAULT 1,
  created_at      DATETIME(3)   NOT NULL,
  updated_at      DATETIME(3)   NOT NULL,
  UNIQUE KEY uk_model_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

要点：
- **明文 key**（决策#1）：不引入加密层；但**读接口一律返回掩码** `api_key_masked: "sk-***a1b2"`（对齐现有安全约束"敏感信息用占位符"），key 明文只出现在写入请求体与 DB。`api_key` 留空 = 复用系统 `LLM_API_KEY`（同端点多模型常见场景）。
- **热生效（多副本安全）**：`ModelCatalog` 按 id 惰性构建 `Model` 实例 + **30s TTL 缓存**；本副本 CRUD 后立即失效，其余副本 ≤30s 收敛。实例构建复用从 `AgentScopeConfig.buildChatModel` 抽出的 `ChatModelFactory`（同一套 enable_thinking / contextLength / HTTP 超时口径）。
- **删除语义（决策#4）**：`DELETE /models/{id}` 直接删行；`session_user.model` 仍引用它的会话**回落默认模型**（路由查不到→默认，warn 一次），用户下一条消息带新 `model` 或 `PATCH` 即可切换到任何现存模型。
- 会话模型映射存 `session_user` 新增列：`model VARCHAR(128) DEFAULT ''`（沿用 `remark` 列的 DDL 演进先例，启动时幂等 ALTER）。

## 4. 接口设计

> 遵循现有 API 风格（逐条对照见 §6）：**请求体 camelCase**（record 组件直出）｜**响应体 snake_case**｜**错误 = HTTP 状态码 + `{"error": "<snake_case 机器码>", "message": "<人读文案>"}`**（ConfirmController 口径）｜**SSE 词表零变更**。

### 4.1 模型配置管理接口（全部新增，无存量冲突）

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/models` | 可选模型列表（前端 picker + debug 管理页共用数据源） |
| `GET` | `/models/{id}` | 详情（key 掩码） |
| `POST` | `/models` | 新增托管模型 |
| `PATCH` | `/models/{id}` | 局部更新（字段缺失=不变；`apiKey=""`=清除回落系统 key；改 `id` 不允许） |
| `DELETE` | `/models/{id}` | 删除（系统模型 → 400；引用会话自动回落默认） |
| `POST` | `/models/{id}/test` | 测试连接：1 次最小 completion（max_tokens≈16），返回 `{ok, latency_ms, error}` |

**`GET /models` 响应**（snake_case）：

```json
{
  "default_model": "system",
  "models": [
    { "id": "system",  "name": "qwen3-32b（系统）", "provider": "openai", "model_id": "qwen3-32b",
      "is_default": true,  "source": "system",  "enabled": true },
    { "id": "01J8XK...", "name": "DeepSeek-V3",   "provider": "openai", "model_id": "deepseek-v3",
      "is_default": false, "source": "managed", "enabled": true }
  ]
}
```

**`POST /models` 请求体**（camelCase；`PATCH` 同构）：

```json
{
  "name": "DeepSeek-V3",
  "provider": "openai",
  "modelId": "deepseek-v3",
  "baseUrl": "https://api.example.com/v1",
  "apiKey": "<占位符>",
  "temperature": 0.3,
  "maxTokens": 16384,
  "timeoutSeconds": 120,
  "enableThinking": false,
  "contextLength": 0,
  "enabled": true
}
```

**错误词表**（`error` 机器码 + `message`，对齐 ConfirmController）：

| 状态 | error | 触发 |
|------|-------|------|
| 400 | `duplicate_name` | name 唯一冲突 |
| 400 | `invalid_config` | modelId/baseUrl 缺失等校验失败 |
| 404 | `model_not_found` | id 不存在 |
| 400 | `system_model_readonly` | PATCH/DELETE 打到系统模型 |
| 502 | `model_test_failed` | /test 上游连不通（message 附上游错误摘要） |

### 4.2 会话模型切换接口（存量接口增量扩展）

| 接口 | 变更 | 语义 |
|------|------|------|
| `POST /threads/chat` | body **新增可选** `model` | 会话开始/会话中切换主入口：校验 → 写 `session_user.model` → **本 turn 生效**（写入先于 turn 启动） |
| `PATCH /threads/{sessionId}` | body **新增可选** `model` | 免发消息切换；持久化后**下一次模型调用生效**（含进行中 turn 的后续 ReAct 轮） |
| `GET /threads` / `GET /threads/{sessionId}` | 响应**新增** `model` | 回显会话当前模型（`""`=默认） |

`model` 取值语义（chat 与 PATCH 一致）：

| 取值 | 行为 |
|------|------|
| 缺省 / `null` | 不改变会话模型 |
| `""` 或 `"system"` | 清除覆盖 → 回默认（系统）模型 |
| 托管模型 id | 校验（存在且 enabled）→ 设为该会话模型 |
| 未知 id / 已禁用 | `400 unknown_model` / `400 model_disabled` |

典型时序（deer-flow 同款体验）：

```
① 会话开始切换：POST /threads/chat {message, sessionId: "webui-x", model: "01J8XK..."}
   → 校验 → session_user.model=01J8XK → 本 turn 全部 LLM 调用走 DeepSeek-V3
② 会话中切换：下一条消息带 model: "system"（或 PATCH）→ 后续调用回系统模型
③ 会话模型被删：路由查不到 → 回落默认模型（warn），再发消息可切新模型
```

## 5. 对现有接口的破坏性影响分析 ★

**结论：无破坏性变更，全部为"可选请求字段 / 新增响应字段 / 新增端点 / 新增列"。**

### 5.1 HTTP 契约逐端点

| 端点 | 变更 | 影响判定 |
|------|------|----------|
| `POST /threads/chat` | body 加**可选** `model` | ✅ 无破坏。旧客户端不传 → 行为与现在完全一致（默认模型）。传未知 id 只影响新客户端自身（返回 error 帧） |
| `PATCH /threads/{sessionId}` | body 加可选 `model`；响应加 `model` 键 | ✅ 无破坏。`title` 行为不变；响应是 JSON object **增键**，旧消费者按需取键不受影响 |
| `GET /threads`、`GET /threads/{sessionId}` | 响应加 `model` 键 | ✅ 无破坏（同上，增键） |
| 其余 `/threads/*`（history/status/confirm/llm-calls/delete） | **零变更** | ✅ |
| `/files/*`、`/skills/*`、`/mcp/*`、A2A `POST /` | **零变更** | ✅ |
| `GET /models*`（6 个） | 纯新增路径 | ✅ 不与任何存量路由冲突（现无 `/models` 前缀） |
| SSE 事件词表 | **零变更**（不新增事件类型，模型切换不上事件流） | ✅ 消费方解析器不用动 |

### 5.2 存储 schema

| 对象 | 变更 | 影响判定 |
|------|------|----------|
| `model_config` | 新表 | ✅ 纯新增 |
| `session_user` | 加列 `model VARCHAR(128) DEFAULT ''` | ✅ ADD COLUMN + 默认值，存量行自动 `''`（=默认模型）；滚动升级期间新旧代码共存安全（旧 SQL 显式列名不受影响） |
| `agent_state` / `agent_fs` / Redis 事件流 | 零变更 | ✅ checkpoint 格式不变，历史会话可继续对话 |

### 5.3 Java 内部（非 HTTP 契约，仅仓内影响）

- `ChatRequest` / `PatchRequest` record 增 `model` 组件 → **编译期**影响仓内测试构造点（约 15 处），随 PR 一并修复；对 HTTP/JSON 调用方无感（Jackson 按名绑定，缺字段=null/默认值）。`LLMConfig` 无变更（未引入 env 备选模型列表，见 §13）。

### 5.4 可感知的行为变化（非破坏，需知情）

| 变化 | 说明 | 降级 |
|------|------|------|
| 新会话首条消息后多一次 LLM 调用 | 标题生成（系统模型、≤20 字输出、异步 fire-and-forget） | 失败仅 warn，标题留空，不影响对话 |
| 删除模型后会话行为 | 引用会话**静默回落默认模型**（warn 一次），上下文/历史不受影响 | 决策#4 的既定语义；`GET /threads` 的 `model` 仍显示旧 id 供排查，可 PATCH 覆盖 |
| `/models/{id}/test` | 真实发起一次 LLM 调用（小 max_tokens） | 仅 debug 页手动触发 |
| 多副本模型配置收敛 | CRUD 后其他副本 ≤30s（TTL）生效；**会话模型映射实时读库无延迟** | TTL 可调；对配置管理场景足够 |
| `/metadata` 的 `model` 字段 | **不动**（那是 OAF frontmatter 声明的展示字段，与会话模型无关） | 文档注明两者语义不同，避免混淆 |

### 5.5 新旧版本混跑矩阵

| 前端 × 后端 | 表现 |
|-------------|------|
| 旧前端 × 新后端 | ✅ 完全正常（不传 model、不解析新增键） |
| 新前端 × 旧后端 | ✅ 优雅降级：`GET /models` 404 → picker 隐藏，仅默认模型 |
| A2A/MCP 调用方 | ✅ 零感知（A2A 链路走默认模型；如需 A2A 切模型可后续经 `message.metadata.model` 扩展，本次不做） |

## 6. API 风格一致性对照 ★

现有代码的既定风格（取自 `ChatRequest`/`ThreadController`/`FileController`/`ConfirmController`/`api-thread-spec.md`）：

| 约定 | 现状证据 | 本设计遵循 |
|------|----------|------------|
| 请求体字段 **camelCase**（record 组件直出，无 @JsonProperty） | `ChatRequest{userId, sessionId, fileIds}` | `POST/PATCH /models` 用 `modelId/baseUrl/apiKey/maxTokens/timeoutSeconds/enableThinking/contextLength`；`model` 单词两边同形 |
| 响应体字段 **snake_case** | `session_id/thread_id/file_id/download_url/updated_at` | `model_id/base_url/api_key_masked/max_tokens/timeout_seconds/enable_thinking/context_length/is_default/created_at` |
| 错误体 = 状态码 + `{"error": "<snake_case 码>", "message": "<人读>"}` | `ConfirmController`：`{"error":"turn_in_progress","message":"..."}` | `duplicate_name / invalid_config / model_not_found / system_model_readonly / model_test_failed / unknown_model / model_disabled` |
| 资源路径 REST 风格、复数名词 | `/threads`、`/files`、`/skills` | `/models`、`/models/{id}`、`/models/{id}/test`（动作型子资源，同 `/confirm-stream` 先例） |
| 幂等 DDL 演进先例 | `ThreadController.ensureRemarkColumn` | `SessionUserStore.ensureColumn`（model 列同款） |
| SSE 错误帧 `{"type":"error","error":"..."}` | api-thread-spec | chat 带非法 model 时同款 error 帧（`"unknown_model: xxx"`），不引入新帧型 |

## 7. 运行时路由（SessionModelMiddleware）

```
每次 LLM 调用 ──► onModelCall（注册为最外层中间件）
                   ├─ 会话 key 两级回退：ctx.getSessionId() → ctx.getUserId()
                   │   （Channel 链路前端 sessionId 实际在 userId=peer，McpUserContextMiddleware 同款先例）
                   ├─ 查 session_user.model（PK 实时查，无缓存 → 多副本强一致）
                   └─ ModelCatalog.resolve(id) ──► 替换 ModelCallInput.model()
                       未设置/未知/已禁用 → 沿用默认模型（warn 一次/id）
```

- **最外层注册**：下游 `LlmLoggingMiddleware`、OTel span 看到的都是生效模型（调试口径正确）。
- **切换生效点**：`chat.model` 落库先于 turn 启动 → 本 turn 即生效；`PATCH` → 下一次模型调用生效。
- **标题/记忆/压缩不受影响**：直调 `model.stream()` 不经本链（§1）。

## 8. 系统模型用途：会话标题 + 记忆压缩

- **记忆压缩**：`CompactionConfig.model` / `MemoryConfig.model` 维持 env 模型现状（注释上标注"系统模型"），零行为变化。
- **会话标题生成（决策#2，纳入）**：`SessionTitleService`
  - 触发：`POST /threads/chat` 新会话且首条用户消息非空（A2A 会话本次不触发，前端侧栏本就过滤非 webui 会话）；
  - 调用：系统模型单次 `stream`（prompt 限首条消息 500 字，输出 ≤20 字标题，maxTokens 小值，60s 超时）；
  - 写入：`session_user.remark`（**已有标题不覆盖**——手动重命名优先）；单线程守护 executor，fail-soft；
  - 展示：前端侧栏 `title || sessionId`（决策#3 配套）。

## 9. 前端

**A. 对话页 picker（`frontend/src/app/assistant/page.tsx`，deer-flow 风格）**
- 输入卡左下角 `<select>`：数据源 `GET /models`（404 → 隐藏，降级）；
- 选择后随下一条消息的 `model` 发送（=会话级覆盖）；切换历史会话按 `GET /threads` 的 `model` 回显；
- 侧栏渲染标题（决策#2 配套）。

**B. debug 页模型管理（决策#3，`src/main/resources/static/debug/`）**
- 现状：模块化原生 JS（`js/{state,api,router,app,utils}.js` + 侧栏路由）；新增 `js/models.js` 管理模块 + 侧栏入口"模型管理"；
- 功能：模型列表（系统只读 + 托管）、新增/编辑表单（apiKey 密码框 + 掩码回显 + 留空=不修改）、启用/禁用、删除（确认提示"引用会话将回落默认模型"）、**测试连接**按钮（展示 ok/latency/error）；
- 全部走 §4.1 REST，与 debug 页现有 `/debug/config/env` 等只读诊断区并列。

## 10. 测试计划

| 层 | 用例 |
|----|------|
| `ModelConfigStore` | CRUD 往返、name 唯一冲突、key 留空/清除回落、enabled 过滤 |
| `ModelCatalog` | system+managed 合并、TTL 失效/热更、删除后 resolve 空、实例构建参数 |
| `SessionModelMiddleware` | 会话命中换 model、两级 key 回退、未知/删除回落默认、默认透传、禁用拦截 |
| `SessionTitleService` | 生成清洗截断、已有标题不覆盖、LLM 异常 fail-soft |
| 控制器契约 | `ChatRequest.model` 校验/落库、PATCH model 语义（含 "" 清除）、GET 响应含 `model`、`/models` CRUD 错误词表、/test 成败 |
| 回归 | 现有 `ChatStreamControllerTest`/`ThreadControllerTest` 全绿（构造点同步）；前端 lint + build |

## 11. 改动清单

| 文件 | 变更 |
|------|------|
| `service/ModelConfigStore.java` | 🆕 表 DDL + CRUD（明文 key） |
| `config/ChatModelFactory.java` | 🆕 从 `AgentScopeConfig.buildChatModel` 抽出共用构建 |
| `service/ModelCatalog.java` | 🆕 system+managed 目录、实例缓存、热失效 |
| `service/SessionModelMiddleware.java` | 🆕 onModelCall 会话路由 |
| `service/SessionTitleService.java` | 🆕 系统模型标题生成 |
| `controller/ModelController.java` | 🆕 §4.1 六端点 |
| `service/SessionUserStore.java` | `model` 列 + `upsertModel/findModelBySession` + remark 读写归位 |
| `controller/ChatStreamController.java` | `ChatRequest.model` + 标题触发 |
| `controller/ThreadController.java` | `PatchRequest.model`、list/详情返回 `model` |
| `config/AgentScopeConfig.java` | 注册路由中间件、标题 bean、系统模型注释标注 |
| `static/debug/js/models.js` + `index.html`/`router.js` | 🆕 debug 页模型管理模块 |
| `frontend/src/app/assistant/page.tsx` + `components/types.ts` | picker、随消息携带 model、会话回显、侧栏标题 |
| `application.yml` / `.env.example` / `AGENTS.md` / `api.md` / `api-thread-spec.md` | 文档同步 |
| 单测 | §10 全部 |

## 12. 实施批次（单次交付，按依赖排序）

1. **B1 配置保存**：`model_config` Store + `ChatModelFactory` + `ModelCatalog`
2. **B2 切换链路**：`session_user.model` + 路由中间件 + chat/PATCH/GET 扩展
3. **B3 系统模型标题**：`SessionTitleService` + 侧栏标题
4. **B4 管理接口**：`ModelController` 六端点 + 测试连接
5. **B5 UI**：debug 页模型管理 + 对话页 picker

每批带单测，`mvn test` 全绿后走 feature 分支 → PR 门禁（六项必需检查）。

## 13. 实施记录（2026-09-24）

- **批次**：B1–B5 单次交付完成；`mvn test` 912 通过（含新增 ModelCatalogTest 13 / SessionModelMiddlewareTest 6 /
  SessionTitleServiceTest 6 / ModelControllerTest 19，及 ChatStream/ThreadController 契约新用例）；前端 `lint`（0 error）+ `build` 通过。
- **偏差 1（接口增强）**：`GET /models` 增加 `?all=true` —— 调试页管理视图需要看到并恢复 **disabled** 托管模型
  （缺省仍只返回启用项，会话 picker 语义不变）。`ModelCatalog.options(boolean includeDisabled)` 承载。
- **偏差 2（配置面裁剪）**：**未引入** `LLM_ALT_MODELS` 之类的 env 静态备选列表——"模型配置保存"统一走
  `model_config` 表（REST CRUD），避免两套机制并存；因此**无新增环境变量**，`.env.example`/`application.yml` 未改。
- **落地文件**（新增）：`config/ChatModelFactory.java`、`service/ModelConfigStore.java`、`service/ModelCatalog.java`、
  `service/SessionModelMiddleware.java`、`service/SessionTitleService.java`、`controller/ModelController.java`、
  `static/debug/modules/models.js`。
- **落地文件**（修改）：`AgentScopeConfig`（buildChatModel 委托工厂 + 路由中间件最外层注册 + 标题服务 bean）、
  `SessionUserStore`（model 列 + ensureColumn + upsertModel/findModelBySession/upsertRemark/findRemarkBySession）、
  `ChatStreamController`（ChatRequest.model + 校验落库 + 标题触发）、`ThreadController`（PatchRequest.model + 列表/详情 model）、
  `static/debug/js/app.js` + `static/debug/js/api.js`（路由与 API 客户端）、
  `frontend/src/app/assistant/page.tsx` + `components/types.ts`（picker、随消息携带 model、会话回显、侧栏标题）。
- **后续可选项**（未做，需要时另开）：A2A 链路经 `message.metadata.model` 切换会话模型；会话模型变更的审计事件。

### 13.1 本地实机冒烟发现的缺陷（已一并修复）

PR 提交前用本地 MySQL(3307)/Redis(16379) + e2e mock LLM 起真实实例走查，暴露三处问题：

1. **`ModelCatalog` 多构造器无 `@Autowired` → 应用起不来**（三个 E2E 作业全挂的根因）：
   测试用包级构造器使 Spring 回落无参构造并抛 `NoSuchMethodException`。修复：public 构造器显式 `@Autowired`，
   并加回归断言（`ModelCatalogTest#shouldExposeSingleAutowirablePublicConstructor`）。
2. **MySQL ERROR 1093（既有静默缺陷）**：`INSERT ... VALUES (?, COALESCE((SELECT user_id FROM session_user ...), 'unknown'), ...)`
   在 MySQL 8 被直接拒绝（"You can't specify target table for update in FROM clause"）——该模式是
   `ThreadController.upsertRemark` 的**原始写法**，意味着**标题重命名此前一直静默失败**（异常被 catch 成 warn），
   单测用 mock DataSource 覆盖不到。修复：`SessionUserStore.upsertColumn` 改 UPDATE 优先、0 行回落 INSERT
   （remark/model 共用，列名白名单防注入）；新增真实 MySQL IT `SessionUserStoreMySqlIT`（`HITL_MYSQL_IT=1` 开关）作回归守卫。
3. **标题触发条件与前端流程不符**：前端首条消息自带 `sessionId`（`webui-xxx`），`isNewSession` 恒为 false →
   标题永不生成。修复：以"`session_user` 中是否已有该会话行"（`firstEverTurn`）判定会话首轮，A2A 已登记会话不触发。

冒烟结论（全部通过）：`GET/POST/PATCH/DELETE /models` 契约与掩码、非法校验词表、模型绑定落库与列表/详情回显、
**路由决定性验证**（绑定指向无效端口的模型 → chat 失败 = 确认切换生效）、PATCH 中途切回 system → chat 成功、
删除被引用模型 → 会话回落默认模型（warn 一次）且继续保持可用、新会话标题写入 `remark`。
