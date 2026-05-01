# E2E Testing — AGENTS.md

## 模块概述

E2E (End-to-End) 测试模块，使用 **Puppeteer** 对前端页面进行自动化功能验证和截图。
主要用于验证 Agent 管理平台的完整业务流程，包括 Agent 创建、代码生成、镜像构建、部署、发布/下线以及聊天测试。

### 技术栈

| 组件 | 技术选型 | 版本 |
|------|---------|------|
| 测试框架 | Puppeteer | Latest |
| 浏览器 | Chromium (Headless) | Bundled |
| 运行环境 | Node.js | v25.9.0 |

---

## 目录结构

```
e2e/
├── AGENTS.md                 # 本文档
├── e2e-test.js               # E2E 测试脚本
├── 12-e2e-test-report.md     # E2E 测试报告
└── screenshots/              # 测试截图
    ├── home.png
    ├── agents_list.png
    ├── create_form.png
    ├── agent_detail.png
    ├── agent_edit.png
    ├── e2e-full-page.png
    └── ...
```

---

## 测试脚本

### e2e-test.js

**功能**:
- 自动导航至 Agent 详情页。
- 验证镜像信息展示 (F1)。
- 验证 Pod 状态监控及刷新按钮 (F2)。
- 验证聊天测试区域及消息发送 (F3)。
- 生成全页面截图 (`e2e-full-page.png`)。

**运行方式**:
```bash
cd /root/agent-manager
node e2e/e2e-test.js
```

**前置条件**:
- 后端服务运行在 `localhost:8080`。
- 前端服务运行在 `localhost:3000`。
- 已安装依赖: `npm install puppeteer` (在根目录)。

**依赖**:
- 依赖根目录的 `node_modules/puppeteer`。
- 使用 `--no-sandbox` 参数运行（适用于 Docker/Root 环境）。

---

## 测试报告

### 12-e2e-test-report.md

包含详细的测试用例、执行结果、截图证据和测试结论。
每次执行测试后应更新此报告。

**最新结果**:
- **通过率**: 5/5 (100%)
- **状态**: ✅ 通过

---

## 截图说明

`screenshots/` 目录包含所有测试过程中生成的截图：
- **功能验证截图**: 各页面核心功能展示。
- **生命周期截图**: Agent 从生成到发布的各阶段状态。
- **E2E 全页截图**: `e2e-full-page.png` 包含完整页面的验证结果。

---

## 维护指南

1. **添加新测试**: 在 `e2e-test.js` 中追加测试逻辑，使用 `findByText` 辅助函数定位元素。
2. **更新截图**: 测试脚本会自动覆盖同名截图。
3. **清理**: 测试完成后，手动清理临时截图（如不需要保留）。
4. **环境检查**: 确保前后端服务正常运行后再执行测试。
