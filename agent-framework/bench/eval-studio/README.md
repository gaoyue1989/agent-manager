# eval-studio — 离线评测服务（开箱即用镜像）

单镜像评测服务：**目标档案配置切换 + evalpack 导入/人审 + 离线回放（replay）/ 联机链路评测（live）+ LLM judge + 报告可视化**。内置 packager（打包）与 replay 引擎（replay-llm + 回放沙盒），与 [eval-collector](../eval-collector/README.md) 组成完整离线评测闭环。设计见 `docs/design/agent-framework-eval-offline-record-replay-design.md` §6。

## 开箱即用（Docker）

```bash
docker run -d --name eval-studio -p 127.0.0.1:18400:18400 \
  -v $PWD/studio-data:/data \
  -e EVAL_LLM_BASE_URL=https://token-plan-cn.xiaomimimo.com/v1 \   # judge 模型（可选）
  -e EVAL_LLM_API_KEY=<judge key> \
  -e EVAL_LLM_MODEL=mimo-v2.6-flash \
  -e STUDIO_TOKEN=<访问token，可选> \
  gaoyue1989/eval-studio:latest

# 浏览器打开 http://127.0.0.1:18400（远程访问走 SSH 隧道/反代：ssh -L 18400:127.0.0.1:18400 <host>）
# 容器以非 root（uid 1000）运行：宿主数据目录属主需对齐，chown -R 1000:1000 studio-data
```

与 collector 组成采集工作站（推荐 [docker-compose](../eval-collector/docker-compose.yml)）：

```bash
ADMIN_TOKEN=<token> [STUDIO_TOKEN=<token>] EVAL_JUDGE_LLM_* ... docker compose up -d
# collector :18300 控制台（接入向导/状态/打包/下线） · studio 绑定回环 127.0.0.1:18400（远程走 SSH 隧道/反代）
```

## 页面（六视图）

| 视图 | 说明 |
|------|------|
| 概览 | 快速开始引导 + 最近 run |
| 目标配置 | **目标档案（Agent Profile）**：每个 agent 一份真实配置（replay/live 双模式、judge、凭据引用），顶栏一键切换活跃档案；预检 + JSON 导入导出（敏感值剥离） |
| 数据包 | evalpack 上传（CHECKSUMS 强校验）/ 会话明细（关联置信度/终答/token 用量） |
| 用例库 | 包内用例草稿**人审转正**（draft → active） |
| 评测任务 | 新建 run（选档案 + 包 + 用例）→ 实时状态 → 报告入口 |
| 对比 | **同 pack 双 run 并排对比**（M4 验收）：逐用例 verdict（回归/改进/稳定）+ 差异检查项 + 终答对照 + 回归结论汇总（`GET /api/compare?run_a=&run_b=`） |
| 趋势 | 跨 run 通过率 / judge 均分 / 漂移率 |

## 核心概念

**目标档案**（按 agent 配置切换，设计 §6.2）：

```json
{
  "id": "prof-release", "name": "发布助手（测试环境 A）",
  "agent": {"oaf_slug": "vendor/release"},
  "target_mode": "replay",                       // replay 离线回放 | live 联机评测
  "replay": {"pack_policy": "latest", "harness": "builtin-stub", "strictness": "strict"},
  "live": {"base_url": "http://10.x.x.x/agent/svc-release", "timeout_s": 300},
  "judge": {"base_url": "...", "model": "..."},   // 缺省回落 EVAL_LLM_* 环境变量
  "secrets_ref": {"llm_api_key": "env:EVAL_LLM_API_KEY"}   // 凭据只存引用
}
```

- `replay` 两档 `harness`：`builtin-stub`（**开箱即用**：容器内自动拉起 replay-llm + 回放沙盒，无需任何外部服务）/ `external`（被测服务自行运行、LLM 指向 replay-llm）。
- `live`：直打真实测试环境服务（studio 即评测代理）；A1 断言只保留结构层（帧/工具/无 error），文本质量交给 judge（真实模型非确定）。
- 两种模式共用同一报告契约（report.json / report.html），可直接并排对比。

**评测分层**（设计 §5）：A1 确定性断言（checks，门禁）+ A2 轨迹等价（replay-llm 匹配统计，strict 失配即失败）+ A3 漂移率（advisory）+ LLM judge（advisory，永不阻断）。

## API（脚本/CI 可 headless 使用）

```
GET  /healthz
GET|POST /api/profiles         PUT|DELETE /api/profiles/{id}
POST /api/profiles/{id}/activate | /precheck     GET /api/profiles/{id}/export（脱敏）
POST /api/packages             GET /api/packages[/{id}][/cases]     DELETE /api/packages/{id}
GET  /api/cases                POST /api/cases/promote
POST /api/runs {type,profile_id,pack_id,case_ids,judge}
GET  /api/runs[/{id}]          GET /api/runs/{id}/report.json|report.html|cases/{case}.trace.json
GET  /api/trends
GET  /api/compare?run_a=&run_b=        同 pack 双 run 并排对比（verdict + 回归结论）
```

目标档案可注入沙盒环境模拟不同被测版本（`replay.extra_env`，如 `{"STUB_MUTATE": "1"}`）——
基线/变更两个档案各跑一次，对比视图即产出回归结论。

## 产物路径（可寻址）

```
{data}/packs/{pack_id}/            # 解包后的 evalpack（CHECKSUMS 校验通过）
{data}/runs/{run_id}/report.json | report.html
{data}/runs/{run_id}/cases/{case_id}.trace.json | .trajectory.json
{data}/runs/{run_id}/replayer-logs.json      # replay-llm 匹配统计（exact/normalized/drift）
{data}/studio.db                             # 元数据（档案/包/用例/run 索引）
```

## 本地开发（不用 Docker）

```bash
pip install -r bench/eval-studio/requirements.txt   # fastapi/uvicorn/httpx/python-multipart
python3 -m uvicorn app.main:app --port 18400 --app-dir bench/eval-studio
# 需 node ≥18（replay-llm / 内置回放沙盒）；EVAL_LLM_* 注入 judge
```

镜像构建（上下文 = bench/）：

```bash
cd bench && docker build -f eval-studio/Dockerfile -t gaoyue1989/eval-studio:latest .
```

## 安全边界

- 访问 token `STUDIO_TOKEN`（issue #97 起真正接线为全局鉴权依赖）：未设或 `STUDIO_SKIP_AUTH=1` 时放行
  （存量本地用法零破坏）；设 token 后 `/api/*` 全保护——缺失/错误的 `Authorization: Bearer` 一律 401。
  `/healthz`（探活/HEALTHCHECK）与 `/`（前端页面，401 后浏览器无 UI 可输 token）匿名可达，受保护面 = API 面。
- 前端 token 输入：页面首访经 `#token=xxx`（取值后立即清除 hash 防留在地址栏）或弹窗输入，
  存 localStorage 后所有请求自动带 `Authorization` 头；收到 401 提示重新输入。
- 容器以非 root（uid 1000）运行：宿主 bind mount 数据目录属主需对齐，`chown -R 1000:1000 studio-data`
  ——历史 root 容器写入的存量目录属主为 root，不迁移将 Permission denied（本地非容器开发不受影响）。
- compose 的 18400 已绑定回环 `127.0.0.1`：远程访问走 SSH 隧道/反代，不再直接暴露公网（刻意的暴露面收敛）。
- collector 控制台「数据打包」是容器间 server-to-server 转调：collector 侧经 `EVAL_PACKAGER_TOKEN`
  透传凭据（compose 已与 STUDIO_TOKEN 同值注入），studio 启用鉴权后该转调自动携带 Bearer 正常工作；
  非 compose 部署时需自行给 collector 设 `EVAL_PACKAGER_TOKEN=<同 STUDIO_TOKEN 值>`。
- 档案凭据只存 `env:VAR` 引用或 judge 显式配置；导出接口一律剥离敏感值。
- evalpack 导入强制 CHECKSUMS 校验（含反向扫描：未登记文件拒收），篡改包拒收。
- 客户端可控标识符（pack_id / session id / case_id / ns）落盘读盘前统一白名单校验
  （`bench/eval/replay/ids.py`：字母/数字开头，仅字母数字点下划线连字符，禁 `..` 与路径分隔符，
  非法返回 400），杜绝路径穿越任意删写（issue #97）；上传先解压到临时 staging、全部校验通过才落最终目录。
- 上传大小上限 `STUDIO_MAX_UPLOAD_BYTES`（字节，默认 268435456 = 256MB），超限返回 413。
