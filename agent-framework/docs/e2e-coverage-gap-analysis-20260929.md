# agent-framework E2E 覆盖缺口分析与补充用例（2026-09-29，第二轮）

> **报告性质**：本文档为第二轮「变更 → 缺口 → 落地」汇编，结构与方法论对齐 [e2e-coverage-gap-analysis-20260927.md](e2e-coverage-gap-analysis-20260927.md)（下称「上轮文档」）。与上轮（纯设计草稿）不同：**本轮 6 条补充用例（FW1-FW3 / RD1-RD3）已写入 `agent-framework/e2e/tests/api-core.spec.ts`，并由落地环节完成本地 core/multi/sandbox 三组门禁自验**（结果来源、旁证及其证据强度见 §4.3——本汇编未复跑）；上轮 25 条设计中 **19 条已由 bc235c4 落地、6 条未落地**（上轮文档与 bc235c4 提交信息所称「20 条」与实际新增 test() 数不符，对账见 §2.2/§3.4）。
> 引用的 git log/show、源码行号、grep 命中、CI workflow 内容均由本汇编在 worktree `/root/agent-manager-wt-e2ecov`（HEAD=7351b71 = origin/master，外加未提交的 e2e 落地变更，清单见 §4.2）**实跑核实**。
> 被测物：`agent-framework/`（Java 运行时，:8100）；CI 门禁：`.github/workflows/agent-framework-ci.yml`。

---

## 1. 近 3 天 master 变更清单（2026-09-26 ~ 2026-09-29）

取证命令：`git log --since="2026-09-26 00:00" --until="2026-09-30 00:00" --no-merges --date=format:'%Y-%m-%d %H:%M' --pretty='%h %ad %s' origin/master`——共 **35 条提交**，下表 35 行逐一对应（时间跨度 09-26 00:29 ~ 09-28 21:29；截至汇编时点 09-29 当日 master 无新提交）。上轮文档 §1 已详述 09-26 ~ 09-27 前段变更的黑盒可观测面，本表从简、聚焦目录归属与门禁相关性。

**门禁相关性总则**（均已实跑核实）：

- CI **E2E 门禁只覆盖 `agent-framework/e2e` 四组**：e2e-core / e2e-multi / e2e-sandbox / e2e-plugin（agent-framework-ci.yml:71/:133/:193/:252 四个 job；其中 e2e-plugin 为非必需检查）。触发条件 = PR 或 master push 触碰 `agent-framework/**` 或 `.github/**`。
- **backend / frontend / platform（manifests、release-agent、仓库根 `e2e/`）侧不在 E2E 门禁内**：backend-ci 仅 `go vet ./... + go test ./...`（backend-ci.yml:48-50）；frontend-ci 仅 lint + test + build（frontend-ci.yml:52-58）；仓库根 `e2e/` 手工脚本**不被任何 workflow 引用**（`grep -rln "e2e/platform-e2e|e2e/ui-e2e|e2e/mcpclient|e2e/platform-config" .github/workflows/` → 零命中）。
- 纯 docs 变更不触发任何相关 job（目录过滤下 Skipped 视为通过）。

| # | commit（日期） | 变更 | 主目录 | CI 门禁相关性 |
|---|---------------|------|--------|---------------|
| 1 | 16df029（09-26 00:29） | bench/eval 评测环境按需供给（provision/teardown/mock MCP/requires_env） | agent-framework（bench/eval） | 触发 agent-framework-ci 单测；E2E 四组不覆盖 bench/eval 面 |
| 2 | 4307a71（09-26 00:29） | 评测环境按需供给设计文档（缺口分析/架构/组件/验收标准） | docs | docs，无行为 job |
| 3 | 3a9256a（09-26 08:56） | OTel chat/execute_tool span 内容属性（gen_ai.*）+ 8192 截断 | agent-framework（src） | 单测门禁；E2E 无 span 断言（e2e 实例不产 span）→ TR1-TR3 遗留（§5.1） |
| 4 | 3147a7b（09-26 11:25） | .gitignore 忽略根目录临时 zip | .gitignore | 无 job |
| 5 | fa4b2cd（09-26 11:46） | 插件冒烟进门禁（e2e-plugin job）+ 评测自检 job + 三态权限黑盒 | agent-framework/e2e + .github + AGENTS.md | 本 commit 即门禁面扩张本身：e2e-plugin/eval-selftest 两 job 上线（均非必需） |
| 6 | 4a1d38d（09-26 11:59） | plugin-smoke 注入 console-only logback，修 CI 非 root 启动失败 | agent-framework/e2e | e2e-plugin job 可用性修复；断言集不变 |
| 7 | 7040fe8（09-26 16:59） | /tools 拆 sdkInternal 段 | agent-framework（src + docs） | 单测门禁；既有 S1（api-core.spec.ts:27）/ PLUGIN-3 断言面 |
| 8 | 832b30e（09-26 22:53） | 《创建 Agent 完整指南》+ 14 篇文档重核 | agent-framework（docs）+ AGENTS.md | docs，无行为 job |
| 9 | 3dad588（09-27 01:38） | 模型采样参数扩展 + 推理引擎方言 | agent-framework（src） | 单测门禁；E2E 后由 bc235c4 补 MOD4-MOD7（api-models.spec.ts:185-412） |
| 10 | 9d7170b（09-27 14:36） | debug 页会话模型切换 picker | agent-framework（static debug 页） | 既有 U14（ui.spec.ts:412）看守 |
| 11 | 062e01f（09-27 15:20） | session_message 归档 + history 双源合并 | agent-framework（src + debug 页） | 单测门禁；E2E 后由 bc235c4 补 HA1-HA3/U15（api-core.spec.ts:1129-1199、ui.spec.ts:500）+ reset-data.mjs 补清 session_message |
| 12 | e5d6ff3（09-27 16:12） | llm-calls 记录键对齐规范 sid + span 属性同源 | agent-framework（src + e2e） | S7 同步改 llm-calls 非空断言；A2A 半边后由 bc235c4 补 A5-A7（api-core.spec.ts:617-703） |
| 13 | 4f96fea（09-27 21:17） | ASKING 态新 turn 入口预检拒绝 | agent-framework（src） | 单测门禁；既有 H6（api-core.spec.ts:490）钉该行为 |
| 14 | 4db16ba（09-27 22:45） | HITL 恢复收尾窗口竞态（confirm 排队抢锁 + 恢复身份取表行） | agent-framework（src） | 单测门禁；无定向 e2e，既有 H 组（H2/H4/H5）回归面间接覆盖 |
| 15 | bc235c4（09-28 03:10） | **上轮缺口分析补充用例落地**（实际新增 19 条 test()，提交信息自称「20 条」，对账见 §2.2） | agent-framework/e2e + docs | E2E 门禁扩张本身：HA/SK/MEM/MOD/A5-A7/X10(沙箱)/U15 进四个必需 job |
| 16 | f4db8ca（09-28 08:18） | 沙箱档 Channel 链路 userKey 反查真实用户（issue #52） | agent-framework（src + e2e） | 单测（SandboxUserKeyMiddlewareTest 新增）+ **同提交新增 X15**（api-sandbox.spec.ts:308）进门禁；核对结论见 §3.3 |
| 17 | 761c9b5（09-28 09:55） | Create LICENSE | LICENSE | 无 job |
| 18 | d31cd93（09-28 10:47） | **Redis cluster 模式与 key 前缀隔离** | agent-framework（src + test + docs） | 单测（AgentRedisPropertiesTest/RedisConnectionFacadeTest）+ 真 Redis IT；前缀隔离 e2e 行为级零覆盖 → 本轮 RD 组补（§3.2） |
| 19 | e1c9de9（09-28 11:31） | 平台默认配置 Secret 化（敏感键路由服务 Secret） | backend + frontend + manifests + docs | **platform 侧：仅单测门禁**；根 e2e/platform-config-secret-e2e.sh 为手工脚本（不进门禁） |
| 20 | 388e2ba（09-28 11:33） | e2e 本地环境契约修复 + Redis 前缀改动收尾 | agent-framework/e2e + src + test | d31cd93 收尾：RedisConnectionFacade 与两个 IT 调整 + e2e 编排脚本（env-up/start-agent）与 package.json 修复；随 E2E 四组门禁 |
| 21 | ed556c4（09-28 11:39） | package-lock resolved 改回公网 npmmirror | agent-framework/e2e | CI runner 网络可达性修复，无断言变化 |
| 22 | 363c676（09-28 11:41） | 服务 Secret 清理补 secrets delete verb | manifests + docs | platform 侧：仅 backend 单测连带；根 e2e 手工脚本可验，不进门禁 |
| 23 | d7065c2（09-28 12:40） | UpdateEnv 失败顺序/空 values 400 CR 修复 | backend + manifests | **backend 侧：仅 go 单测**，无 E2E 门禁 |
| 24 | 2d9be4a（09-28 13:09） | 平台默认配置 Secret 化 E2E（46 断言）+ 存量回归 | e2e/（仓库根，手工脚本） | **不进门禁**（无 workflow 引用），靠人工在部署环境执行 |
| 25 | faba96f（09-28 14:36） | 默认配置改表单默认填入，不再运行时注入 | backend + frontend + e2e + docs | platform 侧：仅单测；前端 settings 页无 UI 自动化（§5.2） |
| 26 | 68eec20（09-28 14:42） | 详情页轮询陈旧闭包覆盖未保存 env 编辑修复 | frontend | **frontend 侧：仅 lint/test/build**；commit 自述「浏览器实测」= 手工验证，无 UI 自动化（§5.2） |
| 27 | 76e0e74（09-28 16:36） | **DB 演进迁入 Flyway（V1 基线 + V6 存量回填）+ /threads slot 双形态匹配修复** | agent-framework（src + docs） | 单测门禁 + 既有 S2 起整组隐式哨兵；slot 双形态/V6 守卫零定向断言 → 本轮 FW 组补（§3.1） |
| 28 | 512a6b3（09-28 17:36） | Flyway 在 E2E/全新库静默不执行修复（补 spring-jdbc + flywayInitializer 依赖） | agent-framework（pom） | 修复「首次 CI 门禁四 E2E job 全红」（提交信息自述）；同类的本地复用库地雷由本轮 reset-data.mjs 修复兜住（§3.1/§4.2） |
| 29 | 3341132（09-28 17:37） | DB 演进 Flyway 设计归档 + 开发规范更新 | docs + AGENTS.md | docs，无行为 job |
| 30 | 7a1b116（09-28 20:24） | **业务 Ingress 模板（INGRESS_TEMPLATE）** | backend + docs | **backend 侧：仅 go 单测**；无任何脚本化 e2e（§5.2 已核实） |
| 31 | c3efa36（09-28 20:35） | INGRESS_TEMPLATE 契约与运维姿势文档 | docs + backend/AGENTS.md | docs |
| 32 | 36b5f57（09-28 20:48） | Ingress overlay 校验 CR 修复（defaultBackend 绕过/pathType/探针） | backend + docs | backend 侧：仅 go 单测 |
| 33 | eafd9b9（09-28 21:05） | Ingress 模板设计文档补部署后验证记录 | docs | docs |
| 34 | 98945a3（09-28 21:22） | **平台默认配置经 get_platform_defaults 暴露给发布助手** | backend + release-agent | backend 单测（mcpsrv server_test.go）；根 e2e mcpclient 工具清单未含该工具（§5.2 已核实） |
| 35 | e943226（09-28 21:29） | TestMCPPublishFlowAndReservedKey 等待异步终态 | backend | backend 侧：仅 go 单测 |

---

## 2. 现有覆盖盘点（相对上轮的增量）

### 2.1 门禁分组

与上轮文档 §2.1 一致，无变化：必需门禁 = 单测 `mvn test` + e2e-core（api-core / api-models / api-reload / ui 四项目）+ e2e-multi（api-multi / ui-multi / api-multi-kill）+ e2e-sandbox（api-sandbox）；非必需 = e2e-plugin、eval-selftest。

### 2.2 用例矩阵增量（上轮 25 条设计 → bc235c4 落地 19 条、6 条未落地；本轮新增 6 条）

上轮全部已落地用例均落进**既有 spec 文件与既有 Playwright 项目**，§2.3 job 表无需扩行（仅 e2e-core 行的内容列与 §3.1 端口表在本轮同步更新，见 §4.2）：

| 落地变更 | 用例 → 位置（盘点时点行号） | 组/项目 |
|----------|------------------------------|---------|
| bc235c4（09-28） | HA1/HA2/HA3 → api-core.spec.ts:1129/1163/1199；A5/A6/A7 → :617/:669/:703；SK2-SK4 → :989/:1055/:1082；MEM1-3 → :1263-1350（describe 级第二实例，端口 8110）；MOD4-MOD7 → api-models.spec.ts:185/266/313/412；X10/X11（沙箱技能）→ api-sandbox.spec.ts:169/:237；U15 → ui.spec.ts:500；另 reset-data.mjs 补清 session_message、llm-server.mjs 增 vllm 方言夹具路由 | core / sandbox / ui |
| 本轮（未提交） | FW1/FW2/FW3 → api-core.spec.ts:751/:793/:842+:913；RD1/RD2/RD3 → :1408/:1449/:1478（明细见 §4.1） | core |

> **「20 条」对账**：bc235c4 提交信息自称「20 条补充用例」，实际新增 `test(` **19 处**（`git show bc235c4 -- <spec> | grep -cE '^\+\s*test\('`：api-core 12 / api-models 4 / api-sandbox 2 / ui 1，本汇编实跑）。差额为上轮文档 §4.5 的第 4 条——**X10（记忆面，落地时建议重编号 X14，上轮文档 :1670）「沙箱档记忆关断 no-op」**：既不在 bc235c4 落地清单（e2e/tests 全目录 grep `X14` 零命中、api-sandbox.spec.ts 无 AGENT_MEMORY_ENABLED，本汇编已复核），也未进任何搁置登记。本表与 §3.4/§5.1 为修正后的口径（25 = 19 落地 + 6 未落地）。

---

## 3. 本轮缺口比对结论（变更 × 覆盖）

> 以下三节的问题定义与证据要点**转写自本轮设计素材（任务材料）**，其中的源码行号、grep 命中、提交祖先关系（`git merge-base --is-ancestor <c> origin/master`，76e0e74/512a6b3/d31cd93/f4db8ca 均 YES）已由本汇编逐一复跑核实。
> **核实口径与「即红」类断言的证据强度**：复跑核实 = 文本/行号/命中/祖先关系的**存在性核对**，不含行为级变异验证。下文「即红/必红」类反事实断言属**源码推理给出的预期红点**：其中仅 FW3 三道守卫在设计环节做过 SQL 谓词变体级实证（逐字复刻 V6 四谓词的变体 SELECT——去掉任一守卫，对应该守卫的行即被回填；设计环节诊断实跑输出），FW1/FW2/RD 的「回归即红」未做实现级变异复跑，其正向验证为 §4.3 的通过轮。

### 3.1 缺口一：Flyway 迁移 + /threads slot 双形态匹配（76e0e74 / 512a6b3）→ FW 组（已落地）

**缺口定义**：76e0e74 把 ThreadController 的列表两 SQL、getThread 元信息、deleteBySessionId 的匹配从 `= ? OR LIKE CONCAT(?, ':%')` 换成 **SUBSTRING_INDEX 冒号前/后段双向匹配**（`AGENT_STATE_JOIN`，ThreadController.java:58-60；列表 :160-162 与 :188-189、详情元信息 :254-256、删除 :468-471），提交信息明示修复症状——规范形态槽位 `{userId}:{sid}` 匹配落空导致详情 updated_at 退化、删除留孤儿行。但既有用例零感知：S7（api-core.spec.ts:157）对详情只断 200（:163）、删除只断列表移除（:187-188），全部 spec 无 SUBSTRING_INDEX/updated_at 溯源/agent_state 直查断言——**双臂 SQL 任一臂回归在门禁不可见**。

**V6 存量回填**（V6__backfill_session_user_from_agent_state.sql）在 CI 每轮全新库上 agent_state 为空恒 no-op，其三道守卫（V6:49 `c.uid<>'unknown'`、:50 `c.sid<>'unknown'`、:51 `claimants=1`，已实读核实）与 baseline-version=5 升级路径（application.yml:27-31 `baseline-on-migrate: true`/`baseline-version: 5`）无任何覆盖——**baseline-version 被误抬高会在「存量库」静默跳过 V6**（「存量库」为本项目口径：有表、无 flyway_schema_history，首启走 baseline-on-migrate 到 baseline-version，application.yml:23 注释原文；已有历史表的库不走 baseline，抬高该值无涉）。实测真实 e2e 库两种槽位形态并存（老形态 `{sid}:gw-hash` 残留与规范形态 `{uid}:{sid}`），两臂都值得钉。

**512a6b3 配套基建地雷（本轮新发现）**：512a6b3 同步改的 reset-data.mjs TABLES 漏了 V2 引入的 **agui_interrupt**——第二次起 env-up 在残留表上走「schema 非空 + 历史表缺失」→ baseline(5) → V6 因 session_user 缺失失败 → 主实例启动死亡（设计环节实跑复现：agent-a.log 单次启动记录 baseline 5 → 'Migration V6 failed' → cancelling refresh；手工 DROP 后恢复全新链）。CI 的 fresh services MySQL 不受影响，但**本地/复用库第二轮 env-up 必踩**——已随本轮修复（§4.2）。与 512a6b3 所修问题的关系：故障表现同向（迁移环节出错 → 实例不可用 → 门禁/本地全红），机理不同——512a6b3 是依赖缺失致迁移静默不执行，本例是清场清单漏表致误走 baseline 路径后 V6 失败。

**落地**：FW1（A2A 规范槽位 DB 形态 + 详情 updated_at 溯源 + 删除无孤儿；若回退旧 LIKE 前缀实现，规范槽位经旧实现恒匹配落空，详情 updated_at 断言为预期红点）、FW2（SQL 直插老 Channel 形态 `{peer}:{gw-hash}` 种子——session_user 与 agent_state 两行 updated_at 时间戳刻意错开（「双轨」），使列表/详情的 updated_at 值断言必须经 SUBSTRING_INDEX **冒号前段臂**命中 agent_state 行才能取到该值，前段臂被删即预期红点）、FW3（V6 三守卫种子 + DROP 历史表 + 第二实例走 baseline(5)→V6 升级路径，钉 baseline-version 抬高）。场景矩阵登记于 e2e-ci-plan.md §5.10。

### 3.2 缺口二：Redis cluster 模式与 key 前缀隔离（d31cd93）→ RD 组（已落地）

**缺口定义**：d31cd93 新增 `agent.redis.mode/cluster-nodes/prefix`（application.yml，env 占位符 AGENT_REDIS_MODE/CLUSTER_NODES/PREFIX）与 RedisConnectionFacade 统一连接门面，前缀经 `facade.key()` 施加于事件流（RedisEventLog.java:120-128 eventsKey/repliesKey，已核实）与沙箱守卫（RedisSandboxExecutionGuard）。落地前 e2e 全目录对 `AGENT_REDIS_PREFIX|AGENT_REDIS_MODE|AGENT_REDIS_CLUSTER_NODES|agent.redis` **零命中**（设计环节 grep exit=1）——多 Agent 共用 oaf-redis 的前缀隔离**完全无双实例行为级用例**；既有 R 组多副本是「无前缀同命名空间共享」语义——与隔离恰好相反（R 组验证的是共享命名空间下的互通/续传，不是隔离）。守卫前缀仅单测覆盖（RedisConnectionFacadeTest 断言 sess:/sbx:guard: 两族 key 拼接）；cluster 模式无集群夹具不可黑盒（§5.1）。

**落地**：RD1（同 Redis 双实例——默认无前缀 vs `e2e-isolated` 前缀（:1368 describe，spawnSync 端口 8111）——事件流与断线续传互不可见，双向回放 maxSeq 精确对账）、RD2（DELETE 只清本实例命名空间，若删除未走前缀而波及对方流，对方侧 seq 断言为预期红点）、RD3（门面启动日志 `prefix="e2e-isolated:"` 补冒号规范化直证 + 默认实例 `prefix=""` 对照防 env 泄漏）。场景矩阵登记于 e2e-ci-plan.md §5.11。前缀只切 Redis key——共享 MySQL 的 history/threads 列表/详情面**并不隔离**（这是设计内语义而非遗漏），用例刻意只断 /status 与 /subscribe 面。

### 3.3 核对结论：f4db8ca 沙箱档 Channel 链路 userKey 反查——无新增缺口

f4db8ca（09-28，issue #52）修沙箱档 Channel 链路 userKey 退化：SandboxUserKeyMiddleware 原样取 ctx 值（peer/gw-hash）作沙箱 userKey → 物化/回写按 sessionId 查 KV 恒空。经与 X 组逐一对照，**该提交自身已闭合 e2e 缺口，本轮无新增用例空间**：

- **X15**（api-sandbox.spec.ts:308，f4db8ca 同提交新增，+30 行已核实）：Channel 链路 per-user 技能物化回归门禁——修复前按 peer 查 KV 恒空、探针必红；
- **X9**（:133）：present_file 归属校验全链路，f4db8ca 提交信息明言该缺陷正是「e2e X9 实测暴露」，现有断言持续看守；
- X10/X11（:169/:237）走 A2A（metadata.userId 透传）验物化/tombstone 语义本体，Channel 侧回归由 X15 承接。

残余两点本轮不覆盖（转写自设计素材 notCoveredNotes，已核对行号）：① OpenSandbox.doExec 重绑的黑盒断言路径依赖沙箱文件写读链路，被 X3 test.fixme（:54，「回放 write_file tool_call 不触发沙箱写入」框架缺陷）阻断；② ThreadLocal clearPendingUserKey 卫生是 reactor 线程池复用竞态，黑盒只能概率观测，由 SandboxUserKeyMiddlewareTest（f4db8ca 新增 146 行）单测覆盖。

### 3.4 上轮缺口收尾对照

上轮 25 条设计：**19 条已由 bc235c4 落地**（§2.2）；**6 条未落地**，按本轮约束继续搁置——X12/X13（需开发机重录 LLM 录制件）、TR1-TR3（需 OTLP mock 扩展）、**X14**（沙箱档记忆关断 no-op，上轮 §4.5 第 4 条；前置 = env-up.sh sandbox 分支扩第二实例 + 记忆种子脚本，上轮文档 :2176）。注：bc235c4 提交信息的「20 条」与实际新增 test() 数（19，§2.2 对账）不符，差额即 X14——它此前既未被落地也未被登记搁置，本节为修正后的口径。

---

## 4. 本轮落地明细

### 4.1 用例清单（6 条，全部 api-core 项目 → e2e-core job 必需门禁）

| caseId | 标题 | 组/项目 | 文件:行号 |
|--------|------|---------|-----------|
| FW1 | A2A 规范槽位（{userId}:{sid}）列表/详情可见、删除无孤儿行 | core / api-core | agent-framework/e2e/tests/api-core.spec.ts:751 |
| FW2 | 老 Channel 形态槽位种子：列表/详情 updated_at 取自 agent_state、删除无孤儿 | core / api-core | api-core.spec.ts:793 |
| FW3 | V6 回填：自洽规范槽位入列且 updated_at 溯源 agent_state；三道守卫行均不入列（describe 级第二实例 fwv6，端口 8112） | core / api-core | api-core.spec.ts:842（describe）+:913（test） |
| RD1 | 同 Redis 双实例前缀隔离：事件流与断线续传互不可见 | core / api-core | api-core.spec.ts:1408 |
| RD2 | 删除会话前缀互不影响：DELETE 只清本实例命名空间 | core / api-core | api-core.spec.ts:1449 |
| RD3 | 前缀配置生效直证：门面启动日志 prefix 字段（含冒号规范化） | core / api-core | api-core.spec.ts:1478 |

每条的步骤/断言级定义与设计原理见 e2e-ci-plan.md §5.10（FW）/§5.11（RD）——这是**入库的可复核登记载体**；用例实现本体即 api-core.spec.ts（含落点注释）。设计过程稿（逐条 draftCode 与风险清单）未单独入库，其有效结论已被上述两处与落地代码取代。

### 4.2 配套基建（worktree 未提交变更，`git status` 共 3 文件）

| 文件 | 改动 | 动机 |
|------|------|------|
| agent-framework/e2e/tests/api-core.spec.ts | +367 行：文件头注组清单、`import mysql from 'mysql2/promise'`（:13）、FW 组小节（小节级 openDb/slotRowCount helper，archive-seed.ts 同款地址解析顺序）、RD describe | FW/RD 六用例本体；DB 直查与第二实例编排复用既有先例（HA 组直插种子、MEM 组 spawnSync），零新 spec 文件/项目 |
| agent-framework/e2e/scripts/reset-data.mjs | TABLES 补 `'agui_interrupt'`（:20-23） | §3.1 地雷：漏清使本地/复用库第二轮 env-up 走 baseline(5) → V6 失败 → 主实例启动死亡；CI fresh services 不受影响 |
| agent-framework/docs/e2e-ci-plan.md | +55 行：§5.10 FW 组、§5.11 RD 组场景矩阵（含不覆盖清单）、§2.3:106 e2e-core 行内容列、§3.1 端口表补 8110/8111/8112 行 | 仓库规范要求新增用例同一变更内同步该文档；无新 spec 文件/项目故 job 表不扩行 |

零改动：env-up.sh / start-agent.sh / playwright.config.ts（三例均落 api-core 项目）、无新 LLM 录制件（全部复用 `[E2E:plain]`）、无新 npm 依赖（mysql2 已在 e2e devDependencies）。主代码 src/main 零改动。

### 4.3 本地门禁结果

> 来源：落地环节实跑产出（任务材料提供），**本汇编未复跑**。旁证（本汇编已核实存在于 worktree）：`agent-framework/e2e/.runtime/env.json` 末轮 group=`"sandbox"`、`.runtime/logs/` 存有 agent-fwv6.log（FW3 第二实例）/ agent-rdpfx.log（RD 前缀实例）/ agent-memoff.log 等取证日志。**证据强度声明：旁证只能证明「跑过」（且仅末轮 sandbox 可对上），不能证明下表 JSON 的 green 值；core/multi 两轮在本地无留痕，green 值采信自任务材料。**

```json
[
  {"group":"core","green":true,"rounds":1,"note":"第 1 轮 exit=0"},
  {"group":"multi","green":true,"rounds":1,"note":"第 1 轮 exit=0"},
  {"group":"sandbox","green":true,"rounds":1,"note":"第 1 轮 exit=0"}
]
```

落地环节另完成的定向自验（任务材料自述，本汇编未复跑）：`-g "FW|RD|MEM"` 组合回归 10 passed（三个第二实例 describe 同文件顺序共存无扰）；二轮 env-up（复用库地雷前态）13/13 表清理、Flyway 全新链 [1..6] 全 success——即 reset-data.mjs 修复的端到端实证。

### 4.4 生效路径与 CI 侧注意

- **当前状态**：三个文件均为 worktree 未提交变更（§4.2）——CI 门禁实效以 PR 合入后 agent-framework-ci 四 job 全绿为准，§4.3 只是本地证据，不能替代门禁。
- **CI 与本地库的行为分叉**：CI services 为每轮全新 MySQL，本就走「全新库 Flyway 重建」路径，不受 reset-data 漏 agui_interrupt 地雷影响；该修复惠及的是本地/复用库。FW/RD 六例在 CI fresh 库上的首轮运行，是合入后需要观察的真正新验证面（§3.1 自己指出的分界）。
- **时长与稳定性预评估**（来源：设计材料 + workflow/源码核实）：e2e-core job 预算 30min（agent-framework-ci.yml:76 `timeout-minutes: 30`）；FW3 第二实例预算 spawnSync 150s + /health 轮询 60s、RD beforeAll 冷启动第二 JVM 约 15-25s，均仿 MEM 组 describe 级 spawnSync 先例（已在 CI 稳定运行）；RD1 的 maxSeq 精确对账依赖「seq 在 append 时分配、waitTerminal 后 latest_event_seq 即终局值」不变量（SessionEventStore.java:263 起 `nextSeq` 于 append 内分配，本汇编已核实），无阈值余量依赖；playwright retries=1 下失败重跑会重跑 beforeAll，spawnSync 自带端口清场与 pid 覆盖、幂等。以上为预评估，CI 实际表现待合入后观测。

---

## 5. 不可黑盒覆盖与遗留项

### 5.1 agent-framework 侧

| 项 | 状态 | 前置/理由 |
|----|------|-----------|
| Redis cluster 模式 | 不可黑盒，不设计 | e2e 基础设施为单节点 standalone Redis（CI service 与本地 e2e-redis 均无集群夹具），起真集群超 e2e 编排成本口径；已有覆盖 = AgentRedisPropertiesTest（mode 枚举/种子回落/prefix 校验）+ RedisConnectionFacadeTest（mode 分流 + cluster hash tag key 形态）+ RedisEventLogIT / SessionEventStoreCrossReplicaIT 真 Redis 双路径（本汇编已核实测试文件存在） |
| X12/X13（沙箱档容器内 skill_manage → KV 回写/仲裁） | 遗留，搁置 | 需开发机重录 `skill-manage-sb` LLM 录制件（mock/fixtures/llm + registry + MARKER_MAP），录制前还须验证 X3 同族缺陷不命中 skill_manage 写入链（上轮文档 §5.2） |
| X14（沙箱档记忆关断 no-op：预置 MEMORY.md 不注入容器） | 遗留，搁置（上轮 §4.5 第 4 条，bc235c4 未落地，§2.2/§3.4 对账） | 需 env-up.sh sandbox 分支扩第二实例（SANDBOX_ENABLED=true + AGENT_MEMORY_ENABLED=false + 独立端口）与 scripts/seed-memory.mjs（agent_fs 行编码需逆向 agentscope-harness jar，上轮文档 :2176）；e2e/tests 至今零覆盖（grep X14 = 0、api-sandbox 无 AGENT_MEMORY_ENABLED，本汇编已复核） |
| TR1-TR3（OTel span 内容属性，3a9256a） | 遗留，搁置 | 需 OTLP mock 扩展（mock/otlp-receiver.mjs + env 注入 OTEL_* + lib/otlp.ts）——当前 e2e 实例不产任何 span，属全组前置缺口（上轮文档 §5.3） |
| V6 INSERT IGNORE 幂等重跑 / flyway checksum 破坏 | 有意不覆盖 | 历史表已含 version=6 时二次启动无迁移可跑，黑盒无从与「守卫正确」区分；checksum 破坏被 env-up wait-ready 隐式拦截（实例起不来 env-up 直接失败）（e2e-ci-plan.md §5.10） |
| 沙箱守卫族（sbx:guard:*）前缀行为 | 有意不覆盖 | 守卫仅 SANDBOX_ENABLED=true 激活，core 组两实例均 sandbox=false；wall-clock 时序断言 flaky；确定性断言需直读 Redis 键而 e2e 无 redis 客户端依赖（e2e-ci-plan.md §5.11） |
| 「全新库 Flyway 重建即服务可用」 | 隐式覆盖，不另设用例 | 迁移静默不执行 → turn_lease/session_user 缺失 → 所有聊天流不收敛 → 自 S2 起整组转红（任何依赖聊天收敛的用例全红；512a6b3 提交信息实录该形态）；既有套件即全量哨兵 |

### 5.2 平台侧缺口与建议（backend / frontend / platform——均不在 CI E2E 门禁内，以下为本汇编实跑核实）

| 缺口 | 变更 | 核实证据 | 建议 |
|------|------|----------|------|
| INGRESS_TEMPLATE 无脚本化 e2e | 7a1b116（backend 10 文件 +875/-24）+ 36b5f57 校验收紧 | `grep -rn "INGRESS_TEMPLATE" e2e/*.sh e2e/mcpclient/*` → exit=1 零命中；platform-e2e.sh 仅做 ingress 对象存在性检查（A12/B11/D3），不触及 overlay 语义（host/path/TLS/注解/Endpoint 跟随）；backend-ci 仅 go 单测 | 在仓库根 e2e/ 增补手工脚本段（platform-e2e.sh 或独立 ingress-template-e2e.sh）：发布带 INGRESS_TEMPLATE 的服务后 `kubectl get ingress -o yaml` 断言 overlay 生效 + Endpoint 自动跟随 + 36b5f57 堵的两类绕过负例；该脚本属人工执行档，不进门禁 |
| get_platform_defaults 未进 e2e mcpclient | 98945a3（backend + release-agent） | 工具在 backend/internal/mcpsrv/server.go:161，单测覆盖（server_test.go）；e2e/mcpclient/main.go C1 对 10 个工具做**存在性断言**（main.go:95-103 `names[want]`，非精确计数——服务端多注册/多余工具不会红，少注册才红），其中**无 get_platform_defaults**；`grep get_platform_defaults e2e/*.sh` → 零命中 | mcpclient C1 工具清单断言补该工具 + 追加一次调用断言（返回结构含 llm/redis/mysql/sandbox 默认键）；否则 MCP 面契约漂移（工具漏注册/更名）只有 backend 单测能拦，经 /mcp 的真实协议面无守卫 |
| 前端 settings 页 / env 轮询修复无 UI 自动化 | faba96f（settings 表单默认填入）+ 68eec20（详情页轮询陈旧闭包修复，frontend/src/app/services/[id]/page.tsx 4+/2-） | 68eec20 提交自述「浏览器实测」= 手工验证；frontend-ci 仅 lint/test/build（frontend-ci.yml:52-58），无任何 UI 自动化；根 e2e/ui-e2e.js 覆盖详情页 env 编辑（D3）但无「填入平台默认后跨轮询边界不被冲掉」场景，`grep -c settings` 于 ui-e2e.js/platform-e2e.sh/platform-config-secret-e2e.sh 全为 0——settings 页零自动化 | ui-e2e.js 增两段：① settings 页改默认配置 → 新发布向导表单预填断言；② 详情页「填入平台默认」后等待 ≥2 个轮询周期（约 10s+）断言填入值不丢（正是 68eec20 的回归面）。保持手工档，不进门禁 |

> 三条均为缺口登记与建议路径，**归属与排期本文档未定**（超出静态分析范围，需平台侧维护者裁决）。若需自动化接入，可选路径参照既有先例：fa4b2cd 即以「非必需 job」形态把 plugin-smoke 接入门禁（新增 job 默认不挡合并，转必需需仓库管理员改分支保护，见根 AGENTS.md CI 节）；mcpclient 补断言与 ui-e2e 扩段维持手工档成本最低。

---

## 6. 性质声明

本文档的取证分两类口径，均为 2026-09-29 在 worktree `/root/agent-manager-wt-e2ecov` 实跑：① §1-§2、§4、§5 的**工作区/仓库状态类取证**（git log/show/status/diff、grep、CI workflow、测试文件存在性）由本汇编直接执行；② §3 的**问题定义转写自本轮设计素材（任务材料）**，其中的行号/命中/祖先关系经本汇编复跑核实——核实口径为存在性核对（文本在、行号对、命中数符），不含行为级变异验证，「即红/必红」类断言的证据强度见 §3 导语。§4.3 门禁结果与落地环节定向自验为该环节产出、本汇编未复跑，旁证只能证明「跑过」而非断言值本身（§4.3 已声明）。平台侧三条缺口（§5.2）的核实口径为静态覆盖检查 + CI workflow 范围核对，未在部署环境实跑任何平台脚本。
