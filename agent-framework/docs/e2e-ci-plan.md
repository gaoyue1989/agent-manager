# agent-framework GitHub Actions E2E 验证体系设计（e2e-ci-plan）

> 状态：**设计稿 v2**，待评审
> 范围：agent-framework（新增 `agent-framework/e2e/`）+ `.github/workflows/agent-framework-ci.yml`（新增 3 个 e2e job）
> 前置：无 —— 不依赖 Kind 集群、platform-backend、前端、真实 LLM API Key、任何 GitHub Secrets
> 复用：`bench/mock-llm`、`bench/mock-mcp`、`example/approval-forms`（mock MCP + 选择器先例）、`docs/api-thread-spec.md`（协议权威）
> v2 变更（2026-09-18）：① 沙箱服务改为 **mock 实现**（不再部署真实 OpenSandbox Server / 拉取镜像）；② 新增 **F 组文件上传下载验证矩阵**（原 S9 扩充为独立场景组）
> v3 变更（2026-09-18）：mock LLM 与 mock 沙箱统一升级为 **「真实服务录制 → 回放」架构**——LLM 回复从真实 LLM API 录制（流式 chunk 原文回放），沙箱协议从本机真实 OpenSandbox Server 录制；录制只在开发机做一次并提交为夹具，CI 纯回放，零密钥零外联

---

## 1. 背景与目标

### 1.1 现状与差距

| 验证层 | 现状 | 缺口 |
|--------|------|------|
| 单元测试（CI 已有） | 61 类 / 676 用例，controller 层用 MockMvc standalone + Mockito mock `HarnessAgent`/各 Store | LLM HTTP、SSE 真实序列化、MySQL/Redis 真实读写、MCP 真实协议全部被 mock 掉 |
| 集成测试（本地手动） | `HITL_MYSQL_IT=1`、`REDIS_IT=1`、`SANDBOX_IT=1`、`S3_IT=1` 环境变量门控的 `*IT` 类 | surefire 不捡 `*IT`，CI 从不执行 |
| 平台 E2E（仓库根 `e2e/`） | 面向 Kind 全链路（platform-backend + release-agent + 真实 LLM） | 需要人工维护的集群与 `.env.secrets`，无法进 GitHub Actions，且测的是"平台编排"不是"agent-framework 本体" |
| 压测设施（`bench/`） | mock-llm（OpenAI 兼容）+ mock-mcp（streamableHttp）+ docker MySQL 编排 | 只服务并发压测，无断言体系 |

结论：**agent-framework 自身的黑盒行为（真实进程 + 真实依赖 + 真实 HTTP/SSE）没有任何一条 CI 防线**。多副本改造（durable-sse-multinode）、history 权威化（AgentState）、HITL 租约补丁这类高风险合入后只能靠人肉回归。

### 1.2 目标

在 GitHub Actions（ubuntu-latest，runner 进程 + 自带 Docker 仅用于 MySQL/Redis/nginx services）上拉起一套**完全自包含、零密钥**的 agent-framework 运行环境并做黑盒 E2E：

1. **基础设施**：MySQL 8（状态/租约/审计权威）+ Redis 7（session 事件流，appendonly + noeviction）——GH Actions services 容器
2. **Mock 服务**（全部 node/python 进程，零镜像拉取，响应体一律来自**真实服务录制件**回放，见 §4.1/§4.4）：
   - OpenAI 兼容 mock LLM（真实 LLM 流式回复录制回放 + 场景标记路由 + 调用记录）
   - streamableHttp mock MCP ×2（普通只读工具；`ui://` MCP App + `ask` HITL 工具）
   - **mock OpenSandbox Server**（本机真实 Server 协议录制回放：路由/字段/事件序取录制件，文件与命令语义本地受控执行）
3. **验证面**：
   - 全端点 API 调用（信息/会话/文件/线程管理/A2A）
   - **文件上传下载全链路**（上传校验矩阵、图片内联、工作区注入、present_file 交付、下载/预览、历史卡片）
   - Debug 页面浏览器 E2E（Playwright）
   - 会话场景：刷新续传（/status 判态 + /subscribe 游标）、**多副本**（双实例随机路由、kill 副本接管、并发互斥、跨副本确认）
   - MCP Apps（卡片资源代理、4.7 静默上下文更新）
   - HITL 人工审批（挂起 → 批准/拒绝 → 重复确认 409）
   - 沙箱（Shell 执行、文件沙箱化、USER 级复用、容器重建降级）

### 1.3 非目标

- 不测平台编排链路（上传 OAF 包 → K8s 拉起 → A2A 注册），那是仓库根 `e2e/` 的职责
- 不做性能/并发压测（`bench/` 已覆盖）
- 不验证沙箱的**容器级隔离安全性**（mock 沙箱不提供隔离，只验证 agent-framework 与沙箱服务协议交互的正确性——隔离属 OpenSandbox 项目自身的职责）
- 不做 S3 存储档（`S3FileStorageIT` 已按环境变量门控覆盖，凭据无法进 CI）
- 不追求 100% 分支覆盖；按"六大风险面 × 高频回归路径"取场景集合
- 不修改现有 `bench/`、`example/` 资产（只读复用，见 §4）

### 1.4 设计原则

1. **复用优先**：mock LLM fork 自 `bench/mock-llm/server.js`（已验证能驱动真实 agent 完整流），mock MCP 直接复用 `example/approval-forms/mock-mcp/approval_mcp.py`（已实现 ui:// 卡片全协议）与 `bench/mock-mcp/server.js`（read_only 普通工具）。
2. **零密钥、零外拉镜像**：所有外部依赖均为 mock 或 GH Actions services；被测进程所需镜像字段（`SANDBOX_IMAGE`）仅是传给 mock 的字符串，从不 pull。`LLM_API_KEY=e2e-dummy`、OpenSandbox api_key 用占位符，不新增任何 GitHub Secrets。
3. **黑盒**：只打真实 HTTP/SSE（`POST /threads/chat` 收流、`GET /threads/{sid}/subscribe` 续传），不注入 Java 进程内部；被测物是 `mvn package` 产出的 jar 本体。
4. **CI = 本地同路径**：环境编排收敛为 `agent-framework/e2e/scripts/env-up.sh / env-down.sh / run.sh`，CI 与开发者用同一脚本，本地可完整复现排障。
5. **确定性**：LLM 应答按消息标记路由到固定录制件，无随机性；每个用例用运行级唯一 `userId`/`sessionId`（`e2e-<runId>-<case>`），防 HITL 暂停态串场（与根 e2e 断言原则一致）。
6. **录制回放（record → replay）**：mock 的响应体不手写——LLM 从真实 LLM API 录制（流式 chunk 原文）、沙箱从本机真实 OpenSandbox Server 录制；录制只在开发机做一次并提交为夹具，CI 纯回放。以真实线上格式为契约，杜绝手写 mock 与 SDK 解析行为之间的偏差。

---

## 2. 总体架构

### 2.1 拓扑（e2e-core job 示意）

```
ubuntu-latest runner
├── GH services: mysql:8.0 (127.0.0.1:3306, DB=agent_framework_e2e)   ← 表由实例启动时幂等自建
├── GH services: redis:7    (127.0.0.1:6379, appendonly+noeviction)  ← sess:{sid}:events 流
├── node e2e/mock/llm-server.mjs      (127.0.0.1:18081, /v1/chat/completions + /stats /reset /health)
├── node bench/mock-mcp/server.js     (127.0.0.1:18082, bench_echo, read_only)      [复用直引]
├── python3 example/approval-forms/mock-mcp/approval_mcp.py (127.0.0.1:8813, ui:// + ask + app_only) [复用直引]
├── java -jar target/agent-framework-*.jar  (127.0.0.1:8100)
│     env: LLM_BASE_URL=http://127.0.0.1:18081/v1
│           CHECKPOINT_JDBC_URL=jdbc:mysql://127.0.0.1:3306/agent_framework_e2e
│           AGENT_REDIS_URL=redis://127.0.0.1:6379
│           AGENT_CONFIG_DIR=e2e/fixtures/agent-config
└── npx playwright test（API 项目：node fetch 收 SSE；UI 项目：chromium 打 http://127.0.0.1:8100/debug/）
```

e2e-sandbox job 在上述基础上**追加一个 node 进程** `e2e/mock/sandbox-server.mjs`（:8090 + 每沙箱代理端口池 41xxx），实例 env 切 `SANDBOX_ENABLED=true`（详见 §4.4）。

### 2.2 组件矩阵

| 组件 | 来源 | 部署方式 | 端口 | 就绪探测 |
|------|------|---------|------|---------|
| MySQL 8 | actions services | `services: mysql:8.0`，`MYSQL_DATABASE=agent_framework_e2e` | 3306 | `mysqladmin ping`（GH 原生 health） |
| Redis 7 | actions services | `services: redis:7-bookworm`，cmd `--appendonly yes --maxmemory-policy noeviction` | 6379 | `redis-cli ping` |
| mock LLM | **新增** `e2e/mock/llm-server.mjs`（回放 `mock/fixtures/llm/` 录制件，骨架 fork 自 `bench/mock-llm/server.js`） | node 后台进程 | 18081 | `GET /health` |
| mock MCP（普通工具） | 复用 `bench/mock-mcp/server.js` | node 后台进程 | 18082 | `GET /health` |
| mock MCP（MCP Apps/HITL） | 复用 `example/approval-forms/mock-mcp/approval_mcp.py` | python3 后台进程（仅标准库） | 8813 | 脚本启动即算就绪 + 首用重试 |
| **mock OpenSandbox** | **新增** `e2e/mock/sandbox-server.mjs`（协议取自 `mock/fixtures/sandbox/` 真实服务录制件，见 §4.4） | node 后台进程 | 8090（管理）+ 41xxx（execd 代理池） | `GET /health` → `{"status":"healthy"}` |
| agent-framework | `mvn -B -DskipTests package` 产物 | runner 上直跑 jar | 8100（单副本）/ 8101+8102（多副本） | `GET /health` 200 |
| nginx 轮询 LB | docker run nginx | 多副本 job 专用 | 8100 | 配置装载成功 + 任一 upstream /health |
| Playwright | `e2e/package.json` | `npx playwright install --with-deps chromium` | — | — |

### 2.3 job 拆分

| job | 副本 | 内容 | 依赖 | 预算 |
|-----|------|------|------|------|
| `test`（已有） | — | mvn test | — | ~6min |
| `e2e-core` | 1 | S/F/H/M/A 组 API + U 组 UI（§5.1-5.5、§5.7-5.8） | needs: test | ~12-15min |
| `e2e-multi` | 2 + nginx | R 组（刷新续传跨副本、kill 接管、并发互斥、跨副本 confirm）+ U9（§5.6/§5.7） | needs: test | ~10-15min |
| `e2e-sandbox` | 1 + mock 沙箱 | X 组（Shell、沙箱文件、USER 复用、容器重建降级、上传注入，§5.6） | needs: test | ~8-12min |
| `build-push`（已有） | — | 镜像推送 | needs: test | 不变 |

三个 e2e job 与 `build-push` 并行；单测红则全部不跑。用 `concurrency.group = e2e-${{ github.ref }}` + `cancel-in-progress` 抑制同分支重复跑。沙箱走 mock 后无外拉镜像与 continue-on-error 门槛，三个 job 同级硬门禁。

---

## 3. 环境编排设计

### 3.1 端口规划

| 端口 | 用途 |
|------|------|
| 3306 / 6379 | MySQL / Redis（services 固定映射 localhost） |
| 8100 | 被测入口：core/sandbox job = 实例本体；multi job = nginx LB |
| 8101 / 8102 | multi job 两副本 |
| 18081 / 18082 / 8813 | mock LLM / bench MCP / approval MCP（沿用 bench 与 example 既有端口约定） |
| 8090 | mock OpenSandbox 管理口（仅 sandbox job） |
| 41000-41099 | mock OpenSandbox 每沙箱 execd 代理端口池（仅 sandbox job；上限 100 个并发虚拟沙箱，远超用例需要） |

### 3.2 实例环境变量矩阵

| 变量 | 值 | 说明 |
|------|----|------|
| `LLM_BASE_URL` | `http://127.0.0.1:18081/v1` | OpenAI 兼容，须带 `/v1` |
| `LLM_API_KEY` / `LLM_MODEL_ID` | `e2e-dummy` / `e2e-mock-model` | 占位符，无真实密钥 |
| `CHECKPOINT_JDBC_URL` | `jdbc:mysql://127.0.0.1:3306/agent_framework_e2e` | 库由 MySQL service env 预建；**表全部实例自建**（`TurnLeaseStore.java:63` 等构造器 `initSchema()`，幂等） |
| `CHECKPOINT_USERNAME/PASSWORD` | service 容器 env 同值 | — |
| `AGENT_REDIS_URL` | `redis://127.0.0.1:6379` | 事件流回放必需；Redis 缺失时启动可过但 /status 503 |
| `AGENT_CONFIG_DIR` | `e2e/fixtures/agent-config` | §4.3 夹具 |
| `SERVER_PORT` | 8100 / 8101 / 8102 | — |
| `FILE_STORAGE_TYPE` / `FILE_STORAGE_LOCAL_DIR` | `local` / `e2e/.runtime/files` | F 组文件链路 |
| `AGENT_CLEANUP_TURN_LEASE_TTL_SECONDS` | **15**（默认 60，`AgentManagerProperties.java:101-108`） | R4 kill 接管等待从 ~60s 压到 ~20s |
| `AGENT_CLEANUP_TURN_LEASE_RENEW_SECONDS` | **5**（默认 20） | 须 < TTL |
| `AGENT_CLEANUP_CONFIRM_TTL_MINUTES` | 默认 30 | H 组如需测 TTL 过期再单独压 |
| `SANDBOX_ENABLED` | false（core/multi）/ true（sandbox job） | — |
| `OPENSANDBOX_SERVER_URL` | `127.0.0.1:8090` | 指向 mock（仅 sandbox job） |
| `OPENSANDBOX_API_KEY` | `e2e-placeholder` | 占位符（与 bench 做法一致；mock 校验可关） |
| `SANDBOX_IMAGE` | 默认值原样（`opensandbox/code-interpreter:v1.1.0`） | 仅作为 create 请求字段传给 mock，**从不拉取** |

> 多副本两实例**共享同一 MySQL 库与同一 Redis**（这是多副本语义的前提：状态/租约在 MySQL，事件流在 Redis，路由无粘性）。

### 3.3 nginx 轮询 LB（multi job）

最小配置要点（`e2e/fixtures/nginx-lb.conf.template` 渲染生成）：

```nginx
events { worker_connections 1024; }
http {
  upstream agent_replicas { server host.docker.internal:8101; server host.docker.internal:8102; }
  server {
    listen 80;
    location / {
      proxy_pass http://agent_replicas;
      proxy_http_version 1.1;
      proxy_buffering off;            # SSE 必需：禁缓冲
      proxy_read_timeout 3600s;       # 对齐生产 ingress 注解
      proxy_send_timeout 3600s;
    }
  }
}
```

`docker run -p 8100:80 --add-host=host.docker.internal:host-gateway nginx`。选轮询而非 client 端轮换实例的原因：**U9（页面刷新后续传）必须让"刷新后请求随机落到另一副本"由基础设施真实发生**，客户端轮换模拟不了浏览器行为。同时决策 D2 已明确生产禁用 sessionAffinity，轮询是生产行为的忠实模拟。

### 3.4 编排脚本与失败取证

```
e2e/scripts/env-up.sh      # 起 mock 进程 → 起实例(1..N) → 轮询 /health → 生成 .runtime/env.json（端口/pid/日志路径）
e2e/scripts/env-down.sh    # 按 env.json 反向清理（SIGTERM→SIGKILL 兜底；sandbox job 附带调 mock 的清理端点）
e2e/scripts/run.sh <group> # env-up → npx playwright test --project=<group> → 总是 env-down → 退出码透传
```

失败取证（CI `if: always()` 上传 artifact `e2e-logs-<job>`）：

- 各实例 stdout/stderr 全量日志（含 Spring 启动栈）
- mock LLM `/stats` 终态快照（最后一次请求的完整 messages，定位脚本分支是否命中）
- mock 沙箱 `/stats` 终态快照（create/connect/命令/文件操作流水，仅 sandbox job）
- Playwright HTML report + `trace: retain-on-failure`（UI 组）
- `e2e/.runtime/env.json`（实际端口/pid，排除端口漂移）

### 3.5 时序与超时约定

- 排队超时 `ACQUIRE_TIMEOUT` 是**硬编码 120s**（`ChatStreamController.java:82`，package-visible static，非配置项）→ R3 只断言 `waiting` 帧出现后客户端主动断开，**不**等满 120s（完整超时路径留本地手动档，job 时间不可承受）。
- mock LLM 全场景 0 网络延迟（`MOCK_LLM_DELAY_MS=0`，除 `slow`/`hang` 场景自带）；单 turn 预期 <5s，CI 断言窗口统一 60s（刷新续传类 90s），远小于根 e2e 的 ≥300s 真实 LLM 窗口。
- Playwright：`timeout: 30000`（expect 默认）/ `180000`（单测）/ job 级 `timeout-minutes: 25`。
- 重试策略：CI `retries: 0`（API 组）/ UI 组过渡期允许 `retries: 1`，稳定后归零——黑盒 e2e 的价值在于暴露 flaky，不在于绿。

---

## 4. Mock 服务设计

### 4.1 mock LLM（`e2e/mock/llm-server.mjs`，fork 自 `bench/mock-llm/server.js`，录制回放架构）

保留 bench 版骨架（零依赖 node、`/stats` `/reset` `/health`、进程编排），**响应体来源整体替换为真实 LLM 录制件回放**，场景标记只做路由：

#### 4.1.1 录制（开发机一次性，产物提交入库）

`e2e/scripts/record-llm.mjs` 是**录制反向代理**（默认 :18091 → 真实 LLM BASE_URL；密钥取 `.env.secrets`，仅开发机使用，CI 永不触达）：

- 透传请求/响应并落盘：请求 JSON 全文 + 流式 SSE chunk **原文序列**（含 tool_calls 增量分片、`finish_reason`、`usage`）或非流式 JSON 全文
- 夹具格式 `e2e/mock/fixtures/llm/<scenario>.json`：`{ scenario, model, recordedAt, calls: [{ request, chunks[] | body }] }`——一个场景可含**有序多次调用**（多轮工具链，如 `file:deliver` 的 write→present 两段），回放按会话内调用次序推进
- **只录 body，不落任何请求头**（Authorization 天然不入库）；录制清单与 §4.1.3 场景表一一对应，缺一即 `check:fixtures` 报红
- 驱动方式：带 `[E2E:<scenario>]` 标记的消息经真实 agent-framework（`LLM_BASE_URL` 指向录制代理）完整跑一轮，多轮场景跑完整链路，录制器按会话聚合成序列。录制时真实 SDK 已成功解析过这些 chunk 一轮——格式保真由构造保证

#### 4.1.2 回放引擎（CI 运行形态）

- 请求进来 → 取 `messages` 中最后一条 `role=user` 的文本，匹配尾部标记 `[E2E:<scenario>](<arg>)` → 取夹具 → 按 sessionId 维度递增的调用序号回放 `calls[i]`：chunk 原文逐条重发
- **时序注入是回放引擎的包装，不改写 chunk 内容**：`slow` 场景对前 n 个 chunk 加延迟；`hang` 场景重放前 2 个 chunk 后挂起
- `role==tool` 消息在末尾（工具结果回流）时推进到序列下一 call（收尾文本）；无标记默认 `plain`
- **严格模式**（CI 默认）：标记无对应夹具 → 500 + 明确错误，宁可红也不静默合成；`MOCK_LLM_ALLOW_SYNTH=1`（仅本地排障）才允许回落手写合成响应，且 `/stats` 标记 `synthesized:true`
- 断言侧效应：期望文本从夹具读取——测试与夹具同源，不存在"mock 文本 vs 期望文本"两处维护

#### 4.1.3 场景路由表（首期；每场景一个录制夹具）

| 标记 | 行为 | 服务于 |
|------|------|--------|
| `[E2E:plain](<text>)` | 逐 token 输出 `<text>`（TEXT_BLOCK_DELTA 序列） | S2/S3、U2 |
| `[E2E:slow](<text>,<chunkMs>,<n>)` | 前 n 个 delta 每块延迟 chunkMs | R2/R4/U7（拉宽流式窗口制造"进行中"） |
| `[E2E:hang]` | 输出 2 个 delta 后无限挂起（不收流） | R4（kill 副本时 turn 永不完成） |
| `[E2E:tool:echo](<text>)` | 返回 tool_calls `echo` | S4 内置工具 |
| `[E2E:tool:time]` | 返回 tool_calls `get_current_time` | S4 |
| `[E2E:tool:write](<file>,<content>)` | 返回 tool_calls `write_file` | X3、F 组前置 |
| `[E2E:tool:read](<path>)` | 返回 tool_calls `read_file` | F1（读上传文件）、X3 |
| `[E2E:tool:present](<file>)` | 返回 tool_calls `present_file` | F5 |
| `[E2E:file:deliver](<file>,<content>)` | 两段式：先 `write_file`，tool 结果回流后再 `present_file` | F5/F6/F10（输出文档交付） |
| `[E2E:tool:mcp_echo](<text>)` | 返回 tool_calls `bench_echo`（MCP） | S5 |
| `[E2E:hitl:submit](<app>)` | 返回 tool_calls `submit_application`（ask 工具） | H1-H6、U4/U5 |
| `[E2E:mcpapp:form](<app>)` | 返回 tool_calls `show_application_form`（ui:// 工具） | M1、U6 |
| `[E2E:execute](<cmd>)` | 返回 tool_calls `execute` | X2/X3 |
| `[E2E:recall]` | 输出对上一轮 `[E2E:remember](<x>)` 的引用文本（多轮记忆回归） | S3 |

> **夹具即契约**：回放架构下响应内容一律以录制件为准，标记 `<arg>` 仅用于请求侧对齐与断言描述；录制件内 tool_calls 的参数（文件名/命令/应用名）即测试侧固定输入——如 F1 上传的文件名必须取录制件中 `read_file` 的路径 `uploads/note.txt`、X2 的命令即录制件中的 `echo hello-e2a`。

**断言支撑**（区别于 bench 版的核心增强）：

- `GET /stats`：`{ calls: [{sessionId?, messages, systemContent, toolCalls}], count }` —— 记录每次请求的**完整 messages（含 content 块类型）与 system 内容**
- 用途 1（M5）：`POST /mcp/ui-context` 静默更新后，下一次 /chat 的 `systemContent` 必须含注入标记（直接证明 `UiContextInjectionHook` 生效）
- 用途 2（S3/R1）：跨请求 messages 长度单调递增 = 记忆/上下文累积生效
- 用途 3（F2）：messages 中出现 `image_url`（`data:image/png;base64,...`）内容块 = 上传图片经 `UploadWorkspaceInjector.buildContentBlocks` 内联成 ImageBlock 后被 OpenAI 序列化（`UploadWorkspaceInjector.java:149-160` 路径的端到端直证）
- 用途 4：`tool_calls` 请求参数回显 = Agent 传参正确性（如 `bench_echo` 收到的 text）
- `POST /reset`：每用例前清状态（含会话级回放调用序号），保证断言不串场

**流式与非流式**：`/v1/chat/completions` 同时支持 `stream:true/false`（bench 版已具备，agent 运行时走流式，A2A blocking 可能走非流式）。

### 4.2 mock MCP（三处复用，零新建）

| 服务端 | 复用对象 | 提供能力 | 配置侧 |
|--------|---------|---------|--------|
| approval MCP（:8813） | `example/approval-forms/mock-mcp/approval_mcp.py`（python 标准库 ThreadingHTTPServer） | `initialize`/`notifications/initialized`/`ping`/`tools/list`/`tools/call`/`resources/read`（`ui://approval/application-form.html`，mimeType `text/html;profile=mcp-app`）+ GET 探测 200 空帧；工具：`create_application`/`get_application`（普通）、`show_application_form`（ui）、`confirm_application`（app_only）、`submit_application`（ask） | 见 §4.3 |
| bench MCP（:18082） | `bench/mock-mcp/server.js` | `bench_echo` + `/stats`（调用计数断言） | `permissions.read_only: true` |
| deny 语义 | **同一 :8813 服务**在 `mcp-configs/denied/` 下二次注册，`permissions.tools.get_application: deny` | deny 不需要服务端配合（纯 agent 侧权限评估） | 见 §4.3 |

> approval_mcp.py 状态存内存——用例间以应用名参数隔离即可，不需要 /reset（与 example e2e 用法一致）。

### 4.3 agent-config 夹具（`e2e/fixtures/agent-config/`）

```
agent-config/
├── AGENTS.md                 # frontmatter：name=e2e-agent，精简系统提示词（仿 bench/agent/AGENTS.md）
│                             #   config.permission.mode 默认（使 permissions.tools 生效），require_confirmation: false
├── tools.json                # 只写 deny（保持内置全量，对齐主仓约定）
├── skills/
│   └── demo-skill/SKILL.md   # S1 动态技能断言用（/skills dynamic 标记）
└── mcp-configs/
    ├── approval/config.yaml  # streamableHttp http://127.0.0.1:8813
    │                         #   permissions.tools.submit_application: ask     ← HITL 主场景
    │                         #   ui.tools.show_application_form: "ui://approval/application-form.html"
    │                         #   ui.app_only: [confirm_application]             ← 卡片内工具不入 LLM 工具集
    │                         #   ⚠ 不配 read_only / ActiveMCP.json（会短路 ask，example README 明确警告）
    ├── bench/config.yaml     # streamableHttp http://127.0.0.1:18082；read_only: true
    └── denied/config.yaml    # streamableHttp http://127.0.0.1:8813；permissions.tools.get_application: deny
```

同一 :8813 物理服务被 `approval`（ask/ui）与 `denied`（deny）两个逻辑注册消费——MCP 协议允许一个 server 多 client session，config.yaml 目录名即注册名，互不影响。

### 4.4 mock OpenSandbox Server（`e2e/mock/sandbox-server.mjs`，新增）

**定位**：在 HTTP 线上协议层仿真 OpenSandbox Server + 沙箱 execd，让 `SANDBOX_ENABLED=true` 的 agent-framework 完整走通「创建/恢复沙箱 → execd 文件操作 → 命令执行 → 容器销毁」全链路，而**不启动任何容器**。虚拟沙箱以 mock 进程内的「本地目录 + 受控命令执行」承载。

#### 4.4.1 协议依据与录制回放

| 依据 | 内容 |
|------|------|
| `docs/opensandbox-integration-plan.md:87-102,1111-1135` | 管理 API 面：health / create(202) / get / list / delete / endpoints/{port} / pause / resume / snapshots / diagnostics |
| SDK 字节码核对（`com.alibaba:opensandbox-sandbox:1.0.18`，`com.alibaba.opensandbox.sandbox.api.*`） | 实际方法集：`SandboxesApi`（sandboxesGet/Post、SandboxIdGet/Delete/EndpointsPortGet/MetadataPatch/PausePost/RenewExpirationPost）；execd `CommandApi`（runCommand→ServerStreamEvent、getCommandStatus、interruptCommand、createSession/runInSession）；`FilesystemApi`（listDirectory/getFilesInfo/downloadFile/uploadFile/replaceContent/chmodFiles/makeDirs/removeFiles/removeDirs/renameFiles/searchFiles）；`HealthApi.ping` |
| **真实服务录制**（mock 响应格式的唯一来源，M0 产出） | 本开发机 `127.0.0.1:8090` 常驻真实 OpenSandbox Server：`e2e/scripts/record-sandbox-protocol.mjs` 作为**录制反向代理**（agent → 录制代理 :8091 → 真实 Server），按场景清单跑「create → connect/resume → exec（成功/失败）→ 文件读写 → 上传注入 → endpoints → delete」抓全 req/resp（含 execd SSE 事件帧）落 `e2e/mock/fixtures/sandbox/`；只录 body 与 Content-Type，不落请求头 |

**结构回放 + 语义仿真**：mock 的路由集合、请求/响应字段、SSE 事件名与顺序**一律取自录制件**——管理类响应直接原样回放（id/端口按运行时重写）；execd 文件与命令操作回放录制件的**报文结构**，语义层由本地目录与受控命令执行填充真实结果（文件内容、stdout/exitCode）。这样 X3/X6 的动态行为（写什么、读到什么）不受录制件限制，而线上格式零手写。`MOCK_SANDBOX_MODE=replay` 可切纯回放（固定命令返回录制 stdout，不本地执行）供排障；CI 默认混合模式。

#### 4.4.2 线上协议子集（mock 实现范围；"mock 行为"列描述语义层，报文格式一律按 §4.4.1 录制件回放）

| 分类 | 路由 | mock 行为 |
|------|------|----------|
| 管理 | `GET /health` | `{"status":"healthy"}` |
| 管理 | `POST /v1/sandboxes` | 202；分配 sandboxId + 独立端口（41000 池递增）+ 根目录 `.runtime/sandboxes/{id}/`；起该端口的 execd 代理 listener |
| 管理 | `GET /v1/sandboxes/{id}` | 返回 Sandbox 对象（status=running）；未知 id → 404（供 resume 降级路径触发） |
| 管理 | `GET /v1/sandboxes/{id}/endpoints/{port}` | `{endpoint: "127.0.0.1:<该沙箱端口>/proxy/44772"}`（对齐 direct ingress 形态 `host:port/proxy/44772`，端口即沙箱鉴别器） |
| 管理 | `DELETE /v1/sandboxes/{id}`、`PATCH .../metadata`、`POST .../renew-expiration` | 删除=关 listener+清目录（可选保留目录供取证）；metadata/renew 通用 200 |
| execd（每沙箱端口，前缀 `/proxy/44772`） | `HealthApi.ping` | 200 |
| execd | `CommandApi.runCommand`（SSE 事件流：init/output/complete，对照录制件） | **本地受控执行**：`child_process.exec(command, {cwd: 沙箱根目录})`，stdout/stderr/exitCode 按事件序回放 |
| execd | `FilesystemApi.*`（`/files*` 全家：list/info/upload/download/replace/permissions/search） | 映射到沙箱根目录的真实 fs 操作（路径钳制在根内，防越界） |
| 观测 | `GET /stats`、`POST /reset`、`POST /admin/destroy/{id}` | /stats：`{creates[], connects[], commands[{sandboxId,cmd,exitCode,stdout}], fileOps[], errors[]}`（X 组断言 + 失败取证）；/admin/destroy：模拟容器 GC（连接即 404），驱动 X6 降级场景 |

**受控执行的安全性说明**：mock 对命令做白名单前缀校验（`echo`/`ls`/`cat`/`mkdir`/`tar`/`base64`/`rm`/`printf`/`test`/`wc` 等 e2e 脚本所需集合），白名单外直接返回非零退出码与错误文本。命令来源是脚本化 mock LLM（封闭集合），不存在任意命令注入面；runner 本身一次性，无持久化风险。`tar cf - … | base64`（`doPersistWorkspace`/`doHydrateWorkspace`，`opensandbox-integration-plan.md` §4.3.4）在真实 shell 下原样可用，工作区持久化/回灌链路为**真行为**而非仿真。

**USER 级复用的归属说明**：沙箱复用是 agent 侧行为（`agent_state` 存 sandboxId，`resume()` 用 `connector().connect()` 重连，`OpenSandboxClient.java:140-162`）；mock 服务端只需按 id 语义工作。X4 通过 mock `/stats` 断言「同 userId 第二会话 = connect 既有 id、零 create；异 userId = 新 create」。

---

## 5. 测试场景矩阵

> 协议依据 `docs/api-thread-spec.md`（v1.0 权威）。帧词表速查见附录 A。
> 断言风格：**帧序列有序子集 + 关键字段精确 + 终态帧三选一（`done` / `permission_ask` / `error`）**；`id:`（seq）单调递增是每条流式用例的通用断言。

### 5.1 S 组 — 基础 API（e2e-core）

| # | 场景 | 步骤 | 断言 |
|---|------|------|------|
| S1 | 元数据端点 | GET `/`、`/health`、`/metadata?includeDetails=true`、`/system-prompt`、`/.well-known/agent-card.json`、`/tools`、`/mcp`、`/skills` | 200；`/tools` 含 26 内置 + `mcp__bench__bench_echo`（只读标记）+ `show_application_form` 带 `uiResourceUri` + `confirm_application` 带 `appOnly`；`/mcp` 三 server；`/skills` 含 demo-skill 且 `dynamic:true`；agent-card description 含 A2A 通道限制声明（A 组依赖） |
| S2 | 单次流基础 | POST `/threads/chat`（不带 sessionId，`[E2E:plain]`） | 帧序：`session_created` → `AGENT_START` → `MODEL_CALL_START` → `TEXT_BLOCK_START/DELTA+/END` → `MODEL_CALL_END` → `AGENT_END` → `done`；TEXT 拼接 == 夹具录制文本（期望值与录制件同源）；seq 单调；`session_created.session_id` 与后续 /history 一致 |
| S3 | 续会话记忆 | 同 sessionId 连发 `[E2E:remember](x)` → `[E2E:recall]` | 第二次请求 mock `/stats` 的 messages 含第一轮 user/assistant（上下文累积）；recall 回复含 x |
| S4 | 内置工具 | `[E2E:tool:echo]` / `[E2E:tool:time]` | `TOOL_CALL_START{toolName=echo, toolCallId}` → `TOOL_RESULT_START → TEXT_DELTA* → END{state:SUCCESS}` → 收尾文本 → `done`；history 中 tool_calls[].state == `success`（小写） |
| S5 | MCP 只读工具 | `[E2E:tool:mcp_echo](hello)` | 同 S4 帧序且 toolName=`bench_echo`；bench MCP `/stats` 调用数 +1 且收到 text=hello |
| S6 | 参数校验 | POST body 空 message 且无 fileIds；GET 不存在 sid 的 /history、/status | SSE `error` 帧（`message or fileIds is required`）；不存在会话按当前实现返回 404/空态（实施时按代码固化，行为记录型断言） |
| S7 | 线程生命周期 | GET `/threads` → GET `/threads/{sid}` → PATCH 改名 → GET `/threads/{sid}/llm-calls` → DELETE | 列表含新会话；PATCH 后 title/remark 生效；llm-calls 记录数 ≥ 本会话 LLM 请求数（mock /stats 交叉核对）；DELETE 后 /status 404、`/threads` 不含、MySQL session 级联表行消失、Redis `sess:{sid}:*` 键消失（经 /status 404 间接验证，不直连 Redis） |
| S8 | history 回放 | S4 完成后 GET `/threads/{sid}/history` | messages[].tool_calls[] 配对完整：`id/name/input/state=success/output`；无 pendingConfirm（null） |

### 5.2 F 组 — 文件上传下载全链路（e2e-core；X5 为沙箱变体）

> 行为依据 `FileController.java`（上传校验 §74-150 / 下载 §152-198 / MIME-扩展名映射 §260-299）、`UploadWorkspaceInjector.java`（工作区注入 §51-79、唯一化命名 §93-120、内容块构造 §129-177）、`FileTools.present_file`（file_ready 帧合成）。

| # | 场景 | 步骤 | 断言 |
|---|------|------|------|
| F1 | 文档上传 → 对话读文件 | POST `/files/upload`（note.txt，text/plain）→ `/threads/chat` 携 fileIds + `[E2E:tool:read](uploads/note.txt)` | 上传 200 `{file_id,file_name,mime_type,size}`；mock /stats 该次请求 messages 含路径提示文本块（"用户上传了文件（工作区相对路径）：- uploads/note.txt"，`UploadWorkspaceInjector.java:170-174`）；`read_file` TOOL_RESULT 文本 == 上传内容（工作区注入 `uploads/{唯一化名}` 生效）；`done` |
| F2 | 图片上传 → 视觉内联 | 上传 1KB png（image/png）→ chat 携 fileIds + `[E2E:plain]` | mock /stats 该次请求含 `image_url` 内容块且 data 前缀 `data:image/png;base64,`（ImageBlock→OpenAI 序列化路径端到端直证）；文本块不含该图路径提示（已内联，不降级） |
| F3 | 图片超限降级 | 上传 >5MB png（超过 `imageMaxMb`）→ 同 F2 | 无 image_url 块，改为路径提示文本（降级路径）；下载仍可用 |
| F4 | 同名重复上传唯一化 | 同 user 同会话连传两次 note.txt，第二次 chat 携两个 fileIds | 第二次注入路径为 `uploads/note_1.txt`（`uniqueWorkspacePath` 追加 `_1`）；两文件互不覆盖（read_file 分别读到各自内容） |
| F5 | present_file 输出文档交付 | `[E2E:file:deliver](report.md,<content>)`（write→present 两段式） | SSE 在 present_file 的 `TOOL_RESULT_END` 后出现 `file_ready{file_id,file_name,mime_type,size,download_url}`；`GET download_url` 字节 == content、Content-Type/Content-Length 正确、`Content-Disposition: attachment`、`X-Content-Type-Options: nosniff` |
| F6 | 内联预览规则 | 分别 present/上传 png 与 txt 与 zip，GET `/files/{id}?inline=1` | png/txt → `Content-Disposition: inline`（`FileController.java:184-186`：inline 仅 image/* 与 text/*）；zip → attachment；文件名 RFC5987 UTF-8 编码正确（中文名用例） |
| F7 | 下载负例 | GET `/files/not-a-uuid`；GET `/files/{随机UUID}` | 非 UUID 格式 → 400；不存在 → 404 |
| F8 | 上传校验矩阵（负例） | 逐条：空文件；`.sh` 声明 text/plain（危险扩展名）；note.png 声明 text/plain（扩展名/MIME 不一致）；21MB txt；同 user 连传 21 个 pending（沙箱关闭时 status=injected 计数语义按 `countPending` 实现固化） | 依次 400 `no_file_uploaded` / 415 `unsupported_file_type`（`.sh` 不在任何 MIME 映射集且属危险扩展名）/ 400 `extension_mime_mismatch` / 413 `file_too_large` / 429 `too_many_pending_files`；错误体 `{error, message}` 结构一致 |
| F9 | 用户键解析与 pending 隔离 | body `userId` 与 header `X-User-Id` 分别上传；user A 灌满 pending 后 user B 上传 | header 优先于 body（`FileController.java:206-211`）：同 fileId 归属与 pending 计数按 header 键走（经 F1 读文件行为区分）；A 429 时 B 仍 200（userKey 维度隔离） |
| F10 | history 文件卡片补齐 | F5 完成后 GET `/threads/{sid}/history` | 消息中文件交付项含下载卡片信息（file_id/download_url 补齐，ThreadController 卡片补齐语义）；刷新/换端恢复后卡片可再下载 |
| F11 | 会话删除级联文件语义 | F1 会话 DELETE `/threads/{sid}` 后 | file_asset 行级联清理（`SessionCleanupService` 联动语义，经下载 404 或保留策略按当前实现固化——行为记录型断言，实施时锁定） |

### 5.3 R 组 — 刷新续传与多副本（e2e-multi；R1 亦入 core）

| # | 场景 | 步骤 | 断言 |
|---|------|------|------|
| R1 | completed 续传 | 完成一 turn 后 GET `/status` → GET `/subscribe?afterSeq=0` | status=`completed`（最新事件 AGENT_END）；subscribe 全量回放（帧数 == Redis 事件数）后补 `done` 关流 |
| R2 | working 跨副本续传 | 实例 A 发 `[E2E:slow]`；收到第 k 个 delta 后**断开**原流（模拟刷新）；立即在实例 B `GET /subscribe?afterSeq=k` | status=`working`（租约在 A 持有，B 查共享 turn_lease）；断连不杀任务（durable 核心不变量）；B 侧收到 seq>k 全部帧 + `done`；A+B 两段帧并集无缺口无重复 |
| R3 | 同会话并发互斥 | slow turn 进行中，第二请求打 LB（可落任一副本） | 第二流持续收 `waiting` 帧（15s 间隔）不产生业务帧；断开后原 turn 正常 `done`；（120s 完整排队超时不在 CI 跑，见 §3.5） |
| R4 | kill 执行副本接管 | 实例 A 发 `[E2E:hang]` → 确认进入 working → `kill -9` A 进程 → 轮询实例 B `/status`（TTL=15s + 余量，≤25s） | B 侧 /status 由 `working` → `interrupted`（无租约+最新事件非终态）；`/subscribe` 收 `interrupted{reason:turn_interrupted}` 关流；后续同会话可正常发起新 turn（不死锁） |
| R5 | 跨副本并发 confirm 互斥 | `[E2E:hitl:submit]` 挂起后，对**两个副本**同时 POST `/threads/{sid}/confirm`（同 tool_call_id） | 一侧 200 执行恢复，另一侧 409（`turn_in_progress` / `confirm_already_consumed`）；工具只执行一次（approval MCP 状态机最终值唯一） |
| R6 | 跨副本事件完整性 | slow turn 在 A 执行，B 全程 `/subscribe?afterSeq=0` | B 收到帧数与 A 原生流一致（802/802 型对账，多副本改造的验收口径） |

### 5.4 H 组 — HITL 人工审批（e2e-core；R5 在 multi）

| # | 场景 | 步骤 | 断言 |
|---|------|------|------|
| H1 | ask 挂起 | `[E2E:hitl:submit](app-1)` | 流终止帧是 `permission_ask`（**无 done**）：`reply_id` + `tool_calls[{tool_call_id,name=submit_application,input}]`；`/status`=`waiting_confirm`；`/history` 的 `pendingConfirm.tools[].tool_call_id` 匹配、`source=agent_state` |
| H2 | 批准（confirm-stream） | H1 后 POST `/threads/{sid}/confirm-stream` `{results:[{tool_call_id,confirmed:true}]}` | 新流新 replyId（`AGENT_START`）；`TOOL_RESULT_END{state:SUCCESS}`（submit_application 真实执行，approval MCP 状态 app-1=approved）；收尾文本 → `done`；`/history` 挂起项清空（pendingConfirm=null）、tool_calls[].state=`success` |
| H3 | 拒绝 | 同 H2 但 `confirmed:false` | `TOOL_RESULT_END{state:denied}`，output 含 "Permission denied by user"；工具未执行（MCP 状态不变）；`done`；history state=`denied` |
| H4 | 重复确认 | H2 完成后再次 confirm（同 tool_call_id） | 409 `confirm_already_consumed`（state 优先路径 + 残留表行 CAS 双通道命中其一） |
| H5 | 同步 confirm | 用 `/confirm`（JSON）走批准路径 | 语义同 H2（非流式响应）；与 confirm-stream 结果一致 |
| H6 | 挂起期间新消息 | H1 挂起态下发 `[E2E:plain]` 新 turn | 新 turn 正常执行（租约已在挂起时让出）；完成后 `/status` 仍 `waiting_confirm`、pendingConfirm 保持 |
| H7 | deny 工具 | `[E2E:tool:...]` 触发 `get_application`（denied 注册） | **无 permission_ask**；`TOOL_RESULT_END{state:denied}`；`done`（deny 不挂起） |
| H8 | UI 代理 ask 拦截 | POST `/mcp/denied-or-approval/tools/submit_application`（无 confirmed） | 403 + `{needsConfirm:true, toolCalls[...]}`（卡片路径 ask 语义） |

### 5.5 M 组 — MCP Apps（e2e-core API 侧 + U6 UI 侧）

| # | 场景 | 步骤 | 断言 |
|---|------|------|------|
| M1 | ui 元数据随帧下发 | `[E2E:mcpapp:form](app-2)` 收流 | `TOOL_CALL_START` payload 含 `ui:{resourceUri:"ui://approval/application-form.html", server:"approval"}`；随后工具执行结果正常（show_application_form 是普通执行 + 卡片展示） |
| M2 | UI 资源拉取 | GET `/mcp/approval/resources/ui?uri=ui://approval/application-form.html` | `{html, mimeType:"text/html;profile=mcp-app", csp:{default}}`，html 非空 |
| M3 | 资源列表 | GET `/mcp/approval/resources` | 含上述 uri |
| M4 | 卡片工具代理 | POST `/mcp/approval/tools/confirm_application`（app_only 工具） | 远端真实执行（MCP 状态变化）；对比：经 LLM 侧不可见（M6） |
| M5 | 4.7 静默上下文更新 | POST `/mcp/ui-context` `{sessionId, content:"UICTX-MARK-1"}` → 同会话 `[E2E:plain]` | 响应 2xx 不触发回复；mock `/stats` 最新一次请求 `systemContent` 含 `UICTX-MARK-1`（Hook 注入直证）；不更新时（对照组会话）不含 |
| M6 | app_only 隔离 | GET `/tools` | `confirm_application` 带 `appOnly:true` 标记且不出现在 LLM 可调集合语义中（对照 `ui.tools`/`app_only` 声明） |

### 5.6 X 组 — 沙箱（e2e-sandbox job，mock OpenSandbox）

| # | 场景 | 步骤 | 断言 |
|---|------|------|------|
| X1 | 沙箱模式启动 | SANDBOX_ENABLED=true + mock:8090 起 `/health` | 200；mock /stats 出现 create（首个对话触发沙箱创建）；非沙箱功能不回归（S2 冒烟复跑通过） |
| X2 | Shell 执行 | `[E2E:execute](echo hello-e2a)` | TOOL_RESULT 文本含 `hello-e2a`；mock /stats 记录命令 exitCode=0；turn `done` |
| X3 | 文件沙箱化 + 回写 | `[E2E:tool:write](data/out.txt,hello)` → 同会话 `[E2E:tool:read](data/out.txt)` → 新会话（同 user）再读 | 首会话写入成功（mock 沙箱根目录落盘，/stats fileOps 记录）；同会话读回一致；**新会话**读回一致（USER 级沙箱复用 + WorkspaceSyncService 回写 agent_fs → 重注入） |
| X4 | USER 级复用/隔离 | 同 userId 两个 session 各触发一次对话；异 userId 各一次 | mock /stats：同 user 第二 session = `connect` 既有 sandboxId、**无新 create**；异 user = 新 create、不同 sandboxId；两 user 沙箱根目录互不可见（X3 型读文件负例） |
| X5 | 上传注入沙箱 | 上传 note.txt（沙箱模式 status=pending 挂账）→ chat 携 fileIds + `[E2E:tool:read](uploads/note.txt)` | 首次 exec 前 mock /stats fileOps 出现向沙箱 `uploads/note.txt` 的注入写入（`injectPendingUploads`）；read_file 读回 == 上传内容；turn `done` |
| X6 | 容器重建降级 | 会话用过沙箱后 `POST /admin/destroy/{id}`（模拟容器 GC）→ 同 user 再对话 + 读旧文件 | resume 连接 404 → 框架降级 create 新沙箱（SandboxManager.acquire 行为）；上传文件 `resetInjectedToPending` 回滚重注入（`OpenSandboxClient.java:112-118` 双保险）→ 旧文件在新沙箱仍可读 |
| X7 | 命令失败传播 | `[E2E:execute](ls /nonexistent-e2a)` | exitCode≠0；TOOL_RESULT 呈 ERROR 态（stderr 可见）；turn 仍 `done`（单工具失败不炸流） |

### 5.7 U 组 — Debug 页面 Playwright（e2e-core）

> 目标页：`http://127.0.0.1:8100/debug/`（同源，无需 example 的 proxy.py）。选择器契约固化于 `e2e/lib/selectors.js`，来源是现有 `static/debug/modules/chat.js` 与 `mcp-app-host.js` 的稳定 id/class（`#chatInput`、`#sendBtn`、`.confirm-card [data-act="approve"/"reject"]`、`.mcp-apps-container/.mcp-apps-iframe`、`#threadList`、`.nav-item[data-hash]`、文件上传/下载卡片元素——实施时从 `modules/chat.js` 附件与卡片渲染路径锁定），交互手法已被 `example/approval-forms/e2e/*.js` 验证可用。

| # | 场景 | 步骤 | 断言 |
|---|------|------|------|
| U1 | 模块渲染冒烟 | 遍历 10 个 `.nav-item` 路由（`#/`…`#/logs`） | hash 切换后 `#module-content` 非空且无全局报错（收集 console/pageerror） |
| U2 | 基础对话 | `#chatInput` 输入 `[E2E:plain]` → `#sendBtn` | 发送钮流式期间变 Stop/禁用；assistant 气泡出现且文本拼接完整（等 `done`）；无 console error |
| U3 | 会话列表/切换/回放 | U2 后新会话再对话 → 点 `#threadList` 旧项 | history 回放渲染出旧会话全部消息（含工具行状态图标）；`#uidInput` userId 隔离（切 userId 列表变化） |
| U4 | HITL 批准 | `[E2E:hitl:submit]` → `.confirm-card` → `[data-act="approve"]` | 确认卡渲染工具名/参数；点击后卡片转"处理中…"；后续工具结果 + 收尾消息 + 完成（复刻 example approval 流） |
| U5 | HITL 拒绝 | 同 U4 但 reject | 工具行呈 denied 态；agent 回复继续；无残留确认卡 |
| U6 | MCP App 卡片全链路 | `[E2E:mcpapp:form]` → `.mcp-apps-container.open` → iframe 内表单填写提交（`tools/call` 经代理）→ 若触发 ask 则出现 `.confirm-card` 再批准 | iframe（sandbox=allow-scripts）加载 ui:// 资源成功；卡内按钮 → 代理调用 → MCP 状态变更回流展示；AGENT_START 时旧卡 teardown 无泄漏（DOM 中旧 `.mcp-apps-container` 移除） |
| U7 | 刷新恢复（working） | `[E2E:slow]` 发送 → 首批气泡渲染后 `page.reload()` | 重载后历史消息恢复 + 流自动续传（游标）→ 最终消息完整无重复；期间无"重复发送" |
| U8 | 刷新恢复（waiting_confirm） | `[E2E:hitl:submit]` 挂起后 reload | 确认卡从 pendingConfirm 重建；批准后正常完成 |
| U9 | 多副本 UI 冒烟（multi job） | U2/U7 在 LB 入口重跑 | 页面行为对随机路由无感知（多副本对前端透明） |
| U10 | 文件上传对话（UI） | 附件按钮上传 note.txt → 发送 `[E2E:tool:read](uploads/note.txt)` | 上传出现附件预览条；回复含 read_file 工具行 + 文件内容摘录；图片上传呈缩略预览（image 内联路径的 UI 面） |
| U11 | 文件交付下载卡片（UI） | `[E2E:file:deliver]` → 消息内下载卡片 → 点击下载 → 切走会话再切回 | `file_ready` 卡片渲染（文件名/大小）；下载触发浏览器下载且内容正确；历史回放（U3 切换/刷新）后卡片仍在且可下载（F10 的 UI 面） |

### 5.8 A 组 — A2A（e2e-core）

| # | 场景 | 步骤 | 断言 |
|---|------|------|------|
| A1 | message/send | POST `/` JSON-RPC `message/send`（blocking，`[E2E:plain]`） | 200 JSON-RPC result 为 Message（含 text part） |
| A2 | message/stream | `message/stream` | SSE JSON-RPC 增量推送至终态 |
| A3 | tasks/get | A1 后 `tasks/get` | 返回 Task（MySqlTaskStore 读 agent_state 构造） |
| A4 | 通道限制声明 | S1 已覆盖 agent-card description | 含 "A2A 通道不支持 ask" 声明文案（防止限制被静默移除） |

> 明确**不做**：A2A 驱动 ask 工具（挂起态 A2A 无法批准是已声明的设计限制，`AgentCardNotes.A2A_CHANNEL_LIMITATION`），e2e 不为已知限制写红测。

---

## 6. 测试代码结构与技术选型

### 6.1 选型：Playwright Test 统一承载 API + UI

- **单一运行器**：API 用例以 Playwright Test 的 node 上下文直接 `fetch`（不依赖 page），UI 用例用 chromium——同一份 `playwright.config.ts`、同一套报告与 trace 体系，避免 node:test + playwright 双栈
- Playwright（用户指定）相对现有 Puppeteer 脚本的增益：内建 trace/screenshot/视频取证、`expect.poll` 天然适配"等 SSE 终态帧"、多 project 分组映射 job
- 语言：TypeScript（`e2e/tsconfig.json`，仅测试代码，不侵入主工程）

### 6.2 目录结构

```
agent-framework/e2e/
├── package.json              # devDeps: @playwright/test；scripts: test:<core|multi|sandbox>、check:fixtures
├── playwright.config.ts      # projects: api-core / api-multi / api-sandbox / ui / ui-multi；reporter: html + line
├── mock/
│   ├── llm-server.mjs        # §4.1 回放引擎（骨架 fork 自 bench/mock-llm/server.js，bench 原件不动）
│   ├── sandbox-server.mjs    # §4.4 mock OpenSandbox（管理 API + execd 代理 + /stats /admin）
│   └── fixtures/             # 真实服务录制件（提交入库，CI 回放的唯一响应来源）
│       ├── llm/              #   <scenario>.json：请求 + 流式 chunk 序列（§4.1.1）
│       └── sandbox/          #   管理/execd 各操作 req/resp + SSE 事件序（§4.4.1）
├── fixtures/
│   ├── agent-config/         # §4.3（AGENTS.md / tools.json / skills / mcp-configs×3）
│   └── nginx-lb.conf.template
├── scripts/
│   ├── env-up.sh / env-down.sh / run.sh     # §3.4（CI 与本地同路径）
│   ├── wait-ready.sh         # 通用就绪轮询（url, 超时, 间隔）
│   ├── record-llm.mjs                       # §4.1.1 LLM 录制反向代理（仅开发机，密钥走 .env.secrets）
│   └── record-sandbox-protocol.mjs          # §4.4.1 沙箱录制反向代理（仅开发机，对本地真实 Server）
├── lib/
│   ├── sse.ts                # POST 收流（fetch + ReadableStream 手解析 data:/id:/注释 hb）与 GET subscribe；帧收集器
│   ├── client.ts             # API 封装：chat()/confirmStream()/status()/subscribe()/history()/a2a()/upload()/download()
│   ├── files.ts              # F 组：multipart 上传构造（含 MIME/扩展名故意错配能力）、下载字节与响应头断言工具
│   ├── env.ts                # BASE_URL/runId 解析；ids() 生成 e2e-<runId>-<case> 唯一 userId/sessionId
│   ├── matchers.ts           # expectFrames(有序子集)/expectTerminal(done|permission_ask|error)/seqMonotonic
│   └── selectors.ts          # §5.7 选择器契约单点维护
├── tests/
│   ├── api-core.spec.ts      # S/F/H/M/A 组（可拆多文件）
│   ├── api-multi.spec.ts     # R 组
│   ├── api-sandbox.spec.ts   # X 组
│   ├── ui.spec.ts            # U1-U8、U10-U11
│   └── ui-multi.spec.ts      # U9
└── .runtime/                 # 运行产物（gitignore）：env.json、实例日志、文件存储、sandboxes/
```

### 6.3 关键实现约定

- **SSE 解析**：`POST /threads/chat` 是 POST，不能用 EventSource——`lib/sse.ts` 用 fetch + ReadableStream 逐行解析，处理 `data:`/`id:`/`: hb` 注释心跳；`collectStream(promise)` 返回 `{frames, terminal, abort()}`，`abort()` 模拟刷新断连（R2/U7 核心动作）
- **终态语义**：每条流式断言必先收敛到终态帧三选一，再做内容断言——避免"流没关就断言"的假绿
- **用例隔离**：`test.beforeEach` 调 mock `/reset`（LLM 与沙箱两个 mock 都清）；userId/sessionId 用例级唯一；跨用例共享会话仅限显式设计（S3/X3/X4 续会话）
- **不动被测物**：全部经 HTTP；DB/Redis 状态只经 API 间接断言（/status、/history、/threads），不直连——保持黑盒边界，避免测试耦合内部 schema
- **多副本寻址**：multi job 用例通过 `lib/env.ts` 同时暴露 `BASE`（LB :8100）与 `REPLICA_A/REPLICA_B`（:8101/8102）——R2/R4/R5 需要精确"在 A 执行、在 B 观察"
- **文件用例产物**：F 组的上传源文件与期望字节由 `lib/files.ts` 现场生成（含可控大小的 png/text 构造器），不落二进制夹具进库；下载断言用字节级比对

---

## 7. CI 工作流变更（agent-framework-ci.yml 增补草案）

```yaml
  e2e-core:
    name: E2E 核心（API+UI）
    needs: test
    runs-on: ubuntu-latest
    timeout-minutes: 25
    services:
      mysql:
        image: mysql:8.0
        env: { MYSQL_ROOT_PASSWORD: e2e-root, MYSQL_DATABASE: agent_framework_e2e,
               MYSQL_USER: e2e, MYSQL_PASSWORD: e2e-pass }
        ports: ['3306:3306']
        options: >-
          --health-cmd "mysqladmin ping -prootpass" --health-interval 5s
          --health-timeout 5s --health-retries 20
      redis:
        image: redis:7-bookworm
        ports: ['6379:6379']
        options: >-
          --health-cmd "redis-cli ping" --health-interval 5s
          --health-timeout 5s --health-retries 20
          --cmd "redis-server --appendonly yes --maxmemory-policy noeviction"   # 对齐 RedisEventLog 自检期望
    env: { E2E_GROUP: core }
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21', cache: maven }
      - uses: actions/setup-node@v4
        with: { node-version: '22', cache: npm, cache-dependency-path: agent-framework/e2e/package-lock.json }
      - run: mvn -B -DskipTests package        # working-directory: agent-framework
      - run: npx playwright install --with-deps chromium   # working-directory: agent-framework/e2e；首次后走 PLAYWRIGHT_BROWSERS_PATH + actions/cache
      - run: npm run check:fixtures        # 录制件覆盖校验：场景注册表 ↔ fixtures 一一对应 + 敏感信息扫描，缺件即红
      - run: ./scripts/run.sh core
      - if: always()
        uses: actions/upload-artifact@v4
        with: { name: e2e-logs-core, path: agent-framework/e2e/.runtime/ }

  e2e-multi:    # 同上骨架；E2E_GROUP=multi；run.sh 内起 8101/8102 双实例 + nginx LB(:8100)；只跑 api-multi+ui-multi
  e2e-sandbox:  # 同 core 骨板（services/步骤完全一致）；E2E_GROUP=sandbox；
                # run.sh 额外起 mock/sandbox-server.mjs(:8090)，实例 SANDBOX_ENABLED=true；只跑 api-sandbox
```

- `run.sh` 退出码透传给 job；`check:fixtures` 在三个 e2e job 均作前置（录制件缺失/含敏感串直接红，不带病进测试）；失败 artifact 含 §3.4 全部取证
- 三个 e2e job 并入现有 `concurrency` 策略；不新增 Secrets；无 docker build/pull（除 services/nginx）
- 预计总时长：单测 ~6min → e2e 三 job 并行（core ~12-15 / multi ~10-15 / sandbox ~8-12min），与 build-push 并行，master 全量 CI 墙钟 ~25min 封顶

---

## 8. 实施计划（分期合入，每期可独立回滚）

| 期 | 内容 | 交付物 | 验收标准（DoD） |
|----|------|--------|----------------|
| M0 录制件生产 | record-llm.mjs（对真实 LLM，密钥走 `.env.secrets`，仅开发机）+ record-sandbox-protocol.mjs（对本机真实 OpenSandbox Server）按场景清单录制，产出 `mock/fixtures/{llm,sandbox}/` 全量 + `check:fixtures` | PR1（纯夹具与录制脚本，无可执行测试） | check:fixtures 绿；录制件零请求头/零密钥（敏感模式扫描通过） |
| M1 基建+基础 API | e2e 目录骨架、llm-server.mjs（回放引擎+/stats）、agent-config 夹具、env-up/down/run、S1-S6 | PR2 + CI `e2e-core`(仅 S 组) | CI 绿；本地 `./scripts/run.sh core` 可复现；无新增 Secrets |
| M2 文件+会话+HITL | **F1-F11**、S7-S8、H1-H5、A1-A4 | PR3 | core job 全绿；F8 校验矩阵与单元测试语义一致（对照 `FileControllerTest` 如有） |
| M3 多副本 | nginx LB、R1-R6、U9 | PR4 + `e2e-multi` job | R4 接管 ≤25s 稳定通过 ×3 连续 run |
| M4 UI | U1-U8、U10-U11、selectors 契约、trace 取证 | PR5 | UI 组连续 5 run 零 flaky 后 retries 归零 |
| M5 mock 沙箱 | sandbox-server.mjs（§4.4.1 录制件结构回放 + 本地受控语义仿真）→ X1-X7 | PR6 + `e2e-sandbox` job | check:fixtures 契约校验通过；X 组连续 5 run 全绿 |
| M6 收尾 | 根/子 AGENTS.md 与 e2e/AGENTS.md 文档同步、超时阈值复盘 | PR7 | 文档与实现一致；总墙钟 ≤25min |

依赖关系：**M0 最先**（LLM 录制仅依赖开发机真实密钥，沙箱录制仅依赖本机真实 Server，两者都不进 CI）；M2 依赖 M1；M3/M4/M5 依赖 M0 的对应录制件，三者可并行。

---

## 9. 风险与开放问题

| # | 风险/问题 | 影响 | 对策 |
|---|-----------|------|------|
| R1 | mock OpenSandbox 与 SDK 线上协议存在偏差（字段名/SSE 事件序/代理路径形态；SDK 版本 `com.alibaba:opensandbox-sandbox:1.0.18`） | X 组假红或漏测 | 三重锚定：集成计划文档 API 面 + SDK 字节码方法集 + **真实服务录制件回放**（响应格式即真实 Server 原文）；`check:fixtures` 校验场景/路由覆盖；SDK 或 Server 升级时一键重录（录制脚本入库） |
| R2 | mock LLM 回放与 `agentscope-extensions-model-openai` 解析行为的兼容（tool_calls 增量分片、finish_reason、多轮 tool 消息回流形态） | 场景假红/假绿 | 回放体 = 真实 LLM 的 chunk 原文（录制时已被真实 SDK 成功解析过一轮），格式保真由构造保证；SDK 升级若改变解析行为，CI 回放用例即红——这正是要防的回归 |
| R3 | Debug 页选择器随前端重构漂移 | UI 组维护成本 | selectors.ts 单点契约 + U1 冒烟先行（选择器失应在 U1 即红，而非散落在深场景） |
| R4 | kill -9 接管窗口受 runner 调度抖动影响 | R4 偶发超时 | TTL 压至 15s、断言窗口 25s、`expect.poll` 轮询而非固定 sleep；连续 3 run 验证后再纳入硬门禁 |
| R5 | runner 上多进程/多端口的资源与端口冲突 | 环境不稳 | §3.1 端口静态规划无交集；mock 沙箱代理端口池 41000-41099 仅 sandbox job 监听；job 级隔离互不可见 |
| R6 | `ACQUIRE_TIMEOUT=120s` 硬编码（`ChatStreamController.java:82`） | R3 完整排队超时路径 CI 不可测 | 本期只测 waiting 帧语义；开放问题：是否值得改成可配置（小改动，但属主工程变更，另行评审，不在本设计内夹带） |
| R7 | approval_mcp.py 内存态 + 无 /reset | 用例间状态串扰 | 以应用名参数隔离（每用例唯一 app-N），不依赖清理；如需强化再给它加 /reset（改动 example 资产需单独评审） |
| R8 | 多副本两实例共享 MySQL（连接池 ×2）与 Redis | services 容器连接数上限 | mysql:8.0 默认 max_connections=151，实例池默认 10×2 + mock 零占用，余量充足；不做预调优 |
| R9 | UI 刷新续传（U7）对 slow 场景时序敏感（reload 时机） | 偶发 | 断言先行：reload 前必须已观察到 ≥1 个渲染中的 delta（`expect` 条件化），保证"刷新发生在 working 态"这一前提成立再触发 |
| R10 | agent-framework jar 启动失败（schema 初始化 fail-fast：`TurnLeaseStore.java:72-74`）导致整组超时等待 | 定位耗时 | env-up.sh 就绪探测失败时立即 dump 实例日志退出（fail fast + 取证，不空转满 25min） |
| R11 | F8 超限用例构造 21MB 文件、21 个 pending 上传的用例耗时 | job 拖长 | 21MB 用零填充 buffer 现场生成（~秒级）；pending 用例并行小文件；合计预算 <30s |
| R12 | mock 沙箱命令白名单过窄挡住 SDK 内部命令（如工作区 hydrate 的管道命令变体） | X3/X6 假红 | 白名单先按录制件实测命令集生成；未知命令记 /stats 并按"允许但告警"灰名单策略（CI 断言无白名单外命令出现在最终稳定版） |
| R13 | 录制件过期：真实 LLM 行为/模型版本或 OpenSandbox Server 升级后，录制件不再代表现网格式 | 假绿（旧格式仍被旧 SDK 接受）或误红 | 夹具带 `recordedAt/model` 元数据；升级 SDK/依赖/Server 的 PR 流程含"重录 + 重跑 check:fixtures"；录制脚本入库使重录为一键操作 |
| R14 | 录制件意外携带敏感信息（Authorization 头、真实密钥、内网地址） | 泄漏进仓库 | 录制器只落 body（头仅 Content-Type）；`check:fixtures` 附带敏感模式扫描（`sk-`/`Bearer `/内网 IP 段）；真实密钥只存在于开发机 `.env.secrets`（gitignored），CI 全程不触达 |

---

## 10. 附录 A：E2E 关心的 SSE 帧词表速查（依据 api-thread-spec v1.0）

| 帧 | 断言用途 |
|----|---------|
| `session_created` | 新会话首帧，session_id 权威来源 |
| `AGENT_START` / `AGENT_END` | turn 边界；replyId 变化（confirm 恢复 = 新 turn） |
| `MODEL_CALL_START/END` | token 计数字段存在性 |
| `TEXT_BLOCK_START/DELTA/END` | 文本拼接完整性（delta 拼接 == 夹具录制文本） |
| `TOOL_CALL_START/DELTA/END` | toolName/toolCallId；**M 组：`ui` 字段** |
| `TOOL_RESULT_START/ TEXT_DELTA/ END` | `state`：SUCCESS/ERROR/DENIED（deny 路径 H7/U5、命令失败 X7） |
| `permission_ask` | HITL 挂起终态帧（代替 done）；tool_calls[].tool_call_id |
| `file_ready` | present_file 交付（F5/F6/U11） |
| `waiting` | turn 排队（R3） |
| `interrupted` | `reason: turn_interrupted / lease_lost`（R4） |
| `error` | 负例终态（S6） |
| `done` | 正常终态；subscribe 回放完成后同样补发 |
| `: hb`（注释行） | 空闲心跳（解析器须忽略，不产生帧） |

## 附录 B：与既有测试资产的关系

| 资产 | 关系 |
|------|------|
| `src/test`（676 单测） | 不变，继续作为第一道门；e2e 是第二道 |
| `*IT` 环境门控集成测试 | 不变（本地/集群手动档）；e2e 不替代（IT 有 DB 级白盒断言） |
| `bench/` | **只读复用** mock-mcp 与编排手法；mock-llm fork 后 bench 原件不动 |
| `example/approval-forms` | **只读复用** approval_mcp.py 与 e2e 选择器先例 |
| 仓库根 `e2e/`（平台级） | 互不重叠：那边测"平台编排 agent-framework"，这边测"agent-framework 本体"；两者共同覆盖发布链路（根 e2e 的 file-support-ui / S 档场景矩阵是 F 组/U10-U11 的取材来源） |

---

## 11. 实施记录与框架缺陷复核（2026-09-19）

### 11.1 实施结果

| 组 | 用例数 | CI 结果 |
|----|--------|--------|
| api-core（S/F/H/M/A） | 27 | ✅ |
| api-sandbox（X） | 6 + 2 fixme | ✅ |
| ui（U） | 9 + 2 fixme | ✅ |
| api-multi + ui-multi + api-multi-kill（R/U9） | 7 | ✅ |
| 单测（mvn test） | 676 | ✅ |

CI run 35433743261 五个 job 全绿（单测 / E2E 核心 / E2E 多副本 / E2E 沙箱 / 构建推送）。

### 11.2 录制回放架构的关键实现语义

1. **调用索引按请求形状推导**：请求以 user 结尾 → `calls[0]`；以 tool 结尾 → tool 消息条数即索引；拒绝恢复（末条 tool 含 "Permission denied"）→ `variants.denied`。
2. **录制期策展**（`record-llm.mjs` flush）：过滤后台记忆提取调用（system 含 "memory extraction assistant"）+ 前导裁剪（首个真实 tool_call/非空 content 之前的噪声调用，如 mimo 的 NO_REPLY）+ 剥除模型顽固附传的 `, "content": null` + `{{appId}}` 占位符改写。
3. **回放期参数覆盖**（`ARGS_OVERRIDE`）：录制件中 app id 被切分进多个 delta 片段、字符串替换不可行——`hitl-submit`/`mcpapp-form` 的 tool_call arguments 在回放期整体重写为 `{"application_id":"<标记参数>"}`。
4. **后台调用识别**：无 system 消息的请求（标题生成/记忆提取变体）一律合成良性响应，防止偷走主对话的调用序。
5. **`/threads/chat` 正常结束无显式 `done` 帧**（AGENT_END 后关流）；`done` 由 `/subscribe` 的 Tailer 补发。
6. **双流 `id:` 语义不同**：chat 流为事件哈希，subscribe 流为数字 seq（R 组断言用后者）。
7. **delta 走 1s 攒批窗口**（`SessionEventStore.DEFAULT_FLUSH_INTERVAL_MS`，`_DELTA` 后缀攒批、里程碑立即刷）——对时序敏感的断言必须容忍该窗口。

### 11.3 框架语义缺陷（逐条实测复核 → 已修复）

> **状态更新（2026-09-19）**：D1/D3/D6 三条已修复并通过 e2e 验证（转正 H2/H5/F5/X9/U11）；
> D4 属 SDK 设计约束（非本框架缺陷），H6 按真实行为断言。修复提交 `79dd546`。

| 缺陷 | 修复方式 | 验证 |
|------|---------|------|
| **D1** HITL 批准后参数丢失 | `resolveConfirmContext` 改为 **confirm_context 表优先**（该表来自 `RequireUserConfirmEvent`，携带完整参数），state 仅在表行缺失时回落；两路都命中时表内参数为权威，仅反向异常才用 state 补齐 | e2e：批准后 `state=success`、`input={"application_id":...}`（修复前 `state=error` + 空参数）；H2/H5 从 fixme 转正 |
| **D3** write_file→present_file 断裂 | 三处：① 工具名判定改用 `ToolCallStartEvent` 登记表（delta 帧的 `getToolCallName()` 恒为占位符 `__fragment__`）；② `WorkspaceReader` 命名空间对齐框架 `RemoteFilesystemSpec(USER)`（`agents/{agentName}/users/{userId}`）；③ 新增 `kv_sync_key` 表登记 (relPath→userKey)，读取端反查写入侧键（Channel 链路 ctx.userId 是网关 peer） | e2e：`file_ready 帧存在 = true`（修复前恒 false）；F5/X9/U11 从 fixme 转正 |
| **D6** app_only 短路 ask | `registerAll` 拆分 `readOnlyHint`：只有 `permissions.read_only` 才强制只读，`ui.app_only` 与 ActiveMCP 子集过滤不再影响权限评估 | 单测 `filteredRegistrationShouldNotForceReadOnlyWhenHintFalse` |
| **D4** ASKING 态拒绝新 turn | 非本框架引入（AgentScope SDK 会话级守卫）；H6 按真实行为断言，`api-thread-spec` 描述待更新 | H6 断言 error 帧含 "ASKING" |

#### 11.3.0 原始实测记录（修复前）



| # | 缺陷 | 实测证据 | 根因位置 | 影响面 |
|---|------|---------|---------|--------|
| **D1** | **HITL 批准后恢复执行时工具参数丢失**（确认，最严重） | 挂起时 `permission_ask.tool_calls[0].input` = `{"application_id":"APP-0001"}`，`confirm_context.tool_calls_json` 同样完整；批准后 `TOOL_RESULT_END.state=ERROR`（`argument "content" is null`），history 终态 `input={}` | `agent_state.state_data` 中 SDK 持久化的 assistant `tool_use.input` **本身为 `{}`**（asking 时 SDK 不落参数）；而 `AgentRuntimeService.resolveConfirmContext` **优先** `loadConfirmContextFromState`（经 `AgentStateReader.loadAskingSnapshot` 从 state 重建 ToolUseBlock），用空 input 覆盖了表里的完好参数 | HITL 批准路径全断（H2/H5/R5）；凡 ask 工具带必填参数必失败 |
| **D3** | **非沙箱模式 write_file→present_file 断裂**（确认） | `write_file` 报 SUCCESS（"Written to report.md"），`present_file` 返回 `{"error":"file not readable in workspace: report.md"}`，无 `file_ready` 帧 | `ChatStreamController` 的 KV 同步判定 `if (event instanceof ToolCallDeltaEvent delta && "write_file".equals(delta.getToolCallName()))` **恒为 false**——实测 `ToolCallDeltaEvent.getToolCallName()` 返回占位符 **`"__fragment__"`**（工具名只在 `ToolCallStartEvent` 上）；于是 `accumulateWriteFileInput` 从不累积、`syncWriteFileToKv` 永不执行，present_file 从 KV 读不到 write_file 写在 SDK 本地会话目录的文件 | F5/F10/U11/X9；非沙箱模式下"生成文件并交付"类功能失效 |
| **D6** | **`ui.app_only` 与 `permissions.tools.ask` 不能同 server 共存**（确认） | 同 config.yaml 同时声明二者时，ask 工具被注册为 read-only，HITL 被短路（无 permission_ask 帧、直接执行） | `McpToolRegistrar.registerAll`：`hasAppOnly` 与 `forceReadOnly` 一起走 `registerReadOnly` 手动注册路径（只读语义绕过权限系统） | 夹具设计约束（e2e 用 approval/bench/denied/cards 四个逻辑 server 规避）；使用方需知 |
| **D4** | **ASKING 态下新 turn 被拒绝** | 挂起后发 `[E2E:plain]` → `AGENT_START,error`，error 文本 "Agent is paused for human-in-the-loop confirmation: ... need your approval before the agent can continue" | AgentScope SDK 的会话级守卫（非本框架引入）；租约已让出但 SDK 拒绝推进同一会话 | H6 已按真实行为断言；`api-thread-spec` 中"挂起期间可发新消息"的描述与实现不符，应更新 |

#### 11.3.1 复核后撤销的两条（原判断为 e2e 自身假设错误）

| # | 原判断 | 复核结论 | 证据 |
|---|--------|---------|------|
| D2 | 客户端断连后剩余 TEXT delta 不再持久化 | **不成立**——断连后事件继续落库（XLEN 3→11）；`subscribe?afterSeq=cutSeq` 拿到剩余 3 个 delta；`afterSeq=0` 全量回放 4 个 delta，文本完整 `"端到端链路畅通，测试正常。"` | 原 R2 失败是**时序竞态**：断连后立即订阅时 delta 仍在 1s 攒批窗口内未落 Redis（§11.2-7）。R6 的 `AGENT_START` 缺失同源（tailer 追赶起点落在窗口内） |
| D5 | hang 型停顿在模型流停滞前不产事件 | **不成立**——hang turn 在 2s 内即落库 `AGENT_START, MODEL_CALL_START, TEXT_BLOCK_START`（XLEN=3），`/status` 稳定报 `working`，kill 后按租约 TTL 转 `interrupted` | 原 R4 失败是探针查询的 Redis 与实例配置不一致（多实例/历史会话 key 混淆）。R4 可改回 hang 场景 |

> **教训**：D2/D5 的"缺陷"实为 e2e 自身的时序与环境假设错误。凡断言"数据丢失"前，必须先排除攒批窗口与多实例观测面混淆——这是 R2/R4 两轮返工的根因。

### 11.4 运维要点

- 录制器与 jar 必须**成对重启**（半开连接池会让录制流中断/丢 chunk）；录制器已内置 per-request 记录、空流丢弃、mtime 夹具缓存失效。
- mock LLM 缺夹具即 500（严格模式）；`MOCK_LLM_ALLOW_SYNTH=1` 仅限本地排障。
- mock 沙箱命令白名单：`echo/ls/cat/mkdir/tar/base64/rm/printf/test/wc/stat/find/sort/head/tail/grep/sed/sh/true/false`；白名单外拒绝并记 `/stats`。
- 应用自带 logback 强写 `/applog`，CI 非 root 不可写——`env-up.sh` 已注入仅控制台的 `logback-e2e.xml`（`LOGGING_CONFIG`）。
- `agent-framework/.gitignore` 的 `lib/` 已根锚定（`/lib/`），避免再次误伤 `e2e/lib/`。
