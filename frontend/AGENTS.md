# Frontend — AGENTS.md

## 二级模块概述

OAF 发布平台前端（React 19 + Next.js 16 + Tailwind 3，容器化 standalone）。3 个业务页面 + 发布助手对话页，全部 Client Component，无状态库（useState+useEffect+轮询），样式 Tailwind utility 单文件内联。

> ⚠ This is NOT the Next.js you know. This version has breaking changes — read `node_modules/next/dist/docs/` guides before writing code.

## 页面

| 路由 | 文件 | 说明 |
|------|------|------|
| `/` | src/app/page.tsx | 服务列表：状态轮询(5s)、行操作（下线/上线/重发布/删除） |
| `/publish` | src/app/publish/page.tsx | 发布向导：上传 zip/选包 → 选镜像 → env 键值对编辑器（增删行）→ 提交 |
| `/services/[id]` | src/app/services/[id]/page.tsx | 详情：状态/Pod 轮询、Agent Card、env 编辑保存（全量覆盖=滚动重启）、事件时间线 |
| `/assistant` | src/app/assistant/page.tsx | 发布助手对话：无状态单次流 SSE + HITL 确认卡片 |

## API 客户端（src/lib/api.ts）

对齐后端 REST 契约（15 方法）：packages/images/services 三组。统一 `{code,message,data}` 包装；错误抛 Error(message)。

## 关键实现约定

- **同源反代**（next.config.ts rewrites）：
  - `/api/v1/*` → platform-backend svc:8080
  - `/agent/release-agent/*` → release-agent svc:8100（对话单次流/confirm-stream）
- **对话单次流解析**（assistant/page.tsx consumeStream）：fetch ReadableStream 手解 SSE `data:` 帧；TEXT_BLOCK_DELTA 增量必须落到「最后一条 assistant 气泡」（工具状态行会插在其后）；permission_ask 渲染确认卡片 → POST confirm-stream
- **HTTP 非安全上下文无 crypto.randomUUID**——sessionId 用时间戳+随机串兜底（localStorage 持久化）
- 列表/详情均 5s 轮询实时状态；操作后手动 load() 全刷

## 构建与部署

```bash
npm run lint && npm run build   # standalone 输出
docker build -t platform-frontend:v2 .
# 导入集群 + rollout restart 见 docs/deployment.md
```
