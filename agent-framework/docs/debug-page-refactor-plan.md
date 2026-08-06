# Debug Page 重构计划

> **状态: ✅ 已完成 (2026-08-07)**
> 重构已落地：新调试页面位于 `src/main/resources/static/debug/`（拆分架构，index.html + css/js/modules），
> `DebugController` 改为加载 `classpath:/static/debug/index.html`，旧单文件 `templates/debug_page.html` 已删除。
> 本文档保留作为重构过程记录。

## 〇、架构方案选型

### 0.1 问题分析

当前 `debug_page.html` 为单文件架构（728行），随着功能扩展面临以下问题：

| 问题 | 影响 |
|------|------|
| 代码臃肿 | 单文件过大，难以维护 |
| 功能耦合 | 所有功能混在一起，修改风险高 |
| 加载性能 | 一次性加载所有功能，首屏慢 |
| 开发效率 | 多人协作容易冲突 |
| 扩展困难 | 新增功能需要修改整个文件 |

### 0.2 架构方案对比

#### 方案一：多页面方案（MPA）

```
/debug/                    # 主入口（导航页）
/debug/chat                # 对话调试页
/debug/tools               # 工具管理页
/debug/config              # 配置查看页
/debug/memory              # 记忆管理页
/debug/logs                # 日志查看页
/debug/database            # 数据库状态页
```

**实现方式：**
- 后端：多个 `@GetMapping` 端点
- 前端：多个独立 HTML 文件
- 导航：页面间跳转

**优点：**
- ✅ 结构清晰，每个页面职责单一
- ✅ 按需加载，性能好
- ✅ 开发独立，互不影响
- ✅ 无需前端路由库

**缺点：**
- ❌ 页面跳转会刷新，体验稍差
- ❌ 共享状态需要通过 URL 参数或 localStorage
- ❌ 代码重复（公共样式/脚本）

---

#### 方案二：SPA + Hash 路由

```
/debug#/                   # 主入口
/debug#/chat               # 对话调试
/debug#/tools              # 工具管理
/debug#/config             # 配置查看
/debug#/memory             # 记忆管理
/debug#/logs               # 日志查看
```

**实现方式：**
- 后端：单个 `/debug` 端点返回主框架
- 前端：`hashchange` 事件监听 + 动态加载模块
- 模块：JS 文件按功能拆分

**优点：**
- ✅ 单页面体验，无刷新跳转
- ✅ 共享状态容易管理
- ✅ 按需加载模块
- ✅ 实现简单，无需构建工具

**缺点：**
- ❌ 需要自己实现简单路由
- ❌ 首屏需要加载框架代码

---

#### 方案三：SPA + History 路由

```
/debug                     # 主入口
/debug/chat                # 对话调试
/debug/tools               # 工具管理
/debug/config              # 配置查看
/debug/memory              # 记忆管理
```

**实现方式：**
- 后端：`/debug/**` 全部返回同一页面
- 前端：`popstate` 事件 + `pushState` API
- 模块：动态 import 或预加载

**优点：**
- ✅ URL 更美观
- ✅ 单页面体验
- ✅ 与现代前端框架一致

**缺点：**
- ❌ 后端需要处理通配路由
- ❌ 需要处理浏览器兼容性
- ❌ 实现复杂度稍高

---

#### 方案四：标签页方案（Tab）

```
/debug                     # 单一页面
├── Tab: Chat              # 对话调试
├── Tab: Tools             # 工具管理
├── Tab: Config            # 配置查看
├── Tab: Memory            # 记忆管理
└── Tab: Logs              # 日志查看
```

**实现方式：**
- 后端：单个 `/debug` 端点
- 前端：Tab 切换 + 内容区域动态渲染
- 模块：JS 对象管理各 Tab 内容

**优点：**
- ✅ 实现最简单
- ✅ 状态共享容易
- ✅ 无页面跳转

**缺点：**
- ❌ 所有功能一次性加载
- ❌ 页面仍然较大
- ❌ Tab 多了体验差

---

#### 方案五：侧边栏导航 + 动态面板

```
/debug                     # 单一页面
├── 侧边栏导航
│   ├── Chat
│   ├── Tools
│   ├── Config
│   └── ...
└── 内容面板（动态切换）
```

**实现方式：**
- 后端：单个 `/debug` 端点
- 前端：侧边栏点击切换面板内容
- 模块：面板内容懒加载

**优点：**
- ✅ 类似管理后台风格
- ✅ 导航清晰
- ✅ 实现适中

**缺点：**
- ❌ 占用屏幕空间
- ❌ 移动端体验差

---

### 0.3 方案对比总结

| 方案 | 实现复杂度 | 用户体验 | 性能 | 可维护性 | 推荐度 |
|------|-----------|----------|------|----------|--------|
| 多页面 (MPA) | ⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐ |
| Hash 路由 SPA | ⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| History 路由 SPA | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ |
| 标签页 Tab | ⭐ | ⭐⭐⭐ | ⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ |
| 侧边栏导航 | ⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐ |

### 0.4 推荐方案：Hash 路由 SPA

**推荐理由：**

1. **实现简单**：无需构建工具，纯原生 JavaScript
2. **体验良好**：无页面刷新，状态共享容易
3. **按需加载**：模块可动态加载，首屏快
4. **兼容性好**：所有浏览器都支持 hash 路由
5. **调试方便**：URL 可分享，刷新保持状态

**目录结构：**

```
templates/
├── debug/
│   ├── index.html          # 主框架（导航 + 路由）
│   ├── css/
│   │   ├── base.css        # 基础样式
│   │   ├── layout.css      # 布局样式
│   │   └── components.css  # 组件样式
│   ├── js/
│   │   ├── app.js          # 应用入口
│   │   ├── router.js       # 路由管理
│   │   ├── api.js          # API 客户端
│   │   ├── state.js        # 状态管理
│   │   └── utils.js        # 工具函数
│   └── modules/
│       ├── chat.js         # 对话模块
│       ├── tools.js        # 工具管理
│       ├── skills.js       # 技能管理
│       ├── mcp.js          # MCP 管理
│       ├── memory.js       # 记忆管理
│       ├── config.js       # 配置查看
│       ├── database.js     # 数据库状态
│       ├── workspace.js    # 工作区管理
│       └── logs.js         # 日志查看
```

**路由设计：**

```javascript
const routes = {
  '#/': { module: 'chat', title: 'Chat' },
  '#/tools': { module: 'tools', title: 'Tools' },
  '#/skills': { module: 'skills', title: 'Skills' },
  '#/mcp': { module: 'mcp', title: 'MCP' },
  '#/memory': { module: 'memory', title: 'Memory' },
  '#/config': { module: 'config', title: 'Config' },
  '#/database': { module: 'database', title: 'Database' },
  '#/workspace': { module: 'workspace', title: 'Workspace' },
  '#/logs': { module: 'logs', title: 'Logs' }
};
```

**后端改造：**

```java
@RestController
public class DebugController {

    @GetMapping(value = "/debug", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> debugPage() {
        // 返回主框架 HTML
        return ResponseEntity.ok(loadResource("templates/debug/index.html"));
    }

    @GetMapping(value = "/debug/module/{name}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getModule(@PathVariable String name) {
        // 返回模块配置或数据
        return ResponseEntity.ok(getModuleConfig(name));
    }
}
```

---

## 一、当前功能分析

### 1.1 已实现功能

| 功能模块 | 实现状态 | 说明 |
|----------|----------|------|
| **Agent 信息显示** | ✅ | 显示名称、MCP数量、工具列表 |
| **健康检查** | ✅ | 显示连接状态、Checkpoint状态 |
| **工具列表** | ✅ | 显示所有工具标签 |
| **技能列表** | ✅ | 显示技能名称和描述 |
| **MCP 服务器列表** | ✅ | 显示MCP服务器名称 |
| **Thread 管理** | ✅ | 列出/选择/新建Thread，30秒自动刷新 |
| **对话功能** | ✅ | 支持A2A和Channel双模式 |
| **工具调用展示** | ✅ | 折叠/展开工具调用块，显示参数和结果 |
| **LLM 调用历史** | ✅ | 查看每个Thread的LLM请求/响应详情 |
| **System Prompt 查看** | ✅ | 查看完整系统提示词（基础+自动生成部分） |
| **Agent Card 查看** | ✅ | 查看Agent Card JSON |
| **使用统计** | ✅ | 显示输入/输出token数和耗时 |
| **MCP Apps 渲染** | ✅ | iframe渲染MCP应用UI |
| **流式传输** | ✅ | 支持A2A和Channel两种流式模式 |

### 1.2 当前架构

```
debug_page.html (728行)
├── HTML结构
│   ├── Header: 状态显示、按钮组
│   ├── Main: 侧边栏 + 聊天区域
│   └── Modal: 弹窗显示
├── CSS样式 (131行)
│   └── 暗色主题，响应式设计
└── JavaScript逻辑 (533行)
    ├── 初始化: 健康检查、工具/技能/MCP加载
    ├── Thread管理: 列表、选择、新建
    ├── 消息发送: A2A/Channel双模式
    ├── 流式处理: SSE解析、实时显示
    ├── 工具调用: 折叠/展开、参数/结果显示
    └── 弹窗功能: Agent Card、System Prompt、LLM Calls
```

---

## 二、完整功能清单对比

### 2.1 API端点覆盖

| 端点 | 当前覆盖 | 说明 |
|------|----------|------|
| `GET /` | ✅ | 服务信息 + 协议声明 |
| `GET /health` | ✅ | 健康检查 |
| `GET /.well-known/agent-card.json` | ✅ | Agent Card |
| `GET /skills` | ✅ | 技能列表 |
| `GET /mcp` | ✅ | MCP服务器列表 |
| `GET /tools` | ⚠️ | 部分覆盖（仅显示工具名，未分类） |
| `GET /debug` | ✅ | 调试页面本身 |
| `GET /system-prompt` | ✅ | 系统提示词 |
| `GET /threads` | ✅ | Thread列表 |
| `GET /chat/stream` | ✅ | Channel SSE流式对话 |
| `POST /` | ✅ | A2A JSON-RPC |

### 2.2 功能模块对比

| 功能模块 | 完整功能 | 当前状态 | 缺失功能 |
|----------|----------|----------|----------|
| **工具体系** | 内置工具(26个)、自定义工具、MCP工具、工具过滤 | ⚠️ 部分 | 工具分类展示、工具过滤机制显示、工具详情查看 |
| **技能管理** | 技能列表、技能详情、技能加载 | ⚠️ 部分 | 技能详情查看、技能加载状态 |
| **MCP集成** | MCP服务器列表、MCP工具列表、MCP配置查看 | ⚠️ 部分 | MCP工具列表、MCP配置详情、连接状态 |
| **记忆管理** | MEMORY.md、memory/、记忆搜索 | ❌ 缺失 | 记忆内容查看、记忆搜索功能 |
| **上下文压缩** | 30条触发、保留10条 | ❌ 缺失 | 压缩配置查看、压缩状态显示 |
| **Plan Mode** | 进入/写入/退出计划模式 | ❌ 缺失 | 计划模式状态显示、计划内容查看 |
| **多租户** | userId隔离、sessionId生成 | ⚠️ 部分 | 多租户配置查看、用户切换功能 |
| **数据库状态** | agent_state、agent_fs表 | ❌ 缺失 | 数据库连接状态、表统计信息 |
| **工作区管理** | AGENTS.md生成、tools.json生成、Skills复制 | ❌ 缺失 | 工作区文件浏览、工作区状态查看 |
| **子Agent** | agent_spawn、agent_send、agent_list | ❌ 缺失 | 子Agent列表、子Agent状态查看 |
| **A2UI协议** | A2UI代码块提取、Artifact生成 | ❌ 缺失 | A2UI预览功能、A2UI代码块高亮 |
| **环境变量** | LLM配置、服务配置、Checkpoint配置 | ❌ 缺失 | 环境变量查看（脱敏显示） |
| **日志系统** | LLM调用日志 | ⚠️ 部分 | 系统日志查看、错误日志查看 |
| **配置管理** | OAF配置、MCP配置 | ❌ 缺失 | OAF配置查看、MCP配置编辑 |

---

## 三、缺失功能识别

### 3.1 高优先级缺失功能

#### 3.1.1 工具分类展示
**当前问题**: 工具列表仅显示工具名标签，未区分内置工具、自定义工具、MCP工具。

**需要实现**:
- 调用 `GET /tools` 获取分类工具列表
- 分三个区域显示：内置工具、自定义工具、MCP工具
- 显示工具详情（参数、描述、readOnly属性）

#### 3.1.2 MCP工具列表
**当前问题**: 仅显示MCP服务器名称，未显示MCP工具列表。

**需要实现**:
- 调用 `GET /mcp` 获取MCP服务器详情
- 显示每个MCP服务器的工具列表
- 显示MCP工具的参数和描述

#### 3.1.3 记忆管理可视化
**当前问题**: 完全缺失记忆管理功能。

**需要实现**:
- 新增API端点或利用现有工具
- 显示MEMORY.md内容
- 显示memory/目录下的记忆文件
- 支持记忆搜索功能

#### 3.1.4 环境变量查看
**当前问题**: 无法查看当前配置的环境变量。

**需要实现**:
- 新增API端点获取环境变量（脱敏显示）
- 显示LLM配置、服务配置、Checkpoint配置
- 敏感信息（如API Key）部分隐藏

### 3.2 中优先级缺失功能

#### 3.2.1 上下文压缩状态
**当前问题**: 无法查看上下文压缩配置和状态。

**需要实现**:
- 显示压缩配置（触发条数、保留条数）
- 显示当前Thread的消息数量
- 显示压缩触发状态

#### 3.2.2 Plan Mode状态
**当前问题**: 无法查看Plan Mode状态。

**需要实现**:
- 显示Plan Mode是否启用
- 显示当前计划内容（如果有）
- 支持进入/退出Plan Mode

#### 3.2.3 多租户配置查看
**当前问题**: 无法查看多租户配置。

**需要实现**:
- 显示当前userId
- 显示sessionId生成规则
- 显示租户前缀

#### 3.2.4 数据库状态
**当前问题**: 无法查看数据库连接状态。

**需要实现**:
- 显示数据库连接状态
- 显示表统计信息（agent_state、agent_fs）
- 显示连接池配置

### 3.3 低优先级缺失功能

#### 3.3.1 工作区文件浏览
**当前问题**: 无法浏览工作区文件。

**需要实现**:
- 新增API端点获取工作区文件列表
- 支持文件内容查看
- 显示工作区目录结构

#### 3.3.2 子Agent管理
**当前问题**: 无法查看和管理子Agent。

**需要实现**:
- 显示子Agent列表
- 显示子Agent状态
- 支持向子Agent发送消息

#### 3.3.3 A2UI预览
**当前问题**: 无法预览A2UI代码块。

**需要实现**:
- 识别A2UI代码块
- 渲染A2UI预览
- 支持A2UI代码块高亮

#### 3.3.4 系统日志查看
**当前问题**: 无法查看系统日志。

**需要实现**:
- 新增API端点获取系统日志
- 支持日志级别筛选
- 支持日志搜索

---

## 四、重构计划

### 4.1 架构设计（Hash 路由 SPA）

#### 4.1.1 目录结构

```
templates/
├── debug/
│   ├── index.html              # 主框架（导航 + 路由容器）
│   ├── css/
│   │   ├── base.css            # 基础样式（变量、重置）
│   │   ├── layout.css          # 布局样式（侧边栏、主区域）
│   │   └── components.css      # 组件样式（按钮、卡片、弹窗）
│   ├── js/
│   │   ├── app.js              # 应用入口 + 初始化
│   │   ├── router.js           # Hash 路由管理
│   │   ├── api.js              # API 客户端（统一封装）
│   │   ├── state.js            # 状态管理（全局状态）
│   │   └── utils.js            # 工具函数（esc、formatMarkdown等）
│   └── modules/
│       ├── chat.js             # 对话模块（A2A/Channel）
│       ├── tools.js            # 工具管理模块
│       ├── skills.js           # 技能管理模块
│       ├── mcp.js              # MCP 管理模块
│       ├── memory.js           # 记忆管理模块
│       ├── config.js           # 配置查看模块
│       ├── database.js         # 数据库状态模块
│       ├── workspace.js        # 工作区管理模块
│       └── logs.js             # 日志查看模块
```

#### 4.1.2 路由设计

```javascript
// router.js
const routes = {
  '#/':         { module: 'chat',     title: 'Chat',      icon: '💬' },
  '#/tools':    { module: 'tools',    title: 'Tools',     icon: '🔧' },
  '#/skills':   { module: 'skills',   title: 'Skills',    icon: '📚' },
  '#/mcp':      { module: 'mcp',      title: 'MCP',       icon: '🔌' },
  '#/memory':   { module: 'memory',   title: 'Memory',    icon: '🧠' },
  '#/config':   { module: 'config',   title: 'Config',    icon: '⚙️' },
  '#/database': { module: 'database', title: 'Database',  icon: '🗄️' },
  '#/workspace':{ module: 'workspace',title: 'Workspace', icon: '📁' },
  '#/logs':     { module: 'logs',     title: 'Logs',      icon: '📋' }
};

class Router {
  constructor() {
    this.modules = {};
    this.current = null;
    window.addEventListener('hashchange', () => this.resolve());
  }

  register(name, module) {
    this.modules[name] = module;
  }

  resolve() {
    const hash = window.location.hash || '#/';
    const route = routes[hash] || routes['#/'];
    
    if (this.current) {
      this.current.unmount?.();
    }
    
    const module = this.modules[route.module];
    if (module) {
      this.current = module;
      module.mount?.();
    }
  }
}
```

#### 4.1.3 模块接口规范

每个模块需实现以下接口：

```javascript
// modules/chat.js
export default {
  // 模块名称
  name: 'chat',
  
  // 挂载到容器
  mount() {
    const container = document.getElementById('module-content');
    container.innerHTML = this.render();
    this.init();
  },
  
  // 渲染 HTML
  render() {
    return `<div class="chat-module">...</div>`;
  },
  
  // 初始化事件绑定
  init() {
    // 绑定事件、加载数据
  },
  
  // 卸载（清理事件监听）
  unmount() {
    // 清理定时器、事件监听等
  }
};
```

#### 4.1.4 状态管理

```javascript
// state.js
const state = {
  agent: {
    info: null,
    health: null,
    tools: { builtin: [], custom: [], mcp: [] },
    skills: [],
    mcpServers: []
  },
  threads: {
    list: [],
    current: null
  },
  config: {
    env: null,
    oaf: null
  },
  ui: {
    streamMode: 'a2a',
    isStreaming: false
  }
};

// 状态变更事件
const listeners = {};

export function getState(path) {
  return path.split('.').reduce((obj, key) => obj?.[key], state);
}

export function setState(path, value) {
  const keys = path.split('.');
  const last = keys.pop();
  const target = keys.reduce((obj, key) => obj[key], state);
  target[last] = value;
  notify(path, value);
}

export function subscribe(path, callback) {
  if (!listeners[path]) listeners[path] = [];
  listeners[path].push(callback);
}

function notify(path, value) {
  listeners[path]?.forEach(cb => cb(value));
}
```

#### 4.1.5 API 客户端

```javascript
// api.js
const BASE = window.location.pathname.replace(/\/debug\/?.*$/, '') || '';

export const api = {
  // Agent 信息
  async getAgentInfo() {
    const resp = await fetch(`${BASE}/`);
    return resp.json();
  },
  
  async getHealth() {
    const resp = await fetch(`${BASE}/health`);
    return resp.json();
  },
  
  // 工具/技能/MCP
  async getTools() {
    const resp = await fetch(`${BASE}/tools`);
    return resp.json();
  },
  
  async getSkills() {
    const resp = await fetch(`${BASE}/skills`);
    return resp.json();
  },
  
  async getMcpServers() {
    const resp = await fetch(`${BASE}/mcp`);
    return resp.json();
  },
  
  // Thread
  async getThreads() {
    const resp = await fetch(`${BASE}/threads`);
    return resp.json();
  },
  
  async getThreadHistory(threadId) {
    const resp = await fetch(`${BASE}/threads/${encodeURIComponent(threadId)}`);
    return resp.json();
  },
  
  // 配置
  async getSystemPrompt() {
    const resp = await fetch(`${BASE}/system-prompt`);
    return resp.json();
  },
  
  async getAgentCard() {
    const resp = await fetch(`${BASE}/.well-known/agent-card.json`);
    return resp.json();
  },
  
  // 新增端点
  async getEnvConfig() {
    const resp = await fetch(`${BASE}/debug/config/env`);
    return resp.json();
  },
  
  async getMemory() {
    const resp = await fetch(`${BASE}/debug/memory`);
    return resp.json();
  },
  
  async getDatabaseStatus() {
    const resp = await fetch(`${BASE}/debug/database/status`);
    return resp.json();
  },
  
  async getWorkspace() {
    const resp = await fetch(`${BASE}/debug/workspace`);
    return resp.json();
  },
  
  async getLogs(level = 'all', limit = 100) {
    const resp = await fetch(`${BASE}/debug/logs?level=${level}&limit=${limit}`);
    return resp.json();
  }
};
```

### 4.2 UI 设计

#### 4.2.1 主框架布局

```
┌─────────────────────────────────────────────────────────────┐
│  Header: Agent Name | Status | StreamMode | Actions         │
├──────────┬──────────────────────────────────────────────────┤
│          │                                                  │
│  Side    │  Content Area                                    │
│  Nav     │  (动态加载模块内容)                                │
│          │                                                  │
│  💬 Chat │                                                  │
│  🔧 Tools│                                                  │
│  📚 Skills                                                  │
│  🔌 MCP  │                                                  │
│  🧠 Memory                                                  │
│  ⚙️ Config                                                  │
│  🗄️ DB   │                                                  │
│  📁 Work │                                                  │
│  📋 Logs │                                                  │
│          │                                                  │
├──────────┴──────────────────────────────────────────────────┤
│  Footer: Version | Engine | Links                          │
└─────────────────────────────────────────────────────────────┘
```

#### 4.2.2 index.html 主框架

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <title>Agent Debug Console</title>
  <link rel="stylesheet" href="css/base.css">
  <link rel="stylesheet" href="css/layout.css">
  <link rel="stylesheet" href="css/components.css">
</head>
<body>
  <div class="app">
    <header class="header">
      <div class="header-left">
        <h1>Agent Debug Console</h1>
        <span id="agentName" class="agent-name"></span>
      </div>
      <div class="header-center">
        <span id="statusDot" class="status-dot"></span>
        <span id="statusText" class="status-text">Connecting...</span>
      </div>
      <div class="header-right">
        <div class="mode-switch">
          <button id="modeA2A" class="mode-btn active">A2A</button>
          <button id="modeChannel" class="mode-btn">Channel</button>
        </div>
        <button id="btnRefresh" class="btn">Refresh</button>
        <button id="btnCard" class="btn">Card</button>
      </div>
    </header>
    
    <div class="main">
      <nav class="sidebar" id="sidebar">
        <!-- 导航项由 router 动态生成 -->
      </nav>
      
      <main class="content" id="module-content">
        <!-- 模块内容由 router 动态加载 -->
      </main>
    </div>
    
    <footer class="footer">
      <span id="version"></span>
      <span id="engine"></span>
    </footer>
  </div>
  
  <!-- 弹窗容器 -->
  <div id="modal-container"></div>
  
  <!-- 加载核心脚本 -->
  <script type="module" src="js/app.js"></script>
</body>
</html>
```

#### 4.2.3 各模块页面设计

**Chat 模块 (`#/`)**

```
┌─────────────────────────────────────────────────────────────┐
│  Thread: thread-abc123 | LLM Calls | System Prompt          │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  [消息列表]                                                  │
│  - User: 你好                                               │
│  - Assistant: 你好！有什么可以帮助你的？                      │
│  - Tool Call: read_file({path: "test.txt"})                 │
│  - Tool Result: "文件内容..."                                │
│  - Assistant: 根据文件内容...                                │
│                                                             │
├─────────────────────────────────────────────────────────────┤
│  [输入框]                                    [Send] [Stop]  │
└─────────────────────────────────────────────────────────────┘
```

**Tools 模块 (`#/tools`)**

```
┌─────────────────────────────────────────────────────────────┐
│  Tools Management                                           │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌─ Built-in Tools (26) ─────────────────────────────────┐ │
│  │ 📄 read_file      readOnly                            │ │
│  │ ✏️ write_file                                         │ │
│  │ 🔍 grep_files     readOnly                            │ │
│  │ ...                                                   │ │
│  └───────────────────────────────────────────────────────┘ │
│                                                             │
│  ┌─ Custom Tools (2) ────────────────────────────────────┐ │
│  │ ⏰ get_current_time  readOnly                         │ │
│  │ 📢 echo              readOnly                         │ │
│  └───────────────────────────────────────────────────────┘ │
│                                                             │
│  ┌─ MCP Tools (5) ───────────────────────────────────────┐ │
│  │ 🔌 weather: get_weather  readOnly                     │ │
│  │ 🔌 database: query_db                                │ │
│  └───────────────────────────────────────────────────────┘ │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

**Config 模块 (`#/config`)**

```
┌─────────────────────────────────────────────────────────────┐
│  Configuration                                              │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌─ Environment Variables ───────────────────────────────┐ │
│  │ LLM_API_KEY      sk-...abc123                         │ │
│  │ LLM_MODEL_ID     gpt-4                                │ │
│  │ LLM_BASE_URL     https://api.openai.com/v1            │ │
│  │ LLM_TEMPERATURE   0.7                                 │ │
│  └───────────────────────────────────────────────────────┘ │
│                                                             │
│  ┌─ OAF Configuration ───────────────────────────────────┐ │
│  │ Name        My Agent                                  │ │
│  │ Version     1.0.0                                     │ │
│  │ Slug        local/my-agent                            │ │
│  └───────────────────────────────────────────────────────┘ │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 4.3 功能实现计划

#### 阶段一：框架搭建（1天）

**目标**: 搭建 Hash 路由 SPA 框架

**任务**:
1. 创建目录结构
   - 创建 `templates/debug/` 目录
   - 创建 `css/`、`js/`、`modules/` 子目录

2. 实现核心框架
   - `index.html` - 主框架页面
   - `js/router.js` - Hash 路由管理
   - `js/api.js` - API 客户端
   - `js/state.js` - 状态管理
   - `js/app.js` - 应用入口

3. 实现基础样式
   - `css/base.css` - CSS 变量、重置样式
   - `css/layout.css` - 布局样式
   - `css/components.css` - 组件样式

4. 迁移 Chat 模块
   - 从原 `debug_page.html` 提取对话功能
   - 封装为 `modules/chat.js`

**验收标准**:
- 访问 `/#/` 显示对话页面
- 对话功能正常工作
- 路由切换无刷新

---

#### 阶段二：核心模块（2天）

**目标**: 实现核心功能模块

**任务**:
1. Tools 模块 (`modules/tools.js`)
   - 调用 `GET /tools` 获取分类工具列表
   - 分区域显示：内置、自定义、MCP
   - 工具详情弹窗

2. Skills 模块 (`modules/skills.js`)
   - 调用 `GET /skills` 获取技能列表
   - 技能详情显示
   - 技能加载状态

3. MCP 模块 (`modules/mcp.js`)
   - 调用 `GET /mcp` 获取 MCP 服务器列表
   - 显示每个服务器的工具列表
   - MCP 配置查看

4. Config 模块 (`modules/config.js`)
   - 新增 `GET /debug/config/env` 端点（脱敏）
   - 显示环境变量配置
   - 显示 OAF 配置

**验收标准**:
- 各模块页面正常显示
- 数据加载正确
- 详情弹窗可用

---

#### 阶段三：高级模块（2天）

**目标**: 实现高级功能模块

**任务**:
1. Memory 模块 (`modules/memory.js`)
   - 新增 `GET /debug/memory` 端点
   - 显示 MEMORY.md 内容
   - 显示 memory/ 文件列表
   - 记忆搜索功能

2. Database 模块 (`modules/database.js`)
   - 新增 `GET /debug/database/status` 端点
   - 显示连接状态
   - 显示表统计信息
   - 显示连接池状态

3. Workspace 模块 (`modules/workspace.js`)
   - 新增 `GET /debug/workspace` 端点
   - 显示工作区文件列表
   - 文件内容查看

4. Logs 模块 (`modules/logs.js`)
   - 新增 `GET /debug/logs` 端点
   - 显示系统日志
   - 日志级别筛选
   - 日志搜索

**验收标准**:
- 各模块功能完整
- API 端点正常响应
- 错误处理完善

---

#### 阶段四：优化完善（1天）

**目标**: 优化性能，完善细节

**任务**:
1. 后端改造
   - 修改 `DebugController` 支持新路由
   - 新增调试相关 API 端点
   - 完善错误处理

2. 性能优化
   - 模块懒加载
   - 数据缓存
   - 减少不必要的 API 调用

3. 用户体验
   - 加载状态显示
   - 错误提示优化
   - 快捷键支持

4. 文档完善
   - API 文档
   - 使用说明
   - 开发指南

**验收标准**:
- 页面加载 < 2秒
- 交互响应 < 100ms
- 无明显 bug

---

## 五、新功能设计

### 5.1 工具分类展示

#### 5.1.1 API设计

**修改 `/tools` 端点**:

```json
{
  "builtin": [
    {
      "name": "read_file",
      "description": "读取文件内容",
      "parameters": {
        "path": {"type": "string", "description": "文件路径", "required": true}
      },
      "readOnly": true,
      "concurrencySafe": true
    }
  ],
  "custom": [
    {
      "name": "get_current_time",
      "description": "返回指定时区当前时间",
      "parameters": {
        "timezone": {"type": "string", "description": "IANA时区", "required": true}
      },
      "readOnly": true,
      "concurrencySafe": true
    }
  ],
  "mcp": [
    {
      "server": "weather",
      "tools": [
        {
          "name": "get_weather",
          "description": "获取天气信息",
          "parameters": {
            "city": {"type": "string", "description": "城市名", "required": true}
          },
          "readOnly": true
        }
      ]
    }
  ]
}
```

#### 5.1.2 UI设计

```html
<div class="tools-panel">
  <h3>Tools</h3>
  
  <!-- 内置工具 -->
  <div class="tool-category">
    <h4>Built-in Tools <span class="count">26</span></h4>
    <div class="tool-list">
      <div class="tool-item" onclick="showToolDetail('read_file')">
        <span class="tool-name">read_file</span>
        <span class="tool-badge readOnly">readOnly</span>
      </div>
    </div>
  </div>
  
  <!-- 自定义工具 -->
  <div class="tool-category">
    <h4>Custom Tools <span class="count">2</span></h4>
    <div class="tool-list">
      <div class="tool-item" onclick="showToolDetail('get_current_time')">
        <span class="tool-name">get_current_time</span>
        <span class="tool-badge readOnly">readOnly</span>
      </div>
    </div>
  </div>
  
  <!-- MCP工具 -->
  <div class="tool-category">
    <h4>MCP Tools <span class="count">5</span></h4>
    <div class="tool-list">
      <div class="tool-item" onclick="showToolDetail('weather.get_weather')">
        <span class="tool-name">get_weather</span>
        <span class="tool-server">weather</span>
      </div>
    </div>
  </div>
</div>
```

### 5.2 记忆管理

#### 5.2.1 API设计

**新增 `/memory` 端点**:

```json
{
  "memory_md": "# Memory\n\n## User\n- 用户偏好设置...",
  "files": [
    {
      "name": "user_preferences.md",
      "type": "user",
      "description": "用户偏好设置",
      "size": 1024,
      "modified": "2024-01-15T10:30:00Z"
    }
  ],
  "stats": {
    "total_files": 5,
    "total_size": 10240
  }
}
```

**新增 `/memory/search` 端点**:

```json
// 请求
{
  "query": "用户偏好",
  "limit": 10
}

// 响应
{
  "results": [
    {
      "file": "user_preferences.md",
      "content": "用户偏好设置...",
      "score": 0.95
    }
  ]
}
```

#### 5.2.2 UI设计

```html
<div class="memory-panel">
  <h3>Memory</h3>
  
  <!-- 搜索框 -->
  <div class="memory-search">
    <input type="text" placeholder="Search memory..." onkeyup="searchMemory(this.value)">
  </div>
  
  <!-- MEMORY.md内容 -->
  <div class="memory-content">
    <h4>MEMORY.md</h4>
    <pre class="memory-text"># Memory\n\n## User\n- 用户偏好设置...</pre>
  </div>
  
  <!-- 记忆文件列表 -->
  <div class="memory-files">
    <h4>Memory Files</h4>
    <div class="file-list">
      <div class="file-item" onclick="showMemoryFile('user_preferences.md')">
        <span class="file-icon">📄</span>
        <span class="file-name">user_preferences.md</span>
        <span class="file-type">user</span>
      </div>
    </div>
  </div>
</div>
```

### 5.3 配置查看

#### 5.3.1 API设计

**新增 `/config/env` 端点**:

```json
{
  "llm": {
    "api_key": "sk-...abc123",
    "model_id": "gpt-4",
    "base_url": "https://api.openai.com/v1",
    "provider": "openai",
    "temperature": 0.7,
    "max_tokens": 4096,
    "timeout": 120
  },
  "server": {
    "host": "0.0.0.0",
    "port": 8100
  },
  "checkpoint": {
    "jdbc_url": "jdbc:mysql://127.0.0.1:3307/agent_manager_test",
    "username": "agent_manager"
  },
  "config_dir": "/config"
}
```

**新增 `/config/oaf` 端点**:

```json
{
  "name": "Agent Name",
  "vendorKey": "local",
  "agentKey": "agent",
  "version": "1.0.0",
  "slug": "local/agent",
  "description": "Agent描述",
  "tools": ["read_file", "write_file"],
  "deniedTools": [],
  "skills": [...],
  "mcpServers": [...],
  "agents": [...]
}
```

#### 5.3.2 UI设计

```html
<div class="config-panel">
  <h3>Configuration</h3>
  
  <!-- 环境变量 -->
  <div class="config-section">
    <h4>Environment Variables</h4>
    <div class="config-group">
      <h5>LLM Configuration</h5>
      <div class="config-item">
        <span class="config-key">LLM_API_KEY</span>
        <span class="config-value">sk-...abc123</span>
      </div>
    </div>
  </div>
  
  <!-- OAF配置 -->
  <div class="config-section">
    <h4>OAF Configuration</h4>
    <div class="config-item">
      <span class="config-key">Name</span>
      <span class="config-value">Agent Name</span>
    </div>
  </div>
</div>
```

### 5.4 数据库状态

#### 5.4.1 API设计

**新增 `/database/status` 端点**:

```json
{
  "connected": true,
  "database": "agent_manager_test",
  "host": "127.0.0.1:3307",
  "tables": {
    "agent_state": {
      "rows": 150,
      "size": "2.5MB"
    },
    "agent_fs": {
      "rows": 890,
      "size": "15.2MB"
    }
  },
  "connection_pool": {
    "active": 5,
    "idle": 5,
    "total": 10
  }
}
```

#### 5.4.2 UI设计

```html
<div class="database-panel">
  <h3>Database Status</h3>
  
  <!-- 连接状态 -->
  <div class="db-status">
    <span class="status-dot connected"></span>
    <span class="status-text">Connected</span>
  </div>
  
  <!-- 表统计 -->
  <div class="db-tables">
    <h4>Tables</h4>
    <div class="table-item">
      <span class="table-name">agent_state</span>
      <span class="table-rows">150 rows</span>
      <span class="table-size">2.5MB</span>
    </div>
  </div>
  
  <!-- 连接池 -->
  <div class="db-pool">
    <h4>Connection Pool</h4>
    <div class="pool-stats">
      <span>Active: 5</span>
      <span>Idle: 5</span>
      <span>Total: 10</span>
    </div>
  </div>
</div>
```

---

## 六、后端改造

### 6.1 DebugController 改造

**当前实现**：

```java
@RestController
public class DebugController {
    private final Resource debugPage = new ClassPathResource("templates/debug_page.html");

    @GetMapping(value = "/debug", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> debugPage() {
        // 返回单个 HTML 文件
    }
}
```

**改造后**：

```java
@RestController
@RequestMapping("/debug")
public class DebugController {
    
    // 主框架页面
    @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> debugPage() {
        return ResponseEntity.ok(loadResource("templates/debug/index.html"));
    }
    
    // 静态资源
    @GetMapping("/css/{filename}")
    public ResponseEntity<Resource> getCss(@PathVariable String filename) {
        return serveStatic("templates/debug/css/" + filename, "text/css");
    }
    
    @GetMapping("/js/{filename}")
    public ResponseEntity<Resource> getJs(@PathVariable String filename) {
        return serveStatic("templates/debug/js/" + filename, "application/javascript");
    }
    
    @GetMapping("/modules/{filename}")
    public ResponseEntity<Resource> getModule(@PathVariable String filename) {
        return serveStatic("templates/debug/modules/" + filename, "application/javascript");
    }
}
```

### 6.2 新增调试 API 端点

#### 6.2.1 环境变量配置

```java
@GetMapping("/debug/config/env")
public Map<String, Object> getEnvConfig() {
    return Map.of(
        "llm", Map.of(
            "api_key", maskSecret(props.llm().apiKey()),
            "model_id", props.llm().modelId(),
            "base_url", props.llm().baseUrl(),
            "provider", props.llm().provider(),
            "temperature", props.llm().temperature(),
            "max_tokens", props.llm().maxTokens(),
            "timeout", props.llm().timeout()
        ),
        "server", Map.of(
            "host", props.server().host(),
            "port", props.server().port()
        ),
        "checkpoint", Map.of(
            "jdbc_url", props.checkpoint().jdbcUrl(),
            "username", props.checkpoint().username()
        ),
        "config_dir", props.configDir()
    );
}

private String maskSecret(String secret) {
    if (secret == null || secret.length() < 8) return "***";
    return secret.substring(0, 4) + "..." + secret.substring(secret.length() - 4);
}
```

#### 6.2.2 数据库状态

```java
@GetMapping("/debug/database/status")
public Map<String, Object> getDatabaseStatus() {
    try {
        var stats = dataSource.getConnection().getMetaData();
        var poolStats = getHikariPoolStats();
        
        return Map.of(
            "connected", true,
            "database", stats.getDatabaseProductName(),
            "host", extractHost(stats.getURL()),
            "tables", getTableStats(),
            "connection_pool", poolStats
        );
    } catch (Exception e) {
        return Map.of("connected", false, "error", e.getMessage());
    }
}
```

#### 6.2.3 记忆管理

```java
@GetMapping("/debug/memory")
public Map<String, Object> getMemory() {
    // 通过 agent 的文件系统读取记忆内容
    var memoryMd = agent.getMemoryContent();
    var memoryFiles = agent.getMemoryFiles();
    
    return Map.of(
        "memory_md", memoryMd,
        "files", memoryFiles,
        "stats", Map.of(
            "total_files", memoryFiles.size(),
            "total_size", calculateTotalSize(memoryFiles)
        )
    );
}
```

#### 6.2.4 工作区状态

```java
@GetMapping("/debug/workspace")
public Map<String, Object> getWorkspace() {
    var workspacePath = Path.of(props.configDir(), ".agentscope/workspace");
    var files = listWorkspaceFiles(workspacePath);
    
    return Map.of(
        "path", workspacePath.toString(),
        "files", files,
        "exists", Files.exists(workspacePath)
    );
}
```

#### 6.2.5 系统日志

```java
@GetMapping("/debug/logs")
public Map<String, Object> getLogs(
    @RequestParam(defaultValue = "all") String level,
    @RequestParam(defaultValue = "100") int limit
) {
    // 读取日志文件或内存日志
    var logs = logAppender.getLogs(level, limit);
    
    return Map.of(
        "logs", logs,
        "total", logs.size(),
        "level", level
    );
}
```

### 6.3 API 端点汇总

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/debug` | 主框架页面 |
| `GET` | `/debug/css/{file}` | CSS 静态资源 |
| `GET` | `/debug/js/{file}` | JS 静态资源 |
| `GET` | `/debug/modules/{file}` | 模块 JS 文件 |
| `GET` | `/debug/config/env` | 环境变量（脱敏） |
| `GET` | `/debug/database/status` | 数据库状态 |
| `GET` | `/debug/memory` | 记忆内容 |
| `GET` | `/debug/workspace` | 工作区状态 |
| `GET` | `/debug/logs` | 系统日志 |

---

## 七、实施建议

### 7.1 开发原则

1. **渐进式重构**: 不要一次性重写所有功能，分阶段实施
2. **向后兼容**: 保持现有API端点的兼容性
3. **模块化设计**: 每个功能模块独立，便于维护和扩展
4. **用户体验优先**: 关注用户体验，提供直观的操作界面

### 7.2 技术选型

1. **前端框架**: 保持原生JavaScript，不引入框架
2. **样式方案**: 保持现有CSS风格，使用CSS变量
3. **状态管理**: 简单的JavaScript对象，不引入复杂状态管理库
4. **API通信**: 使用fetch API，保持现有模式

### 7.3 测试策略

1. **功能测试**: 每个新功能都要有对应的测试用例
2. **兼容性测试**: 确保在不同浏览器和设备上正常工作
3. **性能测试**: 确保页面加载和交互响应速度
4. **安全测试**: 确保敏感信息不泄露

### 7.4 文档要求

1. **功能文档**: 每个功能模块都要有详细的使用说明
2. **API文档**: 所有API端点都要有完整的文档
3. **开发文档**: 代码结构和开发规范文档
4. **用户手册**: 面向最终用户的使用手册

---

## 八、总结

### 8.1 当前状态

- **已实现功能**: 14个主要功能模块
- **代码规模**: 728行HTML/CSS/JavaScript
- **功能覆盖率**: 约60%的完整功能

### 8.2 重构目标

- **功能覆盖率**: 提升至95%以上
- **代码质量**: 模块化、可维护、可扩展
- **用户体验**: 直观、高效、友好
- **性能指标**: 页面加载<2秒，交互响应<100ms

### 8.3 预期收益

1. **开发效率**: 模块化设计提升开发效率30%
2. **调试效率**: 完整功能提升调试效率50%
3. **用户体验**: 直观界面提升用户满意度
4. **维护成本**: 清晰架构降低维护成本40%

### 8.4 风险评估

1. **技术风险**: 低，基于现有技术栈
2. **时间风险**: 中，需要合理安排开发计划
3. **资源风险**: 低，主要依赖现有开发人员
4. **兼容性风险**: 低，保持向后兼容

---

## 附录

### A. 参考文档

1. [Agent Framework AGENTS.md](../AGENTS.md)
2. [API文档](./api.md)
3. [AgentScope 2.0文档](https://github.com/modelscope/agentscope)

### B. 相关文件

1. `debug_page.html` - 当前调试页面
2. `DebugController.java` - 调试页面控制器
3. 各Controller文件 - API端点实现

### C. 更新记录

- **2024-01-15**: 初始版本创建
- **2024-01-20**: 完成功能分析和重构计划
- **2024-01-25**: 完成新功能设计