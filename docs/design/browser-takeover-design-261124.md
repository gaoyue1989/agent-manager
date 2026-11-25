# 浏览器人工接管（Browser Takeover）设计 · 261124

## 1. 背景与目标

业务 Agent 在沙箱内操作浏览器时，会遇到只有人才能完成的步骤（密码、短信/2FA 验证码、图形验证码、高风险确认）。本设计给 agent-framework 增加能力：**人工可以中途接管浏览器控制权，接管期间 Agent 不得操作浏览器；交还后 Agent 先重新采样现场再继续任务**。

演示形态：`demo/browser-takeover/`（OAF 包 + 沙箱镜像 + 独立前端 + 部署/e2e 脚本）。

## 2. 总体架构

四段链路，职责分离：

| 链路 | 路径 |
|------|------|
| Agent 操作浏览器 | Agent → `execute`(bash) → `chrome-devtools` CLI → 沙箱内 chrome-devtools-mcp daemon → 沙箱内 Chromium（9222 仅沙箱回环） |
| 实时画面 | 浏览器(noVNC) → WS → agent-framework `/threads/{sid}/browser/vnc` → OpenSandbox server 端口代理（注入 `OPEN-SANDBOX-API-KEY`）→ 沙箱 websockify(6080) → Xtigervnc(5901) |
| 控制权 | 前端 ↔ agent-framework REST（状态/接管/交还）；控制权锁落 Redis |
| 人工操作 | 人工 → noVNC → 同一 Chromium（与 Agent 看到的是同一个页面/Cookie） |

关键取舍：

- **不给 agent-framework 引入浏览器驱动**：操作通道复用沙箱 shell，chrome-devtools-mcp 自带 CLI 与 daemon（Unix socket，无需对外端口），Agent 侧零新增驱动代码、无需把 CDP 端口暴露出去。
- **画面代理放业务面**：backend 是控制面（原则一：业务面不得依赖 backend），且只有 agent-framework 持有会话→沙箱映射与归属信息；本环境 OpenSandbox server 配置了 API key，浏览器无法自带该 header，必须由可信前置代理注入。

## 3. 控制权状态机

- 状态：`AGENT`（缺省） / `HUMAN`，按会话（sid）维护；唯一真相源 = Redis `brw:ctrl:{sid}`（经 `RedisConnectionFacade.key` 取服务前缀隔离），值 `{reason, by, trigger, since}`，TTL `AGENT_BROWSER_TAKEOVER_TTL_MINUTES`（默认 60，过期即回落 AGENT）。
- 写失败 → 503 `control_store_unavailable`（不谎称成功）；闸门读失败 → fail-open + WARN（与 `sbx:guard` 同向，拦截是 UX 安全网而非安全边界）。
- **闸门**：`BrowserControlMiddleware.onActing` —— 控制权为 HUMAN 时把 `execute` 调用整体 DENY（合成 DENIED 事件 + 注入 DENIED tool result）。不按命令文本匹配：bash 是 Agent 触达浏览器的唯一通道，识别 `chrome-devtools` 字样可被 `sh -c`/脚本绕过。

### 3.1 被动触发（Agent 请求接管）

`request_browser_takeover(reason)` 自定义工具（`CustomTool`，默认 ALLOW）：翻锁为 HUMAN，工具结果要求模型结束本轮并用一句话告知用户需要做什么。之后模型若再调 `execute` 会被闸门拒绝。

### 3.2 主动触发（用户点「接管」）

`POST /threads/{sid}/browser/takeover`：先翻锁（安全第一），再复用**协作式停止**（`TurnStopService.request`，`turn_stop` 表 + 属主副本轮询 + SDK 检查点间中断），当前轮以 `cancelled` 收流。

### 3.3 交还（唤醒 Agent）

`POST /threads/{sid}/browser/release`（SSE）：翻锁为 AGENT 后**内部调用 `ChatStreamController.chat()`** 发一条固定的续跑消息，整条 chat 管线（租约/意图/事件流/沙箱生命周期/SSE 续传）原样复用，不复制 turn 装配。续跑消息要求：先 `chrome-devtools list_pages` + `take_snapshot` 重新采样现场，不要重复人工已完成的步骤。

### 3.4 沙箱保留

接管期间沙箱不能被 LRU 回收或 TTL 到期：续期扫描（`SandboxRenewalRegistry.sweep`）除 call refcount / turn 注册 / T4 保留窗外，把「控制权 = HUMAN」也视为保留信号（`SandboxHoldSignal`，运行期从应用上下文惰性取，读取失败按不持有处理）。控制权锁在 Redis，因此**任一副本**的扫描都能看到该信号。

## 4. 接口契约

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/threads/{sid}/browser` | `{control:{owner,reason,by,trigger,since,expires_in_seconds}, sandbox:{ready,sandbox_id}, vnc_path}`；沙箱就绪为只读探测（不连接不占位）；`sandbox_id` 仅下发给已建立归属的会话所有者 |
| POST | `/threads/{sid}/browser/takeover` | body `{reason?}` → `{control, stop_outcome}` |
| POST | `/threads/{sid}/browser/release` | SSE；非 HUMAN 持有时返回 `not_taken_over` 错误帧 |
| GET | `/threads/{sid}/browser/vnc` | WebSocket；握手校验会话归属（`X-User-Id` 头 > `?userId=` > 匿名），上游=沙箱固定端口（缺省 6080）+ server 代理 |

四个端点统一走 `SessionAccessGuard.establishedOwner`（fail-closed）：`session_user` 必须存在明确归属且归属者 = 请求者；无映射行 / 占位行 `unknown` / 保留命名空间（`u-*`、`debug-user`、`gw-*`）一律 404（不泄露会话存在性）。沙箱解析走 `SandboxLocator.locateOwned` / `sandboxIdOwned`，要求绑定行 `sandbox_binding.user_key` = 会话归属者（`user_key` 恒为沙箱创建时的真实用户，是区分「会话键」与「用户身份」的唯一可靠判据）；无绑定簿记（`SANDBOX_LIFECYCLE_ENABLED=false`）或归属不符 → 视为无沙箱（WS 4404 / `ready=false`），禁止把 `sessionId` 当 `USER(sessionId)` 跨用户寻址（issue #559）。

总开关 `AGENT_BROWSER_ENABLED`（默认 false）：关闭时零装配（无端点/无中间件/无工具，存量服务零影响）。

## 5. 沙箱镜像

`demo/browser-takeover/sandbox-image`：基于 `opensandbox/chrome`（Xtigervnc :1 + Chromium 143）+ Node 22 + `chrome-devtools-mcp@1.13.0`（含 `chrome-devtools` CLI）+ noVNC/websockify + 演示站点。

- entrypoint 启动 Xtigervnc(:1) / websockify(6080→5901) / 演示站点(8088) / Chromium(`--remote-debugging-port=9222`，仅回环)；
- daemon 经 `/etc/cd4a/config.json` 固定 `browserUrl=http://127.0.0.1:9222`、`headless=false`——显式或隐式启动都附着到 VNC 上可见的同一个 Chromium；
- K8s 运行时下镜像直接作为沙箱 Pod 容器，引导脚本由 `execd-installer` init 容器注入 `/opt/opensandbox`；镜像需在 kind 节点本地（`IfNotPresent`）。

## 6. 安全边界与已知限制

- 9222（CDP）不对外：仅沙箱内回环；Agent 依赖沙箱内 CLI；server 代理连不到该端口（实测 502）。
- 沙箱内 Xtigervnc 以 `-SecurityTypes None` 启动：6080/5901 只在 server 代理之后可达，画面鉴权由业务面的 WS 代理（会话归属校验 + API key 注入）承担，VNC 层不再叠加密码。
- noVNC 画面必须经归属校验的代理；上游 API key 只在业务进程侧注入。
- **接管授权规则（issue #559，P0）**：会话归属 fail-closed（见 §4）；沙箱归属以 `sandbox_binding.user_key` = 会话归属者为证明，跨用户/无簿记一律拒绝。攻击面：`session_user` 无行时旧 `ownedBy` 放行 + `SandboxLocator` 把 `sessionId` 当 `USER(sessionId)` 候选，使任意已认证用户以受害者 `userId` 作 sid 即可读到受害者 `sandbox_id` 并经 noVNC 全权操控。`SandboxLocator.locate` / `sandboxId`（无归属证明）仅供 env-update 等非实时控制链路复用，浏览器接管不得回退到这两个方法。
- `viewOnly` 是前端约束：接管期间 Agent 不动浏览器由服务端闸门保证；反向（人工不在 Agent 控制时误触）不做服务端强制，后续可在 WS 代理层做 RFB 输入帧过滤。
- 后续：宿主机 nginx(:8911) `/agent/` 需补 WebSocket 升级头（属系统配置，未在本次改动内）；多实例 noVNC 输入审计；WebRTC 高帧率方案。
