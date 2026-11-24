# 浏览器人工接管 Demo

演示目标：**Agent 在浏览器里干活 → 中途人工接管 → 交还后 Agent 重新采样现场继续**。

- Agent 侧：沙箱内 `execute`(bash) 调 chrome-devtools CLI 操作浏览器（控制端口 9222 只在沙箱回环，不对外）。
- 人工侧：独立前端里的 noVNC 实时画面；接管时画面可操作（与 Agent 看到的是同一个 Chromium）。
- 控制权：agent-framework 侧 Redis 控制权锁 + acting 闸门；接管期间 Agent 的沙箱命令一律被拒绝。

设计细节见 [docs/design/browser-takeover-design-261124.md](../../docs/design/browser-takeover-design-261124.md)。

## 资产

| 路径 | 内容 |
|------|------|
| `sandbox-image/` | 浏览器沙箱镜像（`opensandbox/chrome` + Node 22 + chrome-devtools-mcp 1.13.0 + noVNC/websockify + 演示站点） |
| `packages/browser-agent/` | OAF 包：提示词（浏览器操作规范 + 接管协议）与 `chrome-devtools-cli` 技能 |
| `frontend/` | 独立前端（Vite + React + TS + noVNC），直调 agent-framework API |
| `deploy.sh` | 构建/导入镜像、上传包、经平台 API 发布服务；`down` 下线 |
| `e2e/browser-takeover-e2e.mjs` | API 级 e2e：被动触发/主动接管/闸门拒绝/交还重采样 |

## 前置

- kind 集群 + platform-backend/frontend/router 存活（`docs/deployment.md`）
- OpenSandbox 已部署（`scripts/deploy-opensandbox.sh`），本机 `127.0.0.1:18600` 端口转发可用（e2e 用）
- `agent-framework` 镜像含本次改动（`WITH_AGENT_FRAMEWORK=1 ./deploy.sh` 或手动构建）
- 宿主机内存 ≥ 6G 空闲（浏览器沙箱 ~1.5G + 业务 Pod ~0.8G）
- 本机磁盘 ≥ 3G（浏览器镜像 ~1.7G）

## 运行

```bash
# 1. 发布服务（含沙箱镜像构建/导入、OAF 包上传）
SECRETS_FILE=/path/to/.env.secrets ./deploy.sh

# 2. 启动独立前端（默认代理到 127.0.0.1:30080）
cd frontend && npm install && npm run dev
# 打开 http://<主机IP>:5188（dev server 监听 0.0.0.0）

# 可选：HTTPS 入口（自签证书，浏览器需点「高级 → 继续前往」）
#   wss + 安全上下文；经只放行 CONNECT:443 的代理访问时也能通
npm run dev:https     # https://<主机IP>/

# 可选：实时画面直连 OpenSandbox 网关（访问链路阻断业务面 WS 时的演示用通路；
#   绕过业务面的会话归属校验，仅限本地演示）
#   http://<主机IP>:5188/?vncBase=42.194.213.236（OpenSandbox 网关公网 IP；VPC 内网地址为 100.66.1.6）

# 3. e2e（API 级，无需前端）
node e2e/browser-takeover-e2e.mjs

# 4. 下线
./deploy.sh down
```

演示站点（沙箱内）：`http://127.0.0.1:8088/`，账号 `demo`，密码见 `DEMO_GUIDE.md`。
