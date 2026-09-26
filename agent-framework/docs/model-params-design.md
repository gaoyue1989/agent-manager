# 模型采样参数扩展设计（推理强度 / 思考开关 / 温度 / 最大输出 / 频率惩罚）

> 状态：**已实施**（2026-09-27；单测 1043 全绿 + MySQL IT 迁移/回环 2/2 + 集群部署验证通过）
> 上游设计：[session-model-switch-design.md](session-model-switch-design.md)（model_config 表与 /models CRUD 的既有契约，本文只做增量）
> 调研输入：deer-flow（声明式能力 + 按模型方言映射）、agentscope-java 官方（GenerateOptions 中性参数 + 厂商 compat SPI）。

**修订记录**

| 版本 | 变更 |
|------|------|
| v1 | 初版：五参数链路 + reasoning_effort/frequency_penalty 新增 |
| v2 | 明确内网自托管引擎（vLLM/SGLang）为第一优先；两引擎方言合并为同一实现；provider 列启用为方言选择器；修正 enable_thinking 无差别注入的兼容缺陷 |
| v2.1 | 评审修订：provider 空串语义明确为"不变/默认 openai"（与 name/modelId 一致）；§6 补系统模型 runbook；§7 补真实 MySQL IT |

## 0. 决策记录

| # | 决策 | 理由 |
|---|------|------|
| D1 | 新增 `reasoning_effort`、`frequency_penalty` 两列，均 **NULL = 不下发** | 不下发 = 端点默认行为，存量模型零影响；宽松端点与严格端点都不因多余字段 400 |
| D2 | 透传走官方一等字段 `GenerateOptions.reasoningEffort/frequencyPenalty`；**自托管方言（vllm/sglang）例外**，effort 走 `chat_template_kwargs` | 顶层一等字段适用 OpenAI 兼容严格端点；自托管引擎的思考控制统一在 Jinja 模板 kwarg（见 §2 矩阵） |
| D3 | `reasoning_effort` 值集**不做白名单**，仅格式校验（`^[a-z0-9_]{1,16}$`） | 值集因模型而异（OpenAI：minimal/low/medium/high；GLM：low/high/max）；模板不认领的 kwarg 在 Jinja 中无害忽略，合法性由 `POST /models/{id}/test` 兜底 |
| D4 | `frequency_penalty` 范围校验 `[-2.0, 2.0]`，设置后不可撤销"下发"（只能改值，含 0.0） | PATCH record 无法区分"缺省"与"显式 null"；多数端点 0.0 与不传等价。`reasoning_effort` 字符串可表达空串清除，保留三态（对齐 api_key） |
| D5 | `enable_thinking` 与 `reasoning_effort` **配置层保持两个独立字段** | 正交维度：Qwen3/MiMo 系模板只认开关、gpt-oss 系只认 effort（常思考）；合并成单枚举会丢"思考开但不发 effort"的表达，或对不支持 effort 的模型产生伪档位。**载荷层**在自托管方言下合并进同一个 `chat_template_kwargs` Map（合并点是载荷，不是配置） |
| D6 | schema 演进用 `information_schema` 检查 + `ALTER TABLE ADD COLUMN` | MySQL 8.0 不支持 `ADD COLUMN IF NOT EXISTS`；ModelConfigStore 现有 initSchema 是手工 DDL，无 flyway |
| D7 | **vLLM 与 SGLang 合并为同一方言实现**；`provider` 列（已存在，现为摆设）启用为方言选择器，值域约定 `openai`（默认）/`vllm`/`sglang`/`glm`/`deepseek` | 两引擎 OpenAI 兼容层均支持 `chat_template_kwargs` 透传（SGLang 另有服务端级 `--default-chat-template-kwargs`），内网模型思考/effort 控制全部经模板 kwarg，映射分支完全同构；不新增 `reasoning_dialect` 列，避免两个重叠旋钮漂移 |
| D8 | `openai` 方言**不再无差别注入** `chat_template_kwargs.enable_thinking`（修正现状缺陷） | 现实现对所有端点注入，打到 OpenAI/DeepSeek 官方等严格端点会 400；修正后存量内网模型需迁移 provider 值（见 §6 上线动作） |

## 1. 背景与目标

托管模型（model_config）现可配温度、最大输出、思考开关等，但缺**推理强度**与**频率惩罚**。当前可选模型大量经 **vLLM / SGLang 内网自托管**，两引擎的参数通道是本次设计的第一优先；外部托管端点（OpenAI/GLM/DeepSeek 官方 API）次之。五个采样参数：

| 参数 | 现状 | 本期动作 |
|------|------|----------|
| `temperature` | 表/API/透传已有 | 纳入方言矩阵：全引擎通用，零方言处理 |
| `max_tokens` | 同上 | 同上 |
| `enable_thinking` | 已有，但注入逻辑无差别适用所有端点（缺陷） | 语义不变，**注入逻辑按方言收口**（D8） |
| `reasoning_effort` | 无 | **新增**：表列 + API 字段 + 按方言透传 |
| `frequency_penalty` | 无 | **新增**：表列 + API 字段 + 一等字段透传（全引擎通用） |

## 2. 参数 × 方言矩阵（合并分析结论）

五个参数按方言处理成本分三档：

| 参数 | openai（严格外部 API） | **vllm / sglang（合并方言，内网优先）** | glm（官方 API） | deepseek（官方 API） |
|------|------------------------|----------------------------------------|------------------|----------------------|
| `temperature` | 顶层（通用） | 顶层（通用） | 顶层（通用） | 顶层（通用） |
| `max_tokens` | 顶层（通用） | 顶层（通用） | 顶层（通用） | 顶层（通用） |
| `frequency_penalty` | 顶层（通用） | 顶层（通用） | 顶层（通用） | 顶层（通用） |
| `enable_thinking` | **不下发**（端点无此概念，D8） | `chat_template_kwargs.enable_thinking=false` | `thinking: {type:"disabled"}`（GLM-5.3+ 会 400，由连接测试暴露） | 不下发（reasoner 常开，chat 无思考） |
| `reasoning_effort` | 顶层 `reasoning_effort`（一等字段） | `chat_template_kwargs.reasoning_effort`（Jinja 模板认领；不认领则无害忽略） | 顶层 `reasoning_effort`（仅思考开启时生效） | 不下发（官方 API 无此参数） |

**合并结论：**

1. **三个采样参数（temperature / max_tokens / frequency_penalty）天然合并**——它们是 OpenAI 标准顶层字段，vLLM/SGLang 的 OpenAI 兼容层原生支持，走官方一等字段 `GenerateOptions`，无任何方言分支。
2. **思考两参数（enable_thinking / reasoning_effort）在 vllm 与 sglang 间完全同构**——都走 `chat_template_kwargs`，kwarg 名一致，实现为一个共享分支（`case "vllm", "sglang"`）。Jinja 模板对未使用的 kwarg 无害忽略：Qwen3 系模板忽略 `reasoning_effort`、gpt-oss 系模板忽略 `enable_thinking`（其常思考），因此**一个分支安全覆盖两类内网模型**。
3. 真正需要独立方言的只剩外部严格端点：glm（`thinking.type` 嵌套对象）与 deepseek（无参数）。openai 方言是"最朴素"路径，也是未知 provider 的兜底。

## 3. 存储：model_config 演进

```sql
ALTER TABLE model_config
  ADD COLUMN reasoning_effort  VARCHAR(16) DEFAULT NULL NULL AFTER enable_thinking,
  ADD COLUMN frequency_penalty DOUBLE      DEFAULT NULL NULL AFTER reasoning_effort;
```

- 存量行两列落 NULL → 下发行为与升级前完全一致（D1）。
- **代码内迁移**（D6）：`ModelConfigStore.initSchema()` 在 CREATE TABLE（含新列，供全新部署）之后，增加 `ensureColumn(table, column, ddl)`：查 `information_schema.COLUMNS`，缺列则执行 ALTER。幂等、无版本表。
- `provider` 列无 DDL 变更（`VARCHAR(32) DEFAULT 'openai'` 已存在），**值域约定**：`openai` / `vllm` / `sglang` / `glm` / `deepseek`；未知值按 `openai` 兜底（保持现状兼容）。
- `ModelConfig` record 与 `COLUMNS`、`map(rs)`、INSERT/UPDATE 语句同步加两字段，位置对齐 DDL。

## 4. 接口契约变化（/models）

请求体继续 camelCase、响应继续 snake_case。`UpsertRequest` 增两字段：

```jsonc
// POST /models —— 内网 vLLM 模型示例
{
  "name": "qwen3-32b-内网",
  "modelId": "qwen3-32b",
  "baseUrl": "http://10.x.x.x:8000/v1",
  "apiKey": "",
  "provider": "vllm",              // 方言选择器（D7）；缺省 openai
  "reasoningEffort": "medium",     // 新增，可空；空串=清除
  "frequencyPenalty": 0.5          // 新增，可空
}
```

PATCH 三态语义（与 api_key 现状对齐）：

| 字段 | 缺省（不传） | 空串 / 数值 |
|------|--------------|-------------|
| `reasoningEffort` | 不变 | **空串 = 清除**（回落 NULL，不下发）；非空串 = 替换（D3 格式校验） |
| `frequencyPenalty` | 不变 | 数值 = 替换；**不支持清除下发**，只能改值（D4） |
| `provider` | 不变（**空串同缺省**，与 name/modelId 语义一致） | 值域校验（§3 枚举 + 未知值 400，防止拼写错误静默落错方言；`openai` 兜底仅适用于**存量数据**读取路径） |

响应视图（`managedView`）增三键：`reasoning_effort`、`frequency_penalty`（NULL → `null`）；provider 已在现有视图中。`systemModelView` 同步透出系统模型对应值（未配置为 null）。`GET /models` 列表项不扩展（维持 picker 轻量语义）。

校验汇总（超限/非法 → 400 `invalid_config`）：

| 字段 | 规则 |
|------|------|
| `reasoningEffort` | null 或匹配 `^[a-z0-9_]{1,16}$` |
| `frequencyPenalty` | null 或 ∈ [-2.0, 2.0] |
| `provider` | null 或 ∈ §3 枚举（写入路径严格；存量读取宽松） |

## 5. 运行时透传链路（方言单点收口）

```
model_config 行
  → ModelConfigStore.ModelConfig（record + 2 字段）
  → ModelCatalog.build() → AgentManagerProperties.LLMConfig（record + 2 可空字段 + provider 已有）
  → ChatModelFactory.build() → applyDialect()  ← 唯一方言收口点
  → GenerateOptions / additionalBodyParams → OpenAIChatModel → OpenAIRequest
```

ChatModelFactory 增量（示意）：

```java
var optionsBuilder = GenerateOptions.builder()
    .temperature(llm.temperature())     // 三采样参数：一等字段，全引擎通用（§2 结论 1）
    .maxTokens(llm.maxTokens());
if (llm.frequencyPenalty() != null) {
    optionsBuilder.frequencyPenalty(llm.frequencyPenalty());
}

// 思考两参数：按方言收口（D7/D8）
switch (llm.provider()) {
    case "vllm", "sglang" -> {
        // 合并进同一个 Map——additionalBodyParam 同 key 覆盖，必须一次 put（D5 载荷合并点）
        Map<String, Object> kwargs = new HashMap<>();
        if (!llm.enableThinking()) {
            kwargs.put("enable_thinking", false);
        }
        if (llm.reasoningEffort() != null && !llm.reasoningEffort().isBlank()) {
            kwargs.put("reasoning_effort", llm.reasoningEffort());
        }
        if (!kwargs.isEmpty()) {
            optionsBuilder.additionalBodyParam("chat_template_kwargs", kwargs);
        }
    }
    case "glm" -> {
        if (!llm.enableThinking()) {
            optionsBuilder.additionalBodyParam("thinking", Map.of("type", "disabled"));
        }
        if (llm.reasoningEffort() != null && !llm.reasoningEffort().isBlank()) {
            optionsBuilder.reasoningEffort(llm.reasoningEffort());
        }
    }
    case "openai", default -> {
        // 严格外部端点：思考开关不下发（D8）；effort 走官方一等字段
        if (llm.reasoningEffort() != null && !llm.reasoningEffort().isBlank()) {
            optionsBuilder.reasoningEffort(llm.reasoningEffort());
        }
    }
    // deepseek：两者均不下发，无分支
}
```

- **正交性**：vllm/sglang 方言下 `enable_thinking=false` + `reasoning_effort=low` 自然共存于同一 kwargs；模板按需取用，无需联动校验（D5）。
- 系统模型（LLM_* env）：新增可选 `LLM_REASONING_EFFORT` / `LLM_FREQUENCY_PENALTY`，缺省不下发；provider 沿用 `LLM_PROVIDER`（缺省 openai）。系统模型行为默认不变。
- 模型级 options 是默认值：agentscope `mergeOptions(模型级, 调用级)` 语义下，调用侧未显式指定的字段沿用模型级配置。
- 连接测试 `POST /models/{id}/test` 走同一 `build`，自动覆盖新参数与方言：配错的方言/端点不认的参数在这里以 502 + 端点报错暴露（GLM-5.3 thinking disabled → 400 即此类）。

## 6. 兼容性影响分析

| 场景 | 行为 |
|------|------|
| 存量托管模型行（新列 NULL） | 请求体与升级前逐字节等价，零行为变化 |
| **存量 `provider='openai'` 的内网模型**（如已建的 mimo-v2.6-flash） | D8 修正后 `enable_thinking=false` 不再注入 chat_template_kwargs → MiMo 端点恢复默认开思考（`<think>` 内容回归）。**上线动作：这些模型 PATCH `provider=vllm`**（MiMo 端点实测接受 chat_template_kwargs，改后行为与今天一致） |
| **系统模型指向内网 vLLM/Qwen 端点**（`LLM_PROVIDER` 缺省 `openai`） | 同受 D8 影响：升级后系统模型不再注入 `enable_thinking=false`。若依赖关闭思考（如标题生成去 `<think>`），**上线动作：显式设置 `LLM_PROVIDER=vllm`**；env 无值域校验但方言映射做了大小写归一 |
| 老客户端 PATCH（不带新字段） | 缺省 = 不变，契约向后兼容 |
| 新旧版本混跑（滚动发布） | 新列对旧代码透明；旧 Pod 写行新列 NULL，语义正确 |
| `GET /models/{id}` 响应 | 纯新增键，宽松解析无影响 |
| Debug 页「Models」表单 | 后端先行，前端表单可后续跟进（非本期阻塞项） |

**上线顺序**：部署新版本 → 对内网模型逐一 `PATCH provider`（vllm/sglang）→ 逐模型跑 `/models/{id}/test` 验证。

## 7. 测试要点

1. schema：全新部署建表含新列；存量库 `ensureColumn` 幂等（二次启动不再 ALTER）——真实 MySQL 覆盖见 `ModelConfigStoreMySqlIT`（`HITL_MYSQL_IT=1` 门控，含旧表自动迁移场景）
2. CRUD：新参数落库与回读；`reasoningEffort: ""` 清除；非法值（`Medium`/`3.0`/provider 拼错）400
3. **方言矩阵断言**（抓请求体，每方言一组）：
   - vllm/sglang：effort+开关同入一个 `chat_template_kwargs`（Map 合并、无覆盖丢失）；temperature/max_tokens/frequency_penalty 在顶层
   - openai：effort 在顶层；enable_thinking=false 不产生任何附加字段
   - glm：`thinking.type=disabled` 嵌套结构正确
   - deepseek：思考/effort 均不出现在请求体
   - 未知 provider 值的存量行按 openai 路径下发
4. 连接测试：内网 vLLM（Qwen3 系：effort 被模板忽略不出错）与 SGLang 各跑一轮；GLM-5.3 配 disabled 复现 400 → 502 透出
5. 回归：未配置新参数的既有模型会话对话、标题生成、记忆压缩行为不变

## 8. 非目标（后续演进，本文不做）

- **值集声明与前端能力渲染**（deer-flow 式 per-model effort 档位/thinking 三态 unsupported/optional/required）
- **厂商 compat provider**（agentscope 式 SPI，glm/deepseek 包）——方言分支超出一处 switch 再引入
- `thinkingBudget`、`top_p`、`presence_penalty`、`seed` 等其余 GenerateOptions 参数的配置化
- `extra_body` 自由 JSON 列（逃生舱的配置面暴露）

## 9. 引擎支持依据

- SGLang：OpenAI 兼容层支持 per-request `chat_template_kwargs` 与服务端级 `--default-chat-template-kwargs`（Qwen3 `enable_thinking` 即此通道）；同时支持 OpenAI 风格顶层 `reasoning_effort`
- vLLM：`chat_template_kwargs` 为协议一等字段，模板 kwarg 透传；`reasoning_effort` 顶层支持随版本/模型模板而异 → 统一走 kwarg 路线规避差异
- GLM 官方：`thinking.type` 开关（GLM-5.3+ 不接受 disabled，400）；`reasoning_effort` 仅思考开启时生效
- DeepSeek 官方：无思考开关与 effort 参数
