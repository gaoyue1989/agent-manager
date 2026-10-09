# 离线评测链路使用指南（采集 → 打包 → 回放/评测 → 对比）

> 端到端操作手册：从零开始把一个业务 Agent 的真实流量录下来、打包、回放评测并产出回归结论。
> 组件设计见 [docs/design/agent-framework-eval-offline-record-replay-design.md](../../docs/design/agent-framework-eval-offline-record-replay-design.md)；
> 日常趋势轨评测看 [FLYWHEEL.md](FLYWHEEL.md)，模块结构看 [README.md](README.md)。

## 1. 架构一览

```
【测试环境 · 业务面】                         【评测工作站 · Docker】

业务 Agent 服务（Java / 任意 OpenAI 兼容客户端）      eval-studio :18400
  ├─ LLM_BASE_URL ────────────┐                      目标档案 / 数据包 / 用例人审
  ├─ OPENSANDBOX_SERVER_URL ──┤   eval-collector      run 编排（replay/live）/
  └─ mcp-configs url ─────────┘   :18200-18202       报告 / 轨迹 / 对比 / 趋势
        │ 三协议透传+录制        :18300 控制台
        ▼                     JSONL 录制数据 ──共享卷──▶ flywheel pack ──▶ evalpack
   真实上游（LLM API / OpenSandbox / MCP）
```

| 组件 | 端口 | 用途 | 镜像 |
|------|------|------|------|
| eval-collector | 18200 LLM / 18201 沙箱 / 18202 MCP（数据面，无认证）· 18203 业务服务反代（可选）· 18300 控制台+管理 API | 旁路录制三类外部交互；18203 可选录制前端 API（附录 C） | `gaoyue1989/eval-collector` |
| eval-studio | 18400 | 评测服务：目标档案 / 包 / 用例 / run / 报告 / 对比 / 趋势；内置打包与回放引擎 | `gaoyue1989/eval-studio` |

业务服务接入 = 三个外部依赖键指向 collector（`LLM_BASE_URL`、`OPENSANDBOX_SERVER_URL`、
OAF 包 `mcp-configs/*/config.yaml` 的 `connection.url`），**Java 侧零改动**，随时可切回。
前端 API 录制（可选，附录 C）：档案配 `upstream.agent` 后，把前端 `AGENT_INTERNAL_URL`
指向 `http://<collector>:18203/{ns}` 即可——HTTP 层入参/返回 + sessionId 强关联一并入包，
前端零代码改动（纯环境变量切换）。

## 2. 快速开始（compose 工作站）

```bash
cd agent-framework/bench/eval-collector
ADMIN_TOKEN=<管理token> \
EVAL_JUDGE_LLM_BASE_URL=https://token-plan-cn.xiaomimimo.com/v1 \
EVAL_JUDGE_LLM_API_KEY=<judge key> \
EVAL_JUDGE_LLM_MODEL=mimo-v2.6-flash \
docker compose -p eval-round up -d

# collector 控制台  http://<host>:18300   （studio 打包/评测/报告）
# eval-studio      http://<host>:18400
```

compose 同时挂载共享卷：studio 可直接读 collector 录制数据出完整 evalpack
（collector 控制台「数据打包」按钮 → 转调 studio）。
不需要采集、只想本地评测时，单独跑 `gaoyue1989/eval-studio:latest` 即可（见 §6 本地开发）。

## 3. 全流程演练

### ① 采集（eval-collector）

**页面路径**：控制台 →「接入向导」五步（创建档案 → 配置上游+预检 → 脱敏确认 → 生成切换片段 → 验证采集）。

CLI 等价：

```bash
# 1. 建接入档案（ns=业务服务名）；upstream 填真实端点；llm_api_key 可选（填则由 collector 注入）
curl -H "Authorization: Bearer $ADMIN_TOKEN" localhost:18300/api/profiles -H 'content-type: application/json' -d '{
  "ns": "svc-release", "display": "发布助手（测试环境 A）",
  "upstream": {"llm": "https://openrouter.ai/api/v1", "llm_api_key": "<上游key>",
               "llm_default_model": "<默认模型>", "sandbox": "10.x.x.x:8090",
               "mcp": {"platform": "http://platform-backend:8080/mcp"}}
}'
# 2. 上游预检 → 3. 生成切换片段 → 手工/调用平台 API 把业务服务三个键切到 collector：
#    LLM_BASE_URL=http://<collector>:18200/svc-release/v1 等（片段里已生成好）
# 4. 在被测服务里正常对话，控制台「状态面板」看到计数增长即通
# 强会话关联（推荐）：请求带 X-Eval-Session 头；不带则打包阶段按指纹聚类（confidence=medium）
```

要点：`X-Eval-Session` 头强关联；录制副本自动脱敏（密钥/邮箱/手机号），透传不受影响；
档案三态 `recording / passthrough / disabled`（仅透传不录制，业务零影响）。

### ② 打包（collector 控制台 或 CLI）

**页面路径**：控制台 →「数据打包」→「导出 evalpack」（有 studio 工作站时直接出完整包）。

```bash
# 控制台按钮等价于：collector POST /api/pack → studio /api/packs/from-collector
# 离线 CLI 等价（可加 --traces 驱动器轨迹提升草稿精度，--export-e2e-fixtures 反哺门禁轨夹具）：
python3 bench/eval/flywheel.py pack --collector-dir /var/lib/eval-collector/svc-release \
  --out bench/eval/reports/packs/pk-svc-release
# 产物：manifest.json / sessions / interactions / correlation / cases-draft / CHECKSUMS / zip
```

### ③ 评测（eval-studio）

**页面路径**：
1. 「数据包」上传 zip（或工作站模式已自动导入）→ 查看会话明细（关联置信度/终答/token）；
2. 「用例库」把 draft 草稿**人审转正**；
3. 「目标配置」建目标档案（每个 agent 一份，**可切换/保存/预检**）：

```json
{ "id": "prof-release", "name": "发布助手（测试环境 A）",
  "target_mode": "replay",                       // replay 离线回放 | live 联机评测
  "replay": {"harness": "builtin-stub",          // builtin-stub 开箱即用 | external 自备被测服务
             "strictness": "strict",
             "extra_env": {"STUB_MUTATE": "1"} },// 可选：沙盒环境注入，模拟行为变更的被测版本
  "live": {"base_url": "http://10.x.x.x/agent/svc-release"},
  "judge": {},                                    // 缺省回落 EVAL_LLM_* 环境变量
  "secrets_ref": {"llm_api_key": "env:EVAL_LLM_API_KEY"} }
```

4. 「评测任务」→ 选档案 + 包 + 用例 → 运行 → 报告中心查看（checks 明细 / 轨迹 diff / 漂移率 / judge）。

CLI 等价：

```bash
# 离线回放（--provision 自动起独立 MySQL/Redis + 受测 jar + 回放器，需 mvn package + docker）
python3 bench/eval/flywheel.py replay --pack <pack> --out reports/runs/x \
  --base-url http://127.0.0.1:18100 --mode strict --judge
# 联机链路评测（直打真实测试环境，结构层断言 + judge）
python3 bench/eval/flywheel.py live --pack <pack> --out reports/runs/y \
  --base-url http://... --judge
```

### ④ 对比（回归结论）

**页面路径**：studio「对比」→ 选基准 run（A）与对比 run（B）→ 并排结论。

典型用法：同一 pack 跑两次——A 用基线档案、B 用注入行为变更的档案
（`replay.extra_env: {"STUB_MUTATE": "1"}`）——对比视图即标出 **回归用例** 与通过率/漂移率变化。

```bash
curl "localhost:18400/api/compare?run_a=<runA>&run_b=<runB>"
# 逐用例 verdict: regression / improved / stable_pass / both_failed / new_in_b / removed_in_b
```

### ⑤ 下线

控制台 →「下线向导」：生成还原片段把三个键切回真实上游 → 确认无流量 → 停容器/处置数据。

## 4. 凭据与环境变量

| 变量 | 注入位置 | 说明 |
|------|----------|------|
| `EVAL_COLLECTOR_ADMIN_TOKEN` | collector | 控制台/管理 API token；未设仅绑 127.0.0.1 |
| `EVAL_RECORD_LLM_BASE_URL/_API_KEY/_MODEL` | collector 档案（控制台填入） | 录制上游（也可用业务方自己的上游） |
| `EVAL_LLM_BASE_URL/_API_KEY/_MODEL` | studio | judge 模型（评测打分，advisory 不阻断） |
| `STUDIO_TOKEN` / `STUDIO_SKIP_AUTH` | studio | 访问控制（内网部署按需） |
| `EVAL_JUDGE_ENGINE` | flywheel | `auto`（装了 py-openjudge 用之）/ `direct` / `openjudge` |

凭据只在 `.env.secrets`（gitignored）或环境变量；档案里只存 `env:VAR` 引用，导出剥离敏感值。

## 5. 排障

| 症状 | 原因 | 处理 |
|------|------|------|
| 回放漂移 100%（录制明明正常） | 回放期会话状态残留：同 sid 在 DB/Redis 有历史 | 回放前重置存储（DROP/CREATE + 独立 `AGENT_REDIS_PREFIX`）；`--provision` 已内置 |
| 漂移但录制/回放请求头一致 | 差异在深内容：记忆注入、AgentStateStore ID（gw-hash）、工作区残留导致的工具集变化 | 看 `run-gold-replay/replayer-captured.json` + `drift_samples`；两侧开关一致（如 `AGENT_MEMORY_ENABLED`）；运行目录每 run 清空 |
| 回放用例失败：`tool_calls.required` 大面积未调用 | expected 把「可用工具目录」当成了「必须调用」 | 用新版 packager 重打包（已修复：required 只取轨迹实际调用） |
| HTTP 反代口 404 / 录制无 session | 档案未配 `upstream.agent`；`POST /threads/chat` 的 path 段是 "chat" 不是 sid（设计如此，走 body 提取） | 档案补 agent 上游；session 提取规则见 `extractSession` |
| collector 交互计数与实际调用数不符 | 上游 keep-alive：`[DONE]` 后连接长期不关 | 已修复（[DONE] 语义结束 + 60s 兜底）；确认镜像 ≥ 2026-10-04 |
| e2e/本地 collector 起不来 EADDRINUSE | 工作站容器占用 18200-18300 | `docker compose -p eval-round stop` 后再跑本地进程 |
| 录制请求与回放请求 model 不一致 | 打包取到了旁路调用（标题）的模型 | 用新版 packager（`_recorded_model` 取主链最大请求体的 model） |

## 6. 本地开发（不用 Docker）

```bash
# collector（Node ≥18，零依赖）
node bench/eval-collector/server.mjs               # data=./data conf=./conf
# studio（Python ≥3.10 + Node，fastapi/uvicorn/httpx/python-multipart）
pip install -r bench/eval-studio/requirements.txt
python3 -m uvicorn app.main:app --port 18400 --app-dir bench/eval-studio
# 评测 CLI（httpx；py-openjudge==0.2.2 可选）
pip install -e bench/eval  # 或 pip install 'httpx>=0.27' 'py-openjudge==0.2.2'
```

## 7. CI 集成

- `评测离线回放 e2e (--offline)` job：agent-framework 变更即跑全链路离线回归（mock 上游，
  零真实 LLM；含漂移注入金标 / rebase 闭环 / studio API 断言）——**非必需检查**，稳定后可转必需。
- `构建评测镜像并推送` job：master push 构建推送 `eval-collector` / `eval-studio` 双镜像（buildx + gha 缓存）。

## 8. 边界与安全（务必阅读）

- collector 采集期间处于业务外部调用**关键路径**：仅限测试环境，出包后走「下线向导」切回。
- 数据面端口（18200-18202）不加认证（加认证需业务侧带凭据，违背零侵入前提）；安全边界 =
  测试网络隔离 + 采集窗口期；集群部署建议 NetworkPolicy 限源。管理面强制 token。
- 录制内容含真实用户数据：默认脱敏规则开箱即用，evalpack 必须声明脱敏应用（导入强校验 CHECKSUMS）。
- live 模式打真实服务：`auto_confirm` 会真实执行确认类工具（如发布）；对共享实例遵守趋势轨纪律
  （或用 auto_deny）。远端子 agent 委派（A2A spawn）一期不做回放，相关会话只跑局部断言。
