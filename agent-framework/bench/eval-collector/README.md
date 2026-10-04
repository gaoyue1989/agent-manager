# eval-collector — 离线评测数据收集代理

测试环境旁路录制业务 Agent 的三类外部交互（**LLM API / OpenSandbox / MCP**），产出可回放的录制数据；内置 Web 控制台把「部署 → 接入 → 验证 → 打包 → 下线」做成页面向导。设计见 `docs/design/agent-framework-eval-offline-record-replay-design.md` §2。

## 开箱即用（Docker）

```bash
docker run -d --name eval-collector \
  -p 18200-18202:18200-18202 -p 18300:18300 \
  -v $PWD/collector-data:/var/lib/eval-collector \
  -v $PWD/collector-conf:/etc/eval-collector \
  -e EVAL_COLLECTOR_ADMIN_TOKEN=<你的管理token> \
  gaoyue1989/eval-collector:latest

# 浏览器打开 http://<host>:18300 → 「接入向导」五步完成接入
```

| 端口 | 用途 | 认证 |
|------|------|------|
| 18200 | LLM 代理（OpenAI 兼容，SSE 透传）`/{ns}/v1/...` | 无（数据面，仅测试网络可达） |
| 18201 | 沙箱代理 `/{ns}/...` | 无 |
| 18202 | MCP 代理 `/mcp/{ns}/{server}/...` | 无 |
| 18300 | 管理控制台 + 管理 API | Bearer token（未设 token 仅绑 127.0.0.1） |

## 接入一个业务服务（向导五步的 CLI 等价）

```bash
# 1. 创建接入档案（ns=业务服务名）
curl -s localhost:18300/api/profiles -H 'content-type: application/json' -d '{
  "ns": "svc-release",
  "display": "发布助手（测试环境 A）",
  "upstream": {
    "llm": "https://openrouter.ai/api/v1",
    "llm_api_key": "<上游key，可选：填则由collector注入，不填透传调用方凭据>",
    "llm_default_model": "<可选：请求未带model时注入>",
    "sandbox": "10.x.x.x:8090",
    "mcp": {"platform": "http://platform-backend.agent-platform.svc:8080/mcp"}
  }
}'
# 2. 预检上游连通性     POST /api/profiles/svc-release/precheck
# 3. 生成切换片段       GET  /api/profiles/svc-release/switch-snippet
#    → 平台 env PATCH 请求体 + OAF 包 mcp-configs url 改写 diff，复制即用
# 4. 业务服务三个键切到 collector：LLM_BASE_URL=http://<collector>:18200/svc-release/v1 …
# 5. 验证：控制台状态面板看到交互计数增长即通
```

## 管理 API（与控制台同源同 token，脚本/CI 可 headless 使用）

```
GET  /healthz                          健康检查（无需认证）
GET  /api/status                       概览（档案/计数/存储）
GET|POST /api/profiles                 档案列表 / 创建
PUT|DELETE /api/profiles/{ns}          档案修改 / 删除
POST /api/profiles/{ns}/state          {state: recording|passthrough|disabled}
POST /api/profiles/{ns}/precheck       上游预检（LLM /models、MCP initialize、沙箱 ping）
GET  /api/profiles/{ns}/switch-snippet 切换片段   /restore-snippet 还原片段
GET  /api/interactions?ns=&kind=&limit 交互流水（脱敏摘要）
POST /api/mask-test                    脱敏预览
POST /api/pack                         打包：EVAL_PACKAGER_URL 指向 studio 打包端点时转发出完整 evalpack，否则返回等价 CLI
```

## 录制数据形态

```
{data}/{ns}/llm/YYYYMMDD.jsonl      # 一行一个完整交互；LLM chunks 为 SSE data 载荷原文
{data}/{ns}/sandbox/YYYYMMDD.jsonl  #   （与 e2e/mock/fixtures/llm 夹具同构，可直接回放）
{data}/{ns}/mcp/YYYYMMDD.jsonl
{data}/audit.jsonl                  # 档案配置变更审计
```

- 会话关联：业务请求带 `X-Eval-Session` 头时强关联（推荐；框架/驱动侧加一个头即可）；否则打包阶段按内容指纹 join。
- 脱敏：录制副本逐字符串值过规则（默认含密钥/邮箱/手机号/JSON 敏感字段），透传内容不受影响；请求头一律不入库。
- 状态三态：`recording`（录制）/ `passthrough`（仅透传不录制，业务零影响）/ `disabled`（拒绝）。

## 本地开发

```bash
node server.mjs                      # 默认 data=./data conf=./conf，控制台 http://127.0.0.1:18300
```

零 npm 依赖（Node ≥ 18，标准库实现）。全局配置见 `collector.example.yaml`。

## 安全边界（务必阅读）

- collector 采集期间处于业务外部调用**关键路径**：仅用于测试环境；出包后经「下线向导」把三个键切回真实上游。
- 数据面端口不加认证（加认证需业务侧带凭据，违背零侵入前提），安全边界 = 测试网络隔离 + 采集窗口期；集群部署建议 NetworkPolicy 限源。
- 管理 API 强制 token；未设置时仅绑 127.0.0.1。
