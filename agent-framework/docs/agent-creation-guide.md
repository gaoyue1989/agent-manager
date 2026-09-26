# 创建 Agent 完整指南

> **读者**：想在 agent-framework 上构建一个新 Agent 的开发者 / 配置包作者
> **目标**：从零到一个能跑起来、能被调用的 Agent，覆盖 OAF 包构建 → 测试环境部署 → 配置 → API 调用全流程
> **状态**：随代码同步维护 | 复核日期 2026-09-26（master @ `a263b92`）
>
> ✅ **本篇 §1 的示例已于 2026-09-26 端到端实跑验证**（JDK 21 + MySQL 8.0 + Redis 7 + e2e mock LLM/MCP）：
> 打包 → 启动 → `/health` → `/tools` 三段契约 → SSE 对话 → `session_created`/`AGENT_END` 终态 →
> `/status` 判态 → `/subscribe?afterSeq=` 续传 → `/admin/reload` 热加载 → **HITL `permission_ask` 全流程** →
> 三态权限直调（allow 放行 / ask 返回 403 `needsConfirm`）均与本文描述一致。
> §3–§6 的 curl 片段取自该次实跑输出。

本篇是**上手指南**，写法以「照着做就能跑」为第一原则。深入原理请转各专项文档（见文末 §9）。

---

## 目录

1. [5 分钟看到效果](#1-5-分钟看到效果)
2. [整体是怎么跑起来的](#2-整体是怎么跑起来的)
3. [OAF 包：Agent 的全部定义](#3-oaf-包agent-的全部定义)
4. [配置说明](#4-配置说明)
5. [在测试环境部署](#5-在测试环境部署)
6. [API 调用完整流程](#6-api-调用完整流程)
7. [框架能力地图](#7-框架能力地图)
8. [常见坑](#8-常见坑)
9. [延伸阅读](#9-延伸阅读)

---

## 1. 5 分钟看到效果

先跑通一个最小 Agent，再回头读原理。这一节的目标是：从零到「能对话、能调 MCP 工具、能人工确认」。

### 1.1 准备 OAF 包

新建目录，写两个文件：

```bash
mkdir -p /tmp/weather-agent/mcp-configs/weather
cd /tmp/weather-agent
```

**`/tmp/weather-agent/AGENTS.md`**

```markdown
---
name: "weather-agent"
vendorKey: "myorg"
agentKey: "weather-agent"
version: "1.0.0"
slug: "myorg/weather-agent"
description: "查询城市天气的智能助手"
author: "@myorg"
license: "MIT"

mcpServers:
  - vendor: "myorg"
    server: "weather"
    version: "1.0.0"
    configDir: "mcp-configs/weather"

config:
  permission:
    mode: default
    tools:
      get_weather: allow
      submit_report: ask
---

# 天气查询助手

你是天气查询助手。根据用户给出的城市名调用 MCP 工具获取天气数据，用中文简洁播报
（温度、天气状况、风力）。

## 工作规范

1. 用户未指定城市时必须先追问，禁止猜测。
2. 只允许调用 `get_weather` 查询；生成日报才允许 `submit_report`（会触发人工确认卡）。
3. 工具返回错误时如实告知用户，不要编造数据。
```

**`/tmp/weather-agent/mcp-configs/weather/config.yaml`**

```yaml
server: weather
vendor: myorg
version: "1.0.0"

connection:
  type: streamableHttp
  url: http://your-mcp-host:8080/mcp
  timeout: 60

permissions:
  tools:
    get_weather: allow
    submit_report: ask
```

此时目录长这样：

```
weather-agent/
├── AGENTS.md                    # 必需：Agent 清单 + system prompt
└── mcp-configs/
    └── weather/
        └── config.yaml          # MCP server 连接与权限配置
```

### 1.2 起依赖并运行

需要 Docker、MySQL 8、Redis、可用的 OpenAI 兼容 LLM 端点。

```bash
# 1) MySQL
docker run -d --name af-mysql -p 3307:3306 \
  -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=agent_manager_test \
  -e MYSQL_USER=agent_manager -e MYSQL_PASSWORD='Agent@Manager2026' mysql:8.0

# 2) Redis
docker run -d --name af-redis -p 6379:6379 \
  redis:7.2-alpine redis-server --appendonly yes --maxmemory-policy noeviction

# 3) 构建镜像
cd /root/agent-manager/agent-framework && make docker-build

# 4) 起服务
docker run -d --name agent-framework -p 8100:8100 \
  -e LLM_API_KEY=sk-xxx \
  -e LLM_MODEL_ID=your-model-id \
  -e LLM_BASE_URL=https://your-llm-endpoint/v1 \
  -e AGENT_CONFIG_DIR=/config \
  -e AGENT_REDIS_URL=redis://host.docker.internal:6379 \
  -e CHECKPOINT_JDBC_URL='jdbc:mysql://host.docker.internal:3307/agent_manager_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC' \
  -e CHECKPOINT_USERNAME=agent_manager -e CHECKPOINT_PASSWORD='Agent@Manager2026' \
  -v /tmp/weather-agent:/config \
  agent-framework:latest
```

> **`AGENT_REDIS_URL` 必须设。** 不设时默认指向容器内的 `localhost:6379`，对话仍然能发起，但事件不落库——断线回放、跨副本订阅、history 文件关联全部失效，且**不报错**。这是最常见的「静默半残」。

### 1.3 验证

```bash
curl http://localhost:8100/health
# {"status":"healthy","agent":"weather-agent","version":"1.0.0",...,"llm_configured":true}

curl 'http://localhost:8100/tools?includeInternal=true' | head -c 600
# 看 MCP 工具是否注册成功

docker logs -f agent-framework   # 工具没出来先看这里
```

### 1.4 对话

```bash
BASE=http://localhost:8100

# 建会话 → 首帧会回传真实 sessionId
curl -sN -X POST "$BASE/threads/chat" \
  -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
  -d '{"message":"北京今天天气怎么样？","userId":"alice"}'
```

输出形如：

```
data:{"type":"session_created","session_id":"b3f1c2d4-...."}

data:{"type":"AGENT_START","replyId":"r1","id":"evt-1"}
data:{"type":"MODEL_CALL_END","replyId":"r1","inputTokens":128,...}
data:{"type":"TOOL_CALL_START","replyId":"r1","toolName":"get_weather","toolCallId":"call-abc"}
data:{"type":"TOOL_RESULT_END","replyId":"r1","toolCallId":"call-abc","state":"SUCCESS"}
data:{"type":"TEXT_BLOCK_DELTA","delta":"北京今天晴，","replyId":"r1",...}
data:{"type":"AGENT_END","replyId":"r1","id":"evt-9"}
```

流关闭 = 本轮结束。取 `session_id` 继续下一轮：

```bash
SID="上一步 session_created 返回的 session_id"
curl -sN -X POST "$BASE/threads/chat" \
  -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
  -d "{\"message\":\"那上海呢\",\"sessionId\":\"$SID\",\"userId\":\"alice\"}"
```

到这里，一个能跑的 Agent 就完成了。接下来几节展开完整流程。

---

## 2. 整体是怎么跑起来的

理解这一节，后面的配置才不会踩坑。

```
                  ┌──────────────┐
   OAF 包 (zip)   │  AGENTS.md   │  ← Agent 的全部定义：frontmatter + system prompt
        │         │  skills/     │  ← 技能（运行时动态加载，不重启生效）
        │         │  mcp-configs/│  ← MCP server 连接 + 工具权限
        │         │  plugins/    │  ← 可选：Java SPI 工具插件（需重启）
        │         └──────────────┘
        │  平台把包 PVC subPath 只读挂载到容器 /config
        ▼
┌─────────────────────────────────────────────────────────┐
│  agent-framework (Spring Boot :8100)                    │
│                                                         │
│  OafConfigLoader  解析 AGENTS.md → OafConfig            │
│         │                                               │
│         ▼                                               │
│  HarnessAgentFactory  装配 ReActAgent                    │
│    ├── ChatModelFactory   ← 模型（来自环境变量 /models）  │
│    ├── 5 个 Middleware    ← OTel → IO Trace → 权限 → …  │
│    ├── McpToolRegistrar   ← 注册 MCP 工具（按三态权限）  │
│    ├── InternalToolRegistry ← 自定义工具（@Tool / 插件） │
│    └── Toolkit 里的 SDK 内置工具（文件/记忆/技能/…）      │
│         │                                               │
│         ▼                                               │
│  HarnessAgentRunner  每轮对话                            │
│    ├── L2 技能仓库：每轮重扫 /config/skills              │
│    ├── 记忆：MEMORY.md（10 分钟节流 flush）              │
│    ├── 上下文压缩：30 条触发保留 10 条                    │
│    └── Turn 租约（MySQL）→ 事件流（Redis Streams）       │
└─────────────────────────────────────────────────────────┘
        │                                    │
        │ REST + SSE                        │ A2A (JSON-RPC)
        ▼                                    ▼
   对话 / 文件 / 技能 / 模型 / 工具       POST /
```

**两个通道的区别很重要**：

| | 对话通道（`/threads/*`） | A2A 通道（`POST /`） |
|---|---|---|
| 协议 | REST + SSE | JSON-RPC 2.0（A2A） |
| 人工确认（HITL） | ✅ 支持 | ❌ **不支持** |
| 事件持久化 / 断线续传 | ✅ Redis Streams | ❌ 不写 `session_event` |
| Turn 租约互斥 | ✅ 跨 Pod 生效 | ❌ 不抢租约 |
| 适用场景 | 变更类操作、需要 UI 的场景 | 纯只读问答 |

> **需要人工确认的工具必须走对话通道。** A2A 通道没有 `confirm` 端点，模型挂起在 `ask` 上后客户端无法批准，且同会话后续请求会持续失败。

---

## 3. OAF 包：Agent 的全部定义

### 3.1 目录结构

OAF 包就是一个 **zip**，`AGENTS.md` 必须在压缩包根目录（不能在多套一层目录）。

```
weather-agent/                      # ← zip 内根目录
├── AGENTS.md                       # 【必需】唯一必需文件
├── skills/                         # 【可选】技能
│   └── <skill-name>/
│       ├── SKILL.md                #   必需：技能清单 + 说明
│       ├── resources/              #   可选：数据 / 附件
│       ├── scripts/                #   可选：可执行脚本
│       └── assets/                 #   可选：图片 / 模板
├── mcp-configs/                    # 【可选】MCP server 配置
│   └── <目录名>/
│       ├── config.yaml             #   必需：连接 + 认证 + 权限
│       └── ActiveMCP.json          #   可选：工具子集过滤
└── plugins/                        # 【可选】Java SPI 工具插件 *.jar（需重启）
```

**运行时真正读取哪些路径**：

| 路径 | 缺失后果 |
|---|---|
| `AGENTS.md` | **启动直接失败**（`IllegalStateException`） |
| `skills/<name>/SKILL.md` | 该技能不被发现，其他功能正常 |
| `mcp-configs/**/config.yaml` | 对应 MCP server 不注册（其他 server 正常） |
| `plugins/*.jar` | 插件工具不注册（框架正常启动） |

> `mcp-configs/` 不是硬性前缀。运行时解析规则是：`configDir` 字段优先 → 为空则用 `server` 名 → 解析出的目录不存在则回退到 `{AGENT_CONFIG_DIR}/{server}`。

### 3.2 `AGENTS.md`：清单 + system prompt

文件 = YAML frontmatter + Markdown 正文。**必须以 `---` 开头**且有第二段 `---`，否则整份文件被当成正文，frontmatter 视为空。

**必填字段**（平台上传门禁会校验，缺任意一个 → HTTP 400）：

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `name` | string | 非空；建议 kebab-case | Agent 显示名。**注意**：走发布助手 `create_oaf_zip` 打包时会被强制校验为 kebab（`^[a-z0-9]+(-[a-z0-9]+)*$`），写成 `"Release Agent"` 会被拒 |
| `vendorKey` | string | 非空，kebab-case | 供应商标识 |
| `agentKey` | string | 非空，kebab-case | Agent 标识 |
| `version` | string | 非空，semver | 建议不带 `+build` 元数据（平台侧正则不接受） |
| `description` | string | 非空 | 描述 |
| `author` | string | 非空 | 形如 `@myorg` |
| `license` | string | 非空 | 如 `MIT` |

**常用可选字段**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `slug` | string | 规范列为必填，但平台**会自动派生** `vendorKey/agentKey`，缺省不会报错 |
| `tags` | list | 标签 |
| `mcpServers[]` | list | MCP server 声明，字段见下 |
| `skills[]` | list | 技能声明 |
| `tools[]` | list[string] | **声明意图**（不是存在性开关），配合 `deniedTools` 剔除工具 |
| `deniedTools[]` | list[string] | 声明式隐藏，类粒度剔除，且从 `/tools` 的 internal 段中消失 |
| `config` | object | 权限与运行时配置，见 §4.1 |
| `memory` | object | 记忆配置 |

**`mcpServers[]` 字段**：`vendor` / `server`（必填）/ `version`（默认 `1.0.0`）/ `configDir` / `required`。

> `required: true` **不控制启动成败**，只是回显声明。真正决定「MCP 连不上是否让 Agent 起不来」的是 `config.yaml` 里的 `startup.required`。

**`skills[]` 字段**：`name` / `source`（默认 `local`）/ `version` / `required` / `description` / `allowed-tools`（**空格分隔的字符串**）。

> `allowed-tools` 只支持字符串写法。写成 YAML 列表或驼峰 `allowedTools` 会被**静默忽略**。

**正文（Markdown）= system prompt**，无结构约束。建议写清角色、工作规范、工具使用边界、输出格式。

### 3.3 `skills/<name>/SKILL.md`：技能

```markdown
---
name: weather-report
description: 生成天气日报的标准格式与步骤：查询 → 核对 → 播报。必须按此格式输出。
version: 1.0.0
---

# 天气日报规范

1. 先调用 `get_weather` 取数。
2. 输出固定三行：日期 / 温度 / 天气状况。
3. 数据缺失时写「暂无数据」，禁止编造。
```

**关键特性：动态加载。** `/config/skills` 被注册为 L2 技能市场仓库，**每轮对话重扫一次，不需要重启**。往包里丢一个新技能目录，下一轮对话就能用 `@技能名` 引用。

`description` 字段是运行时真正消费的——它决定模型什么时候会主动选用这个技能。写清楚「什么场景该用」，比写清楚「这个技能是什么」更重要。

### 3.4 `mcp-configs/<server>/config.yaml`

```yaml
server: weather              # 可选（仅日志与 /mcp 列表展示）
vendor: myorg                # 可选
version: "1.0.0"             # 可选

connection:                  # 【必需】缺整段 → 该 server 不注册
  type: streamableHttp       # sse（默认）| streamableHttp | http（别名）| stdio
  url: http://host:8080/mcp  # sse/streamableHttp 用
  command: /path/to/bin      # stdio 用
  args: ["--flag"]           # stdio 用
  timeout: 60                # 见下方说明

auth:                        # 可选
  token: ${MCP_TOKEN}        # 支持 ${ENV_VAR} 替换 → Authorization: Bearer <token>

userHeaders:                 # 可选，多租户按用户注入
  headers:
    X-User-Id: userId        # header 名 → McpMeta key
  on-missing: deny           # deny（默认，fail-closed）| passthrough

permissions:                 # 可选
  read_only: true            # 强制只读注册 —— ⚠️ 会短路 HITL，见下
  tools:                     # 工具级三态，键 = 远端工具裸名
    get_weather: allow
    submit_report: ask
    delete_weather: deny

ui:                          # 可选，MCP Apps 卡片
  tools:
    show_form: "ui://approval/form.html"   # 必须 ui:// scheme
  app_only:
    confirm_form: "ui://approval/form.html"
  csp:
    connect_domains: ["https://api.example.com"]

startup:                     # 可选
  required: true             # 默认 false = fail-soft（连不上只告警跳过）
```

> **`connection.timeout` 当前不生效。** 字段会被解析，但实际的 LLM / HTTP 超时由 `AGENT_HTTP_*_TIMEOUT_SECONDS` 决定。

**`ActiveMCP.json`（工具子集过滤）**——只这两个字段被读取：

```json
{
  "selectedTools": [
    { "name": "get_weather", "enabled": true },
    { "name": "submit_report", "enabled": false }
  ]
}
```

`enabled: false` 的工具**不会注册到 Toolkit**，模型根本看不到。

> ⚠️ **绝不要在同一个 server 上同时开 `permissions.read_only: true` 或 `ui.app_only` 和 `ask` 权限。** 前两者会走 `registerReadOnly` 路径，`readOnly=true` 会短路 HITL，让人工确认**静默失效**——工具直接执行，不再询问。

### 3.5 `plugins/`：Java SPI 工具插件（可选）

需要写 Java 代码实现 `ToolPlugin` 接口，打成 jar 放进 `plugins/`。**需重启加载**（JVM 类卸载限制）。

用途：把自研工具以插件形式接入，无需改框架、无需重新编译框架包。详见 [tool-plugin-extension-plan.md](tool-plugin-extension-plan.md)。

### 3.6 打包

没有专门的打包脚本，**就是普通 zip**：

```bash
cd /tmp/weather-agent
zip -r weather-agent.zip AGENTS.md skills mcp-configs
```

**平台侧的三层校验**（上传时自动执行，不需要额外跑脚本）：

| 层 | 校验内容 | 失败 |
|---|---|---|
| 包结构 | 根级必须有 `AGENTS.md`、≤20MB、≤2000 条目、拒绝路径逃逸与符号链接 | 400 |
| frontmatter | 7 个必填字段非空、`vendorKey`/`agentKey` kebab、`version` semver | 400 |
| MCP 目录 | 声明的 `configDir` 存在性（仅告警） | 继续启动 |

### 3.7 参考样例

仓库里有三份可直接照抄的完整包：

| 样例 | 路径 | 覆盖了什么 |
|---|---|---|
| 发布助手 | `release-agent/` | 单 MCP + 技能 + 权限 mode |
| E2E 测试包 | `agent-framework/e2e/fixtures/agent-config/` | 4 个 MCP server：`ask` 权限、`deny` 语义、工具子集过滤、UI 卡片 |
| 审批 Demo | `agent-framework/example/approval-forms/agent-config/` | HITL 确认流的最小实现 |

---

## 4. 配置说明

配置分三层，**优先级从低到高**：包内 `AGENTS.md` < 环境变量 < 运行时 API（`/models`、`/admin/reload`）。

### 4.1 AGENTS.md 的 `config` 块

```yaml
config:
  require_confirmation: false        # MCP 工具的兜底：true → 未声明工具一律 ask
  permission:
    mode: default                    # default | accept_edits | explore | bypass | dont_ask
    tools:                           # 工具级三态：allow / ask / deny
      write_file: ask
      execute: deny
      read_file: allow
```

**`permission` 只解析 5 个键**：`temperature`、`max_tokens`、`require_confirmation`、`permission.mode`、`permission.tools`。

> ⚠️ **`config.temperature` / `config.max_tokens` / `model` 都不影响实际的 LLM 调用。** 它们只会被回显到生成的 workspace AGENTS.md 以及 `/debug/config`、`/metadata` 里。真正决定对话模型的是环境变量 `LLM_MODEL_ID`，或通过 `/models` 建托管模型后用会话级 `model` 参数切换。详见 §4.4。

**权限三态的完整规则**：

| 工具类别 | 在哪声明 | 未声明时默认 |
|---|---|---|
| MCP 工具 | `config.yaml` → `permissions.tools` | `require_confirmation: true` → **ask**；否则 **allow** |
| 自定义工具（`@Tool`）+ SDK 内置工具 | `AGENTS.md` → `config.permission.tools` | **allow**（不参与确认） |
| 全局兜底 | `AGENTS.md` → `config.permission.mode` | `default` |

评估顺序：**Deny → Ask → Allow → mode 兜底**。规则是精确工具名映射，**无通配符**。MCP 显式规则优先于 frontmatter 同名声明。

**HITL 链路的两个硬约束**：
1. 走对话通道（`/threads/chat` + `/confirm-stream`），A2A 通道不支持
2. 目标 server 不能开 `read_only` / `app_only`

### 4.2 沙箱开关的三层裁决

`config.sandbox.enabled`（包内）优先级最低：

1. 环境变量 `SANDBOX_ENABLED` **实际存在** → 用它（K8s 注入的 env 能正确压过包声明）
2. 否则读包内 `config.sandbox.enabled`
3. 否则用默认 `false`

```yaml
config:
  sandbox:
    enabled: true
```

沙箱模式下 `/config` 仍只读，可写区是容器内 `/workspace`。

### 4.3 核心环境变量

**LLM（必填三项）**

| 变量 | 默认 | 说明 |
|---|---|---|
| `LLM_API_KEY` | 空 | **必填** |
| `LLM_MODEL_ID` | 空 | **必填**，实际对话模型 |
| `LLM_BASE_URL` | 空 | **必填**，OpenAI 兼容端点 |
| `LLM_TEMPERATURE` | `0.3` | 生成温度 |
| `LLM_MAX_TOKENS` | `16384` | 单次生成上限 |
| `LLM_TIMEOUT` | `120` | 秒 |
| `LLM_ENABLE_THINKING` | `false` | `false` → 注入 `chat_template_kwargs.enable_thinking=false`（Qwen3 / vLLM） |
| `LLM_CONTEXT_LENGTH` | `0` | ≤0 不传给模型 |

**存储（集群部署必配）**

| 变量 | 默认 | 说明 |
|---|---|---|
| `AGENT_REDIS_URL` | `redis://127.0.0.1:6379` | **集群必配**，否则事件不落库 |
| `CHECKPOINT_JDBC_URL` | `jdbc:mysql://127.0.0.1:3307/agent_manager_test` | 用户需 `CREATE TABLE` 权限 |
| `CHECKPOINT_USERNAME` / `_PASSWORD` | `agent_manager` / `Agent@Manager2026` | |

**运行时**

| 变量 | 默认 | 说明 |
|---|---|---|
| `AGENT_CONFIG_DIR` | `/config` | OAF 包目录（只读） |
| `AGENT_WORKSPACE_DIR` | 回落 `AGENT_CONFIG_DIR` | **平台部署必须显式设 `/workspace`**，configDir 是只读的 |
| `AGENT_PLUGINS_DIR` | `{AGENT_CONFIG_DIR}/plugins` | 工具插件目录 |
| `AGENT_MEMORY_ENABLED` | `true` | `false` = 完全关闭记忆（不注册 `memory_*` 工具、不 flush / 整合、沙箱不注入回写） |
| `AGENT_REACT_MAX_ITERS` | `20` | ReAct 最大轮数，长流程需放宽 |
| `AGENT_COMPACTION_TRIGGER_MESSAGES` | `30` | 压缩触发条数 |
| `AGENT_COMPACTION_KEEP_MESSAGES` | `10` | 压缩保留条数 |
| `FILE_STORAGE_LOCAL_DIR` | `/data/files` | 本地文件存储目录 |
| `FILE_EXTERNAL_URL_PREFIXES` | 空（禁用） | `present_url` 外部交付白名单，防 SSRF |

全量变量表见 [agent-framework-deploy.md](agent-framework-deploy.md) §4.1。

### 4.4 换模型的三个途径

| 途径 | 生效范围 | 做法 |
|---|---|---|
| **系统模型** | 全部会话 | 改环境变量 `LLM_MODEL_ID` 等，重启 |
| **托管模型** | 指定会话 | `POST /models` 建模型 → 会话级 `model` 参数绑定 |
| **包内声明** | ❌ 无效 | AGENTS.md 的 `model` 字段不参与 LLM 调用 |

系统模型还额外负责：**会话标题生成**、**记忆 flush / 整合**、**上下文压缩**——这三个内部任务不跟随会话选择的模型。

会话级切换：

```bash
# 建会话时指定
POST /threads/chat  {"message":"...","model":"my-model-id"}

# 会话中途切换 / 重命名
PATCH /threads/{sessionId}  {"model":"another-model-id"}
```

`model` 传 `""` 或 `"system"` 清除覆盖、回到默认；未知或已停用的模型会返回 `unknown_model` 错误帧。

### 4.5 OAF 包热加载

包内容原地更新后，不必重启：

```bash
# 自动分流：只有 MCP 配置变 → 原地 reload；AGENTS.md 变 → 整包重建 Agent
curl -X POST "$BASE/admin/reload?scope=auto"

# 精准单 server
curl -X POST "$BASE/admin/reload?scope=mcp&server=weather"

# 强制整包重建
curl -X POST "$BASE/admin/reload?scope=agent"

# 只读状态
curl "$BASE/admin/reload"
```

- **生效边界：下一轮对话。** 进行中的 turn 不打断。
- 失败时**保留旧配置**继续服务，返回 500 + `"old configuration remains active"`。
- 技能目录走每轮重扫，不走 reload。
- 该端点**无鉴权**，部署时依赖集群内网入口保护。

---

## 5. 在测试环境部署

三种方式，按「从轻到重」排列。

### 5.1 方式 A：本地 Docker（最快，适合调 OAF 包）

见 §1.2。适合：改 `AGENTS.md`、调技能、调 MCP 配置。

不用 Docker 的纯 jar 方式：

```bash
cd /root/agent-manager/agent-framework
mvn clean package -DskipTests
AGENT_CONFIG_DIR=/tmp/weather-agent \
LLM_API_KEY=sk-xxx LLM_MODEL_ID=xxx LLM_BASE_URL=https://xxx/v1 \
AGENT_REDIS_URL=redis://127.0.0.1:6379 \
CHECKPOINT_JDBC_URL='jdbc:mysql://127.0.0.1:3307/agent_manager_test?...' \
java -jar target/agent-framework-*.jar
```

> `AGENT_WORKSPACE_DIR` 本地可以不设（会回落到 `AGENT_CONFIG_DIR`）。但**集群部署必须显式设为 `/workspace`**，否则框架会尝试往只读的 `/config` 写生成文件。

### 5.2 方式 B：E2E 脚本（带 mock，不花 LLM 钱）

适合回归验证，不消耗真实 LLM 额度。

```bash
cd /root/agent-manager/agent-framework
mvn clean package -DskipTests          # 必须先出 jar

# 起 MySQL / Redis（本地端口 13306 / 16379）
./e2e/scripts/local-infra.sh

export MYSQL_URL='jdbc:mysql://127.0.0.1:13306/agent_framework_e2e'
export MYSQL_USER=e2e MYSQL_PASS=e2e-pass
export REDIS_URL='redis://127.0.0.1:16379'

# 一键跑全套
E2E_GROUP=core ./e2e/scripts/run.sh       # core | multi | sandbox
```

`env-up.sh` 会：重置数据 → 清端口 → 复制 `e2e/fixtures/agent-config` 到 `.runtime/agent-config` → 起 mock LLM / mock MCP / mock 沙箱 → 写 `.runtime/env.json`。

**要把自己改的 OAF 包换进去**，直接替换 `.runtime/agent-config` 的内容后重跑，或改 `env-up.sh` 里的复制源。

### 5.3 方式 C：走平台发布（Kind 集群，最接近生产）

完整部署见 [../../docs/deployment.md](../../docs/deployment.md)。

```bash
BASE=http://localhost:30080/api/v1

# ① 上传包
curl -X POST $BASE/packages -F "file=@weather-agent.zip"

# ② 发布服务
curl -X POST $BASE/services -H 'Content-Type: application/json' -d '{
  "packageId": 1,
  "name": "weather-agent",
  "image": "172.20.0.1:5001/agent-framework:latest",
  "replicas": 1,
  "env": {
    "LLM_API_KEY": "sk-xxx",
    "LLM_MODEL_ID": "your-model-id",
    "LLM_BASE_URL": "https://your-endpoint/v1",
    "AGENT_REDIS_URL": "redis://oaf-redis.agent-platform.svc.cluster.local:6379",
    "CHECKPOINT_JDBC_URL": "jdbc:mysql://oaf-mysql.agent-platform.svc.cluster.local:3306/oaf_checkpoint?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
    "CHECKPOINT_USERNAME": "oaf",
    "CHECKPOINT_PASSWORD": "OafPlatform2026"
  }}'

# ③ 轮询到终态
curl $BASE/services/1
# created → deploying → running | register_failed | deploy_failed
```

**平台强制注入、用户不可覆盖的保留键**（冲突直接 400）：

`AGENT_CONFIG_DIR`（强制 `/config`）、`AGENT_WORKSPACE_DIR`（强制 `/workspace`）、`SERVER_HOST`（`0.0.0.0`）、`SERVER_PORT`（`8100`）、`HOST_NAME`（Pod 名）。

**平台自动挂载**：OAF 包 → `/config` 只读；`/workspace` emptyDir 可写；`/data/files` → 共享 PVC 的 `files/`。

**env 是全量覆盖语义**（`PATCH /services/:id/env` 会整体替换），上限 64 键 × 32KB。镜像必须在 `AVAILABLE_IMAGES` 白名单内。

服务就绪后平台自动调 A2A `/.well-known/agent-card.json` 注册服务信息。

---

## 6. API 调用完整流程

服务默认监听 `:8100`。以下是**对话通道**的完整流程，A2A 通道见 §6.7。

### 6.1 最小调用序列

```bash
BASE=http://localhost:8100

# 1. 建会话 → 取 session_created 帧里的 session_id
curl -sN -X POST "$BASE/threads/chat" \
  -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
  -d '{"message":"你好","userId":"alice"}'

# 2. 续接同一会话
SID="<上一步返回的 session_id>"
curl -sN -X POST "$BASE/threads/chat" \
  -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
  -d "{\"message\":\"继续\",\"sessionId\":\"$SID\",\"userId\":\"alice\"}"

# 3. 查状态（刷新恢复用）
curl -s "$BASE/threads/$SID/status"

# 4. 查历史
curl -s "$BASE/threads/$SID/history"
```

> **务必使用 `session_created` 帧回传的 `session_id`。** 你传入的 `sessionId` 会经过 `PathSafe` 清洗（`:` → `_`，截断 64 字符），服务端返回值才是真实 ID。`{tenant}:{thread}` 格式是 AgentState 内部 key，不是对外协议。

### 6.2 SSE 帧格式

```
id: 42                                            ← 游标（seq），只在事件帧上有
data: {"type":"TEXT_BLOCK_DELTA","replyId":"r1","delta":"你好",...}
```

- **`id:` 行才是游标。** `data` 里的 JSON **没有** `seq` 字段（`data.id` 是 SDK 的事件 ID，两者不是一回事）。
- 控制帧（`session_created` / `waiting` / `error` / `done` / `interrupted`）和心跳 `:hb` **没有 `id:` 行**。

### 6.3 事件类型

| 事件 | 关键字段 | 说明 |
|---|---|---|
| `session_created` | `session_id` | 仅新会话时的首帧 |
| `waiting` | — | 排队等待 Turn 租约，每 15s 一帧，最多等 120s |
| `AGENT_START` / `AGENT_END` | `replyId` | **`AGENT_END` 后流直接关闭，没有 `done` 帧** |
| `TEXT_BLOCK_DELTA` | `delta`, `replyId`, `blockId` | 文本增量 |
| `THINKING_BLOCK_DELTA` | `delta`, `replyId`, `blockId` | 深度思考增量 |
| `MODEL_CALL_END` | `inputTokens`, `outputTokens`, `totalTokens` | usage 为 null 时三字段缺省 |
| `TOOL_CALL_START` | `toolName`, `toolCallId` | 命中 MCP App 时附 `ui:{resourceUri,server}` |
| `TOOL_CALL_DELTA` / `_END` | `toolCallId` | 入参增量 |
| `TOOL_RESULT_START` / `TOOL_RESULT_TEXT_DELTA` | `toolCallId` | 结果流式 |
| `TOOL_RESULT_DATA_DELTA` | `tool_call_id`, `media_type` + `data`/`url` | ⚠️ **字段是 snake_case**，与其他工具事件不一致 |
| `TOOL_RESULT_END` | `state` | `SUCCESS` / `ERROR` / `INTERRUPTED` / `DENIED` / `RUNNING` |
| `tool_call_summary` | `summary`, `toolCallId`, `toolName` | 合成帧：工具调用中文摘要 |
| `tool_result_preview` | `preview`, `toolCallId`, `toolName` | 合成帧：结果预览 |
| `permission_ask` | `tool_calls[]`, `reply_id` | **人工确认**，见 §6.5 |
| `file_ready` | `file_id`, `file_name`, `download_url` | 合成帧，文件产出 |
| `error` | `error` | 错误帧 |
| `interrupted` | `reason:"turn_interrupted"` | 仅 `/subscribe` 补发 |

完整词表见 [api-frontend-sse.md](api-frontend-sse.md) §9。

### 6.4 断线重连与多副本

**断线不会杀死服务端任务**，换 Pod 重连照样能拿到后续事件。

```bash
# afterSeq = 最后一个收到的 SSE id: 值
LAST=42
curl -sN -G "$BASE/threads/$SID/subscribe" \
  --data-urlencode "afterSeq=$LAST" -H 'Accept: text/event-stream'

# 只关心某一个 turn
curl -sN -G "$BASE/threads/$SID/subscribe" \
  --data-urlencode "afterSeq=$LAST" --data-urlencode "replyId=r1"
```

> **`/subscribe` 不认 `Last-Event-ID` 请求头。** 浏览器原生 `EventSource` 的自动重连对本 API 无效——必须用 `fetch` + `ReadableStream`，或自建 EventSource 并把最后的 `id` 显式拼进 `afterSeq`。

**页面刷新恢复的标准做法**：

```
GET /threads/{sid}/status
  → state=working / waiting_confirm  → 走 /subscribe?afterSeq={latest_event_seq}
  → state=completed                  → 直接回放历史
  → state=interrupted                → 执行副本崩溃了，展示中断态
  → 503 event_store_unavailable      → 保留本地游标，不要重置为 0
```

**多副本部署的前提**：事件流存 Redis Streams，租约与状态存 MySQL。**没有共享 Redis 就不可能多副本**——每个 Pod 只能看到自己执行的那次请求的事件。

### 6.5 人工确认（HITL）完整流程

> **实测提示（2026-09-26 验证）**：直连 `:8100` 调试时，如果 `sessionId` 是不带命名空间前缀的裸 UUID，
> MCP Apps 的静默上下文注入会跳过并打 WARN：`invalid sessionId format, expected 'tenant:thread' or 'tenant_thread'`。
> 这不影响对话与 HITL（实测 HITL 全流程正常），但卡片类功能会静默失效。
> 平台部署下会话 ID 自带 `{vendorKey}/{agentKey}` 前缀，不会有此问题。


```
① POST /threads/chat {"message":"把报告提交了"}
   ← data:{"type":"permission_ask",
           "tool_calls":[{"tool_call_id":"call-abc","name":"submit_report","input":{...}}],
           "reply_id":"r1"}
   ← 流关闭

② 前端渲染确认卡片
   （也可用 GET /threads/{sid}/history 的 pendingConfirm 字段重建）

③ 确认 / 拒绝
   POST /threads/{sid}/confirm-stream
   {"results":[{"tool_call_id":"call-abc","confirmed":true}]}

   ← data:{"type":"AGENT_START","replyId":"r2"}
   ← data:{"type":"TOOL_RESULT_START",...}
   ← data:{"type":"tool_call_summary","summary":"执行 submit_report",...}   ← 恢复段兜底补发
   ← data:{"type":"TEXT_BLOCK_DELTA","delta":"已提交"}
   ← data:{"type":"AGENT_END"}
```

也可以用同步版本 `POST /threads/{sid}/confirm`，直接返回 `{"response":"...","thread_id":"..."}`，**不产出中间事件**——需要流式进度就用 `confirm-stream`。

要点：
- `tool_call_id` **必须**来自 `permission_ask` 帧（或 `/history` 的 `pendingConfirm`），服务端据此查原始工具入参
- 同一会话同一时刻只允许一个 turn 处于 `permission_ask` 挂起态，此时发新消息会被拒
- 错误码：404 `confirm_context_not_found` / 409 `confirm_already_consumed` / 409 `turn_in_progress`

### 6.6 文件上传下载

```bash
# 上传（multipart 字段名固定为 file）
curl -X POST "$BASE/files/upload" \
  -F "file=@report.pdf" -F "userId=alice" -F "sessionId=$SID"

# 下载 / 内联预览
curl -OJ "$BASE/files/<file_id>"
curl     "$BASE/files/<file_id>?inline=1"      # 仅 image/* 与 text/* 生效
```

- 默认 MIME 白名单含 `application/zip`（为了能上传 OAF 包）
- 扩展名 × MIME 交叉校验，`exe`/`bat`/`sh` 类危险扩展名一律拒绝
- 模型调 `present_file` / `present_url` 产出文件后，会合成 `file_ready` 帧
- `download_url` **恒为相对路径** `/files/{id}`，前端需自己拼 Base URL

### 6.7 A2A 通道

```bash
curl "$BASE/.well-known/agent-card.json"

# JSON-RPC 2.0
curl -X POST "$BASE/" -H 'Content-Type: application/json' -d '{
  "jsonrpc":"2.0","id":"1","method":"message/send",
  "params":{"message":{"role":"user","parts":[{"kind":"text","text":"你好"}],
                       "metadata":{"userId":"alice","sessionId":"t1"}}}}'
```

支持 `message/send`、`message/stream`、`tasks/get`、`tasks/cancel`、`tasks/resubscribe` 等，由 SDK 全量透传。

**差异（再强调一次）**：无 HITL 批准通道、不写事件流、不抢 Turn 租约。**变更类操作一律走对话通道。**

### 6.8 其他常用端点

```bash
# 工具与 MCP
GET  /tools?includeInternal=true     # MCP + 自定义 + SDK 内置三段
GET  /mcp                            # 已注册的 MCP server 摘要

# 技能
GET  /skills                         # 已启用技能
GET  /skills/available               # @ 补全候选（按 X-User-Id 合并个人技能）

# 模型
GET  /models                         # 托管模型列表
POST /models                         # 新建托管模型
POST /models/{id}/test               # 连通性测试

# 会话
GET    /threads                      # 列表
GET    /threads/{sid}                # 详情
PATCH  /threads/{sid}                # 重命名 / 换模型
DELETE /threads/{sid}                # 级联删除

# 运维
GET  /health
GET  /metadata
POST /admin/reload?scope=auto
```

`/tools?includeInternal=true` 返回三段：

| 段 | 位置 | `category` | 内容 |
|---|---|---|---|
| MCP 工具 | `tools[]` | `mcp` | 业务工具，带 `server` |
| 自定义工具 | `tools[]` | `internal` | `@Tool` + 插件，`declared` 标注是否在 OAF 声明列表 |
| SDK 内置 | `sdkInternal[]` | `sdk` | Harness 自注册（文件/记忆/技能/计划/…） |

完整字段见 [api.md](api.md)。

### 6.9 Debug 控制台

浏览器打开 `http://localhost:8100/debug/`，可查看脱敏后的 env、OAF frontmatter、数据库状态、记忆文件、用户技能、sandbox 配置、workspace 文件和实时日志。集群部署时经 ingress 访问会带 `X-Forwarded-Prefix`。

---

## 7. 框架能力地图

构建 Agent 时可以用的能力，按是否需要重启生效分两类。

| 能力 | 说明 | 生效 |
|---|---|---|
| **MCP 工具接入** | 标准协议，`config.yaml` 配连接 | reload |
| **工具权限三态** | allow / ask / deny，ask 走人工确认 | reload |
| **MCP Apps 卡片** | `ui://` 资源渲染富交互卡片 | reload |
| **多租户 userHeaders** | 按用户注入 header / `_meta` | reload |
| **技能（Skill）** | `/config/skills` 每轮重扫 | **下一轮对话** |
| **记忆** | `MEMORY.md` + `memory/`，10 分钟节流 | 自动 |
| **上下文压缩** | 30 条触发保留 10 条 | 自动 |
| **用户个人技能（L4）** | 用户级覆盖，跨会话持久 | **下一轮对话** |
| **沙箱执行** | OpenSandbox 容器内跑 shell / 文件操作 | restart |
| **自定义工具插件** | Java SPI，jar 放 `plugins/` | **需重启** |
| **会话级模型切换** | `/models` 托管模型 + 会话 `model` 参数 | 即时 |
| **会话标题自动生成** | 首条消息后异步生成中文标题 | 自动 |
| **工具调用中文摘要** | `tool_call_summary` / `tool_result_preview` | 自动 |
| **OAF 动态 reload** | 包内容原地更新 | **下一轮对话** |
| **链路追踪** | OTel span + 模型/工具 IO 内容属性 | 即时 |
| **A2A 通道** | JSON-RPC，第三方 Agent 可直接调 | — |

---

## 8. 常见坑

按危害从大到小。

### 8.1 改了配置但没生效

**① `AGENT_REDIS_URL` 没设**
对话能发起、看起来一切正常，但事件不落库——断线回放、跨副本订阅、history 文件关联全部失效，且**不报错**。集群部署必配。

**② 指望 `AGENTS.md` 里的 `model` 换模型**
不生效。必须设环境变量 `LLM_MODEL_ID`，或用 `/models` 建托管模型后传会话级 `model` 参数。同理 `config.temperature` / `config.max_tokens` 也不影响实际调用。

**③ `permissions.read_only: true` 让人工确认静默失效**
`read_only` 会短路 HITL，工具直接执行不再询问。**需要确认的工具，其 server 不能开 `read_only` 或 `ui.app_only`。** 这类失败没有任何报错，最危险。

**④ `McpToolRegistrar` 不消费 `connection.timeout`**
设了 `timeout: 300` 不会有任何效果，实际超时走 `AGENT_HTTP_*_TIMEOUT_SECONDS`。

**⑤ 技能名 / `allowed-tools` 写法不对**
`allowed-tools` 只认**空格分隔的字符串**；写成 YAML 列表或驼峰 `allowedTools` 会被静默忽略。技能名不满足 kebab 约束只打 WARNING 不阻断。

**⑥ `SANDBOX_ENTRYPOINT` 环境变量不生效**
`application.yml` 里没有对应占位符，只有 `@DefaultValue`。要覆盖得用 `AGENT_SANDBOX_ENTRYPOINT`（Spring 松弛绑定）。

### 8.2 对话与流

**⑦ 拿 `data.seq` 当游标**
游标在 **SSE `id:` 行**。`data` JSON 里没有 `seq` 字段，照文档示例写 `if (event.seq) lastSeq = event.seq` 会让续传恒为 0、每次整场重放。

**⑧ 用原生 `EventSource` 做重连**
`/subscribe` 不认 `Last-Event-ID` 头，原生自动重连不带游标。必须用 `fetch` + `ReadableStream`，或自建 EventSource 手动拼 `afterSeq`。

**⑨ 看到 `done` 帧当成对话结束**
`POST /threads/chat` **不发** `done` 帧，收到 `AGENT_END` 后流直接关闭。`done` 只有 `/subscribe` 追到终态时才补。

**⑩ 忽略 `TOOL_RESULT_DATA_DELTA` 的 snake_case**
它的字段是 `tool_call_id` / `tool_call_name` / `media_type`，同组其他事件都是 camelCase，客户端需要特殊处理。

**⑪ `TOOL_RESULT_END.state` 枚举值**
实际是 `SUCCESS` / `ERROR` / `INTERRUPTED` / `DENIED` / `RUNNING`，不是某些旧文档里写的 `COMPLETE`。

**⑫ `permission_ask` 挂起期间发新消息**
会被 SDK 会话级守卫拒绝。先 confirm 再发下一轮。

### 8.3 打包与平台

**⑬ zip 里多套了一层目录**
`AGENTS.md` 必须在压缩包根目录。

**⑭ `name` 写成 `"Release Agent"`**
平台直传 zip 不会校验，但走发布助手 `create_oaf_zip` 打包会被拒（强制 kebab）。**统一写 kebab 最安全。**

**⑮ 平台部署忘了 `AGENT_WORKSPACE_DIR`**
它是保留键，平台会自动注入 `/workspace`。自建部署时若不设，框架会尝试往只读的 `/config` 写生成文件。

**⑯ 调 `PATCH /services/:id/env` 以为是增量**
是**全量覆盖**，没传的键会被清掉。

**⑰ 自定义 sessionId 用了冒号**
`{tenant}:{thread}` 会被 `PathSafe` 清洗成 `{tenant}_{thread}` 并截断 64 字符。以 `session_created` 帧的返回值为准。

---

## 9. 延伸阅读

| 主题 | 文档 |
|---|---|
| 平台侧 OAF 规范 | [../../docs/oaf-specification.md](../../docs/oaf-specification.md) |
| 部署手册（全量环境变量） | [agent-framework-deploy.md](agent-framework-deploy.md) |
| REST API 全量参考 | [api.md](api.md) |
| 前端对接全量文档 | [api-frontend-sse.md](api-frontend-sse.md) |
| 会话 API 契约 | [api-thread-spec.md](api-thread-spec.md) |
| 框架总体设计 | [agent-framework-design.md](agent-framework-design.md) |
| OAF 动态加载与 MCP reload | [oaf-dynamic-reload-plan.md](oaf-dynamic-reload-plan.md) |
| HITL 权限系统 | [hitl-permission-plan.md](hitl-permission-plan.md) |
| 自定义工具插件 | [tool-plugin-extension-plan.md](tool-plugin-extension-plan.md) |
| 技能动态加载 | [oaf-skills-dynamic-loading-plan.md](oaf-skills-dynamic-loading-plan.md) |
| 测试与 E2E | [agent-framework-test.md](agent-framework-test.md) / [e2e-ci-plan.md](e2e-ci-plan.md) |
| Debug 能力 | <http://localhost:8100/debug/> |
