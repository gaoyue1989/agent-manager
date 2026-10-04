# 评测飞轮离线化改造设计：采集代理 + 数据包 + 回放/链路评测 + 报告（eval offline loop）

> 状态：**已实施（2026-10-04，M1–M4 全部落地 + 全链路 e2e 96 断言全绿 + 双镜像交付；附录 A 为实施记录，附录 B 为二期完成记录）**
> 前置文档：[agent-framework-eval-dual-track-design.md](agent-framework-eval-dual-track-design.md)（双轨评测总纲）、[agent-framework-eval-env-provisioning-design.md](agent-framework-eval-env-provisioning-design.md)（环境供给）
> 一句话：把"评测飞轮"从**联机依赖真实 LLM/沙箱/MCP** 升级为**离线闭环**——测试环境旁路录制三类外部交互 → 一键打包 evalpack → 离线部署 OAF 包 + mock 三依赖做确定性回放与 LLM 链路评测 → web 报告与可视化。

---

## 0. 背景与目标

### 0.1 现状盘点（改造起点）

| 能力 | 现状 | 缺口 |
|------|------|------|
| 评测执行 | `bench/eval/flywheel.py` 六步闭环（diff→生成→执行→断言→judge→RCA） | 被测实例仍要求**真实 LLM 端点**（`provision/__init__.py:_target_llm_from_env()`），评测成本高、不可重复 |
| 确定性断言 | `executor/checks.py`（帧计数/工具调用/终答包含，零 LLM） | 输入是手写用例库（9 条），没有真实流量来源 |
| mock 设施 | `e2e/mock/llm-server.mjs`（夹具回放）、`sandbox-server.mjs`、`bench/eval/mock/mcp_server.py`、`e2e/scripts/record-llm.mjs` / `record-sandbox-protocol.mjs`（两个录制代理原型） | 都是 e2e 专用脚本形态，没有统一采集服务、没有会话关联、没有打包/报告闭环 |
| 报告 | `reports/{task}/report.md` + `summary.jsonl` + `history.jsonl` 账本 | 纯文件，无 web 可视化、无明细统计界面 |

### 0.2 目标（对应用户三项需求）

1. **采集**：离线评测数据收集代理服务（eval-collector）——测试环境业务服务仅改配置（三个 URL 键）即可旁路录制 **LLM API、OpenSandbox API、MCP 工具调用**的请求与返回；核心组件（OAF 包 / Redis / MySQL）不录制，回放环境用"部署同版 OAF 包 + 独立 MySQL/Redis + mock 外部依赖"复现行为；collector 以 **Docker 镜像 + 内置 Web 控制台（接入向导）** 交付，降低部署与配置门槛。
2. **数据包 + 回放/链路评测**：采集数据一键生成 **evalpack 数据包**；独立 web 服务（eval-studio）驱动 **case 回放**（确定性回归，能确定结果路径）与 **链路调用评测**（开源 LLM judge 组件打分）。
3. **报告可视化**：评测与回放结果有报告与可视化界面，含明细统计（用例级 checks/轨迹 diff/分数分布/失败分类/跨版本趋势）。

### 0.3 非目标（明确不做）

- 不做生产环境采集（仅测试环境；生产引入属后续独立立项）。
- 不改 agent-framework Java 主链路（一期零侵入：三个外部端点全部配置化切换，无需发版）。
- 不做线上实时评测（回放评测全部离线批量执行）。
- 不自动修复、不直改代码（沿用飞轮"报告 + 人审"纪律）。

---

## 1. 总体架构

```
【测试环境 · 业务面】                      【离线评测环境 · 工作站/CI runner】

┌───────────────────────────┐            ┌────────────────────────────────────────────┐
│ agent-framework 业务服务    │            │ eval-studio（独立 web 服务）                 │
│  （OAF 包 + 独立 MySQL/Redis）│            │  页面：包管理 / 用例库 / 评测任务 /          │
│                           │            │        报告中心 / 轨迹查看器                  │
│  LLM_BASE_URL ─────────────┼───┐        │  API：FastAPI + SQLite(元数据) + 文件库      │
│  OPENSANDBOX_SERVER_URL ───┼───┼──► eval-collector（Docker 镜像，三协议录制代理）              │
│  mcp-configs/*/config.yaml ┼───┘        │        │ 拉起回放 run（调 flywheel.py replay）  │
│        │                  │   透传+录制  │        ▼                                   │
│  ┌─────┴──────────┐       │  (LLM/SBX/MCP + :18300 控制台)│ ┌──────────────────────────────────────┐ │
│  │ 真实上游：LLM API │◄──────┼─────────────┼─│ provision（扩展自 bench/eval）        │ │
│  │ OpenSandbox/MCP │       │            │ │  独立 MySQL + Redis 容器（每 run 销毁）│ │
│  └────────────────┘       │            │ │  受测 agent-framework jar + OAF 包副本  │ │
│                           │            │ │  replay-llm / replay-sandbox /          │ │
│  MySQL session_message、   │◄─(只读导出)──┼─│ replay-mcp（三个回放 mock）             │ │
│  tool_audit；Redis 事件流  │  packager   │ │  sse_client 驱动 + checks + judge       │ │
└───────────────────────────┘            │ └──────────────────────────────────────┘ │
                                         └────────────────────────────────────────────┘
```

数据流：**采集（旁路）→ 打包（关联+脱敏）→ 回放（mock 三依赖 + 独立存储）→ 断言/评测 → 报告**。

组件一览：

| 组件 | 形态 | 语言/技术 | 部署位置 |
|------|------|-----------|----------|
| eval-collector | 常驻录制代理（LLM/SBX/MCP 三协议）+ 内置 Web 控制台（接入向导/状态/配置/打包/下线） | Node.js ≥22 alpine 镜像，零 npm 依赖（沿用 e2e/mock 范式），四固定端口 + 路径前缀多路复用 | 测试环境：宿主机 docker / compose 工作站 / 集群内清单（deploy/k8s.yaml） |
| packager | 打包器（`flywheel.py pack` 子命令；另容器化为 compose 内 `eval-packager` sidecar，控制台一键触发完整打包） | Python（bench/eval 内新模块 `replay/`） | 离线环境或 compose 工作站，只读访问 collector 存储 + 测试环境 MySQL/Redis |
| replay 引擎 | 回放执行编排（`flywheel.py replay` 子命令） | Python + 三个 mock 回放器（Node/Python） | 离线环境，docker 供给 |
| eval-studio | web 服务（页面 + API + run 编排 + 按 agent 的目标档案配置切换/保存，replay/live 双模式） | FastAPI + SQLite + React(Vite+Tailwind) 静态托管 | 离线环境，单容器/单进程 |

---

## 2. eval-collector：离线评测数据收集代理（Docker 镜像 + 内置 Web 控制台交付）

采集配置后续会持续变复杂（多服务接入、多 MCP server、脱敏规则演进），因此 collector 的交付形态直接锁定"**降低部署与配置复杂度**"：一个 Docker 镜像、四个固定端口、内置 Web 控制台把"部署 → 接入业务服务 → 验证采集 → 打包 → 下线"全流程做成页面向导，端口/地址/切换片段等细节由控制台代管，用户不手工拼配置。

### 2.1 交付形态与端口模型

镜像 `gaoyue1989/eval-collector`（Node ≥22 alpine，零 npm 依赖，单进程 = 三协议代理 + 录制器 + 管理控制台）。监听模型为**四固定端口 + 路径前缀多路复用**（多业务服务共用一个实例，不随接入数扩端口）：

| 端口 | 用途 | 路由键 |
|------|------|--------|
| 18200 | LLM 代理（OpenAI 兼容，SSE 透传） | `/{ns}/v1/...` → 该 ns 档案的 `upstream.llm` |
| 18201 | 沙箱代理（管理 API + execd 透传） | `/{ns}/...` → 该 ns 档案的 `upstream.sandbox` |
| 18202 | MCP 代理（streamableHttp JSON-RPC 透传） | `/mcp/{ns}/{server}/...` → 该 ns 档案对应 server |
| 18300 | 管理控制台 + 管理 API | `EVAL_COLLECTOR_ADMIN_TOKEN` Bearer 认证 |

对应业务服务的三个配置键由向导自动生成（用户不手工拼地址）：

- `LLM_BASE_URL = http://<collector>:18200/{ns}/v1`
- `OPENSANDBOX_SERVER_URL = <collector>:18201/{ns}`
- OAF 包 `mcp-configs/{server}/config.yaml` 的 `connection.url = http://<collector>:18202/mcp/{ns}/{server}`

> 兼容性注记：LLM base_url 携带路径前缀、沙箱 SDK domain 携带路径，是路径复用路由的两个前提（e2e 已实证 `LLM_BASE_URL` 可带 `/v1` 路径）。M1 首日各做一次真实 SDK spike；任一不成立，该协议退回"端口池按 ns 自动分配 + docker 范围端口映射"模型，向导生成的片段随路由模型自动变化，用户无感。

三种部署形态（命令/清单均可从控制台与 README 直接复制）：

**形态一：宿主机 docker（最快开始）**

```bash
docker run -d --name eval-collector \
  -p 18200-18202:18200-18202 -p 18300:18300 \
  -v $PWD/eval-collector/data:/var/lib/eval-collector \
  -v $PWD/eval-collector/conf:/etc/eval-collector \
  -e EVAL_COLLECTOR_ADMIN_TOKEN=<token> \
  gaoyue1989/eval-collector:latest
# 浏览器打开 http://<host>:18300 → 进入接入向导
```

**形态二：docker compose 采集工作站（collector + 可选 eval-packager sidecar，一键出完整 evalpack）**

```yaml
services:
  collector:
    image: gaoyue1989/eval-collector:latest
    ports: ["18200-18202:18200-18202", "18300:18300"]
    volumes: ["./data:/var/lib/eval-collector", "./conf:/etc/eval-collector"]
    environment: { EVAL_COLLECTOR_ADMIN_TOKEN: "${ADMIN_TOKEN}" }
  packager:                        # 可选：完整数据包（会话关联需要它）
    image: gaoyue1989/eval-packager:latest
    volumes: ["./data:/var/lib/eval-collector", "./packs:/packs"]
    environment:                   # 只读凭据；不发布端口，仅 compose 内网可达
      EVAL_MYSQL_URL: "mysql://ro_user:${MYSQL_RO_PW}@10.x.x.x:3306"
      EVAL_REDIS_URL: "redis://:${REDIS_RO_PW}@10.x.x.x:6379"
```

**形态三：集群内部署**（测试环境即 kind 集群时的推荐形态）：`deploy/k8s.yaml`（Deployment + Service + 录制卷 PVC/hostPath），业务服务以 svc DNS 寻址（如 `http://eval-collector.agent-platform.svc:18200/{ns}/v1`）。该清单由运维 `kubectl apply`，**不经平台发布 API、不设 ownerReferences**——collector 是测试基础设施而非业务 agent 服务，不触碰控制面原则；业务服务的三个键切换仍走平台显式 env 编辑 API（向导生成调用体）。

卷与环境变量：

| 挂载/变量 | 用途 |
|-----------|------|
| `/var/lib/eval-collector` | 录制数据（按 ns 分桶 JSONL，日轮转，保留策略可配） |
| `/etc/eval-collector/collector.yaml` | 接入档案（控制台可编辑、校验后热生效、重启持久） |
| `EVAL_COLLECTOR_ADMIN_TOKEN` | 控制台/管理 API 令牌；未设置时控制台仅绑定 127.0.0.1 |
| `GET :18300/healthz` | 容器健康检查（含各 ns 上游探活汇总），供 docker HEALTHCHECK / K8s probe |

### 2.2 配置分层（复杂度治理）

配置拆两层，避免一个不断膨胀的大配置文件：

- **全局层**（镜像默认 + env 覆盖）：存储目录、轮转/保留、token、路由模式（前缀复用/端口池）。
- **接入档案层**（每业务服务一个 ns profile，控制台或配置文件管理）：

```yaml
ns: svc-release                      # 业务服务名（= 平台服务名）
display: 发布助手（测试环境 A）
upstream:
  llm: http://real-llm-gateway/v1
  sandbox: 10.x.x.x:8090
  mcp:
    platform: http://platform-backend.agent-platform.svc:8080/mcp
record:
  sampling: 1.0
  body_max_bytes: 1048576            # 超限截断 + sha256（沙箱文件类）
  mask_rules: []                     # 继承全局默认规则集；可追加
state: recording                     # recording | passthrough(仅透传不录制) | disabled
```

内置**默认脱敏规则集**开箱即用（Authorization 头 drop、`sk-` 密钥、常见手机号/邮箱模式等），自定义规则在向导第三步追加。所有配置变更经控制台校验（JSON Schema + 上游连通性探测）后原子写入并热生效，变更审计追加进 `meta.json`。

### 2.3 内置 Web 控制台（:18300，"页面指引"）

自包含静态单页（inline JS/CSS、零 CDN 依赖——测试环境无外网也可用），无用户体系、单 token 分权（多人共用靠 token；多用户/细粒度审计后续按需立项）。控制台展示的交互详情**一律为脱敏后数据**（原文只存在于 JSONL 存储）。五个视图：

1. **接入向导**（核心指引，五步把"配 collector + 切流量 + 验证"做完）：
   - ① 创建接入档案：业务服务名（ns）+ 展示名；
   - ② 配置上游：LLM/沙箱/MCP server 地址（可从平台服务列表带出）；每项一键**预检**——由 collector 容器主动探测（LLM `GET /models` 或 1-token 试调、MCP initialize 握手、沙箱 ping），探测网络视角与代理转发完全一致；
   - ③ 确认脱敏规则：默认规则集 + 追加；提供**脱敏预览**（贴样例报文，实时查看脱敏效果）；
   - ④ 生成切换片段（复制即用）：平台 env 编辑 API 调用体（`PATCH /api/v1/services/{id}/env`，含原值备份）+ OAF 包 `mcp-configs` url 改写 diff + release-agent 对话式操作提示；片段中地址由路由模型自动拼装；
   - ⑤ 验证采集：提示在被测服务发一条测试消息，collector 检测到该 ns 首条交互即点亮通过，转入状态面板。
2. **状态面板**：按 ns/协议的当日与累计交互数、上游健康、存储占用、最近错误、最近交互流水（摘要 → 点开脱敏后详情）。
3. **配置管理**：接入档案/脱敏规则/保留策略编辑（校验 + 热生效 + 审计）；按 ns **暂停/恢复录制**（暂停 = 仅透传不录制，业务流量不受影响）。
4. **数据打包**：导出交互数据包（collector 本地即得）/ 触发完整 evalpack（转调 compose 内 packager sidecar 的 `POST /pack`，产物落 `./packs` 供下载）；未部署 sidecar 时展示等价的离线 CLI 命令。
5. **下线向导**：生成还原片段（三个键切回原上游——原值在接入时已随档案留存）→ 确认该 ns 已无流量 → 提示删除容器/清单与数据处置。

管理 API（`/api/*`，与页面同源、同 token）覆盖上述全部能力，供脚本化与 CI 场景 headless 使用（`curl :18300/api/...`）。

### 2.4 三协议录制要点（复用已有原型）

| 协议 | 母本 | 录制内容 | 备注 |
|------|------|----------|------|
| LLM | `e2e/scripts/record-llm.mjs` | 完整请求 body + 流式 SSE chunk 原文（`data:` 载荷逐条）/ 非流式 body；耗时、状态码 | Authorization 头**永不落盘**；chunk 原文保证回放保真（llm-server.mjs 已验证该形态可回放） |
| 沙箱 | `e2e/scripts/record-sandbox-protocol.mjs` | 管理 API（create/delete/...）与 execd（command/files.*）每次 HTTP 交互：method/path/status/req/resp（NDJSON 事件流整收）| 大 body（文件二进制）按 `body_max_bytes` 截断 + hash |
| MCP | 新增（参照 mcp_server.py 的 streamableHttp 子集理解） | JSON-RPC 报文对（request id ↔ response）；重点 `tools/call` 的 arguments 与 CallToolResult；notifications 记录不配对 | initialize/tools/list 结果随包记录一次，用于回放目录自描述 |

**写入模型**：一次交互完成后原子写一条 JSONL（流中断也落已收部分 + `truncated: true`）。存储布局：

```
/var/lib/eval-collector/{ns}/
  llm/20261004.jsonl          # 每行一个完整交互
  sandbox/20261004.jsonl
  mcp-platform/20261004.jsonl
  meta.json                   # 上游地址、配置指纹、collector 版本
```

LLM 交互记录结构（其余协议同构，略）：

```json
{"kind":"llm","id":"llm-000042","ns":"svc-release","ts_start":"...","ts_end":"...",
 "upstream":"http://real-llm/v1","status":200,"duration_ms":8213,"stream":true,
 "request":{"model":"...","messages":[...],"tools":[...]},
 "chunks":["data: {...}\n\n","...","data: [DONE]"],
 "truncated":false}
```

### 2.5 会话关联（采集侧弱关联 + 打包侧强关联）

难点：LLM/沙箱请求不带 sessionId（一期不改 Java，无法注入 header）。设计为**打包时 join**，采集侧只保证时间戳精度与原文完整：

1. **会话骨架来源**（均为已有持久化，只读导出）：
   - MySQL `session_message`（`SessionMessageArchiveStateStore` write-through 全量消息归档）——会话内每轮 user/assistant/tool 消息与内容；
   - MySQL `tool_audit`（`ToolAuditStore`）——工具调用名、参数、结果审计；
   - Redis `sess:{sid}:events`（XRANGE）——帧序列时间线，含 `MODEL_CALL_START/END` 窗口与 `TOOL_CALL_*` 帧（见 `config/frame-mapping.json`）。
2. **join 算法**（packager 内实现，输出带置信度）：
   - 时间窗：交互 `ts_start` 落在会话活跃窗（首帧~末帧 + 30s 容差）内 → 候选；
   - 内容指纹：LLM 请求 `messages` 末条 user 内容与会话第 k 轮用户消息归一化后一致/相似度 > 0.9，或请求中 tool 消息内容 ⊆ 该会话 tool_audit 结果集 → **强归属**；
   - 沙箱/MCP 交互：`tool_audit`（工具名 + 参数归一化 + 时间窗）与 `TOOL_RESULT` 帧窗匹配；
   - 多候选歧义 → 标记 `ambiguous`，eval-studio 人审裁决；
   - 无法归属且符合后台调用特征（无 system / memory-extraction system，llm-server.mjs 已验证的识别规则）→ 标记 `background`（title/compaction/memory 旁路调用），随包保留但不进会话轨迹。
3. **置信度分级**：`high`（时间窗+指纹双命中）/ `medium`（仅时间窗）/ `ambiguous`（多候选）；只允许 high 进"转正用例"，medium/ambiguous 需人审。

### 2.6 可用性与安全红线（对齐架构强约束）

- collector 是**业务面旁路组件、仅测试环境**：它在采集期间处于外部调用关键路径上，宕机表现等同 LLM 网关不可达。因此：生产环境与正式发版**禁止**指向 collector；采集结束（出包后）经**下线向导**把三个 URL 键切回原上游；collector 自身 `GET /healthz` + 写入失败告警日志。
- 数据面端口（18200-18202）**不加认证**：它们在业务调用路径上，加认证需要 Java 侧送凭据（违背零侵入前提）；安全边界 = 仅测试网络可达 + 采集窗口期 + 集群形态下建议 NetworkPolicy 限源。管理面（:18300）必须 token（未设置时仅绑 127.0.0.1）。
- 平台 backend 全程不参与采集链路（三个键属于业务服务 env 配置，不新增任何 backend 写路径），不违反"业务资源只由显式发布 API 写入"。
- 脱敏两级：采集侧 `mask_rules`（正则/JSON path drop，控制台展示一律为脱敏后数据）+ 打包侧二次脱敏（见 §3.3）；Authorization/api key 永不落盘；evalpack 的 manifest 必须声明已应用规则，未声明不得导入 eval-studio。

---

## 3. evalpack 数据包格式与打包器

### 3.1 CLI 与输入

```
python3 bench/eval/flywheel.py pack \
  --collector-dir /var/lib/eval-collector/svc-release \
  --mysql-url ... --redis-url ...          # 只读，导会话骨架
  --since 2026-10-03 --until 2026-10-04 \
  [--sessions sid1,sid2] [--oaf-zip release-agent.zip] \
  --out reports/packs/pk-20261004-a/
```

OAF 包来源：优先从平台包存储取当前运行版本（PVC `packages/{id}` 副本 + sha256 校验），也可 `--oaf-zip` 显式指定。**必须**与测试环境实际运行版本一致（meta 记录 slug/version/hash），否则包上标记 `oaf_mismatch` 警告。

### 3.2 目录契约（format_version: 1）

```
pk-20261004-a/
  manifest.json               # 见下
  oaf/release-agent.zip       # OAF 包副本（含 mcp-configs 原文）
  sessions/{sid}.json         # 会话骨架：inputs（逐轮）、帧序列（事件流导出）、
                              #   tool_calls、final_output、hitl 点（ask/confirm 决策与参数）、
                              #   token_usage、model_switch 涉及的 model_config 行
  interactions/
    llm/{id}.json             # 单交互完整记录（§2.4 结构）
    sandbox/{id}.json
    mcp/{server}/{id}.json
  correlation.json            # {sid: {llm:[id], mcp:[...], sandbox:[...], confidence}}
  cases-draft/{sid}.json      # 自动生成的候选用例（对齐 bench/eval case 格式，人审转正）
  CHECKSUMS                   # 全包 sha256 清单
```

`manifest.json`：

```json
{"pack_id":"pk-20261004-a","format_version":"1",
 "source":{"env":"test-env-a","ns":"svc-release","time_range":["...","..."],
           "collector_version":"..."},
 "runtime":{"framework_version":"<commit>","oaf":{"slug":"vendor/agent","version":"1.2.0","sha256":"..."}},
 "redaction":{"applied":true,"rules":[...]},
 "sessions":[{"sid":"...","confidence":"high","turns":3,"has_hitl":true,"has_remote_spawn":false}],
 "stats":{"llm":42,"mcp":11,"sandbox":6,"bytes":18324567}}
```

### 3.3 关键处理

- **二次脱敏**：打包时对会话内容/交互 body 再过一遍规则（可配置字段树脱敏，如 `messages[].content` 内的手机号/邮箱），脱敏后重算 CHECKSUMS；manifest 声明 `applied`。
- **候选用例自动生成**（`cases-draft`）：expected 从录制骨架**保守**派生——帧结构（`AGENT_END` 计数、无 error）、工具调用名集合、终答长度下限 + 从稳定子串抽取的包含断言（volatile 词剔除）；`ground_truth` = 录制终答；`requires_tools` = 录制工具名。人审可在 eval-studio 加严。**保守派生是刻意的**：自动断言过严会把"良性漂移"误报为回归。
- **数据包 → e2e 夹具（反哺通道，可选）**：packager 提供 `--export-e2e-fixtures`，把 LLM 交互转成 `e2e/mock/fixtures/llm/*.json` + registry 登记，真实流量沉淀为门禁轨夹具（格式同构，`calls[].chunks` 原文）。

---

## 4. 回放执行链：mock 三件套 + provision 扩展 + 轨迹确定性

### 4.1 执行流水线（`flywheel.py replay`，studio 复用同一入口）

```
对 run 的每个 case（= 一个会话）：
 1. provision（扩展自 bench/eval/provision/）：
    docker 起 独立 MySQL(:随机) + 独立 Redis(:随机)
      —— 库名 eval_{run_id}、AGENT_REDIS_PREFIX=eval-{run_id}-（原则二：独立库/前缀，跑完销毁）
    起三个回放 mock：replay-llm(:A)、replay-sandbox(:B)、replay-mcp(:C，按包内 server 多实例)
    起受测 jar：OAF 包来自 evalpack（解到 /config）、LLM_BASE_URL→:A、
      OPENSANDBOX_SERVER_URL→:B、mcp-configs url 改写→:C、CHECKPOINT/AGENT_REDIS 指向独立容器
      （受测 jar = 指定构建产物/镜像，用于"同包不同版本"回归对比）
    预检：contract preflight（沿用 provision.preflight）+ 会话级 model_config seed（录制时切过模型则预置）
 2. 驱动：sse_client.stream_chat 逐轮回放录制的用户输入；
    HITL：遇 permission_ask 时回放录制的 confirm 决策（approve/deny + 参数）走 confirm-stream
 3. 采集回放轨迹：驱动器 SSE view + 三个 mock 各自记录"收到的请求 + 回放了的响应 + 匹配结果"
 4. 断言与评测（见 §4.4 / §5）
 5. teardown：销毁容器与临时目录，落 trace
```

### 4.2 replay-llm：LLM 回放器（llm-server.mjs 的泛化）

数据源从"fixtures 目录 + 场景标记"换成"evalpack 中该会话的 interactions 有序序列"，匹配算法：

1. **后台调用识别**（不消耗主序游标）：无 system 消息或 system 含 memory-extraction 特征 → 合成良性响应（沿用 llm-server.mjs 已验证规则）。
2. **主序匹配**（每会话一个游标 cursor）：
   - 归一化请求：volatile 字段遮蔽（§4.3 规则表）；
   - 结构指纹：消息条数、role 序列、末条 user/tool 内容归一化比对、tools 名单；
   - 命中 cursor 位置录制请求（exact / normalized 两档）→ 回放录制 chunks 原文；
   - 失配：`strict` 模式判 case 失败；`loose` 模式（默认）按最邻近候选继续 + 记录漂移。
3. **volatile 改写**：回放前对 chunks 做 `{{占位符}}` 替换（把录制期值换成当次值，泛化自现有 `rewrites`/`ARGS_OVERRIDE` 机制，占位符由归一化 diff 自动生成）。

### 4.3 归一化规则表（轨迹确定性的基础，配单测）

| 类别 | 规则 | 例 |
|------|------|----|
| 时间戳 | ISO-8601 / epoch 毫秒模式 → `<TS>` | `2026-10-04T01:02:03Z` |
| 标识 | UUID / nanoid / 递增 id → `<ID>` | session id、file id |
| 路径 | 含 id 段的路径 → 归一模板 | `/files/f-123abc` → `/files/<FID>` |
| 端口/主机 | 回放环境地址 → `<HOST>` | `127.0.0.1:18100` |
| 随机 token | 长度 ≥N 的 hex/base64 → `<TOKEN>` | |

规则集中在 `replay/normalize.py`（Python，供 packager 关联/断言复用）与 replayer 内同构实现（Node）。规则表进 `selftest` 离线自检。

### 4.4 轨迹等价与"确定结果路径"

**回放的执行路径必须可确定、可对比**，三层保障：

1. **A1 终态断言**（checks.py，门禁）：expected 来自录制骨架（§3.3 保守派生）。
2. **A2 轨迹等价断言**（新增 `replay/trajectory.py`，门禁）：对比录制轨迹 vs 回放轨迹的**外部调用序列**——每步 `{type: llm|sandbox|mcp, name/server+tool, args_hash(归一化)}`，输出逐步 diff（exact / normalized / drift / missing / extra）。
3. **A3 请求漂移度量**（advisory）：`drift_rate = 漂移步数 / 总步数`，run 级汇总；用于区分"良性漂移"（框架 prompt 组装改动）与"行为回归"。

匹配/失配策略（run 级参数 `mode`）：

- `strict`：任一 normalized 失配 → case fail。用于**回归门禁**（同包 + 已知良好构建做基线）。
- `loose`：失配不阻断，记 diff 继续。用于**诊断 / 新构建首轮摸底**。

产物路径固定可寻址（"确定结果路径"的另一半语义）：

```
runs/{run_id}/report.json                    # run 汇总 + 全 case 结果
runs/{run_id}/cases/{case}/trace.json        # 驱动器视角轨迹（沿用现有 trace 结构，扩展字段）
runs/{run_id}/cases/{case}/trajectory.json   # A2 逐步 diff
runs/{run_id}/cases/{case}/replayer-logs.json
runs/{run_id}/report.html                    # 自包含静态报告（可独立分发）
```

### 4.5 replay-sandbox / replay-mcp

- **replay-sandbox**：不真执行命令，按 `(method/path, 归一化参数)` 匹配录制交互回放（create 返回录制沙箱 id + 端点、command 返回录制 NDJSON 事件流原文）；`SANDBOX_ENABLED=false` 的会话天然无此类交互。
- **replay-mcp**：扩展 `bench/eval/mock/mcp_server.py` 加 replay 模式——启动参数给 evalpack 目录，`tools/call` 按 `(tool, 归一化 args)` 匹配录制结果回放；ask 类工具照常发权限流（HITL 决策由驱动器回放）；initialize/tools-list 用包内录制的目录自描述。
- 三者统一暴露 `/stats`（当次匹配统计：exact/normalized/drift/missing）供 report 聚合。

### 4.6 边界与已知限制（P1 范围声明）

| 场景 | 处理 |
|------|------|
| 远程子 agent 委派（agents[] / A2A spawn） | P1 不回放远端：轨迹含 `REQUIRE_EXTERNAL_EXECUTION`/远端 spawn 的会话标记 `链路不完整`，只跑局部断言；多实例联合回放（一个 pack 含多服务 + 端点重写）列 P2 |
| 时间相关行为（定时/超时依赖） | 归一化遮蔽 + loose 模式；涉及用例在报告中标注 |
| 沙箱文件大对象 | 录制期已截断 + hash 的，回放返回占位内容（hash 校验断言） |
| 录制后框架 prompt 组装变更 | A3 漂移率上升是**预期信号**（这正是回归检测语义）；提供 `--rebase`（演进项）：loose 回放产物生成新基线包，人审后替换 |
| 共享 release-agent 等共享服务 | 回放环境天然独立（每 run 独立存储），无 HITL 真执行风险 |

---

## 5. 链路评测（LLM judge 指标体系）

沿用双轨评测的"确定性门禁 + LLM 评审 advisory"分层：

### 5.1 确定性门禁层（0 LLM，可阻断）

A1 终态断言 + A2 轨迹等价（§4.4）。exit code 契约沿用飞轮：0 全过 / 1 有失败 / 2 无可执行用例。

### 5.2 LLM 评审层（开源组件，永不阻断）

- **主选 OpenJudge（py-openjudge==0.2.2，pyproject 已预留钉版）**：与趋势轨打分一致（0~1 + reason），judge 端点用内网 OpenAI 兼容 LLM（`EVAL_LLM_BASE_URL/_API_KEY/_MODEL`，复用 `graders/correctness.py:judge_from_env`）。
- **可选增强 DeepEval**（独立 venv，不进 pyproject 主依赖）：维度库化 + 自带 HTML 报告可嵌入 studio。映射：
  - 终答正确性：`GEval`（criteria 基于 ground_truth=录制终答）
  - 工具选择正确性：`ToolCorrectnessMetric`（expected_tools 来自录制轨迹）
  - 应答相关性：`AnswerRelevancyMetric`
- **维度契约**（两套实现共用）：`scores: {dim: {score: 0~1, reason, judge_model}}`；judge 模型名入 run 账本（换模型 = 趋势断代，沿飞轮纪律）。
- 评审层输入是**回放产物**（input=录制输入、actual=回放终答/轨迹、expected=录制终答/轨迹），因此链路评测可脱离真实外部依赖批量执行。目标模式由档案决定（§6.2）：`replay` 用回放产物离线跑；`live` 由 studio 代理直打真实测试环境服务——两模式共用断言与评分契约，报告可比。

---

## 6. eval-studio：web 服务与报告可视化

### 6.1 形态与数据模型

- FastAPI + uvicorn 单进程（run 执行为子进程/线程 worker，SQLite 任务表排队，默认并发 1）；SQLite 只存元数据索引，实体（pack/run/report/trace）落文件库：

```
bench/eval-studio/
  app/            # FastAPI：api/、worker/、store/
  frontend/       # Vite + React + Tailwind（构建产物由 FastAPI 静态托管）
  data/           # packs/、runs/、studio.db（gitignored）
```

- 领域对象：`Profile`（评测目标档案，见 §6.2）、`Package`（evalpack 导入）、`Case`（转正用例，含来源 sid）、`Run`（replay | chain 两类；chain 可选目标模式 `target_mode=replay|live`（live = 联机打真实测试环境），由档案带出；参数：profile、pack、cases、受测 jar 引用、mode、judge 配置）、`Report`。

### 6.2 目标档案（Agent Profile）：按 agent 的配置切换与保存

eval-studio 本质是**面向评测目标的代理/编排服务**——前面面对多个不同业务 agent（各自的 OAF 包、依赖端点、受测版本、judge 偏好），后面面对本地回放集群或真实测试环境。对每个 agent 维护一份**目标档案**并支持切换与持久化，"评测另一个 agent"就变成**切档案**而不是重新填表：

```json
{
  "id": "prof-release-agent",
  "name": "发布助手（测试环境 A）",
  "agent": {"oaf_slug": "vendor/release", "platform_service": "svc-release"},
  "target_mode": "replay",                  // replay = 离线回放（默认） | live = 联机打真实服务
  "replay": {                               // target_mode=replay 时使用
    "pack_policy": "latest|pin:pk-...",     // 默认取该 agent 最新包，可钉版本
    "framework_ref": "repo-master|image:tag|jar-path",
    "strictness": "strict|loose",
    "provision": {"mysql_image": "...", "redis_image": "..."}
  },
  "live": {                                 // target_mode=live 时使用
    "base_url": "http://10.x.x.x/agent/svc-release",
    "collector": "http://eval-collector:18300",   // 可选：联机评测顺带采集出新 evalpack
    "timeout_s": 300, "hitl_policy": "auto_confirm"
  },
  "judge": {"base_url": "...", "model": "...", "dims": ["correctness", "tool_selection"]},
  "secrets_ref": {"llm_api_key": "env:EVAL_LLM_API_KEY"},  // 凭据只存引用，不落明文
  "precheck": {"last_status": "ok", "checked_at": "..."}
}
```

- **切换与保存**：档案全部持久化（SQLite `profile` 表 + JSON 导入/导出，团队可共享）；全局**当前活跃档案**唯一，页面顶栏常显、一键切换；新建 run 默认继承活跃档案，表单最小只需选用例，其余参数可临时覆写（不回写档案）。
- **live 模式（联机链路评测）**：不 provision 本地回放集群，sse_client 直接打档案里的真实 base_url（studio 即评测代理），断言/judge 与离线回放共用同一套契约；配置 collector 地址后可"**联机评测顺带采集**"——跑一轮真实链路评测同时落出新的 evalpack，喂给下一轮离线回放，形成采集-评测闭环。
- **预检**：replay 档案校验 pack/框架引用存在与 hash；live 档案探测 base_url 可达（`/.well-known/agent-card.json`）与 judge 端点连通；结果缓存进档案并在切换时展示。
- **凭据安全**：API key/数据库口令等敏感值只存**引用**（`env:VAR`，由 studio 进程环境注入）或加密字段（密钥 `STUDIO_SECRET_KEY`），页面一律脱敏展示；导出 JSON 不含敏感值。

### 6.3 API 面（草案）

```
POST /api/packages（上传 zip，校验 manifest+CHECKSUMS+脱敏声明）
GET  /api/packages/{id}/sessions/{sid}          # 骨架 + 交互关联 + 置信度
POST /api/packages/{id}/promote  {sid, edits}   # 会话 → 用例（人审转正）
GET/POST /api/cases
GET/POST /api/profiles、PUT /api/profiles/{id}   # 目标档案 CRUD + 导入/导出 JSON（不含敏感值）
POST /api/profiles/{id}/activate | /precheck     # 切换当前活跃档案 / 连通性与引用预检
POST /api/runs   {type, profile_id, case_ids, package_id?, framework_ref?, mode, judge{...}}
                                                 # 档案带默认参数，最小只需 type + profile + cases
GET  /api/runs/{id}/report(.html)
GET  /api/runs/{id}/cases/{case}/trace|trajectory
GET  /api/trends?case_set=&pack=                # 跨 run 对比（回归视图）
WS   /api/runs/{id}/events                      # 进度推送
```

### 6.4 页面（对应需求 2/3）

1. **数据包管理**：上传/列表/会话明细（帧时间线、逐轮输入输出、关联置信度、脱敏声明）。
2. **目标配置**：目标档案列表/编辑/复制/切换（当前活跃档案顶栏常显、一键切）/预检/导入导出；敏感凭据脱敏展示。评测评测不同 agent = 切档案。
3. **用例库**：候选用例人审（并排：录制轨迹 vs 派生 expected）→ 转正；可导出回 repo `cases/*.json`（趋势轨复用）。
4. **评测任务**：新建 run 简化为「选档案 → 选用例 → 跑」（其余参数取档案默认，可临时覆写）+ 实时进度。
5. **报告中心**：run 汇总看板——通过率、分数分布、耗时 p50/p95、token 用量、A3 漂移率、失败分类（复用 `analyzer/rca.py` 七类）；**明细统计**——用例级 checks 逐项（name/passed/detail）、轨迹逐步 diff 表；**对比视图**——同 pack 不同 framework 版本并排（回归定位主入口）。
6. **轨迹查看器**：会话帧时间线 + LLM 请求/响应 chunk 级查看 + 工具调用参数/结果 + HITL 卡 + 录制 vs 回放双栏 diff。

### 6.5 报告契约（report.json 摘录）

```json
{"run_id":"...","type":"replay","pack_id":"pk-20261004-a",
 "framework":{"commit":"...","jar_sha256":"..."},"mode":"strict",
 "judge":{"model":"...","dims":[...]},
 "summary":{"case_total":12,"pass":11,"fail":1,"error":0,"pass_rate":0.917,
   "score_avg":0.93,"score_distribution":[...],"drift_rate":0.02,
   "duration_ms_p50":...,"token_usage":{...}},
 "cases":[{"case_id":"...","sid":"...","status":"failed",
   "checks":[{"name":"frames.AGENT_END","passed":true,"detail":"..."}],
   "trajectory_diff":[...],"scores":{"correctness":{"score":0.9,"reason":"..."}},
   "rca_category":"工具执行失败","trace_path":"cases/case_x/trace.json"}]}
```

静态 HTML 报告自包含（JSON 内嵌 + 无依赖渲染），可离线分发。

---

## 7. 复用与改动清单

**不动**：Java 主链路（一期零侵入）；趋势轨现有子命令与账本语义；门禁轨 e2e。

**复用**：`record-llm.mjs`/`record-sandbox-protocol.mjs`（collector 协议层母本）、`llm-server.mjs`（匹配/改写/后台识别规则）、`sandbox-server.mjs`（协议形状）、`mcp_server.py`（加 replay 模式）、`provision/`（assemble/start_instance/preflight/teardown 扩展为 pack 驱动 + 独立 MySQL/Redis）、`sse_client.py`/`checks.py`/`rca.py`、`frame-mapping.json`（会话骨架解析）。

**新增**：

```
agent-framework/bench/eval-collector/        # Docker 镜像源：Node 采集代理 + 内置控制台
  server.mjs、console/index.html（自包含单页，零 CDN 依赖）、collector.yaml
  Dockerfile、docker-compose.yml（含可选 eval-packager sidecar）、deploy/k8s.yaml、README
agent-framework/bench/eval/replay/           # packager.py、normalize.py、trajectory.py
agent-framework/bench/eval/mock/replay-llm.mjs、replay-sandbox.mjs
agent-framework/bench/eval-studio/           # FastAPI + 前端
```

**修改**：`flywheel.py` 加 `pack` / `replay` 子命令；`pyproject.toml`（如引入 judge 增强依赖则钉版注释）；`.github/workflows/agent-framework-ci.yml`（master push 增推 `gaoyue1989/eval-collector` 与 `eval-packager` 镜像，沿用 buildx + gha 缓存，按目录过滤只在该目录变更时构建）；`FLYWHEEL.md`/`docs/e2e-ci-plan.md`（若 M4 落 CI job）。

---

## 8. 架构原则对齐

- **原则一（控制面不扰业务面）**：collector 仅测试环境业务面旁路组件，backend 零参与；回放/评测全部在离线环境（工作站/CI runner docker），不触碰 agent-platform 集群任何业务资源；采集启停 = 业务服务 env 配置切换（显式发布 API 路径），无后台批量迁移。
- **原则二（业务面隔离/无状态）**：回放实例每 run 独立 MySQL（库名 `eval_{run_id}`）+ 独立 Redis（前缀 `eval-{run_id}-`），容器跑完销毁；不共享、不留痕、无 sessionAffinity 诉求。
- **安全**：两级脱敏 + Authorization/密钥永不落盘 + evalpack 导入强校验（CHECKSUMS + 脱敏声明缺失拒收）；studio 不保存任何上游密钥（judge 凭据走 env）。

---

## 9. 里程碑与验收标准

| 阶段 | 内容 | 验收 |
|------|------|------|
| M1 采集链路 | collector Docker 镜像 + 内置控制台（接入向导）+ 三协议录制 + 脱敏 + 存储 + `pack` 子命令（关联打包；compose 工作站含 packager sidecar） | ① `docker run` / compose 一键起服务，向导五步完成接入并在页面验证首条采集；② 测试环境某服务开采集跑真实会话 → 产出 evalpack；③ 关联置信度抽查：≥95% 会话外部交互正确归属（high 占比报告化）；④ 路径前缀路由的两个 SDK 前提 spike 通过或回落端口池模型 |
| M2 回放链路 | 三个回放器 + provision pack 化 + 驱动 + A1/A2/A3 + `replay` 子命令 | ① 同一构建回放录制会话：无远端委派的会话 strict 模式轨迹等价通过；② 金标验证：用历史引入行为变更的 commit 作受测构建，漂移能被捕获（A2/A3 可见）；③ `selftest` 扩展归一化规则与 trajectory diff 单测 |
| M3 eval-studio | 目标档案（按 agent 配置切换/保存/预检 + live 联机模式）、包管理/用例人审/run 编排/报告中心/轨迹查看器 | 手工验收：①从上传 evalpack 到查看 run 报告全流程页面可用；②配置两个不同 agent 的档案并一键切换，各自完成一次 run（replay 与 live 各一）；③静态 HTML 报告可独立打开 |
| M4 链路评测与 CI 化 | OpenJudge 维度接入（DeepEval 可选）、跨 run 趋势、`eval-replay` CI job（非必需起步） | 同 pack 多版本对比视图产出回归结论；CI job 在 agent-framework 变更时离线回放固定 pack 集，零真实 LLM 依赖 |

---

## 10. 风险与开放问题

1. **会话关联歧义**：并发会话同工具同毫秒窗口 → 指纹仲裁仍歧义时标记 ambiguous 人审；后续可评估 Java 侧为 LLM transport 注入 `X-Session-Id` 头（二期小改，需 SDK transport 支持）从根本上消除。
2. **录制内容合规**：LLM 请求含真实用户数据，脱敏规则需持续维护；evalpack 分发范围与保留策略需随首版落地明确。
3. **远端委派链路不完整**（P1 边界）：多服务联合回放（端点重写 + 多实例编排）作为 P2 立项评估。
4. **基线演进成本**：框架 prompt 组装类变更会推高漂移率，需要 `--rebase` 流程（人审后接受新基线）防止基线僵化。
5. **OpenJudge/DeepEval 内网可用性**：judge 端点不可用时评审层自动跳过（advisory 本就不阻断），run 标注 `judge_skipped`。
6. **evalpack 体积**：LLM chunk 原文 + 沙箱交互可能较大；已设 body 截断与包级压缩（zip），保留策略与 studio 存储配额待首版实测后定。
7. **路由前提与控制台暴露面**：LLM base_url 带路径前缀 / 沙箱 SDK domain 带路径是路径复用路由的两个前提，M1 首日 spike 验证，不成立则回落端口池（向导片段自动适配）；数据面端口不加认证（零侵入前提），安全边界依赖测试网络隔离与采集窗口期（集群形态配 NetworkPolicy），控制台未设 token 时仅绑 127.0.0.1。

---

## 附录 A：实施记录（2026-10-04，一期）

### A.1 交付清单

| 组件 | 位置 | 说明 |
|------|------|------|
| eval-collector | `agent-framework/bench/eval-collector/` | `server.mjs`（零依赖：三协议透传+录制/管理 API/档案/预检/脱敏/片段生成）+ `console.html`（内置控制台五视图）+ Dockerfile + compose（collector+studio 工作站） |
| packager | `bench/eval/replay/packager.py` | `flywheel.py pack`：录制数据→evalpack（manifest/sessions/interactions/correlation/cases-draft/CHECKSUMS/zip）；会话关联=X-Eval-Session 强关联 + 归一化指纹+10min 窗聚类；全轮终答拼接对齐驱动器口径 |
| replay 引擎 | `bench/eval/replay/{normalize,trajectory,runner}.py` + `bench/eval/mock/replay-llm.mjs` | `flywheel.py replay`（A1 checks + A2 轨迹等价 + A3 漂移 + strict/loose + judge + report.json/html）；replay-llm 游标匹配（exact/normalized/drift + 背景调用合成 + 占位改写）；`flywheel.py live`（联机评测，结构层断言 + judge） |
| eval-studio | `bench/eval-studio/` | FastAPI + SQLite + 自包含 SPA（六视图）；目标档案 CRUD/切换/预检/导出脱敏、包导入（CHECKSUMS 强校验）、用例人审转正、run worker（replay=内置 stub 沙盒开箱即用 / external；live）、报告/轨迹/趋势；Dockerfile |
| e2e | `bench/eval/tests/e2e_offline_loop.py` 等 | **73 断言全绿**（单进程编排 mock 上游/collector/stub/replay-llm/studio 全部子进程）；`--offline` 全离线可跑 |

### A.2 验证记录（2026-10-04 实跑）

- e2e 全量（真实上游）：**PASS 73 / FAIL 0**。真实录制=OpenRouter `stealth/space-bunny-alpha`（2 会话 3 调用经 collector 透传录制）；judge=mimo `mimo-v2.6-flash`（录制↔回放终答一致性打分 1.0）。
- 漂移金标：`STUB_MUTATE=1` 注入请求形状变更 → strict 回放 2/2 失败、drift=1.0（A2/A3 捕获行为回归的能力被证实）。
- `flywheel.py selftest` 原有自检回归通过（帧映射契约未受影响）。
- 镜像冒烟：`gaoyue1989/eval-collector:0.1.0`（healthz/token 鉴权/控制台）；`gaoyue1989/eval-studio:0.1.0`（容器内导入 evalpack → 建 replay 档案 → 容器内自动拉起 replay-llm+stub 沙盒 → 完整 replay run 2/2 通过、drift=0）。

### A.3 与设计的偏差（如实记载）

1. **会话关联简化**：一期落地「X-Eval-Session 头强关联（high）+ 归一化指纹+时间窗聚类（medium）」两级；设计 §2.5 的 MySQL（session_message/tool_audit）导出增强未实现（平台侧集成留待后续，头方案已在 collector/stub 驱动侧跑通）。
2. **回放器范围**：一期实现 replay-llm（主链路）；replay-sandbox / replay-mcp 未实现——录制侧三协议均已就绪且入包，回放器按同一范式补充即可。
3. **回放被测对象**：e2e 用 stub-agent（与 agent-framework 同构的 /threads/chat 帧方言）验证全链路；受测 jar 的 provision pack 化（每 run 独立 MySQL/Redis 容器，设计 §4.1）未实现——`harness=external` 已预留接入点，沿用既有 `provision/` 模块接入。
4. **studio 形态**：前端由 React(Vite+Tailwind) 改为自包含 vanilla SPA（零构建、零 CDN，与 collector 控制台同形态，更贴合开箱即用）；run worker 由子进程改为进程内 worker 线程。
5. **未实施项**：evalpack→e2e 夹具反哺（`--export-e2e-fixtures`）、`--rebase` 基线演进、OpenJudge/DeepEval 接入（一期沿用 `graders/correctness.py` 的 judge 形态，维度契约已对齐）、CI 增推镜像 job、镜像推送远端仓库。
6. **里程碑对照**：M1 ✔；M2 部分（LLM 回放+金标 ✔，沙箱/MCP 回放器与 provision pack 化未做）；M3 ✔（含双档案切换验收）；M4 部分（judge 维度与 CI 化未做）。

---

## 附录 B：二期完成记录（2026-10-04，M2–M4 收尾）

### B.1 交付清单（相对附录 A.3 偏差的收口）

| 项 | 落点 | 说明 |
|----|------|------|
| replay-http（沙箱/MCP 回放器） | `bench/eval/mock/replay-http.mjs` | sandbox 按 (method, 归一化路径, 归一化 body) 三级匹配回放录制响应；mcp 按 JSON-RPC 方法+归一化 arguments 匹配；/stats（exact/normalized/fallback/miss）进报告 |
| 多回放器编排 | `replay/runner.py` `start_replayers()/stop_replayers()` | 按包内交互按需拉起 llm/sandbox/mcp 三回放器；空目录不拉起（防止误开 SANDBOX_ENABLED 指向无录制件回放器） |
| provision pack 化 | `provision/replay_pack.py` + `flywheel.py replay --provision` | evalpack → 独立 MySQL/Redis（local-infra）+ 受测 jar（docker，`AGENT_MEMORY_ENABLED` 等开关透传）+ 三回放器；独立 `.runtime-replay` 运行目录每 run 清空；`replayer-captured.json` 随 run 落盘 |
| 真实 jar 金标 | e2e `--phase provision`（`--with-provision`） | 真实 agent-framework jar 经 collector 录制（OpenRouter stealth/space-bunny-alpha）→ 打包（指纹聚类 + 背景过滤）→ provision 回放 strict 全过、drift=0 |
| --rebase 基线演进 | `replay/evolve.py rebase_pack` | replay-llm 捕获回放期实际请求（/captured）→ 替换录制请求生成新基线包（响应保持录制件）+ CHECKSUMS 重算 + manifest.rebase 留痕；金标：旧基线拒绝行为变更、新基线接受 |
| --export-e2e-fixtures | `replay/evolve.py export_e2e_fixtures` | evalpack → e2e/mock/fixtures/llm 夹具（calls[].request/chunks 同构），入库走 PR 人审 |
| OpenJudge 接入 | `graders/openjudge_adapter.py` + `graders/correctness.py` 引擎选择 | 钉版 py-openjudge==0.2.2（pyproject）；EVAL_JUDGE_ENGINE=auto|direct|openjudge；lazy import 未安装自动回落直评；实测正例 raw=5.0→归一化 1.0 |
| CI | `.github/workflows/agent-framework-ci.yml` | 新增 `eval-replay-offline`（--offline 全链路 e2e，非必需）与 `images-eval`（master push 推 eval-collector/eval-studio 双镜像，buildx + gha 缓存） |
| 对比视图（M4 验收收口） | `eval-studio/app/main.py` `GET /api/compare` + SPA「对比」页 | 同 pack 双 run 并排对比：逐用例 verdict（regression/improved/stable_pass/both_failed/new/removed）+ 差异检查项 + 终答对照 + 汇总结论；配合 `replay.extra_env`（沙盒环境注入，模拟行为变更的被测版本）即得「基线 vs 变更」回归结论；e2e 断言钉住；另修 collector→studio 打包转发端点对接（EVAL_PACKAGER_URL 为完整端点） |
| selftest 扩展（M2 验收③） | `flywheel.py cmd_selftest` | 补齐归一化规则（TS/UUID/gw-hash/HOST/TOKEN + 普通数字不受影响）与轨迹等价/漂移率断言，同步钉 JS 侧同构规则；`final_text_from_chunks/usage_from_chunks` 一并钉住 |

### B.2 金标调 through 过程中固化的契约与规则（重要）

1. **背景调用判定**（packager `_is_background` 与 replay-llm `isBackgroundCall` 两侧同构）：无 user 消息（title/compaction 类）**或** system 含 memory-extraction / 会话标题生成助手特征——背景调用不进会话主链、不生成用例、回放期合成应答。真实 Agent 的标题/记忆调用若混入主链会造成假漂移与假用例。
2. **归一化规则新增 `gw-` 前缀**：框架 AgentStateStore ID（gw-hash 用户身份，约 12 位 hex）→ `<GW>`；三处（normalize.py / replay-llm.mjs / replay-http.mjs）必须同步，unit 断言钉住。
3. **replay-llm 匹配从严格游标改为序不敏感匹配**（未消费集合上 exact > normalized > 漂移）：标题生成与主链调用并发，到达顺序在录制/回放间可能互换。
4. **派生 expected 语义修正**：`tool_calls.required` 只能用「轨迹里实际调用的工具」，绝不能用请求里的可用工具目录；`final_text_min_len` 取真实交付长度的一半（短答案不误判）。
5. **回放供给纪律**（金标调试结论，回放漂移排查三板斧）：录制/回放两侧影响请求形状的开关必须一致（记忆、沙箱、工作区物化）；受测实例运行目录必须每 run 清空（残留 workspace/skills 会改变工具集）；`replayer-captured.json` + `drift_samples` 是漂移定位的第一手证据。
6. **collector SSE 录制以 [DONE] 为语义结束**：上游 keep-alive 下 `[DONE]` 后连接可能长期不关，等 `end` 会丢录制（60s 兜底 + `close` 双保险）。

### B.3 验证记录（2026-10-04）

- 全量 e2e（真实录制模型 + mimo judge + OpenJudge + provision 金标 + studio）：**PASS 96 / FAIL 0**；`--offline` 模式供 CI。
- 金标：真实 jar 录制（2 会话，4 交互含 2 背景标题调用）→ provision 回放 strict 2/2 通过、drift=0。
- OpenJudge：`openjudge-0.2.2` 引擎标识 + 正例 raw=5.0 → score=1.0。
- `flywheel.py selftest` 回归通过；collector/studio 镜像重建并冒烟通过。
