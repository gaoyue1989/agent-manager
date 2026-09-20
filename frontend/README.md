# OAF 服务发布平台 — 前端（platform-frontend）

Next.js 16 + React 19 + Tailwind 管理界面：服务列表、发布向导（上传 OAF 包 → 选镜像 → 编辑环境变量）、服务详情（状态轮询 / A2A 注册信息 / env 滚动重启）与发布助手对话页 `/assistant`（HITL 确认卡、durable SSE 断线续传）。

## 开发

```bash
npm install
npm run dev                    # http://localhost:3000（rewrites 反代 platform-backend 与 release-agent）
npm run lint && npm run build  # CI 门禁同款检查
```

- 目录结构、页面与 API 对接约定见 [AGENTS.md](AGENTS.md)
- 对话页 SSE/确认流协议见 [../agent-framework/docs/api-frontend-sse.md](../agent-framework/docs/api-frontend-sse.md)
