# Agent Framework 并发压测（bench）

对应设计方案：[../docs/concurrency-benchmark-plan.md](../docs/concurrency-benchmark-plan.md)（**执行结论见其 §13**，含两项 harness 缺陷的根因与证据）。被测为 **docker 容器 1C/1G 硬限** 的 agent-framework（SANDBOX_ENABLED 可切换），LLM/MCP 均 mock，闭环并发打 `POST /threads/{sid}/chat`（SSE）。

> 2026-09-16 首轮结论：非沙箱最大并发 4（峰值 637 req/min，C=8 触发 SDK 死锁）；沙箱模式并发上限 1（harness stop() 竞态）。复测请先阅读 §13.4 实施偏差（会话池模型、256Mi 沙箱、500ms 会话间隔、专用压测 MySQL）。

## 目录

```
bench/
├── mock-llm/            # OpenAI 兼容 mock（脚本化状态机，:18081）
├── mock-mcp/            # streamableHttp MCP mock（bench_echo，:18082）
├── agent/               # bench 专用 OAF 配置（挂载为容器 /config）
├── sql/init-bench-db.sql# 独立压测库 agent_manager_bench（幂等）
├── load/runner.js       # 闭环并发 runner（SSE 解析/指标/观测采样/停止条件）
├── load/report.js       # 汇总报告生成
├── run-bench.sh         # 一键编排
└── results/             # 运行产物（gitignore）
```

## 快速开始

```bash
cd agent-framework/bench
./run-bench.sh                          # 默认核心子集 B0,B1,B3,B5 × C=1,2,4,8,10,16/32
SCENARIOS=B0,B1,B2,B3,B4,B5 ./run-bench.sh   # 全矩阵（≈2.5h）
STAGES=1,4,10 STAGE_SECONDS=60 ./run-bench.sh # 快速验证档
```

前置：docker / node / mysql client / jq / curl；本地 MySQL :3307 可达；OpenSandbox `:8090/health` healthy（沙箱场景）；镜像 `agent-framework:latest` 已构建（`make docker-build`）。

## 单步调试

```bash
mysql -h127.0.0.1 -P3307 -uagent_manager -p... < sql/init-bench-db.sql
(cd mock-llm && node server.js &)       # mock-mcp 同理
# 被测容器由 run-bench.sh 拉起（或参照脚本内 docker run 手动起）
node load/runner.js --mode warmup --scenario B1 --session-pool 10 --results-dir results
node load/runner.js --mode run --scenario B1 --stage 1 --concurrency 1 --session-pool 1 \
  --stage-seconds 60 --results-dir results
node load/report.js --results-dir results
```

## 参数（run-bench.sh 环境变量）

| 变量 | 默认 | 说明 |
|------|------|------|
| SCENARIOS | B0,B1,B3,B5 | 场景子集（B5 会话池固定 1：单会话并发测 turn 租约串行化上界） |
| STAGES | 1,2,4,8,10,16,32 | 并发阶梯（C=10 为会话池边界档） |
| SESSION_POOL | 10 | 会话池上限（沙箱隔离键=sessionId，池大小≈沙箱数；C>10 为池受限区间） |
| STAGE_SECONDS / RAMP_SECONDS | 180 / 15 | 每档稳态 / ramp 时长 |
| IMAGE | agent-framework:latest | 被测镜像 |

## 口径与停止条件

- **成功**：SSE 收到 `AGENT_END`，全程无 `error` 帧、HTTP 200；单请求超时 120s
- **支持并发 C**：错误率 <1% 且 P95 ≤10s 且服务未崩溃
- **档停止条件**（跳过当前场景剩余档）：错误率 >5%（≥20 样本）/ P95 >30s / 健康检查连续 3 次失败
- 观测采样 5s 周期：容器 CPU/内存、JVM 堆（有 jcmd 时）、MySQL Threads_connected/行锁等待、沙箱数、loadavg

## 清理

`run-bench.sh` 退出时自动：删容器、停 mock、按 `metadata.userId` 前缀 `bench-user-` 删除压测沙箱（共享 OpenSandbox 单点，禁止整库清理）。库 `agent_manager_bench` 保留供复跑（手动 DROP 可彻底清理）。
