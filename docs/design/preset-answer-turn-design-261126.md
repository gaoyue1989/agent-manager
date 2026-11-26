# 预置一问一答轮次设计（Preset Answer Turn）

> 状态：**已实施（2026-11-26，feat/preset-turn-chat 分支）**。
> 关联：[session-context-injection-design-261125.md](session-context-injection-design-261125.md) 是「参考资料进系统提示词」，本设计是「一组问答进对话历史」，两者相互独立、可同时使用。

## 1. 需求与决策

前端把问题和答案都生成好，调一次 `POST /threads/chat`；服务端不触达真实模型，把这一组问答作为**一轮正常对话**写进会话，之后的轮次能在对话历史里看到它。

用户确认的决策（2026-11-26）：

| # | 决策 | 结论 |
|---|---|---|
| D0 | 请求以什么为单位 | 一轮 = 问题 + 回答，字段 `presetTurn: {question, answer}`，与非空 `message`/`fileIds` 互斥 |
| D1 | 预置轮是会话首条消息时，标题怎么来 | 照常调标题模型 |
| D2 | 历史里是否标记预置轮 | 不标记，历史里无法区分 |
| D3 | 后台记忆与压缩如何对待预置问答 | 与普通轮次一致 |

范围：只做 `/threads/chat`；一次请求一轮；答案只支持文本（可含 Markdown）。A2A、AG-UI、控制台特殊展示不在范围。

## 2. 已核实的代码事实

| # | 事实 | 依据 |
|---|---|---|
| F1 | 中间件替换 `ModelCallInput` 的 model 是 SDK 的常规扩展方式，下游与模型调用核心看到的都是替换后的模型 | `SessionModelMiddleware.onModelCall` |
| F2 | 会话模型路由在每次模型调用上都整体替换 model（会话绑定 → 全局默认模型） | `SessionModelMiddleware.onModelCall` |
| F3 | LLM 调用记录在模型调用结束事件处落盘，记录的模型名取自它自己看到的 `input.model()` | `LlmLoggingMiddleware.onModelCall` |
| F4 | 本轮用量汇总按模型调用结束事件计数，每见一个 `model_calls` 加一 | `TurnUsageTracker.onModelCallEnd` |
| F5 | SDK 只在处理模型响应分片时给最终助手消息分配 id（`messageId = chunk.getId()`）；没有分片时消息 id 为空，`AssistantMessage.Builder` 不会自动补 | agentscope-core 2.0.4 `ReasoningContext.processChunk` / `buildFinalMessage` |
| F6 | 消息归档以 `(session_id, msg_id)` 为幂等键，id 为空的消息直接跳过；history 合并时「仅存在于会话状态、不在归档表」的消息被追加在归档行之后 | `SessionMessageArchiveStateStore.toRecord`、`ThreadController` history 合并 |
| F7 | 请求级数据经 `RuntimeContext` 类型化属性随行是现成机制：本轮存活、SDK 不持久化 | `ChatRequestHeaders.toRuntimeContext()` |
| F8 | SDK 的 Hook 接口已标记废弃，工程内仅为 `gotoReasoning` 而保留使用 | `EmptyCompletionRecoveryHook` 类说明 |

F5 与 F6 是实施中实测发现的：首版按「中间件直接发文本事件」实现，本地 e2e 的 history 返回 `user, user, assistant, assistant`，归档表里没有预置答案这一行。

## 3. 方案选型

| 方案 | 做法 | 结论 |
|---|---|---|
| 甲 前端把答案拼进 `message` | 不改代码 | **不采纳**：答案以用户身份进入历史，角色错位 |
| 乙 服务端绕过 agent，直接改写会话状态 | 手工拼用户消息与助手消息写状态库 | **否决**：要自己处理多副本并发、压缩、归档、事件流，SDK 也没有「不调模型直接追加消息」的公开入口 |
| 丙 让模型把给定答案原样复述 | 提示词约束 | **否决**：仍调模型，且不保证逐字一致 |
| 丁-1 中间件短路，直接发文本事件 | 模型调用链上不调用下游，自己发文本块事件 | **实施后否决**：走不到 F5 的分片处理，预置答案没有 id，落不了归档表（F6），历史顺序错乱；补 id 只能靠已废弃的 Hook（F8）或落库装饰器补丁 |
| 丁-2 把本轮模型替换为本地预置模型（**选定**） | 中间件把 model 换成返回预置文本的本地 Model，再照常调用下游 | 走 SDK 完整的模型调用路径，id、事件流、落库、归档全部由既有逻辑处理；只用了 F1 这一常规扩展方式，不触达任何真实模型 |

## 4. 接口契约

### `POST /threads/chat`

```json
{
  "sessionId": "可选，缺省自动建会话",
  "presetTurn": {
    "question": "总结一下今天的头条",
    "answer": "今天共有三条要闻：……"
  }
}
```

| 字段 | 必填 | 约束 |
|---|---|---|
| `presetTurn` | 否 | 省略时行为与原先完全一致 |
| `presetTurn.question` | 是 | 这一轮的问题，非空白 |
| `presetTurn.answer` | 是 | 这一轮的回答，非空白纯文本（可含 Markdown） |

- `presetTurn` 与非空 `message`、`fileIds` 互斥；空白 `message`、空 `fileIds` 不算冲突。
- `model` 传值仍照常绑定会话；`skills`、`tools` 对预置轮没有作用。
- 入口校验失败返回一帧 `error`，不抢租约、不写会话、不调模型：`preset_turn_invalid`（问题或答案空白）、`preset_turn_conflict`（与非空 `message`/`fileIds` 并存）。
- 会话处于 `permission_ask` 挂起态时与普通消息一样被拒（`turn_pending_confirm`）。

成功时 SSE 帧与普通轮次一致，不新增帧类型。`AGENT_END.usage` 的 token 全为 0，`model_calls` 为 1——那是对本地预置模型 `preset-answer` 的一次调用。`GET /threads/{sessionId}/llm-calls` 里会有一条 `request.model = "preset-answer"` 的记录。

历史接口不做任何字段变化：这组问答就是一条普通用户消息加一条普通助手消息（D2）。

字段级细节以 [agent-framework/docs/api.md](../../agent-framework/docs/api.md) 为准。

## 5. 组件

| 组件 | 职责 |
|---|---|
| `PresetAnswerMiddleware` | 只实现 `onModelCall`。运行时上下文带 `PresetAnswer` 时，把本轮 model 换成返回预置文本的本地 Model；不带时原样透传 |
| `ChatStreamController` | `ChatRequest` 增加 `presetTurn`；入口校验与互斥；`question` 充当本轮用户消息；`answer` 经 `RuntimeContext` 属性随行（在透传头上下文基础上派生，F7） |
| `HarnessAgentFactory` | 注册中间件 |

注册位置有一条硬约束：**在会话模型路由之内、LLM 调用记录之外**。路由在外层会先把模型换成会话生效模型（F2），本中间件在其内再换才不会被覆盖；记录在外层看到的才是预置模型而非会话模型（F3）。`HarnessAgentFactoryMiddlewareAssemblyTest` 断言这一嵌套关系。

前端、backend、网关、表结构、标题服务、历史解析均不改。

## 6. 关键语义

**预置轮 = 一个真实的对话轮次，唯一区别是「真实模型」被本地预置模型替代。**

- 会话归属、认领、租约与排队、挂起预检、停止、事件流、断线续传、历史、标题、压缩、记忆抽取、归档：全部与普通轮次相同。
- 回答的写入不依赖真实模型连接：会话模型一被调用即报错的桩模型下，预置轮照常完成。
- 与设计稿相比，实施带来一处可见变化：设计稿写的是「`model_calls` 为 0、模型调用记录里不留痕迹」。按丁-2 实现，`model_calls` 为 1（token 为 0），并留下一条标注为 `preset-answer` 的调用记录。由于 D2 决定历史不标记，llm-calls 里这条记录是唯一可按会话查询的明细痕迹（链路追踪的 span 上模型名同为 `preset-answer`）。

由「同等对待」直接带来的两个后果（D3，不特殊处理）：

1. 问答进入对话历史后，会被后台记忆抽取与会话归档当作正常对话内容。
2. 会话很长时，预置轮与普通轮次一样可能触发上下文压缩（压缩会调用摘要模型）。

## 7. 安全

不新增端点、不新增放行路径：沿用 `/threads/chat` 的网关鉴权与归属校验，已有会话只有归属用户能写入。预置答案以助手身份进入该用户自己的会话历史，写入权限的边界与该用户自己发消息相同，不跨用户。

## 8. 测试

单元与集成：

| 测试 | 覆盖 |
|---|---|
| `PresetAnswerMiddlewareTest` | 真实 SDK agent + 与会话模型路由同构的模型替换中间件 + 一调即报错的桩模型：预置轮不触达会话模型、答案逐字成为助手消息、**消息带 id**、会话状态为一问一答、事件流走完整模型调用生命周期；普通轮次照常到达会话模型；不带预置答案时原样透传 |
| `HarnessAgentFactoryMiddlewareAssemblyTest` | 预置回答中间件嵌套在会话模型路由之内、LLM 调用记录之外 |
| `ChatStreamControllerTest` | 答案随行且问题作用户消息、新会话首帧与标题照常生成、透传头与答案共存、普通轮次不携带、空白校验、互斥校验 |

E2E（`agent-framework/e2e`）：

| 用例 | 断言 |
|---|---|
| PA1 | 新会话首帧 `session_created`；答案逐字下发；token 为 0、`model_calls` 为 1；mock 模型零对话调用；标题来自标题模型；调用记录标注 `preset-answer` |
| PA2 | 后续真实提问的模型请求携带 `[用户问题, 助手答案, 新问题]`；history 与归档表顺序一致（F5/F6 的回归锚点） |
| PA3 | 空白、互斥各返回对应 `error` 帧；零模型调用；不认领会话 |
| PA4 | 已有会话追加；他人会话被拒 |
| R8（多副本） | A 副本写入，B 副本历史可见，后续提问携带这组问答 |

mock LLM 的调用记录为此新增 `dialogue` 字段（用户与助手消息的角色和正文），使「后续模型请求确实带着预置问答」可以被直接断言。

## 9. 验证记录（2026-11-26）

**本地隔离环境 e2e**（临时库 + 独立 Redis 逻辑库，真实 MySQL/Redis + mock 模型）：S1/S2/S9/CX1~CX6/PA1~PA4 共 13 例全过。首版（丁-1 短路机制）在同一环境下 PA2 失败，暴露了 F5/F6。

**真机**（本机 kind；一次性工作空间 + 测试 Agent，只把该 Deployment 切到验证镜像；真实模型）：接口 19 项全过。

| 项 | 实测 |
|---|---|
| 预置轮（新会话） | 首帧 `session_created`；答案逐字下发；帧序 `AGENT_START → MODEL_CALL_START → TEXT_BLOCK_START/DELTA/END → MODEL_CALL_END → AGENT_RESULT → AGENT_END`；token 0、`model_calls` 1；耗时 0.6~2.8 秒（含新会话的 agent 初始化） |
| 调用记录 | 仅一条 `preset-answer`，真实模型未被触达 |
| 标题 | 标题模型生成「今日科技领域三条头条汇总」 |
| 后续真实提问 | 模型一字不差复述预置问题，并给出预置答案里三条新闻的关键词（芯片出口管制、国产大模型「沧澜」开源、新能源车企季度交付量）；其请求历史角色序列为 `SYSTEM, USER, ASSISTANT, USER` |
| 历史 | 顺序与内容逐字一致，无预置标记；`subscribe` 回放含预置答案 |
| 拒绝路径 | 空白答案、与 `message` 并存、他人会话三种均按契约拒绝 |
| 控制台 | 调试面板重新加载后读历史：四条消息按「提问 → 预置答案（Markdown 列表渲染）→ 追问 → 真实模型回答」显示，与普通对话无异 |

**未实测**：多副本 R8 交 CI 门禁；控制台没有发起预置轮的界面，实时流式渲染未在控制台走查（该渲染路径与普通轮次相同、未改动）。

## 10. 风险与边界

- 预置轮会照常走系统提示词组装（含会话上下文读取、记忆检索），只是组装结果不发给任何真实模型；`llm-calls` 记录里带有这次的完整请求消息。
- 结束帧的 `model_calls` 为 1，按模型名统计的监控会出现 `preset-answer` 这个标签值（token 恒为 0）。
- 一次请求只能写入一轮；多轮即多次请求，每次是一个完整轮次。
- `answer` 的长度没有上限，与 `message` 同口径；它全文作为**单个** `TEXT_BLOCK_DELTA` 下发（事件存储里对应一行），经平台入口时受入口的请求体大小限制约束。

## 顺带观察（不在本设计范围）

标题模型偶发不生成标题而直接回答问题：同一个预置问题在多次运行里，有一次会话标题成了「我目前无法获取实时资讯，不能提供…」。这是既有标题生成逻辑的模型输出随机性，与预置轮无关。
