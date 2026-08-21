# Debug 页面重构计划（对齐 AgentScope 官方前端）

> **⚠️ 此文档描述的长连接 SSE 架构已被 stateless-single-stream-plan.md 取代**
> 新架构改为 POST /threads/{sid}/chat 单次流（SSE 直吐，执行完即关闭），删除了 GET /threads/{sid}/events 长连接订阅端点和 SessionEventBus。请参考 [stateless-single-stream-plan.md](stateless-single-stream-plan.md)。

> **状态: ✅ 已完成 (2026-08-17)**
> 参考 agentscope 官方前端重构调试页面，已完成主题/布局/会话页/长连接 SSE 全部改造，308 用例全绿。

---

## 〇、决策记录

| 决策点 | 结论 |
|--------|------|
| 参考对象 | `agentscope-ai/agentscope` 仓库 `examples/web_ui/frontend`（官方 Web UI，非 agentscope-studio） |
| 主题方案 | **全局浅色**（用户确认）：整个 debug 控制台（header/sidebar/所有模块）统一改为官方浅灰风格 |
| 改动范围 | 布局 + 样式全面对齐官方；**会话页（Chat 模块）功能增强**（更多事件展示）；其余模块仅做样式适配，功能不变 |
| 后端接口 | 默认不改；**仅当功能确实需要时才改，改动前必须先与用户确认** |
| 前端技术 | 保持现有原生 JS 模块体系，不引入 React/构建工具（官方是 React，此处仅做视觉/交互对齐） |
| **工具展示策略** | **不做流式工具参数回填**（移除 `fillToolArgsFromHistory`/`refreshPendingToolArgs`）；Channel 按自身事件词表完整展示，A2A 仅按标准 A2A 帧展示，**保留两通道展示差异** |
| 历史工具展示 | **保留**（3.7：`/history` 独立数据源，含完整 tool_calls） |
| 会话侧栏分组 | **不做 今日/更早 分组，平铺列表**（按 `updated_at` 降序） |
| 工具渲染器匹配 | **建映射表**：内置工具名（read_file/write_file/edit_file/grep_files/glob_files 等）→ 官方渲染样式，未映射用 Default |
| 流式传输模型 | **改长连接 SSE + 断线重连**（对齐官方）；需要后端新增订阅端点（见第六节 F 项） |
| **A2A 长连接机制** | **走 SDK `tasks/resubscribe`**（协议自带，A2A 侧不新建订阅端点，沿用 A2A 标准） |
| **事件总线扇出（F-A）** | `POST .../chat` 内部调用 `chatChannel.sendStream` 并 `doOnNext` 扇出到 per-session `Sinks.Many` 总线（方案 A，不动 SDK） |
| **旧端点 `/chat/stream`** | **保留**；页面提供「单次流调试」按钮（一次性流模式，与长连接订阅并存） |
| A2A 工具展示形态 | **仅工具名折叠块**（忠实呈现标准帧，无参数/结果区） |
| 主题切换 | **自动（prefers-color-scheme）+ Header 手动开关**双通道 |
| 默认折叠状态 | 工具分组、思维链 **默认折叠**（对齐官方） |
| **后端改动（F）** | **已确认**：3.8 长连接 SSE 订阅/触发端点 + 保留旧端点（本轮唯一后端改动） |
| **事件时序（迟到订阅）** | **先订阅后发送**：前端保证建立订阅后才发 `POST .../chat`，总线不做缓冲补发（简单，切换会话瞬间的消息可接受丢失） |
| **总线生命周期** | 惰性创建（`Map<sessionId, Sinks.Many>`）、无订阅超时清理、心跳 ~15s、切换会话前端主动 `AbortController` 关闭（默认方案） |
| **验证环境** | 本地 `mvn -o test`（300 用例 + F 新增）；手工验收起后端时注入 LLM 环境变量（sensenova 实测可用 key）：`LLM_API_KEY=sk-WBHF2xYYN61Kde4mXnYYjkxJxryw9KIB`、`LLM_MODEL_ID=sensenova-6.7-flash-lite`、`LLM_BASE_URL=https://token.sensenova.cn/v1` |

---

## 一、现状分析

### 1.1 当前调试页面架构

```
src/main/resources/static/debug/
├── index.html              # 入口：Header 状态条 + Sidebar 导航 + Content 区
├── css/
│   ├── base.css            # CSS 变量（GitHub 深色主题）+ 重置
│   ├── layout.css          # Header / Sidebar / Content / Chat 布局
│   └── components.css      # 按钮 / 气泡 / 工具块 / 思维链 / 表格 / 弹窗
├── js/
│   ├── api.js              # 后端端点封装
│   ├── app.js              # 模块注册 + 全局对象 window.App
│   ├── router.js           # Hash 路由 + 侧边栏渲染
│   ├── state.js            # 全局状态 + Header 状态点
│   └── utils.js            # esc / formatMarkdown / modal / toast
└── modules/                # 10 个功能模块
    ├── chat.js             # 会话页（A2A / Channel 双模式，实时流渲染）
    ├── tools.js / skills.js / mcp.js
    ├── config.js / memory.js / database.js / workspace.js / sandbox.js / logs.js
```

### 1.2 当前主题特征（将被替换）

| 项 | 当前值（深色） | 官方目标（浅色） |
|----|---------------|-----------------|
| 背景 | `#0d1117` / `#161b22` | `#f4f5f6` / 白卡 `#fff` |
| 文本 | `#c9d1d9` / `#8b949e` | `#18191c` / `#6b6f76` |
| 强调色 | `#58a6ff`（蓝） | 紫 `rgba(170,59,255,…)` + 主题色 `#18191c` |
| 明暗 | 仅深色 | `prefers-color-scheme` 浅/深双主题 |
| 圆角 | 8px | `--radius: 1rem`（卡片 22px，输入 32px） |
| 字体 | 系统字体 | Geist / Geist Mono |

### 1.3 会话页当前能力（chat.js，966 行）

- A2A `message/stream` / Channel `/chat/stream` 双模式
- 消息气泡（user/assistant/system）、Markdown 渲染（弱实现：只支持 code/粗斜体）
- 工具调用折叠块（`Tool: name` + Arguments/Result）、思维链折叠块
- 工具参数/结果渐进渲染（`TOOL_CALL_DELTA` / `TOOL_RESULT_TEXT_DELTA`）
- usage 统计（LLM Calls / token / 耗时）
- Thread 下拉选择 + 历史加载、MCP App iframe 渲染、LLM Calls / System Prompt / Card 弹窗

---

## 二、官方前端参考分析

> 来源：`examples/web_ui/frontend`，关键文件：
> `pages/chat/index.tsx`、`pages/chat/ChatViewport.tsx`、`components/chat/ChatContent.tsx`、
> `components/chat/ASMessageBubble.tsx`、`components/chat/TextInput.tsx`、
> `components/chat/tool-renderers/*`、`components/layout/AppLayout.tsx`、`components/layout/AppSidebar.tsx`、`index.css`。

### 2.1 布局骨架

```
┌──────────────────────────────────────────────────────────────┐
│ AppLayout: 浅灰画布 #f4f5f6，内容悬浮 22px 圆角卡片           │
├────────┬─────────────────────────────────────────────────────┤
│ AppSidebar │  Chat 页面：                                    │
│ (窄图标栏) │  ┌─ 会话侧栏 ─┐  ┌──────── 主区域 (flex) ──────┐ │
│ 圆形logo  │  │ Agent 选择 │  │ 顶栏: LLM选择/参数/权限/Panel ▾ │ │
│ 聊天icon  │  │ + 新会话   │  │ 消息列表 (居中列 max-w 48rem)  │ │
│ 调度icon  │  │ 会话分组   │  │ 输入 pill (rounded-[32px])     │ │
│ 渠道icon  │  │ 今日/更早  │  └─────────────────────────────┘ │
│ ...      │  └───────────┘  右面板: Plan/MCP/Skill/Permission │
└────────┴─────────────────────────────────────────────────────┘
```

- AppSidebar：窄图标栏（约 56px），圆形黑色 logo、居中图标按钮（tooltip 提示）、底部设置
- 会话侧栏：Agent 下拉 + 会话分组（今日/更早）+ 每条会话的菜单（重命名/删除）
- 主区域：顶栏（LLM Select、参数 Popover、权限 Select、面板开关 Dropdown）+ 居中消息列 + 输入 pill
- ChatContent：`MessageScroller`（自动滚动 + 右下角回底按钮），空状态显示 `chat.greeting` 大字问候

### 2.2 消息气泡（ASMessageBubble —— 核心对齐对象）

| 结构 | 官方实现 |
|------|---------|
| 消息行 | 用户右对齐 `bubble muted`、助手左对齐 `bubble ghost`，`Message align=end/start` |
| **页脚 badge** | `<MessageFooter class="font-mono">`：`[状态图标] [耗时] [↑in ↓out tokens]` —— 运行中旋转 Loader，完成显示 CheckCircle；耗时每秒跳动（`now - created_at`） |
| 文本块 | `Markdown`（react-markdown + remark-gfm） |
| **思维链块** | `Collapsible`，标题「thinking」/「thinking for Xs」（实时计时），运行中 `shimmer` 流动效果，展开显示 Markdown 正文 |
| 数据块（附件） | image 显示图、video 图标、file 图标（`Attachment` + mime 扩展名） |
| 错误块 | `Alert destructive`（图标 + 标题 + 描述） |
| **工具调用组** | 连续 tool_call 合并为一个 `tool_call_group`，标题由 `summarizeToolGroup` 汇总（如「2 Bash · 1 Read · 1 Edit」），带 `+N -M` DiffStats |
| **工具行** | `ToolCallRow`：`▶ 工具名 + 参数 → 状态图标 + chevron`；状态图标 round 徽标：绿色 ✓ 成功 / 红色 ✗ 失败 / ⛔ 中断/拒绝 / 转圈 running；运行中 `shimmer` |
| 工具渲染器 | Bash / Read / Write / Edit / Glob / Grep / TaskCreate 各自专用渲染（命令行、编号文件内容、diff 高亮等），未注册工具用 Default |

### 2.3 token/设计变量（index.css 摘录，浅色）

```css
:root {
  --text: #6b6f76; --text-h: #18191c;
  --bg: #f4f5f6; --border: oklch(0.922 0 0);
  --code-bg: #f4f3ec; --accent: rgba(170,59,255,.1); --accent-border: rgba(170,59,255,.5);
  --primary: #18191c; --secondary: #f7f8f9; --muted: #f7f8f9; --muted-foreground: #6b6f76;
  --card: oklch(1 0 0); --radius: 1rem; --sidebar: rgba(255,255,255,.62);
  --shadow-panel: 0 1px 2px rgba(24,25,28,.05), 0 18px 40px -30px rgba(24,25,28,.28);
}
@media (prefers-color-scheme: dark) { /* 深色覆写 */ }
```

---

## 三、改造方案

### 3.1 总体策略

1. **主题层**：重写 `base.css` 变量为官方浅色 + 暗色 `prefers-color-scheme` 双主题；`layout.css`、`components.css` 重排版式与组件样式。
2. **会话页（核心）**：重写 `modules/chat.js` 渲染逻辑，对齐官方气泡/思维/工具分组/输入 pill；保留现有全部功能（A2A/Channel 双模式、tool call delta、LLM Calls 等）。**工具展示保留两通道差异（见 3.5-(4)），不做任何历史回填。**
3. **其余模块**：`tools/skills/mcp/config/memory/database/workspace/sandbox/logs` 逻辑不变，仅自动获得新主题（其样式均已走 CSS 变量，无需改 JS）；若个别硬编码颜色需微调，逐处改。
4. **后端**：默认零改动。需要改后端的功能单独列出（见第六节），**需用户确认后才实施**。

### 3.2 文件改动清单（实际执行）

| 文件 | 动作 | 说明 |
|------|------|------|
| `css/base.css` | **重写** | 官方浅色 token（浅/深）+ 重置 + Geist 字体栈 |
| `css/layout.css` | **重写** | Header/窄侧栏/会话侧栏(滚动)/主区并排对齐官方；flex-direction: row 修复 |
| `css/components.css` | **重写** | 气泡/页脚badge/思维块(边框+背景)/工具组/DiffStats/输入pill/弹窗/toast/表格等官方样式 |
| `index.html` | **调整** | 主题初始化脚本（避免闪烁）+ Header 主题切换按钮 |
| `js/router.js` | **重写** | 窄图标侧栏（图标 + tooltip data-title） |
| `js/api.js` | **重写** | 长连接订阅（onEvent + ready promise）+ triggerSessionChat |
| `js/state.js` | **重写** | 主题管理（自动 + 手动 + localStorage）+ loadAgentInfo |
| `js/utils.js` | **重写** | formatDuration / toolStateIcon / msgStateIcon |
| `js/app.js` | **重写** | initTheme + toolGroupToggle / toolRowToggle |
| `modules/chat.js` | **完全重写** | 官方气泡/思维计时/工具分组/渲染映射/长连接SSE/单次流/A2A标准帧/ready promise |
| `modules/tools.js` | 不动 | 功能不变 |
| `modules/skills.js` | 不动 | 功能不变 |
| `modules/mcp.js` | 不动 | 功能不变 |
| `modules/config.js` | 不动 | 功能不变 |
| `modules/memory.js` | 不动 | 功能不变 |
| `modules/database.js` | 不动 | 功能不变 |
| `modules/workspace.js` | 不动 | 功能不变 |
| `modules/sandbox.js` | 不动 | 功能不变 |
| `modules/logs.js` | 不动 | 功能不变 |
| `SessionEventBus.java` | **新建** | per-session 事件总线（Sinks.Many 分桶 + 惰性清理） |
| `SessionStreamController.java` | **新建** | GET /events（connected 初始帧 + 订阅 + 心跳）+ POST /chat（fire-and-forget） |
| `AgentEventSseSerializer.java` | **新建** | SSE 序列化共用工具（原 StreamController 词表 + replyId/blockId） |
| `StreamController.java` | **重构** | 调用共用序列化器，移除重复代码 |
| `AgentScopeConfig.java` | **微调** | 注册 SessionEventBus bean |
| `SessionStreamControllerTest.java` | **新建** | 8 个测试（订阅/触发/序列化/隔离性） |

### 3.3 主题层实现要点（css）

- `:root` 写入官方浅色变量，`@media (prefers-color-scheme: dark)` 覆写深色；现有 `var(--accent)` 语义映射到 `--primary`（文本强调色）与紫色饰边分离。
- **手动主题开关**：HTML `<html>` 根加 `.dark` 类可强制覆盖（优先级高于媒体查询）；Header 放 明/暗 切换按钮，写入 `localStorage`（`debug-theme`），未设置时跟随系统。CSS 用 `:root` / `html.dark` 双定义 + `@media` 兜底，保证"系统自动"与"手动覆盖"都生效。
- `body { background: var(--bg, #f4f5f6); color: var(--text-h); }`
- 主区卡片：`background: var(--card); border-radius: 22px; box-shadow: var(--shadow-panel);`
- 字体：`Geist Variable` 优先、`Geist Mono Variable` 用于 mono；无外网离线环境兜底 `system-ui, sans-serif`（**不引入外链字体**，避免内网无法加载，用系统近似字体栈即可）。
- 滚动条、focus ring、badge 色板同步浅/深。

### 3.4 会话页布局对齐（chat.js + layout.css）

```
┌ Chat 模块（module-page）───────────────────────────────┐
│ ┌ 会话侧栏 ┐ ┌ 主区（flex-1，浅灰画布）────────────────┐ │
│ │ Thread   │ │ 顶栏: Thread选择 | New | Refresh |     │ │
│ │ 分组列表 │ │       A2A/Channel | LLM Calls | ...    │ │
│ │ (平铺,按   │ │ ┌ 消息滚动器（居中 max-w 48rem）──────┐ │ │
│ │  更新降序) │ │ │  气泡 + 页脚 badge                  │ │ │
│ │          │ │ │  思维块(实时计时) 工具分组(+N -M)    │ │ │
│ │          │ │ │  向下按钮 ↧                          │ │ │
│ │          │ │ └─────────────────────────────────────┘ │ │
│ │          │ │ ┌ 输入 pill (圆角) [Send/Stop] ───────┐ │ │
│ └─────────┘ │ └──────────────────────────────────────┘ │ │
└─────────────────────────────────────────────────────────┘
```

- 会话侧栏：将顶栏 `module-header` 中的 `threadSelect` 下拉改为左侧侧栏列表（**平铺，按 `updated_at` 降序**，不做今日/更早分组），保留选中高亮、「+ New」。
- 消息滚动器：自动吸底；非底部时显示右下角回底圆钮（`MessageScrollerButton`）。
- 输入区：官方 pill（`rounded-[32px] bg-muted p-1`），Enter 发送 / Shift+Enter 换行；流式时 Send 变 Stop；**附「单次流」调试按钮**（走旧 `/chat/stream` 一次性流，绕过长连接总线，便于排查）。

### 3.5 会话页渲染对齐（核心改造点）

#### (1) 消息气泡 + 页脚
- 用户消息右对齐（`bubble muted`）、助手左对齐（`bubble ghost`）。
- 助手消息页脚 badge（mono）：`[状态] [耗时] [↑in ↓out]`：
  - 状态：运行中旋转图标、完成 ✓；耗时每 1s 跳动（`Date.now() - 开始`，总耗时较难在现有流中精确获得——见第六节后端可选增强）。
  - token：沿用现有 `MODEL_CALL_END`/A2A `_chat_usage` 的 usage 累计，展示在页脚。

#### (2) 思维链块（官方 ThinkingBlockView）
- 更名文案 「Thinking…」→ 官方样式「thinking」/「thinking for {Xs}」实时秒计；
- 运行中标题 `shimmer` 流动高亮；
- **默认折叠**（展开保留现有 toggle）；

#### (3) 工具调用 → 工具分组（官方 tool_call_group + ToolCallRow）
现有逻辑已是单卡块，将升级为：
- **连续工具合并分组**：连续 tool_call（无论名称）合并为一个可折叠容器（**默认折叠**），标题汇总统计：`N Bash · M Read · K Edit`（按内置工具名归类；MCP 工具归 `mcp` 组）；Edit/Write 仅当结果含 diff 数据时累加 `+N -M`（否则省略，见六-E）。
- **每行 `ToolCallRow`**：`▶ 工具名/参数 + 状态图标 + chevron`；状态图标 ✓/✗/⛔/转圈（依据 `ToolResultEnd.state`）。
- **专用渲染器**（原生 JS 实现，非 React）：
  - `Bash`：显示命令行 + 输出（折叠）
  - `Read`：文件路径头 + 编号行内容
  - `Write`/`Edit`：diff 高亮（`+`绿 `-`红），`+N -M` 统计
  - `Glob`/`Grep`：模式 + 匹配结果
  - Default：Arguments/Result（保留现状）
- **渲染器映射表**（内置工具名 → 官方渲染样式，未列出的用 Default）：

  | 内置工具名 | 渲染器 |
  |-----------|--------|
  | `bash` / `shell` / `exec_command` | Bash |
  | `read_file` | Read |
  | `write_file` | Write |
  | `edit_file` | Edit |
  | `glob_files` | Glob |
  | `grep_files` | Grep |
  | 其余 20 个内置 + 全部 MCP（`mcp__*`） | Default |
- **保留现有 `TOOL_CALL_DELTA` / `TOOL_RESULT_TEXT_DELTA` 渐进渲染能力**（仅 Channel / 历史来源具备；A2A 标准帧无此粒度）。

#### (4) 工具展示：双通道差异（重要约束）

**不做流式工具参数回填**。工具展示回归各通道数据的真实面貌，不跨通道补数据：

| 维度 | Channel（/chat/stream） | A2A（标准帧 message/stream） |
|------|------------------------|------------------------------|
| 工具参数 | 有（`TOOL_CALL_START`/`TOOL_CALL_DELTA`/`TOOL_CALL_END` 渐进收到） | **无**（A2A 帧不含工具参数，仅 data part 带 `_agentscope_tool_name`/`_agentscope_tool_call_id` metadata） |
| 工具结果 | 有（`TOOL_RESULT_START`/`TOOL_RESULT_TEXT_DELTA`/`TOOL_RESULT_END` + `state`） | 无独立结果帧；仅能靠后续 text 呈现，不额外解析 |
| 展示形态 | 完整 ToolCallRow（Arguments + Result + 状态图标 ✓/✗） | 仅展示工具名 + 工具名折叠块（**无参数、无结果**），形态即官方标准帧的忠实呈现 |
| 分组 | 连续工具调用合并分组 | 同样合并分组，但每行仅工具名 |
| 回填 | — | **移除** `fillToolArgsFromHistory` / `refreshPendingToolArgs`（不再为 A2A 流从 agent_state 补参数） |

> 原则：**A2A 展示的就是标准 A2A 帧里有的东西**，不带入 Channel 才有的细节；多通道数据各按自己真实来源渲染，不造假、不回填。
> 历史消息渲染（见 3.7）是**独立的数据来源**（`/debug/threads/{id}/history` 已含 tool_calls），与 A2A 流式回填无关，保留。

#### (5) MCP App iframe / LLM Calls / System Prompt / Card 弹窗
- 全部保留，仅弹窗样式跟随新主题；LLM Calls 折叠条目沿用官方卡片风。

### 3.6 更多事件展示（会话页信息增强）

在保留原事件处理基础上，**增强可见性与实时反馈**：

| 事件 | 现状 | 增强 |
|------|------|------|
| `TEXT_BLOCK_DELTA` | 追加文本 | 进入当前 assistant 气泡 |
| `THINKING_BLOCK_DELTA/END` | 折叠展示 | 官方思维块（实时计时 + shimmer） |
| `TOOL_CALL_START/DELTA/END` | 卡片展示参数 | 分组 + ToolCallRow + 状态图标（running→success/error）；**仅 Channel 完整展示参数**，A2A 仅工具名（见 3.5-(4)） |
| `TOOL_RESULT_START/TEXT_DELTA/END` | 填结果 | 状态图标根据 `state` 切换 ✓/✗；**仅 Channel** 有结果与状态 |
| `MODEL_CALL_END` | 累加 usage | 页脚 badge 实时 ↑in ↓out |
| **`MODEL_CALL_START`** | **已转发但未消费** | 新增：气泡内展示「LLM 调用中（第 N 次）」指示，结束即消失——**零后端改动** |
| `DATA_BLOCK_START/DELTA` | 已转发未消费 | 视 SDK 字段可用性展示附件/音频占位（见六-C） |
| timeout / error | 红字 | 官方 `Alert destructive` 样式 |
| 空会话 | “New thread” | 官方 greeting 大字 + 居中 |

### 3.7 历史消息渲染增强（零后端改动，当前缺失）

`/debug/threads/{sid}/history` 已通过 `StateDataParser.toRoleContentList` 返回每条消息的 `tool_calls`（含 `id`/`name`/完整 `input`），但当前 `loadThreadHistory` **只渲染 role+content 纯文本、丢弃工具调用**，导致加载历史会话时看不到任何工具执行过程。

改造：历史渲染复用与流式一致的渲染管线——按 `content` 块顺序渲染文本 + 工具分组（ToolCallRow 状态置为已完成 ✓，无 result 数据则不展示结果区），使历史会话与实时会话视觉一致、信息完整。

### 3.8 流式传输模型：单次流 → 长连接 SSE（需后端配合）

**现状（单次流）**：每次发送独立触发 `/chat/stream`（Channel）或 `POST / message/stream`（A2A），一次连接对应一次回复。问题：**无法感知他人/后台触发器（定时任务、子 Agent 回话、重试）在同一 session 上的新回复**，切换会话/长时间停留会漏消息。

**目标（长连接 SSE，对齐官方前端）**：

```
页面加载/选择会话
   │
   ├─ ① GET /debug/threads/{sessionId}/events   ← 长连接订阅（后端保留）
   │        event: agent_event / data: {type, ...}   （该 session 上任何 run 的事件）
   │
   ├─ ② POST /debug/threads/{sessionId}/chat          ← fire-and-forget 触发
   │        body: {message, userId}                    （不用等流，事件经由 ① 回流）
   │
   └─ ③ 断线自动重连（指数退避）
```

**前端改造**：
- 建会话级事件总线：`subscribe(sessionId)` 打开长连接，事件经统一解析灌入 Msg/Block 状态机；
- **发送走长连接模式**：fire-and-forget（Channel 走新触发端点 `POST /debug/threads/{sessionId}/chat`，事件经订阅回流）；
- **「单次流」调试模式（保留）**：切换后发送走旧 `GET /chat/stream` 一次性流，用于排查总线问题；
- `EventSource`/fetch-reader + 重连（`AbortController` 退出、切换会话即关旧连接）；
- 状态机按 `reply_id` 聚合各次回复，流结束收到 terminal 事件后更新页脚/状态。
- **时序约束（已确认）：先订阅后发送** —— 建立长连接订阅成功后才允许 `POST .../chat` 触发，避免迟到订阅丢事件；总线不设缓冲补发（切换会话瞬间的消息可接受丢失）。

**后端所需契约（第六节 F，需确认）**：
- **Channel 侧事件总线（SDK 无现成订阅，需自建）**：
  - SDK 的 `ChatUiChannel` 是请求-响应式（`sendStream` 每条消息一个 Flux + `outboundQueue`），**没有会话级广播**。拟在应用层自建 **per-session 事件总线**（Reactor `Sinks.Many<AgentEvent>` 注册表，按 sessionId 分桶）：
    - `POST /debug/threads/{sessionId}/chat`：内部调用现有 `chatChannel.sendStream(SendOptions, msg)`，并 `doOnNext(e -> sessionBus(sessionId).tryEmitNext(e))` 扇出到该会话总线；终态事件（REPLY_END/AGENT_END）负责标记阶段结束。
    - `GET /debug/threads/{sessionId}/events`：订阅 `sessionBus(sessionId)`，返回 `Flux<ServerSentEvent>`（复用 StreamController 的 `toSSE` 词表），心跳保活。
  - 会话总线生命周期：`Map<sessionId, Sinks.Many>` 惰性创建、无订阅超时后清理；实现上不侵入/改造 ChatUiChannel，只在外层做扇出。
  - 现有 `GET /chat/stream` **保留不动**（一次性流独立使用，便于单发调试），新端点与它并存；**页面提供「单次流调试」按钮**以走旧端点。
- **A2A 侧：直接用 SDK `tasks/resubscribe`（标准协议自带，已确认）** —— A2A 模式不新建订阅端点；发送仍走 `POST / message/message`，断线/恢复用 `tasks/resubscribe` 按 A2A 会话语义取回事件。
- 订阅生命周期：与 session 绑定，200/空闲事件、心跳保活（~15s comment/`ping`）、断开清理。

> 备选（不改后端）：维持单次流，仅前端在切换会话/定时拉历史兜底。**本方案默认按长连接实施，但后端端点为新需求，需你确认后端改动后执行。**

---

## 四、改造步骤（仅会话页与主题，分阶段）

### 阶段一：主题层（无 JS 改动）✅
1. 重写 `css/base.css`（官方浅色 token + dark 覆写 + 字体栈）✅
2. 重写 `css/layout.css`（Header/侧栏/主区对齐官方）✅
3. 重写 `css/components.css`（官方组件样式）✅
4. 调整 `index.html`（Header 形态 + 主题开关）✅

### 阶段二：会话页布局 ✅
5. `js/router.js` 侧栏窄栏化（图标 + tooltip）✅
6. `modules/chat.js` 布局重排（会话侧栏 + 居中列 + pill 输入）✅

### 阶段三：会话页渲染对齐 + 长连接 SSE ✅
7. 消息气泡 + 页脚 badge（状态/耗时/token）+ `MODEL_CALL_START` 指示 ✅
8. 思维块官方化（thinking for Xs + shimmer，默认折叠）✅
9. 工具分组渲染器（Bash/Read/Write/Edit/Glob/Grep/Default，按映射表）✅
10. 回底按钮、空会话 greeting、错误 Alert ✅
11. 历史消息渲染增强（工具调用从 history 复原）✅
12. 保留 A2A/Channel 双模式、tool delta 渐进渲染（仅 Channel）、LLM Calls/MCP iframe；**删除 A2A 流工具参数回填逻辑** ✅
13. **长连接 SSE（3.8）**：per-session 事件总线 + connected 初始帧 + 订阅/触发端点 ✅

### 阶段四：回归 + 验证 ✅
14. `mvn test` 308 用例全绿（含 SessionStreamControllerTest 8 个）✅
15. 手工验收 + e2e puppeteer 截图 15 张全通过 ✅
16. 浏览器实测 Channel 长连接（mock LLM 首字节 3.5ms，真实 LLM 18s）✅

---

## 五、不改动的部分

- 后端 `DebugController` / `DebugApiController` / `StreamController` / `A2AController`：**默认不动**；**唯一例外：3.8/六-F 长连接订阅端点（新控制器或新方法）**。
- 其余 9 个模块的功能逻辑：`tools/skills/mcp/config/memory/database/workspace/sandbox/logs` 行为完全不变。
- 不引入前端构建工具/框架，不新增 Java/前端依赖。

---

## 六、可能需要后端配合的点（默认不做，需用户确认）

以下属「功能增强才需要」的范围，**未经用户确认不实施**：

| # | 需求 | 后端改动 | 影响 |
|---|------|---------|------|
| A | 助手消息**精确总耗时**（页脚真实时长） | `StreamController.toSSE` 增加回包开始/结束时间戳（`reply_start_at`/`reply_end_at`），或 forward `ReplyStartEvent/ReplyEndEvent` | 现前端只能近似计时（从首个 delta 计），无后端也能做，但精确值需后端 |
| B | 工具**真实运行时长**（ToolCall 级） | `ToolCallStart/End` 或 `ToolResultEnd` 事件带时间戳 | 增强 ToolCallRow 显示耗时 |
| C | 更多事件类型透传（DataBlock block_id 等） | `toSSE` 透传 `data.values`/`blockId`/`mediaType`（当前 v2.0.0 注释缺字段） | 用于附件渲染，需核实 SDK 字段 |
| D | 会话侧栏展示会话**标题/来源**（今日/更早分组） | `/debug/threads` 补充 `created_at`、可选 `name` | **已按用户决策：不做分组，平铺列表，此需求作废** |
| E | Edit/Write 工具 **diff 数据**（`+N -M` 统计与高亮） | 后端内置工具结果补充 unified diff（`metadata.diff`）或统一文本 diff | 官方 DiffStats 依赖该字段；无 diff 时降级为纯 Arguments/Result 展示 |
| **F** | **长连接订阅端点（3.8 流式模型改造）** | 应用层自建 **per-session 事件总线**（`Sinks.Many` 按 sessionId 分桶）：新增 `GET /debug/threads/{sessionId}/events`（订阅总线）+ `POST /debug/threads/{sessionId}/chat`（触发并扇出事件到总线，**方案 A：内部调 `sendStream` + `doOnNext` 扇出**）；现有 `/chat/stream` **保留**（页面提供「单次流调试」按钮）；**A2A 侧走 SDK `tasks/resubscribe`，不新建端点** | **用户已确认**：长连接 SSE + F-A 扇出方案 + 保留旧端点；总线生命周期/心跳与实施细节实施中对齐 |

> **降级策略**：无后端配合时，E 类工具（Edit/Write）按 Default 渲染（参数+结果），不强行做 diff 高亮；A 用客户端近似计时。

> 结论：**F（长连接 SSE）为本轮确认的后端改动**（总线方案 A + 保留旧端点 + A2A 用 resubscribe）；A/B/C/E 在功能需要时单独与用户确认；D 已作废。

---

## 七、验收标准

1. `/debug` 全页面为官方浅灰风格；**跟随系统自动浅/深 + Header 手动开关**均可生效。✅
2. 会话页：消息气泡 + 页脚 badge（耗时/tokens）、思维块实时计时、工具分组（默认折叠）、LLM 调用中指示、输入 pill、回底按钮、空会话 greeting，全部呈现。✅
3. 会话页既有能力不丢：A2A/Channel、工具进度渲染（Channel）、LLM Calls、MCP iframe、Thread 历史（且历史含工具调用展示）。✅
4. **工具展示差异符合预期**：Channel 完整展示工具参数/结果；A2A 仅展示标准帧内容（工具名），无参数/结果、无回填造假。✅
5. **长连接 SSE 生效**：他人/后台触发的回复能实时出现在当前会话页；断线自动重连；「单次流」调试按钮可切回旧 `/chat/stream` 一次性流。✅
6. **先订阅后发送**：fetch 返回（connected 帧）即订阅就绪，触发事件经总线回流，无事件丢失。✅
7. 其余模块功能与现状完全一致（仅样式变化）。✅
8. `mvn test` 308 全绿（含 SessionStreamControllerTest）。✅

---

## 八、风险与备注

- **字体**：官方使用 Geist，需外网字体源；本环境内网/离线优先，采用 `Geist Variable, system-ui, sans-serif` 回退栈，不做外链。
- **主题切换**：`prefers-color-scheme` 自动 + `.dark`/`.light` 手动覆盖（root class + localStorage），CSS 优先级：手动覆盖 > 系统自动。
- **工作量**：95% 以上是纯前端 CSS/JS；chat.js 是最大改造文件（~1080 行）。
- **双模式事件词汇差异**：Channel SSE 事件丰富（TEXT_BLOCK_DELTA/TOOL_CALL_*/MODEL_CALL_*），A2A `message/stream` 仅有 artifact-update/status-update/message 且无工具参数。渲染中间层（handleEvent）按 agent-replyId 聚合 block 事件，与流解析解耦。
- **A2A 工具展示约束**：Channel 完整展示工具参数/结果；A2A 仅展示标准 A2A 帧实际携带的内容（工具名 + 折叠块，无参数/结果）。两通道展示差异为**有意保留**。
- **长连接 SSE 生命周期**：connected 初始帧（确保 fetch 立即返回）+ 心跳 15s + 事件总线惰性清理。触发（POST）与订阅（GET）分离，`ready` promise 保证"先订阅后发送"时序。
- **历史与实时一致性**：历史渲染（3.7）与流式渲染共用同一套组件函数。
- **LLM 延迟**：mock LLM 首字节 3.5ms（框架开销 < 0.2s）；真实 sensenova 首次约 18s，均为 LLM API 延迟，非框架问题。