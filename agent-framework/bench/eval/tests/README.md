# 离线评测 e2e（tests/）

全链路自动验证：单元断言 → collector 三协议录制（hermetic）→ 真实录制 → pack → replay（A1/A2/A3）→ judge → 漂移金标 → eval-studio API 全流程。

```bash
cd agent-framework

# 全量（真实录制模型 OpenRouter + judge mimo；凭据读 .env.secrets）
python3 bench/eval/tests/e2e_offline_loop.py

# 全离线（mock 上游 + 跳过 judge；无外网可跑）
python3 bench/eval/tests/e2e_offline_loop.py --offline

# 单阶段调试（unit|collector|record|pack|replay|studio）
python3 bench/eval/tests/e2e_offline_loop.py --phase collector --offline

# 工作目录（子进程日志/数据包/run 产物都保留在此，排障用）
python3 bench/eval/tests/e2e_offline_loop.py --work /tmp/eval-e2e
```

exit 0 = 全部断言通过；任一失败非 0（子进程日志在 `{work}/{name}.log`）。

## 文件

| 文件 | 用途 |
|------|------|
| `e2e_offline_loop.py` | 全链路编排器（单进程管理全部子进程生命周期） |
| `mock-upstream-llm.mjs` | OpenAI 兼容 mock 上游（流式/非流式 + 沙箱/MCP mock 端点） |
| `stub-agent.mjs` | stub 业务 Agent：/threads/chat SSE 帧方言 + LLM 转发（`STUB_MUTATE=1` 注入漂移，金标验证用） |
| `collector_yaml_selftest.mjs` | collector 自研 YAML 解析器回归断言（抽取 server.mjs 真实实现执行，由 `flywheel.py selftest` 挂载，issue #97） |

## 覆盖断言（108 项）

- **单元**：normalize 规则（时间戳/UUID/token/host 遮蔽、指纹等价）、轨迹等价评估、chunks 反推终答/usage。
- **collector**：三协议+业务反代透传与录制（LLM 流式 chunks 原文一致、沙箱 method/path、MCP JSON-RPC 对、HTTP sessionId 提取）、session 头强关联、默认模型注入、录制副本脱敏（邮箱/手机号/密钥不入库）、上游预检、passthrough/disabled 三态、切换片段生成、控制台页面；**防路径穿越**（四数据口 `%2e%2e`/`../` 一律 400 且不落录制，合法编码查询串/路径原样透传不误伤，issue #97）；**超长脱敏保护**（1MB 病理载荷 mask-test 毫秒级返回并打 `mask_skipped`，修复前为分钟级阻塞）。
- **record/pack**：真实模型录制会话、驱动器轨迹捕获、evalpack 契约（manifest/CHECKSUMS/zip/用例草稿/会话骨架/置信度）。
- **replay**：A1 checks 全过、A2 轨迹等价 drift=0、回放终答与录制一致（确定性）、report.json/html；**金标**：注入行为变更（`STUB_MUTATE=1`）→ strict 回放失败 drift=1.0。
- **judge**：mimo 真实打分（录制↔回放一致性 1.0）。
- **studio**：档案 CRUD/切换/导出脱敏、包上传（CHECKSUMS）/明细、用例转正、replay run（内置沙盒）与 live run 全流程、报告下载、轨迹明细、趋势。

Docker 冒烟（镜像开箱即用验证，另行执行）：collector 容器 healthz/鉴权/控制台；studio 容器内导入 evalpack → 建 replay 档案（builtin-stub）→ 容器内完整 replay run 2/2 通过。
