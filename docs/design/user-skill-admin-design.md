# 用户技能管理（L4）与沙箱回写修复 — 设计与实施记录

**日期**：2026-09-23　**状态**：已实施（全量单测实跑 825 用例 / 0 失败 / 跳过 4；E2E 脚本 `e2e/user-skill-admin-e2e.sh` 入库，集群实跑前置见 e2e/AGENTS.md。终审补丁：写入侧栅栏 §6.2-5/§6.4、标记与截断可见性 §4/§7、索引失败 500 §3/§9、E9 与 UI 门禁 §8）

## 1. 背景与目标

技能此前分两层：L2 包内技能（PVC `/config/skills`，只读、每轮重扫）与 L4 用户级覆盖（agent_fs KV，由会话内 `skill_manage`/`propose_skill` 自学习写入）。L4 没有管理面——看不到「谁有个人技能」，不能读/改/删，更不能把包内技能下发成某用户的个人版本。

本次交付三项：

1. **用户技能管理 API**：`/skills/users/*`（用户索引、列表、读取、写入、删除、从包内下发）+ 调试页 Skills 模块「用户技能」区块；
2. **缺陷修复**：沙箱档会话内 `skill_manage` 写 L4 不落库（只存活于容器 TTL，到期彻底丢失）；
3. **回归脚本**：`e2e/user-skill-admin-e2e.sh`（非沙箱 37 断言；`SANDBOX=1` 加沙箱回写用例 E8 与回写仲裁用例 E9）。

终审补的四项（均为对称性/可见性缺口，不改变主流程）：管理面写入缺与删除对称的回写仲裁（§6.2-5）；tombstone 无可见性导致「用户重建了却不落库」无法解释（§4）；用户索引 SQL 失败被降级成 200 + 空列表（§9）；调试页不读 `truncated`、把子集当全集（§7）。

## 2. 存储形态（L4）

| 项 | 取值 |
|---|---|
| 命名空间 | `agents/{agent}/users/{uid}/skills`（DB 内段分隔符 0x1F，即 `agents␟{agent}␟users␟{uid}␟skills`） |
| item_key | `/{技能名}/{相对路径}`（前导斜杠，如 `/demo-a/SKILL.md`） |
| 技能判定 | 目录须含 `SKILL.md`（`WorkspaceReader.listUserSkills`，`WorkspaceReader.java:280`） |
| 元数据 | 任一路径段以 `.` 或 `_` 开头即不算技能（`.archive`/`_drafts`/`.audit`/`.usage.json`） |
| 删除标记 | `/{技能名}/.deleted`（tombstone，`WorkspaceReader.java:268`、`:537`） |
| 写入栅栏 | `/{技能名}/.admin-override`（管理面 PUT/下发时写入，回写侧命中即跳过同名技能；`WorkspaceReader.java` 的 `markUserSkillAdminOverride`/`isUserSkillAdminOverride`/`clearUserSkillAdminOverride`） |
| 版本 | `agent_fs.version` 每次写入自增，`userSkillFileVersion`（`WorkspaceReader.java:436`） |

命名空间与 key 由 `WorkspaceReader.skillNamespaceFor` / `skillFileKey`（`WorkspaceReader.java:80-93`）唯一负责，管理 API、调试页、沙箱回写、非沙箱 SDK 路由共用同一实现，禁止手拼——手拼会让写入落在 SDK 读不到的命名空间。两条硬约束见 `UserSkillService.java:129-135`：控制字符（含 0x1F）不得进入命名空间段（JdbcStore 直接抛异常），userId 必须与 `PathSafe.sanitize` 幂等等价（框架 `RemoteFilesystemSpec` 用原始 userId，不等价则管理面写的 L4 推理侧读不到）。

## 3. 接口语义（UserSkillController.java，前缀 `/skills/users`）

| 方法 | 路径 | 语义 | 实现要点 |
|------|------|------|----------|
| GET | `/skills/users` | 存在 L4 覆盖的用户索引 | `UserSkillService.listUsers` 下推 SQL 聚合（`namespace_path LIKE` 前缀已转义 `%`/`_`）；用户数 >5000 或行数 >100000 触顶时带 `truncated=true` 并丢弃可能只取到一半的末位用户；**查询失败（SQL/连接不可用）500**，不降级成 200 + 空列表（`UserSkillController.java:89-107`） |
| GET | `/skills/users/{userId}` | 某用户个人技能列表 | 返回 `files`/`bytes`/`version`/`hasPackageBaseline`/`adminOverride` 与 `tombstones`（已删除但标记仍在的技能，技能本身已不在 L4 列表里）；KV 枚举失败 **500**，不降级成空列表（`UserSkillController.java:113-130`） |
| GET | `/skills/users/{userId}/{name}` | 技能文件内容（`?file=` 相对路径，默认 `SKILL.md`） | L4 优先、无覆盖回落包内基线；`source=user/package` 与 `files`/`version`/`hasUserOverride` **同源**（避免「source=package 却带 L4 文件清单」把包内内容回填成个人覆盖），`userOverrideExists` 单独表达该用户是否另有覆盖；KV 读失败 500，两侧都不存在 404 |
| PUT | `/skills/users/{userId}/{name}` | 新建/覆盖该用户 `SKILL.md` | 返回 `action=created/updated` + `version`；>100KB → 413；写入同时**置写侧栅栏** `/{name}/.admin-override`（沙箱回写据此跳过同名技能，防止管理面写入被同代容器内旧副本改回）；成功消息按档位区分（沙箱档明确提示不回注容器 + 栅栏代价） |
| DELETE | `/skills/users/{userId}/{name}` | 删除该用户全部 L4 覆盖文件 | 返回 `deletedFiles` + `hasPackageBaseline` + `tombstone`（标记名 + 清除方式），并清除写侧栅栏；写 tombstone 防回写复活；无覆盖 → 404 |
| POST | `/skills/users/{userId}/{name}/sync-from-package` | 包内同名技能整目录下发为个人版本 | 全量替换 + 差集清理 + 失败回滚 + 置写侧栅栏；非 UTF-8 文件列入 `skipped`；包内无此技能 → 404 |
| GET | `/debug/user-skills` | 同上索引（调试页数据源） | `DebugApiController.java:206`，与 `/skills/users` 同一 `listUsers`，带 `truncated`；查询失败同样 500（不返回 200 + 空列表，否则调试页会静默显示 0 user(s)） |

路由消歧（集群实测口径，`UserSkillController.java:32-36`）：`GET /skills/users` 单段字面量不与既有路由冲突（`SkillManageController` 无 `GET /skills/{name}` 映射）；但 `userId` 恰为 `content` 时，`/skills/users/{userId}` 与 `GET /skills/{name}/content` 同时匹配，由更具体的后者命中（返回包内技能内容）。这是既有路由下的窄边界，索引、写入、删除路径不受影响（E2E 3.5 固化了该口径）。

## 4. 删除与回落语义

- `DELETE` 只删 L4 覆盖（逐文件 key 删除，`WorkspaceReader.deleteUserSkill`，`WorkspaceReader.java:463`），**不动包内 L2**；
- 删除前先写 tombstone：沙箱档同代容器内 `/workspace/skills` 副本仍存活，`syncBack` 据此跳过同名技能，否则「删除接口成功、紧跟 GET 也是包内基线，用户再发一条消息覆盖就复活」（E2E 9.9 为该语义的集成断言）；
- `hasPackageBaseline` 判定要求包内目录含 `SKILL.md`（`UserSkillService.java:594`）：有则下一轮回落该基线，无则该技能消失——只看目录存在会把「无 SKILL.md 的目录」标成有基线，调试页与删除提示都与事实不符；
- 管理面重新写入（`UserSkillService.java:389`）或从包内下发（`UserSkillService.java:525`）会清除 tombstone，避免该技能被回写侧永久跳过；
- **tombstone 无 TTL、也没有用户侧清除路径，且管理面此前完全不可见**（列表/明细都不暴露）：管理员删除后，该用户在同代（及后续）容器内用 `skill_manage` 重建同名技能会被回写永久跳过（KV 不落库、容器换代即丢），用户以为技能已保存。现在三处显式暴露：
  1. `GET /skills/users/{uid}` 返回 `tombstones`（技能名 + 标记时间，`UserSkillService.listTombstones` ← `WorkspaceReader.listUserSkillTombstones`）——删除后技能已不在 `skills` 列表里，只能由该字段看到；
  2. `DELETE` 响应带 `tombstone`（标记名 + 清除方式）且 `message` 写明「重建同名技能不会被回写落库，需管理面重新写入或从包内下发清除标记」；
  3. 调试页在技能表格下方渲染黄色提示块（后果 + 清除方式 + 标记时间），并在执行「删除」的确认框提示中保持一致口径。
- 与删除对称，**管理面写入也会在 KV 留下栅栏** `/{name}/.admin-override`（详见 §6.4），列表用 `adminOverride` 字段暴露。

## 5. 下发语义（sync-from-package）

`POST /skills/users/{uid}/{name}/sync-from-package` 把包内（L2）同名技能整目录**以包内清单为准全量替换**为该用户个人版本（`UserSkillService.java:438`）：

1. 包内目录不存在 → 404；包内缺 `SKILL.md` → 400（无主文件的目录不算技能）；
2. 边界：单文件 ≤100KB（`MAX_CONTENT_BYTES`，`UserSkillService.java:49`）、文件数 ≤200（`MAX_SYNC_FILES`，`:51`）、`技能名 + 相对路径 ≤253`（`item_key VARCHAR(255)` 减去 key 里两个 `/`，`:57`）——越界在入口 400，不落到 KV 写入 500；
3. `SKILL.md` 先写且失败即整体失败，其余文件随后按序写入；
4. **差集清理**：包内已不存在的旧文件（如换版后删掉的 `scripts/`）从 L4 删除，保证个人目录 = 包内目录（否则旧资源仍被 L4 覆盖可见）；
5. 任一环节失败 best-effort 回滚（恢复旧内容 / 删除本次新建键 / 还原被清理文件，`rollbackSync`，`UserSkillService.java:537`），不留「有 SKILL.md 缺资源」的半份覆盖；
6. 非 UTF-8/二进制文件显式跳过并列入响应 `skipped`（KV 只存字符串，静默替换 U+FFFD 等于下发即损坏数据）。

## 6. 沙箱写回缺陷与修复（本次核心）

### 6.1 根因

沙箱档（`SANDBOX_ENABLED=true`）下 SDK 用 `SandboxBackedFilesystem` 覆盖 `RemoteFilesystemSpec` 的 KV 路由（SDK 2.0.3 jar 内 `io/agentscope/harness/agent/filesystem/sandbox/SandboxBackedFilesystem.class`），整个工作区（含 `skills/`）的文件系统换成容器内 `/workspace`：

- `skill_manage` 的 workspace-writable 技能仓库（mainDir=`skills`）把 L4 写进容器 `/workspace/skills/{name}/SKILL.md`；
- 修复前 `WorkspaceSyncService.syncBack` 只回写 `MEMORY.md` 与 `memory/*.md`（运行时文件还用裸 `List.of(userId)` 命名空间），`skills/` 从未回写；
- 结果：沙箱会话里「技能当轮可用」，但 `agent_fs` 永远没有 L4 行，容器 TTL 到期后彻底丢失，管理面/调试页也看不到该技能。

### 6.2 修法（四处，均在既有文件内扩展）

1. **回写依赖换 `WorkspaceReader`**：`AgentScopeConfig.java:123` 的 `workspaceSyncService` Bean 改为注入 `WorkspaceReader`（`OpenSandboxFilesystemSpec` 的调用点 `AgentScopeConfig.java:109` 不变），回写命名空间与读取侧同源；
2. **新增 `syncUserSkills`**（`WorkspaceSyncService.java:132`）：`listDirectory("/workspace/skills")` 列技能目录 → `collectSkillFiles` 递归展开（`WorkspaceSyncService.java:224`，跳过 `.`/`_` 段、深度 ≤5、文件数 ≤200、单文件 ≤100KB，超限告警跳过不阻塞）→ 逐文件 `WorkspaceReader.writeUserSkillFile`（`WorkspaceReader.java:414`）；`SKILL.md` 先写且写失败即中止该技能（避免留下「有资源无主文件」的孤儿行——这类行 `listUserSkills`/`deleteSkill` 都以前提 SKILL.md 判定，管理面既看不到也删不掉）；
3. **运行时文件命名空间对齐**：MEMORY.md/memory 回写改走 `WorkspaceReader.writeWorkspaceFile`（`WorkspaceReader.java:183`，命名空间 `agents/{agent}/users/{uid}`），修掉原先写裸 userId 命名空间的落点错误；
4. **删除语义只归管理面**：回写前查 tombstone（`isUserSkillDeleted`，`WorkspaceSyncService.java:169`）命中即跳过；**不做差集删除**——新代容器可能只投影了包内 L2 而没有 L4 副本，按差集删 KV 会误删用户覆盖（数据丢失），故「沙箱内删除技能」当前不解释为删除 L4（边界说明见 `WorkspaceSyncService.java:33-36`）；回写整体保持在 try/catch 内（`WorkspaceSyncService.java:58-71`），失败只告警，与既有 `stop()` fail-soft 语义一致。
5. **写入侧对称仲裁（admin-override 栅栏，本次补）**：删除有 tombstone 防护，写入此前没有——管理面 PUT 只写 KV（不回注容器），而同代容器内的副本是旧的，下一次 call 结束时 `syncBack` 会把 KV 改回容器版本，PUT 静默失效。修法：`UserSkillService.writeSkill`/`syncFromPackage` 写完即置 `/{name}/.admin-override`（`WorkspaceReader.markUserSkillAdminOverride`），`WorkspaceSyncService.syncOneSkill` 命中即跳过同名技能（tombstone 判定优先）；`deleteUserSkill` 删技能时一并清除栅栏。栅栏的代价（容器内 skill_manage 修改在清除前不落库）与状态经接口响应与调试页显式下发，见 §4/§6.4。

### 6.3 不采纳的备选（记录以免重复评估）

`HarnessAgent.Builder.filesystemRoute("skills", kvBackedFilesystem)` 让 `skills/` 绕过容器直走 KV（SDK 2.0.3 支持：`javap -classpath agentscope-harness-2.0.3.jar io.agentscope.harness.agent.HarnessAgent$Builder` 实测存在 `filesystemRoute(String, AbstractFilesystem)` 与 `filesystemRoutes` 字段，jar 内亦有 `io/agentscope/harness/agent/filesystem/RoutedSandboxFilesystem.class` 负责按路由分发）。不采纳原因：会改变技能脚本在容器 `.skills-cache` 的投影与执行路径，并与 `workspaceProjection` 注入路径相互影响，风险高于回写方案；如将来要彻底去掉「容器内中间态」再单独评估。

**按「容器内文件 mtime 晚于标记时间」放行回写**（栅栏命中时仍允许更新的覆盖落库）：技术可行（`EntryInfo.getModifiedAt()` 可用），**不采纳**——容器启动/投影可能重写 `/workspace/skills` 下文件（本机无集群，未验证投影是否 touch SKILL.md），一旦投影刷新 mtime，命中标记后反而会把包内 L2 或旧副本写进 L4，把删除与写入的仲裁一起架空；且 mtime 依赖容器与框架两侧时钟可比，回写侧拿到的只是 execd 口径的时间。当前用「命中即跳过」的确定性判定，代价（容器内修改在标记清除前不落库）已由响应文案与调试页显式暴露。

**同一 uid 容器换代后自动清标记**：回写侧拿不到「容器代」标识（沙箱句柄不向 KV 暴露代际信息），用时间差推断会让标记语义随部署节奏漂移，不做。

### 6.4 生效范围分档（运维口径，务必区分）

| 档位 | 管理面写入/删除 | 会话内 `skill_manage` 写入 |
|------|----------------|---------------------------|
| 非沙箱（`SANDBOX_ENABLED=false`） | 直接写 agent_fs L4，推理读同一份，**下一轮会话生效** | 直接落 agent_fs（原本即正确） |
| 沙箱（`SANDBOX_ENABLED=true`） | 只写 agent_fs KV，**不会回注容器**；需容器换代或「会话开始物化 L4」能力（尚未实现）才对该用户会话生效。写入另置栅栏 `/{name}/.admin-override`：**同代容器内旧副本在下次 call 结束时不会把该 KV 写入改回容器版本**（无栅栏时会——容器里那份是旧副本，而 §6.4 已说明容器不物化 L4，会话读的就是容器内副本，「最新的」大概率不是管理面写的），代价是该技能在容器内用 skill_manage 的后续修改在栅栏清除前也不落库 | 写容器 `/workspace/skills` → 每次 call 结束由 `syncBack` 回写 L4（本次修复）；命中 `/{name}/.deleted` 或 `/{name}/.admin-override` 时跳过同名技能 |

写/删/下发接口的成功消息按档位区分（`UserSkillController.java:185-200`、`:233-256`），避免「显示已生效、实际不回注容器」的运维误判；PUT/DELETE 的提示同时说明对应标记的后果与清除方式。

**回写仲裁一览（沙箱档）**——管理面的 KV 与容器内副本谁说了算，只有两种显式标记能改变默认（容器 → KV）方向：

| 标记键 | 写入方 | 清除方 | 生效期间的用户可见后果 |
|--------|--------|--------|------------------------|
| `/{name}/.deleted` | 管理面 DELETE | 管理面 PUT / 从包内下发 | 该用户在同代（及后续）容器内重建同名技能不落库；列表 `tombstones` + 删除响应 `tombstone` 字段 + 调试页提示块显式暴露 |
| `/{name}/.admin-override` | 管理面 PUT / 从包内下发 | 管理面 DELETE（随之清除）；下次管理面写入会覆盖为新的时间戳 | 管理面内容不会被容器内旧副本改回；该技能在容器内的 skill_manage 修改不落库；列表 `adminOverride` 字段 + 调试页「管理面栅栏」徽标暴露 |

标记值均为写入时间毫秒（仅服务现场核对与调试页展示，判定只看存在性）；两者都以 `.` 开头，属元数据段，不进技能文件清单、不算技能。集成层断言见 `e2e/user-skill-admin-e2e.sh` E9（9.1~9.12）。

## 7. 调试页

Skills 模块新增「用户技能（个人覆盖 L4）」区块（`static/debug/modules/skills.js:230` 起）：用户索引下拉（`/debug/user-skills`）→ 该用户技能列表 → 明细弹窗（标 `source=user/package`）→ 编辑保存（PUT，带字节数提示）→ 删除（提示是否回落基线）→ 从包内下发。前端接口封装见 `static/debug/js/api.js:85-96`；加载序号 `userSkillSeq`（`skills.js:7`）保证并发多次「加载」只认最后一次响应。

本轮补的三处可见性/提示（此前调试页把它们静默丢了）：

- **索引触顶截断**：读 `data.truncated`，命中时把概览改写为 `≥N user(s)（索引触顶截断，仅前 N 个，其他用户请手工输入 userId）` 并用 `--yellow` 高亮，同时给下拉加 `title`——满足 `UserSkillService.UserSkillIndex` javadoc 的「调试页需提示」契约（此前全仓无人读 truncated，用户数 >5000 时下拉被当成全集）；
- **索引接口失败**：`/debug/user-skills` 失败（500）时概览显示 `user index unavailable: <原因>` 并标红，而不是静默显示 `0 user(s)`（手填 userId 仍可用）；
- **回写仲裁标记**：技能行带 `adminOverride` 时显示「管理面栅栏」黄色徽标（提示容器内修改不落库），列表下方渲染 tombstone 提示块（技能名 + 删除时间 + 「重建不会落回库、需管理面重写/从包内下发清除」）。

## 8. 验证记录

**单测**（2026-09-23 实跑）：

```bash
# 全量（agent-framework 目录）
mvn -o -B test
# → Tests run: 825, Failures: 0, Errors: 0, Skipped: 4 ... BUILD SUCCESS

# 本次交付相关五类（子集，用于快速回归）
mvn -o -B test -Dtest='UserSkillServiceTest,UserSkillControllerTest,WorkspaceSyncServiceTest,WorkspaceReaderTest,DebugApiControllerTest' -DfailIfNoTests=false
# → Tests run: 128, Failures: 0, Errors: 0, Skipped: 0 ... BUILD SUCCESS
```

`WorkspaceSyncServiceTest`（25 用例，fake OpenSandbox files + InMemory/Jdbc 双 BaseStore）覆盖回写关键路径：新建技能 → KV 出现 `agents/{agent}/users/{uid}/skills` 下 `/{name}/SKILL.md`；已有 L4 被覆盖；元数据目录/文件不落库；单文件与文件数超限跳过；条目目录被误判成文件时回落递归；列举失败容错；KV 中已有、沙箱内不存在的技能**不被删除**；带 tombstone 的技能跳过回写（以及 tombstone 优先于栅栏）；**带 `admin-override` 栅栏的技能跳过回写（KV 保留管理面内容）且命中即提前返回（不读文件）**；`SKILL.md` 写失败中止该技能；memory 回写落 `agents/{agent}/users/{uid}` 命名空间（不再落裸 userId）。`WorkspaceReaderTest`（22 用例）覆盖命名空间/key 形态（前导斜杠）、版本自增、删除计数与 `-1` 失败语义、tombstone 写入与重创建清除、**栅栏写读清（删除时一并清除、栅栏不算技能文件）、tombstone 枚举（技能名 + 时间戳，删除后仍可枚举）**。`UserSkillServiceTest`（35 用例）另覆盖**索引查询失败抛 IllegalStateException（不降级空列表）**、**写入/下发置栅栏、删除清栅栏、`listTombstones` 视图**；`UserSkillControllerTest`/`DebugApiControllerTest` 覆盖索引失败 500、列表 `tombstones`/`adminOverride` 字段、删除响应 `tombstone` 与栅栏提示文案。

**UI 门禁（Playwright，agent-framework/e2e/tests/ui.spec.ts）**：`U-SK5` 索引 `truncated=true` 必须显式提示（概览文案 + 下拉 `title`）、`U-SK6` 索引接口失败必须显示 `user index unavailable`（不静默 0 user(s)）、`U-SK7` 删除标记提示块与「管理面栅栏」徽标必须渲染（本机未实跑，需 MySQL/Redis + chromium 环境）。

**E2E**：`e2e/user-skill-admin-e2e.sh`——非沙箱档 37 断言（PUT 个人覆盖 → 明细 `source=user` → 索引 `/skills/users` 与 `/debug/user-skills` → A2A 会话生效性与用户隔离 → DELETE 回落包内基线并逐字节比对 → sync-from-package 含 `scripts/` 资源逐字节比对 → 负例 400/404/413）；`SANDBOX=1` 跑管理面 KV 用例并追加 E8 8.1~8.6（容器内 `skill_manage` → `agent_fs` 出现 L4 行、命名空间为 `agents/…/users/{uid}/skills`、内容含会话内 marker、其他用户命名空间为空、不再落裸 userId 命名空间）与 **E9 9.1~9.12（回写仲裁：DELETE 后同一 uid 再发消息，KV 不得出现 SKILL.md 行（tombstone 防复活）；PUT 后再发消息，KV 内容必须仍是管理面 marker（admin-override 防被容器内旧副本改回）；删除响应暴露 `tombstone.name` 与后果文案）**。本文件记录脚本与断言口径，集群实跑前置（新镜像导入 kind 节点）见 [../../e2e/AGENTS.md](../../e2e/AGENTS.md)。

**缺陷归因命令**（现场核对用，`{uid}`/`{技能名}` 按实际替换；`agent_fs` 段分隔符 0x1F）：

```sql
select replace(namespace_path, 0x1F, '|'), item_key, version
  from oaf_checkpoint.agent_fs
 where namespace_path like concat('agents', 0x1F, '%', 'users', 0x1F, '{uid}', 0x1F, 'skills', 0x1F)
   and item_key like '/{技能名}/%';
```

修复前集群实测 0 行（技能只活在容器里；缺陷定位时的核对记录），修复后应出现 `…|users|{uid}|skills|` 下的 `/{技能名}/SKILL.md`（与 E8 断言 8.2/8.3 同口径）。

## 9. 安全与边界

- 端点无鉴权，与既有 `PUT /skills/{name}/content` 同级：能访问业务入口者即可改**该 agent 命名空间内**的个人技能，跨 agent 不可达（`UserSkillController.java:47-48`）；
- 输入全部走名称/路径校验（`isValidUserId`/`isValidSkillName`/`isValidSkillFilePath`/`isValidSkillIdentity`，`UserSkillService.java:149-196`），禁路径穿越、控制字符、超长组合；
- 读取失败显式 500（不把「存储不可用」降级成 404 或空集）；**用户索引（SQL 聚合）查询失败同样 500**——降级成 200 + 空列表会让 DB 抖动看起来像「没有任何用户有个人技能」（调试页此前会静默显示 0 user(s)）；索引触顶带 `truncated=true` 不静默返回子集，调试页据此提示；
- 单文件 100KB（413）、下发文件数 200、索引扫描 5000 用户 / 100000 行——四处上限均为防单点写入或枚举把内存/表打爆；沙箱回写侧另设技能目录深度 ≤5 与单技能文件数 ≤200（与下发同口径，`WorkspaceSyncService.java:42-46`）。

## 10. 后续补齐（2026-09-24）：会话开始物化 L4 + 按用户合并 L4

§6.4 表中「沙箱档管理面写入需『会话开始物化 L4』能力（尚未实现）」与「按 session userId 合并 L4」已实施。

### 10.1 会话开始物化 L4（沙箱档生效）

- `WorkspaceReader.materializeUserSkills(osbSandbox, userId)`：枚举 L4（复用 `listUserSkills`）→ 逐文件经 `osbSandbox.files().write(WriteEntry)` 写容器 `/workspace/skills/{name}/{相对路径}`；`/{name}/.deleted` 跳过、`/{name}/.admin-override` 照写（栅栏语义即管理面内容优先）、**只写不删**；单技能文件数 ≤200、单文件 ≤100KB。
- `OpenSandbox.materializeUserSkills()`（每实例幂等，失败 fail-soft）；调用点：`SandboxUserKeyMiddleware.onAgent`（acquire 之后、agent 执行前，主路径）与 `OpenSandbox.doExec`（兜底）。
- 效果：管理面 PUT / 从包内下发在该用户**下一个 turn** 投影进容器生效；DELETE 写 tombstone 后下一次物化不再写回（容器内旧副本仍需容器换代清除，见 §6.2.4）。

### 10.2 /skills/available 与 @Skill 注入按 session userId 合并 L4

- `SkillCatalogService.availableSkills(userId)`：全局目录（已启用）∪ 该用户 L4（同名 L4 描述覆盖）。
- `SkillInjectionService.injectSkillReferences(message, userId)` / `parseSkillReferences(message, userId)`：启停集合并入 L4；内容读取 L4 优先、无覆盖回落包内基线。
- `SkillManageController` 的 `/skills/available`、`/skills/parse-refs` 读网关注入的 `X-User-Id`（次选 `?userId=`）；`ChatStreamController.chat` 以会话 userId 调用注入；debug 页 `loadAvailableSkills` 带当前 userId。

### 10.3 验证与边界

- 新增单测：`WorkspaceReaderTest`（materialize 2 例）、`SkillCatalogServiceTest`（L4 合并 1 例）、`SkillInjectionServiceTest`（L4 注入 1 例）；全量 `mvn -o test` 908 通过 / 0 失败（跳过 4）。
- 边界：物化使用沙箱会话的 userKey（`SandboxUserKeyMiddleware` 取 `ctx.userId`，缺省 `sessionId`）；Channel 链路框架 `RuntimeContext.userId` 为网关 peer（=会话 id），需网关保证 peer 与 `X-User-Id` 一致，管理面 L4 才会与沙箱物化 key 对齐。
