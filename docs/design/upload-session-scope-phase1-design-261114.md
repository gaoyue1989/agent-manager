# 上传注入会话隔离 + 交付通道加固 一期设计-261114

状态：**已实施（2026-11-14，feat/upload-session-scope-present-harden 分支，issue #345 一期）**；修订记录：v2 —— review 发现 6 项问题并修订：upload sessionId 必填 → bind-at-use（消 e2e 三套件破坏）；状态机统一 pending；spool 目录避开 /tmp；跨 session 引用定语义；delete 路径 sessionId 取数补实。文件名 RFC 7578 解码已由先行 PR #344 更窄实现（仅 `%22` 逆变），不在本期范围。实施偏差见 §6。

## 1. 背景与问题（均已实测钉死）

| # | 缺陷 | 证据 |
|---|------|------|
| P1 | 沙箱撞名改名 vs 提示路径冻结 → agent `file_not_found`。提示在请求时刻按 `file_asset.workspace_path`（或原名）冻结，注入在沙箱 acquire 时才执行且按历史改名加 `_1/_2` → 错位。容器销毁全量重放、后缀滚动（实测到 `_4`） | 2026-11-14 真实环境 5 轮对话复现；**已由 #343（b17716f）先行修复** |
| P2 | pending 队列是用户级（`listPending`：`user_key=? OR session_id=?`），跨 session 全量重放；旧 session 文件对新 session 是无引用路径的纯噪音；历史 workspace_path 只增不减 = P1 放大器 | `FileAssetStore.java` listPending；**本期修复** |
| P3 | `present_file` 先整读后拒超限：`readByteArray` 全量进堆之后才查 `FILE_PRESENT_MAX_MB`。超限 present 单请求 OOM 风险；>50MB 产物无任何交付旁路 | `FileTools` 步骤序；**本期修复** |
| P4 | 双引号文件名保真丢失（客户端 `%22` 转义入库不解码） | 2026-11-14 实测；**已由 #344（f3f7ec2）先行修复** |

## 2. 目标 / 非目标

**目标**
1. 上传 pending 作用域 session 化（消 P2，与非沙箱 session 目录语义对齐，消两种模式的架构分叉）
2. present_file stat 前置快速拒绝 + 流式拷贝上限中止（消 P3 OOM 路径，堆占用恒定）

**非目标（明确不做）**
- 50MB 上限默认值不动（流式化后提顶只是配置变更，另议）
- fetch_file 按需加载、生成文件跨容器恢复提示（二期；本期建成的 readStream↔writeBounded 通道 + workspace_path 预分配/绑定语义即二期反向复用体）
- 非沙箱 present_file 流式化（dev/debug 形态）。不做的原因（已核实）：非沙箱 filesystem=RemoteFilesystemSpec **不提供 Shell**（`HarnessAgentFactory` 装配分支），文件来源只有 write_file（LLM 输出上限）与上传件（≤20MB）——大文件 OOM 场景构造不出来，而流式化需改 `WorkspaceReader` 共享读通道，无风险消减收益。**守护不变量：`FILE_UPLOAD_MAX_MB` 必须保持 < `FILE_PRESENT_MAX_MB`**（否则非沙箱"再 present 大上传件"复现先读后拒），实现于两配置注释 + FileControllerTest 单测钉住
- A2A FilePart 附件、S3 multipart、50MB 上限定标（二期边界）

## 3. 设计

### 3.1 A：上传作用域 session 化（P2）

**上传侧**
- `session_id` 保持可选（上传先于会话是真实流）；**首次被 chat 引用时绑定**（bind-at-use）
- 上传状态机统一：**两种模式 upload 一律 status=pending**（消非沙箱"upload 即 injected 但文件未落盘"的状态失真）；实际交付时置 injected（非沙箱=chat 写盘后，沙箱=acquire 注入后）
- pending 配额（uploadMaxPending）**维持 user 维度计数**（防滥用按用户算，与注入作用域解耦）
- `FileController.isSandboxMode` 依赖随之退役

**绑定 + 路径分配（SSOT）**
- `UploadWorkspaceInjector.assignWorkspacePaths(fileIds, userKey, sessionId)` 取代 `ensureWorkspacePath`，chat 请求路径、提示构造之前执行：
  1. 归属校验：上传记录 userKey ≠ chat 用户 → 跳过+warn（他人文件不可引用）
  2. bind-at-use：`session_id IS NULL` 的行条件写绑定本会话（`FileAssetStore.bindSession`，WHERE session_id IS NULL 防跨 session 并发抢绑）；已绑定异 session → 跳过+warn（所有权=首引用会话）
  3. 路径分配：`workspace_path IS NULL` 的行按占用集合（**同 session 的 DB 行集** + 本批次已分配）唯一化分配并立即回写（分配即预留）
- 同 session turn 由 TurnLeaseGuard 串行 → 并发分配天然无竞态
- 提示区块 `buildContentBlocks` 只收 bound 列表（跳过项不出现在提示中）

**注入侧（沙箱）**
- `OpenSandbox` 新增 `sessionId` 实例绑定（createNew/wrapReused/resume 三路径捕获，middleware 兜底）；`injectPendingUploads` 严格 `listPending(sessionId)` 单条件过滤；**身份缺失跳过注入**（行保持 pending，下 turn 重试）——user 维度兜底是泄漏根源，绝不回退
- 写入路径 = 行内 `workspace_path` 原样落盘（#343 语义不变）→ 容器销毁重建后重放路径不变（幂等，`_N` 漂移消失）
- `resetInjectedToPending(sessionId)` session 单条件：createNew 被动兜底 / delete 主动回滚 / middleware 换代 reset 三处对齐；sid 缺失跳过（createNew 兜底覆盖）

**非沙箱**：`injectToWorkspace` 取预留路径；status=injected 幂等跳过；pending 行目标已在磁盘存在（agent 手写同名防覆盖）→ 按 DB 行集 ∪ 目录现状重分配；写盘后 `markInjected`。本地 ls 判重（uniqueWorkspacePath）退役，统一 DB 集合

**存量与清理**
- session_id NULL 的 pending 行 = 上传后从未被引用，自然不注入；**pending 超期清理**：`cleanupExpiredUploads` 不再豁免 pending（7 天同口径，防上传未发送文件永久占额）
- `origin=generated` 行不受影响；存量 NULL-session injected 行由 7 天保留期自然回收

### 3.2 B：present_file 交付通道加固（P3）

**stat 前置（快速拒绝）**
- `OpenSandbox.statWorkspaceFile(relPath)`：`files().listDirectory(父目录)` 按 basename 定位 `EntryInfo.getSize()`（结构化元数据：无 shell 依赖、无转义面、mock `/directories/list` 原生支持；评审稿的 `wc -c` exec 方案据此替换）
- 超限直接返回错误，不发起传输；失败降级返回 -1（正确性由拷贝上限兜底）

**流式拷贝 + 上限中止（正确性主干，不依赖 stat）**
- 读：SDK `readStream(path)`（`/files/download` octet-stream 全量流；1.0.18 jar javap 实证存在）→ `OpenSandbox.openWorkspaceStream`
- 写：`FileStorage.writeBounded(key, InputStream, maxBytes, contentType)`：流式拷贝越 maxBytes 即抛 `StorageOverflowException` 且不留任何目标对象（local：兄弟 tmp 计数写、越限删 tmp 不 move；S3：spool 文件计数拷贝、越限删 spool 不 PUT → 成功按精确 size 单 PUT）；成功返回落盘字节数
- **spool 目录默认 `{workspaceBase}/tmp/file-transfer/`，`FILE_SPOOL_DIR` 可配——不用 `/tmp`**（业务 Pod /tmp 不可写实测）；local 后端沿用目标同目录 tmp（原子 move 不跨设备）
- 堆占用恒定（缓冲区级）；`write` 收敛为 writeBounded 的无界委托（单一写路径）
- 选型规则：**源有界 → byte[]**（上传注入/base64/KV 读保持现状），**源无界（沙箱 shell 生成）→ 流式**

### 3.3 C：二期复用面

`readStream` ↔ `writeBounded` 通道 + `workspace_path` 预分配/绑定语义即二期 `fetch_file`（上传按需 + 产物恢复）的直接复用体，本期实现保持这两个面通用、不被 present_file 专用逻辑污染。

## 4. 测试

**单元（2138 例全绿）**
- FileAssetStoreTest：listPending session 单条件过滤；bindSession 条件写（NULL 绑定成功/被抢绑 false）；resetInjectedToPending session 单条件
- UploadWorkspaceInjectorTest：assign 绑定+分配/幂等/同 session 撞名 `_1`/异 session 跳过/userKey 不匹配跳过/抢绑失败跳过；injectToWorkspace injected 跳过/磁盘撞名重分配（@TempDir 隔离）
- OpenSandboxTest：注入按 session 过滤（原 3 用例改造）+ 无 session 绑定跳过 + ThreadLocal 兜底
- FileToolsTest：stat 超限快速拒绝（verify 无 readStream）；writeBounded 越限拒绝无落库；流式登记 size 正确；非沙箱 byte[] 回归
- LocalFileStorageTest：writeBounded 正常返回字节数/越限中止无残留/关源流契约
- FileControllerTest：upload 恒 pending 断言 + cap 不变量钉子；S3EnvBindingTest 锁 @ConstructorBinding 绑定链路

**e2e（api-sandbox 新增，既有套件零改动通过为显式门禁）**
- X19 会话隔离：同 uid 双 session，session B 探针 404 session A 的上传件
- X20 容器换代幂等重放：mock DELETE 沙箱 → 下 turn 同路径重注入、无 `_N` 漂移
- X21 交付通道：mock 直写 51MB → present stat 快速拒绝且 `/files/download` 零传输、无 file_ready；40MB → stat 通过 → 流式交付 → file_ready（size 一致）→ 下载字节一致；驱动机制 = 新 fixture `file-present.json`（file-deliver 派生 present+收尾两调用）+ mock-llm `ARGS_OVERRIDE` 运行时注入 `file_path`（`[E2E:file:present](路径)`）

**真实环境验收（kind + 真实 opensandbox-server，issue §验收）**
1. 原 bug 回归：同名文件二次上传+对话 → 提示路径 = 沙箱磁盘路径
2. 新 session 对话：旧 session 上传文件不出现
3. 容器销毁重建：同路径幂等重注入，无 `_N` 漂移
4. 60MB present：stat 快速拒绝、Pod 堆平稳
5. ≤50MB present 全链路交付下载

## 5. 风险与对策

| 风险 | 对策 |
|------|------|
| 镜像无 wc → stat 失败（评审稿方案） | 改用 listDirectory 结构化 stat（execd API，无 shell/coreutils 依赖）；仍失败降级 -1，拷贝上限兜底 |
| 非沙箱 agent 手写 uploads/ 同名 | 分配时目标存在性校验（DB 集合 ∪ 目录现状 → 重分配改名） |
| 上传后从未发送的文件占额 | pending 超期清理（7 天，与 injected 同口径） |
| delete 路径 sessionId 取数失败 | 实例绑定（create/wrapReused/resume 捕获）优先，ThreadLocal 兜底；均缺失跳过（createNew 被动兜底下 turn 收敛） |
| FileConfig 加组件波及按位构造测试 | 沿用本项目既有先例：16 参兼容构造器 + canonical `@ConstructorBinding`（与外层 record 9 参先例同款，S3EnvBindingTest 锁绑定链路） |

## 6. 实施记录与设计偏差（2026-11-14）

1. **stat 机制**：评审稿 `wc -c` exec → 实施 `listDirectory` + `EntryInfo.size`。理由：无 shell 注入面/无镜像 coreutils 依赖/mock 原生支持（`/directories/list` 返回 size）；语义不变（快速拒绝 + 失败降级）。
2. **归属校验**：assign 新增 userKey ≠ chat 用户跳过（评审稿未列）。上传/聊天同一 resolveUserKey（X-User-Id 优先），前端与 e2e 同 uid 传递，无兼容风险。
3. **bindSession 条件写**：评审稿 UPDATE ... IS NULL 语义落为返回 boolean 的条件写，抢绑失败方按异 session 跳过。
4. **注入身份缺失语义**：跳过注入（评审稿"未绑定行永不注入"的自然延伸）；A2A/invoke 链路（session 键=真实用户）无上传绑定，注入自然为空集。
5. **mock-llm 场景参数化**：X21 复用 `ARGS_OVERRIDE` 语义层整体重写（hitl-submit 先例），新增 `file-present` 场景直 present 任意沙箱路径。
6. 存量 NULL-session injected 行不迁移（不做兼容原则），由保留期自然回收。
7. **mock 基建两处契约修复**（X23 联调实测钉死）：① mock 沙箱 `/directories/list` 响应字段对齐真实 execd FileInfo 契约（sandbox-api 1.0.18 必填 `modified_at/created_at/mode`，旧字段 `mod_time/permission` 令 SDK 反序列化失败 → stat 恒降级）；② mock-llm ARGS_OVERRIDE 场景每次请求重写 tool_call id——录制件冻结 id 在同会话跨 turn 重复时，SDK 对重复 id 的 tool_use 短路复用历史 tool_result（工具不再执行）。
