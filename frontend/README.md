# OAF 服务发布平台 — 前端（platform-frontend）

Next.js 16 + React 19 + Tailwind 控制台（JWT 登录）：用户侧 `/console`（工作空间 Agent 全生命周期：创建/详情/导出/模板 + 资产中心 models/tools/skills/sandbox/memory/knowledge/credentials + 集成/可观测/工作空间配置），运维侧 `/admin`（发布/服务/包/用户/工作空间/审计/平台设置），发布助手对话页 `/admin/assistant`（HITL 确认卡、durable SSE 断线续传）；旧路径（`/publish`、`/packages`、`/services/[id]`、`/assistant` 等）307 至对应 `/admin/*`。

## 开发

```bash
npm install
npm run dev                    # http://localhost:3000（src/proxy.ts 同源反代 platform-backend 与 release-agent）
npm run lint && npm run test && npm run build  # CI 门禁同款检查（lint + node:test 单测 + 构建）
```

- 目录结构、页面与 API 对接约定见 [AGENTS.md](AGENTS.md)
- 对话页 SSE/确认流协议见 [../agent-framework/docs/api-frontend-sse.md](../agent-framework/docs/api-frontend-sse.md)
