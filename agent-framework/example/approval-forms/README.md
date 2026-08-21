# 审批 Demo（approval-forms）

端到端演示 Agent 审批场景的完整闭环：

```
对话收集申请 → MCP App 表单卡片确认 → HITL 人工审批 → 提交完成
```

## 演示流程

1. 用户在对话中口述申请需求（标题 + 申请文本 + 文件链接）
2. LLM 调用 `create_application` 创建申请单，再调用 `show_application_form` 触发确认卡片
3. 表单卡片（MCP App，iframe 内嵌）展示申请详情，用户核对后点击「确认表单」
4. 用户回到对话发送「提交申请」，Agent 调用 `submit_application`（配置为 `ask` 权限）
5. 前端弹出 **HITL 人工审批确认卡**，批准后工具真正执行，申请单状态变为 `submitted`

## 目录结构

```
approval-forms/
├── agent-config/                    # agent-framework 配置目录（AGENT_CONFIG_DIR 指向这里）
│   ├── AGENTS.md                    # OAF 配置 + 系统提示（显式禁用 LLM 直调确认工具）
│   ├── skills/approval-flow/SKILL.md
│   └── mcp-configs/approval/config.yaml   # MCP 连接 + 工具权限（submit_application: ask）
├── mock-mcp/approval_mcp.py         # 模拟审批后端 MCP 服务器（:8813，仅标准库）
├── ui/                              # 演示前端（同源页面，经 proxy.py 代理 API）
├── proxy.py                         # 静态服务 + 反代 :8100（:8913，零依赖）
├── start.sh                         # 一键启动 mock MCP + proxy
└── e2e/approval-e2e.js              # Puppeteer 端到端测试
```

## 关键设计

| 环节 | 实现 |
|------|------|
| 表单卡片 | MCP App 规范：`ui://approval/application-form.html` 资源 + iframe 沙箱宿主（复用 agent-framework 的 mcp-app-host.js） |
| 工具权限 | `confirm_application` 为普通 allow 工具（SKILL.md 禁止 LLM 直调，仅卡片经 `/mcp/{server}/tools/{tool}` 代理调用）；`submit_application` 配 `ask` 走 HITL 确认流 |
| 状态同步 | 卡片确认后调 `ui/update-model-context` 静默注入模型上下文，Agent 下一轮即可感知 |
| 同源部署 | proxy.py 将页面与 API 合并为同源 :8913，MCP 资源代理/工具代理/CORS 全部免配置 |

> ⚠️ 不要给 config.yaml 加 `ui.app_only` / `permissions.read_only`——registerReadOnly 路径会让工具注册为只读，
> `checkPermissions` 直接放行，HITL ask 确认流被短路（详见 agent-framework 的 McpToolRegistrar）。

## 启动

前置：agent-framework 已构建并可用（`mvn package -DskipTests`），MySQL :3307、LLM 配置就绪。

```bash
cd agent-framework/example/approval-forms

# 1) 启动 mock MCP (:8813) + 页面/代理 (:8913)
./start.sh
# 或者手动：python3 mock-mcp/approval_mcp.py &  python3 proxy.py --port 8913 --backend 127.0.0.1:8100

# 2) 启动 agent-framework，配置指向本示例（另开终端）
cd agent-framework
LLM_API_KEY=... LLM_MODEL_ID=... LLM_BASE_URL=... \
  AGENT_CONFIG_DIR=/root/agent-manager/agent-framework/example/approval-forms/agent-config \
  java -jar target/agent-framework-2.1.0.jar
```

浏览器访问 <http://localhost:8913/>，点「＋」新建会话，输入申请需求即可演示。

## E2E 测试

```bash
cd agent-framework/example/approval-forms/e2e
npm install          # 或 ln -s 复用仓库已有 puppeteer
node approval-e2e.js # 截图输出到 e2e/screenshots/
```

覆盖断言：表单卡片挂载 → iframe 内表单渲染 → 点击确认 → HITL 确认卡出现 → 批准 → 提交结果回流。

## 接口速览

> 完整部署与 API 说明见 [docs/deployment-guide.md](docs/deployment-guide.md)；
> 历史踩坑与根因分析见 [docs/troubleshooting.md](docs/troubleshooting.md)。

| 路径 | 说明 |
|------|------|
| `POST /threads/{sid}/chat` | 单次流对话（SSE 直吐 Agent 事件词表；body `{message, userId}`） |
| `POST /threads/{sid}/confirm-stream` | HITL 确认流（body `{results:[{tool_call_id, confirmed, accept_rule}]}`；恢复执行事件） |
| `GET /threads` | 会话列表 |
| `GET /threads/{sid}/history` | 会话历史（附 `pendingConfirm`） |
| `/mcp/{server}/resources/ui?uri=...` | MCP 资源代理（卡片 HTML 拉取） |
| `/mcp/{server}/tools/{tool}` | 卡片工具调用代理（ask 工具 403 + needsConfirm 确认流） |
| `POST /mcp/ui-context` | 卡片状态静默更新模型上下文 |