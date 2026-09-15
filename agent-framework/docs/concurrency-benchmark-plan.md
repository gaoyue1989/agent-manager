# Agent Framework 并发压测设计方案（沙箱模式）

>
> **环境核对（2026-09-15）**：本机 OpenSandbox Server 已就绪（docker `opensandbox-server`，`http://127.0.0.1:8090/health` → healthy）；本地 MySQL 监听 127.0.0.1:3307（agent-framework 默认 checkpoint 库所在）；宿主机 8C / 8G（kind 集群常驻约 2G，可用约 3G）。本地已有 `agent-framework:latest` 镜像（Dockerfile 运行阶段 JRE 21，`JAVA_OPTS` 可覆盖，默认 `-XX:MaxRAMPercentage=75`）。agent-framework 内置工具经 harness jar 确认存在 `ShellExecuteTool` / `FilesystemTool`（沙箱模式下经 execd 执行）。
>
> **资源约束（2026-09-15 定稿）**：被测服务固定运行在 **1C / 1G**（docker `--cpus=1 --memory=1g --memory-swap=1g` 硬限），模拟最小规格生产部署；每个稳态档执行 **3 分钟**。MySQL / OpenSandbox / mock / 压测器均在宿主机正常运行，不施加该约束。

## 1. 背景与目标

### 1.1 背景

沙箱集成已落地（`SANDBOX_ENABLED=true`，OpenSandbox，USER 级复用 + 每请求记忆回写，详见 [opensandbox-integration-plan.md](opensandbox-integration-plan.md)），但**沙箱模式下的并发承载能力从未量化**。沙箱链路在每个 turn 中新增了多段开销与串行化点：

```
POST /threads/{sid}/chat
  ├─ Turn 租约（MySQL turn_lease，同 session 串行）
  ├─ ChatUiChannel → HarnessAgent.streamEvents
  │   ├─ SandboxLifecycleMiddleware.acquire（同 userId GET_LOCK 串行；首次 create、后续 resume）
  │   ├─ LLM 调用（宿主侧 HTTP）
  │   ├─ 文件/Shell 工具 → 沙箱 execd（每条命令一次 HTTP 往返）
  │   └─ 记忆读写 / WorkspaceSyncService 回写（沙箱 ↔ MySQL agent_fs）
  └─ AGENT_END → 释放租约
```

### 1.2 目标

| # | 目标 | 产出 |
|---|------|------|
| G1 | 沙箱模式**最大可用并发数**（满足验收口径的最高 inflight 档位） | 并发-错误率曲线 |
| G2 | 峰值吞吐 **req/min**（稳态阶段成功请求数/分钟） | 并发-吞吐曲线 |
| G3 | 延迟分位 P50 / P95 / P99 + 首 token 时延（TTFT） | 各档延迟表 |
| G4 | 瓶颈定位：JVM（GC/堆）、MySQL（连接池/锁）、OpenSandbox（创建/execd） | 观测数据 + 归因结论 |

### 1.3 非目标

- 不评估真实 LLM 回复质量（LLM 用 mock，确定性脚本化响应）
- 不覆盖 A2A 协议全量方法与前端 UI 链路（主对话入口 `POST /threads/{sid}/chat` 单口径）
- 不做长时间稳定性/内存泄漏 soak 测试（每档 3 分钟、每场景 ≤ 25 分钟，可后续加档）

---

## 2. 测试口径定义

| 术语 | 定义 |
|------|------|
| **1 个请求** | 一次完整对话 turn：`POST /threads/{sessionId}/chat` 发出 → SSE 收到 `done` 帧 |
| **成功请求** | 收到 `done` 帧，且全程无 `error` 帧、HTTP 非 5xx |
| **失败请求** | error 帧 / 连接超时（默认 120s）/ 断连 / 5xx |
| **并发 C** | 闭环模型的 inflight 请求数（同时未完成的最大请求数） |
| **"支持并发 C"** | 该档错误率 < 1% 且 P95 ≤ 10s 且无服务崩溃（进程存活、后续档可恢复） |
| **最大并发** | 满足"支持"口径的最高档 C（受沙箱池上限 10 约束，结论分区间解读，见 §6.1） |
| **req/min** | 该档 **3 分钟稳态窗口**内成功请求数 ÷ 3（取各档峰值） |

> P95 ≤ 10s 为定稿 SLO（2026-09-15 用户确认；mock LLM 毫秒级响应下正常 turn 应秒级完成，10s 命中即说明排队/锁等待已实质影响体验）。可经 `bench` 参数覆盖。

---

## 3. 总体拓扑

```
┌─────────────────────── 宿主机（8C/8G，单机压测）───────────────────────────────┐
│                                                                            │
│  ┌──────────────┐  POST /threads/{sid}/chat (SSE)  ┌────────────────────┐  │
│  │ load runner  │ ───────────────────────────────▶ │ bench-agent-fw 容器│  │
│  │ (Node.js)    │ ◀──────────── SSE 帧流 ───────── │  ★ 1C / 1G 硬限 ★  │  │
│  └──────┬───────┘                                  │ agent-framework    │  │
│         │ 周期采样                                   │ jar :8101          │  │
│         ▼                                          │ SANDBOX_ENABLED=   │  │
│  ┌──────────────┐   GET /v1/sandboxes              │ true               │  │
│  │ observer     │ ─────────────────────────┐       └─────┬─────┬────────┘  │
│  │ (docker stats│                          ▼             │     │           │
│  │  + jcmd +    │                 ┌────────────────┐ ┌───▼───┐ ┌▼────────┐ │
│  │  MySQL)      │                 │ OpenSandbox    │ │mock-  │ │mock-mcp │ │
│  └──────────────┘                 │ Server :8090   │ │llm    │ │:18082   │ │
│                                   │ (docker 常驻)  │ │:18081 │ │stream-  │ │
│                                   └───────┬────────┘ │OpenAI │ │ableHttp │ │
│                                           ▼          └───────┘ └─────────┘ │
│                                  沙箱容器池（≈ userId 数，不受 1C1G 约束）    │
│                                                                            │
│  MySQL :3307（本地实例，独立压测库 agent_manager_bench）◀─ turn_lease/GET_LOCK│
│                                                           /agent_state/agent_fs
└────────────────────────────────────────────────────────────────────────────┘
```

关键决策：

| 决策 | 选择 | 理由 |
|------|------|------|
| 被测形态 | **docker 容器**（镜像 `agent-framework:latest`，容器名 `bench-agent-fw`，映射 `8101:8100`） | 硬限资源必须容器化；本地已有当日构建镜像，无需重新构建 |
| 资源约束 | `--cpus=1 --memory=1g --memory-swap=1g`（**1C/1G，禁 swap**） | 模拟最小规格部署；JDK 21 cgroup 感知自动收敛线程池/ GC，无需手工 `ActiveProcessorCount`；`-XX:MaxRAMPercentage=75`（镜像默认）→ 堆约 750m |
| 接入方式 | **直连容器 8101**，不经宿主 nginx / ingress | 控制变量；ingress 档不在本期范围 |
| 容器网络 | 宿主依赖经 docker0 网关 `172.17.0.1` 访问（MySQL :3307、mock-llm :18081、mock-mcp :18082、OpenSandbox :8090，均为宿主 0.0.0.0 发布端口） | bridge 默认网络即可，无需 host 网络 |
| 数据库 | 复用本地 MySQL :3307，**独立库 `agent_manager_bench`** | 不污染 `agent_manager_test`；同实例保留 GET_LOCK/连接池真实行为 |
| 压测工具 | **自研 Node.js runner**（undici/fetch 流式读 SSE） | 与 e2e 工具链一致、零新增重型依赖；SSE 逐帧解析与 `done` 判定精确可控，k6 的 SSE 支持仍属实验性 |
| Mock 实现 | Node.js（`mock-llm` + `mock-mcp` 两个独立进程，跑宿主机） | 同上；无需编译，便于调延迟参数 |
| 资源约束范围 | 仅被测服务 1C/1G；MySQL / OpenSandbox / mock / runner 不限 | 归因清晰：瓶颈读数都指向被测服务自身或其显式依赖 |

---

## 4. Mock 服务设计

### 4.1 mock-llm（OpenAI 兼容，:18081）

覆盖 `agent.llm.base-url`（`LLM_BASE_URL=http://127.0.0.1:18081/v1`）。

**端点**：

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/v1/chat/completions` | 支持 `stream: true`（SSE chunk）与 `stream: false`（JSON），行为一致 |
| GET | `/stats` | 调用计数、p50/p95 时延、按场景分布（供报告与压测端对账） |
| POST | `/reset` | 清零统计（每档开始前调用） |

**脚本化响应状态机**（确定性，不依赖真实模型）：按**场景指令 + messages 尾部 role** 决定响应：

```
解析请求最后一条消息：
  role == "tool"  → 进入收尾轮：返回固定文本
                    "BENCH-OK <echo of tool marker>"（含 usage 固定 token 数）
  role == "user"  → 首轮：解析消息内嵌场景标记 [BENCH:plain|file|shell|mcp]
                    ├─ plain → 直接返回固定文本（1 次 LLM 调用结束）
                    └─ file/shell/mcp → 返回 tool_calls（1 个工具调用）：
                         file → write_file("/workspace/bench/<sid>.txt", 固定内容)
                         shell→ execute("python3 -c \"print('BENCH-SHELL-OK')\"")
                         mcp  → bench_echo("ping")   （mock MCP 工具）
```

要点：

- **每请求 LLM 调用次数固定**（plain=1，其余=2），压测端与 `/stats` 对账可发现异常重试
- `tool_calls` 的工具名以实施时 `/system-prompt` 输出的实际内置工具名单为准（现为 `execute` / `write_file`，harness 2.0.3 已确认存在对应 Tool 类）；若名单有出入仅改 mock 的工具名常量
- 可配置响应延迟 `MOCK_LLM_DELAY_MS`（默认 0，测系统本体；提供模拟真实 LLM 的档位用于对比，非本期必跑）
- `finish_reason` 正确返回 `tool_calls` / `stop`；流式按 3~5 个 chunk 吐出，避免非流式分支未覆盖
- 固定 `usage`（如 in=200 / out=50），供事件流 token 断言

### 4.2 mock-mcp（streamableHttp，:18082）

覆盖 bench agent 的 `mcp-configs/bench/config.yaml`（`connection.type: streamableHttp`）。

**行为**：

| JSON-RPC 方法 | 响应 |
|---------------|------|
| `initialize` | 标准 capabilities（tools） |
| `tools/list` | 1 个工具：`bench_echo(text)` |
| `tools/call` | 固定回显 `"BENCH-MCP-OK:" + text`，延迟 <1ms |

- 响应以 `application/json` 返回（单响应模式）；若 `McpToolRegistrar`/SDK 要求 SSE 编码响应，mock 加 `text/event-stream` 分支（实施时以联调为准，两模式封装同一 handler）
- `GET /stats`、`POST /reset` 同 mock-llm
- 只读语义（`permissions.read_only: true`），避免 HITL 拦截把请求打成 `permission_ask` 暂停

### 4.3 为什么不用真实 LLM/MCP

真实 LLM：响应延迟抖动大（秒~十秒级）、token 速率不可控、按请求计费、无法保证触发工具调用的确定性——会把被测系统本身的并发信号完全淹没。mock 后单请求时延由**被测链路自身**决定，拐点归因干净。

---

## 5. 被测 Agent 配置（bench 专用 OAF 包）

新增 `agent-framework/bench/agent/`：

```
bench/agent/
├── AGENTS.md                          # frontmatter：name=Bench Agent, slug=agentmanager/bench-agent
│                                      # permission.mode=bypass, require_confirmation=false
│                                      # system prompt 精简（百字级，减少 token 与解析开销）
└── mcp-configs/bench/config.yaml      # connection: streamableHttp → http://127.0.0.1:18082/mcp
                                       # permissions.read_only: true, tools: bench_echo: allow
```

**agent-framework 启动环境变量**（由 `run-bench.sh` 注入，地址一律为容器内视角的 `172.17.0.1`）：

| 变量 | 压测值 | 说明 |
|------|--------|------|
| `SERVER_PORT` | `8100`（容器内） | 宿主映射 `8101:8100`，避开常驻 8100 |
| `LLM_BASE_URL` | `http://172.17.0.1:18081/v1` | 指向 mock-llm |
| `LLM_API_KEY` / `LLM_MODEL_ID` | `bench-mock` / `bench-model` | mock 不校验，占位即可 |
| `CHECKPOINT_JDBC_URL` | `jdbc:mysql://172.17.0.1:3307/agent_manager_bench` | 独立压测库 |
| `SANDBOX_ENABLED` | `true` / `false`（B0） | 场景切换 |
| `SANDBOX_MEMORY_MB` | `512` | 沙箱池 10 × 512Mi ≤ 5Gi 上限，为宿主机 8G（kind 常驻约 2G）留裕量（生产默认 1024 保留为对照可选） |
| `SANDBOX_TIMEOUT_MINUTES` | `240` | 避免压测中途沙箱过期重建污染读数 |
| `OPENSANDBOX_SERVER_URL` | `172.17.0.1:8090` | 本机 OpenSandbox Server |

**容器运行规格**（`run-bench.sh` 统一拉起）：

```bash
docker run -d --name bench-agent-fw \
  --cpus=1 --memory=1g --memory-swap=1g \
  -p 8101:8100 \
  -v "$BENCH_DIR/agent:/config:ro" \
  -e SERVER_PORT=8100 -e AGENT_CONFIG_DIR=/config \
  -e LLM_BASE_URL=http://172.17.0.1:18081/v1 ... \
  agent-framework:latest
```

> 1G 限制内堆约 750m（镜像默认 `MaxRAMPercentage=75`），余量给 metaspace/线程栈/直接内存；`--memory-swap=1g` 禁 swap，OOM 即失败请求计入读数（不静默换性能）。

**建库**（`bench/sql/init-bench-db.sql`，幂等）：`CREATE DATABASE IF NOT EXISTS agent_manager_bench`；表（`agent_state`/`agent_fs`/`turn_lease`/...）由服务启动自动建。

---

## 6. 负载模型与场景矩阵

### 6.1 负载模型

- **闭环（closed-loop）**：runner 维持 C 个 inflight 请求，任一请求完成立即补位发起下一个——测"系统能承住多少并发"，而非固定速率打流
- **会话/用户分配**：
  - `sessionId`：**每请求全局唯一**（`bench-{stage}-{seq}`）。绕开 turn 租约的同会话排队语义（那是产品特性，不是要测的并发瓶颈）
  - `userId`：从大小为 U 的池取（`bench-user-{0..U-1}`）。沙箱按 USER 隔离 → 沙箱数量 ≈ U
- **U 的取法（定稿：池上限 10）**：默认 `U = min(C, 10)`。C ≤ 10 的档位测的是**服务并发能力**（每请求独立用户，无池化约束）；C > 10 的档位并行度被用户池结构性封顶在 10（同用户请求被 `JdbcSandboxExecutionGuard` GET_LOCK 串行化），读数按**"池受限区间"**单独解读——这本身就是"沙箱池 = 10"这一部署约束下的真实结论，报告分区间给结论，不混为一谈
- **预热**：正式计时前，每个 userId 先串行发 1 次请求，确保沙箱全部处于已创建（resume）状态——把 create 开销与稳态开销分离
- **阶梯**：`C = 1, 2, 4, 8, 10, 16, 32`（默认；**C=10 为池边界档**，是"服务并发能力"区间的最大档；C=16/32 为池受限区间，`STAGES` 可改）。每档先 15s ramp（线性拉起 inflight），再**稳态固定运行 180s（3 分钟）**。1C 约束下更大并发几乎必然触发停止条件，故不进默认阶梯（仍可参数化开启）
- **单档耗时**：≈ 15s ramp + 180s 稳态 + 15s 收尾 ≈ **3.5 分钟**；单场景 7 档 ≈ 25 分钟；核心子集 4 场景（B0/B1/B3/B5）≈ **1.6 小时**；全矩阵 6 场景 ≈ **2.5 小时**
- **停止条件**（当前场景终止，不终止整个压测）：错误率 > 5%，或 P95 > 30s（SLO 10s 的 3 倍兜底，让劣化曲线有可见度后即停），或 agent-framework 进程退出（1G 下 OOM 被杀即命中此条）

### 6.2 场景矩阵

| 场景 | 沙箱 | LLM 轮数 | 每 turn 沙箱动作 | 目的 |
|------|------|---------|------------------|------|
| **B0** 非沙箱纯文本 | ✗ | 1 | 无 | **基线**：无沙箱时的系统上限，供差值归因 |
| **B1** 沙箱纯文本 | ✓ | 1 | acquire/resume + 记忆读写 + sync-back 回写 | 沙箱**固定开销**本身（无工具） |
| **B2** 沙箱 + 文件工具 | ✓ | 2 | + write_file / read_file 经 execd | 文件工具路径吞吐 |
| **B3** 沙箱 + Shell | ✓ | 2 | + execute（python3 print）经 execd | 最重路径：进程拉起 + 命令执行 |
| **B4** 沙箱 + MCP 工具 | ✓ | 2 | 同 B1 + mock MCP 调用 | MCP 注册/调用链路叠加开销 |
| **B5** 同用户并发 | ✓ | 1（B1 同款） | 同 userId × 多 session 并发 | 验证 `JdbcSandboxExecutionGuard` 串行化上界：吞吐 ≈ 1/单请求时延，**预期不随 C 增长**（U=1） |

> 每场景独立跑完整阶梯。B1 与 B0 的吞吐差 = 沙箱固定开销；B3 vs B1 = 工具执行开销。

---

## 7. 指标采集

### 7.1 压测端（每请求）

| 指标 | 来源 |
|------|------|
| 总时延 | POST 发出 → `done` 帧 |
| TTFT | POST 发出 → 首个非 `waiting` 的 SSE 帧 |
| `waiting` 帧数 / 排队时长 | 帧计数（Turn 租约或 DB 锁等待的直接证据） |
| LLM 调用次数（对账） | 与 mock-llm `/stats` 增量比对，异常重试即暴露 |
| 结果 | success / error / timeout + error 文本样本（前 50 条全量留存） |

原始数据写 `bench/results/{scenario}/{stage}.jsonl`，供报告与复查。

### 7.2 系统端（observer，随阶梯周期采样，默认 5s）

| 组件 | 采样方式 | 指标 |
|------|---------|------|
| agent-framework 容器 | `docker stats --no-stream bench-agent-fw` | CPU%（1 核上限，持续 ≈100% 即 CPU 饱和）、内存/1G |
| agent-framework JVM | `docker exec bench-agent-fw jcmd 1 GC.heap_info` + `jstat -gcutil`（镜像无 jcmd 时退化为容器内存曲线） | 堆用量、GC 次数/耗时 |
| MySQL | `SHOW GLOBAL STATUS`（`Threads_connected`、`Innodb_row_lock_waits`）+ `SHOW PROCESSLIST` 长度 | 连接数、锁等待 |
| OpenSandbox | `GET /v1/sandboxes` | 沙箱总数曲线（应 ≈ U 且稳定；增长=异常重建） |
| 宿主机 | `/proc/loadavg` | load（区分"被测容器 1 核饱和"与"宿主机整体争抢"） |

### 7.3 报告

`bench/load/report.js` 汇总 JSONL → `bench/results/report-{timestamp}.md`：

- 每场景一张表：C / 成功数 / 错误率 / RPS / **req/min** / P50 / P95 / P99 / TTFT P50 / waiting 帧均数
- 并发-吞吐曲线（ASCII 条形或 markdown 表，不引图表库）
- 各场景结论行：`B3: 最大并发 = 8，峰值 47 req/min，瓶颈初判 = Hikari 连接池（Threads_connected 顶满 10）`

---

## 8. 目录与产物

```
agent-framework/bench/
├── README.md                # 使用说明（启动顺序、参数、报告解读）
├── mock-llm/
│   ├── package.json         # 零三方依赖（node:http 原生实现）
│   └── server.js
├── mock-mcp/
│   ├── package.json
│   └── server.js
├── agent/                   # bench 专用 OAF 配置（见 §5）
│   ├── AGENTS.md
│   └── mcp-configs/bench/config.yaml
├── sql/
│   └── init-bench-db.sql
├── load/
│   ├── runner.js            # 并发调度 + SSE 解析 + 指标聚合
│   └── report.js
├── run-bench.sh             # 一键编排：建库 → 起 mock ×2 → 起 agent-framework →
│                            #   预热 → 逐场景逐档执行 → 报告 → 停进程
└── results/                 # 运行产物（gitignore）
```

`run-bench.sh` 参数：`SCENARIOS=B0,B1,B3,B5`（默认核心子集，可改 `B0,B1,B2,B3,B4,B5` 全矩阵）、`STAGES=1,2,4,8,10,16,32`、`USER_POOL=10`、`STAGE_SECONDS=180`、`P95_SLO_MS=10000`。

---

## 9. 预期瓶颈与读数解释（实施前先立此存照）

| # | 机制 | 预期表现 | 判读 |
|---|------|---------|------|
| 1 | Hikari 连接池（默认 **10**） | C > 10 后吞吐不再增长、waiting 帧增多 | `Threads_connected` 顶格即证据；调优项（`spring.datasource.hikari.maximum-pool-size`）记入报告建议 |
| 2 | `JdbcSandboxExecutionGuard`（MySQL GET_LOCK，同 userId） | 同用户请求串行；B5 吞吐恒定 ≈ 1/单请求时延；C > 10 档并行度被池结构性封顶 10 | B5 曲线水平即验证；跨用户（U>1）在 C ≤ 10 区间不受影响；C > 10 档吞吐停在 10/单请求时延附近属预期，不算服务缺陷 |
| 3 | Turn 租约（同 sessionId） | 压测已用唯一 sid 规避 | 若出现 waiting 帧 → 检查 sid 生成是否泄漏复用 |
| 4 | OpenSandbox execd 单沙箱内串行 | 同用户并发被 GET_LOCK 先行串行化，execd 队列不应成为主要瓶颈 | B3 与 B2 差值 = shell 进程拉起成本 |
| 5 | 沙箱 create（首轮，含 workspace 注入）vs resume | 预热后 create 不应出现在稳态窗口 | `/v1/sandboxes` 数量上升 = resume 失败降级重建，读数作废需排查 |
| 6 | 记忆回写（每请求 agent_fs 写） | B1 单请求时延含 1~2 次 DB 写 | 与 B0 差值共同归因 |
| 7 | **被测服务 1 核 CPU 饱和（1C 约束下的首要预期瓶颈）** | 并发升高后容器 CPU 持续 ≈100%（docker stats），吞吐停在单核能力上、延迟线性上涨 | 归因顺序：先看容器 CPU 是否先于其他指标饱和；1C 下 Tomcat（默认 200 线程）+ reactor + GC 共享单核，C=8~10（池边界前）即可能饱和——这正是本次要量化的结论 |
| 8 | 宿主机整体（8C，runner/mock/沙箱池共享剩余 7 核） | runner 与沙箱池不应打满宿主 | observer 记录 loadavg；若宿主 load > 8×2 则该档读数标注"宿主争抢"，归因不算服务 |
| 9 | 容器 1G 内存 | 堆 750m + 堆外，B1~B4 稳态应稳定；OOM-Kill 表现为进程退出、整批连接断开 | 一旦命中即以停止条件终止场景；报告单列 OOM 档位 |

---

## 10. 实施步骤

| 步骤 | 内容 | 验收 |
|------|------|------|
| 1 | mock-llm / mock-mcp 开发 | curl 冒烟：非流式+流式两分支、`/stats` 可用 |
| 2 | bench agent 配置 + 建库脚本 | 服务以 bench env 启动成功，`/tools` 含 bench_echo |
| 3 | 单请求人工冒烟（每场景 1 次） | B0~B5 全部收到 `done`，mock `/stats` 次数符合预期轮数 |
| 4 | runner + report 开发 | C=1 档跑通 B1，JSONL/报告产出正确 |
| 5 | 全量执行 | 6 场景 × 7 档完整跑完，无步骤中断 |
| 6 | 清理 | 杀 mock/服务进程；按 metadata 前缀删除 bench 沙箱；`agent_manager_bench` 库保留（供复跑）或 DROP |
| 7 | 结论归档 | 报告补"最大并发 / 峰值 req/min / 瓶颈归因 / 调优建议"四行结论，回填本文档 §12 |

## 11. 风险与注意事项

- **数据隔离**：只写 `agent_manager_bench`，禁止触碰 `agent_manager_test` 与集群内 `oaf_platform`
- **沙箱清理**：压测创建的沙箱按 `metadata.userId` 前缀 `bench-user-` 过滤后逐个 `DELETE /v1/sandboxes/{id}`（脚本收尾自动执行；OpenSandbox Server 为共享单点，不允许整库清理）
- **确定性**：mock LLM 响应零随机；sessionId/userId 生成规则固定，保证复跑可比
- **环境独占**：压测期间不并行跑 e2e/CI（LLM mock 与共享 MySQL 都会被干扰）；跑前确认 OpenSandbox `/health` 与 MySQL :3307 可达
- **日志量**：agent-framework 的 LLM/事件日志在高压下会放大磁盘写入，报告需记录该因素（必要时 `LOG_LEVEL` 降 WARN 跑对照，属可选加档）
- **验收标准（对本次压测工作本身）**：每场景产出完整 7 档数据表；`req/min`、错误率、P95 三列无空洞；至少给出 1 条有观测数据支撑的瓶颈归因

## 12. 待确认事项

| # | 事项 | 默认方案（无异议按此执行） | 状态 |
|---|------|---------------------------|------|
| 1 | P95 SLO 阈值 | **10s**（§2）；停止条件 P95 > 30s（3 倍兜底） | ✅ 已定稿（2026-09-15 用户确认） |
| 2 | 场景取舍与耗时 | 核心子集 `B0,B1,B3,B5` ≈1.6h（默认，用户确认）；全矩阵 ≈2.5h 按需补 | ✅ 已确认 |
| 3 | 是否补"集群档"（release-agent 经 :30080 ingress 压测） | 本期不做，拓扑/脚本预留 `BASE_URL` 参数化 | ⏳ 后续 |
| 4 | 沙箱内存 512Mi 是否代表生产 | 生产默认 1024Mi；512Mi 为 10 沙箱池留内存裕量，结论中注明 | ✅ 已确认 |
| 5 | mock 工具名与 SDK 响应编码 | 实施第 3 步冒烟时以实际 `/system-prompt` 与联调为准修正 | ⏳ 实施确认 |
| 6 | 1C/1G 是否为目标生产规格 | 已按用户要求定稿为压测基准规格；如后续有生产规格再按同方法复测 | ✅ 已定稿 |
| 7 | 并发用户池上限 | **10**（用户定稿）：C ≤ 10 档为服务并发能力区间，C > 10 档为池受限区间，报告分区间给结论 | ✅ 已定稿 |
