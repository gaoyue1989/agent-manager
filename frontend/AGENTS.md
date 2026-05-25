# Frontend — AGENTS.md

## 二级模块概述

React 19 + Next.js 16 前端应用，使用 App Router + Tailwind CSS 3，提供 Agent 管理 UI：Dashboard 概览、Agent 列表、配置创建 (表单/JSON/YAML)、详情操作面板、代码预览、Pod 状态、聊天测试。

<!-- BEGIN:nextjs-agent-rules -->
> ⚠ This is NOT the Next.js you know. This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` before writing any code. Heed deprecation notices.
<!-- END:nextjs-agent-rules -->

## 基础规则

严格按用户需求执行，不擅自加功能、不脑补逻辑、不画蛇添足；只输出可直接运行的完整代码，拒绝伪代码。需求模糊主动提问，输出无多余闲聊，全程对齐项目现有代码风格、目录结构、命名规范。

## 开发流程

先看项目目录和现有关联代码，理清逻辑再编码；只修改指定文件与逻辑，不改动无关代码、不整文件重写。

## 代码规范

命名语义化，禁止硬编码密钥、魔法数字；网络、IO、数据库操作必做判空、边界校验和异常捕获；优先复用现有工具，不私自升级框架、乱加依赖；复杂逻辑加中文注释。

## 输出格式

代码块标语言、改文件标路径；保留配置原有缩进，不搞多余排版；完工自动清理调试日志、临时测试代码。

## 安全约束

不随意改 Git、Docker 及系统配置；禁用高危删除命令，敏感信息用占位符；不做删核心文件、清依赖等破坏性操作，环境报错先给排查方案。

---

## 目录结构

```
frontend/
├── package.json                # 依赖: next 16, react 19, tailwindcss 3
├── tsconfig.json               # TypeScript 配置
├── next.config.ts              # Next.js 配置 (output: 'standalone')
├── tailwind.config.mjs         # Tailwind CSS 配置
├── postcss.config.mjs          # PostCSS 配置
├── eslint.config.mjs           # ESLint 配置
└── src/
    ├── app/                    # App Router 页面
    │   ├── layout.tsx          # 根布局 (zh-CN, 顶部导航)
    │   ├── globals.css         # 全局样式
    │   ├── page.tsx            # Dashboard (/)
    │   └── agents/
    │       ├── page.tsx        # Agent 列表 (/agents)
    │       ├── create/page.tsx # 创建 Agent (/agents/create)
    │       └── [id]/
    │           ├── page.tsx    # Agent 详情 (/agents/[id])
    │           └── edit/page.tsx # 编辑 Agent (/agents/[id]/edit)
    └── lib/
        └── api.ts              # API 客户端 (fetch 封装)
```

## Standalone 构建与部署

Next.js 配置为 `output: 'standalone'`，PM2 运行 `frontend/.next/standalone/server.js`。

`npm run build` 仅生成 `.next/` 输出，需额外将构建产物复制到 standalone 目录：

```
cp .next/BUILD_ID .next/*.json .next/*.js .next/standalone/.next/
cp -r .next/static .next/standalone/.next/static
cp -r .next/server .next/standalone/.next/server
cp -r public .next/standalone/public
```

可通过 `make build-frontend` 一键构建+复制，或 `make frontend-restart` 构建+重启 PM2。

---

## 技术依赖 (package.json)

```
next 16.2.4                   # App Router
react 19.2.4 / react-dom      # UI 框架
axios 1.15.2                  # (已安装但未使用，实际用原生 fetch)
tailwindcss 3.4.19            # 样式框架
typescript 5                  # 类型检查
```

## 页面路由

所有页面均为 Client Component (`'use client'`)，无 SSR：

| 路由 | 文件 | 功能 |
|------|------|------|
| `/` | `app/page.tsx` | Dashboard: Agent 统计概览 (总数/已发布/已下线/草稿/错误) |
| `/agents` | `app/agents/page.tsx` | Agent 列表: 分页表格，状态筛选 |
| `/agents/create` | `app/agents/create/page.tsx` | 创建 Agent: 表单/JSON/YAML 三种输入模式 |
| `/agents/[id]` | `app/agents/[id]/page.tsx` | Agent 详情: 操作面板、代码预览、Pod 状态、聊天测试 |
| `/agents/[id]/edit` | `app/agents/[id]/edit/page.tsx` | 编辑 Agent: JSON 配置编辑 |

## 布局 (app/layout.tsx)

- 根布局，`<html lang="zh-CN">`
- 固定顶部导航栏: 首页 / Agent 列表 / 创建 Agent
- `<main>{children}</main>` 插槽渲染页面

## API 客户端 (lib/api.ts)

- 基于原生 `fetch`，非 axios
- 基础 URL: `NEXT_PUBLIC_API_URL` 环境变量，默认 `http://100.66.1.5:8080/api/v1`
- `api.agents` 对象封装全部 17 个端点方法
- 返回值类型均为 `any`，无请求/响应类型定义
- 错误处理: 非 2xx 抛出异常，message 为响应 body

### 完整端点列表

| 方法 | 路由 | 说明 |
|------|------|------|
| `list(params)` | `GET /agents` | 列表查询 |
| `get(id)` | `GET /agents/:id` | 详情 |
| `create(config, configType, options)` | `POST /agents` | 创建 |
| `update(id, config, options)` | `PUT /agents/:id` | 更新 |
| `delete(id)` | `DELETE /agents/:id` | 删除 |
| `generate(id)` | `POST /agents/:id/generate` | 代码生成 |
| `getCode(id)` | `GET /agents/:id/code` | 获取代码 |
| `getDeployments(id)` | `GET /agents/:id/deployments` | 部署历史 |
| `build(id)` | `POST /agents/:id/build` | 构建镜像 |
| `deploy(id)` | `POST /agents/:id/deploy` | 部署 |
| `publish(id)` | `POST /agents/:id/publish` | 发布 |
| `unpublish(id)` | `POST /agents/:id/unpublish` | 下线 |
| `imageInfo(id)` | `GET /agents/:id/image-info` | 镜像信息 |
| `podStatus(id)` | `GET /agents/:id/pod-status` | Pod 状态 |
| `chat(id, data)` | `POST /agents/:id/chat` | 聊天测试 |
| `podFiles(id)` | `GET /agents/:id/pod-files` | 挂载文件列表 |
| `podFile(id, key)` | `GET /agents/:id/pod-file?key=` | 挂载文件内容 |

## 状态管理

- 无全局状态库 (无 Redux/Zustand/Context)
- 所有状态由 `useState` + `useEffect` 管理
- 无缓存层，每次导航重新请求
- 变更后通过 `load()` 函数重新拉取数据

## 组件架构

- 无 `components/` 共享组件目录，所有 UI 内联在页面文件中
- 仅 AgentDetail 页内含有一个本地 `ActionBtn` 辅助组件
- 样式全部使用 Tailwind CSS utility classes

## 页面详情

### Dashboard (`/`, app/page.tsx)
- 调用 `api.agents.list()` 获取全部 Agent
- 统计 5 类数量: 总数 / published / unpublished / draft / error
- 5 列 grid 展示彩色统计卡片
- 底部分隔线 + "创建 Agent" 链接

### AgentList (`/agents`, app/agents/page.tsx)
- 调用 `api.agents.list({ limit: 100 })` 获取列表
- 表格列: 名称、状态 (彩色 badge)、版本、更新时间、操作 (详情/编辑/删除)
- 支持 `?status=` URL 参数筛选
- **删除功能**: 点击"删除"链接 → 确认框 → `DELETE /agents/:id` → 刷新列表

### CreateAgent (`/agents/create`, app/agents/create/page.tsx)
- 两种输入模式 Tab 切换: 表单 / YAML
- **表单模式**：生成 OAF v0.8.0 AGENTS.md 格式
  - Identity 区块: name, vendorKey, agentKey, version
  - Metadata 区块: description, author, license, tags
  - Skills 区块: 动态添加/删除技能 (name, source, version, required)
  - MCP Servers 区块: 动态添加/删除 MCP 服务器
  - Tools 区块: checkbox 选择工具
  - Config 区块: temperature, max_tokens
  - Instructions 区块: Markdown 系统指令
- **YAML 模式**: 直接编辑 OAF AGENTS.md
- 提交 `POST /agents` (config_type: 'oaf')，成功后 `router.push(`/agents/${id}`)`
- **注意**: LLM 配置 (API Key/Model/Endpoint) 通过环境变量注入，不在表单中配置

### AgentDetail (`/agents/[id]`, app/agents/[id]/page.tsx)
最复杂的页面，包含：
- **基本信息卡片**: 描述、模型、系统提示词
- **操作卡片**: 状态相关按钮 (生成代码 → 构建镜像 → 部署 → 发布/下线/删除)
  - 每个按钮有加载态 (`actionLoading`)
  - `ActionBtn` 本地组件封装
  - **删除按钮**: 红色按钮，点击确认后 `DELETE /agents/:id`，成功后跳转到列表页
- **镜像信息卡片**: 条件渲染 (有 build 记录时显示)
- **Pod 状态卡片**: published 状态显示 (名称/就绪/状态/重启/年龄/IP)
- **挂载文件卡片**: published + 挂载模式显示，通过 K8s ConfigMap API 读取
  - `FileTree` 组件：可折叠/展开目录树，点击文件名弹窗查看文件内容
  - `FileTreePlaceholder` 组件：未加载时的占位按钮
  - `viewingFile` 状态：控制文件内容模态框
- **聊天测试卡片**: published 状态显示
  - 聊天历史 (用户/助手气泡)
  - 延迟显示
  - 输入框 + 发送按钮
- **代码预览卡片**: 有生成代码时显示 (深色背景 pre 块)
- **部署历史表格**: 有部署记录时显示 (沙箱名/版本/状态/时间/端点)

### EditAgent (`/agents/[id]/edit`, app/agents/[id]/edit/page.tsx)
- 加载 agent 的 `config` JSON 字符串
- 深色背景全高 textarea 编辑
- 保存 → `PUT /agents/:id` → 返回详情页
- 取消 → `router.back()`

