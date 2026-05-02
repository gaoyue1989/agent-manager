# E2E Testing — Agent Manager

## 模块概述

E2E (End-to-End) 测试模块，使用 **Puppeteer** 对前端页面进行自动化功能验证和截图。
主要用于验证 Agent 管理平台的完整业务流程，包括 Agent 创建、代码生成、镜像构建、部署、发布/下线以及聊天测试。

### 技术栈

| 组件 | 技术选型 | 版本 |
|------|---------|------|
| 测试框架 | Puppeteer | Latest |
| 浏览器 | Chromium (Headless) | Bundled |
| 运行环境 | Node.js | v25.9.0 |
| Mock 服务 | Python FastAPI/Starlette | 端口 9998 |

---

## 目录结构

```
e2e/
├── AGENTS.md                 # 本文档
├── e2e-test.js               # E2E 测试脚本
├── mock_mcp_server.py        # Mock MCP Server (用于测试 MCP 集成)
├── test_skills.zip           # Skill 测试包 (包含 2 个 Skill)
├── test_skills/              # Skill 源文件目录
│   ├── e2e-test-skill/
│   │   └── SKILL.md
│   └── second-skill/
│       └── SKILL.md
├── screenshots/              # 测试截图
│   ├── metadata.json         # 截图元数据 (索引、状态、描述)
│   └── *.png                 # 按功能点命名的截图文件
├── package.json              # Puppeteer 依赖
├── package-lock.json         # 依赖锁定文件
├── node_modules/             # Puppeteer 及依赖
└── screenshot.js             # 旧版截图脚本 (已废弃)
```

---

## 截图规则

### 1. 截图命名规范
每个测试用例执行后自动截图，文件名格式：
```
{序号}-{时间戳}-{测试名称}.png
```
- 序号：2 位数字，从 01 开始递增
- 时间戳：ISO 8601 格式，去除 `:` 和 `.`
- 测试名称：测试用例名称，空格替换为 `-`

示例：`01-2026-05-02T01-00-00-000Z-E4-1-Nginx-首页访问.png`

### 2. 截图元数据
所有截图信息记录在 `screenshots/metadata.json`：
```json
[
  {
    "index": 1,
    "filename": "01-2026-05-02T01-00-00-000Z-E4-1-Nginx-首页访问.png",
    "name": "E4-1: Nginx 首页访问",
    "status": "PASS",
    "description": "通过 Nginx 8911 端口访问首页，验证反向代理配置正确",
    "timestamp": "2026-05-02T01:00:00.000Z",
    "url": "http://localhost:8911/"
  }
]
```

### 3. 截图触发时机
- **每个测试用例**执行后自动截图
- **页面导航**后等待 2 秒确保渲染完成
- **操作执行**后（如点击按钮、输入文本）等待相应时间
- **测试完成**后截取全页面截图

### 4. 截图分析
截图用于以下目的：
- **功能验证**: 确认页面元素正确显示（工具标签、MCP 配置、Skill 元数据等）
- **状态确认**: 验证 Agent 状态流转（draft → generated → built → deployed → published）
- **问题排查**: 测试失败时通过截图定位 UI 异常
- **文档记录**: 作为测试报告附件，证明功能正常工作

---

## 测试用例

### E4: Nginx 8911 统一端口
| 用例 | 验证内容 | 截图说明 |
|------|---------|---------|
| E4-1 | 通过 `http://localhost:8911` 访问首页 | 首页正常渲染，包含导航栏和统计卡片 |
| E4-2 | 通过 Nginx 转发 API 请求 | API 返回正常 JSON 数据 |

### E1: 创建 Agent (MCP + Enabled Tools)
| 用例 | 验证内容 | 截图说明 |
|------|---------|---------|
| E1-1 | API 创建 Agent，配置包含 MCP + 工具 | 创建成功，返回 Agent ID |
| E1-2 | 详情页展示 Agent 基本信息 | 详情页显示名称、状态、配置类型 |
| E1-3 | 详情页展示启用工具列表 | 工具标签正确显示 (write_todos, ls 等) |
| E1-4 | 详情页展示 MCP 配置 | MCP URL 和传输协议正确显示 |

### E2: 代码生成验证
| 用例 | 验证内容 | 截图说明 |
|------|---------|---------|
| E2-1 | 触发代码生成 API | 代码生成成功，状态为 success |
| E2-2 | 生成代码包含 MCP 客户端初始化 | agent.py 包含 `langchain_mcp_adapters` 或 `get_mcp_tools` |
| E2-3 | 生成代码包含工具配置 | agent.py 包含 `ENABLED_TOOLS` 和 `EXCLUDED_TOOLS` |
| E2-4 | Agent 使用工具初始化 | `create_deep_agent()` 使用 `all_tools` |
| E2-5 | 生成代码 Python 语法正确 | 通过 `py_compile` 检查 |

### E3: Skill 上传验证
| 用例 | 验证内容 | 截图说明 |
|------|---------|---------|
| E3-1 | 上传 test_skills.zip | 解析到 2 个 Skill (e2e-test-skill, second-skill) |
| E3-2 | Skill 元数据解析 | name/description 正确提取 |
| E3-3 | Skill 元数据字段验证 | name 和 description 字段存在 |
| E3-4 | Skill 列表查询 | GET API 返回已上传的 Skill 列表 |

### F1-F3: 已有 Agent 验证 (Agent 2)
| 用例 | 验证内容 | 截图说明 |
|------|---------|---------|
| F1 | 镜像信息展示 | 镜像地址 (172.20.0.1:5001)、版本、构建状态 |
| F2 | Pod 状态监控 + 刷新 | Pod 名称、状态、就绪、重启、IP |
| F3 | 聊天测试 | 发送消息后收到 Agent 响应 |

---

## 运行方式

### 前置条件
1. 后端服务运行在 `localhost:8080`
2. 前端服务运行在 `localhost:3000`
3. Nginx 运行在 `localhost:8911`，代理前端和后端
4. Mock MCP Server 运行在 `localhost:9998`

### 启动 Mock MCP Server
```bash
cd /root/agent-manager
/root/agent-manager/codegen/venv/bin/python3 e2e/mock_mcp_server.py 9998
```

### 运行测试
```bash
# 方式 1: 从 e2e 目录运行
cd e2e && node e2e-test.js

# 方式 2: 通过 Makefile
make test-e2e
```

### 环境变量
| 变量 | 说明 | 默认值 |
|------|------|--------|
| `BASE_URL` | 测试基础 URL | `http://localhost:8911` |
| `MCP_PORT` | Mock MCP Server 端口 | `9998` |

---

## 截图查看

测试完成后，截图保存在 `e2e/screenshots/` 目录：
```bash
ls -la e2e/screenshots/
cat e2e/screenshots/metadata.json | python3 -m json.tool
```

每个截图文件可直接用图片查看器打开，或通过浏览器访问。

---

## 维护指南

1. **添加新测试**: 在 `e2e-test.js` 中追加测试逻辑，使用 `log()` 函数自动截图
2. **更新截图**: 测试脚本自动覆盖同名截图，metadata.json 记录所有截图信息
3. **清理**: 测试完成后，metadata.json 自动清理，旧截图保留供对比
4. **环境检查**: 确保前后端服务、Nginx、Mock MCP Server 正常运行后再执行测试
