# DB Schema/数据演进迁入 Flyway 设计（V1 基线 e91d1f0 + 存量回填）

> 状态：已实施（2026-09-28，PR #58）
> 关联排查：`/threads?userId=debug-user` 存量历史不可见（见 §1 根因）

## 1. 背景与根因

### 1.1 现象

`GET /agent/release-agent/threads?userId=debug-user` 只返回当天创建的 1 条会话，升级前该接口（旧实现忽略 userId 参数）会返回全量历史。

### 1.2 根因（两层）

1. **列表数据源演进断裂**：`0cd8a90` 起 `ThreadController.listThreads()` 从「`agent_state` 全表 session_id 去重」改为「`session_user` 驱动 + LEFT JOIN `agent_state`」。`session_user` 只在发消息时由 ChatStreamController/A2AController/ConfirmController upsert 写入，**没有任何存量回填机制**——库里 76 个会话槽位中 33 个从未登记，对任何过滤条件都不可见。
2. **userId 命名空间碎片**：不同入口写入的 `user_id` 不同（发布助手硬编码 `webui`、调试台默认 `debug-user`、eval 走 `eval-test`、更老的 Channel 链路把 peerId 当 userId），即使回填也要面对归属不可考的问题。

### 1.3 伴生缺陷：JOIN 方向

列表 JOIN 的 `a.session_id LIKE CONCAT(su.session_id, ':%')` 只能匹配老 Channel 形态 `{peer}:{key}`，漏掉规范形态 `{userId}:{sid}`（`SessionKeyResolver` 解析后写入）：A2A 会话整条不可见、Channel 会话 `updated_at` 退化为 `session_user.created_at`、`deleteThread` 在规范形态槽位留孤儿行。`AgentStateReader` 早已双向探测（含 `'%:', ?` 后缀形态），history 不受影响。

### 1.4 结构性问题（促使引入迁移工具）

排查时盘点：**9 个类散落 11 处 `CREATE TABLE IF NOT EXISTS`、6 处 `ADD COLUMN`**（各 Store 构造器自建，`ThreadController.ensureRemarkColumn` 甚至在请求路径上跑 JDBC 元数据探测）。该模式只覆盖"建表/加列"，没有"数据迁移"概念（§1.1 正是数据无回填路径所致）；演进历史无法审计；多副本同时启动存在 ALTER 竞态（靠 catch 成 warn 赌运气）。

## 2. 方案选型

| 方案 | 说明 | 结论 |
|------|------|------|
| Flyway（社区版） | Spring Boot 生态标准；版本化 SQL/Java 迁移、历史表 checksum、自带多副本锁、fail-fast | **采用**（版本由 spring-boot-dependencies 管，Boot 3.3.5 → 10.10.x） |
| Liquibase | 能力更强但重（XML/YAML changelog），收益不成比例 | 不采用 |
| 自研 SchemaMigrator | 版本表 + `GET_LOCK` + 有序迁移列表，约百行 | 不采用：锁语义/失败处理/漂移校验都要自己养，长期成本高于现成方案 |

## 3. 迁移链设计

### 3.1 基线策略

以 **e91d1f0（2026-09-20，PR #8 合并点）** 为分界：之前的表结构收敛为 `V1__baseline_e91d1f0.sql`，之后的每次涉库提交各占一个迁移，本次新增的存量回填收尾。

**V1 以现网 `SHOW CREATE TABLE` 为准，而非当时代码里的 initSchema 文本**——老表由更早版本代码创建且未显式声明 COLLATE，实际落成 server 默认 `utf8mb4_0900_ai_ci`，与后续新表（显式 `utf8mb4_unicode_ci`）并存；重建必须忠实还原，否则跨表 JOIN 会因排序规则不一致报错。代码 DDL 与现网的历史漂移（如 `file_asset` 现网无 `idx_origin_status`）一并按现网固化。

### 3.2 迁移清单

| 版本 | 内容 | 来源 |
|------|------|------|
| V1 | 基线 9 表：agent_state、agent_fs（SDK 表，带 `IF NOT EXISTS` 防 SDK bean 与 Flyway 初始化顺序竞态）、session_user（无 model 列）、confirm_context、turn_lease、file_asset、kv_sync_key、tool_audit_log、ui_context | e91d1f0 时点现网 schema |
| V2 | `agui_interrupt`（AG-UI HITL interrupt 元数据） | 2cd55f9（作者 09-11，合并部署晚于基线点，现网已存在；COMMENT 取干净中文） |
| V3 | `model_config` 建表 + `session_user` 补 `model` 列 | 25246e3（2026-09-24） |
| V4 | `model_config` 补 `reasoning_effort`/`frequency_penalty` | 3dad588（2026-09-27） |
| V5 | `session_message` 消息轨归档表 | 062e01f（2026-09-27） |
| V6 | 存量回填：从 `agent_state` 规范槽位补建 `session_user` 行（数据迁移，见 §3.4） | 本次新增 |

### 3.3 升级路径

`application.yml`：`spring.flyway.baseline-on-migrate=true`、`baseline-version=5`。

- **现网存量库**（有表、无 `flyway_schema_history`）：首启自动基线到 V5（V1..V5 的 DDL 均已就位），仅执行 V6 增量；
- **全新库**（CI E2E / 新环境）：schema 为空不触发基线，从 V1 完整重建；
- **多副本同时启动**：Flyway 历史表锁互斥，仅一副本执行迁移，其余等待后校验；
- **失败语义**：迁移失败即启动失败（fail-fast），替代原先 DDL 失败被 catch 成 warn 静默带病运行。

### 3.4 V6 回填规则（保守口径）

数据源限定 `agent_state` 中 `state_key='agent_state'` 的会话状态行，逐条守卫：

1. **自洽性**：slot 形态 `{userId}:{sid}` 且与状态 JSON `$.user_id`/`$.session_id` 双向一致——老形态 `{peer}:{gwHash}` 的真实用户不可考，不回填；
2. **无主残留**：`user_id`/`session_id` 为 `unknown` 的降级写入跳过；
3. **共享桶排除**：同一 `sessionId` 被多个 `userId` 认领的是共享运行时桶（gw-hash，老 Channel 链路所有 peer 共用一个 hash 作 session 键），不是真实业务会话，跳过——真实业务会话 id 全局唯一，只归属一个用户；
4. **幂等**：`INSERT IGNORE`，已登记行原样保留，可重复执行。

实测现网数据（agent_state 213 行 / session_user 59 行）回填**零新增**——存量可见性恢复主要由 §4 的 JOIN 修复承载（可达槽位 43→57），V6 的价值是兜住"写了 agent_state 但未及登记"的孤儿类（人工构造验证恢复正确），并把"数据回填必须随结构变更同版本交付"的机制建立起来。

## 4. /threads slot 双形态匹配修复

`ThreadController` 中列表两条 SQL、`getThread` 元信息、`deleteBySessionId` 统一改为：

```sql
ON SUBSTRING_INDEX(a.session_id, ':', 1)  = su.session_id   -- 老 Channel 形态 {peer}:{key}
OR SUBSTRING_INDEX(a.session_id, ':', -1) = su.session_id   -- 规范形态 {userId}:{sid}
```

- session_id 无冒号时 `SUBSTRING_INDEX` 返回原值，精确匹配同被覆盖；
- 相比 LIKE 前缀，等值匹配顺带消除了 `_`/`%` 通配符误匹配；
- 边界：老形态槽位的冒号后段（gw-hash）若恰好与某业务 session_id 同名会被误关联——现网不存在该命名（gw-hash 从不作为 `session_user.session_id`）。

## 5. 手工 DDL 收口

- 移除 8 个 Store（SessionUserStore/ConfirmContextStore/FileAssetStore/ModelConfigStore/SessionMessageStore/ToolAuditStore/TurnLeaseStore/UiContextStore）构造器的 `initSchema/ensureColumn` 与 `ThreadController.ensureRemarkColumn`（请求路径 DDL 探测一并清掉）；
- Store 构造器不再访问 DB，HTTP 流量在 Spring 上下文刷新完成后才开始，Flyway 迁移必然先于首次业务查询执行；V1 中两张 SDK 表带 `IF NOT EXISTS`，SDK 侧（`agentscope-extensions-mysql`，同为 `IF NOT EXISTS`）与 Flyway 谁先初始化都安全；
- 真实 MySQL 的 `*MySqlIT`（SessionUserStoreMySqlIT / ModelConfigStoreMySqlIT / ThreadHistoryConfirmIT）改经新增的 `TestSchemaMigrator`（按文件名升序执行 classpath `db/migration/V*.sql`，容忍 1050/1060"已存在"错误实现重复执行等价）执行同一批迁移文件——测试 schema 与生产迁移链同源。

## 6. 验证记录（2026-09-28）

| 验证项 | 结果 |
|--------|------|
| scratch MySQL（docker mysql:8.0）跑完整链 V1..V6，12 表 `SHOW CREATE TABLE` 与现网逐表 diff | 全部一致（仅 AUTO_INCREMENT 差异） |
| 灌现网真实数据（agent_state 213 行 + session_user 59 行）跑 V6 | 59 行保持不变，零误插（gw-hash/unknown 被守卫排除） |
| 人工构造未登记规范槽位 | 正确恢复为 `session_user` 行（含时间戳取自 agent_state） |
| V6 重复执行 | 幂等（59 不变） |
| 可达会话槽位（新旧 JOIN 对比，现网数据） | 43 → 57（其余 19 个为无主测试残留） |
| 模拟 `?userId=debug-user`（新 JOIN） | 返回正确标题"查看所有服务状态"与真实 updated_at |
| `mvn test` | 1100 例 / 0 失败 / 0 错误 / 4 跳过（环境变量门控 IT） |

### 6.1 CI E2E 揪出的两个静默缺陷（首次门禁四 job 全红的根因）

`mvn test` 全绿 ≠ 迁移真的在执行。CI 四个 E2E job 同时红（所有聊天"流未收敛"），本地全量复现后定位到两处：

1. **spring-jdbc 缺失 → Flyway 整段静默不装配**。`FlywayAutoConfiguration` 外层条件全部匹配（类在 classpath、DataSource bean 存在），但内层 `FlywayConfiguration` 有 `@ConditionalOnClass(JdbcUtils)`——`JdbcUtils` 在 **spring-jdbc** 里，而本项目不引 `spring-boot-starter-jdbc`。结果是：启动零报错、Flyway 零执行、连 `flyway_schema_history` 都不建，所有业务表缺失。修复：pom 显式补 `spring-jdbc`。教训：**接入 Spring Boot 自动配置类功能，不能只看外层 @ConditionalOnClass，要确认内层嵌套配置的全部条件类在 classpath**——这类失败完全静默。
2. **SDK store 与 Flyway 的建表竞态 → V1..V5 被基线跳过**。补上 spring-jdbc 后 Flyway 开始执行，但 SDK `distributedStore` bean 构造期抢先建了 `agent_state/agent_fs`，Flyway 看到"非空 schema"按 `baseline-on-migrate` 语义直接基线收场，`session_user/turn_lease` 等表仍然缺失。修复：`AgentScopeConfig.distributedStore` 加 `@DependsOn("flywayInitializer")`（注意 bean 名是 `flywayInitializer`，类名才是 FlywayMigrationInitializer）。另：`e2e/scripts/reset-data.mjs` 补清 `flyway_schema_history`，否则本地重跑（脏库）时 Flyway 跳过重建。

修复后：全新库启动即跑全链 V1..V6（历史表 6 条全 success）、本地 multi E2E 场景复跑通过。

## 7. 后续演进纪律（开发规范）

1. **表结构/数据演进一律新增 V 文件**（`agent-framework/src/main/resources/db/migration/V<N>__<描述>.sql`），命名带来源提交 hash 便于考古；**禁止**回到 Store 构造器手工 DDL、禁止修改已合并的 V 文件（checksum 校验会拒绝启动）。
2. **Expand-Contract**：每个版本只做加法（加表、加列、回填），删列/改列/改名延后一个版本，代码在一个发布周期内兼容新旧两种数据形态。
3. **"新机制只写新数据"的改动，必须同版本配套回填迁移**——本设计 §1.1 的根因正是 DDL 有自动兜底而数据没有。
4. **读侧兜底保留**：`/threads` 这类"重建视角"查询对未登记数据做降级合并（本 PR 的双向匹配），作为迁移遗漏时的最后防线。
5. 部署无需人工干预：发版后 release-agent 首启自动基线/迁移；上线前如需演练，可参照 §6 用 scratch MySQL 跑全链对比。
