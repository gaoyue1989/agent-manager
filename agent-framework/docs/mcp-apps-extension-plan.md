# MCP Apps 扩展接入方案（工具渲染交互式卡片）

> **状态: ✅ 阶段一完成（后端，2026-08-19）；✅ 阶段二完成（Debug 页卡片渲染 + 4.7 静默更新，2026-08-19）**
> 目标：基于 MCP Apps 扩展协议（工具 `_meta.ui.resourceUri` + `ui://` HTML 资源），
> 让 agent 在对话中渲染**交互式卡片**（沙箱 iframe + JSON-RPC over postMessage），支持卡片主动调工具、交互触发会话续写。
> 参考：[MCP Apps 官方文档](https://modelcontextprotocol.io/extensions/apps/build)、
> [ext-apps 仓库](https://github.com/modelcontextprotocol/ext-apps)、
> [specification 2026-01-26 (Stable)](https://github.com/modelcontextprotocol/ext-apps/blob/main/specification/2026-01-26/apps.mdx)、
> [官方公告（2026-01-26 首个官方 MCP 扩展）](https://blog.modelcontextprotocol.io/posts/2026-01-26-mcp-apps)。
> **范围：仅 agent-framework 工程（后端 Java 服务 + 内嵌 Debug 页面）。Go 后端、React 前端本次不涉及。**

---

## 一、背景与目标

### 1.1 需求

当前 agent 工具调用结果只以「文本/JSON 折叠行」展示（Debug 页 `tool-call-row`）。
需要支持 **MCP 扩展协议（MCP Apps）**：MCP server 的工具声明 `_meta.ui.resourceUri` 并托管
`ui://` HTML 资源后，agent 调用该工具时，前端在对话流中渲染**交互式卡片**（表单、图表、按钮等），
卡片内交互可反向调用 server 工具，交互结果可静默更新模型上下文影响后续对话。

### 1.2 MCP Apps 机制速览

MCP Apps 是 MCP 协议的**首个官方扩展**（2026-01-26 发布 Stable 版本，draft 分支继续演进）：
**工具 + 资源 + host 渲染**三要素：

| 要素 | 说明 |
|------|------|
| 工具 | 工具定义带 `_meta.ui.resourceUri`（`ui://` scheme），host 识别后渲染 UI |
| 资源 | server 以 `ui://` 资源托管打包好的单文件 HTML（`vite-plugin-singlefile` 产物），mimeType 为 `RESOURCE_MIME_TYPE` |
| host | 拉取资源 HTML → **沙箱 iframe** 渲染 → **JSON-RPC over postMessage** 双向通信（`ui/initialize`、`ui/message`、`ui/update-model-context`、`ui/open-link`，与核心 MCP 共享 `tools/call`） |

关键机制：
- **Tool-UI 关联**：工具定义**顶层 `_meta.ui.resourceUri`** 字段（`ui://` scheme；`_meta["ui/resourceUri"]` 为 deprecated 旧格式）。
- **能力协商（渐进增强）**：host 连接时在 `capabilities.extensions["io.modelcontextprotocol/ui"]` 声明 UI 能力
  （`mimeTypes: ["text/html;profile=mcp-app"]`），server 据此决定是否注册带 UI 元数据的工具变体；
  不支持 UI 的 host 下工具退化为纯文本（工具结果 MUST 仍含 meaningful content）。
- **Tool visibility**：`["model","app"]` 默认（LLM 可见可调）；`["app"]` 仅 App 可见（刷新/分页/表单提交等 UI 交互工具，不进 LLM 上下文，且禁止跨 server 调用）。
- **Host Context**：theme/locale/display mode/容器尺寸/platform，App 据此自适应渲染。
- **生命周期**：`ui/initialize` → `ui/notifications/initialized` → host 下发 `tool-input`/`tool-result` → 交互 → `ui/resource-teardown`。

官方 host（Claude 等）流程：
```
工具被调用 ─▶ host 按 _meta.ui.resourceUri 拉取 HTML ─▶ 沙箱 iframe 渲染
   ▲                                                      │
   └────── client.callTool() ◀── JSON-RPC ────────────────┘
```

本项目角色分工：**MCP App server**（Node/TS，外部）提供工具+UI；
**agent-framework**（Java）是 MCP 客户端 + 资源/调用代理；**Debug 页面**（agent-framework 内嵌）充当 host。

### 1.3 目标

1. agent-framework 作为 MCP **客户端**：发现带 UI 元数据的 MCP 工具，向 `/tools` API、SSE 事件流透出。
2. 新增**资源代理**与**调用代理**端点，Debug 页（agent-framework 内嵌）可经框架拉取 `ui://` HTML 并代发工具调用。
3. Debug 页面实现 **host 渲染**：iframe + postMessage 协议，工具调用 → 卡片渲染 → 交互回调。
4. 卡片交互可**静默更新模型上下文**（对齐规范 `ui/update-model-context`，不触发新回复，影响后续轮次）。
5. **范围外**：Go 后端与 React 前端（agent-manager）不变更（如需产品页支持，后续另立方案）。

---

## 二、现状分析

### 2.1 现有链路（相关代码）

```
MCP server (外部) ──McpClientBuilder──▶ McpToolRegistrar.java (注册到 Toolkit)
                                            │ listTools() → McpSchema.Tool（无 _meta 字段）
                                            ▼
AgentScope HarnessAgent ──streamEvents──▶ AgentEventSseSerializer.java（词表事件）
                                            │ TOOL_CALL_START / TOOL_RESULT_END 等
                                            ▼
Debug 页 chat.js handleEvent() ──▶ 工具折叠行 / HITL 确认卡片（已有卡片先例）
```

| 现有能力 | 位置 | 与本方案的关系 |
|---------|------|---------------|
| MCP 工具注册缓存 | `McpToolRegistrar.java:125` `recordRegisteredTools` / `:423` `ToolInfo(name, displayName, description, serverName)` | 需扩展 `uiResourceUri` 字段 |
| MCP 配置加载 | `McpManager.java`（ActiveMCP.json / config.yaml connection） | 需透出 `ui` 静态声明 |
| SSE 词表 | `AgentEventSseSerializer.java:46` `TOOL_CALL_START`（toolName/toolCallId） | 需携带 ui 元数据 |
| 前端事件渲染 | `static/debug/modules/chat.js:769` `handleEvent` + `:640` `renderConfirmCard` | HITL 确认卡片是卡片渲染先例，MCP App 卡片可复用交互模式 |
| 工具列表 API | `ToolController.java:59` `/tools` | 需增加 `uiResourceUri` |

### 2.2 SDK 能力验证结论（javap 实测，mcp-core 0.17.0 / agentscope-core 2.0.0）

| 验证项 | 结论 |
|-------|------|
| `McpSchema.Tool` | ✅ **有 `meta` 字段**（`JsonProperty("_meta")`，`Map<String,Object>`），`tool.meta()` 可读 `_meta.ui.resourceUri` → **自动发现可行** |
| `Tool` Jackson 兼容性 | ✅ 类级 `@JsonIgnoreProperties(ignoreUnknown=true)` → 未知字段静默丢弃，**不会抛异常**（不存在「listTools 崩溃」风险） |
| `McpSchema.Tool.annotations()` | 仅 `title/readOnlyHint/destructiveHint/idempotentHint/openWorldHint/returnDirect`，与能力协商无关 |
| `McpSchema.ClientCapabilities` | 只有 `experimental/roots/sampling/elicitation` 四个字段，**无 `extensions`** → SEP-1724 的 `capabilities.extensions["io.modelcontextprotocol/ui"]` 无法表达，能力协商在 0.17.0 上不可用 |
| `McpClientWrapper` | 仅 `listTools/callTool`，**无 resources API**，底层 `client` 字段 private；`McpClientBuilder.buildCapabilities()` 亦为 private |
| `McpSyncClient`（SDK 原生） | ✅ 有 `listResources()/readResource()/listResourceTemplates()`，可独立建连；`resources/read` 是核心 MCP 方法，**不需要能力协商**。构建方式：**`McpClient.sync(transport).build()`**（无 `McpSyncClient.builder()`；`SyncSpec` 支持 `requestTimeout/capabilities/clientInfo/...`） |
| transport 构建 | `HttpClientStreamableHttpTransport.builder().endpoint(url).build()` / `HttpClientSseClientTransport.builder(url).build()` / `StdioClientTransport.builder(...)` |
| `ResourceContents._meta` | ✅ `TextResourceContents` 实现 `Meta` 接口，有 `meta()` → 资源动态 `_meta.ui.csp` **可读**（config.yaml 静态声明仍优先，见 4.2） |

**结论**：能力协商在 0.17.0 上不可表达（唯一硬限制，server 是否注册 UI 变体取决于其自身实现），
但 `_meta.ui` **可读** → **静态声明为主路径（管理员可控），自动发现为兜底**（config.yaml 优先，见 4.2）；
资源读取需**独立资源连接**（`McpClient.sync()`，见 4.3）。

---

## 三、总体架构

### 3.1 组件拓扑

```
┌─────────────────────┐   MCP (sse/streamableHttp)   ┌──────────────────────────────────┐
│  MCP App server      │◀────────────────────────────▶│  agent-framework (Java :8100)      │
│  (Node/TS, vite)     │   tools + ui:// 资源          │  ├ McpToolRegistrar (工具注册)      │
│  - 工具带 _meta.ui   │                              │  ├ McpResourceProxy (资源+调用代理) │
│  - ui:// 资源 HTML   │                              │  └ SSE 词表扩展 (ui 元数据)         │
└─────────────────────┘                              └──────────────────────────────────┘
                                                              │ REST (资源/调用代理)
                                                              │ SSE (长连接事件流)
                                                      ┌───────▼──────────────────────┐
                                                      │ Debug 页 host（卡片渲染）      │
                                                      │  chat.js + McpAppHost (阶段二) │
                                                      │  沙箱 iframe ◀─postMessage─▶    │
                                                      └──────────────────────────────┘
```

### 3.2 一次完整的卡片交互时序

```
用户: "给我一个天气卡片"
  │
  ▼
agent 决定调用 get_weather (config.yaml ui 声明 resourceUri=ui://weather/mcp-app.html)
  │
  ▼
TOOL_CALL_START(ui: {resourceUri, server}) ──SSE──▶ 前端: 创建卡片占位
  │                                                    │ GET /mcp/weather/resources/ui?uri=ui://...
  │                                                    ▼
  │                                              McpResourceProxy → server readResource → HTML + CSP 声明
  │                                                    │
  │                                                    ▼
  │                                              沙箱 iframe 渲染（McpAppHost 注入 CSP meta）
  │                                                    │ ui/initialize → 响应 → ui/notifications/initialized
TOOL_RESULT_END(state=success) ──SSE──────────────▶ 前端: 按序下发 ui/notifications/tool-input → tool-result
  │
  │   用户点击卡片按钮
  │                                                    │ JSON-RPC: tools/call
  │                                                    ▼
  │                                              POST /mcp/weather/tools/{tool} (带 sessionId/userId)
  │                                                    │ McpResourceProxy.callTool → server
  │                                                    │ 可选: 静默更新模型上下文（ui/update-model-context 语义）
  │                                                    ▼
  │                                            (回执) tools/call 响应回发 iframe
```

### 3.3 三种卡片来源（由简到繁）

| 模式 | 说明 | 前端渲染方式 | 适用场景 |
|------|------|------------|---------|
| **A. MCP Apps 标准** | server 声明 `_meta.ui` + 托管 `ui://` 资源 | 拉取 HTML → iframe | 官方 MCP App（Node 脚手架产物） |
| **B. 静态声明** | `config.yaml` 声明 `ui.tools`（逐工具指向本机静态 HTML 或远程） | 同上（代理读取） | 无 UI 元数据的既有 server、内网不便起 Node 服务的场景 |
| **C. 内联 HTML 结果**（后续可做） | 工具结果直接返回 `text/html` content | 直接注入 iframe | 快速验证、不依赖资源端点 |

阶段一/二先实现 **A + B**。

> **关于 A 模式的渐进增强限制**：Stable 规范要求 host 连接时通过 `capabilities.extensions["io.modelcontextprotocol/ui"]`
> 声明 UI 能力，server 才注册带 `_meta.ui` 的工具。实测 mcp-core 0.17.0 的 `ClientCapabilities` **无 `extensions` 字段**，
> agentscope wrapper 亦无法注入 → 能力协商在 Java 侧 0.17.0 上**不可表达**，且即使 server 注册了 text-only 变体
> （无 UI 元数据），工具调用也完全正常（规范要求工具结果 MUST 含 meaningful content）。
> **因此：A 模式与 B 模式统一走 config.yaml 静态声明**（`_meta.ui` 自动发现为 P2，依赖 mcp-core 升级，见 4.2）。
> `resources/read` 是核心 MCP 方法，能力协商不影响资源代理。

---

## 四、后端设计（agent-framework）

### 4.1 工具 UI 元数据模型

扩展 `McpToolRegistrar.ToolInfo`（`McpToolRegistrar.java:423`）：

```java
public record ToolInfo(
    String name,
    String displayName,
    String description,
    String serverName,
    String uiResourceUri,   // 新增: "ui://weather/mcp-app.html"，无 UI 为 null
    String uiSource         // 新增: "config"（当前唯一来源）；未来自动发现可用时扩展
) {}
```

- 构造点：`recordRegisteredTools`（`:125`）与 `registerReadOnly`（`:265`）两处同步补充。
- **不存 `_meta.ui` 之外的注解**（`title/readOnlyHint` 等与现有 `permissions.read_only` 逻辑重复，不引入）。

### 4.2 UI 元数据发现机制（静态声明为主，自动发现兜底）

1. **静态声明（主路径，管理员可控）**：`mcp-configs/{server}/config.yaml` 新增 `ui` 段，
   **必须显式逐工具声明**（无 server 级默认，避免误标无 UI 工具）：
   ```yaml
   connection:
     type: streamableHttp
     url: http://weather-app:3001/mcp
   ui:
     tools:
       get_weather: "ui://weather/mcp-app.html"   # 有 UI 的工具显式声明
       get_forecast: "ui://weather/forecast.html"
     app_only:                                    # 仅卡片可调，对 LLM 隐藏（对齐规范 visibility:["app"]）
       refresh_dashboard: "ui://weather/mcp-app.html"
     csp:                                         # 资源 CSP 白名单静态声明（0.17.0 资源 _meta 读不到时的兜底）
       connect_domains: ["https://api.weather.com"]
       resource_domains: ["https://cdn.jsdelivr.net"]
   ```
   - 在 `McpToolRegistrar` 新增 `loadUiMapping(mcp)`（仿 `loadToolPermissions` 的 `loadConfigYaml` 解析，`:168`）：
     - 逐工具显式声明 → `ToolInfo.uiResourceUri`（`uiSource=config`）
     - **`app_only` 工具不注册进 Toolkit，但必须记录 ToolInfo**（含 uiResourceUri，供代理校验 + `/tools` 标记）
       （对齐规范 `visibility: ["app"]` 语义，弥补无能力协商时 server 不注册 UI 工具变体的缺口）
     - `csp` 静态白名单并入资源代理（见 4.4），与资源动态 `_meta.ui.csp` 叠加时**取交集**（host 只能收紧）
2. **`_meta.ui` 自动发现（兜底，阶段一实现）**：0.17.0 的 `Tool` record 有 `meta()`（JsonProperty `_meta`），
   工具注册缓存时读取 `tool.meta().get("ui") → resourceUri`，命中且未在 config.yaml 声明时
   `ToolInfo.uiResourceUri` 取自动发现值（`uiSource=auto`），**config.yaml 声明优先**。
   局限：server 是否注册带 `_meta.ui` 的工具变体取决于其自身实现（能力协商 0.17.0 不可表达），
   未命中时仍可靠静态声明兜底。
3. **SDK 升级追踪**：跟踪 mcp-core 新版本对 SEP-1724 extensions 能力协商的支持，届时评估能力协商。

### 4.3 资源代理服务 McpResourceProxy（新）

**问题**：`McpClientWrapper` 不暴露 resources，且 `client` 字段 private。
**方案**：独立建连的代理服务，按 server 懒加载 `McpSyncClient`（SDK 原生，同一份 config.yaml 连接配置）。

```java
@Service
public class McpResourceProxy {
    // serverName -> McpSyncClient（懒加载 + 复用 + 超时回收）
    private final ConcurrentHashMap<String, McpSyncClient> clients;

    /** 按 server 读取 ui:// 资源，仅允许 ui:// scheme */
    public ResourceResult readUiResource(String serverName, String uri);
    /** 代发工具调用（供 UI 回调与 /mcp 代理共用） */
    public CallResult callTool(String serverName, String toolName, Map<String, Object> args);
}
```

- 传输构建：从 `config.yaml` 读 `connection`（复用 `McpClientBuilder` 的解析逻辑，抽公共方法或复制 transport 分支），
  用 SDK `McpClient.sync(transport).build()` 构建 `McpSyncClient`（0.17.0 无 `McpSyncClient.builder()`；
  transport 用 `HttpClientStreamableHttpTransport.builder().endpoint(url)` /
  `HttpClientSseClientTransport.builder(url)` / `StdioClientTransport.builder(...)`）。
- 认证：同样支持 `auth.token` + `${ENV_VAR}`（经 `customizeRequest` / `HttpClient.Builder` header 注入）。
- 资源读取：`client.readResource(uri)` → 返回 `ResourceContents`（text 类型）。
- **懒连接**：仅在首次读资源/代发调用时连接；连接失败返回 502 并记日志，不影响 agent 主链路。

### 4.4 新增端点（controller）

| 方法 | 路径 | 说明 | 鉴权/校验 |
|------|------|------|----------|
| GET | `/mcp/{server}/resources/ui?uri=ui://...` | 读取 `ui://` 资源 HTML，响应含 CSP 元数据 JSON 字段 | uri scheme 必须 `ui://`，且 **uri ∈ 该 server 静态声明集合**（防任意资源读取代理）；server 必须已注册；大小上限 1MB |
| POST | `/mcp/{server}/tools/{tool}` | UI 卡片代发工具调用 | body: `{arguments, sessionId?, userId?}`；空参数校验；仅允许已注册 MCP 工具（防任意调用） |
| GET | `/mcp/{server}/resources`（可选） | 列出该 server 全部 `ui://` 资源，供前端预拉取 | — |

**CSP 施加机制（修正 srcdoc 注入下 CSP 头不生效的矛盾）**：

- Debug 页用 `srcdoc` 注入 HTML 时，HTTP 响应头 CSP **不会作用于 iframe 内容**。因此 CSP 传递方式为：
  代理端点响应用 JSON 返回 `{html, mimeType, csp: {...}}`，由前端 `McpAppHost` 构造
  `<meta http-equiv="Content-Security-Policy" content="...">` 注入 HTML `<head>`（等价于规范 sandbox proxy 的 CSP 施加路径）。
- CSP 值构造：静态声明 `ui.csp`（config.yaml，管理员可控）与资源动态 `_meta.ui.csp` **取交集**（host 只能收紧）。
  当前 0.17.0 大概率丢弃资源 `_meta`（与 `Tool._meta` 同因，实施阶段一先行验证），故阶段一实际以静态声明为准；
  无任何声明时用宽松 default：
  ```
  default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline';
  img-src 'self' data:; media-src 'self' data:; connect-src 'self'; object-src 'none'; frame-src 'none'
  ```
  （`connect-src 'self'` 允许卡片调用同源代理端点，同时禁止外联；需外联的卡片通过 config.yaml `csp.connect_domains`
  声明白名单。资源 MIME 为 `text/html;profile=mcp-app`。）
- **不得下发 `frame-ancestors`**：卡片本身要在宿主页面 iframe 内渲染，`frame-ancestors 'none'` 会自毁渲染；
  若需防外部页面嵌套，由宿主页面自身 CSP 管，不在资源响应层做。

**工具调用代理的鉴权联动**：
- 只允许调用**已注册**工具（`registeredTools` 缓存校验，含 app_only 工具），且：
  - `permissions.read_only: true` 的 server → 拒绝写工具（沿用只读语义）。
  - `permissions.tools` 声明 `deny` 的工具 → 拒绝。
  - `permissions.tools` 声明 `ask` 的工具 → **走 UI 侧确认流程**：
    1. 代理端点返回 `403` + `{needsConfirm: true, toolCalls: [{name, arguments, toolCallId}]}`（复用 HITL 确认卡片数据格式）。
    2. 前端 `McpAppHost` 检测 `needsConfirm` → 在卡片外弹出 HITL 确认卡片（复用 `renderConfirmCard` 样式）。
    3. 用户 Approve → 前端重试 `POST /mcp/{server}/tools/{tool}`，请求头带 `X-Confirm-Approve: true`（或 body `confirmed: true`）。
    4. 代理端点校验确认标记后执行。
    5. 用户 Reject → 不重试，卡片显示"已拒绝"。
  - app_only 工具仅限 `tools/call` 由卡片经代理调用（同 server 内），LLM 路径不可见。
- 调用结果格式：`{content: [...], isError}` 原样透传。

### 4.5 SSE 词表扩展

`AgentEventSseSerializer.payload()`（`AgentEventSseSerializer.java:33`）在 `ToolCallStartEvent` 分支：
SDK 事件本身无 ui 元数据，需在**事件发源地**补全。做法：

- `AgentRuntimeService` / `SessionStreamController` 的事件转发处，维护 `toolName → ToolInfo` 查询
  （注入 `McpToolRegistrar`），`TOOL_CALL_START` 时若工具带 `uiResourceUri`，payload 追加：

```json
{
  "type": "TOOL_CALL_START",
  "toolCallId": "...",
  "toolName": "get_weather",
  "ui": { "resourceUri": "ui://weather/mcp-app.html", "server": "weather" }
}
```

- 无 UI 的工具 payload 不变（词表向后兼容，旧前端无感知）。
- 同理 `TOOL_RESULT_END` 不追加（前端按 `toolCallId` 关联占位卡片即可）。
- **裸名歧义处理**：`McpTool` 注册用远端裸名，`ToolCallStartEvent` 不含 server 信息，
  跨 server 同名工具（`weather:get_weather` 与 `stocks:get_weather`）查表可能命中多条。
  策略：同一裸名对应多个 ToolInfo 且 `uiResourceUri` 不同 → **不携带 `ui` 字段**（安全降级为普通工具行，
  并记 WARN 日志提示管理员改用不同裸名或在 config.yaml 消歧）；
  若多个 ToolInfo 的 `uiResourceUri` 相同（同资源），可正常携带（渲染结果一致）。

### 4.6 /tools API 扩展

`ToolController.getMcpTools()`（`ToolController.java:83`）每项增加：

```json
{ "name": "get_weather", "server": "weather", "category": "mcp",
  "description": "...", "uiResourceUri": "ui://weather/mcp-app.html", "appOnly": false }
```

- `appOnly: true` 标记 app_only 工具（对 LLM 隐藏、仅卡片可调），前端据此区分展示。
- `McpManager.getMcpSummaries()` 同步透出 `has_ui: true`（server 级标记，表示该 server 有带 UI 的工具，供前端预检）。

### 4.7 卡片交互静默更新模型上下文（本项目增强点）

MCP Apps 标准模型中，卡片 `tools/call` 结果只回 iframe，**不进 LLM 上下文**。
本项目目标「卡片交互影响后续对话」，采用**静默更新上下文**模式（不触发新回复）：

- **阶段二（对齐规范 `ui/update-model-context` 语义，已实施）**：
  卡片 `tools/call` 交互结果经 `ui/update-model-context` 推送时，host 调用
  `POST /mcp/ui-context`（入参 `{sessionId, content?, structuredContent?}`，sessionId 必含 `tenant:thread`），
  后端持久化到独立表 `ui_context`（覆盖式 upsert），下次 agent 调用时注入为 system context。
  - **注入实现（实测修正）**：HarnessAgent **拒绝** `PreCallEvent.inputMessages` 中 role=SYSTEM 的消息
    （"Hooks must not inject SYSTEM messages into PreCallEvent.inputMessages"），且 Hook 无 RuntimeContext
    可拿 sessionId——因此 Controller 在用户消息 metadata 写入 `uiContextSessionId`，
    `UiContextInjectionHook`（新，注册于 AgentScopeConfig.harnessAgent）在 PreCallEvent 阶段查库后
    `appendSystemContent()` 注入。metadata 随历史持久化，历史重放时仍注入最新值（符合覆盖语义）。
  - 不触发新回复，不影响当前流；下次用户消息时 LLM 自然感知 UI 交互结果。
  - 每次更新**覆盖**上次（规范语义：each request overwrites the previous context）。
- **P2（对齐规范 `ui/message` 语义）**：`POST /mcp/{server}/tools/{tool}` body 带
  `sessionId`/`userId` 时，调用成功后经 `ChatUiChannel.send(SendOptions, "[ui-action] ...")`
  以 user 角色消息触发新一轮 agent 回复（ChatUiChannel 队列化，不打断进行中流）。
  作为需要"立即触发回复"场景的备选路径。
- **安全**：仅允许注入与 `sessionId` 匹配的会话（校验 `sessionId` 格式 `tenant:thread`），防跨租户注入。

---

## 五、Debug 页面 host 设计（agent-framework 内嵌）

### 5.1 卡片渲染器与 McpAppHost（阶段二）

**5.1.1 卡片渲染器注册**（仿 `RENDERERS`，`chat.js:27`；已实施）

- `TOOL_CALL_START` 带 `ui` 字段时，不走 `renderToolRow`，改走 `renderMcpAppCard(r, data.ui)`：
  - 占位卡片：标题（toolName + resourceUri）+ 「加载中…」+ 折叠头（`.mcp-apps-container/.mcp-apps-header/.mcp-apps-body`，默认展开）。
  - 异步 `fetch(BASE + '/mcp/{server}/resources/ui?uri=...')`（响应 `{html, csp}`）→ 创建
    `<iframe sandbox="allow-scripts">`（**不设 allow-same-origin**），`srcdoc` 注入 HTML
    （**注意：srcdoc 下 CSP 响应头不生效**，由 McpAppHost 将 csp 构造成
    `<meta http-equiv="Content-Security-Policy">` 注入 `<head>`，见 4.4），卡片主体替换占位；
    资源拉取失败显示 `.mcp-apps-error` + 重试按钮。
- `TOOL_RESULT_END` 时，host 按规范通知顺序下发：`ui/notifications/tool-input`（完整参数）→ `ui/notifications/tool-result`。

**5.1.2 McpAppHost（新模块 `static/debug/js/mcp-app-host.js`）**

封装 iframe 生命周期与 **JSON-RPC over postMessage** 协议（Stable 2026-01-26，对应官方 `App` class 的宿主侧实现）。
实现对齐官方 `ext-apps/examples/basic-host`（参考实现），消息映射如下：

| 方向 | 方法（JSON-RPC） | 载荷 | 处理 | 状态 |
|------|-----------------|------|------|------|
| app → host | `ui/initialize` (request) | `{appCapabilities, clientInfo, protocolVersion}` | 回 `McpUiInitializeResult`：`hostCapabilities`（openLinks/serverTools/serverResources 按实现声明）+ `hostContext`（theme/locale/timeZone/containerDimensions 等） | 阶段二 |
| app → host | `ui/notifications/initialized` | `{}` | **handshake 完成标记**，此后 host 才下发 `tool-input` 等消息 | 阶段二 |
| host → app | `ui/notifications/tool-input` (notification) | `{arguments}` | 工具完整参数（initialize 完成后必发，对应 `app.ontoolinput`） | 阶段二 |
| host → app | `ui/notifications/tool-result` (notification) | CallToolResult | 工具执行完成推送（`content` + `structuredContent`，对应 `app.ontoolresult`） | 阶段二 |
| host → app | `ui/notifications/tool-cancelled` | `{reason}` | 工具取消时下发 | 阶段二 |
| app → host | `tools/call` (request) | `{name, arguments}` | 转 `POST /mcp/{server}/tools/{tool}`：正常结果回发；ask 工具返回 `needsConfirm` 时 host 弹确认卡片，Approve 后重试；app-only 工具仅允许同 server 调用 | 阶段二 |
| app → host | `ui/message` (request) | `{role, content}` | P2：转 user 消息触发新回复（见 4.7） | P2 |
| app → host | `ui/update-model-context` (request) | `{content, structuredContent}` | 静默更新模型上下文，下次 agent 调用时注入（见 4.7） | 阶段二 |
| app → host | `ui/open-link` (request) | `{url}` | **默认拒绝**（安全默认），仅白名单内放行（对应 `app.openUrl`） | 阶段二默认拒绝 |
| app → host | `notifications/message` (notification) | `{level, message}` | 打印到 console/调试面板 | 阶段二 |
| app → host | `ui/notifications/size-changed` | `{width, height}` | P2：iframe 高度自适应 | P2 |
| host → app | `ui/resource-teardown` (request) | `{reason}` | 卸载前通知，等待响应后销毁（防数据丢失） | 阶段二 |

- **单例管理**：`Map<tcId, McpAppHost>`，reply 结束（AGENT_END）后执行 `ui/resource-teardown` 流程再卸载 host 实例（iframe 保留静态渲染）。
- **渲染架构（简化 vs 规范）**：规范要求 Web host 采用**双 iframe sandbox proxy**（外层代理 iframe 不同 origin：`allow-scripts allow-same-origin`，内层 View iframe；消息经 `ui/notifications/sandbox-proxy-ready` / `sandbox-resource-ready` 桥接）。
  阶段二先做**单 iframe 简化版**（`sandbox="allow-scripts"` 无 same-origin，View 与宿主同 origin 沙箱场景，功能等价、隔离略弱于规范），双 iframe 架构列为 P2。
- **安全**：CSP 由资源代理端点按 `_meta.ui.csp`/默认值下发；`ui/open-link` 一律拒绝并告警（P2 支持配置化白名单）。

**5.1.3 与 HITL 确认卡片的交互顺序**：
- **LLM 触发路径**：工具带 UI 且 `permissions.tools` 为 `ask` 时，先出确认卡片，批准后工具执行、结果到达后再渲染 MCP App 卡片（确认卡片与 App 卡片不重叠）。
- **UI 代理调用路径**：卡片内 `tools/call` 触发 `ask` 工具时，代理端点返回 `needsConfirm`，
  host 在卡片外弹出确认卡片（复用 `renderConfirmCard` 样式），用户 Approve 后重试调用。
  确认卡片与 App 卡片在同一 reply 气泡内分层展示（确认卡片在上，App 卡片在下）。

### 5.2 卡片 UI 规范（Debug 页 CSS，已实施）

并入 `static/debug/css/components.css`（`.mcp-apps-*` 前缀）：

- 卡片容器：边框圆角、折叠头（点击切换 `.open`）、`.mcp-apps-body` 默认收起、展开显示 iframe。
- 加载/错误态：`加载中…` 占位、失败显示 `.mcp-apps-error` + 重试按钮。
- iframe：宽度 100%，min-height 200px，max-height 60vh（P2：App 发 `ui/notifications/size-changed` 高度自适应）。

---

## 六、MCP App server 开发指南（示例，阶段三）

按官方脚手架（`create-mcp-app` skill / 手动 vite 配置）产出，部署为独立 Node 服务：

```bash
# 项目结构（官方模板，vite-plugin-singlefile 打包单文件 HTML）
my-mcp-app/
├── server.ts            # McpServer + registerAppTool + registerAppResource (express :3001/mcp)
├── mcp-app.html         # UI 入口
├── vite.config.ts       # viteSingleFile()
└── src/mcp-app.ts       # App class: connect() / ontoolresult / callServerTool
```

接入步骤：
1. `npm install && npm run build && npm run serve`（本机 3001）。
2. agent 的 `mcp-configs/{server}/config.yaml` 注册：
   ```yaml
   connection: { type: streamableHttp, url: "http://<node-host>:3001/mcp" }
   ui:
     tools:
       get_time: "ui://get-time/mcp-app.html"
   ```
3. `ActiveMCP.json` 勾选启用工具（沿用现有子集过滤）。
4. Debug 页对话触发工具 → 卡片渲染。

**示例优先做「get-time 时钟卡片」**（官方 demo 原样）验证全链路，再做业务卡片（如天气、订单状态表单）。

---

## 七、与现有机制的关系

| 机制 | 关系 | 说明 |
|------|------|------|
| A2UI（```a2ui``` 块） | **并存** | A2UI 是 LLM 生成声明式 UI（事件流文本解析，`A2uiService.java`）；MCP Apps 是 server 托管交互式 HTML。前端渲染器按「文本块含 a2ui 标记 → A2UI；工具调用带 ui 元数据 → MCP App 卡片」分流 |
| HITL 确认卡片 | 前置 | `ask` 工具先确认后渲染卡片（5.1.3） |
| `permissions.read_only` | 联动 | UI 代理调用沿用只读约束（4.4） |
| Channel/A2A 双链路 | 仅 Channel 受益 | A2A `message/stream` 标准帧无工具参数（`chat.js:1086` 注释已明确），卡片渲染依赖 Channel 链路词表；A2A 侧如需卡片需 `agent-card` 扩展协商（P2，SDK 未桥接，同 HITL 结论） |

---

## 八、分阶段实施计划

| 阶段 | 内容 | 产出 | 验证 |
|------|------|------|------|
| **一（后端）** | ToolInfo 扩展 + config.yaml `ui` 解析 + `_meta.ui` 自动发现兜底 + McpResourceProxy + 4 个端点 + SSE 词表扩展 + /tools 扩展 | 后端可被 curl 验证 | 单测 + 假 MCP server 集成测试 |
| **二（Debug 页）** | McpAppHost + 卡片渲染器 + CSS | Debug 页全链路卡片 | 手动 E2E：get-time 卡片 |
| **三（示例与打磨）** | 业务卡片示例、P2 项（能力协商〔mcp-core extensions 升级前置〕、`ui/message` 触发新回复、双 iframe sandbox proxy、resize、openUrl 白名单） | 示例 + 增强 | 回归 |

阶段一不依赖阶段二，可独立推进。

---

## 九、测试方案

1. **单元测试**（agent-framework，mock 不连网）：
   - `McpToolRegistrar`：config.yaml `ui` 解析（逐工具声明/app_only/csp/缺失回退）、ToolInfo 字段、
     `_meta.ui` 自动发现兜底（带 `meta={"ui":{"resourceUri":...}}` 的 Tool → uiSource=auto；config 声明优先）。
   - `McpResourceProxy`：scheme 白名单 + uri ∈ 静态声明集合校验、空参数、未注册 server/tool 拒绝、ask 工具拒绝。
   - 序列化：TOOL_CALL_START 带/不带 ui 两种词表；裸名冲突时不携带 ui 的降级行为。
2. **集成测试**（起假 MCP server，参考现有 MCP 测试先例）：
   - 假 server 提供带 `_meta.ui` 工具 + `ui://` 资源 → 资源代理返回 HTML（CSP 交集断言）；
     `_meta` 兼容性已 javap 排除（`@JsonIgnoreProperties(ignoreUnknown=true)`，见 §2.2）。
   - 工具调用代理：成功/失败/isError/只读拦截/ask 确认流（needsConfirm → 重试 with confirmed）。
3. **E2E（Debug 页手动 + e2e 目录 puppeteer，阶段三）**：
   - 发消息触发 UI 工具 → iframe 渲染（CSP meta 生效断言）→ 卡片按钮调工具 → 结果回流 → 静默更新上下文注入 agent 后续回复。

---

## 十、风险与开放问题

| # | 风险/问题 | 影响 | 应对 |
|---|----------|------|------|
| 1 | **MCP Apps 规范版本演进**（2026-01-26 Stable 已发布；draft 分支仍继续演进，协议细节可能变动） | 前端 JSON-RPC 方法/消息需跟进 | 实现对齐 Stable 2026-01-26 规范与官方 `basic-host` 参考实现，跟踪 draft 变更 |
| 2 | Java SDK 0.17.0 `ClientCapabilities` 无 `extensions` 字段无法做能力协商（server 侧可能不注册带 `_meta.ui` 的工具变体） | 自动发现不保证命中 | 静态声明为主路径（4.2）；自动发现仅兜底（4.2-2，`_meta` 已可读） |
| 3 | `McpClientWrapper` 不暴露 resources | 需独立连接 | McpResourceProxy 懒连接（4.3，`McpClient.sync()`）；MCP server 多连接是协议允许的 |
| 4 | iframe 安全（XSS/数据外泄） | 高 | `sandbox` 无 same-origin + CSP meta 注入（srcdoc 模式下唯一生效路径）+ `connect-src 'self'` 默认（仅允许同源代理）+ `ui/open-link` 默认拒绝 |
| 5 | 上下文更新的并发安全（ui/update-model-context 写入 agent_state 与 agent 读取的竞态） | 中 | 写入用 MySQL 悲观锁（SELECT FOR UPDATE）或乐观锁（version 字段）；agent 读取时取最新值；P2 ui/message 触发回复场景走 ChatUiChannel 队列化 |
| 6 | stdio 型 MCP server 的资源代理（进程级单连接） | 低 | stdio 用共享 client 而非独立连接（McpResourceProxy 对 stdio 复用 wrapper 或串行化） |
| 7 | 离线环境依赖（Node server 需 npm 包） | 低 | 卡片示例可在联网机器打包后传输；server 运行时依赖内网 npm 镜像 |
| 8 | ~~0.17.0 Jackson 对 Tool 顶层 `_meta` 未知字段兼容性~~ | ~~高~~ | ✅ **已排除**（javap 实测：`Tool` 类级 `@JsonIgnoreProperties(ignoreUnknown=true)`，未知字段静默丢弃，不抛异常；且 `_meta` 本身有 `meta()` 可读） |
| 9 | streamableHttp 双连接 session 语义（wrapper 连接 + 代理连接 = 两个 MCP session） | 低 | 多数 server 无 session 态差异；有状态 server 在接入文档中提示，必要时代理走复用连接（P2） |

---

## 附录 A：MCP Apps host 协议要点（实现依据）

- **协议版本**：Stable `specification/2026-01-26/apps.mdx`（draft 分支演进中）。
- 官方实现参考：`ext-apps/examples/basic-host`（npm start，SERVERS 环境变量指定 server，:8080）——本方案 host 的实现基准。
- **通信**：JSON-RPC over postMessage；`ui/initialize`、`ui/message`、`ui/update-model-context`、`ui/open-link` + 共享 `tools/call`（dialect 细节见规范 Communication Protocol 章节）。
- `App` class 客户端侧 API：`connect()` / `ontoolresult` / `callServerTool` / `sendMessage` / `updateModelContext` / `openUrl` / `log`
  （API 文档：https://apps.extensions.modelcontextprotocol.io/api/ ）。
- 资源 MIME：`RESOURCE_MIME_TYPE`（`@modelcontextprotocol/ext-apps` 导出）。
- 渲染：deny-by-default CSP + 沙箱 iframe（Patterns 文档：CSP 与 CORS 配置）。
- 能力协商：host 在 `initialize` 的 `capabilities.extensions["io.modelcontextprotocol/ui"]` 声明 UI 支持
  （`mimeTypes: ["text/html;profile=mcp-app"]`），server 按能力决定注册工具变体（SEP-1724 扩展机制，Java 0.17.0 不支持）。
