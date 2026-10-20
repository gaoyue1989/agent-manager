# 每日更新日志自动生成设计（daily-changelog）

## 1. 背景与目标

仓库没有 changelog 惯例，master 更新以 PR merge 为主（日均 2~11 条），缺少一个对外可读的「每天发生了什么」视图。目标：每天自动生成一份类似 [zcode changelog](https://zcode.z.ai/cn/changelog) 的中文更新日志（「新功能 / 优化调整 / 问题修复 / 其他变更」分类 + 条目列表），发布为一条 GitHub Release，无需人工介入。

## 2. 方案总览

| 组件 | 路径 | 职责 |
|------|------|------|
| 生成脚本 | `scripts/gen-daily-changelog.sh` | 收集指定日期落地 master 的提交材料，调 LLM 总结，markdown 输出到 stdout；本地与 CI 共用 |
| 定时工作流 | `.github/workflows/daily-changelog.yml` | 每日定时跑脚本，发布/更新 GitHub Release |

LLM 走 OpenAI 兼容接口（小米托管 `mimo-v2.6-flash`，`https://token-plan-cn.xiaomimimo.com/v1`），密钥存仓库 Secret `LLM_API_KEY`；base URL / model 名非敏感，明文写在 workflow `env` 顶部。

## 3. 统计口径

- **时间**：北京时间（Asia/Shanghai）自然日。定时在 UTC 16:30（北京 00:30）触发，汇总刚结束的完整一天，避免深夜提交漏统；GitHub cron 有分钟级调度延迟，不影响口径。
- **范围**：仅默认分支 master 的 **first-parent 视角**——每条 = 一次落地 master 的 PR 合并或直接提交（feature 分支上的中间提交通过「分支内提交主题列表」附在对应 merge 下，供 LLM 归纳，上限 30 条防 prompt 过长）。
- **无提交日**：脚本退出码 2，workflow 优雅跳过，不发 Release。

## 4. 发布与幂等

- Release tag 与标题（Releases 列表展示名）统一用日期版本号 `vYYYY.MM.DD`（如 `v2026.10.19`）；`--target master` 让 tag 落在 master。
- 重跑/补跑幂等：tag 已存在则 `gh release edit` 只更新正文，不重复建。
- 不标记 `--latest`（`--latest=false`）：这是文档型 Release，不伪装成代码发布。

## 5. 降级策略

LLM 调用失败（网络/密钥/限流，重试 2 次仍失败）不阻断日报：降级输出当日原始提交列表（含每个 PR 的分支内提交主题），文首标注「自动总结生成失败」。保证定时任务每天都有产出、失败可事后补跑覆盖。

## 6. 安全

- 密钥只存 GitHub Secret（`LLM_API_KEY`），脚本从环境变量读取，不写入任何入库文件；提交材料里只有提交信息，无代码与密钥内容。
- 工作流 `permissions: contents: write`，仅建/改 Release 所需最小权限；不写业务资源，不触碰控制面/业务面分层约束。

## 7. 运维

```bash
# 手动补跑某天（合入 master 后）
gh workflow run daily-changelog.yml -f date=2026-10-01

# 本地生成（不发布）
LLM_API_KEY=xxx bash scripts/gen-daily-changelog.sh 2026-10-01 > changelog.md

# 停用/回滚
gh workflow disable daily-changelog
```

## 8. 已知边界

- schedule 只在默认分支 master 上生效，PR 合入前定时不会触发。
- 仅统计 master：feature 分支上未合并的进行中工作不体现（刻意的噪音取舍）。
- LLM 归纳基于提交信息，代码级行为变化以 PR 内容为准；条目准确性受提交信息质量约束。
